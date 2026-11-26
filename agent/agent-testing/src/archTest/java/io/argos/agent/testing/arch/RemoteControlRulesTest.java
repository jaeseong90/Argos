/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.arch;

import static io.argos.agent.testing.arch.Fixtures.COMPLIANT;
import static io.argos.agent.testing.arch.Fixtures.VIOLATING;
import static io.argos.agent.testing.arch.Fixtures.assertPassesOnCompliant;
import static io.argos.agent.testing.arch.Fixtures.assertViolation;
import static io.argos.agent.testing.arch.Fixtures.classes;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import io.argos.agent.testing.arch.fixture.violating.core.command.ScriptedCommand;
import io.argos.agent.testing.arch.fixture.violating.core.config.PluginKeys;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * ST-91-1 (16 §1.4, DSN-16-05): the agent's command and remote-config code cannot turn a server
 * message into code. The rule fails on the violating fixtures, passes on the compliant agent, and
 * holds for the real {@code io.argos.agent.core.command} and {@code io.argos.agent.core.config}
 * packages (also part of {@link AgentArchitectureTest}).
 */
@Tag("req:SEC-91")
class RemoteControlRulesTest {

  @Test
  @DisplayName(
      "T-SEC-91a SEC-91 [ST-91-1] rejects class loading, method handle lookup, scripts, processes"
          + " and member enumeration in a command handler")
  void rejectsDynamicCodeInCommands() {
    String type = ScriptedCommand.class.getName();
    assertViolation(
        RemoteControlRules.noDynamicCodeInRemoteControl(VIOLATING),
        classes(ScriptedCommand.class),
        type + ".load (ScriptedCommand.java:",
        "java.lang.Class.forName name is not a constant (comes from local variable or parameter",
        "java.lang.ClassLoader.loadClass (class loading)",
        "java.lang.Class.getMethod name is not a constant",
        "calls java.lang.Class.getDeclaredMethods",
        "java.lang.invoke.MethodHandles$Lookup.findStatic (method handle lookup)",
        "calls javax.script.ScriptEngineManager.<init>",
        "calls javax.script.ScriptEngine.eval",
        "calls java.lang.Runtime.exec",
        "creates java.lang.ProcessBuilder",
        "calls java.lang.ProcessBuilder.start");
  }

  @Test
  @DisplayName(
      "T-SEC-91a SEC-91 [ST-91-1] rejects a remote-config value used as a class name, also through"
          + " a branch merge")
  void rejectsClassNamesFromConfig() {
    assertViolation(
        RemoteControlRules.noDynamicCodeInRemoteControl(VIOLATING),
        classes(PluginKeys.class),
        PluginKeys.class.getName() + ".apply",
        "java.lang.Class.forName name is not a constant",
        "calls java.lang.Class.newInstance");
  }

  @Test
  @DisplayName(
      "T-SEC-91a SEC-91 [ST-91-1] accepts Class.forName and getMethod with names written in the"
          + " source (THREAD_DUMP's HotSpotDiagnosticMXBean lookup)")
  void acceptsConstantNames() {
    assertPassesOnCompliant(RemoteControlRules.noDynamicCodeInRemoteControl(COMPLIANT));
  }

  @Test
  @DisplayName("T-SEC-91a SEC-91 [ST-91-1] name argument depth of forName and getMethod")
  void nameArgumentDepth() {
    assertThat(RemoteControlRules.nameArgumentDepth("(Ljava/lang/String;)Ljava/lang/Class;"))
        .isZero();
    assertThat(
            RemoteControlRules.nameArgumentDepth(
                "(Ljava/lang/String;ZLjava/lang/ClassLoader;)Ljava/lang/Class;"))
        .isEqualTo(2);
    assertThat(
            RemoteControlRules.nameArgumentDepth(
                "(Ljava/lang/Module;Ljava/lang/String;)Ljava/lang/Class;"))
        .isZero();
    assertThat(
            RemoteControlRules.nameArgumentDepth(
                "(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;"))
        .isEqualTo(1);
    assertThat(RemoteControlRules.nameArgumentDepth("()V")).isEqualTo(-1);
  }

  @Test
  @DisplayName(
      "T-SEC-91a SEC-91 [ST-91-1] the agent's command and config packages have no dynamic class"
          + " loading, lookup or execution API")
  void productionCommandAndConfigPackages() {
    AgentLayout layout = AgentLayout.production();
    JavaClasses production = AgentArchitectureTest.productionClasses();
    // The rule is not vacuous: the dispatcher, the THREAD_DUMP handler with its JDK 21 lookup and
    // the remote-config gate are among the checked classes.
    assertThat(RemoteControlRules.remoteControlPackages(layout))
        .containsExactly("io.argos.agent.core.command", "io.argos.agent.core.config");
    assertThat(production.stream().map(JavaClass::getName))
        .contains(
            "io.argos.agent.core.command.CommandDispatcher",
            "io.argos.agent.core.command.ThreadDumpCommand",
            "io.argos.agent.core.command.ConfigPushApplier",
            "io.argos.agent.core.config.RemoteConfigGate");
    RemoteControlRules.noDynamicCodeInRemoteControl(layout)
        .allowEmptyShould(false)
        .check(production);
  }
}
