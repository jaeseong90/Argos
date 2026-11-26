/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.load;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.ContainerPort;
import io.argos.server.testing.load.DockerContainers;
import io.argos.server.testing.load.Jcmd;
import io.argos.server.testing.load.ResidentSetProbe;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("req:N-06")
@Tag("req:N-18")
class ProbeParsersTest {

  @Test
  @DisplayName("T-NFR-06a /proc/<pid>/status gives VmRSS and VmHWM in bytes")
  void procStatus() {
    String status =
        """
        Name:\tjava
        VmPeak:\t 9999999 kB
        VmHWM:\t  2097152 kB
        VmRSS:\t  1048576 kB
        Threads:\t120
        """;
    assertThat(ResidentSetProbe.parseProcStatus(status))
        .containsExactly(1_073_741_824L, 2_147_483_648L);
    assertThat(ResidentSetProbe.parseProcStatus("VmRSS:\t 10 kB\n")).containsExactly(10_240L, -1L);
    assertThat(ResidentSetProbe.parseProcStatus("Name: java\n")).isNull();
  }

  @Test
  @DisplayName("T-NFR-06a the Windows probe line is WorkingSet64 and PeakWorkingSet64")
  void windowsLine() {
    assertThat(ResidentSetProbe.parseWindowsLine("123456 789012"))
        .containsExactly(123456L, 789012L);
    assertThat(ResidentSetProbe.parseWindowsLine("Get-Process : cannot find")).isNull();
  }

  @Test
  @DisplayName("T-NFR-06a docker stats sizes and lines are parsed (binary and decimal units)")
  void dockerStats() {
    assertThat(DockerContainers.parseSize("1.5GiB")).isEqualTo(1_610_612_736L);
    assertThat(DockerContainers.parseSize("512MiB")).isEqualTo(536_870_912L);
    assertThat(DockerContainers.parseSize("800kB")).isEqualTo(800_000L);
    assertThat(DockerContainers.parseSize("12B")).isEqualTo(12L);
    assertThat(DockerContainers.parseSize("--")).isEqualTo(-1L);
    assertThat(
            DockerContainers.parseStats(
                "0123456789ab\t1.25GiB / 4GiB\t153.20%\nbad line\nfedcba987654\t300MiB / 1GiB\t1.5%\n"))
        .containsExactly(
            new DockerContainers.Stats("0123456789ab", 1_342_177_280L, 4_294_967_296L, 153.2),
            new DockerContainers.Stats("fedcba987654", 314_572_800L, 1_073_741_824L, 1.5));
  }

  @Test
  @DisplayName(
      "T-NFR-06a the container publishing a host port is found; containers without port data are"
          + " skipped")
  void publishingSkipsContainersWithoutPorts() {
    Container hostNetwork = new Container(); // Ports missing, as for host-network containers
    Container noPublicPort = new Container();
    noPublicPort.ports =
        new ContainerPort[] {null, new ContainerPort().withPrivatePort(8123).withType("tcp")};
    Container other = new Container();
    other.ports = new ContainerPort[] {new ContainerPort().withPrivatePort(5432).withPublicPort(1)};
    Container clickHouse = new Container();
    clickHouse.ports =
        new ContainerPort[] {new ContainerPort().withPrivatePort(8123).withPublicPort(18123)};

    List<Container> containers = List.of(hostNetwork, noPublicPort, other, clickHouse);
    assertThat(DockerContainers.publishing(containers, 18123)).containsSame(clickHouse);
    assertThat(DockerContainers.publishing(containers, 9999)).isEmpty();
    assertThat(DockerContainers.publishing(List.of(hostNetwork), 18123)).isEmpty();
  }

  @Test
  @DisplayName("T-NFR-06a jcmd VM.native_memory summary gives the total and each category")
  void nativeMemory() {
    String nmt =
        """
        12345:

        Native Memory Tracking:

        (Omitting categories weighting less than 1KB)

        Total: reserved=6000000KB, committed=2500000KB
               malloc: 150000KB #400000
               mmap:   reserved=5850000KB, committed=2350000KB

        -                 Java Heap (reserved=4194304KB, committed=2097152KB)
                                    (mmap: reserved=4194304KB, committed=2097152KB)

        -                     Class (reserved=1050000KB, committed=60000KB)
                                    (classes #12000)

        -                    Thread (reserved=200000KB, committed=200000KB)
                                    (thread #190)
        """;
    Jcmd.NativeMemory m = Jcmd.parseNativeMemory(nmt);
    assertThat(m).isNotNull();
    assertThat(m.reservedKb()).isEqualTo(6_000_000L);
    assertThat(m.committedKb()).isEqualTo(2_500_000L);
    assertThat(m.committedKbByCategory())
        .containsExactly(
            java.util.Map.entry("Java Heap", 2_097_152L),
            java.util.Map.entry("Class", 60_000L),
            java.util.Map.entry("Thread", 200_000L));
    assertThat(m.committedOffHeapKb()).isEqualTo(2_500_000L - 2_097_152L);
    assertThat(Jcmd.parseNativeMemory("Native memory tracking is not enabled")).isNull();
  }

  @Test
  @DisplayName("T-NFR-06a jcmd GC.class_histogram gives rows, totals and filtered sums")
  void classHistogram() {
    String histogram =
        """
        12345:
         num     #instances         #bytes  class name (module)
        -------------------------------------------------------
           1:        100000       80000000  [B (java.base@21.0.9)
           2:         20000        3200000  org.HdrHistogram.Histogram
           3:          5000         400000  org.HdrHistogram.ConcurrentHistogram
           4:         10000         240000  java.lang.String (java.base@21.0.9)
        Total        135000       83840000
        """;
    Jcmd.ClassHistogram h = Jcmd.parseClassHistogram(histogram);
    assertThat(h.rows()).hasSize(4);
    assertThat(h.rows().get(0)).isEqualTo(new Jcmd.HistogramRow("[B", 100_000, 80_000_000));
    assertThat(h.totalInstances()).isEqualTo(135_000);
    assertThat(h.totalBytes()).isEqualTo(83_840_000);
    assertThat(h.bytes(n -> n.startsWith("org.HdrHistogram."))).isEqualTo(3_600_000);
    assertThat(h.instances(n -> n.startsWith("org.HdrHistogram."))).isEqualTo(25_000);
    assertThat(h.top(2)).hasSize(2);
  }
}
