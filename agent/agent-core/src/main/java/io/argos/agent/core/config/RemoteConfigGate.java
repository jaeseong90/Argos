/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.config;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * The agent-side hard cap of remote configuration (SEC-23, DSN-10-16, 16 §5.5, 10 §12.2 steps 1-4):
 * decides for one pushed {@code key = value} whether it may be applied. Applying, {@code
 * ConfigApplyResult} and retransform limits are WO-17; WO-10 provides the evaluation.
 *
 * <p>{@link #evaluate(String, String, AllowList)} checks in this order, the first failing one
 * decides (the key may carry the {@code argos.} prefix, {@code argos.enabled} = {@code enabled}):
 *
 * <ol>
 *   <li>{@link Decision#IMMUTABLE}: {@code server.*}, {@code remote-config.*}, {@code log.dir},
 *       {@code app.*} and {@code agent.disabled} whatever the allow list says and whether the key
 *       exists or not (16 §5.5 step 1, 10 §12.2 step 2).
 *   <li>{@link Decision#UNKNOWN_KEY}: not in {@link ConfigKeys} (a blank or null key included).
 *   <li>{@link Decision#IMMUTABLE}: a known key whose "원격" column is "-" ({@link
 *       KeySpec.Remote#NONE}: {@code log.*} except {@code log.level}, {@code debug.*}, {@code
 *       test.*}).
 *   <li>{@link Decision#NOT_ALLOWED}: no pattern of the local {@code remote-config.allow} matches
 *       ({@link AllowList#matches(String)}); the default list is {@link AllowList#DEFAULT}. The
 *       "원격" column Y/N does not widen the list: only the local allow list does (ADR-0010 C-92).
 *   <li>{@link Decision#REDUCE_MASKING}: {@code capture.mask-patterns} (and {@code
 *       commands.env-dump.mask-patterns}) whose pushed items do not keep every current pattern (16
 *       §5.5 step 3: add only), judged on the raw items before the value check, so a push that
 *       drops a pattern and also carries an invalid one is {@code reduce_masking}.
 *   <li>{@link Decision#INVALID_VALUE}: no value, an empty value for a key that is not a list, or a
 *       value {@link KeySpec#parse(String)} rejects (type, range, format, item caps; 16 §5.5 step
 *       4).
 *   <li>{@link Decision#APPLY} otherwise.
 * </ol>
 *
 * <p>Pure and side-effect free; logs nothing (the caller writes the WARN of 16 §5.5 step 6).
 */
public final class RemoteConfigGate {
  /** Outcome of one key; every value but {@link #APPLY} is a reject reason of 16 §5.5. */
  public enum Decision {
    APPLY,
    IMMUTABLE,
    NOT_ALLOWED,
    UNKNOWN_KEY,
    INVALID_VALUE,
    REDUCE_MASKING;

    /** True for {@link #APPLY}. */
    public boolean applies() {
      return this == APPLY;
    }

    /**
     * The reject reason as sent in {@code ConfigApplyResult.rejected[].reason} and written to the
     * WARN line ({@code not_allowed}, {@code immutable}, ...); {@code applied} for {@link #APPLY}.
     */
    public String reason() {
      return this == APPLY ? "applied" : name().toLowerCase(Locale.ROOT);
    }
  }

  private final AgentConfig current;

  private RemoteConfigGate(AgentConfig current) {
    this.current = current;
  }

  /**
   * A gate that compares pushed values with {@code current} (for {@link Decision#REDUCE_MASKING}).
   */
  public static RemoteConfigGate of(AgentConfig current) {
    return new RemoteConfigGate(Objects.requireNonNull(current, "current"));
  }

  /** The configuration this gate compares with. */
  public AgentConfig current() {
    return current;
  }

  /**
   * The decision for the pushed {@code key = value} under the local allow list {@code local} (see
   * the class comment). Never throws for any input.
   */
  public Decision evaluate(String key, String value, AllowList local) {
    Objects.requireNonNull(local, "local");
    String canonical = key == null ? "" : ConfigKeys.canonical(key);
    if (isImmutable(canonical)) {
      return Decision.IMMUTABLE;
    }
    KeySpec spec = canonical.isEmpty() ? null : ConfigKeys.spec(canonical);
    if (spec == null) {
      return Decision.UNKNOWN_KEY;
    }
    if (spec.remote() == KeySpec.Remote.NONE) {
      return Decision.IMMUTABLE;
    }
    if (!local.matches(canonical)) {
      return Decision.NOT_ALLOWED;
    }
    if (value != null && isMaskPatternKey(canonical) && !keepsEveryPattern(canonical, value)) {
      return Decision.REDUCE_MASKING; // 16 §5.5 step 3 comes before the value check of step 4
    }
    if (value == null
        || (value.trim().isEmpty() && spec.type() != KeySpec.Type.LIST)
        || spec.check(value) != null) {
      return Decision.INVALID_VALUE; // a push sets a value; an empty scalar would mean "unset"
    }
    return Decision.APPLY;
  }

  /**
   * 16 §5.5 step 1 and 10 §12.2 step 2: never changed remotely whatever the allow list says, known
   * key or not ({@code server.*}, {@code remote-config.*}, {@code app.*}, {@code log.dir}, {@code
   * agent.disabled}).
   */
  static boolean isImmutable(String key) {
    return key.startsWith("server.")
        || key.startsWith("remote-config.")
        || key.startsWith("app.")
        || key.equals(ConfigKeys.LOG_DIR)
        || key.equals(ConfigKeys.AGENT_DISABLED);
  }

  private static boolean isMaskPatternKey(String key) {
    return key.equals(ConfigKeys.CAPTURE_MASK_PATTERNS)
        || key.equals(ConfigKeys.ENV_DUMP_MASK_PATTERNS);
  }

  /**
   * 16 §5.5 step 3: the pushed list must keep every current pattern (adding is allowed). Judged on
   * the raw items ({@link KeySpec#splitList(String, int)}, the items {@link KeySpec#parse(String)}
   * would return) before the value is validated, so that a push that drops a pattern is {@code
   * reduce_masking} even when its other items are invalid. A value that is not a list at all (an
   * unclosed quote, or longer than {@link KeySpec#MAX_RAW_LENGTH}) has no items to compare; it is
   * left to step 4 ({@code invalid_value}). Item caps do not apply here: an over-long list that
   * keeps every pattern is {@code invalid_value} in step 4.
   */
  private boolean keepsEveryPattern(String key, String value) {
    List<String> kept = current.getList(key);
    if (kept.isEmpty() || value.length() > KeySpec.MAX_RAW_LENGTH) {
      return true;
    }
    List<String> pushed = KeySpec.splitList(value, KeySpec.MAX_RAW_LENGTH);
    if (pushed == null) {
      return true;
    }
    for (String pattern : kept) {
      if (!pushed.contains(pattern)) {
        return false;
      }
    }
    return true;
  }
}
