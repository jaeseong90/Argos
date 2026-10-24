/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.storage.queries.it;

import io.argos.storage.clickhouse.Account;
import io.argos.storage.clickhouse.ArgosClickHouse;
import io.argos.storage.clickhouse.MemoryBudget;
import io.argos.storage.queries.BenchContext;
import io.argos.storage.queries.MachineLoad;
import io.argos.storage.queries.QueryCatalog;
import io.argos.storage.queries.QueryRunner;
import io.argos.storage.queries.Server;
import io.argos.storage.schema.SchemaApplier;
import io.argos.storage.schema.SchemaParameters;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * One ClickHouse server (the bundled image with the repository configuration, 3 GiB container) and
 * one CI dataset per test JVM, shared by the integration test classes: the dataset is loaded once
 * into the prefix {@code ci_} through the writer path. The consistency procedures add rows but keep
 * every invariant, so the classes do not depend on their order; exact comparisons with the ground
 * truth use the snapshot taken right after the load ({@link Datasets.Snapshot}).
 */
final class Fixture {

  static final String PREFIX = "ci_";

  private static ArgosClickHouse clickHouse;
  private static Server server;
  private static MachineLoad load;
  private static Datasets.Loaded loaded;
  private static final QueryCatalog CATALOG = QueryCatalog.fromRepository();

  private Fixture() {}

  static synchronized Server server() {
    if (server == null) {
      clickHouse = ArgosClickHouse.builder().memoryLimit(3 * MemoryBudget.GIB).start();
      server = Server.wrap(clickHouse);
      load = MachineLoad.start();
      ArgosClickHouse started = clickHouse;
      MachineLoad sampler = load;
      Runtime.getRuntime()
          .addShutdownHook(
              new Thread(
                  () -> {
                    sampler.close();
                    started.close();
                  },
                  "argos-clickhouse-stop"));
    }
    return server;
  }

  static synchronized MachineLoad load() {
    server();
    return load;
  }

  static SchemaParameters schema() {
    return SchemaParameters.defaults().withTablePrefix(PREFIX);
  }

  /**
   * The CI dataset, loaded on first use, with the snapshot of the server's counts taken right after
   * the load (before any test or procedure adds rows).
   */
  static synchronized Datasets.Loaded dataset() {
    if (loaded == null) {
      SchemaApplier.apply(server().client(Account.MIGRATOR), schema());
      loaded =
          Datasets.load(
              server().client(Account.WRITER),
              server().client(Account.MIGRATOR),
              schema(),
              reportDir());
      Evidence.record("dataset", "source", loaded.source());
      Evidence.record("dataset", "description", loaded.description());
      Evidence.record("dataset", "load time (s)", loaded.elapsed().toSeconds());
      Evidence.record(
          "dataset",
          "late xlog rows / re-sent blocks / skewed instances",
          loaded.lateRows() + " / " + loaded.resentBlocks() + " / " + loaded.skewedInstances());
    }
    return loaded;
  }

  /** A fresh runner on the CI dataset (its own results and query ids). */
  static QueryRunner runner() {
    dataset();
    return new QueryRunner(server(), schema(), QueryRunner.Options.defaults(), load());
  }

  /** A context on a fresh runner (shared parameters resolved from the CI dataset). */
  static BenchContext context() {
    return BenchContext.resolve(runner(), CATALOG);
  }

  static QueryCatalog catalog() {
    return CATALOG;
  }

  static Path reportDir() {
    return Paths.get(System.getProperty("argos.reportDir", "build/reports/storage-queries"));
  }
}
