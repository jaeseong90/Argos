# XLog frame time (T-NFR-14)

- Started: 2026-10-23T14:31:58.670Z
- Machine: Windows_NT 10.0.26200 x64, AMD Ryzen 5 5600H with Radeon Graphics, 12 logical cores, 15 GB
- Browser: chromium 156.0.8078.4 (headless), Playwright 1.64.1, Node v24.14.0
- GPU: ANGLE (NVIDIA, NVIDIA GeForce RTX 3050 Ti Laptop GPU (0x000025A0) Direct3D11 vs_5_0 ps_5_0, D3D11)
- Viewport 1366x768, DPR 1 and 2 (per scenario), plot 1254x382 CSS px, refresh 13.3 ms

Frame time = busiest rendering-pipeline thread per frame (Chrome trace). Budget: p95 and mean <= 16 ms of the frames of all runs together (pooled). Max run p95 / Max run mean: the highest p95 and mean of a single run (reported, not judged). Canvas px: backing store of the point layer (capped, ADR-0003 C-34) / the overlay.

| Scenario | DPR | Canvas px (points / overlay) | Mode | Frames | Frame time p50 / p95 / max (ms) | Mean | Max run p95 (ms) | Max run mean (ms) | Interval p50 / p95 / max (ms) | JS p50 / p95 / max (ms) | Busiest thread | CPU load before (%) | Verdict |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| T-NFR-14 static 10k render=default dpr=1 | 1 | 1254x382 / 1334x420 | pixels | 357 | 2.4 / 6.5 / 42.2 | 3.2 | 8.7 | 4.4 | 13.3 / 16.4 / 42.2 | 1.4 / 3.6 / 16.2 | main 90%, gpu 9% | 77-100 | PASS |
| T-NFR-14 static 50k render=default dpr=1 | 1 | 1254x382 / 1334x420 | pixels | 357 | 4.0 / 9.6 / 22.9 | 4.8 | 13.7 | 6.0 | 13.3 / 15.6 / 26.9 | 3.1 / 7.0 / 22.3 | main 99%, gpu 1% | 78-100 | PASS |
| T-NFR-14 static 100k render=default dpr=1 | 1 | 1254x382 / 1334x420 | pixels | 357 | 5.6 / 9.4 / 142 | 6.8 | 15.2 | 8.0 | 13.3 / 15.3 / 142 | 4.7 / 7.8 / 16.1 | main 100% | 87-95 | PASS |
| T-NFR-14 static 100k worst render=default color=service y=log dpr=1 | 1 | 1254x382 / 1334x420 | pixels | 357 | 4.3 / 6.2 / 14.9 | 4.5 | 6.7 | 4.9 | 13.3 / 13.5 / 14.9 | 3.7 / 5.2 / 10.2 | main 100% | 12-25 | PASS |
| T-NFR-14 realtime 100k + 500/s render=default dpr=1 | 1 | 1254x382 / 1334x420 | pixels | 298 | 1.0 / 4.0 / 11.8 | 1.7 | 5.4 | 2.0 | 95.5 / 110 / 117 | 0.2 / 2.1 / 11.1 | main 69%, gpu 29% | 6-20 | PASS |
| T-NFR-14 interaction 100k hover+drag render=default dpr=1 | 1 | 1254x382 / 1334x420 | pixels | 513 | 1.8 / 4.0 / 16.6 | 2.3 | 4.1 | 2.4 | 13.3 / 26.2 / 160 | 0.4 / 0.6 / 0.9 | main 100% | 5-18 | PASS |
| T-NFR-14 static 10k render=default dpr=2 | 2 | 2508x764 / 2668x840 | pixels | 357 | 2.4 / 3.4 / 5.5 | 2.6 | 3.7 | 2.9 | 13.3 / 13.5 / 14.7 | 1.8 / 2.3 / 3.1 | gpu 52%, main 48% | 7-19 | PASS |
| T-NFR-14 static 50k render=default dpr=2 | 2 | 2508x764 / 2668x840 | pixels | 357 | 4.3 / 5.3 / 7.8 | 4.4 | 5.9 | 4.7 | 13.3 / 13.6 / 14.5 | 3.7 / 4.5 / 5.5 | main 99%, gpu 1% | 7-14 | PASS |
| T-NFR-14 static 100k render=default dpr=2 | 2 | 2508x764 / 2668x840 | pixels | 357 | 8.3 / 10.9 / 15.3 | 8.6 | 11.7 | 9.6 | 13.3 / 13.8 / 16.0 | 7.6 / 9.8 / 14.6 | main 100% | 12-75 | PASS |
| T-NFR-14 static 100k worst render=default color=service y=log dpr=2 | 2 | 2508x764 / 2668x840 | pixels | 357 | 7.7 / 11.2 / 19.2 | 8.2 | 13.1 | 9.1 | 13.3 / 13.6 / 19.2 | 7.1 / 10.3 / 18.5 | main 100%, gpu 0% | 5-18 | PASS |
| T-NFR-14 realtime 100k + 500/s render=default dpr=2 | 2 | 2508x764 / 2668x840 | pixels | 297 | 1.7 / 3.9 / 9.7 | 2.2 | 4.5 | 2.3 | 95.1 / 111 / 122 | 0.7 / 2.3 / 3.8 | main 59%, gpu 41% | 6-20 | PASS |
| T-NFR-14 interaction 100k hover+drag render=default dpr=2 | 2 | 2508x764 / 2668x840 | pixels | 513 | 3.0 / 4.1 / 17.3 | 3.1 | 4.3 | 3.2 | 13.4 / 26.2 / 160 | 0.5 / 0.7 / 0.9 | gpu 65%, main 35% | 13-16 | PASS |

