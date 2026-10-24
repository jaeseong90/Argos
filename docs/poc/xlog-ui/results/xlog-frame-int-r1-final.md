# XLog frame time (T-NFR-14)

- Started: 2026-10-23T17:45:34.138Z
- Machine: Windows_NT 10.0.26200 x64, AMD Ryzen 5 5600H with Radeon Graphics, 12 logical cores, 15 GB
- Browser: chromium 156.0.8078.4 (headless), Playwright 1.64.1, Node v24.14.0
- GPU: ANGLE (AMD, AMD Radeon(TM) Graphics (0x00001638) Direct3D11 vs_5_0 ps_5_0, D3D11)
- Viewport 1366x768, DPR 1 and 2 (per scenario), plot 1254x382 CSS px, refresh 6.9 ms

Frame time = busiest rendering-pipeline thread per frame (Chrome trace). Budget: p95 and mean <= 16 ms of the frames of all runs together (pooled). Max run p95 / Max run mean: the highest p95 and mean of a single run (reported, not judged). Canvas px: backing store of the point layer (capped, ADR-0003 C-34) / the overlay.

| Scenario | DPR | Canvas px (points / overlay) | Mode | Frames | Frame time p50 / p95 / max (ms) | Mean | Max run p95 (ms) | Max run mean (ms) | Interval p50 / p95 / max (ms) | JS p50 / p95 / max (ms) | Busiest thread | CPU load before (%) | Verdict |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| T-NFR-14 static 10k render=default dpr=1 | 1 | 1254x382 / 1334x420 | pixels | 357 | 1.2 / 1.8 / 4.8 | 1.3 | 1.9 | 1.3 | 6.9 / 7.1 / 8.0 | 0.8 / 1.1 / 1.8 | main 98%, gpu 2% | 7-10 | PASS |
| T-NFR-14 static 50k render=default dpr=1 | 1 | 1254x382 / 1334x420 | pixels | 357 | 2.3 / 3.4 / 7.0 | 2.5 | 3.5 | 2.7 | 6.9 / 7.1 / 7.8 | 1.9 / 2.6 / 3.0 | main 98%, gpu 2% | 8-19 | PASS |
| T-NFR-14 static 100k render=default dpr=1 | 1 | 1254x382 / 1334x420 | pixels | 357 | 3.5 / 5.0 / 9.1 | 3.7 | 5.6 | 4.2 | 6.9 / 7.1 / 9.1 | 3.1 / 4.0 / 5.5 | main 98%, gpu 2% | 8-14 | PASS |
| T-NFR-14 static 100k worst render=default color=service y=log dpr=1 | 1 | 1254x382 / 1334x420 | pixels | 357 | 3.7 / 6.6 / 11.9 | 4.1 | 7.5 | 4.9 | 6.9 / 7.3 / 11.9 | 3.3 / 5.7 / 7.5 | main 99%, gpu 1% | 5-44 | PASS |
| T-NFR-14 realtime 100k + 500/s render=default dpr=1 | 1 | 1254x382 / 1334x420 | pixels | 297 | 1.1 / 2.7 / 5.1 | 1.4 | 3.1 | 1.4 | 95.0 / 110 / 117 | 0.2 / 1.7 / 3.0 | main 83%, gpu 17% | 2-13 | PASS |
| T-NFR-14 interaction 100k hover+drag render=default dpr=1 | 1 | 1254x382 / 1334x420 | pixels | 513 | 1.6 / 3.6 / 14.9 | 2.0 | 3.9 | 2.1 | 7.0 / 13.6 / 89.1 | 0.4 / 0.6 / 0.8 | main 100%, gpu 0% | 7-20 | PASS |
| T-NFR-14 static 10k render=default dpr=2 | 2 | 2508x764 / 2668x840 | pixels | 357 | 2.2 / 3.3 / 5.4 | 2.3 | 3.7 | 2.6 | 6.9 / 7.1 / 7.2 | 1.6 / 2.4 / 3.3 | main 55%, gpu 45% | 7-16 | PASS |
| T-NFR-14 static 50k render=default dpr=2 | 2 | 2508x764 / 2668x840 | pixels | 357 | 4.1 / 4.9 / 7.1 | 4.3 | 5.4 | 4.6 | 6.9 / 7.0 / 8.8 | 3.8 / 4.4 / 6.2 | main 100%, gpu 0% | 5-19 | PASS |
| T-NFR-14 static 100k render=default dpr=2 | 2 | 2508x764 / 2668x840 | pixels | 357 | 6.0 / 8.0 / 10.6 | 6.3 | 9.1 | 6.8 | 6.9 / 8.0 / 10.6 | 5.6 / 7.5 / 10.1 | main 99%, gpu 1% | 7-18 | PASS |
| T-NFR-14 static 100k worst render=default color=service y=log dpr=2 | 2 | 2508x764 / 2668x840 | pixels | 357 | 8.0 / 9.5 / 11.3 | 8.0 | 9.8 | 8.2 | 8.0 / 9.5 / 11.3 | 7.6 / 9.0 / 10.8 | main 100%, gpu 0% | 5-15 | PASS |
| T-NFR-14 realtime 100k + 500/s render=default dpr=2 | 2 | 2508x764 / 2668x840 | pixels | 297 | 1.8 / 4.1 / 7.2 | 2.2 | 4.3 | 2.4 | 97.3 / 110 / 116 | 0.8 / 2.3 / 2.9 | main 89%, gpu 11% | 13-20 | PASS |
| T-NFR-14 interaction 100k hover+drag render=default dpr=2 | 2 | 2508x764 / 2668x840 | pixels | 513 | 2.1 / 3.2 / 13.8 | 2.2 | 3.4 | 2.5 | 7.0 / 13.6 / 90.1 | 0.4 / 0.5 / 0.7 | gpu 67%, main 33% | 5-15 | PASS |

