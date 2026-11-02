# WO-02 agent PoC: integration evidence (2026-11-02)

Evidence behind chapters 9 and 12 of [`docs/poc/agent-poc.md`](../../poc/agent-poc.md) (WO-02
integration run). The session scratch directories of the WO-02 lanes (2026-10-24) were deleted and
the build outputs were cleaned before this run, so everything here was produced again on
2026-11-02. All numbers are **reference values** from one developer laptop; Gate 0 numbers are
re-measured by a human on dedicated hardware.

## Run conditions

| Item | Value |
|---|---|
| Date | 2026-11-02, 19:35 to 21:58 local time (UTC+9); re-verification after the review fix round (`CHANGELOG.md` and `docs/poc/agent-poc.md` section 14.4 only, no code change) from 22:23, steps 29 onwards in [`runs.tsv`](runs.tsv) and section 12.7 of the PoC document |
| Code | commit `4a43e77` plus the uncommitted working tree of WO-00 to WO-04 and the WO-02 integration changes |
| Machine | AMD Ryzen 5 5600H (6 cores, 12 threads), 15.3 GiB RAM, Windows 11 Home 10.0.26200, AC power. Nothing else ran (WO-03 and WO-04 had finished); the machine CPU load before each step is in [`runs.tsv`](runs.tsv) |
| Agent jar | `argos-agent-0.0.0-SNAPSHOT.jar`, 4,074,167 bytes, SHA-256 `bfe8341fb33a80ee3ffaadf067d8f4077b566d380ee514f1b6f3ebf0f50218e3` (modules servlet, spring-webmvc, jdbc, executor, httpclient-jdk; no transport) |
| JDKs | 8 = Temurin 1.8.0_504, 11 = Temurin 11.0.32.1, 17 = Temurin 17.0.20.1, 21 = Oracle OpenJDK 21+35 (GA), 25 = OpenJDK 25+36; transport bench also AdoptOpenJDK 8u242-b08 and 8u252-b09 JREs (SHA-256 of the release zips checked against the release `.sha256.txt`) |

## Files

| Path | Content |
|---|---|
| [`runs.tsv`](runs.tsv) | Every integration command: start time, duration, exit code, CPU load before, outcome |
| `agent-jar/` | Release jar checks: `check-shading.txt` (output of `test/agent/check-shading.sh`), `jar-tf.txt` (`jar tf` listing), the `verifyAgentJar` reports (`relocated-strings.txt`, `own-relocated-literals.txt`, `major-version.txt`, `modules.txt`), `argos-deps.txt` from the jar |
| `agent-e2e/` | Sample app matrix (T-AG-010, T-AG-040, T-AG-070, T-BLD-006): per app x JDK the transform list, the JSONL transactions, the agent log, stdout/stderr and the temp directory events written by `SampleAppAgentTest` (`agent/agent-testing/build/reports/agent-e2e/`) |
| `agent-tests.tsv` | JUnit result counts per test class of the agent integration run |
| `transport/` | P-3 transport comparison: `summary.md` and `runs.tsv` of the bench (`transportPocBench`), `smoke.md` (known findings asserted) and `smoke-strict.md` (`-Ptransport.poc.strict=true`), and per comparison jar the reports of `transportPocJars` (`<id>/check-shading.txt`, `relocated-strings.txt`, `major-version.txt`, `own-relocated-literals.txt`). The per-run raw directories of the bench (command lines, stdout, stderr, agent logs, class load logs) are not copied (size); they are in `test/agent/transport-poc/build/poc/bench/<timestamp>/raw/` after a run |
| `ci/` | Logs of the Linux container steps: `regen-log.txt` (lock and verification metadata regeneration with an empty Gradle cache, docs/dev-setup.md section 7; result byte-identical to the repository files), `regen-fresh-log.txt` (the same with the metadata file deleted first, for comparison), `build-java-cpus4-log.txt` and `build-java-cpuset-log.txt` (pr.yml `build-java` simulation: empty Gradle, pnpm and Corepack caches, `--cpus=4` and `--cpuset-cpus=0-3`). Re-verification after the review fix round: `r2-regen-log.txt` and `r2-build-java-cpus4-log.txt` (same procedures) |

## Changes made when copying

- Machine paths are replaced: `<repo>` (working tree), `<scratch>` (session scratch directory),
  `<home>` (user home); the user name is replaced by `<user>`.
- Line endings are LF; `.log` files are renamed to `-log.txt` (the repository ignores `*.log`).
