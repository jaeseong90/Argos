# Argos 테스트 설계

| 항목 | 내용 |
|---|---|
| 문서 번호 | 18 |
| 문서 버전 | 0.1 (초안) |
| 작성일 | 2026-10-23 |
| 상태 | 설계 검토 중 |
| 만족시키는 요구 | D-01~D-26, N-01~N-30, SEC 자동 검증 항목 전부, 03 문서 2·3·4장 전부, S-01~S-18(수락), O-Q02·O-Q03·O-S05, P-1~P-6 |
| 의존 문서 | 01 제품 기획 v0.3, 02 아키텍처 v0.2, 03 완성 기준 v0.2, 05 벤치마크 v0.2, 06 보안 요구 v0.1, 10 에이전트, 11 프로토콜, 12 서버, 13 저장소, 15 UI, 19 저장소·빌드·릴리스 |
| 이 문서에 의존하는 문서 | 04 작업 지시서(WO 수락 기준의 테스트 ID), 19 저장소·빌드·릴리스(태스크·러너·아티팩트), 03 완성 기준(증빙 방법) |

## 1. 목적과 범위

이 문서는 03 문서의 모든 완성 기준이 **어떤 테스트가 어떻게 증명하는지**를 정한다. Claude Code가 WO를 받았을 때 테스트를 새로 발명하지 않고, 여기 정의된 하네스·픽스처·ID 규약 위에서 테스트를 추가하기만 하면 되는 수준을 목표로 한다. 외부 기여 PR도 같은 하네스로 검증된다(O-Q02).

범위: 테스트 계층·주기·환경, ID 규약, 샘플 앱, 에이전트 하네스, 호환성 매트릭스, 오버헤드 벤치, 서버 부하 생성기, 저장소 PoC 하네스, 정확성·카오스·장시간·보안·E2E 테스트, 사람 수락 체크리스트, 테스트 데이터·플레이키·커버리지, 요구 ↔ 테스트 대응표.

범위 밖: 각 모듈 내부의 단위 테스트 케이스 목록(해당 설계 문서와 WO가 정한다), CI 워크플로 YAML(19 문서).

## 2. 테스트 계층 모델

### 2.1 계층과 실행 주기

| 계층 | 대상 | 도구 | Gradle/pnpm 태스크 | 실행 주기 | 환경 | 시간 예산 |
|---|---|---|---|---|---|---|
| 단위 | Advice 로직, SQL 정규화, 큐·상한, 설정 파서, 전파, 마스킹, 지문, 알림 상태 머신, 소스 선택 규칙 | JUnit 5, AssertJ, Vitest | `test`, `pnpm test` | 매 커밋(PR) | GitHub-hosted | ≤ 5분 |
| 변환 검증 | 변환 클래스 베이스라인, `onError`·`ClassCircularityError` 0, ASM 검증, Unsafe 참조, ArchUnit Advice 규칙, SPDX·셰이딩·SBOM 존재 | ByteBuddy Listener, ASM, ArchUnit, `check-shading.sh` | `verifyAgentJar`(agent-dist), `archTest` | 매 커밋 | GitHub-hosted | ≤ 4분 |
| 통합(경량) | 에이전트 ↔ 가짜 수집 서버, 샘플 앱 1종(shop-boot3 × JDK 17), 서버 ↔ Testcontainers ClickHouse·PostgreSQL, 마이그레이션 두 경로, 정합 D-03·D-12·D-13, 알림 상태 전이 | JUnit 5, Testcontainers, agent-testing 하네스 | `integrationTest` | 매 커밋 | GitHub-hosted | ≤ 12분 |
| 계약 | OpenAPI 스펙 린트·호환 diff·`x-argos-role` 누락, 권한 매트릭스 생성 테스트, WS 프레임 스키마, 웹훅 페이로드 스키마, 설정 키 스냅샷 | Spectral, openapi-diff, JUnit 5 | `contractTest` | 매 커밋 | GitHub-hosted | ≤ 3분 |
| 보안(빠른) | 토큰 수명주기, 세션·CSRF·WS, 하드 캡, 마스킹, 상한 5종, SSRF, 헤더·CSP, PII 시드 grep(1/1000 데이터) | JUnit 5, Testcontainers, Playwright 훅 | `securityTest` | 매 커밋 | GitHub-hosted | ≤ 8분 |
| E2E | 화면별 핵심 시나리오, 05 §2 필수 요소, XSS 전역 훅, CSP 0, 딥링크 5항, 실시간 재접속 | Playwright | `pnpm e2e` | 매 커밋(샤딩 4) | GitHub-hosted | ≤ 10분 |
| 매트릭스 | 유효 조합표 × 가상 스레드 on/off, JDK 25 deny, JDBC 9종, 독립 Tomcat 재배포 50회, 공존 | 컨테이너, agent-testing | `matrixTest` | nightly(main·release push, 수동 트리거), `needs-matrix` PR은 신뢰 브랜치로 가져와 실행 | **사내 self-hosted만** | ≤ 4시간 |
| 성능 | 7.3 오버헤드 프로토콜, 서버 5,000 TPS 1시간, 저장소 리플레이 | k6, 가짜 에이전트, jcmd, NMT | `benchTest`, `serverLoadTest`, `storageBench` | 주 1회 + 릴리스 전 + 핵심 경로 PR(수동) | 사내 self-hosted(부하기 분리) | ≤ 3시간(벤치) / ≤ 2시간(서버) / ≤ 6시간(저장소 전체 규모) |
| 장시간 | 72시간 soak | 하네스 + 수집 스크립트 | `soakTest` | 릴리스 전(Gate 1·2) | 사내 self-hosted | 72시간 |
| 카오스 | 서버 중단·지연·손실·5xx·디스크 풀·DNS·TLS·instance_id 재부여·ClickHouse 다운 | toxiproxy, docker | `chaosTest` | 릴리스 전 + 수집 경로 변경 PR(수동) | 사내 self-hosted | ≤ 3시간 |
| 보안(릴리스) | ZAP 인증 활성 스캔 + API 스캔, SBOM 전체 재스캔, 침투 체크리스트(사람) | ZAP, Trivy/Grype | `zapScan`(스크립트) | 릴리스 전(Gate 1.5·2) | 사내 self-hosted | ≤ 2시간 + 사람 |
| 수락 | S-01~S-18 체크리스트 | 사람 + 데모 프로파일 | - | WO REVIEW·Gate | 사람 | WO당 ≤ 15분 |
| 운영 중 | 야간 정합 검사(D-14), 자기 메트릭 알림 | 서버 ops 역할 | - | 매일 | 운영 서버 | - |

### 2.2 Gradle 태스크 분리 (DSN-18-02)

| 태스크 | 소스 세트 | 포함 | 제외 조건 |
|---|---|---|---|
| `test` | `src/test` | 단위 | 네트워크·컨테이너 금지. 1초 넘는 테스트는 `@Tag("slow")`로 `integrationTest`로 이동 |
| `integrationTest` | `src/integrationTest` | Testcontainers, 가짜 수집 서버, 샘플 앱 1종 | `@Tag("integration")` |
| `contractTest` | `src/contractTest`(server) | OpenAPI·스키마·권한 매트릭스 생성 | |
| `securityTest` | `src/securityTest`(server·agent-testing·ui e2e 태그 `@security`) | SEC 자동 항목 | 매 커밋은 1/1000 데이터, 릴리스 전은 전체 |
| `matrixTest` | `agent-testing/src/matrixTest` | `-Pmatrix.combo=<id>` 또는 전체 | self-hosted 라벨이 없으면 skip + 경고 |
| `benchTest`, `serverLoadTest`, `storageBench`, `soakTest`, `chaosTest` | `test/*` 별도 Gradle 서브프로젝트 | 스크립트 + Java 러너 | `-Pbench.machine-profile` 필수 |
| `verifyAgentJar` | agent-dist | 셰이딩·Unsafe·SPDX·SBOM·버전 매니페스트·major 52 | `shadowJar` 뒤 자동 |
| `archTest` | 각 모듈 | ArchUnit 규칙 | `check`에 포함 |

`./gradlew check`는 `test`, `archTest`, `contractTest`, `verifyAgentJar`를 포함한다. `integrationTest`·`securityTest`는 CI가 별도 잡으로 병렬 실행한다.

### 2.3 실행 환경과 격리 (O-S05)

| 환경 | 실행 대상 | 트리거 | 제약 |
|---|---|---|---|
| GitHub-hosted(ubuntu-latest, 4코어) | 매 커밋 계층 전부 | PR(포크 포함), push | Docker 가능. 사내 자원·비밀값 접근 없음 |
| 사내 self-hosted `argos-matrix`(16코어/64GB) | 매트릭스, 카오스, ZAP | main·release push, `workflow_dispatch` | 포크 PR 금지. `pull_request_target` 미사용 |
| 사내 self-hosted `argos-bench-app`(8코어/16GB, 격리) + `argos-bench-load`(부하기) | 오버헤드 벤치, soak | 주간 cron, 수동 | 두 머신 같은 L2, governor performance 고정 |
| 사내 self-hosted `argos-storage`(8코어/16GB/1TB NVMe) | 저장소 PoC 전체 규모, 서버 부하 | 수동 | ClickHouse 전용 |
| 사내 DB 호스트 | Oracle·Tibero·Altibase·CUBRID·DB2 | 매트릭스에서 환경변수로 연결 | 미설정 시 해당 조합 skip + "미검증" |

### 2.4 시간 예산 합계

PR 게이트 전체 ≤ 25분(병렬 잡 중 가장 긴 것 기준). nightly ≤ 4시간. 주간 성능 ≤ 6시간. 릴리스 전 게이트는 soak 72시간을 포함해 4일 안에 끝나도록 병렬 배치한다(19 문서).

## 3. 테스트 ID 규약 (DSN-18-01)

| 접두 | 영역 | 예 |
|---|---|---|
| `T-AG-nnn` | 에이전트 단위·통합(계측·코어·전송) | T-AG-031 JDBC 중첩 제거 |
| `T-SV-nnn` | 서버 단위·통합(수집·알림·명령·관리) | T-SV-010 WAL 선기록 후 ack |
| `T-ST-nnn` | 저장소(스키마·롤업·마이그레이션·PoC) | T-ST-014 백분위 값 오차 |
| `T-API-nnn` | API 계약 | T-API-003 소스 선택 규칙 |
| `T-UI-nnn` | UI 단위·컴포넌트 | T-UI-021 XLog 좌표 변환 |
| `T-E2E-nnn` | Playwright | T-E2E-040 딥링크 로그인 복귀 |
| `T-ACC-nn` | 정확성, D-nn과 번호 일치 | T-ACC-13 중복 0·누락 0 |
| `T-NFR-nn` | 비기능, N-nn과 번호 일치(N-10a~d는 T-NFR-10a~d) | T-NFR-01 상대 CPU |
| `T-SEC-nn` | 보안, SEC-nn과 번호 일치 | T-SEC-23 하드 캡 |
| `T-MX-nnn` | 매트릭스 조합 | T-MX-017 boot3×JDK21×VT |
| `T-CH-nnn` | 카오스 | T-CH-004 디스크 풀 |
| `T-SOAK-nnn` | 장시간 | T-SOAK-001 72h 힙 기울기 |
| `T-BLD-nnn` | 빌드·패키징 검증 | T-BLD-002 셰이딩 클래스 목록 |
| `T-ACPT-nn` | 사람 수락, S-nn과 번호 일치 | T-ACPT-07 에이전트 도입 |

규칙: 테스트 메서드·spec 이름에 ID를 접두한다(`@DisplayName("T-AG-031 …")`, `test('T-E2E-040 …')`). 요구 ID는 JUnit `@Tag("req:D-24")`, Playwright `test.info().annotations`로 붙인다. CI는 태그에서 요구 ↔ 테스트 대응표(16장)를 생성해 `build/reports/traceability.md`로 남기고, 16장과 diff가 나면 실패한다. 삭제된 ID는 재사용하지 않는다.

## 4. 샘플 앱 세트 (`sample-apps/`)

### 4.1 목록 (DSN-18-03)

| 앱 | 스택 | JDK | 용도 |
|---|---|---|---|
| `shop-boot2` | Spring Boot 2.7, Framework 5.3, javax Servlet 4.0, Tomcat 9, JPA(Hibernate 5), HikariCP, RestTemplate, Apache HC4, OkHttp 3 | 8(빌드), 8·11·17·21 실행 | 레거시 조합 |
| `shop-boot3` | Boot 3.x, Framework 6.x, jakarta 6, Tomcat 10.1, JPA(Hibernate 6), HikariCP, RestTemplate, WebClient(Reactor Netty), Apache HC5, OkHttp 4, Java 11 HttpClient | 17(빌드), 17·21·25 실행 | 기준 앱. 매 커밋 통합·벤치·E2E 데이터 |
| `shop-boot4` | Boot 4.x, Framework 7.x, jakarta 6.1, Tomcat 11 | 21(빌드), 17·21·25 실행 | 최신 조합 |
| `shop-heavy` | shop-boot3 + Spring Cloud·Hibernate 전 모듈·Jackson 모듈·QueryDSL 등 의존성 200 jar 이상, 클래스 3만 이상 | 17 | N-04·N-27 기동 비용 |
| `shop-batch` | Boot 3.x + `@Scheduled` 3종(고정·cron·오래 걸리는 작업) + Quartz Job 2종 + Spring Batch Job(Tasklet Step 1, Chunk Step 1, 청크 5,000) + 공유 DataSource | 17·21 | F-A24, D-20, S-09 |
| `shop-war-javax` | 같은 코드의 WAR, 독립 Tomcat 9 | 8·11·17·21 | 재배포 누수, 컨텍스트 경로 접두(F-A36) |
| `shop-war-jakarta` | WAR, 독립 Tomcat 10.1/11 | 17·21·25 | 동일. 멀티 웹앱(2개 배포) |
| `ext-stub` | 외부 호출 대상 스텁(HTTP): 정상·지연(ms 파라미터)·타임아웃(응답 없음)·연결 거부·5xx·리다이렉트 | 17 | 외부 호출 분류, SSRF 테스트 대상 |

세 shop 앱은 **같은 엔드포인트 카탈로그**를 구현한다(공유 모듈 `sample-apps/common`). 설정은 환경변수로: `SAMPLE_DB=h2|postgres|mysql|…`(Testcontainers JDBC URL 주입), `SAMPLE_VT=true`(Boot 3.2+ `spring.threads.virtual.enabled`), `SAMPLE_ROUTING_DS=true`(AbstractRoutingDataSource + TransactionAwareDataSourceProxy + 드라이버 래퍼 중첩), `SAMPLE_POOL=hikari|tomcat|dbcp2`.

### 4.2 공통 엔드포인트 카탈로그

