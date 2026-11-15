# ADR-0010: Phase 1 1차 슬라이스 착수 때 정한 PM 결정(C-91~)

| 항목 | 내용 |
|---|---|
| 상태 | Accepted (provisional, maintainer confirmation pending) |
| 날짜 | 2026-11-03(C-91~C-100), 2026-11-04(C-101~C-122) |
| 결정자 | PM(메인테이너 위임 결정). 메인테이너 확인 대기 |
| 관련 | ADR-0009(Gate 0 결정), docs/04 §3(Phase 1 WO 보강), WO-02 완료 보고 D-1~D-3·D-14 |

## English summary

Before each Phase 1 work order of the first vertical slice starts, the PM resolves the conflicts
between design documents that the work-order enrichment found. This ADR records them as C-91 and
up, continuing ADR-0009. Rows are added pair by pair (WO-10/20, WO-11/24, WO-12/21, WO-13/23,
WO-30/40, WO-33/34). Design-document text is corrected by the maintainer later; until then this
ADR wins where it differs.

## 맥락

- docs/04 §2는 Phase 1 WO를 착수 전에 Phase 0 수준(8개 항목)으로 보강하라고 한다. 보강 중 설계 문서끼리 어긋나거나 WO-02 PoC가 설계와 다르게 만든 부분이 나왔다.
- 사용자는 Phase 1 1차 수직 슬라이스 12개(WO-10·20 → 11·24 → 12·21 → 13·23 → 30·40 → 33·34)를 PM에게 위임했다. 결정은 요구 문서(01·06)와 테스트 기준(18)을 지키는 쪽, 그리고 보안상 보수적인 쪽을 고른다.

## 결정

