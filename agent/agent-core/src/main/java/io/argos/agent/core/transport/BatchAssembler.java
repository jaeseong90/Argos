/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.transport;

import com.google.protobuf.ByteString;
import com.google.protobuf.Message;
import io.argos.agent.bootstrap.DictKind;
import io.argos.agent.bootstrap.StepType;
import io.argos.agent.bootstrap.TraceContext;
import io.argos.agent.core.trace.Dictionary;
import io.argos.agent.core.trace.ProfileRecord;
import io.argos.agent.core.trace.ProfileSteps;
import io.argos.agent.core.trace.RemoteIps;
import io.argos.agent.core.trace.Transaction;
import io.argos.proto.ProtocolLimits;
import io.argos.proto.v1.ActiveState;
import io.argos.proto.v1.ActiveTransaction;
import io.argos.proto.v1.ActiveTransactionSnapshot;
import io.argos.proto.v1.ExtStat;
import io.argos.proto.v1.KeyValue;
import io.argos.proto.v1.Limits;
import io.argos.proto.v1.ProfileStep;
import io.argos.proto.v1.SqlStat;
import io.argos.proto.v1.StatBatch;
import io.argos.proto.v1.TransactionProfile;
import io.argos.proto.v1.TransactionRecord;

/**
 * Builds the argos.v1 messages of the queue items on {@code argos-sender} (10 §10.2 {@code
 * BatchAssembler}, 11 §4.3, §4.4, DSN-11-07):
 *
 * <ul>
 *   <li>{@link #record(Transaction)}: one {@code TransactionRecord} (XLog point);
 *   <li>{@link #profile(ProfileRecord)}: one {@code TransactionProfile} from the step builder
 *       ({@link ProfileSteps}: repeat folding, {@code truncated}, {@code total_steps}), at most
 *       {@value ProtocolLimits#MAX_STEPS_PER_PROFILE} steps, with the transaction's {@code kind}
 *       (ADR-0010 C-138).
 * </ul>
 *
 * <p>Caps: the {@code Limits} of the session's {@code ServerHello} ({@link #limits(Limits)}), the
 * 11 §4.2 defaults ({@link ProtocolLimits#defaults()}) before the first hello; field caps of 11 §4
 * that {@code Limits} does not carry are the {@link ProtocolLimits} constants. Strings are cut to
 * whole characters within their UTF-8 byte cap.
 *
 * <p>Missing values (C-4, D-23): CPU time and allocated bytes the agent did not measure stay absent
 * optional fields; every other 0 is the value 0.
 *
 * <p>Dictionary ids: every id a message carries is passed to the {@link DictionaryCache} so that
 * its text is pre-sent (11 §7). The service name and the error class get their id here ({@link
 * Dictionary#id(int, String)}, the same xxh3 the tracer uses).
 *
 * <p>Summary steps (summary mode, 10 §9.1): each entry of the step builder's summary becomes one
 * {@code ProfileStep} of type {@code SUMMARY} after the recorded steps: {@code parent_seq} 0,
 * {@code name_id} = the summarized name, {@code status} = the summarized step type (C-1 number),
 * {@code repeat_count} = the step count, {@code elapsed_us} = their total time, {@code fetch_us} =
 * the longest one, {@code rows} = the number of failed ones, {@code error} = any failed. This
 * encoding is normative (ADR-0010 C-141; the {@code profile_step} table of proto/README.md).
 *
 * <p>Not filled yet (no producer before the work order named): {@code headers}, {@code request_id},
 * {@code sql_ids}, {@code dbc_wait_ms}, {@code dispatch_count}, {@code timed_out}, {@code
 * error_top_frame_id} (WO-12/13/19), {@code app_version_id} (0: the instance's {@code
 * AgentHello.app_version}). {@code ProfileStep.batch_size} is the step's {@code executeBatch} item
 * count ({@code Tracer.stepBatch}, WO-13), absent when not set. WO-14 fills {@code caller_app_id}
 * (the incoming {@code tracestate} {@code argos} entry), {@code ProfileStep.failure} (HTTP steps)
 * and {@code ext_count} / {@code ext_time_ms} (written by {@code Tracer.httpEnd} through {@code
 * TraceContext.addExternalCall}).
 */
