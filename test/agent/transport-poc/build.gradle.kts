// SPDX-License-Identifier: Apache-2.0
//
// WO-02 P-3: agent transport stack comparison (02 §11 P-3, 10 §10.3, 11 §15 #2-#4, A-1, A-14,
// ADR-0003 C-23). Development-only (ArgosProject.isDevelopmentOnly): nothing here is shipped, and no
// task of this project is part of `build` or `check` apart from formatting, README and license data.
//
// Comparison agent jars (one per transport x protobuf flavour), assembled exactly like
// :agent:agent-dist (19 §3.3 stages 1-3: shade + relocate + minimize, inst/**.classdata, root
// bootstrap classes + META-INF) from agent-core, every instrumentation module and a minimal sender
// that sends a small TransactionBatch every second to a local fake collector:
//   grpc-netty   NettyChannelBuilder, NIO, JDK TLS, fixed settings of 10 §10.3
//   grpc-okhttp  OkHttpChannelBuilder, JDK TLS
//   http1        own HTTP/1.1 client over a JDK SSLSocket (blocking, on argos-sender)
// x lite (protobuf-javalite) / full (protobuf-java) generated from a snapshot of the 11 §4 protos.
//
// Tasks (group "transport poc"):
//   transportPocJars    builds every comparison jar and its reports (check-shading, relocated
//                       strings, major version, own-code relocated literals, licenses)
//   transportPocTest    proto flavour tests (javalite map/optional, 11 §15 #2) + functional smoke of
//                       every jar against the fake collector (JDK 8 and 21)
//   transportPocBench   the P-3 measurement matrix -> build/poc/bench/<timestamp>/ (summary.md, raw/)
// Known findings waiting for a PM decision (ADR-0005 transport P1, P3) are asserted, not failed on:
// the Netty 4.2 jars must break C-22 with Netty classes only, and a grpc-netty smoke run on Windows
// JDK 16+ may create (and at once delete) the NIO selector's AF_UNIX socket file. Any other result
// fails as before; -Ptransport.poc.strict=true counts the known findings as failures again.
// JDK homes for the Java 8 ALPN boundary (11 §15 #4): -Ptransport.poc.jdk8u242=<home>,
// -Ptransport.poc.jdk8u252=<home> (optional; the bench reports "not run" without them).

import com.github.jengelman.gradle.plugins.shadow.ShadowExtension
import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import com.github.jengelman.gradle.plugins.shadow.transformers.ApacheLicenseResourceTransformer
import com.github.jengelman.gradle.plugins.shadow.transformers.ApacheNoticeResourceTransformer
import com.google.protobuf.gradle.ProtobufExtension
import com.google.protobuf.gradle.id
import io.argos.buildlogic.AgentShading
import io.argos.buildlogic.ArgosProject
import io.argos.buildlogic.CheckLicenses
import io.argos.buildlogic.CheckMajorVersion
import io.argos.buildlogic.CheckRelocatedStrings
import io.argos.buildlogic.ClassFiles
import io.argos.buildlogic.GenerateDepsManifest
import io.argos.buildlogic.GenerateThirdPartyNotices
import io.argos.buildlogic.LicenseDataRenderer
import io.argos.buildlogic.RelocatedStringPolicy
import io.argos.buildlogic.argosBash
import io.argos.buildlogic.argosLibs
import io.argos.buildlogic.argosGitCommit
import io.argos.buildlogic.argosProtocolVersion
import io.argos.buildlogic.argosSourceDateInstant
import io.argos.buildlogic.versionOf
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier

plugins { id("argos.java-agent") }

// Both plugins come with the build-logic classpath (see :agent:agent-dist).
apply(plugin = "com.google.protobuf")

apply(plugin = "com.gradleup.shadow")

description = "WO-02 P-3 agent transport comparison (development only, never shipped)."

val pocGroup = "transport poc"

// -Ptransport.poc.strict=true: the known findings (ADR-0005 transport P1, P3) fail like any other.
val strictPoc = providers.gradleProperty("transport.poc.strict").map(String::toBoolean).getOrElse(false)

// The plugin's own shadowJar (main source set) is not used; nothing joins assemble.
extensions.configure<ShadowExtension> {
  addShadowVariantIntoJavaComponent = false
  addShadowJarToAssembleLifecycle = false
}

tasks.named("shadowJar") { enabled = false }

// --- Protocol snapshot: javalite and full protobuf-java code from the same files (A-14) ----------

val protoLite = sourceSets.create("protoLite")
val protoFull = sourceSets.create("protoFull")

