// SPDX-License-Identifier: Apache-2.0
//
// Agent test harness (18 §5): AppLauncher, FakeCollector, assertion DSL, transform baselines,
// matrix runner. It is never shipped, so it runs on the JDK 21 test-harness toolchain
// (DSN-19-02) and launches sample apps on other JDKs as separate processes.
//
// ArchUnit rules over all agent modules (19 §5.3, 10 §2 B-1..B-4, 10 §5.3) live in src/archTest:
// - the rules run against the classes of every agent module (project dependencies below);
// - src/archFixtures holds compliant and violating agent-like classes, compiled exactly like agent
//   code (--release 8), so that every rule is shown to fail on a violation (T-AG-001..004).
//   Fixtures are test-only and never packaged.

import io.argos.buildlogic.AgentShading
import io.argos.buildlogic.ArgosProject
import io.argos.buildlogic.argosArchivesName
import java.util.zip.ZipFile

plugins {
  id("argos.java-common")
  id("argos.integration-test")
}

description = "Argos agent test harness (launcher, fake collector, assertions, matrix)."

val archFixtures = sourceSets.create("archFixtures")

tasks.named<JavaCompile>(archFixtures.compileJavaTaskName) {
  options.release = ArgosProject.AGENT_JAVA_RELEASE
  // JDK 21 javac warns that release 8 is obsolete (see argos.java-agent).
  options.compilerArgs.add("-Xlint:-options")
}

// ByteBuddy's class files carry SpotBugs annotations whose (LGPL) jar is not on the classpath;
// javac's classfile lint reports each of them when it reads advice annotations, so that lint
// category is off for the code that compiles against the fixtures.
listOf(archFixtures.compileJavaTaskName, "compileArchTestJava").forEach {
  tasks.named<JavaCompile>(it) { options.compilerArgs.add("-Xlint:-classfile") }
}

// Every agent module whose classes the rules scan (single list: settings.gradle.kts).
val agentModulePaths =
  listOf(ArgosProject.AGENT_BOOTSTRAP_PATH, ":agent:agent-core") +
    rootProject.project(":agent:agent-instrumentation").childProjects.values.map { it.path }.sorted()

dependencies {
  // Advice annotations for the fixtures (provided by the agent jar at runtime; test-only here).
  "archFixturesCompileOnly"(libs.bytebuddy)

  "archTestImplementation"(archFixtures.output)
  "archTestImplementation"(libs.asm)
  "archTestImplementation"(libs.asm.tree)
  // Lets ArchUnit resolve Advice annotation types (default attribute values).
  "archTestRuntimeOnly"(libs.bytebuddy)
  agentModulePaths.forEach { "archTestImplementation"(project(it)) }
}

tasks.named<Test>("archTest") {
  val modules = agentModulePaths.map { rootProject.project(it).argosArchivesName() }
  systemProperty("argos.archunit.agentModules", modules.joinToString(","))
  systemProperty("argos.archunit.relocatedPackages", AgentShading.RELOCATED_PACKAGES.joinToString(","))
  // Gradle names of the instrumentation modules: the module column of the allow-list.
  val instrumentation = rootProject.project(":agent:agent-instrumentation")
  systemProperty("argos.archunit.instrumentationModules", instrumentation.childProjects.keys.sorted().joinToString(","))
  // Read by the test that checks each module name against the package of its sources.
  systemProperty("argos.archunit.instrumentationDir", instrumentation.projectDir.absolutePath)
  // 10 §5.3 A-5: readOnly=false / AssignReturned allow-list.
  val allowlist =
    layout.settingsDirectory.file("agent/agent-instrumentation/src/main/resources/argos-advice-allowlist.txt")
  inputs.file(allowlist).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("adviceAllowlist")
  systemProperty("argos.archunit.adviceAllowlist", allowlist.asFile.absolutePath)
}

// --- Agent attach tests (WO-02: T-BLD-006, T-AG-060, T-AG-040/043 PoC, 10 §4.2 isolation) ---------
// integrationTest launches example.trivial.TrivialApp (src/trivialApp, Java 8 bytecode) with
// -javaagent on several JDKs, once with the release agent jar and once with a probe agent jar: the
// release jar plus the test-only modules of src/probeModule (never shipped). The probe module is
// compiled against agent-dist's stage 1 jar (relocated ByteBuddy names), so the probe jar needs no
// relocation of its own. JDKs: -Pagent.attach.jdks=8,11,17,21,25 (default 8,21, Gradle toolchains).

val trivialApp = sourceSets.create("trivialApp")
// A test-only Java agent run before Argos: loads ThreadPoolExecutor and HttpURLConnection before
// the Argos premain, so the attach test sees them retransformed (P-6, 10 §4.3 P6).
val preloadAgent = sourceSets.create("preloadAgent")
val probeModule = sourceSets.create("probeModule")

