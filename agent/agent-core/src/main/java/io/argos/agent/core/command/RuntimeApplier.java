/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.command;

import io.argos.agent.bootstrap.AgentControl;
import io.argos.agent.bootstrap.ModuleHealth;
import io.argos.agent.core.config.AgentConfig;
import io.argos.agent.core.config.AppPackages;
import io.argos.agent.core.config.ConfigKeys;
import io.argos.agent.core.config.HttpSettings;
import io.argos.agent.core.config.KeySpec;
import io.argos.agent.core.log.AgentLogger;
import io.argos.agent.core.masking.Masker;
import io.argos.agent.core.trace.Sampler;
import io.argos.agent.core.trace.TraceRuntime;
import io.argos.agent.core.trace.TracerImpl;
import io.argos.agent.core.watch.MaxDurationEnforcer;
import io.argos.proto.v1.AgentEventType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Makes a new configuration snapshot take effect in the running agent (WO-17, 10 §12.2 step 3):
 * only the keys whose value changed, each on the part that reads it ("volatile 교체"). Runs on {@code
 * argos-command}; never blocks an application thread (every target publishes an immutable object
 * through a volatile field).
 *
 * <ul>
 *   <li>{@code enabled}: kill switch, first stage ({@code AgentControl.setEnabled}) and {@code
 *       AgentEvent KILL_SWITCH_ON/OFF}; metrics and the session keep running (F-A15);
 *   <li>{@code instrumentation.disabled}: an installed module goes to {@code DISABLED_BY_REMOTE}
 *       and back to {@code ENABLED}; a module that was not installed at start cannot be enabled
 *       remotely ({@link #refusal});
 *   <li>{@code instrumentation.ignore-*}: {@code TracerImpl.setIgnoreRules};
 *   <li>{@code instrumentation.custom-methods} and its {@code max-classes-per-pattern}: a remote
 *       change is retransformed by {@code ConfigPushApplier} through the {@link Retransformer}
 *       before it is published (nothing to do here); a local change on {@code CONFIG_RELOAD} is
 *       {@code restart_required};
 *   <li>{@code sampling.profile.*}: a new configured sampler ({@link SamplingControl}, the server's
 *       throttle cap stays), {@code max-steps} for transactions that begin afterwards;
 *   <li>{@code log.level}: the agent logger's level;
 *   <li>{@code capture.*} and {@code async.step}: {@code AgentControl.setCapture} (the advice reads
 *       the capture modes per call); {@code capture.mask-patterns} also installs a new {@link
 *       Masker} (add-only, enforced by the gate);
 *   <li>{@code commands.*}: read by the command handlers and the cooldown at each command ({@code
 *       commands.env-dump.mask-patterns} add-only, enforced by the gate);
 *   <li>{@code metrics.interval-seconds}, {@code active.snapshot-interval-seconds}: read by {@code
 *       argos-metrics} from the configuration in force at each tick;
 *   <li>{@code transaction.max-duration-ms}: the {@code argos-watch} enforcer;
 *   <li>{@code instrumentation.app-packages}: {@code TracerImpl.setAppPackages}, the packages of
 *       {@code error_top_frame_id} for errors that end afterwards (WO-19, C-237 (6));
 *   <li>{@code transaction.slow-stack.*}: read by the {@code argos-watch} slow-stack sampler from
 *       the configuration in force at each 1 s tick ({@code core.watch.SlowStackWatcher}, WO-19); a
 *       remote {@code transaction.slow-stack.enabled=true} also re-enables a sampler that disabled
 *       itself over its cost budget.
 * </ul>
 *
 * Every other key is read once at agent start: {@link #appliesAtRunTime} is false and the apply
 * path rejects it with {@code restart_required} rather than report it applied (plan assumption,
 * docs/agent-config.md).
 */
public final class RuntimeApplier {
  /** {@code RejectedKey.message} of a key that is read only at agent start. */
  public static final String RESTART_REQUIRED = "restart_required: read only at agent start";

  /** Where {@code AgentEvent}s go ({@code AgentSession.event}; nothing when no session). */
  public interface Events {
    void event(AgentEventType type, String module, String detail);
  }

  private final AgentControl control;
  private final String[] moduleNames;
  private final Set<String> disabledAtStart;
  private final TraceRuntime runtime;
  private final SamplingControl sampling;
  private final MaxDurationEnforcer enforcer;
  private final Events events;

  /**
   * @param control kill switch, module states, capture modes (null: those keys only change the
   *     snapshot)
   * @param moduleNames module id to module name of the installed modules (null entries for ids
   *     without an installed module)
   * @param startConfig the configuration at agent start ({@code instrumentation.disabled} decides
   *     which modules were not installed)
   * @param runtime the recording runtime (ignore rules, max steps), may be null
   * @param sampling the profile sampler control, may be null
   * @param enforcer the {@code transaction.max-duration-ms} task, may be null
   * @param events where {@code AgentEvent}s go, may be null
   */
  public RuntimeApplier(
      AgentControl control,
      String[] moduleNames,
      AgentConfig startConfig,
      TraceRuntime runtime,
      SamplingControl sampling,
      MaxDurationEnforcer enforcer,
      Events events) {
    this.control = control;
    this.moduleNames = moduleNames == null ? new String[0] : moduleNames.clone();
    this.disabledAtStart =
        Collections.unmodifiableSet(
            new HashSet<String>(
                Objects.requireNonNull(startConfig, "startConfig").instrumentationDisabled()));
    this.runtime = runtime;
    this.sampling = sampling;
    this.enforcer = enforcer;
    this.events = events;
  }

  /**
   * True when a change of {@code key} (canonical) takes effect in the running agent (see the class
   * comment); false for keys read only at start.
   */
  public boolean appliesAtRunTime(String key) {
    if (key == null) {
      return false;
    }
    return key.equals(ConfigKeys.ENABLED)
        || key.equals(ConfigKeys.LOG_LEVEL)
        || key.startsWith("sampling.profile.")
        || key.startsWith("capture.")
        || key.equals(ConfigKeys.ASYNC_STEP)
        || key.startsWith("commands.")
        || key.equals(ConfigKeys.INSTRUMENTATION_DISABLED)
        || key.equals(ConfigKeys.INSTRUMENTATION_IGNORE_PATHS)
        || key.equals(ConfigKeys.INSTRUMENTATION_IGNORE_EXTENSIONS)
        || key.equals(ConfigKeys.INSTRUMENTATION_IGNORE_USER_AGENTS)
        || key.equals(ConfigKeys.INSTRUMENTATION_IGNORE_METHODS)
        || key.equals(ConfigKeys.INSTRUMENTATION_CUSTOM_METHODS)
        || key.equals(ConfigKeys.INSTRUMENTATION_CUSTOM_METHODS_MAX_CLASSES)
        || key.equals(ConfigKeys.METRICS_INTERVAL_SECONDS)
        || key.equals(ConfigKeys.ACTIVE_SNAPSHOT_INTERVAL_SECONDS)
        || key.equals(ConfigKeys.TRANSACTION_MAX_DURATION_MS)
        || key.equals(ConfigKeys.INSTRUMENTATION_APP_PACKAGES)
        || key.startsWith("transaction.slow-stack.");
  }

  /**
   * Why {@code candidate} (the configuration with a new value of {@code key}) cannot take effect,
   * as a {@code RejectedKey.message} ({@code "<reason>: ..."}), or null when it can: a key read
   * only at start ({@link #RESTART_REQUIRED}), or an {@code instrumentation.disabled} that would
   * enable a module that was disabled at start and therefore never installed.
   */
  public String refusal(String key, AgentConfig candidate) {
    if (!appliesAtRunTime(key)) {
      return RESTART_REQUIRED;
    }
    if (ConfigKeys.INSTRUMENTATION_DISABLED.equals(key)) {
      Set<String> off = new HashSet<String>(candidate.instrumentationDisabled());
      Set<String> wouldEnable = new TreeSet<String>();
      for (String module : disabledAtStart) {
        if (!off.contains(module) && !installed(module)) {
          wouldEnable.add(module);
        }
      }
      if (!wouldEnable.isEmpty()) {
        return "restart_required: modules disabled at agent start cannot be enabled remotely: "
            + join(wouldEnable);
      }
    }
    return null;
  }

  /**
   * Applies every key whose value differs between {@code previous} and {@code next} (both snapshots
   * of the running agent) and returns those keys, in registry order.
   */
  public Set<String> apply(AgentConfig previous, AgentConfig next) {
    Set<String> changed = changedKeys(previous, next);
    if (changed.isEmpty()) {
      return changed;
    }
    if (changed.contains(ConfigKeys.ENABLED)) {
      applyEnabled(next.enabled());
    }
    if (changed.contains(ConfigKeys.INSTRUMENTATION_DISABLED)) {
      applyModules(next);
    }
    TracerImpl tracer = runtime == null ? null : runtime.tracerImpl();
    if (tracer != null
        && (changed.contains(ConfigKeys.INSTRUMENTATION_IGNORE_PATHS)
            || changed.contains(ConfigKeys.INSTRUMENTATION_IGNORE_EXTENSIONS)
            || changed.contains(ConfigKeys.INSTRUMENTATION_IGNORE_USER_AGENTS)
            || changed.contains(ConfigKeys.INSTRUMENTATION_IGNORE_METHODS))) {
      tracer.setIgnoreRules(HttpSettings.ignoreRules(next));
    }
    if (changedSampler(changed) && sampling != null) {
      sampling.base(Sampler.of(next));
    }
    if (tracer != null && changed.contains(ConfigKeys.MAX_STEPS)) {
      tracer.setMaxSteps(next.maxSteps());
    }
    if (tracer != null && changed.contains(ConfigKeys.INSTRUMENTATION_APP_PACKAGES)) {
      // An empty value goes back to the packages inferred from the main class.
      tracer.setAppPackages(AppPackages.of(next).packages());
    }
    if (changed.contains(ConfigKeys.LOG_LEVEL)) {
      AgentLogger.setLevel(next.logLevel());
    }
    if (anyStartsWith(changed, "capture.") || changed.contains(ConfigKeys.ASYNC_STEP)) {
      if (changed.contains(ConfigKeys.CAPTURE_MASK_PATTERNS)) {
        installMasker(next);
      }
      if (control != null) {
        control.setCapture(next.capture());
      }
    }
    if (enforcer != null && changed.contains(ConfigKeys.TRANSACTION_MAX_DURATION_MS)) {
      enforcer.maxDurationMs(next.transactionMaxDurationMs());
    }
    return changed;
  }

  private void applyEnabled(boolean enabled) {
    if (control != null) {
      control.setEnabled(enabled);
    }
    if (events != null) {
      events.event(
          enabled ? AgentEventType.KILL_SWITCH_OFF : AgentEventType.KILL_SWITCH_ON,
          "",
          enabled
              ? "instrumentation enabled by remote configuration"
              : "instrumentation disabled by remote configuration (enabled=false)");
    }
    if (enabled) {
      AgentLogger.info("kill switch off: instrumentation enabled by remote configuration");
    } else {
      AgentLogger.warn(
          "kill switch on: instrumentation disabled by remote configuration (enabled=false);"
              + " metrics and the collector session keep running");
    }
  }

  private void applyModules(AgentConfig next) {
    if (control == null) {
      return;
    }
    Set<String> off = new HashSet<String>(next.instrumentationDisabled());
    for (int moduleId = 0; moduleId < moduleNames.length; moduleId++) {
      String name = moduleNames[moduleId];
      if (name == null) {
        continue;
      }
      int state = ModuleHealth.state(moduleId);
      if (off.contains(name) && state == ModuleHealth.ENABLED) {
        control.setModuleState(moduleId, ModuleHealth.DISABLED_BY_REMOTE);
        logModule(name, false);
      } else if (!off.contains(name) && state == ModuleHealth.DISABLED_BY_REMOTE) {
        control.setModuleState(moduleId, ModuleHealth.ENABLED);
        logModule(name, true);
      }
    }
  }

  /** One INFO per module state change; {@code name} is a module id of the agent jar's index. */
  private static void logModule(String name, boolean enabled) {
    AgentLogger.info(
        "instrumentation module {} {} by remote configuration",
        name,
        enabled ? "enabled" : "disabled");
  }

  private static void installMasker(AgentConfig next) {
    List<String> warnings = new ArrayList<String>();
    Masker.install(Masker.of(next.captureMaskPatterns(), warnings));
    if (!warnings.isEmpty()) {
      AgentLogger.warn(
          "remote capture.mask-patterns: {} pattern warning(s); invalid patterns are skipped",
          warnings.size());
    }
  }

  private boolean installed(String module) {
    for (String name : moduleNames) {
      if (module.equals(name)) {
        return true;
      }
    }
    return false;
  }

  private static boolean changedSampler(Set<String> changed) {
    for (String key : changed) {
      if (key.startsWith("sampling.profile.") && !key.equals(ConfigKeys.MAX_STEPS)) {
        return true;
      }
    }
    return false;
  }

  private static boolean anyStartsWith(Set<String> keys, String prefix) {
    for (String key : keys) {
      if (key.startsWith(prefix)) {
        return true;
      }
    }
    return false;
  }

  /**
   * The canonical keys whose typed value differs between {@code a} and {@code b}, in registry
   * order; a per-service slow threshold that differs counts as {@code
   * sampling.profile.slow-threshold-ms.services.<service>}. The token is not compared ({@code
   * server.*} never changes at run time).
   */
  public static Set<String> changedKeys(AgentConfig a, AgentConfig b) {
    Set<String> changed = new LinkedHashSet<String>();
    for (KeySpec spec : ConfigKeys.all()) {
      if (spec.isFamily() || spec.isSecret()) {
        continue;
      }
      if (!Objects.equals(a.value(spec.name()), b.value(spec.name()))) {
        changed.add(spec.name());
      }
    }
    Set<String> services = new TreeSet<String>(a.slowThresholdServices().keySet());
    services.addAll(b.slowThresholdServices().keySet());
    for (String service : services) {
      if (!Objects.equals(
          a.slowThresholdServices().get(service), b.slowThresholdServices().get(service))) {
        changed.add(ConfigKeys.SLOW_THRESHOLD_SERVICES + service);
      }
    }
    return changed;
  }

  static String join(Iterable<String> values) {
    StringBuilder out = new StringBuilder();
    for (String value : values) {
      out.append(out.length() == 0 ? "" : ",").append(value);
    }
    return out.toString();
  }
}
