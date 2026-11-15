/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.launcher;

import io.argos.agent.testing.baseline.TransformList;
import io.argos.agent.testing.baseline.TransformLog;
import io.argos.agent.testing.collector.Collected;
import io.argos.agent.testing.export.FileExporterReader;
import io.argos.agent.testing.jvm.JcmdClient;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * A sample app started by {@link AppLauncher} (18 §5.2, DSN-18-04): a local process ({@link
 * ProcessApp}) or a container ({@link ContainerApp}), with the same view.
 *
 * <p>Stop semantics: {@link #close()} and {@link #gracefulStop()} ask the process to end (SIGTERM
 * on Linux and macOS, so JVM shutdown hooks and the agent's shutdown flush run); {@link #kill9()}
 * ends it at once. On Windows both end the process without shutdown hooks, so tests wait for what
 * the agent writes periodically (JSONL about every 100 ms, transform list and log about every 2 s)
 * instead of relying on the shutdown flush.
 */
public interface RunningApp extends AutoCloseable {
  SampleApp app();

  /** Feature version of the JDK the app runs on. */
  int jdk();

  /** The HTTP port on 127.0.0.1 (the mapped port of a container). */
  int port();

  /** The process id (-1 for a container). */
  long pid();

  boolean isAlive();

  /** The run's work directory (see {@link AppLauncher}). */
  Path workDir();

  /** The agent's {@code log.dir} ({@code <workDir>/logs}). */
  default Path agentLogDir() {
    return workDir().resolve("logs");
  }

  /** The command line that started the app. */
  List<String> command();

  /**
   * Whether the run has the Argos agent ({@code -javaagent:.../argos-agent.jar}, see {@link
   * AppLauncher}).
   */
  default boolean agentAttached() {
    return command().stream()
        .anyMatch(arg -> arg.startsWith("-javaagent:") && arg.endsWith("argos-agent.jar"));
  }

  default String baseUrl() {
    return "http://127.0.0.1:" + port();
  }

  /** One HTTP/1.1 call (no body, no redirects, 30 s timeout). */
  HttpResult call(String method, String path, Map<String, String> headers)
      throws IOException, InterruptedException;

  default HttpResult get(String path) throws IOException, InterruptedException {
    return call("GET", path, Map.of());
  }

  /**
   * A small open-model load for integration tests: {@code rps} GET requests per second for {@code
   * duration}, cycling through {@code paths} (not the overhead benchmark: that is {@code
   * test/bench}).
   */
  LoadResult load(int rps, Duration duration, String... paths) throws InterruptedException;

  /**
   * What the agent sent: the fake collector's view when the launcher had one, else the JSONL of
   * {@code argos.test.exporter=file:<workDir>/out}.
   */
  Collected collected();

  /** The JSONL exporter output ({@code <workDir>/out/argos-transactions.jsonl}). */
  default FileExporterReader transactions() {
    return FileExporterReader.inDirectory(workDir().resolve("out"));
  }

  /**
   * The agent's self-diagnostics snapshot file ({@code argos.test.selfdiag-file} of the harness
   * defaults, {@code <workDir>/out/selfdiag.json}, ADR-0010 C-95).
   */
  default Path selfDiagnosticsFile() {
    return workDir().resolve("out").resolve(AppLauncher.SELF_DIAGNOSTICS_FILE);
  }

  /**
   * The self-diagnostics of this run read from {@link #selfDiagnosticsFile()}: not available when
   * no agent is attached, when the run does not set {@code argos.test.selfdiag-file}, or when the
   * agent wrote no file (a release build ignores {@code argos.test.*}).
   */
  default SelfDiagnostics readSelfDiagnostics() {
    if (!agentAttached()) {
      return SelfDiagnostics.notAvailable("no Argos agent is attached to this run");
    }
    if (command().stream().noneMatch(arg -> arg.startsWith("-Dargos.test.selfdiag-file="))) {
      return SelfDiagnostics.notAvailable(
          "the run does not set argos.test.selfdiag-file (harness defaults are off)");
    }
    return SelfDiagnostics.read(selfDiagnosticsFile());
  }

  /**
   * A self-diagnostics snapshot the agent wrote after {@code after} (it rewrites the file every 2
   * s): waits up to {@code timeout}, then returns the latest snapshot, or a not-available one that
   * says no fresh snapshot came.
   */
  default SelfDiagnostics awaitSelfDiagnostics(Instant after, Duration timeout) {
    Path file = selfDiagnosticsFile();
    boolean fresh =
        Polling.await(
            () -> {
              try {
                return Files.getLastModifiedTime(file).toInstant().isAfter(after);
              } catch (IOException e) {
                return false;
              }
            },
            Boolean::booleanValue,
            timeout);
    SelfDiagnostics diagnostics = selfDiagnostics();
    if (!fresh && diagnostics.available()) {
      return SelfDiagnostics.notAvailable(
          "no self-diagnostics snapshot written after " + after + " within " + timeout);
    }
    return diagnostics;
  }

  /** {@code jcmd <pid> ...} with the app's own JDK. */
  JcmdClient jcmd();

  /**
   * The agent's self-diagnostics (18 §5.2, 10 §19): the latest snapshot of {@link
   * #selfDiagnosticsFile()} ({@link #readSelfDiagnostics()}); a run without one gets a
   * not-available object that says why instead of returning zeros.
   */
  SelfDiagnostics selfDiagnostics();

  /** Process creation to port binding (ADR-0004 C-49, N-04). */
  Duration startupTime();

  /** Process creation to the first 200 of the readiness path. */
  Duration readyTime();

  default long spawnToPortMillis() {
    return startupTime().toMillis();
  }

  default long spawnToReadyMillis() {
    return readyTime().toMillis();
  }

  /** Ends the process at once (SIGKILL / TerminateProcess). */
  void kill9();

  /** Asks the process to end and waits up to 15 s (see the class comment for Windows). */
  void gracefulStop();

  List<String> stdoutLines();

  List<String> stderrLines();

  /** The agent log ({@code logs/argos-agent.log}), empty when absent. */
  String agentLog();

  /** {@code -Dargos.debug.transform-list} ({@code <workDir>/out/transformed.txt}). */
  default Path transformListFile() {
    return workDir().resolve("out").resolve("transformed.txt");
  }

  /** {@code -Dargos.debug.transform-log} ({@code <workDir>/out/transform.log}). */
  default Path transformLogFile() {
    return workDir().resolve("out").resolve("transform.log");
  }

  /** The transformed-class list as written so far (empty before the first write). */
  default TransformList transformList() {
    return TransformList.read(transformListFile());
  }

  /** The transform log as written so far (empty before the first write). */
  default TransformLog transformLog() {
    return TransformLog.read(transformLogFile());
  }

  /** Waits (polling every 200 ms) until the transformed-class list satisfies {@code condition}. */
  default TransformList awaitTransformList(Predicate<TransformList> condition, Duration timeout) {
    return Polling.await(this::transformList, condition, timeout);
  }

  /** Waits (polling every 200 ms) until the transform log satisfies {@code condition}. */
  default TransformLog awaitTransformLog(Predicate<TransformLog> condition, Duration timeout) {
    return Polling.await(this::transformLog, condition, timeout);
  }

  /**
   * Every entry created in the app's temp directory since before the spawn (T-BLD-006): top-level
   * entries seen by a watch service (also ones deleted again) plus the current tree.
   */
  Set<String> tempCreated();

  /** Top-level temp entries seen by the watch service, with ms after the watch started. */
  Map<String, Long> tempEvents();

  /** Command, startup and the tail of stdout/stderr, for assertion messages. */
  String describe();

  /** Stops the app (see the class comment) and waits for the process to end. */
  @Override
  void close();
}
