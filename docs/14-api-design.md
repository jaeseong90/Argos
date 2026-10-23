# Argos API 설계

| 항목 | 내용 |
|---|---|
| 문서 번호 | 14 |
| 문서 버전 | 0.1 (초안) |
| 작성일 | 2026-10-23 |
| 상태 | 설계 검토 중 |
| 만족시키는 요구 | F-S04, F-S05, F-S07, F-S08, F-S10, F-S18, F-S20, F-S22, F-S23, F-S26, F-S29, F-S30, F-S32, F-U01~F-U21(데이터 공급), D-03, D-06, D-07, D-18, D-20, N-07, N-24, N-25, O-R03, SEC-21, SEC-33, SEC-39~50, SEC-55, SEC-56, SEC-60, SEC-63, SEC-64 |
| 의존 문서 | 02 아키텍처(DSN-02-14·15·18·20, §4.3 테이블 목록, §7 횡단 규칙), 05 벤치마크 2장, 06 보안 요구 |
| 이 문서에 의존하는 문서 | 12 서버(구현), 13 저장소(인덱스·쿼리), 15 UI(라우트·클라이언트), 16 보안(세부), 18 테스트(계약 테스트) |

## 1. 범위와 원칙

이 문서는 서버가 브라우저(UI)와 운영 자동화(배포 파이프라인)에 제공하는 **REST API와 WebSocket 프로토콜**을 정한다. 에이전트↔서버 gRPC는 11 문서다.

| 원칙 | 의미 |
|---|---|
| UI가 쓰는 API가 곧 공개 API | 비공개 엔드포인트는 없다. UI는 생성된 클라이언트만 쓴다 |
| 추가만 허용 | `/api/v1` 응답 스키마는 필드 추가만 가능. 제거·의미 변경은 `/api/v2`(O-R03) |
| 모든 응답에 출처와 제한을 적는다 | `source`, `truncated`, `shown/total`, `sampling`을 봉투에 넣어 UI가 숨기지 않고 표시한다(D-06·D-07) |
| 앱 범위 조회는 `app` 필수, ID 단건·전체 현황·전 앱 알림은 예외 | DSN-14-07 |
| 수집 데이터는 신뢰할 수 없는 입력 | API는 이스케이프하지 않고 그대로 반환하며 스키마에 `x-argos-untrusted`로 표기, 렌더 측이 텍스트 노드로만 그린다(SEC-60) |
| 측정 가능한 상한 | 모든 목록·범위·문자열에 상한이 있고 초과는 400 또는 `truncated`로 표기 |

## 2. API 규약

### 2.1 기본 경로·버전 (DSN-14-01)

- 기본 경로 `/api/v1`. 모든 경로는 `/api/v1/...`이며 아래 표에서는 접두를 생략한다.
- 1.0 전(0.x)에는 호환성 약속이 없다. 1.0부터 `/api/v1`은 **필드 추가만** 허용한다. 필드 제거·타입 변경·의미 변경·필수 파라미터 추가는 breaking이며 `/api/v2`를 신설하고 `/api/v1`을 최소 2마이너 유지한다.
- 폐기 예정 엔드포인트는 응답 헤더 `Deprecation: true`, `Sunset: <RFC 1123 날짜>`, `Link: <문서 URL>; rel="deprecation"`을 붙인다.
- 서버 버전은 모든 응답에 `X-Argos-Server-Version: X.Y.Z` 헤더로 노출한다.

### 2.2 인증·CSRF (DSN-14-02, SEC-40~44)

| 항목 | 규칙 |
|---|---|
| 세션 | `argos_at`(액세스 15분), `argos_rt`(리프레시, 8시간 비활동·24시간 절대) 쿠키. `HttpOnly; Secure; SameSite=Strict; Path=/api`. `Secure`는 TLS 종단이 서버 앞에 있어도 유지(리버스 프록시 가이드) |
| CSRF | 더블 서브밋: `GET /auth/csrf`가 `argos_csrf` 쿠키(HttpOnly 아님, SameSite=Strict)를 내리고, 상태 변경 요청(POST·PUT·PATCH·DELETE)은 `X-Argos-CSRF` 헤더에 같은 값을 보낸다. 서버는 쿠키·헤더 일치 + `Origin`(또는 `Sec-Fetch-Site: same-origin`) 검사. 불일치는 403 `csrf_invalid` |
| 로그인 | `POST /auth/login` 성공 시 세션 식별자 재발급(세션 고정 방지). 실패 10회 → 423 `account_locked`(15분, 누진) |
| 리프레시 | `POST /auth/refresh`는 `argos_rt`를 회전한다. 구 리프레시 재사용 감지 → 그 사용자 모든 세션 폐기 + 401 `session_revoked` |
| 즉시 무효화 | 서버 측 `user_sessions` 레코드를 매 요청 확인(캐시 5초). 로그아웃·비밀번호 변경·역할 변경·강제 종료 시 다음 요청부터 401 |
| 토큰 인증(예외) | `POST /events`(배포 마커)만 `Authorization: Bearer argos_...`(앱 단위 에이전트 토큰)를 대안으로 허용한다. 토큰의 앱과 요청 `app`이 다르면 403. 그 외 모든 엔드포인트는 쿠키 세션만 |
| Actuator | `/actuator/health` 무인증(상세 숨김), `/actuator/prometheus`는 서버 설정의 베이직 또는 전용 베어러 토큰 + IP 허용 목록, 나머지 비활성(SEC-55) |

### 2.3 공통 쿼리 파라미터 (DSN-14-03)

| 파라미터 | 타입 | 기본 | 의미 |
|---|---|---|---|
| `app` | int(app_id) | 앱 범위 엔드포인트는 필수(경로 `/apps/{appId}`로도 표현) | 앱 |
| `env` | string | 전체 | 환경 필터. 전체 현황·전 앱 알림에서 사용 |
| `from`, `to` | ISO-8601 UTC(ms 허용) | 필수(범위 엔드포인트) | 반개구간 `[from, to)`. `to`가 `from`보다 작거나 범위 상한 초과면 400 `range_invalid` |
| `kind` | `HTTP` \| `BATCH` \| `MESSAGE` \| `ALL` | `HTTP` | 트랜잭션 종류(D-20) |
| `instance` | int[] | 전체 | 인스턴스 필터(반복 가능) |
| `step` | duration(`10s`, `1m`, `5m`, `1h`) | 자동 | 시계열 버킷. 자동이면 버킷 수 ≤ 720이 되게 서버가 고른다 |
| `limit` | int | 엔드포인트별 | 상한을 넘으면 400 `limit_exceeded` |
| `cursor` | opaque string | 없음 | 키셋 페이지네이션. 응답 `meta.next_cursor` |
| `sort` | `field:asc|desc` | 엔드포인트별 | 허용 필드 밖은 400 |
| `format` | `json` \| `csv` | `json` | CSV는 `x-argos-csv: true`인 엔드포인트만 |
| `tz` | IANA | 없음 | CSV 시각 표기용. JSON은 항상 UTC |

### 2.4 시간 표현

- 요청·응답 모두 ISO-8601 UTC(`2026-10-23T01:02:03.456Z`). 서버는 KST 등 지역 시간을 받지 않는다.
- 시계열 버킷의 `ts`는 버킷 시작. 분 롤업은 분 시작, 시간 롤업은 시 시작.
- 트랜잭션 `ts`는 시작 시각, `elapsed_ms`는 정수 ms. 응답시간 µs 내부 값은 노출하지 않는다.

### 2.5 응답 봉투 (DSN-14-04)

```json
{
  "data": { },
  "meta": {
    "request_id": "01J...",
    "generated_at": "2026-10-23T01:02:03.456Z",
    "from": "2026-10-23T00:00:00Z", "to": "2026-10-23T01:00:00Z",
    "source": "raw",
    "truncated": false,
    "shown": 100000, "total": 143210,
    "sampling": { "applied": true, "rule": "error OR slow OR cityHash64(tx_id) % 2 = 0" },
    "next_cursor": null,
    "retention_exceeded": false
  }
}
```

| 필드 | 규칙 |
|---|---|
| `source` | `raw` \| `1m` \| `1h` \| `mixed`(원본+롤업 병합). 시계열·통계 엔드포인트 필수 |
| `truncated` | 행 제한에 걸려 잘렸으면 true. UI는 "더 좁은 범위" 안내 |
| `shown/total` | XLog·검색에서 표시 수와 전체 수. `total`은 `count()` 선조회 값 |
| `sampling` | XLog 10만 점 초과 서버 샘플링(A-5) 적용 여부와 규칙 문자열 |
| `retention_exceeded` | 범위 일부가 원본 보존(30일) 밖이면 true. UI "원본 보존 기간 경과" 표시(D-03) |
| 목록 응답 | `data`는 배열, `meta.next_cursor`로 이어 받는다 |

### 2.6 에러 스키마·상태 코드 (DSN-14-05)

```json
{ "error": { "code": "range_invalid", "message": "to must be after from", "details": [ { "field": "to", "reason": "before_from" } ], "request_id": "01J..." } }
```

| 상태 | 사용 | 대표 코드 |
|---|---|---|
| 400 | 파라미터 검증 실패 | `range_invalid`, `limit_exceeded`, `filter_invalid`, `unsupported_sort`, `payload_too_large` |
| 401 | 미인증·세션 만료·폐기 | `unauthenticated`, `session_expired`, `session_revoked` |
| 403 | 권한 부족·CSRF·객체 소유 | `forbidden`, `csrf_invalid`, `not_owner`, `pii_view_required` |
| 404 | 없음 | `not_found`(존재 여부를 권한으로 숨겨야 하면 403 대신 404) |
| 409 | 상태 충돌 | `already_exists`, `invalid_state`(예: 이미 ACK) |
| 422 | 의미 검증 실패 | `webhook_destination_blocked`, `password_policy`, `template_variable_forbidden`, `remote_key_not_allowed` |
| 423 | 계정 잠금 | `account_locked` |
| 429 | 레이트 리밋 | `rate_limited` + `Retry-After` |
| 500 | 서버 오류 | `internal`(메시지 고정, 상세 없음) |
| 503 | 저장소 불가·시작 중 | `storage_unavailable`, `starting` |
| 504 | 쿼리 타임아웃 | `query_timeout`(UI "범위를 좁히세요") |

에러 본문에 스택·내부 경로·SQL·호스트명을 넣지 않는다. 모든 에러는 `request_id`를 포함하고 서버 로그와 대조된다.

### 2.7 레이트 리밋 (DSN-14-14, SEC-56)

