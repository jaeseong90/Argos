// SPDX-License-Identifier: Apache-2.0
//
// argos.integration-test: test layers beyond unit tests (19 §3.6, 18 §2.2). None of these suites
// is part of check; CI runs them as separate jobs.
//   integrationTest  src/integrationTest  Testcontainers, fake collector, one sample app (PR)
//   securityTest     src/securityTest     SEC suite (PR light / nightly full)
//   matrixTest       src/matrixTest       sample apps x JDKs; self-hosted runners only
//   benchTest        src/benchTest        overhead protocol; requires -Pbench.machine-profile

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

/** Runner label that marks the trusted self-hosted matrix runner (DSN-19-06, O-S05). */
val matrixRunnerLabel = "argos-matrix"

val runnerLabels =
  providers.environmentVariable("ARGOS_RUNNER_LABELS").getOrElse("").split(',').map { it.trim() }
val matrixCombo = providers.gradleProperty("matrix.combo").orNull
val benchMachineProfile = providers.gradleProperty("bench.machine-profile").orNull

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
          val label = matrixRunnerLabel
          val onTrustedRunner = label in runnerLabels
          val combo = matrixCombo
          jvmArgs(bytecodeVerificationFlags)
          if (combo != null) systemProperty("argos.matrix.combo", combo)
          // Runs on the self-hosted matrix runner, or locally when a combination is chosen
          // explicitly (-Pmatrix.combo=<id>, docs/dev-setup.md). Otherwise skip with a warning.
          onlyIf("runner label '$label' present or -Pmatrix.combo set") { task ->
            val run = onTrustedRunner || combo != null
            if (!run) {
              task.logger.warn(
                "WARNING: ${task.path} skipped: not on a self-hosted '$label' runner " +
                  "(ARGOS_RUNNER_LABELS) and no -Pmatrix.combo=<id> given."
              )
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
          doFirst {
            if (profile == null) {
              throw GradleException("benchTest requires -Pbench.machine-profile=<id> (18 §2.2)")
            }
          }
        }
      }
    }
  }
}
