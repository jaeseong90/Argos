// SPDX-License-Identifier: Apache-2.0
package io.argos.buildlogic

import java.util.zip.ZipFile
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Layout and rules of THIRD-PARTY-NOTICES (O-L04, 19 §5.5), shared by [GenerateThirdPartyNotices]
 * (writes it) and [CheckThirdPartyNotices] (verifies it in the shipped jar).
 *
 * Layout: one section per bundled module, headed by `group:artifact:version` at column 0, with the
 * indented lines `License: <SPDX expression>`, optionally `License text:` and `NOTICE:` followed by
 * the text indented by four spaces, or [noticeReference] for a NOTICE shared by every module of a
 * group. The shared NOTICE texts follow the modules after a [SEPARATOR] line, each headed by
 * [sharedNoticeHeading] at column 0 with the text indented by two spaces.
 */
object ThirdPartyNotices {
  val SEPARATOR = "-".repeat(72)

  const val LICENSE_PREFIX = "  License: "
  const val LICENSE_TEXT_HEADING = "  License text:"
  const val NOTICE_HEADING = "  NOTICE:"

  private const val SHARED_NOTICE_PREFIX = "NOTICE of "

  fun noticeReference(group: String) = "$NOTICE_HEADING see \"$SHARED_NOTICE_PREFIX$group\" below"

  fun sharedNoticeHeading(group: String) =
    "$SHARED_NOTICE_PREFIX$group (applies to every $group module listed above)"

  /**
   * Phrases of the license body of each allow-listed license other than Apache-2.0
   * (build-logic/allowed-licenses.json), compared case-insensitively with whitespace and comment
   * markers collapsed. A license without an entry cannot pass [violations] (fail closed): add its
   * phrases here together with its text.
   */
  val LICENSE_BODY_MARKERS: Map<String, List<String>> =
    mapOf(
      "BSD-3-Clause" to
        listOf(
          "Redistribution and use in source and binary forms",
          "Redistributions in binary form must reproduce the above copyright notice",
          "endorse or promote products derived from this software",
          "THIS SOFTWARE IS PROVIDED BY",
          "ARE DISCLAIMED",
        ),
      "BSD-2-Clause" to
        listOf(
          "Redistribution and use in source and binary forms",
          "Redistributions in binary form must reproduce the above copyright notice",
          "THIS SOFTWARE IS PROVIDED BY",
          "ARE DISCLAIMED",
        ),
      "MIT" to
        listOf(
          "Permission is hereby granted, free of charge, to any person obtaining a copy",
          "The above copyright notice and this permission notice shall be included",
          "THE SOFTWARE IS PROVIDED \"AS IS\"",
        ),
      "MIT-0" to
        listOf(
          "Permission is hereby granted, free of charge, to any person obtaining a copy",
          "THE SOFTWARE IS PROVIDED \"AS IS\"",
        ),
      "ISC" to
        listOf(
          "this software for any purpose with or without fee is hereby granted",
          "above copyright notice and this permission notice appear in all copies",
          "THE SOFTWARE IS PROVIDED \"AS IS\"",
        ),
      "0BSD" to
        listOf(
          "this software for any purpose with or without fee is hereby granted",
          "THE SOFTWARE IS PROVIDED \"AS IS\"",
        ),
      "PostgreSQL" to
        listOf(
          "Permission to use, copy, modify, and distribute this software and its documentation for any purpose, without fee",
          "AS IS",
        ),
      "Unicode-DFS-2015" to
        listOf(
          "Permission is hereby granted, free of charge, to any person obtaining a copy of the Unicode data files",
          "AS IS",
        ),
      "Unicode-DFS-2016" to
        listOf(
          "Permission is hereby granted, free of charge, to any person obtaining a copy of the Unicode data files",
          "AS IS",
        ),
      "CC0-1.0" to listOf("CC0 1.0 Universal", "Statement of Purpose"),
    )

  /** Licenses whose text carries no copyright line of its own (a public-domain dedication). */
  private val NO_COPYRIGHT_LINE = setOf("CC0-1.0")

  /** The license every Argos artifact ships as META-INF/LICENSE (O-L01). */
  private const val PROJECT_LICENSE = "Apache-2.0"

  /** A copyright line with a year: "Copyright 2008 Google Inc.", "Copyright (c) 2000-2011 INRIA". */
  private val COPYRIGHT_LINE = Regex("(?i)copyright\\s*(\\(c\\)|©)?\\s*\\d{4}")

  private val MODULE_HEADER = Regex("^([^\\s:]+):([^\\s:]+):(\\S+)$")

