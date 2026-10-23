# Argos 저장소 설계

| 항목 | 내용 |
|---|---|
| 문서 번호 | 13 |
| 문서 버전 | 0.1 (초안) |
| 작성일 | 2026-10-23 |
| 상태 | 설계 검토 중. 5·4·6장의 "PoC 결정" 표기 항목은 Phase 0 P-4로 확정 |
| 만족시키는 요구 | D-03, D-08, D-09, D-11~D-17, D-23, N-06~N-08, N-18~N-22, N-24, F-S02, F-S10, F-S16, F-S19, F-S21~F-S23, F-S32, SEC-13, SEC-22, SEC-30~SEC-35, SEC-52, SEC-74, SEC-76, SEC-80~SEC-84 |
| 의존 문서 | 02 아키텍처(DSN-02-08~10·12~15·20, §5 데이터 분류표), 11 프로토콜(배치·통계 메시지), 12 서버(WAL·라이터) |
| 이 문서에 의존하는 문서 | 12 서버(라이터·토큰·정합 검사), 14 API(테이블·소스 선택·쿼리 설정), 17 배포·운영(설정 파일·백업·용량), 18 테스트(PoC 하네스·정합 테스트) |

## 1. 범위와 설계 결정 요약

이 문서는 ClickHouse(트랜잭션·프로파일·메트릭·통계·롤업)와 PostgreSQL(메타데이터·사전·감사)의 스키마, 적재·조회 규약, 운영 설정, 보존, 삭제, 백업, 마이그레이션, 용량, PoC 사양을 정한다. 서버 코드가 이 문서를 구현하고(12), API가 이 문서의 쿼리 패턴을 쓴다(14).

| ID | 결정 | 대안 | 선택 이유 | 영향 요구 |
|---|---|---|---|---|
| DSN-13-01 | 원본 5 테이블(`xlog`, `profile_step`, `metric`, `sql_stat_raw`, `ext_stat_raw`) + 롤업 11 테이블. 모든 테이블에 `app_id`, `env LowCardinality`, `kind` 포함 | env·kind를 PG 조인으로만 해결 | 전체 현황·배치 분리(D-20)를 ClickHouse 단독 쿼리로 처리 | DSN-02-14·15 |
| DSN-13-02 | SQL·외부 호출 통계는 에이전트 10초 창 전수 집계 테이블(`*_stat_raw`)에서만 롤업. `profile_step`에서 통계를 만들지 않는다 | 프로파일 기반 집계 | D-12. 샘플 편향 제거 | F-A22, F-S21 |
| DSN-13-03 | `*_stat_raw`에 고정 경계 15구간 히스토그램 `buckets Array(UInt32)`를 싣는다. p50/p95/p99는 버킷 선형 보간 | 분포 없이 avg·max만 / 에이전트 tDigest 전송 | 외부 호스트·SQL p95(F-U05·S-11)를 전수로 제공하면서 행 크기 60B 이내 | F-U05, F-U06 |
| DSN-13-04 | 메트릭 행은 `kind(gauge/counter)`와 `delta`를 함께 싣는다. counter의 리셋 보정 증가분은 에이전트가 계산 | 서버 MV에서 델타 계산 | MV는 블록 경계를 넘는 델타를 계산할 수 없다. 리셋은 에이전트가 가장 정확히 안다 | D-09, D-16 |
| DSN-13-05 | 측정 불가 값(`cpu_time_ms`, `alloc_bytes`)은 `Nullable` 대신 음수 센티널(-1)로 저장하고 API 계층이 null로 변환 | Nullable 컬럼 | Nullable은 별도 널 맵 파일과 정렬 키 제약. 센티널은 비용 0 | D-23 |
| DSN-13-06 | 백분위 상태는 `quantilesTDigest(0.5, 0.95, 0.99)`를 기본으로 두고 PoC Q14에서 BFloat16·DD와 값 오차를 비교해 확정 | 즉시 확정 | 값 오차 기준(p50·p95 ≤ 2%, p99 ≤ 5%)을 실측 없이 보장할 수 없다 | A-11 |
| DSN-13-07 | 중복 제거 토큰은 라이터가 WAL에 기록한 **블록 매니페스트**의 `block_id`에서 유도한다: `argos:{table}:{block_id}`. 재적재는 같은 block_id로 같은 블록을 다시 넣는다 | WAL 세그먼트:오프셋 범위 | 라이터가 묶는 블록 경계가 재시작 후에도 동일해야 토큰이 일치한다. 경계 자체를 WAL에 남기면 결정적이다 | D-13, DSN-02-12 |
| DSN-13-08 | 1m 롤업은 원본에 건 MV, 1h 롤업은 1m 테이블에 건 MV(캐스케이드). 롤업 SELECT는 `deploy/clickhouse/rollups/*.sql` 한 벌을 MV 생성과 재구축 CLI가 공유 | 원본에 1m·1h MV 둘 다 | insert당 parts 수를 절반으로. 같은 SELECT라야 재구축이 바이트 단위 동일(D-14) | D-14, F-S22 |
| DSN-13-09 | 사전 키는 전부 xxh3_64(UInt64). 원문은 PostgreSQL `dictionary`에 비동기 upsert, 64KB 상한·절단 플래그·`norm_version` | 서버 발급 순번 | DSN-02-10. 적재 경로가 사전 왕복에 묶이지 않음 | D-08, D-22 |
| DSN-13-10 | `tx_id`는 Snowflake형. 단건 조회는 `tx_id >> 23`으로 복원한 시각 ±2초 범위로 파티션·정렬 키를 좁히고 bloom 인덱스로 보강. `trace_id` Argos 생성분은 상위 4B Unix 초로 힌트 복원 | URL에 ts 필수 | DSN-02-09. URL ts는 선택 힌트 | D-11, A-6 |
| DSN-13-11 | 멀티테넌시는 컬럼이 아니라 설정값 `argos.storage.clickhouse.database`(기본 `argos`)와 `table-prefix`(기본 빈 문자열)로 예약. 모든 DDL·쿼리는 `{db}.{prefix}xlog` 형식의 플레이스홀더 | `tenant_id` 컬럼 | 정렬 키 밖 컬럼은 테넌시에 쓸 수 없다. DB 분리가 ClickHouse 정석 | A-7 |
| DSN-13-12 | ClickHouse 계정 3개: `argos_writer`(INSERT), `argos_reader`(readonly=1, 쿼리 상한), `argos_migrator`(DDL, 마이그레이션 시에만 사용). `default` 사용자는 비밀번호 설정 + 네트워크 제한 | 단일 계정 | SEC-74·76, 최소 권한 | SEC-74, SEC-76 |
| DSN-13-13 | PostgreSQL 계정 2개: `argos_app`(DML + `audit_logs`는 INSERT·SELECT만), `argos_migrator`(DDL·Flyway). 감사 로그 삭제는 `argos_migrator`로 도는 보존 작업만 | 단일 계정 | SEC-82 불변성 | SEC-80~82 |
| DSN-13-14 | 보존은 TTL(원본)과 `DROP PARTITION`(단축·비상)으로만 지운다. `ttl_only_drop_parts=1`로 TTL이 재작성 머지를 일으키지 않게 한다 | 기본 TTL 머지 | 야간 I/O 급증 방지, 단축 즉시 반영 | N-08, DSN-02-13 |
| DSN-13-15 | 개인정보 삭제는 `ALTER TABLE … DELETE` 비동기 뮤테이션을 **일 파티션 단위**로 발행하고 `system.mutations`로 상태를 추적. 롤업은 대상 밖임을 API 응답에 명시 | 경량 `DELETE FROM` | 버전 의존성 회피, 파티션 한정으로 비용 통제 | SEC-33, F-S32 |
| DSN-13-16 | 백업: PostgreSQL `pg_dump` 일 1회 필수. ClickHouse는 기본 "재구축 가능 텔레메트리, 백업 선택", 켜면 외부 대상(NAS·S3 호환)으로 `BACKUP … SETTINGS base_backup` 증분 | CH 전체 백업 필수 | 같은 디스크에 7일분 전체 백업은 물리적으로 불가(N-20) | N-20, SEC-84 |
| DSN-13-17 | ClickHouse 마이그레이션은 자체 러너. 이력·체크섬은 PostgreSQL `ch_schema_history`, 스크립트는 멱등, MV 교체는 4단계 고정 | Flyway 커뮤니티 CH 모듈 | 트랜잭션 없음·MV 처리 미흡. 러너 규칙을 우리가 통제 | N-22 |
| DSN-13-18 | ClickHouse는 번들에 **정확 버전**을 고정하고 지원 범위를 "그 LTS와 다음 LTS"로 한다. 사용 기능의 최소 버전을 17장 확인 목록으로 관리 | "최신 LTS" | N-21. 설치 시점마다 다른 버전이면 매트릭스가 아니다 | N-21 |
| DSN-13-19 | `remote_ip`는 `IPv6` 타입 하나로 저장(IPv4는 매핑 주소). 모드(`off`/`masked`/`full`)와 `user_id` 모드(`off`/`plain`/`hashed`)를 행마다 Enum8로 기록 | IPv4 컬럼, 모드는 인스턴스 메타만 | 인스턴스마다 모드가 다를 수 있고 UI가 행 단위로 "마스킹됨"을 표시해야 한다. Enum8은 압축 비용 0 | SEC-31, SEC-32 |
| DSN-13-20 | 연속 반복 step은 에이전트가 접어서 `repeat_count`·합계 시간으로 1행에 담는다(N+1 요약). 저장소는 `repeat_count UInt16` 컬럼으로 받는다 | 전부 저장 | step 상한 1,000과 N+1 수천 회의 충돌 해소, 상세 화면 접힌 요약 | F-U02 |

## 2. 공통 전제

| 항목 | 값 |
|---|---|
| 시간 | ClickHouse 서버 `timezone=UTC`. 모든 시간 컬럼은 UTC. `DateTime64(3)`은 ms, 메트릭·통계 창은 `DateTime`(초) |
| `tx_id` | UInt64 = (ms − 2026-01-01T00:00:00Z) << 23 ‖ node(12bit) << 11 ‖ seq(11bit). 복원: `toDateTime64((bitShiftRight(tx_id, 23) + 1767225600000) / 1000, 3, 'UTC')` |
| `trace_id` | FixedString(16). Argos 생성분은 바이트 0~3이 Unix 초(big-endian). 외부 유입분은 임의 |
| 사전 키 | `service_id`, `sql_id`, `name_id`, `target_id`(db·host·pool), `error_class_id`, `error_group_id`, `thread_name_id`, `frame_id`, `version_id`: xxh3_64 → UInt64. 원문은 PostgreSQL `dictionary` |
| `app_id` | PostgreSQL `apps.id`(UInt32). (name, env) 쌍에 1:1 |
| `env` | `apps.env` 값을 비정규화한 LowCardinality(String) |
| `kind` | `Enum8('HTTP'=1, 'BATCH'=2, 'MESSAGE'=3)` |
| 결측 | `cpu_time_ms Int32`, `alloc_bytes Int64`는 -1이 결측. API가 null로 변환(D-23) |
| 플레이스홀더 | DDL·쿼리의 `{db}`는 설정 `argos.storage.clickhouse.database`(기본 `argos`), `{p}`는 `argos.storage.clickhouse.table-prefix`(기본 ``) |
| 계정 | `argos_writer`, `argos_reader`, `argos_migrator`(7장) |
| 히스토그램 경계(ms) | `[1, 2, 5, 10, 20, 50, 100, 200, 500, 1000, 2000, 5000, 10000, 30000, +∞]` → 15 버킷. 에이전트·서버·API가 상수 `LATENCY_BUCKETS_MS`를 공유(11 문서) |

## 3. ClickHouse 원본 테이블

### 3.1 `xlog` — 트랜잭션 전수(XLog 점 1개 = 행 1개)

근거: 드래그 검색·대시보드 원본 구간·XLog 점 조회가 `(app_id, ts)` 범위 스캔이므로 정렬 키 선두를 `app_id, ts`로 둔다. `tx_id`를 세 번째에 두어 같은 ms 안의 순서를 고정하고 단건 조회를 정렬 키로 끝낸다. 하루 파티션은 TTL·비상 삭제·재구축 단위다.

