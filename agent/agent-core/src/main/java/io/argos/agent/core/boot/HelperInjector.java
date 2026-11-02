/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.boot;

import io.argos.agent.core.log.AgentLogger;
import io.argos.agent.instrumentation.api.InstrumentationModule;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.security.ProtectionDomain;
import java.util.List;
import net.bytebuddy.utility.JavaModule;

/**
 * Defines a module's helper classes in the class loader of a transformed type, once per (module,
 * loader), before the advice that calls them is inlined (10 §5.4, §4.4).
 *
 * <p>Mechanism (no {@code sun.misc.Unsafe}, no temporary jar, F-A35, DSN-02-02): the class bytes
 * come from the agent jar ({@code inst/**.classdata} through the agent class loader) and are
 * defined with {@code ClassLoader#defineClass} called reflectively. On JDK 9+ the agent first opens
 * {@code java.base/java.lang} to its own unnamed module only ({@code
 * Instrumentation.redefineModule}, U-3). No lock is held while defining: two threads may race on
 * the first transformation in a loader; the loser's {@code LinkageError} (duplicate definition) is
 * ignored when the class exists.
 *
 * <p>Restrictions: the bootstrap loader cannot receive helpers (modules of JDK classes use the
 * bootstrap API only); a helper must not extend the type being transformed (circularity).
 */
final class HelperInjector {
  private final Instrumentation inst;
  private final ClassLoader agentLoader;
  private final LoaderState state;
  private volatile Method defineClass;
  private volatile Method findLoadedClass;
  private volatile boolean javaLangOpened;

  HelperInjector(Instrumentation inst, ClassLoader agentLoader, LoaderState state) {
    this.inst = inst;
    this.agentLoader = agentLoader;
    this.state = state;
  }

  /**
   * Injects the helpers of {@code module} into {@code target} unless done before.
   *
   * @throws IllegalStateException when the helpers cannot be defined; the transformation of the
   *     type then fails and is counted against the module (the class stays unchanged)
   */
  void inject(
      InstrumentationModule module,
      ClassLoader target,
      ProtectionDomain domain,
      JavaModule targetModule) {
    List<String> helpers = module.helperClassNames();
    if (helpers.isEmpty() || state.has(target, LoaderState.HELPERS_INJECTED, module.moduleId())) {
      return;
    }
    if (target == null) {
      throw new IllegalStateException(
          "module "
              + module.id()
              + " declares helper classes but transforms a class of the bootstrap class loader;"
              + " such modules must use the bootstrap API only");
    }
    try {
      Class<?> first = null;
      for (String name : helpers) {
        Class<?> helper = define(target, name, domain);
        if (first == null) {
          first = helper;
        }
      }
      if (first != null && targetModule != null) {
        // A named module (JDK platform modules) must also read the loader's unnamed module that
        // now holds the helpers.
        Object named = targetModule.unwrap();
        if (Jpms.isNamed(named)) {
          Jpms.addReads(inst, named, Jpms.moduleOf(first));
        }
      }
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException(
          "helper injection of module " + module.id() + " into " + target + " failed", e);
    }
    state.add(target, LoaderState.HELPERS_INJECTED, module.moduleId());
    AgentLogger.debug("injected {} helper(s) of {} into {}", helpers.size(), module.id(), target);
  }

  private Class<?> define(ClassLoader target, String name, ProtectionDomain domain)
      throws Exception {
    Method find = findLoadedClassMethod();
    Class<?> existing = (Class<?>) find.invoke(target, name);
    if (existing != null) {
      return existing;
    }
    byte[] bytes = classFile(name);
    try {
      return (Class<?>) defineClassMethod().invoke(target, name, bytes, 0, bytes.length, domain);
    } catch (InvocationTargetException e) {
      Throwable cause = e.getCause();
      if (cause instanceof LinkageError) {
        Class<?> raced = (Class<?>) find.invoke(target, name);
        if (raced != null) {
          return raced;
        }
      }
      throw e;
    }
  }

  private byte[] classFile(String name) throws IOException {
    String resource = name.replace('.', '/') + ".class";
    InputStream in = agentLoader.getResourceAsStream(resource);
    if (in == null) {
      throw new IOException("helper class file not found in the agent jar: " + resource);
    }
    try {
      ByteArrayOutputStream out = new ByteArrayOutputStream(4096);
      byte[] buffer = new byte[4096];
      int n;
      while ((n = in.read(buffer)) >= 0) {
        out.write(buffer, 0, n);
      }
      return out.toByteArray();
    } finally {
      in.close();
    }
  }

  private Method defineClassMethod() throws NoSuchMethodException {
    Method method = defineClass;
    if (method == null) {
      openJavaLang();
      method =
          ClassLoader.class.getDeclaredMethod(
              "defineClass",
              String.class,
              byte[].class,
              int.class,
              int.class,
              ProtectionDomain.class);
      method.setAccessible(true);
      defineClass = method;
    }
    return method;
  }

  private Method findLoadedClassMethod() throws NoSuchMethodException {
    Method method = findLoadedClass;
    if (method == null) {
      openJavaLang();
      method = ClassLoader.class.getDeclaredMethod("findLoadedClass", String.class);
      method.setAccessible(true);
      findLoadedClass = method;
    }
    return method;
  }

  /** JDK 9+: opens java.base/java.lang to the agent class loader's unnamed module only (U-3). */
  private void openJavaLang() {
    if (!Jpms.SUPPORTED || javaLangOpened) {
      return;
    }
    javaLangOpened = true;
    Object javaBase = Jpms.moduleOf(ClassLoader.class);
    Object agentModule = Jpms.moduleOf(HelperInjector.class);
    // Unconditionally: JDK 9-15 report java.lang "open" to unnamed modules under
    // --illegal-access=permit, but using that path prints a warning on stderr.
    Jpms.addOpens(inst, javaBase, "java.lang", agentModule);
    AgentLogger.info(
        "opened java.base/java.lang to the Argos agent class loader (helper injection, 10 §4.4)");
  }
}
