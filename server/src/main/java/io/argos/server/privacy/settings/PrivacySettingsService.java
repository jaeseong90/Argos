/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.privacy.settings;

import io.argos.server.audit.Actor;
import io.argos.server.audit.AuditEvent;
import io.argos.server.audit.AuditResult;
import io.argos.server.audit.AuditService;
import io.argos.server.audit.Target;
import io.argos.server.config.ArgosProperties;
import io.argos.server.storage.postgres.auth.SettingsRepository;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * The server-wide privacy defaults (14 §4.14 {@code /admin/settings/privacy}, 16 §6.10; WO-28): the
 * default IP collection mode, {@code settings.default_ip_mode} (chosen in the setup wizard or by
 * {@code ARGOS_IP_MODE}), else {@code argos.privacy.default-ip-mode}. A change is recorded as
 * {@code PRIVACY_SETTING_CHANGED} with the values before and after. It is the server's default
 * only: agents keep their local {@code capture.remote-ip} (hard cap), and nothing is pushed to
 * running agents (WO-28 plan, out of scope).
 */
@Service
public class PrivacySettingsService {

  /** The IP modes (SEC-31). */
  public static final Set<String> IP_MODES = Set.of("full", "masked", "off");

  private final SettingsRepository settings;
  private final ArgosProperties properties;
  private final AuditService audit;

  public PrivacySettingsService(
      SettingsRepository settings, ArgosProperties properties, AuditService audit) {
    this.settings = settings;
    this.properties = properties;
    this.audit = audit;
  }

  /**
   * The default IP mode and its origin.
   *
   * @param mode {@code full}, {@code masked} or {@code off}
   * @param source {@code settings} or {@code config}
   */
  public record IpModeSetting(String mode, String source) {}

  /** The effective default IP mode. */
  public IpModeSetting defaultIpMode() {
    Optional<String> stored = settings.defaultIpMode();
    return stored
        .map(m -> new IpModeSetting(m, "settings"))
        .orElseGet(
            () ->
                new IpModeSetting(
                    properties.privacy().defaultIpMode().name().toLowerCase(Locale.ROOT),
                    "config"));
  }

  /**
   * Stores a new default IP mode.
   *
   * @throws IllegalArgumentException for a value that is not a mode
   */
  public IpModeSetting setDefaultIpMode(String mode, UUID userId, Actor actor) {
    if (mode == null || !IP_MODES.contains(mode)) {
      throw new IllegalArgumentException("not an IP mode");
    }
    IpModeSetting before = defaultIpMode();
    settings.saveDefaultIpMode(mode, userId);
    audit.record(
        AuditEvent.PRIVACY_SETTING_CHANGED,
        actor,
        new Target("setting", "default_ip_mode", "default_ip_mode"),
        AuditResult.success(),
        Map.of("default_ip_mode", before.mode()),
        Map.of("default_ip_mode", mode));
    return defaultIpMode();
  }
}
