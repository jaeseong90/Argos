// SPDX-License-Identifier: Apache-2.0
//
// Translated labels of the rule screens' closed lists (docs/15 §12, T-UI-008): metrics, target
// types, kinds, evaluation units, no-data handling, week days and the field error codes. A metric
// a newer server knows but this UI does not keeps its wire name (no "unknown" for a valid rule).
import { useTranslation } from 'react-i18next';

import { isAlertMetric, type AlertTargetType } from './metricCatalog';
import type { EvaluationUnit, FieldError, NoData, RuleKind, WeekDay } from './ruleForm';

/** Label of a metric wire name. */
export function useMetricLabel(): (metric: string) => string {
  const { t } = useTranslation(['common', 'alerts']);
  return (metric) => (isAlertMetric(metric) ? t(`alerts:rules.metrics.${metric}`) : metric);
}

export function useRuleLabels() {
  const { t } = useTranslation(['common', 'alerts']);
  return {
    targetType: (type: AlertTargetType): string => {
      switch (type) {
        case 'app':
          return t('alerts:rules.targetType.app');
        case 'instance':
          return t('alerts:rules.targetType.instance');
        case 'service':
          return t('alerts:rules.targetType.service');
        case 'ext_host':
          return t('alerts:rules.targetType.extHost');
        case 'server':
          return t('alerts:rules.targetType.server');
      }
    },
    kind: (kind: RuleKind): string =>
      kind === 'HTTP' ? t('alerts:rules.kind.http') : t('alerts:rules.kind.batch'),
    evaluationUnit: (unit: EvaluationUnit): string =>
      unit === 'app'
        ? t('alerts:rules.evaluationUnit.app')
        : t('alerts:rules.evaluationUnit.instance'),
    noData: (mode: NoData): string => {
      switch (mode) {
        case 'ignore':
          return t('alerts:rules.noData.ignore');
        case 'warn':
          return t('alerts:rules.noData.warn');
        case 'critical':
          return t('alerts:rules.noData.critical');
      }
    },
    day: (day: WeekDay): string => {
      switch (day) {
        case 'Mon':
          return t('alerts:rules.days.mon');
        case 'Tue':
          return t('alerts:rules.days.tue');
        case 'Wed':
          return t('alerts:rules.days.wed');
        case 'Thu':
          return t('alerts:rules.days.thu');
        case 'Fri':
          return t('alerts:rules.days.fri');
        case 'Sat':
          return t('alerts:rules.days.sat');
        case 'Sun':
          return t('alerts:rules.days.sun');
      }
    },
    error: (error: FieldError): string => {
      switch (error) {
        case 'required':
          return t('alerts:rules.errors.required');
        case 'length':
          return t('alerts:rules.errors.length');
        case 'duration':
          return t('alerts:rules.errors.duration');
        case 'outOfRange':
          return t('alerts:rules.errors.outOfRange');
        case 'number':
          return t('alerts:rules.errors.number');
        case 'thresholdRequired':
          return t('alerts:rules.errors.thresholdRequired');
        case 'notBeyondWarn':
          return t('alerts:rules.errors.notBeyondWarn');
        case 'ids':
          return t('alerts:rules.errors.ids');
        case 'notForTarget':
          return t('alerts:rules.errors.notForTarget');
        case 'notForMetric':
          return t('alerts:rules.errors.notForMetric');
        case 'url':
          return t('alerts:rules.errors.url');
        case 'slot':
          return t('alerts:rules.errors.slot');
        case 'templateForbidden':
          return t('alerts:rules.errors.templateForbidden');
        case 'invalid':
          return t('alerts:rules.errors.invalid');
      }
    },
  };
}
