/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.load;

import io.argos.proto.v1.AckStatus;
import io.argos.proto.v1.RejectReason;
import io.argos.proto.v1.TransactionBatch;
import io.argos.proto.v1.TransactionRecord;
import io.argos.server.testing.fakeagent.AgentChannels;
import io.argos.server.testing.fakeagent.AgentTokens;
import io.argos.server.testing.fakeagent.Attacks;
import io.argos.server.testing.fakeagent.BatchKind;
import io.argos.server.testing.fakeagent.CallResult;
import io.argos.server.testing.fakeagent.FakeAgent;
import io.argos.server.testing.fakeagent.HelloResult;
import io.argos.server.testing.load.CumulativeHistogram;
import io.argos.server.testing.load.Jcmd;
import io.argos.server.testing.load.PrometheusText;
import io.argos.storage.clickhouse.Account;
import io.argos.storage.clickhouse.ClickHouseHttp;
import io.grpc.Status;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The attacks of 18 §8 under the steady load (T-SEC-11..14, N-10d, WO-29 step A2): gzip bombs (1 MB
 * to 1 GiB) and 5,001-record batches are refused before decoding (SEC-11), 100,000 random services
 * (smoke 10,000) are accepted and quota-trimmed (SEC-12, SEC-13), the instance flood of one token
 * (1,000 Hellos; smoke 200) stops at 100 new instances per app and hour (SEC-14). Meanwhile a quiet
 * instance of another app sends one transaction every 200 ms and its receive-to-{@code
 * argos_reader} latency is measured, the server's resident set is sampled every 250 ms (growth at
 * most 200 MB) and the steady load's ingest lag over the attack is read from the server's
 * histogram. The dictionary flood's cost (rows and relation size of {@code argos.dictionary}, the
 * server's Caffeine nodes in the class histogram, {@code argos_dictionary_overflow_total}) is the
 * C-238 (14) section. The unauthenticated flood blocks the loopback address for 10 minutes and is
 * therefore {@link UnauthFloodPhase}, run last.
 */
final class AttackPhase implements LoadPhase {

  /** 18 §12 T-SEC-11..15: resident set growth during the attacks. */
  static final long RSS_GROWTH_LIMIT = 200L * 1024 * 1024;

  /** N-10d: receive -> query of another instance during the attacks. */
  static final long OTHER_INSTANCE_LAG_MILLIS = 5_000;

  private final List<Criterion> criteria = new ArrayList<>();
  private final List<ReportSection> sections = new ArrayList<>();

  /** Dictionary flood services of a profile (18 §8: 100,000 at full scale). */
  static int dictionaryFloodServices(LoadProfile p) {
    return switch (p) {
      case FULL -> Attacks.DICTIONARY_FLOOD_SERVICES;
      case SMOKE -> 10_000;
      case MINI -> 2_000;
    };
  }

  /**
   * Instance flood Hellos of a profile (18 §8: 1,000 at full scale; above 100 in every profile).
   */
  static int instanceFloodHellos(LoadProfile p) {
    return switch (p) {
      case FULL -> Attacks.INSTANCE_FLOOD_INSTANCES;
      case SMOKE -> 200;
      case MINI -> 150;
    };
  }

  /** Oversized batches of a profile. */
  static int overflowBatches(LoadProfile p) {
    return p == LoadProfile.FULL ? 10 : 3;
  }

  @Override
  public String name() {
    return "attacks";
  }

