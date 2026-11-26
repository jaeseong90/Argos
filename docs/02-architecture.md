# Argos 시스템 아키텍처

| 항목 | 내용 |
|---|---|
| 문서 버전 | 0.3 |
| 작성일 | 2026-10-23 |
| 상태 | 설계 기준선. 상세 설계 10~19 문서 v0.1 완료. 12장 미결(A-)은 Phase 0 PoC로 확정, 13장은 설계 문서 간 정합 결정 |
| 반영 리뷰 | JVM 에이전트, 데이터·저장소, 보안·개인정보, 운영자·UX, 오픈소스(2026-10-22) |
| 관련 문서 | [01 제품 기획](01-product-plan.md), [03 완성 기준](03-definition-of-done.md), [05 벤치마크](05-benchmark.md), [06 보안 요구](06-security-requirements.md), 설계 10~19 |

## 0. 이 문서의 역할

이 문서는 **구성 요소, 경계, 데이터 흐름, 전역 설계 결정**을 정한다. 각 구성 요소의 상세 설계(클래스·인터페이스·스키마·API·화면)는 10번대 설계 문서가 맡는다. 문서 간 충돌이 생기면 이 문서 → 해당 설계 문서 순으로 우선한다. 이 문서를 바꾸는 결정은 ADR(`docs/adr/`)로 남긴다.

| 번호 | 설계 문서 | 범위 |
|---|---|---|
| 10 | 에이전트 설계 | 모듈·클래스로더·계측 프레임워크·컨텍스트·버퍼·전송·설정·명령·자기 보호 |
| 11 | 프로토콜 설계 | proto 정의, 세션·배치·사전·명령 메시지, 멱등성, 버전 협상 |
| 12 | 서버 설계 | 수집 파이프라인(WAL·중복 제거), 세션 레지스트리, 실시간 버스, 알림 엔진, 명령 라우팅, 메타데이터 |
| 13 | 저장소 설계 | ClickHouse DDL·롤업·TTL·코덱, PostgreSQL 스키마, 마이그레이션 러너, 용량 |
| 14 | API 설계 | REST(OpenAPI)·WebSocket 프레임, 소스 선택 규칙, 권한 표기, 에러 규약 |
| 15 | UI 설계 | 정보 구조, 화면 사양, 컴포넌트, URL 상태, 실시간, XLog 캔버스, i18n |
| 16 | 보안 설계 | 위협 모델, 토큰·세션·권한 구현, 마스킹·분류 구현, 공급망 |
| 17 | 배포·운영 설계 | Compose·tar·에어갭 패키지, 설정 관리, 업그레이드, 백업, 자기 모니터링, 런북 |
| 18 | 테스트 설계 | 테스트 계층, 매트릭스·벤치·장시간·카오스·정확성·보안·E2E 하네스 |
| 19 | 저장소·빌드·릴리스 설계 | 모노레포, Gradle·pnpm, CI 파이프라인·러너 격리, 릴리스·서명·SBOM, 기여 흐름 |

## 1. 전체 구성

```
┌──────────────── 계측 대상 애플리케이션 서버 ─────────────────┐
│  JVM (Tomcat / Spring Boot, 플랫폼 또는 가상 스레드)          │
│   └─ argos-agent.jar  (-javaagent, 단일 jar)                  │
│        ├─ 계측(ByteBuddy Advice)  ├─ 전수 통계 집계           │
│        ├─ bounded 큐·배치         ├─ gRPC 클라이언트(TLS)      │
└───────────────────────────┬──────────────────────────────────┘
                            │ gRPC :9100  배치 unary(트랜잭션·프로파일·메트릭·통계·액티브)
                            │           + 세션 양방향 스트림(Hello·heartbeat·사전·설정·명령)
┌───────────────────────────▼──────────────────────────────────┐
│  argos-server (Spring Boot, 단일 JVM, 역할 프로파일 all)       │
│   collector ─ 상한 검사 ─ WAL 기록 ─ ack ─┬─ 실시간 버스 ─ WebSocket ─┐  │
│                                          └─ 배치 라이터(dedup 토큰) │  │
│   web ─ REST /api/v1 (OpenAPI) ─ 조회(원본/1m/1h 자동 선택) ───┼──┼──┐│
│   alert ─ 규칙 평가(10초) ─ 상태 머신 ─ 채널(목적지 검증·서명) │  │  ││
│   admin ─ 토큰·세션·RBAC·감사 ─ 명령 라우팅 ─ 설정 변경 통제    │  │  ││
│   ops ─ 야간 정합 검사·롤업 재구축 ─ 자기 메트릭 /actuator/prometheus │
└──────────────────────────────────────────┼──┼──┼──────────────┘
        ┌─────────────────────────────────┘  │  │
┌───────▼─────────┐   ┌──────────────────────▼┐ │   ┌─────────────┐
│   ClickHouse    │   │      PostgreSQL       │ │   │  React UI   │
│ xlog, profile,  │   │ 사용자·세션, 앱·인스턴스│ │   │ :8080 (쿠키 │
│ metric, stat,   │   │ 토큰(해시), 사전(원문) │ └──▶│  세션, WS)  │
│ 1m/1h 롤업      │   │ 규칙·채널(암호화)·이력│     │             │
│ (argos_writer/  │   │ 명령·결과(암호화)     │     └─────────────┘
│  argos_reader)  │   │ 이벤트, 감사, 마이그레이션 이력│
└─────────────────┘   └───────────────────────┘
```

런타임 구성 요소는 넷이다. 에이전트, 서버(1 JVM), ClickHouse, PostgreSQL. 1.0에서는 이 넷 외의 런타임 의존성(Redis, Kafka 등)을 두지 않는다. 외부로 노출되는 포트는 UI/API(8080)와 gRPC(9100)뿐이다.

## 2. 설계 원칙

1. **에이전트는 절대 앱을 해치지 않는다.** 앱 스레드를 블로킹하지 않고, 예외·반환값을 바꾸지 않고, 객체 동일성을 깨지 않고(Runnable 래핑 금지), 상한 없는 메모리를 쓰지 않는다. 서버가 없어도 앱은 그대로 돈다. 에이전트 자신의 스레드·클래스는 계측 대상이 아니다.
2. **XLog는 100%, 상세는 조건부, 통계는 전수.** 트랜잭션 점은 전수 기록하고, 프로파일(step)은 에러·느림·샘플만 전송하며, SQL·외부 호출 통계는 에이전트가 전 트랜잭션에서 집계해 따로 보낸다(D-12).
3. **숫자는 서로 일치한다.** 수집 경로는 중복 0·누락 0(WAL + 중복 제거 토큰, D-13), 롤업은 원본의 결정적 함수(D-14), 1요청 = 1트랜잭션(D-04), 1 SQL 실행 = 1 step(D-24). 불일치는 야간 정합 검사가 잡아 재구축한다.
4. **프라이버시 기본값.** 개인정보 가능 필드는 설정 없이는 수집되지 않거나 마스킹된다(5장 분류표). 수집 범위를 넓히는 설정은 원격으로 바꿀 수 없다(SEC-23).
5. **단일 노드로 시작, 수평 확장 가능.** 1.0은 all-in-one. 역할 분리·ClickHouse 복제·샤딩은 테이블·DB 이름을 설정값으로 두어 1.x에서 바꿀 수 있게 한다.
6. **표준 호환.** W3C Trace Context(기존 `traceparent` 보존), OpenMetrics 명명, OpenAPI, Prometheus 노출. 내부 모델은 OTLP를 받아들일 수 있게 둔다(2.0).
7. **AI가 구현한다는 전제.** 모듈 경계와 인터페이스를 명시해 작업 지시서 하나가 모듈 하나에 대응하도록 한다. 모든 모듈은 독립 테스트가 가능해야 한다. 금지 규칙(Advice 작성 규칙, HTML 조립 금지, Mock DB 금지)은 린트·ArchUnit으로 기계 검사한다.

## 3. 전역 설계 결정

