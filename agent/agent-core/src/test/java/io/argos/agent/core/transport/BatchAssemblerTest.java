/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.transport;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.bootstrap.CaptureConfig;
import io.argos.agent.bootstrap.DictKind;
import io.argos.agent.bootstrap.Kind;
import io.argos.agent.bootstrap.StepType;
import io.argos.agent.bootstrap.TraceContext;
import io.argos.agent.core.config.AgentConfig;
import io.argos.agent.core.guard.AgentCounters;
import io.argos.agent.core.trace.ProfileRecord;
import io.argos.agent.core.trace.TraceRuntime;
import io.argos.agent.core.trace.TracerImpl;
import io.argos.agent.core.trace.Transaction;
import io.argos.proto.ProtocolLimits;
import io.argos.proto.v1.Limits;
import io.argos.proto.v1.ProfileStep;
import io.argos.proto.v1.TransactionKind;
import io.argos.proto.v1.TransactionProfile;
import io.argos.proto.v1.TransactionRecord;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The argos.v1 messages of queue items (11 §4.3, §4.4, C-4, C-69, DSN-11-07). */
@Tag("req:DSN-02-11")
class BatchAssemblerTest {
  private final TraceRuntime runtime = runtime();
  private final DictionaryCache cache = new DictionaryCache(runtime.dictionary());
  private final BatchAssembler assembler = new BatchAssembler(runtime.dictionary(), cache);

  private static TraceRuntime runtime() {
    Map<String, String> values = new HashMap<String, String>();
    values.put("app.name", "orders");
    values.put("sampling.profile.normal-rate", "1.0");
    return TraceRuntime.create(AgentConfig.of(values), new AgentCounters());
  }

  private Transaction endedTransaction() {
    final List<Transaction> out = new ArrayList<Transaction>();
    runtime
        .queues()
        .transaction()
        .drain(
            Integer.MAX_VALUE,
            new Consumer<QueueItem>() {
              @Override
              public void accept(QueueItem item) {
                out.add((Transaction) item);
              }
            });
    assertThat(out).hasSize(1);
    return out.get(0);
  }

  private ProfileRecord endedProfile() {
    final List<ProfileRecord> out = new ArrayList<ProfileRecord>();
    runtime
        .queues()
        .profile()
        .drain(
            Integer.MAX_VALUE,
            new Consumer<QueueItem>() {
              @Override
              public void accept(QueueItem item) {
                out.add((ProfileRecord) item);
              }
            });
    assertThat(out).hasSize(1);
    return out.get(0);
  }

  private static String repeat(char c, int count) {
    char[] chars = new char[count];
    Arrays.fill(chars, c);
    return new String(chars);
  }

  @Test
  @DisplayName(
      "the record carries ids, kind, times, status, the service id and the 16-byte trace id")
  void record() {
    TracerImpl tracer = runtime.tracerImpl();
    TraceContext ctx = tracer.begin(Kind.HTTP, "GET /orders/{id}", 1_000_000);
    tracer.setServiceName(ctx, "GET /orders/{id}", true);
    tracer.setHttp(ctx, "GET", "/orders/1", "10.1.2.3", "alice");
    tracer.end(ctx, 200, null, null, null, 4_000_000);

    Transaction tx = endedTransaction();
    TransactionRecord record = assembler.record(tx);
    assertThat(record.getTxId()).isEqualTo(ctx.txId);
    assertThat(record.getTraceId().size()).isEqualTo(ProtocolLimits.TRACE_ID_BYTES);
    assertThat(record.getKind()).isEqualTo(TransactionKind.HTTP);
    assertThat(record.getStartTsMs()).isEqualTo(ctx.startEpochMs);
    assertThat(record.getElapsedUs()).isEqualTo(3_000);
    assertThat(record.getHttpStatus()).isEqualTo(200);
    assertThat(record.getHttpMethod()).isEqualTo("GET");
    assertThat(record.getUrlPath()).isEqualTo("/orders/1");
    assertThat(record.getServiceId())
        .isEqualTo(runtime.dictionary().id(DictKind.SERVICE, "GET /orders/{id}"));
    assertThat(record.getRemoteIp().size()).isEqualTo(4);
    assertThat(record.getProfileCaptured()).isEqualTo(tx.profileCaptured());
    assertThat(cache.hasPending()).as("the service name is pre-sent").isTrue();
  }

