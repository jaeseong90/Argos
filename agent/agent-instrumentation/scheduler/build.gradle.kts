// SPDX-License-Identifier: Apache-2.0
//
// @Scheduled, Quartz and Spring Batch entry points as BATCH transactions (10 §6.8, WO-18). The
// libraries are compileOnly at the oldest supported line (Boot 2 BOM: Spring 5.3.31, Quartz 2.3.2,
// Spring Batch 4.3.10); at run time the application's own classes are used.
//
// Tests: the module is installed with the core's own installer into the test JVM (bootstrap API on
// the boot class path, as with the agent jar, and a tiny -javaagent that only hands out the
// Instrumentation: testAgentJar, the pattern of the executor module), so the advice runs inside the
// real library classes on their own threads (Spring's task scheduler, Quartz worker threads)
// against the real tracer. Library versions (03 §3.1, boundary versions):
// - test: JDK 8, Boot 2 BOM line (Spring 5.3.31, Quartz 2.3.2, Spring Batch 4.3.10);
// - testModern: JDK 17, Boot 3 BOM line (Spring 6.2, Quartz 2.5.2, Spring Batch 5.2.6);
// - testSpring7: JDK 17, Boot 4 BOM line (Spring 7.0), @Scheduled and Quartz tests only (Spring
//   Batch 6 moved its packages and is not supported).
// Quartz's JDBC pools (c3p0, HikariCP-java7) and Spring Batch's Jettison are not needed by the
// tests (RAM job store, no XStream serializer) and are excluded. Spring Batch 4.3's JSR-352 API
// (javax.batch-api, the super interface of its JobInstance) stays.

import org.gradle.api.artifacts.component.ProjectComponentIdentifier

plugins { id("argos.java-agent") }

description = "Argos agent instrumentation: scheduler."

fun ModuleDependency.withoutQuartzPools() {
  exclude(group = "com.mchange")
  exclude(group = "com.zaxxer")
}

fun ModuleDependency.withoutBatchExtras() {
  exclude(group = "org.codehaus.jettison")
}

dependencies {
  compileOnly(project(":agent:agent-bootstrap"))
  implementation(project(":agent:agent-instrumentation:api"))
  compileOnly(platform(libs.sample.boot2.bom))
  compileOnly(libs.sample.spring.context) { isTransitive = false }
  compileOnly(libs.sample.quartz) { isTransitive = false }
  // Transitive: JobInstance of Batch 4.3 implements the JSR-352 javax.batch API.
  compileOnly(libs.sample.spring.batch.core) { withoutBatchExtras() }

  testImplementation(project(":agent:agent-bootstrap"))
  testImplementation(project(":agent:agent-core"))
  testImplementation(platform(libs.sample.boot2.bom))
  testImplementation(libs.sample.spring.context)
  testImplementation(libs.sample.quartz) { withoutQuartzPools() }
  testImplementation(libs.sample.spring.batch.core) { withoutBatchExtras() }
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
      include("io/argos/agent/instrumentation/scheduler/testagent/**")
    }
    manifest {
      attributes(
        "Premain-Class" to
          "io.argos.agent.instrumentation.scheduler.testagent.InstrumentationHolderAgent",
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
  // The fixtures of com.example.jobs name library types: JUnit discovery must not load them before
  // the module is installed (it reads the methods of every class it is handed).
  exclude("com/example/**")
}

/** A resolvable runtime class path: the test dependencies with the libraries of [libraries]. */
fun variantClasspath(name: String, libraries: NamedDomainObjectProvider<DependencyScopeConfiguration>) =
  configurations.resolvable(name) {
    extendsFrom(
      configurations.testImplementation.get(),
      configurations.testRuntimeOnly.get(),
      libraries.get(),
    )
    attributes {
      attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
      attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
      attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
      attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.EXTERNAL))
    }
  }

// Boot 3 line: the newer BOM wins every version conflict (Spring 6.2, Quartz 2.5.2, Batch 5.2.6).
val modernLibraries = configurations.dependencyScope("modernLibraries")

dependencies {
  modernLibraries(platform(libs.sample.boot3.bom))
  modernLibraries(libs.sample.spring.context)
  modernLibraries(libs.sample.quartz) { withoutQuartzPools() }
  modernLibraries(libs.sample.spring.batch.core) { withoutBatchExtras() }
}

val testRuntimeModern = variantClasspath("testRuntimeModern", modernLibraries)

// Boot 4 line: Spring 7.0 and its Quartz; Spring Batch (6.x under Boot 4) is left out.
val spring7Libraries = configurations.dependencyScope("spring7Libraries")

dependencies {
  spring7Libraries(platform(libs.sample.boot4.bom))
  spring7Libraries(libs.sample.spring.context)
  spring7Libraries(libs.sample.quartz) { withoutQuartzPools() }
}

val testRuntimeSpring7 =
  variantClasspath("testRuntimeSpring7", spring7Libraries).apply {
    configure { exclude(group = "org.springframework.batch") }
  }

fun registerVariantTest(
  taskName: String,
  summary: String,
  classpathConfiguration: NamedDomainObjectProvider<ResolvableConfiguration>,
  vararg includes: String,
) =
  tasks.register<Test>(taskName) {
    description = "Runs the module tests on JDK 17 against $summary."
    group = "verification"
    useJUnitPlatform()
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath =
      sourceSets.test.get().output + sourceSets.main.get().output + classpathConfiguration.get()
    javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(17) }
    // HelloTest pins the Java 8 launcher of the default test task.
    filter {
      excludeTestsMatching("*HelloTest")
      for (include in includes) {
        includeTestsMatching(include)
      }
    }
    shouldRunAfter(tasks.named("test"))
  }

val testModern =
  registerVariantTest(
    "testModern",
    "Spring 6.2, Quartz 2.5.2 and Spring Batch 5.2.6",
    testRuntimeModern,
  )

val testSpring7 =
  registerVariantTest(
    "testSpring7",
    "Spring 7.0 (@Scheduled and Quartz tests)",
    testRuntimeSpring7,
    "*ScheduledAdviceTest",
    "*QuartzAdviceTest",
  )

tasks.named("check") { dependsOn(testModern, testSpring7) }
