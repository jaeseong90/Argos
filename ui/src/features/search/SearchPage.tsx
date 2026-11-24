// SPDX-License-Identifier: Apache-2.0
//
// Global search `/search` (docs/15 §9.12, docs/14 §4.11, F-U14, T-ACPT-12, WO-39). The input says
// how it is read while it is typed: digits up to 20 → a transaction id, 32 hex digits → a trace
// id, `user:` and `req:` → a user id or request id over the selected range (at most 24 h), anything
// else → text over services, SQL and instances (at least 3 characters). The query is the URL
// (`q=`, at most 1 KB, and `type=`), so the top bar search (Ctrl+K) and a shared link land here.
// An id lookup with exactly one match opens it at once; otherwise the matches are listed. A user id
// search without matches says so when instances of the environment do not collect user ids.
//
// Match labels and target params are collected strings: labels render as text (SafeText) and a
// target is opened only through the routes searchDestination() knows (SEC-60, SEC-64).
import { useQuery } from '@tanstack/react-query';
import { useEffect, useId, useMemo, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useNavigate } from 'react-router';

import { listApps } from '../../api/apps';
import { errorInfo } from '../../api/errors';
import { getInstance, listInstances } from '../../api/instances';
import {
  instancePath,
  planSearch,
  rangeFits,
  search,
  searchDestination,
  searchErrorKind,
  searchKeys,
  SEARCH_Q_MAX_BYTES,
  SEARCH_TEXT_MIN_CHARS,
  type SearchMatch,
  type SearchRequest,
} from '../../api/search';
import { untrusted } from '../../api/untrusted';
import { DataTable, type DataTableColumn } from '../../components/DataTable';
import { EmptyState } from '../../components/EmptyState';
import { ErrorState } from '../../components/ErrorState';
import { LoadingState } from '../../components/LoadingState';
import { SafeText } from '../../components/SafeText';
import { carriedQuery, fitsFilterValue, SearchQ } from '../../state/urlState';
import { useTimeRange } from '../../state/useTimeRange';
import { useUrlState } from '../../state/useUrlState';

import { kindLabel, matchTypeLabel, searchErrorLabel } from './labels';

import './search.css';

type ScreenState = 'loading' | 'empty' | 'error' | 'ready';

/** Applications whose instances the "not collected" hint looks at (the first by name). */
const MODE_HINT_APPS_MAX = 50;

/**
 * Instances of the environment (or every one) and how many of them do not collect user ids
 * (`user_id_mode` off or unknown), for the hint of an empty user id search (T-ACPT-12).
 */
async function userIdModes(env: string | null): Promise<{ total: number; off: number }> {
  const apps = await listApps(env === null ? {} : { env });
  const lists = await Promise.all(
    apps.data.slice(0, MODE_HINT_APPS_MAX).map((app) => listInstances(app.app_id)),
  );
  let total = 0;
  let off = 0;
  for (const list of lists) {
    for (const instance of list.data) {
      total += 1;
      if (instance.user_id_mode === 'off') {
        off += 1;
      }
    }
  }
  return { total, off };
}

