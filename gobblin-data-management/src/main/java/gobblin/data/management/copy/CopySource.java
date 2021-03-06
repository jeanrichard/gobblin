/*
 * Copyright (C) 2014-2016 LinkedIn Corp. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use
 * this file except in compliance with the License. You may obtain a copy of the
 * License at  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
 * CONDITIONS OF ANY KIND, either express or implied.
 */

package gobblin.data.management.copy;

import gobblin.configuration.ConfigurationKeys;
import gobblin.configuration.SourceState;
import gobblin.configuration.State;
import gobblin.configuration.WorkUnitState;
import gobblin.data.management.copy.extractor.FileAwareInputStreamExtractor;
import gobblin.data.management.copy.publisher.CopyEventSubmitterHelper;
import gobblin.data.management.dataset.Dataset;
import gobblin.data.management.dataset.DatasetUtils;
import gobblin.data.management.partition.Partition;
import gobblin.data.management.retention.dataset.finder.DatasetFinder;
import gobblin.metrics.GobblinMetrics;
import gobblin.metrics.Tag;
import gobblin.metrics.event.sla.SlaEventKeys;
import gobblin.util.PathUtils;
import gobblin.source.extractor.Extractor;
import gobblin.source.extractor.extract.AbstractSource;
import gobblin.source.workunit.Extract;
import gobblin.source.workunit.WorkUnit;
import gobblin.util.HadoopUtils;
import gobblin.util.WriterUtils;
import gobblin.util.guid.Guid;

import java.io.IOException;
import java.net.URI;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nullable;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;

import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;


/**
 * {@link gobblin.source.Source} that generates work units from {@link gobblin.data.management.copy.CopyableDataset}s.
 *
 */
@Slf4j
public class CopySource extends AbstractSource<String, FileAwareInputStream> {

  public static final String DEFAULT_DATASET_PROFILE_CLASS_KEY = CopyableGlobDatasetFinder.class.getCanonicalName();
  public static final String SERIALIZED_COPYABLE_FILE = CopyConfiguration.COPY_PREFIX + ".serialized.copyable.file";
  public static final String SERIALIZED_COPYABLE_DATASET = CopyConfiguration.COPY_PREFIX + ".serialized.copyable.datasets";
  public static final String WORK_UNIT_GUID = CopyConfiguration.COPY_PREFIX + ".work.unit.guid";

  /**
   * <ul>
   * Does the following:
   * <li>Instantiate a {@link DatasetFinder}.
   * <li>Find all {@link Dataset} using {@link DatasetFinder}.
   * <li>For each {@link CopyableDataset} get all {@link CopyableFile}s.
   * <li>Create a {@link WorkUnit} per {@link CopyableFile}.
   * </ul>
   *
   * <p>
   * In this implementation, one workunit is created for every {@link CopyableFile} found. But the extractor/converters
   * and writers are built to support multiple {@link CopyableFile}s per workunit
   * </p>
   *
   * @param state see {@link gobblin.configuration.SourceState}
   * @return Work units for copying files.
   */
  @Override
  public List<WorkUnit> getWorkunits(SourceState state) {

    List<WorkUnit> workUnits = Lists.newArrayList();
    CopyContext copyContext = new CopyContext();

    try {

      DatasetFinder<CopyableDataset> datasetFinder =
          DatasetUtils.instantiateDatasetFinder(state.getProperties(), getSourceFileSystem(state),
              DEFAULT_DATASET_PROFILE_CLASS_KEY);
      List<CopyableDataset> copyableDatasets = datasetFinder.findDatasets();
      FileSystem targetFs = getTargetFileSystem(state);

      for (CopyableDataset copyableDataset : copyableDatasets) {

        Path targetRoot = getTargetRoot(state, datasetFinder, copyableDataset);

        CopyConfiguration copyConfiguration =
            CopyConfiguration.builder(state.getProperties()).targetRoot(targetRoot).copyContext(copyContext).build();



        Collection<CopyableFile> files = copyableDataset.getCopyableFiles(targetFs, copyConfiguration);
        Collection<Partition<CopyableFile>> partitions = partitionCopyableFiles(files);

        for (Partition<CopyableFile> partition : partitions) {
          Extract extract = new Extract(Extract.TableType.SNAPSHOT_ONLY, CopyConfiguration.COPY_PREFIX, partition.getName());
          for (CopyableFile copyableFile : partition.getFiles()) {

            CopyableDatasetMetadata metadata = new CopyableDatasetMetadata(copyableDataset, targetRoot);
            CopyableFile.DatasetAndPartition datasetAndPartition = copyableFile.getDatasetAndPartition(metadata);

            WorkUnit workUnit = new WorkUnit(extract);
            workUnit.addAll(state);
            serializeCopyableFile(workUnit, copyableFile);
            serializeCopyableDataset(workUnit, metadata);
            GobblinMetrics.addCustomTagToState(workUnit, new Tag<>(CopyEventSubmitterHelper.DATASET_ROOT_METADATA_NAME,
                copyableDataset.datasetRoot().toString()));
            workUnit.setProp(ConfigurationKeys.DATASET_URN_KEY, datasetAndPartition.toString());
            workUnit.setProp(SlaEventKeys.DATASET_URN_KEY, copyableDataset.datasetRoot());
            workUnit.setProp(SlaEventKeys.PARTITION_KEY, copyableFile.getFileSet());
            computeAndSetWorkUnitGuid(workUnit);
            workUnits.add(workUnit);
          }
        }
      }
    } catch (IOException e) {
      throw new RuntimeException(e);
    }

    log.info(String.format("Created %s workunits", workUnits.size()));

    return workUnits;
  }

