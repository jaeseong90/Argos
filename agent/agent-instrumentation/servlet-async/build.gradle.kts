// SPDX-License-Identifier: Apache-2.0
//
// Servlet async hooks (10 §6.7): Tomcat's startAsync and AsyncContext.start, matched by name. The
// servlet APIs are compileOnly at the lowest supported version (javax 3.1, jakarta 5.0; the
// application's own classes are used at run time, checkForbiddenDependencies keeps them out of the
// agent jar) and on the test classpath for the helper tests (dev-only licenses,
// docs/licenses/dev-only.md).

plugins { id("argos.java-agent") }

description = "Argos agent instrumentation: servlet-async."

dependencies {
  compileOnly(project(":agent:agent-bootstrap"))
  implementation(project(":agent:agent-instrumentation:api"))
  compileOnly(libs.agent.servlet.javax.api)
  compileOnly(libs.agent.servlet.jakarta.api)

  testImplementation(project(":agent:agent-bootstrap"))
  // Helper tests run against the real tracer.
  testImplementation(project(":agent:agent-core"))
  testImplementation(libs.agent.servlet.javax.api)
  testImplementation(libs.agent.servlet.jakarta.api)
}
