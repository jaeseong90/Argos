/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.metric;

import io.argos.agent.core.log.AgentLogger;
import java.lang.invoke.MethodHandle;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.atomic.LongAdder;

/**
 * The started Tomcat connector endpoints of the application (10 §6.4, F-A10, DSN-10-12): the tomcat
 * module registers each {@code org.apache.tomcat.util.net.AbstractEndpoint} at the exit of its
 * {@code start()} ({@code Tracer.registerConnector}); the metric collector of WO-16 reads {@link
 * #snapshot()} every 5 s for the 10 §11 series {@code tomcat_threads_busy/current/max} and {@code
 * tomcat_connections_current/max} with the label {@code connector}.
 *
 * <p><b>Getters only, never JMX</b>: {@code getName}, {@code getCurrentThreadsBusy}, {@code
 * getCurrentThreadCount}, {@code getMaxThreads}, {@code getConnectionCount}, {@code
 * getMaxConnections}, {@code getLocalPort}/{@code getPort} of the public class {@code
 * AbstractEndpoint} (Tomcat 9, 10.1, 11). Negative values (Tomcat's "no executor", "external
 * executor", "unlimited") are missing (-1, C-4). With a virtual-thread executor ({@code
 * getUseVirtualThreads()}, or an executor class named {@code *VirtualThread*} as Spring Boot sets
 * it) {@code threadsMax} is missing (10 §6.4).
 *
 * <p><b>Instance update</b> (10 §10.4, 11 §4.2): after each change of the connector list, and
 * whenever the transport session is a new one, the registry hands the connector names (registration
 * order) and the port of the first connector with a known port to its {@link Publisher}. The
 * agent's publisher ({@link #TomcatRegistry(boolean)}) sends them as {@code InstanceUpdate} through
 * the WO-11 session API and, unless {@code app.instance} is configured, makes the instance name
 * {@code host:port}. A registration before the transport session exists is published by the next
 * {@link #snapshot()} that finds one (the metric collector calls it every 5 s).
 *
 * <p><b>No class loader pinned</b> (F-A36): an entry holds its endpoint weakly and otherwise only
 * its name, order and port; the adapter of method handles on the endpoint's getters, which refer to
 * the endpoint class, is built once per class and kept on that class itself ({@link ClassValue}:
 * {@link Class} → class value → adapter → {@link Class}), so it is collected with the application's
 * class loader whether or not the entry was dropped yet. The adapter refers neither to the registry
 * nor to the class value. Checked by {@code RegistryClassLoaderLeakTest} (T-AG-062h: an application
 * loader holding its endpoint in a static field is collected with and without a snapshot).
 *
 * <p><b>Bounds and failures</b>: weak references (a collected endpoint disappears from the snapshot
 * at once and its entry is dropped at the next registration or snapshot); at most {@value
 * #MAX_CONNECTORS} endpoints, further ones counted in {@value #OVERFLOW_COUNTER}; a failing getter,
 * or an expected getter the Tomcat version lacks (counted once per registered endpoint), makes the
 * value missing and counts in {@value #GETTER_ERRORS_COUNTER} with one WARN. An endpoint registered
 * under the name of a listed one replaces it (a restarted connector). Lock-free, never throws.
 */
public final class TomcatRegistry {
  /** Upper bound of registered endpoints (also the connector cap of {@code InstanceUpdate}). */
  public static final int MAX_CONNECTORS = 32;

  /** Upper bound of a connector name (a metric label). */
  public static final int MAX_NAME_CHARS = 256;

  /** Endpoints not registered because {@value #MAX_CONNECTORS} were registered. */
  public static final String OVERFLOW_COUNTER =
      "argos_agent_registry_overflow_total{registry=\"tomcat\"}";

  /** Getter calls that failed and expected getters an endpoint class lacks. */
  public static final String GETTER_ERRORS_COUNTER =
      "argos_agent_registry_getter_errors_total{registry=\"tomcat\"}";

  static final String ENDPOINT = "org.apache.tomcat.util.net.AbstractEndpoint";

  /**
   * The adapter of each class an endpoint was registered with, kept on that class (see the class
   * comment: no class loader pinned); {@link Adapter#NONE} for a class that is no endpoint.
   */
  private static final ClassValue<Adapter> ADAPTERS =
      new ClassValue<Adapter>() {
        @Override
        protected Adapter computeValue(Class<?> type) {
          return Adapter.build(type);
        }
      };

  /** Where connector names and the port go (the transport session; tests). */
  public interface Publisher {
    /** The current destination, null while there is none (no session yet). Must not block. */
    Object destination();

    /**
     * Sends the connector names (registration order) and the port of the first connector whose port
     * is known (-1 when none) to {@code destination}. Must not block.
     */
    void publish(Object destination, List<String> connectorNames, int port);
  }