| 버킷 | 한도 | 키 |
|---|---|---|
| 로그인·리프레시·설정 토큰 | 분당 20 | IP |
| 일반 API | 초당 50 | 사용자 |
| 무거운 조회(텍스트 검색, 트레이스 30일, CSV, 알림 미리보기, 삭제 API) | 분당 10 | 사용자 |
| 명령 요청 | 분당 10 | 인스턴스(SEC-24) + 사용자 |
| WebSocket 구독 변경 | 초당 5 | 연결 |

응답 헤더 `X-RateLimit-Limit`, `X-RateLimit-Remaining`, 초과 시 429 + `Retry-After`(초).

### 2.8 OpenAPI 확장 (DSN-14-10)

| 확장 | 값 | 의미 |
|---|---|---|
| `x-argos-role` | `public` \| `viewer` \| `operator` \| `admin` | 최소 역할. 상위 역할 포함. **모든 operation 필수**(린트) |
| `x-argos-audit` | 이벤트 이름(예: `token.create`) | 감사 로그 이벤트 카탈로그(SEC-80)와 1:1. 상태 변경·민감 열람 operation 필수 |
| `x-argos-pii` | `view` | 바인드 파라미터 `full`·환경 덤프 등 추가 권한 `pii.view` 필요 |
| `x-argos-untrusted` | true(스키마 속성) | 수집 유래 문자열. 클라이언트는 텍스트로만 렌더 |
| `x-argos-csv` | true | `format=csv` 지원 |
| `x-argos-limits` | `{ range_max, rows_max, timeout_s }` | 엔드포인트 상한. UI 안내문 생성에 사용 |
| `x-argos-source` | 테이블 목록 | 조회 소스(문서화 목적) |

### 2.9 호환성 diff 린트

- 스펙은 코드에서 생성해 `server/openapi/argos-v1.yaml`로 커밋한다(8장).
- CI: `oasdiff breaking <main> <PR>`이 breaking을 보고하면 PR에 `breaking-change` 라벨이 없을 때 실패. 라벨이 있으면 CHANGELOG `Changed`·업그레이드 가이드 항목 존재를 확인한다.
- Spectral 규칙: `x-argos-role` 누락 실패, 상태 변경 operation의 `x-argos-audit` 누락 실패, 2xx 응답 스키마 누락 실패, `operationId`는 `<resource>_<verb>`(예: `transactions_search`), 수집 유래 문자열 속성의 `x-argos-untrusted` 누락 경고.
- `/admin/audit`에 DELETE·PUT·PATCH operation이 있으면 실패(SEC-82).

### 2.10 신뢰할 수 없는 문자열 (DSN-14-13)

수집 유래 문자열(서비스 이름, URL, SQL, 파라미터, 예외, 스택, 헤더, user_id, 인스턴스·호스트·스레드 이름, 사전 문자열, 명령 결과 본문)은 **API에서 이스케이프하지 않는다**(이중 이스케이프 방지). 대신 ① 스키마에 `x-argos-untrusted: true`, ② 길이 상한(이름류 2KB, SQL 64KB, 명령 결과 1MB)을 적용하고 초과는 `…[truncated]` 접미 + `truncated: true`, ③ 제어 문자(U+0000~U+001F, 탭·개행 제외)는 U+FFFD로 치환한다. 렌더 규칙은 15 문서·SEC-60.

## 3. 소스 선택·시간 범위 규칙 (DSN-14-06)

| 범위 길이 | 소스 | 비고 |
|---|---|---|
| ≤ 6시간 | 원본(`xlog`, `metric`, `*_stat_raw`) | 버킷 10초~1분 |
| ≤ 7일 | `*_1m` | 버킷 1분~1시간 |
| > 7일 | `*_1h` | 버킷 1시간~1일. 최대 1년 |

- **마지막 2분은 항상 원본**에서 계산해 롤업과 병합한다(늦은 도착 보정). 병합 응답은 `source: mixed`.
- **분 경계(D-03)**: 롤업을 쓰는 요청에서 `from`·`to`가 분 경계가 아니면 양 끝의 부분 분을 원본에서 계산해 더한다. 원본이 보존 밖이면 그 부분은 롤업 분 전체로 대체하고 `retention_exceeded: true`.
- 실시간 UI는 자동 갱신 시 최근 5분을 다시 조회한다(D-03). API는 `from`을 5분 전으로 잡은 요청을 캐시하지 않는다.
- 소스 선택은 `source` 파라미터로 강제할 수 없다(일관성). 단, 테스트·정합 검사용으로 `admin` 역할만 `X-Argos-Debug-Source` 헤더를 쓸 수 있다.
- 범위 상한·행 제한·타임아웃은 4장 각 표의 "제한" 열. ClickHouse 쿼리는 `argos_reader`로 `max_execution_time=10`, `max_rows_to_read`, `max_result_rows`를 전달한다. 초과는 504 `query_timeout` 또는 `truncated`.

캐시: 롤업 소스(≤7일·>7일) 응답은 `Cache-Control: private, max-age=30`, 원본·실시간 범위는 `no-store`. 모든 API 응답은 `Vary: Cookie`.

## 4. 엔드포인트

표 열: 메서드·경로 | 역할 | 주요 파라미터 | 응답 요지 | 소스 | 제한. 역할은 최소 역할. `(A)`는 `x-argos-audit` 대상.

### 4.1 인증·부트스트랩

| 메서드·경로 | 역할 | 파라미터 | 응답 | 제한·비고 |
|---|---|---|---|---|
| `GET /setup/status` | public | - | `{ needs_setup, server_version }` | 관리자 존재 시 `needs_setup: false` |
| `POST /setup/admin` (A) | public + 설정 토큰 | `{ setup_token, username, password, ip_mode: full\|masked\|off }` | 201 `{ user }` + 로그인 세션 | 토큰 30분·1회. 관리자 존재 시 404. SEC-47·SEC-31 |
| `GET /auth/csrf` | public | - | `{ csrf_token }` + `argos_csrf` 쿠키 | |
| `POST /auth/login` (A) | public | `{ username, password }` | `{ user, roles, must_change_password, session_expires_at }` + 쿠키 | 423 잠금, 분당 20/IP |
| `POST /auth/refresh` | public(쿠키) | - | `{ session_expires_at }` + 쿠키 회전 | 재사용 감지 → 401 `session_revoked` |
| `POST /auth/logout` (A) | viewer | - | 204 + 쿠키 삭제 | 세션 레코드 폐기, WS 1초 내 종료 |
| `GET /auth/me` | viewer | - | `{ user, roles, permissions: ["pii.view"?], session_expires_at, kiosk_allowed }` | |
| `PUT /auth/password` (A) | viewer | `{ current, new }` | 204 | SEC-48 정책, 성공 시 다른 세션 폐기 |

### 4.2 전체 현황 (F-U17)

| 메서드·경로 | 역할 | 파라미터 | 응답 | 소스 | 제한 |
|---|---|---|---|---|---|
| `GET /overview` | viewer | `env`, `group`, `kind` | `data.apps[]`: `{ app_id, name, env, group, status(UP\|WARN\|STALE\|DOWN 최악 기준), instances: {up,total}, tps, p95_ms, error_rate, active, batch_running, firing: {critical, warn}, last_deploy: {ts, version}, sparkline: {ts[], tps[], p95[], err[]}(1시간·60점) }`, `data.alerts_firing_total`, `data.server: { ingest_lag_s, disk_usage_ratio, channel_failures_24h, sessions, cert_expiry_days }` | `app_stat_1m`(최근 1h), 세션 레지스트리, 알림 상태, 자기 메트릭 | 앱 500개. 5초 캐시 |
| `GET /overview/alerts` | viewer | `env`, `severity`, `state` | 전 앱 FIRING·ACKED 목록(4.13 이벤트 스키마) | PostgreSQL | 1,000행 |

### 4.3 앱·인스턴스

| 메서드·경로 | 역할 | 파라미터 | 응답 | 제한·비고 |
|---|---|---|---|---|
| `GET /apps` | viewer | `env`, `group`, `q` | `[{ app_id, name, env, group, instances_total, instances_up, registration_policy, recommended_rules_applied, last_seen_at, apdex_t_ms }]` | 500 |
| `POST /apps` (A) | admin | `{ name, env, group?, apdex_t_ms?=500, registration_policy?: auto\|approval }` | 201 | (name, env) 중복 409 |
| `GET /apps/{appId}` | viewer | - | 앱 + 설정 요약 | |
| `PATCH /apps/{appId}` (A) | admin | `{ group?, apdex_t_ms?, registration_policy?, app_packages?[] }` | 200 | Apdex T 변경은 이후부터(D-17), 이벤트 마커 생성 |
| `DELETE /apps/{appId}` (A) | admin | `?confirm=<name>` | 202 | 토큰 폐기·세션 종료·데이터는 TTL. 이름 확인 필수 |
| `GET /apps/{appId}/instances` | viewer | `state`, `archived=false` | `[{ instance_id, name, host, pid, state, agent_version, compat: ok\|old\|unsupported, config_hash, config_drift, last_heartbeat_at, clock_skew_ms, drops_24h, modules_disabled[], ip_mode, virtual_threads, cgroup, started_at, version }]` | 1,000 |
| `GET /instances/{instanceId}` | viewer | - | 인스턴스 메타 전체(05 문서 2.7 상단 항목) | |
| `POST /instances/{instanceId}/archive` (A) | operator | - | 204 | |
| `DELETE /instances/{instanceId}` (A) | admin | - | 204 | 보관 상태만 삭제 가능 |
| `GET /instances/{instanceId}/effective-config` | operator | - | `[{ key, value(마스킹), source: file\|env\|server, remote_allowed }]` | 토큰·비밀값은 `****` |

### 4.4 대시보드 (F-U01, F-U19, F-U20)

