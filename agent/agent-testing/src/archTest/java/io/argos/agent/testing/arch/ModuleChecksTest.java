/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.arch;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.instrumentation.api.InstrumentationModule;
import io.argos.agent.testing.arch.modulefixture.BadModule;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * Instrumentation module consistency (10 §5.1-§5.4, C-25) for every module of the agent, and the
 * proof on a violating fixture that each check fails.
 */
class ModuleChecksTest {
  private static final ClassLoader LOADER = ModuleChecksTest.class.getClassLoader();

  static List<InstrumentationModule> productionModules() throws ReflectiveOperationException {
    List<InstrumentationModule> modules = new ArrayList<>();
    for (String name : ModuleChecks.indexedModules(LOADER)) {
      if (name.startsWith(AgentLayout.PRODUCTION_ROOT + ".instrumentation.")) {
        modules.add(
            Class.forName(name, true, LOADER)
                .asSubclass(InstrumentationModule.class)
                .getConstructor()
                .newInstance());
      }
    }
    return modules;
  }

  @TestFactory
  @Tag("req:F-A35")
  Stream<DynamicTest> everyModuleIsConsistent() throws ReflectiveOperationException {
    List<String> gradleModules = AgentLayout.production().instrumentationModules();
    List<InstrumentationModule> modules = productionModules();
    return modules.stream()
        .map(
            module ->
                DynamicTest.dynamicTest(
                    "10 §5.1-§5.4 module " + module.id() + ": index, id, helpers, advice",
                    () ->
                        assertThat(ModuleChecks.problems(module, LOADER, gradleModules))
                            .isEmpty()));
  }

  @Test
  @DisplayName("module ids are unique; the servlet and spring-webmvc modules are indexed (WO-02)")
  void moduleIdsAreUnique() throws ReflectiveOperationException {
    Set<Integer> ids = new HashSet<>();
    Set<String> names = new HashSet<>();
    for (InstrumentationModule module : productionModules()) {
      assertThat(ids.add(module.moduleId())).as("module id %s", module.moduleId()).isTrue();
      assertThat(names.add(module.id())).as("module %s", module.id()).isTrue();
    }
    assertThat(names).contains("servlet", "spring-webmvc");
  }

  @Test
  @DisplayName("every check rejects the violating fixture module")
  void violatingModule() {
    String p = BadModule.class.getPackage().getName() + ".";
    List<String> problems =
        ModuleChecks.problems(new BadModule(), LOADER, List.of("servlet", "spring-webmvc"));
    assertThat(problems)
        .anySatisfy(m -> assertThat(m).contains("is not listed in a META-INF/services"))
        .anySatisfy(m -> assertThat(m).contains("'no-such-module' is not an instrumentation"))
        .anySatisfy(m -> assertThat(m).contains("moduleId() 61 is outside the production range"))
        .anySatisfy(m -> assertThat(m).contains("other.pkg.OtherHelper is not in the module"))
        .anySatisfy(m -> assertThat(m).contains("helper " + p + "MissingHelper has no class file"))
        .anySatisfy(m -> assertThat(m).contains(p + "StatefulHelper has static state calls"))
        .anySatisfy(
            m ->
                assertThat(m)
                    .contains(
                        p + "SubHelper extends " + p + "StatefulHelper, which is not a helper"))
        .anySatisfy(m -> assertThat(m).contains(p + "NotAnnotatedAdvice has no @Advice"))
        .anySatisfy(m -> assertThat(m).contains(p + "MisnamedInterceptor does not end with"))
        .anySatisfy(m -> assertThat(m).contains("advice " + p + "GoneAdvice has no class file"));
    assertThat(problems).noneMatch(m -> m.contains("NAME"));
  }
}
