# XLog frame time (T-NFR-14)

- Started: 2026-10-23T11:06:03.430Z
- Machine: Windows_NT 10.0.26200 x64, AMD Ryzen 5 5600H with Radeon Graphics, 12 logical cores, 15 GB
- Browser: chromium 156.0.8078.4 (headless), Playwright 1.64.1, Node v24.14.0
- GPU: ANGLE (AMD, AMD Radeon(TM) Graphics (0x00001638) Direct3D11 vs_5_0 ps_5_0, D3D11)
- Viewport 1366x768, DPR 1, plot 1254x382, refresh 6.9 ms

Frame time = busiest rendering-pipeline thread per frame (Chrome trace). Budget: p95 and mean <= 16 ms.

| Scenario | Mode | Frames | Frame time p50 / p95 / max (ms) | Mean | Interval p50 / p95 / max (ms) | JS p50 / p95 / max (ms) | Busiest thread | CPU load before (%) | Verdict |
|---|---|---|---|---|---|---|---|---|---|
| T-NFR-14 static 10k render=default | path | 357 | 6.9 / 8.0 / 11.3 | 6.7 | 7.0 / 8.0 / 15.9 | 1.5 / 1.9 / 2.7 | gpu 100% | 9-19 | PASS |
| T-NFR-14 static 50k render=default | path | 357 | 32.4 / 74.0 / 80.7 | 41.8 | 32.4 / 74.0 / 80.7 | 7.2 / 9.3 / 21.2 | gpu 99%, main 1% | 19-68 | FAIL |
| T-NFR-14 static 100k render=default | path | 357 | 90.3 / 145 / 161 | 95.3 | 90.3 / 145 / 161 | 11.7 / 15.4 / 19.5 | gpu 99%, main 1% | 2-61 | FAIL |
| T-NFR-14 realtime 100k + 500/s render=default | path | 297 | 1.1 / 3.7 / 7.2 | 1.6 | 94.9 / 111 / 115 | 0.1 / 1.9 / 3.3 | gpu 58%, main 42% | 12-19 | PASS |
| T-NFR-14 interaction 100k hover+drag render=default | path | 513 | 1.9 / 4.0 / 22.5 | 2.2 | 7.0 / 13.5 / 98.0 | 0.4 / 0.6 / 0.8 | main 100%, gpu 0% | 15-49 | PASS |

## T-NFR-14 static 10k render=default

| Run | CPU before / during (%) | Quiet | Frame time p50 / p95 / max | Interval p50 / p95 / max | JS p50 / p95 / max | p95 busy per thread | Untraced interval p50 / p95 / max | Untraced JS p50 / p95 / max |
|---|---|---|---|---|---|---|---|---|
| 1 | 19 / 20 | yes | 6.1 / 7.0 / 8.9 | 6.9 / 7.2 / 12.2 | 1.5 / 1.9 / 2.7 | main 2.7, compositor 0.4, gpu 7.0, viz 0.6 | 6.9 / 7.3 / 14.0 | 1.5 / 2.0 / 2.6 |
| 2 | 9 / 39 | yes | 6.9 / 8.3 / 10.7 | 7.0 / 8.3 / 12.3 | 1.5 / 1.9 / 2.0 | main 2.7, compositor 0.5, gpu 8.3, viz 0.5 | 6.9 / 7.0 / 13.9 | 1.5 / 2.0 / 2.6 |
| 3 | 9 / 34 | yes | 6.9 / 8.3 / 11.3 | 7.0 / 8.3 / 15.9 | 1.5 / 1.9 / 2.1 | main 2.7, compositor 0.5, gpu 8.3, viz 0.5 | 6.9 / 7.1 / 13.9 | 1.5 / 1.8 / 1.9 |

## T-NFR-14 static 50k render=default

| Run | CPU before / during (%) | Quiet | Frame time p50 / p95 / max | Interval p50 / p95 / max | JS p50 / p95 / max | p95 busy per thread | Untraced interval p50 / p95 / max | Untraced JS p50 / p95 / max |
|---|---|---|---|---|---|---|---|---|
| 1 | 19 / 31 | yes | 29.6 / 42.4 / 55.8 | 29.6 / 42.4 / 55.8 | 6.1 / 7.9 / 8.4 | main 9.2, compositor 0.6, gpu 42.4, viz 0.6 | 27.8 / 41.8 / 55.6 | 5.5 / 7.9 / 9.0 |
| 2 | 20 / 28 | yes | 29.2 / 40.4 / 57.9 | 29.2 / 40.4 / 57.9 | 5.4 / 7.8 / 8.0 | main 9.0, compositor 0.6, gpu 40.4, viz 0.5 | 27.8 / 41.7 / 48.8 | 5.3 / 7.7 / 7.9 |
| 3 | 68 / 94 | no (waited 178 s) | 65.0 / 77.0 / 80.7 | 65.0 / 77.0 / 80.7 | 8.1 / 15.0 / 21.2 | main 19.1, compositor 1.5, gpu 77.0, viz 0.7 | 55.6 / 69.4 / 69.5 | 7.7 / 8.8 / 13.4 |

