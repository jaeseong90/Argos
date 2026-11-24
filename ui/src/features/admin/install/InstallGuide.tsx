// SPDX-License-Identifier: Apache-2.0
//
// Agent install guide drawer (05 §2.11-7, docs/15 §9.10 "설치 안내", WO-39 §1.4 `admin-install`):
// copyable -javaagent options, argos-agent.yml (application name, environment and collector
// address filled in, the token as a file path placeholder: no token is ever shown here), the
// `--check` command, where the compatibility table is, and the canary checklist (one instance
// first, self-diagnosis clean, compare, then the rest). The checklist state is this component's
// only; nothing is stored. Application names are entered strings: text only (SafeText, CodeBlock).
import { useEffect, useId, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router';

import type { App } from '../../../api/apps';
import { CodeBlock } from '../../../components/CodeBlock';
import { SafeText } from '../../../components/SafeText';

import {
  agentYaml,
  checkCommand,
  collectorAddress,
  javaagentLine,
  javaagentOptions,
  TOKEN_FILE,
} from './javaagent';

import '../common/admin.css';

type SnippetKind = 'javaagent' | 'yml' | 'check';

const CANARY_STEPS = ['one', 'selfDiag', 'compare', 'rollout'] as const;

export interface InstallGuideProps {
  readonly app: Pick<App, 'app_id' | 'name' | 'env'>;
  /** argos.collector.public-address of the server when known (a token issue answer), else null. */
  readonly publicAddress?: string | null;
  /** Shows the link to the token screen (administrators). */
  readonly canIssueTokens?: boolean;
  readonly onClose: () => void;
}

export function InstallGuide({
  app,
  publicAddress = null,
  canIssueTokens = false,
  onClose,
}: InstallGuideProps) {
  const { t } = useTranslation(['common', 'admin']);
  const titleId = useId();
  const panelRef = useRef<HTMLDivElement>(null);
  const [copied, setCopied] = useState<{ kind: SnippetKind; ok: boolean } | null>(null);
  const [checked, setChecked] = useState<ReadonlySet<string>>(new Set());
  const onCloseRef = useRef(onClose);
  useEffect(() => {
    onCloseRef.current = onClose;
  }, [onClose]);

  useEffect(() => {
    const opener = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    panelRef.current?.focus();
    const onKeyDown = (event: KeyboardEvent): void => {
      if (event.key === 'Escape' && !event.defaultPrevented) {
        event.preventDefault();
        onCloseRef.current();
      }
    };
    document.addEventListener('keydown', onKeyDown);
    return () => {
      document.removeEventListener('keydown', onKeyDown);
      if (opener !== null && opener.isConnected) {
        opener.focus();
      }
    };
  }, []);

  const name = String(app.name);
  const env = String(app.env);
  const snippets: Record<SnippetKind, string> = {
    javaagent: javaagentOptions(name, env, undefined, publicAddress),
    yml: agentYaml(name, env, undefined, publicAddress),
    check: checkCommand(),
  };

  const copy = (kind: SnippetKind): void => {
    const clipboard = typeof navigator === 'undefined' ? undefined : navigator.clipboard;
    if (clipboard === undefined) {
      setCopied({ kind, ok: false });
      return;
    }
    clipboard.writeText(snippets[kind]).then(
      () => {
        setCopied({ kind, ok: true });
      },
      () => {
        setCopied({ kind, ok: false });
      },
    );
  };

  const snippet = (kind: SnippetKind, label: string, help: string) => (
    <section className="admin-panel" aria-label={label}>
      <h3 className="admin-panel__title">{label}</h3>
      <p className="admin-note">{help}</p>
      <CodeBlock
        value={snippets[kind]}
        label={label}
        foldLines={0}
        copyable={false}
        data-testid={`admin-install-${kind}`}
      />
      <div className="admin-toolbar">
        <button
          type="button"
          className="control-button"
          onClick={() => {
            copy(kind);
          }}
          data-testid={`admin-install-copy-${kind}`}
        >
          {t('admin:install.copy')}
        </button>
        <span className="admin-muted" role="status">
          {copied?.kind === kind
            ? copied.ok
              ? t('admin:install.copied')
              : t('admin:install.copyFailed')
            : null}
        </span>
      </div>
    </section>
  );

  return (
    <div className="admin-drawer__backdrop">
      <div
        ref={panelRef}
        className="admin-drawer"
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        tabIndex={-1}
        data-testid="admin-install"
        data-app-id={String(app.app_id)}
        data-collector-address={collectorAddress(undefined, publicAddress)}
      >
        <div className="admin-drawer__header">
          <h2 id={titleId} className="admin-drawer__title">
            {t('admin:install.title')} <SafeText value={app.name} /> · <SafeText value={app.env} />
          </h2>
          <button
            type="button"
            className="control-button"
            onClick={onClose}
            data-testid="admin-install-close"
          >
            {t('admin:install.close')}
          </button>
        </div>
        <section className="admin-panel">
          <h3 className="admin-panel__title">{t('admin:install.tokenTitle')}</h3>
          <p className="admin-note" data-testid="admin-install-token">
            {t('admin:install.token', { file: TOKEN_FILE })}
          </p>
          {canIssueTokens ? (
            <Link
              to={`/admin/tokens?app=${String(app.app_id)}`}
              data-testid="admin-install-tokens-link"
            >
              {t('admin:install.tokensLink')}
            </Link>
          ) : null}
          {publicAddress === null || publicAddress.trim() === '' ? (
            <p className="admin-note" data-testid="admin-install-address-note">
              {t('admin:install.addressNote')}
            </p>
          ) : null}
        </section>
        {snippet('javaagent', t('admin:install.javaagentTitle'), t('admin:install.javaagentHelp'))}
        {snippet(
          'yml',
          t('admin:install.ymlTitle'),
          t('admin:install.ymlHelp', { line: javaagentLine() }),
        )}
        {snippet('check', t('admin:install.checkTitle'), t('admin:install.checkHelp'))}
        <section className="admin-panel">
          <h3 className="admin-panel__title">{t('admin:install.compatTitle')}</h3>
          <p className="admin-note" data-testid="admin-install-compat">
            {t('admin:install.compat')}
          </p>
        </section>
        <section className="admin-panel" data-testid="admin-install-canary">
          <h3 className="admin-panel__title">{t('admin:install.canaryTitle')}</h3>
          <ol className="admin-checklist">
            {CANARY_STEPS.map((step) => (
              <li key={step} data-testid="admin-install-canary-item" data-step={step}>
                <label>
                  <input
                    type="checkbox"
                    checked={checked.has(step)}
                    onChange={(event) => {
                      const next = new Set(checked);
                      if (event.target.checked) {
                        next.add(step);
                      } else {
                        next.delete(step);
                      }
                      setChecked(next);
                    }}
                  />
                  <span>{t(`admin:install.canary.${step}`)}</span>
                </label>
              </li>
            ))}
          </ol>
        </section>
      </div>
    </div>
  );
}
