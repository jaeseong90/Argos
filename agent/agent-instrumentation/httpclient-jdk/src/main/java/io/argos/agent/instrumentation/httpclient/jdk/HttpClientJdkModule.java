/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.httpclient.jdk;

import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import io.argos.agent.instrumentation.api.InstrumentationModule;
import io.argos.agent.instrumentation.api.ModuleIds;
import io.argos.agent.instrumentation.api.TypeInstrumentation;
import io.argos.agent.instrumentation.api.TypeTransformer;
import java.util.Collections;
import java.util.List;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

/**
 * Outgoing calls through {@code java.net.HttpURLConnection} (10 §6.5, F-A05), WO-02 P-6 scope: one
 * point, {@code sun.net.www.protocol.http.HttpURLConnection.getInputStream()} of {@code java.base}
 * (HTTP, and HTTPS through its delegate). The class is on the JDK allow-list of the core; when the
 * application uses it before the agent starts it is retransformed at premain, and the core adds the
 * read edge {@code java.base} → Argos bootstrap module (JPMS, 10 §4.4) before the class is
 * (re)defined.
 *
 * <p>The JDK runs the request/response exchange in the first {@code getInputStream()} call ({@code
 * getResponseCode()} calls it too, and the JDK calls it again from inside itself); the advice
 * records that first call once per connection: an HTTP step (name = host, detail = {@code METHOD
 * host[:port]/path}, query string never kept), {@code traceparent} on a request that is not
 * connected yet and has none (F-A32), status, failure class ({@code FailureKind}) and {@code
 * recordExtStat}. The advice runs in the bootstrap class loader, so it has no helper and uses the
 * bootstrap API only (10 §5.4).
 *
 * <p>Not in the PoC (WO-14): {@code connect()}/{@code getOutputStream()} start points (a POST body
 * written before {@code getInputStream()} is outside the step and gets no header), {@code
 * tracestate}, {@code propagation.exclude-hosts}, URL path masking of the detail.
 */
public final class HttpClientJdkModule implements InstrumentationModule {
  public static final int MODULE_ID = ModuleIds.HTTPCLIENT_JDK;

  /** The JDK implementation class (java.base; HTTPS delegates extend it). */
  static final String CONNECTION_CLASS = "sun.net.www.protocol.http.HttpURLConnection";

  @Override
  public String id() {
    return "httpclient-jdk";
  }

  @Override
  public int moduleId() {
    return MODULE_ID;
  }

  @Override
  public List<TypeInstrumentation> types() {
    return Collections.<TypeInstrumentation>singletonList(
        new TypeInstrumentation() {
          @Override
          public ElementMatcher<TypeDescription> typeMatcher() {
            return named(CONNECTION_CLASS);
          }

          @Override
          public void transform(TypeTransformer transformer) {
            transformer.applyAdvice(getInputStream(), HttpUrlConnectionAdvice.class.getName());
          }

          @Override
          public String toString() {
            return "httpclient-jdk(" + CONNECTION_CLASS + ".getInputStream)";
          }
        });
  }

  static ElementMatcher.Junction<MethodDescription> getInputStream() {
    return named("getInputStream").and(takesArguments(0));
  }

  @Override
  public String[] libraryVersionRange() {
    return new String[] {"java.net.HttpURLConnection (JDK 8-25)"};
  }
}
