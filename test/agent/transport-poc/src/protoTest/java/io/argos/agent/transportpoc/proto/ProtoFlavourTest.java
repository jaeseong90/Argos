/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.transportpoc.proto;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.MessageLite;
import org.junit.jupiter.api.Test;

/**
 * 11 §15 open item #2 (A-14): the generated code of the 11 §4 protos supports {@code map} and
 * proto3 {@code optional} presence with both runtimes. The same test source runs twice:
 * protoLiteTest (protobuf-javalite, {@code option lite}) and protoFullTest (protobuf-java), on Java
 * 8.
 */
class ProtoFlavourTest {

  @Test
  void mapFieldsRoundTrip() throws Exception {
    ServerHello hello =
        ServerHello.newBuilder()
            .setAccepted(true)
            .putConfig("sampling.profile.normal-rate", "0.05")
            .putConfig("capture.remote-ip", "masked")
            .build();
    ServerHello parsed = ServerHello.parseFrom(hello.toByteArray());
    assertThat(parsed.getConfigMap())
        .containsEntry("sampling.profile.normal-rate", "0.05")
        .containsEntry("capture.remote-ip", "masked")
        .hasSize(2);
    assertThat(parsed.getConfigOrDefault("missing", "default")).isEqualTo("default");

    Command command =
        Command.newBuilder().setType(CommandType.THREAD_STACK).putArgs("tx_id", "42").build();
    assertThat(Command.parseFrom(command.toByteArray()).getArgsMap()).containsEntry("tx_id", "42");
  }

  @Test
  void optionalFieldsKeepPresence() throws Exception {
    TransactionRecord unset = TransactionRecord.newBuilder().setTxId(1).build();
    TransactionRecord zero =
        TransactionRecord.newBuilder().setTxId(2).setCpuTimeUs(0).setAllocBytes(0).build();

    TransactionRecord parsedUnset = TransactionRecord.parseFrom(unset.toByteArray());
    TransactionRecord parsedZero = TransactionRecord.parseFrom(zero.toByteArray());

    // C-4 / 11 §4.4: "absent" and "0" are different values on the wire.
    assertThat(parsedUnset.hasCpuTimeUs()).isFalse();
    assertThat(parsedUnset.hasAllocBytes()).isFalse();
    assertThat(parsedZero.hasCpuTimeUs()).isTrue();
    assertThat(parsedZero.getCpuTimeUs()).isZero();
    assertThat(parsedZero.hasAllocBytes()).isTrue();

    InstanceUpdate update = InstanceUpdate.newBuilder().setVirtualThreads(false).build();
    InstanceUpdate parsed = InstanceUpdate.parseFrom(update.toByteArray());
    assertThat(parsed.hasVirtualThreads()).isTrue();
    assertThat(parsed.hasInstanceName()).isFalse();

    Throttle throttle = Throttle.newBuilder().setProfileNormalRate(0.0f).build();
    assertThat(Throttle.parseFrom(throttle.toByteArray()).hasProfileNormalRate()).isTrue();
  }

  @Test
  void transactionBatchOfTheComparisonSenderIsSmall() throws Exception {
    TransactionBatch.Builder batch =
        TransactionBatch.newBuilder()
            .setHeader(
                BatchHeader.newBuilder()
                    .setInstanceId(7)
                    .setAgentEpoch(-1L)
                    .setSeq(1)
                    .setSentAtMs(1_700_000_000_000L));
    for (int i = 0; i < 5; i++) {
      batch.addRecords(
          TransactionRecord.newBuilder()
              .setTxId(1_700_000_000_000L << 20 | i)
              .setKind(TransactionKind.HTTP)
              .setServiceId(0x1234_5678_9abc_def0L)
              .setStartTsMs(1_700_000_000_000L)
              .setElapsedUs(12_345)
              .setHttpMethod("GET")
              .setHttpStatus(200)
              .setUrlPath("/poc/" + i)
              .setCpuTimeUs(4_321));
    }
    MessageLite built = batch.build();
    assertThat(built.getSerializedSize()).isBetween(200, 600);
    assertThat(TransactionBatch.parseFrom(built.toByteArray())).isEqualTo(built);
  }
}
