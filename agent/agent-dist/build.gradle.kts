// SPDX-License-Identifier: Apache-2.0
//
// Agent single jar assembly (DSN-19-03, 19 §3.3, 10 §4.1):
//   1. shadowJar     agent-core + instrumentation modules + their runtime dependencies, external
//                    packages relocated below io.argos.agent.shaded -> build/shadow/agent-inst.jar
//   2. classdataJar  *.class -> inst/**/*.classdata (AgentClassLoader contract) -> build/classdata
//   3. agentJar      agent-bootstrap classes at the root + inst/ + META-INF (LICENSE, NOTICE,
//                    THIRD-PARTY-NOTICES, argos-deps.txt, sbom/agent.cdx.json) + manifest
//                    -> build/libs/argos-agent-<version>.jar
//   4-5. generateDepsManifest, generateThirdPartyNotices, cyclonedxDirectBom feed step 3.
//   6-8. checkShading, checkRelocatedStrings, checkMajorVersion (and checkModuleIndex, C-25,
//        checkOwnRelocatedLiterals, C-23) run from verifyAgentJar, which is part of check and
//        finalizes agentJar.
// No temporary files are extracted at runtime; nothing here may add Add-Opens,
// Launcher-Agent-Class or Boot-Class-Path to the manifest (DSN-02-03).

import com.github.jengelman.gradle.plugins.shadow.ShadowExtension
import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import com.github.jengelman.gradle.plugins.shadow.transformers.ApacheLicenseResourceTransformer
import com.github.jengelman.gradle.plugins.shadow.transformers.ApacheNoticeResourceTransformer
import io.argos.buildlogic.AgentShading
import io.argos.buildlogic.ArgosProject
import io.argos.buildlogic.CheckLicenses
import io.argos.buildlogic.CheckMajorVersion
import io.argos.buildlogic.CheckModuleIndex
import io.argos.buildlogic.CheckOwnRelocatedLiterals
import io.argos.buildlogic.CheckRelocatedStrings
import io.argos.buildlogic.GenerateDepsManifest
import io.argos.buildlogic.GenerateThirdPartyNotices
import io.argos.buildlogic.NormalizeSbom
import io.argos.buildlogic.argosBash
import io.argos.buildlogic.argosGitCommit
import io.argos.buildlogic.argosProtocolVersion
import io.argos.buildlogic.argosSourceDateInstant
import org.cyclonedx.gradle.CyclonedxDirectTask
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.cyclonedx.model.Component
import org.cyclonedx.model.ExternalReference

plugins {
  id("argos.java-agent")
  id("argos.publishing")
}

// Shadow and CycloneDX come with the build-logic classpath; plugin ids without a version are not
// resolvable from an included build, so they are applied here instead of in the plugins block.
apply(plugin = "com.gradleup.shadow")

apply(plugin = "org.cyclonedx.bom")

val shadowJar = tasks.named<ShadowJar>("shadowJar")

description = "Argos APM Java agent (single -javaagent jar)."

val agentFileName = "${ArgosProject.AGENT_ARTIFACT_ID}-$version.jar"

// Every instrumentation module registered in settings.gradle.kts (single list).
val instrumentationPaths =
  rootProject.project(":agent:agent-instrumentation").childProjects.values.map { it.path }.sorted()

dependencies {
  // Everything below inst/. Declared as api so that Shadow's minimize() treats these modules as
  // entry points and only trims their third-party dependencies.
  api(project(":agent:agent-core"))
  instrumentationPaths.forEach { api(project(it)) }
}

// agent-bootstrap goes to the jar root (bootstrap class loader), never below inst/.
val agentBootstrap = configurations.dependencyScope("agentBootstrap")
val agentBootstrapJar =
  configurations.resolvable("agentBootstrapJar") {
    extendsFrom(agentBootstrap.get())
    isTransitive = false
    attributes {
      attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
      attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
      attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
      attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.EXTERNAL))
    }
  }

dependencies { agentBootstrap(project(ArgosProject.AGENT_BOOTSTRAP_PATH)) }

// The java-library jar of this project would be an empty, misleading artifact.
tasks.jar { enabled = false }

extensions.configure<ShadowExtension> { addShadowVariantIntoJavaComponent = false }

