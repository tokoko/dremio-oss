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
package com.dremio.exec.catalog;

import static com.dremio.test.DremioTest.CLASSPATH_SCAN_RESULT;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import javax.inject.Provider;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import com.dremio.common.AutoCloseables;
import com.dremio.common.config.LogicalPlanPersistence;
import com.dremio.common.config.SabotConfig;
import com.dremio.common.exceptions.UserException;
import com.dremio.concurrent.Runnables;
import com.dremio.concurrent.SafeRunnable;
import com.dremio.config.DremioConfig;
import com.dremio.connector.metadata.DatasetHandle;
import com.dremio.connector.metadata.EntityPath;
import com.dremio.datastore.adapter.LegacyKVStoreProviderAdapter;
import com.dremio.datastore.api.LegacyKVStore;
import com.dremio.datastore.api.LegacyKVStoreProvider;
import com.dremio.exec.ExecConstants;
import com.dremio.exec.catalog.conf.ConnectionConf;
import com.dremio.exec.catalog.conf.SourceType;
import com.dremio.exec.record.BatchSchema;
import com.dremio.exec.server.SabotContext;
import com.dremio.exec.server.options.DefaultOptionManager;
import com.dremio.exec.server.options.OptionManagerWrapper;
import com.dremio.exec.server.options.OptionValidatorListingImpl;
import com.dremio.exec.server.options.SystemOptionManager;
import com.dremio.exec.store.CatalogService;
import com.dremio.exec.store.SchemaConfig;
import com.dremio.exec.store.StoragePlugin;
import com.dremio.options.OptionManager;
import com.dremio.options.OptionValidatorListing;
import com.dremio.options.TypeValidators.PositiveLongValidator;
import com.dremio.service.coordinator.ClusterCoordinator;
import com.dremio.service.coordinator.ClusterCoordinator.Role;
import com.dremio.service.listing.DatasetListingService;
import com.dremio.service.namespace.NamespaceKey;
import com.dremio.service.namespace.NamespaceService;
import com.dremio.service.namespace.SourceState;
import com.dremio.service.namespace.dataset.proto.DatasetConfig;
import com.dremio.service.namespace.dataset.proto.DatasetType;
import com.dremio.service.namespace.dataset.proto.ReadDefinition;
import com.dremio.service.namespace.proto.EntityId;
import com.dremio.service.namespace.source.proto.SourceConfig;
import com.dremio.service.namespace.source.proto.SourceInternalData;
import com.dremio.service.orphanage.Orphanage;
import com.dremio.service.scheduler.Cancellable;
import com.dremio.service.scheduler.ModifiableLocalSchedulerService;
import com.dremio.service.scheduler.ModifiableSchedulerService;
import com.dremio.service.scheduler.Schedule;
import com.dremio.service.scheduler.SchedulerService;
import com.dremio.service.users.SystemUser;
import com.dremio.services.credentials.CredentialsService;
import com.dremio.test.DremioTest;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Sets;

/**
 * Unit tests for PluginsManager.
 */
public class TestPluginsManager {
  private LegacyKVStoreProvider storeProvider;
  private PluginsManager plugins;
  private SabotContext sabotContext;
  private SchedulerService schedulerService;
  private ModifiableSchedulerService modifiableSchedulerService;
  private NamespaceService mockNamespaceService;
  private Orphanage mockOrphanage;
  private List<Cancellable> scheduledTasks = new ArrayList<>();

