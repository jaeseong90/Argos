# XLog frame time (T-NFR-14)

- Started: 2026-10-23T07:02:55.282Z
- Machine: Windows_NT 10.0.26200 x64, AMD Ryzen 5 5600H with Radeon Graphics, 12 logical cores, 15 GB
- Browser: chromium 156.0.8078.4 (headless), Playwright 1.64.1, Node v24.14.0
- GPU: ANGLE (AMD, AMD Radeon(TM) Graphics (0x00001638) Direct3D11 vs_5_0 ps_5_0, D3D11)
- Viewport 1366x768, DPR 1, plot 1256x384, refresh 6.9 ms

Frame time = busiest rendering-pipeline thread per frame (Chrome trace). Budget: p95 and mean <= 16 ms.

| Scenario | Mode | Frames | Frame time p50 / p95 / max (ms) | Mean | Interval p50 / p95 / max (ms) | JS p50 / p95 / max (ms) | Busiest thread | CPU load before (%) | Verdict |
|---|---|---|---|---|---|---|---|---|---|
| T-NFR-14 static 10k render=default | path | 357 | 6.2 / 7.6 / 28.0 | 6.5 | 6.9 / 7.6 / 28.0 | 1.5 / 1.9 / 17.1 | gpu 100% | 4-9 | PASS |
| T-NFR-14 static 50k render=default | path | 357 | 26.6 / 32.6 / 41.7 | 27.2 | 26.6 / 32.6 / 41.7 | 5.3 / 7.8 / 8.0 | gpu 99%, main 1% | 7-11 | FAIL |
| T-NFR-14 static 100k render=default | path | 357 | 61.8 / 79.1 / 98.7 | 63.7 | 61.9 / 79.1 / 98.7 | 12.3 / 17.9 / 19.4 | gpu 99%, main 1% | 5-12 | FAIL |
| T-NFR-14 realtime 100k + 500/s render=default | path | 297 | 1.0 / 3.4 / 6.0 | 1.6 | 96.9 / 111 / 119 | 0.1 / 1.8 / 2.3 | gpu 62%, main 38% | 3-6 | PASS |
| comparison: realtime 100k + 500/s render=default incremental=0 | path | 297 | 66.7 / 91.3 / 113 | 68.7 | 97.3 / 111 / 115 | 11.9 / 15.2 / 17.7 | gpu 100% | 11-16 | not judged |
| T-NFR-14 interaction 100k hover+drag render=default | path | 516 | 1.5 / 3.2 / 6.8 | 1.9 | 7.0 / 13.4 / 55.4 | 0.4 / 0.6 / 0.8 | main 100% | 3-5 | PASS |

## T-NFR-14 static 10k render=default

| Run | CPU before / during (%) | Quiet | Frame time p50 / p95 / max | Interval p50 / p95 / max | JS p50 / p95 / max | p95 busy per thread | Untraced interval p50 / p95 / max | Untraced JS p50 / p95 / max |
|---|---|---|---|---|---|---|---|---|
| 1 | 9 / 37 | yes | 6.9 / 9.0 / 28.0 | 7.0 / 9.0 / 28.0 | 1.5 / 2.1 / 17.1 | main 3.3, compositor 0.4, gpu 9.0, viz 0.6 | 6.9 / 7.0 / 7.1 | 1.5 / 1.8 / 1.8 |
| 2 | 4 / 16 | yes | 6.0 / 6.8 / 7.1 | 6.9 / 7.1 / 16.2 | 1.5 / 1.7 / 1.9 | main 2.4, compositor 0.3, gpu 6.8, viz 0.7 | 6.9 / 7.0 / 13.9 | 1.5 / 1.8 / 2.0 |
| 3 | 7 / 16 | yes | 6.2 / 7.0 / 7.6 | 6.9 / 7.1 / 10.0 | 1.6 / 1.8 / 1.8 | main 2.4, compositor 0.4, gpu 7.0, viz 0.5 | 6.9 / 7.0 / 13.8 | 1.5 / 1.7 / 1.9 |

## T-NFR-14 static 50k render=default

| Run | CPU before / during (%) | Quiet | Frame time p50 / p95 / max | Interval p50 / p95 / max | JS p50 / p95 / max | p95 busy per thread | Untraced interval p50 / p95 / max | Untraced JS p50 / p95 / max |
|---|---|---|---|---|---|---|---|---|
| 1 | 7 / 17 | yes | 26.7 / 32.4 / 38.8 | 26.7 / 32.4 / 38.8 | 5.3 / 7.9 / 7.9 | main 9.2, compositor 0.5, gpu 32.4, viz 0.5 | 27.8 / 34.7 / 41.6 | 5.2 / 7.8 / 7.9 |
| 2 | 10 / 18 | yes | 26.6 / 34.3 / 41.7 | 26.6 / 34.3 / 41.7 | 5.2 / 7.8 / 8.0 | main 8.8, compositor 0.6, gpu 34.3, viz 0.5 | 27.8 / 34.7 / 55.5 | 5.2 / 7.6 / 7.9 |
| 3 | 11 / 18 | yes | 26.5 / 32.0 / 38.1 | 26.5 / 32.0 / 38.1 | 5.3 / 7.8 / 7.9 | main 8.7, compositor 0.6, gpu 32.0, viz 0.5 | 27.8 / 34.7 / 34.8 | 5.3 / 7.6 / 7.8 |

- p95 32.6 ms > 16 ms
- mean 27.2 ms > 16 ms

## T-NFR-14 static 100k render=default