| ID | 결정 | 대안 | 선택 이유 | 영향 요구 |
|---|---|---|---|---|
| DSN-02-01 | 에이전트는 **ByteBuddy Advice 인라인**만 사용. `MethodDelegation`·`skipOn` 금지. `readOnly=false`(인자·반환 교체)는 **허용 목록 파일에 등록된 관찰 데이터 부가 목적**(전파 헤더 추가, MDC 맵 부가)에만 허용하고 ArchUnit이 검사한다. 앱의 의미·예외·반환값을 바꾸는 용도는 금지 | 전면 금지 | 클래스로더 경계 문제 최소화, 앱 동작 변경 불가능 보장. jdk11 HttpClient·Logback MDC는 인자 부가 없이는 계측 불가(DSN-10-04) | F-A15, N-01 |
| DSN-02-02 | **단일 jar bootstrap**: jar 루트에 `agent-bootstrap` 클래스, 나머지는 `inst/*.classdata`로 숨기고 격리 `AgentClassLoader`가 로드. 임시 파일 0 | 중첩 jar 추출 | 읽기 전용 FS·`noexec` tmp에서 기동, 보안팀 거부감 제거 | F-A31 |
| DSN-02-03 | JDK 클래스 계측 시 `Instrumentation.redefineModule`로 bootstrap unnamed 모듈 read edge 추가. `Add-Opens` 매니페스트 미사용 | 매니페스트 속성 | 매니페스트 속성은 에이전트 jar에서 무효. JDK 9+ `IllegalAccessError` 방지 | F-A31 |
| DSN-02-04 | 요청 앵커는 `ServletRequest` 속성, ThreadLocal은 캐시. `DispatcherType.REQUEST`에서만 트랜잭션 시작 | ThreadLocal만 | Spring Boot `/error` 디스패치 이중 트랜잭션 방지 | F-A28, D-04, D-21 |
| DSN-02-05 | Executor는 **허용목록**만 계측, 컨텍스트는 Runnable에 필드 주입(약참조 맵 폴백), 래핑 금지 | 인터페이스 전체 매칭 + 래핑 | `remove()`·`ScheduledFuture`·Netty 타입 검사 보존, 변환 대상 최소화 | F-A07, 원칙 1 |
| DSN-02-06 | JDBC는 `java.sql` 인터페이스 서브타입 매칭 + 클래스 계층별 CallDepth로 최외곽만 기록. `getConnection` 대기·`commit/rollback`도 step | 드라이버별 계측 | 드라이버 무관, 래퍼 중첩 제거 | F-A03, D-24 |
| DSN-02-07 | 풀·Tomcat 메트릭은 생성자·`start()` Advice로 인스턴스를 약참조 레지스트리에 등록하고 getter 호출. JMX는 보조 | JMX 우선 | Spring Boot는 HikariCP·Tomcat MBean이 기본 off | F-A10 |
| DSN-02-08 | SQL·외부 호출 통계는 에이전트가 10초 창으로 전수 집계해 `SendStats`로 전송. 서버는 `sql_stat_raw`·`ext_stat_raw`에서 롤업 | 프로파일에서 집계 | 프로파일 샘플링(12%, 편향)으로는 통계가 틀린다 | F-A22, F-S21, D-12 |
| DSN-02-09 | `tx_id` = 41비트 ms ‖ 12비트 노드 ‖ 11비트 시퀀스(Snowflake형, 랜덤 금지). `trace_id` Argos 생성분은 상위 4B에 Unix 초 | 32비트 초 + 32비트 랜덤 | 전역 유일, id만으로 파티션 범위 계산(ts 힌트 불필요), 충돌 0 | D-11, A-6 해소 |
| DSN-02-10 | 사전 키(service·sql·name·error_group·host·frame)는 xxh3_64 내용 해시. 원문은 PostgreSQL `dictionary`에 비동기 upsert | 서버 발급 순번, 32비트 해시 | 적재 경로가 사전 왕복에 묶이지 않음, 충돌 확률 무시 가능 | D-08 |
| DSN-02-11 | 전송은 **배치 단위 unary RPC**(100ms 또는 500건, deadline 5초, in-flight ≤ 4) + 세션 양방향 스트림. 중복 제거 키는 `(instance_id, agent_epoch, 배치 종류, seq)`이며 `seq`는 종류별 단조 증가, `agent_epoch`는 기동 시 난수(DSN-11-02) | 클라이언트 스트리밍 + 단일 Ack | 배치별 ack·재전송, 재기동 후 seq 리셋 구분, gRPC 내부 버퍼 무제한 증가 방지 | N-02, N-05, D-13 |
| DSN-02-12 | 서버는 수신 배치를 **WAL에 기록 후 ack**(fsync 직후 ack, 적재는 비동기). 블록 경계는 WAL 자체의 INTENT/COMMIT 레코드로 기록하고(DSN-12-02), ClickHouse insert마다 `insert_deduplication_token = argos:{table}:{block_id}`(block_id = COMMIT 레코드 위치, DSN-13-07)를 붙여 MV까지 중복 제거. 중복 제거 윈도는 메모리 + WAL 복원 | 메모리 `batch_seq` 윈도만, 별도 인덱스 파일 | 재시작·스풀 재적재·MV 부분 실패에서도 원본·롤업 비대칭 중복 0 | D-13 |
| DSN-02-13 | 롤업 SELECT 정의는 MV와 재구축 CLI가 같은 파일을 읽는다. 1h는 1m에서 캐스케이드. 야간 정합 검사가 전날 D-03·D-12를 검증하고 불일치 날을 재구축 | MV만 | 롤업을 결정적·재구축 가능하게 | D-14, F-S22 |
| DSN-02-14 | 앱 식별은 (name, env) 쌍. `env`는 모든 ClickHouse 테이블에 LowCardinality 컬럼, 모든 목록·규칙·전체 현황의 필터 | 라벨만 | 같은 앱 이름이 환경마다 존재. 나중에 넣으면 전부 고쳐야 함 | F-A25, A-8 |
| DSN-02-15 | 트랜잭션 `kind`(HTTP/BATCH/MESSAGE). 통계·Apdex·TPS는 기본 HTTP만, 종류별 분리 집계 | 배치 미지원 | 배치가 HTTP 통계를 오염시키지 않으면서 배치도 추적 | F-A24, D-20 |
| DSN-02-16 | 에이전트 토큰은 앱 단위, 서버는 SHA-256 해시만 보관, 폐기 즉시 세션 종료 | 전역 토큰, 평문 | 06 문서 A | SEC-01~06 |
| DSN-02-17 | 원격 변경 가능 설정은 에이전트 로컬 `remote-config.allow`가 정한다(하드 캡). 민감 수집 키는 기본 불허. 환경 덤프는 에이전트 측 마스킹 | 서버가 전권 | 서버·운영자 계정 침해가 전 앱 데이터 유출로 번지지 않게 | SEC-20~25 |
| DSN-02-18 | UI 인증은 httpOnly·Secure·SameSite=Strict 쿠키 + 서버 측 세션 레코드(즉시 무효화) + CSRF 토큰. WebSocket은 핸드셰이크 쿠키·Origin 검사 | Authorization 헤더 + localStorage | XSS 한 번으로 세션 탈취되지 않게, 역할 변경 즉시 반영 | SEC-40~44 |
| DSN-02-19 | 서버 역할 프로파일 `all` 단일 노드만 1.0 공식 지원. 역할 분리·다중 collector는 1.x | 1.0 HA | 구성 요소 최소화. 설계에 자리만 둔다 | F-S09(C), N-09 |
| DSN-02-20 | 메트릭 이름은 OpenMetrics 스타일(`snake_case`, 단위 접미사, 라벨). 서버 자기 메트릭도 같은 규약으로 `/actuator/prometheus` | 자체 규약 | 기존 Prometheus와 상호운용 | F-S20, D-16 |
| DSN-02-21 | 사용자 문서·UI 원본·로그는 영어, ko는 i18n. 기획·설계 문서는 한국어 | 한국어 원본 | 오픈소스 공개·외부 피드백 | O-A04·A05 |
| DSN-02-22 | 외부 네트워크 호출은 운영자가 설정한 대상(수집 서버, 알림 채널, SMTP)에만. 사용 통계·업데이트 확인 없음 | opt-out 텔레메트리 | 폐쇄망·공공·금융 전제, 오픈소스 신뢰 | O-P01, N-17 |

## 4. 구성 요소와 책임

### 4.1 에이전트 (`argos-agent.jar`, Java 8 타겟)

| 모듈 | 책임 | 설계 문서 |
|---|---|---|
| `agent-bootstrap` | bootstrap 클래스로더에 주입되는 최소 API: `TraceContextHolder`, `Tracer`·`CallDepth`·`ArgosThread`, 킬 스위치 플래그, 필드 주입 폴백용 약참조 맵. 의존성 0 | 10 |
| `agent-core` | 트랜잭션·프로파일 빌더(kind, step 트리, 상한), 전수 통계 집계기, bounded 큐·배치·전송, 설정 로더(파일/환경변수/서버 푸시 + `remote-config.allow`), 사전 캐시, 명령 처리(마스킹), 메트릭 수집기(JVM·풀·Tomcat·cgroup), 느린 스택 감시, 자기 보호·자가 진단, `--check` | 10, 11 |
| `agent-instrumentation/*` | 모듈 하나 = 패키지 하나: servlet, spring-webmvc, jdbc, pool, httpclient-{jdk,apache4,apache5,okhttp,jdk11,reactornetty}, executor, servlet-async, scheduler(@Scheduled·Quartz·Spring Batch), logging(MDC), custom. 각각 TypeMatcher + Advice + helper + witness | 10 |
| `agent-dist` | 단일 jar 패키징(`inst/*.classdata`), 셰이딩(`io.argos.agent.shaded.*`), 매니페스트(`Premain-Class`, `Agent-Class`, `Can-Retransform-Classes`), NOTICE·SBOM·버전 매니페스트 동봉, `check-shading.sh` | 19 |
| `agent-testing` | 샘플 앱 기동·요청·수집 결과 단언 하네스, 변환 목록 베이스라인, fail-safe 주입, 매트릭스 러너 | 18 |

핵심 규칙(상세는 10 문서): 요청 경계(F-A28), 서비스 이름 폴백·상한(F-A29), 제외 규칙(F-A23), JDBC 최외곽·DBC step(F-A03), Executor 허용목록·필드 주입(F-A07), 가상 스레드(F-A30), WebClient 범위(F-A33), 스트리밍 수명(F-A34), 외부 추적 공존(F-A32), 자기 보호·변환 안전·킬 스위치 2단(F-A15), 전송 상한(스레드 ≤ 6, 직접 메모리 ≤ 8MB, NIO·JDK TLS·Unsafe 미사용, F-A35).

### 4.2 서버 (`argos-server`, Java 21 Spring Boot, 단일 JVM)

| 역할 | 책임 | 설계 문서 |
|---|---|---|
| collector | gRPC 수신(상한·쿼터 검사 → WAL → ack), 세션 레지스트리(인스턴스 상태 모델 UP/STALE/DOWN/WARN), 사전 해석, 시계 차이 측정, 타임스탬프 가드, 실시간 버스 publish, 배치 라이터(dedup 토큰, 명시 컬럼), 에러 그룹 지문 계산, 전수 통계 적재 | 12 |
| web | REST `/api/v1`(OpenAPI, `x-argos-role`), 소스 자동 선택(≤6h 원본 / ≤7d 1m / 그 이상 1h, 마지막 2분은 원본), WebSocket 멀티플렉스, 쿠키 세션·CSRF, 레이트 리밋 | 12, 14 |
| alert | 규칙 모델(평가 창·지속·최소 표본·단위·no-data·기동 유예·2단계·복구·재알림·그룹핑·음소거·ACK), 10초 평가, 상태 머신, 채널(목적지 검증·서명·재시도·실패 자기 알림), 기본 템플릿 | 12 |
| admin | 앱·인스턴스·승인 대기·토큰(해시·회전·폐기)·사용자·역할·권한 매트릭스·감사 로그·설정 변경 통제(사유·diff·결과·되돌리기)·킬 스위치·명령 라우팅(TTL·동시 1건·권한 분리)·개인정보 삭제 API | 12, 16 |
| ops | 야간 정합 검사·롤업 재구축 CLI, 저장소 상태 패널·디스크 비상 보존 단축, 자기 메트릭(Prometheus), 마이그레이션 러너(PostgreSQL Flyway + ClickHouse 자체), 설정 토큰 부트스트랩 | 12, 13, 17 |

### 4.3 저장소

| 저장소 | 내용 | 접근 |
|---|---|---|
| ClickHouse DB `argos` | `xlog`, `profile_step`, `metric`, `sql_stat_raw`·`ext_stat_raw`(10초 창, 15구간 히스토그램 `buckets` 포함), 롤업 `app_stat_1m/1h`, `service_stat_1m/1h`, `instance_stat_1m`, `sql_stat_1m/1h`, `ext_stat_1m/1h`, `error_stat_1m`, `metric_1m/1h`. 서버 자기 메트릭은 `metric`에 `app_id = 0`으로도 기록 | `argos_writer`(INSERT), `argos_reader`(readonly, 쿼리 상한), `argos_migrator`(DDL). timezone UTC. 상세는 13 문서 |
| PostgreSQL 스키마 `argos` | `users`, `user_sessions`, `user_permissions`(pii.view 등), `roles`, `apps`, `instances`, `pending_registrations`(승인 대기), `agent_tokens`(해시), `dictionary`(원문, kind, hash64, norm_version), `error_groups`, `alert_rules`(+`alert_rule_revisions`, `alert_states`), `alert_events`, `mutes`, `notification_channels`(암호화), `notification_deliveries`, `webhook_signing_keys`, `commands`, `command_results`(암호화), `events`(배포·자동 이벤트), `config_changes`·`config_change_results`, `privacy_jobs`, `reconcile_results`, `service_apdex_overrides`, `audit_logs`(앱 역할은 INSERT·SELECT만), `settings`, `ch_schema_history` | 앱·감사 기록·백업·마이그레이션 계정 분리, Flyway. 상세는 13·16 문서 |
| 서버 로컬 디스크 | WAL(`data/wal`, 상한 10GiB), 설정 토큰, 마스터 키 | 0600/0700 |

### 4.4 UI (`ui/`, React + TypeScript)

전체 현황(기본 화면) → 앱 대시보드 → XLog → 트랜잭션 상세 → 트레이스. 액티브 트랜잭션, 서비스·SQL·외부 호출·에러 통계, 인스턴스 상세·스레드 덤프, 알림(규칙·채널·이력), 관리(앱·인스턴스·토큰·설정 변경·사용자·감사·개인정보·라이선스). 전역 상태(앱·환경·시간 범위·종류·필터)는 URL에 있다. 수집 데이터는 텍스트 노드로만 렌더한다. 상세는 15 문서, 화면별 필수 요소는 05 문서 2장.

## 5. 데이터 모델 요약과 데이터 분류표

### 5.1 핵심 엔터티

