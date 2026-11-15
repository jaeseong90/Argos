/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.config;

import java.util.regex.Pattern;

/**
 * The agent token (SEC-06, 16 §2.6) and where it came from. The value leaves this object only
 * through {@link #reveal()}, which the transport calls to build the {@code authorization} metadata
 * (WO-11); everything else ({@link #toString()}, {@link #masked()}, logs, self-diagnostics, {@code
 * --check}, {@code InstanceUpdate} snapshots) shows {@value #MASK} (ADR-0010 C-94). The token never
 * enters {@code ConfigHash} or {@link AgentConfig#explicitValues()}.
 */
public final class TokenHolder {
  /** How the token was configured (16 §2.6 "토큰 3방식"). */
  public enum Source {
    /** No token. */
    NONE,
    /** {@code server.token} written in plain text (yml or {@code -D}); WARN once at start. */
    PLAIN,
    /** {@code server.token-file}, read once at start. */
    FILE,
    /** {@code ARGOS_TOKEN} (or {@code ${ARGOS_TOKEN}} in the yml). */
    ENVIRONMENT
  }

  /** The masked form of every token (C-94, SEC-06). */
  public static final String MASK = "argos_****";

  /** No token configured. */
  public static final TokenHolder NONE = new TokenHolder(null, Source.NONE);

  /** The token format of 16 §2.1 ({@code argos_} + 43 base64url characters). */
  private static final Pattern TOKEN = Pattern.compile("argos_[A-Za-z0-9_-]{43}");

  /** {@code argos_} followed by token characters, of any length (a cut or over-long token too). */
  private static final Pattern TOKEN_RUN = Pattern.compile("argos_[A-Za-z0-9_-]+");

  private final String value;
  private final Source source;

  private TokenHolder(String value, Source source) {
    this.value = value;
    this.source = source;
  }

  /** A token from {@code source}; {@link #NONE} for a null or blank {@code value}. */
  public static TokenHolder of(String value, Source source) {
    if (value == null || value.trim().isEmpty() || source == Source.NONE) {
      return NONE;
    }
    return new TokenHolder(value.trim(), source);
  }

  public boolean isPresent() {
    return value != null;
  }

  public Source source() {
    return source;
  }

  /** True when the token has the 16 §2.1 format ({@code ^argos_[A-Za-z0-9_-]{43}$}). */
  public boolean hasValidFormat() {
    return value != null && TOKEN.matcher(value).matches();
  }

  /** {@value #MASK} when a token is present, else {@code none}. */
  public String masked() {
    return value == null ? "none" : MASK;
  }

  /**
   * The token itself, for the transport's call credentials only (WO-11). Never log, hash or report
   * the result.
   */
  public String reveal() {
    return value;
  }

  /**
   * {@code text} with every token of the 16 §2.1 format replaced by {@value #MASK} (C-94: the
   * diagnostics bundle pattern {@code argos_[A-Za-z0-9_-]{43}}, rendered as {@value #MASK}).
   */
  public static String maskTokens(String text) {
    if (text == null || text.indexOf("argos_") < 0) {
      return text;
    }
    return TOKEN.matcher(text).replaceAll(MASK);
  }

  /**
   * {@code text} with every run {@code argos_[A-Za-z0-9_-]+} replaced by {@value #MASK}, whatever
   * its length: unlike {@link #maskTokens(String)} it also masks a token cut by a length limit and
   * one followed by more token characters, so no part of the random part is left. A value that only
   * looks like that ({@code /opt/argos_agent}) is masked as well; this is meant for configuration
   * values quoted in messages, not for whole log lines.
   */
  public static String maskTokenRuns(String text) {
    if (text == null || text.indexOf("argos_") < 0) {
      return text;
    }
    return TOKEN_RUN.matcher(text).replaceAll(MASK);
  }

  /**
   * A configuration value or name for a message (SEC-06, 16 §2.6, C-94): masking first, cutting
   * last. In order: every non-null {@code secrets} entry of at least 4 characters (the configured
   * token, whatever its format) and then every {@code argos_} run ({@link #maskTokenRuns(String)},
   * which covers the 16 §2.1 format) become {@value #MASK} in the whole value; then the value is
   * cut to {@code maxChars} characters plus {@code ...}; then the {@code argos_} runs of the cut
   * text are masked again (defence in depth: a cut can only shorten a run, never create one, but
   * the step keeps the rule "no {@code argos_} run in a quoted value" independent of the steps
   * before it); control characters become {@code ?}. A token therefore never shows in part, not
   * even when the value is longer than the cut (config-security-qa-1).
   */
  static String quoteValue(String value, int maxChars, String... secrets) {
    String text = value == null ? "" : value;
    for (String secret : secrets) {
      if (secret != null && secret.length() >= 4 && text.contains(secret)) {
        text = text.replace(secret, MASK);
      }
    }
    text = maskTokenRuns(text);
    if (text.length() > maxChars) {
      text = maskTokenRuns(text.substring(0, maxChars)) + "...";
    }
    StringBuilder out = new StringBuilder(text.length());
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      out.append(Character.isISOControl(c) ? '?' : c);
    }
    return out.toString();
  }

  @Override
  public String toString() {
    return masked() + " (" + source + ")";
  }
}
