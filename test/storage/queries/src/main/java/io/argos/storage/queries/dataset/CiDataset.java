/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.storage.queries.dataset;

import io.argos.storage.clickhouse.ClickHouseHttp;
import io.argos.storage.queries.dataset.TxModel.ExtCall;
import io.argos.storage.queries.dataset.TxModel.SqlExec;
import io.argos.storage.queries.dataset.TxModel.Tx;
import io.argos.storage.schema.SchemaParameters;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * Deterministic small dataset for the CI consistency tests (T-ST-030..033) and the query smoke run,
 * loaded through the writer path of 13 §7.4: argos_writer, explicit columns, RowBinary, one {@code
 * insert_deduplication_token = 'argos:<table>:<block_id>'} per block (13 §5.1, C-2).
 *
 * <p>Besides the distributions of {@link TxModel} it contains the cases that the consistency checks
 * must survive (13 §15): late arrivals (0.5 % of the transactions arrive 1..30 minutes late, in a
 * later block), re-sent blocks with the same token (at least one per table), blocks that cross
 * midnight, clock offsets of +-2 s and +60 s, and distributed calls across applications. The SQL
 * and outgoing call statistics are the complete 10 s windows of the generated calls, attributed to
 * the window of the transaction start, so D-12 holds by construction.
 *
 * <p>This is the stand-in until the PoC generator (test/storage/generator) offers an API; it is not
 * a load generator (no target TPS, no replay).
 */
public final class CiDataset {

  /** xlog columns in insert order. */
  public static final String XLOG_COLUMNS =
      "app_id, env, instance_id, ts, tx_id, kind, trace_id, span_id, parent_span_id,"
          + " incoming_trace, caller_app_id, service_id, version_id, http_method, http_status,"
          + " elapsed_ms, error, error_class_id, error_group_id, apdex_t_ms, remote_ip, ip_mode,"
          + " user_id, user_id_mode, request_id, sql_count, sql_time_ms, dbc_time_ms, ext_count,"
          + " ext_time_ms, cpu_time_ms, alloc_bytes, thread_name_id, profile_captured, streaming,"
          + " timeout_flag, dispatch_count, late, flags, sql_ids";

  /** profile_step columns in insert order. */
  public static final String PROFILE_COLUMNS =
      "app_id, env, instance_id, tx_id, ts, kind, seq, parent_seq, type, start_offset_ms,"
          + " elapsed_ms, exec_ms, fetch_ms, name_id, target_id, rows, status, error,"
          + " repeat_count, batch_size, detail, detail_enc, stack";

  /** sql_stat_raw columns in insert order. */
  public static final String SQL_STAT_COLUMNS =
      "app_id, env, instance_id, ts, kind, sql_id, db_id, count, error_count, sum_ms,"
          + " sum_exec_ms, sum_fetch_ms, max_ms, fetch_rows, buckets";

  /** ext_stat_raw columns in insert order. */
  public static final String EXT_STAT_COLUMNS =
      "app_id, env, instance_id, ts, kind, host_id, count, error_count, timeout_count,"
          + " connect_fail_count, sum_ms, max_ms, buckets";

  /** metric columns in insert order. */
  public static final String METRIC_COLUMNS =
      "app_id, env, instance_id, name, kind, ts, value, delta";

  /** Heap gauge series name (OpenMetrics name with labels). */
  public static final String HEAP_METRIC = "jvm_memory_used_bytes{area=\"heap\"}";

  private static final long LATE_MIN_MS = 60_000;
  private static final long LATE_MAX_MS = 30 * 60_000;
  private static final long BATCH_DELAY_MS = 2_000;

  private final Config config;
  private final TxModel model;
  private final List<Tx> transactions = new ArrayList<>();
  private final Set<Long> late = new HashSet<>();
  private int callees;