  /**
   * @param state a {@link gobblin.configuration.WorkUnitState} carrying properties needed by the returned
   *          {@link Extractor}
   * @return a {@link FileAwareInputStreamExtractor}.
   * @throws IOException
   */
  @Override
  public Extractor<String, FileAwareInputStream> getExtractor(WorkUnitState state) throws IOException {

    CopyableFile copyableFile = deserializeCopyableFile(state);

    return new FileAwareInputStreamExtractor(getSourceFileSystem(state), copyableFile);
  }

  @Override
  public void shutdown(SourceState state) {
  }

  protected FileSystem getSourceFileSystem(State state) throws IOException {

    Configuration conf = HadoopUtils.getConfFromState(state);
    String uri = state.getProp(ConfigurationKeys.SOURCE_FILEBASED_FS_URI, ConfigurationKeys.LOCAL_FS_URI);
    return FileSystem.get(URI.create(uri), conf);
  }

  private FileSystem getTargetFileSystem(State state) throws IOException {
    return WriterUtils.getWriterFS(state, 1, 0);
  }

  private Path getTargetRoot(State state, DatasetFinder<?> datasetFinder, CopyableDataset dataset) {
    Preconditions.checkArgument(state.contains(ConfigurationKeys.DATA_PUBLISHER_FINAL_DIR),
        "Missing property " + ConfigurationKeys.DATA_PUBLISHER_FINAL_DIR);
    Path basePath = new Path(state.getProp(ConfigurationKeys.DATA_PUBLISHER_FINAL_DIR));
    Path datasetRelativeToCommonRoot = PathUtils.relativizePath(
        PathUtils.getPathWithoutSchemeAndAuthority(dataset.datasetRoot()),
        PathUtils.getPathWithoutSchemeAndAuthority(datasetFinder.commonDatasetRoot()));
    return new Path(basePath, datasetRelativeToCommonRoot);
  }

  private void computeAndSetWorkUnitGuid(WorkUnit workUnit) throws IOException {
    Guid guid = Guid.fromStrings(workUnit.contains(ConfigurationKeys.CONVERTER_CLASSES_KEY) ?
        workUnit.getProp(ConfigurationKeys.CONVERTER_CLASSES_KEY) :
        "");
    setWorkUnitGuid(workUnit, guid.append(deserializeCopyableFile(workUnit)));
  }

  /**
   * Set a unique, replicable guid for this work unit. Used for recovering partially successful work units.
   * @param state {@link State} where guid should be written.
   * @param guid A byte array guid.
   */
  public static void setWorkUnitGuid(State state, Guid guid) throws IOException {
    state.setProp(WORK_UNIT_GUID, guid.toString());
  }

  /**
   * Get guid in this state if available. This is the reverse operation of {@link #setWorkUnitGuid}.
   * @param state State from which guid should be extracted.
   * @return A byte array guid.
   * @throws IOException
   */
  public static Optional<Guid> getWorkUnitGuid(State state) throws IOException {
    if (state.contains(WORK_UNIT_GUID)) {
      return Optional.of(Guid.deserialize(state.getProp(WORK_UNIT_GUID)));
    } else {
      return Optional.absent();
    }
  }

  /**
   * Serialize a {@link List} of {@link CopyableFile}s into a {@link State} at {@link #SERIALIZED_COPYABLE_FILE}
   */
  public static void serializeCopyableFile(State state, CopyableFile copyableFile) throws IOException {
    state.setProp(SERIALIZED_COPYABLE_FILE, CopyableFile.serialize(copyableFile));
  }

  /**
   * Deserialize a {@link List} of {@link CopyableFile}s from a {@link State} at {@link #SERIALIZED_COPYABLE_FILE}
   */
  public static CopyableFile deserializeCopyableFile(State state) throws IOException {
    return CopyableFile.deserialize(state.getProp(SERIALIZED_COPYABLE_FILE));
  }

  /**
   * Serialize a {@link CopyableDataset} into a {@link State} at {@link #SERIALIZED_COPYABLE_DATASET}
   */
  public static void serializeCopyableDataset(State state, CopyableDatasetMetadata copyableDataset) throws IOException {
    state.setProp(SERIALIZED_COPYABLE_DATASET, copyableDataset.serialize());
  }

  /**
   * Deserialize a {@link CopyableDataset} from a {@link State} at {@link #SERIALIZED_COPYABLE_DATASET}
   */
  public static CopyableDatasetMetadata deserializeCopyableDataset(State state) throws IOException {
    return CopyableDatasetMetadata.deserialize(state.getProp(SERIALIZED_COPYABLE_DATASET));
  }

  private Collection<Partition<CopyableFile>> partitionCopyableFiles(Collection<CopyableFile> files) {
    Map<String, Partition.Builder<CopyableFile>> partitionBuildersMaps = Maps.newHashMap();
    for (CopyableFile file : files) {
      if (!partitionBuildersMaps.containsKey(file.getFileSet())) {
        partitionBuildersMaps.put(file.getFileSet(), new Partition.Builder<CopyableFile>(file.getFileSet()));
      }
      partitionBuildersMaps.get(file.getFileSet()).add(file);
    }
    return Lists.newArrayList(Iterables.transform(partitionBuildersMaps.values(),
        new Function<Partition.Builder<CopyableFile>, Partition<CopyableFile>>() {
          @Nullable @Override public Partition<CopyableFile> apply(Partition.Builder<CopyableFile> input) {
            return input.build();
          }
        }));
  }

}
