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
package com.dremio.sabot.op.join.vhash;

import com.dremio.sabot.exec.context.MetricDef;

/**
 * Stats for {@link com.dremio.sabot.op.join.hash.HashJoinOperator}
 * and {@link com.dremio.sabot.op.join.vhash.VectorizedHashJoinOperator}
 * VERY IMPORTANT
 * Please add new stats at the end of Metric table and
 * be careful about changing the order of metrics and/or
 * removing metrics. Changes may result in incorrectly
 * rendering old profiles.
 */
public class HashJoinStats {

  public enum Metric implements MetricDef {

    NUM_BUCKETS,
    NUM_ENTRIES,
    NUM_RESIZING,
    RESIZING_TIME_NANOS,
    PIVOT_TIME_NANOS,
    INSERT_TIME_NANOS,
    ACCUMULATE_TIME_NANOS,
    REVERSE_TIME_NANOS,
    UNPIVOT_TIME_NANOS,
    VECTORIZED,
    PROBE_PIVOT_NANOS,
    PROBE_MATCH_NANOS,
    PROBE_LIST_NANOS,
    PROBE_FIND_NANOS,
    PROBE_COPY_NANOS,
    BUILD_COPY_NANOS,
    BUILD_COPY_NOMATCH_NANOS,
    LINK_TIME_NANOS,
    UNMATCHED_BUILD_KEY_COUNT,
    UNMATCHED_PROBE_COUNT,
    OUTPUT_RECORDS,
    HASHCOMPUTATION_TIME_NANOS,  /* used by hash agg and build side of hash join */
    PROBE_HASHCOMPUTATION_TIME_NANOS, /* used by probe side of hash join */
    RUNTIME_FILTER_DROP_COUNT,
    RUNTIME_COL_FILTER_DROP_COUNT,
    DUPLICATE_BUILD_RECORD_COUNT,
    EXTRA_CONDITION_EVALUATION_COUNT,
    EXTRA_CONDITION_EVALUATION_MATCHED,
    EXTRA_CONDITION_SETUP_NANOS,
    BUILD_CARRYOVER_COPY_NANOS,

    SPILL_COUNT, /* Number of times the operator spilled */
    SPILL_REPLAY_COUNT, /* Number of times the operator replayed spill */
    SPILL_WR_BUILD_BYTES, /* total spilled bytes, from build side */
    SPILL_RD_BUILD_BYTES, /* total replayed bytes, from build side */
    SPILL_WR_BUILD_RECORDS, /* total spilled records, from build side */
    SPILL_RD_BUILD_RECORDS, /* total replayed records, from build side */
    SPILL_WR_BUILD_BATCHES, /* total spilled batches, from build side */
    SPILL_RD_BUILD_BATCHES, /* total replayed batches, from build side */
    SPILL_RD_BUILD_BATCHES_MERGED, /* total replayed batches, from build side , after merge */
    SPILL_WR_PROBE_BYTES, /* total spilled bytes, from probe side */
    SPILL_RD_PROBE_BYTES, /* total replayed bytes, from probe side */
    SPILL_WR_PROBE_RECORDS, /* total spilled records, from probe side */
    SPILL_RD_PROBE_RECORDS, /* total replayed records, from probe side */
    SPILL_WR_PROBE_BATCHES, /* total spilled batches, from probe side */
    SPILL_RD_PROBE_BATCHES, /* total replayed batches, from probe side */
    SPILL_RD_PROBE_BATCHES_MERGED, /* total replayed batches, from probe side , after merge */
    SPILL_WR_NANOS, /* time spent in spill write */
    SPILL_RD_NANOS, /* time spent in spill read */
    OOB_SENDS, /* number of oob messages sent*/
    OOB_DROP_UNDER_THRESHOLD, /* number of oob messages ignored because of being under threshold */
    OOB_DROP_NO_VICTIM, /* number of oob messages dropped because a victim partition wasn't found */
    OOB_DROP_LOCAL, /* number of self sent oob messages ignored */
    OOB_DROP_WRONG_STATE, /* number of oob messages dropped because it was not in build phase */
    OOB_SPILL; /* number of spills performed */

    @Override
    public int metricId() {
      return ordinal();
    }
  }

}
