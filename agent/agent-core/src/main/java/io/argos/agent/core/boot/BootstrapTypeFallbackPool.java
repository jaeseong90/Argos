/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.boot;

import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.pool.TypePool;

/**
 * The type pool of the agent builder (ADR-0010 C-256 (1)): ByteBuddy's lazy default pool ({@code
 * PoolStrategy.Default.FAST}) over the class file locator of the instrumented class's loader, with
 * one fallback for a name whose class file the locator cannot find: a type the bootstrap class
 * loader has already defined without a class file.
 *
 * <p>Other Java agents define such types at run time and add them to the classes they transform
 * before Argos sees them. The OpenTelemetry Java agent, for example, adds interfaces such as {@code
 * io.opentelemetry.javaagent.bootstrap.field.VirtualFieldAccessor$java$lang$Runnable$...} to every
 * {@code Runnable}, {@code Callable}, servlet filter or JDBC statement it instruments, and defines
 * them in the bootstrap loader. Without the fallback the safe matchers' hierarchy walk and the
 * context field check cannot resolve them, and ByteBuddy's method graph ({@code REDEFINE_FROZEN},
 * the type strategy before C-256 (1)) failed with {@code NoSuchTypeException}: the class stayed
 * unchanged, and a module with 10 such failures was disabled (T-MX-112, T-MX-113).
 *
 * <p>The fallback asks the bootstrap loader only, without initialisation ({@code
 * Class.forName(name, false, null)}). The pool reaches it only for a name the locator has no class
 * file for, and the locator also reads the bootstrap loader's resources, so the lookup returns a
 * type the bootstrap loader has already defined or fails; it does not load a class of an
 * application loader inside a transformation. A type found this way is described from the loaded
 * class ({@link TypeDescription.ForLoadedType}); every other unresolved name stays unresolved as
 * before (the class stays unchanged and the failure is recorded).
 */
final class BootstrapTypeFallbackPool extends TypePool.Default.WithLazyResolution {

  BootstrapTypeFallbackPool(ClassFileLocator classFileLocator) {
    super(
        TypePool.CacheProvider.Simple.withObjectType(),
        classFileLocator,
        TypePool.Default.ReaderMode.FAST);
  }

  @Override
  protected Resolution doResolve(String name) {
    Resolution resolution = super.doResolve(name);
    if (resolution.isResolved()) {
      return resolution;
    }
    Class<?> loaded = definedByBootstrap(name);
    if (loaded == null) {
      return resolution;
    }
    Resolution fallback = new Resolution.Simple(TypeDescription.ForLoadedType.of(loaded));
    // The unresolved result is already cached (describe(name) reads the cache first): replace it.
    if (cacheProvider instanceof TypePool.CacheProvider.Simple) {
      ((TypePool.CacheProvider.Simple) cacheProvider).getStorage().put(name, fallback);
    }
    return fallback;
  }

  /**
   * The class {@code name} of the bootstrap loader, not initialised, or {@code null} when the
   * bootstrap loader does not know it.
   */
  static Class<?> definedByBootstrap(String name) {
    try {
      return Class.forName(name, false, null);
    } catch (ClassNotFoundException | LinkageError | SecurityException e) {
      return null;
    }
  }
}