```sql
CREATE TABLE {db}.{p}xlog
(
    app_id           UInt32,
    env              LowCardinality(String),
    instance_id      UInt32,
    ts               DateTime64(3, 'UTC') CODEC(DoubleDelta, LZ4),
    tx_id            UInt64,                                  -- Snowflake형(D-11). 시간 접두라 기본 LZ4로 충분
    kind             Enum8('HTTP' = 1, 'BATCH' = 2, 'MESSAGE' = 3),
    trace_id         FixedString(16) CODEC(NONE),             -- 랜덤. 압축 시도 비용만 든다
    span_id          UInt64 CODEC(NONE),
    parent_span_id   UInt64 CODEC(NONE),                      -- 0이면 루트
    caller_app_id    UInt32,                                  -- 분산 호출의 호출자 앱(서비스 맵)
    service_id       UInt64,                                  -- 사전 키
    version_id       UInt64,                                  -- 앱 버전 태그 사전 키(F-A21). 없으면 0
    http_method      LowCardinality(String),                  -- BATCH는 ''
    http_status      UInt16,                                  -- BATCH는 0(성공)/1(실패)
    elapsed_ms       UInt32 CODEC(T64, LZ4),
    error            UInt8,
    error_class_id   UInt64,                                  -- 예외 클래스 사전 키. 0이면 없음
    error_group_id   UInt64,                                  -- 지문(F-S19). 서버가 insert 전에 계산
    apdex_t_ms       UInt16,                                  -- insert 시점 서비스 T(F-S16, D-17)
    remote_ip        IPv6,                                    -- IPv4는 ::ffff:a.b.c.d. off면 ::
    ip_mode          Enum8('off' = 0, 'masked' = 1, 'full' = 2),
    user_id          String CODEC(ZSTD(3)),                   -- hashed면 16자 hex, plain ≤128, off면 ''
    user_id_mode     Enum8('off' = 0, 'plain' = 1, 'hashed' = 2),
    request_id       String CODEC(ZSTD(3)),                   -- 설정된 요청 ID 헤더 값 ≤128
    sql_count        UInt16,
    sql_time_ms      UInt32 CODEC(T64, LZ4),                  -- exec + fetch 합
    dbc_time_ms      UInt32 CODEC(T64, LZ4),                  -- 커넥션 획득 대기 합(F-A03)
    ext_count        UInt16,
    ext_time_ms      UInt32 CODEC(T64, LZ4),
    cpu_time_ms      Int32  CODEC(T64, LZ4),                  -- -1 = 결측(D-23)
    alloc_bytes      Int64  CODEC(T64, LZ4),                  -- -1 = 결측
    thread_name_id   UInt64,                                  -- 정규화된 스레드 이름 사전 키(F-A30)
    profile_captured UInt8,                                   -- 1이면 profile_step 전송됨(D-07)
    streaming        UInt8,                                   -- SSE·스트리밍 응답(F-A34)
    timeout_flag     UInt8,                                   -- max-duration 강제 종료(F-A34)
    sql_ids          Array(UInt64),                           -- 실행된 SQL 사전 키 집합(중복 제거). A-10: PoC Q11로 채택 결정
    INDEX idx_tx      tx_id      TYPE bloom_filter(0.01) GRANULARITY 4,   -- PoC Q5: fp 0.01·0.025, GRANULARITY 1·4 비교
    INDEX idx_trace   trace_id   TYPE bloom_filter(0.01) GRANULARITY 4,   -- PoC Q6
    INDEX idx_user    user_id    TYPE bloom_filter(0.01) GRANULARITY 4,   -- N-24 user_id 검색
    INDEX idx_request request_id TYPE bloom_filter(0.01) GRANULARITY 4,
    INDEX idx_sql     sql_ids    TYPE bloom_filter(0.01) GRANULARITY 4    -- A-10
)
ENGINE = MergeTree
PARTITION BY toDate(ts)
ORDER BY (app_id, ts, tx_id)
TTL toDateTime(ts) + INTERVAL 30 DAY
SETTINGS ttl_only_drop_parts = 1,
         non_replicated_deduplication_window = 1000,
         index_granularity = 8192;
```

| 컬럼 그룹 | 분류(02 §5.2) | 비고 |
|---|---|---|
| `remote_ip`, `user_id`, `request_id` | 개인정보 가능 | 모드 컬럼이 함께 있어 UI가 행 단위로 "마스킹됨"을 표시한다. 삭제 API 대상(9장) |
| `service_id`·`sql_ids` | 내부(사전 경유) | 원문은 PostgreSQL. 쿼리스트링은 서비스 이름에 들어오지 않는다(SEC-34) |
| 나머지 | 내부 | |

### 3.2 `profile_step` — 트랜잭션 상세 단계(조건부)

근거: 상세 조회는 항상 `(app_id, tx_id)` 전체 step을 읽는다. `tx_id`가 시간 접두이므로 `ORDER BY (app_id, tx_id, seq)`는 시간 지역성을 가져 압축과 파티션 프루닝이 함께 된다. 스택 원문은 넣지 않는다(프레임 사전 id 배열).

```sql
CREATE TABLE {db}.{p}profile_step
(
    app_id          UInt32,
    env             LowCardinality(String),
    instance_id     UInt32,
    tx_id           UInt64,
    ts              DateTime64(3, 'UTC') CODEC(DoubleDelta, LZ4),   -- 트랜잭션 시작 시각(파티션·TTL용)
    kind            Enum8('HTTP' = 1, 'BATCH' = 2, 'MESSAGE' = 3),
    seq             UInt16,
    parent_seq      UInt16,                                         -- 0이면 루트 직하
    type            Enum8('SQL' = 1, 'DBC' = 2, 'COMMIT' = 3, 'ROLLBACK' = 4, 'HTTP' = 5,
                          'METHOD' = 6, 'ASYNC' = 7, 'ERROR' = 8, 'STACK' = 9, 'MESSAGE' = 10),
    start_offset_ms UInt32 CODEC(T64, LZ4),                         -- 트랜잭션 시작 기준
    elapsed_ms      UInt32 CODEC(T64, LZ4),                         -- 반복 접힘이면 합계
    exec_ms         UInt32 CODEC(T64, LZ4),                         -- SQL 실행(execute) 시간
    fetch_ms        UInt32 CODEC(T64, LZ4),                         -- SQL fetch 시간(ResultSet)
    name_id         UInt64,                                         -- SQL 해시 / URL 패턴 / 메서드 / 예외 클래스 / 풀 이름
    target_id       UInt64,                                         -- SQL: db_id, HTTP: host_id, DBC: pool_id, 그 외 0
    rows            UInt32,                                         -- SQL fetch 행수, HTTP 응답 바이트, 배치 건수
    status          UInt16,                                         -- HTTP 상태 또는 실패 분류(0 OK, 1 TIMEOUT, 2 CONNECT_FAIL, 3 OTHER)
    error           UInt8,
    repeat_count    UInt16,                                         -- 연속 반복 접힘 횟수(1 = 접힘 없음)
    detail          String CODEC(ZSTD(3)),                          -- 바인드 파라미터(마스킹)·URL 경로. 상한 4KB. 스택 원문 금지
    stack           Array(UInt64)                                   -- STACK·ERROR: 프레임 사전 키. 상한 64
)
ENGINE = MergeTree
PARTITION BY toDate(ts)
ORDER BY (app_id, tx_id, seq)
TTL toDateTime(ts) + INTERVAL 7 DAY
SETTINGS ttl_only_drop_parts = 1,
         non_replicated_deduplication_window = 1000;
```

`detail`은 분류 "개인정보 가능"(바인드 파라미터). 모드가 `masked`면 `[String(11)]` 같은 타입·길이 표기만 들어온다(SEC-3A). 삭제 API 대상.

### 3.3 `metric` — 카운터 원본(5초 격자)

근거: 인스턴스 1개의 시계열을 읽는 패턴이 지배적이므로 `(app_id, instance_id, name, ts)`. 이름은 OpenMetrics 문자열(라벨 포함)을 LowCardinality로. 인스턴스당 시리즈 상한 500(D-16)이 카디널리티를 막는다.

```sql
CREATE TABLE {db}.{p}metric
(
    app_id      UInt32,
    env         LowCardinality(String),
    instance_id UInt32,
    name        LowCardinality(String),                  -- 예: jvm_memory_used_bytes{area="heap"}
    kind        Enum8('gauge' = 1, 'counter' = 2),
    ts          DateTime('UTC') CODEC(DoubleDelta, LZ4),  -- 5초 격자에 정렬
    value       Float64 CODEC(Gorilla, LZ4),             -- gauge: 순간값, counter: 누적값
    delta       Float64 CODEC(Gorilla, LZ4)              -- counter: 직전 샘플 대비 증가분(리셋 보정, 에이전트 계산). gauge: 0
)
ENGINE = MergeTree
PARTITION BY toDate(ts)
ORDER BY (app_id, instance_id, name, ts)
TTL ts + INTERVAL 14 DAY
SETTINGS ttl_only_drop_parts = 1,
         non_replicated_deduplication_window = 1000;
```

결측은 행이 없는 것이다. 롤업의 `sample_count=0`이 null로 반환된다(D-09·D-16). 메트릭 종류·단위 레지스트리는 11 문서(프로토콜 `MetricDescriptor`)에 있고 서버가 `kind`를 채운다.

### 3.4 `sql_stat_raw` — SQL 전수 통계(10초 창)

근거: D-12. 에이전트가 트랜잭션 종료마다 `(sql_id, db_id, kind)` 키로 10초 창에 누적하고 창이 닫히면 전송한다. 프로파일 샘플링과 무관하게 모든 SQL 실행이 여기에 센다. 15구간 히스토그램으로 p95를 전수로 낸다(DSN-13-03).

```sql
CREATE TABLE {db}.{p}sql_stat_raw
(
    app_id        UInt32,
    env           LowCardinality(String),
    instance_id   UInt32,
    ts            DateTime('UTC') CODEC(DoubleDelta, LZ4),   -- 10초 창 시작
    kind          Enum8('HTTP' = 1, 'BATCH' = 2, 'MESSAGE' = 3),
    sql_id        UInt64,
    db_id         UInt64,                                   -- JDBC URL(벤더·호스트·DB명) 사전 키
    count         UInt32,
    error_count   UInt32,
    sum_ms        UInt64,                                   -- exec + fetch
    sum_exec_ms   UInt64,
    sum_fetch_ms  UInt64,
    max_ms        UInt32,
    fetch_rows    UInt64,
    buckets       Array(UInt32)                             -- 15 버킷(2장 경계). 길이 고정
)
ENGINE = MergeTree
PARTITION BY toDate(ts)
ORDER BY (app_id, sql_id, db_id, kind, ts)
TTL ts + INTERVAL 14 DAY
SETTINGS ttl_only_drop_parts = 1,
         non_replicated_deduplication_window = 1000;
```

### 3.5 `ext_stat_raw` — 외부 호출 전수 통계(10초 창)

```sql
CREATE TABLE {db}.{p}ext_stat_raw
(
    app_id             UInt32,
    env                LowCardinality(String),
    instance_id        UInt32,
    ts                 DateTime('UTC') CODEC(DoubleDelta, LZ4),
    kind               Enum8('HTTP' = 1, 'BATCH' = 2, 'MESSAGE' = 3),
    host_id            UInt64,                             -- scheme://host:port 사전 키
    count              UInt32,
    error_count        UInt32,                             -- 5xx + 기타 예외
    timeout_count      UInt32,
    connect_fail_count UInt32,
    sum_ms             UInt64,
    max_ms             UInt32,
    buckets            Array(UInt32)
)
ENGINE = MergeTree
PARTITION BY toDate(ts)
ORDER BY (app_id, host_id, kind, ts)
TTL ts + INTERVAL 14 DAY
SETTINGS ttl_only_drop_parts = 1,
         non_replicated_deduplication_window = 1000;
```

커넥션 획득 대기(DBC)는 SQL 단위가 아니라 풀 단위 현상이므로 `*_stat_raw`가 아니라 메트릭 counter `jdbc_pool_wait_seconds_total{pool}`·`jdbc_pool_wait_count_total{pool}`로 온다(10 문서). 트랜잭션 단위 합계는 `xlog.dbc_time_ms`.

## 4. 롤업 테이블과 Materialized View

### 4.1 규약

- 1분 롤업은 **원본 테이블에 건 MV**로, 1시간 롤업은 **1분 테이블에 건 MV**로 만든다(캐스케이드). 원본 insert 1건이 만드는 parts는 원본 1 + 1m 롤업 수.
- 롤업 SELECT 본문은 `deploy/clickhouse/rollups/<table>.sql` 파일 한 벌에만 있다. 마이그레이션 러너는 이 파일을 읽어 `CREATE MATERIALIZED VIEW … AS <SELECT>`를 만들고, 재구축 CLI(`argos-admin rollup rebuild`)는 같은 파일을 읽어 `INSERT INTO <rollup> <SELECT> WHERE toDate(ts) = :date`를 실행한다(D-14). 파일에는 `{db}`, `{p}`, `{where}` 플레이스홀더만 허용한다.
- 집계 컬럼은 가능하면 `SimpleAggregateFunction`(sum·max·min·any), 분포만 `AggregateFunction`(백분위 상태)로 둔다. 1h 캐스케이드는 `SimpleAggregateFunction`은 그대로 sum/max, `AggregateFunction`은 `-MergeState`.
- 모든 롤업은 `AggregatingMergeTree`, 1m은 `PARTITION BY toDate(minute)`(재구축 단위 = 하루), 1h는 `PARTITION BY toYYYYMM(hour)`.
- 조회는 항상 `-Merge`(`sumMerge`, `quantilesTDigestMerge`)로 읽거나 `FINAL` 없이 `GROUP BY`로 합친다(14 문서).

### 4.2 `app_stat_1m` / `app_stat_1h`