Selection timing (not judged): query = hit index check + rectangle query, the docs/15 §7.3 "선택 계산" (budget 5 ms); compute = snapshot copy + summary; commit = pointer up to the React commit of summary and list.

| Scenario | DPR | Selections | Points | Query p50 / p95 / max (ms) | Query within budget | Compute p50 / p95 / max (ms) | Commit p50 / p95 / max (ms) | CPU load before (%) |
|---|---|---|---|---|---|---|---|---|
| selection 100k full-plot drag render=default dpr=1 (not judged) | 1 | 15 | 100000 | 1.0 / 6.4 / 6.4 | p95 > 5 ms | 8.5 / 21.9 / 21.9 | 51.1 / 71.5 / 71.5 | 2-19 |
| selection 100k full-plot drag render=default dpr=2 (not judged) | 2 | 15 | 100000 | 0.9 / 4.5 / 4.5 | p95 <= 5 ms | 5.7 / 19.3 / 19.3 | 40.7 / 73.0 / 73.0 | 4-19 |

## T-NFR-14 static 10k render=default dpr=1

| Run | CPU before / during (%) | Quiet | Frame time p50 / p95 / max | Interval p50 / p95 / max | JS p50 / p95 / max | p95 busy per thread | Untraced interval p50 / p95 / max | Untraced JS p50 / p95 / max |
|---|---|---|---|---|---|---|---|---|
| 1 | 10 / 17 | yes | 1.2 / 1.8 / 4.8 | 6.9 / 7.1 / 7.3 | 0.8 / 1.2 / 1.4 | main 1.7, compositor 0.3, gpu 1.1, viz 0.4 | 6.9 / 7.0 / 7.1 | 0.8 / 1.0 / 1.3 |
| 2 | 9 / 27 | yes | 1.3 / 1.9 / 3.7 | 7.0 / 7.1 / 8.0 | 0.8 / 1.1 / 1.7 | main 1.9, compositor 0.4, gpu 1.3, viz 0.5 | 6.9 / 7.0 / 7.1 | 0.8 / 1.1 / 1.3 |
| 3 | 7 / 20 | yes | 1.2 / 1.6 / 2.4 | 6.9 / 7.0 / 7.2 | 0.8 / 1.0 / 1.8 | main 1.6, compositor 0.3, gpu 1.1, viz 0.4 | 6.9 / 7.0 / 7.2 | 0.8 / 1.0 / 1.8 |

