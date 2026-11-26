// SPDX-License-Identifier: Apache-2.0
//
// argos.demo-image: the public demo images of the demo profile (17 §3.2, §3.9, WO-41), built by Jib
// like the server image (19 §3.5): argos-demo-app (sample-apps/demo-app + the Argos agent) and
// argos-demo-load (sample-apps/load). Apply after argos.sample-app; configure with demoImage { }
// (DemoImageExtension, defaults in DemoImage).
//   Base, user, labels  the server's digest-pinned Temurin 21 JRE, uid:gid 10001:10001 and OCI label
//                       set (ServerImage), with the image's own title and description
//   Layout              classes and libraries in /opt/argos-demo/app, LICENSE and NOTICE in
//                       /opt/argos-demo (root-owned, read-only); the demo app also carries
//                       :agent:agent-dist:agentJar at /opt/argos-agent/argos-agent.jar and starts
//                       with -javaagent (DemoImage.APP_JVM_FLAGS)
//   Notices (WO-44)     every THIRD-PARTY-NOTICES and argos-deps.txt the project generates
//                       (registerBundledNotices, the demo app) go to /opt/argos-demo as well;
//                       checkDemoImageFiles (T-BLD-003b, O-L04) checks the staged files and runs
//                       before every Jib task
//   Tasks               jibDockerBuild (local daemon, tag = project version), jibBuildTar, jib.
//                       The release workflow (WO-44, release.yml) selects the platforms with
//                       -Pargos.image.platforms=linux/amd64,linux/arm64 and the registry image with
//                       Jib's -Djib.to.image / -Djib.to.tags; nothing here names a registry.
// Not part of check or build. The Jib tasks store no configuration cache entry (as the server's).

import io.argos.buildlogic.ArgosProject
import io.argos.buildlogic.GenerateDepsManifest
import io.argos.buildlogic.GenerateThirdPartyNotices
import io.argos.buildlogic.argosGitCommit
import io.argos.buildlogic.demo.DemoImage
import io.argos.buildlogic.demo.DemoImageExtension
import io.argos.buildlogic.release.notices.VerifyImageNotices
import io.argos.buildlogic.sample.SampleAppExtension
import io.argos.buildlogic.server.ServerImage

plugins { id("com.google.cloud.tools.jib") }

val demoImage =
  extensions.create<DemoImageExtension>("demoImage").apply {
    jvmFlags.convention(emptyList())
    environment.convention(emptyMap())
    ports.convention(emptyList())
    withAgent.convention(false)
  }

val sourceDateEpoch = providers.environmentVariable("SOURCE_DATE_EPOCH").orNull
val imageCreationTime = ServerImage.creationTime(sourceDateEpoch)
val imagePlatforms =
  ServerImage.platforms(providers.gradleProperty("argos.image.platforms").getOrElse(ServerImage.DEFAULT_PLATFORMS))

// Files the image adds besides the application (extraDirectories): LICENSE and NOTICE of the Argos
// code (O-L01, O-L04) and, for the demo app, the agent jar under a version-free name.
val imageFiles = layout.buildDirectory.dir("jib/demo-image-files")
val license = layout.settingsDirectory.file("LICENSE")
val notice = layout.settingsDirectory.file("NOTICE")

val prepareDemoImageFiles =
  tasks.register<Sync>("prepareDemoImageFiles") {
    group = "argos"
    description = "Stages the extra files of the demo image (LICENSE, NOTICE, agent jar)."
    into(imageFiles)
    from(files(license, notice)) { into(DemoImage.WORKING_DIRECTORY.removePrefix("/")) }
  }

// T-BLD-003b: LICENSE, NOTICE and, for an image with libraries, argos-deps.txt and a complete
// THIRD-PARTY-NOTICES in /opt/argos-demo of the staged files.
val checkDemoImageFiles =
  tasks.register<VerifyImageNotices>("checkDemoImageFiles") {
    imageFiles = layout.dir(prepareDemoImageFiles.map { it.destinationDir })
    directory = DemoImage.WORKING_DIRECTORY.removePrefix("/") + "/"
    reportFile = layout.buildDirectory.file("reports/demo-image/notices.txt")
  }

afterEvaluate {
  // Notices of the bundled libraries (WO-44, O-L04, ADR-0010 C-186 (1)), from the generator tasks
  // the project registers (registerBundledNotices).
  val generated =
    (tasks.withType<GenerateThirdPartyNotices>().names + tasks.withType<GenerateDepsManifest>().names).sorted()
  if (generated.isNotEmpty()) {
    prepareDemoImageFiles.configure {
      from(generated.map { tasks.named(it) }) { into(DemoImage.WORKING_DIRECTORY.removePrefix("/")) }
    }
  }
  checkDemoImageFiles.configure {
    dependsOn(prepareDemoImageFiles)
    thirdParty = generated.isNotEmpty()
  }

  val sampleApp = extensions.getByType<SampleAppExtension>()
  val mainClassName =
    sampleApp.mainClass.orNull
      ?: throw GradleException("$path: argos.demo-image needs sampleApp.bootJar(...) or executableJar(...)")
  val name = demoImage.imageName.get()

  if (demoImage.withAgent.get()) {
    val agentScope = configurations.dependencyScope("demoAgent")
    val agentJar =
      configurations.resolvable("demoAgentJar") {
        extendsFrom(agentScope.get())
        attributes { attribute(Category.CATEGORY_ATTRIBUTE, objects.named("argos-agent-jar")) }
      }
    dependencies { add(agentScope.name, project(":agent:agent-dist")) }
    prepareDemoImageFiles.configure {
      from(agentJar) {
        into(DemoImage.AGENT_DIRECTORY.removePrefix("/"))
        rename { DemoImage.AGENT_JAR.substringAfterLast('/') }
      }
    }
  }

  val labels =
    argosGitCommit().map { commit ->
      DemoImage.labels(
        demoImage.title.get(),
        demoImage.description.get(),
        project.version.toString(),
        commit,
        ArgosProject.REPOSITORY_URL,
        imageCreationTime,
      )
    }

  jib {
    from {
      image = DemoImage.BASE_IMAGE
      platforms {
        imagePlatforms.forEach { (os, architecture) ->
          platform {
            this.os = os
            this.architecture = architecture
          }
        }
      }
    }
    // Local name; the release workflow overrides it with -Djib.to.image/-Djib.to.tags (WO-44).
    to { image = "$name:${project.version}" }
    container {
      mainClass = mainClassName
      user = DemoImage.USER
      ports = demoImage.ports.get()
      workingDirectory = DemoImage.WORKING_DIRECTORY
      appRoot = DemoImage.APP_ROOT
      environment = demoImage.environment.get()
      jvmFlags = demoImage.jvmFlags.get()
      creationTime = imageCreationTime
      filesModificationTime = if (imageCreationTime == "EPOCH") "EPOCH_PLUS_SECOND" else imageCreationTime
      this.labels = labels
    }
    extraDirectories {
      paths {
        path {
          setFrom(imageFiles.get().asFile)
          into = "/"
        }
      }
    }
  }
}

// Jib 3.5.4 reads the Project model at execution time (as in argos.java-server).
tasks.named { it.startsWith("jib") }.configureEach {
  notCompatibleWithConfigurationCache("Jib tasks use the Project model at execution time")
  dependsOn(prepareDemoImageFiles, checkDemoImageFiles)
}
