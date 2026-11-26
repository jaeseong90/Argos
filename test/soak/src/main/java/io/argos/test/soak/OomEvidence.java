/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.test.soak;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The OOM evidence of T-NFR-18 ("OOM 0", N-18), collected like the server load run (WO-29
 * LoadRunner): an exited process, {@code hs_err} crash files and heap dumps in a work directory,
 * {@code OutOfMemoryError} in a process log (server console, app stdout and stderr, agent log),
 * {@code MEMORY_LIMIT_EXCEEDED} in the ClickHouse error log, a Docker container {@code OOMKilled},
 * and on Linux new OOM-killer lines in the kernel log ({@code dmesg}) since the start. Every
 * finding is one line of the report; no finding is the PASS of the invariant.
 */
public final class OomEvidence {

  /** A line of a JVM or app log that names an out-of-memory error. */
  static final Pattern JVM_OOM = Pattern.compile("OutOfMemoryError");

  /** A kernel log line of the OOM killer. */
  static final Pattern KERNEL_OOM =
      Pattern.compile("(?i)(out of memory: kill|oom-kill|killed process \\d+|oom_reaper)");

  private final List<String> findings = new ArrayList<>();
  private final List<String> unavailable = new ArrayList<>();

  /** The findings so far. */
  public List<String> findings() {
    return List.copyOf(findings);
  }

  /** Checks that could not run (reported, not a finding). */
  public List<String> unavailable() {
    return List.copyOf(unavailable);
  }

  /** Adds a finding. */
  public void add(String finding) {
    findings.add(finding);
  }

  /** Records a check that could not run. */
  public void notAvailable(String what) {
    unavailable.add(what);
  }

  /** A process that should still run but exited (with its exit code, or -1). */
  public void exited(String process, boolean alive, int exitCode) {
    if (!alive) {
      findings.add(process + " exited with " + exitCode + " during the run");
    }
  }

  /**
   * {@code hs_err_pid*.log} and {@code *.hprof} files directly in {@code directory} ({@code
   * -XX:ErrorFile} and {@code -XX:HeapDumpPath} of the run's JVMs).
   */
  public void crashFiles(String process, Path directory) {
    if (!Files.isDirectory(directory)) {
      return;
    }
    try (Stream<Path> files = Files.list(directory)) {
      for (Path f : files.sorted().toList()) {
        String name = f.getFileName().toString();
        if (name.startsWith("hs_err_pid") || name.endsWith(".hprof")) {
          findings.add(process + " wrote " + name);
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** {@code OutOfMemoryError} lines of {@code log} (a missing file has none). */
  public void logFile(String process, Path log) {
    long n = countLines(log, JVM_OOM);
    if (n > 0) {
      findings.add(n + " OutOfMemoryError line(s) in " + process + " " + log.getFileName());
    }
  }

  /** {@code MEMORY_LIMIT_EXCEEDED} lines of the ClickHouse error log. */
  public void clickHouseMemoryLimit(long lines) {
    if (lines > 0) {
      findings.add(lines + " MEMORY_LIMIT_EXCEEDED line(s) in the ClickHouse error log");
    }
  }

  /** A container the OOM killer ended. */
  public void containerOomKilled(String name, boolean oomKilled) {
    if (oomKilled) {
      findings.add(name + " container OOMKilled");
    }
  }

  /**
   * New OOM-killer lines of the kernel log: {@code before} and {@code after} are two {@code dmesg}
   * outputs (null when it could not be read, for example without the privilege).
   */
  public void kernelLog(String before, String after) {
    if (before == null || after == null) {
      unavailable.add("dmesg (not Linux, or not readable without privileges)");
      return;
    }
    long added = count(after, KERNEL_OOM) - count(before, KERNEL_OOM);
    if (added > 0) {
      findings.add(added + " new OOM-killer line(s) in the kernel log (dmesg)");
    }
  }

  /** Lines of {@code text} matching {@code pattern}. */
  static long count(String text, Pattern pattern) {
    return text.lines().filter(l -> pattern.matcher(l).find()).count();
  }

  /** Lines of {@code file} matching {@code pattern}, streamed (0 for a missing file). */
  static long countLines(Path file, Pattern pattern) {
    if (!Files.isRegularFile(file)) {
      return 0;
    }
    try (Stream<String> lines = Files.lines(file, StandardCharsets.UTF_8)) {
      return lines.filter(l -> pattern.matcher(l).find()).count();
    } catch (UncheckedIOException e) {
      // Not UTF-8 (a crash or a console in another encoding): read byte-wise as ISO-8859-1.
      try (Stream<String> lines = Files.lines(file, StandardCharsets.ISO_8859_1)) {
        return lines.filter(l -> pattern.matcher(l).find()).count();
      } catch (IOException io) {
        throw new UncheckedIOException(io);
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
