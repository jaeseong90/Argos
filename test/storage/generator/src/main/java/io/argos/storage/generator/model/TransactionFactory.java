/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.storage.generator.model;

import io.argos.storage.generator.GeneratorConfig;
import io.argos.storage.generator.RunRange;
import io.argos.storage.generator.model.Topology.App;
import io.argos.storage.generator.model.Topology.ExtHost;
import io.argos.storage.generator.model.Topology.Instance;
import io.argos.storage.generator.model.Topology.Service;
import io.argos.storage.generator.model.Topology.SqlStatement;
import io.argos.storage.generator.model.Transaction.ExtCall;
import io.argos.storage.generator.model.Transaction.SqlRun;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;

/**
 * Creates transactions with the distributions of docs/13 §15: log-normal response time (median 80
 * ms, sigma 1.0, per-service shifts with traffic-weighted mean 0) plus 1 % outliers (2-30 s), 1 %
 * errors over 50 error groups, sql_ids with mean 3 drawn from the service's usual statements (Zipf
 * over the app's SQL), N+1 loops, outgoing calls (external hosts and one internal call to another
 * app that produces a child transaction of the same trace), user_id 30 % (hashed), masked client
 * addresses (Zipf over 10,000), request_id 50 %, and the agent sampler of docs/10 §9.2 (errors,
 * slow, 10 % of the rest).
 */
public final class TransactionFactory {

  /** Share of outliers cut by the max-duration watchdog (timed_out, F-A34). */
  static final double MAX_DURATION_CUT_SHARE = 0.02;

  /** Share of HTTP root transactions that continue an external incoming trace. */
  static final double EXTERNAL_TRACE_SHARE = 0.03;

  /** Share of transactions with an N+1 loop (one statement repeated 10-50 times). */
  static final double N_PLUS_ONE_SHARE = 0.05;

  /** Share of HTTP transactions that also run a rarely used statement of the app. */
  static final double RARE_SQL_SHARE = 0.05;

  /** Share of batch transactions that are large chunks (profile summary mode). */
  static final double BIG_CHUNK_SHARE = 0.005;

  private final GeneratorConfig config;
  private final Topology topology;
  private final double sqlPoissonMean;
  private double normalCaptureRate;

  public TransactionFactory(Topology topology) {
    this.topology = topology;
    this.config = topology.config;
    // n = 0 with probability 0.1, otherwise 1 + Poisson(lambda): mean 0.9 * (1 + lambda).
    this.sqlPoissonMean = Math.max(0, config.sqlIdsMean / 0.9 - 1);
    this.normalCaptureRate = config.profileNormalRate;
  }

  /**
   * Sets the capture rate of normal transactions (neither failed nor slow) for the transactions
   * created from now on; {@code profile-normal-rate} until called (profile-step-rate control).
   */
  public void setNormalCaptureRate(double rate) {
    this.normalCaptureRate = Math.max(0, Math.min(1, rate));
  }

  /** The capture rate of normal transactions in use. */
  public double normalCaptureRate() {
    return normalCaptureRate;
  }

  /** A root HTTP transaction of a service, or a batch transaction when {@code service.batch}. */
  public Transaction root(
      Instance instance, Service service, long startTrueMs, SplittableRandom random) {
    int kind = service.batch ? Transaction.BATCH : Transaction.HTTP;
    Transaction tx = new Transaction(instance, service, kind, startTrueMs);
    tx.elapsedMs = elapsed(service, random, tx);
    if (kind == Transaction.HTTP) {
      startHttpRoot(tx, random);
    } else {
      tx.httpStatus = 0;
    }
    plan(tx, random, true);
    finish(tx, random);
    if (kind == Transaction.BATCH) {
      tx.httpStatus = tx.error ? 1 : 0;
    }
    return tx;
  }

