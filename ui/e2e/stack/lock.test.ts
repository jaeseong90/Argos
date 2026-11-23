// SPDX-License-Identifier: Apache-2.0
//
// The working-tree lock of e2e/stack/lock.ts (ADR-0010 C-235 (8)): the owner file, the stale rule
// (only a dead PID of this host and PID kind), never deleting another run's lock, and release only
// by the owner's token. The probe (host, PID kind, liveness) is injected; one case uses the real
// process.kill check with a child process that has exited.
import { spawnSync } from 'node:child_process';
import { existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

import { afterEach, describe, expect, it } from 'vitest';

import {
  acquireLock,
  hostPidKind,
  inheritedTokenVariable,
  inspectLock,
  judgeStale,
  LockOwner,
  OWNER_FILE,
  pidAlive,
  releaseLock,
  tryAcquireLock,
  type LockProbe,
} from './lock.ts';

const dirs: string[] = [];

function root(): string {
  const dir = mkdtempSync(join(tmpdir(), 'uie2e-lock-'));
  dirs.push(dir);
  return join(dir, 'argos-locks');
}

afterEach(() => {
  for (const dir of dirs.splice(0)) {
    rmSync(dir, { recursive: true, force: true });
  }
});

function probe(alive: Record<number, boolean | null>, host = 'host-a'): LockProbe {
  return {
    host,
    pidKind: 'windows',
    alive: (pid) => (pid in alive ? (alive[pid] ?? null) : false),
  };
}

function owner(overrides: Partial<LockOwner> = {}): LockOwner {
  return {
    version: 1,
    name: 'server-it',
    token: 'other-token',
    pid: 4242,
    pid_kind: 'windows',
    host: 'host-a',
    started_at: '2026-11-24T01:02:03.000Z',
    purpose: 'someone else',
    ...overrides,
  };
}

/** A lock made by another run (as argos-lock.sh writes it), or without owner file when null. */
function plant(lockRoot: string, file: LockOwner | string | null): string {
  const dir = join(lockRoot, 'server-it');
  mkdirSync(dir, { recursive: true });
  if (file !== null) {
    writeFileSync(
      join(dir, OWNER_FILE),
      typeof file === 'string' ? file : `${JSON.stringify(file, null, 2)}\n`,
    );
  }
  return dir;
}

const env = {};

describe('tryAcquireLock', () => {
  it('creates the directory and writes the owner file (PID, host, start time, purpose)', () => {
    const r = root();
    const result = tryAcquireLock('server-it', {
      root: r,
      env,
      probe: probe({}),
      purpose: 'ui e2e stack 20261124-010203-abcd',
      pid: 1234,
      token: 'tok-1',
      now: () => new Date('2026-11-24T01:02:03.456Z'),
    });
    expect(result.acquired).toBe(true);
    const text = readFileSync(join(r, 'server-it', OWNER_FILE), 'utf8');
    // One key per line: test/lock/argos-lock.sh reads it with sed.
    expect(text).toBe(
      [
        '{',
        '  "version": 1,',
        '  "name": "server-it",',
        '  "token": "tok-1",',
        '  "pid": 1234,',
        '  "pid_kind": "windows",',
        '  "host": "host-a",',
        '  "started_at": "2026-11-24T01:02:03.456Z",',
        '  "purpose": "ui e2e stack 20261124-010203-abcd"',
        '}',
        '',
      ].join('\n'),
    );
    expect(LockOwner.parse(JSON.parse(text)).pid).toBe(1234);
  });

  it('does not take or touch a lock whose owner is alive', () => {
    const r = root();
    const dir = plant(r, owner());
    const result = tryAcquireLock('server-it', {
      root: r,
      env,
      probe: probe({ 4242: true }),
      purpose: 'mine',
      token: 'mine',
    });
    expect(result.acquired).toBe(false);
    expect(readFileSync(join(dir, OWNER_FILE), 'utf8')).toContain('"other-token"');
  });

  it('reaps a lock whose owner PID on this host is not alive, then takes it', () => {
    const r = root();
    plant(r, owner());
    const result = tryAcquireLock('server-it', {
      root: r,
      env,
      probe: probe({ 4242: false }),
      purpose: 'mine',
      token: 'mine',
    });
    expect(result.acquired).toBe(true);
    expect(readFileSync(join(r, 'server-it', OWNER_FILE), 'utf8')).toContain('"mine"');
  });

  it.each([
    ['without an owner file', null],
    ['with an unreadable owner file', '{"pid": '],
    ['of another host', owner({ host: 'host-b' })],
    ['of another PID kind', owner({ pid_kind: 'posix' })],
  ])('never treats a lock %s as stale', (_what, file) => {
    const r = root();
    const dir = plant(r, file);
    const result = tryAcquireLock('server-it', {
      root: r,
      env,
      // Every PID is dead to this probe: only the rule keeps the lock.
      probe: probe({}),
      purpose: 'mine',
      token: 'mine',
    });
    expect(result.acquired).toBe(false);
    expect(existsSync(dir)).toBe(true);
    if (!result.acquired) {
      expect(result.inspection.verdict.stale).toBe(false);
    }
  });

  it('treats an unknown liveness as alive', () => {
    expect(judgeStale(owner(), probe({ 4242: null })).stale).toBe(false);
    expect(judgeStale(owner({ host: 'HOST-A' }), probe({ 4242: false })).stale).toBe(true);
  });

  it('judges a real PID that has exited as stale and this process as alive', () => {
    const child = spawnSync(process.execPath, ['-e', '0']);
    expect(child.status).toBe(0);
    expect(pidAlive(child.pid)).toBe(false);
    expect(pidAlive(process.pid)).toBe(true);
    const real: LockProbe = { host: 'host-a', pidKind: hostPidKind(), alive: pidAlive };
    expect(judgeStale(owner({ pid: child.pid, pid_kind: hostPidKind() }), real).stale).toBe(true);
    expect(judgeStale(owner({ pid: process.pid, pid_kind: hostPidKind() }), real).stale).toBe(
      false,
    );
  });
});

describe('releaseLock', () => {
  it('deletes only a lock with the caller token', () => {
    const r = root();
    const dir = plant(r, owner());
    expect(releaseLock('server-it', 'mine', { root: r, env })).toBe('foreign');
    expect(existsSync(dir)).toBe(true);
    expect(releaseLock('server-it', 'other-token', { root: r, env })).toBe('released');
    expect(existsSync(dir)).toBe(false);
    expect(releaseLock('server-it', 'other-token', { root: r, env })).toBe('absent');
  });

  it('never deletes a lock without an owner file', () => {
    const r = root();
    const dir = plant(r, null);
    expect(releaseLock('server-it', 'mine', { root: r, env })).toBe('foreign');
    expect(existsSync(dir)).toBe(true);
  });
});

describe('acquireLock', () => {
  it('waits (bounded) and names the owner and the manual removal in the error', async () => {
    const r = root();
    const dir = plant(r, owner({ purpose: ':server:integrationTest' }));
    const lines: string[] = [];
    await expect(
      acquireLock('server-it', {
        root: r,
        env,
        probe: probe({ 4242: true }),
        purpose: 'mine',
        waitSeconds: 0.2,
        pollMs: 50,
        log: (line) => lines.push(line),
      }),
    ).rejects.toThrow(/PID 4242 \(windows\) on host-a .*":server:integrationTest".*rm -rf/);
    expect(lines.some((line) => line.startsWith('waiting up to'))).toBe(true);
    expect(existsSync(dir)).toBe(true);
  });

  it('takes the lock once its owner releases it', async () => {
    const r = root();
    plant(r, owner());
    setTimeout(() => releaseLock('server-it', 'other-token', { root: r, env }), 100);
    const handle = await acquireLock('server-it', {
      root: r,
      env,
      probe: probe({ 4242: true }),
      purpose: 'mine',
      waitSeconds: 5,
      pollMs: 20,
    });
    expect(handle.inherited).toBe(false);
    expect(inspectLock('server-it', { root: r, env }).owner?.token).toBe(handle.token);
    expect(handle.release()).toBe('released');
  });

  it('uses the lock of an enclosing argos-lock.sh run and leaves it on release', async () => {
    const r = root();
    const dir = plant(r, owner({ token: 'outer' }));
    const handle = await acquireLock('server-it', {
      root: r,
      env: { [inheritedTokenVariable('server-it')]: 'outer' },
      probe: probe({ 4242: true }),
      purpose: 'nested',
      waitSeconds: 0,
    });
    expect(inheritedTokenVariable('server-it')).toBe('ARGOS_LOCK_TOKEN_SERVER_IT');
    expect(handle.inherited).toBe(true);
    expect(handle.release()).toBe('inherited');
    expect(existsSync(dir)).toBe(true);
  });

  it('rejects a lock name that could leave the lock directory', () => {
    expect(() => inspectLock('../server-it', { root: root(), env })).toThrow(/invalid lock name/);
  });
});
