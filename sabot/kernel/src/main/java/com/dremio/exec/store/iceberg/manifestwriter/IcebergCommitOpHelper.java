/*
 * Copyright (C) 2017-2019 Dremio Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.dremio.exec.store.iceberg.manifestwriter;

import static com.dremio.exec.store.iceberg.IcebergSerDe.deserializedJsonAsSchema;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import org.apache.arrow.vector.IntVector;
import org.apache.arrow.vector.VarBinaryVector;
import org.apache.arrow.vector.VarCharVector;
import org.apache.arrow.vector.complex.ListVector;
import org.apache.arrow.vector.complex.impl.UnionListReader;
import org.apache.arrow.vector.complex.reader.FieldReader;
import org.apache.arrow.vector.util.Text;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.GenericManifestFile;
import org.apache.iceberg.ManifestFile;
import org.apache.iceberg.ManifestFiles;
import org.apache.iceberg.io.CloseableIterable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.dremio.common.AutoCloseables;
import com.dremio.common.exceptions.UserException;
import com.dremio.common.expression.SchemaPath;
import com.dremio.exec.physical.config.WriterCommitterPOP;
import com.dremio.exec.record.TypedFieldId;
import com.dremio.exec.record.VectorAccessible;
import com.dremio.exec.store.OperationType;
import com.dremio.exec.store.RecordWriter;
import com.dremio.exec.store.dfs.IcebergTableProps;
import com.dremio.exec.store.iceberg.DremioFileIO;
import com.dremio.exec.store.iceberg.IcebergMetadataInformation;
import com.dremio.exec.store.iceberg.IcebergPartitionData;
import com.dremio.exec.store.iceberg.IcebergSerDe;
import com.dremio.exec.store.iceberg.SupportsIcebergMutablePlugin;
import com.dremio.exec.store.iceberg.model.IcebergCommandType;
import com.dremio.exec.store.iceberg.model.IcebergModel;
import com.dremio.exec.store.iceberg.model.IcebergOpCommitter;
import com.dremio.exec.store.iceberg.model.IcebergTableIdentifier;
import com.dremio.exec.store.metadatarefresh.committer.ReadSignatureProvider;
import com.dremio.exec.util.VectorUtil;
import com.dremio.io.file.FileSystem;
import com.dremio.io.file.Path;
import com.dremio.sabot.exec.context.OperatorContext;
import com.dremio.sabot.exec.context.OperatorStats;
import com.dremio.sabot.op.writer.WriterCommitterOperator;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.base.Stopwatch;
import com.google.common.collect.ImmutableSet;
import com.google.protobuf.ByteString;

/**
 * Helper class for flushing out commits
 */
