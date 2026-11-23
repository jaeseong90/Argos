/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.api;

import static net.bytebuddy.matcher.ElementMatchers.named;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.pool.TypePool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** SafeMatchers, Witness and module defaults (10 §5.1). */
class ApiTest {

  @AfterEach
  void resetSink() {
    SafeMatchers.setErrorSink(null);
  }

  @Test
  @DisplayName(
      "safeHasSuperType matches superclasses and interfaces; safeImplements interfaces only")
  void hierarchy() {
    TypeDescription arrayList = TypeDescription.ForLoadedType.of(ArrayList.class);
    assertThat(SafeMatchers.safeHasSuperType(named("java.util.AbstractList")).matches(arrayList))
        .isTrue();
    assertThat(SafeMatchers.safeHasSuperType(named("java.util.Collection")).matches(arrayList))
        .isTrue();
    assertThat(SafeMatchers.safeImplements(named("java.util.List")).matches(arrayList)).isTrue();
    assertThat(SafeMatchers.safeImplements(named("java.util.AbstractList")).matches(arrayList))
        .isFalse();
    assertThat(SafeMatchers.safeHasSuperType(named("java.lang.Runnable")).matches(arrayList))
        .isFalse();
    assertThat(SafeMatchers.safeImplements(named("java.util.List")).toString())
        .startsWith("safeImplements(");
  }

  @Test
  @DisplayName("an unresolvable supertype ends the walk with false and is reported, never thrown")
  void unresolvableSupertype() {
    DynamicType.Unloaded<Object> missing =
        new ByteBuddy().subclass(Object.class).name("x.Missing").make();
    DynamicType.Unloaded<?> child =
        new ByteBuddy().subclass(missing.getTypeDescription()).name("x.Child").make();
    TypePool pool =
        TypePool.Default.of(
            new ClassFileLocator.Compound(
                ClassFileLocator.Simple.of("x.Child", child.getBytes()),
                ClassFileLocator.ForClassLoader.ofSystemLoader()));
    TypeDescription description = pool.describe("x.Child").resolve();
    final List<String> reported = new ArrayList<String>();
    SafeMatchers.setErrorSink(
        new SafeMatchers.ErrorSink() {
          @Override
          public void onResolutionError(String typeName, Throwable error) {
            reported.add(typeName);
          }
        });
    assertThat(SafeMatchers.safeHasSuperType(named("java.lang.Runnable")).matches(description))
        .isFalse();
    // Reported for the type whose superclass could not be resolved; that branch ends there.
    assertThat(reported).containsExactly("x.Child");
    assertThat(SafeMatchers.safeHasSuperType(named("x.Child")).matches(description)).isTrue();
  }

  @Test
  @DisplayName("Witness.hasClassesNamed looks up class files; null loader means JDK classes only")
  void witness() throws IOException {
    ElementMatcher<ClassLoader> jdkOnly = Witness.hasClassesNamed("java.lang.String");
    ElementMatcher<ClassLoader> withTest =
        Witness.hasClassesNamed("java.lang.String", ApiTest.class.getName());
    assertThat(jdkOnly.matches(null)).isTrue();
    assertThat(withTest.matches(null)).isFalse();
    assertThat(withTest.matches(ApiTest.class.getClassLoader())).isTrue();
    try (URLClassLoader isolated = new URLClassLoader(new URL[0], null)) {
      assertThat(withTest.matches(isolated)).isFalse();
    }
    assertThat(withTest.toString()).contains(ApiTest.class.getName());
  }

  @Test
  @DisplayName("module defaults: any class loader, no helpers, no version range; ids 0..63")
  void moduleDefaults() {
    InstrumentationModule module =
        new InstrumentationModule() {
          @Override
          public String id() {
            return "m";
          }

          @Override
          public int moduleId() {
            return ModuleIds.JDBC;
          }

          @Override
          public List<TypeInstrumentation> types() {
            return new ArrayList<TypeInstrumentation>();
          }
        };
    assertThat(module.witness().matches(null)).isTrue();
    assertThat(module.helperClassNames()).isEmpty();
    assertThat(module.libraryVersionRange()).isEmpty();
    assertThat(ModuleIds.MAX).isEqualTo(63);
    assertThat(ModuleIds.TEST_FIRST).isLessThanOrEqualTo(ModuleIds.MAX);
  }

  @Test
  @DisplayName(
      "TypeTransformer.injectContextField is a default no-op (DSN-02-05): transformers written"
          + " before field injection still compile and run, and a module may always ask for it")
  void injectContextFieldDefault() {
    final List<String> applied = new ArrayList<String>();
    TypeTransformer transformer =
        new TypeTransformer() {
          @Override
          public void applyAdvice(
              ElementMatcher<? super net.bytebuddy.description.method.MethodDescription> methods,
              String adviceClassName) {
            applied.add(adviceClassName);
          }
        };
    TypeInstrumentation task =
        new TypeInstrumentation() {
          @Override
          public ElementMatcher<TypeDescription> typeMatcher() {
            return named("x.Task");
          }

          @Override
          public void transform(TypeTransformer t) {
            t.applyAdvice(named("run"), "x.RunAdvice");
            t.injectContextField();
          }
        };
    task.transform(transformer);
    assertThat(applied).containsExactly("x.RunAdvice");
  }
}
