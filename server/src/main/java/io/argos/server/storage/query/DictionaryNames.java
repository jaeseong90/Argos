/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.storage.query;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.argos.server.api.error.ApiException;
import io.argos.server.api.error.ErrorCode;
import java.sql.Array;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Names of dictionary keys (13 §2, §12.3; 11 §7): ClickHouse rows carry 64-bit keys ({@code
 * service_id}, {@code sql_id}, {@code host_id}, ...), the texts live in PostgreSQL {@code
 * argos.dictionary} by {@code (app_id, kind, hash64)}. Lookups are batched (one statement per 1,000
 * keys) and the found entries are kept in a bounded cache (a key's text never changes: the key is a
 * hash of it).
 *
 * <p>Key 0 means "none" in every ClickHouse column (no error class, no version, ...) and is never
 * looked up. A key without an entry is shown as {@code <unknown:hash>} with the key in 16
 * lower-case hex digits (11 §7 (3)); asking the agent for the entry ({@code DictionaryRequest}) is
 * not part of the query layer. Texts are collected data: callers apply the untrusted-string rules
 * of 14 §2.10 before they put them into a response.
 */
@Component
public class DictionaryNames {

  /** Keys per PostgreSQL statement. */
  static final int BATCH = 1_000;

  /** Upper bound of the cache: characters of the cached texts plus a per-entry overhead. */
  static final long MAX_CACHE_WEIGHT = 32L * 1024 * 1024;

  private static final Logger LOG = LoggerFactory.getLogger(DictionaryNames.class);

  private static final String SELECT =
      "SELECT hash64, text, truncated, norm_version FROM argos.dictionary"
          + " WHERE app_id = ? AND kind = ?::argos.dict_kind AND hash64 = ANY(?)";

  /** Dictionary kinds: the PostgreSQL {@code argos.dict_kind} values (C-16, V003 {@code pool}). */
  public enum Kind {
    /** Service names (URL patterns, batch jobs). */
    SERVICE,
    /** Normalized SQL texts. */
    SQL,
    /** Exception class names. */
    CLASS,
    /** Method names. */
    METHOD,
    /** Normalized thread names. */
    THREAD,
    /** Outgoing call hosts ({@code scheme://host:port}). */
    HOST,
    /** Stack frames. */
    FRAME,
    /** Metric names. */
    METRIC,
    /** Error group patterns. */
    ERROR_GROUP,
    /** JDBC targets. */
    DB_TARGET,
    /** Application versions. */
    APP_VERSION,
    /** Connection pool names (V003). */
    POOL;

    /** The {@code dict_kind} value. */
    public String pgName() {
      return name().toLowerCase(Locale.ROOT);
    }
  }

  /**
   * One dictionary entry.
   *
   * @param text the text (at most 64 KiB, 13 §12.3)
   * @param truncated whether the stored text was cut
   * @param normVersion the SQL normalizer version, null for other kinds
   */
  public record Entry(String text, boolean truncated, Integer normVersion) {}

  private record Key(long appId, Kind kind, long hash) {}

  private final JdbcTemplate jdbc;
  private final Cache<Key, Entry> cache;

  public DictionaryNames(DataSource dataSource) {
    JdbcTemplate t = new JdbcTemplate(dataSource);
    t.setQueryTimeout(5);
    this.jdbc = t;
    this.cache =
        Caffeine.newBuilder()
            .maximumWeight(MAX_CACHE_WEIGHT)
            .weigher((Key k, Entry e) -> e.text().length() + 64)
            .expireAfterAccess(Duration.ofHours(1))
            .build();
  }

  /** {@code <unknown:hash>} with the key in 16 lower-case hex digits. */
  public static String unknown(long hash) {
    return "<unknown:" + String.format(Locale.ROOT, "%016x", hash) + ">";
  }

  /**
   * The entries of {@code hashes}; keys without an entry and key 0 are absent from the result.
   *
   * @throws ApiException 503 {@code storage_unavailable} when PostgreSQL cannot be read
   */
  public Map<Long, Entry> entries(long appId, Kind kind, Collection<Long> hashes) {
    Objects.requireNonNull(kind, "kind");
    Map<Long, Entry> found = new LinkedHashMap<>();
    Set<Long> missing = new LinkedHashSet<>();
    for (Long hash : hashes) {
      if (hash == null || hash == 0L || found.containsKey(hash)) {
        continue;
      }
      Entry cached = cache.getIfPresent(new Key(appId, kind, hash));
      if (cached != null) {
        found.put(hash, cached);
      } else {
        missing.add(hash);
      }
    }
    if (missing.isEmpty()) {
      return found;
    }
    List<Long> list = new ArrayList<>(missing);
    for (int from = 0; from < list.size(); from += BATCH) {
      List<Long> batch = list.subList(from, Math.min(list.size(), from + BATCH));
      load(appId, kind, batch, found);
    }
    return found;
  }

  /**
   * The names of {@code hashes}: the text of each entry, {@code <unknown:hash>} for a key without
   * one; key 0 is left out.
   */
  public Map<Long, String> names(long appId, Kind kind, Collection<Long> hashes) {
    Map<Long, Entry> entries = entries(appId, kind, hashes);
    Map<Long, String> names = new LinkedHashMap<>();
    for (Long hash : hashes) {
      if (hash == null || hash == 0L || names.containsKey(hash)) {
        continue;
      }
      Entry e = entries.get(hash);
      names.put(hash, e == null ? unknown(hash) : e.text());
    }
    return names;
  }

  /** The name of one key ({@code <unknown:hash>} without an entry), null for key 0. */
  public String name(long appId, Kind kind, long hash) {
    if (hash == 0L) {
      return null;
    }
    return names(appId, kind, List.of(hash)).get(hash);
  }

  private void load(long appId, Kind kind, List<Long> batch, Map<Long, Entry> found) {
    try {
      jdbc.query(
          SELECT,
          ps -> {
            ps.setLong(1, appId);
            ps.setString(2, kind.pgName());
            Array array = ps.getConnection().createArrayOf("bigint", batch.toArray());
            ps.setArray(3, array);
          },
          rs -> {
            long hash = rs.getLong(1);
            String text = rs.getString(2);
            boolean truncated = rs.getBoolean(3);
            int norm = rs.getInt(4);
            Integer normVersion = rs.wasNull() ? null : norm;
            Entry entry = new Entry(text == null ? "" : text, truncated, normVersion);
            cache.put(new Key(appId, kind, hash), entry);
            found.put(hash, entry);
          });
    } catch (DataAccessException e) {
      LOG.warn(
          "Cannot read {} dictionary names from PostgreSQL: {}",
          kind.pgName(),
          e.getClass().getSimpleName());
      throw new ApiException(ErrorCode.STORAGE_UNAVAILABLE);
    }
  }
}
