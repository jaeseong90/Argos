/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.bootstrap;

/**
 * Immutable snapshot of the data capture modes that helpers read on every request (10 §3, every
 * {@code capture.*} key of 10 §15 and {@code async.step}). The core builds a new object from the
 * configuration and installs it with {@link AgentControl#setCapture} whenever the configuration
 * changes (start, {@code ConfigPush}, reload), so a helper sees one consistent set of modes per
 * call. Arrays returned by the accessors are shared: callers must not modify them.
 *
 * <p>Fields (10 §15 key, default): {@link #queryStringMode()} ({@code capture.query-string}, off),
 * {@link #queryStringWhitelist()} ({@code capture.query-string.whitelist}, empty), {@link
 * #headerWhitelist()} ({@code capture.http-headers}, empty), {@link #remoteIpMode()} ({@code
 * capture.remote-ip}, masked), {@link #trustedProxies()} ({@code capture.trusted-proxies}, empty),
 * {@link #userIdMode()} ({@code capture.user-id.mode}, off), {@link #userIdSource()} ({@code
 * capture.user-id.source}, none), {@link #userIdMaxLength()} (128, SEC-32), {@link #sqlParamMode()}
 * ({@code capture.sql-parameters}, masked), {@link #sqlParamWhitelistColumns()} ({@code
 * capture.sql-parameters.whitelist-columns}, empty), {@link #errorMessageMax()} ({@code
 * capture.error-message-max-length}, 1024), {@link #stackDepth()} ({@code capture.stack-depth}, 30)
 * {@link #asyncStep()} ({@code async.step}, false) and {@link #requestIdHeader()} ({@code
 * capture.request-id-header}, empty: not collected, ADR-0010 C-124). {@code capture.mask-patterns}
 * stays in the core ({@code Masker}): helpers never mask, the core does (16 §6.6).
 *
 * <p>Additions to the field list of 10 §3 (WO-02 report item D-2, ADR-0010 C-93): {@link
 * #asyncStep()} (the executor advice runs inside JDK classes and can read bootstrap state only),
 * {@link #queryStringWhitelist()} and {@link #userIdMaxLength()} (16 §6.1 lists both next to their
 * modes); the user id length is the fixed 128 of SEC-32 because 10 §15 has no key for it. WO-12
 * adds {@link #requestIdHeader()} (ADR-0010 C-124) and the parsed form of {@code
 * capture.user-id.source} ({@link #userIdSourceType()}, {@link #userIdSourceKey()}), so that a
 * servlet helper reads the one header or session attribute without parsing the setting per request.
 */
public final class CaptureConfig {
  /** {@code capture.query-string}: off (default), whitelist, full. */
  public static final int QUERY_STRING_OFF = 0;

  public static final int QUERY_STRING_WHITELIST = 1;
  public static final int QUERY_STRING_FULL = 2;

  /** {@code capture.remote-ip}: masked (default), full, off. */
  public static final int REMOTE_IP_MASKED = 0;

  public static final int REMOTE_IP_FULL = 1;
  public static final int REMOTE_IP_OFF = 2;

  /** {@code capture.user-id.mode}: off (default), plain, hashed. */
  public static final int USER_ID_OFF = 0;

  public static final int USER_ID_PLAIN = 1;
  public static final int USER_ID_HASHED = 2;

  /** {@code capture.sql-parameters}: masked (default), partial, full. */
  public static final int SQL_PARAMS_MASKED = 0;

  public static final int SQL_PARAMS_PARTIAL = 1;
  public static final int SQL_PARAMS_FULL = 2;

  /** Maximum length of a captured user id (SEC-32, 16 §6.1). */
  public static final int USER_ID_MAX_LENGTH = 128;

  /** Default of {@code capture.error-message-max-length} (10 §15). */
  public static final int DEFAULT_ERROR_MESSAGE_MAX = 1024;

  /** Default of {@code capture.stack-depth} (10 §15). */
  public static final int DEFAULT_STACK_DEPTH = 30;

  /** {@link #userIdSourceType()} of {@code header:<name>}: a request header. */
  public static final String USER_ID_SOURCE_HEADER = "header";

  /**
   * {@link #userIdSourceType()} of {@code session:<attribute>}: an existing session's attribute.
   */
  public static final String USER_ID_SOURCE_SESSION = "session";

  /**
   * {@link #userIdSourceType()} of {@code mdc:<key>}: read by the logging module from the MDC of
   * the transaction's log events (ADR-0010 C-125 (3)).
   */
  public static final String USER_ID_SOURCE_MDC = "mdc";

  private static final String[] NONE = new String[0];

  /** The documented defaults (10 §15). */
  public static final CaptureConfig DEFAULT = new Builder().build();

  /** The snapshot installed by the core ({@link AgentControl#setCapture}); never null. */
  private static volatile CaptureConfig current = DEFAULT;

