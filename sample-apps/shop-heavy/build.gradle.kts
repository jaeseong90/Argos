// SPDX-License-Identifier: Apache-2.0
//
// shop-heavy (18 §4.1): shop-boot3 plus Spring Cloud, Hibernate modules, Jackson modules, QueryDSL
// and other common enterprise libraries: at least 200 dependency jars and 30,000 classes, the
// startup cost baseline of N-04/N-27. Java 17, runs on 17, 21 and 25 (02 §9). The app reuses
// shop-boot3's classes (plain jar) and initializes every extra library during the context refresh,
// before the port is bound, so that at least 30,000 classes are loaded at startup
// (io.argos.sample.heavy.startup.StartupReport; the smoke checks startupClassTargetMet).
// The packaged size target is asserted by verifyHeavyFootprint (part of check, below).

import java.util.zip.ZipFile

plugins {
  id("argos.sample-app")
  id("argos.integration-test")
}

description = "Sample app: shop-boot3 with a heavy dependency set (200+ jars, 30,000+ classes)."

sampleApp {
  javaRelease = 17
  runJdk = 17
  catalog = "shop"
  bootJar("io.argos.sample.heavy.ShopHeavyApplication", libs.sample.boot3.loader)
  dockerImage()
  smoke {
    // Clients of GET /api/pay/{id}: shop-boot3's stack (its web layer, unchanged).
    expectClients("hurl", "resttemplate", "hc5", "okhttp", "jdk11", "webclient")
    variant("default") {
      expectRuntime("heavyWarmup", "ok")
      expectRuntime("startupClassTargetMet", "true")
    }
    variant("jdk21") {
      runJdk = 21
      expectRuntime("heavyWarmup", "ok")
      expectRuntime("startupClassTargetMet", "true")
    }
    // JDK 25 runs as the 02 §9 matrix does: sun.misc.Unsafe memory access denied.
    variant("jdk25") {
      runJdk = 25
      jvmArg("--sun-misc-unsafe-memory-access=deny")
      expectRuntime("javaFeatureVersion", "25")
      expectRuntime("heavyWarmup", "ok")
      expectRuntime("startupClassTargetMet", "true")
    }
    // Startup without the startup steps: libraries load on first use only (comparison run).
    variant("no-warmup") {
      env("SAMPLE_HEAVY_WARMUP", "false")
      expectRuntime("heavyWarmup", "off")
    }
  }
}

dependencies {
  implementation(platform(libs.sample.boot3.bom))
  implementation(platform(libs.sample.spring.cloud.bom))
  implementation(project(":sample-apps:shop-boot3"))
  implementation(project(":sample-apps:common"))
  // Compile-time view of shop-boot3's stack used by the heavy layer (JPA, JDBC, transactions).
  implementation(libs.sample.starter.data.jpa)
  // Spring Cloud
  implementation(libs.sample.cloud.openfeign)
  implementation(libs.sample.cloud.circuitbreaker)
  implementation(libs.sample.cloud.loadbalancer)
  implementation(libs.sample.cloud.gateway.mvc)
  implementation(libs.sample.cloud.function.web)
  // Spring Boot starters beyond shop-boot3
  implementation(libs.sample.starter.validation)
  implementation(libs.sample.starter.cache)
  implementation(libs.sample.starter.aop)
  implementation(libs.sample.starter.thymeleaf)
  implementation(libs.sample.starter.hateoas)
  implementation(libs.sample.starter.mail)
  // Hibernate modules: Envers, JCache second-level cache (Caffeine as the JCache provider)
  implementation(libs.sample.hibernate.envers)
  implementation(libs.sample.hibernate.jcache)
  implementation(libs.sample.caffeine)
  runtimeOnly(libs.sample.caffeine.jcache)
  // Jackson modules
  implementation(libs.sample.jackson.xml)
  implementation(libs.sample.jackson.yaml)
  implementation(libs.sample.jackson.csv)
  implementation(libs.sample.jackson.cbor)
  implementation(libs.sample.jackson.smile)
  implementation(libs.sample.jackson.guava)
  implementation(libs.sample.jackson.joda)
  implementation(libs.sample.jackson.hibernate6)
  implementation(libs.sample.jackson.blackbird)
  implementation(libs.sample.jackson.kotlin)
  // Query, API documentation, documents, cloud SDK, utilities, metrics
  implementation(libs.sample.querydsl.jpa)
  implementation(libs.sample.springdoc)
  implementation(libs.sample.poi.ooxml)
  implementation(libs.sample.aws.s3)
  implementation(libs.sample.guava)
  implementation(libs.sample.commons.lang3)
  implementation(libs.sample.commons.text)
  implementation(libs.sample.commons.collections4)
  implementation(libs.sample.mapstruct)
  implementation(libs.sample.micrometer.prometheus)
}

