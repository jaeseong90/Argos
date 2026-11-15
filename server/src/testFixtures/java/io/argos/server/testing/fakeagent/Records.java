/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.testing.fakeagent;

import com.google.protobuf.ByteString;
import io.argos.proto.LatencyBuckets;
import io.argos.proto.TransactionFlags;
import io.argos.proto.v1.ActiveState;
import io.argos.proto.v1.ActiveTransaction;
import io.argos.proto.v1.CounterDelta;
import io.argos.proto.v1.DictionaryEntry;
import io.argos.proto.v1.DictionaryKind;
import io.argos.proto.v1.ExtStat;
import io.argos.proto.v1.FailureKind;
import io.argos.proto.v1.KeyValue;
import io.argos.proto.v1.MetricKind;
import io.argos.proto.v1.MetricSample;
import io.argos.proto.v1.MetricSeries;
import io.argos.proto.v1.ProfileStep;
import io.argos.proto.v1.SqlStat;
import io.argos.proto.v1.StepType;
import io.argos.proto.v1.TransactionKind;
import io.argos.proto.v1.TransactionProfile;
import io.argos.proto.v1.TransactionRecord;
import io.argos.server.ingest.hash.Xxh3;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SplittableRandom;

/**
 * Record builders of the fake agent: every field that feeds a V001 (and V002, ADR-0010 C-144)
 * column is filled with a plausible value, on the agent clock ({@link AgentClock}), following the
 * proto/README.md mapping. Tests change what they need on the returned protobuf builders (for
 * example {@code clearCpuTimeUs()} for an unmeasured transaction, D-23).
 *
 * <p>Every name used gets its dictionary id from {@link #id(DictionaryKind, String)}: the XXH3-64
 * of the text with the 0 → 1 remap, as the agent computes it ({@link Xxh3}), and the entry is
 * remembered for {@link #dictionaryEntries()} and {@link #drainNewEntries()} (the pre-send of 11
 * §7) and for answers to {@code DictionaryRequest}.
 *
 * <p>Thread-safe; WO-29's load generator can share one instance per fake agent.
 */
public final class Records {

  /** Normalizer version sent with {@code SQL_TEXT} entries (D-22). */
  public static final int SQL_NORM_VERSION = 1;

  private final AgentClock clock;
  private final TxIds txIds;
  private final SplittableRandom random;
  private final Map<Key, DictionaryEntry> dictionary = new LinkedHashMap<>();
  private final List<DictionaryEntry> fresh = new ArrayList<>();
  private long requestCounter;

  /** Builders on {@code clock}, agent node 1, a random seed. */
  public Records(AgentClock clock) {
    this(clock, 1, new SplittableRandom().nextLong());
  }

  /**
   * Builders on {@code clock}.
   *
   * @param node the agent node of generated {@code tx_id}s (0..4095)
   * @param seed seed of random ids (trace and span ids)
   */
  public Records(AgentClock clock, int node, long seed) {
    this.clock = clock;
    this.txIds = new TxIds(node);
    this.random = new SplittableRandom(seed);
  }

  /** The agent clock of these builders. */
  public AgentClock clock() {
    return clock;
  }

  // --- dictionary (11 §7) ---

  /**
   * The dictionary id of {@code text} (XXH3-64 of its UTF-8 bytes, never 0); the entry is
   * remembered and, the first time, queued for {@link #drainNewEntries()}.
   */
  public synchronized long id(DictionaryKind kind, String text) {
    long hash = Xxh3.hash64(text);
    Key key = new Key(kind, hash);
    if (!dictionary.containsKey(key)) {
      DictionaryEntry entry =
          DictionaryEntry.newBuilder()
              .setKind(kind)
              .setHash(hash)
              .setText(text)
              .setNormVersion(kind == DictionaryKind.SQL_TEXT ? SQL_NORM_VERSION : 0)
              .build();
      dictionary.put(key, entry);
      fresh.add(entry);
    }
    return hash;
  }

  /** Every remembered entry, in first-use order. */
  public synchronized List<DictionaryEntry> dictionaryEntries() {
    return List.copyOf(dictionary.values());
  }

