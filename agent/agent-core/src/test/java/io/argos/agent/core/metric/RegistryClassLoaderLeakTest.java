/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.metric;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.bootstrap.PoolKind;
import io.argos.agent.core.metric.redeploy.RedeployPoolApp;
import io.argos.agent.core.metric.redeploy.RedeployTomcatApp;
import java.lang.ref.WeakReference;
import java.net.URL;
import java.net.URLClassLoader;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import org.apache.commons.dbcp2.BasicDataSource;
import org.apache.commons.pool2.impl.GenericObjectPool;
import org.apache.tomcat.util.net.NioEndpoint;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The registries keep no web application alive (10 §6.4 "weak registry, removed when collected",
 * F-A36): a registered pool or connector endpoint must not pin the class loader of an application
 * that is undeployed or redeployed.
 *
 * <p>Each test loads the library (DBCP2 with commons-pool2 and the commons-logging API, or Tomcat)
 * and an application class that holds the pool or endpoint in a static field ({@link
 * RedeployPoolApp}, {@link RedeployTomcatApp}) in a child {@link URLClassLoader} whose parent is
 * the platform (extension) loader, as a web application's loader. The registry, like the agent,
 * comes from the test's own loader. After the registration every strong reference of the test is
 * dropped, and the child loader must then be collected (bounded wait), both when no snapshot ran
 * (the registry drops a collected entry only in {@code register} and {@code snapshot}, and before
 * WO-16 nothing calls {@code snapshot} regularly) and when one did. The test fails as soon as an
 * entry, an adapter, a method handle or a cache of the registry refers to the library class, since
 * the class refers to its loader, the loader to the application class and its static field to the
 * pool.
 */
@Tag("req:F-A36")
@Tag("req:F-A10")
@Tag("req:DSN-10-12")
class RegistryClassLoaderLeakTest {

  /**
   * Initializes {@link DriverManager} with the test's context class loader first: DBCP2's static
   * initializer calls {@code DriverManager.getDrivers()}, which would otherwise load the JDBC
   * drivers of this JVM through the child loader set as context loader (none) and leave H2
   * unregistered for the other tests of the JVM.
   */
  @BeforeAll
  static void initializeDriverManager() {
    DriverManager.getDrivers();
  }

  @Test
  @DisplayName(
      "T-AG-062h PoolRegistry: a redeployed application's class loader (DBCP2 and a class holding"
          + " the pool in a static field) is collected while its entry stays registered and no"
          + " snapshot ran")
  void poolWithoutSnapshot() throws Exception {
    PoolRegistry registry = new PoolRegistry();
    WeakReference<ClassLoader> loader = deployPool(registry, false);
    PoolRegistryTest.awaitCollected(loader);
    assertThat(registry.slotsInUse()).as("no expunge ran: the entry is still there").isEqualTo(1);
    assertThat(registry.size()).isZero();
    assertThat(registry.snapshot()).isEmpty();
    assertThat(registry.slotsInUse()).isZero();
    assertThat(registry.getterErrorsTotal()).isZero();
  }

  @Test
  @DisplayName(
      "T-AG-062h PoolRegistry: the class loader is collected after snapshots called the pool's"
          + " getters and nameOf found it")
  void poolAfterSnapshot() throws Exception {
    PoolRegistry registry = new PoolRegistry();
    WeakReference<ClassLoader> loader = deployPool(registry, true);
    PoolRegistryTest.awaitCollected(loader);
    assertThat(registry.snapshot()).isEmpty();
    assertThat(registry.slotsInUse()).isZero();
    assertThat(registry.getterErrorsTotal()).isZero();
  }

  @Test
  @DisplayName(
      "T-AG-062h TomcatRegistry: an application class loader holding a registered endpoint in a"
          + " static field is collected, with and without a snapshot")
  void tomcat() throws Exception {
    for (boolean snapshot : new boolean[] {false, true}) {
      TomcatRegistryTest.RecordingPublisher publisher = new TomcatRegistryTest.RecordingPublisher();
      publisher.destination = "session";
      TomcatRegistry registry = new TomcatRegistry(publisher);
      WeakReference<ClassLoader> loader = deployTomcat(registry, snapshot);
      PoolRegistryTest.awaitCollected(loader);
      assertThat(registry.size()).as("snapshot ran: %s", snapshot).isZero();
      assertThat(registry.snapshot()).isEmpty();
      assertThat(registry.slotsInUse()).isZero();
      assertThat(registry.getterErrorsTotal()).isZero();
      assertThat(publisher.calls).containsExactly("session [http-nio-redeploy] 18089");
    }
  }

