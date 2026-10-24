# XLog frame time (T-NFR-14)

- Started: 2026-10-23T18:50:05.270Z
- Machine: Windows_NT 10.0.26200 x64, AMD Ryzen 5 5600H with Radeon Graphics, 12 logical cores, 15 GB
- Browser: chromium 156.0.8078.4 (headless), Playwright 1.64.1, Node v24.14.0
- GPU: ANGLE (AMD, AMD Radeon(TM) Graphics (0x00001638) Direct3D11 vs_5_0 ps_5_0, D3D11)
- Viewport 1366x768, DPR 1 and 2 (per scenario), plot 1254x382 CSS px, refresh 6.9 ms

Frame time = busiest rendering-pipeline thread per frame (Chrome trace). Budget: p95 and mean <= 16 ms of the frames of all runs together (pooled). Max run p95 / Max run mean: the highest p95 and mean of a single run (reported, not judged). Canvas px: backing store of the point layer (capped, ADR-0003 C-34) / the overlay.

| Scenario | DPR | Canvas px (points / overlay) | Mode | Frames | Frame time p50 / p95 / max (ms) | Mean | Max run p95 (ms) | Max run mean (ms) | Interval p50 / p95 / max (ms) | JS p50 / p95 / max (ms) | Busiest thread | CPU load before (%) | Verdict |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| T-NFR-14 static 10k render=default dpr=1 | 1 | 1254x382 / 1334x420 | pixels | 357 | 1.2 / 1.7 / 5.5 | 1.3 | 1.8 | 1.3 | 6.9 / 7.1 / 9.1 | 0.8 / 1.0 / 1.5 | main 98%, gpu 2% | 5-14 | PASS |
| T-NFR-14 static 50k render=default dpr=1 | 1 | 1254x382 / 1334x420 | pixels | 357 | 2.3 / 3.0 / 16.5 | 2.4 | 3.1 | 2.5 | 6.9 / 7.1 / 16.5 | 1.9 / 2.3 / 15.9 | main 99%, gpu 1% | 7-16 | PASS |
| T-NFR-14 static 100k render=default dpr=1 | 1 | 1254x382 / 1334x420 | pixels | 357 | 3.4 / 4.1 / 5.1 | 3.4 | 4.2 | 3.6 | 6.9 / 7.0 / 7.7 | 3.0 / 3.4 / 3.9 | main 100%, gpu 0% | 6-16 | PASS |
| T-NFR-14 static 100k worst render=default color=service y=log dpr=1 | 1 | 1254x382 / 1334x420 | pixels | 357 | 3.8 / 4.7 / 5.4 | 3.9 | 5.1 | 4.2 | 6.9 / 7.1 / 7.2 | 3.4 / 4.1 / 5.0 | main 100%, gpu 0% | 3-5 | PASS |
| T-NFR-14 realtime 100k + 500/s render=default dpr=1 | 1 | 1254x382 / 1334x420 | pixels | 297 | 1.2 / 2.8 / 10.2 | 1.5 | 3.3 | 1.6 | 95.1 / 111 / 115 | 0.2 / 1.5 / 2.3 | main 88%, gpu 11% | 4-17 | PASS |
| T-NFR-14 interaction 100k hover+drag render=default dpr=1 | 1 | 1254x382 / 1334x420 | pixels | 513 | 1.4 / 3.2 / 8.7 | 1.8 | 3.3 | 1.8 | 7.0 / 13.6 / 83.2 | 0.3 / 0.5 / 0.6 | main 100% | 12-14 | PASS |
| T-NFR-14 static 10k render=default dpr=2 | 2 | 2508x764 / 2668x840 | pixels | 357 | 2.3 / 3.0 / 6.7 | 2.4 | 3.1 | 2.7 | 6.9 / 7.1 / 7.2 | 1.7 / 2.2 / 3.0 | gpu 75%, main 25% | 14-19 | PASS |
| T-NFR-14 static 50k render=default dpr=2 | 2 | 2508x764 / 2668x840 | pixels | 357 | 4.3 / 5.1 / 6.2 | 4.4 | 5.3 | 4.4 | 6.9 / 7.1 / 7.2 | 3.8 / 4.5 / 5.1 | main 100%, gpu 0% | 12-20 | PASS |
| T-NFR-14 static 100k render=default dpr=2 | 2 | 2508x764 / 2668x840 | pixels | 357 | 6.5 / 7.5 / 9.7 | 6.6 | 8.1 | 6.9 | 6.9 / 7.7 / 9.7 | 6.0 / 6.9 / 9.2 | main 100% | 15-18 | PASS |
| T-NFR-14 static 100k worst render=default color=service y=log dpr=2 | 2 | 2508x764 / 2668x840 | pixels | 357 | 7.6 / 9.3 / 13.3 | 7.8 | 9.9 | 7.9 | 7.6 / 9.3 / 13.3 | 7.2 / 8.8 / 12.9 | main 100% | 14-17 | PASS |
| T-NFR-14 realtime 100k + 500/s render=default dpr=2 | 2 | 2508x764 / 2668x840 | pixels | 297 | 1.7 / 3.7 / 10.2 | 2.1 | 3.8 | 2.1 | 95.2 / 111 / 119 | 0.7 / 2.5 / 3.6 | main 83%, gpu 17% | 7-12 | PASS |
| T-NFR-14 interaction 100k hover+drag render=default dpr=2 | 2 | 2508x764 / 2668x840 | pixels | 513 | 2.5 / 4.2 / 14.2 | 2.7 | 4.5 | 3.0 | 7.0 / 13.7 / 85.0 | 0.4 / 0.6 / 0.8 | gpu 64%, main 36% | 15-18 | PASS |

