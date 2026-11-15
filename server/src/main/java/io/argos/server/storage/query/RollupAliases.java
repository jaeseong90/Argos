/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.storage.query;

import io.argos.server.api.error.ApiException;
import io.argos.server.api.error.ErrorCode;
import java.time.Clock;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The physical table of each logical rollup table (13 §13.2 step 3): the ClickHouse migration
 * runner switches a rebuilt rollup ({@code app_stat_1h_v2}) in by merging {@code {"app_stat_1h":
 * "app_stat_1h_v2"}} into the PostgreSQL setting {@code rollup_alias} ({@code argos.settings},
 * written by {@code MigrationStore#setRollupAlias}); every query of the API names a rollup through
 * {@code {rollup:<logical>}} and so through {@link #physical(String)}. A name without an entry is
 * its own table. Names are without database and prefix.
 *
 * <p>The setting is read at most every {@link #TTL} (a short cache: a switch reaches the queries
 * within seconds, and the hot path does not read PostgreSQL). A value that is not a plain table
 * name ({@code [A-Za-z0-9_]+}) is ignored with one WARN: it would be put into SQL text. When
 * PostgreSQL cannot be read the last map stays in use; before the first successful read the query
 * fails with 503 {@code storage_unavailable}.
 */
@Component
public class RollupAliases {

  /** How long a read of the setting is used. */
  public static final Duration TTL = Duration.ofSeconds(10);

  /** The settings key (13 §13.2). */
  public static final String SETTING_KEY = "rollup_alias";

  private static final Logger LOG = LoggerFactory.getLogger(RollupAliases.class);

  private static final Pattern TABLE = Pattern.compile("[A-Za-z0-9_]{1,128}");

  private static final String SELECT =
      "SELECT value::text FROM argos.settings WHERE key = '" + SETTING_KEY + "'";

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final JdbcTemplate jdbc;
  private final Clock clock;
  private final Object lock = new Object();
  private volatile Snapshot snapshot;
  private boolean failing; // guarded by lock: one WARN per failure streak

  public RollupAliases(DataSource dataSource, Clock clock) {
    JdbcTemplate t = new JdbcTemplate(dataSource);
    t.setQueryTimeout(5);
    this.jdbc = t;
    this.clock = clock;
  }

  /**
   * The physical table of a logical rollup table.
   *
   * @param logical a name of {@link ReaderQuery#ROLLUP_TABLES}
   * @throws ApiException 503 {@code storage_unavailable} when the setting was never readable
   */
  public String physical(String logical) {
    return aliases().getOrDefault(logical, logical);
  }

  /** Every alias currently in force, logical to physical. */
  public Map<String, String> aliases() {
    Snapshot current = snapshot;
    long now = clock.millis();
    if (current != null && now - current.loadedAtMillis < TTL.toMillis()) {
      return current.aliases;
    }
    synchronized (lock) {
      current = snapshot;
      if (current != null && now - current.loadedAtMillis < TTL.toMillis()) {
        return current.aliases;
      }
      try {
        Map<String, String> loaded = load();
        snapshot = new Snapshot(loaded, now);
        if (failing) {
          LOG.info("The rollup alias setting is readable again");
          failing = false;
        }
        return loaded;
      } catch (DataAccessException e) {
        if (!failing) {
          LOG.warn(
              "Cannot read the rollup alias setting from PostgreSQL ({}); {}",
              e.getClass().getSimpleName(),
              current == null ? "queries fail until it is readable" : "the last read stays in use");
          failing = true;
        }
        if (current == null) {
          throw new ApiException(ErrorCode.STORAGE_UNAVAILABLE);
        }
        // Keep the last map, retry after the next TTL.
        snapshot = new Snapshot(current.aliases, now);
        return current.aliases;
      }
    }
  }

  /** Forgets the cached map (tests and the migration runner's same-process switch). */
  public void invalidate() {
    snapshot = null;
  }

  private Map<String, String> load() {
    List<String> values = jdbc.queryForList(SELECT, String.class);
    if (values.isEmpty() || values.get(0) == null) {
      return Map.of();
    }
    return parse(values.get(0));
  }

  /** The valid entries of the JSON object {@code text}; others are logged and skipped. */
  static Map<String, String> parse(String text) {
    JsonNode root;
    try {
      root = JSON.readTree(text);
    } catch (JacksonException e) {
      LOG.warn("The rollup alias setting is not JSON; no alias is used");
      return Map.of();
    }
    if (root == null || !root.isObject()) {
      LOG.warn("The rollup alias setting is not a JSON object; no alias is used");
      return Map.of();
    }
    Map<String, String> aliases = new LinkedHashMap<>();
    for (Map.Entry<String, JsonNode> e : root.properties()) {
      String logical = e.getKey();
      JsonNode value = e.getValue();
      String physical = value != null && value.isString() ? value.asString() : null;
      if (!ReaderQuery.ROLLUP_TABLES.contains(logical)) {
        LOG.warn(
            "Rollup alias for an unknown rollup table ignored ({} characters)", logical.length());
        continue;
      }
      if (physical == null || !TABLE.matcher(physical).matches()) {
        LOG.warn("Rollup alias of {} ignored: the value is not a plain table name", logical);
        continue;
      }
      aliases.put(logical, physical);
    }
    return Collections.unmodifiableMap(aliases);
  }

  private record Snapshot(Map<String, String> aliases, long loadedAtMillis) {}
}
