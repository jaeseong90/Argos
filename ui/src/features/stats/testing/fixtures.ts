// SPDX-License-Identifier: Apache-2.0
//
// Statistics responses for the screens' tests (docs/14 §4.8 StatsViews): service and SQL rows with
// changes (including null, a clamped one and 0), details with series, breakdowns, raw window and
// related transactions, and an event list. 64-bit keys are decimal strings beyond 2^53 where it
// matters (C-175). Test-only module: nothing in the application imports it.
import type {
  AppEvent,
  ErrorGroup,
  ErrorGroupDetail,
  ExternalDetail,
  ExternalStats,
  ServiceDetail,
  ServiceStats,
  SqlDetail,
  SqlStats,
  StatsBreakdown,
  StatsSeries,
} from '../../../api/stats';
import { untrusted } from '../../../api/untrusted';

export const FROM_ISO = '2026-11-20T03:00:00.000Z';
export const TO_ISO = '2026-11-20T04:00:00.000Z';
export const FROM = Date.parse(FROM_ISO);
export const TO = Date.parse(TO_ISO);
export const RANGE_QUERY = `from=${FROM_ISO}&to=${TO_ISO}`;
/** A key beyond 2^53: Number() would change it (C-175). */
export const BIG_ID = '18446744073709551615';

export function serviceRow(overrides: Partial<ServiceStats> = {}): ServiceStats {
  return {
    service_id: '11',
    service: untrusted('GET /api/orders/{id}'),
    count: 1200,
    errors: 12,
    error_rate: 0.01,
    avg_ms: 120,
    p50_ms: 80,
    p95_ms: 450,
    p99_ms: 900,
    max_ms: 2100,
    apdex: 0.93,
    sum_ms: 144_000,
    sql_time_ratio: 0.4,
    ext_time_ratio: 0.1,
    delta: { count: 0.12, p95_ms: -0.05, error_rate: 0.004 },
    ...overrides,
  };
}

export function serviceRows(): ServiceStats[] {
  return [
    serviceRow(),
    serviceRow({
      service_id: BIG_ID,
      service: untrusted('POST /api/orders'),
      count: 300,
      errors: 0,
      error_rate: 0,
      p95_ms: null,
      delta: { count: null, p95_ms: null, error_rate: null },
    }),
    serviceRow({
      service_id: '13',
      service: untrusted('GET /health'),
      count: 50,
      delta: { count: 25, p95_ms: 0, error_rate: -0.01 },
    }),
  ];
}

export function sqlRow(overrides: Partial<SqlStats> = {}): SqlStats {
  return {
    sql_id: '21',
    sql_normalized: untrusted('SELECT * FROM orders WHERE id = ?'),
    sql_truncated: false,
    db: {
      db_id: '31',
      target: untrusted('jdbc:postgresql://db:5432/shop'),
      host: untrusted('db'),
      name: untrusted('shop'),
      vendor: untrusted('postgresql'),
    },
    count: 5000,
    errors: 3,
    sum_ms: 25_000,
    exec_ms: 20_000,
    fetch_ms: 5000,
    avg_ms: 5,
    max_ms: 300,
    p50_ms: 3,
    p95_ms: 18,
    p99_ms: 60,
    fetch_rows: 5000,
    estimated: true,
    delta: { count: 0.2, p95_ms: 0.1, error_rate: 0.001 },
    ...overrides,
  };
}

export function sqlRows(): SqlStats[] {
  return [
    sqlRow(),
    sqlRow({
      sql_id: '22',
      sql_normalized: untrusted('UPDATE stock SET qty = qty - ? WHERE item = ?'),
      db: {
        db_id: '32',
        target: untrusted('jdbc:mysql://inventory:3306/stock'),
        host: untrusted('inventory'),
        name: untrusted('stock'),
        vendor: untrusted('mysql'),
      },
      count: 800,
      p95_ms: null,
      delta: { count: null, p95_ms: null, error_rate: null },
    }),
  ];
}

const MINUTE = 60_000;

export function series(buckets = 6, overrides: Partial<StatsSeries> = {}): StatsSeries {
  const ts = Array.from({ length: buckets }, (_, i) =>
    new Date(FROM + i * 10 * MINUTE).toISOString(),
  );
  return {
    ts,
    count: ts.map((_, i) => (i === 2 ? null : 100 + i)),
    errors: ts.map(() => 1),
    error_rate: ts.map(() => 0.01),
    avg_ms: ts.map(() => 120),
    p95_ms: ts.map((_, i) => (i === 3 ? null : 400 + i)),
    p99_ms: ts.map(() => 900),
    ...overrides,
  };
}

