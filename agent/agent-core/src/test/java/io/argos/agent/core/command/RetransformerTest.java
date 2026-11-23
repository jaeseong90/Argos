/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.command;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.core.config.EffectiveConfig.Source;
import io.argos.agent.core.guard.RetransformLimiter;
import io.argos.agent.core.log.LogCapture;
import io.argos.proto.v1.ConfigApplyResult;
import io.argos.proto.v1.ConfigPush;
import io.argos.proto.v1.RejectCode;
import io.argos.proto.v1.RejectedKey;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.lang.instrument.UnmodifiableClassException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The retransformation of a remote {@code instrumentation.custom-methods} change (10 §12.2 step 4,
 * §6.10, F-A15, SEC-25, T-AG-046) with a recording {@link Instrumentation}: the class choice, the
 * 50 classes / one per minute limit (injected clock), the policy installed only when the change
 * goes through, and the {@code ConfigApplyResult} of the apply path.
 */
@Tag("req:F-A15")
@Tag("req:SEC-25")
class RetransformerTest {
  private static final String TARGET = "org.junit.jupiter.api.Tag";

  @TempDir File dir;
  private RemoteFixture fixture;
  private final AtomicLong millis = new AtomicLong(1_000_000L);
  private final RetransformLimiter limiter =
      new RetransformLimiter(
          new RetransformLimiter.Clock() {
            @Override
            public long millis() {
              return millis.get();
            }
          });

  @AfterEach
  void restore() {
    CustomMethodPolicy.install(null);
    if (fixture != null) {
      fixture.close();
    }
  }

  /** A recording instrumentation over {@code loaded}; {@code failing} classes throw. */
  static final class FakeInstrumentation implements InvocationHandler {
    final Class<?>[] loaded;
    final Set<Class<?>> failing = new HashSet<Class<?>>();
    final List<List<Class<?>>> calls = new ArrayList<List<Class<?>>>();

    FakeInstrumentation(Class<?>... loaded) {
      this.loaded = loaded;
    }

    Instrumentation proxy() {
      return (Instrumentation)
          Proxy.newProxyInstance(
              Instrumentation.class.getClassLoader(), new Class<?>[] {Instrumentation.class}, this);
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
      if (method.getName().equals("getAllLoadedClasses")) {
        return loaded.clone();
      }
      if (method.getName().equals("isModifiableClass")) {
        return true;
      }
      if (method.getName().equals("retransformClasses")) {
        List<Class<?>> batch = Arrays.asList((Class<?>[]) args[0]);
        for (Class<?> type : batch) {
          if (failing.contains(type)) {
            throw new UnmodifiableClassException("test");
          }
        }
        calls.add(batch);
        return null;
      }
      throw new UnsupportedOperationException(method.getName());
    }
  }

  /** {@code count} distinct classes named {@link #TARGET}, each in its own class loader. */
  static Class<?>[] copiesOfTarget(int count) throws IOException {
    byte[] bytes;
    try (InputStream in =
        RetransformerTest.class
            .getClassLoader()
            .getResourceAsStream(TARGET.replace('.', '/') + ".class")) {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      byte[] buffer = new byte[8192];
      for (int n = in.read(buffer); n >= 0; n = in.read(buffer)) {
        out.write(buffer, 0, n);
      }
      bytes = out.toByteArray();
    }
    Class<?>[] classes = new Class<?>[count];
    for (int i = 0; i < count; i++) {
      classes[i] = new DefiningLoader().define(bytes);
    }
    return classes;
  }

  private static final class DefiningLoader extends ClassLoader {
    DefiningLoader() {
      super(null);
    }

    Class<?> define(byte[] bytes) {
      return defineClass(null, bytes, 0, bytes.length);
    }
  }

  private Retransformer retransformer(FakeInstrumentation inst) {
    return new Retransformer(inst.proxy(), limiter, fixtureClock());
  }

