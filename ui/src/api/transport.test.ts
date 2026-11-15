// SPDX-License-Identifier: Apache-2.0
//
// HTTP transport (docs/15 §11, DSN-15-08, SEC-40, SEC-43): CSRF double submit, CSRF retry, one
// shared session refresh, session expiry. The network is stubbed at the fetch boundary only.
// Requirements (test/README.md §5): { type: 'req', description: 'SEC-43' }, { type: 'req', description: 'SEC-40' }, { type: 'req', description: 'SEC-41' }
import { afterEach, describe, expect, it, vi } from 'vitest';

import {
  envelope,
  errorBody,
  installApiStub,
  noContent,
  uninstallApiStub,
} from '../../test/apiStub';

import { expectNoContent, unwrap } from './client';
import { readCookie, Transport, type LockManagerLike } from './transport';

afterEach(() => {
  uninstallApiStub();
  localStorage.clear();
  vi.restoreAllMocks();
});

interface FakeLocks extends LockManagerLike {
  /** Every request in order, with whether it asked not to wait. */
  readonly requests: { name: string; ifAvailable: boolean }[];
  /** Requests waiting for the lock now. */
  waiting(): number;
}

/**
 * One exclusive lock shared by every "tab" of a test, like navigator.locks within an origin:
 * FIFO, and `ifAvailable` answers null instead of waiting while it is held. It also takes the
 * two-argument form `request(name, callback)`, so the race tests below run against the earlier
 * transport too (they must fail there).
 */
function fakeLocks(): FakeLocks {
  let held = false;
  const queue: (() => void)[] = [];
  const requests: { name: string; ifAvailable: boolean }[] = [];
  const release = (): void => {
    const next = queue.shift();
    if (next === undefined) {
      held = false;
    } else {
      next();
    }
  };
  const request = (
    name: string,
    second: { readonly ifAvailable?: boolean } | ((lock: unknown) => Promise<unknown>),
    third?: (lock: unknown) => Promise<unknown>,
  ): Promise<unknown> => {
    const options = typeof second === 'function' ? {} : second;
    const callback = typeof second === 'function' ? second : third;
    if (callback === undefined) {
      throw new Error('fakeLocks: no callback');
    }
    requests.push({ name, ifAvailable: options.ifAvailable === true });
    if (held && options.ifAvailable === true) {
      return callback(null);
    }
    const granted = held
      ? new Promise<void>((resolve) => {
          queue.push(resolve);
        })
      : Promise.resolve();
    held = true;
    return granted.then(() => callback({ name })).finally(release);
  };
  return {
    requests,
    waiting: () => queue.length,
    request: request as LockManagerLike['request'],
  };
}

