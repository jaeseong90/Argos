// SPDX-License-Identifier: Apache-2.0
//
// Collection server panel of the overview (05 §2.10 "수집 서버 자기 상태(적재 지연·디스크·채널
// 실패·세션 수·인증서 만료)", docs/15 §9.1): the five `server.*` values of `GET /overview`. A value
// the server does not measure yet is null (disk and certificate expiry until WO-28) and reads
// "Not available", never 0 (T-UI-007). Each value carries the raw
// API value in `data-value` ("null" when missing). Channel failures are the final failed
// notification deliveries of the last 24 hours (`server.channel_failures_24h`, WO-25).
import { useTranslation } from 'react-i18next';

import type { ServerHealth } from '../../api/overview';
import { ValueText } from '../../components/ValueText';

import { rawValue } from './cells';
import { finite } from './model';

/** Item ids (testid suffixes `overview-server-<id>`) in the 05 §2.10 order. */
export const SERVER_ITEM_IDS = [
  'ingest-lag',
  'disk',
  'channel-failures',
  'sessions',
  'cert-expiry',
] as const;
export type ServerItemId = (typeof SERVER_ITEM_IDS)[number];

/** Ingest lag above N-06's 5 s, disk above 85 %, any channel failure, a certificate within 30 days. */
export function serverItemWarn(id: ServerItemId, value: number | null): boolean {
  if (value === null) {
    return false;
  }
  switch (id) {
    case 'ingest-lag':
      return value > 5;
    case 'disk':
      return value > 0.85;
    case 'channel-failures':
      return value > 0;
    case 'sessions':
      return false;
    case 'cert-expiry':
      return value < 30;
  }
}

function valueOf(server: ServerHealth, id: ServerItemId): number | null {
  switch (id) {
    case 'ingest-lag':
      return finite(server.ingest_lag_s);
    case 'disk':
      return finite(server.disk_usage_ratio);
    case 'channel-failures':
      return finite(server.channel_failures_24h);
    case 'sessions':
      return finite(server.sessions);
    case 'cert-expiry':
      return finite(server.cert_expiry_days);
  }
}

export function ServerHealthPanel({ server }: { readonly server: ServerHealth }) {
  const { t } = useTranslation(['common', 'overview']);
  const label = (id: ServerItemId): string => {
    switch (id) {
      case 'ingest-lag':
        return t('overview:panel.server.ingestLag');
      case 'disk':
        return t('overview:panel.server.disk');
      case 'channel-failures':
        return t('overview:panel.server.channelFailures');
      case 'sessions':
        return t('overview:panel.server.sessions');
      case 'cert-expiry':
        return t('overview:panel.server.certExpiry');
    }
  };
  const render = (id: ServerItemId, value: number) => {
    switch (id) {
      case 'ingest-lag':
        return <ValueText value={value} unit="seconds" />;
      case 'disk':
        return <ValueText value={value} unit="ratio" />;
      case 'cert-expiry':
        return t('overview:panel.server.days', { count: value });
      case 'channel-failures':
      case 'sessions':
        return <ValueText value={value} unit="count" />;
    }
  };
  return (
    <section
      className="overview-panel overview-server-panel"
      aria-labelledby="overview-server-title"
      data-testid="overview-server-panel"
    >
      <h2 id="overview-server-title" className="overview-panel__title">
        {t('overview:panel.server.title')}
      </h2>
      <dl className="overview-server-panel__items">
        {SERVER_ITEM_IDS.map((id) => {
          const value = valueOf(server, id);
          const warn = serverItemWarn(id, value);
          return (
            <div key={id} className="overview-server-panel__item">
              <dt>{label(id)}</dt>
              <dd
                className={`overview-server-panel__value${warn ? ' overview-server-panel__value--warn' : ''}${value === null ? ' overview-server-panel__value--missing' : ''}`}
                title={value === null ? t('overview:panel.server.notAvailableTitle') : undefined}
                data-testid={`overview-server-${id}`}
                data-value={rawValue(value)}
                data-warn={warn ? 'true' : undefined}
              >
                {value === null ? t('overview:panel.server.notAvailable') : render(id, value)}
              </dd>
            </div>
          );
        })}
      </dl>
    </section>
  );
}
