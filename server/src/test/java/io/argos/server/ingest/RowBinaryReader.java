/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.ingest;

import io.argos.server.storage.api.Column;
import io.argos.server.storage.api.Table;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Test-side parser of RowBinary rows by the {@link Table} catalogue: proves that the decoder writes
 * every column in catalogue order with the catalogue type (13 §7.4 explicit columns).
 */
final class RowBinaryReader {

  private RowBinaryReader() {}

  /** Every row of {@code bytes} as column name → value. */
  static List<Map<String, Object>> rows(Table table, byte[] bytes) {
    ByteBuffer in = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
    List<Map<String, Object>> rows = new ArrayList<>();
    while (in.hasRemaining()) {
      Map<String, Object> row = new LinkedHashMap<>();
      for (Column c : table.columns()) {
        row.put(c.name(), value(c.type(), in));
      }
      rows.add(row);
    }
    return rows;
  }

  private static Object value(String type, ByteBuffer in) {
    if (type.startsWith("Enum8(") || type.equals("UInt8")) {
      return in.get() & 0xFF;
    }
    if (type.startsWith("Array(")) {
      String inner = type.substring(6, type.length() - 1);
      long n = varint(in);
      List<Object> list = new ArrayList<>();
      for (long i = 0; i < n; i++) {
        list.add(value(inner, in));
      }
      return list;
    }
    if (type.startsWith("Map(")) {
      long n = varint(in);
      Map<String, String> map = new LinkedHashMap<>();
      for (long i = 0; i < n; i++) {
        map.put(string(in), string(in));
      }
      return map;
    }
    return switch (type) {
      case "UInt16" -> in.getShort() & 0xFFFF;
      case "UInt32" -> in.getInt() & 0xFFFF_FFFFL;
      case "Int32" -> in.getInt();
      case "UInt64", "Int64", "DateTime64(3, 'UTC')" -> in.getLong();
      case "DateTime('UTC')" -> in.getInt() & 0xFFFF_FFFFL;
      case "Float64" -> in.getDouble();
      case "String", "LowCardinality(String)" -> string(in);
      case "FixedString(16)" -> {
        byte[] b = new byte[16];
        in.get(b);
        yield b;
      }
      case "IPv6" -> {
        byte[] b = new byte[16];
        in.get(b);
        try {
          yield InetAddress.getByAddress(b).getHostAddress();
        } catch (UnknownHostException e) {
          throw new IllegalStateException(e);
        }
      }
      default -> throw new IllegalArgumentException("Type not handled by the test reader: " + type);
    };
  }

  private static String string(ByteBuffer in) {
    int n = (int) varint(in);
    byte[] b = new byte[n];
    in.get(b);
    return new String(b, StandardCharsets.UTF_8);
  }

  private static long varint(ByteBuffer in) {
    long result = 0;
    int shift = 0;
    while (true) {
      int b = in.get() & 0xFF;
      result |= (long) (b & 0x7F) << shift;
      if ((b & 0x80) == 0) {
        return result;
      }
      shift += 7;
    }
  }
}
