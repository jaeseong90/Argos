# XLog frame time (T-NFR-14)

- Started: 2026-10-23T10:25:29.226Z
- Machine: Windows_NT 10.0.26200 x64, AMD Ryzen 5 5600H with Radeon Graphics, 12 logical cores, 15 GB
- Browser: chromium 156.0.8078.4 (headless), Playwright 1.64.1, Node v24.14.0
- GPU: ANGLE (AMD, AMD Radeon(TM) Graphics (0x00001638) Direct3D11 vs_5_0 ps_5_0, D3D11)
- Viewport 1366x768, DPR 1, plot 1256x384, refresh 6.9 ms

Frame time = busiest rendering-pipeline thread per frame (Chrome trace). Budget: p95 and mean <= 16 ms.

| Scenario | Mode | Frames | Frame time p50 / p95 / max (ms) | Mean | Interval p50 / p95 / max (ms) | JS p50 / p95 / max (ms) | Busiest thread | CPU load before (%) | Verdict |
|---|---|---|---|---|---|---|---|---|---|
| T-NFR-14 static 10k render=default | path | 357 | 6.5 / 7.0 / 8.0 | 6.4 | 6.9 / 7.1 / 18.1 | 1.3 / 1.8 / 2.1 | gpu 100%, main 0% | 14-17 | PASS |
| T-NFR-14 static 50k render=default | path | 357 | 32.9 / 70.8 / 84.5 | 40.7 | 32.9 / 70.8 / 84.5 | 6.3 / 9.9 / 17.8 | gpu 99%, main 1% | 4-77 | FAIL |
| T-NFR-14 static 100k render=default | path | 357 | 90.9 / 130 / 142 | 89.1 | 90.9 / 130 / 142 | 11.5 / 15.4 / 16.7 | gpu 99%, main 1% | 9-17 | FAIL |
| T-NFR-14 realtime 100k + 500/s render=default | path | 300 | 1.1 / 3.9 / 5.8 | 1.6 | 94.8 / 111 / 117 | 0.1 / 1.7 / 2.5 | gpu 62%, main 38% | 13-19 | PASS |
| comparison: realtime 100k + 500/s render=default incremental=0 | path | 247 | 110 / 178 / 201 | 117 | 111 / 178 / 201 | 14.2 / 20.4 / 29.8 | gpu 100%, main 0% | 30-74 | not judged |
| T-NFR-14 interaction 100k hover+drag render=default | path | 516 | 1.3 / 3.4 / 11.6 | 1.8 | 7.0 / 13.4 / 69.5 | 0.3 / 0.5 / 1.3 | main 100% | 3-12 | PASS |

## T-NFR-14 static 10k render=default

| Run | CPU before / during (%) | Quiet | Frame time p50 / p95 / max | Interval p50 / p95 / max | JS p50 / p95 / max | p95 busy per thread | Untraced interval p50 / p95 / max | Untraced JS p50 / p95 / max |
|---|---|---|---|---|---|---|---|---|
| 1 | 17 / 37 | yes | 6.7 / 7.0 / 7.8 | 6.9 / 7.1 / 18.1 | 1.4 / 1.7 / 2.0 | main 2.4, compositor 0.4, gpu 7.0, viz 0.5 | 6.9 / 7.0 / 7.1 | 1.4 / 1.6 / 1.6 |
| 2 | 16 / 26 | yes | 6.1 / 7.0 / 7.3 | 6.9 / 7.1 / 7.4 | 1.3 / 1.7 / 2.0 | main 2.4, compositor 0.4, gpu 7.0, viz 0.6 | 6.9 / 7.1 / 13.8 | 1.3 / 1.7 / 1.8 |
| 3 | 14 / 39 | yes | 6.9 / 7.3 / 8.0 | 6.9 / 7.3 / 8.0 | 1.3 / 1.8 / 2.1 | main 2.5, compositor 0.4, gpu 7.3, viz 0.6 | 6.9 / 7.0 / 18.1 | 1.4 / 1.9 / 2.0 |

## T-NFR-14 static 50k render=default

| Run | CPU before / during (%) | Quiet | Frame time p50 / p95 / max | Interval p50 / p95 / max | JS p50 / p95 / max | p95 busy per thread | Untraced interval p50 / p95 / max | Untraced JS p50 / p95 / max |
|---|---|---|---|---|---|---|---|---|
| 1 | 77 / 93 | no (waited 57 s) | 63.2 / 74.2 / 84.5 | 63.2 / 74.2 / 84.5 | 8.1 / 13.4 / 17.8 | main 16.3, compositor 1.5, gpu 74.2, viz 0.7 | 62.5 / 76.3 / 83.4 | 7.9 / 10.9 / 15.2 |
| 2 | 18 / 53 | yes | 32.5 / 44.7 / 53.2 | 32.5 / 44.7 / 53.2 | 5.7 / 7.9 / 9.0 | main 9.3, compositor 0.7, gpu 44.7, viz 0.6 | 41.6 / 62.5 / 62.6 | 6.9 / 8.2 / 8.6 |
| 3 | 4 / 24 | yes | 26.7 / 32.5 / 36.2 | 26.7 / 32.5 / 36.2 | 5.0 / 7.7 / 7.9 | main 8.8, compositor 0.5, gpu 32.5, viz 0.5 | 27.8 / 34.8 / 48.6 | 5.0 / 7.6 / 7.9 |

- p95 70.8 ms > 16 ms
- mean 40.7 ms > 16 ms

## T-NFR-14 static 100k render=default

