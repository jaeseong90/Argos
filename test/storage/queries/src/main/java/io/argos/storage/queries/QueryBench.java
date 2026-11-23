/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.storage.queries;

import io.argos.storage.clickhouse.Account;
import io.argos.storage.clickhouse.MemoryBudget;
import io.argos.storage.queries.dataset.CiDataset;
import io.argos.storage.queries.procedures.CompressionComparison;
import io.argos.storage.queries.procedures.ConcurrentLoad;
import io.argos.storage.queries.procedures.PercentileAccuracy;
import io.argos.storage.queries.procedures.RetentionOps;
import io.argos.storage.schema.SchemaApplier;
import io.argos.storage.schema.SchemaParameters;
import java.io.PrintStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Command line of the storage PoC queries ({@code ./gradlew :test:storage:queries:storageQueries
 * -Pqueries.args="..."}).
 *
 * <pre>
 * server (one of):
 *   --volume NAME [--memory-gib 5]   bundled image on a named Docker volume (restartable: --cold restart)
 *   --url http://host:8123           external server; ARGOS_CH_{READER,WRITER,MIGRATOR}_PASSWORD
 *   (none)                           a fresh container (use with --apply-schema --load-ci-dataset)
 * data:
 *   --database argos --prefix P      schema parameters of the loaded data
 *   --apply-schema                   apply deploy/clickhouse V001 first (idempotent)
 *   --load-ci-dataset                load the deterministic CI dataset (48 h, 1 TPS)
 * plan:
 *   --queries all|none|Q01,Q02,...   timing and consistency files (default all = Q01..Q13)
 *   --procedures all|none|Q14,...    Q05/bloom Q11/cost Q14 Q15 Q16 Q17 Q18 Q19 Q20 (default all)
 *   --warm 3  --cold none|restart|restart+dropcache|command:SHELL
 *   --q14-source synthetic|dataset --q14-apps 2 --q14-per-minute 3000 --q14-days 3
 *   --q14-profile ci                 loaded data is the 1/100 profile: stored p99 by rank (C-221)
 *   --q15-tx 20000 --q15-repeats 100 --q16-rows 20000 --bloom-days 3
 *   --q17-users 10 --q17-interval-s 5 --q17-duration-s 1800 --q17-ingestion TEXT_WITHOUT_SPACES
 *   --q17-window live|fixed          (default: live when --q17-ingestion is given, else fixed)
 *   --q18-wait-s 600 --q18-repeats 2 --q18-copy-hours 24 --q18-settle-s 120
 *   --q20-repeats 2 --q20-settle-s 300
 * output:
 *   --out DIR (report.md, runs.tsv)  --strict (exit 1 when a criterion fails)
 * </pre>
 */
public final class QueryBench {

  private QueryBench() {}

  /** Entry point. */
  public static void main(String[] args) throws Exception {
    PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
    Map<String, String> o = parse(args);
    SchemaParameters schema =
        SchemaParameters.defaults()
            .withDatabase(o.getOrDefault("database", "argos"))
            .withTablePrefix(o.getOrDefault("prefix", ""));
    long memory = (long) (Double.parseDouble(o.getOrDefault("memory-gib", "5")) * MemoryBudget.GIB);
    boolean failed;
    try (MachineLoad load = MachineLoad.start();
        Server server =
            o.containsKey("url")
                ? Server.external(URI.create(o.get("url")), System.getenv())
                : Server.container(o.get("volume"), memory, schema.tablePrefix())) {
      if (o.containsKey("apply-schema")) {
        SchemaApplier.apply(server.client(Account.MIGRATOR), schema);
      }
      if (o.containsKey("load-ci-dataset")) {
        CiDataset.Summary s =
            CiDataset.generate(CiDataset.Config.ci(Instant.now()))
                .load(server.client(Account.WRITER), schema);
        out.println("CI dataset loaded: " + s);
      }
      QueryRunner.Options options =
          QueryRunner.Options.defaults()
              .withWarmRuns(Integer.parseInt(o.getOrDefault("warm", "3")))
              .withCold(ColdStrategy.parse(o.getOrDefault("cold", "none")));
      QueryRunner runner = new QueryRunner(server, schema, options, load);
      QueryCatalog catalog = QueryCatalog.fromRepository();
      List<String> problems = catalog.validate();
      if (!problems.isEmpty()) {
        throw new IllegalStateException("Invalid query catalog: " + problems);
      }
      List<String> parameterProblems = new java.util.ArrayList<>();
      BenchContext ctx = BenchContext.resolveLenient(runner, catalog, parameterProblems);
      parameterProblems.forEach(p -> out.println("WARNING shared parameters: " + p));
      BenchRun.Plan plan = plan(o);
      BenchResults results = BenchRun.run(ctx, plan, load);
      parameterProblems.forEach(p -> results.error("shared parameters: " + p));
      Path report =
          ReportWriter.write(
              results, Path.of(o.getOrDefault("out", "build/reports/storage-queries/bench")));
      for (VariantResult v : results.variants()) {
        out.println(v);
      }
      for (ProcedureResult p : results.procedures()) {
        out.println(p);
      }
      results.errors().forEach(e -> out.println("ERROR " + e));
      out.println("Report: " + report.toAbsolutePath());
      failed = !results.allPassed();
    }
    if (failed && o.containsKey("strict")) {
      System.exit(1);
    }
  }

