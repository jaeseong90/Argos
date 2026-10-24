# XLog frame time (T-NFR-14)

- Started: 2026-10-23T14:13:31.353Z
- Machine: Windows_NT 10.0.26200 x64, AMD Ryzen 5 5600H with Radeon Graphics, 12 logical cores, 15 GB
- Browser: chromium 156.0.8078.4 (headless), Playwright 1.64.1, Node v24.14.0
- GPU: ANGLE (NVIDIA, NVIDIA GeForce RTX 3050 Ti Laptop GPU (0x000025A0) Direct3D11 vs_5_0 ps_5_0, D3D11)
- Viewport 1366x768, DPR 1 and 2 (per scenario), plot 1254x382 CSS px, refresh 13.3 ms

Frame time = busiest rendering-pipeline thread per frame (Chrome trace). Budget: p95 and mean <= 16 ms of the frames of all runs together (pooled). Max run p95 / Max run mean: the highest p95 and mean of a single run (reported, not judged). Canvas px: backing store of the point layer (capped, ADR-0003 C-34) / the overlay.

| Scenario | DPR | Canvas px (points / overlay) | Mode | Frames | Frame time p50 / p95 / max (ms) | Mean | Max run p95 (ms) | Max run mean (ms) | Interval p50 / p95 / max (ms) | JS p50 / p95 / max (ms) | Busiest thread | CPU load before (%) | Verdict |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| T-NFR-14 static 100k render=default dpr=1 | 1 | 1254x382 / 1334x420 | pixels | 357 | 12.7 / 17.3 / 19.8 | 13.2 | 18.0 | 14.4 | 13.4 / 17.3 / 19.8 | 12.2 / 16.8 / 19.2 | main 100% | 16-19 | FAIL |
| T-NFR-14 static 100k worst render=default color=service y=log dpr=1 | 1 | 1254x382 / 1334x420 | pixels | 357 | 14.4 / 22.8 / 26.6 | 16.1 | 23.9 | 19.8 | 14.4 / 22.8 / 26.6 | 13.8 / 22.2 / 24.4 | main 100% | 5-9 | FAIL |
| T-NFR-14 static 100k render=default dpr=2 | 2 | 2508x764 / 2668x840 | pixels | 357 | 26.0 / 30.5 / 33.9 | 25.5 | 30.6 | 26.1 | 26.0 / 30.5 / 33.9 | 25.4 / 29.9 / 31.8 | main 100% | 17-19 | FAIL |
| T-NFR-14 static 100k worst render=default color=service y=log dpr=2 | 2 | 2508x764 / 2668x840 | pixels | 357 | 24.6 / 37.2 / 42.7 | 26.8 | 39.5 | 30.7 | 24.6 / 37.2 / 42.7 | 24.0 / 36.6 / 42.1 | main 100% | 6-31 | FAIL |

## T-NFR-14 static 100k render=default dpr=1

| Run | CPU before / during (%) | Quiet | Frame time p50 / p95 / max | Interval p50 / p95 / max | JS p50 / p95 / max | p95 busy per thread | Untraced interval p50 / p95 / max | Untraced JS p50 / p95 / max |
|---|---|---|---|---|---|---|---|---|
| 1 | 17 / 49 | yes | 14.7 / 18.0 / 19.8 | 14.7 / 18.0 / 19.8 | 14.1 / 17.0 / 19.2 | main 18.0, compositor 0.6, gpu 2.2, viz 0.6 | 13.3 / 26.6 / 27.4 | 12.7 / 16.4 / 16.9 |
| 2 | 16 / 32 | yes | 11.5 / 17.7 / 18.8 | 13.4 / 17.7 / 18.8 | 10.8 / 17.2 / 17.5 | main 17.7, compositor 0.5, gpu 2.0, viz 0.5 | 13.3 / 26.6 / 26.8 | 13.5 / 17.5 / 17.9 |
| 3 | 19 / 27 | yes | 12.3 / 17.2 / 18.3 | 13.4 / 17.2 / 18.3 | 11.9 / 16.7 / 17.7 | main 17.2, compositor 0.5, gpu 1.9, viz 0.5 | 13.4 / 26.7 / 26.9 | 14.3 / 16.5 / 17.1 |

- p95 17.3 ms > 16 ms

## T-NFR-14 static 100k worst render=default color=service y=log dpr=1