  /** The entries first used since the last call (what the agent pre-sends, 11 §7 (1)). */
  public synchronized List<DictionaryEntry> drainNewEntries() {
    List<DictionaryEntry> drained = List.copyOf(fresh);
    fresh.clear();
    return drained;
  }

  /** The remembered entry of ({@code kind}, {@code hash}). */
  public synchronized Optional<DictionaryEntry> entry(DictionaryKind kind, long hash) {
    return Optional.ofNullable(dictionary.get(new Key(kind, hash)));
  }

  /** A dictionary key: (kind, hash). */
  private record Key(DictionaryKind kind, long hash) {}

  // --- transactions (xlog) ---

  /** An HTTP transaction of {@code GET /api/orders/{id}} that ended now after 25 ms. */
  public TransactionRecord.Builder transaction() {
    return http("GET /api/orders/{id}", clock.nowMs() - 25, 25_000);
  }

  /**
   * A successful HTTP transaction with every xlog field set: ids, trace context, service, timing,
   * method, status, URL path, masked remote IP (4 bytes), counts and times of SQL, outgoing calls
   * and connection wait, measured CPU and allocation (present), thread, SQL ids, dispatch count, a
   * whitelisted header, a request id and the C-69 flags.
   *
   * @param service service name (SERVICE dictionary), e.g. {@code "GET /api/orders/{id}"}
   * @param startMs start on the agent clock (also the {@code tx_id} prefix)
   * @param elapsedUs duration in microseconds
   */
  public TransactionRecord.Builder http(String service, long startMs, long elapsedUs) {
    String sql = "SELECT o.id, o.total FROM orders o WHERE o.id = ?";
    long requestNumber;
    synchronized (this) {
      requestNumber = ++requestCounter;
    }
    String method = service.contains(" ") ? service.substring(0, service.indexOf(' ')) : "GET";
    return TransactionRecord.newBuilder()
        .setTxId(txIds.next(startMs))
        .setTraceId(traceId(startMs))
        .setSpanId(nonZeroRandom())
        .setParentSpanId(0)
        .setIncomingTrace(false)
        .setCallerAppId(0)
        .setKind(TransactionKind.HTTP)
        .setServiceId(id(DictionaryKind.SERVICE, service))
        .setStartTsMs(startMs)
        .setElapsedUs(elapsedUs)
        .setHttpMethod(method)
        .setHttpStatus(200)
        .setUrlPath("/api/orders/" + requestNumber)
        .setError(false)
        .setRemoteIp(ByteString.copyFrom(new byte[] {10, 1, 2, 0}))
        .setUserId("")
        .setSqlCount(2)
        .setSqlTimeMs(12)
        .setExtCount(1)
        .setExtTimeMs(5)
        .setDbcWaitMs(1)
        .setCpuTimeUs(8_000)
        .setAllocBytes(1L << 20)
        .setThreadNameId(id(DictionaryKind.THREAD, "http-nio-8080-exec-#"))
        .setVirtualThread(false)
        .setProfileCaptured(false)
        .addSqlIds(id(DictionaryKind.SQL_TEXT, sql))
        .setDispatchCount(1)
        .setStreaming(false)
        .setTimedOut(false)
        .addHeaders(KeyValue.newBuilder().setKey("X-Request-Id").setValue("req-" + requestNumber))
        .setRequestId("req-" + requestNumber)
        .setAppVersionId(0)
        .setFlags(0);
  }

  /**
   * A BATCH transaction (the agent sends {@code http_status} 0 and no method; the server stores 1
   * for a failure, ADR-0010 C-143 (3)).
   */
  public TransactionRecord.Builder batch(
      String service, long startMs, long elapsedUs, boolean failed) {
    TransactionRecord.Builder b =
        http(service, startMs, elapsedUs)
            .setKind(TransactionKind.BATCH)
            .setHttpMethod("")
            .setHttpStatus(0)
            .setUrlPath("")
            .clearHeaders()
            .setRemoteIp(ByteString.EMPTY);
    return failed
        ? withError(b, "java.lang.IllegalStateException", "step failed for job #", "Job.run(Job)")
        : b;
  }

