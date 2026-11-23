// SPDX-License-Identifier: Apache-2.0
//
// required/instance.yaml is valid and covers the docs/05 §2.7 checklist (docs/04 WO-37) one entry
// per bullet, in order, so that T-E2E-instance-<n> carries the bullet's number, with checks that
// exist in required/instanceChecks.ts and route variables of manifestVariables() (docs/18 §13,
// DSN-18-10); and the pure oracle helpers of instanceChecks.ts compute what the screen must show.
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

import { describe, expect, it } from 'vitest';

import { MISSING_DASH } from '../../src/utils/missing';
import type { SeedManifest } from '../stack/manifest';
import { TRAFFIC_INSTANCE } from '../stack/seed/traffic';

import { CHECKS } from './checks';
import {
  badgeState,
  chartNamesOf,
  chunkNames,
  expectedHeader,
  expectedPanels,
  INSTANCE_CHECKS,
  instancePath,
  markersOf,
  metricLatest,
  metricSum,
  PANEL_IDS,
  panelOf,
  replayQuery,
  restartsOf,
  seriesSum,
  trafficInstance,
  type InstanceApi,
  type MetricNameApi,
  type MetricsApi,
} from './instanceChecks';
import {
  fillTemplate,
  parseRequiredFile,
  RUNNING_OWNERS,
  templateVariables,
  testTitle,
} from './schema';
import { manifestVariables } from './variables';

const DIR = dirname(fileURLToPath(import.meta.url));
const FILE = join(DIR, 'instance.yaml');

/** As much of the seed manifest as manifestVariables() and trafficInstance() read. */
const SEED = {
  generator: { range: { start: '2030-01-01T00:00:00Z', end: '2030-01-01T00:43:00Z' } },
  apps: [
    {
      app_id: 3,
      name: 'order-api',
      env: 'prod',
      source: 'generator',
      instances: [{ instance_id: 12, name: 'order-1' }],
    },
    {
      app_id: 11,
      name: 'e2e-shop',
      env: 'e2e',
      source: 'traffic',
      instances: [
        { instance_id: 40, name: 'e2e-shop-live' },
        { instance_id: 41, name: TRAFFIC_INSTANCE },
      ],
    },
  ],
  primary_app_id: 3,
  secondary_app_id: 4,
  traffic: {
    app_id: 11,
    range: { start: '2030-01-01T01:00:00Z', end: '2030-01-01T01:20:00Z' },
  },
  windows: [],
  tx: {},
} as unknown as SeedManifest;

const INSTANCE: InstanceApi = {
  instance_id: 41,
  name: TRAFFIC_INSTANCE,
  state: 'UP',
  app: { app_id: 11, name: 'e2e-shop', env: 'e2e' },
  version: null,
  agent_version: '0.0.0-SNAPSHOT',
  compat: 'OK',
  jvm_version: '21.0.5',
  jvm_vendor: 'Eclipse Adoptium',
  os: 'Linux 6.6',
  host: 'shop-host',
  pid: 4242,
  cgroup: null,
  started_at: '2030-01-01T00:59:00Z',
  clock_skew_ms: 3,
  drops_24h: 0,
  modules_disabled: [],
  ip_mode: 'masked',
  virtual_threads: true,
  remote_config_allow: ['sampling.normal-rate'],
  config_hash: 'sha256:x',
  config_drift: false,
};

describe('required/instance.yaml (docs/05 §2.7)', () => {
  const spec = parseRequiredFile(readFileSync(FILE, 'utf8'), FILE);

  it('lists 2.7-1 .. 2.7-6 once each, in order, every entry a running WO-37 item with a known check', () => {
    expect(spec.screen).toBe('instance');
    expect(spec.source).toBe('docs/05 §2.7');
    expect(spec.first_number).toBe(1);
    expect(spec.items.map((item) => item.id)).toEqual([
      '2.7-1',
      '2.7-2',
      '2.7-3',
      '2.7-4',
      '2.7-5',
      '2.7-6',
    ]);
    expect(RUNNING_OWNERS.has('WO-37')).toBe(true);
    for (const item of spec.items) {
      expect(item.owner, item.id).toBe('WO-37');
      expect(item.req, item.id).toContain('F-U08');
      const checks = item.assert.flatMap((a) => ('check' in a ? [a.check] : []));
      expect(checks, `${item.id}: one named check`).toHaveLength(1);
      for (const check of checks) {
        expect(Object.keys(INSTANCE_CHECKS), `${item.id}: ${check}`).toContain(check);
        expect(Object.keys(CHECKS), `${item.id}: ${check} registered in checks.ts`).toContain(
          check,
        );
      }
    }
  });

  it('runs the environment, commands and dumps tab items as an operator, the rest as a viewer', () => {
    expect(spec.items.map((item) => `${item.id} ${item.role}`)).toEqual([
      '2.7-1 viewer',
      '2.7-2 viewer',
      '2.7-3 operator',
      '2.7-4 operator',
      '2.7-5 operator',
      '2.7-6 viewer',
    ]);
  });

  it('fills every route variable from the manifest (the traffic application)', () => {
    const vars = manifestVariables(SEED);
    for (const item of spec.items) {
      for (const name of templateVariables(item.route ?? '')) {
        expect(vars, `${item.id} variable ${name}`).toHaveProperty([name]);
      }
      expect(fillTemplate(item.route ?? '', vars), item.id).toBe('/apps/11/instances');
    }
  });

  it('test titles follow T-E2E-instance-<n> <05 id> <title>, n = the bullet number', () => {
    spec.items.forEach((item, index) => {
      expect(testTitle(spec, index)).toMatch(
        new RegExp(`^T-E2E-instance-${String(index + 1)} ${item.id.replace('.', '\\.')} `),
      );
      expect(item.id).toBe(`2.7-${String(index + 1)}`);
    });
  });
});

