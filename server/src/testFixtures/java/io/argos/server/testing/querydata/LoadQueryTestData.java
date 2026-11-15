/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.testing.querydata;

import io.argos.storage.clickhouse.ClickHouseHttp;
import java.io.PrintStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Development-only command: loads the query test data ({@link QueryTestData}) into a running
 * installation and writes its manifest, so that another process (WO-30's E2E stack) can seed a
 * server with the same data the integration tests use. Never part of a shipped artifact.
 *
 * <pre>
 *   ARGOS_CH_WRITER_PASSWORD=... ARGOS_PG_PASSWORD=... \
 *   ./gradlew :server:loadQueryTestData -Pquerydata.clickhouse=http://127.0.0.1:8123 \
 *       -Pquerydata.postgres=jdbc:postgresql://127.0.0.1:5432/argos [-Pquerydata.profile=tiny]
 *       [-Pquerydata.placement=recent] [-Pquerydata.out=DIR]
 * </pre>
 *
 * Arguments ({@code --key=value}): {@code clickhouse} (HTTP URL), {@code postgres} (JDBC URL),
 * {@code profile} ({@code tiny}, {@code ci}, {@code local}, {@code full}; default tiny), {@code
 * placement} ({@code recent}, {@code around_midnight}; default recent), {@code out} (manifest
 * directory), {@code database} (default argos), {@code prefix} (default empty), {@code ch-user}
 * (default argos_writer), {@code pg-user} (default argos_app). Passwords only from the environment:
 * {@code ARGOS_CH_WRITER_PASSWORD} and {@code ARGOS_PG_PASSWORD}; an argument that looks like a
 * password is refused. Preconditions: the server has started once on the databases (schema
 * migrated) and they hold no other data with the generator's ids (apps 1..10, instances 1..50).
 */
public final class LoadQueryTestData {

  private LoadQueryTestData() {}

  /** Entry point. */
  public static void main(String[] args) throws Exception {
    PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
    try {
      run(List.of(args), System.getenv(), out);
    } catch (IllegalArgumentException e) {
      out.println("loadQueryTestData: " + e.getMessage());
      System.exit(2);
    }
  }

  /**
   * Runs the load.
   *
   * @param args {@code --key=value} arguments
   * @param environment the environment (passwords)
   * @param log progress output (never a password)
   * @return the loaded data set
   * @throws IllegalArgumentException for missing or invalid arguments
   */
  public static QueryDataset run(
      List<String> args, Map<String, String> environment, PrintStream log)
      throws SQLException, java.io.IOException {
    Map<String, String> a = parse(args);
    String clickhouse = required(a, "clickhouse");
    String postgres = required(a, "postgres");
    String chPassword = environment.get("ARGOS_CH_WRITER_PASSWORD");
    String pgPassword = environment.get("ARGOS_PG_PASSWORD");
    if (chPassword == null || chPassword.isEmpty()) {
      throw new IllegalArgumentException("Set ARGOS_CH_WRITER_PASSWORD (argos_writer password)");
    }
    if (pgPassword == null || pgPassword.isEmpty()) {
      throw new IllegalArgumentException("Set ARGOS_PG_PASSWORD (argos_app password)");
    }
    QueryDataProfile profile = QueryDataProfile.of(a.getOrDefault("profile", "tiny"));
    Placement placement = Placement.of(a.getOrDefault("placement", "recent"));
    String database = a.getOrDefault("database", "argos");
    String prefix = a.getOrDefault("prefix", "");
    Path outDir = Path.of(a.getOrDefault("out", "build/query-test-data"));
    ClickHouseHttp writer =
        new ClickHouseHttp(
            URI.create(clickhouse), a.getOrDefault("ch-user", "argos_writer"), chPassword);
    log.printf(
        "loadQueryTestData: profile %s, placement %s, ClickHouse %s, PostgreSQL %s%n",
        profile.generatorName(),
        placement.name().toLowerCase(java.util.Locale.ROOT),
        clickhouse,
        postgres);
    QueryDataset dataset;
    try (Connection pg =
        DriverManager.getConnection(postgres, a.getOrDefault("pg-user", "argos_app"), pgPassword)) {
      dataset =
          QueryTestData.load(writer, () -> pg, database, prefix, profile, placement, outDir, log);
    }
    Path manifest = QueryDataManifest.write(dataset, outDir);
    log.printf(
        "loadQueryTestData: %,d xlog rows %s .. %s, manifest %s%n",
        dataset.xlogRows(), dataset.start(), dataset.end(), manifest.toAbsolutePath());
    return dataset;
  }

  private static Map<String, String> parse(List<String> args) {
    Map<String, String> out = new LinkedHashMap<>();
    for (String arg : args) {
      if (!arg.startsWith("--") || arg.indexOf('=') < 3) {
        throw new IllegalArgumentException("Expected --key=value, got an argument of another form");
      }
      String key = arg.substring(2, arg.indexOf('='));
      if (key.contains("password") || key.contains("secret") || key.contains("token")) {
        throw new IllegalArgumentException(
            "Passwords come from the environment only (ARGOS_CH_WRITER_PASSWORD,"
                + " ARGOS_PG_PASSWORD), never from arguments");
      }
      if (!List.of(
              "clickhouse",
              "postgres",
              "profile",
              "placement",
              "out",
              "database",
              "prefix",
              "ch-user",
              "pg-user")
          .contains(key)) {
        throw new IllegalArgumentException("Unknown argument --" + key);
      }
      out.put(key, arg.substring(arg.indexOf('=') + 1));
    }
    return out;
  }

  private static String required(Map<String, String> args, String key) {
    String value = args.get(key);
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("--" + key + " is required");
    }
    return value;
  }
}