```sql
CREATE TABLE {db}.{p}app_stat_1m
(
    app_id            UInt32,
    env               LowCardinality(String),
    kind              Enum8('HTTP' = 1, 'BATCH' = 2, 'MESSAGE' = 3),
    minute            DateTime('UTC'),
    count             SimpleAggregateFunction(sum, UInt64),
    error_count       SimpleAggregateFunction(sum, UInt64),
    sum_elapsed_ms    SimpleAggregateFunction(sum, UInt64),
    max_elapsed_ms    SimpleAggregateFunction(max, UInt32),
    sum_sql_time_ms   SimpleAggregateFunction(sum, UInt64),
    sum_dbc_time_ms   SimpleAggregateFunction(sum, UInt64),
    sum_ext_time_ms   SimpleAggregateFunction(sum, UInt64),
    apdex_satisfied   SimpleAggregateFunction(sum, UInt64),
    apdex_tolerating  SimpleAggregateFunction(sum, UInt64),
    q_elapsed         AggregateFunction(quantilesTDigest(0.5, 0.95, 0.99), UInt32)   -- PoC 결정(A-11)
)
ENGINE = AggregatingMergeTree
PARTITION BY toDate(minute)
ORDER BY (app_id, kind, minute)
TTL minute + INTERVAL 90 DAY
SETTINGS ttl_only_drop_parts = 1, non_replicated_deduplication_window = 1000;

-- deploy/clickhouse/rollups/app_stat_1m.sql
SELECT
    app_id, env, kind, toStartOfMinute(ts) AS minute,
    count()                                        AS count,
    countIf(error = 1)                             AS error_count,
    sum(elapsed_ms)                                AS sum_elapsed_ms,
    max(elapsed_ms)                                AS max_elapsed_ms,
    sum(sql_time_ms)                               AS sum_sql_time_ms,
    sum(dbc_time_ms)                               AS sum_dbc_time_ms,
    sum(ext_time_ms)                               AS sum_ext_time_ms,
    countIf(elapsed_ms <= apdex_t_ms)              AS apdex_satisfied,
    countIf(elapsed_ms > apdex_t_ms AND elapsed_ms <= 4 * apdex_t_ms) AS apdex_tolerating,
    quantilesTDigestState(0.5, 0.95, 0.99)(elapsed_ms) AS q_elapsed
FROM {db}.{p}xlog
{where}
GROUP BY app_id, env, kind, minute;

CREATE MATERIALIZED VIEW {db}.{p}app_stat_1m_mv TO {db}.{p}app_stat_1m AS <rollups/app_stat_1m.sql with {where} = ''>;

CREATE TABLE {db}.{p}app_stat_1h
(
    app_id UInt32, env LowCardinality(String), kind Enum8('HTTP' = 1, 'BATCH' = 2, 'MESSAGE' = 3),
    hour DateTime('UTC'),
    count SimpleAggregateFunction(sum, UInt64), error_count SimpleAggregateFunction(sum, UInt64),
    sum_elapsed_ms SimpleAggregateFunction(sum, UInt64), max_elapsed_ms SimpleAggregateFunction(max, UInt32),
    sum_sql_time_ms SimpleAggregateFunction(sum, UInt64), sum_dbc_time_ms SimpleAggregateFunction(sum, UInt64),
    sum_ext_time_ms SimpleAggregateFunction(sum, UInt64),
    apdex_satisfied SimpleAggregateFunction(sum, UInt64), apdex_tolerating SimpleAggregateFunction(sum, UInt64),
    q_elapsed AggregateFunction(quantilesTDigest(0.5, 0.95, 0.99), UInt32)
)
ENGINE = AggregatingMergeTree
PARTITION BY toYYYYMM(hour)
ORDER BY (app_id, kind, hour)
TTL hour + INTERVAL 365 DAY
SETTINGS ttl_only_drop_parts = 1;

-- deploy/clickhouse/rollups/app_stat_1h.sql  (1m 테이블에 건 MV)
SELECT app_id, env, kind, toStartOfHour(minute) AS hour,
       sum(count) AS count, sum(error_count) AS error_count,
       sum(sum_elapsed_ms) AS sum_elapsed_ms, max(max_elapsed_ms) AS max_elapsed_ms,
       sum(sum_sql_time_ms) AS sum_sql_time_ms, sum(sum_dbc_time_ms) AS sum_dbc_time_ms,
       sum(sum_ext_time_ms) AS sum_ext_time_ms,
       sum(apdex_satisfied) AS apdex_satisfied, sum(apdex_tolerating) AS apdex_tolerating,
       quantilesTDigestMergeState(0.5, 0.95, 0.99)(q_elapsed) AS q_elapsed
FROM {db}.{p}app_stat_1m
{where}
GROUP BY app_id, env, kind, hour;
```

Apdex T가 서비스마다 다르므로 앱 단위 Apdex는 "행에 기록된 각 서비스 T 기준 만족·허용 건수의 합 / 전체"로 정의한다(D-17). T 변경 시점은 PostgreSQL `events(type='apdex_t_change')`로 기록해 UI 마커로 쓴다.

### 4.3 `service_stat_1m` / `service_stat_1h`

`app_stat`과 같은 컬럼에 `service_id UInt64`, `version_id UInt64`를 더하고 정렬 키 `(app_id, kind, service_id, version_id, minute)`. SELECT는 `GROUP BY app_id, env, kind, service_id, version_id, minute`. 1h는 `(app_id, kind, service_id, version_id, hour)`, 365일. 버전별 분리(F-A21)는 이 키로 끝난다. 버전 무시 조회는 `version_id`를 GROUP BY에서 빼고 합친다.

### 4.4 `error_stat_1m`

```sql
CREATE TABLE {db}.{p}error_stat_1m
(
    app_id         UInt32,
    env            LowCardinality(String),
    kind           Enum8('HTTP' = 1, 'BATCH' = 2, 'MESSAGE' = 3),
    error_group_id UInt64,
    service_id     UInt64,                                  -- 영향 서비스 분포용
    version_id     UInt64,                                  -- 어느 버전부터(S-13)
    minute         DateTime('UTC'),
    count          SimpleAggregateFunction(sum, UInt64),
    first_ts       SimpleAggregateFunction(min, DateTime64(3, 'UTC')),
    last_ts        SimpleAggregateFunction(max, DateTime64(3, 'UTC'))
)
ENGINE = AggregatingMergeTree
PARTITION BY toDate(minute)
ORDER BY (app_id, error_group_id, service_id, version_id, minute)
TTL minute + INTERVAL 90 DAY
SETTINGS ttl_only_drop_parts = 1, non_replicated_deduplication_window = 1000;

-- rollups/error_stat_1m.sql
SELECT app_id, env, kind, error_group_id, service_id, version_id, toStartOfMinute(ts) AS minute,
       count() AS count, min(ts) AS first_ts, max(ts) AS last_ts
FROM {db}.{p}xlog
WHERE error = 1 AND error_group_id != 0 {where}
GROUP BY app_id, env, kind, error_group_id, service_id, version_id, minute;
```

그룹 메타(대표 스택·메시지 패턴·최초/최근)는 PostgreSQL `error_groups`(12.4). "관련 트랜잭션"은 `xlog.error_group_id` 필터로 30일 범위에서 찾는다.

### 4.5 `sql_stat_1m` / `sql_stat_1h`, `ext_stat_1m` / `ext_stat_1h`

```sql
CREATE TABLE {db}.{p}sql_stat_1m
(
    app_id       UInt32,
    env          LowCardinality(String),
    kind         Enum8('HTTP' = 1, 'BATCH' = 2, 'MESSAGE' = 3),
    sql_id       UInt64,
    db_id        UInt64,
    minute       DateTime('UTC'),
    count        SimpleAggregateFunction(sum, UInt64),
    error_count  SimpleAggregateFunction(sum, UInt64),
    sum_ms       SimpleAggregateFunction(sum, UInt64),
    sum_exec_ms  SimpleAggregateFunction(sum, UInt64),
    sum_fetch_ms SimpleAggregateFunction(sum, UInt64),
    max_ms       SimpleAggregateFunction(max, UInt32),
    fetch_rows   SimpleAggregateFunction(sum, UInt64),
    buckets      SimpleAggregateFunction(sumForEach, Array(UInt64))   -- 15 버킷 합
)
ENGINE = AggregatingMergeTree
PARTITION BY toDate(minute)
ORDER BY (app_id, kind, sql_id, db_id, minute)
TTL minute + INTERVAL 90 DAY
SETTINGS ttl_only_drop_parts = 1, non_replicated_deduplication_window = 1000;

-- rollups/sql_stat_1m.sql
SELECT app_id, env, kind, sql_id, db_id, toStartOfMinute(ts) AS minute,
       sum(count) AS count, sum(error_count) AS error_count,
       sum(sum_ms) AS sum_ms, sum(sum_exec_ms) AS sum_exec_ms, sum(sum_fetch_ms) AS sum_fetch_ms,
       max(max_ms) AS max_ms, sum(fetch_rows) AS fetch_rows,
       sumForEach(arrayMap(x -> toUInt64(x), buckets)) AS buckets
FROM {db}.{p}sql_stat_raw
{where}
GROUP BY app_id, env, kind, sql_id, db_id, minute;
```

`ext_stat_1m`은 `host_id` 키, `timeout_count`·`connect_fail_count` 컬럼 추가, 나머지 동일. 1h 테이블은 `minute → hour`, `PARTITION BY toYYYYMM(hour)`, 365일, 1m에 건 MV.

버킷 기반 백분위(14 문서가 쓰는 함수):

```sql
-- p95 추정: 누적 합이 0.95*count를 넘는 버킷 안에서 선형 보간. 경계는 2장 상수.
WITH sumForEach(buckets) AS b, arraySum(b) AS n
SELECT argosBucketQuantile(b, 0.95)   -- 서버 측 UDF 또는 arrayCumSum + arrayFirstIndex 조합(14 문서 7장 예시)
```

오차는 버킷 폭에 묶인다(100~200ms 구간이면 최대 ±50ms). 이것은 "SQL·외부 호출 p95는 버킷 추정값"으로 UI 툴팁에 명시한다. 트랜잭션 응답시간 백분위(app·service)는 tDigest 상태로 값 오차 기준을 지킨다.

### 4.6 `metric_1m` / `metric_1h`

```sql
CREATE TABLE {db}.{p}metric_1m
(
    app_id       UInt32,
    env          LowCardinality(String),
    instance_id  UInt32,
    name         LowCardinality(String),
    kind         Enum8('gauge' = 1, 'counter' = 2),
    minute       DateTime('UTC'),
    sample_count SimpleAggregateFunction(sum, UInt32),
    avg_sum      SimpleAggregateFunction(sum, Float64),     -- avg = avg_sum / sample_count
    min_v        SimpleAggregateFunction(min, Float64),
    max_v        SimpleAggregateFunction(max, Float64),
    last_v       SimpleAggregateFunction(anyLast, Float64),  -- counter의 누적값 최종치
    increase     SimpleAggregateFunction(sum, Float64)      -- counter: sum(delta). gauge: 0
)
ENGINE = AggregatingMergeTree
PARTITION BY toDate(minute)
ORDER BY (app_id, instance_id, name, minute)
TTL minute + INTERVAL 90 DAY
SETTINGS ttl_only_drop_parts = 1, non_replicated_deduplication_window = 1000;

-- rollups/metric_1m.sql
SELECT app_id, env, instance_id, name, kind, toStartOfMinute(ts) AS minute,
       count() AS sample_count, sum(value) AS avg_sum, min(value) AS min_v, max(value) AS max_v,
       anyLast(value) AS last_v, sum(delta) AS increase
FROM {db}.{p}metric
{where}
GROUP BY app_id, env, instance_id, name, kind, minute;
```

`metric_1h`는 `hour`, `toYYYYMM`, 365일. gauge는 avg(=avg_sum/sample_count)·min·max, counter는 `increase`(기간 증가분)와 `last_v`로 조회한다(D-16). `sample_count=0` 버킷은 존재하지 않으므로 API가 시간 격자를 채우며 null을 넣는다. 카운터 리셋 보정은 에이전트가 `delta`를 계산할 때 처리한다(이전 샘플보다 작으면 현재값을 증가분으로).

### 4.7 `instance_stat_1m` (추가 롤업)

인스턴스 비교 표(F-U20)와 인스턴스 타일은 인스턴스별 TPS·p95·에러율이 필요하다. `service_stat`에는 인스턴스가 없으므로 `(app_id, instance_id, kind, minute)` 키의 롤업을 하나 더 둔다. 컬럼은 `app_stat_1m`과 동일(`instance_id` 추가), `rollups/instance_stat_1m.sql`은 `GROUP BY app_id, env, instance_id, kind, minute`. 1h는 두지 않는다(비교는 단기 조회). 이로써 `xlog` insert당 1m MV는 app·service·instance·error 4개다.

## 5. 중복 제거(D-13)

### 5.1 토큰 유도(DSN-13-07)

```
WAL 레코드:   RECORD{seq, instance_id, batch_seq, table, payload}            -- 수신 즉시 append, 그룹 fsync 10ms, 그 뒤 ack
라이터:       2초 또는 50,000행마다 테이블별로 레코드를 모아 블록 구성
              BLOCK{block_id(UUIDv7), table, first_seq, last_seq, row_count} 를 WAL에 append   -- insert 전
              INSERT INTO {table} (...) SETTINGS insert_deduplication_token = 'argos:{table}:{block_id}',
                                                  deduplicate_blocks_in_dependent_materialized_views = 1
              성공 → ACK{block_id} append. 실패 → 재시도(같은 block_id) 3회 → 보류(스풀 = WAL 자체)
재시작:       WAL에서 ACK 없는 BLOCK을 찾아 같은 block_id로 재삽입. BLOCK 없이 끝난 RECORD는 새 블록으로 구성
```

