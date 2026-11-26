// SPDX-License-Identifier: Apache-2.0
package io.argos.buildlogic.traceability

import io.argos.buildlogic.testing.MatrixCatalog
import io.argos.buildlogic.testing.MatrixDefinitionException
import io.argos.buildlogic.testing.MatrixRunResult
import io.argos.buildlogic.traceability.JUnitXmlResults.Status
import java.io.File
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.time.Instant
import java.time.temporal.ChronoUnit
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

/**
 * Walks the repository for test sources and results (no Gradle model needed, so the included build
 * build-logic and the UI are covered too):
 * - Java/Kotlin sources of test source sets: `<project>/src/<set>/` where `<set>` is `test` or
 *   ends with `Test` (integrationTest, archTest, matrixTest, securityTest, contractTest, ...);
 * - UI tests: `*.test.*` (Vitest) and `*.spec.*` (Playwright) below ui/, outside node_modules;
 * - shell test scripts ([ShellTestScanner.isShellTest]) and the matrix rows of test/matrix/matrix.yaml;
 * - results: the XML files in `build/test-results/<task>` of every project (JUnit; Vitest writes
 *   ui/build/test-results/vitest/junit.xml), the shell TAP files `build/test-results/shell/<suite>/<script>.tap`,
 *   the Playwright JSON files `results*.json` at any depth below ui/test-results and the matrix results
 *   `build/matrix/T-MX-*.json`.
 * Skipped: node_modules, VCS and IDE directories, every other build output, docs/.
 */
object RepositoryScan {

  private val skippedDirectories = setOf("node_modules", ".git", ".gradle", ".kotlin", ".idea", ".vscode", "dist", "coverage", "playwright-report", "docs")
  private val scriptTest = Regex("""^.+\.(test|spec)\.(ts|tsx|mts|js|mjs)$""")

  data class Collected(
    val junitSources: List<File>,
    val scriptSources: List<File>,
    val resultFiles: List<File>,
    val shellSources: List<File> = emptyList(),
    val shellResults: List<File> = emptyList(),
    val playwrightResults: List<File> = emptyList(),
    val matrixResults: List<File> = emptyList(),
    val matrixCatalog: File? = null,
  )