describe('instance oracle helpers', () => {
  it('picks the real agent instance of the traffic seed and its range', () => {
    expect(trafficInstance(SEED)).toEqual({
      appId: 11,
      instanceId: 41,
      name: TRAFFIC_INSTANCE,
      from: '2030-01-01T01:00:00Z',
      to: '2030-01-01T01:20:00Z',
    });
    const without = { ...SEED, apps: [] } as unknown as SeedManifest;
    expect(() => trafficInstance(without)).toThrow(/no instance of the traffic app/);
  });

  it('writes the deep link of an instance with range, tab and further keys (metrics is the default tab)', () => {
    expect(instancePath(11, 41)).toBe('/apps/11/instances/41');
    expect(instancePath(11, 41, { from: 'a', to: 'b', tab: 'metrics' })).toBe(
      '/apps/11/instances/41?from=a&to=b',
    );
    expect(instancePath(11, 41, { tab: 'env', extra: { kind: 'ALL' } })).toBe(
      '/apps/11/instances/41?tab=env&kind=ALL',
    );
  });

  it('replays a request query with repeated keys as arrays', () => {
    expect(
      replayQuery('http://x/api/v1/apps/3/events?from=a&to=b&type=deploy&type=start&type=stop'),
    ).toEqual({ from: 'a', to: 'b', type: ['deploy', 'start', 'stop'] });
  });

  it('chunks metric names by 50 in order without duplicates (docs/14 §4.10)', () => {
    const names = Array.from({ length: 120 }, (_, i) => `m${String(i)}`);
    const chunks = chunkNames([...names, 'm0', 'm1']);
    expect(chunks.map((c) => c.length)).toEqual([50, 50, 20]);
    expect(chunks.flat()).toEqual(names);
    expect(chunkNames([])).toEqual([]);
  });

  it('sums a series without its empty buckets (D-09: null is not 0), rounded to 6 decimals', () => {
    expect(seriesSum([1, null, 2.5, Number.NaN])).toEqual({ n: 2, sum: 3.5 });
    expect(seriesSum([null, null])).toEqual({ n: 0, sum: 0 });
    expect(seriesSum([0.1, 0.2])).toEqual({ n: 2, sum: 0.3 });
  });

  it('maps the docs/10 §11 metric names to the panels; unreported panels are not measured', () => {
    expect(PANEL_IDS).toEqual([
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
    expect(panelOf('jvm_memory_used_bytes')).toBe('heap');
    expect(panelOf('jvm_gc_pause_seconds_total')).toBe('gc-pause');
    expect(panelOf('jdbc_pool_waiting')).toBe('jdbc-pools');
    expect(panelOf('argos_agent_dropped_total')).toBeNull();
    const names: MetricNameApi[] = [
      {
        name: 'jvm_memory_used_bytes{area="heap"}',
        metric: 'jvm_memory_used_bytes',
        labels: { area: 'heap' },
      },
      { name: 'jvm_threads_live', metric: 'jvm_threads_live', labels: {} },
      { name: 'container_cpu_limit_cores', metric: 'container_cpu_limit_cores', labels: {} },
      {
        name: 'argos_agent_dropped_total{queue="transaction"}',
        metric: 'argos_agent_dropped_total',
        labels: { queue: 'transaction' },
      },
    ];
    expect(chartNamesOf(names)).toEqual([
      'jvm_memory_used_bytes{area="heap"}',
      'jvm_threads_live',
      'container_cpu_limit_cores',
    ]);
    const answer: MetricsApi = {
      ts: ['2030-01-01T01:00:00Z', '2030-01-01T01:01:00Z'],
      series: [
        {
          name: 'jvm_memory_used_bytes{area="heap"}',
          metric: 'jvm_memory_used_bytes',
          labels: { area: 'heap' },
          value: [100, null],
        },
        { name: 'jvm_threads_live', metric: 'jvm_threads_live', labels: {}, value: [null, null] },
        {
          name: 'container_cpu_limit_cores',
          metric: 'container_cpu_limit_cores',
          labels: {},
          value: [1.5, 1.5],
        },
      ],
    };
    const panels = expectedPanels(names, answer);
    expect(panels.heap).toEqual({
      measured: true,
      summary: { 'jvm_memory_used_bytes{area="heap"}': { n: 1, sum: 100 } },
    });
    expect(panels.threads).toEqual({
      measured: true,
      summary: { jvm_threads_live: { n: 0, sum: 0 } },
    });
    // The CPU limit is a note of the CPU panel, not a series of it.
    expect(panels.cpu).toEqual({ measured: false, summary: {} });
    expect(panels.tomcat).toEqual({ measured: false, summary: {} });
  });

  it('counts restarts (start events of the instance) and markers (deploys, own start/stop/config)', () => {
    const events = [
      { event_id: 'a', type: 'start', ts: 't', instance_id: 41 },
      { event_id: 'b', type: 'start', ts: 't', instance_id: 40 },
      { event_id: 'c', type: 'deploy', ts: 't', instance_id: null },
      { event_id: 'd', type: 'stop', ts: 't', instance_id: 41 },
      { event_id: 'e', type: 'config', ts: 't', instance_id: 40 },
      { event_id: 'f', type: 'start', ts: 't', instance_id: 41 },
    ];
    expect(restartsOf(events, 41)).toBe(2);
    expect(markersOf(events, 41)).toBe(4);
    expect(restartsOf([], 41)).toBe(0);
  });

  it('reduces the agent series of the self-diagnosis summary: sums of counters, latest gauge, null without values', () => {
    const answer: MetricsApi = {
      ts: ['a', 'b', 'c'],
      series: [
        {
          name: 'd{queue="transaction"}',
          metric: 'argos_agent_dropped_total',
          labels: {},
          value: [0, 2, null],
        },
        {
          name: 'd{queue="profile"}',
          metric: 'argos_agent_dropped_total',
          labels: {},
          value: [1, null, 0],
        },
        {
          name: 'h',
          metric: 'argos_agent_heap_bytes_estimated',
          labels: {},
          value: [10, 20, null],
        },
        { name: 'o', metric: 'argos_agent_clock_offset_ms', labels: {}, value: [null, null, null] },
      ],
    };
    expect(metricSum(answer, 'argos_agent_dropped_total')).toBe(3);
    expect(metricLatest(answer, 'argos_agent_heap_bytes_estimated')).toBe(20);
    expect(metricLatest(answer, 'argos_agent_clock_offset_ms')).toBeNull();
    expect(metricSum(answer, 'argos_agent_cpu_seconds_total')).toBeNull();
  });

  it('expects every header item of 05 §2.7-1 from the instance answer, missing values without data-value', () => {
    const header = expectedHeader(INSTANCE, 2);
    expect(Object.keys(header)).toEqual([
      'app',
      'version',
      'agent-version',
      'os',
      'host',
      'pid',
      'cgroup',
      'started-at',
      'restarts',
      'clock-skew',
      'drops',
      'modules-disabled',
      'ip-mode',
      'virtual-threads',
    ]);
    expect(header.app).toEqual({ value: '11' });
    expect(header.version).toEqual({ value: null, text: MISSING_DASH });
    expect(header['agent-version']).toEqual({ value: 'ok', dashWhenMissing: false });
    expect(header.cgroup).toEqual({ value: null, text: MISSING_DASH });
    expect(header['started-at']).toEqual({ value: String(Date.parse('2030-01-01T00:59:00Z')) });
    expect(header.restarts).toEqual({ value: '2' });
    expect(header.drops).toEqual({ value: '0' });
    expect(header['modules-disabled']).toEqual({ value: '0' });
    expect(header['ip-mode']).toEqual({ value: 'masked' });
    expect(header['virtual-threads']).toEqual({ value: 'on' });
    const missing = expectedHeader(
      {
        ...INSTANCE,
        compat: 'future',
        pid: null,
        started_at: null,
        clock_skew_ms: null,
        drops_24h: null,
        modules_disabled: null,
        ip_mode: null,
        virtual_threads: null,
      },
      0,
    );
    for (const id of [
      'agent-version',
      'pid',
      'started-at',
      'clock-skew',
      'drops',
      'modules-disabled',
      'ip-mode',
      'virtual-threads',
    ]) {
      expect(missing[id]?.value, id).toBeNull();
    }
    expect(expectedHeader({ ...INSTANCE, virtual_threads: false }, 0)['virtual-threads']).toEqual({
      value: 'off',
    });
  });

  it('normalizes the badge state as the status badge does', () => {
    expect(badgeState('up')).toBe('UP');
    expect(badgeState('STALE')).toBe('STALE');
    expect(badgeState('archived')).toBe('UNKNOWN');
  });
});
