/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.load;

import com.google.protobuf.MessageLite;
import io.argos.load.LoadCounters.Counter;
import io.argos.proto.v1.Ack;
import io.argos.proto.v1.AckStatus;
import io.argos.proto.v1.ExtStat;
import io.argos.proto.v1.FailureKind;
import io.argos.proto.v1.MetricSample;
import io.argos.proto.v1.ProfileStep;
import io.argos.proto.v1.SqlStat;
import io.argos.proto.v1.TransactionBatch;
import io.argos.proto.v1.TransactionKind;
import io.argos.proto.v1.TransactionProfile;
import io.argos.proto.v1.TransactionRecord;
import io.argos.server.testing.fakeagent.BatchKind;
import io.argos.server.testing.fakeagent.CallResult;
import io.argos.server.testing.fakeagent.FakeAgent;
import io.argos.server.testing.fakeagent.HelloResult;
import io.argos.server.testing.fakeagent.Records;
import io.grpc.Status;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.SplittableRandom;
import java.util.concurrent.TimeUnit;

/**
 * One fake agent instance of the load (18 §8): every second it builds the transactions of its share
 * of the rate (Zipf services, log-normal elapsed with 1 % outliers, 1 % errors), the captured
 * profiles with their steps, holds back the late arrivals, sends a transaction batch twice now and
 * then (same seq), and at the 10 s window and 5 s grid its statistics and metrics. Every batch is
 * retried like an agent would (same seq; a session the server ended is opened again) and what the
 * server acknowledged is counted in {@link LoadCounters}.
 *
 * <p>Called by one scheduler thread at a time ({@code scheduleAtFixedRate} never overlaps runs of
 * one task); {@link #flushLate} runs after the scheduler stopped.
 */
final class SimInstance {

  private static final int MAX_RETRIES = 4;
  private static final int MAX_TX_PER_BATCH = 5_000;
  private static final long HEARTBEAT_NANOS = TimeUnit.SECONDS.toNanos(10);

  /**
   * Ids remembered against a seq wrap-around: start times lie within the last 31 s, and an instance
   * makes at most a few thousand transactions in that time.
   */
  private static final int RECENT_IDS = 16_384;

  private final java.util.LinkedHashSet<Long> recentIds = new java.util.LinkedHashSet<>();

  private final int index;
  private final LoadApp app;
  private final FakeAgent agent;
  private final Records records;
  private final Workload workload;
  private final LoadOptions options;
  private final LoadCounters counters;
  private final SplittableRandom random;
  private final double txPerSecond;
  private final int stepsPerProfile;
  private final PriorityQueue<Held> late =
      new PriorityQueue<>(Comparator.comparingLong(Held::releaseNanos));

  private long lastTickNanos;
  private double carry;
  private long lastStatWindow = Long.MIN_VALUE;
  private long lastMetricGrid = Long.MIN_VALUE;
  private long lastHeartbeatNanos;
  private long ticks;

  private record Held(TransactionRecord tx, long releaseNanos) {}

  SimInstance(
      int index,
      LoadApp app,
      FakeAgent agent,
      Workload workload,
      LoadOptions options,
      LoadCounters counters,
      long seed) {
    this.index = index;
    this.app = app;
    this.agent = agent;
    this.records = agent.records();
    this.workload = workload;
    this.options = options;
    this.counters = counters;
    this.random = new SplittableRandom(seed);
    this.txPerSecond = options.tps() / options.instances();
    this.stepsPerProfile = options.stepsPerProfile();
  }

  int index() {
    return index;
  }

  LoadApp app() {
    return app;
  }

  FakeAgent agent() {
    return agent;
  }

  /** Opens the session; false when the server did not accept the Hello. */
  boolean connect() {
    HelloResult hello = agent.connect();
    lastHeartbeatNanos = System.nanoTime();
    lastTickNanos = System.nanoTime();
    if (!hello.accepted()) {
      counters.failure(agent + " Hello not accepted: " + hello);
      return false;
    }
    return true;
  }

