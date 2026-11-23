// SPDX-License-Identifier: Apache-2.0
//
// Executor context propagation (10 §6.6, §8). Every target is a JDK class (java.base, retransformed
// by the core) or is matched by name (Spring, Tomcat), so the module has no compileOnly library.
//
// Tests: the module is installed with the core's own installer into the test JVM (the bootstrap
// API on the boot class path, as with the agent jar, and a tiny -javaagent that only hands out the
// Instrumentation: testAgentJar), so the advice runs inside the real JDK classes
// (CompletableFuture, ForkJoinPool, FutureTask, ScheduledThreadPoolExecutor, ...) on real threads
// against the real tracer. The JDK internals the advice relies on are witnessed per JDK (U-12):
// - test: JDK 8 (the Java 8 launcher of argos.java-agent, HelloTest included);
// - testJdk11, testJdk17, testJdk21 (ThreadPerTaskExecutor, virtual threads), testJdk25.

import org.gradle.api.artifacts.component.ProjectComponentIdentifier

plugins { id("argos.java-agent") }

description = "Argos agent instrumentation: executor."

dependencies {
  compileOnly(project(":agent:agent-bootstrap"))
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
      include("io/argos/agent/instrumentation/executor/testagent/**")
    }
    manifest {
      attributes(
        "Premain-Class" to
          "io.argos.agent.instrumentation.executor.testagent.InstrumentationHolderAgent",
        "Can-Retransform-Classes" to "true",
        "Can-Redefine-Classes" to "true",
      )
    }
  }

val testAgentFile = testAgentJar.flatMap { it.archiveFile }

/** JVM arguments of the retransforming tests: bootstrap API on the boot class path, holder agent. */
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

fun registerJdkTest(jdk: Int) =
  tasks.register<Test>("testJdk$jdk") {
    description = "Runs the module tests on JDK $jdk (U-12 witness of the JDK internals)."
    group = "verification"
    useJUnitPlatform()
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(jdk) }
    // HelloTest pins the Java 8 launcher of the default test task.
    exclude("**/HelloTest*")
    shouldRunAfter(tasks.named("test"))
  }

val jdkTests = listOf(11, 17, 21, 25).map { registerJdkTest(it) }

tasks.named("check") { dependsOn(jdkTests) }
