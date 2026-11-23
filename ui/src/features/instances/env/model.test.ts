// SPDX-License-Identifier: Apache-2.0
//
// Environment tab model (05 §2.7-3, WO-37): the effective configuration merges the server's view
// with the latest self diagnosis (agent value and source win; without a report the server's value
// and an unknown source), modules join modules_disabled, the error counter over the range and the
// self diagnosis, and the public self-diagnosis summary sums counters and takes the latest gauges;
// missing series are null, never 0 (D-23).
import { describe, expect, it } from 'vitest';

import { untrusted } from '../../../api/untrusted';
import { configEntry, grid, metricName, metricsBody, series } from '../testing/fixtures';

import { agentSummary, mergeEffectiveConfig, moduleRows, sumValues } from './model';

/** Requirements of every titled test in this file (test/README.md §5). */
export const REQ = { type: 'req', description: 'F-U08' };
export const REQ_MISSING = { type: 'req', description: 'D-23' };

const FROM = Date.parse('2026-11-20T11:00:00.000Z');

describe('mergeEffectiveConfig', () => {
  const server = [
    configEntry('sampling.normal-rate', {
      server_value: untrusted('0.2'),
      server_status: 'applied',
      value: untrusted('0.2'),
      source: 'server',
    }),
    configEntry('log.level', { remote_allowed: true }),
    configEntry('capture.sql-parameters', { remote_allowed: false }),
  ];

  it('shows the server values with unknown sources without a self diagnosis', () => {
    const rows = mergeEffectiveConfig(server, null);
    expect(rows.map((r) => r.key)).toEqual([
      'capture.sql-parameters',
      'log.level',
      'sampling.normal-rate',
    ]);
    const pushed = rows.find((r) => r.key === 'sampling.normal-rate');
    expect(pushed).toMatchObject({ value: '0.2', source: 'server', origin: 'server' });
    const level = rows.find((r) => r.key === 'log.level');
    expect(level).toMatchObject({ value: null, source: null, origin: 'none', remoteAllowed: true });
  });

  it('takes the agent value and source of the self diagnosis and adds keys the server does not list', () => {
    const rows = mergeEffectiveConfig(server, [
      { key: 'log.level', value: 'INFO', source: 'file' },
      { key: 'server.address', value: 'collector:9100', source: 'env' },
    ]);
    expect(rows.find((r) => r.key === 'log.level')).toMatchObject({
      value: 'INFO',
      source: 'file',
      origin: 'agent',
      remoteAllowed: true,
    });
    expect(rows.find((r) => r.key === 'server.address')).toMatchObject({
      source: 'env',
      remoteAllowed: null,
      serverStatus: null,
    });
    expect(rows.find((r) => r.key === 'sampling.normal-rate')?.serverStatus).toBe('applied');
  });
});

describe('moduleRows', () => {
  const ts = grid(FROM, 3);
  const jdbcErrors = metricName('argos_agent_module_errors_total', { module: 'jdbc' }, 'counter');
  const agent = metricsBody(ts, [series(jdbcErrors, ts, [0, 2, null])]);

  it('joins disabled modules, the error counter and the self diagnosis', () => {
    const rows = moduleRows({ modules_disabled: [untrusted('kafka')] }, agent, [
      { name: 'servlet', state: 'active', errors: 0 },
      { name: 'jdbc', state: 'active', errors: 5 },
      { name: 'quartz', state: 'starting', errors: null },
    ]);
    expect(rows.map((r) => [r.name, r.state, r.errorsInRange, r.errorsReported])).toEqual([
      ['jdbc', 'active', 2, 5],
      ['kafka', 'disabled', null, null],
      ['quartz', 'unknown', null, null],
      ['servlet', 'active', null, 0],
    ]);
    expect(rows.find((r) => r.name === 'quartz')?.reportedState).toBe('starting');
  });

  it('T-UI-007 leaves the state unknown when the server does not track the instance', () => {
    const rows = moduleRows({ modules_disabled: null }, agent, null);
    expect(rows).toEqual([
      {
        name: 'jdbc',
        state: 'unknown',
        reportedState: null,
        errorsInRange: 2,
        errorsReported: null,
        reason: null,
      },
    ]);
  });

  it('reads the agent states DISABLED_BY_* as disabled and keeps the reason (C-234 (1))', () => {
    const rows = moduleRows({ modules_disabled: [] }, null, [
      { name: 'servlet', state: 'ENABLED', errors: 0, reason: null },
      {
        name: 'jdbc',
        state: 'DISABLED_BY_ERRORS',
        errors: 11,
        reason: 'error threshold reached (instrumentation.error-threshold)',
      },
      { name: 'kafka', state: 'DISABLED_BY_CONFIG', errors: 0, reason: 'instrumentation.disabled' },
      { name: 'okhttp', state: 'DISABLED_BY_REMOTE', errors: 0, reason: 'remote' },
    ]);
    expect(rows.map((r) => [r.name, r.state, r.reportedState, r.reason])).toEqual([
      ['jdbc', 'disabled', null, 'error threshold reached (instrumentation.error-threshold)'],
      ['kafka', 'disabled', null, 'instrumentation.disabled'],
      ['okhttp', 'disabled', null, 'remote'],
      ['servlet', 'active', null, null],
    ]);
  });
});

describe('agentSummary', () => {
  const ts = grid(FROM, 3);

  it('sums counters over the range and takes the latest gauges', () => {
    const summary = agentSummary(
      metricsBody(ts, [
        series(metricName('argos_agent_dropped_total', { queue: 'tx' }), ts, [0, 1, null]),
        series(metricName('argos_agent_dropped_total', { queue: 'metric' }), ts, [2, 0, 0]),
        series(metricName('argos_agent_cpu_seconds_total'), ts, [0.5, 0.25, 0.25]),
        series(metricName('argos_agent_heap_bytes_estimated'), ts, [1000, 2000, null]),
        series(metricName('argos_agent_clock_offset_ms'), ts, [3, null, null]),
        series(metricName('argos_agent_stack_capture_seconds_total'), ts, [0, 0, 0]),
      ]),
    );
    expect(summary).toEqual({
      dropped: 3,
      cpuSeconds: 1,
      heapBytes: 2000,
      stackCaptureSeconds: 0,
      clockOffsetMs: 3,
      dictionaryOverflow: null,
    });
  });

  it('T-UI-007 is null for every item without the series, 0 stays a value', () => {
    expect(agentSummary(null)).toEqual({
      dropped: null,
      cpuSeconds: null,
      heapBytes: null,
      stackCaptureSeconds: null,
      clockOffsetMs: null,
      dictionaryOverflow: null,
    });
    expect(sumValues([null, null])).toBeNull();
    expect(sumValues([0, null])).toBe(0);
  });
});
