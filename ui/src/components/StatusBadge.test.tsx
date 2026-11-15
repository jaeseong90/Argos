// SPDX-License-Identifier: Apache-2.0
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';

import { AlertStateLabel, normalizeAlertState } from './AlertStateLabel';
import { KindFilter } from './KindFilter';
import { normalizeStatus, StatusBadge } from './StatusBadge';
import { installDomStubs } from './testing/dom';
import { withI18n } from './testing/withI18n';

/** Requirement of every titled test in this file (test/README.md §5). */
export const REQ = { type: 'req', description: 'F-S28' };

installDomStubs();

/** Reads a stylesheet next to this test (Vitest turns CSS imports into empty modules). */
async function readSibling(name: string): Promise<string> {
  const fsModule = 'node:fs';
  const fs = (await import(/* @vite-ignore */ fsModule)) as {
    readFileSync(path: URL, encoding: 'utf8'): string;
  };
  return fs.readFileSync(new URL(name, import.meta.url), 'utf8');
}

const badgesCss = await readSibling('./badges.css');

describe('StatusBadge (docs/15 §6.4, C-8)', () => {
  it('T-UI-008 maps the four states to their colour classes and always shows the label', () => {
    for (const [status, label, className] of [
      ['UP', 'UP', 'status-badge--up'],
      ['stale', 'STALE', 'status-badge--stale'],
      ['DOWN', 'DOWN', 'status-badge--down'],
      ['WARN', 'WARN', 'status-badge--warn'],
    ] as const) {
      const { unmount } = render(<StatusBadge status={status} />, { wrapper: withI18n('en') });
      const badge = screen.getByTestId('status-badge');
      expect(badge).toHaveClass(className);
      expect(badge.querySelector('.status-badge__label')).toHaveTextContent(label);
      unmount();
    }
    expect(normalizeStatus('bogus')).toBe('UNKNOWN');
    expect(normalizeStatus(null)).toBe('UNKNOWN');
  });

  it('T-UI-008 uses the status tokens: dot colour, -text label colour, orange border for WARN', () => {
    const rule = (selector: string): string => {
      const start = badgesCss.indexOf(`${selector} {`);
      return start < 0 ? '' : badgesCss.slice(start, badgesCss.indexOf('}', start));
    };
    expect(rule('.status-badge--up .status-badge__dot')).toContain('var(--status-up)');
    expect(rule('.status-badge--stale .status-badge__dot')).toContain('var(--status-stale)');
    expect(rule('.status-badge--down .status-badge__dot')).toContain('var(--status-down)');
    expect(rule('.status-badge--warn')).toContain('border: 2px solid var(--status-warn)');
    expect(rule('.status-badge--warn .status-badge__dot')).toContain('var(--status-up)');
    expect(rule('.status-badge--stale')).toContain('var(--status-stale-text)');
  });

  it('T-UI-008 explains STALE (60 s) and DOWN (180 s or 5 min after shutdown) per C-8', async () => {
    const user = userEvent.setup();
    const { unmount } = render(<StatusBadge status="STALE" />, { wrapper: withI18n('en') });
    expect(screen.getByTestId('status-badge')).toHaveTextContent('No heartbeat for 60 s or more.');
    await user.hover(screen.getByTestId('status-badge'));
    expect(await screen.findByRole('tooltip')).toHaveTextContent('No heartbeat for 60 s or more.');
    unmount();
    render(<StatusBadge status="DOWN" />, { wrapper: withI18n('en') });
    expect(screen.getByTestId('status-badge')).toHaveTextContent(
      'No heartbeat for 180 s or more, or 5 minutes since the agent shut down.',
    );
  });

  it('T-UI-008 lists the WARN reasons with the appendix B terms in Korean', () => {
    render(
      <StatusBadge status="WARN" warnReasons={['clock_skew', 'drops', 'module_disabled', 'x']} />,
      {
        wrapper: withI18n('ko'),
      },
    );
    expect(screen.getByTestId('status-badge')).toHaveTextContent(
      '경고와 함께 동작 중: · 시간 오차 · 유실 · 모듈 비활성 · 기타',
    );
  });
});

describe('alert state labels (docs/01 appendix B)', () => {
  it('T-UI-008 shows OK/PENDING/FIRING/ACKED/RESOLVED/NO_DATA as 정상/대기/발생/확인/해제/데이터 없음', () => {
    const expected = {
      OK: '정상',
      PENDING: '대기',
      FIRING: '발생',
      ACKED: '확인',
      RESOLVED: '해제',
      NO_DATA: '데이터 없음',
    } as const;
    for (const [state, label] of Object.entries(expected)) {
      const { unmount } = render(<AlertStateLabel state={state} />, { wrapper: withI18n('ko') });
      expect(
        screen.getByTestId('alert-state').querySelector('.alert-state__label'),
      ).toHaveTextContent(label);
      unmount();
    }
  });

  it('shows English labels, severity and normalises API spellings', () => {
    render(<AlertStateLabel state="firing" severity="critical" />, { wrapper: withI18n('en') });
    const label = screen.getByTestId('alert-state');
    expect(label).toHaveTextContent('FiringCritical');
    expect(label).toHaveClass('alert-state--firing', 'alert-state--sev-critical');
    expect(normalizeAlertState('nodata')).toBe('NO_DATA');
    expect(normalizeAlertState('no_data')).toBe('NO_DATA');
    expect(normalizeAlertState('weird')).toBeNull();
  });

  it('T-UI-006 labels the kinds 요청/배치/메시지 in Korean (appendix B)', () => {
    render(<KindFilter value="HTTP" onChange={() => undefined} />, { wrapper: withI18n('ko') });
    expect(screen.getAllByRole('radio').map((radio) => radio.textContent)).toEqual([
      '요청',
      '배치',
      '메시지',
    ]);
  });
});
