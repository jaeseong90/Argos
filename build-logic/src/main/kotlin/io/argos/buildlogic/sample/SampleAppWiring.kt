// SPDX-License-Identifier: Apache-2.0
package io.argos.buildlogic.sample

import javax.inject.Inject
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.artifacts.MinimalExternalModuleDependency
import org.gradle.api.attributes.Bundling
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.LibraryElements
import org.gradle.api.attributes.Usage
import org.gradle.api.attributes.java.TargetJvmEnvironment
import org.gradle.api.file.ArchiveOperations
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.file.RegularFile
import org.gradle.api.file.RelativePath
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Delete
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.bundling.War
import org.gradle.api.tasks.bundling.ZipEntryCompression
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService

/**
 * Wiring of the argos.sample-app convention plugin: compilation per app, packaging (Spring Boot jar,
 * executable jar, WAR + standalone Tomcat), container image, launch descriptor and smoke tasks.
 */
object SampleAppWiring {

  /** Project of the smoke runner (io.argos.sample.smoke.SmokeMain). */
  const val SMOKE_RUNNER_PROJECT = ":sample-apps:smoke"
  const val SMOKE_MAIN_CLASS = "io.argos.sample.smoke.SmokeMain"

  /** The smoke runner itself runs on the build JDK (DSN-19-02); apps run on their own JDK. */
  const val SMOKE_RUNNER_JDK = 21

  /** build/<dir>: packaged app (<app>.jar or <app>.war) and launch descriptor. */
  const val ARTIFACT_DIR = "sample-app"

  /**
   * Not staged from a Tomcat distribution: the docs and examples web apps, and the Eclipse JDT
   * compiler (EPL), which only compiles JSPs (the sample apps have none). The rest of the
   * distribution is listed in docs/licenses/dev-only.md (`org.apache.tomcat:tomcat`).
   */
  private val TOMCAT_EXCLUDES = listOf("*/webapps/docs/**", "*/webapps/examples/**", "*/lib/ecj-*.jar")

  private const val SMOKE_LIFECYCLE = "sampleSmoke"
  private const val SMOKE_RUNNER_CONFIGURATION = "sampleSmokeRunner"
  private const val APP_JAR_TASK = "sampleAppJar"

  /** The UI unit tests (Vitest). Always registered; -Pargos.ui.skip=true only skips it. */
  private const val UI_UNIT_TEST_TASK = ":ui:test"

  /** Applied once by the plugin. */
  fun configure(project: Project, ext: SampleAppExtension) {
    ext.catalog.convention("none")
    ext.packaging.convention(SamplePackaging.LIBRARY)
    ext.webContexts.convention(listOf(ext.appName))

    // Vitest hits its per-test timeouts when the sample apps build next to it on a 4-CPU runner
    // (pr.yml build-java). Same remedy as ui/build.gradle.kts, where uiTest mustRunAfter lint and
    // typecheck for the same reason: the sample-app tasks start after :ui:test. All of them, not
    // only compilation, tests and smoke runs: their lighter tasks (Spotless, license reports) would
    // otherwise take the workers before :ui:test and push the agent's tests into its time window.
    // Deletions are left out so that `clean build` keeps cleaning first. mustRunAfter only orders
    // tasks that are in the graph anyway: no dependency is added.
    project.tasks.configureEach { if (this !is Delete) mustRunAfter(UI_UNIT_TEST_TASK) }

    project.tasks.withType(JavaCompile::class.java).configureEach {
      options.release.set(ext.javaRelease)
      options.compilerArgumentProviders.add(ObsoleteReleaseLintArgument(ext.javaRelease))
    }

    val toolchains = project.extensions.getByType(JavaToolchainService::class.java)
    val runLauncher =
      toolchains.launcherFor { languageVersion.set(ext.runJdk.map { JavaLanguageVersion.of(it) }) }
    project.tasks.withType(Test::class.java).configureEach { javaLauncher.set(runLauncher) }

    ext.smokeVariants.configureEach {
      runJdk.convention(ext.runJdk)
      seedEnvironment.convention(false)
    }
    ext.smokeVariants.all { registerSmoke(project, ext, this) }

    project.afterEvaluate {
      val variants = ext.integrationTestVariants.get()
      if (variants.isNotEmpty()) {
        variants
          .filter { name -> ext.smokeVariants.findByName(name) == null }
          .takeIf { it.isNotEmpty() }
          ?.let { throw GradleException("${project.path}: unknown smoke variants $it in integrationTest") }
        val smokeTasks = variants.map { smokeTaskName(it) }
        if (project.tasks.names.contains("integrationTest")) {
          project.tasks.named("integrationTest") { dependsOn(smokeTasks) }
        } else {
          project.tasks.register("integrationTest") {
            group = "verification"
            description = "Integration layer of this sample app: smoke variants $variants (18 §2.1)."
            dependsOn(smokeTasks)
          }
        }
      }
    }
  }

