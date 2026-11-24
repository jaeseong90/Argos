/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.storage.postgres.auth;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Server settings written by the setup ({@code settings}, 13 §12.6): the default IP collection mode
 * chosen in the setup wizard or by {@code ARGOS_IP_MODE} of an unattended installation (16 §6.10,
 * SEC-31). The value is a JSON string ({@code "full"}, {@code "masked"}, {@code "off"}).
 *
 * <p>WO-28 adds the retention set by {@code PUT /api/v1/admin/settings/retention} ({@value
 * #RETENTION}: a JSON object of the API fields with whole days, e.g. {@code {"xlog_days": 45}};
 * these values win over {@code argos.retention.*} of the configuration, PM question 3 assumption)
 * and reads the default IP mode back ({@code GET /api/v1/admin/settings/privacy}).
 */
@Repository
public class SettingsRepository {

  /** Key of the default IP mode (16 §6.10 {@code settings.default_ip_mode}). */
  public static final String DEFAULT_IP_MODE = "default_ip_mode";

  /** Key of the retention set through the API (WO-28). */
  public static final String RETENTION = "retention";

  /** Most days of one retention value (as the configuration allows). */
  private static final int MAX_RETENTION_DAYS = 36_500;

  private static final Pattern IP_MODE = Pattern.compile("full|masked|off");
  private static final Pattern RETENTION_FIELD = Pattern.compile("[a-z0-9_]{1,32}");
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final JdbcTemplate jdbc;

  public SettingsRepository(DataSource dataSource) {
    JdbcTemplate t = new JdbcTemplate(dataSource);
    t.setQueryTimeout(UserRepository.QUERY_TIMEOUT_SECONDS);
    this.jdbc = t;
  }

  /**
   * Stores the default IP mode.
   *
   * @param mode {@code full}, {@code masked} or {@code off}
   * @param updatedBy the administrator who chose it, or null (unattended setup)
   */
  public void saveDefaultIpMode(String mode, UUID updatedBy) {
    if (mode == null || !IP_MODE.matcher(mode).matches()) {
      throw new IllegalArgumentException("not an IP mode: " + mode);
    }
    jdbc.update(
        "INSERT INTO argos.settings (key, value, updated_by, updated_at)"
            + " VALUES (?, to_jsonb(CAST(? AS text)), ?, now())"
            + " ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value,"
            + " updated_by = EXCLUDED.updated_by, updated_at = now()",
        DEFAULT_IP_MODE,
        mode,
        updatedBy);
  }

  /** The stored default IP mode, or empty when none was chosen yet (or the value is not a mode). */
  public Optional<String> defaultIpMode() {
    List<String> values =
        jdbc.queryForList(
            "SELECT value #>> '{}' FROM argos.settings WHERE key = ?",
            String.class,
            DEFAULT_IP_MODE);
    return values.stream().filter(v -> v != null && IP_MODE.matcher(v).matches()).findFirst();
  }

  /**
   * The retention values stored through the API, by API field ({@code xlog_days}, ...); empty when
   * none. Entries that are not a field name with whole days between 1 and 36,500 are ignored.
   */
  public Map<String, Integer> retentionOverrides() {
    List<String> values =
        jdbc.queryForList(
            "SELECT value::text FROM argos.settings WHERE key = ?", String.class, RETENTION);
    if (values.isEmpty() || values.get(0) == null) {
      return Map.of();
    }
    JsonNode node;
    try {
      node = JSON.readTree(values.get(0));
    } catch (JacksonException e) {
      return Map.of();
    }
    Map<String, Integer> out = new LinkedHashMap<>();
    for (Map.Entry<String, JsonNode> e : node.properties()) {
      JsonNode v = e.getValue();
      if (RETENTION_FIELD.matcher(e.getKey()).matches()
          && v.isIntegralNumber()
          && v.asLong() >= 1
          && v.asLong() <= MAX_RETENTION_DAYS) {
        out.put(e.getKey(), v.asInt());
      }
    }
    return Collections.unmodifiableMap(out);
  }

  /**
   * Stores the retention values set through the API (replacing the stored object).
   *
   * @param values API field to whole days
   * @param updatedBy the administrator, or null
   */
  public void saveRetentionOverrides(Map<String, Integer> values, UUID updatedBy) {
    ObjectNode node = JSON.createObjectNode();
    values.forEach(
        (k, v) -> {
          if (!RETENTION_FIELD.matcher(k).matches()
              || v == null
              || v < 1
              || v > MAX_RETENTION_DAYS) {
            throw new IllegalArgumentException("not a retention value: " + k);
          }
          node.put(k, v);
        });
    jdbc.update(
        "INSERT INTO argos.settings (key, value, updated_by, updated_at)"
            + " VALUES (?, CAST(? AS jsonb), ?, now())"
            + " ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value,"
            + " updated_by = EXCLUDED.updated_by, updated_at = now()",
        RETENTION,
        JSON.writeValueAsString(node),
        updatedBy);
  }
}
