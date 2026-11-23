// SPDX-License-Identifier: Apache-2.0
//
// Result views of the text commands (05 §2.7-3, docs/10 §12.1, WO-37): the self diagnosis report
// (gauges, modules, effective configuration with its source, counters, other keys) and the
// environment dump (JVM arguments, environment variables, system properties; values masked by the
// agent are shown exactly as sent, `****(len=N)`, and marked as masked). A result that is not the
// expected JSON shows as raw text (CodeBlock). Every collected string goes through SafeText or
// CodeBlock (SEC-60, SEC-61).
import { useId } from 'react';
import { useTranslation } from 'react-i18next';

import { untrusted, type Untrusted } from '../../../api/untrusted';
import { CodeBlock } from '../../../components/CodeBlock';
import { SafeText } from '../../../components/SafeText';
import { useFormat } from '../../../i18n/format';
import { MISSING_DASH } from '../../../utils/missing';

import { hasMaskedPart, isMasked, parseEnvDump } from './envDump';
import { parseSelfDiagnosis, type KeyValue } from './selfDiagnosis';

import './commands.css';

/** A command result's text (GET /commands/{id}/result: `text`). */
export interface ResultText {
  readonly text: Untrusted | null;
}

function KeyValueTable({
  caption,
  rows,
  testId,
  maskAware = false,
}: {
  readonly caption: string;
  readonly rows: readonly KeyValue[];
  readonly testId: string;
  readonly maskAware?: boolean;
}) {
  const { t } = useTranslation(['common', 'commands']);
  if (rows.length === 0) {
    return (
      <p className="cmd-muted" data-testid={`${testId}-empty`}>
        {t('commands:result.noEntries', { section: caption })}
      </p>
    );
  }
  return (
    <table className="cmd-kv" data-testid={testId}>
      <caption className="cmd-kv__caption">
        {t('commands:result.entries', { section: caption, count: rows.length })}
      </caption>
      <thead>
        <tr>
          <th scope="col">{t('commands:result.key')}</th>
          <th scope="col">{t('commands:result.value')}</th>
        </tr>
      </thead>
      <tbody>
        {rows.map((row, index) => {
          const masked = maskAware && (isMasked(row.value) || hasMaskedPart(row.value));
          return (
            <tr key={`${row.key}#${String(index)}`} data-masked={masked ? 'true' : undefined}>
              <th scope="row" className="cmd-kv__key">
                <SafeText value={row.key} mono maxLines={2} />
              </th>
              <td className="cmd-kv__value">
                <SafeText value={row.value} mono maxLines={3} />
                {masked ? (
                  <span
                    className="cmd-badge cmd-badge--muted"
                    title={t('commands:envDump.maskedTitle')}
                  >
                    {t('commands:envDump.masked')}
                  </span>
                ) : null}
              </td>
            </tr>
          );
        })}
      </tbody>
    </table>
  );
}

function RawResult({
  raw,
  testId,
}: {
  readonly raw: { readonly text: Untrusted };
  readonly testId: string;
}) {
  const { t } = useTranslation(['common', 'commands']);
  return (
    <div className="cmd-raw" data-testid={testId}>
      <p className="cmd-muted">{t('commands:result.raw')}</p>
      <CodeBlock value={raw.text} language="text" label={t('commands:result.rawLabel')} />
    </div>
  );
}

