/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.export;

import io.argos.agent.bootstrap.AgentControl;
import io.argos.agent.core.config.AgentConfig;
import io.argos.agent.core.config.ConfigInputs;
import io.argos.agent.core.config.LoadedConfig;
import io.argos.agent.core.guard.AgentCounters;
import io.argos.agent.core.guard.SelfDiagnosticsCollector;
import io.argos.agent.core.trace.Dictionary;
import io.argos.agent.core.trace.TraceRuntime;
import io.argos.agent.core.watch.MaxDurationEnforcer;
import java.lang.instrument.Instrumentation;
import java.util.Collections;
import java.util.List;

/**
 * What an {@link ExporterFactory} may use: configuration, dictionary and agent identity, and for
 * the {@code grpc} transport (WO-11) the loaded configuration (effective token, configuration
 * hash), the agent counters, the recording runtime (queues, active registry, tracer), the module
 * ids and the self-diagnostics collector that takes the transport's self metrics. The transport
 * parts are null in contexts built with the three-argument constructor (test exporters).
 *
 * <p>The remote control parts of WO-17 ({@link #remoteControl}: instrumentation, bootstrap
 * capability, configuration inputs, installed module names, the max-duration task) let the {@code
 * grpc} slot build the command executor and the remote configuration path on {@code argos-sender};
 * they are plain references of classes the start sequence already loaded, so the premain thread
 * defines no class for them (T-NFR-30a). Null when not given (the transport then rejects remote
 * configuration and commands it has no parts for).
 */
public final class ExporterContext {
  private final AgentConfig config;
  private final Dictionary dictionary;
  private final AgentInfo agent;
  private final LoadedConfig loaded;
  private final AgentCounters counters;
  private final TraceRuntime runtime;
  private final List<String> modules;
  private final SelfDiagnosticsCollector diagnostics;
  private volatile Instrumentation instrumentation;
  private volatile AgentControl control;
  private volatile ConfigInputs configInputs;
  private volatile String[] moduleNames;
  private volatile MaxDurationEnforcer maxDurationEnforcer;

  public ExporterContext(AgentConfig config, Dictionary dictionary, AgentInfo agent) {
    this(config, dictionary, agent, null, null, null, null, null);
  }

  /**
   * The agent's context: {@code runtime} supplies the dictionary; {@code modules} are the ids of
   * the loaded instrumentation modules.
   */
  public ExporterContext(
      AgentConfig config,
      Dictionary dictionary,
      AgentInfo agent,
      LoadedConfig loaded,
      AgentCounters counters,
      TraceRuntime runtime,
      List<String> modules,
      SelfDiagnosticsCollector diagnostics) {
    this.config = config;
    this.dictionary = dictionary;
    this.agent = agent;
    this.loaded = loaded;
    this.counters = counters;
    this.runtime = runtime;
    this.modules =
        modules == null
            ? Collections.<String>emptyList()
            : Collections.unmodifiableList(new java.util.ArrayList<String>(modules));
    this.diagnostics = diagnostics;
  }

  public AgentConfig config() {
    return config;
  }

  /** Resolves name ids of transactions and steps to their texts. */
  public Dictionary dictionary() {
    return dictionary;
  }

  public AgentInfo agent() {
    return agent;
  }

  /** The loaded configuration (effective token, configuration hash), or null. */
  public LoadedConfig loaded() {
    return loaded;
  }

  /** The agent's counters, or null. */
  public AgentCounters counters() {
    return counters;
  }

  /** The recording runtime (queues, active registry, tracer), or null. */
  public TraceRuntime runtime() {
    return runtime;
  }

  /** Ids of the loaded instrumentation modules (empty when unknown). */
  public List<String> modules() {
    return modules;
  }

  /** The self-diagnostics collector, or null. */
  public SelfDiagnosticsCollector diagnostics() {
    return diagnostics;
  }

  /**
   * Sets the remote control parts (WO-17, start sequence P7, before {@code argos-sender} starts):
   * the JVM instrumentation (retransform), the bootstrap capability (kill switch, module states,
   * capture modes), the configuration inputs of the start ({@code CONFIG_RELOAD}), module id to
   * name of the installed modules ({@code instrumentation.disabled}) and the {@code
   * transaction.max-duration-ms} task. Returns this context.
   */
  public ExporterContext remoteControl(
      Instrumentation instrumentation,
      AgentControl control,
      ConfigInputs configInputs,
      String[] moduleNames,
      MaxDurationEnforcer maxDurationEnforcer) {
    this.instrumentation = instrumentation;
    this.control = control;
    this.configInputs = configInputs;
    this.moduleNames = moduleNames == null ? null : moduleNames.clone();
    this.maxDurationEnforcer = maxDurationEnforcer;
    return this;
  }

  /** The JVM instrumentation, or null. */
  public Instrumentation instrumentation() {
    return instrumentation;
  }

  /** The bootstrap capability, or null. */
  public AgentControl control() {
    return control;
  }

  /** The configuration inputs of the agent start, or null. */
  public ConfigInputs configInputs() {
    return configInputs;
  }

  /** Module id to module name of the installed modules (a copy), or null. */
  public String[] moduleNames() {
    String[] names = moduleNames;
    return names == null ? null : names.clone();
  }

  /** The {@code transaction.max-duration-ms} task, or null. */
  public MaxDurationEnforcer maxDurationEnforcer() {
    return maxDurationEnforcer;
  }
}