  private static java.util.function.LongSupplier fixtureClock() {
    return new java.util.function.LongSupplier() {
      @Override
      public long getAsLong() {
        return 0L;
      }
    };
  }

  private static CustomMethodPolicy policy(String... items) {
    return CustomMethodPolicy.of(Arrays.asList(items), 500);
  }

  @Test
  @DisplayName(
      "T-AG-046 up to 50 matching classes are retransformed in one call and the new policy is"
          + " installed; a second change within a minute is refused retransform_limit and installs"
          + " nothing; a minute later it goes through")
  void limitPerMinute() throws Exception {
    Class<?>[] classes = copiesOfTarget(50);
    FakeInstrumentation inst = new FakeInstrumentation(classes);
    Retransformer retransformer = retransformer(inst);
    CustomMethodPolicy first = policy(TARGET + "#value");
    Retransformer.Result result = retransformer.apply(CustomMethodPolicy.current(), first);
    assertThat(result.applied()).isTrue();
    assertThat(result.classes()).isEqualTo(50);
    assertThat(inst.calls).hasSize(1);
    assertThat(inst.calls.get(0)).hasSize(50);
    assertThat(CustomMethodPolicy.current()).isSameAs(first);

    millis.addAndGet(59_000L);
    CustomMethodPolicy second = policy(TARGET + "#*");
    Retransformer.Result refused = retransformer.apply(first, second);
    assertThat(refused.applied()).isFalse();
    assertThat(refused.classes()).isZero();
    assertThat(refused.refusal())
        .startsWith("retransform_limit: at most one retransformation per minute")
        .contains("next one in 1 s");
    assertThat(CustomMethodPolicy.current()).isSameAs(first);
    assertThat(inst.calls).hasSize(1);

    millis.addAndGet(1_000L);
    assertThat(retransformer.apply(first, second).applied()).isTrue();
    assertThat(CustomMethodPolicy.current()).isSameAs(second);
    Map<String, Object> diagnostics = retransformer.diagnostics();
    assertThat(diagnostics)
        .containsEntry(Retransformer.EXTRA_REQUESTS, 3L)
        .containsEntry(Retransformer.EXTRA_REJECTED, 1L)
        .containsEntry(Retransformer.EXTRA_CLASSES, 100L);
  }

  @Test
  @DisplayName(
      "T-AG-046 51 classes are refused retransform_limit without using up the minute; a change"
          + " that touches no class neither")
  void tooManyClasses() throws Exception {
    Class<?>[] classes = copiesOfTarget(51);
    FakeInstrumentation inst = new FakeInstrumentation(classes);
    Retransformer retransformer = retransformer(inst);
    try (LogCapture log = LogCapture.start("INFO")) {
      Retransformer.Result refused =
          retransformer.apply(CustomMethodPolicy.current(), policy(TARGET + "#*"));
      assertThat(refused.applied()).isFalse();
      assertThat(refused.refusal())
          .isEqualTo(
              "retransform_limit: 51 classes would be retransformed, at most 50 per request");
      assertThat(log.linesAt("WARN")).singleElement().asString().contains("retransform_limit");
    }
    assertThat(inst.calls).isEmpty();
    // No class: allowed, and the minute stays free for the next change.
    CustomMethodPolicy none = policy("com.example.none.*#*");
    assertThat(retransformer.apply(CustomMethodPolicy.current(), none).applied()).isTrue();
    assertThat(CustomMethodPolicy.current()).isSameAs(none);
    FakeInstrumentation few = new FakeInstrumentation(copiesOfTarget(3));
    Retransformer second = new Retransformer(few.proxy(), limiter, fixtureClock());
    assertThat(second.apply(none, policy(TARGET + "#*")).classes()).isEqualTo(3);
  }

