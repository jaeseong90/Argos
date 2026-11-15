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
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.method.support.InvocableHandlerMethod;

/**
 * Spring MVC naming (10 §6.2, F-A02, F-A29, F-A36, ADR-0004 C-48) of both namespace helpers, with
 * the real tracer (the end applies the F-A29 fallback and the cap). Every case also checks D-26:
 * the helpers read the dispatcher type, method, context path and one attribute, nothing else.
 *
 * <p>Test IDs (C-122, partial checks of the integration IDs): T-AG-020b pattern names, T-AG-020c
 * the handler-method fallback and its exit retry, T-AG-020d no pattern read off the REQUEST
 * dispatch (C-48), T-AG-015a the context path prefix (F-A36).
 */
@Tag("req:F-A02")
@Tag("req:D-26")
class SpringWebMvcHelperTest {
  /** The request methods a helper may call (D-26: no parameters, body or session). */
  private static final List<String> ALLOWED_CALLS =
      Arrays.asList(
          "getAttribute", "getMethod", "getContextPath", "getDispatcherType", "hashCode", "equals");

  private final List<Transaction> ended = new ArrayList<Transaction>();
  private final AgentControl control = TestControls.newControl();
  private TracerImpl tracer;

  /** A request proxy of one namespace that records every call. */
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

