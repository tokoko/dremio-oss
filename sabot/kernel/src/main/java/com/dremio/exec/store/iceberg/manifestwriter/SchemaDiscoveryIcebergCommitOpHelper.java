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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.apache.arrow.vector.IntVector;
import org.apache.arrow.vector.VarBinaryVector;
import org.apache.arrow.vector.complex.ListVector;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.ManifestFile;
import org.apache.iceberg.types.Types;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.dremio.common.exceptions.UserException;
import com.dremio.common.expression.SchemaPath;
import com.dremio.common.types.SupportsTypeCoercionsAndUpPromotions;
import com.dremio.exec.catalog.CatalogOptions;
import com.dremio.exec.catalog.ColumnCountTooLargeException;
import com.dremio.exec.exception.NoSupportedUpPromotionOrCoercionException;
import com.dremio.exec.physical.config.WriterCommitterPOP;
import com.dremio.exec.planner.acceleration.IncrementalUpdateUtils;
import com.dremio.exec.record.BatchSchema;
import com.dremio.exec.record.TypedFieldId;
import com.dremio.exec.record.VectorAccessible;
import com.dremio.exec.store.RecordWriter;
import com.dremio.exec.store.dfs.IcebergTableProps;
import com.dremio.exec.store.iceberg.IcebergPartitionData;
import com.dremio.exec.store.iceberg.SupportsIcebergMutablePlugin;
import com.dremio.exec.store.iceberg.model.IcebergCommandType;
import com.dremio.exec.store.iceberg.model.IcebergModel;
import com.dremio.exec.util.VectorUtil;
import com.dremio.io.file.Path;
import com.dremio.sabot.exec.context.OperatorContext;
import com.dremio.sabot.exec.context.OperatorStats;

/**
 * Discovers the schema from the incoming data vectors instead of config. The manifest files are kept in memory, and
 * the icebergCommitterOp is lazily initialized only at the commit time.
 */
public class SchemaDiscoveryIcebergCommitOpHelper extends IcebergCommitOpHelper implements SupportsTypeCoercionsAndUpPromotions {
    private static final Logger logger = LoggerFactory.getLogger(SchemaDiscoveryIcebergCommitOpHelper.class);

    private VarBinaryVector schemaVector;
    private BatchSchema currentSchema;
    private List<DataFile> deletedDataFiles = new ArrayList<>();
    private List<String> partitionColumns;
    private final int implicitColSize;

  protected SchemaDiscoveryIcebergCommitOpHelper(OperatorContext context, WriterCommitterPOP config) {
        super(context, config);
        this.partitionColumns = Optional.ofNullable(config.getIcebergTableProps().getPartitionColumnNames()).orElse(Collections.EMPTY_LIST);
        this.implicitColSize = (int) partitionColumns.stream().filter(IncrementalUpdateUtils.UPDATE_COLUMN::equals).count();
        this.currentSchema = config.getIcebergTableProps().getFullSchema();
    }

    @Override
    public void setup(VectorAccessible incoming) {
        TypedFieldId schemaFieldId = RecordWriter.SCHEMA.getFieldId(SchemaPath.getSimplePath(RecordWriter.FILE_SCHEMA_COLUMN));
        schemaVector = incoming.getValueAccessorById(VarBinaryVector.class, schemaFieldId.getFieldIds()).getValueVector();

        TypedFieldId metadataFileId = RecordWriter.SCHEMA.getFieldId(SchemaPath.getSimplePath(RecordWriter.ICEBERG_METADATA_COLUMN));
        icebergMetadataVector = incoming.getValueAccessorById(VarBinaryVector.class, metadataFileId.getFieldIds()).getValueVector();
        TypedFieldId operationTypeId = RecordWriter.SCHEMA.getFieldId(SchemaPath.getSimplePath(RecordWriter.OPERATION_TYPE_COLUMN));
        operationTypeVector = incoming.getValueAccessorById(IntVector.class, operationTypeId.getFieldIds()).getValueVector();
        partitionDataVector = (ListVector) VectorUtil.getVectorFromSchemaPath(incoming, RecordWriter.PARTITION_DATA_COLUMN);
    }

    @Override
    public void consumeData(int records) throws Exception {
      super.consumeData(records);
      IntStream.range(0, records).filter(i -> schemaVector.isSet(i) != 0).forEach(this::consumeSchema);
      IntStream.range(0, records).forEach(this::consumePartitionData);
    }

    private void consumeSchema(int recordIdx) {
        byte[] schemaBytes = schemaVector.get(recordIdx);
        BatchSchema schemaAtThisRow = BatchSchema.deserialize(schemaBytes);
        if (!currentSchema.equals(schemaAtThisRow)) {
          try {
            currentSchema = currentSchema.mergeWithUpPromotion(schemaAtThisRow, this);
          } catch (NoSupportedUpPromotionOrCoercionException e) {
            e.addDatasetPath(config.getDatasetPath().getPathComponents());
            throw UserException.unsupportedError().message(e.getMessage()).build();
          }
            if (currentSchema.getTotalFieldCount() > context.getOptions().getOption(CatalogOptions.METADATA_LEAF_COLUMN_MAX)) {
              throw new ColumnCountTooLargeException((int) context.getOptions().getOption(CatalogOptions.METADATA_LEAF_COLUMN_MAX));
            }
        }
    }

