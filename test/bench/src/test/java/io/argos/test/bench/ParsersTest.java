/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.test.bench;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Samples are trimmed copies of real JDK 21 output (shop-boot3 with the agent, 2026-10-24). */
class ParsersTest {

  static final String THREADS_START =
      """
      22740:
      2026-10-24 19:26:05
      Full thread dump OpenJDK 64-Bit Server VM (21.0.10+7-LTS mixed mode, sharing):

      "main" #1 [17020] prio=5 os_prio=0 cpu=4593.75ms elapsed=20.12s tid=0x000001cb2d7b8f40 nid=17020 waiting on condition  [0x0000007ba5bfe000]
         java.lang.Thread.State: TIMED_WAITING (sleeping)

      "argos-logger" #20 [17676] daemon prio=5 os_prio=0 cpu=15.62ms elapsed=18.61s tid=0x000001cb6184b140 nid=17676 waiting on condition  [0x0000007ba7cff000]
         java.lang.Thread.State: WAITING (parking)

      "argos-sender" #30 [26660] daemon prio=5 os_prio=0 cpu=15.62ms elapsed=18.23s tid=0x000001cb61b682e0 nid=26660 waiting on condition  [0x0000007ba87ff000]

      "argos-watch" #31 [26208] daemon prio=5 os_prio=0 cpu=0.00ms elapsed=18.22s tid=0x000001cb61e28270 nid=26208 waiting on condition  [0x0000007ba88ff000]

      "VM Thread" os_prio=2 cpu=31.25ms elapsed=20.10s tid=0x000001cb5f2d1a40 nid=10984 runnable

      "G1 Conc#0" os_prio=2 cpu=0.00ms elapsed=20.11s tid=0x000001cb2d84e350 nid=24868 runnable
      """;

  @Test
  void threadDumpCountsJavaThreadsAndAgentCpu() {
    Parsers.ThreadDump dump = Parsers.threadDump(THREADS_START);
    assertThat(dump.javaThreads()).isEqualTo(4); // VM-internal threads have no "#N"
    assertThat(dump.agentThreadCpuMs())
        .containsOnlyKeys("argos-logger", "argos-sender", "argos-watch");
    assertThat(dump.agentCpuMs()).isCloseTo(31.24, within(1e-9));
    Parsers.ThreadDump end =
        Parsers.threadDump(
            THREADS_START
                .replace("cpu=15.62ms elapsed=18.61s", "cpu=140.62ms elapsed=618.61s")
                .replace("cpu=0.00ms elapsed=18.22s", "cpu=46.88ms elapsed=618.22s"));
    assertThat(LoadSet.agentThreadCpuMs(dump, end)).isCloseTo(125.0 + 46.88, within(1e-9));
  }

  @Test
  void classHistogramSumsAgentClassesOnly() {
    String text =
        """
        22740:
         num     #instances         #bytes  class name (module)
        -------------------------------------------------------
           1:        123456       12345678  [B (java.base@21.0.10)
         113:           259          20720  io.argos.agent.shaded.net.bytebuddy.pool.TypePool$Default$LazyTypeDescription$MethodToken
         150:            40           3200  io.argos.agent.core.trace.Transaction
         160:             2             64  [Lio.argos.agent.bootstrap.StepType;
         170:            51           1368  io.argos.sample.boot3.OrderEntity
        Total        123808       12370998
        """;
    Parsers.AgentHeap heap = Parsers.classHistogram(text);
    assertThat(heap.instances()).isEqualTo(259 + 40 + 2);
    assertThat(heap.bytes()).isEqualTo(20720 + 3200 + 64);
    assertThat(heap.totalBytes()).isEqualTo(12345678L + 20720 + 3200 + 64 + 1368);
    assertThat(heap.topClasses()).hasSize(3);
  }

  static final String NMT =
      """
      22740:

      Native Memory Tracking:

      (Omitting categories weighting less than 1KB)

      Total: reserved=2152024KB, committed=775932KB
             malloc: 75152KB #393201
             mmap:   reserved=2076872KB, committed=700780KB

      -                 Java Heap (reserved=524288KB, committed=524288KB)
                                  (mmap: reserved=524288KB, committed=524288KB)

      -                     Class (reserved=1050599KB, committed=16551KB)
                                  (classes #21658)
                                  (  instance classes #19995, array classes #1663)
                                  (malloc=2023KB #59981) (peak=2279KB #66985)
                                  (mmap: reserved=1048576KB, committed=14528KB, at peak)
                                  (  Metadata:   )
                                  (    reserved=131072KB, committed=90240KB)
                                  (    used=89238KB)
                                  (    waste=1002KB =1.11%)
                                  (  Class space:)
                                  (    reserved=1048576KB, committed=14528KB)
                                  (    used=13833KB)
                                  (    waste=695KB =4.79%)

      -                    Thread (reserved=65717KB, committed=3933KB)
                                  (thread #65)
                                  (stack: reserved=65536KB, committed=3752KB)

      -                  Internal (reserved=1264KB, committed=1264KB)
                                  (malloc=1200KB #30058)

      -                     Other (reserved=118KB, committed=118KB)
                                  (malloc=118KB #12)

      -    Native Memory Tracking (reserved=6726KB, committed=6726KB)
      """;

