/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.config;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Where the server log of in-process integration tests goes. The server writes {@code
 * ${LOG_PATH}/argos-server.log} ({@code log4j2-spring.xml}); Spring Boot sets {@code LOG_PATH} only
 * when it is unset, so the first value of a test JVM stays. Without one the log would land in
 * {@code server/logs/server} of the source tree, so every server start in these tests first calls
 * {@link #ensureLogPath()}, which points it below {@code server/build}.
 */
public final class IntegrationLogs {

  private IntegrationLogs() {}

  /** The log directory of this test JVM (set to {@code server/build/test-logs} when unset). */
  public static synchronized Path ensureLogPath() {
    String current = System.getProperty("LOG_PATH");
    if (current == null || current.isBlank()) {
      String root = System.getProperty("argos.repositoryRoot", ".");
      Path dir =
          Paths.get(root, "server", "build", "test-logs", "integrationTest").toAbsolutePath();
      System.setProperty("LOG_PATH", dir.toString());
      return dir;
    }
    return Paths.get(current);
  }

  /** The server log file of this test JVM. */
  public static Path logFile() {
    return ensureLogPath().resolve("argos-server.log");
  }
}
