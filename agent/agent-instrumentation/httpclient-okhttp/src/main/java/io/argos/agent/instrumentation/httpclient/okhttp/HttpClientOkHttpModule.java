/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.httpclient.okhttp;

import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.namedOneOf;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import io.argos.agent.instrumentation.api.HelperClasses;
import io.argos.agent.instrumentation.api.InstrumentationModule;
import io.argos.agent.instrumentation.api.ModuleIds;
import io.argos.agent.instrumentation.api.TypeInstrumentation;
import io.argos.agent.instrumentation.api.TypeTransformer;
import io.argos.agent.instrumentation.api.Witness;
import java.util.Arrays;
import java.util.List;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

/**
 * Outgoing calls through OkHttp 3 and 4 (10 §6.5, F-A05, F-A06, F-A32): one HTTP step per call,
 * recorded by an application interceptor of the agent ({@link OkHttpInterceptorHelper}, a helper
 * that implements {@code okhttp3.Interceptor}) at the front of every client's interceptor list.
 *
 * <ul>
 *   <li>{@code OkHttpClient$Builder}: the interceptor is added at index 0 at the exit of every
 *       constructor and again, if missing, at the entry of {@code build()} (never twice). The
 *       constructors are needed besides {@code build()} (10 §6.5 names {@code build()} only):
 *       {@code new OkHttpClient()} builds from {@code new Builder()} without calling {@code
 *       build()}, and Spring's {@code OkHttp3ClientHttpRequestFactory()} creates its client that
 *       way.
 *   <li>{@code RealCall} constructor exit ({@code okhttp3.RealCall} in 3.x, {@code
 *       okhttp3.internal.connection.RealCall} in 4.x): the creating thread's transaction is
 *       attached to the {@code Call} ({@code ContextStore<Call, TraceContext>}), so that an {@code
 *       enqueue}d call, whose interceptors run on a dispatcher thread, is recorded in the
 *       transaction that created it (10 §18 U-11).
 * </ul>
 *
 * <p>The interceptor sees the call once, before retries and redirects (so the step carries the
 * final status), and adds the propagation headers through {@code Request.newBuilder().header(..)},
 * the public API interceptors use; it never changes the response or the exception.
 */
public final class HttpClientOkHttpModule implements InstrumentationModule {
  public static final int MODULE_ID = ModuleIds.HTTPCLIENT_OKHTTP;

  static final String BUILDER_CLASS = "okhttp3.OkHttpClient$Builder";

  /** The {@code Call} implementation of OkHttp 3.x and of OkHttp 4.x. */
  static final String[] REAL_CALL_CLASSES = {
    "okhttp3.RealCall", "okhttp3.internal.connection.RealCall"
  };

  @Override
  public String id() {
    return "httpclient-okhttp";
  }

  @Override
  public int moduleId() {
    return MODULE_ID;
  }

  @Override
  public ElementMatcher<ClassLoader> witness() {
    return Witness.hasClassesNamed(
        "okhttp3.OkHttpClient", BUILDER_CLASS, "okhttp3.Interceptor", "okhttp3.Call");
  }

  @Override
  public List<TypeInstrumentation> types() {
    return Arrays.<TypeInstrumentation>asList(
        new TypeInstrumentation() {
          @Override
          public ElementMatcher<TypeDescription> typeMatcher() {
            return named(BUILDER_CLASS);
          }

          @Override
          public void transform(TypeTransformer transformer) {
            transformer.applyAdvice(
                isConstructor(), OkHttpBuilderConstructorAdvice.class.getName());
            transformer.applyAdvice(build(), OkHttpBuilderBuildAdvice.class.getName());
          }

          @Override
          public String toString() {
            return "httpclient-okhttp(OkHttpClient$Builder)";
          }
        },
        new TypeInstrumentation() {
          @Override
          public ElementMatcher<TypeDescription> typeMatcher() {
            return namedOneOf(REAL_CALL_CLASSES);
          }

          @Override
          public void transform(TypeTransformer transformer) {
            transformer.applyAdvice(isConstructor(), OkHttpRealCallAdvice.class.getName());
          }

          @Override
          public String toString() {
            return "httpclient-okhttp(RealCall.<init>)";
          }
        });
  }

  /** {@code OkHttpClient.Builder.build()}. */
  static ElementMatcher.Junction<MethodDescription> build() {
    return named("build").and(takesArguments(0));
  }

  @Override
  public List<String> helperClassNames() {
    return HelperClasses.of(HttpClientOkHttpModule.class, "OkHttpInterceptorHelper");
  }

  @Override
  public String[] libraryVersionRange() {
    return new String[] {"com.squareup.okhttp3:okhttp 3.10-4.12 (tested 3.14.9, 4.12.0)"};
  }
}
