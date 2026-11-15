/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.metric;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * Method handles on public getters of library objects (pools, Tomcat endpoints) for the registries
 * of this package (10 §6.4, DSN-10-12: getters only, never JMX). Reflection lives here, in the
 * core, never in advice (10 §5.3 A-3).
 *
 * <p>Only public members of public classes are used ({@link MethodHandles#publicLookup()}), so no
 * access check is ever suppressed and a library in a named module that does not export the package
 * yields no handle (the value is then missing). Every handle is adapted to {@code (Object)Object},
 * the type {@link #get} invokes exactly. A class is never loaded here: the declaring types come
 * from the object's own class hierarchy or from the declared types of members found on it.
 *
 * <p>A handle refers to the library class, and through it to its class loader: the registries keep
 * handles only in an adapter attached to that library class itself ({@link ClassValue}), never in a
 * registry entry, a field or a cache of the agent, so that class → class value → adapter → class is
 * a cycle the GC collects with the application's class loader (F-A36, no loader pinned; {@code
 * RegistryClassLoaderLeakTest}, T-AG-062h).
 */
final class Getters {
  /** The type every adapted handle has. */
  static final MethodType OBJECT_GETTER = MethodType.methodType(Object.class, Object.class);

  /** Upper bound of the superclass walk (deep proxy or subclass chains). */
  static final int MAX_DEPTH = 16;

  /** Missing value of a sample (C-4: never 0 for "not measured"). */
  static final long MISSING = -1L;

  private Getters() {}

  /**
   * The class named {@code name} among {@code type} and its superclasses (no class is loaded), or
   * null; only a public class qualifies.
   */
  static Class<?> ancestor(Class<?> type, String name) {
    Class<?> c = type;
    for (int depth = 0; c != null && depth < MAX_DEPTH; depth++) {
      if (c.getName().equals(name)) {
        return Modifier.isPublic(c.getModifiers()) ? c : null;
      }
      c = c.getSuperclass();
    }
    return null;
  }

  /**
   * The public no-argument method {@code name} of the public type {@code owner}, typed {@code
   * (owner)R}; null when there is none (another library version) or it is not accessible.
   */
  static MethodHandle method(Class<?> owner, String name) {
    if (owner == null || !Modifier.isPublic(owner.getModifiers())) {
      return null;
    }
    try {
      Method method = owner.getMethod(name);
      return lookupIn(owner)
          .findVirtual(owner, name, MethodType.methodType(method.getReturnType()));
    } catch (NoSuchMethodException | IllegalAccessException | RuntimeException | LinkageError e) {
      return null;
    }
  }

  /**
   * The public lookup moved to {@code owner} (still public access only), so that the JVM resolves
   * the member on behalf of {@code owner}'s class loader. A plain {@link
   * MethodHandles#publicLookup()} resolves on behalf of {@code Object} (the boot loader): on Java 8
   * that records a loader constraint for the member's types, and the same library in a second class
   * loader (another web application, a redeploy) then fails with a {@code LinkageError} ("loader
   * constraint violation"), so its values would be missing (found in WO-13, {@code
   * PoolAdviceTest}).
   */
  private static MethodHandles.Lookup lookupIn(Class<?> owner) {
    return MethodHandles.publicLookup().in(owner);
  }

  /**
   * The public field {@code name} of the public type {@code owner} (declared there or inherited),
   * as a getter typed {@code (owner)T}; null when there is none or it is not accessible.
   */
  static MethodHandle field(Class<?> owner, String name) {
    if (owner == null || !Modifier.isPublic(owner.getModifiers())) {
      return null;
    }
    try {
      Field field = owner.getField(name);
      return lookupIn(owner).findGetter(owner, name, field.getType());
    } catch (NoSuchFieldException | IllegalAccessException | RuntimeException | LinkageError e) {
      return null;
    }
  }

  /**
   * {@code first} followed by the public no-argument method {@code name} of its return type: a
   * getter of a getter's value (Hikari's {@code config.getMaximumPoolSize()}, Tomcat JDBC's {@code
   * getPoolProperties().getMaxActive()}). Null when either is missing.
   */
  static MethodHandle then(MethodHandle first, String name) {
    if (first == null) {
      return null;
    }
    MethodHandle second = method(first.type().returnType(), name);
    if (second == null) {
      return null;
    }
    try {
      return MethodHandles.filterReturnValue(first, second);
    } catch (RuntimeException e) {
      return null;
    }
  }

  /** {@code handle} adapted to {@code (Object)Object} (primitives boxed); null for null. */
  static MethodHandle adapt(MethodHandle handle) {
    if (handle == null) {
      return null;
    }
    try {
      return handle.asType(OBJECT_GETTER);
    } catch (RuntimeException e) {
      return null;
    }
  }

  /**
   * Invokes an adapted getter on {@code target}. Throws what the getter throws (the caller counts
   * it); a target of another class throws {@link ClassCastException}.
   */
  static Object get(MethodHandle getter, Object target) throws Throwable {
    return (Object) getter.invokeExact(target);
  }

  /**
   * Identity of two references: the registries match objects, classes and class loaders by
   * identity, never by {@code equals} (an application's {@code equals} is not called).
   */
  @SuppressWarnings("ReferenceEquality") // identity semantics by design
  static boolean same(Object a, Object b) {
    return a == b;
  }

  /** A sample value: a non-negative number, or {@link #MISSING} for anything else. */
  static long number(Object value) {
    if (value instanceof Number) {
      long n = ((Number) value).longValue();
      return n >= 0 ? n : MISSING;
    }
    return MISSING;
  }

  /** The simple name of {@code type} for generated names ({@code <SimpleName>-<n>}). */
  static String simpleName(Class<?> type) {
    String simple = type.getSimpleName();
    if (simple != null && !simple.isEmpty()) {
      return simple;
    }
    String name = type.getName();
    int dot = name.lastIndexOf('.');
    return dot >= 0 ? name.substring(dot + 1) : name;
  }

  /** {@code text} trimmed and cut to {@code max} characters, or null when empty. */
  static String cut(String text, int max) {
    if (text == null) {
      return null;
    }
    String trimmed = text.trim();
    if (trimmed.isEmpty()) {
      return null;
    }
    return trimmed.length() > max ? trimmed.substring(0, max) : trimmed;
  }
}
