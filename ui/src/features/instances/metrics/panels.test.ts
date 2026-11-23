// SPDX-License-Identifier: Apache-2.0
//
// Instance chart model (05 §2.7-2, WO-37): the discovered names of docs/10 §11 map onto the panels
// (heap by area, memory pools, GC per collector, threads with virtual threads, classes, CPU with
// the container limit, FD, Tomcat per connector, pools, system memory, disk, network) keyed by the
// stored series name; a panel without reported series is "Not measured" with no lines; null
// buckets stay null (D-23); the 7-day baseline moves the earlier window onto the current grid.
import { describe, expect, it } from 'vitest';

import { grid, metricName, metricsBody, series } from '../testing/fixtures';

import {
  agentNames,
  BASELINE_OFFSET_MS,
  buildPanels,
  chartNames,
  PANELS,
  seriesSummary,
  shiftBaseline,
  type SeriesLabelKind,
} from './panels';

/** Requirements of every titled test in this file (test/README.md §5). */
export const REQ = { type: 'req', description: 'F-U08' };
export const REQ_MISSING = { type: 'req', description: 'D-23' };

const FROM = Date.parse('2026-11-20T11:00:00.000Z');
const label = (kind: SeriesLabelKind, name: string): string => `${kind}:${name}`;

const heapUsed = metricName('jvm_memory_used_bytes', { area: 'heap' });
const heapMax = metricName('jvm_memory_max_bytes', { area: 'heap' });
const gcYoung = metricName(
  'jvm_gc_collection_count_total',
  { gc: 'g1', gc_type: 'young' },
  'counter',
);
const gcPause = metricName('jvm_gc_pause_seconds_total', { gc: 'g1' }, 'counter');
const vtStarted = metricName('jvm_threads_virtual_started_total', {}, 'counter');
const live = metricName('jvm_threads_live');
const tomcat = metricName('tomcat_threads_busy', { connector: 'http-nio-8080' });
const pool = metricName('jdbc_pool_active', { pool: 'HikariPool-1' });
const cpu = metricName('process_cpu_usage_ratio');
const cpuLimit = metricName('container_cpu_limit_cores');
const dropped = metricName('argos_agent_dropped_total', { queue: 'tx' }, 'counter');
const other = metricName('http_server_requests');

describe('chartNames and agentNames', () => {
  it('requests the panel series and the container CPU limit, not unrelated or agent series', () => {
    const names = chartNames([heapUsed, other, cpuLimit, dropped, pool]);
    expect(names).toEqual([heapUsed.name, cpuLimit.name, pool.name]);
    expect(agentNames([heapUsed, dropped, other])).toEqual([dropped.name]);
  });

  it('covers every metric group of 05 §2.7-2', () => {
    expect(PANELS.map((p) => p.id)).toEqual([
      'heap',
      'memory-pools',
      'gc-count',
      'gc-pause',
      'threads',
      'classes',
      'cpu',
      'fd',
      'tomcat',
      'jdbc-pools',
      'system-memory',
      'disk',
      'network',
    ]);
  });
});