| Run | CPU before / during (%) | Quiet | Frame time p50 / p95 / max | Interval p50 / p95 / max | JS p50 / p95 / max | p95 busy per thread | Untraced interval p50 / p95 / max | Untraced JS p50 / p95 / max |
|---|---|---|---|---|---|---|---|---|
| 1 | 14 / 60 | yes | 93.9 / 133 / 137 | 93.9 / 133 / 138 | 11.5 / 15.4 / 16.7 | main 18.2, compositor 0.7, gpu 133, viz 0.7 | 111 / 153 / 174 | 13.4 / 15.6 / 17.4 |
| 2 | 17 / 60 | yes | 94.4 / 131 / 142 | 94.4 / 131 / 142 | 11.8 / 15.4 / 16.2 | main 17.9, compositor 0.7, gpu 131, viz 0.7 | 62.6 / 125 / 139 | 10.5 / 15.5 / 15.9 |
| 3 | 9 / 56 | yes | 74.5 / 128 / 134 | 74.5 / 128 / 135 | 10.8 / 15.7 / 15.9 | main 17.8, compositor 0.7, gpu 128, viz 0.7 | 111 / 146 / 167 | 14.1 / 16.0 / 17.7 |

- p95 129.6 ms > 16 ms
- mean 89.1 ms > 16 ms

## T-NFR-14 realtime 100k + 500/s render=default

| Run | CPU before / during (%) | Quiet | Frame time p50 / p95 / max | Interval p50 / p95 / max | JS p50 / p95 / max | p95 busy per thread | Untraced interval p50 / p95 / max | Untraced JS p50 / p95 / max |
|---|---|---|---|---|---|---|---|---|
| 1 | 18 / 48 | yes | 1.1 / 4.0 / 4.9 | 94.8 / 111 / 117 | 0.1 / 1.7 / 2.0 | main 4.0, compositor 1.1, gpu 1.8, viz 1.0 | - | 0.1 / 1.5 / 1.8 |
| 2 | 13 / 46 | yes | 1.1 / 3.8 / 5.8 | 94.7 / 111 / 111 | 0.1 / 1.7 / 2.5 | main 3.8, compositor 1.1, gpu 1.9, viz 1.1 | - | 0.1 / 1.4 / 2.0 |
| 3 | 19 / 34 | yes | 1.0 / 3.4 / 5.4 | 94.8 / 111 / 113 | 0.0 / 1.9 / 2.3 | main 3.4, compositor 1.1, gpu 1.9, viz 1.0 | - | 0.0 / 2.2 / 2.4 |

- run 1: 105 frames (0 full redraws), points 100801 -> 104230
- run 2: 104 frames (0 full redraws), points 112939 -> 116337
- run 3: 102 frames (0 full redraws), points 123358 -> 126634

## comparison: realtime 100k + 500/s render=default incremental=0

| Run | CPU before / during (%) | Quiet | Frame time p50 / p95 / max | Interval p50 / p95 / max | JS p50 / p95 / max | p95 busy per thread | Untraced interval p50 / p95 / max | Untraced JS p50 / p95 / max |
|---|---|---|---|---|---|---|---|---|
| 1 | 50 / 48 | no (waited 57 s) | 95.0 / 148 / 164 | 97.6 / 148 / 164 | 13.0 / 16.8 / 18.9 | main 21.6, compositor 1.0, gpu 148, viz 1.3 | - | 11.5 / 16.5 / 18.0 |
| 2 | 74 / 81 | no (waited 57 s) | 151 / 192 / 201 | 151 / 192 / 201 | 17.8 / 21.3 / 29.8 | main 28.2, compositor 0.9, gpu 192, viz 1.1 | - | 15.5 / 21.9 / 24.4 |
| 3 | 30 / 33 | no (waited 57 s) | 110 / 138 / 147 | 110 / 139 / 147 | 13.7 / 20.4 / 23.0 | main 23.0, compositor 0.7, gpu 138, viz 0.6 | - | 13.8 / 17.5 / 20.5 |

- run 1: 102 frames (102 full redraws), points 109967 -> 113468
- run 2: 68 frames (68 full redraws), points 123014 -> 126359
- run 3: 91 frames (91 full redraws), points 135892 -> 139271

## T-NFR-14 interaction 100k hover+drag render=default

| Run | CPU before / during (%) | Quiet | Frame time p50 / p95 / max | Interval p50 / p95 / max | JS p50 / p95 / max | p95 busy per thread | Untraced interval p50 / p95 / max | Untraced JS p50 / p95 / max |
|---|---|---|---|---|---|---|---|---|
| 1 | 12 / 33 | yes | 1.5 / 4.9 / 11.6 | 7.0 / 13.5 / 69.5 | 0.4 / 0.6 / 1.3 | main 4.9, compositor 0.9, gpu 1.6, viz 0.7 | - | - |
| 2 | 12 / 9 | yes | 1.2 / 2.7 / 4.7 | 7.0 / 13.3 / 55.2 | 0.3 / 0.4 / 0.6 | main 2.7, compositor 0.6, gpu 1.0, viz 0.5 | - | - |
| 3 | 3 / 10 | yes | 1.2 / 2.6 / 5.0 | 7.0 / 13.5 / 55.1 | 0.3 / 0.4 / 0.5 | main 2.6, compositor 0.6, gpu 1.0, viz 0.5 | - | - |

- run 1: selection of 1098 points, compute 1.6 ms, React commit 70.1 ms (not a canvas frame, not judged)
- run 1: 173 frames, point layer redrawn in 0
- run 2: selection of 1098 points, compute 1.0 ms, React commit 48.8 ms (not a canvas frame, not judged)
- run 2: 173 frames, point layer redrawn in 0
- run 3: selection of 1098 points, compute 0.7 ms, React commit 47.6 ms (not a canvas frame, not judged)
- run 3: 173 frames, point layer redrawn in 0
