/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * {@link JdkThreadDumpReader} on real {@code HotSpotDiagnosticMXBean.dumpThreads(path, JSON)}
 * output of JDK 21 (21+35) and JDK 25 (25+36), recorded with one virtual thread {@code vt-sample}
 * (tid 27) holding a monitor, and on broken and oversized input (10 §12.1, 10 U-2).
 */
@Tag("req:F-A12")
@Tag("req:F-A30")
class JdkThreadDumpReaderTest {

  private static JdkThreadDumpReader.Result fixture(String name) throws IOException {
    try (InputStream in = JdkThreadDumpReaderTest.class.getResourceAsStream(name);
        Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
      return JdkThreadDumpReader.read(reader);
    }
  }

  private static JdkThreadDumpReader.DumpedThread byName(
      JdkThreadDumpReader.Result result, String name) {
    for (JdkThreadDumpReader.DumpedThread t : result.threads) {
      if (name.equals(t.name)) {
        return t;
      }
    }
    throw new AssertionError(name + " not read");
  }

  @Test
  @DisplayName(
      "T-AG-054 the JDK 21 dumpThreads JSON is read: every container's threads, the virtual"
          + " thread recognised by its frames although JDK 21 does not mark it")
  void jdk21() throws IOException {
    JdkThreadDumpReader.Result result = fixture("jdk21-dumpThreads.json");
    assertThat(result.truncated).isFalse();
    assertThat(result.threads).hasSize(11);
    JdkThreadDumpReader.DumpedThread vt = byName(result, "vt-sample");
    assertThat(vt.tid).isEqualTo(27L);
    assertThat(vt.virtual).isNull();
    assertThat(vt.state).isNull();
    assertThat(vt.stack).contains("java.base/java.lang.VirtualThread.run(VirtualThread.java:311)");
    JdkThreadDumpReader.DumpedThread plat = byName(result, "plat \"quoted\" é");
    assertThat(plat.stack.get(0)).isEqualTo("java.base/java.lang.Thread.sleep0(Native Method)");
    Set<Long> platform = new HashSet<Long>();
    for (JdkThreadDumpReader.DumpedThread t : result.threads) {
      if (!t.name.equals("vt-sample")) {
        platform.add(t.tid);
      }
    }
    assertThat(ThreadDumpCommand.isVirtual(vt, platform)).isTrue();
    assertThat(ThreadDumpCommand.isVirtual(plat, platform)).isFalse();
  }

  @Test
  @DisplayName(
      "T-AG-054 the JDK 25 dumpThreads JSON is read with state, virtual=true, waitingOn,"
          + " parkBlocker and monitorsOwned")
  void jdk25() throws IOException {
    JdkThreadDumpReader.Result result = fixture("jdk25-dumpThreads.json");
    assertThat(result.truncated).isFalse();
    assertThat(result.threads).hasSize(12);
    JdkThreadDumpReader.DumpedThread vt = byName(result, "vt-sample");
    assertThat(vt.tid).isEqualTo(27L);
    assertThat(vt.virtual).isTrue();
    assertThat(vt.state).isEqualTo("TIMED_WAITING");
    assertThat(vt.locks).hasSize(1);
    assertThat(vt.locks.get(0)).startsWith("java.lang.Object@");
    JdkThreadDumpReader.DumpedThread finalizer = byName(result, "Finalizer");
    assertThat(finalizer.waitingOn).startsWith("java.lang.ref.ReferenceQueue$Lock@");
    assertThat(finalizer.virtual).isNull();
    assertThat(ThreadDumpCommand.isVirtual(vt, Collections.singleton(27L))).isTrue();
    // A carrier waiting in the pool reports its park blocker.
    boolean parkBlocker = false;
    for (JdkThreadDumpReader.DumpedThread t : result.threads) {
      if (t.waitingOn != null && t.waitingOn.startsWith("java.util.concurrent.ForkJoinPool@")) {
        parkBlocker = true;
      }
    }
    assertThat(parkBlocker).isTrue();
  }

  @Test
  @DisplayName("broken JDK dump input ends the read with an error, never a partial guess")
  void brokenInput() {
    for (String bad :
        new String[] {
          "",
          "{",
          "{\"threadDump\":",
          "{\"a\":tru}",
          "{\"a\":\"unterminated}",
          "{\"a\":\"bad \\x escape\"}",
          "{\"a\":1}}",
          "[1,2,,3]",
          "{\"a\" 1}",
          "{\"a\":\"ctl\u0001\"}",
        }) {
      assertThatThrownBy(() -> JdkThreadDumpReader.read(new StringReader(bad)))
          .as(bad)
          .isInstanceOf(IOException.class);
    }
  }

  @Test
  @DisplayName("JDK dump input deeper than the nesting bound is refused")
  void depthBound() {
    StringBuilder deep = new StringBuilder();
    for (int i = 0; i < 1000; i++) {
      deep.append('[');
    }
    for (int i = 0; i < 1000; i++) {
      deep.append(']');
    }
    assertThatThrownBy(() -> JdkThreadDumpReader.read(new StringReader(deep.toString())))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("nesting");
  }

  @Test
  @DisplayName(
      "JDK dump input over the character bound returns the threads read so far, truncated;"
          + " long strings and arrays are kept only up to their bounds")
  void sizeBounds() throws IOException {
    StringBuilder json = new StringBuilder("{\"threadDump\":{\"threadContainers\":[{\"threads\":[");
    for (int i = 0; i < 2000; i++) {
      json.append(i == 0 ? "" : ",")
          .append("{\"tid\":\"")
          .append(i + 1)
          .append("\",\"name\":\"t")
          .append(i)
          .append("\",\"stack\":[\"a.B.c(B.java:1)\"]}");
    }
    json.append("]}]}}");
    JdkThreadDumpReader.Result full = JdkThreadDumpReader.read(new StringReader(json.toString()));
    assertThat(full.threads).hasSize(2000);
    assertThat(full.truncated).isFalse();
    JdkThreadDumpReader.Result cut =
        JdkThreadDumpReader.read(new StringReader(json.toString()), json.length() / 2);
    assertThat(cut.truncated).isTrue();
    assertThat(cut.threads.size()).isBetween(900, 1100);
    // The retained characters are bounded too (names and frames of the threads kept).
    JdkThreadDumpReader.Result retained =
        JdkThreadDumpReader.read(
            new StringReader(json.toString()), JdkThreadDumpReader.MAX_INPUT_CHARS, 20_000L);
    assertThat(retained.truncated).isTrue();
    assertThat(retained.threads.size()).isBetween(100, 300);

    StringBuilder longText = new StringBuilder();
    for (int i = 0; i < JdkThreadDumpReader.MAX_STRING_CHARS * 3; i++) {
      longText.append('x');
    }
    StringBuilder frames = new StringBuilder();
    for (int i = 0; i < 1000; i++) {
      frames.append(i == 0 ? "" : ",").append("\"f").append(i).append('"');
    }
    String one =
        "{\"threadContainers\":[{\"threads\":[{\"tid\":5,\"name\":\""
            + longText
            + "\",\"stack\":["
            + frames
            + "]}]}]}";
    JdkThreadDumpReader.Result bounded = JdkThreadDumpReader.read(new StringReader(one));
    assertThat(bounded.threads).hasSize(1);
    assertThat(bounded.threads.get(0).tid).isEqualTo(5L);
    assertThat(bounded.threads.get(0).name).hasSize(JdkThreadDumpReader.MAX_STRING_CHARS);
    assertThat(bounded.threads.get(0).stack).hasSize(ThreadDumpJson.MAX_FRAMES);
  }
}