- `non_replicated_deduplication_window = 1000`: 테이블당 최근 1,000개 블록 해시를 기억한다. 2초 주기면 약 33분. 재시작 복구는 이 창 안에서 끝나야 하므로 WAL 보류 블록이 1,000개를 넘기 전(약 30분)에 복구하지 못하면 라이터는 재적재를 멈추고 알림을 낸다(서버 12 문서 규칙). 메모리 비용은 블록당 수십 바이트, 무시 가능.
- `deduplicate_blocks_in_dependent_materialized_views = 1`: 원본 블록이 중복으로 버려지면 MV 결과 블록도 버려진다. MV 하나가 실패해 원본만 커밋된 경우, 재시도 시 원본은 중복으로 버려지고 **MV는 각 MV 테이블의 자기 창에서 토큰으로 판단**하므로 성공한 MV는 버려지고 실패했던 MV는 들어간다. 이것이 D-13 "비대칭 중복 0"의 근거이며 PoC Q15로 실측한다.
- 에이전트 재전송(`batch_seq` 중복)은 WAL append 전에 `(instance_id, batch_seq)` 윈도(1,000)로 거른다(12 문서). WAL 이후의 중복은 토큰이 막는다. 두 층이 겹쳐야 "에이전트 재전송 + 서버 재시작" 조합을 덮는다.

### 5.2 테스트(18 문서로 전달)

| 시나리오 | 기대 |
|---|---|
| 같은 block_id로 블록 100회 재삽입 | 원본·모든 1m·1h 롤업 행 수 불변 |
| insert 성공 직후 `kill -9` → 재기동 → 재삽입 | 행 수 불변, ACK 기록 |
| MV 1개를 일부러 깨뜨린(권한 제거) 상태로 insert → 복구 → 재삽입 | 원본·정상 MV 불변, 깨졌던 MV만 채워짐 |
| 에이전트가 `batch_seq` 재전송 | WAL에 1건만 기록 |

## 6. 조회 패턴(14 API가 구현)

### 6.1 소스 선택 규칙

| 조회 범위 | 소스 | 버킷 |
|---|---|---|
| ≤ 6시간 | 원본(`xlog`, `metric`, `*_stat_raw`) | 10초·30초·1분(범위/360) |
| ≤ 7일 | `*_1m` | 1분·5분·15분 |
| > 7일 | `*_1h` | 1시간·6시간·1일 |
| 공통 | 마지막 2분은 항상 원본에서 더해 합친다(늦은 도착 보정). 응답에 `source: raw|1m|1h`를 넣는다 | |

범위 끝이 분 경계가 아니면 양 끝 부분 분을 원본에서 계산한다(D-03). 원본 보존(30일)을 넘는 범위는 롤업이 기준값이고 응답에 `rawRetentionExceeded: true`를 넣어 UI가 표시한다.

### 6.2 엔드포인트 ↔ 테이블 매핑

| 경로(14 문서) | 소스 | 제한 |
|---|---|---|
| `GET /overview` (전체 현황) | `app_stat_1m`(최근 1시간, 앱 전부), `metric_1m`(인스턴스 상태는 PG) | 앱 ≤ 500 |
| `GET /apps/{id}/dashboard` | 6.1 규칙으로 `xlog` / `app_stat_1m` / `app_stat_1h`, `metric*` | 버킷 ≤ 720, 범위 ≤ 1년 |
| `GET /apps/{id}/xlog` | `xlog` | 범위 ≤ 24h, 결과 ≤ 100,000점(초과 시 결정적 샘플링, `shown/total`) |
| `POST /apps/{id}/transactions/search` | `xlog`(+ `idx_sql`, `idx_user`, `idx_request`) | 범위 ≤ 24h(user·request id 검색은 ≤ 1h 기본), 결과 ≤ 1,000 |
| `GET /transactions/{tx_id}` | `xlog` 1행 + `profile_step` | ts는 `tx_id`에서 복원, `?app_id`·`?ts` 힌트 선택 |
| `GET /traces/{trace_id}` | `xlog`(`idx_trace`) + `profile_step` | 시간 힌트 없으면 24h(Argos 생성 id는 상위 4B로 힌트), 트랜잭션 ≤ 500 |
| `GET /apps/{id}/services/stats`, `/services/{service_id}` | `service_stat_1m/1h` | Top N ≤ 500, 버전 분리 옵션 |
| `GET /apps/{id}/sql/stats`, `/sql/{sql_id}` | `sql_stat_raw/1m/1h`, 관련 트랜잭션은 `xlog.sql_ids`(A-10) 또는 `profile_step` | Top N ≤ 500 |
| `GET /apps/{id}/external/stats`, `/external/{host_id}` | `ext_stat_raw/1m/1h` | Top N ≤ 500 |
| `GET /apps/{id}/errors/stats`, `/errors/{group_id}` | `error_stat_1m`, 메타는 PG `error_groups`, 관련 트랜잭션은 `xlog.error_group_id` | |
| `GET /instances/{id}/metrics` | `metric` / `metric_1m` / `metric_1h` | 시리즈 ≤ 50, 포인트 ≤ 2,000/시리즈 |
| `GET /apps/{id}/instances/compare` | `xlog`(≤6h) 또는 `service_stat_1m`를 인스턴스로 재집계할 수 없으므로 **`instance_stat_1m`**(4.7) | |
| `GET /admin/storage/health` | `system.parts`, `system.merges`, `system.events`, `system.mutations`, 디스크 | 관리자 |

### 6.3 대표 SQL

```sql
-- (1) 대시보드 원본 구간 10초 버킷(≤6h). ts 범위 + kind. 끝 2분 보정은 API가 분 경계로 쪼개 호출.
SELECT toStartOfInterval(ts, INTERVAL 10 SECOND) AS b,
       count() AS cnt, countIf(error = 1) AS err,
       avg(elapsed_ms) AS avg_ms, quantilesTDigest(0.5, 0.95, 0.99)(elapsed_ms) AS q
FROM {db}.{p}xlog
WHERE app_id = {app} AND kind = 'HTTP' AND ts >= {from} AND ts < {to}
GROUP BY b ORDER BY b
SETTINGS max_execution_time = 10, max_rows_to_read = 2000000000;

-- (2) 대시보드 롤업 구간(≤7d). 분 버킷 합치기.
SELECT toStartOfInterval(minute, INTERVAL {step} MINUTE) AS b,
       sum(count) AS cnt, sum(error_count) AS err,
       sum(sum_elapsed_ms) / sum(count) AS avg_ms,
       quantilesTDigestMerge(0.5, 0.95, 0.99)(q_elapsed) AS q
FROM {db}.{p}app_stat_1m
WHERE app_id = {app} AND kind = 'HTTP' AND minute >= {from} AND minute < {to}
GROUP BY b ORDER BY b;

-- (3) XLog 점. 총량 선조회 후 N = ceil(total / 100000). 결정적 샘플링(A-5): 에러·지연은 전부.
SELECT ts, tx_id, elapsed_ms, error, service_id, instance_id, kind
FROM {db}.{p}xlog
WHERE app_id = {app} AND kind = 'HTTP' AND ts >= {from} AND ts < {to}
  AND (error = 1 OR elapsed_ms >= {slow_ms} OR cityHash64(tx_id) % {N} = 0)
ORDER BY ts
SETTINGS max_result_rows = 120000, result_overflow_mode = 'break';

-- (4) 드래그 검색. PREWHERE로 ts·elapsed 범위를 먼저 거른다.
SELECT ts, tx_id, service_id, elapsed_ms, http_status, error_class_id, instance_id,
       sql_count, sql_time_ms, ext_count, ext_time_ms, user_id, user_id_mode, profile_captured, kind, version_id
FROM {db}.{p}xlog
PREWHERE app_id = {app} AND ts >= {from} AND ts < {to} AND elapsed_ms BETWEEN {ymin} AND {ymax}
WHERE kind = 'HTTP' [AND service_id IN (...)] [AND error = 1] [AND instance_id IN (...)]
ORDER BY elapsed_ms DESC
LIMIT 1000;

-- (5) tx_id 단건. ts를 id에서 복원해 ±2초. app_id 힌트가 없으면 전체 앱(파티션·정렬 키로 충분히 좁다).
WITH toDateTime64((bitShiftRight({tx}, 23) + 1767225600000) / 1000, 3, 'UTC') AS t0
SELECT * FROM {db}.{p}xlog
WHERE [app_id = {app} AND] ts BETWEEN t0 - INTERVAL 2 SECOND AND t0 + INTERVAL 2 SECOND AND tx_id = {tx}
LIMIT 2;                                     -- 2행이면 충돌 감지(실질적 불가)
SELECT * FROM {db}.{p}profile_step WHERE app_id = {app} AND tx_id = {tx} ORDER BY seq;

-- (6) trace_id. Argos 생성 id는 상위 4B Unix 초 → ±1시간 힌트. 외부 id는 24시간.
SELECT app_id, tx_id, ts, span_id, parent_span_id, caller_app_id, service_id, elapsed_ms, error, instance_id
FROM {db}.{p}xlog
WHERE ts >= {hint_from} AND ts < {hint_to} AND trace_id = {trace}
LIMIT 500;

-- (7) 서비스 Top N 1일(1m 롤업 병합), 버전 분리 옵션.
SELECT service_id [, version_id],
       sum(count) AS cnt, sum(error_count) AS err,
       sum(sum_elapsed_ms) / sum(count) AS avg_ms, max(max_elapsed_ms) AS max_ms,
       quantilesTDigestMerge(0.5, 0.95, 0.99)(q_elapsed) AS q,
       (sum(apdex_satisfied) + sum(apdex_tolerating) / 2) / sum(count) AS apdex
FROM {db}.{p}service_stat_1m
WHERE app_id = {app} AND kind = 'HTTP' AND minute >= {from} AND minute < {to}
GROUP BY service_id [, version_id]
ORDER BY {sort} DESC LIMIT 500;

-- (8) SQL Top N 1일(전수). 버킷 합으로 p95 추정.
SELECT sql_id, db_id, sum(count) AS cnt, sum(error_count) AS err,
       sum(sum_ms) AS total_ms, sum(sum_ms) / sum(count) AS avg_ms, max(max_ms) AS max_ms,
       sum(fetch_rows) AS rows, sumForEach(buckets) AS b
FROM {db}.{p}sql_stat_1m
WHERE app_id = {app} AND kind = 'HTTP' AND minute >= {from} AND minute < {to}
GROUP BY sql_id, db_id ORDER BY total_ms DESC LIMIT 500;

-- (9) 에러 그룹 → 관련 트랜잭션(30일, 원본).
SELECT ts, tx_id, service_id, instance_id, version_id, elapsed_ms
FROM {db}.{p}xlog
WHERE app_id = {app} AND ts >= {from} AND ts < {to} AND error_group_id = {g}
ORDER BY ts DESC LIMIT 10;

-- (10) 인스턴스 메트릭 7일(1m). 시간 격자는 API가 채움(결측 null).
SELECT minute, name, kind, sample_count, avg_sum / sample_count AS avg_v, min_v, max_v, last_v, increase
FROM {db}.{p}metric_1m
WHERE app_id = {app} AND instance_id = {inst} AND name IN ({names}) AND minute >= {from} AND minute < {to}
ORDER BY name, minute;

-- (11) user_id 검색(≤1h 기본, 최대 24h). hashed 모드면 API가 앱 솔트로 해시한 값을 넘긴다. bloom(idx_user) 활용.
SELECT ts, tx_id, service_id, elapsed_ms, error, instance_id
FROM {db}.{p}xlog
WHERE app_id = {app} AND ts >= {from} AND ts < {to} AND user_id = {uid}
ORDER BY ts DESC LIMIT 1000;

-- (12) 'SQL 포함' 검색(A-10 채택 시).
SELECT ts, tx_id, service_id, elapsed_ms, sql_count, sql_time_ms
FROM {db}.{p}xlog
WHERE app_id = {app} AND ts >= {from} AND ts < {to} AND has(sql_ids, {sql_id})
ORDER BY ts DESC LIMIT 1000;

-- (13) D-03 정합 검사(전날, 야간 ops). 결과 0행이 정상.
SELECT m, a, s
FROM (SELECT toStartOfMinute(ts) AS m, count() AS c FROM {db}.{p}xlog WHERE toDate(ts) = {d} AND kind='HTTP' GROUP BY m) x
FULL JOIN (SELECT minute AS m, sum(count) AS a FROM {db}.{p}app_stat_1m WHERE toDate(minute) = {d} AND kind='HTTP' GROUP BY m) y USING m
FULL JOIN (SELECT minute AS m, sum(count) AS s FROM {db}.{p}service_stat_1m WHERE toDate(minute) = {d} AND kind='HTTP' GROUP BY m) z USING m
WHERE x.c != y.a OR x.c != z.s;

-- (14) D-12 정합 검사(전날).
SELECT sum(sql_count) AS x, (SELECT sum(count) FROM {db}.{p}sql_stat_1m WHERE toDate(minute) = {d}) AS s
FROM {db}.{p}xlog WHERE toDate(ts) = {d}
HAVING x != s;
```