  /**
   * Files of the repository below [root]; with [includeResults] false no result file is collected
   * (a clean checkout: every test NOT RUN, `-Ptraceability.results=none`).
   */
  fun collect(root: File, includeResults: Boolean = true): Collected {
    val junit = mutableListOf<File>()
    val scripts = mutableListOf<File>()
    val results = mutableListOf<File>()
    val shells = mutableListOf<File>()
    val shellResults = mutableListOf<File>()
    val playwright = mutableListOf<File>()
    val matrix = mutableListOf<File>()
    val rootPath = root.toPath()
    Files.walkFileTree(
      rootPath,
      object : SimpleFileVisitor<Path>() {
        override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
          if (dir == rootPath) return FileVisitResult.CONTINUE
          val name = dir.fileName.toString()
          val relative = rootPath.relativize(dir).toString().replace('\\', '/')
          if (name in skippedDirectories && !relative.contains("/src/")) return FileVisitResult.SKIP_SUBTREE
          if (name == "node_modules") return FileVisitResult.SKIP_SUBTREE
          // A "build" directory outside src/ is a build output: only its test results are read.
          if (name == "build" && !relative.contains("/src/") && !relative.startsWith("src/")) {
            if (!includeResults) return FileVisitResult.SKIP_SUBTREE
            val testResults = dir.resolve("test-results").toFile()
            testResults.listFiles { f -> f.isDirectory }.orEmpty().sortedBy { it.name }.forEach { task ->
              results += task.listFiles { f -> f.isFile && f.name.endsWith(".xml") }.orEmpty().sortedBy { it.name }
            }
            testResults.resolve("shell").listFiles { f -> f.isDirectory }.orEmpty().sortedBy { it.name }.forEach { suite ->
              shellResults += suite.listFiles { f -> f.isFile && f.name.endsWith(".tap") }.orEmpty().sortedBy { it.name }
            }
            dir.resolve("matrix").toFile().listFiles { f -> f.isFile }.orEmpty().sortedBy { it.name }.forEach { f ->
              if (MatrixEvidence.isResultFile(rootPath.relativize(f.toPath()).toString().replace('\\', '/'))) matrix += f
            }
            return FileVisitResult.SKIP_SUBTREE
          }
          // Playwright output: only the JSON reports are read, never the attachments.
          if (name == "test-results" && relative.startsWith("ui/")) {
            if (includeResults) {
              Files.walk(dir).use { paths ->
                paths.filter { Files.isRegularFile(it) }.forEach { p ->
                  if (PlaywrightResults.isResultsFile(rootPath.relativize(p).toString().replace('\\', '/'))) playwright += p.toFile()
                }
              }
            }
            return FileVisitResult.SKIP_SUBTREE
          }
          return FileVisitResult.CONTINUE
        }

        override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
          val relative = rootPath.relativize(file).toString().replace('\\', '/')
          val name = file.fileName.toString()
          if ((name.endsWith(".java") || name.endsWith(".kt")) && sourceSetOf(relative) != null) {
            junit += file.toFile()
          } else if (relative.startsWith("ui/") && scriptTest.matches(name)) {
            scripts += file.toFile()
          } else if (name.endsWith(".sh") && ShellTestScanner.isShellTest(relative)) {
            shells += file.toFile()
          }
          return FileVisitResult.CONTINUE
        }
      },
    )
    return Collected(
      junit.sortedBy { it.path },
      scripts.sortedBy { it.path },
      results.sortedBy { it.path },
      shells.sortedBy { it.path },
      shellResults.sortedBy { it.path },
      playwright.sortedBy { it.path },
      matrix.sortedBy { it.path },
      root.resolve(MatrixEvidence.CATALOG_PATH).takeIf { it.isFile },
    )
  }

  /** "a/src/integrationTest/java/X.java" -> "integrationTest"; null when not a test source set. */
  fun sourceSetOf(relativePath: String): String? {
    val parts = relativePath.split('/')
    val src = parts.indexOf("src")
    if (src < 0 || src + 2 >= parts.size) return null
    val set = parts[src + 1]
    return set.takeIf { it == "test" || (it.endsWith("Test") && it.length > 4) }
  }

  /** Repository-relative project directory of a results file (".../build/test-results/<task>/x.xml"). */
  fun projectDirOfResult(relativePath: String): String =
    relativePath.substringBefore("/build/test-results/", missingDelimiterValue = "").let {
      if (relativePath.startsWith("build/test-results/")) "" else it
    }

  /** Scans everything below [root] into a model, results included. */
  fun model(root: File): TraceabilityModel = model(root, includeResults = true)

  /**
   * Scans everything below [root] into a model; with [includeResults] false every test is NOT RUN
   * (no result file is read: the state of a clean checkout, `-Ptraceability.results=none`).
   */
  fun model(root: File, includeResults: Boolean): TraceabilityModel {
    val files = collect(root, includeResults)
    val classes = mutableListOf<SourceClass>()
    val tests = mutableListOf<SourceTest>()
    var withoutId = 0
    for (file in files.junitSources) {
      val relative = file.relativeTo(root).invariantSeparatorsPath
      val scan =
        JavaKotlinTestScanner.scan(relative, file.readText(), Traceability.projectDirOf(relative), sourceSetOf(relative)!!)
      classes += scan.classes
      tests += scan.tests
    }
    for (file in files.scriptSources) {
      val relative = file.relativeTo(root).invariantSeparatorsPath
      val suite = if (file.name.contains(".spec.")) "playwright" else "vitest"
      val scan = ScriptTestScanner.scan(relative, file.readText(), "ui", suite)
      tests += scan.tests
      withoutId += scan.untitledTests
    }
    for (file in files.shellSources) {
      val relative = file.relativeTo(root).invariantSeparatorsPath
      tests += ShellTestScanner.scan(relative, file.readText()).tests
    }
    val unreadable = mutableListOf<String>()
    val matrixCatalog =
      files.matrixCatalog?.let {
        try {
          MatrixCatalog.load(it)
        } catch (e: MatrixDefinitionException) {
          unreadable += MatrixEvidence.CATALOG_PATH
          null
        }
      }
    matrixCatalog?.let { tests += MatrixEvidence.sources(it) }
    val results = mutableListOf<Pair<String, JUnitXmlResults.Case>>()
    var vitestResultFiles = 0
    for (file in files.resultFiles) {
      val relative = file.relativeTo(root).invariantSeparatorsPath
      val cases = JUnitXmlResults.read(file)
      if (cases.isEmpty() && file.length() > 0 && !file.readText().contains("<testsuite")) unreadable += relative
      val project = projectDirOfResult(relative)
      if (project == "ui") vitestResultFiles++
      cases.forEach { results += project to it.copy(file = File(relative)) }
    }
    val scriptResults = mutableListOf<ScriptCase>()
    for (file in files.playwrightResults) {
      val relative = file.relativeTo(root).invariantSeparatorsPath
      PlaywrightResults.read(file, relative)?.let { scriptResults += it } ?: run { unreadable += relative }
    }
    for (file in files.shellResults) {
      val relative = file.relativeTo(root).invariantSeparatorsPath
      ShellResults.read(file, relative)?.let { scriptResults += it } ?: run { unreadable += relative }
    }
    for (file in files.matrixResults) {
      val relative = file.relativeTo(root).invariantSeparatorsPath
      try {
        scriptResults += MatrixEvidence.result(MatrixRunResult.fromJson(file), matrixCatalog, relative)
      } catch (e: MatrixDefinitionException) {
        unreadable += relative
      } catch (e: groovy.json.JsonException) {
        unreadable += relative
      }
    }
    withoutId += tests.count { it.methodName != null && it.testIds.isEmpty() && it.requirements.isEmpty() }
    val playwrightSources = files.scriptSources.count { it.name.contains(".spec.") }
    return Traceability.build(
      classes,
      tests,
      results,
      sourceFiles = files.junitSources.size + files.scriptSources.size + files.shellSources.size + (if (matrixCatalog != null) 1 else 0),
      resultFiles = files.resultFiles.size + files.playwrightResults.size + files.shellResults.size + files.matrixResults.size,
      unreadableResultFiles = unreadable,
      testsWithoutId = withoutId,
      scriptResults = scriptResults,
      sourceFileCounts =
        mapOf(
          Traceability.SOURCE_JUNIT to files.junitSources.size,
          Traceability.SOURCE_VITEST to files.scriptSources.size - playwrightSources,
          PlaywrightResults.SOURCE to playwrightSources,
          ShellTestScanner.SOURCE to files.shellSources.size,
          MatrixEvidence.SOURCE to (if (matrixCatalog != null) 1 else 0),
        ),
      resultFileCounts =
        mapOf(
          Traceability.SOURCE_JUNIT to files.resultFiles.size - vitestResultFiles,
          Traceability.SOURCE_VITEST to vitestResultFiles,
          PlaywrightResults.SOURCE to files.playwrightResults.size,
          ShellTestScanner.SOURCE to files.shellResults.size,
          MatrixEvidence.SOURCE to files.matrixResults.size,
        ),
    )
  }
}

