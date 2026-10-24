/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.storage.queries;

import io.argos.storage.clickhouse.Account;
import io.argos.storage.clickhouse.ClickHouseHttp;
import io.argos.storage.schema.SchemaParameters;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * What the procedures share: the runner (server, schema, options), the catalog and the shared
 * parameters resolved from the loaded data.
 *
 * @param runner the runner
 * @param catalog the query files
 * @param shared parameters of params.sql (dataset range, busiest application)
 */
public record BenchContext(QueryRunner runner, QueryCatalog catalog, Parameters shared) {

  private static final DateTimeFormatter SECONDS =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT).withZone(ZoneOffset.UTC);
  private static final DateTimeFormatter MILLIS =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT).withZone(ZoneOffset.UTC);

  /** Resolves the shared probes and returns the context; a failing probe throws. */
  public static BenchContext resolve(QueryRunner runner, QueryCatalog catalog) {
    Parameters shared = runner.resolve(catalog.parameters(), runner.baseParameters());
    return new BenchContext(runner, catalog, shared);
  }

  /**
   * Resolves the shared probes, recording failures in {@code problems} (e.g. a server without data
   * for a run of the synthetic Q14 only).
   */
  public static BenchContext resolveLenient(
      QueryRunner runner, QueryCatalog catalog, java.util.List<String> problems) {
    Parameters shared = runner.resolve(catalog.parameters(), runner.baseParameters(), problems);
    return new BenchContext(runner, catalog, shared);
  }

  /** A query file. */
  public QueryFile file(String id) {
    return catalog.query(id);
  }

  /** Schema parameters. */
  public SchemaParameters schema() {
    return runner.schema();
  }

  /** A client of the server. */
  public ClickHouseHttp client(Account account) {
    return runner.server().client(account);
  }

  /** Shared parameters plus the probes of {@code id}. */
  public Parameters parametersOf(String id) {
    return runner.resolve(file(id), shared);
  }

  /** A shared DateTime parameter as an instant. */
  public Instant instant(String name) {
    String value = shared.get(name);
    if (value == null) {
      throw new IllegalStateException("No shared parameter " + name);
    }
    String v = value.length() > 19 ? value.substring(0, 19) : value;
    return LocalDateTime.parse(v.replace(' ', 'T')).toInstant(ZoneOffset.UTC);
  }

  /** A shared Date parameter. */
  public LocalDate date(String name) {
    String value = shared.get(name);
    if (value == null) {
      throw new IllegalStateException("No shared parameter " + name);
    }
    return LocalDate.parse(value);
  }

  /** DateTime text of an instant (query parameter form). */
  public static String dateTime(Instant t) {
    return SECONDS.format(t);
  }

  /** DateTime64(3) text of an instant (query parameter form). */
  public static String dateTime64(Instant t) {
    return MILLIS.format(t);
  }

  /** Milliseconds of a duration as double. */
  public static double ms(Duration d) {
    return d.toNanos() / 1e6;
  }
}