  @Test
  void nativeMemorySummary() {
    Parsers.NativeMemory nmt = Parsers.nativeMemory(NMT);
    assertThat(nmt.totalCommittedKb()).isEqualTo(775932);
    assertThat(nmt.committed("Java Heap")).isEqualTo(524288);
    assertThat(nmt.committed("Internal")).isEqualTo(1264);
    assertThat(nmt.committed("Other")).isEqualTo(118);
    assertThat(nmt.committed("Native Memory Tracking")).isEqualTo(6726);
    assertThat(nmt.committed("Missing")).isZero();
    assertThat(nmt.threads()).isEqualTo(65);
    assertThat(nmt.classes()).isEqualTo(21658);
    assertThat(nmt.metaspaceUsedKb()).isEqualTo(89238 + 13833);
    assertThat(nmt.metaspaceCommittedKb()).isEqualTo(90240 + 14528);
  }

  @Test
  void cgroupBannerAndSpringLines() {
    assertThat(Parsers.cgroupUsageMicros("usage_usec 104904\nuser_usec 17484\n")).isEqualTo(104904);
    assertThat(Parsers.cgroupUsageMicros("nothing")).isEqualTo(-1);
    String log =
        "2026-10-24T19:25:47.139+09:00 INFO  [main] Argos agent 0.0.0-SNAPSHOT (commit 4a43e77, protocol 1)"
            + " started in 464.816 ms: app=- env=default exporter=noop modules=[servlet] enabled=true\n";
    assertThat(Parsers.premainMillis(log).getAsDouble()).isEqualTo(464.816);
    assertThat(Parsers.premainMillis("no banner")).isEmpty();
    double[] started =
        Parsers.springStarted(
            "... : Started ShopBoot3Application in 10.634 seconds (process running for 12.255)\n");
    assertThat(started).containsExactly(10.634, 12.255);
    assertThat(Parsers.springStarted("Started App in 3.5 seconds\n")[1]).isNaN();
    assertThat(Parsers.springStarted("nothing")).isEmpty();
  }

  @Test
  void sidecarSamplesGiveAppTimelineK6CpuAndMachineLoad() {
    String samples =
        String.join(
            "\n",
            List.of(
                "S 100.00 5000000 - cpu 1000 0 500 8000 100 0 0 0 0 0",
                "S 101.00 6000000 200000 cpu 1600 0 600 8300 100 0 0 0 0 0",
                "S 102.00 7500000 900000 cpu 2200 0 700 8600 100 0 0 0 0 0",
                "garbage line",
                "S 103.00 8000000 - cpu 2300 0 700 9500 100 0 0 0 0 0"));
    ContainerPlatform.Sidecar s = ContainerPlatform.parseSidecar(samples);
    assertThat(s.app()).hasSize(4);
    assertThat(s.app().get(2)).containsExactly(2.0, 2.5);
    assertThat(s.k6CpuSeconds()).isCloseTo(0.9, within(1e-9));
    assertThat(s.machine()).hasSize(3);
    // second interval: busy 700 of 1000 ticks
    assertThat(s.machine().get(0).get(1)).isCloseTo(70.0, within(1e-9));
    assertThat(s.machine().get(2).get(1)).isCloseTo(10.0, within(1e-9));
  }

  @Test
  void gcWindowCountsPausesAndAllocationBetweenThem() {
    String log =
        String.join(
            "\n",
            List.of(
                "[0.042s][info][gc] Using G1",
                "[1.261s][info][gc] GC(0) Pause Young (Normal) (G1 Evacuation Pause) 51M->4M(1024M) 4.903ms",
                "[10.000s][info][gc] GC(1) Pause Young (Normal) (G1 Evacuation Pause) 504M->6M(1024M) 5.000ms",
                "[20.000s][info][gc] GC(2) Pause Young (Concurrent Start) (G1 Evacuation Pause) 506M->8M(1024M) 6.000ms",
                "[20.001s][info][gc] GC(3) Concurrent Mark Cycle",
                "[20.010s][info][gc] GC(3) Pause Remark 10M->10M(1024M) 1.000ms",
                "[30.000s][info][gc] GC(4) Pause Young (Normal) (G1 Evacuation Pause) 1G->10240K(1024M) 7.000ms",
                "[40.000s][info][gc] GC(5) Pause Young (Normal) (G1 Evacuation Pause) 600M->10M(1024M) 8.000ms"));
    // window 5..35 s: GC(1), GC(2), remark, GC(4); allocation 500 + 500 + 2 + 1014 MB
    Map<String, Object> w = GcLog.window(log, 5, 35);
    assertThat(w.get("pauses")).isEqualTo(4);
    assertThat(w.get("youngPauses")).isEqualTo(3);
    assertThat((Double) w.get("pauseMsTotal")).isCloseTo(19.0, within(1e-9));
    assertThat((Double) w.get("allocatedMbBetweenPauses"))
        .isCloseTo(500 + 500 + 2 + 1014, within(1e-9));
    assertThat(GcLog.uptimeOfThreadDump(THREADS_START)).isEqualTo(20.12);
    assertThat(GcLog.uptimeOfThreadDump("no dump")).isNaN();
  }

  @Test
  void loadMachineBusyShareFromProcStat() {
    long[] before = {1000, 0, 500, 8000, 100, 0, 0, 0, 0, 0};
    long[] after = {1600, 0, 600, 8300, 100, 0, 0, 0, 50, 0};
    assertThat(ProcessPlatform.busyPercent(before, after)).isCloseTo(70.0, within(1e-9));
    assertThat(ProcessPlatform.busyPercent(new long[0], after)).isNaN();
  }
}
