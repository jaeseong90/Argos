/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.export;

import io.argos.agent.core.config.AgentConfig;
import io.argos.agent.core.config.LoadedConfig;
import io.argos.agent.core.guard.AgentCounters;
import io.argos.agent.core.guard.SelfDiagnosticsCollector;
import io.argos.agent.core.trace.Dictionary;
import io.argos.agent.core.trace.TraceRuntime;
import java.util.Collections;
import java.util.List;

/**
 * What an {@link ExporterFactory} may use: configuration, dictionary and agent identity, and for
 * the {@code grpc} transport (WO-11) the loaded configuration (effective token, configuration
 * hash), the agent counters, the recording runtime (queues, active registry, tracer), the module
 * ids and the self-diagnostics collector that takes the transport's self metrics. The transport
 * parts are null in contexts built with the three-argument constructor (test exporters).
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
}