Selection timing (not judged): query = hit index check + rectangle query, the docs/15 §7.3 "선택 계산" (budget 5 ms); compute = snapshot copy + summary; commit = pointer up to the React commit of summary and list.

| Scenario | DPR | Selections | Points | Query p50 / p95 / max (ms) | Query within budget | Compute p50 / p95 / max (ms) | Commit p50 / p95 / max (ms) | CPU load before (%) |
|---|---|---|---|---|---|---|---|---|
| selection 100k full-plot drag render=default dpr=1 (not judged) | 1 | 15 | 100000 | 0.9 / 4.8 / 4.8 | p95 <= 5 ms | 8.0 / 21.8 / 21.8 | 52.0 / 87.2 / 87.2 | 10-11 |
| selection 100k full-plot drag render=default dpr=2 (not judged) | 2 | 15 | 100000 | 1.0 / 8.5 / 8.5 | p95 > 5 ms | 9.5 / 17.3 / 17.3 | 59.7 / 114 / 114 | 9-18 |

## T-NFR-14 static 10k render=default dpr=1

| Run | CPU before / during (%) | Quiet | Frame time p50 / p95 / max | Interval p50 / p95 / max | JS p50 / p95 / max | p95 busy per thread | Untraced interval p50 / p95 / max | Untraced JS p50 / p95 / max |
|---|---|---|---|---|---|---|---|---|
| 1 | 98 / 98 | no (waited 148 s) | 2.6 / 6.9 / 10.4 | 13.4 / 17.5 / 27.6 | 1.4 / 3.1 / 7.8 | main 6.8, compositor 0.8, gpu 3.1, viz 1.0 | 13.3 / 14.5 / 19.0 | 1.2 / 1.6 / 4.0 |
| 2 | 77 / 61 | no (waited 148 s) | 1.9 / 4.0 / 6.1 | 13.3 / 13.9 / 19.7 | 1.2 / 1.8 / 3.6 | main 3.5, compositor 0.9, gpu 2.1, viz 0.7 | 13.3 / 13.5 / 14.7 | 1.4 / 2.5 / 4.8 |
| 3 | 100 / 100 | no (waited 148 s) | 3.2 / 8.7 / 42.2 | 13.3 / 17.1 / 42.2 | 1.7 / 5.2 / 16.2 | main 8.3, compositor 3.1, gpu 3.4, viz 0.8 | 13.3 / 15.4 / 17.7 | 1.5 / 5.1 / 9.0 |