모든 조회는 `argos_reader`로 실행하고 쿼리 설정(`max_execution_time=10`, `max_rows_to_read`, `max_result_rows`, `max_memory_usage`)을 쿼리마다 넘긴다. 범위 상한·행 제한은 API 계층이 거부한다.

## 7. ClickHouse 서버·사용자 설정

### 7.1 메모리 예산(S 토폴로지, 16GB 1대)

| 구성 요소 | 예산 | 설정 |
|---|---|---|
| OS·페이지 캐시 | 2 GiB | |
| PostgreSQL | 1.5 GiB | `shared_buffers=512MB`, `work_mem=16MB`, `max_connections=50` |
| argos-server | 5 GiB | `-Xmx4g`, 오프힙(Netty·gRPC·WAL 버퍼) 1 GiB |
| ClickHouse | 7.5 GiB | `max_server_memory_usage=8053063680`, `mark_cache_size=536870912`, `uncompressed_cache_size=0` |

설치 스크립트(17 문서)는 호스트 RAM을 읽어 같은 비율(ClickHouse 47%, 서버 31%, PG 9%, OS 13%)로 산출한다(N-18).

### 7.2 `deploy/clickhouse/config.d/argos.xml`

```xml
<clickhouse>
  <timezone>UTC</timezone>
  <max_server_memory_usage>8053063680</max_server_memory_usage>
  <max_concurrent_queries>50</max_concurrent_queries>
  <mark_cache_size>536870912</mark_cache_size>
  <uncompressed_cache_size>0</uncompressed_cache_size>
  <background_pool_size>4</background_pool_size>
  <background_schedule_pool_size>8</background_schedule_pool_size>
  <max_partition_size_to_drop>0</max_partition_size_to_drop>     <!-- 비상 보존 단축이 큰 파티션도 DROP 가능 -->
  <max_table_size_to_drop>0</max_table_size_to_drop>
  <merge_tree>
    <ttl_only_drop_parts>1</ttl_only_drop_parts>
    <merge_with_ttl_timeout>14400</merge_with_ttl_timeout>          <!-- TTL 주기 4시간(N-08) -->
    <parts_to_delay_insert>300</parts_to_delay_insert>
    <parts_to_throw_insert>1000</parts_to_throw_insert>
  </merge_tree>
  <storage_configuration>
    <disks><default><keep_free_space_bytes>10737418240</keep_free_space_bytes></default></disks>
  </storage_configuration>
  <listen_host>127.0.0.1</listen_host>                               <!-- tar 설치. Compose는 내부 네트워크만 -->
  <logger><level>warning</level></logger>
  <query_log><ttl>event_date + INTERVAL 7 DAY</ttl></query_log>
  <part_log remove="1"/>
  <text_log remove="1"/>
</clickhouse>
```

### 7.3 `deploy/clickhouse/users.d/argos.xml`

```xml
<clickhouse>
  <profiles>
    <argos_writer_profile>
      <async_insert>0</async_insert>
      <max_insert_threads>1</max_insert_threads>
      <max_memory_usage>2147483648</max_memory_usage>
      <insert_deduplication_token_support>1</insert_deduplication_token_support>
    </argos_writer_profile>
    <argos_reader_profile>
      <readonly>1</readonly>
      <max_memory_usage>3221225472</max_memory_usage>              <!-- 3 GiB / 쿼리 -->
      <max_execution_time>10</max_execution_time>
      <max_threads>4</max_threads>
      <max_rows_to_read>2000000000</max_rows_to_read>
      <max_bytes_to_read>21474836480</max_bytes_to_read>
      <max_result_rows>200000</max_result_rows>
      <result_overflow_mode>break</result_overflow_mode>
      <max_concurrent_queries_for_user>20</max_concurrent_queries_for_user>
      <joined_subquery_requires_alias>0</joined_subquery_requires_alias>
    </argos_reader_profile>
    <argos_migrator_profile>
      <max_execution_time>3600</max_execution_time>
      <mutations_sync>0</mutations_sync>
    </argos_migrator_profile>
  </profiles>
  <users>
    <default><password_sha256_hex from_env="ARGOS_CH_DEFAULT_PASSWORD_SHA256"/><networks><ip>127.0.0.1</ip></networks></default>
    <argos_writer>
      <password_sha256_hex from_env="ARGOS_CH_WRITER_PASSWORD_SHA256"/>
      <profile>argos_writer_profile</profile>
      <networks><ip>::/0</ip></networks>                            <!-- Compose 내부 네트워크. tar는 127.0.0.1 -->
      <grants><query>GRANT INSERT ON {db}.*</query></grants>
    </argos_writer>
    <argos_reader>
      <password_sha256_hex from_env="ARGOS_CH_READER_PASSWORD_SHA256"/>
      <profile>argos_reader_profile</profile>
      <networks><ip>::/0</ip></networks>
      <grants>
        <query>GRANT SELECT ON {db}.*</query>
        <query>GRANT SELECT ON system.parts, system.merges, system.mutations, system.events, system.metrics, system.asynchronous_metrics, system.query_log, system.disks</query>
      </grants>
    </argos_reader>
    <argos_migrator>
      <password_sha256_hex from_env="ARGOS_CH_MIGRATOR_PASSWORD_SHA256"/>
      <profile>argos_migrator_profile</profile>
      <networks><ip>::/0</ip></networks>
      <grants>
        <query>GRANT CREATE, DROP, ALTER, INSERT, SELECT ON {db}.*</query>   <!-- 마이그레이션·재구축·삭제 뮤테이션·비상 DROP -->
      </grants>
    </argos_migrator>
  </users>
</clickhouse>
```

비밀번호는 설치 시 CSPRNG로 생성해 `.env`에 쓰고(SEC-74) 해시로 주입한다. 서버의 야간 정합·재구축·삭제 API·비상 보존 단축은 `argos_migrator` 연결을 쓰고, 일반 적재는 `argos_writer`, 조회는 `argos_reader`만 쓴다(SEC-76).

### 7.4 적재 규칙(12 서버 라이터가 준수)

| 항목 | 값 |
|---|---|
| insert 단위 | 테이블당 2초 또는 50,000행 중 먼저. `INSERT INTO t (col, …) FORMAT RowBinaryWithNamesAndTypes` 명시 컬럼 |
| 토큰 | `argos:{table}:{block_id}`(5장) |
| 동시 insert | 테이블당 1 연결(순서 보장), 전체 ≤ 8 연결 |
| parts 경고 | 활성 parts 파티션당 ≥ 300 또는 `DelayedInserts > 0` → WARN·수집 상태 패널. ≥ 1,000 → 심각 알림 |
| 적재 지연 | `argos_ingest_lag_seconds`(수신 → ack) p99 ≤ 5초(N-06). 지연이 30초를 넘으면 기본 규칙 알림 |
| 실패 | 재시도 3회(1·2·4초) → WAL 보류. ClickHouse 30분 이상 불가 시 WAL 상한(10GiB) 도달 전 알림 |

### 7.5 운영 지표(F-S23, 서버 자기 메트릭으로 수집)

| 지표 | 소스 | 용도 |
|---|---|---|
| `argos_ch_parts_active{table,partition}` 최대 | `system.parts WHERE active` | too many parts 조기 경고 |
| `argos_ch_merges_running`, 진행률 | `system.merges` | 머지 적체 |
| `argos_ch_inserted_rows_total`, `argos_ch_delayed_inserts_total`, `argos_ch_rejected_inserts_total`, `argos_ch_merged_rows_total` | `system.events` | 적재 건강 |
| `argos_ch_memory_tracking_bytes` | `system.metrics MemoryTracking` | 7.5GiB 상한 대비 |
| `argos_ch_disk_free_bytes`, `argos_ch_disk_used_ratio` | `system.disks` | 80/90/95% 임계 |
| `argos_ch_oldest_unmerged_part_age_seconds` | `system.parts min(modification_time)` | 머지 정체 |
| `argos_ch_query_duration_p99_seconds{user}` | `system.query_log`(최근 5분) | 조회 성능 |
| `argos_ch_ttl_last_run_seconds` | `system.parts`의 TTL 정보 또는 러너 기록 | TTL 동작 확인 |
| `argos_ch_mutations_pending` | `system.mutations WHERE NOT is_done` | 삭제 API 진행 |
| `argos_dictionary_unresolved_total` | 서버 사전 캐시 | 사전 미해결 |

## 8. 보존·디스크

| 테이블 | 기본 보존 | 설정 키 |
|---|---|---|
| `xlog` | 30일 | `argos.storage.retention.xlog-days` |
| `profile_step` | 7일 | `argos.storage.retention.profile-days` |
| `metric`, `sql_stat_raw`, `ext_stat_raw` | 14일 | `argos.storage.retention.raw-days` |
| `*_1m`(`instance_stat_1m` 포함) | 90일 | `argos.storage.retention.rollup-1m-days` |
| `*_1h` | 365일 | `argos.storage.retention.rollup-1h-days` |

- TTL 삭제는 `merge_with_ttl_timeout=14400`(4시간) 주기로 `ttl_only_drop_parts=1`에 따라 part 단위 drop으로만 일어난다. 하루 파티션이므로 TTL 경계와 part 경계가 맞아 재작성이 없다.
- **보존 변경 절차**(설정 파일 변경 → 서버 기동 시 러너 적용 → `audit_logs`): 연장은 `ALTER TABLE … MODIFY TTL … SETTINGS materialize_ttl_after_modify = 0`(기존 part 재작성 없음), 단축은 `MODIFY TTL` 뒤 새 보존 밖 파티션을 `ALTER TABLE … DROP PARTITION`으로 즉시 제거. 러너는 변경 전후 값을 `ch_schema_history`에 `retention_change`로 기록한다.
- **디스크 임계**(F-S23): 80% 경고(알림 템플릿 "서버 디스크"). 90% 비상 보존 단축: `profile_step` 가장 오래된 파티션 → `metric`·`*_stat_raw` 가장 오래된 파티션 → `xlog` 가장 오래된 파티션 순으로 85% 아래가 될 때까지 DROP하고, 수집 상태 패널·알림에 "비상 보존 단축(테이블·날짜)"을 표시하며 `events(type='retention_emergency')`를 남긴다. 95% 이상이면 ClickHouse insert를 멈추고 WAL만 쓴다(상한 10GiB), WAL 초과분은 서버 드롭으로 센다(D-06).
- **원본 보존 밖 롤업**: 31~90일 구간은 롤업만 있다. API는 `rawRetentionExceeded`를 응답에 넣고 UI는 "원본 보존 기간 경과: 상세·관련 트랜잭션 없음"을 표시한다(D-03 적용 범위).

## 9. 개인정보 삭제(SEC-33, F-S32)

```
DELETE /admin/privacy/subjects?user_id=…|ip=…&from&to   (관리자, 감사 로그)
  1. 대상 일 파티션 목록 = [from, to] ∩ 보존 범위
  2. 파티션마다 뮤테이션 발행(argos_migrator):
       ALTER TABLE {db}.{p}xlog         DELETE WHERE toDate(ts) = :d AND app_id = :app AND user_id = :uid   (또는 remote_ip = :ip)
       ALTER TABLE {db}.{p}profile_step DELETE WHERE toDate(ts) = :d AND app_id = :app
           AND tx_id IN (SELECT tx_id FROM {db}.{p}xlog WHERE toDate(ts) = :d AND app_id = :app AND user_id = :uid)
     (profile_step 뮤테이션은 xlog 뮤테이션 **이전**에 발행한다. 순서가 바뀌면 서브쿼리가 빈다)
  3. 작업 레코드 PostgreSQL privacy_jobs(id, subject_kind, subject_hash, from, to, mutation_ids[], status, created_by, created_at, finished_at)
  4. 상태는 system.mutations(mutation_id, is_done, latest_fail_reason)로 폴링해 갱신. GET /admin/privacy/jobs/{id}
  5. 롤업(app/service/error/sql/ext/metric)은 집계값이라 대상 밖. 응답과 문서에 명시.
```

- `hashed` 모드의 user_id는 API가 앱 솔트로 해시해 비교한다. `plain`·`hashed` 혼재 기간이면 둘 다 조건에 넣는다.
- 비용: 뮤테이션은 파티션의 모든 part를 재작성한다. 5,000 TPS 하루 파티션(약 30GB)은 수십 분이 걸릴 수 있다. 기본 동시 뮤테이션 2개, 사용자 요청은 큐에 넣는다. PoC Q18에 뮤테이션 소요 측정을 포함한다.
- `privacy_jobs`는 `subject_hash`(요청 값의 SHA-256)만 저장한다. 삭제 대상 원문을 PG에 남기지 않는다.

## 10. 백업·복구(N-20, SEC-84)

