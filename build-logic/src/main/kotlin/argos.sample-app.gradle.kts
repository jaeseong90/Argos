// SPDX-License-Identifier: Apache-2.0
//
// argos.sample-app: every project under sample-apps/ (18 §4, C-18, WO-01).
// - argos.java-common (Spotless, Error Prone, -Werror, locking, license checks). Sample apps are
//   development-only (ArgosProject.isDevelopmentOnly): checkDevOnlyLicenses checks them against
//   docs/licenses/dev-only.md; nothing here is shipped.
// - Compiled by the JDK 21 compiler with `--release <javaRelease>` (DSN-19-02, like the agent);
//   tests and the smoke run the app on its own JDK (`runJdk`, 18 §4.1 build JDK column).
// - Packaging (18 §4.4): bootJar() = Spring Boot executable jar built without the Spring Boot
//   Gradle plugin (one plugin version cannot serve Boot 2.7, 3.x and 4.x, and the 2.7 plugin does
//   not run on Gradle 9), executableJar() = plain `java -jar`, war() = WAR for a standalone Tomcat
//   from tomcat(key, distribution). Output: build/sample-app/<app>.jar|war + <app>.properties
//   (task sampleAppJar, or war; the large Spring Boot jars are not part of `assemble` and are built
//   on demand by the smoke, the agent harness through the sampleAppElements configuration, and
//   sampleImage).
// - dockerImage(): `sampleImage` = docker build of the project's Dockerfile (JDK_IMAGE build arg).
// - smoke { variant(...) }: `sampleSmoke<Variant>` tasks and the `sampleSmoke` lifecycle task. Not
//   part of check (PR budget, 18 §2.4); variants listed in integrationTest(...) run in the
//   integration layer (18 §2.1). See sample-apps/README.md.

import io.argos.buildlogic.sample.SampleAppExtension
import io.argos.buildlogic.sample.SampleAppWiring

plugins { id("argos.java-common") }

val sampleApp = extensions.create<SampleAppExtension>("sampleApp", project)

SampleAppWiring.configure(project, sampleApp)
