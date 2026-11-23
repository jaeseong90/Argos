// SPDX-License-Identifier: Apache-2.0
//
// The instance header (05 §2.7-1, docs/15 §9.8): state (F-S28) with the WARN reasons the header can
// tell, application and environment, application version, agent version with the old/unsupported
// warning, JVM, OS, host, PID, cgroup, start time, uptime, restarts in the selected range, clock
// skew, drops (24 h), modules disabled by the agent, IP capture mode and virtual-thread mode, then
// the four links of 05 §2.7-6. A value the API does not have is the D-23 dash, never 0; every
// collected string renders through SafeText (SEC-60). Each item is `instance-meta-<key>` for tests.
import type { ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { useNavigate } from 'react-router';

import type { Instance, InstanceEvent } from '../../../api/instances';
import { SafeLink } from '../../../components/SafeLink';
import { SafeText } from '../../../components/SafeText';
import { StatusBadge } from '../../../components/StatusBadge';
import { ValueText } from '../../../components/ValueText';
import { useFormat } from '../../../i18n/format';
import { MISSING_DASH } from '../../../utils/missing';
import {
  configHistoryUrl,
  instanceActiveUrl,
  instanceAlertsUrl,
  instanceXLogUrl,
  type CarriedState,
} from '../links';

import {
  clockSkewWarns,
  compatOf,
  ipModeOf,
  restartCount,
  uptimeMs,
  virtualThreadMode,
  warnReasons,
} from './model';

export interface InstanceHeaderProps {
  readonly instance: Instance;
  /** Deploy/start/stop/config events of the selected range (null while loading). */
  readonly events: readonly InstanceEvent[] | null;
  /** True when the server cut the event list (1,000 rows): the restart count is a lower bound. */
  readonly eventsTruncated: boolean;
  readonly now: number;
  readonly current: CarriedState;
}

interface ItemProps {
  readonly id: string;
  readonly label: string;
  readonly value?: string | number | null;
  readonly children: ReactNode;
}

function Item({ id, label, value, children }: ItemProps) {
  return (
    <div
      className="instance-header__item"
      data-testid={`instance-meta-${id}`}
      data-value={value === null || value === undefined ? undefined : String(value)}
      data-missing={value === null ? 'true' : undefined}
    >
      <dt className="instance-header__label">{label}</dt>
      <dd className="instance-header__value">{children}</dd>
    </div>
  );
}

function Missing() {
  const { t } = useTranslation();
  return (
    <span className="value-text value-text--missing" title={t('component.missing.noData')}>
      {MISSING_DASH}
    </span>
  );
}

export function InstanceHeader({
  instance,
  events,
  eventsTruncated,
  now,
  current,
}: InstanceHeaderProps) {
  const { t } = useTranslation(['common', 'instance']);
  const format = useFormat();
  const navigate = useNavigate();
  const go = (path: string): void => {
    void navigate(path);
  };
  const compat = compatOf(instance);
  const ipMode = ipModeOf(instance);
  const vt = virtualThreadMode(instance);
  const uptime = uptimeMs(instance, now);
  const restarts = restartCount(events, instance.instance_id);
  const startedAt = instance.started_at === null ? null : Date.parse(instance.started_at);
  const skewWarn = clockSkewWarns(instance.clock_skew_ms);
  const modules = instance.modules_disabled;
  const appId = instance.app.app_id;
  const id = instance.instance_id;

  const ipLabel =
    ipMode === 'full'
      ? t('instance:header.ipMode.full')
      : ipMode === 'masked'
        ? t('instance:header.ipMode.masked')
        : ipMode === 'off'
          ? t('instance:header.ipMode.off')
          : null;
  const vtLabel =
    vt === 'on'
      ? t('instance:header.virtualThreads.on')
      : vt === 'off'
        ? t('instance:header.virtualThreads.off')
        : null;

  return (
    <header className="instance-header" data-testid="instance-header">
      <div className="instance-header__title-row">
        <h1 id="instance-title" className="instance-header__title">
          <SafeText value={instance.name} data-testid="instance-name" />
        </h1>
        <StatusBadge
          status={instance.state}
          warnReasons={warnReasons(instance)}
          data-testid="instance-meta-state"
        />
        <span
          className={`instance-badge instance-badge--ip-${ipMode ?? 'unknown'}`}
          data-testid="instance-meta-ip-mode"
          data-value={ipMode ?? undefined}
          data-missing={ipMode === null ? 'true' : undefined}
        >
          {t('instance:header.ipMode.label')} {ipLabel === null ? <Missing /> : ipLabel}
        </span>
        <span
          className={`instance-badge instance-badge--vt-${vt ?? 'unknown'}`}
          data-testid="instance-meta-virtual-threads"
          data-value={vt ?? undefined}
          data-missing={vt === null ? 'true' : undefined}
        >
          {t('instance:header.virtualThreads.label')} {vtLabel === null ? <Missing /> : vtLabel}
        </span>
      </div>
      <dl className="instance-header__grid">
        <Item id="app" label={t('instance:header.app')} value={appId}>
          <SafeText value={instance.app.name} />
          <span className="instance-header__env">
            <SafeText value={instance.app.env} data-testid="instance-meta-env" />
          </span>
        </Item>
        <Item id="version" label={t('instance:header.version')}>
          <SafeText value={instance.version} />
        </Item>
        <Item id="agent-version" label={t('instance:header.agentVersion')} value={compat}>
          <SafeText value={instance.agent_version} />
          {compat === 'old' ? (
            <span
              className="instance-warning instance-warning--warn"
              role="note"
              data-testid="instance-compat-warning"
              data-compat="old"
            >
              {t('instance:header.compat.old')}
            </span>
          ) : compat === 'unsupported' ? (
            <span
              className="instance-warning instance-warning--error"
              role="note"
              data-testid="instance-compat-warning"
              data-compat="unsupported"
            >
              {t('instance:header.compat.unsupported')}
            </span>
          ) : null}
        </Item>
        <Item id="jvm" label={t('instance:header.jvm')}>
          {instance.jvm_version === null && instance.jvm_vendor === null ? (
            <Missing />
          ) : (
            <>
              <SafeText value={instance.jvm_version} />{' '}
              <span className="instance-header__muted">
                <SafeText value={instance.jvm_vendor} />
              </span>
            </>
          )}
        </Item>
        <Item id="os" label={t('instance:header.os')}>
          <SafeText value={instance.os} />
        </Item>
        <Item id="host" label={t('instance:header.host')}>
          <SafeText value={instance.host} />
        </Item>
        <Item id="pid" label={t('instance:header.pid')} value={instance.pid}>
          {instance.pid === null ? <Missing /> : String(instance.pid)}
        </Item>
        <Item id="cgroup" label={t('instance:header.cgroup')}>
          <SafeText value={instance.cgroup} />
        </Item>
        <Item
          id="started-at"
          label={t('instance:header.startedAt')}
          value={startedAt !== null && Number.isFinite(startedAt) ? startedAt : null}
        >
          {startedAt === null || !Number.isFinite(startedAt) ? (
            <Missing />
          ) : (
            <time
              dateTime={instance.started_at ?? undefined}
              title={format.dateTimeWithOffset(startedAt)}
            >
              {format.dateTime(startedAt)}
            </time>
          )}
        </Item>
        <Item id="uptime" label={t('instance:header.uptime')} value={uptime}>
          {uptime === null ? <Missing /> : format.duration(uptime)}
        </Item>
        <Item id="restarts" label={t('instance:header.restarts')} value={restarts}>
          {restarts === null ? (
            <Missing />
          ) : (
            <span title={t('instance:header.restartsHint')}>
              {eventsTruncated
                ? t('instance:header.atLeast', { count: restarts })
                : format.number(restarts)}
            </span>
          )}
        </Item>
        <Item id="clock-skew" label={t('common:term.clockSkew')} value={instance.clock_skew_ms}>
          <ValueText value={instance.clock_skew_ms} unit="ms" />
          {skewWarn ? (
            <span
              className="instance-warning instance-warning--warn"
              role="note"
              data-testid="instance-clock-skew-warning"
            >
              {t('instance:header.clockSkewWarn')}
            </span>
          ) : null}
        </Item>
        <Item id="drops" label={t('instance:header.drops')} value={instance.drops_24h}>
          <ValueText value={instance.drops_24h} unit="count" />
        </Item>
        <Item
          id="modules-disabled"
          label={t('instance:header.modulesDisabled')}
          value={modules === null ? null : modules.length}
        >
          {modules === null ? (
            <Missing />
          ) : modules.length === 0 ? (
            t('instance:header.modulesNone')
          ) : (
            <ul className="instance-header__modules" data-testid="instance-modules-disabled">
              {modules.map((name) => (
                <li key={name} className="instance-warning instance-warning--warn">
                  <SafeText value={name} mono />
                </li>
              ))}
            </ul>
          )}
        </Item>
      </dl>
      <nav className="instance-header__links" aria-label={t('instance:links.label')}>
        <SafeLink
          href={instanceXLogUrl(appId, id, current)}
          onNavigate={go}
          data-testid="instance-link-xlog"
        >
          {t('instance:links.xlog')}
        </SafeLink>
        <SafeLink
          href={instanceActiveUrl(appId, id, current)}
          onNavigate={go}
          data-testid="instance-link-active"
        >
          {t('instance:links.active')}
        </SafeLink>
        <SafeLink
          href={instanceAlertsUrl(appId, current)}
          onNavigate={go}
          data-testid="instance-link-alerts"
        >
          {t('instance:links.alerts')}
        </SafeLink>
        <SafeLink
          href={configHistoryUrl(appId, current)}
          onNavigate={go}
          data-testid="instance-link-config"
        >
          {t('instance:links.config')}
        </SafeLink>
      </nav>
    </header>
  );
}
