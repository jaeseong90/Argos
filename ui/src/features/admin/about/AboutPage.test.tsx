// SPDX-License-Identifier: Apache-2.0
//
// About and open-source licenses (docs/15 §9.10 `/admin/about`, `/admin/licenses`, 05 §2.11-10,
// O-L04, ADR-0010 C-153, WO-39): the version fields equal GET /about (server version, commit,
// protocol, uptime, support policy path) next to the UI build version, the agent version
// distribution with an unreported version labelled, the three license sections with the build's
// components or, without a generated list, "Not included in this build" and the next action;
// component names as text (T-UI-005); error with retry.
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import {
  envelope,
  errorBody,
  installApiStub,
  uninstallApiStub,
  type ApiStub,
} from '../../../../test/apiStub';
import {
  findInjectedMarkup,
  XSS_PAYLOADS,
  xssFired,
} from '../../../components/testing/xssPayloads';
import { adminApp } from '../testing/renderAdmin';

import { UI_VERSION } from './AboutPage';

/** Requirements of every titled test in this file (test/README.md §5). */
export const REQ = { type: 'req', description: 'O-L04' };
export const REQ_XSS = { type: 'req', description: 'SEC-61' };

const FIND = { timeout: 5000 };

let stub: ApiStub;

function missing(reason: string) {
  return { available: false, reason, components: [], truncated: false };
}

async function ready(testId: string, state = 'ready'): Promise<HTMLElement> {
  const page = await screen.findByTestId(testId, {}, FIND);
  await waitFor(() => {
    expect(page).toHaveAttribute('data-state', state);
  }, FIND);
  return page;
}

beforeEach(() => {
  stub = installApiStub();
  stub.on('GET /api/v1/about', () =>
    envelope({
      version: UI_VERSION,
      commit: 'abc1234',
      protocol_version: 1,
      agent_versions: [
        { version: '1.0.0', count: 5 },
        { version: null, count: 1 },
      ],
      uptime_s: 3_725,
      support_policy_url: 'SECURITY.md#supported-versions',
    }),
  );
  stub.on('GET /api/v1/about/licenses', () =>
    envelope({
      server: {
        available: true,
        reason: null,
        truncated: false,
        components: [
          { name: 'org.springframework:spring-core', version: '6.2.0', license: 'Apache-2.0' },
          { name: 'com.example:no-version', version: null, license: null },
        ],
      },
      ui: missing('not_generated'),
      agent: missing('unreadable'),
    }),
  );
});

afterEach(() => {
  uninstallApiStub();
  Reflect.deleteProperty(window, '__xss');
});

describe('/admin/about (docs/15 §9.10)', () => {
  it('shows the server and UI versions, commit, protocol, uptime, support policy and agent versions', async () => {
    render(adminApp('/admin/about', { role: 'viewer' }).element);
    await ready('admin-about');
    expect(screen.getByTestId('admin-about-version')).toHaveTextContent(UI_VERSION);
    expect(screen.getByTestId('admin-about-ui_version')).toHaveTextContent(UI_VERSION);
    expect(screen.getByTestId('admin-about-commit')).toHaveTextContent('abc1234');
    expect(screen.getByTestId('admin-about-protocol_version')).toHaveTextContent('1');
    expect(screen.getByTestId('admin-about-uptime_s')).toHaveAttribute('data-value', '3725');
    expect(screen.getByTestId('admin-about-support_policy_url')).toHaveTextContent(
      'SECURITY.md#supported-versions',
    );
    expect(screen.queryByTestId('admin-about-mismatch')).toBeNull();
    const table = document.querySelector<HTMLElement>('[data-table-id="admin-about-agents"]');
    expect(table).toHaveTextContent('1.0.0');
    expect(table).toHaveTextContent('Not reported');
    expect(table).toHaveTextContent('5');
  });

  it('warns when the server reports another version than the UI build', async () => {
    stub.on('GET /api/v1/about', () =>
      envelope({
        version: '9.9.9',
        commit: 'unknown',
        protocol_version: 1,
        agent_versions: [],
        uptime_s: 1,
        support_policy_url: null,
      }),
    );
    render(adminApp('/admin/about').element);
    await ready('admin-about');
    expect(screen.getByTestId('admin-about-mismatch')).toBeInTheDocument();
    expect(screen.getByTestId('admin-about-support_policy_url')).toHaveTextContent(
      'Not given by this build',
    );
  });

  it('shows the error with its request id and retries', async () => {
    let fail = true;
    stub.on('GET /api/v1/about', () =>
      fail
        ? errorBody(500, 'internal_error', { requestId: 'req-about' })
        : envelope({
            version: UI_VERSION,
            commit: 'c',
            protocol_version: 1,
            agent_versions: [],
            uptime_s: 0,
            support_policy_url: null,
          }),
    );
    render(adminApp('/admin/about').element);
    const page = await ready('admin-about', 'error');
    expect(page).toHaveTextContent('req-about');
    fail = false;
    fireEvent.click(within(page).getByRole('button', { name: /retry/i }));
    await ready('admin-about');
  });
});

describe('/admin/licenses (05 §2.11-10, C-153)', () => {
  it('lists the components of a generated section and says what is not in this build', async () => {
    render(adminApp('/admin/licenses', { role: 'viewer' }).element);
    await ready('admin-licenses');
    const server = screen.getByTestId('admin-licenses-server');
    expect(server).toHaveAttribute('data-available', 'true');
    expect(server).toHaveTextContent('org.springframework:spring-core');
    expect(server).toHaveTextContent('Apache-2.0');
    const ui = screen.getByTestId('admin-licenses-ui');
    expect(ui).toHaveAttribute('data-available', 'false');
    expect(ui).toHaveAttribute('data-reason', 'not_generated');
    expect(within(ui).getByTestId('admin-licenses-ui-missing')).toHaveTextContent(
      'Not included in this build.',
    );
    expect(within(ui).getByTestId('admin-licenses-ui-missing')).toHaveTextContent('NOTICE');
    expect(screen.getByTestId('admin-licenses-agent')).toHaveTextContent('could not be read');
  });

  it('T-UI-005 renders XSS payloads in component names, versions and licenses as text', async () => {
    stub.on('GET /api/v1/about/licenses', () =>
      envelope({
        server: {
          available: true,
          reason: null,
          truncated: true,
          components: XSS_PAYLOADS.map((payload) => ({
            name: payload,
            version: payload,
            license: payload,
          })),
        },
        ui: missing('not_generated'),
        agent: missing('not_generated'),
      }),
    );
    render(adminApp('/admin/licenses').element);
    const page = await ready('admin-licenses');
    expect(findInjectedMarkup(page)).toEqual([]);
    expect(xssFired()).toBe(false);
    expect(screen.getByTestId('admin-licenses-server-truncated')).toBeInTheDocument();
  });
});