| # | 주제(출처) | 결정 | 적용 |
|---|---|---|---|
| C-91 | `TxIdGenerator` 시퀀스 초과·시드(10 §9.5 대 WO-02 D-3) | 시퀀스 초과(같은 ms 2,048건 초과)는 **10 §9.5대로 다음 ms까지 기다린다**(`LockSupport.parkNanos(1000)` 반복). 한 JVM에서 2,048건/ms를 넘는 경우만 해당하며, 다음 ms를 빌리는 D-3 방식은 T-ACC-11(`tx_id >> 23` 복원 ±1ms)을 깨므로 쓰지 않는다. 시드는 D-3의 기동 절감을 살린다: premain에서는 `ThreadLocalRandom`·`nanoTime`·pid로 12비트 임시 `nodeId`를 만들고, premain 뒤 `ArgosThread`에서 `SecureRandom` 값으로 바꾸며, `ServerHello` 뒤에는 `instance_id mod 4096`으로 바꾼다. 사전 해시는 설계대로 `Xxh3`(자체 구현, `Unsafe` 없음, U-17)이며 PoC의 FNV-1a 64는 쓰지 않는다. | WO-10, T-ACC-11 |
| C-92 | `remote-config.allow` 기본값(06 SEC-23·16 §5.5 5패턴, 10 §12.2 8패턴, 10 §15 13패턴) | **요구 문서 SEC-23의 5패턴**(`sampling.*`, `instrumentation.disabled`, `instrumentation.ignore-paths`, `log.level`, `enabled`(SEC-23 표기 `argos.enabled`))을 기본값으로 한다. 나머지 키는 운영자가 에이전트 yml의 `remote-config.allow`에 넣어야 원격 변경된다. `docs/agent-config.md`에 이 기본값과 늘리는 방법을 적는다. | WO-10, WO-17, 10 §12.2·§15 정정 |
| C-93 | WO-02 설계 차이 D-1·D-2(10 §2·§3) | 확정한다. `AgentClassLoader`는 `io.argos.agent.bootstrap`(package-private)에 둔다(코어를 싣는 로더라서 코어 패키지에 둘 수 없다). Tracer API 차이(`long` `targetId`·`dbId`, `TraceContext.markEnded()`, `Tracer.ignoreReason`, `Tracer.dict(SQL)` 코어 정규화, `CaptureConfig.asyncStep`, `AgentControl`)는 WO-10이 확정하는 bootstrap API에 넣고 javadoc에 근거를 적는다. D-14(열린 step 스택)는 10 §9.1대로 스코프별 스택으로 고친다. | WO-10, 10 §2·§3 정정 |
| C-94 | 토큰 마스킹 표기(06 SEC-06·10 §14·12 로그 `argos_****` 대 16 §2.6 `argos_********`) | **`argos_****`** 로 통일한다(요구 문서 SEC-06). 진단 번들 치환 정규식(`argos_[A-Za-z0-9_-]{43}`)은 그대로 두고 치환 결과만 이 표기로 한다. | WO-10, WO-11, WO-28·43, 16 §2.6 정정 |
| C-95 | 자가 진단 하네스 경로(18 §19, 10 §19) | 테스트 전용 키 `argos.test.selfdiag-file`을 둔다. 개발 빌드에서만 자가 진단 스냅샷을 그 파일에 쓰고 릴리스 빌드는 무시한다(T-BLD-010, C-89). WO-17이 `SELF_DIAG` 명령을 만든 뒤에는 하네스가 명령 경로도 쓴다. | WO-10, WO-17, test/README.md |
| C-96 | 서버 설정 키 체계(12 §10 대 13 §8·17 §6.1·16·14) | **12 §10 트리를 기준**으로 한다(docs/04 WO-20 "`ArgosProperties` 12 §10 설정 레퍼런스 전부"). 12에 없는 키(PostgreSQL 소유자 계정, ClickHouse migrator 계정, `table-prefix` 등)는 같은 트리에 더한다. 17 §3.8의 짧은 이름은 환경변수 매핑으로 지원한다. 0.x에는 호환 약속이 없고 키 스냅샷(T-API-009)으로 고정한다. | WO-20, 13 §8·16·17 §6.1·14 정정 |
| C-97 | PostgreSQL 계정(C-11 대 13 DSN-13-13·16 §10.8) | C-11이 우선한다: `argos_app`(서버 런타임), `argos_audit`(감사 로그 파티션 보존 작업, 12 §8.1), `argos_backup`(읽기 전용 덤프), 마이그레이션은 소유자 계정. WO-20은 기준 SQL `deploy/postgres/roles.sql`을 두고, 설치기의 역할 생성(17 `config/postgres/init/`)은 WO-40이 그 파일을 실행한다. | WO-20, WO-40, 13·16 정정 |
| C-98 | PostgreSQL V001 범위(13 §12 대 02 §4.3·12 §16·14·16의 추가 테이블·열) | V001은 **13 §12 + C-11·C-16**이다. 13 §12에 없는 테이블(`alert_states`, `mutes`, `user_permissions`, `roles`, `reconcile_results`, `service_apdex_overrides`, `webhook_signing_keys`)과 16의 보안 열은 그것을 쓰는 WO가 V002 이후 마이그레이션으로 더한다. 한 번 만든 V 파일은 다른 WO가 고치지 않는다. | WO-20, WO-24·25·26·27·28 |
| C-99 | T-ST-040 담당(18 부록 A, 04에 담당 없음) | TTL 동작·보존 변경 리허설은 보존 설정 API를 만드는 **WO-28** 몫이다. WO-20 러너는 생성 시 `{retention.*}` 렌더링만 하고 기존 테이블에 `MODIFY TTL`을 적용하지 않는다. | WO-20, WO-28 |
| C-100 | nightly 테스트의 게이트(T-SV-002, WO-50 전) | nightly 테스트는 `-Pserver.nightly=true` 같은 명시 플래그로만 돌게 만들고, 이 PC에서는 참고 실행 1회만 한다. nightly 잡 연결은 WO-50 몫이다. | WO-20, WO-50 |
| C-101 | proto 추가형 필드(11 §4 밖: 16 §3.4·§6.3, 13 §18·§12.3, 10 §6.3) | 11 §12가 허용하는 **추가형 optional 필드**로 넣는다: `AgentHello.plaintext`(16 §3.4), `ServerHello.user_id_salt`(16 §6.3, `capture.user-id.mode: hashed`), `SqlStat`·`ExtStat`의 `buckets[15]`와 `SqlStat.sum_exec_us`·`sum_fetch_us`(13 §18, V001 `sql_stat_raw` 원천), `DictionaryKind.POOL = 12`(10 §6.3 DBC 풀 이름, 13 §12.3 PG enum 'pool'; C-16 목록 확장). 16 §16의 `ServerHello.default_ip_mode`는 넣지 않는다(서버가 수집 범위를 넓히는 경로가 되어 SEC-23·DSN-16-06과 어긋남). WO-11 종료 뒤 proto는 추가형 변경만 PM 승인으로 한다. | WO-11, WO-12·13·21, C-16·11 §4 정정 |
| C-102 | UNAUTHENTICATED 재접속(11 §10 대 16 §2.4) | **16 §2.4**: 5분 고정 백오프, WARN은 시간당 1회. 폐기 토큰을 가진 에이전트가 서버와 로그에 주는 부하를 줄인다. | WO-11, 11 §10 정정 |
| C-103 | `CommandType` 범위(11 §4.1 5종 대 16 §1.4 8종) | proto는 **11 §4.1의 5종**(THREAD_DUMP·THREAD_STACK·SELF_DIAGNOSTICS·CONFIG_RELOAD·ENV_DUMP + reserved)이다. 설정 변경·킬 스위치·custom 메서드 retransform은 `ConfigPush`(11 §4, `KILL_SWITCH_ON/OFF` 포함)로 보내고 에이전트의 `remote-config.allow` 하드 캡을 그대로 거친다. 폐쇄 목록·임의 코드 실행 불가 보증(SEC-21·24·91)은 같다. | WO-11, WO-17·26, 16 §1.4 정정 |
| C-104 | 생성 코드 공유(11 §4 "각각 생성" 대 19 §2 모듈 그래프) | `:proto` 한 곳에서 protobuf-java(full)·gRPC 스텁을 만들고(C-72), 서버는 `project(":proto")`, 에이전트 jar는 `io.argos.proto`를 `io.argos.agent.shaded.io.argos.proto`로 relocate한다. 11 §4의 "각각 생성"은 javalite 전제 문구로 A-14·C-72로 근거가 없어졌다. | WO-11, WO-21, 11 §4 정정 |
| C-105 | 배치 재시도·사전 캐시 크기(10 §10.2·§10.4 대 11 §6·§7·§16) | 재시도는 **11 §6·§16**(같은 seq로 최대 3회, 200ms·1s·3s)을 따른다. 사전 캐시 크기는 **10 §10.4**(서비스 10,000, SQL 10,000, 클래스·메서드 20,000, 호스트 1,000, 스레드 1,000, 프레임 50,000)를 따른다. | WO-11, 10 §10.2·11 §7 정정 |
| C-106 | 인증 계열 12·14·16 충돌 10건(WO-24 보강) | (1) 설정 엔드포인트는 14 §4.1 경로(`/setup/status`·`/setup/admin`), 토큰 없음·틀림·만료·재사용은 모두 403(T-SEC-47) (2) CSRF 오류 코드 `csrf_invalid`(14) (3) 리프레시 쿠키는 16 §7.1(Path `/api/v1/auth/refresh`, Max-Age 86400) (4) 세션 캐시는 DSN-12-10의 5초 TTL과 16 §7.3 즉시 무효화 이벤트를 함께 쓴다 (5) kiosk는 16 §15 #15(idle 24h·absolute 30일, 설정 가능, 기본 비활성) (6) `pii.view`는 관리자별 부여(`user_permissions`, 기본 미부여; 14·02) (7) 감사 이벤트 이름은 16 §11.1(`LOGIN_FAILED`, `KIOSK_SESSION_ISSUED` 등) (8) 레이트 리밋 메트릭 `argos_ratelimit_rejections_total{scope}`(12 §9.4) (9) API `Cache-Control`은 항상 `no-store`(SEC-45) (10) 무인 설치 IP 모드 변수 `ARGOS_IP_MODE`(17 §3.8). | WO-24, 12·14·16 정정 |
| C-107 | `argos_at` 쿠키 Path와 WebSocket 경로(14 §2.2·16 §7.1 대 12 §3.7·14 §5.1) | `argos_at`의 Path를 **`/`** 로 한다. WebSocket 경로 `/ws`는 바꾸지 않는다. HttpOnly·Secure·SameSite=Strict는 그대로이고 Path는 보안 경계가 아니다. 리프레시 쿠키는 C-106 (3)대로 좁은 Path를 유지한다. | WO-24, WO-22·30, 14 §2.2·16 §7.1 정정 |
| C-108 | Argon2id 인코더(16 §7.9) | 0.x에서는 **bcrypt cost 12만** 구현한다(SEC-48 충족). Argon2id 선택 인코더는 후속 WO로 미룬다(암호 라이브러리 추가 필요). | WO-24 |
| C-109 | `/admin/users*` API 담당(14 §4.14, 04에 없음) | **WO-24**가 맡는다(무효화 훅 시험에 필요). 화면은 WO-39. | WO-24, WO-39 |
| C-110 | 권한 매트릭스 생성의 스펙 원천(ADR-0003 C-28 대 T-SEC-49·T-API-010) | WO-24는 springdoc 런타임 스펙으로 매트릭스 생성기를 만들고 현재 엔드포인트에 실행한다. `docs/api/openapi.yaml` 생성·커밋·린트·oasdiff는 WO-23(C-28)이 맡고, 그 뒤 생성기는 커밋된 스펙도 읽는다. | WO-24, WO-23 |
| C-111 | PostgreSQL V001 동결 전 정정(13 §12 대 12 §5.1·02 §4.3·§5, WO-20 보고 PM-1~3) | (1) `alert_events` PK는 `(id, ts)`, `notification_deliveries.alert_event_id`는 FK 없이 논리 참조로 둔다(PG 16 파티션 테이블은 PK에 파티션 키가 있어야 하고, 13 §12 원문은 실패한다). (2) `notification_deliveries`는 **일반 테이블**로 두고 365일 보존은 일 단위 DELETE 정리(배치)로 한다. 건수가 적고, 파티션으로 바꾸면 PK·NOT NULL 조건만 늘어난다. 정리 잡은 알림 발송을 만드는 WO-25가 맡는다. (3) 표 이름은 **`alert_rule_history`**(13 §12·V001)다. | WO-20(현행 유지), WO-25, 13 §12 보존 표·12 §5.1·02 §4.3·§5 정정 |
| C-112 | 보존 키 stat·metric 대 CH V001 `{retention.raw-days}` 하나(12 §10·17 §3.8, WO-20 PM-4) | stat ≠ metric이면 **기동 거부(fail-closed)를 유지**하고, 오류 메시지는 두 키 이름과 같은 값을 넣으라는 안내를 담는다. 키 분리(새 CH 스키마 버전의 `{retention.stat-days}`·`{retention.metric-days}`)는 보존 변경을 맡는 WO-28이 T-ST-040과 함께 한다. 그때까지 WO-40 설치기는 두 값을 같게 쓴다. max(stat, metric)는 설정보다 오래 보관하게 되어 쓰지 않는다. | WO-28, WO-40 |
| C-113 | `ARGOS_HTTP_BIND`·`ARGOS_GRPC_BIND` 의미(17 §3.8 대 WO-20 구현) | **17 §3.8대로 Compose의 호스트 쪽 게시 주소**(`ports:` 보간)다. 서버는 두 변수를 읽지 않는다. 컨테이너 안 리슨 주소는 정식 키(`server.address`, `argos.collector.bind`, 기본 모든 인터페이스)다. WO-40이 서버의 두 별칭 매핑을 지우고 키 스냅샷을 고친다. | WO-40, 16 §10.7 정정 |
| C-114 | 관리 포트 컨테이너 노출(C-7 대 17 §3.3·§3.8 `ARGOS_MGMT_EXPOSE`·§11.1) | C-7의 뜻은 "기본으로 호스트 밖에서 닿지 않는다"이다. 컨테이너 설치: 서버는 컨테이너 안 `0.0.0.0:8081`에서 듣고(`MANAGEMENT_SERVER_ADDRESS`를 Compose가 준다), Compose는 기본 `127.0.0.1:8081:8081`로 게시하며 `ARGOS_MGMT_EXPOSE`일 때만 넓힌다. 베어러 토큰은 계속 필수이고, 설치기는 `allow-cidrs`에 루프백과 Compose 네트워크 대역을 넣는다. 비컨테이너 설치는 127.0.0.1 그대로다. | WO-40, 17 §3.3·§11.1 정정 |
| C-115 | WO-20 구현이 고른 설계 불일치 확정(WO-20 PM-5·10) | (a) 알림 표시 시간대 `argos.server.timezone` 기본값은 **UTC**(17 §3.8 `ARGOS_TZ_DISPLAY`; 영어 기본 오픈소스 제품의 중립 기본값, 저장은 항상 UTC). WO-20 PM 후속 조치로 반영함 (b) 관리 포트 스크랩은 베어러 필수(C-7·16 §7.12), 17 §11.1의 로컬 무인증은 쓰지 않는다 (c) `argos.metrics.auth`는 bearer만, basic은 요구가 생기면 추가 (d) 관리 포트 8081(C-7), 12 §10의 9101 아님 (e) Flyway 위치 `db/pg`(13 §13.1), 12 §9.3 `db/migration` 아님 (f) Flyway 실행 계정은 PG 소유자(C-11·C-97) (g) CH 러너의 V/M 순서 정적 규칙은 보수적으로 둔다(실제 마이그레이션이 막히면 그때 완화). | WO-20, 12 §10·§9.3·17 §11.1·13 §13.1 정정 |
| C-116 | 감사 파티션 DROP 하한(WO-20 storage-migrations-2) | WO-28이 V002 이후에서 `audit_logs_maintain_partitions`를 교체해 **90일 안의 파티션 DROP을 DB에서 거부**하고, `argos.retention.audit` 검증 하한을 90일로 올린다. 최종 기본 보존 기간은 01 미결 #20(사용자 결정)을 따른다. | WO-28 |
| C-117 | 서버 사용자 세션·감사 IP의 분류(WO-20 PM-7, 02 §5.2) | `user_sessions.ip`와 `audit_logs.ip`는 수집 대상 앱의 데이터가 아니라 Argos 사용자의 **보안 기록**이다. `argos.privacy.default-ip-mode`는 적용하지 않고 전체 주소를 저장한다. 열람은 관리자(자기 세션은 본인)만 한다. 세션 행은 만료·폐기 7일 뒤 지우고, 감사 행은 감사 보존을 따른다. `user_agent`는 256자에서 자르고 HTML로 렌더하지 않는다. | WO-24, WO-28, 02 §5.2 |
| C-118 | 원격 설정 허용 기본값의 glob과 설정 출처 우선순위(16 §5.5 대 C-92, 10 §15·02 §7, WO-10 B-1·B-11) | (1) C-92 기본값의 SEC-23 `sampling.*`는 **`sampling.**`** 로 적는다. 16 §5.5 glob(`*` 한 세그먼트, `**` 나머지 전부)에서 `sampling.*`는 어떤 `sampling.profile.*` 키에도 맞지 않는다. PM 후속 조치로 반영함(`ConfigKeys`, `docs/agent-config.md`, 테스트). (2) 로컬 출처 우선순위는 **10 §15·02 §7**(env > 시스템 속성 > 파일)이다. (3) 원격 푸시는 `remote-config.allow`에 든 키에 한해 로컬 값을 이긴다. allow 목록은 운영자의 하드 캡이고, 키를 고정하려면 allow에서 뺀다. 16 §5.5 7단계 문구는 정정한다. 전체 동작은 WO-17이 맞춘다. | WO-10(반영), WO-17, 16 §5.5 정정 |
| C-119 | 에이전트 설정 키 이름과 형식(16 §6.1·§6.6·17 §6.2·17 데모 compose 대 10 §15, WO-10 B-2~B-5·B-14) | **10 §15가 에이전트 설정 키의 기준**이다(`docs/agent-config.md`). (1) 16 §6.1의 `capture.exception-message.max-length`·`capture.sql-parameters.columns-whitelist`는 10 §15 이름(`capture.error-message-max-length`·`capture.sql-parameters.whitelist-columns`)으로 정정한다. `capture.user-id.max-length`는 키를 두지 않고 128로 고정한다. (2) `buffer.profile.max-bytes`(16MB)는 키 없이 상수로 둔다. (3) 사용자 마스킹 패턴(16 §6.6)은 정규식 목록이고 치환은 `<masked>`로 고정한다. (4) 자리표시자는 `server.token: ${ARGOS_TOKEN}`만 지원한다. 17 §6.2 예제의 `${APP_VERSION:}`는 환경변수 규칙의 `ARGOS_APP_VERSION`으로 정정한다. (5) 토큰 파일 환경변수는 10 §15 규칙대로 `ARGOS_SERVER_TOKEN_FILE`이다. 17 데모 compose의 `ARGOS_TOKEN_FILE`은 WO-40이 정정하고 별칭은 두지 않는다. | WO-40, 16 §6.1·§6.6·17 §6.2 정정 |
| C-120 | 에이전트 자원 예산(N-05 예외, 10 §14 '기타 8MB', WO-10 B-6·B-8·B-9) | (1) N-05 예외(C-91)의 크기를 받아들인다. 한 JVM이 2,048건/ms를 넘으면 `parkNanos`로 다음 ms를 기다리며, Windows 기본 타이머에서는 대기 1회가 약 15.6ms라 JVM당 약 13만 tx/s로 제한된다. Linux 측정은 WO-19가 한다. (2) 10 §14 '기타' 배분은 측정 추정(약 12MB)에 맞춰 **12MB**로 고친다. N-02 64MB 전체 추정은 52.1MB로 안이다. (3) 바이트 상한이 없는 `LogPipeline`(4,096건 × 8,192자, 최악 약 64MB)에는 **대기 중 텍스트 합계 1MiB 바이트 상한**을 두고 넘으면 버린 수를 센다. `agent-core`를 맡는 WO-11이 넣는다. `ContextStore`는 개수 상한을 유지하고 항목당 바이트는 WO-19가 잰다. (4) `ServiceNames` 바이트 상한은 `service-dictionary-max`에 비례한다. 운영자가 올린 상한은 의도한 메모리 증가다(`buffer.max-bytes`와 같은 원칙). javadoc·`docs/agent-config.md`에 반영함. | WO-10(반영), WO-11, WO-19, 10 §14 정정 |
| C-121 | `start_ts`와 weakmap 지표(WO-10 A-3·B-10·B-15) | (1) `start_ts`는 **`tx_id`의 시각 접두**(`TxIdGenerator.epochMillisOf(txId)`, 시계 1회 읽기)다. 시계를 두 번 읽으면 선점·대기로 2ms가 어긋났다. begin 앞뒤 벽시계 [before−1, after+1] 검사를 T-ACC-11 begin 경로 테스트에 넣었다(PM 후속 조치, 320만 건 위반 0). (2) 18 §5.9·§19의 `weakmap_size`는 TraceContext·Scope 값 저장소 항목 수(GC 뒤 0)로, 장수 키 캐시(Connection·Statement·Thread 저장소)는 `weakmap_entries`(두 묶음 사이 비증가)로 나눈다. | WO-10(반영), 18 §5.9·§19 정정 |
| C-122 | 테스트 ID·메트릭 이름 등록(WO-10 B-7·B-12·B-13·B-16, WO-20 PM-6) | 18 부록 A에 다음 ID를 둔다. 문자 접미는 전체 ID의 부분 검증이다. T-SEC-23a(원격 설정 게이트 평가 단위, 전체 T-SEC-23은 WO-17), T-SEC-06a(에이전트 설정 쪽 토큰 마스킹), T-SEC-06b(서버 로그 마스킹, `RequestLogMaskingIT`), T-NFR-30a(premain 클래스 예산), T-AG-001a~d 등 WO-10의 T-AG 세부 ID, T-BLD-016(`checkFlywayNames` 네거티브; T-BLD-015는 C-90 예약), T-SV-001a(마이그레이션 완료 전 readiness DOWN), T-SV-001b(ClickHouse 중단 시 readiness DOWN). 10 §11 '에이전트 자기'와 18 §19에 메트릭 6개를 더한다: `argos_agent_scope_chain_cut_total`(한 스레드 미종료 activate 64개에서 체인 절단), `argos_agent_sql_outside_tx_total`, `argos_agent_transactions_leaked_total`, `argos_agent_transactions_unanchored_total`, `argos_agent_service_dictionary_overflow_total`, `argos_agent_profile_dropped_total`. 16 §6.6 card 규칙에 'Luhn 실패 시 같은 시작점의 짧은 후보(19→13자리) 재검사'를 보충한다. 10 U-15(JDK 25 `BytecodeVerificationLocal`은 진단 플래그라 `UnlockDiagnosticVMOptions`가 필요, C-27)와 U-17(`Xxh3` 자체 구현, Unsafe 없음)을 닫는다. | WO-10·WO-20(태그 반영), 10 §11·U-15·U-17, 16 §6.6, 18 부록 A·§19 정정 |