  static BenchRun.Plan plan(Map<String, String> o) {
    BenchRun.Plan full =
        BenchRun.Plan.full(o.getOrDefault("q17-ingestion", "none").replace('_', ' '));
    Set<String> files = select(o.getOrDefault("queries", "all"), full.files());
    Set<String> procedures = select(o.getOrDefault("procedures", "all"), full.procedures());
    PercentileAccuracy.Options q14 =
        new PercentileAccuracy.Options(
            PercentileAccuracy.Source.valueOf(
                o.getOrDefault("q14-source", "synthetic").toUpperCase(Locale.ROOT)),
            Integer.parseInt(o.getOrDefault("q14-apps", "2")),
            Integer.parseInt(o.getOrDefault("q14-per-minute", "3000")),
            Integer.parseInt(o.getOrDefault("q14-days", "3")),
            Integer.parseInt(o.getOrDefault("q14-days", "3")),
            Integer.parseInt(o.getOrDefault("q14-days", "3")),
            Integer.parseInt(o.getOrDefault("q14-days", "3")),
            Long.parseLong(o.getOrDefault("q14-min-count", "1000")),
            o.getOrDefault("q14-profile", ""));
    String ingestion = o.getOrDefault("q17-ingestion", "none").replace('_', ' ');
    ConcurrentLoad.Options q17 =
        new ConcurrentLoad.Options(
            Integer.parseInt(o.getOrDefault("q17-users", "10")),
            Duration.ofSeconds(Long.parseLong(o.getOrDefault("q17-interval-s", "5"))),
            Duration.ofSeconds(Long.parseLong(o.getOrDefault("q17-duration-s", "1800"))),
            Duration.ofSeconds(10),
            ingestion,
            o.containsKey("q17-window")
                ? ConcurrentLoad.Window.parse(o.get("q17-window"))
                : ConcurrentLoad.Options.defaultWindow(ingestion));
    RetentionOps.Options q18 =
        new RetentionOps.Options(
            Duration.ofSeconds(Long.parseLong(o.getOrDefault("q18-wait-s", "600"))),
            Integer.parseInt(o.getOrDefault("q18-repeats", "2")),
            Integer.parseInt(o.getOrDefault("q18-copy-hours", "24")),
            Duration.ofSeconds(Long.parseLong(o.getOrDefault("q18-settle-s", "120"))));
    CompressionComparison.Options q20 =
        new CompressionComparison.Options(
            Integer.parseInt(o.getOrDefault("q20-repeats", "2")),
            Duration.ofSeconds(Long.parseLong(o.getOrDefault("q20-settle-s", "300"))));
    return new BenchRun.Plan(
        files,
        procedures,
        q14,
        Integer.parseInt(o.getOrDefault("q15-tx", "20000")),
        Integer.parseInt(o.getOrDefault("q15-repeats", "100")),
        Integer.parseInt(o.getOrDefault("q16-rows", "20000")),
        q17,
        q18,
        q20,
        Integer.parseInt(o.getOrDefault("bloom-days", "3")));
  }

  private static Set<String> select(String text, Set<String> all) {
    if (text.equals("all")) {
      return all;
    }
    if (text.equals("none")) {
      return Set.of();
    }
    Set<String> chosen = new LinkedHashSet<>(Arrays.asList(text.split(",", -1)));
    for (String c : chosen) {
      if (!all.contains(c)) {
        throw new IllegalArgumentException("Unknown entry " + c + "; choose from " + all);
      }
    }
    return chosen;
  }

  static Map<String, String> parse(String[] args) {
    Set<String> flags = Set.of("apply-schema", "load-ci-dataset", "strict");
    Map<String, String> o = new LinkedHashMap<>();
    for (int i = 0; i < args.length; i++) {
      if (!args[i].startsWith("--")) {
        throw new IllegalArgumentException("Unexpected argument " + args[i]);
      }
      String key = args[i].substring(2);
      if (flags.contains(key)) {
        o.put(key, "true");
      } else {
        if (i + 1 >= args.length) {
          throw new IllegalArgumentException("Missing value for --" + key);
        }
        o.put(key, args[++i]);
      }
    }
    return o;
  }
}
