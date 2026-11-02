# test/load/fake-agents

Server load generator of [docs/18 §8](../../../docs/18-test-design.md): fake agents that speak the
`argos.v1` protocol (proto stubs of docs/11, not the agent code) and drive the collector server.
Development-only, never shipped.

| | |
|---|---|
| Gradle project | `:test:load:fake-agents` |
| Task | `serverLoadTest` (not part of `check` or `build`) |
| Owning work order | **WO-29** (server load test and memory budget), docs/04 |
| Requirements | N-06 (5,000 TPS for 1 hour), N-10d (attack scenarios), N-18, D-13 (sent = stored) |
| Runs on | self-hosted `argos-storage` (18 §2.3), never on GitHub-hosted runners |

## Status

The runner does not exist yet. `serverLoadTest` is a placeholder that fixes the task name and the
machine-profile rule of 18 §2.2:

```
./gradlew serverLoadTest                                     # fails: requires -Pbench.machine-profile=<id>
./gradlew serverLoadTest -Pbench.machine-profile=argos-storage   # fails: no runner yet (WO-29), nothing measured
```

It never reports a measurement. WO-29 replaces the placeholder with the generator (parameters of
18 §8: `--instances`, `--tps`, `--steps-per-tx`, `--profile-rate`, `--stats-rows`, `--metrics`,
`--duration`, `--late-arrival`, `--duplicate`, `--clock-skew`, `--attack`) and its runner, and
writes `docs/bench/server-load.md`.

See [test/README.md](../../README.md) for every test layer and how to run it.
