// SPDX-License-Identifier: Apache-2.0
//
// Outgoing calls through the Reactor Netty HttpClient behind Spring's WebClient (10 §6.5, F-A33,
// WO-14): internals of reactor.netty.http.client.HttpClientConnect, guarded by a witness that
// checks the methods and fields the advice uses (10 §18 U-9). The library is compileOnly at 1.2.18
// (shop-boot3); `test` runs the module tests against 1.2.18 (Netty 4.1) and `testReactorNetty13`
// runs the same test classes against 1.3.8 (shop-boot4, Netty 4.2), the versions already in the
// verification metadata (03 §3.1). Each version's POM pins its Netty and Reactor Core versions.

plugins { id("argos.java-agent") }

description = "Argos agent instrumentation: httpclient-reactornetty."

dependencies {
  compileOnly(project(":agent:agent-bootstrap"))
  implementation(project(":agent:agent-instrumentation:api"))
  compileOnly(libs.agent.reactor.netty.http)

  testImplementation(project(":agent:agent-bootstrap"))
  // Advice tests run against the real tracer (propagation, HOST dictionary, failure classes).
  testImplementation(project(":agent:agent-core"))
  testImplementation(libs.agent.reactor.netty.http)
}

// Reactor Netty 1.3: the test dependencies plus reactor-netty-http 1.3.8 (wins over 1.2.18).
val reactorNetty13 = configurations.dependencyScope("reactorNetty13")
val testRuntimeReactorNetty13 =
  configurations.resolvable("testRuntimeReactorNetty13") {
    extendsFrom(
      configurations.testImplementation.get(),
      configurations.testRuntimeOnly.get(),
      reactorNetty13.get(),
    )
    attributes {
      attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
      attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
      attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
      attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.EXTERNAL))
    }
  }

dependencies { reactorNetty13(libs.agent.reactor.netty.http.latest) }

val testReactorNetty13 =
  tasks.register<Test>("testReactorNetty13") {
    description = "Runs the module tests against reactor-netty-http 1.3.8."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath =
      sourceSets.test.get().output + sourceSets.main.get().output + testRuntimeReactorNetty13.get()
    useJUnitPlatform()
    shouldRunAfter(tasks.test)
  }

tasks.check { dependsOn(testReactorNetty13) }
