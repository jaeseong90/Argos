/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.storage.api;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * One column of a raw table: its name and its ClickHouse type as ClickHouse prints it ({@code
 * system.columns.type}: no {@code CODEC}, no {@code DEFAULT}, single spaces, {@code ", "} between
 * arguments). The RowBinaryWithNamesAndTypes header of every insert repeats these strings, and
 * ClickHouse checks them against the table ({@code input_format_with_types_use_header}).
 *
 * @param name column name (13 naming: lower snake case)
 * @param type ClickHouse type, e.g. {@code DateTime64(3, 'UTC')} or {@code Enum8('HTTP' = 1,
 *     'BATCH' = 2, 'MESSAGE' = 3)}
 */
public record Column(String name, String type) {

  private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9_]*");

  /** Validates the name. */
  public Column {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(type, "type");
    if (!NAME.matcher(name).matches()) {
      throw new IllegalArgumentException("Invalid column name: " + name);
    }
    if (type.isBlank()) {
      throw new IllegalArgumentException("Blank type of column " + name);
    }
  }
}
