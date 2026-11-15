/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.csv;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * The rows of one CSV export: a header line and rows of the same width (values as the JSON response
 * has them; {@link CsvWriter} formats them).
 */
public final class CsvTable {

  private final List<String> header;
  private final List<List<Object>> rows = new ArrayList<>();

  /** A table with these column names. */
  public CsvTable(String... header) {
    this.header = List.of(header);
  }

  /** Adds a row; it must have one value per column (nulls allowed). */
  public CsvTable add(Object... values) {
    if (values.length != header.size()) {
      throw new IllegalArgumentException(
          "Row of " + values.length + " values for " + header.size() + " columns");
    }
    rows.add(Collections.unmodifiableList(new ArrayList<>(Arrays.asList(values))));
    return this;
  }

  /** The column names. */
  public List<String> header() {
    return header;
  }

  /** The rows. */
  public List<List<Object>> rows() {
    return Collections.unmodifiableList(rows);
  }

  /** The number of rows. */
  public int size() {
    return rows.size();
  }

  @Override
  public String toString() {
    return "CsvTable" + header + " rows=" + rows.size();
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof CsvTable t && header.equals(t.header) && rows.equals(t.rows);
  }

  @Override
  public int hashCode() {
    return Objects.hash(header, rows);
  }
}
