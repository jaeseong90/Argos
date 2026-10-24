// SPDX-License-Identifier: Apache-2.0
//
// Synthetic XLog data for the canvas PoC (WO-04): log-normal response times with a long tail,
// Zipf-distributed services, skewed instances, per-service error rates and an incident window in
// which one service slows down on one instance. Deterministic for a seed. A few service, instance
// and error class names carry XSS payloads (SEC-61): they must render as text everywhere.
import type { SamplingInfo } from '../../xlog/sampling';
import {
  KIND_BATCH,
  KIND_HTTP,
  KIND_MESSAGE,
  STATUS_ERROR,
  STATUS_NORMAL,
  STATUS_SLOW,
  XLogBuffer,
  type XLogDictionary,
  type XLogPoint,
} from '../../xlog/XLogBuffer';

/** Slow threshold used to derive the status of generated points (app default, docs/15 §7.2). */
export const DUMMY_SLOW_THRESHOLD_MS = 1000;
/** Upper bound of generated response times (10 minutes). */
export const MAX_ELAPSED_MS = 600_000;

/**
 * Payloads from docs/15 §15 and SEC-61. Each would set window.__xss if it were parsed as HTML.
 * They are plain strings in this module and are only ever rendered as React text nodes.
 */
export const XSS_SERVICE_NAMES: readonly string[] = [
  'GET /api/search?q=<img src=x onerror="window.__xss=1">',
  'POST /api/reviews/"><svg/onload=window.__xss=1>',
  '<script>window.__xss=1</script>',
];
export const XSS_INSTANCE_NAME = 'shop-api-"><svg/onload=window.__xss=1>';
export const XSS_ERROR_CLASS = 'com.example.<img src=x onerror="window.__xss=1">Exception';

/** Small, fast, seedable PRNG (mulberry32). Returns floats in [0, 1). */
export function createRandom(seed: number): () => number {
  let state = seed >>> 0;
  return () => {
    state = (state + 0x6d2b79f5) >>> 0;
    let z = state;
    z = Math.imul(z ^ (z >>> 15), z | 1);
    z ^= z + Math.imul(z ^ (z >>> 7), z | 61);
    return ((z ^ (z >>> 14)) >>> 0) / 4294967296;
  };
}

/** Standard normal variate (Box-Muller). */
function normal(random: () => number): number {
  let u = random();
  while (u <= Number.MIN_VALUE) {
    u = random();
  }
  return Math.sqrt(-2 * Math.log(u)) * Math.cos(2 * Math.PI * random());
}

/** 32-bit finaliser of MurmurHash3, used for the deterministic sample. */
export function mix32(value: number): number {
  let h = value >>> 0;
  h = Math.imul(h ^ (h >>> 16), 0x85ebca6b);
  h = Math.imul(h ^ (h >>> 13), 0xc2b2ae35);
  return (h ^ (h >>> 16)) >>> 0;
}

/** Hash of a 64-bit transaction id. */
export function txHash(hi: number, lo: number): number {
  return mix32(mix32(lo) ^ Math.imul(hi >>> 0, 0x9e3779b1));
}

const RESOURCES = [
  '/api/orders/{id}',
  '/api/orders',
  '/api/products/{id}',
  '/api/products',
  '/api/cart',
  '/api/cart/items',
  '/api/users/{id}',
  '/api/users/{id}/profile',
  '/api/payments/{id}',
  '/api/payments/{id}/capture',
  '/api/inventory/{sku}',
  '/api/shipping/quotes',
  '/api/coupons/{code}',
  '/api/recommendations',
  '/api/reviews/{id}',
  '/api/categories',
  '/api/wishlist',
  '/api/addresses/{id}',
  '/api/notifications',
  '/api/auth/session',
  '/admin/reports/daily',
  '/admin/orders/{id}/refund',
  '/admin/products/import',
  '/admin/users',
];
const VERBS = ['GET', 'POST', 'PUT', 'DELETE'];
const BATCH_SERVICES = ['SettlementJob.run', 'InventorySyncJob.run', 'ReportExportJob.run'];
const MESSAGE_SERVICES = ['OrderEventListener.onMessage', 'StockEventListener.onMessage'];

const INSTANCES = [
  'shop-api-1',
  'shop-api-2',
  'shop-api-3',
  'shop-api-4',
  'shop-api-5',
  'shop-api-6',
  XSS_INSTANCE_NAME,
  'shop-batch-1',
  'shop-worker-1',
];
const API_INSTANCE_WEIGHTS = [1, 0.95, 0.9, 0.85, 0.6, 0.4, 0.05];
const BATCH_INSTANCE = 7;
const MESSAGE_INSTANCE = 8;
/** The incident hits this instance (shop-api-3). */
const INCIDENT_INSTANCE = 2;

