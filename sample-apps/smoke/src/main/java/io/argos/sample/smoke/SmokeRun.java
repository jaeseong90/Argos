/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.smoke;

import io.argos.sample.common.catalog.CatalogEntry;
import io.argos.sample.common.catalog.Catalogs;
import io.argos.sample.common.catalog.Check;
import io.argos.sample.common.seed.Seeds;
import io.argos.sample.extstub.ExtStub;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * One smoke variant: start ext-stub (unless an URL is given), start the app N times (startup
 * measurement), run the catalog on the first start, evaluate the variant expectations, write the
 * report.
 */
final class SmokeRun {

  private final SmokeOptions options;
  private final SmokeClient client = new SmokeClient();
  private final List<EntryResult> results = new ArrayList<>();
  private final List<Map<String, Object>> startups = new ArrayList<>();
  private final List<String> problems = new ArrayList<>();
  private final List<String> notes = new ArrayList<>();
  private Map<String, Object> runtime = new LinkedHashMap<>();

  SmokeRun(SmokeOptions options) {
    this.options = options;
  }

  /** Returns the process exit code: 0 all passed, 1 failures. */
  int run() throws IOException, InterruptedException {
    Files.createDirectories(options.reportDir);
    ExtStub stub = null;
    String extUrl = options.extStubUrl;
    if (extUrl == null) {
      stub = ExtStub.start(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 32);
      extUrl = stub.baseUri().toString();
    }
    Map<String, Object> artifact = new LinkedHashMap<>();
    try {
      Map<String, String> env = new LinkedHashMap<>();
      env.put("SAMPLE_EXT_URL", extUrl);
      if (options.seedEnvironment) {
        env.putAll(Seeds.environment());
      }
      env.putAll(options.env);
      if (options.kind.equals("url")) {
        runCatalog(options.url, "");
        notes.add("--kind url: no startup measurement, log checks (LOG_CONTAINS) not evaluated");
      } else {
        artifact = ArtifactStats.of(options);
        for (int run = 1; run <= options.startupRuns; run++) {
          startOnce(env, run);
        }
      }
    } finally {
      if (stub != null) {
        stub.close();
      }
    }
    SmokeReport report =
        new SmokeReport(options, artifact, startups, runtime, results, problems, notes);
    report.write();
    System.out.println(report.summaryLine());
    return report.passed() ? 0 : 1;
  }

  private void startOnce(Map<String, String> env, int run)
      throws IOException, InterruptedException {
    Map<String, Object> machine = MachineLoad.sample();
    Path runDir = options.reportDir.resolve("run-" + run);
    AppProcess.deleteTree(runDir);
    try (AppProcess app = AppProcess.start(options, env, runDir, "app")) {
      Map<String, Object> startup = new LinkedHashMap<>(app.startup());
      startup.put("run", run);
      startup.put("machine", machine);
      startups.add(startup);
      if (run == 1) {
        for (String context : options.contexts) {
          runCatalog(app.baseUrl(context), context);
        }
        checkLog(app);
      }
    } catch (IllegalStateException e) {
      problems.add("run " + run + ": " + e.getMessage());
    } finally {
      AppProcess.deleteTree(runDir.resolve("tmp"));
    }
  }

  private void runCatalog(String baseUrl, String context) throws InterruptedException {
    List<CatalogEntry> catalog = Catalogs.byName(options.catalog);
    if (catalog.isEmpty()) {
      return;
    }
    Map<String, Object> before = fetchRuntime(baseUrl);
    if (options.expectClients != null) {
      problems.addAll(clientProblems(before, options.expectClients, context));
    }
    Set<String> capabilities = new HashSet<>();
    if (before.get("capabilities") instanceof List<?> list) {
      list.forEach(c -> capabilities.add(String.valueOf(c)));
    }
    List<CatalogEntry> sequential = new ArrayList<>();
    List<CatalogEntry> concurrent = new ArrayList<>();
    List<CatalogEntry> exclusive = new ArrayList<>();
    for (CatalogEntry entry : catalog) {
      if (!options.only.isEmpty() && options.only.stream().noneMatch(entry.id()::startsWith)) {
        continue;
      }
      if (entry.requires() != null && !capabilities.contains(entry.requires())) {
        EntryResult skipped = new EntryResult(entry, context);
        skipped.skipped = "app does not report " + entry.requires();
        results.add(skipped);
        continue;
      }
      switch (entry.phase()) {
        case SEQUENTIAL -> sequential.add(entry);
        case CONCURRENT -> concurrent.add(entry);
        case EXCLUSIVE -> exclusive.add(entry);
      }
    }
    for (int round = 0; round < options.repeat; round++) {
      runPhases(baseUrl, context, sequential, concurrent, exclusive);
    }
    runtime = fetchRuntime(baseUrl);
    problems.addAll(runtimeProblems(runtime, options.expectRuntime, context));
  }

