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
 * checkMajorVersion (19 §3.3 step 8, T-BLD-001): every `.class` and `.classdata` entry of the agent
 * jar has class file major version 52 (Java 8, F-A31).
 */
abstract class CheckMajorVersion : DefaultTask() {

  @get:InputFile
  @get:PathSensitive(PathSensitivity.NONE)
  abstract val jar: RegularFileProperty

  @get:Input abstract val expectedMajor: Property<Int>

  /** Empty marker file written on success, so the task is up to date for an unchanged jar. */
  @get:OutputFile abstract val resultFile: RegularFileProperty

  init {
    group = "verification"
    description = "Checks that every .class/.classdata entry has major version 52 (T-BLD-001)."
    expectedMajor.convention(ClassFiles.JAVA_8_MAJOR)
  }

  @TaskAction
  fun check() {
    val file = jar.get().asFile
    val violations = ClassFiles.majorVersionViolations(file, expectedMajor.get())
    if (violations.isNotEmpty()) {
      throw GradleException(
        "checkMajorVersion: ${violations.size} class file(s) in ${file.name} do not have major " +
          "version ${expectedMajor.get()} (Java 8, F-A31):\n  " + violations.joinToString("\n  ")
      )
    }
    val classes = ClassFiles.classEntries(file).count()
    resultFile.get().asFile.writeText("$classes classes, major ${expectedMajor.get()}\n")
    logger.info("checkMajorVersion: $classes classes in ${file.name} have major ${expectedMajor.get()}")
  }
}

/**
 * How property-name strings of shaded libraries (`io.netty.noUnsafe`, `net.bytebuddy.experimental`,
 * ...) must look after relocation (19 §3.3 step 7, 19 §13 #6, T-BLD-005).
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