/** Renders the model as build/reports/traceability.md and its JSON twin. */
object TraceabilityRenderer {

  const val QUARANTINE_LIMIT = 10

  /** Display names of [Traceability.SOURCES]. */
  val SOURCE_NAMES: Map<String, String> =
    mapOf(
      Traceability.SOURCE_JUNIT to "JUnit (Gradle test tasks)",
      Traceability.SOURCE_VITEST to "Vitest (ui)",
      PlaywrightResults.SOURCE to "Playwright (ui/e2e)",
      ShellTestScanner.SOURCE to "Shell suites (TAP)",
      MatrixEvidence.SOURCE to "Compatibility matrix",
    )

  private const val REASON_LIMIT = 120

  fun markdown(model: TraceabilityModel, generatedAt: String): String {
    val sb = StringBuilder()
    val entries = model.entries
    val withReq = entries.filter { it.requirements.isNotEmpty() }
    val quarantined = entries.filter { it.quarantined }
    sb.appendLine("# Requirement to test traceability")
    sb.appendLine()
    sb.appendLine(
      "Generated by `./gradlew traceabilityReport` at $generatedAt from ${model.sourceFiles} test source " +
        "files and ${model.resultFiles} result files of this workspace (JUnit XML, Vitest JUnit XML, Playwright " +
        "JSON, shell TAP, matrix JSON; docs/18 §3, §16, DSN-18-01)."
    )
    sb.appendLine(
      "Test IDs come from display names, method names, test titles, shell assertion descriptions with their " +
        "`# test-ids:` headers and matrix rows; requirements from `@Tag(\"req:<ID>\")`, Playwright `req` " +
        "annotations (per test in the JSON results, per file in the sources), shell `# req:` headers and N-13 for " +
        "matrix rows. \"Last result\" aggregates the result files in this workspace; NOT RUN means no result file " +
        "(run the suite first; a clean checkout shows every test NOT RUN, never FAILED)."
    )
    sb.appendLine(
      "The comparison with the 18 §16 table and the catalogue rules are `./gradlew checkTraceability`."
    )
    sb.appendLine()
    if (model.sources.isNotEmpty()) {
      sb.appendLine("## Sources")
      sb.appendLine()
      sb.appendLine("| Source | Test source files | Result files | Result cases | Unlisted result cases (no ID, no requirement) | PASSED | FAILED | SKIPPED | NOT RUN |")
      sb.appendLine("|---|---|---|---|---|---|---|---|---|")
      for (s in model.sources) {
        val c = model.statusCounts(s.source)
        sb.appendLine(
          "| ${SOURCE_NAMES[s.source] ?: s.source} | ${s.sourceFiles} | ${s.resultFiles} | ${s.resultCases} | ${s.unlistedResultCases} | " +
            "${c["PASSED"]} | ${c["FAILED"]} | ${c["SKIPPED"]} | ${c["NOT RUN"]} |"
        )
      }
      sb.appendLine()
    }
    sb.appendLine("## Summary")
    sb.appendLine()
    sb.appendLine("| Item | Count |")
    sb.appendLine("|---|---|")
    sb.appendLine("| Requirements with at least one test | ${model.byRequirement.size} |")
    sb.appendLine("| Distinct test IDs | ${model.testIds.size} |")
    sb.appendLine("| Tests with a requirement tag | ${withReq.size} |")
    sb.appendLine("| Tests with a test ID but no requirement tag | ${model.withoutRequirement.size} |")
    sb.appendLine("| Tests with neither (JUnit methods and UI titles) | ${model.testsWithoutId} |")
    val counts = entries.groupingBy { it.result?.status?.name ?: "NOT RUN" }.eachCount()
    sb.appendLine(
      "| Last result of the reported tests | " +
        listOf("PASSED", "FAILED", "SKIPPED", "NOT RUN").joinToString(", ") { "$it ${counts[it] ?: 0}" } + " |"
    )
    sb.appendLine("| Quarantined tests (`@Tag(\"quarantine\")`, limit $QUARANTINE_LIMIT, DSN-18-13) | ${quarantined.size} |")
    sb.appendLine("| Catalogue test IDs covered only by partial checks (suffixed IDs, C-182) | ${model.partiallyCovered.size} |")
    if (model.unreadableResultFiles.isNotEmpty()) {
      sb.appendLine("| Unreadable result files | ${model.unreadableResultFiles.size} |")
    }
    fun row(first: List<String>, entry: TestEntry) = row(first, entry, model.partialOf(entry))
    sb.appendLine()
    sb.appendLine("## Requirement to tests")
    sb.appendLine()
    sb.appendLine("| Requirement | Test ID | Test | Suite | Last result |")
    sb.appendLine("|---|---|---|---|---|")
    for ((req, list) in model.byRequirement) {
      for (entry in list) sb.appendLine(row(listOf(req), entry))
    }
    sb.appendLine()
    sb.appendLine("## Test IDs without a requirement tag")
    sb.appendLine()
    sb.appendLine("| Test ID | Test | Suite | Last result |")
    sb.appendLine("|---|---|---|---|")
    for (entry in model.withoutRequirement) sb.appendLine(row(emptyList(), entry))
    if (model.partiallyCovered.isNotEmpty()) {
      sb.appendLine()
      sb.appendLine("## Catalogue test IDs covered only by partial checks")
      sb.appendLine()
      sb.appendLine(
        "A suffixed ID checks part of its catalogue ID (ADR-0010 C-122, C-182). No test with the catalogue ID " +
          "itself has passed, so the passed results of these partial tests read \"partial of <ID>\" above."
      )
      sb.appendLine()
      sb.appendLine("| Catalogue test ID | Partial test IDs |")
      sb.appendLine("|---|---|")
      for ((id, partial) in model.partiallyCovered) sb.appendLine("| $id | ${partial.joinToString(", ")} |")
    }
    if (quarantined.isNotEmpty()) {
      sb.appendLine()
      sb.appendLine("## Quarantined tests")
      sb.appendLine()
      sb.appendLine("| Test ID | Test | Suite | Last result |")
      sb.appendLine("|---|---|---|---|")
      for (entry in quarantined.sortedWith(Traceability.entryOrder)) sb.appendLine(row(emptyList(), entry))
    }
    val failed = entries.filter { it.result?.status == Status.FAILED }
    if (failed.isNotEmpty()) {
      sb.appendLine()
      sb.appendLine("## Failed in the last results (tagged or not)")
      sb.appendLine()
      sb.appendLine("| Test ID | Test | Suite | Last result |")
      sb.appendLine("|---|---|---|---|")
      for (entry in failed.sortedWith(Traceability.entryOrder)) sb.appendLine(row(emptyList(), entry))
    }
    if (model.unreadableResultFiles.isNotEmpty()) {
      sb.appendLine()
      sb.appendLine("## Unreadable result files")
      sb.appendLine()
      model.unreadableResultFiles.forEach { sb.appendLine("- `$it`") }
    }
    return sb.toString()
  }

