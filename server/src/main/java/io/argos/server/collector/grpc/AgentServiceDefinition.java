/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.collector.grpc;

import com.google.protobuf.Message;
import com.google.protobuf.Parser;
import io.argos.proto.v1.Ack;
import io.argos.proto.v1.ActiveTransactionSnapshot;
import io.argos.proto.v1.AgentMessage;
import io.argos.proto.v1.AgentServiceGrpc;
import io.argos.proto.v1.MetricBatch;
import io.argos.proto.v1.ProfileBatch;
import io.argos.proto.v1.ServerMessage;
import io.argos.proto.v1.StatBatch;
import io.argos.proto.v1.TransactionBatch;
import io.argos.server.collector.AgentServiceImpl;
import io.argos.server.session.SessionHandler;
import io.grpc.MethodDescriptor;
import io.grpc.ServerServiceDefinition;
import io.grpc.protobuf.ProtoUtils;
import io.grpc.stub.ServerCalls;

/**
 * The {@code argos.v1.AgentService} definition the collector serves (11 §3): the generated method
 * descriptors (names, types) with the collector's request marshaller ({@link InboundMarshaller}:
 * bounded decoding, violations instead of exceptions) bound to {@link AgentServiceImpl} and {@link
 * SessionHandler}. No other service is registered: no reflection, no gRPC health service (16 §4.1,
 * 12 open item 9, ADR-0010 C-130 (5)); calls to them are {@code UNIMPLEMENTED}.
 */
public final class AgentServiceDefinition {

  private AgentServiceDefinition() {}

  /**
   * The service with requests bounded to {@code maxMessageBytes} decompressed bytes.
   *
   * @param service the data RPCs
   * @param sessions the session stream
   * @param maxMessageBytes {@code argos.collector.max-message-bytes}
   */
  public static ServerServiceDefinition of(
      AgentServiceImpl service, SessionHandler sessions, long maxMessageBytes) {
    return ServerServiceDefinition.builder(AgentServiceGrpc.SERVICE_NAME)
        .addMethod(
            method(
                AgentServiceGrpc.getSessionMethod(),
                AgentMessage.parser(),
                ServerMessage.getDefaultInstance(),
                maxMessageBytes),
            ServerCalls.asyncBidiStreamingCall(sessions))
        .addMethod(
            method(
                AgentServiceGrpc.getSendTransactionsMethod(),
                TransactionBatch.parser(),
                Ack.getDefaultInstance(),
                maxMessageBytes),
            ServerCalls.asyncUnaryCall(service::sendTransactions))
        .addMethod(
            method(
                AgentServiceGrpc.getSendProfilesMethod(),
                ProfileBatch.parser(),
                Ack.getDefaultInstance(),
                maxMessageBytes),
            ServerCalls.asyncUnaryCall(service::sendProfiles))
        .addMethod(
            method(
                AgentServiceGrpc.getSendMetricsMethod(),
                MetricBatch.parser(),
                Ack.getDefaultInstance(),
                maxMessageBytes),
            ServerCalls.asyncUnaryCall(service::sendMetrics))
        .addMethod(
            method(
                AgentServiceGrpc.getSendStatsMethod(),
                StatBatch.parser(),
                Ack.getDefaultInstance(),
                maxMessageBytes),
            ServerCalls.asyncUnaryCall(service::sendStats))
        .addMethod(
            method(
                AgentServiceGrpc.getSendActiveSnapshotMethod(),
                ActiveTransactionSnapshot.parser(),
                Ack.getDefaultInstance(),
                maxMessageBytes),
            ServerCalls.asyncUnaryCall(service::sendActiveSnapshot))
        .build();
  }

  private static <Q extends Message, R extends Message> MethodDescriptor<Inbound<Q>, R> method(
      MethodDescriptor<Q, R> generated, Parser<Q> parser, R response, long maxMessageBytes) {
    return generated.toBuilder(
            new InboundMarshaller<>(parser, maxMessageBytes), ProtoUtils.marshaller(response))
        .build();
  }
}
