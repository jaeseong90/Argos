/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.metric;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.core.trace.TracerImpl;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.apache.tomcat.util.net.NioEndpoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * {@link TomcatRegistry} against real Tomcat 9 {@code NioEndpoint} objects (10 §6.4, F-A10,
 * DSN-10-12): getters only, missing values, the virtual-thread rule, the connector list for {@code
 * InstanceUpdate} (10 §10.4), weak removal, replacement, the cap and failing getters. The endpoints
 * get Tomcat's own executor ({@code createExecutor()}) but bind no socket: unit tests use no
 * network (18 §2.2); the started connector of a sample app is the integration test's.
 */
@Tag("req:F-A10")
@Tag("req:DSN-10-12")
class TomcatRegistryTest {
  private final List<NioEndpoint> withExecutor = new ArrayList<NioEndpoint>();

  /** Strong references for the test's duration: registered endpoints must not be collected. */
  private final List<NioEndpoint> kept = new ArrayList<NioEndpoint>();

  private final RecordingPublisher publisher = new RecordingPublisher();

  @AfterEach
  void shutdownExecutors() {
    for (NioEndpoint endpoint : withExecutor) {
      endpoint.shutdownExecutor();
    }
  }

  /** An endpoint kept alive for the test's duration. */
  private NioEndpoint endpoint(String name, int port) {
    NioEndpoint endpoint = unreferenced(name, port);
    kept.add(endpoint);
    return endpoint;
  }

  private static NioEndpoint unreferenced(String name, int port) {
    NioEndpoint endpoint = new NioEndpoint();
    endpoint.setName(name);
    endpoint.setPort(port);
    return endpoint;
  }

  private NioEndpoint running(String name, int port) {
    NioEndpoint endpoint = endpoint(name, port);
    endpoint.setMinSpareThreads(3);
    endpoint.setMaxThreads(17);
    endpoint.setMaxConnections(123);
    endpoint.createExecutor();
    withExecutor.add(endpoint);
    awaitIdle(endpoint);
    return endpoint;
  }

