/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.boot;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.Set;

/**
 * {@code java.lang.Module} operations through reflection (the agent targets Java 8, 10 §4.4,
 * DSN-02-03): read edges and opens via {@code Instrumentation.redefineModule}. On JDK 8 every
 * operation is a no-op ({@link #SUPPORTED} is false).
 */
final class Jpms {
  static final boolean SUPPORTED;

  private static final Method GET_MODULE;
  private static final Method IS_NAMED;
  private static final Method GET_NAME;
  private static final Method CAN_READ;
  private static final Method IS_OPEN;
  private static final Method REDEFINE_MODULE;
  private static final Method IS_MODIFIABLE;

  static {
    Method getModule = null;
    Method isNamed = null;
    Method getName = null;
    Method canRead = null;
    Method isOpen = null;
    Method redefine = null;
    Method modifiable = null;
    try {
      getModule = Class.class.getMethod("getModule");
      Class<?> module = getModule.getReturnType();
      isNamed = module.getMethod("isNamed");
      getName = module.getMethod("getName");
      canRead = module.getMethod("canRead", module);
      isOpen = module.getMethod("isOpen", String.class, module);
      redefine =
          Instrumentation.class.getMethod(
              "redefineModule", module, Set.class, Map.class, Map.class, Set.class, Map.class);
      modifiable = Instrumentation.class.getMethod("isModifiableModule", module);
    } catch (NoSuchMethodException e) {
      getModule = null;
    }
    SUPPORTED = getModule != null;
    GET_MODULE = getModule;
    IS_NAMED = isNamed;
    GET_NAME = getName;
    CAN_READ = canRead;
    IS_OPEN = isOpen;
    REDEFINE_MODULE = redefine;
    IS_MODIFIABLE = modifiable;
  }

  private Jpms() {}

  /** The module of {@code type}, or null on JDK 8. */
  static Object moduleOf(Class<?> type) {
    return SUPPORTED ? invoke(GET_MODULE, type) : null;
  }

  static boolean isNamed(Object module) {
    return module != null && (Boolean) invoke(IS_NAMED, module);
  }

  static String name(Object module) {
    return module == null ? null : (String) invoke(GET_NAME, module);
  }

  static boolean canRead(Object from, Object to) {
    return from == null || to == null || (Boolean) invoke(CAN_READ, from, to);
  }

  static boolean isOpen(Object module, String packageName, Object to) {
    return module == null || to == null || (Boolean) invoke(IS_OPEN, module, packageName, to);
  }

  static boolean isModifiable(Instrumentation inst, Object module) {
    return module != null && (Boolean) invoke(IS_MODIFIABLE, inst, module);
  }

  /** Adds a read edge {@code from -> to} unless it exists. */
  static void addReads(Instrumentation inst, Object from, Object to) {
    if (!SUPPORTED || canRead(from, to)) {
      return;
    }
    invoke(
        REDEFINE_MODULE,
        inst,
        from,
        Collections.singleton(to),
        Collections.emptyMap(),
        Collections.emptyMap(),
        Collections.emptySet(),
        Collections.emptyMap());
  }

  /**
   * Opens {@code packageName} of {@code module} to {@code to}. Always redefines the module: on JDK
   * 9-15 {@code isOpen} reports packages opened by {@code --illegal-access=permit}, whose use
   * prints an "illegal reflective access" warning; an explicit open does not.
   */
  static void addOpens(Instrumentation inst, Object module, String packageName, Object to) {
    if (!SUPPORTED) {
      return;
    }
    invoke(
        REDEFINE_MODULE,
        inst,
        module,
        Collections.emptySet(),
        Collections.emptyMap(),
        Collections.singletonMap(packageName, Collections.singleton(to)),
        Collections.emptySet(),
        Collections.emptyMap());
  }

  private static Object invoke(Method method, Object target, Object... args) {
    try {
      return method.invoke(target, args);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(method.getName() + " failed", e);
    }
  }
}
