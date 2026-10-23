// SPDX-License-Identifier: Apache-2.0
//
// Classes injected into the bootstrap class loader (10 §3). Zero dependencies (B-1); the
// argos.java-agent plugin fails check if any dependency is declared.

plugins { id("argos.java-agent") }

description = "Argos agent bootstrap API (Tracer, TraceContext, CallDepth, KillSwitch, ...)."
