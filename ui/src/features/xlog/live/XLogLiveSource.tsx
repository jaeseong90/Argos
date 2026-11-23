// SPDX-License-Identifier: Apache-2.0
//
// The realtime XLog of the screen as a lazily loaded part (docs/15 §14 initial path, WO-33 review
// r1-new-2): XLogPage renders it only while live, so the realtime code (useXLogLive, the
// XLogLiveController with its push validation and tx_id ring) is not on the static path of a
// screen opened without live=1. It runs the hook with the page's options, reports the hook's state
// to the page in the commit that changed it (before paint; new points while paused change `size`,
// so the page re-renders as it did when it ran the hook itself), renders the two live notes (failed
// gap fill, REST polling fallback) where the page places it, and reports null when it unmounts
// (live turned off). Nothing else changes: the page passes the same buffer, filters and attempt.
import { useLayoutEffect } from 'react';
import { useTranslation } from 'react-i18next';

import { useXLogLive, type UseXLogLiveOptions, type XLogLiveState } from './useXLogLive';
import { POLL_INTERVAL_MS } from './XLogLiveController';

export interface XLogLiveSourceProps {
  /** The options of useXLogLive, as the page would pass them to the hook. */
  readonly options: UseXLogLiveOptions;
  /** The hook's state after every change, null once the source unmounts. Must be stable. */
  readonly onState: (state: XLogLiveState | null) => void;
}

export function XLogLiveSource({ options, onState }: XLogLiveSourceProps) {
  const { t } = useTranslation(['common', 'xlog']);
  const live = useXLogLive(options);
  const {
    status,
    error,
    meta,
    dictionary,
    window,
    paused,
    lastFill,
    fillError,
    polling,
    size,
    pause,
    resume,
    stats,
  } = live;

  useLayoutEffect(() => {
    onState({
      status,
      error,
      meta,
      dictionary,
      window,
      paused,
      lastFill,
      fillError,
      polling,
      size,
      pause,
      resume,
      stats,
    });
  }, [
    onState,
    status,
    error,
    meta,
    dictionary,
    window,
    paused,
    lastFill,
    fillError,
    polling,
    size,
    pause,
    resume,
    stats,
  ]);
  useLayoutEffect(
    () => () => {
      onState(null);
    },
    [onState],
  );

  return (
    <>
      {fillError === null ? null : (
        <p className="xlog-page__note" role="status" data-testid="xlog-live-fill-error">
          {t('xlog:live.fillError')}
        </p>
      )}
      {polling ? (
        <p className="xlog-page__note" role="status" data-testid="xlog-live-polling">
          {t('xlog:live.polling', { seconds: POLL_INTERVAL_MS / 1000 })}
        </p>
      ) : null}
    </>
  );
}