  /**
   * {@code builder} as a failed transaction: {@code error}, the exception class (CLASS), the masked
   * message and the top application frame (FRAME), the inputs of the error fingerprint (C-131).
   */
  public TransactionRecord.Builder withError(
      TransactionRecord.Builder builder, String exceptionClass, String message, String topFrame) {
    return builder
        .setError(true)
        .setHttpStatus(builder.getKind() == TransactionKind.HTTP ? 500 : 0)
        .setErrorClassId(id(DictionaryKind.CLASS, exceptionClass))
        .setErrorMessage(message)
        .setErrorTopFrameId(id(DictionaryKind.FRAME, topFrame));
  }

  /**
   * {@code builder} as run on a virtual thread ({@code virtual_thread}, C-69 bit 1; CPU unmeasured,
   * D-23).
   */
  public static TransactionRecord.Builder onVirtualThread(TransactionRecord.Builder builder) {
    return builder
        .setVirtualThread(true)
        .setFlags(builder.getFlags() | TransactionFlags.VIRTUAL_THREAD)
        .clearCpuTimeUs();
  }

  // --- profiles (profile_step) ---

  /**
   * The profile of {@code tx} with {@code steps}: same {@code tx_id}, start and kind (C-138), not
   * truncated, {@code total_steps} = the number of steps.
   */
  public TransactionProfile.Builder profile(TransactionRecord tx, List<ProfileStep> steps) {
    return TransactionProfile.newBuilder()
        .setTxId(tx.getTxId())
        .setStartTsMs(tx.getStartTsMs())
        .setKind(tx.getKind())
        .addAllSteps(steps)
        .setTruncated(false)
        .setTotalSteps(steps.size());
  }

  /**
   * A DBC step: connection wait on {@code pool} (POOL) of {@code dbTarget} (DB_TARGET), C-143 (4).
   */
  public ProfileStep dbcStep(
      int seq, int startOffsetUs, int elapsedUs, String pool, String dbTarget) {
    return ProfileStep.newBuilder()
        .setSeq(seq)
        .setParentSeq(0)
        .setType(StepType.DBC)
        .setStartOffsetUs(startOffsetUs)
        .setElapsedUs(elapsedUs)
        .setNameId(id(DictionaryKind.POOL, pool))
        .setTargetId(id(DictionaryKind.DB_TARGET, dbTarget))
        .setRepeatCount(1)
        .build();
  }

  /**
   * A SQL step: normalized SQL (SQL_TEXT), target (DB_TARGET), exec + fetch, rows, masked binds.
   */
  public ProfileStep sqlStep(
      int seq,
      int parentSeq,
      int startOffsetUs,
      int elapsedUs,
      int fetchUs,
      String sql,
      String dbTarget,
      int rows) {
    return ProfileStep.newBuilder()
        .setSeq(seq)
        .setParentSeq(parentSeq)
        .setType(StepType.SQL)
        .setStartOffsetUs(startOffsetUs)
        .setElapsedUs(elapsedUs)
        .setFetchUs(fetchUs)
        .setNameId(id(DictionaryKind.SQL_TEXT, sql))
        .setTargetId(id(DictionaryKind.DB_TARGET, dbTarget))
        .setRows(rows)
        .setDetail("[Long]")
        .setBatchSize(0)
        .setRepeatCount(1)
        .build();
  }

  /** An outgoing HTTP call to {@code host} (HOST, {@code scheme://host:port}, C-142). */
  public ProfileStep httpCallStep(
      int seq,
      int parentSeq,
      int startOffsetUs,
      int elapsedUs,
      String host,
      int status,
      FailureKind failure) {
    return ProfileStep.newBuilder()
        .setSeq(seq)
        .setParentSeq(parentSeq)
        .setType(StepType.HTTP_CALL)
        .setStartOffsetUs(startOffsetUs)
        .setElapsedUs(elapsedUs)
        .setNameId(id(DictionaryKind.HOST, host))
        .setTargetId(id(DictionaryKind.HOST, host))
        .setRows(512)
        .setStatus(status)
        .setFailure(failure)
        .setError(failure != FailureKind.FAILURE_NONE)
        .setDetail("/api/stock")
        .setRepeatCount(1)
        .build();
  }