listOf(protoLite, protoFull).forEach {
  it.extensions.getByName<SourceDirectorySet>("proto").setSrcDirs(listOf("src/proto"))
}

val protobufVersion = argosLibs.versionOf("protobuf")
val grpcVersion = argosLibs.versionOf("grpc")

configure<ProtobufExtension> {
  protoc { artifact = "com.google.protobuf:protoc:$protobufVersion" }
  plugins { id("grpc") { artifact = "io.grpc:protoc-gen-grpc-java:$grpcVersion" } }
  generateProtoTasks {
    ofSourceSet(protoLite.name).configureEach {
      builtins.named("java") { option("lite") }
      plugins {
        id("grpc") {
          option("@generated=omit")
          option("lite")
        }
      }
    }
    ofSourceSet(protoFull.name).configureEach {
      plugins { id("grpc") { option("@generated=omit") } }
    }
  }
}

// Generated code is not ours to lint (the argos.java-common -Werror would fail on protoc output).
listOf(protoLite, protoFull).forEach {
  tasks.named<JavaCompile>(it.compileJavaTaskName) { options.compilerArgs.remove("-Werror") }
}

dependencies {
  "protoLiteImplementation"(libs.protobuf.javalite)
  "protoLiteImplementation"(libs.grpc.protobuf.lite)
  "protoLiteImplementation"(libs.grpc.stub)
  "protoFullImplementation"(libs.protobuf.java)
  "protoFullImplementation"(libs.grpc.protobuf)
  "protoFullImplementation"(libs.grpc.stub)
}

// --- Comparison variants ----------------------------------------------------------------------

/**
 * One comparison jar: a transport (source directory below src/), a protobuf flavour and, for
 * grpc-netty, the Netty line (4.2 from the catalog, or 4.1 for the ADR-0003 C-22 comparison).
 */
data class Variant(
  val id: String,
  val transport: String,
  val lite: Boolean,
  val netty41: Boolean = false,
  /** "-x" jars: same classes as [sourceId], minus src/../unsafe/<sourceId>.excluded.txt. */
  val sourceId: String = id,
) {
  val sourceSetName = camel(sourceId)
  val taskSuffix = camel(id).replaceFirstChar { it.uppercase() }
  val grpc = transport.startsWith("grpc")
  val excludesUnsafe = sourceId != id
  /** ADR-0005 transport P1: Netty 4.2 ships Java 11 class files (major 55), ADR-0003 C-22. */
  val knownC22Violation = transport == "grpcNetty" && !netty41
  /** ADR-0005 transport P3: the event loop's NIO selector, Windows JDK 16+ (T-BLD-006). */
  val knownAfUnixTemp = transport == "grpcNetty"

  private fun camel(text: String) =
    text.split("-").joinToString("") { it.replaceFirstChar(Char::uppercase) }.replaceFirstChar(Char::lowercase)
}

// -Ptransport.poc.variants=grpc-netty-full,http1-full limits the jars that are built (iteration).
val selectedVariants =
  providers.gradleProperty("transport.poc.variants").map { it.split(",").map(String::trim).toSet() }.orNull
val variants =
  listOf(
      Variant("grpc-netty-lite", "grpcNetty", lite = true),
      Variant("grpc-netty-full", "grpcNetty", lite = false),
      Variant("grpc-netty41-full", "grpcNetty", lite = false, netty41 = true),
      Variant("grpc-okhttp-lite", "grpcOkhttp", lite = true),
      Variant("grpc-okhttp-full", "grpcOkhttp", lite = false),
      Variant("http1-lite", "http1", lite = true),
      Variant("http1-full", "http1", lite = false),
      // ADR-0003 "exclude rather than allow-list": never-loaded Unsafe-referencing classes removed
      // (lists from the P-3 class-load runs), the loaded ones in unsafe/<id>.allowlist.txt.
      Variant("grpc-netty41-full-x", "grpcNetty", lite = false, netty41 = true, sourceId = "grpc-netty41-full"),
      Variant("http1-full-x", "http1", lite = false, sourceId = "http1-full"),
    )
    .filter { selectedVariants == null || it.id in selectedVariants }

// Every instrumentation module, as in :agent:agent-dist (the comparison jars carry the full agent).
val instrumentationPaths =
  rootProject.project(":agent:agent-instrumentation").childProjects.values.map { it.path }.sorted()

/**
 * Annotation-only artifacts of the gRPC/Netty/OkHttp graphs: not needed at run time (CLASS
 * retention or never read reflectively), so they are not shaded at all instead of being relocated.
 */
