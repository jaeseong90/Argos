/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.boot;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.List;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.dynamic.ClassFileLocator;

/**
 * Creates the agent builder with Argos defaults from the first instance on.
 *
 * <p>Why not {@code new AgentBuilder.Default(byteBuddy).with(...)}: every public constructor
 * creates ByteBuddy's default {@code InitializationStrategy.SelfInjection.Split}, whose class
 * initialisation runs {@code NexusAccessor} → {@code ClassInjector.UsingReflection} → {@code
 * ClassInjector.UsingUnsafe} on JDK 9+, i.e. the {@code sun.misc.Unsafe} code that agent-dist
 * removes from the jar (ADR-0003, F-A35; WO-02 observed {@code NoClassDefFoundError} on JDK 21).
 * This factory calls ByteBuddy's protected full constructor reflectively with {@link
 * AgentBuilder.InitializationStrategy.NoOp} and {@link AgentBuilder.InjectionStrategy.Disabled}:
 * advice is inlined (no auxiliary types), helpers are defined by {@link HelperInjector}, and
 * ByteBuddy never initialises its class injectors.
 *
 * <p>Arguments are chosen by parameter type (ByteBuddy 1.18 defaults, except the type strategy
 * {@code REDEFINE_FROZEN} and the ignore matcher); a parameter type this class does not know (a new
 * ByteBuddy version) fails with an exception that names it, so the agent starts disabled instead of
 * reaching the removed classes.
 */
final class ArgosAgentBuilder {
  private ArgosAgentBuilder() {}

  static AgentBuilder create(ByteBuddy byteBuddy, AgentBuilder.RawMatcher ignored) {
    Constructor<?> full = null;
    for (Constructor<?> constructor : AgentBuilder.Default.class.getDeclaredConstructors()) {
      if (full == null
          || constructor.getParameterTypes().length > full.getParameterTypes().length) {
        full = constructor;
      }
    }
    if (full == null) {
      throw new IllegalStateException("AgentBuilder.Default has no constructor");
    }
    Class<?>[] types = full.getParameterTypes();
    Object[] arguments = new Object[types.length];
    try {
      for (int i = 0; i < types.length; i++) {
        arguments[i] = argument(types[i], byteBuddy, ignored);
      }
      full.setAccessible(true);
      return (AgentBuilder) full.newInstance(arguments);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException("cannot create the ByteBuddy agent builder", e);
    }
  }

  private static Object argument(
      Class<?> type, ByteBuddy byteBuddy, AgentBuilder.RawMatcher ignored)
      throws ReflectiveOperationException {
    if (type == ByteBuddy.class) {
      return byteBuddy;
    }
    if (type == AgentBuilder.Listener.class) {
      return AgentBuilder.Listener.NoOp.INSTANCE;
    }
    if (type == AgentBuilder.CircularityLock.class) {
      return new AgentBuilder.CircularityLock.Default();
    }
    if (type == AgentBuilder.PoolStrategy.class) {
      return AgentBuilder.PoolStrategy.Default.FAST;
    }
    if (type == AgentBuilder.TypeStrategy.class) {
      return AgentBuilder.TypeStrategy.Default.REDEFINE_FROZEN;
    }
    if (type == AgentBuilder.LocationStrategy.class) {
      return AgentBuilder.LocationStrategy.ForClassLoader.STRONG;
    }
    if (type == ClassFileLocator.class) {
      return ClassFileLocator.NoOp.INSTANCE;
    }
    // Protected nested types of ByteBuddy: their constants are looked up by name.
    if (type.getName().endsWith("AgentBuilder$Default$NativeMethodStrategy")) {
      return constant(type, "Disabled");
    }
    if (type.getName().endsWith("AgentBuilder$Default$WarmupStrategy")) {
      return constant(type, "NoOp");
    }
    if (type == AgentBuilder.TransformerDecorator.class) {
      return AgentBuilder.TransformerDecorator.NoOp.INSTANCE;
    }
    if (type == AgentBuilder.InitializationStrategy.class) {
      return AgentBuilder.InitializationStrategy.NoOp.INSTANCE;
    }
    if (type == AgentBuilder.RedefinitionStrategy.class) {
      return AgentBuilder.RedefinitionStrategy.DISABLED;
    }
    if (type == AgentBuilder.RedefinitionStrategy.DiscoveryStrategy.class) {
      return AgentBuilder.RedefinitionStrategy.DiscoveryStrategy.SinglePass.INSTANCE;
    }
    if (type == AgentBuilder.RedefinitionStrategy.BatchAllocator.class) {
      return AgentBuilder.RedefinitionStrategy.BatchAllocator.ForTotal.INSTANCE;
    }
    if (type == AgentBuilder.RedefinitionStrategy.Listener.class) {
      return AgentBuilder.RedefinitionStrategy.Listener.NoOp.INSTANCE;
    }
    if (type.getName().endsWith("AgentBuilder$RedefinitionStrategy$ResubmissionStrategy")) {
      return constant(type, "Disabled");
    }
    if (type == AgentBuilder.InjectionStrategy.class) {
      return AgentBuilder.InjectionStrategy.Disabled.INSTANCE;
    }
    if (type == AgentBuilder.LambdaInstrumentationStrategy.class) {
      return AgentBuilder.LambdaInstrumentationStrategy.DISABLED;
    }
    if (type == AgentBuilder.DescriptionStrategy.class) {
      return AgentBuilder.DescriptionStrategy.Default.HYBRID;
    }
    if (type == AgentBuilder.FallbackStrategy.class) {
      return AgentBuilder.FallbackStrategy.ByThrowableType.ofOptionalTypes();
    }
    if (type == AgentBuilder.ClassFileBufferStrategy.class) {
      return AgentBuilder.ClassFileBufferStrategy.Default.RETAINING;
    }
    if (type == AgentBuilder.InstallationListener.class) {
      return AgentBuilder.InstallationListener.NoOp.INSTANCE;
    }
    if (type == AgentBuilder.RawMatcher.class) {
      return ignored;
    }
    if (type == List.class) {
      return Collections.emptyList();
    }
    throw new IllegalStateException(
        "unsupported AgentBuilder.Default constructor parameter "
            + type.getName()
            + " (ByteBuddy version change?)");
  }

  /** The {@code INSTANCE} constant of the nested class {@code type$nested}. */
  private static Object constant(Class<?> type, String nested) throws ReflectiveOperationException {
    Field field =
        Class.forName(type.getName() + "$" + nested, true, type.getClassLoader())
            .getDeclaredField("INSTANCE");
    field.setAccessible(true);
    return field.get(null);
  }
}