  @Override
  public void underLoad(LoadContext c) throws Exception {
    LoadOptions o = c.options();
    List<String> selected = new ArrayList<>(o.attacks());
    selected.remove("unauth-flood");
    if (selected.isEmpty()) {
      sections.add(
          new ReportSection(
              ReportSection.PLANNED.get(0), "No attack under load was selected (`--attack`)."));
      return;
    }
    AgentTokens.IssuedToken quietToken = PhaseSupport.app(c, "attack-quiet");
    AgentTokens.IssuedToken attackerToken = PhaseSupport.app(c, "attack-attacker");
    AgentTokens.IssuedToken instanceToken = PhaseSupport.app(c, "attack-instances");
    PrometheusText before = PhaseSupport.scrape(c);
    long dictRowsBefore = PhaseSupport.pgScalar(c, "SELECT count(*) FROM argos.dictionary");
    long dictBytesBefore =
        PhaseSupport.pgScalar(c, "SELECT pg_total_relation_size('argos.dictionary')");
    Jcmd.ClassHistogram histBefore = histogram(c);
    long rssBefore = c.rss().sampleNow().orElse(-1);
    AtomicLong rssMax = new AtomicLong(rssBefore);
    AtomicBoolean attacking = new AtomicBoolean(true);
    CompletableFuture<Void> rssSampler =
        CompletableFuture.runAsync(
            () -> {
              while (attacking.get()) {
                c.rss().sampleNow().ifPresent(v -> rssMax.accumulateAndGet(v, Math::max));
                sleep(250);
              }
            });

    Map<Long, Long> sentAt = new ConcurrentHashMap<>();
    Map<Long, Long> seenAt = new ConcurrentHashMap<>();
    List<String> quietFailures = Collections.synchronizedList(new ArrayList<>());
    ClickHouseHttp reader = c.env().clickHouse(Account.READER);
    long epoch = System.currentTimeMillis() * 1_000 + 900_000;
    List<CallResult> bombs = Collections.synchronizedList(new ArrayList<>());
    List<CallResult> overflows = Collections.synchronizedList(new ArrayList<>());
    List<CallResult> floodCalls = Collections.synchronizedList(new ArrayList<>());
    List<HelloResult> instanceHellos = new ArrayList<>();
    long startNanos = System.nanoTime();
    try (FakeAgent quiet =
            PhaseSupport.agent(
                PhaseSupport.channel(c, AgentChannels.Options.defaults()),
                true,
                quietToken,
                "attack-quiet",
                "quiet-1",
                epoch + 1,
                4_000 + 1,
                false);
        FakeAgent bomber =
            PhaseSupport.agent(
                PhaseSupport.channel(c, AgentChannels.Options.defaults().withPreCompressedGzip()),
                true,
                attackerToken,
                "attack-attacker",
                "bomber",
                epoch + 2,
                4_000 + 2,
                true);
        FakeAgent overflow =
            PhaseSupport.agent(
                PhaseSupport.channel(c, AgentChannels.Options.defaults()),
                true,
                attackerToken,
                "attack-attacker",
                "overflow",
                epoch + 3,
                4_000 + 3,
                false);
        FakeAgent flood =
            PhaseSupport.agent(
                PhaseSupport.channel(c, AgentChannels.Options.defaults()),
                true,
                attackerToken,
                "attack-attacker",
                "dict-flood",
                epoch + 4,
                4_000 + 4,
                false);
        FakeAgent instances =
            PhaseSupport.agent(
                PhaseSupport.channel(c, AgentChannels.Options.defaults()),
                true,
                instanceToken,
                "attack-instances",
                "instances",
                epoch + 5,
                4_000 + 5,
                false)) {
      for (FakeAgent a : List.of(quiet, bomber, overflow, flood)) {
        HelloResult hello = a.connect();
        if (!hello.accepted()) {
          throw new IllegalStateException("attack phase: " + a + " not accepted: " + hello);
        }
      }
      CompletableFuture<Void> quietSender =
          CompletableFuture.runAsync(
              () -> {
                while (attacking.get()) {
                  TransactionRecord tx = quiet.records().transaction().build();
                  sentAt.put(tx.getTxId(), System.nanoTime());
                  CallResult r = quiet.sendTransactions(quiet.batches().transactions(List.of(tx)));
                  if (r.ackStatus() != AckStatus.ACK_OK) {
                    quietFailures.add(r.toString());
                  }
                  sleep(200);
                }
              });
      CompletableFuture<Void> poller =
          CompletableFuture.runAsync(
              () -> {
                long deadline = Long.MAX_VALUE;
                while (System.nanoTime() < deadline) {
                  try {
                    List<List<String>> rows =
                        reader.rows(
                            "SELECT toString(tx_id) FROM argos.xlog WHERE app_id = "
                                + quietToken.appId());
                    long now = System.nanoTime();
                    for (List<String> row : rows) {
                      seenAt.putIfAbsent(Long.parseUnsignedLong(row.get(0)), now);
                    }
                  } catch (RuntimeException e) {
                    quietFailures.add("poll: " + e);
                  }
                  if (!attacking.get()) {
                    if (seenAt.keySet().containsAll(sentAt.keySet())) {
                      return;
                    }
                    if (deadline == Long.MAX_VALUE) {
                      deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(2);
                    }
                  }
                  sleep(100);
                }
              });

      List<CompletableFuture<?>> attacks = new ArrayList<>();
      if (selected.contains("gzip-bomb")) {
        byte[] bomb = Attacks.gzipBomb(Attacks.GZIP_BOMB_INFLATED_BYTES);
        attacks.add(
            CompletableFuture.runAsync(
                () -> {
                  for (int i = 0; i < 3; i++) {
                    bombs.add(
                        bomber.sendRaw(
                            BatchKind.TRANSACTIONS,
                            bomb,
                            FakeAgent.CallSpec.defaults()
                                .withCompression(FakeAgent.Compression.GZIP)
                                .withDeadline(Duration.ofSeconds(60))));
                  }
                }));
      }
      if (selected.contains("batch-overflow")) {
        int n = overflowBatches(o.profile());
        attacks.add(
            CompletableFuture.runAsync(
                () -> {
                  for (int i = 0; i < n; i++) {
                    TransactionBatch big =
                        Attacks.oversizedTransactionBatch(overflow, Attacks.OVERFLOW_RECORDS);
                    overflows.add(overflow.sendTransactions(big));
                  }
                }));
      }
      int floodServices = dictionaryFloodServices(o.profile());
      if (selected.contains("dict-flood")) {
        attacks.add(
            CompletableFuture.runAsync(
                () -> {
                  for (TransactionBatch b :
                      Attacks.randomServiceTransactions(flood, floodServices, o.seed() + 4_242)) {
                    floodCalls.add(flood.sendTransactions(b));
                  }
                }));
      }
      int instanceHellosPlanned = instanceFloodHellos(o.profile());
      CompletableFuture<List<HelloResult>> instanceFlood =
          selected.contains("instance-flood")
              ? CompletableFuture.supplyAsync(
                  () -> Attacks.instanceFlood(instances, "flood", instanceHellosPlanned))
              : CompletableFuture.completedFuture(List.of());
      attacks.add(instanceFlood);
      CompletableFuture.allOf(attacks.toArray(CompletableFuture[]::new)).get(15, TimeUnit.MINUTES);
      instanceHellos.addAll(instanceFlood.get());
      TimeUnit.SECONDS.sleep(5);
      attacking.set(false);
      quietSender.get(1, TimeUnit.MINUTES);
      poller.get(3, TimeUnit.MINUTES);
      rssSampler.get(1, TimeUnit.MINUTES);
    } finally {
      attacking.set(false);
    }
    long attackSeconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - startNanos);
    PrometheusText after = PhaseSupport.scrape(c);
    // The dictionary requests of the flood are answered asynchronously: give the writer a moment.
    TimeUnit.SECONDS.sleep(5);
    long dictRowsAfter = PhaseSupport.pgScalar(c, "SELECT count(*) FROM argos.dictionary");
    long dictBytesAfter =
        PhaseSupport.pgScalar(c, "SELECT pg_total_relation_size('argos.dictionary')");
    Jcmd.ClassHistogram histAfter = histogram(c);

