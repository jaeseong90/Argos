// SPDX-License-Identifier: Apache-2.0
//
// Privacy, retention and audit calls (docs/14 §4.14, docs/16 §6.4, §9.6, WO-39): the deletion
// request (exactly one subject, range at most 30 days, checked before sending; the subject never
// in a query key), the job, the server's IP mode default, the retention change (only changed
// fields, `confirm` for a shorter value) and the audit filters → request parameters and CSV export.
// Requirements (test/README.md §5): { type: 'req', description: 'SEC-33' }, { type: 'req', description: 'SEC-80' }
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import {
  envelope,
  errorBody,
  installApiStub,
  uninstallApiStub,
  type ApiStub,
} from '../../../test/apiStub';
import { ApiError } from '../errors';

import { auditKeys, auditQuery, exportAudit, listAudit } from './audit';
import {
  deletionErrorKind,
  deletionFormProblem,
  DELETION_RANGE_MAX_MS,
  isIpLiteral,
  isJobActive,
  privacyKeys,
  requestDeletion,
  updatePrivacySettings,
} from './privacy';
import { retentionChanges, shortenedFields, updateRetention } from './retention';

export const REQ = { type: 'req', description: 'SEC-33' };

const DAY = 86_400_000;
const T0 = Date.UTC(2026, 10, 1);
const JOB = '0a1b2c3d-4e5f-4a6b-8c7d-000000000002';

let stub: ApiStub;

beforeEach(() => {
  stub = installApiStub();
});

afterEach(() => {
  uninstallApiStub();
  vi.restoreAllMocks();
});

describe('privacy: deletion request (docs/14 §4.14, SEC-33)', () => {
  it('checks the form before anything is sent: value, IP form, range order and 30 days', () => {
    const form = { kind: 'user_id' as const, value: 'kim', appId: null, from: T0, to: T0 + DAY };
    expect(deletionFormProblem(form)).toBeNull();
    expect(deletionFormProblem({ ...form, value: '  ' })).toBe('valueRequired');
    expect(deletionFormProblem({ ...form, value: 'x'.repeat(1025) })).toBe('valueTooLong');
    expect(deletionFormProblem({ ...form, kind: 'ip', value: '10.0.0' })).toBe('ipInvalid');
    expect(deletionFormProblem({ ...form, kind: 'ip', value: '10.0.0.1' })).toBeNull();
    expect(deletionFormProblem({ ...form, kind: 'ip', value: '2001:db8::1' })).toBeNull();
    expect(deletionFormProblem({ ...form, from: null })).toBe('rangeRequired');
    expect(deletionFormProblem({ ...form, to: T0 })).toBe('rangeOrder');
    expect(deletionFormProblem({ ...form, to: T0 + DELETION_RANGE_MAX_MS })).toBeNull();
    expect(deletionFormProblem({ ...form, to: T0 + DELETION_RANGE_MAX_MS + 1 })).toBe(
      'rangeTooLong',
    );
    expect(isIpLiteral('256.1.1.1')).toBe(false);
  });

  it('sends exactly one subject with the range and the application in the body, never in the URL; keys carry the job id only', async () => {
    stub.on('POST /api/v1/admin/privacy/deletions', () =>
      envelope(
        {
          job_id: JOB,
          status: 'queued',
          subject_kind: 'ip',
          subject_sha256: 'ab',
          from: '2026-11-01T00:00:00.000Z',
          to: '2026-11-02T00:00:00.000Z',
          app_id: 3,
          rollups_excluded: true,
          notices: [],
        },
        {},
        202,
      ),
    );
    const accepted = await requestDeletion({
      kind: 'ip',
      value: '10.0.0.1',
      from: T0,
      to: T0 + DAY,
      appId: 3,
    });
    expect(accepted.job_id).toBe(JOB);
    const calls = stub.calls('POST /api/v1/admin/privacy/deletions');
    expect(calls).toHaveLength(1);
    const call = calls[0];
    // SEC-33 (ADR-0010 C-241 (3)): the subject is in the JSON body only; the URL has no query.
    expect(call?.body).toEqual({
      from: '2026-11-01T00:00:00.000Z',
      to: '2026-11-02T00:00:00.000Z',
      ip: '10.0.0.1',
      app_id: 3,
    });
    expect([...(call?.query.keys() ?? [])]).toEqual([]);
    expect(call?.path).toBe('/api/v1/admin/privacy/deletions');
    expect(JSON.stringify(privacyKeys.job(JOB))).not.toContain('10.0.0.1');
  });

  it('maps 429, 400 range too long, 404 and 503 to screen kinds; follows queued and running jobs', () => {
    const http = (status: number, details: { field: string; reason: string }[] = []) =>
      new ApiError({ kind: 'http', status, code: 'x', message: 'x', details });
    expect(deletionErrorKind(http(429))).toBe('rateLimited');
    expect(deletionErrorKind(http(400, [{ field: 'to', reason: 'too_long' }]))).toBe(
      'rangeTooLong',
    );
    expect(deletionErrorKind(http(400, [{ field: 'to', reason: 'before_from' }]))).toBe(
      'rangeOrder',
    );
    expect(deletionErrorKind(http(400, [{ field: 'ip', reason: 'invalid' }]))).toBe('valueInvalid');
    expect(deletionErrorKind(http(404))).toBe('appNotFound');
    expect(deletionErrorKind(http(503))).toBe('queueFull');
    expect(isJobActive(undefined)).toBe(true);
    expect(isJobActive({ status: 'running' })).toBe(true);
    expect(isJobActive({ status: 'done' })).toBe(false);
    expect(isJobActive({ status: 'failed' })).toBe(false);
  });

  it('changes the default IP mode', async () => {
    stub.on('PUT /api/v1/admin/settings/privacy', () =>
      envelope({ default_ip_mode: 'off', default_ip_mode_source: 'settings' }),
    );
    await updatePrivacySettings('off');
    expect(stub.calls('PUT /api/v1/admin/settings/privacy')[0]?.body).toEqual({
      default_ip_mode: 'off',
    });
  });
});