final class BatchAssembler {
  private final Dictionary dictionary;
  private final DictionaryCache dictionaryCache;
  private volatile Limits limits = ProtocolLimits.defaults();

  BatchAssembler(Dictionary dictionary, DictionaryCache dictionaryCache) {
    this.dictionary = dictionary;
    this.dictionaryCache = dictionaryCache;
  }

  /** Applies the {@code Limits} of a {@code ServerHello}; unset (0) values keep the defaults. */
  void limits(Limits value) {
    if (value == null) {
      return;
    }
    Limits defaults = ProtocolLimits.defaults();
    limits =
        Limits.newBuilder()
            .setMaxMessageBytes(or(value.getMaxMessageBytes(), defaults.getMaxMessageBytes()))
            .setMaxTransactionsPerBatch(
                or(value.getMaxTransactionsPerBatch(), defaults.getMaxTransactionsPerBatch()))
            .setMaxStepsPerBatch(or(value.getMaxStepsPerBatch(), defaults.getMaxStepsPerBatch()))
            .setMaxDetailBytes(or(value.getMaxDetailBytes(), defaults.getMaxDetailBytes()))
            .setMaxStringBytes(or(value.getMaxStringBytes(), defaults.getMaxStringBytes()))
            .setMaxFrames(or(value.getMaxFrames(), defaults.getMaxFrames()))
            .setMaxActiveItems(or(value.getMaxActiveItems(), defaults.getMaxActiveItems()))
            .setMaxSeriesPerInstance(
                or(value.getMaxSeriesPerInstance(), defaults.getMaxSeriesPerInstance()))
            .setMaxDictionaryEntriesPerHour(
                or(
                    value.getMaxDictionaryEntriesPerHour(),
                    defaults.getMaxDictionaryEntriesPerHour()))
            .build();
  }

  Limits limits() {
    return limits;
  }

  /** Records per {@code TransactionBatch}: {@code Limits} and 11 §4.4 (5,000). */
  int maxRecordsPerBatch() {
    return Math.max(
        1, Math.min(limits.getMaxTransactionsPerBatch(), ProtocolLimits.MAX_RECORDS_PER_BATCH));
  }

  /** Steps of all profiles of one {@code ProfileBatch}: {@code Limits} and 11 §4.4 (50,000). */
  int maxStepsPerBatch() {
    return Math.max(
        1, Math.min(limits.getMaxStepsPerBatch(), ProtocolLimits.MAX_PROFILE_STEPS_PER_BATCH));
  }

  /**
   * The serialized size a batch aims at: 1 MiB (11 §2), never above the server's {@code
   * max_message_bytes}.
   */
  int targetBatchBytes() {
    int max = limits.getMaxMessageBytes();
    return max > 0
        ? Math.min(ProtocolLimits.TARGET_BATCH_BYTES, max)
        : ProtocolLimits.TARGET_BATCH_BYTES;
  }

