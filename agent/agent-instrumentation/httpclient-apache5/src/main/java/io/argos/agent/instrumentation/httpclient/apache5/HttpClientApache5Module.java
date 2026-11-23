/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.httpclient.apache5;

import static net.bytebuddy.matcher.ElementMatchers.isAbstract;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.namedOneOf;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import io.argos.agent.instrumentation.api.HelperClasses;
import io.argos.agent.instrumentation.api.InstrumentationModule;
import io.argos.agent.instrumentation.api.ModuleIds;
import io.argos.agent.instrumentation.api.TypeInstrumentation;
import io.argos.agent.instrumentation.api.TypeTransformer;
import io.argos.agent.instrumentation.api.Witness;
import java.util.Collections;
import java.util.List;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

/**
 * Outgoing calls through the classic (blocking) Apache HttpClient 5 (10 §6.5, F-A05, F-A06, F-A32):
 * one HTTP step per call of the single gate {@code CloseableHttpClient.doExecute(HttpHost,
 * ClassicHttpRequest, HttpContext)}, which every {@code execute(..)}/{@code executeOpen(..)}
 * overload (response handlers included) and Spring's {@code HttpComponentsClientHttpRequestFactory}
 * reach once. Redirects and retries run inside it, so the step carries the final status. The async
 * client is not instrumented (1.x).
 *
 * <p>Named matching only (10 §5.1): {@code doExecute} is abstract in {@code CloseableHttpClient},
 * so the advice goes into the two classic implementations by name ({@code InternalHttpClient} of
 * {@code HttpClients}/{@code HttpClientBuilder}, {@code MinimalHttpClient}); a decorator that
 * delegates to another client is counted once ({@code CallDepth.HTTP_CLIENT}). Headers go through
 * the request's public {@code setHeader} (no argument replacement, no {@code readOnly=false}).
 */
public final class HttpClientApache5Module implements InstrumentationModule {
  public static final int MODULE_ID = ModuleIds.HTTPCLIENT_APACHE5;

  /** The library's implementations of the classic {@code CloseableHttpClient.doExecute}. */
  static final String[] CLIENT_CLASSES = {
    "org.apache.hc.client5.http.impl.classic.InternalHttpClient",
    "org.apache.hc.client5.http.impl.classic.MinimalHttpClient"
  };

  @Override
  public String id() {
    return "httpclient-apache5";
  }

  @Override
  public int moduleId() {
    return MODULE_ID;
  }

  @Override
  public ElementMatcher<ClassLoader> witness() {
    return Witness.hasClassesNamed(
        "org.apache.hc.client5.http.impl.classic.CloseableHttpClient",
        "org.apache.hc.core5.http.ClassicHttpRequest",
        "org.apache.hc.core5.http.HttpHost");
  }

  @Override
  public List<TypeInstrumentation> types() {
    return Collections.<TypeInstrumentation>singletonList(
        new TypeInstrumentation() {
          @Override
          public ElementMatcher<TypeDescription> typeMatcher() {
            return namedOneOf(CLIENT_CLASSES);
          }

          @Override
          public void transform(TypeTransformer transformer) {
            transformer.applyAdvice(doExecute(), Apache5ExecuteAdvice.class.getName());
          }

          @Override
          public String toString() {
            return "httpclient-apache5(classic doExecute)";
          }
        });
  }

  /** {@code doExecute(HttpHost, ClassicHttpRequest, HttpContext)} with a body. */
  static ElementMatcher.Junction<MethodDescription> doExecute() {
    return named("doExecute")
        .and(takesArguments(3))
        .and(takesArgument(0, named("org.apache.hc.core5.http.HttpHost")))
        .and(takesArgument(1, named("org.apache.hc.core5.http.ClassicHttpRequest")))
        .and(takesArgument(2, named("org.apache.hc.core5.http.protocol.HttpContext")))
        .and(not(isAbstract()));
  }

  @Override
  public List<String> helperClassNames() {
    return HelperClasses.of(HttpClientApache5Module.class, "Apache5Helper");
  }

  @Override
  public String[] libraryVersionRange() {
    return new String[] {
      "org.apache.httpcomponents.client5:httpclient5 5.x classic (tested 5.5.2, 5.6.5)"
    };
  }
}
