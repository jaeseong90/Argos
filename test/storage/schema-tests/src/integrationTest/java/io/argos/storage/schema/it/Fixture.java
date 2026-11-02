/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.storage.schema.it;

import io.argos.storage.clickhouse.Account;
import io.argos.storage.clickhouse.ArgosClickHouse;
import io.argos.storage.clickhouse.ClickHouseHttp;
import io.argos.storage.clickhouse.MemoryBudget;
import io.argos.storage.schema.SchemaApplier;
import io.argos.storage.schema.SchemaParameters;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * One ClickHouse server shared by the integration test classes of this JVM (2 GiB container, the
 * repository configuration). Each class works on its own table prefix ({@code {p}}), which also
 * exercises the multi-tenancy placeholder of DSN-13-11. Classes that need an empty server, another
 * version or a restart start their own {@link ArgosClickHouse}.
 *
 * <p>The writer may insert into the five raw tables of the default prefix only (users.d/argos.xml,
 * ADR-0007 C-60); the launcher grants it the raw tables of the test prefixes listed in {@link
 * #PREFIXES} as an installation with that prefix would ({@code
 * ArgosClickHouse.Builder.writerTablePrefixes}). A class with a new prefix adds it there.
 */
final class Fixture {

  /** The table prefixes of the test classes on the shared server. */
  static final List<String> PREFIXES =
      List.of(
          "",
          "acc_",
          "conf_",
          "confsec_",
          "dd100_",
          "ddref_",
          "ddbrk_",
          "dd1h_",
          "ddmid_",
          "ddid_",
          "ddwin_",
          "rb_",
          "rbx_",
          "mv_");

  private static ArgosClickHouse server;
  private static final Set<String> APPLIED = new HashSet<>();

  private Fixture() {}

  /** The shared server, started on first use and stopped when the JVM exits. */
  static synchronized ArgosClickHouse server() {
    if (server == null) {
      server =
          ArgosClickHouse.builder()
              .memoryLimit(2 * MemoryBudget.GIB)
              .writerTablePrefixes(PREFIXES.toArray(String[]::new))
              .start();
      ArgosClickHouse started = server;
      Runtime.getRuntime().addShutdownHook(new Thread(started::close, "argos-clickhouse-stop"));
    }
    return server;
  }

  /** Applies the repository schema with {@code prefix} once and returns its parameters. */
  static synchronized SchemaParameters schema(String prefix) {
    if (!PREFIXES.contains(prefix)) {
      throw new IllegalArgumentException("Add the test prefix to Fixture.PREFIXES: " + prefix);
    }
    SchemaParameters params = SchemaParameters.defaults().withTablePrefix(prefix);
    if (APPLIED.add(prefix)) {
      SchemaApplier.apply(migrator(), params);
    }
    return params;
  }

  static ClickHouseHttp writer() {
    return server().client(Account.WRITER);
  }

  static ClickHouseHttp reader() {
    return server().client(Account.READER);
  }

  static ClickHouseHttp migrator() {
    return server().client(Account.MIGRATOR);
  }
}