  private final Publisher publisher;
  private final AtomicReferenceArray<Entry> slots = new AtomicReferenceArray<Entry>(MAX_CONNECTORS);
  private final AtomicLong order = new AtomicLong();
  private final AtomicInteger unnamed = new AtomicInteger();
  private final AtomicInteger version = new AtomicInteger();
  private final LongAdder overflow = new LongAdder();
  private final LongAdder getterErrors = new LongAdder();
  private volatile Object publishedTo;
  private volatile int publishedVersion = -1;

  /**
   * The agent's registry: publishes through the transport session ({@code AgentSession}); {@code
   * nameInstance} is true when {@code app.instance} is not configured, so the instance name becomes
   * {@code host:port}.
   */
  public TomcatRegistry(boolean nameInstance) {
    this(new SessionPublisher(nameInstance));
  }

  /** A registry publishing to {@code publisher} (null: nowhere). */
  public TomcatRegistry(Publisher publisher) {
    this.publisher = publisher;
  }

  /**
   * Registers a started endpoint. Returns true when it was added; false for null, an endpoint
   * already registered (its port is refreshed), a class that is not an {@code AbstractEndpoint}
   * (counted) or a full registry (counted). Publishes the connector list. Never throws.
   */
  public boolean register(Object endpoint) {
    if (endpoint == null) {
      return false;
    }
    try {
      expunge();
      Entry known = entryOf(endpoint);
      if (known != null) {
        refreshPort(known, ADAPTERS.get(endpoint.getClass()), endpoint);
        publish();
        return false;
      }
      Adapter adapter = ADAPTERS.get(endpoint.getClass());
      if (adapter.type == null) {
        getterErrors.increment();
        AgentLogger.warnOnce(
            "registry.tomcat.unsupported",
            "{} is not a Tomcat endpoint: it has no connector metrics (counted in {})",
            endpoint.getClass(),
            GETTER_ERRORS_COUNTER);
        return false;
      }
      if (adapter.missingGetters > 0) {
        getterErrors.add(adapter.missingGetters);
        AgentLogger.warnOnce(
            "registry.tomcat.missing-getter",
            "{} lacks {} of the expected connector getters: those values are reported missing"
                + " (counted in {})",
            endpoint.getClass(),
            adapter.missingGetters,
            GETTER_ERRORS_COUNTER);
      }
      Entry entry =
          new Entry(
              endpoint, name(adapter, endpoint), order.incrementAndGet(), port(adapter, endpoint));
      boolean added = insert(entry);
      if (added) {
        version.incrementAndGet();
      }
      publish();
      return added;
    } catch (Throwable t) {
      getterErrors.increment();
      return false;
    }
  }

  /**
   * The values of every registered endpoint in registration order (getters called now, on the
   * calling thread); collected endpoints are dropped first, and a pending connector list is
   * published. Never throws.
   */
  public List<ConnectorSample> snapshot() {
    List<ConnectorSample> samples = new ArrayList<ConnectorSample>();
    try {
      expunge();
      for (Entry entry : live()) {
        Object endpoint = entry.endpoint.get();
        if (endpoint == null) {
          continue;
        }
        Adapter a = ADAPTERS.get(endpoint.getClass());
        refreshPort(entry, a, endpoint);
        samples.add(
            new ConnectorSample(
                entry.name,
                entry.port,
                value(a.busy, endpoint),
                value(a.current, endpoint),
                virtualThreads(a, endpoint) ? Getters.MISSING : value(a.max, endpoint),
                value(a.connections, endpoint),
                value(a.maxConnections, endpoint)));
      }
      publish();
    } catch (Throwable t) {
      getterErrors.increment();
    }
    return Collections.unmodifiableList(samples);
  }

  /** Names of the registered live endpoints in registration order. */
  public List<String> connectorNames() {
    List<String> names = new ArrayList<String>();
    for (Entry entry : live()) {
      if (entry.endpoint.get() != null) {
        names.add(entry.name);
      }
    }
    return Collections.unmodifiableList(names);
  }

  /** Registered endpoints that are still alive. */
  public int size() {
    int size = 0;
    for (int i = 0; i < MAX_CONNECTORS; i++) {
      Entry entry = slots.get(i);
      if (entry != null && entry.endpoint.get() != null) {
        size++;
      }
    }
    return size;
  }

  /** Slots holding an entry, collected or not (tests: no entry outlives its endpoint's expunge). */
  int slotsInUse() {
    int used = 0;
    for (int i = 0; i < MAX_CONNECTORS; i++) {
      used += slots.get(i) == null ? 0 : 1;
    }
    return used;
  }

  /** {@value #OVERFLOW_COUNTER}. */
  public long overflowTotal() {
    return overflow.sum();
  }