  @Before
  public void setup() throws Exception {
    storeProvider =
        LegacyKVStoreProviderAdapter.inMemory(DremioTest.CLASSPATH_SCAN_RESULT);
    storeProvider.start();
    mockNamespaceService = mock(NamespaceService.class);
    mockOrphanage = mock(Orphanage.class);
    when(mockNamespaceService.getAllDatasets(Mockito.any())).thenReturn(Collections.emptyList());

    final DatasetListingService mockDatasetListingService = mock(DatasetListingService.class);
    final DremioConfig dremioConfig = DremioConfig.create();
    final SabotConfig sabotConfig = SabotConfig.create();
    sabotContext = mock(SabotContext.class);

    // used in c'tor
    when(sabotContext.getClasspathScan())
        .thenReturn(CLASSPATH_SCAN_RESULT);
    when(sabotContext.getNamespaceService(anyString()))
        .thenReturn(mockNamespaceService);
    when(sabotContext.getDatasetListing())
        .thenReturn(mockDatasetListingService);

    final LogicalPlanPersistence lpp = new LogicalPlanPersistence(SabotConfig.create(), CLASSPATH_SCAN_RESULT);
    when(sabotContext.getLpPersistence())
        .thenReturn(lpp);

    final OptionValidatorListing optionValidatorListing = new OptionValidatorListingImpl(CLASSPATH_SCAN_RESULT);
    final SystemOptionManager som = new SystemOptionManager(optionValidatorListing, lpp, () -> storeProvider, true);
    final OptionManager optionManager = OptionManagerWrapper.Builder.newBuilder()
      .withOptionManager(new DefaultOptionManager(optionValidatorListing))
      .withOptionManager(som)
      .build();

    som.start();
    when(sabotContext.getOptionManager())
        .thenReturn(optionManager);

    // used in start
    when(sabotContext.getKVStoreProvider())
        .thenReturn(storeProvider);
    when(sabotContext.getConfig())
        .thenReturn(DremioTest.DEFAULT_SABOT_CONFIG);

    final Set<Role> roles = Sets.newHashSet(ClusterCoordinator.Role.MASTER);

    // used in newPlugin
    when(sabotContext.getRoles())
        .thenReturn(roles);
    when(sabotContext.isMaster())
        .thenReturn(true);

    when(sabotContext.getCredentialsServiceProvider())
      .thenReturn(() -> mock(CredentialsService.class));

    LegacyKVStore<NamespaceKey, SourceInternalData> sourceDataStore = storeProvider.getStore(CatalogSourceDataCreator.class);
    schedulerService = mock(SchedulerService.class);
    mockScheduleInvocation();
    final MetadataRefreshInfoBroadcaster broadcaster = mock(MetadataRefreshInfoBroadcaster.class);
    doNothing().when(broadcaster).communicateChange(any());

    PositiveLongValidator option = ExecConstants.MAX_CONCURRENT_METADATA_REFRESHES;
    modifiableSchedulerService = new ModifiableLocalSchedulerService(1, "modifiable-scheduler-",
      option, () -> optionManager) {
      public Cancellable schedule(Schedule schedule, Runnable task) {
        Cancellable wakeupTask = super.schedule(schedule, task);
        scheduledTasks.add(wakeupTask);
        return wakeupTask;
      }
    };

    plugins = new PluginsManager(sabotContext, mockNamespaceService, mockOrphanage, mockDatasetListingService, optionManager, dremioConfig,
        sourceDataStore, schedulerService,
      ConnectionReader.of(sabotContext.getClasspathScan(), sabotConfig), CatalogServiceMonitor.DEFAULT, () -> broadcaster,null, modifiableSchedulerService);
    plugins.start();
  }

  @After
  public void shutdown() throws Exception {
    if (plugins != null) {
      plugins.close();
    }

    if (storeProvider != null) {
      storeProvider.close();
    }

    AutoCloseables.close(modifiableSchedulerService);
  }

  private void mockScheduleInvocation() {
    doAnswer(new Answer<Cancellable>() {
      @Override
      public Cancellable answer(InvocationOnMock invocation) {
        final Object[] arguments = invocation.getArguments();
        if (arguments[1] instanceof SafeRunnable) {
          return mock(Cancellable.class);
        }
        // allow thread that does first piece of work: scheduleMetadataRefresh
        // (that was not part of thread before) go through
        final Runnable r = (Runnable) arguments[1];
        Runnables.executeInSeparateThread(new Runnable() {
          @Override
          public void run() {
            r.run();
          }

        });
        return mock(Cancellable.class);
      } // using SafeRunnable, as Runnable is also used to run initial setup that used to run w/o any scheduling
    }).when(schedulerService).schedule(any(Schedule.class), any(Runnable.class));
  }

  private static final String INSPECTOR = "inspector";

  private static final EntityPath DELETED_PATH = new EntityPath(ImmutableList.of(INSPECTOR, "deleted"));

  private static final DatasetConfig incompleteDatasetConfig = new DatasetConfig();

  private static final EntityPath ENTITY_PATH = new EntityPath(ImmutableList.of(INSPECTOR, "one"));
  private static final DatasetHandle DATASET_HANDLE = () -> ENTITY_PATH;

