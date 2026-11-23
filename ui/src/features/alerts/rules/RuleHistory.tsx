// SPDX-License-Identifier: Apache-2.0
//
// Revision history of a rule (05 §2.4-4 "변경 이력", docs/14 §4.13 `history[]`: the 20 newest
// versions): version, who changed it and when. The user name (`changed_by_name`, ADR-0010 C-227
// (4) additive field) is entered data and renders as text; without it the user id is shown.
import { useTranslation } from 'react-i18next';

import type { AlertRuleHistoryEntry } from '../../../api/alertRules';
import { SafeText } from '../../../components/SafeText';
import { useFormat } from '../../../i18n/format';

export function RuleHistory({ history }: { readonly history: readonly AlertRuleHistoryEntry[] }) {
  const { t } = useTranslation(['common', 'alerts']);
  const format = useFormat();
  return (
    <section className="alert-rule-section" data-testid="alert-rule-history">
      <h2 className="alert-rule-section__title">{t('alerts:rules.history.title')}</h2>
      {history.length === 0 ? (
        <p className="alert-rule-preview__muted">{t('alerts:rules.history.none')}</p>
      ) : (
        <table className="alert-rule-history">
          <thead>
            <tr>
              <th scope="col">{t('alerts:rules.history.version')}</th>
              <th scope="col">{t('alerts:rules.history.changedBy')}</th>
              <th scope="col">{t('alerts:rules.history.changedAt')}</th>
            </tr>
          </thead>
          <tbody>
            {history.map((entry) => {
              const at = entry.changed_at === null ? Number.NaN : Date.parse(entry.changed_at);
              return (
                <tr
                  key={entry.version}
                  data-testid="alert-rule-history-row"
                  data-version={entry.version}
                >
                  <td>{t('alerts:rules.history.versionValue', { version: entry.version })}</td>
                  <td>
                    <SafeText value={entry.changed_by_name ?? entry.changed_by} />
                  </td>
                  <td>
                    {Number.isFinite(at) ? (
                      <time dateTime={new Date(at).toISOString()}>{format.dateTime(at)}</time>
                    ) : (
                      '—'
                    )}
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      )}
    </section>
  );
}