## T-NFR-14 static 50k render=default dpr=1

| Run | CPU before / during (%) | Quiet | Frame time p50 / p95 / max | Interval p50 / p95 / max | JS p50 / p95 / max | p95 busy per thread | Untraced interval p50 / p95 / max | Untraced JS p50 / p95 / max |
|---|---|---|---|---|---|---|---|---|
| 1 | 8 / 28 | yes | 2.2 / 3.1 / 3.7 | 6.9 / 7.1 / 7.1 | 1.8 / 2.0 / 2.8 | main 2.8, compositor 0.4, gpu 1.2, viz 0.4 | 6.9 / 7.0 / 7.2 | 1.9 / 2.2 / 2.6 |
| 2 | 19 / 52 | yes | 2.4 / 3.5 / 7.0 | 6.9 / 7.1 / 7.8 | 1.9 / 2.7 / 3.0 | main 3.3, compositor 0.4, gpu 1.4, viz 0.5 | 6.9 / 7.0 / 7.2 | 1.8 / 2.1 / 2.3 |
| 3 | 14 / 45 | yes | 2.5 / 3.4 / 7.0 | 6.9 / 7.1 / 7.3 | 2.1 / 2.8 / 3.0 | main 3.4, compositor 0.4, gpu 1.5, viz 0.5 | 6.9 / 7.0 / 7.1 | 1.9 / 2.6 / 3.1 |

## T-NFR-14 static 100k render=default dpr=1

| Run | CPU before / during (%) | Quiet | Frame time p50 / p95 / max | Interval p50 / p95 / max | JS p50 / p95 / max | p95 busy per thread | Untraced interval p50 / p95 / max | Untraced JS p50 / p95 / max |
|---|---|---|---|---|---|---|---|---|
| 1 | 8 / 63 | yes | 4.1 / 5.6 / 9.1 | 6.9 / 7.2 / 9.1 | 3.4 / 4.4 / 5.5 | main 5.3, compositor 0.5, gpu 4.3, viz 0.5 | 6.9 / 7.1 / 7.4 | 4.3 / 5.4 / 6.5 |
| 2 | 14 / 21 | yes | 3.4 / 4.0 / 6.2 | 6.9 / 7.1 / 7.1 | 3.0 / 3.4 / 3.6 | main 4.0, compositor 0.4, gpu 1.2, viz 0.4 | 6.9 / 7.0 / 7.1 | 3.8 / 4.4 / 5.0 |
| 3 | 8 / 16 | yes | 3.4 / 4.0 / 5.2 | 6.9 / 7.1 / 8.9 | 3.0 / 3.3 / 3.6 | main 3.9, compositor 0.3, gpu 1.2, viz 0.4 | 6.9 / 7.0 / 10.1 | 3.0 / 3.6 / 4.5 |

## T-NFR-14 static 100k worst render=default color=service y=log dpr=1

