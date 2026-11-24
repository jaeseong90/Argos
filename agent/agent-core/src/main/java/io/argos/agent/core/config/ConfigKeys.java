/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Registry of every configuration key of the agent (10 §15 table, in its order), plus the
 * diagnostics keys ({@code debug.*}, ADR-0009 C-89) and the test-only keys ({@code test.*}, 18 §19,
 * T-BLD-010). Each key is a {@link KeySpec} with type, documented default, the "원격" column,
 * secrecy, bounds and description; {@link AgentConfig} parses values with it and {@code
 * ConfigLoader} warns about keys that are not here (02 §7 "알 수 없는 키는 WARN").
 *
 * <p>Names are canonical: dot notation without the {@code argos.} prefix ({@code log.level}). The
 * system property is {@code -Dargos.<key>} ({@link #PROPERTY_PREFIX}) and the environment variable
 * {@code ARGOS_<KEY>} ({@link #environmentName(String)}). 06 SEC-23 writes the kill switch as
 * {@code argos.enabled}: that is the {@code -D} form of the key {@code enabled}, not another key.
 *
 * <p>Not here on purpose: the WO-02 PoC keys {@code exporter}, {@code exporter.file.path} and
 * {@code exporter.file.max-mb} (ADR-0009 C-88: file and noop exporters are {@code test.exporter}
 * only), and keys that 16 §6.1 names but 10 §15 does not ({@code capture.user-id.max-length}, fixed
 * at 128 by SEC-32 in {@code CaptureConfig}).
 *
 * <p>Two table rows have a structured value: {@code sampling.profile.slow-threshold-ms} is "int or
 * {default, services{}}" (the per-service values are the family {@value
 * #SLOW_THRESHOLD_SERVICES}{@code <service name>}), and {@code instrumentation.custom-methods} is a
 * list of {pattern, as-transaction} written as list items {@code <class glob>#<method
 * glob>[|<as-transaction>]} ({@link KeySpec.Format#CUSTOM_METHOD}; the step is always METHOD, an
 * item with a step type suffix of the earlier format is ignored, ADR-0010 C-237 (5)).
 */
public final class ConfigKeys {
  /** System property prefix ({@code -Dargos.<key>}). */
  public static final String PROPERTY_PREFIX = "argos.";

  /** Environment variable prefix ({@code ARGOS_<KEY>}). */
  public static final String ENVIRONMENT_PREFIX = "ARGOS_";

  /**
   * The token environment variable of SEC-06 and 16 §2.6 ({@code ARGOS_TOKEN}); the generic name of
   * {@code server.token} would be {@code ARGOS_SERVER_TOKEN}.
   */
  public static final String TOKEN_ENVIRONMENT = "ARGOS_TOKEN";

  /**
   * Location of {@code argos-agent.yml}: the agent argument {@code config=<path>} (17 §
   * installation, {@code -javaagent:argos-agent.jar=config=/opt/argos-agent/argos-agent.yml}), or
   * {@code -Dargos.config} / {@code ARGOS_CONFIG}. An input of {@code ConfigLoader}, not a
   * configuration key, so it is not in the registry and never reported as unknown.
   */
  public static final String CONFIG_FILE = "config";

  /** Test-only namespace of the agent (18 §15, DSN-18-15): ignored in release builds. */
  public static final String TEST_PREFIX = "test.";

  /** Test-only namespace of the server ({@code argos.testing.*}); never read by the agent. */
  public static final String TESTING_PREFIX = "testing.";

  // --- server.* -------------------------------------------------------------------------------
  public static final String SERVER_ADDRESS = "server.address";
  public static final String SERVER_TLS = "server.tls";
  public static final String SERVER_TLS_CA_FILE = "server.tls.ca-file";
  public static final String SERVER_TLS_INSECURE_SKIP_VERIFY = "server.tls.insecure-skip-verify";
  public static final String SERVER_TOKEN = "server.token";
  public static final String SERVER_TOKEN_FILE = "server.token-file";
  public static final String SERVER_CONNECT_TIMEOUT_MS = "server.connect-timeout-ms";
  public static final String SERVER_DEADLINE_MS = "server.deadline-ms";

  // --- app.* ----------------------------------------------------------------------------------
  public static final String APP_NAME = "app.name";
  public static final String APP_ENV = "app.env";
  public static final String APP_INSTANCE = "app.instance";
  public static final String APP_VERSION = "app.version";
  public static final String APP_GROUP = "app.group";

  // --- kill switch ----------------------------------------------------------------------------
  public static final String ENABLED = "enabled";
  public static final String AGENT_DISABLED = "agent.disabled";

  // --- sampling.* -----------------------------------------------------------------------------
  public static final String SLOW_THRESHOLD_MS = "sampling.profile.slow-threshold-ms";

  /** Prefix of the per-service slow thresholds ({@code ...services.<service name>}). */
  public static final String SLOW_THRESHOLD_SERVICES =
      "sampling.profile.slow-threshold-ms.services.";

  public static final String NORMAL_RATE = "sampling.profile.normal-rate";
  public static final String MAX_STEPS = "sampling.profile.max-steps";

  // --- capture.* ------------------------------------------------------------------------------
  public static final String CAPTURE_QUERY_STRING = "capture.query-string";
  public static final String CAPTURE_QUERY_STRING_WHITELIST = "capture.query-string.whitelist";
  public static final String CAPTURE_HTTP_HEADERS = "capture.http-headers";

  /**
   * One request header whose value becomes {@code TransactionRecord.request_id} (ADR-0010 C-124):
   * empty by default (not collected), not remotely settable unless allowed, value cut at 128 bytes.
   */
  public static final String CAPTURE_REQUEST_ID_HEADER = "capture.request-id-header";

  public static final String CAPTURE_REMOTE_IP = "capture.remote-ip";
  public static final String CAPTURE_TRUSTED_PROXIES = "capture.trusted-proxies";
  public static final String CAPTURE_USER_ID_MODE = "capture.user-id.mode";
  public static final String CAPTURE_USER_ID_SOURCE = "capture.user-id.source";
  public static final String CAPTURE_SQL_PARAMETERS = "capture.sql-parameters";
  public static final String CAPTURE_SQL_PARAMETERS_WHITELIST_COLUMNS =
      "capture.sql-parameters.whitelist-columns";
  public static final String CAPTURE_MASK_PATTERNS = "capture.mask-patterns";
  public static final String CAPTURE_ERROR_MESSAGE_MAX = "capture.error-message-max-length";
  public static final String CAPTURE_STACK_DEPTH = "capture.stack-depth";

  // --- instrumentation.* ----------------------------------------------------------------------
  public static final String INSTRUMENTATION_DISABLED = "instrumentation.disabled";
  public static final String INSTRUMENTATION_EXCLUDE_CLASSES = "instrumentation.exclude-classes";
  public static final String INSTRUMENTATION_IGNORE_PATHS = "instrumentation.ignore-paths";
  public static final String INSTRUMENTATION_IGNORE_EXTENSIONS =
      "instrumentation.ignore-extensions";
  public static final String INSTRUMENTATION_IGNORE_USER_AGENTS =
      "instrumentation.ignore-user-agents";
  public static final String INSTRUMENTATION_IGNORE_METHODS = "instrumentation.ignore-methods";
  public static final String INSTRUMENTATION_CUSTOM_METHODS = "instrumentation.custom-methods";
  public static final String INSTRUMENTATION_CUSTOM_METHODS_MAX_CLASSES =
      "instrumentation.custom-methods.max-classes-per-pattern";
  public static final String INSTRUMENTATION_APP_PACKAGES = "instrumentation.app-packages";
  public static final String INSTRUMENTATION_SERVICE_DICTIONARY_MAX =
      "instrumentation.service-dictionary-max";
  public static final String INSTRUMENTATION_ERROR_THRESHOLD = "instrumentation.error-threshold";
  public static final String INSTRUMENTATION_TRANSFORM_ERROR_THRESHOLD =
      "instrumentation.transform-error-threshold";

  // --- transaction.* --------------------------------------------------------------------------
  public static final String TRANSACTION_MAX_DURATION_MS = "transaction.max-duration-ms";
  public static final String SLOW_STACK_ENABLED = "transaction.slow-stack.enabled";
  public static final String SLOW_STACK_INTERVAL_MS = "transaction.slow-stack.interval-ms";
  public static final String SLOW_STACK_MAX_CAPTURES = "transaction.slow-stack.max-captures";
  public static final String SLOW_STACK_MAX_THREADS_PER_CYCLE =
      "transaction.slow-stack.max-threads-per-cycle";
  public static final String SLOW_STACK_DEPTH = "transaction.slow-stack.depth";

  // --- propagation.* --------------------------------------------------------------------------
  public static final String PROPAGATION_EXCLUDE_HOSTS = "propagation.exclude-hosts";
  public static final String PROPAGATION_ARGOS_STATE_HOSTS = "propagation.argos-state.hosts";
  public static final String PROPAGATION_PRESERVE_INCOMING = "propagation.preserve-incoming";

  /** ASYNC step per propagated executor task (10 §6.6, §15; default false). */
  public static final String ASYNC_STEP = "async.step";

  // --- buffer.*, sender.* ---------------------------------------------------------------------
  public static final String TRANSACTION_CAPACITY = "buffer.transaction.capacity";
  public static final String BUFFER_MAX_AGE_SECONDS = "buffer.max-age-seconds";
  public static final String BUFFER_MAX_BYTES = "buffer.max-bytes";
  public static final String SENDER_FLUSH_INTERVAL_MS = "sender.flush-interval-ms";
  public static final String SENDER_BATCH_SIZE = "sender.batch-size";
  public static final String SENDER_MAX_IN_FLIGHT = "sender.max-in-flight";
  public static final String SENDER_COMPRESSION = "sender.compression";

  // --- metrics.*, stats.*, active.* -----------------------------------------------------------
  public static final String METRICS_INTERVAL_SECONDS = "metrics.interval-seconds";
  public static final String METRICS_MAX_SERIES = "metrics.max-series";
  public static final String STATS_WINDOW_SECONDS = "stats.window-seconds";
  public static final String STATS_MAX_KEYS = "stats.max-keys";
  public static final String ACTIVE_SNAPSHOT_INTERVAL_SECONDS = "active.snapshot-interval-seconds";

  // --- sql.* ----------------------------------------------------------------------------------
  public static final String SQL_MAX_LENGTH = "sql.max-length";
  public static final String SQL_BACKSLASH_ESCAPE = "sql.backslash-escape";
  public static final String SQL_KEEP_HINTS = "sql.keep-hints";

  // --- commands.* -----------------------------------------------------------------------------
  public static final String ENV_DUMP_MASK_PATTERNS = "commands.env-dump.mask-patterns";
  public static final String THREAD_DUMP_COOLDOWN_SECONDS = "commands.thread-dump.cooldown-seconds";
  public static final String THREAD_DUMP_MAX_BYTES = "commands.thread-dump.max-bytes";

  // --- remote-config.*, log.* -----------------------------------------------------------------
  public static final String REMOTE_CONFIG_ALLOW = "remote-config.allow";
  public static final String LOG_LEVEL = "log.level";
  public static final String LOG_DIR = "log.dir";
  public static final String LOG_MAX_FILE_MB = "log.max-file-mb";
  public static final String LOG_MAX_FILES = "log.max-files";

  // --- diagnostics (allowed in release builds, C-89) ------------------------------------------
  public static final String DEBUG_TRANSFORM_TIMING = "debug.transform-timing";

  /**
   * Transformation log (18 §5.6, DSN-18-05, T-AG-005): every transformed class with its loader and
   * modules, every {@code onError} event and the {@code ClassCircularityError} count, rewritten by
   * the watch thread. Class and loader names only, so release builds honour it (C-89).
   */
  public static final String DEBUG_TRANSFORM_LOG = "debug.transform-log";

  /**
   * Transformed-class list (10 §5.2 step 4, baseline diff, N-27), rewritten by the watch thread.
   */
  public static final String DEBUG_TRANSFORM_LIST = "debug.transform-list";

  // --- test-only (development builds, T-BLD-010) ----------------------------------------------
  /**
   * Test-only exporter selection (18 §5.4, 10 §19, C-88): {@code file:<dir>} (JSONL in {@code
   * <dir>/argos-transactions.jsonl}), {@code noop} (encodes every transaction, then drops it),
   * {@code drop} (drops without encoding: the WO-02 P-2 baseline, C-80) or {@code grpc} (the
   * default exporter).
   */
  public static final String TEST_EXPORTER = "test.exporter";

  /**
   * Test-only fault injection (18 §5.7, 10 §19, ADR-0009 C-86, T-AG-040/041): module-level specs
   * {@code advice-throw:<module id>}, {@code transform-error:<module id>}, {@code
   * helper-nocdef:<module id>}.
   */
  public static final String TEST_FAULTS = "test.faults";

  /**
   * Test-only self-diagnostics file (ADR-0010 C-95, 18 §19): development builds write the
   * self-diagnostics snapshot to this file; release builds ignore the key.
   */
  public static final String TEST_SELFDIAG_FILE = "test.selfdiag-file";

  /**
   * Test-only switch of the agent's CPU guard (ADR-0010 C-240 (3)): {@code off} keeps the {@code
   * OverheadMonitor} from halving the profile {@code normal-rate}, so that a suite that assumes
   * {@code normal-rate=1.0} is not shaken on a CI runner with few cores. Development builds only,
   * the same rule as the C-95 test keys: release builds drop it with the T-BLD-010 WARN.
   */
  public static final String TEST_OVERHEAD_MONITOR = "test.overhead-monitor";

  /**
   * The default of {@code remote-config.allow}: the 10 §15 list (ADR-0010 C-240 (4); every key it
   * names is inside the remote hard cap of 16 §5.5: none is immutable, none is a default-deny key
   * of SEC-23), with {@code sampling.*} written {@code sampling.**} so that it reaches the {@code
   * sampling.profile.*} keys (C-118). It widens the five SEC-23 patterns of C-92 by the keys whose
   * Remote column is {@code Y}; {@code instrumentation.exclude-classes} (Y) is not in 10 §15 and
   * needs the local list.
   */
  public static final String REMOTE_CONFIG_ALLOW_DEFAULT =
      "sampling.**, instrumentation.disabled, instrumentation.ignore-*, log.level, enabled,"
          + " transaction.slow-stack.*, capture.error-message-max-length, capture.stack-depth,"
          + " instrumentation.app-packages, async.step, active.snapshot-interval-seconds,"
          + " metrics.interval-seconds, transaction.max-duration-ms";

  private static final Map<String, KeySpec> SPECS = new LinkedHashMap<String, KeySpec>();
  private static final List<KeySpec> FAMILIES = new ArrayList<KeySpec>();

  static {
    // server.*
    add(
        str(SERVER_ADDRESS, null, "Collector address host:port (default port 9100).")
            .format(KeySpec.Format.HOST_PORT)
            .maxLength(512)
            .required());
    add(bool(SERVER_TLS, "true", "TLS to the collector; false sends plaintext (WARN)."));
    add(path(SERVER_TLS_CA_FILE, "PEM file of an own CA that signed the collector certificate."));
    add(
        bool(
            SERVER_TLS_INSECURE_SKIP_VERIFY,
            "false",
            "Skips certificate verification (WARN on every connection)."));
    add(
        str(SERVER_TOKEN, null, "Agent token written in plain text (WARN once); prefer a file.")
            .secret()
            .maxLength(512));
    add(path(SERVER_TOKEN_FILE, "File that holds the agent token (0600 recommended)."));
    add(integer(SERVER_CONNECT_TIMEOUT_MS, "5000", 1, 600_000, "Connect timeout in milliseconds."));
    add(integer(SERVER_DEADLINE_MS, "5000", 1, 600_000, "Deadline of one RPC in milliseconds."));

    // app.*
    add(str(APP_NAME, null, "Application name.").maxLength(256).required());
    add(
        str(APP_ENV, "default", "Environment name; 'default' with a WARN when unset.")
            .maxLength(256));
    add(
        str(APP_INSTANCE, null, "Instance name; automatic (host:port, else host:pid) when unset.")
            .maxLength(256));
    add(
        str(APP_VERSION, null, "Application version; the jar manifest Implementation-Version.")
            .maxLength(256));
    add(str(APP_GROUP, null, "Group of the application on the overview.").maxLength(256));

    // kill switch
    add(
        bool(ENABLED, "true", "Kill switch, first stage: false makes every advice a no-op.")
            .remote(KeySpec.Remote.Y));
    add(
        bool(
                AGENT_DISABLED,
                "false",
                "Kill switch, second stage (-Dargos.agent.disabled only): installs nothing.")
            .propertyOnly());

    // sampling.*
    add(
        integer(
                SLOW_THRESHOLD_MS,
                "1000",
                0,
                86_400_000,
                "Slow threshold (ms) above which a profile is always sent; per service below.")
            .remote(KeySpec.Remote.Y));
    add(
        integer(
                SLOW_THRESHOLD_SERVICES + "*",
                null,
                0,
                86_400_000,
                "Slow threshold (ms) of one service: ...slow-threshold-ms.services.<service name>.")
            .remote(KeySpec.Remote.Y));
    add(
        KeySpec.builder(NORMAL_RATE, KeySpec.Type.DOUBLE)
            .defaultValue("0.1")
            .range(0.0, 1.0)
            .remote(KeySpec.Remote.Y)
            .description("Share (0..1) of normal transactions whose profile is sent."));
    add(
        integer(
                MAX_STEPS,
                "1000",
                1,
                10_000,
                "Steps kept per transaction; further steps are summarized.")
            .remote(KeySpec.Remote.Y));

    // capture.*
    add(
        enumeration(
                CAPTURE_QUERY_STRING,
                "off",
                "Query string capture: off, whitelist or full.",
                "off",
                "whitelist",
                "full")
            .remote(KeySpec.Remote.N));
    add(
        list(CAPTURE_QUERY_STRING_WHITELIST, "", "Query parameters kept in whitelist mode.")
            .format(KeySpec.Format.QUERY_PARAM)
            .items(64, 256)
            .remote(KeySpec.Remote.N));
    add(
        list(CAPTURE_HTTP_HEADERS, "", "Request headers to capture (values up to 1 KB).")
            .format(KeySpec.Format.HEADER_NAME)
            .items(10, 128)
            .remote(KeySpec.Remote.N));
    add(
        str(
                CAPTURE_REQUEST_ID_HEADER,
                null,
                "Request header sent as the transaction's request id (128 B); empty: not collected.")
            .format(KeySpec.Format.HEADER_NAME)
            .maxLength(128)
            .remote(KeySpec.Remote.N));
    add(
        enumeration(
                CAPTURE_REMOTE_IP,
                "masked",
                "Client IP capture: full, masked or off.",
                "full",
                "masked",
                "off")
            .remote(KeySpec.Remote.N));
    add(
        list(CAPTURE_TRUSTED_PROXIES, "", "Proxy CIDRs whose X-Forwarded-For is trusted.")
            .format(KeySpec.Format.CIDR)
            .items(64, 64)
            .remote(KeySpec.Remote.N));
    add(
        enumeration(
                CAPTURE_USER_ID_MODE,
                "off",
                "User id capture: off, plain or hashed.",
                "off",
                "plain",
                "hashed")
            .remote(KeySpec.Remote.N));
    add(
        str(
                CAPTURE_USER_ID_SOURCE,
                null,
                "User id source: header:<name>, session:<attribute> or mdc:<key>.")
            .format(KeySpec.Format.USER_ID_SOURCE)
            .maxLength(264)
            .remote(KeySpec.Remote.N));
    add(
        enumeration(
                CAPTURE_SQL_PARAMETERS,
                "masked",
                "Bind parameter capture: masked, partial or full.",
                "masked",
                "partial",
                "full")
            .remote(KeySpec.Remote.N));
    add(
        list(
                CAPTURE_SQL_PARAMETERS_WHITELIST_COLUMNS,
                "",
                "Columns whose bind values are kept (masked) in partial mode.")
            .format(KeySpec.Format.COLUMN)
            .items(256, 128)
            .remote(KeySpec.Remote.N));
    add(
        list(
                CAPTURE_MASK_PATTERNS,
                "",
                "Extra masking regular expressions, applied after the built-in set.")
            .format(KeySpec.Format.REGEX)
            .items(32, 512) // MaskPatterns.MAX_USER_PATTERNS, MAX_USER_PATTERN_CHARS
            .remote(KeySpec.Remote.N));
    add(
        integer(
                CAPTURE_ERROR_MESSAGE_MAX,
                "1024",
                0,
                65_536,
                "Characters of an exception message kept after masking.")
            .remote(KeySpec.Remote.Y));
    add(
        integer(CAPTURE_STACK_DEPTH, "30", 0, 64, "Frames of an ERROR step stack.")
            .remote(KeySpec.Remote.Y));

    // instrumentation.*
    add(
        list(INSTRUMENTATION_DISABLED, "", "Instrumentation module ids to disable.")
            .format(KeySpec.Format.MODULE_ID)
            .items(64, 64)
            .remote(KeySpec.Remote.Y));
    add(
        list(INSTRUMENTATION_EXCLUDE_CLASSES, "", "Class globs never transformed (field bypass).")
            .format(KeySpec.Format.CLASS_GLOB)
            .items(256, 512)
            .remote(KeySpec.Remote.Y));
    add(
        list(
                INSTRUMENTATION_IGNORE_PATHS,
                "/actuator/health, /actuator/health/**, /health, /healthz, /ready, /favicon.ico",
                "Request path globs that are not recorded.")
            .format(KeySpec.Format.GLOB)
            .items(256, 1024)
            .remote(KeySpec.Remote.Y));
    add(
        list(
                INSTRUMENTATION_IGNORE_EXTENSIONS,
                "css, js, map, png, jpg, jpeg, gif, svg, ico, woff, woff2, ttf, eot",
                "Request path extensions that are not recorded.")
            .format(KeySpec.Format.EXTENSION)
            .items(128, 16)
            .remote(KeySpec.Remote.Y));
    add(
        list(INSTRUMENTATION_IGNORE_USER_AGENTS, "", "User-Agent globs that are not recorded.")
            .format(KeySpec.Format.GLOB)
            .items(64, 1024)
            .remote(KeySpec.Remote.Y));
    add(
        list(INSTRUMENTATION_IGNORE_METHODS, "OPTIONS", "HTTP methods that are not recorded.")
            .format(KeySpec.Format.HTTP_METHOD)
            .items(16, 16)
            .remote(KeySpec.Remote.Y));
    add(
        list(
                INSTRUMENTATION_CUSTOM_METHODS,
                "",
                "Custom methods: <class glob>#<method glob>[|<as-transaction>].")
            .format(KeySpec.Format.CUSTOM_METHOD)
            .items(256, 800)
            .remote(KeySpec.Remote.N));
    add(
        integer(
                INSTRUMENTATION_CUSTOM_METHODS_MAX_CLASSES,
                "500",
                1,
                10_000,
                "Classes one custom pattern may match before it is disabled.")
            .remote(KeySpec.Remote.N));
    add(
        list(
                INSTRUMENTATION_APP_PACKAGES,
                null,
                "Application packages for the top frame; automatic (main class package) if unset.")
            .format(KeySpec.Format.PACKAGE)
            .items(32, 256)
            .remote(KeySpec.Remote.Y));
    add(
        integer(
                INSTRUMENTATION_SERVICE_DICTIONARY_MAX,
                "10000",
                1,
                100_000,
                "Service names per application; further names become _other_.")
            .remote(KeySpec.Remote.N));
    add(
        integer(
                INSTRUMENTATION_ERROR_THRESHOLD,
                "100",
                1,
                1_000_000,
                "Advice errors that disable a module.")
            .remote(KeySpec.Remote.N));
    add(
        integer(
                INSTRUMENTATION_TRANSFORM_ERROR_THRESHOLD,
                "10",
                1,
                1_000_000,
                "Transform errors that disable a module.")
            .remote(KeySpec.Remote.N));

    // transaction.*
    add(
        integer(
                TRANSACTION_MAX_DURATION_MS,
                "3600000",
                1000,
                86_400_000,
                "Transactions running longer are ended by the agent (leaked).")
            .remote(KeySpec.Remote.Y));
    add(
        bool(SLOW_STACK_ENABLED, "true", "Stack captures of slow running transactions.")
            .remote(KeySpec.Remote.Y));
    add(
        integer(SLOW_STACK_INTERVAL_MS, "3000", 100, 3_600_000, "Milliseconds between captures.")
            .remote(KeySpec.Remote.Y));
    add(
        integer(SLOW_STACK_MAX_CAPTURES, "5", 0, 100, "Stack captures per transaction.")
            .remote(KeySpec.Remote.Y));
    add(
        integer(SLOW_STACK_MAX_THREADS_PER_CYCLE, "10", 1, 100, "Threads captured per 1 s cycle.")
            .remote(KeySpec.Remote.Y));
    add(
        integer(SLOW_STACK_DEPTH, "64", 1, 64, "Frames per captured stack.")
            .remote(KeySpec.Remote.Y));

    // propagation.*
    add(
        list(PROPAGATION_EXCLUDE_HOSTS, "", "Host globs that get no traceparent/tracestate.")
            .format(KeySpec.Format.GLOB)
            .items(256, 256)
            .remote(KeySpec.Remote.N));
    add(
        list(PROPAGATION_ARGOS_STATE_HOSTS, "", "Host globs that get the argos= tracestate entry.")
            .format(KeySpec.Format.GLOB)
            .items(256, 256)
            .remote(KeySpec.Remote.N));
    add(
        bool(PROPAGATION_PRESERVE_INCOMING, "true", "Keeps an existing outgoing traceparent.")
            .remote(KeySpec.Remote.N));
    add(
        bool(ASYNC_STEP, "false", "Records an ASYNC step per propagated task.")
            .remote(KeySpec.Remote.Y));

    // buffer.*, sender.*
    add(
        integer(
                TRANSACTION_CAPACITY,
                "20000",
                1,
                1_000_000,
                "Transactions queued for sending; the oldest is dropped when full.")
            .remote(KeySpec.Remote.N));
    add(
        integer(
                BUFFER_MAX_AGE_SECONDS,
                "120",
                1,
                3600,
                "Seconds a queued item may wait before it is dropped.")
            .remote(KeySpec.Remote.N));
    add(
        KeySpec.builder(BUFFER_MAX_BYTES, KeySpec.Type.BYTES)
            .defaultValue("32MB")
            .range(1024L, 1L << 30)
            .remote(KeySpec.Remote.N)
            .description("Estimated bytes of the transaction and profile queues together."));
    add(
        integer(SENDER_FLUSH_INTERVAL_MS, "100", 1, 60_000, "Milliseconds between batches.")
            .remote(KeySpec.Remote.N));
    add(integer(SENDER_BATCH_SIZE, "500", 1, 5000, "Items per batch.").remote(KeySpec.Remote.N));
    add(
        integer(SENDER_MAX_IN_FLIGHT, "4", 1, 4, "Batches in flight over all kinds.")
            .remote(KeySpec.Remote.N));
    add(
        enumeration(SENDER_COMPRESSION, "gzip", "Batch compression: gzip or none.", "gzip", "none")
            .remote(KeySpec.Remote.N));

    // metrics.*, stats.*, active.*
    add(
        integer(METRICS_INTERVAL_SECONDS, "5", 1, 3600, "Seconds between metric samples.")
            .remote(KeySpec.Remote.Y));
    add(
        integer(METRICS_MAX_SERIES, "500", 1, 500, "Metric series per instance.")
            .remote(KeySpec.Remote.N));
    add(
        integer(STATS_WINDOW_SECONDS, "10", 1, 3600, "Seconds of one statistics window.")
            .remote(KeySpec.Remote.N));
    add(
        integer(STATS_MAX_KEYS, "5000", 1, 5000, "SQL keys per statistics window.")
            .remote(KeySpec.Remote.N));
    add(
        integer(
                ACTIVE_SNAPSHOT_INTERVAL_SECONDS,
                "5",
                1,
                3600,
                "Seconds between active transaction snapshots.")
            .remote(KeySpec.Remote.Y));

    // sql.*
    add(
        integer(SQL_MAX_LENGTH, "65536", 64, 65_536, "Characters of SQL text normalized.")
            .remote(KeySpec.Remote.N));
    add(
        bool(SQL_BACKSLASH_ESCAPE, "true", "Backslash escapes quotes in SQL strings (MySQL).")
            .remote(KeySpec.Remote.N));
    add(
        bool(SQL_KEEP_HINTS, "false", "Keeps optimizer hint comments in SQL.")
            .remote(KeySpec.Remote.N));

    // commands.*
    add(
        list(
                ENV_DUMP_MASK_PATTERNS,
                "",
                "Extra masking regular expressions of the environment dump (cannot remove any).")
            .format(KeySpec.Format.REGEX)
            .items(64, 1024)
            .remote(KeySpec.Remote.N));
    add(
        integer(THREAD_DUMP_COOLDOWN_SECONDS, "5", 1, 3600, "Seconds between two thread dumps.")
            .remote(KeySpec.Remote.N));
    add(
        integer(
                THREAD_DUMP_MAX_BYTES,
                "1048576",
                1024,
                1_048_576,
                "Bytes of a thread dump result before it is truncated.")
            .remote(KeySpec.Remote.N));

    // remote-config.*, log.*
    add(
        list(
                REMOTE_CONFIG_ALLOW,
                REMOTE_CONFIG_ALLOW_DEFAULT,
                "Key globs a collector may change (hard cap; local file only).")
            .format(KeySpec.Format.KEY_GLOB)
            .items(128, 256));
    add(
        enumeration(
                LOG_LEVEL,
                "INFO",
                "Agent log level: ERROR, WARN, INFO, DEBUG or TRACE.",
                "ERROR",
                "WARN",
                "INFO",
                "DEBUG",
                "TRACE")
            .remote(KeySpec.Remote.Y));
    add(path(LOG_DIR, "Agent log directory; automatic (../logs next to the jar, else temp)."));
    add(integer(LOG_MAX_FILE_MB, "10", 1, 1024, "Megabytes of one log file."));
    add(integer(LOG_MAX_FILES, "5", 1, 100, "Rolled log files kept."));

    // diagnostics
    add(
        bool(DEBUG_TRANSFORM_TIMING, "false", "Logs start phase and per-class transform timing.")
            .kind(KeySpec.Kind.DIAGNOSTIC));
    add(
        path(DEBUG_TRANSFORM_LOG, "File of transformed classes, transform errors and loaders.")
            .kind(KeySpec.Kind.DIAGNOSTIC));
    add(
        path(DEBUG_TRANSFORM_LIST, "File of transformed class names (baseline diff).")
            .kind(KeySpec.Kind.DIAGNOSTIC));

    // test-only
    add(
        str(
                TEST_EXPORTER,
                null,
                "Test exporter: file:<dir>, noop, drop or grpc (development builds).")
            .maxLength(4096)
            .kind(KeySpec.Kind.TEST));
    add(
        list(TEST_FAULTS, "", "Test fault injection specs <kind>:<module> (development builds).")
            .items(32, 128)
            .kind(KeySpec.Kind.TEST));
    add(
        path(TEST_SELFDIAG_FILE, "Self-diagnostics snapshot file (development builds).")
            .kind(KeySpec.Kind.TEST));
    add(
        enumeration(
                TEST_OVERHEAD_MONITOR,
                "on",
                "CPU guard: off keeps normal-rate from being halved (development builds).",
                "on",
                "off")
            .kind(KeySpec.Kind.TEST));
  }

  private ConfigKeys() {}

  /** Every key in registry order (10 §15 order, then diagnostics and test keys). */
  public static List<KeySpec> all() {
    return Collections.unmodifiableList(new ArrayList<KeySpec>(SPECS.values()));
  }

  /**
   * The spec of {@code key} (canonical name, or a member of a family such as {@code
   * sampling.profile.slow-threshold-ms.services.<service>}), or null when the key is unknown.
   */
  public static KeySpec spec(String key) {
    if (key == null) {
      return null;
    }
    KeySpec spec = SPECS.get(key);
    if (spec != null) {
      return spec;
    }
    for (KeySpec family : FAMILIES) {
      if (family.matches(key)) {
        return family;
      }
    }
    return null;
  }

  /** True when {@code key} is in the registry. */
  public static boolean isKnown(String key) {
    return spec(key) != null;
  }

  /**
   * The environment variable of {@code key}: {@code ARGOS_} + key with {@code .}/{@code -} as
   * {@code _}, upper case (10 §15).
   */
  public static String environmentName(String key) {
    return ENVIRONMENT_PREFIX + key.replace('.', '_').replace('-', '_').toUpperCase(Locale.ROOT);
  }

  /** True for keys of the test-only namespaces ({@code test.*}, {@code testing.*}). */
  public static boolean isTestOnly(String key) {
    return key.startsWith(TEST_PREFIX) || key.startsWith(TESTING_PREFIX);
  }

  /**
   * The canonical name of a key written with the {@code argos.} prefix (SEC-23 {@code
   * argos.enabled}).
   */
  public static String canonical(String key) {
    return key != null && key.startsWith(PROPERTY_PREFIX)
        ? key.substring(PROPERTY_PREFIX.length())
        : key;
  }

  private static void add(KeySpec.Builder builder) {
    KeySpec spec = builder.build();
    if (SPECS.put(spec.name(), spec) != null) {
      throw new IllegalStateException("duplicate configuration key " + spec.name());
    }
    if (spec.isFamily()) {
      FAMILIES.add(spec);
    }
  }

  private static KeySpec.Builder bool(String name, String defaultValue, String description) {
    return KeySpec.builder(name, KeySpec.Type.BOOL)
        .defaultValue(defaultValue)
        .description(description);
  }

  private static KeySpec.Builder integer(
      String name, String defaultValue, long min, long max, String description) {
    return KeySpec.builder(name, KeySpec.Type.INT)
        .defaultValue(defaultValue)
        .range(min, max)
        .description(description);
  }

  private static KeySpec.Builder str(String name, String defaultValue, String description) {
    return KeySpec.builder(name, KeySpec.Type.STRING)
        .defaultValue(defaultValue)
        .description(description);
  }

  private static KeySpec.Builder path(String name, String description) {
    return KeySpec.builder(name, KeySpec.Type.PATH).maxLength(4096).description(description);
  }

  private static KeySpec.Builder list(String name, String defaultValue, String description) {
    return KeySpec.builder(name, KeySpec.Type.LIST)
        .defaultValue(defaultValue)
        .description(description);
  }

  private static KeySpec.Builder enumeration(
      String name, String defaultValue, String description, String... values) {
    return KeySpec.builder(name, KeySpec.Type.ENUM)
        .values(values)
        .defaultValue(defaultValue)
        .maxLength(32)
        .description(description);
  }
}
