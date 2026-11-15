// SPDX-License-Identifier: Apache-2.0
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { AppSelect, buildAppGroups, matchesSearch, type AppOption } from './AppSelect';
import { EnvSelect } from './EnvSelect';
import { installDomStubs } from './testing/dom';
import { withI18n } from './testing/withI18n';
import { findInjectedMarkup, XSS_PAYLOADS, xssFired } from './testing/xssPayloads';

/** Requirement of every titled test in this file (test/README.md §5). */
export const REQ = { type: 'req', description: 'F-U14' };

installDomStubs();

const wrapper = withI18n('en');

const APPS: AppOption[] = [
  { app_id: 1, name: 'shop-api', env: 'prod' },
  { app_id: 2, name: 'billing', env: 'prod' },
  { app_id: 3, name: 'shop-api', env: 'stage' },
  { app_id: 4, name: 'auth', env: 'dev' },
  { app_id: 5, name: 'admin', env: 'prod' },
];

afterEach(() => {
  Reflect.deleteProperty(window, '__xss');
});

/** Ant Design renders the open list in a portal; option groups and options carry these classes. */
function listLabels(): string[] {
  return [...document.querySelectorAll('.ant-select-item-group, .ant-select-item-option')].map(
    (node) =>
      node.classList.contains('ant-select-item-group') ? `# ${node.textContent}` : node.textContent,
  );
}

function optionByText(text: string): HTMLElement {
  const option = [...document.querySelectorAll<HTMLElement>('.ant-select-item-option')].find(
    (node) => node.textContent === text,
  );
  if (option === undefined) {
    throw new Error(`option ${text} not shown`);
  }
  return option;
}

async function openSelect(testId: string): Promise<void> {
  const user = userEvent.setup();
  await user.click(within(screen.getByTestId(testId)).getByRole('combobox'));
  await waitFor(() => {
    expect(document.querySelector('.ant-select-dropdown')).not.toBeNull();
  });
}

describe('AppSelect (docs/15 §6.5, 05 §2.0-6)', () => {
  it('puts recent apps first and groups the others by environment, sorted by name', () => {
    const { recent, byEnv } = buildAppGroups(APPS, [3, 99, 3, 1]);
    expect(recent.map((app) => app.app_id)).toEqual([3, 1]);
    expect(byEnv.map(([env, apps]) => [env, apps.map((app) => app.app_id)])).toEqual([
      ['dev', [4]],
      ['prod', [5, 2]],
    ]);
    expect(matchesSearch({ search: 'shop-api\u0000prod' }, ' PROD ')).toBe(true);
    expect(matchesSearch({ search: 'shop-api\u0000prod' }, 'stage')).toBe(false);
    expect(matchesSearch(undefined, 'x')).toBe(false);
  });

  it('shows the recent group first, searches by name or environment and emits the app id', async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    render(<AppSelect apps={APPS} value={1} onChange={onChange} recentAppIds={[3]} />, { wrapper });
    expect(screen.getByTestId('app-select')).toHaveTextContent('shop-apiprod');
    await openSelect('app-select');
    expect(listLabels()).toEqual([
      '# Recent',
      'shop-apistage',
      '# dev',
      'authdev',
      '# prod',
      'adminprod',
      'billingprod',
      'shop-apiprod',
    ]);
    await user.keyboard('bill');
    await waitFor(() => {
      expect(listLabels()).toEqual(['# prod', 'billingprod']);
    });
    await user.click(optionByText('billingprod'));
    expect(onChange).toHaveBeenCalledWith(2);
  });

  it('T-UI-005 renders collected app and environment names as text', async () => {
    const apps = XSS_PAYLOADS.map((payload, index) => ({
      app_id: index + 1,
      name: payload,
      env: payload,
    }));
    render(<AppSelect apps={apps} value={1} onChange={vi.fn()} recentAppIds={[2]} />, { wrapper });
    await openSelect('app-select');
    // The selected value and every option the list renders (it is virtualised) show the names as
    // text with the full value as title.
    const titles = new Set(
      [...document.body.querySelectorAll('[title]')].map((node) => node.getAttribute('title')),
    );
    expect(titles.has(XSS_PAYLOADS[0] ?? '')).toBe(true);
    const options = [...document.querySelectorAll('.ant-select-item-option')];
    expect(options.length).toBeGreaterThanOrEqual(5);
    for (const option of options) {
      const [name, env] = option.querySelectorAll('.safe-text');
      expect(XSS_PAYLOADS).toContain(name?.textContent);
      expect(env?.textContent).toBe(name?.textContent);
    }
    expect(findInjectedMarkup(document.body)).toEqual([]);
    expect(xssFired()).toBe(false);
  });
});

describe('EnvSelect (docs/15 §6.5, 05 §2.0-6)', () => {
  it('offers all environments, recent ones first and the rest sorted, and emits null for all', async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    render(
      <EnvSelect
        envs={['prod', 'dev', 'stage', 'dev']}
        value="prod"
        onChange={onChange}
        recentEnvs={['stage', 'qa']}
      />,
      { wrapper },
    );
    await openSelect('env-select');
    expect(listLabels()).toEqual([
      'All environments',
      '# Recent',
      'stage',
      '# Environments',
      'dev',
      'prod',
    ]);
    await user.click(optionByText('All environments'));
    expect(onChange).toHaveBeenLastCalledWith(null);
  });

  it('has no "all" entry when the screen needs one environment, and searches', async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    render(<EnvSelect envs={['prod', 'dev']} value={null} onChange={onChange} allowAll={false} />, {
      wrapper: withI18n('ko'),
    });
    expect(screen.getByTestId('env-select')).toHaveTextContent('환경 선택');
    await openSelect('env-select');
    expect(listLabels()).toEqual(['# 환경', 'dev', 'prod']);
    await user.keyboard('pro');
    await waitFor(() => {
      expect(listLabels()).toEqual(['# 환경', 'prod']);
    });
    await user.click(optionByText('prod'));
    expect(onChange).toHaveBeenLastCalledWith('prod');
  });
});
