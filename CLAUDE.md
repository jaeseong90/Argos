# Argos APM: AI 코딩 도구 사용자를 위한 작업 지침

이 파일은 이 저장소에서 Claude Code 등 AI 코딩 도구를 쓰는 **모든 기여자**를 위한 규약 요약이다. 규칙의 원본은 `CONTRIBUTING.md`(작성 예정)와 `docs/03-definition-of-done.md`다. 마지막 절은 메인테이너 내부 워크플로이며 외부 기여자에게는 선택 사항이다.

## 프로젝트

Java 백엔드(Spring Boot / Tomcat)용 설치형 **오픈소스 APM(Apache-2.0)**. 품질 목표는 상용 SW 수준이며, 그 기준은 `docs/05-benchmark.md`(Datadog APM·Jennifer·Prometheus/Grafana 대비)로 정의한다. 자체 바이트코드 에이전트(Java 8 타겟) + Spring Boot 수집 서버(Java 21) + ClickHouse + PostgreSQL + React UI.

개발 방식: 사람이 기획·결정·수락 검수를 맡고, AI 코딩 에이전트가 작업 지시서(WO) 단위로 구현한다. 현재 설계 단계이며 코드는 아직 없다.

## 먼저 읽을 것

1. `docs/README.md`: 문서 지도
2. `docs/01-product-plan.md`: 무엇을, 왜, 어디까지 (기능 F-xxx, 정확성 D-xx, 비기능 N-xx, 시나리오 S-xx, 오픈소스 요구 O-xx, 호환성 매트릭스)
3. `docs/02-architecture.md`: 구성 요소, 전역 설계 결정(DSN-02-xx), 데이터 분류표
4. `docs/10-*.md` ~ `docs/19-*.md`: 상세 설계 (작업하는 영역의 문서는 반드시)
5. `docs/03-definition-of-done.md`: 언제 끝인가 (공통 DoD, 영역별 DoD, 게이트, 보고 형식)
6. `docs/05-benchmark.md` 2장: 화면별 필수 요소 (UI 작업 시)
7. `docs/06-security-requirements.md`: 보안 요구 SEC-xx (인증·전송·명령·수집 데이터·UI·공급망 작업 시)
8. `docs/04-work-orders.md`: 지금 할 일 (WO 목록, 상태 표)

## 작업 규칙

- **지시받은 WO(또는 이슈) 범위만** 구현한다. 범위 밖 변경은 보고서·PR에 제안만 남기고 하지 않는다.
- **완성 기준은 03 문서가 유일한 기준**이다. 테스트 없이 "동작한다"고 보고하지 않는다. 테스트는 수락 기준을 직접 검증해야 한다.
- 모호한 점은 가정을 명시하고 진행한 뒤 보고서 "가정과 미해결"에 남긴다. 단, **02 문서의 전역 설계 결정(DSN)이나 설계 문서를 바꿔야 하면 멈추고 묻는다.** 바뀌면 ADR을 남긴다. 설계 문서끼리 어긋날 때의 정합 결정은 02 §13(C-1~C-20)과 `docs/adr/0003`(C-21~C-36)·`docs/adr/0004`(C-37~C-49)·`docs/adr/0007`(C-50~C-71)·`docs/adr/0009`(C-72~C-90)·`docs/adr/0010`(C-91~)가 우선한다. 전송·셰이딩·저장소 결정은 `docs/adr/0005`·`0006`·`0008`에 있다.
- **배포물에 포함되는 의존성은 Apache-2.0 호환 허용 목록만**(Apache-2.0, MIT, MIT-0, BSD, ISC, 0BSD, PostgreSQL. 전체 목록은 `build-logic/allowed-licenses.json`, MIT-0은 ADR-0004 C-42). GPL·LGPL·AGPL·SSPL·BSL은 금지. 테스트 전용 드라이버는 배포물에 들어가지 않는 조건으로 허용하고 `docs/licenses/dev-only.md`에 기록한다.
- **에이전트 모듈에 의존성을 추가할 때는** 셰이딩 규칙·SBOM·버전 매니페스트에 넣고 보고서에 `jar tf`와 `check-shading.sh` 결과를 첨부한다.
- 외부 오픈소스 코드(OpenTelemetry·Pinpoint·Scouter·SkyWalking 등 Apache-2.0)를 차용·개작하면 원 저작권 헤더를 유지하고 `NOTICE`에 출처를 적는다. 상용 제품(Jennifer·Datadog 등)의 코드·에셋·문구는 참고만 하고 복사하지 않는다.
- 새 파일에는 SPDX 헤더(`SPDX-License-Identifier: Apache-2.0`)를 넣는다.
- 에이전트·서버·UI는 운영자가 설정한 대상(수집 서버·알림 채널·SMTP) 외의 **외부 네트워크 호출을 하지 않는다.** 사용 통계·업데이트 확인 코드를 넣지 않는다.
- 새로 수집·저장하는 필드는 02 문서 데이터 분류표에 추가한다. 개인정보 가능 필드는 기본 "수집 안 함 또는 마스킹".
- 커밋에는 `Signed-off-by`(DCO)를 넣는다. 사용자 문서·README·CHANGELOG·로그·에러 메시지·UI 원본 문자열은 영어(ko는 i18n). 기획·설계 문서는 한국어. 코드 식별자·주석은 영어.
- 영업·가격·판로·라이선스 키·에디션·유료 기능 같은 사업 요소는 문서와 코드에 넣지 않는다. 모든 기능은 모든 사용자에게 같다.

