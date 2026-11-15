/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.collector.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.argos.proto.v1.BatchHeader;
import io.argos.proto.v1.RejectReason;
import io.argos.server.session.api.IpMode;
import io.argos.server.session.api.UserIdMode;
import io.argos.server.wal.api.WalRecordType;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Token decision (16 §2.4), token hash shape (16 §2.1), dedup key (C-3) and mode mapping (C-143).
 */
@Tag("req:SEC-01")
@Tag("req:SEC-02")
class TokenRecordTest {

  private static final Instant NOW = Instant.parse("2026-11-08T00:00:00Z");

  private static TokenRecord record(
      TokenStatus status, Instant expires, Instant grace, Instant revoked) {
    return new TokenRecord(
        UUID.randomUUID(),
        3,
        "order-api",
        "prod",
        "argos_AbCdEfGh",
        status,
        expires,
        grace,
        revoked);
  }

  @Test
  @DisplayName(
      "ACTIVE before expiry and GRACE before grace_until authenticate; the rest fails closed")
  void verdicts() {
    assertThat(record(TokenStatus.ACTIVE, null, null, null).verdict(NOW))
        .isEqualTo(TokenVerdict.ACCEPTED);
    assertThat(record(TokenStatus.ACTIVE, NOW.plusSeconds(1), null, null).verdict(NOW))
        .isEqualTo(TokenVerdict.ACCEPTED);
    assertThat(record(TokenStatus.ACTIVE, NOW, null, null).verdict(NOW))
        .isEqualTo(TokenVerdict.EXPIRED);
    assertThat(record(TokenStatus.GRACE, null, NOW.plusSeconds(60), null).verdict(NOW))
        .isEqualTo(TokenVerdict.ACCEPTED);
    assertThat(record(TokenStatus.GRACE, null, NOW, null).verdict(NOW))
        .isEqualTo(TokenVerdict.EXPIRED);
    assertThat(record(TokenStatus.GRACE, null, null, null).verdict(NOW))
        .isEqualTo(TokenVerdict.EXPIRED);
    assertThat(record(TokenStatus.EXPIRED, null, null, null).verdict(NOW))
        .isEqualTo(TokenVerdict.EXPIRED);
    assertThat(record(TokenStatus.REVOKED, null, null, null).verdict(NOW))
        .isEqualTo(TokenVerdict.REVOKED);
    assertThat(record(TokenStatus.ACTIVE, null, null, NOW.minusSeconds(1)).verdict(NOW))
        .isEqualTo(TokenVerdict.REVOKED);
    assertThat(TokenVerdict.REVOKED.rejectReason()).isEqualTo(RejectReason.TOKEN_REVOKED);
    assertThat(TokenVerdict.UNKNOWN.rejectReason()).isEqualTo(RejectReason.TOKEN_INVALID);
    assertThat(TokenVerdict.REJECT_RETRY_AFTER_S).isEqualTo(300);
  }

  @Test
  @DisplayName(
      "stored status text: V001 names, 'rotating' is GRACE, unknown text never authenticates")
  void statusText() {
    assertThat(TokenStatus.fromStored("ACTIVE")).isEqualTo(TokenStatus.ACTIVE);
    assertThat(TokenStatus.fromStored("grace")).isEqualTo(TokenStatus.GRACE);
    assertThat(TokenStatus.fromStored("rotating")).isEqualTo(TokenStatus.GRACE);
    assertThat(TokenStatus.fromStored("bogus")).isEqualTo(TokenStatus.REVOKED);
    assertThat(TokenStatus.fromStored(null)).isEqualTo(TokenStatus.REVOKED);
  }

  @Test
  @DisplayName(
      "token hash: SHA-256 hex, constant-time match, only the 14-character prefix is shown")
  void tokenHash() {
    String token = "argos_" + "A".repeat(43);
    TokenHash hash = TokenHash.of(token);
    assertThat(hash.hex()).hasSize(64).matches("[0-9a-f]{64}");
    assertThat(hash.matches(TokenHash.of(token))).isTrue();
    assertThat(hash.matches(TokenHash.of(token.replace('A', 'B')))).isFalse();
    assertThat(hash.matches(null)).as("no hash never matches").isFalse();
    assertThat(hash.toString()).doesNotContain(hash.hex()).doesNotContain(token);
    assertThat(TokenHash.isWellFormed(token)).isTrue();
    assertThat(TokenHash.isWellFormed(token + "A")).isFalse();
    assertThat(TokenHash.isWellFormed("argos_" + "A".repeat(42) + "=")).isFalse();
    assertThat(TokenHash.isWellFormed(null)).isFalse();
    assertThat(TokenHash.displayPrefix(token)).isEqualTo("argos_AAAAAAAA");
    assertThat(TokenHash.displayPrefix("Bearer junk")).isEqualTo("argos_****");
    assertThatThrownBy(() -> new TokenHash("ABC")).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("dedup key: (instance, epoch, kind, seq) of the four data kinds only (C-3)")
  void dedupKey() {
    BatchHeader header =
        BatchHeader.newBuilder().setInstanceId(9).setAgentEpoch(-5).setSeq(77).build();
    assertThat(DedupKey.of(9, WalRecordType.STAT, header))
        .isEqualTo(new DedupKey(9, -5, WalRecordType.STAT, 77));
    assertThatThrownBy(() -> new DedupKey(9, 1, WalRecordType.BLOCK_INTENT, 1))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("C-143 (1): unspecified or unknown capture modes are stored as off")
  void modes() {
    assertThat(IpMode.fromProto(io.argos.proto.v1.IpMode.IP_MASKED)).isEqualTo(IpMode.MASKED);
    assertThat(IpMode.fromProto(io.argos.proto.v1.IpMode.IP_MODE_UNSPECIFIED))
        .isEqualTo(IpMode.OFF);
    assertThat(IpMode.fromProto(io.argos.proto.v1.IpMode.UNRECOGNIZED)).isEqualTo(IpMode.OFF);
    assertThat(IpMode.isKnown(io.argos.proto.v1.IpMode.UNRECOGNIZED)).isFalse();
    assertThat(IpMode.FULL.enum8()).isEqualTo(2);
    assertThat(IpMode.OFF.storesRemoteIp()).isFalse();
    assertThat(UserIdMode.fromProto(io.argos.proto.v1.UserIdMode.USER_ID_HASHED))
        .isEqualTo(UserIdMode.HASHED);
    assertThat(UserIdMode.fromProto(io.argos.proto.v1.UserIdMode.UNRECOGNIZED))
        .isEqualTo(UserIdMode.OFF);
    assertThat(UserIdMode.PLAIN.storedName()).isEqualTo("plain");
    assertThat(UserIdMode.fromStoredName("HASHED")).isEqualTo(UserIdMode.HASHED);
    assertThat(UserIdMode.fromStoredName("x")).isEqualTo(UserIdMode.OFF);
    assertThat(IpMode.fromEnum8(7)).isEqualTo(IpMode.OFF);
  }
}
