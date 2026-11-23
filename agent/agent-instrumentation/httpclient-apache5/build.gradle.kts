// SPDX-License-Identifier: Apache-2.0
//
// Outgoing calls through Apache HttpClient 5 classic (10 §6.5, WO-14): the doExecute gate of the
// classic client implementations; the async client is 1.x. The library is compileOnly at 5.5.2 (the
// version of shop-boot3); `test` runs the module tests against 5.5.2 and `testHc5Latest` runs the
// same test classes against 5.6.5 (shop-boot4), the two versions already in the verification
// metadata (03 §3.1).

plugins { id("argos.java-agent") }

description = "Argos agent instrumentation: httpclient-apache5."

dependencies {
  compileOnly(project(":agent:agent-bootstrap"))
  implementation(project(":agent:agent-instrumentation:api"))
  compileOnly(libs.agent.hc5)

  testImplementation(project(":agent:agent-bootstrap"))
  // Advice tests run against the real tracer (propagation, HOST dictionary, failure classes).
  testImplementation(project(":agent:agent-core"))
  testImplementation(libs.agent.hc5)
}

// The newer library line: the test dependencies plus httpclient5 5.6.5 (wins the version conflict).
val hc5Latest = configurations.dependencyScope("hc5Latest")
val testRuntimeHc5Latest =
  configurations.resolvable("testRuntimeHc5Latest") {
    extendsFrom(configurations.testImplementation.get(), configurations.testRuntimeOnly.get(), hc5Latest.get())
    attributes {
      attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
      attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
      attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
      attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.EXTERNAL))
    }
  }

dependencies { hc5Latest(libs.agent.hc5.latest) }

val testHc5Latest =
  tasks.register<Test>("testHc5Latest") {
    description = "Runs the module tests against httpclient5 5.6.5."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().output + sourceSets.main.get().output + testRuntimeHc5Latest.get()
    useJUnitPlatform()
    shouldRunAfter(tasks.test)
  }

tasks.check { dependsOn(testHc5Latest) }
