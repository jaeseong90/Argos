/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.pool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.argos.agent.bootstrap.AgentControl;
import io.argos.agent.bootstrap.DictKind;
import io.argos.agent.bootstrap.ModuleHealth;
import io.argos.agent.bootstrap.PoolKind;
import io.argos.agent.bootstrap.TestControls;
import io.argos.agent.bootstrap.Tracer;
import io.argos.agent.core.metric.PoolRegistry;
import io.argos.agent.core.metric.PoolSample;
import io.argos.agent.core.trace.TracerImpl;
import java.lang.management.ManagementFactory;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import javax.management.ObjectName;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The pool module's advice inlined into the real HikariCP 4.0.3, Tomcat JDBC 9.0.83 and DBCP2 2.9.0
 * classes (Java 8 versions of the Spring Boot 2.7 BOM) by {@link AdvisingLoader}, as the agent does
 * at load time, over in-memory H2: each pool registers itself with the real tracer's pool registry
 * when it exists, with JMX off (no MBean is registered or read), two pools are told apart by name,
 * a re-created pool registers again, a failed pool start registers nothing, and an advice failure
 * never reaches the application (10 §6.4, F-A10, DSN-10-12, A-7).
 */
@Tag("req:F-A10")
@Tag("req:DSN-10-12")
class PoolAdviceTest {
  private static final String FIXTURES = "io.argos.agent.instrumentation.pool.fixture.PoolFixtures";

  private final AgentControl control = TestControls.newControl();
  private final PoolRegistry registry = new PoolRegistry();
  private final List<DataSource> open = new ArrayList<DataSource>();
  private TracerImpl tracer;
  private AdvisingLoader loader;
  private Class<?> fixtures;
  private int errorsBefore;

  @BeforeEach
  void install() throws Exception {
    tracer = TracerImpl.builder().control(control).pools(registry).build();
    control.installTracer(tracer);
    loader =
        new AdvisingLoader(
            PoolAdviceTest.class.getClassLoader(),
            new PoolModule(),
            "com.zaxxer.hikari.",
            "org.apache.tomcat.jdbc.",
            "org.apache.commons.dbcp2.",
            "io.argos.agent.instrumentation.pool.fixture.");
    fixtures = loader.loadClass(FIXTURES);
    errorsBefore = ModuleHealth.errors(PoolModule.MODULE_ID);
  }

  @AfterEach
  void uninstall() throws Exception {
    for (int i = open.size() - 1; i >= 0; i--) {
      call("close", open.get(i));
    }
    control.installTracer(null);
  }

  private Object call(String method, Object... args) throws Exception {
    for (Method candidate : fixtures.getMethods()) {
      if (candidate.getName().equals(method)
          && candidate.getParameterTypes().length == args.length) {
        try {
          return candidate.invoke(null, args);
        } catch (InvocationTargetException e) {
          if (e.getCause() instanceof Exception) {
            throw (Exception) e.getCause();
          }
          throw e;
        }
      }
    }
    throw new NoSuchMethodException(method);
  }

  private DataSource pool(String method, Object... args) throws Exception {
    DataSource dataSource = (DataSource) call(method, args);
    open.add(dataSource);
    return dataSource;
  }

  private PoolSample sample(String name) {
    for (PoolSample sample : registry.snapshot()) {
      if (sample.name().equals(name)) {
        return sample;
      }
    }
    throw new AssertionError("no pool named " + name + " in " + registry.snapshot());
  }

  private List<String> names() {
    List<String> names = new ArrayList<String>();
    for (PoolSample sample : registry.snapshot()) {
      names.add(sample.name());
    }
    return names;
  }

  private void assertNoAdviceErrors() {
    assertThat(ModuleHealth.errors(PoolModule.MODULE_ID))
        .as("advice errors of the pool module (suppressed exceptions)")
        .isEqualTo(errorsBefore);
  }