| Run | CPU before / during (%) | Quiet | Frame time p50 / p95 / max | Interval p50 / p95 / max | JS p50 / p95 / max | p95 busy per thread | Untraced interval p50 / p95 / max | Untraced JS p50 / p95 / max |
|---|---|---|---|---|---|---|---|---|
| 1 | 12 / 17 | yes | 61.7 / 82.5 / 98.7 | 61.8 / 82.5 / 98.7 | 12.5 / 17.9 / 18.1 | main 19.8, compositor 0.6, gpu 82.5, viz 0.6 | 62.5 / 76.4 / 97.3 | 12.6 / 17.8 / 18.2 |
| 2 | 7 / 20 | yes | 64.0 / 82.2 / 97.7 | 64.0 / 82.2 / 97.7 | 12.4 / 18.0 / 19.4 | main 20.3, compositor 0.6, gpu 82.2, viz 0.6 | 62.5 / 76.4 / 90.4 | 12.2 / 17.7 / 18.1 |
| 3 | 5 / 15 | yes | 60.7 / 75.8 / 92.8 | 60.8 / 75.8 / 92.8 | 11.8 / 17.5 / 17.7 | main 19.0, compositor 0.6, gpu 75.8, viz 0.6 | 62.5 / 90.1 / 97.2 | 11.9 / 17.4 / 18.0 |

- p95 79.1 ms > 16 ms
- mean 63.7 ms > 16 ms

## T-NFR-14 realtime 100k + 500/s render=default

| Run | CPU before / during (%) | Quiet | Frame time p50 / p95 / max | Interval p50 / p95 / max | JS p50 / p95 / max | p95 busy per thread | Untraced interval p50 / p95 / max | Untraced JS p50 / p95 / max |
|---|---|---|---|---|---|---|---|---|
| 1 | 6 / 7 | yes | 0.9 / 3.2 / 6.0 | 98.4 / 111 / 111 | 0.1 / 1.8 / 2.2 | main 3.2, compositor 0.9, gpu 1.8, viz 0.8 | - | 0.1 / 1.5 / 2.2 |
| 2 | 6 / 18 | yes | 1.0 / 3.9 / 5.5 | 96.1 / 111 / 115 | 0.1 / 2.0 / 2.2 | main 3.9, compositor 1.0, gpu 1.9, viz 0.9 | - | 0.1 / 1.6 / 2.1 |
| 3 | 3 / 7 | yes | 1.0 / 3.4 / 4.8 | 96.8 / 113 / 119 | 0.1 / 1.5 / 2.3 | main 3.4, compositor 1.0, gpu 1.8, viz 0.9 | - | 0.1 / 1.4 / 2.1 |

- run 1: 102 frames (0 full redraws), points 100790 -> 104215
- run 2: 102 frames (0 full redraws), points 104559 -> 107872
- run 3: 102 frames (0 full redraws), points 108185 -> 111604

## comparison: realtime 100k + 500/s render=default incremental=0

| Run | CPU before / during (%) | Quiet | Frame time p50 / p95 / max | Interval p50 / p95 / max | JS p50 / p95 / max | p95 busy per thread | Untraced interval p50 / p95 / max | Untraced JS p50 / p95 / max |
|---|---|---|---|---|---|---|---|---|
| 1 | 11 / 15 | yes | 59.6 / 93.8 / 113 | 97.6 / 111 / 113 | 11.7 / 14.4 / 17.5 | main 17.1, compositor 0.8, gpu 93.8, viz 1.0 | - | 11.7 / 14.7 / 16.6 |
| 2 | 13 / 15 | yes | 67.4 / 92.7 / 107 | 97.2 / 111 / 114 | 11.8 / 16.1 / 17.2 | main 19.0, compositor 1.0, gpu 92.7, viz 1.0 | - | 12.4 / 14.2 / 16.6 |
| 3 | 16 / 12 | yes | 67.8 / 81.2 / 93.4 | 97.3 / 112 / 115 | 12.2 / 14.2 / 17.7 | main 18.5, compositor 0.8, gpu 81.2, viz 0.9 | - | 12.1 / 14.4 / 15.9 |

- run 1: 103 frames (103 full redraws), points 100789 -> 104209
- run 2: 102 frames (102 full redraws), points 107033 -> 110395
- run 3: 103 frames (103 full redraws), points 110712 -> 114166

## T-NFR-14 interaction 100k hover+drag render=default

| Run | CPU before / during (%) | Quiet | Frame time p50 / p95 / max | Interval p50 / p95 / max | JS p50 / p95 / max | p95 busy per thread | Untraced interval p50 / p95 / max | Untraced JS p50 / p95 / max |
|---|---|---|---|---|---|---|---|---|
| 1 | 3 / 11 | yes | 1.5 / 3.5 / 6.4 | 7.0 / 13.4 / 55.2 | 0.4 / 0.6 / 0.8 | main 3.5, compositor 0.8, gpu 1.3, viz 0.5 | - | - |
| 2 | 3 / 14 | yes | 1.5 / 3.0 / 6.8 | 7.0 / 13.5 / 55.4 | 0.4 / 0.5 / 0.7 | main 3.0, compositor 0.8, gpu 1.2, viz 0.5 | - | - |
| 3 | 5 / 14 | yes | 1.6 / 2.9 / 5.5 | 7.0 / 13.4 / 55.0 | 0.4 / 0.5 / 0.7 | main 2.9, compositor 0.8, gpu 1.2, viz 0.6 | - | - |

- run 1: selection of 1098 points, compute 1.9 ms, React commit 85.3 ms (not a canvas frame, not judged)
- run 1: 173 frames, point layer redrawn in 0
- run 2: selection of 1098 points, compute 1.5 ms, React commit 63.0 ms (not a canvas frame, not judged)
- run 2: 173 frames, point layer redrawn in 0
- run 3: selection of 1098 points, compute 1.2 ms, React commit 54.8 ms (not a canvas frame, not judged)
- run 3: 173 frames, point layer redrawn in 0
