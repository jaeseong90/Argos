// SPDX-License-Identifier: Apache-2.0
//
// k6 scenario of docs/18 §7.2 and docs/01 §7.3 item 3 (overhead benchmark workload): open model,
// constant arrival rate; mix: DB 1-3 SQL 60% (orders/{id} 40%, orders?page 20%), external HTTP 20%
// (pay, one ext-stub call), N+1 10% (items, 1 + 20 SQL), slow 300 ms 5%, error 5% (unhandled
// exception, 500). The Java demo generator with MIX=bench sends the same mix in the same order
// (sample-apps/load: same table, same schedule; MixTest compares the table below with it).
//
// k6 is AGPL-3.0: it runs as a separate tool (container grafana/k6 pinned by digest, see
// run-bench.sh), for development and benchmarks only, never inside an Argos artifact
// (docs/licenses/dev-only.md, ADR-0003 C-33). The script uses built-in k6 modules only (no remote
// imports): it talks to TARGET_URL and nothing else.
//
// Parameters (environment: `k6 run -e NAME=value` or `docker run -e NAME=value`):
//   TARGET_URL           base URL of the app (a WAR context path may follow)  http://localhost:8080
//   RATE                 requests per second (constant arrival rate)          1000
//   DURATION             measured window                                     10m
//   WARMUP               warm-up at the same rate and mix before the window,  3m
//                        excluded from the results (18 §7.2: 13 minutes, the
//                        first 3 discarded); 0s disables it
//   PRE_ALLOCATED_VUS    VUs allocated up front                              300
//   MAX_VUS              VU ceiling (no free VU: k6 drops the iteration)     600
//   REQUEST_TIMEOUT      per-request timeout                                 10s
//   SUMMARY_PATH         JSON summary written at the end                     summary.json
//   GATE                 on: thresholds below fail the run (exit code 99)    on
//   MAX_UNEXPECTED_RATE  allowed share of unexpected status or failure       0.01
//   MAX_DROPPED_RATE     allowed share of dropped iterations                 0.01
// "Unexpected" means a status other than the route's expected one (error-500 expects 500) or no
// answer; the 5% intended errors are reported separately (http5xxRate).

import http from 'k6/http';
import exec from 'k6/execution';
import { check, fail } from 'k6';
import { Rate } from 'k6/metrics';

const TARGET = (__ENV.TARGET_URL || 'http://localhost:8080').replace(/\/+$/, '');
const RATE = positiveNumber('RATE', 1000);
const DURATION = __ENV.DURATION || '10m';
const WARMUP = __ENV.WARMUP || '3m';
const PRE_ALLOCATED_VUS = positiveNumber('PRE_ALLOCATED_VUS', 300);
const MAX_VUS = Math.max(PRE_ALLOCATED_VUS, positiveNumber('MAX_VUS', 600));
const REQUEST_TIMEOUT = __ENV.REQUEST_TIMEOUT || '10s';
const SUMMARY_PATH = __ENV.SUMMARY_PATH || 'summary.json';
const GATE = (__ENV.GATE || 'on').toLowerCase() !== 'off';
const MAX_UNEXPECTED_RATE = fraction('MAX_UNEXPECTED_RATE', 0.01);
const MAX_DROPPED_RATE = fraction('MAX_DROPPED_RATE', 0.01);

const WARMUP_SECONDS = seconds(WARMUP);
const DURATION_SECONDS = seconds(DURATION);
if (DURATION_SECONDS <= 0) {
  throw new Error('DURATION must be positive');
}

// Orders in the sample data (common: ShopSchema.ORDERS, PAGE_SIZE 100 -> 10 pages).
const ORDERS = 1000;
const PAGES = 10;

// mix:begin (one route per line; MixTest in sample-apps/load compares these rows with Mix.bench())
const MIX = [
  { route: 'orders.get', service: 'GET /api/orders/{id}', weight: 40, expect: 200, path: (o) => `/api/orders/${orderId(o)}` },
  { route: 'orders.page', service: 'GET /api/orders', weight: 20, expect: 200, path: (o) => `/api/orders?page=${o % PAGES}` },
  { route: 'pay', service: 'GET /api/pay/{id}', weight: 20, expect: 200, path: (o) => `/api/pay/${orderId(o)}` },
  { route: 'orders.items', service: 'GET /api/orders/{id}/items', weight: 10, expect: 200, path: (o) => `/api/orders/${orderId(o)}/items` },
  { route: 'slow', service: 'GET /api/slow', weight: 5, expect: 200, path: () => `/api/slow?ms=300` },
  { route: 'error-500', service: 'GET /api/error-500', weight: 5, expect: 500, path: () => `/api/error-500` },
];
// mix:end

// Order id 1..ORDERS; 7919 is coprime with 1000, so consecutive ordinals visit every order.
function orderId(ordinal) {
  return 1 + ((ordinal * 7919) % ORDERS);
}