  private CiDataset(Config config) {
    this.config = config;
    this.model =
        new TxModel(
            config.seed(), config.start().toEpochMilli(), config.span().toMillis(), /* seq */ 0);
  }

  /**
   * Dataset configuration.
   *
   * @param seed random seed (same seed and range: same rows)
   * @param start first second (UTC)
   * @param span length of the range
   * @param tps transactions per second over all applications (callees come on top)
   * @param metricInstances instances that report the four metric series every 5 s
   * @param blockRows rows per insert block (the writer's 50,000-row cap at small scale)
   */
  public record Config(
      long seed, Instant start, Duration span, double tps, int metricInstances, int blockRows) {

    /** CI profile: the 48 hours before the last full hour (two midnights at most), 1 TPS. */
    public static Config ci(Instant now) {
      Instant end = now.truncatedTo(ChronoUnit.HOURS);
      return new Config(
          20261024L, end.minus(Duration.ofDays(2)), Duration.ofDays(2), 1.0, 10, 20_000);
    }

    /** End of the range. */
    public Instant end() {
      return start.plus(span);
    }
  }

  /** Generates the dataset (in memory). */
  public static CiDataset generate(Config config) {
    CiDataset d = new CiDataset(config);
    d.generateTransactions();
    return d;
  }

  /** The configuration. */
  public Config config() {
    return config;
  }

  /** The transaction model (clock offsets, identifiers). */
  public TxModel model() {
    return model;
  }

  /** The generated transactions, in generation order. */
  public List<Tx> transactions() {
    return List.copyOf(transactions);
  }

  private void generateTransactions() {
    SplittableRandom random = new SplittableRandom(config.seed());
    long startMs = config.start().toEpochMilli();
    long seconds = config.span().toSeconds();
    double carry = 0;
    for (long s = 0; s < seconds; s++) {
      carry += config.tps();
      int n = (int) carry;
      carry -= n;
      for (int i = 0; i < n; i++) {
        long trueMs = startMs + s * 1000 + random.nextInt(1000);
        Tx tx = model.next(random, trueMs);
        transactions.add(tx);
        if (random.nextInt(200) == 0) {
          late.add(tx.txId());
        }
        if (tx.kind() == 1 && tx.app() <= 7 && random.nextInt(10) == 0) {
          Tx callee = model.callee(random, tx);
          transactions.add(callee);
          callees++;
        }
      }
    }
  }

  /** The insert blocks of every raw table, in insert order (re-sent blocks included). */
  public List<Block> blocks(SchemaParameters p) {
    List<Block> all = new ArrayList<>();
    SplittableRandom random = new SplittableRandom(config.seed() ^ 0xB10C);
    for (String table : TABLES) {
      all.addAll(tableBlocks(p, table, random));
    }
    return all;
  }

  /** The raw tables in insert order. */
  static final List<String> TABLES =
      List.of("xlog", "profile_step", "sql_stat_raw", "ext_stat_raw", "metric");

  /** The insert blocks of one raw table (one table at a time keeps the heap small). */
  private List<Block> tableBlocks(SchemaParameters p, String table, SplittableRandom random) {
    return switch (table) {
      case "xlog" ->
          chunk(p, table, XLOG_COLUMNS, datasetXlogEntries(transactions, random), random);
      case "profile_step" -> chunk(p, table, PROFILE_COLUMNS, profileEntries(transactions), random);
      case "sql_stat_raw" ->
          chunk(p, table, SQL_STAT_COLUMNS, sqlStatEntries(transactions), random);
      case "ext_stat_raw" ->
          chunk(p, table, EXT_STAT_COLUMNS, extStatEntries(transactions), random);
      case "metric" -> chunk(p, table, METRIC_COLUMNS, metricEntries(), random);
      default -> throw new IllegalArgumentException("Unknown raw table " + table);
    };
  }