  /** A bundled module as META-INF/argos-deps.txt lists it. */
  data class Dependency(val group: String, val artifact: String, val version: String, val license: String) {
    val key: String
      get() = "$group:$artifact"

    override fun toString() = "$group:$artifact:$version ($license)"
  }

  /** A module section of THIRD-PARTY-NOTICES: its header version and its indented lines. */
  data class Section(val version: String, val lines: List<String>)

  /** Parses META-INF/argos-deps.txt: `group:artifact:version:license`, `#` comment lines. */
  fun parseDependencies(text: String): List<Dependency> =
    text
      .lineSequence()
      .map { it.trim() }
      .filter { it.isNotEmpty() && !it.startsWith("#") }
      .map { line ->
        val parts = line.split(':', limit = 4)
        if (parts.size != 4) throw GradleException("Malformed argos-deps.txt line: $line")
        Dependency(parts[0], parts[1], parts[2], parts[3])
      }
      .toList()

  /** Module sections by `group:artifact`, and shared NOTICE texts by group. */
  fun parse(text: String): Pair<Map<String, Section>, Map<String, List<String>>> {
    val sections = linkedMapOf<String, Section>()
    val shared = linkedMapOf<String, List<String>>()
    var current: MutableList<String>? = null
    for (raw in text.replace("\r\n", "\n").lines()) {
      if (raw.isEmpty() || raw[0].isWhitespace()) {
        current?.add(raw)
        continue
      }
      val header = MODULE_HEADER.matchEntire(raw)
      current =
        when {
          header != null ->
            mutableListOf<String>().also {
              sections["${header.groupValues[1]}:${header.groupValues[2]}"] = Section(header.groupValues[3], it)
            }
          raw.startsWith(SHARED_NOTICE_PREFIX) ->
            mutableListOf<String>().also { shared[raw.removePrefix(SHARED_NOTICE_PREFIX).substringBefore(' ')] = it }
          else -> null
        }
    }
    return sections to shared
  }

  /** Lower case, comment markers removed, whitespace collapsed: for phrase matching only. */
  private fun normalize(lines: List<String>): String =
    lines
      .joinToString(" ") { it.trim().trimStart('*', '/', '#').trim() }
      .replace(Regex("\\s+"), " ")
      .lowercase()

  private fun covers(pattern: String, dependency: Dependency) =
    if (pattern.contains(':')) pattern == dependency.key else pattern == dependency.group

  /**
   * Violations of O-L04 in [notices] for the bundled [dependencies]:
   * - every module has a section with its version and license;
   * - a module whose license is not satisfied by Apache-2.0 alone (BSD, MIT, ...) carries a copyright
   *   line and the license body (BSD-3-Clause condition 2, MIT "above copyright notice and this
   *   permission notice") in its own section;
   * - every module matched by [noticeRequired] (`group` or `group:artifact`) carries a NOTICE, inline
   *   or as a reference to a non-empty shared NOTICE text (Apache-2.0 §4(d)); a pattern that matches
   *   no bundled module is reported as stale.
   */
  fun violations(notices: String, dependencies: List<Dependency>, noticeRequired: List<String>): List<String> {
    val (sections, shared) = parse(notices)
    val result = mutableListOf<String>()
    for (dependency in dependencies) {
      val section = sections[dependency.key]
      if (section == null) {
        result += "$dependency: no section in THIRD-PARTY-NOTICES"
        continue
      }
      if (section.version != dependency.version) {
        result += "$dependency: THIRD-PARTY-NOTICES names version ${section.version}"
      }
      val stated = section.lines.firstOrNull { it.startsWith(LICENSE_PREFIX) }?.removePrefix(LICENSE_PREFIX)?.trim()
      if (stated != dependency.license) {
        result += "$dependency: THIRD-PARTY-NOTICES states license '${stated ?: "none"}'"
      }
      result += licenseTextViolations(dependency, section)
      if (noticeRequired.any { covers(it, dependency) } && !hasNotice(dependency, section, shared)) {
        result += "$dependency: the original NOTICE text is missing (Apache-2.0 4(d))"
      }
    }
    for (pattern in noticeRequired) {
      if (dependencies.none { covers(pattern, it) }) {
        result += "NOTICE required for '$pattern', but no bundled module matches it (stale entry)"
      }
    }
    return result
  }

  private fun hasNotice(dependency: Dependency, section: Section, shared: Map<String, List<String>>): Boolean {
    val index = section.lines.indexOfFirst { it.startsWith(NOTICE_HEADING) }
    if (index < 0) return false
    val heading = section.lines[index]
    if (heading == NOTICE_HEADING) {
      return section.lines.drop(index + 1).takeWhile { it.isEmpty() || it.startsWith("    ") }.any { it.isNotBlank() }
    }
    return heading == noticeReference(dependency.group) &&
      shared[dependency.group].orEmpty().any { it.isNotBlank() }
  }

