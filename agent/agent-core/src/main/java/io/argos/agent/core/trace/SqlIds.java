/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.trace;

import io.argos.agent.bootstrap.DictKind;
import io.argos.agent.core.sql.SqlNormalizer;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Dictionary ids of SQL statements (10 §6.3, §9.4): the raw text a JDBC helper passes in is
 * normalized ({@link SqlNormalizer}) before it is hashed and kept, so the dictionary and everything
 * exported from it hold literal-free text only (SEC-35).
 *
 * <p>A bounded cache maps raw text to its id: applications prepare the same statements over and
 * over, and a cache hit costs one hash lookup instead of a normalization. Bounds (N-02): at most
 * {@value #MAX_ENTRIES} entries and {@value #MAX_CACHED_CHARS} characters in total; statements
 * longer than {@value #MAX_KEY_CHARS} characters are not cached. A full cache is cleared and
 * refilled (the WO-02 stand-in for the LRU of 10 §9.4). The raw keys never leave the process.
 */
public final class SqlIds {
  static final int MAX_ENTRIES = 10_000;
  static final int MAX_KEY_CHARS = 4_096;
  static final long MAX_CACHED_CHARS = 2_000_000L;

  private final Dictionary dictionary;
  private final SqlNormalizer normalizer;
  private final ConcurrentHashMap<String, Long> cache = new ConcurrentHashMap<String, Long>();
  private final AtomicLong cachedChars = new AtomicLong();
  private final AtomicLong clears = new AtomicLong();

  public SqlIds(Dictionary dictionary, SqlNormalizer normalizer) {
    this.dictionary = dictionary;
    this.normalizer = normalizer;
  }

  /** The id of the normalized form of {@code rawSql} (0 for null). */
  public long id(String rawSql) {
    if (rawSql == null) {
      return 0;
    }
    Long cached = cache.get(rawSql);
    if (cached != null) {
      return cached.longValue();
    }
    long id = dictionary.id(DictKind.SQL, normalizer.normalize(rawSql));
    int chars = rawSql.length();
    if (chars <= MAX_KEY_CHARS) {
      if (cache.size() >= MAX_ENTRIES || cachedChars.addAndGet(chars) > MAX_CACHED_CHARS) {
        cache.clear();
        cachedChars.set(chars);
        clears.incrementAndGet();
      }
      cache.put(rawSql, Long.valueOf(id));
    }
    return id;
  }

  /** Entries in the raw-text cache (approximate under concurrency). */
  public int cachedEntries() {
    return cache.size();
  }

  /** Times the cache was full and cleared. */
  public long clears() {
    return clears.get();
  }
}