## 기술 스택 요약

| 영역 | 스택 |
|---|---|
| agent | Java 8 타겟(`--release 8`), ByteBuddy Advice 인라인만, 단일 jar(`inst/*.classdata`) + 격리 클래스로더, gRPC(NIO·JDK TLS) + protobuf, 전부 `io.argos.agent.shaded.*`로 셰이딩, 자체 로거, Unsafe 미사용 |
| server | Java 21, Spring Boot, grpc-java, Reactor(실시간 버스), ClickHouse Java client, PostgreSQL + Flyway, Spring Security(httpOnly 쿠키 세션) |
| ui | React + TypeScript + Vite, pnpm, TanStack Query, Ant Design, uPlot, Canvas XLog, i18next(en 기본), Vitest + Playwright |
| 빌드 | Gradle(Kotlin DSL) 모노레포, JDK toolchain(agent 8 / server 21), Spotless(SPDX 포함) + Error Prone + ArchUnit, ESLint(`react/no-danger`) + tsc strict, CycloneDX SBOM, gitleaks |

## 빌드·검증 명령

준비: JDK 21(Gradle 9.8.2 실행, `gradle/gradle-daemon-jvm.properties`), Node 24.14 이상 24.x + Corepack(25 이상 미지원), Docker. Gradle·pnpm 전역 설치는 필요 없다. JDK 8(에이전트 테스트)은 Gradle 툴체인이 자동으로 내려받는다. 저장소 루트에서 `pnpm -C ui …`를 쓰려면 먼저 `corepack install -g "$(node -p "require('./ui/package.json').packageManager")"`를 한 번 실행한다(안 하면 `ERR_PNPM_BAD_PM_VERSION`; 대안은 `cd ui && corepack pnpm …`). 자세한 내용은 `docs/dev-setup.md`.

