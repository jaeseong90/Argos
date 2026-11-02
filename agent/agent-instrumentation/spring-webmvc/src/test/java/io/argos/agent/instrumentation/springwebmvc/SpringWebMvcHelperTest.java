/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.springwebmvc;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.bootstrap.AgentControl;
import io.argos.agent.bootstrap.Kind;
import io.argos.agent.bootstrap.TestControls;
import io.argos.agent.bootstrap.TraceContext;
import io.argos.agent.core.trace.Dictionary;
import io.argos.agent.core.trace.TracerImpl;
import io.argos.agent.core.trace.Transaction;
import io.argos.agent.core.trace.TxIdGenerator;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Spring MVC naming (10 §6.2, ADR-0004 C-48) of both namespace helpers, with the real tracer. */
@Tag("req:F-A02")
class SpringWebMvcHelperTest {
  private final List<Transaction> ended = new ArrayList<Transaction>();
  private final AgentControl control = TestControls.newControl();
  private TracerImpl tracer;

  /** A request proxy of one namespace. */
  static final class Request implements InvocationHandler {
    final String namespace;
    final Map<String, Object> attributes = new HashMap<String, Object>();
    final List<String> calls = new ArrayList<String>();
    String method = "GET";
    String contextPath = "";
    String dispatcher = "REQUEST";
    Object proxy;

    private Request(String namespace) {
      this.namespace = namespace;
    }

    static Request of(String namespace) throws ClassNotFoundException {
      Request handler = new Request(namespace);
      Class<?> type =
          Class.forName(
              namespace + ".servlet.http.HttpServletRequest",
              false,
              SpringWebMvcHelperTest.class.getClassLoader());
      handler.proxy = Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler);
      return handler;
    }

    @Override
    @SuppressWarnings("ReferenceEquality") // a proxy is equal to itself only
    public Object invoke(Object self, Method m, Object[] args) throws Exception {
      String name = m.getName();
      calls.add(name);
      switch (name) {
        case "getAttribute":
          return attributes.get((String) args[0]);
        case "getMethod":
          return method;
        case "getContextPath":
          return contextPath;
        case "getDispatcherType":
          Class<?> type =
              Class.forName(
                  namespace + ".servlet.DispatcherType",
                  false,
                  m.getDeclaringClass().getClassLoader());
          for (Object constant : type.getEnumConstants()) {
            if (((Enum<?>) constant).name().equals(dispatcher)) {
              return constant;
            }
          }
          throw new IllegalArgumentException(dispatcher);
        case "hashCode":
          return System.identityHashCode(self);
        case "equals":
          return self == args[0];
        default:
          throw new UnsupportedOperationException("unexpected request call: " + name);
      }
    }
  }

  @BeforeEach
  void installTracer() {
    tracer =
        new TracerImpl(
            new Dictionary(),
            new TracerImpl.Sink() {
              @Override
              public void accept(Transaction tx) {
                ended.add(tx);
              }
            },
            control,
            new TxIdGenerator(),
            1000,
            100);
    control.installTracer(tracer);
  }

  @AfterEach
  void uninstallTracer() {
    TraceContext leftover = tracer.current();
    if (leftover != null) {
      tracer.end(leftover, 0, null, null, null, System.nanoTime());
    }
    control.installTracer(null);
  }

  private static void nameService(Request request) {
    if (request.namespace.equals("javax")) {
      SpringWebMvcHelperJavax.nameService(request.proxy);
    } else {
      SpringWebMvcHelperJakarta.nameService(request.proxy);
    }
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"javax", "jakarta"})
  @DisplayName("REQUEST dispatch: the best matching pattern fixes the service name")
  void patternNamesService(String namespace) throws Exception {
    TraceContext context = tracer.begin(Kind.HTTP, "GET /api/orders/1", System.nanoTime());
    Request request = Request.of(namespace);
    request.attributes.put(SpringWebMvcHelperCommon.BEST_MATCHING_PATTERN, "/api/orders/{id}");
    nameService(request);
    assertThat(context.serviceName).isEqualTo("GET /api/orders/{id}");
    assertThat(context.serviceFixed).isTrue();
    tracer.end(context, 200, null, null, null, System.nanoTime());
    assertThat(context.serviceName).isEqualTo("GET /api/orders/{id}");
    assertThat(request.calls)
        .doesNotContain("getParameter", "getSession", "getReader", "getInputStream");
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"javax", "jakarta"})
  @Tag("req:D-04")
  @DisplayName("C-48: an ERROR dispatch's /error pattern never names the service; fallback stays")
  void errorDispatchDoesNotName(String namespace) throws Exception {
    TraceContext context = tracer.begin(Kind.HTTP, "GET /api/not-found-x", System.nanoTime());
    Request request = Request.of(namespace);
    // REQUEST dispatch without handler: no pattern.
    nameService(request);
    assertThat(context.serviceFixed).isFalse();
    // Spring Boot's ERROR dispatch to BasicErrorController.
    request.dispatcher = "ERROR";
    request.attributes.put(SpringWebMvcHelperCommon.BEST_MATCHING_PATTERN, "/error");
    nameService(request);
    assertThat(context.serviceFixed).isFalse();
    tracer.end(context, 404, null, null, null, System.nanoTime());
    assertThat(context.serviceName).isEqualTo("GET /api/not-found-x");
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"javax", "jakarta"})
  @DisplayName("FORWARD keeps the forwarding handler's name; the first fixed name wins")
  void forwardKeepsName(String namespace) throws Exception {
    TraceContext context = tracer.begin(Kind.HTTP, "GET /api/forward", System.nanoTime());
    Request request = Request.of(namespace);
    request.attributes.put(SpringWebMvcHelperCommon.BEST_MATCHING_PATTERN, "/api/forward");
    nameService(request);
    request.dispatcher = "FORWARD";
    request.attributes.put(SpringWebMvcHelperCommon.BEST_MATCHING_PATTERN, "/api/orders/{id}");
    nameService(request);
    assertThat(context.serviceName).isEqualTo("GET /api/forward");
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"javax", "jakarta"})
  @Tag("req:F-A36")
  @DisplayName("F-A36: a non-root context path prefixes the name; the real method is used")
  void contextPathAndMethod(String namespace) throws Exception {
    TraceContext context = tracer.begin(Kind.HTTP, "x", System.nanoTime());
    Request request = Request.of(namespace);
    request.contextPath = "/shop-a";
    request.method = "DELETE";
    request.attributes.put(SpringWebMvcHelperCommon.BEST_MATCHING_PATTERN, "/api/orders/{id}");
    nameService(request);
    assertThat(context.serviceName).isEqualTo("/shop-a DELETE /api/orders/{id}");
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"javax", "jakarta"})
  @DisplayName("no transaction or a non-string pattern: nothing happens")
  void noTransaction(String namespace) throws Exception {
    Request request = Request.of(namespace);
    request.attributes.put(SpringWebMvcHelperCommon.BEST_MATCHING_PATTERN, "/x");
    nameService(request);
    TraceContext context = tracer.begin(Kind.HTTP, "GET /y", System.nanoTime());
    request.attributes.put(SpringWebMvcHelperCommon.BEST_MATCHING_PATTERN, Arrays.asList("/z"));
    nameService(request);
    assertThat(context.serviceFixed).isFalse();
    SpringWebMvcHelperJakarta.nameService(new Object());
    SpringWebMvcHelperJavax.nameService(null);
    assertThat(context.serviceName).isEqualTo("GET /y");
  }
}
