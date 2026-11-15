/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.servletasync;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.bootstrap.AgentControl;
import io.argos.agent.bootstrap.ContextStore;
import io.argos.agent.bootstrap.Kind;
import io.argos.agent.bootstrap.TestControls;
import io.argos.agent.bootstrap.TraceContext;
import io.argos.agent.core.trace.Dictionary;
import io.argos.agent.core.trace.TracerImpl;
import io.argos.agent.core.trace.Transaction;
import io.argos.agent.core.trace.TxIdGenerator;
import io.argos.agent.instrumentation.api.HelperClasses;
import io.argos.agent.instrumentation.api.ModuleIds;
import io.argos.agent.instrumentation.api.TypeInstrumentation;
import io.argos.agent.instrumentation.api.TypeTransformer;
import java.lang.ref.WeakReference;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDefinition;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * ServletAsyncModule: identity, the Tomcat names it matches (never by subtype, 10 §5.1, C-127), and
 * its helpers against the real tracer: {@code startAsync} puts the anchored transaction into async
 * mode and maps the container's response; {@code AsyncContext.start} attaches the transaction to
 * the task without wrapping it.
 */
@Tag("req:F-A34")
class ServletAsyncModuleTest {
  private final ServletAsyncModule module = new ServletAsyncModule();
  private final AgentControl control = TestControls.newControl();
  private TracerImpl tracer;