| 메서드·경로 | 역할 | 파라미터 | 응답 | 소스 | 제한 |
|---|---|---|---|---|---|
| `GET /apps/{appId}/dashboard` | viewer | `from`, `to`, `kind`, `step?`, `baseline?: 1d\|7d`, `instance[]` | `data.tiles: { tps, avg_ms, p95_ms, p99_ms, error_rate, active, delta: {basis: prev\|7d, tps, p95_ms, error_rate} }`, `data.series: { ts[], tps[], avg_ms[], p95_ms[], p99_ms[], error_rate[], active[], heap_used[], heap_max[], gc_pause_ms[], cpu[], tomcat_busy_ratio[], pool_usage_ratio: {pool: []}, ext_time_ms[], ext_errors[] }`, `data.baseline?: 같은 구조(offset 적용)`, `data.instances[]`(타일), `data.firing[]`, `data.collection: { agent_drops, server_drops, server_drops_quota, ingest_lag_s, clock_skew_instances, excluded, late, batch_running }`, `data.events[]`(4.12 스키마), `data.outages[]: [{from, to, reason}]`(D-18) | `app_stat_*`, `metric*`, 세션·알림·이벤트 | 범위 ≤ 1년, 버킷 ≤ 720 |
| `GET /apps/{appId}/instances/compare` | viewer | `from`, `to`, `kind` | `[{ instance_id, name, state, tps, p95_ms, error_rate, active, heap_ratio, cpu, pool_usage_ratio, uptime_s, outlier: { p95, error_rate, cpu }(앱 평균 대비 2σ) }]` | `service_stat_*` 인스턴스 축(13 문서에 `instance_stat_1m` 요청), `metric_1m` | |

### 4.5 XLog (F-U02)

| 메서드·경로 | 역할 | 파라미터 | 응답 | 소스 | 제한 |
|---|---|---|---|---|---|
| `GET /apps/{appId}/xlog` | viewer | `from`, `to`, `kind`, `service[]`, `instance[]`, `error?`, `min_ms?`, `max_ms?`, `limit≤100000`, `color_by?: status\|instance\|service` | **컬럼형 JSON**: `data: { tx_id: string[](u64 문자열), ts: number[](epoch ms), elapsed_ms: number[], status: number[](0 정상 1 지연 2 에러), instance_id: number[], service_id: number[], kind: number[] }`, `data.dictionary: { service: {id: name}, instance: {id: name} }`, `meta.shown/total/sampling`, `meta.slow_threshold_ms`, `meta.outages[]` | `xlog` | 범위 ≤ 24h. 10만 점 초과 시 A-5 샘플링(결정적). 응답 gzip. 2초(N-07) |
| `POST /apps/{appId}/xlog/summary` | viewer | `{ from, to, min_ms, max_ms, kind, service[], instance[] }`(드래그 영역) | `{ count, by_service: [{service_id, name, count, avg_ms, max_ms, errors}](상위 10), by_instance[], by_error_class[], by_status[] }` | `xlog` | 범위 ≤ 24h, 1초 |

`format=binary`(application/x-argos-xlog, 32바이트 고정 레코드)는 1.x 예약. 1.0은 컬럼형 JSON + gzip으로 N-07을 만족한다(PoC P-5에서 10만 점 전송·파싱 시간 측정).

### 4.6 트랜잭션 검색·상세 (F-U02, F-U14)

| 메서드·경로 | 역할 | 파라미터 | 응답 | 소스 | 제한 |
|---|---|---|---|---|---|
| `POST /apps/{appId}/transactions/search` | viewer | `{ from, to, kind, filters: { service_id[], instance_id[], http_status[], error?, error_class_id[], error_group_id?, min_ms, max_ms, remote_ip?(모드 masked면 /24), user_id?, request_id?, sql_id?(SQL 포함, A-10), version_id[], has_profile?, trace_id? }, sort: elapsed_ms:desc\|ts:desc, cursor, limit≤1000 }` | `[{ tx_id, ts, service, elapsed_ms, http_status, error_class, instance, sql_count, sql_time_ms, ext_count, ext_time_ms, user_id?, has_profile, kind, version }]` | `xlog`(+ bloom `tx_id`·`trace_id`·`sql_ids`) | 범위 ≤ 24h. `user_id`·`request_id` 검색은 1시간 3초(N-24). 텍스트 필터 각 1KB |
| `GET /transactions/{txId}` | viewer | `ts?`(힌트) | 상세(아래 스키마) | `xlog` 1행(ts는 `tx_id >> 23`으로 복원) + `profile_step` + 사전 | 0.5초. 없으면 404 |
| `GET /transactions/{txId}/baseline` | viewer | - | `{ service_p50_ms, service_p95_ms, percentile_of_this, sample_normal_tx_id }` | `service_stat_1m`(±30분), `xlog` | 같은 서비스·같은 인스턴스 우선 |
| `GET /transactions/{txId}/export` (A: `transaction.export`) | viewer | - | 상세 JSON 파일(`Content-Disposition`) | | 분당 10 |

트랜잭션 상세 스키마(요지)

```json
{
  "tx_id": "7312...", "trace_id": "4bf9...", "span_id": "a1..", "parent_span_id": null,
  "app": {"app_id": 3, "name": "order-api", "env": "prod"}, "instance": {"instance_id": 12, "name": "web-01:8080"},
  "kind": "HTTP", "service": "GET /api/orders/{id}", "url": "/api/orders/123", "http_method": "GET", "http_status": 500,
  "ts": "2026-10-23T01:02:03.456Z", "elapsed_ms": 1832, "error": true, "error_class": "java.sql.SQLTimeoutException",
  "error_group_id": "9f3...", "version": "1.4.2", "thread": "http-nio-8080-exec-7",
  "cpu_time_ms": 12, "alloc_bytes": 4194304, "measured": {"cpu": true, "alloc": true},
  "sql": {"count": 23, "time_ms": 1650, "wait_ms": 1200}, "ext": {"count": 1, "time_ms": 90},
  "remote_ip": "10.0.1.0", "ip_mode": "masked", "user_id": null, "user_id_mode": "off",
  "headers": {"X-Request-Id": "..."}, "caller": {"app_id": 1, "service": "POST /checkout", "instance_id": 4},
  "profile": {"state": "captured|sampled_out|lost", "steps": [
    {"seq": 1, "parent_seq": 0, "type": "DBC", "start_offset_ms": 3, "elapsed_ms": 1200, "name": "HikariPool-1", "status": 0},
    {"seq": 2, "parent_seq": 0, "type": "SQL", "start_offset_ms": 1203, "elapsed_ms": 410, "exec_ms": 400, "fetch_ms": 10, "rows": 1,
     "sql_id": "c1..", "sql": "SELECT ... WHERE id = ?", "target": "mysql://db1/shop", "params": "[Long]", "params_mode": "masked", "status": 0, "repeat": 1},
    {"seq": 9, "parent_seq": 0, "type": "STACK", "start_offset_ms": 1000, "frames": ["com.shop.OrderService.load(OrderService.java:88)", "..."]}
  ], "self_time_ms": 120, "self_time_ratio": 0.07, "step_truncated": false},
  "flags": {"streaming": false, "timeout": false},
  "links": {"trace": "/traces/4bf9...?ts=...", "logs": "https://kibana/...?q=trace_id:4bf9..."}
}
```

- `params`는 모드에 따라 `masked`(타입만) / `partial` / `full`(응답에 `x-argos-pii`, 요청자가 `pii.view` 없으면 `masked`로 강등하고 `params_mode: "masked_by_role"`). `full` 열람은 감사 `pii.view`.
- `measured.cpu/alloc=false`면 UI는 "측정 불가"(D-23).
- 연속 반복 step은 서버가 접지 않는다. UI가 `sql_id`·`parent_seq` 동일 연속 구간을 접는다(15 문서).

### 4.7 트레이스 (F-U03)

| 메서드·경로 | 역할 | 파라미터 | 응답 | 소스 | 제한 |
|---|---|---|---|---|---|
| `GET /traces/{traceId}` | viewer | `ts?`(힌트), `window?: 1h\|24h\|7d\|30d`(기본 24h, 힌트 있으면 ±1h) | `{ trace_id, root_tx_id, nodes: [{ tx_id, app, env, service, instance, ts, elapsed_ms, status, error, version, parent_tx_id, caller_step: {elapsed_ms, network_wait_ms}, profile_state }], external_nodes: [{ host, path_pattern, elapsed_ms, status, caller_tx_id }], missing: [{ parent_tx_id, reason: not_instrumented\|not_yet_collected }], critical_path: tx_id[] }` | `xlog`(bloom `trace_id`, 상위 4B 시각 힌트) + `profile_step`(HTTP step) | 트랜잭션 ≤ 500, 30일은 "무거운 조회" 버킷 |

### 4.8 통계 (F-U05~F-U07, F-U11)

| 메서드·경로 | 역할 | 파라미터 | 응답 | 소스 | 제한 |
|---|---|---|---|---|---|
| `GET /apps/{appId}/services/stats` | viewer | `from`, `to`, `kind`, `sort(count\|error_rate\|avg_ms\|p95_ms\|p99_ms\|max_ms\|apdex\|sum_ms)`, `limit≤500`, `split_version?`, `compare_prev?` | `[{ service_id, service, version?, count, errors, error_rate, avg_ms, p50_ms, p95_ms, p99_ms, max_ms, apdex, sum_ms, sql_time_ratio, ext_time_ratio, delta?: {count, p95_ms, error_rate} }]` | `service_stat_1m/1h` | 1초 |
| `GET /apps/{appId}/services/{serviceId}` | viewer | `from`, `to`, `kind`, `step?` | `{ service, series: {ts[], count[], p95_ms[], p99_ms[], error_rate[]}, by_version[], by_instance[], top_sql[](10), top_external[](10), top_error_groups[](10) }` | 롤업 + `sql_stat_*`(서비스 축은 13 문서 요청) | |
| `GET /apps/{appId}/sql/stats` | viewer | `from`, `to`, `kind`, `db_id?`, `sort(sum_ms\|count\|avg_ms\|max_ms\|errors)`, `limit≤500`, `compare_prev?` | `[{ sql_id, sql_normalized(2KB), db: {db_id, vendor, host, name}, count, errors, sum_ms, avg_ms, max_ms, fetch_rows, delta? }]` | `sql_stat_1m/1h`(전수, D-12) | 1초 |
| `GET /apps/{appId}/sql/{sqlId}` | viewer | `from`, `to` | `{ sql_normalized, sql_text(64KB, truncated), norm_version, series, by_db[], by_service[](count·share), by_instance[], wait_ms_total, related_tx: {note: "profiled only", items[]} }` | `sql_stat_*`, 사전, `xlog.sql_ids`(A-10) | |
| `GET /apps/{appId}/external/stats` | viewer | `from`, `to`, `sort`, `limit≤500` | `[{ host_id, host, path_pattern?, count, p50_ms, p95_ms, errors, timeouts, connect_failures, delta? }]` | `ext_stat_1m/1h` | |
| `GET /apps/{appId}/external/{hostId}` | viewer | `from`, `to` | `{ host, series, by_service[], related_tx[] }` | | |
| `GET /apps/{appId}/errors/groups` | viewer | `from`, `to`, `kind`, `new_only?`, `sort(count\|first_seen\|last_seen)`, `limit≤500` | `[{ group_id, error_class, message_pattern(1KB, 마스킹됨), top_frame, count, first_seen, last_seen, is_new, services[](상위 5), versions[] }]` | `error_stat_1m` + PG `error_groups` | |
| `GET /apps/{appId}/errors/groups/{groupId}` | viewer | `from`, `to` | `{ ...group, sample_stack[](앱 프레임 표시), series, by_version[], by_instance[], by_service[], recent_tx[](10) }` | + `xlog.error_group_id` | |
| `GET /apps/{appId}/map` | viewer | `from`, `to`, `kind` | `{ nodes: [{ id, type: app\|db\|external, name, env?, calls, error_rate, p95_ms }], edges: [{ from, to, calls, error_rate, p95_ms }] }` | `xlog.caller_app_id`, `sql_stat`, `ext_stat` | S(F-U11) |

