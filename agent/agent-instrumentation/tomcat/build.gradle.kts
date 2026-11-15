// SPDX-License-Identifier: Apache-2.0
//
// Tomcat connectors without JMX (10 §6.4, F-A10, DSN-10-12): AbstractEndpoint.start() exit
// registers the started endpoint with the core's Tomcat registry. The advice uses the bootstrap API
// only, so the module has no compileOnly library (the endpoint class is matched by name).
//
// Tests inline the module's real advice with ByteBuddy into the real Tomcat 9 AbstractEndpoint (a
// child-first test class loader, as the agent transforms it at load time) and start a NioEndpoint
// subclass whose bind() and startInternal() do nothing (unit tests open no socket, 18 §2.2); the
// started connectors of the sample apps are the integration tests' (agent-testing jdbc package).
// tomcat-embed-core 9.0.83 comes from the Spring Boot 2.7 BOM (Java 8, already verified for
// agent-core); Tomcat's annotation API is not needed. Test classpath only: never shipped.

plugins { id("argos.java-agent") }

description = "Argos agent instrumentation: tomcat."

dependencies {
  compileOnly(project(":agent:agent-bootstrap"))
  implementation(project(":agent:agent-instrumentation:api"))

  testImplementation(project(":agent:agent-bootstrap"))
  // Advice tests run against the real tracer and Tomcat registry.
  testImplementation(project(":agent:agent-core"))
  testImplementation(platform(libs.sample.boot2.bom))
  testImplementation(libs.sample.tomcat.embed.core) {
    exclude(group = "org.apache.tomcat", module = "tomcat-annotations-api")
  }
}
