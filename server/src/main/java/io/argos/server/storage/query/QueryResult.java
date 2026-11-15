/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.storage.query;

import java.util.Iterator;
import java.util.List;
import java.util.Objects;

/**
 * The rows of one reader query, in result order.
 *
 * @param queryId the ClickHouse {@code query_id} of the statement ({@code system.query_log})
 * @param columns column names
 * @param types ClickHouse type names of the columns
 * @param rows the rows
 */
public record QueryResult(
    String queryId, List<String> columns, List<String> types, List<QueryRow> rows)
    implements Iterable<QueryRow> {

  public QueryResult {
    Objects.requireNonNull(queryId, "queryId");
    columns = List.copyOf(columns);
    types = List.copyOf(types);
    rows = List.copyOf(rows);
  }

  /** The number of rows. */
  public int size() {
    return rows.size();
  }

  /** Whether there is no row. */
  public boolean isEmpty() {
    return rows.isEmpty();
  }

  /** The only row; {@link IllegalStateException} unless there is exactly one. */
  public QueryRow single() {
    if (rows.size() != 1) {
      throw new IllegalStateException("Expected one row, got " + rows.size());
    }
    return rows.get(0);
  }

  @Override
  public Iterator<QueryRow> iterator() {
    return rows.iterator();
  }
}