val annotationOnlyModules =
  listOf(
    "com.google.code.findbugs" to "jsr305",
    "com.google.errorprone" to "error_prone_annotations",
    "com.google.j2objc" to "j2objc-annotations",
    "org.codehaus.mojo" to "animal-sniffer-annotations",
    "org.checkerframework" to "checker-qual",
    "com.google.android" to "annotations",
    "org.jetbrains" to "annotations",
    "org.jspecify" to "jspecify",
  )

/**
 * Classes removed from a transport's jar (original class file paths; ADR-0003 "exclude rather than
 * allow-list"), by transport. Empty: the measured jars keep every class so that check-shading
 * reports what each stack brings (Netty 4.2's Java 11 JCTools VarHandle queues cannot be removed:
 * Netty 4.2 uses them on Java 11+ whenever Unsafe is off).
 */
val pocExcludedClasses = mapOf<String, List<String>>()

/** Replacement of java.util.logging.Logger in the comparison jars (src/sender/.../JulLogger). */
val JUL_REPLACEMENT = "io.argos.agent.transportpoc.sender.JulLogger"

/** Extra relocations of the OkHttp variant (okio 3 is Kotlin; ADR-0003 C-23 evidence). */
val okhttpExtraPackages = listOf("okio", "kotlin")

/**
 * Property names of the shaded transport libraries checked by checkRelocatedStrings in addition to
 * AgentShading.PROPERTY_STRINGS (C-23): Netty knobs of 10 §10.3 and related ones, gRPC switches.
 */
val transportPropertyNames =
  AgentShading.PROPERTY_STRINGS +
    listOf(
      "io.netty.noKeySetOptimization",
      "io.netty.recycler.maxCapacityPerThread",
      "io.netty.allocator.numHeapArenas",
      "io.netty.machineId",
      "io.netty.processId",
      "io.netty.native.workdir",
      "io.netty.transport.noNative",
      "io.netty.handler.ssl.openssl.useKeyManagerFactory",
      "io.netty.varHandle.enabled",
      "io.grpc.netty.useCustomAllocator",
      "io.grpc.internal.DnsNameResolverProvider.enable_jndi",
      "io.grpc.internal.DnsNameResolverProvider.enable_grpclb",
    )

val sender = layout.projectDirectory.dir("src/sender/java")

variants.distinctBy { it.sourceSetName }.forEach { v ->
  val proto = if (v.lite) protoLite else protoFull
  val sourceSet =
    sourceSets.create(v.sourceSetName) {
      java.setSrcDirs(
        listOfNotNull(
          sender,
          if (v.grpc) layout.projectDirectory.dir("src/grpcCommon/java") else null,
          layout.projectDirectory.dir("src/${v.transport}/java"),
        )
      )
      resources.setSrcDirs(emptyList<Any>())
    }
  dependencies {
    sourceSet.compileOnlyConfigurationName(project(ArgosProject.AGENT_BOOTSTRAP_PATH))
    sourceSet.compileOnlyConfigurationName(proto.output)
    sourceSet.implementationConfigurationName(project(":agent:agent-core"))
    instrumentationPaths.forEach { sourceSet.implementationConfigurationName(project(it)) }
    sourceSet.implementationConfigurationName(if (v.lite) libs.protobuf.javalite else libs.protobuf.java)
    if (v.grpc) {
      sourceSet.implementationConfigurationName(
        if (v.netty41) enforcedPlatform(libs.poc.netty41.bom) else platform(libs.netty.bom)
      )
      sourceSet.implementationConfigurationName(libs.grpc.stub)
      sourceSet.implementationConfigurationName(if (v.lite) libs.grpc.protobuf.lite else libs.grpc.protobuf)
    }
    when (v.transport) {
      "grpcNetty" -> sourceSet.implementationConfigurationName(libs.grpc.netty)
      "grpcOkhttp" -> sourceSet.implementationConfigurationName(libs.grpc.okhttp)
    }
  }
  configurations.named(sourceSet.runtimeClasspathConfigurationName) {
    annotationOnlyModules.forEach { (group, module) -> exclude(group = group, module = module) }
  }
}

// --- Fake collector (JDK 21 process), host application and bench runner -----------------------

val collector = sourceSets.create("collector")
val host = sourceSets.create("host")
val bench = sourceSets.create("bench")

// The bench runner only runs on the build JDK (it launches the measured JVMs as processes).
tasks.named<JavaCompile>(bench.compileJavaTaskName) { options.release = ArgosProject.BUILD_JDK }