| 대상 | 방식 | 주기·보존 | RPO/RTO |
|---|---|---|---|
| PostgreSQL | `pg_dump -Fc` → `age`(또는 GPG) 암호화 옵션 → `backup/pg/`(0600) | 일 1회, 7일 | 24h / 30분 |
| ClickHouse(선택, 기본 off) | `BACKUP DATABASE {db} TO Disk('backups', '{date}') SETTINGS base_backup = Disk('backups', '{prev}')` 증분, 대상은 외부 디스크(NAS·S3 호환 `S3(...)`) | 일 1회, 7일 | 24h / 데이터량 비례 |
| WAL | 백업 대상 아님(복구 후 에이전트 재접속으로 자가 복구) | | |
| 마스터 키·설정 토큰 | `conf/master.key`를 PG 백업과 **별도 경로**에 보관(같은 묶음이면 암호문이 무의미) | | |

- 같은 디스크에 백업하지 않는다(DSN-13-16). ClickHouse 백업이 꺼진 상태의 복구는 "PG 복원 → 서버 기동 → 에이전트 재접속 → 통계는 재수집부터"다. 운영 매뉴얼에 "ClickHouse 데이터는 재구축 가능한 텔레메트리이며 기본 백업 대상이 아니다"를 명시한다.
- 복구 시점 불일치: PG가 CH보다 과거면 새로 생긴 `apps.id`·`instances.id`를 모르는 행이 CH에 있다 → UI는 "알 수 없는 앱/인스턴스(id)"로 표시한다. 사전은 내용 해시라 에이전트 재접속 시 미해결 id를 다시 받아 채운다(DSN-13-09). CH가 PG보다 과거면 롤업 재구축 CLI로 보존 내 날짜를 다시 만든다.
- 복구 리허설(Gate 2): 비참여자가 문서대로 PG 복원 + (켜져 있다면) CH 복원 + 마스터 키 복구를 1시간 안에.

## 11. 확장 경로

| 단계 | 변경 | 설계상 준비 |
|---|---|---|
| 단일 노드(1.0) | `MergeTree`, 로컬 디스크 | 테이블·DB 이름 설정화, 조회 계층은 이름만 사용 |
| 복제(1.x) | `ReplicatedMergeTree` + ClickHouse Keeper(3노드 또는 내장) | 전환 후보: (a) 데이터 디렉터리에 `convert_to_replicated` 플래그 후 재기동(버전 확인), (b) 새 Replicated 테이블 생성 → `ATTACH PARTITION FROM` → 이름 교체. `non_replicated_deduplication_window`는 Replicated에서 `replicated_deduplication_window`로 교체 |
| 샤딩(1.x) | `Distributed` 테이블을 조회용 이름으로, 샤드 로컬 테이블은 `_local` 접미 | 조회 SQL은 변경 없음. 라이터는 샤드 키 `app_id`로 직접 쓰기 |

A-9(1.0부터 Replicated + Keeper)는 "설치 구성 요소 +1(Keeper)과 운영 복잡도" 때문에 1.0은 MergeTree로 두고, 전환 절차를 17 문서 런북에 둔다.

## 12. PostgreSQL 스키마(스키마 `argos`, Flyway `V__` 관리)

```sql
CREATE SCHEMA IF NOT EXISTS argos;
SET search_path TO argos;

-- 12.1 인증·권한
CREATE TABLE users (
  id            UUID PRIMARY KEY,
  username      VARCHAR(64) NOT NULL UNIQUE,
  display_name  VARCHAR(128),
  password_hash VARCHAR(100) NOT NULL,            -- bcrypt cost 12 (SEC-48)
  role          VARCHAR(16) NOT NULL CHECK (role IN ('ADMIN','OPERATOR','VIEWER')),
  enabled       BOOLEAN NOT NULL DEFAULT TRUE,
  must_change_password BOOLEAN NOT NULL DEFAULT TRUE,
  failed_logins INT NOT NULL DEFAULT 0,
  locked_until  TIMESTAMPTZ,
  kiosk_allowed BOOLEAN NOT NULL DEFAULT FALSE,   -- F-U21 긴 세션 허용(관리자 승인)
  kiosk_ip_allowlist CIDR[],
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE user_sessions (                      -- SEC-42
  id            UUID PRIMARY KEY,
  user_id       UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  refresh_hash  CHAR(64) NOT NULL UNIQUE,         -- SHA-256(refresh token)
  issued_at     TIMESTAMPTZ NOT NULL,
  last_seen_at  TIMESTAMPTZ NOT NULL,
  expires_at    TIMESTAMPTZ NOT NULL,             -- 절대 만료(24h 또는 kiosk 설정)
  revoked_at    TIMESTAMPTZ,
  user_agent    VARCHAR(256),
  ip            INET,
  kiosk         BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE INDEX ix_user_sessions_user ON user_sessions(user_id) WHERE revoked_at IS NULL;

-- 12.2 앱·인스턴스·토큰
CREATE TABLE apps (
  id            SERIAL PRIMARY KEY,                -- ClickHouse app_id(UInt32)
  name          VARCHAR(128) NOT NULL,
  env           VARCHAR(32)  NOT NULL,
  app_group     VARCHAR(64),
  apdex_t_ms    INT NOT NULL DEFAULT 500,
  slow_threshold_ms INT NOT NULL DEFAULT 1000,
  auto_register BOOLEAN NOT NULL DEFAULT FALSE,
  recommended_rules_applied BOOLEAN NOT NULL DEFAULT FALSE,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (name, env)                               -- DSN-02-14
);

CREATE TABLE instances (
  id              SERIAL PRIMARY KEY,              -- ClickHouse instance_id(UInt32)
  app_id          INT NOT NULL REFERENCES apps(id),
  name            VARCHAR(128) NOT NULL,           -- 설정 이름 → host:port → host:pid
  host            VARCHAR(255), pid INT,
  jvm_version     VARCHAR(64), jvm_vendor VARCHAR(64), os VARCHAR(64),
  cgroup          VARCHAR(8),                      -- v1|v2|none
  virtual_threads BOOLEAN,
  agent_version   VARCHAR(32), protocol_version INT,
  version_tag     VARCHAR(128),
  ip_mode         VARCHAR(8), user_id_mode VARCHAR(8), query_string_mode VARCHAR(10), sql_param_mode VARCHAR(8),
  config_hash     CHAR(16),
  remote_config_allow TEXT[],                      -- SEC-23 에이전트가 보고한 허용 키
  status          VARCHAR(8) NOT NULL DEFAULT 'DOWN', -- UP|STALE|DOWN (F-S28)
  warn_flags      TEXT[],                          -- clock_skew|drops|old_version|module_disabled
  clock_offset_ms INT,
  first_seen_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  last_seen_at    TIMESTAMPTZ,
  started_at      TIMESTAMPTZ,
  archived_at     TIMESTAMPTZ,                     -- 7일 미접속 자동 보관
  UNIQUE (app_id, name)
);
CREATE INDEX ix_instances_app_status ON instances(app_id, status) WHERE archived_at IS NULL;

CREATE TABLE agent_tokens (                        -- SEC-01~05
  id           UUID PRIMARY KEY,
  app_id       INT NOT NULL REFERENCES apps(id),
  token_hash   CHAR(64) NOT NULL UNIQUE,           -- SHA-256
  token_prefix CHAR(14) NOT NULL,                  -- 'argos_' + 8자
  issued_by    UUID REFERENCES users(id),
  issued_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  expires_at   TIMESTAMPTZ,
  grace_until  TIMESTAMPTZ,                        -- 회전 유예
  revoked_at   TIMESTAMPTZ,
  last_used_at TIMESTAMPTZ,
  status       VARCHAR(8) NOT NULL DEFAULT 'ACTIVE' -- ACTIVE|GRACE|REVOKED|EXPIRED
);
CREATE INDEX ix_agent_tokens_app ON agent_tokens(app_id) WHERE status IN ('ACTIVE','GRACE');

CREATE TABLE pending_registrations (               -- F-S30 승인 대기
  id SERIAL PRIMARY KEY, name VARCHAR(128), env VARCHAR(32), host VARCHAR(255),
  first_attempt_at TIMESTAMPTZ, last_attempt_at TIMESTAMPTZ, attempts INT, UNIQUE (name, env, host)
);

-- 12.3 사전
CREATE TYPE dict_kind AS ENUM ('service','sql','class','method','thread','host','db','pool','frame','version','metric','error_group');
CREATE TABLE dictionary (
  app_id       INT NOT NULL,
  kind         dict_kind NOT NULL,
  hash64       BIGINT NOT NULL,                    -- xxh3_64를 signed로 저장
  text         TEXT NOT NULL,                      -- 상한 64KB(초과 절단)
  truncated    BOOLEAN NOT NULL DEFAULT FALSE,
  norm_version SMALLINT,                           -- sql: 정규화기 버전(D-22)
  first_seen   TIMESTAMPTZ NOT NULL DEFAULT now(),
  last_seen    TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (app_id, kind, hash64)
);
CREATE INDEX ix_dictionary_text_trgm ON dictionary USING gin (text gin_trgm_ops);   -- 전역 검색(F-U14 S)
CREATE INDEX ix_dictionary_last_seen ON dictionary(last_seen);                      -- 90일 미참조 정리(SEC-13)

CREATE TABLE error_groups (                        -- F-S19
  app_id          INT NOT NULL,
  group_id        BIGINT NOT NULL,                  -- xxh3_64(class + masked message pattern + top app frame)
  error_class     VARCHAR(512),
  message_pattern TEXT,                             -- 마스킹·정규화된 패턴(SEC-36)
  top_frame       VARCHAR(1024),
  sample_stack    TEXT,                             -- 대표 스택 1개(마스킹)
  first_seen      TIMESTAMPTZ NOT NULL, last_seen TIMESTAMPTZ NOT NULL,
  count_total     BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (app_id, group_id)
);

-- 12.4 알림
CREATE TABLE alert_rules (
  id UUID PRIMARY KEY, app_id INT REFERENCES apps(id), env VARCHAR(32), name VARCHAR(128) NOT NULL,
  template_key VARCHAR(64), enabled BOOLEAN NOT NULL DEFAULT TRUE,
  spec JSONB NOT NULL,                              -- 02 §5.5 규칙 모델(window, for, condition, recover, min_samples, …)
  created_by UUID, created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(), version INT NOT NULL DEFAULT 1
);
CREATE TABLE alert_rule_history (rule_id UUID, version INT, spec JSONB, changed_by UUID, changed_at TIMESTAMPTZ, PRIMARY KEY (rule_id, version));

CREATE TABLE alert_events (                        -- 상태 전이만 기록(평가 틱 아님)
  id UUID PRIMARY KEY, rule_id UUID NOT NULL REFERENCES alert_rules(id), app_id INT, env VARCHAR(32),
  target_kind VARCHAR(16), target_id BIGINT, target_name VARCHAR(256),
  from_state VARCHAR(10), to_state VARCHAR(10),    -- OK|PENDING|FIRING|ACKED|RESOLVED|NO_DATA
  severity VARCHAR(8), value DOUBLE PRECISION, threshold DOUBLE PRECISION, duration_s INT,
  acked_by UUID, ack_note VARCHAR(512),
  ts TIMESTAMPTZ NOT NULL
) PARTITION BY RANGE (ts);                         -- 월 파티션, 보존 365일
CREATE INDEX ix_alert_events_rule_ts ON alert_events(rule_id, ts DESC);
CREATE INDEX ix_alert_events_app_ts  ON alert_events(app_id, ts DESC);

CREATE TABLE notification_channels (
  id UUID PRIMARY KEY, name VARCHAR(128) NOT NULL, type VARCHAR(16) NOT NULL,   -- email|slack|webhook
  config_enc BYTEA NOT NULL,                       -- AES-256-GCM(마스터 키) (SEC-52)
  config_masked JSONB NOT NULL,                    -- UI 표시용 마스킹본
  signing_key_enc BYTEA,                           -- 웹훅 서명 키(SEC-53)
  enabled BOOLEAN NOT NULL DEFAULT TRUE, created_at TIMESTAMPTZ, updated_at TIMESTAMPTZ
);
CREATE TABLE notification_deliveries (             -- F-S27 채널별 전송 결과
  id UUID PRIMARY KEY, alert_event_id UUID REFERENCES alert_events(id), channel_id UUID REFERENCES notification_channels(id),
  attempt INT, status VARCHAR(8), http_status INT, error VARCHAR(512), sent_at TIMESTAMPTZ
);
CREATE INDEX ix_deliveries_channel_sent ON notification_deliveries(channel_id, sent_at DESC);

-- 12.5 명령·설정 변경·이벤트
CREATE TABLE commands (
  id UUID PRIMARY KEY, instance_id INT NOT NULL REFERENCES instances(id), type VARCHAR(32) NOT NULL,
  requested_by UUID NOT NULL REFERENCES users(id), requested_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  expires_at TIMESTAMPTZ NOT NULL,                  -- TTL 60초(SEC-24)
  status VARCHAR(10) NOT NULL,                      -- QUEUED|SENT|DONE|FAILED|EXPIRED|REJECTED
  finished_at TIMESTAMPTZ, duration_ms INT, error VARCHAR(512)
);
CREATE TABLE command_results (
  command_id UUID PRIMARY KEY REFERENCES commands(id) ON DELETE CASCADE,
  body_enc BYTEA NOT NULL,                          -- gzip → AES-256-GCM (SEC-22)
  body_size INT NOT NULL, truncated BOOLEAN NOT NULL DEFAULT FALSE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()     -- 보존 30일
);

CREATE TABLE config_changes (                      -- F-S29
  id UUID PRIMARY KEY, app_id INT NOT NULL, target_instance_ids INT[],
  reason TEXT NOT NULL, before JSONB NOT NULL, after JSONB NOT NULL,
  requested_by UUID NOT NULL, requested_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  reverted_from UUID REFERENCES config_changes(id)
);
CREATE TABLE config_change_results (
  change_id UUID REFERENCES config_changes(id) ON DELETE CASCADE, instance_id INT,
  status VARCHAR(10), rejected_keys TEXT[], applied_at TIMESTAMPTZ, error VARCHAR(512), PRIMARY KEY (change_id, instance_id)
);

CREATE TABLE events (                              -- F-S18 배포·자동 이벤트
  id UUID PRIMARY KEY, app_id INT, env VARCHAR(32), instance_id INT,
  type VARCHAR(24) NOT NULL,                        -- deploy|start|stop|config|state|apdex_t_change|retention_emergency|kill_switch
  title VARCHAR(256), detail JSONB, version VARCHAR(128), ts TIMESTAMPTZ NOT NULL, created_by UUID
);
CREATE INDEX ix_events_app_ts ON events(app_id, ts DESC);

-- 12.6 감사·설정·개인정보 작업·마이그레이션 이력
CREATE TABLE audit_logs (                          -- SEC-80~82. argos_app은 INSERT·SELECT만
  id BIGSERIAL, ts TIMESTAMPTZ NOT NULL DEFAULT now(),
  actor_type VARCHAR(8) NOT NULL, actor_id VARCHAR(64), actor_name VARCHAR(128),
  ip INET, user_agent VARCHAR(256), request_id VARCHAR(64),
  action VARCHAR(64) NOT NULL, target_type VARCHAR(32), target_id VARCHAR(64), target_name VARCHAR(256),
  result VARCHAR(8) NOT NULL, reason VARCHAR(512),
  before JSONB, after JSONB,                        -- 비밀값 마스킹
  subject_user_id VARCHAR(128), subject_tx_id BIGINT,   -- 개인정보 열람 대상
  PRIMARY KEY (id, ts)
) PARTITION BY RANGE (ts);                         -- 월 파티션, 보존 365일(설정, 미결 20)
CREATE INDEX ix_audit_ts ON audit_logs(ts DESC);
CREATE INDEX ix_audit_actor_ts ON audit_logs(actor_id, ts DESC);
REVOKE UPDATE, DELETE, TRUNCATE ON audit_logs FROM argos_app;

CREATE TABLE settings (key VARCHAR(128) PRIMARY KEY, value JSONB NOT NULL, updated_by UUID, updated_at TIMESTAMPTZ NOT NULL DEFAULT now());

CREATE TABLE privacy_jobs (                        -- 9장
  id UUID PRIMARY KEY, subject_kind VARCHAR(8) NOT NULL, subject_hash CHAR(64) NOT NULL, app_id INT,
  from_ts TIMESTAMPTZ, to_ts TIMESTAMPTZ, mutation_ids TEXT[], status VARCHAR(10), created_by UUID,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(), finished_at TIMESTAMPTZ, error VARCHAR(512)
);

CREATE TABLE ch_schema_history (                   -- 13장
  version VARCHAR(32) PRIMARY KEY, description VARCHAR(256), checksum CHAR(64) NOT NULL,
  applied_at TIMESTAMPTZ NOT NULL DEFAULT now(), duration_ms INT, success BOOLEAN NOT NULL, error TEXT
);
```

