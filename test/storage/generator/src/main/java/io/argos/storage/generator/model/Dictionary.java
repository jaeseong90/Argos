/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.storage.generator.model;

import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;

/**
 * Dictionary keys of the generated data (13 §2: UInt64 keys of texts kept in PostgreSQL). A key is
 * a 64-bit hash of {@code kind|app|text}, independent of the run seed, never 0 (0 means "none" in
 * the xlog columns). The real agent uses xxh3_64; any uniform 64-bit hash has the same storage
 * behaviour. Entries are recorded for the {@code dictionary.tsv} output (names of top services, SQL
 * and hosts in reports).
 */
public final class Dictionary {

  /** Dictionary kinds of C-16 (02 §13). */
  public enum Kind {
    SERVICE,
    SQL,
    CLASS,
    METHOD,
    THREAD,
    HOST,
    FRAME,
    METRIC,
    ERROR_GROUP,
    DB_TARGET,
    APP_VERSION
  }

  private static final long DICTIONARY_SEED = 0x4152474F53L; // "ARGOS"

  /**
   * One dictionary entry.
   *
   * @param kind dictionary kind
   * @param appId owning app (PostgreSQL apps.id)
   * @param id 64-bit key
   * @param text original text
   */
  public record Entry(Kind kind, int appId, long id, String text) {}

  private final List<Entry> entries = new ArrayList<>();
  private final boolean record;

  /**
   * @param record keep the entries (false for large frame sets that are not needed)
   */
  public Dictionary(boolean record) {
    this.record = record;
  }

  /** The key of a text; records the entry. */
  public long id(Kind kind, int appId, String text) {
    long id = key(kind, appId, text);
    if (record) {
      entries.add(new Entry(kind, appId, id, text));
    }
    return id;
  }

  /** The key of a text without recording it. */
  public static long key(Kind kind, int appId, String text) {
    long id = Hash.of(DICTIONARY_SEED, kind.name() + '|' + appId + '|' + text);
    return id == 0 ? 1 : id;
  }

  /** Recorded entries in creation order. */
  public List<Entry> entries() {
    return entries;
  }

  /** Writes {@code kind TAB app_id TAB id TAB text} lines (text with tabs and newlines escaped). */
  public void writeTsv(Writer out) throws IOException {
    out.write("kind\tapp_id\tid\ttext\n");
    for (Entry e : entries) {
      out.write(e.kind().name());
      out.write('\t');
      out.write(Integer.toString(e.appId()));
      out.write('\t');
      out.write(Long.toUnsignedString(e.id()));
      out.write('\t');
      out.write(e.text().replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n"));
      out.write('\n');
    }
  }
}
