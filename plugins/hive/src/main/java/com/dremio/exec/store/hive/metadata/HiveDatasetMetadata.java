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
package com.dremio.exec.store.hive.metadata;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectOutput;
import java.io.ObjectOutputStream;
import java.util.List;
import java.util.Objects;

import org.apache.arrow.vector.types.pojo.Schema;

import com.dremio.connector.metadata.BytesOutput;
import com.dremio.connector.metadata.DatasetMetadata;
import com.dremio.connector.metadata.DatasetStats;
import com.dremio.service.namespace.dataset.proto.IcebergMetadata;

public final class HiveDatasetMetadata implements DatasetMetadata {

  private final Schema schema;
  private final List<String> partitionColumns;
  private final List<String> sortColumns;
  private final BytesOutput extraInfo;
  private final MetadataAccumulator metadataAccumulator;
  private final byte[] icebergMetadata;
  private final DatasetStats manifestStats;

  private HiveDatasetMetadata(
    final Schema schema,
    final List<String> partitionColumns,
    final List<String> sortColumns,
    final BytesOutput extraInfo,
    final MetadataAccumulator metadataAccumulator,
    final byte[] icebergMetadata,
    final DatasetStats manifestStats
  ) {
    this.schema = schema;
    this.partitionColumns = partitionColumns;
    this.sortColumns = sortColumns;
    this.extraInfo = extraInfo;
    this.metadataAccumulator = metadataAccumulator;
    this.icebergMetadata = icebergMetadata;
    this.manifestStats = manifestStats;
  }

  @Override
  public DatasetStats getDatasetStats() {
    return metadataAccumulator.getDatasetStats();
  }

  @Override
  public DatasetStats getManifestStats() {
    return manifestStats;
  }

  @Override
  public Schema getRecordSchema() {
    return schema;
  }

  @Override
  public List<String> getPartitionColumns() {
    return partitionColumns;
  }

  @Override
  public List<String> getSortColumns() {
    return sortColumns;
  }

  @Override
  public BytesOutput getExtraInfo() {
    return extraInfo;
  }

  @Override
  public byte[] getIcebergMetadata() {
    return icebergMetadata;
  }

  public MetadataAccumulator getMetadataAccumulator() {
    return metadataAccumulator;
  }

  public static Builder newBuilder() {
    return new Builder();
  }

  public static final class Builder {
    private Schema schema;
    private List<String> partitionColumns;
    private List<String> sortColumns;
    private BytesOutput extraInfo;
    private MetadataAccumulator metadataAccumulator;
    private byte[] icebergMetadata;
    private DatasetStats manifestStats;

    private Builder() {
    }

    public Builder schema(Schema schema) {
      this.schema = schema;
      return this;
    }

    public Builder partitionColumns(List<String> partitionColumns) {
      this.partitionColumns = partitionColumns;
      return this;
    }

    public Builder sortColumns(List<String> sortColumns) {
      this.sortColumns = sortColumns;
      return this;
    }

    public Builder extraInfo(BytesOutput extraInfo) {
      this.extraInfo = extraInfo;
      return this;
    }

    public Builder metadataAccumulator(MetadataAccumulator metadataAccumulator) {
      this.metadataAccumulator = metadataAccumulator;
      return this;
    }

    public Builder icebergMetadata(IcebergMetadata icebergMetadata) {
      if (icebergMetadata == null) {
        this.icebergMetadata = new byte[0];
        return this;
      }

      try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
           ObjectOutput out = new ObjectOutputStream(bos)) {
        out.writeObject(icebergMetadata);
        this.icebergMetadata = bos.toByteArray();
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
      return this;
    }

    public Builder manifestStats(DatasetStats manifestStats) {
      this.manifestStats = manifestStats;
      return this;
    }

    public HiveDatasetMetadata build() {

      Objects.requireNonNull(metadataAccumulator, "metadata accumulator is required");
      Objects.requireNonNull(schema, "schema is required");
      Objects.requireNonNull(partitionColumns, "partition columns is required");
      Objects.requireNonNull(sortColumns, "sort columns is required");
      Objects.requireNonNull(extraInfo, "extra info is required");
      Objects.requireNonNull(icebergMetadata, "Iceberg metadata is required");

      return new HiveDatasetMetadata(schema, partitionColumns, sortColumns, extraInfo, metadataAccumulator, icebergMetadata, manifestStats);
    }
  }
}
