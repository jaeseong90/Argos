# XLog frame time (T-NFR-14)

- Started: 2026-10-23T10:41:23.110Z
- Machine: Windows_NT 10.0.26200 x64, AMD Ryzen 5 5600H with Radeon Graphics, 12 logical cores, 15 GB
- Browser: chromium 156.0.8078.4 (headless), Playwright 1.64.1, Node v24.14.0
- GPU: ANGLE (AMD, AMD Radeon(TM) Graphics (0x00001638) Direct3D11 vs_5_0 ps_5_0, D3D11)
- Viewport 1366x768, DPR 1, plot 1256x384, refresh 7.0 ms

Frame time = busiest rendering-pipeline thread per frame (Chrome trace). Budget: p95 and mean <= 16 ms.

| Scenario | Mode | Frames | Frame time p50 / p95 / max (ms) | Mean | Interval p50 / p95 / max (ms) | JS p50 / p95 / max (ms) | Busiest thread | CPU load before (%) | Verdict |
|---|---|---|---|---|---|---|---|---|---|
| T-NFR-14 static 10k render=pixels | pixels | 357 | 3.2 / 6.4 / 19.5 | 3.3 | 6.9 / 8.2 / 25.6 | 2.5 / 3.7 / 8.6 | main 98%, gpu 2% | 16-95 | PASS |
| T-NFR-14 static 50k render=pixels | pixels | 357 | 8.4 / 10.9 / 29.3 | 8.3 | 8.4 / 11.0 / 29.3 | 7.9 / 10.1 / 28.6 | main 100% | 16-72 | PASS |
| T-NFR-14 static 100k render=pixels | pixels | 357 | 10.2 / 14.4 / 17.7 | 10.8 | 10.2 / 14.6 / 17.7 | 9.7 / 13.9 / 17.1 | main 100% | 5-17 | PASS |
| T-NFR-14 realtime 100k + 500/s render=pixels | pixels | 302 | 1.8 / 7.9 / 56.9 | 3.0 | 95.5 / 111 / 123 | 0.3 / 2.3 / 10.8 | main 85%, gpu 14% | 7-71 | PASS |
| comparison: realtime 100k + 500/s render=pixels incremental=0 | pixels | 297 | 14.0 / 18.7 / 30.0 | 13.9 | 94.7 / 111 / 119 | 12.0 / 16.4 / 20.7 | main 100% | 13-20 | not judged |
| T-NFR-14 interaction 100k hover+drag render=pixels | pixels | 516 | 2.2 / 6.3 / 27.9 | 2.6 | 7.0 / 15.3 / 84.7 | 0.4 / 0.7 / 1.3 | main 100%, viz 0% | 9-87 | PASS |

## T-NFR-14 static 10k render=pixels

| Run | CPU before / during (%) | Quiet | Frame time p50 / p95 / max | Interval p50 / p95 / max | JS p50 / p95 / max | p95 busy per thread | Untraced interval p50 / p95 / max | Untraced JS p50 / p95 / max |
|---|---|---|---|---|---|---|---|---|
| 1 | 16 / 30 | yes | 2.0 / 2.9 / 4.5 | 6.9 / 7.0 / 15.2 | 1.6 / 1.9 / 2.6 | main 2.5, compositor 0.4, gpu 1.3, viz 0.4 | 6.9 / 7.0 / 13.8 | 1.7 / 2.1 / 2.7 |
| 2 | 95 / 97 | no (waited 57 s) | 3.7 / 8.1 / 11.5 | 7.0 / 9.3 / 13.5 | 2.8 / 6.2 / 8.6 | main 7.7, compositor 1.0, gpu 2.8, viz 0.9 | 6.9 / 7.1 / 14.0 | 3.0 / 6.7 / 10.2 |
| 3 | 91 / 83 | no (waited 57 s) | 3.2 / 4.6 / 19.5 | 6.9 / 7.3 / 25.6 | 2.6 / 2.8 / 4.3 | main 3.8, compositor 0.6, gpu 2.1, viz 0.6 | 6.9 / 7.1 / 13.9 | 1.7 / 2.4 / 2.6 |

## T-NFR-14 static 50k render=pixels

| Run | CPU before / during (%) | Quiet | Frame time p50 / p95 / max | Interval p50 / p95 / max | JS p50 / p95 / max | p95 busy per thread | Untraced interval p50 / p95 / max | Untraced JS p50 / p95 / max |
|---|---|---|---|---|---|---|---|---|
| 1 | 72 / 95 | no (waited 57 s) | 10.0 / 11.6 / 29.3 | 10.0 / 11.6 / 29.3 | 9.3 / 10.9 / 28.6 | main 11.6, compositor 1.0, gpu 2.6, viz 0.8 | 7.0 / 14.0 / 20.9 | 8.9 / 12.3 / 21.0 |
| 2 | 44 / 75 | no (waited 57 s) | 7.8 / 9.9 / 13.1 | 7.8 / 9.9 / 13.1 | 7.2 / 9.1 / 12.5 | main 9.9, compositor 0.6, gpu 2.1, viz 0.6 | 6.9 / 7.0 / 14.0 | 5.6 / 7.8 / 8.7 |
| 3 | 16 / 31 | yes | 6.2 / 9.2 / 9.8 | 7.0 / 9.2 / 9.8 | 5.7 / 8.6 / 9.0 | main 9.2, compositor 0.5, gpu 1.9, viz 0.6 | 6.9 / 7.0 / 7.2 | 5.4 / 7.1 / 8.9 |

## T-NFR-14 static 100k render=pixels