  @Test
  @DisplayName(
      "T-AG-046 the classes a removed item matched are retransformed too; forbidden and generated"
          + " classes never are")
  void previousClassesAndExclusions() throws Exception {
    Class<?>[] targets = copiesOfTarget(2);
    Class<?>[] loaded = {
      targets[0], targets[1], org.junit.jupiter.api.Test.class, String.class, Runnable.class
    };
    FakeInstrumentation inst = new FakeInstrumentation(loaded);
    Retransformer retransformer = retransformer(inst);
    CustomMethodPolicy previous = policy("org.junit.jupiter.api.Test#*");
    CustomMethodPolicy next = policy(TARGET + "#*", "**#*");
    // "**#*" matches every allowed class (5 loaded, 3 allowed): within the limit of 500.
    Retransformer.Result result = retransformer.apply(previous, next);
    assertThat(result.classes()).isEqualTo(3);
    assertThat(inst.calls.get(0))
        .containsExactlyInAnyOrder(targets[0], targets[1], org.junit.jupiter.api.Test.class);
  }

  @Test
  @DisplayName(
      "T-AG-046 a batch the JVM refuses is retried class by class; the failing class is counted")
  void fallbackPerClass() throws Exception {
    Class<?>[] classes = copiesOfTarget(3);
    FakeInstrumentation inst = new FakeInstrumentation(classes);
    inst.failing.add(classes[1]);
    Retransformer retransformer = retransformer(inst);
    try (LogCapture log = LogCapture.start("INFO")) {
      Retransformer.Result result =
          retransformer.apply(CustomMethodPolicy.current(), policy(TARGET + "#*"));
      assertThat(result.applied()).isTrue();
      assertThat(result.classes()).isEqualTo(2);
      assertThat(log.linesAt("WARN")).singleElement().asString().contains("1 of 3 class(es)");
    }
    assertThat(retransformer.diagnostics()).containsEntry(Retransformer.EXTRA_FAILED, 1L);
  }

  // --- through the apply path ----------------------------------------------------------------

  private ConfigPushApplier applier(RemoteFixture f, FakeInstrumentation inst) {
    return new ConfigPushApplier(
        f.config, f.applier, null, f.clock(), f.control, retransformer(inst));
  }

  private static ConfigPush push(String key, String value) {
    return ConfigPush.newBuilder().setChangeId("c").putValues(key, value).build();
  }

  private static Map<String, String> allowCustomMethods() {
    Map<String, String> properties = RemoteFixture.required();
    properties.put(
        "argos.remote-config.allow", "[\"sampling.**\", instrumentation.custom-methods]");
    return properties;
  }

  @Test
  @DisplayName(
      "T-AG-046/T-AG-069 an allowed custom-methods push is retransformed and reported"
          + " (retransformed_classes, source remote); a second one within a minute is rejected"
          + " LIMIT_EXCEEDED retransform_limit and the value in force stays")
  void applyPathLimit() throws Exception {
    RemoteFixture f = new RemoteFixture(dir, allowCustomMethods());
    fixture = f;
    FakeInstrumentation inst = new FakeInstrumentation(copiesOfTarget(4));
    ConfigPushApplier applier = applier(f, inst);
    ConfigApplyResult first =
        applier.apply(push("instrumentation.custom-methods", "[" + TARGET + "#value]"));
    assertThat(first.getAppliedKeysList()).containsExactly("instrumentation.custom-methods");
    assertThat(first.getRetransformedClasses()).isEqualTo(4);
    assertThat(f.config.config().instrumentationCustomMethods()).containsExactly(TARGET + "#value");
    assertThat(f.config.effective().source("instrumentation.custom-methods"))
        .isEqualTo(Source.REMOTE);
    assertThat(CustomMethodPolicy.current().activeItems()).hasSize(1);

    ConfigApplyResult second =
        applier.apply(
            ConfigPush.newBuilder()
                .setChangeId("c2")
                .putValues("instrumentation.custom-methods", "[" + TARGET + "#*]")
                .putValues("sampling.profile.normal-rate", "0.5")
                .build());
    assertThat(second.getAppliedKeysList()).containsExactly("sampling.profile.normal-rate");
    assertThat(second.getRetransformedClasses()).isZero();
    RejectedKey rejected = second.getRejected(0);
    assertThat(rejected.getKey()).isEqualTo("instrumentation.custom-methods");
    assertThat(rejected.getCode()).isEqualTo(RejectCode.LIMIT_EXCEEDED);
    assertThat(rejected.getMessage()).startsWith("retransform_limit: ");
    assertThat(f.config.config().instrumentationCustomMethods()).containsExactly(TARGET + "#value");
    assertThat(CustomMethodPolicy.current().items().get(0).methodGlob()).isEqualTo("value");
  }

