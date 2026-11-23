// SPDX-License-Identifier: Apache-2.0
//
// Test fixtures of the command screens (WO-37): command views, thread dump results and a render
// harness with a signed-in role, the router and the query client. Test-only module: nothing in the
// application imports it.
import { QueryClientProvider } from '@tanstack/react-query';
import type { ReactElement, ReactNode } from 'react';
import { createMemoryRouter, RouterProvider } from 'react-router';

import type {
  CommandInstance,
  CommandResultView,
  CommandThread,
  CommandView,
} from '../../../../api/commands';
import { createQueryClient } from '../../../../api/queryClient';
import { untrusted } from '../../../../api/untrusted';
import { withI18n } from '../../../../components/testing/withI18n';
import type { SupportedLanguage } from '../../../../i18n';
import { useAuthStore, type Role } from '../../../../state/authStore';

export const ME = 'u-me';
export const OTHER = 'u-other';

let seq = 0;

/** A UUIDv4-shaped command id (distinct per call). */
export function commandId(n?: number): string {
  seq += 1;
  const hex = (n ?? seq).toString(16).padStart(12, '0');
  return `0a1b2c3d-4e5f-4a6b-8c7d-${hex}`;
}

export function commandView(
  overrides: Omit<Partial<CommandView>, 'requested_by_name'> & {
    requested_by_name?: string | null;
  } = {},
): CommandView {
  const { requested_by_name: name = null, ...rest } = overrides;
  const id = rest.command_id ?? commandId();
  return {
    command_id: id,
    instance_id: 7,
    type: 'thread_dump',
    args: {},
    status: 'done',
    error: null,
    requested_by: ME,
    requested_at: '2026-11-24T10:00:00.000Z',
    sent_at: '2026-11-24T10:00:00.050Z',
    finished_at: '2026-11-24T10:00:00.850Z',
    expires_at: new Date(Date.now() + 60_000).toISOString(),
    duration_ms: 800,
    result_size: 2048,
    truncated: false,
    ...rest,
    requested_by_name: name === null ? null : untrusted(name),
  };
}

export function thread(
  overrides: Omit<Partial<CommandThread>, 'name'> & { name?: string } = {},
): CommandThread {
  const { name, ...rest } = overrides;
  return {
    id: 1,
    name: untrusted(name ?? 'main'),
    state: untrusted('RUNNABLE'),
    virtual: false,
    locks_held: [],
    waiting_on: null,
    frames: [untrusted('java.lang.Thread.run(Thread.java:840)')],
    tx_id: null,
    ...rest,
  };
}

export function frames(...lines: string[]) {
  return lines.map((line) => untrusted(line));
}

export function dumpResult(
  threads: CommandThread[],
  overrides: Partial<CommandResultView> = {},
): CommandResultView {
  return {
    command_id: commandId(),
    instance_id: 7,
    type: 'thread_dump',
    format: 'json',
    content_type: 'application/json',
    size_bytes: 4096,
    truncated: false,
    text: null,
    threads,
    deadlocks: [],
    ...overrides,
  };
}

export function textResult(type: string, text: string, id?: string): CommandResultView {
  return {
    command_id: id ?? commandId(),
    instance_id: 7,
    type,
    format: 'json',
    content_type: 'application/json',
    size_bytes: text.length,
    truncated: false,
    text: untrusted(text),
    threads: null,
    deadlocks: null,
  };
}

export function instance(overrides: Partial<CommandInstance> = {}): CommandInstance {
  return {
    instance_id: 7,
    name: untrusted('order-1'),
    state: 'UP',
    app: { app_id: 3, name: untrusted('shop'), env: untrusted('prod') },
    ...overrides,
  } as CommandInstance;
}

/** Signs a user in with a role (the store only; no server call). */
export function signIn(role: Role, userId: string = ME): void {
  useAuthStore.setState({
    status: 'authenticated',
    user: { id: userId, username: 'tester' },
    role,
    permissions: [],
    sessionExpiresAt: null,
    mustChangePassword: false,
  });
}

export interface AppTree {
  /** The element to render (test: `render(tree.element)`). */
  readonly element: ReactElement;
  /** pathname + search of the router's current location. */
  readonly url: () => string;
}

/** `ui` at an instance detail route with i18n, the query client and a memory router. */
export function appTree(
  ui: ReactNode,
  path = '/apps/3/instances/7',
  language: SupportedLanguage = 'en',
): AppTree {
  const client = createQueryClient();
  const router = createMemoryRouter(
    [
      { path: '/apps/:appId/instances/:instanceId', element: ui },
      { path: '*', element: <div data-testid="elsewhere" /> },
    ],
    { initialEntries: [path] },
  );
  const I18n = withI18n(language);
  return {
    element: (
      <I18n>
        <QueryClientProvider client={client}>
          <RouterProvider router={router} />
        </QueryClientProvider>
      </I18n>
    ),
    url: () => `${router.state.location.pathname}${router.state.location.search}`,
  };
}
