// SPDX-License-Identifier: Apache-2.0
package io.argos.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * checkMajorVersion (19 §3.3 step 8, T-BLD-001, ADR-0003 C-22): the agent's own `.class` and
 * `.classdata` entries have class file major version 52 exactly; shaded third-party classes
 * (`inst/io/argos/agent/shaded/`) have major version 52 or lower. Both rules mean "loadable by
 * Java 8" (F-A31).
 */
abstract class CheckMajorVersion : DefaultTask() {

  @get:InputFile
  @get:PathSensitive(PathSensitivity.NONE)
  abstract val jar: RegularFileProperty

  /** Required major version of the agent's own classes. */
  @get:Input abstract val ownMajor: Property<Int>

  /** Highest allowed major version of shaded third-party classes. */
  @get:Input abstract val maxShadedMajor: Property<Int>

  /** Empty marker file written on success, so the task is up to date for an unchanged jar. */
  @get:OutputFile abstract val resultFile: RegularFileProperty

  init {
    group = "verification"
    description =
      "Checks class file major versions: own classes == 52, shaded classes <= 52 (T-BLD-001, C-22)."
    ownMajor.convention(ClassFiles.JAVA_8_MAJOR)
    maxShadedMajor.convention(ClassFiles.JAVA_8_MAJOR)
  }

  @TaskAction
  fun check() {
    val file = jar.get().asFile
    val violations = ClassFiles.majorVersionViolations(file, ownMajor.get(), maxShadedMajor.get())
    if (violations.isNotEmpty()) {
      throw GradleException(
        "checkMajorVersion: ${violations.size} class file(s) in ${file.name} violate the major " +
          "version rule (own classes == ${ownMajor.get()}, shaded classes <= ${maxShadedMajor.get()}; " +
          "Java 8, F-A31, ADR-0003 C-22):\n  " + violations.joinToString("\n  ")
      )
    }
    val counts = ClassFiles.classEntries(file).map { (name, bytes) -> name to ClassFiles.majorVersion(bytes) }
      .groupingBy { (name, major) -> (if (ClassFiles.isShadedEntry(name)) "shaded" else "own") + " major $major" }
      .eachCount()
      .toSortedMap()
    resultFile.get().asFile.writeText(counts.entries.joinToString("\n", postfix = "\n") { "${it.key}: ${it.value}" })
    logger.info("checkMajorVersion: ${file.name}: $counts")
  }
}

/**
 * How property-name strings of shaded libraries (`io.netty.noUnsafe`, `net.bytebuddy.experimental`,
 * ...) must look after relocation (19 §3.3 step 7, 19 §13 #6, T-BLD-005). Decided by WO-02:
 * [RELOCATED] (ADR-0003 C-23, ADR-0008).
 */
enum class RelocatedStringPolicy {
  /** The agent sets the relocated names (`io.argos.agent.shaded.io.netty.noUnsafe`). */
  RELOCATED,
  /** Relocation excludes the property names; the libraries read the original names. */
  ORIGINAL,
  /** Not decided yet (10 §10.3 / WO-02): report what the jar contains, never fail. */
  UNDECIDED,
}

/**
 * checkRelocatedStrings (19 §3.3 step 7, T-BLD-005): reads the string constants of every class in
 * the agent jar and reports each configured property name in its original and relocated form.
 * With [RelocatedStringPolicy.RELOCATED] an original-form occurrence fails the build, with
 * [RelocatedStringPolicy.ORIGINAL] a relocated-form occurrence does; [RelocatedStringPolicy.UNDECIDED]
 * only writes the report (the decision belongs to 10 §10.3 and WO-02).
 */
abstract class CheckRelocatedStrings : DefaultTask() {

  @get:InputFile
  @get:PathSensitive(PathSensitivity.NONE)
  abstract val jar: RegularFileProperty

  /** Relocation prefix (AgentShading.PREFIX). */
  @get:Input abstract val prefix: Property<String>

  /** Property names in their original form, e.g. `io.netty.noUnsafe`. */
  @get:Input abstract val propertyNames: ListProperty<String>

  @get:Input abstract val policy: Property<RelocatedStringPolicy>

  @get:OutputFile abstract val reportFile: RegularFileProperty

  init {
    group = "verification"
    description = "Checks how property-name string constants were relocated (T-BLD-005)."
  }

  @TaskAction
  fun check() {
    val file = jar.get().asFile
    val relocationPrefix = prefix.get() + "."
    val originals = mutableMapOf<String, MutableSet<String>>()
    val relocated = mutableMapOf<String, MutableSet<String>>()
    for ((entry, bytes) in ClassFiles.classEntries(file)) {
      if (ClassFiles.majorVersion(bytes) == null) continue
      for (constant in ClassFiles.stringConstants(bytes)) {
        for (name in propertyNames.get()) {
          when (constant) {
            name -> originals.getOrPut(name) { sortedSetOf() } += entry
            relocationPrefix + name -> relocated.getOrPut(name) { sortedSetOf() } += entry
          }
        }
      }
    }
    val report = buildString {
      append("# checkRelocatedStrings (T-BLD-005) for ${file.name}, policy ${policy.get()}\n")
      for (name in propertyNames.get().sorted()) {
        append("$name\n")
        append("  original : ${originals[name].orEmpty().ifEmpty { setOf("-") }.joinToString()}\n")
        append("  relocated: ${relocated[name].orEmpty().ifEmpty { setOf("-") }.joinToString()}\n")
      }
    }
    reportFile.get().asFile.apply { parentFile.mkdirs() }.writeText(report, Charsets.UTF_8)

    val violations =
      when (policy.get()) {
        RelocatedStringPolicy.RELOCATED -> originals.map { (name, classes) -> "$name (original form) in $classes" }
        RelocatedStringPolicy.ORIGINAL -> relocated.map { (name, classes) -> "$relocationPrefix$name in $classes" }
        RelocatedStringPolicy.UNDECIDED -> emptyList()
      }
    if (violations.isNotEmpty()) {
      throw GradleException(
        "checkRelocatedStrings: property names do not follow policy ${policy.get()}:\n  " +
          violations.joinToString("\n  ")
      )
    }
    if (policy.get() == RelocatedStringPolicy.UNDECIDED) {
      logger.lifecycle(
        "checkRelocatedStrings: policy UNDECIDED (10 §10.3, WO-02); report only: " +
          reportFile.get().asFile
      )
    }
  }
}

