# XLog frame time (T-NFR-14)

- Started: 2026-10-23T07:12:43.053Z
- Machine: Windows_NT 10.0.26200 x64, AMD Ryzen 5 5600H with Radeon Graphics, 12 logical cores, 15 GB
- Browser: chromium 156.0.8078.4 (headless), Playwright 1.64.1, Node v24.14.0
- GPU: ANGLE (AMD, AMD Radeon(TM) Graphics (0x00001638) Direct3D11 vs_5_0 ps_5_0, D3D11)
- Viewport 1366x768, DPR 1, plot 1256x384, refresh 6.9 ms

Frame time = busiest rendering-pipeline thread per frame (Chrome trace). Budget: p95 and mean <= 16 ms.

| Scenario | Mode | Frames | Frame time p50 / p95 / max (ms) | Mean | Interval p50 / p95 / max (ms) | JS p50 / p95 / max (ms) | Busiest thread | CPU load before (%) | Verdict |
|---|---|---|---|---|---|---|---|---|---|
| T-NFR-14 static 10k render=rect | rect | 357 | 7.5 / 10.2 / 13.4 | 8.0 | 7.5 / 10.7 / 15.8 | 2.1 / 3.2 / 3.9 | gpu 100% | 6-14 | PASS |
| T-NFR-14 static 50k render=rect | rect | 357 | 40.8 / 56.4 / 68.7 | 41.6 | 40.8 / 56.4 / 68.7 | 12.3 / 19.0 / 19.9 | gpu 99%, main 1% | 2-8 | FAIL |
| T-NFR-14 static 100k render=rect | rect | 357 | 87.8 / 125 / 135 | 91.0 | 87.8 / 125 / 135 | 27.7 / 36.8 / 64.4 | gpu 99%, main 1% | 3-20 | FAIL |

## T-NFR-14 static 10k render=rect

| Run | CPU before / during (%) | Quiet | Frame time p50 / p95 / max | Interval p50 / p95 / max | JS p50 / p95 / max | p95 busy per thread | Untraced interval p50 / p95 / max | Untraced JS p50 / p95 / max |
|---|---|---|---|---|---|---|---|---|
| 1 | 14 / 30 | yes | 7.9 / 10.7 / 12.3 | 8.0 / 10.7 / 12.3 | 2.2 / 3.2 / 3.4 | main 4.6, compositor 0.4, gpu 10.7, viz 0.6 | 7.0 / 13.9 / 14.0 | 2.6 / 3.3 / 3.7 |
| 2 | 6 / 20 | yes | 7.4 / 11.7 / 13.4 | 7.4 / 12.1 / 13.4 | 2.1 / 3.1 / 3.9 | main 4.8, compositor 0.4, gpu 11.7, viz 0.5 | 7.0 / 13.9 / 14.0 | 2.1 / 3.1 / 3.2 |
| 3 | 13 / 24 | yes | 7.4 / 9.3 / 11.1 | 7.4 / 9.4 / 15.8 | 2.1 / 3.1 / 3.4 | main 4.3, compositor 0.4, gpu 9.3, viz 0.4 | 7.0 / 13.9 / 20.8 | 2.1 / 3.1 / 3.2 |

## T-NFR-14 static 50k render=rect

| Run | CPU before / during (%) | Quiet | Frame time p50 / p95 / max | Interval p50 / p95 / max | JS p50 / p95 / max | p95 busy per thread | Untraced interval p50 / p95 / max | Untraced JS p50 / p95 / max |
|---|---|---|---|---|---|---|---|---|
| 1 | 8 / 19 | yes | 36.6 / 48.5 / 60.8 | 36.6 / 48.5 / 60.8 | 11.4 / 18.8 / 19.2 | main 20.2, compositor 0.6, gpu 48.5, viz 0.5 | 34.9 / 48.6 / 62.5 | 11.4 / 18.9 / 19.4 |
| 2 | 4 / 27 | yes | 36.6 / 47.4 / 52.7 | 36.6 / 47.4 / 52.7 | 11.5 / 19.0 / 19.2 | main 20.2, compositor 0.6, gpu 47.4, viz 0.5 | 48.6 / 69.4 / 76.3 | 15.7 / 19.2 / 19.7 |
| 3 | 2 / 32 | yes | 46.0 / 61.1 / 68.7 | 46.0 / 61.1 / 68.7 | 14.2 / 18.9 / 19.9 | main 20.2, compositor 0.6, gpu 61.1, viz 0.5 | 48.6 / 62.5 / 69.5 | 16.0 / 18.9 / 19.9 |

- p95 56.4 ms > 16 ms
- mean 41.6 ms > 16 ms

## T-NFR-14 static 100k render=rect

| Run | CPU before / during (%) | Quiet | Frame time p50 / p95 / max | Interval p50 / p95 / max | JS p50 / p95 / max | p95 busy per thread | Untraced interval p50 / p95 / max | Untraced JS p50 / p95 / max |
|---|---|---|---|---|---|---|---|---|
| 1 | 20 / 33 | yes | 87.9 / 125 / 126 | 87.9 / 125 / 126 | 27.7 / 36.7 / 39.2 | main 39.4, compositor 0.7, gpu 125, viz 0.6 | 76.4 / 118 / 132 | 22.9 / 36.7 / 37.4 |
| 2 | 3 / 18 | yes | 74.2 / 103 / 111 | 74.2 / 103 / 111 | 22.7 / 36.6 / 37.1 | main 39.0, compositor 0.7, gpu 103, viz 0.5 | 90.2 / 118 / 125 | 29.3 / 36.7 / 89.6 |
| 3 | 14 / 49 | yes | 105 / 129 / 135 | 105 / 129 / 135 | 31.8 / 37.2 / 64.4 | main 41.6, compositor 0.7, gpu 129, viz 0.6 | 104 / 132 / 139 | 32.6 / 37.3 / 49.4 |

- p95 125.4 ms > 16 ms
- mean 91.0 ms > 16 ms
