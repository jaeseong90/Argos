/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.transactions;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * The ClickHouse statements of a set of API requests in {@code system.query_log} (T-ST-053,
 * ADR-0007 C-64): every statement under a request id ({@code query_id = <request id>-<n>}) must run
 * as {@code argos_reader} with the {@code SETTINGS} limits in its SQL text. Read as {@code
 * argos_reader} (13 §7.3 grants SELECT on {@code system.query_log}); the log is flushed every 7.5
 * s.
 */
final class QueryLogCheck {

  /** The flush interval of {@code system.query_log} plus a margin. */
  private static final long FLUSH_MS = 8_000;

  private QueryLogCheck() {}

  /**
   * One finished statement.
   *
   * @param queryId {@code <request id>-<n>}
   * @param user the ClickHouse user
   * @param query the SQL text (line feeds as spaces)
   * @param maxExecutionTime {@code Settings['max_execution_time']}
   * @param maxRowsToRead {@code Settings['max_rows_to_read']}
   * @param maxResultRows {@code Settings['max_result_rows']}
   * @param maxMemoryUsage {@code Settings['max_memory_usage']}
   */
  record Row(
      String queryId,
      String user,
      String query,
      String maxExecutionTime,
      String maxRowsToRead,
      String maxResultRows,
      String maxMemoryUsage) {

    /** The request id part of the query id. */
    String requestId() {
      int dash = queryId.indexOf('-');
      return dash < 0 ? queryId : queryId.substring(0, dash);
    }
  }

  /**
   * The finished statements of {@code requests}, by request id. Waits (at most 60 s) until every id
   * of {@code mustAppear} has a statement, then one more flush interval so that the statements of
   * the other requests are in too.
   */
  static Map<String, List<Row>> rows(Collection<String> requests, Collection<String> mustAppear)
      throws InterruptedException {
    List<String> ids = new ArrayList<>();
    for (String id : requests) {
      if (!id.matches("[A-Za-z0-9_]{1,64}")) {
        throw new IllegalArgumentException("Unexpected request id");
      }
      ids.add("'" + id + "'");
    }
    if (ids.isEmpty()) {
      return Map.of();
    }
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
    Map<String, List<Row>> out = read(ids);
    while (!out.keySet().containsAll(mustAppear) && System.nanoTime() < deadline) {
      TimeUnit.MILLISECONDS.sleep(1_000);
      out = read(ids);
    }
    TimeUnit.MILLISECONDS.sleep(FLUSH_MS);
    return read(ids);
  }

  private static Map<String, List<Row>> read(List<String> quotedIds) {
    Map<String, List<Row>> out = new HashMap<>();
    // Test SQL: the ids are checked request ids, no other value is concatenated.
    for (List<String> r :
        TxApiFixture.reader(
            "SELECT query_id, user, replaceAll(query, '\\n', ' '),"
                + " Settings['max_execution_time'], Settings['max_rows_to_read'],"
                + " Settings['max_result_rows'], Settings['max_memory_usage']"
                + " FROM system.query_log WHERE type = 'QueryFinish'"
                + " AND event_date >= yesterday()"
                + " AND substring(query_id, 1, position(query_id, '-') - 1) IN ("
                + String.join(",", quotedIds)
                + ") ORDER BY event_time_microseconds")) {
      Row row = new Row(r.get(0), r.get(1), r.get(2), r.get(3), r.get(4), r.get(5), r.get(6));
      out.computeIfAbsent(row.requestId(), k -> new ArrayList<>()).add(row);
    }
    return out;
  }

  /** What is wrong with the statements of {@code operation}; empty when every one is limited. */
  static List<String> problems(String operation, List<Row> rows) {
    List<String> problems = new ArrayList<>();
    for (Row row : rows) {
      String at = operation + " (" + row.queryId() + ")";
      if (!row.user().equals("argos_reader")) {
        problems.add(at + ": ran as " + row.user() + ", not argos_reader");
      }
      if (!row.query().contains("SETTINGS max_execution_time = ")) {
        problems.add(at + ": no SETTINGS max_execution_time in the SQL text");
      }
      if (row.maxExecutionTime().isEmpty()
          || row.maxRowsToRead().isEmpty()
          || row.maxResultRows().isEmpty()
          || row.maxMemoryUsage().isEmpty()) {
        problems.add(
            at
                + ": a limit is not set (max_execution_time, max_rows_to_read, max_result_rows,"
                + " max_memory_usage)");
      }
    }
    return problems;
  }
}