Selection timing (not judged): query = hit index check + rectangle query, the docs/15 §7.3 "선택 계산" (budget 5 ms); compute = snapshot copy + summary; commit = pointer up to the React commit of summary and list.

| Scenario | DPR | Selections | Points | Query p50 / p95 / max (ms) | Query within budget | Compute p50 / p95 / max (ms) | Commit p50 / p95 / max (ms) | CPU load before (%) |
|---|---|---|---|---|---|---|---|---|
| selection 100k full-plot drag render=default dpr=1 (not judged) | 1 | 15 | 100000 | 0.7 / 2.1 / 2.1 | p95 <= 5 ms | 5.9 / 9.3 / 9.3 | 44.0 / 62.0 / 62.0 | 12-14 |
| selection 100k full-plot drag render=default dpr=2 (not judged) | 2 | 15 | 100000 | 0.8 / 5.0 / 5.0 | p95 <= 5 ms | 5.8 / 16.4 / 16.4 | 43.1 / 65.4 / 65.4 | 12-19 |

## T-NFR-14 static 10k render=default dpr=1

| Run | CPU before / during (%) | Quiet | Frame time p50 / p95 / max | Interval p50 / p95 / max | JS p50 / p95 / max | p95 busy per thread | Untraced interval p50 / p95 / max | Untraced JS p50 / p95 / max |
|---|---|---|---|---|---|---|---|---|
| 1 | 5 / 16 | yes | 1.3 / 1.8 / 5.5 | 6.9 / 7.1 / 9.1 | 0.9 / 1.2 / 1.4 | main 1.7, compositor 0.3, gpu 1.1, viz 0.4 | 6.9 / 7.0 / 7.1 | 0.8 / 1.1 / 1.2 |
| 2 | 10 / 18 | yes | 1.2 / 1.6 / 3.5 | 6.9 / 7.1 / 7.2 | 0.8 / 1.0 / 1.1 | main 1.5, compositor 0.3, gpu 1.0, viz 0.4 | 6.9 / 7.0 / 7.1 | 0.8 / 1.1 / 1.2 |
| 3 | 14 / 14 | yes | 1.2 / 1.7 / 2.1 | 6.9 / 7.1 / 7.2 | 0.8 / 1.0 / 1.5 | main 1.7, compositor 0.3, gpu 1.1, viz 0.4 | 6.9 / 7.0 / 14.2 | 0.8 / 1.1 / 1.8 |

## T-NFR-14 static 50k render=default dpr=1

| Run | CPU before / during (%) | Quiet | Frame time p50 / p95 / max | Interval p50 / p95 / max | JS p50 / p95 / max | p95 busy per thread | Untraced interval p50 / p95 / max | Untraced JS p50 / p95 / max |
|---|---|---|---|---|---|---|---|---|
| 1 | 7 / 25 | yes | 2.3 / 3.1 / 4.4 | 6.9 / 7.1 / 7.2 | 1.9 / 2.5 / 2.7 | main 3.0, compositor 0.4, gpu 1.3, viz 0.4 | 6.9 / 7.0 / 7.1 | 1.8 / 2.1 / 2.3 |
| 2 | 16 / 37 | yes | 2.3 / 2.9 / 16.5 | 6.9 / 7.1 / 16.5 | 1.9 / 2.3 / 15.9 | main 2.9, compositor 0.4, gpu 1.3, viz 0.5 | 6.9 / 7.1 / 7.1 | 2.3 / 2.8 / 3.2 |
| 3 | 16 / 24 | yes | 2.2 / 2.9 / 4.3 | 6.9 / 7.0 / 7.1 | 1.9 / 2.1 / 2.6 | main 2.8, compositor 0.3, gpu 1.3, viz 0.4 | 6.9 / 7.1 / 7.2 | 1.8 / 2.1 / 2.6 |

