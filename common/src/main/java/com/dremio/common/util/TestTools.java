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
package com.dremio.common.util;

import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

import org.junit.rules.TestName;
import org.junit.rules.TestRule;
import org.junit.rules.Timeout;

import com.dremio.common.VM;

public class TestTools {

  static final String WORKING_PATH = Paths.get("").toAbsolutePath().toString();

  public static TestRule getTimeoutRule() {
    return getTimeoutRule(10000, TimeUnit.MILLISECONDS);
  }

  public static TestRule getTimeoutRule(int timeout, TimeUnit unit) {
    return VM.isDebugEnabled() ? new TestName() : Timeout.builder().withTimeout(timeout, unit).build();
  }

  /**
   * If not enforced, the repeat rule applies only if the test is run in non-debug mode.
   */
  public static TestRule getRepeatRule(final boolean enforce) {
    return enforce || !VM.isDebugEnabled() ? new RepeatTestRule() : new TestName();
  }

  public static String getWorkingPath() {
    return WORKING_PATH;
  }

  private static final String PATH_SEPARATOR = System.getProperty("file.separator");
  private static final String[] STRUCTURE = {"dremio", "exec", "java-exec", "src", "test", "resources"};

  /**
   * Returns fully qualified path where test resources reside if current working directory is at any level in the
   * following root->exec->java-exec->src->test->resources, throws an {@link IllegalStateException} otherwise.
   */
  public static String getTestResourcesPath() {
    final StringBuilder builder = new StringBuilder(WORKING_PATH);
    for (int i=0; i< STRUCTURE.length; i++) {
      if (WORKING_PATH.endsWith(STRUCTURE[i])) {
        for (int j=i+1; j< STRUCTURE.length; j++) {
          builder.append(PATH_SEPARATOR).append(STRUCTURE[j]);
        }
        return builder.toString();
      }
    }
    final String msg = String.format("Unable to recognize working directory[%s]. The workspace must be root or exec " +
        "module.", WORKING_PATH);
    throw new IllegalStateException(msg);
  }


}