```
./gradlew build                                   # 전체: 컴파일 + check + UI(pnpm install·build·lint·typecheck·test)
./gradlew build -Pargos.ui.skip=true              # Java만 (빠른 반복용, PR 전에는 전체 build)
./gradlew check                                   # test, archTest, contractTest, verifyAgentJar, spotlessCheck, Error Prone,
                                                  # checkLicenses, checkSpdx, checkUnitTestSources, build-logic 테스트, UI test·lint·typecheck.
                                                  # 루트 가드(argos.test-layers)가 check 구성(18 §2.2)과 성능 태스크의 -Pbench.machine-profile을 그래프 단계에서 검사(test/README.md)
./gradlew spotlessApply                           # Java 포맷·SPDX 헤더 자동 수정
./gradlew :agent:agent-dist:agentJar              # 에이전트 단일 jar → agent/agent-dist/build/libs/argos-agent-<버전>.jar
./gradlew :agent:agent-dist:check verifyAgentJar  # checkShading, checkMajorVersion(자체 52·셰이딩 ≤ 52), checkRelocatedStrings·checkOwnRelocatedLiterals(C-23), checkModuleIndex,
                                                  # checkManifestVersion(Implementation-Version = 프로젝트 버전, T-BLD-010 릴리스 판정 fail-closed), checkJulBridge(JUL 참조 0),
                                                  # checkThirdPartyNotices(O-L04: 비Apache 라이선스의 저작권·본문, Netty·gRPC·PerfMark 원 NOTICE. 원문은 agent/agent-dist/src/notices/)
./gradlew :agent:agent-testing:archTest           # Advice·패키지 경계·코드 규칙(T-AG-001~004, ADV-01~04, A-1~A-9, B-1~B-4, AGT-01~03, 10 §14 로그 인자 allowlist LOG-01).
                                                  # 규칙마다 위반 픽스처(src/archFixtures)가 실제로 잡혀야 통과. INFO 이상 로그의 문자열 인자는 src/archTest/resources/log-argument-allowlist.txt에 근거와 함께
./gradlew checkLicenses checkDevOnlyLicenses      # 라이선스 허용 목록(테스트 전용 예외: docs/licenses/dev-only.md)
./gradlew integrationTest                         # Testcontainers(Docker), 샘플 앱 shop-boot3 스모크 3변형·시드 센티널, 저장소 스키마·생성기·쿼리(실제 ClickHouse). check에 포함되지 않음
./gradlew integrationTest -Pqueries.profile=ci    # CI와 같다: 저장소 정합 T-ST-030~033·053을 생성기 1/100 프로파일로(12스레드 개발 머신에서 전체 약 7분)
./gradlew :agent:agent-core:integrationTest       # T-ACC-11(tx_id 1,000만 건 충돌 0·tx_id >> 23 ±1ms·두 인스턴스·begin 경로 start_ts 일치)과 N-02 축소판 힙 예산(포크 JVM, 큐·사전 상한, io.argos.agent. ≤ 64MB),
                                                  # SessionFlowControlIntegrationTest(실제 grpc-netty 스택: isReady 백프레셔·메타데이터·SESSION_REQUIRED 같은 seq 재전송),
                                                  # SqlNormalizerTimingIntegrationTest(@Tag slow, SQL 정규화 1ms 예산 실측: 64KB 최악 입력 8종을 함께 워밍업한 뒤 입력마다 라운드 최솟값의 중앙값, 최대 3회 시도, [sql-timing] 줄에 중앙값·p90도, 조용한 머신에서). 약 1.5~2분
./gradlew :agent:agent-testing:integrationTest -Pagent.attach.jdks=8,11,17,21,25 -Pagent.e2e=shop-boot2@8,shop-boot2@11,shop-boot2@17,shop-boot2@21,shop-boot3@17,shop-boot3@21,shop-boot3@25,shop-boot4@21
                                                  # 에이전트 하네스(agent/agent-testing/README.md) 위의 부착(JDK별)·샘플 앱 종단 매트릭스와 FakeCollector
                                                  # (T-AG-005·010·040~045·050·053·060·070, T-BLD-006·010, T-NFR-30a premain 클래스 예산, C-27 플래그, JDK 25는 deny).
                                                  # 서블릿 스위트(WO-12, 패키지 io.argos.agent.testing.servlet, --tests '*servlet*'): T-AG-010~017·020, T-ACC-04·19·21,
                                                  # T-SEC-26b·31·32·34·37 에이전트 측을 -Pagent.e2e의 부트 앱마다 실행(앱당 실행 4개, 동시 6개 상한).
                                                  # 기본은 부착 JDK 8·21 + shop-boot3@21(이 노트북 약 19분), -Pagent.e2e=none이면 종단 생략. 결과 agent/agent-testing/build/reports/agent-e2e/
                                                  # (<app>-jdk<N>-transform.log, 서블릿 스위트는 wo12-<app>-jdk<N>-<profile>[-<n>]-{agent,stdout,tomcat}.log,
                                                  # <n>은 같은 실행의 재기동 순번, WAR 무에이전트 기준선은 wo12-<app>-jdk<N>-war-noagent-tomcat.log).
                                                  # 자가 진단(18 §19)은 개발 빌드 전용 키 -Dargos.test.selfdiag-file=<파일>(C-95)로 하네스가 읽는다(RunningApp.selfDiagnostics(),
                                                  # 에이전트가 보고하지 않은 값은 0이 아니라 NOT_AVAILABLE). 기동 단계별 ms·클래스 수는 -Dargos.debug.transform-timing=true(C-79)
                                                  # -Pagent.baseline.record=true는 변환 베이스라인(agent/agent-testing/baselines/transformed-classes)과 premain 클래스 예산
                                                  # (baselines/premain-classes/trivial-release-<jdk>.txt, 부착 JDK별) 재녹화(계측·기동 경로를 바꾸는 WO는 diff를 PR에),
                                                  # -Pagent.e2e에 WAR 조합(shop-war-javax@8·11·17·21, shop-war-jakarta@17·21·25)을 넣으면 독립 Tomcat(127.0.0.1)에서 WarAppAgentTest
                                                  # (조합당 약 30~50초, 기본에는 WAR 조합이 없어 skip). 예: -Pagent.e2e=shop-war-jakarta@21 --tests '*WarAppAgentTest'
                                                  # WAR 조합은 Spring 없는 서블릿 픽스처(src/servletFixture{Javax,Jakarta}, /servlet-fixture)도 함께 배포해 War*Test(T-AG-011·015·016·020)를 돌리고,
                                                  # War*Test 클래스마다 Tomcat을 새로 띄우고 마지막에 종료 포트로 우아하게 끝낸 뒤 Tomcat 로그를 판정한다(TomcatLogCheck: 경고·스택의 에이전트 언급 0,
                                                  # SEVERE·ThreadLocal 누수는 무에이전트 실행 대비). 예: -Pagent.e2e=shop-war-javax@8,shop-war-jakarta@21 --tests '*War*'(약 10분)
                                                  # -Pagent.container=true는 컨테이너 모드 테스트 추가(Docker, JDK 이미지는 다이제스트 고정, -Pagent.container.image.<jdk>=<image>@sha256:<digest>로 교체)
./gradlew :agent:agent-testing:integrationTest -Pagent.e2e=shop-boot2@8,shop-boot3@21 --tests '*jdbc*'
                                                  # JDBC·풀 스위트(WO-13, 패키지 io.argos.agent.testing.jdbc): T-AG-030~033·036·062f/g·032c/d·035a, T-ACC-22a·24, T-SEC-3A 에이전트 측.
                                                  # 기본은 H2만(기본 integrationTest에도 포함, C-149 (1)). -Pagent.jdbc.containers=true(또는 postgres,mysql처럼 목록)면
                                                  # MySQL 8.4·MariaDB 11.4·PostgreSQL 16·SQL Server 2022(이미지 다이제스트 고정, 한 번에 1개, 무작위 비밀번호, nightly·수동, C-149 (2)),
                                                  # -Pagent.jdbc.oracle=true면 gvenzl/oracle-free(4 GiB, FUTC dev-only, C-149 (3)). 사내 Oracle 19c·Tibero·Altibase·CUBRID·DB2는
                                                  # ARGOS_TEST_<DB>_URL·_USER·_PASSWORD(없으면 사유와 함께 skip, 사람 확인). 이 노트북 H2 약 6분, 컨테이너 + Oracle 약 10분(DB당 컨테이너 ≤ 4 GiB, Oracle 실측 약 3.1 GiB).
                                                  # 로그 agent/agent-testing/build/reports/agent-e2e/wo13-*, 콘솔 [wo13-table]·[wo13-db]·[wo13-sql-ids] 줄은 결과 XML의 system-out에
./gradlew :agent:agent-testing:integrationTest --tests '*transport*' -Pagent.jdk8u252=<JRE> -Pagent.jdk8u242=<JRE>
                                                  # gRPC 전송(WO-11) 대 FakeCollector: T-AG-071·067, T-ACC-02a·06a, T-SEC-06·07a·08c, T-NFR-26a, C-74 증명(T-BLD-004a), C-75, T-BLD-011·011a(실행 시점 프록시 미사용),
                                                  # 세션 반응(ConnectionTransportTest: 백오프당 TCP 접속·DNS 조회 1회, 해석 불가 주소 WARN 분당 1회, 영구 거부·REPLACED 뒤 연결·argos-io 해제, Ack.throttle).
                                                  # 옛 JRE(AdoptOpenJDK 8u242-b08·8u252-b09.1, docs/poc/agent-poc.md 9.8)는 툴체인이 아니라 경로로 준다(없으면 skip, Windows는 %TEMP% 밖에 풀 것).
                                                  # -Pagent.container=true면 POSIX token-file WARN(0644·0600)·Linux C-75·Linux T-NFR-26a 추가. 약 12~15분(ConnectionTransportTest 약 4~5분 포함)
./gradlew :agent:agent-testing:fakeCollectorJar   # 독립 실행 FakeCollector(개발 전용, 벤치 --exporter grpc용) → agent/agent-testing/build/fake-collector/fake-collector.jar + lib/
                                                  # java -jar <jar> --port 0 --ready-file F --token-file T [--tls --tls-dir D] [--summary S]. 계약 벡터는 src/main/resources/contract/argos-v1-vectors.json
./gradlew :test:agent:transport-poc:transportPocJars :test:agent:transport-poc:transportPocTest
                                                  # P-3 전송 비교 jar 9종·보고서, proto 테스트, 스모크(JDK 8·21). 알려진 발견(ADR-0005 P1·P3)은 단언하고 -Ptransport.poc.strict=true면 실패로 셈
./gradlew :test:agent:transport-poc:transportPocBench -Ptransport.poc.jdk8u242=<JRE> -Ptransport.poc.jdk8u252=<JRE>
                                                  # P-3 측정 매트릭스(약 35분) → test/agent/transport-poc/build/poc/bench/<시각>/summary.md. 조용한 머신에서
./gradlew :server:bootJar                         # 서버 실행 jar → server/build/libs/argos-server-<버전>.jar(레이어 4종: java -Djarmode=tools -jar <jar> list-layers).
                                                  # -Pargos.ui.skip=true가 없으면 ui/dist를 static/에 넣는다. :server:checkFlywayNames(check 포함)는 db/pg 파일명 규칙(C-28)
./gradlew :server:integrationTest                 # 서버 기동 순서·설정·관리 포트·PG/CH 마이그레이션·DB 권한(실제 PostgreSQL 16·ClickHouse 26.8, Testcontainers),
                                                  # WO-21 수집 경로(collector·ingest·e2e: FakeAgent → gRPC → WAL → ClickHouse, T-SV-010은 bootJar 자식 JVM 강제 종료 2회,
                                                  # T-ACC-13 (c) ClickHouse 3분 중단 2종(ingest 수준·gRPC 종단) 포함, WO-23 조회 API 포함 약 22분. bootJar를 먼저 빌드한다).
                                                  # 실시간 1초를 넘는 테스트는 @Tag("slow")로 integrationTest에 둔다(WAL fsync 1초 → UNAVAILABLE, 기록기 플러시 요청, keepalive 강제)
                                                  # 소요 표 server/build/reports/migration/timings.md. -Pserver.nightly=true면 T-SV-002(V001 + 생성기 1/100 → 다음 버전, 약 2분) 추가, 없으면 SKIPPED.
                                                  # -Pserver.chOutageMinutes=30 --tests '*ClickHouseOutage*'는 30분 중단 변형(03 §3.2, 두 클래스라 약 65분, 조용한 머신에서).
                                                  # -Pserver.load=true --tests '*IngestLagLoad*'는 1,000 TPS 5분(인스턴스 10, 프로파일 0.12·통계·메트릭, 약 6분) 적재 지연
                                                  # p50/p99를 /actuator/prometheus에서 읽어 server/build/reports/ingest-load/report.md로(공유 머신은 참고값).
                                                  # -Pserver.load.enforce=true를 더하면 p99 ≤ 5초를 단언한다(조용한 머신·CI). 없으면 IngestLagLoadIT는 SKIPPED.
                                                  # 프로토콜 수준 가짜 에이전트(공격 5종 포함)는 server/src/testFixtures의 io.argos.server.testing.fakeagent(FakeAgent, WO-29 재사용).
                                                  # 두 Gradle 실행이 같은 :server:integrationTest를 동시에 돌리면 결과 파일이 깨진다. --tests 부분 실행은 결과 XML을 지워
                                                  # traceabilityReport에서 나머지가 NOT RUN이 된다(전체 실행 뒤에 돌릴 것). :server:securityTest와 한 실행에 주면 병렬로 돈다(org.gradle.parallel)
                                                  # WO-23 조회 API(소스 선택·T-ACC-03 정합·C-50 조회 순서·XLog 샘플링·FakeAgent → 적재 → API 종단)도 여기 있다. 공유 조회 데이터
                                                  # (QueryTestData.shared(), 생성기 tiny, CH 1.5GiB + PG 0.5GiB)를 JVM 끝까지 유지하므로 -Pserver.load·-Pserver.nightly·apiBench는 --tests로 따로 돌린다
./gradlew :server:generateOpenApi                 # docs/api/openapi.yaml 재생성(생성본, 손 편집 금지, C-28). 컨트롤러·DTO를 바꾸면 돌리고 함께 커밋한다(빌드 버전과 무관, info.version v1).
                                                  # :server:contractTest(check 포함)가 커밋본 = 생성본, 14 §2.9 린트(JUnit, Spectral 없음, C-151), 저장소 조회 operation의 x-argos-limits,
                                                  # 커밋본 스펙의 권한 매트릭스(T-API-010), oasdiff 네거티브(T-API-002, Docker)를 검사한다. 서버 jar를 다시 만들 수 있어 서버 통합 실행 중에는 돌리지 않는다.
                                                  # 같은 record를 요청 본문과 2xx 응답에 함께 쓰면 생성이 실패한다(요청 record를 따로 둘 것). 요청 필드는 @Schema(requiredMode = REQUIRED)를 단 것만 required
./gradlew :server:integrationTest --tests '*QueryApiBench*' -Pserver.apiBench=true [-Pserver.apiBench.profile=ci|local|full] [-Pserver.apiBench.enforce=true]
                                                  # 조회 API N-07·N-24 호출 묶음(대시보드 1h·XLog 10만 점 이상 창·상세(±2초 경로, captured)·trace(2건 이상)·서비스·SQL Top N 1일·
                                                  # user/request 검색 1h, 콜드·웜 3회 중앙값). 표본(SQL 확인)·응답 조건이 미달이면 그 케이스는 FAILED
                                                  # → server/build/reports/api-bench/report.md. 단독 실행. ci(1/100, 7.2h) 약 5~6분(적재 약 3분), local(1/20, 36h)은 적재가 약 5배.
                                                  # 이 PC 수치는 참고값(INVALID). enforce는 케이스마다 웜 3회 중앙값 ≤ 목표와 콜드(재기동 뒤 첫 호출) ≤ 목표의 2배를 모두 요구하고(C-180,
                                                  # 보고서에 두 판정 열), profile=full(생성기 30일, 목표 하드웨어) 전용이며 다른 프로필이면 적재 전에 실패.
                                                  # 자원: -Pserver.apiBench.serverXmx=<힙>(서버 자식 JVM)·-Pserver.apiBench.chMemory=<크기>(ClickHouse 컨테이너, 예 7.5g). 기본은 enforce면
                                                  # -Xmx4g·CH 7.5GiB·PG 1.5GiB(13 §7.1), 아니면 -Xmx1g·CH 4GiB·PG 0.5GiB. 플래그가 없으면 SKIPPED
./gradlew :server:loadQueryTestData -Pquerydata.clickhouse=<url> -Pquerydata.postgres=<jdbc-url> [-Pquerydata.profile=tiny|ci|local|full]
                                                  # 조회 테스트 데이터(생성기, 시드 42)를 스키마가 있는 실행 중 설치에 적재(개발 전용, WO-30 E2E 시드). 비밀번호는 환경변수
                                                  # ARGOS_CH_WRITER_PASSWORD·ARGOS_PG_PASSWORD로만. 매니페스트 server/build/query-test-data/query-test-data.json(-Pquerydata.out)
./gradlew :server:jibDockerBuild                  # 로컬 이미지 argos-server:<버전>(linux/amd64, 베이스 다이제스트 고정, user 10001:10001, HOME=/data, 포트 8080·9100, UI 포함.
                                                  # -Pargos.ui.skip=true면 UI 없음). Jib은 구성 캐시 미호환. 서버 jar를 다시 만들므로 .gradle/argos-locks/server-it를 잡고 돈다.
                                                  # /data·/data/tmp·/logs는 이미지에서 10001:10001 0700이라 새 named volume은 준비가 필요 없다(bind mount만 chown, server/README.md)
bash deploy/compose/test/run-all.sh               # Compose 설치 스크립트 테스트(Docker, 약 50초): env-init을 Ubuntu·Alpine·Debian 컨테이너에서(권한·길이·금지 목록·종료 코드·
                                                  # RAM 8/16/32GB 스냅샷·--force 거부), argos-compose.sh(미제공 명령 거부·up 사전 검사·token create 입력 상한·SAN 예시),
                                                  # smoke.sh 판정 로직과 변이(5432 게시·.env 0644 등), compose 파일(다이제스트 = image.properties, 포트 집합, 프록시 XFF·INTERNALPROXIES, .env.example)
./gradlew :deploy:composeSmoke                    # Compose 종단 스모크(WO-40, Docker, check 미포함, 약 3.5분 + 이미지 빌드): jibDockerBuild → env-init → up --wait → smoke.sh
                                                  # → token create + 실제 에이전트(shop-boot3, TLS) 적재 → 5개 상한 → ARGOS_MGMT_EXPOSE 변형 → down -v, 마법사·무인 두 모드와 금지 비밀번호 기동 거부.
                                                  # server-it 잠금을 잡고, 호스트 포트 8080·9100을 쓴다(프로젝트 argos-wo40-smoke, 컨테이너 ≤ 6GiB 상한). -Pcompose.smoke.modes=wizard|unattended,
                                                  # 진단은 ARGOS_SMOKE_KEEP_ON_FAILURE=1 bash deploy/compose/test/compose-smoke.sh. Linux Docker Engine에서는 root로(비밀값을 uid 10001에 넘김)
deploy/compose/smoke.sh                           # 설치 상태 검사(운영자용, POSIX sh, 기본 읽기 전용): .env·secrets/ 권한, 게시 포트 {8080, 9100}(+127.0.0.1:8081), ss -ltnp, 이미지 다이제스트,
                                                  # back internal, uid 10001, health, /actuator 404, 설정 토큰 403. --complete-setup(마법사 API로 관리자 생성, 테스트 설치용),
                                                  # --check-admin-login(무인 설치 직후 로그인·변경 강제·로그아웃). 설치 절차는 docs/install/compose.md(ko: compose.ko.md)
test/storage/bench/bench-server.sh start          # 저장소 PoC 벤치용 단독 ClickHouse(127.0.0.1:18123, 비밀번호는 test/storage/bench/build/). 절차는 test/storage/bench/README.md
./gradlew :test:storage:generator:storageBench -Pbench.machine-profile=<id> -Pstorage.profile=local -Pstorage.target=url:http://127.0.0.1:18123/ -Pstorage.apply-schema=true
                                                  # 저장소 PoC 벌크 생성·적재(13 §15, 18 §9; profile ci 1/100, local 1/20, full). 리플레이는 storageGenerate -Pstorage.mode=replay
./gradlew :test:storage:queries:storageQueries -Pqueries.args="--url http://127.0.0.1:18123" -Pqueries.cold=none
                                                  # Q01~Q20 러너 → test/storage/queries/build/reports/storage-queries/bench/report.md
./gradlew securityTest                            # 보안 테스트 스위트. check에 포함되지 않음
./gradlew :server:securityTest                    # 서버 SEC 스위트(T-SEC-40~49·55·56 서버 부분: 쿠키·세션 수명·CSRF·설정 토큰·비밀번호·헤더·CSP·레이트 리밋·에러 본문,
                                                  # 실제 로그인 세션 권한 매트릭스, 미존재 사용자명 잠금(재기동·노드 간 유지). Testcontainers, 약 4분). 실측 표 server/build/reports/rate-limits.md·permission-matrix-session.md.
                                                  # WO-21 수집기: T-SEC-07b TLS 1.1·CBC 거부, T-SEC-11 gzip 폭탄(bootJar 자식 JVM, 최대 RSS 증가 ≤ 200MB, 조용한 머신에서),
                                                  # T-SEC-11~14 공격 중 다른 인스턴스 적재 ≤ 5초, T-SEC-15 리플렉션·미인증·Hello 폭주 차단
                                                  # 웹 계층 스텁 매트릭스(Docker 없음)는 :server:contractTest → permission-matrix.md. 두 Gradle 실행이 같은 :server:securityTest를 동시에 돌리면 결과 파일이 깨진다
java server/scripts/GeneratePasswordDenylist.java # 비밀번호 금지 목록 해시 재생성(개발 도구, SecLists 10k를 고정 커밋·SHA-256으로 내려받음, 출처 NOTICE)
                                                  # → server/src/main/resources/security/password-denylist.sha256. 빌드·실행 시에는 아무것도 내려받지 않는다
./gradlew matrixTest -Pmatrix.combo=<id>          # 호환성 매트릭스 1개 조합(또는 -Pmatrix.group=<group>)을 로컬 실행(Docker·해당 JDK 필요). 전체 매트릭스는 신뢰 러너 전용.
                                                  # 라벨(ARGOS_RUNNER_LABELS=argos-matrix)·선택이 없으면 SKIPPED + WARNING 1줄
./gradlew :test:bench:benchTest -Pbench.machine-profile=<id>
                                                  # 오버헤드 벤치 전체 프로토콜(18 §7, bench-protocol.sh: main·startup·container·vt, 약 5시간). 벤치 머신 전용
./gradlew serverLoadTest -Pbench.machine-profile=<id>   # soakTest·chaosTest도 같다. 러너는 WO-29·WO-52 몫이라 지금은 담당 WO를 밝히고 실패(측정 없음).
                                                  # benchTest·serverLoadTest·storageBench·soakTest·chaosTest는 프로필이 없으면 아무것도 빌드하기 전에 실패
test/bench/bench-run.sh --app shop-boot3 --jdk 21 --rounds 3 --exporter noop
                                                  # 18 §7.3 A-B-A-B-A-B 세트 + 보고서 docs/bench/<날짜>-<커밋7>.md. 전용 장비 절차는 docs/poc/agent-overhead.md 9장.
                                                  # --exporter noop = -Dargos.test.exporter=noop(인코딩 후 버림, 18 §5.4), drop = -Dargos.test.exporter=drop(인코딩 없이 버림,
                                                  # WO-02 P-2 설정 재현, C-80). 옛 키 argos.exporter는 없다(C-88). 둘 다 테스트 키라 개발(-SNAPSHOT) jar만 받는다(릴리스 jar는 거부)
test/bench/bench-run.sh --smoke --startup --exporter drop --machine-profile <이름>
                                                  # 공유 머신에서 측정 체인만 확인(약 7분, 보고서는 항상 INVALID)
test/bench/bench-run.sh ... --exporter grpc --collector HOST:PORT --agent-config server.tls=false,server.token-file=FILE
                                                  # 전송 비용 포함(N-01·N-03 gRPC 재측정). 수집기는 fake-collector.jar 또는 서버, 예시는 test/bench/README.md
test/bench/bench.sh <load|startup|report|publish> ...   # 벤치 하네스 직접 실행(k6는 test/bench/fetch-k6.sh). 사용법 test/bench/README.md
./gradlew traceabilityReport                      # 요구 → 테스트 ID → 테스트 → 마지막 결과 → build/reports/traceability.md·.json(테스트 실행 없음, test/README.md 6장)
./gradlew :sample-apps:<app>:sampleSmoke          # 샘플 앱을 에이전트 없이 기동해 엔드포인트 카탈로그 검증(전 변형, check 미포함).
                                                  # 1개 변형은 sampleSmoke<Variant>, 루트 sampleSmoke는 54변형 전부. -Psample.startupRuns=5는 기동 중앙값.
                                                  # 스위치(sample-apps/README.md): SAMPLE_DB=h2|postgres|mysql|mariadb|mssql|oracle(mariadb·mssql·oracle 드라이버는 shop-boot3만, WO-13),
                                                  # SAMPLE_POOL=hikari|tomcat|dbcp2, SAMPLE_ROUTING_DS, SAMPLE_DRIVER_WRAPPER(C-40 위임 래퍼 5번째 층), SAMPLE_SECOND_POOL(풀 2개·재기동)
./gradlew :sample-apps:<app>:sampleAppJar         # 실행 jar → sample-apps/<app>/build/sample-app/<app>.jar (WAR 앱은 :war)
./gradlew :sample-apps:<app>:sampleImage          # 샘플 앱 Docker 이미지 argos-sample/<app>:dev

pnpm -C ui install --frozen-lockfile
pnpm -C ui build && pnpm -C ui test               # build = tsc + vite + 번들 라이선스 검사 + 외부 참조 검사 + 번들 크기 보고(bundle:size, 15 §14 대비 gzip 표,
                                                  # 예산 초과는 경고, 청크 그래프 결함은 실패. 결과 ui/build/bundle-size.json)
pnpm -C ui lint && pnpm -C ui typecheck && pnpm -C ui format:check            # ESLint, tsc strict, Prettier
pnpm -C ui check:i18n && pnpm -C ui check:external && pnpm -C ui check:licenses  # i18n 키, 외부 참조, 라이선스
pnpm -C ui check:licenses:dev                     # 허용 목록 밖 UI 개발 도구가 dev-only.md `npm:` 행에 있는지
pnpm -C ui gen:api && pnpm -C ui check:api       # docs/api/openapi.yaml → src/api/generated/schema.ts(손 편집 금지, x-argos-untrusted → Untrusted 브랜드).
                                                  # check:api(= test에도 포함)는 커밋본 스펙으로 재생성한 결과와 diff 0. 스펙이 바뀌면 gen:api 후 함께 커밋
pnpm -C ui playwright:install                     # Playwright Chromium 설치(머신당 1회, e2e·perf 전제)
pnpm -C ui e2e                                    # vite build + Playwright E2E: preview 프로젝트(WO-04 spec, vite preview) + stack 프로젝트(WO-30 spec 전부,
                                                  # 이 트리의 서버 bootJar + PostgreSQL·ClickHouse 컨테이너, 127.0.0.1 무작위 포트, 라벨 io.argos.e2e·접두 argos-e2e-, ≤ 5GiB,
                                                  # 시드: :server:loadQueryTestData tiny(오늘 UTC 앵커, C-170) + 사용자 3 + shop-boot3·에이전트·seed-traffic.sh, 매니페스트
                                                  # ui/e2e/.stack/seed-manifest.json, WS만 스텁). 전제: Docker, JAVA_HOME JDK 21, Windows는 Git Bash, 그리고 jar 5개
                                                  # ./gradlew :server:bootJar :agent:agent-dist:agentJar :sample-apps:shop-boot3:sampleAppJar :sample-apps:ext-stub:sampleAppJar :sample-apps:smoke:sampleAppJar
                                                  # (UI·서버 소스보다 오래된 bootJar는 거부). 재시도 0, 이 노트북 약 3분(스택 기동·시드 약 1분). test·Gradle check에 포함되지 않음.
                                                  # 실서버 실행은 server-it 락. 서버 CSP 카운터는 Chromium에서 검증 불가(csp-counter-not-verifiable 주석, ui/e2e/README.md CSP 절)
pnpm -C ui e2e:preview                            # preview 프로젝트만 / pnpm -C ui e2e:stack [--shard=i/n]: stack 프로젝트만
pnpm -C ui e2e:docker [-- --update-snapshots=missing|all]   # stack 프로젝트를 다이제스트 고정 Linux Playwright 이미지 안에서(컨테이너는 pnpm-lock.yaml에서 만든 package-lock.json을
                                                  # npm ci로 무결성 검사하며 설치. UI를 바꾼 뒤에는 --update-snapshots=all로 다시 만들고 이미지를 확인. 시각 회귀 기준 이미지 생성·비교는
                                                  # 여기서만, 그 밖에서는 screenshot-linux-only로 skip). CI pr.yml e2e 잡(4샤드)이 이것을 쓴다. 기준 이미지 갱신은 quiet 락
pnpm -C ui e2e:stack:up && pnpm -C ui e2e:stack:down   # 시드된 스택을 띄워 두고(ARGOS_E2E_STACK_URL=<URL>로 재사용) / 제거. node e2e/stack/cli.ts restart-server는
                                                  # DB를 둔 채 새 bootJar로 서버만 재기동. 변수·픽스처·required YAML은 ui/e2e/README.md
pnpm -C ui perf:initial-load                      # 스택 위 초기 로드(T-NFR-14 골격, Navigation Timing 5회·CPU 부하 기록, 참고값). 같은 jar 전제
pnpm -C ui perf                                   # vite build + XLog 프레임 시간 spec(T-NFR-14, Chrome tracing). 조용한 머신에서, 약 12~16분

bash test/lint/check-spdx.sh                      # SPDX 헤더 전수(T-BLD-007)
bash sample-apps/load/run-bench.sh --summary build/bench/summary.json   # k6 bench.js 1,000/s × 1분(Docker), 결과 JSON·머신 부하
java -jar sample-apps/load/build/sample-app/load.jar --target-url http://127.0.0.1:8080 --mix bench --rate 1000 --duration 1m --summary build/load/summary.json
                                                  # 데모 부하 생성기(의존성 없음, :sample-apps:load:sampleAppJar), 실행 중인 앱 대상
bash test/seed/seed-traffic.sh --start --repeat 10   # PII·XSS 시드 트래픽(센티널 ARGOS_SEED). 실행 중인 앱은 --url <URL>
bash test/agent/check-shading.sh agent/agent-dist/build/libs/argos-agent-0.0.0-SNAPSHOT.jar
bash .github/scripts/check-workflows.sh           # 워크플로 정책(pull_request_target·self-hosted 금지, SHA 고정)
bash .github/scripts/check-proto.sh [--against-git <base>|--against <dir>]   # proto buf lint, 인자를 주면 buf breaking(고정 다이제스트 컨테이너, --network none, PR lint 잡)
bash .github/scripts/check-file-modes.sh          # git 인덱스에서 gradlew·*.sh가 100755인지(lint 잡)
REQUIRE_DOCKER=1 bash .github/scripts/test/run-all.sh   # CI 스크립트 테스트(DCO·changelog·워크플로 정책 등)
bash .github/scripts/run-gitleaks.sh dir          # 비밀 스캔(Docker)
```