  /** The {@code TransactionRecord} of an ended transaction. */
  TransactionRecord record(Transaction tx) {
    TraceContext ctx = tx.context();
    int maxString = limits.getMaxStringBytes();
    TransactionRecord.Builder record =
        TransactionRecord.newBuilder()
            .setTxId(ctx.txId)
            .setTraceId(traceId(ctx.traceIdHi, ctx.traceIdLo))
            .setSpanId(ctx.spanId)
            .setParentSpanId(ctx.parentSpanId)
            .setIncomingTrace(ctx.parentSpanId != 0)
            // WO-14: the app id of the incoming tracestate argos entry (0: none, malformed).
            .setCallerAppId(ctx.callerAppId)
            .setKindValue(ctx.kind)
            .setStartTsMs(ctx.startEpochMs)
            .setElapsedUs(Math.max(0L, tx.elapsedNanos()) / 1000L)
            .setHttpStatus(Math.max(0, ctx.status))
            .setError(ctx.error)
            .setSqlCount(Math.max(0, ctx.sqlCount))
            .setSqlTimeMs(u32(ctx.sqlNanos / 1_000_000L))
            .setExtCount(Math.max(0, ctx.extCount))
            .setExtTimeMs(u32(ctx.extNanos / 1_000_000L))
            // WO-13: outermost DataSource.getConnection() time of the transaction (F-A03).
            .setDbcWaitMs(u32(Math.max(0L, ctx.dbcNanos) / 1_000_000L))
            .setVirtualThread(ctx.virtualThread)
            .setProfileCaptured(tx.profileCaptured())
            .setStreaming(ctx.streaming)
            .setTimedOut(ctx.timedOut)
            .setDispatchCount(Math.max(0, ctx.dispatchCount))
            .setFlags(tx.flags() & TraceContext.FLAG_MASK);
    long serviceId = dictionary.id(DictKind.SERVICE, ctx.serviceName);
    record.setServiceId(serviceId);
    dictionaryCache.reference(DictKind.SERVICE, serviceId);
    long threadId = tx.threadNameId();
    record.setThreadNameId(threadId);
    dictionaryCache.reference(DictKind.THREAD, threadId);
    String method = tx.httpMethod();
    if (method != null) {
      record.setHttpMethod(Utf8.cut(method, ProtocolLimits.MAX_HTTP_METHOD_BYTES));
    }
    String path = tx.path();
    if (path != null) {
      record.setUrlPath(
          Utf8.cut(path, Math.min(ProtocolLimits.MAX_URL_PATH_BYTES, Math.max(maxString, 1))));
    }
    String errorClass = tx.errorClass();
    if (errorClass != null) {
      long classId = dictionary.id(DictKind.CLASS, errorClass);
      record.setErrorClassId(classId);
      dictionaryCache.reference(DictKind.CLASS, classId);
    }
    String message = tx.errorMessage();
    if (message != null) {
      record.setErrorMessage(Utf8.cut(message, ProtocolLimits.MAX_ERROR_MESSAGE_BYTES));
    }
    byte[] ip = RemoteIps.bytes(tx.remoteIp());
    if (ip != null) {
      record.setRemoteIp(ByteString.copyFrom(ip));
    }
    String user = tx.userId();
    if (user != null) {
      record.setUserId(Utf8.cut(user, ProtocolLimits.MAX_USER_ID_BYTES));
    }
    // WO-12: whitelisted headers (<= 10 pairs) and the request id header value (C-124).
    String[] headers = tx.headers();
    for (int i = 0;
        i + 1 < headers.length && record.getHeadersCount() < ProtocolLimits.MAX_HEADERS;
        i += 2) {
      record.addHeaders(
          KeyValue.newBuilder()
              .setKey(Utf8.cut(headers[i], ProtocolLimits.MAX_KEY_BYTES))
              .setValue(Utf8.cut(headers[i + 1], ProtocolLimits.MAX_VALUE_BYTES)));
    }
    String requestId = tx.requestId();
    if (requestId != null) {
      record.setRequestId(Utf8.cut(requestId, ProtocolLimits.MAX_REQUEST_ID_BYTES));
    }
    if (tx.cpuNanos() >= 0) {
      record.setCpuTimeUs(tx.cpuNanos() / 1000L);
    }
    if (tx.allocatedBytes() >= 0) {
      record.setAllocBytes(tx.allocatedBytes());
    }
    return record.build();
  }

