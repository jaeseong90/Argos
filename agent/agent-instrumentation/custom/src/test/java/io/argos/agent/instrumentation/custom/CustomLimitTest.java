/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.custom;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.bootstrap.StepType;
import io.argos.agent.bootstrap.TraceContext;
import io.argos.agent.core.command.CustomMethodPolicy;
import io.argos.agent.core.command.Retransformer;
import io.argos.agent.core.guard.RetransformLimiter;
import io.argos.agent.core.log.CustomLogCapture;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.description.modifier.Visibility;
import net.bytebuddy.implementation.FixedValue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The per-item class limit at class load (T-SEC-25 "매칭 501개 패턴 비활성", 16 §5.6) and the
 * retransformation of a policy change through the core's {@link Retransformer} (T-AG-046, 10 §6.10
 * "동적 추가"): new items' methods get the advice, removed items' methods lose it.
 */
@Tag("req:SEC-25")
@Tag("req:F-A14")
@Tag("req:F-A15")
class CustomLimitTest extends CustomTestSupport {
  private static final String MANY = "example.many.";

  /**
   * Defines {@code count} classes {@code example.many.C<i>} with {@code int work()} in a loader.
   */
  private static List<Class<?>> defineMany(FixtureLoader loader, int from, int count) {
    List<Class<?>> classes = new ArrayList<Class<?>>();
    for (int i = from; i < from + count; i++) {
      String name = MANY + "C" + i;
      byte[] bytes =
          new ByteBuddy()
              .subclass(Object.class)
              .name(name)
              .defineMethod("work", int.class, Visibility.PUBLIC)
              .intercept(FixedValue.value(i))
              .make()
              .getBytes();
      classes.add(loader.define(name, bytes));
    }
    return classes;
  }

  private static int work(Class<?> type) throws Exception {
    Method work = type.getMethod("work");
    return ((Integer) work.invoke(type.getConstructor().newInstance())).intValue();
  }

  @Test
  @DisplayName(
      "T-SEC-25 an item matching more than 500 classes at class load is disabled at the 501st with"
          + " one WARN and counted (self-diagnostics); its classes stop recording without a"
          + " retransformation, a class another active item matches keeps recording")
  void limitAtClassLoad() throws Exception {
    CustomMethodPolicy policy = policy(MANY + "C*#work", MANY + "C1#work");
    FixtureLoader loader = new FixtureLoader();
    try (CustomLogCapture log = CustomLogCapture.start("INFO")) {
      List<Class<?>> first = defineMany(loader, 0, 500);
      assertThat(policy.items().get(0).active()).as("500 classes: at the limit").isTrue();
      TraceContext before = begin();
      assertThat(work(first.get(0))).isEqualTo(0);
      assertThat(stepNames(end(before), StepType.METHOD)).containsExactly(MANY + "C0#work");

      defineMany(loader, 500, 1);
      assertThat(policy.items().get(0).disabled()).isTrue();
      assertThat(policy.disabledCount()).isEqualTo(1);
      assertThat(log.linesAt("WARN"))
          .singleElement()
          .asString()
          .contains("instrumentation.custom-methods item 1 disabled: it matches more than 500")
          .doesNotContain("example.many");
      defineMany(loader, 501, 3);
      assertThat(log.linesAt("WARN")).as("one WARN per item").hasSize(1);
    }
    assertThat(CustomMatchers.DisabledClasses.isDisabled(MANY + "C0")).isTrue();
    assertThat(CustomMatchers.DisabledClasses.isDisabled(MANY + "C1"))
        .as("item 2 still matches C1")
        .isFalse();
    TraceContext after = begin();
    assertThat(work(loader.loadClass(MANY + "C0"))).isEqualTo(0);
    assertThat(work(loader.loadClass(MANY + "C1"))).isEqualTo(1);
    assertThat(work(loader.loadClass(MANY + "C2"))).isEqualTo(2);
    assertThat(stepNames(end(after), StepType.METHOD))
        .as("C0 and C2 carry the advice of the disabled item: no step; C1 (item 2): a step")
        .containsExactly(MANY + "C1#work");
  }

  @Test
  @DisplayName(
      "T-SEC-25 a class matched again is counted once (retransformation, a second matcher call)")
  void countedOnce() {
    CustomMethodPolicy policy = policy(2, "example.once.*#*");
    CustomMethodPolicy.Item item = policy.items().get(0);
    assertThat(item.countClass("example.once.A")).isEqualTo(CustomMethodPolicy.Item.COUNTED);
    assertThat(item.countClass("example.once.A")).isEqualTo(CustomMethodPolicy.Item.COUNTED);
    assertThat(item.countClass("example.once.B")).isEqualTo(CustomMethodPolicy.Item.COUNTED);
    assertThat(item.countClass("example.once.C")).isEqualTo(CustomMethodPolicy.Item.DISABLED_NOW);
    assertThat(item.countClass("example.once.D")).isEqualTo(CustomMethodPolicy.Item.INACTIVE);
    assertThat(item.countedClassNames())
        .containsExactlyInAnyOrder("example.once.A", "example.once.B", "example.once.C");
  }

  @Test
  @DisplayName(
      "T-AG-046 a policy change retransformed by the Retransformer adds the step advice to a"
          + " loaded class; removing the item a minute later removes it again")
  void retransformAddsAndRemoves() throws Exception {
    final AtomicLong millis = new AtomicLong(5_000_000L);
    Retransformer retransformer =
        new Retransformer(
            instrumentation(),
            new RetransformLimiter(
                new RetransformLimiter.Clock() {
                  @Override
                  public long millis() {
                    return millis.get();
                  }
                }),
            new LongSupplier() {
              @Override
              public long getAsLong() {
                return System.nanoTime();
              }
            });
    String target = "example.custom.globs.Alpha";
    Callable<?> alpha = (Callable<?>) new FixtureLoader().instance(target);
    assertThat(stepsOf(alpha)).as("no item: no advice").isEmpty();

    CustomMethodPolicy added =
        CustomMethodPolicy.of(java.util.Arrays.asList(target + "#call"), 500);
    Retransformer.Result first = retransformer.apply(CustomMethodPolicy.current(), added);
    assertThat(first.applied()).isTrue();
    assertThat(first.classes()).as("this test's Alpha (other loaders may hold more)").isPositive();
    assertThat(stepsOf(alpha)).containsExactly(target + "#call");

    millis.addAndGet(60_000L);
    CustomMethodPolicy removed = CustomMethodPolicy.of(new ArrayList<String>(), 500);
    Retransformer.Result second = retransformer.apply(added, removed);
    assertThat(second.applied()).isTrue();
    assertThat(stepsOf(alpha)).as("item removed: advice removed").isEmpty();
  }

  private List<String> stepsOf(Callable<?> callable) throws Exception {
    TraceContext ctx = begin();
    callable.call();
    return stepNames(end(ctx), StepType.METHOD);
  }
}
