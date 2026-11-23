// SPDX-License-Identifier: Apache-2.0
//
// The lazily loaded realtime part of the XLog screen (XLogLiveSource, WO-33 review r1-new-2): it
// reports the state of useXLogLive to the page in the commit that changed it and null when it
// unmounts (live turned off), and renders no live note while there is nothing to say.
import { render, screen } from '@testing-library/react';
import { I18nextProvider } from 'react-i18next';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { fakeSockets } from '../../../../test/fakeWebSocket';
import { toXlogPointFilters } from '../../../api/transactionSearch';
import { createI18n } from '../../../i18n';
import { INITIAL_CONNECTION_STATE, useConnectionStore } from '../../../realtime/connectionStore';
import { setWsManager } from '../../../realtime/hooks';
import { WsManager } from '../../../realtime/WsManager';
import { XLogBuffer } from '../../../xlog/XLogBuffer';

import type { UseXLogLiveOptions, XLogLiveState } from './useXLogLive';
import { XLogLiveSource } from './XLogLiveSource';

export const REQ = { type: 'req', description: 'F-U02' };

describe('XLogLiveSource (docs/15 §7.5, §14 lazily loaded realtime part)', () => {
  beforeEach(() => {
    useConnectionStore.setState(INITIAL_CONNECTION_STATE);
    const sockets = fakeSockets();
    setWsManager(new WsManager({ createSocket: sockets.factory, visibility: null }));
  });

  afterEach(() => {
    setWsManager(null);
  });

  it('T-UI-001 reports the hook state to the page and null when it unmounts', () => {
    const options: UseXLogLiveOptions = {
      enabled: false,
      appId: 1,
      env: undefined,
      kind: 'HTTP',
      filters: toXlogPointFilters([]),
      buffer: new XLogBuffer(10),
    };
    const reports: (XLogLiveState | null)[] = [];
    const onState = vi.fn((state: XLogLiveState | null) => {
      reports.push(state);
    });
    const i18n = createI18n('en');
    const view = render(
      <I18nextProvider i18n={i18n}>
        <XLogLiveSource options={options} onState={onState} />
      </I18nextProvider>,
    );
    // Reported in the first commit: the state of a disabled session (useXLogLive enabled = false).
    expect(reports).toHaveLength(1);
    expect(reports[0]).toMatchObject({
      status: 'idle',
      error: null,
      meta: null,
      window: null,
      paused: false,
      fillError: null,
      polling: false,
      size: 0,
    });
    expect(screen.queryByTestId('xlog-live-fill-error')).toBeNull();
    expect(screen.queryByTestId('xlog-live-polling')).toBeNull();
    // A re-render with the same state reports nothing new.
    view.rerender(
      <I18nextProvider i18n={i18n}>
        <XLogLiveSource options={options} onState={onState} />
      </I18nextProvider>,
    );
    expect(reports).toHaveLength(1);
    view.unmount();
    expect(reports).toHaveLength(2);
    expect(reports[1]).toBeNull();
  });
});