  /** A METHOD step of {@code methodName} (METHOD_NAME, {@code Class#method}). */
  public ProfileStep methodStep(
      int seq, int parentSeq, int startOffsetUs, int elapsedUs, String methodName) {
    return ProfileStep.newBuilder()
        .setSeq(seq)
        .setParentSeq(parentSeq)
        .setType(StepType.METHOD)
        .setStartOffsetUs(startOffsetUs)
        .setElapsedUs(elapsedUs)
        .setNameId(id(DictionaryKind.METHOD_NAME, methodName))
        .setRepeatCount(1)
        .build();
  }

  /** An ERROR step: exception class (CLASS) and up to 30 frames (FRAME). */
  public ProfileStep errorStep(
      int seq, int parentSeq, int startOffsetUs, String exceptionClass, List<String> frames) {
    ProfileStep.Builder b =
        ProfileStep.newBuilder()
            .setSeq(seq)
            .setParentSeq(parentSeq)
            .setType(StepType.ERROR)
            .setStartOffsetUs(startOffsetUs)
            .setNameId(id(DictionaryKind.CLASS, exceptionClass))
            .setError(true)
            .setRepeatCount(1);
    frames.forEach(f -> b.addFrameIds(id(DictionaryKind.FRAME, f)));
    return b.build();
  }

  /** A STACK step (slow-stack sample) with up to 64 frames (FRAME). */
  public ProfileStep stackStep(int seq, int startOffsetUs, List<String> frames) {
    ProfileStep.Builder b =
        ProfileStep.newBuilder()
            .setSeq(seq)
            .setParentSeq(0)
            .setType(StepType.STACK)
            .setStartOffsetUs(startOffsetUs)
            .setRepeatCount(1);
    frames.forEach(f -> b.addFrameIds(id(DictionaryKind.FRAME, f)));
    return b.build();
  }

  /**
   * A SUMMARY step as encoded by the agent (ADR-0010 C-141, proto/README.md): {@code status} = the
   * summarized step type, {@code repeat_count} = step count, {@code elapsed_us} = total, {@code
   * fetch_us} = longest, {@code rows} = failures.
   */
  public ProfileStep summaryStep(
      int seq,
      StepType summarized,
      DictionaryKind nameKind,
      String name,
      int count,
      int totalUs,
      int maxUs,
      int failures) {
    return ProfileStep.newBuilder()
        .setSeq(seq)
        .setParentSeq(0)
        .setType(StepType.SUMMARY)
        .setElapsedUs(totalUs)
        .setFetchUs(maxUs)
        .setNameId(id(nameKind, name))
        .setRows(failures)
        .setStatus(summarized.getNumber())
        .setError(failures > 0)
        .setRepeatCount(count)
        .build();
  }

  // --- metrics ---

  /** A series of a metric batch (DSN-11-04); {@code name} in OpenMetrics form with labels. */
  public static MetricSeries series(int index, String name, MetricKind kind) {
    return MetricSeries.newBuilder().setIndex(index).setName(name).setKind(kind).build();
  }

  /**
   * A sample of series {@code index} at {@code tsMs} (put it on the 5 s grid with {@link #grid5s}).
   */
  public static MetricSample sample(int index, long tsMs, double value, double delta) {
    return MetricSample.newBuilder()
        .setSeriesIndex(index)
        .setTsMs(tsMs)
        .setValue(value)
        .setDelta(delta)
        .build();
  }

  /** {@code ms} rounded down to the 5 s metric grid. */
  public static long grid5s(long ms) {
    return Math.floorDiv(ms, 5_000L) * 5_000L;
  }

  // --- statistics (sql_stat_raw, ext_stat_raw) ---

  /** {@code ms} rounded down to the 10 s statistics window. */
  public static long window10s(long ms) {
    return Math.floorDiv(ms, 10_000L) * 10_000L;
  }

