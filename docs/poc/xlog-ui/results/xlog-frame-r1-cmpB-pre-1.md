# XLog frame time (T-NFR-14)

- Started: 2026-10-23T14:00:27.147Z
- Machine: Windows_NT 10.0.26200 x64, AMD Ryzen 5 5600H with Radeon Graphics, 12 logical cores, 15 GB
- Browser: chromium 156.0.8078.4 (headless), Playwright 1.64.1, Node v24.14.0
- GPU: ANGLE (NVIDIA, NVIDIA GeForce RTX 3050 Ti Laptop GPU (0x000025A0) Direct3D11 vs_5_0 ps_5_0, D3D11)
- Viewport 1366x768, DPR 1 and 2 (per scenario), plot 1254x382 CSS px, refresh 13.3 ms

Frame time = busiest rendering-pipeline thread per frame (Chrome trace). Budget: p95 and mean <= 16 ms of the frames of all runs together (pooled). Max run p95 / Max run mean: the highest p95 and mean of a single run (reported, not judged). Canvas px: backing store of the point layer (capped, ADR-0003 C-34) / the overlay.

| Scenario | DPR | Canvas px (points / overlay) | Mode | Frames | Frame time p50 / p95 / max (ms) | Mean | Max run p95 (ms) | Max run mean (ms) | Interval p50 / p95 / max (ms) | JS p50 / p95 / max (ms) | Busiest thread | CPU load before (%) | Verdict |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| T-NFR-14 static 100k render=default dpr=1 | 1 | 1254x382 / 1334x420 | pixels | 357 | 10.8 / 15.5 / 31.5 | 11.5 | 16.2 | 11.8 | 13.3 / 15.5 / 31.5 | 10.2 / 15.0 / 17.2 | main 100% | 5-13 | PASS |
| T-NFR-14 static 100k worst render=default color=service y=log dpr=1 | 1 | 1254x382 / 1334x420 | pixels | 357 | 14.5 / 19.3 / 26.4 | 15.1 | 20.8 | 16.8 | 14.5 / 19.3 / 26.4 | 14.1 / 18.6 / 25.8 | main 100% | 18-19 | FAIL |
| T-NFR-14 static 100k render=default dpr=2 | 2 | 2508x764 / 2668x840 | pixels | 357 | 22.0 / 29.6 / 41.2 | 22.9 | 30.6 | 24.9 | 22.0 / 29.6 / 41.2 | 21.4 / 29.0 / 40.1 | main 100% | 12-18 | FAIL |
| T-NFR-14 static 100k worst render=default color=service y=log dpr=2 | 2 | 2508x764 / 2668x840 | pixels | 357 | 26.5 / 37.3 / 59.2 | 27.9 | 39.9 | 29.5 | 26.5 / 37.3 / 59.2 | 26.0 / 36.7 / 58.0 | main 100% | 11-19 | FAIL |

## T-NFR-14 static 100k render=default dpr=1

| Run | CPU before / during (%) | Quiet | Frame time p50 / p95 / max | Interval p50 / p95 / max | JS p50 / p95 / max | p95 busy per thread | Untraced interval p50 / p95 / max | Untraced JS p50 / p95 / max |
|---|---|---|---|---|---|---|---|---|
| 1 | 13 / 19 | yes | 10.5 / 15.7 / 31.5 | 13.3 / 15.7 / 31.5 | 10.0 / 15.3 / 16.8 | main 15.7, compositor 0.4, gpu 1.7, viz 0.5 | 13.3 / 13.5 / 13.5 | 10.3 / 14.8 / 16.9 |
| 2 | 8 / 26 | yes | 10.9 / 14.1 / 16.6 | 13.3 / 14.1 / 16.6 | 10.4 / 13.7 / 15.9 | main 14.1, compositor 0.5, gpu 1.8, viz 0.4 | 13.3 / 13.5 / 13.6 | 10.2 / 13.9 / 15.2 |
| 3 | 5 / 25 | yes | 10.8 / 16.2 / 17.7 | 13.4 / 16.2 / 17.7 | 10.2 / 15.6 / 17.2 | main 16.2, compositor 0.5, gpu 1.9, viz 0.5 | 13.3 / 13.4 / 13.5 | 9.7 / 13.0 / 16.8 |

## T-NFR-14 static 100k worst render=default color=service y=log dpr=1