// Stage 1.
shadowJar {
  description = "Stage 1: shades agent-core, instrumentation and dependencies (agent-inst.jar)."
  archiveFileName = "agent-inst.jar"
  destinationDirectory = layout.buildDirectory.dir("shadow")
  dependencies { exclude(project(ArgosProject.AGENT_BOOTSTRAP_PATH)) }
  AgentShading.RELOCATED_PACKAGES.forEach { relocate(it, "${AgentShading.PREFIX}.$it") }
  // Reflection/ServiceLoader-loaded classes (gRPC NameResolverProvider, Netty channels) get
  // minimize exclusions together with the transport stack in WO-02 (19 §13 #4).
  minimize()
  // Let the transformers see every copy of the merged resources; other duplicates keep the first.
  duplicatesStrategy = DuplicatesStrategy.INCLUDE
  mergeServiceFiles()
  transform(ApacheNoticeResourceTransformer::class.java)
  transform(ApacheLicenseResourceTransformer::class.java)
  filesNotMatching(listOf("META-INF/services/**", "META-INF/NOTICE*", "META-INF/LICENSE*")) {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
  }
  exclude(AgentShading.EXCLUDED_ENTRIES)
  // sun.misc.Unsafe users of shaded libraries are removed, not allow-listed (ADR-0003, F-A35).
  // Both the original and the relocated path, so the rule holds whichever form Shadow filters on.
  exclude(AgentShading.UNSAFE_EXCLUDED_CLASSES)
  exclude(AgentShading.UNSAFE_EXCLUDED_CLASSES.map { AgentShading.PREFIX.replace('.', '/') + "/" + it })
}

// Stage 2.
val classdataJar =
  tasks.register<Sync>("classdataJar") {
    description = "Stage 2: renames shaded classes to inst/**/*.classdata (directory output)."
    group = "build"
    into(layout.buildDirectory.dir("classdata"))
    from(zipTree(shadowJar.flatMap { it.archiveFile })) {
      exclude("META-INF/MANIFEST.MF")
      // The notice transformer always writes META-INF/NOTICE, empty when nothing was merged.
      eachFile { if (size == 0L) exclude() }
      rename("""^(.*)\.class$""", "\$1.classdata")
      into("inst")
    }
    includeEmptyDirs = false
  }

// Step 4 (SEC-77) and THIRD-PARTY-NOTICES (O-L04), from the graph that is shaded in stage 1.
// Licenses come from checkLicenses (same report, 19 §5.5), so the jar cannot be assembled with a
// dependency outside the allow-list.
val agentRuntimeRoot = configurations.runtimeClasspath.flatMap { it.incoming.resolutionResult.rootComponent }

val shippedLicenses =
  tasks
    .named<CheckLicenses>("checkLicenses")
    .flatMap { it.resolvedLicensesFile }
    .map { CheckLicenses.readResolved(it.asFile) }

val generateDepsManifest =
  tasks.register<GenerateDepsManifest>("generateDepsManifest") {
    runtimeRoot = agentRuntimeRoot
    artifactName = agentFileName
    licenses = shippedLicenses
    outputFile = layout.buildDirectory.file("agent-meta-inf/argos-deps.txt")
  }

// Jars of the bundled third-party modules: their original NOTICE files go into
// THIRD-PARTY-NOTICES (O-L04, Apache-2.0 4(d)).
val bundledArtifacts = configurations.runtimeClasspath.get().incoming.artifacts

val generateThirdPartyNotices =
  tasks.register<GenerateThirdPartyNotices>("generateThirdPartyNotices") {
    runtimeRoot = agentRuntimeRoot
    artifactName = agentFileName
    licenses = shippedLicenses
    noticeSources.from(bundledArtifacts.artifactFiles)
    noticeSourceKeys =
      bundledArtifacts.resolvedArtifacts.map { artifacts ->
        artifacts.mapNotNull { artifact ->
          val id = artifact.id.componentIdentifier as? ModuleComponentIdentifier
          id?.let { "${artifact.file.name}=${it.group}:${it.module}" }
        }
      }
    outputFile = layout.buildDirectory.file("agent-meta-inf/THIRD-PARTY-NOTICES")
  }

// Step 5 (SEC-70): conservative SBOM of everything that may be shaded, before relocation, plus the
// bootstrap classes that go to the jar root unshaded.
tasks.named<CyclonedxDirectTask>("cyclonedxDirectBom") {
  includeConfigs = listOf("runtimeClasspath", agentBootstrapJar.name)
  testConfigs = emptyList()
  projectType = Component.Type.LIBRARY
  componentGroup = ArgosProject.MAVEN_GROUP
  componentName = ArgosProject.AGENT_ARTIFACT_ID
  componentDescription = "Argos APM Java agent"
  // Reproducible output (DSN-19-05) and no build-environment leakage.
  includeBomSerialNumber = false
  includeBuildSystem = false
  includeLicenseText = false
  externalReferences =
    listOf(
      ExternalReference().apply {
        type = ExternalReference.Type.VCS
        url = ArgosProject.REPOSITORY_URL
      }
    )
  jsonOutput = layout.buildDirectory.file("sbom/raw/agent.cdx.json")
  xmlOutput.convention(null as RegularFile?)
}

