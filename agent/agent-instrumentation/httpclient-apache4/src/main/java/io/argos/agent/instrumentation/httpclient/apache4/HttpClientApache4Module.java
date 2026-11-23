/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.httpclient.apache4;

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
 * Outgoing calls through Apache HttpClient 4.5 (10 §6.5, F-A05, F-A06, F-A32): one HTTP step per
 * call of the single gate {@code CloseableHttpClient.doExecute(HttpHost, HttpRequest,
 * HttpContext)}, which every {@code execute(..)} overload (response handlers included) and Spring's
 * {@code HttpComponentsClientHttpRequestFactory} reach once. Redirects and retries run inside it,
 * so the step carries the final status.
 *
 * <p>Named matching only (10 §5.1): {@code doExecute} is abstract in {@code CloseableHttpClient},
 * so the advice goes into the three implementations of the library by name ({@code
 * InternalHttpClient} of {@code HttpClients}/{@code HttpClientBuilder}, {@code MinimalHttpClient},
 * and the deprecated {@code AbstractHttpClient} family such as {@code DefaultHttpClient}). A
 * decorating subclass in an application is not matched; a decorator that delegates to another
 * client is counted once ({@code CallDepth.HTTP_CLIENT}).
 *
 * <p>Headers go through the request's public {@code setHeader} (no argument replacement, no {@code
 * readOnly=false}); the step, the propagation decision and the failure class come from the
 * bootstrap API ({@code outgoingHeaders}, {@code hostId}, {@code httpStart}, {@code httpEnd}).
 */
public final class HttpClientApache4Module implements InstrumentationModule {
  public static final int MODULE_ID = ModuleIds.HTTPCLIENT_APACHE4;

  /** The library's implementations of {@code CloseableHttpClient.doExecute}. */
  static final String[] CLIENT_CLASSES = {
    "org.apache.http.impl.client.InternalHttpClient",
    "org.apache.http.impl.client.MinimalHttpClient",
    "org.apache.http.impl.client.AbstractHttpClient"
  };

  @Override
  public String id() {
    return "httpclient-apache4";
  }

  @Override
  public int moduleId() {
    return MODULE_ID;
  }

  @Override
  public ElementMatcher<ClassLoader> witness() {
    return Witness.hasClassesNamed(
        "org.apache.http.impl.client.CloseableHttpClient",
        "org.apache.http.HttpRequest",
        "org.apache.http.HttpHost");
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
            transformer.applyAdvice(doExecute(), Apache4ExecuteAdvice.class.getName());
          }

          @Override
          public String toString() {
            return "httpclient-apache4(doExecute)";
          }
        });
  }

  /** {@code doExecute(HttpHost, HttpRequest, HttpContext)} with a body. */
  static ElementMatcher.Junction<MethodDescription> doExecute() {
    return named("doExecute")
        .and(takesArguments(3))
        .and(takesArgument(0, named("org.apache.http.HttpHost")))
        .and(takesArgument(1, named("org.apache.http.HttpRequest")))
        .and(takesArgument(2, named("org.apache.http.protocol.HttpContext")))
        .and(not(isAbstract()));
  }

  @Override
  public List<String> helperClassNames() {
    return HelperClasses.of(HttpClientApache4Module.class, "Apache4Helper");
  }

  @Override
  public String[] libraryVersionRange() {
    return new String[] {"org.apache.httpcomponents:httpclient 4.5.x (tested 4.5.14)"};
  }
}
