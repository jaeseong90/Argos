/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.transport;

import com.google.protobuf.ByteString;
import io.argos.agent.bootstrap.DictKind;
import io.argos.agent.bootstrap.StepType;
import io.argos.agent.bootstrap.TraceContext;
import io.argos.agent.core.trace.Dictionary;
import io.argos.agent.core.trace.ProfileRecord;
import io.argos.agent.core.trace.ProfileSteps;
import io.argos.agent.core.trace.RemoteIps;
import io.argos.agent.core.trace.Transaction;
import io.argos.proto.ProtocolLimits;
import io.argos.proto.v1.Limits;
import io.argos.proto.v1.ProfileStep;
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
 * error_top_frame_id}, {@code caller_app_id} (WO-12/13/14/19), {@code ProfileStep.failure} and
 * {@code batch_size} (WO-13/14), {@code app_version_id} (0: the instance's {@code
 * AgentHello.app_version}).
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
            .setKindValue(ctx.kind)
            .setStartTsMs(ctx.startEpochMs)
            .setElapsedUs(Math.max(0L, tx.elapsedNanos()) / 1000L)
            .setHttpStatus(Math.max(0, ctx.status))
            .setError(ctx.error)
            .setSqlCount(Math.max(0, ctx.sqlCount))
            .setSqlTimeMs(u32(ctx.sqlNanos / 1_000_000L))
            .setExtCount(Math.max(0, ctx.extCount))
            .setExtTimeMs(u32(ctx.extNanos / 1_000_000L))
            .setVirtualThread(ctx.virtualThread)
            .setProfileCaptured(tx.profileCaptured())
            .setStreaming(ctx.streaming)
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
      ProfileStep.Builder step =
          ProfileStep.newBuilder()
              .setSeq(i + 1)
              .setParentSeq(Math.max(0, steps.parent(i) + 1))
              .setTypeValue(type)
              .setStartOffsetUs(u32(Math.max(0L, steps.startOffsetNanos(i)) / 1000L))
              .setElapsedUs(u32(Math.max(0L, steps.elapsedNanos(i)) / 1000L))
              .setFetchUs(u32(Math.max(0L, steps.fetchNanos(i)) / 1000L))
              .setRows(u32(Math.max(0L, steps.rows(i))))
              .setStatus(Math.max(0, steps.status(i)))
              .setError(steps.error(i))
              .setRepeatCount(Math.max(1, steps.repeatCount(i)));
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
