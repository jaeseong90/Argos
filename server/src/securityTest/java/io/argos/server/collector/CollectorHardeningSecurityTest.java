/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.collector;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.proto.v1.RejectReason;
import io.argos.proto.v1.TransactionBatch;
import io.argos.server.testing.ServerTestEnvironment;
import io.argos.server.testing.auth.MutableClock;
import io.argos.server.testing.collector.CollectorHarness;
import io.argos.server.testing.fakeagent.AgentTokens;
import io.argos.server.testing.fakeagent.Attacks;
import io.argos.server.testing.fakeagent.CallResult;
import io.argos.server.testing.fakeagent.FakeAgent;
import io.argos.server.testing.fakeagent.HelloResult;
import io.grpc.CallOptions;
import io.grpc.ManagedChannel;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.ClientCalls;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * T-SEC-15 and the SEC-05 address block on the real gRPC listener: no reflection and no health
 * service, unauthenticated and session-less data calls refused before the service, and the
 * unauthenticated Hello flood of 18 §8 (100 failed Hellos per minute from one address) blocked
 * after 20 failures for 10 minutes, measured with an injectable clock.
 */
@Tag("req:SEC-15")
@Tag("req:SEC-05")
class CollectorHardeningSecurityTest {

  private static ServerTestEnvironment env;

  @BeforeAll
  static void start() {
    env = ServerTestEnvironment.start();
  }

  @AfterAll
  static void stop() {
    if (env != null) {
      env.close();
    }
  }

  /** A raw unary call of {@code fullMethod} with empty bytes. */
  private static Status rawCall(ManagedChannel channel, String fullMethod) {
    MethodDescriptor<byte[], byte[]> method =
        MethodDescriptor.<byte[], byte[]>newBuilder()
            .setType(MethodDescriptor.MethodType.UNARY)
            .setFullMethodName(fullMethod)
            .setRequestMarshaller(new Bytes())
            .setResponseMarshaller(new Bytes())
            .build();
    try {
      ClientCalls.blockingUnaryCall(
          channel,
          method,
          CallOptions.DEFAULT.withDeadlineAfter(10, TimeUnit.SECONDS),
          new byte[0]);
      return Status.OK;
    } catch (StatusRuntimeException e) {
      return e.getStatus();
    }
  }

  @Test
  @DisplayName("T-SEC-15 reflection (v1, v1alpha) and the gRPC health service are UNIMPLEMENTED")
  void noReflectionNoHealth() {
    try (CollectorHarness collector = CollectorHarness.start(env)) {
      ManagedChannel channel = collector.channel();
      try {
        assertThat(
                rawCall(channel, "grpc.reflection.v1alpha.ServerReflection/ServerReflectionInfo")
                    .getCode())
            .isEqualTo(Status.Code.UNIMPLEMENTED);
        assertThat(
                rawCall(channel, "grpc.reflection.v1.ServerReflection/ServerReflectionInfo")
                    .getCode())
            .isEqualTo(Status.Code.UNIMPLEMENTED);
        assertThat(rawCall(channel, "grpc.health.v1.Health/Check").getCode())
            .isEqualTo(Status.Code.UNIMPLEMENTED);
      } finally {
        channel.shutdownNow();
      }
    }
  }

  @Test
  @DisplayName(
      "T-SEC-15 SendTransactions without a token is UNAUTHENTICATED, without a session SESSION_REQUIRED; nothing reaches the WAL")
  void unauthenticatedData() {
    try (CollectorHarness collector = CollectorHarness.start(env)) {
      String app = "hard-" + UUID.randomUUID().toString().substring(0, 8);
      String token = collector.token(app, "prod").token();
      try (FakeAgent a = collector.agent(token).appName(app).build()) {
        TransactionBatch batch =
            a.batches().transactions(List.of(a.records().transaction().build()));
        CallResult noToken =
            a.send(
                io.argos.server.testing.fakeagent.BatchKind.TRANSACTIONS,
                batch,
                1,
                FakeAgent.CallSpec.defaults().withoutToken());
        assertThat(noToken.code()).isEqualTo(Status.Code.UNAUTHENTICATED);
        assertThat(noToken.rejectReason()).isEqualTo(RejectReason.TOKEN_INVALID.name());
        CallResult noSession = a.sendTransactions(batch);
        assertThat(noSession.code()).isEqualTo(Status.Code.FAILED_PRECONDITION);
        assertThat(noSession.status().getDescription()).isEqualTo("SESSION_REQUIRED");
        assertThat(collector.wal().appended()).isEmpty();
        assertThat(collector.ingest().calls()).isEmpty();
      }
    }
  }