- 워크플로 lint: `docker run --rm -v "$PWD:/repo:ro" -w /repo rhysd/actionlint:1.7.12@sha256:b1934ee5f1c509618f2508e6eb47ee0d3520686341fec936f3b79331f9315667 -color` (Git Bash에서는 `MSYS_NO_PATHCONV=1`과 Windows 경로 볼륨). PR 잡과 명령의 대응은 `docs/dev-setup.md` 5절.
- 의존성 변경 후: `./gradlew resolveAndLockAll --write-locks --no-configuration-cache -Pargos.ui.skip=true`로 잠금을 갱신하고, `gradle/verification-metadata.xml`은 빈 Gradle 캐시의 Linux 컨테이너에서 `--write-verification-metadata sha256`으로 다시 만든다(명령은 `docs/dev-setup.md` 7절). Windows 웜 캐시에서 만들면 POM·BOM 항목이 빠지고 CRLF로 바뀐다.
- Windows: Git Bash 기준. PowerShell에서는 `.\gradlew.bat`을 쓰고 `"-Pargos.ui.skip=true"`처럼 따옴표로 감싼다. `git config --global core.longpaths true` 권장.
- Windows에서 커밋할 때(core.filemode=false) `gradlew`와 `*.sh`는 `git add --chmod=+x`로 추가해 100755를 유지한다(`git ls-files -s gradlew` → 100755). 안 하면 100644로 기록되어 CI의 `./gradlew` 잡과 Linux·macOS 빌드가 전부 Permission denied(exit 126)로 실패한다. `bash .github/scripts/check-file-modes.sh`로 확인한다.

