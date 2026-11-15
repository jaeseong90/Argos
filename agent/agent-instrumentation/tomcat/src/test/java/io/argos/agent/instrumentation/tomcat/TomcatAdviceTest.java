/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.tomcat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.argos.agent.bootstrap.AgentControl;
import io.argos.agent.bootstrap.ModuleHealth;
import io.argos.agent.bootstrap.TestControls;
import io.argos.agent.core.metric.ConnectorSample;
import io.argos.agent.core.metric.TomcatRegistry;
import io.argos.agent.core.trace.TracerImpl;
import java.lang.management.ManagementFactory;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import javax.management.ObjectName;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The tomcat module's advice inlined into the real Tomcat 9.0.83 {@code AbstractEndpoint} by {@link
 * AdvisingLoader}, as the agent does at load time: a started endpoint registers with the real
 * tracer's Tomcat registry, which publishes the connector names and port ({@code InstanceUpdate},
 * 10 §10.4) and reads the getters without JMX; a failed start registers nothing; a restart keeps
 * one entry (10 §6.4, F-A10, DSN-10-12).
 */
@Tag("req:F-A10")
@Tag("req:DSN-10-12")
class TomcatAdviceTest {
  private static final String FIXTURES =
      "io.argos.agent.instrumentation.tomcat.fixture.TomcatFixtures";

  private final AgentControl control = TestControls.newControl();
  private final Publisher publisher = new Publisher();
  private final TomcatRegistry registry = new TomcatRegistry(publisher);
  private final List<Object> started = new ArrayList<Object>();
  private AdvisingLoader loader;
  private Class<?> fixtures;
  private int errorsBefore;

  @BeforeEach
  void install() throws Exception {
    control.installTracer(TracerImpl.builder().control(control).connectors(registry).build());
    loader =
        new AdvisingLoader(
            TomcatAdviceTest.class.getClassLoader(),
            new TomcatModule(),
            "org.apache.tomcat.util.net.",
            "io.argos.agent.instrumentation.tomcat.fixture.");
    fixtures = loader.loadClass(FIXTURES);
    errorsBefore = ModuleHealth.errors(TomcatModule.MODULE_ID);
    publisher.destination = "session-1";
  }

  @AfterEach
  void uninstall() throws Exception {
    for (Object endpoint : started) {
      call("stop", endpoint);
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

  private Object start(String name, int port, int maxThreads) throws Exception {
    Object endpoint = call("endpoint", name, port, maxThreads, false);
    call("start", endpoint);
    started.add(endpoint);
    return endpoint;
  }

  private void assertNoAdviceErrors() {
    assertThat(ModuleHealth.errors(TomcatModule.MODULE_ID))
        .as("advice errors of the tomcat module (suppressed exceptions)")
        .isEqualTo(errorsBefore);
  }

  @Test
  @DisplayName(
      "T-AG-062e AbstractEndpoint.start() exit registers the started connector: name, port and"
          + " thread getters without JMX, connector list and port published for InstanceUpdate")
  void startRegisters() throws Exception {
    start("http-nio-18080", 18080, 33);
    assertThat(loader.advised()).containsExactly(TomcatModule.ENDPOINT);
    List<ConnectorSample> samples = registry.snapshot();
    assertThat(samples).hasSize(1);
    ConnectorSample sample = samples.get(0);
    assertThat(sample.name()).isEqualTo("http-nio-18080");
    assertThat(sample.port()).isEqualTo(18080);
    assertThat(sample.threadsMax()).isEqualTo(33);
    assertThat(sample.threadsCurrent()).isGreaterThanOrEqualTo(0);
    assertThat(sample.connectionsMax()).isEqualTo(321);
    assertThat(publisher.calls).contains("session-1 [http-nio-18080] 18080");
    assertThat(mbeans("Catalina:*")).as("no Tomcat MBean registered or needed").isEmpty();
    assertNoAdviceErrors();
  }

  @Test
  @DisplayName(
      "T-AG-062e two connectors in start order; a restarted connector keeps one entry; a failed"
          + " start registers nothing")
  void twoConnectorsRestartAndFailure() throws Exception {
    Object http = start("http-nio-18081", 18081, 10);
    start("https-jsse-nio-18443", 18443, 10);
    assertThat(names()).containsExactly("http-nio-18081", "https-jsse-nio-18443");
    assertThat(publisher.calls).contains("session-1 [http-nio-18081, https-jsse-nio-18443] 18081");

    call("stop", http);
    call("start", http);
    assertThat(names()).as("restart: one entry").hasSize(2);

    Object failing = call("endpoint", "http-nio-18082", 18082, 10, true);
    assertThatThrownBy(() -> call("start", failing)).isInstanceOf(java.net.BindException.class);
    assertThat(names()).doesNotContain("http-nio-18082");
    assertNoAdviceErrors();
  }

  private List<String> names() {
    List<String> names = new ArrayList<String>();
    for (ConnectorSample sample : registry.snapshot()) {
      names.add(sample.name());
    }
    return names;
  }

  private static java.util.Set<ObjectName> mbeans(String pattern) throws Exception {
    return ManagementFactory.getPlatformMBeanServer().queryNames(new ObjectName(pattern), null);
  }

  /** Records what the registry publishes for {@code InstanceUpdate}. */
  static final class Publisher implements TomcatRegistry.Publisher {
    volatile Object destination;
    final List<String> calls = Collections.synchronizedList(new ArrayList<String>());

    @Override
    public Object destination() {
      return destination;
    }

    @Override
    public void publish(Object destination, List<String> connectorNames, int port) {
      calls.add(destination + " " + Arrays.toString(connectorNames.toArray()) + " " + port);
    }
  }
}
