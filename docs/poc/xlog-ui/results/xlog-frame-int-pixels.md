# XLog frame time (T-NFR-14)

- Started: 2026-10-23T11:33:48.651Z
- Machine: Windows_NT 10.0.26200 x64, AMD Ryzen 5 5600H with Radeon Graphics, 12 logical cores, 15 GB
- Browser: chromium 156.0.8078.4 (headless), Playwright 1.64.1, Node v24.14.0
- GPU: ANGLE (AMD, AMD Radeon(TM) Graphics (0x00001638) Direct3D11 vs_5_0 ps_5_0, D3D11)
- Viewport 1366x768, DPR 1, plot 1254x382, refresh 6.9 ms

Frame time = busiest rendering-pipeline thread per frame (Chrome trace). Budget: p95 and mean <= 16 ms.

| Scenario | Mode | Frames | Frame time p50 / p95 / max (ms) | Mean | Interval p50 / p95 / max (ms) | JS p50 / p95 / max (ms) | Busiest thread | CPU load before (%) | Verdict |
|---|---|---|---|---|---|---|---|---|---|
| T-NFR-14 static 10k render=pixels | pixels | 357 | 2.0 / 2.9 / 7.0 | 2.1 | 6.9 / 7.1 / 19.1 | 1.6 / 2.2 / 2.7 | main 99%, gpu 1% | 12-17 | PASS |
| T-NFR-14 static 50k render=pixels | pixels | 357 | 5.3 / 7.0 / 9.3 | 5.6 | 6.9 / 7.3 / 19.3 | 5.0 / 6.5 / 8.9 | main 100% | 13-15 | PASS |
| T-NFR-14 static 100k render=pixels | pixels | 357 | 9.7 / 10.9 / 15.7 | 9.9 | 9.7 / 10.9 / 20.8 | 9.3 / 10.5 / 15.2 | main 100% | 11-17 | PASS |
| T-NFR-14 realtime 100k + 500/s render=pixels | pixels | 297 | 1.3 / 3.8 / 8.8 | 1.7 | 95.6 / 110 / 125 | 0.2 / 1.8 / 3.5 | main 86%, gpu 14% | 4-15 | PASS |
| T-NFR-14 interaction 100k hover+drag render=pixels | pixels | 513 | 1.5 / 3.3 / 10.0 | 1.9 | 7.0 / 13.4 / 83.3 | 0.3 / 0.5 / 0.7 | main 100% | 18-20 | PASS |

## T-NFR-14 static 10k render=pixels

| Run | CPU before / during (%) | Quiet | Frame time p50 / p95 / max | Interval p50 / p95 / max | JS p50 / p95 / max | p95 busy per thread | Untraced interval p50 / p95 / max | Untraced JS p50 / p95 / max |
|---|---|---|---|---|---|---|---|---|
| 1 | 15 / 20 | yes | 2.0 / 2.9 / 7.0 | 7.0 / 7.1 / 13.4 | 1.6 / 2.0 / 2.7 | main 2.9, compositor 0.3, gpu 1.2, viz 0.4 | 6.9 / 7.0 / 20.8 | 1.6 / 2.0 / 2.4 |
| 2 | 17 / 22 | yes | 2.0 / 2.7 / 3.9 | 6.9 / 7.1 / 16.0 | 1.6 / 2.0 / 2.6 | main 2.7, compositor 0.3, gpu 1.1, viz 0.5 | 6.9 / 7.0 / 13.9 | 1.5 / 2.4 / 2.6 |
| 3 | 12 / 39 | yes | 2.1 / 2.9 / 4.7 | 6.9 / 7.1 / 19.1 | 1.6 / 2.3 / 2.5 | main 2.9, compositor 0.4, gpu 1.2, viz 0.4 | 6.9 / 7.0 / 13.9 | 1.5 / 2.4 / 2.5 |

## T-NFR-14 static 50k render=pixels

| Run | CPU before / during (%) | Quiet | Frame time p50 / p95 / max | Interval p50 / p95 / max | JS p50 / p95 / max | p95 busy per thread | Untraced interval p50 / p95 / max | Untraced JS p50 / p95 / max |
|---|---|---|---|---|---|---|---|---|
| 1 | 13 / 29 | yes | 5.5 / 9.0 / 9.3 | 6.9 / 9.0 / 9.3 | 5.1 / 8.5 / 8.9 | main 9.0, compositor 0.4, gpu 1.9, viz 0.5 | 6.9 / 7.0 / 7.3 | 5.0 / 6.7 / 8.6 |
| 2 | 15 / 18 | yes | 5.3 / 5.8 / 6.8 | 6.9 / 7.0 / 7.1 | 5.0 / 5.2 / 6.5 | main 5.8, compositor 0.3, gpu 1.0, viz 0.3 | 6.9 / 7.1 / 7.1 | 4.9 / 5.1 / 7.9 |
| 3 | 13 / 20 | yes | 5.3 / 5.9 / 9.2 | 6.9 / 7.1 / 19.3 | 4.9 / 5.2 / 8.8 | main 5.9, compositor 0.3, gpu 1.2, viz 0.4 | 6.9 / 7.0 / 7.1 | 4.9 / 5.2 / 5.8 |

