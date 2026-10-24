# XLog frame time (T-NFR-14)

- Started: 2026-10-23T07:07:37.007Z
- Machine: Windows_NT 10.0.26200 x64, AMD Ryzen 5 5600H with Radeon Graphics, 12 logical cores, 15 GB
- Browser: chromium 156.0.8078.4 (headless), Playwright 1.64.1, Node v24.14.0
- GPU: ANGLE (AMD, AMD Radeon(TM) Graphics (0x00001638) Direct3D11 vs_5_0 ps_5_0, D3D11)
- Viewport 1366x768, DPR 1, plot 1256x384, refresh 6.9 ms

Frame time = busiest rendering-pipeline thread per frame (Chrome trace). Budget: p95 and mean <= 16 ms.

| Scenario | Mode | Frames | Frame time p50 / p95 / max (ms) | Mean | Interval p50 / p95 / max (ms) | JS p50 / p95 / max (ms) | Busiest thread | CPU load before (%) | Verdict |
|---|---|---|---|---|---|---|---|---|---|
| T-NFR-14 static 10k render=pixels | pixels | 357 | 2.0 / 2.9 / 5.8 | 2.1 | 6.9 / 7.1 / 16.0 | 1.6 / 2.4 / 2.7 | main 100%, gpu 0% | 3-8 | PASS |
| T-NFR-14 static 50k render=pixels | pixels | 357 | 5.7 / 8.9 / 10.8 | 6.2 | 7.0 / 8.9 / 14.8 | 5.2 / 8.4 / 8.8 | main 100% | 12-16 | PASS |
| T-NFR-14 static 100k render=pixels | pixels | 357 | 10.4 / 15.5 / 18.4 | 11.0 | 10.5 / 15.5 / 18.4 | 10.0 / 15.0 / 17.9 | main 100% | 3-4 | PASS |
| T-NFR-14 realtime 100k + 500/s render=pixels | pixels | 296 | 1.2 / 3.2 / 5.0 | 1.6 | 95.2 / 110 / 115 | 0.3 / 1.8 / 2.9 | main 82%, gpu 17% | 3-13 | PASS |
| comparison: realtime 100k + 500/s render=pixels incremental=0 | pixels | 301 | 15.1 / 20.2 / 24.9 | 14.9 | 94.7 / 110 / 123 | 13.7 / 18.3 / 20.5 | main 100% | 3-19 | not judged |
| T-NFR-14 interaction 100k hover+drag render=pixels | pixels | 516 | 1.7 / 3.5 / 7.8 | 2.0 | 7.0 / 13.4 / 55.1 | 0.4 / 0.6 / 0.7 | main 100% | 8-16 | PASS |

## T-NFR-14 static 10k render=pixels

| Run | CPU before / during (%) | Quiet | Frame time p50 / p95 / max | Interval p50 / p95 / max | JS p50 / p95 / max | p95 busy per thread | Untraced interval p50 / p95 / max | Untraced JS p50 / p95 / max |
|---|---|---|---|---|---|---|---|---|
| 1 | 3 / 17 | yes | 2.0 / 3.1 / 5.8 | 6.9 / 7.1 / 10.8 | 1.6 / 2.5 / 2.7 | main 3.1, compositor 0.4, gpu 1.1, viz 0.4 | 6.9 / 7.0 / 13.8 | 1.7 / 2.0 / 2.4 |
| 2 | 3 / 7 | yes | 1.8 / 2.4 / 3.2 | 6.9 / 7.1 / 13.1 | 1.5 / 1.8 / 2.3 | main 2.4, compositor 0.3, gpu 1.1, viz 0.4 | 6.9 / 7.0 / 13.8 | 1.5 / 1.9 / 2.3 |
| 3 | 8 / 10 | yes | 2.1 / 2.7 / 5.0 | 7.0 / 7.0 / 16.0 | 1.7 / 2.1 / 2.3 | main 2.6, compositor 0.4, gpu 1.1, viz 0.4 | 6.9 / 7.0 / 13.8 | 1.5 / 2.2 / 2.6 |

## T-NFR-14 static 50k render=pixels

| Run | CPU before / during (%) | Quiet | Frame time p50 / p95 / max | Interval p50 / p95 / max | JS p50 / p95 / max | p95 busy per thread | Untraced interval p50 / p95 / max | Untraced JS p50 / p95 / max |
|---|---|---|---|---|---|---|---|---|
| 1 | 12 / 11 | yes | 5.3 / 6.0 / 7.2 | 6.9 / 7.1 / 11.4 | 5.0 / 5.3 / 5.8 | main 6.0, compositor 0.3, gpu 1.0, viz 0.3 | 6.9 / 7.1 / 20.9 | 5.8 / 8.4 / 8.7 |
| 2 | 16 / 47 | yes | 7.0 / 9.0 / 9.1 | 7.0 / 9.0 / 12.6 | 6.4 / 8.5 / 8.7 | main 9.0, compositor 0.5, gpu 1.7, viz 0.5 | 6.9 / 7.1 / 14.0 | 6.1 / 8.6 / 9.0 |
| 3 | 15 / 20 | yes | 5.7 / 8.9 / 10.8 | 7.0 / 8.9 / 14.8 | 5.2 / 8.4 / 8.8 | main 8.9, compositor 0.4, gpu 1.6, viz 0.4 | 6.9 / 7.0 / 13.8 | 5.2 / 8.1 / 8.5 |

## T-NFR-14 static 100k render=pixels