  @BeforeEach
  void installTracer() {
    tracer =
        new TracerImpl(
            new Dictionary(),
            new TracerImpl.Sink() {
              @Override
              public void accept(Transaction tx) {}
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

  /** Records the advice of the matched type's methods. */
  static final class Recorder implements TypeTransformer {
    final List<ElementMatcher<? super MethodDescription>> methods =
        new ArrayList<ElementMatcher<? super MethodDescription>>();
    final List<String> advice = new ArrayList<String>();

    @Override
    public void applyAdvice(
        ElementMatcher<? super MethodDescription> methodMatcher, String adviceClassName) {
      methods.add(methodMatcher);
      advice.add(adviceClassName);
    }
  }

  /** Shape of Tomcat 9's {@code Request} (only the names matter). */
  abstract static class JavaxRequestShape {
    public abstract javax.servlet.AsyncContext startAsync();

    public abstract javax.servlet.AsyncContext startAsync(
        javax.servlet.ServletRequest request, javax.servlet.ServletResponse response);
  }

  /** Shape of Tomcat 10.1's {@code Request}. */
  abstract static class JakartaRequestShape {
    public abstract jakarta.servlet.AsyncContext startAsync(
        jakarta.servlet.ServletRequest request, jakarta.servlet.ServletResponse response);
  }

  /** Shape of Tomcat's {@code AsyncContextImpl}. */
  abstract static class AsyncContextShape {
    public abstract void start(Runnable run);

    public abstract void complete();
  }

  @Test
  @DisplayName("T-AG-016a identity, helper list, version range, Tomcat witness")
  void identity() {
    assertThat(module.id()).isEqualTo("servlet-async");
    assertThat(module.moduleId()).isEqualTo(ModuleIds.SERVLET_ASYNC);
    assertThat(module.helperClassNames())
        .containsExactly(
            AsyncHelperCommon.class.getName(),
            AsyncHelperJavax.class.getName(),
            AsyncHelperJakarta.class.getName());
    assertThat(HelperClasses.problems(module)).isEmpty();
    assertThat(module.libraryVersionRange()).isNotEmpty();
    // The test class path has no Tomcat: the witness does not match.
    assertThat(module.witness().matches(ServletAsyncModuleTest.class.getClassLoader())).isFalse();
  }

  @Test
  @DisplayName(
      "T-AG-016a C-127: Request.startAsync(req, resp) per namespace and AsyncContextImpl.start"
          + " by name; the no-argument startAsync and other classes are not matched")
  void matchers() {
    List<TypeInstrumentation> types = module.types();
    assertThat(types).hasSize(3);
    TypeDescription request = latent(ServletAsyncModule.TOMCAT_REQUEST);
    TypeDescription asyncContext = latent(ServletAsyncModule.TOMCAT_ASYNC_CONTEXT);
    assertThat(types.get(0).typeMatcher().matches(request)).isTrue();
    assertThat(types.get(1).typeMatcher().matches(request)).isTrue();
    assertThat(types.get(2).typeMatcher().matches(asyncContext)).isTrue();
    for (TypeInstrumentation type : types) {
      assertThat(
              type.typeMatcher()
                  .matches(
                      TypeDescription.ForLoadedType.of(javax.servlet.ServletRequestWrapper.class)))
          .isFalse();
    }
    assertThat(matched(types.get(0), JavaxRequestShape.class)).containsExactly("startAsync2");
    assertThat(matched(types.get(1), JavaxRequestShape.class)).isEmpty();
    assertThat(matched(types.get(1), JakartaRequestShape.class)).containsExactly("startAsync2");
    assertThat(matched(types.get(2), AsyncContextShape.class)).containsExactly("start1");
    Recorder recorder = new Recorder();
    types.get(0).transform(recorder);
    assertThat(recorder.advice).containsExactly(JavaxStartAsyncAdvice.class.getName());
  }

  @Test
  @DisplayName(
      "T-AG-016a startAsync exit: the anchored transaction enters async mode, keeps the status and"
          + " the innermost response maps to it; without an anchor nothing happens")
  void startedMarksAsync() {
    TraceContext context = tracer.begin(Kind.HTTP, "GET /sse", System.nanoTime());
    Map<String, Object> attributes = new HashMap<String, Object>();
    attributes.put(AsyncHelperCommon.TX_ATTRIBUTE, context);
    Object request = request(javax.servlet.http.HttpServletRequest.class, attributes);
    Object facade = response(javax.servlet.http.HttpServletResponse.class, 200);
    javax.servlet.http.HttpServletResponseWrapper wrapper =
        new javax.servlet.http.HttpServletResponseWrapper(
            (javax.servlet.http.HttpServletResponse) facade);
    AsyncHelperJavax.started(request, wrapper);
    assertThat(context.asyncStarted).isTrue();
    assertThat(context.status).isEqualTo(200);
    ContextStore<Object, TraceContext> responses =
        ContextStore.of(Object.class, TraceContext.class);
    assertThat(responses.get(facade)).isSameAs(context);
    assertThat(responses.get(wrapper)).isNull();
    responses.remove(facade);

    TraceContext other = tracer.begin(Kind.HTTP, "GET /x", System.nanoTime());
    Object anonymous =
        request(jakarta.servlet.http.HttpServletRequest.class, new HashMap<String, Object>());
    AsyncHelperJakarta.started(
        anonymous, response(jakarta.servlet.http.HttpServletResponse.class, 200));
    assertThat(other.asyncStarted).isFalse();
    Map<String, Object> jakartaAttributes = new HashMap<String, Object>();
    jakartaAttributes.put(AsyncHelperCommon.TX_ATTRIBUTE, other);
    Object jakartaResponse = response(jakarta.servlet.http.HttpServletResponse.class, 201);
    AsyncHelperJakarta.started(
        request(jakarta.servlet.http.HttpServletRequest.class, jakartaAttributes), jakartaResponse);
    assertThat(other.asyncStarted).isTrue();
    assertThat(other.status).isEqualTo(201);
    responses.remove(jakartaResponse);
    tracer.end(other, 200, null, null, null, System.nanoTime());
    tracer.end(context, 200, null, null, null, System.nanoTime());
    AsyncHelperJavax.started(request, wrapper);
    assertThat(responses.get(facade)).as("an ended transaction is not mapped").isNull();
  }

  @Test
  @SuppressWarnings({"rawtypes", "unchecked"})
  @DisplayName(
      "T-AG-016a startAsync exit: the stream the servlet module recorded on the transaction"
          + " (opened before startAsync) is linked to the innermost response, once; without a"
          + " recorded stream no link is made")
  void startedLinksRecordedStream() {
    ContextStore<Object, TraceContext> responses =
        ContextStore.of(Object.class, TraceContext.class);
    ContextStore<Object, WeakReference<Object>> links =
        (ContextStore) ContextStore.of(Object.class, WeakReference.class);
    TraceContext context = tracer.begin(Kind.HTTP, "GET /stream", System.nanoTime());
    Object stream = new Object();
    context.responseStream = new WeakReference<Object>(stream);
    Map<String, Object> attributes = new HashMap<String, Object>();
    attributes.put(AsyncHelperCommon.TX_ATTRIBUTE, context);
    Object request = request(jakarta.servlet.http.HttpServletRequest.class, attributes);
    Object facade = response(jakarta.servlet.http.HttpServletResponse.class, 200);
    jakarta.servlet.http.HttpServletResponseWrapper wrapper =
        new jakarta.servlet.http.HttpServletResponseWrapper(
            (jakarta.servlet.http.HttpServletResponse) facade);
    long before = ContextStore.totalEntries();
    AsyncHelperJakarta.started(request, wrapper);
    assertThat(responses.get(facade)).isSameAs(context);
    assertThat(links.get(stream)).isNotNull();
    assertThat(links.get(stream).get()).as("linked to the innermost response").isSameAs(facade);
    WeakReference<Object> first = links.get(stream);
    AsyncHelperJakarta.started(request, wrapper);
    assertThat(links.get(stream)).as("an existing link is kept").isSameAs(first);
    assertThat(ContextStore.totalEntries() - before).as("one mapping, one link").isEqualTo(2);
    responses.remove(facade);
    links.remove(stream);
    tracer.end(context, 200, null, null, null, System.nanoTime());

    TraceContext plain = tracer.begin(Kind.HTTP, "GET /plain", System.nanoTime());
    Map<String, Object> plainAttributes = new HashMap<String, Object>();
    plainAttributes.put(AsyncHelperCommon.TX_ATTRIBUTE, plain);
    Object plainFacade = response(javax.servlet.http.HttpServletResponse.class, 200);
    long beforePlain = ContextStore.totalEntries();
    AsyncHelperJavax.started(
        request(javax.servlet.http.HttpServletRequest.class, plainAttributes), plainFacade);
    assertThat(ContextStore.totalEntries() - beforePlain).as("the mapping only").isEqualTo(1);
    responses.remove(plainFacade);
    tracer.end(plain, 200, null, null, null, System.nanoTime());
  }

  @Test
  @DisplayName(
      "T-AG-016a AsyncContext.start(task): the task carries the current transaction"
          + " (ContextStore<Runnable, TraceContext>), never wrapped; no transaction, no entry")
  void startPropagates() {
    Runnable task =
        new Runnable() {
          @Override
          public void run() {}
        };
    AsyncHelperCommon.propagate(task);
    ContextStore<Runnable, TraceContext> tasks =
        ContextStore.of(Runnable.class, TraceContext.class);
    assertThat(tasks.get(task)).isNull();
    TraceContext context = tracer.begin(Kind.HTTP, "GET /async", System.nanoTime());
    AsyncHelperCommon.propagate(task);
    assertThat(tasks.get(task)).isSameAs(context);
    AsyncHelperCommon.propagate(null);
    tasks.remove(task);
    tracer.end(context, 200, null, null, null, System.nanoTime());
  }

  private static TypeDescription latent(String name) {
    return new TypeDescription.Latent(
        name, java.lang.reflect.Modifier.PUBLIC, TypeDefinition.Sort.describe(Object.class));
  }

  private static List<String> matched(TypeInstrumentation type, Class<?> shape) {
    Recorder recorder = new Recorder();
    type.transform(recorder);
    List<String> names = new ArrayList<String>();
    for (MethodDescription method : TypeDescription.ForLoadedType.of(shape).getDeclaredMethods()) {
      for (ElementMatcher<? super MethodDescription> matcher : recorder.methods) {
        if (matcher.matches(method)) {
          names.add(method.getName() + method.getParameters().size());
        }
      }
    }
    return names;
  }

  private static Object request(Class<?> type, final Map<String, Object> attributes) {
    return Proxy.newProxyInstance(
        ServletAsyncModuleTest.class.getClassLoader(),
        new Class<?>[] {type},
        new InvocationHandler() {
          @Override
          @SuppressWarnings("ReferenceEquality")
          public Object invoke(Object self, Method m, Object[] args) {
            switch (m.getName()) {
              case "getAttribute":
                return attributes.get((String) args[0]);
              case "hashCode":
                return System.identityHashCode(self);
              case "equals":
                return self == args[0];
              default:
                throw new UnsupportedOperationException("unexpected request call: " + m.getName());
            }
          }
        });
  }

  private static Object response(Class<?> type, final int status) {
    return Proxy.newProxyInstance(
        ServletAsyncModuleTest.class.getClassLoader(),
        new Class<?>[] {type},
        new InvocationHandler() {
          @Override
          @SuppressWarnings("ReferenceEquality")
          public Object invoke(Object self, Method m, Object[] args) {
            switch (m.getName()) {
              case "getStatus":
                return status;
              case "hashCode":
                return System.identityHashCode(self);
              case "equals":
                return self == args[0];
              default:
                throw new UnsupportedOperationException("unexpected response call: " + m.getName());
            }
          }
        });
  }
}
