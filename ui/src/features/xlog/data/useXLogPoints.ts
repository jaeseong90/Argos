// SPDX-License-Identifier: Apache-2.0
//
// Static (not live) XLog data of the page: `GET /apps/{appId}/xlog` for an absolute range, copied
// into the page's XLogBuffer (clear + appendColumns, docs/15 §7.1) with a new local dictionary.
// The response is not kept in the query cache: 100,000 points would sit there twice (JSON arrays
// and TypedArrays) for every range visited (docs/15 §14 tab heap budget). While a new request runs
// the previous points stay on screen. When it arrives, its dictionary and range become state and
// its columns are held once (a ref) until that render commits: the copy into the buffer happens in
// the layout effect of the same commit, after the canvas received the new range and dictionary as
// props and before the next animation frame, so no frame ever draws new points with the old range
// or dictionary (or old points with the new ones). The columns are released right after the copy,
// and the load's timing goes to `onLoaded` (window.__xlogPerf.lastLoad(), perfHook.ts).
import { useEffect, useLayoutEffect, useRef, useState } from 'react';

import type { ResponseMeta } from '../../../api/client';
import type { XLogBuffer } from '../../../xlog/XLogBuffer';

import { appendPointColumns, type XLogPointColumns } from './columns';
import { LocalDictionary } from './dictionary';
import { resourceSizes, type XLogLoadTiming } from './loadTiming';
import { fetchXLogPoints } from './xlogApi';
import { requestKey, type XLogRequestQuery } from './xlogQuery';

export type XLogDataStatus = 'idle' | 'loading' | 'ready' | 'error';

export interface XLogDataState {
  readonly status: XLogDataStatus;
  /** The failure of the last request (status error). */
  readonly error: unknown;
  /** meta of the points on screen (shown/total/sampling, slow_threshold_ms, outages, source). */
  readonly meta: ResponseMeta | null;
  /** Dictionary of the points on screen: a new object for every load. */
  readonly dictionary: LocalDictionary;
  /** Range of the points on screen (epoch ms), null before the first load. */
  readonly range: { readonly from: number; readonly to: number } | null;
  /** A request is running while older points are on screen. */
  readonly refreshing: boolean;
}

export interface UseXLogPointsOptions {
  readonly appId: number;
  /** The request; null = no request (live mode, or filters that can match nothing). */
  readonly query: XLogRequestQuery | null;
  readonly buffer: XLogBuffer;
  /** Changing it repeats the request (retry). */
  readonly attempt?: number;
  /**
   * A load's points are in the buffer (called in the layout effect of the commit that gave the
   * canvas their range and dictionary): its cost; the first full frame after it completes it.
   */
  readonly onLoaded?: (load: XLogLoadTiming) => void;
}

interface Loaded {
  readonly key: string;
  readonly meta: ResponseMeta;
  readonly dictionary: LocalDictionary;
  readonly range: { readonly from: number; readonly to: number };
}

/** A response waiting for the commit of its `target` state (released by the copy). */
interface Pending {
  readonly target: Loaded;
  readonly columns: XLogPointColumns;
  readonly requestedAt: number;
  readonly receivedAt: number;
}

interface Failed {
  readonly key: string;
  readonly error: unknown;
}

const EMPTY_DICTIONARY = new LocalDictionary();

function isAbort(error: unknown): boolean {
  return error instanceof DOMException && error.name === 'AbortError';
}

export function useXLogPoints({
  appId,
  query,
  buffer,
  attempt = 0,
  onLoaded,
}: UseXLogPointsOptions): XLogDataState {
  // What the buffer holds (the last success) and how the last finished request ended.
  const [loaded, setLoaded] = useState<Loaded | null>(null);
  const [failed, setFailed] = useState<Failed | null>(null);
  const pending = useRef<Pending | null>(null);
  const key = query === null ? null : `${requestKey(appId, query)}#${String(attempt)}`;

  useEffect(() => {
    if (key === null || query === null) {
      return undefined;
    }
    const controller = new AbortController();
    const requestedAt = performance.now();
    const range = { from: Date.parse(query.from), to: Date.parse(query.to) };
    fetchXLogPoints(appId, query, controller.signal).then(
      (points) => {
        if (controller.signal.aborted) {
          return;
        }
        const receivedAt = performance.now();
        // The dictionary is new for every load (the buffer is untouched until the commit).
        const dictionary = new LocalDictionary();
        dictionary.nameServices(points.data.dictionary.service);
        dictionary.nameInstances(points.data.dictionary.instance);
        const target: Loaded = { key, meta: points.meta, dictionary, range };
        pending.current = { target, columns: points.data, requestedAt, receivedAt };
        setFailed(null);
        setLoaded(target);
      },
      (error: unknown) => {
        if (controller.signal.aborted || isAbort(error)) {
          return;
        }
        setFailed({ key, error });
      },
    );
    return () => {
      controller.abort();
    };
    // The key is the request (and the retry attempt): asking again exactly when it changes.
  }, [key, query, appId, buffer]);

  // The commit that hands the canvas the new range and dictionary (its layout effect, a child's,
  // has run) copies the points into the buffer before the browser draws the next frame; the
  // response columns are released.
  useLayoutEffect(() => {
    const response = pending.current;
    if (response === null || response.target !== loaded) {
      return;
    }
    pending.current = null;
    buffer.clear();
    const stats = appendPointColumns(buffer, response.target.dictionary, response.columns);
    const convertedAt = performance.now();
    const sizes = resourceSizes(`/api/v1/apps/${String(appId)}/xlog?`, response.requestedAt);
    const meta = response.target.meta;
    onLoaded?.({
      points: stats.appended,
      shown: meta.shown ?? null,
      total: meta.total ?? null,
      sampled: meta.sampling?.applied === true,
      requestedAt: response.requestedAt,
      responseEndAt: sizes?.responseEndAt ?? null,
      receivedAt: response.receivedAt,
      convertedAt,
      txIdParseMs: stats.parseMs,
      convertMs: stats.totalMs,
      encodedBytes: sizes?.encodedBytes ?? null,
      decodedBytes: sizes?.decodedBytes ?? null,
      transferBytes: sizes?.transferBytes ?? null,
      firstFrameAt: null,
    });
  }, [loaded, buffer, appId, onLoaded]);

  if (key === null) {
    return {
      status: 'idle',
      error: null,
      meta: loaded?.meta ?? null,
      dictionary: loaded?.dictionary ?? EMPTY_DICTIONARY,
      range: loaded?.range ?? null,
      refreshing: false,
    };
  }
  const current = loaded?.key === key;
  const error = failed?.key === key ? failed.error : null;
  return {
    status: error !== null ? 'error' : current ? 'ready' : 'loading',
    error,
    meta: loaded?.meta ?? null,
    dictionary: loaded?.dictionary ?? EMPTY_DICTIONARY,
    range: loaded?.range ?? null,
    refreshing: !current && error === null && loaded !== null,
  };
}