describe('T-SEC-43 transport: CSRF double submit (docs/14 §2.2)', () => {
  it('T-SEC-43 sends X-Argos-CSRF from the argos_csrf cookie on POST, PUT, PATCH and DELETE only', async () => {
    const stub = installApiStub();
    stub.csrfCookie = 'cookie-value';
    stub.on('POST /api/v1/auth/logout', () => noContent());
    stub.on('PUT /api/v1/auth/password', () => noContent());
    stub.on('GET /api/v1/auth/me', () => envelope({}));
    const { client } = stub.runtime;
    await expectNoContent(client.POST('/api/v1/auth/logout'));
    await expectNoContent(
      client.PUT('/api/v1/auth/password', { body: { current: 'a', new: 'b' } }),
    );
    await unwrap(client.GET('/api/v1/auth/me'));
    expect(stub.calls('POST /api/v1/auth/logout')[0]?.headers.get('X-Argos-CSRF')).toBe(
      'cookie-value',
    );
    expect(stub.calls('PUT /api/v1/auth/password')[0]?.headers.get('X-Argos-CSRF')).toBe(
      'cookie-value',
    );
    expect(stub.calls('GET /api/v1/auth/me')[0]?.headers.get('X-Argos-CSRF')).toBeNull();
  });

  it('T-SEC-43 fetches the CSRF cookie first when it is missing', async () => {
    const stub = installApiStub({ csrfToken: 'fresh' });
    stub.on('POST /api/v1/auth/logout', () => noContent());
    await expectNoContent(stub.runtime.client.POST('/api/v1/auth/logout'));
    expect(stub.requests.map((r) => `${r.method} ${r.path}`)).toEqual([
      'GET /api/v1/auth/csrf',
      'POST /api/v1/auth/logout',
    ]);
    expect(stub.calls('POST /api/v1/auth/logout')[0]?.headers.get('X-Argos-CSRF')).toBe('fresh');
  });

  it.each(['csrf_invalid', 'csrf_failed'])(
    'T-SEC-43 on 403 %s fetches a new CSRF token and retries once',
    async (code) => {
      const stub = installApiStub({ csrfToken: 'renewed' });
      stub.csrfCookie = 'stale';
      let attempts = 0;
      stub.on('POST /api/v1/auth/logout', (request) => {
        attempts += 1;
        return request.headers.get('X-Argos-CSRF') === 'renewed'
          ? noContent()
          : errorBody(403, code);
      });
      await expectNoContent(stub.runtime.client.POST('/api/v1/auth/logout'));
      expect(attempts).toBe(2);
      expect(stub.calls('GET /api/v1/auth/csrf')).toHaveLength(1);
    },
  );

  it('T-SEC-43 does not retry a second CSRF failure or a 403 forbidden', async () => {
    const stub = installApiStub();
    stub.csrfCookie = 'x';
    stub.on('POST /api/v1/auth/logout', () => errorBody(403, 'csrf_invalid'));
    stub.on('PUT /api/v1/auth/password', () => errorBody(403, 'forbidden'));
    await expect(
      expectNoContent(stub.runtime.client.POST('/api/v1/auth/logout')),
    ).rejects.toMatchObject({ status: 403, code: 'csrf_invalid' });
    expect(stub.calls('POST /api/v1/auth/logout')).toHaveLength(2);
    await expect(
      expectNoContent(
        stub.runtime.client.PUT('/api/v1/auth/password', { body: { current: 'a', new: 'b' } }),
      ),
    ).rejects.toMatchObject({ code: 'forbidden' });
    expect(stub.calls('PUT /api/v1/auth/password')).toHaveLength(1);
  });

  it('T-SEC-40 never sends an Authorization header and reads cookies without writing them', async () => {
    const stub = installApiStub();
    stub.on('GET /api/v1/auth/me', () => envelope({}));
    await unwrap(stub.runtime.client.GET('/api/v1/auth/me'));
    expect(stub.requests.every((r) => r.headers.get('Authorization') === null)).toBe(true);
    expect(readCookie('a=1; argos_csrf=tok%20en; b=2', 'argos_csrf')).toBe('tok en');
    expect(readCookie('argos_csrfx=1', 'argos_csrf')).toBeNull();
  });
});

