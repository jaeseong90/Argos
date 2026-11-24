// SPDX-License-Identifier: Apache-2.0
//
// Trace id injection into the MDC of Logback, Log4j 2 and Log4j 1.2 / reload4j (10 §6.9, WO-18).
// The helpers compile against the SLF4J API (Logback events read the user id through it), the
// Log4j 2 API (StringMap) and Log4j 1.2, all compileOnly at the oldest supported line; no Logback
// type is referenced (EPL/LGPL, dev-only row). At run time the application's classes are used.
//
// Tests: the advice is inlined into the real library classes by a child-first advising class
// loader (AdvisingLoader, the pattern of httpclient-okhttp) against the real tracer; every advising
// loader is a fresh copy of the libraries, so Log4j 2 can be started once per context map type.
// Library versions (03 §3.1, boundary versions; 10 §18 U-13):
// - test: JDK 8, Boot 2.7 BOM line (SLF4J 1.7.36, Logback 1.2.12, Log4j 2.17.2) and Log4j 1.2.17;
// - testModern: JDK 17, Boot 4.1 BOM line (SLF4J 2.0, Logback 1.5.38, Log4j 2.25.5) and reload4j
//   1.2.26 in place of Log4j 1.2.17 (the same packages, so never on one class path).

plugins { id("argos.java-agent") }

description = "Argos agent instrumentation: logging."

dependencies {
  compileOnly(project(":agent:agent-bootstrap"))
  implementation(project(":agent:agent-instrumentation:api"))
  compileOnly(platform(libs.sample.boot2.bom))
  compileOnly(libs.sample.slf4j.api)
  compileOnly(libs.sample.log4j.api)
  compileOnly(libs.sample.log4j1)

  testImplementation(project(":agent:agent-bootstrap"))
  // Advice tests run against the real tracer (ids, user id decisions, counters).
  testImplementation(project(":agent:agent-core"))
  testImplementation(platform(libs.sample.boot2.bom))
  testImplementation(libs.sample.logback.classic)
  testImplementation(libs.sample.log4j.core)
  // Log4j 1.2 API for the fixture; its run time jar is added to `test` only (reload4j in
  // testModern).
  testCompileOnly(libs.sample.log4j1)
}

/** Log4j 1.2.17 at run time of the default test task. */
val log4j1Libraries = configurations.dependencyScope("log4j1Libraries")

dependencies { log4j1Libraries(libs.sample.log4j1) }

/** A resolvable runtime class path: [extends] plus the libraries of [libraries]. */
fun runtimeClasspath(
  name: String,
  libraries: NamedDomainObjectProvider<DependencyScopeConfiguration>,
  vararg extends: Configuration,
) =
  configurations.resolvable(name) {
    extendsFrom(*extends, libraries.get())
    attributes {
      attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
      attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
      attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
      attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.EXTERNAL))
    }
  }

val testRuntimeLog4j1 = runtimeClasspath("testRuntimeLog4j1", log4j1Libraries)

tasks.named<Test>("test") { classpath += testRuntimeLog4j1.get() }

// Boot 4.1 line: the newer BOM wins every version conflict (SLF4J 2, Logback 1.5, Log4j 2.25).
val modernLibraries = configurations.dependencyScope("modernLibraries")

dependencies {
  modernLibraries(platform(libs.sample.boot4.bom))
  modernLibraries(libs.sample.logback.classic)
  modernLibraries(libs.sample.log4j.core)
  modernLibraries(libs.sample.reload4j)
}

val testRuntimeModern =
  runtimeClasspath(
    "testRuntimeModern",
    modernLibraries,
    configurations.testImplementation.get(),
    configurations.testRuntimeOnly.get(),
  )

val testModern =
  tasks.register<Test>("testModern") {
    description =
      "Runs the module tests on JDK 17 against Logback 1.5, Log4j 2.25 and reload4j 1.2.26."
    group = "verification"
    useJUnitPlatform()
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath =
      sourceSets.test.get().output + sourceSets.main.get().output + testRuntimeModern.get()
    javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(17) }
    // HelloTest pins the Java 8 launcher of the default test task.
    filter { excludeTestsMatching("*HelloTest") }
    shouldRunAfter(tasks.named("test"))
  }

tasks.named("check") { dependsOn(testModern) }
