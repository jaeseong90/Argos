# XLog frame time (T-NFR-14)

- Started: 2026-10-23T13:47:29.851Z
- Machine: Windows_NT 10.0.26200 x64, AMD Ryzen 5 5600H with Radeon Graphics, 12 logical cores, 15 GB
- Browser: chromium 156.0.8078.4 (headless), Playwright 1.64.1, Node v24.14.0
- GPU: ANGLE (NVIDIA, NVIDIA GeForce RTX 3050 Ti Laptop GPU (0x000025A0) Direct3D11 vs_5_0 ps_5_0, D3D11)
- Viewport 1366x768, DPR 2 (per scenario), plot 1254x382 CSS px, refresh 13.3 ms

Frame time = busiest rendering-pipeline thread per frame (Chrome trace). Budget: p95 and mean <= 16 ms of the frames of all runs together (pooled). Max run p95 / Max run mean: the highest p95 and mean of a single run (reported, not judged). Canvas px: backing store of the point layer (capped, ADR-0003 C-34) / the overlay.

| Scenario | DPR | Canvas px (points / overlay) | Mode | Frames | Frame time p50 / p95 / max (ms) | Mean | Max run p95 (ms) | Max run mean (ms) | Interval p50 / p95 / max (ms) | JS p50 / p95 / max (ms) | Busiest thread | CPU load before (%) | Verdict |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| T-NFR-14 static 100k render=default pointcap=1 dpr=2 | 2 | 1254x382 / 2668x840 | pixels | 357 | 4.3 / 5.6 / 26.8 | 4.4 | 6.1 | 5.0 | 13.3 / 13.5 / 26.8 | 3.7 / 4.6 / 5.5 | main 99%, gpu 1% | 20-73 | PASS |
| T-NFR-14 static 100k worst render=default color=service y=log pointcap=1 dpr=2 | 2 | 1254x382 / 2668x840 | pixels | 357 | 4.3 / 5.5 / 9.4 | 4.4 | 5.8 | 4.6 | 13.3 / 13.5 / 14.7 | 3.8 / 4.7 / 5.5 | main 99%, gpu 1% | 7-18 | PASS |

## T-NFR-14 static 100k render=default pointcap=1 dpr=2

| Run | CPU before / during (%) | Quiet | Frame time p50 / p95 / max | Interval p50 / p95 / max | JS p50 / p95 / max | p95 busy per thread | Untraced interval p50 / p95 / max | Untraced JS p50 / p95 / max |
|---|---|---|---|---|---|---|---|---|
| 1 | 73 / 74 | no (waited 148 s) | 4.6 / 6.1 / 26.8 | 13.3 / 13.6 / 26.8 | 4.0 / 4.8 / 5.5 | main 6.1, compositor 0.5, gpu 3.4, viz 0.6 | 13.3 / 13.4 / 13.5 | 3.7 / 4.3 / 4.7 |
| 2 | 54 / 44 | no (waited 148 s) | 4.1 / 5.3 / 6.1 | 13.3 / 13.5 / 14.5 | 3.6 / 4.6 / 5.2 | main 5.3, compositor 0.4, gpu 2.7, viz 0.5 | 13.3 / 13.5 / 13.7 | 3.3 / 4.0 / 5.0 |
| 3 | 20 / 23 | yes | 3.9 / 5.0 / 5.6 | 13.3 / 13.5 / 13.8 | 3.4 / 4.0 / 4.8 | main 5.0, compositor 0.4, gpu 2.9, viz 0.5 | 13.3 / 13.5 / 13.5 | 3.1 / 3.7 / 4.1 |

## T-NFR-14 static 100k worst render=default color=service y=log pointcap=1 dpr=2

| Run | CPU before / during (%) | Quiet | Frame time p50 / p95 / max | Interval p50 / p95 / max | JS p50 / p95 / max | p95 busy per thread | Untraced interval p50 / p95 / max | Untraced JS p50 / p95 / max |
|---|---|---|---|---|---|---|---|---|
| 1 | 15 / 22 | yes | 4.4 / 5.8 / 6.1 | 13.3 / 13.5 / 13.5 | 4.0 / 5.1 / 5.5 | main 5.8, compositor 0.5, gpu 2.6, viz 0.5 | 13.3 / 13.5 / 13.6 | 3.6 / 4.0 / 4.7 |
| 2 | 7 / 32 | yes | 4.3 / 5.3 / 9.4 | 13.3 / 13.5 / 13.6 | 3.8 / 4.5 / 5.1 | main 5.3, compositor 0.4, gpu 2.8, viz 0.4 | 13.3 / 13.5 / 13.5 | 3.3 / 4.1 / 5.6 |
| 3 | 18 / 25 | yes | 3.9 / 4.7 / 5.5 | 13.3 / 13.5 / 14.7 | 3.4 / 4.1 / 5.0 | main 4.7, compositor 0.4, gpu 2.7, viz 0.5 | 13.3 / 13.5 / 13.5 | 3.4 / 3.9 / 4.4 |