  @Test
  @Tag("req:C-156")
  @DisplayName(
      "T-SEC-15 18 §8 unauth-flood: 100 failed Hellos from one address -> blocked after 20 with"
          + " RESOURCE_EXHAUSTED and argos-retry-after-ms = the remaining block time (C-156),"
          + " released after 10 minutes")
  void unauthenticatedFlood() {
    MutableClock clock = new MutableClock();
    try (CollectorHarness collector = CollectorHarness.builder(env).clock(clock).start()) {
      String app = "flood-" + UUID.randomUUID().toString().substring(0, 8);
      String valid = collector.token(app, "prod").token();
      ManagedChannel channel = collector.channel();
      try {
        List<HelloResult> results =
            Attacks.unauthenticatedHelloFlood(
                i ->
                    FakeAgent.builder(channel)
                        .token(AgentTokens.generate())
                        .appName(app)
                        .helloTimeout(Duration.ofSeconds(5))
                        .build(),
                Attacks.UNAUTHENTICATED_HELLOS_PER_MINUTE,
                Duration.ZERO);
        assertThat(results).hasSize(100);
        for (int i = 0; i < 20; i++) {
          assertThat(results.get(i).rejectReason())
              .as("Hello %d is checked: ServerHello TOKEN_INVALID", i)
              .isEqualTo(RejectReason.TOKEN_INVALID);
        }
        // The collector's clock does not move during the flood: the whole block remains.
        for (int i = 20; i < 100; i++) {
          HelloResult r = results.get(i);
          assertThat(r.hello()).as("Hello %d: refused before the session handler", i).isNull();
          assertThat(r.code()).isEqualTo(Status.Code.RESOURCE_EXHAUSTED);
          assertThat(r.status().getDescription()).contains("Too many authentication failures");
          assertThat(r.trailers().get(CallResult.RETRY_AFTER_MS))
              .as("Hello %d: the remaining block time", i)
              .isEqualTo(Long.toString(Duration.ofMinutes(10).toMillis()));
          assertThat(r.trailerRejectReason()).isEqualTo("auth_blocked");
        }
        MeterRegistry meters = collector.bean(MeterRegistry.class);
        assertThat(meters.get("argos.collector.auth.blocked").counter().count()).isEqualTo(80);
        double failuresBefore =
            meters.get("argos.collector.rejected").tag("reason", "token_unknown").counter().count();

        clock.advance(Duration.ofMinutes(4));
        try (FakeAgent blocked = FakeAgent.builder(channel).token(valid).appName(app).build()) {
          HelloResult r = blocked.connect();
          assertThat(r.code())
              .as("a valid token from the blocked address is refused too, without a lookup")
              .isEqualTo(Status.Code.RESOURCE_EXHAUSTED);
          assertThat(r.hello()).isNull();
          assertThat(r.trailers().get(CallResult.RETRY_AFTER_MS))
              .as("6 of the 10 minutes remain")
              .isEqualTo(Long.toString(Duration.ofMinutes(6).toMillis()));
        }
        assertThat(meters.get("argos.collector.auth.blocked").counter().count()).isEqualTo(81);
        assertThat(
                meters
                    .get("argos.collector.rejected")
                    .tag("reason", "token_unknown")
                    .counter()
                    .count())
            .as("no token verdict for a blocked call")
            .isEqualTo(failuresBefore);
        clock.advance(Duration.ofMinutes(6));
        try (FakeAgent released = FakeAgent.builder(channel).token(valid).appName(app).build()) {
          assertThat(released.connect().accepted()).as("released after 10 minutes").isTrue();
        }
      } finally {
        channel.shutdownNow();
      }
    }
  }

  /** Raw bytes in both directions. */
  private static final class Bytes implements MethodDescriptor.Marshaller<byte[]> {
    @Override
    public InputStream stream(byte[] value) {
      return new ByteArrayInputStream(value);
    }

    @Override
    public byte[] parse(InputStream stream) {
      try {
        return stream.readAllBytes();
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
  }
}