public class IcebergCommitOpHelper implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(IcebergCommitOpHelper.class);

    protected final WriterCommitterPOP config;
    protected final OperatorContext context;
    protected VarBinaryVector icebergMetadataVector;
    protected IntVector operationTypeVector;
    protected VarCharVector pathVector;
    protected ListVector partitionDataVector;
    protected IcebergOpCommitter icebergOpCommitter;
    private final Set<IcebergPartitionData> addedPartitions = new HashSet<>(); // partitions with new files
    private final Set<IcebergPartitionData> deletedPartitions = new HashSet<>(); // partitions with deleted files
    private final Set<String> partitionPaths;
    private final Set<String> partitionPathsWithDeletedFiles = new HashSet<>();
    protected Predicate<String> partitionExistsPredicate = path -> true;
    private FileSystem fsToCheckIfPartitionExists;
    protected ReadSignatureProvider readSigProvider = (added, deleted) -> ByteString.EMPTY;
    protected List<ManifestFile> icebergManifestFiles = new ArrayList<>();
    protected boolean success;
    private final DremioFileIO dremioFileIO;

    public static IcebergCommitOpHelper getInstance(OperatorContext context, WriterCommitterPOP config) {
        if (config.getIcebergTableProps() == null) {
            return new NoOpIcebergCommitOpHelper(context, config);
        } else if (config.getIcebergTableProps().isDetectSchema()) {
            return new SchemaDiscoveryIcebergCommitOpHelper(context, config);
        } else {
            return new IcebergCommitOpHelper(context, config);
        }
    }

    protected IcebergCommitOpHelper(OperatorContext context, WriterCommitterPOP config) {
        this.config = config;
        this.context = context;
        this.dremioFileIO = new DremioFileIO(config.getPlugin().getFsConfCopy(), config.getPlugin());
        this.partitionPaths = createPartitionPathsSet(config);
    }

    public void setup(VectorAccessible incoming) {
      Stopwatch stopwatch = Stopwatch.createStarted();
        // TODO: doesn't track wait times currently. need to use dremioFileIO after implementing newOutputFile method
      IcebergTableProps icebergTableProps = config.getIcebergTableProps();

      final IcebergModel icebergModel;
      final IcebergTableIdentifier icebergTableIdentifier;
      final SupportsIcebergMutablePlugin icebergMutablePlugin = (SupportsIcebergMutablePlugin)config.getPlugin();
      icebergModel = icebergMutablePlugin.getIcebergModel(icebergTableProps, config.getProps().getUserName(), context, null);
      icebergTableIdentifier = icebergModel.getTableIdentifier(icebergMutablePlugin.getTableLocation(icebergTableProps));

        TypedFieldId metadataFileId = RecordWriter.SCHEMA.getFieldId(SchemaPath.getSimplePath(RecordWriter.ICEBERG_METADATA_COLUMN));
        TypedFieldId operationTypeId = RecordWriter.SCHEMA.getFieldId(SchemaPath.getSimplePath(RecordWriter.OPERATION_TYPE_COLUMN));
        TypedFieldId pathId = RecordWriter.SCHEMA.getFieldId(SchemaPath.getSimplePath(RecordWriter.PATH_COLUMN));
        icebergMetadataVector = incoming.getValueAccessorById(VarBinaryVector.class, metadataFileId.getFieldIds()).getValueVector();
        operationTypeVector = incoming.getValueAccessorById(IntVector.class, operationTypeId.getFieldIds()).getValueVector();
        pathVector = incoming.getValueAccessorById(VarCharVector.class, pathId.getFieldIds()).getValueVector();
        partitionDataVector = (ListVector) VectorUtil.getVectorFromSchemaPath(incoming, RecordWriter.PARTITION_DATA_COLUMN);

        switch (icebergTableProps.getIcebergOpType()) {
            case CREATE:
                icebergOpCommitter = icebergModel.getCreateTableCommitter(
                        icebergTableProps.getTableName(),
                        icebergTableIdentifier,
                        icebergTableProps.getFullSchema(),
                        icebergTableProps.getPartitionColumnNames(),
                        context.getStats(),
                        icebergTableProps.getPartitionSpec() != null ?
                                IcebergSerDe.deserializePartitionSpec(deserializedJsonAsSchema(icebergTableProps.getIcebergSchema()),
                                        icebergTableProps.getPartitionSpec().toByteArray()) : null
                );
                break;
            case INSERT:
                icebergOpCommitter = icebergModel.getInsertTableCommitter(
                        icebergModel.getTableIdentifier(icebergTableProps.getTableLocation()),
                        context.getStats()
                );
                break;
            case UPDATE:
            case DELETE:
            case MERGE:
                icebergOpCommitter = icebergModel.getDmlCommitter(
                  context.getStats(),
                  icebergModel.getTableIdentifier(icebergTableProps.getTableLocation()),
                  config.getDatasetConfig().get());
                break;
          case FULL_METADATA_REFRESH:
            createReadSignProvider(icebergTableProps, true);
            icebergOpCommitter = icebergModel.getFullMetadataRefreshCommitter(
                  icebergTableProps.getTableName(),
                  config.getDatasetPath().getPathComponents(),
                  icebergTableProps.getDataTableLocation(),
                  icebergTableProps.getUuid(),
                  icebergModel.getTableIdentifier(icebergTableProps.getTableLocation()),
                  icebergTableProps.getFullSchema(),
                  icebergTableProps.getPartitionColumnNames(),
                  config.getDatasetConfig().get(),
                  context.getStats(),
                  null
                );
                break;
            case INCREMENTAL_METADATA_REFRESH:
              createReadSignProvider(icebergTableProps, false);
              icebergOpCommitter = icebergModel.getIncrementalMetadataRefreshCommitter(
                    context,
                    icebergTableProps.getTableName(),
                    config.getDatasetPath().getPathComponents(),
                    icebergTableProps.getDataTableLocation(),
                    icebergTableProps.getUuid(),
                    icebergModel.getTableIdentifier(icebergTableProps.getTableLocation()),
                    icebergTableProps.getPersistedFullSchema(),
                    icebergTableProps.getPartitionColumnNames(),
                    false,
                    config.getDatasetConfig().get()
                );
                icebergOpCommitter.updateSchema(icebergTableProps.getFullSchema());
                break;
        }
      addMetricStat(WriterCommitterOperator.Metric.ICEBERG_COMMIT_SETUP_TIME, stopwatch.elapsed(TimeUnit.MILLISECONDS));
    }

    private List<String> getPartitionPaths() {
        return config.getIcebergTableProps().getPartitionPaths();
    }

  public void consumeData(int records) throws Exception {
        for (int i = 0; i < records; ++i) {
            OperationType operationType = getOperationType(i);
            switch (operationType) {
                case ADD_MANIFESTFILE:
                    consumeManifestFile(getManifestFile(i));
                    consumeManifestPartitionData(i);
                    break;
                case DELETE_DATAFILE:
                    if (isDmlCommandType()) {
                        consumeDeleteDataFilePath(i);
                    } else {
                        consumeDeletedDataFile(getDeleteDataFile(i));
                    }
                    consumeDeletedDataFilePartitionData(i);
                    break;
                default:
                    throw new Exception("Unsupported File Type: " + operationType);
            }
        }
    }

    protected void consumeManifestFile(ManifestFile manifestFile) {
        logger.debug("Adding manifest file: {}", manifestFile.path());
        icebergManifestFiles.add(manifestFile);
        icebergOpCommitter.consumeManifestFile(manifestFile);
    }

    protected void consumeDeletedDataFile(DataFile deletedDataFile) {
      logger.debug("Removing data file: {}", deletedDataFile.path());
      icebergOpCommitter.consumeDeleteDataFile(deletedDataFile);

      // Record the partition paths for deleted files during incremental metadata refresh - this will help limit the
      // number of existence checks we need to perform when updating the read signature.
      // TODO: DX-58354: Logic for managing the read signature needs to be refactored into the IcebergOpCommitter
      // implementations.
      if (config.getIcebergTableProps().getIcebergOpType() == IcebergCommandType.INCREMENTAL_METADATA_REFRESH) {
        String partitionPath = getPartitionPathFromFilePath(deletedDataFile.path().toString());
        if (partitionPath != null) {
          partitionPathsWithDeletedFiles.add(partitionPath);
        } else {
          // Just log and ignore the case where we can't match the deleted data file to a known partition path.
          // For well-formed tables this should not occur.
          logger.warn("Failed to find a corresponding partition path for deleted data file {}",
              deletedDataFile.path());
        }
      }
    }

    protected void consumeDeleteDataFilePath(int row) {
        // For Dml commands, the data file paths to be deleted are carried in through RecordWriter.Path field.
        Preconditions.checkNotNull(pathVector);
        // For inserted rows in MERGE, we don't have a deleted data file path but is passed here as null. As a result, we need to do a null check.
        byte[] filePath = pathVector.get(row);
        if (filePath == null) {
          return;
        }
        Text text = new Text(pathVector.get(row));
        String deletedDataFilePath = text.toString();
        icebergOpCommitter.consumeDeleteDataFilePath(deletedDataFilePath);
    }

    protected IcebergMetadataInformation getIcebergMetadataInformation(int row) throws IOException, ClassNotFoundException {
        return IcebergSerDe.deserializeFromByteArray(icebergMetadataVector.get(row));
    }

    protected ManifestFile getManifestFile(int row) throws IOException, ClassNotFoundException {
        return IcebergSerDe.deserializeManifestFile(getIcebergMetadataInformation(row).getIcebergMetadataFileByte());
    }

    protected DataFile getDeleteDataFile(int row) throws IOException, ClassNotFoundException {
        return IcebergSerDe.deserializeDataFile(getIcebergMetadataInformation(row).getIcebergMetadataFileByte());
    }

    protected boolean isDmlCommandType() {
        IcebergCommandType commandType = config.getIcebergTableProps().getIcebergOpType();
        return commandType == IcebergCommandType.DELETE || commandType == IcebergCommandType.UPDATE
          || commandType == IcebergCommandType.MERGE;
    }

    protected OperationType getOperationType(int row) {
      return OperationType.valueOf(operationTypeVector.get(row));
    }

    public void commit() throws Exception {
        if (icebergOpCommitter == null) {
            logger.warn("Skipping iceberg commit because opCommitter isn't initialized");
            return;
        }
        Stopwatch stopwatch = Stopwatch.createStarted();
        ByteString newReadSignature = readSigProvider.compute(addedPartitions, deletedPartitions);
        addMetricStat(WriterCommitterOperator.Metric.READ_SIGNATURE_COMPUTE_TIME, stopwatch.elapsed(TimeUnit.MILLISECONDS));
        icebergOpCommitter.updateReadSignature(newReadSignature);
      try (AutoCloseable ac = OperatorStats.getWaitRecorder(context.getStats())) {
            icebergOpCommitter.commit();
        } catch (Exception ex) {
        icebergOpCommitter.cleanup(dremioFileIO);
        throw ex;
      }
      success = true;
    }

  protected void createReadSignProvider(IcebergTableProps icebergTableProps, boolean isFullRefresh) {
    if (config.isReadSignatureEnabled()) {
      ByteString existingReadSignature = null;
      if (!isFullRefresh) {
        existingReadSignature = ByteString.copyFrom(config.getDatasetConfig().get().getReadDefinition().getReadSignature().toByteArray());  // TODO avoid copy
      }
      createPartitionExistsPredicate(config, isFullRefresh);

      readSigProvider = config.getSourceTablePlugin().createReadSignatureProvider(existingReadSignature, icebergTableProps.getDataTableLocation(),
        context.getFunctionContext().getContextInformation().getQueryStartTime(),
        getPartitionPaths(), partitionExistsPredicate, isFullRefresh, config.isPartialRefresh());
    }
  }

  private void consumeDeletedDataFilePartitionData(int record) {
    if (config.isReadSignatureEnabled()) {
      List<IcebergPartitionData> partitionData = getPartitionData(record);
      if (!partitionData.isEmpty()) {
        deletedPartitions.add(partitionData.get(0));
      }
    }
  }

  private void consumeManifestPartitionData(int record) {
    if (config.isReadSignatureEnabled()) {
      addedPartitions.addAll(getPartitionData(record));
    }
  }

    protected List<IcebergPartitionData> getPartitionData(int i) {
      List<IcebergPartitionData> partitionDataList = new ArrayList<>();
      UnionListReader partitionDataVectorReader = partitionDataVector.getReader();
      partitionDataVectorReader.setPosition(i);
      int size = partitionDataVectorReader.size();
      FieldReader partitionDataBinaryReader = partitionDataVectorReader.reader();
      for (int j = 0; j < size; j++) {
        partitionDataBinaryReader.setPosition(j);
        byte[] bytes = partitionDataBinaryReader.readByteArray();
        IcebergPartitionData ipd = IcebergSerDe.deserializePartitionData(bytes);
        if (ipd.get(0) != null) {
          partitionDataList.add(ipd);
        }
      }
      return partitionDataList;
    }

    protected void createPartitionExistsPredicate(WriterCommitterPOP config, boolean isFullRefresh) {
      partitionExistsPredicate = (path) ->
      {
        // We need to check existence of partition dirs in two cases:
        // 1. Full refresh - even if Hive reports a partition directory as part of the table, it can be deleted, and
        //    in that case we need to exclude it from the read signature.
        // 2. Incremental refresh for directories where we found deleted data files - in these cases we need to
        //    check whether the entire directory was removed.
        // For other directories during incremental refresh, we skip existence checks as they can be expensive.  If
        // we did not find any deleted files in a partition directory, it must still exist.
        if (isFullRefresh || partitionPathsWithDeletedFiles.contains(path)) {
          try (AutoCloseable ac = OperatorStats.getWaitRecorder(context.getStats())) {
            return getFS(path, config).exists(Path.of(path));
          } catch (Exception e) {
            throw UserException.ioExceptionError(e).buildSilently();
          }
        }

        return true;
      };
    }

    protected FileSystem getFS(String path, WriterCommitterPOP config) throws IOException {
      if (fsToCheckIfPartitionExists == null) {
        Stopwatch stopwatch = Stopwatch.createStarted();
        fsToCheckIfPartitionExists = config.getSourceTablePlugin().createFS(path, config.getProps().getUserName(), context);
        addMetricStat(WriterCommitterOperator.Metric.FILE_SYSTEM_CREATE_TIME, stopwatch.elapsed(TimeUnit.MILLISECONDS));
      }
      return fsToCheckIfPartitionExists;
    }

    public void cleanUpManifestFiles(FileSystem fs) throws IOException, ClassNotFoundException {
        if (icebergMetadataVector == null) {
            return;
        }
        for(int i = 0; i < icebergMetadataVector.getValueCount(); i++) {
            IcebergMetadataInformation information = getIcebergMetadataInformation(i);
            OperationType operationType = getOperationType(i);
            if(operationType == OperationType.ADD_MANIFESTFILE) {
              Path p = Path.of(IcebergSerDe.deserializeManifestFile(information.getIcebergMetadataFileByte()).path());
              if(fs.exists(p)) {
                fs.delete(p, false);
              }
            }
        }
    }

  @VisibleForTesting
  IcebergOpCommitter getIcebergOpCommitter() {
    return icebergOpCommitter;
  }

  @Override
  public void close() throws Exception {
    try {
      // cleanup the files if having exception before the committer commits snapshot
      if (!success) {
        // check the commit status from the icebergOpCommiter if status is not NOT_STARTED
        // we can safely delete the files as exception thrown before
        if ((icebergOpCommitter == null || (icebergOpCommitter != null
          && !icebergOpCommitter.isIcebergTableUpdated()))) {
          deleteManifestFiles(dremioFileIO, icebergManifestFiles, false);
        }
      }
    } catch (Exception e) {
      logger.warn("Cleaning manifest files failed", e);
    }
    icebergManifestFiles.clear();
    icebergOpCommitter = null;
    AutoCloseables.close(fsToCheckIfPartitionExists);
  }

  private void addMetricStat(WriterCommitterOperator.Metric metric, long time) {
    if (context.getStats() != null) {
      context.getStats().addLongStat(metric, time);
    }
  }

  /**
   * Delete all data files referenced in a manifestFile
   */
  private static void deleteDataFilesInManifestFile(DremioFileIO dremioFileIO, ManifestFile manifestFile) {
    try {
      // ManifestFiles.readPaths requires snapshotId not null, created manifestFile has null snapshot id
      // use a NonNullSnapshotIdManifestFileWrapper to provide a non-null dummy snapshotId
      ManifestFile nonNullSnapshotIdManifestFile =
        manifestFile.snapshotId()==null ? GenericManifestFile.copyOf(manifestFile).withSnapshotId(-1L).build():manifestFile;
      CloseableIterable<String> dataFiles = ManifestFiles.readPaths(nonNullSnapshotIdManifestFile, dremioFileIO);
      dataFiles.forEach(f -> dremioFileIO.deleteFile(f));
    } catch (Exception e) {
      logger.warn(String.format("Failed to delete up data files in manifestFile %s" , manifestFile), e);
    }
  }

  public static void deleteManifestFiles(DremioFileIO dremioFileIO, List<ManifestFile> manifestFiles, boolean deleteDataFiles) {
    try {
      for (ManifestFile manifestFile : manifestFiles) {
        // delete data files
        if (deleteDataFiles) {
          deleteDataFilesInManifestFile(dremioFileIO, manifestFile);
        }

        // delete manifest file and corresponding crc file
        ManifestWritesHelper.deleteManifestFileIfExists(dremioFileIO, manifestFile.path());
      }
    } catch (Exception e) {
      logger.warn("Failed to clean up manifest files", e);
    }
  }

  protected String getPartitionPathFromFilePath(String path) {
    // data files could be in some subdirectory of a partition dir, so traverse parent dirs until we find a
    // partition dir (or give up)
    Path parent = Path.of(path).getParent();
    while (parent != null) {
      path = parent.toString();
      if (partitionPaths.contains(path)) {
        return path;
      }
      parent = parent.getParent();
    }

    return null;
  }

  private static Set<String> createPartitionPathsSet(WriterCommitterPOP config) {
    // If this is an INCREMENTAL_REFRESH op, cache all partition paths in a set for fast lookup when evaluating
    // deleted data files.  If the table is unpartitioned add the table root as a single 'partition path'.  If this
    // is not an Iceberg table, return an empty set.
    if (config.getIcebergTableProps() != null &&
        config.getIcebergTableProps().getIcebergOpType() == IcebergCommandType.INCREMENTAL_METADATA_REFRESH) {
      List<String> partitionPaths = config.getIcebergTableProps().getPartitionPaths();
      String dataTableLocation = config.getIcebergTableProps().getDataTableLocation();
      if (partitionPaths != null && !partitionPaths.isEmpty()) {
        return ImmutableSet.copyOf(partitionPaths);
      } else if (dataTableLocation != null) {
        return ImmutableSet.of(dataTableLocation);
      }
    }

    return ImmutableSet.of();
  }
}
