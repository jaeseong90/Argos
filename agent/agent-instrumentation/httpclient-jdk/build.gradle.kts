// SPDX-License-Identifier: Apache-2.0
//
// Outgoing calls through java.net.HttpURLConnection (10 §6.5; WO-02 P-6 point:
// sun.net.www.protocol.http.HttpURLConnection.getInputStream). The target is a JDK class, so the
// module has no compileOnly library. Tests inline the real advice with ByteBuddy into an
// HttpURLConnection subclass and check steps, header injection and failure classes against the
// real tracer.

plugins { id("argos.java-agent") }

description = "Argos agent instrumentation: httpclient-jdk."

dependencies {
  compileOnly(project(":agent:agent-bootstrap"))
  implementation(project(":agent:agent-instrumentation:api"))

  testImplementation(project(":agent:agent-bootstrap"))
  testImplementation(project(":agent:agent-core"))
}
