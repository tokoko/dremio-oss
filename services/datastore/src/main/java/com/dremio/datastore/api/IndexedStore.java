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
package com.dremio.datastore.api;

import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;

import com.dremio.context.TenantContext;
import com.dremio.datastore.SearchTypes.SearchQuery;
import com.dremio.datastore.api.options.VersionOption;

/**
 * A KVStore that also maintains a index of documents for arbitrary retrieval.
 */
public interface IndexedStore<K, V> extends KVStore<K, V> {
  org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(IndexedStore.class);

  /**
   * Creates a lazy iterable over items that match the provided condition, in
   * the order requested. Exposing the appropriate keys and values. Note that
   * each iterator is independent and goes back to the source data to collect
   * data. As such, if you need to use multiple iterators, it is better to cache
   * the results. Note that this may also be internally paginating so different
   * calls to hasNext/next may have different performance characteristics.
   *
   * Note that two unexpected outcomes can occur with this iterator.
   *
   * (1) It is possible some of the values of this iterator will be null. This
   * can happen if the value is deleted around the time the iterator is created
   * and when the value is retrieved.
   *
   * (2) This iterator could return values that don't match the provided
   * conditions. This should be rare but can occur if the value was changed
   * around the time the iterator is created.
   *
   * @param find the find condition.
   *          The condition to match.
   * @param options extra configurations for find operation.
   * @return A lazy iterable over the matching Documents.
   * @throws com.dremio.datastore.DatastoreException when one or more runtime failures are encountered.
   */
  Iterable<Document<K, V>> find(FindByCondition find, FindOption ... options);

  /**
   * Provide a count of the number of documents that match each of the requested
   * conditions.
   *
   * @param conditions find conditions as search queries.
   * @return a count of the number of documents that match each of the requested conditions.
   * @throws com.dremio.datastore.DatastoreException when one or more runtime failures are encountered.
   */
  List<Integer> getCounts(SearchQuery ... conditions);


  /**
   * Reindex does a blind update on documents matching
   * the provided condition. Update will ensure indexed fields
   * are mapped according to latest version.
   *
   * @param findByCondition the find condition
   * @param options extra configurations for find operation.
   * @return a count of number of documents on which reindex is done.
   */
  default long reindex(FindByCondition findByCondition, FindOption... options) {
    Iterable<Document<K, V>> documents = find(findByCondition, options);
    AtomicLong count = new AtomicLong();
    documents.forEach( document -> {
        try {
          put(document.getKey(), document.getValue(), VersionOption.from(document));
          count.incrementAndGet();
        } catch (ConcurrentModificationException e){
          LOGGER.warn(String.format("ConcurrentModificationException while updating %s collection, key %s ", getName(), document.getKey()));
        }
      }
    );
    return count.get();
  }

  /**
   *
   * @param condition condition to search for
   * @param consumer the consumer that must be applied for each matching tuple
   * @param executor the execution context which executes the consumer
   * @param documentToTenantConverter provides tenant context by taking tenantId of the document and value of the document
   *                                  as inputs. Note, 'K' is the key of the document which is a compound key where one
   *                                  of the key is tenantId
   */
  default void applyForAllTenants(FindByCondition condition, BiConsumer<K, V> consumer, ExecutorService executor,
                                  BiFunction<String, V, TenantContext> documentToTenantConverter, FindOption... options) {
    throw new UnsupportedOperationException("Only applicable for MultiTenantKVstore");
  }

  /**
   * Version for the indicies.
   *
   *  @return version number.
   */
  Integer version();
}