### 4.9 액티브 트랜잭션 (F-U04)

| 메서드·경로 | 역할 | 파라미터 | 응답 | 소스 | 제한 |
|---|---|---|---|---|---|
| `GET /apps/{appId}/active` | viewer | `instance[]`, `kind`, `state?`, `min_elapsed_ms?` | `{ snapshot_at, per_instance: [{instance_id, count, max_elapsed_ms, buckets: [n<1s, n1_3s, n3_8s, n8s+]}], items: [{ instance_id, tx_id, service, elapsed_ms, state: SQL\|HTTP\|DBC\|METHOD, state_detail(sql 요약·호스트·풀), thread, user_id?, remote_ip?, kind, started_at }] }` | 메모리(5초 스냅샷) | 2,000행. WS `active` 토픽과 동일 스키마 |

### 4.10 인스턴스 메트릭·명령 (F-U08, F-A12)

| 메서드·경로 | 역할 | 파라미터 | 응답 | 소스 | 제한 |
|---|---|---|---|---|---|
| `GET /instances/{instanceId}/metrics` | viewer | `names[]`(≤50), `from`, `to`, `step?` | `{ series: [{ name, labels, kind: gauge\|counter, unit, ts[], value[](null 결측) }] }` | `metric`/`metric_1m`/`metric_1h` | 시리즈 50, 포인트 2,000/시리즈 |
| `GET /instances/{instanceId}/metrics/names` | viewer | - | 이름·라벨·종류·단위 목록 | 레지스트리 | |
| `POST /instances/{instanceId}/commands` (A: `command.request`) | operator / admin | `{ type: thread_dump\|thread_stack\|self_diagnosis\|reload_config\|env_dump, args?: { thread_id?, tx_id? } }` | 202 `{ command_id(UUID), status: queued, ttl_s: 60 }` | PG `commands` → 세션 스트림 | `env_dump`·`reload_config`는 admin(SEC-21). 분당 10/인스턴스. 인스턴스 DOWN이면 409 `instance_offline` |
| `GET /commands/{commandId}` | 요청자 또는 admin | - | `{ command_id, type, instance_id, requested_by, requested_at, status: queued\|sent\|done\|failed\|expired, duration_ms, result_size, error? }` | | 타인 조회 403(SEC-50) |
| `GET /commands/{commandId}/result` (A: `command.result.view`) | 요청자 또는 admin | `format?: json\|text` | 스레드 덤프 파싱 결과 `{ threads: [{ id, name, state, virtual, locks_held[], waiting_on, frames[], tx_id? }], deadlocks[], truncated }` 또는 원문 다운로드 | PG(복호화) | `env_dump` 결과는 마스킹된 상태로만 존재(SEC-20) |
| `GET /instances/{instanceId}/commands` | operator | `limit≤100` | 명령 이력 | | |

### 4.11 전역 검색 (F-U14)

| 메서드·경로 | 역할 | 파라미터 | 응답 | 소스 | 제한 |
|---|---|---|---|---|---|
| `GET /search` | viewer | `q`(≤1KB), `type?: auto\|tx\|trace\|user\|request\|service\|sql\|instance`, `app?`, `env?`, `from?`, `to?` | `{ matches: [{ type, label, target: {route, params} , score }] }`. `auto`: 숫자 19~20자리 → tx, 32 hex → trace, 그 외 텍스트 | ID 조회(M): `xlog` bloom / 텍스트(S): PG `dictionary` `pg_trgm` | `user`·`request`는 `from/to` 필수(1시간 3초, N-24). 텍스트는 분당 10 |

### 4.12 이벤트 (F-S18)

| 메서드·경로 | 역할 | 파라미터 | 응답 | 제한·비고 |
|---|---|---|---|---|
| `POST /events` (A: `event.create`) | operator 또는 **앱 토큰 Bearer** | `{ app(또는 app_id), env, type: deploy\|custom, title(256), version?, detail?(2KB), ts?(기본 now) }` | 201 `{ event_id }` | 토큰 인증 시 토큰의 앱만. 분당 60/앱 |
| `GET /apps/{appId}/events` | viewer | `from`, `to`, `type[]` | `[{ event_id, ts, type: deploy\|start\|stop\|config\|state\|apdex\|custom, title, version, detail, instance_id?, actor? }]` | 1,000행. 자동 이벤트(기동·종료·설정 변경·상태 전이)는 서버가 생성 |
| `DELETE /events/{eventId}` (A) | admin | - | 204 | `custom`·`deploy`만 |

### 4.13 알림 (F-S06·S17·S24~S27·S31, F-U09)

| 메서드·경로 | 역할 | 파라미터 | 응답 | 제한·비고 |
|---|---|---|---|---|
| `GET /alerts/rules` | viewer | `app?`, `env?`, `enabled?` | 규칙 목록(아래 규칙 스키마) | 1,000 |
| `POST /alerts/rules` (A) | operator | 규칙 | 201 | 템플릿 변수 검증(SEC-39) → 422 `template_variable_forbidden` |
| `GET /alerts/rules/{ruleId}` | viewer | - | 규칙 + 변경 이력 요약 | |
| `PUT /alerts/rules/{ruleId}` (A) | operator | 규칙 | 200 | 채널 URL 변경 불가(채널 선택만) |
| `DELETE /alerts/rules/{ruleId}` (A) | operator | - | 204 | |
| `POST /alerts/rules/{ruleId}/enable` · `/disable` (A) | operator | - | 204 | |
| `POST /alerts/rules/{ruleId}/clone` (A) | operator | `{ app, env }` | 201 | |
| `POST /alerts/rules/preview` | operator | `{ rule(저장 전 가능), days: 7 }` | `{ firings: [{ts_start, ts_end, peak_value}], count, max_duration_s, series: {ts[], value[]} }` | 무거운 조회 버킷 |
| `GET /alerts/templates` | viewer | - | 부록 A 템플릿 14종 `{ key, name, defaults, applies_when }` | |
| `POST /alerts/templates/apply` (A) | operator | `{ app, env, keys[]?(기본 전부) }` | `{ created: [rule_id], skipped: [{key, reason}] }` | 앱 등록 시 UI가 호출 |
| `GET /alerts/channels` | viewer | - | `[{ channel_id, type: email\|slack\|webhook, name, target_masked, last_success_at, last_failure_at, consecutive_failures, signing_enabled }]` | URL은 항상 마스킹(SEC-52) |
| `POST /alerts/channels` (A) | admin | `{ type, name, target(URL·수신자), secret?, allow_http? }` | 201 | 목적지 검증 실패 → 422 `webhook_destination_blocked`(SEC-51) |
| `PUT /alerts/channels/{id}` (A) | admin | 재입력 필드만 | 200 | |
| `DELETE /alerts/channels/{id}` (A) | admin | - | 204 | 규칙이 참조 중이면 409 |
| `POST /alerts/channels/{id}/test` (A) | admin | - | `{ delivered, status_code, duration_ms }` | 같은 목적지 검증. 응답 본문 비노출 |
| `GET /alerts/events` | viewer | `from`, `to`, `app?`, `env?`, `severity?`, `state?`, `rule?`, `delivery?: failed`, `cursor`, `limit≤500` | `[{ event_id, ts, app, env, target: {type, id, name}, rule: {id, name}, severity, state: PENDING\|FIRING\|ACKED\|RESOLVED\|NO_DATA, value, threshold, duration_s, deliveries: [{channel_id, status, attempts, last_error_code}], acked_by?, acked_at?, note?, deeplink }]` | 전 앱은 `/overview/alerts` |
| `POST /alerts/events/{eventId}/ack` (A) | operator | `{ note?(1KB) }` | 200 | FIRING만 → 409 |
| `GET /alerts/mutes` · `POST /alerts/mutes` (A) · `DELETE /alerts/mutes/{id}` (A) | operator | `{ target: {app?, env?, instance?, rule?}, from, to, reason }` | | 최대 30일 |
| `GET /alerts/stats` | viewer | `days: 7\|30`, `app?` | `[{ rule_id, name, firings, avg_duration_s, avg_time_to_ack_s, avg_time_to_resolve_s }]` | 노이즈 규칙 식별용 |

규칙 스키마(JSON, 02 §4.2·운영자 리뷰 YAML 모델과 동일 필드)

```json
{
  "rule_id": "uuid", "name": "p95 지연", "enabled": true, "template_key": "p95_latency",
  "target": {"app_id": 3, "env": "prod", "type": "app|instance|service|ext_host", "ids": []},
  "kind": "HTTP", "metric": "response_time_p95",
  "window": "1m", "for": "5m", "evaluation_unit": "app",
  "condition": {"operator": ">=", "warn": 3000, "critical": 10000},
  "recover": {"threshold": 2000, "for": "2m"},
  "min_samples": 20, "no_data": "ignore", "warmup_grace": "3m",
  "active_time": [{"days": ["Mon","Tue","Wed","Thu","Fri"], "from": "00:00", "to": "24:00"}],
  "mute_schedules": [{"cron": "0 2 * * *", "duration": "2h", "reason": "daily batch"}],
  "channels": {"warn": ["ch-slack-ops"], "critical": ["ch-slack-ops", "ch-webhook-sms"]},
  "renotify_interval": "30m", "runbook_url": "https://wiki/runbook/p95",
  "message_template": "{{app}}/{{env}} {{service}} p95 {{value}}ms >= {{threshold}}ms for {{duration}} {{link}}"
}
```

허용 템플릿 변수: `app`, `env`, `instance`, `service`, `host`, `metric`, `value`, `threshold`, `severity`, `duration`, `link`, `runbook`, `rule`. 그 외 참조는 422.

### 4.14 관리

