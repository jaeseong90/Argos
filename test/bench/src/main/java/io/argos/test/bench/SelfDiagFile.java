/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.test.bench;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.OptionalLong;

/**
 * The agent's self-diagnostics snapshot file as the bench reads it (01 §7.3 item 5, 18 §7.4, N-01
 * agent thread CPU, N-26 direct memory, N-30 premain; ADR-0010 C-95). A development (-SNAPSHOT)
 * build of the agent started with {@code -Dargos.test.selfdiag-file=<file>} rewrites {@code <file>}
 * every 2 s on {@code argos-watch} and once more at shutdown (agent-core {@code
 * SelfDiagnosticsFile}, layout {@code SelfDiagnostics.toJson()} format 1); a release build ignores
 * every {@code argos.test.*} key (T-BLD-010, ADR-0009 C-88), so the harness sets the key only for a
 * development build and never refuses less than it did before.
 *
 * <p>The harness reads three gauges: {@code agent_threads_cpu_seconds_total} (CPU seconds of the
 * live {@code ArgosThread}s since start), {@code direct_memory_bytes} (the JVM's direct buffer
 * pool) and {@code premain_ms}. Missing is not zero (C-4): a gauge the agent left out, a null, a
 * non-numeric value such as {@code "NOT_AVAILABLE"} or a negative number is absent, never 0. This
 * small parser keeps the harness free of agent dependencies.
 */
final class SelfDiagFile {
  private SelfDiagFile() {}

  /** Name of the snapshot file in a run directory. */
  static final String FILE_NAME = "selfdiag.json";

  /** The agent key (test-only, development builds; C-95). */
  static final String KEY = "argos.test.selfdiag-file";

  /** {@code source} of {@code measure.selfDiag} and of {@code premainSource}. */
  static final String SOURCE = "selfdiag-file";

  /** {@code premainSource} when the premain time comes from the agent's start banner. */
  static final String BANNER_SOURCE = "agent-log-banner";

  static final String AGENT_THREADS_CPU = "agent_threads_cpu_seconds_total";
  static final String DIRECT_MEMORY = "direct_memory_bytes";
  static final String PREMAIN = "premain_ms";

  /** The only layout the parser knows ({@code SelfDiagnostics.FORMAT} of agent-core). */
  static final int FORMAT = 1;

  /** The agent rewrites the file every 2 s; a fresh snapshot is awaited this long at most. */
  static final Duration DEFAULT_WAIT = Duration.ofSeconds(10);

  /**
   * One snapshot. {@code available} is false when there is no file or it cannot be read as format 1
   * ({@code reason} says why); the gauges are empty when the agent did not report them.
   *
   * @param modifiedMillis last-modified time of the file (ms since the epoch), -1 when unknown
   */
  record Snapshot(
      boolean available,
      String reason,
      OptionalDouble agentThreadsCpuSeconds,
      OptionalLong directMemoryBytes,
      OptionalDouble premainMs,
      long modifiedMillis) {

    static Snapshot notAvailable(String reason) {
      return new Snapshot(
          false, reason, OptionalDouble.empty(), OptionalLong.empty(), OptionalDouble.empty(), -1);
    }

    Snapshot withModified(long millis) {
      return new Snapshot(
          available, reason, agentThreadsCpuSeconds, directMemoryBytes, premainMs, millis);
    }
  }

  /** {@code -Dargos.test.selfdiag-file=<file>} with the path as the app sees it. */
  static String jvmOption(String file) {
    return "-D" + KEY + "=" + file;
  }

  /**
   * Parses the snapshot JSON of format 1. Broken JSON or another format is a not-available snapshot
   * with the reason, never an empty one that would read as zeros.
   */
  static Snapshot parse(String json) {
    Object root;
    try {
      root = Json.parse(json.strip());
    } catch (IllegalArgumentException e) {
      return Snapshot.notAvailable("unreadable self-diagnostics snapshot: " + e.getMessage());
    }
    if (!(root instanceof Map<?, ?>)) {
      return Snapshot.notAvailable("self-diagnostics snapshot is not a JSON object");
    }
    double format = Json.number(Json.at(root, "format"));
    if (format != FORMAT) {
      return Snapshot.notAvailable(
          "unsupported self-diagnostics format "
              + Json.at(root, "format")
              + " (the harness reads format "
              + FORMAT
              + ")");
    }
    OptionalDouble cpu = decimal(Json.at(root, AGENT_THREADS_CPU));
    OptionalDouble premain = decimal(Json.at(root, PREMAIN));
    OptionalDouble direct = decimal(Json.at(root, DIRECT_MEMORY));
    return new Snapshot(
        true,
        "",
        cpu,
        direct.isPresent()
            ? OptionalLong.of(Math.round(direct.getAsDouble()))
            : OptionalLong.empty(),
        premain,
        -1);
  }

  /** A measured value: a finite non-negative number; anything else is "not measured". */
  private static OptionalDouble decimal(Object value) {
    double d = Json.number(value);
    return Double.isNaN(d) || Double.isInfinite(d) || d < 0
        ? OptionalDouble.empty()
        : OptionalDouble.of(d);
  }

