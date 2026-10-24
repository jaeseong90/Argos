// SPDX-License-Identifier: Apache-2.0
//
// sample-apps/load: traffic for the sample apps.
// - bench.js + run-bench.sh: the k6 constant-arrival-rate scenario of 18 §7.2 / 01 §7.3 item 3.
//   k6 is AGPL-3.0: it runs as a separate container (grafana/k6 pinned by digest), development and
//   benchmarks only, never shipped (docs/licenses/dev-only.md, ADR-0003 C-33).
// - The demo load generator behind the public image argos-demo-load (ADR-0003 C-33, 17 §3.2/§3.9):
//   own Java 17 code on java.net.http with a fixed arrival rate and no third-party dependency, so
//   the image holds allow-listed licenses only (WO-41 moves this project to checkLicenses). Same
//   bench mix as bench.js (MIX=bench) plus the demo mix of 17 §3.9 (MIX=default).
// - integrationTest: the generator over HTTP against an in-process server; then shop-boot3 (jar, JDK
//   17) + ext-stub with the seed environment: the seed check of 18 §4.3 (every seed endpoint
//   answers with the ARGOS_SEED sentinel), test/seed/seed-traffic.sh (every seed endpoint N
//   times) and short runs of the packaged load.jar with both mixes.
// - checkDemoLoadDependencies (in check): the generator keeps zero runtime dependencies.

import io.argos.buildlogic.sample.SampleSmokeSlot

plugins {
  id("argos.sample-app")
  id("argos.integration-test")
}

description = "Sample apps: demo load generator (fixed arrival rate), k6 scenario and seed check."

sampleApp {
  javaRelease = 17
  runJdk = 17
  executableJar("io.argos.sample.load.DemoLoadMain")
  dockerImage()
}

// The packaged apps the seed check starts: shop-boot3 (the reference app, 18 §2.1) and the smoke
// runner that test/seed/seed-traffic.sh wraps. Test inputs only; nothing here reaches load.jar.
val seedTarget = configurations.dependencyScope("seedTarget")
val seedTargetFiles =
  configurations.resolvable("seedTargetFiles") {
    extendsFrom(seedTarget.get())
    isTransitive = false
    attributes {
      attribute(Category.CATEGORY_ATTRIBUTE, objects.named<Category>("argos-sample-app"))
    }
  }
val seedRunner = configurations.dependencyScope("seedRunner")
val seedRunnerFiles =
  configurations.resolvable("seedRunnerFiles") {
    extendsFrom(seedRunner.get())
    isTransitive = false
    attributes {
      attribute(Category.CATEGORY_ATTRIBUTE, objects.named<Category>("argos-sample-app"))
    }
  }

dependencies {
  seedTarget(project(":sample-apps:shop-boot3"))
  seedRunner(project(":sample-apps:smoke"))
  // Tests only: the mixes are checked against the shop catalog (common: ShopCatalog, ShopSchema);
  // the seed check uses the seed catalog (Seeds) and ext-stub. Nothing here reaches load.jar.
  testImplementation(project(":sample-apps:common"))
  "integrationTestImplementation"(project(":sample-apps:common"))
  "integrationTestImplementation"(project(":sample-apps:ext-stub"))
}

// ADR-0003 C-33: the demo generator becomes the public argos-demo-load image, so it ships Argos
// code only. Any runtime dependency (whatever its license) must first be cleared for the image.
val checkDemoLoadDependencies =
  tasks.register("checkDemoLoadDependencies") {
    group = "verification"
    description = "Fails when the demo load generator gains a runtime dependency (ADR-0003 C-33)."
    val runtime: FileCollection = configurations.runtimeClasspath.get()
    inputs.files(runtime).withPropertyName("runtimeClasspath")
    doLast {
      val files = runtime.files
      if (files.isNotEmpty()) {
        throw GradleException(
          "sample-apps/load must not have runtime dependencies (it is the public argos-demo-load " +
            "image, ADR-0003 C-33): ${files.map { it.name }}"
        )
      }
    }
  }