    judge(
        o,
        selected,
        bombs,
        overflows,
        floodCalls,
        instanceHellos,
        instanceFloodHellos(o.profile()),
        before,
        after,
        rssBefore,
        rssMax.get(),
        sentAt,
        seenAt,
        quietFailures,
        attackSeconds);
    dictionarySection(
        o,
        selected,
        before,
        after,
        dictRowsBefore,
        dictRowsAfter,
        dictBytesBefore,
        dictBytesAfter,
        histBefore,
        histAfter);
  }

  private void judge(
      LoadOptions o,
      List<String> selected,
      List<CallResult> bombs,
      List<CallResult> overflows,
      List<CallResult> floodCalls,
      List<HelloResult> instanceHellos,
      int instanceHellosPlanned,
      PrometheusText before,
      PrometheusText after,
      long rssBefore,
      long rssMax,
      Map<Long, Long> sentAt,
      Map<Long, Long> seenAt,
      List<String> quietFailures,
      long attackSeconds) {
    List<List<String>> rows = new ArrayList<>();
    rows.add(List.of("Attack", "Sent", "Outcome", "Expected"));
    if (selected.contains("gzip-bomb")) {
      boolean ok =
          bombs.size() == 3
              && bombs.stream()
                  .allMatch(
                      r ->
                          r.code() == Status.Code.RESOURCE_EXHAUSTED
                              && "message_size".equals(r.rejectReason()));
      rows.add(
          List.of(
              "gzip-bomb",
              "3 x 1 MB (inflates to 1 GiB)",
              outcomes(bombs),
              "RESOURCE_EXHAUSTED" + " message_size"));
      criteria.add(
          PhaseSupport.invariant(
              "T-SEC-11",
              "SEC-11",
              "gzip bomb under load refused before decoding",
              "every call RESOURCE_EXHAUSTED, reason message_size",
              outcomes(bombs),
              !bombs.isEmpty(),
              ok,
              "18 §8 gzip-bomb, 4 MiB after decompression"));
    }
    if (selected.contains("batch-overflow")) {
      boolean ok =
          !overflows.isEmpty()
              && overflows.stream()
                  .allMatch(
                      r ->
                          r.code() == Status.Code.RESOURCE_EXHAUSTED
                              && "batch_items".equals(r.rejectReason()));
      rows.add(
          List.of(
              "batch-overflow",
              overflows.size() + " x 5,001 records",
              outcomes(overflows),
              "RESOURCE_EXHAUSTED batch_items"));
      criteria.add(
          PhaseSupport.invariant(
              "T-SEC-11",
              "SEC-11",
              "5,001-record batches under load refused",
              "every call RESOURCE_EXHAUSTED, reason batch_items",
              outcomes(overflows),
              !overflows.isEmpty(),
              ok,
              "18 §8 batch-overflow, 11 §4.4"));
    }
    if (selected.contains("dict-flood")) {
      long dropped =
          floodCalls.stream()
              .filter(CallResult::ok)
              .mapToLong(r -> r.ack().getDroppedItems())
              .sum();
      boolean ok = !floodCalls.isEmpty() && floodCalls.stream().allMatch(CallResult::ok);
      rows.add(
          List.of(
              "dict-flood",
              String.format(
                  Locale.ROOT,
                  "%,d random services in %d batches",
                  dictionaryFloodServices(o.profile()),
                  floodCalls.size()),
              outcomes(floodCalls) + String.format(Locale.ROOT, ", %,d items dropped", dropped),
              "accepted, quota-trimmed (ACK_OK / ACK_PARTIAL_DROP)"));
      criteria.add(
          PhaseSupport.invariant(
              "T-SEC-13",
              "SEC-12, SEC-13",
              "dictionary flood under load accepted within the instance quota",
              "every call answered OK (ACK_OK or ACK_PARTIAL_DROP)",
              outcomes(floodCalls),
              !floodCalls.isEmpty(),
              ok,
              "18 §8 dict-flood; quota drops are counted, the per-app cap is T-SV-014"));
    }
    if (selected.contains("instance-flood")) {
      long accepted = instanceHellos.stream().filter(HelloResult::accepted).count();
      boolean ok =
          instanceHellos.size() == instanceHellosPlanned
              && accepted == 100
              && instanceHellos.subList(0, Math.min(100, instanceHellos.size())).stream()
                  .allMatch(HelloResult::accepted)
              && instanceHellos.stream()
                  .filter(h -> !h.accepted())
                  .allMatch(
                      h ->
                          h.rejectReason() == RejectReason.INSTANCE_LIMIT
                              && h.code() == Status.Code.RESOURCE_EXHAUSTED);
      String measured =
          String.format(
              Locale.ROOT,
              "%d accepted, %d refused (%s)",
              accepted,
              instanceHellos.size() - accepted,
              helloOutcomes(instanceHellos));
      rows.add(
          List.of(
              "instance-flood",
              instanceHellos.size() + " Hellos with new instance names",
              measured,
              "100 accepted, the rest INSTANCE_LIMIT"));
      criteria.add(
          PhaseSupport.invariant(
              "T-SEC-14",
              "SEC-14",
              "instance flood under load capped at 100 new instances per app and hour",
              "exactly 100 accepted, the rest INSTANCE_LIMIT / RESOURCE_EXHAUSTED",
              measured,
              !instanceHellos.isEmpty(),
              ok,
              "18 §8 instance-flood; per-token session limits raised for the run so that the"
                  + " per-app hourly cap is what stops it"));
    }

    // Other instances: the quiet instance's transactions and the steady load's ingest lag.
    List<Long> latencies = new ArrayList<>();
    sentAt.forEach(
        (tx, t) -> {
          Long seen = seenAt.get(tx);
          latencies.add(seen == null ? Long.MAX_VALUE : TimeUnit.NANOSECONDS.toMillis(seen - t));
        });
    latencies.sort(null);
    long missing = latencies.stream().filter(v -> v == Long.MAX_VALUE).count();
    long max = latencies.isEmpty() ? -1 : latencies.get(latencies.size() - 1);
    long p50 = latencies.isEmpty() ? -1 : latencies.get((latencies.size() - 1) / 2);
    String quiet =
        latencies.isEmpty()
            ? "n/a"
            : String.format(
                Locale.ROOT,
                "%d transactions, p50 %d ms, max %s, %d not visible, %d calls not ACK_OK",
                latencies.size(),
                p50,
                missing > 0 ? "not visible" : max + " ms",
                missing,
                quietFailures.size());
    criteria.add(
        PhaseSupport.measurement(
            o,
            "T-SEC-11..14",
            "N-10d",
            "another app's transactions reach argos_reader during the attacks",
            "<= 5 s",
            quiet,
            !latencies.isEmpty(),
            missing == 0 && max <= OTHER_INSTANCE_LAG_MILLIS && quietFailures.isEmpty(),
            "quiet instance, one transaction every 200 ms, polled every 100 ms"));
    CumulativeHistogram lag =
        after
            .histogram("argos_ingest_lag_seconds")
            .minus(before.histogram("argos_ingest_lag_seconds"));
    String steady =
        lag.count() <= 0
            ? "n/a"
            : String.format(
                Locale.ROOT,
                "p50 %s, p99 %s, %,.0f observations",
                Format.seconds(lag.quantile(0.5)),
                Format.seconds(lag.quantile(0.99)),
                lag.count());
    criteria.add(
        PhaseSupport.measurement(
            o,
            "T-SEC-11..14",
            "N-10d",
            "steady load ingest lag p99 during the attacks",
            "<= 5 s",
            steady,
            lag.count() > 0,
            lag.quantile(0.99) <= 5.0,
            "argos_ingest_lag_seconds difference over the attack phase (every app)"));
    long growth = rssBefore < 0 || rssMax < 0 ? -1 : Math.max(0, rssMax - rssBefore);
    criteria.add(
        PhaseSupport.measurement(
            o,
            "T-SEC-11",
            "SEC-11, N-10d",
            "server resident set growth during the attacks",
            "<= 200 MB",
            growth < 0
                ? "n/a"
                : "+"
                    + Format.bytes(growth)
                    + " (before "
                    + Format.bytes(rssBefore)
                    + ", max "
                    + Format.bytes(rssMax)
                    + ")",
            growth >= 0,
            growth >= 0 && growth <= RSS_GROWTH_LIMIT,
            "resident set sampled every 250 ms"));

    StringBuilder md = new StringBuilder();
    md.append(
        String.format(
            Locale.ROOT,
            "Run after the measured window while the steady load continued; %d s in total. Apps"
                + " `attack-quiet`, `attack-attacker`, `attack-instances` (outside the D-13"
                + " count). Sizes of profile `%s`%s.%n%n",
            attackSeconds,
            o.profile().id(),
            o.profile() == LoadProfile.FULL
                ? " (18 §8 full sizes)"
                : " (reduced, 18 §8 sizes at full)"));
    md.append(PhaseSupport.table(rows)).append('\n');
    md.append("| Item | Value |\n|---|---|\n");
    md.append("| Quiet instance (another app) | ").append(Format.cell(quiet)).append(" |\n");
    md.append("| Steady load ingest lag during the attacks | ")
        .append(Format.cell(steady))
        .append(" |\n");
    md.append("| argos_collector_rejected_total increase by reason | ")
        .append(
            Format.cell(
                PhaseSupport.counts(delta(before, after, "argos_collector_rejected_total"))))
        .append(" |\n");
    md.append("| argos_collector_dropped_total increase by reason | ")
        .append(
            Format.cell(PhaseSupport.counts(delta(before, after, "argos_collector_dropped_total"))))
        .append(" |\n");
    md.append("| Server resident set | ")
        .append(
            Format.cell(
                growth < 0
                    ? "n/a"
                    : "before " + Format.bytes(rssBefore) + ", max " + Format.bytes(rssMax)))
        .append(" |\n");
    if (!quietFailures.isEmpty()) {
      md.append("\nQuiet instance problems: ")
          .append(
              Format.cell(
                  String.join("; ", quietFailures.subList(0, Math.min(5, quietFailures.size())))))
          .append('\n');
    }
    md.append(
        "\nThe unauthenticated Hello flood (T-SEC-15) blocks the loopback address that every fake"
            + " agent shares, so it runs last, after the load and the other phases (section"
            + " below).\n");
    sections.add(new ReportSection(ReportSection.PLANNED.get(0), md.toString()));
  }

  private void dictionarySection(
      LoadOptions o,
      List<String> selected,
      PrometheusText before,
      PrometheusText after,
      long rowsBefore,
      long rowsAfter,
      long bytesBefore,
      long bytesAfter,
      Jcmd.ClassHistogram histBefore,
      Jcmd.ClassHistogram histAfter) {
    if (!selected.contains("dict-flood")) {
      return;
    }
    long services = dictionaryFloodServices(o.profile());
    long rows = rowsBefore < 0 || rowsAfter < 0 ? -1 : rowsAfter - rowsBefore;
    long bytes = bytesBefore < 0 || bytesAfter < 0 ? -1 : bytesAfter - bytesBefore;
    long caffeineBefore = caffeine(histBefore);
    long caffeineAfter = caffeine(histAfter);
    long heapBefore = histBefore == null ? -1 : histBefore.totalBytes();
    long heapAfter = histAfter == null ? -1 : histAfter.totalBytes();
    double overflow =
        after.sum("argos_dictionary_overflow_total")
            - before.sum("argos_dictionary_overflow_total");
    StringBuilder md = new StringBuilder();
    md.append("| Item | Value |\n|---|---|\n");
    md.append(
        String.format(
            Locale.ROOT,
            "| Random services sent | %,d |%n| New rows in `argos.dictionary` | %s |%n",
            services,
            rows < 0 ? "n/a" : String.format(Locale.ROOT, "%,d", rows)));
    md.append(
        String.format(
            Locale.ROOT,
            "| `pg_total_relation_size('argos.dictionary')` | %s -> %s (%s%s) |%n",
            Format.bytes(bytesBefore),
            Format.bytes(bytesAfter),
            PhaseSupport.signedBytes(bytes < 0 ? Long.MIN_VALUE : bytes),
            rows > 0 && bytes >= 0
                ? String.format(Locale.ROOT, ", %,d bytes per new row", bytes / rows)
                : ""));
    md.append(
        String.format(
            Locale.ROOT,
            "| Server heap, Caffeine cache classes (class histogram) | %s -> %s (%s) |%n",
            Format.bytes(caffeineBefore),
            Format.bytes(caffeineAfter),
            PhaseSupport.signedBytes(
                caffeineBefore < 0 || caffeineAfter < 0
                    ? Long.MIN_VALUE
                    : caffeineAfter - caffeineBefore)));
    md.append(
        String.format(
            Locale.ROOT,
            "| Server live heap after a full GC | %s -> %s (%s) |%n",
            Format.bytes(heapBefore),
            Format.bytes(heapAfter),
            PhaseSupport.signedBytes(
                heapBefore < 0 || heapAfter < 0 ? Long.MIN_VALUE : heapAfter - heapBefore)));
    md.append(
        String.format(
            Locale.ROOT, "| argos_dictionary_overflow_total increase | %,.0f |%n", overflow));
    md.append(
        "\nThe live heap difference covers everything the steady load holds between the two"
            + " histograms, not only the dictionary; the Caffeine classes are the resolver's known"
            + " cache (and other Caffeine caches of the server). The nightly pre-clean-up"
            + " (`DictionaryCleanupJob`, 03:30, `last_seen` older than 90 days, 10,000 rows per"
            + " statement) holds one batch of keys at a time; its delete cost for 100,000 rows is"
            + " the `[wo29-dict-cleanup]` line of `DictionaryCleanupJobIT` (T-SV-014f). The rows"
            + " of this run are fresh and are not deleted by it.\n");
    sections.add(new ReportSection(ReportSection.PLANNED.get(2), md.toString()));
  }

  private static long caffeine(Jcmd.ClassHistogram h) {
    return h == null ? -1 : h.bytes(n -> n.startsWith("com.github.benmanes.caffeine."));
  }

  private static Jcmd.ClassHistogram histogram(LoadContext c) {
    try {
      return Jcmd.parseClassHistogram(Jcmd.run(c.server().pid(), 300, "GC.class_histogram"));
    } catch (RuntimeException e) {
      LoadRunner.log("jcmd GC.class_histogram failed: " + e);
      return null;
    }
  }

  static Map<String, Long> delta(PrometheusText before, PrometheusText after, String name) {
    Map<String, Double> b = before.sumBy(name, "reason");
    Map<String, Long> out = new LinkedHashMap<>();
    after
        .sumBy(name, "reason")
        .forEach(
            (k, v) -> {
              long d = Math.round(v - b.getOrDefault(k, 0.0));
              if (d != 0) {
                out.put(k.isEmpty() ? "(no reason)" : k, d);
              }
            });
    return out;
  }

  private static String outcomes(List<CallResult> calls) {
    Map<String, Long> counts = new LinkedHashMap<>();
    synchronized (calls) {
      for (CallResult r : calls) {
        String key =
            r.ok()
                ? String.valueOf(r.ackStatus())
                : r.code() + (r.rejectReason() == null ? "" : " " + r.rejectReason());
        counts.merge(key, 1L, Long::sum);
      }
    }
    return PhaseSupport.counts(counts);
  }

  private static String helloOutcomes(List<HelloResult> hellos) {
    Map<String, Long> counts = new LinkedHashMap<>();
    for (HelloResult h : hellos) {
      if (!h.accepted()) {
        counts.merge(h.rejectReason() + "/" + h.code(), 1L, Long::sum);
      }
    }
    return PhaseSupport.counts(counts);
  }

  private static void sleep(long ms) {
    try {
      TimeUnit.MILLISECONDS.sleep(ms);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  @Override
  public List<ReportSection> sections() {
    return List.copyOf(sections);
  }

  @Override
  public List<Criterion> criteria() {
    return List.copyOf(criteria);
  }
}
