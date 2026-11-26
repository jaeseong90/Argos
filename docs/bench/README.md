# docs/bench

Measurement reports of Argos. The agent overhead reports (docs/18 §7, 01 §7.3, N-01..N-04 and
N-26..N-30) are written by the bench harness [`test/bench`](../../test/bench/README.md); the weekly
workflow `.github/workflows/bench.yml` proposes a new one every week as a pull request. This page
says how the files are named, how a report is approved, and which reports may be compared.

## Contents

| File or directory | What it is | Written by |
|---|---|---|
| `<YYYY-MM-DD>-<commit7>.md` | Agent overhead report of 18 §7.6: environment, run parameters, raw results per run, statistics with confidence intervals, memory and startup, verdicts of N-01..N-04 and N-26..N-30, graphs, reproduction commands. Ends with the machine-readable summary comment | `bench.sh publish` (`bench-protocol.sh`, `benchTest`, weekly workflow) |
| `<YYYY-MM-DD>-<commit7>/` | The report's graphs (SVG with their data as TSV) and `summary.json`; raw set files when a person copies them (docs/poc/agent-overhead.md 9.4) | same |
| `latest.md` | Pointer to the newest weekly report: its verdicts, regression result and headline numbers, and a link | `publish --latest` |
| `trend.md`, `trend.svg` (and its TSV) | Trend over the reports' summaries: relative CPU (mean, peak), p99 difference, heap, startup increase | `publish --trend`, `bench.sh trend` |
| `gate1-agent-<YYYY-MM-DD>-<commit7>.md` | Agent part of the Gate 1 report: T-NFR-01..04 and T-NFR-26..30 from a dedicated run, with the items a person still has to close | `bench.sh gate1` |
| `gate1.md` | The whole Gate 1 report (agent, server, storage, API, UI requirements) | WO-53 aggregates it |
| `server-load.md` | Server load test (18 §8, N-06, N-18, D-13) | `serverLoadTest -Pserver.load.publish=true` (WO-29) |
| `wo02-overhead-2026-10-24/`, `wo02-agent-2026-11-02/`, `wo03-storage-2026-10-24/` | Phase 0 PoC evidence (WO-02, WO-03), measured on a shared laptop | historical |

The date is the UTC date of the first measured set; the commit is the measured tree. The date and
commit name of a report never change, and a published report is never rewritten (a recomputation is
a new report).

## Approval

A report becomes a regression baseline only after a maintainer approves it:

1. The weekly workflow opens the pull request `bench/weekly-<YYYY-MM-DD>-<commit7>` with the report,
   its directory, `latest.md` and the trend. A FAIL verdict or a regression WARN is flagged at the
   top of its description.
2. The reviewer checks that the measured sets are valid (no set-level INVALID: off CV, k6 gate C-46,
   loader errors, exporter, environment record) and reads the regression result.
3. To approve, set `"approved": true` in the summary comment at the end of the Markdown report
   (`<!-- argos-bench-summary ... -->`), commit it to the branch, and merge. A report whose measured
   sets are INVALID is merged as a record, if at all, but not approved.

A weekly report measures only the sets main and startup (ADR-0009 C-80), so its overall verdict is
INVALID ("not measured" for N-28 and N-29 and the requirements of the other sets) even when every
measured set is valid; that is no reason to withhold approval. Container, vt, worst, otel and grpc
run per release candidate.

## Comparison rules

- `--baseline auto` compares a new report with the most recent approved report of the same machine
  profile, app, JDK, exporter and encoding. A report without such a baseline says NOT COMPARED and
  why. WARN (18 §7.7): relative CPU +1.5 percentage points or more, or p99 difference +0.3 ms or
  more, worse than the baseline; FAIL (a limit of 01 exceeded) takes precedence.
- Verdicts use the `noop` exporter (ADR-0009 C-80). `noop` encodes every transaction with the
  agent's protobuf encoder and drops it (ADR-0010 C-145 (4), 18 §5.4). The WO-02 sets used JSONL
  encoding, or `drop` without encoding, so their numbers are **never compared** with protobuf-era
  reports; the trend lists them as "not compared". A `grpc` set adds a transport cost section
  (on(grpc) - on(noop)) without a verdict.
- shop-boot3 gained JDBC drivers in WO-13. Startup and transformation numbers of shop-boot3 from
  before and after that change carry the footnote "WO-13 driver addition" (ADR-0010 C-186 (2),
  summary field `sampleAppDrivers`). Within one report the A-B comparison uses the same jar, so the
  verdicts are unaffected.
- `latest.md` is a short Markdown pointer, not the symbolic link 18 §7.7 mentions: Windows checkouts
  (`core.symlinks=false`) do not keep links.
- Shared-machine runs (smoke runs, a laptop) are INVALID by construction and are never committed
  here; they stay under `test/bench/build`.

## Reference material, not baselines

The `wo02-*` and `wo03-*` directories are the Phase 0 PoC evidence. They were measured on a shared
developer laptop (the off-run CPU coefficients of variation of the two complete WO-02 load sets were
26.17% and 4.39%, above the 2% limit), by a
PoC agent without the profile policy, and with JSONL or no encoding. They explain the Gate 0
discussion in [docs/poc/agent-overhead.md](../poc/agent-overhead.md) and serve as input to
`bench.sh noise`; no report is compared with them.
