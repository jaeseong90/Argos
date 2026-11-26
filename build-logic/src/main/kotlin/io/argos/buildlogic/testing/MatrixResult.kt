// SPDX-License-Identifier: Apache-2.0
package io.argos.buildlogic.testing

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import io.argos.buildlogic.traceability.JUnitXmlResults
import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeParseException
import java.util.Properties
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants
import javax.xml.stream.XMLStreamException

/**
 * The result of one matrix combination (18 §6.2): `build/matrix/<ID>.json`, written after the
 * combination's Test task from its JUnit XML and the files its tests wrote to
 * `build/matrix/<ID>/` (argos.test.matrix.resultDir). MatrixReport reads it back.
 */
data class MatrixRunResult(
  val id: String,
  val task: String,
  val commit: String,
  val os: String,
  val startedAt: String?,
  val finishedAt: String?,
  val durationSeconds: Long,
  val passed: Int,
  val failed: Int,
  val skipped: Int,
  val cases: List<Case>,
  val skipReasons: Map<String, Int>,
  val failures: List<String>,
  val metrics: Map<String, String>,
  val lines: List<String>,
  val missingRequirements: List<String>,
) {
  data class Case(val className: String, val name: String, val status: String, val message: String)

  fun toJson(): String =
    JsonOutput.prettyPrint(
      JsonOutput.toJson(
        linkedMapOf(
          "format" to 1,
          "id" to id,
          "task" to task,
          "commit" to commit,
          "os" to os,
          "started_at" to startedAt,
          "finished_at" to finishedAt,
          "duration_seconds" to durationSeconds,
          "passed" to passed,
          "failed" to failed,
          "skipped" to skipped,
          "skip_reasons" to skipReasons,
          "failures" to failures,
          "metrics" to metrics,
          "missing_requirements" to missingRequirements,
          "lines" to lines,
          "cases" to cases.map { linkedMapOf("class" to it.className, "name" to it.name, "status" to it.status, "message" to it.message) },
        )
      )
    ) + "\n"

  companion object {
    fun fromJson(file: File): MatrixRunResult {
      val map = JsonSlurper().parse(file) as? Map<*, *> ?: throw MatrixDefinitionException("${file.path}: not a JSON object")
      fun s(key: String) = map[key]?.toString()
      fun i(key: String) = (map[key] as? Number)?.toInt() ?: 0
      fun strings(key: String) = (map[key] as? List<*>).orEmpty().map { it.toString() }
      @Suppress("UNCHECKED_CAST")
      fun strMap(key: String) = (map[key] as? Map<String, Any?>).orEmpty().mapValues { it.value.toString() }
      return MatrixRunResult(
        id = s("id") ?: throw MatrixDefinitionException("${file.path}: no 'id'"),
        task = s("task").orEmpty(),
        commit = s("commit") ?: "unknown",
        os = s("os").orEmpty(),
        startedAt = s("started_at"),
        finishedAt = s("finished_at"),
        durationSeconds = (map["duration_seconds"] as? Number)?.toLong() ?: 0,
        passed = i("passed"),
        failed = i("failed"),
        skipped = i("skipped"),
        cases =
          (map["cases"] as? List<*>).orEmpty().mapNotNull { it as? Map<*, *> }.map {
            Case(it["class"]?.toString().orEmpty(), it["name"]?.toString().orEmpty(), it["status"]?.toString().orEmpty(), it["message"]?.toString().orEmpty())
          },
        skipReasons = strMap("skip_reasons").mapValues { it.value.toDoubleOrNull()?.toInt() ?: 0 },
        failures = strings("failures"),
        metrics = strMap("metrics"),
        lines = strings("lines"),
        missingRequirements = strings("missing_requirements"),
      )
    }
  }
}

/** Builds [MatrixRunResult] from JUnit XML (statuses through [JUnitXmlResults]) and result files. */
object MatrixResults {

  /** Metrics of a combination (18 §6.2): startup time, transformed classes, transactions. */
  val METRIC_KEYS: List<String> = listOf("startup_ms", "transformed_classes", "transactions")

