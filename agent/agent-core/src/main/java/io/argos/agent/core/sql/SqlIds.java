/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.sql;

import io.argos.agent.bootstrap.DictKind;
import io.argos.agent.core.guard.AgentCounters;
import io.argos.agent.core.hash.Xxh3;
import io.argos.agent.core.trace.Dictionary;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Dictionary ids of SQL statements (10 §6.3, §9.4, the {@code SqlNormalizer.idOf} of 10 §6.3): the
 * raw text a JDBC helper passes in is normalized ({@link SqlNormalizer}, in the dialect of its
 * target) before it is hashed ({@code sql_id = xxh3_64(normalized)}, {@link Dictionary}) and kept,
 * so the dictionary and everything exported from it hold literal-free text only (SEC-35). A
 * statement the normalizer cannot handle becomes {@value SqlNormalizer#UNPARSED} and counts {@code
 * argos_agent_sql_unparsed_total} (DSN-10-17); a statement longer than {@code sql.max-length} is
 * kept with the {@code truncated} flag. The dictionary pre-sends new ids as {@code SQL_TEXT}
 * entries with {@code norm_version} {@value SqlNormalizer#NORM_VERSION} (WO-11 {@code
 * DictionaryCache}).
 *
 * <p>Raw text to id cache (10 §9.4 "LRU 10,000", "식별자 동일성 키 + 해시"): applications prepare the same
 * statements over and over, and a hit costs two hashes and one map lookup instead of a
 * normalization. The cache keys are not the raw text, which may hold literals (a plain {@code
 * Statement} with values written into the SQL), but a {@link Key} of the statement's length, its
 * dialect and two independent 64-bit hashes of its UTF-16 characters, so no raw text stays in the
 * agent's heap once its statement is normalized (SEC-35). It is an approximate LRU without a lock
 * (N-05): two generations of at most {@value #GENERATION_ENTRIES} entries each; a hit in the old
 * generation moves the entry to the young one, and when the young one is full it becomes the old
 * one and the old one is dropped. An entry used since the last rotation therefore survives the next
 * one; an entry not used for two rotations is gone. At most {@value #MAX_ENTRIES} entries in total
 * (a key is a few dozen bytes whatever the statement); statements longer than {@value
 * #MAX_KEY_CHARS} characters are normalized every time and not cached.
 *
 * <p>Collisions: two different statements share a key only when they have the same length and
 * dialect and both hashes collide. With 128 hash bits and at most {@value #MAX_ENTRIES} keys the
 * chance that any two cached statements collide is below 10<sup>-29</sup> for statements that are
 * not built to collide. A collision attributes the second statement to the first one's {@code
 * sql_id} (a wrong statistic); it never discloses anything, since the dictionary holds normalized,
 * literal-free text only.
 */
public final class SqlIds {
  /** Entries of both generations together (10 §9.4). */
  public static final int MAX_ENTRIES = 10_000;

  static final int GENERATION_ENTRIES = MAX_ENTRIES / 2;

  /** Longest raw statement cached (its key costs one buffer of twice its length to compute). */
  static final int MAX_KEY_CHARS = 4_096;

  private final Dictionary dictionary;
  private final SqlNormalizer normalizer;
  private final AgentCounters counters;

  /** Created by the first statement, so that the premain thread loads no cache class (C-79). */
  private final AtomicReference<Generations> generations = new AtomicReference<Generations>();

  private final AtomicLong rotations = new AtomicLong();

  /**
   * The cache key of a raw statement: its length, its dialect, and XXH3-64 of its UTF-16LE and of
   * its UTF-16BE bytes (the same characters in two byte orders: two hashes of the whole text that
   * do not collide together by chance). Every character is encoded, so statements that differ in
   * any character (unpaired surrogates included) differ in their bytes. The text itself is not
   * kept.
   */
  static final class Key {
    final int length;
    final int dialect;
    final long little;
    final long big;

    private Key(int length, int dialect, long little, long big) {
      this.length = length;
      this.dialect = dialect;
      this.little = little;
      this.big = big;
    }

    /**
     * The key of {@code rawSql} (at most {@value SqlIds#MAX_KEY_CHARS} characters) in {@code
     * dialect}.
     */
    static Key of(String rawSql, int dialect) {
      int length = rawSql.length();
      byte[] bytes = new byte[2 * length];
      for (int i = 0; i < length; i++) {
        char c = rawSql.charAt(i);
        bytes[2 * i] = (byte) c;
        bytes[2 * i + 1] = (byte) (c >>> 8);
      }
      long little = Xxh3.raw64(bytes, 0, bytes.length);
      for (int i = 0; i < bytes.length; i += 2) {
        byte low = bytes[i];
        bytes[i] = bytes[i + 1];
        bytes[i + 1] = low;
      }
      long big = Xxh3.raw64(bytes, 0, bytes.length);
      return new Key(length, dialect, little, big);
    }

    @Override
    public boolean equals(Object other) {
      if (this == other) {
        return true;
      }
      if (!(other instanceof Key)) {
        return false;
      }
      Key key = (Key) other;
      return little == key.little
          && big == key.big
          && length == key.length
          && dialect == key.dialect;
    }

    @Override
    public int hashCode() {
      return (int) (little ^ (little >>> 32));
    }
  }

  private static final class Generation {
    final ConcurrentHashMap<Key, Long> map = new ConcurrentHashMap<Key, Long>();
  }

  private static final class Generations {
    final Generation young;
    final Generation old;

    Generations(Generation young, Generation old) {
      this.young = young;
      this.old = old;
    }
  }

  /** SQL ids without agent counters (tests). */
  public SqlIds(Dictionary dictionary, SqlNormalizer normalizer) {
    this(dictionary, normalizer, null);
  }

  /** SQL ids that count unparsed statements in {@code counters} (may be null). */
  public SqlIds(Dictionary dictionary, SqlNormalizer normalizer, AgentCounters counters) {
    this.dictionary = dictionary;
    this.normalizer = normalizer;
    this.counters = counters;
  }

  /** The normalizer behind these ids ({@code sql.*} settings). */
  public SqlNormalizer normalizer() {
    return normalizer;
  }

  /** The id of the normalized form of {@code rawSql} in the unknown dialect (0 for null). */
  public long id(String rawSql) {
    return id(rawSql, SqlDialect.UNKNOWN);
  }

  /** The id of the normalized form of {@code rawSql} read in {@code dialect} (0 for null). */
  public long id(String rawSql, int dialect) {
    if (rawSql == null) {
      return 0;
    }
    Key key = rawSql.length() <= MAX_KEY_CHARS ? Key.of(rawSql, dialect) : null;
    if (key != null) {
      Generations current = generations();
      Long cached = current.young.map.get(key);
      if (cached == null) {
        cached = current.old.map.get(key);
        if (cached != null) {
          put(key, cached);
        }
      }
      if (cached != null) {
        return cached;
      }
    }
    String normalized = normalizer.normalize(rawSql, dialect);
    if (SqlNormalizer.UNPARSED.equals(normalized) && counters != null) {
      counters.incSqlUnparsed();
    }
    long id = dictionary.id(DictKind.SQL, normalized, normalizer.truncated(rawSql));
    if (key != null) {
      put(key, id);
    }
    return id;
  }

  private Generations generations() {
    Generations current = generations.get();
    if (current != null) {
      return current;
    }
    Generations created = new Generations(new Generation(), new Generation());
    return generations.compareAndSet(null, created) ? created : generations.get();
  }

  private void put(Key key, Long id) {
    Generations current = generations();
    Generation young = current.young;
    if (young.map.size() >= GENERATION_ENTRIES) {
      Generations rotated = new Generations(new Generation(), young);
      if (generations.compareAndSet(current, rotated)) {
        rotations.incrementAndGet();
      }
      young = generations.get().young;
    }
    young.map.put(key, id);
  }

  /** Entries in the raw-text cache, both generations (approximate under concurrency). */
  public int cachedEntries() {
    Generations current = generations.get();
    return current == null ? 0 : current.young.map.size() + current.old.map.size();
  }

  /** Times the young generation was full and replaced the old one. */
  public long clears() {
    return rotations.get();
  }
}