  /** One table row; [partialOf]: the catalogue IDs only partial checks cover (C-182). */
  private fun row(first: List<String>, entry: TestEntry, partialOf: List<String>): String {
    val cells =
      first +
        listOf(
          entry.testIds.joinToString(", ").ifEmpty { "-" },
          "`${shortName(entry.className)}` ${entry.name} ([${entry.file}:${entry.line}](${entry.file}))",
          entry.result?.suites?.joinToString(", ") ?: entry.suite,
          result(entry.result) +
            if (entry.result?.status == Status.PASSED && partialOf.isNotEmpty()) ", partial of ${partialOf.joinToString(", ")}" else "",
        )
    return "| " + cells.joinToString(" | ") { escape(it) } + " |"
  }

  /** "com.example.FooTest$Inner" -> "FooTest$Inner"; a UI test file "ui/src/a/b.test.ts" -> "b.test.ts". */
  private fun shortName(className: String): String =
    if (className.contains('/')) className.substringAfterLast('/') else className.substringAfterLast('.')

  private fun result(result: ResultSummary?): String =
    when (result) {
      null -> "NOT RUN"
      else ->
        result.status.name + (result.lastRun?.let { " ($it)" } ?: "") + (if (result.runs > 1) " x${result.runs}" else "") +
          if (result.status != Status.PASSED && result.reasons.isNotEmpty()) {
            val reason = result.reasons.first()
            " [" + (if (reason.length > REASON_LIMIT) reason.take(REASON_LIMIT - 3) + "..." else reason) + "]"
          } else ""
    }

