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
package com.dremio.service.nessie.upgrade.storage;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.projectnessie.server.store.TableCommitMetaStoreWorker;
import org.projectnessie.versioned.persist.adapter.DatabaseAdapter;
import org.projectnessie.versioned.persist.nontx.ImmutableAdjustableNonTransactionalDatabaseAdapterConfig;
import org.projectnessie.versioned.persist.nontx.NonTransactionalDatabaseAdapterConfig;

import com.dremio.common.config.SabotConfig;
import com.dremio.common.scanner.ClassPathScanner;
import com.dremio.common.scanner.persistence.ScanResult;
import com.dremio.datastore.LocalKVStoreProvider;
import com.dremio.service.nessie.DatastoreDatabaseAdapterFactory;
import com.dremio.service.nessie.ImmutableDatastoreDbConfig;
import com.dremio.service.nessie.NessieDatastoreInstance;

class TestPurgeObsoleteKeyLists {

  private static final ScanResult scanResult = ClassPathScanner.fromPrescan(SabotConfig.create());

  private PurgeObsoleteKeyLists task;
  private LocalKVStoreProvider storeProvider;
  private DatabaseAdapter adapter;

  @BeforeEach
  void createKVStore() throws Exception {
    task = new PurgeObsoleteKeyLists();
    storeProvider = new LocalKVStoreProvider(scanResult, null, true, false); // in-memory
    storeProvider.start();

    NessieDatastoreInstance nessieDatastore = new NessieDatastoreInstance();
    nessieDatastore.configure(new ImmutableDatastoreDbConfig.Builder()
      .setStoreProvider(() -> storeProvider)
      .build());
    nessieDatastore.initialize();

    NonTransactionalDatabaseAdapterConfig adapterCfg = ImmutableAdjustableNonTransactionalDatabaseAdapterConfig
      .builder()
      .build();
    adapter = new DatastoreDatabaseAdapterFactory().newBuilder()
      .withConfig(adapterCfg)
      .withConnector(nessieDatastore)
      .build(new TableCommitMetaStoreWorker());
    adapter.initializeRepo("main");
  }

  @AfterEach
  void stopKVStore() throws Exception {
    if (storeProvider != null) {
      storeProvider.close();
    }
  }

  @Test
  void testUpgrade() throws Exception {
    // Validate only that the maintenance call was made. More extensive tests are in ITCommitLogMaintenance.
    Assertions.assertThat(task.upgrade(storeProvider)).contains("purgeKeyLists");
  }
}
