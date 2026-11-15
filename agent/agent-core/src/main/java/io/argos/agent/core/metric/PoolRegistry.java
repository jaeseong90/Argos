/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.metric;

import io.argos.agent.bootstrap.PoolKind;
import io.argos.agent.core.log.AgentLogger;
import java.lang.invoke.MethodHandle;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.atomic.LongAdder;

/**
 * The connection pools of the application (10 §6.4, F-A10, DSN-10-12): the pool module registers
 * each pool when it exists ({@code Tracer.registerPool}), the JDBC module names DBC steps after it
 * ({@link #nameOf}, 10 §6.3, ADR-0010 C-101, C-143 (4)) and the metric collector of WO-16 reads
 * {@link #snapshot()} every 5 s for the 10 §11 series {@code jdbc_pool_*{pool}}.
 *
 * <p><b>Getters only, never JMX</b> (DSN-10-12): each pool class gets an adapter of method handles
 * on its public getters ({@link Getters}), built once per class and shared by the pools of that
 * class. The adapter is kept on the pool class itself ({@link ClassValue}), never in a registry
 * entry or another agent structure:
 *
 * <ul>
 *   <li>{@link PoolKind#HIKARI} {@code HikariPool}: {@code getActiveConnections}, {@code
 *       getIdleConnections}, {@code getThreadsAwaitingConnection}, {@code getTotalConnections};
 *       through its public {@code config} field {@code getMaximumPoolSize} and {@code getPoolName}.
 *       The config object is the {@code HikariDataSource} that created the pool, so {@link #nameOf}
 *       finds the pool from the data source without calling it.
 *   <li>{@link PoolKind#TOMCAT_JDBC} {@code ConnectionPool}: {@code getActive}, {@code getIdle},
 *       {@code getWaitCount}, {@code getSize}, {@code getName}, {@code
 *       getPoolProperties().getMaxActive()}. A {@code DataSourceProxy} is matched through its
 *       {@code getPoolProperties()} (a field read): its {@code getPool()} would create the pool.
 *   <li>{@link PoolKind#DBCP2} {@code BasicDataSource} (the data source is the registered object):
 *       {@code getNumActive}, {@code getNumIdle}, {@code getMaxTotal}; waiting and total are
 *       missing (the library has no such getter).
 * </ul>
 *
 * <p><b>Names</b>: the pool's own name, else {@code <SimpleName>-<n>} ({@code n} counts unnamed
 * pools per kind from 1), cut to {@value #MAX_NAME_CHARS} characters. A pool registered under the
 * name of a pool already listed replaces it (a re-created pool; two live pools of one name would be
 * one metric series anyway).
 *
 * <p><b>No class loader pinned</b> (F-A36): an entry holds its pool and alias weakly and otherwise
 * only its name, kind and order; the adapter, whose method handles refer to the pool class, hangs
 * on that class ({@link Class} → class value → adapter → {@link Class}), so it is collected with
 * the class loader of the application, whether or not the entry was dropped yet. Neither the
 * adapter nor its resolver refers to the registry or to the class value. Checked by {@code
 * RegistryClassLoaderLeakTest} (T-AG-062h: a redeployed application's loader, holding its pool in a
 * static field, is collected with and without a snapshot).
 *
 * <p><b>Bounds and failures</b>: weak references only (a collected pool disappears from the
 * snapshot at once and its entry is dropped at the next registration or snapshot); at most {@value
 * #MAX_POOLS} pools, further ones counted in {@value #OVERFLOW_COUNTER}; a getter that throws, or
 * an expected getter the library version lacks (counted once per registered pool), makes the value
 * missing (-1, C-4) and counts in {@value #GETTER_ERRORS_COUNTER}, with one WARN. No method throws,
 * blocks on a lock of its own or calls a pool method with side effects.
 *
 * <p>Thread-safe and lock-free: registration and {@link #nameOf} run on application threads, {@link
 * #snapshot()} on an agent thread.
 */
public final class PoolRegistry {
  /** Upper bound of registered pools. */
  public static final int MAX_POOLS = 64;