const ERROR_CLASSES = [
  'java.sql.SQLTimeoutException',
  'java.net.SocketTimeoutException',
  'java.lang.NullPointerException',
  'java.lang.IllegalStateException',
  'org.springframework.web.client.HttpServerErrorException',
  'org.springframework.dao.DataIntegrityViolationException',
  XSS_ERROR_CLASS,
];
const ERROR_CLASS_WEIGHTS = [0.3, 0.25, 0.15, 0.1, 0.1, 0.07, 0.03];
const ERROR_HTTP = [500, 500, 500, 502, 503, 504, 400, 404];
const OK_HTTP = [200, 200, 200, 200, 200, 200, 200, 200, 200, 200, 200, 200, 201, 204, 302, 304];

interface ServiceModel {
  readonly name: string;
  readonly kind: number;
  /** ln of the median response time. */
  readonly mu: number;
  readonly sigma: number;
  readonly errorRate: number;
}

export interface DummyModel {
  readonly dictionary: XLogDictionary;
  readonly services: readonly ServiceModel[];
  /** Cumulative Zipf weights over services. */
  readonly serviceCdf: Float64Array;
  readonly instanceCdf: Float64Array;
  readonly errorClassCdf: Float64Array;
  /** Index of the service that slows down during the incident. */
  readonly incidentService: number;
}

function cumulative(weights: readonly number[]): Float64Array {
  const cdf = new Float64Array(weights.length);
  let sum = 0;
  weights.forEach((w, i) => {
    sum += w;
    cdf[i] = sum;
  });
  for (let i = 0; i < cdf.length; i += 1) {
    cdf[i] = (cdf[i] ?? 0) / sum;
  }
  return cdf;
}

function pick(cdf: Float64Array, u: number): number {
  let lo = 0;
  let hi = cdf.length - 1;
  while (lo < hi) {
    const mid = (lo + hi) >>> 1;
    if ((cdf[mid] ?? 1) < u) {
      lo = mid + 1;
    } else {
      hi = mid;
    }
  }
  return lo;
}

/** Share of all points that are batch transactions (tens of seconds each, D-20 kind=batch). */
const BATCH_SHARE = 0.0002;

/**
 * Builds the service/instance model: about `serviceCount` services ranked by a Zipf law (s = 1.1).
 * The XSS service names sit at ranks 3, 7 and 12, so they show up in the top-10 summary and in
 * tooltips. Message listeners sit at ranks 15 and 44; batch jobs are the last three services and
 * get BATCH_SHARE of the points, so request latency, not batch runtime, sets the y axis.
 */
export function createDummyModel(seed: number, serviceCount = 200): DummyModel {
  const random = createRandom(seed ^ 0x5bd1e995);
  const names: string[] = [];
  for (const resource of RESOURCES) {
    for (const verb of VERBS) {
      names.push(`${verb} ${resource}`);
    }
  }
  // Deterministic shuffle so the popular services are a mix of verbs and resources.
  for (let i = names.length - 1; i > 0; i -= 1) {
    const j = Math.floor(random() * (i + 1));
    const tmp = names[i] ?? '';
    names[i] = names[j] ?? '';
    names[j] = tmp;
  }
  names.unshift('GET /api/orders/{id}');
  const ordered = [...new Set(names)];
  const total = Math.max(20, Math.min(serviceCount, 2000));
  const services: ServiceModel[] = [];
  const xssRanks = new Map([
    [3, XSS_SERVICE_NAMES[0] ?? ''],
    [7, XSS_SERVICE_NAMES[1] ?? ''],
    [12, XSS_SERVICE_NAMES[2] ?? ''],
  ]);
  const messageRanks = new Map([
    [15, MESSAGE_SERVICES[0] ?? 'Listener.onMessage'],
    [44, MESSAGE_SERVICES[1] ?? 'Listener.onMessage'],
  ]);
  const firstBatchRank = total - BATCH_SERVICES.length;
  let next = 0;
  for (let rank = 0; rank < total; rank += 1) {
    let name = xssRanks.get(rank);
    let kind = KIND_HTTP;
    if (name === undefined) {
      const message = messageRanks.get(rank);
      if (rank >= firstBatchRank) {
        name = BATCH_SERVICES[rank - firstBatchRank] ?? 'BatchJob.run';
        kind = KIND_BATCH;
      } else if (message !== undefined) {
        name = message;
        kind = KIND_MESSAGE;
      } else {
        const base = ordered[next % ordered.length] ?? 'GET /';
        name =
          next < ordered.length ? base : `${base} #${String(Math.floor(next / ordered.length))}`;
        next += 1;
      }
    }
    const slowService = random() < 0.08;
    const median =
      kind === KIND_BATCH
        ? 20_000 + random() * 60_000
        : kind === KIND_MESSAGE
          ? 15 + random() * 60
          : slowService
            ? 400 + random() * 600
            : Math.exp(Math.log(15) + random() * Math.log(250 / 15));
    services.push({
      name,
      kind,
      mu: Math.log(median),
      sigma: 0.45 + random() * 0.5,
      errorRate: random() < 0.15 ? 0.02 + random() * 0.06 : 0.001 + random() * 0.01,
    });
  }
  // Keep names unique: a dictionary maps index -> name, duplicates would merge in summaries.
  const seen = new Map<string, number>();
  const unique = services.map((service) => {
    const count = seen.get(service.name) ?? 0;
    seen.set(service.name, count + 1);
    return count === 0 ? service : { ...service, name: `${service.name} (${String(count + 1)})` };
  });
  return {
    dictionary: {
      services: unique.map((s) => s.name),
      instances: INSTANCES,
      errorClasses: ERROR_CLASSES,
    },
    services: unique,
    serviceCdf: cumulative(serviceWeights(unique)),
    instanceCdf: cumulative(API_INSTANCE_WEIGHTS),
    errorClassCdf: cumulative(ERROR_CLASS_WEIGHTS),
    incidentService: 0,
  };
}