describe('T-SEC-41 transport: 401 -> one shared refresh -> retry once (docs/15 §11)', () => {
  it('T-SEC-41 refreshes once for concurrent 401s and retries each request once', async () => {
    const stub = installApiStub();
    stub.csrfCookie = 'c';
    let refreshed = false;
    stub.on('POST /api/v1/auth/refresh', async () => {
      await new Promise((resolve) => setTimeout(resolve, 5));
      refreshed = true;
      return envelope({ session_expires_at: '2099-01-01T00:00:00.000Z' });
    });
    stub.on('GET /api/v1/auth/me', () =>
      refreshed ? envelope({ ok: 1 }) : errorBody(401, 'session_expired'),
    );
    stub.on('GET /api/v1/apps', () =>
      refreshed ? envelope([]) : errorBody(401, 'session_expired'),
    );
    const onRefreshed = vi.fn();
    stub.runtime.transport.setListeners({ onSessionRefreshed: onRefreshed });
    const { client } = stub.runtime;
    const results = await Promise.all([
      unwrap(client.GET('/api/v1/auth/me')),
      unwrap(client.GET('/api/v1/apps')),
      unwrap(client.GET('/api/v1/auth/me')),
    ]);
    expect(results.map((r) => r.data)).toEqual([{ ok: 1 }, [], { ok: 1 }]);
    expect(stub.calls('POST /api/v1/auth/refresh')).toHaveLength(1);
    expect(stub.calls('GET /api/v1/auth/me')).toHaveLength(4);
    expect(stub.calls('POST /api/v1/auth/refresh')[0]?.headers.get('X-Argos-CSRF')).toBe('c');
    expect(onRefreshed).toHaveBeenCalledWith('2099-01-01T00:00:00.000Z');
  });

  it('T-SEC-41 a failed refresh reports the expired session once per request and returns the 401', async () => {
    const stub = installApiStub();
    stub.csrfCookie = 'c';
    stub.on('POST /api/v1/auth/refresh', () => errorBody(401, 'session_revoked'));
    stub.on('GET /api/v1/apps', () => errorBody(401, 'session_expired'));
    const onExpired = vi.fn();
    stub.runtime.transport.setListeners({ onSessionExpired: onExpired });
    await expect(unwrap(stub.runtime.client.GET('/api/v1/apps'))).rejects.toMatchObject({
      status: 401,
    });
    expect(onExpired).toHaveBeenCalledTimes(1);
    expect(stub.calls('GET /api/v1/apps')).toHaveLength(1);
  });

  it('T-SEC-41 a 401 after a successful refresh is not retried again', async () => {
    const stub = installApiStub();
    stub.csrfCookie = 'c';
    stub.on('POST /api/v1/auth/refresh', () =>
      envelope({ session_expires_at: '2099-01-01T00:00:00Z' }),
    );
    stub.on('GET /api/v1/apps', () => errorBody(401, 'session_revoked'));
    const onExpired = vi.fn();
    stub.runtime.transport.setListeners({ onSessionExpired: onExpired });
    await expect(unwrap(stub.runtime.client.GET('/api/v1/apps'))).rejects.toMatchObject({
      status: 401,
    });
    expect(stub.calls('GET /api/v1/apps')).toHaveLength(2);
    expect(stub.calls('POST /api/v1/auth/refresh')).toHaveLength(1);
    expect(onExpired).toHaveBeenCalledTimes(1);
  });

  it.each([
    ['POST /api/v1/auth/login', 'POST'],
    ['GET /api/v1/setup/status', 'GET'],
    ['POST /api/v1/auth/logout', 'POST'],
  ])('T-SEC-41 a 401 of %s is an answer, not an expired session (no refresh)', async (route) => {
    const stub = installApiStub();
    stub.csrfCookie = 'c';
    stub.on(route, () => errorBody(401, 'unauthenticated'));
    const { client } = stub.runtime;
    const call =
      route === 'POST /api/v1/auth/login'
        ? unwrap(client.POST('/api/v1/auth/login', { body: { username: 'a', password: 'b' } }))
        : route === 'GET /api/v1/setup/status'
          ? unwrap(client.GET('/api/v1/setup/status'))
          : expectNoContent(client.POST('/api/v1/auth/logout'));
    await expect(call).rejects.toMatchObject({ status: 401 });
    expect(stub.calls('POST /api/v1/auth/refresh')).toHaveLength(0);
  });

  it('T-SEC-41 serialises refreshes across tabs with a Web Lock; a newer marker leads to a check, never to a skipped refresh on its own', async () => {
    const locks = fakeLocks();
    const stub = installApiStub({ locks });
    stub.csrfCookie = 'c';
    let refreshed = false;
    stub.on('POST /api/v1/auth/refresh', () => {
      refreshed = true;
      return envelope({ session_expires_at: '2099-01-01T00:00:00Z' });
    });
    stub.on('GET /api/v1/apps', () =>
      refreshed ? envelope([]) : errorBody(401, 'session_expired'),
    );
    await unwrap(stub.runtime.client.GET('/api/v1/apps'));
    // The lock was free: taken without waiting, no check, one refresh.
    expect(locks.requests).toEqual([{ name: 'argos.session-refresh', ifAvailable: true }]);
    expect(stub.calls('POST /api/v1/auth/refresh')).toHaveLength(1);
    expect(stub.calls('GET /api/v1/apps')).toHaveLength(2);

    // The marker says another tab refreshed after this tab's request left: the check decides.
    const failedAt = Date.now() - 1_000;
    localStorage.setItem('argos.ui.refreshAt', String(Date.now()));
    const passing = vi.fn(() => Promise.resolve(true));
    await expect(stub.runtime.transport.refreshSession(failedAt, passing)).resolves.toBe(true);
    expect(passing).toHaveBeenCalledTimes(1);
    expect(stub.calls('POST /api/v1/auth/refresh')).toHaveLength(1);
    // The check still answers 401 (the marker was wrong or stale): this tab refreshes.
    const failing = vi.fn(() => Promise.resolve(false));
    await expect(stub.runtime.transport.refreshSession(failedAt, failing)).resolves.toBe(true);
    expect(failing).toHaveBeenCalledTimes(1);
    expect(stub.calls('POST /api/v1/auth/refresh')).toHaveLength(2);
    // A check that cannot reach the server proves nothing either: the refresh decides.
    const offline = vi.fn(() => Promise.reject(new Error('offline')));
    await expect(stub.runtime.transport.refreshSession(failedAt, offline)).resolves.toBe(true);
    expect(offline).toHaveBeenCalledTimes(1);
    expect(stub.calls('POST /api/v1/auth/refresh')).toHaveLength(3);
  });

  it('T-SEC-41 a 401 that arrives after another tab refreshed is only retried (send time decides)', async () => {
    vi.useFakeTimers({ now: 1_000_000, toFake: ['Date'] });
    try {
      const stub = installApiStub();
      stub.csrfCookie = 'c';
      let rotated = false;
      stub.on('POST /api/v1/auth/refresh', () => envelope({}));
      stub.on('GET /api/v1/apps', () => {
        if (rotated) {
          return envelope([]);
        }
        // The request left without an access cookie; while its 401 travels back, another tab
        // finishes its refresh (marker after the send time, before this tab sees the 401).
        vi.setSystemTime(1_000_050);
        localStorage.setItem('argos.ui.refreshAt', String(Date.now()));
        rotated = true;
        vi.setSystemTime(1_000_100);
        return errorBody(401, 'session_expired');
      });
      await expect(unwrap(stub.runtime.client.GET('/api/v1/apps'))).resolves.toMatchObject({
        data: [],
      });
      expect(stub.calls('POST /api/v1/auth/refresh')).toHaveLength(0);
      expect(stub.calls('GET /api/v1/apps')).toHaveLength(2);
    } finally {
      vi.useRealTimers();
    }
  });
});