- p95 74.0 ms > 16 ms
- mean 41.8 ms > 16 ms

## T-NFR-14 static 100k render=default

| Run | CPU before / during (%) | Quiet | Frame time p50 / p95 / max | Interval p50 / p95 / max | JS p50 / p95 / max | p95 busy per thread | Untraced interval p50 / p95 / max | Untraced JS p50 / p95 / max |
|---|---|---|---|---|---|---|---|---|
| 1 | 2 / 22 | yes | 65.5 / 83.7 / 96.4 | 65.5 / 84.1 / 96.4 | 9.6 / 14.9 / 15.5 | main 16.5, compositor 0.6, gpu 83.7, viz 0.5 | 97.1 / 132 / 167 | 11.3 / 14.8 / 21.8 |
| 2 | 61 / 72 | no (waited 178 s) | 125 / 152 / 161 | 125 / 152 / 161 | 13.4 / 16.1 / 17.0 | main 19.6, compositor 0.9, gpu 152, viz 0.8 | 125 / 174 / 181 | 13.1 / 17.7 / 22.6 |
| 3 | 19 / 66 | yes | 90.3 / 145 / 150 | 90.3 / 146 / 150 | 12.0 / 15.3 / 19.5 | main 17.7, compositor 0.8, gpu 145, viz 0.8 | 104 / 153 / 162 | 13.3 / 17.2 / 33.7 |

- p95 145.4 ms > 16 ms
- mean 95.3 ms > 16 ms

## T-NFR-14 realtime 100k + 500/s render=default

| Run | CPU before / during (%) | Quiet | Frame time p50 / p95 / max | Interval p50 / p95 / max | JS p50 / p95 / max | p95 busy per thread | Untraced interval p50 / p95 / max | Untraced JS p50 / p95 / max |
|---|---|---|---|---|---|---|---|---|
| 1 | 19 / 29 | yes | 1.1 / 4.6 / 7.2 | 94.9 / 112 / 115 | 0.0 / 1.9 / 3.3 | main 4.6, compositor 1.1, gpu 1.7, viz 1.0 | - | 0.1 / 1.7 / 2.7 |
| 2 | 18 / 18 | yes | 1.1 / 3.5 / 6.5 | 95.0 / 110 / 114 | 0.1 / 1.8 / 2.3 | main 3.5, compositor 1.1, gpu 1.7, viz 0.9 | - | 0.1 / 1.6 / 2.9 |
| 3 | 12 / 43 | yes | 1.1 / 3.8 / 6.5 | 94.8 / 110 / 112 | 0.1 / 2.5 / 3.1 | main 3.8, compositor 1.2, gpu 1.7, viz 1.0 | - | 0.1 / 2.5 / 3.6 |

- run 1: 103 frames (0 full redraws), points 103361 -> 106705
- run 2: 102 frames (0 full redraws), points 128764 -> 132188
- run 3: 104 frames (0 full redraws), points 150000 -> 150001

## T-NFR-14 interaction 100k hover+drag render=default

| Run | CPU before / during (%) | Quiet | Frame time p50 / p95 / max | Interval p50 / p95 / max | JS p50 / p95 / max | p95 busy per thread | Untraced interval p50 / p95 / max | Untraced JS p50 / p95 / max |
|---|---|---|---|---|---|---|---|---|
| 1 | 49 / 63 | no (waited 178 s) | 2.1 / 4.2 / 22.5 | 7.0 / 13.1 / 98.0 | 0.5 / 0.7 / 0.8 | main 4.2, compositor 0.8, gpu 1.8, viz 0.6 | - | - |
| 2 | 17 / 34 | yes | 1.6 / 3.1 / 7.9 | 7.0 / 13.4 / 82.7 | 0.4 / 0.5 / 0.7 | main 3.1, compositor 0.7, gpu 1.3, viz 0.5 | - | - |
| 3 | 15 / 49 | yes | 1.9 / 3.4 / 9.7 | 7.0 / 13.8 / 82.7 | 0.4 / 0.6 / 0.6 | main 3.4, compositor 0.8, gpu 1.4, viz 0.6 | - | - |

- run 1: selection of 1056 points, compute 3.3 ms, React commit 107.9 ms (not a canvas frame, not judged)
- run 1: 172 frames, point layer redrawn in 0
- run 2: selection of 1056 points, compute 0.9 ms, React commit 80.3 ms (not a canvas frame, not judged)
- run 2: 172 frames, point layer redrawn in 0
- run 3: selection of 1056 points, compute 1.0 ms, React commit 45.3 ms (not a canvas frame, not judged)
- run 3: 172 frames, point layer redrawn in 0