// The CycloneDX plugin always writes the current time; pin it (DSN-19-05, T-BLD-009). Every Argos
// module whose classes the jar ships (bootstrap at the root, the rest below inst/) must be a
// component of the SBOM, otherwise the task fails (T-BLD-008).
val agentSbom =
  tasks.register<NormalizeSbom>("agentSbom") {
    inputFile = tasks.named<CyclonedxDirectTask>("cyclonedxDirectBom").flatMap { it.jsonOutput }
    timestamp = argosSourceDateInstant()
    requiredProjects =
      listOf(ArgosProject.AGENT_BOOTSTRAP_PATH, ":agent:agent-core") + instrumentationPaths
    outputFile = layout.buildDirectory.file("sbom/agent.cdx.json")
  }

// Stage 3.
val agentJar =
  tasks.register<Jar>("agentJar") {
    description = "Stage 3: assembles the single agent jar (argos-agent-<version>.jar)."
    group = "build"
    archiveFileName = agentFileName
    destinationDirectory = layout.buildDirectory.dir("libs")

    val license = layout.settingsDirectory.file("LICENSE").asFile
    val notice = layout.settingsDirectory.file("NOTICE").asFile

    // Root: bootstrap classes only.
    inputs.files(agentBootstrapJar)
    from(zipTree(agentBootstrapJar.map { it.singleFile })) { exclude("META-INF/**") }
    // inst/**: everything AgentClassLoader loads.
    from(classdataJar)
    into("META-INF") {
      from(license, notice)
      from(generateThirdPartyNotices, generateDepsManifest)
      into("sbom") { from(agentSbom) }
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
          "Implementation-Title" to "${ArgosProject.PRODUCT_NAME} Java agent",
          "Implementation-Version" to version.toString(),
          "Implementation-Vendor" to ArgosProject.COPYRIGHT_HOLDER,
          "Argos-Protocol-Version" to argosProtocolVersion(),
          "Argos-Commit" to argosGitCommit(),
        )
      )
    }

    doFirst {
      // LICENSE and NOTICE are mandatory in every shipped artifact (O-L01, O-L04).
      listOf(license, notice).forEach {
        if (!it.isFile) throw GradleException("Missing $it: required in META-INF of the agent jar")
      }
    }
  }

// Steps 6-8 (19 §3.3, §5.6).
val agentJarFile = agentJar.flatMap { it.archiveFile }

// Step 6, T-BLD-001..004 and T-BLD-006: test/agent/check-shading.sh (also run by CI directly).
val buildJdkHome =
  javaToolchains
    .launcherFor { languageVersion = JavaLanguageVersion.of(ArgosProject.BUILD_JDK) }
    .map { it.metadata.installationPath.asFile.absolutePath }

val checkShading =
  tasks.register<Exec>("checkShading") {
    group = "verification"
    description = "Runs test/agent/check-shading.sh on the agent jar (19 §5.6)."
    // Local copies only: lambdas below are serialized by the configuration cache.
    val script = layout.settingsDirectory.file("test/agent/check-shading.sh").asFile
    val allowlist = layout.settingsDirectory.file("test/agent/unsafe-allowlist.txt").asFile
    val jar = agentJarFile
    val jdkHome = buildJdkHome
    val marker = layout.buildDirectory.file("reports/agent-jar/check-shading.ok")
    inputs.file(jar).withPathSensitivity(PathSensitivity.NONE)
    inputs.files(script, allowlist).withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.file(marker)
    // Git Bash on Windows (the WSL bash launcher cannot read Windows paths), bash elsewhere.
    executable = argosBash().get()
    argumentProviders.add(
      CommandLineArgumentProvider {
        listOf(script.invariantSeparatorsPath, jar.get().asFile.invariantSeparatorsPath)
      }
    )
    doFirst { environment("JAVA_HOME", jdkHome.get()) }
    doLast { marker.get().asFile.writeText("check-shading.sh passed\n") }
  }

