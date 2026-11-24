/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.privacy.deletion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.argos.server.config.CidrRange;
import io.argos.server.ops.migration.SchemaParameters;
import io.argos.server.privacy.deletion.DeletionQueries.Ip;
import io.argos.server.privacy.deletion.DeletionQueries.UserId;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The statements of a privacy deletion (13 §9, 16 §6.8; ADR-0007 C-66). */
@Tag("req:SEC-33")
@Tag("req:F-S32")
class DeletionQueriesTest {

  private static final SchemaParameters PARAMS = SchemaParameters.defaults();
  private static final LocalDate DAY = LocalDate.parse("2026-11-20");

  @Test
  @DisplayName(
      "T-SV-045a a user id is matched as plain rows with the value and hashed rows with each"
          + " application's HMAC; the value never appears as text and nothing is quoted from input")
  void userIdCondition() {
    String hostile = "alice' OR 1=1 --\\";
    String condition =
        DeletionQueries.condition(new UserId(hostile, Map.of(7, "AbCd_-0123456789", 3, "x")), 7);
    assertThat(condition)
        .startsWith("app_id = 7 AND ")
        .contains("user_id_mode = 'plain' AND user_id = unhex('")
        .contains("user_id_mode = 'hashed'")
        .contains("(app_id = 3 AND user_id = unhex('78'))")
        .doesNotContain("alice")
        .doesNotContain("OR 1=1")
        .doesNotContain("AbCd");
    // Only the fixed SQL text has quotes: every quote opens or closes a hex or enum literal.
    assertThat(condition.replaceAll("'[0-9a-f]*'", "").replaceAll("'(plain|hashed)'", ""))
        .doesNotContain("'");
    assertThat(DeletionQueries.condition(new UserId("bob", Map.of()), null))
        .isEqualTo("(user_id_mode = 'plain' AND user_id = unhex('626f62'))");
    assertThatThrownBy(() -> new UserId("bob", Map.of(1, "bad'hash")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName(
      "T-SV-045a an address matches full rows exactly and masked rows by their /24 or /48"
          + " network, as the agent masks")
  void ipCondition() {
    String v4 =
        DeletionQueries.condition(new Ip(CidrRange.literal("192.0.2.77").orElseThrow()), null);
    assertThat(v4)
        .isEqualTo(
            "((ip_mode = 'full' AND remote_ip = toIPv6('192.0.2.77')) OR (ip_mode = 'masked'"
                + " AND remote_ip = toIPv6('192.0.2.0')))");
    String v6 =
        DeletionQueries.condition(
            new Ip(CidrRange.literal("2001:db8:1:2:3:4:5:6").orElseThrow()), 2);
    assertThat(v6)
        .startsWith("app_id = 2 AND ")
        .contains("toIPv6('2001:db8:1:2:3:4:5:6')")
        .contains("toIPv6('2001:db8:1:0:0:0:0:0')");
  }

  @Test
  @DisplayName(
      "T-SV-045b mutations are DELETE IN PARTITION ID of one day; profile_step selects the"
          + " transactions of xlog with the same condition (issued before the xlog mutation)")
  void statements() {
    String c = "user_id_mode = 'plain'";
    assertThat(DeletionQueries.deleteProfile(PARAMS, DAY, c))
        .isEqualTo(
            "ALTER TABLE argos.profile_step DELETE IN PARTITION ID '20261120' WHERE (app_id,"
                + " tx_id) IN (SELECT app_id, tx_id FROM argos.xlog WHERE toDate(ts) ="
                + " '2026-11-20' AND user_id_mode = 'plain')");
    assertThat(DeletionQueries.deleteXlog(PARAMS, DAY, c))
        .isEqualTo(
            "ALTER TABLE argos.xlog DELETE IN PARTITION ID '20261120' WHERE user_id_mode ="
                + " 'plain'");
    assertThat(DeletionQueries.countXlog(PARAMS, DAY, c))
        .isEqualTo(
            "SELECT toString(count()) FROM argos.xlog WHERE toDate(ts) = '2026-11-20' AND"
                + " user_id_mode = 'plain'");
    assertThat(DeletionQueries.days(DAY, DAY.plusDays(2)))
        .containsExactly(DAY, DAY.plusDays(1), DAY.plusDays(2));
  }

  @Test
  @DisplayName("T-SV-045a the stored subject is the SHA-256 hex of the normalised value")
  void hash() {
    assertThat(DeletionJobService.sha256("alice"))
        .isEqualTo("2bd806c97f0e00af1a1fc3328fa763a9269723c8db8fac4f93af71db186d6e90")
        .hasSize(64);
  }
}
