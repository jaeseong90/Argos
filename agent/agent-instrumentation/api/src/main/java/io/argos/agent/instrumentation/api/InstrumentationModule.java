/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.api;

import java.util.Collections;
import java.util.List;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.matcher.ElementMatchers;

/**
 * One instrumentation module (10 §5.1): a library family such as {@code servlet} or {@code jdbc}.
 *
 * <p>Discovery (ADR-0003 C-25): the core never references module classes. Each module lists its
 * implementation class in {@code
 * src/main/resources/META-INF/services/io.argos.agent.instrumentation.api.InstrumentationModule}
 * (one binary class name per line); {@code agent-dist} merges these files into {@code
 * inst/META-INF/services/} and the core instantiates every listed class through its public no-arg
 * constructor. Modules are applied in ascending {@link #moduleId()} order.
 */
public interface InstrumentationModule {
  /**
   * Stable module name, also the value of the {@code instrumentation.disabled} setting: {@code
   * servlet}, {@code jdbc}, {@code httpclient-okhttp}, ...
   */
  String id();

  /**
   * Index into {@code ModuleHealth} (0..63), from {@link ModuleIds}. Advice of this module starts
   * with {@code if (Argos.skip(MODULE_ID)) return;} using the same number.
   */
  int moduleId();

  /**
   * Class loaders this module applies to, evaluated once per class loader and cached by the core.
   * Use {@link Witness#hasClassesNamed} (resource lookup, no class loading). Default: every loader.
   */
  default ElementMatcher<ClassLoader> witness() {
    return ElementMatchers.<ClassLoader>any();
  }

  /** The types this module transforms. */
  List<TypeInstrumentation> types();

  /**
   * Binary names of the helper classes the core defines in the target class loader before the first
   * transformation in that loader (10 §5.4), in dependency order (a class after the classes it
   * extends or implements). Helpers reference bootstrap classes, JDK classes and the target library
   * only, and keep no static state. Modules that transform classes of the bootstrap class loader
   * cannot have helpers: their advice uses the bootstrap API only.
   */
  default List<String> helperClassNames() {
    return Collections.emptyList();
  }

  /** Supported library versions for the module README, e.g. {@code "javax.servlet 3.1-4.0"}. */
  default String[] libraryVersionRange() {
    return new String[0];
  }
}
