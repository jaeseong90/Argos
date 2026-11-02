# test/soak

72-hour soak test of [docs/18 §11.2](../../docs/18-test-design.md): shop-boot3 with the agent under
a constant 500 TPS k6 load, shop-batch every hour, the real server, ClickHouse and PostgreSQL, with
`-XX:NativeMemoryTracking=summary`. Development-only, never shipped.

| | |
|---|---|
| Gradle project | `:test:soak` |
| Task | `soakTest` (not part of `check` or `build`) |
| Owning work order | **WO-52** (chaos and 72-hour soak), docs/04 |
| Tests | T-SOAK-001 (heap slope), T-NFR-05, T-NFR-18 |
| Runs on | self-hosted `argos-bench-app` + `argos-bench-load` (18 §2.3), before Gate 1 and Gate 2 |

## Status

The runner does not exist yet. `soakTest` is a placeholder that fixes the task name and the
machine-profile rule of 18 §2.2:

```
./gradlew soakTest                                           # fails: requires -Pbench.machine-profile=<id>
./gradlew soakTest -Pbench.machine-profile=argos-bench-app   # fails: no runner yet (WO-52), nothing measured
```

It never reports a measurement. WO-52 adds the collection scripts and the Java collector for the
measurements of 18 §11.2 (agent heap after `GC.run`, NMT, FD and thread counts, server RSS and WAL,
ClickHouse parts and merges, nightly consistency check, alert false positives, app p99) and writes
`docs/bench/soak-<date>.md`.

See [test/README.md](../README.md) for every test layer and how to run it.