| 경로 | 동작 | 검증 목적 |
|---|---|---|
| `GET /api/orders/{id}` | SQL 1건(PreparedStatement) + 응답 | 기본 트랜잭션, 서비스 이름 패턴 |
| `GET /api/orders?page=` | SQL 3건, fetch 100행 | sql_count, fetch_ms·rows |
| `GET /api/orders/{id}/items` | N+1(SQL 1 + 20) | 반복 step 접기, S-03 |
| `GET /api/pay/{id}` | ext-stub 호출 1회(클라이언트 종류는 `?client=hurl|hc4|hc5|okhttp|jdk11|webclient`) | HTTP step, 전파 헤더, F-A33 |
| `GET /api/pay/{id}?timeout=1` / `?refuse=1` / `?status=500` | 외부 호출 실패 3종 | TIMEOUT/CONNECT_FAIL/5xx 분류 |
| `GET /api/slow?ms=300` | `Thread.sleep` 또는 SQL `pg_sleep` | 느림 임계, 자동 스택 캡처 |
| `GET /api/error-500` | 미처리 `IllegalStateException("order 42 not found for kim@example.test")` | D-21, 에러 그룹, 메시지 마스킹 |
| `GET /api/send-error` | `response.sendError(503)` | D-21 |
| `GET /api/forward` | `RequestDispatcher.forward("/api/orders/1")` | D-04 FORWARD |
| `GET /api/not-found-x` | 404(핸들러 없음) | 폴백 서비스 이름, `/error` 미생성 |
| `GET /api/async/deferred` | `DeferredResult` 200ms | Servlet async 수명 |
| `GET /api/async/callable` | `Callable` | 동일 |
| `GET /api/async/executor` | `@Async` + `CompletableFuture.supplyAsync` + 병렬 스트림 + `ScheduledThreadPoolExecutor` | F-A07 전파, 허용목록, remove()·cancel() 보존 |
| `GET /api/sse` | SseEmitter 3이벤트 후 종료 | F-A34 첫 flush 종료 |
| `GET /ws/echo` | WebSocket 업그레이드 | F-A34 101 종료 |
| `GET /api/pool/exhaust?hold=5000` | 커넥션 maxPoolSize+1개를 5초 점유 | DBC 대기 step, S-10, 풀 메트릭 |
| `POST /api/tx/commit` / `rollback` | 트랜잭션 커밋·롤백 | COMMIT/ROLLBACK step |
| `GET /api/stmt?q=` | `Statement.executeQuery`(리터럴 인라인) | 정규화·PII 미전송(SEC-35) |
| `GET /api/batch-insert` | `addBatch`×50 + `executeBatch` | 배치 step |
| `GET /api/user?id=` + 헤더 `X-User-Id` | 세션 속성·헤더 user id | SEC-32 모드 |
| `GET /actuator/health`, `/favicon.ico`, `/static/app.js` | 노이즈 | F-A23 제외 |
| `OPTIONS /api/orders` | preflight | 제외 |
| `GET /api/threads/dump-me` | 현재 스레드 10초 블로킹 | 스레드 덤프·단일 스택 매칭 |
| `GET /api/mdc-log` | 로그 1줄 출력 | F-A20 MDC 주입 |
| `GET /api/version` | 버전 태그 노출 | F-A21 |
| `GET /seed/*` | 4.3 PII·XSS 시드 | 보안 |
| (shop-batch) `POST /batch/run/{job}` | Job 수동 트리거 | kind=BATCH |

### 4.3 PII·XSS 시드 엔드포인트 (DSN-18-09)

모든 시드 값은 **센티널 부분 문자열 `ARGOS_SEED`**를 포함해 저장소 전체를 한 번의 `LIKE '%ARGOS_SEED%'`로 훑을 수 있게 한다.

| 시드 | 값 | 심는 위치(엔드포인트) |
|---|---|---|
| 이메일 | `ARGOS_SEED.kim@example.test` | 쿼리스트링 `?email=`, 바인드 파라미터, Statement 리터럴, 예외 메시지, 헤더 `X-Contact` |
| 휴대전화 | `010-ARGOS_SEED-0001`(패턴 테스트용 별도 `010-9999-0001`도) | 쿼리스트링, 바인드, 예외 메시지 |
| 주민번호 형태 | `990101-1ARGOS_SEED` + 패턴용 `990101-1234567` | 바인드, Statement 리터럴 |
| 카드번호 형태 | `4111-1111-ARGOS_SEED` + `4111111111111111` | 바인드, 예외 메시지 |
| 사용자 식별자 | `user-ARGOS_SEED-42` | 헤더 `X-User-Id`, 세션 속성 |
| IP | 요청 소켓 + `X-Forwarded-For: 203.0.113.ARGOS_SEED`(스푸핑) | XFF 헤더 |
| 비밀값 | 환경변수 `DB_PASSWORD=ARGOS_SEED_pw`, `AWS_SECRET_ACCESS_KEY=ARGOS_SEEDAKIA…`, `ARGOS_TOKEN`, `JAVA_TOOL_OPTIONS=-Dsecret=ARGOS_SEED` | 앱 프로세스 환경(환경 덤프 테스트) |
| 토큰 형태 | `eyJhbGciOi.ARGOS_SEED.sig`, `AKIAARGOSSEED0000001` | 환경변수 값, 헤더 |
| 한글·이스케이프 리터럴 | `'홍길동ARGOS_SEED'`, `'O''Brien ARGOS_SEED'`, `$$ARGOS_SEED$$` | Statement 리터럴(정규화 PII 케이스) |
| XSS | `<img src=x onerror="window.__xss=1">`, `"><svg/onload=window.__xss=1>`, `javascript:window.__xss=1`, `<script>window.__xss=1</script>` | 서비스 경로 세그먼트(`/seed/xss/<payload>`), 쿼리스트링(whitelist 모드), 헤더 값, user id, SQL 리터럴·바인드, 예외 메시지, 스레드 이름(커스텀 실행기), 외부 호출 경로(ext-stub), 배치 작업명 |
| CSV 수식 | `=HYPERLINK("http://x","ARGOS_SEED")`, `+cmd|' /C calc'!A0`, `-1+1`, `@SUM(1)` | 서비스 이름, SQL 리터럴, user id |

시드 트래픽 생성기 `test/seed/seed-traffic.sh`가 모든 시드 엔드포인트를 N회 호출한다. 보안 테스트는 이후 ClickHouse·PostgreSQL·에이전트 로그·서버 로그·알림 Mock 수신함·CSV 출력에서 `ARGOS_SEED` 원문이 0건임을 단언한다(단, `masked`·`off` 기본 설정에서. `full`·`plain`을 켠 테스트는 반대로 존재를 단언).

### 4.4 구성과 패키징

- 각 앱은 fat jar와 Dockerfile(`eclipse-temurin:<jdk>-jre`)을 가진다. 매트릭스는 `JDK_IMAGE` 빌드 인자로 JDK를 바꾼다.
- `SAMPLE_VT=true`면 Boot 3.2+에서 가상 스레드 실행기를 켜고, 앱이 `/api/runtime`에 `virtualThreads: true`를 노출한다.
- `SAMPLE_ROUTING_DS=true`면 `AbstractRoutingDataSource → TransactionAwareDataSourceProxy → HikariDataSource → 드라이버`의 4단 래퍼. JDBC 중첩 제거(D-24)의 핵심 픽스처.
- `SAMPLE_CUSTOM_EXECUTOR=true`면 `ThreadPoolExecutor` 서브클래스에 스레드 이름 `seed-ARGOS_SEED-%d`를 붙여 스레드 이름 시드를 만든다.
- 모든 앱은 `-Dsample.fixed-clock=2026-01-15T00:00:00Z`로 비즈니스 시각을 고정할 수 있다(E2E 데이터 결정성). 에이전트 타임스탬프는 고정하지 않는다(D-02 테스트는 실제 시계 오프셋 주입으로).
- 라이선스: 샘플 앱 의존성(MySQL Connector/J 등)은 배포물이 아니므로 허용 목록 밖이어도 `docs/licenses/dev-only.md`에 기록한다(O-L03).

## 5. 에이전트 테스트 하네스 (`agent-testing`)

### 5.1 모듈 구조

```
agent/agent-testing/
  src/main/java/io/argos/agent/testing/
    launcher/   AppLauncher, RunningApp, Jdk, SampleApp, JvmOptions
    collector/  FakeCollector(gRPC AgentService 구현), FaultInjector, Collected
    export/     FileExporterReader(JSONL), NoopExporter 설정 헬퍼
    assertion/  CollectedAssert, TransactionAssert, StepAssert, MetricAssert
    baseline/   TransformBaseline(record·diff)
    leak/       LeakProbe(jcmd 히스토그램 파서, Tomcat 재배포 드라이버)
    log/        AgentLogAssert(허용목록 fixture, stdout 캡처)
    jvm/        JcmdClient, NmtParser, ThreadCounter, UnsafeScanner(javap)
  src/integrationTest/   매 커밋 통합(T-AG-*)
  src/matrixTest/        매트릭스(T-MX-*)
  baselines/transformed-classes/<app>-<jdk>[-vt].txt
  fixtures/log-allowlist/agent-info.txt
```

### 5.2 AppLauncher (DSN-18-04)

```java
public interface AppLauncher {
  static AppLauncher of(SampleApp app, Jdk jdk);          // SampleApp.SHOP_BOOT3, Jdk.J21
  AppLauncher withAgent(Path agentJar, Map<String, String> argosConfig);  // argos-agent.yml 생성
  AppLauncher withoutAgent();                             // off 기준선
  AppLauncher withJvmOptions(String... opts);             // -XX:+BytecodeVerificationLocal 등
  AppLauncher withEnv(String key, String value);
  AppLauncher virtualThreads(boolean on);
  AppLauncher collector(FakeCollector collector);         // null이면 FileExporter
  AppLauncher container(boolean inDocker, String cpus, String memory);
  RunningApp start(Duration readyTimeout);                // "Started" 로그 + 포트 바인딩 대기
}

public interface RunningApp extends AutoCloseable {
  int port(); long pid(); Path workDir(); Path agentLogDir();
  HttpResult call(String method, String path, Map<String, String> headers);
  void load(int rps, Duration d, String... paths);        // 소형 부하(통합용)
  Collected collected();                                  // FakeCollector 또는 JSONL
  JcmdClient jcmd();                                      // GC.run, GC.class_histogram, Thread.print, VM.native_memory
  SelfDiagnostics selfDiagnostics();                      // 에이전트 자가 진단 명령 결과(10 문서 요구)
  Duration startupTime();                                 // JVM 시작 → 포트 바인딩
  void kill9(); void gracefulStop();
}
```

구현: `ProcessBuilder`로 `java -javaagent:… -jar …`. Docker 모드는 Testcontainers `GenericContainer`로 같은 인터페이스. JDK는 `~/.argos-test/jdks/<id>` 또는 컨테이너 이미지 `ghcr.io/<org>/argos-ci-jdk:<ver>`.

### 5.3 FakeCollector

- gRPC `AgentService` 전체를 구현하고 모든 배치를 `(instance, seq, 수신 시각, 바디)`로 기록한다. 11 문서의 proto 스텁 아티팩트에 의존한다.
- `FaultInjector`: `latency(Duration)`, `failNext(int n, Status s)`, `hang()`(응답 안 함), `rejectAuth()`, `closeStream()`, `serverRestart(newInstanceId)`, `tlsCertificate(wrongHostCert)`, `plaintextOnly()`.
- `ConfigPush(key, value)`·`Command(type)`를 보내고 `ConfigApplyResult`·`CommandResult`를 받는 API.
- 수신 상한 검사·쿼터는 **하지 않는다**(서버의 책임). 에이전트가 보낸 그대로를 기록해 "보낸 수"의 기준값이 된다.

### 5.4 익스포터 SPI와 no-op

에이전트는 테스트 전용 익스포터를 바꿔 끼울 수 있어야 한다(10 문서 요구): `-Dargos.test.exporter=file:<dir>` (JSONL), `noop`(인코딩까지 하고 버림), 기본 `grpc`. 릴리스 빌드에서는 `argos.test.*` 프로퍼티가 무시된다. 오버헤드 벤치는 `noop`(계측 비용)과 `grpc`(전송 비용)를 분리 측정한다(P-2, Gate 0).

### 5.5 단언 DSL

```java
assertThat(app.collected())
    .transactions().hasSize(1)
    .first()
      .hasKind(HTTP).hasService("GET /api/orders/{id}").hasStatus(500).isError()
      .hasEnv("test").hasVersion("1.2.3")
      .cpuTimeMs().isMissing()               // 가상 스레드: 0이 아니라 결측(D-23)
      .steps().ofType(SQL).hasSize(1)        // D-24
      .steps().ofType(DBC).first().elapsedMs().isGreaterThan(0)
      .noStepFromThread(startsWith("argos-"));  // 재진입(F-A15)
assertThat(app.collected()).stats().sql().count("SELECT … WHERE id = ?").isEqualTo(1000);  // D-12
assertThat(app.collected()).metrics().gauge("jdbc_pool_active", "pool", "HikariPool-1").exists();
assertThat(app.collected()).dictionary().serviceCount().isLessThanOrEqualTo(10_000);
```

결측은 proto의 optional 필드 부재로 표현되며 DSL은 `isMissing()`과 `isZero()`를 구분한다.

### 5.6 변환 클래스 베이스라인 (DSN-18-05)

- 에이전트 디버그 플래그 `-Dargos.debug.transform-log=<file>`(10 문서 요구)로 변환된 `(클래스로더 종류, 클래스명, 모듈)` 목록을 기록한다.
- `TransformBaseline.record(app, jdk, vt)`가 `baselines/transformed-classes/<app>-<jdk>[-vt].txt`에 정렬 저장. `diff()`는 **추가된 클래스가 있으면 실패**, 삭제는 경고. 베이스라인 갱신은 PR에서 파일을 바꾸고 리뷰어가 승인한다(허용목록 밖 Executor 매칭, 생성 클래스 매칭을 조기 발견).
- 같은 로그에서 `onError`·`ClassCircularityError` 카운트 0을 단언한다(T-AG-005).

### 5.7 fail-safe 주입

에이전트 테스트 훅 `-Dargos.test.faults=<spec>`(10 문서 요구). spec 예: `advice-throw:servlet.enter`, `advice-throw:jdbc.exit`, `transform-error:httpclient-okhttp`, `helper-nocdef:spring-webmvc`. 테스트: 주입 후 샘플 앱 요청이 정상 응답(2xx), 해당 모듈 에러 카운터 증가, 임계(10) 초과 시 모듈 `disabled=true`가 자가 진단에 보이고 WARN 1회, 다른 모듈은 정상 수집(T-AG-040~045). `instrumentation.exclude-classes=com.example.Foo`가 변환 로그에서 해당 클래스 부재로 확인(T-AG-046).

### 5.8 재진입 검증

FakeCollector를 `latency(200ms)`로 두고 1,000 요청 후, `collected()`에 스레드 이름이 `argos-`로 시작하는 트랜잭션·step이 0건, `sql_outside_tx_total`·`orphan_steps_total` 카운터가 에이전트 자신의 송신에 의해 증가하지 않음(T-AG-050). 에이전트의 셰이딩된 Netty·스케줄러 클래스가 변환 로그에 없음(베이스라인).

### 5.9 누수 검사 (`LeakProbe`)

| 검사 | 방법 | 판정 |
|---|---|---|
| ThreadLocal 잔존 | 1만 요청 후 `jcmd GC.run`×2 → 자가 진단 `threadlocal_contexts_live`(10 문서 요구: 활성 스레드 순회 카운트) | 0 |
| 활성 트랜잭션 레지스트리 | 자가 진단 `active_registry_size` | 0 |
| 약참조 맵 | 자가 진단 `weakmap_size`(GC 후) | 0 |
| 힙 상주 | `GC.class_histogram`에서 `io.argos.` 인스턴스 바이트 합 | ≤ 64MB(N-02) |
| WAR 재배포 | Tomcat manager `deploy`/`undeploy` 50회 → `GC.run`×2 → 히스토그램 `ParallelWebappClassLoader` 인스턴스 수 | 1(현재 배포분). Tomcat 로그에 "ThreadLocal" 누수 경고 0 |
| 네이티브 | `-XX:NativeMemoryTracking=summary` 기동 → 부하 전 baseline → 10분 후 diff | `Internal+Other` ≤ 16MB, `Thread` 증가 ≤ 6 |