## T-NFR-14 static 100k render=pixels

| Run | CPU before / during (%) | Quiet | Frame time p50 / p95 / max | Interval p50 / p95 / max | JS p50 / p95 / max | p95 busy per thread | Untraced interval p50 / p95 / max | Untraced JS p50 / p95 / max |
|---|---|---|---|---|---|---|---|---|
| 1 | 11 / 20 | yes | 10.5 / 11.3 / 12.2 | 10.5 / 11.3 / 20.8 | 10.1 / 10.7 / 11.2 | main 11.3, compositor 0.5, gpu 1.4, viz 0.5 | 7.0 / 14.0 / 20.8 | 9.3 / 11.2 / 14.5 |
| 2 | 16 / 30 | yes | 9.7 / 10.3 / 10.9 | 9.7 / 10.4 / 14.8 | 9.3 / 9.8 / 10.2 | main 10.3, compositor 0.6, gpu 1.6, viz 0.5 | 7.0 / 13.9 / 14.0 | 9.2 / 9.6 / 10.3 |
| 3 | 17 / 22 | yes | 9.5 / 10.3 / 15.7 | 9.5 / 10.3 / 15.7 | 9.1 / 9.8 / 15.2 | main 10.3, compositor 0.5, gpu 1.4, viz 0.4 | 7.0 / 13.9 / 14.0 | 9.1 / 9.5 / 9.9 |

## T-NFR-14 realtime 100k + 500/s render=pixels

| Run | CPU before / during (%) | Quiet | Frame time p50 / p95 / max | Interval p50 / p95 / max | JS p50 / p95 / max | p95 busy per thread | Untraced interval p50 / p95 / max | Untraced JS p50 / p95 / max |
|---|---|---|---|---|---|---|---|---|
| 1 | 13 / 43 | yes | 1.2 / 4.1 / 5.1 | 95.4 / 110 / 118 | 0.2 / 1.8 / 2.3 | main 4.1, compositor 0.9, gpu 1.5, viz 0.9 | - | 0.3 / 2.0 / 2.2 |
| 2 | 4 / 11 | yes | 1.2 / 3.0 / 5.5 | 94.9 / 111 / 125 | 0.2 / 1.9 / 2.4 | main 3.0, compositor 0.9, gpu 1.3, viz 0.8 | - | 0.3 / 1.7 / 2.3 |
| 3 | 15 / 60 | yes | 1.5 / 4.1 / 8.8 | 101 / 109 / 116 | 0.3 / 1.7 / 3.5 | main 4.1, compositor 0.9, gpu 1.6, viz 0.9 | - | 0.2 / 1.8 / 2.5 |

- run 1: 103 frames (0 full redraws), points 100788 -> 104207
- run 2: 102 frames (0 full redraws), points 112902 -> 116312
- run 3: 102 frames (0 full redraws), points 121658 -> 124910

## T-NFR-14 interaction 100k hover+drag render=pixels

| Run | CPU before / during (%) | Quiet | Frame time p50 / p95 / max | Interval p50 / p95 / max | JS p50 / p95 / max | p95 busy per thread | Untraced interval p50 / p95 / max | Untraced JS p50 / p95 / max |
|---|---|---|---|---|---|---|---|---|
| 1 | 18 / 37 | yes | 1.6 / 3.5 / 10.0 | 7.0 / 13.4 / 83.3 | 0.4 / 0.6 / 0.7 | main 3.5, compositor 0.7, gpu 1.6, viz 0.6 | - | - |
| 2 | 20 / 19 | yes | 1.3 / 2.7 / 8.0 | 7.0 / 13.4 / 82.8 | 0.3 / 0.4 / 0.6 | main 2.7, compositor 0.7, gpu 1.2, viz 0.5 | - | - |
| 3 | 18 / 31 | yes | 1.5 / 2.9 / 7.8 | 7.0 / 13.3 / 82.7 | 0.3 / 0.5 / 0.6 | main 2.9, compositor 0.7, gpu 1.3, viz 0.5 | - | - |

- run 1: selection of 1056 points, compute 1.5 ms, React commit 72.6 ms (not a canvas frame, not judged)
- run 1: 172 frames, point layer redrawn in 0
- run 2: selection of 1056 points, compute 0.8 ms, React commit 51.4 ms (not a canvas frame, not judged)
- run 2: 172 frames, point layer redrawn in 0
- run 3: selection of 1056 points, compute 0.9 ms, React commit 52.4 ms (not a canvas frame, not judged)
- run 3: 172 frames, point layer redrawn in 0