export function SearchPage() {
  const { t } = useTranslation(['common', 'search']);
  const navigate = useNavigate();
  const inputId = useId();
  const { state, set } = useUrlState(SearchQ);
  const time = useTimeRange(SearchQ);
  const urlQ = state.q ?? '';
  const [text, setText] = useState<string>(urlQ);
  const [shownFor, setShownFor] = useState<string>(urlQ);
  if (shownFor !== urlQ) {
    // A new URL query (top bar search, back/forward): show it in the input.
    setShownFor(urlQ);
    setText(urlQ);
  }

  const typing = planSearch(text);
  const plan = useMemo(() => planSearch(urlQ, state.type), [urlQ, state.type]);
  const range = time.queryRange;
  const env = state.env ?? null;
  const fits = rangeFits(plan, range);
  const tooShort = plan.kind === 'text' && Array.from(plan.q).length < SEARCH_TEXT_MIN_CHARS;
  const request: SearchRequest = useMemo(
    () => ({ plan, from: range.from, to: range.to, env }),
    [plan, range.from, range.to, env],
  );
  const enabled = plan.kind !== 'empty' && fits && !tooShort;
  const query = useQuery({
    queryKey: searchKeys.find(request),
    queryFn: ({ signal }) => search(request, signal),
    enabled,
    retry: false,
    staleTime: 30_000,
  });
  const matches = useMemo(() => query.data?.data.matches ?? [], [query.data]);
  const carried = carriedQuery(state);

  // An id lookup with one match opens it (docs/15 §9.12 "ID 조회는 결과 1건이면 즉시 이동").
  const opened = useRef<string | null>(null);
  const [openError, setOpenError] = useState<unknown>(null);
  const openMatch = async (match: SearchMatch, replace: boolean): Promise<void> => {
    const destination = searchDestination(match.target, carried);
    if (destination.kind === 'path') {
      void navigate(destination.path, { replace });
    } else if (destination.kind === 'instance') {
      try {
        const instance = await getInstance(destination.instanceId);
        void navigate(instancePath(instance.data.app.app_id, destination.instanceId, carried), {
          replace,
        });
      } catch (error) {
        setOpenError(error);
      }
    }
  };
  const single = (plan.kind === 'tx' || plan.kind === 'trace') && matches.length === 1;
  const singleKey = single ? JSON.stringify(searchKeys.find(request)) : null;
  // The effect runs once per question; it opens with the current render's values.
  const openRef = useRef(openMatch);
  useEffect(() => {
    openRef.current = openMatch;
  });
  useEffect(() => {
    const match = matches[0];
    if (singleKey === null || match === undefined || opened.current === singleKey) {
      return;
    }
    opened.current = singleKey;
    void openRef.current(match, true);
  }, [singleKey, matches]);

  const emptyUserSearch = plan.kind === 'user' && query.isSuccess && matches.length === 0;
  const modes = useQuery({
    queryKey: ['search', 'user-id-modes', env] as const,
    queryFn: () => userIdModes(env),
    enabled: emptyUserSearch,
    staleTime: 60_000,
    retry: false,
  });

  const submit = (): void => {
    const q = text.trim();
    if (q === '' || !fitsFilterValue(q)) {
      return;
    }
    opened.current = null;
    setOpenError(null);
    set({ q: untrusted(q), type: 'auto' });
  };
  const tooLong = !fitsFilterValue(text.trim());

  const columns: DataTableColumn<SearchMatch>[] = [
    {
      id: 'type',
      header: t('search:column.type'),
      value: (row) => row.type,
      width: 120,
      render: (row) => matchTypeLabel(t, row.type),
    },
    {
      id: 'label',
      header: t('search:column.label'),
      value: (row) => row.label,
      width: 480,
      render: (row) => <SafeText value={row.label} maxLines={2} data-testid="search-label" />,
    },
    {
      id: 'target',
      header: t('search:column.target'),
      value: () => null,
      width: 140,
      hideable: false,
      render: (row) => {
        const destination = searchDestination(row.target, carried);
        if (destination.kind === 'path') {
          return (
            <Link to={destination.path} data-testid="search-open">
              {t('search:open')}
            </Link>
          );
        }
        if (destination.kind === 'instance') {
          return (
            <button
              type="button"
              className="control-button"
              onClick={() => {
                void openMatch(row, false);
              }}
              data-testid="search-open"
            >
              {t('search:open')}
            </button>
          );
        }
        return <span className="search-muted">{t('search:notOpenable')}</span>;
      },
    },
  ];

  let screenState: ScreenState;
  if (enabled && query.isPending) {
    screenState = 'loading';
  } else if (enabled && query.isError) {
    screenState = 'error';
  } else if (!enabled || matches.length === 0) {
    screenState = 'empty';
  } else {
    screenState = 'ready';
  }
  const errorKind = query.isError ? searchErrorKind(query.error) : null;

  return (
    <section
      className="search-page"
      aria-labelledby="search-title"
      aria-busy={screenState === 'loading' || undefined}
      data-testid="search"
      data-state={screenState}
      data-kind={plan.kind}
    >
      <h1 id="search-title" className="search-page__title">
        {t('search:title')}
      </h1>
      {/* A group, not a form (T-UI-005 treats forms as injected markup); Enter submits. */}
      <div className="search-box" role="search">
        <label className="visually-hidden" htmlFor={inputId}>
          {t('search:inputLabel')}
        </label>
        <input
          id={inputId}
          className="search-box__input"
          type="search"
          autoComplete="off"
          spellCheck={false}
          placeholder={t('search:placeholder')}
          value={text}
          aria-invalid={tooLong || undefined}
          onChange={(event) => {
            setText(event.target.value);
          }}
          onKeyDown={(event) => {
            if (event.key === 'Enter') {
              event.preventDefault();
              submit();
            }
          }}
          data-testid="search-input"
        />
        <button
          type="button"
          className="control-button control-button--primary"
          disabled={text.trim() === '' || tooLong}
          onClick={submit}
          data-testid="search-submit"
        >
          {t('search:submit')}
        </button>
        <span
          className="search-kind"
          data-testid="search-kind"
          data-kind={typing.kind}
          aria-live="polite"
        >
          {kindLabel(t, typing.kind)}
        </span>
      </div>
      {tooLong ? (
        <p className="search-error" role="alert" data-testid="search-too-long">
          {t('search:tooLong', { bytes: SEARCH_Q_MAX_BYTES })}
        </p>
      ) : null}
      <p className="search-muted">{t('search:help')}</p>

      {plan.kind === 'empty' ? (
        <EmptyState
          title={t('search:start.title')}
          description={t('search:start.description')}
          data-testid="search-start"
        />
      ) : !fits ? (
        <EmptyState
          title={t('search:rangeRequired.title')}
          description={t('search:rangeRequired.description')}
          data-testid="search-range-required"
        />
      ) : tooShort ? (
        <EmptyState
          title={t('search:tooShort.title')}
          description={t('search:tooShort.description', { count: SEARCH_TEXT_MIN_CHARS })}
          data-testid="search-too-short"
        />
      ) : screenState === 'loading' ? (
        <LoadingState variant="table" data-testid="search-loading" />
      ) : screenState === 'error' ? (
        errorKind === 'other' || errorKind === null ? (
          <ErrorState
            error={errorInfo(query.error)}
            onRetry={() => {
              void query.refetch();
            }}
            data-testid="search-error"
          />
        ) : (
          <p className="search-error" role="alert" data-testid="search-error" data-kind={errorKind}>
            {searchErrorLabel(t, errorKind, errorInfo(query.error).retryAfterSeconds ?? null)}
          </p>
        )
      ) : matches.length === 0 ? (
        <>
          <EmptyState
            title={t('search:empty.title')}
            description={
              plan.needsRange
                ? t('search:empty.descriptionRange')
                : plan.kind === 'text'
                  ? t('search:empty.descriptionText')
                  : t('search:empty.descriptionId')
            }
            data-testid="search-empty"
          />
          {emptyUserSearch && modes.data !== undefined && modes.data.off > 0 ? (
            <p
              className="search-warning"
              role="status"
              data-testid="search-not-collected"
              data-off={String(modes.data.off)}
              data-total={String(modes.data.total)}
            >
              {modes.data.off === modes.data.total
                ? t('search:notCollected.all')
                : t('search:notCollected.some', {
                    off: modes.data.off,
                    total: modes.data.total,
                  })}
            </p>
          ) : null}
        </>
      ) : single ? (
        <LoadingState variant="table" data-testid="search-opening" />
      ) : (
        <DataTable
          tableId="search-results"
          caption={t('search:caption')}
          columns={columns}
          rows={matches}
          rowKey={(row) => `${row.type}:${row.target.route}:${JSON.stringify(row.target.params)}`}
          dataComplete
          density="dense"
          rowAttributes={(row) => ({ 'data-type': row.type, 'data-route': row.target.route })}
        />
      )}
      {openError === null ? null : (
        <ErrorState compact error={errorInfo(openError)} data-testid="search-open-error" />
      )}
    </section>
  );
}
