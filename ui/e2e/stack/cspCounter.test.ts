// SPDX-License-Identifier: Apache-2.0
//
// The run-level server CSP counter check (e2e/stack/cspCounter.ts, ADR-0010 C-188): one-sided. A
// growth beyond the reports the positive controls may cause fails the run; no growth, or less than
// the controls' reports (Chromium's Reporting API may deliver late or never), makes no claim.
import { mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

import { afterEach, describe, expect, it } from 'vitest';

import {
  addCspControlReports,
  clearCspControl,
  judgeCspCounter,
  readCspControl,
  recordCspControl,
} from './cspCounter.ts';

const dirs: string[] = [];

function controlFile(): string {
  const dir = mkdtempSync(join(tmpdir(), 'uie2e-csp-'));
  dirs.push(dir);
  return join(dir, 'run', 'csp-control.json');
}

afterEach(() => {
  for (const dir of dirs.splice(0)) {
    rmSync(dir, { recursive: true, force: true });
  }
});

describe('judgeCspCounter', () => {
  it('fails on any growth when no control ran', () => {
    expect(judgeCspCounter(1, null, null)).toEqual({ kind: 'violations', increase: 1 });
    expect(judgeCspCounter(3, null, null)).toEqual({ kind: 'violations', increase: 3 });
  });

  it("subtracts the controls' possible reports and fails on what remains", () => {
    const control = { run_id: 'r', expected_increase: 3 };
    expect(judgeCspCounter(4, null, control)).toEqual({ kind: 'violations', increase: 1 });
    expect(judgeCspCounter(3, null, control)).toEqual({ kind: 'no-growth' });
  });

  it("makes no claim when the controls' reports arrived late or never", () => {
    const control = { run_id: 'r', expected_increase: 3 };
    expect(judgeCspCounter(0, null, control)).toEqual({ kind: 'no-growth' });
    expect(judgeCspCounter(2, null, control)).toEqual({ kind: 'no-growth' });
    expect(judgeCspCounter(0, null, null)).toEqual({ kind: 'no-growth' });
  });

  it('names the problem of an unreadable counter', () => {
    expect(judgeCspCounter(null, 'the management URL of the stack is unknown', null)).toEqual({
      kind: 'unread',
      reason: 'argos_csp_violations_total not read: the management URL of the stack is unknown',
    });
  });
});

describe('the control record', () => {
  it('round-trips and is ignored for another run id', () => {
    const file = controlFile();
    expect(readCspControl('run-a', file)).toBeNull();
    recordCspControl({ run_id: 'run-a', expected_increase: 1 }, file);
    expect(readCspControl('run-a', file)).toEqual({ run_id: 'run-a', expected_increase: 1 });
    expect(readCspControl('run-b', file)).toBeNull();
    clearCspControl('run-a', file);
    expect(readCspControl('run-a', file)).toBeNull();
  });

  it('adds the reports of each control of the run', () => {
    const file = controlFile();
    expect(addCspControlReports('run-a', 1, file)).toEqual({
      run_id: 'run-a',
      expected_increase: 1,
    });
    addCspControlReports('run-a', 2, file);
    expect(readCspControl('run-a', file)).toEqual({ run_id: 'run-a', expected_increase: 3 });
    // A record of another run on the same path starts again from zero.
    recordCspControl({ run_id: 'run-old', expected_increase: 5 }, file);
    expect(addCspControlReports('run-a', 1, file)).toEqual({
      run_id: 'run-a',
      expected_increase: 1,
    });
  });

  it('refuses a malformed record', () => {
    const file = controlFile();
    recordCspControl({ run_id: 'run-a', expected_increase: 0 }, file);
    writeFileSync(file, '{"run_id":"run-a","expected_increase":-1}');
    expect(() => readCspControl('run-a', file)).toThrow();
  });
});
