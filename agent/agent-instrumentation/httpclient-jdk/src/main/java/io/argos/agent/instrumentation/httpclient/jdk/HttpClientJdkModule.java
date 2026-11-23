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
 * Outgoing calls through {@code java.net.HttpURLConnection} (10 §6.5, F-A05, F-A06, F-A32): the
 * state machine of 10 §6.5 over {@code connect()}, {@code getOutputStream()}, {@code
 * getInputStream()} and {@code getErrorStream()} of {@code
 * sun.net.www.protocol.http.HttpURLConnection} (java.base; HTTPS connections delegate to a subclass
 * of it, which inherits these methods). {@code getResponseCode()} is not declared by that class:
 * {@code java.net.HttpURLConnection.getResponseCode()} calls {@code getInputStream()}, which is the
 * exchange point.
 *
 * <p>The class is on the JDK allow-list of the core; when the application uses it before the agent
 * starts it is retransformed at premain, and the core adds the read edge {@code java.base} → Argos
 * bootstrap module (JPMS, 10 §4.4) before the class is (re)defined. The advice runs in the
 * bootstrap class loader, so the module has no helper and uses the bootstrap API only (10 §5.4).
 * Matching is by name (10 §5.1 "named() first"); the HTTPS delegate needs no own rule.
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
            transformer.applyAdvice(statePoints(), HttpUrlConnectionAdvice.class.getName());
          }

          @Override
          public String toString() {
            return "httpclient-jdk("
                + CONNECTION_CLASS
                + ".connect/getOutputStream/getInputStream/getErrorStream)";
          }
        });
  }

  /**
   * The methods of the state machine (10 §6.5): {@code connect()}, {@code getOutputStream()},
   * {@code getInputStream()}, {@code getErrorStream()}, all without arguments.
   */
  static ElementMatcher.Junction<MethodDescription> statePoints() {
    return named("connect")
        .or(named("getOutputStream"))
        .or(named("getInputStream"))
        .or(named("getErrorStream"))
        .and(takesArguments(0));
  }

  @Override
  public String[] libraryVersionRange() {
    return new String[] {"java.net.HttpURLConnection (JDK 8-25)"};
  }
}
