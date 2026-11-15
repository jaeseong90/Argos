// SPDX-License-Identifier: Apache-2.0
//
// Agent core, loaded from inst/**.classdata by AgentClassLoader (10 §2, §4.2).
// agent-bootstrap is compileOnly: at runtime its classes come from the bootstrap class loader.
// The instrumentation api (and through it ByteBuddy) is the only instrumentation project the core
// may reference (10 §2 B-3); modules are discovered at runtime (ADR-0003 C-25).
// JCTools (Apache-2.0) provides the bounded MPSC queues of 10 §10.1 (atomic variant, no Unsafe);
// agent-dist shades it under io.argos.agent.shaded.org.jctools and minimize() keeps only the
// classes the queues reach.
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

  // Unit and integration tests run on Java 8 with the bootstrap classes on the class path.
  testImplementation(project(":agent:agent-bootstrap"))
  "integrationTestImplementation"(project(":agent:agent-bootstrap"))
}

// AgentConfigDocTest compares docs/agent-config.md with the ConfigKeys registry (10 §15): a change
// to the document alone must rerun the unit tests.
tasks.named<Test>("test") {
  inputs
    .file(layout.settingsDirectory.file("docs/agent-config.md"))
    .withPropertyName("agentConfigReference")
    .withPathSensitivity(PathSensitivity.RELATIVE)
}