/** Zipf weights by rank; batch jobs share BATCH_SHARE of the total. */
function serviceWeights(services: readonly ServiceModel[]): number[] {
  const zipf = services.map((service, rank) =>
    service.kind === KIND_BATCH ? 0 : 1 / Math.pow(rank + 1, 1.1),
  );
  const sum = zipf.reduce((acc, w) => acc + w, 0);
  const batchCount = services.filter((service) => service.kind === KIND_BATCH).length;
  const batchWeight = batchCount === 0 ? 0 : (sum * BATCH_SHARE) / (1 - BATCH_SHARE) / batchCount;
  return services.map((service, rank) =>
    service.kind === KIND_BATCH ? batchWeight : (zipf[rank] ?? 0),
  );
}

/** Writes generated points; `incident` is [from, to) of the incident window or null. */
export class PointFactory {
  readonly model: DummyModel;
  private readonly random: () => number;
  private readonly incident: { readonly from: number; readonly to: number } | null;

  constructor(
    model: DummyModel,
    seed: number,
    incident: { readonly from: number; readonly to: number } | null = null,
  ) {
    this.model = model;
    this.random = createRandom(seed);
    this.incident = incident;
  }

  /** One point at time `ts`. */
  next(ts: number): XLogPoint {
    const random = this.random;
    const model = this.model;
    const serviceIndex = pick(model.serviceCdf, random());
    const service = model.services[serviceIndex] ?? model.services[0];
    if (service === undefined) {
      throw new Error('The dummy model has no services');
    }
    let instance: number;
    if (service.kind === KIND_BATCH) {
      instance = BATCH_INSTANCE;
    } else if (service.kind === KIND_MESSAGE) {
      instance = MESSAGE_INSTANCE;
    } else {
      instance = pick(model.instanceCdf, random());
    }
    let elapsed = Math.exp(service.mu + service.sigma * normal(random));
    // Long tail: 2% of the calls wait on something slow (lock, GC, remote timeout).
    if (random() < 0.02) {
      elapsed *= Math.exp(0.8 + Math.abs(normal(random)) * 0.9);
    }
    let errorRate = service.errorRate;
    const inIncident =
      this.incident !== null &&
      ts >= this.incident.from &&
      ts < this.incident.to &&
      serviceIndex === model.incidentService &&
      instance === INCIDENT_INSTANCE;
    if (inIncident) {
      elapsed *= 6;
      errorRate = 0.25;
    }
    elapsed = Math.min(MAX_ELAPSED_MS, Math.max(1, Math.round(elapsed)));
    const isError = random() < errorRate;
    const status = isError
      ? STATUS_ERROR
      : elapsed >= DUMMY_SLOW_THRESHOLD_MS && service.kind === KIND_HTTP
        ? STATUS_SLOW
        : STATUS_NORMAL;
    const http = service.kind !== KIND_HTTP;
    const httpStatus = http
      ? 0
      : isError
        ? (ERROR_HTTP[Math.floor(random() * ERROR_HTTP.length)] ?? 500)
        : (OK_HTTP[Math.floor(random() * OK_HTTP.length)] ?? 200);
    const errorClass = isError ? pick(model.errorClassCdf, random()) + 1 : 0;
    return {
      ts,
      elapsed,
      status,
      instance,
      service: serviceIndex,
      txIdLo: Math.floor(random() * 4294967296),
      txIdHi: Math.floor(random() * 4294967296),
      kind: service.kind,
      httpStatus,
      errorClass,
    };
  }
}

