# XLog frame time (T-NFR-14)

- Started: 2026-10-23T14:03:47.823Z
- Machine: Windows_NT 10.0.26200 x64, AMD Ryzen 5 5600H with Radeon Graphics, 12 logical cores, 15 GB
- Browser: chromium 156.0.8078.4 (headless), Playwright 1.64.1, Node v24.14.0
- GPU: ANGLE (NVIDIA, NVIDIA GeForce RTX 3050 Ti Laptop GPU (0x000025A0) Direct3D11 vs_5_0 ps_5_0, D3D11)
- Viewport 1366x768, DPR 1 and 2 (per scenario), plot 1254x382 CSS px, refresh 13.3 ms

Frame time = busiest rendering-pipeline thread per frame (Chrome trace). Budget: p95 and mean <= 16 ms of the frames of all runs together (pooled). Max run p95 / Max run mean: the highest p95 and mean of a single run (reported, not judged). Canvas px: backing store of the point layer (capped, ADR-0003 C-34) / the overlay.

| Scenario | DPR | Canvas px (points / overlay) | Mode | Frames | Frame time p50 / p95 / max (ms) | Mean | Max run p95 (ms) | Max run mean (ms) | Interval p50 / p95 / max (ms) | JS p50 / p95 / max (ms) | Busiest thread | CPU load before (%) | Verdict |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| T-NFR-14 static 100k render=default dpr=1 | 1 | 1254x382 / 1334x420 | pixels | 357 | 3.7 / 4.6 / 5.8 | 3.8 | 4.7 | 4.0 | 13.3 / 13.5 / 15.4 | 3.2 / 3.8 / 4.4 | main 100% | 10-15 | PASS |
| T-NFR-14 static 100k worst render=default color=service y=log dpr=1 | 1 | 1254x382 / 1334x420 | pixels | 357 | 4.4 / 5.8 / 7.2 | 4.5 | 6.2 | 4.9 | 13.3 / 13.5 / 13.7 | 3.9 / 5.3 / 5.7 | main 100% | 11-16 | PASS |
| T-NFR-14 static 100k render=default dpr=2 | 2 | 2508x764 / 2668x840 | pixels | 357 | 6.2 / 7.9 / 10.7 | 6.4 | 8.0 | 6.9 | 13.3 / 13.5 / 13.8 | 5.7 / 7.2 / 10.0 | main 100% | 4-10 | PASS |
| T-NFR-14 static 100k worst render=default color=service y=log dpr=2 | 2 | 2508x764 / 2668x840 | pixels | 357 | 7.3 / 11.4 / 14.8 | 7.9 | 13.4 | 9.1 | 13.3 / 13.5 / 16.9 | 6.7 / 10.6 / 14.2 | main 100% | 12-18 | PASS |

## T-NFR-14 static 100k render=default dpr=1

| Run | CPU before / during (%) | Quiet | Frame time p50 / p95 / max | Interval p50 / p95 / max | JS p50 / p95 / max | p95 busy per thread | Untraced interval p50 / p95 / max | Untraced JS p50 / p95 / max |
|---|---|---|---|---|---|---|---|---|
| 1 | 15 / 15 | yes | 3.6 / 4.3 / 5.8 | 13.3 / 13.5 / 13.7 | 3.1 / 3.7 / 3.8 | main 4.3, compositor 0.4, gpu 1.3, viz 0.5 | 13.3 / 13.5 / 13.5 | 3.1 / 3.7 / 4.0 |
| 2 | 10 / 19 | yes | 3.5 / 4.5 / 5.2 | 13.3 / 13.5 / 13.9 | 3.1 / 3.7 / 3.8 | main 4.5, compositor 0.4, gpu 1.2, viz 0.4 | 13.3 / 13.5 / 13.5 | 3.1 / 3.6 / 3.7 |
| 3 | 12 / 46 | yes | 4.0 / 4.7 / 5.7 | 13.3 / 13.5 / 15.4 | 3.4 / 3.9 / 4.4 | main 4.7, compositor 0.5, gpu 1.7, viz 0.5 | 13.3 / 13.5 / 13.6 | 3.3 / 3.8 / 5.1 |

