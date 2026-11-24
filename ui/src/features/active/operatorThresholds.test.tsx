// SPDX-License-Identifier: Apache-2.0
//
// Operator-wide default of the elapsed colour thresholds (ADR-0010 C-225 (3), 05 §2.6-1): the
// server setting argos.ui.active-thresholds-ms arrives as `default_thresholds_ms` with the REST
// snapshot; the screen uses the viewer's stored thresholds first, then that default, then 1/3/8 s,
// and "Default" returns to the operator default (the stored entry is removed).
import { fireEvent, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeAll, beforeEach, describe, expect, it } from 'vitest';

import { envelope, installApiStub, uninstallApiStub, type ApiStub } from '../../../test/apiStub';
import {
  PRELOAD_TIMEOUT_MS,
  preloadRoutes,
  renderApp,
  resetShellState,
  signedIn,
} from '../../../test/renderApp';
import { resetWrittenUrls } from '../../state/useUrlState';

import { scenario } from './testing/fixtures';
import { operatorThresholds, readThresholds } from './thresholdPreference';

/** Requirements of every titled test in this file (test/README.md §5). */
export const REQ = { type: 'req', description: 'F-U04' };

const FIND = { timeout: 5000 };
const ACTIVE = 'GET /api/v1/apps/1/active';

let stub: ApiStub;

function answer(defaults: unknown) {
  stub.on(ACTIVE, () =>
    envelope(
      { ...scenario(), default_thresholds_ms: defaults },
      { generated_at: '2026-11-23T10:00:02.000Z', request_id: 'req-active' },
    ),
  );
}

async function openActive() {
  signedIn(stub);
  const app = renderApp('/apps/1/active');
  const page = await screen.findByTestId('active-page', {}, FIND);
  await waitFor(() => {
    expect(page).toHaveAttribute('data-state', 'ready');
  }, FIND);
  return app;
}

beforeAll(preloadRoutes, PRELOAD_TIMEOUT_MS);

beforeEach(() => {
  resetShellState();
  resetWrittenUrls();
  stub = installApiStub();
});

afterEach(() => {
  uninstallApiStub();
});

describe('active: operator default thresholds (C-225 (3))', () => {
  it('T-API-013c without a viewer choice the operator default colours the bars and "Default" stays off', async () => {
    answer([2000, 5000, 10000]);
    await openActive();
    await waitFor(() => {
      // order-2: 9 s → 5–10 s, 0.7 s → < 2 s (the same recount as custom 2/5/10 s).
      expect(screen.getByTestId('active-bar-2')).toHaveAttribute('data-buckets', '1,0,1,0');
    }, FIND);
    expect(screen.getByTestId('active-threshold-1')).toHaveValue(2);
    expect(screen.getByTestId('active-thresholds')).toHaveAttribute('data-custom', 'false');
    expect(screen.getByTestId('active-thresholds')).toHaveAttribute('data-defaults', '2,5,10');
    expect(screen.getByTestId('active-thresholds-reset')).toBeDisabled();
    expect(localStorage.getItem('argos.ui.activeThresholds')).toBeNull();
  });

  it('the viewer value wins over the operator default; "Default" returns to the operator default', async () => {
    localStorage.setItem('argos.ui.activeThresholds', '[1,3,8]');
    answer([2000, 5000, 10000]);
    await openActive();
    // The stored 1/3/8 s, not the operator's 2/5/10 s: the server's own buckets.
    await waitFor(() => {
      expect(screen.getByTestId('active-bar-2')).toHaveAttribute('data-buckets', '1,0,0,1');
    }, FIND);
    expect(screen.getByTestId('active-thresholds')).toHaveAttribute('data-custom', 'true');
    fireEvent.click(screen.getByTestId('active-thresholds-reset'));
    await waitFor(() => {
      expect(screen.getByTestId('active-bar-2')).toHaveAttribute('data-buckets', '1,0,1,0');
    }, FIND);
    expect(screen.getByTestId('active-threshold-1')).toHaveValue(2);
    expect(localStorage.getItem('argos.ui.activeThresholds')).toBeNull();
    expect(readThresholds()).toEqual([1, 3, 8]);
  });

  it('a missing or invalid server default falls back to 1/3/8 s', () => {
    expect(operatorThresholds({ default_thresholds_ms: [500, 1500, 4000] })).toEqual([0.5, 1.5, 4]);
    expect(operatorThresholds({})).toBeNull();
    expect(operatorThresholds({ default_thresholds_ms: [3000, 1000, 8000] })).toBeNull();
    expect(operatorThresholds({ default_thresholds_ms: [0, 1000, 8000] })).toBeNull();
    expect(operatorThresholds({ default_thresholds_ms: [1000, 3000] })).toBeNull();
    expect(operatorThresholds({ default_thresholds_ms: ['1000', 3000, 8000] })).toBeNull();
    expect(operatorThresholds(null)).toBeNull();
  });
});
