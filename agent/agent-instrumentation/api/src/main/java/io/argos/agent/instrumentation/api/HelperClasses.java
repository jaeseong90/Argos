/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Declares the helper classes of a module ({@link InstrumentationModule#helperClassNames()}, 10
 * §5.4) by simple name, without loading them: helpers reference the target library (servlet API,
 * JDBC driver types), which the agent class loader cannot see.
 *
 * <pre>
 * public List&lt;String&gt; helperClassNames() {
 *   return HelperClasses.of(ServletModule.class, "ServletHelperJavax", "ServletHelperJakarta");
 * }
 * </pre>
 *
 * <p>Rules (checked here and by the module checks of {@code archTest}): helpers live in the package
 * of their module, their simple name contains {@code Helper} (ArchUnit recognises helpers by name,
 * B-2), they are listed in dependency order (a helper after the helpers it uses as super types),
 * each once. Helpers are final classes with static methods and no static state; they use the
 * bootstrap API, JDK classes and the target library only.
 */
public final class HelperClasses {
  private HelperClasses() {}

  /**
   * Binary names of {@code simpleNames} in the package of {@code module}, in the given order.
   *
   * @throws IllegalArgumentException when a name is empty, qualified, not a helper name or repeated
   *     (a programming error found by the module's unit test)
   */
  public static List<String> of(
      Class<? extends InstrumentationModule> module, String... simpleNames) {
    String name = module.getName();
    String packagePrefix = name.substring(0, name.lastIndexOf('.') + 1);
    List<String> helpers = new ArrayList<String>(simpleNames.length);
    Set<String> seen = new HashSet<String>();
    for (String simpleName : simpleNames) {
      String problem = problem(simpleName);
      if (problem != null) {
        throw new IllegalArgumentException(
            "helper '" + simpleName + "' of " + name + ": " + problem);
      }
      if (!seen.add(simpleName)) {
        throw new IllegalArgumentException(
            "helper '" + simpleName + "' of " + name + " is listed twice");
      }
      helpers.add(packagePrefix + simpleName);
    }
    return Collections.unmodifiableList(helpers);
  }

  /**
   * Problems of a module's helper list (10 §5.4): helpers outside the module package, names without
   * {@code Helper}, duplicates. Empty when the list is valid.
   */
  public static List<String> problems(InstrumentationModule module) {
    String name = module.getClass().getName();
    String packagePrefix = name.substring(0, name.lastIndexOf('.') + 1);
    List<String> problems = new ArrayList<String>();
    Set<String> seen = new HashSet<String>();
    for (String helper : module.helperClassNames()) {
      if (!helper.startsWith(packagePrefix) || helper.indexOf('.', packagePrefix.length()) >= 0) {
        problems.add(helper + " is not in the module package " + packagePrefix);
        continue;
      }
      String problem = problem(helper.substring(packagePrefix.length()));
      if (problem != null) {
        problems.add(helper + ": " + problem);
      }
      if (!seen.add(helper)) {
        problems.add(helper + " is listed twice");
      }
    }
    return problems;
  }

  private static String problem(String simpleName) {
    if (simpleName == null || simpleName.isEmpty()) {
      return "empty name";
    }
    if (simpleName.indexOf('.') >= 0) {
      return "give the simple name (the helper lives in the module package)";
    }
    String outer = simpleName;
    int nested = outer.indexOf('$');
    if (nested >= 0) {
      outer = outer.substring(0, nested);
    }
    if (!outer.contains("Helper")) {
      return "helper class names contain 'Helper' (10 §5.4, ArchUnit B-2)";
    }
    return null;
  }
}