| Run | CPU before / during (%) | Quiet | Frame time p50 / p95 / max | Interval p50 / p95 / max | JS p50 / p95 / max | p95 busy per thread | Untraced interval p50 / p95 / max | Untraced JS p50 / p95 / max |
|---|---|---|---|---|---|---|---|---|
| 1 | 18 / 23 | yes | 14.5 / 18.6 / 21.9 | 14.6 / 18.6 / 21.9 | 14.1 / 17.6 / 20.5 | main 18.6, compositor 0.5, gpu 1.8, viz 0.5 | 13.3 / 26.7 / 26.8 | 13.6 / 19.8 / 21.4 |
| 2 | 19 / 22 | yes | 13.1 / 17.4 / 20.6 | 13.3 / 17.4 / 20.6 | 12.7 / 16.9 / 20.2 | main 17.4, compositor 0.5, gpu 1.7, viz 0.5 | 13.3 / 13.5 / 26.8 | 12.9 / 15.6 / 19.2 |
| 3 | 19 / 50 | yes | 16.2 / 20.8 / 26.4 | 16.2 / 20.8 / 26.4 | 15.8 / 20.2 / 25.8 | main 20.8, compositor 0.6, gpu 2.0, viz 0.6 | 13.4 / 26.8 / 26.9 | 15.8 / 24.9 / 25.8 |

- p95 19.3 ms > 16 ms

## T-NFR-14 static 100k render=default dpr=2

| Run | CPU before / during (%) | Quiet | Frame time p50 / p95 / max | Interval p50 / p95 / max | JS p50 / p95 / max | p95 busy per thread | Untraced interval p50 / p95 / max | Untraced JS p50 / p95 / max |
|---|---|---|---|---|---|---|---|---|
| 1 | 18 / 37 | yes | 21.9 / 29.8 / 41.2 | 21.9 / 29.8 / 41.2 | 21.3 / 29.3 / 40.1 | main 29.8, compositor 0.6, gpu 4.1, viz 0.8 | 26.6 / 26.8 / 40.1 | 20.5 / 27.4 / 29.9 |
| 2 | 15 / 49 | yes | 25.5 / 30.6 / 32.4 | 25.5 / 30.6 / 32.4 | 24.5 / 29.5 / 31.8 | main 30.6, compositor 0.6, gpu 4.6, viz 0.8 | 26.7 / 40.1 / 53.3 | 27.2 / 37.9 / 44.1 |
| 3 | 12 / 25 | yes | 19.4 / 27.5 / 31.3 | 19.4 / 27.5 / 31.3 | 18.9 / 27.1 / 30.8 | main 27.5, compositor 0.7, gpu 3.6, viz 0.6 | 13.4 / 26.8 / 27.7 | 18.3 / 23.2 / 27.1 |

- p95 29.6 ms > 16 ms
- mean 22.9 ms > 16 ms

## T-NFR-14 static 100k worst render=default color=service y=log dpr=2

| Run | CPU before / during (%) | Quiet | Frame time p50 / p95 / max | Interval p50 / p95 / max | JS p50 / p95 / max | p95 busy per thread | Untraced interval p50 / p95 / max | Untraced JS p50 / p95 / max |
|---|---|---|---|---|---|---|---|---|
| 1 | 11 / 21 | yes | 25.0 / 35.0 / 38.4 | 25.0 / 35.0 / 38.4 | 24.5 / 34.6 / 37.9 | main 35.0, compositor 0.6, gpu 4.1, viz 0.8 | 26.7 / 26.8 / 26.9 | 22.7 / 27.2 / 33.6 |
| 2 | 19 / 36 | yes | 28.9 / 39.9 / 59.2 | 28.9 / 39.9 / 59.2 | 28.1 / 38.5 / 58.0 | main 39.9, compositor 0.6, gpu 4.1, viz 0.8 | 26.7 / 40.1 / 40.2 | 24.6 / 35.3 / 39.7 |
| 3 | 12 / 24 | yes | 26.5 / 36.7 / 38.0 | 26.5 / 36.7 / 38.0 | 26.1 / 36.3 / 37.5 | main 36.7, compositor 0.6, gpu 3.9, viz 0.7 | 26.7 / 40.1 / 40.2 | 25.1 / 31.9 / 40.6 |

- p95 37.3 ms > 16 ms
- mean 27.9 ms > 16 ms
