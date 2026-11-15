/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.sql;

import io.argos.agent.bootstrap.DictKind;
import io.argos.agent.core.trace.Dictionary;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Dictionary ids of JDBC targets (10 §6.3 {@code DbInfo}, C-16 {@code DB_TARGET}): the URL a
 * connection reports ({@code getMetaData().getURL()}) becomes {@link JdbcUrlParser#targetText}
 * ({@code <vendor>://<host>:<port>/<database>}, credentials and parameters removed, SEC-35) and its
 * {@code xxh3_64} id in the {@link Dictionary}. The raw URL is never kept: the JDBC module asks
 * once per connection object, so there is no URL cache to hold credentials.
 *
 * <p>The SQL dialect of each id ({@link SqlDialect}) is remembered for the normalizer ({@link
 * #dialectOf(long)}): at most {@value #MAX_TARGETS} ids (the dictionary's {@code DB_TARGET}
 * capacity, 10 §10.4); a full map is cleared and refilled, which only makes the next statements of
 * the forgotten targets use the stricter {@link SqlDialect#UNKNOWN} reading until their next
 * connection is read. Lock-free; never throws.
 */
public final class DbIdentity {
  /** Targets whose dialect is remembered. */
  static final int MAX_TARGETS = 1_000;

  private final Dictionary dictionary;
  private final ConcurrentHashMap<Long, Integer> dialects = new ConcurrentHashMap<Long, Integer>();

  public DbIdentity(Dictionary dictionary) {
    this.dictionary = dictionary;
  }

  /** The {@code DB_TARGET} id of {@code jdbcUrl}; 0 for null. */
  public long id(String jdbcUrl) {
    if (jdbcUrl == null) {
      return 0;
    }
    try {
      JdbcUrlParser.Target target = JdbcUrlParser.parse(jdbcUrl);
      long id = dictionary.id(DictKind.DB_TARGET, target.text());
      Long key = Long.valueOf(id);
      int dialect = SqlDialect.of(target.vendor());
      Integer known = dialects.get(key);
      if (known == null || known.intValue() != dialect) {
        if (dialects.size() >= MAX_TARGETS) {
          dialects.clear();
        }
        dialects.put(key, Integer.valueOf(dialect));
      }
      return id;
    } catch (RuntimeException e) {
      return 0;
    }
  }

  /** The {@link SqlDialect} of the target {@code dbId}; {@link SqlDialect#UNKNOWN} when unknown. */
  public int dialectOf(long dbId) {
    if (dbId == 0) {
      return SqlDialect.UNKNOWN;
    }
    Integer dialect = dialects.get(Long.valueOf(dbId));
    return dialect == null ? SqlDialect.UNKNOWN : dialect.intValue();
  }

  /** Targets whose dialect is remembered (tests, diagnostics). */
  public int size() {
    return dialects.size();
  }
}
