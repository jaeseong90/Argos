// SPDX-License-Identifier: Apache-2.0
//
// Server CSV export (docs/15 §6 "CSV", §11 "CSV", DSN-15-06, docs/14 §4.17, 03 §3.3): the UI never
// builds a CSV. A screen's CSV button (DataTable `onExportCsv`) calls exportCsv() with an operation
// of the generated contract that answers `text/csv` (x-argos-csv); the request goes through the
// shared transport (cookies, X-Argos-CSRF on POST, one shared refresh on 401) with `format=csv`
// and `Accept: text/csv`, and the answer is saved as a file under the name the server gives
// (Content-Disposition, sanitised).
//
// The server audits the export (SEC-80), guards against formula injection (SEC-63), caps it at
// 10,000 rows (400 limit_exceeded) and rate-limits it as HEAVY (429 + Retry-After, C-176). A 429
// is thrown as an ApiError with `retryAfterSeconds`, which the screen shows as a retry hint; there
// is no automatic retry.
//
// saveFile() is the one approved place that turns data into a download link (an object URL, an
// <a download> and revokeObjectURL): the lint rule argos/no-object-url forbids object URLs
// everywhere else in src/ (exception for this file in eslint-allow.json). It also saves the two
// JSON downloads of the detail screens (WO-34): the transaction export's server bytes and the
// received trace response (ADR-0010 C-207 (1)), under a sanitised `.json` name.
import { api } from './client';
import { ApiError, networkError, toApiError } from './errors';
import type { paths } from './generated/schema';

/** HTTP methods of the CSV operations in the contract. */
export type CsvMethod = 'get' | 'post';

type IsCsvOperation<Op> = [Op] extends [never]
  ? false
  : [Op] extends [{ responses: { 200: { content: { 'text/csv': unknown } } } }]
    ? true
    : false;

/** Paths of the contract whose `M` operation can answer `text/csv` (x-argos-csv, docs/14 §4.17). */
export type CsvPath<M extends CsvMethod> = {
  [P in keyof paths]: M extends keyof paths[P]
    ? IsCsvOperation<paths[P][M]> extends true
      ? P
      : never
    : never;
}[keyof paths];

type OperationOf<P extends keyof paths, M extends CsvMethod> = M extends keyof paths[P]
  ? NonNullable<paths[P][M]>
  : never;
type ParametersOf<Op> = Op extends { parameters: infer X } ? X : never;
type PathParamsOf<Op> = ParametersOf<Op> extends { path: infer X } ? X : never;
/** The operation's query without `format` (always `csv` here). */
type QueryOf<Op> =
  ParametersOf<Op> extends { query?: infer Q } ? Omit<NonNullable<Q>, 'format'> : never;
type JsonBodyOf<Op> = Op extends { requestBody?: infer B }
  ? NonNullable<B> extends { content: { 'application/json': infer J } }
    ? J
    : never
  : never;

type QueryPart<Q> = object extends Q ? { readonly query?: Q } : { readonly query: Q };
type BodyPart<M extends CsvMethod, Op> = M extends 'post'
  ? { readonly body: JsonBodyOf<Op> }
  : { readonly body?: never };

/**
 * Parameters of a CSV export: the path parameters, the query of the JSON form without `format`
 * (`tz` defaults to the browser time zone, docs/15 §12) and, for POST, the JSON body.
 */
export type CsvRequest<P extends keyof paths, M extends CsvMethod> = {
  readonly path: PathParamsOf<OperationOf<P, M>>;
} & QueryPart<QueryOf<OperationOf<P, M>>> &
  BodyPart<M, OperationOf<P, M>>;

/** A downloaded export. */
export interface CsvFile {
  readonly blob: Blob;
  /** Sanitised file name from Content-Disposition, or DEFAULT_CSV_FILE_NAME. */
  readonly fileName: string;
}

/** File name when the server sends none or one that is empty after sanitising. */
export const DEFAULT_CSV_FILE_NAME = 'argos-export.csv';
/**
 * File name of a JSON download without a usable name (the transaction export of docs/14 §4.6 and
 * the trace JSON of ADR-0010 C-207 (1)).
 */
export const DEFAULT_JSON_FILE_NAME = 'argos-export.json';
/** Longest file name kept (the server's names are about 60 characters). */
export const MAX_CSV_FILE_NAME_LENGTH = 128;

/** The extension a saved file always ends in and the name used when nothing usable is left. */
interface FileKind {
  readonly extension: '.csv' | '.json';
  readonly fallback: string;
}

const CSV_FILE: FileKind = { extension: '.csv', fallback: DEFAULT_CSV_FILE_NAME };
const JSON_FILE: FileKind = { extension: '.json', fallback: DEFAULT_JSON_FILE_NAME };
/** The object URL lives this long after the click, so that the browser can start the download. */
export const REVOKE_DELAY_MS = 10_000;