describe('T-SEC-41a transport: across tabs the server decides whether a refresh already happened, not a marker another tab wrote', () => {
  const BASE = 'http://localhost:3000';

  /**
   * The server and the browser's cookie jar, shared by every tab of a test like the cookies of one
   * browser profile: the access cookie has expired; a refresh makes it valid again. `holdRefresh`
   * keeps a refresh open until the test lets it finish.
   */
  function sharedSession(holdRefresh: Promise<void> = Promise.resolve()) {
    const state = {
      accessValid: false,
      refreshes: [] as string[],
      apps: [] as { tab: string; status: number }[],
    };
    const tab = (name: string, locks: LockManagerLike | null): Transport =>
      new Transport({
        origin: BASE,
        locks,
        readCookies: () => 'argos_csrf=c',
        fetch: async (request) => {
          const path = new URL(request.url).pathname;
          if (path === '/api/v1/auth/refresh') {
            state.refreshes.push(name);
            await holdRefresh;
            state.accessValid = true;
            return envelope({ session_expires_at: '2099-01-01T00:00:00Z' });
          }
          if (path === '/api/v1/apps') {
            const status = state.accessValid ? 200 : 401;
            state.apps.push({ tab: name, status });
            return status === 200 ? envelope([]) : errorBody(401, 'session_expired');
          }
          return errorBody(404, 'not_found');
        },
      });
    return { state, tab };
  }

  const getApps = (tab: Transport): Promise<Response> =>
    tab.fetch(new Request(`${BASE}/api/v1/apps`));

  it('T-SEC-41a the tab that waited for the lock retries with the rotated cookies and does not refresh again, although its marker is stale', async () => {
    // The refreshing tab's "refreshed at" write has not reached the waiting tab yet (Chromium
    // hands localStorage writes to other tabs asynchronously): here it never arrives.
    const setItem = Storage.prototype.setItem.bind(localStorage);
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation((key: string, value: string) => {
      if (key !== 'argos.ui.refreshAt') {
        setItem(key, value);
      }
    });
    let finishRefresh: () => void = () => undefined;
    const hold = new Promise<void>((resolve) => {
      finishRefresh = resolve;
    });
    const locks = fakeLocks();
    const { state, tab } = sharedSession(hold);
    const tabB = tab('b', locks);
    const tabA = tab('a', locks);

    const fromB = getApps(tabB);
    await vi.waitFor(() => {
      expect(state.refreshes).toEqual(['b']);
    });
    const fromA = getApps(tabA);
    // Tab A got its 401 and waits for the lock that tab B holds while it refreshes.
    await vi.waitFor(() => {
      expect(locks.waiting()).toBe(1);
    });
    finishRefresh();
    const [responseA, responseB] = await Promise.all([fromA, fromB]);

    expect([responseA.status, responseB.status]).toEqual([200, 200]);
    expect(state.refreshes, 'POST /auth/refresh over both tabs').toEqual(['b']);
    expect(state.apps.filter((c) => c.tab === 'a').map((c) => c.status)).toEqual([401, 200]);
    expect(localStorage.getItem('argos.ui.refreshAt')).toBeNull();
  });

  it('T-SEC-41a with the lock busy but no refresh by the other tab, the check still answers 401 and this tab refreshes exactly once', async () => {
    const locks = fakeLocks();
    const { state, tab } = sharedSession();
    let releaseOther: () => void = () => undefined;
    // Another tab holds the lock and lets it go without refreshing (its refresh was not needed).
    const otherTab = locks.request(
      'argos.session-refresh',
      {},
      () =>
        new Promise<void>((resolve) => {
          releaseOther = resolve;
        }),
    );
    const tabA = tab('a', locks);
    const fromA = getApps(tabA);
    await vi.waitFor(() => {
      expect(locks.waiting()).toBe(1);
    });
    releaseOther();
    await otherTab;

    expect((await fromA).status).toBe(200);
    expect(state.refreshes).toEqual(['a']);
    expect(state.apps.map((c) => c.status)).toEqual([401, 401, 200]);
  });

  it('T-SEC-41a a single tab that finds the lock free refreshes at once, without an extra request', async () => {
    const locks = fakeLocks();
    const { state, tab } = sharedSession();
    expect((await getApps(tab('a', locks))).status).toBe(200);
    expect(state.refreshes).toEqual(['a']);
    expect(state.apps.map((c) => c.status)).toEqual([401, 200]);
    expect(locks.requests).toEqual([{ name: 'argos.session-refresh', ifAvailable: true }]);
  });

  it('T-SEC-41a without Web Locks a newer marker alone does not skip the refresh: the check answers 401 and this tab refreshes once', async () => {
    const { state, tab } = sharedSession();
    const tabA = tab('a', null);
    localStorage.setItem('argos.ui.refreshAt', String(Date.now() + 60_000));
    expect((await getApps(tabA)).status).toBe(200);
    expect(state.refreshes).toEqual(['a']);
    expect(state.apps.map((c) => c.status)).toEqual([401, 401, 200]);
  });
});