| 엔터티 | 키 | 핵심 필드 | 저장 |
|---|---|---|---|
| Application | `app_id`(서버 발급) | name, env, group, 등록 정책, Apdex T | PostgreSQL |
| Instance | `instance_id` | app_id, name(설정 → `host:port` → `host:pid`), host, pid, JVM, OS, cgroup, 에이전트 버전, 버전 태그, 가상 스레드 모드, IP 수집 모드, 상태, 설정 해시, 시계 차이 | PostgreSQL + 메모리 |
| Transaction(xlog) | `(app_id, ts, tx_id)` | env, instance_id, kind, trace_id/span/parent, caller_app_id, service_id, version_id, http_method/status, elapsed_ms, error, error_class_id, error_group_id, apdex_t_ms, remote_ip(모드), user_id(모드), sql_count/time, ext_count/time, cpu_time_ms(결측 가능), alloc_bytes(결측 가능), thread_name_id, profile_captured, sql_ids(A-10) | ClickHouse 30일 |
| Profile step | `(app_id, tx_id, seq)` | parent_seq, type(SQL/DBC/COMMIT/ROLLBACK/HTTP/METHOD/ASYNC/ERROR/STACK/MESSAGE), start_offset_ms, elapsed_ms, name_id, target_id, rows, status, error, detail(≤4KB, 마스킹), stack(frame id 배열) | ClickHouse 7일 |
| Stat(10초 창) | `(app_id, sql_id, db_id, ts)` / `(app_id, host_id, ts)` | count, error_count, sum_ms, max_ms, fetch_rows | ClickHouse 14일 → 1m 90일 → 1h 1년 |
| Metric | `(app_id, instance_id, name, ts)` | value, 종류(gauge/counter)는 레지스트리 | ClickHouse 14일 → 1m/1h |
| Dictionary | `(app_id, kind, hash64)` | text(≤64KB, truncated 플래그), norm_version, first/last_seen | PostgreSQL |
| Event | id | app_id, env, ts, type(deploy/start/stop/config/state), title, detail, version | PostgreSQL |

### 5.2 데이터 분류표 (SEC-30)

새 필드를 추가하는 모든 변경은 이 표를 갱신해야 한다(03 공통 DoD). "개인정보 가능" 필드의 기본값은 수집 안 함 또는 마스킹이다.