## 검토한 대안

| 대안 | 채택하지 않은 이유 |
|---|---|
| C-91: D-3 유지(다음 ms 빌림) | 앱 스레드 대기는 없지만 버스트에서 시각 접두가 앞으로 밀려 T-ACC-11을 바꿔야 한다(테스트 약화) |
| C-91: premain에서 `SecureRandom` | Java 8에서 premain 0.7~0.9초가 늘어 C-79(+2초) 위험을 키운다 |
| C-92: 10 §15의 13패턴 | 요구 문서보다 넓다. 원격 변경 범위는 보수적으로 시작하고 운영자가 늘린다(DSN-16-06) |
| C-94: `argos_********` | 요구 문서(SEC-06)와 에이전트·서버 설계 다수가 `argos_****`이다 |
| C-107: WebSocket 경로를 `/api/v1/ws`로 | 12·14·15가 이미 `/ws`를 쓰고, Path는 보안 경계가 아니다(HttpOnly·SameSite=Strict가 막는다) |
| C-112: `{retention.raw-days}` = max(stat, metric) | 운영자가 정한 기간보다 오래 보관하게 된다. 0.x에는 기존 설치가 없어 거부해도 잃을 것이 없다 |
| C-113: 두 이름을 컨테이너 안 리슨 주소로 유지 | `.env`가 서버 컨테이너에 넘어가면 127.0.0.1 바인드로 게시 포트가 닿지 않는다 |
| C-118: 끝의 `.*`를 '이하 전부'로 재정의 | 16 §5.5 glob 의미가 키마다 달라진다. 기본값 한 줄을 고치는 쪽이 좁다 |
| C-121: `start_ts`를 별도 시계 읽기로 | 선점·대기로 `tx_id >> 23`과 2ms 어긋나 T-ACC-11 복원 ±1ms를 깬다 |