| Run | CPU before / during (%) | Quiet | Frame time p50 / p95 / max | Interval p50 / p95 / max | JS p50 / p95 / max | p95 busy per thread | Untraced interval p50 / p95 / max | Untraced JS p50 / p95 / max |
|---|---|---|---|---|---|---|---|---|
| 1 | 44 / 53 | no (waited 148 s) | 4.1 / 7.5 / 11.9 | 6.9 / 8.1 / 11.9 | 3.7 / 6.2 / 7.5 | main 7.5, compositor 0.7, gpu 2.5, viz 0.6 | 6.9 / 7.1 / 7.4 | 4.0 / 5.8 / 7.4 |
| 2 | 11 / 15 | yes | 3.6 / 4.3 / 6.2 | 6.9 / 7.0 / 7.2 | 3.2 / 3.4 / 3.8 | main 4.0, compositor 0.3, gpu 1.0, viz 0.4 | 6.9 / 7.0 / 14.1 | 3.3 / 3.5 / 3.7 |
| 3 | 5 / 17 | yes | 3.6 / 4.0 / 4.4 | 6.9 / 7.0 / 7.1 | 3.2 / 3.4 / 3.9 | main 4.0, compositor 0.3, gpu 1.0, viz 0.3 | 6.9 / 7.0 / 7.1 | 3.2 / 3.4 / 4.4 |

## T-NFR-14 realtime 100k + 500/s render=default dpr=1

| Run | CPU before / during (%) | Quiet | Frame time p50 / p95 / max | Interval p50 / p95 / max | JS p50 / p95 / max | p95 busy per thread | Untraced interval p50 / p95 / max | Untraced JS p50 / p95 / max |
|---|---|---|---|---|---|---|---|---|
| 1 | 12 / 14 | yes | 1.0 / 3.1 / 5.1 | 95.1 / 111 / 115 | 0.2 / 1.9 / 3.0 | main 3.1, compositor 0.6, gpu 1.1, viz 0.6 | - | 0.2 / 1.6 / 2.7 |
| 2 | 2 / 7 | yes | 1.1 / 2.7 / 3.8 | 94.9 / 112 / 117 | 0.2 / 1.9 / 2.1 | main 2.7, compositor 0.6, gpu 1.0, viz 0.5 | - | 0.2 / 2.0 / 2.5 |
| 3 | 13 / 5 | yes | 1.2 / 2.5 / 2.8 | 94.8 / 110 / 114 | 0.2 / 1.7 / 2.1 | main 2.5, compositor 0.6, gpu 1.1, viz 0.5 | - | 0.2 / 2.2 / 2.7 |

- run 1: 102 frames (0 full redraws), points 105730 -> 109171
- run 2: 102 frames (0 full redraws), points 109503 -> 112903
- run 3: 102 frames (0 full redraws), points 113268 -> 116643

## T-NFR-14 interaction 100k hover+drag render=default dpr=1

| Run | CPU before / during (%) | Quiet | Frame time p50 / p95 / max | Interval p50 / p95 / max | JS p50 / p95 / max | p95 busy per thread | Untraced interval p50 / p95 / max | Untraced JS p50 / p95 / max |
|---|---|---|---|---|---|---|---|---|
| 1 | 17 / 37 | yes | 1.7 / 3.9 / 14.9 | 7.0 / 13.4 / 89.0 | 0.4 / 0.6 / 0.7 | main 3.9, compositor 0.7, gpu 1.3, viz 0.6 | - | - |
| 2 | 7 / 46 | yes | 1.7 / 3.6 / 9.1 | 7.0 / 13.7 / 89.1 | 0.4 / 0.6 / 0.8 | main 3.6, compositor 0.7, gpu 1.4, viz 0.6 | - | - |
| 3 | 20 / 34 | yes | 1.6 / 3.3 / 9.2 | 6.9 / 13.6 / 82.8 | 0.3 / 0.5 / 0.6 | main 3.3, compositor 0.7, gpu 1.2, viz 0.6 | - | - |