  @SourceType(value = INSPECTOR, configurable = false)
  public static class Inspector extends ConnectionConf<Inspector, StoragePlugin> {
    private final boolean hasAccessPermission;

    Inspector() {
      this.hasAccessPermission = true;
    }

    Inspector(boolean hasAccessPermission) {
      this.hasAccessPermission = hasAccessPermission;
    }

    @Override
    public StoragePlugin newPlugin(SabotContext context, String name, Provider<StoragePluginId> pluginIdProvider) {
      final ExtendedStoragePlugin mockStoragePlugin = mock(ExtendedStoragePlugin.class);
      try {
        when(mockStoragePlugin.listDatasetHandles())
            .thenReturn(Collections::emptyIterator);

        when(mockStoragePlugin.getDatasetHandle(eq(DELETED_PATH)))
            .thenReturn(Optional.empty());

        when(mockStoragePlugin.getDatasetHandle(eq(ENTITY_PATH)))
            .thenReturn(Optional.of(DATASET_HANDLE));

        when(mockStoragePlugin.getState())
            .thenReturn(SourceState.GOOD);

        when(mockStoragePlugin.hasAccessPermission(anyString(), any(), any())).thenReturn(hasAccessPermission);
      } catch (Exception ignored) {
        throw new IllegalStateException("will not throw");
      }

      return mockStoragePlugin;
    }

    @Override
    @SuppressWarnings("EqualsHashCode") // .hashCode() is final in ConnectionConf and can't be overridden
    public boolean equals(Object other) {
      // this forces the replace call to always do so
      return false;
    }
  }

  @Test
  public void permissionCacheShouldClearOnReplace() throws Exception {
    final SourceConfig inspectorConfig = new SourceConfig()
        .setType(INSPECTOR)
        .setName(INSPECTOR)
        .setMetadataPolicy(CatalogService.DEFAULT_METADATA_POLICY)
        .setConfig(new Inspector(true).toBytesString());

    final LegacyKVStore<NamespaceKey, SourceInternalData> kvStore = storeProvider.getStore(CatalogSourceDataCreator.class);

    // create one; lock required
    final ManagedStoragePlugin plugin;
    plugin = plugins.create(inspectorConfig, SystemUser.SYSTEM_USERNAME);
    plugin.startAsync().get();

    final SchemaConfig schemaConfig = mock(SchemaConfig.class);
    when(schemaConfig.getUserName()).thenReturn("user");
    final MetadataRequestOptions requestOptions = MetadataRequestOptions.newBuilder()
        .setSchemaConfig(schemaConfig)
        .setNewerThan(1000)
        .build();

    // force a cache of the permissions
    plugin.checkAccess(new NamespaceKey("test"), incompleteDatasetConfig, "user", requestOptions);

    // create a replacement that will always fail permission checks
    final SourceConfig newConfig = new SourceConfig()
        .setType(INSPECTOR)
        .setName(INSPECTOR)
        .setMetadataPolicy(CatalogService.DEFAULT_METADATA_POLICY)
        .setConfig(new Inspector(false).toBytesString());

    plugin.replacePluginWithLock(newConfig, 1000, false);

    // will throw if the cache has been cleared
    boolean threw = false;
    try {
      plugin.checkAccess(new NamespaceKey("test"), incompleteDatasetConfig, "user", requestOptions);
    } catch (UserException e) {
      threw = true;
    }

    assertTrue(threw);
  }

  @Test
  public void testCreateSource() throws Exception {
    final SourceConfig newConfig = new SourceConfig()
      .setType(INSPECTOR)
      .setName("TEST")
      .setMetadataPolicy(CatalogService.DEFAULT_METADATA_POLICY)
      .setConfig(new Inspector(false).toBytesString());

    boolean userExceptionOccured = false;
    try {
      doThrow(UserException.validationError().message("Already Exists %s", "").buildSilently())
        .when(mockNamespaceService)
        .addOrUpdateSource(newConfig.getKey(), newConfig);
      scheduledTasks.clear();
      ManagedStoragePlugin plugin = plugins.create(newConfig, "testuser");
    } catch(UserException e) {
      userExceptionOccured = true;
    }
    assertEquals(scheduledTasks.size(), 1);
    assertTrue(scheduledTasks.get(0).isCancelled());
    assertTrue(userExceptionOccured);
  }

