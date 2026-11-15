// SPDX-License-Identifier: Apache-2.0
//
// Small modules of the app root: the CSP nonce from <meta name="csp-nonce"> (docs/16 §9.9, WO-24),
// the CSP violation log (SEC-46), the locale-bound formatters with relative times (docs/15 §12)
// and the session handlers that expire the auth store on a failed refresh (docs/15 §11).
// Requirements (test/README.md §5): { type: 'req', description: 'SEC-46' }, { type: 'req', description: 'F-U13' }, { type: 'req', description: 'SEC-41' }
import { afterEach, describe, expect, it, vi } from 'vitest';

import { envelope, errorBody, installApiStub, uninstallApiStub } from '../../test/apiStub';
import { installSessionHandlers } from '../features/auth/session';
import { createFormatter, formatRelativeTime } from '../i18n/format';
import { useAuthStore } from '../state/authStore';

import { blockedOrigin, cspViolationCount, watchCspViolations } from './cspViolations';
import { readCspNonce } from './nonce';

afterEach(() => {
  document.head.querySelectorAll('meta[name="csp-nonce"]').forEach((m) => {
    m.remove();
  });
  uninstallApiStub();
});

function withNonceMeta(content: string): Document {
  const meta = document.createElement('meta');
  meta.setAttribute('name', 'csp-nonce');
  meta.setAttribute('content', content);
  document.head.append(meta);
  return document;
}

describe('T-SEC-46 CSP nonce and violations (DSN-15-09)', () => {
  it('T-SEC-46 reads the per-request nonce the server wrote into index.html', () => {
    expect(readCspNonce(withNonceMeta('rAnd0mN0nce+/16B=='))).toBe('rAnd0mN0nce+/16B==');
  });

  it.each(['', 'short', '"><script>', 'a b c d e f g h i j'])(
    'T-SEC-46 treats an empty or malformed nonce as none: %j',
    (content) => {
      expect(readCspNonce(withNonceMeta(content))).toBeUndefined();
    },
  );

  it('T-SEC-46 counts violations and logs the directive and the blocked origin only', () => {
    const target = new EventTarget();
    const report = vi.fn();
    const stop = watchCspViolations(target, report);
    const before = cspViolationCount();
    const event = Object.assign(new Event('securitypolicyviolation'), {
      effectiveDirective: 'style-src-elem',
      blockedURI: 'https://cdn.example/x.css?user=alice',
      disposition: 'enforce',
    });
    target.dispatchEvent(event);
    expect(report).toHaveBeenCalledWith({
      directive: 'style-src-elem',
      blocked: 'https://cdn.example',
      disposition: 'enforce',
    });
    expect(cspViolationCount()).toBe(before + 1);
    stop();
    target.dispatchEvent(event);
    expect(report).toHaveBeenCalledTimes(1);
    expect(blockedOrigin('inline')).toBe('inline');
    expect(blockedOrigin('')).toBe('');
  });
});

describe('T-UI-006 formatters (docs/15 §12)', () => {
  it('T-UI-006 formats relative times in the UI language', () => {
    expect(formatRelativeTime(-30_000, 'en')).toBe('30 sec. ago');
    expect(formatRelativeTime(-3 * 60_000, 'en')).toBe('3 min. ago');
    expect(formatRelativeTime(-2 * 3_600_000, 'ko')).toBe('2시간 전');
    expect(formatRelativeTime(2 * 86_400_000, 'en')).toBe('in 2 days');
  });

  it('T-UI-006 binds every helper to the locale and shows the UTC offset in absolute times', () => {
    const en = createFormatter('en');
    const ko = createFormatter('ko');
    expect(en.number(1234567)).toBe('1,234,567');
    expect(en.dateTimeWithOffset(Date.UTC(2026, 10, 14, 1, 2, 3))).toMatch(
      /\(UTC([+−]\d\d:\d\d)?\)$/,
    );
    expect(en.relative(1_000, 61_000)).toBe('1 min. ago');
    expect(ko.locale).toBe('ko');
    expect(typeof ko.duration(1234)).toBe('string');
    expect(typeof ko.bytes(2048)).toBe('string');
  });
});

describe('T-SEC-41 session handlers (docs/15 §11)', () => {
  it('T-SEC-41 a refresh that fails expires the session; a successful one updates the expiry', async () => {
    const stub = installApiStub();
    useAuthStore.getState().setSession({
      user: { id: 'u', username: 'alice' },
      roles: ['viewer'],
      must_change_password: false,
      session_expires_at: '2026-11-14T01:00:00.000Z',
    });
    const undo = installSessionHandlers();
    let refreshOk = true;
    let meCalls = 0;
    stub.on('GET /api/v1/auth/me', () => {
      meCalls += 1;
      return meCalls % 2 === 1
        ? errorBody(401, 'session_expired')
        : envelope({ user: { id: 'u', username: 'alice' } });
    });
    stub.on('POST /api/v1/auth/refresh', () =>
      refreshOk
        ? envelope({ session_expires_at: '2026-11-14T02:00:00.000Z' })
        : errorBody(401, 'session_revoked'),
    );
    await stub.runtime.client.GET('/api/v1/auth/me');
    expect(useAuthStore.getState().sessionExpiresAt).toBe(Date.parse('2026-11-14T02:00:00.000Z'));
    expect(useAuthStore.getState().status).toBe('authenticated');
    refreshOk = false;
    localStorage.clear();
    await stub.runtime.client.GET('/api/v1/auth/me');
    expect(useAuthStore.getState().status).toBe('expired');
    undo();
  });
});
