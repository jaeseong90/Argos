// SPDX-License-Identifier: Apache-2.0
//
// 72-hour soak test (18 §11.2): shop-boot3 with the agent under 500 TPS k6, shop-batch hourly, real
// server, ClickHouse and PostgreSQL; heap slope, NMT, FD, threads, parts, alert false positives.
// Development-only (ArgosProject.isDevelopmentOnly), never shipped.
//
// soakTest is a placeholder until WO-52 (chaos and 72-hour soak) adds the scripts and the Java
// collector: it requires -Pbench.machine-profile=<id> (18 §2.2) and then fails, naming WO-52. It
// never measures anything. See README.md.

import io.argos.buildlogic.testing.registerPendingRunner

plugins { base }

description = "Argos 72-hour soak test (18 §11.2; runner: WO-52)."

registerPendingRunner(
  taskName = "soakTest",
  owningWorkOrder = "WO-52",
  design = "docs/18-test-design.md §11.2",
  summary = "72-hour soak test: heap slope, NMT, FD, threads, ClickHouse parts (18 §11.2, T-SOAK).",
)