## 결과

- WO-10: C-91~C-95 반영, 완료 뒤 C-118~C-122(PM 후속 조치로 C-118 기본값·C-121 테스트·C-122 태그 반영). WO-20: C-96~C-100 반영, 완료 뒤 C-111~C-117(PM 후속 조치로 C-115 시간대·C-122 태그 반영).
- WO-11: C-101~C-105, C-120 (3). WO-24: C-106~C-110, C-117. WO-25: C-111 (2). WO-28: C-112·C-116·C-117. WO-40: C-113·C-114·C-119 (5). WO-17: C-118 (3). WO-19: C-120 (1)·(3).
- 메인테이너 설계 문서 정정(한 번에): 10 §2·§3(C-93), §12.2·§15(C-92), 16 §2.6(C-94), 18 §19(C-95)·부록 A(C-99 담당), 13 §8·§12·DSN-13-13, 16 §10.8, 17 §6.1, 14 설정 키 표기(C-96~C-98).
- 2차 정정(쌍 2 착수 때): 11 §4·§4.1·§6·§7·§10·§12(C-101~C-105), 16 §1.4·§2.4·§7·§16(C-101~C-108), 12·14 인증 계열(C-106·C-107·C-109·C-110), 13 §12 보존 표·12 §5.1·02 §4.3·§5(C-111), 12 §10·§9.3·17 §3.3·§3.8·§11.1·13 §13.1·16 §10.7(C-113~C-115), 16 §5.5·§6.1·§6.6·17 §6.2(C-118·C-119), 10 §14(C-120), 18 §5.9·§19·부록 A, 10 §11·U-15·U-17(C-121·C-122).

## 확인 사항

- [ ] 메인테이너가 이 ADR의 결정을 확인했다.
- [ ] 위 "결과"의 설계 문서 정정을 반영했다.
