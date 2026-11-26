// SPDX-License-Identifier: Apache-2.0
package io.argos.buildlogic.sample

import javax.inject.Inject
import org.gradle.api.Action
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.Project
import org.gradle.api.artifacts.MinimalExternalModuleDependency
import org.gradle.api.file.FileCollection
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider

/** How a sample-apps project is packaged (18 §4.4). */
enum class SamplePackaging(val kind: String) {
  /** A library used by apps (common) or a tool started by Gradle (smoke). */
  LIBRARY("library"),
  /** Spring Boot executable jar (BOOT-INF/classes, BOOT-INF/lib, Spring Boot loader). */
  BOOT_JAR("boot-jar"),
  /** Plain executable jar with every runtime dependency merged (ext-stub, load). */
  EXECUTABLE_JAR("jar"),
  /** WAR deployed to a standalone Tomcat distribution. */
  WAR("war"),
}

/** Spring Boot loader entry points: 2.x (classic) and 3.2+ (launch package). */
enum class BootLauncher(val className: String) {
  CLASSIC("org.springframework.boot.loader.JarLauncher"),
  LAUNCH("org.springframework.boot.loader.launch.JarLauncher"),
}

/**
 * The `sampleApp { }` extension of the argos.sample-app convention plugin (WO-01).
 *
 * ```
 * sampleApp {
 *   javaRelease = 17          // javac --release (the JDK 21 compiler compiles every module)
 *   runJdk = 17               // JDK that runs tests and the default smoke (18 §4.1 build JDK)
 *   catalog = "shop"          // endpoint catalog checked by the smoke (common: Catalogs)
 *   bootJar("io.argos.sample.boot3.ShopBoot3Application", libs.sample.boot3.loader)
 *   dockerImage()
 *   smoke {
 *     expectClients("hurl", "resttemplate", "hc5", "okhttp", "jdk11", "webclient")
 *     variant("default")
 *     variant("vt") { runJdk = 21; env("SAMPLE_VT", "true"); expectRuntime("virtualThreads", "true") }
 *     integrationTest("default", "vt")
 *   }
 * }
 * ```
 */
abstract class SampleAppExtension @Inject constructor(private val project: Project) {

  /** App name: archive file name, image name, build-info `app` (the Gradle project name). */
  val appName: String = project.name

  /** `javac --release` of main and test sources. */
  abstract val javaRelease: Property<Int>

  /** JDK that runs the tests and, unless a variant says otherwise, the app in the smoke. */
  abstract val runJdk: Property<Int>

  /** Endpoint catalog of the smoke: "shop", "batch" or "none". */
  abstract val catalog: Property<String>

  /** Packaging chosen by [bootJar], [executableJar] or [war]; LIBRARY otherwise. */
  abstract val packaging: Property<SamplePackaging>

  /** Main class of BOOT_JAR and EXECUTABLE_JAR packaging. */
  abstract val mainClass: Property<String>

  /** WAR packaging: the context paths the smoke deploys the WAR under (default: the app name). */
  abstract val webContexts: ListProperty<String>

  /** WAR packaging: Tomcat distribution keys (e.g. "tomcat10") -> staged CATALINA_HOME. */
  abstract val tomcatDistributions: MapProperty<String, String>

  /** Smoke variants (see [smoke]). */
  abstract val smokeVariants: NamedDomainObjectContainer<SmokeVariant>

  /** Variants that also run in `integrationTest` (18 §2.1 integration layer, PR budget). */
  abstract val integrationTestVariants: ListProperty<String>

  /**
   * Outgoing-call clients of the app's stack (18 §4.1) that every smoke variant expects in GET
   * /api/runtime `clients`, as an exact set (see [SmokeSpec.expectClients]); empty: not checked.
   */
  abstract val expectedClients: ListProperty<String>

  /** BOOT_JAR packaging: the Spring Boot loader entry point of the jar (set by [bootJar]). */
  abstract val bootLauncher: Property<BootLauncher>

  /** Spring Boot executable jar. [loader] is the spring-boot-loader of the app's Boot version. */
  fun bootJar(
    mainClass: String,
    loader: Provider<MinimalExternalModuleDependency>,
    launcher: BootLauncher = BootLauncher.LAUNCH,
  ) {
    SampleAppWiring.bootJar(project, this, mainClass, loader, launcher)
  }

