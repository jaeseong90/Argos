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
