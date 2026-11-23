// SPDX-License-Identifier: Apache-2.0
//
// The thread dump viewer (05 §2.7-5, docs/15 §9.8 ThreadDumpViewer, WO-37): threads grouped by
// state with counts (collapsible; collapsed by default for large dumps), identical stacks of a
// state collapsed into one entry ×N, the locks threads wait for with their holder and waiters, the
// deadlock badge (the agent's cycles, or wait-for graph cycles of a text dump), virtual threads
// marked, search over thread names and frames (class and method), the threads tied to a
// transaction with a link to its detail (as of the dump: the transaction may have ended since), the
// download of the dump's text, and a notice when the dump or a stack was cut ("download the full
// file"). Comparing two dumps is a later version (tab slot only).
//
// Every collected string (thread names, states, frames, locks) goes through SafeText or CodeBlock
// (SEC-60, SEC-61); nothing is assembled into markup.
import { useEffect, useId, useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router';

import type { CommandResultView } from '../../../api/commands';
import type { Untrusted } from '../../../api/untrusted';
import { CodeBlock } from '../../../components/CodeBlock';
import { SafeText } from '../../../components/SafeText';
import { useFormat } from '../../../i18n/format';

import {
  buildDumpModel,
  COLLAPSE_THRESHOLD,
  MAX_DUMP_FRAMES,
  STATE_GROUPS,
  type DumpModel,
  type DumpThread,
  type StackGroup,
  type StateGroup,
  type StateSection,
} from './dumpModel';
import { transactionHref } from './links';

import './dumps.css';

/** Stack entries rendered per state section before "Show more". */
export const STACKS_PAGE = 100;
/** Thread names listed per stack entry before "Show all". */
const NAMES_SHOWN = 10;
/** Locks listed in the lock panel. */
const LOCKS_SHOWN = 20;
/** Search typing settles this long before the URL is written. */
const SEARCH_DEBOUNCE_MS = 300;

/** The viewer's search, as the URL holds it. */
export interface DumpSearch {
  readonly q: Untrusted | undefined;
}

export interface ThreadDumpViewerProps {
  readonly appId: number;
  readonly result: Pick<CommandResultView, 'threads' | 'deadlocks' | 'truncated' | 'command_id'>;
  /** The search (URL `q`, typed by the user: rendered only as the input's value). */
  readonly search: DumpSearch;
  readonly onQueryChange: (query: string) => void;
  /** Saves the dump's text; null hides the button. */
  readonly onDownload: (() => void) | null;
  readonly downloading?: boolean;
  readonly downloadError?: string | null;
}

export function ThreadDumpViewer({
  appId,
  result,
  search,
  onQueryChange,
  onDownload,
  downloading = false,
  downloadError = null,
}: ThreadDumpViewerProps) {
  const { t } = useTranslation(['common', 'commands']);
  const format = useFormat();
  const searchId = useId();
  const query: string = search.q ?? '';
  const model = useMemo(() => buildDumpModel(result, query), [result, query]);
  const large = model.total > COLLAPSE_THRESHOLD;
  const searching = query.trim() !== '';
  const [draft, setDraft] = useState(query);
  const [synced, setSynced] = useState(query);
  if (synced !== query) {
    // The URL changed from outside (back button, deep link): show its query.
    setSynced(query);
    setDraft(query);
  }
  useEffect(() => {
    if (draft === query) {
      return undefined;
    }
    const id = setTimeout(() => {
      onQueryChange(draft);
    }, SEARCH_DEBOUNCE_MS);
    return () => {
      clearTimeout(id);
    };
  }, [draft, query, onQueryChange]);

  const shownCount = model.sections.reduce((sum, s) => sum + s.count, 0);

  return (
    <div
      className="dump-viewer"
      data-testid="dump-viewer"
      data-command-id={result.command_id}
      data-total={model.total}
      data-deadlocks={model.deadlocks.length}
      data-deadlock-source={model.deadlockSource}
      data-virtual={model.virtualCount}
      data-truncated={String(model.truncated)}
    >
      <div className="dump-summary" data-testid="dump-summary">
        <strong data-testid="dump-total">
          {t('commands:viewer.total', { count: model.total })}
        </strong>
        <ul className="dump-counts" aria-label={t('commands:viewer.byState')}>
          {STATE_GROUPS.map((group) => (
            <li
              key={group}
              className={`dump-state dump-state--${group}`}
              data-testid={`dump-count-${group}`}
              data-count={model.counts[group]}
            >
              {t(`commands:viewer.state.${group}`)} {format.number(model.counts[group])}
            </li>
          ))}
        </ul>
        {model.virtualCount > 0 ? (
          <span className="cmd-badge cmd-badge--info" data-testid="dump-virtual-count">
            {t('commands:viewer.virtualCount', { count: model.virtualCount })}
          </span>
        ) : null}
        {model.deadlocks.length > 0 ? (
          <span className="cmd-badge cmd-badge--error" data-testid="dump-deadlock-badge">
            {t('commands:viewer.deadlockBadge', { count: model.deadlocks.length })}
          </span>
        ) : null}
        {onDownload === null ? null : (
          <button
            type="button"
            className="control-button"
            onClick={onDownload}
            disabled={downloading}
            data-testid="dump-download"
          >
            {t('commands:viewer.download')}
          </button>
        )}
      </div>
      {downloadError === null ? null : (
        <p
          className="cmd-feedback cmd-feedback--error"
          role="alert"
          data-testid="dump-download-error"
        >
          {downloadError}
        </p>
      )}
      {model.truncated ? (
        <p className="cmd-notice" data-testid="dump-truncated">
          {result.truncated
            ? t('commands:viewer.truncated')
            : t('commands:viewer.depthCut', { count: MAX_DUMP_FRAMES })}
        </p>
      ) : null}

      {model.deadlocks.length === 0 ? null : <DeadlockPanel model={model} />}
      {model.locks.length === 0 ? null : <LockPanel model={model} />}
      {model.txThreads.length === 0 ? null : <TransactionPanel appId={appId} model={model} />}

      <div className="dump-search">
        <label htmlFor={searchId}>{t('commands:viewer.search')}</label>
        <input
          id={searchId}
          type="search"
          className="cmd-input dump-search__input"
          value={draft}
          maxLength={256}
          placeholder={t('commands:viewer.searchPlaceholder')}
          onChange={(event) => {
            setDraft(event.target.value);
          }}
          onKeyDown={(event) => {
            if (event.key === 'Enter') {
              event.preventDefault();
              onQueryChange(draft);
            }
          }}
          data-testid="dump-search"
        />
        {searching ? (
          <span
            className="cmd-muted"
            role="status"
            data-testid="dump-search-count"
            data-count={shownCount}
          >
            {t('commands:viewer.searchCount', { count: shownCount, total: model.total })}
          </span>
        ) : null}
      </div>

      {model.sections.length === 0 ? (
        <p className="cmd-muted" data-testid="dump-no-match">
          {searching ? t('commands:viewer.noMatch') : t('commands:viewer.noThreads')}
        </p>
      ) : (
        model.sections.map((section) => (
          <StateSectionView
            key={`${section.group}|${searching ? 'q' : ''}`}
            appId={appId}
            section={section}
            model={model}
            initiallyOpen={searching || !large}
          />
        ))
      )}
    </div>
  );
}

function threadLabel(thread: DumpThread) {
  return <SafeText value={thread.name} mono />;
}

function DeadlockPanel({ model }: { readonly model: DumpModel }) {
  const { t } = useTranslation(['common', 'commands']);
  const headingId = useId();
  return (
    <section
      className="dump-panel dump-panel--error"
      aria-labelledby={headingId}
      data-testid="dump-deadlocks"
    >
      <h3 id={headingId} className="dump-panel__title">
        {t('commands:viewer.deadlocks', { count: model.deadlocks.length })}
      </h3>
      <p className="cmd-muted">
        {model.deadlockSource === 'agent'
          ? t('commands:viewer.deadlockFromAgent')
          : t('commands:viewer.deadlockFromGraph')}
      </p>
      <ol className="dump-cycles">
        {model.deadlocks.map((cycle, index) => (
          <li key={index} data-testid={`dump-deadlock-${String(index)}`} data-size={cycle.length}>
            <ul className="dump-cycle">
              {cycle.map((thread) => (
                <li key={thread.key} data-thread-key={thread.key}>
                  {threadLabel(thread)}
                  {thread.waitingOn === null ? null : (
                    <>
                      {' '}
                      <span className="cmd-muted">{t('commands:viewer.waitsFor')}</span>{' '}
                      <SafeText value={thread.waitingOn} mono />
                    </>
                  )}
                </li>
              ))}
            </ul>
          </li>
        ))}
      </ol>
    </section>
  );
}

function LockPanel({ model }: { readonly model: DumpModel }) {
  const { t } = useTranslation(['common', 'commands']);
  const headingId = useId();
  const shown = model.locks.slice(0, LOCKS_SHOWN);
  return (
    <section className="dump-panel" aria-labelledby={headingId} data-testid="dump-locks">
      <h3 id={headingId} className="dump-panel__title">
        {t('commands:viewer.locks', { count: model.locks.length })}
      </h3>
      <table className="cmd-kv dump-locks-table">
        <thead>
          <tr>
            <th scope="col">{t('commands:viewer.lock')}</th>
            <th scope="col">{t('commands:viewer.holder')}</th>
            <th scope="col">{t('commands:viewer.waiters')}</th>
          </tr>
        </thead>
        <tbody>
          {shown.map((lock) => (
            <tr
              key={lock.lock}
              data-testid="dump-lock-row"
              data-lock={lock.lock}
              data-waiters={lock.waiters.length}
              data-deadlocked={String(
                lock.holder !== null && model.deadlocked.has(lock.holder.key),
              )}
            >
              <td>
                <SafeText value={lock.label} mono />
              </td>
              <td>
                {lock.holder === null ? (
                  <span className="cmd-muted">{t('commands:viewer.noHolder')}</span>
                ) : (
                  threadLabel(lock.holder)
                )}
              </td>
              <td>
                <span className="dump-waiters">
                  <strong>{lock.waiters.length}</strong>{' '}
                  {lock.waiters.slice(0, 3).map((waiter) => (
                    <span key={waiter.key} className="dump-waiter">
                      {threadLabel(waiter)}
                    </span>
                  ))}
                  {lock.waiters.length > 3 ? (
                    <span className="cmd-muted">
                      {t('commands:viewer.moreNames', { count: lock.waiters.length - 3 })}
                    </span>
                  ) : null}
                </span>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      {model.locks.length > LOCKS_SHOWN ? (
        <p className="cmd-muted">
          {t('commands:viewer.moreLocks', { count: model.locks.length - LOCKS_SHOWN })}
        </p>
      ) : null}
    </section>
  );
}

function TransactionPanel({ appId, model }: { readonly appId: number; readonly model: DumpModel }) {
  const { t } = useTranslation(['common', 'commands']);
  const headingId = useId();
  return (
    <section className="dump-panel" aria-labelledby={headingId} data-testid="dump-transactions">
      <h3 id={headingId} className="dump-panel__title">
        {t('commands:viewer.transactions', { count: model.txThreads.length })}
      </h3>
      <p className="cmd-muted">{t('commands:viewer.snapshotNote')}</p>
      <ul className="dump-tx-list">
        {model.txThreads.slice(0, 50).map((thread) => (
          <li key={thread.key} data-testid="dump-tx-thread" data-tx-id={thread.txId ?? undefined}>
            {threadLabel(thread)}{' '}
            <span className={`dump-state dump-state--${thread.group}`}>
              {t(`commands:viewer.state.${thread.group}`)}
            </span>{' '}
            {thread.txId === null ? null : (
              <Link to={transactionHref(appId, thread.txId)} data-testid="dump-tx-link">
                {t('commands:viewer.openTransactionId', { txId: thread.txId })}
              </Link>
            )}
          </li>
        ))}
      </ul>
    </section>
  );
}

function StateSectionView({
  appId,
  section,
  model,
  initiallyOpen,
}: {
  readonly appId: number;
  readonly section: StateSection;
  readonly model: DumpModel;
  readonly initiallyOpen: boolean;
}) {
  const { t } = useTranslation(['common', 'commands']);
  const [open, setOpen] = useState(initiallyOpen);
  const [limit, setLimit] = useState(STACKS_PAGE);
  const bodyId = useId();
  const shown = section.stacks.slice(0, limit);
  return (
    <section
      className="dump-section"
      data-testid={`dump-section-${section.group}`}
      data-count={section.count}
      data-stacks={section.stacks.length}
      data-open={String(open)}
    >
      <h3 className="dump-section__title">
        <button
          type="button"
          className="dump-section__toggle"
          aria-expanded={open}
          aria-controls={bodyId}
          onClick={() => {
            setOpen(!open);
          }}
          data-testid={`dump-section-toggle-${section.group}`}
        >
          <span className={`dump-state dump-state--${section.group}`}>
            {t(`commands:viewer.state.${section.group}`)}
          </span>{' '}
          {t('commands:viewer.sectionCount', {
            count: section.count,
            stacks: section.stacks.length,
          })}
        </button>
      </h3>
      {open ? (
        <div id={bodyId} className="dump-section__body">
          {shown.map((stack) => (
            <StackEntry key={stack.hash} appId={appId} stack={stack} model={model} />
          ))}
          {section.stacks.length > limit ? (
            <button
              type="button"
              className="control-button"
              onClick={() => {
                setLimit(limit + STACKS_PAGE);
              }}
              data-testid={`dump-section-more-${section.group}`}
            >
              {t('commands:viewer.moreStacks', { count: section.stacks.length - limit })}
            </button>
          ) : null}
        </div>
      ) : null}
    </section>
  );
}

function StackEntry({
  appId,
  stack,
  model,
}: {
  readonly appId: number;
  readonly stack: StackGroup;
  readonly model: DumpModel;
}) {
  const { t } = useTranslation(['common', 'commands']);
  const [allNames, setAllNames] = useState(false);
  const names = allNames ? stack.threads : stack.threads.slice(0, NAMES_SHOWN);
  const virtual = stack.threads.filter((th) => th.virtual).length;
  const deadlocked = stack.threads.filter((th) => model.deadlocked.has(th.key)).length;
  const first = stack.threads[0];
  const cut = first?.depthCut === true;
  return (
    <article
      className="dump-stack-entry"
      data-testid="dump-stack"
      data-hash={stack.hash}
      data-count={stack.threads.length}
      data-virtual={virtual}
      data-deadlocked={deadlocked}
      data-frames={stack.frames.length}
    >
      <div className="dump-stack-entry__head">
        {stack.threads.length > 1 ? (
          <span
            className="cmd-badge"
            data-testid="dump-stack-count"
            title={t('commands:viewer.sameStackTitle')}
          >
            ×{stack.threads.length}
          </span>
        ) : null}
        {virtual > 0 ? (
          <span
            className="cmd-badge cmd-badge--info"
            title={t('commands:viewer.virtualTitle')}
            data-testid="dump-stack-virtual"
          >
            {t('commands:viewer.virtual')}
            {stack.threads.length > 1 ? ` ${String(virtual)}` : ''}
          </span>
        ) : null}
        {deadlocked > 0 ? (
          <span className="cmd-badge cmd-badge--error" data-testid="dump-stack-deadlock">
            {t('commands:viewer.deadlock')}
          </span>
        ) : null}
        {cut ? (
          <span
            className="cmd-badge cmd-badge--warn"
            title={t('commands:viewer.depthCut', { count: MAX_DUMP_FRAMES })}
          >
            {t('commands:viewer.depthCutBadge', { count: MAX_DUMP_FRAMES })}
          </span>
        ) : null}
      </div>
      <ul className="dump-names" aria-label={t('commands:viewer.threads')}>
        {names.map((thread) => (
          <li key={thread.key} data-testid="dump-thread" data-thread-key={thread.key}>
            {threadLabel(thread)}
            {thread.waitingOn === null ? null : (
              <span className="dump-names__wait">
                <span className="cmd-muted">{t('commands:viewer.waitingOn')}</span>{' '}
                <SafeText value={thread.waitingOn} mono />
              </span>
            )}
            {thread.txId === null ? null : (
              <Link
                to={transactionHref(appId, thread.txId)}
                className="dump-names__tx"
                data-testid="dump-thread-tx-link"
              >
                {t('commands:viewer.openTransactionId', { txId: thread.txId })}
              </Link>
            )}
          </li>
        ))}
      </ul>
      {stack.threads.length > NAMES_SHOWN ? (
        <button
          type="button"
          className="cmd-link-button"
          aria-expanded={allNames}
          onClick={() => {
            setAllNames(!allNames);
          }}
          data-testid="dump-stack-names-toggle"
        >
          {allNames
            ? t('commands:viewer.fewerNames')
            : t('commands:viewer.moreNames', { count: stack.threads.length - NAMES_SHOWN })}
        </button>
      ) : null}
      {stack.frames.length === 0 ? (
        <p className="cmd-muted">{t('commands:viewer.noFrames')}</p>
      ) : (
        <CodeBlock
          value={stack.frames.join('\n')}
          language="stack"
          label={t('commands:viewer.frames', { count: stack.frames.length })}
        />
      )}
    </article>
  );
}

/** The state label key of a group (for tests and other screens). */
export function stateLabelKey(group: StateGroup): `commands:viewer.state.${StateGroup}` {
  return `commands:viewer.state.${group}`;
}
