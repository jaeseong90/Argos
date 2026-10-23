// SPDX-License-Identifier: Apache-2.0
//
// argos.java-agent: agent-* and instrumentation modules (19 §3.2, DSN-19-02).
// - Compiled by the JDK 21 compiler with --release 8 (main and test sources).
// - Every test task runs on a Java 8 toolchain (auto-provisioned through foojay).
// - Forbidden runtime dependencies (logging frameworks, servlet API/implementations) fail check.
// - agent-bootstrap must have zero dependencies (checked).
// - Every agent module has a README.md (instrumentation modules document their version range).

import io.argos.buildlogic.ArgosProject
import io.argos.buildlogic.CheckForbiddenDependencies
import io.argos.buildlogic.CheckModuleReadme
import io.argos.buildlogic.CheckNoDependencies

plugins { id("argos.java-common") }

tasks.withType<JavaCompile>().configureEach {
  options.release = ArgosProject.AGENT_JAVA_RELEASE
  // JDK 21 javac warns that release 8 is obsolete; that warning would break -Werror and carries
  // no information for us (Java 8 is a product requirement, F-A31).
  options.compilerArgs.add("-Xlint:-options")
}

val agentTestLauncher =
  javaToolchains.launcherFor {
    languageVersion = JavaLanguageVersion.of(ArgosProject.AGENT_JAVA_RELEASE)
  }

tasks.withType<Test>().configureEach { javaLauncher = agentTestLauncher }

val checkForbiddenDependencies =
  tasks.register<CheckForbiddenDependencies>("checkForbiddenDependencies") {
    runtimeRoot = configurations.runtimeClasspath.flatMap { it.incoming.resolutionResult.rootComponent }
  }

val checkModuleReadme =
  tasks.register<CheckModuleReadme>("checkModuleReadme") {
    readme.from(layout.projectDirectory.file("README.md"))
  }

tasks.named("check") { dependsOn(checkForbiddenDependencies, checkModuleReadme) }

if (path == ArgosProject.AGENT_BOOTSTRAP_PATH) {
  val checkNoDependencies =
    tasks.register<CheckNoDependencies>("checkNoDependencies") {
      compileRoot =
        configurations.compileClasspath.flatMap { it.incoming.resolutionResult.rootComponent }
      runtimeRoot =
        configurations.runtimeClasspath.flatMap { it.incoming.resolutionResult.rootComponent }
    }
  tasks.named("check") { dependsOn(checkNoDependencies) }
}