  /**
   * Deploys the pool application in its own loader, registers its pool and returns a weak reference
   * to the loader; no strong reference to the loader, its classes or the pool remains.
   */
  private static WeakReference<ClassLoader> deployPool(PoolRegistry registry, boolean snapshot)
      throws Exception {
    URLClassLoader loader =
        webApplication(
            BasicDataSource.class,
            GenericObjectPool.class,
            Class.forName(
                "org.apache.commons.logging.LogFactory",
                false,
                RegistryClassLoaderLeakTest.class.getClassLoader()),
            RedeployPoolApp.class);
    try {
      Object pool = start(loader, RedeployPoolApp.class.getName());
      assertThat(pool.getClass().getName()).isEqualTo(BasicDataSource.class.getName());
      assertThat(pool.getClass()).isNotSameAs(BasicDataSource.class);
      assertThat(registry.register(pool, PoolKind.DBCP2)).isTrue();
      assertThat(registry.nameOf(pool)).isEqualTo("BasicDataSource-1");
      if (snapshot) {
        for (int i = 0; i < 3; i++) {
          List<PoolSample> samples = registry.snapshot();
          assertThat(samples).hasSize(1);
          assertThat(samples.get(0).max()).isEqualTo(7);
          assertThat(samples.get(0).active()).isZero();
        }
      }
      return new WeakReference<ClassLoader>(loader);
    } finally {
      loader.close();
    }
  }

  /** As {@link #deployPool} for the endpoint application and the Tomcat registry. */
  private static WeakReference<ClassLoader> deployTomcat(TomcatRegistry registry, boolean snapshot)
      throws Exception {
    URLClassLoader loader = webApplication(NioEndpoint.class, RedeployTomcatApp.class);
    try {
      Object endpoint = start(loader, RedeployTomcatApp.class.getName());
      assertThat(endpoint.getClass()).isNotSameAs(NioEndpoint.class);
      assertThat(registry.register(endpoint)).isTrue();
      if (snapshot) {
        for (int i = 0; i < 3; i++) {
          List<ConnectorSample> samples = registry.snapshot();
          assertThat(samples).hasSize(1);
          assertThat(samples.get(0).threadsMax()).isEqualTo(23);
          assertThat(samples.get(0).port()).isEqualTo(18089);
        }
      }
      return new WeakReference<ClassLoader>(loader);
    } finally {
      loader.close();
    }
  }

  /**
   * Calls the public static {@code start()} of {@code appClass} as loaded by {@code loader}, with
   * {@code loader} as the context class loader (as a container does for its applications).
   */
  private static Object start(ClassLoader loader, String appClass) throws Exception {
    Thread thread = Thread.currentThread();
    ClassLoader previous = thread.getContextClassLoader();
    thread.setContextClassLoader(loader);
    try {
      Class<?> app = Class.forName(appClass, true, loader);
      assertThat(app.getClassLoader()).isSameAs(loader);
      return app.getMethod("start").invoke(null);
    } finally {
      thread.setContextClassLoader(previous);
    }
  }

  /**
   * A web application's loader: the class path entries (jars, the test classes directory) of {@code
   * classes}, parent the platform (extension) loader, so that the library and application classes
   * are defined by this loader and not by the test's.
   */
  private static URLClassLoader webApplication(Class<?>... classes) {
    List<URL> urls = new ArrayList<URL>();
    List<String> seen = new ArrayList<String>();
    for (Class<?> type : classes) {
      URL location = type.getProtectionDomain().getCodeSource().getLocation();
      if (!seen.contains(location.toExternalForm())) {
        seen.add(location.toExternalForm());
        urls.add(location);
      }
    }
    return new URLClassLoader(
        urls.toArray(new URL[0]), ClassLoader.getSystemClassLoader().getParent());
  }
}