export interface DummyOptions {
  /** Points to show (after sampling). */
  readonly count: number;
  /** Points in the range before sampling; > count turns on the deterministic sample. */
  readonly total?: number;
  readonly seed: number;
  readonly from: number;
  readonly to: number;
  /** Buffer capacity; defaults to the number of generated points. */
  readonly capacity?: number;
  readonly serviceCount?: number;
  /** Incident window as fractions of the range; null disables it. */
  readonly incident?: { readonly start: number; readonly end: number } | null;
}

export interface DummyDataset {
  readonly buffer: XLogBuffer;
  readonly dictionary: XLogDictionary;
  readonly sampling: SamplingInfo | null;
  readonly model: DummyModel;
  readonly from: number;
  readonly to: number;
}

/** Upper bound of generated points per dataset (memory guard for URL parameters). */
export const MAX_DUMMY_TOTAL = 500_000;

/**
 * Generates `total` points (default `count`) uniformly over [from, to] in time order. When
 * total > count, keeps every slow/error point and the normal points whose transaction id hash
 * falls into 1/N (A-5 deterministic sample), so the same points survive a reload.
 */
export function createDummyDataset(options: DummyOptions): DummyDataset {
  const count = Math.max(0, Math.floor(options.count));
  const total = Math.min(MAX_DUMMY_TOTAL, Math.max(count, Math.floor(options.total ?? count)));
  const from = options.from;
  const to = Math.max(options.to, from + 1);
  const model = createDummyModel(options.seed, options.serviceCount);
  const incidentShape =
    options.incident === undefined ? { start: 0.55, end: 0.62 } : options.incident;
  const span = to - from;
  const incident =
    incidentShape === null
      ? null
      : { from: from + incidentShape.start * span, to: from + incidentShape.end * span };
  const factory = new PointFactory(model, options.seed, incident);
  const timeRandom = createRandom(options.seed ^ 0x2545f491);
  const times = new Float64Array(total);
  for (let i = 0; i < total; i += 1) {
    times[i] = from + timeRandom() * span;
  }
  times.sort();

  const generated: XLogPoint[] = [];
  if (total > count) {
    // Pass 1 counts the slow/error points (always kept); pass 2 regenerates the same sequence from
    // the same seed and keeps them plus 1/keepEvery of the normal points.
    const counter = new PointFactory(model, options.seed, incident);
    let special = 0;
    for (const ts of times) {
      if (counter.next(ts).status !== STATUS_NORMAL) {
        special += 1;
      }
    }
    const room = Math.max(1, count - special);
    const keepEvery = Math.max(1, Math.ceil((total - special) / room));
    for (const ts of times) {
      const p = factory.next(ts);
      if (p.status !== STATUS_NORMAL || txHash(p.txIdHi, p.txIdLo) % keepEvery === 0) {
        generated.push(p);
      }
    }
  } else {
    for (const ts of times) {
      generated.push(factory.next(ts));
    }
  }

  const capacity = Math.max(1, options.capacity ?? generated.length);
  const buffer = new XLogBuffer(capacity);
  // Column copy (docs/15 §7.1: no row objects in the buffer).
  const columns = {
    ts: new Float64Array(generated.length),
    elapsed: new Uint32Array(generated.length),
    status: new Uint8Array(generated.length),
    instance: new Uint16Array(generated.length),
    service: new Uint32Array(generated.length),
    txIdLo: new Uint32Array(generated.length),
    txIdHi: new Uint32Array(generated.length),
    kind: new Uint8Array(generated.length),
    httpStatus: new Uint16Array(generated.length),
    errorClass: new Uint32Array(generated.length),
  };
  generated.forEach((p, i) => {
    columns.ts[i] = p.ts;
    columns.elapsed[i] = p.elapsed;
    columns.status[i] = p.status;
    columns.instance[i] = p.instance;
    columns.service[i] = p.service;
    columns.txIdLo[i] = p.txIdLo;
    columns.txIdHi[i] = p.txIdHi;
    columns.kind[i] = p.kind;
    columns.httpStatus[i] = p.httpStatus;
    columns.errorClass[i] = p.errorClass;
  });
  buffer.appendColumns(columns);
  return {
    buffer,
    dictionary: model.dictionary,
    sampling: total > count ? { shown: generated.length, total } : null,
    model,
    from,
    to,
  };
}
