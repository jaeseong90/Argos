// SPDX-License-Identifier: Apache-2.0
//
// The fresh-install variant of the E2E stack (WO-46, e2e/stack/fresh.ts): the server environment
// without the unattended administrator, the setup token parsing, the reuse switch, the lock token,
// and the Playwright projects that are defined only when selected.
import { describe, expect, it } from 'vitest';

import { PROJECTS, selectedProjects } from '../fixtures/projects';

import { freshLockToken, freshReuseUrl, parseSetupToken, setupTokenFile } from './fresh';
import { SETUP_TOKEN_FILE } from './secrets';
import { serverEnvironment } from './stack';

describe('fresh stack server environment', () => {
  it('has no unattended administrator and no IP mode (the wizard chooses it)', () => {
    const fresh = serverEnvironment('20261126-000000-abcd', 41234, 'key', 'fresh');
    expect(fresh).not.toHaveProperty('ARGOS_ADMIN_USERNAME');
    expect(fresh).not.toHaveProperty('ARGOS_ADMIN_PASSWORD_FILE');
    expect(fresh).not.toHaveProperty('ARGOS_IP_MODE');
    expect(fresh.ARGOS_PG_URL).toBe(
      'jdbc:postgresql://argos-e2e-20261126-000000-abcd-pg:5432/argos',
    );
  });

  it('differs from the seeded stack only by the administrator and the IP mode', () => {
    const seeded = serverEnvironment('20261126-000000-abcd', 41234, 'key');
    const fresh = serverEnvironment('20261126-000000-abcd', 41234, 'key', 'fresh');
    expect(seeded.ARGOS_ADMIN_USERNAME).toBe('admin');
    expect(seeded.ARGOS_IP_MODE).toBe('masked');
    const { ARGOS_ADMIN_USERNAME, ARGOS_ADMIN_PASSWORD_FILE, ARGOS_IP_MODE, ...rest } = seeded;
    expect([ARGOS_ADMIN_USERNAME, ARGOS_ADMIN_PASSWORD_FILE, ARGOS_IP_MODE]).not.toContain(
      undefined,
    );
    expect(fresh).toEqual(rest);
  });
});

describe('setup token', () => {
  it('accepts the 43 base64url characters of the server file, trailing line end included', () => {
    const token = 'A'.repeat(20) + '_-' + 'z9'.repeat(10) + 'b';
    expect(token).toHaveLength(43);
    expect(parseSetupToken(`${token}\n`)).toBe(token);
  });

  it('refuses anything else (empty, short, other characters)', () => {
    expect(parseSetupToken('')).toBeNull();
    expect(parseSetupToken('A'.repeat(42))).toBeNull();
    expect(parseSetupToken(`${'A'.repeat(42)}=`)).toBeNull();
    expect(parseSetupToken('A'.repeat(44))).toBeNull();
  });

  it('is copied to the run secrets directory', () => {
    expect(setupTokenFile('run-1').replaceAll('\\', '/')).toMatch(
      new RegExp(`/e2e/\\.stack/run-1/secrets/${SETUP_TOKEN_FILE.replace('.', '\\.')}$`),
    );
  });
});

describe('reuse and lock', () => {
  it('reuses ARGOS_E2E_FRESH_URL without trailing slashes, nothing when empty', () => {
    expect(freshReuseUrl({ ARGOS_E2E_FRESH_URL: ' http://127.0.0.1:5000// ' })).toBe(
      'http://127.0.0.1:5000',
    );
    expect(freshReuseUrl({ ARGOS_E2E_FRESH_URL: '' })).toBeNull();
    expect(freshReuseUrl({})).toBeNull();
  });

  it('holds the server-it lock with a token of its own run', () => {
    expect(freshLockToken('20261126-000000-abcd')).toBe('e2e-fresh-20261126-000000-abcd');
  });
});

describe('fresh-install projects', () => {
  it('are known project names and selected by --project fresh-install', () => {
    for (const name of ['fresh-setup', 'fresh-install', 'fresh-teardown']) {
      expect(PROJECTS).toContain(name);
    }
    expect(selectedProjects(['test', '--project', 'fresh-install'])).toEqual(['fresh-install']);
  });
});
