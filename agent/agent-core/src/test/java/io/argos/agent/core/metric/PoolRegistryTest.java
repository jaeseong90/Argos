/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.metric;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.pool.HikariPool;
import io.argos.agent.bootstrap.DictKind;
import io.argos.agent.bootstrap.PoolKind;
import io.argos.agent.core.trace.TracerImpl;
import java.lang.management.ManagementFactory;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.management.ObjectName;
import org.apache.commons.dbcp2.BasicDataSource;
import org.apache.tomcat.jdbc.pool.ConnectionPool;
import org.apache.tomcat.jdbc.pool.DataSourceProxy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * {@link PoolRegistry} against the real pool libraries over in-memory H2 (10 §6.4, F-A10,
 * DSN-10-12): the values come from the pools' getters with JMX off, names, missing values, weak
 * removal, re-registration, the cap and failing getters. The registration itself is what the pool
 * module's advice does at the end of the pool constructors (HikariPool, ConnectionPool) and of
 * DBCP2's {@code createDataSource()}.
 */
@Tag("req:F-A10")
@Tag("req:DSN-10-12")
class PoolRegistryTest {
  private final List<AutoCloseable> closeables = new ArrayList<AutoCloseable>();

  @AfterEach
  void close() throws Exception {
    for (int i = closeables.size() - 1; i >= 0; i--) {
      closeables.get(i).close();
    }
  }

  private <T extends AutoCloseable> T closeLater(T value) {
    closeables.add(value);
    return value;
  }

  private HikariDataSource hikari(String name, String database, int max) {
    HikariConfig config = new HikariConfig();
    config.setJdbcUrl("jdbc:h2:mem:" + database + ";DB_CLOSE_DELAY=-1");
    config.setUsername("sa");
    config.setPassword("");
    if (name != null) {
      config.setPoolName(name);
    }
    config.setMaximumPoolSize(max);
    config.setMinimumIdle(1);
    config.setRegisterMbeans(false);
    return closeLater(new HikariDataSource(config));
  }

  private org.apache.tomcat.jdbc.pool.DataSource tomcatJdbc(String name, String database) {
    final org.apache.tomcat.jdbc.pool.DataSource ds = new org.apache.tomcat.jdbc.pool.DataSource();
    ds.setDriverClassName("org.h2.Driver");
    ds.setUrl("jdbc:h2:mem:" + database + ";DB_CLOSE_DELAY=-1");
    ds.setUsername("sa");
    ds.setPassword("");
    ds.setName(name);
    ds.setMaxActive(5);
    ds.setMaxIdle(5);
    ds.setMinIdle(1);
    ds.setInitialSize(2);
    ds.setJmxEnabled(false);
    closeables.add(
        new AutoCloseable() {
          @Override
          public void close() {
            ds.close(true);
          }
        });
    return ds;
  }

  private BasicDataSource dbcp2(String database, int maxTotal) {
    BasicDataSource ds = new BasicDataSource();
    ds.setDriverClassName("org.h2.Driver");
    ds.setUrl("jdbc:h2:mem:" + database + ";DB_CLOSE_DELAY=-1");
    ds.setUsername("sa");
    ds.setPassword("");
    ds.setMaxTotal(maxTotal);
    return closeLater(ds);
  }

  private static PoolSample only(List<PoolSample> samples, String name) {
    for (PoolSample sample : samples) {
      if (sample.name().equals(name)) {
        return sample;
      }
    }
    throw new AssertionError("no pool " + name + " in " + samples);
  }

