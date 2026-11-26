/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.boot;

import static net.bytebuddy.matcher.ElementMatchers.named;
import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.core.log.LogCapture;
import io.argos.agent.instrumentation.api.InstrumentationModule;
import io.argos.agent.instrumentation.api.TypeInstrumentation;
import io.argos.agent.instrumentation.api.TypeTransformer;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.agent.builder.ResettableClassFileTransformer;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.scaffold.TypeValidation;
import net.bytebuddy.matcher.ElementMatcher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * ADR-0010 C-257 (2): a retransformation the JVM rejects is logged, not silent. Before, the premain
 * retransformation ran with ByteBuddy's {@code NoOp} redefinition listener and a rejected batch
 * (T-MX-112: "attempted to change superclass or interfaces", 21 classes) left no trace.
 *
 * <p>Without a second agent the JVM's rejection is reproduced by an {@link Instrumentation} whose
 * {@code retransformClasses} throws what HotSpot throws for such a class file; the agent builder is
 * the real one of {@link InstrumentationInstaller#install} (premain discovery of the JDK allow-list
 * classes, ignore matcher, a module rule that matches them).
 */
@Tag("req:N-27")
@Tag("req:F-A32")
class RetransformFailureListenerTest {
  private static final String JVM_REASON =
      "class redefinition failed: attempted to change superclass or interfaces";

  @Test
  @DisplayName(
      "T-AG-005d a premain retransformation the JVM rejects writes one WARN with the class count,"
          + " a class name and the JVM's reason; the agent still starts. The NoOp listener of"
          + " before C-257 (2) writes nothing")
  void rejectedPremainRetransformationIsLogged() {
    RejectingInstrumentation jvm = new RejectingInstrumentation();
    try (LogCapture log = LogCapture.start("INFO")) {
      ResettableClassFileTransformer transformer =
          InstrumentationInstaller.install(
              jvm.proxy(),
              RetransformFailureListenerTest.class.getClassLoader(),
              Collections.<InstrumentationModule>singletonList(new ExecutorModule()),
              Collections.<String>emptyList(),
              AgentBuilder.Listener.NoOp.INSTANCE,
              AgentBuilder.Listener.NoOp.INSTANCE,
              new HelperInjector(
                  null,
                  RetransformFailureListenerTest.class.getClassLoader(),
                  new LoaderState(),
                  TestFaults.NONE),
              new LoaderState(),
              TestFaults.NONE);
      assertThat(transformer).isNotNull();
      assertThat(jvm.batches)
          .as("one premain batch with the allow-listed executors only (String is not)")
          .containsExactly(
              Arrays.<Class<?>>asList(ThreadPoolExecutor.class, ScheduledThreadPoolExecutor.class));
      assertThat(log.linesAt("WARN"))
          .singleElement()
          .asString()
          .contains("retransformation of 2 already loaded class(es) failed")
          .contains("stay uninstrumented")
          .contains("e.g. java.util.concurrent.ThreadPoolExecutor")
          .contains("UnsupportedOperationException: " + JVM_REASON);
    }

    // The same rejection with the listener of before C-257 (2): nothing in the log.
    RejectingInstrumentation silent = new RejectingInstrumentation();
    try (LogCapture log = LogCapture.start("INFO")) {
      ArgosAgentBuilder.create(
              new ByteBuddy().with(TypeValidation.DISABLED),
              IgnoreMatchers.matcher(
                  Collections.<String>emptyList(),
                  RetransformFailureListenerTest.class.getClassLoader()))
          .disableClassFormatChanges()
          .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
          .with(new PremainDiscovery())
          .type(named(ThreadPoolExecutor.class.getName()))
          .transform(new AgentBuilder.Transformer.ForAdvice())
          .installOn(silent.proxy());
      assertThat(silent.batches).hasSize(1);
      assertThat(log.linesAt("WARN")).isEmpty();
    }
  }

  @Test
  @DisplayName(
      "T-AG-005d failures within a minute of a WARN are counted, not logged; the next line after"
          + " the minute reports them; nothing is retried")
  void rateLimitedToOneLinePerMinute() {
    ManualClockListener listener = new ManualClockListener();
    List<Class<?>> two = Arrays.<Class<?>>asList(ThreadPoolExecutor.class, String.class);
    List<Class<?>> one = Collections.<Class<?>>singletonList(Integer.class);
    RuntimeException rejected = new UnsupportedOperationException(JVM_REASON);
    try (LogCapture log = LogCapture.start("INFO")) {
      assertThat(listener.onError(0, two, rejected, two)).isEmpty();
      listener.now += RetransformFailureListener.INTERVAL_NANOS - 1L;
      assertThat(listener.onError(0, one, rejected, one)).isEmpty();
      assertThat(listener.onError(0, one, rejected, one)).isEmpty();
      assertThat(log.linesAt("WARN")).hasSize(1);
      assertThat(log.linesAt("WARN").get(0))
          .contains("retransformation of 2 already loaded class(es)");

      listener.now += 1L;
      listener.onError(0, one, rejected, one);
      List<String> warns = log.linesAt("WARN");
      assertThat(warns).hasSize(2);
      // The two classes counted in between and this batch's one.
      assertThat(warns.get(1))
          .contains("retransformation of 3 already loaded class(es)")
          .contains("this batch: 1, e.g. java.lang.Integer");
      assertThat(listener.failedClasses()).isEqualTo(5L);
    }
  }

