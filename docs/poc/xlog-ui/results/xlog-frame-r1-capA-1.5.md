# XLog frame time (T-NFR-14)

- Started: 2026-10-23T13:56:10.504Z
- Machine: Windows_NT 10.0.26200 x64, AMD Ryzen 5 5600H with Radeon Graphics, 12 logical cores, 15 GB
- Browser: chromium 156.0.8078.4 (headless), Playwright 1.64.1, Node v24.14.0
- GPU: ANGLE (NVIDIA, NVIDIA GeForce RTX 3050 Ti Laptop GPU (0x000025A0) Direct3D11 vs_5_0 ps_5_0, D3D11)
- Viewport 1366x768, DPR 2 (per scenario), plot 1254x382 CSS px, refresh 13.3 ms

Frame time = busiest rendering-pipeline thread per frame (Chrome trace). Budget: p95 and mean <= 16 ms of the frames of all runs together (pooled). Max run p95 / Max run mean: the highest p95 and mean of a single run (reported, not judged). Canvas px: backing store of the point layer (capped, ADR-0003 C-34) / the overlay.

| Scenario | DPR | Canvas px (points / overlay) | Mode | Frames | Frame time p50 / p95 / max (ms) | Mean | Max run p95 (ms) | Max run mean (ms) | Interval p50 / p95 / max (ms) | JS p50 / p95 / max (ms) | Busiest thread | CPU load before (%) | Verdict |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| T-NFR-14 static 100k render=default pointcap=1.5 dpr=2 | 2 | 1881x573 / 2668x840 | pixels | 357 | 5.0 / 6.5 / 8.0 | 5.2 | 6.7 | 5.3 | 13.3 / 13.5 / 15.3 | 4.5 / 5.8 / 6.6 | main 100%, gpu 0% | 7-15 | PASS |
| T-NFR-14 static 100k worst render=default color=service y=log pointcap=1.5 dpr=2 | 2 | 1881x573 / 2668x840 | pixels | 357 | 6.5 / 8.7 / 13.2 | 6.6 | 9.9 | 7.0 | 13.3 / 13.5 / 13.8 | 6.0 / 8.1 / 11.4 | main 100% | 6-12 | PASS |

## T-NFR-14 static 100k render=default pointcap=1.5 dpr=2

| Run | CPU before / during (%) | Quiet | Frame time p50 / p95 / max | Interval p50 / p95 / max | JS p50 / p95 / max | p95 busy per thread | Untraced interval p50 / p95 / max | Untraced JS p50 / p95 / max |
|---|---|---|---|---|---|---|---|---|
| 1 | 7 / 28 | yes | 4.9 / 6.5 / 8.0 | 13.3 / 13.5 / 14.0 | 4.4 / 5.9 / 6.5 | main 6.5, compositor 0.4, gpu 2.8, viz 0.5 | 13.3 / 13.5 / 13.5 | 4.5 / 5.2 / 5.7 |
| 2 | 14 / 30 | yes | 5.1 / 6.7 / 7.2 | 13.3 / 13.5 / 15.3 | 4.5 / 5.9 / 6.6 | main 6.7, compositor 0.5, gpu 2.8, viz 0.4 | 13.3 / 13.5 / 13.5 | 4.6 / 5.7 / 7.4 |
| 3 | 15 / 19 | yes | 5.1 / 6.3 / 7.2 | 13.3 / 13.5 / 13.6 | 4.6 / 5.7 / 6.0 | main 6.3, compositor 0.4, gpu 2.7, viz 0.4 | 13.3 / 13.4 / 13.5 | 4.4 / 5.5 / 5.9 |

## T-NFR-14 static 100k worst render=default color=service y=log pointcap=1.5 dpr=2

| Run | CPU before / during (%) | Quiet | Frame time p50 / p95 / max | Interval p50 / p95 / max | JS p50 / p95 / max | p95 busy per thread | Untraced interval p50 / p95 / max | Untraced JS p50 / p95 / max |
|---|---|---|---|---|---|---|---|---|
| 1 | 11 / 27 | yes | 6.8 / 8.9 / 9.7 | 13.3 / 13.5 / 13.5 | 6.2 / 8.4 / 8.7 | main 8.9, compositor 0.4, gpu 2.7, viz 0.6 | 13.3 / 13.4 / 13.6 | 5.3 / 6.6 / 7.7 |
| 2 | 6 / 43 | yes | 6.7 / 9.9 / 13.2 | 13.3 / 13.4 / 13.8 | 6.1 / 9.2 / 11.4 | main 9.9, compositor 0.5, gpu 3.6, viz 0.5 | 13.3 / 13.5 / 13.8 | 5.7 / 6.5 / 7.2 |
| 3 | 12 / 21 | yes | 5.6 / 7.1 / 8.1 | 13.3 / 13.5 / 13.6 | 5.0 / 6.5 / 7.1 | main 7.1, compositor 0.4, gpu 2.5, viz 0.5 | 13.3 / 13.4 / 13.7 | 5.0 / 6.3 / 8.4 |
