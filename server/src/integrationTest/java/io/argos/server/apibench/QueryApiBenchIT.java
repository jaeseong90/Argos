/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.apibench;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.argos.server.api.query.QueryLimits;
import io.argos.server.auth.Role;
import io.argos.server.testing.ServerTestEnvironment;
import io.argos.server.testing.ServerTestEnvironment.PgAccount;
import io.argos.server.testing.auth.ApiResponse;
import io.argos.server.testing.auth.ApiTestClient;
import io.argos.server.testing.auth.ArgosTestServer;
import io.argos.server.testing.auth.TestUsers;
import io.argos.server.testing.querydata.ApiBenchVerdict;
import io.argos.server.testing.querydata.Placement;
import io.argos.server.testing.querydata.QueryDataProfile;
import io.argos.server.testing.querydata.QueryDataset;
import io.argos.server.testing.querydata.QueryTestData;
import io.argos.server.testing.querydata.Samples;
import io.argos.storage.clickhouse.Account;
import io.argos.storage.clickhouse.MemoryBudget;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

/**
 * The N-07 and N-24 measurement tool of the query API (WO-23; 01 N-07, N-24; 18 T-NFR-07·24): loads
 * the generator data ({@link QueryTestData}, fixed seed) at the chosen profile into its own
 * environment (not the shared test data; {@link Resources}: ClickHouse 4 GiB and PostgreSQL 512 MiB
 * containers and a server heap of 1 GiB by default, the 13 §7.1 budget with enforce), then measures
 * each call of the N-07/N-24 set against the whole server started from the boot jar:
 *
 * <ul>
 *   <li><b>cold</b>: the first call after a server restart (a new JVM: no JIT-compiled code, new
 *       connection pools, empty caches). ClickHouse stays as warm as the data load left it: the
 *       reader account cannot drop its caches (no SYSTEM privilege), and the OS page cache of the
 *       container is not touched;
 *   <li><b>warm</b>: the median of the next 3 identical calls.
 * </ul>
 *
 * Calls: dashboard 1 h, XLog over a window of at least 100,000 points (gzip), transaction detail of
 * a transaction with profile steps, trace of a trace with at least 2 transactions, services and SQL
 * Top N over 1 day, user_id and request_id search over 1 h around a sample that the window holds.
 * Each case states what its data must hold (checked with SQL as {@code argos_reader} before the
 * measurement) and what its responses must show (checked on every measured response); a case that
 * misses either is FAILED with the reason, never a reference number. The report {@code
 * server/build/reports/api-bench/report.md} lists the environment, the data scale and time span,
 * the samples, the machine CPU load during the calls, every time and the targets.
 *
 * <p>Runs only with {@code -Pserver.apiBench=true} (system property {@code argos.test.apiBench});
 * profile {@code -Pserver.apiBench.profile=ci|local|full} ({@code argos.test.apiBenchProfile},
 * default {@code ci}; {@code tiny} only checks the tool). Verdict "reference" unless {@code
 * -Pserver.apiBench.enforce=true} ({@code argos.test.apiBenchEnforce}), which asserts for every
 * case both conditions of ADR-0010 C-180 ({@link ApiBenchVerdict}): the warm median ≤ its target
 * and the cold call ≤ 2 × its target; the report shows each condition as a column. The targets hold
 * on 30 days of data, so enforce needs {@code -Pserver.apiBench.profile=full} (target hardware
 * only, WO-53): with any other profile the test fails before it loads anything. Server heap {@code
 * -Pserver.apiBench.serverXmx} ({@code argos.test.apiBenchServerXmx}) and ClickHouse container
 * memory {@code -Pserver.apiBench.chMemory} ({@code argos.test.apiBenchChMemory}, e.g. {@code
 * 7.5g}); see {@link Resources} for the defaults. Run it alone ({@code --tests
 * '*QueryApiBenchIT'}): its containers and the shared query test data of other classes together
 * exceed 5 GiB.
 */
@Tag("integration")
@Tag("slow")
@Tag("req:N-07")
@Tag("req:N-24")
class QueryApiBenchIT {

  private static final int WARM_RUNS = 3;

  /** The XLog point count of N-07 ("XLog 100,000 points: 2 s"). */
  static final long XLOG_POINTS = 100_000;

  /** Longest XLog window: the API range limit (24 h). */
  static final long XLOG_MAX_MINUTES = 24 * 60;

  /** How many samples of a kind are tried before a case is FAILED for want of data. */
  static final int SAMPLE_TRIES = 20;