  /** Upper bound of a pool name (a metric label and a {@code POOL} dictionary text). */
  public static final int MAX_NAME_CHARS = 256;

  /** Pools not registered because {@value #MAX_POOLS} were registered. */
  public static final String OVERFLOW_COUNTER =
      "argos_agent_registry_overflow_total{registry=\"pool\"}";

  /** Getter calls that failed and expected getters a pool class lacks. */
  public static final String GETTER_ERRORS_COUNTER =
      "argos_agent_registry_getter_errors_total{registry=\"pool\"}";

  static final String HIKARI_POOL = "com.zaxxer.hikari.pool.HikariPool";
  static final String TOMCAT_POOL = "org.apache.tomcat.jdbc.pool.ConnectionPool";
  static final String TOMCAT_DATA_SOURCE = "org.apache.tomcat.jdbc.pool.DataSourceProxy";
  static final String DBCP2_DATA_SOURCE = "org.apache.commons.dbcp2.BasicDataSource";

  /**
   * The adapter of each class a pool was registered or read with, kept on that class (see the class
   * comment: no class loader pinned); {@link Adapter#NONE} for a class of no known kind.
   */
  private static final ClassValue<Adapter> ADAPTERS =
      new ClassValue<Adapter>() {
        @Override
        protected Adapter computeValue(Class<?> type) {
          return Adapter.build(type);
        }
      };

  private final AtomicReferenceArray<Entry> slots = new AtomicReferenceArray<Entry>(MAX_POOLS);
  private final AtomicIntegerArray unnamed = new AtomicIntegerArray(PoolKind.COUNT);
  private final AtomicLong order = new AtomicLong();
  private final LongAdder overflow = new LongAdder();
  private final LongAdder getterErrors = new LongAdder();

  /**
   * Registers {@code pool} of {@link PoolKind} {@code kind}. Returns true when it was added; false
   * for null, an unknown kind, a pool already registered, a class without the getters of its kind
   * (counted) or a full registry (counted). Never throws.
   */
  public boolean register(Object pool, int kind) {
    if (pool == null || !PoolKind.valid(kind)) {
      return false;
    }
    try {
      expunge();
      if (entryOf(pool) != null) {
        return false;
      }
      Adapter adapter = ADAPTERS.get(pool.getClass());
      if (adapter.kind != kind) {
        getterErrors.increment();
        AgentLogger.warnOnce(
            "registry.pool.unsupported",
            "{} is not a pool of the registered kind: it has no pool metrics (counted in {})",
            pool.getClass(),
            GETTER_ERRORS_COUNTER);
        return false;
      }
      if (adapter.missingGetters > 0) {
        getterErrors.add(adapter.missingGetters);
        AgentLogger.warnOnce(
            "registry.pool.missing-getter",
            "{} lacks {} of the expected pool getters: those values are reported missing (counted"
                + " in {})",
            pool.getClass(),
            adapter.missingGetters,
            GETTER_ERRORS_COUNTER);
      }
      Object alias = adapter.alias == null ? null : read(adapter.alias, pool);
      Entry entry = new Entry(pool, alias, kind, name(adapter, pool), order.incrementAndGet());
      return insert(entry);
    } catch (Throwable t) {
      getterErrors.increment();
      return false;
    }
  }

  /**
   * The name of the registered pool that is {@code dataSourceOrPool} or belongs to it (Hikari's
   * {@code HikariDataSource}, Tomcat JDBC's {@code DataSourceProxy}, DBCP2's {@code
   * BasicDataSource}); null when there is none. Reads at most {@code getPoolProperties()} of a
   * Tomcat data source; never creates a pool. Never throws.
   */
  public String nameOf(Object dataSourceOrPool) {
    if (dataSourceOrPool == null) {
      return null;
    }
    try {
      boolean tomcat = false;
      for (int i = 0; i < MAX_POOLS; i++) {
        Entry entry = slots.get(i);
        if (entry == null) {
          continue;
        }
        if (Getters.same(entry.pool.get(), dataSourceOrPool)
            || (entry.alias != null && Getters.same(entry.alias.get(), dataSourceOrPool))) {
          return entry.name;
        }
        tomcat |= entry.kind == PoolKind.TOMCAT_JDBC;
      }
      return tomcat ? tomcatDataSource(dataSourceOrPool) : null;
    } catch (Throwable t) {
      return null;
    }
  }

