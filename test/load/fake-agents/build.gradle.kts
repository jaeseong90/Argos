// SPDX-License-Identifier: Apache-2.0
//
// Server load generator (18 §8, DSN-18-04): fake agents that drive the collector server at protocol
// level (11 proto stubs), N-06 one-hour 5,000 TPS run and the attack scenarios of N-10d.
// Development-only (ArgosProject.isDevelopmentOnly), never shipped.
//
// serverLoadTest is a placeholder until WO-29 (server load test and memory budget) adds the
// generator and its runner: it requires -Pbench.machine-profile=<id> like every performance task
// (18 §2.2) and then fails, naming WO-29. It never measures anything. See README.md.

import io.argos.buildlogic.testing.registerPendingRunner

plugins { base }

description = "Argos server load generator: fake agents at protocol level (18 §8; runner: WO-29)."

registerPendingRunner(
  taskName = "serverLoadTest",
  owningWorkOrder = "WO-29",
  design = "docs/18-test-design.md §8",
  summary = "Server load test: fake agents, 5,000 TPS for 1 hour, attack scenarios (18 §8, N-06, N-10d).",
)
