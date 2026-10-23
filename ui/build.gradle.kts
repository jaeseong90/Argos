// SPDX-License-Identifier: Apache-2.0
//
// Gradle wrapper around the pnpm build of the UI (19 §2, §4). Gradle only calls the pnpm scripts
// defined in ui/package.json; all UI tooling lives there.
//   :ui:pnpmInstall  pnpm install --frozen-lockfile
//   :ui:pnpmBuild    pnpm run build        (part of assemble, so of :ui:build)
//   :ui:test         pnpm run test         (part of check)
//   :ui:lint         pnpm run lint         (part of check)
//   :ui:typecheck    pnpm run typecheck    (part of check)
// pnpm is taken from PATH, otherwise run through corepack (pinned by package.json#packageManager).
// -Pargos.pnpm=<command> overrides the command; -Pargos.ui.skip=true skips every UI task (for
// Java-only CI jobs).

plugins { base }

description = "Argos UI (React + TypeScript + Vite)."

val skipUi = providers.gradleProperty("argos.ui.skip").map { it.toBoolean() }.getOrElse(false)

val isWindows = System.getProperty("os.name").lowercase().contains("windows")

val pnpmOnPath =
  providers.environmentVariable("PATH").getOrElse("").split(File.pathSeparator).any { dir ->
    listOf("pnpm", "pnpm.cmd", "pnpm.exe").any { File(dir, it).isFile }
  }

val pnpmCommand: List<String> =
  providers.gradleProperty("argos.pnpm").orNull?.let { listOf(it) }
    ?: if (pnpmOnPath) listOf("pnpm") else listOf("corepack", "pnpm")

// pnpm and corepack are .cmd shims on Windows, which CreateProcess cannot start directly.
val commandPrefix: List<String> = if (isWindows) listOf("cmd", "/c") else emptyList()

/** Inputs that affect every pnpm script (lockfile, manifests, tool configuration). */
val projectInputs =
  fileTree(layout.projectDirectory) {
    exclude("node_modules/**", "dist/**", "build/**", "coverage/**", "test-results/**")
    exclude("playwright-report/**", ".vite/**", "*.log")
  }

fun registerPnpm(name: String, taskDescription: String, vararg args: String) =
  tasks.register<Exec>(name) {
    group = "ui"
    description = taskDescription
    workingDir = layout.projectDirectory.asFile
    commandLine(commandPrefix + pnpmCommand + args.toList())
    environment("COREPACK_ENABLE_DOWNLOAD_PROMPT", "0")
    val skip = skipUi
    onlyIf("-Pargos.ui.skip is not set") { !skip }
  }

val pnpmInstall =
  registerPnpm("pnpmInstall", "Installs UI dependencies from pnpm-lock.yaml.", "install", "--frozen-lockfile")

pnpmInstall {
  inputs.files("package.json", "pnpm-lock.yaml").withPathSensitivity(PathSensitivity.RELATIVE)
  inputs.files(fileTree(layout.projectDirectory) { include("pnpm-workspace.yaml", ".npmrc") })
  outputs.file("node_modules/.modules.yaml")
}

val pnpmBuild = registerPnpm("pnpmBuild", "Builds the UI bundle into ui/dist.", "run", "build")

pnpmBuild {
  dependsOn(pnpmInstall)
  inputs.files(projectInputs).withPathSensitivity(PathSensitivity.RELATIVE)
  outputs.dir("dist")
}

val uiTest = registerPnpm("test", "Runs the UI unit tests (Vitest).", "run", "test")

val uiLint = registerPnpm("lint", "Runs ESLint on the UI sources.", "run", "lint")

val uiTypecheck = registerPnpm("typecheck", "Type-checks the UI sources (tsc --strict).", "run", "typecheck")

// Each check task declares the UI sources as inputs and writes a marker file on success, so it is
// UP-TO-DATE when nothing changed since its last successful run.
listOf(uiTest, uiLint, uiTypecheck).forEach { task ->
  task {
    dependsOn(pnpmInstall)
    inputs.files(projectInputs).withPathSensitivity(PathSensitivity.RELATIVE)
    val marker = layout.buildDirectory.file("ui-check/$name.ok")
    outputs.file(marker)
    doLast { marker.get().asFile.writeText("ok\n") }
  }
}

// scripts/check-licenses.test.mjs reads docs/licenses/dev-only.md, which is outside ui/ and so not
// in projectInputs; without this input an edit of that list would leave the tests UP-TO-DATE.
uiTest {
  inputs
    .file(layout.settingsDirectory.file("docs/licenses/dev-only.md"))
    .withPathSensitivity(PathSensitivity.RELATIVE)
}

// tsc, ESLint (typed rules) and Vitest each start a TypeScript program; running them at the same
// time (org.gradle.parallel with the configuration cache runs tasks of one project concurrently)
// slows every one of them down enough to hit test timeouts. pnpmBuild also runs `tsc -b`, which
// writes the same node_modules/.tmp/*.tsbuildinfo files as typecheck.
uiTypecheck { mustRunAfter(pnpmBuild) }

uiLint { mustRunAfter(uiTypecheck) }

uiTest { mustRunAfter(uiLint, uiTypecheck) }

tasks.assemble { dependsOn(pnpmBuild) }

tasks.check { dependsOn(uiTest, uiLint, uiTypecheck) }

tasks.clean { delete("dist") }