  /**
   * A SQL statistic: {@code count} executions totalling {@code sumUs} (exec + fetch), the slowest
   * {@code maxUs}, 15 latency buckets with every execution at the mean, and {@code sum_exec_us} /
   * {@code sum_fetch_us} present (90 % / 10 %); clear them for the C-143 (2) cases.
   */
  public SqlStat.Builder sqlStat(
      String sql, String dbTarget, TransactionKind kind, int count, long sumUs, int maxUs) {
    long fetchUs = sumUs / 10;
    return SqlStat.newBuilder()
        .setSqlId(id(DictionaryKind.SQL_TEXT, sql))
        .setDbId(id(DictionaryKind.DB_TARGET, dbTarget))
        .setKind(kind)
        .setCount(count)
        .setErrorCount(0)
        .setSumUs(sumUs)
        .setMaxUs(maxUs)
        .setFetchRows(count)
        .addAllBuckets(buckets(count, count == 0 ? 0 : sumUs / count))
        .setSumExecUs(sumUs - fetchUs)
        .setSumFetchUs(fetchUs);
  }

  /** An outgoing-call statistic of {@code host} (HOST), buckets at the mean. */
  public ExtStat.Builder extStat(
      String host, TransactionKind kind, int count, long sumUs, int maxUs) {
    return ExtStat.newBuilder()
        .setHostId(id(DictionaryKind.HOST, host))
        .setKind(kind)
        .setCount(count)
        .setErrorCount(0)
        .setTimeoutCount(0)
        .setConnectFailCount(0)
        .setSumUs(sumUs)
        .setMaxUs(maxUs)
        .addAllBuckets(buckets(count, count == 0 ? 0 : sumUs / count));
  }

  /** A window counter (D-19, D-20), stored as {@code argos_agent_*} metric rows. */
  public static CounterDelta counter(String name, long delta) {
    return CounterDelta.newBuilder().setName(name).setDelta(delta).build();
  }

  /**
   * 15 latency buckets (13 §2, {@link LatencyBuckets}) with {@code count} executions of {@code
   * eachUs}.
   */
  public static List<Integer> buckets(int count, long eachUs) {
    List<Integer> buckets = new ArrayList<>(LatencyBuckets.COUNT);
    int index = LatencyBuckets.indexOfMicros(eachUs);
    for (int i = 0; i < LatencyBuckets.COUNT; i++) {
      buckets.add(i == index ? count : 0);
    }
    return buckets;
  }

  // --- active snapshot ---

  /** The in-flight view of {@code tx} at {@code nowMs} (agent clock) in {@code state}. */
  public ActiveTransaction.Builder active(TransactionRecord tx, ActiveState state, long nowMs) {
    return ActiveTransaction.newBuilder()
        .setTxId(tx.getTxId())
        .setServiceId(tx.getServiceId())
        .setKind(tx.getKind())
        .setStartTsMs(tx.getStartTsMs())
        .setElapsedMs((int) Math.max(0, nowMs - tx.getStartTsMs()))
        .setState(state)
        .setStateNameId(tx.getSqlIdsCount() > 0 ? tx.getSqlIds(0) : 0)
        .setThreadNameId(tx.getThreadNameId())
        .setThreadId(42)
        .setVirtualThread(tx.getVirtualThread())
        .setUserId(tx.getUserId())
        .setRemoteIp(tx.getRemoteIp());
  }

  // --- ids ---

  private synchronized long nonZeroRandom() {
    long value;
    do {
      value = random.nextLong();
    } while (value == 0);
    return value;
  }

  /** 16 bytes; bytes 0-3 are the Unix seconds of the start (13 §2 Argos traces). */
  private synchronized ByteString traceId(long startMs) {
    ByteBuffer buffer = ByteBuffer.allocate(16);
    buffer.putInt((int) (startMs / 1000));
    buffer.putInt(random.nextInt());
    buffer.putLong(random.nextLong());
    return ByteString.copyFrom(buffer.array());
  }
}