  /**
   * A second Spring Boot jar `build/sample-app/<app>-<name>.jar` (task `sampleApp<Name>Jar`): the
   * same classes, loader and manifest as [bootJar], plus [libraries] in BOOT-INF/lib. The default
   * jar, its launch descriptor and its smoke are unchanged (e.g. shop-boot3 `devtools`, WO-50).
   */
  fun bootJarVariant(name: String, libraries: FileCollection) {
    SampleAppWiring.bootJarVariant(project, this, name, libraries)
  }

  /** Plain executable jar (`java -jar`), runtime dependencies merged. */
  fun executableJar(mainClass: String) {
    SampleAppWiring.executableJar(project, this, mainClass)
  }

  /** WAR for a standalone Tomcat; the smoke deploys it under each context path. */
  fun war(vararg contexts: String) {
    SampleAppWiring.war(project, this, contexts.toList())
  }

  /** Registers a standalone Tomcat distribution (zip from Maven Central) under [key]. */
  fun tomcat(key: String, distribution: Provider<MinimalExternalModuleDependency>) {
    SampleAppWiring.tomcat(project, this, key, distribution)
  }

  /** `sampleImage`: `docker build` of the project's Dockerfile (JDK_IMAGE build argument). */
  fun dockerImage() {
    SampleAppWiring.dockerImage(project, this)
  }

  /** Smoke variants and the `sampleSmoke` tasks. */
  fun smoke(action: Action<SmokeSpec>) {
    SampleAppWiring.ensureSmokeRunner(project, this)
    action.execute(SmokeSpec(this))
  }
}

/** DSL of `sampleApp.smoke { }`. */
class SmokeSpec(private val app: SampleAppExtension) {

  fun variant(name: String) {
    app.smokeVariants.register(name)
  }

  fun variant(name: String, action: Action<SmokeVariant>) {
    app.smokeVariants.register(name, action)
  }

  /** Variants that also run as part of `integrationTest`. */
  fun integrationTest(vararg variants: String) {
    app.integrationTestVariants.addAll(variants.toList())
  }

  /**
   * The outgoing-call clients of the app's 18 §4.1 stack (`hurl`, `resttemplate`, `hc4`, ...).
   * Every variant fails when GET /api/runtime `clients` is not exactly this set, so a client that
   * drops out of the app fails the smoke instead of turning its pay entries into SKIPPED.
   */
  fun expectClients(vararg names: String) {
    app.expectedClients.addAll(names.toList())
  }
}

/** One smoke run: app start (JDK, environment, JVM options), catalog calls, expectations. */
abstract class SmokeVariant @Inject constructor(private val variantName: String) :
  org.gradle.api.Named {

  override fun getName(): String = variantName

  /** JDK that runs the app (default: sampleApp.runJdk). */
  abstract val runJdk: Property<Int>

  /** Environment variables of the app process (SAMPLE_VT, SAMPLE_ROUTING_DS, ...). */
  abstract val environment: MapProperty<String, String>

  /** Extra JVM options of the app process. */
  abstract val jvmArgs: ListProperty<String>

  /** WAR packaging: Tomcat distribution key (sampleApp.tomcat(key, ...)). */
  abstract val tomcat: Property<String>

  /** Expected fields of GET /api/runtime (string comparison of the JSON value). */
  abstract val runtimeExpectations: MapProperty<String, String>

  /** Expected substrings of catalog responses: catalog entry id -> substring. */
  abstract val bodyExpectations: MapProperty<String, String>

  /**
   * Sets the secret-looking seed variables of 18 §4.3 (DB_PASSWORD, AWS_SECRET_ACCESS_KEY,
   * ARGOS_TOKEN, JAVA_TOOL_OPTIONS with the ARGOS_SEED sentinel; common: Seeds.environment()) in the
   * app process.
   */
  abstract val seedEnvironment: Property<Boolean>

  fun env(key: String, value: String) {
    environment.put(key, value)
  }

  fun jvmArg(vararg args: String) {
    jvmArgs.addAll(args.toList())
  }

  fun expectRuntime(field: String, value: String) {
    runtimeExpectations.put(field, value)
  }

  fun expectBody(entryId: String, substring: String) {
    bodyExpectations.put(entryId, substring)
  }
}