## T-NFR-14 static 50k render=default dpr=1

| Run | CPU before / during (%) | Quiet | Frame time p50 / p95 / max | Interval p50 / p95 / max | JS p50 / p95 / max | p95 busy per thread | Untraced interval p50 / p95 / max | Untraced JS p50 / p95 / max |
|---|---|---|---|---|---|---|---|---|
| 1 | 100 / 100 | no (waited 149 s) | 4.8 / 13.7 / 22.9 | 13.3 / 15.9 / 22.9 | 3.7 / 10.7 / 22.3 | main 13.7, compositor 1.1, gpu 4.0, viz 0.9 | 13.3 / 13.5 / 14.0 | 3.2 / 5.5 / 8.9 |
| 2 | 87 / 87 | no (waited 149 s) | 4.0 / 7.2 / 13.1 | 13.3 / 15.1 / 26.9 | 3.0 / 5.4 / 8.2 | main 6.9, compositor 0.9, gpu 2.6, viz 0.9 | 13.3 / 13.6 / 17.5 | 2.7 / 5.9 / 9.4 |
| 3 | 78 / 79 | no (waited 148 s) | 3.5 / 7.3 / 13.8 | 13.3 / 15.6 / 20.3 | 2.6 / 5.1 / 8.3 | main 7.3, compositor 0.8, gpu 3.0, viz 0.6 | 13.3 / 13.5 / 14.5 | 2.6 / 4.4 / 7.3 |

## T-NFR-14 static 100k render=default dpr=1

| Run | CPU before / during (%) | Quiet | Frame time p50 / p95 / max | Interval p50 / p95 / max | JS p50 / p95 / max | p95 busy per thread | Untraced interval p50 / p95 / max | Untraced JS p50 / p95 / max |
|---|---|---|---|---|---|---|---|---|
| 1 | 87 / 86 | no (waited 148 s) | 5.3 / 15.2 / 142 | 13.3 / 16.8 / 142 | 4.5 / 8.7 / 16.1 | main 15.2, compositor 1.2, gpu 3.2, viz 1.2 | 13.3 / 13.5 / 27.7 | 4.4 / 6.6 / 11.7 |
| 2 | 88 / 93 | no (waited 147 s) | 6.2 / 9.7 / 13.6 | 13.4 / 15.4 / 16.6 | 5.1 / 8.2 / 12.4 | main 9.7, compositor 1.0, gpu 3.8, viz 1.1 | 13.3 / 13.5 / 13.7 | 4.1 / 5.2 / 11.7 |
| 3 | 95 / 92 | no (waited 148 s) | 5.5 / 7.5 / 8.9 | 13.4 / 14.6 / 15.6 | 4.6 / 6.2 / 8.3 | main 7.5, compositor 0.8, gpu 2.1, viz 0.7 | 13.3 / 13.5 / 14.4 | 4.6 / 6.6 / 10.4 |

## T-NFR-14 static 100k worst render=default color=service y=log dpr=1

