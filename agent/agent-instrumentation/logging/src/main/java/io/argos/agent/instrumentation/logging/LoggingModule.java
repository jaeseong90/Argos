/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.logging;

import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.namedOneOf;
import static net.bytebuddy.matcher.ElementMatchers.not;
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
 * Trace id injection into the MDC of the application's log events (10 §6.9, F-A20, SEC-26): every
 * log event created while a transaction is active carries {@value LoggingHelper#TRACE_ID_KEY} (32
 * lower-case hex digits) and {@value LoggingHelper#TX_ID_KEY} (16 lower-case hex digits), so that a
 * pattern such as {@code %X{argos.trace_id}} prints them. The application's thread MDC is never
 * written: the keys are added to the event's own MDC copy at event creation, so an application's
 * {@code MDC.clear()} does not remove them, and events created without a transaction get no keys.
 *
 * <ul>
 *   <li>Logback: {@code ch.qos.logback.classic.spi.LoggingEvent.getMDCPropertyMap()} and {@code
 *       getMdc()} return the event's map plus the two keys (a new map, {@code @Advice.Return}
 *       allowlisted, 10 §5.3 A-5). Logback caches the map in the event: an {@code AsyncAppender}
 *       snapshots it on the application thread ({@code prepareForDeferredProcessing()}) and its
 *       worker formats the event later, without a transaction. The entry of {@code
 *       prepareForDeferredProcessing()} therefore attaches the creating thread's transaction to the
 *       event ({@code ContextStore}, weak keys, bounded) and the worker's call finds it there. The
 *       constructor exit reads {@code capture.user-id.source: mdc:<key>} (ADR-0010 C-125 (3)).
 *   <li>Log4j 2: the exit of {@code injectContextData(List, StringMap)} of the three {@code
 *       ThreadContextDataInjector} implementations puts the keys into the returned map when it is
 *       mutable ({@code putValue}); a frozen map is never changed (10 §18 U-13, module README).
 *   <li>Log4j 1.2 and reload4j: the exit of the {@code LoggingEvent} constructors takes the event's
 *       MDC copy ({@code getMDCCopy()}) and sets {@code mdcCopy} to a copy with the two keys
 *       ({@code @Advice.FieldValue("mdcCopy")} allowlisted).
 * </ul>
 *
 * <p>Agent threads are skipped ({@code Argos.skip}); the agent never logs through the application's
 * logging API (its own logger only, 10 §14).
 */
public final class LoggingModule implements InstrumentationModule {
  public static final int MODULE_ID = ModuleIds.LOGGING;

  static final String LOGBACK_EVENT = "ch.qos.logback.classic.spi.LoggingEvent";

  /** The context data injectors of Log4j 2 (10 §6.9); binary names. */
  static final String[] LOG4J2_INJECTORS = {
    "org.apache.logging.log4j.core.impl.ThreadContextDataInjector$ForDefaultThreadContextMap",
    "org.apache.logging.log4j.core.impl.ThreadContextDataInjector$ForGarbageFreeThreadContextMap",
    "org.apache.logging.log4j.core.impl.ThreadContextDataInjector$ForCopyOnWriteThreadContextMap"
  };

  static final String LOG4J1_EVENT = "org.apache.log4j.spi.LoggingEvent";

  @Override
  public String id() {
    return "logging";
  }

  @Override
  public int moduleId() {
    return MODULE_ID;
  }

  @Override
  public ElementMatcher<ClassLoader> witness() {
    return Witness.hasClassesNamed(LOGBACK_EVENT)
        .or(
            Witness.hasClassesNamed(
                "org.apache.logging.log4j.core.impl.ThreadContextDataInjector",
                "org.apache.logging.log4j.util.StringMap"))
        .or(Witness.hasClassesNamed(LOG4J1_EVENT));
  }

  @Override
  public List<TypeInstrumentation> types() {
    return Arrays.<TypeInstrumentation>asList(
        new TypeInstrumentation() {
          @Override
          public ElementMatcher<TypeDescription> typeMatcher() {
            return named(LOGBACK_EVENT);
          }

          @Override
          public void transform(TypeTransformer transformer) {
            transformer.applyAdvice(logbackMdcMethods(), LogbackMdcAdvice.class.getName());
            transformer.applyAdvice(
                named("prepareForDeferredProcessing").and(takesArguments(0)),
                LogbackDeferredAdvice.class.getName());
            transformer.applyAdvice(
                isConstructor().and(not(takesArguments(0))), LogbackEventAdvice.class.getName());
          }

          @Override
          public String toString() {
            return "logging(logback LoggingEvent)";
          }
        },
        new TypeInstrumentation() {
          @Override
          public ElementMatcher<TypeDescription> typeMatcher() {
            return namedOneOf(LOG4J2_INJECTORS);
          }

          @Override
          public void transform(TypeTransformer transformer) {
            transformer.applyAdvice(
                named("injectContextData").and(takesArguments(2)),
                Log4j2InjectAdvice.class.getName());
          }

          @Override
          public String toString() {
            return "logging(log4j2 ThreadContextDataInjector)";
          }
        },
        new TypeInstrumentation() {
          @Override
          public ElementMatcher<TypeDescription> typeMatcher() {
            return named(LOG4J1_EVENT);
          }

          @Override
          public void transform(TypeTransformer transformer) {
            transformer.applyAdvice(isConstructor(), Log4j1EventAdvice.class.getName());
          }

          @Override
          public String toString() {
            return "logging(log4j1 LoggingEvent.<init>)";
          }
        });
  }

  /** {@code getMDCPropertyMap()} and the older {@code getMdc()} of Logback's event. */
  static ElementMatcher.Junction<MethodDescription> logbackMdcMethods() {
    return namedOneOf("getMDCPropertyMap", "getMdc").and(takesArguments(0));
  }

  @Override
  public List<String> helperClassNames() {
    return HelperClasses.of(
        LoggingModule.class, "LoggingHelper", "LogbackHelper", "Log4j2Helper", "Log4j1Helper");
  }

  @Override
  public String[] libraryVersionRange() {
    return new String[] {
      "ch.qos.logback:logback-classic 1.2-1.5 (tested 1.2.12, 1.5.x)",
      "org.apache.logging.log4j:log4j-core 2.17-2.25 (tested 2.17.2, 2.25.5; see README for the"
          + " frozen context maps of 10 U-13)",
      "log4j:log4j 1.2.17, ch.qos.reload4j:reload4j 1.2.x (tested 1.2.17, reload4j 1.2.26)"
    };
  }
}