export function breakdown(
  id: string,
  name: string | null,
  overrides: Partial<StatsBreakdown> = {},
): StatsBreakdown {
  return {
    id,
    name: name === null ? null : untrusted(name),
    count: 100,
    share: 0.5,
    avg_ms: 100,
    p95_ms: 300,
    errors: 1,
    ...overrides,
  };
}

export function serviceDetail(overrides: Partial<ServiceDetail> = {}): ServiceDetail {
  return {
    service_id: '11',
    service: untrusted('GET /api/orders/{id}'),
    series: series(),
    by_version: [
      breakdown('41', '1.4.0', { p95_ms: 520, count: 400 }),
      breakdown('42', '1.5.0', { p95_ms: 310, count: 800 }),
    ],
    by_instance: [breakdown('7', 'web-1'), breakdown('8', 'web-2', { p95_ms: null })],
    top_sql: [breakdown('21', 'SELECT * FROM orders WHERE id = ?')],
    top_external: [breakdown('51', 'payments.example:443')],
    top_error_groups: [breakdown('61', 'java.lang.IllegalStateException', { share: null })],
    raw_window: { from: FROM_ISO, to: TO_ISO },
    ...overrides,
  };
}

export function sqlDetail(overrides: Partial<SqlDetail> = {}): SqlDetail {
  return {
    sql_id: '21',
    sql_normalized: untrusted('SELECT * FROM orders WHERE id = ?'),
    sql_text: untrusted('SELECT * FROM orders WHERE id = ?'),
    sql_truncated: false,
    norm_version: 1,
    count: 5000,
    exec_ms_total: 20_000,
    fetch_ms_total: 5000,
    wait_ms_total: null,
    estimated: true,
    series: series(),
    by_service: [
      breakdown('11', 'GET /api/orders/{id}', { share: 0.75, count: 3750 }),
      breakdown('12', 'POST /api/orders', { share: 0.25, count: 1250 }),
    ],
    by_instance: [breakdown('7', 'web-1')],
    by_db: [sqlRow()],
    raw_window: { from: FROM_ISO, to: TO_ISO },
    related_tx: {
      note: 'Latest transactions whose recorded SQL ids contain this statement',
      items: [
        {
          tx_id: BIG_ID,
          ts: '2026-11-20T03:30:00.000Z',
          elapsed_ms: 840,
          instance_id: 7,
          service_id: '11',
          service: untrusted('GET /api/orders/{id}'),
        },
      ],
    },
    ...overrides,
  };
}

export function externalRow(overrides: Partial<ExternalStats> = {}): ExternalStats {
  return {
    host_id: '51',
    host: untrusted('https://payments.example:443'),
    path_pattern: null,
    count: 900,
    errors: 9,
    timeouts: 4,
    connect_failures: 2,
    sum_ms: 90_000,
    avg_ms: 100,
    max_ms: 3000,
    p50_ms: 60,
    p95_ms: 400,
    p99_ms: 1200,
    estimated: true,
    delta: { count: 0.1, p95_ms: 0.2, error_rate: -0.002 },
    ...overrides,
  };
}

export function externalRows(): ExternalStats[] {
  return [
    externalRow(),
    externalRow({
      host_id: BIG_ID,
      host: untrusted('http://inventory.internal:8080'),
      count: 200,
      errors: 0,
      timeouts: 0,
      connect_failures: 0,
      p95_ms: null,
      delta: { count: null, p95_ms: null, error_rate: null },
    }),
  ];
}

export function externalDetail(overrides: Partial<ExternalDetail> = {}): ExternalDetail {
  return {
    host_id: '51',
    host: untrusted('https://payments.example:443'),
    series: series(),
    estimated: true,
    by_service: [
      breakdown('11', 'GET /api/orders/{id}', { share: 0.6, count: 60 }),
      breakdown('12', 'POST /api/orders', { share: 0.4, count: 40 }),
    ],
    raw_window: { from: FROM_ISO, to: TO_ISO },
    related_tx: {
      note: 'Latest profiled transactions that called this host',
      items: [
        {
          tx_id: BIG_ID,
          ts: '2026-11-20T03:40:00.000Z',
          elapsed_ms: 1500,
          instance_id: 7,
          service_id: '11',
          service: untrusted('GET /api/orders/{id}'),
        },
      ],
    },
    ...overrides,
  };
}

