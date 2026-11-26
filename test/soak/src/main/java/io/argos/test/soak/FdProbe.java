/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.test.soak;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Open file descriptors of a process (18 §11.2 "FD 수"): the entries of {@code /proc/<pid>/fd} on
 * Linux; on Windows the process's handle count ({@code Get-Process HandleCount}), a reference that
 * includes more than files; elsewhere not available (-1).
 */
public final class FdProbe {

  /** What the probe reads. */
  public enum Source {
    LINUX_PROC("Linux /proc/<pid>/fd entries"),
    WINDOWS_HANDLES(
        "Windows Get-Process HandleCount (reference: handles include threads, events and keys,"
            + " not only files)"),
    UNAVAILABLE("not available on this operating system");

    private final String description;

    Source(String description) {
      this.description = description;
    }

    /** For the report. */
    public String description() {
      return description;
    }
  }

  private final long pid;
  private final Source source;

  private FdProbe(long pid, Source source) {
    this.pid = pid;
    this.source = source;
  }

  /** A probe of process {@code pid} on this operating system. */
  public static FdProbe of(long pid) {
    String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
    if (Files.isDirectory(Path.of("/proc", Long.toString(pid), "fd"))) {
      return new FdProbe(pid, Source.LINUX_PROC);
    }
    return new FdProbe(pid, os.contains("win") ? Source.WINDOWS_HANDLES : Source.UNAVAILABLE);
  }

  public Source source() {
    return source;
  }

  /** The count now, -1 when it cannot be read. */
  public long count() {
    try {
      return switch (source) {
        case LINUX_PROC -> {
          try (Stream<Path> fds = Files.list(Path.of("/proc", Long.toString(pid), "fd"))) {
            yield fds.count();
          }
        }
        case WINDOWS_HANDLES -> windowsHandles();
        case UNAVAILABLE -> -1;
      };
    } catch (IOException e) {
      return -1;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return -1;
    }
  }

  private long windowsHandles() throws IOException, InterruptedException {
    // No double quotes in the script: Windows command-line quoting of ProcessBuilder breaks them.
    Process p =
        new ProcessBuilder(
                "powershell",
                "-NoProfile",
                "-NonInteractive",
                "-Command",
                "(Get-Process -Id " + pid + ").HandleCount")
            .redirectErrorStream(true)
            .start();
    String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
    if (!p.waitFor(30, TimeUnit.SECONDS)) {
      p.destroyForcibly();
      return -1;
    }
    return parseCount(out);
  }

  /** The number on the last line of {@code output}, -1 when there is none. */
  static long parseCount(String output) {
    List<String> lines = output.strip().lines().toList();
    String last = lines.isEmpty() ? "" : lines.get(lines.size() - 1).strip();
    return last.matches("\\d+") ? Long.parseLong(last) : -1;
  }
}
