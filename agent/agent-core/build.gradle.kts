// SPDX-License-Identifier: Apache-2.0
//
// Agent core, loaded from inst/**.classdata by AgentClassLoader (10 §2, §4.2).
// agent-bootstrap is compileOnly: at runtime its classes come from the bootstrap class loader.
// The instrumentation api (and through it ByteBuddy) is the only instrumentation project the core
// may reference (10 §2 B-3); modules are discovered at runtime (ADR-0003 C-25).

plugins { id("argos.java-agent") }

description = "Argos agent core (transactions, statistics, transport, configuration, commands)."

dependencies {
  compileOnly(project(":agent:agent-bootstrap"))
  implementation(project(":agent:agent-instrumentation:api"))

  // Unit tests run on Java 8 with the bootstrap classes on the class path.
  testImplementation(project(":agent:agent-bootstrap"))
}
