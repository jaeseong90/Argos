/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.trace;

import io.argos.agent.bootstrap.AgentControl;
import io.argos.agent.bootstrap.ContextStore;
import io.argos.agent.bootstrap.Tracer;
import io.argos.agent.core.config.AgentConfig;
import io.argos.agent.core.config.HttpSettings;
import io.argos.agent.core.guard.AgentCounters;
import io.argos.agent.core.sql.SqlNormalizer;
import io.argos.agent.core.stats.StatsAggregator;
import io.argos.agent.core.transport.AgentQueue;
import io.argos.agent.core.transport.QueueItem;
import io.argos.agent.core.transport.Queues;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Everything the recording side of the agent consists of, built at P5 (10 §4.3) and handed to the
 * other parts: the {@link Tracer} that the start sequence installs ({@code
 * AgentControl.installTracer}), the {@link Queues} that the sender (WO-11, until then the export
 * worker) and the statistics and metric collectors (WO-16) use, the {@link ActiveRegistry} of the
 * watch thread (WO-19), the {@link Dictionary} of the exporters and the self-diagnostics gauges of
 * 18 §19.
 *
 * <p>The agent logger is not a parameter: {@code AgentLogger} is the static facade of the one log
 * pipeline per JVM (10 §14), initialised at P3 before this runtime exists.
 *
 * <p>{@link #create} never throws for a valid configuration, starts no thread (P7 starts agent
 * threads) and allocates only bounded structures (10 §14 "상한"). Work that is too slow for premain
 * (N-30) runs later on an agent thread through {@link #afterPremain()}: the {@code SecureRandom}
 * node id of {@code tx_id} (C-91) and the CPU and allocation meter ({@code ThreadMXBean}).
 */
public final class TraceRuntime {
  /** {@link #selfValues()} key: thread-locals that still hold a context (18 §19). */
  public static final String THREADLOCAL_CONTEXTS_LIVE = "threadlocal_contexts_live";

  /** {@link #selfValues()} key: contexts in the {@link ActiveRegistry}. */
  public static final String ACTIVE_REGISTRY_SIZE = "active_registry_size";

  /**
   * {@link #selfValues()} key: entries of the weak fallback maps ({@code ContextStore}) that hold
   * transaction state (a {@code TraceContext} or {@code Scope} value) after stale keys were
   * expunged: the leak gauge of 18 §5.9 ({@code ContextStore.contextEntries()}).
   */
  public static final String WEAKMAP_SIZE = "weakmap_size";

  /**
   * {@link #selfValues()} key: every live entry of the weak fallback maps, including caches of
   * facts about long-lived objects (a pooled connection's target id), which are not leaks.
   */
  public static final String WEAKMAP_ENTRIES = "weakmap_entries";

  /** {@link #selfValues()} key prefix: items per queue ({@code argos_agent_queue_size{queue}}). */
  public static final String QUEUE_SIZE = "argos_agent_queue_size";

  /** {@link #selfValues()} key prefix: estimated bytes per queue. */
  public static final String QUEUE_BYTES = "argos_agent_queue_bytes_estimated";

  /**
   * {@link #selfValues()} key: estimated heap bytes of queues, dictionary and service names (10
   * §11, §14).
   */
  public static final String HEAP_BYTES_ESTIMATED = "argos_agent_heap_bytes_estimated";

  /** {@link #selfValues()} key: estimated bytes of the dictionary texts. */
  public static final String DICTIONARY_BYTES = "argos_agent_dictionary_bytes_estimated";

  private final Tracer tracer;
  private final Queues queues;
  private final ActiveRegistry activeRegistry;
  private final Dictionary dictionary;
  private final TracerImpl tracerImpl;
  private final AtomicBoolean afterPremainDone = new AtomicBoolean();

  /**
   * {@code ServerHello.active_snapshot_interval_s} of the current session (11 §3), 0 when none was
   * given; {@code argos-metrics} prefers it to {@code active.snapshot-interval-seconds}.
   */
  private volatile int serverActiveSnapshotIntervalSeconds;

  /** The parts given; {@link #create} is the factory of the agent. */
  public TraceRuntime(Tracer tracer, Queues queues, ActiveRegistry activeRegistry) {
    this(tracer, queues, activeRegistry, null);
  }

  private TraceRuntime(
      Tracer tracer, Queues queues, ActiveRegistry activeRegistry, Dictionary dictionary) {
    this.tracer = Objects.requireNonNull(tracer, "tracer");
    this.queues = Objects.requireNonNull(queues, "queues");
    this.activeRegistry = Objects.requireNonNull(activeRegistry, "activeRegistry");
    this.tracerImpl = tracer instanceof TracerImpl ? (TracerImpl) tracer : null;
    if (dictionary != null) {
      this.dictionary = dictionary;
    } else {
      this.dictionary = tracerImpl != null ? tracerImpl.dictionary() : new Dictionary();
    }
  }

  /**
   * Builds the tracer ({@link TracerImpl}: sampling at end, summary mode, repeat folding, Snowflake
   * {@code tx_id}, xxh3 dictionary), the queues ({@link Queues#create}) and the active registry of
   * {@code config}, counting in {@code counters}. Module errors are counted but cannot disable a
   * module (no {@link AgentControl}); the agent uses {@link #create(AgentConfig, AgentCounters,
   * AgentControl)}.
   */
  public static TraceRuntime create(AgentConfig config, AgentCounters counters) {
    return create(config, counters, null);
  }

  /**
   * {@link #create(AgentConfig, AgentCounters)} with the bootstrap capability that lets {@code
   * moduleError} disable a module after {@code instrumentation.error-threshold} errors (10 §5.2).
   */
  public static TraceRuntime create(
      AgentConfig config, AgentCounters counters, AgentControl control) {
    return create(config, counters, control, null);
  }

  /**
   * {@link #create(AgentConfig, AgentCounters, AgentControl)} where {@code moduleErrors} (the
   * agent's {@code SelfProtection.adviceError}) handles every {@code Tracer.moduleError}: counting,
   * the first WARN and disabling the module (10 §5.2, §14). Null keeps the built-in handling.
   */
  public static TraceRuntime create(
      AgentConfig config,
      AgentCounters counters,
      AgentControl control,
      TracerImpl.ModuleErrors moduleErrors) {
    Objects.requireNonNull(config, "config");
    Objects.requireNonNull(counters, "counters");
    Dictionary dictionary = new Dictionary(counters);
    Queues queues = Queues.create(config, counters);
    ActiveRegistry registry = new ActiveRegistry(ActiveRegistry.DEFAULT_CAPACITY);
    TracerImpl tracer =
        TracerImpl.builder()
            .dictionary(dictionary)
            .counters(counters)
            .control(control)
            .queues(queues)
            .activeRegistry(registry)
            .sampler(Sampler.of(config))
            .maxSteps(config.maxSteps())
            .errorThreshold(config.instrumentationErrorThreshold())
            .retainSteps(writesTestFile(config))
            .moduleErrors(moduleErrors)
            .instanceNameFromConnector(!configured(config.appInstance()))
            .sqlNormalizer(
                new SqlNormalizer(
                    config.sqlBackslashEscape(), config.sqlMaxLength(), config.sqlKeepHints()))
            .statsSettings(config.statsWindowSeconds(), config.statsMaxKeys())
            .build();
    tracer.setIgnoreRules(HttpSettings.ignoreRules(config));
    tracer.setServiceNames(HttpSettings.serviceNames(config));
    tracer.setPropagation(HttpSettings.propagation(config));
    return new TraceRuntime(tracer, queues, registry, dictionary);
  }

  /** True for a non-blank configuration value ({@code app.instance}). */
  static boolean configured(String value) {
    return value != null && !value.trim().isEmpty();
  }

  /**
   * True for {@code test.exporter=file[:<dir>]} (development builds only, T-BLD-010): the JSONL
   * test exporter writes the steps of every transaction, so ended transactions keep them whether or
   * not the sampler captured the profile ({@code profile_captured} still tells the decision).
   */
  static boolean writesTestFile(AgentConfig config) {
    String test = config.testExporter();
    if (test == null) {
      return false;
    }
    String name = test.trim().toLowerCase(Locale.ROOT);
    return name.equals("file") || name.startsWith("file:");
  }

  /**
   * The work that premain leaves to an agent thread (N-30, C-91): replaces the provisional {@code
   * tx_id} node with 12 bits of a {@code SecureRandom} (unless the server already assigned one) and
   * installs the CPU and allocation meter ({@link ThreadMeter#jvm()}). Runs once; later calls do
   * nothing. Never throws.
   */
  public void afterPremain() {
    if (tracerImpl == null || !afterPremainDone.compareAndSet(false, true)) {
      return;
    }
    try {
      tracerImpl.txIds().reseedFromSecureRandom();
    } catch (Throwable ignored) {
      // The provisional node id stays (still 12 bits of randomness).
    }
    try {
      tracerImpl.setThreadMeter(ThreadMeter.jvm());
    } catch (Throwable ignored) {
      // No meter: CPU and allocation stay missing (D-23).
    }
  }

  /** The tracer to install (P5). */
  public Tracer tracer() {
    return tracer;
  }

  /** The tracer implementation, or null when this runtime was built around another tracer. */
  public TracerImpl tracerImpl() {
    return tracerImpl;
  }

  public Queues queues() {
    return queues;
  }

  public ActiveRegistry activeRegistry() {
    return activeRegistry;
  }

  /** The dictionary of the tracer (exporters resolve ids with it). */
  public Dictionary dictionary() {
    return dictionary;
  }

  /**
   * The full statistics aggregator of the tracer (10 §9.3), or null when this runtime was built
   * around another tracer.
   */
  public StatsAggregator stats() {
    return tracerImpl == null ? null : tracerImpl.stats();
  }

  /**
   * Keeps {@code ServerHello.active_snapshot_interval_s} of an accepted session (called on {@code
   * argos-io}); 0 or less: the server gave none.
   */
  public void serverActiveSnapshotIntervalSeconds(int seconds) {
    serverActiveSnapshotIntervalSeconds = Math.max(0, seconds);
  }

  /** {@code ServerHello.active_snapshot_interval_s} of the current session, 0 when none. */
  public int serverActiveSnapshotIntervalSeconds() {
    return serverActiveSnapshotIntervalSeconds;
  }

  /**
   * The gauges this runtime measures, by their 18 §19 names ({@link #THREADLOCAL_CONTEXTS_LIVE},
   * {@link #ACTIVE_REGISTRY_SIZE}, {@link #WEAKMAP_SIZE}) and the queue and memory estimates
   * ({@code argos_agent_queue_size{queue}}, {@code argos_agent_queue_bytes_estimated{queue}},
   * {@link #HEAP_BYTES_ESTIMATED}, {@link #DICTIONARY_BYTES}); a gauge it cannot measure is absent,
   * never 0 (D-23). Agent threads only: it visits the thread-slot registry and expunges the weak
   * fallback maps first, so {@code weakmap_size} counts live keys.
   */
  public Map<String, Long> selfValues() {
    Map<String, Long> values = new LinkedHashMap<String, Long>();
    if (tracerImpl != null) {
      values.put(THREADLOCAL_CONTEXTS_LIVE, ThreadSlots.liveContexts());
    }
    values.put(ACTIVE_REGISTRY_SIZE, (long) activeRegistry.size());
    ContextStore.expungeAll();
    values.put(WEAKMAP_SIZE, ContextStore.contextEntries());
    values.put(WEAKMAP_ENTRIES, ContextStore.totalEntries());
    for (AgentQueue<QueueItem> queue : queues.all()) {
      values.put(label(QUEUE_SIZE, queue.name()), (long) queue.size());
      values.put(label(QUEUE_BYTES, queue.name()), queue.estimatedBytes());
    }
    values.put(DICTIONARY_BYTES, dictionary.estimatedBytes());
    long heap = queues.estimatedBytes() + dictionary.estimatedBytes();
    if (tracerImpl != null) {
      heap += tracerImpl.serviceNames().estimatedBytes();
    }
    values.put(HEAP_BYTES_ESTIMATED, heap);
    return Collections.unmodifiableMap(values);
  }

  private static String label(String metric, String queue) {
    return metric + "{queue=\"" + queue + "\"}";
  }
}