export function errorGroupRow(overrides: Partial<ErrorGroup> = {}): ErrorGroup {
  return {
    group_id: '61',
    error_class: untrusted('java.lang.IllegalStateException'),
    message_pattern: untrusted('Order <num> is not payable'),
    top_frame: untrusted('com.shop.order.OrderService.pay(OrderService.java:88)'),
    count: 40,
    first_seen: '2026-11-20T03:05:00.000Z',
    last_seen: '2026-11-20T03:55:00.000Z',
    first_seen_ever: '2026-11-20T03:05:00.000Z',
    is_new: true,
    services: [breakdown('11', 'GET /api/orders/{id}', { count: 30 })],
    versions: [{ version_id: '42', version: untrusted('1.5.0') }],
    delta: { count: null, p95_ms: null, error_rate: null },
    ...overrides,
  };
}

export function errorGroupRows(): ErrorGroup[] {
  return [
    errorGroupRow(),
    errorGroupRow({
      group_id: BIG_ID,
      error_class: untrusted('java.net.SocketTimeoutException'),
      message_pattern: untrusted('Read timed out'),
      top_frame: untrusted('com.shop.client.PaymentClient.call(PaymentClient.java:40)'),
      count: 25,
      first_seen: '2026-11-20T03:10:00.000Z',
      last_seen: '2026-11-20T03:50:00.000Z',
      first_seen_ever: '2026-11-01T00:00:00.000Z',
      is_new: false,
      services: [
        breakdown('12', 'POST /api/orders', { count: 20 }),
        breakdown('11', 'GET /api/orders/{id}', { count: 5 }),
      ],
      versions: [
        { version_id: '41', version: untrusted('1.4.0') },
        { version_id: '42', version: untrusted('1.5.0') },
      ],
      delta: { count: 0.25, p95_ms: null, error_rate: null },
    }),
  ];
}

/** A masked stack: application frames (com.shop) and library frames. */
export const SAMPLE_STACK: readonly string[] = [
  'com.shop.order.OrderService.pay(OrderService.java:88)',
  'org.springframework.aop.framework.ReflectiveMethodInvocation.proceed(ReflectiveMethodInvocation.java:186)',
  'com.shop.web.OrderController.pay(OrderController.java:41)',
  'java.base/java.lang.Thread.run(Thread.java:1583)',
];

export function errorGroupDetail(overrides: Partial<ErrorGroupDetail> = {}): ErrorGroupDetail {
  const group = errorGroupRow({ delta: undefined });
  return {
    group,
    sample_stack: SAMPLE_STACK.map((frame) => untrusted(frame)),
    series: series(6, { errors: null, error_rate: null, avg_ms: null, p95_ms: null }),
    by_version: [
      breakdown('41', '1.4.0', { count: 10, share: 0.25 }),
      breakdown('42', '1.5.0', { count: 30, share: 0.75 }),
    ],
    by_instance: [breakdown('7', 'web-1', { count: 40, share: 1 })],
    by_service: [breakdown('11', 'GET /api/orders/{id}', { count: 40, share: 1 })],
    raw_window: { from: FROM_ISO, to: TO_ISO },
    recent_tx: [
      {
        tx_id: BIG_ID,
        ts: '2026-11-20T03:55:00.000Z',
        elapsed_ms: 320,
        instance_id: 7,
        service_id: '11',
        service: untrusted('GET /api/orders/{id}'),
      },
      {
        tx_id: '9001',
        ts: '2026-11-20T03:54:00.000Z',
        elapsed_ms: 290,
        instance_id: 7,
        service_id: '11',
        service: untrusted('GET /api/orders/{id}'),
      },
    ],
    ...overrides,
  };
}

export function deployEvent(id: string, ts: string, version: string): AppEvent {
  return {
    event_id: id,
    ts,
    type: 'deploy',
    title: untrusted(`Deploy ${version}`),
    version: untrusted(version),
    detail: null,
    detail_truncated: false,
    instance_id: null,
    actor: null,
  };
}
