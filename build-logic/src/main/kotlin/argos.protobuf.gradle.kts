// SPDX-License-Identifier: Apache-2.0
//
// argos.protobuf: Java code generation from the argos.v1 protos (19 §3.4, ADR-0010 C-104).
// - Only :proto applies it. The code is generated once there (full protobuf-java messages and
//   grpc-java stubs, C-72); the server depends on project(":proto") and the agent jar relocates
//   io.argos.proto (agent-dist). Any other project applying this plugin fails the configuration.
// - protobuf-javalite is not used (ADR-0005 A-14, ADR-0009 C-72): the former argos.proto.lite
//   switch is gone.
// - Works without any .proto file (generateProto is then NO-SOURCE).
// - Generated code is a build output and is never committed. It compiles under the common
//   -Xlint:all -Werror with no finding (protobuf 4.36.2, grpc-java 1.85.1); Error Prone skips
//   build/generated (argos.java-common). Should a generator upgrade add lint findings, exclude only
//   the generated sources from -Werror (separate compilation), never the hand-written ones.

import com.google.protobuf.gradle.id
import io.argos.buildlogic.argosLibs
import io.argos.buildlogic.library
import io.argos.buildlogic.versionOf

plugins {
  `java-library`
  id("com.google.protobuf")
}

check(path == ":proto") {
  "argos.protobuf generates the argos.v1 code once, in :proto (ADR-0010 C-104); " +
    "$path must depend on project(\":proto\") instead of applying the plugin"
}

val catalog = argosLibs

protobuf {
  protoc { artifact = "com.google.protobuf:protoc:${catalog.versionOf("protobuf")}" }
  plugins { id("grpc") { artifact = "io.grpc:protoc-gen-grpc-java:${catalog.versionOf("grpc")}" } }
  generateProtoTasks {
    all().configureEach {
      plugins {
        id("grpc") {
          // Avoid @javax.annotation.Generated (no extra dependency on Java 9+).
          option("@generated=omit")
        }
      }
    }
  }
}

dependencies {
  "api"(catalog.library("protobuf-java"))
  "api"(catalog.library("grpc-protobuf"))
  "api"(catalog.library("grpc-stub"))
  // grpc-java's transitive Guava is raised to the catalog floor (CVE-2026-102554, WO-44 T-BLD-008).
  // A constraint on api reaches every consumer of :proto, including the shaded agent jar.
  val guava = catalog.library("grpc-guava-android").get()
  constraints {
    "api"("${guava.module}:${guava.versionConstraint.requiredVersion}") {
      because("grpc-java 1.85.1 requests guava 33.6.0-android (CVE-2026-102554)")
    }
  }
}