  private const val MESSAGE_LIMIT = 300
  private const val LINE_LIMIT = 200
  private val resultLine = Regex("""^\[mx-[a-z0-9-]+].*""")
  private val token = Regex("""([a-z_]+)=([0-9]+)""")
  private val camel = Regex("([a-z])([A-Z])")

  private val factory: XMLInputFactory =
    XMLInputFactory.newFactory().apply {
      setProperty(XMLInputFactory.SUPPORT_DTD, false)
      setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
    }

  /**
   * Reads the JUnit XML of [xmlDir] (one Test task) and the `*.properties` and `*.txt` files of
   * [resultDir]; [missingRequirements] are the row's `requires` that were not met at run time.
   */
  fun collect(
    id: String,
    task: String,
    xmlDir: File,
    resultDir: File?,
    commit: String,
    os: String,
    missingRequirements: List<String>,
  ): MatrixRunResult {
    val cases = JUnitXmlResults.readDirectory(xmlDir)
    val details = xmlDir.listFiles { f -> f.isFile && f.name.endsWith(".xml") }.orEmpty().sortedBy { it.name }.map(::details)
    val messages = details.flatMap { it.messages.entries }.associate { it.key to it.value }
    val resultCases =
      cases.map { c ->
        MatrixRunResult.Case(c.className, c.name, c.status.name, messages[c.className + "\u0000" + c.name].orEmpty())
      }
    val skipReasons =
      resultCases.filter { it.status == "SKIPPED" }
        .groupingBy { it.message.ifEmpty { "skipped without a reason" } }
        .eachCount()
        .toSortedMap()
    val failures =
      resultCases.filter { it.status == "FAILED" }.map { "${simpleName(it.className)} > ${it.name}: ${it.message.ifEmpty { "failed" }}" }
    val lines =
      (details.flatMap { it.systemOut } + resultFileLines(resultDir)).filter { resultLine.matches(it) }.distinct().take(LINE_LIMIT)
    val metrics = linkedMapOf<String, String>()
    // [mx-...] result lines first, then the files of the result directory (they win).
    lines.forEach { line -> token.findAll(line).forEach { m -> if (m.groupValues[1] in METRIC_KEYS) metrics[m.groupValues[1]] = m.groupValues[2] } }
    resultProperties(resultDir).forEach { (k, v) -> if (k in METRIC_KEYS) metrics[k] = v }
    val starts = details.mapNotNull { it.start }
    val ends = details.mapNotNull { d -> d.start?.plusMillis((d.seconds * 1000).toLong()) }
    val started = starts.minOrNull()
    val finished = ends.maxOrNull()
    return MatrixRunResult(
      id = id,
      task = task,
      commit = commit,
      os = os,
      startedAt = started?.toString(),
      finishedAt = finished?.toString(),
      durationSeconds = if (started != null && finished != null) Duration.between(started, finished).seconds else 0,
      passed = resultCases.count { it.status == "PASSED" },
      failed = resultCases.count { it.status == "FAILED" },
      skipped = resultCases.count { it.status == "SKIPPED" },
      cases = resultCases,
      skipReasons = skipReasons,
      failures = failures,
      metrics = METRIC_KEYS.filter { it in metrics }.associateWith { metrics.getValue(it) },
      lines = lines,
      missingRequirements = missingRequirements,
    )
  }

  /** The row's `requires` that are not met: env:NAME unset or blank, prop:name absent, other os. */
  fun missingRequirements(requires: List<String>, env: (String) -> String?, props: Map<String, String>, os: String): List<String> =
    requires.filter { req ->
      val value = req.substringAfter(':')
      when (req.substringBefore(':')) {
        "env" -> env(value).isNullOrBlank()
        "prop" -> props[value].isNullOrBlank()
        "os" -> osFamily(os) != value
        else -> true
      }
    }

  fun osFamily(osName: String): String {
    val name = osName.lowercase()
    return when {
      name.contains("win") -> "windows"
      name.contains("mac") || name.contains("darwin") -> "mac"
      else -> "linux"
    }
  }