dependencies {
  "collectorImplementation"(protoFull.output)
  "collectorImplementation"(platform(libs.netty.bom))
  "collectorImplementation"(libs.protobuf.java)
  "collectorImplementation"(libs.grpc.protobuf)
  "collectorImplementation"(libs.grpc.stub)
  "collectorImplementation"(libs.grpc.netty)
}

// --- Variant jar assembly (mirrors :agent:agent-dist, 19 §3.3) ----------------------------------

val agentBootstrapScope = configurations.dependencyScope("pocAgentBootstrap")
val agentBootstrapJar =
  configurations.resolvable("pocAgentBootstrapJar") {
    extendsFrom(agentBootstrapScope.get())
    isTransitive = false
    attributes {
      attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
      attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
      attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
      attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.EXTERNAL))
    }
  }

// The release agent jar without transport: baseline of every delta (P-3 table "base").
val baseAgentScope = configurations.dependencyScope("pocBaseAgent")
val baseAgentJar =
  configurations.resolvable("pocBaseAgentJar") {
    extendsFrom(baseAgentScope.get())
    attributes { attribute(Category.CATEGORY_ATTRIBUTE, objects.named("argos-agent-jar")) }
  }

dependencies {
  agentBootstrapScope(project(ArgosProject.AGENT_BOOTSTRAP_PATH))
  baseAgentScope(project(":agent:agent-dist"))
}

val licenseDataFile = layout.buildDirectory.file("reports/dependency-license/${LicenseDataRenderer.FILE_NAME}")

/** Minimal CycloneDX document listing argos-deps.txt (comparison jars are never published). */
abstract class PocSbom : DefaultTask() {
  @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val depsFile: RegularFileProperty

  @get:Input abstract val componentName: Property<String>

  @get:OutputFile abstract val outputFile: RegularFileProperty

  @TaskAction
  fun write() {
    fun json(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    val components =
      depsFile.get().asFile.readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.map { line ->
        val (group, name, version) = line.split(":")
        val license = line.substringAfter("$group:$name:$version:")
        "    {\"type\": \"library\", \"group\": ${json(group)}, \"name\": ${json(name)}, " +
          "\"version\": ${json(version)}, \"purl\": ${json("pkg:maven/$group/$name@$version")}, " +
          "\"licenses\": [{\"expression\": ${json(license)}}]}"
      }
    outputFile.get().asFile.writeText(
      "{\n  \"bomFormat\": \"CycloneDX\",\n  \"specVersion\": \"1.6\",\n  \"version\": 1,\n" +
        "  \"metadata\": {\"component\": {\"type\": \"library\", \"name\": ${json(componentName.get())}}},\n" +
        "  \"components\": [\n" + components.joinToString(",\n") + "\n  ]\n}\n"
    )
  }
}

/** Runs test/agent/check-shading.sh and records its output and exit code; never fails. */
abstract class PocCheckShading : DefaultTask() {
  @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val jar: RegularFileProperty

  @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val script: RegularFileProperty

  @get:Input abstract val bash: Property<String>

  @get:Input abstract val javaHome: Property<String>

  /** Allow-list instead of test/agent/unsafe-allowlist.txt (ARGOS_UNSAFE_ALLOWLIST). */
  @get:Optional @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val allowlist: RegularFileProperty

  @get:OutputFile abstract val reportFile: RegularFileProperty

  @TaskAction
  fun run() {
    val process =
      ProcessBuilder(bash.get(), script.get().asFile.invariantSeparatorsPath, jar.get().asFile.invariantSeparatorsPath)
        .redirectErrorStream(true)
        .apply {
          environment()["JAVA_HOME"] = javaHome.get()
          if (allowlist.isPresent) environment()["ARGOS_UNSAFE_ALLOWLIST"] = allowlist.get().asFile.absolutePath
        }
        .start()
    val output = process.inputStream.bufferedReader().readText()
    val exit = process.waitFor()
    reportFile.get().asFile.writeText("$output\nexit=$exit\n")
    logger.lifecycle("check-shading ${jar.get().asFile.name}: exit $exit (${reportFile.get().asFile})")
  }
}

/**
 * ADR-0005 transport P1 (PM decision pending): the Netty 4.2 comparison jars are expected to break
 * ADR-0003 C-22 with Netty's Java 11 class files. This task asserts exactly that finding: it fails
 * when the jar has no C-22 violation (Netty changed: update the ADR and the variant) and when a class
 * outside [allowedPrefix] breaks the rule. With -Ptransport.poc.strict=true the variant gets the
 * plain checkMajorVersion, which fails on the finding.
 */
abstract class PocKnownMajorViolation : DefaultTask() {
  @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val jar: RegularFileProperty

