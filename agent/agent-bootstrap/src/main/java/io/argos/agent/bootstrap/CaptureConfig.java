/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.bootstrap;

/**
 * Immutable snapshot of the data capture modes that helpers read on every request (10 §3, §15
 * {@code capture.*}). The core replaces the whole object when the configuration changes, so a
 * helper sees one consistent set of modes per call. Arrays returned by the accessors are shared:
 * callers must not modify them.
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

  private static final String[] NONE = new String[0];

  /** The documented defaults (10 §15). */
  public static final CaptureConfig DEFAULT =
      new CaptureConfig(
          QUERY_STRING_OFF,
          NONE,
          NONE,
          REMOTE_IP_MASKED,
          NONE,
          USER_ID_OFF,
          null,
          SQL_PARAMS_MASKED,
          NONE,
          1024,
          30);

  private final int queryStringMode;
  private final String[] queryStringWhitelist;
  private final String[] headerWhitelist;
  private final int remoteIpMode;
  private final String[] trustedProxies;
  private final int userIdMode;
  private final String userIdSource;
  private final int sqlParamMode;
  private final String[] sqlParamWhitelistColumns;
  private final int errorMessageMax;
  private final int stackDepth;
  private final boolean asyncStep;

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
    this.sqlParamMode = sqlParamMode;
    this.sqlParamWhitelistColumns = copy(sqlParamWhitelistColumns);
    this.errorMessageMax = errorMessageMax;
    this.stackDepth = stackDepth;
    this.asyncStep = false;
  }

  private CaptureConfig(CaptureConfig base, boolean asyncStep) {
    this.queryStringMode = base.queryStringMode;
    this.queryStringWhitelist = base.queryStringWhitelist;
    this.headerWhitelist = base.headerWhitelist;
    this.remoteIpMode = base.remoteIpMode;
    this.trustedProxies = base.trustedProxies;
    this.userIdMode = base.userIdMode;
    this.userIdSource = base.userIdSource;
    this.sqlParamMode = base.sqlParamMode;
    this.sqlParamWhitelistColumns = base.sqlParamWhitelistColumns;
    this.errorMessageMax = base.errorMessageMax;
    this.stackDepth = base.stackDepth;
    this.asyncStep = asyncStep;
  }

  /**
   * A copy with {@code async.step} set (10 §6.6, §15: the executor module records an ASYNC step per
   * propagated task only when it is on; default off for cost). Addition to the fields of 10 §3: the
   * executor advice runs in JDK classes and can read bootstrap state only.
   */
  public CaptureConfig withAsyncStep(boolean on) {
    return on == asyncStep ? this : new CaptureConfig(this, on);
  }

  private static String[] copy(String[] values) {
    return values == null || values.length == 0 ? NONE : values.clone();
  }

  public int queryStringMode() {
    return queryStringMode;
  }

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
}
