// SPDX-License-Identifier: Apache-2.0
//
// Spring MVC service names (10 §6.2, F-A02, ADR-0004 C-48). The module reads the handler mapping's
// request attribute through the servlet API only, so Spring itself is not a compile dependency.
// Servlet APIs: compileOnly at the lowest supported version and on the test classpath (see the
// servlet module).

plugins { id("argos.java-agent") }

description = "Argos agent instrumentation: spring-webmvc."

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