  /** Jar entry prefix of the classes the known finding covers. */
  @get:Input abstract val allowedPrefix: Property<String>

  @get:OutputFile abstract val reportFile: RegularFileProperty

  @TaskAction
  fun check() {
    val violations = ClassFiles.majorVersionViolations(jar.get().asFile)
    val unexpected = violations.filterNot { it.startsWith(allowedPrefix.get()) }
    reportFile.get().asFile.writeText(
      "# known finding ADR-0005 transport P1 (ADR-0003 C-22): ${violations.size} class file(s) " +
        "break the major version rule (own == 52, shaded <= 52)\n" +
        violations.joinToString("") { "$it\n" }
    )
    if (violations.isEmpty()) {
      throw GradleException(
        "pocCheckMajorVersion: the known C-22 violation (ADR-0005 transport P1) is gone from " +
          "${jar.get().asFile.name}; update the ADR, docs/poc/agent-poc.md 9 and the variant"
      )
    }
    if (unexpected.isNotEmpty()) {
      throw GradleException(
        "pocCheckMajorVersion: class files outside ${allowedPrefix.get()} break C-22:\n  " +
          unexpected.joinToString("\n  ")
      )
    }
    logger.lifecycle(
      "pocCheckMajorVersion: known finding ADR-0005 transport P1 in ${jar.get().asFile.name}: " +
        "${violations.size} Netty class file(s) above major 52 (${reportFile.get().asFile})"
    )
  }
}

/**
 * Reports string constants of the agent's own classes (outside io/argos/agent/shaded/) that Shadow
 * rewrote into the relocated namespace (ADR-0003 C-23 side effect: a literal such as
 * "net.bytebuddy." meant for application classes becomes "io.argos.agent.shaded.net.bytebuddy.").
 */
abstract class PocOwnRelocatedLiterals : DefaultTask() {
  @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val jar: RegularFileProperty

  @get:Input abstract val prefix: Property<String>

  @get:OutputFile abstract val reportFile: RegularFileProperty