  fun bootJar(
    project: Project,
    ext: SampleAppExtension,
    mainClass: String,
    loader: Provider<MinimalExternalModuleDependency>,
    launcher: BootLauncher,
  ) {
    choosePackaging(project, ext, SamplePackaging.BOOT_JAR)
    ext.mainClass.set(mainClass)
    registerBuildInfo(project, ext)
    val archives = archiveOperations(project)
    val loaderConfiguration =
      project.configurations.create("sampleBootLoader") {
        description = "spring-boot-loader of the app's Spring Boot version (unpacked at the jar root)."
        isCanBeConsumed = false
        isCanBeResolved = true
        isTransitive = false
      }
    project.dependencies.addProvider(loaderConfiguration.name, loader)
    val runtimeClasspath = project.configurations.getByName("runtimeClasspath")
    val index =
      project.tasks.register("sampleClasspathIndex", SampleClasspathIndex::class.java) {
        libraries.from(runtimeClasspath)
        indexFile.set(project.layout.buildDirectory.file("tmp/sampleClasspathIndex/classpath.idx"))
      }
    val main = project.extensions.getByType(SourceSetContainer::class.java).getByName("main")
    val version = project.version.toString()
    val jar =
      project.tasks.register(APP_JAR_TASK, Jar::class.java) {
        group = "build"
        description = "Spring Boot executable jar build/$ARTIFACT_DIR/${ext.appName}.jar (18 §4.4)."
        archiveFileName.set("${ext.appName}.jar")
        destinationDirectory.set(project.layout.buildDirectory.dir(ARTIFACT_DIR))
        // The Spring Boot loader reads nested jars in place: they must be stored, not deflated.
        entryCompression = ZipEntryCompression.STORED
        duplicatesStrategy = DuplicatesStrategy.FAIL
        manifest.attributes(
          mapOf(
            "Main-Class" to launcher.className,
            "Start-Class" to mainClass,
            "Spring-Boot-Classes" to "BOOT-INF/classes/",
            "Spring-Boot-Lib" to "BOOT-INF/lib/",
            "Spring-Boot-Classpath-Index" to "BOOT-INF/classpath.idx",
            "Implementation-Title" to ext.appName,
            "Implementation-Version" to version,
            "Argos-Sample-App" to ext.appName,
          )
        )
        from(loaderConfiguration.elements.map { files -> files.map { archives.zipTree(it.asFile) } }) {
          exclude("META-INF/**")
        }
        into("BOOT-INF/classes") { from(main.output) }
        into("BOOT-INF/lib") { from(runtimeClasspath) }
        into("BOOT-INF") { from(index) }
      }
    registerArtifact(project, ext, jar.flatMap { it.archiveFile }, jar)
  }