  /**
   * Generates and inserts the dataset through the writer path and returns what was loaded.
   *
   * @param writer argos_writer client
   * @param p schema parameters (database and prefix)
   */
  public Summary load(ClickHouseHttp writer, SchemaParameters p) {
    long started = System.nanoTime();
    Map<String, Long> rows = new LinkedHashMap<>();
    Set<String> tokens = new HashSet<>();
    int sent = 0;
    int resent = 0;
    int midnight = 0;
    long bytes = 0;
    SplittableRandom random = new SplittableRandom(config.seed() ^ 0xB10C);
    for (String table : TABLES) {
      for (Block b : tableBlocks(p, table, random)) {
        insert(writer, b);
        sent++;
        bytes += b.body().length;
        if (tokens.add(b.token())) {
          rows.merge(b.table(), (long) b.rows(), Long::sum);
          if (b.crossesMidnight()) {
            midnight++;
          }
        } else {
          resent++;
        }
      }
    }
    long lateRows = transactions.stream().filter(t -> late.contains(t.txId())).count();
    return new Summary(
        config.start(),
        config.end(),
        rows,
        sent,
        resent,
        midnight,
        lateRows,
        callees,
        model.skewedInstances(),
        bytes,
        Duration.ofNanos(System.nanoTime() - started));
  }

  /** Inserts one block as the batch writer does (13 §5.1, §7.4). */
  public static void insert(ClickHouseHttp writer, Block b) {
    writer.insert(
        "INSERT INTO " + b.qualifiedTable() + " (" + b.columns() + ") FORMAT RowBinary",
        b.body(),
        Map.of(
            "insert_deduplication_token",
            b.token(),
            "deduplicate_blocks_in_dependent_materialized_views",
            "1"));
  }

  /**
   * Blocks of extra transactions for the procedures (Q15 duplicate re-insert, Q16 late arrival):
   * {@code count} transactions spread evenly over [from, to), their steps and statistics windows,
   * and one metric sample per series of instance 9901 every 5 s. Identifiers never collide with the
   * dataset's (separate tx_id sequence space).
   *
   * @param p schema parameters
   * @param seed seed of these blocks (and of their tokens)
   * @param from first ms (true time)
   * @param to end (exclusive)
   * @param count number of transactions
   */
  public static Extra extra(SchemaParameters p, long seed, Instant from, Instant to, int count) {
    TxModel model =
        new TxModel(seed, from.toEpochMilli(), Duration.between(from, to).toMillis(), 1024);
    SplittableRandom random = new SplittableRandom(seed);
    List<Tx> txs = new ArrayList<>();
    long span = Math.max(1, Duration.between(from, to).toMillis());
    for (int i = 0; i < count; i++) {
      long trueMs = from.toEpochMilli() + span * i / count;
      Tx tx = model.next(random, trueMs);
      // Keep the stored ts inside the window: no clock offset for the extra rows.
      txs.add(withTs(tx, trueMs, model));
    }
    Map<String, Block> blocks = new LinkedHashMap<>();
    blocks.put("xlog", one(p, "xlog", XLOG_COLUMNS, xlogEntries(txs, null), seed));
    blocks.put("profile_step", one(p, "profile_step", PROFILE_COLUMNS, profileEntries(txs), seed));
    blocks.put("sql_stat_raw", one(p, "sql_stat_raw", SQL_STAT_COLUMNS, sqlStatEntries(txs), seed));
    blocks.put("ext_stat_raw", one(p, "ext_stat_raw", EXT_STAT_COLUMNS, extStatEntries(txs), seed));
    List<Entry> metrics = new ArrayList<>();
    for (long t = from.toEpochMilli() / 5000 * 5000; t < to.toEpochMilli(); t += 5000) {
      long ts = t;
      metrics.add(
          new Entry(
              ts,
              ts,
              w ->
                  w.u32(1)
                      .string("prod")
                      .u32(9901)
                      .string(HEAP_METRIC)
                      .u8(1)
                      .u32(ts / 1000)
                      .f64(1.5e8)
                      .f64(0)
                      .endRow()));
    }
    blocks.put("metric", one(p, "metric", METRIC_COLUMNS, metrics, seed));
    Map<Long, Long> perMinute = new TreeMap<>();
    for (Tx tx : txs) {
      perMinute.merge(tx.tsMs() / 60_000 * 60_000, 1L, Long::sum);
    }
    return new Extra(blocks, txs, perMinute);
  }

