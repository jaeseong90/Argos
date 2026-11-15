/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.tomcat;

import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

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
 * Tomcat connectors without JMX (10 §6.4, F-A10, DSN-10-12): registers every started connector
 * endpoint with the core's Tomcat registry ({@code Tracer.registerConnector(endpoint)}) at the exit
 * of {@code org.apache.tomcat.util.net.AbstractEndpoint.start()} (embedded Tomcat of Spring Boot
 * and standalone Tomcat; NIO, NIO2 and APR endpoints all inherit the final {@code start()}). The
 * registry keeps a weak reference, reads the endpoint's public getters for the 10 §11 {@code
 * tomcat_threads_*} and {@code tomcat_connections_*} series (sent by WO-16) and reports the
 * connector names and the {@code host:port} instance name through the transport's {@code
 * InstanceUpdate} (10 §10.4, 11 §4.2).
 *
 * <p>Named matching only (10 §5.1). A {@code start()} that throws started no connector, so nothing
 * is registered. A restarted connector calls {@code start()} again and is registered again (the
 * registry refreshes the port of an endpoint it already holds). The advice uses the bootstrap API
 * only, so the module has no helper.
 */
public final class TomcatModule implements InstrumentationModule {
  public static final int MODULE_ID = ModuleIds.TOMCAT;

  /** The connector endpoint base class (Tomcat 9.0, 10.1, 11.0). */
  static final String ENDPOINT = "org.apache.tomcat.util.net.AbstractEndpoint";

  @Override
  public String id() {
    return "tomcat";
  }

  @Override
  public int moduleId() {
    return MODULE_ID;
  }

  @Override
  public ElementMatcher<ClassLoader> witness() {
    return Witness.hasClassesNamed(ENDPOINT);
  }

  @Override
  public List<TypeInstrumentation> types() {
    return Collections.<TypeInstrumentation>singletonList(
        new TypeInstrumentation() {
          @Override
          public ElementMatcher<TypeDescription> typeMatcher() {
            return named(ENDPOINT);
          }

          @Override
          public void transform(TypeTransformer transformer) {
            transformer.applyAdvice(start(), EndpointStartAdvice.class.getName());
          }

          @Override
          public String toString() {
            return "tomcat(" + ENDPOINT + ".start)";
          }
        });
  }

  /** {@code start()} without arguments. */
  static ElementMatcher.Junction<MethodDescription> start() {
    return named("start").and(takesArguments(0));
  }

  @Override
  public String[] libraryVersionRange() {
    return new String[] {"Apache Tomcat 9.0, 10.1, 11.0 (embedded and standalone)"};
  }
}
