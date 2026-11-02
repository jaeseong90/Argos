/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.test.bench;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Linux /proc and cgroup v2 parsers of 18 §7.4 (samples in the kernel's documented formats). */
class ProcFsTest {

  /** A JVM's /proc/<pid>/stat: utime 41234, stime 5678 ticks, 65 threads, start 987654. */
  static final String STAT =
      "22740 (java) S 1 22740 22740 0 -1 1077936384 52011 0 3 0 41234 5678 0 0 20 0 65 0 987654"
          + " 6015586304 140123 18446744073709551615 1 1 0 0 0 0 0 4096 16796879 0 0 0 17 3 0 0"
          + " 0 0 0 0 0 0 0 0 0 0 0";

  @Test
  @DisplayName("utime + stime of /proc/<pid>/stat, also when the command name has spaces")
  void processStat() {
    ProcFs.Stat stat = ProcFs.parseStat(STAT);
    assertThat(stat.utimeTicks()).isEqualTo(41234);
    assertThat(stat.stimeTicks()).isEqualTo(5678);
    assertThat(stat.cpuTicks()).isEqualTo(46912);
    assertThat(stat.threads()).isEqualTo(65);
    assertThat(stat.startTimeTicks()).isEqualTo(987654);
    assertThat(ProcFs.ticksToSeconds(stat.cpuTicks(), 100)).isCloseTo(469.12, within(1e-9));
    // comm may contain spaces and parentheses: fields are counted after the last ')'
    ProcFs.Stat odd = ProcFs.parseStat(STAT.replace("(java)", "(my (odd) app)"));
    assertThat(odd.cpuTicks()).isEqualTo(46912);
    assertThatThrownBy(() -> ProcFs.parseStat("no fields"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ProcFs.parseStat("1 (x) S 1 2"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("Threads: of /proc/<pid>/status")
  void statusThreads() {
    String status =
        """
        Name:\tjava
        Umask:\t0022
        State:\tS (sleeping)
        Tgid:\t22740
        Threads:\t65
        SigQ:\t0/63445
        """;
    assertThat(ProcFs.statusThreads(status)).isEqualTo(65);
    assertThat(ProcFs.statusThreads("Name:\tjava\n")).isEqualTo(-1);
  }

  @Test
  @DisplayName("machine busy share from two /proc/stat cpu lines")
  void procStatBusyShare() {
    String before = "cpu  1000 0 500 8000 100 0 0 0 0 0\ncpu0 500 0 250 4000 50 0 0 0 0 0\n";
    String after = "cpu  1600 0 600 8300 100 0 0 0 50 0\ncpu0 800 0 300 4150 50 0 0 0 25 0\n";
    long[] a = ProcFs.cpuLine(before);
    long[] b = ProcFs.cpuLine(after);
    assertThat(a).hasSize(10);
    // 700 busy of 1000 ticks; guest (50) is part of user and is not counted twice
    assertThat(ProcFs.busyPercent(a, b)).isCloseTo(70.0, within(1e-9));
    assertThat(ProcFs.cpuLine("intr 1 2 3\n")).isEmpty();
    assertThat(ProcFs.busyPercent(new long[0], b)).isNaN();
    assertThat(ProcFs.busyPercent(a, a)).isNaN();
  }

  @Test
  @DisplayName("usage_usec of a cgroup v2 cpu.stat")
  void cgroupV2CpuStat() {
    String cpuStat =
        """
        usage_usec 104904123
        user_usec 87484000
        system_usec 17420123
        nr_periods 600
        nr_throttled 12
        throttled_usec 3400
        """;
    assertThat(ProcFs.cgroupUsageMicros(cpuStat)).isEqualTo(104904123L);
    assertThat(ProcFs.cgroupUsageMicros("nothing")).isEqualTo(-1);
    // the moved WO-02 entry point delegates
    assertThat(Parsers.cgroupUsageMicros(cpuStat)).isEqualTo(104904123L);
  }
}
