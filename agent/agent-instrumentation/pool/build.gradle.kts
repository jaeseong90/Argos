// SPDX-License-Identifier: Apache-2.0
//
// Connection pools without JMX (10 §6.4, F-A10, DSN-10-12): the advice registers HikariCP, Tomcat
// JDBC and DBCP2 pools with the core's pool registry. The advice uses the bootstrap API only, so the
// module has no compileOnly library (the pool classes are matched by name).
//
// Tests inline the module's real advice with ByteBuddy into the real pool classes (a child-first
// test class loader, as the agent transforms them at load time) over in-memory H2 and check the
// registry of the real tracer. The Spring Boot 2.7 BOM fixes the Java 8 versions of the libraries
// and their dependencies to versions already verified for the sample apps and agent-core (HikariCP
// 4.0.3, tomcat-jdbc 9.0.83, commons-dbcp2 2.9.0, H2 2.1.214; DBCP2's commons-logging API comes from
// spring-jcl as in the apps). Test classpath only: never shipped (H2 is a dev-only row).

plugins { id("argos.java-agent") }

description = "Argos agent instrumentation: pool."

dependencies {
  compileOnly(project(":agent:agent-bootstrap"))
  implementation(project(":agent:agent-instrumentation:api"))

  testImplementation(project(":agent:agent-bootstrap"))
  // Advice tests run against the real tracer and pool registry.
  testImplementation(project(":agent:agent-core"))
  testImplementation(platform(libs.sample.boot2.bom))
  testImplementation(libs.sample.hikari)
  testImplementation(libs.sample.tomcat.jdbc)
  testImplementation(libs.sample.dbcp2)
  testImplementation(libs.sample.h2)
  testRuntimeOnly(libs.sample.spring.jcl)
}
