// SPDX-License-Identifier: Apache-2.0
//
// Chaos tests (18 §11.1, DSN-18-08): sample app with the agent <-> toxiproxy <-> real server <->
// ClickHouse and PostgreSQL (Compose test profile), k6 at 200 TPS, scenarios T-CH-001..011.
// Development-only (ArgosProject.isDevelopmentOnly), never shipped.
//
// chaosTest is a placeholder until WO-52 (chaos and 72-hour soak) adds the scenarios: it requires
// -Pbench.machine-profile=<id> (18 §2.2) and then fails, naming WO-52. It never runs a scenario.
// See README.md.

import io.argos.buildlogic.testing.registerPendingRunner

plugins { base }

description = "Argos chaos tests with toxiproxy and docker (18 §11.1; scenarios: WO-52)."

registerPendingRunner(
  taskName = "chaosTest",
  owningWorkOrder = "WO-52",
  design = "docs/18-test-design.md §11.1",
  summary = "Chaos tests: server outage, latency, loss, 5xx, disk full, DNS, TLS, ClickHouse outage (18 §11.1, T-CH).",
)