  private fun escape(cell: String): String = cell.replace("|", "\\|").replace("\r", " ").replace("\n", " ")

  fun json(model: TraceabilityModel, generatedAt: String): String {
    fun entry(e: TestEntry): Map<String, Any?> {
      val map: MutableMap<String, Any?> =
        linkedMapOf(
          "testIds" to e.testIds,
          "requirements" to e.requirements,
          "tags" to e.tags,
          "class" to e.className,
          "name" to e.name,
          "file" to e.file,
          "line" to e.line,
          "suite" to e.suite,
          "source" to e.source,
          "result" to
            e.result?.let {
              linkedMapOf<String, Any?>("status" to it.status.name, "runs" to it.runs, "lastRun" to it.lastRun, "suites" to it.suites).apply {
                if (it.reasons.isNotEmpty()) put("reasons", it.reasons)
              }
            },
        )
      // C-182: the catalogue IDs this test checks only partly and no full test has passed.
      model.partialOf(e).takeIf { it.isNotEmpty() }?.let { map["partialOf"] = it }
      return map
    }
    val root =
      linkedMapOf(
        "generatedAt" to generatedAt,
        "sourceFiles" to model.sourceFiles,
        "resultFiles" to model.resultFiles,
        "summary" to
          linkedMapOf(
            "requirements" to model.byRequirement.size,
            "testIds" to model.testIds.size,
            "testsWithRequirement" to model.entries.count { it.requirements.isNotEmpty() },
            "testsWithIdWithoutRequirement" to model.withoutRequirement.size,
            "testsWithoutIdOrRequirement" to model.testsWithoutId,
            "quarantined" to model.entries.count { it.quarantined },
            "quarantineLimit" to QUARANTINE_LIMIT,
            "partiallyCoveredTestIds" to model.partiallyCovered.size,
            "lastResults" to model.statusCounts(),
          ),
        "sources" to
          model.sources.map { s ->
            linkedMapOf(
              "source" to s.source,
              "sourceFiles" to s.sourceFiles,
              "resultFiles" to s.resultFiles,
              "resultCases" to s.resultCases,
              "unlistedResultCases" to s.unlistedResultCases,
              "lastResults" to model.statusCounts(s.source),
            )
          },
        "requirements" to model.byRequirement.map { (req, list) -> linkedMapOf("id" to req, "tests" to list.map(::entry)) },
        "testIdsWithoutRequirement" to model.withoutRequirement.map(::entry),
        "partiallyCoveredTestIds" to model.partiallyCovered.map { (id, partial) -> linkedMapOf("id" to id, "partialIds" to partial) },
        "quarantined" to model.entries.filter { it.quarantined }.sortedWith(Traceability.entryOrder).map(::entry),
        "unreadableResultFiles" to model.unreadableResultFiles,
      )
    return Json.write(root) + "\n"
  }
}