### 5.10 JVM 옵션·정적 검사

- 모든 통합·매트릭스 테스트는 `-XX:+BytecodeVerificationLocal`을 켠다(코어 클래스 변환 검증). JDK 25 조합은 `--sun-misc-unsafe-memory-access=deny`.
- `UnsafeScanner`: `javap -c -p`로 jar 안 모든 클래스의 `sun/misc/Unsafe` 참조를 찾아 허용 목록(`fixtures/unsafe-allowlist.txt`, 기본 비어 있음) 밖 0건(T-BLD-004).
- `AgentLogAssert`: 정상 시나리오 로그를 `fixtures/log-allowlist/agent-info.txt`(정규식 줄)와 대조. ERROR 0, 허용목록 밖 WARN 0, stdout/stderr 캡처 0바이트(T-AG-060). PII 시드 센티널 0건(T-SEC-26). 토큰 원문 0건(T-SEC-06).
- ArchUnit(`archTest`, agent-instrumentation): Advice 클래스(`@Advice.OnMethodEnter/Exit`가 있는 클래스)에 람다·`invokedynamic`·`synchronized`·`Thread.sleep`·`BlockingQueue.put/take`·`Class.forName`·`Method.invoke`·`new Throwable()` 금지, `skipOn`·`readOnly=false` 속성 금지, `MethodDelegation` 참조 금지, `java.*` 필드 타입 외 앱 타입 보관 금지(T-AG-001~004).

## 6. 호환성 매트릭스 러너 (`matrixTest`)

### 6.1 조합표

`test/matrix/matrix.yaml`이 유일한 정의다. 02 문서 §9 유효 조합표를 그대로 코드화하고 ID를 부여한다.

| ID 범위 | 축 | 값 |
|---|---|---|
| T-MX-001~012 | shop-boot2 × JDK 8·11·17·21(○), 25(△ 기대 실패 허용 아님 → "미검증" 표기로 실행만) | |
| T-MX-020~031 | shop-boot3 × JDK 17·21·25 × VT off/on | JDK 25는 deny 플래그 |
| T-MX-040~051 | shop-boot4 × JDK 17·21·25 × VT off/on | |
| T-MX-060~063 | shop-heavy × JDK 17·21·25 | 기동 시간 기록(N-27) |
| T-MX-070~075 | shop-batch × JDK 17·21·25 | kind=BATCH 분리 집계 |
| T-MX-080~087 | shop-war-javax × Tomcat 9 × JDK 8·11·17·21 | 재배포 50회 포함 |
| T-MX-090~095 | shop-war-jakarta × Tomcat 10.1·11 × JDK 17·21·25 | 멀티 웹앱 접두 |
| T-MX-100~108 | JDBC 9종 × shop-boot3/JDK 21 × 풀 3종(HikariCP·Tomcat JDBC·DBCP2) 중 Hikari 전부 + 나머지 풀은 PostgreSQL만 | |
| T-MX-110~113 | 공존: JaCoCo 앞/뒤, OTel javaagent 앞/뒤, devtools | 기동·수집 정상, `traceparent` 보존 |
| T-MX-120~121 | 라우팅 DataSource·중첩 래퍼(boot2·boot3) | D-24 |

각 조합은 같은 **스모크 시퀀스**를 돌린다: 기동(N-04 기록) → 카탈로그 25개 엔드포인트 각 20회 → 외부 호출 3종 → 비동기 4종 → 제외 노이즈 100회 → 단언(트랜잭션 수 = 기대값, `/error` 서비스 0, SQL step 1:1, 제외 카운터 = 100, 변환 베이스라인 일치, 로그 허용목록, 결측 규칙) → 스레드 덤프 명령(VT 포함 확인) → 종료(AgentBye 수신).

### 6.2 실행 설계 (DSN-18-11)

- Gradle `matrixTest -Pmatrix.combo=T-MX-021` 단건, `-Pmatrix.group=boot3` 그룹, 기본 전체.
- 실행 단위는 Docker Compose 프로젝트 1개: 샘플 앱 컨테이너(JDK 이미지) + ext-stub + FakeCollector(호스트 프로세스) + 필요 시 DB 컨테이너. 조합당 평균 4분, 병렬 4 → 전체 약 2시간.
- 결과: JUnit XML + `build/matrix/<combo>.json`(기동 시간, 변환 클래스 수, 트랜잭션 수, 실패 사유). 머지 스크립트가 `docs/compatibility-matrix.md`를 생성한다(검증 ○ / 실패 ✗ / 미검증 △ / 커뮤니티 검증 ◎(이슈 `kind/compat-report` 링크)).
- 실패는 "예상된 실패"로 처리하지 않는다. 미검증 조합(△)은 실행하되 결과를 게이트에 쓰지 않고 표에만 적는다.

### 6.3 JDBC 매트릭스

| DB | 소스 | 비고 |
|---|---|---|
| MySQL 8 | Testcontainers `mysql:8` | Connector/J 8·9 두 버전 |
| MariaDB 11 | `mariadb:11` | |
| PostgreSQL 16 | `postgres:16` | 달러 쿼팅·배열 리터럴 정규화 케이스 |
| MS SQL 2022 | `mcr.microsoft.com/mssql/server:2022-latest` | `@p1` 마커 |
| Oracle | `gvenzl/oracle-free`(컨테이너) + 사내 Oracle 19c(`ARGOS_TEST_ORACLE_URL`) | PL/SQL 블록·`{call}`·`:1` 마커, `OraclePreparedStatementWrapper` 중첩 |
| Tibero, Altibase, CUBRID, DB2 | 사내 호스트 `ARGOS_TEST_<DB>_URL/USER/PASSWORD`, 드라이버 jar `test/jdbc-drivers/`(저장소 밖, 러너 로컬) | 미설정 시 `@EnabledIfEnvironmentVariable` skip → 표에 △ |

각 DB에서: SQL 1회 = step 1개(D-24), `getConnection` DBC step, 정규화 결과 동일 해시(D-22), JDBC URL 파서가 벤더·호스트·DB명을 올바르게 추출, 바인드 파라미터 `masked` 기본에서 시드 0건.

### 6.4 공존 테스트

JaCoCo·OTel javaagent·`spring-boot-devtools`와 `-javaagent` 순서 앞·뒤로 기동. 판정: 기동 성공, 트랜잭션 수 동일, 변환 베이스라인(공존 전용 파일) 일치, 앱이 보낸 `traceparent`(OTel 생성)가 Argos HTTP step에 그대로 기록되고 덮어쓰이지 않음(F-A32).

## 7. 오버헤드 벤치 하네스 (`benchTest`) (DSN-18-07)

01 문서 7.3 프로토콜의 자동화다. `test/bench/`에 둔다.

### 7.1 환경 기록

`bench-env.sh`가 보고서 머리에 기록: 앱 머신 CPU 모델·코어·메모리, governor(`performance` 아니면 중단), THP, 커널, OS 이미지 해시, JDK 빌드(`java -version`), 에이전트 jar SHA-256, `argos-agent.yml` 해시, 샘플 앱 커밋, k6 버전, 부하기 머신 정보, 두 머신 간 ping RTT.

### 7.2 k6 시나리오 (`bench.js`)

```javascript
export const options = {
  scenarios: { mix: { executor: 'constant-arrival-rate', rate: 1000, timeUnit: '1s',
                      duration: '13m', preAllocatedVUs: 300, maxVUs: 600 } },
  summaryTrendStats: ['p(50)', 'p(95)', 'p(99)', 'p(99.9)', 'max'],
};
// 가중치: orders/{id} 40%, orders?page 20% (DB 60%), pay 20% (외부 1회), items N+1 10%, slow 300ms 5%, error-500 5%
```

HDR 원시 데이터는 `--out json`으로 저장하고, 러너가 요청별 지연을 재집계해 p50·p95·p99·p99.9와 95% 신뢰구간(부트스트랩 1,000회)을 계산한다. 처음 3분은 폐기한다.

### 7.3 교대 절차

`bench-run.sh --app shop-boot3 --jdk 21 --rounds 3 --exporter noop|grpc` → off, on, off, on, off, on 순으로 6회. 각 회차 사이 앱 재기동 + 워밍업 3분. off 3회 CPU 변동계수가 2%를 넘으면 결과를 `INVALID`로 표시하고 종료(측정 잡음 임계는 18.1 미결).

### 7.4 측정

| 항목 | 방법 | 산출 |
|---|---|---|
| 프로세스 CPU | 측정 구간 시작·끝 `/proc/<pid>/stat` utime+stime(틱 → 초), 컨테이너면 cgroup v2 `cpu.stat usage_usec` | 상대 오버헤드 = (on − off) / off, 피크는 10초 창 중 최대 1분 평균 |
| 에이전트 스레드 CPU | 자가 진단 `agent_threads_cpu_seconds_total` 차분 | 호스트 전체 대비 % |
| 지연 | k6 원시 → HDR 재집계 | p99 차이와 신뢰구간. 구간이 0을 포함하면 "차이 없음" |
| 힙 상주 | 측정 후 `jcmd GC.run`×2 → `GC.class_histogram` `io.argos.` 합 | ≤ 64MB |
| 메타스페이스 | `jcmd VM.metaspace` on−off | ≤ 32MB |
| 네이티브·스레드 | NMT summary diff, `/proc/<pid>/status Threads` | `Internal+Other` ≤ 16MB, 스레드 차 ≤ 6, 직접 메모리(NMT `Other`·에이전트 자가 진단 `direct_memory_bytes`) ≤ 8MB |
| 기동 | JVM 시작(프로세스 생성 시각) → 포트 바인딩, 10회 중앙값, 기본·heavy, `-Xshare:off`와 기본 CDS 둘 다 | +2초 / heavy +10% |
| premain | 에이전트 로그 배너의 `premain_ms` | ≤ 500ms(N-30) |

### 7.5 변형 실행

`--profile default|worst`(프로파일 정책 정상 10% vs 100%), `--container --cpus=2 --memory=4g`(N-29), `--vt`(N-28, Boot 3.2+), `--exporter noop|grpc`(계측 비용 vs 전송 비용), `--reference otel`(같은 하네스로 OpenTelemetry javaagent + OTLP 블랙홀 수신기, 참조점으로만 보고).

### 7.6 보고서 템플릿 (`docs/bench/<YYYY-MM-DD>-<commit7>.md`)

1. 환경(7.1) 2. 실행 매개변수 3. 회차별 원시 표(CPU 초, 요청 수, 오류 수, p50/95/99/99.9) 4. 통계(상대 CPU 평균·피크, p99 차이와 신뢰구간) 5. 메모리·스레드·기동 표 6. 판정(N-01~N-04·N-26~N-30 각각 PASS/FAIL/INVALID) 7. 그래프(CPU 시계열, 지연 CDF) 8. 재현 명령. 수치 없는 PASS는 러너가 생성하지 않는다.

### 7.7 회귀 판정

FAIL: N-xx 목표 초과. WARN: 직전 승인 보고서 대비 상대 CPU +1.5%p 또는 p99 차이 +0.3ms 이상 악화. 주간 실행 결과는 `docs/bench/latest.md` 심볼릭 링크로 가리키고 CI가 추세 그래프를 갱신한다.

## 8. 서버 부하 생성기 (`serverLoadTest`)

`test/load/fake-agents`(Java, 11 문서 proto 스텁 사용). 실제 에이전트 코드가 아니라 프로토콜 수준에서 서버를 때린다.

| 매개변수 | 기본 | 설명 |
|---|---|---|
| `--instances` | 50 | 가짜 인스턴스 수(앱 10개에 분배, env prod/stg) |
| `--tps` | 5,000 | 전체 트랜잭션/초(Zipf 서비스 100종, 로그정규 응답시간, 에러 1%) |
| `--steps-per-tx`, `--profile-rate` | 15, 0.12 | 프로파일 50,000 row/s |
| `--stats-rows` | 10,000/s | sql_stat·ext_stat 10초 창 |
| `--metrics` | 100/인스턴스/5초 | |
| `--duration` | 1h | N-06 |
| `--late-arrival`, `--duplicate`, `--clock-skew` | 0.5%, 0.1%, ±2s(2개 +60s) | D-13·D-15 |
| `--attack` | 없음 | `gzip-bomb`(1MB → 1GB), `batch-overflow`(5,001건), `dict-flood`(랜덤 서비스 10만), `instance-flood`(시간당 1,000), `unauth-flood`(IP당 분당 100 Hello 실패) |

측정: 서버 `/actuator/prometheus`에서 `argos_ingest_lag_seconds`(p99 ≤ 5초), `argos_collector_rejected_total{reason}`, 서버 RSS·힙, ClickHouse `system.parts` 활성 수(파티션당 < 300), `DelayedInserts` 0, 동시에 가상 사용자 10명이 대시보드·XLog·Top N API를 5초마다 호출해 p95 ≤ 2초(Q17). 공격 시나리오는 서버 메모리 상한 유지와 다른 인스턴스 적재 지연 ≤ 5초를 단언(N-10d). 끝나면 **보낸 수 = count(xlog) = Σ service_stat_1m.count**(D-13, 기준값은 생성기 카운터).

## 9. 저장소 PoC 하네스 (`storageBench`, P-4) (DSN-18-12)

`test/storage/`에 Java 생성기·러너(ClickHouse Java client 공유).

- **생성기 분포**: 앱 10, 인스턴스 50, 서비스 100(Zipf s=1.1), SQL 2,000(Zipf), 외부 호스트 30, 버전 3(15일째 전환), 에러율 1%·에러 그룹 50, 응답시간 로그정규(σ=1.0, 중앙값 80ms) + 1% 이상치(2~30초), user_id 30% 채움(해시 모드), remote_ip 1만 종(Zipf, masked), 인스턴스별 시계 오차 ±2초(2개 +60초), 늦은 도착 0.5%(1~30분), 중복 재전송 0.1%(같은 dedup 토큰), 자정 경계 포함, kind BATCH 2%.
- **규모**(30일): xlog 13억, profile_step 5.4억(7일), metric 12억(14일), sql_stat_raw 1.2억, ext_stat_raw 0.2억. `-Pscale=0.01`이면 1/100(CI).
- **모드**: `bulk`(용량·압축 측정, 대량 insert) / `replay`(실제 라이터 경로: 2초 배치, dedup 토큰, 목표 TPS로 1시간 이상 → parts·적재 지연·동시 질의).
- **쿼리·검증 Q1~Q20**: `test/storage/queries/Qnn.sql`(파라미터 치환). 콜드 = OS 캐시 drop(`echo 3 > /proc/sys/vm/drop_caches`, 러너 전용 호스트) + `SYSTEM DROP MARK CACHE` 후 1회, 웜 = 3회 중앙값. 기준은 03 문서 Gate 0 저장소 행과 13 문서 PoC 절.
- **보고서** `docs/poc/clickhouse-benchmark.md`: 환경, 적재 속도, 테이블별 압축률(`system.parts` 압축 전/후 바이트), 행당 바이트 B_x·B_p·B_m·B_s, 쿼리별 시간, parts 추이, 메모리 피크, 결정 사항(A-9·A-10·A-11·코덱·bloom).
- CI(1/100): 생성·적재·Q12·Q13·Q15·Q16 정합 0 단언만(T-ST-030~033). 전체 규모는 사내 `argos-storage`에서 수동.

## 10. 정확성 테스트 (T-ACC-nn, D-nn과 1:1)

