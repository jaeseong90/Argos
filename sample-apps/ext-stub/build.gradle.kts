// SPDX-License-Identifier: Apache-2.0
//
// ext-stub (18 §4.1): target of the sample apps' outgoing calls. Normal answer, delay (ms
// parameter), no answer (client timeout), status codes (5xx), redirect, request log for header
// propagation checks. "Connection refused" needs a port where nothing listens: the apps call
// <ext-stub host>:SAMPLE_EXT_REFUSED_PORT (default 1). JDK HttpServer only: no dependencies.
// HTTP behaviour tests open sockets, so they run in integrationTest (18 §2.2: no network in test);
// ExtStubJarTest starts the packaged jar (java -jar, options and environment) as a process.

plugins {
  id("argos.sample-app")
  id("argos.integration-test")
}

description = "Sample apps: outgoing-call stub (normal, delay, timeout, 5xx, redirect)."

sampleApp {
  javaRelease = 17
  runJdk = 17
  executableJar("io.argos.sample.extstub.ExtStubMain")
  dockerImage()
}

/**
 * System properties of ExtStubJarTest: the packaged ext-stub.jar (the `java -jar` entry point that
 * run-bench.sh and the image use), the JDK that runs it (runJdk) and scratch space.
 */
abstract class PackagedJarArguments : CommandLineArgumentProvider {
  @get:InputFiles
  @get:PathSensitive(PathSensitivity.NONE)
  abstract val jar: ConfigurableFileCollection

  @get:Nested abstract val launcher: Property<JavaLauncher>

  @get:Internal abstract val scratch: DirectoryProperty

  override fun asArguments(): List<String> =
    listOf(
      "-Dargos.extStub.jar=${jar.singleFile.absolutePath}",
      "-Dargos.extStub.java=${launcher.get().executablePath.asFile.absolutePath}",
      "-Dargos.extStub.scratch=${scratch.get().asFile.absolutePath}",
    )
}

val packagedJarArguments =
  objects.newInstance<PackagedJarArguments>().apply {
    jar.from(tasks.named<Jar>("sampleAppJar").flatMap { it.archiveFile })
    launcher =
      javaToolchains.launcherFor {
        languageVersion = sampleApp.runJdk.map { JavaLanguageVersion.of(it) }
      }
    scratch = layout.buildDirectory.dir("tmp/integrationTest-scratch")
  }

tasks.named<Test>("integrationTest") {
  jvmArgumentProviders.add(packagedJarArguments)
  val scratchDir = packagedJarArguments.scratch
  doFirst {
    // Logs and temp files of the previous run go; nothing outside build/.
    scratchDir.get().asFile.deleteRecursively()
    scratchDir.get().asFile.mkdirs()
  }
}