/** Minimal JSON writer (maps, lists, strings, numbers, booleans, null); no dependency needed. */
object Json {
  fun write(value: Any?): String = StringBuilder().also { append(it, value, 0) }.toString()

  private fun append(sb: StringBuilder, value: Any?, indent: Int) {
    when (value) {
      null -> sb.append("null")
      is String -> string(sb, value)
      is Number,
      is Boolean -> sb.append(value.toString())
      is Map<*, *> -> {
        if (value.isEmpty()) {
          sb.append("{}")
          return
        }
        sb.append("{\n")
        value.entries.forEachIndexed { i, (k, v) ->
          sb.append("  ".repeat(indent + 1))
          string(sb, k.toString())
          sb.append(": ")
          append(sb, v, indent + 1)
          sb.append(if (i < value.size - 1) ",\n" else "\n")
        }
        sb.append("  ".repeat(indent)).append('}')
      }
      is Iterable<*> -> {
        val items = value.toList()
        if (items.isEmpty()) {
          sb.append("[]")
          return
        }
        if (items.all { it == null || it is String || it is Number || it is Boolean }) {
          sb.append('[')
          items.forEachIndexed { i, v ->
            if (i > 0) sb.append(", ")
            append(sb, v, indent)
          }
          sb.append(']')
          return
        }
        sb.append("[\n")
        items.forEachIndexed { i, v ->
          sb.append("  ".repeat(indent + 1))
          append(sb, v, indent + 1)
          sb.append(if (i < items.size - 1) ",\n" else "\n")
        }
        sb.append("  ".repeat(indent)).append(']')
      }
      else -> string(sb, value.toString())
    }
  }