  /** The child transaction served by the callee app of an internal call. */
  Transaction child(
      Transaction parent, ExtCall call, Instance calleeInstance, SplittableRandom random) {
    App callee = calleeInstance.app;
    Service service = callee.httpServices.get(callee.httpServiceWeights.sample(random));
    long start = parent.startTrueMs + call.startOffsetMs + 1;
    Transaction tx = new Transaction(calleeInstance, service, Transaction.HTTP, start);
    tx.elapsedMs = Math.max(1, call.durationMs - 2);
    System.arraycopy(parent.traceId, 0, tx.traceId, 0, 16);
    tx.incomingTrace = true;
    tx.argosTrace = parent.argosTrace;
    tx.parentSpanId = call.spanId;
    tx.callerAppId = parent.instance.app.id;
    tx.remoteIpv4 = parent.instance.internalIpv4;
    tx.requestId = parent.requestId;
    tx.spanId = nonZero(random.nextLong());
    plan(tx, random, false);
    finish(tx, random);
    return tx;
  }

  private void startHttpRoot(Transaction tx, SplittableRandom random) {
    tx.spanId = nonZero(random.nextLong());
    if (random.nextDouble() < EXTERNAL_TRACE_SHARE) {
      random.nextBytes(tx.traceId);
      tx.incomingTrace = true;
      tx.parentSpanId = nonZero(random.nextLong());
    } else {
      // Argos-generated trace id: bytes 0..3 are the Unix seconds (agent clock), big-endian.
      tx.argosTrace = true;
      random.nextBytes(tx.traceId);
      long seconds = Math.floorDiv(tx.tsAgentMs, 1000L);
      tx.traceId[0] = (byte) (seconds >>> 24);
      tx.traceId[1] = (byte) (seconds >>> 16);
      tx.traceId[2] = (byte) (seconds >>> 8);
      tx.traceId[3] = (byte) seconds;
    }
    tx.remoteIpv4 = topology.remoteIps[topology.remoteIpWeights.sample(random)];
    if (random.nextDouble() < config.userIdShare) {
      int user = topology.userWeights.sample(random);
      tx.userId = hex16(Hash.of(tx.instance.app.userSalt, user));
    }
    if (random.nextDouble() < config.requestIdShare) {
      tx.requestId = uuid(random);
    }
  }

  private int elapsed(Service service, SplittableRandom random, Transaction tx) {
    if (random.nextDouble() < config.outlierRate) {
      if (random.nextDouble() < MAX_DURATION_CUT_SHARE) {
        tx.timedOut = true;
        return config.outlierMaxMs;
      }
      return config.outlierMinMs + random.nextInt(config.outlierMaxMs - config.outlierMinMs + 1);
    }
    double shift = service.batch ? 0 : service.logShift;
    double sigma = service.batch ? config.latencySigma : topology.noiseSigma;
    double ms = config.latencyMedianMs * Math.exp(shift + sigma * random.nextGaussian());
    return (int) Math.max(1, Math.min(config.outlierMaxMs, Math.round(ms)));
  }

  /** SQL runs, outgoing calls and the time layout inside the transaction. */
  private void plan(Transaction tx, SplittableRandom random, boolean root) {
    App app = tx.instance.app;
    boolean bigChunk = false;
    if (tx.kind == Transaction.BATCH) {
      bigChunk = random.nextDouble() < BIG_CHUNK_SHARE;
      planBatchSql(tx, random, bigChunk);
    } else {
      planHttpSql(tx, random);
    }
    // Outgoing calls.
    if (tx.kind == Transaction.HTTP) {
      if (!app.externalHosts.isEmpty() && random.nextDouble() < config.extCallRate) {
        int calls = 1 + (random.nextDouble() < 0.5 ? 0 : random.nextDouble() < 0.5 ? 1 : 2);
        for (int c = 0; c < calls; c++) {
          ExtHost host = app.externalHosts.get(app.externalHostWeights.sample(random));
          tx.extCalls.add(new ExtCall(host, random.nextInt(host.pathIds.length)));
        }
      }
      if (root && app.calleeHost != null && random.nextDouble() < config.internalCallRate) {
        ExtHost host = app.calleeHost;
        tx.extCalls.add(new ExtCall(host, random.nextInt(host.pathIds.length)));
      }
    } else if (!app.externalHosts.isEmpty() && random.nextDouble() < 0.1) {
      ExtHost host = app.externalHosts.get(app.externalHostWeights.sample(random));
      tx.extCalls.add(new ExtCall(host, random.nextInt(host.pathIds.length)));
    }
    layout(tx, random);
  }