| Run | CPU before / during (%) | Quiet | Frame time p50 / p95 / max | Interval p50 / p95 / max | JS p50 / p95 / max | p95 busy per thread | Untraced interval p50 / p95 / max | Untraced JS p50 / p95 / max |
|---|---|---|---|---|---|---|---|---|
| 1 | 9 / 18 | yes | 13.4 / 14.9 / 17.7 | 13.4 / 14.9 / 17.7 | 13.0 / 14.4 / 17.2 | main 14.9, compositor 0.5, gpu 1.6, viz 0.5 | 13.3 / 26.6 / 26.8 | 13.3 / 17.3 / 19.4 |
| 2 | 7 / 52 | yes | 20.4 / 23.9 / 26.6 | 20.4 / 23.9 / 26.6 | 19.8 / 23.3 / 24.4 | main 23.9, compositor 0.7, gpu 2.3, viz 0.6 | 13.4 / 26.8 / 26.8 | 17.9 / 22.0 / 22.9 |
| 3 | 5 / 33 | yes | 14.1 / 20.9 / 23.9 | 14.1 / 20.9 / 23.9 | 13.5 / 20.4 / 23.2 | main 20.9, compositor 0.6, gpu 1.8, viz 0.6 | 13.3 / 26.8 / 26.8 | 14.4 / 20.5 / 21.8 |

- p95 22.8 ms > 16 ms
- mean 16.1 ms > 16 ms

## T-NFR-14 static 100k render=default dpr=2

| Run | CPU before / during (%) | Quiet | Frame time p50 / p95 / max | Interval p50 / p95 / max | JS p50 / p95 / max | p95 busy per thread | Untraced interval p50 / p95 / max | Untraced JS p50 / p95 / max |
|---|---|---|---|---|---|---|---|---|
| 1 | 17 / 35 | yes | 26.2 / 30.6 / 32.3 | 26.2 / 30.6 / 32.3 | 25.5 / 30.0 / 31.8 | main 30.6, compositor 0.7, gpu 4.1, viz 0.7 | 26.7 / 40.0 / 40.1 | 24.7 / 30.1 / 30.7 |
| 2 | 17 / 43 | yes | 26.2 / 30.5 / 31.7 | 26.2 / 30.5 / 31.7 | 25.6 / 29.8 / 30.9 | main 30.5, compositor 0.7, gpu 4.7, viz 0.8 | 26.7 / 26.8 / 40.2 | 25.1 / 29.6 / 32.1 |
| 3 | 19 / 38 | yes | 25.8 / 30.4 / 33.9 | 25.8 / 30.4 / 33.9 | 25.0 / 29.8 / 31.6 | main 30.4, compositor 0.7, gpu 4.6, viz 0.7 | 26.7 / 39.9 / 40.1 | 26.0 / 29.0 / 30.7 |

- p95 30.5 ms > 16 ms
- mean 25.5 ms > 16 ms

## T-NFR-14 static 100k worst render=default color=service y=log dpr=2

| Run | CPU before / during (%) | Quiet | Frame time p50 / p95 / max | Interval p50 / p95 / max | JS p50 / p95 / max | p95 busy per thread | Untraced interval p50 / p95 / max | Untraced JS p50 / p95 / max |
|---|---|---|---|---|---|---|---|---|
| 1 | 31 / 32 | no (waited 148 s) | 30.0 / 39.5 / 42.7 | 30.0 / 39.5 / 42.7 | 29.2 / 38.9 / 42.1 | main 39.5, compositor 0.7, gpu 4.0, viz 0.8 | 26.8 / 40.1 / 40.2 | 33.2 / 39.4 / 41.6 |
| 2 | 9 / 18 | yes | 23.8 / 32.6 / 38.2 | 23.8 / 32.6 / 38.2 | 23.2 / 32.2 / 37.7 | main 32.6, compositor 0.6, gpu 3.5, viz 0.7 | 26.7 / 40.1 / 40.5 | 23.6 / 39.7 / 42.0 |
| 3 | 6 / 19 | yes | 23.8 / 30.6 / 37.8 | 23.8 / 30.6 / 37.8 | 23.3 / 30.0 / 37.3 | main 30.6, compositor 0.6, gpu 4.0, viz 0.8 | 26.7 / 39.9 / 40.0 | 24.2 / 30.2 / 38.2 |

- p95 37.2 ms > 16 ms
- mean 26.8 ms > 16 ms
