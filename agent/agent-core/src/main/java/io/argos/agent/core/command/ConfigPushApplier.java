/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.command;

import io.argos.agent.bootstrap.AgentControl;
import io.argos.agent.core.config.AgentConfig;
import io.argos.agent.core.config.AllowList;
import io.argos.agent.core.config.ConfigKeys;
import io.argos.agent.core.config.EffectiveConfig;
import io.argos.agent.core.config.KeySpec;
import io.argos.agent.core.config.LoadedConfig;
import io.argos.agent.core.config.RemoteConfigGate;
import io.argos.agent.core.config.RuntimeConfig;
import io.argos.agent.core.log.AgentLogger;
import io.argos.proto.v1.ConfigApplyResult;
import io.argos.proto.v1.ConfigPush;
import io.argos.proto.v1.RejectCode;
import io.argos.proto.v1.RejectedKey;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Applies remote configuration (WO-17; 10 §12.2, 16 §5.5, SEC-23, ADR-0010 C-118 (3), C-231 (4)):
 * {@code ConfigPush}, the {@code ServerHello.config} of every established session and the local
 * file of {@code CONFIG_RELOAD}. Called on {@code argos-command} only (one change at a time),
 * except {@link #helloKillSwitch}.
 *
 * <p><b>ConfigPush</b> (16 §5.5 steps 1-6): every key is evaluated by {@link RemoteConfigGate}
 * against the local {@code remote-config.allow} (the hard cap) and the configuration in force; then
 * {@link RuntimeApplier#refusal} rejects keys the running agent cannot apply ({@code
 * restart_required}). The keys that pass join the remote overlay, and one new snapshot is published
 * ({@code local.withValues(overlay)}, partial apply of DSN-11-08); the changed keys take effect
 * ({@link RuntimeApplier#apply}); the new configuration hash goes to the session ({@code
 * InstanceUpdate.config_hash}). The answer is one {@code ConfigApplyResult}: {@code applied_keys},
 * {@code rejected[{key, code, message "<reason>: ..."}]}, {@code effective_config_hash}, {@code
 * duration_ms}. Reason to code: {@code immutable}, {@code not_allowed} and {@code restart_required}
 * are {@code NOT_ALLOWED}, {@code unknown_key} {@code UNKNOWN_KEY}, {@code invalid_value} {@code
 * INVALID_VALUE}, {@code reduce_masking} and {@code forbidden_pattern} {@code FORBIDDEN_PATTERN},
 * {@code retransform_limit} {@code LIMIT_EXCEEDED}. An {@code instrumentation.custom-methods}
 * change goes through the {@link Retransformer} before it is published (SEC-25, 10 §12.2 step 4):
 * an item with a forbidden class prefix rejects the key ({@code forbidden_pattern}), a refused
 * retransformation rejects it ({@code retransform_limit}), and {@code retransformed_classes} tells
 * how many classes were retransformed. A rejected key keeps its value in force (local or an earlier
 * remote value). Rejections give one WARN {@code remote config rejected keys: k(reason), ...} with
 * key names and reasons only, never values.
 *
 * <p><b>ServerHello.config</b> (C-231 (4)): the values the server holds replace the whole remote
 * overlay (a key the server no longer holds falls back to the local value), gated the same way;
 * nothing is reported back (1.x), rejections are a WARN only. {@code enabled=false} turns the kill
 * switch on at once on {@code argos-io} ({@link #helloKillSwitch}), before the full apply.
 *
 * <p><b>CONFIG_RELOAD</b> ({@link #reload}): the local configuration is read again with the same
 * inputs; every changed local key that the running agent can apply takes effect (local keys are not
 * hard-capped), the allow list is refreshed and the remote overlay is gated again against it (a key
 * dropped from the list loses its remote value). Changed keys read only at start keep their running
 * value and are reported {@code restart_required}.
 */
public final class ConfigPushApplier {
  /** Keys handled per push; further keys are ignored (the protocol already bounds a message). */
  static final int MAX_KEYS = 1_000;

  /** Key names shown in one WARN line. */
  static final int MAX_WARN_KEYS = 32;

  /** Where a new configuration hash goes ({@code AgentSession.configHash}). */
  public interface HashSink {
    void configHash(String hash);
  }

  private final RuntimeConfig config;
  private final RuntimeApplier runtime;
  private final HashSink hashes;
  private final LongSupplier nanoClock;
  private final AgentControl control;
  private final Retransformer retransformer;

  /**
   * The applier without a retransformer: an {@code instrumentation.custom-methods} change only
   * installs its {@link CustomMethodPolicy} (unit tests).
   */
  public ConfigPushApplier(
      RuntimeConfig config,
      RuntimeApplier runtime,
      HashSink hashes,
      LongSupplier nanoClock,
      AgentControl control) {
    this(config, runtime, hashes, nanoClock, control, null);
  }

  /**
   * @param config the configuration in force (published here)
   * @param runtime makes changed keys take effect
   * @param hashes where the new configuration hash goes (may be null)
   * @param nanoClock {@code System::nanoTime} ({@code duration_ms})
   * @param control the kill switch of {@link #helloKillSwitch} (may be null)
   * @param retransformer retransforms the classes of an {@code instrumentation.custom-methods}
   *     change (may be null)
   */
  public ConfigPushApplier(
      RuntimeConfig config,
      RuntimeApplier runtime,
      HashSink hashes,
      LongSupplier nanoClock,
      AgentControl control,
      Retransformer retransformer) {
    this.config = Objects.requireNonNull(config, "config");
    this.runtime = Objects.requireNonNull(runtime, "runtime");
    this.hashes = hashes;
    this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
    this.control = control;
    this.retransformer = retransformer;
  }

  /** The configuration in force. */
  public RuntimeConfig config() {
    return config;
  }

  // --- ConfigPush
  // ----------------------------------------------------------------------------------

  /** Applies {@code push} (see the class comment) and returns its {@code ConfigApplyResult}. */
  public synchronized ConfigApplyResult apply(ConfigPush push) {
    long start = nanoClock.getAsLong();
    RuntimeConfig.State current = config.state();
    RemoteConfigGate gate = RemoteConfigGate.of(current.config());
    Map<String, String> overlay = new LinkedHashMap<String, String>(current.overlay());
    List<String> applied = new ArrayList<String>();
    List<RejectedKey> rejected = new ArrayList<RejectedKey>();
    int seen = 0;
    for (Map.Entry<String, String> entry : sorted(push.getValuesMap()).entrySet()) {
      if (seen++ >= MAX_KEYS) {
        break;
      }
      String key = canonical(entry.getKey());
      String value = entry.getValue();
      RemoteConfigGate.Decision decision = gate.evaluate(entry.getKey(), value, current.allow());
      if (!decision.applies()) {
        rejected.add(rejectedKey(key, code(decision), message(decision, key, value)));
        continue;
      }
      String raw = value.trim();
      AgentConfig candidate = current.config().withValues(Collections.singletonMap(key, raw));
      String refusal = runtime.refusal(key, candidate);
      if (refusal != null) {
        rejected.add(rejectedKey(key, RejectCode.NOT_ALLOWED, refusal));
        continue;
      }
      String forbidden = forbiddenCustomMethods(key, candidate);
      if (forbidden != null) {
        rejected.add(rejectedKey(key, RejectCode.FORBIDDEN_PATTERN, forbidden));
        continue;
      }
      overlay.put(key, raw);
      applied.add(key);
    }
    int retransformed = retransformIfChanged(current, overlay, applied, rejected);
    String hash = current.configHash();
    if (!applied.isEmpty()) {
      hash =
          publish(
              current, RuntimeConfig.State.of(current.local(), current.localEffective(), overlay));
    }
    long durationMs = TimeUnit.NANOSECONDS.toMillis(Math.max(0L, nanoClock.getAsLong() - start));
    warnRejected("remote config rejected keys: ", rejected);
    AgentLogger.info(
        "remote configuration change: {} key(s) applied, {} rejected in {} ms",
        applied.size(),
        rejected.size(),
        durationMs);
    return ConfigApplyResult.newBuilder()
        .setChangeId(push.getChangeId())
        .addAllAppliedKeys(applied)
        .addAllRejected(rejected)
        .setEffectiveConfigHash(hash)
        .setRetransformedClasses(retransformed)
        .setDurationMs((int) Math.min(Integer.MAX_VALUE, durationMs))
        .build();
  }

  /**
   * A {@code ConfigApplyResult} that rejects every key of {@code push} with {@code code} and {@code
   * message} and applies nothing (a full queue, no apply path in this agent context).
   */
  public static ConfigApplyResult rejectAll(
      ConfigPush push, RejectCode code, String message, String configHash) {
    ConfigApplyResult.Builder result =
        ConfigApplyResult.newBuilder()
            .setChangeId(push.getChangeId())
            .setEffectiveConfigHash(configHash == null ? "" : configHash);
    for (String key : sorted(push.getValuesMap()).keySet()) {
      if (result.getRejectedCount() >= MAX_KEYS) {
        break;
      }
      result.addRejected(rejectedKey(canonical(key), code, message));
    }
    return result.build();
  }

  // --- ServerHello.config
  // ----------------------------------------------------------------------------

  /**
   * {@code argos-io}, before the full apply of {@link #applyHello}: {@code enabled=false} in the
   * hello config turns the kill switch on at once, when the gate accepts that key. Never blocks.
   */
  public void helloKillSwitch(Map<String, String> values) {
    if (control == null || values == null || values.isEmpty()) {
      return;
    }
    String value = null;
    for (Map.Entry<String, String> entry : values.entrySet()) {
      if (ConfigKeys.ENABLED.equals(canonical(entry.getKey()))) {
        value = entry.getValue();
      }
    }
    if (value == null) {
      return;
    }
    RuntimeConfig.State current = config.state();
    if (RemoteConfigGate.of(current.local())
            .evaluate(ConfigKeys.ENABLED, value, current.allow())
            .applies()
        && "false".equalsIgnoreCase(value.trim())) {
      control.setEnabled(false);
    }
  }

  /**
   * The {@code ServerHello.config} of a new session: the remote overlay becomes exactly the gated
   * {@code values} (see the class comment). Returns the rejected keys (tests; the session gets no
   * report).
   */
  public synchronized List<RejectedKey> applyHello(Map<String, String> values) {
    RuntimeConfig.State current = config.state();
    RemoteConfigGate gate = RemoteConfigGate.of(current.local());
    Map<String, String> overlay = new LinkedHashMap<String, String>();
    List<RejectedKey> rejected = new ArrayList<RejectedKey>();
    int seen = 0;
    for (Map.Entry<String, String> entry : sorted(values).entrySet()) {
      if (seen++ >= MAX_KEYS) {
        break;
      }
      String key = canonical(entry.getKey());
      String value = entry.getValue();
      RemoteConfigGate.Decision decision = gate.evaluate(entry.getKey(), value, current.allow());
      if (!decision.applies()) {
        rejected.add(rejectedKey(key, code(decision), message(decision, key, value)));
        continue;
      }
      String raw = value.trim();
      AgentConfig candidate = current.local().withValues(Collections.singletonMap(key, raw));
      String refusal = runtime.refusal(key, candidate);
      if (refusal != null) {
        rejected.add(rejectedKey(key, RejectCode.NOT_ALLOWED, refusal));
        continue;
      }
      String forbidden = forbiddenCustomMethods(key, candidate);
      if (forbidden != null) {
        rejected.add(rejectedKey(key, RejectCode.FORBIDDEN_PATTERN, forbidden));
        continue;
      }
      overlay.put(key, raw);
    }
    retransformIfChanged(current, overlay, new ArrayList<String>(), rejected);
    if (!overlay.equals(current.overlay())) {
      publish(current, RuntimeConfig.State.of(current.local(), current.localEffective(), overlay));
    }
    warnRejected("remote config rejected keys (ServerHello config): ", rejected);
    AgentLogger.info(
        "ServerHello configuration: {} remote value(s) in force, {} rejected",
        overlay.size(),
        rejected.size());
    return rejected;
  }

  // --- CONFIG_RELOAD
  // ---------------------------------------------------------------------------------

  /** What a {@link #reload} did. */
  public static final class Reloaded {
    private final List<String> appliedKeys;
    private final List<RejectedKey> rejected;
    private final String configHash;
    private final List<String> restartRequired;

    Reloaded(
        List<String> appliedKeys,
        List<RejectedKey> rejected,
        String configHash,
        List<String> restartRequired) {
      this.appliedKeys = Collections.unmodifiableList(appliedKeys);
      this.rejected = Collections.unmodifiableList(rejected);
      this.configHash = configHash;
      this.restartRequired = Collections.unmodifiableList(restartRequired);
    }

    /** Keys whose value in force changed (local keys and remote values gated out). */
    public List<String> appliedKeys() {
      return appliedKeys;
    }

    /** Remote values the refreshed allow list no longer accepts (now back to the local value). */
    public List<RejectedKey> rejected() {
      return rejected;
    }

    public String configHash() {
      return configHash;
    }

    /** Changed local keys that are read only at start: they keep their running value. */
    public List<String> restartRequired() {
      return restartRequired;
    }
  }

  /** {@code CONFIG_RELOAD} with the freshly loaded local configuration {@code fresh}. */
  public synchronized Reloaded reload(LoadedConfig fresh) {
    RuntimeConfig.State current = config.state();
    AgentConfig oldLocal = current.local();
    AgentConfig freshLocal = fresh.config();
    Set<String> restart = new TreeSet<String>();
    for (String key : RuntimeApplier.changedKeys(oldLocal, freshLocal)) {
      if ((!runtime.appliesAtRunTime(key) && !ConfigKeys.REMOTE_CONFIG_ALLOW.equals(key))
          || isCustomMethodsKey(key)) {
        // Local custom methods take effect at the next start: a reload never retransforms.
        restart.add(key);
      }
    }
    if (oldLocal.serverToken().isPresent() != freshLocal.serverToken().isPresent()
        || (freshLocal.serverToken().isPresent()
            && !Objects.equals(
                freshLocal.serverToken().reveal(), oldLocal.serverToken().reveal()))) {
      restart.add(ConfigKeys.SERVER_TOKEN);
    }
    Map<String, String> explicit = new LinkedHashMap<String, String>(freshLocal.explicitValues());
    Map<String, EffectiveConfig.Entry> entries =
        new LinkedHashMap<String, EffectiveConfig.Entry>(
            fresh.effective() == null
                ? Collections.<String, EffectiveConfig.Entry>emptyMap()
                : fresh.effective().entries());
    for (String key : restart) {
      String old = oldLocal.explicitValues().get(key);
      if (old != null) {
        explicit.put(key, old);
      } else {
        explicit.remove(key);
      }
      EffectiveConfig.Entry oldEntry = current.localEffective().entries().get(key);
      if (oldEntry != null) {
        entries.put(key, oldEntry);
      } else {
        entries.remove(key);
      }
    }
    // The token is never in the explicit values; withExplicitValues keeps the running one.
    AgentConfig newLocal = oldLocal.withExplicitValues(explicit);
    EffectiveConfig newLocalEffective = new EffectiveConfig(entries);
    AllowList allow = AllowList.of(newLocal.remoteConfigAllow());
    RemoteConfigGate gate = RemoteConfigGate.of(newLocal);
    Map<String, String> overlay = new LinkedHashMap<String, String>();
    List<RejectedKey> rejected = new ArrayList<RejectedKey>();
    for (Map.Entry<String, String> entry : current.overlay().entrySet()) {
      RemoteConfigGate.Decision decision = gate.evaluate(entry.getKey(), entry.getValue(), allow);
      if (!decision.applies()) {
        rejected.add(
            rejectedKey(
                entry.getKey(),
                code(decision),
                message(decision, entry.getKey(), entry.getValue())));
        continue;
      }
      String refusal =
          runtime.refusal(
              entry.getKey(),
              newLocal.withValues(Collections.singletonMap(entry.getKey(), entry.getValue())));
      if (refusal != null) {
        rejected.add(rejectedKey(entry.getKey(), RejectCode.NOT_ALLOWED, refusal));
        continue;
      }
      overlay.put(entry.getKey(), entry.getValue());
    }
    RuntimeConfig.State next = RuntimeConfig.State.of(newLocal, newLocalEffective, overlay);
    if (!customMethodsOf(current.config()).equals(customMethodsOf(next.config()))) {
      // A remote custom-methods value the refreshed allow list dropped: the hard cap wins, so the
      // policy of the value now in force is installed even when the retransformation is refused.
      Retransformer.Result result = retransform(current.config(), next.config());
      if (result != null && !result.applied()) {
        CustomMethodPolicy.install(CustomMethodPolicy.of(next.config()));
      }
    }
    List<String> applied =
        new ArrayList<String>(RuntimeApplier.changedKeys(current.config(), next.config()));
    String hash = publish(current, next);
    warnRejected("configuration reload dropped remote values: ", rejected);
    AgentLogger.info(
        "configuration reloaded: {} key(s) applied, {} restart required, {} remote value(s)"
            + " dropped",
        applied.size(),
        restart.size(),
        rejected.size());
    return new Reloaded(applied, rejected, hash, new ArrayList<String>(restart));
  }

  // --- shared ------------------------------------------------------------------------------------

  /**
   * SEC-25, 16 §5.6: a remote {@code instrumentation.custom-methods} with an item whose class
   * pattern starts with a forbidden prefix is rejected ({@code forbidden_pattern}) as a whole, and
   * so is one with an item that carries a step type suffix ({@code step_type_suffix}, ADR-0010
   * C-237 (5)), both {@code FORBIDDEN_PATTERN}; the message names the item position and the prefix,
   * never the item text. Null when acceptable.
   */
  static String forbiddenCustomMethods(String key, AgentConfig candidate) {
    if (!ConfigKeys.INSTRUMENTATION_CUSTOM_METHODS.equals(key)) {
      return null;
    }
    List<String> items = candidate.instrumentationCustomMethods();
    for (int i = 0; i < items.size(); i++) {
      String prefix = CustomMethodPolicy.forbiddenPrefixOf(items.get(i));
      if (prefix != null) {
        return CustomMethodPolicy.REASON_FORBIDDEN
            + ": item "
            + (i + 1)
            + " starts with the forbidden prefix "
            + prefix;
      }
      if (CustomMethodPolicy.hasStepTypeSuffix(items.get(i))) {
        return CustomMethodPolicy.REASON_STEP_TYPE_SUFFIX
            + ": item "
            + (i + 1)
            + " has a step type suffix (custom methods always record METHOD steps)";
      }
    }
    return null;
  }

  /**
   * 10 §12.2 step 4: when {@code overlay} changes the {@code instrumentation.custom-methods} in
   * force, retransforms through the {@link Retransformer} and returns the classes retransformed. A
   * refused retransformation ({@code retransform_limit}) puts the remote values in force back into
   * {@code overlay}, moves the keys from {@code applied} to {@code rejected} ({@code
   * LIMIT_EXCEEDED}) and returns 0.
   */
  private int retransformIfChanged(
      RuntimeConfig.State current,
      Map<String, String> overlay,
      List<String> applied,
      List<RejectedKey> rejected) {
    AgentConfig before = current.config();
    AgentConfig after = current.local().withValues(overlay);
    if (customMethodsOf(before).equals(customMethodsOf(after))) {
      return 0;
    }
    Retransformer.Result result = retransform(before, after);
    if (result == null) {
      return 0;
    }
    if (result.applied()) {
      return result.classes();
    }
    for (String key :
        Arrays.asList(
            ConfigKeys.INSTRUMENTATION_CUSTOM_METHODS,
            ConfigKeys.INSTRUMENTATION_CUSTOM_METHODS_MAX_CLASSES)) {
      String old = current.overlay().get(key);
      String now = overlay.get(key);
      if (Objects.equals(old, now)) {
        continue;
      }
      if (old != null) {
        overlay.put(key, old);
      } else {
        overlay.remove(key);
      }
      applied.remove(key);
      rejected.add(rejectedKey(key, RejectCode.LIMIT_EXCEEDED, result.refusal()));
    }
    return 0;
  }

  /**
   * The retransformation from {@code before}'s custom methods to {@code after}'s; null, with the
   * new policy installed, when this applier has no retransformer.
   */
  private Retransformer.Result retransform(AgentConfig before, AgentConfig after) {
    CustomMethodPolicy next = CustomMethodPolicy.of(after);
    if (retransformer == null) {
      CustomMethodPolicy.install(next);
      return null;
    }
    CustomMethodPolicy previous = CustomMethodPolicy.current();
    if (previous.items().isEmpty()) {
      previous = CustomMethodPolicy.of(before);
    }
    return retransformer.apply(previous, next);
  }

  private static boolean isCustomMethodsKey(String key) {
    return ConfigKeys.INSTRUMENTATION_CUSTOM_METHODS.equals(key)
        || ConfigKeys.INSTRUMENTATION_CUSTOM_METHODS_MAX_CLASSES.equals(key);
  }

  /** The custom methods items and their per-item limit, compared to detect a change. */
  private static List<String> customMethodsOf(AgentConfig config) {
    List<String> out = new ArrayList<String>(config.instrumentationCustomMethods());
    out.add(String.valueOf(config.instrumentationCustomMethodsMaxClassesPerPattern()));
    return out;
  }

  /** Makes {@code next} take effect, publishes it and its hash; returns the hash. */
  private String publish(RuntimeConfig.State current, RuntimeConfig.State next) {
    runtime.apply(current.config(), next.config());
    config.publish(next);
    String hash = next.configHash();
    if (hashes != null && !hash.equals(current.configHash())) {
      hashes.configHash(hash);
    }
    return hash;
  }

  /** 16 §5.5 step 6: one WARN with key names and reasons, never values. */
  private static void warnRejected(String prefix, List<RejectedKey> rejected) {
    if (rejected.isEmpty()) {
      return;
    }
    StringBuilder keys = new StringBuilder();
    int shown = 0;
    for (RejectedKey key : rejected) {
      if (shown == MAX_WARN_KEYS) {
        keys.append(", ... (").append(rejected.size() - shown).append(" more)");
        break;
      }
      keys.append(shown == 0 ? "" : ", ")
          .append(safeKey(key.getKey()))
          .append('(')
          .append(reason(key.getMessage()))
          .append(')');
      shown++;
    }
    AgentLogger.warn("{}{}", prefix, keys.toString());
  }

  /** The {@code <reason>} part of a {@code "<reason>: ..."} message. */
  static String reason(String message) {
    int colon = message.indexOf(':');
    return colon < 0 ? message : message.substring(0, colon);
  }

  /**
   * A key name for the log: known keys as they are; any other text is cut to 64 characters with
   * everything but {@code [A-Za-z0-9._*-]} replaced by {@code ?} (a pushed key is server input).
   */
  static String safeKey(String key) {
    if (key == null) {
      return "?";
    }
    if (ConfigKeys.spec(key) != null) {
      return key;
    }
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < key.length() && out.length() < 64; i++) {
      char c = key.charAt(i);
      boolean plain =
          (c >= 'a' && c <= 'z')
              || (c >= 'A' && c <= 'Z')
              || (c >= '0' && c <= '9')
              || c == '.'
              || c == '_'
              || c == '*'
              || c == '-';
      out.append(plain ? c : '?');
    }
    return out.toString();
  }

  /** Reject reason to {@code RejectCode} (16 §5.5, plan lane A). */
  static RejectCode code(RemoteConfigGate.Decision decision) {
    switch (decision) {
      case UNKNOWN_KEY:
        return RejectCode.UNKNOWN_KEY;
      case INVALID_VALUE:
        return RejectCode.INVALID_VALUE;
      case REDUCE_MASKING:
        return RejectCode.FORBIDDEN_PATTERN;
      case IMMUTABLE:
      case NOT_ALLOWED:
      default:
        return RejectCode.NOT_ALLOWED;
    }
  }

  /** {@code RejectedKey.message}: {@code "<reason>: <constant explanation>"}, never the value. */
  static String message(RemoteConfigGate.Decision decision, String key, String value) {
    switch (decision) {
      case IMMUTABLE:
        return "immutable: this key is never changed remotely";
      case NOT_ALLOWED:
        return "not_allowed: the key is not in the agent's local remote-config.allow";
      case UNKNOWN_KEY:
        return "unknown_key: not a configuration key of this agent";
      case REDUCE_MASKING:
        return "reduce_masking: mask patterns can only be added, never removed";
      case INVALID_VALUE:
        return "invalid_value: " + invalidReason(key, value);
      default:
        return decision.reason();
    }
  }

  private static String invalidReason(String key, String value) {
    if (value == null) {
      return "no value";
    }
    if (value.trim().isEmpty()) {
      return "empty value";
    }
    KeySpec spec = ConfigKeys.spec(key);
    String reason = spec == null ? null : spec.check(value);
    // KeySpec reasons never contain the value (KeySpec#parse contract).
    return reason == null ? "rejected by the value check" : cut(reason, 200);
  }

  static RejectedKey rejectedKey(String key, RejectCode code, String message) {
    return RejectedKey.newBuilder()
        .setKey(cut(key == null ? "" : key, 256))
        .setCode(code)
        .setMessage(cut(message, 1024))
        .build();
  }

  static String canonical(String key) {
    String canonical = key == null ? "" : ConfigKeys.canonical(key.trim());
    return canonical == null ? "" : canonical;
  }

  private static Map<String, String> sorted(Map<String, String> values) {
    return values == null
        ? Collections.<String, String>emptyMap()
        : new TreeMap<String, String>(values);
  }

  private static String cut(String value, int maxChars) {
    return value.length() <= maxChars ? value : value.substring(0, maxChars);
  }
}