  /** A Tomcat JDBC {@code DataSourceProxy}: the pool whose properties object it holds. */
  private String tomcatDataSource(Object dataSource) {
    for (int i = 0; i < MAX_POOLS; i++) {
      Entry entry = slots.get(i);
      if (entry == null || entry.alias == null || entry.kind != PoolKind.TOMCAT_JDBC) {
        continue;
      }
      Object pool = entry.pool.get();
      if (pool == null) {
        continue;
      }
      Object properties = ADAPTERS.get(pool.getClass()).dataSourceProperties(dataSource, this);
      if (properties != null && Getters.same(properties, entry.alias.get())) {
        return entry.name;
      }
    }
    return null;
  }

  /**
   * The values of every registered pool in registration order (getters called now, on the calling
   * thread); collected pools are dropped first. Never throws.
   */
  public List<PoolSample> snapshot() {
    List<PoolSample> samples = new ArrayList<PoolSample>();
    try {
      expunge();
      for (Entry entry : live()) {
        Object pool = entry.pool.get();
        if (pool == null) {
          continue;
        }
        Adapter a = ADAPTERS.get(pool.getClass());
        samples.add(
            new PoolSample(
                entry.name,
                a.kind,
                value(a.active, pool),
                value(a.idle, pool),
                value(a.waiting, pool),
                value(a.total, pool),
                value(a.max, pool)));
      }
    } catch (Throwable t) {
      getterErrors.increment();
    }
    return Collections.unmodifiableList(samples);
  }

  /** Registered pools that are still alive. */
  public int size() {
    int size = 0;
    for (int i = 0; i < MAX_POOLS; i++) {
      Entry entry = slots.get(i);
      if (entry != null && entry.pool.get() != null) {
        size++;
      }
    }
    return size;
  }