## T-NFR-14 static 100k worst render=default color=service y=log dpr=1

| Run | CPU before / during (%) | Quiet | Frame time p50 / p95 / max | Interval p50 / p95 / max | JS p50 / p95 / max | p95 busy per thread | Untraced interval p50 / p95 / max | Untraced JS p50 / p95 / max |
|---|---|---|---|---|---|---|---|---|
| 1 | 16 / 17 | yes | 4.6 / 6.2 / 7.2 | 13.3 / 13.5 / 13.7 | 4.1 / 5.3 / 5.7 | main 6.2, compositor 0.4, gpu 1.3, viz 0.4 | 13.3 / 13.4 / 13.5 | 3.6 / 4.1 / 4.3 |
| 2 | 11 / 35 | yes | 4.4 / 5.4 / 5.9 | 13.3 / 13.5 / 13.6 | 3.8 / 4.2 / 5.1 | main 5.4, compositor 0.5, gpu 1.4, viz 0.5 | 13.3 / 13.4 / 13.5 | 3.9 / 4.3 / 5.2 |
| 3 | 13 / 22 | yes | 4.4 / 5.0 / 6.4 | 13.3 / 13.5 / 13.6 | 3.9 / 4.1 / 4.4 | main 5.0, compositor 0.4, gpu 1.3, viz 0.4 | 13.3 / 13.4 / 13.7 | 3.8 / 4.2 / 4.7 |

## T-NFR-14 static 100k render=default dpr=2

| Run | CPU before / during (%) | Quiet | Frame time p50 / p95 / max | Interval p50 / p95 / max | JS p50 / p95 / max | p95 busy per thread | Untraced interval p50 / p95 / max | Untraced JS p50 / p95 / max |
|---|---|---|---|---|---|---|---|---|
| 1 | 10 / 35 | yes | 6.7 / 8.0 / 8.9 | 13.3 / 13.5 / 13.8 | 6.1 / 7.4 / 7.8 | main 8.0, compositor 0.4, gpu 3.3, viz 0.5 | 13.3 / 13.5 / 13.6 | 6.7 / 7.4 / 8.4 |
| 2 | 6 / 16 | yes | 6.0 / 7.8 / 10.7 | 13.3 / 13.5 / 13.6 | 5.5 / 7.3 / 10.0 | main 7.8, compositor 0.5, gpu 3.1, viz 0.5 | 13.3 / 13.4 / 13.5 | 5.4 / 6.1 / 7.3 |
| 3 | 4 / 16 | yes | 6.1 / 7.2 / 8.0 | 13.3 / 13.5 / 13.8 | 5.7 / 6.5 / 7.2 | main 7.2, compositor 0.4, gpu 2.8, viz 0.4 | 13.3 / 13.4 / 13.5 | 5.4 / 7.0 / 7.5 |

## T-NFR-14 static 100k worst render=default color=service y=log dpr=2

| Run | CPU before / during (%) | Quiet | Frame time p50 / p95 / max | Interval p50 / p95 / max | JS p50 / p95 / max | p95 busy per thread | Untraced interval p50 / p95 / max | Untraced JS p50 / p95 / max |
|---|---|---|---|---|---|---|---|---|
| 1 | 18 / 54 | yes | 8.7 / 13.4 / 14.8 | 13.3 / 13.8 / 16.9 | 8.2 / 12.8 / 14.2 | main 13.4, compositor 0.5, gpu 5.3, viz 0.5 | 13.3 / 13.5 / 13.6 | 11.3 / 14.0 / 15.0 |
| 2 | 12 / 14 | yes | 6.8 / 8.3 / 9.4 | 13.3 / 13.5 / 13.8 | 6.3 / 7.3 / 8.3 | main 8.3, compositor 0.5, gpu 3.0, viz 0.4 | 13.3 / 13.5 / 26.9 | 8.6 / 16.5 / 21.4 |
| 3 | 15 / 30 | yes | 7.2 / 10.2 / 11.8 | 13.3 / 13.5 / 13.6 | 6.5 / 9.6 / 11.2 | main 10.2, compositor 0.5, gpu 3.5, viz 0.5 | 13.3 / 13.4 / 13.5 | 7.4 / 10.9 / 12.8 |