  fun executableJar(project: Project, ext: SampleAppExtension, mainClass: String) {
    choosePackaging(project, ext, SamplePackaging.EXECUTABLE_JAR)
    ext.mainClass.set(mainClass)
    registerBuildInfo(project, ext)
    val archives = archiveOperations(project)
    val runtimeClasspath = project.configurations.getByName("runtimeClasspath")
    val main = project.extensions.getByType(SourceSetContainer::class.java).getByName("main")
    val version = project.version.toString()
    val jar =
      project.tasks.register(APP_JAR_TASK, Jar::class.java) {
        group = "build"
        description = "Executable jar build/$ARTIFACT_DIR/${ext.appName}.jar (java -jar)."
        archiveFileName.set("${ext.appName}.jar")
        destinationDirectory.set(project.layout.buildDirectory.dir(ARTIFACT_DIR))
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        manifest.attributes(
          mapOf(
            "Main-Class" to mainClass,
            "Implementation-Title" to ext.appName,
            "Implementation-Version" to version,
            "Argos-Sample-App" to ext.appName,
          )
        )
        from(main.output)
        from(runtimeClasspath.elements.map { files -> files.map { archives.zipTree(it.asFile) } }) {
          exclude("META-INF/MANIFEST.MF", "META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
          exclude("module-info.class", "META-INF/versions/*/module-info.class")
        }
      }
    registerArtifact(project, ext, jar.flatMap { it.archiveFile }, jar)
  }

  fun war(project: Project, ext: SampleAppExtension, contexts: List<String>) {
    project.pluginManager.apply("war")
    choosePackaging(project, ext, SamplePackaging.WAR)
    if (contexts.isNotEmpty()) ext.webContexts.set(contexts)
    registerBuildInfo(project, ext)
    val war =
      project.tasks.named("war", War::class.java) {
        archiveFileName.set("${ext.appName}.war")
        destinationDirectory.set(project.layout.buildDirectory.dir(ARTIFACT_DIR))
      }
    registerArtifact(project, ext, war.flatMap { it.archiveFile }, war)
  }

  fun tomcat(
    project: Project,
    ext: SampleAppExtension,
    key: String,
    distribution: Provider<MinimalExternalModuleDependency>,
  ) {
    require(key.matches(Regex("[a-z][a-z0-9]*"))) { "Tomcat key must be like 'tomcat10': $key" }
    val configuration =
      project.configurations.create("sampleTomcat${taskSuffix(key)}") {
        description = "Standalone Tomcat binary distribution '$key' (zip)."
        isCanBeConsumed = false
        isCanBeResolved = true
        isTransitive = false
      }
    project.dependencies.addProvider(
      configuration.name,
      distribution.map {
        "${it.module.group}:${it.module.name}:${it.versionConstraint.requiredVersion}@zip"
      },
    )
    val archives = archiveOperations(project)
    project.tasks.register(stageTomcatTaskName(key), Sync::class.java) {
      group = "build"
      description = "Unpacks Tomcat '$key' to build/tomcat/$key (CATALINA_HOME of smoke and image)."
      from(configuration.elements.map { files -> files.map { archives.zipTree(it.asFile) } }) {
        exclude(TOMCAT_EXCLUDES)
      }
      // The input of the zip source is the archive; a changed exclude list alone would leave an
      // existing build/tomcat/<key> up to date with the files it no longer wants.
      inputs.property("sampleTomcatExcludes", TOMCAT_EXCLUDES)
      // apache-tomcat-<version>/bin/... -> bin/...
      eachFile {
        relativePath = RelativePath(true, *relativePath.segments.drop(1).toTypedArray())
      }
      includeEmptyDirs = false
      into(project.layout.buildDirectory.dir("tomcat/$key"))
    }
    ext.tomcatDistributions.put(key, distribution.map { it.versionConstraint.requiredVersion })
  }

  fun dockerImage(project: Project, ext: SampleAppExtension) {
    val jdkImage = project.providers.gradleProperty("sample.jdkImage")
    project.tasks.register("sampleImage", Exec::class.java) {
      group = "build"
      description =
        "docker build of ${project.projectDir.name}/Dockerfile -> argos-sample/${ext.appName}:dev " +
          "(-Psample.jdkImage=<image@digest> overrides JDK_IMAGE)."
      dependsOn(ext.packaging.map { artifactTaskName(it) })
      dependsOn(ext.tomcatDistributions.map { keys -> keys.keys.map { stageTomcatTaskName(it) } })
      workingDir(project.projectDir)
      executable("docker")
      val image = "argos-sample/${ext.appName}:dev"
      val override = jdkImage.orNull
      args(
        listOf("build", "--tag", image) +
          (if (override != null) listOf("--build-arg", "JDK_IMAGE=$override") else emptyList()) +
          listOf(".")
      )
    }
  }

  /** Creates the smoke runner configuration and the `sampleSmoke` lifecycle task once. */
  fun ensureSmokeRunner(project: Project, ext: SampleAppExtension) {
    if (project.configurations.findByName(SMOKE_RUNNER_CONFIGURATION) != null) return
    if (project.path == SMOKE_RUNNER_PROJECT) {
      throw GradleException("$SMOKE_RUNNER_PROJECT cannot smoke-test itself")
    }
    val objects = project.objects
    project.configurations.create(SMOKE_RUNNER_CONFIGURATION) {
      description = "Class path of the smoke runner ($SMOKE_RUNNER_PROJECT)."
      isCanBeConsumed = false
      isCanBeResolved = true
      attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class.java, Usage.JAVA_RUNTIME))
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category::class.java, Category.LIBRARY))
        attribute(
          LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE,
          objects.named(LibraryElements::class.java, LibraryElements.JAR),
        )
        attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling::class.java, Bundling.EXTERNAL))
        attribute(
          TargetJvmEnvironment.TARGET_JVM_ENVIRONMENT_ATTRIBUTE,
          objects.named(TargetJvmEnvironment::class.java, TargetJvmEnvironment.STANDARD_JVM),
        )
      }
    }
    project.dependencies.add(
      SMOKE_RUNNER_CONFIGURATION,
      project.dependencies.project(mapOf("path" to SMOKE_RUNNER_PROJECT)),
    )
    project.tasks.register(SMOKE_LIFECYCLE) {
      group = "verification"
      description =
        "Starts ${ext.appName} without an agent, calls its endpoint catalog and checks the expected " +
          "behaviour, for every smoke variant (sample-apps/README.md)."
    }
  }

  fun smokeTaskName(variant: String): String = SMOKE_LIFECYCLE + taskSuffix(variant)

  private fun registerSmoke(project: Project, ext: SampleAppExtension, variant: SmokeVariant) {
    val toolchains = project.extensions.getByType(JavaToolchainService::class.java)
    val slot =
      project.gradle.sharedServices.registerIfAbsent(
        "argosSampleSmokeSlot",
        SampleSmokeSlot::class.java,
      ) {
        maxParallelUsages.set(1)
      }
    val runner = project.configurations.getByName(SMOKE_RUNNER_CONFIGURATION)
    val args = project.objects.newInstance(SmokeArguments::class.java)
    args.appName.set(ext.appName)
    args.variantName.set(variant.name)
    args.kind.set(ext.packaging.map { it.kind })
    args.catalog.set(ext.catalog)
    args.artifact.set(ext.packaging.flatMap { artifactFile(project, it) })
    args.appLauncher.set(
      toolchains.launcherFor { languageVersion.set(variant.runJdk.map { JavaLanguageVersion.of(it) }) }
    )
    args.appJdk.set(variant.runJdk)
    args.environment.set(variant.environment)
    args.jvmArgs.set(variant.jvmArgs)
    args.runtimeExpectations.set(variant.runtimeExpectations)
    args.bodyExpectations.set(variant.bodyExpectations)
    args.expectClients.set(ext.expectedClients)
    args.seedEnvironment.set(variant.seedEnvironment)
    args.contexts.set(
      ext.packaging.flatMap { if (it == SamplePackaging.WAR) ext.webContexts else project.provider { emptyList<String>() } }
    )
    args.tomcatHome.set(variant.tomcat.flatMap { project.layout.buildDirectory.dir("tomcat/$it") })
    args.reportDirectory.set(project.layout.buildDirectory.dir("reports/sample-smoke/${variant.name}"))
    args.startupRuns.set(project.providers.gradleProperty("sample.startupRuns"))
    args.extStubUrl.set(project.providers.gradleProperty("sample.extStubUrl"))
    val runnerTmp = project.layout.buildDirectory.dir("tmp/sample-smoke/${variant.name}")

    val task =
      project.tasks.register(smokeTaskName(variant.name), JavaExec::class.java) {
        group = "verification"
        description =
          "Smoke variant '${variant.name}' of ${ext.appName}: start without an agent, call the " +
            "catalog, check expectations; report in build/reports/sample-smoke/${variant.name}."
        usesService(slot)
        javaLauncher.set(
          toolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(SMOKE_RUNNER_JDK)) }
        )
        classpath = runner
        mainClass.set(SMOKE_MAIN_CLASS)
        argumentProviders.add(args)
        dependsOn(ext.packaging.map { artifactTaskName(it) })
        dependsOn(variant.tomcat.map { listOf(stageTomcatTaskName(it)) }.orElse(emptyList()))
        outputs.dir(args.reportDirectory)
        outputs.upToDateWhen { false }
        val tmpDir = runnerTmp.get().asFile
        systemProperty("java.io.tmpdir", tmpDir.absolutePath)
        doFirst { tmpDir.mkdirs() }
      }
    project.tasks.named(SMOKE_LIFECYCLE) { dependsOn(task) }
  }

  private fun choosePackaging(project: Project, ext: SampleAppExtension, packaging: SamplePackaging) {
    val current = ext.packaging.get()
    if (current != SamplePackaging.LIBRARY) {
      throw GradleException("${project.path}: packaging already set to $current")
    }
    ext.packaging.set(packaging)
  }

  private fun artifactTaskName(packaging: SamplePackaging): String =
    when (packaging) {
      SamplePackaging.BOOT_JAR,
      SamplePackaging.EXECUTABLE_JAR -> APP_JAR_TASK
      SamplePackaging.WAR -> "war"
      SamplePackaging.LIBRARY -> throw GradleException("A library has no runnable artifact")
    }

  private fun artifactFile(project: Project, packaging: SamplePackaging): Provider<RegularFile> =
    when (packaging) {
      SamplePackaging.BOOT_JAR,
      SamplePackaging.EXECUTABLE_JAR ->
        project.tasks.named(APP_JAR_TASK, Jar::class.java).flatMap { it.archiveFile }
      SamplePackaging.WAR -> project.tasks.named("war", War::class.java).flatMap { it.archiveFile }
      SamplePackaging.LIBRARY ->
        throw GradleException("${project.path}: smoke needs bootJar(), executableJar() or war()")
    }

  private fun stageTomcatTaskName(key: String): String = "sampleStageTomcat${taskSuffix(key)}"

  private fun taskSuffix(name: String): String =
    name.split('-', '_', '.').filter { it.isNotEmpty() }.joinToString("") {
      it.replaceFirstChar(Char::uppercaseChar)
    }

  /** build-info.properties (app, version) as a generated main resource. */
  private fun registerBuildInfo(project: Project, ext: SampleAppExtension) {
    val task =
      project.tasks.register("sampleBuildInfo", SampleBuildInfo::class.java) {
        appName.set(ext.appName)
        appVersion.set(project.version.toString())
        outputDirectory.set(project.layout.buildDirectory.dir("generated/sample-build-info"))
      }
    project.extensions
      .getByType(SourceSetContainer::class.java)
      .getByName("main")
      .resources
      .srcDir(task)
  }

  /**
   * The packaged app as an outgoing variant (`sampleAppElements`) for the agent test harness, the
   * launch descriptor.
   */
  private fun registerArtifact(
    project: Project,
    ext: SampleAppExtension,
    file: Provider<RegularFile>,
    producer: TaskProvider<*>,
  ) {
    val objects = project.objects
    project.configurations.create("sampleAppElements") {
      description = "The packaged sample app (${ext.appName}) for AppLauncher (18 §5.2)."
      isCanBeConsumed = true
      isCanBeResolved = false
      attributes {
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category::class.java, "argos-sample-app"))
      }
      outgoing.artifact(file) { builtBy(producer) }
    }
    val descriptor =
      project.tasks.register("sampleLaunchDescriptor", SampleLaunchDescriptor::class.java) {
        properties.put("app", ext.appName)
        properties.put("kind", ext.packaging.map { it.kind })
        properties.put("artifact", file.map { it.asFile.name })
        properties.put("mainClass", ext.mainClass.orElse(""))
        properties.put("runJdk", ext.runJdk.map { it.toString() })
        properties.put("javaRelease", ext.javaRelease.map { it.toString() })
        properties.put("catalog", ext.catalog)
        properties.put("contexts", ext.webContexts.map { it.joinToString(",") })
        properties.put(
          "tomcat",
          ext.tomcatDistributions.map { m -> m.entries.joinToString(",") { "${it.key}:${it.value}" } },
        )
        descriptor.set(
          project.layout.buildDirectory.file("$ARTIFACT_DIR/${ext.appName}.properties")
        )
      }
    // Not part of `assemble`: the Spring Boot jars are large (stored, uncompressed) and only the
    // smoke, the agent harness and images need them; they build them on demand. The WAR plugin keeps
    // `war` in `assemble`.
    producer.configure { finalizedBy(descriptor) }
  }

  private fun archiveOperations(project: Project): ArchiveOperations =
    project.objects.newInstance(ArchiveServices::class.java).archives

  /** Injects [ArchiveOperations] (zipTree without capturing the Project in task actions). */
  abstract class ArchiveServices @Inject constructor(val archives: ArchiveOperations)
}
