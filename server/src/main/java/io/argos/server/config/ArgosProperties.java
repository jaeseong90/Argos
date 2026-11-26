/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.config;

import io.argos.server.bootstrap.PhaseOrder;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.boot.context.properties.bind.Name;
import org.springframework.util.unit.DataSize;

/**
 * The server configuration tree {@code argos.*} (12 §10; key names per ADR-0010 C-96: the 12 §10
 * tree is the reference, keys missing from 12 are added to the same tree, the short environment
 * names of 17 §3.8 are mapped onto it by placeholders in {@code application.yml}, see {@link
 * EnvironmentAliases}). Environment variables also follow Spring's relaxed binding, e.g. {@code
 * argos.clickhouse.writer.password-file} = {@code ARGOS_CLICKHOUSE_WRITER_PASSWORD_FILE}.
 *
 * <p>Immutable records bound through their constructors; defaults are the 12 §10 values. The key
 * set, names, types and defaults are a public contract fixed by the config-key snapshot {@code
 * server/src/contractTest/resources/config-keys.txt} (T-API-009): a change to this tree updates the
 * snapshot in the same change.
 *
 * <p>Validation: the constraint annotations of the components are checked by the {@code
 * config-validation} start step ({@link ConfigValidation}, {@code CONFIG} phase), together with the
 * cross-field rules, so that a wrong setting stops the start with one ERROR line naming the keys.
 * Secrets ({@code password}, {@code token}, {@code master-key}) are read preferably from their
 * {@code *-file} key; a plain value from a configuration file logs a warning ({@link
 * ConfigKeyWarnings}); database passwords must pass the credential policy ({@link
 * CredentialPolicy}, SEC-74). {@link #toString()} never shows a secret.
 *
 * <p>Additions to 12 §10 (C-96), each with its reason: {@code postgres.owner.*} (C-11, C-97),
 * {@code postgres.password}, {@code clickhouse.*.password} (plain values, warned, 17 §6.1), {@code
 * clickhouse.table-prefix} (DSN-13-11), {@code clickhouse.migrator.*} (C-11), {@code
 * collector.bind} (16 §10.7; not fed by {@code ARGOS_GRPC_BIND}, a Compose publish address,
 * ADR-0010 C-113), {@code collector.limits.*} (the 11 {@code Limits} message), {@code alert.smtp.*}
 * (16 §8.5), {@code master-key} (12 §8.2 {@code ARGOS_MASTER_KEY}), {@code metrics.token} (16 §10.6
 * {@code ARGOS_METRICS_TOKEN}) and {@code testing.*} (18 §15). The management port of 12 §10
 * ({@code management.server.port} 9101) is 8081 on 127.0.0.1 (02 C-7) and stays a Spring key
 * ({@code application.yml}).
 *
 * <p>WO-24 additions: {@code jwt-key-file}, {@code jwt-key}, {@code jwt-previous-key-file} and
 * {@code jwt-previous-key} (16 §7.1 {@code conf/jwt.key} or {@code ARGOS_JWT_KEY}, the previous key
 * of a rotation; top level like {@code master-key}, so that the relaxed environment names are the
 * documented {@code ARGOS_JWT_KEY} and {@code ARGOS_JWT_KEY_FILE}), {@code admin-username} and
 * {@code admin-password-file} (unattended setup, 16 §7.8 / 17 §3.8 {@code ARGOS_ADMIN_USERNAME},
 * {@code ARGOS_ADMIN_PASSWORD_FILE}), {@code auth.setup-token-file} (16 §7.8 {@code
 * conf/setup-token}) and {@code security.*} (16 §7.1 {@code argos.security.cookie.secure}, 16 §7.5
 * and §15 #15 {@code argos.security.kiosk.*}).
 *
 * @param server server-wide settings
 * @param collector gRPC collector (WO-21)
 * @param wal write-ahead log (WO-21)
 * @param writer ClickHouse batch writer (WO-21)
 * @param clickhouse ClickHouse connection and accounts
 * @param postgres PostgreSQL connection and accounts
 * @param masterKeyFile master key file of the envelope cipher (WO-28)
 * @param masterKey master key as a value, normally from the environment variable {@code
 *     ARGOS_MASTER_KEY} (12 §8.2, WO-28)
 * @param jwtKeyFile Ed25519 signing key of the access tokens (PKCS#8 PEM), created at the first
 *     start when missing and {@code jwt-key} is not set (16 §7.1, WO-24)
 * @param jwtKey the signing key as a value, normally from the environment variable {@code
 *     ARGOS_JWT_KEY}; wins over {@code jwt-key-file} (16 §7.1, WO-24)
 * @param jwtPreviousKeyFile the previous signing key during a key rotation: tokens signed with it
 *     are still accepted until they expire, never issued (16 §7.1 "kid", WO-24)
 * @param jwtPreviousKey the previous signing key as a value; wins over {@code
 *     jwt-previous-key-file}
 * @param adminUsername first administrator of an unattended installation (with {@code
 *     admin-password-file} and an explicit {@code privacy.default-ip-mode}, 16 §7.8, WO-24)
 * @param adminPasswordFile file holding that administrator's initial password (0600; the password
 *     must be changed at the first login)
 * @param web web UI and REST API
 * @param auth user authentication (WO-24)
 * @param security cookie and kiosk-session settings of the UI sessions (16 §7.1, §7.5; WO-24)
 * @param ratelimit API rate limits (WO-24)
 * @param alert alert engine (WO-25)
 * @param agent agent registration policy (WO-22)
 * @param instance instance lifecycle (WO-22)
 * @param commands remote commands (WO-26)
 * @param retention data retention, applied to ClickHouse tables when they are created
 * @param privacy privacy defaults
 * @param ops operations jobs (WO-27, WO-28)
 * @param audit audit sinks (WO-28)
 * @param ui UI options
 * @param metrics authentication of the management port's info and metrics endpoints (SEC-55)
 * @param demo demo mode of the Compose demo profile (17 §3.2, §3.9; WO-41)
 * @param bootstrap applications and agent tokens registered at start (17 §3.8, §6.4; WO-41)
 * @param testing test hooks of development builds, ignored by release builds (18 §15)
 */