  @Test
  @DisplayName(
      "T-AG-005d the listener never throws into ByteBuddy: empty or null batches, a throwable"
          + " whose toString fails; a long multi-line JVM reason is cut to one line")
  void neverThrowsAndBoundsTheReason() {
    ManualClockListener listener = new ManualClockListener();
    try (LogCapture log = LogCapture.start("INFO")) {
      assertThat(listener.onError(0, null, null, null)).isEmpty();
      listener.now += RetransformFailureListener.INTERVAL_NANOS;
      Throwable broken =
          new VerifyError() {
            private static final long serialVersionUID = 1L;

            @Override
            @SuppressWarnings("OverrideThrowableToString") // a throwable whose toString fails
            public String toString() {
              throw new IllegalStateException("broken");
            }
          };
      assertThat(listener.onError(0, Collections.<Class<?>>emptyList(), broken, null)).isEmpty();
      listener.now += RetransformFailureListener.INTERVAL_NANOS;
      StringBuilder huge = new StringBuilder("Bad type on operand stack\nException Details:\n");
      for (int i = 0; i < 100; i++) {
        huge.append("  Location: fixture/Type.method()V @").append(i).append('\n');
      }
      listener.onError(
          0,
          Collections.<Class<?>>singletonList(ThreadPoolExecutor.class),
          new VerifyError(huge.toString()),
          null);
      listener.onBatch(0, null, null);
      listener.onComplete(0, null, null);

      List<String> warns = log.linesAt("WARN");
      assertThat(warns).hasSize(3);
      assertThat(warns.get(0))
          .contains("0 already loaded class(es)")
          .contains("e.g. -")
          .endsWith("unknown reason; at most one such line per minute");
      assertThat(warns.get(1)).contains(broken.getClass().getName() + "; at most one");
      List<String> lines = log.lines();
      String last = lines.get(lines.size() - 1);
      assertThat(last)
          .contains("java.lang.VerifyError: Bad type on operand stack Exception Details:")
          .contains("...; at most one such line per minute")
          .doesNotContain("\n")
          .doesNotContain("@99");
      assertThat(last.length()).isLessThan(RetransformFailureListener.MAX_REASON_CHARS + 300);
    }
  }

  /** The listener with a clock the test moves. */
  static final class ManualClockListener extends RetransformFailureListener {
    long now = 1_000_000_000L;

    @Override
    long nanoTime() {
      return now;
    }
  }

  /** A module that instruments the two executors the premain discovery finds. */
  static final class ExecutorModule implements InstrumentationModule {
    @Override
    public String id() {
      return "retransform-failure-test";
    }

    @Override
    public int moduleId() {
      return 63;
    }

    @Override
    public List<TypeInstrumentation> types() {
      return Collections.<TypeInstrumentation>singletonList(
          new TypeInstrumentation() {
            @Override
            public ElementMatcher<TypeDescription> typeMatcher() {
              return named(ThreadPoolExecutor.class.getName())
                  .or(named(ScheduledThreadPoolExecutor.class.getName()));
            }

            @Override
            public void transform(TypeTransformer transformer) {
              // The JVM rejects the batch before any transformation runs.
            }
          });
    }
  }

  /**
   * An instrumentation whose {@code retransformClasses} fails like HotSpot does for a class file
   * whose interfaces differ from the loaded class; records the batches it was asked for.
   */
  static final class RejectingInstrumentation implements InvocationHandler {
    final List<List<Class<?>>> batches = new ArrayList<List<Class<?>>>();
    final List<ClassFileTransformer> transformers = new ArrayList<ClassFileTransformer>();

    Instrumentation proxy() {
      return (Instrumentation)
          Proxy.newProxyInstance(
              Instrumentation.class.getClassLoader(), new Class<?>[] {Instrumentation.class}, this);
    }

    @Override
    @SuppressWarnings("ReferenceEquality") // Proxy.equals of a test double: identity
    public Object invoke(Object proxy, Method method, Object[] args) {
      String name = method.getName();
      if (name.equals("getAllLoadedClasses")) {
        return new Class<?>[] {
          String.class, ThreadPoolExecutor.class, ScheduledThreadPoolExecutor.class
        };
      }
      if (name.equals("isModifiableClass")
          || name.equals("isRetransformClassesSupported")
          || name.equals("isRedefineClassesSupported")) {
        return true;
      }
      if (name.equals("addTransformer")) {
        transformers.add((ClassFileTransformer) args[0]);
        return null;
      }
      if (name.equals("removeTransformer")) {
        return Boolean.valueOf(transformers.remove(args[0]));
      }
      if (name.equals("retransformClasses")) {
        batches.add(Arrays.asList((Class<?>[]) args[0]));
        throw new UnsupportedOperationException(JVM_REASON);
      }
      if (name.equals("hashCode")) {
        return Integer.valueOf(System.identityHashCode(proxy));
      }
      if (name.equals("equals")) {
        return Boolean.valueOf(proxy == args[0]);
      }
      if (method.getReturnType() == boolean.class) {
        return false;
      }
      return null;
    }
  }
}