  /** One second of load (the scheduler's task). Never throws. */
  void tick() {
    try {
      runTick();
    } catch (RuntimeException e) {
      counters.failure(agent + " tick failed: " + e);
    }
  }

  private void runTick() {
    long nowNanos = System.nanoTime();
    double elapsedS = (nowNanos - lastTickNanos) / 1e9;
    lastTickNanos = nowNanos;
    ticks++;
    double due = carry + txPerSecond * Math.min(elapsedS, 5.0);
    int n = (int) Math.floor(due);
    carry = due - n;
    if (nowNanos - lastHeartbeatNanos >= HEARTBEAT_NANOS) {
      lastHeartbeatNanos = nowNanos;
      try {
        agent.heartbeat();
      } catch (IllegalStateException e) {
        reconnect();
      }
    }

    long nowMs = records.clock().nowMs();
    List<TransactionRecord> batch = new ArrayList<>(n + 8);
    List<TransactionProfile> profiles = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      TransactionRecord tx = transaction(nowMs);
      if (tx.getProfileCaptured()) {
        profiles.add(records.profile(tx, steps(tx)).build());
      }
      if (random.nextDouble() < options.lateArrival()) {
        late.add(new Held(tx, nowNanos + lateDelayNanos()));
      } else {
        batch.add(tx);
      }
    }
    while (!late.isEmpty() && late.peek().releaseNanos() <= nowNanos) {
      batch.add(late.poll().tx());
      counters.add(Counter.TX_LATE, 1);
    }
    preSendDictionary();
    sendTransactions(batch, true);
    sendProfiles(profiles);

