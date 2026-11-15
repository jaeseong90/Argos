// SPDX-License-Identifier: Apache-2.0
//
// Environment picker of the top bar (docs/15 §3.2, §6.5, 05 §2.0-6): searchable, recently used
// first, optional "All environments". Environment names are collected strings (SafeText).
// Controlled: the shell maps a choice to the URL (`env=`).
import { Select } from 'antd';
import { useMemo, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';

import { matchesSearch } from './AppSelect';
import { SafeText } from './SafeText';

import './controls.css';

export interface EnvSelectProps {
  readonly envs: readonly string[];
  /** null = all environments (filter screens) or not chosen. */
  readonly value: string | null;
  readonly onChange: (env: string | null) => void;
  /** Recently used environments, most recent first. */
  readonly recentEnvs?: readonly string[];
  /** Offer "All environments" (overview, all-app alerts). Default true. */
  readonly allowAll?: boolean;
  readonly disabled?: boolean;
}

interface EnvOption {
  readonly value: string;
  readonly label: ReactNode;
  readonly search: string;
}

const ALL_ENVS = '\u0000all';

/** Searchable environment picker with recent environments first. */
export function EnvSelect({
  envs,
  value,
  onChange,
  recentEnvs = [],
  allowAll = true,
  disabled = false,
}: EnvSelectProps) {
  const { t } = useTranslation();
  const options = useMemo(() => {
    const unique = [...new Set(envs)];
    const recent = recentEnvs.filter(
      (env, index) => unique.includes(env) && recentEnvs.indexOf(env) === index,
    );
    const others = unique.filter((env) => !recent.includes(env)).sort((a, b) => a.localeCompare(b));
    const toOption = (env: string): EnvOption => ({
      value: env,
      label: <SafeText value={env} />,
      search: env.toLowerCase(),
    });
    const groups: (EnvOption | { label: string; title: string; options: EnvOption[] })[] = [];
    if (allowAll) {
      groups.push({
        value: ALL_ENVS,
        label: t('component.envSelect.all'),
        search: t('component.envSelect.all').toLowerCase(),
      });
    }
    if (recent.length > 0) {
      const label = t('component.envSelect.recent');
      groups.push({ label, title: label, options: recent.map(toOption) });
    }
    if (others.length > 0) {
      const label = t('component.envSelect.environments');
      groups.push({ label, title: label, options: others.map(toOption) });
    }
    return groups;
  }, [envs, recentEnvs, allowAll, t]);

  return (
    <span className="env-select" data-testid="env-select">
      <Select<string>
        className="env-select__select"
        aria-label={t('component.envSelect.label')}
        placeholder={t('component.envSelect.placeholder')}
        value={value ?? (allowAll ? ALL_ENVS : undefined)}
        options={options}
        disabled={disabled}
        showSearch={{
          filterOption: (input, option) =>
            matchesSearch(option as { search?: string } | undefined, input),
        }}
        popupMatchSelectWidth={false}
        onChange={(next) => {
          onChange(next === ALL_ENVS ? null : next);
        }}
      />
    </span>
  );
}
