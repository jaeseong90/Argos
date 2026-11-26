/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.httpclient.apache4;

import static org.assertj.core.api.Assertions.assertThat;

import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.util.VersionInfo;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The library versions the module tests run against (T-BLD-017, ADR-0010 C-240 (10)): the {@code
 * testLowerBound} task pins httpclient 4.5 (the first 4.5 release, 4.5.0 in ADR-0010 C-240 (10))
 * and httpcore 4.4.1 and passes them as {@code argos.test.hc4.expected} and {@code
 * argos.test.hc4core.expected}; the advice tests of the same run then prove the lower boundary. The
 * default {@code test} task (Boot 2 BOM version) only prints it.
 */
@Tag("req:F-A05")
class Apache4VersionTest {

  @Test
  @DisplayName(
      "T-BLD-017 httpclient-apache4: the module tests run against the httpclient and httpcore"
          + " versions of their task (testLowerBound: 4.5 and 4.4.1)")
  void libraryVersions() {
    ClassLoader loader = CloseableHttpClient.class.getClassLoader();
    String client = release(VersionInfo.loadVersionInfo("org.apache.http.client", loader));
    String core = release(VersionInfo.loadVersionInfo("org.apache.http", loader));
    System.out.println("[wo50-hc4] httpclient " + client + ", httpcore " + core);
    String expectedClient = System.getProperty("argos.test.hc4.expected");
    String expectedCore = System.getProperty("argos.test.hc4core.expected");
    Assumptions.assumeTrue(
        expectedClient != null,
        "default test task: httpclient " + client + " from the Boot 2 BOM (not pinned)");
    assertThat(client).as("httpclient on the test class path").isEqualTo(expectedClient);
    assertThat(core).as("httpcore on the test class path").isEqualTo(expectedCore);
  }

  private static String release(VersionInfo info) {
    assertThat(info).as("version info of the library").isNotNull();
    return info.getRelease();
  }
}