/** Characters that are path separators or invalid in file names on common systems. */
const UNSAFE_NAME_CHARACTERS = new Set(['<', '>', ':', '"', '|', '?', '*', '/', '\\']);

function isUnsafeCodePoint(code: number): boolean {
  return (
    code < 0x20 || // C0 controls
    (code >= 0x7f && code < 0xa0) || // DEL and C1 controls
    code === 0x200e || // left-to-right mark
    code === 0x200f || // right-to-left mark
    (code >= 0x202a && code <= 0x202e) || // bidi embeddings and overrides (fake extensions)
    (code >= 0x2066 && code <= 0x2069) // bidi isolates
  );
}

/**
 * A safe download name: the last path segment only, without control, bidi and reserved characters,
 * without leading or trailing dots and spaces, at most MAX_CSV_FILE_NAME_LENGTH characters and
 * always ending in the kind's extension. Null or empty → the kind's fallback name.
 */
function sanitizeFileName(raw: string | null, kind: FileKind): string {
  if (raw === null) {
    return kind.fallback;
  }
  const segment = raw.split(/[/\\]/).pop() ?? '';
  let name = Array.from(segment)
    .filter((ch) => !UNSAFE_NAME_CHARACTERS.has(ch) && !isUnsafeCodePoint(ch.codePointAt(0) ?? 0))
    .join('')
    .replace(/^[.\s]+|[.\s]+$/g, '');
  if (name === '') {
    return kind.fallback;
  }
  if (!name.toLowerCase().endsWith(kind.extension)) {
    name = `${name}${kind.extension}`;
  }
  if (name.length > MAX_CSV_FILE_NAME_LENGTH) {
    const stem = name.slice(0, MAX_CSV_FILE_NAME_LENGTH - kind.extension.length);
    name = `${stem.replace(/[.\s]+$/, '')}${kind.extension}`;
  }
  return name;
}

/** sanitizeFileName for CSV exports: always ends in `.csv`, else DEFAULT_CSV_FILE_NAME. */
export function sanitizeCsvFileName(raw: string | null): string {
  return sanitizeFileName(raw, CSV_FILE);
}

/** sanitizeFileName for JSON downloads: always ends in `.json`, else DEFAULT_JSON_FILE_NAME. */
export function sanitizeJsonFileName(raw: string | null): string {
  return sanitizeFileName(raw, JSON_FILE);
}

/**
 * The file name of a `Content-Disposition` header (RFC 6266): `filename*=UTF-8''…` (RFC 8187) wins
 * over `filename="…"`; the result is sanitised (sanitizeCsvFileName).
 */
export function csvFileName(contentDisposition: string | null): string {
  return sanitizeCsvFileName(contentDispositionName(contentDisposition));
}

/** As csvFileName, for a JSON download (sanitizeJsonFileName). */
export function jsonFileName(contentDisposition: string | null): string {
  return sanitizeJsonFileName(contentDispositionName(contentDisposition));
}

/** The raw (unsanitised) file name of a `Content-Disposition` header, or null. */
function contentDispositionName(contentDisposition: string | null): string | null {
  if (contentDisposition === null) {
    return null;
  }
  let raw: string | null = null;
  const extended = /(?:^|;)\s*filename\*\s*=\s*([^;]*)/i.exec(contentDisposition)?.[1];
  if (extended !== undefined) {
    const encoded = /^[\w!#$%&+^`{}~-]*'[^']*'(.*)$/.exec(extended.trim())?.[1];
    if (encoded !== undefined) {
      try {
        raw = decodeURIComponent(encoded);
      } catch {
        raw = null;
      }
    }
  }
  if (raw === null) {
    const plain = /(?:^|;)\s*filename\s*=\s*("(?:[^"\\]|\\.)*"|[^;]*)/i.exec(
      contentDisposition,
    )?.[1];
    if (plain !== undefined) {
      const value = plain.trim();
      raw =
        value.length >= 2 && value.startsWith('"') && value.endsWith('"')
          ? value.slice(1, -1).replace(/\\(.)/g, '$1')
          : value;
    }
  }
  return raw;
}

/** The browser's IANA time zone: CSV times are written in it (docs/15 §12). */
function browserTimeZone(): string {
  return Intl.DateTimeFormat().resolvedOptions().timeZone;
}

interface CsvInit {
  readonly params: {
    readonly path: unknown;
    readonly query: Record<string, unknown>;
  };
  readonly body?: unknown;
  readonly headers: Record<string, string>;
  readonly parseAs: 'arrayBuffer';
}

