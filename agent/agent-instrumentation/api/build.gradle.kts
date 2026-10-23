// SPDX-License-Identifier: Apache-2.0

plugins { id("argos.java-agent") }

description = "Argos agent instrumentation: api."

dependencies {
  compileOnly(project(":agent:agent-bootstrap"))
}
