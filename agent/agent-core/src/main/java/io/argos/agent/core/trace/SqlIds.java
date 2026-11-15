/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.trace;

import io.argos.agent.bootstrap.DictKind;
import io.argos.agent.core.guard.AgentCounters;
import io.argos.agent.core.sql.SqlNormalizer;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Dictionary ids of SQL statements (10 §6.3, §9.4): the raw text a JDBC helper passes in is
 * normalized ({@link SqlNormalizer}) before it is hashed ({@code sql_id = xxh3_64(normalized)},
 * {@link Dictionary}) and kept, so the dictionary and everything exported from it hold literal-free
 * text only (SEC-35). A statement the normalizer cannot handle becomes {@value Dictionary#UNPARSED}
 * and counts {@code argos_agent_sql_unparsed_total} (DSN-10-17).
 *
 * <p>A bounded cache maps raw text to its id: applications prepare the same statements over and
 * over, and a cache hit costs one hash lookup instead of a normalization. Bounds (N-02): at most
 * {@value #MAX_ENTRIES} entries and {@value #MAX_CACHED_CHARS} characters in total; statements
 * longer than {@value #MAX_KEY_CHARS} characters are not cached. A full cache is cleared and
 * refilled (an LRU of 10 §9.4 would need a lock or per-entry bookkeeping on the application
 * thread). The raw keys never leave the process.
 */
public final class SqlIds {
  static final int MAX_ENTRIES = 10_000;
  static final int MAX_KEY_CHARS = 4_096;
  static final long MAX_CACHED_CHARS = 2_000_000L;

  private final Dictionary dictionary;
  private final SqlNormalizer normalizer;
  private final AgentCounters counters;
  private final ConcurrentHashMap<String, Long> cache = new ConcurrentHashMap<String, Long>();
  private final AtomicLong cachedChars = new AtomicLong();
  private final AtomicLong clears = new AtomicLong();

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

  /** The id of the normalized form of {@code rawSql} (0 for null). */
  public long id(String rawSql) {
    if (rawSql == null) {
      return 0;
    }
    Long cached = cache.get(rawSql);
    if (cached != null) {
      return cached.longValue();
    }
    String normalized = normalizer.normalize(rawSql);
    if (SqlNormalizer.UNPARSED.equals(normalized) && counters != null) {
      counters.incSqlUnparsed();
    }
    long id = dictionary.id(DictKind.SQL, normalized);
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
