/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.storage.api;

import com.google.protobuf.ByteString;
import java.util.Objects;

/**
 * Rows of one table produced from one WAL data record, already encoded: {@code rowCount} rows in
 * ClickHouse RowBinary (fixed-size numbers little-endian, strings and arrays with a LEB128 length,
 * {@code Enum8} as one byte, {@code IPv6} as 16 bytes, {@code Map} as a LEB128 count of key and
 * value pairs), every row with the columns of {@link Table#columns()} in that order. The writer
 * prepends the RowBinaryWithNamesAndTypes header (column count, names, types of the catalogue) and
 * concatenates the rows of one block.
 *
 * <p>Encoded bytes keep the writer queue small (about 240 bytes per {@code xlog} row instead of
 * dozens of boxed values) and are immutable ({@link ByteString}).
 *
 * @param rowCount number of rows, at least 1
 * @param rowBinary the rows
 */
public record EncodedRows(int rowCount, ByteString rowBinary) {

  /** Validates the counts. */
  public EncodedRows {
    Objects.requireNonNull(rowBinary, "rowBinary");
    if (rowCount < 1) {
      throw new IllegalArgumentException("rowCount < 1: " + rowCount);
    }
    if (rowBinary.isEmpty()) {
      throw new IllegalArgumentException("No row bytes for " + rowCount + " rows");
    }
  }
}
