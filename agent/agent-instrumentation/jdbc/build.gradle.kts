// SPDX-License-Identifier: Apache-2.0
//
// JDBC (10 §6.3, WO-02 PoC scope: prepareStatement and execute). The JDBC API is part of the JDK,
// so the module has no compileOnly library. Tests apply the real advice with ByteBuddy to generated
// JDBC classes (pool-proxy-over-driver nesting) and check the steps of the real tracer.

plugins { id("argos.java-agent") }

description = "Argos agent instrumentation: jdbc."

dependencies {
  compileOnly(project(":agent:agent-bootstrap"))
  implementation(project(":agent:agent-instrumentation:api"))

  testImplementation(project(":agent:agent-bootstrap"))
  // Advice tests run against the real tracer (SQL normalization, dictionary, steps).
  testImplementation(project(":agent:agent-core"))
}