listOf(trivialApp.compileJavaTaskName, probeModule.compileJavaTaskName, preloadAgent.compileJavaTaskName).forEach {
  tasks.named<JavaCompile>(it) {
    options.release = ArgosProject.AGENT_JAVA_RELEASE
    options.compilerArgs.addAll(listOf("-Xlint:-options", "-Xlint:-classfile"))
  }
}

val agentJarScope = configurations.dependencyScope("agentJarScope")
val releaseAgentJar =
  configurations.resolvable("releaseAgentJar") {
    extendsFrom(agentJarScope.get())
    attributes { attribute(Category.CATEGORY_ATTRIBUTE, objects.named("argos-agent-jar")) }
  }
val agentInstScope = configurations.dependencyScope("agentInstScope")
val agentInstJar =
  configurations.resolvable("agentInstJar") {
    extendsFrom(agentInstScope.get())
    attributes { attribute(Category.CATEGORY_ATTRIBUTE, objects.named("argos-agent-inst-jar")) }
  }

dependencies {
  agentJarScope(project(":agent:agent-dist"))
  agentInstScope(project(":agent:agent-dist"))
  "probeModuleCompileOnly"(project(ArgosProject.AGENT_BOOTSTRAP_PATH))
  "probeModuleCompileOnly"(files(agentInstJar))
}

/**
 * Writes the merged module index of the probe agent jar: the release jar's entries (if any) plus the
 * probe module's own service file (ADR-0003 C-25).
 */
abstract class MergeModuleIndex : DefaultTask() {
  @get:InputFiles @get:PathSensitive(PathSensitivity.NONE) abstract val releaseJar: ConfigurableFileCollection

  @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE) abstract val probeIndex: ConfigurableFileCollection

  @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

  @TaskAction
  fun merge() {
    val name = "io.argos.agent.instrumentation.api.InstrumentationModule"
    val lines = mutableListOf("# merged by agent-testing mergeProbeModuleIndex")
    ZipFile(releaseJar.singleFile).use { zip ->
      zip.getEntry("inst/META-INF/services/$name")?.let { entry ->
        lines += zip.getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8) }.lines()
      }
    }
    probeIndex.files.filter { it.isFile }.forEach { lines += it.readText(Charsets.UTF_8).lines() }
    val out = outputDirectory.get().asFile.apply { deleteRecursively(); mkdirs() }
    File(out, name).writeText(lines.filter { it.isNotBlank() }.joinToString("\n", postfix = "\n"))
  }
}

val mergeProbeModuleIndex =
  tasks.register<MergeModuleIndex>("mergeProbeModuleIndex") {
    releaseJar.from(releaseAgentJar)
    probeIndex.from(
      layout.projectDirectory.file(
        "src/probeModule/resources/META-INF/services/io.argos.agent.instrumentation.api.InstrumentationModule"
      )
    )
    outputDirectory = layout.buildDirectory.dir("probe-agent/services")
  }

val probeAgentJar =
  tasks.register<Zip>("probeAgentJar") {
    description = "Release agent jar plus the test-only probe modules (attach tests, never shipped)."
    archiveFileName = "argos-agent-probe.jar"
    destinationDirectory = layout.buildDirectory.dir("probe-agent")
    from(zipTree(releaseAgentJar.map { it.singleFile })) {
      exclude("inst/META-INF/services/io.argos.agent.instrumentation.api.InstrumentationModule")
    }
    from(probeModule.output.classesDirs) {
      include("**/*.class")
      rename("""^(.*)\.class$""", "\$1.classdata")
      into("inst")
    }
    from(mergeProbeModuleIndex) { into("inst/META-INF/services") }
  }

val preloadAgentJar =
  tasks.register<Jar>("preloadAgentJar") {
    description = "Test-only Java agent that loads two JDK classes before the Argos premain (attach tests)."
    archiveFileName = "preload-agent.jar"
    destinationDirectory = layout.buildDirectory.dir("preload-agent")
    from(preloadAgent.output)
    manifest { attributes("Premain-Class" to "example.preload.PreloadAgent") }
  }

val attachJdks =
  providers.gradleProperty("agent.attach.jdks").getOrElse("8,21").split(',').map { it.trim().toInt() }

// --- Harness library (src/main, WO-02 minimal 18 §5.1) and sample apps with the agent -------------
// src/main: launcher (AppLauncher, RunningApp, SampleApp), JSONL reader and records, transaction
// assertions, transformed-class list, console output checks. integrationTest's SampleAppAgentTest
// starts WO-01 sample apps with the release agent jar: -Pagent.e2e=<app>@<jdk>,... (default
// shop-boot3@21; "none" skips it). Apps come from their sampleAppElements variant (WO-01 report
// D-1); the launch descriptor (build/sample-app/<app>.properties) is produced next to the jar.