| 필드 | 분류 | 기본 수집 | 마스킹·모드 | 보존 | 삭제 경로 |
|---|---|---|---|---|---|
| 서비스 이름(URL 패턴) | 내부 | 수집 | 쿼리스트링 제외, 숫자·UUID 세그먼트 정규화, 사전 상한(개수 10,000, 추정 바이트 4MiB·개수 상한에 비례, ADR-0010 C-120) | 사전 90일 미참조 정리 | 사전 관리자 삭제 |
| URL(원 경로) | 개인정보 가능 | 경로만 | `capture.query-string: off`(기본)/whitelist/full, 길이 2KB | xlog 30일 | 삭제 API(tx 범위) |
| HTTP 헤더 | 개인정보 가능 | 수집 안 함 | 화이트리스트에 있는 이름만(소문자, 최대 10개, 이름 128B), 값 1KiB, 값 없는 헤더는 보내지 않음, 원격 설정 불가 | xlog 30일 | 삭제 API |
| remote_ip | 개인정보 가능 | 설치 시 선택 강제 | `full`/`masked`(권장 기본)/`off`, trusted-proxies가 있을 때만 XFF(마지막 32줄, 오른쪽 2,048자를 항목 경계로 절단, 오른쪽 32홉까지) | xlog 30일 | 삭제 API(IP 기준) |
| user_id | 개인정보 가능 | 수집 안 함 | `off`(기본)/`plain`/`hashed`(base64url(HMAC-SHA256(앱 솔트, 정규화 id)) 앞 16자, 솔트가 없으면 보내지 않음, ADR-0010 C-163). 정규화: ISO 제어문자 제거, 128자 절단, trim, UTF-8 | xlog 30일 | 삭제 API(user_id 기준) |
| SQL 정규화문 | 내부 | 수집 | 리터럴 → `?`, 실패 시 원문 미전송(`<unparsed>`) | 사전 | 사전 관리자 삭제 |
| SQL 바인드 파라미터 | 개인정보 가능 | 수집 안 함(`masked` 기본: 값·타입·길이 모두 보내지 않음, ADR-0010 C-181) | `masked`(기본)/`partial`(`capture.sql-parameters.whitelist-columns` ≤256개의 `<column> = ?` 위치만 Masker 뒤 값, 나머지 위치는 `[String(11)]`처럼 타입·길이)/`full`(전 값 Masker 뒤, 서버는 WO-71 전까지 저장 안 함 C-148, 관리자 열람·감사). detail 형식 `1='<email>', 2=[String(13)], 3=42, 4=NULL`, JDK 값 타입만 렌더(byte[]는 길이, 그 밖의 객체는 타입명, toString 금지, LOB·스트림 안 읽음), 값 256자·위치 64·실행당 4,096자·배치는 첫 세트, 실행 뒤 폐기 | profile 7일 | 삭제 API |
| 예외 메시지 | 개인정보 가능 | 수집(마스킹 후) | 1KB 상한, 정규식 마스킹(이메일·전화·주민번호·카드(Luhn 검사)·IP), 사용자 패턴 평가가 실패하면 메시지 전체 `<masked>`(fail closed) | xlog·profile | 삭제 API |
| 스택 트레이스 | 내부 | 수집 | 프레임 사전 id, 깊이 30/64. 출처 둘(WO-19, ADR-0010 C-240 (11)): ERROR step은 예외로 끝난 트랜잭션의 `getStackTrace` 사본 앞 `capture.stack-depth`개(최대 30)와 첫 앱 프레임 `error_top_frame_id`(줄 번호 없음), STACK step은 1초 넘게 실행 중인 요청의 느린 스택 표본(`transaction.slow-stack.*`, 깊이 64, 트랜잭션당 최대 5개, 사이클당 10개) | profile 7일, 대표 스택은 error_groups | - |
| 스레드 덤프 본문 | 개인정보 가능(비밀값 가능) | 명령 시만 | 서버 마스터 키 봉투 암호화 저장, 운영자 이상 열람(감사) | 30일 | 자동 만료 |
| 바인드 파라미터 `full` 값 | 개인정보 가능 | `full` 모드일 때만 | ClickHouse에도 봉투 암호화 컬럼(`*_enc`)으로 저장, `pii.view` 권한자만 복호화 열람(감사). 권한 없는 요청은 403이 아니라 `masked_by_role`로 강등 반환 | profile 7일 | 삭제 API |
| user_id 해시 솔트 | 비밀값 | 앱 생성 시 | 앱별 솔트를 PostgreSQL에 암호화 저장, ServerHello로 에이전트에 전달 | 앱 수명 | 앱 삭제 |
| 환경변수·시스템 프로퍼티 덤프 | 비밀값 | 명령 시만(관리자) | 에이전트 측 키·값 패턴 마스킹(끄기 불가), 암호화 저장, 열람 감사 | 30일 | 자동 만료 |
| 에이전트 토큰 | 비밀값 | - | 서버 해시만, 발급 시 1회 표시, 로그·번들 마스킹 | - | 폐기 |
| 알림 채널 URL·SMTP 비밀번호 | 비밀값 | - | 마스터 키 AES-256-GCM, UI 마스킹 | - | 삭제 |
| 사용자 비밀번호 | 비밀값 | - | bcrypt cost 12 | - | - |
| 감사 로그 | 내부(개인정보 가능: 행위자·열람 대상) | 수집 | 비밀값 마스킹 | 1년(설정) | API 삭제 불가, 만료 삭제 |
| 메트릭 | 내부 | 수집 | 시리즈 상한 | 14일/90일/1년 | - |
| 인스턴스 메타(호스트·PID·JVM) | 내부 | 수집 | - | 인스턴스 보관 정책 | 인스턴스 삭제 |
| 스레드 이름(`thread_name_id` 사전) | 내부(앱이 이름에 사용자 값을 넣으면 개인정보 가능) | 수집 | 앞 256자에서 UUID·8자 이상 16진·`-` 뒤 숫자 제거, 에이전트당 1,000 이름(넘으면 `_other_`). 원래 이름은 개발 빌드 JSONL 테스트 익스포터에만 | 사전 90일 미참조 정리 | 사전 관리자 삭제 |
| 트랜잭션 CPU 시간·할당 바이트 | 내부 | 측정할 수 있을 때만(가상 스레드·다른 스레드 종료·미지원이면 결측, D-23) | - | xlog 30일 | 삭제 API(tx 범위) |
| 트랜잭션 flags(가상 스레드·leaked·summarized, C-69)·`profile_captured` | 내부 | 수집 | - | xlog 30일 | 삭제 API(tx 범위) |
| step `repeat_count`·`truncated`·`total_steps` | 내부 | 수집 | - | profile 7일 | 삭제 API |
| 에이전트 유효 설정·`config_hash`(값·출처) | 내부 | 수집(기동 로그, Hello) | 비밀 키는 `argos_****`, 해시에서 토큰 제외 | 인스턴스 보관 정책 | 인스턴스 삭제 |
| 에이전트 로그·기동 실패 stderr 한 줄(C-78) | 내부 | 로컬 파일 | INFO 이하에 수집 데이터 0(SEC-26), 토큰 `argos_****`, 인용 값은 마스킹 뒤 64자 절단, 디렉터리 0700 | 로컬 롤링 | 운영자 삭제 |
| 에이전트 자가 진단 스냅숏 파일(C-95) | 내부 | 개발 빌드에서 테스트 키를 줄 때만 | 에이전트 자체 지표·이름만 | 덮어씀 | 파일 삭제 |
| Argos 사용자 세션 IP(`user_sessions.ip`) | 개인정보 가능(보안 기록) | 로그인·리프레시 때 | 전체 주소, `default-ip-mode` 미적용, 관리자·본인만 열람(ADR-0010 C-117) | 세션 만료·폐기 뒤 7일 | 세션 폐기·사용자 삭제·만료 정리 |
| Argos 사용자 세션 UA(`user_sessions.user_agent`) | 개인정보 가능(낮음) | 수집 | 256자 절단, HTML 렌더 금지 | 세션과 같음 | 세션과 같음 |
| 리프레시 토큰 해시(`user_sessions.refresh_hash`) | 비밀값 파생 | 발급 때 | SHA-256 hex만 저장, 원문 미저장, 로그·번들 제외 | 세션 수명 | 세션 폐기·사용자 삭제 |
| 감사 로그 IP·UA(`audit_logs.ip`·`user_agent`) | 개인정보 가능(보안 기록) | 감사 이벤트 때 | 전체 주소(C-117), UA 256자, before/after는 `AuditMasking` 뒤 저장 | 감사 로그와 같음 | 파티션 DROP(만료) |
| 설정 토큰(32B base64url) | 비밀값 | 활성 ADMIN이 없을 때 기동 시 생성(ADR-0010 C-134) | 메모리·`conf/setup-token` 0600, 로그 1줄(DSN-16-12), 번들 제외 | 30분 또는 관리자 생성 즉시 | 파일 삭제·메모리 폐기 |
| JWT 서명 키(Ed25519) | 비밀값 | 설치 시 생성 | `conf/jwt.key` 0600 또는 환경변수, 로그·번들 제외, kid 회전 | 교체까지 | 키 교체 |
| DB 계정 비밀번호(PG owner·app·audit·backup, CH writer·reader·migrator) | 비밀값 | 설치기 생성 | 파일 0600·psql 변수, CH는 SHA-256만, 로그·유효 설정 마스킹 | 교체까지 | 교체 |
| `ch_schema_history.error` | 내부 | 마이그레이션 실패 때 | 한 줄 2,000자 | 영구 | - |
| `users.kiosk_ip_allowlist`, `pending_registrations.host` | 내부 | 설정·등록 때 | - | 행과 같음 | 행 삭제 |
| xlog `dispatch_count`(C-17, WO-12) | 내부 | 수집 | REQUEST 1, 다른 디스패치마다 +1 | xlog 30일 | 삭제 API(tx 범위) |
| xlog flags `streaming`·`timed_out`(C-17·C-69, F-A34) | 내부 | 수집 | - | xlog 30일 | 삭제 API(tx 범위) |
| 액세스 JWT(`argos_at` 쿠키) | 비밀값 파생 | 로그인·리프레시 때 | 저장 안 함(서명 검증), HttpOnly 쿠키로만, 로그 마스킹 | 토큰 수명(16 §7.1) | 만료 |
| CSRF 토큰 | 비밀값 파생 | 요청 때 | 저장 안 함(더블 서브밋 쿠키), 로그 마스킹 | 쿠키 수명 | - |
| 세션 내부 열(`user_sessions.family_id`·`rotated_at`·`rotated_from`·`revoked_reason`·`idle_expires_at`·`absolute_expires_at`) | 내부(보안 기록) | 발급·회전·폐기 때 | - | 세션과 같음 | 세션과 같음 |
| 로그인 실패 기록(`login_failures_unknown`, `users.failed_logins`·`lockout_level`) | 내부(미존재 이름은 SHA-256 가명) | 로그인 실패 때 | 미존재 이름 원문 미저장, `argos_app` DML·`argos_backup` SELECT | `login_failures_unknown` 상한 10만 행(오래된 실패부터 삭제) | users는 성공 로그인·관리자 해제·비밀번호 변경/재설정 때 초기화, 미존재 이름은 축출만 |
| 사용자 권한(`user_permissions`) | 내부(관리자 부여 기록) | 부여 때 | 부여자·시각 기록 | 사용자와 같음 | 사용자 삭제 시 CASCADE |
| `settings.default_ip_mode` | 내부 | 설치·설정 때 | - | 영구 | 설정 변경 |
| 레이트 리밋 버킷 키(IP·세션 id·사용자 id) | 내부 | 요청 때 | 메모리만, 상한 있음 | 전체 리필 시간의 2배 | 자동 폐기 |
| CSP 위반 리포트 | 내부 | 브라우저 보고 때 | 저장 안 함, 로그에 directive·차단 origin·disposition만 | 로그 보존 | - |
| DB 커넥션 풀 이름(`DictionaryKind.POOL` 사전, DBC step `name_id`) | 내부 | 수집(WO-13부터) | 사전 상한 | 사전 90일 미참조 정리 | 사전 관리자 삭제 |
| DB 대상(`DictionaryKind.DB_TARGET`, SQL·COMMIT·ROLLBACK·DBC step `target_id`, WO-13) | 내부 | 수집 | `<vendor>://<host>:<port>/<db>`, 계정·비밀번호·매개변수 제거, 첫 호스트만, 안전하게 못 읽으면 벤더만. H2는 `h2:mem:<name>`, 파일 DB는 마지막 경로 조각 | 사전 90일 미참조 정리(상한 C-120 비례) | 사전 관리자 삭제 |
| Tomcat 커넥터 이름(`InstanceUpdate.connectors`, 메트릭 라벨 `connector`, WO-13) | 내부 | 수집 | 프로토콜·포트 꼴 운영 식별자 | 인스턴스 메타데이터와 같음 | 인스턴스 삭제 |
| 인스턴스 이름 기본값 `host:port`(`app.instance` 미설정 시 rename, WO-13) | 내부 | 수집 | 호스트명·포트만 | 인스턴스 메타데이터와 같음 | 인스턴스 삭제 |
| SQL step 부가 필드(SQL_TEXT truncated 플래그, `TransactionRecord.dbc_wait_ms`, ProfileStep `batch_size`·`fetch_us`·`rows`, WO-13) | 내부 | 수집 | 수치·플래그만 | xlog 30일, profile 7일 | 삭제 API(tx 범위) |
| SQL·외부 호출 전수 통계(`SqlStat`·`ExtStat`: count·error·sum·max·`buckets[15]`·`sum_exec/fetch`) | 내부 | 수집(WO-16부터) | - | `sql_stat_raw`·`ext_stat_raw` 원본 14일, 롤업 정책 | - |
| 평문 접속 선언(`AgentHello.plaintext`) | 내부 | 수집 | - | 인스턴스 보관 정책 | 인스턴스 삭제 |
| 프로파일 트랜잭션 종류(`TransactionProfile.kind`, C-138) | 내부 | 수집 | - | profile 7일 | 삭제 API |
| 요청 ID(`request_id`, `capture.request-id-header`, C-124) | 내부(헤더 값에 사용자 값이 들어가면 개인정보 가능) | 수집 안 함(설정한 경우만) | 헤더 1개, 값 128B 절단, 원격 설정 불가(10 §12 기본 불허) | xlog 30일 | 삭제 API(tx 범위) |
| ClickHouse `system.query_log` 조회 문(바인딩된 검색 값 `user_id`·`request_id`·`trace_id` 포함, WO-23) | 개인정보 가능 | 기록(ClickHouse 기본 동작) | 조회 API로 노출하지 않는다. `argos_reader`와 운영자의 DB 직접 접근만. hashed 모드의 user_id는 해시 값만 남는다 | 7일(config.d TTL) | TTL 만료. 개인정보 삭제 요청 안내에 '조회 기록은 7일 안에 자연 삭제'를 적는다(WO-28 문서) |
| 브라우저 localStorage UI 선호 키 `argos.ui.{live,sidebar,lang,refreshAt,recentApps,cols}`(WO-30) | 내부(UI 선호, 개인정보·비밀값 아님) | 수집 안 함(서버로 보내지 않음) | `refreshAt`은 탭 간 single-flight용 epoch ms(토큰 아님), `recentApps`는 앱 id ≤ 5, `cols`는 표 50·열 64 상한 | 브라우저 | 브라우저 저장소 삭제 |
| `pending_registrations.home_app_id`(WO-22, V004) | 내부(내부 앱 id, 개인정보 아님) | 등록 대기 때 | - | 행과 같음 | 행 삭제, 기준 앱 삭제 시 CASCADE |
| 액티브 게이지 `argos_active_transactions`(ClickHouse `metric`·`metric_1m`·`metric_1h`, WO-22, C-178 (1)) | 내부 | 수집(액티브 스냅숏마다) | 개수만, 트랜잭션 내용 없음 | 메트릭과 같음(14일/90일/1년) | - |
| 인스턴스 1시간 롤업 `instance_stat_1h`(ClickHouse V003, WO-27, C-212 (1)) | 내부(집계, 개인정보 없음) | 수집(`instance_stat_1m` 캐스케이드) | 인스턴스·종류별 건수·합계·상태(DD 백분위 상태)만, 트랜잭션 내용 없음 | 1h 롤업과 같음(365일) | 파티션 만료 |
| 정합 검사 결과 `reconcile_results`(PG V005, WO-27, 12 §9.1) | 내부(운영 메타데이터, 개인정보 없음) | 야간 정합·재구축 때 | `app_id`·테이블 이름·불일치 분 수·재구축 결과·digest(`detail` JSON)만 | 영구(깨끗한 날은 요약 1행) | - |
| 서비스 Apdex T 오버라이드 `service_apdex_overrides`(PG V006, WO-27, D-17) | 내부(설정, 개인정보 없음) | 설정 변경 때 | `app_id`·`service_id`·`apdex_t_ms`·`updated_at`·`updated_by`(사용자 UUID, 외래 키 없음)만, 앱당 1,000행 상한 | 행과 같음 | 행 삭제, 앱 삭제 시 CASCADE |
| 들어온 W3C `tracestate`(WO-14, ADR-0010 C-223 (9)) | 내부(다른 벤더 엔트리에 상대 시스템 값이 있을 수 있음) | 메모리 전달만(저장 안 함) | W3C 키·값 규칙 검사, 512자·32멤버를 넘거나 규칙을 어기면 통째로 버림, `argos` 엔트리는 `propagation.argos-state.hosts` 대상으로 나갈 때만 붙임 | 트랜잭션 수명(메모리) | - |
| `TransactionRecord.caller_app_id`(WO-14, C-223 (9)) | 내부(신뢰하지 않는 값, 서비스 맵·트레이스 연결용) | 수집(들어온 `tracestate`의 `argos` 엔트리가 있을 때만, 없으면 0) | 앱 id(u32)만 | xlog 30일 | 삭제 API(tx 범위) |
| HTTP 외부 호출 step detail `METHOD host[:port]/경로`(WO-14, C-223 (9)) | 개인정보 가능(경로 세그먼트에 식별자가 섞일 수 있음) | 수집 | 쿼리스트링·프래그먼트·`;` 경로 매개변수 제거, userinfo 제거, 제어문자 `?`, 4KiB(4,096자) 절단. 앱이 직접 넣어 보존한 `traceparent`가 있으면 ` traceparent=<값>`을 덧붙임 | profile 7일 | 삭제 API |
| HOST 사전 원문 `scheme://host:port`(`DictionaryKind.HOST`, HTTP step `target_id`, ADR-0010 C-142, WO-14) | 내부 | 수집 | scheme·host 소문자, 기본 포트도 적음, IPv6 대괄호, userinfo·경로·쿼리 없음 | 사전 90일 미참조 정리(상한 C-120 비례) | 사전 관리자 삭제 |
| 외부 호출 실패 분류 `ProfileStep.failure`(WO-14, F-A05, C-223 (3)·(9)) | 내부 | 수집 | `NONE`·`TIMEOUT`·`CONNECT_FAIL`·`HTTP_5XX`·`OTHER` 열거값만(원인 기준, 예외 내용 없음) | profile 7일 | 삭제 API |
| 알림 단위 상태 `alert_states`(PG V007, WO-25, ADR-0010 C-226 (13)) | 내부(운영 상태). `target_name`은 인스턴스 이름·서비스 이름·호스트 원문(사전과 같은 분류), `acked_by`는 사용자 UUID, `ack_note`는 사용자가 쓴 자유 문자열(개인정보를 쓸 수 있음) | 평가·전이 때(규칙×단위 1행) | 수집 원문(SQL·예외·URL 원문·user_id) 없음. `ack_note` 512자, HTML 렌더 금지 | 규칙·단위가 있는 동안(RESOLVED·OK도 행 유지) | 규칙 삭제·단위 정리 시 행 삭제 |
| 알림 음소거 `mutes`(PG V007, WO-25, C-226 (13)) | 내부. `reason`은 사용자가 쓴 자유 문자열(개인정보를 쓸 수 있음), `created_by`는 사용자 UUID | 운영자 생성 때 | `reason` 512자, 최대 30일, HTML 렌더 금지 | 행과 같음 | 운영자 삭제(API), 앱 삭제 시 CASCADE |
| 알림 규칙 soft delete `alert_rules.deleted_at`(PG V007, WO-25) | 내부 | 규칙 삭제 때 | - | 영구(`alert_events`가 참조) | - |
| 웹훅 서명 키 `webhook_signing_keys`(PG V008, WO-25, 16 §8.4, C-226 (13)) | 비밀값 | 웹훅 채널 생성·회전 때 | `key_enc` 봉투 암호문(AES-256-GCM, 마스터 키, AAD에 채널·키 id), `key_id`는 화면에 보이지 않음, 로그·번들 제외. 유효 키 최대 2개(회전 뒤 7일 유예) | 회전 뒤 7일(`retire_after`), 현재 키는 채널 수명 | 일일 정리, 채널 삭제 시 즉시 삭제 |
| 알림 채널 상태 열 `notification_channels.status`·`consecutive_failures`·`last_success_at`·`last_failure_at`·`deleted_at`(PG V008, WO-25) | 내부(운영 상태) | 전달 결과·삭제 때 | - | 행과 같음(삭제된 채널은 비밀값을 지운 행만 남김) | soft delete(`config_enc` 비움·서명 키 삭제) |
| 이메일 채널 수신자 주소(`notification_channels.config_enc` 안, WO-25, C-226 (5)·(13)) | 개인정보 가능 | 관리자 입력 때 | 평문 저장 안 함: `config_enc` 봉투 암호화(마스터 키), 화면·`config_masked`는 `o***@example.com` 꼴 마스킹, 채널당 20개 | 채널 수명 | 채널 삭제(soft delete 때 `config_enc` 비움) |
| 알림 전달 기록 `notification_deliveries`(PG V001·V008, WO-25, F-S27, C-226 (13)) | 내부 | 알림 이벤트×채널마다 | `attempt`·`status`(pending·sent·failed·dropped)·마지막 `http_status`·`error`는 오류 코드만, 응답 본문·URL·수신자 없음 | 365일 | 일일 정리 |
| 알림 전이 사유 `alert_events.reason`(PG V009, WO-38, ADR-0010 C-227 (3)·C-228 (2)(7)) | 내부(운영 코드, 개인정보 없음) | 상태 전이 때. `alert` push에도 같은 값 | 닫힌 목록 `condition`·`cleared`·`escalated`·`acked`·`resolved`·`target_gone`·`rule_disabled`·`rule_deleted`·`no_data`·`data_returned`만(검사 제약), V009 이전 행은 null | 365일(`alert_events`와 같음) | 일일 정리 |
| 알림 규칙 이력 표시 이름 `changed_by_name`(응답 전용, WO-38, C-228 (7)) | 내부(사용자 이름, untrusted 표시) | 저장 안 함(조회 때 `users`에서 파생) | `x-argos-untrusted`, HTML 렌더 금지, 삭제된 사용자는 null | 저장 안 함 | - |
| 알림 규칙 요약 `AlertRuleRef.metric`·`unit`(응답 전용, events·`/overview/alerts`·대시보드 `firing[]`, WO-38, C-228 (7)) | 내부(규칙 정의에서 파생) | 저장 안 함 | F-S31 메트릭 닫힌 목록·단위 열거값만 | 저장 안 함 | - |
| 메트릭 라벨 `mount`(`system_disk_*_bytes`, WO-16, ADR-0010 C-229 (9)) | 내부(경로에 사용자 이름이 들어갈 수 있음) | 수집(Linux 로컬 파일 시스템, 최대 16개) | `/home/<name>`·`/Users/<name>`·`C:\Users\<name>` 세그먼트를 에이전트가 `~`로 바꿔 보냄, 같은 라벨은 첫 마운트만 | 메트릭과 같음(14일/90일/1년) | - |
| 메트릭 라벨 `iface`·`pool`·memory pool·buffer 이름·표 밖 GC 원문 이름(WO-16, C-229 (9)) | 내부(운영 식별자: 네트워크 인터페이스·풀·JVM 메모리 영역·GC 이름) | 수집 | 레지스트리·시리즈 상한(`metrics.max-series` 500), 표 밖 GC는 원문 이름 그대로(앱 설정 값) | 메트릭과 같음(14일/90일/1년) | - |
| 인스턴스 메타 `cgroup`(`AgentHello.cgroup` `v1`·`v2`·`none`, WO-16, C-229 (9)) | 내부 | 수집 | 열거값만 | 인스턴스 보관 정책 | 인스턴스 삭제 |
| 액티브 스냅숏 항목(`ActiveSnapshot`: 서비스·경과·상태·SQL 정규화문·`user_id`·`remote_ip` 등, WO-16, C-225 (1)·C-229 (9)) | 개인정보 가능(`user_id`·`remote_ip`) | 수집(5초마다) | `user_id`·`remote_ip`는 `TransactionRecord`와 같은 캡처 모드를 적용한 값(위 user_id·remote_ip 행), SQL은 `sql_id`(정규화문 사전)만 | 서버 메모리에 인스턴스당 최신 1개만, 세션 종료 시 폐기(저장 안 함) | 세션 종료 |
| 에이전트 토큰 폐기 기록 `agent_tokens.revoked_by`·`revoke_reason`(PG V010, WO-26, ADR-0010 C-231 (1)) | 내부. `revoked_by`는 사용자 UUID, `revoke_reason`은 관리자가 쓴 자유 문자열(개인정보를 쓸 수 있음) | 관리자 폐기 때(서버·명령줄 폐기는 `revoked_by` null) | `revoke_reason` 512자, 감사 로그에는 길이만, HTML 렌더 금지. 사용자 삭제 시 `revoked_by` null(ON DELETE SET NULL) | 토큰 행과 같음 | 토큰 행 삭제 |
| 원격 명령 부가 열 `commands.args`·`sent_at`·`error_code`(PG V011, WO-26, C-231 (3)) | 내부 | 명령 요청·전송·종료 때 | `args`는 THREAD_STACK의 `thread_id`·`tx_id`(숫자만, 8항목·값 256B), 비밀값·수집 원문 없음. `error_code`는 닫힌 목록(`rejected`·`cooldown`·`agent_failed`·`agent_expired`·`ttl`·`timeout`·`session_ended`·`invalid_result`, 검사 제약), 에이전트 문자열 아님 | 30일(`argos.retention.command-results`), 인스턴스당 최신 100건 | 일일 정리, 인스턴스 삭제 시 삭제 |
| 명령 결과 형식 `command_results.content_type`(PG V011, WO-26) | 내부 | 결과 수신 때 | `text/plain`·`application/json`만. 본문은 기존 `body_enc`(봉투 암호화, SEC-22) | 명령 결과와 같음(30일) | 명령 행 삭제 시 CASCADE |
| 자가 진단 명령 결과(SELF_DIAGNOSTICS JSON: 에이전트 버전·JVM·유효 설정 키·값·출처·원격 허용 목록·수집 모드·큐·사전·에이전트 스레드·모듈 이름·상태·사유, WO-17·WO-37, ADR-0010 C-234 (1)(9)) | 내부(수집 데이터 없음, SEC-26) | 명령 시만(운영자 이상) | 토큰과 비밀 같은 키의 값은 `argos_****`, 값 안의 토큰 꼴 문자열 마스킹, 256 KiB 상한. `command_results.body_enc` 봉투 암호화(SEC-22), 열람은 요청자·관리자만(SEC-50), 열람 때 `COMMAND_RESULT_VIEWED` 감사. 환경 탭은 사용자가 '최근 자가 진단 보기'를 누를 때만 읽는다(C-234 (3)) | 30일 | 자동 만료, 명령 행 삭제 시 CASCADE |
| 브라우저가 저장하는 스레드 덤프 `.txt`(덤프 뷰어 다운로드, WO-37) | 스레드 덤프 본문과 같음(개인정보 가능) | 사용자가 다운로드할 때만 | 서버 저장 아님(비고): 이미 열람·감사된 결과를 브라우저가 텍스트로 만들어 사용자 PC에 저장. 서버 전송·외부 호출 없음 | 사용자 PC | 사용자가 삭제 |
| 명령 요청자 표시 이름 `requested_by_name`(응답 전용, 명령 목록·상세, WO-37, C-234 (9)) | 내부(사용자 이름, untrusted 표시) | 저장 안 함(조회 때 `users`에서 파생, 비고) | `x-argos-untrusted`, HTML 렌더 금지, 삭제된 사용자는 `deleted-<id>` | 저장 안 함 | - |
| 설정 변경 `config_changes.reason`·`before`·`after`와 새 열 `kind`·`status`·`target_all`·`preview`·`applied_at`·`applied_by`(PG V001·V011, WO-26, F-S29, C-231 (4)) | 내부. `reason`은 관리자가 쓴 자유 문자열(개인정보를 쓸 수 있음), `applied_by`는 사용자 UUID | 미리보기·적용·되돌리기 때 | `reason` 1KiB(제어문자 거부), HTML 렌더 금지. `before`·`after`·`preview`는 에이전트 설정 키·값(원격 허용 키만, 비밀 키는 원격 대상이 아님, 16 §5.5), `kind`·`status`는 닫힌 목록(검사 제약) | 영구(변경 이력) | 앱 행이 남는 동안 유지(앱은 soft delete) |
| 앱 서버 보관 설정 `app_config`(PG V011, WO-26, 12 §6.2) | 내부(설정, 개인정보 없음) | 설정 변경 적용 때 | 키 128자·값 1,024자, 에이전트 문자열 형식, 비밀 키 없음(원격 허용 목록 밖), 화면에서 비밀 같은 값은 `****`, `updated_by`는 사용자 UUID | 행과 같음 | 되돌리기·키 제거 시 행 삭제, 앱 행 삭제 시 CASCADE |
| 설정 변경 결과 열 `config_change_results.applied_keys`·`rejected`·`effective_config_hash`·`duration_ms`·`pushed_at`(PG V011, WO-26, SEC-23) | 내부 | 푸시·적용 결과 때 | 키 이름과 거부 코드만(`rejected`는 `[{key, code, by}]`, 에이전트 `RejectCode` 또는 서버 `not_allowed`), 값 없음, `status`는 닫힌 목록(검사 제약) | 설정 변경과 같음 | 변경 행 삭제 시 CASCADE |
| 앱 코드 패키지 `apps.app_packages`(PG V012, WO-26, C-207 (3)) | 내부(관리자 설정, 개인정보 없음) | 앱 수정(PATCH) 때 | 50개·각 255자, `java.`·`javax.`·`jdk.`·`sun.`·`io.argos.` 거부. UI가 스택 프레임 강조에만 사용 | 앱 행과 같음 | 앱 수정 |
| 앱 soft delete `apps.deleted_at`(PG V012, WO-26, C-231 (5)) | 내부 | 앱 삭제 때 | 저장 이름 뒤 `#deleted-<id>`, 토큰 폐기·세션 종료, 수집 데이터는 TTL까지 둠 | 영구(인스턴스·토큰·알림 규칙이 참조) | - |
| 사용자 soft delete `users.deleted_at`(PG V012, WO-26, C-231 (5), C-136 (3)) | 내부(사용자 이름은 익명화) | 사용자 삭제 때 | 같은 문장에서 `username`을 `deleted-<id>`로, 비밀번호 해시를 무작위 비밀값의 해시로 바꾸고 비활성·키오스크 승인·추가 권한 제거·세션 폐기. 감사 로그의 옛 이름은 유지 | 영구(토큰·명령 기록이 참조) | - |
| 토큰으로 쓴 이벤트 `events`(type `deploy`·`custom`, `created_by` null, WO-26, 02 C-12, C-153) | 내부 | 앱 에이전트 토큰 Bearer로 `POST /events` 때(홈 앱만) | 행위자는 감사 로그에 토큰 접두만(원문·해시 없음), 앱당 분당 60건 | 영구(배포 마커) | 관리자 삭제(API, `deploy`·`custom`만) |
| 이벤트 본문 `events.detail.text`(WO-26, 14 §4.12) | 내부. 사용자·배포 도구가 쓴 자유 문자열(개인정보를 쓸 수 있음) | 이벤트 작성 때 | UTF-8 2KB, `{"text": ...}`로 저장, `x-argos-untrusted`, HTML 렌더 금지 | 이벤트 행과 같음 | 관리자 삭제(API, `deploy`·`custom`만) |
| 앱 user_id 솔트 열 `apps.user_id_salt_enc`(PG V013, WO-28, ADR-0010 C-125 (2)·C-152) | 비밀값 | 처음 필요할 때 서버가 생성(에이전트 Hello·hashed 검색·삭제) | 32바이트 솔트를 봉투 암호화(마스터 키, AAD `apps:user_id_salt_enc:<id>`), 화면·API·로그·번들에 없음, `ServerHello.user_id_salt`로 TLS에서만 전달, `argos-admin secrets rekey`로 재암호화 | 앱 수명 | 앱 삭제 |
| 개인정보 삭제 작업 `privacy_jobs`(PG V014 새 열 `started_at`·`rows_affected`·`tables`·`warnings`, `subject_hash`·`subject_kind`, WO-28, 13 §9) | 내부(대상은 가명) | 삭제 요청 때 | 대상 원문 저장 안 함: 정규화 값의 SHA-256 hex(`subject_hash`, 검사 제약)만, `subject_kind`는 `user_id`·`ip`, `tables`는 테이블·파티션·뮤테이션 id·완료 여부만, `warnings`·`error`는 짧은 코드(ClickHouse 오류 코드까지, 뮤테이션 문장 없음) | 영구(삭제 이력) | - |
| 감사 외부 싱크 출력(`argos.audit.sinks` `file:` JSONL, `syslog://`·`syslog+tcp://`·`syslog+tls://` RFC 5424, WO-28, 16 §11) | 감사 로그와 같음(개인정보 가능: 행위자·IP·열람 대상) | 운영자가 싱크를 설정한 경우만 | `audit_logs` 행과 같은 내용(before/after는 `AuditMasking` 뒤), JSONL 파일 0600·100 MiB×10 롤링, syslog SD-ID `argos@32473`(문서용 PEN, C-238 (7)), 비차단 큐 | JSONL은 롤링 10개, syslog는 수신 측 정책 | 운영자 파일 삭제·수신 측 정책 |
| 감사 폴백 파일 `argos.audit.fallback-file`(WO-28) | 감사 로그와 같음 | PostgreSQL 기록이 실패할 때만 | JSONL, 0600, `audit_logs` 행과 같은 내용 | 운영자가 PG로 옮기거나 지울 때까지(자동 삭제 없음) | 운영자 삭제 |
| 감사 열람 대상 `audit_logs.subject_user_id`·`subject_tx_id`(V001 열의 실값, WO-28, SEC-81) | 개인정보 가능(누구의 데이터를 열람했는지) | 개인정보 열람 감사 때(검색·상세·민감 열람) | user_id는 검색 필터와 같이 정규화(제어문자 제거, 128자), hashed 모드면 해시 값, tx_id는 부호 있는 64비트로 저장 | 감사 로그와 같음 | 파티션 DROP(만료), API 삭제 불가 |
| 에이전트 감사 이벤트 클라이언트 IP(`AGENT_AUTH_FAILED`·`AGENT_AUTH_BLOCKED`·`AGENT_SESSION_REJECTED`의 `audit_logs.ip`, WO-28, C-158 (1)·C-238 (15)) | 개인정보 가능(보안 기록) | 에이전트 인증 실패·차단·세션 거부 때(주소당 분당 상한) | 전체 주소, 마스킹 없음(C-117 보안 기록 근거, `default-ip-mode` 미적용), 토큰 원문·해시 없음(접두만) | 감사 로그와 같음 | 파티션 DROP(만료) |
| dead-letter 블록 파일(`argos.writer.dead-letter.dir`, WO-28, C-158 (3)·C-238 (15)) | 개인정보 가능(수집 원본 행) | ClickHouse가 결정적 코드로 3회 거부한 블록만 | 삽입 본문 그대로(`RowBinaryWithNamesAndTypes`), POSIX 디렉터리 0700·파일 0600(Windows는 상위 디렉터리 권한), 영역 상한 1 GiB(넘는 블록은 버리고 `argos_collector_dropped_total{reason="dead_letter"}`), 파일 이름에 시각·테이블·코드·WAL 범위만, 내용은 로그에 없음 | 자동 삭제 없음(운영자가 재적재 후 삭제) | 운영자 파일 삭제 |
| 진단 번들 `argos-diag-<host>-<ts>.tar.gz`(`argos-admin bundle`, WO-28, 17 §4.4) | 내부(운영 정보, 로그에 수집 데이터가 남아 있을 수 있음) | 운영자가 명령을 실행할 때만 | 0600, 토큰·비밀번호·마스터 키·채널 URL·메트릭 토큰 마스킹(T-SEC-06g), 서버 로그·유효 설정·버전·상태 요약 | 운영자가 지울 때까지 | 운영자 파일 삭제 |
| PG 설정 키 `settings.retention`·`settings.storage_ttl_last_run_at`(WO-28, C-238 (3)) | 내부(운영 설정, 개인정보 없음) | 보존 변경(API·CLI)·TTL 실행 관찰 때 | 테이블별 보존 일수(설정 파일보다 우선), 마지막 TTL 실행 시각만 | 영구 | 설정 변경 |
| 이벤트 `events` type `retention_emergency`(WO-28, 13 §8) | 내부 | 비상 보존 DROP 때 | detail은 테이블·파티션·바이트·디스크 사용률·소요 ms만 | 이벤트 행과 같음 | - |
| 인스턴스 이벤트 `events` type `start`(WO-29, ADR-0010 C-240 (1)·C-244 (3)) | 내부(운영 메타데이터, 개인정보 없음) | 새 에이전트 프로세스의 첫 Hello 때 1건(agent_epoch LRU 10,000 + `instances.started_at` ≠ `process_start_ms`, PG를 못 읽으면 남기지 않음, `check_only` Hello는 남기지 않음) | `detail.agent_version`, 앱·env·인스턴스 id, 서버 시각만(호스트·사용자·설정 값 없음) | 이벤트 행과 같음 | - |
| 서버 자기 메트릭 시리즈(ClickHouse `metric` app_id 0·instance 0·env '', WO-28, 12 §9.4) | 내부 | 수집(5초 격자) | 서버 미터 이름·태그(이유·테이블·리스너 등 닫힌 목록)만, 수집 데이터 없음. `argos_ch_memory_tracking_bytes`(C-238 (13)) 포함 | 메트릭과 같음(14일/90일/1년) | - |
| TLS 리로드 요청·결과 파일 `<data>/tls/reload.request`·`reload.result`(`argos-admin tls reload`, WO-28, 16 §3.1) | 내부 | CLI 요청 때 | 요청 시각·결과 코드·인증서 만료 시각만, 키·비밀번호 없음 | 다음 요청이 덮어씀 | 파일 삭제 |
| 토큰 발급 응답 `collector_address`(응답 전용, WO-28, C-231 (9)) | 내부(설치 주소) | 저장 안 함(`argos.collector.public-address` 설정 값) | 설정한 공용 수집 주소만, 토큰과 함께 1회 표시되는 설치 스니펫에 사용 | 저장 안 함 | - |
| 감사 detail 새 키(WO-28·쌍 14 마무리): `_kill_switch`·`_not_allowed_by_instance`(CONFIG_PUSH·KILL_SWITCH), `_outcome`(CUSTOM_METHOD_CHANGED: 개수·인스턴스별 상태·거부 코드, C-238 (8)), `subject_kind`·`subject_sha256`·`app_id`·`from`·`to`(PRIVACY_DELETE_*), 테이블·파티션·바이트·사용률(RETENTION_EMERGENCY_DROP), 서브커맨드·옵션 이름·결과(ADMIN_COMMAND_EXECUTED), digest·키 수(ROLLUP_REBUILT·CONSISTENCY_CHECKED·APDEX_REAPPLIED) | 감사 로그와 같음 | 해당 감사 이벤트 때 | 대상 원문 없음(삭제 대상은 SHA-256만), 설정 값은 `AuditMasking`·`ConfigValueMasking` 뒤, 비밀값 없음 | 감사 로그와 같음 | 파티션 DROP(만료) |
| 로그 상관 MDC 키 `argos.trace_id`·`argos.tx_id`(앱 로그 이벤트, WO-18, 10 §6.9, 공개 계약) | 내부(식별자) | 수집 아님: 트랜잭션 안에서 만든 앱 로그 이벤트에 에이전트가 넣음(Logback·Log4j 1.2·reload4j, Log4j 2는 garbage-free 맵만, C-237 (1)) | 이벤트 자신의 MDC 사본에만, 앱 스레드 MDC 불변, 값은 hex 식별자만 | 앱 로그 보존 정책(앱 소관) | 앱 로그 정책 |
| user_id 출처 `capture.user-id.source=mdc:<key>`(WO-18, C-125 (3)·C-237 (4)) | 개인정보 가능 | 수집 안 함(설정한 경우만, 원격 설정 불가) | 트랜잭션의 로그 이벤트 MDC에서 첫 값을 읽기만 함, 기존 user_id 필드와 같은 `plain`·`hashed` 규칙(위 user_id 행), 없으면 기록 안 함 + `argos_agent_user_id_skipped_total{reason="mdc_absent"}` | xlog 30일(user_id와 같음) | 삭제 API(user_id 기준) |
| 배치 트랜잭션 kind `BATCH`와 서비스 이름(`BATCH <class>#<method>`·`BATCH <group>.<name>`(Quartz job key)·`BATCH <job>.<step>`(Spring Batch), custom `as-transaction`의 `BATCH <Class#method>`, WO-18, 10 §6.8) | 내부(앱 코드·잡 설정 이름) | 수집 | 프록시 접미 제거(`$$` 앞 절단, C-237 (7)), 서비스 사전 상한(C-120), BATCH 서비스는 Apdex 없음(D-20, C-237 (8)) | xlog 30일, 사전 90일 미참조 정리 | 사전 관리자 삭제·삭제 API(tx 범위) |
| 앱 패키지 추정 `instrumentation.app-packages`(자가 진단 `extras.app_packages`·`app_packages_source`, 유효 설정, WO-18, C-237 (6)) | 내부(앱 패키지 이름) | 수집(자가 진단·유효 설정, gRPC 전송만) | `Start-Class`·`Main-Class`·명령줄 주 클래스에서 상위 2단계 패키지만, JDK·에이전트·런처 패키지 제외 | 자가 진단 결과(30일)·인스턴스 보관 정책 | 자동 만료·인스턴스 삭제 |
| 백업 세트 PostgreSQL 덤프 `pg.dump`(`.age`·`.gpg`)(`argos-compose.sh backup`·`backup.sh`, `ARGOS_BACKUP_DIR`, WO-43, 17 §4.4·§10, ADR-0010 C-247) | 개인정보 가능 + 비밀값(암호문): `argos` 스키마 전체 사본(감사 로그의 IP·사용자 이름·열람 대상, 사용자 이름·비밀번호 해시, 사전의 SQL 정규화문·URL, 봉투 암호화된 비밀값(채널·서명 키·명령 결과·솔트), 토큰 해시·접두) | 운영자가 백업을 실행할 때(문서화한 호스트 cron)만 | 세트 디렉터리 0700·파일 0600(T-SEC-84a), 호스트에 age(우선)·GPG가 있고 수신자를 주면 암호화하고 평문 덤프를 남기지 않음(T-SEC-84c). 비밀값은 마스터 키 봉투 그대로라 마스터 키 없이는 복호화 불가, 마스터 키는 세트에 넣지 않음(별도 보관). 스크립트 출력·로그에 비밀값 없음(T-SEC-73) | 기본 7일(`--keep-days`), CH 증분 체인이 기대는 세트는 더 남아 최대 약 2주(C-247 (6)) | 보존 정리·운영자 삭제. 개인정보 삭제 작업은 백업 사본에 미치지 않음(보존 기간 뒤 사라짐, 백업에 개인정보가 있음은 `docs/ops/backup-restore.md`에 명시) |
| 백업 세트 `manifest.json`(WO-43, C-247 (1)) | 내부(운영 메타데이터, 개인정보 없음) | 백업마다 | 서버 이미지 이름·id·다이제스트·버전, Flyway 버전, 마스터 키 kid·이전 kid(키 아님), 암호화 도구와 `encryption_recipient`(age 공개 수신자 또는 GPG 키 id), 파일 SHA-256·크기, CH 백업 이름·체인. 0600. restore가 docker 호출 전에 kid를 비교 | 세트와 같음 | 세트와 같음 |
| ClickHouse 백업(`backup.sh --clickhouse`, `BACKUP DATABASE argos TO Disk('backups', …)`, 외부 마운트 `ARGOS_CH_BACKUP_DIR`, WO-43, 13 §10, C-247 (3)(4)) | 개인정보 가능: 수집 텔레메트리 전체 사본(xlog·profile_step·통계·메트릭, user_id·IP는 수집 때 적용한 모드 그대로) | 운영자가 `--clickhouse`를 줄 때만(선택, 증분) | CH 데이터와 같은 디스크면 거부(T-NFR-20c), 백업 디스크는 `backups.allowed_disk`로 한정, 디렉터리 권한은 운영자 마운트(문서화). 스크립트 암호화 대상 아님(운영자가 대상 저장소에서 암호화) | 세트 보존과 같음(증분 체인 보호) | 보존 정리·운영자 삭제. 개인정보 삭제 뮤테이션은 백업에 미치지 않음 |
| Compose 진단 묶음 `argos-diag-compose-*.tar.gz`(`argos-compose.sh diag`·`diag-bundle.sh`, WO-43, 17 §4.4) | 내부(운영 정보, DB 로그에 SQL·사용자 이름이 남을 수 있음) | 운영자가 명령을 실행할 때만 | 0600(T-SEC-84d). 서버 진단 번들(위 행, 마스킹) + compose 상태·이미지·compose 파일 이름·SHA-256, 비밀 키 값을 `****`로 바꾼 `.env`, ClickHouse·PostgreSQL 컨테이너 로그 마지막 1000줄(같은 마스킹), 디스크 사용량. `secrets/`·`tls/` 내용·`docker compose config`·서버 컨테이너 로그(설정 토큰)는 넣지 않음, 심은 비밀값 grep 0(T-SEC-06i) | 운영자가 지울 때까지 | 운영자 삭제 |