/**
 * checkOwnRelocatedLiterals (ADR-0003 C-23, ADR-0005 transport): Shadow rewrites every string
 * constant that starts with a relocated package, the constants of the agent's own classes included.
 * A literal meant for application classes, such as the ignore-list prefix `net.bytebuddy.`, then
 * silently reads `io.argos.agent.shaded.net.bytebuddy.` in the jar. The check fails when an own
 * class that went through Shadow (below `inst/io/argos/agent/`, outside `shaded/`) holds a string
 * constant in the relocated namespace (dotted or slashed form) that [allowed] does not list. Root
 * bootstrap classes are not relocated and are not scanned.
 */
abstract class CheckOwnRelocatedLiterals : DefaultTask() {

  @get:InputFile
  @get:PathSensitive(PathSensitivity.NONE)
  abstract val jar: RegularFileProperty

  /** Relocation prefix (AgentShading.PREFIX). */
  @get:Input abstract val prefix: Property<String>

  /** Intended constants as `<jar entry>: <constant>`, e.g. relocated property names the agent sets. */
  @get:Input abstract val allowed: ListProperty<String>

  @get:OutputFile abstract val reportFile: RegularFileProperty

  init {
    group = "verification"
    description = "Checks that Shadow did not relocate string literals of the agent's own classes (C-23)."
    allowed.convention(emptyList())
  }

  @TaskAction
  fun check() {
    val file = jar.get().asFile
    val dotted = prefix.get() + "."
    val slashed = prefix.get().replace('.', '/') + "/"
    val found = sortedSetOf<String>()
    for ((entry, bytes) in ClassFiles.classEntries(file)) {
      if (!entry.startsWith("inst/io/argos/agent/") || ClassFiles.isShadedEntry(entry)) continue
      if (ClassFiles.majorVersion(bytes) == null) continue
      for (constant in ClassFiles.stringConstants(bytes)) {
        if (constant.startsWith(dotted) || constant.startsWith(slashed)) found += "$entry: $constant"
      }
    }
    val permitted = allowed.get().toSet()
    val violations = found.filterNot { it in permitted }
    reportFile.get().asFile.apply { parentFile.mkdirs() }
      .writeText(
        "# own-class string constants in the relocated namespace of ${file.name} (${found.size})\n" +
          found.joinToString("") { line -> (if (line in permitted) "allowed  " else "REJECTED ") + line + "\n" },
        Charsets.UTF_8,
      )
    if (violations.isNotEmpty()) {
      throw GradleException(
        "checkOwnRelocatedLiterals: Shadow relocated ${violations.size} string literal(s) of the " +
          "agent's own classes; build such strings at run time or list intended constants:\n  " +
          violations.joinToString("\n  ")
      )
    }
  }
}

/**
 * checkModuleIndex (ADR-0003 C-25): the instrumentation modules the core discovers at runtime are
 * the classes listed in the merged service file [SERVICE_FILE] of the agent jar. Every listed class
 * must exist as `inst/<binary name>.classdata` below `io/argos/agent/`, and appear only once.
 * Writes the module list to [reportFile].
 */
abstract class CheckModuleIndex : DefaultTask() {
  companion object {
    const val SERVICE_FILE =
      "inst/META-INF/services/io.argos.agent.instrumentation.api.InstrumentationModule"
  }

  @get:InputFile
  @get:PathSensitive(PathSensitivity.NONE)
  abstract val jar: RegularFileProperty

  @get:OutputFile abstract val reportFile: RegularFileProperty

  init {
    group = "verification"
    description = "Checks that every module listed in the module index exists in the jar (C-25)."
  }

  @TaskAction
  fun check() {
    val file = jar.get().asFile
    val problems = mutableListOf<String>()
    val modules = mutableListOf<String>()
    java.util.zip.ZipFile(file).use { zip ->
      val index = zip.getEntry(SERVICE_FILE)
      if (index != null) {
        val text = zip.getInputStream(index).use { it.readBytes().toString(Charsets.UTF_8) }
        for (raw in text.lines()) {
          val name = raw.substringBefore('#').trim()
          if (name.isEmpty()) continue
          if (name in modules) problems += "$name listed more than once"
          modules += name
          val entry = "inst/" + name.replace('.', '/') + ".classdata"
          when {
            !name.startsWith("io.argos.agent.") -> problems += "$name is not an Argos class"
            zip.getEntry(entry) == null -> problems += "$name has no $entry"
          }
        }
      }
    }
    reportFile.get().asFile.apply { parentFile.mkdirs() }
      .writeText(
        "# instrumentation modules of ${file.name} ($SERVICE_FILE)\n" +
          modules.joinToString("") { "$it\n" },
        Charsets.UTF_8,
      )
    if (problems.isNotEmpty()) {
      throw GradleException("checkModuleIndex: ${file.name}:\n  " + problems.joinToString("\n  "))
    }
    logger.info("checkModuleIndex: ${modules.size} module(s) in ${file.name}")
  }
}
