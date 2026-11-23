// SPDX-License-Identifier: Apache-2.0
//
// "Create from template" panel (05 §2.4-3, docs/15 §9.9 drawer, docs/14 §4.13 GET /alerts/templates;
// the alert screens' dialog, Ant Design stays inside src/components):
// the 14 default templates of 01 appendix A with what they watch; choosing one fills the editor
// form with its rule. A template of two metrics (two parts, ADR-0010 C-226 (6)) asks which part.
// Template names and defaults come from the server's built-in list.
import { useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';

import {
  alertRuleKeys,
  listAlertTemplates,
  type AlertTemplate,
  type AlertTemplatePart,
} from '../../../api/alertRules';
import { errorInfo } from '../../../api/errors';
import { useAlertValueFormat } from '../../../components/AlertValue';
import { ErrorState } from '../../../components/ErrorState';
import { LoadingState } from '../../../components/LoadingState';
import { SafeText } from '../../../components/SafeText';
import { AlertDialog } from '../common/AlertDialog';

import { useMetricLabel } from './labels';
import { metricInfo } from './metricCatalog';

export interface TemplateDrawerProps {
  readonly open: boolean;
  readonly onClose: () => void;
  readonly onChoose: (template: AlertTemplate, part: AlertTemplatePart, index: number) => void;
}

export function TemplateDrawer({ open, onClose, onChoose }: TemplateDrawerProps) {
  const { t } = useTranslation(['common', 'alerts']);
  const metricLabel = useMetricLabel();
  const formatValue = useAlertValueFormat();
  const templates = useQuery({
    queryKey: alertRuleKeys.templates(),
    queryFn: ({ signal }) => listAlertTemplates(signal),
    staleTime: 5 * 60_000,
    enabled: open,
  });

  const partSummary = (part: AlertTemplatePart): string => {
    const rule = part.rule;
    const unit = metricInfo(rule.metric)?.unit ?? null;
    const threshold = rule.condition.warn ?? rule.condition.critical;
    return t('alerts:rules.templates.partSummary', {
      metric: metricLabel(rule.metric),
      operator: rule.condition.operator,
      threshold: formatValue(threshold, unit),
      for: rule.for,
    });
  };

  return (
    <AlertDialog
      open={open}
      onClose={onClose}
      title={t('alerts:rules.templates.title')}
      data-testid="alert-rule-template-drawer"
      footer={
        <button type="button" className="control-button" onClick={onClose}>
          {t('alerts:rules.actions.cancel')}
        </button>
      }
    >
      <div className="alert-rule-template-list">
        <p className="alert-rule-preview__muted">{t('alerts:rules.templates.description')}</p>
        {templates.isPending ? (
          <LoadingState variant="table" rows={6} />
        ) : templates.isError ? (
          <ErrorState
            compact
            error={errorInfo(templates.error)}
            onRetry={() => {
              void templates.refetch();
            }}
          />
        ) : (
          <ul className="alert-rule-templates">
            {templates.data.map((template) => {
              const parts = template.defaults;
              return (
                <li
                  key={template.key}
                  className="alert-rule-templates__item"
                  data-testid={`alert-rule-template-${template.key}`}
                  data-parts={parts.length}
                >
                  <div className="alert-rule-templates__head">
                    <SafeText value={template.name} className="alert-rule-templates__name" />
                    <span className="alert-rule-preview__muted">
                      {t('alerts:rules.templates.version', { version: template.version })}
                    </span>
                  </div>
                  <div className="alert-rule-templates__parts">
                    {parts.map((part, index) => (
                      <button
                        key={`${template.key}-${String(index)}`}
                        type="button"
                        className="control-button"
                        data-testid={
                          parts.length === 1
                            ? `alert-rule-template-${template.key}-choose`
                            : `alert-rule-template-${template.key}-part-${String(index)}`
                        }
                        onClick={() => {
                          onChoose(template, part, index);
                        }}
                      >
                        {parts.length === 1
                          ? t('alerts:rules.templates.use')
                          : t('alerts:rules.templates.usePart', { part: index + 1 })}
                        <span className="alert-rule-templates__summary">{partSummary(part)}</span>
                      </button>
                    ))}
                  </div>
                </li>
              );
            })}
          </ul>
        )}
      </div>
    </AlertDialog>
  );
}