  private void planHttpSql(Transaction tx, SplittableRandom random) {
    Service service = tx.service;
    App app = tx.instance.app;
    int[] active = tx.instance.activeSqls;
    int n = 0;
    if (random.nextDouble() >= 0.1) {
      n = 1 + poisson(random, sqlPoissonMean);
    }
    n = Math.min(n, active != null ? active.length : distinctPreference(service));
    if (n == 0) {
      return;
    }
    List<SqlStatement> chosen;
    if (active != null) {
      // N-06 load: distinct statements drawn uniformly from the instance's active set.
      chosen = uniformActive(app, active, n, random);
    } else {
      chosen = new ArrayList<>();
      Set<Integer> seen = new LinkedHashSet<>();
      for (int idx : service.sqlPreference) {
        if (chosen.size() == n) {
          break;
        }
        if (seen.add(idx)) {
          chosen.add(app.sqls.get(idx));
        }
      }
      if (random.nextDouble() < RARE_SQL_SHARE) {
        // A rarely used statement of the app (long tail of the 2,000 SQL).
        int rare = app.sqlWeights.sample(random);
        if (seen.add(rare)) {
          chosen.set(random.nextInt(chosen.size()), app.sqls.get(rare));
        }
      }
    }
    int loop = random.nextDouble() < N_PLUS_ONE_SHARE ? random.nextInt(chosen.size()) : -1;
    for (int i = 0; i < chosen.size(); i++) {
      SqlStatement sql = chosen.get(i);
      if (i == loop) {
        tx.sqlRuns.add(new SqlRun(sql, 10 + random.nextInt(41)));
        continue;
      }
      double u = random.nextDouble();
      int count = u < 0.80 ? 1 : u < 0.95 ? 2 : 3 + random.nextInt(3);
      if (count == 1 || random.nextBoolean()) {
        tx.sqlRuns.add(new SqlRun(sql, count));
      } else {
        // Same statement executed again later (not folded: separate steps).
        tx.sqlRuns.add(new SqlRun(sql, 1));
        tx.sqlRuns.add(new SqlRun(sql, count - 1));
      }
    }
    // Repeated statements that are not consecutive are moved apart.
    if (tx.sqlRuns.size() > 2) {
      List<SqlRun> runs = new ArrayList<>(tx.sqlRuns);
      Collections.shuffle(runs, new java.util.Random(random.nextLong()));
      tx.sqlRuns.clear();
      tx.sqlRuns.addAll(runs);
    }
  }

  private void planBatchSql(Transaction tx, SplittableRandom random, boolean bigChunk) {
    Service service = tx.service;
    App app = tx.instance.app;
    int[] active = tx.instance.activeSqls;
    int distinct =
        Math.min(
            active != null ? active.length : distinctPreference(service), 3 + random.nextInt(6));
    List<SqlStatement> chosen;
    if (active != null) {
      chosen = uniformActive(app, active, distinct, random);
    } else {
      chosen = new ArrayList<>();
      Set<Integer> seen = new LinkedHashSet<>();
      for (int idx : service.sqlPreference) {
        if (chosen.size() == distinct) {
          break;
        }
        if (seen.add(idx)) {
          chosen.add(app.sqls.get(idx));
        }
      }
    }
    if (bigChunk) {
      // read item, process, write item: interleaved, so every execution is its own step.
      int items = 200 + random.nextInt(1301);
      int perItem = Math.min(chosen.size(), 2 + random.nextInt(2));
      for (int item = 0; item < items; item++) {
        for (int k = 0; k < perItem; k++) {
          tx.sqlRuns.add(new SqlRun(chosen.get(k), 1));
        }
      }
      return;
    }
    for (SqlStatement sql : chosen) {
      int count = 1 + random.nextInt(10);
      SqlRun run = new SqlRun(sql, count);
      if (sql.write && random.nextDouble() < 0.3) {
        run.batchSize = 50 + random.nextInt(451);
      }
      tx.sqlRuns.add(run);
    }
  }

