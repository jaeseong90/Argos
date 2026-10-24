// SPDX-License-Identifier: Apache-2.0
//
// Smoke runner behind every app's sampleSmoke<Variant> task (argos.sample-app): starts the packaged
// app without an agent (Spring Boot jar, or WAR in a standalone Tomcat), starts ext-stub in-process,
// calls the app's endpoint catalog (common: ShopCatalog / BatchCatalog), checks status codes and
// expectations, and records jar count, class count and startup time (WO-01 report, N-04/N-27
// baseline). Usable by hand against a running app as well (sample-apps/README.md).

plugins { id("argos.sample-app") }

description = "Sample apps: smoke runner (start app, call the catalog, check, report)."

sampleApp {
  javaRelease = 17
  runJdk = 21
}

dependencies {
  implementation(project(":sample-apps:common"))
  implementation(project(":sample-apps:ext-stub"))
}

// `java -jar` form for manual runs: build/sample-app/smoke.jar (ext-stub and common merged).
sampleApp { executableJar("io.argos.sample.smoke.SmokeMain") }