  /** One xlog block of the given transactions (token derived from {@code seed}). */
  public static Block xlogBlock(SchemaParameters p, List<Tx> txs, long seed) {
    return one(p, "xlog", XLOG_COLUMNS, xlogEntries(txs, null), seed);
  }

  private static Tx withTs(Tx tx, long tsMs, TxModel model) {
    long txId =
        ((tsMs - TxModel.TX_EPOCH_MS) << 23) | (tx.txId() & ((1L << 23) - 1)); // same node, seq
    if (model.clockOffsetMs(tx.instance()) == 0 && tx.tsMs() == tsMs) {
      return tx;
    }
    return new Tx(
        tx.app(),
        tx.instance(),
        tsMs,
        txId,
        tx.kind(),
        tx.traceId(),
        tx.spanId(),
        tx.parentSpanId(),
        tx.incomingTrace(),
        tx.callerApp(),
        tx.serviceId(),
        tx.versionId(),
        tx.httpMethod(),
        tx.httpStatus(),
        tx.elapsedMs(),
        tx.error(),
        tx.errorClassId(),
        tx.errorGroupId(),
        tx.apdexTms(),
        tx.remoteIp(),
        tx.userId(),
        tx.requestId(),
        tx.sqls(),
        tx.exts(),
        tx.sqlTimeMs(),
        tx.extTimeMs(),
        tx.dbcTimeMs(),
        tx.cpuTimeMs(),
        tx.allocBytes(),
        tx.threadNameId(),
        tx.profileCaptured(),
        tx.flags(),
        tx.sqlIds());
  }

  // ---------------------------------------------------------------------------------------------
  // Row encoders
  // ---------------------------------------------------------------------------------------------

  private List<Entry> datasetXlogEntries(List<Tx> txs, SplittableRandom random) {
    return xlogEntries(txs, random, late);
  }

  private static List<Entry> xlogEntries(List<Tx> txs, SplittableRandom random, Set<Long> late) {
    List<Entry> entries = new ArrayList<>(txs.size());
    for (Tx tx : txs) {
      boolean isLate = late.contains(tx.txId());
      long delay =
          isLate && random != null
              ? LATE_MIN_MS + random.nextLong(LATE_MAX_MS - LATE_MIN_MS)
              : BATCH_DELAY_MS;
      entries.add(new Entry(tx.tsMs() + delay, tx.tsMs(), w -> writeXlog(w, tx, isLate)));
    }
    return entries;
  }

  private static List<Entry> xlogEntries(List<Tx> txs, SplittableRandom random) {
    return xlogEntries(txs, random, Set.of());
  }

  private static void writeXlog(RowBinary w, Tx tx, boolean late) {
    w.u32(tx.app())
        .string(tx.env())
        .u32(tx.instance())
        .u64(tx.tsMs())
        .u64(tx.txId())
        .u8(tx.kind())
        .fixed(tx.traceId())
        .u64(tx.spanId())
        .u64(tx.parentSpanId())
        .u8(tx.incomingTrace())
        .u32(tx.callerApp())
        .u64(tx.serviceId())
        .u64(tx.versionId())
        .string(tx.httpMethod())
        .u16(tx.httpStatus())
        .u32(tx.elapsedMs())
        .u8(tx.error() ? 1 : 0)
        .u64(tx.errorClassId())
        .u64(tx.errorGroupId())
        .u16(tx.apdexTms())
        .ipv6(tx.remoteIp())
        .u8(1) // ip_mode masked
        .string(tx.userId())
        .u8(tx.userId().isEmpty() ? 0 : 2) // user_id_mode off / hashed
        .string(tx.requestId())
        .u16(tx.sqls().size())
        .u32(tx.sqlTimeMs())
        .u32(tx.dbcTimeMs())
        .u16(tx.exts().size())
        .u32(tx.extTimeMs())
        .u32(tx.cpuTimeMs())
        .u64(tx.allocBytes())
        .u64(tx.threadNameId())
        .u8(tx.profileCaptured() ? 1 : 0)
        .u8(0) // streaming
        .u8(0) // timeout_flag
        .u16(1) // dispatch_count
        .u8(late ? 1 : 0)
        .u16(tx.flags())
        .u64Array(tx.sqlIds())
        .endRow();
  }

