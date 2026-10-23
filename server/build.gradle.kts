// SPDX-License-Identifier: Apache-2.0
//
// Collector server (02 §4.2, 12): one Spring Boot module, roles split by package
// (collector, web, alert, admin, ops, storage). Application code starts with WO-20.

plugins {
  id("argos.java-server")
  id("argos.integration-test")
}

description = "Argos collector server (gRPC ingest, REST API, alerting, administration)."
