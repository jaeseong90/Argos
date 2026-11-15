// SPDX-License-Identifier: Apache-2.0
//
// Servlet request boundary (10 §6.1, §7.1): javax.servlet and jakarta.servlet. The servlet APIs are
// compileOnly (the application provides them; checkForbiddenDependencies keeps them out of the
// agent jar) at the lowest supported version (javax 3.1, jakarta 5.0), and on the test classpath
// for the helper tests (dev-only licenses, docs/licenses/dev-only.md).
//
// src/servlet4: the javax.servlet 4.0 HttpServletMapping helper (10 §6.1 exit, WO-12), compiled
// on its own against javax.servlet-api 4.0 so that the other javax helpers stay compiled against
// 3.1; its classes go into the module jar next to the main classes (the agent jar's inst/ tree).

plugins { id("argos.java-agent") }

description = "Argos agent instrumentation: servlet."

val servlet4: SourceSet = sourceSets.create("servlet4")

dependencies {
  compileOnly(project(":agent:agent-bootstrap"))
  implementation(project(":agent:agent-instrumentation:api"))
  compileOnly(libs.agent.servlet.javax.api)
  compileOnly(libs.agent.servlet.jakarta.api)
  compileOnly(servlet4.output)
  "servlet4CompileOnly"(libs.agent.servlet.javax.api4)

  testImplementation(project(":agent:agent-bootstrap"))
  // Helper tests run against the real tracer (exclusion rules, fallback names, masking).
  testImplementation(project(":agent:agent-core"))
  testImplementation(servlet4.output)
  // The startAsync hook of the servlet-async module (AsyncHelperJavax/Jakarta.started): the helper
  // tests drive the real hook between the request boundary and the flush end (10 §6.7).
  testImplementation(project(":agent:agent-instrumentation:servlet-async"))
  // Servlet 4.0 at test run time: the javax helpers (compiled against 3.1) and the Servlet 4
  // mapping helper run against the newer API, as on Tomcat 9.
  testImplementation(libs.agent.servlet.javax.api4)
  testImplementation(libs.agent.servlet.jakarta.api)
}

tasks.jar { from(servlet4.output) }
