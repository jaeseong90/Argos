/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.context;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** W3C {@code tracestate} bounds and the {@code argos} entry (10 §8, 11 §4.1 rule 5, SEC-38). */
@Tag("req:F-A32")
@Tag("req:SEC-38")
class TraceStateCodecTest {
  @Test
  @DisplayName("T-AG-057b a valid tracestate is kept, members trimmed, empty members removed")
  void sanitizeKeeps() {
    assertThat(TraceStateCodec.sanitize("rojo=00f067aa0ba902b7, congo=t61rcWkgMzE"))
        .isEqualTo("rojo=00f067aa0ba902b7,congo=t61rcWkgMzE");
    assertThat(TraceStateCodec.sanitize(" vendor@tenant=1 ,, ,a-b_c*d/e=x y"))
        .isEqualTo("vendor@tenant=1,a-b_c*d/e=x y");
    assertThat(TraceStateCodec.sanitize("1tenant@sys=v")).isEqualTo("1tenant@sys=v");
    assertThat(TraceStateCodec.sanitize(null)).isNull();
    assertThat(TraceStateCodec.sanitize(" , ")).isNull();
  }

  @ParameterizedTest(name = "T-AG-057b drops the whole tracestate [{0}]")
  @ValueSource(
      strings = {
        "Upper=1",
        "1key=1",
        "key",
        "key=",
        "=v",
        "key=a,b=",
        "key=has=equals",
        "key=ctl\u0001",
        "dup=1,dup=2",
        "t@=1",
        "@s=1",
        "tenant@Sys=1",
        "tenant@system-way-too-long=1"
      })
  void sanitizeDrops(String value) {
    assertThat(TraceStateCodec.sanitize(value)).isNull();
  }

  @Test
  @DisplayName("T-AG-057b over 512 characters or 32 members the tracestate is dropped as a whole")
  void sanitizeBounds() {
    StringBuilder entries = new StringBuilder();
    for (int i = 0; i < 32; i++) {
      entries.append(i == 0 ? "" : ",").append("k").append(i).append("=v");
    }
    assertThat(TraceStateCodec.sanitize(entries.toString())).isNotNull();
    assertThat(TraceStateCodec.sanitize(entries + ",k32=v")).as("33 members").isNull();

    StringBuilder value = new StringBuilder("k=");
    while (value.length() < 256 + 2) {
      value.append('v');
    }
    assertThat(TraceStateCodec.sanitize(value.toString())).as("256-character value").isNotNull();
    assertThat(TraceStateCodec.sanitize(value + "v")).as("257-character value").isNull();

    StringBuilder longest = new StringBuilder();
    int i = 0;
    while (longest.length() < TraceStateCodec.MAX_CHARS) {
      longest.append(longest.length() == 0 ? "" : ",").append("a").append(i++).append("=xxxxxxx");
    }
    assertThat(longest.length()).isGreaterThan(TraceStateCodec.MAX_CHARS);
    assertThat(TraceStateCodec.sanitize(longest.toString())).as("over 512 characters").isNull();
  }

  @Test
  @DisplayName("T-AG-057b caller_app_id from a well-formed argos entry")
  void callerAppId() {
    assertThat(TraceStateCodec.callerAppId("argos=1f:2:abcdef0123456789")).isEqualTo(0x1f);
    assertThat(TraceStateCodec.callerAppId("rojo=1,argos=ffffffff:ffffffff:ffffffffffffffff"))
        .as("u32 app id ffffffff keeps its bit pattern")
        .isEqualTo(-1);
    assertThat(TraceStateCodec.callerAppId("rojo=1")).isZero();
    assertThat(TraceStateCodec.callerAppId(null)).isZero();
  }

  @ParameterizedTest(name = "T-AG-057b a malformed argos entry gives caller 0 [{0}]")
  @ValueSource(
      strings = {
        "argos=1:2",
        "argos=1:2:3:4",
        "argos=:2:3",
        "argos=1::3",
        "argos=1:2:",
        "argos=0:2:3",
        "argos=1F:2:3",
        "argos=123456789:2:3",
        "argos=1:123456789:3",
        "argos=1:2:12345678901234567",
        "argos=x:2:3"
      })
  void callerAppIdMalformed(String value) {
    assertThat(TraceStateCodec.callerAppId(value)).isZero();
  }

  @Test
  @DisplayName("T-AG-057b the argos entry: lower-case hex u32:u32:u64 that parses back")
  void argosEntry() {
    String entry = TraceStateCodec.argosEntry(0x1fL, 7L, -2L);
    assertThat(entry).isEqualTo("argos=1f:7:fffffffffffffffe");
    assertThat(entry.length()).isLessThanOrEqualTo(6 + 8 + 1 + 8 + 1 + 16);
    assertThat(TraceStateCodec.callerAppId(entry)).isEqualTo(0x1f);
  }

  @Test
  @DisplayName(
      "T-AG-057b outgoing: the argos entry first, an existing argos entry replaced not duplicated")
  void outgoingReplaces() {
    assertThat(TraceStateCodec.outgoing("rojo=1,argos=9:9:9,congo=2", "argos=1:2:3"))
        .isEqualTo("argos=1:2:3,rojo=1,congo=2");
    assertThat(TraceStateCodec.outgoing(null, "argos=1:2:3")).isEqualTo("argos=1:2:3");
  }

  @Test
  @DisplayName("T-SEC-38a outgoing without an entry removes any argos member")
  void outgoingStrips() {
    assertThat(TraceStateCodec.outgoing("rojo=1,argos=9:9:9", null)).isEqualTo("rojo=1");
    assertThat(TraceStateCodec.outgoing("argos=9:9:9", null)).isNull();
    assertThat(TraceStateCodec.outgoing(null, null)).isNull();
    assertThat(TraceStateCodec.hasArgos("rojo=1,argos=9:9:9")).isTrue();
    assertThat(TraceStateCodec.hasArgos("rojo=1")).isFalse();
  }

  @Test
  @DisplayName("T-AG-057b outgoing keeps at most 32 members and 512 characters, dropping the right")
  void outgoingBounds() {
    StringBuilder full = new StringBuilder();
    for (int i = 0; i < 32; i++) {
      full.append(i == 0 ? "" : ",").append("k").append(i).append("=v");
    }
    String out = TraceStateCodec.outgoing(full.toString(), "argos=1:2:3");
    assertThat(out.split(",")).hasSize(32);
    assertThat(out).startsWith("argos=1:2:3,k0=v").doesNotContain("k31=v");

    StringBuilder wide = new StringBuilder();
    for (int i = 0; i < 4; i++) {
      wide.append(i == 0 ? "" : ",").append("w").append(i).append('=');
      for (int c = 0; c < 124; c++) {
        wide.append('x');
      }
    }
    assertThat(wide.length()).isLessThanOrEqualTo(TraceStateCodec.MAX_CHARS);
    String trimmed = TraceStateCodec.outgoing(wide.toString(), "argos=1:2:3");
    assertThat(trimmed.length()).isLessThanOrEqualTo(TraceStateCodec.MAX_CHARS);
    assertThat(trimmed).startsWith("argos=1:2:3,w0=").doesNotContain("w3=");
  }
}