  private static final DateTimeFormatter CH_MS =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT).withZone(ZoneOffset.UTC);

  private static final DateTimeFormatter CH_S =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT).withZone(ZoneOffset.UTC);

  @TempDir Path work;

  /**
   * One measured call.
   *
   * @param id short name (also the server log prefix)
   * @param requirement {@code N-07} or {@code N-24}
   * @param target what the requirement says
   * @param targetMs the target in milliseconds
   * @param request the request as text (report)
   * @param call the call
   * @param size what the response holds (points, rows), from the last response
   * @param precondition null when the data holds what the case needs (checked before the
   *     measurement), otherwise why not: the case is then FAILED and not measured
   * @param check null when a response shows the load the case is about, otherwise why not (applied
   *     to every measured response)
   */
  record Case(
      String id,
      String requirement,
      String target,
      long targetMs,
      String request,
      Function<BenchClient, BenchClient.Timed> call,
      Function<BenchClient.Timed, String> size,
      String precondition,
      Function<BenchClient.Timed, String> check) {}

  /**
   * The times of one case.
   *
   * @param c the case
   * @param cold the first call after the restart, null when the case was not measured
   * @param warm the next calls
   * @param cpu the machine CPU load during the calls, null when not measured
   * @param failure why the case FAILED (precondition or response check), or null
   */
  record Result(
      Case c,
      BenchClient.Timed cold,
      List<BenchClient.Timed> warm,
      CpuSampler.Window cpu,
      String failure) {

    static Result notMeasured(Case c) {
      return new Result(c, null, List.of(), null, c.precondition());
    }

    boolean measured() {
      return cold != null;
    }

    double warmMedian() {
      List<Double> ms = warm.stream().map(BenchClient.Timed::millis).sorted().toList();
      return ms.get(ms.size() / 2);
    }

    boolean succeeded() {
      return failure == null && measured() && ok(cold) && warm.stream().allMatch(Result::ok);
    }

    private static boolean ok(BenchClient.Timed t) {
      return t.status() >= 200 && t.status() < 300;
    }

    String status() {
      if (!measured()) {
        return "not measured";
      }
      if (ok(cold) && warm.stream().allMatch(Result::ok)) {
        return Integer.toString(cold.status());
      }
      BenchClient.Timed bad = ok(cold) ? warm.stream().filter(t -> !ok(t)).findFirst().get() : cold;
      return bad.status() + (bad.errorCode() == null ? "" : " " + bad.errorCode());
    }

    /** The C-180 verdict of a measured, successful case; null otherwise. */
    ApiBenchVerdict times() {
      return failure == null && succeeded()
          ? ApiBenchVerdict.of(cold.millis(), warmMedian(), c.targetMs())
          : null;
    }

    String verdict(boolean enforce) {
      if (failure != null) {
        return "FAILED (" + failure + ")";
      }
      if (!succeeded()) {
        return "ERROR";
      }
      return times().overall(enforce);
    }
  }

  /**
   * The server heap and the container memory of a run. Defaults: with enforce the 13 §7.1 budget of
   * the S topology (server {@code -Xmx4g}, ClickHouse 7.5 GiB, PostgreSQL 1.5 GiB); otherwise what
   * a shared development machine allows (containers at most 5 GiB together: ClickHouse 4 GiB,
   * PostgreSQL 512 MiB; server {@code -Xmx1g}). {@code argos.test.apiBenchServerXmx} and {@code
   * argos.test.apiBenchChMemory} override the server heap and the ClickHouse memory.
   *
   * @param serverXmx the {@code -Xmx} value of the server JVM
   * @param clickHouseBytes the ClickHouse container memory limit
   * @param postgresBytes the PostgreSQL container memory limit
   */
  record Resources(String serverXmx, long clickHouseBytes, long postgresBytes) {

    private static final Pattern SIZE = Pattern.compile("([0-9]+(?:\\.[0-9]+)?)([kKmMgG]?)");

    static Resources of(boolean enforce) {
      String xmx = System.getProperty("argos.test.apiBenchServerXmx", "").strip();
      String ch = System.getProperty("argos.test.apiBenchChMemory", "").strip();
      return new Resources(
          xmx.isEmpty() ? (enforce ? "4g" : "1g") : xmx,
          ch.isEmpty() ? (enforce ? 15 * MemoryBudget.GIB / 2 : 4 * MemoryBudget.GIB) : bytes(ch),
          enforce ? 3 * MemoryBudget.GIB / 2 : MemoryBudget.GIB / 2);
    }

    /** A memory size such as {@code 7.5g}, {@code 4096m} or a byte count. */
    static long bytes(String text) {
      Matcher m = SIZE.matcher(text.strip());
      if (!m.matches()) {
        throw new IllegalArgumentException(
            "Memory size '" + text + "' is not a size such as 7.5g, 4096m or a byte count");
      }
      long unit =
          switch (m.group(2).toLowerCase(Locale.ROOT)) {
            case "k" -> 1L << 10;
            case "m" -> 1L << 20;
            case "g" -> 1L << 30;
            default -> 1L;
          };
      return (long) (Double.parseDouble(m.group(1)) * unit);
    }

    /** {@code bytes} in GiB with one decimal. */
    static String gib(long bytes) {
      return String.format(Locale.ROOT, "%.1f GiB", bytes / (double) MemoryBudget.GIB);
    }
  }

  @Test
  @DisplayName(
      "T-NFR-07/T-NFR-24 (reference unless enforced) query API times on generator data: dashboard"
          + " 1 h, XLog over at least 100,000 points, transaction detail with steps, trace of 2+"
          + " transactions, services and SQL Top N over 1 day, user_id and request_id search over"
          + " 1 h with hits; cold after a server restart and warm (median of 3); a case whose data"
          + " or response misses its load is FAILED; enforce runs only on full (30-day) data")
  void bench() throws Exception {
    assumeTrue(
        Boolean.getBoolean("argos.test.apiBench"),
        "The query API bench runs only with -Pserver.apiBench=true");
    QueryDataProfile profile =
        QueryDataProfile.of(System.getProperty("argos.test.apiBenchProfile", "ci"));
    boolean enforce = Boolean.getBoolean("argos.test.apiBenchEnforce");
    if (enforce && profile != QueryDataProfile.FULL) {
      // A failure, not a skip: an enforced verdict on less than 30 days of data would be wrong.
      fail(
          "-Pserver.apiBench.enforce=true asserts the N-07/N-24 targets, which hold on 30 days of"
              + " data: run it with -Pserver.apiBench.profile=full on the target hardware (got"
              + " profile "
              + profile.generatorName()
              + "; without enforce the bench reports reference numbers)");
    }
    Resources resources = Resources.of(enforce);
    Path reportDir =
        Path.of(System.getProperty("argos.test.apiBenchReportDir", "build/reports/api-bench"));
    Files.createDirectories(reportDir);
    Instant started = Instant.now();

    try (CpuSampler cpu = new CpuSampler();
        ServerTestEnvironment env =
            ServerTestEnvironment.builder()
                .clickHouseMemoryLimit(resources.clickHouseBytes())
                .postgresMemoryLimit(resources.postgresBytes())
                .start()) {
      long loadStart = System.nanoTime();
      QueryDataset data;
      ArgosTestServer schema = ArgosTestServer.start(env); // creates the schema
      try {
        data = QueryTestData.load(env, profile, Placement.RECENT);
      } finally {
        schema.close();
      }
      Duration load = Duration.ofNanos(System.nanoTime() - loadStart);
      Inputs in = inputs(env, data);
      TestUsers.TestUser user = new TestUsers(env).create(Role.VIEWER);

      long measureStart = System.nanoTime();
      List<Result> results = new ArrayList<>();
      for (Case c : cases(in)) {
        if (c.precondition() != null) {
          results.add(Result.notMeasured(c));
          continue;
        }
        try (BenchServer server = BenchServer.start(env, work, c.id(), resources.serverXmx())) {
          ApiTestClient session = ApiTestClient.of(server.baseUrl());
          ApiResponse login = session.login(user.username(), user.password());
          assertThat(login.status()).as("login " + login.body()).isEqualTo(200);
          BenchClient client = new BenchClient(session);
          long t0 = System.nanoTime();
          BenchClient.Timed cold = c.call().apply(client);
          List<BenchClient.Timed> warm = new ArrayList<>();
          for (int i = 0; i < WARM_RUNS; i++) {
            warm.add(c.call().apply(client));
          }
          CpuSampler.Window window = cpu.window(t0, System.nanoTime());
          results.add(new Result(c, cold, warm, window, responseFailure(c, cold, warm)));
        }
      }
      CpuSampler.Window overall = cpu.window(measureStart, System.nanoTime());
      String clickHouse = env.clickHouse(Account.READER).scalar("SELECT version()").strip();
      Path report = reportDir.resolve("report.md");
      Files.writeString(
          report,
          report(
              profile,
              enforce,
              resources,
              started,
              load,
              data,
              in,
              results,
              overall,
              cpu,
              clickHouse),
          StandardCharsets.UTF_8);
      System.out.println("Query API bench report: " + report.toAbsolutePath());

      for (Result r : results) {
        assertThat(r.failure()).as(r.c().id() + " FAILED").isNull();
        assertThat(r.succeeded()).as(r.c().id() + ": " + r.status()).isTrue();
        if (enforce) {
          // ADR-0010 C-180: (a) warm median <= target and (b) cold <= 2 x target, both.
          assertThat(r.warmMedian())
              .as(r.c().id() + " warm median (ms) against " + r.c().target())
              .isLessThanOrEqualTo((double) r.c().targetMs());
          assertThat(r.cold().millis())
              .as(
                  r.c().id()
                      + " cold (ms) against "
                      + ApiBenchVerdict.COLD_FACTOR
                      + " x "
                      + r.c().target())
              .isLessThanOrEqualTo((double) ApiBenchVerdict.COLD_FACTOR * r.c().targetMs());
        }
      }
    }
  }

  /** The first response of a measured case that misses the case's check (cold first), or null. */
  private static String responseFailure(
      Case c, BenchClient.Timed cold, List<BenchClient.Timed> warm) {
    List<BenchClient.Timed> all = new ArrayList<>();
    all.add(cold);
    all.addAll(warm);
    for (int i = 0; i < all.size(); i++) {
      BenchClient.Timed t = all.get(i);
      if (t.status() < 200 || t.status() >= 300) {
        continue; // reported as the status (ERROR)
      }
      String why = t.json() == null ? "no JSON body" : c.check().apply(t);
      if (why != null) {
        return (i == 0 ? "cold" : "warm " + i) + " response: " + why;
      }
    }
    return null;
  }

  // ------------------------------------------------------------------------------------ inputs

  /**
   * The XLog window of the busiest application.
   *
   * @param minutes the window {@code [end - minutes, end)}
   * @param total count(xlog) of the window (every kind, the call asks for {@code kind=ALL})
   * @param failure why the window does not hold {@value #XLOG_POINTS} points, or null
   */
  record XlogWindow(long minutes, long total, String failure) {}

  /**
   * A sample the call asks for, chosen from the loaded data.
   *
   * @param appId its application
   * @param value the id (tx id decimal, trace id hex, user id, request id)
   * @param ts its time (the search window is built around it)
   * @param rows what the data holds for it (profile steps, trace transactions, search hits)
   * @param failure why no sample with what the case needs was found, or null
   */
  record Pick(int appId, String value, Instant ts, long rows, String failure) {

    static Pick none(String failure) {
      return new Pick(0, null, null, 0, failure);
    }
  }

  /**
   * What the calls ask for, taken from the loaded data.
   *
   * @param busyApp the application with the most transactions (dashboard, XLog, Top N)
   * @param end the end of the generated range (start of the minute of the load)
   * @param xlog the XLog window of at least {@value #XLOG_POINTS} points
   * @param tx a transaction with profile steps whose ts its tx_id gives (the ±2 s path)
   * @param trace a trace with at least 2 transactions
   * @param user a user id with hits in its 1 h window (its rows and application switched to plain
   *     mode, see the report)
   * @param request a request id with hits in its 1 h window
   */
  record Inputs(
      int busyApp, Instant end, XlogWindow xlog, Pick tx, Pick trace, Pick user, Pick request) {}

  private static Inputs inputs(ServerTestEnvironment env, QueryDataset data) throws SQLException {
    Sql reader = new Sql(env, Account.READER);
    int busy =
        (int)
            reader.scalarLong(
                "SELECT app_id FROM argos.xlog GROUP BY app_id ORDER BY count() DESC, app_id"
                    + " LIMIT 1",
                Map.of());
    Instant end = data.end();
    Samples s = data.samples();
    XlogWindow xlog = xlogWindow(reader, busy, data.start(), end);
    Pick tx = detailSample(reader, busy, data.profileFrom(), end);
    Pick trace = traceSample(reader, busy, data.start(), end);
    Pick request = searchSample(reader, s.requests(), "request_id", end);
    Pick user = userSample(env, reader, s.users(), end);
    return new Inputs(busy, end, xlog, tx, trace, user, request);
  }

  /**
   * Q03 {@code @probe xlog_window}: the minutes of the busiest application that hold {@value
   * #XLOG_POINTS} transactions at its mean rate per minute of the last day of {@code app_stat_1m}
   * (2 % margin), at most 24 h; then count(xlog) of the window, widened while it falls short.
   */
  private static XlogWindow xlogWindow(Sql reader, int app, Instant start, Instant end) {
    Instant dayAgo = end.minus(Duration.ofDays(1));
    Instant rateFrom = start.isAfter(dayAgo) ? start : dayAgo;
    List<String> rate =
        reader.row(
            "SELECT sum(count), uniqExact(minute) FROM argos.app_stat_1m"
                + " WHERE app_id = {app:UInt32}"
                + " AND minute >= {from:DateTime('UTC')} AND minute < {to:DateTime('UTC')}",
            Map.of(
                "app", Integer.toString(app),
                "from", CH_S.format(rateFrom.truncatedTo(ChronoUnit.MINUTES)),
                "to", CH_S.format(end)));
    long count = Long.parseLong(rate.get(0));
    long minutesWithData = Long.parseLong(rate.get(1));
    if (count == 0 || minutesWithData == 0) {
      return new XlogWindow(0, 0, "app_stat_1m holds no minute of application " + app);
    }
    double perMinute = (double) count / minutesWithData;
    long dataMinutes = Duration.between(start, end).toMinutes();
    long cap = Math.min(XLOG_MAX_MINUTES, Math.max(1, dataMinutes));
    long minutes = Math.min(cap, Math.max(10, (long) Math.ceil(XLOG_POINTS * 1.02 / perMinute)));
    long total = xlogCount(reader, app, end, minutes);
    for (int i = 0; i < 5 && total < XLOG_POINTS && minutes < cap; i++) {
      long more = (long) Math.ceil((XLOG_POINTS - total) * 1.05 / perMinute) + 1;
      minutes = Math.min(cap, minutes + more);
      total = xlogCount(reader, app, end, minutes);
    }
    String failure =
        total >= XLOG_POINTS
            ? null
            : String.format(
                Locale.ROOT,
                "the longest window (%d min) holds %,d transactions of application %d, fewer than"
                    + " %,d",
                minutes,
                total,
                app,
                XLOG_POINTS);
    return new XlogWindow(minutes, total, failure);
  }

  private static long xlogCount(Sql reader, int app, Instant end, long minutes) {
    return reader.scalarLong(
        "SELECT count() FROM argos.xlog WHERE app_id = {app:UInt32}"
            + " AND ts >= {from:DateTime64(3, 'UTC')} AND ts < {to:DateTime64(3, 'UTC')}",
        Map.of(
            "app", Integer.toString(app),
            "from", CH_MS.format(end.minus(Duration.ofMinutes(minutes))),
            "to", CH_MS.format(end)));
  }

  /**
   * Q05 {@code @probe tx} ({@code ptx}): the smallest tx_id with steps in {@code profile_step}
   * during the last hour of the busiest application, limited to transactions whose stored ts is
   * within 1 s of the time in their tx_id (the ±2 s lookup of C-50, not the fallback that T-API-005
   * measures) and whose xlog row says the profile was captured.
   */
  private static Pick detailSample(Sql reader, int app, Instant profileFrom, Instant end) {
    Instant hourAgo = end.minus(Duration.ofHours(1));
    Instant from = profileFrom.isAfter(hourAgo) ? profileFrom : hourAgo;
    Map<String, String> window =
        Map.of("app", Integer.toString(app), "from", CH_MS.format(from), "to", CH_MS.format(end));
    String tx =
        reader.scalar(
            "SELECT min(p.tx_id) FROM argos.profile_step AS p"
                + " WHERE p.app_id = {app:UInt32}"
                + " AND p.ts >= {from:DateTime64(3, 'UTC')} AND p.ts < {to:DateTime64(3, 'UTC')}"
                + " AND abs(dateDiff('millisecond', p.ts, toDateTime64("
                + "(bitShiftRight(p.tx_id, 23) + 1767225600000) / 1000, 3, 'UTC'))) <= 1000"
                + " AND p.tx_id IN (SELECT tx_id FROM argos.xlog WHERE app_id = {app:UInt32}"
                + " AND ts >= {from:DateTime64(3, 'UTC')} AND ts < {to:DateTime64(3, 'UTC')}"
                + " AND profile_captured = 1)",
            window);
    if (tx.isEmpty() || tx.equals("0")) {
      return Pick.none(
          "no transaction of application "
              + app
              + " with profile steps, a captured profile and its tx_id time in the last hour");
    }
    Map<String, String> one = new LinkedHashMap<>(window);
    one.put("tx", tx);
    long steps =
        reader.scalarLong(
            "SELECT count() FROM argos.profile_step WHERE app_id = {app:UInt32}"
                + " AND tx_id = {tx:UInt64}",
            one);
    String ts =
        reader.scalar(
            "SELECT toUnixTimestamp64Milli(min(ts)) FROM argos.xlog WHERE app_id = {app:UInt32}"
                + " AND tx_id = {tx:UInt64}"
                + " AND ts >= {from:DateTime64(3, 'UTC')} AND ts < {to:DateTime64(3, 'UTC')}",
            one);
    return new Pick(
        app,
        tx,
        Instant.ofEpochMilli(Long.parseLong(ts)),
        steps,
        steps > 0 ? null : "tx " + tx + " has no profile step");
  }

  /**
   * Q06 {@code @probe trace}: a trace 6 hours before the end (or at the start of shorter data), one
   * that crossed applications (a callee transaction has {@code caller_app_id != 0}), the busiest
   * application's first; it must have at least 2 transactions in the last 24 hours (the API's
   * default window).
   */
  private static Pick traceSample(Sql reader, int app, Instant start, Instant end) {
    Instant sixAgo = end.minus(Duration.ofHours(6));
    Instant from = start.isAfter(sixAgo) ? start : sixAgo;
    Instant to = from.plus(Duration.ofHours(1)).isAfter(end) ? end : from.plus(Duration.ofHours(1));
    List<List<String>> rows =
        reader.rows(
            "SELECT lower(hex(trace_id)), toUnixTimestamp64Milli(ts), app_id FROM argos.xlog"
                + " WHERE ts >= {from:DateTime64(3, 'UTC')} AND ts < {to:DateTime64(3, 'UTC')}"
                + " AND caller_app_id != 0"
                + " ORDER BY app_id = {app:UInt32} DESC, ts, tx_id LIMIT 1",
            Map.of(
                "app", Integer.toString(app),
                "from", CH_MS.format(from),
                "to", CH_MS.format(to)));
    if (rows.isEmpty()) {
      return Pick.none(
          "no transaction with a caller (caller_app_id != 0) between " + from + " and " + to);
    }
    String trace = rows.get(0).get(0);
    Instant dayAgo = end.minus(Duration.ofHours(24));
    long transactions =
        reader.scalarLong(
            "SELECT count() FROM argos.xlog"
                + " WHERE ts >= {from:DateTime64(3, 'UTC')} AND ts < {to:DateTime64(3, 'UTC')}"
                + " AND trace_id = toFixedString(unhex({trace:String}), 16)",
            Map.of(
                "trace", trace,
                "from", CH_MS.format(start.isAfter(dayAgo) ? start : dayAgo),
                "to", CH_MS.format(end)));
    return new Pick(
        Integer.parseInt(rows.get(0).get(2)),
        trace,
        Instant.ofEpochMilli(Long.parseLong(rows.get(0).get(1))),
        transactions,
        transactions >= 2
            ? null
            : "trace " + trace + " has " + transactions + " transaction(s) in the last 24 h");
  }

  /** The 1 h search window of a sample: 30 minutes on each side, cut at the end of the data. */
  static Instant[] searchWindow(Instant ts, Instant end) {
    Instant to = ts.plus(Duration.ofMinutes(30));
    if (to.isAfter(end)) {
      to = end;
    }
    to = to.truncatedTo(ChronoUnit.SECONDS);
    return new Instant[] {to.minus(Duration.ofHours(1)), to};
  }

  /** The first of the samples whose 1 h window holds a row with that value of {@code column}. */
  private static Pick searchSample(
      Sql reader, List<Samples.Value> samples, String column, Instant end) {
    int tried = 0;
    for (Samples.Value v : samples) {
      if (tried++ == SAMPLE_TRIES) {
        break;
      }
      long hits = searchHits(reader, v, column, end, false);
      if (hits > 0) {
        return new Pick(v.appId(), v.value(), v.ts(), hits, null);
      }
    }
    return Pick.none(
        "none of the first "
            + Math.min(tried, SAMPLE_TRIES)
            + " "
            + column
            + " samples is in xlog within its 1 h window");
  }

  private static long searchHits(
      Sql reader, Samples.Value v, String column, Instant end, boolean plainOnly) {
    Instant[] w = searchWindow(v.ts(), end);
    return reader.scalarLong(
        "SELECT count() FROM argos.xlog WHERE app_id = {app:UInt32}"
            + " AND ts >= {from:DateTime64(3, 'UTC')} AND ts < {to:DateTime64(3, 'UTC')}"
            + (column.equals("user_id")
                ? " AND user_id = {v:String}" + (plainOnly ? " AND user_id_mode = 'plain'" : "")
                : " AND request_id = {v:String}"),
        Map.of(
            "app", Integer.toString(v.appId()),
            "from", CH_MS.format(w[0]),
            "to", CH_MS.format(w[1]),
            "v", v.value()));
  }

  /**
   * A user id sample with hits in its window whose C-163 normalization (the API's {@code user_id}
   * filter) leaves it unchanged. The generator stores user ids in hashed mode, which the API does
   * not search before the application salt exists (C-152, WO-28): the rows of that user id are
   * switched to plain mode ({@code ALTER TABLE ... UPDATE} as {@code argos_migrator}, synchronous)
   * and the instances of its application too, so the plain-id statement (bloom index on user_id,
   * then {@code user_id_mode = 'plain'}) finds them.
   */
  private static Pick userSample(
      ServerTestEnvironment env, Sql reader, List<Samples.Value> samples, Instant end)
      throws SQLException {
    int tried = 0;
    Samples.Value chosen = null;
    for (Samples.Value v : samples) {
      if (tried++ == SAMPLE_TRIES) {
        break;
      }
      if (v.value().equals(QueryLimits.userIdFilter(v.value()))
          && searchHits(reader, v, "user_id", end, false) > 0) {
        chosen = v;
        break;
      }
    }
    if (chosen == null) {
      return Pick.none(
          "none of the first "
              + Math.min(tried, SAMPLE_TRIES)
              + " user_id samples is in xlog within its 1 h window and unchanged by the C-163"
              + " normalization");
    }
    new Sql(env, Account.MIGRATOR)
        .execute(
            "ALTER TABLE argos.xlog UPDATE user_id_mode = 'plain'"
                + " WHERE app_id = {app:UInt32} AND user_id = {v:String}",
            Map.of("app", Integer.toString(chosen.appId()), "v", chosen.value()),
            Map.of("mutations_sync", "2"));
    try (Connection c = env.connect(PgAccount.APP);
        PreparedStatement ps =
            c.prepareStatement(
                "UPDATE argos.instances SET user_id_mode = 'plain' WHERE app_id = ?")) {
      ps.setInt(1, chosen.appId());
      ps.executeUpdate();
    }
    long hits = searchHits(reader, chosen, "user_id", end, true);
    return new Pick(
        chosen.appId(),
        chosen.value(),
        chosen.ts(),
        hits,
        hits > 0 ? null : "the plain-mode rows of the user_id sample are not in its 1 h window");
  }

  /** ClickHouse statements of the bench with every value a query parameter ({name:Type}). */
  private record Sql(ServerTestEnvironment env, Account account) {

    String execute(String sql, Map<String, String> params, Map<String, String> settings) {
      Map<String, String> url = new LinkedHashMap<>(settings);
      params.forEach((name, value) -> url.put("param_" + name, value));
      return env.clickHouse(account).execute(sql, url).body();
    }

    List<List<String>> rows(String sql, Map<String, String> params) {
      List<List<String>> rows = new ArrayList<>();
      for (String line : execute(sql, params, Map.of()).split("\n", -1)) {
        if (!line.isEmpty()) {
          rows.add(List.of(line.split("\t", -1)));
        }
      }
      return rows;
    }

    List<String> row(String sql, Map<String, String> params) {
      List<List<String>> rows = rows(sql, params);
      if (rows.size() != 1) {
        throw new IllegalStateException("Expected one row, got " + rows.size() + " for: " + sql);
      }
      return rows.get(0);
    }

    String scalar(String sql, Map<String, String> params) {
      List<String> row = row(sql, params);
      if (row.size() != 1) {
        throw new IllegalStateException("Expected one value, got " + row + " for: " + sql);
      }
      return row.get(0);
    }

    long scalarLong(String sql, Map<String, String> params) {
      return Long.parseLong(scalar(sql, params));
    }
  }

  // ------------------------------------------------------------------------------------- cases

  private static List<Case> cases(Inputs in) {
    Instant end = in.end();
    String app = "/api/v1/apps/" + in.busyApp();
    String hour = range(end.minus(Duration.ofHours(1)), end);
    String day = range(end.minus(Duration.ofDays(1)), end);
    List<Case> cases = new ArrayList<>();
    cases.add(
        new Case(
            "dashboard-1h",
            "N-07",
            "dashboard 1 h: 1 s",
            1_000,
            "GET " + app + "/dashboard?" + hour,
            c -> c.get(app + "/dashboard?" + hour),
            t ->
                t.json() == null
                    ? ""
                    : t.json().path("meta").path("source").asString()
                        + " source, "
                        + t.json().path("data").path("tiles").path("count").asLong()
                        + " transactions",
            null,
            t ->
                t.json().path("data").path("tiles").path("count").asLong() > 0
                    ? null
                    : "the busiest application has no transaction in the hour"));
    XlogWindow w = in.xlog();
    String xlogRange = range(end.minus(Duration.ofMinutes(w.minutes())), end);
    cases.add(
        new Case(
            "xlog-100k",
            "N-07",
            "XLog 100,000 points: 2 s",
            2_000,
            "GET "
                + app
                + "/xlog?"
                + w.minutes()
                + " min holding "
                + String.format(Locale.ROOT, "%,d", w.total())
                + " transactions&kind=ALL&limit=100000 (gzip)",
            c -> c.get(app + "/xlog?" + xlogRange + "&kind=ALL&limit=100000"),
            QueryApiBenchIT::xlogText,
            w.failure(),
            t -> {
              long total = t.json().path("meta").path("total").asLong();
              long shown = t.json().path("meta").path("shown").asLong();
              if (total < XLOG_POINTS) {
                return String.format(
                    Locale.ROOT, "meta.total %,d is below %,d points", total, XLOG_POINTS);
              }
              return shown > 0 ? null : "no point shown";
            }));
    String tx = "/api/v1/transactions/" + in.tx().value();
    cases.add(
        new Case(
            "tx-detail",
            "N-07, N-24",
            "transaction detail / tx id: 0.5 s",
            500,
            "GET /api/v1/transactions/{tx_id with "
                + in.tx().rows()
                + " profile steps} (no ts hint)",
            c -> c.get(tx),
            QueryApiBenchIT::stepsText,
            in.tx().failure(),
            t -> {
              JsonNode profile = t.json().path("data").path("profile");
              String state = profile.path("state").asString();
              if (!state.equals("captured")) {
                return "profile.state " + state + ", expected captured";
              }
              return profile.path("steps").size() > 0 ? null : "profile.steps is empty";
            }));
    String trace = "/api/v1/traces/" + in.trace().value();
    cases.add(
        new Case(
            "trace",
            "N-24",
            "trace id: 0.5 s",
            500,
            "GET /api/v1/traces/{trace_id of "
                + in.trace().rows()
                + " transactions} (no hint: id time or default window 24 h)",
            c -> c.get(trace),
            t ->
                t.json() == null
                    ? ""
                    : t.json().path("data").path("nodes").size() + " transactions",
            in.trace().failure(),
            t -> {
              int nodes = t.json().path("data").path("nodes").size();
              return nodes >= 2 ? null : nodes + " node(s), expected at least 2";
            }));
    cases.add(
        new Case(
            "services-top-1d",
            "N-07",
            "services Top N 1 day: 1 s",
            1_000,
            "GET " + app + "/services/stats?" + day + "&sort=count:desc&limit=100",
            c -> c.get(app + "/services/stats?" + day + "&sort=count:desc&limit=100"),
            QueryApiBenchIT::rowsText,
            null,
            QueryApiBenchIT::someRows));
    cases.add(
        new Case(
            "sql-top-1d",
            "N-07",
            "SQL Top N 1 day: 1 s",
            1_000,
            "GET " + app + "/sql/stats?" + day + "&sort=sum_ms:desc&limit=100",
            c -> c.get(app + "/sql/stats?" + day + "&sort=sum_ms:desc&limit=100"),
            QueryApiBenchIT::rowsText,
            null,
            QueryApiBenchIT::someRows));
    cases.add(search("user-search-1h", "user_id", in.user(), end));
    cases.add(search("request-search-1h", "request_id", in.request(), end));
    return cases;
  }

  private static Case search(String id, String field, Pick sample, Instant end) {
    Map<String, Object> body = new LinkedHashMap<>();
    String path = "/api/v1/apps/" + sample.appId() + "/transactions/search";
    if (sample.failure() == null) {
      Instant[] w = searchWindow(sample.ts(), end);
      body.put("from", w[0].toString());
      body.put("to", w[1].toString());
      body.put("kind", "ALL");
      body.put("filters", Map.of(field, sample.value()));
      body.put("limit", 100);
    }
    return new Case(
        id,
        "N-24",
        field + " 1 h: 3 s",
        3_000,
        "POST " + path + " {" + field + ": sample with " + sample.rows() + " hit(s), 1 h}",
        c -> c.post(path, body),
        QueryApiBenchIT::rowsText,
        sample.failure(),
        QueryApiBenchIT::someRows);
  }

  private static String someRows(BenchClient.Timed t) {
    return t.json().path("data").size() > 0 ? null : "0 rows";
  }

  private static String range(Instant from, Instant to) {
    return "from=" + from + "&to=" + to;
  }

  private static String rowsText(BenchClient.Timed t) {
    return t.json() == null ? "" : t.json().path("data").size() + " rows";
  }

  private static String xlogText(BenchClient.Timed t) {
    if (t.json() == null) {
      return "";
    }
    JsonNode meta = t.json().path("meta");
    return String.format(
        Locale.ROOT,
        "%,d of %,d points, sampling applied: %s",
        meta.path("shown").asLong(),
        meta.path("total").asLong(),
        meta.path("sampling").path("applied").asBoolean());
  }

  private static String stepsText(BenchClient.Timed t) {
    if (t.json() == null) {
      return "";
    }
    JsonNode profile = t.json().path("data").path("profile");
    return profile.path("state").asString() + ", " + profile.path("steps").size() + " steps";
  }

  // ------------------------------------------------------------------------------------ report

  private static String report(
      QueryDataProfile profile,
      boolean enforce,
      Resources resources,
      Instant started,
      Duration load,
      QueryDataset data,
      Inputs in,
      List<Result> results,
      CpuSampler.Window overall,
      CpuSampler cpu,
      String clickHouse) {
    StringBuilder md = new StringBuilder();
    md.append("# Query API bench (N-07, N-24)\n\n");
    List<String> failed =
        results.stream().filter(r -> r.failure() != null).map(r -> r.c().id()).toList();
    if (enforce) {
      boolean pass = results.stream().allMatch(r -> r.verdict(true).equals("PASS"));
      md.append("Verdict: **")
          .append(pass ? "PASS" : "FAIL")
          .append("** (enforce mode on ")
          .append(profile.generatorName())
          .append(" data: every warm median within its target and every cold call within ")
          .append(ApiBenchVerdict.COLD_FACTOR)
          .append(" x its target, ADR-0010 C-180).\n\n");
    } else {
      md.append("Verdict: **reference (INVALID)**. ")
          .append("Not enforced, ")
          .append(profile.generatorName())
          .append(" data (")
          .append(hours(data.start(), data.end()))
          .append(" h): the numbers show the shape, not the target hardware result on 30 days")
          .append(" of data (-Pserver.apiBench.profile=full -Pserver.apiBench.enforce=true")
          .append(" there).\n\n");
    }
    if (!failed.isEmpty()) {
      md.append("**FAILED cases** (their data or responses miss the load they measure; no")
          .append(" reference number): ")
          .append(String.join(", ", failed))
          .append(".\n\n");
    }
    md.append("Generated ").append(started.truncatedTo(ChronoUnit.SECONDS)).append(".\n\n");

    md.append("## Environment\n\n| Item | Value |\n|---|---|\n");
    row(md, "OS", System.getProperty("os.name") + " " + System.getProperty("os.version"));
    String cpuName = System.getenv("PROCESSOR_IDENTIFIER");
    row(
        md,
        "CPU",
        (cpuName == null ? System.getProperty("os.arch") : cpuName)
            + ", "
            + cpu.processors()
            + " logical processors");
    row(
        md,
        "Memory",
        String.format(Locale.ROOT, "%.1f GiB", cpu.totalMemory() / (double) (1L << 30)));
    row(md, "JVM", System.getProperty("java.vm.name") + " " + System.getProperty("java.version"));
    row(
        md,
        "Server",
        "boot jar, child JVM -Xmx" + resources.serverXmx() + ", one restart per call");
    row(
        md,
        "ClickHouse",
        clickHouse
            + " (Testcontainers, "
            + Resources.gib(resources.clickHouseBytes())
            + " memory limit)");
    row(
        md,
        "PostgreSQL",
        "16 (Testcontainers, " + Resources.gib(resources.postgresBytes()) + " memory limit)");
    row(md, "Machine CPU load during the calls", overall.text());
    md.append('\n');

    md.append("## Data\n\n| Item | Value |\n|---|---|\n");
    row(md, "Generator profile", profile.generatorName() + " (seed " + data.seed() + ")");
    row(
        md,
        "Time span",
        data.start() + " to " + data.end() + " (" + hours(data.start(), data.end()) + " h)");
    row(md, "Placement", data.placement().name().toLowerCase(Locale.ROOT));
    int instances = data.apps().stream().mapToInt(a -> a.instances().size()).sum();
    row(md, "Applications / instances", data.apps().size() + " / " + instances);
    for (Map.Entry<String, Long> e : new java.util.TreeMap<>(data.rows()).entrySet()) {
      row(md, "Rows " + e.getKey(), String.format(Locale.ROOT, "%,d", e.getValue()));
    }
    row(md, "Load (generate and insert)", load.toSeconds() + " s");
    row(md, "Busiest application (dashboard, XLog, Top N)", Integer.toString(in.busyApp()));
    md.append('\n');

    md.append("## Samples (checked with SQL as argos_reader before the measurement)\n\n")
        .append("| Case | Sample | Data | Condition |\n|---|---|---|---|\n");
    sampleRow(
        md,
        "xlog-100k",
        "application " + in.busyApp() + ", the last " + in.xlog().minutes() + " min",
        String.format(Locale.ROOT, "%,d transactions (count, every kind)", in.xlog().total()),
        in.xlog().failure(),
        "count >= 100,000");
    sampleRow(
        md,
        "tx-detail",
        in.tx().failure() == null
            ? "tx " + in.tx().value() + " of application " + in.tx().appId() + " at " + in.tx().ts()
            : "-",
        in.tx().rows() + " profile steps",
        in.tx().failure(),
        "steps > 0, profile captured, ts within 1 s of its tx_id time");
    sampleRow(
        md,
        "trace",
        in.trace().failure() == null
            ? "trace "
                + in.trace().value()
                + " (a callee in application "
                + in.trace().appId()
                + ")"
            : "-",
        in.trace().rows() + " transactions in the last 24 h",
        in.trace().failure(),
        "transactions >= 2");
    sampleRow(
        md,
        "user-search-1h",
        in.user().failure() == null
            ? "a user id of application " + in.user().appId() + " at " + in.user().ts()
            : "-",
        in.user().rows() + " plain-mode rows in the 1 h window",
        in.user().failure(),
        "rows >= 1, unchanged by the C-163 normalization");
    sampleRow(
        md,
        "request-search-1h",
        in.request().failure() == null
            ? "a request id of application " + in.request().appId() + " at " + in.request().ts()
            : "-",
        in.request().rows() + " rows in the 1 h window",
        in.request().failure(),
        "rows >= 1");
    md.append('\n');

    md.append("## Calls\n\n")
        .append("| Call | Request | Target | Cold ms | Warm ms (median of ")
        .append(WARM_RUNS)
        .append(") | Warm runs ms | Status | Wire / JSON bytes | Result | CPU load")
        .append(" | Warm <= target | Cold <= ")
        .append(ApiBenchVerdict.COLD_FACTOR)
        .append(" x target | Verdict |\n")
        .append("|---|---|---|---|---|---|---|---|---|---|---|---|---|\n");
    for (Result r : results) {
      md.append("| ")
          .append(r.c().id())
          .append(" | `")
          .append(r.c().request())
          .append("` | ")
          .append(r.c().requirement())
          .append(" ")
          .append(r.c().target())
          .append(" | ");
      if (r.measured()) {
        BenchClient.Timed last = r.warm().get(r.warm().size() - 1);
        md.append(ms(r.cold().millis()))
            .append(" | ")
            .append(ms(r.warmMedian()))
            .append(" | ")
            .append(String.join(", ", r.warm().stream().map(t -> ms(t.millis())).toList()))
            .append(" | ")
            .append(r.status())
            .append(" | ")
            .append(String.format(Locale.ROOT, "%,d / %,d", last.wireBytes(), last.bodyBytes()))
            .append(" | ")
            .append(r.c().size().apply(last))
            .append(" | ")
            .append(r.cpu().text());
      } else {
        md.append("- | - | - | ").append(r.status()).append(" | - | - | -");
      }
      ApiBenchVerdict times = r.times();
      md.append(" | ")
          .append(times == null ? "-" : times.warm(enforce))
          .append(" | ")
          .append(times == null ? "-" : times.cold(enforce))
          .append(" | ")
          .append(r.verdict(enforce).replace("|", "\\|"))
          .append(" |\n");
    }
    md.append('\n');

    md.append("## Method\n\n")
        .append("- Cold: the first call after a server restart (new JVM from the boot jar, new")
        .append(" connection pools, empty caches), right after the login. ClickHouse is as warm as")
        .append(" the data load and the sample checks left it: the reader account cannot drop its")
        .append(" caches.\n")
        .append("- Warm: the median of the next ")
        .append(WARM_RUNS)
        .append(" identical calls on the same session.\n")
        .append("- Verdict (ADR-0010 C-180): a case passes when its warm median is at most the")
        .append(" target and its cold call at most ")
        .append(ApiBenchVerdict.COLD_FACTOR)
        .append(" times the target; the two columns before the verdict show each condition")
        .append(" (within/above for reference numbers, PASS/FAIL enforced).\n")
        .append("- Time: from sending the request to the whole body read and inflated")
        .append(" (Accept-Encoding: gzip, like a browser), on the same machine (loopback).\n")
        .append("- Samples: each case's data condition (table above) is checked with SQL as")
        .append(" argos_reader before the measurement; a case whose data misses it is FAILED and")
        .append(" not measured. Each measured response (cold and warm) must show the load too:")
        .append(" XLog meta.total >= 100,000, detail profile.state captured with steps, trace")
        .append(" nodes >= 2, searches and Top N at least one row, dashboard transactions > 0;")
        .append(" otherwise the case is FAILED. The response conditions are not checked by an")
        .append(" extra call before the cold one, which would make it warm.\n")
        .append("- XLog window: the minutes that hold 100,000 transactions of the busiest")
        .append(" application at its mean rate of the last day of app_stat_1m (Q03 probe")
        .append(" xlog_window, at most 24 h), confirmed and widened with count(xlog). With more")
        .append(" points than the limit the server samples (A-5, meta.sampling.applied), so")
        .append(" shown is about total / ceil(total / 100,000) plus the error and slow points.\n")
        .append("- Detail sample: Q05 probe tx (the smallest tx_id with steps in profile_step in")
        .append(
            " the last hour), only transactions whose ts is within 1 s of their tx_id time: the")
        .append(" +-2 s lookup of C-50, not the 2-day fallback (T-API-005).\n")
        .append("- Trace sample: Q06 probe trace (6 h before the end, a callee with")
        .append(" caller_app_id != 0, the busiest application first).\n")
        .append("- user_id: the generator stores user ids in hashed mode, which the API does not")
        .append(" search before the application salt exists (C-152, WO-28). Before the")
        .append(" measurement the xlog rows of the sampled user id are switched to plain mode")
        .append(" (ALTER TABLE ... UPDATE as argos_migrator, mutations_sync = 2) and the instances")
        .append(" of its application too; the value is one the C-163 normalization leaves")
        .append(" unchanged. The call runs the statement of a plain id (bloom index on user_id,")
        .append(" then user_id_mode = 'plain') and returns those rows.\n")
        .append(
            "- Targets: 01 N-07 (dashboard 1 h 1 s, XLog 100,000 points 2 s, transaction detail")
        .append(
            " 0.5 s, services and SQL Top N 1 day 1 s) and N-24 (tx id and trace id 0.5 s, user")
        .append(
            " id and request id over 1 h 3 s), on 30 days of data (generator full) on the target")
        .append(" hardware (argos-storage, 8 cores, 16 GB, NVMe): a human runs that with")
        .append(" -Pserver.apiBench=true -Pserver.apiBench.profile=full")
        .append(" -Pserver.apiBench.enforce=true --tests '*QueryApiBenchIT' (docs/bench/gate1.md,")
        .append(
            " WO-53). Enforce defaults to the 13 §7.1 budget: server -Xmx4g, ClickHouse 7.5 GiB")
        .append(
            " and PostgreSQL 1.5 GiB (-Pserver.apiBench.serverXmx and -Pserver.apiBench.chMemory")
        .append(" override the first two); the full load takes hours and the disk of a 30-day")
        .append(" installation. Enforce with any other profile fails before the load.\n");
    return md.toString();
  }

  private static void sampleRow(
      StringBuilder md, String id, String sample, String data, String failure, String condition) {
    md.append("| ")
        .append(id)
        .append(" | ")
        .append(sample.replace("|", "\\|"))
        .append(" | ")
        .append(data.replace("|", "\\|"))
        .append(" | ")
        .append(condition)
        .append(": ")
        .append(failure == null ? "met" : "**not met** (" + failure.replace("|", "\\|") + ")")
        .append(" |\n");
  }

  private static void row(StringBuilder md, String item, String value) {
    md.append("| ").append(item).append(" | ").append(value.replace("|", "\\|")).append(" |\n");
  }

  private static String ms(double millis) {
    return String.format(Locale.ROOT, "%.1f", millis);
  }

  private static String hours(Instant from, Instant to) {
    return String.format(Locale.ROOT, "%.1f", Duration.between(from, to).toMinutes() / 60.0);
  }
}