    private void consumePartitionData(int recordIdx) {
      List<IcebergPartitionData> partitionDataForThisManifest = getPartitionData(recordIdx);
      int existingPartitionDepth = partitionColumns.size() - implicitColSize;
      partitionDataForThisManifest.stream().forEach(x -> {
        if(x.size() > existingPartitionDepth) {
          partitionColumns = x.getPartitionType().fields().stream().map(Types.NestedField::name).collect(Collectors.toList());
        }
      });
    }


    @Override
    protected void consumeManifestFile(ManifestFile manifestFile) {
      logger.debug("Adding manifest file: {}", manifestFile.path());
      icebergManifestFiles.add(manifestFile);

      int existingPartitionDepth = partitionColumns.size() - implicitColSize;
      if(config.getIcebergTableProps().isDetectSchema() && manifestFile.partitions().size() > existingPartitionDepth
        && config.getIcebergTableProps().getIcebergOpType() == IcebergCommandType.INCREMENTAL_METADATA_REFRESH) {
        throw new UnsupportedOperationException ("Addition of a new level dir is not allowed in incremental refresh. Please forget and " +
            "promote the table again.");
      }
    }

    @Override
    protected void consumeDeletedDataFile(DataFile deletedDataFile) {
        logger.debug("Removing data file: {}", deletedDataFile.path());
        deletedDataFiles.add(deletedDataFile);
    }

    @Override
    public void commit() throws Exception {
        initializeIcebergOpCommitter();
        super.commit();
        icebergManifestFiles.clear();
        deletedDataFiles.clear();
    }

    private void initializeIcebergOpCommitter() throws Exception {
        // TODO: doesn't track wait times currently. need to use dremioFileIO after implementing newOutputFile method
        IcebergModel icebergModel = ((SupportsIcebergMutablePlugin)config.getPlugin()).
          getIcebergModel(config.getIcebergTableProps(), config.getProps().getUserName(), context, null);
        IcebergTableProps icebergTableProps = config.getIcebergTableProps();

        switch (icebergTableProps.getIcebergOpType()) {
            case CREATE:
                icebergOpCommitter = icebergModel.getCreateTableCommitter(
                        icebergTableProps.getTableName(),
                        icebergModel.getTableIdentifier(icebergTableProps.getTableLocation()),
                        currentSchema,
                        partitionColumns, context.getStats(), null);
                break;
            case INSERT:
                icebergOpCommitter = icebergModel.getInsertTableCommitter(icebergModel.getTableIdentifier(icebergTableProps.getTableLocation()), context.getStats());
                break;
            case FULL_METADATA_REFRESH:
              createReadSignProvider(icebergTableProps, true);
              icebergOpCommitter = icebergModel.getFullMetadataRefreshCommitter(
                icebergTableProps.getTableName(),
                config.getDatasetPath().getPathComponents(),
                icebergTableProps.getDataTableLocation(),
                icebergTableProps.getUuid(),
                icebergModel.getTableIdentifier(icebergTableProps.getTableLocation()),
                currentSchema,
                partitionColumns,
                config.getDatasetConfig().orElseThrow(() -> new IllegalStateException("DatasetConfig not found")),
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
                  icebergTableProps.getFullSchema(),
                  partitionColumns,
                  true,
                  config.getDatasetConfig().orElseThrow(() -> new IllegalStateException("DatasetConfig not found"))
                );
              icebergOpCommitter.updateSchema(currentSchema);
              break;
        }

        try (AutoCloseable ac = OperatorStats.getWaitRecorder(context.getStats())) {
          icebergManifestFiles.forEach(icebergOpCommitter::consumeManifestFile);
          deletedDataFiles.forEach(icebergOpCommitter::consumeDeleteDataFile);
        }
    }

  protected void createPartitionExistsPredicate(WriterCommitterPOP config, boolean isFullRefresh) {
    // SchemaDiscoveryIcebergCommitOpHelper is used for non-Hive sources where partition paths won't be
    // provided via IcebergTableProps.getPartitionPaths.  Partition existence will be done only for paths
    // that map to deleted partitions in IncrementalRefreshReadSignatureProvider.handleDeletedPartitions, so
    // no need to optimize them out here.
    partitionExistsPredicate = (path) ->
    {
      try (AutoCloseable ac = OperatorStats.getWaitRecorder(context.getStats())) {
        return getFS(path, config).exists(Path.of(path));
      } catch (Exception e) {
        throw UserException.ioExceptionError(e).buildSilently();
      }
    };
  }
}