| 메서드·경로 | 역할 | 파라미터 | 응답 | 제한·비고 |
|---|---|---|---|---|
| `GET /admin/pending-registrations` | admin | `env?` | `[{ id, name, env, host, first_attempt_at, last_attempt_at, attempts }]` | F-S30 |
| `POST /admin/pending-registrations/{id}/approve` (A) | admin | `{ group?, apdex_t_ms? }` | 201 `{ app, token(1회) }` | |
| `POST /admin/pending-registrations/{id}/reject` (A) | admin | - | 204 | |
| `GET /admin/apps/{appId}/tokens` | admin | - | `[{ token_id, prefix(8), created_by, created_at, expires_at, last_used_at, state: active\|rotating\|revoked, instances_using }]` | 원문 없음(SEC-02) |
| `POST /admin/apps/{appId}/tokens` (A: `token.create`) | admin | `{ expires_in_days?: 365 }` | 201 `{ token_id, token: "argos_..." }` **1회만** | 앱당 활성 5개 → 409 |
| `POST /admin/tokens/{tokenId}/rotate` (A) | admin | `{ grace_days?: 7 }` | 201 새 토큰 1회 + 구 토큰 `rotating`(만료 시각) | SEC-04 |
| `POST /admin/tokens/{tokenId}/revoke` (A) | admin | `{ reason }` | 204 | 활성 세션 5초 내 종료 |
| `GET /admin/apps/{appId}/config` | admin | - | `{ remote_allowed_keys[](교집합·인스턴스별 차이), current: {key: value}, drift: [{instance_id, key, value}] }` | F-S29 |
| `POST /admin/apps/{appId}/config/changes` (A) | admin | `{ reason(필수, 1KB), changes: {key: value}, targets: {all: true} \| {instance_ids[]} }` | 201 `{ change_id, preview: {diff: [{key, before, after}], rejected_keys_by_instance: [{instance_id, keys[]}]} , status: previewed }` | 하드 캡 밖 키는 미리보기에서 경고, 적용 시 거부로 기록(SEC-23) |
| `POST /admin/config/changes/{changeId}/apply` (A) | admin | - | 202 `{ status: applying }` | N-25 10초 내 결과 |
| `GET /admin/config/changes/{changeId}` | admin | - | `{ ...change, results: [{ instance_id, status: applied\|rejected\|offline\|pending, rejected_keys[], applied_at }] }` | WS `admin` 토픽으로도 푸시 |
| `POST /admin/config/changes/{changeId}/revert` (A) | admin | `{ reason }` | 201 새 change(이전값) | 한 번의 동작 |
| `GET /admin/apps/{appId}/config/changes` | admin | `cursor`, `limit≤100` | 변경 이력 | |
| `POST /admin/apps/{appId}/kill-switch` (A) | admin | `{ enabled: bool, instance_ids?[], reason }` | 202 | 설정 변경(`argos.enabled`)의 특수 경로. 결과는 config change와 동일 |
| `GET /admin/users` · `POST /admin/users` (A) | admin | `{ username, email?, roles[], must_change_password: true }` | | 초기 비밀번호는 응답 1회 또는 초기화 링크 |
| `PATCH /admin/users/{id}` (A) | admin | `{ roles?, enabled?, permissions?: ["pii.view"], kiosk_session?: {allowed, ip_cidrs[]} }` | 200 | 역할 변경 시 세션 폐기 |
| `POST /admin/users/{id}/sessions/revoke` (A) · `/password/reset` (A) · `/unlock` (A) | admin | | 204 | |
| `GET /admin/audit` | admin | `from`, `to`, `actor?`, `action?`, `target_type?`, `target_id?`, `cursor`, `limit≤500` | `[{ ts, actor: {type: user\|system\|agent, id, name}, ip, user_agent, action, target: {type, id, name}, result, before?, after?, request_id, pii_subject? }]` | 읽기 전용(SEC-82). PUT·PATCH·DELETE 없음 |
| `GET /admin/audit/export` (A: `audit.export`) | admin | 같은 필터, `format=csv` | CSV | 분당 10 |
| `DELETE /admin/privacy/subjects` (A: `privacy.delete`) | admin | `?user_id=…` 또는 `?ip=…`, `from`, `to`(필수, ≤ 30일) | 202 `{ job_id }` | SEC-33. 롤업 제외 명시 |
| `GET /admin/privacy/jobs/{jobId}` | admin | - | `{ status: queued\|running\|done\|failed, tables: [{name, mutation_id, done}], rows_affected }` | |
| `GET /admin/settings/retention` · `PUT` (A) | admin | `{ xlog_days, profile_days, stat_raw_days, metric_raw_days, rollup_1m_days, rollup_1h_days }` | | 적용은 다음 TTL 주기 + 러너(N-08). 단축은 확인 필드 |
| `GET /admin/settings/privacy` · `PUT` (A) | admin | `{ default_ip_mode, mask_patterns[], query_string_mode, trusted_proxies[] }` | | 서버 기본값. 에이전트 로컬 설정이 우선(하드 캡) |
| `GET /admin/storage/health` | operator | - | `{ disk: {used_ratio, free_bytes}, parts: [{table, partition, active}], merges, delayed_inserts, rejected_inserts, oldest_unmerged_part_age_s, ttl_last_run_at, dictionary_unresolved, wal: {bytes, segments}, emergency_retention_active }` | F-S23 |
| `GET /about/licenses` | viewer | - | `{ server: [...], ui: [...], agent: [...] }`(이름·버전·라이선스) | O-L04 |
| `GET /about` | viewer | - | `{ version, commit, protocol_version, agent_versions: {ver: count}, uptime_s }` | O-R12 |

### 4.15 수집 상태 (F-S10)

| 메서드·경로 | 역할 | 파라미터 | 응답 | 소스 |
|---|---|---|---|---|
| `GET /apps/{appId}/collection-health` | viewer | `from`, `to` | `{ agent_drops: {ts[], value[]}, server_drops: {...}, server_drops_quota: {...}, excluded: {ts[], value[], by_reason}, ingest_lag_s: {...}, late_rows, clock_skew: [{instance_id, skew_ms}], outages: [{from, to, reason: server_down\|session_lost\|queue_overflow}], per_instance: [{instance_id, drops, excluded, skew_ms, lag_s}] }` | 자기 메트릭(ClickHouse `metric` 테이블의 `argos_*` 시리즈) + 세션 이벤트 |
| `GET /server/health` | viewer | - | 전체 현황용 요약(4.2 `server`와 동일) | |

### 4.16 Actuator

| 경로 | 인증 | 내용 |
|---|---|---|
| `GET /actuator/health` | 없음 | `{ status }`만(`show-details: never`) |
| `GET /actuator/info` | viewer 세션 | 버전·커밋·프로토콜 |
| `GET /actuator/prometheus` | 베이직 또는 전용 베어러(`argos.metrics.token`) + IP 허용 목록 | OpenMetrics. 02 §7 명명 규약 |
| 그 외 | 비활성(404) | env·heapdump·threaddump·loggers |

### 4.17 CSV 내보내기 (DSN-14-12, SEC-63)

- `x-argos-csv: true`인 목록 엔드포인트(트랜잭션 검색, 서비스·SQL·외부·에러 통계, 인스턴스 비교, 알림 이력, 감사 로그)에 `format=csv`.
- 응답 `text/csv; charset=utf-8`, UTF-8 BOM, `Content-Disposition: attachment; filename="argos-<resource>-<from>-<to>.csv"`.
- 모든 셀을 큰따옴표로 감싸고, 값이 `=`, `+`, `-`, `@`, 탭, CR로 시작하면 `'`를 접두한다. 개행은 `\n`으로 치환.
- 행 상한 10,000(초과 시 400 `limit_exceeded`, UI 안내). 시각은 `tz` 파라미터가 있으면 그 시간대 + 오프셋 표기, 없으면 UTC.
- 감사 이벤트 `export.csv`(화면·필터·행 수)를 남긴다(SEC-80). 분당 10/사용자.

## 5. WebSocket 프로토콜 (DSN-14-09, F-S03, SEC-44)

### 5.1 연결

- 경로 `wss://<host>/ws`(TLS 종단에 따라 `ws://`는 서버 설정 `allow-insecure-ws`일 때만). 핸드셰이크에서 `argos_at` 쿠키 세션을 검증하고 `Origin`이 서버 외부 URL(`argos.server.external-url`)과 일치해야 한다. 실패는 HTTP 401/403으로 핸드셰이크 거부.
- 연결당 구독 ≤ 20, 수신 메시지 ≤ 4KB, 사용자당 연결 ≤ 5. 초과는 `error` 프레임 후 유지 또는 close.
- 세션 폐기(로그아웃·역할 변경·만료) 시 서버가 1초 내 close `4401`(unauthenticated). 역할 강등으로 구독 권한을 잃으면 해당 구독만 `error` + 해지.
- 액세스 토큰 만료 전 UI는 `/auth/refresh`를 호출한다. WS 자체는 재인증하지 않으며, 세션 레코드가 유효한 동안 유지된다.

### 5.2 프레임

클라이언트 → 서버

```json
{"op": "subscribe",   "id": "s1", "topic": "xlog",    "params": {"app": 3, "env": "prod", "kind": "HTTP", "service": [], "instance": []}}
{"op": "subscribe",   "id": "s2", "topic": "counter", "params": {"app": 3}}
{"op": "subscribe",   "id": "s3", "topic": "active",  "params": {"app": 3}}
{"op": "subscribe",   "id": "s4", "topic": "alert",   "params": {"env": "prod"}}
{"op": "subscribe",   "id": "s5", "topic": "overview","params": {"env": "prod"}}
{"op": "subscribe",   "id": "s6", "topic": "admin",   "params": {"change_id": "..."}}
{"op": "unsubscribe", "id": "s1"}
{"op": "ping", "t": 1729645323456}
```

서버 → 클라이언트