  /** Durations and offsets of the SQL runs and calls within the elapsed time. */
  private void layout(Transaction tx, SplittableRandom random) {
    int e = tx.elapsedMs;
    int pre = (int) Math.floor(e * (0.01 + 0.04 * random.nextDouble()));
    int budget = e - pre;
    // Outgoing calls.
    int ext = 0;
    if (!tx.extCalls.isEmpty()) {
      int x = (int) Math.floor(budget * (0.2 + 0.5 * random.nextDouble()));
      double[] w = new double[tx.extCalls.size()];
      double sum = 0;
      for (int c = 0; c < w.length; c++) {
        w[c] = tx.extCalls.get(c).host.medianMs * Math.exp(0.8 * random.nextGaussian());
        sum += w[c];
      }
      for (int c = 0; c < w.length; c++) {
        ExtCall call = tx.extCalls.get(c);
        call.durationMs = (int) Math.max(1, Math.floor(x * w[c] / sum));
        ext += call.durationMs;
      }
    }
    // SQL runs.
    int sqlTotal = 0;
    if (!tx.sqlRuns.isEmpty()) {
      int s = (int) Math.floor(Math.max(0, budget - ext) * (0.2 + 0.6 * random.nextDouble()));
      double[] w = new double[tx.sqlRuns.size()];
      double sum = 0;
      for (int i = 0; i < w.length; i++) {
        SqlRun run = tx.sqlRuns.get(i);
        w[i] = run.count * run.sql.baseMs * (0.5 + random.nextDouble());
        sum += w[i];
      }
      for (int i = 0; i < w.length; i++) {
        SqlRun run = tx.sqlRuns.get(i);
        run.totalMs = (int) Math.floor(s * w[i] / sum);
        run.fetchMs =
            run.sql.write ? 0 : (int) Math.floor(run.totalMs * (0.05 + 0.25 * random.nextDouble()));
        run.execMs = run.totalMs - run.fetchMs;
        run.rows =
            run.sql.write
                ? run.count
                : (int)
                    Math.min(
                        1_000_000L,
                        Math.round(
                            run.count * run.sql.meanRows * Math.exp(0.5 * random.nextGaussian())));
        sqlTotal += run.totalMs;
      }
    }
    // Connection acquisition wait (F-A03): usually none.
    int dbc = 0;
    if (!tx.sqlRuns.isEmpty() && random.nextDouble() < 0.05) {
      int room = Math.max(0, (budget - ext - sqlTotal) / 2);
      dbc = (int) Math.min(room, Math.round(5 * Math.exp(random.nextGaussian())));
    }
    // A few transactions are dominated by their calls (elapsed too small for the minimum).
    int busy = pre + ext + sqlTotal + dbc;
    if (busy > tx.elapsedMs) {
      tx.elapsedMs = busy;
    }
    tx.preMs = pre;
    tx.dbcWaitMs = dbc;
    tx.extTimeMs = ext;
    // Sequential layout: pre, DBC, then SQL runs and calls interleaved with even gaps.
    int items = tx.sqlRuns.size() + tx.extCalls.size();
    int gap = items == 0 ? 0 : Math.max(0, (tx.elapsedMs - busy) / (items + 1));
    int cursor = pre;
    tx.dbcOffsetMs = cursor;
    cursor += dbc;
    int callSlot = tx.extCalls.isEmpty() ? -1 : random.nextInt(Math.max(1, tx.sqlRuns.size() + 1));
    int callIndex = 0;
    for (int i = 0; i <= tx.sqlRuns.size(); i++) {
      if (i == callSlot) {
        while (callIndex < tx.extCalls.size()) {
          ExtCall call = tx.extCalls.get(callIndex++);
          cursor += gap;
          call.startOffsetMs = cursor;
          cursor += call.durationMs;
        }
      }
      if (i < tx.sqlRuns.size()) {
        SqlRun run = tx.sqlRuns.get(i);
        cursor += gap;
        run.startOffsetMs = cursor;
        cursor += run.totalMs;
      }
    }
    while (callIndex < tx.extCalls.size()) {
      ExtCall call = tx.extCalls.get(callIndex++);
      cursor += gap;
      call.startOffsetMs = cursor;
      cursor += call.durationMs;
    }
  }

