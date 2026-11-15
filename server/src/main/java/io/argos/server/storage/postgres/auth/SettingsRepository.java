/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.storage.postgres.auth;

import java.util.UUID;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Server settings written by the setup ({@code settings}, 13 §12.6): the default IP collection mode
 * chosen in the setup wizard or by {@code ARGOS_IP_MODE} of an unattended installation (16 §6.10,
 * SEC-31). The value is a JSON string ({@code "full"}, {@code "masked"}, {@code "off"}).
 */
@Repository
public class SettingsRepository {

  /** Key of the default IP mode (16 §6.10 {@code settings.default_ip_mode}). */
  public static final String DEFAULT_IP_MODE = "default_ip_mode";

  private static final Pattern IP_MODE = Pattern.compile("full|masked|off");

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
}