| Run | CPU before / during (%) | Quiet | Frame time p50 / p95 / max | Interval p50 / p95 / max | JS p50 / p95 / max | p95 busy per thread | Untraced interval p50 / p95 / max | Untraced JS p50 / p95 / max |
|---|---|---|---|---|---|---|---|---|
| 1 | 3 / 24 | yes | 10.9 / 16.7 / 18.4 | 10.9 / 16.7 / 18.4 | 10.5 / 16.2 / 17.9 | main 16.7, compositor 0.5, gpu 1.5, viz 0.5 | 13.8 / 14.0 / 28.9 | 9.6 / 13.1 / 29.5 |
| 2 | 3 / 34 | yes | 10.3 / 15.5 / 17.1 | 10.5 / 15.5 / 17.1 | 10.0 / 14.9 / 16.6 | main 15.5, compositor 0.5, gpu 1.7, viz 0.5 | 13.9 / 20.8 / 20.9 | 11.7 / 16.1 / 17.2 |
| 3 | 4 / 16 | yes | 10.0 / 12.5 / 16.4 | 10.0 / 12.5 / 16.4 | 9.6 / 12.0 / 16.0 | main 12.5, compositor 0.5, gpu 1.3, viz 0.5 | 7.0 / 14.0 / 20.9 | 9.6 / 10.2 / 17.0 |

## T-NFR-14 realtime 100k + 500/s render=pixels

| Run | CPU before / during (%) | Quiet | Frame time p50 / p95 / max | Interval p50 / p95 / max | JS p50 / p95 / max | p95 busy per thread | Untraced interval p50 / p95 / max | Untraced JS p50 / p95 / max |
|---|---|---|---|---|---|---|---|---|
| 1 | 7 / 5 | yes | 1.2 / 3.2 / 3.7 | 95.2 / 111 / 114 | 0.2 / 2.0 / 2.7 | main 3.2, compositor 0.7, gpu 1.3, viz 0.7 | - | 0.2 / 1.9 / 2.5 |
| 2 | 13 / 33 | yes | 1.2 / 3.2 / 3.4 | 95.5 / 111 / 115 | 0.3 / 1.5 / 2.5 | main 3.2, compositor 0.7, gpu 1.3, viz 0.7 | - | 0.2 / 1.9 / 2.6 |
| 3 | 3 / 10 | yes | 1.2 / 3.1 / 5.0 | 94.8 / 110 / 111 | 0.2 / 1.8 / 2.9 | main 3.1, compositor 0.8, gpu 1.4, viz 0.7 | - | 0.2 / 1.7 / 2.5 |

- run 1: 102 frames (0 full redraws), points 100804 -> 104191
- run 2: 103 frames (0 full redraws), points 106093 -> 109558
- run 3: 102 frames (0 full redraws), points 109876 -> 113337

## comparison: realtime 100k + 500/s render=pixels incremental=0

| Run | CPU before / during (%) | Quiet | Frame time p50 / p95 / max | Interval p50 / p95 / max | JS p50 / p95 / max | p95 busy per thread | Untraced interval p50 / p95 / max | Untraced JS p50 / p95 / max |
|---|---|---|---|---|---|---|---|---|
| 1 | 19 / 8 | yes | 12.2 / 18.7 / 20.6 | 94.7 / 110 / 123 | 11.0 / 15.4 / 17.7 | main 18.7, compositor 0.7, gpu 1.2, viz 0.7 | - | 14.9 / 17.5 / 19.4 |
| 2 | 3 / 9 | yes | 13.9 / 18.0 / 21.2 | 94.6 / 110 / 111 | 11.3 / 16.0 / 17.8 | main 18.0, compositor 0.7, gpu 1.2, viz 0.6 | - | 15.8 / 19.1 / 25.0 |
| 3 | 12 / 34 | yes | 17.9 / 22.1 / 24.9 | 94.7 / 111 / 112 | 16.7 / 18.9 / 20.5 | main 22.1, compositor 0.8, gpu 1.3, viz 0.7 | - | 17.0 / 19.9 / 23.2 |

- run 1: 102 frames (102 full redraws), points 102489 -> 105797
- run 2: 104 frames (104 full redraws), points 111194 -> 114598
- run 3: 106 frames (106 full redraws), points 119960 -> 123399

## T-NFR-14 interaction 100k hover+drag render=pixels

| Run | CPU before / during (%) | Quiet | Frame time p50 / p95 / max | Interval p50 / p95 / max | JS p50 / p95 / max | p95 busy per thread | Untraced interval p50 / p95 / max | Untraced JS p50 / p95 / max |
|---|---|---|---|---|---|---|---|---|
| 1 | 9 / 14 | yes | 1.5 / 3.5 / 7.8 | 7.0 / 13.4 / 54.9 | 0.4 / 0.6 / 0.7 | main 3.5, compositor 0.8, gpu 1.3, viz 0.5 | - | - |
| 2 | 8 / 24 | yes | 1.6 / 3.2 / 7.0 | 7.0 / 13.2 / 55.1 | 0.4 / 0.6 / 0.6 | main 3.2, compositor 0.8, gpu 1.3, viz 0.6 | - | - |
| 3 | 16 / 45 | yes | 1.9 / 3.7 / 7.7 | 7.0 / 13.6 / 54.9 | 0.4 / 0.6 / 0.7 | main 3.7, compositor 0.9, gpu 1.4, viz 0.7 | - | - |

- run 1: selection of 1098 points, compute 2.0 ms, React commit 80.0 ms (not a canvas frame, not judged)
- run 1: 173 frames, point layer redrawn in 0
- run 2: selection of 1098 points, compute 1.5 ms, React commit 68.7 ms (not a canvas frame, not judged)
- run 2: 173 frames, point layer redrawn in 0
- run 3: selection of 1098 points, compute 1.3 ms, React commit 56.2 ms (not a canvas frame, not judged)
- run 3: 173 frames, point layer redrawn in 0
