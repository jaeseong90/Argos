// SPDX-License-Identifier: Apache-2.0
//
// Root project: lifecycle tasks and repository-wide checks only (checkSpdx, build-logic tests; see
// build-logic/src/main/kotlin/argos.repository-checks.gradle.kts). Module builds use the convention
// plugins in build-logic/.

plugins { id("argos.repository-checks") }

description = "Argos APM: on-premises Java APM (agent, collector server, UI)."