  /** The skip or failure reason of a JUnit message: first line, assumption prefixes removed, capped. */
  fun reason(message: String): String {
    var text = message.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().trim()
    for (prefix in listOf("org.opentest4j.TestAbortedException: ", "Assumption failed: ", "assumption is not true")) {
      text = text.removePrefix(prefix).trim()
    }
    return if (text.length > MESSAGE_LIMIT) text.substring(0, MESSAGE_LIMIT - 3) + "..." else text
  }

  private fun simpleName(className: String) = className.substringAfterLast('.')

  private class Details(val start: Instant?, val seconds: Double, val messages: Map<String, String>, val systemOut: List<String>)

  /** Testsuite timestamp and time, per-case failure and skip messages, system-out lines. */
  private fun details(file: File): Details {
    var start: Instant? = null
    var seconds = 0.0
    val messages = LinkedHashMap<String, String>()
    val out = mutableListOf<String>()
    try {
      file.inputStream().buffered().use { input ->
        val reader = factory.createXMLStreamReader(input)
        try {
          var key: String? = null
          var inOut = false
          val text = StringBuilder()
          while (reader.hasNext()) {
            when (reader.next()) {
              XMLStreamConstants.START_ELEMENT ->
                when (reader.localName) {
                  "testsuite" -> {
                    start = parseTimestamp(reader.getAttributeValue(null, "timestamp"))
                    seconds = reader.getAttributeValue(null, "time")?.toDoubleOrNull() ?: 0.0
                  }
                  "testcase" ->
                    key = reader.getAttributeValue(null, "classname").orEmpty() + "\u0000" + reader.getAttributeValue(null, "name").orEmpty()
                  "failure",
                  "error",
                  "skipped" -> {
                    val k = key
                    val message = reader.getAttributeValue(null, "message")
                    if (k != null && message != null && (reader.localName != "skipped" || k !in messages)) messages[k] = reason(message)
                  }
                  "system-out" -> {
                    inOut = true
                    text.setLength(0)
                  }
                }
              XMLStreamConstants.CHARACTERS,
              XMLStreamConstants.CDATA -> if (inOut) text.append(reader.text)
              XMLStreamConstants.END_ELEMENT ->
                when (reader.localName) {
                  "testcase" -> key = null
                  "system-out" -> {
                    inOut = false
                    out += text.lines().map { it.trim() }
                  }
                }
            }
          }
        } finally {
          reader.close()
        }
      }
    } catch (e: XMLStreamException) {
      return Details(null, 0.0, emptyMap(), emptyList())
    }
    return Details(start, seconds, messages, out)
  }

  /** Gradle writes `2026-11-25T23:44:11.564Z`; older versions wrote a UTC time without the zone. */
  private fun parseTimestamp(value: String?): Instant? {
    if (value == null) return null
    return try {
      Instant.parse(value)
    } catch (e: DateTimeParseException) {
      try {
        LocalDateTime.parse(value).toInstant(ZoneOffset.UTC)
      } catch (e2: DateTimeParseException) {
        null
      }
    }
  }

  private fun resultFiles(dir: File?, suffix: String): List<File> =
    dir?.listFiles { f -> f.isFile && f.name.endsWith(suffix) }.orEmpty().sortedBy { it.name }

  private fun resultFileLines(dir: File?): List<String> =
    resultFiles(dir, ".txt").flatMap { it.readLines(Charsets.UTF_8).map(String::trim) }

  /** key=value pairs of the result directory; camelCase keys are accepted as snake_case. */
  private fun resultProperties(dir: File?): Map<String, String> {
    val out = LinkedHashMap<String, String>()
    resultFiles(dir, ".properties").forEach { file ->
      val props = Properties()
      file.reader(Charsets.UTF_8).use { props.load(it) }
      props.stringPropertyNames().sorted().forEach { name ->
        out[camel.replace(name) { "${it.groupValues[1]}_${it.groupValues[2]}" }.lowercase()] = props.getProperty(name).trim()
      }
    }
    return out
  }
}