## T-NFR-14 static 100k render=default dpr=1

| Run | CPU before / during (%) | Quiet | Frame time p50 / p95 / max | Interval p50 / p95 / max | JS p50 / p95 / max | p95 busy per thread | Untraced interval p50 / p95 / max | Untraced JS p50 / p95 / max |
|---|---|---|---|---|---|---|---|---|
| 1 | 16 / 11 | yes | 3.3 / 4.0 / 5.1 | 6.9 / 7.0 / 7.1 | 3.0 / 3.2 / 3.7 | main 4.0, compositor 0.3, gpu 1.1, viz 0.3 | 6.9 / 7.0 / 7.1 | 3.0 / 3.2 / 3.6 |
| 2 | 6 / 7 | yes | 3.3 / 3.7 / 4.2 | 6.9 / 7.0 / 7.2 | 2.9 / 3.1 / 3.2 | main 3.7, compositor 0.3, gpu 0.9, viz 0.3 | 6.9 / 7.0 / 7.1 | 3.0 / 3.1 / 3.3 |
| 3 | 12 / 29 | yes | 3.5 / 4.2 / 4.7 | 6.9 / 7.0 / 7.7 | 3.1 / 3.6 / 3.9 | main 4.2, compositor 0.4, gpu 1.4, viz 0.4 | 6.9 / 7.1 / 7.2 | 3.0 / 3.3 / 4.6 |

## T-NFR-14 static 100k worst render=default color=service y=log dpr=1

| Run | CPU before / during (%) | Quiet | Frame time p50 / p95 / max | Interval p50 / p95 / max | JS p50 / p95 / max | p95 busy per thread | Untraced interval p50 / p95 / max | Untraced JS p50 / p95 / max |
|---|---|---|---|---|---|---|---|---|
| 1 | 5 / 15 | yes | 4.1 / 5.1 / 5.4 | 6.9 / 7.1 / 7.1 | 3.8 / 4.3 / 5.0 | main 5.1, compositor 0.3, gpu 1.1, viz 0.4 | 6.9 / 7.0 / 7.1 | 3.3 / 3.5 / 3.8 |
| 2 | 5 / 17 | yes | 3.7 / 4.2 / 4.9 | 6.9 / 7.1 / 7.2 | 3.3 / 3.7 / 4.3 | main 4.2, compositor 0.4, gpu 1.2, viz 0.4 | 6.9 / 7.1 / 7.1 | 3.3 / 3.5 / 3.6 |
| 3 | 3 / 25 | yes | 3.7 / 4.5 / 5.0 | 6.9 / 7.0 / 7.1 | 3.4 / 4.0 / 4.5 | main 4.5, compositor 0.3, gpu 1.2, viz 0.4 | 6.9 / 7.0 / 7.1 | 3.3 / 3.5 / 4.3 |

## T-NFR-14 realtime 100k + 500/s render=default dpr=1

| Run | CPU before / during (%) | Quiet | Frame time p50 / p95 / max | Interval p50 / p95 / max | JS p50 / p95 / max | p95 busy per thread | Untraced interval p50 / p95 / max | Untraced JS p50 / p95 / max |
|---|---|---|---|---|---|---|---|---|
| 1 | 4 / 12 | yes | 1.2 / 2.5 / 3.5 | 95.1 / 111 / 114 | 0.2 / 1.5 / 2.3 | main 2.5, compositor 0.7, gpu 1.2, viz 0.7 | - | 0.2 / 1.6 / 1.9 |
| 2 | 17 / 23 | yes | 1.2 / 3.3 / 10.2 | 95.1 / 111 / 114 | 0.2 / 1.6 / 2.1 | main 3.3, compositor 0.7, gpu 1.3, viz 0.7 | - | 0.2 / 1.7 / 2.3 |
| 3 | 14 / 19 | yes | 1.2 / 2.8 / 3.7 | 95.3 / 111 / 115 | 0.2 / 1.4 / 2.2 | main 2.8, compositor 0.8, gpu 1.3, viz 0.7 | - | 0.2 / 1.8 / 2.3 |

