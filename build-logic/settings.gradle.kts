// SPDX-License-Identifier: Apache-2.0
//
// Included build that provides the Argos convention plugins (DSN-19-01, 19 §3.2).

dependencyResolutionManagement {
  repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
  // Convention plugins depend on Gradle plugins, which are published to the Plugin Portal.
  repositories { gradlePluginPortal() }
  // Reuse the root version catalog so plugin versions have a single source.
  versionCatalogs { create("libs") { from(files("../gradle/libs.versions.toml")) } }
}

rootProject.name = "build-logic"