/** The self diagnosis report of a result text. */
export function SelfDiagnosisView({ result }: { readonly result: ResultText }) {
  const { t } = useTranslation(['common', 'commands']);
  const format = useFormat();
  const headingId = useId();
  const parsed = parseSelfDiagnosis(result.text);
  if (parsed.kind === 'text') {
    return <RawResult raw={parsed} testId="self-diagnosis-raw" />;
  }
  const { report } = parsed;
  return (
    <div
      className="cmd-report"
      data-testid="self-diagnosis-report"
      data-format={report.format ?? undefined}
    >
      <section aria-labelledby={`${headingId}-gauges`}>
        <h4 id={`${headingId}-gauges`} className="cmd-report__heading">
          {t('commands:selfDiagnosis.gauges')}
        </h4>
        {report.gauges.length === 0 ? (
          <p className="cmd-muted">{t('commands:selfDiagnosis.noGauges')}</p>
        ) : (
          <dl className="cmd-gauges" data-testid="self-diagnosis-gauges">
            {report.gauges.map((gauge) => (
              <div key={gauge.key} className="cmd-gauges__item" data-key={gauge.key}>
                <dt>{t(`commands:selfDiagnosis.gauge.${gauge.key}`)}</dt>
                <dd>{format.number(gauge.value, 3)}</dd>
              </div>
            ))}
          </dl>
        )}
      </section>
      <section aria-labelledby={`${headingId}-modules`}>
        <h4 id={`${headingId}-modules`} className="cmd-report__heading">
          {t('commands:selfDiagnosis.modules')}
        </h4>
        {report.modules.length === 0 ? (
          <p className="cmd-muted">{t('commands:selfDiagnosis.noModules')}</p>
        ) : (
          <table className="cmd-kv" data-testid="self-diagnosis-modules">
            <thead>
              <tr>
                <th scope="col">{t('commands:selfDiagnosis.module')}</th>
                <th scope="col">{t('commands:selfDiagnosis.moduleState')}</th>
                <th scope="col">{t('commands:selfDiagnosis.moduleErrors')}</th>
                <th scope="col">{t('commands:selfDiagnosis.moduleReason')}</th>
              </tr>
            </thead>
            <tbody>
              {report.modules.map((module, index) => (
                <tr key={`${module.name}#${String(index)}`}>
                  <th scope="row">
                    <SafeText value={module.name} mono />
                  </th>
                  <td>
                    <SafeText value={module.state} />
                  </td>
                  <td className="cmd-num">
                    {module.errors === null ? MISSING_DASH : format.number(module.errors)}
                  </td>
                  <td>
                    {module.reason === null ? MISSING_DASH : <SafeText value={module.reason} />}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>
      <section aria-labelledby={`${headingId}-config`}>
        <h4 id={`${headingId}-config`} className="cmd-report__heading">
          {t('commands:selfDiagnosis.effectiveConfig')}
        </h4>
        {report.effectiveConfig.length === 0 ? (
          <p className="cmd-muted">{t('commands:selfDiagnosis.noConfig')}</p>
        ) : (
          <table className="cmd-kv" data-testid="self-diagnosis-config">
            <thead>
              <tr>
                <th scope="col">{t('commands:result.key')}</th>
                <th scope="col">{t('commands:result.value')}</th>
                <th scope="col">{t('commands:selfDiagnosis.source')}</th>
              </tr>
            </thead>
            <tbody>
              {report.effectiveConfig.map((entry, index) => (
                <tr key={`${entry.key}#${String(index)}`}>
                  <th scope="row">
                    <SafeText value={entry.key} mono />
                  </th>
                  <td>
                    <SafeText value={entry.value} mono maxLines={2} />
                  </td>
                  <td>
                    <SafeText value={entry.source} />
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>
      {report.counters.length === 0 ? null : (
        <KeyValueTable
          caption={t('commands:selfDiagnosis.counters')}
          rows={report.counters.map((c) => ({
            key: c.name,
            value: untrusted(format.number(c.value)),
          }))}
          testId="self-diagnosis-counters"
        />
      )}
      {report.other.length === 0 ? null : (
        <KeyValueTable
          caption={t('commands:result.other')}
          rows={report.other}
          testId="self-diagnosis-other"
        />
      )}
    </div>
  );
}

/** The environment dump of a result text (administrators only, SEC-21). */
export function EnvDumpView({ result }: { readonly result: ResultText }) {
  const { t } = useTranslation(['common', 'commands']);
  const headingId = useId();
  const parsed = parseEnvDump(result.text);
  if (parsed.kind === 'text') {
    return <RawResult raw={parsed} testId="env-dump-raw" />;
  }
  const { report } = parsed;
  return (
    <div
      className="cmd-report"
      data-testid="env-dump-report"
      data-truncated={report.truncated ? 'true' : undefined}
    >
      <p className="cmd-muted">{t('commands:envDump.maskNote')}</p>
      {report.truncated ? (
        <p className="cmd-notice" role="note" data-testid="env-dump-truncated">
          {t('commands:envDump.truncated')}
        </p>
      ) : null}
      {report.unavailable.length === 0 ? null : (
        <p className="cmd-notice" role="note" data-testid="env-dump-unavailable">
          {t('commands:envDump.unavailable', {
            sections: report.unavailable
              .map((section) => t(`commands:envDump.section.${section}`))
              .join(', '),
          })}
        </p>
      )}
      <section aria-labelledby={`${headingId}-jvm`}>
        <h4 id={`${headingId}-jvm`} className="cmd-report__heading">
          {t('commands:envDump.jvmArguments')}
        </h4>
        {report.jvmArguments.length === 0 ? (
          <p className="cmd-muted">{t('commands:envDump.noJvmArguments')}</p>
        ) : (
          <ul className="cmd-args" data-testid="env-dump-jvm-arguments">
            {report.jvmArguments.map((arg, index) => {
              const masked = hasMaskedPart(arg);
              return (
                <li key={`${arg}#${String(index)}`} data-masked={masked ? 'true' : undefined}>
                  <SafeText value={arg} mono maxLines={2} />
                  {masked ? (
                    <span
                      className="cmd-badge cmd-badge--muted"
                      title={t('commands:envDump.maskedTitle')}
                    >
                      {t('commands:envDump.masked')}
                    </span>
                  ) : null}
                </li>
              );
            })}
          </ul>
        )}
      </section>
      <KeyValueTable
        caption={t('commands:envDump.env')}
        rows={report.env}
        testId="env-dump-env"
        maskAware
      />
      <KeyValueTable
        caption={t('commands:envDump.systemProperties')}
        rows={report.systemProperties}
        testId="env-dump-system-properties"
        maskAware
      />
      {report.other.length === 0 ? null : (
        <KeyValueTable
          caption={t('commands:result.other')}
          rows={report.other}
          testId="env-dump-other"
          maskAware
        />
      )}
    </div>
  );
}