  private static List<Entry> profileEntries(List<Tx> txs) {
    List<Entry> entries = new ArrayList<>();
    for (Tx tx : txs) {
      if (!tx.profileCaptured()) {
        continue;
      }
      List<Consumer<RowBinary>> steps = new ArrayList<>();
      int[] seq = {1};
      int offset = 0;
      steps.add(w -> step(w, tx, 1, 0, 6, 0, tx.elapsedMs(), 0, 0, tx.serviceId(), 0, 0, 0, ""));
      int sqlSteps = Math.min(6, tx.sqls().size());
      if (sqlSteps > 0) {
        int s = ++seq[0];
        int at = offset;
        steps.add(
            w ->
                step(
                    w, tx, s, 1, 2, at, tx.dbcTimeMs(), 0, 0, TxModel.dbId(tx.app()), 0, 0, 0, ""));
      }
      for (int i = 0; i < sqlSteps; i++) {
        SqlExec q = tx.sqls().get(i);
        int s = ++seq[0];
        int at = offset + i;
        steps.add(
            w ->
                step(
                    w,
                    tx,
                    s,
                    1,
                    1,
                    at,
                    q.ms(),
                    q.execMs(),
                    q.ms() - q.execMs(),
                    q.sqlId(),
                    q.dbId(),
                    q.rows(),
                    q.error() ? 1 : 0,
                    "id = ?"));
      }
      for (ExtCall e : tx.exts()) {
        int s = ++seq[0];
        steps.add(
            w ->
                step(
                    w,
                    tx,
                    s,
                    1,
                    5,
                    0,
                    e.ms(),
                    0,
                    0,
                    e.hostId(),
                    e.hostId(),
                    0,
                    e.error() ? 500 : 200,
                    ""));
      }
      if (sqlSteps > 0) {
        int s = ++seq[0];
        steps.add(
            w ->
                step(
                    w,
                    tx,
                    s,
                    1,
                    3,
                    tx.elapsedMs() - 1,
                    1,
                    0,
                    0,
                    TxModel.dbId(tx.app()),
                    0,
                    0,
                    0,
                    ""));
      }
      if (tx.error()) {
        int s = ++seq[0];
        steps.add(
            w ->
                step(
                    w,
                    tx,
                    s,
                    1,
                    8,
                    tx.elapsedMs() - 1,
                    0,
                    0,
                    0,
                    tx.errorClassId(),
                    0,
                    0,
                    0,
                    "masked"));
      }
      for (Consumer<RowBinary> st : steps) {
        entries.add(new Entry(tx.tsMs() + BATCH_DELAY_MS, tx.tsMs(), st));
      }
    }
    return entries;
  }

