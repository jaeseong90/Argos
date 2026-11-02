// SPDX-License-Identifier: Apache-2.0
//
// Executor context propagation (10 §6.6; WO-02 P-6 point: java.util.concurrent.ThreadPoolExecutor).
// The target is a JDK class, so the module has no compileOnly library. Tests inline the real advice
// with ByteBuddy into a class with the ThreadPoolExecutor hook signatures and check propagation
// across real threads against the real tracer.

plugins { id("argos.java-agent") }

description = "Argos agent instrumentation: executor."

dependencies {
  compileOnly(project(":agent:agent-bootstrap"))
  implementation(project(":agent:agent-instrumentation:api"))

  testImplementation(project(":agent:agent-bootstrap"))
  testImplementation(project(":agent:agent-core"))
}
