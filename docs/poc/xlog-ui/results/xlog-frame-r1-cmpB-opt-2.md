# XLog frame time (T-NFR-14)

- Started: 2026-10-23T14:21:53.651Z
- Machine: Windows_NT 10.0.26200 x64, AMD Ryzen 5 5600H with Radeon Graphics, 12 logical cores, 15 GB
- Browser: chromium 156.0.8078.4 (headless), Playwright 1.64.1, Node v24.14.0
- GPU: ANGLE (NVIDIA, NVIDIA GeForce RTX 3050 Ti Laptop GPU (0x000025A0) Direct3D11 vs_5_0 ps_5_0, D3D11)
- Viewport 1366x768, DPR 1 and 2 (per scenario), plot 1254x382 CSS px, refresh 13.3 ms

Frame time = busiest rendering-pipeline thread per frame (Chrome trace). Budget: p95 and mean <= 16 ms of the frames of all runs together (pooled). Max run p95 / Max run mean: the highest p95 and mean of a single run (reported, not judged). Canvas px: backing store of the point layer (capped, ADR-0003 C-34) / the overlay.

| Scenario | DPR | Canvas px (points / overlay) | Mode | Frames | Frame time p50 / p95 / max (ms) | Mean | Max run p95 (ms) | Max run mean (ms) | Interval p50 / p95 / max (ms) | JS p50 / p95 / max (ms) | Busiest thread | CPU load before (%) | Verdict |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| T-NFR-14 static 100k render=default dpr=1 | 1 | 1254x382 / 1334x420 | pixels | 357 | 4.1 / 4.9 / 13.0 | 4.1 | 5.5 | 4.3 | 13.3 / 13.5 / 23.4 | 3.5 / 3.9 / 5.3 | main 100% | 16-19 | PASS |
| T-NFR-14 static 100k worst render=default color=service y=log dpr=1 | 1 | 1254x382 / 1334x420 | pixels | 357 | 4.6 / 6.1 / 6.9 | 4.7 | 6.4 | 5.3 | 13.3 / 13.5 / 13.5 | 4.0 / 5.4 / 5.9 | main 100% | 13-17 | PASS |
| T-NFR-14 static 100k render=default dpr=2 | 2 | 2508x764 / 2668x840 | pixels | 357 | 7.5 / 8.4 / 10.3 | 7.3 | 8.6 | 7.5 | 13.3 / 13.5 / 13.6 | 6.9 / 7.7 / 8.3 | main 100% | 15-19 | PASS |
| T-NFR-14 static 100k worst render=default color=service y=log dpr=2 | 2 | 2508x764 / 2668x840 | pixels | 357 | 8.8 / 10.5 / 12.1 | 8.7 | 10.9 | 8.8 | 13.3 / 13.5 / 15.2 | 8.2 / 9.7 / 11.4 | main 100% | 14-19 | PASS |

## T-NFR-14 static 100k render=default dpr=1

| Run | CPU before / during (%) | Quiet | Frame time p50 / p95 / max | Interval p50 / p95 / max | JS p50 / p95 / max | p95 busy per thread | Untraced interval p50 / p95 / max | Untraced JS p50 / p95 / max |
|---|---|---|---|---|---|---|---|---|
| 1 | 16 / 35 | yes | 4.2 / 5.5 / 13.0 | 13.3 / 13.5 / 23.4 | 3.6 / 4.2 / 5.3 | main 5.5, compositor 0.6, gpu 1.5, viz 0.5 | 13.3 / 13.5 / 13.5 | 3.6 / 3.9 / 4.2 |
| 2 | 19 / 28 | yes | 4.0 / 4.6 / 5.9 | 13.3 / 13.5 / 13.6 | 3.5 / 3.9 / 4.0 | main 4.6, compositor 0.4, gpu 1.4, viz 0.5 | 13.3 / 13.5 / 13.5 | 3.5 / 3.8 / 4.0 |
| 3 | 19 / 20 | yes | 3.9 / 4.5 / 5.9 | 13.3 / 13.5 / 13.5 | 3.3 / 3.8 / 4.8 | main 4.5, compositor 0.5, gpu 1.4, viz 0.4 | 13.3 / 13.4 / 13.6 | 3.5 / 3.9 / 4.4 |

