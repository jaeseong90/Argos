// SPDX-License-Identifier: Apache-2.0
import { useTranslation } from 'react-i18next';

import type { ColorMapping } from './renderer';
import { useXLogFormat } from './useXLogFormat';
import type { XLogDictionary } from './XLogBuffer';

export interface XLogLegendProps {
  readonly mapping: ColorMapping;
  readonly dictionary: XLogDictionary;
}

/**
 * Colour legend. Every colour is named, so the colour is never the only cue (docs/15 §13); names
 * from the dictionaries are rendered as text only (SEC-60).
 */
export function XLogLegend({ mapping, dictionary }: XLogLegendProps) {
  const { t } = useTranslation(['common', 'xlog']);
  const format = useXLogFormat();
  const names = mapping.colorBy === 'instance' ? dictionary.instances : dictionary.services;
  return (
    <ul className="xlog-legend" data-testid="xlog-legend" aria-label={t('xlog:legend.label')}>
      {mapping.legend.map((entry) => {
        let label: string;
        if (entry.kind === 'status') {
          label = format.status(entry.value);
        } else if (entry.kind === 'key') {
          label = format.name(names[entry.value] ?? null, entry.value);
        } else {
          label = t('xlog:legend.other');
        }
        return (
          <li key={`${entry.kind}-${String(entry.value)}`} className="xlog-legend__item">
            <span
              className="xlog-legend__swatch"
              style={{ backgroundColor: entry.color }}
              aria-hidden="true"
            />
            <span className="xlog-legend__label">{label}</span>
          </li>
        );
      })}
    </ul>
  );
}