  /** The snapshot in {@code file}; not available (with the reason) when there is none. */
  static Snapshot read(Path file) {
    long modified = modified(file);
    String text;
    try {
      text = Files.readString(file, StandardCharsets.UTF_8);
    } catch (NoSuchFileException e) {
      return Snapshot.notAvailable(
          "the agent wrote no self-diagnostics file "
              + file.getFileName()
              + " (a release build ignores "
              + KEY
              + ", or the agent did not start)");
    } catch (IOException e) {
      return Snapshot.notAvailable("cannot read " + file.getFileName() + ": " + e.getMessage());
    }
    return parse(text).withModified(modified);
  }

  /** Last-modified time of {@code file} in ms, -1 when it does not exist. */
  static long modified(Path file) {
    try {
      return Files.getLastModifiedTime(file).toMillis();
    } catch (IOException e) {
      return -1;
    }
  }

  /**
   * Waits up to {@code timeout} for the agent's next write of {@code file}: a last-modified time
   * other than {@code previousModified} (from {@link #modified(Path)} taken before; a change, not a
   * comparison with this machine's clock, so a container's bind mount works too), then reads it. A
   * snapshot that cannot be parsed (a non-atomic replace on Windows) is read again. Without a new
   * write in time the latest snapshot is returned (or a not-available one).
   */
  static Snapshot awaitNext(Path file, long previousModified, Duration timeout)
      throws InterruptedException {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (true) {
      long now = modified(file);
      if (now >= 0 && now != previousModified) {
        Snapshot s = read(file);
        if (s.available() || System.nanoTime() > deadline) {
          return s;
        }
      }
      if (System.nanoTime() > deadline) {
        return read(file);
      }
      Thread.sleep(100);
    }
  }

  /**
   * {@code measure.selfDiag} of a measured window (contract of WO-51 lanes A and B): {@code
   * agentThreadsCpuSeconds} = end − start of {@code agent_threads_cpu_seconds_total}, {@code
   * directMemoryBytes} and {@code premainMs} of the end snapshot (the start one when the end one
   * lacks them), {@code source = "selfdiag-file"} and the seconds between the two snapshots. A
   * value that is not measured is left out (never 0). Null when neither snapshot is available.
   */
  static Map<String, Object> window(Snapshot start, Snapshot end) {
    if (!start.available() && !end.available()) {
      return null;
    }
    Map<String, Object> out = new LinkedHashMap<>();
    if (start.agentThreadsCpuSeconds().isPresent() && end.agentThreadsCpuSeconds().isPresent()) {
      double delta =
          end.agentThreadsCpuSeconds().getAsDouble() - start.agentThreadsCpuSeconds().getAsDouble();
      if (delta >= 0) {
        out.put("agentThreadsCpuSeconds", delta);
      } else {
        // The gauge sums live agent threads only: a thread that ended took its CPU time along.
        out.put(
            "agentThreadsCpuNote",
            String.format(
                Locale.ROOT,
                "not measured: %s fell by %.3f s in the window (an agent thread ended)",
                AGENT_THREADS_CPU,
                -delta));
      }
    }
    OptionalLong direct =
        end.directMemoryBytes().isPresent() ? end.directMemoryBytes() : start.directMemoryBytes();
    if (direct.isPresent()) {
      out.put("directMemoryBytes", direct.getAsLong());
    }
    OptionalDouble premain = end.premainMs().isPresent() ? end.premainMs() : start.premainMs();
    if (premain.isPresent()) {
      out.put("premainMs", premain.getAsDouble());
    }
    out.put("source", SOURCE);
    if (start.modifiedMillis() >= 0 && end.modifiedMillis() >= 0) {
      out.put("snapshotIntervalSeconds", (end.modifiedMillis() - start.modifiedMillis()) / 1000.0);
    }
    if (!start.available()) {
      out.put("startSnapshot", "not available: " + start.reason());
    }
    if (!end.available()) {
      out.put("endSnapshot", "not available: " + end.reason());
    }
    return out;
  }

  /**
   * The premain time of a run (N-30, 18 §7.4): the snapshot's {@code premain_ms} first, else the
   * agent's start banner. Fills {@code premainMs} (null when neither has it) and {@code
   * premainSource} ({@value #SOURCE}, {@value #BANNER_SOURCE} or null) of {@code run}.
   */
  static void putPremain(Map<String, Object> run, Snapshot snapshot, String agentLog) {
    if (snapshot != null && snapshot.premainMs().isPresent()) {
      run.put("premainMs", snapshot.premainMs().getAsDouble());
      run.put("premainSource", SOURCE);
      return;
    }
    OptionalDouble banner = Parsers.premainMillis(agentLog == null ? "" : agentLog);
    run.put("premainMs", banner.isPresent() ? banner.getAsDouble() : null);
    run.put("premainSource", banner.isPresent() ? BANNER_SOURCE : null);
  }
}