- run 1: selection of 1544 points, query 0.70 ms, compute 2.1 ms, React commit 104.3 ms (not a canvas frame, not judged)
- run 1: 172 frames, point layer redrawn in 0
- run 2: selection of 1544 points, query 0.70 ms, compute 2.8 ms, React commit 83.0 ms (not a canvas frame, not judged)
- run 2: 172 frames, point layer redrawn in 0
- run 3: selection of 1544 points, query 0.40 ms, compute 1.2 ms, React commit 46.9 ms (not a canvas frame, not judged)
- run 3: 172 frames, point layer redrawn in 0
- run 1: frames around the end of the drag (pointer up, React commit of summary and list, next hover frames; not judged): p50 / p95 / max 11.5 / 129 / 297 ms
- run 2: frames around the end of the drag (pointer up, React commit of summary and list, next hover frames; not judged): p50 / p95 / max 14.4 / 82.7 / 313 ms
- run 3: frames around the end of the drag (pointer up, React commit of summary and list, next hover frames; not judged): p50 / p95 / max 9.9 / 41.1 / 162 ms

## T-NFR-14 static 10k render=default dpr=2

| Run | CPU before / during (%) | Quiet | Frame time p50 / p95 / max | Interval p50 / p95 / max | JS p50 / p95 / max | p95 busy per thread | Untraced interval p50 / p95 / max | Untraced JS p50 / p95 / max |
|---|---|---|---|---|---|---|---|---|
| 1 | 7 / 28 | yes | 2.4 / 3.7 / 5.4 | 6.9 / 7.0 / 7.1 | 1.9 / 2.6 / 3.3 | main 3.4, compositor 0.4, gpu 3.0, viz 0.6 | 6.9 / 7.0 / 14.2 | 1.9 / 2.2 / 2.4 |
| 2 | 8 / 16 | yes | 1.9 / 2.6 / 4.7 | 6.9 / 7.0 / 7.1 | 1.5 / 1.9 / 2.2 | main 2.4, compositor 0.3, gpu 2.2, viz 0.4 | 6.9 / 7.0 / 7.1 | 1.5 / 1.7 / 2.2 |
| 3 | 16 / 33 | yes | 2.2 / 3.0 / 4.1 | 6.9 / 7.1 / 7.2 | 1.6 / 2.0 / 2.4 | main 2.8, compositor 0.4, gpu 2.6, viz 0.5 | 6.9 / 7.1 / 7.1 | 2.1 / 2.8 / 3.6 |

## T-NFR-14 static 50k render=default dpr=2

| Run | CPU before / during (%) | Quiet | Frame time p50 / p95 / max | Interval p50 / p95 / max | JS p50 / p95 / max | p95 busy per thread | Untraced interval p50 / p95 / max | Untraced JS p50 / p95 / max |
|---|---|---|---|---|---|---|---|---|
| 1 | 19 / 17 | yes | 4.6 / 5.4 / 7.1 | 6.9 / 7.1 / 7.1 | 4.2 / 4.6 / 6.2 | main 5.2, compositor 0.3, gpu 2.7, viz 0.4 | 6.9 / 7.0 / 7.1 | 3.5 / 3.8 / 3.9 |
| 2 | 5 / 18 | yes | 4.1 / 4.6 / 6.0 | 6.9 / 7.0 / 8.8 | 3.8 / 4.1 / 4.4 | main 4.6, compositor 0.3, gpu 2.4, viz 0.3 | 6.9 / 7.0 / 7.1 | 3.8 / 4.2 / 4.7 |
| 3 | 7 / 14 | yes | 4.1 / 4.3 / 5.2 | 6.9 / 7.0 / 7.1 | 3.7 / 3.9 / 4.3 | main 4.3, compositor 0.3, gpu 2.2, viz 0.3 | 6.9 / 7.0 / 7.1 | 3.7 / 3.9 / 4.6 |

## T-NFR-14 static 100k render=default dpr=2