  private static void step(
      RowBinary w,
      Tx tx,
      int seq,
      int parent,
      int type,
      int start,
      int elapsed,
      int exec,
      int fetch,
      long nameId,
      long targetId,
      int rows,
      int status,
      String detail) {
    w.u32(tx.app())
        .string(tx.env())
        .u32(tx.instance())
        .u64(tx.txId())
        .u64(tx.tsMs())
        .u8(tx.kind())
        .u16(seq)
        .u16(parent)
        .u8(type)
        .u32(Math.max(0, start))
        .u32(Math.max(0, elapsed))
        .u32(Math.max(0, exec))
        .u32(Math.max(0, fetch))
        .u64(nameId)
        .u64(targetId)
        .u32(rows)
        .u16(status)
        .u8(type == 8 ? 1 : 0)
        .u16(1)
        .u32(0)
        .string(detail)
        .string("")
        .u64Array(type == 8 ? new long[] {TxModel.key(11, 1), TxModel.key(11, 2)} : new long[0])
        .endRow();
  }

  private static List<Entry> sqlStatEntries(List<Tx> txs) {
    Map<String, long[]> windows = new TreeMap<>();
    Map<String, Tx> owner = new LinkedHashMap<>();
    for (Tx tx : txs) {
      long window = tx.tsMs() / 10_000 * 10_000;
      for (SqlExec q : tx.sqls()) {
        String key =
            tx.app() + "|" + tx.instance() + "|" + window + "|" + tx.kind() + "|" + q.sqlId();
        long[] a = windows.computeIfAbsent(key, k -> new long[7 + 15]);
        owner.putIfAbsent(key, tx);
        a[0]++;
        a[1] += q.error() ? 1 : 0;
        a[2] += q.ms();
        a[3] += q.execMs();
        a[4] += q.ms() - q.execMs();
        a[5] = Math.max(a[5], q.ms());
        a[6] += q.rows();
        a[7 + TxModel.bucket(q.ms())]++;
      }
    }
    List<Entry> entries = new ArrayList<>(windows.size());
    windows.forEach(
        (key, a) -> {
          Tx tx = owner.get(key);
          String[] parts = key.split("\\|", -1);
          long window = Long.parseLong(parts[2]);
          long sqlId = Long.parseLong(parts[4]);
          entries.add(
              new Entry(
                  window + 10_000 + BATCH_DELAY_MS,
                  window,
                  w ->
                      w.u32(tx.app())
                          .string(tx.env())
                          .u32(tx.instance())
                          .u32(window / 1000)
                          .u8(tx.kind())
                          .u64(sqlId)
                          .u64(TxModel.dbId(tx.app()))
                          .u32(a[0])
                          .u32(a[1])
                          .u64(a[2])
                          .u64(a[3])
                          .u64(a[4])
                          .u32(a[5])
                          .u64(a[6])
                          .u32Array(java.util.Arrays.copyOfRange(a, 7, 22))
                          .endRow()));
        });
    return entries;
  }

  private static List<Entry> extStatEntries(List<Tx> txs) {
    Map<String, long[]> windows = new TreeMap<>();
    Map<String, Tx> owner = new LinkedHashMap<>();
    for (Tx tx : txs) {
      long window = tx.tsMs() / 10_000 * 10_000;
      for (ExtCall e : tx.exts()) {
        String key =
            tx.app() + "|" + tx.instance() + "|" + window + "|" + tx.kind() + "|" + e.hostId();
        long[] a = windows.computeIfAbsent(key, k -> new long[6 + 15]);
        owner.putIfAbsent(key, tx);
        a[0]++;
        a[1] += e.error() ? 1 : 0;
        a[2] += e.timeout() ? 1 : 0;
        a[3] += e.connectFail() ? 1 : 0;
        a[4] += e.ms();
        a[5] = Math.max(a[5], e.ms());
        a[6 + TxModel.bucket(e.ms())]++;
      }
    }
    List<Entry> entries = new ArrayList<>(windows.size());
    windows.forEach(
        (key, a) -> {
          Tx tx = owner.get(key);
          String[] parts = key.split("\\|", -1);
          long window = Long.parseLong(parts[2]);
          long hostId = Long.parseLong(parts[4]);
          entries.add(
              new Entry(
                  window + 10_000 + BATCH_DELAY_MS,
                  window,
                  w ->
                      w.u32(tx.app())
                          .string(tx.env())
                          .u32(tx.instance())
                          .u32(window / 1000)
                          .u8(tx.kind())
                          .u64(hostId)
                          .u32(a[0])
                          .u32(a[1])
                          .u32(a[2])
                          .u32(a[3])
                          .u64(a[4])
                          .u32(a[5])
                          .u32Array(java.util.Arrays.copyOfRange(a, 6, 21))
                          .endRow()));
        });
    return entries;
  }