dependencies {
  implementation(libs.assertj.core)
  // In-process target of the sample apps' outgoing calls (WO-02 P-6: HttpURLConnection, F-A32).
  "integrationTestImplementation"(project(":sample-apps:ext-stub"))
}

/** Jar apps the launcher can start (WAR apps need the staged Tomcat: WO-05). */
val e2eApps = listOf("shop-boot2", "shop-boot3", "shop-boot4", "shop-heavy", "shop-batch")

val e2eCombos =
  providers.gradleProperty("agent.e2e").getOrElse("shop-boot3@21").split(',')
    .map { it.trim() }
    .filter { it.isNotEmpty() && it != "none" }
    .onEach {
      val app = it.substringBefore('@')
      if (app !in e2eApps || it.substringAfter('@', "").toIntOrNull() == null) {
        throw GradleException("-Pagent.e2e: expected <app>@<jdk> with app in $e2eApps, got '$it'")
      }
    }
val e2eRequestedApps = e2eCombos.map { it.substringBefore('@') }.distinct()
val e2eJdks = e2eCombos.map { it.substringAfter('@').toInt() }.distinct()

/** One resolvable configuration per app, so that only the requested apps are built. */
val sampleAppJars =
  e2eApps.associateWith { app ->
    val name = "sampleApp" + app.split('-').joinToString("") { it.replaceFirstChar(Char::uppercaseChar) }
    val scope = configurations.dependencyScope("${name}Scope")
    dependencies { add(scope.name, project(":sample-apps:$app")) }
    configurations.resolvable(name) {
      extendsFrom(scope.get())
      attributes { attribute(Category.CATEGORY_ATTRIBUTE, objects.named("argos-sample-app")) }
    }
  }

tasks.named<Test>("integrationTest") {
  val launchers =
    (attachJdks + e2eJdks).distinct().associateWith { version ->
      javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(version) }
    }
  val appFiles: Map<String, FileCollection> = e2eRequestedApps.associateWith { sampleAppJars.getValue(it).get() }
  appFiles.forEach { (app, files) ->
    inputs.files(files).withPropertyName("sampleApp-$app").withNormalizer(ClasspathNormalizer::class.java)
    // The launch descriptor next to the jar finalizes the jar task; make it an explicit input step.
    dependsOn(":sample-apps:$app:sampleLaunchDescriptor")
  }
  // Locals only inside the providers below (the configuration cache cannot keep script objects).
  val combos = e2eCombos.joinToString(",")
  // Transformed-class lists and JSONL of the e2e runs (T-AG-005 baseline input, PoC run table).
  val e2eReportDir = layout.buildDirectory.dir("reports/agent-e2e")
  outputs.dir(e2eReportDir).withPropertyName("e2eReports")
  val attachJdkList = attachJdks.joinToString(",")
  inputs.property("e2eCombos", combos)
  jvmArgumentProviders.add(
    CommandLineArgumentProvider {
      listOf("-Dargos.test.e2e=" + combos, "-Dargos.test.reportDir=" + e2eReportDir.get().asFile.absolutePath) +
        appFiles.map { (app, files) -> "-Dargos.test.sampleApp.$app=" + files.singleFile.absolutePath }
    }
  )
  val releaseJarFiles: FileCollection = releaseAgentJar.get()
  val probeJar = probeAgentJar.flatMap { it.archiveFile }
  val appClasses = trivialApp.output.classesDirs
  val preloadJar = preloadAgentJar.flatMap { it.archiveFile }
  inputs.file(preloadJar).withPropertyName("preloadAgentJar").withPathSensitivity(PathSensitivity.NONE)
  inputs.files(releaseJarFiles).withPropertyName("releaseAgentJar").withNormalizer(ClasspathNormalizer::class.java)
  inputs.file(probeJar).withPropertyName("probeAgentJar").withPathSensitivity(PathSensitivity.NONE)
  inputs.files(appClasses).withPropertyName("trivialApp").withNormalizer(ClasspathNormalizer::class.java)
  jvmArgumentProviders.add(
    CommandLineArgumentProvider {
      listOf(
        "-Dargos.test.releaseAgentJar=" + releaseJarFiles.singleFile.absolutePath,
        "-Dargos.test.probeAgentJar=" + probeJar.get().asFile.absolutePath,
        "-Dargos.test.trivialAppClasses=" + appClasses.files.joinToString(File.pathSeparator) { it.absolutePath },
        "-Dargos.test.preloadAgentJar=" + preloadJar.get().asFile.absolutePath,
        "-Dargos.test.jdks=" + attachJdkList,
      ) +
        launchers.map { (version, launcher) ->
          "-Dargos.test.java$version=" + launcher.get().executablePath.asFile.absolutePath
        }
    }
  )
}