```json
{"op": "ack",   "id": "s1", "topic": "xlog", "since": "2026-10-23T01:02:03.456Z"}
{"op": "push",  "id": "s1", "topic": "xlog", "t": "2026-10-23T01:02:03.500Z",
 "data": {"tx_id": ["..."], "ts": [..], "elapsed_ms": [..], "status": [..], "instance_id": [..], "service_id": [..], "kind": [..]},
 "dict": {"service": {"17": "GET /api/orders/{id}"}}, "dropped": 0}
{"op": "push",  "id": "s2", "topic": "counter", "t": "...", "data": {"tps": 412.4, "avg_ms": 83, "p95_ms": 410, "p99_ms": 1200, "error_rate": 0.012, "active": 37, "heap_used": .., "gc_pause_ms": .., "cpu": .., "per_instance": [{"instance_id": 12, "active": 9, "state": "UP"}]}}
{"op": "push",  "id": "s3", "topic": "active",  "t": "...", "data": { "snapshot_at": "...", "per_instance": [..], "items": [..] }}
{"op": "push",  "id": "s4", "topic": "alert",   "t": "...", "data": { "event": { ...4.13 알림 이벤트 스키마 } }}
{"op": "push",  "id": "s5", "topic": "overview","t": "...", "data": { "apps": [ { "app_id": 3, "status": "WARN", "tps": .., "p95_ms": .., "error_rate": .., "active": .., "firing": {..} } ] }}
{"op": "push",  "id": "s6", "topic": "admin",   "t": "...", "data": { "change_id": "...", "instance_id": 12, "status": "applied" }}
{"op": "error", "id": "s9", "code": "forbidden|limit_exceeded|invalid_params|topic_unknown", "message": "..."}
{"op": "pong",  "t": 1729645323456, "server_t": "2026-10-23T01:02:03.700Z"}
{"op": "notice", "code": "backpressure", "topic": "xlog", "dropped": 1250}
```

### 5.3 토픽 규칙

| 토픽 | 주기 | 내용 | 역할 |
|---|---|---|---|
| `xlog` | 100ms 묶음 | 종료 트랜잭션 점(컬럼형, 4.5와 동일 필드). 도착이 `ts + 60s`를 넘는 행은 제외(D-15) | viewer |
| `counter` | 5초 | 앱 카운터(대시보드 타일·시계열 마지막 점·인스턴스별 액티브·상태) | viewer |
| `active` | 5초 | 액티브 스냅샷(4.9와 동일) | viewer |
| `alert` | 즉시 | 알림 상태 전이·전송 결과 | viewer |
| `overview` | 5초 | 전체 현황 행 변동분 | viewer |
| `admin` | 즉시 | 설정 변경 적용 결과, 킬 스위치 결과, 명령 상태 전이(`command_id`) | admin(명령 상태는 요청자) |

- 백프레셔: 구독자 송신 버퍼가 1MB를 넘으면 가장 오래된 `xlog` 묶음부터 버리고 `notice backpressure`로 드롭 수를 알린다. `counter`·`active`는 최신 1건만 유지.
- 서버 측 시간 `t`는 묶음 생성 시각. 클라이언트는 `ping/pong`으로 왕복 지연을 측정해 "N초 전 기준" 표시에 쓴다.
- 재접속: 클라이언트는 지수 백오프(1s→30s)로 재접속하고, 마지막 수신 `t`부터 현재까지를 REST(`/xlog`, `/dashboard`)로 보충한다(2.0 공통 규칙). 서버는 재전송하지 않는다.
- 하트비트: 클라이언트 `ping` 30초, 서버는 60초 무응답 시 close `4408`.
- close 코드: `4401` 미인증·세션 폐기, `4403` Origin 불일치, `4408` 타임아웃, `4429` 연결 수 초과, `1012` 서버 재시작.

## 6. 딥링크 URL 상태 ↔ API 매핑 (DSN-14-17, F-U14, SEC-64)

전역 쿼리 키(모든 라우트 공통)

| 키 | 값 | API 파라미터 | 비고 |
|---|---|---|---|
| `app` | app_id | `app` / `{appId}` | |
| `env` | string | `env` | 전체 현황·알림 |
| `from`, `to` | ISO-8601 UTC 또는 상대(`now-1h`) | `from`, `to` | 상대값은 UI가 절대값으로 변환. 알림 딥링크는 절대값 |
| `live` | `1` | - | 실시간 모드. 알림 딥링크에는 없음 |
| `kind` | `HTTP\|BATCH\|MESSAGE\|ALL` | `kind` | 기본 HTTP |
| `f.<field>` | 값 또는 `!값`(제외) | 각 엔드포인트 필터 | 예: `f.service=17`, `f.status=!200`, `f.user=abc`. 자유 텍스트 필터 1KB |
| `sel` | `ms:<min>-<max>,ts:<from>-<to>` | XLog 요약·검색 | 드래그 선택 |
| `tab` | 문자열 | - | 상세 화면 탭 |
| `baseline` | `1d\|7d` | `baseline` | |
| `kiosk` | `1` | - | 상황판 모드 |

라우트별 매핑

| 라우트 | 추가 키 | 호출 API |
|---|---|---|
| `/overview` | `group` | `GET /overview`, WS `overview`·`alert` |
| `/apps/:appId/dashboard` | `baseline`, `f.instance` | `GET /dashboard`, `/instances/compare`, WS `counter` |
| `/apps/:appId/xlog` | `sel`, `color`, `f.*` | `GET /xlog`, `POST /xlog/summary`, `POST /transactions/search`, WS `xlog` |
| `/tx/:txId` | `ts`, `tab`, `compare=<txId>` | `GET /transactions/{txId}`, `/baseline` |
| `/traces/:traceId` | `ts`, `window` | `GET /traces/{traceId}` |
| `/apps/:appId/active` | `f.instance`, `f.state` | `GET /active`, WS `active` |
| `/apps/:appId/services[/:serviceId]` | `sort`, `split=version`, `compare=prev` | `GET /services/stats`, `/services/{id}` |
| `/apps/:appId/sql[/:sqlId]`, `/external[/:hostId]`, `/errors[/:groupId]` | `sort`, `new=1` | 4.8 |
| `/apps/:appId/map` | - | `GET /map` |
| `/instances/:instanceId` | `tab`, `metric[]` | `GET /instances/{id}`, `/metrics`, `/commands` |
| `/alerts/rules[/:ruleId]`, `/alerts/channels`, `/alerts/events`, `/alerts/stats` | `f.severity`, `f.state`, `f.delivery` | 4.13 |
| `/admin/*` | 리소스별 | 4.14 |
| `/search` | `q`, `type` | `GET /search` |

- URL 길이 상한 2KB, 개별 필터 값 1KB. UI는 복원 시 스키마 검증(15 문서, zod) 후 실패한 키만 기본값으로 바꾸고 안내한다.
- 알림 딥링크 생성 규약(서버가 메시지에 넣는 URL): `<external-url>/apps/{appId}/dashboard?env=&from=<발화-15m>&to=<발화+15m>&f.<target>=…&alert=<eventId>`. `alert` 키가 있으면 UI는 그 이벤트 마커를 강조하고 실시간을 끈다(05 문서 2.0 딥링크 기준 2~4).

## 7. 보안 처리

### 7.1 역할 매트릭스 (DSN-14-11, SEC-49)

| 엔드포인트 그룹 | viewer | operator | admin |
|---|---|---|---|
| 조회(전체 현황·대시보드·XLog·검색·상세·트레이스·통계·액티브·메트릭·이벤트 조회·알림 조회·수집 상태·about·licenses) | ○ | ○ | ○ |
| 바인드 파라미터 `full` 열람 | - | - | ○ + `pii.view` |
| CSV 내보내기 | ○(감사) | ○(감사) | ○(감사) |
| 명령: 스레드 덤프·단일 스레드 스택·자가 진단 | - | ○ | ○ |
| 명령: 환경 덤프·설정 리로드 | - | - | ○ |
| 명령 결과 조회 | 요청자 | 요청자 | ○ |
| 이벤트 생성(배포 마커) | - | ○ (또는 앱 토큰) | ○ |
| 알림 규칙 CRUD·미리보기·템플릿 적용·ACK·음소거 | - | ○ | ○ |
| 알림 채널 생성·URL 변경·테스트 전송 | - | - | ○ |
| 인스턴스 보관 | - | ○ | ○ |
| 유효 설정 조회 | - | ○ | ○ |
| 저장소 상태 | - | ○ | ○ |
| 앱 생성·수정·삭제, 승인 대기, 토큰, 설정 변경·되돌리기, 킬 스위치 | - | - | ○ |
| 사용자·역할·세션·감사 로그·개인정보 삭제·보존·개인정보 설정 | - | - | ○ |
| 설정 토큰 부트스트랩 | public(토큰) | | |

- 권한 매트릭스 테스트는 OpenAPI `x-argos-role`에서 생성한다(3역할 × 전 operation, 허용은 2xx/4xx 비403, 거부는 403). 역할 표기 없는 operation은 린트 실패(SEC-49).
- 객체 수준: 명령·명령 결과는 `requested_by == me OR admin`. 알림 ACK는 operator 이상 누구나. 세션 리소스는 본인 또는 admin.

### 7.2 입력 상한

| 입력 | 상한 |
|---|---|
| URL 길이 | 8KB(서버), 딥링크 2KB(UI) |
| JSON 본문 | 256KB(검색 필터·규칙), 1MB(없음 — 업로드 API 없음) |
| 문자열 필터 | 1KB |
| 배열 파라미터 | 100개(`instance[]`, `service[]`), 50개(`names[]`) |
| `limit` | 엔드포인트별(4장) |
| 시간 범위 | 원본 조회 24h, 시계열 1년, 삭제 API 30일 |
| 규칙 이름·채널 이름 | 128자, 메시지 템플릿 2KB, 사유 1KB |

초과는 400 `payload_too_large` 또는 `limit_exceeded`. 서버는 `maxHttpRequestHeaderSize` 16KB, 본문 1MB로 Tomcat 커넥터를 제한한다.

### 7.3 민감 필드 노출

| 필드 | 노출 규칙 |
|---|---|
| 바인드 파라미터 | 저장 모드(`masked`/`partial`/`full`) 그대로 반환하되 `full`은 `pii.view`가 없으면 `masked`로 강등. `full` 반환 시 감사 `pii.view`(tx_id, 사용자) |
| 환경 덤프 결과 | 에이전트 측 마스킹된 내용만 존재. admin만. 열람 감사 |
| 스레드 덤프 결과 | operator 이상, 요청자·admin. 열람 감사 |
| 토큰 | 생성·회전 응답에만 1회 |
| 채널 URL·비밀값 | 항상 마스킹(`https://hooks.slack.com/****`) |
| 유효 설정의 토큰·비밀값 | `****` |
| user_id·remote_ip | 저장 모드 그대로. 응답에 `user_id_mode`·`ip_mode` 동봉 |
| 감사 로그 `before/after` | 비밀값 마스킹된 상태로 저장되어 그대로 반환 |

### 7.4 보안 헤더

모든 API 응답: `Cache-Control: no-store`(롤업 캐시 허용 응답 제외), `X-Content-Type-Options: nosniff`, `Content-Type: application/json; charset=utf-8`. 정적 UI 응답은 SEC-45·46의 헤더·CSP(16 문서).