## 6. 데이터 흐름

### 6.1 수집

```
앱 요청 ─▶ Advice(요청 경계·제외 규칙) ─▶ Transaction 빌더(step 트리, 상한) ─▶ 종료 시 샘플링 결정
   ├─▶ XLog 레코드 ─▶ 트랜잭션 큐(시간 기반 120초분, 메모리 상한)
   ├─▶ 프로파일(에러·느림 100% + 정상 N%) ─▶ 프로파일 큐
   └─▶ 전수 통계 집계기(sql_id·db_id / host_id, 10초 창) ─▶ 통계 큐
메트릭 수집기(5초) ─▶ 메트릭 큐          액티브 스냅샷(5초) ─▶ 액티브 큐
송신 스레드(argos-sender) ─▶ 배치(100ms/500건) ─▶ unary RPC(in-flight ≤ 4, deadline 5초) ─▶ ack(last_seq)
                                                      실패·상한 ─▶ 드롭 + 카운터(D-06)
서버 collector ─▶ 상한·쿼터·토큰 검사 ─▶ WAL append(그룹 fsync) ─▶ ack
   ─▶ 디코드·사전 해석·타임스탬프 가드·에러 그룹 지문·Apdex T 기록
   ├─▶ 실시간 버스(60초 이상 늦은 행 제외) ─▶ WebSocket 구독자(백프레셔: 최신 우선 드롭)
   └─▶ 배치 라이터(테이블별, 2초/50,000행, dedup 토큰 = WAL 위치) ─▶ ClickHouse ─▶ MV 1m ─▶ MV 1h
야간 ops ─▶ 전날 D-03·D-12 정합 검사 ─▶ 불일치 날 재구축(같은 SELECT) ─▶ 수집 상태 패널
```

