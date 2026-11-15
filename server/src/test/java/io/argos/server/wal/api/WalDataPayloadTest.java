/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.wal.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.argos.proto.v1.BatchHeader;
import io.argos.proto.v1.TransactionBatch;
import io.argos.proto.v1.TransactionRecord;
import io.argos.server.session.api.IpMode;
import io.argos.server.session.api.SessionContext;
import io.argos.server.session.api.UserIdMode;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The payload of a WAL data record rebuilds the identical session context and batch (D-13). */
@Tag("req:D-13")
@Tag("req:DSN-12-01")
@Tag("req:C-129")
class WalDataPayloadTest {

  private static final SessionContext SESSION =
      new SessionContext(
          3,
          12,
          "prod",
          UUID.fromString("0f8fad5b-d9cb-469f-a165-70867728950e"),
          0x8000_0000_0000_0001L,
          IpMode.MASKED,
          UserIdMode.HASHED,
          500,
          1_793_000_000_000L,
          "1.4.2-ü",
          0);

  @Test
  @DisplayName("encode/decode keeps every value; with the header values the context is identical")
  void roundTrip() throws Exception {
    TransactionBatch batch =
        TransactionBatch.newBuilder()
            .setHeader(BatchHeader.newBuilder().setInstanceId(12).setAgentEpoch(1).setSeq(5))
            .addRecords(TransactionRecord.newBuilder().setTxId(42).setUrlPath("/a"))
            .build();
    SessionContext received = SESSION.atReceipt(1_793_000_000_123L, 60_000, 750);

    byte[] bytes = WalDataPayload.of(received, batch.toByteString()).encode();
    WalDataPayload decoded = WalDataPayload.decode(bytes);

    assertThat(TransactionBatch.parseFrom(decoded.batch())).isEqualTo(batch);
    assertThat(
            decoded.context(
                received.instanceId(),
                received.agentEpoch(),
                received.receivedAtMs(),
                received.tsCorrectionMs()))
        .isEqualTo(received);
  }

  @Test
  @DisplayName("an unknown format, a truncated payload and over-long texts are refused")
  void refused() {
    byte[] bytes =
        WalDataPayload.of(SESSION, TransactionBatch.getDefaultInstance().toByteString()).encode();
    byte[] wrongFormat = bytes.clone();
    wrongFormat[0] = 2;
    assertThatThrownBy(() -> WalDataPayload.decode(wrongFormat))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("format 2");
    assertThatThrownBy(() -> WalDataPayload.decode(Arrays.copyOf(bytes, 10)))
        .isInstanceOf(IllegalArgumentException.class);
    SessionContext longVersion = SESSION.withAppVersion("v".repeat(70_000));
    assertThatThrownBy(
            () ->
                WalDataPayload.of(longVersion, TransactionBatch.getDefaultInstance().toByteString())
                    .encode())
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("WAL record types: codes of 12 §3.4, data types are the four batch kinds")
  void recordTypes() {
    assertThat(WalRecordType.values())
        .extracting(WalRecordType::code)
        .containsExactly(1, 2, 3, 4, 16, 17);
    assertThat(WalRecordType.values())
        .filteredOn(WalRecordType::isData)
        .containsExactly(
            WalRecordType.TX, WalRecordType.PROFILE, WalRecordType.METRIC, WalRecordType.STAT);
    for (WalRecordType type : WalRecordType.values()) {
      assertThat(WalRecordType.fromCode(type.code())).isEqualTo(type);
    }
    assertThatThrownBy(() -> WalRecordType.fromCode(5))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