## 8. OpenAPI 산출 규약

| 항목 | 규칙 |
|---|---|
| 생성 | springdoc-openapi로 컨트롤러 애너테이션에서 생성. 확장 필드는 커스텀 애너테이션(`@ArgosRole`, `@ArgosAudit`, `@ArgosPii`, `@ArgosCsv`, `@ArgosLimits`)이 `OperationCustomizer`로 주입 |
| 커밋 | `./gradlew :server:generateOpenApi` → `server/openapi/argos-v1.yaml`. CI는 생성본과 커밋본 diff가 0인지 확인 |
| 클라이언트 | `pnpm -C ui gen:api` → `openapi-typescript`로 타입, 얇은 fetch 래퍼(`credentials: include`, `X-Argos-CSRF` 자동 주입, 401 시 refresh 1회 재시도, 봉투 언래핑)를 `ui/src/api/generated/`에 생성. 수정 금지 |
| 린트 | Spectral 커스텀 룰셋(2.9) + `oasdiff breaking` |
| 문서 | 문서 사이트에 Redoc 렌더(1.0). `/api/v1/openapi.yaml`은 viewer 세션으로 제공 |
| 예시 | 각 operation에 `examples` 1개 이상(아래 샘플이 원본) |

예시 1: 로그인

```http
POST /api/v1/auth/login
X-Argos-CSRF: 7f3c...
{"username": "ops", "password": "••••••••••••"}

200 OK
Set-Cookie: argos_at=...; HttpOnly; Secure; SameSite=Strict; Path=/api; Max-Age=900
Set-Cookie: argos_rt=...; HttpOnly; Secure; SameSite=Strict; Path=/api/v1/auth; Max-Age=28800
{"data": {"user": {"id": "u1", "username": "ops", "roles": ["operator"], "permissions": []}, "must_change_password": false, "session_expires_at": "2026-10-23T09:02:03Z"}, "meta": {"request_id": "01J..."}}
```

예시 2: 대시보드(롤업 + 마지막 2분 원본)

```http
GET /api/v1/apps/3/dashboard?from=2026-10-22T00:00:00Z&to=2026-10-23T00:00:00Z&kind=HTTP&baseline=7d

200 OK
{"data": {"tiles": {"tps": 412.4, "avg_ms": 83, "p95_ms": 410, "p99_ms": 1210, "error_rate": 0.012, "active": 37,
          "delta": {"basis": "7d", "tps": 0.04, "p95_ms": -0.11, "error_rate": 0.002}},
         "series": {"ts": ["2026-10-22T00:00:00Z", "..."], "tps": [..], "p95_ms": [..], "p99_ms": [..], "error_rate": [..], "active": [..],
                    "heap_used": [..], "gc_pause_ms": [..], "cpu": [..], "tomcat_busy_ratio": [..], "pool_usage_ratio": {"HikariPool-1": [..]}},
         "baseline": {"ts": [..], "p95_ms": [..]}, "instances": [..], "firing": [..],
         "collection": {"agent_drops": 0, "server_drops": 0, "server_drops_quota": 0, "ingest_lag_s": 1.2, "clock_skew_instances": 0, "excluded": 86400, "late": 3, "batch_running": 1},
         "events": [{"event_id": "e1", "ts": "2026-10-22T14:00:00Z", "type": "deploy", "title": "v1.4.2", "version": "1.4.2"}],
         "outages": []},
 "meta": {"request_id": "01J...", "from": "...", "to": "...", "source": "mixed", "truncated": false, "retention_exceeded": false}}
```

예시 3: XLog 컬럼형

```http
GET /api/v1/apps/3/xlog?from=2026-10-23T00:50:00Z&to=2026-10-23T01:00:00Z&kind=HTTP&limit=100000
Accept-Encoding: gzip

200 OK
{"data": {"tx_id": ["7312000000000001", "..."], "ts": [1761180600123, ...], "elapsed_ms": [83, ...], "status": [0, 1, 2, ...], "instance_id": [12, ...], "service_id": [17, ...], "kind": [1, ...],
          "dictionary": {"service": {"17": "GET /api/orders/{id}"}, "instance": {"12": "web-01:8080"}}},
 "meta": {"shown": 100000, "total": 143210, "sampling": {"applied": true, "rule": "error OR slow OR cityHash64(tx_id) % 2 = 0"}, "slow_threshold_ms": 1000, "outages": [], "source": "raw"}}
```

예시 4: 에러

```http
POST /api/v1/alerts/channels
{"type": "webhook", "name": "ops", "target": "http://169.254.169.254/latest/meta-data"}

422 Unprocessable Entity
{"error": {"code": "webhook_destination_blocked", "message": "Destination resolves to a blocked address range", "details": [{"field": "target", "reason": "link_local"}], "request_id": "01J..."}}
```

예시 5: 명령 요청·결과

```http
POST /api/v1/instances/12/commands
{"type": "thread_dump"}
202 Accepted
{"data": {"command_id": "a3f1...-uuid", "status": "queued", "ttl_s": 60}}

GET /api/v1/commands/a3f1...-uuid
200 OK
{"data": {"command_id": "a3f1...", "type": "thread_dump", "instance_id": 12, "requested_by": "u1", "requested_at": "...", "status": "done", "duration_ms": 420, "result_size": 184320}}
```

## 9. 테스트 포인트 (03 문서 3.2·3.5 대응)

| 테스트 | 내용 | 03 항목 |
|---|---|---|
| 계약 테스트 | 모든 operation의 응답이 스펙 스키마와 일치(생성본 기준). 봉투 필수 필드(`source`·`truncated`) | 3.2 OpenAPI |
| 권한 매트릭스 | `x-argos-role`에서 생성한 3역할 × 전 operation 테스트, 역할 표기 누락 린트 | 3.5 SEC-49 |
| 객체 수준 권한 | 타인 명령 결과 403, UUID 형식 | 3.5 |
| 소스 선택 | 범위 길이별 `source` 값, 마지막 2분 병합, 분 경계 미정렬 시 대시보드 적분 = XLog 점 수(D-03) | 3.2 |
| 상한 | `limit`·범위·문자열·배열 초과 400, `truncated` 표기, CSV 10,000행 | 3.5 입력 상한 |
| 에러 본문 | 스택·경로·SQL 미노출 스냅샷, `request_id` 존재 | 3.2 |
| 세션 | 쿠키 플래그, 리프레시 회전·재사용 감지, 로그아웃·역할 변경 후 401/403, CSRF 누락 403, Origin 불일치 403 | 3.5 SEC-40~43 |
| WebSocket | 무인증 핸드셰이크 거부, Origin 불일치, 로그아웃 후 1초 내 close 4401, 구독 20 초과 `error`, 백프레셔 `notice`, 재접속 보충 | 3.5 SEC-44, 3.3 실시간 |
| 설정 토큰 | 토큰 없이 403, 재사용 403, 관리자 존재 시 404 | 3.5 SEC-47 |
| 레이트 리밋 | 각 버킷 초과 429 + `Retry-After` | 3.5 SEC-56 |
| 민감 필드 | `pii.view` 없는 `full` 요청이 `masked_by_role`로 강등, 열람 감사 기록 | 3.5 |
| 템플릿 변수 | 금지 변수 422 | 3.5 SEC-39 |
| 웹훅 목적지 | 금지 대역 422, 테스트 전송 동일 | 3.5 SEC-51 |
| CSV | 수식 주입 접두, BOM, 감사 이벤트 | 3.5 SEC-63 |
| 감사 | 상태 변경 operation마다 `x-argos-audit` 이벤트 기록(카탈로그 스냅샷), `/admin/audit` 쓰기 operation 부재 린트 | 3.5 SEC-80·82 |
| 호환성 | `oasdiff breaking` CI, 설정 키 스냅샷 | 2 공통 DoD |
| 성능 | N-07(대시보드 1초·XLog 2초·상세 0.5초·Top N 1초), N-24(ID 검색), 30일 적재 상태 | 3.2 부하 |
| 딥링크 | 변조 URL 복원, 길이 상한, 알림 딥링크 5항 | 3.3 |

## 10. 요구 ID 대응표

| 요구 | 이 문서의 대응 |
|---|---|
| F-S04 | 4장 전체, 3장 소스 선택, 2.8 `x-argos-role` |
| F-S05 | 4.1 인증, 2.2 |
| F-S07 | 4.10 명령, 4.14 토큰·설정 변경 |
| F-S08 | 2.8 `x-argos-audit`, 4.14 `/admin/audit`(읽기 전용) |
| F-S10 | 4.15 수집 상태, 4.4 `collection` |
| F-S18 | 4.12 이벤트, 토큰 Bearer 예외 |
| F-S20 | 4.16 Actuator |
| F-S22·S23 | 4.14 `/admin/storage/health` |
| F-S26 | 4.13 ACK |
| F-S29 | 4.14 설정 변경 4단계(요청·미리보기·적용·되돌리기), WS `admin` |
| F-S30 | 4.14 승인 대기 |
| F-S32 | 4.14 삭제 API 비동기 job |
| F-U01~U21 | 4.2~4.15의 각 응답 스키마가 05 문서 2장 필수 요소를 공급 |
| D-03 | 3장 분 경계·마지막 2분·`retention_exceeded` |
| D-06·D-07·D-18 | 봉투 `sampling`·`truncated`, 상세 `profile.state`, `outages[]` |
| D-20 | `kind` 기본 HTTP |
| N-07·N-24 | 4장 제한 열, 9장 성능 테스트 |
| N-25 | 4.14 설정 변경 결과, WS `admin` |
| O-R03 | 2.1 버전 정책, 2.9 diff 린트 |
| SEC-21·50 | 4.10 명령 권한·객체 수준 |
| SEC-33 | 4.14 삭제 API |
| SEC-39 | 4.13 템플릿 변수 |
| SEC-40~48 | 2.2, 4.1 |
| SEC-49 | 7.1, 2.8 |
| SEC-55·56 | 4.16, 2.7 |
| SEC-60 | 2.10 |
| SEC-63·64 | 4.17, 6장 |

## 11. 설계 결정

