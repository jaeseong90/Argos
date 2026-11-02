// SPDX-License-Identifier: Apache-2.0
package io.argos.buildlogic.traceability

import java.io.File
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants
import javax.xml.stream.XMLStreamException

/** Reads the JUnit XML files that Gradle test tasks write (build/test-results/<task>/TEST-*.xml). */
object JUnitXmlResults {

  enum class Status {
    PASSED,
    FAILED,
    SKIPPED,
  }

  /**
   * One `<testcase>`: [name] is the JUnit display name (dynamic and parameterized tests included),
   * [suite] the test task that ran it (the results directory name), [file] the XML file.
   */
  data class Case(
    val className: String,
    val name: String,
    val status: Status,
    val timeSeconds: Double,
    val timestamp: String?,
    val suite: String,
    val file: File,
  )

  private val factory: XMLInputFactory =
    XMLInputFactory.newFactory().apply {
      // Result files are local build outputs, but never resolve DTDs or external entities.
      setProperty(XMLInputFactory.SUPPORT_DTD, false)
      setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
    }

  /** The XML files directly in [dir] (one test task), in name order. */
  fun readDirectory(dir: File, suite: String = dir.name): List<Case> =
    dir.listFiles { f -> f.isFile && f.name.endsWith(".xml") }.orEmpty().sortedBy { it.name }.flatMap { read(it, suite) }

  /** Test cases of one XML file; a malformed file yields no cases (reported by the caller). */
  fun read(file: File, suite: String = file.parentFile?.name ?: ""): List<Case> {
    val cases = mutableListOf<Case>()
    try {
      file.inputStream().buffered().use { input ->
        val reader = factory.createXMLStreamReader(input)
        try {
          var timestamp: String? = null
          var className = ""
          var name = ""
          var time = 0.0
          var status = Status.PASSED
          var inCase = false
          while (reader.hasNext()) {
            when (reader.next()) {
              XMLStreamConstants.START_ELEMENT ->
                when (reader.localName) {
                  "testsuite" -> timestamp = reader.getAttributeValue(null, "timestamp")
                  "testcase" -> {
                    inCase = true
                    className = reader.getAttributeValue(null, "classname").orEmpty()
                    name = reader.getAttributeValue(null, "name").orEmpty()
                    time = reader.getAttributeValue(null, "time")?.toDoubleOrNull() ?: 0.0
                    status = Status.PASSED
                  }
                  "failure",
                  "error" -> if (inCase) status = Status.FAILED
                  "skipped" -> if (inCase && status != Status.FAILED) status = Status.SKIPPED
                }
              XMLStreamConstants.END_ELEMENT ->
                if (reader.localName == "testcase" && inCase) {
                  cases += Case(className, name, status, time, timestamp, suite, file)
                  inCase = false
                }
            }
          }
        } finally {
          reader.close()
        }
      }
    } catch (e: XMLStreamException) {
      return emptyList()
    }
    return cases
  }
}
