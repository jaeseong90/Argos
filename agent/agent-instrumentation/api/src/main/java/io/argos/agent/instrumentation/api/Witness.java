/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.api;

import java.util.Arrays;
import net.bytebuddy.matcher.ElementMatcher;

/**
 * Class loader matchers for {@link InstrumentationModule#witness()} (10 §5.1, §5.2): they look for
 * class files as resources and never load a class.
 */
public final class Witness {
  private Witness() {}

  /**
   * Matches a class loader that can see every class in {@code classNames} (binary names). The
   * bootstrap loader ({@code null}) is checked through the parent of the system class loader (the
   * platform loader on JDK 9+, the extension loader on JDK 8), which sees JDK classes but not the
   * application class path.
   */
  public static ElementMatcher.Junction<ClassLoader> hasClassesNamed(String... classNames) {
    final String[] resources = new String[classNames.length];
    for (int i = 0; i < classNames.length; i++) {
      resources[i] = classNames[i].replace('.', '/') + ".class";
    }
    final String description = "hasClassesNamed" + Arrays.toString(classNames);
    return new ElementMatcher.Junction.AbstractBase<ClassLoader>() {
      @Override
      public boolean matches(ClassLoader loader) {
        ClassLoader lookup = loader == null ? jdkLoader() : loader;
        if (lookup == null) {
          return false;
        }
        for (String resource : resources) {
          if (lookup.getResource(resource) == null) {
            return false;
          }
        }
        return true;
      }

      @Override
      public String toString() {
        return description;
      }
    };
  }

  private static ClassLoader jdkLoader() {
    ClassLoader system = ClassLoader.getSystemClassLoader();
    return system == null ? null : system.getParent();
  }
}
