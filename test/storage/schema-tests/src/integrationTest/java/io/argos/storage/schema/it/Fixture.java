/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.storage.schema.it;

import io.argos.storage.clickhouse.Account;
import io.argos.storage.clickhouse.ArgosClickHouse;
import io.argos.storage.clickhouse.ClickHouseHttp;
import io.argos.storage.clickhouse.MemoryBudget;
import io.argos.storage.schema.SchemaApplier;
import io.argos.storage.schema.SchemaParameters;
import java.util.HashSet;
import java.util.Set;

/**
 * One ClickHouse server shared by the integration test classes of this JVM (2 GiB container, the
 * repository configuration). Each class works on its own table prefix ({@code {p}}), which also
 * exercises the multi-tenancy placeholder of DSN-13-11. Classes that need an empty server, another
 * version or a restart start their own {@link ArgosClickHouse}.
 */
final class Fixture {

  private static ArgosClickHouse server;
  private static final Set<String> APPLIED = new HashSet<>();

  private Fixture() {}

  /** The shared server, started on first use and stopped when the JVM exits. */
  static synchronized ArgosClickHouse server() {
    if (server == null) {
      server = ArgosClickHouse.builder().memoryLimit(2 * MemoryBudget.GIB).start();
      ArgosClickHouse started = server;
      Runtime.getRuntime().addShutdownHook(new Thread(started::close, "argos-clickhouse-stop"));
    }
    return server;
  }

  /** Applies the repository schema with {@code prefix} once and returns its parameters. */
  static synchronized SchemaParameters schema(String prefix) {
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
