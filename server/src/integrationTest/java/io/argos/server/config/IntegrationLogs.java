/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.springframework.boot.logging.LoggingInitializationContext;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.core.env.StandardEnvironment;

/**
 * Where the server log of in-process integration tests goes. The server writes {@code
 * ${LOG_PATH}/argos-server.log} ({@code log4j2-spring.xml}); Spring Boot sets {@code LOG_PATH} only
 * when it is unset, so the first value of a test JVM stays. Without one the log would land in
 * {@code server/logs/server} of the source tree, so every server start in these tests first calls
 * {@link #ensureLogPath()}, which points it below {@code server/build}.
 *
 * <p>The log file outlives a test run (it is appended to by every run of the task) and rolls at 100
 * MB ({@code argos-server.log.1} becomes the previous file): a test that reads "what the server
 * wrote since offset N" uses {@link #offset()} and {@link #since(long)}, which follow one rollover,
 * instead of slicing the current file (that slice is empty after a rollover).
 *
 * <p>Tests that run the {@code argos-admin} command line in this JVM ({@code AdminCli.run}, WO-27)
 * leave Log4j on {@code log4j2-admin.xml} (standard error only), and later server starts of the JVM
 * keep it: nothing reached the log file after such a class (ADR-0010 C-226 (11): T-SV-001b failed
 * whenever it ran after ApdexReapplyIT). {@link #ensureLogPath()} reloads the server configuration
 * first.
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

  /**
   * Puts the server's {@code log4j2-spring.xml} (console and {@code ${LOG_PATH}/argos-server.log})
   * back after an in-process {@code AdminCli.run}, whose {@code log4j2-admin.xml} (standard error
   * only) later server starts of this JVM would otherwise keep (Spring Boot initializes the logging
   * system once): call it after every command run of a test.
   */
  public static synchronized void restoreServerLogging() {
    ensureLogPath();
    LoggingSystem system = LoggingSystem.get(IntegrationLogs.class.getClassLoader());
    system.cleanUp();
    system.beforeInitialize();
    system.initialize(
        new LoggingInitializationContext(new StandardEnvironment()),
        "classpath:log4j2-spring.xml",
        null);
  }

  /** The server log file of this test JVM. */
  public static Path logFile() {
    return ensureLogPath().resolve("argos-server.log");
  }

  /** The current end of the log file (0 when it does not exist yet), for {@link #since(long)}. */
  public static long offset() {
    Path log = logFile();
    try {
      return Files.exists(log) ? Files.size(log) : 0;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * What the server logged since {@code offset} (an earlier {@link #offset()}): the rest of the
   * file, or, when the file rolled over in between (the current file is shorter than the offset),
   * the rest of the rolled file {@code argos-server.log.1} followed by the whole current file.
   */
  public static String since(long offset) throws IOException {
    Path log = logFile();
    byte[] current = Files.exists(log) ? Files.readAllBytes(log) : new byte[0];
    if (current.length >= offset) {
      return new String(
          current, (int) offset, current.length - (int) offset, StandardCharsets.UTF_8);
    }
    Path rolled = log.resolveSibling(log.getFileName() + ".1");
    byte[] previous = Files.exists(rolled) ? Files.readAllBytes(rolled) : new byte[0];
    int from = (int) Math.min(offset, previous.length);
    return new String(previous, from, previous.length - from, StandardCharsets.UTF_8)
        + new String(current, StandardCharsets.UTF_8);
  }
}