  private final int queryStringMode;
  private final String[] queryStringWhitelist;
  private final String[] headerWhitelist;
  private final int remoteIpMode;
  private final String[] trustedProxies;
  private final int userIdMode;
  private final String userIdSource;
  private final String userIdSourceType;
  private final String userIdSourceKey;
  private final int userIdMaxLength;
  private final int sqlParamMode;
  private final String[] sqlParamWhitelistColumns;
  private final int errorMessageMax;
  private final int stackDepth;
  private final boolean asyncStep;
  private final String requestIdHeader;

  /**
   * The WO-02 constructor (no query-string whitelist, user id length 128, {@code async.step} off);
   * new code uses {@link #builder()}.
   */
  public CaptureConfig(
      int queryStringMode,
      String[] queryStringWhitelist,
      String[] headerWhitelist,
      int remoteIpMode,
      String[] trustedProxies,
      int userIdMode,
      String userIdSource,
      int sqlParamMode,
      String[] sqlParamWhitelistColumns,
      int errorMessageMax,
      int stackDepth) {
    this.queryStringMode = queryStringMode;
    this.queryStringWhitelist = copy(queryStringWhitelist);
    this.headerWhitelist = copy(headerWhitelist);
    this.remoteIpMode = remoteIpMode;
    this.trustedProxies = copy(trustedProxies);
    this.userIdMode = userIdMode;
    this.userIdSource = userIdSource;
    this.userIdSourceType = sourceType(userIdSource);
    this.userIdSourceKey = sourceKey(userIdSource);
    this.userIdMaxLength = USER_ID_MAX_LENGTH;
    this.sqlParamMode = sqlParamMode;
    this.sqlParamWhitelistColumns = copy(sqlParamWhitelistColumns);
    this.errorMessageMax = errorMessageMax;
    this.stackDepth = stackDepth;
    this.asyncStep = false;
    this.requestIdHeader = null;
  }

  private CaptureConfig(Builder b) {
    this.queryStringMode = b.queryStringMode;
    this.queryStringWhitelist = copy(b.queryStringWhitelist);
    this.headerWhitelist = copy(b.headerWhitelist);
    this.remoteIpMode = b.remoteIpMode;
    this.trustedProxies = copy(b.trustedProxies);
    this.userIdMode = b.userIdMode;
    this.userIdSource = b.userIdSource;
    this.userIdSourceType = sourceType(b.userIdSource);
    this.userIdSourceKey = sourceKey(b.userIdSource);
    this.userIdMaxLength = b.userIdMaxLength;
    this.sqlParamMode = b.sqlParamMode;
    this.sqlParamWhitelistColumns = copy(b.sqlParamWhitelistColumns);
    this.errorMessageMax = b.errorMessageMax;
    this.stackDepth = b.stackDepth;
    this.asyncStep = b.asyncStep;
    this.requestIdHeader = blankToNull(b.requestIdHeader);
  }

  /** A builder with the documented defaults. */
  public static Builder builder() {
    return new Builder();
  }

  /** A builder that starts from this snapshot. */
  public Builder toBuilder() {
    Builder b = new Builder();
    b.queryStringMode = queryStringMode;
    b.queryStringWhitelist = queryStringWhitelist;
    b.headerWhitelist = headerWhitelist;
    b.remoteIpMode = remoteIpMode;
    b.trustedProxies = trustedProxies;
    b.userIdMode = userIdMode;
    b.userIdSource = userIdSource;
    b.userIdMaxLength = userIdMaxLength;
    b.sqlParamMode = sqlParamMode;
    b.sqlParamWhitelistColumns = sqlParamWhitelistColumns;
    b.errorMessageMax = errorMessageMax;
    b.stackDepth = stackDepth;
    b.asyncStep = asyncStep;
    b.requestIdHeader = requestIdHeader;
    return b;
  }

  /**
   * The snapshot the core installed ({@link AgentControl#setCapture}), {@link #DEFAULT} before the
   * core started or after a failed start. One volatile read.
   */
  public static CaptureConfig current() {
    return current;
  }

  /** {@link AgentControl#setCapture}: null restores {@link #DEFAULT}. */
  static void install(CaptureConfig value) {
    current = value == null ? DEFAULT : value;
  }

  /**
   * A copy with {@code async.step} set (10 §6.6, §15: the executor module records an ASYNC step per
   * propagated task only when it is on; default off for cost).
   */
  public CaptureConfig withAsyncStep(boolean on) {
    return on == asyncStep ? this : toBuilder().asyncStep(on).build();
  }

  private static String[] copy(String[] values) {
    return values == null || values.length == 0 ? NONE : values.clone();
  }

