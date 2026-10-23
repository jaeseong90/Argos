// SPDX-License-Identifier: Apache-2.0
//
// Agent core, loaded from inst/**.classdata by AgentClassLoader (10 §2, §4.2).
// agent-bootstrap is compileOnly: at runtime its classes come from the bootstrap class loader.

plugins { id("argos.java-agent") }

description = "Argos agent core (transactions, statistics, transport, configuration, commands)."

dependencies { compileOnly(project(":agent:agent-bootstrap")) }