| ID | 규칙 | 테스트 설계(입력 → 기대 → 방법) |
|---|---|---|
| T-ACC-01 | 단조 시계 | 요청 처리 중 테스트 훅으로 시스템 시각을 +1시간 변경 → elapsed_ms는 실제 경과(±5ms) → 가짜 서버 수신값 |
| T-ACC-02 | 시계 차이 측정 | FakeCollector가 응답 타임스탬프에 +3초 오프셋 → `ServerHello`·heartbeat 오프셋 이동 중앙값 ≈ 3초, 인스턴스 WARN(1초 이상) → 서버 통합 |
| T-ACC-03 | 점 수 = 통계 = TPS 적분 | 합성 1시간 적재(늦은 도착·중복 포함) → 분 경계 범위에서 `count(xlog)` = `Σ service_stat_1m.count` = `Σ app_stat_1m.count` = 대시보드 버킷 합(오차 0), 미정렬 범위는 양 끝 원본 보정 → Testcontainers + API |
| T-ACC-04 | 1요청 1트랜잭션 | 예외·404·sendError·forward·async dispatch 5종 각 100회 → 트랜잭션 정확히 100, `/error` 0 → agent-testing |
| T-ACC-05 | 호출자 ≥ 피호출자 | boot3 → boot3 2-hop 100회 → HTTP step elapsed ≥ 피호출 트랜잭션 elapsed, 트레이스 API가 둘 다 반환 |
| T-ACC-06 | 드롭 가시화 | FakeCollector `hang()` 2분 → 에이전트 드롭 카운터 > 0, 복구 후 `argos_agent_dropped_total` 수신, 수집 상태 API에 노출 |
| T-ACC-07 | 미수집 사유 구분 | 샘플링 미수집 트랜잭션과 프로파일 배치 드롭 트랜잭션 → 상세 API `profile_status: SAMPLED_OUT` / `LOST` |
| T-ACC-08 | 사전 키 안정 | 같은 정규화 SQL을 다른 인스턴스·재기동 후 → 같은 `sql_id`; 사전 upsert 중복 없음 |
| T-ACC-09 | 메트릭 결측 | 에이전트 60초 정지(SIGSTOP) → 해당 버킷 null(0 아님) → 메트릭 API |
| T-ACC-10 | 에러 판정 | 4xx 100회 → error=0, 5xx·예외 → error=1 |
| T-ACC-11 | tx_id 유일·시간 접두 | 1,000만 건 생성(멀티스레드) 충돌 0; `tx_id >> 23` 복원 시각 = start_ts(±1ms); 두 인스턴스 동시 생성 충돌 0 |
| T-ACC-12 | 전수 통계 | 프로파일 정상 샘플 0%로 1만 요청 → `Σ xlog.sql_count` = `Σ sql_stat_1m.count` = 실제 SQL 실행 수(앱 측 카운터) |
| T-ACC-13 | 중복 0·누락 0 | 가짜 에이전트 보낸 수 기준: (a) 같은 seq 재전송 100회 (b) 서버 `kill -9` 후 재기동·WAL 복구 (c) ClickHouse 30분 중단 후 복구 (d) MV 1개 실패 주입 후 재시도 → 원본·모든 롤업 행 수 = 보낸 수 |
| T-ACC-14 | 롤업 결정적·재구축 | 하루치 적재 → `rollup rebuild --date` → 재구축 전후 바이트 동일(`cityHash64` 집계); 야간 정합 검사에 불일치 주입(수동 DELETE) → 탐지·재구축 로그 |
| T-ACC-15 | 타임스탬프 가드 | ts = now+10s / now−8d / now−90s 배치 → clamp / 거부 카운트 / 저장만·실시간 미발행; 실시간 TPS가 ts 버킷 기준 |
| T-ACC-16 | 메트릭 종류 | counter 리셋 주입(재기동) → 1m increase 보정값, gauge avg·max·min, `sample_count`; 시리즈 501번째 드롭 + 카운트 |
| T-ACC-17 | Apdex T 기록 | T 500→1000 변경 → 이전 분 불변, 이후 분 새 T, 재구축 소급 옵션, API 마커 |
| T-ACC-17b | Apdex T 변경 이벤트 | 앱·서비스 T 변경 → `apdex_t_change` 이벤트 1건(이전·새 값·서비스), 스탬프 즉시 재적재; 변경 없음 → 이벤트 0; 1,001번째 오버라이드 거부 → 단위 |
| T-ACC-18 | 수집 중단 음영 | 세션 끊김 5분 → 수집 상태 API `gaps[]`에 구간, 대시보드 API 응답에 `gaps` 포함(0과 구분) |
| T-ACC-19 | 제외 카운터 | 노이즈 100회 → 트랜잭션 0, `transactions_ignored_total{reason}` = 100, 수집 상태 "제외 100" |
| T-ACC-20 | kind 분리 | shop-batch Job 실행 + HTTP 1,000 → HTTP 통계·Apdex·TPS에 배치 미포함, BATCH 통계 별도, 컨텍스트 없는 SQL은 `sql_outside_tx_total` |
| T-ACC-21 | 최종 상태 코드 | 예외 탈출 시 status<400 → 저장 500; ERROR 디스패치 후 503 → 503 |
| T-ACC-22 | SQL 정규화 50케이스 | 입력·기대 정규화문·해시 고정 fixture(`normalization-cases.yaml`), PII 시드 리터럴 0건, 실패 주입 시 `<unparsed>` |
| T-ACC-23 | 측정 불가 → 결측 | VT 모드 → `cpu_time_ms`·`alloc_bytes` 필드 부재(0 아님), 상세 API `null` |
| T-ACC-24 | JDBC 1:1 | 라우팅 DS 4단 래퍼 + 9 DB → SQL step 1, `sql_count` 1 |
| T-ACC-25 | GC pause/cycle | `-XX:+UseZGC`(세대형)·Shenandoah·G1·Parallel·Serial 기동 → MXBean 이름 매핑표대로 라벨, `jvm_gc_pause_seconds_total`에 Cycles 미포함 |
| T-ACC-26 | 요청 비소비 | Mock `HttpServletRequest`(Mockito spy) 통과 → `getParameter*`·`getReader`·`getInputStream`·`getSession(true)` 호출 0, `getSession(false)`만 |

매 커밋 `integrationTest`에는 T-ACC-01~26 중 경량 버전(1/1000 데이터)이 들어가고, 전체 규모는 `storageBench`·`serverLoadTest`에서 반복한다.

## 11. 카오스·장시간

### 11.1 카오스 (`chaosTest`) (DSN-18-08)

구성: 샘플 앱(shop-boot3, 에이전트) ↔ **toxiproxy** ↔ 실제 서버 ↔ ClickHouse·PostgreSQL(Compose). 부하는 k6 200 TPS 지속. 판정 공통: 앱 p99 변화가 벤치 신뢰구간 안, 에이전트 로그 ERROR 0, 복구 후 60초 내 인스턴스 UP·수집 재개, 드롭·중단 구간이 수집 상태에 표시.

| ID | 시나리오 | 방법 | 추가 판정 |
|---|---|---|---|
| T-CH-001 | 서버 중단 1시간 | `docker stop argos-server` | 큐 120초분 초과 드롭 카운트 정확, 재접속 백오프 ≤ 30초 |
| T-CH-002 | 지연 500ms | toxiproxy latency | in-flight 상한 유지, 힙 증가 ≤ 큐 상한 |
| T-CH-003 | 손실 10% | toxiproxy `limit_data`/timeout | 재전송 중복 0(D-13) |
| T-CH-004 | 서버 5xx·`RESOURCE_EXHAUSTED` | 서버 테스트 플래그 | 드롭(쿼터) 구분 표시 |
| T-CH-005 | 에이전트 로그 디스크 풀 | 로그 디렉터리 tmpfs 1MB | stderr 폴백 분당 ≤ 10줄, 앱 영향 0 |
| T-CH-006 | DNS 실패 | 서버 호스트명 해석 실패(`/etc/hosts` 제거) | premain 비블로킹, 재시도 |
| T-CH-007 | TLS 호스트명 불일치 | 잘못된 인증서 | 접속 실패·WARN, `insecure-skip-verify`면 접속 + 매 접속 WARN |
| T-CH-008 | instance_id 재부여 | 서버 DB 초기화 후 재기동 | 새 id로 세션, 이전 데이터 "알 수 없는 인스턴스" 표시 |
| T-CH-009 | ClickHouse 30분 중단 | `docker pause clickhouse` | WAL 누적(상한 10GiB 미만), 복구 후 재적재, D-13 |
| T-CH-010 | PostgreSQL 중단 5분 | `docker pause postgres` | 수집 지속(사전 미해결 카운트), UI 로그인 실패 메시지, 복구 후 사전 해결 |
| T-CH-011 | 서버 디스크 90% | 디스크 채우기 | 비상 보존 단축 실행·표시·알림, 95% WAL만 |

### 11.2 장시간 (`soakTest`)

72시간. 구성: shop-boot3(에이전트, 500 TPS k6 상시) + shop-batch(배치 매시간) + 실제 서버 + ClickHouse + PostgreSQL. `-XX:NativeMemoryTracking=summary`.

| 측정 | 간격 | 판정 |
|---|---|---|
| 에이전트 힙 상주(`io.argos.` 히스토그램, GC.run 후) | 1시간 | 72점 선형회귀 기울기 ≤ 0(허용 +50KB/h), 최대 ≤ 64MB |
| NMT `Internal+Other+Thread` | 1시간 | 증가 ≤ 16MB, 스레드 수 평탄(±0) |
| FD 수(`/proc/<pid>/fd`) | 10분 | 평탄(±5) |
| 서버 RSS·힙·오프힙, WAL 크기 | 10분 | 상한 내, WAL 평상시 < 100MB |
| ClickHouse 활성 parts(파티션당), merges, 디스크 | 10분 | parts < 300, 디스크 증가율 = N-19 공식 ±30% |
| 야간 정합 검사 결과 | 매일 | 불일치 0 |
| 알림 오탐 | 전 기간 | 템플릿 규칙 발화 0(정상 부하) |
| 앱 p99 | 1시간 | 첫 6시간 대비 악화 ≤ 신뢰구간 |

## 12. 보안 테스트 스위트 (`securityTest`)

| ID | SEC | 설계 |
|---|---|---|
| T-SEC-01·02 | 앱 단위 토큰·해시 보관 | 다른 앱 Hello → `PERMISSION_DENIED`; 발급 후 PostgreSQL `agent_tokens`에 원문 부재(정규식 `argos_[A-Za-z0-9_-]{40,}` 0건), 로그 0건 |
| T-SEC-03~05 | 만료·회전·폐기·레이트 리밋 | 만료 토큰 거부; 회전 유예 중 두 토큰 동시 세션; 폐기 → 활성 세션 5초 내 종료(FakeAgent가 `UNAUTHENTICATED` 수신) → 재접속 거부; 분당 61번째 세션 `RESOURCE_EXHAUSTED`, IP 인증 실패 21회 → 10분 차단 |
| T-SEC-06 | 토큰 보관·마스킹 | 평문 yml → WARN 1회; `token-file` 0600; 진단 번들·자가 진단 출력 grep `argos_****`만 |
| T-SEC-07·08 | TLS | testssl.sh 스크립트: TLS 1.1·CBC·RSA 키교환 거부; 호스트명 불일치 접속 실패; `allow-plaintext` 없이 평문 거부 |
| T-SEC-09 | 인증서 만료 메트릭·리로드 | 7일 남은 인증서 → 메트릭·알림; 파일 교체 → 새 인증서 핸드셰이크 |
| T-SEC-11~15 | 수집 상한·하드닝 | 가짜 에이전트 공격 5종(8장) → 거부 코드·카운터, 서버 RSS 증가 ≤ 200MB, 다른 인스턴스 적재 지연 ≤ 5초; 리플렉션 RPC 실패; 미인증 `SendTransactions` 거부 |
| T-SEC-20 | 환경 덤프 마스킹 | 시드 환경변수 → 덤프 결과 원문 0건, `****(len=N)`; 패턴 단위 30케이스 |
| T-SEC-21·50 | 명령 권한·객체 권한 | 역할 3 × 명령 5 매트릭스; 타인 명령 결과 403; id UUIDv4 형식 |
| T-SEC-22·24 | 명령 결과 암호화·수명 | DB 직접 조회 평문 부재; TTL 60초 지난 명령 미실행; 연속 덤프 쿨다운 거부 |
| T-SEC-23 | 하드 캡 | 불허 키 푸시 → `rejected_keys`, 로컬 값 불변, 감사 로그; 허용 키 적용 |
| T-SEC-25 | 금지 접두사 | `java.*` 패턴 설정 → 무시 + WARN; 매칭 501개 패턴 비활성 |
| T-SEC-26 | 로그 PII | INFO 1만 요청 후 에이전트 로그 센티널 0건 |
| T-SEC-30 | 분류표 갱신 | 스키마(proto·DDL) 필드 목록과 02 문서 분류표 필드 목록 diff 테스트(새 필드 누락 시 실패) |
| T-SEC-31·32·34 | IP·user_id·쿼리스트링 모드 | 각 모드 저장값 단언; `masked` IP /24 검색; 기본에서 `?phone=` 저장 URL 부재 |
| T-SEC-33 | 삭제 API | user_id·IP 기준 삭제 → 조회 0건, 뮤테이션 상태 API, 감사 로그 |
| T-SEC-35·36 | 정규화 실패 안전·메시지 마스킹 | T-ACC-22 공유; 메시지 패턴 5종 마스킹, 지문 안정성(마스킹된 값이 달라도 같은 그룹) |
| T-SEC-37·38 | XFF·전파 헤더 | trusted-proxies 미설정 시 소켓 IP; `exclude-hosts` 호스트에 헤더 부재, 기본 `tracestate` Argos 엔트리 미주입 |
| T-SEC-39 | 템플릿 변수 | 금지 변수 참조 저장 거부, 변수 집합 스냅샷 |
| T-SEC-3A | 바인드 모드 | masked/partial/full 저장값, full 열람 권한·감사 |
| T-SEC-40~44 | 세션 | Playwright: `document.cookie`·localStorage 토큰 부재, Set-Cookie 플래그; 액세스 만료 401; 리프레시 재사용 → 전체 무효화; 로그아웃·역할 강등 즉시 반영; CSRF 토큰 없는 POST 403, 교차 Origin 403, 세션 id 재발급; WS 무인증 close 4401, 교차 Origin 거부, 로그아웃 후 1초 내 종료 |
| T-SEC-44c | 실서버 WS 세션 폐기(E2E) | 페이지 밖에서 세션을 폐기(API 로그아웃)하면 서버가 1초 안에 소켓을 끝내고(reauth 또는 close 4401) 페이지는 `/login?next=<현재 경로>`로 이동, 재접속 없음 |
| T-SEC-44d | WS 폐기 종료 경로 | 폐기된 세션(가족 중 어느 행이든)은 reauth 뒤 1초 안에 close 4401, 종료 요청 시 구독 종료·대기 프레임 폐기, 막힌 송신이 있어도 강제 종료(900ms)로 4401 지연 없음, 프레임은 한 번에 하나의 송신 작업이 순서대로 보냄 |
| T-SEC-45·46 | 헤더·CSP | 모든 응답 헤더 단언; E2E 전 실행 중 CSP 위반 리포트 0(`securitypolicyviolation` 수집) |
| T-SEC-47·48 | 부트스트랩·비밀번호 | 토큰 없이 마법사 403, 재사용 403, 관리자 존재 시 404; 11자 거부·12자 허용, 잠금·누진, bcrypt cost 12 |
| T-SEC-49 | 권한 매트릭스 생성 | OpenAPI 스펙 파싱 → 엔드포인트 × 역할 3 → 기대(2xx/403) 자동 생성 실행; `x-argos-role` 누락 엔드포인트 린트 실패 |
| T-SEC-51~54 | 채널 | 금지 대역 URL 10종 저장 400, 리다이렉트 미추종(ext-stub), allow-cidrs 통과, 응답 본문 비노출; 채널 비밀값 DB 평문 부재·GET 마스킹·키 교체; 웹훅 서명 검증; 평문 SMTP 기본 거부 |
| T-SEC-55·56 | Actuator·레이트 리밋 | `/actuator/env` 404, prometheus 무인증 401; 429 + `Retry-After` |
| T-SEC-60~64 | UI 렌더링 | ESLint 규칙 테스트; 컴포넌트 테스트에 XSS 페이로드 → 텍스트 렌더; E2E 전역 훅(13장); CSV 셀 접두; 변조 URL 복원 |
| T-SEC-70~77 | 공급망 | SBOM 생성·컴포넌트 수 > 0·jar 내 존재·Trivy sbom Critical·High 0(예외 파일 대조); gitleaks 0; 2회 빌드 jar 해시 일치; `.env` 생성·0600, 금지 비밀번호 기동 실패; 노출 포트 = {8080, 9100}; 조회 계정 DDL 실패; `argos-deps.txt` 형식 |
| T-SEC-80~83 | 감사 | 이벤트 카탈로그 스냅샷(각 이벤트 발생 → 행 단언); 스키마 필드; DELETE/UPDATE API 부재 린트; 조회 계정 삭제 실패; syslog 포맷 |
| T-SEC-93 | 스위트 분리 실행 | `./gradlew securityTest`가 위 전부를 포함하고 CI 잡 이름 `security` |