  /** Slots holding an entry, collected or not (tests: no entry outlives its pool's expunge). */
  int slotsInUse() {
    int used = 0;
    for (int i = 0; i < MAX_POOLS; i++) {
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

  private String name(Adapter adapter, Object pool) {
    Object own = adapter.name == null ? null : read(adapter.name, pool);
    String name = own instanceof String ? Getters.cut((String) own, MAX_NAME_CHARS) : null;
    if (name != null) {
      return name;
    }
    String generated =
        Getters.simpleName(pool.getClass()) + "-" + unnamed.incrementAndGet(adapter.kind);
    return generated.length() > MAX_NAME_CHARS
        ? generated.substring(generated.length() - MAX_NAME_CHARS)
        : generated;
  }

  private boolean insert(Entry entry) {
    // A pool with the name of a listed pool replaces it (re-created pool).
    for (int i = 0; i < MAX_POOLS; i++) {
      Entry old = slots.get(i);
      if (old != null && old.name.equals(entry.name) && slots.compareAndSet(i, old, entry)) {
        return true;
      }
    }
    for (int i = 0; i < MAX_POOLS; i++) {
      Entry old = slots.get(i);
      if ((old == null || old.pool.get() == null) && slots.compareAndSet(i, old, entry)) {
        return true;
      }
    }
    overflow.increment();
    AgentLogger.warnOnce(
        "registry.pool.overflow",
        "more than {} connection pools: further pools have no pool metrics (counted in {})",
        MAX_POOLS,
        OVERFLOW_COUNTER);
    return false;
  }

  private Entry entryOf(Object pool) {
    for (int i = 0; i < MAX_POOLS; i++) {
      Entry entry = slots.get(i);
      if (entry != null && Getters.same(entry.pool.get(), pool)) {
        return entry;
      }
    }
    return null;
  }

  /** Drops entries whose pool was collected. */
  private void expunge() {
    for (int i = 0; i < MAX_POOLS; i++) {
      Entry entry = slots.get(i);
      if (entry != null && entry.pool.get() == null) {
        slots.compareAndSet(i, entry, null);
      }
    }
  }

  private List<Entry> live() {
    List<Entry> entries = new ArrayList<Entry>();
    for (int i = 0; i < MAX_POOLS; i++) {
      Entry entry = slots.get(i);
      if (entry != null) {
        entries.add(entry);
      }
    }
    Collections.sort(entries, ORDER);
    return entries;
  }

  /** A getter's value as a sample number; missing for no handle; a failure is counted. */
  private long value(MethodHandle getter, Object target) {
    if (getter == null) {
      return Getters.MISSING;
    }
    try {
      return Getters.number(Getters.get(getter, target));
    } catch (Throwable t) {
      failed(target, t);
      return Getters.MISSING;
    }
  }

  /** A getter's value, or null when it failed (counted). */
  private Object read(MethodHandle getter, Object target) {
    try {
      return Getters.get(getter, target);
    } catch (Throwable t) {
      failed(target, t);
      return null;
    }
  }

  void failed(Object target, Throwable t) {
    getterErrors.increment();
    AgentLogger.warnOnce(
        "registry.pool.getter",
        "a connection pool getter of {} failed with {}: the value is reported missing (counted in"
            + " {})",
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
   * One registered pool: weak references and plain values only, never the pool class (its adapter
   * is found through {@link #ADAPTERS} from the live pool).
   */
  private static final class Entry {
    final WeakReference<Object> pool;

    /** The object the application holds for the pool (Hikari config, Tomcat properties). */
    final WeakReference<Object> alias;

    /** {@link PoolKind} of the pool. */
    final int kind;

    final String name;
    final long order;

    Entry(Object pool, Object alias, int kind, String name, long order) {
      this.pool = new WeakReference<Object>(pool);
      this.alias = alias == null ? null : new WeakReference<Object>(alias);
      this.kind = kind;
      this.name = name;
      this.order = order;
    }
  }

  /**
   * The getters of one pool class (adapted handles; null when the class has none). Kept only in
   * {@link #ADAPTERS} on that class; refers to nothing outside the class's own loader (no registry,
   * no class value).
   */
  static final class Adapter {
    /** A class of no known pool kind ({@code kind} 0: no registration matches it). */
    static final Adapter NONE = new Adapter(null, 0, null, null, null, null, null, null, null, 0);

    final Class<?> type;
    final int kind;
    final MethodHandle active;
    final MethodHandle idle;
    final MethodHandle waiting;
    final MethodHandle total;
    final MethodHandle max;
    final MethodHandle name;
    final MethodHandle alias;

    /** Getters the kind provides but this class lacks (another library version). */
    final int missingGetters;

    /** Tomcat JDBC: {@code DataSourceProxy.getPoolProperties()} of this pool's class loader. */
    private volatile Resolver dataSources;

    private Adapter(
        Class<?> type,
        int kind,
        MethodHandle active,
        MethodHandle idle,
        MethodHandle waiting,
        MethodHandle total,
        MethodHandle max,
        MethodHandle name,
        MethodHandle alias,
        int expected) {
      this.type = type;
      this.kind = kind;
      this.active = active;
      this.idle = idle;
      this.waiting = waiting;
      this.total = total;
      this.max = max;
      this.name = name;
      this.alias = alias;
      int present = 0;
      for (MethodHandle handle :
          new MethodHandle[] {active, idle, waiting, total, max, name, alias}) {
        present += handle == null ? 0 : 1;
      }
      this.missingGetters = Math.max(0, expected - present);
    }

    /**
     * The adapter of {@code type}: the kind whose pool class is {@code type} or one of its
     * superclasses (at most one: the three are unrelated classes), else {@link #NONE}.
     */
    static Adapter build(Class<?> type) {
      for (int kind = 1; kind < PoolKind.COUNT; kind++) {
        Adapter adapter = build(type, kind);
        if (adapter != null) {
          return adapter;
        }
      }
      return NONE;
    }

    /** The adapter of {@code type} for {@code kind}; null when the class is not of that kind. */
    private static Adapter build(Class<?> type, int kind) {
      switch (kind) {
        case PoolKind.HIKARI:
          {
            Class<?> owner = Getters.ancestor(type, HIKARI_POOL);
            if (owner == null) {
              return null;
            }
            MethodHandle config = Getters.field(owner, "config");
            MethodHandle name = Getters.then(config, "getPoolName");
            if (name == null) {
              // HikariPool.toString() returns its pool name (PoolBase).
              name = Getters.method(owner, "toString");
            }
            return new Adapter(
                type,
                kind,
                Getters.adapt(Getters.method(owner, "getActiveConnections")),
                Getters.adapt(Getters.method(owner, "getIdleConnections")),
                Getters.adapt(Getters.method(owner, "getThreadsAwaitingConnection")),
                Getters.adapt(Getters.method(owner, "getTotalConnections")),
                Getters.adapt(Getters.then(config, "getMaximumPoolSize")),
                Getters.adapt(name),
                Getters.adapt(config),
                7);
          }
        case PoolKind.TOMCAT_JDBC:
          {
            Class<?> owner = Getters.ancestor(type, TOMCAT_POOL);
            if (owner == null) {
              return null;
            }
            MethodHandle properties = Getters.method(owner, "getPoolProperties");
            return new Adapter(
                type,
                kind,
                Getters.adapt(Getters.method(owner, "getActive")),
                Getters.adapt(Getters.method(owner, "getIdle")),
                Getters.adapt(Getters.method(owner, "getWaitCount")),
                Getters.adapt(Getters.method(owner, "getSize")),
                Getters.adapt(Getters.then(properties, "getMaxActive")),
                Getters.adapt(Getters.method(owner, "getName")),
                Getters.adapt(properties),
                7);
          }
        case PoolKind.DBCP2:
          {
            Class<?> owner = Getters.ancestor(type, DBCP2_DATA_SOURCE);
            if (owner == null) {
              return null;
            }
            return new Adapter(
                type,
                kind,
                Getters.adapt(Getters.method(owner, "getNumActive")),
                Getters.adapt(Getters.method(owner, "getNumIdle")),
                null,
                null,
                Getters.adapt(Getters.method(owner, "getMaxTotal")),
                null,
                null,
                3);
          }
        default:
          return null;
      }
    }

    /**
     * Tomcat JDBC: {@code dataSource.getPoolProperties()} when {@code dataSource} is a {@code
     * DataSourceProxy} of this pool's class loader, else null. The handle is kept in this adapter,
     * so it refers only to a class of the pool's own loader and goes with that loader; {@code
     * registry} is used for counting only, never kept.
     */
    Object dataSourceProperties(Object dataSource, PoolRegistry registry) {
      if (type == null) {
        return null;
      }
      Class<?> proxy = Getters.ancestor(dataSource.getClass(), TOMCAT_DATA_SOURCE);
      if (proxy == null || !Getters.same(proxy.getClassLoader(), type.getClassLoader())) {
        return null;
      }
      Resolver resolver = dataSources;
      if (resolver == null || !Getters.same(resolver.type, proxy)) {
        MethodHandle handle = Getters.adapt(Getters.method(proxy, "getPoolProperties"));
        if (handle == null) {
          return null;
        }
        resolver = new Resolver(proxy, handle);
        dataSources = resolver;
      }
      try {
        return Getters.get(resolver.handle, dataSource);
      } catch (Throwable t) {
        registry.failed(dataSource, t);
        return null;
      }
    }
  }

  /** A getter on a data source class (of the adapter's class loader, see above). */
  private static final class Resolver {
    final Class<?> type;
    final MethodHandle handle;

    Resolver(Class<?> type, MethodHandle handle) {
      this.type = type;
      this.handle = handle;
    }
  }
}
