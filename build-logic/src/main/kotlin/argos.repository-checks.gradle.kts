// SPDX-License-Identifier: Apache-2.0
//
// argos.repository-checks: repository-wide checks of the root project.
//   checkSpdx  test/lint/check-spdx.sh: SPDX header (exactly Apache-2.0) in every source, script,
//              config and markup file it lists (19 §5.1, O-L07, T-BLD-007). CI also runs the script
//              directly (pr.yml lint job).
//   check      also runs the build-logic tests (license policy, jar checks, script tests).

import io.argos.buildlogic.argosBash

plugins { base }

val checkSpdx =
  tasks.register<Exec>("checkSpdx") {
    group = "verification"
    description = "Checks SPDX headers in every source file (test/lint/check-spdx.sh, T-BLD-007)."
    val root = layout.settingsDirectory.asFile
    val script = layout.settingsDirectory.file("test/lint/check-spdx.sh").asFile
    workingDir = root
    // Git Bash on Windows (the WSL bash launcher cannot read Windows paths), bash elsewhere.
    executable = argosBash().get()
    args(script.invariantSeparatorsPath, root.invariantSeparatorsPath)
  }

tasks.named("check") {
  dependsOn(checkSpdx)
  dependsOn(gradle.includedBuild("build-logic").task(":check"))
}