- run 1: 102 frames (0 full redraws), points 100788 -> 104177
- run 2: 102 frames (0 full redraws), points 104525 -> 107806
- run 3: 101 frames (0 full redraws), points 108153 -> 111566

## T-NFR-14 interaction 100k hover+drag render=default dpr=1

| Run | CPU before / during (%) | Quiet | Frame time p50 / p95 / max | Interval p50 / p95 / max | JS p50 / p95 / max | p95 busy per thread | Untraced interval p50 / p95 / max | Untraced JS p50 / p95 / max |
|---|---|---|---|---|---|---|---|---|
| 1 | 12 / 26 | yes | 1.4 / 3.3 / 7.9 | 7.0 / 13.6 / 83.0 | 0.3 / 0.5 / 0.6 | main 3.3, compositor 0.6, gpu 1.1, viz 0.5 | - | - |
| 2 | 14 / 32 | yes | 1.4 / 3.3 / 8.4 | 7.0 / 13.5 / 82.9 | 0.4 / 0.5 / 0.6 | main 3.3, compositor 0.7, gpu 1.3, viz 0.6 | - | - |
| 3 | 13 / 30 | yes | 1.4 / 2.8 / 8.7 | 7.0 / 13.6 / 83.2 | 0.3 / 0.4 / 0.6 | main 2.8, compositor 0.7, gpu 1.2, viz 0.5 | - | - |

- run 1: selection of 1544 points, query 0.60 ms, compute 1.8 ms, React commit 67.1 ms (not a canvas frame, not judged)
- run 1: 172 frames, point layer redrawn in 0
- run 2: selection of 1544 points, query 0.30 ms, compute 1.1 ms, React commit 49.3 ms (not a canvas frame, not judged)
- run 2: 172 frames, point layer redrawn in 0
- run 3: selection of 1544 points, query 0.40 ms, compute 1.0 ms, React commit 48.2 ms (not a canvas frame, not judged)
- run 3: 172 frames, point layer redrawn in 0
- run 1: frames around the end of the drag (pointer up, React commit of summary and list, next hover frames; not judged): p50 / p95 / max 9.0 / 45.1 / 182 ms
- run 2: frames around the end of the drag (pointer up, React commit of summary and list, next hover frames; not judged): p50 / p95 / max 9.5 / 73.0 / 138 ms
- run 3: frames around the end of the drag (pointer up, React commit of summary and list, next hover frames; not judged): p50 / p95 / max 8.6 / 78.5 / 147 ms

## T-NFR-14 static 10k render=default dpr=2

| Run | CPU before / during (%) | Quiet | Frame time p50 / p95 / max | Interval p50 / p95 / max | JS p50 / p95 / max | p95 busy per thread | Untraced interval p50 / p95 / max | Untraced JS p50 / p95 / max |
|---|---|---|---|---|---|---|---|---|
| 1 | 14 / 29 | yes | 2.2 / 3.1 / 6.7 | 6.9 / 7.1 / 7.1 | 1.7 / 2.2 / 2.7 | main 3.0, compositor 0.4, gpu 2.7, viz 0.5 | 6.9 / 7.0 / 7.1 | 1.6 / 1.9 / 2.3 |
| 2 | 19 / 33 | yes | 2.6 / 3.1 / 5.5 | 6.9 / 7.0 / 7.2 | 2.0 / 2.3 / 2.6 | main 3.1, compositor 0.4, gpu 2.8, viz 0.6 | 6.9 / 7.1 / 7.1 | 1.6 / 2.3 / 2.6 |
| 3 | 14 / 26 | yes | 2.2 / 2.8 / 3.5 | 6.9 / 7.1 / 7.2 | 1.6 / 2.1 / 3.0 | main 2.8, compositor 0.4, gpu 2.6, viz 0.5 | 6.9 / 7.0 / 7.1 | 1.6 / 2.5 / 2.9 |

## T-NFR-14 static 50k render=default dpr=2