### 6.2 제어

```
에이전트 ─ Session 스트림 ─▶ AgentHello(버전·JVM·호스트·app·env·설정 해시·remote-config.allow·IP 모드)
서버 ─▶ ServerHello(app_id·instance_id·시계 오프셋·적용 설정) 또는 거부(버전·토큰·미등록·상한)
heartbeat 30초 ↔ 시계 재측정, 상태 모델 UP/STALE/DOWN
서버 ─▶ ConfigPush(키·값·change_id) ─▶ 에이전트: allow 검사 ─▶ 적용 또는 거부 ─▶ ConfigApplyResult(rejected_keys)
서버 ─▶ Command(id UUID, type, TTL) ─▶ 에이전트: 권한은 서버가, 마스킹·쿨다운은 에이전트가 ─▶ CommandResult(암호화 저장)
에이전트 ─▶ InstanceUpdate(포트 확정·가상 스레드 감지·버전 변경), AgentBye(정상 종료)
```

### 6.3 조회

```
UI(URL 상태) ─▶ REST /api/v1 (쿠키 세션, CSRF, 역할) ─▶ 소스 선택(원본/1m/1h, 마지막 2분 원본)
   ─▶ ClickHouse(argos_reader, 쿼리 상한) + PostgreSQL(사전·메타) ─▶ 응답(source 표기, 제한·샘플링 표기)
UI ─▶ WebSocket /ws(핸드셰이크 쿠키·Origin) ─▶ 구독 {xlog|counter|active, app, env} ─▶ 100ms 묶음 푸시
```

