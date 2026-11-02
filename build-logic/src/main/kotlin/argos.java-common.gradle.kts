// SPDX-License-Identifier: Apache-2.0
//
// argos.java-common: applied to every Java module (19 §3.2).
// Spotless (google-java-format + SPDX header), Error Prone with -Werror, -Xlint:all -Werror,
// reproducible archives (19 §3.7), dependency locking, JUnit 5 test suites with test logging,
// the archTest suite (wired into check), the unit-test layer rules of 18 §2.2
// (checkUnitTestSources in check, slow-test warning after `test`) and the license allow-list
// checks (argos.license-check: checkLicenses in check, checkDevOnlyLicenses as a separate CI check).

import com.diffplug.gradle.spotless.SpotlessTask
import io.argos.buildlogic.ArgosProject
import io.argos.buildlogic.argosArchivesName
import io.argos.buildlogic.argosLibs
import io.argos.buildlogic.library
import io.argos.buildlogic.testing.CheckUnitTestSources
import io.argos.buildlogic.testing.SlowUnitTestWarning
import io.argos.buildlogic.versionOf
import net.ltgt.gradle.errorprone.errorprone
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent

plugins {
  `java-library`
  id("com.diffplug.spotless")
  id("net.ltgt.errorprone")
  id("argos.license-check")
}

val catalog = argosLibs

base { archivesName = argosArchivesName() }

java {
  // Every module is compiled by the JDK 21 compiler (DSN-19-02). Agent modules add --release 8.
  toolchain { languageVersion = JavaLanguageVersion.of(ArgosProject.BUILD_JDK) }
}

// Lock every resolvable configuration; gradle.lockfile is committed (19 §3.7, SEC-73). STRICT: a
// configuration without lock state fails to resolve (write it with resolveAndLockAll below).
dependencyLocking {
  lockAllConfigurations()
  lockMode = LockMode.STRICT
}

dependencies { "errorprone"(catalog.library("errorprone-core")) }

tasks.withType<JavaCompile>().configureEach {
  options.encoding = "UTF-8"
  options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
  options.errorprone {
    disableWarningsInGeneratedCode = true
    excludedPaths = ".*[/\\\\]build[/\\\\]generated[/\\\\].*"
    // 19 §5.2: these are errors even if -Werror is ever relaxed.
    error("MissingOverride", "UnusedVariable", "UnusedMethod")
  }
}

spotless {
  java {
    // Hand-written sources only; generated code (protobuf, ...) lives under build/.
    target("src/*/java/**/*.java")
    googleJavaFormat(catalog.versionOf("google-java-format"))
    licenseHeaderFile(layout.settingsDirectory.file("build-logic/spdx-header.txt").asFile)
    trimTrailingWhitespace()
    endWithNewline()
  }
}

// Spotless bug diffplug/spotless#2850: the doLast hook that Spotless adds to every `clean` task
// closes the shared formatter class loaders, so `./gradlew clean build` with parallel projects
// fails with NoClassDefFoundError lints. Run every Spotless task after every clean task.
val cleanTaskPaths =
  rootProject.allprojects
    .filter { it.buildFile.isFile }
    .map { if (it.path == ":") ":clean" else "${it.path}:clean" }

tasks.withType<SpotlessTask>().configureEach { mustRunAfter(cleanTaskPaths) }

// Reproducible archives (DSN-19-05, 19 §3.7).
tasks.withType<AbstractArchiveTask>().configureEach {
  isPreserveFileTimestamps = false
  isReproducibleFileOrder = true
  dirPermissions { unix("755") }
  filePermissions { unix("644") }
}

testing {
  suites {
    withType<JvmTestSuite>().configureEach {
      useJUnitJupiter(catalog.versionOf("junit"))
      dependencies { implementation(catalog.library("assertj-core")) }
    }
    // ArchUnit rules (19 §5.3, 18 §2.2): part of check in every module.
    register<JvmTestSuite>("archTest") {
      dependencies {
        implementation(project())
        implementation(catalog.library("archunit-junit5"))
      }
      targets.configureEach { testTask.configure { shouldRunAfter(tasks.named("test")) } }
    }
  }
}

tasks.withType<Test>().configureEach {
  // Flaky tests are quarantined, never retried (DSN-18-13).
  testLogging {
    events(TestLogEvent.FAILED, TestLogEvent.SKIPPED, TestLogEvent.STANDARD_ERROR)
    exceptionFormat = TestExceptionFormat.FULL
    showExceptions = true
    showCauses = true
    showStackTraces = true
  }
}

tasks.named("check") { dependsOn(testing.suites.named("archTest")) }

// Unit-test layer rules (18 §2.2, test/README.md). src/test holds unit tests only: tests tagged
// slow/integration or using Testcontainers fail check (move them to src/integrationTest), and
// every `test` run lists its tests slower than 1 s as a warning (timing is machine-dependent, so it
// never fails the build). Network use cannot be detected cheaply and remains a review rule.
val checkUnitTestSources =
  tasks.register<CheckUnitTestSources>("checkUnitTestSources") {
    sources.from(fileTree("src/test") { include("**/*.java", "**/*.kt") })
    projectDirectory = layout.projectDirectory
    marker = layout.buildDirectory.file("test-layers/checkUnitTestSources.ok")
  }

tasks.named("check") { dependsOn(checkUnitTestSources) }

tasks.named<Test>("test") { doLast(SlowUnitTestWarning(reports.junitXml.outputLocation)) }

// Writes lock state for every resolvable configuration:
//   ./gradlew resolveAndLockAll --write-locks --no-configuration-cache
tasks.register("resolveAndLockAll") {
  group = "help"
  description = "Resolves every resolvable configuration so that --write-locks records all of them."
  notCompatibleWithConfigurationCache("Resolves configurations at execution time")
  val writeLocks = gradle.startParameter.isWriteDependencyLocks
  val resolvable = configurations.matching { it.isCanBeResolved }
  doFirst {
    require(writeLocks) { "resolveAndLockAll must be run with --write-locks" }
  }
  doLast { resolvable.forEach { it.resolve() } }
}
