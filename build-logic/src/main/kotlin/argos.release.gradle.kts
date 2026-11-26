// SPDX-License-Identifier: Apache-2.0
//
// argos.release: root project only. Release assembly of 19 §7.2 (WO-44); not part of check or build.
//   releaseArtifacts        collects the release assets into build/release/ under their release names
//                           and writes SHA256SUMS (sorted names, LF, `sha256sum -c` format):
//                             argos-agent-X.Y.Z.jar          :agent:agent-dist:agentJar
//                             argos-server-X.Y.Z.jar         :server:bootJar (with the UI bundle)
//                             argos-agent-X.Y.Z.cdx.json     :agent:agent-dist:agentSbom
//                             argos-server-X.Y.Z.cdx.json    :server:serverSbom
//                             argos-ui-X.Y.Z.cdx.json        :ui:pnpmBuild (ui/build/sbom/ui.cdx.json)
//                           X.Y.Z is the project version (-Pversion=X.Y.Z on tag builds).
//                           -Pargos.release.parts=agent[,server][,ui] selects a subset for local
//                           reproducibility runs (test/release/verify-reproducible.sh); a release has all.
//   compareReleaseOutputs   compares two release outputs entry by entry (T-BLD-009, CompareArchives):
//                           -Pargos.release.compare.a=<dir> -Pargos.release.compare.b=<dir>
//                           [-Pargos.release.compare.report=<file>]; fails when they differ.
// Signing, the GitHub release and the images are .github/workflows/release.yml's (WO-44).

import io.argos.buildlogic.release.assembly.AssembleReleaseArtifacts
import io.argos.buildlogic.release.assembly.CompareReleaseOutputs
import io.argos.buildlogic.release.assembly.ReleaseAssets

check(project == rootProject) { "argos.release is applied to the root project only" }

val releaseVersion = project.version.toString()
val uiSkipped = providers.gradleProperty("argos.ui.skip").map { it.toBoolean() }.getOrElse(false)
val partsSpec = providers.gradleProperty(ReleaseAssets.PARTS_PROPERTY).orNull
val releaseParts = ReleaseAssets.parseParts(partsSpec)
val releaseAssets = ReleaseAssets.assets(releaseVersion, releaseParts)
val repositoryRoot = layout.settingsDirectory.asFile

tasks.register<AssembleReleaseArtifacts>("releaseArtifacts") {
  group = "argos"
  description =
    "Collects the release assets (agent and server jars, agent, server and UI SBOMs) into " +
      "build/release/ with SHA256SUMS (19 §7.2). -Pargos.release.parts selects a subset."
  assetNames = releaseAssets.map { it.name }
  assetSources = releaseAssets.map { File(repositoryRoot, it.sourcePath).absolutePath }
  assetBuiltBy = releaseAssets.associate { it.name to it.builtBy }
  releaseAssets.forEach { asset -> sources.from(files(File(repositoryRoot, asset.sourcePath)) { builtBy(asset.builtBy) }) }
  outputDirectory = layout.buildDirectory.dir("release")
  val parts = releaseParts
  val skipped = uiSkipped
  doFirst {
    if (skipped && ReleaseAssets.UI in parts) {
      throw GradleException(
        "releaseArtifacts: -Pargos.ui.skip=true leaves the UI out of the server jar and produces no UI " +
          "SBOM; a release needs the UI. Drop -Pargos.ui.skip, or select parts without ui for a local " +
          "check: -P${ReleaseAssets.PARTS_PROPERTY}=agent,server"
      )
    }
    if (skipped && ReleaseAssets.SERVER in parts) {
      logger.warn("releaseArtifacts: the server jar has no UI bundle (-Pargos.ui.skip=true); not a releasable output")
    }
    if (parts != ReleaseAssets.ALL_PARTS) {
      logger.warn("releaseArtifacts: parts ${parts.joinToString(",")} only; a release has ${ReleaseAssets.ALL_PARTS.joinToString(",")}")
    }
  }
}

tasks.register<CompareReleaseOutputs>("compareReleaseOutputs") {
  group = "argos"
  description =
    "Compares two release outputs entry by entry (T-BLD-009): -P${CompareReleaseOutputs.PROPERTY_A}=<dir> " +
      "-P${CompareReleaseOutputs.PROPERTY_B}=<dir>."
  directoryA = providers.gradleProperty(CompareReleaseOutputs.PROPERTY_A).orElse("")
  directoryB = providers.gradleProperty(CompareReleaseOutputs.PROPERTY_B).orElse("")
  val projectDirectory = layout.projectDirectory
  report =
    providers
      .gradleProperty(CompareReleaseOutputs.PROPERTY_REPORT)
      .map { projectDirectory.file(File(it).absolutePath) }
      .orElse(layout.buildDirectory.file("reproducibility/compare-report.txt"))
}
