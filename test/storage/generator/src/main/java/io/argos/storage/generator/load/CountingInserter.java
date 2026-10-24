/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.storage.generator.load;

import io.argos.storage.generator.rows.Table;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Inserter without a destination (dry run, tests): keeps the tokens and a SHA-256 per table of
 * every block (token and bytes), in send order, so that two runs can be compared byte for byte.
 */
public final class CountingInserter implements Inserter {

  private final MessageDigest[] digests = new MessageDigest[Table.ALL.size()];
  private final List<List<String>> tokens = new ArrayList<>();
  private final long[] rows = new long[Table.ALL.size()];
  private final long[] bytes = new long[Table.ALL.size()];
  private final boolean keepTokens;

  /**
   * @param keepTokens keep every token (tests); false keeps only the digests
   */
  public CountingInserter(boolean keepTokens) {
    this.keepTokens = keepTokens;
    for (int i = 0; i < digests.length; i++) {
      try {
        digests[i] = MessageDigest.getInstance("SHA-256");
      } catch (NoSuchAlgorithmException e) {
        throw new IllegalStateException(e);
      }
      tokens.add(new ArrayList<>());
    }
  }

  @Override
  public long insert(Block block) {
    int t = block.table().index();
    synchronized (digests[t]) {
      digests[t].update(block.token().getBytes(StandardCharsets.UTF_8));
      digests[t].update(block.data());
      rows[t] += block.rows();
      bytes[t] += block.data().length;
      if (keepTokens) {
        tokens.get(t).add(block.token());
      }
    }
    return -1;
  }

  /** Tokens sent for a table, in send order (duplicates included). */
  public List<String> tokens(Table table) {
    synchronized (digests[table.index()]) {
      return List.copyOf(tokens.get(table.index()));
    }
  }

  /** Rows sent for a table (duplicates included). */
  public long rows(Table table) {
    synchronized (digests[table.index()]) {
      return rows[table.index()];
    }
  }

  /** SHA-256 (hex) per table of everything sent; consumes the digests. */
  public Map<String, String> digests() {
    Map<String, String> m = new LinkedHashMap<>();
    for (Table table : Table.ALL) {
      synchronized (digests[table.index()]) {
        m.put(table.name(), HexFormat.of().formatHex(digests[table.index()].digest()));
      }
    }
    return m;
  }

  @Override
  public String describe() {
    return "none (count only)";
  }
}