    long window = Records.window10s(nowMs) - 10_000;
    if (window != lastStatWindow && nowMs - Records.window10s(nowMs) >= 500) {
      lastStatWindow = window;
      sendStats(window);
    }
    long grid = Records.grid5s(nowMs) - 5_000;
    if (grid != lastMetricGrid) {
      lastMetricGrid = grid;
      sendMetrics(grid);
    }
  }

  /** Sends every held-back transaction now (end of the load). */
  void flushLate() {
    List<TransactionRecord> rest = new ArrayList<>(late.size());
    while (!late.isEmpty()) {
      rest.add(late.poll().tx());
    }
    counters.add(Counter.TX_LATE, rest.size());
    sendTransactions(rest, false);
  }

  /** Transactions still held back. */
  int lateHeld() {
    return late.size();
  }

  /** One transaction started before {@code nowMs} (package-visible for the tx_id unit test). */
  TransactionRecord transaction(long nowMs) {
    String service = workload.services().get(workload.serviceRanks().sample(random));
    long elapsedUs = elapsedUs();
    long startMs = nowMs - elapsedUs / 1_000 - random.nextInt(1_000);
    TransactionRecord.Builder b = records.http(service, startMs, elapsedUs);
    // tx_id = start ms << 23 | node << 11 | seq (11 bits): a real agent's start times only grow,
    // but these are back-dated at random, so seq k and k + 2,048 can meet in the same ms. Such an
    // id is drawn again (the next seq), so that uniqExact(tx_id) = sent holds (D-13).
    while (!recentIds.add(b.getTxId())) {
      b = records.http(service, startMs, elapsedUs);
    }
    if (recentIds.size() > RECENT_IDS) {
      recentIds.remove(recentIds.iterator().next());
    }
    if (random.nextDouble() < options.errorRate()) {
      String cls = Workload.ERROR_CLASSES.get(random.nextInt(Workload.ERROR_CLASSES.size()));
      records.withError(b, cls, "load error #", "io.argos.load.Shop.handle(Shop.java)");
      counters.add(Counter.TX_ERRORS, 1);
    }
    if (stepsPerProfile > 0 && random.nextDouble() < options.profileRate()) {
      b.setProfileCaptured(true);
    }
    return b.build();
  }

  /** Log-normal, median 80 ms, sigma 1.0; 1 % outliers between 2 and 30 s (18 §9). */
  private long elapsedUs() {
    if (random.nextDouble() < 0.01) {
      return 2_000_000L + (long) (random.nextDouble() * 28_000_000L);
    }
    double ms = 80.0 * Math.exp(random.nextGaussian());
    return Math.max(100L, Math.min(600_000_000L, Math.round(ms * 1_000)));
  }

  private long lateDelayNanos() {
    long min = options.lateMin().toNanos();
    long max = options.lateMax().toNanos();
    return min + (max > min ? (long) (random.nextDouble() * (max - min)) : 0);
  }

  private List<ProfileStep> steps(TransactionRecord tx) {
    List<ProfileStep> steps = new ArrayList<>(stepsPerProfile);
    List<String> sql = workload.sql();
    List<String> hosts = workload.hosts();
    int salt = (int) (tx.getTxId() & 0xffff);
    for (int s = 1; s <= stepsPerProfile; s++) {
      int offset = Math.min(s * 200, 60_000_000);
      ProfileStep step =
          switch (s % 4) {
            case 1 -> records.dbcStep(s, offset, 150, Workload.POOL, Workload.DB);
            case 2 ->
                records.sqlStep(
                    s, 0, offset, 900, 100, sql.get((salt + s) % sql.size()), Workload.DB, 1);
            case 3 ->
                records.httpCallStep(
                    s,
                    0,
                    offset,
                    2_000,
                    hosts.get((salt + s) % hosts.size()),
                    200,
                    FailureKind.FAILURE_NONE);
            default ->
                records.methodStep(
                    s, 0, offset, 300, Workload.METHODS.get(s % Workload.METHODS.size()));
          };
      steps.add(step);
    }
    return steps;
  }

  private void preSendDictionary() {
    try {
      agent.sendNewDictionaryEntries();
    } catch (IllegalStateException e) {
      reconnect();
    }
  }

  private void sendTransactions(List<TransactionRecord> txs, boolean mayDuplicate) {
    for (int from = 0; from < txs.size(); from += MAX_TX_PER_BATCH) {
      List<TransactionRecord> part =
          txs.subList(from, Math.min(txs.size(), from + MAX_TX_PER_BATCH));
      TransactionBatch batch = agent.batches().transactions(part);
      long accepted = deliver(BatchKind.TRANSACTIONS, batch, part.size());
      if (accepted >= 0) {
        counters.addAccepted(app.index(), accepted);
        counters.add(Counter.TX_DROPPED, part.size() - accepted);
        if (mayDuplicate && random.nextDouble() < options.duplicate()) {
          counters.add(Counter.DUPLICATE_BATCHES, 1);
          CallResult again = call(BatchKind.TRANSACTIONS, batch);
          if (again.ok() && again.ackStatus() == AckStatus.ACK_DUPLICATE) {
            counters.add(Counter.DUPLICATE_ACKS, 1);
          } else {
            counters.failure(agent + " resent batch not recognized as duplicate: " + again);
          }
        }
      } else {
        counters.add(Counter.TX_UNACKNOWLEDGED, part.size());
      }
    }
  }

  private void sendProfiles(List<TransactionProfile> profiles) {
    if (profiles.isEmpty()) {
      return;
    }
    long accepted =
        deliver(BatchKind.PROFILES, agent.batches().profiles(profiles), profiles.size());
    if (accepted > 0) {
      counters.add(Counter.PROFILES, accepted);
      counters.add(Counter.STEPS, accepted * stepsPerProfile);
    }
  }

  private void sendStats(long windowStartMs) {
    List<SqlStat> sql = new ArrayList<>(options.sqlRowsPerWindow());
    List<String> texts = workload.sql();
    for (int q = 0; q < options.sqlRowsPerWindow(); q++) {
      int count = 1 + random.nextInt(40);
      sql.add(
          records
              .sqlStat(
                  texts.get(q % texts.size()),
                  Workload.DB,
                  TransactionKind.HTTP,
                  count,
                  count * 2_000L,
                  9_000)
              .build());
    }
    List<ExtStat> ext = new ArrayList<>(options.extRowsPerWindow());
    List<String> hosts = workload.hosts();
    for (int h = 0; h < options.extRowsPerWindow(); h++) {
      int count = 1 + random.nextInt(20);
      ext.add(
          records
              .extStat(
                  hosts.get(h % hosts.size()), TransactionKind.HTTP, count, count * 5_000L, 20_000)
              .build());
    }
    preSendDictionary();
    MessageLite batch =
        agent
            .batches()
            .stats(
                windowStartMs,
                sql,
                ext,
                List.of(
                    Records.counter("transactions_dropped_total", 0),
                    Records.counter("steps_truncated_total", 0)));
    long accepted = deliver(BatchKind.STATS, batch, sql.size() + ext.size());
    if (accepted >= 0) {
      // Dropped stats rows (quota) come off the end: the outgoing-call rows first.
      long extAccepted = Math.max(0, accepted - sql.size());
      counters.add(Counter.STAT_SQL, accepted - extAccepted);
      counters.add(Counter.STAT_EXT, extAccepted);
    }
  }

  private void sendMetrics(long gridMs) {
    List<MetricSample> samples = new ArrayList<>(workload.series().size());
    for (int m = 0; m < workload.series().size(); m++) {
      samples.add(Records.sample(m, gridMs, m * 10.0 + ticks % 100, 0));
    }
    long accepted =
        deliver(
            BatchKind.METRICS, agent.batches().metrics(workload.series(), samples), samples.size());
    if (accepted > 0) {
      counters.add(Counter.METRIC_SAMPLES, accepted);
    }
  }

  /**
   * Sends {@code batch} until the server acknowledges it (same seq on every retry) and returns the
   * items it kept ({@code items - dropped_items}); -1 when every attempt failed.
   */
  private long deliver(BatchKind kind, MessageLite batch, int items) {
    for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
      CallResult r = call(kind, batch);
      if (r.ok()) {
        Ack ack = r.ack();
        if (ack.hasThrottle()) {
          counters.add(Counter.THROTTLES, 1);
        }
        if (ack.getStatus() == AckStatus.ACK_DUPLICATE) {
          // A retry whose first attempt reached the WAL after all.
          return attempt > 0 ? items : 0;
        }
        return (long) items - ack.getDroppedItems();
      }
      counters.add(Counter.CALLS_FAILED, 1);
      Status.Code code = r.code();
      switch (code) {
        case UNAUTHENTICATED, FAILED_PRECONDITION -> reconnect();
        case UNAVAILABLE, DEADLINE_EXCEEDED, RESOURCE_EXHAUSTED, ABORTED, CANCELLED, INTERNAL -> {
          // transient: back off and send the same batch again
        }
        default -> {
          counters.failure(agent + " " + kind + " refused: " + r);
          return -1;
        }
      }
      if (attempt == MAX_RETRIES) {
        counters.failure(
            agent + " " + kind + " failed after " + (MAX_RETRIES + 1) + " attempts: " + r);
        return -1;
      }
      counters.add(Counter.RETRIES, 1);
      long backoff = r.retryAfterMs().orElse(250L << Math.min(attempt, 4));
      sleep(Math.min(backoff, 10_000));
    }
    return -1;
  }

  private CallResult call(BatchKind kind, MessageLite batch) {
    counters.add(Counter.CALLS, 1);
    return agent.send(kind, batch, 0, FakeAgent.CallSpec.defaults());
  }

  private void reconnect() {
    HelloResult hello = agent.connect();
    if (hello.accepted()) {
      counters.add(Counter.RECONNECTS, 1);
    } else {
      counters.failure(agent + " reconnect not accepted: " + hello);
    }
  }

  private static void sleep(long millis) {
    try {
      TimeUnit.MILLISECONDS.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
