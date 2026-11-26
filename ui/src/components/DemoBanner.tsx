// SPDX-License-Identifier: Apache-2.0
//
// Demo mode banner (docs/17 §3.9, O-A01, WO-41): when the server runs the Compose demo profile
// (GET /api/v1/setup/status `demo_mode`, public, so the sign-in and setup pages know too) every
// screen says that plaintext gRPC is enabled and the data is sample traffic. Fixed text from i18n
// only, no collected data; it cannot be closed. Nothing renders while the status loads, when it
// fails, or when demo mode is off.
import { ExperimentOutlined } from '@ant-design/icons';
import { useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';

import { fetchSetupStatus } from '../api/auth';
import { queryKeys } from '../api/queryKeys';

import './demoBanner.css';

/** Demo mode does not change while a server runs; the status is fetched once per page load. */
const STATUS_STALE_MS = 5 * 60_000;

export interface DemoBannerProps {
  /** `shell`: sticky below the top bar of the shell; `page`: a strip on the sign-in page. */
  readonly placement?: 'shell' | 'page';
}

export function DemoBanner({ placement = 'shell' }: DemoBannerProps) {
  const { t } = useTranslation(['common', 'shell']);
  const status = useQuery({
    queryKey: queryKeys.setup.status(),
    queryFn: fetchSetupStatus,
    staleTime: STATUS_STALE_MS,
    retry: false,
  });
  if (status.data?.demo_mode !== true) {
    return null;
  }
  return (
    <div
      className={`demo-banner demo-banner--${placement}`}
      role="status"
      data-testid="demo-banner"
    >
      <span className="demo-banner__icon" aria-hidden="true">
        <ExperimentOutlined />
      </span>
      <span className="demo-banner__text">{t('shell:demo.banner')}</span>
    </div>
  );
}
