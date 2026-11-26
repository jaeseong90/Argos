// SPDX-License-Identifier: Apache-2.0
//
// Outgoing calls through Apache HttpClient 4.5 (10 §6.5, WO-14): the doExecute gate of the client
// implementations. The library is compileOnly (the application provides it) and on the test class
// path at the version the sample apps use (4.5.14 from the Boot 2 BOM, as in shop-boot2). Its
// commons-logging 1.2 is replaced by Spring's JCL bridge (as in the apps and in the pool module):
// the 1.2 artifacts are not in the verification metadata.

plugins { id("argos.java-agent") }

description = "Argos agent instrumentation: httpclient-apache4."

dependencies {
  compileOnly(project(":agent:agent-bootstrap"))
  implementation(project(":agent:agent-instrumentation:api"))
  compileOnly(platform(libs.sample.boot2.bom))
  compileOnly(libs.sample.hc4) { exclude(group = "commons-logging") }

  testImplementation(project(":agent:agent-bootstrap"))
  // Advice tests run against the real tracer (propagation, HOST dictionary, failure classes).
  testImplementation(project(":agent:agent-core"))
  testImplementation(platform(libs.sample.boot2.bom))
  testImplementation(libs.sample.hc4) { exclude(group = "commons-logging") }
  testRuntimeOnly(libs.sample.spring.jcl)
}

// Lower boundary (WO-50, ADR-0010 C-240 (10)): the same module tests against httpclient 4.5, the
// first 4.5 release (C-240 writes 4.5.0), with the httpcore 4.4.1 it was released with. Both are
// pinned with strictly, so the Boot 2 BOM of the test dependencies cannot move them up.
val lowerBoundLibraries = configurations.dependencyScope("lowerBoundLibraries")

dependencies {
  lowerBoundLibraries(libs.agent.hc4.min) {
    version { strictly(libs.agent.hc4.min.get().version!!) }
    exclude(group = "commons-logging")
  }
  lowerBoundLibraries(libs.agent.hc4.core.min) {
    version { strictly(libs.agent.hc4.core.min.get().version!!) }
  }
}

val testRuntimeLowerBound =
  configurations.resolvable("testRuntimeLowerBound") {
    extendsFrom(
      configurations.testImplementation.get(),
      configurations.testRuntimeOnly.get(),
      lowerBoundLibraries.get(),
    )
    attributes {
      attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
      attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
      attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
      attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.EXTERNAL))
    }
  }

val testLowerBound =
  tasks.register<Test>("testLowerBound") {
    description = "Runs the module tests on JDK 8 against httpclient 4.5 and httpcore 4.4.1."
    group = "verification"
    useJUnitPlatform()
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath =
      sourceSets.test.get().output + sourceSets.main.get().output + testRuntimeLowerBound.get()
    javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(8) }
    // Apache4VersionTest checks that the class path really holds these versions.
    systemProperty("argos.test.hc4.expected", libs.agent.hc4.min.get().version!!)
    systemProperty("argos.test.hc4core.expected", libs.agent.hc4.core.min.get().version!!)
    shouldRunAfter(tasks.named("test"))
  }

tasks.named("check") { dependsOn(testLowerBound) }
