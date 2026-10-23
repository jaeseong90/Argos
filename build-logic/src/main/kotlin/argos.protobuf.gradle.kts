// SPDX-License-Identifier: Apache-2.0
//
// argos.protobuf: Java code generation from the argos.v1 protos (19 §3.4).
// - :proto owns proto/src/main/proto; other projects that apply this plugin (agent-core, server,
//   WO-11) compile the same files.
// - Agent projects honour -Pargos.proto.lite=true (A-1); :proto and :server always use full
//   protobuf-java.
// - Works without any .proto file (generateProto is then NO-SOURCE).
// - Generated code is a build output and is never committed.

import com.google.protobuf.gradle.id
import io.argos.buildlogic.argosLibs
import io.argos.buildlogic.library
import io.argos.buildlogic.versionOf

plugins {
  `java-library`
  id("com.google.protobuf")
}

val catalog = argosLibs

val lite =
  path.startsWith(":agent:") &&
    providers.gradleProperty("argos.proto.lite").map { it.toBoolean() }.getOrElse(false)

protobuf {
  protoc { artifact = "com.google.protobuf:protoc:${catalog.versionOf("protobuf")}" }
  plugins { id("grpc") { artifact = "io.grpc:protoc-gen-grpc-java:${catalog.versionOf("grpc")}" } }
  generateProtoTasks {
    all().configureEach {
      if (lite) builtins.named("java") { option("lite") }
      plugins {
        id("grpc") {
          // Avoid @javax.annotation.Generated (no extra dependency on Java 9+).
          option("@generated=omit")
          if (lite) option("lite")
        }
      }
    }
  }
}

if (path != ":proto") {
  // Compile the shared protos owned by :proto.
  sourceSets.named("main") {
    extensions
      .getByName<SourceDirectorySet>("proto")
      .srcDir(layout.settingsDirectory.dir("proto/src/main/proto"))
  }
}

dependencies {
  if (lite) {
    "api"(catalog.library("protobuf-javalite"))
    "api"(catalog.library("grpc-protobuf-lite"))
  } else {
    "api"(catalog.library("protobuf-java"))
    "api"(catalog.library("grpc-protobuf"))
  }
  "api"(catalog.library("grpc-stub"))
}
