// SPDX-License-Identifier: Apache-2.0
//
// The instance header (05 §2.7-1, WO-37): every item shows the GET /instances/{id} value, a missing
// value is the D-23 dash and never 0, the agent compatibility warnings (old, unsupported), the IP
// capture and virtual-thread badges, the restart count from this instance's start events (other
// instances' left out), the clock skew warning at 1 s, the four links of 05 §2.7-6 with the
// carried range, and collected strings rendered as text (SEC-61).
import { render, screen, within } from '@testing-library/react';
import type { ReactNode } from 'react';
import { MemoryRouter } from 'react-router';
import { afterEach, describe, expect, it } from 'vitest';

import type { InstanceEvent } from '../../../api/instances';
import { untrusted } from '../../../api/untrusted';
import { installDomStubs } from '../../../components/testing/dom';
import { withI18n } from '../../../components/testing/withI18n';
import {
  findInjectedMarkup,
  xssFired,
  XSS_PAYLOADS,
} from '../../../components/testing/xssPayloads';
import { AlertsQ, CommonQ, defaultState, parseUrl, XLogQ } from '../../../state/urlState';
import { bareInstance, FROM, instanceBody, NOW, TO } from '../testing/fixtures';

import { InstanceHeader } from './InstanceHeader';
import { instanceMarkers, restartCount, uptimeMs } from './model';

/** Requirements of every titled test in this file (test/README.md §5). */
export const REQ = { type: 'req', description: 'F-U08' };
export const REQ_MISSING = { type: 'req', description: 'D-23' };
export const REQ_STATE = { type: 'req', description: 'F-S28' };
export const REQ_XSS = { type: 'req', description: 'SEC-61' };

installDomStubs();

const CURRENT = {
  ...defaultState(CommonQ),
  time: { kind: 'absolute', from: FROM, to: TO } as const,
  env: untrusted('prod'),
};

function event(overrides: Partial<InstanceEvent>): InstanceEvent {
  return {
    event_id: 'e-1',
    type: 'start',
    ts: '2026-11-20T11:30:00.000Z',
    instance_id: 7,
    title: null,
    detail: null,
    detail_truncated: false,
    actor: null,
    version: null,
    ...overrides,
  };
}

function wrapper({ children }: { readonly children: ReactNode }) {
  const I18n = withI18n('en');
  return (
    <MemoryRouter>
      <I18n>{children}</I18n>
    </MemoryRouter>
  );
}

function item(id: string): HTMLElement {
  return screen.getByTestId(`instance-meta-${id}`);
}

afterEach(() => {
  Reflect.deleteProperty(window, '__xss');
});