  /** {@value #GETTER_ERRORS_COUNTER}. */
  public long getterErrorsTotal() {
    return getterErrors.sum();
  }

  /** The registry's counters by OpenMetrics name (to be registered in 10 §11 by WO-16). */
  public Map<String, Long> counters() {
    Map<String, Long> out = new LinkedHashMap<String, Long>();
    out.put(OVERFLOW_COUNTER, overflow.sum());
    out.put(GETTER_ERRORS_COUNTER, getterErrors.sum());
    return Collections.unmodifiableMap(out);
  }

  // --- internals ------------------------------------------------------------------------------

  /**
   * Hands the connector list to the publisher when the list or the destination changed since the
   * last publication; nothing while there is no destination or no connector.
   */
  private void publish() {
    Publisher target = publisher;
    if (target == null) {
      return;
    }
    try {
      Object destination = target.destination();
      if (destination == null) {
        return;
      }
      int current = version.get();
      if (Getters.same(destination, publishedTo) && current == publishedVersion) {
        return;
      }
      List<String> names = new ArrayList<String>();
      int port = -1;
      for (Entry entry : live()) {
        if (entry.endpoint.get() == null) {
          continue;
        }
        names.add(entry.name);
        if (port <= 0 && entry.port > 0) {
          port = entry.port;
        }
      }
      if (names.isEmpty()) {
        return;
      }
      target.publish(destination, Collections.unmodifiableList(names), port);
      publishedTo = destination;
      publishedVersion = current;
    } catch (Throwable t) {
      AgentLogger.warnOnce(
          "registry.tomcat.publish",
          "the connector list could not be reported to the server ({}); the next snapshot retries",
          t.getClass());
    }
  }

  private String name(Adapter adapter, Object endpoint) {
    Object own = adapter.name == null ? null : read(adapter.name, endpoint);
    String name = own instanceof String ? Getters.cut((String) own, MAX_NAME_CHARS) : null;
    if (name != null) {
      return name;
    }
    String generated = Getters.simpleName(endpoint.getClass()) + "-" + unnamed.incrementAndGet();
    return generated.length() > MAX_NAME_CHARS
        ? generated.substring(generated.length() - MAX_NAME_CHARS)
        : generated;
  }

  /** The bound local port, else the configured port; -1 when neither is known. */
  private int port(Adapter adapter, Object endpoint) {
    long local = adapter.localPort == null ? -1 : number(adapter.localPort, endpoint);
    if (local > 0 && local <= 65535) {
      return (int) local;
    }
    long configured = adapter.port == null ? -1 : number(adapter.port, endpoint);
    return configured > 0 && configured <= 65535 ? (int) configured : -1;
  }

  private void refreshPort(Entry entry, Adapter adapter, Object endpoint) {
    if (entry.port > 0) {
      return;
    }
    int port = port(adapter, endpoint);
    if (port > 0) {
      entry.port = port;
      version.incrementAndGet();
    }
  }

  /** True for a virtual-thread executor (10 §6.4: {@code maxThreads} missing). */
  private boolean virtualThreads(Adapter adapter, Object endpoint) {
    if (adapter.virtualThreads != null
        && Boolean.TRUE.equals(read(adapter.virtualThreads, endpoint))) {
      return true;
    }
    if (adapter.executor != null) {
      Object executor = read(adapter.executor, endpoint);
      return executor != null && executor.getClass().getName().contains("VirtualThread");
    }
    return false;
  }

  private boolean insert(Entry entry) {
    // An endpoint with the name of a listed one replaces it (restarted connector).
    for (int i = 0; i < MAX_CONNECTORS; i++) {
      Entry old = slots.get(i);
      if (old != null && old.name.equals(entry.name) && slots.compareAndSet(i, old, entry)) {
        return true;
      }
    }
    for (int i = 0; i < MAX_CONNECTORS; i++) {
      Entry old = slots.get(i);
      if ((old == null || old.endpoint.get() == null) && slots.compareAndSet(i, old, entry)) {
        return true;
      }
    }
    overflow.increment();
    AgentLogger.warnOnce(
        "registry.tomcat.overflow",
        "more than {} Tomcat connectors: further connectors have no metrics (counted in {})",
        MAX_CONNECTORS,
        OVERFLOW_COUNTER);
    return false;
  }

  private Entry entryOf(Object endpoint) {
    for (int i = 0; i < MAX_CONNECTORS; i++) {
      Entry entry = slots.get(i);
      if (entry != null && Getters.same(entry.endpoint.get(), endpoint)) {
        return entry;
      }
    }
    return null;
  }

  /** Drops entries whose endpoint was collected; a removal changes the published list. */
  private void expunge() {
    for (int i = 0; i < MAX_CONNECTORS; i++) {
      Entry entry = slots.get(i);
      if (entry != null && entry.endpoint.get() == null && slots.compareAndSet(i, entry, null)) {
        version.incrementAndGet();
      }
    }
  }