  /**
   * GET /api/runtime before the catalog: the outgoing-call clients the app reports must be exactly
   * the declared ones (sampleApp.smoke.expectClients, the app's 18 §4.1 stack, in any order).
   * Without this, a client missing from the app would only turn its pay entries into SKIPPED.
   */
  static List<String> clientProblems(
      Map<String, Object> runtime, Set<String> expected, String context) {
    Set<String> actual = new TreeSet<>();
    if (runtime.get("clients") instanceof List<?> list) {
      list.forEach(c -> actual.add(String.valueOf(c)));
    }
    Set<String> wanted = new TreeSet<>(expected);
    if (actual.equals(wanted)) {
      return List.of();
    }
    Set<String> missing = new TreeSet<>(wanted);
    missing.removeAll(actual);
    Set<String> undeclared = new TreeSet<>(actual);
    undeclared.removeAll(wanted);
    return List.of(
        (context.isEmpty() ? "" : context + ": ")
            + "GET /api/runtime clients = "
            + actual
            + ", expected the app's stack "
            + wanted
            + " (missing "
            + missing
            + ", not declared "
            + undeclared
            + ")");
  }

  /**
   * GET /api/runtime after the catalog: the variant's expected fields, and one rule for every app:
   * with the routing data source switched on, the catalog's connections must have gone through it
   * ({@code routingLookups} &gt; 0), otherwise the app bypassed the chain it reports.
   */
  static List<String> runtimeProblems(
      Map<String, Object> runtime, Map<String, String> expectRuntime, String context) {
    String prefix = context.isEmpty() ? "" : context + ": ";
    List<String> found = new ArrayList<>();
    for (Map.Entry<String, String> expected : expectRuntime.entrySet()) {
      Object actual = runtime.get(expected.getKey());
      if (!expected.getValue().equals(MiniJson.scalar(actual))) {
        found.add(
            prefix
                + "GET /api/runtime "
                + expected.getKey()
                + " = "
                + MiniJson.scalar(actual)
                + ", expected "
                + expected.getValue());
      }
    }
    if ("true".equals(MiniJson.scalar(runtime.get("routingDataSource")))) {
      String lookups = MiniJson.scalar(runtime.get("routingLookups"));
      if (lookups.equals("0") || lookups.equals("null")) {
        found.add(
            prefix
                + "GET /api/runtime routingDataSource = true but routingLookups = "
                + lookups
                + ": requests bypassed the routing DataSource");
      }
    }
    return found;
  }

  /** SEQUENTIAL entries one by one while CONCURRENT ones run in parallel; then EXCLUSIVE ones. */
  private void runPhases(
      String baseUrl,
      String context,
      List<CatalogEntry> sequential,
      List<CatalogEntry> concurrent,
      List<CatalogEntry> exclusive)
      throws InterruptedException {
    ExecutorService pool = Executors.newFixedThreadPool(16);
    try {
      List<Future<EntryResult>> running = new ArrayList<>();
      for (CatalogEntry entry : concurrent) {
        running.add(pool.submit(() -> client.call(baseUrl, context, entry, options.expectBody)));
      }
      for (CatalogEntry entry : sequential) {
        results.add(client.call(baseUrl, context, entry, options.expectBody));
      }
      for (int i = 0; i < running.size(); i++) {
        try {
          results.add(running.get(i).get(180, TimeUnit.SECONDS));
        } catch (ExecutionException | TimeoutException e) {
          EntryResult failed = new EntryResult(concurrent.get(i), context);
          failed.failures.add("call did not finish: " + e);
          results.add(failed);
        }
      }
    } finally {
      pool.shutdownNow();
    }
    for (CatalogEntry entry : exclusive) {
      results.add(client.call(baseUrl, context, entry, options.expectBody));
    }
  }

  private Map<String, Object> fetchRuntime(String baseUrl) throws InterruptedException {
    CatalogEntry runtimeEntry = CatalogEntry.get("runtime.fetch", "/api/runtime").build();
    EntryResult result = client.call(baseUrl, "", runtimeEntry, Map.of());
    return result.status == 200 ? MiniJson.parseObjectOrEmpty(result.body) : new LinkedHashMap<>();
  }

  /** LOG_CONTAINS checks, against the app log after every call. */
  private void checkLog(AppProcess app) throws IOException, InterruptedException {
    Thread.sleep(300); // flush of the last log lines
    String log = app.readLog();
    for (EntryResult result : results) {
      for (Check check : result.entry.checks()) {
        if (check.type() == Check.Type.LOG_CONTAINS
            && result.skipped == null
            && !log.contains(check.value())) {
          result.failures.add(check.toString());
        }
      }
    }
  }
}
