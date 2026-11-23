// SPDX-License-Identifier: Apache-2.0
//
// The layout spec opens every shell route (e2e/specs/routes.ts): the list must match the registry
// src/routes/screens.ts exactly (every screen, nothing else but /login and /setup), with the
// screens that have a component (`load` in their registry entry) as screens, the others as
// placeholders, and the minimum role of each screen's permission (src/state/permissions.ts). Both
// files are read as text, so this tooling test needs no React.
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

import { describe, expect, it } from 'vitest';

import { SHELL_ROUTES } from './routes';

const SRC = join(dirname(fileURLToPath(import.meta.url)), '..', '..', 'src');

interface RegistryScreen {
  readonly pattern: string;
  readonly permission: string;
  readonly owner: string;
  /** The entry has a `load` line: the route shows the screen, not the placeholder. */
  readonly loaded: boolean;
}

function registry(): RegistryScreen[] {
  const source = readFileSync(join(SRC, 'routes', 'screens.ts'), 'utf8');
  const entry =
    /\{\s*id: '[^']+',\s*path: '([^']+)',\s*scope: '(app|global)',[\s\S]*?permission: '([^']+)',\s*owner: '([^']+)',([^}]*)\}/g;
  return [...source.matchAll(entry)].map((m) => ({
    pattern: m[2] === 'app' ? `/apps/:appId/${m[1] ?? ''}` : `/${m[1] ?? ''}`,
    permission: m[3] ?? '',
    owner: m[4] ?? '',
    loaded: /\bload:/.test(m[5] ?? ''),
  }));
}

function minimumRoles(): Map<string, string> {
  const source = readFileSync(join(SRC, 'state', 'permissions.ts'), 'utf8');
  const block = /const ROLE_PERMISSIONS = \{([\s\S]*?)\} as const/.exec(source)?.[1] ?? '';
  const roles = new Map<string, string>();
  for (const m of block.matchAll(/'?([a-z][a-z.-]*)'?: '(viewer|operator|admin)'/g)) {
    roles.set(m[1] ?? '', m[2] ?? '');
  }
  return roles;
}

describe('E2E shell route list', () => {
  const screens = registry();

  it('lists every screen of src/routes/screens.ts and nothing else but /login and /setup', () => {
    expect(screens.length).toBeGreaterThan(30);
    const listed = SHELL_ROUTES.map((r) => r.pattern).sort();
    expect(listed).toEqual([...screens.map((s) => s.pattern), '/login', '/setup'].sort());
  });

  it('marks the screens with a component (load) as screens and the others as placeholders', () => {
    expect(screens.find((s) => s.pattern === '/account')?.loaded).toBe(true);
    for (const screen of screens) {
      const route = SHELL_ROUTES.find((r) => r.pattern === screen.pattern);
      expect(route?.kind, screen.pattern).toBe(screen.loaded ? 'screen' : 'placeholder');
    }
  });

  it('opens each screen with the minimum role of its permission', () => {
    const roles = minimumRoles();
    expect(roles.get('view')).toBe('viewer');
    for (const screen of screens) {
      const route = SHELL_ROUTES.find((r) => r.pattern === screen.pattern);
      expect(route?.role, `${screen.pattern} (${screen.permission})`).toBe(
        roles.get(screen.permission),
      );
    }
  });
});