  @Test
  @Tag("req:F-A28")
  @Tag("req:F-A34")
  @DisplayName(
      "T-AG-017a WO-12 fields: dispatch_count, streaming, timed_out, whitelisted headers (<= 10,"
          + " values 1 KiB) and request_id (128 B) reach the record; nothing when not captured")
  void requestFields() {
    TracerImpl tracer = runtime.tracerImpl();
    TraceContext ctx = tracer.begin(Kind.HTTP, "GET /sse", 0);
    ctx.dispatchCount = 3;
    ctx.streaming = true;
    ctx.timedOut = true;
    String[] headers = new String[24];
    for (int i = 0; i < 12; i++) {
      headers[2 * i] = "X-H" + i;
      headers[2 * i + 1] = i == 0 ? repeat('한', 600) : "v" + i;
    }
    tracer.setHeaders(ctx, headers);
    tracer.setRequestId(ctx, repeat('한', 100));
    tracer.end(ctx, 200, null, null, null, 1);
    TransactionRecord record = assembler.record(endedTransaction());
    assertThat(record.getDispatchCount()).isEqualTo(3);
    assertThat(record.getStreaming()).isTrue();
    assertThat(record.getTimedOut()).isTrue();
    assertThat(record.getHeadersCount()).isEqualTo(ProtocolLimits.MAX_HEADERS);
    assertThat(record.getHeaders(0).getKey()).isEqualTo("x-h0");
    assertThat(record.getHeaders(0).getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8))
        .hasSizeLessThanOrEqualTo(ProtocolLimits.MAX_VALUE_BYTES);
    assertThat(record.getHeaders(1).getValue()).isEqualTo("v1");
    assertThat(record.getRequestId().getBytes(java.nio.charset.StandardCharsets.UTF_8))
        .hasSizeLessThanOrEqualTo(ProtocolLimits.MAX_REQUEST_ID_BYTES);

