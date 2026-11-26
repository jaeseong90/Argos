/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.proto;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.InvalidProtocolBufferException;
import io.argos.proto.v1.AgentHello;
import io.argos.proto.v1.InstanceUpdate;
import io.argos.proto.v1.SqlStat;
import io.argos.proto.v1.Throttle;
import io.argos.proto.v1.TransactionRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Proto contract test (c): "not measured" travels as an absent optional field and survives the
 * wire; 0 stays the value 0 (C-4, D-23, 11 §4.4). The full T-ACC-23 (virtual-thread transactions
 * stored and returned as missing) belongs to WO-15 and the server; this is its protocol part. The
 * additive fields of ADR-0010 C-101 that carry a value with a meaningful 0/false ({@code
 * AgentHello.plaintext}, {@code SqlStat.sum_exec_us}, {@code sum_fetch_us}) are optional too (11
 * §12).
 */
@Tag("req:D-23")
@Tag("req:C-4")
class OptionalPresenceTest {

  @Test
  @DisplayName(
      "T-ACC-23a cpu_time_us and alloc_bytes: absent and 0 stay distinct after a round trip")
  void cpuAndAllocPresence() throws InvalidProtocolBufferException {
    TransactionRecord missing = roundTrip(TransactionRecord.newBuilder().setTxId(1).build());
    assertThat(missing.hasCpuTimeUs()).isFalse();
    assertThat(missing.hasAllocBytes()).isFalse();

    TransactionRecord zero =
        roundTrip(
            TransactionRecord.newBuilder().setTxId(1).setCpuTimeUs(0).setAllocBytes(0).build());
    assertThat(zero.hasCpuTimeUs()).isTrue();
    assertThat(zero.getCpuTimeUs()).isZero();
    assertThat(zero.hasAllocBytes()).isTrue();
    assertThat(zero.getAllocBytes()).isZero();
    // Presence costs bytes on the wire: a present 0 is serialized, an absent value is not.
    assertThat(zero.getSerializedSize()).isGreaterThan(missing.getSerializedSize());

    TransactionRecord measured =
        roundTrip(
            TransactionRecord.newBuilder()
                .setTxId(1)
                .setCpuTimeUs(1_234)
                .setAllocBytes(56)
                .build());
    assertThat(measured.getCpuTimeUs()).isEqualTo(1_234);
    assertThat(measured.getAllocBytes()).isEqualTo(56);

    TransactionRecord cleared =
        roundTrip(measured.toBuilder().clearCpuTimeUs().clearAllocBytes().build());
    assertThat(cleared.hasCpuTimeUs()).isFalse();
    assertThat(cleared.hasAllocBytes()).isFalse();
  }

  @Test
  @DisplayName(
      "non-optional numeric fields have no presence: 0 is the value 0, never 'missing' (C-4)")
  void plainFieldsHaveNoPresence() throws InvalidProtocolBufferException {
    TransactionRecord unset = roundTrip(TransactionRecord.newBuilder().build());
    TransactionRecord zero = roundTrip(TransactionRecord.newBuilder().setSqlCount(0).build());
    assertThat(zero).isEqualTo(unset);
    assertThat(TransactionRecord.getDescriptor().findFieldByName("sql_count").hasPresence())
        .isFalse();
    assertThat(TransactionRecord.getDescriptor().findFieldByName("cpu_time_us").hasPresence())
        .isTrue();
  }

  @Test
  @Tag("req:C-101")
  @DisplayName(
      "C-101 additive optional fields: AgentHello.plaintext and SqlStat.sum_exec_us/sum_fetch_us"
          + " keep 'not reported' apart from a reported false/0 after a round trip (11 §12)")
  void additiveFieldsArePresenceTracked() throws InvalidProtocolBufferException {
    assertThat(AgentHello.getDescriptor().findFieldByName("plaintext").hasPresence()).isTrue();
    assertThat(SqlStat.getDescriptor().findFieldByName("sum_exec_us").hasPresence()).isTrue();
    assertThat(SqlStat.getDescriptor().findFieldByName("sum_fetch_us").hasPresence()).isTrue();

    AgentHello unreported =
        AgentHello.parseFrom(AgentHello.newBuilder().setAppName("shop").build().toByteArray());
    assertThat(unreported.hasPlaintext()).isFalse();
    AgentHello overTls =
        AgentHello.parseFrom(
            AgentHello.newBuilder().setAppName("shop").setPlaintext(false).build().toByteArray());
    assertThat(overTls.hasPlaintext()).as("false over TLS is a reported value").isTrue();
    assertThat(overTls.getPlaintext()).isFalse();
    AgentHello plaintext =
        AgentHello.parseFrom(
            AgentHello.newBuilder().setAppName("shop").setPlaintext(true).build().toByteArray());
    assertThat(plaintext.hasPlaintext()).isTrue();
    assertThat(plaintext.getPlaintext()).isTrue();

    SqlStat missing = SqlStat.parseFrom(SqlStat.newBuilder().setSqlId(7).build().toByteArray());
    assertThat(missing.hasSumExecUs()).isFalse();
    assertThat(missing.hasSumFetchUs()).isFalse();
    SqlStat zero =
        SqlStat.parseFrom(
            SqlStat.newBuilder()
                .setSqlId(7)
                .setSumExecUs(0)
                .setSumFetchUs(0)
                .build()
                .toByteArray());
    assertThat(zero.hasSumExecUs()).isTrue();
    assertThat(zero.getSumExecUs()).isZero();
    assertThat(zero.hasSumFetchUs()).isTrue();
    assertThat(zero.getSumFetchUs()).isZero();
    SqlStat measured =
        SqlStat.parseFrom(
            SqlStat.newBuilder()
                .setSqlId(7)
                .setSumUs(45_000)
                .setSumExecUs(40_000)
                .setSumFetchUs(5_000)
                .build()
                .toByteArray());
    assertThat(measured.getSumExecUs() + measured.getSumFetchUs()).isEqualTo(measured.getSumUs());
    SqlStat cleared =
        SqlStat.parseFrom(
            measured.toBuilder().clearSumExecUs().clearSumFetchUs().build().toByteArray());
    assertThat(cleared.hasSumExecUs()).isFalse();
    assertThat(cleared.hasSumFetchUs()).isFalse();
  }