describe('InstanceHeader (05 §2.7-1)', () => {
  it('shows every item as the API answers it', () => {
    const events = [
      event({ event_id: 'a' }),
      event({ event_id: 'b', ts: '2026-11-20T11:40:00.000Z' }),
      event({ event_id: 'c', instance_id: 8 }),
      event({ event_id: 'd', type: 'deploy', instance_id: null }),
    ];
    render(
      <InstanceHeader
        instance={instanceBody()}
        events={events}
        eventsTruncated={false}
        now={NOW}
        current={CURRENT}
      />,
      { wrapper },
    );
    expect(screen.getByTestId('instance-name')).toHaveTextContent('order-api-1');
    expect(item('state')).toHaveAttribute('data-status', 'UP');
    expect(item('app')).toHaveTextContent('order-api');
    expect(screen.getByTestId('instance-meta-env')).toHaveTextContent('prod');
    expect(item('version')).toHaveTextContent('2.4.1');
    expect(item('agent-version')).toHaveTextContent('1.0.3');
    expect(item('agent-version')).toHaveAttribute('data-value', 'ok');
    expect(screen.queryByTestId('instance-compat-warning')).toBeNull();
    expect(item('jvm')).toHaveTextContent('21.0.5');
    expect(item('jvm')).toHaveTextContent('Eclipse Adoptium');
    expect(item('os')).toHaveTextContent('Linux 6.8 amd64');
    expect(item('host')).toHaveTextContent('host-a.internal');
    expect(item('pid')).toHaveAttribute('data-value', '4242');
    expect(item('cgroup')).toHaveTextContent('v2');
    expect(item('started-at')).toHaveAttribute(
      'data-value',
      String(Date.parse('2026-11-18T10:00:00.000Z')),
    );
    expect(item('uptime')).toHaveAttribute('data-value', String(2 * 86_400_000 + 2 * 3_600_000));
    expect(item('uptime')).toHaveTextContent('2d 2h');
    expect(item('restarts')).toHaveAttribute('data-value', '2');
    expect(item('clock-skew')).toHaveAttribute('data-value', '12');
    expect(screen.queryByTestId('instance-clock-skew-warning')).toBeNull();
    expect(item('drops')).toHaveAttribute('data-value', '0');
    expect(item('drops')).toHaveTextContent('0');
    expect(item('modules-disabled')).toHaveAttribute('data-value', '0');
    expect(item('modules-disabled')).toHaveTextContent('None');
    expect(item('ip-mode')).toHaveAttribute('data-value', 'masked');
    expect(item('ip-mode')).toHaveTextContent('IP capture: Masked');
    expect(item('virtual-threads')).toHaveAttribute('data-value', 'on');
    expect(item('virtual-threads')).toHaveTextContent('Virtual threads: On');
  });

  it('T-UI-007 shows the dash for every missing value, never 0', () => {
    render(
      <InstanceHeader
        instance={bareInstance({ state: 'DOWN' })}
        events={null}
        eventsTruncated={false}
        now={NOW}
        current={CURRENT}
      />,
      { wrapper },
    );
    for (const id of [
      'version',
      'agent-version',
      'jvm',
      'os',
      'host',
      'pid',
      'cgroup',
      'started-at',
      'uptime',
      'restarts',
      'clock-skew',
      'drops',
      'modules-disabled',
      'ip-mode',
      'virtual-threads',
    ]) {
      const value = item(id).querySelector('dd') ?? item(id);
      expect(value, id).toHaveTextContent('—');
      expect(value.textContent, id).not.toMatch(/\d/);
    }
    expect(item('pid')).toHaveAttribute('data-missing', 'true');
    expect(item('state')).toHaveAttribute('data-status', 'DOWN');
  });

  it('warns about an old and an unsupported agent version', () => {
    const { rerender } = render(
      <InstanceHeader
        instance={instanceBody({ compat: 'old', state: 'WARN' })}
        events={[]}
        eventsTruncated={false}
        now={NOW}
        current={CURRENT}
      />,
      { wrapper },
    );
    const warning = screen.getByTestId('instance-compat-warning');
    expect(warning).toHaveAttribute('data-compat', 'old');
    expect(warning).toHaveTextContent('Old agent version: upgrade recommended');
    rerender(
      <InstanceHeader
        instance={instanceBody({ compat: 'unsupported' })}
        events={[]}
        eventsTruncated={false}
        now={NOW}
        current={CURRENT}
      />,
    );
    expect(screen.getByTestId('instance-compat-warning')).toHaveAttribute(
      'data-compat',
      'unsupported',
    );
    expect(screen.getByTestId('instance-compat-warning')).toHaveTextContent('not supported');
  });

  it('shows the IP and virtual-thread modes, skew warnings, disabled modules and a cut restart count', () => {
    render(
      <InstanceHeader
        instance={instanceBody({
          ip_mode: 'off',
          virtual_threads: false,
          clock_skew_ms: -1500,
          modules_disabled: [untrusted('kafka'), untrusted('quartz')],
        })}
        events={[event({})]}
        eventsTruncated
        now={NOW}
        current={CURRENT}
      />,
      { wrapper },
    );
    expect(item('ip-mode')).toHaveTextContent('IP capture: Off');
    expect(item('virtual-threads')).toHaveTextContent('Virtual threads: Off');
    expect(screen.getByTestId('instance-clock-skew-warning')).toBeInTheDocument();
    expect(
      within(screen.getByTestId('instance-modules-disabled')).getAllByRole('listitem'),
    ).toHaveLength(2);
    expect(item('restarts')).toHaveTextContent('At least 1');
  });

  it('links to this instance XLog (color=instance), active transactions, alert history and config history', () => {
    render(
      <InstanceHeader
        instance={instanceBody()}
        events={[]}
        eventsTruncated={false}
        now={NOW}
        current={CURRENT}
      />,
      { wrapper },
    );
    const href = (id: string) => screen.getByTestId(id).getAttribute('href') ?? '';
    const xlog = new URL(href('instance-link-xlog'), 'http://x');
    expect(xlog.pathname).toBe('/apps/1/xlog');
    const xlogState = parseUrl(XLogQ, xlog.search).state;
    expect(xlogState.color).toBe('instance');
    expect(xlogState.filters).toEqual([{ field: 'instance', op: 'eq', value: '7' }]);
    expect(xlogState.time).toEqual({ kind: 'absolute', from: FROM, to: TO });
    expect(xlogState.env).toBe('prod');
    const active = new URL(href('instance-link-active'), 'http://x');
    expect(active.pathname).toBe('/apps/1/active');
    expect(parseUrl(CommonQ, active.search).state.filters).toEqual([
      { field: 'instance', op: 'eq', value: '7' },
    ]);
    const alerts = new URL(href('instance-link-alerts'), 'http://x');
    expect(alerts.pathname).toBe('/alerts');
    expect(parseUrl(AlertsQ, alerts.search).state.app).toBe(1);
    const config = new URL(href('instance-link-config'), 'http://x');
    expect(config.pathname).toBe('/admin/config');
    expect(config.searchParams.get('app')).toBe('1');
  });

  it('T-UI-005 renders collected strings as text', () => {
    for (const payload of XSS_PAYLOADS) {
      const { unmount } = render(
        <InstanceHeader
          instance={instanceBody({
            name: untrusted(payload),
            host: untrusted(payload),
            os: untrusted(payload),
            jvm_vendor: untrusted(payload),
            version: untrusted(payload),
            agent_version: untrusted(payload),
            cgroup: untrusted(payload),
            modules_disabled: [untrusted(payload)],
            app: { app_id: 1, name: untrusted(payload), env: untrusted(payload) },
          })}
          events={[]}
          eventsTruncated={false}
          now={NOW}
          current={CURRENT}
        />,
        { wrapper },
      );
      expect(findInjectedMarkup(document.body)).toEqual([]);
      expect(screen.getByTestId('instance-name').textContent).toBe(payload.slice(0, 4096));
      unmount();
    }
    expect(xssFired()).toBe(false);
  });
});