  @Test
  @Tag("req:F-A09")
  @DisplayName(
      "T-AG-062a HikariCP: active/idle/waiting/total and config max from getters with JMX off; the"
          + " pool's own name; the HikariDataSource maps to its pool without being called")
  void hikari() throws Exception {
    PoolRegistry registry = new PoolRegistry();
    HikariDataSource ds = hikari("orders-pool", "wo13-hikari", 4);
    HikariPool pool = (HikariPool) ds.getHikariPoolMXBean();
    assertThat(registry.register(pool, PoolKind.HIKARI)).isTrue();
    assertThat(registry.register(pool, PoolKind.HIKARI)).as("registered once").isFalse();

    Connection held = ds.getConnection();
    try {
      PoolSample sample = only(registry.snapshot(), "orders-pool");
      assertThat(sample.kindName()).isEqualTo("hikari");
      assertThat(sample.active()).isEqualTo(1).isEqualTo(pool.getActiveConnections());
      assertThat(sample.idle()).isEqualTo(pool.getIdleConnections());
      assertThat(sample.waiting()).isZero();
      assertThat(sample.total()).isEqualTo(pool.getTotalConnections()).isGreaterThanOrEqualTo(1);
      assertThat(sample.max()).isEqualTo(4);
    } finally {
      held.close();
    }
    assertThat(only(registry.snapshot(), "orders-pool").active()).isZero();
    assertThat(registry.nameOf(pool)).isEqualTo("orders-pool");
    assertThat(registry.nameOf(ds))
        .as("the data source is the pool's config")
        .isEqualTo("orders-pool");
    assertThat(registry.nameOf(new Object())).isNull();
    assertThat(registry.nameOf(null)).isNull();
    assertThat(registry.getterErrorsTotal()).isZero();
    assertThat(
            ManagementFactory.getPlatformMBeanServer()
                .queryNames(new ObjectName("com.zaxxer.hikari:*"), null))
        .as("no MBean: the values do not come from JMX")
        .isEmpty();
  }

  @Test
  @Tag("req:F-A09")
  @DisplayName(
      "T-AG-062a Tomcat JDBC: getActive/getIdle/getWaitCount/getSize and getPoolProperties()"
          + ".getMaxActive(); DataSourceProxy mapped by its properties, getPool() never called")
  void tomcatJdbc() throws Exception {
    PoolRegistry registry = new PoolRegistry();
    org.apache.tomcat.jdbc.pool.DataSource ds = tomcatJdbc("billing", "wo13-tomcat");
    Connection held = ds.getConnection();
    try {
      ConnectionPool pool = ds.getPool();
      assertThat(registry.register(pool, PoolKind.TOMCAT_JDBC)).isTrue();
      PoolSample sample = only(registry.snapshot(), "billing");
      assertThat(sample.kindName()).isEqualTo("tomcat-jdbc");
      assertThat(sample.active()).isEqualTo(1).isEqualTo(pool.getActive());
      assertThat(sample.idle()).isEqualTo(pool.getIdle());
      assertThat(sample.waiting()).isZero();
      assertThat(sample.total()).isEqualTo(pool.getSize()).isEqualTo(2);
      assertThat(sample.max()).isEqualTo(5);
      assertThat(registry.nameOf(pool)).isEqualTo("billing");
      assertThat(registry.nameOf(ds)).isEqualTo("billing");
    } finally {
      held.close();
    }

    org.apache.tomcat.jdbc.pool.DataSource unused = tomcatJdbc("never", "wo13-tomcat-unused");
    assertThat(registry.nameOf(unused)).isNull();
    Field poolField = DataSourceProxy.class.getDeclaredField("pool");
    poolField.setAccessible(true);
    assertThat(poolField.get(unused))
        .as("nameOf must not create the pool (getPool() would)")
        .isNull();
    assertThat(
            ManagementFactory.getPlatformMBeanServer()
                .queryNames(new ObjectName("tomcat.jdbc:*"), null))
        .isEmpty();
  }

  @Test
  @DisplayName(
      "T-AG-062a DBCP2: getNumActive/getNumIdle/getMaxTotal; no name, so BasicDataSource-<n>;"
          + " waiting and total missing (-1), not counted as errors")
  void dbcp2() throws Exception {
    PoolRegistry registry = new PoolRegistry();
    BasicDataSource first = dbcp2("wo13-dbcp2-a", 6);
    BasicDataSource second = dbcp2("wo13-dbcp2-b", 3);
    Connection held = first.getConnection();
    second.getConnection().close();
    try {
      assertThat(registry.register(first, PoolKind.DBCP2)).isTrue();
      assertThat(registry.register(second, PoolKind.DBCP2)).isTrue();
      List<PoolSample> samples = registry.snapshot();
      assertThat(samples)
          .extracting(PoolSample::name)
          .containsExactly("BasicDataSource-1", "BasicDataSource-2");
      PoolSample sample = samples.get(0);
      assertThat(sample.kindName()).isEqualTo("dbcp2");
      assertThat(sample.active()).isEqualTo(1).isEqualTo(first.getNumActive());
      assertThat(sample.idle()).isEqualTo(first.getNumIdle());
      assertThat(sample.max()).isEqualTo(6);
      assertThat(sample.waiting()).isEqualTo(-1);
      assertThat(sample.total()).isEqualTo(-1);
      assertThat(samples.get(1).max()).isEqualTo(3);
      assertThat(registry.nameOf(first)).isEqualTo("BasicDataSource-1");
      assertThat(registry.getterErrorsTotal()).isZero();
    } finally {
      held.close();
    }
  }