interface CsvResult {
  readonly data?: ArrayBuffer;
  readonly error?: unknown;
  readonly response: Response;
}

/** The generated client's `request` without its per-path types (checked by CsvRequest above). */
type UntypedRequest = (method: CsvMethod, url: string, init: CsvInit) => Promise<CsvResult>;

/**
 * Requests the CSV form of a contract operation through the shared client and transport. Throws
 * an ApiError: `limit_exceeded` (more than 10,000 rows), `rate_limited` 429 with
 * `retryAfterSeconds` (HEAVY bucket, C-176: show a retry hint, do not retry automatically),
 * `network`, or `invalid_response` when the answer is not CSV.
 */
export async function requestCsv<M extends CsvMethod, P extends CsvPath<M>>(
  method: M,
  path: P,
  request: CsvRequest<P, M>,
): Promise<CsvFile> {
  const loose = request as {
    readonly path: unknown;
    readonly query?: Record<string, unknown>;
    readonly body?: unknown;
  };
  const query: Record<string, unknown> = { ...loose.query, format: 'csv' };
  query.tz ??= browserTimeZone();
  const send = api().client.request as unknown as UntypedRequest;
  let result: CsvResult;
  try {
    result = await send(method, path, {
      params: { path: loose.path, query },
      ...(method === 'post' ? { body: loose.body } : {}),
      headers: { Accept: 'text/csv' },
      // Bytes, not text: the server's UTF-8 BOM (for spreadsheet programs) must survive.
      parseAs: 'arrayBuffer',
    });
  } catch (error) {
    if (error instanceof DOMException && error.name === 'AbortError') {
      throw error;
    }
    throw networkError();
  }
  const { response } = result;
  if (!response.ok) {
    throw toApiError(response, result.error);
  }
  const type = response.headers.get('Content-Type') ?? '';
  if (!/^text\/csv\s*(;|$)/i.test(type) || result.data === undefined) {
    throw new ApiError({
      kind: 'invalid_response',
      status: response.status,
      code: 'invalid_response',
      message: 'The export answered without text/csv',
    });
  }
  return {
    // The server's bytes (BOM included), always typed as CSV, so the browser never renders them.
    blob: new Blob([result.data], { type: 'text/csv;charset=utf-8' }),
    fileName: csvFileName(response.headers.get('Content-Disposition')),
  };
}

/** Media type of the blobs saveFile() names `.json`. */
const JSON_BLOB_TYPE = /^application\/json\s*(;|$)/i;

/** Timer and document seams of saveFile (tests replace them). */
export interface SaveFileEnvironment {
  readonly document?: Document;
  readonly setTimeout?: (callback: () => void, ms: number) => unknown;
}

/**
 * Saves a blob as a download: an object URL on a temporary <a download>, clicked once and removed;
 * the URL is revoked after REVOKE_DELAY_MS. The only place in src/ that creates object URLs
 * (argos/no-object-url; eslint-allow.json). A blob typed `application/json` (the transaction
 * export's server bytes, the received trace response of ADR-0010 C-207 (1)) is saved under a
 * `.json` name (sanitizeJsonFileName); every other blob under a `.csv` name (sanitizeCsvFileName).
 */
export function saveFile(blob: Blob, fileName: string, env: SaveFileEnvironment = {}): void {
  const doc = env.document ?? document;
  const schedule = env.setTimeout ?? ((callback, ms) => setTimeout(callback, ms));
  const url = URL.createObjectURL(blob);
  try {
    const link = doc.createElement('a');
    link.href = url;
    link.download = JSON_BLOB_TYPE.test(blob.type)
      ? sanitizeJsonFileName(fileName)
      : sanitizeCsvFileName(fileName);
    link.rel = 'noopener';
    link.hidden = true;
    doc.body.append(link);
    link.click();
    link.remove();
  } finally {
    schedule(() => {
      URL.revokeObjectURL(url);
    }, REVOKE_DELAY_MS);
  }
}

/**
 * Exports the CSV form of a contract operation and saves it (T-UI-004, DSN-15-06). Returns the file
 * name. Errors as in requestCsv(); nothing is saved on an error.
 *
 *     await exportCsv('get', '/api/v1/apps/{appId}/sql/stats', {
 *       path: { appId },
 *       query: { from, to, sort: sortSpecToUrl(sort) },
 *     });
 */
export async function exportCsv<M extends CsvMethod, P extends CsvPath<M>>(
  method: M,
  path: P,
  request: CsvRequest<P, M>,
  env: SaveFileEnvironment = {},
): Promise<string> {
  const file = await requestCsv(method, path, request);
  saveFile(file.blob, file.fileName, env);
  return file.fileName;
}