| Run | CPU before / during (%) | Quiet | Frame time p50 / p95 / max | Interval p50 / p95 / max | JS p50 / p95 / max | p95 busy per thread | Untraced interval p50 / p95 / max | Untraced JS p50 / p95 / max |
|---|---|---|---|---|---|---|---|---|
| 1 | 17 / 35 | yes | 11.7 / 16.6 / 17.7 | 11.7 / 16.6 / 17.7 | 11.1 / 16.1 / 17.1 | main 16.6, compositor 0.6, gpu 1.8, viz 0.6 | 13.9 / 14.0 / 21.0 | 11.0 / 14.5 / 18.3 |
| 2 | 5 / 20 | yes | 9.5 / 10.5 / 11.5 | 9.5 / 10.6 / 16.4 | 9.1 / 10.0 / 11.1 | main 10.5, compositor 0.5, gpu 1.4, viz 0.4 | 13.8 / 14.0 / 14.4 | 10.0 / 12.6 / 15.5 |
| 3 | 8 / 33 | yes | 10.1 / 12.7 / 14.3 | 10.1 / 12.7 / 14.3 | 9.7 / 12.2 / 13.8 | main 12.7, compositor 0.6, gpu 1.7, viz 0.6 | 7.0 / 14.0 / 14.0 | 9.1 / 10.4 / 13.8 |

## T-NFR-14 realtime 100k + 500/s render=pixels

| Run | CPU before / during (%) | Quiet | Frame time p50 / p95 / max | Interval p50 / p95 / max | JS p50 / p95 / max | p95 busy per thread | Untraced interval p50 / p95 / max | Untraced JS p50 / p95 / max |
|---|---|---|---|---|---|---|---|---|
| 1 | 16 / 97 | yes | 2.2 / 11.8 / 18.3 | 96.1 / 111 / 123 | 0.3 / 2.1 / 10.8 | main 7.9, compositor 1.9, gpu 1.7, viz 0.9 | - | 0.4 / 2.7 / 6.3 |
| 2 | 71 / 84 | no (waited 57 s) | 2.3 / 16.9 / 56.9 | 103 / 112 / 115 | 0.3 / 2.8 / 10.1 | main 11.2, compositor 1.7, gpu 1.9, viz 0.9 | - | 0.3 / 2.3 / 4.0 |
| 3 | 7 / 11 | yes | 1.3 / 3.2 / 5.1 | 94.9 / 111 / 112 | 0.2 / 2.3 / 2.6 | main 3.2, compositor 0.7, gpu 1.2, viz 0.6 | - | 0.2 / 2.0 / 2.5 |

- run 1: 123 frames (0 full redraws), points 102490 -> 106111
- run 2: 113 frames (0 full redraws), points 115768 -> 119336
- run 3: 102 frames (0 full redraws), points 121319 -> 124612

## comparison: realtime 100k + 500/s render=pixels incremental=0

| Run | CPU before / during (%) | Quiet | Frame time p50 / p95 / max | Interval p50 / p95 / max | JS p50 / p95 / max | p95 busy per thread | Untraced interval p50 / p95 / max | Untraced JS p50 / p95 / max |
|---|---|---|---|---|---|---|---|---|
| 1 | 16 / 19 | yes | 12.9 / 17.8 / 20.0 | 94.6 / 111 / 112 | 11.1 / 16.0 / 17.0 | main 17.8, compositor 1.1, gpu 1.8, viz 1.1 | - | 10.0 / 15.9 / 17.0 |
| 2 | 13 / 17 | yes | 14.1 / 18.7 / 20.0 | 94.7 / 111 / 119 | 12.9 / 15.5 / 17.1 | main 18.7, compositor 1.2, gpu 1.7, viz 1.1 | - | 10.6 / 15.5 / 16.8 |
| 3 | 20 / 25 | yes | 14.4 / 20.1 / 30.0 | 94.8 / 110 / 113 | 12.4 / 17.4 / 20.7 | main 20.1, compositor 1.2, gpu 1.8, viz 1.2 | - | 12.7 / 17.7 / 19.0 |

- run 1: 104 frames (104 full redraws), points 100784 -> 104202
- run 2: 103 frames (103 full redraws), points 104552 -> 107866
- run 3: 103 frames (103 full redraws), points 108180 -> 111639

## T-NFR-14 interaction 100k hover+drag render=pixels

| Run | CPU before / during (%) | Quiet | Frame time p50 / p95 / max | Interval p50 / p95 / max | JS p50 / p95 / max | p95 busy per thread | Untraced interval p50 / p95 / max | Untraced JS p50 / p95 / max |
|---|---|---|---|---|---|---|---|---|
| 1 | 87 / 95 | no (waited 57 s) | 2.9 / 7.4 / 27.9 | 7.4 / 17.9 / 84.7 | 0.6 / 0.8 / 1.3 | main 7.4, compositor 1.4, gpu 2.5, viz 0.9 | - | - |
| 2 | 13 / 38 | yes | 1.8 / 3.9 / 6.9 | 7.0 / 13.5 / 55.2 | 0.4 / 0.6 / 0.9 | main 3.9, compositor 0.7, gpu 1.5, viz 0.5 | - | - |
| 3 | 9 / 29 | yes | 1.3 / 3.4 / 6.3 | 7.0 / 13.5 / 55.1 | 0.3 / 0.5 / 0.7 | main 3.4, compositor 0.7, gpu 1.4, viz 0.5 | - | - |

- run 1: selection of 1098 points, compute 3.6 ms, React commit 179.8 ms (not a canvas frame, not judged)
- run 1: 173 frames, point layer redrawn in 0
- run 2: selection of 1098 points, compute 1.2 ms, React commit 64.6 ms (not a canvas frame, not judged)
- run 2: 173 frames, point layer redrawn in 0
- run 3: selection of 1098 points, compute 1.1 ms, React commit 37.1 ms (not a canvas frame, not judged)
- run 3: 173 frames, point layer redrawn in 0
