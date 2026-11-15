/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.launcher;

import io.argos.agent.testing.baseline.TransformList;
import io.argos.agent.testing.baseline.TransformLog;
import io.argos.agent.testing.collector.Collected;
import io.argos.agent.testing.collector.JsonlCollected;
import io.argos.agent.testing.export.FileExporterReader;
import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Properties;

/**
 * A {@link SampleApp#mainClass} app run to completion by {@link AppLauncher#runToExit} (the attach
 * tests' trivial app).
 *
 * @param app the app
 * @param jdk feature version of the JDK
 * @param dir the run's work directory
 * @param command the command line
 * @param exitCode the exit code (-1 after a timeout)
 * @param timedOut whether the run was killed at the timeout
 * @param stdout everything on stdout
 * @param stderr everything on stderr
 * @param tempEvents every event in the run's temp directory and the entries left there ({@link
 *     TempWatch#eventsAndLeftovers()}: T-BLD-006 expects none)
 * @param systemTempAdded agent-like entries that appeared in the harness's own temp directory
 *     ({@link AppLauncher#watchSystemTemp})
 * @param wallTime spawn to exit
 */
public record ExitedApp(
    SampleApp app,
    int jdk,
    Path dir,
    List<String> command,
    int exitCode,
    boolean timedOut,
    String stdout,
    String stderr,
    List<String> tempEvents,
    List<String> systemTempAdded,
    Duration wallTime) {

  public ExitedApp {
    command = List.copyOf(command);
    tempEvents = List.copyOf(tempEvents);
    systemTempAdded = List.copyOf(systemTempAdded);
  }

  /** {@code <dir>/logs} ({@code argos.log.dir}). */
  public Path logs() {
    return dir.resolve("logs");
  }

  /** {@code <dir>/out}. */
  public Path out() {
    return dir.resolve("out");
  }

  /** {@code <dir>/logs/argos-agent.log}. */
  public Path agentLogFile() {
    return logs().resolve("argos-agent.log");
  }

  /**
   * The agent log. A run with the agent writes it, so a missing file fails the calling test with an
   * {@link AssertionError} (a {@code doesNotContain} check on an absent log would otherwise pass);
   * use {@link #agentLogIfPresent()} where no log is a valid outcome.
   */
  public String agentLog() {
    return agentLogIfPresent()
        .orElseThrow(
            () ->
                new AssertionError(
                    "the agent log " + agentLogFile() + " does not exist\n" + describe()));
  }

  /** The agent log, or empty when the run wrote none (kill switch, no agent). */
  public Optional<String> agentLogIfPresent() {
    Path log = agentLogFile();
    try {
      return Files.exists(log)
          ? Optional.of(Files.readString(log, StandardCharsets.UTF_8))
          : Optional.empty();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** The JSONL of {@code argos.test.exporter=file:<dir>/out}. */
  public FileExporterReader transactions() {
    return FileExporterReader.inDirectory(out());
  }

  /** {@link #transactions()} as {@link Collected}. */
  public Collected collected() {
    return new JsonlCollected(transactions());
  }

  public Path transformListFile() {
    return out().resolve("transformed.txt");
  }

  public TransformList transformList() {
    return TransformList.read(transformListFile());
  }

  /** The raw lines of the transformed-class list (empty when absent). */
  public List<String> transformListLines() {
    try {
      return Files.exists(transformListFile())
          ? Files.readAllLines(transformListFile(), StandardCharsets.UTF_8)
          : List.of();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  public TransformLog transformLog() {
    return TransformLog.read(out().resolve("transform.log"));
  }

  /**
   * The agent's last self-diagnostics snapshot ({@code out/selfdiag.json} of {@code
   * argos.test.selfdiag-file}, rewritten at shutdown, ADR-0010 C-95); not available when the run
   * wrote none.
   */
  public SelfDiagnostics selfDiagnostics() {
    return SelfDiagnostics.read(out().resolve(AppLauncher.SELF_DIAGNOSTICS_FILE));
  }

  /**
   * The classes the agent class loader defined on the premain thread ({@code
   * out/selfdiag.json.premain-classes}, ADR-0009 C-79), without the header; empty when absent.
   */
  public List<String> premainClasses() {
    Path file = out().resolve(AppLauncher.SELF_DIAGNOSTICS_FILE + ".premain-classes");
    try {
      return Files.exists(file)
          ? Files.readAllLines(file, StandardCharsets.UTF_8).stream()
              .filter(line -> !line.isBlank() && !line.startsWith("#"))
              .toList()
          : List.of();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** A properties file the app wrote ({@code out/<name>}). */
  public Properties properties(String name) {
    Properties properties = new Properties();
    try (Reader reader =
        Files.newBufferedReader(out().resolve(name), StandardCharsets.ISO_8859_1)) {
      properties.load(reader);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return properties;
  }

  /** Non-blank stderr lines. */
  public List<String> stderrLines() {
    return stderr.lines().filter(line -> !line.isBlank()).toList();
  }

  /** Command, exit code and outputs, for assertion messages. */
  public String describe() {
    return "JDK "
        + jdk
        + " in "
        + dir
        + " (exit "
        + exitCode
        + (timedOut ? ", timed out" : "")
        + ")\ncommand: "
        + String.join(" ", command)
        + "\nstdout:\n"
        + stdout
        + "\nstderr:\n"
        + stderr;
  }
}