## 7. 횡단 규칙

| 영역 | 규칙 |
|---|---|
| 시간 | 저장 UTC, 표시 브라우저 로컬. 응답시간은 `nanoTime` 차분(µs → ms). 타임스탬프는 에이전트 시계 + 서버 측 오프셋 기록. 가드 `now+5s`/`now-7d` |
| 식별자 | `tx_id` Snowflake형(`ts = tx_id >> 23`), `trace_id` 16B, 사전 키 xxh3_64, 리소스 id UUIDv4, 앱은 (name, env) |
| step 타입 열거 | `SQL=1, DBC=2, COMMIT=3, ROLLBACK=4, HTTP=5, METHOD=6, ASYNC=7, ERROR=8, STACK=9, MESSAGE=10, SUMMARY=11`. proto·ClickHouse `Enum8`·에이전트 상수가 같은 번호를 쓴다 |
| 결측 표현 | proto는 `optional` 필드 부재, ClickHouse 저장은 센티널 `-1`(정수)·`NULL`(문자열), REST 응답은 `null` + `measured: false`. 0과 결측을 섞지 않는다(D-23) |
| 수집 중단 구간 | 필드명은 `outages[]`(시작·끝·사유). `gaps`는 쓰지 않는다 |
| 응답 봉투 | `source(raw|1m|1h|mixed)`, `truncated`, `shown/total`, `sampling`, `retention_exceeded`, `outages`. snake_case |
| 포트 | 8080 UI/API, 9100 gRPC(둘만 외부 노출), 8081 관리(`management.server.port`, 127.0.0.1 바인딩, 베어러 인증) |
| 테스트 ID | 18 문서의 `T-<영역>-nnn` 체계가 유일. 다른 문서의 보조 ID(예: `ST-xx`)는 별칭 |
| 상한 | 모든 큐·캐시·사전·문자열·배치·스택에 상한. 초과는 거부 또는 드롭 + 카운터. 상한값은 설정 레퍼런스에 표로 |
| 설정 | 에이전트 `argos-agent.yml` → `-Dargos.*` → `ARGOS_*` 순 오버라이드, 서버 Spring `argos.*`. 알 수 없는 키는 WARN. 유효 설정과 출처를 조회 가능 |
| 메트릭 이름 | OpenMetrics: `jvm_memory_used_bytes{area="heap"}`, `jvm_gc_pause_seconds_total{gc="g1"}`(pause 계열은 `{gc}`만, 10 §11·ADR-0010 C-229 (4)), `tomcat_threads_busy{connector="http-nio-8080"}`, `jdbc_pool_active{pool="HikariPool-1"}`, `argos_agent_dropped_total{queue}`, `argos_ingest_lag_seconds` |
| 로그 | 에이전트: 자체 로거, 파일 롤링(10MB×5), stdout 0, INFO 이하 PII 없음. 서버: 구조화 JSON, 요청 id, 비밀값 마스킹 |
| 에러 응답 | 4xx 표준 본문(code, message, details), 5xx + 요청 id, 스택·경로·SQL 미노출 |
| 권한 | 관리자/운영자/조회자, OpenAPI `x-argos-role`, 객체 수준은 명령 결과(요청자·관리자) |
| 공개 계약 | 설정 키, API 스키마(추가만), WS 프레임, 메트릭 이름, 웹훅 페이로드(schema_version), CLI, 디렉터리, MDC 키(`argos.trace_id`, `argos.tx_id`), `tracestate` 키 `argos`. 변경은 폐기 절차(O-R03) |

## 8. 배포 토폴로지

| 규모 | 구성 | 대상 |
|---|---|---|
| S (1.0 기본) | VM 1대(8코어/16GB/500GB SSD): argos-server(all, 관리 포트 8081은 127.0.0.1) + ClickHouse(7.5GiB) + PostgreSQL(1.5GiB). 메모리 예산표는 17 문서 | 인스턴스 ~50, ~500 TPS 지속(5,000 TPS는 1시간 상한. 2,000 TPS 지속은 보존 단축 또는 1TB) |
| demo | Compose `--profile demo`: 위 + 샘플 앱(에이전트 부착) + 부하 생성기. 60초 내 XLog | 평가 |
| M (1.x) | collector × 2 + web × 1 + ClickHouse 복제 2 + Keeper + PostgreSQL | 인스턴스 ~300 |
| L (1.x) | ClickHouse 샤드 클러스터 | 그 이상 |

설치 방식: Docker Compose(amd64·arm64), `tar.gz` + systemd, 에어갭 번들(이미지·체크섬·GPG 서명·공개키 지문), Helm(1.x). 설치 시 자격증명 랜덤 생성, DB 포트 비노출, 설정 토큰 부트스트랩. 디렉터리 `/opt/argos/{bin,conf,lib,logs,data}`.

## 9. 테스트 전략 개요

| 계층 | 내용 | 실행 |
|---|---|---|
| 단위 | Advice 로직, SQL 정규화(50케이스, PII 시드), 큐·상한, 설정 파서, 컨텍스트 전파, 마스킹 패턴, 지문 | 매 커밋 |
| 변환 검증 | ASM `CheckClassAdapter`, 변환 클래스 목록 베이스라인 diff, `onError`·`ClassCircularityError` 0, `-XX:+BytecodeVerificationLocal`, ArchUnit Advice 규칙, Unsafe 참조 검사 | 매 커밋 |
| 통합(경량) | Testcontainers ClickHouse·PostgreSQL 적재·조회·정합·마이그레이션, 가짜 gRPC 서버/에이전트 | 매 커밋 |
| 매트릭스 | 유효 조합표(아래) × 가상 스레드 on/off, JDK 25는 `--sun-misc-unsafe-memory-access=deny`, JDBC 9종(Testcontainers + 사내 상용 DB), 독립 Tomcat WAR 재배포 50회, 공존(JaCoCo·OTel·devtools) | nightly(신뢰 브랜치, 사내 러너) |
| 성능 | 01 문서 7.3 프로토콜(계측 on/off, no-op 익스포터, 컨테이너, 가상 스레드), 서버 5,000 TPS 1시간, 저장소 리플레이 | 주 1회 + 릴리스 전 |
| 장시간 | 72시간: 힙 기울기, NMT, FD·스레드, parts | 릴리스 전 |
| 카오스 | 서버 중단 1시간·지연·손실·5xx·디스크 풀·DNS·인증서 불일치·instance_id 재부여·ClickHouse 30분 중단 | 릴리스 전 |
| 정확성 | D-01~D-26 각각 자동 테스트, 야간 정합 검사 | 매 커밋 + 운영 |
| 보안 | `securityTest` 스위트(권한 매트릭스·상한·레이트 리밋·SSRF·XSS 시드·토큰·하드 캡·PII 시드), SBOM 스캔, gitleaks, 인증 상태 ZAP | 매 커밋 / 릴리스 전 |
| E2E | Playwright 화면별 핵심 시나리오, 05 문서 2장 필수 요소, XSS 전역 훅, CSP 위반 0 | 매 커밋 |

유효 조합표(샘플 앱 × JDK)

| 샘플 앱 | JDK 8 | JDK 11 | JDK 17 | JDK 21 | JDK 25 | 비고 |
|---|---|---|---|---|---|---|
| shop-boot2 (Boot 2.7, javax, Tomcat 9) | ○ | ○ | ○ | ○ | △ 미검증 표기 | |
| shop-boot3 (Boot 3.x, jakarta, Tomcat 10.1) | – | – | ○ | ○ | ○ | 3.2+ 가상 스레드 on/off |
| shop-boot4 (Boot 4.x, jakarta, Tomcat 11) | – | – | ○ | ○ | ○ | 가상 스레드 on/off |
| shop-heavy (boot3 + 의존성 200 jar, 클래스 3만) | – | – | ○ | ○ | ○ | 기동 지연 측정 |
| 독립 Tomcat 9 + WAR(javax) | ○ | ○ | ○ | ○ | – | 재배포 누수 |
| 독립 Tomcat 10.1/11 + WAR(jakarta) | – | – | ○ | ○ | ○ | |
| shop-batch (Spring Batch·@Scheduled·Quartz) | – | – | ○ | ○ | ○ | kind=BATCH |

