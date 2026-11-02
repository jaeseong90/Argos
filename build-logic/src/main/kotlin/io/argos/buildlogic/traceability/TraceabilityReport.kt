// SPDX-License-Identifier: Apache-2.0
package io.argos.buildlogic.traceability

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
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

/**
 * Walks the repository for test sources and JUnit XML results (no Gradle model needed, so the
 * included build build-logic and the UI are covered too):
 * - Java/Kotlin sources of test source sets: `<project>/src/<set>/` where `<set>` is `test` or
 *   ends with `Test` (integrationTest, archTest, matrixTest, securityTest, contractTest, ...);
 * - UI tests: `*.test.*` (Vitest) and `*.spec.*` (Playwright) below ui/, outside node_modules;
 * - results: the XML files in `build/test-results/<task>` of every project.
 * Skipped: node_modules, VCS and IDE directories, every other build output, docs/.
 */
object RepositoryScan {

  private val skippedDirectories = setOf("node_modules", ".git", ".gradle", ".kotlin", ".idea", ".vscode", "dist", "coverage", "playwright-report", "docs")
  private val scriptTest = Regex("""^.+\.(test|spec)\.(ts|tsx|mts|js|mjs)$""")

  data class Collected(val junitSources: List<File>, val scriptSources: List<File>, val resultFiles: List<File>)

  fun collect(root: File): Collected {
    val junit = mutableListOf<File>()
    val scripts = mutableListOf<File>()
    val results = mutableListOf<File>()
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
            val testResults = dir.resolve("test-results").toFile()
            testResults.listFiles { f -> f.isDirectory }.orEmpty().sortedBy { it.name }.forEach { task ->
              results += task.listFiles { f -> f.isFile && f.name.endsWith(".xml") }.orEmpty().sortedBy { it.name }
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
          }
          return FileVisitResult.CONTINUE
        }
      },
    )
    return Collected(junit.sortedBy { it.path }, scripts.sortedBy { it.path }, results.sortedBy { it.path })
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

  /** Scans everything below [root] into a model. */
  fun model(root: File): TraceabilityModel {
    val files = collect(root)
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
    val unreadable = mutableListOf<String>()
    val results = mutableListOf<Pair<String, JUnitXmlResults.Case>>()
    for (file in files.resultFiles) {
      val relative = file.relativeTo(root).invariantSeparatorsPath
      val cases = JUnitXmlResults.read(file)
      if (cases.isEmpty() && file.length() > 0 && !file.readText().contains("<testsuite")) unreadable += relative
      val project = projectDirOfResult(relative)
      cases.forEach { results += project to it.copy(file = File(relative)) }
    }
    withoutId += tests.count { it.methodName != null && it.testIds.isEmpty() && it.requirements.isEmpty() }
    return Traceability.build(
      classes,
      tests,
      results,
      sourceFiles = files.junitSources.size + files.scriptSources.size,
      resultFiles = files.resultFiles.size,
      unreadableResultFiles = unreadable,
      testsWithoutId = withoutId,
    )
  }
}

/** Renders the model as build/reports/traceability.md and its JSON twin. */
object TraceabilityRenderer {

  const val QUARANTINE_LIMIT = 10

  fun markdown(model: TraceabilityModel, generatedAt: String): String {
    val sb = StringBuilder()
    val entries = model.entries
    val withReq = entries.filter { it.requirements.isNotEmpty() }
    val quarantined = entries.filter { it.quarantined }
    sb.appendLine("# Requirement to test traceability")
    sb.appendLine()
    sb.appendLine(
      "Generated by `./gradlew traceabilityReport` at $generatedAt from ${model.sourceFiles} test source " +
        "files and ${model.resultFiles} JUnit XML result files of this workspace (docs/18 §3, §16, DSN-18-01)."
    )
    sb.appendLine(
      "Test IDs come from display names, method names and test titles; requirements from " +
        "`@Tag(\"req:<ID>\")` and Playwright `{ type: 'req' }` annotations. \"Last result\" is the newest " +
        "JUnit XML in this workspace; NOT RUN means no result file (run the suite first)."
    )
    sb.appendLine(
      "Skeleton (WO-05): the comparison with the 18 §16 table and the failing CI job are WO-53."
    )
    sb.appendLine()
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
    if (model.unreadableResultFiles.isNotEmpty()) {
      sb.appendLine("| Unreadable result files | ${model.unreadableResultFiles.size} |")
    }
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

  private fun row(first: List<String>, entry: TestEntry): String {
    val cells =
      first +
        listOf(
          entry.testIds.joinToString(", ").ifEmpty { "-" },
          "`${shortName(entry.className)}` ${entry.name} ([${entry.file}:${entry.line}](${entry.file}))",
          entry.result?.suites?.joinToString(", ") ?: entry.suite,
          result(entry.result),
        )
    return "| " + cells.joinToString(" | ") { escape(it) } + " |"
  }

  /** "com.example.FooTest$Inner" -> "FooTest$Inner"; a UI test file "ui/src/a/b.test.ts" -> "b.test.ts". */
  private fun shortName(className: String): String =
    if (className.contains('/')) className.substringAfterLast('/') else className.substringAfterLast('.')

  private fun result(result: ResultSummary?): String =
    when (result) {
      null -> "NOT RUN"
      else -> result.status.name + (result.lastRun?.let { " ($it)" } ?: "") + if (result.runs > 1) " x${result.runs}" else ""
    }

  private fun escape(cell: String): String = cell.replace("|", "\\|").replace("\r", " ").replace("\n", " ")

  fun json(model: TraceabilityModel, generatedAt: String): String {
    fun entry(e: TestEntry): Map<String, Any?> =
      linkedMapOf(
        "testIds" to e.testIds,
        "requirements" to e.requirements,
        "tags" to e.tags,
        "class" to e.className,
        "name" to e.name,
        "file" to e.file,
        "line" to e.line,
        "suite" to e.suite,
        "result" to
          e.result?.let {
            linkedMapOf("status" to it.status.name, "runs" to it.runs, "lastRun" to it.lastRun, "suites" to it.suites)
          },
      )
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
          ),
        "requirements" to model.byRequirement.map { (req, list) -> linkedMapOf("id" to req, "tests" to list.map(::entry)) },
        "testIdsWithoutRequirement" to model.withoutRequirement.map(::entry),
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

  init {
    group = "reporting"
    description =
      "Writes build/reports/traceability.md and .json: requirement -> tests -> last result (18 §3, §16)."
    doNotTrackState("Scans test sources and JUnit XML results of the whole workspace on every run")
  }

  @TaskAction
  fun generate() {
    val root = repositoryRoot.get().asFile
    val model = RepositoryScan.model(root)
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
  }
}