| Run | CPU before / during (%) | Quiet | Frame time p50 / p95 / max | Interval p50 / p95 / max | JS p50 / p95 / max | p95 busy per thread | Untraced interval p50 / p95 / max | Untraced JS p50 / p95 / max |
|---|---|---|---|---|---|---|---|---|
| 1 | 25 / 46 | no (waited 148 s) | 4.7 / 6.7 / 14.9 | 13.3 / 13.5 / 14.9 | 4.0 / 5.5 / 10.2 | main 6.7, compositor 0.5, gpu 1.6, viz 0.6 | 13.3 / 13.4 / 13.5 | 3.9 / 4.9 / 5.3 |
| 2 | 12 / 26 | yes | 4.1 / 6.0 / 6.7 | 13.3 / 13.5 / 13.8 | 3.5 / 5.1 / 5.7 | main 6.0, compositor 0.5, gpu 1.5, viz 0.5 | 13.3 / 13.5 / 14.1 | 3.6 / 4.3 / 5.8 |
| 3 | 16 / 30 | yes | 4.3 / 6.2 / 8.7 | 13.3 / 13.5 / 14.3 | 3.7 / 4.7 / 6.4 | main 6.2, compositor 0.5, gpu 1.7, viz 0.5 | 13.3 / 13.4 / 13.5 | 3.6 / 4.2 / 4.5 |

## T-NFR-14 realtime 100k + 500/s render=default dpr=1

| Run | CPU before / during (%) | Quiet | Frame time p50 / p95 / max | Interval p50 / p95 / max | JS p50 / p95 / max | p95 busy per thread | Untraced interval p50 / p95 / max | Untraced JS p50 / p95 / max |
|---|---|---|---|---|---|---|---|---|
| 1 | 20 / 16 | yes | 1.0 / 5.4 / 11.8 | 95.3 / 110 / 112 | 0.2 / 1.8 / 11.1 | main 3.9, compositor 1.0, gpu 1.8, viz 1.1 | - | 0.3 / 1.8 / 2.7 |
| 2 | 6 / 13 | yes | 1.0 / 4.0 / 5.8 | 95.5 / 110 / 115 | 0.2 / 2.1 / 2.6 | main 4.0, compositor 1.1, gpu 1.7, viz 1.1 | - | 0.3 / 2.0 / 2.7 |
| 3 | 6 / 19 | yes | 1.0 / 3.9 / 6.8 | 96.5 / 111 / 117 | 0.2 / 2.3 / 2.4 | main 3.9, compositor 1.1, gpu 1.7, viz 1.1 | - | 0.2 / 2.0 / 2.6 |

- run 1: 103 frames (0 full redraws), points 104150 -> 107478
- run 2: 103 frames (0 full redraws), points 112034 -> 115381
- run 3: 103 frames (0 full redraws), points 116630 -> 119970

## T-NFR-14 interaction 100k hover+drag render=default dpr=1

| Run | CPU before / during (%) | Quiet | Frame time p50 / p95 / max | Interval p50 / p95 / max | JS p50 / p95 / max | p95 busy per thread | Untraced interval p50 / p95 / max | Untraced JS p50 / p95 / max |
|---|---|---|---|---|---|---|---|---|
| 1 | 5 / 15 | yes | 1.8 / 4.1 / 16.6 | 13.3 / 26.4 / 160 | 0.4 / 0.7 / 0.9 | main 4.1, compositor 0.8, gpu 1.3, viz 0.7 | - | - |
| 2 | 18 / 33 | yes | 1.9 / 4.1 / 13.0 | 13.4 / 26.0 / 160 | 0.4 / 0.6 / 0.9 | main 4.1, compositor 0.8, gpu 1.5, viz 0.8 | - | - |
| 3 | 9 / 15 | yes | 1.7 / 3.5 / 10.3 | 13.3 / 26.3 / 160 | 0.4 / 0.6 / 0.6 | main 3.5, compositor 0.8, gpu 1.3, viz 0.6 | - | - |

