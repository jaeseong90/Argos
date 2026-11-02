// SPDX-License-Identifier: Apache-2.0
//
// Servlet request boundary (10 §6.1, §7.1): javax.servlet and jakarta.servlet. The servlet APIs are
// compileOnly (the application provides them; checkForbiddenDependencies keeps them out of the
// agent jar) at the lowest supported version (javax 3.1, jakarta 5.0), and on the test classpath
// for the helper tests (dev-only licenses, docs/licenses/dev-only.md).

plugins { id("argos.java-agent") }

description = "Argos agent instrumentation: servlet."

dependencies {
  compileOnly(project(":agent:agent-bootstrap"))
  implementation(project(":agent:agent-instrumentation:api"))
  compileOnly(libs.agent.servlet.javax.api)
  compileOnly(libs.agent.servlet.jakarta.api)

  testImplementation(project(":agent:agent-bootstrap"))
  // Helper tests run against the real tracer (exclusion rules, fallback names, masking).
  testImplementation(project(":agent:agent-core"))
  testImplementation(libs.agent.servlet.javax.api)
  testImplementation(libs.agent.servlet.jakarta.api)
}
