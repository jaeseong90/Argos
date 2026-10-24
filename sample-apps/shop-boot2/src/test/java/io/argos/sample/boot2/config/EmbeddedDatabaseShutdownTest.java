/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.boot2.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.sample.common.BuildInfo;
import io.argos.sample.common.SampleConfig;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;

/**
 * The context-close step of the WAR app does nothing for a database other than in-memory H2. The H2
 * case (SHUTDOWN, H2 shutdown hook removed) is EmbeddedDatabaseShutdownH2Test (integrationTest:
 * slow, 18 §2.2).
 */
class EmbeddedDatabaseShutdownTest {

  @Test
  void doesNothingForOtherDatabases() {
    Map<String, String> env = new HashMap<>();
    env.put("SAMPLE_DB", "postgres");
    SampleConfig config =
        SampleConfig.from(env, new Properties(), new BuildInfo("boot2-shutdown-pg", "test"));
    EmbeddedDatabaseShutdown shutdown = new EmbeddedDatabaseShutdown(null, config);
    assertThat(shutdown.applies()).isFalse();
    // No data source access: would throw a NullPointerException otherwise.
    assertThat(shutdown.shutdownDatabase()).isFalse();
    shutdown.close();
  }
}