| Run | CPU before / during (%) | Quiet | Frame time p50 / p95 / max | Interval p50 / p95 / max | JS p50 / p95 / max | p95 busy per thread | Untraced interval p50 / p95 / max | Untraced JS p50 / p95 / max |
|---|---|---|---|---|---|---|---|---|
| 1 | 20 / 29 | yes | 4.3 / 5.1 / 6.2 | 6.9 / 7.1 / 7.2 | 3.9 / 4.4 / 4.5 | main 5.0, compositor 0.4, gpu 2.7, viz 0.4 | 7.0 / 7.0 / 7.1 | 3.9 / 4.6 / 5.3 |
| 2 | 16 / 32 | yes | 4.2 / 4.9 / 5.7 | 6.9 / 7.1 / 7.2 | 3.9 / 4.3 / 4.8 | main 4.9, compositor 0.4, gpu 2.6, viz 0.4 | 6.9 / 7.0 / 7.1 | 3.6 / 3.9 / 5.4 |
| 3 | 12 / 45 | yes | 4.2 / 5.3 / 5.7 | 7.0 / 7.1 / 7.1 | 3.7 / 4.7 / 5.1 | main 5.3, compositor 0.4, gpu 2.8, viz 0.5 | 6.9 / 7.0 / 7.1 | 3.7 / 4.5 / 5.2 |

## T-NFR-14 static 100k render=default dpr=2

| Run | CPU before / during (%) | Quiet | Frame time p50 / p95 / max | Interval p50 / p95 / max | JS p50 / p95 / max | p95 busy per thread | Untraced interval p50 / p95 / max | Untraced JS p50 / p95 / max |
|---|---|---|---|---|---|---|---|---|
| 1 | 15 / 32 | yes | 6.5 / 7.8 / 9.7 | 6.9 / 7.8 / 9.7 | 6.0 / 6.9 / 9.2 | main 7.8, compositor 0.4, gpu 3.4, viz 0.5 | 6.9 / 7.0 / 7.1 | 6.0 / 6.7 / 7.0 |
| 2 | 18 / 46 | yes | 6.8 / 8.1 / 9.0 | 6.9 / 8.1 / 9.0 | 6.3 / 7.5 / 8.4 | main 8.1, compositor 0.5, gpu 3.5, viz 0.6 | 6.9 / 7.0 / 7.5 | 5.9 / 6.4 / 7.0 |
| 3 | 17 / 29 | yes | 6.2 / 6.7 / 7.3 | 6.9 / 7.1 / 8.0 | 5.8 / 6.2 / 6.9 | main 6.7, compositor 0.4, gpu 3.0, viz 0.5 | 6.9 / 7.0 / 7.1 | 5.8 / 6.2 / 6.5 |

## T-NFR-14 static 100k worst render=default color=service y=log dpr=2

| Run | CPU before / during (%) | Quiet | Frame time p50 / p95 / max | Interval p50 / p95 / max | JS p50 / p95 / max | p95 busy per thread | Untraced interval p50 / p95 / max | Untraced JS p50 / p95 / max |
|---|---|---|---|---|---|---|---|---|
| 1 | 14 / 32 | yes | 7.6 / 9.2 / 13.3 | 7.6 / 9.2 / 13.3 | 7.2 / 8.6 / 12.9 | main 9.2, compositor 0.5, gpu 3.4, viz 0.6 | 7.0 / 13.9 / 14.0 | 7.2 / 7.6 / 8.1 |
| 2 | 17 / 31 | yes | 7.6 / 9.9 / 11.4 | 7.6 / 10.0 / 11.4 | 7.2 / 9.3 / 10.8 | main 9.9, compositor 0.6, gpu 3.5, viz 0.6 | 7.0 / 13.8 / 13.9 | 7.1 / 7.8 / 9.7 |
| 3 | 14 / 31 | yes | 7.6 / 8.9 / 10.3 | 7.6 / 8.9 / 10.3 | 7.2 / 8.4 / 9.5 | main 8.9, compositor 0.5, gpu 3.4, viz 0.6 | 7.0 / 13.9 / 14.0 | 7.2 / 8.7 / 9.7 |

## T-NFR-14 realtime 100k + 500/s render=default dpr=2

| Run | CPU before / during (%) | Quiet | Frame time p50 / p95 / max | Interval p50 / p95 / max | JS p50 / p95 / max | p95 busy per thread | Untraced interval p50 / p95 / max | Untraced JS p50 / p95 / max |
|---|---|---|---|---|---|---|---|---|
| 1 | 7 / 18 | yes | 1.7 / 3.8 / 8.5 | 95.1 / 110 / 114 | 0.7 / 2.2 / 3.2 | main 3.8, compositor 0.8, gpu 2.1, viz 0.8 | - | 0.9 / 3.0 / 7.7 |
| 2 | 12 / 29 | yes | 1.6 / 3.8 / 10.2 | 95.2 / 111 / 112 | 0.7 / 2.5 / 3.6 | main 3.8, compositor 0.8, gpu 2.2, viz 0.8 | - | 0.7 / 2.1 / 3.4 |
| 3 | 11 / 10 | yes | 1.7 / 3.6 / 4.9 | 95.3 / 111 / 119 | 0.7 / 2.6 / 3.0 | main 3.6, compositor 1.0, gpu 2.1, viz 0.9 | - | 0.7 / 2.4 / 4.3 |

