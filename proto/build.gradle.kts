// SPDX-License-Identifier: Apache-2.0
//
// argos.v1 protocol definitions (11 §, 19 §3.4) and the integer protocol version in
// PROTOCOL_VERSION (DSN-19-10). The generated Java code (full protobuf-java + gRPC stubs) serves
// the server and the test harness; it targets Java 8 like the agent.

plugins {
  id("argos.java-agent")
  id("argos.protobuf")
}

description = "Argos protocol (argos.v1 protobuf messages and gRPC services)."