tasks.named("check") { dependsOn(checkDemoLoadDependencies) }

// load.jar (sampleAppJar) becomes the public argos-demo-load image: like every shipped artifact it
// carries the project's LICENSE and NOTICE in META-INF (O-L01, O-L04; as agent-dist's agentJar).
// The plain jar gets them too, so no jar of this project goes without them.
listOf("jar", "sampleAppJar").forEach { taskName ->
  tasks.named<Jar>(taskName) {
    val license = layout.settingsDirectory.file("LICENSE").asFile
    val notice = layout.settingsDirectory.file("NOTICE").asFile
    into("META-INF") { from(license, notice) }
    doFirst {
      listOf(license, notice).forEach {
        if (!it.isFile) throw GradleException("Missing $it: required in META-INF of $taskName")
      }
    }
  }
}

/** System properties of the seed check: packaged apps, the JDK that runs them, scratch space. */
abstract class SeedCheckArguments : CommandLineArgumentProvider {
  @get:InputFiles
  @get:PathSensitive(PathSensitivity.NONE)
  abstract val appJar: ConfigurableFileCollection

  @get:InputFiles
  @get:PathSensitive(PathSensitivity.NONE)
  abstract val smokeJar: ConfigurableFileCollection

  /** The packaged demo load generator (load.jar), run as a process against shop-boot3. */
  @get:InputFiles
  @get:PathSensitive(PathSensitivity.NONE)
  abstract val loadJar: ConfigurableFileCollection

  @get:Nested abstract val appLauncher: Property<JavaLauncher>

  @get:Internal abstract val repositoryRoot: DirectoryProperty

  @get:Internal abstract val scratch: DirectoryProperty

  override fun asArguments(): List<String> =
    listOf(
      "-Dargos.seed.appJar=${appJar.singleFile.absolutePath}",
      "-Dargos.seed.smokeJar=${smokeJar.singleFile.absolutePath}",
      "-Dargos.load.jar=${loadJar.singleFile.absolutePath}",
      "-Dargos.seed.appJava=${appLauncher.get().executablePath.asFile.absolutePath}",
      "-Dargos.seed.repositoryRoot=${repositoryRoot.get().asFile.absolutePath}",
      "-Dargos.seed.scratch=${scratch.get().asFile.absolutePath}",
      "-Djava.io.tmpdir=${scratch.get().asFile.absolutePath}",
    )
}

val seedCheckArguments =
  objects.newInstance<SeedCheckArguments>().apply {
    appJar.from(seedTargetFiles)
    smokeJar.from(seedRunnerFiles)
    loadJar.from(tasks.named<Jar>("sampleAppJar").flatMap { it.archiveFile })
    // shop-boot3 runs on its build JDK (18 §4.1).
    appLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(17) }
    repositoryRoot = layout.settingsDirectory
    scratch = layout.buildDirectory.dir("tmp/integrationTest-scratch")
  }

tasks.named<Test>("integrationTest") {
  jvmArgumentProviders.add(seedCheckArguments)
  inputs
    .file(layout.settingsDirectory.file("test/seed/seed-traffic.sh"))
    .withPathSensitivity(PathSensitivity.RELATIVE)
    .withPropertyName("seedTrafficScript")
  val scratchDir = seedCheckArguments.scratch
  doFirst {
    // App logs, reports and temp files of the previous run go; nothing outside build/.
    scratchDir.get().asFile.deleteRecursively()
    scratchDir.get().asFile.mkdirs()
  }
  // One app start at a time across the build (the slot of the sampleSmoke tasks).
  usesService(
    gradle.sharedServices.registerIfAbsent("argosSampleSmokeSlot", SampleSmokeSlot::class.java) {
      maxParallelUsages = 1
    }
  )
}

// The unit tests compare the Java "bench" mix with bench.js (project directory).
tasks.named<Test>("test") {
  inputs.file("bench.js").withPathSensitivity(PathSensitivity.RELATIVE)
  systemProperty("argos.load.benchScript", file("bench.js").absolutePath)
}
