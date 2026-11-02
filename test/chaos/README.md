# test/chaos

Chaos tests of [docs/18 §11.1](../../docs/18-test-design.md) (DSN-18-08): sample app with the agent
<-> toxiproxy <-> real server <-> ClickHouse and PostgreSQL (Compose `test` profile), k6 at 200 TPS.
Development-only, never shipped.

| | |
|---|---|
| Gradle project | `:test:chaos` |
| Task | `chaosTest` (not part of `check` or `build`) |
| Owning work order | **WO-52** (chaos and 72-hour soak), docs/04 |
| Tests | T-CH-001..T-CH-011, N-05 |
| Runs on | self-hosted `argos-matrix` (18 §2.3), before releases and for PRs that change the collection path (manual) |

## Status

The scenarios do not exist yet. `chaosTest` is a placeholder that fixes the task name and the
machine-profile rule of 18 §2.2:

```
./gradlew chaosTest                                          # fails: requires -Pbench.machine-profile=<id>
./gradlew chaosTest -Pbench.machine-profile=argos-matrix     # fails: no scenarios yet (WO-52), nothing run
```

It never reports a result. WO-52 adds the scenarios of 18 §11.1 (server outage, latency 500 ms,
loss 10%, 5xx and `RESOURCE_EXHAUSTED`, agent log disk full, DNS failure, TLS host name mismatch,
instance_id reassignment, ClickHouse and PostgreSQL outages, server disk 90%) with the common
verdict (app p99 within the bench confidence interval, agent log ERROR 0, recovery within 60 s).

See [test/README.md](../README.md) for every test layer and how to run it.