  @Test
  @Tag("req:C-240")
  @DisplayName(
      "T-AG-068b C-240 (1) AgentHello.check_only is an additive optional field: a Hello without it"
          + " is a normal session, the set value survives, and its bytes only append field 26")
  void checkOnlyIsAdditive() throws InvalidProtocolBufferException {
    assertThat(AgentHello.getDescriptor().findFieldByName("check_only").hasPresence()).isTrue();
    assertThat(AgentHello.getDescriptor().findFieldByName("check_only").getNumber()).isEqualTo(26);
    AgentHello normal = AgentHello.newBuilder().setAppName("shop").setAgentEpoch(7).build();
    AgentHello parsed = AgentHello.parseFrom(normal.toByteArray());
    assertThat(parsed.hasCheckOnly()).isFalse();
    assertThat(parsed.getCheckOnly()).isFalse();
    AgentHello check = normal.toBuilder().setCheckOnly(true).build();
    AgentHello parsedCheck = AgentHello.parseFrom(check.toByteArray());
    assertThat(parsedCheck.hasCheckOnly()).isTrue();
    assertThat(parsedCheck.getCheckOnly()).isTrue();
    // Buffer compatibility: the normal Hello's bytes are a prefix of the check Hello's bytes (field
    // 26, varint, tag 0xD0 0x01, value 1), so a receiver that does not know the field keeps it as
    // an unknown field and reads every other field as before.
    byte[] base = normal.toByteArray();
    byte[] withFlag = check.toByteArray();
    assertThat(withFlag).startsWith(base);
    assertThat(java.util.Arrays.copyOfRange(withFlag, base.length, withFlag.length))
        .containsExactly((byte) 0xD0, (byte) 0x01, (byte) 0x01);
  }

  @Test
  @DisplayName("InstanceUpdate and Throttle: absent fields mean 'unchanged', set values survive")
  void instanceUpdateAndThrottlePresence() throws InvalidProtocolBufferException {
    InstanceUpdate empty =
        InstanceUpdate.parseFrom(InstanceUpdate.newBuilder().build().toByteArray());
    assertThat(empty.hasInstanceName()).isFalse();
    assertThat(empty.hasVirtualThreads()).isFalse();
    assertThat(empty.hasAppVersion()).isFalse();
    assertThat(empty.hasConfigHash()).isFalse();

    InstanceUpdate off =
        InstanceUpdate.parseFrom(
            InstanceUpdate.newBuilder()
                .setVirtualThreads(false)
                .setAppVersion("")
                .build()
                .toByteArray());
    assertThat(off.hasVirtualThreads()).isTrue();
    assertThat(off.getVirtualThreads()).isFalse();
    assertThat(off.hasAppVersion()).isTrue();

    Throttle none = Throttle.parseFrom(Throttle.newBuilder().build().toByteArray());
    assertThat(none.hasProfileNormalRate()).isFalse();
    assertThat(none.hasSendBackoffMs()).isFalse();
    Throttle stop =
        Throttle.parseFrom(
            Throttle.newBuilder()
                .setProfileNormalRate(0f)
                .setSendBackoffMs(0)
                .build()
                .toByteArray());
    assertThat(stop.hasProfileNormalRate()).isTrue();
    assertThat(stop.getProfileNormalRate()).isZero();
    assertThat(stop.hasSendBackoffMs()).isTrue();
  }

  private static TransactionRecord roundTrip(TransactionRecord record)
      throws InvalidProtocolBufferException {
    return TransactionRecord.parseFrom(record.toByteArray());
  }
}