  private fun string(sb: StringBuilder, s: String) {
    sb.append('"')
    for (c in s) {
      when {
        c == '"' -> sb.append("\\\"")
        c == '\\' -> sb.append("\\\\")
        c == '\n' -> sb.append("\\n")
        c == '\r' -> sb.append("\\r")
        c == '\t' -> sb.append("\\t")
        c < ' ' -> sb.append("\\u%04x".format(c.code))
        else -> sb.append(c)
      }
    }
    sb.append('"')
  }
}

/**
 * `traceabilityReport`: requirement -> tests -> last result, as build/reports/traceability.md and
 * build/reports/traceability.json (18 §3, §16, DSN-18-01). Reads files only: no network, no
 * containers, no test execution. Run it after the test tasks whose results it should show.
 */
@DisableCachingByDefault(because = "Reads the whole workspace; fast, and always reflects the latest results")
abstract class TraceabilityReport : DefaultTask() {

  @get:Internal abstract val repositoryRoot: DirectoryProperty

  @get:OutputFile abstract val markdownReport: RegularFileProperty

  @get:OutputFile abstract val jsonReport: RegularFileProperty

  /** False: read no result file, every test NOT RUN (a clean checkout; `-Ptraceability.results=none`). */
  @get:Input abstract val includeResults: Property<Boolean>

  init {
    group = "reporting"
    description =
      "Writes build/reports/traceability.md and .json: requirement -> tests -> last result (18 §3, §16)."
    includeResults.convention(true)
    doNotTrackState("Scans test sources and results (JUnit, Vitest, Playwright, shell, matrix) of the whole workspace on every run")
  }

  @TaskAction
  fun generate() {
    val root = repositoryRoot.get().asFile
    val model = RepositoryScan.model(root, includeResults.get())
    val now = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString()
    val md = markdownReport.get().asFile.apply { parentFile.mkdirs() }
    md.writeText(TraceabilityRenderer.markdown(model, now))
    jsonReport.get().asFile.apply { parentFile.mkdirs() }.writeText(TraceabilityRenderer.json(model, now))
    val failed = model.entries.count { it.result?.status == Status.FAILED }
    logger.lifecycle(
      "Traceability: ${model.byRequirement.size} requirements, ${model.testIds.size} test IDs, " +
        "${model.entries.count { it.requirements.isNotEmpty() }} tagged tests ($failed failed in the last results). " +
        "Report: ${md.toURI()}"
    )
    for (s in model.sources) {
      val c = model.statusCounts(s.source)
      logger.lifecycle(
        "  ${s.source}: ${s.sourceFiles} source files, ${s.resultFiles} result files; " +
          "PASSED ${c["PASSED"]}, FAILED ${c["FAILED"]}, SKIPPED ${c["SKIPPED"]}, NOT RUN ${c["NOT RUN"]}"
      )
    }
    if (model.unreadableResultFiles.isNotEmpty()) {
      logger.warn("Traceability: ${model.unreadableResultFiles.size} unreadable result files: ${model.unreadableResultFiles.joinToString(", ")}")
    }
  }
}