// Smooth weighted round robin, the same algorithm and tie rule (lowest index) as
// WeightedSchedule.java: every cycle of sum(weights)/gcd slots holds each route weight/gcd times.
function buildSchedule(weights) {
  const gcd = (a, b) => (b === 0 ? a : gcd(b, a % b));
  const divisor = weights.reduce((a, b) => gcd(a, b), 0);
  const perCycle = weights.map((w) => w / divisor);
  const length = perCycle.reduce((a, b) => a + b, 0);
  const current = perCycle.map(() => 0);
  const seen = perCycle.map(() => 0);
  const slots = [];
  const occurrence = [];
  for (let slot = 0; slot < length; slot++) {
    let best = 0;
    for (let i = 0; i < perCycle.length; i++) {
      current[i] += perCycle[i];
      if (current[i] > current[best]) {
        best = i;
      }
    }
    current[best] -= length;
    slots.push(best);
    occurrence.push(seen[best]++);
  }
  return { slots, occurrence, perCycle };
}

const SCHEDULE = buildSchedule(MIX.map((r) => r.weight));
const CALLBACKS = MIX.map((r) => http.expectedStatuses(r.expect));
const status5xx = new Rate('status_5xx');

function thresholds() {
  const t = {
    // Listed so that the summary carries the measured-window sub-metrics.
    'http_reqs{phase:measure}': ['count>=0'],
    'http_req_duration{phase:measure}': ['max>=0'],
    'status_5xx{phase:measure}': ['rate>=0'],
    'iterations{phase:measure}': ['count>=0'],
  };
  for (const r of MIX) {
    t[`http_req_duration{phase:measure,route:${r.route}}`] = ['max>=0'];
    t[`http_reqs{phase:measure,route:${r.route}}`] = ['count>=0'];
  }
  const allowedDrops = Math.floor(RATE * (WARMUP_SECONDS + DURATION_SECONDS) * MAX_DROPPED_RATE);
  t['http_req_failed{phase:measure}'] = GATE ? [`rate<=${MAX_UNEXPECTED_RATE}`] : ['rate>=0'];
  t['checks{phase:measure}'] = GATE ? [`rate>=${1 - MAX_UNEXPECTED_RATE}`] : ['rate>=0'];
  t.dropped_iterations = GATE ? [`count<=${allowedDrops}`] : ['count>=0'];
  return t;
}

export const options = {
  scenarios: {
    mix: {
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: `${WARMUP_SECONDS + DURATION_SECONDS}s`,
      preAllocatedVUs: PRE_ALLOCATED_VUS,
      maxVUs: MAX_VUS,
    },
  },
  discardResponseBodies: true,
  userAgent: 'argos-bench/k6',
  summaryTrendStats: ['avg', 'min', 'p(50)', 'p(95)', 'p(99)', 'p(99.9)', 'max'],
  thresholds: thresholds(),
};

export function setup() {
  const res = http.get(`${TARGET}/actuator/health`, { timeout: '10s', tags: { phase: 'setup' } });
  if (res.status !== 200) {
    fail(`${TARGET}/actuator/health answered ${res.status} (${res.error || 'no error'}); is the app up?`);
  }
  return { startedAt: new Date().toISOString() };
}

export default function () {
  const n = exec.scenario.iterationInTest;
  const elapsedMs = Date.now() - exec.scenario.startTime;
  const phase = elapsedMs < WARMUP_SECONDS * 1000 ? 'warmup' : 'measure';
  // VU-level tag: the request, check and iteration metrics of this iteration carry the phase.
  exec.vu.metrics.tags.phase = phase;
  const slot = n % SCHEDULE.slots.length;
  const index = SCHEDULE.slots[slot];
  const ordinal = Math.floor(n / SCHEDULE.slots.length) * SCHEDULE.perCycle[index] + SCHEDULE.occurrence[slot];
  const route = MIX[index];
  const tags = { name: route.service, route: route.route };
  const res = http.get(`${TARGET}${route.path(ordinal)}`, {
    tags,
    timeout: REQUEST_TIMEOUT,
    responseCallback: CALLBACKS[index],
  });
  check(res, { 'expected status': (r) => r.status === route.expect }, { route: route.route });
  status5xx.add(res.status >= 500 || res.status === 0, { route: route.route });
}