| Run | CPU before / during (%) | Quiet | Frame time p50 / p95 / max | Interval p50 / p95 / max | JS p50 / p95 / max | p95 busy per thread | Untraced interval p50 / p95 / max | Untraced JS p50 / p95 / max |
|---|---|---|---|---|---|---|---|---|
| 1 | 12 / 54 | yes | 6.6 / 9.1 / 10.6 | 7.0 / 9.1 / 10.6 | 6.2 / 8.5 / 10.1 | main 9.1, compositor 0.5, gpu 3.3, viz 0.6 | 7.0 / 13.9 / 14.0 | 7.3 / 9.0 / 9.9 |
| 2 | 7 / 17 | yes | 5.7 / 6.6 / 7.2 | 6.9 / 7.1 / 7.2 | 5.3 / 5.8 / 6.7 | main 6.2, compositor 0.3, gpu 3.3, viz 0.4 | 6.9 / 7.0 / 7.1 | 5.5 / 6.3 / 7.7 |
| 3 | 18 / 33 | yes | 6.2 / 6.9 / 7.8 | 6.9 / 7.2 / 7.8 | 5.7 / 6.4 / 7.5 | main 6.9, compositor 0.4, gpu 3.4, viz 0.5 | 6.9 / 7.1 / 7.1 | 6.0 / 7.4 / 7.9 |

## T-NFR-14 static 100k worst render=default color=service y=log dpr=2

| Run | CPU before / during (%) | Quiet | Frame time p50 / p95 / max | Interval p50 / p95 / max | JS p50 / p95 / max | p95 busy per thread | Untraced interval p50 / p95 / max | Untraced JS p50 / p95 / max |
|---|---|---|---|---|---|---|---|---|
| 1 | 15 / 21 | yes | 8.2 / 9.8 / 11.2 | 8.2 / 9.8 / 11.2 | 7.8 / 9.3 / 10.8 | main 9.8, compositor 0.6, gpu 3.9, viz 0.6 | 7.0 / 13.8 / 13.9 | 7.3 / 8.5 / 10.0 |
| 2 | 13 / 30 | yes | 8.0 / 9.1 / 10.1 | 8.0 / 9.1 / 10.1 | 7.6 / 8.7 / 9.7 | main 9.1, compositor 0.5, gpu 3.3, viz 0.5 | 7.0 / 13.9 / 15.0 | 7.3 / 8.5 / 9.2 |
| 3 | 5 / 27 | yes | 8.0 / 9.3 / 11.3 | 8.0 / 9.3 / 11.3 | 7.5 / 8.9 / 10.5 | main 9.3, compositor 0.5, gpu 3.2, viz 0.6 | 7.0 / 13.9 / 14.0 | 7.9 / 10.4 / 13.3 |

## T-NFR-14 realtime 100k + 500/s render=default dpr=2

| Run | CPU before / during (%) | Quiet | Frame time p50 / p95 / max | Interval p50 / p95 / max | JS p50 / p95 / max | p95 busy per thread | Untraced interval p50 / p95 / max | Untraced JS p50 / p95 / max |
|---|---|---|---|---|---|---|---|---|
| 1 | 20 / 21 | yes | 1.8 / 4.3 / 5.8 | 99.1 / 110 / 116 | 0.9 / 2.3 / 2.7 | main 4.3, compositor 0.9, gpu 2.0, viz 0.9 | - | 0.9 / 2.5 / 3.5 |
| 2 | 16 / 19 | yes | 1.7 / 4.1 / 4.7 | 95.3 / 111 / 114 | 0.9 / 2.3 / 2.8 | main 4.1, compositor 0.8, gpu 2.1, viz 0.8 | - | 0.7 / 2.2 / 3.3 |
| 3 | 13 / 7 | yes | 1.6 / 3.3 / 7.2 | 97.5 / 110 / 116 | 0.6 / 2.4 / 2.9 | main 3.3, compositor 0.9, gpu 2.1, viz 0.9 | - | 0.7 / 2.3 / 3.2 |

- run 1: 101 frames (0 full redraws), points 101621 -> 104962
- run 2: 102 frames (0 full redraws), points 105301 -> 108662
- run 3: 101 frames (0 full redraws), points 109025 -> 112415