## 10. OpenTelemetry Java Agent 참고 지침

Apache-2.0이므로 참고·차용할 수 있으나 원 저작권 헤더·`NOTICE` 유지가 조건이다(O-L05).

| 가져와도 되는 것 | Argos 적용 |
|---|---|
| `AgentClassLoader`, 단일 jar `.classdata`, `ExposeAgentBootstrapListener`(read edge) | DSN-02-02·03 |
| `HelperInjector`, `VirtualField`(필드 주입 + 약참조 폴백), `CallDepth` | 10 문서 계측 프레임워크 |
| `GlobalIgnoredTypesConfigurer` 제외 목록, `ExecutorMatchers` 허용목록 | `AgentBuilder.ignore()`, DSN-02-05 |
| 서블릿 디스패치 요청 속성 앵커 | DSN-02-04 |
| `SqlStatementSanitizer`(+ `IN` 축약 추가), `JdbcConnectionUrlParser`(+ Tibero·Altibase·CUBRID) | D-22 |
| HTTP 클라이언트별 계측 함정 처리 | httpclient-* 모듈 |
| "실제 앱 기동 → 요청 → 단언" 테스트 패턴 | agent-testing |

가져오면 안 되는 것: `Instrumenter`/`Extractor` 추상화와 `InstrumentationModule` SPI(모듈 12개에 과잉), muzzle(빌드 복잡도 — 대신 SkyWalking식 witness 검사), OTel SDK·`Context`·semantic conventions 의존(서비스 이름 규약만 채택), `indy` Advice 모드, Groovy/Spock 테스트. 참고 가능 프로젝트: OpenTelemetry Java, Pinpoint, Scouter, SkyWalking, Elastic APM Java, dd-trace-java(모두 Apache-2.0). 피할 것: Byteman(LGPL), JaCoCo(EPL) 포함, Jennifer(비공개, 화면 개념만).

## 11. Phase 0 PoC 검증 항목

| # | 검증 | 통과 기준 | 결과 기록 |
|---|---|---|---|
| P-1 | 단일 jar bootstrap·격리 CL·read edge 구조로 Servlet·Spring MVC·JDBC 계측 | JDK 8/17/21 × Boot 2.7/3.x 기동·수집, 임시 파일 0, 변환 목록 베이스라인 | `docs/poc/agent-poc.md` |
| P-2 | 오버헤드(01 문서 7.3 프로토콜) | no-op 익스포터 상대 CPU ≤ 5%, p99 ≤ max(1ms, 5%), 기동 +2초(heavy 포함), `--cpus=2` 병기 | `docs/poc/agent-overhead.md` |
| P-3 | 전송 스택 비교: grpc-netty(NIO·JDK TLS) vs grpc-okhttp vs 자체 HTTP/1.1+protobuf | jar 크기·클래스 수·메타스페이스·기동·직접 메모리·스레드·8u252 TLS·JDK 25 deny 표. 채택 기준 jar ≤ 15MB, 기동 ≤ 2초, 스레드 ≤ 6, 직접 메모리 ≤ 8MB, Unsafe 경고 0 | `docs/poc/agent-poc.md` |
| P-4 | ClickHouse 목표 규모 벌크 + 리플레이, 쿼리·검증 20종 | Q1~Q11 기준, 압축률 xlog ≥ 3·profile ≥ 5·metric ≥ 8, Q12·13·15·16 정합 0, Q17 동시 부하, Q18~20 기록. 백분위 함수·코덱·bloom·sql_ids 결정 | `docs/poc/clickhouse-benchmark.md` |
| P-5 | XLog 캔버스 10만 점 | 16ms/프레임, 드래그 선택·요약 | `docs/poc/xlog-ui.md` |
| P-6 | JDK 클래스 계측 + JPMS | JDK 17·21·25에서 `HttpURLConnection`·`ThreadPoolExecutor`·`CompletableFuture` 계측 `IllegalAccessError` 0 | `docs/poc/agent-poc.md` |

## 12. 아키텍처 미결 사항

| # | 질문 | 추천 | 확정 시점 |
|---|---|---|---|
| A-1 | 전송 스택 | gRPC over grpc-netty(NIO·JDK TLS·Unsafe off), Netty 4.1 | 확정(ADR-0005, ADR-0009 C-72·C-73, provisional) |
| A-2 | 역할 분리 시 내부 버스 | gRPC fan-out(의존성 추가 없음) | 1.x 착수 |
| A-3 | 프로파일 step을 메모리에 기록 후 종료 시 결정하는 방식의 메모리 비용 | step 상한 1,000 + detail 지연 생성 + 큐 시간 기반(120초분) | Phase 0 (P-2) |
| A-4 | SQL 원문 보관 | PostgreSQL `dictionary`(64KB 상한, 90일 미참조 정리) | 확정 |
| A-5 | XLog 10만 점 초과 샘플링 | `error OR slow OR cityHash64(tx_id) % N = 0` 결정적 샘플 | 확정 |
| A-6 | 트랜잭션 상세 조회의 ts | `tx_id`에서 복원, URL ts는 힌트 | 확정(DSN-02-09) |
| A-7 | 멀티테넌시 예약 | 컬럼 대신 ClickHouse DB 이름·테이블 접두를 설정값으로 | 확정 |
| A-8 | 환경 태그 | (name, env) → app_id, env는 모든 테이블 LowCardinality 컬럼 | 확정(DSN-02-14) |
| A-9 | 1.0부터 ReplicatedMergeTree + 내장 Keeper 여부 | 1.0은 MergeTree. 전환 절차(`convert_to_replicated`)는 17 문서 런북에 | 확정(ADR-0006 결정 2, provisional) |
| A-10 | `xlog.sql_ids` 배열(SQL 포함 검색·관련 트랜잭션 30일) 채택 여부 | 채택(최대 64개) | 확정(ADR-0006 결정 3, provisional) |
| A-11 | 백분위 함수(tDigest / BFloat16 / DD) | 트랜잭션 롤업 `quantilesDD(0.01, 0.5, 0.95, 0.99)`. 코덱(ZSTD(1))·bloom(fpp 0.01, G4, `idx_user` G1)도 함께 확정 | 확정(ADR-0007 C-53~C-56, provisional) |
| A-12 | 에이전트 큐 크기 단위 | 건수·시간(120초분)·바이트 삼중 상한(DSN-10-09), 디스크 스풀은 1.x | 확정 |
| A-13 | 1초 카운터(Jennifer 수준 실시간) | 1.0은 5초. 1.x에서 에이전트 부하 측정 후 | 1.x |
| A-14 | `protobuf-javalite` 채택(에이전트 jar 크기) | 불채택. 에이전트도 protobuf-java(JDK 24+ `Unsafe` 경고·deny 실패) | 확정(ADR-0005, ADR-0009 C-72, provisional) |
| A-15 | 알림 평가용 백분위(HdrHistogram 메모리 윈도)와 롤업 백분위(A-11)의 값 차이 허용 | 허용. "알림 p95는 메모리 윈도 기준"을 문서에 명시 | 확정 |

## 13. 설계 문서 간 정합 결정 (PM, 2026-10-23)

10~19 문서는 병렬로 작성되어 세부가 어긋난 곳이 있다. 아래 결정이 우선하며, 각 문서는 다음 개정에서 이에 맞춘다. 구현자는 문서가 다르면 이 표를 따른다.

| # | 어긋남 | 결정 | 반영할 문서 |
|---|---|---|---|
| C-1 | step 타입 번호(10: DBC=2…, 11: DBC=8 추가) | §7 표의 열거(`SQL=1 … SUMMARY=11`) | 11 proto enum, 13 `Enum8` |
| C-2 | 중복 제거 토큰(12: WAL 위치, 13: 블록 매니페스트 `block_id`) | DSN-02-12: WAL INTENT/COMMIT 레코드가 블록 경계, 토큰 `argos:{table}:{block_id}`(block_id = COMMIT 레코드 위치) | 12·13 문구 통일 |
| C-3 | 중복 제거 키(02 초안: instance+seq, 11: +agent_epoch+종류) | DSN-02-11: `(instance_id, agent_epoch, 종류, seq)` | 12 윈도 키 |
| C-4 | 결측 표현(10: -1, 11·18: optional 부재, 13: 센티널, 14: null) | §7 "결측 표현" 행 | 10 레코드 빌더(proto optional 부재로 송신) |
| C-5 | 수집 중단 필드명(14: `outages`, 15·18: `gaps`) | `outages` | 15, 18 |
| C-6 | 응답 봉투 필드(13: `rawRetentionExceeded`) | `retention_exceeded`(14 기준) | 13 |
| C-7 | 관리 포트(12: 9101, 17: 8081) | 8081, 127.0.0.1, 베어러 인증 | 12 |
| C-8 | 인스턴스 상태 수치(01 초안: STALE 30초, 12: 60초/180초) | STALE = heartbeat 60초 미수신(2회 누락), DOWN = 180초 또는 Bye 후 5분(DSN-12-08). 01 F-S28 갱신 완료 | 14·15 표시 문구 |
| C-9 | `readOnly=false` 금지(02 초안) vs 허용 목록(10) | DSN-02-01 완화 반영 완료 | — |
| C-10 | 테스트 ID(16: `ST-xx`, 18: `T-SEC-xx`) | 18의 `T-` 체계. 16의 `ST-xx`는 별칭 | 16 대응표 주석 |
| C-11 | DB 계정(13: CH 3종, 16: 6종) | CH `argos_writer/reader/migrator` + PG `argos_app/argos_audit/argos_backup`(마이그레이션은 PG 소유자 계정) = 6종 | 13 PG 절 |
| C-12 | `POST /events` 인증(14: 에이전트 토큰 Bearer 허용 여부를 16에 질의) | 허용. 앱 범위 에이전트 토큰으로 배포 마커만 생성 가능, 감사 기록. 전용 이벤트 토큰은 1.x | 16 권한 매트릭스 |
| C-13 | 바인드 파라미터 `full` 권한 없는 요청(03 vs 강등) | `masked_by_role` 강등 반환(14 제안 채택). 분류표 갱신 완료 | 15·16 |
| C-14 | SQL·외부 호출 백분위(13: 15구간 버킷 추정) vs 트랜잭션 백분위(tDigest 등, A-11) | 이원화 채택. UI는 버킷 추정값에 `estimated` 표기 | 14·15 |
| C-15 | 메트릭 counter 리셋 보정 주체(13: 에이전트가 `delta` 계산) | 에이전트가 `kind`·`delta`를 실어 보낸다(DSN-13-04) | 10·11 |
| C-16 | 사전 kind 추가(10: FRAME·METRIC·ERROR_GROUP, 11: DB_TARGET·APP_VERSION) | 전부 채택: `SERVICE, SQL, CLASS, METHOD, THREAD, HOST, FRAME, METRIC, ERROR_GROUP, DB_TARGET, APP_VERSION` | 11 enum, 13 PG enum |
| C-17 | xlog 추가 컬럼 요구(11·12: dbc_wait_ms, request_id, dispatch_count, streaming, timed_out, incoming_trace, app_version_id, late, flags) | 채택. 13 DDL에 추가 | 13 |
| C-18 | 샘플 앱 세트(18: shop-heavy·shop-batch·ext-stub 추가) | 채택. 04 WO-01 범위 확대 | 04 |
| C-19 | 세션 스트림 idle 타임아웃(11: heartbeat 3회 90초) | 채택. 상태 모델과 별개(연결 종료 후 STALE/DOWN은 12 규칙) | 12 |
| C-20 | 알림 백분위 소스(12: HdrHistogram 메모리 윈도) | A-15로 확정 | 14 응답 표기 |
