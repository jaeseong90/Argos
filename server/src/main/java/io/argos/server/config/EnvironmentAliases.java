/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.config;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * The short environment variable names of 17 §3.8 and §3.2 (ADR-0010 C-96) and the setting each one
 * feeds. The mapping itself is a placeholder in the packaged {@code application.yml}, e.g. {@code
 * argos.postgres.url: ${ARGOS_PG_URL:}}; this class is the documented list of those names, used to
 * keep them out of the unknown-key warnings and to name the source of a value ({@link
 * EffectiveServerConfig}). {@code ApplicationYamlTest} checks that both agree; the names and their
 * keys are a public contract (17 §6.1, ADR-0010 C-96) fixed by the hand-maintained snapshot {@code
 * server/src/contractTest/resources/config-env-aliases.txt} ({@code ConfigEnvAliasSnapshotTest},
 * T-API-009): a change here updates that file in the same change.
 *
 * <p>Precedence: the canonical names (an {@code argos.*} key in a configuration file, a system
 * property, a command line argument or the relaxed environment name such as {@code
 * ARGOS_POSTGRES_URL}) win over the short name, which only feeds the packaged {@code
 * application.yml}. Every other {@code argos.*} key has its relaxed environment name too ({@code
 * ARGOS_METRICS_TOKEN}, {@code ARGOS_MASTER_KEY_FILE}, ...).
 */
public final class EnvironmentAliases {

  /** Short name to the key it sets; retention values are whole days. */
  public static final Map<String, String> ALIASES = aliases();

  /**
   * Variables of the installation (17 §3.8) that the server does not read itself, or that a later
   * work order reads: never reported as unknown settings.
   */
  public static final Set<String> DEPLOYMENT_VARIABLES =
      Set.of(
          "ARGOS_VERSION",
          "ARGOS_MGMT_EXPOSE",
          "ARGOS_SERVER_MEM",
          "ARGOS_CH_MEM",
          "ARGOS_PG_MEM",
          "ARGOS_SERVER_JAVA_OPTS",
          "ARGOS_START_PERIOD",
          // host-side publish addresses of the Compose ports (17 §3.8, ADR-0010 C-113): the server
          // listens on every interface (server.address, argos.collector.bind)
          "ARGOS_HTTP_BIND",
          "ARGOS_GRPC_BIND",
          // read through the relaxed names of their keys (unattended setup WO-24/WO-40, JWT key
          // WO-24)
          "ARGOS_ADMIN_PASSWORD_FILE",
          "ARGOS_JWT_KEY",
          "ARGOS_JWT_KEY_FILE");

  private EnvironmentAliases() {}

  private static Map<String, String> aliases() {
    Map<String, String> m = new LinkedHashMap<>();
    m.put("ARGOS_LOG_LEVEL", "logging.level.root");
    m.put("ARGOS_TZ_DISPLAY", "argos.server.timezone");
    m.put("ARGOS_IP_MODE", "argos.privacy.default-ip-mode");
    m.put("ARGOS_RETENTION_XLOG_DAYS", "argos.retention.xlog");
    m.put("ARGOS_RETENTION_PROFILE_DAYS", "argos.retention.profile");
    m.put("ARGOS_RETENTION_RAW_STAT_DAYS", "argos.retention.stat");
    m.put("ARGOS_RETENTION_METRIC_DAYS", "argos.retention.metric");
    m.put("ARGOS_RETENTION_ROLLUP_1M_DAYS", "argos.retention.rollup-1m");
    m.put("ARGOS_RETENTION_ROLLUP_1H_DAYS", "argos.retention.rollup-1h");
    m.put("ARGOS_PG_URL", "argos.postgres.url");
    m.put("ARGOS_PG_USER", "argos.postgres.user");
    m.put("ARGOS_PG_PASSWORD_FILE", "argos.postgres.password-file");
    m.put("ARGOS_PG_OWNER_USER", "argos.postgres.owner.user");
    m.put("ARGOS_PG_OWNER_PASSWORD_FILE", "argos.postgres.owner.password-file");
    m.put("ARGOS_CH_URL", "argos.clickhouse.url");
    m.put("ARGOS_CH_DATABASE", "argos.clickhouse.database");
    m.put("ARGOS_CH_WRITER_USER", "argos.clickhouse.writer.user");
    m.put("ARGOS_CH_WRITER_PASSWORD_FILE", "argos.clickhouse.writer.password-file");
    m.put("ARGOS_CH_READER_USER", "argos.clickhouse.reader.user");
    m.put("ARGOS_CH_READER_PASSWORD_FILE", "argos.clickhouse.reader.password-file");
    m.put("ARGOS_CH_MIGRATOR_USER", "argos.clickhouse.migrator.user");
    m.put("ARGOS_CH_MIGRATOR_PASSWORD_FILE", "argos.clickhouse.migrator.password-file");
    m.put("ARGOS_TLS_CERT_FILE", "argos.collector.tls.cert-file");
    m.put("ARGOS_TLS_KEY_FILE", "argos.collector.tls.key-file");
    // WO-41: the Compose demo profile (17 §3.2) and the applications registered at start (17 §3.8)
    m.put("ARGOS_DEMO_MODE", "argos.demo.enabled");
    m.put("ARGOS_BOOTSTRAP_APPS_FILE", "argos.bootstrap.apps-file");
    return Collections.unmodifiableMap(m);
  }

  /** Whether {@code variable} is a documented name (alias or installation variable). */
  public static boolean isDocumented(String variable) {
    return ALIASES.containsKey(variable) || DEPLOYMENT_VARIABLES.contains(variable);
  }
}