  /** Error, status, call outcomes, child transactions, CPU, flags and the sampler. */
  private void finish(Transaction tx, SplittableRandom random) {
    App app = tx.instance.app;
    tx.appVersionId = tx.instance.appVersionIdAt(tx.startTrueMs);
    tx.error = random.nextDouble() < config.errorRate;
    if (tx.error) {
      tx.errorGroup = app.errorGroups.get(app.errorGroupWeights.sample(random));
      if (tx.kind == Transaction.HTTP) {
        double u = random.nextDouble();
        tx.httpStatus = u < 0.8 ? 500 : u < 0.87 ? 502 : u < 0.94 ? 503 : 504;
      }
    } else if (tx.kind == Transaction.HTTP) {
      double u = random.nextDouble();
      tx.httpStatus =
          u < 0.92 ? 200 : u < 0.95 ? 201 : u < 0.97 ? 204 : u < 0.98 ? 302 : u < 0.99 ? 304 : 404;
    }
    // SQL totals; an SQL-caused error fails the last execution of one run.
    int count = 0;
    int time = 0;
    Set<Long> ids = new LinkedHashSet<>();
    for (SqlRun run : tx.sqlRuns) {
      count += run.count;
      time += run.totalMs;
      ids.add(run.sql.id);
    }
    if (tx.error && tx.errorGroup.cause() == Topology.Cause.SQL && !tx.sqlRuns.isEmpty()) {
      tx.sqlRuns.get(tx.sqlRuns.size() - 1).error = true;
    } else if (!tx.sqlRuns.isEmpty() && random.nextDouble() < 0.001) {
      tx.sqlRuns.get(random.nextInt(tx.sqlRuns.size())).error = true; // handled SQL error
    }
    tx.sqlCount = count;
    tx.sqlTimeMs = time;
    tx.sqlIds = new long[ids.size()];
    int k = 0;
    for (long id : ids) {
      tx.sqlIds[k++] = id;
    }
    // Call outcomes and child transactions.
    boolean extCaused = tx.error && tx.errorGroup.cause() == Topology.Cause.EXT;
    for (int c = 0; c < tx.extCalls.size(); c++) {
      ExtCall call = tx.extCalls.get(c);
      call.spanId = nonZero(random.nextLong());
      call.responseBytes =
          (int) Math.min(10_000_000L, Math.round(2048 * Math.exp(random.nextGaussian())));
      double u = random.nextDouble();
      if (extCaused && c == tx.extCalls.size() - 1) {
        call.timeout = true;
        call.status = 1;
      } else if (u < 0.002) {
        call.timeout = true;
        call.status = 1;
      } else if (u < 0.003) {
        call.connectFail = true;
        call.status = 2;
      } else if (u < 0.013) {
        call.error = true;
        call.status = 500 + random.nextInt(4);
      } else {
        call.status = 200;
      }
      if (call.host.internal && !call.connectFail && call.host.target.httpServiceWeights != null) {
        Instance callee =
            pickUpInstance(call.host.target, tx.startTrueMs + call.startOffsetMs, random);
        Transaction child = child(tx, call, callee, random);
        call.child = child;
        tx.children.add(child);
      }
    }
    // CPU and allocation (-1 when not measured: some JVMs, virtual threads, D-23).
    if (tx.instance.cpuMeasured && !app.virtualThreads) {
      tx.cpuTimeMs =
          (int)
              Math.min(
                  tx.elapsedMs, Math.round(tx.elapsedMs * (0.03 + 0.37 * random.nextDouble())));
      tx.allocBytes = Math.round(262_144 * Math.exp(random.nextGaussian()));
    } else {
      tx.cpuTimeMs = -1;
      tx.allocBytes = -1;
    }
    if (tx.kind == Transaction.HTTP) {
      tx.streaming = tx.service.httpMethod.equals("GET") && random.nextDouble() < 0.005;
      double d = random.nextDouble();
      tx.dispatchCount = d < 0.002 ? 2 : d < 0.02 ? 1 : 0;
    }
    if (app.virtualThreads && tx.kind == Transaction.HTTP) {
      tx.flags |= Transaction.FLAG_VIRTUAL_THREAD;
    }
    if (random.nextDouble() < 0.0001) {
      tx.flags |= Transaction.FLAG_LEAKED;
    }
    // Agent sampler (docs/10 §9.2): errors, slow transactions, normal-rate of the rest.
    tx.profileCaptured =
        tx.error
            || tx.elapsedMs >= config.slowThresholdMs
            || random.nextDouble() < normalCaptureRate;
  }

