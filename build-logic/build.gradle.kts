// SPDX-License-Identifier: Apache-2.0

plugins { `kotlin-dsl` }

// STRICT: a configuration without lock state fails to resolve instead of resolving unchecked.
dependencyLocking {
  lockAllConfigurations()
  lockMode = LockMode.STRICT
}

/** Maps a version-catalog plugin entry to its plugin marker artifact. */
fun pluginMarker(plugin: Provider<PluginDependency>): Provider<String> =
  plugin.map { "${it.pluginId}:${it.pluginId}.gradle.plugin:${it.version.requiredVersion}" }

dependencies {
  implementation(pluginMarker(libs.plugins.spotless))
  implementation(pluginMarker(libs.plugins.errorprone))
  implementation(pluginMarker(libs.plugins.shadow))
  implementation(pluginMarker(libs.plugins.cyclonedx))
  implementation(pluginMarker(libs.plugins.license.report))
  implementation(pluginMarker(libs.plugins.protobuf))
  implementation(pluginMarker(libs.plugins.maven.publish))
  // argos.java-server (WO-20): boot jar and server image.
  implementation(pluginMarker(libs.plugins.spring.boot))
  implementation(pluginMarker(libs.plugins.jib))
}

// Tests of the build logic itself (part of the root `check`): license policy and checks (TestKit),
// agent jar checks, and the repository scripts test/agent/check-shading.sh and
// test/lint/check-spdx.sh. Test IDs follow 18 §3 (T-BLD-nnn in display names, req: tags).
val repositoryRoot = layout.projectDirectory.dir("..")

testing {
  suites {
    named<JvmTestSuite>("test") {
      useJUnitJupiter(libs.versions.junit)
      dependencies {
        implementation(gradleTestKit())
        implementation(libs.assertj.core)
      }
      targets.configureEach {
        testTask.configure {
          val root = repositoryRoot.asFile
          systemProperty("argos.repositoryRoot", root.absolutePath)
          // Inputs the tests read from the repository (rerun the tests when they change).
          inputs
            .files(
              repositoryRoot.file("test/agent/check-shading.sh"),
              repositoryRoot.file("test/agent/unsafe-allowlist.txt"),
              repositoryRoot.file("test/lint/check-spdx.sh"),
              repositoryRoot.file("build-logic/allowed-licenses.json"),
              repositoryRoot.file("build-logic/license-overrides.json"),
              repositoryRoot.file("docs/licenses/dev-only.md"),
            )
            .withPathSensitivity(PathSensitivity.RELATIVE)
            .withPropertyName("repositoryFiles")
          testLogging {
            events("failed", "skipped")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
          }
        }
      }
    }
  }
}