  @Test
  @DisplayName(
      "T-AG-062a two pools keep distinct names and their own values, in registration order")
  void twoPools() throws Exception {
    PoolRegistry registry = new PoolRegistry();
    HikariDataSource read = hikari("read-pool", "wo13-two-a", 2);
    HikariDataSource write = hikari("write-pool", "wo13-two-b", 7);
    registry.register(read.getHikariPoolMXBean(), PoolKind.HIKARI);
    registry.register(write.getHikariPoolMXBean(), PoolKind.HIKARI);
    Connection held = write.getConnection();
    try {
      List<PoolSample> samples = registry.snapshot();
      assertThat(samples).extracting(PoolSample::name).containsExactly("read-pool", "write-pool");
      assertThat(samples.get(0).max()).isEqualTo(2);
      assertThat(samples.get(0).active()).isZero();
      assertThat(samples.get(1).max()).isEqualTo(7);
      assertThat(samples.get(1).active()).isEqualTo(1);
      assertThat(registry.nameOf(read)).isEqualTo("read-pool");
      assertThat(registry.nameOf(write)).isEqualTo("write-pool");
    } finally {
      held.close();
    }
  }

  @Test
  @DisplayName(
      "T-AG-062a a re-created pool registers again: the same name replaces the old entry, a pool"
          + " created after the old one was collected takes a slot again")
  void reRegistration() throws Exception {
    PoolRegistry registry = new PoolRegistry();
    HikariDataSource old = hikari("orders", "wo13-recreate-a", 2);
    registry.register(old.getHikariPoolMXBean(), PoolKind.HIKARI);
    old.close();
    HikariDataSource recreated = hikari("orders", "wo13-recreate-b", 3);
    assertThat(registry.register(recreated.getHikariPoolMXBean(), PoolKind.HIKARI)).isTrue();
    assertThat(registry.size()).isEqualTo(1);
    assertThat(registry.snapshot()).extracting(PoolSample::max).containsExactly(3L);
    assertThat(registry.nameOf(recreated)).isEqualTo("orders");
    assertThat(registry.nameOf(old)).as("the replaced pool is gone").isNull();

    WeakReference<Object> collected = registerThrowaway(registry);
    awaitCollected(collected);
    assertThat(registry.snapshot()).extracting(PoolSample::name).containsExactly("orders");
    BasicDataSource again = dbcp2("wo13-recreate-c", 2);
    assertThat(registry.register(again, PoolKind.DBCP2)).isTrue();
    assertThat(registry.size()).isEqualTo(2);
  }

  @Test
  @Tag("req:F-A36")
  @DisplayName(
      "T-AG-062a weak references: a collected pool disappears from the snapshot and its entry is"
          + " dropped (the class loader part is T-AG-062h)")
  void weakRemoval() throws Exception {
    PoolRegistry registry = new PoolRegistry();
    WeakReference<Object> ref = registerThrowaway(registry);
    assertThat(registry.size()).isEqualTo(1);
    awaitCollected(ref);
    assertThat(registry.snapshot()).isEmpty();
    assertThat(registry.size()).isZero();
    assertThat(registry.slotsInUse()).as("no entry keeps the pool class").isZero();
  }

  @Test
  @Tag("req:N-02")
  @DisplayName(
      "T-AG-062a at most 64 pools: the 65th is refused and counted; a slot freed by GC is reused")
  void cap() throws Exception {
    PoolRegistry registry = new PoolRegistry();
    List<BasicDataSource> pools = new ArrayList<BasicDataSource>();
    for (int i = 0; i < PoolRegistry.MAX_POOLS; i++) {
      BasicDataSource ds = new BasicDataSource();
      pools.add(ds);
      assertThat(registry.register(ds, PoolKind.DBCP2)).isTrue();
    }
    assertThat(registry.register(new BasicDataSource(), PoolKind.DBCP2)).isFalse();
    assertThat(registry.overflowTotal()).isEqualTo(1);
    assertThat(registry.counters()).containsEntry(PoolRegistry.OVERFLOW_COUNTER, 1L);
    assertThat(registry.snapshot()).hasSize(PoolRegistry.MAX_POOLS);

    WeakReference<Object> ref = new WeakReference<Object>(pools.remove(0));
    awaitCollected(ref);
    assertThat(registry.register(new BasicDataSource(), PoolKind.DBCP2)).isTrue();
    assertThat(registry.size()).isEqualTo(PoolRegistry.MAX_POOLS);
  }

