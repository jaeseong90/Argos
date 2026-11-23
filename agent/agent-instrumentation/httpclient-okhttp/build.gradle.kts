// SPDX-License-Identifier: Apache-2.0
//
// Outgoing calls through OkHttp 3 and 4 (10 §6.5, WO-14): an application interceptor added to every
// OkHttpClient.Builder and the calling transaction attached to each Call when it is created. The
// library is compileOnly at 3.14.9 (shop-boot2); `test` runs the module tests against 3.14.9 and
// `testOkHttp4` runs the same test classes against 4.12.0 (shop-boot3/4; its Kotlin standard
// library is managed by the Boot 3 BOM as in shop-boot3), the versions already in the verification
// metadata (03 §3.1).

plugins { id("argos.java-agent") }

description = "Argos agent instrumentation: httpclient-okhttp."

dependencies {
  compileOnly(project(":agent:agent-bootstrap"))
  implementation(project(":agent:agent-instrumentation:api"))
  compileOnly(libs.sample.okhttp3)

  testImplementation(project(":agent:agent-bootstrap"))
  // Advice tests run against the real tracer (propagation, HOST dictionary, failure classes).
  testImplementation(project(":agent:agent-core"))
  testImplementation(libs.sample.okhttp3)
}

// OkHttp 4: the test dependencies plus okhttp 4.12.0 (wins the version conflict over 3.14.9).
val okhttp4 = configurations.dependencyScope("okhttp4")
val testRuntimeOkHttp4 =
  configurations.resolvable("testRuntimeOkHttp4") {
    extendsFrom(configurations.testImplementation.get(), configurations.testRuntimeOnly.get(), okhttp4.get())
    attributes {
      attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
      attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
      attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
      attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.EXTERNAL))
    }
  }

dependencies {
  okhttp4(platform(libs.sample.boot3.bom))
  okhttp4(libs.sample.okhttp4)
}

val testOkHttp4 =
  tasks.register<Test>("testOkHttp4") {
    description = "Runs the module tests against OkHttp 4.12.0."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().output + sourceSets.main.get().output + testRuntimeOkHttp4.get()
    useJUnitPlatform()
    shouldRunAfter(tasks.test)
  }

tasks.check { dependsOn(testOkHttp4) }