  /**
   * Waits (at most 10 s) until the prestarted core threads run: a worker that has not started yet
   * counts as active in Tomcat's {@code ThreadPoolExecutor.getActiveCount()} (state -1), so on a
   * busy machine {@code getCurrentThreadsBusy()} briefly reports the core threads as busy.
   */
  private static void awaitIdle(NioEndpoint endpoint) {
    long deadline = System.nanoTime() + 10_000_000_000L;
    while (endpoint.getCurrentThreadsBusy() != 0 && System.nanoTime() < deadline) {
      try {
        Thread.sleep(2);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
    }
  }

  @Test
  @Tag("req:F-A09")
  @DisplayName(
      "T-AG-062b Tomcat endpoint getters without JMX: name, port, busy/current/max threads,"
          + " current and max connections equal Tomcat's own getters")
  void getters() {
    TomcatRegistry registry = new TomcatRegistry(publisher);
    NioEndpoint endpoint = running("http-nio-18080", 18080);
    assertThat(registry.register(endpoint)).isTrue();
    assertThat(registry.register(endpoint)).as("registered once").isFalse();

    ConnectorSample sample = registry.snapshot().get(0);
    assertThat(sample.name()).isEqualTo("http-nio-18080");
    assertThat(sample.port()).as("configured port while unbound").isEqualTo(18080);
    assertThat(sample.threadsCurrent()).isEqualTo(endpoint.getCurrentThreadCount()).isEqualTo(3);
    assertThat(sample.threadsBusy()).isEqualTo(endpoint.getCurrentThreadsBusy()).isZero();
    assertThat(sample.threadsMax()).isEqualTo(17);
    assertThat(sample.connectionsMax()).isEqualTo(endpoint.getMaxConnections()).isEqualTo(123);
    assertThat(sample.connectionsCurrent())
        .isEqualTo(endpoint.getConnectionCount())
        .isGreaterThanOrEqualTo(0);
    assertThat(registry.getterErrorsTotal()).isZero();
    assertThat(registry.connectorNames()).containsExactly("http-nio-18080");
  }

  @Test
  @DisplayName(
      "T-AG-062b missing values: no executor yet (busy, current), a virtual-thread executor"
          + " (max threads, 10 §6.4)")
  void missingValues() {
    TomcatRegistry registry = new TomcatRegistry(publisher);
    NioEndpoint idle = endpoint("http-nio-1", 1);
    NioEndpoint virtual = endpoint("http-nio-2", 2);
    virtual.setUseVirtualThreads(true);
    registry.register(idle);
    registry.register(virtual);
    List<ConnectorSample> samples = registry.snapshot();
    assertThat(samples.get(0).threadsBusy()).isEqualTo(-1);
    assertThat(samples.get(0).threadsCurrent()).isEqualTo(-1);
    assertThat(samples.get(0).threadsMax()).isEqualTo(idle.getMaxThreads());
    assertThat(virtual.getMaxThreads()).as("Tomcat still reports its platform cap").isPositive();
    assertThat(samples.get(1).threadsMax()).isEqualTo(-1);
    assertThat(registry.getterErrorsTotal()).isZero();
  }

  @Test
  @Tag("req:F-A09")
  @DisplayName(
      "T-AG-062b InstanceUpdate (10 §10.4): names in registration order and the first port go to"
          + " the session once it exists, again only after a change or for a new session")
  void publishesConnectors() {
    TomcatRegistry registry = new TomcatRegistry(publisher);
    registry.register(endpoint("http-nio-8080", 8080));
    assertThat(publisher.calls).as("no session yet").isEmpty();

    publisher.destination = "session-1";
    registry.snapshot();
    assertThat(publisher.calls).containsExactly("session-1 [http-nio-8080] 8080");
    registry.snapshot();
    registry.snapshot();
    assertThat(publisher.calls).as("unchanged: not sent again").hasSize(1);

    NioEndpoint ajp = endpoint("ajp-nio-8009", 8009);
    registry.register(ajp);
    assertThat(publisher.calls).last().isEqualTo("session-1 [http-nio-8080, ajp-nio-8009] 8080");

    publisher.destination = "session-2";
    registry.snapshot();
    assertThat(publisher.calls).last().isEqualTo("session-2 [http-nio-8080, ajp-nio-8009] 8080");
    assertThat(publisher.calls).hasSize(3);
  }

  @Test
  @Tag("req:F-A36")
  @DisplayName(
      "T-AG-062b a collected endpoint disappears, its entry is dropped and the shorter list is"
          + " published")
  void weakRemoval() throws Exception {
    TomcatRegistry registry = new TomcatRegistry(publisher);
    publisher.destination = "session";
    registry.register(endpoint("http-nio-8080", 8080));
    WeakReference<Object> ref = registerThrowaway(registry, "http-nio-9090");
    assertThat(registry.size()).isEqualTo(2);
    PoolRegistryTest.awaitCollected(ref);
    assertThat(registry.snapshot())
        .extracting(ConnectorSample::name)
        .containsExactly("http-nio-8080");
    assertThat(registry.slotsInUse()).isEqualTo(1);
    assertThat(publisher.calls).last().isEqualTo("session [http-nio-8080] 8080");
  }

  @Test
  @DisplayName(
      "T-AG-062b a restarted connector (same name) replaces the old endpoint; at most 32"
          + " endpoints, the 33rd counted")
  void replacementAndCap() {
    TomcatRegistry registry = new TomcatRegistry(publisher);
    NioEndpoint first = endpoint("http-nio-8080", 8080);
    NioEndpoint restarted = endpoint("http-nio-8080", 8080);
    restarted.setMaxThreads(50);
    registry.register(first);
    assertThat(registry.register(restarted)).isTrue();
    assertThat(registry.snapshot()).extracting(ConnectorSample::threadsMax).containsExactly(50L);

    // endpoint(..) keeps every registered endpoint reachable, so no slot is freed by GC here.
    for (int i = 1; i < TomcatRegistry.MAX_CONNECTORS; i++) {
      assertThat(registry.register(endpoint("c-" + i, 10_000 + i))).isTrue();
    }
    assertThat(registry.register(unreferenced("c-overflow", 9))).isFalse();
    assertThat(registry.overflowTotal()).isEqualTo(1);
    assertThat(registry.counters()).containsEntry(TomcatRegistry.OVERFLOW_COUNTER, 1L);
    assertThat(registry.size()).isEqualTo(TomcatRegistry.MAX_CONNECTORS);
  }

  @Test
  @DisplayName(
      "T-AG-062b a throwing getter yields a missing value and a counter, never an exception; a"
          + " non-endpoint and null are refused")
  void failingGetter() {
    TomcatRegistry registry = new TomcatRegistry(publisher);
    FailingEndpoint failing = new FailingEndpoint();
    kept.add(failing);
    failing.setName("http-nio-7");
    failing.setPort(7);
    assertThat(registry.register(failing)).isTrue();
    ConnectorSample sample = registry.snapshot().get(0);
    assertThat(sample.threadsBusy()).isEqualTo(-1);
    assertThat(sample.connectionsMax()).isEqualTo(failing.getMaxConnections());
    assertThat(registry.getterErrorsTotal()).isEqualTo(1);
    assertThat(registry.register(new Object())).isFalse();
    assertThat(registry.register(unreferenced("not-an-endpoint", 1).getClass())).isFalse();
    assertThat(registry.register(null)).isFalse();
    assertThat(registry.getterErrorsTotal()).isEqualTo(3);

    FailingPublisher broken = new FailingPublisher();
    TomcatRegistry withBrokenPublisher = new TomcatRegistry(broken);
    assertThat(withBrokenPublisher.register(endpoint("http-nio-6", 6))).isTrue();
    assertThat(withBrokenPublisher.snapshot()).hasSize(1);
    assertThat(broken.calls).as("retried by the next snapshot").isEqualTo(2);
  }

  @Test
  @DisplayName(
      "T-AG-062b Tracer.registerConnector reaches the registry and never throws; the development"
          + " self-diagnostics members list the connector")
  void tracerApi() {
    TomcatRegistry registry = new TomcatRegistry(publisher);
    TracerImpl tracer = TracerImpl.builder().connectors(registry).build();
    tracer.registerConnector(running("http-nio-18081", 18081));
    tracer.registerConnector(null);
    tracer.registerConnector(new Object());
    assertThat(tracer.connectors()).isSameAs(registry);
    assertThat(registry.connectorNames()).containsExactly("http-nio-18081");
    String members = MetricDiagnostics.members(null, registry);
    assertThat(members)
        .startsWith("\"pools\":[],")
        .contains(
            "\"connectors\":[{\"name\":\"http-nio-18081\",\"port\":18081,\"threads_busy\":0,"
                + "\"threads_current\":3,\"threads_max\":17,")
        .contains("\"connections_max\":123}]")
        .contains(
            "\"" + TomcatRegistry.GETTER_ERRORS_COUNTER.replace("\"", "\\\"") + "\":1",
            "\"" + TomcatRegistry.OVERFLOW_COUNTER.replace("\"", "\\\"") + "\":0");

    TomcatRegistry unknownPort = new TomcatRegistry(publisher);
    NioEndpoint noPort = running("http-nio-0", 0);
    unknownPort.register(noPort);
    assertThat(MetricDiagnostics.members(null, unknownPort))
        .as("a missing value (-1) is left out, never written as 0 or -1 (C-4)")
        .contains("{\"name\":\"http-nio-0\",\"threads_busy\":0,")
        .doesNotContain("\"port\"");
  }

  /** Registers an endpoint nothing else references. */
  private static WeakReference<Object> registerThrowaway(TomcatRegistry registry, String name) {
    NioEndpoint endpoint = unreferenced(name, 9090);
    assertThat(registry.register(endpoint)).isTrue();
    return new WeakReference<Object>(endpoint);
  }

  /** An endpoint whose busy-thread getter fails (a broken Tomcat version). */
  public static final class FailingEndpoint extends NioEndpoint {
    @Override
    public int getCurrentThreadsBusy() {
      throw new IllegalStateException("broken getter");
    }
  }

  /** Records what is published: "destination [names] port". */
  static final class RecordingPublisher implements TomcatRegistry.Publisher {
    volatile Object destination;
    final List<String> calls = new ArrayList<String>();

    @Override
    public Object destination() {
      return destination;
    }

    @Override
    public void publish(Object destination, List<String> connectorNames, int port) {
      calls.add(destination + " " + Arrays.toString(connectorNames.toArray()) + " " + port);
    }
  }

  /** A publisher whose session API fails. */
  static final class FailingPublisher implements TomcatRegistry.Publisher {
    int calls;

    @Override
    public Object destination() {
      return "session";
    }

    @Override
    public void publish(Object destination, List<String> connectorNames, int port) {
      calls++;
      throw new IllegalStateException("session failed");
    }
  }
}