  @Test
  @DisplayName(
      "T-AG-062d the advised pool classes are exactly HikariPool, Tomcat JDBC ConnectionPool and"
          + " DBCP2 BasicDataSource")
  void advisesTheThreePoolClasses() throws Exception {
    pool("hikari", "orders", "advised-h", 2);
    pool("tomcat", "tomcat", "advised-t", 2);
    pool("dbcp2", "advised-d", 2);
    assertThat(loader.advised())
        .containsExactlyInAnyOrder(
            PoolModule.HIKARI_POOL, PoolModule.TOMCAT_JDBC_POOL, PoolModule.DBCP2_DATA_SOURCE);
    assertNoAdviceErrors();
  }

  @Test
  @DisplayName(
      "T-AG-062d HikariPool constructor exit registers the pool by its name with getter values"
          + " and no MBean (JMX off)")
  void hikari() throws Exception {
    DataSource orders = pool("hikari", "orders", "hikari-a", 5);
    Connection held = (Connection) call("hold", orders);
    try {
      PoolSample sample = sample("orders");
      assertThat(sample.kind()).isEqualTo(PoolKind.HIKARI);
      assertThat(sample.max()).isEqualTo(5);
      assertThat(sample.active()).isEqualTo(1);
      assertThat(sample.idle()).isGreaterThanOrEqualTo(0);
      assertThat(sample.total()).isGreaterThanOrEqualTo(1);
      assertThat(sample.waiting()).isZero();
      long id = tracer.poolNameId(orders);
      assertThat(id).as("DBC step name of the data source (C-143 (4))").isNotZero();
      assertThat(tracer.poolNameId(new Object())).isZero();
      assertThat(dictionaryText(id)).isEqualTo("orders");
    } finally {
      held.close();
    }
    assertThat(mbeans("com.zaxxer.hikari:*")).as("Hikari MBeans (registerMbeans off)").isEmpty();
    assertNoAdviceErrors();
  }

  @Test
  @DisplayName(
      "T-AG-062d Tomcat JDBC ConnectionPool constructor exit registers the pool; DBCP2"
          + " createDataSource exit registers the data source once (waiting missing)")
  void tomcatAndDbcp2() throws Exception {
    DataSource tomcat = pool("tomcat", "tomcat-orders", "tomcat-a", 4);
    DataSource dbcp2 = pool("dbcp2", "dbcp2-a", 3);
    for (int i = 0; i < 5; i++) {
      call("use", dbcp2);
      call("use", tomcat);
    }
    PoolSample tomcatSample = sample("tomcat-orders");
    assertThat(tomcatSample.kind()).isEqualTo(PoolKind.TOMCAT_JDBC);
    assertThat(tomcatSample.max()).isEqualTo(4);
    assertThat(tomcatSample.active()).isZero();
    assertThat(tomcatSample.idle()).isGreaterThanOrEqualTo(1);
    assertThat(tracer.poolNameId(tomcat)).as("DataSourceProxy -> pool name").isNotZero();

    List<PoolSample> dbcp2Samples = new ArrayList<PoolSample>();
    for (PoolSample sample : registry.snapshot()) {
      if (sample.kind() == PoolKind.DBCP2) {
        dbcp2Samples.add(sample);
      }
    }
    assertThat(dbcp2Samples).as("one entry for six createDataSource() calls").hasSize(1);
    PoolSample dbcp2Sample = dbcp2Samples.get(0);
    assertThat(dbcp2Sample.name()).startsWith("BasicDataSource-");
    assertThat(dbcp2Sample.max()).isEqualTo(3);
    assertThat(dbcp2Sample.waiting()).as("DBCP2 has no waiting getter: missing").isEqualTo(-1);
    assertThat(tracer.poolNameId(dbcp2)).isNotZero();
    assertThat(mbeans("tomcat.jdbc:*")).as("Tomcat JDBC MBeans (jmxEnabled off)").isEmpty();
    assertNoAdviceErrors();
  }