  @Test
  public void disableMetadataValidityCheck() throws Exception {

    final SourceConfig sourceConfigWithValidityCheck = new SourceConfig()
      .setType(INSPECTOR)
      .setName("source")
      .setMetadataPolicy(CatalogService.DEFAULT_METADATA_POLICY)
      .setConfig(new Inspector(true).toBytesString());

    final DatasetConfig incompleteDatasetConfig = new DatasetConfig();
    final LegacyKVStore<NamespaceKey, SourceInternalData> kvStore = storeProvider.getStore(CatalogSourceDataCreator.class);

    // create one; lock required
    final ManagedStoragePlugin pluginWithValidityCheck;
    pluginWithValidityCheck = plugins.create(sourceConfigWithValidityCheck, SystemUser.SYSTEM_USERNAME);
    pluginWithValidityCheck.startAsync().get();

    final SchemaConfig schemaConfig = mock(SchemaConfig.class);
    when(schemaConfig.getUserName()).thenReturn("user");

    // Ensure for an incomplete datasetConfig, validity is not checked even if option to disable validity is set
    final MetadataRequestOptions metadataRequestOptions = ImmutableMetadataRequestOptions.newBuilder()
      .setNewerThan(0L)
      .setSchemaConfig(SchemaConfig.newBuilder(CatalogUser.from("dremio")).build())
      .setCheckValidity(false)
      .build();
    assertFalse(pluginWithValidityCheck.isCompleteAndValid(incompleteDatasetConfig, metadataRequestOptions, mockNamespaceService));

    final SourceConfig sourceConfigDisableValidity = new SourceConfig()
      .setType(INSPECTOR)
      .setName("source2")
      .setMetadataPolicy(CatalogService.DEFAULT_METADATA_POLICY)
      .setDisableMetadataValidityCheck(true)
      .setConfig(new Inspector(true).toBytesString());

    final ManagedStoragePlugin pluginWithDisableValidity;
    pluginWithDisableValidity = plugins.create(sourceConfigDisableValidity, SystemUser.SYSTEM_USERNAME);
    pluginWithDisableValidity.startAsync().get();

    // Ensure for an incomplete datasetConfig, validity is not checked even if SourceConfig to disable validity is set
    assertFalse(pluginWithDisableValidity.isCompleteAndValid(incompleteDatasetConfig, ImmutableMetadataRequestOptions.copyOf(metadataRequestOptions).withCheckValidity(true), mockNamespaceService));

    final ReadDefinition readDefinition = new ReadDefinition();
    readDefinition.setSplitVersion(0L);

    DatasetConfig completeDatasetConfig = new DatasetConfig();
    completeDatasetConfig.setType(DatasetType.PHYSICAL_DATASET);
    completeDatasetConfig.setId(new EntityId("test"));
    completeDatasetConfig.setFullPathList(ImmutableList.of("test", "file", "foobar"));
    completeDatasetConfig.setRecordSchema((new BatchSchema(Collections.EMPTY_LIST)).toByteString());
    completeDatasetConfig.setReadDefinition(readDefinition);
    completeDatasetConfig.setTotalNumSplits(0);

    // Ensure for a complete config, isStillValid is called and expiry is ignored if request option is set.
    assertTrue(pluginWithValidityCheck.isCompleteAndValid(completeDatasetConfig, metadataRequestOptions, mockNamespaceService));

    // Ensure for a complete config, isStillValid is called and expiry is ignored if request option is not set but source config option is set to disable.
    assertTrue(pluginWithDisableValidity.isCompleteAndValid(completeDatasetConfig, metadataRequestOptions, mockNamespaceService));

    // Ensure for a complete config, isStillValid is called and expiry is ignored if request option is set to true but source config option is set to disable.
    assertTrue(pluginWithDisableValidity.isCompleteAndValid(completeDatasetConfig, ImmutableMetadataRequestOptions.copyOf(metadataRequestOptions).withCheckValidity(true), mockNamespaceService));

    // Ensure for a complete config, isStillValid is called and expiry is checked and fails if request option is set to false and source config option is not set to disable .
    assertFalse(pluginWithValidityCheck.isCompleteAndValid(completeDatasetConfig, ImmutableMetadataRequestOptions.copyOf(metadataRequestOptions).withCheckValidity(true), mockNamespaceService));
  }

}
