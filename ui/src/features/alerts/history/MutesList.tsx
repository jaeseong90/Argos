// SPDX-License-Identifier: Apache-2.0
//
// Active one-off mutes (docs/14 §4.13 GET /alerts/mutes, operator and above, ADR-0010 C-226 (7))
// with "end now" (DELETE). The target is shown with application and rule names where the screen
// knows them; the reason is entered text (SafeText).
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';

import { deleteAlertMute, type AlertMute } from '../../../api/alerts';
import { errorInfo } from '../../../api/errors';
import { queryKeys } from '../../../api/queryKeys';
import type { Untrusted } from '../../../api/untrusted';
import { ErrorState } from '../../../components/ErrorState';
import { SafeText } from '../../../components/SafeText';
import { useFormat } from '../../../i18n/format';

import { useMutesQuery } from './data';

export interface MutesListProps {
  readonly appNames: ReadonlyMap<number, Untrusted>;
  readonly ruleNames: ReadonlyMap<string, Untrusted>;
}

export function MutesList({ appNames, ruleNames }: MutesListProps) {
  const { t } = useTranslation(['common', 'alerts']);
  const format = useFormat();
  const queryClient = useQueryClient();
  const query = useMutesQuery(true);
  const remove = useMutation({
    mutationFn: (muteId: string) => deleteAlertMute(muteId),
    onSettled: () => {
      void queryClient.invalidateQueries({ queryKey: queryKeys.alerts.mutes() });
    },
  });
  const mutes: readonly AlertMute[] = query.data?.data ?? [];
  return (
    <section
      className="alerts-panel"
      aria-labelledby="alerts-mutes-title"
      data-testid="alerts-mutes"
    >
      <h2 id="alerts-mutes-title" className="alerts-panel__title">
        {t('alerts:mutes.title')}
      </h2>
      {query.isError ? (
        <ErrorState
          compact
          error={errorInfo(query.error)}
          onRetry={() => {
            void query.refetch();
          }}
        />
      ) : query.isPending ? (
        <p className="alerts-muted">{t('alerts:mutes.loading')}</p>
      ) : mutes.length === 0 ? (
        <p className="alerts-muted" data-testid="alerts-mutes-empty">
          {t('alerts:mutes.empty')}
        </p>
      ) : (
        <ul className="alerts-mutes__list">
          {mutes.map((mute) => {
            const appName = mute.target.app === null ? undefined : appNames.get(mute.target.app);
            const ruleName =
              mute.target.rule === null ? undefined : ruleNames.get(mute.target.rule);
            return (
              <li
                key={mute.mute_id}
                className="alerts-mutes__row"
                data-testid="alerts-mute-row"
                data-mute-id={mute.mute_id}
              >
                <span className="alerts-mutes__target">
                  <span className="alerts-muted">{t('alerts:mutes.target.app')}</span>
                  {appName === undefined ? (
                    <span>{mute.target.app ?? t('alerts:mutes.target.anyApp')}</span>
                  ) : (
                    <SafeText value={appName} />
                  )}
                  {mute.target.env === null ? null : (
                    <span className="alerts-env">
                      <SafeText value={mute.target.env} />
                    </span>
                  )}
                  {mute.target.instance === null ? null : (
                    <span>{t('alerts:mutes.instanceId', { id: mute.target.instance })}</span>
                  )}
                  <span className="alerts-muted">{t('alerts:mutes.target.rule')}</span>
                  {ruleName === undefined ? (
                    <span>
                      {mute.target.rule === null
                        ? t('alerts:mutes.target.anyRule')
                        : mute.target.rule}
                    </span>
                  ) : (
                    <SafeText value={ruleName} />
                  )}
                </span>
                <span className="alerts-mutes__until">
                  {t('alerts:mutes.until', { time: format.dateTime(Date.parse(mute.to)) })}
                </span>
                {mute.reason === null ? null : (
                  <span className="alerts-note">
                    <SafeText value={mute.reason} />
                  </span>
                )}
                <button
                  type="button"
                  className="control-button"
                  disabled={remove.isPending}
                  onClick={() => {
                    remove.mutate(mute.mute_id);
                  }}
                  data-testid="alerts-mute-delete"
                >
                  {t('alerts:mutes.delete')}
                </button>
              </li>
            );
          })}
        </ul>
      )}
      {remove.isError ? (
        <ErrorState
          compact
          error={errorInfo(remove.error)}
          data-testid="alerts-mute-delete-error"
        />
      ) : null}
    </section>
  );
}