    TraceContext plain = tracer.begin(Kind.HTTP, "GET /a", 0);
    tracer.end(plain, 200, null, null, null, 1);
    TransactionRecord empty = assembler.record(endedTransaction());
    assertThat(empty.getHeadersCount()).isZero();
    assertThat(empty.getRequestId()).isEmpty();
    assertThat(empty.getTimedOut()).isFalse();
    assertThat(empty.getDispatchCount()).isZero();
  }

  @Test
  @Tag("req:C-4")
  @Tag("req:D-23")
  @DisplayName("C-4: CPU time and allocation that were not measured stay absent optional fields")
  void missingValues() {
    TracerImpl tracer = runtime.tracerImpl();
    TraceContext ctx = tracer.begin(Kind.HTTP, "GET /a", 0);
    tracer.end(ctx, 200, null, null, null, 1);
    TransactionRecord record = assembler.record(endedTransaction());
    assertThat(record.hasCpuTimeUs()).isFalse();
    assertThat(record.hasAllocBytes()).isFalse();
    assertThat(record.getSqlCount()).as("a plain 0 stays the value 0").isZero();
  }

  @Test
  @Tag("req:SEC-11")
  @DisplayName("11 §4.4: strings are cut to their byte caps at character boundaries")
  void stringCaps() {
    TracerImpl tracer = runtime.tracerImpl();
    assembler.limits(Limits.newBuilder().setMaxStringBytes(100).build());
    // setHttp applies capture.user-id (SEC-32): plain mode so that a user id reaches the record.
    tracer.setCapture(
        CaptureConfig.DEFAULT.toBuilder().userIdMode(CaptureConfig.USER_ID_PLAIN).build());
    TraceContext ctx = tracer.begin(Kind.HTTP, "GET /a", 0);
    tracer.setHttp(ctx, "GET", "/" + repeat('p', 500), "127.0.0.1", repeat('한', 200));
    tracer.end(ctx, 500, IllegalStateException.class.getName(), repeat('x', 3_000), null, 1);
    TransactionRecord record = assembler.record(endedTransaction());
    assertThat(record.getUrlPath().getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
        .isLessThanOrEqualTo(100);
    assertThat(record.getUserId()).isNotEmpty();
    assertThat(record.getUserId().getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
        .isLessThanOrEqualTo(ProtocolLimits.MAX_USER_ID_BYTES);
    assertThat(record.getErrorMessage().getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
        .isLessThanOrEqualTo(ProtocolLimits.MAX_ERROR_MESSAGE_BYTES);
    assertThat(record.getError()).isTrue();
    assertThat(record.getErrorClassId())
        .isEqualTo(runtime.dictionary().id(DictKind.CLASS, IllegalStateException.class.getName()));
  }

  @Test
  @DisplayName("the profile: seq from 1, parent_seq 0 for roots, detail cap, names pre-sent")
  void profile() {
    TracerImpl tracer = runtime.tracerImpl();
    assembler.limits(Limits.newBuilder().setMaxDetailBytes(10).build());
    TraceContext ctx = tracer.begin(Kind.HTTP, "GET /b", 1_000_000);
    long sql = tracer.dict(DictKind.SQL, "select 1");
    int seq = tracer.stepStart(ctx, StepType.SQL, sql, 0, 2_000_000);
    tracer.stepDetail(ctx, seq, repeat('d', 100));
    tracer.stepEnd(ctx, seq, 0, false, 5, 5_000_000);
    tracer.end(ctx, 200, null, null, null, 6_000_000);

    TransactionProfile profile = assembler.profile(endedProfile());
    assertThat(profile.getTxId()).isEqualTo(ctx.txId);
    assertThat(profile.getStartTsMs()).isEqualTo(ctx.startEpochMs);
    assertThat(profile.getTotalSteps()).isEqualTo(1);
    assertThat(profile.getTruncated()).isFalse();
    ProfileStep step = profile.getSteps(0);
    assertThat(step.getSeq()).isEqualTo(1);
    assertThat(step.getParentSeq()).isZero();
    assertThat(step.getType()).isEqualTo(io.argos.proto.v1.StepType.SQL);
    assertThat(step.getStartOffsetUs()).isEqualTo(1_000);
    assertThat(step.getElapsedUs()).isEqualTo(3_000);
    assertThat(step.getNameId()).isEqualTo(sql);
    assertThat(step.getRows()).isEqualTo(5);
    assertThat(step.getDetail()).hasSize(10);
    assertThat(step.getRepeatCount()).isEqualTo(1);
    List<io.argos.proto.v1.DictionaryEntries> pending = cache.takePending();
    assertThat(pending.get(0).getEntriesList()).extracting(e -> e.getText()).contains("select ?");
  }

  @Test
  @Tag("req:C-138")
  @DisplayName(
      "ADR-0010 C-138: the profile carries the transaction's kind (HTTP, BATCH, MESSAGE), equal to"
          + " the record's kind (the source of profile_step.kind)")
  void profileKind() {
    TracerImpl tracer = runtime.tracerImpl();
    int[] kinds = {Kind.HTTP, Kind.BATCH, Kind.MESSAGE};
    TransactionKind[] expected = {
      TransactionKind.HTTP, TransactionKind.BATCH, TransactionKind.MESSAGE
    };
    for (int i = 0; i < kinds.length; i++) {
      TraceContext ctx = tracer.begin(kinds[i], "kind " + i, 0);
      tracer.end(ctx, kinds[i] == Kind.HTTP ? 200 : 0, null, null, null, 1);
      TransactionRecord record = assembler.record(endedTransaction());
      TransactionProfile profile = assembler.profile(endedProfile());
      assertThat(profile.getTxId()).isEqualTo(ctx.txId);
      assertThat(profile.getKind()).as(Kind.name(kinds[i])).isEqualTo(expected[i]);
      assertThat(profile.getKind()).as("= TransactionRecord.kind").isEqualTo(record.getKind());
    }
  }

  @Test
  @DisplayName("frame ids are capped: ERROR 30, others the larger STACK cap, and by Limits")
  void frameCaps() {
    assertThat(BatchAssembler.frameCap(StepType.ERROR)).isEqualTo(30);
    assertThat(BatchAssembler.frameCap(StepType.STACK)).isEqualTo(64);
    assertThat(BatchAssembler.nameKind(StepType.SQL)).isEqualTo(DictKind.SQL);
    assertThat(BatchAssembler.nameKind(StepType.DBC)).isEqualTo(DictKind.POOL);
    assertThat(BatchAssembler.nameKind(StepType.HTTP)).isEqualTo(DictKind.HOST);
    assertThat(BatchAssembler.nameKind(StepType.ERROR)).isEqualTo(DictKind.CLASS);
    assertThat(BatchAssembler.nameKind(StepType.METHOD)).isEqualTo(DictKind.METHOD);
    assertThat(BatchAssembler.targetKind(StepType.SQL)).isEqualTo(DictKind.DB_TARGET);
    assertThat(BatchAssembler.targetKind(StepType.COMMIT)).isEqualTo(DictKind.DB_TARGET);
    assertThat(BatchAssembler.targetKind(StepType.HTTP)).isEqualTo(DictKind.HOST);
    assertThat(BatchAssembler.targetKind(StepType.METHOD)).isZero();
  }

  @Test
  @Tag("req:DSN-11-07")
  @DisplayName(
      "DSN-11-07: ProtocolLimits before the hello; a hello's Limits apply, unset values keep the"
          + " defaults; 1 MiB target never above max_message_bytes")
  void limits() {
    assertThat(assembler.limits()).isEqualTo(ProtocolLimits.defaults());
    assertThat(assembler.targetBatchBytes()).isEqualTo(ProtocolLimits.TARGET_BATCH_BYTES);
    assembler.limits(
        Limits.newBuilder().setMaxMessageBytes(1000).setMaxTransactionsPerBatch(9).build());
    assertThat(assembler.targetBatchBytes()).isEqualTo(1000);
    assertThat(assembler.maxRecordsPerBatch()).isEqualTo(9);
    assertThat(assembler.limits().getMaxDetailBytes())
        .isEqualTo(ProtocolLimits.defaults().getMaxDetailBytes());
    assembler.limits(null);
    assertThat(assembler.maxRecordsPerBatch()).isEqualTo(9);
    assembler.limits(Limits.newBuilder().setMaxTransactionsPerBatch(999_999).build());
    assertThat(assembler.maxRecordsPerBatch()).isEqualTo(ProtocolLimits.MAX_RECORDS_PER_BATCH);
  }

  @Test
  @DisplayName("u32 saturates; the trace id is big endian hi then lo")
  void numbers() {
    assertThat(BatchAssembler.u32(-5)).isZero();
    assertThat(BatchAssembler.u32(7)).isEqualTo(7);
    assertThat(BatchAssembler.u32(Long.MAX_VALUE)).isEqualTo(-1);
    byte[] bytes = BatchAssembler.traceId(0x0102030405060708L, 0x090A0B0C0D0E0F10L).toByteArray();
    assertThat(bytes)
        .containsExactly(
            (byte) 1, (byte) 2, (byte) 3, (byte) 4, (byte) 5, (byte) 6, (byte) 7, (byte) 8,
            (byte) 9, (byte) 10, (byte) 11, (byte) 12, (byte) 13, (byte) 14, (byte) 15, (byte) 16);
  }
}
