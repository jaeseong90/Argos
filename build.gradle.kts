// SPDX-License-Identifier: Apache-2.0
//
// Root project: lifecycle tasks and repository-wide checks only. Module builds use the convention
// plugins in build-logic/.
//   argos.repository-checks  checkSpdx and the build-logic tests in check
//                            (build-logic/src/main/kotlin/argos.repository-checks.gradle.kts)
//   argos.test-layers        task-graph guards of the test layers (composition of check,
//                            -Pbench.machine-profile) and traceabilityReport (test/README.md)

plugins {
  id("argos.repository-checks")
  id("argos.test-layers")
  id("argos.release") // releaseArtifacts, compareReleaseOutputs (WO-44, build-logic/.../argos.release.gradle.kts)
}

description = "Argos APM: on-premises Java APM (agent, collector server, UI)."