| ID | 결정 | 대안 | 선택 이유 | 영향 요구 |
|---|---|---|---|---|
| DSN-14-01 | `/api/v1` 추가만 허용, breaking은 `/api/v2` 병행 + `Deprecation`/`Sunset` 헤더 | 버전 헤더 협상 | 공개 계약을 URL로 명확히(O-R03) | O-R03 |
| DSN-14-02 | 쿠키 세션 + 더블 서브밋 CSRF + Origin 검사. 배포 마커만 앱 토큰 Bearer 예외 | Authorization 헤더 전면 | XSS 내성(SEC-40), 배포 파이프라인 자동화 요구 | SEC-40~43, F-S18 |
| DSN-14-03 | 공통 파라미터 집합과 키셋 커서 페이지네이션 | 오프셋 | ClickHouse 대량 정렬 비용, 일관된 UI | F-U16 |
| DSN-14-04 | 봉투에 `source`·`truncated`·`shown/total`·`sampling`·`retention_exceeded` 필수 | 헤더 | UI가 숨기지 않고 표시(D-06·D-07) | D-03·06·07 |
| DSN-14-05 | 에러 `{code, message, details[], request_id}`, 코드 열거형 고정 | RFC 9457 | 단순성, 클라이언트 분기 용이 | 02 §7 |
| DSN-14-06 | 소스 자동 선택(≤6h raw / ≤7d 1m / >7d 1h), 마지막 2분 원본 병합, 분 경계 부분 분 원본 계산, `source` 강제 불가 | 클라이언트 선택 | D-03 일관성, 쿼리 비용 통제 | D-03, N-07 |
| DSN-14-07 | ID 단건(`tx_id`·`trace_id`)·전체 현황·전 앱 알림은 `app` 불필요. `tx_id`는 시각 복원, `trace_id`는 시간 힌트 기본 24h | 모두 `app` 필수 | DSN-02-09, 운영자 리뷰 OP-40 | F-U14, N-24 |
| DSN-14-08 | XLog는 컬럼형 JSON + gzip, 사전 동봉. 바이너리는 1.x 예약 | 행 배열 JSON, protobuf | 10만 점 전송·파싱 비용 최소, 추가 의존성 없음 | N-07, N-14 |
| DSN-14-09 | WebSocket 자체 JSON 프레임(STOMP 미사용), 토픽 6종, 100ms 묶음, 백프레셔 드롭 + `notice`, 세션 폐기 시 close 4401, 재접속은 REST 보충 | STOMP, SSE | 의존성 최소, 멀티플렉스·백프레셔 제어 | F-S03, SEC-44 |
| DSN-14-10 | OpenAPI 확장 `x-argos-role`·`x-argos-audit`·`x-argos-pii`·`x-argos-untrusted`·`x-argos-csv`·`x-argos-limits`, 린트로 강제 | 문서화만 | 권한 매트릭스·감사 테스트를 스펙에서 생성 | SEC-49, SEC-80 |
| DSN-14-11 | 3역할 매트릭스. 민감 작업(채널 URL·환경 덤프·토큰·설정 변경·킬 스위치·개인정보)은 admin, `pii.view`는 admin 중 부여 | 운영자 확대 | 06 문서 SEC-49, 미결 19 | SEC-49 |
| DSN-14-12 | CSV: 전 셀 따옴표, 위험 접두 `'`, BOM, 10,000행, 감사 | 그대로 출력 | SEC-63 | SEC-63 |
| DSN-14-13 | 수집 유래 문자열은 이스케이프 없이 반환 + `x-argos-untrusted` + 길이 상한 + 제어 문자 치환 | 서버 측 HTML 이스케이프 | 이중 이스케이프 방지, 렌더 책임 명확 | SEC-60 |
| DSN-14-14 | 레이트 리밋 버킷 5종(로그인 IP·일반 사용자·무거운 조회·명령·WS) | 단일 한도 | SEC-56·SEC-24 | SEC-56 |
| DSN-14-15 | 시간 범위 상한: 원본 조회 24h, 시계열 1년(버킷 ≤ 720), 트레이스 30일은 무거운 버킷, 삭제 30일 | 무제한 | 쿼리 비용·메모리 상한(N-18) | N-07, N-18 |
| DSN-14-16 | 바인드 파라미터 `full`은 `pii.view` 없으면 `masked_by_role` 강등(403 아님), 열람 감사 | 403 | 화면 깨짐 없이 안전 기본값 | SEC-3A |
| DSN-14-17 | 딥링크 쿼리 키 규약(`app`·`env`·`from`·`to`·`live`·`kind`·`f.<field>`·`sel`·`tab`·`alert`), 2KB 상한, 알림 딥링크는 절대 시각·실시간 off | 해시 라우트 상태 | 공유·브라우저 이력·알림 품질(05 문서 2.0) | F-U14, SEC-64 |
| DSN-14-18 | 명령은 비동기(202 + UUID), 결과는 폴링 또는 WS `admin`, TTL 60초, 결과 조회는 요청자·admin | 동기 대기 | 에이전트 응답 지연·세션 끊김 대응, SEC-21·24·50 | F-A12, SEC-21 |
| DSN-14-19 | 설정 변경은 요청(미리보기 diff·거부 키) → 적용 → 인스턴스별 결과 → 되돌리기(새 변경)의 4단계 리소스 | 즉시 PUT | 변경 통제(F-S29), 하드 캡 거부 가시화 | F-S29, SEC-23, N-25 |
| DSN-14-20 | 개인정보 삭제는 비동기 job, 기간 ≤ 30일 필수, 롤업 제외 명시 | 동기 삭제 | ClickHouse 뮤테이션 비용 | SEC-33 |

## 12. 미결·가정

| # | 항목 | 가정·처리 |
|---|---|---|
| 1 | 인스턴스 비교 표(4.4)의 인스턴스 축 통계 | 13 문서에 `instance_stat_1m`(또는 `service_stat_1m`에 `instance_id` 차원) 요청. 없으면 원본 `xlog` 집계로 6시간 이하만 지원 |
| 2 | 서비스 상세의 "그 서비스의 SQL Top N" | `sql_stat_raw`에 `service_id` 차원이 없으면 `xlog.sql_ids`(A-10) 또는 프로파일 기반(표본 표시)으로 대체. 13 문서에 `service_id` 차원 추가 비용 검토 요청 |
| 3 | XLog 바이너리 포맷 | 1.x 예약. PoC P-5에서 컬럼형 JSON+gzip의 10만 점 전송·파싱 시간이 N-07을 만족하는지 측정 |
| 4 | `GET /traces/{traceId}` 30일 조회 비용 | bloom 파라미터는 WO-03 Q6 결과에 따른다. 기본 24h 유지 |
| 5 | 상대 시간(`now-1h`) 파싱 | UI에서만 처리. 서버는 절대 시각만 받는다 |
| 6 | WebSocket 프록시 환경 | `Origin` 비교 대상 `argos.server.external-url` 설정 필수. 설치 가이드에 리버스 프록시 WS 업그레이드 설정 포함(17 문서) |
| 7 | 배포 마커 API의 앱 토큰 인증 | 에이전트 토큰 재사용은 범위 최소화를 위해 `POST /events`로 한정. 16 문서가 별도 "이벤트 전용 토큰"이 낫다고 판단하면 변경 |
| 8 | 상황판(kiosk) 긴 세션 | `/auth/me`의 `kiosk_allowed`로 UI가 자동 갱신 모드 허용 여부를 안다. 세션 수명 연장 메커니즘은 16 문서 |
| 9 | OpenAPI 생성 도구 | springdoc-openapi 가정. Spring Boot 버전 확정 후 호환 버전 확인(착수 시 확인) |
| 10 | `oasdiff`·Spectral 버전 | 착수 시 확인. 대안 `openapi-diff` |

## 13. 다른 설계 문서에 요구하는 것

| 대상 문서 | 요구 | 이유 |
|---|---|---|
| 12 서버 | 4장 전 엔드포인트 구현, 3장 소스 선택기(`SourceSelector`)와 분 경계 병합기, 봉투 직렬화기, 에러 핸들러(`request_id`), 레이트 리밋 필터(5버킷), WS 허브(토픽 6종·백프레셔·close 코드), 명령 비동기 상태 머신, 설정 변경 4단계 상태 머신, 삭제 job 러너, 감사 인터셉터(`x-argos-audit`) | 이 문서는 계약만 정한다 |
| 13 저장소 | `instance_stat_1m`(또는 인스턴스 차원), 서비스 축 SQL 통계 가능 여부, `xlog` bloom(`tx_id`·`trace_id`·`sql_ids`·`user_id`·`request_id`) 파라미터, `metric` 이름·라벨·종류 레지스트리 테이블, 자기 메트릭(`argos_*`)의 저장 위치, 수집 중단 구간 산출용 세션 이벤트 저장, `ext_stat_raw`에 `path_pattern` 포함 여부 | 4.4·4.8·4.15 응답 |
| 15 UI | 6장 쿼리 키 규약과 zod 스키마, 생성 클라이언트(`ui/src/api/generated`) 사용 강제, 연속 반복 step 접기, `measured=false`·`profile.state`·`sampling`·`truncated`·`retention_exceeded`·`outages` 표시, WS 재접속 보충 절차, `masked_by_role` 표시 | 봉투 의미를 화면에 그대로 |
| 16 보안 | 쿠키 속성·세션 레코드·리프레시 회전 구현, CSRF 필터, Origin 검사, 설정 토큰 생성·만료, `pii.view` 권한 모델, 배포 마커 토큰 인증 범위 검토, Actuator 인증 설정, 레이트 리밋 저장소(인메모리 vs PG), kiosk 세션 정책 | 2.2·7장의 구현 |
| 18 테스트 | 9장 테스트 포인트 전부. 특히 스펙 생성 기반 권한 매트릭스 테스트 생성기, 계약 테스트 하네스, WS 테스트 클라이언트, N-07·N-24 측정 스크립트 | 03 문서 3.2·3.5 |
| 11 프로토콜 | 명령 타입 열거(`thread_dump`·`thread_stack`·`self_diagnosis`·`reload_config`·`env_dump`)와 결과 포맷(스레드 덤프 JSON 구조)이 4.10 응답과 일치, `ConfigApplyResult.rejected_keys`, `InstanceUpdate` 필드가 4.3 인스턴스 메타와 일치 | 서버가 변환 없이 전달 |
| 17 배포·운영 | `argos.server.external-url`, Actuator 인증·IP 허용 목록, 리버스 프록시(443·WS 업그레이드) 가이드, CSV `tz` 기본값 설정 | 운영 설정 |
| 02 아키텍처(PM) | 변경 요청 없음. 단, 02 §4.3 PostgreSQL 목록에 `pending_registrations`, `privacy_jobs`, `mutes`, `user_permissions` 추가를 13 문서가 반영하도록 PM이 조정 | 4.14 리소스 |