- run 1: selection of 1544 points, query 0.70 ms, compute 1.8 ms, React commit 92.2 ms (not a canvas frame, not judged)
- run 1: 172 frames, point layer redrawn in 0
- run 2: selection of 1544 points, query 0.40 ms, compute 1.2 ms, React commit 57.6 ms (not a canvas frame, not judged)
- run 2: 172 frames, point layer redrawn in 0
- run 3: selection of 1544 points, query 0.60 ms, compute 1.5 ms, React commit 45.2 ms (not a canvas frame, not judged)
- run 3: 172 frames, point layer redrawn in 0
- run 1: frames around the end of the drag (pointer up, React commit of summary and list, next hover frames; not judged): p50 / p95 / max 11.3 / 116 / 269 ms
- run 2: frames around the end of the drag (pointer up, React commit of summary and list, next hover frames; not judged): p50 / p95 / max 12.3 / 90.1 / 218 ms
- run 3: frames around the end of the drag (pointer up, React commit of summary and list, next hover frames; not judged): p50 / p95 / max 9.3 / 60.7 / 159 ms

## T-NFR-14 static 10k render=default dpr=2

| Run | CPU before / during (%) | Quiet | Frame time p50 / p95 / max | Interval p50 / p95 / max | JS p50 / p95 / max | p95 busy per thread | Untraced interval p50 / p95 / max | Untraced JS p50 / p95 / max |
|---|---|---|---|---|---|---|---|---|
| 1 | 19 / 44 | yes | 2.8 / 3.7 / 5.5 | 13.3 / 13.5 / 14.7 | 2.1 / 2.5 / 3.1 | main 3.4, compositor 0.5, gpu 3.4, viz 0.6 | 13.3 / 13.5 / 13.8 | 2.3 / 2.8 / 3.9 |
| 2 | 7 / 12 | yes | 2.3 / 3.0 / 4.3 | 13.3 / 13.6 / 13.7 | 1.7 / 2.1 / 2.3 | main 2.7, compositor 0.4, gpu 2.6, viz 0.4 | 13.3 / 13.4 / 13.5 | 1.8 / 2.2 / 2.4 |
| 3 | 9 / 17 | yes | 2.3 / 2.8 / 3.6 | 13.3 / 13.6 / 13.7 | 1.7 / 2.1 / 2.3 | main 2.8, compositor 0.4, gpu 2.7, viz 0.5 | 13.3 / 13.4 / 13.5 | 1.8 / 2.2 / 2.4 |

## T-NFR-14 static 50k render=default dpr=2

| Run | CPU before / during (%) | Quiet | Frame time p50 / p95 / max | Interval p50 / p95 / max | JS p50 / p95 / max | p95 busy per thread | Untraced interval p50 / p95 / max | Untraced JS p50 / p95 / max |
|---|---|---|---|---|---|---|---|---|
| 1 | 14 / 52 | yes | 4.6 / 5.9 / 7.8 | 13.3 / 13.6 / 13.8 | 4.0 / 4.7 / 5.5 | main 5.9, compositor 0.5, gpu 3.4, viz 0.6 | 13.3 / 13.4 / 13.5 | 4.1 / 4.7 / 5.0 |
| 2 | 7 / 19 | yes | 4.4 / 5.3 / 5.9 | 13.3 / 13.5 / 14.5 | 3.9 / 4.3 / 4.4 | main 5.3, compositor 0.6, gpu 2.9, viz 0.5 | 13.3 / 13.5 / 13.5 | 4.3 / 4.7 / 5.1 |
| 3 | 9 / 17 | yes | 4.1 / 4.9 / 5.4 | 13.3 / 13.7 / 13.8 | 3.6 / 4.1 / 4.4 | main 4.7, compositor 0.4, gpu 2.9, viz 0.6 | 13.3 / 13.5 / 13.5 | 3.5 / 4.4 / 4.6 |

## T-NFR-14 static 100k render=default dpr=2

