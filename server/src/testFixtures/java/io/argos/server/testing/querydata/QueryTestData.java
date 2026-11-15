/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.testing.querydata;

import io.argos.server.testing.ServerTestEnvironment;
import io.argos.server.testing.ServerTestEnvironment.PgAccount;
import io.argos.server.testing.auth.ArgosTestServer;
import io.argos.storage.clickhouse.Account;
import io.argos.storage.clickhouse.ClickHouseHttp;
import io.argos.storage.clickhouse.MemoryBudget;
import io.argos.storage.generator.Generator;
import io.argos.storage.generator.GeneratorConfig;
import io.argos.storage.generator.RunRange;
import io.argos.storage.generator.load.ClickHouseInserter;
import io.argos.storage.generator.model.Topology;
import io.argos.storage.generator.rows.Table;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * The data of the query API tests (WO-23): the storage generator's data (test/storage/generator, 13
 * §15 distributions, fixed seed 42) loaded in bulk as {@code argos_writer} into a {@link
 * ServerTestEnvironment} whose schema the server created, plus the PostgreSQL rows that describe
 * the estate ({@code apps}, {@code instances}, {@code dictionary}, {@code error_groups}; {@link
 * PgSeed}).
 *
 * <ul>
 *   <li>{@link #shared()}: one environment per test JVM (ClickHouse 1.5 GiB, PostgreSQL 512 MiB, so
 *       that a test class with its own default environment stays within 5 GiB), the server started
 *       on it (migrations), the {@link QueryDataProfile#TINY tiny} data placed {@link
 *       Placement#RECENT recently}; built on first use, closed when the JVM exits. Tests that share
 *       it must not delete rows; they add their own with {@link #rows()} in applications or
 *       instances of their own (ids from 900 up are never generated).
 *   <li>{@link #load}: the same load into any environment or installation (the bench with {@code
 *       ci}/{@code local}, the dev command {@link LoadQueryTestData}).
 * </ul>
 *
 * <pre>{@code
 * QueryTestData.Shared data = QueryTestData.shared();
 * ApiTestClient client = data.server().client();
 * QueryDataset dataset = data.dataset();
 * Samples.Transaction tx = dataset.samples().transactions().get(0);
 * }</pre>
 */
public final class QueryTestData {

  /** The fixed generator seed. */
  public static final long SEED = 42;

  /** First application id that the generator never uses (hand-made rows). */
  public static final int FIRST_FREE_APP_ID = 900;

  private static final Object LOCK = new Object();
  private static Shared shared; // guarded by LOCK

  private QueryTestData() {}

  /**
   * The environment, server and tiny data shared by the tests of this JVM; started on first use.
   */
  public static Shared shared() {
    synchronized (LOCK) {
      if (shared == null) {
        shared = Shared.start();
      }
      return shared;
    }
  }

  /**
   * Generates and loads a data set into {@code env} (its schema must exist: start a server on it
   * first) and seeds its PostgreSQL rows.
   */
  public static QueryDataset load(
      ServerTestEnvironment env, QueryDataProfile profile, Placement placement) {
    try (Connection pg = env.connect(PgAccount.APP)) {
      return load(
          env.clickHouse(Account.WRITER),
          () -> pg,
          ServerTestEnvironment.DATABASE,
          "",
          profile,
          placement,
          null,
          new PrintStream(System.out, true, StandardCharsets.UTF_8));
    } catch (SQLException e) {
      throw new IllegalStateException("PostgreSQL seed failed: " + e.getMessage(), e);
    }
  }

  /**
   * Generates and loads a data set into any installation.
   *
   * @param writer an {@code argos_writer} client
   * @param pg a connection of {@code argos_app} (or the schema owner)
   * @param database ClickHouse database
   * @param prefix table prefix
   * @param profile generator profile
   * @param placement time placement
   * @param out directory for the generator's files (manifest, samples, dictionary), or null
   * @param log progress output
   */
  public static QueryDataset load(
      ClickHouseHttp writer,
      Supplier<Connection> pg,
      String database,
      String prefix,
      QueryDataProfile profile,
      Placement placement,
      Path out,
      PrintStream log) {
    long now = System.currentTimeMillis();
    GeneratorConfig config =
        GeneratorConfig.defaults()
            .with("seed", Long.toString(SEED))
            .with("profile", profile.generatorName())
            .with("database", database)
            .with("table-prefix", prefix)
            // 50,000 rows per block: the batch writer size of 13 §7.4, and a small test heap.
            .with("block-rows", "50000")
            .with("monitor-interval-s", "30");
    if (placement == Placement.RECENT) {
      long end = Math.floorDiv(now, 60_000L) * 60_000L;
      config = config.with("start", Instant.ofEpochMilli(end - config.xlogSpanMs()).toString());
    }
    Generator.Result result;
    try {
      result =
          new Generator(config, new ClickHouseInserter(writer, database, prefix), null, log)
              .run(RunRange.bulk(config, now), out);
    } catch (IOException e) {
      throw new UncheckedIOException("Generator output failed", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while loading the query test data", e);
    }
    Topology topology = result.topology();
    int entries;
    try {
      entries =
          PgSeed.seed(Objects.requireNonNull(pg.get(), "connection"), topology, result.range());
    } catch (SQLException e) {
      throw new IllegalStateException("PostgreSQL seed failed: " + e.getMessage(), e);
    }
    return dataset(profile, placement, result, entries);
  }

  private static QueryDataset dataset(
      QueryDataProfile profile, Placement placement, Generator.Result result, int entries) {
    Topology topology = result.topology();
    List<QueryDataset.App> apps = new ArrayList<>();
    for (Topology.App app : topology.apps) {
      List<QueryDataset.Instance> instances = new ArrayList<>();
      for (Topology.Instance i : app.instances) {
        instances.add(new QueryDataset.Instance(i.id, PgSeed.instanceName(i), i.skewMs));
      }
      apps.add(new QueryDataset.App(app.id, app.name, app.env, instances));
    }
    List<Integer> skewed = topology.skewedInstances().stream().map(i -> i.id).toList();
    Map<String, Long> rows = new LinkedHashMap<>();
    for (Table t : Table.ALL) {
      rows.put(t.name(), result.manifest().rows(t));
    }
    RunRange r = result.range();
    return new QueryDataset(
        profile,
        placement,
        SEED,
        Instant.ofEpochMilli(r.start()),
        Instant.ofEpochMilli(r.end()),
        Instant.ofEpochMilli(r.profileFrom()),
        Instant.ofEpochMilli(r.metricFrom()),
        apps,
        skewed,
        rows,
        result.manifest().xlogSqlCount(),
        Samples.of(result.manifest().samples()),
        entries);
  }

  /** Hand-made rows for the shared environment. */
  public static HandMadeRows rows() {
    return HandMadeRows.of(shared().env());
  }

  /** The shared environment, server and data of a test JVM. */
  public static final class Shared {

    private final ServerTestEnvironment env;
    private final ArgosTestServer server;
    private final QueryDataset dataset;

    private Shared(ServerTestEnvironment env, ArgosTestServer server, QueryDataset dataset) {
      this.env = env;
      this.server = server;
      this.dataset = dataset;
    }

    private static Shared start() {
      ServerTestEnvironment env =
          ServerTestEnvironment.builder()
              .clickHouseMemoryLimit(3 * MemoryBudget.GIB / 2)
              .postgresMemoryLimit(MemoryBudget.GIB / 2)
              .start();
      ArgosTestServer server = null;
      try {
        server = ArgosTestServer.start(env);
        QueryDataset dataset = load(env, QueryDataProfile.TINY, Placement.RECENT);
        Shared s = new Shared(env, server, dataset);
        Runtime.getRuntime()
            .addShutdownHook(new Thread(s::close, "argos-query-test-data-shutdown"));
        return s;
      } catch (RuntimeException | Error e) {
        if (server != null) {
          server.close();
        }
        env.close();
        throw e;
      }
    }

    /** The databases. */
    public ServerTestEnvironment env() {
      return env;
    }

    /** The server on the databases (default properties). */
    public ArgosTestServer server() {
      return server;
    }

    /** The loaded data. */
    public QueryDataset dataset() {
      return dataset;
    }

    private void close() {
      try {
        server.close();
      } finally {
        env.close();
      }
    }
  }
}