  private List<Entry> metricEntries() {
    List<Entry> entries = new ArrayList<>();
    SplittableRandom random = new SplittableRandom(config.seed() ^ 0x3E7);
    long start = config.start().toEpochMilli() / 5000 * 5000;
    long end = config.end().toEpochMilli();
    int made = 0;
    for (int app = 1; app <= TxModel.APPS && made < config.metricInstances(); app++) {
      for (int k = 1; k <= 2 && made < config.metricInstances(); k++, made++) {
        int instance = TxModel.instanceId(app, k);
        String env = TxModel.env(app);
        int a = app;
        double heap = 2e8;
        double requests = 1000;
        double gc = 10;
        for (long t = start; t < end; t += 5000) {
          heap = Math.max(5e7, Math.min(4e8, heap + random.nextInt(-5_000_000, 5_000_001)));
          double cpu = random.nextDouble();
          double dReq = random.nextInt(20);
          double dGc = random.nextDouble() * 0.05;
          requests += dReq;
          gc += dGc;
          long ts = t;
          double h = heap;
          double r = requests;
          double g = gc;
          entries.add(
              new Entry(
                  ts + BATCH_DELAY_MS,
                  ts,
                  w -> metric(w, a, env, instance, HEAP_METRIC, 1, ts, h, 0)));
          entries.add(
              new Entry(
                  ts + BATCH_DELAY_MS,
                  ts,
                  w -> metric(w, a, env, instance, "process_cpu_usage", 1, ts, cpu, 0)));
          entries.add(
              new Entry(
                  ts + BATCH_DELAY_MS,
                  ts,
                  w -> metric(w, a, env, instance, "http_server_requests_total", 2, ts, r, dReq)));
          entries.add(
              new Entry(
                  ts + BATCH_DELAY_MS,
                  ts,
                  w -> metric(w, a, env, instance, "jvm_gc_pause_seconds_total", 2, ts, g, dGc)));
        }
      }
    }
    return entries;
  }

  private static void metric(
      RowBinary w,
      int app,
      String env,
      int instance,
      String name,
      int kind,
      long tsMs,
      double value,
      double delta) {
    w.u32(app)
        .string(env)
        .u32(instance)
        .string(name)
        .u8(kind)
        .u32(tsMs / 1000)
        .f64(value)
        .f64(delta)
        .endRow();
  }

  // ---------------------------------------------------------------------------------------------
  // Blocks
  // ---------------------------------------------------------------------------------------------

  private List<Block> chunk(
      SchemaParameters p,
      String table,
      String columns,
      List<Entry> entries,
      SplittableRandom random) {
    entries.sort(Comparator.comparingLong(Entry::arrivalMs).thenComparingLong(Entry::tsMs));
    List<Block> blocks = new ArrayList<>();
    for (int i = 0; i < entries.size(); i += config.blockRows()) {
      List<Entry> part = entries.subList(i, Math.min(entries.size(), i + config.blockRows()));
      blocks.add(encode(p, table, columns, part, blockId(config.seed(), table, blocks.size())));
    }
    // Re-sent blocks with the same token (13 §15 "duplicate re-send 0.1 %", at least one per
    // table).
    List<Block> withResends = new ArrayList<>();
    for (int i = 0; i < blocks.size(); i++) {
      withResends.add(blocks.get(i));
      boolean forced = i == Math.min(1, blocks.size() - 1);
      if (forced || random.nextInt(1000) == 0) {
        withResends.add(blocks.get(Math.max(0, i - 1)));
      }
    }
    return withResends;
  }

