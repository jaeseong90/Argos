// SPDX-License-Identifier: Apache-2.0
//
// argos.repository-checks: repository-wide checks of the root project.
//   checkSpdx  test/lint/check-spdx.sh: SPDX header (exactly Apache-2.0) in every source, script,
//              config and markup file it lists (19 §5.1, O-L07, T-BLD-007). CI also runs the script
//              directly (pr.yml lint job).
//   checkNotice  test/lint/check-notice.sh (T-BLD-012, O-L05, WO-44): third-party source code in
//              the repository (a foreign copyright header or SPDX identifier, a borrowed-code
//              marker) is listed with its source in NOTICE, and every path NOTICE lists exists.
//   check      also runs the build-logic tests (license policy, jar checks, script tests).

import io.argos.buildlogic.argosBash

plugins { base }

val checkNotice =
  tasks.register<Exec>("checkNotice") {
    group = "verification"
    description = "Checks that NOTICE lists the third-party source code of the repository (test/lint/check-notice.sh, T-BLD-012)."
    val root = layout.settingsDirectory.asFile
    val script = layout.settingsDirectory.file("test/lint/check-notice.sh").asFile
    workingDir = root
    executable = argosBash().get()
    args(script.invariantSeparatorsPath, root.invariantSeparatorsPath)
  }

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
  dependsOn(checkNotice)
  dependsOn(gradle.includedBuild("build-logic").task(":check"))
}