// QueryDSL's class files carry org.jetbrains.annotations.Range, an annotation QueryDSL compiles
// against but does not publish as a dependency; javac's classfile lint reports the missing class,
// which -Werror turns into an error. Only that lint category is switched off, only here.
tasks.named<JavaCompile>("compileJava") { options.compilerArgs.add("-Xlint:-classfile") }

// The integration tests use the libraries of the heavy layer directly (Thymeleaf, POI, mail).
configurations.named("integrationTestImplementation") {
  extendsFrom(configurations.implementation.get())
}

/**
 * Fails when the runtime class path, which is exactly what goes into BOOT-INF/lib of the packaged
 * app (argos.sample-app), has fewer than [minJars] jars or fewer than [minClasses] classes in those
 * jars (18 §4.1: at least 200 dependency jars and 30,000 classes). Classes are counted as the smoke
 * report counts them (smoke.json `artifact.dependencyClasses`): `.class` entries, including
 * multi-release ones, without module-info and package-info. Writes the measured numbers to
 * [report].
 */
abstract class VerifyHeavyFootprint : DefaultTask() {

  @get:Classpath abstract val libraries: ConfigurableFileCollection

  @get:Input abstract val minJars: Property<Int>

  @get:Input abstract val minClasses: Property<Long>

  @get:OutputFile abstract val report: RegularFileProperty

  @TaskAction
  fun verify() {
    val jars = libraries.files.filter { it.isFile && it.name.endsWith(".jar") }
    val classes = jars.sumOf { jar -> countClasses(jar) }
    report
      .get()
      .asFile
      .writeText(
        "# Measured by :sample-apps:shop-heavy:verifyHeavyFootprint (runtime class path).\n" +
          "dependencyJars=${jars.size}\n" +
          "dependencyClasses=$classes\n" +
          "minJars=${minJars.get()}\n" +
          "minClasses=${minClasses.get()}\n"
      )
    val problems = mutableListOf<String>()
    if (jars.size < minJars.get()) {
      problems += "${jars.size} dependency jars, expected at least ${minJars.get()}"
    }
    if (classes < minClasses.get()) {
      problems += "$classes classes in the dependency jars, expected at least ${minClasses.get()}"
    }
    if (problems.isNotEmpty()) {
      throw GradleException(
        "shop-heavy is below its size target (18 §4.1): ${problems.joinToString("; ")}" +
          " (measured: ${jars.size} jars, $classes classes)"
      )
    }
    logger.lifecycle("shop-heavy footprint: ${jars.size} dependency jars, $classes classes")
  }

  private fun countClasses(jar: File): Long =
    ZipFile(jar).use { zip ->
      zip
        .entries()
        .asSequence()
        .count { entry ->
          val name = entry.name
          !entry.isDirectory &&
            name.endsWith(".class") &&
            !name.endsWith("module-info.class") &&
            !name.endsWith("package-info.class")
        }
        .toLong()
    }
}

val verifyHeavyFootprint =
  tasks.register<VerifyHeavyFootprint>("verifyHeavyFootprint") {
    description = "Checks that shop-heavy has at least 200 dependency jars and 30,000 classes."
    group = "verification"
    libraries.from(configurations.runtimeClasspath)
    minJars = 200
    minClasses = 30_000L
    report = layout.buildDirectory.file("reports/heavy-footprint/footprint.properties")
  }

tasks.named("check") { dependsOn(verifyHeavyFootprint) }
