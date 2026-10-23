// SPDX-License-Identifier: Apache-2.0
//
// Agent test harness (18 §5): AppLauncher, FakeCollector, assertion DSL, transform baselines,
// matrix runner. It is never shipped, so it runs on the JDK 21 test-harness toolchain
// (DSN-19-02) and launches sample apps on other JDKs as separate processes.
//
// ArchUnit rules over all agent modules (19 §5.3, 10 §2 B-1..B-4, 10 §5.3) live in src/archTest:
// - the rules run against the classes of every agent module (project dependencies below);
// - src/archFixtures holds compliant and violating agent-like classes, compiled exactly like agent
//   code (--release 8), so that every rule is shown to fail on a violation (T-AG-001..004).
//   Fixtures are test-only and never packaged.

import io.argos.buildlogic.AgentShading
import io.argos.buildlogic.ArgosProject
import io.argos.buildlogic.argosArchivesName

plugins {
  id("argos.java-common")
  id("argos.integration-test")
}

description = "Argos agent test harness (launcher, fake collector, assertions, matrix)."

val archFixtures = sourceSets.create("archFixtures")

tasks.named<JavaCompile>(archFixtures.compileJavaTaskName) {
  options.release = ArgosProject.AGENT_JAVA_RELEASE
  // JDK 21 javac warns that release 8 is obsolete (see argos.java-agent).
  options.compilerArgs.add("-Xlint:-options")
}

// ByteBuddy's class files carry SpotBugs annotations whose (LGPL) jar is not on the classpath;
// javac's classfile lint reports each of them when it reads advice annotations, so that lint
// category is off for the code that compiles against the fixtures.
listOf(archFixtures.compileJavaTaskName, "compileArchTestJava").forEach {
  tasks.named<JavaCompile>(it) { options.compilerArgs.add("-Xlint:-classfile") }
}

// Every agent module whose classes the rules scan (single list: settings.gradle.kts).
val agentModulePaths =
  listOf(ArgosProject.AGENT_BOOTSTRAP_PATH, ":agent:agent-core") +
    rootProject.project(":agent:agent-instrumentation").childProjects.values.map { it.path }.sorted()

dependencies {
  // Advice annotations for the fixtures (provided by the agent jar at runtime; test-only here).
  "archFixturesCompileOnly"(libs.bytebuddy)

  "archTestImplementation"(archFixtures.output)
  "archTestImplementation"(libs.asm)
  "archTestImplementation"(libs.asm.tree)
  // Lets ArchUnit resolve Advice annotation types (default attribute values).
  "archTestRuntimeOnly"(libs.bytebuddy)
  agentModulePaths.forEach { "archTestImplementation"(project(it)) }
}

tasks.named<Test>("archTest") {
  val modules = agentModulePaths.map { rootProject.project(it).argosArchivesName() }
  systemProperty("argos.archunit.agentModules", modules.joinToString(","))
  systemProperty("argos.archunit.relocatedPackages", AgentShading.RELOCATED_PACKAGES.joinToString(","))
  // Gradle names of the instrumentation modules: the module column of the allow-list.
  val instrumentation = rootProject.project(":agent:agent-instrumentation")
  systemProperty("argos.archunit.instrumentationModules", instrumentation.childProjects.keys.sorted().joinToString(","))
  // Read by the test that checks each module name against the package of its sources.
  systemProperty("argos.archunit.instrumentationDir", instrumentation.projectDir.absolutePath)
  // 10 §5.3 A-5: readOnly=false / AssignReturned allow-list.
  val allowlist =
    layout.settingsDirectory.file("agent/agent-instrumentation/src/main/resources/argos-advice-allowlist.txt")
  inputs.file(allowlist).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("adviceAllowlist")
  systemProperty("argos.archunit.adviceAllowlist", allowlist.asFile.absolutePath)
}