  private static String blankToNull(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  /** {@code header}, {@code session} or {@code mdc} of {@code <type>:<key>}; null otherwise. */
  private static String sourceType(String source) {
    if (sourceKey(source) == null) {
      return null;
    }
    String type = source.substring(0, source.indexOf(':')).trim();
    if (USER_ID_SOURCE_HEADER.equals(type)) {
      return USER_ID_SOURCE_HEADER;
    }
    if (USER_ID_SOURCE_SESSION.equals(type)) {
      return USER_ID_SOURCE_SESSION;
    }
    return USER_ID_SOURCE_MDC.equals(type) ? USER_ID_SOURCE_MDC : null;
  }

  /** The non-blank key after the first {@code :}; null when there is none. */
  private static String sourceKey(String source) {
    if (source == null) {
      return null;
    }
    int colon = source.indexOf(':');
    if (colon <= 0) {
      return null;
    }
    return blankToNull(source.substring(colon + 1));
  }

  public int queryStringMode() {
    return queryStringMode;
  }

  /**
   * Query parameter names kept in {@code whitelist} mode ({@code capture.query-string.whitelist}).
   */
  public String[] queryStringWhitelist() {
    return queryStringWhitelist;
  }

  /** Request headers to capture (lower case), values up to 1 KB. */
  public String[] headerWhitelist() {
    return headerWhitelist;
  }

  public int remoteIpMode() {
    return remoteIpMode;
  }

  /** CIDRs whose {@code X-Forwarded-For} is trusted; empty means XFF is ignored. */
  public String[] trustedProxies() {
    return trustedProxies;
  }

  public int userIdMode() {
    return userIdMode;
  }

  /** {@code header:<name>}, {@code session:<attr>} or {@code mdc:<key>}; null when unset. */
  public String userIdSource() {
    return userIdSource;
  }

  /**
   * The type of {@link #userIdSource()}: {@link #USER_ID_SOURCE_HEADER}, {@link
   * #USER_ID_SOURCE_SESSION} or {@link #USER_ID_SOURCE_MDC} (compare with {@code equals}); null
   * when the source is unset or not of the form {@code <type>:<key>}.
   */
  public String userIdSourceType() {
    return userIdSourceType;
  }

  /** The header name, session attribute or MDC key of {@link #userIdSource()}, or null. */
  public String userIdSourceKey() {
    return userIdSourceKey;
  }

  /** Characters of a user id kept before hashing or sending (SEC-32: 128). */
  public int userIdMaxLength() {
    return userIdMaxLength;
  }

  public int sqlParamMode() {
    return sqlParamMode;
  }

  public String[] sqlParamWhitelistColumns() {
    return sqlParamWhitelistColumns;
  }

  /** {@code capture.error-message-max-length}. */
  public int errorMessageMax() {
    return errorMessageMax;
  }

  /** {@code capture.stack-depth} of ERROR steps. */
  public int stackDepth() {
    return stackDepth;
  }

  /** {@code async.step}: record an ASYNC step for each task that carries a transaction. */
  public boolean asyncStep() {
    return asyncStep;
  }

  /**
   * {@code capture.request-id-header} (ADR-0010 C-124): the one request header whose value becomes
   * {@code TransactionRecord.request_id} (cut at 128 bytes); null when not collected (the default).
   */
  public String requestIdHeader() {
    return requestIdHeader;
  }

  /** Builder of a {@link CaptureConfig}; starts from the documented defaults. Not thread-safe. */
  public static final class Builder {
    private int queryStringMode = QUERY_STRING_OFF;
    private String[] queryStringWhitelist = NONE;
    private String[] headerWhitelist = NONE;
    private int remoteIpMode = REMOTE_IP_MASKED;
    private String[] trustedProxies = NONE;
    private int userIdMode = USER_ID_OFF;
    private String userIdSource;
    private int userIdMaxLength = USER_ID_MAX_LENGTH;
    private int sqlParamMode = SQL_PARAMS_MASKED;
    private String[] sqlParamWhitelistColumns = NONE;
    private int errorMessageMax = DEFAULT_ERROR_MESSAGE_MAX;
    private int stackDepth = DEFAULT_STACK_DEPTH;
    private boolean asyncStep;
    private String requestIdHeader;

    Builder() {}

    public Builder queryStringMode(int mode) {
      this.queryStringMode = mode;
      return this;
    }

    public Builder queryStringWhitelist(String[] names) {
      this.queryStringWhitelist = names;
      return this;
    }

    public Builder headerWhitelist(String[] names) {
      this.headerWhitelist = names;
      return this;
    }

    public Builder remoteIpMode(int mode) {
      this.remoteIpMode = mode;
      return this;
    }

    public Builder trustedProxies(String[] cidrs) {
      this.trustedProxies = cidrs;
      return this;
    }

    public Builder userIdMode(int mode) {
      this.userIdMode = mode;
      return this;
    }

    public Builder userIdSource(String source) {
      this.userIdSource = source;
      return this;
    }

    public Builder userIdMaxLength(int length) {
      this.userIdMaxLength = length;
      return this;
    }

    public Builder sqlParamMode(int mode) {
      this.sqlParamMode = mode;
      return this;
    }

    public Builder sqlParamWhitelistColumns(String[] columns) {
      this.sqlParamWhitelistColumns = columns;
      return this;
    }

    public Builder errorMessageMax(int length) {
      this.errorMessageMax = length;
      return this;
    }

    public Builder stackDepth(int depth) {
      this.stackDepth = depth;
      return this;
    }

    public Builder asyncStep(boolean on) {
      this.asyncStep = on;
      return this;
    }

    /** {@code capture.request-id-header}; null or blank: not collected. */
    public Builder requestIdHeader(String header) {
      this.requestIdHeader = header;
      return this;
    }

    public CaptureConfig build() {
      return new CaptureConfig(this);
    }
  }
}