| Run | CPU before / during (%) | Quiet | Frame time p50 / p95 / max | Interval p50 / p95 / max | JS p50 / p95 / max | p95 busy per thread | Untraced interval p50 / p95 / max | Untraced JS p50 / p95 / max |
|---|---|---|---|---|---|---|---|---|
| 1 | 20 / 51 | yes | 7.8 / 10.0 / 10.6 | 13.3 / 13.8 / 15.4 | 7.2 / 8.9 / 9.7 | main 10.0, compositor 0.7, gpu 3.8, viz 0.7 | 13.3 / 13.5 / 13.5 | 8.0 / 9.0 / 9.6 |
| 2 | 12 / 51 | yes | 7.8 / 9.9 / 10.9 | 13.3 / 13.6 / 14.2 | 7.2 / 9.3 / 9.7 | main 9.9, compositor 0.6, gpu 3.8, viz 0.6 | 13.3 / 13.4 / 14.9 | 7.8 / 9.6 / 11.0 |
| 3 | 75 / 81 | no (waited 148 s) | 9.5 / 11.7 / 15.3 | 13.3 / 14.4 / 16.0 | 8.8 / 10.5 / 14.6 | main 11.7, compositor 0.7, gpu 6.1, viz 0.9 | 13.3 / 13.5 / 13.6 | 8.7 / 11.2 / 12.4 |

## T-NFR-14 static 100k worst render=default color=service y=log dpr=2

| Run | CPU before / during (%) | Quiet | Frame time p50 / p95 / max | Interval p50 / p95 / max | JS p50 / p95 / max | p95 busy per thread | Untraced interval p50 / p95 / max | Untraced JS p50 / p95 / max |
|---|---|---|---|---|---|---|---|---|
| 1 | 11 / 49 | yes | 8.5 / 13.1 / 19.2 | 13.4 / 14.7 / 19.2 | 8.0 / 12.3 / 18.5 | main 13.1, compositor 0.8, gpu 5.9, viz 0.6 | 13.3 / 13.5 / 15.9 | 9.5 / 11.4 / 12.8 |
| 2 | 5 / 26 | yes | 8.4 / 9.5 / 10.2 | 13.3 / 13.6 / 13.7 | 7.7 / 8.9 / 9.2 | main 9.5, compositor 0.6, gpu 3.4, viz 0.5 | 13.3 / 13.5 / 13.5 | 8.0 / 9.0 / 9.8 |
| 3 | 18 / 24 | yes | 6.9 / 8.4 / 15.9 | 13.3 / 13.6 / 15.9 | 6.3 / 7.5 / 15.4 | main 8.4, compositor 0.5, gpu 3.1, viz 0.5 | 13.3 / 13.5 / 15.9 | 8.1 / 10.4 / 24.1 |

## T-NFR-14 realtime 100k + 500/s render=default dpr=2

| Run | CPU before / during (%) | Quiet | Frame time p50 / p95 / max | Interval p50 / p95 / max | JS p50 / p95 / max | p95 busy per thread | Untraced interval p50 / p95 / max | Untraced JS p50 / p95 / max |
|---|---|---|---|---|---|---|---|---|
| 1 | 6 / 10 | yes | 1.6 / 3.4 / 9.7 | 95.5 / 111 / 121 | 0.7 / 2.2 / 2.5 | main 3.4, compositor 1.1, gpu 2.9, viz 1.1 | - | 0.8 / 2.7 / 3.5 |
| 2 | 20 / 7 | yes | 1.6 / 3.6 / 4.5 | 95.0 / 116 / 122 | 0.7 / 2.2 / 2.7 | main 3.5, compositor 1.1, gpu 2.9, viz 1.0 | - | 0.8 / 2.5 / 3.2 |
| 3 | 8 / 26 | yes | 1.7 / 4.5 / 6.8 | 95.3 / 112 / 120 | 0.7 / 2.8 / 3.8 | main 4.5, compositor 1.2, gpu 3.1, viz 1.2 | - | 0.7 / 2.3 / 2.7 |

- run 1: 102 frames (0 full redraws), points 102477 -> 105767
- run 2: 102 frames (0 full redraws), points 106082 -> 109528
- run 3: 102 frames (0 full redraws), points 114082 -> 117538