| 테이블 | 보존·정리 작업(서버 ops 스케줄러) |
|---|---|
| `dictionary` | `last_seen < now() - 90d` 야간 삭제(SEC-13). 앱당 상한 100만 행 초과 시 신규를 `<overflow>`로 매핑 |
| `error_groups` | 90일 미발생 그룹 삭제 |
| `alert_events`, `notification_deliveries` | 월 파티션, 365일 지난 파티션 DETACH·DROP |
| `command_results` | 30일 또는 인스턴스당 최근 100건 |
| `commands` | 90일 |
| `config_changes` | 365일(되돌리기 대상이므로 최근 20건은 유지) |
| `events` | 365일 |
| `audit_logs` | 월 파티션, 설정 기간(기본 365일) 지난 파티션을 `argos_migrator` 작업으로만 DROP |
| `user_sessions` | 만료·폐기 7일 후 삭제 |
| `instances` | 7일 미접속 `archived_at`, 30일 후 삭제(참조 CH 데이터는 TTL이 처리) |

## 13. 마이그레이션 러너(N-22, N-21)

### 13.1 PostgreSQL

Flyway. `server/src/main/resources/db/pg/V{N}__{desc}.sql`. 기동 시 `argos_migrator` 자격으로 `migrate`, 이후 애플리케이션은 `argos_app`으로 접속. `flyway.validateOnMigrate=true`, 체크섬 불일치 시 기동 거부.

### 13.2 ClickHouse 자체 러너

| 규칙 | 내용 |
|---|---|
| 위치·이름 | `deploy/clickhouse/schema/V{NNN}__{desc}.sql`(테이블·인덱스·TTL·설정), `deploy/clickhouse/rollups/{table}.sql`(SELECT 본문), `deploy/clickhouse/migrations/M{NNN}__{desc}.yaml`(MV 교체·백필 같은 다단계 절차) |
| 이력 | PostgreSQL `ch_schema_history(version, checksum, applied_at, duration_ms, success)`. 체크섬 불일치는 기동 거부 |
| 멱등 | 모든 DDL은 `CREATE … IF NOT EXISTS`, `ALTER TABLE … ADD COLUMN IF NOT EXISTS`, `DROP … IF EXISTS`. 러너가 정규식으로 검사해 위반 스크립트는 거부 |
| 플레이스홀더 | `{db}`, `{p}`, `{where}`, `{retention.*}`만 허용 |
| MV 교체 4단계 | (1) 새 롤업 테이블·MV를 `_v2` 접미로 생성 → (2) 원본 보존 내 날짜를 재구축 CLI로 백필(하루 단위, 진행률 기록) → (3) `settings.rollup_alias` 갱신으로 조회를 `_v2`로 전환 → (4) 다음 마이너에서 구 MV·테이블 DROP. 단계는 `M` 절차 파일의 `steps:`로 선언하고 각 단계가 `ch_schema_history`에 기록된다 |
| 대용량 ALTER | `ADD COLUMN`은 메타데이터 변경(즉시). `MODIFY COLUMN` 타입 변경·`MODIFY ORDER BY`는 금지(새 테이블 + 4단계). `MODIFY TTL`은 `materialize_ttl_after_modify=0` 필수 |
| 롤백 | 직전 마이너만. 롤백 스크립트 `U{NNN}__`은 컬럼·MV 추가의 역만 지원. 데이터 파괴 롤백은 없음 |
| 테스트 | CI 두 경로: 빈 DB → 전부 적용 / "이전 마이너 스키마 + 1/100 데이터" → 증분 적용. 각 스크립트 소요 ≤ 10분(1/100 데이터), 적용 후 D-03·D-12 정합 쿼리 0 |
| 버전 고정 | Compose·tar 번들에 ClickHouse 정확 버전(예: `clickhouse/clickhouse-server:<LTS>.x`). 지원 범위 = 그 LTS와 다음 LTS. 업그레이드는 한 LTS 단위, 17 문서 `clickhouse-upgrade.md` 체크(설정 기본값 변경, 집계 상태 호환, `system.*` 스키마 변경). 릴리스 노트에 CH 버전 변경 명시 |

## 14. 용량 산정(N-19)

공식(일 증가량, B는 압축 후 행당 바이트, PoC 실측으로 확정):

```
xlog     = TPS × 86,400 × B_x                       (전 kind 합)
profile  = TPS × 캡처율(기본 0.12) × step 수(평균 8, 접힘 후) × 86,400 × B_p
metric   = 인스턴스 × 시리즈(기본 150) × 17,280 × B_m
sql_stat = 인스턴스 × 활성 SQL 종(평균 200) × 8,640 × B_s       (10초 창, 창마다 활성 SQL만 행 생성)
ext_stat = 인스턴스 × 활성 호스트(평균 10) × 8,640 × B_e
rollup   ≈ (service 100 + sql 2,000 + ext 50 + error 50 + instance N) × 1,440 × B_r  (1m, 하루) + 1h는 1/60
```

| 항목 | 기대값 | PoC 측정 항목 |
|---|---|---|
| B_x | 70 B(랜덤 id 40B 포함, 압축률 ≥ 3) | Q20 코덱 비교 |
| B_p | 25 B(압축률 ≥ 5) | |
| B_m | 4 B(DoubleDelta+Gorilla, 압축률 ≥ 8) | |
| B_s, B_e | 30 B(버킷 배열 포함) | |
| B_r | 60 B(1m), tDigest 상태 포함 service·app은 300 B | |

| 규모 | 전제 | xlog/일 | profile/일 | metric/일 | stat/일 | 롤업/일 | 합계/일 | 보존 누적(30/7/14/14/90+365) |
|---|---|---|---|---|---|---|---|---|
| 500 TPS, 50 인스턴스 | 기본 S | 3.0 GB | 1.0 GB | 0.5 GB | 0.4 GB | 0.4 GB | 5.3 GB | 약 150 GB |
| 2,000 TPS, 150 인스턴스 | S 상한 | 12 GB | 4.1 GB | 1.6 GB | 1.1 GB | 0.6 GB | 19 GB | 약 530 GB → 500GB 디스크 초과, 보존 단축(xlog 14일) 또는 1TB |
| 5,000 TPS, 300 인스턴스 | N-06 상한(1시간) | 30 GB | 10 GB | 3.1 GB | 2.2 GB | 0.8 GB | 46 GB | 지속 운용 불가. M 토폴로지 또는 보존 7/3/7일 |

운영 매뉴얼(17 문서)은 이 표와 "디스크 = 일 증가량 × 보존 + 20% 머지 여유 + WAL 10GiB + 백업(켠 경우)"를 싣는다. Gate 1에서 도그푸딩 실측과 ±30% 안이면 확정.

## 15. PoC(P-4) 사양

| 항목 | 내용 |
|---|---|
| 환경 | 사내 서버 8코어/16GB/NVMe 500GB, 7장 설정 적용, ClickHouse 번들 고정 버전. CI는 Docker에서 1/100 규모 |
| 생성기 | `test/storage/generator`(Java). 분포: 앱 10(env prod 7·stg 3), 인스턴스 50, 서비스 100(Zipf s=1.1), SQL 2,000(Zipf), 외부 호스트 20, 버전 3(15일째 전환), kind HTTP 97%·BATCH 3%, 에러율 1%·에러 그룹 50, 응답시간 로그정규(σ=1.0, 중앙값 80ms) + 1% 이상치(2~30초), user_id 30% 채움(hashed), remote_ip 1만 종(masked), request_id 50%, 인스턴스별 시계 오차 ±2초(2개는 +60초), 늦은 도착 0.5%(1~30분), 중복 재전송 0.1%, 자정 경계 포함, `sql_ids` 평균 3개 |
| 규모 | xlog 13억 행(30일 × 500 TPS), profile_step 5.4억(7일), metric 12억(14일), sql_stat_raw 1.2억·ext_stat_raw 0.1억(14일) |
| 모드 | **벌크**: 날짜순 대량 insert(용량·압축 측정). **리플레이**: 12 문서 라이터와 같은 경로(WAL → 블록 → 토큰)로 2초 배치, 목표 TPS(500 → 2,000 → 5,000) 각 1시간(parts·적재 지연·동시 질의) |
| 측정 | 적재 속도, 테이블별 압축률(`sum(data_uncompressed_bytes)/sum(data_compressed_bytes)`), 행당 바이트, parts 추이, 메모리 피크, 쿼리 시간(콜드 1회·웜 3회 중앙값), 정합 |

