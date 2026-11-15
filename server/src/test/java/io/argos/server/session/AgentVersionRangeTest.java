/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.session;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.server.session.AgentVersionRange.Verdict;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** 11 §5 check 4 version rule: agents X.(Y-2) to X.Y of server X.Y (O-R01, O-R02). */
@Tag("req:O-R01")
class AgentVersionRangeTest {

  @Test
  @DisplayName("11 §5 server 1.5.2 accepts agents 1.3.x to 1.5.x")
  void range() {
    AgentVersionRange r = new AgentVersionRange("1.5.2");
    assertThat(r.check("1.5.0")).isEqualTo(Verdict.ACCEPTED);
    assertThat(r.check("1.5.9-SNAPSHOT")).isEqualTo(Verdict.ACCEPTED);
    assertThat(r.check("1.3.0")).isEqualTo(Verdict.ACCEPTED);
    assertThat(r.check("1.4")).isEqualTo(Verdict.ACCEPTED);
    assertThat(r.check("1.2.9")).isEqualTo(Verdict.TOO_OLD);
    assertThat(r.check("0.9.0")).isEqualTo(Verdict.TOO_OLD);
    assertThat(r.check("1.6.0")).isEqualTo(Verdict.TOO_NEW);
    assertThat(r.check("2.0.0")).isEqualTo(Verdict.TOO_NEW);
    assertThat(r.check("garbage")).isEqualTo(Verdict.TOO_OLD);
    assertThat(r.check("")).isEqualTo(Verdict.TOO_OLD);
    assertThat(r.min()).isEqualTo("1.3");
    assertThat(r.max()).isEqualTo("1.5");
    assertThat(r.describe()).contains("1.5.2").contains("1.3.x").contains("1.5.x");
  }

  @Test
  @DisplayName("11 §5 a server below minor 2 starts its range at minor 0; a snapshot server is X.Y")
  void lowMinor() {
    AgentVersionRange r = new AgentVersionRange("0.0.0-SNAPSHOT");
    assertThat(r.check("0.0.0")).isEqualTo(Verdict.ACCEPTED);
    assertThat(r.check("0.1.0")).isEqualTo(Verdict.TOO_NEW);
    assertThat(r.min()).isEqualTo("0.0");
  }

  @Test
  @DisplayName("an unknown server version (no build information) accepts every agent")
  void unknownServer() {
    AgentVersionRange r = new AgentVersionRange("unknown");
    assertThat(r.check("9.9.9")).isEqualTo(Verdict.ACCEPTED);
    assertThat(r.min()).isEmpty();
  }
}