PII 시드 grep(N-10c, DSN-18-09): 시드 트래픽 1만 요청 후 (a) ClickHouse `system.columns`에서 `type LIKE '%String%'`인 모든 컬럼에 대해 `SELECT count() FROM t WHERE col LIKE '%ARGOS_SEED%'` 자동 생성 실행, (b) PostgreSQL `pg_dump --data-only | grep -c ARGOS_SEED`, (c) 서버·에이전트 로그, (d) 알림 Mock 수신함, (e) 모든 CSV 내보내기 결과 → 전부 0.

ZAP(릴리스 전): `zaproxy/zap-stable` 컨테이너, 인증 컨텍스트(로그인 폼 + 쿠키 세션), OpenAPI 스펙 import로 API 스캔, 활성 스캔 정책 기본, 결과 Critical·High 0, Medium은 `docs/security/scan-<version>.md`에 사유. gRPC는 ZAP 대상 밖이며 T-SEC-11~15와 11 문서의 프로토버프 변이 퍼징(1.x)으로 보완.

## 13. E2E (Playwright) (DSN-18-10)

- 구조: `ui/e2e/specs/<screen>.spec.ts`(화면별), `ui/e2e/required/<screen>.yaml`(05 §2 필수 요소를 항목 ID·셀렉터·검증 방법으로 코드화 → 데이터 주도 테스트 `T-E2E-<screen>-<n>`), `ui/e2e/fixtures/`(auth, seedData, clock, ws).
- 픽스처: `auth`(API 로그인 → 쿠키, 역할별 3계정), `seedData`(저장소 생성기 1/1000 + 시드 트래픽 결과를 전역 셋업에서 1회 적재, 범위 고정 `2026-01-15T00:00~01:00Z`), `clock`(브라우저 `Date` 고정은 하지 않음. URL 절대 범위로 결정성 확보), `ws`(toxiproxy 토글로 끊김·재접속).
- 공통 훅: `page.addInitScript`로 `securitypolicyviolation` 이벤트 수집 배열과 `window.__xss` 감시. 모든 테스트 `afterEach`에서 `__xss === undefined`, CSP 위반 0 단언(T-SEC-62·46). 콘솔 에러 0.
- 필수 테스트 그룹: 로그인·전체 현황 기본 화면, 딥링크 5항(비로그인 복귀, 알림 링크 고정 범위·실시간 아님, 대상 필터, 마커, 1주 뒤 동일 데이터), 시간 범위 전역 상태(차트 드래그 → URL 변경 → 다른 화면 유지), 표 값 클릭 필터 칩, XLog 드래그 → 요약 → 목록 → 상세 → SQL 통계 이동(S-01 자동 부분), 트랜잭션 상세 필수 요소 전수, 액티브·인스턴스·스레드 덤프, 알림 규칙 편집·미리보기·템플릿, 알림 이력·ACK, 관리 화면(토큰 1회 표시·설정 변경 diff·되돌리기·킬 스위치 확인), 실시간 끊김 배지·재접속·로그아웃 시 WS 종료, i18n en/ko 전환(하드코딩 문자열 0은 린트), 키보드 조작(시간 이동·목록·상세), 1366×768 가로 스크롤 없음(모든 화면 `scrollWidth <= clientWidth`).
- 스크린샷 비교: 로딩·빈 상태·에러 상태 3종 × 화면, 핵심 화면 6종, 1366×768, 임계 0.1%, 폰트 고정(번들 폰트).
- 성능: XLog 10만 점 렌더 프레임 측정은 E2E가 아니라 `ui/perf/xlog-frame.spec.ts`(Chrome tracing, 16ms 이하, T-NFR-14), Lighthouse 초기 로드 2초.
- 샤딩 4, 재시도 0(15장), 트레이스·비디오는 실패 시만.

## 14. 사람 수락 체크리스트 (T-ACPT-nn ↔ S-nn)

각 체크리스트는 데모 프로파일(`docker compose --profile demo up`) 또는 WO 지정 환경에서 15분 안에 끝난다. 자동화된 부분은 E2E ID를 적고 사람은 "눈으로 확인" 항목만 수행한다.

| ID | 사람이 확인하는 것 | 자동화된 부분 |
|---|---|---|
| T-ACPT-01 | 알림 → 대시보드 → XLog 드래그 → 요약에서 공통 서비스 → 상세 SQL 비중 → SQL 통계까지 화면 전환 5회 이내, 10분 이내 | T-E2E xlog→detail→sql 경로 |
| T-ACPT-02 | 액티브 화면에서 8초 이상 요청 선택 → 스레드 스택 30초 내 표시, 상태별 그룹·트랜잭션 매칭 | T-SV 명령 왕복 |
| T-ACPT-03 | SQL Top N(1일) 1초 내, N+1 접힌 요약, 전수 수치(배치 제외) | T-ACC-12, T-NFR-07 |
| T-ACPT-04 | 배포 마커·버전별 p95 비교 표 | T-E2E service-version |
| T-ACPT-05 | 2-hop 트레이스 트리, 네트워크·대기 표시, 인스턴스 대시보드 점프 | T-ACC-05, T-E2E trace |
| T-ACPT-06 | 7일 힙 추세 1초, ZGC에서 GC 시간 알림 비발화 | T-ACC-25 |
| T-ACPT-07 | `--check` 표·종료 코드, 등록·토큰, 1대 적용, 비교 표 차이(CPU 3%p·p99 1ms), 자가 진단 0, 10분 | T-MX 스모크, T-NFR-01·03 |
| T-ACPT-08 | 서버 중단 중 앱 영향 없음(체감·p99), 복구 1분, 음영·유실 수 표시 | T-CH-001, T-ACC-18 |
| T-ACPT-09 | 배치 트랜잭션 구분 표시, HTTP 통계 비영향 | T-ACC-20 |
| T-ACPT-10 | 커넥션 대기 N초 + SQL M초 분리, 풀 알림 30초 내 | T-AG DBC, T-NFR-23 |
| T-ACPT-11 | 호스트별 타임아웃 통계, 액티브 "외부 호출 중" | T-AG 실패 분류 |
| T-ACPT-12 | user id 검색 3초, 로그 열기 링크, 미수집 모드 안내 | T-NFR-24, T-E2E search |
| T-ACPT-13 | 신규 에러 그룹 대표 스택 3클릭, 버전 분포 | T-E2E errors |
| T-ACPT-14 | 이상치 인스턴스 강조 30초, XLog 색 기준 전환 | T-E2E instances |
| T-ACPT-15 | 저트래픽 비발화, ACK 후 재알림 중지·복구 알림, 딥링크 복귀 | T-SV 상태 머신, T-E2E deeplink |
| T-ACPT-16 | 30개 앱 스크롤 없이, 밤사이 이력 한 목록 | T-E2E overview 1366 |
| T-ACPT-17 | 롤링 재기동 4대 알림 0, 마커 자동 | T-SV AgentBye·유예 |
| T-ACPT-18 | 설정 변경 사유·diff·결과 10초·되돌리기, 불허 키 거부, 감사 | T-SEC-23, T-NFR-25 |

## 15. 테스트 데이터·플레이키·커버리지·아티팩트

- **결정성**: 모든 생성기 시드 고정(기본 42), id는 시드에서 유도, 시간 범위는 절대값. 테스트는 `Instant.now()` 대신 주입 가능한 `Clock`을 쓴다(에이전트는 예외: `nanoTime` 측정은 범위 단언).
- **데이터 세트**: `tiny`(단위·CI E2E, 1/1000), `ci`(통합, 1/100), `full`(사내 전체 규모). 생성기 하나(`test/storage/generator`)가 모두 만든다.
- **플레이키 정책(DSN-18-13)**: 자동 재시도 금지. 3회 이상 간헐 실패한 테스트는 `@Tag("quarantine")`로 격리하고 이슈(`kind/bug`, `area/test`)와 소유자를 기록, 7일 안에 수정 또는 삭제. 격리 테스트는 결과를 보고하되 게이트에 쓰지 않는다. 격리 목록이 10개를 넘으면 릴리스 금지.
- **커버리지(DSN-18-14)**: JaCoCo·c8. 핵심 경로(agent-bootstrap, agent-core 전송·컨텍스트·자기 보호, 서버 collector·auth·command·마스킹·정규화) 라인 85%·브랜치 70%, 그 외 라인 70%. 수치는 게이트가 아니라 리뷰 지표. 계측 모듈은 통합 테스트 커버리지로 측정(에이전트 jar에 JaCoCo를 붙인 샘플 앱 실행).
- **아티팩트·보존**: JUnit XML, Playwright 트레이스(실패분), 매트릭스 `compat-report.json`·생성된 `docs/compatibility-matrix.md`, 벤치 보고서(`docs/bench/`, 저장소 커밋), soak·카오스 보고서(`docs/bench/`), SBOM 스캔 결과, 추적성 보고서(`traceability.md`). CI 아티팩트 90일, 저장소 커밋 보고서는 영구.
- **테스트 전용 설정 네임스페이스**: `argos.test.*`(에이전트), `argos.testing.*`(서버)는 릴리스 빌드에서 무시되며 그 사실을 T-BLD-010이 검증한다.

## 16. 요구 ↔ 테스트 대응표

### 16.1 정확성·비기능

| 요구 | 테스트 | 요구 | 테스트 |
|---|---|---|---|
| D-01~D-26 | T-ACC-01~26(10장) | N-01 | T-NFR-01 벤치 상대 CPU |
| N-02 | T-NFR-02 벤치 힙 + T-SOAK-001 기울기 | N-03 | T-NFR-03 벤치 p99 신뢰구간 |
| N-04 | T-NFR-04 기동(기본·heavy·CDS) | N-05 | T-CH-001~003 |
| N-06 | T-NFR-06 서버 부하 1시간 | N-07 | T-NFR-07 Q1·Q3·Q5·Q7·Q8(30일 데이터) |
| N-08 | T-ST-040 TTL·보존 변경 리허설 | N-09 | (설계 확인, 테스트 없음) |
| N-10a | ASVS 체크리스트 ↔ T-SEC 매핑 리뷰 | N-10b | T-SEC-70·71 + ZAP + `securityTest` 전부 |
| N-10c | T-SEC PII grep | N-10d | T-SEC-11~15 공격 5종 |
| N-11 | T-ACPT 설치(비참여자) | N-12 | T-NFR-12 에어갭 설치 |
| N-13 | T-MX 전부 | N-14 | T-NFR-14 XLog 프레임, Lighthouse |
| N-15 | T-SV-020 수집 지연(수신 → WS 1초, → 조회 5초) | N-16 | T-NFR-16 데모 60초(스크립트 측정) + 비참여자 2인 |
| N-17 | T-NFR-17 에어갭 패킷 캡처 egress 0 | N-18 | T-SOAK OOM 0 + ClickHouse 설정 단언 |
| N-19 | T-ST-050 B_* 실측 vs 공식 ±30% | N-20 | T-ACPT 백업·복구 리허설 |
| N-21 | T-ST-051 ClickHouse 이전 LTS → 현재 업그레이드 | N-22 | T-ST-052 마이그레이션 두 경로·10분·MV 4단계 |
| N-23 | T-SV-030 알림 지연 30초 | N-24 | T-NFR-24 ID 검색 응답 |
| N-25 | T-SV-031 설정·킬 스위치 10초(가짜 에이전트 50) | N-26 | T-NFR-26 메타스페이스·직접 메모리·스레드 |
| N-27 | T-AG-005 베이스라인 + T-NFR-04 heavy | N-28 | T-NFR-28 VT 반복 |
| N-29 | T-NFR-29 컨테이너 반복 | N-30 | T-NFR-30 premain 배너 |

### 16.2 보안

SEC-01~93 중 "자동" 검증 항목은 12장 T-SEC-nn과 1:1. "수동"(SEC-10 가이드, SEC-30 표 존재, SEC-76 권한 표, SEC-84 리허설, SEC-90~92 문서)은 Gate 체크리스트 항목으로 03 문서가 판정한다.

### 16.3 03 문서 DoD 불릿 ↔ 테스트

| 03 항목 | 테스트 |
|---|---|
| 2장 정적 분석 0 | `check`(Spotless·Error Prone·ESLint·tsc·ArchUnit T-AG-001~004) |
| 2장 분류표 갱신 | T-SEC-30 |
| 2장 입력 상한 | T-SEC-11, 각 모듈 단위 |
| 2장 외부 호출 없음 | T-NFR-17 + 정적(네트워크 클래스 참조 허용 목록, T-BLD-011) |
| 2장 SPDX·차용 코드 | Spotless 헤더 검사, `NOTICE` 린트 T-BLD-012 |
| 2장 셰이딩·SBOM | T-BLD-001~006(`verifyAgentJar`) |
| 3.1 전부 | T-AG-001~060, T-MX, T-NFR-01~04·26~30, T-SEC-06·20·23·25·26 |
| 3.2 전부 | T-SV, T-ST, T-API, T-ACC-03·12·13 |
| 3.3 전부 | T-UI, T-E2E, T-SEC-40·60~62 |
| 3.4 전부 | T-ACPT 설치, T-NFR-16·17, T-SEC-47·74·75, T-ST-052(업그레이드), T-CH-009(WAL 복구) |
| 3.5 전부 | 12장 |
| Gate 0 | P-1 T-AG+T-MX 축소, P-2 T-NFR-01~04(noop), P-3 전송 비교 보고서(벤치 하네스 `--exporter` 3종), P-4 T-ST Q1~Q20, P-5 T-NFR-14, P-6 T-AG-070 JPMS |
| Gate 1 | 16.1·16.2 M 항목 + T-SOAK + T-CH + T-ACPT M 범위 + 매트릭스 지정 조합 |
| Gate 1.5 | + T-NFR-16·17, T-SEC-70~73 릴리스 파이프라인, ZAP |
| Gate 2 | 전부 + 침투 체크리스트(사람) + 보안 릴리스 모의 훈련 기록 |