  /** The {@code TransactionProfile} of a sampled transaction. */
  TransactionProfile profile(ProfileRecord profile) {
    ProfileSteps steps = profile.build();
    int maxDetail = limits.getMaxDetailBytes();
    int maxFrames = Math.max(0, limits.getMaxFrames());
    TransactionProfile.Builder out =
        TransactionProfile.newBuilder()
            .setTxId(profile.txId())
            .setStartTsMs(profile.startEpochMs())
            .setKindValue(profile.kind())
            .setTotalSteps(u32(steps.totalSteps()));
    int cap = ProtocolLimits.MAX_STEPS_PER_PROFILE;
    boolean truncated = steps.truncated();
    int recorded = steps.size();
    for (int i = 0; i < recorded; i++) {
      if (out.getStepsCount() >= cap) {
        truncated = true;
        break;
      }
      int type = steps.type(i);
      long fetchNanos = Math.max(0L, steps.fetchNanos(i));
      // 11 ProfileStep.elapsed_us of an SQL step is exec + fetch (the server stores exec_ms as
      // elapsed - fetch); the step buffer keeps the execution time and the fetch time apart.
      long elapsedNanos = Math.max(0L, steps.elapsedNanos(i)) + fetchNanos;
      ProfileStep.Builder step =
          ProfileStep.newBuilder()
              .setSeq(i + 1)
              .setParentSeq(Math.max(0, steps.parent(i) + 1))
              .setTypeValue(type)
              .setStartOffsetUs(u32(Math.max(0L, steps.startOffsetNanos(i)) / 1000L))
              .setElapsedUs(u32(elapsedNanos / 1000L))
              .setFetchUs(u32(fetchNanos / 1000L))
              .setRows(u32(Math.max(0L, steps.rows(i))))
              .setStatus(Math.max(0, steps.status(i)))
              .setError(steps.error(i))
              .setRepeatCount(Math.max(1, steps.repeatCount(i)));
      int failure = steps.failure(i);
      if (failure != 0) {
        // WO-14: the outcome class of an HTTP step (10 §6.5); the server keeps the status when it
        // is not 0 and the failure number otherwise (ADR-0010 C-141, C-143 (5)).
        step.setFailureValue(failure);
      }
      int batchSize = steps.batchSize(i);
      if (batchSize > 0) {
        step.setBatchSize(batchSize);
      }
      long nameId = steps.nameId(i);
      step.setNameId(nameId);
      dictionaryCache.reference(nameKind(type), nameId);
      long targetId = steps.targetId(i);
      step.setTargetId(targetId);
      dictionaryCache.reference(targetKind(type), targetId);
      String detail = steps.detail(i);
      if (detail != null) {
        step.setDetail(Utf8.cut(detail, Math.max(0, maxDetail)));
      }
      long[] stack = steps.stack(i);
      if (stack != null) {
        int frames = Math.min(stack.length, Math.min(maxFrames, frameCap(type)));
        for (int f = 0; f < frames; f++) {
          step.addFrameIds(stack[f]);
          dictionaryCache.reference(DictKind.FRAME, stack[f]);
        }
      }
      out.addSteps(step);
    }
    for (int key = 0; key < steps.summarySize(); key++) {
      if (out.getStepsCount() >= cap) {
        truncated = true;
        break;
      }
      long nameId = steps.summaryNameId(key);
      long errors = steps.summaryErrors(key);
      out.addSteps(
          ProfileStep.newBuilder()
              .setSeq(out.getStepsCount() + 1)
              .setParentSeq(0)
              .setType(io.argos.proto.v1.StepType.SUMMARY)
              .setNameId(nameId)
              .setStatus(Math.max(0, steps.summaryType(key)))
              .setRepeatCount(u32(steps.summaryCount(key)))
              .setElapsedUs(u32(steps.summaryTotalNanos(key) / 1000L))
              .setFetchUs(u32(steps.summaryMaxNanos(key) / 1000L))
              .setRows(u32(errors))
              .setError(errors > 0));
      dictionaryCache.reference(nameKind(steps.summaryType(key)), nameId);
    }
    return out.setTruncated(truncated).build();
  }

