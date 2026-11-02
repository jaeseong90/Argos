// SPDX-License-Identifier: Apache-2.0
//
// argos.integration-test: test layers beyond unit tests (19 §3.2, §3.6, 18 §2.2). None of these
// suites is part of check (argos.test-layers fails the build if one is wired into it); CI runs them
// as separate jobs. test/README.md lists every layer and how to run it.
//   integrationTest  src/integrationTest  Testcontainers, fake collector, one sample app (PR)
//   securityTest     src/securityTest     SEC suite (PR light / nightly full)
//   matrixTest       src/matrixTest       sample apps x JDKs; agent-testing only has sources.
//                                         Runs on a self-hosted 'argos-matrix' runner
//                                         (ARGOS_RUNNER_LABELS) or with -Pmatrix.combo=<id> /
//                                         -Pmatrix.group=<group>; otherwise SKIPPED with a warning.
//   benchTest        src/benchTest        overhead protocol; the runner lives in test/bench (18 §7).
//                                         Requires -Pbench.machine-profile=<id> (argos.test-layers
//                                         fails fast without it; the task checks it again).

import io.argos.buildlogic.testing.MachineProfile
import io.argos.buildlogic.testing.MatrixGate
import io.argos.buildlogic.testing.TestLayers

plugins { id("argos.java-common") }

/**
 * HotSpot verifies boot class path (JDK) classes only with -XX:+BytecodeVerificationLocal;
 * application classes are verified by default. Integration and matrix tests run with it so that JDK
 * classes transformed by the agent are verified too (19 §11, 18 §5.10, T-AG-006). The flag is a
 * product flag on Java 8 but a diagnostic flag on JDK 17, 21 and 25, which reject it unless
 * -XX:+UnlockDiagnosticVMOptions comes first; Java 8 accepts both. Sample apps started as separate
 * processes use the same two flags (agent-testing README).
 */
val bytecodeVerificationFlags = listOf("-XX:+UnlockDiagnosticVMOptions", "-XX:+BytecodeVerificationLocal")

val runnerLabels = providers.environmentVariable(TestLayers.RUNNER_LABELS_ENV).orNull
val matrixCombo = providers.gradleProperty("matrix.combo").orNull
val matrixGroup = providers.gradleProperty("matrix.group").orNull
val benchMachineProfile = providers.gradleProperty(TestLayers.MACHINE_PROFILE_PROPERTY).orNull

testing {
  suites {
    register<JvmTestSuite>("integrationTest") {
      dependencies { implementation(project()) }
      targets.configureEach {
        testTask.configure {
          shouldRunAfter(tasks.named("test"))
          jvmArgs(bytecodeVerificationFlags)
        }
      }
    }

    register<JvmTestSuite>("securityTest") {
      dependencies { implementation(project()) }
      targets.configureEach { testTask.configure { shouldRunAfter(tasks.named("test")) } }
    }

    register<JvmTestSuite>("matrixTest") {
      dependencies { implementation(project()) }
      targets.configureEach {
        testTask.configure {
          // Local copies only: the onlyIf spec is serialized by the configuration cache.
          val run = MatrixGate.shouldRun(runnerLabels, matrixCombo, matrixGroup)
          val combo = matrixCombo
          val group = matrixGroup
          jvmArgs(bytecodeVerificationFlags)
          if (combo != null) systemProperty("argos.matrix.combo", combo)
          if (group != null) systemProperty("argos.matrix.group", group)
          val reason = "runner label '${TestLayers.MATRIX_RUNNER_LABEL}' present, or -Pmatrix.combo / -Pmatrix.group set"
          onlyIf(reason) { task ->
            if (!run) {
              // Projects without matrix tests stay quiet; the one with tests says why it skipped.
              val hasTests = (task as Test).testClassesDirs.files.any { dir -> dir.walk().any { it.isFile } }
              if (hasTests) task.logger.warn(MatrixGate.skipWarning(task.path))
            }
            run
          }
        }
      }
    }

    register<JvmTestSuite>("benchTest") {
      dependencies { implementation(project()) }
      targets.configureEach {
        testTask.configure {
          val profile = benchMachineProfile
          if (profile != null) systemProperty("argos.bench.machineProfile", profile)
          val taskPath = path
          doFirst {
            if (!MachineProfile.isValid(profile)) {
              throw GradleException(MachineProfile.missingMessage(listOf(taskPath), profile))
            }
          }
        }
      }
    }
  }
}