## 17. 설계 결정

| ID | 결정 | 대안 | 선택 이유 | 영향 요구 |
|---|---|---|---|---|
| DSN-18-01 | 테스트 ID 접두 체계와 요구 태그, CI가 추적성 보고서 생성·diff | 문서만 | 요구 ↔ 테스트 누락을 기계가 잡는다 | 03 전부 |
| DSN-18-02 | 소스 세트·태스크 분리, PR 게이트 ≤ 25분 | 단일 `test` | 느린 테스트가 PR을 막지 않고, 외부 기여 PR이 사내 자원 없이 돈다 | O-Q02 |
| DSN-18-03 | 샘플 앱 7종 + ext-stub이 유일한 에이전트 통합 픽스처. Mock 서블릿 환경 금지(D-26 단위 제외) | Mock 컨테이너 | 실제 Tomcat·Spring 디스패치 동작이 검증 대상 | F-A28, D-04 |
| DSN-18-04 | 에이전트 테스트는 FakeCollector(인프로세스 gRPC)와 대조, 서버 테스트는 가짜 에이전트와 대조. "보낸 수"가 기준값 | 양쪽 실제 | 책임 분리, 결정성 | D-13 |
| DSN-18-05 | 변환 클래스 베이스라인 파일을 저장소에 커밋, 추가 시 실패 | 로그만 | 허용목록 밖 변환을 PR에서 잡는다 | F-A07, N-27 |
| DSN-18-06 | 정확성 테스트는 D-nn과 번호 1:1, 경량판은 매 커밋 | 선택적 | 규칙 하나당 테스트 하나를 보장 | D-01~26 |
| DSN-18-07 | 벤치는 open model·A-B-A-B-A-B·신뢰구간, 변동계수 2% 초과 시 INVALID | 단일 실행 | 1ms·5% 수준 차이를 잡음과 구분 | N-01~04 |
| DSN-18-08 | 카오스는 toxiproxy + docker 조작 | 수동 | 재현 가능·CI 실행 가능 | N-05 |
| DSN-18-09 | PII·XSS 시드는 센티널 `ARGOS_SEED` 포함, 저장소 전 컬럼 LIKE 스캔 | 필드별 단언 | 새 필드가 생겨도 유출을 잡는다 | N-10c, SEC-62 |
| DSN-18-10 | E2E 필수 요소는 YAML 데이터 주도, 05 §2 항목 ID와 1:1 | spec에 하드코딩 | 05 문서 변경이 테스트 변경을 강제 | 03 3.3 |
| DSN-18-11 | 매트릭스·벤치·카오스·soak은 사내 self-hosted 신뢰 브랜치 전용, 포크 PR은 GitHub-hosted만 | 공용 러너 | O-S05 격리 | O-S05 |
| DSN-18-12 | 저장소 생성기·러너는 Java(ClickHouse 클라이언트 공유), 스케일 파라미터로 CI·전체 겸용 | Python | 툴체인 단일화, 라이터 경로 재사용 | P-4 |
| DSN-18-13 | 자동 재시도 금지, 격리 7일, 격리 10개 초과 시 릴리스 금지 | 재시도 3회 | 플레이키가 정확성 신뢰를 갉아먹지 않게 | 03 1장 |
| DSN-18-14 | 커버리지는 리뷰 지표(핵심 85/70, 기타 70), 게이트 아님 | 게이트 | 숫자 맞추기용 테스트 방지 | - |
| DSN-18-15 | 테스트 전용 설정 `argos.test.*`는 릴리스 빌드에서 무시되고 그 사실을 테스트 | 항상 활성 | 운영 JVM에 테스트 훅 노출 금지 | SEC |

## 18. 미결·가정

| # | 항목 | 가정·처리 |
|---|---|---|
| 1 | 상용 DB(Oracle 사내·Tibero·Altibase·CUBRID·DB2) 확보 | 미확보 시 △ 표기. `gvenzl/oracle-free`로 Oracle 기본 검증은 가능하다고 가정(착수 시 이미지 라이선스·가용성 확인) |
| 2 | 벤치 머신 2대(앱·부하기, 같은 L2, governor 고정) | 사내 제공 전제. 가상 머신이면 CPU 핀닝·전용 호스트 필요 |
| 3 | 측정 잡음 임계(off CV 2%, p99 신뢰구간) | 첫 벤치 결과로 재조정 |
| 4 | k6(AGPL)·toxiproxy(MIT)·ZAP(Apache-2.0)·testssl.sh(GPL) | 모두 개발 도구 전용, 배포물 미포함. `docs/licenses/dev-only.md` 기록 |
| 5 | `-XX:+BytecodeVerificationLocal`이 JDK 25에서 유지 | 착수 시 `-XX:+PrintFlagsFinal` 확인 |
| 6 | 가상 스레드 CPU·alloc이 -1(예외 아님) | JDK 21·25 실측 후 DSL `isMissing()` 구현 조정 |
| 7 | `GC.class_histogram`의 `io.argos.` 합계가 힙 상주의 충분한 근사 | 셰이딩 패키지(`io.argos.agent.shaded.`) 포함. 바이트 배열 등 공유 객체는 자가 진단 `direct_memory_bytes`·큐 바이트로 보완 |
| 8 | Boot 2.7 × JDK 25 | 미검증(△)으로 실행만 |
| 9 | 매트릭스 전체 2시간·nightly 4시간 예산 | 병렬도 4 기준. 러너 사양에 따라 조정 |
| 10 | Tomcat manager API로 WAR 재배포 50회 | 매트릭스 컨테이너에 manager 앱 활성 |
| 11 | ZAP의 WebSocket 커버리지 | 제한적. WS는 T-SEC-44 자체 테스트가 주 |
| 12 | 에어갭 패킷 캡처 방법 | 격리 네트워크 네임스페이스에서 `tcpdump` 외부 목적지 0건. 17 문서와 조율 |

## 19. 다른 설계 문서에 요구하는 것

| 대상 문서 | 요구 | 이유 |
|---|---|---|
| 10 에이전트 | 테스트 전용 프로퍼티 네임스페이스 `argos.test.*`(릴리스 빌드 무시): `exporter=file:<dir>|noop|grpc`, `faults=<spec>`(advice-throw·transform-error·helper-nocdef), `debug.transform-log=<file>`(변환 클래스·onError·ClassCircularityError 기록) | 5.4·5.6·5.7 |
| 10 에이전트 | 자가 진단 결과에 `threadlocal_contexts_live`, `active_registry_size`, `weakmap_size`, `agent_threads_cpu_seconds_total`, `direct_memory_bytes`, `premain_ms`, 모듈별 `disabled`·에러 카운트, 드롭·제외·orphan 카운터 | 5.9, 7.4, T-ACC |
| 10 에이전트 | 에이전트 스레드 이름 접두 `argos-` 고정, `ArgosThread` 서브클래스 | 5.8 |
| 10 에이전트 | 결측 값은 proto optional 부재로 표현(0 금지) | DSL `isMissing()` |
| 11 프로토콜 | proto 스텁을 별도 Gradle 아티팩트(`argos-proto`)로 발행해 FakeCollector·가짜 에이전트가 의존 | 5.3, 8장 |
| 11 프로토콜 | 시계 오프셋 측정 필드(`sent_at`·`received_at`)와 `batch_seq`가 테스트에서 주입 가능 | T-ACC-02·13 |
| 12 서버 | 테스트 전용 설정 `argos.testing.*`: 상한·쿼터 축소값, 실패 주입(5xx·RESOURCE_EXHAUSTED·MV 실패), WAL 경로 오버라이드, 야간 정합 검사 즉시 실행 API | 8장, T-ACC-13·14, T-CH-004 |
| 12 서버 | 수집 상태 API에 `gaps[]`, 드롭 사유별 카운터, 늦은 도착·시계 보정 수 | T-ACC-06·15·18 |
| 12 서버 | 알림 채널 Mock(웹훅 수신기·SMTP 수신기)을 테스트 프로파일에 포함 | T-SV-030, PII 수신함 |
| 13 저장소 | DDL·롤업 SELECT 파일 경로를 생성기가 읽어 스키마를 단일 소스로 | 9장 |
| 13 저장소 | Q1~Q20 쿼리 파일과 기준값을 13 문서가 소유하고 18은 러너만 | 9장 |
| 14 API | 응답에 `source`, `shown/total`, `profile_status`, `gaps` 필드 | T-API, T-ACC |
| 15 UI | `data-testid` 규약(05 §2 항목 ID 기반), i18n 키로 텍스트 단언, 앱이 `window.__xss`를 정의하지 않음, dev 빌드 CSP `report-uri` 로컬 엔드포인트, `?kiosk` 등 URL 상태 스키마 공개 | 13장 |
| 17 배포·운영 | Compose `test` 프로파일(toxiproxy·ext-stub·채널 Mock 포함), 에어갭 테스트용 격리 네트워크 절차, 설치 스크립트가 노출 포트·`.env` 권한을 테스트 가능하게 | 11장, T-NFR-12·17, T-SEC-74·75 |
| 19 빌드·릴리스 | 2.2 태스크·소스 세트 정의, CI 잡 분리와 self-hosted 라벨(`argos-matrix`·`argos-bench-*`·`argos-storage`), 포크 PR 격리, 아티팩트 보존 90일, 추적성 보고서 생성 단계, 2회 빌드 해시 비교, `verifyAgentJar`를 `shadowJar` finalizer로 | 2장, 15장 |
| 03 완성 기준 | Gate 표의 각 행에 이 문서의 테스트 ID를 증빙으로 추가(PM 반영) | 16.3 |
| 04 작업 지시서 | WO 수락 기준을 테스트 ID로 적고, WO-01(샘플 앱)·WO-18(매트릭스·벤치)에 4·6·7장을 입력으로 | 전체 |

## 부록 A. 테스트 카탈로그 (ID 예약)

WO 수락 기준은 아래 ID를 인용한다. 번호는 예약이며 구현 시 세부 케이스는 `T-AG-031a`처럼 접미사로 늘린다. 10·12·13·14·15 문서가 자기 영역의 ID를 추가·세분할 수 있고, 이 부록은 PM이 통합 시 갱신한다.

### A.1 에이전트 (T-AG)