// Step 7, T-BLD-005: policy RELOCATED (ADR-0003 C-23, decided in WO-02, ADR-0008).
val checkRelocatedStrings =
  tasks.register<CheckRelocatedStrings>("checkRelocatedStrings") {
    jar = agentJarFile
    prefix = AgentShading.PREFIX
    propertyNames = AgentShading.PROPERTY_STRINGS
    policy = AgentShading.PROPERTY_STRING_POLICY
    reportFile = layout.buildDirectory.file("reports/agent-jar/relocated-strings.txt")
  }

// Step 8, T-BLD-001.
val checkMajorVersion =
  tasks.register<CheckMajorVersion>("checkMajorVersion") {
    jar = agentJarFile
    resultFile = layout.buildDirectory.file("reports/agent-jar/major-version.txt")
  }

// ADR-0003 C-25: the module index the core reads at runtime lists only classes of the jar.
val checkModuleIndex =
  tasks.register<CheckModuleIndex>("checkModuleIndex") {
    jar = agentJarFile
    reportFile = layout.buildDirectory.file("reports/agent-jar/modules.txt")
  }

// ADR-0003 C-23 side effect (ADR-0005 transport): Shadow also relocates string literals of the
// agent's own classes (IgnoreMatchers' "net.bytebuddy." became "io.argos.agent.shaded.net.bytebuddy.").
// Own classes may hold relocated-namespace constants only when listed here as "<entry>: <constant>".
val checkOwnRelocatedLiterals =
  tasks.register<CheckOwnRelocatedLiterals>("checkOwnRelocatedLiterals") {
    jar = agentJarFile
    prefix = AgentShading.PREFIX
    reportFile = layout.buildDirectory.file("reports/agent-jar/own-relocated-literals.txt")
  }

val verifyAgentJar =
  tasks.register("verifyAgentJar") {
    group = "verification"
    description =
      "Verifies the agent jar: shading, relocated strings (library and own), major version, module index (18 §2.2)."
    dependsOn(checkShading, checkRelocatedStrings, checkOwnRelocatedLiterals, checkMajorVersion, checkModuleIndex)
  }

agentJar { finalizedBy(verifyAgentJar) }

tasks.assemble { dependsOn(agentJar) }

// The agent jar for other projects (agent-testing attach and integration tests):
//   dependencies { x(project(path = ":agent:agent-dist", configuration = "agentJarElements")) }
configurations.consumable("agentJarElements") {
  attributes {
    attribute(Category.CATEGORY_ATTRIBUTE, objects.named("argos-agent-jar"))
  }
  outgoing.artifact(agentJar)
}

// Stage 1 output (relocated classes as .class): agent-testing compiles its test-only probe module
// against the relocated ByteBuddy/api names, so the probe needs no relocation step of its own.
configurations.consumable("agentInstElements") {
  attributes {
    attribute(Category.CATEGORY_ATTRIBUTE, objects.named("argos-agent-inst-jar"))
  }
  outgoing.artifact(shadowJar)
}

tasks.check { dependsOn(verifyAgentJar) }

// Maven Central publication (19 §7.5): the single jar plus sources (bootstrap + core) and a
// javadoc jar. Bootstrap API javadoc replaces the empty javadoc jar once the API exists (WO-10).
val agentSourcesJar =
  tasks.register<Jar>("agentSourcesJar") {
    description = "Sources jar of the published agent (agent-bootstrap and agent-core)."
    archiveFileName = "${ArgosProject.AGENT_ARTIFACT_ID}-$version-sources.jar"
    destinationDirectory = layout.buildDirectory.dir("libs")
    from(layout.settingsDirectory.dir("agent/agent-bootstrap/src/main/java"))
    from(layout.settingsDirectory.dir("agent/agent-core/src/main/java"))
  }

val agentJavadocJar =
  tasks.register<Jar>("agentJavadocJar") {
    description = "Javadoc jar of the published agent."
    archiveFileName = "${ArgosProject.AGENT_ARTIFACT_ID}-$version-javadoc.jar"
    destinationDirectory = layout.buildDirectory.dir("libs")
  }

publishing {
  publications {
    register<MavenPublication>("agent") {
      artifact(agentJar)
      artifact(agentSourcesJar) { classifier = "sources" }
      artifact(agentJavadocJar) { classifier = "javadoc" }
    }
  }
}

mavenPublishing {
  coordinates(ArgosProject.MAVEN_GROUP, ArgosProject.AGENT_ARTIFACT_ID, version.toString())
  pom {
    name = "${ArgosProject.PRODUCT_NAME} Java agent"
    description = "Bytecode agent of ${ArgosProject.PRODUCT_NAME} for Java 8+ applications."
  }
}
