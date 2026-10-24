/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.boot2.http;

import static org.assertj.core.api.Assertions.assertThat;

import okhttp3.ConnectionPool;
import org.junit.jupiter.api.Test;

/**
 * The undeploy cleanup of the OkHttp client (no network: 18 §2.2). The end-to-end effect, no
 * "OkHttp ConnectionPool" thread holding the web app class loader after a WAR undeploy, needs a
 * standalone Tomcat and is checked with shop-war-javax (README, undeploy check).
 */
class OkHttp3ClientTest {

  @Test
  void theCleanupTaskOfTheOkHttpOnTheClassPathCanBeWoken() {
    // Fails when an OkHttp upgrade renames the field close() relies on.
    assertThat(OkHttp3Client.wakeCleanupTask(new ConnectionPool())).isTrue();
  }

  @Test
  void closeWithoutAnyCallIsHarmless() {
    OkHttp3Client client = new OkHttp3Client(100, 100);
    client.close();
    client.close();
    assertThat(client.name()).isEqualTo("okhttp");
  }
}