  private fun licenseTextViolations(dependency: Dependency, section: Section): List<String> {
    val expression =
      try {
        SpdxExpression.parse(dependency.license)
      } catch (e: GradleException) {
        return listOf("$dependency: ${e.message}")
      }
    if (expression.satisfiedBy(setOf(PROJECT_LICENSE))) return emptyList()
    val text = normalize(section.lines)
    val hasCopyright = section.lines.any { COPYRIGHT_LINE.containsMatchIn(it) }
    val reproduced = mutableSetOf(PROJECT_LICENSE)
    val missing = mutableListOf<String>()
    for (id in expression.identifiers().distinct().filter { it != PROJECT_LICENSE }) {
      val markers = LICENSE_BODY_MARKERS[id]
      when {
        markers == null ->
          missing += "no license-text phrases are known for $id (add them to ThirdPartyNotices.LICENSE_BODY_MARKERS)"
        markers.any { !text.contains(normalize(listOf(it))) } ->
          missing +=
            "the $id license text is missing (expected: " +
              markers.filter { !text.contains(normalize(listOf(it))) }.joinToString("; ") { "\"$it\"" } + ")"
        !hasCopyright && id !in NO_COPYRIGHT_LINE -> missing += "no copyright line for $id"
        else -> reproduced += id
      }
    }
    return if (expression.satisfiedBy(reproduced)) emptyList()
    else missing.map { "$dependency: $it (O-L04)" }
  }
}

/**
 * checkThirdPartyNotices (O-L04, T-BLD-003): META-INF/THIRD-PARTY-NOTICES of the jar names every
 * module of META-INF/argos-deps.txt with its version and license, reproduces the copyright line and
 * the license text of every module whose license is not Apache-2.0, and carries the original NOTICE
 * of the modules matched by [noticeRequired] ([ThirdPartyNotices.violations]). Writes the checked
 * modules to [reportFile].
 */
abstract class CheckThirdPartyNotices : DefaultTask() {

  @get:InputFile
  @get:PathSensitive(PathSensitivity.NONE)
  abstract val jar: RegularFileProperty

  /** `group` or `group:artifact` of bundled modules whose upstream project has a NOTICE file. */
  @get:Input abstract val noticeRequired: ListProperty<String>

  @get:OutputFile abstract val reportFile: RegularFileProperty

  init {
    group = "verification"
    description =
      "Checks the jar's THIRD-PARTY-NOTICES: every bundled module, license texts of non-Apache licenses, original NOTICE texts (O-L04)."
    noticeRequired.convention(emptyList())
  }

  @TaskAction
  fun check() {
    val file = jar.get().asFile
    val (deps, notices) =
      ZipFile(file).use { zip ->
        fun read(name: String): String =
          zip.getEntry(name)?.let { entry -> zip.getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8) } }
            ?: throw GradleException("checkThirdPartyNotices: ${file.name} has no $name (T-BLD-003)")
        read("META-INF/argos-deps.txt") to read("META-INF/THIRD-PARTY-NOTICES")
      }
    val dependencies = ThirdPartyNotices.parseDependencies(deps)
    val violations = ThirdPartyNotices.violations(notices, dependencies, noticeRequired.get())
    reportFile.get().asFile.apply { parentFile.mkdirs() }
      .writeText(
        buildString {
          append("THIRD-PARTY-NOTICES of ${file.name}: ${dependencies.size} bundled module(s)\n")
          append("NOTICE required for: ${noticeRequired.get().joinToString(", ").ifEmpty { "-" }}\n")
          dependencies.forEach { append(it).append('\n') }
          append(if (violations.isEmpty()) "result: ok\n" else "result: ${violations.size} violation(s)\n")
          violations.forEach { append("  ").append(it).append('\n') }
        },
        Charsets.UTF_8,
      )
    if (violations.isNotEmpty()) {
      throw GradleException(
        "checkThirdPartyNotices: META-INF/THIRD-PARTY-NOTICES of ${file.name} is incomplete (O-L04):\n" +
          violations.joinToString("\n") { "  $it" } +
          "\nAdd the upstream text to agent/agent-dist/src/notices (README.md there) and wire it in " +
          "generateThirdPartyNotices."
      )
    }
    logger.info("checkThirdPartyNotices: ${file.name}: ${dependencies.size} bundled module(s), notices complete")
  }
}