## T-NFR-14 interaction 100k hover+drag render=default dpr=2

| Run | CPU before / during (%) | Quiet | Frame time p50 / p95 / max | Interval p50 / p95 / max | JS p50 / p95 / max | p95 busy per thread | Untraced interval p50 / p95 / max | Untraced JS p50 / p95 / max |
|---|---|---|---|---|---|---|---|---|
| 1 | 13 / 28 | yes | 3.1 / 4.1 / 17.3 | 13.3 / 26.3 / 160 | 0.5 / 0.7 / 0.9 | main 4.1, compositor 0.8, gpu 3.7, viz 0.7 | - | - |
| 2 | 15 / 41 | yes | 3.0 / 4.3 / 11.8 | 13.4 / 26.1 / 160 | 0.5 / 0.7 / 0.9 | main 4.3, compositor 0.9, gpu 3.4, viz 0.7 | - | - |
| 3 | 16 / 26 | yes | 2.7 / 3.9 / 11.2 | 13.4 / 26.2 / 160 | 0.4 / 0.6 / 0.7 | main 3.9, compositor 0.8, gpu 3.0, viz 0.7 | - | - |

- run 1: selection of 1544 points, query 0.90 ms, compute 3.3 ms, React commit 128.9 ms (not a canvas frame, not judged)
- run 1: 172 frames, point layer redrawn in 0
- run 2: selection of 1544 points, query 0.50 ms, compute 1.7 ms, React commit 72.6 ms (not a canvas frame, not judged)
- run 2: 172 frames, point layer redrawn in 0
- run 3: selection of 1544 points, query 0.50 ms, compute 1.2 ms, React commit 61.9 ms (not a canvas frame, not judged)
- run 3: 172 frames, point layer redrawn in 0
- run 1: frames around the end of the drag (pointer up, React commit of summary and list, next hover frames; not judged): p50 / p95 / max 17.6 / 77.2 / 369 ms
- run 2: frames around the end of the drag (pointer up, React commit of summary and list, next hover frames; not judged): p50 / p95 / max 19.9 / 70.6 / 262 ms
- run 3: frames around the end of the drag (pointer up, React commit of summary and list, next hover frames; not judged): p50 / p95 / max 15.3 / 92.7 / 197 ms

## selection 100k full-plot drag render=default dpr=1 (not judged)

| Run | CPU before / during (%) | Quiet | Selections | Query p50 / p95 / max | Compute p50 / p95 / max | Commit p50 / p95 / max |
|---|---|---|---|---|---|---|
| 1 | 10 / 23 | yes | 5 | 1.9 / 4.8 / 4.8 | 8.9 / 21.8 / 21.8 | 65.1 / 87.2 / 87.2 |
| 2 | 11 / 18 | yes | 5 | 0.8 / 1.5 / 1.5 | 8.7 / 15.9 / 15.9 | 54.8 / 66.3 / 66.3 |
| 3 | 10 / 23 | yes | 5 | 0.7 / 0.9 / 0.9 | 5.7 / 8.4 / 8.4 | 49.1 / 49.4 / 49.4 |

## selection 100k full-plot drag render=default dpr=2 (not judged)

| Run | CPU before / during (%) | Quiet | Selections | Query p50 / p95 / max | Compute p50 / p95 / max | Commit p50 / p95 / max |
|---|---|---|---|---|---|---|
| 1 | 18 / 37 | yes | 5 | 1.1 / 8.5 / 8.5 | 9.5 / 12.7 / 12.7 | 71.6 / 114 / 114 |
| 2 | 13 / 23 | yes | 5 | 0.7 / 3.8 / 3.8 | 9.8 / 17.3 / 17.3 | 57.1 / 69.4 / 69.4 |
| 3 | 9 / 26 | yes | 5 | 0.8 / 1.0 / 1.0 | 8.0 / 17.2 / 17.2 | 52.1 / 60.0 / 60.0 |
