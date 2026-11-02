/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.boot;

import static net.bytebuddy.matcher.ElementMatchers.named;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.URL;
import java.util.Collections;
import java.util.Enumeration;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The advice of a module is read through the agent class loader's type pool (DSN-02-01: inlined,
 * never loaded). A library type in an advice signature is therefore not resolvable there; module
 * advice takes library arguments as {@code Object} and lets its helper cast them (WO-02 servlet).
 */
class AdviceFactoryTest {

  /** A "library" type that the agent class loader does not see. */
  public static final class LibraryRequest {}

  /** The instrumented class. */
  public static final class Target {
    public String handle(LibraryRequest request) {
      return "handled";
    }
  }

  /** Advice with a library type parameter (what modules must not do). */
  public static final class TypedAdvice {
    private TypedAdvice() {}

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void enter(@Advice.Argument(0) LibraryRequest request) {}
  }

  /** Advice that takes the argument as Object (the module rule). */
  public static final class ObjectAdvice {
    private ObjectAdvice() {}

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void enter(@Advice.Argument(0) Object request) {}
  }

  /** Sees everything of the test class path except {@link LibraryRequest}'s class file. */
  static final class AgentLikeLoader extends ClassLoader {
    private static final String HIDDEN =
        LibraryRequest.class.getName().replace('.', '/') + ".class";

    AgentLikeLoader() {
      super(AdviceFactoryTest.class.getClassLoader());
    }

    @Override
    public URL getResource(String name) {
      return name.equals(HIDDEN) ? null : super.getResource(name);
    }

    @Override
    public Enumeration<URL> getResources(String name) throws IOException {
      return name.equals(HIDDEN) ? Collections.<URL>emptyEnumeration() : super.getResources(name);
    }

    @Override
    public java.io.InputStream getResourceAsStream(String name) {
      return name.equals(HIDDEN) ? null : super.getResourceAsStream(name);
    }
  }

  private static DynamicType.Unloaded<Target> inline(String adviceClass) {
    AdviceFactory advices = new AdviceFactory(new AgentLikeLoader());
    return new ByteBuddy()
        .redefine(Target.class, ClassFileLocator.ForClassLoader.of(Target.class.getClassLoader()))
        .visit(advices.advice(60, adviceClass).on(named("handle")))
        .make();
  }

  @Test
  @DisplayName("advice with an Object parameter inlines into a method that takes a library type")
  void objectParameterInlines() {
    assertThat(inline(ObjectAdvice.class.getName()).getBytes()).isNotEmpty();
  }

  @Test
  @DisplayName("advice with a library type parameter cannot be resolved by the agent's type pool")
  void libraryTypeParameterFails() {
    assertThatThrownBy(() -> inline(TypedAdvice.class.getName()))
        .hasStackTraceContaining(LibraryRequest.class.getName());
  }
}