## T-NFR-14 static 100k worst render=default color=service y=log dpr=1

| Run | CPU before / during (%) | Quiet | Frame time p50 / p95 / max | Interval p50 / p95 / max | JS p50 / p95 / max | p95 busy per thread | Untraced interval p50 / p95 / max | Untraced JS p50 / p95 / max |
|---|---|---|---|---|---|---|---|---|
| 1 | 13 / 30 | yes | 5.5 / 6.4 / 6.9 | 13.3 / 13.5 / 13.5 | 4.9 / 5.6 / 5.9 | main 6.4, compositor 0.5, gpu 1.5, viz 0.5 | 13.3 / 13.5 / 13.6 | 3.9 / 4.3 / 4.7 |
| 2 | 17 / 33 | yes | 4.5 / 5.2 / 6.1 | 13.3 / 13.5 / 13.5 | 3.9 / 4.3 / 5.1 | main 5.2, compositor 0.5, gpu 1.5, viz 0.5 | 13.3 / 13.4 / 13.5 | 3.9 / 4.2 / 4.6 |
| 3 | 13 / 24 | yes | 4.4 / 5.2 / 6.5 | 13.3 / 13.5 / 13.5 | 3.9 / 4.3 / 4.7 | main 5.2, compositor 0.4, gpu 1.5, viz 0.5 | 13.3 / 13.4 / 13.5 | 3.9 / 4.5 / 5.0 |

## T-NFR-14 static 100k render=default dpr=2

| Run | CPU before / during (%) | Quiet | Frame time p50 / p95 / max | Interval p50 / p95 / max | JS p50 / p95 / max | p95 busy per thread | Untraced interval p50 / p95 / max | Untraced JS p50 / p95 / max |
|---|---|---|---|---|---|---|---|---|
| 1 | 18 / 38 | yes | 7.5 / 8.6 / 10.3 | 13.3 / 13.4 / 13.6 | 6.9 / 7.7 / 8.0 | main 8.6, compositor 0.5, gpu 3.3, viz 0.5 | 13.3 / 13.4 / 13.5 | 6.9 / 7.8 / 8.1 |
| 2 | 19 / 27 | yes | 7.6 / 8.4 / 10.0 | 13.3 / 13.5 / 13.6 | 7.0 / 7.7 / 8.2 | main 8.4, compositor 0.5, gpu 3.1, viz 0.6 | 13.3 / 13.5 / 13.5 | 7.0 / 7.7 / 8.2 |
| 3 | 15 / 33 | yes | 7.1 / 8.3 / 9.3 | 13.3 / 13.5 / 13.6 | 6.5 / 7.4 / 8.3 | main 8.3, compositor 0.5, gpu 3.2, viz 0.5 | 13.3 / 13.5 / 21.3 | 6.6 / 7.5 / 7.7 |

## T-NFR-14 static 100k worst render=default color=service y=log dpr=2

| Run | CPU before / during (%) | Quiet | Frame time p50 / p95 / max | Interval p50 / p95 / max | JS p50 / p95 / max | p95 busy per thread | Untraced interval p50 / p95 / max | Untraced JS p50 / p95 / max |
|---|---|---|---|---|---|---|---|---|
| 1 | 16 / 30 | yes | 8.9 / 10.3 / 11.5 | 13.3 / 13.5 / 13.6 | 8.1 / 9.7 / 10.7 | main 10.3, compositor 0.4, gpu 3.4, viz 0.4 | 13.3 / 13.5 / 13.5 | 8.2 / 9.8 / 13.2 |
| 2 | 14 / 35 | yes | 8.7 / 10.9 / 12.1 | 13.3 / 13.5 / 13.5 | 8.2 / 10.3 / 11.4 | main 10.9, compositor 0.5, gpu 3.4, viz 0.5 | 13.3 / 13.4 / 13.5 | 8.6 / 10.2 / 12.7 |
| 3 | 19 / 37 | yes | 9.0 / 10.5 / 11.9 | 13.3 / 13.5 / 15.2 | 8.3 / 9.7 / 10.8 | main 10.5, compositor 0.5, gpu 3.6, viz 0.6 | 13.3 / 13.5 / 13.6 | 8.2 / 9.9 / 11.0 |
