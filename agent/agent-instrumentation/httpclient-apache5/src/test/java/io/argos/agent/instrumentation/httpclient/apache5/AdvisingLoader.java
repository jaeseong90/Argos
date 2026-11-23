/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.httpclient.apache5;

import io.argos.agent.instrumentation.api.InstrumentationModule;
import io.argos.agent.instrumentation.api.TypeInstrumentation;
import io.argos.agent.instrumentation.api.TypeTransformer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.pool.TypePool;

/**
 * A child-first class loader that defines the classes of the given packages itself and inlines the
 * advice of a module into every class one of the module's type rules matches, exactly as the agent
 * does at load time (same matchers, same advice classes; {@link TestAdvice} reports a suppressed
 * advice failure to {@code ModuleHealth}). Everything else (JDK, bootstrap API, JUnit, logging,
 * other libraries) comes from the parent, so the advised library classes call the test's tracer.
 */
final class AdvisingLoader extends ClassLoader {
  static {
    registerAsParallelCapable();
  }

  private final String[] childFirst;
  private final InstrumentationModule module;
  private final TypePool types;
  private final ClassFileLocator locator;
  private final List<String> advised = new ArrayList<String>();

  /**
   * Type descriptions of the parent's class files, shared by every loader over the same parent: the
   * bytes they describe are the same, so a second loader (a redeploy) and the next test do not
   * parse the libraries again (ADR-0010 C-186 (4): the unit tests stay under one second).
   */
  private static final Map<ClassLoader, TypePool> TYPE_POOLS =
      new ConcurrentHashMap<ClassLoader, TypePool>();

  AdvisingLoader(ClassLoader parent, InstrumentationModule module, String... childFirstPackages) {
    super(parent);
    this.module = module;
    this.childFirst = childFirstPackages.clone();
    this.locator = ClassFileLocator.ForClassLoader.of(parent);
    this.types = TYPE_POOLS.computeIfAbsent(parent, p -> TypePool.Default.of(locator));
  }

  /** Binary names of the classes this loader advised, in definition order. */
  synchronized List<String> advised() {
    return new ArrayList<String>(advised);
  }

  @Override
  protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
    if (!isChildFirst(name)) {
      return super.loadClass(name, resolve);
    }
    synchronized (getClassLoadingLock(name)) {
      Class<?> loaded = findLoadedClass(name);
      if (loaded == null) {
        loaded = define(name);
      }
      if (resolve) {
        resolveClass(loaded);
      }
      return loaded;
    }
  }

  private boolean isChildFirst(String name) {
    for (String prefix : childFirst) {
      if (name.startsWith(prefix)) {
        return true;
      }
    }
    return false;
  }

  private Class<?> define(String name) throws ClassNotFoundException {
    byte[] bytes = read(name);
    TypeDescription type = types.describe(name).resolve();
    for (TypeInstrumentation rule : module.types()) {
      if (rule.typeMatcher().matches(type)) {
        bytes = advise(type, rule);
        synchronized (this) {
          advised.add(name);
        }
      }
    }
    definePackageOf(name);
    return defineClass(name, bytes, 0, bytes.length);
  }

  /** Libraries read their resource bundles through {@code getClass().getPackage()}. */
  @SuppressWarnings("deprecation") // getPackage(String): Java 8 API, the tests run on Java 8
  private void definePackageOf(String className) {
    int dot = className.lastIndexOf('.');
    if (dot < 0) {
      return;
    }
    String packageName = className.substring(0, dot);
    synchronized (this) {
      if (getPackage(packageName) == null) {
        definePackage(packageName, null, null, null, null, null, null, null);
      }
    }
  }

  private byte[] advise(TypeDescription type, TypeInstrumentation rule) {
    final List<ElementMatcher<? super MethodDescription>> methods =
        new ArrayList<ElementMatcher<? super MethodDescription>>();
    final List<String> advice = new ArrayList<String>();
    rule.transform(
        new TypeTransformer() {
          @Override
          public void applyAdvice(
              ElementMatcher<? super MethodDescription> methodMatcher, String adviceClassName) {
            methods.add(methodMatcher);
            advice.add(adviceClassName);
          }
        });
    DynamicType.Builder<?> builder = new ByteBuddy().redefine(type, locator);
    for (int i = 0; i < methods.size(); i++) {
      try {
        Class<?> adviceClass = Class.forName(advice.get(i), false, getParent());
        builder = builder.visit(TestAdvice.of(adviceClass, module.moduleId()).on(methods.get(i)));
      } catch (ClassNotFoundException e) {
        throw new IllegalStateException(e);
      }
    }
    return builder.make().getBytes();
  }

  private byte[] read(String name) throws ClassNotFoundException {
    InputStream in = getParent().getResourceAsStream(name.replace('.', '/') + ".class");
    if (in == null) {
      throw new ClassNotFoundException(name);
    }
    try {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      byte[] buffer = new byte[8192];
      int read;
      while ((read = in.read(buffer)) != -1) {
        out.write(buffer, 0, read);
      }
      return out.toByteArray();
    } catch (IOException e) {
      throw new ClassNotFoundException(name, e);
    } finally {
      try {
        in.close();
      } catch (IOException ignored) {
        // read already completed or failed
      }
    }
  }
}
