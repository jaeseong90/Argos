/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.test.bench;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Linux process and cgroup counters of 18 §7.4: process CPU from {@code /proc/<pid>/stat} (utime +
 * stime in clock ticks), threads from {@code /proc/<pid>/status}, machine CPU from {@code
 * /proc/stat}, container CPU from cgroup v2 {@code cpu.stat usage_usec}. The parsers take text, so
 * they are tested on any OS; the readers return -1 where the files do not exist (Windows, macOS).
 */
final class ProcFs {
  private ProcFs() {}

  private static final Pattern SPACES = Pattern.compile("\\s+");
  private static final Pattern STATUS_THREADS = Pattern.compile("(?m)^Threads:\\s+(\\d+)\\s*$");
  private static final Pattern CGROUP_USAGE = Pattern.compile("(?m)^usage_usec (\\d+)");

  /** Clock ticks per second of /proc times, read once ({@code getconf CLK_TCK}, 100 if unknown). */
  private static volatile long clockTicks = -1;

  /**
   * Fields of {@code /proc/<pid>/stat} used here: utime (14), stime (15), num_threads (20) and
   * starttime (22), in clock ticks except the thread count.
   */
  record Stat(long utimeTicks, long stimeTicks, long threads, long startTimeTicks) {
    long cpuTicks() {
      return utimeTicks + stimeTicks;
    }
  }

  /**
   * Parses one {@code /proc/<pid>/stat} line. The command name (field 2) is in parentheses and may
   * itself contain spaces and parentheses, so the fields are counted after the last ')'.
   */
  static Stat parseStat(String text) {
    int close = text.lastIndexOf(')');
    if (close < 0) {
      throw new IllegalArgumentException("not a /proc/<pid>/stat line: " + text);
    }
    String[] f = SPACES.split(text.substring(close + 1).trim(), -1);
    // f[0] is field 3 (state): field n is f[n - 3].
    if (f.length < 20) {
      throw new IllegalArgumentException("short /proc/<pid>/stat line: " + text);
    }
    return new Stat(
        Long.parseLong(f[14 - 3]),
        Long.parseLong(f[15 - 3]),
        Long.parseLong(f[20 - 3]),
        Long.parseLong(f[22 - 3]));
  }

  /** {@code Threads:} of {@code /proc/<pid>/status}, -1 when absent. */
  static long statusThreads(String status) {
    Matcher m = STATUS_THREADS.matcher(status);
    return m.find() ? Long.parseLong(m.group(1)) : -1;
  }

  /** The tick counters of the aggregate {@code cpu} line of {@code /proc/stat}, empty if absent. */
  static long[] cpuLine(String procStat) {
    for (String line : procStat.split("\\R", -1)) {
      String[] f = SPACES.split(line.trim(), -1);
      if (f.length >= 9 && f[0].equals("cpu")) {
        long[] ticks = new long[f.length - 1];
        for (int i = 1; i < f.length; i++) {
          ticks[i - 1] = Long.parseLong(f[i]);
        }
        return ticks;
      }
    }
    return new long[0];
  }

  /**
   * Busy share in percent between two {@code /proc/stat} cpu lines: user, nice, system, irq,
   * softirq and steal are busy, idle and iowait are idle; guest and guest_nice (fields 9 and 10)
   * are already counted in user and nice.
   */
  static double busyPercent(long[] before, long[] after) {
    if (before == null || after == null || before.length < 8 || after.length < 8) {
      return Double.NaN;
    }
    long total = 0;
    long idle = 0;
    for (int i = 0; i < 8; i++) {
      long delta = after[i] - before[i];
      total += delta;
      if (i == 3 || i == 4) {
        idle += delta;
      }
    }
    return total <= 0 ? Double.NaN : 100.0 * (total - idle) / total;
  }

  /** {@code usage_usec} of a cgroup v2 {@code cpu.stat}, -1 when absent. */
  static long cgroupUsageMicros(String cpuStat) {
    Matcher m = CGROUP_USAGE.matcher(cpuStat);
    return m.find() ? Long.parseLong(m.group(1)) : -1;
  }

  /** CPU seconds of {@code ticks} clock ticks. */
  static double ticksToSeconds(long ticks, long ticksPerSecond) {
    return (double) ticks / ticksPerSecond;
  }

  /** True when this machine has {@code /proc/self/stat} (Linux). */
  static boolean available() {
    return Files.isReadable(Path.of("/proc/self/stat"));
  }

  /** Clock ticks per second ({@code getconf CLK_TCK}); 100, the Linux value, if unknown. */
  static long clockTicksPerSecond() {
    long ticks = clockTicks;
    if (ticks > 0) {
      return ticks;
    }
    ticks = 100;
    try {
      Cmd.Result r = Cmd.run(List.of("getconf", "CLK_TCK"), Duration.ofSeconds(10));
      if (r.ok()) {
        ticks = Long.parseLong(r.output().trim());
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (NumberFormatException e) {
      // keep 100
    }
    clockTicks = ticks;
    return ticks;
  }

  /** CPU time of process {@code pid} in nanoseconds from /proc/pid/stat, -1 when unavailable. */
  static long processCpuNanos(long pid) {
    try {
      Path file = Path.of("/proc", Long.toString(pid), "stat");
      if (!Files.isReadable(file)) {
        return -1;
      }
      Stat stat = parseStat(Files.readString(file, StandardCharsets.UTF_8));
      return Math.round(ticksToSeconds(stat.cpuTicks(), clockTicksPerSecond()) * 1e9);
    } catch (IOException | RuntimeException e) {
      return -1;
    }
  }

  /** Threads of process {@code pid} from /proc/pid/status, -1 when unavailable. */
  static long processThreads(long pid) {
    try {
      Path file = Path.of("/proc", Long.toString(pid), "status");
      return Files.isReadable(file)
          ? statusThreads(Files.readString(file, StandardCharsets.UTF_8))
          : -1;
    } catch (IOException | RuntimeException e) {
      return -1;
    }
  }
}