## 절대 하지 말 것

- 에이전트 코드에서 앱 스레드를 블로킹하거나, 예외·반환값을 바꾸거나, Runnable을 래핑하거나, 상한 없는 컬렉션을 만드는 것.
- Advice에 람다·`skipOn`·`readOnly=false`·`synchronized`·블로킹 호출·리플렉션 조회를 쓰는 것. `MethodDelegation` 사용.
- 에이전트에서 SLF4J·Log4j 등 앱과 충돌할 수 있는 로깅 프레임워크 사용. stdout/stderr 출력.
- `java.*`·`javax.*`·`jdk.*`·`sun.*`·`io.argos.*`를 커스텀 계측 대상으로 허용하는 것.
- 통합 테스트에서 실제 DB(ClickHouse·PostgreSQL)를 Mock으로 대체하는 것.
- 수집 데이터(SQL·예외·URL·user_id·헤더·스레드 덤프)를 HTML 문자열로 조립해 렌더하는 것(`dangerouslySetInnerHTML`·`innerHTML`).
- 토큰·비밀번호·채널 URL을 평문 저장·로그 출력하거나, 토큰을 localStorage에 두는 것.
- 테스트를 통과시키기 위해 테스트를 약화하는 것. 기준을 바꿔야 하면 보고하고 묻는다.

## 메인테이너 워크플로 (내부)

- 사람이 `docs/04-work-orders.md`의 WO 하나를 골라 지시한다. 지시 예: "`docs/04-work-orders.md`의 WO-12를 수행해. 완성 기준은 `docs/03-definition-of-done.md`를 따르고, 끝나면 그 문서 6장 형식으로 보고해."
- 완료 보고는 03 문서 6장 템플릿으로. 실행한 검증 명령과 출력 요약, 실패한 것도 적는다.
- 커밋은 사람이 요청할 때만. 메시지에 WO 번호를 넣는다.
- 사내 시스템 운영 기록은 공개 저장소에 두지 않는다(비공개 저장소, 공개는 익명화 요약).
