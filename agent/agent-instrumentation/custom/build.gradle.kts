// SPDX-License-Identifier: Apache-2.0
//
// User-configured method tracing (10 §6.10, F-A14, SEC-25). The targets are application classes
// matched by name at run time, so the module has no compileOnly library. Its matchers (never the
// advice, B-2) read the core's CustomMethodPolicy, which shares the agent class loader with the
// module in the agent jar: agent-core is compileOnly here and nothing new is shipped.
//
// Tests: the module is installed with the core's own installer into the test JVM (the bootstrap
// API on the boot class path, as with the agent jar, and a tiny -javaagent that only hands out the
// Instrumentation: testAgentJar, the executor module's pattern), so the advice runs inside fixture
// classes defined after the install, against the real tracer and the real Retransformer.
// - test: JDK 8 (the Java 8 launcher of argos.java-agent, HelloTest included);
// - testJdk21: the same tests on JDK 21 (hidden lambda classes, jdk.proxyN JDK proxies).

import org.gradle.api.artifacts.component.ProjectComponentIdentifier

plugins { id("argos.java-agent") }

description = "Argos agent instrumentation: custom."

dependencies {
  compileOnly(project(":agent:agent-bootstrap"))
  compileOnly(project(":agent:agent-core"))
  implementation(project(":agent:agent-instrumentation:api"))

  testImplementation(project(":agent:agent-bootstrap"))
  testImplementation(project(":agent:agent-core"))
}

val bootstrapJar: FileCollection =
  configurations.testRuntimeClasspath
    .get()
    .incoming
    .artifactView {
      componentFilter {
        it is ProjectComponentIdentifier && it.projectPath == ":agent:agent-bootstrap"
      }
    }
    .files

val testAgentJar =
  tasks.register<Jar>("testAgentJar") {
    description = "Test-only -javaagent that hands the Instrumentation to the module tests."
    archiveFileName = "instrumentation-holder-agent.jar"
    destinationDirectory = layout.buildDirectory.dir("test-agent")
    from(sourceSets.test.map { it.output }) {
      include("io/argos/agent/instrumentation/custom/testagent/**")
    }
    manifest {
      attributes(
        "Premain-Class" to "io.argos.agent.instrumentation.custom.testagent.InstrumentationHolderAgent",
        "Can-Retransform-Classes" to "true",
        "Can-Redefine-Classes" to "true",
      )
    }
  }

val testAgentFile = testAgentJar.flatMap { it.archiveFile }

/** JVM arguments of the module tests: bootstrap API on the boot class path, holder agent. */
class AgentJvmArgs(
  @get:Classpath val bootstrap: FileCollection,
  @get:InputFile @get:PathSensitive(PathSensitivity.NONE) val agent: Provider<RegularFile>,
) : CommandLineArgumentProvider {
  override fun asArguments(): Iterable<String> =
    listOf(
      "-Xbootclasspath/a:" + bootstrap.singleFile.absolutePath,
      "-javaagent:" + agent.get().asFile.absolutePath,
    )
}

tasks.withType<Test>().configureEach {
  jvmArgumentProviders.add(AgentJvmArgs(bootstrapJar, testAgentFile))
}

val testJdk21 =
  tasks.register<Test>("testJdk21") {
    description = "Runs the module tests on JDK 21 (hidden lambda classes, jdk.proxyN proxies)."
    group = "verification"
    useJUnitPlatform()
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(21) }
    // HelloTest pins the Java 8 launcher of the default test task.
    exclude("**/HelloTest*")
    shouldRunAfter(tasks.named("test"))
  }

tasks.named("check") { dependsOn(testJdk21) }