| # | 내용 | 기준 |
|---|---|---|
| Q1 | 대시보드 1h 원본 10초 버킷(count·avg·p95) | 1초 |
| Q2 | 대시보드 7일 `app_stat_1m` / 90일 `app_stat_1h` | 1초 |
| Q3 | XLog 10분 10만 점(결정적 샘플링, shown/total) | 2초 |
| Q4 | 드래그 검색 1h × 응답시간 범위 + 서비스·에러 필터, 1,000행 | 1초 |
| Q5 | tx_id 상세: id 복원 ±2초 / URL ts / 범위 없이 bloom만(fp 0.01·0.025 × GRANULARITY 1·4) | 0.5초 / 0.5초 / 측정·결정 |
| Q6 | trace_id: 힌트 ±1h / 24h / 30일 | 0.5초 / 1초 / 측정 |
| Q7 | 서비스 Top N 1일·7일(백분위 병합), 버전별 분리 | 1초 |
| Q8 | SQL Top N 1일·7일(`sql_stat_1m`, 버킷 p95), SQL 상세 추이 | 1초 |
| Q9 | 에러 그룹 집계 1일 + 그룹 → 관련 트랜잭션 30일 | 1초 |
| Q10 | 메트릭: 인스턴스 1개 힙 7일(1m), 앱 합계 90일(1h) | 1초 |
| Q11 | 'SQL 포함' 검색 `has(sql_ids, ?)` 1일 + `sql_ids` 컬럼 디스크 비용(GB/일) | 2초, 비용 기록 → A-10 결정 |
| Q12 | **D-03 정합**: 30일 분 단위 `count(xlog)` vs `Σ app_stat_1m` vs `Σ service_stat_1m` vs `Σ instance_stat_1m` 불일치 분 수 | 0 |
| Q13 | **D-12 정합**: `Σ xlog.sql_count` vs `Σ sql_stat_1m.count`, `Σ xlog.ext_count` vs `Σ ext_stat_1m.count` | 0 |
| Q14 | **백분위 정확도**: `quantileExact` 대비 tDigest / BFloat16 / DD 상태 병합의 상대 값 오차, 1m·1h·1d × p50/p95/p99 + 버킷 p95 오차 | p50·p95 ≤ 2%, p99 ≤ 5%(상태 함수) → A-11 결정. 버킷 p95는 오차 기록 |
| Q15 | **중복 재적재**: 같은 block_id 블록 100회 재삽입, MV 1개 고장 → 복구 → 재삽입 | 원본·모든 롤업 증가 0, 고장 MV만 채워짐 |
| Q16 | **늦은 도착**: 1시간 전 ts 2만 행 삽입 → 해당 분 롤업 갱신, Q12 재실행 | 불일치 0 |
| Q17 | **동시 부하**: 리플레이 5,000 TPS + 50,000 step/s + 10,000 stat row/s 1시간 중 가상 사용자 10명이 Q1·Q3·Q7을 5초마다 30분 | 쿼리 p95 2초, 적재 지연 p99 5초, parts/파티션 < 300, DelayedInserts 0, 메모리 피크 < 7.5GiB |
| Q18 | **TTL·DROP·뮤테이션**: 1일 TTL 복제본 삭제 반영 시간, 5,000 TPS 하루 파티션 DROP 시간·디스크 회수, user_id 삭제 뮤테이션 하루 파티션 소요 | 측정·기록 |
| Q19 | **마이그레이션 리허설**: 전체 데이터에서 `ADD COLUMN`, `MODIFY TTL`(materialize 0/1), MV 교체 4단계(백필 1일) | 각 10분 이내 |
| Q20 | **압축 비교**: 서버 기본 LZ4 vs ZSTD(1), 컬럼 코덱 on/off 테이블별 압축률·insert CPU | 기록 → 코덱 확정 |

Gate 0 기준(03 문서): Q1~Q11 충족, 압축률 xlog ≥ 3·profile ≥ 5·metric ≥ 8, Q12·13·15·16 정합 0, Q17 충족, Q18~20 기록. 결과는 `docs/poc/clickhouse-benchmark.md`.

## 16. 테스트 포인트와 요구 대응

| 03 문서 3.2 항목 | 이 문서 |
|---|---|
| Testcontainers 적재·조회 | 3·4·6장 DDL·쿼리를 그대로 통합 테스트 fixture로 |
| 빈 DB·이전 버전 DB 두 경로 마이그레이션, 각 스크립트 소요 | 13장 러너 |
| D-03·D-12·D-13 정합(중복 재전송·kill -9·CH 30분 중단·늦은 도착) | 5장 시나리오, 6.3 (13)(14) 쿼리 |
| 읽기 전용 사용자·쿼리 설정, `system.query_log` 검증 | 7.3 프로파일 |
| 소스 자동 선택·`source` 응답, 분 경계 미정렬 일치 | 6.1 |
| 자기 메트릭 저장소 지표 | 7.5 |

| 요구 | 구현 위치 |
|---|---|
| D-03 | 4.2 app/service/instance 롤업, 6.1 끝 2분 원본, 6.3(13) 정합 |
| D-08, D-22 | 2장 사전 키, 12.3 `dictionary.norm_version` |
| D-09, D-16 | 3.3 `metric.kind/delta`, 4.6 `sample_count·increase` |
| D-11 | 2장 `tx_id`, 6.3(5) 복원 조회 |
| D-12 | 3.4·3.5 전수 통계, 4.5 롤업, 6.3(14) 정합 |
| D-13 | 5장 토큰·블록 매니페스트 |
| D-14 | 4.1 rollups 파일 공유, 13장 재구축 |
| D-15 | 라이터 가드는 12 문서. 저장소는 `ts` 범위 외 데이터가 없다는 전제 |
| D-17 | 3.1 `apdex_t_ms`, 4.2 집계식, `events(apdex_t_change)` |
| D-23 | 2장 센티널 -1 |
| N-06 | 7.4 적재 규칙, Q17 |
| N-07, N-24 | 6.3 쿼리, 인덱스(`idx_user`, `idx_request`) |
| N-08 | 8장 |
| N-18 | 7.1·7.2 |
| N-19 | 14장 |
| N-20 | 10장 |
| N-21, N-22 | 13장 |
| F-S02, F-S21 | 3·4·5장 |
| F-S10, F-S23 | 7.5, 8장 디스크 임계 |
| F-S16 | 3.1 `apdex_t_ms`, 4.2 |
| F-S19 | 3.1 `error_group_id`, 4.4, 12.3 `error_groups` |
| F-S22 | 4.1 재구축 규약, 6.3(13)(14) |
| F-S32 | 9장 |
| SEC-13 | 12.3 사전 정리·상한 |
| SEC-22, SEC-52 | 12.4·12.5 `*_enc` 컬럼 |
| SEC-30~35 | 3.1 모드 컬럼, 9장 삭제, 12.3 `dictionary` 절단 |
| SEC-74, SEC-76 | 7.3 계정·권한, DSN-13-12·13 |
| SEC-80~84 | 12.6 `audit_logs` 불변·파티션, 10장 백업 보호 |

## 17. 미결·가정

| # | 항목 | 가정 | 처리 |
|---|---|---|---|
| 1 | `insert_deduplication_token` + `non_replicated_deduplication_window` + `deduplicate_blocks_in_dependent_materialized_views`가 비복제 MergeTree + MV 조합에서 문서대로 동작 | 22.x 이후 지원 | 번들 고정 버전에서 Q15로 실측. 착수 시 확인 |
| 2 | `quantilesBFloat16`·`quantilesDD`의 `-State/-MergeState` 지원과 상태 크기 | 둘 다 지원 | Q14 |
| 3 | `parts_to_delay_insert/throw_insert` 기본값 | 버전별 상이(150/300 또는 1000/3000) | 7.2에서 명시 설정하므로 기본값 무관 |
| 4 | `max_partition_size_to_drop=0`을 서버 설정으로 해제 가능 | 가능 | 착수 시 확인 |
| 5 | `DateTime64` 컬럼 TTL에 `toDateTime()` 캐스트 필요 여부 | 캐스트 유지가 안전 | 유지 |
| 6 | 집계 상태(`AggregateFunction`) 바이너리의 LTS 간 호환 | 실무상 안정, 공식 보장 제한 | 업그레이드 테스트에 "롤업 읽기 정상" 추가, 불일치 시 재구축으로 복구 |
| 7 | MergeTree → Replicated 전환 `convert_to_replicated` 플래그 가용 버전 | 23.11 전후 | 1.x 착수 시 확인, 대안 `ATTACH PARTITION FROM` |
| 8 | `bloom_filter` 인덱스가 `Array(UInt64)`의 `has()`에 적용 | 적용됨 | Q11 `ProfileEvents`로 스킵 효과 확인 |
| 9 | `Gorilla` 코덱이 `Float64`에 허용 | 허용 | 착수 시 확인 |
| 10 | `SimpleAggregateFunction(sumForEach, Array(UInt64))` 지원 | 지원(또는 `AggregateFunction(sumForEach)`로 대체) | 착수 시 확인, 대체 시 조회는 `sumForEachMerge` |
| 11 | `tx_id` 노드 비트 충돌(instance_id mod 4096) | 1.0 규모(≤300 인스턴스)에서 0 | 조회가 2행이면 둘 다 반환하고 경고(6.3(5)) |
| 12 | 메모리 예산 수치 | 16GB 경험값 | WO-27 부하 테스트 RSS 실측 후 설치 스크립트 비율 조정 |
| 13 | 행당 바이트 기대값 | 랜덤 id 40B + 압축 가정 | Q20, 벌크·리플레이 차이 모두 기록 |
| 14 | 버킷 p95의 UI 표기 | "추정값(버킷)" 툴팁 | 15 문서 |
| 15 | PostgreSQL `pg_trgm` 확장 가용 | 표준 contrib | 설치 스크립트가 `CREATE EXTENSION` |
| 16 | `instance_stat_1m` 추가로 insert당 1m MV 수 = 5(app·service·instance·error + xlog) | parts 영향은 Q17로 확인 | 초과 시 instance 롤업을 서버 측 집계로 대체 검토 |

## 18. 다른 설계 문서에 요구하는 것

| 대상 | 요구 | 이유 |
|---|---|---|
| 11 프로토콜 | `TransactionRecord`에 `kind`, `env`, `version_id`, `dbc_time_ms`, `ip_mode`, `user_id_mode`, `request_id`, `streaming`, `timeout`, `sql_ids[]`, `error_class`·`error_message(masked)`·`top_frame`(지문 계산 입력) 필드. `ProfileStep`에 `type`(DBC·COMMIT·ROLLBACK·STACK 포함), `exec_ms`, `fetch_ms`, `target_id`, `rows`, `repeat_count`, `stack[]`(frame id). `StatsBatch`(sql·ext 10초 창, `buckets[15]`). `MetricSample`에 `kind`, `delta`. `MetricDescriptor`(이름·종류·단위). 히스토그램 경계 상수 공유 | 3장 컬럼이 전부 채워지려면 |
| 11 프로토콜 | `batch_seq` 단조 증가·세션 재수립과 무관(DSN-02-11) | 5.1 에이전트 재전송 1차 필터 |
| 12 서버 | 라이터: WAL 블록 매니페스트(`block_id` UUIDv7)·토큰 규칙(5.1), 테이블별 2초/50,000행, 명시 컬럼 RowBinary, `argos_writer` 연결, 1,000 블록 복구 창 알림 | DSN-13-07 |
| 12 서버 | 수신 가드: `(instance_id, batch_seq)` 윈도, 타임스탬프 가드(D-15), 에러 그룹 지문 계산(마스킹 후), `apdex_t_ms` 주입, `env` 비정규화, 사전 비동기 upsert(상한 SEC-13) | 저장소는 가드 이후 데이터만 받는다 |
| 12 서버 | 야간 ops: 6.3(13)(14) 정합 쿼리 실행 → 불일치 날 재구축 CLI(`argos-admin rollup rebuild --table --date`) → 수집 상태 패널. 보존 작업(12장 표), 디스크 임계 동작(8장), 삭제 뮤테이션 잡(9장) | F-S22·F-S23·F-S32 |
| 12 서버 | 메트릭 `delta`는 에이전트가 계산하되 서버가 `kind`를 `MetricDescriptor`로 채움 | DSN-13-04 |
| 14 API | 6.1 소스 선택·끝 2분 원본 합산·`source`·`rawRetentionExceeded` 응답 필드, 6.2 상한, 6.3 쿼리 템플릿 사용, `argos_reader` + 쿼리 설정 전달, 버킷 p95 함수(`argosBucketQuantile`), 결측 null 변환(센티널), 시간 격자 채움, hashed user_id 검색 시 앱 솔트 해시 | |
| 15 UI | "원본 보존 기간 경과", "표시 N / 전체 M", 버킷 p95 "추정값" 툴팁, 행 단위 "IP 마스킹됨"·"측정 불가(가상 스레드)" 표시 | D-03·D-07·D-23·SEC-31 |
| 17 배포·운영 | `config.d/argos.xml`·`users.d/argos.xml` 배치와 RAM 비율 산출, 비밀번호 해시 주입, `pg_trgm` 확장, 백업 스크립트(10장)·외부 대상 설정, 보존 변경 절차·비상 보존 단축 런북, ClickHouse 정확 버전 고정·LTS 업그레이드 체크리스트, 용량 표(14장) 게재 | |
| 18 테스트 | PoC 생성기·리플레이 하네스(15장), 5.2 중복 시나리오, 13장 두 경로 마이그레이션 테스트, Q12·Q13 정합 테스트를 Testcontainers 1/100 규모로 CI에 | |
| 10 에이전트 | 전수 통계 집계기(10초 창, `(sql_id, db_id, kind)`·`(host_id, kind)` 키, 15 버킷), 메트릭 counter `delta` 리셋 보정, 연속 반복 step 접기(`repeat_count`), DBC 풀 대기는 `jdbc_pool_wait_seconds_total`·`jdbc_pool_wait_count_total` counter로 | 3.4·3.5·3.3·DSN-13-20 |
| 02 아키텍처(PM) | §4.3 테이블 목록에 `instance_stat_1m`(4.7)과 `*_stat_raw`의 `buckets` 추가, A-11 후보에 "버킷 p95(SQL·외부 호출)와 tDigest(트랜잭션) 이원화" 반영 | 설계 중 발견 |