  /**
   * A batch its producer built ({@link PreparedBatch}, WO-16) as it is sent: the dictionary ids of
   * a statistics batch ({@code sql_id}, {@code db_id}, {@code host_id}) and of an active snapshot
   * ({@code service_id}, {@code thread_name_id}, {@code state_name_id} by state) go to the {@link
   * DictionaryCache} so that their texts are pre-sent (11 §7 (1)); an active snapshot keeps at most
   * the session's {@code Limits.max_active_items} items (the longest running, the items are sorted)
   * and is then {@code truncated}. Metric batches carry their series names inline and pass as they
   * are.
   */
  Message prepared(BatchKind kind, Message body) {
    if (kind == BatchKind.STATS && body instanceof StatBatch) {
      StatBatch stats = (StatBatch) body;
      for (SqlStat row : stats.getSqlList()) {
        dictionaryCache.reference(DictKind.SQL, row.getSqlId());
        dictionaryCache.reference(DictKind.DB_TARGET, row.getDbId());
      }
      for (ExtStat row : stats.getExtList()) {
        dictionaryCache.reference(DictKind.HOST, row.getHostId());
      }
      return body;
    }
    if (kind == BatchKind.ACTIVE && body instanceof ActiveTransactionSnapshot) {
      ActiveTransactionSnapshot snapshot = (ActiveTransactionSnapshot) body;
      int max = Math.max(1, Math.min(limits.getMaxActiveItems(), ProtocolLimits.MAX_ACTIVE_ITEMS));
      if (snapshot.getItemsCount() > max) {
        ActiveTransactionSnapshot.Builder fewer =
            snapshot.toBuilder().clearItems().setTruncated(true);
        for (int i = 0; i < max; i++) {
          fewer.addItems(snapshot.getItems(i));
        }
        snapshot = fewer.build();
      }
      for (ActiveTransaction item : snapshot.getItemsList()) {
        dictionaryCache.reference(DictKind.SERVICE, item.getServiceId());
        dictionaryCache.reference(DictKind.THREAD, item.getThreadNameId());
        int stateKind = activeStateKind(item.getStateValue());
        if (stateKind != 0) {
          dictionaryCache.reference(stateKind, item.getStateNameId());
        }
      }
      return snapshot;
    }
    return body;
  }

  /** The dictionary kind of {@code ActiveTransaction.state_name_id} by state (11 §4.4); 0: none. */
  static int activeStateKind(int state) {
    switch (state) {
      case ActiveState.IN_SQL_VALUE:
        return DictKind.SQL;
      case ActiveState.IN_HTTP_VALUE:
        return DictKind.HOST;
      case ActiveState.IN_DBC_WAIT_VALUE:
        return DictKind.DB_TARGET;
      default:
        return 0;
    }
  }

  /** The dictionary kind of a step's {@code name_id} by step type (a fallback, see the cache). */
  static int nameKind(int type) {
    switch (type) {
      case StepType.SQL:
        return DictKind.SQL;
      case StepType.DBC:
        return DictKind.POOL;
      case StepType.HTTP:
        return DictKind.HOST;
      case StepType.ASYNC:
      case StepType.ERROR:
        return DictKind.CLASS;
      default:
        return DictKind.METHOD;
    }
  }

  /** The dictionary kind of a step's {@code target_id} by step type. */
  static int targetKind(int type) {
    switch (type) {
      case StepType.SQL:
      case StepType.DBC:
      case StepType.COMMIT:
      case StepType.ROLLBACK:
        return DictKind.DB_TARGET;
      case StepType.HTTP:
        return DictKind.HOST;
      default:
        return 0;
    }
  }

  /** Frame cap by step type: ERROR 30, STACK 64 (11 §4.3), others the larger one. */
  static int frameCap(int type) {
    return type == StepType.ERROR
        ? ProtocolLimits.MAX_ERROR_FRAMES
        : ProtocolLimits.MAX_STACK_FRAMES;
  }

  static ByteString traceId(long hi, long lo) {
    byte[] bytes = new byte[ProtocolLimits.TRACE_ID_BYTES];
    for (int i = 0; i < 8; i++) {
      bytes[i] = (byte) (hi >>> (56 - 8 * i));
      bytes[8 + i] = (byte) (lo >>> (56 - 8 * i));
    }
    return ByteString.copyFrom(bytes);
  }

  /** {@code value} as an unsigned 32-bit field value (saturated at 2^32 - 1, negative as 0). */
  static int u32(long value) {
    if (value <= 0) {
      return 0;
    }
    return (int) Math.min(value, 0xFFFF_FFFFL);
  }

  private static int or(int value, int fallback) {
    return value > 0 ? value : fallback;
  }
}
