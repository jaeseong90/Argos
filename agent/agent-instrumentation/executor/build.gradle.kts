// SPDX-License-Identifier: Apache-2.0

plugins { id("argos.java-agent") }

description = "Argos agent instrumentation: executor."

dependencies {
  compileOnly(project(":agent:agent-bootstrap"))
  implementation(project(":agent:agent-instrumentation:api"))
}
