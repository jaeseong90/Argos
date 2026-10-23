// SPDX-License-Identifier: Apache-2.0
//
// argos.publishing: Maven Central publication skeleton (19 §3.2, §7.5).
// Publisher: com.vanniktech.maven.publish (base plugin) on top of maven-publish. Decided in WO-00:
// Gradle-native publications, Sonatype Central Portal upload and in-memory GPG signing from CI
// secrets, without taking over the GitHub release, checksum, cosign and attestation steps that
// release.yml owns (DSN-19-08, DSN-19-09).
//
// The project creates its own publication(s) and sets coordinates (agent-dist publishes
// io.github.argos-apm:argos-agent). Nothing is published by the build itself; Maven Central
// upload happens only in release.yml (WO-44), never from a developer machine.

import io.argos.buildlogic.ArgosProject

plugins {
  `maven-publish`
  id("com.vanniktech.maven.publish.base")
}

mavenPublishing {
  publishToMavenCentral(automaticRelease = false)
  // Signing keys exist only in the release environment (19 §7.4). Without them nothing is signed,
  // which is fine for local and staging-repository publications.
  if (providers.gradleProperty("signingInMemoryKey").isPresent) {
    signAllPublications()
  }
  pom {
    inceptionYear = ArgosProject.INCEPTION_YEAR
    url = ArgosProject.REPOSITORY_URL
    licenses {
      license {
        name = "Apache-2.0"
        url = "https://www.apache.org/licenses/LICENSE-2.0.txt"
        distribution = "repo"
      }
    }
    developers {
      developer {
        id = "argos-apm"
        name = ArgosProject.COPYRIGHT_HOLDER
        url = ArgosProject.GITHUB_ORG_URL
      }
    }
    scm {
      url = ArgosProject.REPOSITORY_URL
      connection = "scm:git:${ArgosProject.REPOSITORY_URL}.git"
      developerConnection = "scm:git:ssh://git@github.com/argos-apm/argos.git"
    }
    issueManagement {
      system = "GitHub"
      url = "${ArgosProject.REPOSITORY_URL}/issues"
    }
  }
}

publishing {
  repositories {
    // File repository for inspecting what would be published:
    //   ./gradlew :agent:agent-dist:publishAllPublicationsToLocalStagingRepository
    maven {
      name = "localStaging"
      url = uri(layout.settingsDirectory.dir("build/staging-repo"))
    }
  }
}
