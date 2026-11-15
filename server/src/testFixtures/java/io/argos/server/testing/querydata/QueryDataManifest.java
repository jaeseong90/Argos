/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.testing.querydata;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * The manifest of a loaded data set ({@value #FILE_NAME}), for consumers outside the JVM that
 * loaded it (WO-30's E2E stack): the absolute time range, the applications and instances with their
 * ids, the row counts and the sample ids (transactions, skewed-clock transactions, traces, user and
 * request ids). No secret is in it.
 */
public final class QueryDataManifest {

  /** File name in the output directory. */
  public static final String FILE_NAME = "query-test-data.json";

  /** Format version of the manifest. */
  public static final int VERSION = 1;

  private static final JsonMapper JSON =
      JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();

  private QueryDataManifest() {}

  /** The manifest document of {@code dataset}. */
  public static Map<String, Object> document(QueryDataset dataset) {
    Map<String, Object> d = new LinkedHashMap<>();
    d.put("manifest_version", VERSION);
    d.put("profile", dataset.profile().generatorName());
    d.put("placement", dataset.placement().name().toLowerCase(java.util.Locale.ROOT));
    d.put("seed", dataset.seed());
    d.put("loaded_at", Instant.now().toString());
    Map<String, Object> range = new LinkedHashMap<>();
    range.put("start", dataset.start().toString());
    range.put("end", dataset.end().toString());
    range.put("profile_from", dataset.profileFrom().toString());
    range.put("metric_from", dataset.metricFrom().toString());
    d.put("range", range);
    List<Map<String, Object>> apps = new ArrayList<>();
    for (QueryDataset.App app : dataset.apps()) {
      Map<String, Object> a = new LinkedHashMap<>();
      a.put("app_id", app.appId());
      a.put("name", app.name());
      a.put("env", app.env());
      List<Map<String, Object>> instances = new ArrayList<>();
      for (QueryDataset.Instance i : app.instances()) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("instance_id", i.instanceId());
        m.put("name", i.name());
        m.put("skew_ms", i.skewMs());
        instances.add(m);
      }
      a.put("instances", instances);
      apps.add(a);
    }
    d.put("apps", apps);
    d.put("skewed_instance_ids", dataset.skewedInstanceIds());
    d.put("rows", dataset.rows());
    d.put("dictionary_entries", dataset.dictionaryEntries());
    Samples s = dataset.samples();
    Map<String, Object> samples = new LinkedHashMap<>();
    samples.put("transactions", transactions(s.transactions()));
    samples.put("skewed_instance_transactions", transactions(s.skewedInstanceTransactions()));
    samples.put("late_transactions", transactions(s.lateTransactions()));
    List<Map<String, Object>> traces = new ArrayList<>();
    for (Samples.Trace t : s.traces()) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("trace_id", t.traceIdHex());
      m.put("app_id", t.appId());
      m.put("ts", t.ts().toString());
      m.put("argos_generated", t.argosGenerated());
      m.put("transactions", t.transactions());
      traces.add(m);
    }
    samples.put("traces", traces);
    samples.put("users", values(s.users(), "user_id"));
    samples.put("requests", values(s.requests(), "request_id"));
    d.put("samples", samples);
    return d;
  }

  /** Writes the manifest of {@code dataset} to {@code directory/}{@value #FILE_NAME}. */
  public static Path write(QueryDataset dataset, Path directory) throws IOException {
    Files.createDirectories(directory);
    Path file = directory.resolve(FILE_NAME);
    Files.writeString(file, JSON.writeValueAsString(document(dataset)), StandardCharsets.UTF_8);
    return file;
  }

  private static List<Map<String, Object>> transactions(List<Samples.Transaction> list) {
    List<Map<String, Object>> out = new ArrayList<>();
    for (Samples.Transaction t : list) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("app_id", t.appId());
      m.put("instance_id", t.instanceId());
      m.put("tx_id", t.txIdText());
      m.put("ts", t.ts().toString());
      m.put("service_id", Long.toUnsignedString(t.serviceId()));
      m.put("kind", t.kind());
      m.put("profile_captured", t.profileCaptured());
      m.put("late", t.late());
      out.add(m);
    }
    return out;
  }

  private static List<Map<String, Object>> values(List<Samples.Value> list, String key) {
    List<Map<String, Object>> out = new ArrayList<>();
    for (Samples.Value v : list) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("app_id", v.appId());
      m.put(key, v.value());
      m.put("ts", v.ts().toString());
      out.add(m);
    }
    return out;
  }
}
