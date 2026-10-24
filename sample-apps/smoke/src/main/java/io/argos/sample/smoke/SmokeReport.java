/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.smoke;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes {@code smoke.json} and {@code smoke.md} into the report directory and the one-line summary
 * printed by the Gradle task. The numbers of the WO-01 report table (jar count, class count,
 * startup time without an agent, machine load) come from here.
 */
final class SmokeReport {

  private final SmokeOptions options;
  private final Map<String, Object> artifact;
  private final List<Map<String, Object>> startups;
  private final Map<String, Object> runtime;
  private final List<EntryResult> results;
  private final List<String> problems;
  private final List<String> notes;

  SmokeReport(
      SmokeOptions options,
      Map<String, Object> artifact,
      List<Map<String, Object>> startups,
      Map<String, Object> runtime,
      List<EntryResult> results,
      List<String> problems,
      List<String> notes) {
    this.options = options;
    this.artifact = artifact;
    this.startups = startups;
    this.runtime = runtime;
    this.results = results;
    this.problems = problems;
    this.notes = notes;
  }

  long count(String result) {
    return results.stream().filter(r -> r.toMap().get("result").equals(result)).count();
  }

  boolean passed() {
    boolean started = options.kind.equals("url") || !startups.isEmpty();
    if (!started || !problems.isEmpty() || count("FAIL") > 0) {
      return false;
    }
    return options.catalog.equals("none") || count("PASS") > 0;
  }

  Long median(String field) {
    List<Long> values = new ArrayList<>();
    for (Map<String, Object> startup : startups) {
      if (startup.get(field) instanceof Number n) {
        values.add(n.longValue());
      }
    }
    if (values.isEmpty()) {
      return null;
    }
    values.sort(Long::compare);
    int mid = values.size() / 2;
    return values.size() % 2 == 1 ? values.get(mid) : (values.get(mid - 1) + values.get(mid)) / 2;
  }

  void write() throws IOException {
    Map<String, Object> json = new LinkedHashMap<>();
    json.put("app", options.app);
    json.put("variant", options.variant);
    json.put("result", passed() ? "PASS" : "FAIL");
    json.put("finishedAt", Instant.now().toString());
    json.put("kind", options.kind);
    json.put("catalog", options.catalog);
    json.put("appJdk", options.appJdk);
    json.put("appJava", options.appJava);
    json.put("environment", new ArrayList<>(options.env.keySet()));
    json.put("seedEnvironment", options.seedEnvironment);
    json.put("jvmArgs", options.jvmArgs);
    json.put("contexts", options.contexts);
    json.put("artifact", artifact);
    Map<String, Object> startup = new LinkedHashMap<>();
    startup.put("runs", startups.size());
    startup.put("medianSpawnToPortMs", median("spawnToPortMs"));
    startup.put("medianSpawnToReadyMs", median("spawnToReadyMs"));
    startup.put("medianProcessStartToPortMs", median("processStartToPortMs"));
    startup.put("details", startups);
    json.put("startup", startup);
    json.put("runtime", runtime);
    json.put("problems", problems);
    json.put("notes", notes);
    json.put("only", options.only);
    json.put("repeat", options.repeat);
    if (options.expectClients != null) {
      json.put("expectClients", new ArrayList<>(options.expectClients));
    }
    Map<String, Object> summary = new LinkedHashMap<>();
    summary.put("passed", count("PASS"));
    summary.put("failed", count("FAIL"));
    summary.put("skipped", count("SKIPPED"));
    json.put("summary", summary);
    List<Object> entries = new ArrayList<>();
    results.forEach(r -> entries.add(r.toMap()));
    json.put("results", entries);
    Files.writeString(
        options.reportDir.resolve("smoke.json"), MiniJson.write(json), StandardCharsets.UTF_8);
    Files.writeString(options.reportDir.resolve("smoke.md"), markdown(), StandardCharsets.UTF_8);
  }

  private String markdown() {
    StringBuilder md = new StringBuilder();
    md.append("# Smoke ").append(options.app).append(" / ").append(options.variant).append("\n\n");
    md.append("Result: **").append(passed() ? "PASS" : "FAIL").append("**. ");
    md.append(summaryLine()).append("\n\n");
    for (String problem : problems) {
      md.append("- Problem: ").append(problem.replace("\n", " ")).append('\n');
    }
    for (String note : notes) {
      md.append("- Note: ").append(note).append('\n');
    }
    md.append(
        "\n| Entry | Request | Expected | Status | ms | Result |\n|---|---|---|---|---|---|\n");
    for (EntryResult r : results) {
      Map<String, Object> m = r.toMap();
      md.append("| ")
          .append(r.context.isEmpty() ? "" : r.context + ": ")
          .append(r.entry.id())
          .append(" | `")
          .append(r.entry.method())
          .append(' ')
          .append(r.entry.path().replace("|", "%7C"))
          .append("` | ")
          .append(r.entry.expectedStatus())
          .append(" | ")
          .append(r.status)
          .append(" | ")
          .append(r.elapsedMs)
          .append(" | ")
          .append(m.get("result"))
          .append(
              r.failures.isEmpty() ? "" : ": " + String.join("; ", r.failures).replace("|", "/"))
          .append(r.skipped == null ? "" : " (" + r.skipped + ")")
          .append(" |\n");
    }
    return md.toString();
  }

  String summaryLine() {
    StringBuilder line = new StringBuilder();
    line.append("SMOKE ")
        .append(passed() ? "PASS" : "FAIL")
        .append(' ')
        .append(options.app)
        .append(" [")
        .append(options.variant)
        .append("]: ")
        .append(count("PASS"))
        .append(" passed, ")
        .append(count("FAIL"))
        .append(" failed, ")
        .append(count("SKIPPED"))
        .append(" skipped");
    if (!startups.isEmpty()) {
      line.append("; startup without agent (median of ")
          .append(startups.size())
          .append("): port ")
          .append(median("spawnToPortMs"))
          .append(" ms, ready ")
          .append(median("spawnToReadyMs"))
          .append(" ms");
      Object load = ((Map<?, ?>) startups.get(0).get("machine")).get("systemCpuLoadPercent");
      line.append(", machine CPU load before start ").append(load).append('%');
    }
    if (artifact.containsKey("dependencyJars")) {
      line.append("; jars ")
          .append(artifact.get("dependencyJars"))
          .append(", classes ")
          .append(artifact.get("totalClasses"));
    }
    if (runtime.containsKey("loadedClassCount")) {
      line.append(", loaded classes ").append(runtime.get("loadedClassCount"));
    }
    if (!problems.isEmpty()) {
      line.append("; problems: ").append(problems.size());
    }
    line.append(" -> ").append(options.reportDir.resolve("smoke.md").toAbsolutePath());
    return line.toString();
  }
}