  @Test
  @DisplayName(
      "T-SEC-25 a pushed custom-methods item with a forbidden prefix rejects the key"
          + " FORBIDDEN_PATTERN with a WARN, nothing is retransformed; without the key in the"
          + " allow list it is NOT_ALLOWED")
  void forbiddenPush() throws Exception {
    RemoteFixture f = new RemoteFixture(dir, allowCustomMethods());
    fixture = f;
    FakeInstrumentation inst = new FakeInstrumentation(copiesOfTarget(1));
    ConfigPushApplier applier = applier(f, inst);
    try (LogCapture log = LogCapture.start("INFO")) {
      ConfigApplyResult result =
          applier.apply(
              push("instrumentation.custom-methods", "[com.example.A#a, java.lang.String#*]"));
      assertThat(result.getAppliedKeysList()).isEmpty();
      assertThat(result.getRejected(0).getCode()).isEqualTo(RejectCode.FORBIDDEN_PATTERN);
      assertThat(result.getRejected(0).getMessage())
          .isEqualTo("forbidden_pattern: item 2 starts with the forbidden prefix java.");
      assertThat(log.linesAt("WARN"))
          .singleElement()
          .asString()
          .contains("instrumentation.custom-methods(forbidden_pattern)");
      assertThat(log.text()).doesNotContain("java.lang.String#");
    }
    assertThat(inst.calls).isEmpty();
    assertThat(f.config.config().instrumentationCustomMethods()).isEmpty();
    f.close();

    RemoteFixture g = new RemoteFixture(dir, RemoteFixture.required());
    fixture = g;
    ConfigApplyResult notAllowed =
        applier(g, inst).apply(push("instrumentation.custom-methods", "[com.example.A#a]"));
    assertThat(notAllowed.getRejected(0).getCode()).isEqualTo(RejectCode.NOT_ALLOWED);
    assertThat(inst.calls).isEmpty();
  }

  @Test
  @DisplayName(
      "T-AG-046 ServerHello config with custom methods is retransformed like a push; a local"
          + " custom-methods change on CONFIG_RELOAD is restart_required")
  void helloAndReload() throws Exception {
    RemoteFixture f = new RemoteFixture(dir, allowCustomMethods(), "log.level: INFO");
    fixture = f;
    FakeInstrumentation inst = new FakeInstrumentation(copiesOfTarget(2));
    ConfigPushApplier applier = applier(f, inst);
    List<RejectedKey> rejected =
        applier.applyHello(
            Collections.singletonMap("instrumentation.custom-methods", "[" + TARGET + "#*]"));
    assertThat(rejected).isEmpty();
    assertThat(inst.calls).hasSize(1);
    assertThat(f.config.config().instrumentationCustomMethods()).containsExactly(TARGET + "#*");

    f.writeYml("log.level: INFO", "instrumentation.custom-methods: [com.example.B#b]");
    ConfigPushApplier.Reloaded reloaded = applier.reload(f.load());
    assertThat(reloaded.restartRequired()).contains("instrumentation.custom-methods");
    assertThat(inst.calls).hasSize(1);
    assertThat(f.config.state().local().instrumentationCustomMethods()).isEmpty();
  }
}