| ID | 내용 | 계층 | 요구 |
|---|---|---|---|
| T-AG-001 | Advice 클래스에 람다·invokedynamic 없음(ArchUnit) | 변환 검증 | F-A15 |
| T-AG-002 | Advice에 `skipOn`·`readOnly=false`·`MethodDelegation` 참조 없음 | 변환 검증 | F-A15 |
| T-AG-003 | Advice에 `synchronized`·블로킹·리플렉션·`new Throwable()` 없음 | 변환 검증 | F-A15, N-01 |
| T-AG-004 | `TraceContext`·step 빌더 필드 타입이 bootstrap·primitive·String만 | 변환 검증 | F-A36 |
| T-AG-005 | 변환 클래스 목록 = 베이스라인, `onError`·`ClassCircularityError` 0 | 변환 검증 | N-27 |
| T-AG-006 | `-XX:+BytecodeVerificationLocal`로 샘플 앱 기동·요청 정상 | 통합 | F-A15 |
| T-AG-010 | 요청 1건 → 트랜잭션 1건, 서비스 이름 `GET /api/orders/{id}` | 통합 | F-A01·A02 |
| T-AG-011 | 디스패치 5종(예외·404·sendError·forward·async) 각 1트랜잭션, `/error` 0, 최종 상태 | 통합 | F-A28, D-04, D-21 |
| T-AG-012 | 제외 규칙 기본값 100회 → 트랜잭션 0, 카운터 100 | 통합 | F-A23, D-19 |
| T-AG-013 | 폴백 서비스 이름 정규화(`/api/x/12345` → `/api/x/{*}`), 사전 10,001번째 → `_other_` | 통합 | F-A29 |
| T-AG-014 | 쿼리스트링 기본 제거·whitelist·full 모드 | 통합 | SEC-34 |
| T-AG-015 | 멀티 웹앱 컨텍스트 경로 접두 | 매트릭스 | F-A36 |
| T-AG-016 | SSE 첫 flush 종료·streaming 플래그, WS 101 종료, max-duration 강제 종료 | 통합 | F-A34 |
| T-AG-017 | 헤더 화이트리스트·user id 모드(off/plain/hashed)·IP 모드 3종·XFF trusted-proxies | 통합 | SEC-31·32·37 |
| T-AG-020 | Spring MVC `bestMatchingPattern` 읽기, Spring 없는 앱 `HttpServletMapping` 폴백 | 통합 | F-A02 |
| T-AG-030 | JDBC: prepareStatement SQL 캡처, execute exec_ms, fetch_ms·rows 분리 | 통합 | F-A03 |
| T-AG-031 | 래퍼 4단 중첩에서 SQL step 1개, `sql_count` 1 | 매트릭스 | D-24 |
| T-AG-032 | `getConnection` DBC step(풀 이름), COMMIT·ROLLBACK step, 배치 step | 통합 | F-A03 |
| T-AG-033 | 바인드 파라미터 masked/partial/full, 타입 화이트리스트, LOB 비접근, 256자·64개 | 통합 | F-A04, SEC-3A |
| T-AG-034 | 정규화 50케이스(`normalization-cases.yaml`), `<unparsed>` | 단위 | D-22, SEC-35 |
| T-AG-035 | JDBC URL 파서 9벤더(Tibero·Altibase·CUBRID 포함) | 단위 | F-A03 |
| T-AG-036 | 트랜잭션 밖 SQL은 미기록 + `sql_outside_tx_total` | 통합 | D-20 |
| T-AG-040 | Advice 예외 주입 → 앱 2xx, 에러 카운터 증가 | 통합 | F-A15 |
| T-AG-041 | transform 오류 11회 → 모듈 disabled, WARN 1회, 타 모듈 정상 | 통합 | F-A15 |
| T-AG-042 | `exclude-classes` 글롭 → 변환 로그에 부재 | 통합 | F-A15 |
| T-AG-043 | 킬 스위치 1단(`argos.enabled=false`) 즉시 return, 2단(`agent.disabled`) 미설치 | 통합 | F-A15 |
| T-AG-044 | 초기화 예외 주입 → 앱 정상 기동, 로그만 | 통합 | F-A15 |
| T-AG-045 | 로그 디렉터리 읽기 전용 → stderr 폴백 분당 ≤ 10줄 | 통합 | F-A15 |
| T-AG-046 | retransform 상한(클래스 50·분당 1회) | 통합 | F-A15 |
| T-AG-050 | 재진입: `argos-` 스레드 기원 step 0, 셰이딩 클래스 미변환 | 통합 | F-A15 |
| T-AG-051 | Executor 허용목록 밖 미변환, `remove()`·`cancel()` 보존, 병렬 스트림·`ThreadPerTaskExecutor` 전파 | 통합 | F-A07 |
| T-AG-052 | CompletableFuture·@Async 하위 step이 부모에 결합, 풀 스레드 재사용 오염 0 | 통합 | F-A07 |
| T-AG-053 | 컨텍스트 누수 4종(5.9) | 통합·매트릭스 | N-02 |
| T-AG-054 | 가상 스레드: 트랜잭션 수 동일, CPU·alloc 결측, 이름 사전 ≤ 1,000, 덤프에 VT 포함 | 매트릭스 | F-A30, D-23 |
| T-AG-055 | WebClient `block()` 지점 전파, WebFlux 앱 메트릭만 | 통합 | F-A33 |
| T-AG-056 | HTTP 클라이언트 6종 step·헤더 주입, 실패 분류 3종 | 통합 | F-A05 |
| T-AG-057 | 기존 `traceparent` 보존, `tracestate` 벤더 엔트리, `exclude-hosts` | 통합 | F-A32, SEC-38 |
| T-AG-056a~d | (WO-14 세분, ADR-0010 C-223 (9)) a: 실패 분류(원인 기준, 원인 체인 8단계, 순환·던지는 `getCause()` 안전, Netty `ConnectTimeoutException`도 TIMEOUT C-223 (3)) / b: HOST 사전 원문 `scheme://host:port`(C-142)·step detail(쿼리·프래그먼트·`;` 매개변수 제거, 4KiB 절단) / c: HTTP step(이름·대상 = HOST id, 상태·실패, 리프 step, 외부 호출 합계 동시성 정확) / d: 배치의 WO-14 필드(`incoming_trace`·부모 span·`caller_app_id`·`ext_count`/`ext_time_ms`). 모듈 단위 시험은 접미사 `m` | 단위 | F-A05, C-142 |
| T-AG-057a~d | (WO-14 세분, C-223 (9)) a: `traceparent` 파싱·생성(55자, 입력 256자 상한)·보존한 앱 `traceparent`를 step detail에 덧붙임 / b: `tracestate` 검사(512자·32멤버 초과는 통째로 버림)·`argos` 엔트리 형식·`caller_app_id` / c: 들어온·나가는 전파(앱 `traceparent` 보존, `argos-state.hosts`일 때만 `argos` 엔트리, 첫 ServerHello 전 엔트리 없음) / d: 서블릿 들어온 헤더(`traceparent` 없으면 `tracestate` 안 읽음, 여러 줄 513자·32줄 상한). 모듈 단위 시험은 접미사 `m` | 단위 | F-A32, SEC-38 |
| T-AG-056m | (WO-16, ADR-0010 C-224 (1)·C-229 (2), 클라이언트 모듈 단위) OkHttp 3.14.9·4.12.0 `callTimeout`: 취소된 호출 + `Call.timeout()` 설정 + 경과 ≥ timeout − min(20ms, timeout/2)이면 TIMEOUT, 앱이 받는 예외는 바꾸지 않음(`OkHttpCallTimeoutTest`). 다른 클라이언트 모듈의 단위 시험도 이 접미사를 쓴다 | 단위 | F-A05 |
| T-SEC-38 | (에이전트 측 보안, WO-14) 실제 샘플 앱·ext-stub에서 클라이언트별 `exclude-hosts` 호스트에 두 헤더 부재, 기본 설정에서 `tracestate` `argos` 엔트리 미주입(`PropagationHeadersTest`) | 통합 | SEC-38 |
| T-SEC-38a | (WO-14 세분) 호스트 글롭(`*`·`?` 대소문자 무시, 정확 호스트·IP·IPv6·끝 점, 글롭 256개·256자 상한, 역추적 없는 선형 매칭)·기본값(글롭 없음 = 매칭 없음, `argos` 엔트리 없음)·`argos-state.hosts` 밖으로 들어온 `argos` 엔트리 미전달. 모듈 단위 시험은 `T-SEC-38m` | 단위 | SEC-38 |
| T-AG-058 | `@Scheduled`·Quartz·Spring Batch Step → kind=BATCH, 청크 요약 | 통합 | F-A24 |
| T-AG-060 | 로그 허용목록: ERROR 0, 허용 외 WARN 0, stdout/stderr 0바이트 | 통합 | F-A35 |
| T-AG-061 | 전수 통계 10초 창 집계가 실제 실행 수와 일치 | 통합 | F-A22, D-12 |
| T-AG-062 | JVM·풀·Tomcat 메트릭이 JMX off에서 수집, GC 이름 매핑표 전부 | 통합·매트릭스 | F-A09·A10, D-25 |
| T-AG-063 | cgroup v1·v2 한도 기준 사용률, `container_*` 지표 | 매트릭스(컨테이너) | F-A11 |
| T-AG-064 | 느린 스택 캡처 상한 10·깊이 64·일괄 getThreadInfo·비용 보고 | 통합 | F-A19 |
| T-AG-065 | MDC 주입 4프레임워크, 앱 `MDC.clear()` 무영향 | 통합 | F-A20 |
| T-AG-066 | 버전 태그(설정·매니페스트), env 태그 필수 | 통합 | F-A21·A25 |
| T-AG-067 | AgentBye 후 1초 플러시, 서버 수신 | 통합 | F-A26 |
| T-AG-068 | `--check` 표·종료 코드(연결·TLS·토큰·시계·JDK) | 통합 | F-A27 |
| T-AG-069 | 하드 캡: 불허 키 거부·허용 키 적용 | 통합 | SEC-23 |
| T-AG-070 | JDK 17·21·25 `HttpURLConnection`·`ThreadPoolExecutor`·`CompletableFuture` 계측 `IllegalAccessError` 0 | 매트릭스 | F-A31 |
| T-AG-071 | 전송: in-flight ≤ 4, `isReady` 백프레셔, 큐 초과 드롭·카운터, `batch_seq` 단조, 8u252 TLS | 통합 | DSN-02-11 |
| T-AG-072 | 환경 덤프 마스킹 30케이스 + 시드 0건 | 통합 | SEC-20 |

### A.2 서버 (T-SV)

| ID | 내용 | 계층 | 요구 |
|---|---|---|---|
| T-SV-001 | 빈 DB 기동·마이그레이션 멱등(2회) | 통합 | N-22 |
| T-SV-002 | 이전 버전 스키마 + 1/100 데이터에서 마이그레이션, 각 ≤ 10분 | 통합(nightly) | N-22 |
| T-SV-010 | WAL 선기록 후 ack, `kill -9` 복구 재적재 | 통합 | D-13 |
| T-SV-011 | dedup 토큰으로 재삽입 100회 → 원본·롤업 불변 | 통합 | D-13 |
| T-SV-012 | 수신 상한 5종 거부 코드·카운터 | 보안 | SEC-11 |
| T-SV-013 | 인스턴스 쿼터 드롭 구분 표시 | 보안 | SEC-12 |
| T-SV-014 | 사전 폭증·인스턴스 등록 상한 | 보안 | SEC-13·14 |
| T-SV-015 | 타임스탬프 가드·늦은 도착 실시간 미발행 | 통합 | D-15 |
| T-SV-015b | 실시간 버스가 수신보다 60초 넘게 오래된 점을 발행하지 않음(`argos_realtime_dropped_total{reason=late}`) | 단위 | D-15 |
| T-SV-016 | 에러 그룹 지문(마스킹 후) 계산·`error_group_id` 기록 | 단위·통합 | F-S19 |
| T-SV-016e | 에러 그룹 대표 스택: 첫 ERROR step의 프레임을 다시 마스킹(이메일·IPv4)·줄바꿈 제거해 `sample_stack`에 한 번 기록, 늦게 온 프레임 사전은 채워질 때 기록, 실행당 100그룹 상한, CH·PG 읽기 실패 카운트 | 단위·통합 | F-S19 |
| T-SV-017 | Apdex T 행 기록, T 변경 이후 적용 | 통합 | D-17 |
| T-SV-017b | 서비스 Apdex T 오버라이드: 해당 서비스에만 적용(id 부호 없는 비교), 앱당 1,000개·T 1~65535 상한, `xlog.apdex_t_ms`는 수신 시점 스냅숏의 오버라이드 또는 앱 T | 단위 | D-17, F-S16 |
| T-SV-017c | WAL 페이로드: 오버라이드 없으면 형식 1과 바이트 동일, 있으면 형식 2, 빈 항목·1,000개 초과·중복 id·잘린 목록은 거부 | 단위 | D-17, C-129 |
| T-SV-018 | 전수 통계 적재·1m·1h 롤업 | 통합 | F-S21 |
| T-SV-019 | 야간 정합 검사 탐지·재구축, `rollup rebuild` 바이트 동일 | 통합 | D-14, F-S22 |
| T-SV-020 | 수집 지연: 수신 → WS 1초, → 조회 5초 | 통합 | N-15 |
| T-SV-020d | 실시간 버스 xlog 100ms 묶음: 한 번 비우면 앱당 한 묶음(도착 순), 발행한 점을 100ms 라운드 안에 push | 단위 | N-15 |
| T-SV-020e | 실서버 WS(E2E `wsMode: 'server'`): 에이전트를 붙인 shop-boot3 요청이 XLog 실시간 화면에 실제 xlog push로 표시되고(저장 행과 tx id 일치), 인스턴스 상태가 REST·counter 토픽에서 UP | E2E | N-15 |
| T-SV-020f | counter push의 JVM 값(C-212 (1)): 5초 창의 `heap_used`(인스턴스 합)·`gc_pause_ms`(증가 합)·`cpu`(평균), 표본 없으면 null, 닫힌 초·5초 넘게 앞선 표본과 상한 밖 표본은 제외하고 `argos_realtime_dropped_total{reason=metric}`으로 셈(C-220 (9)), 실시간 경로만 발행 | 단위 | F-S03, D-23 |
| T-SV-020g | FakeAgent MetricBatch → 실서버 counter push: heap·GC·CPU 값이 보낸 값과 같고 null 아님, 90초 늦은 표본은 push 안 함 | 통합 | F-S03, D-23 |
| T-SV-020h | (WO-25, ADR-0010 C-226 (13)) `alert` 토픽은 제어 프레임: 같은 구독에 전이 2건이 모두 남고(최신 병합 없음) 발행 순서·`t`·`topic=alert`가 맞으며, app·env 필터와 서버 자기 알림(app null)은 필터 없는 구독에만, 작은 outbox 예산에서도 잘리지 않음(`RealtimeBusTest`) | 단위 | F-S06 |
| T-SV-020i | (WO-25, C-226 (13)) overview 행의 `firing`이 알림 엔진 보고(`updateFiring`)에서 온다: 보고 전 null, 보고 뒤 `{critical, warn}`(항목 없는 앱 0/0, 입력 맵 복사), 빈 보고면 모두 0/0(`RealtimeBusTest`) | 단위 | C-212, F-U17 |
| T-SV-021 | 실시간 버스 느린 구독자 최신 우선 드롭 | 통합 | F-S03 |
| T-SV-021a | 느린 구독자는 가장 오래된 xlog push부터 잃고 notice `backpressure`와 드롭 카운터를 받음 | 단위 | F-S03 |
| T-SV-021b | 같은 토픽의 빠른 구독자는 모든 점을 받음(드롭 0) | 단위 | F-S03 |
| T-SV-021c | counter·active·overview는 느린 구독자에게 최신 push만 유지 | 단위 | F-S03 |
| T-SV-021d | `publishXlog`는 블로킹하지 않음: 막힌 구독자는 버스 스레드만 지연, 큐 가득은 드롭·카운트 | 단위 | F-S03 |
| T-SV-021e | 제어 프레임과 최신 우선 push는 드롭하지 않고 xlog push부터 버림 | 단위 | F-S03 |
| T-SV-022 | 인스턴스 상태 UP/STALE/DOWN/WARN 전이·이벤트. 전체 현황·앱 목록의 `status`(최악 상태)·`instances_up`(UP + WARN)과 `GET /apps/{id}/active`의 `per_instance.state`가 상태 머신을 따름(WO-31, C-216 (3)·C-218 (2)(4)) | 단위·통합 | F-S28, F-U17 |
| T-SV-023 | AgentBye 후 5분 내 재접속 시 다운 알림 없음, 기동 유예 3분 | 통합 | F-S25, S-17 |
| T-SV-030 | 알림: 조건 충족 → 전송 요청 ≤ 30초 | 통합 | N-23 |
| T-SV-031 | 설정 푸시·킬 스위치 50 인스턴스 ≤ 10초, 결과 집계 | 통합 | N-25 |
| T-SV-032 | 상태 머신(지속·복구 임계·NO_DATA·최소 표본·2단계·재알림·ACK) | 단위 | F-S17·S25·S26 |
| T-SV-033 | 템플릿 14종 생성·"권장 규칙 적용"·차이 표시 | 통합 | F-S24 |
| T-SV-034 | 채널 전송 실패 재시도 3회·다른 채널 자기 알림 | 통합 | F-S27 |
| T-SV-035 | 웹훅 목적지 검증·서명·SMTP STARTTLS | 보안 | SEC-51~54 |
| T-SV-036 | 알림 지표 확장 전부 계산 가능 | 단위 | F-S31 |
| T-SV-040 | 토큰 발급(1회)·해시·만료·회전·폐기 즉시 종료 | 보안 | SEC-01~05 |
| T-SV-041 | 명령 TTL·동시 1건·권한 분리·결과 암호화·UUID | 보안 | SEC-21~24 |
| T-SV-042 | 설정 변경 통제: 사유 필수·diff·인스턴스별 결과·되돌리기·drift | 통합 | F-S29 |
| T-SV-043 | 앱 등록 정책(auto-register off → 승인 대기) | 통합 | F-S30 |
| T-SV-044 | 감사 이벤트 카탈로그 스냅샷·불변·syslog | 보안 | SEC-80~82 |
| T-SV-045 | 개인정보 삭제 API·뮤테이션 상태 | 보안 | F-S32 |
| T-SV-046 | 저장소 상태 지표·디스크 90% 비상 보존 단축 | 통합·카오스 | F-S23 |
| T-SV-047 | 자기 메트릭 Prometheus 노출·인증 | 통합 | F-S20, SEC-55 |
| T-SV-048 | 설정 토큰 부트스트랩·비밀번호 정책·잠금 | 보안 | SEC-47·48 |
| T-SV-049 | 금지 DB 비밀번호 기동 거부 | 보안 | SEC-74 |

### A.3 저장소·API·UI·빌드·NFR (T-ST / T-API / T-UI / T-BLD / T-NFR)