describe('retention (docs/14 §4.14 /admin/settings/retention)', () => {
  const values = {
    xlog_days: 30,
    profile_days: 7,
    stat_raw_days: 14,
    metric_raw_days: 14,
    rollup_1m_days: 90,
    rollup_1h_days: 365,
  };

  it('sends only changed fields and confirm when one becomes shorter', async () => {
    const changes = retentionChanges(values, { ...values, xlog_days: 10, rollup_1h_days: 400 });
    expect(changes).toEqual({ xlog_days: 10, rollup_1h_days: 400 });
    expect(shortenedFields(values, changes)).toEqual(['xlog_days']);
    stub.on('PUT /api/v1/admin/settings/retention', () =>
      envelope({
        applied: true,
        apply_error: null,
        changes: [],
        queued_drops: 2,
        sources: {},
        values,
      }),
    );
    await updateRetention(changes, true);
    await updateRetention({ rollup_1h_days: 400 }, false);
    const calls = stub.calls('PUT /api/v1/admin/settings/retention');
    expect(calls[0]?.body).toEqual({ xlog_days: 10, rollup_1h_days: 400, confirm: true });
    expect(calls[1]?.body).toEqual({ rollup_1h_days: 400 });
  });
});

describe('audit (docs/14 §4.14, docs/16 §9.6)', () => {
  const filter = {
    from: T0,
    to: T0 + DAY,
    actor: ' admin ',
    action: 'KILL_SWITCH_ON',
    targetType: '',
    targetId: undefined,
  };

  it('sends the range, the non-empty filters, limit 500 and the cursor', async () => {
    stub.on('GET /api/v1/admin/audit', () => envelope([], { next_cursor: null }));
    await listAudit(filter, 'c-2');
    const query = stub.calls('GET /api/v1/admin/audit')[0]?.query;
    expect(query?.get('from')).toBe('2026-11-01T00:00:00.000Z');
    expect(query?.get('to')).toBe('2026-11-02T00:00:00.000Z');
    expect(query?.get('actor')).toBe('admin');
    expect(query?.get('action')).toBe('KILL_SWITCH_ON');
    expect(query?.has('target_type')).toBe(false);
    expect(query?.has('target_id')).toBe(false);
    expect(query?.get('limit')).toBe('500');
    expect(query?.get('cursor')).toBe('c-2');
    expect(auditKeys.list(filter)).toEqual(['admin', 'audit', 'list', auditQuery(filter)]);
  });

  it('exports the server CSV of the same filters; a 429 comes back with its retry time', async () => {
    stub.on(
      'GET /api/v1/admin/audit/export',
      () =>
        new Response('﻿"ts"\n', {
          status: 200,
          headers: {
            'Content-Type': 'text/csv; charset=utf-8',
            'Content-Disposition': 'attachment; filename="argos-audit.csv"',
          },
        }),
    );
    const click = vi.fn();
    const doc = {
      createElement: () => ({ click, remove: vi.fn() }),
      body: { append: vi.fn() },
    } as unknown as Document;
    vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:x');
    vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => undefined);
    const name = await exportAudit(filter, { document: doc, setTimeout: () => 0 });
    expect(name).toBe('argos-audit.csv');
    expect(click).toHaveBeenCalledTimes(1);
    const query = stub.calls('GET /api/v1/admin/audit/export')[0]?.query;
    expect(query?.get('format')).toBe('csv');
    expect(query?.get('actor')).toBe('admin');
    expect(query?.get('action')).toBe('KILL_SWITCH_ON');

    stub.on('GET /api/v1/admin/audit/export', () =>
      errorBody(429, 'rate_limited', { headers: { 'Retry-After': '30' } }),
    );
    const error: unknown = await exportAudit(filter, { document: doc }).catch((e: unknown) => e);
    expect(error).toBeInstanceOf(ApiError);
    expect((error as ApiError).retryAfterSeconds).toBe(30);
  });
});