## T-NFR-14 interaction 100k hover+drag render=default dpr=2

| Run | CPU before / during (%) | Quiet | Frame time p50 / p95 / max | Interval p50 / p95 / max | JS p50 / p95 / max | p95 busy per thread | Untraced interval p50 / p95 / max | Untraced JS p50 / p95 / max |
|---|---|---|---|---|---|---|---|---|
| 1 | 12 / 16 | yes | 1.9 / 3.3 / 13.8 | 7.0 / 13.6 / 90.1 | 0.4 / 0.5 / 0.7 | main 3.3, compositor 0.6, gpu 2.3, viz 0.5 | - | - |
| 2 | 5 / 18 | yes | 1.8 / 2.7 / 6.7 | 7.0 / 13.4 / 83.0 | 0.3 / 0.4 / 0.6 | main 2.7, compositor 0.6, gpu 2.1, viz 0.5 | - | - |
| 3 | 15 / 43 | yes | 2.5 / 3.4 / 9.6 | 7.0 / 13.7 / 82.7 | 0.4 / 0.6 / 0.6 | main 3.4, compositor 0.7, gpu 2.9, viz 0.7 | - | - |

- run 1: selection of 1544 points, query 0.60 ms, compute 1.7 ms, React commit 74.8 ms (not a canvas frame, not judged)
- run 1: 172 frames, point layer redrawn in 0
- run 2: selection of 1544 points, query 0.30 ms, compute 1.2 ms, React commit 50.2 ms (not a canvas frame, not judged)
- run 2: 172 frames, point layer redrawn in 0
- run 3: selection of 1544 points, query 0.70 ms, compute 2.4 ms, React commit 55.9 ms (not a canvas frame, not judged)
- run 3: 172 frames, point layer redrawn in 0
- run 1: frames around the end of the drag (pointer up, React commit of summary and list, next hover frames; not judged): p50 / p95 / max 11.6 / 44.9 / 213 ms
- run 2: frames around the end of the drag (pointer up, React commit of summary and list, next hover frames; not judged): p50 / p95 / max 12.0 / 47.2 / 140 ms
- run 3: frames around the end of the drag (pointer up, React commit of summary and list, next hover frames; not judged): p50 / p95 / max 14.2 / 49.0 / 186 ms

## selection 100k full-plot drag render=default dpr=1 (not judged)

| Run | CPU before / during (%) | Quiet | Selections | Query p50 / p95 / max | Compute p50 / p95 / max | Commit p50 / p95 / max |
|---|---|---|---|---|---|---|
| 1 | 19 / 38 | yes | 5 | 1.7 / 4.6 / 4.6 | 9.7 / 21.9 / 21.9 | 67.5 / 71.5 / 71.5 |
| 2 | 19 / 49 | yes | 5 | 1.2 / 6.4 / 6.4 | 8.4 / 20.5 / 20.5 | 55.7 / 71.4 / 71.4 |
| 3 | 2 / 25 | yes | 5 | 0.6 / 0.8 / 0.8 | 6.0 / 14.4 / 14.4 | 43.4 / 51.1 / 51.1 |

## selection 100k full-plot drag render=default dpr=2 (not judged)

| Run | CPU before / during (%) | Quiet | Selections | Query p50 / p95 / max | Compute p50 / p95 / max | Commit p50 / p95 / max |
|---|---|---|---|---|---|---|
| 1 | 19 / 21 | yes | 5 | 2.3 / 4.5 / 4.5 | 14.2 / 19.3 / 19.3 | 52.4 / 73.0 / 73.0 |
| 2 | 4 / 21 | yes | 5 | 0.9 / 1.1 / 1.1 | 5.7 / 7.6 / 7.6 | 40.5 / 42.8 / 42.8 |
| 3 | 7 / 22 | yes | 5 | 0.7 / 0.8 / 0.8 | 5.3 / 5.8 / 5.8 | 39.5 / 41.4 / 41.4 |