| ID | 내용 | 요구 |
|---|---|---|
| T-ST-001~020 | Q1~Q20(13 문서 소유 기준값) | N-07, Gate 0 |
| T-ST-030~033 | 1/100 CI: 생성·적재·Q12·Q13·Q15·Q16 정합 0 | D-03·12·13 |
| T-ST-040 | TTL 동작·보존 변경 리허설(연장 MODIFY TTL, 단축 DROP PARTITION) | N-08 |
| T-ST-041 | 코덱·압축률 테이블별 기준 | N-19 |
| T-ST-042 | 백분위 상태 병합 값 오차(1m·1h·1d). 적재 데이터 p99는 버킷별로 꼬리 표본(n × 0.01) 100 미만이면 순위 오차 ≤ 0.5pp, 이상이면 값 오차 ≤ 5%, 값 판정 버킷 0개면 실패(C-219). ci 프로파일은 모든 버킷을 순위 오차 ≤ 0.5pp로 판정하고 값 오차는 기록만(C-221) | A-11 |
| T-ST-050 | 행당 바이트 실측 vs 공식 ±30% | N-19 |
| T-ST-051 | ClickHouse 이전 LTS → 현재 업그레이드, 롤업 읽기 정상 | N-21 |
| T-ST-052 | 마이그레이션 두 경로·MV 4단계 교체 | N-22 |
| T-ST-053 | `argos_reader` 쿼리 상한·`system.query_log` 검증 | 03 3.2 |
| T-ST-054 | 삭제 뮤테이션 소요(일 파티션) | SEC-33 |
| T-API-001 | OpenAPI 린트·`x-argos-role` 전수 | SEC-49 |
| T-API-002 | 호환 diff가 breaking 탐지(필드 제거·타입 변경) | O-R03 |
| T-API-003 | 소스 선택 규칙·`source` 응답·마지막 2분 원본 | 02 §6.3 |
| T-API-004 | 범위 상한·행 제한·타임아웃·에러 본문 표준 | 03 3.2 |
| T-API-005 | ID 단건 조회(ts 복원), trace 24h 기본 | D-11 |
| T-API-006 | XLog 10만 점 초과 결정적 샘플링 `shown/total` | A-5 |
| T-API-007 | WS 프레임 스키마·구독 상한·인증 | SEC-44 |
| T-API-007a | WS 클라이언트 프레임(14 §5.2) 파싱: subscribe·unsubscribe·ping, 잘못된 프레임은 `invalid_params`(유효한 id는 유지, 보낸 값은 되돌리지 않음) | SEC-44 |
| T-API-007b | 구독: ack(id·topic·since) 뒤 push, 구독 20개 상한(21번째 `limit_exceeded`, 연결 유지), 연결당 구독 레이트 리밋(용량 20·초당 1), `topic_unknown`, admin 토픽 권한·역할 강등 시 해당 구독만 종료, ping → pong | SEC-44 |
| T-API-007c | 연결 상한: 메시지 4,096 UTF-8 바이트 처리·4,097바이트 close 4413, 60초 무수신 close 4408(59초 유지), 사용자당 5연결(6번째 4429), 서버 종료 시 1012 | SEC-44 |
| T-API-007d | 서버 프레임 벡터 고정본 = 서버가 쓰는 프레임(모든 종류), 생성 결과 결정적 | SEC-44 |
| T-API-008 | 웹훅 페이로드 `schema_version`·템플릿 변수 집합 | SEC-39, O-R03 |
| T-API-009 | 설정 키 스냅샷(에이전트·서버) | O-R03 |
| T-API-010 | 권한 매트릭스 생성 실행 | SEC-49 |
| T-API-011a | (WO-36, ADR-0010 C-222 (3)·(9)) sql·external 통계 `sort=p95_ms`가 행의 버킷 p95(C-14) 순서(내림·오름), 생성기 데이터에서도 내림차순(`StatsScreenParamsIT`, 실제 CH·PG) | F-U05·U06, 14 §4.8 |
| T-API-011b | (WO-36, C-222 (3)·(9)) errors/groups `compare_prev`: `delta.count` = (현재 − 직전) / 직전, 직전 0이면 null, 생성기 데이터에서 직접 계산한 값과 일치 | F-U07, 14 §4.8 |
| T-API-011c | (쌍 9 마무리, ADR-0010 C-222 (7)) `external/{hostId}` 시계열에 버킷별 `timeouts`·`connect_failures`(덮인 빈 버킷 0), 다른 통계 시계열에는 없음(`StatsScreenParamsIT`, 실제 CH·PG). 14 §4.8 정정(비파괴 가산) | F-U05, 14 §4.8 |
| T-API-012a | (WO-38, ADR-0010 C-227 (3)·C-228 (2)(7)) 알림 응답 추가 필드: 실제 엔진의 모든 이벤트에 `reason`(닫힌 목록, V009 이전 행 null), events·`/overview/alerts`·대시보드 `firing[]`의 `rule` {id, name, metric, unit}, 규칙 이력 `changed_by_name`, `AlertRuleRef.metric`·`unit` 열거값 = `AlertMetric`(`AlertsApiIT`, `AlertSchemaEnumsTest`) | F-S17, F-S26 |
| T-UI-001 | XLog 좌표·선택 영역 계산·결정적 샘플 표시 | F-U02 |
| T-UI-002 | 시간 범위 상태 리듀서(드래그·프리셋·이동·실시간 해제) | 05 §2.0 |
| T-UI-003 | URL ↔ 상태 직렬화·스키마 검증·1KB 상한 | F-U14, SEC-64 |
| T-UI-004 | 표 공통(정렬·열 필터·값 클릭 필터·CSV 접두) | F-U16, SEC-63 |
| T-UI-005 | 수집 데이터 렌더 컴포넌트 XSS 페이로드 → 텍스트 | SEC-61 |
| T-UI-006 | 하드코딩 문자열 린트, en 키 누락 0, ko 용어표 적용 | F-U13 |
| T-UI-007 | 결측 표시(`—`/"측정 불가")가 0과 구분 | D-23 |
| T-UI-008 | 상태 모델 색 매핑, 알림 상태 라벨(ko) | F-S28, 부록 B |
| T-E2E-090~099 | 전체 현황(WO-31): 1366×768 30개 앱 무스크롤, 환경·그룹 필터, 카드/표 전환 유지, 키오스크, 앱별 이름·10개 항목 = API 응답(결측은 결측), 정렬·이상 앱 상단 고정, FIRING 패널, 서버 상태 패널, 행 클릭 시 범위 유지, 실서버 WS 갱신 | F-U16·U17, T-ACPT-16 |
| T-E2E-090a~d | 전체 현황 시각 기준 이미지(e2e:docker): 로딩(2초 뒤 안내 'Still loading…', C-218 (4))·앱 없는 환경·오류(요청 ID·재시도)·정상 | F-U16, 05 §2.0-8 |
| T-E2E-stats-1~10 | 통계 화면(WO-36, ADR-0010 C-222 (9)): `required/stats.yaml`의 05 §2.5 체크리스트(2.5-1 표 공통 4종·2.5-2 서비스 상세·2.5-3 SQL 상세·2.5-4 외부 호출 호스트별 timeout·connect fail·2.5-5 에러 분석). 8(맵 외부 노드 링크)은 owner WO-70(F-U11), 10(대표 스택 앱 프레임 굵게)은 owner WO-26, 둘 다 컴포넌트·fixme까지 | F-U05~U07, 05 §2.5 |
| T-E2E-stats-20~28 | 20~23: STATISTICS 메뉴 4종이 실화면(= API, 1366×768 가로 스크롤 없음, axe Critical 0, 증거 스크린샷) / 24: 키보드 목록 → 상세 → 뒤로(범위 유지) / 25~27: T-ACPT-03(SQL 1일 Top N, 배치 제외)·T-ACPT-11(호스트별 timeout·connect fail = API)·T-ACPT-01 마지막 구간의 자동 부분 / 28: 시드 XSS 서비스 이름이 텍스트 | F-U05~U07, SEC-61 |
| T-E2E-stats-30~36 | 통계 시각 기준 이미지(e2e:docker): 로딩·빈 범위(다음 행동)·API 상한 초과 오류(요청 ID)·4종 목록 정상 | F-U16, 05 §2.0-8 |
| T-E2E-stats-37~39 | fixme(owner 명시): 37 에이전트가 분류한 TIMEOUT·CONNECT_FAIL의 외부 통계(owner WO-16, C-223 (7)), 38 대표 스택 앱 프레임 굵게(owner WO-26), 39 외부 호출 → 맵 외부 노드 링크(owner WO-70, F-U11) | F-U05~U07, F-U11 |
| T-E2E-service-version-1·2 | T-ACPT-04: 서비스 상세의 버전별 p95(= `by_version`)와 배포 마커(= `GET /events`), 목록의 버전 분리 | F-U05 |
| T-E2E-errors-1~4 | T-ACPT-13: 1 대시보드·메뉴·new 필터에서 3클릭 안에 신규 그룹 대표 스택 / 2 new 목록 = `new_only=true`, 버전·인스턴스 분포 = API / 3 최근 10건 = `recent_tx`, 상세 링크 / 4 실데이터 대표 스택이 비어 있지 않음(fixme, owner WO-19, C-222 (1)) | F-U07 |
| T-E2E-active-1~12 | 액티브 트랜잭션(WO-35, ADR-0010 C-225 (8)): `required/active.yaml`의 05 §2.6 체크리스트(필수). 인스턴스 막대·경과 4구간(= 마지막 push·REST), 경과 색 임계 설정, 막대 클릭 `f.instance`, 9개 열(수집 안 함 표시), 경과 정렬·5초 갱신·서버 시계 'N초 전', 일시 정지·재개, 상태·서비스·종류 필터, 스택·스레드 덤프(명령 API 전까지 사유와 함께 비활성, WO-26·WO-17), 최근 완료 링크, 규칙 링크, 갱신 멈춤 배지 | F-U04, 05 §2.6 |
| T-E2E-active-20~31 | 20~23: 실서버(개발 전용 FakeAgent 피더 `:server:activeFeedJar`, C-225 (1)) 화면 값 = 실제 push·REST, 5초 갱신·'N초 전' ≤ 11초(C-225 (6)), 일시 정지, 필터 → URL / 24·25: 스텁 갱신 멈춤·재연결, WARN·STALE 배지·잘림 안내 / 26: 사용자 임계 2/5/10초(localStorage, C-225 (3)) / 27: 8초 이상 행의 명령 비활성 사유·최근 완료 XLog 링크 / 28: 수집 문자열 XSS → 텍스트 / 29: 1366×768 가로 스크롤 없음·axe Critical 0 / 30: fixme T-ACPT-02 스택 30초(owner WO-26·WO-17·WO-37) / 31: fixme 실제 에이전트 스냅숏(owner WO-16) | F-U04, SEC-61, T-ACPT-02 |
| T-E2E-active-31a·31b | (WO-16, ADR-0010 C-225 (1)·C-229 (9)) 실제 에이전트의 액티브 스냅숏: a 단위(`ActiveSnapshotterTest`·`PreparedBatchDictionaryTest`) 경과 내림차순·최대 500개·고정 크기 힙·상태(IN_SQL sql_id·IN_DBC_WAIT·IN_HTTP·IN_ASYNC)·캡처 모드 적용된 user_id·remote_ip·SQL 문 사전 선전송·`max_active_items` / b 통합(`ActiveSnapshotE2eTest`) 샘플 앱의 느린 요청이 IN_SQL과 sql_id 사전과 함께 5초 안에 보고됨 | F-A10, F-U08 |
| T-E2E-active-40~43 | 액티브 시각 기준 이미지(e2e:docker): 로딩·연결된 인스턴스 없음·오류(요청 ID)·정상(고정 스텁 스냅숏, 느린 행 선택) | F-U16, 05 §2.0-8 |
| T-E2E-alerts-1~3 | 알림(WO-25, C-226 (13)): 1 앱 대시보드 'Firing alerts' 패널 = 알림 엔진 firing 응답(시드의 실제 FIRING 포함), 행 → `/alerts?app=<id>&alert=<event id>` / 2 전체 현황에 시드의 실제 FIRING이 나열되고 앱 알림 셀에 셈, 행 → `/alerts?state=firing&alert=<event id>` / 3 기본 템플릿 추천 규칙이 주 앱에 적용(GET /alerts/rules, 활성) | F-S06, F-S24, 05 §2.3-5·§2.10-4 |
| T-E2E-alerts-4~12 | 알림 화면(WO-38, ADR-0010 C-228 (7)), 실스택 직렬: 4 오류율 템플릿에서 만든 규칙(변경 필드 표시)의 7일 미리보기 = API 재질의, 저장·목록 / 5 저장한 규칙이 발화해 고정 패널·목록에 FIRING(API와 같음), 메모와 함께 ACK → ACKED(사용자·메모), 배지 감소 / 6 행에서 대상 일시 중지 → GET /alerts/mutes에 나열, 해제 / 7 채널: 웹훅 목적지 검증(10.0.0.1·169.254.169.254 → 422), URL 마스킹만, 가짜 수신기로 테스트 전송과 상태, 이메일 `o***@도메인`, Slack, 서명 키 회전 1회 표시 / 8 이력 10열·필터 = 서버 매개변수와 같은 질문의 응답·7·30일 통계·전 앱 보기 / 9 전체 현황의 ACKED 행 → ACKED 이력과 그 알림(C-226 (8)) / 10 1366에서 규칙 이름 잘림 없음·대시보드 값/임계치 단위 / 11 비로그인 알림 링크가 로그인 뒤 복귀, 이력 딥 링크가 대시보드를 발화 ±15분 절대 범위로 / 12 실시간 이력이 서버 `alert` push로 고정 패널 갱신 | F-S17, F-S24~27, 05 §2.4·§2.9 |
| T-E2E-alerts-20~25 | 알림 시각 기준 이미지(e2e:docker): 20 이력 로딩 / 21 이벤트 없는 범위(다음 행동 안내) / 22 오류(요청 ID·재시도) / 23 고정 알림·채널 상태·10열 이력 / 24 오류율 템플릿 규칙 편집기와 미리보기 / 25 마스킹된 대상과 상태의 채널 목록 | F-U16, 05 §2.0 |
| T-E2E-alerts-30~40 | `required/alerts.yaml`의 05 §2.4-1~5·§2.9-1~6 체크리스트(필수): 편집기 전 필드·허용 변수만, 7일 미리보기(500ms 디바운스) = API, 템플릿 생성·변경 표시, 복제·비활성≠삭제·이력 = API, 채널 목적지 검증·마스킹·테스트 전송, FIRING·ACKED 고정·채널 상태, 10열 = /alerts/events, 필터 = 서버 매개변수, 행 동작, 7·30일 통계 = /alerts/stats, 전체 현황 → 전 앱 이력 | F-S17, F-S24~27, 05 §2.4·§2.9 |
| T-BLD-001 | major version 52 전수 | 03 3.1 |
| T-BLD-002 | jar 클래스는 `io.argos.`·`io.argos.agent.shaded.`만 | 03 3.1 |
| T-BLD-003 | `META-INF` LICENSE·NOTICE·THIRD-PARTY-NOTICES·argos-deps.txt·sbom 존재·형식 | O-L04, SEC-77 |
| T-BLD-004 | Unsafe 참조 허용 목록 밖 0 | F-A35 |
| T-BLD-005 | 셰이딩 후 프로퍼티 문자열(`io.netty.noUnsafe` 등) relocate 확인 | F-A35 |
| T-BLD-006 | jar 크기 ≤ 15MB, 임시 파일 0(기동 중 `/tmp` 감시) | Gate 0 P-3 |
| T-BLD-007 | SPDX 헤더 전수, 라이선스 허용 목록 | O-L03·L07 |
| T-BLD-008 | SBOM 컴포넌트 수 > 0, 스캔 Critical·High 0 또는 예외 파일 대조 | SEC-70·71 |
| T-BLD-009 | 2회 빌드 jar 해시 일치 | SEC-73 |
| T-BLD-010 | 릴리스 빌드에서 `argos.test.*`·`argos.testing.*` 무시 | DSN-18-15 |
| T-BLD-011 | 에이전트 네트워크 클래스 참조 허용 목록(gRPC 채널만) | N-17 |
| T-BLD-012 | `NOTICE` 차용 코드 출처 린트(헤더 ↔ NOTICE 대조) | O-L05 |
| T-NFR-01~04, 26~30 | 7장 벤치 하네스 판정 | N-01~04, N-26~30 |
| T-NFR-06 | 8장 1시간 부하 | N-06 |
| T-NFR-07·24 | 30일 데이터 조회 응답(Q1·Q3·Q5·Q7·Q8, ID 검색) | N-07, N-24 |
| T-NFR-12·17 | 에어갭 설치·egress 0 | N-12, N-17 |
| T-NFR-14 | XLog 프레임 ≤ 16ms, Lighthouse 초기 로드 ≤ 2초 | N-14 |
| T-NFR-16 | 데모 60초(스크립트) + 비참여자 5분 | N-16 |
| T-NFR-18 | ClickHouse 설정 단언 + soak OOM 0 | N-18 |