export function handleSummary(data) {
  const values = (name) => (data.metrics[name] ? data.metrics[name].values : {});
  const measured = values('http_reqs{phase:measure}');
  const duration = values('http_req_duration{phase:measure}');
  const requests = measured.count || 0;
  const dropped = values('dropped_iterations').count || 0;
  const iterations = values('iterations').count || 0;
  const routes = MIX.map((r) => {
    const count = values(`http_reqs{phase:measure,route:${r.route}}`).count || 0;
    return {
      route: r.route,
      service: r.service,
      weightPercent: r.weight,
      expectedStatus: r.expect,
      requests: count,
      sharePercent: requests > 0 ? round((100 * count) / requests) : null,
      latencyMs: latency(values(`http_req_duration{phase:measure,route:${r.route}}`)),
    };
  });
  const thresholdResults = {};
  for (const [name, metric] of Object.entries(data.metrics)) {
    if (metric.thresholds) {
      thresholdResults[name] = Object.values(metric.thresholds).every((t) => t.ok);
    }
  }
  const result = {
    tool: 'k6',
    script: 'sample-apps/load/bench.js',
    config: {
      targetUrl: TARGET,
      rate: RATE,
      durationSeconds: DURATION_SECONDS,
      warmupSeconds: WARMUP_SECONDS,
      preAllocatedVUs: PRE_ALLOCATED_VUS,
      maxVUs: MAX_VUS,
      requestTimeout: REQUEST_TIMEOUT,
      gate: GATE,
      maxUnexpectedRate: MAX_UNEXPECTED_RATE,
      maxDroppedRate: MAX_DROPPED_RATE,
    },
    results: {
      windowSeconds: DURATION_SECONDS,
      requests,
      targetRate: RATE,
      achievedRate: round(requests / DURATION_SECONDS),
      unexpectedRate: round6(values('http_req_failed{phase:measure}').rate),
      checksRate: round6(values('checks{phase:measure}').rate),
      http5xxRate: round6(values('status_5xx{phase:measure}').rate),
      iterationsWholeRun: iterations,
      droppedIterationsWholeRun: dropped,
      droppedRate: round6(dropped / Math.max(1, iterations + dropped)),
      latencyMs: latency(duration),
      routes,
      thresholds: thresholdResults,
      thresholdsPassed: Object.values(thresholdResults).every((ok) => ok),
    },
    k6: data,
  };
  return {
    [SUMMARY_PATH]: JSON.stringify(result, null, 2),
    stdout: text(result),
  };
}

function latency(v) {
  return {
    avg: round(v.avg),
    min: round(v.min),
    p50: round(v['p(50)']),
    p95: round(v['p(95)']),
    p99: round(v['p(99)']),
    'p99.9': round(v['p(99.9)']),
    max: round(v.max),
  };
}

function text(result) {
  const r = result.results;
  const l = r.latencyMs;
  const lines = [
    '',
    `bench.js  target=${result.config.targetUrl}  rate=${result.config.rate}/s  window=${r.windowSeconds}s  warm-up=${result.config.warmupSeconds}s (excluded)`,
    `  requests=${r.requests}  achieved=${r.achievedRate}/s  unexpected=${pct(r.unexpectedRate)}  5xx=${pct(r.http5xxRate)} (5% intended)  dropped=${r.droppedIterationsWholeRun} (${pct(r.droppedRate)})`,
    `  latency ms  p50=${l.p50}  p95=${l.p95}  p99=${l.p99}  p99.9=${l['p99.9']}  max=${l.max}  avg=${l.avg}`,
    '  route          share   expect  p50      p95      p99      max',
  ];
  for (const x of r.routes) {
    lines.push(
      `  ${x.route.padEnd(14)} ${String(x.sharePercent).padStart(5)}%  ${x.expectedStatus}     ${pad(x.latencyMs.p50)} ${pad(x.latencyMs.p95)} ${pad(x.latencyMs.p99)} ${pad(x.latencyMs.max)}`,
    );
  }
  lines.push(`  thresholds ${r.thresholdsPassed ? 'passed' : 'FAILED'}; summary: ${SUMMARY_PATH}`, '');
  return lines.join('\n');
}

function pad(value) {
  return String(value).padEnd(8);
}

function pct(value) {
  return value === null || value === undefined ? 'n/a' : `${(value * 100).toFixed(3)}%`;
}

function round(value) {
  return value === undefined || value === null || Number.isNaN(value) ? null : Math.round(value * 1000) / 1000;
}

function round6(value) {
  return value === undefined || value === null || Number.isNaN(value) ? null : Math.round(value * 1e6) / 1e6;
}

function positiveNumber(name, fallback) {
  const raw = __ENV[name];
  if (raw === undefined || raw === '') {
    return fallback;
  }
  const value = Number(raw);
  if (!(value > 0)) {
    throw new Error(`${name} must be a positive number, got '${raw}'`);
  }
  return value;
}

function fraction(name, fallback) {
  const raw = __ENV[name];
  if (raw === undefined || raw === '') {
    return fallback;
  }
  const value = Number(raw);
  if (!(value >= 0 && value <= 1)) {
    throw new Error(`${name} must be between 0 and 1, got '${raw}'`);
  }
  return value;
}

// k6-style duration (500ms, 30s, 1m30s, 2h; a bare number is seconds) in seconds.
function seconds(text) {
  const value = String(text).trim().toLowerCase();
  if (/^\d+$/.test(value)) {
    return Number(value);
  }
  const part = /(\d+)(ms|s|m|h)/y;
  const factor = { ms: 0.001, s: 1, m: 60, h: 3600 };
  let total = 0;
  let position = 0;
  while (position < value.length) {
    part.lastIndex = position;
    const match = part.exec(value);
    if (!match) {
      throw new Error(`bad duration '${text}' (examples: 500ms, 30s, 1m30s)`);
    }
    total += Number(match[1]) * factor[match[2]];
    position = part.lastIndex;
  }
  return total;
}
