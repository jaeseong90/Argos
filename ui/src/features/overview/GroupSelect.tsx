// SPDX-License-Identifier: Apache-2.0
//
// Application group filter of the overview (05 §2.10, docs/15 §9.1 GroupSelect): the distinct
// `group` values of the applications (`GET /apps`), "All groups" first. Controlled: the page maps
// a choice to the URL (`group=`).
//
// A native select (Ant Design stays behind the shared components of src/components, F-U16). The
// page offers only group names the URL can carry (the OverviewQ group pattern: letters, digits,
// `.`, `_`, `-`), so an option label never holds markup, control or bidi characters; React sets it
// as text either way.
import { useId, useMemo } from 'react';
import { useTranslation } from 'react-i18next';

/** Value of the "All groups" option (a group name never starts with NUL). */
const ALL_GROUPS = '\u0000all';

export interface GroupSelectProps {
  readonly groups: readonly string[];
  /** null = all groups. */
  readonly value: string | null;
  readonly onChange: (group: string | null) => void;
  readonly disabled?: boolean;
}

export function GroupSelect({ groups, value, onChange, disabled = false }: GroupSelectProps) {
  const { t } = useTranslation(['common', 'overview']);
  const id = useId();
  const options = useMemo(() => [...new Set(groups)].sort((a, b) => a.localeCompare(b)), [groups]);
  return (
    <span className="overview-group-select">
      <label htmlFor={id} className="visually-hidden">
        {t('overview:group.label')}
      </label>
      <select
        id={id}
        className="overview-group-select__select"
        value={value ?? ALL_GROUPS}
        disabled={disabled}
        title={t('overview:group.label')}
        onChange={(event) => {
          const next = event.target.value;
          onChange(next === ALL_GROUPS ? null : next);
        }}
        data-testid="overview-group-select"
      >
        <option value={ALL_GROUPS}>{t('overview:group.all')}</option>
        {options.length === 0 ? null : (
          <optgroup label={t('overview:group.groups')}>
            {options.map((group) => (
              <option key={group} value={group}>
                {group}
              </option>
            ))}
          </optgroup>
        )}
      </select>
    </span>
  );
}