  /** An instance of {@code app} that is up at {@code trueMs} (rolling restart aware). */
  public static Instance pickUpInstance(App app, long trueMs, SplittableRandom random) {
    int n = app.instances.size();
    int start = random.nextInt(n);
    for (int i = 0; i < n; i++) {
      Instance candidate = app.instances.get((start + i) % n);
      if (candidate.upAt(trueMs)) {
        return candidate;
      }
    }
    return app.instances.get(start);
  }

  /** {@code n} distinct statements drawn uniformly from an instance's active set. */
  private static List<SqlStatement> uniformActive(
      App app, int[] active, int n, SplittableRandom random) {
    List<SqlStatement> chosen = new ArrayList<>(n);
    Set<Integer> seen = new LinkedHashSet<>();
    while (chosen.size() < n) {
      int idx = active[random.nextInt(active.length)];
      if (seen.add(idx)) {
        chosen.add(app.sqls.get(idx));
      }
    }
    return chosen;
  }

  /** Number of distinct statements a service prefers. */
  private static int distinctPreference(Service service) {
    Set<Integer> distinct = new LinkedHashSet<>();
    for (int idx : service.sqlPreference) {
      distinct.add(idx);
    }
    return distinct.size();
  }

  /** Snowflake tx_id (13 §2) from the agent clock, the instance node bits and a sequence. */
  public static long txId(long agentMs, int instanceId, long sequence) {
    return ((agentMs - RunRange.TX_EPOCH_MS) << 23)
        | ((instanceId & 0xFFFL) << 11)
        | (sequence & 0x7FFL);
  }

  static int poisson(SplittableRandom random, double mean) {
    if (mean <= 0) {
      return 0;
    }
    double limit = Math.exp(-mean);
    double p = random.nextDouble();
    int k = 0;
    while (p > limit && k < 1000) {
      p *= random.nextDouble();
      k++;
    }
    return k;
  }

  /** 16 lower-case hex digits (the hashed user_id format, 13 §3.1). */
  static String hex16(long v) {
    char[] c = new char[16];
    for (int i = 15; i >= 0; i--) {
      c[15 - i] = HEX[(int) ((v >>> (4 * i)) & 0xF)];
    }
    return new String(c);
  }

  private static final char[] HEX = "0123456789abcdef".toCharArray();

  static long nonZero(long v) {
    return v == 0 ? 1 : v;
  }

  static String uuid(SplittableRandom random) {
    long hi = random.nextLong();
    long lo = random.nextLong();
    hi = (hi & ~0xF000L) | 0x4000L;
    lo = (lo & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L;
    return new java.util.UUID(hi, lo).toString();
  }
}