  private List<Entry> live() {
    List<Entry> entries = new ArrayList<Entry>();
    for (int i = 0; i < MAX_CONNECTORS; i++) {
      Entry entry = slots.get(i);
      if (entry != null) {
        entries.add(entry);
      }
    }
    Collections.sort(entries, ORDER);
    return entries;
  }

  private long value(MethodHandle getter, Object target) {
    return getter == null ? Getters.MISSING : number(getter, target);
  }

  private long number(MethodHandle getter, Object target) {
    try {
      return Getters.number(Getters.get(getter, target));
    } catch (Throwable t) {
      failed(target, t);
      return Getters.MISSING;
    }
  }

  private Object read(MethodHandle getter, Object target) {
    try {
      return Getters.get(getter, target);
    } catch (Throwable t) {
      failed(target, t);
      return null;
    }
  }

  private void failed(Object target, Throwable t) {
    getterErrors.increment();
    AgentLogger.warnOnce(
        "registry.tomcat.getter",
        "a connector getter of {} failed with {}: the value is reported missing (counted in {})",
        target.getClass(),
        t.getClass(),
        GETTER_ERRORS_COUNTER);
  }

  private static final Comparator<Entry> ORDER =
      new Comparator<Entry>() {
        @Override
        public int compare(Entry a, Entry b) {
          return a.order < b.order ? -1 : a.order == b.order ? 0 : 1;
        }
      };

  /**
   * One registered endpoint: a weak reference and plain values only, never the endpoint class (its
   * adapter is found through {@link #ADAPTERS} from the live endpoint).
   */
  private static final class Entry {
    final WeakReference<Object> endpoint;
    final String name;
    final long order;
    volatile int port;

    Entry(Object endpoint, String name, long order, int port) {
      this.endpoint = new WeakReference<Object>(endpoint);
      this.name = name;
      this.order = order;
      this.port = port;
    }
  }

  /**
   * The getters of one endpoint class (adapted handles; null when absent). Kept only in {@link
   * #ADAPTERS} on that class; refers to nothing outside the class's own loader (no registry, no
   * class value).
   */
  static final class Adapter {
    /** A class that is no {@code AbstractEndpoint} ({@code type} null: never registered). */
    static final Adapter NONE = new Adapter();

    /** The endpoint class; null for {@link #NONE}. */
    final Class<?> type;

    final MethodHandle name;
    final MethodHandle busy;
    final MethodHandle current;
    final MethodHandle max;
    final MethodHandle connections;
    final MethodHandle maxConnections;
    final MethodHandle localPort;
    final MethodHandle port;

    /** Optional ({@code getUseVirtualThreads()}, {@code getExecutor()}): never counted. */
    final MethodHandle virtualThreads;

    final MethodHandle executor;
    final int missingGetters;

    private Adapter(Class<?> type, Class<?> owner) {
      this.type = type;
      this.name = Getters.adapt(Getters.method(owner, "getName"));
      this.busy = Getters.adapt(Getters.method(owner, "getCurrentThreadsBusy"));
      this.current = Getters.adapt(Getters.method(owner, "getCurrentThreadCount"));
      this.max = Getters.adapt(Getters.method(owner, "getMaxThreads"));
      this.connections = Getters.adapt(Getters.method(owner, "getConnectionCount"));
      this.maxConnections = Getters.adapt(Getters.method(owner, "getMaxConnections"));
      this.localPort = Getters.adapt(Getters.method(owner, "getLocalPort"));
      this.port = Getters.adapt(Getters.method(owner, "getPort"));
      this.virtualThreads = Getters.adapt(Getters.method(owner, "getUseVirtualThreads"));
      this.executor = Getters.adapt(Getters.method(owner, "getExecutor"));
      int present = 0;
      for (MethodHandle handle :
          new MethodHandle[] {
            name, busy, current, max, connections, maxConnections, localPort, port
          }) {
        present += handle == null ? 0 : 1;
      }
      this.missingGetters = 8 - present;
    }

    /** {@link #NONE}. */
    private Adapter() {
      this.type = null;
      this.name = null;
      this.busy = null;
      this.current = null;
      this.max = null;
      this.connections = null;
      this.maxConnections = null;
      this.localPort = null;
      this.port = null;
      this.virtualThreads = null;
      this.executor = null;
      this.missingGetters = 0;
    }

    /** The adapter of an {@code AbstractEndpoint} subclass; {@link #NONE} for any other class. */
    static Adapter build(Class<?> type) {
      Class<?> owner = Getters.ancestor(type, ENDPOINT);
      return owner == null ? NONE : new Adapter(type, owner);
    }
  }
}
