// SPDX-License-Identifier: Apache-2.0
import { render, screen } from '@testing-library/react';
import { I18nextProvider } from 'react-i18next';
import { describe, expect, it, vi } from 'vitest';

import { createI18n } from '../../../i18n';

import { XLogToolbar, type XLogToolbarProps } from './XLogToolbar';

/** Requirement of every titled test in this file (test/README.md §5). */
export const REQ = { type: 'req', description: 'F-U02' };

function props(overrides: Partial<XLogToolbarProps> = {}): XLogToolbarProps {
  return {
    colorBy: 'status',
    onColorBy: vi.fn(),
    yAxis: 'lin',
    onYAxis: vi.fn(),
    threshold: 1000,
    live: true,
    liveAvailable: true,
    onLive: vi.fn(),
    paused: false,
    onPause: vi.fn(),
    onResume: vi.fn(),
    onSelectVisible: vi.fn(),
    selectVisibleDisabled: false,
    ...overrides,
  };
}

function renderIn(language: 'en' | 'ko', overrides: Partial<XLogToolbarProps>) {
  return render(
    <I18nextProvider i18n={createI18n(language)}>
      <XLogToolbar {...props(overrides)} />
    </I18nextProvider>,
  );
}

describe('XLog toolbar live window (docs/15 §7.5, WO-33 review qa-ux-sec-13)', () => {
  it('names the 5-minute live window while live, whatever range the top bar shows', () => {
    const { unmount } = renderIn('en', { paused: false });
    expect(screen.getByTestId('xlog-live-status')).toHaveTextContent('Live: the last 5 minutes');
    expect(screen.queryByTestId('xlog-live-window')).toBeNull();
    unmount();
    // Paused: the status text stays as it was, the window is named next to it.
    renderIn('en', { paused: true });
    expect(screen.getByTestId('xlog-live-status')).toHaveTextContent(
      /^Paused: data keeps arriving$/,
    );
    expect(screen.getByTestId('xlog-live-window')).toHaveTextContent('5-minute window');
  });

  it('shows no live window without live and translates it', () => {
    const { unmount } = renderIn('en', { live: false, paused: true });
    expect(screen.queryByTestId('xlog-live-window')).toBeNull();
    unmount();
    renderIn('ko', { paused: true });
    expect(screen.getByTestId('xlog-live-window')).toHaveTextContent('5분 창');
  });
});