  @Test
  @DisplayName(
      "T-AG-062d two pools of one library are told apart by name; a re-created pool of the same"
          + " name registers again")
  void twoPoolsAndRestart() throws Exception {
    DataSource first = pool("hikari", "orders", "two-a", 2);
    pool("hikari", "audit", "two-b", 3);
    assertThat(names()).containsExactly("orders", "audit");
    assertThat(sample("orders").max())
        .as("max of orders; getter errors %s", registry.getterErrorsTotal())
        .isEqualTo(2);
    assertThat(sample("audit").max())
        .as("max of audit; getter errors %s", registry.getterErrorsTotal())
        .isEqualTo(3);
    long ordersId = tracer.poolNameId(first);

    call("close", first);
    open.remove(first);
    DataSource restarted = pool("hikari", "orders", "two-a", 4);
    assertThat(names()).as("the re-created pool replaced the closed one").hasSize(2);
    assertThat(sample("orders").max()).as("values of the new pool").isEqualTo(4);
    assertThat(tracer.poolNameId(restarted)).isEqualTo(ordersId);

    // Unnamed pools: Hikari names itself HikariPool-<n>.
    pool("hikari", null, "two-c", 1);
    assertThat(names()).anyMatch(name -> name.startsWith("HikariPool-"));
    assertNoAdviceErrors();
  }

  @Test
  @DisplayName(
      "T-AG-062d the same pool libraries in a second class loader (another web application, a"
          + " redeploy) register with their values too")
  void secondClassLoader() throws Exception {
    pool("hikari", "first-app", "loader-a", 2);
    pool("tomcat", "first-tomcat", "loader-at", 2);
    Class<?> before = fixtures;
    AdvisingLoader second =
        new AdvisingLoader(
            PoolAdviceTest.class.getClassLoader(),
            new PoolModule(),
            "com.zaxxer.hikari.",
            "org.apache.tomcat.jdbc.",
            "io.argos.agent.instrumentation.pool.fixture.");
    fixtures = second.loadClass(FIXTURES);
    assertThat(fixtures).isNotSameAs(before);
    pool("hikari", "second-app", "loader-b", 3);
    pool("tomcat", "second-tomcat", "loader-bt", 4);
    assertThat(sample("first-app").max()).isEqualTo(2);
    assertThat(sample("second-app").max())
        .as("getters of the second loader's HikariPool (Java 8 loader constraints)")
        .isEqualTo(3);
    assertThat(sample("first-tomcat").max()).isEqualTo(2);
    assertThat(sample("second-tomcat").max()).isEqualTo(4);
    assertThat(sample("second-app").active()).isZero();
    assertThat(registry.getterErrorsTotal()).isZero();
    // close() of the pools below goes through the loader that created each of them
    for (DataSource dataSource : open) {
      dataSource.getClass().getMethod("close").invoke(dataSource);
    }
    open.clear();
    assertNoAdviceErrors();
  }

  @Test
  @DisplayName("T-AG-062d a pool whose start fails (constructor throws) is not registered")
  void failedStartIsNotRegistered() throws Exception {
    assertThatThrownBy(() -> call("failingHikari")).isInstanceOf(RuntimeException.class);
    assertThat(registry.snapshot()).isEmpty();
    assertNoAdviceErrors();
  }

  @Test
  @DisplayName(
      "T-AG-062d a failing registry call stays in the advice: the pool starts, the error is counted"
          + " against the module")
  void adviceFailureIsContained() throws Exception {
    control.installTracer(
        (Tracer)
            Proxy.newProxyInstance(
                Tracer.class.getClassLoader(),
                new Class<?>[] {Tracer.class},
                new InvocationHandler() {
                  @Override
                  public Object invoke(Object proxy, Method method, Object[] args)
                      throws Throwable {
                    if (method.getName().equals("registerPool")) {
                      throw new IllegalStateException("injected registry failure");
                    }
                    try {
                      return method.invoke(tracer, args);
                    } catch (InvocationTargetException e) {
                      throw e.getCause();
                    }
                  }
                }));
    DataSource dataSource = pool("hikari", "contained", "contained", 1);
    call("use", dataSource);
    assertThat(registry.snapshot()).isEmpty();
    assertThat(ModuleHealth.errors(PoolModule.MODULE_ID)).isGreaterThan(errorsBefore);
  }

  private String dictionaryText(long id) {
    // The pool name text behind the POOL dictionary id, through the tracer's dictionary.
    long again = tracer.dict(DictKind.POOL, "orders");
    return again == id ? "orders" : "#" + id;
  }

  private static java.util.Set<ObjectName> mbeans(String pattern) throws Exception {
    return ManagementFactory.getPlatformMBeanServer().queryNames(new ObjectName(pattern), null);
  }
}
