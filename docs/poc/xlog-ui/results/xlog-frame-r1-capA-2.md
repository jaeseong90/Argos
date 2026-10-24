# XLog frame time (T-NFR-14)

- Started: 2026-10-23T13:58:13.069Z
- Machine: Windows_NT 10.0.26200 x64, AMD Ryzen 5 5600H with Radeon Graphics, 12 logical cores, 15 GB
- Browser: chromium 156.0.8078.4 (headless), Playwright 1.64.1, Node v24.14.0
- GPU: ANGLE (NVIDIA, NVIDIA GeForce RTX 3050 Ti Laptop GPU (0x000025A0) Direct3D11 vs_5_0 ps_5_0, D3D11)
- Viewport 1366x768, DPR 2 (per scenario), plot 1254x382 CSS px, refresh 13.3 ms

Frame time = busiest rendering-pipeline thread per frame (Chrome trace). Budget: p95 and mean <= 16 ms of the frames of all runs together (pooled). Max run p95 / Max run mean: the highest p95 and mean of a single run (reported, not judged). Canvas px: backing store of the point layer (capped, ADR-0003 C-34) / the overlay.

| Scenario | DPR | Canvas px (points / overlay) | Mode | Frames | Frame time p50 / p95 / max (ms) | Mean | Max run p95 (ms) | Max run mean (ms) | Interval p50 / p95 / max (ms) | JS p50 / p95 / max (ms) | Busiest thread | CPU load before (%) | Verdict |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| T-NFR-14 static 100k render=default pointcap=2 dpr=2 | 2 | 2508x764 / 2668x840 | pixels | 357 | 7.0 / 8.4 / 24.0 | 7.0 | 8.7 | 7.4 | 13.3 / 13.5 / 24.0 | 6.4 / 7.6 / 23.5 | main 100% | 15-18 | PASS |
| T-NFR-14 static 100k worst render=default color=service y=log pointcap=2 dpr=2 | 2 | 2508x764 / 2668x840 | pixels | 357 | 8.3 / 9.8 / 15.8 | 8.1 | 10.1 | 8.4 | 13.3 / 13.5 / 15.9 | 7.7 / 9.1 / 13.2 | main 100% | 13-14 | PASS |

## T-NFR-14 static 100k render=default pointcap=2 dpr=2

| Run | CPU before / during (%) | Quiet | Frame time p50 / p95 / max | Interval p50 / p95 / max | JS p50 / p95 / max | p95 busy per thread | Untraced interval p50 / p95 / max | Untraced JS p50 / p95 / max |
|---|---|---|---|---|---|---|---|---|
| 1 | 18 / 33 | yes | 7.2 / 8.7 / 24.0 | 13.3 / 13.5 / 24.0 | 6.6 / 7.7 / 23.5 | main 8.7, compositor 0.4, gpu 3.4, viz 0.4 | 13.3 / 13.4 / 13.6 | 6.9 / 7.6 / 7.8 |
| 2 | 15 / 22 | yes | 6.9 / 8.0 / 8.4 | 13.3 / 13.5 / 13.5 | 6.3 / 7.4 / 7.8 | main 8.0, compositor 0.4, gpu 2.8, viz 0.5 | 13.3 / 13.5 / 13.6 | 6.1 / 7.4 / 8.4 |
| 3 | 16 / 36 | yes | 6.6 / 8.5 / 9.8 | 13.3 / 13.5 / 13.6 | 5.9 / 7.6 / 9.2 | main 8.5, compositor 0.4, gpu 3.4, viz 0.5 | 13.3 / 13.5 / 13.5 | 6.1 / 7.4 / 7.9 |

## T-NFR-14 static 100k worst render=default color=service y=log pointcap=2 dpr=2

| Run | CPU before / during (%) | Quiet | Frame time p50 / p95 / max | Interval p50 / p95 / max | JS p50 / p95 / max | p95 busy per thread | Untraced interval p50 / p95 / max | Untraced JS p50 / p95 / max |
|---|---|---|---|---|---|---|---|---|
| 1 | 13 / 30 | yes | 8.5 / 9.9 / 10.4 | 13.3 / 13.5 / 13.5 | 7.8 / 9.1 / 9.7 | main 9.9, compositor 0.5, gpu 3.3, viz 0.5 | 13.3 / 13.5 / 13.5 | 8.0 / 9.3 / 12.2 |
| 2 | 14 / 23 | yes | 8.2 / 9.4 / 10.0 | 13.3 / 13.5 / 13.5 | 7.2 / 8.8 / 9.4 | main 9.4, compositor 0.5, gpu 3.1, viz 0.6 | 13.3 / 13.5 / 13.5 | 6.6 / 8.5 / 9.9 |
| 3 | 13 / 36 | yes | 8.5 / 10.1 / 15.8 | 13.3 / 13.5 / 15.9 | 7.9 / 9.4 / 13.2 | main 10.1, compositor 0.5, gpu 3.4, viz 0.5 | 13.3 / 13.5 / 13.6 | 8.4 / 10.8 / 13.2 |
