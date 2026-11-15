/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.config;

import io.argos.agent.bootstrap.CaptureConfig;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Immutable, typed snapshot of the agent configuration (10 §15): one value for every key of {@link
 * ConfigKeys}, the documented default where a key was not set. Built from the resolved raw values
 * ({@link #of(Map)}); where they come from (file, {@code -Dargos.*}, {@code ARGOS_*}, remote) is
 * the job of {@code ConfigLoader} and {@code EffectiveConfig}, so this class has no loading logic.
 *
 * <p>Validation happens once, here: every raw value goes through {@link KeySpec#parse(String)}; an
 * invalid value keeps the default and adds a warning ({@link #warnings()}, at most {@value
 * #MAX_WARNINGS}); an unknown key is ignored with a warning. Warnings never quote a secret value.
 * The token ({@code server.token}) is kept only as a {@link TokenHolder} ({@link #serverToken()}),
 * never in {@link #explicitValues()} or {@link #toString()} (SEC-06).
 *
 * <p>A configuration change (remote push, reload) builds a new snapshot ({@link #withValues(Map)})
 * and swaps it; readers never see a partly applied change.
 */
public final class AgentConfig {
  /** Upper bound of {@link #warnings()}. */
  public static final int MAX_WARNINGS = 100;

  /** Upper bound of per-service slow thresholds ({@link #slowThresholdServices()}). */
  public static final int MAX_SERVICE_THRESHOLDS = 1000;

  /** Upper bound of a service name in a per-service slow threshold key. */
  static final int MAX_SERVICE_NAME_CHARS = 1024;

  private static final int MAX_QUOTED_VALUE_CHARS = 64;

  private static final AgentConfig DEFAULTS = of(Collections.<String, String>emptyMap());

  private final Map<String, Object> values;
  private final Map<String, String> explicit;
  private final Map<String, Integer> serviceThresholds;
  private final TokenHolder token;
  private final List<String> warnings;
  private final List<String> missingRequired;
  private final CaptureConfig capture;

  private AgentConfig(
      Map<String, Object> values,
      Map<String, String> explicit,
      Map<String, Integer> serviceThresholds,
      TokenHolder token,
      List<String> warnings,
      List<String> missingRequired) {
    this.values = values;
    this.explicit = explicit;
    this.serviceThresholds = serviceThresholds;
    this.token = token;
    this.warnings = warnings;
    this.missingRequired = missingRequired;
    this.capture = buildCapture();
  }

  /** Every key at its documented default. */
  public static AgentConfig defaults() {
    return DEFAULTS;
  }

  /**
   * The snapshot of {@code resolved}: canonical key ({@code log.level}, no {@code argos.} prefix)
   * to raw value. Null values and empty scalar values mean "unset"; an empty list is a value (it
   * disables a list rule such as {@code instrumentation.ignore-paths}). Never throws for bad input.
   */
  public static AgentConfig of(Map<String, String> resolved) {
    return of(resolved, null);
  }

  /**
   * {@link #of(Map)}; {@code inheritedToken} is the token of the snapshot a change starts from
   * ({@link #withValues(Map)}), masked in warnings like a token in {@code resolved}.
   */
  private static AgentConfig of(Map<String, String> resolved, String inheritedToken) {
    String[] secrets = {inheritedToken, tokenOf(resolved)};
    Map<String, Object> values = new LinkedHashMap<String, Object>();
    Map<String, String> explicit = new LinkedHashMap<String, String>();
    Map<String, Integer> thresholds = new LinkedHashMap<String, Integer>();
    List<String> warnings = new ArrayList<String>();
    TokenHolder token = TokenHolder.NONE;
    for (KeySpec spec : ConfigKeys.all()) {
      if (!spec.isFamily()) {
        values.put(spec.name(), spec.defaultTyped());
      }
    }
    for (Map.Entry<String, String> entry : resolved.entrySet()) {
      String key = ConfigKeys.canonical(entry.getKey());
      String raw = entry.getValue();
      if (key == null || raw == null) {
        continue;
      }
      KeySpec spec = ConfigKeys.spec(key);
      if (spec == null) {
        warn(warnings, "unknown configuration key '" + quote(key, secrets) + "' ignored");
        continue;
      }
      if (raw.trim().isEmpty() && spec.type() != KeySpec.Type.LIST) {
        continue; // an empty scalar (yml "key:" or -Dargos.key=) means unset
      }
      Object typed;
      try {
        typed = spec.parse(raw);
      } catch (IllegalArgumentException e) {
        warn(warnings, invalid(spec, key, raw, e.getMessage(), secrets));
        continue;
      }
      if (spec.isFamily()) {
        String service = key.substring(ConfigKeys.SLOW_THRESHOLD_SERVICES.length());
        if (service.length() > MAX_SERVICE_NAME_CHARS) {
          warn(warnings, "slow threshold of a service name longer than 1024 characters ignored");
        } else if (thresholds.size() >= MAX_SERVICE_THRESHOLDS
            && !thresholds.containsKey(service)) {
          warn(warnings, "more than " + MAX_SERVICE_THRESHOLDS + " per-service slow thresholds");
        } else {
          thresholds.put(service, (Integer) typed);
          explicit.put(key, raw.trim());
        }
        continue;
      }
      if (spec.isSecret()) {
        token = TokenHolder.of((String) typed, TokenHolder.Source.PLAIN);
        values.put(key, null);
        continue;
      }
      values.put(key, typed);
      explicit.put(key, raw.trim());
    }
    List<String> missing = new ArrayList<String>();
    for (KeySpec spec : ConfigKeys.all()) {
      if (spec.isRequired() && values.get(spec.name()) == null) {
        missing.add(spec.name());
      }
    }
    return new AgentConfig(
        Collections.unmodifiableMap(values),
        Collections.unmodifiableMap(explicit),
        Collections.unmodifiableMap(thresholds),
        token,
        Collections.unmodifiableList(warnings),
        Collections.unmodifiableList(missing));
  }

  /**
   * A new snapshot with {@code overrides} applied over the values set explicitly in this one (a
   * remote push or reload, 10 §12.2 step 3); the token is kept unless {@code overrides} sets {@code
   * server.token}. Validation and warnings as in {@link #of(Map)}; the kept token is masked in the
   * warnings too.
   *
   * <p>Open (WO-17, PM decision pending): {@code overrides} win over every local value here, also
   * over a key the operator set locally. 10 §15 orders the local sources yml &lt; {@code -D} &lt;
   * {@code ARGOS_*}, while 16 §5.5 step 7 says the local yml wins over a pushed value ("file &gt;
   * env &gt; remote"); whether a push may replace a locally set key is decided with the apply path
   * of WO-17, and this method does not change before that.
   */
  public AgentConfig withValues(Map<String, String> overrides) {
    Map<String, String> merged = new LinkedHashMap<String, String>(explicit);
    merged.putAll(overrides);
    AgentConfig next = of(merged, token.reveal());
    if (next.token.isPresent() || !token.isPresent()) {
      return next;
    }
    return new AgentConfig(
        next.values,
        next.explicit,
        next.serviceThresholds,
        token,
        next.warnings,
        next.missingRequired);
  }

  // --- generic access -------------------------------------------------------------------------

  /** The typed value of {@code key} (see {@link KeySpec}), or null when unset without default. */
  public Object value(String key) {
    KeySpec spec = known(key);
    if (spec.isFamily()) {
      return serviceThresholds.get(key.substring(ConfigKeys.SLOW_THRESHOLD_SERVICES.length()));
    }
    return values.get(spec.name());
  }

  /** True when {@code key} was set explicitly (to a valid value). */
  public boolean isSet(String key) {
    return explicit.containsKey(key) || (ConfigKeys.SERVER_TOKEN.equals(key) && token.isPresent());
  }

  public String getString(String key) {
    return (String) typed(key, KeySpec.Type.STRING, KeySpec.Type.PATH, KeySpec.Type.ENUM);
  }

  public boolean getBoolean(String key) {
    return (Boolean) required(key, KeySpec.Type.BOOL);
  }

  public int getInt(String key) {
    return (Integer) required(key, KeySpec.Type.INT);
  }

  /** A LONG, BYTES (bytes) or DURATION (milliseconds) value. */
  public long getLong(String key) {
    return (Long) required(key, KeySpec.Type.LONG, KeySpec.Type.BYTES, KeySpec.Type.DURATION);
  }

  public double getDouble(String key) {
    return (Double) required(key, KeySpec.Type.DOUBLE);
  }

  /** A LIST value; empty when unset without a default. */
  @SuppressWarnings("unchecked") // LIST values are always List<String> (KeySpec.parse)
  public List<String> getList(String key) {
    Object value = typed(key, KeySpec.Type.LIST);
    return value == null ? Collections.<String>emptyList() : (List<String>) value;
  }

  /**
   * The raw values that were set explicitly and accepted, canonical key to trimmed value, without
   * the token. Input of {@code EffectiveConfig} and {@code ConfigHash}.
   */
  public Map<String, String> explicitValues() {
    return explicit;
  }

  /**
   * Problems found while building this snapshot (unknown keys, invalid values), at most {@value
   * #MAX_WARNINGS}; logged as WARN by the caller.
   */
  public List<String> warnings() {
    return warnings;
  }

  /** Required keys without a value ({@code server.address}, {@code app.name}, 10 §4.3 P4). */
  public List<String> missingRequired() {
    return missingRequired;
  }

  // --- server.* -------------------------------------------------------------------------------

  /** {@code server.address}, or null when unset (required, 10 §4.3 P4). */
  public String serverAddress() {
    return getString(ConfigKeys.SERVER_ADDRESS);
  }

  public boolean serverTls() {
    return getBoolean(ConfigKeys.SERVER_TLS);
  }

  /** {@code server.tls.ca-file}, or null. */
  public String serverTlsCaFile() {
    return getString(ConfigKeys.SERVER_TLS_CA_FILE);
  }

  public boolean serverTlsInsecureSkipVerify() {
    return getBoolean(ConfigKeys.SERVER_TLS_INSECURE_SKIP_VERIFY);
  }

  /**
   * {@code server.token} as given in the resolved values ({@link TokenHolder#NONE} when unset). The
   * effective token of the three ways of SEC-06 is {@code LoadedConfig.token()}.
   */
  public TokenHolder serverToken() {
    return token;
  }

  /** {@code server.token-file}, or null. */
  public String serverTokenFile() {
    return getString(ConfigKeys.SERVER_TOKEN_FILE);
  }

  public int serverConnectTimeoutMs() {
    return getInt(ConfigKeys.SERVER_CONNECT_TIMEOUT_MS);
  }

  public int serverDeadlineMs() {
    return getInt(ConfigKeys.SERVER_DEADLINE_MS);
  }

  // --- app.* ----------------------------------------------------------------------------------

  /** {@code app.name}, or null when unset (required). */
  public String appName() {
    return getString(ConfigKeys.APP_NAME);
  }

  public String appEnv() {
    return getString(ConfigKeys.APP_ENV);
  }

  /** {@code app.instance}, or null (computed at start). */
  public String appInstance() {
    return getString(ConfigKeys.APP_INSTANCE);
  }

  /** {@code app.version}, or null (the jar manifest at start). */
  public String appVersion() {
    return getString(ConfigKeys.APP_VERSION);
  }

  /** {@code app.group}, or null. */
  public String appGroup() {
    return getString(ConfigKeys.APP_GROUP);
  }

  // --- kill switch ----------------------------------------------------------------------------

  public boolean enabled() {
    return getBoolean(ConfigKeys.ENABLED);
  }

  public boolean agentDisabled() {
    return getBoolean(ConfigKeys.AGENT_DISABLED);
  }

  // --- sampling.* -----------------------------------------------------------------------------

  /** The default slow threshold in milliseconds ({@code sampling.profile.slow-threshold-ms}). */
  public int slowThresholdMs() {
    return getInt(ConfigKeys.SLOW_THRESHOLD_MS);
  }

  /** The slow threshold of {@code service}: its own value, else {@link #slowThresholdMs()}. */
  public int slowThresholdMs(String service) {
    Integer value = service == null ? null : serviceThresholds.get(service);
    return value == null ? slowThresholdMs() : value;
  }

  /** Per-service slow thresholds (service name to ms), at most {@value #MAX_SERVICE_THRESHOLDS}. */
  public Map<String, Integer> slowThresholdServices() {
    return serviceThresholds;
  }

  public double normalRate() {
    return getDouble(ConfigKeys.NORMAL_RATE);
  }

  public int maxSteps() {
    return getInt(ConfigKeys.MAX_STEPS);
  }

  // --- capture.* ------------------------------------------------------------------------------

  /** {@code capture.query-string}: off, whitelist or full. */
  public String captureQueryString() {
    return getString(ConfigKeys.CAPTURE_QUERY_STRING);
  }

  public List<String> captureQueryStringWhitelist() {
    return getList(ConfigKeys.CAPTURE_QUERY_STRING_WHITELIST);
  }

  public List<String> captureHttpHeaders() {
    return getList(ConfigKeys.CAPTURE_HTTP_HEADERS);
  }

  /** {@code capture.remote-ip}: full, masked or off. */
  public String captureRemoteIp() {
    return getString(ConfigKeys.CAPTURE_REMOTE_IP);
  }

  public List<String> captureTrustedProxies() {
    return getList(ConfigKeys.CAPTURE_TRUSTED_PROXIES);
  }

  /** {@code capture.user-id.mode}: off, plain or hashed. */
  public String captureUserIdMode() {
    return getString(ConfigKeys.CAPTURE_USER_ID_MODE);
  }

  /** {@code capture.user-id.source}, or null. */
  public String captureUserIdSource() {
    return getString(ConfigKeys.CAPTURE_USER_ID_SOURCE);
  }

  /** {@code capture.sql-parameters}: masked, partial or full. */
  public String captureSqlParameters() {
    return getString(ConfigKeys.CAPTURE_SQL_PARAMETERS);
  }

  public List<String> captureSqlParametersWhitelistColumns() {
    return getList(ConfigKeys.CAPTURE_SQL_PARAMETERS_WHITELIST_COLUMNS);
  }

  /** Additional masking patterns; the default set of 16 §6.6 always applies first. */
  public List<String> captureMaskPatterns() {
    return getList(ConfigKeys.CAPTURE_MASK_PATTERNS);
  }

  public int captureErrorMessageMaxLength() {
    return getInt(ConfigKeys.CAPTURE_ERROR_MESSAGE_MAX);
  }

  public int captureStackDepth() {
    return getInt(ConfigKeys.CAPTURE_STACK_DEPTH);
  }

  /**
   * The bootstrap snapshot of every {@code capture.*} key and {@code async.step}, built once per
   * configuration; the core installs it with {@code AgentControl.setCapture}.
   */
  public CaptureConfig capture() {
    return capture;
  }

  // --- instrumentation.* ----------------------------------------------------------------------

  public List<String> instrumentationDisabled() {
    return getList(ConfigKeys.INSTRUMENTATION_DISABLED);
  }

  public List<String> instrumentationExcludeClasses() {
    return getList(ConfigKeys.INSTRUMENTATION_EXCLUDE_CLASSES);
  }

  public List<String> instrumentationIgnorePaths() {
    return getList(ConfigKeys.INSTRUMENTATION_IGNORE_PATHS);
  }

  public List<String> instrumentationIgnoreExtensions() {
    return getList(ConfigKeys.INSTRUMENTATION_IGNORE_EXTENSIONS);
  }

  public List<String> instrumentationIgnoreUserAgents() {
    return getList(ConfigKeys.INSTRUMENTATION_IGNORE_USER_AGENTS);
  }

  public List<String> instrumentationIgnoreMethods() {
    return getList(ConfigKeys.INSTRUMENTATION_IGNORE_METHODS);
  }

  /** Items {@code <class glob>#<method glob>[|<STEP TYPE>[|<as-transaction>]]} (10 §6.10). */
  public List<String> instrumentationCustomMethods() {
    return getList(ConfigKeys.INSTRUMENTATION_CUSTOM_METHODS);
  }

  public int instrumentationCustomMethodsMaxClassesPerPattern() {
    return getInt(ConfigKeys.INSTRUMENTATION_CUSTOM_METHODS_MAX_CLASSES);
  }

  /** {@code instrumentation.app-packages}; empty when unset (computed from the main class). */
  public List<String> instrumentationAppPackages() {
    return getList(ConfigKeys.INSTRUMENTATION_APP_PACKAGES);
  }

  public int instrumentationServiceDictionaryMax() {
    return getInt(ConfigKeys.INSTRUMENTATION_SERVICE_DICTIONARY_MAX);
  }

  public int instrumentationErrorThreshold() {
    return getInt(ConfigKeys.INSTRUMENTATION_ERROR_THRESHOLD);
  }

  public int instrumentationTransformErrorThreshold() {
    return getInt(ConfigKeys.INSTRUMENTATION_TRANSFORM_ERROR_THRESHOLD);
  }

  // --- transaction.* --------------------------------------------------------------------------

  public int transactionMaxDurationMs() {
    return getInt(ConfigKeys.TRANSACTION_MAX_DURATION_MS);
  }

  public boolean slowStackEnabled() {
    return getBoolean(ConfigKeys.SLOW_STACK_ENABLED);
  }

  public int slowStackIntervalMs() {
    return getInt(ConfigKeys.SLOW_STACK_INTERVAL_MS);
  }

  public int slowStackMaxCaptures() {
    return getInt(ConfigKeys.SLOW_STACK_MAX_CAPTURES);
  }

  public int slowStackMaxThreadsPerCycle() {
    return getInt(ConfigKeys.SLOW_STACK_MAX_THREADS_PER_CYCLE);
  }

  public int slowStackDepth() {
    return getInt(ConfigKeys.SLOW_STACK_DEPTH);
  }

  // --- propagation.*, async.step --------------------------------------------------------------

  public List<String> propagationExcludeHosts() {
    return getList(ConfigKeys.PROPAGATION_EXCLUDE_HOSTS);
  }

  public List<String> propagationArgosStateHosts() {
    return getList(ConfigKeys.PROPAGATION_ARGOS_STATE_HOSTS);
  }

  public boolean propagationPreserveIncoming() {
    return getBoolean(ConfigKeys.PROPAGATION_PRESERVE_INCOMING);
  }

  public boolean asyncStep() {
    return getBoolean(ConfigKeys.ASYNC_STEP);
  }

  // --- buffer.*, sender.* ---------------------------------------------------------------------

  public int bufferTransactionCapacity() {
    return getInt(ConfigKeys.TRANSACTION_CAPACITY);
  }

  public int bufferMaxAgeSeconds() {
    return getInt(ConfigKeys.BUFFER_MAX_AGE_SECONDS);
  }

  /** {@code buffer.max-bytes} in bytes. */
  public long bufferMaxBytes() {
    return getLong(ConfigKeys.BUFFER_MAX_BYTES);
  }

  public int senderFlushIntervalMs() {
    return getInt(ConfigKeys.SENDER_FLUSH_INTERVAL_MS);
  }

  public int senderBatchSize() {
    return getInt(ConfigKeys.SENDER_BATCH_SIZE);
  }

  public int senderMaxInFlight() {
    return getInt(ConfigKeys.SENDER_MAX_IN_FLIGHT);
  }

  /** {@code sender.compression}: gzip or none. */
  public String senderCompression() {
    return getString(ConfigKeys.SENDER_COMPRESSION);
  }

  // --- metrics.*, stats.*, active.* -----------------------------------------------------------

  public int metricsIntervalSeconds() {
    return getInt(ConfigKeys.METRICS_INTERVAL_SECONDS);
  }

  public int metricsMaxSeries() {
    return getInt(ConfigKeys.METRICS_MAX_SERIES);
  }

  public int statsWindowSeconds() {
    return getInt(ConfigKeys.STATS_WINDOW_SECONDS);
  }

  public int statsMaxKeys() {
    return getInt(ConfigKeys.STATS_MAX_KEYS);
  }

  public int activeSnapshotIntervalSeconds() {
    return getInt(ConfigKeys.ACTIVE_SNAPSHOT_INTERVAL_SECONDS);
  }

  // --- sql.* ----------------------------------------------------------------------------------

  public int sqlMaxLength() {
    return getInt(ConfigKeys.SQL_MAX_LENGTH);
  }

  public boolean sqlBackslashEscape() {
    return getBoolean(ConfigKeys.SQL_BACKSLASH_ESCAPE);
  }

  public boolean sqlKeepHints() {
    return getBoolean(ConfigKeys.SQL_KEEP_HINTS);
  }

  // --- commands.* -----------------------------------------------------------------------------

  /** Additional environment dump masking patterns; the fixed set always applies (SEC-20). */
  public List<String> envDumpMaskPatterns() {
    return getList(ConfigKeys.ENV_DUMP_MASK_PATTERNS);
  }

  public int threadDumpCooldownSeconds() {
    return getInt(ConfigKeys.THREAD_DUMP_COOLDOWN_SECONDS);
  }

  public int threadDumpMaxBytes() {
    return getInt(ConfigKeys.THREAD_DUMP_MAX_BYTES);
  }

  // --- remote-config.*, log.* -----------------------------------------------------------------

  /** {@code remote-config.allow}: the local hard cap of remote changes (default C-92). */
  public List<String> remoteConfigAllow() {
    return getList(ConfigKeys.REMOTE_CONFIG_ALLOW);
  }

  /** {@code log.level}: ERROR, WARN, INFO, DEBUG or TRACE. */
  public String logLevel() {
    return getString(ConfigKeys.LOG_LEVEL);
  }

  /** {@code log.dir}, or null (computed at start, 10 §14). */
  public String logDir() {
    return getString(ConfigKeys.LOG_DIR);
  }

  public int logMaxFileMb() {
    return getInt(ConfigKeys.LOG_MAX_FILE_MB);
  }

  public int logMaxFiles() {
    return getInt(ConfigKeys.LOG_MAX_FILES);
  }

  // --- diagnostics and test-only --------------------------------------------------------------

  public boolean debugTransformTiming() {
    return getBoolean(ConfigKeys.DEBUG_TRANSFORM_TIMING);
  }

  /** {@code debug.transform-log}, or null. */
  public String debugTransformLog() {
    return getString(ConfigKeys.DEBUG_TRANSFORM_LOG);
  }

  /** {@code debug.transform-list}, or null. */
  public String debugTransformList() {
    return getString(ConfigKeys.DEBUG_TRANSFORM_LIST);
  }

  /** {@code test.exporter}, or null (development builds only; the loader drops it in releases). */
  public String testExporter() {
    return getString(ConfigKeys.TEST_EXPORTER);
  }

  /** {@code test.faults} (development builds only). */
  public List<String> testFaults() {
    return getList(ConfigKeys.TEST_FAULTS);
  }

  /** {@code test.selfdiag-file}, or null (development builds only, C-95). */
  public String testSelfdiagFile() {
    return getString(ConfigKeys.TEST_SELFDIAG_FILE);
  }

  @Override
  public String toString() {
    // Keys only: values may be paths of the operator's machine, and the token is never shown.
    return "AgentConfig" + explicit.keySet() + (token.isPresent() ? " token=" + token : "");
  }

  // --- internals ------------------------------------------------------------------------------

  private CaptureConfig buildCapture() {
    return CaptureConfig.builder()
        .queryStringMode(
            mode(captureQueryString(), "whitelist", "full", CaptureConfig.QUERY_STRING_WHITELIST))
        .queryStringWhitelist(array(captureQueryStringWhitelist(), false))
        .headerWhitelist(array(captureHttpHeaders(), true))
        .remoteIpMode(
            mode(
                captureRemoteIp(),
                "full",
                "off",
                CaptureConfig.REMOTE_IP_FULL,
                CaptureConfig.REMOTE_IP_OFF,
                CaptureConfig.REMOTE_IP_MASKED))
        .trustedProxies(array(captureTrustedProxies(), false))
        .userIdMode(mode(captureUserIdMode(), "plain", "hashed", CaptureConfig.USER_ID_PLAIN))
        .userIdSource(captureUserIdSource())
        .sqlParamMode(
            mode(captureSqlParameters(), "partial", "full", CaptureConfig.SQL_PARAMS_PARTIAL))
        .sqlParamWhitelistColumns(array(captureSqlParametersWhitelistColumns(), false))
        .errorMessageMax(captureErrorMessageMaxLength())
        .stackDepth(captureStackDepth())
        .asyncStep(asyncStep())
        .build();
  }

  /**
   * Modes whose constants are 0 (default value), {@code first} and {@code first + 1}: query string
   * (off, whitelist, full), user id (off, plain, hashed), SQL parameters (masked, partial, full).
   */
  private static int mode(String value, String firstName, String secondName, int first) {
    if (firstName.equals(value)) {
      return first;
    }
    return secondName.equals(value) ? first + 1 : 0;
  }

  private static int mode(
      String value, String firstName, String secondName, int first, int second, int otherwise) {
    if (firstName.equals(value)) {
      return first;
    }
    return secondName.equals(value) ? second : otherwise;
  }

  private static String[] array(List<String> items, boolean lowerCase) {
    String[] array = new String[items.size()];
    for (int i = 0; i < array.length; i++) {
      String item = items.get(i);
      array[i] = lowerCase ? item.toLowerCase(Locale.ROOT) : item;
    }
    return array;
  }

  private Object typed(String key, KeySpec.Type... types) {
    KeySpec spec = known(key);
    for (KeySpec.Type type : types) {
      if (spec.type() == type) {
        return value(key);
      }
    }
    throw new IllegalArgumentException(key + " is a " + spec.type() + " key");
  }

  private Object required(String key, KeySpec.Type... types) {
    Object value = typed(key, types);
    if (value == null) {
      throw new IllegalStateException(key + " has no value and no default");
    }
    return value;
  }

  private static KeySpec known(String key) {
    KeySpec spec = ConfigKeys.spec(key);
    if (spec == null) {
      throw new IllegalArgumentException("unknown configuration key " + key);
    }
    return spec;
  }

  private static String invalid(
      KeySpec spec, String key, String raw, String reason, String[] secrets) {
    String fallback = spec.isFamily() || spec.defaultValue() == null ? "unset" : "the default";
    String shown = spec.isSecret() ? "(hidden)" : "'" + quote(raw, secrets) + "'";
    return "invalid value " + shown + " for " + key + " (" + reason + "); using " + fallback;
  }

  /**
   * A value for a warning, masked before it is cut (SEC-06, C-94): {@link
   * TokenHolder#quoteValue(String, int, String...)} with the tokens of this build ({@code
   * secrets}). A remote push and {@link #withValues(Map)} build snapshots without {@code
   * ConfigLoader}, so the masking has to happen here too.
   */
  private static String quote(String value, String[] secrets) {
    return TokenHolder.quoteValue(value, MAX_QUOTED_VALUE_CHARS, secrets);
  }

  /** The raw {@code server.token} of {@code resolved} (any spelling of the key), or null. */
  private static String tokenOf(Map<String, String> resolved) {
    for (Map.Entry<String, String> entry : resolved.entrySet()) {
      String key = entry.getKey() == null ? null : ConfigKeys.canonical(entry.getKey());
      if (ConfigKeys.SERVER_TOKEN.equals(key) && entry.getValue() != null) {
        String value = entry.getValue().trim();
        return value.isEmpty() ? null : value;
      }
    }
    return null;
  }

  private static void warn(List<String> warnings, String warning) {
    if (warnings.size() < MAX_WARNINGS) {
      warnings.add(warning);
    }
  }
}