describe('header model', () => {
  it('has no uptime for a DOWN instance or without a start time', () => {
    expect(uptimeMs(instanceBody({ state: 'DOWN' }), NOW)).toBeNull();
    expect(uptimeMs(instanceBody({ started_at: null }), NOW)).toBeNull();
    expect(uptimeMs(instanceBody({ started_at: '2026-11-20T12:00:01.000Z' }), NOW)).toBeNull();
    expect(uptimeMs(instanceBody({ state: 'STALE' }), NOW)).not.toBeNull();
  });

  it('counts only this instance start events; null while not loaded', () => {
    expect(restartCount(null, 7)).toBeNull();
    expect(restartCount([], 7)).toBe(0);
    expect(restartCount([event({}), event({ instance_id: 8 }), event({ type: 'stop' })], 7)).toBe(
      1,
    );
  });

  it('marks app deploys and this instance start, stop and config events', () => {
    const markers = instanceMarkers(
      [
        event({ event_id: 'deploy', type: 'deploy', instance_id: null }),
        event({ event_id: 'start' }),
        event({ event_id: 'other', instance_id: 8 }),
        event({ event_id: 'config', type: 'config' }),
        event({ event_id: 'bad', ts: 'not a time' }),
      ],
      7,
    );
    expect(markers.map((m) => [m.id, m.type])).toEqual([
      ['deploy', 'deploy'],
      ['start', 'start'],
      ['config', 'config'],
    ]);
  });
});