- run 1: 102 frames (0 full redraws), points 120904 -> 124239
- run 2: 102 frames (0 full redraws), points 129657 -> 133131
- run 3: 102 frames (0 full redraws), points 133444 -> 136814

## T-NFR-14 interaction 100k hover+drag render=default dpr=2

| Run | CPU before / during (%) | Quiet | Frame time p50 / p95 / max | Interval p50 / p95 / max | JS p50 / p95 / max | p95 busy per thread | Untraced interval p50 / p95 / max | Untraced JS p50 / p95 / max |
|---|---|---|---|---|---|---|---|---|
| 1 | 18 / 24 | yes | 2.2 / 3.1 / 11.1 | 7.0 / 13.6 / 85.0 | 0.4 / 0.5 / 0.8 | main 3.1, compositor 0.7, gpu 2.4, viz 0.6 | - | - |
| 2 | 18 / 67 | yes | 2.6 / 4.5 / 10.2 | 7.0 / 13.8 / 83.8 | 0.4 / 0.6 / 0.7 | main 4.5, compositor 0.9, gpu 3.1, viz 0.8 | - | - |
| 3 | 15 / 68 | yes | 2.6 / 4.2 / 14.2 | 7.0 / 13.9 / 82.9 | 0.4 / 0.6 / 0.7 | main 4.2, compositor 0.9, gpu 3.1, viz 0.8 | - | - |

- run 1: selection of 1544 points, query 0.80 ms, compute 2.2 ms, React commit 81.0 ms (not a canvas frame, not judged)
- run 1: 172 frames, point layer redrawn in 0
- run 2: selection of 1544 points, query 0.50 ms, compute 1.2 ms, React commit 52.9 ms (not a canvas frame, not judged)
- run 2: 172 frames, point layer redrawn in 0
- run 3: selection of 1544 points, query 0.50 ms, compute 1.4 ms, React commit 56.3 ms (not a canvas frame, not judged)
- run 3: 172 frames, point layer redrawn in 0
- run 1: frames around the end of the drag (pointer up, React commit of summary and list, next hover frames; not judged): p50 / p95 / max 14.7 / 105 / 232 ms
- run 2: frames around the end of the drag (pointer up, React commit of summary and list, next hover frames; not judged): p50 / p95 / max 12.3 / 40.0 / 166 ms
- run 3: frames around the end of the drag (pointer up, React commit of summary and list, next hover frames; not judged): p50 / p95 / max 15.9 / 50.2 / 205 ms

## selection 100k full-plot drag render=default dpr=1 (not judged)

| Run | CPU before / during (%) | Quiet | Selections | Query p50 / p95 / max | Compute p50 / p95 / max | Commit p50 / p95 / max |
|---|---|---|---|---|---|---|
| 1 | 14 / 32 | yes | 5 | 1.1 / 2.1 / 2.1 | 6.7 / 9.3 / 9.3 | 46.3 / 62.0 / 62.0 |
| 2 | 12 / 29 | yes | 5 | 0.6 / 0.7 / 0.7 | 5.8 / 8.5 / 8.5 | 44.0 / 48.2 / 48.2 |
| 3 | 14 / 23 | yes | 5 | 0.6 / 0.9 / 0.9 | 5.8 / 5.9 / 5.9 | 40.3 / 43.8 / 43.8 |

## selection 100k full-plot drag render=default dpr=2 (not judged)

| Run | CPU before / during (%) | Quiet | Selections | Query p50 / p95 / max | Compute p50 / p95 / max | Commit p50 / p95 / max |
|---|---|---|---|---|---|---|
| 1 | 12 / 37 | yes | 5 | 4.4 / 5.0 / 5.0 | 9.6 / 16.4 / 16.4 | 58.9 / 65.4 / 65.4 |
| 2 | 19 / 28 | yes | 5 | 0.8 / 1.2 / 1.2 | 5.7 / 7.6 / 7.6 | 43.1 / 43.2 / 43.2 |
| 3 | 19 / 25 | yes | 5 | 0.7 / 0.8 / 0.8 | 5.3 / 5.8 / 5.8 | 41.2 / 42.3 / 42.3 |