  private static Block one(
      SchemaParameters p, String table, String columns, List<Entry> entries, long seed) {
    entries.sort(Comparator.comparingLong(Entry::arrivalMs).thenComparingLong(Entry::tsMs));
    return encode(p, table, columns, entries, blockId(seed, table, 0));
  }

  private static Block encode(
      SchemaParameters p, String table, String columns, List<Entry> entries, String blockId) {
    RowBinary w = new RowBinary();
    long min = Long.MAX_VALUE;
    long max = Long.MIN_VALUE;
    for (Entry e : entries) {
      e.writer().accept(w);
      min = Math.min(min, e.tsMs());
      max = Math.max(max, e.tsMs());
    }
    return new Block(
        table,
        p.table(table),
        columns,
        "argos:" + table + ":" + blockId,
        w.toByteArray(),
        w.rows(),
        min,
        max);
  }

  /** A UUID-shaped block id derived from the seed (stands in for the WAL COMMIT position, C-2). */
  static String blockId(long seed, String table, int index) {
    long hi = TxModel.key(seed, table.hashCode());
    long lo = TxModel.key(hi, index);
    return String.format(
        java.util.Locale.ROOT,
        "%08x-%04x-%04x-%04x-%012x",
        hi >>> 32,
        (hi >>> 16) & 0xFFFF,
        hi & 0xFFFF,
        (lo >>> 48) & 0xFFFF,
        lo & 0xFFFFFFFFFFFFL);
  }

  private record Entry(long arrivalMs, long tsMs, Consumer<RowBinary> writer) {}

  /**
   * One insert block.
   *
   * @param table raw table name without prefix
   * @param qualifiedTable {@code db.prefix+table}
   * @param columns explicit column list
   * @param token insert_deduplication_token
   * @param body RowBinary rows
   * @param rows number of rows
   * @param minTsMs smallest ts of the block
   * @param maxTsMs largest ts of the block
   */
  @SuppressWarnings("ArrayRecordComponent") // encoded bytes, compared by token only
  public record Block(
      String table,
      String qualifiedTable,
      String columns,
      String token,
      byte[] body,
      int rows,
      long minTsMs,
      long maxTsMs) {

    /** The block's rows fall on two or more UTC days (two partitions, 13 §5 "midnight block"). */
    public boolean crossesMidnight() {
      return Math.floorDiv(minTsMs, 86_400_000L) != Math.floorDiv(maxTsMs, 86_400_000L);
    }
  }

  /**
   * Extra blocks for the procedures.
   *
   * @param blocks one block per raw table
   * @param transactions the transactions of the xlog block
   * @param perMinute xlog rows per minute start (epoch ms)
   */
  public record Extra(
      Map<String, Block> blocks, List<Tx> transactions, Map<Long, Long> perMinute) {}

  /**
   * What {@link #load} inserted.
   *
   * @param start first second
   * @param end end of the range
   * @param rows distinct rows per raw table (after deduplication)
   * @param blocks blocks sent, re-sent ones included
   * @param resentBlocks blocks sent again with the same token
   * @param midnightBlocks distinct blocks whose rows fall on two days
   * @param lateRows xlog rows that arrived 1..30 minutes late
   * @param callees callee transactions of distributed calls
   * @param skewedInstances instances with a clock 60 s ahead
   * @param bytes RowBinary bytes sent
   * @param duration generation and insert time
   */
  public record Summary(
      Instant start,
      Instant end,
      Map<String, Long> rows,
      int blocks,
      int resentBlocks,
      int midnightBlocks,
      long lateRows,
      int callees,
      List<Integer> skewedInstances,
      long bytes,
      Duration duration) {}
}
