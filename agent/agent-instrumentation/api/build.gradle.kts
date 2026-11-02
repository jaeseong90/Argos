// SPDX-License-Identifier: Apache-2.0
//
// Instrumentation framework (10 §5.1): InstrumentationModule, TypeInstrumentation, TypeTransformer,
// SafeMatchers, Witness, ModuleIds. ByteBuddy is an api dependency: every instrumentation module
// compiles against ElementMatcher and the Advice annotations (ADR-0003 C-26). agent-dist shades it
// below io.argos.agent.shaded.

plugins { id("argos.java-agent") }

description = "Argos agent instrumentation: api."

dependencies {
  compileOnly(project(":agent:agent-bootstrap"))
  api(libs.bytebuddy)
}
