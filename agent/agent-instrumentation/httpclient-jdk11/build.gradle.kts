// SPDX-License-Identifier: Apache-2.0
//
// Outgoing calls through java.net.http.HttpClient (10 §6.5). The targets are JDK 11+ classes of
// the java.net.http module (platform class loader), so the module has no compileOnly library.
//
// Compilation (ADR-0003 open point, WO-14 assumption (a)): `--release 8` hides java.net.http, which
// the request wrapper helper extends (10 §6.5 `extends HttpRequest`). This module is therefore
// compiled by the build JDK's compiler with `-source 8 -target 8` and no `--release` (class files
// stay major 52, DSN-19-02). A JDK 11 compile toolchain is not used because Error Prone
// (argos.java-common) runs inside javac and needs the JDK 21 compiler. Guards against using more
// than the JDK 8 API plus the JDK 11 java.net.http API:
// - testJdk8 (JDK 8): the bytecode check (Jdk11ModuleOnJdk8Test) allows references outside the JDK 8
//   API only to java.net.http / jdk.internal.net.http, only from advice and helper classes, and
//   pins that the module's witness fails on JDK 8 (the module stays disabled there);
// - test (JDK 11, the oldest java.net.http) and testJdk21 run every advice and helper path, so a
//   member newer than JDK 11 fails with a linkage error.
// The advice and helper tests retransform the real JDK classes with the core's installer: the
// test JVM gets the bootstrap API on the boot class path (as the agent jar does) and a tiny
// -javaagent that only hands out the Instrumentation (testAgentJar).

import org.gradle.api.artifacts.component.ProjectComponentIdentifier

plugins { id("argos.java-agent") }

description = "Argos agent instrumentation: httpclient-jdk11."

dependencies {
  compileOnly(project(":agent:agent-bootstrap"))
  implementation(project(":agent:agent-instrumentation:api"))

  testImplementation(project(":agent:agent-bootstrap"))
  testImplementation(project(":agent:agent-core"))
}

tasks.withType<JavaCompile>().configureEach {
  // No --release (see above); -Xlint:-options from argos.java-agent silences the -source 8 notes.
  options.release.set(null as Int?)
  sourceCompatibility = "1.8"
  targetCompatibility = "1.8"
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
      include("io/argos/agent/instrumentation/httpclient/jdk11/testagent/**")
    }
    manifest {
      attributes(
        "Premain-Class" to
          "io.argos.agent.instrumentation.httpclient.jdk11.testagent.InstrumentationHolderAgent",
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

fun Test.retransformingTests(jdk: Int) {
  javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(jdk) }
  exclude("**/*OnJdk8*")
  jvmArgumentProviders.add(AgentJvmArgs(bootstrapJar, testAgentFile))
}

tasks.named<Test>("test") { retransformingTests(11) }

val testJdk21 =
  tasks.register<Test>("testJdk21") {
    description = "Runs the module tests on JDK 21 (the main target of shop-boot3)."
    group = "verification"
    useJUnitPlatform()
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    retransformingTests(21)
    shouldRunAfter(tasks.named("test"))
  }

val testJdk8 =
  tasks.register<Test>("testJdk8") {
    description = "Runs the JDK 8 checks: witness fails, references outside JDK 8 only java.net.http."
    group = "verification"
    useJUnitPlatform()
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    // The Java 8 launcher of argos.java-agent.
    include("**/*OnJdk8*")
    shouldRunAfter(tasks.named("test"))
  }

tasks.named("check") { dependsOn(testJdk21, testJdk8) }
