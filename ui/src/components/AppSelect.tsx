// SPDX-License-Identifier: Apache-2.0
//
// Application picker of the top bar (docs/15 §3.2, §6.5, 05 §2.0-6): searchable,
// recently used first, grouped by environment. App and environment names are collected
// strings and render through SafeText. Controlled: the shell maps a choice to the URL.
import { Select } from 'antd';
import { useMemo, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';

import { SafeText } from './SafeText';

import './controls.css';

/** The app fields the picker needs (docs/14 App / AppRef). */
export interface AppOption {
  readonly app_id: number;
  readonly name: string;
  readonly env: string;
}

interface SearchableOption {
  readonly value: number;
  readonly label: ReactNode;
  readonly search: string;
  readonly 'data-testid'?: string;
}

interface OptionGroup {
  readonly label: ReactNode;
  readonly title: string;
  readonly options: SearchableOption[];
}

/** Case-insensitive search over an option's search text (name and environment). */
export function matchesSearch(option: { search?: string } | undefined, input: string): boolean {
  return (option?.search ?? '').includes(input.trim().toLowerCase());
}

/** Builds the option groups: recent apps first, then the others grouped by environment. */
export function buildAppGroups(
  apps: readonly AppOption[],
  recentAppIds: readonly number[],
): { readonly recent: AppOption[]; readonly byEnv: [string, AppOption[]][] } {
  const byId = new Map(apps.map((app) => [app.app_id, app]));
  const recent: AppOption[] = [];
  for (const id of recentAppIds) {
    const app = byId.get(id);
    if (app !== undefined && !recent.includes(app)) {
      recent.push(app);
    }
  }
  const groups = new Map<string, AppOption[]>();
  for (const app of apps) {
    if (recent.includes(app)) {
      continue;
    }
    const list = groups.get(app.env) ?? [];
    list.push(app);
    groups.set(app.env, list);
  }
  const byEnv = [...groups.entries()]
    .map(([env, list]): [string, AppOption[]] => [
      env,
      [...list].sort((a, b) => a.name.localeCompare(b.name)),
    ])
    .sort(([a], [b]) => a.localeCompare(b));
  return { recent, byEnv };
}

export interface AppSelectProps {
  readonly apps: readonly AppOption[];
  readonly value: number | null;
  readonly onChange: (appId: number) => void;
  /** Recently used app ids, most recent first (kept by the shell). */
  readonly recentAppIds?: readonly number[];
  readonly disabled?: boolean;
  readonly loading?: boolean;
}

function appLabel(app: AppOption) {
  return (
    <span className="app-select__option">
      <SafeText value={app.name} />
      <span className="app-select__env">
        <SafeText value={app.env} />
      </span>
    </span>
  );
}

/** Searchable app picker, grouped by environment with recent apps first. */
export function AppSelect({
  apps,
  value,
  onChange,
  recentAppIds = [],
  disabled = false,
  loading = false,
}: AppSelectProps) {
  const { t } = useTranslation();
  const recentLabel = t('component.appSelect.recent');
  const options = useMemo(() => {
    const { recent, byEnv } = buildAppGroups(apps, recentAppIds);
    const toOption = (app: AppOption): SearchableOption => ({
      value: app.app_id,
      label: appLabel(app),
      search: `${app.name}\u0000${app.env}`.toLowerCase(),
      'data-testid': `app-select-option-${String(app.app_id)}`,
    });
    const groups: OptionGroup[] = [];
    if (recent.length > 0) {
      groups.push({ label: recentLabel, title: recentLabel, options: recent.map(toOption) });
    }
    for (const [env, list] of byEnv) {
      groups.push({ label: <SafeText value={env} />, title: env, options: list.map(toOption) });
    }
    return groups;
  }, [apps, recentAppIds, recentLabel]);

  return (
    <span className="app-select" data-testid="app-select">
      <Select<number, SearchableOption | OptionGroup>
        className="app-select__select"
        aria-label={t('component.appSelect.label')}
        placeholder={t('component.appSelect.placeholder')}
        value={value ?? undefined}
        options={options}
        disabled={disabled}
        loading={loading}
        showSearch={{
          filterOption: (input, option) =>
            matchesSearch(option as { search?: string } | undefined, input),
        }}
        notFoundContent={t('component.appSelect.empty')}
        popupMatchSelectWidth={false}
        onChange={(next) => {
          onChange(next);
        }}
      />
    </span>
  );
}
