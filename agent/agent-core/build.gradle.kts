// SPDX-License-Identifier: Apache-2.0
//
// Agent core, loaded from inst/**.classdata by AgentClassLoader (10 §2, §4.2).
// agent-bootstrap is compileOnly: at runtime its classes come from the bootstrap class loader.
// The instrumentation api (and through it ByteBuddy) is the only instrumentation project the core
// may reference (10 §2 B-3); modules are discovered at runtime (ADR-0003 C-25).
// JCTools (Apache-2.0) provides the bounded MPSC queues of 10 §10.1 (atomic variant, no Unsafe);
// agent-dist shades it under io.argos.agent.shaded.org.jctools and minimize() keeps only the
// classes the queues reach.
// The gRPC transport (WO-11, 10 §10.2-§10.4, ADR-0005, ADR-0009 C-72/C-73) compiles against the
// generated argos.v1 code of :proto (ADR-0010 C-104), grpc-netty and the agent's Netty 4.1 line.
// They are compileOnly here and runtime dependencies of :agent:agent-dist, which ships them
// (relocated): projects that put agent-core on their class path for other reasons (the
// instrumentation module tests, the archTest scan, the WO-02 transport comparison in
// test/agent/transport-poc with its own snapshot protos, protobuf flavours and Netty lines) must
// not inherit a protocol or a Netty version from the core.
// src/integrationTest: T-ACC-11 (10 million tx_ids) and the forked-JVM heap budget test (N-02).

plugins {
  id("argos.java-agent")
  id("argos.integration-test")
}

description = "Argos agent core (transactions, statistics, transport, configuration, commands)."

dependencies {
  compileOnly(project(":agent:agent-bootstrap"))
  implementation(project(":agent:agent-instrumentation:api"))
  implementation(libs.jctools.core)

  // Transport (shipped by :agent:agent-dist, see above).
  compileOnly(project(":proto"))
  compileOnly(enforcedPlatform(libs.agent.netty.bom))
  compileOnly(libs.grpc.netty)

  // Unit and integration tests run on Java 8 with the bootstrap classes on the class path.
  testImplementation(project(":agent:agent-bootstrap"))
  "integrationTestImplementation"(project(":agent:agent-bootstrap"))
  // The transport's unit tests run against the shipped stack (Netty 4.1, C-73) on Java 8.
  testImplementation(project(":proto"))
  testImplementation(enforcedPlatform(libs.agent.netty.bom))
  testImplementation(libs.grpc.netty)
  // SessionFlowControlIntegrationTest: the real stack against a raw gRPC server on the loopback.
  "integrationTestImplementation"(project(":proto"))
  "integrationTestImplementation"(enforcedPlatform(libs.agent.netty.bom))
  "integrationTestImplementation"(libs.grpc.netty)
  // WO-13 registry tests (core.metric): real pools (HikariCP, Tomcat JDBC, DBCP2) over in-memory H2
  // and a real Tomcat endpoint. The Spring Boot 2.7 BOM fixes their Java 8 versions (HikariCP 4.0.3,
  // tomcat-jdbc and tomcat-embed-core 9.0.83, commons-dbcp2 2.9.0, H2 2.1.214) and their
  // dependencies to versions already verified for the sample apps (it drops DBCP2's
  // commons-logging, whose API spring-jcl provides as in the apps); Tomcat's annotation API is not
  // needed. Test classpath only: never shipped (checkForbiddenDependencies looks at
  // runtimeClasspath; H2 is a dev-only row).
  testImplementation(platform(libs.sample.boot2.bom))
  testImplementation(libs.sample.hikari)
  testImplementation(libs.sample.tomcat.jdbc)
  testImplementation(libs.sample.dbcp2)
  testImplementation(libs.sample.h2)
  testImplementation(libs.sample.tomcat.embed.core) {
    exclude(group = "org.apache.tomcat", module = "tomcat-annotations-api")
  }
  testRuntimeOnly(libs.sample.spring.jcl)
}

// AgentConfigDocTest compares docs/agent-config.md with the ConfigKeys registry (10 §15): a change
// to the document alone must rerun the unit tests.
tasks.named<Test>("test") {
  inputs
    .file(layout.settingsDirectory.file("docs/agent-config.md"))
    .withPropertyName("agentConfigReference")
    .withPathSensitivity(PathSensitivity.RELATIVE)
}