  @Test
  @DisplayName(
      "T-AG-062a a throwing getter yields a missing value and a counter, never an exception; a"
          + " class of another kind, null and unknown kinds are refused")
  void failingGetter() {
    PoolRegistry registry = new PoolRegistry();
    FailingDataSource failing = new FailingDataSource();
    assertThat(registry.register(failing, PoolKind.DBCP2)).isTrue();
    PoolSample sample = registry.snapshot().get(0);
    assertThat(sample.active()).isEqualTo(-1);
    assertThat(sample.idle()).isZero();
    assertThat(sample.max()).isEqualTo(8);
    assertThat(registry.getterErrorsTotal()).isEqualTo(1);
    registry.snapshot();
    assertThat(registry.getterErrorsTotal()).as("counted per failure").isEqualTo(2);
    assertThat(registry.counters()).containsEntry(PoolRegistry.GETTER_ERRORS_COUNTER, 2L);

    assertThat(registry.register(new Object(), PoolKind.HIKARI)).isFalse();
    assertThat(registry.getterErrorsTotal()).isEqualTo(3);
    assertThat(registry.register(null, PoolKind.DBCP2)).isFalse();
    assertThat(registry.register(new BasicDataSource(), 0)).isFalse();
    assertThat(registry.register(new BasicDataSource(), 99)).isFalse();
    assertThat(registry.size()).isEqualTo(1);
  }

  @Test
  @Tag("req:C-101")
  @DisplayName(
      "T-AG-062a Tracer: registerPool and poolNameId give the DBC step a POOL dictionary id (C-101,"
          + " C-143 (4)); 0 for an unknown object; nothing throws")
  void tracerApi() {
    TracerImpl tracer = TracerImpl.builder().build();
    HikariDataSource ds = hikari("orders-pool", "wo13-tracer", 2);
    assertThat(tracer.poolNameId(ds)).as("nothing registered yet").isZero();
    tracer.registerPool(ds.getHikariPoolMXBean(), PoolKind.HIKARI);
    long id = tracer.poolNameId(ds);
    assertThat(id).isNotZero().isEqualTo(tracer.poolNameId(ds.getHikariPoolMXBean()));
    assertThat(tracer.dictionary().text(id)).isEqualTo("orders-pool");
    assertThat(tracer.dictionary().kind(id)).isEqualTo(DictKind.POOL);
    assertThat(tracer.poolNameId(new Object())).isZero();
    assertThat(tracer.poolNameId(null)).isZero();
    tracer.registerPool(null, PoolKind.HIKARI);
    tracer.registerPool(new Object(), 42);
    assertThat(tracer.pools().size()).isEqualTo(1);
  }

  @Test
  @DisplayName("the development self-diagnostics members list pools without missing values")
  void diagnosticsMembers() throws Exception {
    PoolRegistry registry = new PoolRegistry();
    BasicDataSource ds = dbcp2("wo13-diag", 4);
    ds.getConnection().close();
    registry.register(ds, PoolKind.DBCP2);
    String members = MetricDiagnostics.members(registry, null);
    assertThat(members)
        .startsWith("\"pools\":[{\"name\":\"BasicDataSource-1\",\"kind\":\"dbcp2\",\"active\":0,")
        .contains("\"max\":4}")
        .doesNotContain("\"waiting\"")
        .doesNotContain("\"total\"")
        .contains("\"connectors\":[]")
        .contains("\"" + PoolRegistry.OVERFLOW_COUNTER.replace("\"", "\\\"") + "\":0");
  }

  /** A DBCP2 data source whose {@code getNumActive()} fails (a broken library version). */
  public static final class FailingDataSource extends BasicDataSource {
    @Override
    public int getNumActive() {
      throw new IllegalStateException("broken getter");
    }
  }

  /** Registers a pool that nothing else references. */
  private static WeakReference<Object> registerThrowaway(PoolRegistry registry) {
    BasicDataSource ds = new BasicDataSource();
    assertThat(registry.register(ds, PoolKind.DBCP2)).isTrue();
    return new WeakReference<Object>(ds);
  }

  /** Runs GC until {@code ref} is cleared (bounded). */
  static void awaitCollected(WeakReference<?> ref) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
    while (ref.get() != null && System.nanoTime() < deadline) {
      System.gc();
      Thread.sleep(5);
    }
    assertThat(ref.get()).as("collected").isNull();
  }
}