  @TaskAction
  fun scan() {
    val relocated = prefix.get() + "."
    val lines = sortedSetOf<String>()
    for ((entry, bytes) in ClassFiles.classEntries(jar.get().asFile)) {
      if (!entry.startsWith("inst/io/argos/agent/") || ClassFiles.isShadedEntry(entry)) continue
      if (ClassFiles.majorVersion(bytes) == null) continue
      for (constant in ClassFiles.stringConstants(bytes)) {
        if (constant.startsWith(relocated)) lines += "$entry: \"$constant\""
      }
    }
    reportFile.get().asFile.writeText(
      "# own-code string constants in the relocated namespace (${lines.size})\n" + lines.joinToString("\n", postfix = "\n")
    )
  }
}

val buildJdkHome =
  javaToolchains
    .launcherFor { languageVersion = JavaLanguageVersion.of(ArgosProject.BUILD_JDK) }
    .map { it.metadata.installationPath.asFile.absolutePath }

val variantJars = mutableMapOf<String, Provider<RegularFile>>()
val variantReportTasks = mutableListOf<Any>()
// Jar + the reports that never fail (inputs of the bench); the enforcing checks only run in
// transportPocJars, so that one failing variant does not stop the measurement of the others.
val variantBenchInputs = mutableListOf<Any>()

variants.forEach { v ->
  val sourceSet = sourceSets.named(v.sourceSetName).get()
  val proto = if (v.lite) protoLite else protoFull
  val runtime = configurations.named(sourceSet.runtimeClasspathConfigurationName)
  val outDir = layout.buildDirectory.dir("poc/${v.id}")
  val jarName = "argos-agent-poc-${v.id}.jar"

  // Minimize roots (kept whole, and everything they reference is kept): the agent projects and
  // gRPC, whose providers are loaded through ServiceLoader and Class.forName (NameResolverProvider,
  // LoadBalancerProvider, ...: 19 §13 #4). Every other third-party jar is minimized.
  val projectJars =
    runtime.map { conf ->
      conf.incoming
        .artifactView {
          componentFilter { it is ProjectComponentIdentifier || (it is ModuleComponentIdentifier && it.group == "io.grpc") }
        }
        .files
    }

  val stage1 =
    tasks.register<ShadowJar>("pocShadow${v.taskSuffix}") {
      group = pocGroup
      description = "Stage 1 of the ${v.id} comparison jar (shade, relocate, minimize)."
      archiveFileName = "agent-inst.jar"
      destinationDirectory = outDir.map { it.dir("shadow") }
      from(sourceSet.output)
      from(proto.output)
      configurations.set(listOf(runtime.get()))
      dependencies { exclude(project(ArgosProject.AGENT_BOOTSTRAP_PATH)) }
      val packages = AgentShading.RELOCATED_PACKAGES + if (v.transport == "grpcOkhttp") okhttpExtraPackages else emptyList()
      packages.forEach { relocate(it, "${AgentShading.PREFIX}.$it") }
      // No java.util.logging in the agent (CLAUDE.md): the shaded libraries' JUL Logger becomes the
      // agent's JulLogger (src/sender), so the JDK LogManager is never initialised by the agent.
      relocate("java.util.logging.Logger", JUL_REPLACEMENT)
      minimize()
      apiJars.setFrom(projectJars)
      sourceSetsClassesDirs.setFrom(sourceSet.output.classesDirs, proto.output.classesDirs)
      duplicatesStrategy = DuplicatesStrategy.INCLUDE
      mergeServiceFiles()
      transform(ApacheNoticeResourceTransformer::class.java)
      transform(ApacheLicenseResourceTransformer::class.java)
      filesNotMatching(listOf("META-INF/services/**", "META-INF/NOTICE*", "META-INF/LICENSE*")) {
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
      }
      exclude(AgentShading.EXCLUDED_ENTRIES)
      exclude(AgentShading.UNSAFE_EXCLUDED_CLASSES)
      exclude(AgentShading.UNSAFE_EXCLUDED_CLASSES.map { AgentShading.PREFIX.replace('.', '/') + "/" + it })
      // Proto sources and descriptor sets of the dependencies are not needed at run time.
      exclude("**/*.proto")
      val removed =
        pocExcludedClasses[v.transport].orEmpty() +
          if (v.excludesUnsafe) {
            providers.fileContents(layout.projectDirectory.file("unsafe/${v.sourceId}.excluded.txt")).asText.get()
              .lines()
              .map { it.trim() }
              .filter { it.isNotEmpty() && !it.startsWith("#") }
          } else {
            emptyList()
          }
      exclude(removed)
      exclude(removed.map { AgentShading.PREFIX.replace('.', '/') + "/" + it })
    }

  val stage2 =
    tasks.register<Sync>("pocClassdata${v.taskSuffix}") {
      group = pocGroup
      into(outDir.map { it.dir("classdata") })
      from(zipTree(stage1.flatMap { it.archiveFile })) {
        exclude("META-INF/MANIFEST.MF")
        eachFile { if (size == 0L) exclude() }
        rename("""^(.*)\.class$""", "\$1.classdata")
        into("inst")
      }
      includeEmptyDirs = false
    }

  val root = runtime.flatMap { it.incoming.resolutionResult.rootComponent }
  val licenses =
    tasks.register<CheckLicenses>("pocCheckLicenses${v.taskSuffix}") {
      group = pocGroup
      description = "Allow-list check of what the ${v.id} jar would ship (as if it were released)."
      dependsOn("generateLicenseReport")
      reportFile = licenseDataFile
      allowedLicensesFile = layout.settingsDirectory.file("build-logic/allowed-licenses.json")
      overridesFile = layout.settingsDirectory.file("build-logic/license-overrides.json")
      roots.put(runtime.name, root)
      resolvedLicensesFile = outDir.map { it.file("licenses.properties") }
    }
  val resolvedLicenses = licenses.flatMap { it.resolvedLicensesFile }.map { CheckLicenses.readResolved(it.asFile) }
  val deps =
    tasks.register<GenerateDepsManifest>("pocDepsManifest${v.taskSuffix}") {
      runtimeRoot = root
      artifactName = jarName
      this.licenses = resolvedLicenses
      outputFile = outDir.map { it.file("meta-inf/argos-deps.txt") }
    }
  val artifacts = runtime.get().incoming.artifacts
  val notices =
    tasks.register<GenerateThirdPartyNotices>("pocThirdPartyNotices${v.taskSuffix}") {
      runtimeRoot = root
      artifactName = jarName
      this.licenses = resolvedLicenses
      noticeSources.from(artifacts.artifactFiles)
      noticeSourceKeys =
        artifacts.resolvedArtifacts.map { list ->
          list.mapNotNull { artifact ->
            val id = artifact.id.componentIdentifier as? ModuleComponentIdentifier
            id?.let { "${artifact.file.name}=${it.group}:${it.module}" }
          }
        }
      outputFile = outDir.map { it.file("meta-inf/THIRD-PARTY-NOTICES") }
    }
  val sbom =
    tasks.register<PocSbom>("pocSbom${v.taskSuffix}") {
      depsFile = deps.flatMap { it.outputFile }
      componentName = "argos-agent-poc-${v.id}"
      outputFile = outDir.map { it.file("meta-inf/agent.cdx.json") }
    }

  val stage3 =
    tasks.register<Jar>("pocAgentJar${v.taskSuffix}") {
      group = pocGroup
      description = "Stage 3: the ${v.id} comparison agent jar."
      archiveFileName = jarName
      destinationDirectory = outDir
      val license = layout.settingsDirectory.file("LICENSE").asFile
      val notice = layout.settingsDirectory.file("NOTICE").asFile
      inputs.files(agentBootstrapJar)
      from(zipTree(agentBootstrapJar.map { it.singleFile })) { exclude("META-INF/**") }
      from(stage2)
      into("META-INF") {
        from(license, notice)
        from(notices, deps)
        into("sbom") { from(sbom) }
      }
      manifest {
        attributes(
          linkedMapOf(
            "Premain-Class" to ArgosProject.AGENT_PREMAIN_CLASS,
            "Agent-Class" to ArgosProject.AGENT_PREMAIN_CLASS,
            "Main-Class" to ArgosProject.AGENT_PREMAIN_CLASS,
            "Can-Retransform-Classes" to "true",
            "Can-Redefine-Classes" to "false",
            "Multi-Release" to "false",
            "Implementation-Title" to "${ArgosProject.PRODUCT_NAME} Java agent (P-3 ${v.id}, not for release)",
            "Implementation-Version" to version.toString(),
            "Implementation-Vendor" to ArgosProject.COPYRIGHT_HOLDER,
            "Argos-Protocol-Version" to argosProtocolVersion(),
            "Argos-Commit" to argosGitCommit(),
          )
        )
      }
    }
  val jarFile = stage3.flatMap { it.archiveFile }
  variantJars[v.id] = jarFile

  val shading =
    tasks.register<PocCheckShading>("pocCheckShading${v.taskSuffix}") {
      group = pocGroup
      jar = jarFile
      script = layout.settingsDirectory.file("test/agent/check-shading.sh")
      bash = argosBash()
      javaHome = buildJdkHome
      if (v.excludesUnsafe) allowlist = layout.projectDirectory.file("unsafe/${v.id}.allowlist.txt")
      reportFile = outDir.map { it.file("reports/check-shading.txt") }
    }
  val strings =
    tasks.register<CheckRelocatedStrings>("pocCheckRelocatedStrings${v.taskSuffix}") {
      group = pocGroup
      jar = jarFile
      prefix = AgentShading.PREFIX
      propertyNames = transportPropertyNames
      policy = AgentShading.PROPERTY_STRING_POLICY
      reportFile = outDir.map { it.file("reports/relocated-strings.txt") }
    }
  val major =
    if (v.knownC22Violation && !strictPoc) {
      tasks.register<PocKnownMajorViolation>("pocCheckMajorVersion${v.taskSuffix}") {
        group = pocGroup
        jar = jarFile
        allowedPrefix = ClassFiles.SHADED_ENTRY_PREFIX + "io/netty/"
        reportFile = outDir.map { it.file("reports/major-version.txt") }
      }
    } else {
      tasks.register<CheckMajorVersion>("pocCheckMajorVersion${v.taskSuffix}") {
        group = pocGroup
        jar = jarFile
        resultFile = outDir.map { it.file("reports/major-version.txt") }
      }
    }
  val literals =
    tasks.register<PocOwnRelocatedLiterals>("pocOwnRelocatedLiterals${v.taskSuffix}") {
      group = pocGroup
      jar = jarFile
      prefix = AgentShading.PREFIX
      reportFile = outDir.map { it.file("reports/own-relocated-literals.txt") }
    }
  variantReportTasks.addAll(listOf(shading, strings, major, literals))
  variantBenchInputs.addAll(listOf(stage3, shading, literals))
}

// check-shading of the release jar without transport, for the bench's "base" row.
val baseShading =
  tasks.register<PocCheckShading>("pocCheckShadingBase") {
    group = pocGroup
    dependsOn(baseAgentJar)
    jar = layout.file(baseAgentJar.map { it.singleFile })
    script = layout.settingsDirectory.file("test/agent/check-shading.sh")
    bash = argosBash()
    javaHome = buildJdkHome
    reportFile = layout.buildDirectory.file("poc/base/reports/check-shading.txt")
  }
variantReportTasks.add(baseShading)
variantBenchInputs.add(baseShading)

val transportPocJars =
  tasks.register("transportPocJars") {
    group = pocGroup
    description = "Builds every P-3 comparison agent jar with its reports (build/poc/<variant>/)."
    dependsOn(variantReportTasks)
  }

// --- Tests -----------------------------------------------------------------------------------

testing {
  suites {
    listOf("protoLiteTest" to protoLite, "protoFullTest" to protoFull).forEach { (name, proto) ->
      register<JvmTestSuite>(name) {
        sources { java { setSrcDirs(listOf("src/protoTest/java")) } }
        dependencies {
          implementation(proto.output)
          implementation(if (proto == protoLite) libs.protobuf.javalite else libs.protobuf.java)
        }
      }
    }
  }
}

val jdkHome = { version: Int ->
  javaToolchains
    .launcherFor { languageVersion = JavaLanguageVersion.of(version) }
    .map { it.metadata.installationPath.asFile.absolutePath }
}

/** Arguments shared by the smoke and bench runs of the bench runner. */
fun JavaExec.benchArguments(mode: String, outputDirectory: Provider<Directory>, jdks: List<Int>) {
  group = pocGroup
  classpath = bench.runtimeClasspath
  mainClass = "io.argos.agent.transportpoc.bench.TransportBench"
  javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(ArgosProject.BUILD_JDK) }
  val jars = variantJars.toMap()
  jars.values.forEach { inputs.file(it).withPathSensitivity(PathSensitivity.NONE) }
  inputs.files(baseAgentJar).withPathSensitivity(PathSensitivity.NONE)
  val hostClasses = host.output.classesDirs
  val collectorClasspath = collector.runtimeClasspath
  inputs.files(hostClasses, collectorClasspath)
  val homes = jdks.associateWith { jdkHome(it) }
  val old8 =
    mapOf(
      "8u242" to providers.gradleProperty("transport.poc.jdk8u242"),
      "8u252" to providers.gradleProperty("transport.poc.jdk8u252"),
    )
  val repetitions = providers.gradleProperty("transport.poc.repetitions").orElse(if (mode == "bench") "5" else "1")
  val only = providers.gradleProperty("transport.poc.only").orElse("")
  val base = baseAgentJar
  argumentProviders.add(
    CommandLineArgumentProvider {
      buildList {
        add("--mode=$mode")
        add("--out=" + outputDirectory.get().asFile.absolutePath)
        add("--repetitions=" + repetitions.get())
        if (only.get().isNotEmpty()) add("--only=" + only.get())
        add("--base-agent=" + base.get().singleFile.absolutePath)
        add("--host-classes=" + hostClasses.asPath)
        add("--collector-classpath=" + collectorClasspath.asPath)
        jars.forEach { (id, jar) -> add("--variant=$id=" + jar.get().asFile.absolutePath) }
        homes.forEach { (version, home) -> add("--jdk=$version=" + home.get()) }
        old8.forEach { (name, home) -> if (home.isPresent) add("--jdk=$name=" + home.get()) }
      }
    }
  )
}