@ConfigurationProperties("argos")
public record ArgosProperties(
    @Valid @DefaultValue Server server,
    @Valid @DefaultValue Collector collector,
    @Valid @DefaultValue Wal wal,
    @Valid @DefaultValue Writer writer,
    @Valid @DefaultValue ClickHouse clickhouse,
    @Valid @DefaultValue Postgres postgres,
    @DefaultValue("conf/master.key") String masterKeyFile,
    String masterKey,
    @DefaultValue("conf/jwt.key") String jwtKeyFile,
    String jwtKey,
    String jwtPreviousKeyFile,
    String jwtPreviousKey,
    String adminUsername,
    String adminPasswordFile,
    @Valid @DefaultValue Web web,
    @Valid @DefaultValue Auth auth,
    @Valid @DefaultValue Security security,
    @Valid @DefaultValue RateLimit ratelimit,
    @Valid @DefaultValue Alert alert,
    @Valid @DefaultValue Agent agent,
    @Valid @DefaultValue Instance instance,
    @Valid @DefaultValue Commands commands,
    @Valid @DefaultValue Retention retention,
    @Valid @DefaultValue Privacy privacy,
    @Valid @DefaultValue Ops ops,
    @Valid @DefaultValue Audit audit,
    @Valid @DefaultValue Ui ui,
    @Valid @DefaultValue Metrics metrics,
    @Valid @DefaultValue Demo demo,
    @Valid @DefaultValue Bootstrap bootstrap,
    @Valid @DefaultValue Testing testing) {

  public ArgosProperties {
    masterKeyFile = blankToNull(masterKeyFile);
    masterKey = blankToNull(masterKey);
    jwtKeyFile = blankToNull(jwtKeyFile);
    jwtKey = blankToNull(jwtKey);
    jwtPreviousKeyFile = blankToNull(jwtPreviousKeyFile);
    jwtPreviousKey = blankToNull(jwtPreviousKey);
    adminUsername = blankToNull(adminUsername);
    adminPasswordFile = blankToNull(adminPasswordFile);
  }

  @Override
  public String toString() {
    return "ArgosProperties[server="
        + server
        + ", collector="
        + collector
        + ", wal="
        + wal
        + ", writer="
        + writer
        + ", clickhouse="
        + clickhouse
        + ", postgres="
        + postgres
        + ", masterKeyFile="
        + masterKeyFile
        + ", masterKey="
        + Credential.mask(masterKey)
        + ", jwtKeyFile="
        + jwtKeyFile
        + ", jwtKey="
        + Credential.mask(jwtKey)
        + ", jwtPreviousKeyFile="
        + jwtPreviousKeyFile
        + ", jwtPreviousKey="
        + Credential.mask(jwtPreviousKey)
        + ", adminUsername="
        + adminUsername
        + ", adminPasswordFile="
        + adminPasswordFile
        + ", web="
        + web
        + ", auth="
        + auth
        + ", security="
        + security
        + ", ratelimit="
        + ratelimit
        + ", alert="
        + alert
        + ", agent="
        + agent
        + ", instance="
        + instance
        + ", commands="
        + commands
        + ", retention="
        + retention
        + ", privacy="
        + privacy
        + ", ops="
        + ops
        + ", audit="
        + audit
        + ", ui="
        + ui
        + ", metrics="
        + metrics
        + ", demo="
        + demo
        + ", bootstrap="
        + bootstrap
        + ", testing="
        + testing
        + "]";
  }

  /** Null for a null or blank value (placeholders of {@code application.yml} default to ""). */
  static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }

  /**
   * A database or SMTP account: user name and password, the password preferably from a file ({@code
   * password-file}, 17 §6.1). {@link #toString()} never shows the password.
   *
   * @param user account name
   * @param passwordFile file holding the password (first line; a trailing line break is ignored)
   * @param password password given directly; accepted with a warning when it comes from a
   *     configuration file (17 §6.1)
   */
  public record Credential(String user, String passwordFile, String password) {

    public Credential {
      user = blankToNull(user);
      passwordFile = blankToNull(passwordFile);
      password = blankToNull(password);
    }

    /** This credential with {@code defaultUser} when no user is configured. */
    Credential withDefaultUser(String defaultUser) {
      return user == null ? new Credential(defaultUser, passwordFile, password) : this;
    }

    static Credential orDefault(Credential value, String defaultUser) {
      return (value == null ? new Credential(null, null, null) : value)
          .withDefaultUser(defaultUser);
    }

    /** {@code ****} for a set secret, {@code null} otherwise. */
    static String mask(String secret) {
      return secret == null ? "null" : "****";
    }

    @Override
    public String toString() {
      return "Credential[user="
          + user
          + ", passwordFile="
          + passwordFile
          + ", password="
          + mask(password)
          + "]";
    }
  }

  // --- storage
  // ------------------------------------------------------------------------------------

  /**
   * PostgreSQL (13 §12, C-11, C-97). The runtime account is {@code argos_app} ({@code user}, {@code
   * password-file}, {@code password}: the keys of 12 §10); Flyway runs with the schema owner
   * ({@code owner.*}, added by C-96) and closes that data source after the migration.
   *
   * @param url JDBC URL, e.g. {@code jdbc:postgresql://postgres:5432/argos}; required
   * @param user runtime account (default {@code argos_app})
   * @param passwordFile password file of the runtime account
   * @param password password of the runtime account (plain value, warned)
   * @param owner schema owner used by the PostgreSQL migration (default user {@code argos})
   * @param audit the audit retention account (default user {@code argos_audit}, WO-28): the daily
   *     audit partition job runs {@code audit_logs_maintain_partitions} with it; without a password
   *     the job is off (one INFO line at the start)
   */
  public record Postgres(
      @NotBlank(message = "is required: the JDBC URL of the Argos PostgreSQL database")
          @Pattern(
              regexp = "jdbc:postgresql:.*",
              message = "must be a PostgreSQL JDBC URL (jdbc:postgresql://host:5432/argos)")
          String url,
      @DefaultValue("argos_app") String user,
      String passwordFile,
      String password,
      Credential owner,
      Credential audit) {

    /** Default runtime account (C-11). */
    public static final String DEFAULT_APP_USER = "argos_app";

    /** Default schema owner: the database owner created with the PostgreSQL instance (17 §3.2). */
    public static final String DEFAULT_OWNER_USER = "argos";

    /** Default audit retention account (V001, 12 §8.1). */
    public static final String DEFAULT_AUDIT_USER = "argos_audit";

    public Postgres {
      url = blankToNull(url);
      user = blankToNull(user) == null ? DEFAULT_APP_USER : user;
      passwordFile = blankToNull(passwordFile);
      password = blankToNull(password);
      owner = Credential.orDefault(owner, DEFAULT_OWNER_USER);
      audit = Credential.orDefault(audit, DEFAULT_AUDIT_USER);
    }

    /** The runtime account as a {@link Credential}. */
    public Credential app() {
      return new Credential(user, passwordFile, password).withDefaultUser(DEFAULT_APP_USER);
    }

    @Override
    public String toString() {
      return "Postgres[url="
          + url
          + ", app="
          + app()
          + ", owner="
          + owner
          + ", audit="
          + audit
          + "]";
    }
  }

  /**
   * ClickHouse (13 §7.3, C-11, C-60). Three accounts: {@code writer} (INSERT on the five raw
   * tables, WO-21), {@code reader} (read-only queries, WO-23) and {@code migrator} (DDL: migration
   * runner, rebuild, deletion mutations; added by C-96).
   *
   * @param url HTTP endpoint
   * @param database database name ({@code {db}} of the schema templates)
   * @param tablePrefix table name prefix ({@code {p}}, DSN-13-11; added by C-96), empty by default
   * @param timezone server time zone the schema expects (13 §7.2)
   * @param writer batch writer account (default {@code argos_writer})
   * @param reader query account (default {@code argos_reader})
   * @param migrator DDL account (default {@code argos_migrator})
   * @param migration migration runner options (added by C-96; 12 §9.3 "--repair")
   */
  public record ClickHouse(
      @NotBlank(message = "is required: the HTTP URL of ClickHouse")
          @DefaultValue("http://clickhouse:8123")
          String url,
      @Pattern(regexp = "[A-Za-z_][A-Za-z0-9_]*", message = "must be a plain identifier")
          @DefaultValue("argos")
          String database,
      @Pattern(regexp = "[A-Za-z0-9_]*", message = "may contain letters, digits and _ only")
          @DefaultValue("")
          String tablePrefix,
      @DefaultValue("UTC") ZoneId timezone,
      Credential writer,
      Credential reader,
      Credential migrator,
      @Valid @DefaultValue Migration migration) {

    public ClickHouse {
      url = blankToNull(url);
      tablePrefix = tablePrefix == null ? "" : tablePrefix.strip();
      writer = Credential.orDefault(writer, "argos_writer");
      reader = Credential.orDefault(reader, "argos_reader");
      migrator = Credential.orDefault(migrator, "argos_migrator");
      migration = migration == null ? new Migration(false) : migration;
    }

    /**
     * Options of the ClickHouse migration runner (13 §13.2).
     *
     * @param repairChecksums one-shot repair (12 §9.3: a changed applied script is refused unless
     *     repaired): re-records the checksums of changed applied files after a WARN per version
     *     instead of refusing the start; remove the setting afterwards
     */
    public record Migration(@DefaultValue("false") boolean repairChecksums) {}
  }

  /**
   * Retention (12 §10 keys; 13 §8 days). Applied to ClickHouse tables when they are created ({@code
   * {retention.*}} of the schema templates); changing the retention of existing tables ({@code
   * MODIFY TTL}) is WO-28 (ADR-0010 C-99). Whole days, at least one.
   *
   * @param xlog {@code xlog} (13 §8: 30 days)
   * @param profile {@code profile_step} (7 days)
   * @param stat {@code sql_stat_raw}, {@code ext_stat_raw} (14 days)
   * @param metric {@code metric} (14 days)
   * @param rollup1m {@code *_1m} rollups, key {@code rollup-1m} (90 days)
   * @param rollup1h {@code *_1h} rollups, key {@code rollup-1h} (365 days)
   * @param commandResults PostgreSQL {@code command_results} (30 days, WO-26)
   * @param audit PostgreSQL {@code audit_logs} (365 days, WO-28)
   * @param alertEvents PostgreSQL alert events (365 days, WO-25)
   */
  public record Retention(
      @DefaultValue("30d") Duration xlog,
      @DefaultValue("7d") Duration profile,
      @DefaultValue("14d") Duration stat,
      @DefaultValue("14d") Duration metric,
      @Name("rollup-1m") @DefaultValue("90d") Duration rollup1m,
      @Name("rollup-1h") @DefaultValue("365d") Duration rollup1h,
      @DefaultValue("30d") Duration commandResults,
      @DefaultValue("365d") Duration audit,
      @DefaultValue("365d") Duration alertEvents) {}

  // --- other sections of 12 §10
  // ---------------------------------------------------------------------

  /**
   * Server-wide settings.
   *
   * @param timezone time zone of alert messages, default UTC (17 §3.8 {@code ARGOS_TZ_DISPLAY},
   *     ADR-0010 C-115; 12 §15 #8); storage is always UTC
   */
  public record Server(@DefaultValue("UTC") ZoneId timezone) {}

  /**
   * gRPC collector (12 §3.1, §10; WO-21).
   *
   * @param port gRPC port
   * @param bind listen address of the gRPC port, every interface by default (16 §10.7; the Compose
   *     variable {@code ARGOS_GRPC_BIND} is a host-side publish address, ADR-0010 C-113)
   * @param tls TLS of the gRPC listener
   * @param allowPlaintext plaintext gRPC (SEC-08, demo mode only)
   * @param maxMessageBytes maximum message size after decompression, at most {@link
   *     #MAX_MESSAGE_BYTES_UPPER}
   * @param limits batch limits sent to agents in {@code ServerHello} (11 {@code Limits})
   * @param quota per-instance quotas
   * @param session session limits
   * @param authFail authentication failure blocking
   * @param heartbeatInterval heartbeat interval sent to agents
   * @param staleAfter instance STALE after this silence (F-S28)
   * @param downAfter instance DOWN after this silence
   * @param byeGrace grace period after AgentBye
   * @param publicAddress the collector address agents use ({@code host:port}) when it differs from
   *     the host the browser reaches the UI on (a reverse proxy, NAT): returned with an issued
   *     agent token for the setup snippet (ADR-0010 C-231 (9)); none by default (the UI then uses
   *     its own host and port 9100)
   */
  public record Collector(
      @Min(1) @Max(65535) @DefaultValue("9100") int port,
      @NotBlank @DefaultValue("0.0.0.0") String bind,
      @Valid @DefaultValue Tls tls,
      @DefaultValue("false") boolean allowPlaintext,
      @Min(1024) @Max(Collector.MAX_MESSAGE_BYTES_UPPER) @DefaultValue("4194304")
          int maxMessageBytes,
      @Valid @DefaultValue Limits limits,
      @Valid @DefaultValue Quota quota,
      @Valid @DefaultValue Session session,
      @Valid @DefaultValue AuthFail authFail,
      @DefaultValue("30s") Duration heartbeatInterval,
      @DefaultValue("60s") Duration staleAfter,
      @DefaultValue("180s") Duration downAfter,
      @DefaultValue("5m") Duration byeGrace,
      @Pattern(regexp = "[A-Za-z0-9.:\\[\\]-]{1,255}") String publicAddress) {

    /**
     * Largest {@code argos.collector.max-message-bytes} (63 MiB): every accepted batch is one WAL
     * data record, and a record of the largest message with its session envelope must stay readable
     * by recovery (a WAL body is at most 64 MiB) and fit into the WAL writer queue (64 MiB of
     * waiting data records); a larger message would be acknowledged but never recovered, or refused
     * on every resend (D-13).
     */
    public static final int MAX_MESSAGE_BYTES_UPPER = 63 * 1024 * 1024;

    public Collector {
      bind = blankToNull(bind) == null ? "0.0.0.0" : bind.strip();
      publicAddress = blankToNull(publicAddress) == null ? null : publicAddress.strip();
    }

    /**
     * TLS of the gRPC listener.
     *
     * @param enabled TLS on
     * @param certFile certificate chain (PEM)
     * @param keyFile private key (PEM): unencrypted PKCS#8, or encrypted PKCS#8 with {@code
     *     key-password-file}
     * @param keyPasswordFile file holding the password of an encrypted PKCS#8 key (first line; 16
     *     §3.1, ADR-0010 C-158 (5)), none by default
     * @param reload certificate reload (16 §3.1 {@code reload.watch}; WO-28)
     */
    public record Tls(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("conf/tls/server.crt") String certFile,
        @DefaultValue("conf/tls/server.key") String keyFile,
        String keyPasswordFile,
        @Valid @DefaultValue Reload reload) {

      public Tls {
        certFile = blankToNull(certFile) == null ? "conf/tls/server.crt" : certFile;
        keyFile = blankToNull(keyFile) == null ? "conf/tls/server.key" : keyFile;
        keyPasswordFile = blankToNull(keyPasswordFile);
        reload = reload == null ? new Reload(true) : reload;
      }

      /**
       * Certificate reload (16 §3.1).
       *
       * @param watch watch the certificate and key files ({@code WatchService}, 5 s debounce) and
       *     swap the TLS context of new handshakes when they change; false: manual reload only
       *     ({@code POST /api/v1/admin/tls/reload}, {@code argos-admin tls reload})
       */
      public record Reload(@DefaultValue("true") boolean watch) {}
    }

    /**
     * Batch limits of the 11 {@code Limits} message, sent to agents in {@code ServerHello} and
     * checked again by the server (SEC-11). {@code max_message_bytes} is {@code
     * collector.max-message-bytes}; the one {@code max_frames} field of the message carries the
     * error (30) or stack (64) bound, here two keys.
     *
     * @param maxTransactionsPerBatch transactions per batch
     * @param maxStepsPerBatch profile steps per batch
     * @param maxDetailBytes bytes of a step detail
     * @param maxStringBytes bytes of any other string
     * @param maxErrorFrames frames of an error step
     * @param maxStackFrames frames of a stack step
     * @param maxActiveItems active transactions per snapshot
     * @param maxSeriesPerInstance metric series per instance
     * @param maxDictionaryEntriesPerHour new dictionary entries per instance and hour
     */
    public record Limits(
        @Positive @DefaultValue("5000") int maxTransactionsPerBatch,
        @Positive @DefaultValue("50000") int maxStepsPerBatch,
        @Positive @DefaultValue("4096") int maxDetailBytes,
        @Positive @DefaultValue("2048") int maxStringBytes,
        @Positive @DefaultValue("30") int maxErrorFrames,
        @Positive @DefaultValue("64") int maxStackFrames,
        @Positive @DefaultValue("500") int maxActiveItems,
        @Positive @DefaultValue("500") int maxSeriesPerInstance,
        @Positive @DefaultValue("10000") int maxDictionaryEntriesPerHour) {}

    /**
     * Per-instance quotas (SEC-12). Token buckets hold twice the rate (16 §4.3); items beyond a
     * quota are removed from the end of the batch (DSN-12-04, ADR-0010 C-130 (1)).
     *
     * @param txPerSecond transactions per second
     * @param stepsPerSecond profile steps per second
     * @param metricsPer5s metric points per 5 s (key {@code metrics-per-5s})
     * @param statsPer10s statistics rows ({@code SqlStat} + {@code ExtStat}) per 10 s (key {@code
     *     stats-per-10s}; 16 §4.3, ADR-0010 C-130 (4), WO-21): 6,000 by default (bucket 12,000,
     *     ADR-0010 C-157 (3)), the largest legal {@code StatBatch} (5,000 SQL + 1,000 outgoing-call
     *     rows), so that an agent sending it every window loses nothing
     */
    public record Quota(
        @Positive @DefaultValue("2000") int txPerSecond,
        @Positive @DefaultValue("20000") int stepsPerSecond,
        @Positive @DefaultValue("1000") int metricsPer5s,
        @Positive @DefaultValue("6000") int statsPer10s) {}

    /**
     * Session limits (SEC-13).
     *
     * @param maxPerToken concurrent sessions per token
     * @param newPerMinute new sessions per minute
     * @param instancesPerApp instances per app
     * @param newInstancesPerHour new instances per hour
     */
    public record Session(
        @Positive @DefaultValue("100") int maxPerToken,
        @Positive @DefaultValue("60") int newPerMinute,
        @Positive @DefaultValue("1000") int instancesPerApp,
        @Positive @DefaultValue("100") int newInstancesPerHour) {}

    /**
     * Authentication failure blocking per source IP (SEC-15).
     *
     * @param perIpPerMinute failures per IP per minute before blocking
     * @param blockMinutes block duration in minutes
     */
    public record AuthFail(
        @Positive @DefaultValue("20") int perIpPerMinute,
        @Positive @DefaultValue("10") int blockMinutes) {}
  }

  /**
   * Write-ahead log (12 §3.4; WO-21).
   *
   * @param dir WAL directory
   * @param maxBytes upper bound of the WAL size
   * @param segmentBytes segment size
   * @param fsyncIntervalMs group fsync interval
   * @param warnRatio warning threshold of {@code maxBytes}
   */
  public record Wal(
      @NotBlank @DefaultValue("data/wal") String dir,
      @NotNull @DefaultValue("10GB") DataSize maxBytes,
      @NotNull @DefaultValue("256MB") DataSize segmentBytes,
      @Min(1) @Max(1000) @DefaultValue("10") int fsyncIntervalMs,
      @DecimalMin(value = "0.0", inclusive = false) @DecimalMax("1.0") @DefaultValue("0.8")
          double warnRatio) {}

  /**
   * ClickHouse batch writer (12 §3.8, 13 §7.4; WO-21).
   *
   * @param flushIntervalMs flush interval per table
   * @param flushRows flush size per table
   * @param queueRows queue bound per table
   * @param deadLetter where blocks that ClickHouse refuses deterministically go (ADR-0010 C-158
   *     (3); WO-28)
   */
  public record Writer(
      @Positive @DefaultValue("2000") int flushIntervalMs,
      @Positive @DefaultValue("50000") int flushRows,
      @Positive @DefaultValue("200000") int queueRows,
      @Valid @DefaultValue DeadLetter deadLetter) {

    /**
     * The dead-letter area of the writer (ADR-0010 C-158 (3)): a block that ClickHouse refuses with
     * a schema, type or parse error code three times is written here (directory 0700, files 0600 on
     * POSIX) and its WAL range is committed; nothing here is deleted automatically.
     *
     * @param dir directory of the block files
     * @param maxBytes upper bound of all block files; a block that does not fit is discarded and
     *     counted like a stored one
     */
    public record DeadLetter(
        @NotBlank @DefaultValue("data/dead-letter") String dir,
        @NotNull @DefaultValue("1GB") DataSize maxBytes) {}
  }

  /**
   * Web UI and REST API.
   *
   * @param publicUrl external URL of the UI ({@code https://argos.example.com}): Origin check and
   *     deep links (12 §10, required); also the WebSocket origin of the CSP {@code connect-src}
   * @param csp Content-Security-Policy of the UI page (16 §9.9; WO-24)
   */
  public record Web(
      @NotBlank(
              message =
                  "is required: the external URL of the web UI, e.g. https://argos.example.com")
          String publicUrl,
      @Valid @DefaultValue Csp csp) {

    public Web {
      publicUrl = blankToNull(publicUrl);
    }

    /**
     * Content-Security-Policy of the UI page (16 §9.9, SEC-46, DSN-16-15).
     *
     * @param styleUnsafeInline {@code style-src 'self' 'unsafe-inline'} instead of the per-request
     *     nonce: the documented fallback of 16 §9.9 for a component library that cannot attach the
     *     nonce to its style elements (16 §15 #6, decided by the UI work order). Off by default
     */
    public record Csp(@DefaultValue("false") boolean styleUnsafeInline) {}
  }

  /**
   * User authentication (12 §7.2; WO-24).
   *
   * @param accessTtl access token lifetime
   * @param refreshIdleTtl refresh token idle lifetime
   * @param refreshMaxTtl refresh token absolute lifetime
   * @param maxSessionsPerUser concurrent sessions per user
   * @param password password policy
   * @param lockout login lockout
   * @param setupTokenTtl lifetime of the setup token (SEC-47)
   * @param setupTokenFile file the setup token is written to (0600 on POSIX; deleted when the first
   *     administrator is created, 16 §7.8; WO-24)
   */
  public record Auth(
      @DefaultValue("15m") Duration accessTtl,
      @DefaultValue("8h") Duration refreshIdleTtl,
      @DefaultValue("24h") Duration refreshMaxTtl,
      @Positive @DefaultValue("5") int maxSessionsPerUser,
      @Valid @DefaultValue Password password,
      @Valid @DefaultValue Lockout lockout,
      @DefaultValue("30m") Duration setupTokenTtl,
      @DefaultValue("conf/setup-token") String setupTokenFile) {

    public Auth {
      setupTokenFile = blankToNull(setupTokenFile);
    }

    /**
     * Password policy (SEC-45).
     *
     * @param minLength minimum length
     * @param maxLength maximum length
     * @param bannedListFile extra banned passwords (the built-in list is always used)
     */
    public record Password(
        @Min(12) @Max(1024) @DefaultValue("12") int minLength,
        @Min(12) @Max(1024) @DefaultValue("128") int maxLength,
        String bannedListFile) {

      public Password {
        bannedListFile = blankToNull(bannedListFile);
      }
    }

    /**
     * Login lockout (progressive, x2 per repetition).
     *
     * @param failures failures before lockout
     * @param baseMinutes first lockout duration in minutes
     */
    public record Lockout(
        @Positive @DefaultValue("10") int failures,
        @Positive @DefaultValue("15") int baseMinutes) {}
  }

  /**
   * Security settings of the UI sessions (16 §7.1, §7.5; WO-24).
   *
   * @param cookie session cookies
   * @param kiosk long sessions of approved viewer accounts (F-U21)
   * @param masterKey the previous master key of a rotation and the file permission override (16
   *     §8.2; WO-25)
   */
  public record Security(
      @Valid @DefaultValue Cookie cookie,
      @Valid @DefaultValue Kiosk kiosk,
      @Valid @DefaultValue MasterKey masterKey) {

    /**
     * Master key settings besides {@code argos.master-key} and {@code argos.master-key-file} (16
     * §8.2; WO-25). The previous key only decrypts (a rotation in progress: the server reads
     * envelopes of both key ids, 16 §8.2); new envelopes always use the current key.
     *
     * @param previous the previous key as a value (64 hex characters or base64 of 32 bytes),
     *     normally from the environment variable {@code ARGOS_SECURITY_MASTER_KEY_PREVIOUS}
     * @param previousFile file holding the previous key
     * @param permissive accept a key file that group or others can read (16 §8.2; warned at every
     *     start). Without it such a file refuses the start (ADR-0010 C-226 (2), WO-28)
     */
    public record MasterKey(
        String previous, String previousFile, @DefaultValue("false") boolean permissive) {

      public MasterKey {
        previous = blankToNull(previous);
        previousFile = blankToNull(previousFile);
      }

      @Override
      public String toString() {
        return "MasterKey[previous="
            + Credential.mask(previous)
            + ", previousFile="
            + previousFile
            + ", permissive="
            + permissive
            + "]";
      }
    }

    /**
     * Session cookies (16 §7.1).
     *
     * @param secure the {@code Secure} attribute on {@code argos_at}, {@code argos_rt} and {@code
     *     argos_csrf}. Always true in production; false is accepted for non-production setups
     *     without TLS and logs a warning at every start
     */
    public record Cookie(@DefaultValue("true") boolean secure) {}

    /**
     * Kiosk sessions (16 §7.5, §15 #15; ADR-0010 C-106 (5)): a viewer account with {@code
     * kiosk_allowed} that logs in from one of its allowed address ranges gets these lifetimes
     * instead of {@code auth.refresh-idle-ttl} and {@code auth.refresh-max-ttl}.
     *
     * @param enabled whether kiosk sessions are issued at all (off by default)
     * @param idleTtl idle lifetime of a kiosk session
     * @param maxTtl absolute lifetime of a kiosk session
     */
    public record Kiosk(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("24h") Duration idleTtl,
        @DefaultValue("30d") Duration maxTtl) {}
  }

  /**
   * API rate limits (SEC-56; WO-24).
   *
   * @param loginPerIpPerMinute requests per client address per minute to login, refresh and the
   *     setup endpoints (14 §2.7, 16 §7.13)
   * @param apiPerUserPerSecond API calls per session per second (refill rate of the general bucket)
   * @param exportPerUserPerMinute heavy requests (search, CSV export, long traces; handlers with
   *     {@code @RateLimited(HEAVY)}) per user per minute
   * @param apiBurstPerUser capacity of the general bucket per session: the burst allowed above the
   *     per-second rate (16 §7.13; WO-24); at least {@code api-per-user-per-second} is used
   * @param cspReportPerIpPerMinute CSP violation reports per client address per minute (16 §9.9;
   *     WO-24)
   */
  public record RateLimit(
      @Positive @DefaultValue("20") int loginPerIpPerMinute,
      @Positive @DefaultValue("50") int apiPerUserPerSecond,
      @Positive @DefaultValue("10") int exportPerUserPerMinute,
      @Positive @DefaultValue("100") int apiBurstPerUser,
      @Positive @DefaultValue("60") int cspReportPerIpPerMinute) {}

  /**
   * Alert engine (12 §5; WO-25).
   *
   * @param evaluationInterval evaluation period
   * @param groupWindow grouping window
   * @param windowRetention in-memory metric window
   * @param webhook webhook destination rules (SEC-51)
   * @param smtp mail server of the e-mail channel (SEC-54, 16 §8.5)
   * @param recommendedRules recommended default rules (F-S24)
   */
  public record Alert(
      @DefaultValue("10s") Duration evaluationInterval,
      @DefaultValue("30s") Duration groupWindow,
      @DefaultValue("15m") Duration windowRetention,
      @Valid @DefaultValue Webhook webhook,
      @Valid @DefaultValue Smtp smtp,
      @Valid @DefaultValue RecommendedRules recommendedRules) {

    /**
     * Webhook destination rules (SEC-51).
     *
     * @param allowHttp allow plain http destinations
     * @param allowCidrs private ranges allowed as destinations (empty: none)
     * @param timeout delivery timeout
     */
    public record Webhook(
        @DefaultValue("false") boolean allowHttp,
        List<String> allowCidrs,
        @DefaultValue("5s") Duration timeout) {

      public Webhook {
        allowCidrs = allowCidrs == null ? List.of() : List.copyOf(allowCidrs);
      }
    }

    /**
     * Mail server of the e-mail channel (SEC-54, 16 §8.5; WO-25). STARTTLS with certificate
     * verification is required unless {@code insecure} is set, which logs a warning at start.
     *
     * @param host SMTP host; none: the e-mail channel is unavailable
     * @param port SMTP port (submission, 587)
     * @param username SMTP user, if the server requires authentication
     * @param passwordFile file holding the SMTP password
     * @param password SMTP password as a value (warned when it comes from a configuration file)
     * @param starttls {@code required} (default) or {@code optional} (only with {@code insecure})
     * @param checkServerIdentity verify the host name of the server certificate
     * @param insecure allow plaintext and unverified connections (explicit opt-out, warned)
     * @param allowedRecipientDomains recipient domains allowed (empty: all)
     * @param from sender address of the alert mails ({@code MAIL FROM} and {@code From}); none: the
     *     user name when it is an address, otherwise {@code argos@<public-url host>} (WO-25)
     */
    public record Smtp(
        String host,
        @Min(1) @Max(65535) @DefaultValue("587") int port,
        String username,
        String passwordFile,
        String password,
        @NotNull @DefaultValue("required") StartTls starttls,
        @DefaultValue("true") boolean checkServerIdentity,
        @DefaultValue("false") boolean insecure,
        List<String> allowedRecipientDomains,
        String from) {

      public Smtp {
        host = blankToNull(host);
        username = blankToNull(username);
        passwordFile = blankToNull(passwordFile);
        password = blankToNull(password);
        from = blankToNull(from);
        allowedRecipientDomains =
            allowedRecipientDomains == null ? List.of() : List.copyOf(allowedRecipientDomains);
      }

      /** STARTTLS mode of the SMTP connection. */
      public enum StartTls {
        /** The connection must be upgraded to TLS; otherwise the delivery fails. */
        REQUIRED,
        /** Upgrade when the server offers it (needs {@code insecure: true}). */
        OPTIONAL
      }

      @Override
      public String toString() {
        return "Smtp[host="
            + host
            + ", port="
            + port
            + ", username="
            + username
            + ", passwordFile="
            + passwordFile
            + ", password="
            + Credential.mask(password)
            + ", starttls="
            + starttls
            + ", checkServerIdentity="
            + checkServerIdentity
            + ", insecure="
            + insecure
            + ", allowedRecipientDomains="
            + allowedRecipientDomains
            + ", from="
            + from
            + "]";
      }
    }

    /**
     * Recommended rules created for new apps.
     *
     * @param enabled create them
     */
    public record RecommendedRules(@DefaultValue("true") boolean enabled) {}
  }

  /**
   * Agent registration (F-S30; WO-22).
   *
   * @param autoRegister auto-registration per environment ({@code default} applies to the others)
   */
  public record Agent(Map<String, Boolean> autoRegister) {

    public Agent {
      autoRegister = autoRegister == null ? Map.of("default", true) : Map.copyOf(autoRegister);
    }
  }

  /**
   * Instance lifecycle (12 §4.3; WO-22).
   *
   * @param archiveAfter archive instances silent for this long
   * @param purgeAfter delete archived instances after this long
   */
  public record Instance(
      @DefaultValue("7d") Duration archiveAfter, @DefaultValue("30d") Duration purgeAfter) {}

  /**
   * Remote commands (12 §6.1, 16 §5.2 {@code argos.commands.*}; WO-26). The result retention is
   * {@code argos.retention.command-results} (12 §10; 16 §5.3 names it {@code
   * argos.commands.result-retention-days}, the 12 §10 key is the one kept).
   *
   * @param ttl a queued command not sent within this long expires and is never delivered; also the
   *     {@code ttl_s} the agent measures from its receive time (60 s)
   * @param resultTimeout a sent command without a complete result within this long expires with
   *     error {@code timeout} (16 §5.2 TIMEOUT, 120 s)
   * @param maxInflightPerInstance commands queued or sent per instance at most (409 beyond)
   * @param maxPerMinutePerInstance commands requested per instance per minute at most (429 beyond)
   * @param keepPerInstance commands (with their results) kept per instance at most, the newest
   *     first (16 §5.3)
   */
  public record Commands(
      @DefaultValue("60s") Duration ttl,
      @DefaultValue("120s") Duration resultTimeout,
      @DefaultValue("1") @Min(1) @Max(10) int maxInflightPerInstance,
      @DefaultValue("10") @Min(1) @Max(600) int maxPerMinutePerInstance,
      @DefaultValue("100") @Min(1) @Max(10000) int keepPerInstance) {

    public Commands {
      if (ttl == null || ttl.isNegative() || ttl.isZero() || ttl.toSeconds() > 3600) {
        throw new IllegalArgumentException("argos.commands.ttl must be between 1s and 1h");
      }
      if (resultTimeout == null || resultTimeout.compareTo(Duration.ofSeconds(1)) < 0) {
        throw new IllegalArgumentException("argos.commands.result-timeout must be at least 1s");
      }
    }
  }

  /**
   * Privacy defaults.
   *
   * @param defaultIpMode IP collection mode preselected by the setup wizard (12 §15; 17 §3.8 {@code
   *     ARGOS_IP_MODE})
   * @param deletion the privacy deletion jobs (13 §9, SEC-33; WO-28)
   */
  public record Privacy(
      @NotNull @DefaultValue("masked") IpMode defaultIpMode,
      @Valid @DefaultValue Deletion deletion) {

    /**
     * Privacy deletion jobs ({@code POST /api/v1/admin/privacy/deletions}, 13 §9).
     *
     * @param maxConcurrentMutations deletion mutations of Argos pending in ClickHouse at most (13
     *     §9: 2); further requests wait in the queue
     * @param maxRange longest range of one request (14 §4.14: 30 days)
     */
    public record Deletion(
        @Min(1) @Max(16) @DefaultValue("2") int maxConcurrentMutations,
        @NotNull @DefaultValue("30d") Duration maxRange) {}

    /** Client IP collection mode (SEC-31). */
    public enum IpMode {
      /** Full address. */
      FULL,
      /** Masked address (last octet / last 80 bits). */
      MASKED,
      /** Not collected. */
      OFF
    }
  }

  /**
   * Operations jobs (12 §9; WO-27, WO-28).
   *
   * @param reconcile nightly consistency check
   * @param disk disk thresholds (F-S23)
   * @param storageHealthInterval storage health poll interval
   * @param emergency emergency retention shortening (F-S23, 13 §8; WO-28)
   * @param dictionaryCleanup nightly dictionary clean-up (SEC-13, 17 §9.3; WO-29)
   * @param errorGroupCleanup nightly error group clean-up (13 §12.4 table; ADR-0010 C-255 (5))
   */
  public record Ops(
      @Valid @DefaultValue Reconcile reconcile,
      @Valid @DefaultValue Disk disk,
      @DefaultValue("1m") Duration storageHealthInterval,
      @Valid @DefaultValue Emergency emergency,
      @Valid @DefaultValue DictionaryCleanup dictionaryCleanup,
      @Valid @DefaultValue ErrorGroupCleanup errorGroupCleanup) {

    /**
     * Nightly error group clean-up (13 §12.4 storage table, ADR-0010 C-255 (5)): groups not seen
     * for 90 days are deleted.
     *
     * @param cron Spring cron expression (six fields), server time zone; the default 03:45 runs
     *     after the consistency check (03:00) and the dictionary clean-up (03:30)
     * @param enabled false: the nightly job is not scheduled
     */
    public record ErrorGroupCleanup(
        @NotBlank @DefaultValue("0 45 3 * * *") String cron,
        @DefaultValue("true") boolean enabled) {}

    /**
     * Nightly dictionary clean-up (SEC-13, 17 §9.3, ADR-0010 C-238 (14)): entries not seen for 90
     * days are deleted.
     *
     * @param cron Spring cron expression (six fields), server time zone
     * @param enabled false: the nightly job is not scheduled ({@code argos-admin cleanup
     *     dictionary} still works)
     */
    public record DictionaryCleanup(
        @NotBlank @DefaultValue("0 30 3 * * *") String cron,
        @DefaultValue("true") boolean enabled) {}

    /**
     * Nightly consistency check (12 §9.1).
     *
     * @param cron Spring cron expression (six fields), server time zone
     * @param enabled false: the nightly job is not scheduled ({@code argos-admin consistency check}
     *     still works; ADR-0010 C-220 (3))
     */
    public record Reconcile(
        @NotBlank @DefaultValue("0 0 3 * * *") String cron,
        @DefaultValue("true") boolean enabled) {}

    /**
     * Disk thresholds (F-S23). {@code warn-ratio < emergency-ratio < stop-ratio <= 1}.
     *
     * @param warnRatio warning
     * @param emergencyRatio emergency retention
     * @param stopRatio stop ClickHouse inserts, WAL only
     */
    public record Disk(
        @DecimalMin(value = "0.0", inclusive = false) @DecimalMax("1.0") @DefaultValue("0.8")
            double warnRatio,
        @DecimalMin(value = "0.0", inclusive = false) @DecimalMax("1.0") @DefaultValue("0.9")
            double emergencyRatio,
        @DecimalMin(value = "0.0", inclusive = false) @DecimalMax("1.0") @DefaultValue("0.95")
            double stopRatio) {}

    /**
     * Emergency retention shortening (F-S23, 13 §8): from {@code disk.emergency-ratio} the oldest
     * day partition of the raw tables is dropped, one at a time, until the ClickHouse disk is below
     * {@code target-ratio} ({@code target-ratio < disk.emergency-ratio}).
     *
     * @param targetRatio the shortening stops below this ratio
     * @param dropsPerMinute partitions dropped per minute at most
     */
    public record Emergency(
        @DecimalMin(value = "0.0", inclusive = false) @DecimalMax("1.0") @DefaultValue("0.85")
            double targetRatio,
        @Min(1) @Max(600) @DefaultValue("1") int dropsPerMinute) {}
  }

  /**
   * Audit sinks (12 §8.1, 16 §11.3, §11.4; WO-28).
   *
   * @param sinks {@code postgres} (mandatory), {@code syslog://host[:514]} (UDP), {@code
   *     syslog+tcp://host[:514]}, {@code syslog+tls://host[:6514]}, {@code
   *     file:logs/audit/audit.jsonl}; any other form refuses the start
   * @param file size rolling of the JSON Lines sinks and the fallback file
   * @param fallbackFile JSON Lines file of the records the PostgreSQL sink could not write
   * @param agentEventsPerMinute agent audit events ({@code AGENT_AUTH_FAILED}, {@code
   *     AGENT_AUTH_BLOCKED}, {@code AGENT_SESSION_REJECTED}) recorded per event, client address or
   *     token prefix and minute; the others are counted into the next record (ADR-0010 C-158 (1))
   */
  public record Audit(
      List<String> sinks,
      @Valid @DefaultValue AuditFile file,
      @NotBlank @DefaultValue("logs/audit/audit-fallback.jsonl") String fallbackFile,
      @Min(1) @Max(600) @DefaultValue("1") int agentEventsPerMinute) {

    public Audit {
      sinks = sinks == null ? List.of("postgres") : List.copyOf(sinks);
      file = file == null ? new AuditFile(DataSize.ofMegabytes(100), 10) : file;
      fallbackFile = fallbackFile == null ? "logs/audit/audit-fallback.jsonl" : fallbackFile;
    }

    /**
     * Size rolling of an audit JSON Lines file.
     *
     * @param maxBytes size of one file before it rolls
     * @param maxFiles files kept per sink, the current one included
     */
    public record AuditFile(
        @NotNull @DefaultValue("100MB") DataSize maxBytes,
        @Min(2) @Max(1000) @DefaultValue("10") int maxFiles) {}
  }

  /**
   * UI options.
   *
   * @param logLinkTemplate link template to the application's log system (F-U18), none by default
   * @param activeThresholdsMs the operator-wide default elapsed-colour thresholds of the active
   *     transactions screen in milliseconds, three positive ascending values (ADR-0010 C-225 (3),
   *     WO-39); a viewer's own choice in the browser wins. A wrong value does not stop the start:
   *     the server logs a WARN and uses the default ({@code api.active.ActiveThresholds})
   */
  public record Ui(
      String logLinkTemplate, @DefaultValue("1000,3000,8000") List<Integer> activeThresholdsMs) {

    public Ui {
      logLinkTemplate = blankToNull(logLinkTemplate);
      activeThresholdsMs =
          activeThresholdsMs == null
              ? null
              : java.util.Collections.unmodifiableList(
                  new java.util.ArrayList<>(activeThresholdsMs));
    }
  }

  /**
   * Info and metrics endpoints of the management port (SEC-55, C-7, 16 §7.12): {@code
   * /actuator/info} and {@code /actuator/prometheus} need the bearer token and a client address
   * inside {@code allow-cidrs}; {@code /actuator/health} needs neither.
   *
   * @param allowCidrs client address ranges allowed (empty: none, every request is refused)
   * @param tokenFile file holding the bearer token
   * @param token bearer token as a value, normally from the environment variable {@code
   *     ARGOS_METRICS_TOKEN} (16 §10.6)
   */
  public record Metrics(List<String> allowCidrs, String tokenFile, String token) {

    public Metrics {
      allowCidrs = allowCidrs == null ? List.of() : List.copyOf(allowCidrs);
      tokenFile = blankToNull(tokenFile);
      token = blankToNull(token);
    }

    @Override
    public String toString() {
      return "Metrics[allowCidrs="
          + allowCidrs
          + ", tokenFile="
          + tokenFile
          + ", token="
          + Credential.mask(token)
          + "]";
    }
  }

  /**
   * Demo mode (17 §3.2, §3.9, O-A01; WO-41), set by the Compose demo profile ({@code
   * ARGOS_DEMO_MODE=true}, written only by {@code argos-env-init.sh --demo}). When enabled the
   * server supplies {@code collector.tls.enabled=false} and {@code collector.allow-plaintext=true}
   * as defaults (an explicit setting of either key wins; {@code io.argos.server.demo}), logs a
   * warning at every start, and {@code GET /api/v1/setup/status} reports {@code demo_mode} so that
   * the UI shows the demo banner. Never enabled by a production installation.
   *
   * @param enabled whether this server runs the demo
   */
  public record Demo(@DefaultValue("false") boolean enabled) {}

  /**
   * Applications and agent tokens registered at every start (17 §3.8 {@code
   * ARGOS_BOOTSTRAP_APPS_FILE}, §6.4; WO-41), idempotently ({@code
   * io.argos.server.admin.bootstrap}): a YAML file {@code apps: [{name, env, token-file}]} whose
   * token files hold tokens generated by the installation ({@code argos_} + 43 base64url
   * characters); the server stores their SHA-256 and prefix only.
   *
   * @param appsFile the YAML file; none by default
   */
  public record Bootstrap(String appsFile) {

    public Bootstrap {
      appsFile = blankToNull(appsFile);
    }
  }

  /**
   * Test hooks (18 §15): honoured by development builds only. A release build (a version without
   * {@code -SNAPSHOT}, or an unknown version: fail-closed like ADR-0009 C-89) ignores every key
   * here and logs a warning when one is set ({@link TestingHooks}).
   *
   * @param holdBeforePhase hold the start sequence before the steps of this phase while {@code
   *     hold-file} exists (readiness tests)
   * @param holdFile the hold lasts while this file exists; {@code <hold-file>.reached} is created
   *     when the hold begins
   * @param holdTimeout the start fails when the hold lasts longer
   * @param notifyRetryDelays the waits before the retries of a failed notification delivery instead
   *     of 1 s, 5 s and 30 s (12 §5.5; WO-25 T-SV-034); exactly three values
   * @param alertCaFile a PEM file of CA certificates that the notification channels (webhook,
   *     Slack, SMTP) trust in addition to the JDK's trust store (in-process fake receivers of the
   *     tests; WO-25)
   * @param diskUsageFile a file holding the used ratio (0..1) that the storage health job takes as
   *     the ClickHouse disk usage instead of {@code system.disks} while the file exists and parses
   *     (emergency retention tests; WO-28)
   * @param ingestFaultFile a file whose content ({@code UNAVAILABLE|INTERNAL|RESOURCE_EXHAUSTED|
   *     QUOTA}{@code [:ratio]}) makes the collector refuse or quota-trim data batches while it
   *     exists (chaos tests 18 §11.1 T-CH-004, {@code IngestFaultInjector}; WO-52)
   */
  public record Testing(
      PhaseOrder holdBeforePhase,
      String holdFile,
      @DefaultValue("5m") Duration holdTimeout,
      List<Duration> notifyRetryDelays,
      String alertCaFile,
      String diskUsageFile,
      String ingestFaultFile) {

    public Testing {
      holdFile = blankToNull(holdFile);
      notifyRetryDelays = notifyRetryDelays == null ? List.of() : List.copyOf(notifyRetryDelays);
      alertCaFile = blankToNull(alertCaFile);
      diskUsageFile = blankToNull(diskUsageFile);
      ingestFaultFile = blankToNull(ingestFaultFile);
    }

    /** Whether any hook is configured. */
    public boolean anySet() {
      return holdBeforePhase != null
          || holdFile != null
          || !notifyRetryDelays.isEmpty()
          || alertCaFile != null
          || diskUsageFile != null
          || ingestFaultFile != null;
    }
  }
}