    /** D-26: only the allowed calls happened. */
    void assertConsumesNothing() {
      assertThat(calls).as("request calls of the helper").isSubsetOf(ALLOWED_CALLS);
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

  /** The enter of handle(request, response, handler) as the advice runs it. */
  private static TraceContext enter(Request request, Object handler) {
    if (request.namespace.equals("javax")) {
      return SpringWebMvcHelperJavax.nameService(request.proxy, handler);
    }
    return SpringWebMvcHelperJakarta.nameService(request.proxy, handler);
  }

  /** The exit of the same call: only with an enter value (C-24). */
  private static void exit(TraceContext enterValue, Request request) {
    if (enterValue == null) {
      return;
    }
    if (request.namespace.equals("javax")) {
      SpringWebMvcHelperJavax.retry(enterValue, request.proxy);
    } else {
      SpringWebMvcHelperJakarta.retry(enterValue, request.proxy);
    }
  }

  private static HandlerMethod orderGet() {
    return new HandlerMethod("io.argos.sample.web.OrderController", "get", "Long");
  }

  @ParameterizedTest(name = "T-AG-020b the best matching pattern fixes the service name ({0})")
  @ValueSource(strings = {"javax", "jakarta"})
  void patternNamesService(String namespace) throws Exception {
    TraceContext context = tracer.begin(Kind.HTTP, "GET /api/orders/1", System.nanoTime());
    Request request = Request.of(namespace);
    request.attributes.put(SpringWebMvcHelperCommon.BEST_MATCHING_PATTERN, "/api/orders/{id}");
    assertThat(enter(request, orderGet())).as("no exit retry after a pattern").isNull();
    assertThat(context.serviceName).isEqualTo("GET /api/orders/{id}");
    assertThat(context.serviceFixed).isTrue();
    tracer.end(context, 200, null, null, null, System.nanoTime());
    assertThat(context.serviceName).isEqualTo("GET /api/orders/{id}");
    request.assertConsumesNothing();
  }

  @ParameterizedTest(
      name = "T-AG-020b a mapping without a method restriction takes the real request method ({0})")
  @ValueSource(strings = {"javax", "jakarta"})
  void realRequestMethod(String namespace) throws Exception {
    for (String method : Arrays.asList("POST", "PUT", "PATCH", "DELETE")) {
      TraceContext context = tracer.begin(Kind.HTTP, method + " /api/any", System.nanoTime());
      Request request = Request.of(namespace);
      request.method = method;
      request.attributes.put(SpringWebMvcHelperCommon.BEST_MATCHING_PATTERN, "/api/any/**");
      enter(request, null);
      tracer.end(context, 200, null, null, null, System.nanoTime());
      assertThat(context.serviceName).isEqualTo(method + " /api/any/**");
      request.assertConsumesNothing();
    }
  }

  @ParameterizedTest(
      name = "T-AG-020b FORWARD keeps the fixed name of the forwarding handler ({0})")
  @ValueSource(strings = {"javax", "jakarta"})
  void forwardKeepsName(String namespace) throws Exception {
    TraceContext context = tracer.begin(Kind.HTTP, "GET /api/forward", System.nanoTime());
    Request request = Request.of(namespace);
    request.attributes.put(SpringWebMvcHelperCommon.BEST_MATCHING_PATTERN, "/api/forward");
    enter(request, orderGet());
    request.dispatcher = "FORWARD";
    request.attributes.put(SpringWebMvcHelperCommon.BEST_MATCHING_PATTERN, "/api/orders/{id}");
    assertThat(enter(request, orderGet())).isNull();
    tracer.end(context, 200, null, null, null, System.nanoTime());
    assertThat(context.serviceName).isEqualTo("GET /api/forward");
    request.assertConsumesNothing();
  }

  @ParameterizedTest(
      name =
          "T-AG-020d {0}: the /error pattern of the ERROR dispatch never names the service; the F-A29"
              + " fallback stays (C-48)")
  @ValueSource(strings = {"javax", "jakarta"})
  @Tag("req:D-04")
  void errorDispatchDoesNotName(String namespace) throws Exception {
    TraceContext context = tracer.begin(Kind.HTTP, "GET /api/not-found/12345", System.nanoTime());
    Request request = Request.of(namespace);
    // REQUEST dispatch without handler: no pattern, no handler method.
    assertThat(enter(request, null)).isNull();
    assertThat(context.serviceFixed).isFalse();
    // Spring Boot's ERROR dispatch (or ErrorPageFilter's FORWARD in a WAR) to BasicErrorController.
    for (String dispatcher : Arrays.asList("ERROR", "FORWARD", "INCLUDE", "ASYNC")) {
      request.dispatcher = dispatcher;
      request.attributes.put(SpringWebMvcHelperCommon.BEST_MATCHING_PATTERN, "/error");
      assertThat(enter(request, orderGet())).as(dispatcher).isNull();
      assertThat(context.serviceFixed).as(dispatcher).isFalse();
      assertThat(context.serviceName).as(dispatcher).isEqualTo("GET /api/not-found/12345");
    }
    tracer.end(context, 404, null, null, null, System.nanoTime());
    assertThat(context.serviceName).isEqualTo("GET /api/not-found/{*}");
    request.assertConsumesNothing();
  }

  @ParameterizedTest(
      name = "T-AG-015a {0}: a non-root context path prefixes pattern and handler-method names")
  @ValueSource(strings = {"javax", "jakarta"})
  @Tag("req:F-A36")
  void contextPathPrefix(String namespace) throws Exception {
    TraceContext context = tracer.begin(Kind.HTTP, "x", System.nanoTime());
    Request request = Request.of(namespace);
    request.contextPath = "/shop-a";
    request.method = "DELETE";
    request.attributes.put(SpringWebMvcHelperCommon.BEST_MATCHING_PATTERN, "/api/orders/{id}");
    enter(request, null);
    assertThat(context.serviceName).isEqualTo("/shop-a DELETE /api/orders/{id}");
    tracer.end(context, 200, null, null, null, System.nanoTime());

    TraceContext other = tracer.begin(Kind.HTTP, "/shop-b GET /x/1", System.nanoTime());
    Request second = Request.of(namespace);
    second.contextPath = "/shop-b";
    enter(second, orderGet());
    tracer.end(other, 200, null, null, null, System.nanoTime());
    assertThat(other.serviceName).isEqualTo("/shop-b OrderController#get");
    request.assertConsumesNothing();
    second.assertConsumesNothing();
  }

  @ParameterizedTest(
      name =
          "T-AG-020c no pattern and a HandlerMethod: Controller#method, not fixed, kept at the end"
              + " ({0})")
  @ValueSource(strings = {"javax", "jakarta"})
  @Tag("req:F-A29")
  void handlerMethodFallback(String namespace) throws Exception {
    TraceContext context = tracer.begin(Kind.HTTP, "GET /legacy/42", System.nanoTime());
    Request request = Request.of(namespace);
    TraceContext retry = enter(request, orderGet());
    assertThat(retry).as("the exit retries with the pattern").isSameAs(context);
    assertThat(context.serviceName).isEqualTo("OrderController#get");
    assertThat(context.serviceFixed).isFalse();
    exit(retry, request); // still no pattern: nothing changes
    assertThat(context.serviceFixed).isFalse();
    tracer.end(context, 200, null, null, null, System.nanoTime());
    assertThat(context.serviceName).isEqualTo("OrderController#get");
    request.assertConsumesNothing();
  }

  @ParameterizedTest(
      name = "T-AG-020c a pattern that appears during handle fixes the name at the exit ({0})")
  @ValueSource(strings = {"javax", "jakarta"})
  void exitRetryFixesPattern(String namespace) throws Exception {
    TraceContext context = tracer.begin(Kind.HTTP, "GET /legacy/42", System.nanoTime());
    Request request = Request.of(namespace);
    TraceContext retry = enter(request, new InvocableHandlerMethod("a.b.Legacy$Inner", "show"));
    assertThat(context.serviceName).isEqualTo("Legacy$Inner#show");
    request.attributes.put(SpringWebMvcHelperCommon.BEST_MATCHING_PATTERN, "/legacy/{id}");
    exit(retry, request);
    assertThat(context.serviceName).isEqualTo("GET /legacy/{id}");
    assertThat(context.serviceFixed).isTrue();
    tracer.end(context, 200, null, null, null, System.nanoTime());
    assertThat(context.serviceName).isEqualTo("GET /legacy/{id}");
    request.assertConsumesNothing();
  }

  @ParameterizedTest(
      name =
          "T-AG-020c other handlers (HttpRequestHandler, Controller, a String) name nothing; the"
              + " F-A29 fallback applies ({0})")
  @ValueSource(strings = {"javax", "jakarta"})
  void otherHandlers(String namespace) throws Exception {
    for (Object handler : Arrays.asList(new Object(), "handler", Integer.valueOf(1))) {
      TraceContext context = tracer.begin(Kind.HTTP, "GET /static/42", System.nanoTime());
      Request request = Request.of(namespace);
      assertThat(enter(request, handler)).isNull();
      tracer.end(context, 200, null, null, null, System.nanoTime());
      assertThat(context.serviceName).isEqualTo("GET /static/{*}");
      request.assertConsumesNothing();
    }
  }

  @ParameterizedTest(
      name =
          "T-AG-020b no transaction, a fixed name or a non-string pattern: nothing happens, no"
              + " attribute read ({0})")
  @ValueSource(strings = {"javax", "jakarta"})
  void noTransaction(String namespace) throws Exception {
    Request request = Request.of(namespace);
    request.attributes.put(SpringWebMvcHelperCommon.BEST_MATCHING_PATTERN, "/x");
    assertThat(enter(request, orderGet())).isNull();
    assertThat(request.calls).as("no transaction: the request is not read").isEmpty();

    TraceContext context = tracer.begin(Kind.HTTP, "GET /y", System.nanoTime());
    request.attributes.put(SpringWebMvcHelperCommon.BEST_MATCHING_PATTERN, Arrays.asList("/z"));
    assertThat(enter(request, null)).isNull();
    assertThat(context.serviceFixed).isFalse();
    assertThat(SpringWebMvcHelperJakarta.nameService(new Object(), null)).isNull();
    assertThat(SpringWebMvcHelperJavax.nameService(null, null)).isNull();
    assertThat(context.serviceName).isEqualTo("GET /y");

    tracer.setServiceName(context, "BATCH fixed", true);
    request.calls.clear();
    request.attributes.put(SpringWebMvcHelperCommon.BEST_MATCHING_PATTERN, "/w");
    assertThat(enter(request, orderGet())).isNull();
    assertThat(request.calls).as("a fixed name: the request is not read").isEmpty();
    assertThat(context.serviceName).isEqualTo("BATCH fixed");
    request.assertConsumesNothing();
  }

  @ParameterizedTest(name = "T-AG-020c handler-method names of [{0}]")
  @ValueSource(
      strings = {
        "com.example.OrderController#get(Long)",
        "OrderController#get()",
        "com.example.Outer$Inner#list(String, int)",
        "#broken(Long)",
        "com.example.Broken#(Long)",
        "no-hash"
      })
  void handlerMethodNames(String description) {
    final String text = description;
    Object handler =
        new HandlerMethod("unused", "unused") {
          @Override
          public String toString() {
            return text;
          }
        };
    String name = SpringWebMvcHelperCommon.handlerMethodName("", handler);
    switch (description) {
      case "com.example.OrderController#get(Long)":
        assertThat(name).isEqualTo("OrderController#get");
        break;
      case "OrderController#get()":
        assertThat(name).isEqualTo("OrderController#get");
        break;
      case "com.example.Outer$Inner#list(String, int)":
        assertThat(name).isEqualTo("Outer$Inner#list");
        break;
      default:
        assertThat(name).as(description).isNull();
    }
    assertThat(SpringWebMvcHelperCommon.handlerMethodName("/ctx", new HandlerMethod("a.B", "c")))
        .isEqualTo("/ctx B#c");
    assertThat(SpringWebMvcHelperCommon.handlerMethodName("", null)).isNull();
    assertThat(SpringWebMvcHelperCommon.serviceName("", null, "/x")).isNull();
    assertThat(SpringWebMvcHelperCommon.serviceName("", "GET", "")).isNull();
  }
}