val transportPocSmoke =
  tasks.register<JavaExec>("transportPocSmoke") {
    description = "Functional smoke: every comparison jar delivers batches to the fake collector."
    benchArguments("smoke", layout.buildDirectory.dir("poc/smoke"), listOf(8, 21))
    // ADR-0005 transport P3 (see the header): reported as a known finding, unless strict.
    val knownAfUnix = if (strictPoc) "" else variants.filter { it.knownAfUnixTemp }.joinToString(",") { it.id }
    argumentProviders.add(CommandLineArgumentProvider { listOf("--known-af-unix-temp=$knownAfUnix") })
    dependsOn(variantBenchInputs)
  }

tasks.register("transportPocTest") {
  group = pocGroup
  description = "P-3 tests: javalite/full map and optional (11 §15 #2) and the functional smoke."
  dependsOn("protoLiteTest", "protoFullTest", transportPocSmoke)
}

// The runner writes into a new <timestamp> directory below build/poc/bench on every run.
tasks.register<JavaExec>("transportPocBench") {
  description = "P-3 measurement matrix (sizes, metaspace, startup, direct memory, threads, TLS, JDK 25)."
  benchArguments("bench", layout.buildDirectory.dir("poc/bench"), listOf(8, 11, 17, 21, 25))
  dependsOn(variantBenchInputs)
}