describe('buildPanels', () => {
  const ts = grid(FROM, 3);
  const reported = [
    heapUsed,
    heapMax,
    gcYoung,
    gcPause,
    vtStarted,
    live,
    tomcat,
    pool,
    cpu,
    cpuLimit,
  ];
  const answer = {
    data: metricsBody(ts, [
      series(heapUsed, ts, [100, null, 300]),
      series(heapMax, ts, [1000, 1000, 1000]),
      series(gcYoung, ts, [0, 2, 1]),
      series(gcPause, ts, [0, 0.02, 0.01]),
      series(live, ts, [40, 41, 42]),
      series(vtStarted, ts, [5, 0, 7]),
      series(tomcat, ts, [3, 4, 5]),
      series(pool, ts, [1, 2, 3]),
      series(cpu, ts, [0.1, 0.2, 0.3]),
      series(cpuLimit, ts, [1.5, 1.5, null]),
    ]),
    meta: { request_id: 'r', generated_at: '2026-11-20T12:00:00.000Z' },
  };

  it('maps series onto panels keyed by stored name with label parts and units', () => {
    const panels = buildPanels(reported, answer, label);
    const heap = panels.find((p) => p.id === 'heap');
    expect(heap?.series.map((s) => s.key)).toEqual([heapUsed.name, heapMax.name]);
    expect(heap?.series.map((s) => s.label)).toEqual(['memoryUsed:heap', 'memoryMax:heap']);
    expect(heap?.series[0]?.unit).toBe('bytes');
    const gc = panels.find((p) => p.id === 'gc-count');
    expect(gc?.series[0]?.label).toBe('gcCount:g1 young');
    expect(panels.find((p) => p.id === 'gc-pause')?.series[0]?.unit).toBe('seconds');
    const threads = panels.find((p) => p.id === 'threads');
    expect(threads?.series.map((s) => s.label)).toEqual(['threadsLive:', 'threadsVirtualStarted:']);
    expect(panels.find((p) => p.id === 'tomcat')?.series[0]?.label).toBe(
      'tomcatBusy:http-nio-8080',
    );
    expect(panels.find((p) => p.id === 'jdbc-pools')?.series[0]?.label).toBe(
      'jdbcActive:HikariPool-1',
    );
  });

  it('T-UI-007 keeps null buckets null, never 0', () => {
    const heap = buildPanels(reported, answer, label).find((p) => p.id === 'heap');
    expect(heap?.series[0]?.values).toEqual([100, null, 300]);
    expect(seriesSummary(heap?.series ?? [])).toBe(
      JSON.stringify({ [heapUsed.name]: { n: 2, sum: 400 }, [heapMax.name]: { n: 3, sum: 3000 } }),
    );
  });

  it('marks panels without reported series "Not measured" and draws no line for them', () => {
    const panels = buildPanels(reported, answer, label);
    const fd = panels.find((p) => p.id === 'fd');
    expect(fd?.notes).toEqual(['notMeasured']);
    expect(fd?.series).toEqual([]);
    expect(panels.find((p) => p.id === 'heap')?.notes).toEqual([]);
  });

  it('notes the latest container CPU limit under the CPU panel', () => {
    const cpuPanel = buildPanels(reported, answer, label).find((p) => p.id === 'cpu');
    expect(cpuPanel?.notes).toEqual(['containerCpuLimit']);
    expect(cpuPanel?.cpuLimitCores).toBe(1.5);
    const without = buildPanels([cpu], answer, label).find((p) => p.id === 'cpu');
    expect(without?.series.map((s) => s.key)).toEqual([cpu.name]);
  });

  it('adds the 7-day baseline per series key', () => {
    const shifted = { [heapUsed.name]: [9, 8, 7] };
    const heap = buildPanels(reported, answer, label, shifted).find((p) => p.id === 'heap');
    expect(heap?.baseline).toEqual({ offset: '7d', values: { [heapUsed.name]: [9, 8, 7] } });
    const fd = buildPanels(reported, answer, label, shifted).find((p) => p.id === 'fd');
    expect(fd?.baseline).toBeNull();
  });
});

describe('shiftBaseline', () => {
  it('moves the window 7 days earlier onto the current grid; missing buckets stay null', () => {
    const current = grid(FROM, 3).map((t) => Date.parse(t));
    const earlierTs = grid(FROM - BASELINE_OFFSET_MS, 2);
    const baseline = metricsBody(earlierTs, [series(heapUsed, earlierTs, [11, null])]);
    expect(BASELINE_OFFSET_MS).toBe(7 * 24 * 3600 * 1000);
    expect(shiftBaseline(current, baseline, BASELINE_OFFSET_MS)).toEqual({
      [heapUsed.name]: [11, null, null],
    });
    expect(shiftBaseline(current, null, BASELINE_OFFSET_MS)).toEqual({});
  });
});
