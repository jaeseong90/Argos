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
./gradlew :agent:agent-testing:archTest           # Advice·패키지 경계·코드 규칙(T-AG-001~004, ADV-01~04, A-1~A-9, B-1~B-4, AGT-01~03, 10 §14 로그 인자 allowlist LOG-01,
                                                  # SEC-91 ST-91-1: core.command·core.config에 스크립트 엔진·프로세스 실행·동적 클래스 로딩 없음, Class.forName·getMethod는 상수 이름만).
                                                  # 규칙마다 위반 픽스처(src/archFixtures)가 실제로 잡혀야 통과. INFO 이상 로그의 문자열 인자는 src/archTest/resources/log-argument-allowlist.txt에 근거와 함께
./gradlew checkLicenses checkDevOnlyLicenses      # 라이선스 허용 목록(테스트 전용 예외: docs/licenses/dev-only.md)
./gradlew integrationTest                         # Testcontainers(Docker), 샘플 앱 shop-boot3 스모크 3변형·시드 센티널, 저장소 스키마·생성기·쿼리(실제 ClickHouse). check에 포함되지 않음
./gradlew integrationTest -Pqueries.profile=ci    # CI와 같다: 저장소 정합 T-ST-030~033·053을 생성기 1/100 프로파일로(12스레드 개발 머신에서 전체 약 7분)
./gradlew :test:storage:queries:integrationTest --tests '*PercentileErrorIT' -Pqueries.profile=ci
                                                  # T-ST-042 백분위 상태 병합 값 오차 1m·1h·1d(C-53): 합성 Q14/dd, 적재 데이터 Q14/dd_stored, 재구축 DD 상태 바이트 동일(Q14/d14_rebuild). 약 2.5분
./gradlew :agent:agent-core:integrationTest       # T-ACC-11(tx_id 1,000만 건 충돌 0·tx_id >> 23 ±1ms·두 인스턴스·begin 경로 start_ts 일치)과 N-02 축소판 힙 예산(포크 JVM, 큐·사전·전수 통계 창 2개 상한, io.argos.agent. ≤ 64MB),
                                                  # SessionFlowControlIntegrationTest(실제 grpc-netty 스택: isReady 백프레셔·메타데이터·SESSION_REQUIRED 같은 seq 재전송),
                                                  # SqlNormalizerTimingIntegrationTest(@Tag slow, SQL 정규화 1ms 예산 실측: 64KB 최악 입력 8종을 함께 워밍업한 뒤 입력마다 라운드 최솟값의 중앙값, 최대 3회 시도, [sql-timing] 줄에 중앙값·p90도, 조용한 머신에서). 약 1.5~2분
./gradlew :agent:agent-testing:integrationTest -Pagent.attach.jdks=8,11,17,21,25 -Pagent.e2e=shop-boot2@8,shop-boot2@11,shop-boot2@17,shop-boot2@21,shop-boot3@17,shop-boot3@21,shop-boot3@25,shop-boot4@21
                                                  # 에이전트 하네스(agent/agent-testing/README.md) 위의 부착(JDK별)·샘플 앱 종단 매트릭스와 FakeCollector
                                                  # (T-AG-005·010·040~045·050·053·060·070, T-BLD-006·010, T-NFR-30a premain 클래스 예산, C-27 플래그, JDK 25는 deny).
                                                  # 서블릿 스위트(WO-12, 패키지 io.argos.agent.testing.servlet, --tests '*servlet*'): T-AG-010~017·020, T-ACC-04·19·21,
                                                  # T-SEC-26b·31·32·34·37 에이전트 측을 -Pagent.e2e의 부트 앱마다 실행(앱당 실행 4개, 동시 6개 상한).
                                                  # 기본은 부착 JDK 8·21 + shop-boot3@21(이 노트북 약 30분, WO-14 httpclient·WO-16 metric·stats·active 스위트 포함), -Pagent.e2e=none이면 종단 생략. 결과 agent/agent-testing/build/reports/agent-e2e/
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
                                                  # ARGOS_TEST_<DB>_URL·_USER·_PASSWORD(없으면 사유와 함께 skip, 사람 확인. Tibero·Altibase·CUBRID·DB2는 jdbc.VendorDatabaseTest가 shop-boot3
                                                  # SAMPLE_DB=tibero|altibase|cubrid|db2로, 벤더 드라이버 jar 폴더는 ARGOS_TEST_JDBC_DRIVER_DIR → 앱에는 SAMPLE_JDBC_DRIVER_DIR, WO-50). 이 노트북 H2 약 6분, 컨테이너 + Oracle 약 10분(DB당 컨테이너 ≤ 4 GiB, Oracle 실측 약 3.1 GiB).
                                                  # 로그 agent/agent-testing/build/reports/agent-e2e/wo13-*, 콘솔 [wo13-table]·[wo13-db]·[wo13-sql-ids] 줄은 결과 XML의 system-out에
./gradlew :agent:agent-testing:integrationTest -Pagent.e2e=shop-boot2@8,shop-boot4@21 --tests '*httpclient*'
                                                  # HTTP 클라이언트 스위트(WO-14, 패키지 io.argos.agent.testing.httpclient, 기본 integrationTest에도 포함되며 기본은 shop-boot3@21):
                                                  # 앱이 /api/runtime에 알리는 클라이언트마다(boot2@8: hurl·hc4·okhttp·resttemplate, boot3/4: hurl·hc5·okhttp·jdk11·webclient·resttemplate)
                                                  # T-AG-056(HTTP step 1개·C-142 HOST http://127.0.0.1:<port>·TIMEOUT·CONNECT_FAIL·HTTP_5XX·리다이렉트, U-10/U-11 mode=async),
                                                  # T-AG-057·T-SEC-38(기본 tracestate 없음·앱 헤더 보존·수신 채택·argos-state.hosts·exclude-hosts), T-AG-055(WebClient block·future·subscribe,
                                                  # WebFlux 픽스처 트랜잭션 0), T-ACC-05 에이전트 측(shop-boot3 A → B 100회, 호출자 step ≥ 피호출 트랜잭션, shop-boot3에서만).
                                                  # 이 노트북 boot3@21 약 2.5분, boot2@8 + boot4@21 약 2분(에이전트 jar 빌드 후). 로그 build/reports/agent-e2e/wo14-*, [wo14-table]·[wo14-acc05]는 결과 XML system-out
                                                  # OkHttp callTimeout(C-224 (1))은 TIMEOUT: 인터셉터는 InterruptedIOException("timeout")을 못 보므로 취소된 호출 + Call.timeout() 설정 +
                                                  # 경과 ≥ timeout − min(20ms, timeout/2)로 판정(앱 예외 불변). 단위 OkHttpCallTimeoutTest는 :agent:agent-instrumentation:httpclient-okhttp:check(3.14.9·4.12.0)
./gradlew :agent:agent-testing:integrationTest -Pagent.e2e=shop-boot2@8,shop-boot3@21 -Pagent.gc.jdks=8,11,17,21,25 --tests '*metric*' --tests '*stats*' --tests '*active*'
                                                  # 메트릭·전수 통계·액티브 스위트(WO-16, 패키지 io.argos.agent.testing.{metric,stats,active}, 기본 integrationTest에도 포함되며 기본은 부착 JDK·shop-boot3@21):
                                                  # T-AG-062 MetricsE2eTest(Spring 기본 JMX off, JVM·GC·풀·Tomcat·시스템·자기 시리즈, 5초 격자, 시리즈 ≤ 500, counter delta, Hello cgroup = 호스트),
                                                  # T-ACC-25 GcLabelsTest(-Pagent.gc.jdks의 JDK마다 G1·Parallel·Serial·CMS·Shenandoah·ZGC(단일·세대형) 라벨, pause 시리즈에 cycle·concurrent 없음,
                                                  # Shenandoah 없는 빌드는 사유와 skip, [wo16-gc]에 MXBean 원문 = 10 U-14, 기본값은 부착 JDK), T-AG-061 StatsWindowTest(10초 창 실행 수·timeout·connect_fail·fetch_rows),
                                                  # T-ACC-12 FullStatsAppCounterTest(shop-boot3만, normal-rate=0 1만 요청: Σ sqlStatements = Σ sql_count = Σ SqlStat.count, 약 20~30초),
                                                  # C-225 (1) ActiveSnapshotE2eTest(5초 ActiveTransactionSnapshot, IN_SQL 이름이 사전 선전송으로 풀림, 약 25초). 이 노트북 약 10분(GcLabelsTest 26조합 약 7.5분).
                                                  # 결과 줄 [wo16-metrics]·[wo16-gc]·[wo16-stats]·[wo16-fullstats]·[wo16-active]는 결과 XML system-out, 로그 build/reports/agent-e2e/wo16-*
./gradlew :agent:agent-testing:integrationTest -Pagent.container=true --tests '*CgroupContainer*'
                                                  # T-AG-063 cgroup v2(Docker, --cpus 1.5 --memory 512m, 약 40초): Hello cgroup=v2, container_cpu_limit_cores 1.5,
                                                  # container_memory_limit_bytes = system_memory_total_bytes = 512 MiB, [wo16-cgroup]. 실제 cgroup v1 호스트는 WO-50 매트릭스(사람 확인)
./gradlew :agent:agent-testing:integrationTest -Pagent.e2e=shop-boot2@8,shop-boot3@21,shop-boot4@21 --tests '*async*'
                                                  # 비동기 전파·가상 스레드 스위트(WO-15, 패키지 io.argos.agent.testing.async, 기본 integrationTest에도 포함되며 기본은 shop-boot3@21):
                                                  # 샘플 앱 /api/async/executor?work=sql(@Async·supplyAsync·STPE·앱 TPE·JDK 21+ newVirtualThreadPerTaskExecutor 갈래마다 SQL 1개)과 work=detached,
                                                  # T-AG-051m/n(remove·cancel 보존, ThreadPerTaskExecutor), T-AG-052g~j(트랜잭션마다 비동기 SQL step = asyncSql·sql_count, ASYNC step 아래, 풀 스레드 오염 0),
                                                  # Boot 3/4 + JDK 21 이상이면 SAMPLE_VT 실행을 더한다: T-AG-054m~q(트랜잭션 수 = 플랫폼, U-16 tomcat-handler-, VT 2,000요청 뒤 THREAD 사전 1개, 액티브 VT),
                                                  # T-ACC-23m/n(VT는 cpu·alloc 필드 없음). 조합마다 변환 베이스라인(<app>-<jdk>[-vt].txt) 확인. 이 노트북 shop-boot3@21 약 1.5분(앱 2회 기동),
                                                  # 8조합 약 4분. 베이스라인 재녹화는 SampleAppAgentTest와 같은 파일을 쓰므로 한 실행에 함께: --tests '*async*' --tests '*SampleAppAgentTest*' -Pagent.baseline.record=true.
                                                  # 결과 줄 [wo15-sql]·[wo15-leak]·[wo15-batch]·[wo15-vt]·[wo15-baseline]은 결과 XML system-out, 로그 build/reports/agent-e2e/wo15-*
./gradlew :agent:agent-core:testJdk21 :agent:agent-core:testJdk25   # 이름에 VirtualThread가 든 agent-core 단위 테스트(U-1 VT CPU·alloc -1, T-AG-054·T-ACC-23)를 JDK 21·25에서(check 포함).
                                                  # executor 모듈은 test(JDK 8)·testJdk11·17·21·25로 실제 JDK 클래스에 모듈을 설치해 검사한다(U-12 witness, check 포함)
./gradlew :agent:agent-testing:integrationTest -Pagent.attach.jdks=8,11,17,21,25 -Pagent.e2e=shop-boot2@8,shop-boot3@21,shop-boot3@25 --tests 'io.argos.agent.testing.command.*' --tests 'io.argos.agent.testing.remoteconfig.*'
                                                  # 원격 명령·원격 설정·킬 스위치 스위트(WO-17, 기본 integrationTest에도 포함되며 기본은 부착 JDK 8·21·shop-boot3@21). command: CommandRoundTripTest(5종·청크 결합·COOLDOWN·
                                                  # 미지 타입 REJECTED·TTL 수신 기준·CONFIG_RELOAD), EnvDumpSeedTest(T-AG-072·T-SEC-20 센티널 0), ThreadDumpVirtualTest(T-AG-054 덤프 항목: shop-boot3@21+ SAMPLE_VT,
                                                  # 10 U-2: -Pagent.attach.jdks마다 dumpThreads yes|no), SelfDiagCommandE2eTest(C-95 파일 = 명령). remoteconfig: RemoteConfigE2eTest(T-AG-069·T-SEC-23·N-25 에이전트 몫, shop 항목마다),
                                                  # KillSwitchRemoteTest(T-AG-043·C-231 (4) Hello config), RetransformLimitTest(T-AG-046), CustomMethodPolicyE2eTest(T-SEC-25). 이 노트북 위 조합 약 8.5분(에이전트 jar 빌드 후).
                                                  # 결과 줄 [wo17-u2]·[wo17-n25]·[wo17-vt]·[wo17-env]·[wo17-selfdiag]·[wo17-roundtrip]·[wo17-retransform]·[wo17-killswitch]는 결과 XML system-out, 로그 build/reports/agent-e2e/wo17-*.
                                                  # C-233 (6) VT 첫 DBC 대상: --tests '*VirtualThreadDbTargetTest'(약 40초, [c233]). 서버 왕복은 pnpm -C ui e2e:stack --grep "WO-17"
./gradlew :agent:agent-instrumentation:scheduler:check :agent:agent-instrumentation:logging:check :agent:agent-instrumentation:custom:check
                                                  # WO-18 모듈 시험(실제 라이브러리 + 실제 tracer, holder agent): scheduler test(JDK 8: Spring 5.3·Quartz 2.3.2·Batch 4.3)·testModern(JDK 17: Spring 6.2·
                                                  # Quartz 2.5·Batch 5.2)·testSpring7(JDK 17: Spring 7.0, @Scheduled·Quartz), logging test(JDK 8: Logback 1.2·Log4j 2.17.2·Log4j 1.2.17)·testModern
                                                  # (JDK 17: Logback 1.5·Log4j 2.25·reload4j 1.2.26, [wo18-u13] Log4j2 주입기 시그니처·frozen 여부), custom test(JDK 8)·testJdk21. 합계 약 1.5분
./gradlew :agent:agent-instrumentation:httpclient-apache4:testLowerBound   # 하한 경계(C-240 (10), WO-50, check 포함): JDK 8에서 httpclient 4.5(Maven의 첫 4.5 좌표) + httpcore 4.4.1 strictly로
                                                  # Apache4AdviceTest·ModuleTest·Apache4VersionTest([wo50-hc4]). 서블릿 모듈 flush 이름(Jetty 9.4·12 ee10/ee11·Undertow)은 servlet:check의 T-AG-016b
./gradlew :agent:agent-testing:integrationTest -Pagent.e2e=shop-batch@17,shop-batch@21,shop-boot3@21,shop-boot2@8 --tests '*batch*' --tests '*logging*' --tests '*custom*' --tests '*CustomMethodPolicy*' --tests '*SeedLogTest*'
                                                  # WO-18 스위트. batch(패키지 io.argos.agent.testing.batch, shop-batch 조합만, 기본 매트릭스에는 없어 사유와 skip): T-AG-058 BatchTransactionsTest
                                                  # (@Scheduled 3종·Quartz 2종·분리 실행 Spring Batch step = BATCH, 항목당 SQL 20,000건 요약 모드, HTTP 안 동기 실행은 METHOD step, JDK 21+는 vt 1회),
                                                  # T-ACC-20·T-AG-036 BatchStatsTest(SqlStat kind 분리, Quartz job store 폴링 SQL은 sql_outside_tx), T-SEC-26 BatchSeedLogTest. 앱 스위치는
                                                  # shop-batch POST /batch/run/{job}?detached=true(앱이 만든 Thread → BATCH, GET /batch/executions/{id|last}?awaitMs=)와 SAMPLE_BATCH_PER_ITEM_SQL=true.
                                                  # logging(io.argos.agent.testing.logging): T-AG-065 MdcInjectionTest(shop-boot3@21·shop-boot2@8 Logback /api/mdc-log ?clear·?async, Spring 없는
                                                  # 픽스처 src/loggingFixture → build/logging-fixture/<log4j2|log4j1|reload4j>/), C-125 (3) MdcUserIdTest(mdc:<key> plain·hashed·mdc_absent),
                                                  # T-SEC-26 MdcSeedLogTest(1만 요청). custom(io.argos.agent.testing.custom): T-SEC-25 CustomMethodStepsTest(SAMPLE_DRIVER_WRAPPER의 example.jdbcwrapper
                                                  # 대상 METHOD step, 로컬 금지 항목 WARN·원격 FORBIDDEN_PATTERN·제거 push 재변환·500 상한, [wo18-u20]). 결과 줄 [wo18-batch]·[wo18-u19]·[wo18-mdc]·
                                                  # [wo18-log4j2]·[wo18-userid]·[wo18-seedlog]·[wo18-custom]은 결과 XML system-out, 로그 build/reports/agent-e2e/wo18-*
./gradlew :agent:agent-testing:integrationTest -Pagent.e2e=shop-boot2@8,shop-boot3@21 --tests '*slowstack*' --tests '*errorstack*' --tests '*check*' --tests '*tags*'
                                                  # WO-19 스위트(기본 integrationTest에도 포함, 기본은 shop-boot3@21·부착 JDK 8·21, agent-testing README "Slow stacks ..." 절):
                                                  # slowstack.SlowStackE2eTest(T-AG-064, shop-boot3만: /api/slow?ms=5000 동시 15건 → STACK step, 사이클당 ≤ 10·tx당 ≤ 5·3초 간격·64프레임,
                                                  # JDK 21+는 SAMPLE_VT 실행 추가, 비용 자가 진단·argos_agent_stack_capture_seconds_total, 약 1분),
                                                  # errorstack.ErrorStackE2eTest(T-AG-059a~d 제안: /api/error-500 ERROR step 1개 ≤ 30프레임 + 줄 번호 없는 error_top_frame_id, sendError는 없음),
                                                  # check.CheckCliTest(T-AG-068: 실제 jar --check 대 TLS FakeCollector, 정상 0·토큰 거부 1·미신뢰 CA 1·시계 2초 WARN 0·연결 불가 1·사용법 2·
                                                  # --classpath·--json, 8u242는 -Pagent.jdk8u242=<JRE>, 약 2.5분), tags.AppTagsTest(T-AG-066a~d: 매니페스트 app_version·-Dargos.app.version 우선·
                                                  # env 없음 default + WARN·JSONL env/version), attach.PremainTimeAttachTest(T-NFR-30: 부착 JDK마다 배너 1줄 started in ≤ 500ms(공유 머신은 최대 3회 중 최솟값,
                                                  # INVALID 참고값, N-30 판정은 전용 장비)·그 밖 stdout/stderr 0). 결과 줄 [wo19-slowstack]·[wo19-errorstack]·[wo19-check]·[wo19-tags]·[wo19-premain]은
                                                  # 결과 XML system-out, 로그 build/reports/agent-e2e/wo19-*. 단위: core.watch(SlowStackWatcherTest T-AG-064a~e, OverheadMonitorTest T-AG-064f),
                                                  # core.trace ErrorStepTest·TopFrameTest, core.check CheckCommandTest(T-AG-068a), core.config AppVersionTest. VT 스택 비용 [wo19-u13]은 :agent:agent-core:testJdk21·25
./gradlew :agent:agent-testing:test --tests '*ModuleReadmeLint*'   # 계측 모듈 README 린트(T-BLD-017 제안, 03 §3.1, check 포함, 1초 미만): 각 README 'Supported library versions'의
                                                  # 'Version range and witness' 표(최소·최대 검증 버전·경계 시험 클래스 존재), witness = 모듈 소스 witness(), :agent: 태스크 존재, [wo19-readme].
                                                  # 위반 픽스처 src/test/resources/readme-fixtures. 새 모듈·witness 변경·시험 클래스 이름 변경 때 README도 고친다
./gradlew :agent:agent-core:integrationTest --tests '*ContextStoreFootprint*'   # C-120 (3) ContextStore 항목당 바이트(포크 JVM 클래스 히스토그램, 약 5초, [c120-contextstore])
java -jar agent/agent-dist/build/libs/argos-agent-<버전>.jar --check [--config <yml>] [--json] [--classpath <dir|jar>...]
                                                  # 설치 전 점검(10 §13.3, docs/agent-config.md "Pre-flight check"): 설정·토큰·DNS/TCP/TLS·hello·시계·JDK·클래스패스 호환성·로그 디렉터리/토큰 파일 표
                                                  # (또는 JSON 1개), 종료 코드 0(FAIL 없음)·1(FAIL)·2(사용법 오류). 토큰은 argos_****. Hello에 check_only(C-240 (1))를 실어 현재 서버는 인스턴스를
                                                  # 등록하지 않고(세션 id 없음, 스트림 OK 종료, 상태·알림 없음) Bye도 보내지 않는다. 이전 서버는 '<instance|host>-check'를 등록하므로 곧바로 AgentBye
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
                                                  # bootJar는 :server:verifyServerJar(T-BLD-003a)로 끝난다: 루트 META-INF의 LICENSE·NOTICE·THIRD-PARTY-NOTICES·argos-deps.txt(= BOOT-INF/lib)·
                                                  # sbom/server.cdx.json(:server:serverSbom → server/build/sbom/server.cdx.json, 시각 SOURCE_DATE_EPOCH 또는 1980-02-01)·
                                                  # argos/licenses/{server,agent,ui}.json(C-153 About 목록, ui는 UI 포함 빌드만). 비Apache 원문은 server/src/notices(README에 SHA-256, 버전 올리면 갱신)
java -jar server/build/libs/argos-server-<버전>.jar --version   # 'Argos server X (commit c, protocol p[, built t])' 1줄, Spring 미기동, 종료 0(O-R12, T-BLD-021)
java -jar server/build/libs/argos-server-<버전>.jar admin rollup rebuild --date <yyyy-MM-dd> [--table <t>]... [--json]
                                                  # argos-admin(WO-27, C-59): 그날 1m 롤업과 같은 달 1h 롤업을 <t>_rebuild 스테이징 + REPLACE PARTITION으로 재구축(--app 없음, C-59 (c)),
                                                  # 파티션별 재구축 전후 digest(키 수 + 키별 cityHash64 합)를 출력. 설정은 서버와 같은 ARGOS_* 환경(migrator 계정), 로그는 stderr JSON, 결과는 stdout.
                                                  # 종료 코드 0 성공, 1 실패(롤업 락 점유·스키마 미적용·DB 오류·보존 밖·미래 날짜), 2 인자 오류. 세 명령과 야간 잡이 PG advisory lock 하나를 공유
java -jar server/build/libs/argos-server-<버전>.jar admin consistency check --date <yyyy-MM-dd> [--rebuild] [--json]
                                                  # 야간 정합(argos.ops.reconcile.cron, 기본 03:00 서버 시각, 전날 UTC)의 수동 실행: D-03(xlog 분별 count 대 app/service/instance_stat_1m),
                                                  # 원본 대 1m 롤업, 1m 대 1h, D-12(Σsql/ext_count 대 sql/ext_stat_raw, 기록만). --rebuild면 재구축 후 재검사(최대 2회). 결과는 reconcile_results(PG V005),
                                                  # 종료 코드 0 롤업 불일치 없음, 1 남음·실패, 2 인자 오류(D-12는 종료 코드에 반영 안 함)
java -jar server/build/libs/argos-server-<버전>.jar admin apdex reapply --app <id> [--service <unsigned id>] --from <d> --to <d> [--json]
                                                  # Apdex T 소급(D-17, 수동 실행만): 원본 보존 기간 안의 xlog.apdex_t_ms를 현재 T(앱·서비스 오버라이드)로 뮤테이션(mutations_sync=2)한 뒤 그날들을 재구축. 최대 400일
java -jar server/build/libs/argos-server-<버전>.jar admin token create --app <name> --env <env> [--expires-days <n>] [--actor <name>] [--json]
                                                  # 에이전트 토큰 발급(WO-26, C-173 (4)): 서버 TokenService로(앱이 없으면 생성, 앱당 활성 5개, SHA-256 해시 + 14자 접두사만 저장, 감사 TOKEN_ISSUED),
                                                  # 기본 만료 365일(1~3650). 토큰은 stdout에 1회. 종료 코드 0 성공, 1 실패, 2 인자 오류, 4 활성 토큰 한도. argos-compose.sh token create가 서버 컨테이너 안에서 이것을 실행
java -jar server/build/libs/argos-server-<버전>.jar admin <command> ... [--json]
                                                  # argos-admin 17 §4.4 전부(WO-28, docs/ops/admin-cli.md, 사용법 admin help): migrate, config effective [--changed]|check, cleanup instances|dictionary|commands,
                                                  # token issue(= token create)|revoke (--id|--prefix) --reason|rotate [--grace-days], setup-token regenerate(관리자 없을 때만, 0600 파일), tls reload [--wait s]
                                                  # (<data>/tls/reload.request를 서버가 5초마다 확인), retention show|apply(PG settings 값이 설정 파일보다 우선, MODIFY TTL·보존 밖 DROP),
                                                  # secrets rekey [--new-key <path>] [--batch n](별칭 master-key rotate --new-key-file, 채널·서명 키·명령 결과·앱 user_id 솔트 재암호화, MASTER_KEY_ROTATED),
                                                  # secrets verify [--purpose backup|restore|manual] [--json](WO-43: 현재·이전 키로 SecretField 4열 복호화만 시도, 읽기 전용, kid = SHA-256(key)[0:8] 16hex,
                                                  # verifiable·failed·열별 kids, 종료 0 전부 복호화 또는 봉투 없음·1 실패·키·DB 문제·2 인자, --argos.postgres.url=…·--argos.master-key-file=… 덮어쓰기, ADMIN_COMMAND_EXECUTED),
                                                  # CLI token revoke·rotate도 실행 중 서버가 2초마다 PG를 확인해 5초 안에 그 토큰의 세션을 닫는다(TokenRevocationWatcher, C-238 (14) 해소),
                                                  # bundle [--since 24h|7d] [--out dir] [--log-dir dir](argos-diag-<host>-<ts>.tar.gz 0600, 토큰·비밀번호·마스터 키·채널 URL 마스킹), version.
                                                  # stdout은 결과(--json이면 JSON 1개, 실패 {command,error,code}), stderr는 메시지. 종료 코드 0/1/2/4. version 외 전부 감사(전용 이벤트 없으면 ADMIN_COMMAND_EXECUTED,
                                                  # rollup·consistency·apdex는 ROLLUP_REBUILT·CONSISTENCY_CHECKED·APDEX_REAPPLIED, C-220 (3)), 감사 싱크는 서버와 같은 argos.audit.sinks(PG가 죽으면 폴백 파일).
                                                  # 서버는 마스터 키(argos.master-key-file)가 없거나 형식이 틀리거나 POSIX에서 그룹·타인 읽기 가능하면 KEYS 단계에서 기동 거부(C-226 (2)),
                                                  # argos.security.master-key.permissive=true는 권한 검사만 건너뛰고 매 기동 WARN, Windows는 검사 불가 INFO 1줄. 시험 서버는 ServerTestEnvironment.springProperties()의 TestMasterKey.
                                                  # 감사 싱크 argos.audit.sinks: postgres(필수), file:<path>(JSONL 100MiB×10, 0600), syslog://h:514(UDP)·syslog+tcp://·syslog+tls://(RFC 5424, SD-ID argos@32473),
                                                  # 실패 기록은 argos.audit.fallback-file, argos.retention.audit ≥ 90일(V014가 DB에서도 90일 안 DROP 거부)
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
                                                  # WO-36 통계 화면용 가산 매개변수(sql·external sort=p95_ms, errors/groups compare_prev, T-API-011a/b)는
                                                  # --tests 'io.argos.server.api.stats.*'(StatsScreenParamsIT, 앱 id 930, 약 1분)
                                                  # WO-38 알림 화면용 가산(alert_events.reason V009 닫힌 목록, 이벤트·/overview/alerts·대시보드 firing[]의 rule.metric/unit,
                                                  # 규칙 이력 changed_by_name, T-API-012a)은 --tests 'io.argos.server.api.alerts.*'(AlertsApiIT, 약 30초)와 MigrationStartupIT(V009 CHECK·멱등)
                                                  # WO-22 세션·상태·실시간(@Tag("slow") 없음, 클래스당 약 10~35초): io.argos.server.session(InstanceStateIT·ClockSkewIT·OutageIT는 MutableClock으로
                                                  # 60/180초·Bye 유예 5분·수집 중단 outages[], RegistrationPolicyIT 승인 대기 큐, InstanceNamingIT 이름 병합·보관 SQL 경계),
                                                  # io.argos.server.realtime(WebSocketProtocolIT·RealtimeE2eIT: JDK WebSocket 클라이언트 testFixtures io.argos.server.testing.ws.WsTestClient로
                                                  # 실제 /ws, FakeAgent → xlog push ≤ 1초, 서버 시계를 옮기므로 WS는 점프 사이에 ping), ingest.ActiveGaugeIT(C-178 (1) series.active).
                                                  # 보고서 server/build/reports/realtime/ws-latency.md(참고값)·vt-pinning.md(JFR jdk.VirtualThreadPinned, 12 미결 4).
                                                  # WS 서버 프레임 벡터는 단위 테스트 WsFrameVectorsTest(check 포함)가 고정본 server/src/test/resources/io/argos/server/realtime/ws-frame-vectors.json과
                                                  # 대조하고, 다르면 생성본을 server/build/ws-frame-vectors.generated.json에 쓴다(의도한 변경이면 복사해 커밋, ui/e2e/fixtures/wsServerFrames.test.ts가 같은 파일을 읽음)
                                                  # WO-27 통계·롤업·정합: --tests 'io.argos.server.ops.rollup.*'(약 1.5분) = RollupRebuildIT(T-SV-019·T-ACC-14 재구축 digest 동일·월 경계·1h 이중 집계 없음·CLI 종료 코드),
                                                  # ReconcileJobIT(손상 주입 → 탐지 분 수·reconcile_results·게이지·자동 재구축·재검사 0, D-12 기록만), ApdexReapplyIT(T-ACC-17 소급).
                                                  # 서버 RollupRebuildIT와 test/storage RollupRebuildIT는 단순 이름이 같으므로 --tests에는 FQCN을 쓴다.
                                                  # io.argos.server.ingest의 StatsRollupIT(T-SV-018)·FullStatsIT(T-ACC-12 서버 측 1만 건)·ApdexOverrideIT(T-SV-017·T-ACC-17, WAL format 2)·
                                                  # ErrorGroupStackIT(T-SV-016e)는 클래스마다 자체 서버(AgentServerStack, 합계 약 1.5분). realtime.CounterMetricsIT: FakeAgent MetricBatch →
                                                  # counter push heap_used·gc_pause_ms·cpu(C-212 (1), 약 40초), api.dashboard.DashboardC212IT: system_cpu·증감·exclude_instance·instance_stat_1h(약 50초)
                                                  # WO-25 알림: --tests 'io.argos.server.alert.*' --tests 'io.argos.server.api.alerts.*'(약 3분) = AlertInstanceStateIT(T-SV-023 Bye 후 5분 안 재접속 다운 알림 0·워밍업 3분 미평가·
                                                  # WS alert push·ACK·재기동 복원), AlertTemplatesIT(T-SV-033 기본 템플릿 14종·권장 규칙·서버 규칙 1회·템플릿 차이), AlertDeliveryLatencyIT(T-SV-030 조건 충족 → 웹훅 수신 ≤ 30초,
                                                  # [T-SV-030] 줄이 결과 XML system-out), channel.NotifierChannelFailureIT(T-SV-034 재시도 3회·채널 failing → 다른 채널 self 알림, DeliveryCleanupJob 365일 정리),
                                                  # AlertsApiIT(/alerts REST·대시보드·overview firing 실값·channel_failures_24h). 가짜 수신기는 testFixtures io.argos.server.testing.alert의 FakeWebhookReceiver(HTTP/HTTPS·302·500·지연)·
                                                  # FakeSmtpServer(STARTTLS 켜기/끄기)·TestTls·TestMasterKey(실행 시 생성 0600 키 파일 → argos.master-key-file). 개발 빌드 전용 키 argos.testing.notify-retry-delays(재시도 1s/5s/30s 단축)·
                                                  # argos.testing.alert-ca-file(시험 CA 신뢰), 루프백 수신기는 argos.alert.webhook.allow-cidrs[0]=127.0.0.1/32. 외부 Slack·SMTP는 호출하지 않는다
                                                  # WO-26 토큰·명령·설정 변경·앱 관리: --tests 'io.argos.server.admin.*'(약 6분) = TokenLifecycleIT(T-SV-040 발급 → FakeAgent 세션 → 회전 유예 → 폐기 ≤ 5초
                                                  # UNAUTHENTICATED → 재접속 거부, 만료 잡, T-SV-040j argos-admin token create), CommandRoutingIT(T-SV-041 FakeAgent 스레드 덤프 왕복·PG body_enc 봉투·청크 조립·
                                                  # 동시 1건·세션 종료 expired), ConfigChangeIT(T-SV-042 applied/rejected/offline/pending·다음 ServerHello.config·revert·drift·감사),
                                                  # ConfigPushScaleIT(T-SV-031 FakeAgent 50개 설정·킬 스위치 결과 집계 ≤ 10초, [T-SV-031] 줄, 노트북 값은 참고), ApprovalQueueIT(승인 대기 API).
                                                  # --tests '*AppsAdminIT' --tests '*EventsWriteIT' --tests '*SelfSessionsIT'(약 1분): 앱 생성·수정·삭제·app_packages·서비스 Apdex 오버라이드,
                                                  # POST/DELETE /events(쿠키 또는 앱 토큰 Bearer), 내 세션 목록·종료·사용자 삭제. 실제 에이전트의 명령·ConfigPush 왕복은 WO-17 몫(FakeAgent 훅 onCommand·onConfigPush)
                                                  # WO-28 감사·개인정보·보존·저장소·자기 메트릭·CLI: --tests 'io.argos.server.audit.*'(AuditSinksIT JSONL·syslog UDP/TCP 가짜 수신기·PG 폴백, AuditApiIT 필터·커서·CSV,
                                                  # AgentAuditIT 에이전트 감사 3종 레이트 제한, 약 1분), 'io.argos.server.privacy.*'(PrivacyDeletionIT plain·hashed·IP 삭제 → system.mutations 폴링·상태·429·
                                                  # PRIVACY_SETTING_CHANGED, UserIdSaltIT ServerHello 솔트·hashed 검색, MutationDurationIT T-ST-054 @Tag slow 생성기 ci 하루 파티션 삭제 소요 약 4분
                                                  # → server/build/reports/privacy/mutation-timing.md, 참고값), 'io.argos.server.ops.retention.*'(RetentionApplyIT T-ST-040 MODIFY TTL·confirm·CLI, AuditPartitionJobIT 90일 하한),
                                                  # 'io.argos.server.ops.storage.*'와 'io.argos.server.storage.DeadLetterIT'(StorageHealthIT, EmergencyRetentionIT: 개발 전용 argos.testing.disk-usage-file로 사용률 주입 →
                                                  # 0.9 DROP 13 §8 순서·0.85 멈춤·0.95 삽입 정지·0.93 재개, DeadLetterIT 결정적 거부 3회 → 0600 영역, [wo28-emergency]·[wo28-dead-letter], 약 35초),
                                                  # 'io.argos.server.ops.management.*'(SelfMetricsIT /actuator/prometheus 12 §9.4 이름 전수·metric app_id=0 5초 격자), 'io.argos.server.cli.*'(AdminCliOpsIT·AdminCliSecretsIT
                                                  # 서브커맨드별 --json·종료 코드·감사 행, 약 30초), session.RegistrationPolicyIT(앱 정책 > env 기본, C-231 (7)), alert.AlertInstanceStateIT(보관·삭제 → target_gone)
                                                  # WO-39 관리 화면용 서버 가산: --tests '*RetentionQueryBoundaryIT' --tests 'io.argos.server.api.about.*'(각 약 12~15초) = T-ST-040g(보존 PUT 뒤 재기동 없이
                                                  # retention_exceeded 경계 변화, C-238 (3)), T-API-013a(GET /about·/about/licenses, 목록 파일 META-INF/argos/licenses/<part>.json 없으면 not_generated).
                                                  # session.OverviewStateIT T-SV-022a(WS overview status = REST 기준, C-218 (3)), ConfigChangeIT(C-239 (3) CUSTOM_METHOD_CHANGED 행위자 = applied_by).
                                                  # 설정 argos.ui.active-thresholds-ms(기본 1000,3000,8000, 양수 3개 오름차순 아니면 WARN 1줄 + 기본값)는 GET /apps/{id}/active의 default_thresholds_ms(REST 전용,
                                                  # 화면은 뷰어 localStorage > 이 값 > 1/3/8초, C-225 (3), T-API-013c)
                                                  # WO-29: session.CheckOnlyHelloIT(T-SV-022b check_only Hello: 등록·대기열·세션 상한·상태·알림 없음, 약 30초)·session.AgentStartEventIT(T-SV-023b
                                                  # 새 agent_epoch의 첫 Hello마다 events type='start', 같은 프로세스 재접속·서버 재기동 뒤 재접속은 없음, 약 50초),
                                                  # --tests 'io.argos.server.ops.dictionary.*'(DictionaryCleanupJobIT T-SV-014d~f: 야간 사전 정리 잡(C-238 (14), argos.ops.dictionary-cleanup.cron
                                                  # 기본 '0 30 3 * * *'·enabled 기본 true) last_seen 90일 경계·재실행 0·삭제 키 캐시 무효화 뒤 재요청·재삽입·10만 행 삭제 [wo29-dict-cleanup])
                                                  # --tests 'io.argos.server.ops.errorgroup.*'(ErrorGroupCleanupJobIT T-SV-014h·i, 약 30초: 야간 에러 그룹 정리 잡(ADR-0010 C-255 (5),
                                                  # argos.ops.error-group-cleanup.cron 기본 '0 45 3 * * *'·enabled 기본 true) last_seen 90일 경계·재실행 0·2.5만 행 배치 삭제·
                                                  # 정리 전 재발생 그룹 유지·삭제 뒤 재발생은 upsert로 새 그룹 재삽입·API 카탈로그에서 제외 [c255-error-group-cleanup])
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
./gradlew :server:activeFeedJar                   # 개발 전용 FakeAgent 액티브 피더(WO-35, 배포물 아님) → server/build/active-feed/active-feed.jar + lib/. 액티브 E2E의 전제
                                                  # (실제 에이전트의 ActiveTransactionSnapshot은 WO-16 몫). 앱 e2e-active·인스턴스 2개로 실제 수집기에 5초마다 스냅샷을 보낸다.
                                                  # java -jar <jar> --target H:P --token-file T --instances 2 --ready-file F [--duration s] [--interval s] [--app] [--env]
                                                  # [--commands](WO-37, 기본 꺼짐)이면 thread_dump(데드락·VT·64프레임·XSS 이름, 인스턴스 2는 truncated)·thread_stack·
                                                  # self_diagnosis(실제 에이전트 형태: effective_config 맵·modules 사유)·env_dump(text/plain 구획, '****(len=27)', 원문 센티널 없음)·reload_config에 결정적으로 응답(C-234 (1))(E2E 픽스처 e2e/fixtures/commandFeed.ts, 앱 e2e-cmd)
./gradlew :server:jibDockerBuild                  # 로컬 이미지 argos-server:<버전>(linux/amd64, 베이스 다이제스트 고정, user 10001:10001, HOME=/data, 포트 8080·9100, UI 포함.
                                                  # -Pargos.ui.skip=true면 UI 없음). Jib은 구성 캐시 미호환. 서버 jar를 다시 만들므로 server-it 락 안에서 돌린다(test/lock/argos-lock.sh run).
                                                  # /data·/data/tmp·/logs는 이미지에서 10001:10001 0700이라 새 named volume은 준비가 필요 없다(bind mount만 chown, server/README.md)
bash deploy/compose/test/run-all.sh               # Compose 설치 스크립트 테스트(Docker, 이 노트북 약 4분, 약 2,550건): env-init을 Ubuntu·Alpine·Debian 컨테이너에서(권한·길이·금지 목록·종료 코드·
                                                  # RAM 8/16/32GB 스냅샷·--force 거부·--self-signed-tls T-SEC-74a), argos-compose.sh(미지 명령 거부·up 사전 검사·token create 입력 상한·서버 컨테이너 exec 경로(가짜 docker)·SAN 예시·
                                                  # upgrade 규칙 T-NFR-11a), backup.test.sh(backup.sh·restore.sh 논리, 약 50초: T-SEC-84a~c 0700/0600·보존·gpg, T-NFR-20c 같은 디스크 거부, T-SEC-52e kid 불일치 무변경, T-SEC-73),
                                                  # diag.test.sh(diag-bundle.sh T-SEC-84d·T-SEC-06i 마스킹), smoke.sh 판정 로직과 변이(5432 게시·.env 0644 등), compose 파일(다이제스트 = image.properties, 포트 집합, 프록시 XFF·INTERNALPROXIES,
                                                  # chbackup 오버레이·backups.xml, .env.example)
./gradlew :deploy:composeSmoke                    # Compose 종단 스모크(WO-40, Docker, check 미포함, 약 3.5분 + 이미지 빌드): jibDockerBuild → env-init → up --wait → smoke.sh
                                                  # → token create(서버 컨테이너 안 argos-admin, WO-26) + 실제 에이전트(shop-boot3, TLS) 적재 → ops-report.sh(WO-42, 종료 0·비밀값 0) → 5개 상한 → ARGOS_MGMT_EXPOSE 변형 → down -v, 마법사·무인 두 모드와 금지 비밀번호 기동 거부.
                                                  # 마법사 모드는 env-init --self-signed-tls 인증서(에이전트 ca-file)로 접속하고 끝에 argos-compose.sh diag를 풀어 심은 비밀값 grep 0(T-SEC-06i, WO-43).
                                                  # compose-smoke.sh가 server-it 락을 스스로 잡는다(앞의 jibDockerBuild까지 묶으려면 명령 전체를 argos-lock.sh run으로 감쌈). 호스트 포트 8080·9100을 쓴다(프로젝트 argos-wo40-smoke, 컨테이너 ≤ 6GiB 상한). -Pcompose.smoke.modes=wizard|unattended,
                                                  # 진단은 ARGOS_SMOKE_KEEP_ON_FAILURE=1 bash deploy/compose/test/compose-smoke.sh. Linux Docker Engine에서는 root로(비밀값을 uid 10001에 넘김)
./gradlew :deploy:backupRoundTrip                 # 백업·복구 실스택(WO-43, Docker 약 6 GiB, check 미포함, server-it 락을 스스로 잡음, 프로젝트 argos-wo43-backup, 이 노트북 약 8.5분(jibDockerBuild 포함), INVALID 참고):
                                                  # jibDockerBuild·agentJar·shop-boot3 → env-init --unattended → 실제 에이전트 → backup.sh --clickhouse(tmpfs 볼륨 대상) → 변경 → restore.sh --clickhouse = 백업 시점 상태(T-NFR-20a),
                                                  # 다른 유효 키 → 종료 1·라이브 DB 불변(T-SEC-52e), PG만 이전 시점 복원 → WARN·healthy·재접속·조회 API 5xx 0·백업 뒤 토큰 거부(T-NFR-20b), docker kill -s KILL·down/up 중
                                                  # count(xlog) = 보낸 요청(T-ACC-13 (b) Compose), 재라벨 이미지 upgrade·rollback(T-NFR-11a), 같은 디스크 대상 거부(T-NFR-20c), 출력·로그 비밀값 0(T-SEC-73).
                                                  # 결과 줄 [wo43-roundtrip]·[wo43-wrongkey]·[wo43-pitr]·[wo43-kill]·[wo43-downup]·[wo43-upgrade]·[wo43-chsame]. 실패 시 ARGOS_RT_KEEP_ON_FAILURE=1이면 스택 보존, 앱 포트 ARGOS_RT_APP_PORT(기본 18094).
                                                  # Docker Desktop(Windows)은 Windows 경로가 약 180자를 넘는 단일 파일 bind mount를 비root 컨테이너(ClickHouse uid 101)가 읽지 못하므로(EIO) TMPDIR은 짧게(기본 %TEMP%)
cd deploy/compose && ./argos-compose.sh backup [--clickhouse] [--encrypt age:<recipient>|gpg:<key>] [--label L] [--json]   # 운영 스크립트(WO-43, POSIX sh, docs/ops/backup-restore.md):
                                                  # backup.sh = pg_dump -Fc(argos_backup, 컨테이너 안) + manifest(이미지·Flyway·kid·sha256), 세트 ARGOS_BACKUP_DIR(기본 ./backups) 0700·파일 0600, 보존 ARGOS_BACKUP_KEEP_DAYS(7, CH 증분 체인 보호),
                                                  # --clickhouse는 ARGOS_CH_BACKUP_DIR(compose.chbackup.yaml, 다른 디스크만, 같은 디스크면 종료 1), 암호화는 호스트에 이미 있는 age·gpg만(없으면 종료 2, 내려받지 않음).
                                                  # ./argos-compose.sh restore <세트> [--clickhouse] [--master-key <file>] [--yes] [--json]: manifest·sha256·kid 비교 → 서버 정지 → 스테이징 DB argos_restore → secrets verify →
                                                  # 이름 교체(실패면 라이브 DB 불변, 종료 1) → CH DROP+RESTORE → migrate → 시점 불일치 WARN → 기동 → consistency check(경고만). Linux는 root.
                                                  # ./argos-compose.sh upgrade --image <ref> [--yes] | upgrade --rollback: healthy 확인·SemVer 규칙(다운그레이드·메이저 2단계 이상 종료 2) → pre-upgrade 백업(실패면 1, 무변경) →
                                                  # .env ARGOS_SERVER_IMAGE 교체·up --wait(실패면 종료 8 = 롤백 필요). ./argos-compose.sh diag [--since 24h] [--out dir]: 서버 컨테이너 argos-admin bundle + compose ps·이미지 digest·
                                                  # 마스킹한 .env·CH/PG 로그 → argos-diag-compose-<host>-<UTC>.tar.gz 0600. ./argos-compose.sh files: 쓰이는 compose 파일 목록
cd deploy/compose && ./argos-env-init.sh ... --self-signed-tls <host>[,<host>...]   # 평가 전용 자체 서명 gRPC 인증서(C-172 (1), WO-43): SAN = 주어진 DNS·IP, EC P-256 365일, tls/server.key 0600(uid 10001)·0700 디렉터리,
                                                  # 평가 전용 WARN, 기본값 아님, openssl 없거나 기존 tls/server.* 있는데 --force 없으면 종료 2. 에이전트는 tls/server.crt를 server.tls.ca-file로
./gradlew :sample-apps:demo-app:jibDockerBuild :sample-apps:load:jibDockerBuild   # 데모 이미지(WO-41, 로컬 데몬 linux/amd64, 서버와 같은 베이스·uid 10001·OCI 라벨): argos-demo-app:<버전>
                                                  # (order-api·payment-api 겸용, DEMO_ROLE=order|payment, 에이전트 /opt/argos-agent/argos-agent.jar, PostgreSQL만, C-43·C-186 (1)),
                                                  # argos-demo-load:<버전>(TARGET_URL·RATE=50·MIX=demo). multi-arch·GHCR·서명은 WO-44. :sample-apps:demo-app:integrationTest는 Testcontainers PostgreSQL(약 1분)
cd deploy/compose && ./argos-env-init.sh --demo && docker compose --profile demo up -d   # 데모 프로파일(17 §3.9, docs/install/compose.md 14장): --demo = 무인 설치 + IP masked + 127.0.0.1 게시 +
                                                  # secrets/demo_{order,payment}_api_token·demo_db_password(0600) + .env의 ARGOS_DEMO_MODE=true·COMPOSE_FILE(compose.demo.yaml)·COMPOSE_PROFILES=demo.
                                                  # 메모리 최소 7396 MiB(데모 예약 1536). 제거 docker compose --profile demo down -v. 서버 데모 모드 = 평문 gRPC 기본값·기동 WARN·UI 배너(setup/status demo_mode),
                                                  # 앱·토큰 등록은 argos.bootstrap.apps-file(ARGOS_BOOTSTRAP_APPS_FILE, config/demo/bootstrap-apps.yaml)
./gradlew :deploy:demoSmoke [-Pdemo.smoke.enforce=true] [-Pdemo.smoke.long=true]   # 데모 종단(WO-41, Docker 7 GiB·Node.js, check 미포함, server-it 락을 스스로 잡음, 프로젝트 argos-wo41-demo, 127.0.0.1:8080·9100):
                                                  # 이미지 3종 빌드 → env-init --demo → up -d(수동 chmod 없음: Docker Desktop은 데모 오버레이의 ARGOS_SECURITY_MASTERKEY_PERMISSIVE, C-243 (1)) → T-NFR-16 첫 XLog 점(실제 API) → build/reports/demo/first-xlog.md(노트북 32초, INVALID 참고) → payment·느림·에러·N+1 21·
                                                  # 분산 트레이스·BATCH ≤ 150초·권장 규칙·통계·smoke.sh·게시 포트·uid·비밀 누출 0 → down -v 잔여 0. 약 2분(+ 이미지 빌드). enforce면 60초 초과 실패(조용한 머신),
                                                  # long이면 order-api error_rate FIRING까지(노트북 509초, 총 약 9분). ARGOS_DEMO_KEEP=1이면 내리지 않고 URL·새 관리자 비밀번호 파일 경로 출력(스크린샷용)
                                                  # 서버 쪽: --tests 'io.argos.server.demo.*'(DemoBootstrapIT T-NFR-16a·16d, :server:integrationTest, 약 30초). run-all.sh에 env-init --demo·compose 데모 파일·argos-compose 데모 분기 포함
deploy/compose/smoke.sh                           # 설치 상태 검사(운영자용, POSIX sh, 기본 읽기 전용): .env·secrets/ 권한, 게시 포트 {8080, 9100}(+127.0.0.1:8081), ss -ltnp, 이미지 다이제스트,
                                                  # back internal, uid 10001, health, /actuator 404, 설정 토큰 403. --complete-setup(마법사 API로 관리자 생성, 테스트 설치용),
                                                  # --check-admin-login(무인 설치 직후 로그인·변경 강제·로그아웃). 설치 절차는 docs/install/compose.md(ko: compose.ko.md)
deploy/compose/ops-report.sh [--since <UTC>] [--date <yyyy-MM-dd>] [--backup-dir <dir>]
                                                  # 읽기 전용 일일 점검 Markdown(WO-42, 운영자용, POSIX sh): 컨테이너·전날 UTC 야간 정합(reconcile_results를 postgres 컨테이너 안 argos_backup 읽기 전용 트랜잭션으로)·
                                                  # 적재 지연·드롭(서버 컨테이너 안 /actuator/prometheus, 메트릭 토큰은 curl 표준 입력)·디스크·알림·인스턴스·인증서/토큰 만료·최신 백업. 이름·토큰·주소 출력 0, 쓰기·외부 전송 0.
                                                  # 종료 0 수집 완료, 1 구획 FAILED, 2 사용법 오류. 시험 bash deploy/compose/test/ops-report.test.sh(가짜 docker, Ubuntu·Alpine, T-BLD-030 제안, 약 20초, run-all.sh 포함),
                                                  # 실스택은 :deploy:composeSmoke의 ops-report 단계. 도그푸딩 절차·매일/매주 점검·운영 일지·문제 기록·비교 표 템플릿은 docs/dogfooding/(기록은 비공개 저장소),
                                                  # 공개 익명화 요약은 docs/field-reports/, 0.1 업그레이드 노트(에러 그룹 분할, C-240 (9))는 docs/upgrade/0.1.md
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
                                                  # WO-22 /ws(WebSocketSecurityTest): T-SEC-44 핸드셰이크 401(쿠키 없음·위조)·403(교차 Origin), 로그아웃·관리자 폐기·비밀번호 변경·비활성 → reauth + close 4401 ≤ 1초,
                                                  # 역할 강등 시 admin 구독 forbidden, 로그에 토큰 없음(자기 서버만 realtime DEBUG), T-SEC-45 /ws 거부 응답의 보안 헤더
                                                  # WO-25 알림 채널(io.argos.server.security.alert): ChannelTlsSecurityTest(Docker 불필요, T-SV-035 STARTTLS·HTTPS 고정 IP·T-SEC-54 평문 SMTP 거부),
                                                  # AlertChannelSecurityTest(SecuritySuite 환경: T-SEC-51 목적지 검증 7단계·금지 대역 422 webhook_destination_blocked·리다이렉트 미추종·allow-cidrs, T-SEC-52 PG 덤프에 평문 비밀값 0·
                                                  # 마스킹 GET·이전 키 복호화, T-SEC-53 서명 회전 v1 두 개, 키 없음 409 secret_unavailable, 서버 로그에 비밀값 0)
                                                  # WO-26(io.argos.server.security.{token,command,configchange}, 약 5분): AgentTokenSecurityTest(T-SEC-01~05 앱 범위·해시만·로그 0·no-store·만료·유예·폐기 ≤ 5초·
                                                  # 세션 61번째·IP 인증 실패 21번째 RESOURCE_EXHAUSTED), CommandSecurityTest(T-SEC-21 3역할 × 5명령 실제 로그인 세션, T-SEC-50 타인 결과 403·UUIDv4,
                                                  # T-SEC-22 command_results 평문·센티널 0, T-SEC-24 TTL·COOLDOWN), ConfigChangeSecurityTest(T-SEC-23 서버 측 하드 캡: 허용 밖 키는 그 인스턴스에 푸시 안 함)
                                                  # WO-28(io.argos.server.security.{audit,secrets,tls,diagnostics}): AuditSecurityTest(T-SEC-81 열 전수, T-SEC-82 argos_app UPDATE·DELETE·TRUNCATE 42501·syslog 형식),
                                                  # ChannelAuditSecurityTest(CHANNEL_* 행), MasterKeySecurityTest(T-SEC-52c 키 없음·형식 오류·0644 기동 거부(비POSIX는 사유 skip), T-SEC-52d·22b rekey 뒤
                                                  # PG 평문 0·새 kid·이전 키 제거 후 열람), CertReloadSecurityTest(T-SEC-09a~d 만료 게이지·cert 규칙 FIRING·파일 교체 무중단 리로드·key-password-file·reload API·watch=false),
                                                  # DiagnosticBundleSecurityTest(T-SEC-06g 심은 토큰·비밀번호·마스터 키·웹훅 URL·메트릭 토큰 → 풀어 낸 번들 grep 0)
                                                  # WO-46: catalog.SecuritySuiteCatalogSecurityTest(T-SEC-93, 컨테이너 없음, 약 4초): test/security/sec-gate1.tsv(Gate 1 보안 카탈로그: 06 단계 M 요구마다
                                                  # 테스트 ID·계층·실행 명령·근거)의 행 집합 = 06의 M 행, 나열된 ID가 테스트 소스에 정확히 존재 → server/build/reports/security/sec-gate1.md.
                                                  # 새 M 요구나 테스트 ID 이름 변경 때 tsv도 고친다. pii.PiiSeedGrepSecurityTest(T-NFR-10c, 약 2분): 자체 PG·CH + 서버 bootJar 자식 JVM + 실제 에이전트·shop-boot3에
                                                  # PII 시드 세트(seed-traffic.sh --set pii, 기본 1만 요청, -Psecurity.pii.requests=N) → ARGOS_SEED·ARGOSSEED를 CH String 열 전부·pg_dump 열 단위·서버·에이전트 로그·
                                                  # 알림 웹훅·SMTP 수신함·text/csv API 전부에서 0건, 양성 대조(user-id plain) 뒤 개인정보 삭제 API → 0 → server/build/reports/security/pii-seed-grep.md.
                                                  # securityTest는 agentJar·shop-boot3·smoke sampleAppJar를 빌드한다. tls.TestsslSecurityTest(T-SEC-07c, 약 16초): testssl.sh 3.2 컨테이너(다이제스트 고정,
                                                  # GPL dev-only)로 수집기 TLS 포트 → server/build/reports/security/testssl-collector.json. alert.SsrfVectorsSecurityTest(T-SEC-51f 표기 변형·51g 3xx 미추종, 약 25초)
                                                  # 웹 계층 스텁 매트릭스(Docker 없음)는 :server:contractTest → permission-matrix.md. 두 Gradle 실행이 같은 :server:securityTest를 동시에 돌리면 결과 파일이 깨진다
java server/scripts/GeneratePasswordDenylist.java # 비밀번호 금지 목록 해시 재생성(개발 도구, SecLists 10k를 고정 커밋·SHA-256으로 내려받음, 출처 NOTICE)
                                                  # → server/src/main/resources/security/password-denylist.sha256. 빌드·실행 시에는 아무것도 내려받지 않는다
./gradlew matrixTest -Pmatrix.combo=<id>[,<id>]  # 호환성 매트릭스(WO-50, 18 §6, test/matrix/README.md). 조합의 유일한 정의는 test/matrix/matrix.yaml(63행 T-MX-001~154, T-MX-017은 022의 별칭,
                                                  # build-logic MatrixCatalog가 구성 단계에서 검증: 모르는 ID·그룹·필드는 실패). 행마다 :agent:agent-testing:matrixTest-<ID>가 행의 app@jdk·vt·app-env(SAMPLE_CONTAINER 등)·
                                                  # tomcat·coexist·props로 기존 하네스 스위트와 src/matrixTest의 io.argos.agent.testing.matrix(MatrixSmokeTest 18 §6.1 시퀀스·ServiceNameBoundaryTest·
                                                  # WarRedeployTest(manager로 재배포 50회, T-AG-053w)·JspForwardTest·CoexistenceTest·Tls13Jdk8Test)를 돌리고, finalizer matrixResult-<ID>가
                                                  # build/matrix/<ID>.json(+ <ID>/의 *.properties·[mx-*] 줄)을 쓴다. -Pmatrix.group=<boot2|boot3|boot4|heavy|batch|war-javax|war-jakarta|jdbc|coexist|routing|
                                                  # containers|transport|boundary>[,...]. 선택도 러너 라벨(ARGOS_RUNNER_LABELS=argos-matrix)도 없으면 SKIPPED + WARNING 1줄. 조합은 순차, check 미포함,
                                                  # unverified 행은 실패해도 태스크는 녹색(결과는 ✗). 이 PC는 대표 조합만 agent-it 락 안에서 하나씩(전체 매트릭스·사내 DB·옛 JRE·Linux 전용 행은 러너, 사람 확인).
                                                  # Windows에서는 MatrixSmokeTest의 AgentBye 단계가 skip이라 boot 행은 △. 새 smoke·공존 변환 베이스라인(<app>-<jdk>[-vt]-smoke.txt, -coexist-<kind>.txt)은 -Pagent.baseline.record=true
./gradlew :agent:agent-testing:matrixReport [-Pmatrix.results=<dir>]
                                                  # build/matrix(또는 내려받은 nightly 결과 폴더)와 test/matrix/community-reports.yaml → docs/compatibility-matrix.md(생성본, 손 편집 금지, 시계·네트워크 없음).
                                                  # 판정 ○ 통과, ✗ 실패, △ 미실행·skip·requires 미충족·unverified·required 시험 skip(사유와 함께), ◎ 커뮤니티 보고(△ 행만). gate 실패 목록 build/matrix-report/gate-failures.txt
bash test/matrix/fetch-coexist-agents.sh [--dest <dir>]   # 공존 행(T-MX-110~113) 에이전트: OTel javaagent(test/bench/fetch-otel-agent.sh)·JaCoCo 0.8.14 runtime(SHA-256 고정, dev-only)
                                                  # → agent/agent-testing/build/matrix-agents(없으면 CoexistenceTest가 사유와 skip). devtools 행 T-MX-114는 :sample-apps:shop-boot3:sampleAppDevtoolsJar
                                                  # (build/sample-app/shop-boot3-devtools.jar, -Dspring.devtools.restart.enabled=true일 때 restartedMain)
                                                  # nightly: .github/workflows/nightly.yml(schedule·workflow_dispatch(scope·group·combo)·push main/release/**, W8): plan → matrix(self-hosted argos-matrix, 그룹별,
                                                  # max-parallel 4, environment trusted 비밀 ARGOS_TEST_<DB>_*, 변수 ARGOS_MATRIX_JDK8U242_HOME·ARGOS_MATRIX_JDK8U252_HOME·ARGOS_TEST_JDBC_DRIVER_DIR),
                                                  # server-nightly(:server:integrationTest -Pserver.nightly=true), security-full(securityTest, PII 시드 1만), sample-smoke, compose-smoke, demo-first
                                                  # (:deploy:demoSmoke -Pdemo.smoke.enforce=true) → report(matrixReport, gate 실패면 빨간색). 결과 페이지 PR 잡(publish)은 W4 허용 목록 대기로 주석
./gradlew :test:bench:benchTest -Pbench.machine-profile=<id>
                                                  # 오버헤드 벤치 전체 프로토콜(18 §7, bench-protocol.sh: main·startup·container·vt, 약 5시간). 벤치 머신 전용
./gradlew serverLoadTest -Pbench.machine-profile=<id> [-Pserver.load.profile=full|smoke|mini] [-Pserver.load.publish=true] [-Pserver.load.args="--tps=2000 --attack=none"]
                                                  # 서버 부하 시험(WO-29, 18 §8, test/load/fake-agents/README.md): Testcontainers PG·CH + 서버 bootJar 자식 JVM(17 §7.2 옵션·NMT·JFR 핀닝)에
                                                  # 프로토콜 수준 가짜 에이전트 부하 + 대시보드 사용자 10명 5초 폴링, 5초 샘플러(적재 지연·WAL fsync·parts·DelayedInserts·CH/PG 컨테이너·서버 RSS·힙·스레드),
                                                  # 측정 창 뒤 부하 중 공격 4종(gzip-bomb·batch-overflow·dict-flood·instance-flood, 다른 앱 지연·RSS 증가), 부하 뒤 유휴 세션 단계(C-157 (1) 세션당 바이트·5,000 외삽),
                                                  # 마지막에 unauth-flood(루프백 10분 차단). 종료 정합 D-13·OOM 0·CH 설정(N-18)·공격 거부 코드는 모든 프로필에서 단언(어기면 태스크 실패),
                                                  # 시간·자원 판정은 full(5,000 TPS·50,000 step/s·10,000 stat/s 1시간, CH 7.5GiB·PG 1.5GiB·-Xmx4g, 13 §7.1 장비)만 PASS/FAIL, 그 밖은 INVALID.
                                                  # smoke(1,000 TPS 5분, CH 4GiB·PG 1GiB·-Xmx2g) 이 노트북 약 17분, mini(200 TPS 90초) 약 3.5분. 보고서 build/reports/server-load/server-load.md
                                                  # (publish면 docs/bench/server-load.md), samples.tsv·class-histogram.txt·native-memory.txt. server-it 락 안에서, 다른 무거운 작업과 동시에 돌리지 않는다
./gradlew chaosTest -Pbench.machine-profile=<id> [-Pchaos.profile=short|full] [-Pchaos.scenarios=T-CH-001,T-CH-009] [-Pchaos.rate=100] [-Pchaos.publish=true] [-Pchaos.netem.image=<img@sha256>]
                                                  # 카오스(WO-52, 18 §11.1, test/chaos/README.md): Testcontainers PG·CH + 서버 bootJar 자식 JVM(TLS 수집기, 실행별 PKI) ↔ toxiproxy 2.12.0(다이제스트 고정, MIT dev-only)
                                                  # ↔ shop-boot3 + 실제 에이전트(JDK 21), 부하는 k6 대신 sample-apps/load(편차). T-CH-001 서버 중단(Windows는 강제 종료라 server_down 행 SKIPPED)·002 지연 500ms·
                                                  # 003 손실 10%(tc netem 변형은 Linux Docker Engine + -Pchaos.netem.image일 때만, 아니면 SKIPPED)·004 UNAVAILABLE/RESOURCE_EXHAUSTED/쿼터(서버 훅 아래)·005 로그 디스크 풀
                                                  # (컨테이너 1MB tmpfs)·006 DNS 실패(jdk.net.hosts.file)·007 TLS 이름 불일치·skip-verify·009 ClickHouse docker pause(WAL < 10GiB, 재적재)·008 instance_id 재할당(PG 초기화, 마지막).
                                                  # T-CH-010·011은 SKIPPED(WO-52 밖), T-NFR-05는 T-CH-001~003 집계 행. 불변식(수집 재개·에이전트 ERROR 0·드롭 계수 D-13·중복 0·collection-health 표시)은 항상
                                                  # PASS/FAIL, 시간·p99는 profile=full + argos-matrix만 PASS/FAIL(그 밖은 INVALID). short 9개 이 노트북 약 50~60분(메모리 약 6GiB), full 약 3.5시간.
                                                  # 보고서 test/chaos/build/reports/chaos/<run>/report.md·results.json·work/(publish면 docs/bench/chaos-<date>.md, 재변환 실패 WARN 줄 수 C-257 (2) 포함).
                                                  # server-it 락 안에서. 단위 T-CH-020a~o는 :test:chaos:check. 워크플로 .github/workflows/chaos.yml(argos-matrix, 주간·수동·release/** push).
                                                  # 서버 실패 주입 훅: 개발 빌드 전용 키 argos.testing.ingest-fault-file(파일 내용 <UNAVAILABLE|INTERNAL|RESOURCE_EXHAUSTED|QUOTA>[:ratio], 1초에 1회 읽음,
                                                  # 데이터 배치 4종만, 없거나 해석 불가면 꺼짐, 릴리스 빌드 무시). 시험 ./gradlew :server:integrationTest --tests '*IngestFaultHookIT'(T-CH-004a, 약 45초)
./gradlew soakTest -Pbench.machine-profile=<id> [-Psoak.duration=72h|15m] [-Psoak.tps=500] [-Psoak.vt=true] [-Psoak.publish=true] [-Psoak.args="--ch-memory=2g --app-heap=384m"]
                                                  # soak(WO-52, 18 §11.2, test/soak/README.md): shop-boot3·shop-batch + 에이전트(NMT) + ext-stub + 실서버 + PG·CH, load jar 연속 부하 + 배치 주기 실행.
                                                  # 에이전트 힙(io.argos. 히스토그램) 기울기·최대, NMT, argos-* 스레드, FD, 서버 RSS·WAL, CH parts·디스크, 정합(argos-admin consistency check), FIRING 알림, p99, OOM 증거.
                                                  # 불변식(OOM 0·정합·CH 설정, T-NFR-18)은 항상 PASS/FAIL, 시간·자원(T-SOAK-001)은 72h·500TPS·argos-bench-app*만 PASS/FAIL(그 밖은 INVALID, 짧은 실행은 간격 비례 축소).
                                                  # 종료 코드 0(FAIL 없음, INVALID 포함)·1(FAIL·중단)·2(옵션 오류). 이 노트북 15m 약 21분. 보고서 test/soak/build/reports/soak/{report.md,results.json,samples.tsv}
                                                  # (publish면 docs/bench/soak-<시작일 UTC>.md). server-it 락 안에서. 워크플로 .github/workflows/soak.yml(argos-bench-app, 수동만, 76시간 타임아웃).
                                                  # 릴리스 노트의 '72시간 soak·카오스 미실행'은 전용 러너의 유효 실행이 생길 때까지 유지한다(C-246 (2), release.yml 불변)
                                                  # benchTest·serverLoadTest·storageBench·soakTest·chaosTest는 프로필이 없으면 아무것도 빌드하기 전에 실패
test/bench/bench-run.sh --app shop-boot3 --jdk 21 --rounds 3 --exporter noop
                                                  # 18 §7.3 A-B-A-B-A-B 세트 + 보고서 docs/bench/<날짜>-<커밋7>.md. 전용 장비 절차는 docs/poc/agent-overhead.md 9장.
                                                  # --exporter noop = -Dargos.test.exporter=noop(인코딩 후 버림, 18 §5.4), drop = -Dargos.test.exporter=drop(인코딩 없이 버림,
                                                  # WO-02 P-2 설정 재현, C-80). 옛 키 argos.exporter는 없다(C-88). 둘 다 테스트 키라 개발(-SNAPSHOT) jar만 받는다(릴리스 jar는 거부)
test/bench/bench-run.sh --smoke --startup --exporter drop --machine-profile <이름>
                                                  # 공유 머신에서 측정 체인만 확인(약 7분, 보고서는 항상 INVALID)
test/bench/fetch-k6.sh && test/bench/fetch-otel-agent.sh && test/bench/bench-run.sh --smoke --startup --exporter noop --reference otel --otel-agent auto --machine-profile <이름> --out test/bench/build/smoke/<이름> -- --heap 1g
                                                  # WO-51 체인 확인(이 노트북 약 7분, INVALID): fetch-otel-agent.sh는 OTel Java 에이전트 2.32.0(SHA-256 고정, 개발 전용·배포 안 함)을
                                                  # test/bench/build/otel/에 받고, --otel-agent auto는 그 파일만 쓴다(실행 중 내려받기 없음). otel 변형은 하네스의 OTLP 블랙홀(127.0.0.1)로 보낸다(measure.otlp).
                                                  # 에이전트 변형은 -Dargos.test.selfdiag-file로 에이전트 스레드 CPU·직접 메모리·premain을 자가 진단에서 읽는다(measure.selfDiag, jcmd·NMT·배너는 교차 확인·대체).
                                                  # 부하기 쪽 dial 소켓 오류는 실행을 FAIL이 아니라 INVALID로(H-2 잠정 규칙, C-80). 결과는 build 아래에만, docs/bench에 넣지 않는다
test/bench/bench-run.sh ... --exporter grpc --collector HOST:PORT --agent-config server.tls=false,server.token-file=FILE
                                                  # 전송 비용 포함(N-01·N-03 gRPC 재측정). 수집기는 fake-collector.jar 또는 서버, 예시는 test/bench/README.md
test/bench/bench-protocol.sh --no-build --machine-profile <id> [--sets main,startup,container,vt,worst,otel,grpc] [--collector H:P [--collector-config k=v,...]] --baseline auto --latest
                                                  # 전체 프로토콜(benchTest가 부르는 것). grpc 세트는 판정에서 빠지고 보고서 "Transport cost (grpc - noop)" 절(판정은 noop, C-80).
                                                  # --baseline auto = 같은 머신 프로필·앱·JDK·exporter·encoding의 가장 최근 승인 보고서(없으면 NOT COMPARED와 사유), --latest = latest.md(포인터)·trend.md/.svg 갱신
test/bench/bench.sh <load|startup|report|publish|trend|noise|gate1> ...   # 벤치 하네스 직접 실행(k6는 test/bench/fetch-k6.sh). 사용법 test/bench/README.md.
                                                  # publish --baseline auto|none|FILE --latest --trend, trend --dir D(wo02-*·JSONL 시대 보고서는 제외 표, C-145 (4)),
                                                  # gate1 --reports F[,F] --out F.md(T-NFR-01~04·26~30 9행 + F.json, 수치 없는 PASS 없음, 종료 코드 0/3/4, 사람 확인 목록 C-240 (7)·C-244 (7)·C-245 (1)),
                                                  # noise --sets D[,D] [--out F.md](off CV·A-A 부트스트랩 구간·MDE, 18 미결 3 근거)
.github/workflows/bench.yml                       # 주간 벤치(토 18:00 UTC + workflow_dispatch만, pull_request 없음): self-hosted argos-bench-app에서 main·startup(dispatch 입력으로 다른 세트)을
                                                  # --baseline auto --latest로 돌리고, GitHub-hosted publish 잡만 쓰기 권한으로 docs/bench/<날짜>-<커밋7>.md PR을 연다. 구조 검사 BenchWorkflowTest(T-BLD-015i),
                                                  # 커스텀 러너 라벨은 .github/actionlint.yaml. 실제 실행은 전용 장비(사람)
./gradlew traceabilityReport                      # 요구 → 테스트 ID → 테스트 → 마지막 결과 → build/reports/traceability.md·.json(테스트 실행 없음, test/README.md 6장).
                                                  # 결과 출처 5종(WO-53, '## Sources' 표): JUnit XML, Vitest ui/build/test-results/vitest/junit.xml(pnpm -C ui test),
                                                  # Playwright ui/test-results/**/results*.json(새 실행은 ui/test-results/e2e-json/results-<프로젝트>[-shard-i-of-n].json, 테스트별 req annotation),
                                                  # 셸 TAP build/test-results/shell/<suite>/<script>.tap(4개 run-all.sh와 bash test/traceability/shell-results.sh <suite> <script>가 씀,
                                                  # '# argos-shell-results 1' 형식, 스크립트 머리 '# req:'·'# test-ids:'), 매트릭스 test/matrix/matrix.yaml 행 + build/matrix/T-MX-*.json.
                                                  # -Ptraceability.results=none이면 결과 파일을 읽지 않는다(깨끗한 체크아웃과 같다, 전부 NOT RUN, FAILED 0)
./gradlew checkTraceability [-Ptraceability.results=none]   # 카탈로그·대응표 검사(WO-53, test/README.md 6.1, 위반이면 실패): R1 쓰인 ID가 18 부록 A·본문 표에 있음, R2 18 §16.1 대응표 ID가
                                                  # 시험으로 존재, R3 필수 요구(D-01~D-26·N-xx·06 단계 M SEC) 대응 누락 0, R4 docs/gates/gate1-evidence.md의 ID가 카탈로그에 있음.
                                                  # 비시험 증거(design·human·measurement·deferred)는 test/traceability/evidence.yaml. 경고(번호 규칙·부분 덮임·동적 이름만 등)는 실패 아님.
                                                  # → build/reports/traceability-check.md·.json. PR CI traceability 잡(필수 검사)이 결과 파일 없이 checkTraceability traceabilityReport gate1Report를 돈다
./gradlew gate1Report [-Pgate1.publish=true]      # Gate 1 NFR 표(WO-53, 16행): 성능 행은 "approved": true 전용 장비 보고서만(C-252 (5)), N-07·N-24는 웜·콜드 두 조건(C-180),
                                                  # N-15·N-23·N-25는 CI 결과, 사람 항목 목록 → build/reports/gate1.md(publish면 docs/bench/gate1.md). 증빙 지도는 docs/gates/gate1-evidence.md
./gradlew :sample-apps:<app>:sampleSmoke          # 샘플 앱을 에이전트 없이 기동해 엔드포인트 카탈로그 검증(전 변형, check 미포함).
                                                  # 1개 변형은 sampleSmoke<Variant>, 루트 sampleSmoke는 60변형 전부. -Psample.startupRuns=5는 기동 중앙값.
                                                  # 스위치(sample-apps/README.md): SAMPLE_DB=h2|postgres|mysql|mariadb|mssql|oracle(mariadb·mssql·oracle 드라이버는 shop-boot3만, WO-13)
                                                  # |tibero|altibase|cubrid|db2(벤더 드라이버는 SAMPLE_JDBC_DRIVER_DIR의 jar, WO-50), SAMPLE_CONTAINER=tomcat|jetty|undertow(boot2 Jetty 9.4·Undertow 2.2,
                                                  # boot3 Jetty 12.0 ee10·Undertow 2.3, boot4 Jetty 12.1만. WAR·shop-heavy는 무시, 변형 sampleSmokeJetty·Undertow, C-127),
                                                  # SAMPLE_POOL=hikari|tomcat|dbcp2, SAMPLE_ROUTING_DS, SAMPLE_DRIVER_WRAPPER(C-40 위임 래퍼 5번째 층), SAMPLE_SECOND_POOL(풀 2개·재기동)
./gradlew :sample-apps:<app>:sampleAppJar         # 실행 jar → sample-apps/<app>/build/sample-app/<app>.jar (WAR 앱은 :war)
./gradlew :sample-apps:<app>:sampleImage          # 샘플 앱 Docker 이미지 argos-sample/<app>:dev

pnpm -C ui install --frozen-lockfile
pnpm -C ui build && pnpm -C ui test               # build = tsc + vite + 번들 라이선스 검사 + 외부 참조 검사 + 번들 크기 보고(bundle:size, 15 §14 대비 gzip 표,
                                                  # 예산 초과는 경고, 청크 그래프 결함은 실패. 결과 ui/build/bundle-size.json)
pnpm -C ui lint && pnpm -C ui typecheck && pnpm -C ui format:check            # ESLint, tsc strict, Prettier
pnpm -C ui check:i18n && pnpm -C ui check:external && pnpm -C ui check:licenses  # i18n 키, 외부 참조, 라이선스
                                                  # 로캘은 common·shell·auth만 엔트리 번들, 화면 네임스페이스(xlog·tx·trace·dashboard)는 라우트와 함께 지연 로드(C-209).
                                                  # 새 화면 네임스페이스는 ui/src/i18n/routeNamespaces.ts에 등록(routeNamespaces.test가 모듈 그래프와 대조). 앱은 createAppI18n, 테스트는 createI18n(eager)
pnpm -C ui check:licenses:dev                     # 허용 목록 밖 UI 개발 도구가 dev-only.md `npm:` 행에 있는지
pnpm -C ui gen:api && pnpm -C ui check:api       # docs/api/openapi.yaml → src/api/generated/schema.ts(손 편집 금지, x-argos-untrusted → Untrusted 브랜드).
                                                  # check:api(= test에도 포함)는 커밋본 스펙으로 재생성한 결과와 diff 0. 스펙이 바뀌면 gen:api 후 함께 커밋
pnpm -C ui playwright:install                     # Playwright Chromium 설치(머신당 1회, e2e·perf 전제)
pnpm -C ui e2e                                    # vite build --mode perf --outDir build/perf-dist(/xlog-poc는 perf 빌드에만, 운영 번들 제외 C-203) + Playwright E2E:
                                                  # preview 프로젝트(WO-04 spec, 그 빌드의 vite preview) + stack 프로젝트(e2e/specs 전부,
                                                  # 이 트리의 서버 bootJar + PostgreSQL·ClickHouse 컨테이너, 127.0.0.1 무작위 포트, 라벨 io.argos.e2e·접두 argos-e2e-, ≤ 5GiB,
                                                  # 시드: :server:loadQueryTestData tiny(오늘 UTC 앵커, C-170) + 사용자 3 + shop-boot3·에이전트·seed-traffic.sh, 매니페스트
                                                  # ui/e2e/.stack/seed-manifest.json, WS는 기본 스텁·wsMode 'server' spec만 실서버). 전제: Docker, JAVA_HOME JDK 21, Windows는 Git Bash, 그리고 jar 5개
                                                  # ./gradlew :server:bootJar :agent:agent-dist:agentJar :sample-apps:shop-boot3:sampleAppJar :sample-apps:ext-stub:sampleAppJar :sample-apps:smoke:sampleAppJar
                                                  # (UI·서버 소스보다 오래된 bootJar는 거부. 출력이 같아 Gradle이 UP-TO-DATE로 두면 ./gradlew :server:bootJar --rerun).
                                                  # 재시도 0, 이 노트북 약 5.5분(스택 기동·시드 약 1분). test·Gradle check에 포함되지 않음.
                                                  # 새 스택은 jar를 읽기 전에 server-it 락을 스스로 잡고 teardown(실패 시 포함)에서 푼다(e2e/stack/lock.ts, 대기 ARGOS_LOCK_WAIT_SECONDS 기본 1200,
                                                  # ARGOS_E2E_STACK_URL 재사용은 락 없음). 실행은 자기 ui/e2e/.stack/<run>만 지운다. 서버 CSP 카운터는 Chromium에서 검증 불가(csp-counter-not-verifiable 주석, ui/e2e/README.md CSP 절)
pnpm -C ui e2e:preview                            # preview 프로젝트만 / pnpm -C ui e2e:stack [--shard=i/n]: stack 프로젝트만
pnpm -C ui e2e:stack --grep "T-E2E-08[0-9]|T-E2E-dashboard|T-E2E-common-(2|7) "   # 대시보드(WO-32) 부분 실행: dashboard.spec·required/dashboard.yaml·common 2.0-1·2.0-5
pnpm -C ui e2e:stack --grep "T-E2E-stats|T-E2E-service-version|T-E2E-errors"   # 통계 4종(WO-36) 부분 실행(약 3분, 스택 기동·시드 포함): stats.spec·required/stats.yaml(목록·상세 값 = 같은 질문의 API, 서버 CSV,
                                                  # C-14 estimated 배지, 서비스 버전별 p95·배포 마커, 신규 에러 3클릭). 시각 기준 stats-*.png는 e2e:docker 전용(T-E2E-stats-30~36)
pnpm -C ui e2e:stack --grep "T-E2E-alerts"         # 알림 화면(WO-38) 부분 실행(약 2.5분 + 스택 기동·시드 약 1.7분): alerts.spec(템플릿 규칙 → 발화 → 이력 → ACK 직렬 블록 4~9,
                                                  # 채널·1366·딥링크·WS 10~12)·required/alerts.yaml(T-E2E-alerts-30~40). 직렬 블록 일부만 --grep하면 앞 시험이 만든 규칙이 없어 실패한다.
                                                  # 가짜 수신기: 스택이 서버에 --add-host argos-e2e-receivers:host-gateway, webhook allow-cidrs = 127.0.0.1/32 + 그 별칭의 /etc/hosts 주소 전부,
                                                  # SMTP = 별칭의 무작위 포트, 실행별 마스터 키(ARGOS_MASTER_KEY, secrets/의 env 파일)를 주고(ui/e2e/.stack/<run>/receivers.json),
                                                  # 시험이 호스트 127.0.0.1에 node:http 웹훅·Slack과 node:net SMTP 수신기를 띄운다(e2e/fixtures/fakeReceivers.ts, 외부망 없음).
                                                  # Linux Docker Engine 호스트 실행과 e2e:docker의 SMTP는 사유를 남기고 skip. UI 없는 bootJar(-Pargos.ui.skip)는 스택 기동 전에 거부(C-227 (5)).
                                                  # 시각 기준 alerts-*.png는 e2e:docker 전용(T-E2E-alerts-20~25)
pnpm -C ui e2e:stack --grep "T-E2E-09[0-9]|T-E2E-overview"   # 전체 현황(WO-31) 부분 실행: overview.spec·required/overview.yaml(약 2.2분, 스택 기동·시드 포함).
                                                  # T-E2E-090은 실서버 응답을 타입 맞춘 행으로 30개까지 채워(page.route) 1366×768 무스크롤·숫자 셀 잘림 0을 본다.
                                                  # T-E2E-099(wsMode server)는 호스트 JVM으로 shop-boot3 + 에이전트를 띄워 실제 overview push를 받는다
                                                  # (로그 ui/e2e/.stack/<run>/logs/overview-live-shop-boot3.log, JVM 없는 e2e:docker에서는 추적 중인 시드 앱으로 시도하고 없으면 skip)
pnpm -C ui e2e:stack --grep "T-E2E-active"        # 액티브 트랜잭션(WO-35) 부분 실행: active.spec·required/active.yaml(약 4.6분, 스택 기동·시드 포함, jar 5개 + :server:activeFeedJar 전제).
                                                  # beforeAll이 피더(앱 e2e-active)를 띄워 실서버 active push·REST·화면을 대조, 끊김·인스턴스 상태는 WS 스텁.
                                                  # 실데이터 테스트는 JVM 없는 e2e:docker 안에서 skip(시각 기준 active-*.png T-E2E-active-40~43만). 피더 로그 ui/e2e/.stack/<run>/logs/active-feed.log
pnpm -C ui e2e:stack --grep "WO-17"               # 실제 에이전트 ↔ 실서버 명령·원격 설정·킬 스위치(WO-17) 부분 실행: agent-commands.spec(T-AG-054·T-SEC-24·T-AG-072·T-SEC-21·T-AG-069·T-SEC-23·T-AG-043 (stack)).
                                                  # 호스트 JVM 21로 자체 앱 e2e-wo17(인스턴스 wo17-cmd, SAMPLE_VT·센티널 비밀값, 토큰은 psql로 만든 secrets/wo17-agent.token)을 띄워 WO-26 REST로 명령·설정 변경·킬 스위치를 보낸다.
                                                  # jar 5개 전제, JVM 없는 e2e:docker 안에서는 전부 skip(CI 이미지 결과는 사람 확인). [wo17-n25]는 콘솔·annotation, 로그 .stack/<run>/logs/wo17-cmd-shop-boot3.log·wo17-cmd-agent/.
                                                  # 이 노트북 시험 약 2분 + 스택 기동·시드 약 2~4분. liveShop.ts를 바꾸면 --grep "T-E2E-active|T-E2E-099|T-E2E-async"도 함께
pnpm -C ui e2e:stack --grep "T-ACC-20|T-AG-058"   # 배치(WO-18) 부분 실행: batch.spec이 shop-batch + 에이전트를 별도 앱 e2e-wo18-batch로 띄워(분리 실행 + 항목당 SQL + HTTP 1,000건)
                                                  # T-ACC-20 (stack)(kind=HTTP 통계·대시보드에 배치 없음, kind=BATCH 따로)·T-AG-058 (stack)(XLog kind=BATCH 점, 상세 요약 모드 SUMMARY 행 = API).
                                                  # jar 5개 + :sample-apps:shop-batch:sampleAppJar 전제, JVM 없는 e2e:docker 안에서는 skip. 스택 기동·시드 포함 약 3분
pnpm -C ui e2e:stack --grep "T-E2E-instance|T-E2E-053"   # 인스턴스 상세(WO-37) 부분 실행(약 3분, 스택 기동·시드 포함, jar 5개 + :server:activeFeedJar 전제): instance.spec·required/instance.yaml
                                                  # (헤더·차트·환경 탭·링크 = 페이지가 받은 응답과 같은 질문의 API, 실제 에이전트 인스턴스 e2e-shop-1), 역할별 환경 탭은 명령 피더(앱 e2e-cmd)
                                                  # 시각 기준 instance-*.png(T-E2E-instance-40~43)는 e2e:docker 전용. T-E2E-053·055a 플레이스홀더 대상은 /apps/:id/map
pnpm -C ui e2e:stack --grep "T-E2E-dumps|T-E2E-active"   # 명령 탭·스레드 덤프 뷰어·액티브 행 동작(WO-37) 부분 실행(시험 약 8분 + 스택 기동·시드 2~4분): dumps.spec·required/dumps.yaml·active.spec.
                                                  # 피더 ActiveFeed --commands(e2e/fixtures/commandFeed.ts, 앱 e2e-cmd: e2e-cmd-1 정상·e2e-cmd-2 잘린 덤프)가 실제 수집기 → PG 암호화 결과로 왕복,
                                                  # T-E2E-dumps-30은 실제 에이전트(shop-boot3 SAMPLE_VT=true, JDK 21) 덤프. 명령마다 인스턴스 이력으로 분당 10개·동시 1개 빈자리를 기다린다(commandSlot).
                                                  # 시각 기준 dumps-*.png(T-E2E-dumps-40·41)는 e2e:docker 전용
pnpm -C ui e2e:stack --grep "T-E2E-05[78] "       # 설정 마법사 첫 앱 단계·내 세션(WO-26) 부분 실행(스택 기동·시드 약 4.5분 + 시험 수 초): T-E2E-057(첫 앱 등록·토큰 1회 표시·no-store·Bearer 이벤트 쓰기,
                                                  # 끝나면 앱 삭제, /setup/status·/setup/admin만 page.route), T-E2E-058(/account 내 세션 목록·다른 세션 즉시 종료 → 그 세션 401)
pnpm -C ui e2e:stack --grep "T-E2E-admin|T-E2E-search|T-E2E-roles|T-SEC-47"   # 관리·검색 화면(WO-39) 부분 실행(시험 약 4.5분 + 스택 기동·시드 2~4분, jar 5개 + :server:activeFeedJar 전제):
                                                  # admin.spec(T-E2E-admin-20~29 직렬: UI 앱 생성 → 설치 안내 = 클립보드 → 토큰 1회 모달 원문으로 실제 에이전트(liveShop, 호스트 JVM) UP, 닫은 뒤 DOM·스토리지·URL 부재,
                                                  # 설정 diff·결과·되돌리기, 킬 스위치 확인 취소 요청 0 → WARN 타일, 승인 정책 앱 토큰으로 피더를 다른 env로 접속시켜 승인·거부(APP_MISMATCH 때문에 이름이 아니라 env 변형),
                                                  # 회전·폐기 → STALE/DOWN, 사용자·감사 CSV·개인정보 삭제 done·수집 상태 순위·about/licenses)·required/admin.yaml(T-E2E-admin-1~13)·search.spec(T-E2E-search-1~8)·
                                                  # required/search.yaml(30~33)·roles.spec(T-E2E-roles-1~3, 16 §7.10 기대표)·auth.spec T-SEC-47 (UI). [wo39-n25] 줄은 콘솔·annotation(노트북 값은 INVALID).
                                                  # 대시보드 2.3-3 실데이터 WARN/STALE(C-217 (2), T-E2E-dashboard-5)은 --grep "STALE \(yellow\)". 호스트 JVM 시험은 e2e:docker 안에서 skip, 시각 기준 admin-*.png(40~43)는 e2e:docker 전용
pnpm -C ui e2e:stack --grep "T-E2E-alerts|T-E2E-09[67] |T-E2E-dashboard|T-E2E-overview" # 알림 패널(WO-25) 부분 실행: 시드 (e)가 주 앱에 실제 FIRING 알림을 만들고(seed/alerts.ts, 매니페스트 alert, timings.seed_alert_s),
                                                  # T-E2E-alerts-1·2(대시보드·전체 현황 패널 = 페이지가 받은 응답, 링크 /alerts?…&alert=<event_id>)·-3(권장 템플릿 규칙 적용, 템플릿 part 중복 없음),
                                                  # T-E2E-096·097, required 2.3-5·2.10-4. 스택 서버에 마스터 키가 없어 채널은 만들지 않는다(409 secret_unavailable). 규칙 창이 1h라 재사용 스택은 적재 1시간 뒤 알림이 풀린다
pnpm -C ui e2e:stack --grep "T-SV-020e|T-SEC-44c|T-E2E-xlog-10 "   # 실서버 WS(WO-22) 부분 실행. spec에서 test.use({ wsMode: 'server' })면 ws 픽스처가 스텁 대신 실제 /ws로 중계하며
                                                  # 클라이언트 프레임은 14 §5.2로, 서버 프레임은 UI parseServerFrame으로 검사(serverFrames()·waitForServerFrame()·waitForClose()). 기본은 'stub'.
                                                  # 시드는 에이전트를 Node TCP 프록시(e2e/stack/seed/grpcProxy.ts) 뒤에 두고, 데이터가 조회되면 프록시를 얼려 인스턴스가 STALE이 될 때까지
                                                  # 폴링한 뒤 풀어 session_lost 수집 중단 1구간을 만든다(seed/outage.ts, 매니페스트 outage, timings.seed_outage_s·seed_outage_extra_s,
                                                  # 못 만들면 missing_optional category outage). T-SV-020e는 JVM이 없는 e2e:docker 이미지에서는 skip
pnpm -C ui e2e:docker [-- --update-snapshots=missing|all]   # stack 프로젝트를 다이제스트 고정 Linux Playwright 이미지 안에서(컨테이너는 pnpm-lock.yaml에서 만든 package-lock.json을
                                                  # npm ci로 무결성 검사하며 설치. UI를 바꾼 뒤에는 --update-snapshots=all로 다시 만들고 이미지를 확인. 시각 회귀 기준 이미지 생성·비교는
                                                  # 여기서만, 그 밖에서는 screenshot-linux-only로 skip). CI pr.yml e2e 잡(4샤드)이 이것을 쓴다. 기준 이미지 갱신은 quiet 락
pnpm -C ui e2e:stack --grep "T-SEC-62"            # XSS 시드 전 화면 순회(WO-46, xss-seed.spec, 약 6분 새 스택 포함): 18 §4.3 위치 10종의 페이로드가 API 데이터에 실재 → 화면이 텍스트로 표시·주입 요소 0,
                                                  # routes.ts 전 라우트 방문(계획에 없는 새 라우트는 실패), CSP 카운터 증가 0. 스윕 전용 앱 e2e-xss-*(shop-boot3)·e2e-xss-batch-*(:sample-apps:shop-batch:sampleAppJar 전제)
pnpm -C ui e2e:fresh                              # 신규 설치 스택 변형(WO-46, C-192 (1), 약 1분): 관리자 없음·시드 없음·설정 토큰 파일(secrets/setup.token, 출력 금지)로 fresh-install 프로젝트
                                                  # (specs/fresh/setup-wizard.spec: T-SEC-47 (fresh) 무토큰·오토큰 거부, T-E2E-059 실서버 마법사·IP 단계 강제·첫 앱 토큰 1회·사용한 토큰 403, CSP 0).
                                                  # 메인 스택과 같은 server-it 락. e2e:docker 이미지 안은 node e2e/stack/cli.ts docker-fresh(CI e2e 잡 샤드 1)
pnpm -C ui e2e:stack:up && pnpm -C ui e2e:stack:down   # 시드된 스택을 띄워 두고(ARGOS_E2E_STACK_URL=<URL>로 재사용) / 제거. node e2e/stack/cli.ts restart-server는
                                                  # DB를 둔 채 새 bootJar로 서버만 재기동. 변수·픽스처·required YAML은 ui/e2e/README.md
pnpm -C ui perf:initial-load                      # 스택 위 초기 로드(T-NFR-14 골격, Navigation Timing 5회·CPU 부하 기록, 참고값). 같은 jar 전제
pnpm -C ui perf                                   # vite build --mode perf --outDir build/perf-dist + XLog 프레임 시간 spec(T-NFR-14, Chrome tracing): 기본은 /xlog-poc(합성 점).
                                                  # 조용한 머신에서, 약 12~16분. XLOG_PERF_TARGET=route XLOG_PERF_SCENARIOS=static,interaction,selection이면 E2E 스택(jar 5개 전제,
                                                  # 스택이 server-it 락을 잡음)의 정식 XLog 화면: 프레임 C-35 런별, 선택 계산·화면 선택(selectMs)·화면 반영(놓은 뒤 요약·목록 첫 행 프레임, GPU 포함)
                                                  # C-36 런별(전체 플롯·부분 선택 2변형), 10만 점 창 로드 3회. DPR 1·2 약 9분. route에서 realtime은 WS 스텁(10만 점 + 500점/s),
                                                  # XLOG_PERF_ROUTE_UNSAMPLED=1은 비표본 약 10만 점 창. 결과 ui/perf/results/xlog-frame-<runId>.md(ui/README.md "Performance, XLog screen")

bash test/lint/check-spdx.sh                      # SPDX 헤더 전수(T-BLD-007)
bash test/lock/argos-lock.sh run server-it --purpose "<용도>" -- ./gradlew :server:integrationTest
                                                  # 작업 트리 공유 락(ADR-0010 C-235 (8)): server-it(실서버 실행·서버 bootJar 재생성: :server:integrationTest·securityTest·bootJar·generateOpenApi·
                                                  # jibDockerBuild·composeSmoke·e2e:stack·e2e:docker·perf route), agent-it, quiet(측정), deps(잠금 파일 편집). 락 = .gradle/argos-locks/<name> 디렉터리(mkdir) +
                                                  # 그 안 owner.json(token·pid·pid_kind·host·started_at UTC·purpose). 남의 락은 지우지 않는다. 같은 호스트에서 소유 PID가 죽은 락만 낡은 락으로 자동 정리하고,
                                                  # owner.json 없는 락·다른 호스트 락은 기다린 뒤(기본 1800초, --wait-seconds) 소유자와 수동 삭제(rm -rf)를 알리고 실패한다. Gradle 태스크는 빌드 로직이 아니라
                                                  # 호출자가 run으로 감싼다(명령이 끝나거나 실패해도 해제. 셸이 바로 끝나는 AI 도구 호출은 run만 쓴다). acquire|release --token|status도 있다.
                                                  # Windows는 Windows PID(Git Bash /proc/<pid>/winpid, tasklist), Node 쪽 구현은 ui/e2e/stack/lock.ts. 테스트 bash test/lock/argos-lock.test.sh
bash sample-apps/load/run-bench.sh --summary build/bench/summary.json   # k6 bench.js 1,000/s × 1분(Docker), 결과 JSON·머신 부하
java -jar sample-apps/load/build/sample-app/load.jar --target-url http://127.0.0.1:8080 --mix bench --rate 1000 --duration 1m --summary build/load/summary.json
                                                  # 데모 부하 생성기(의존성 없음, :sample-apps:load:sampleAppJar), 실행 중인 앱 대상
bash test/seed/seed-traffic.sh --start --repeat 10   # PII·XSS 시드 트래픽(센티널 ARGOS_SEED). 실행 중인 앱은 --url <URL>, --set pii|xss|all(기본 all)
bash test/agent/check-shading.sh agent/agent-dist/build/libs/argos-agent-0.0.0-SNAPSHOT.jar
bash test/lint/check-notice.sh                    # 차용 코드 출처 린트(T-BLD-012, 루트 check의 checkNotice): 비Argos 저작권·비Apache SPDX·'Adapted from' 표지 ↔ NOTICE 절
./gradlew releaseArtifacts -Pversion=X.Y.Z        # 릴리스 자산(WO-44, 19 §7.2, check 미포함) → build/release/: argos-{agent,server}-X.Y.Z.jar, argos-{agent,server,ui}-X.Y.Z.cdx.json,
                                                  # SHA256SUMS(sha256sum -c). SOURCE_DATE_EPOCH = 태그 커밋 시각. -Pargos.release.parts=agent[,server][,ui]는 일부만(로컬 확인용)
bash test/release/verify-reproducible.sh run [--ui-skip] [--agent-only] [--images] [--version X.Y.Z] [--out <dir>]
                                                  # T-BLD-009: --no-build-cache --rerun-tasks 2회 빌드 → 엔트리 단위 비교(compareReleaseOutputs, 원인 분류), 보고서 build/reproducibility/compare-report.txt.
                                                  # --images는 amd64 jibBuildTar 3종도(이미지 tar 사본 약 0.9GB가 build/reproducibility/{a,b}/images에 남으니 지울 것). server-it 락 안에서.
                                                  # compare <dirA> <dirB>는 release.yml 두 러너 비교. 종료 0 같음·1 다름·2 사용법
bash test/release/check-sbom.sh [--require agent,server,ui] [--scanner "bash test/release/trivy-scan.sh"] build/release
                                                  # T-BLD-008: CycloneDX 형식·컴포넌트 > 0, .trivyignore = docs/security/cve-exceptions.md ID 집합. 스캐너는 Trivy 0.76.2 다이제스트 고정(Docker, DB만 내려받음)
                                                  # PR sbom-scan 잡(WO-46, C-246 (4)) 로컬 재현: ./gradlew :agent:agent-dist:agentSbom :server:serverSbom :ui:pnpmBuild → 세 SBOM을
                                                  # build/sbom-scan/argos-<part>-<버전>.cdx.json으로 복사 → 같은 check-sbom.sh --require agent,server,ui --scanner ... build/sbom-scan.
                                                  # 예외 승인 절차는 docs/security/cve-exceptions.md. 워크플로 정책 W7(check-workflows.sh): securityTest는 build-java 밖 자기 잡, sbom-scan은 Trivy 게이트
                                                  # --review-dates warn|fail [--today YYYY-MM-DD](기본 warn: 재검토일 지난 예외 행마다 WARN, fail은 종료 1. 형식 오류·승인일 + 90일 초과는 항상 실패, T-BLD-008b 제안)
bash test/release/trivy-scan.sh --image argos-server:<버전> .trivyignore   # 로컬 데몬 이미지 스캔(WO-47, T-SEC-71d 제안): docker save tar를 :ro로 같은 다이제스트 고정 Trivy에(소켓 미전달),
                                                  # 수정 가능한 Critical·High, 같은 .trivyignore. 이미지는 먼저 ./gradlew :server:jibDockerBuild(server-it 락). pr.yml sbom-scan이 SBOM 게이트 뒤에 실행.
                                                  # 주간 .github/workflows/sbom-rescan.yml(화 04:23 UTC·dispatch: SBOM 3종 --review-dates fail, 서버·데모 이미지 2종, 최신 릴리스 SBOM, 실패 시 kind/security 이슈)의
                                                  # 로컬 재현 명령은 docs/security/cve-exceptions.md 'Where the gate runs'. 공급망 CI 전체(러너 격리·Dependabot PR 절차·수동 핀 갱신)는 docs/security/supply-chain.md
bash test/release/check-image-arch.sh [--version X.Y.Z] <project>/build/jib-image.tar arm64   # T-BLD-022: jibBuildTar -Pargos.image.platforms=linux/arm64 결과의 os/arch·uid 10001·
                                                  # OCI 라벨·베이스 다이제스트(--print-expected-base). 테스트 bash test/release/run-all.sh(JDK 없으면 ARGOS_RELEASE_TEST_GRADLE=0)
deploy/verify-release.sh [--offline] [--key <asc>] [--fingerprint <FPR>] [--repo <o/n>] [<version>] <dir>
                                                  # 릴리스 검증(docs/install/verify-release.md): SHA256SUMS·GPG(keys/ARGOS-RELEASE-KEY.asc, 지문은 아직 PLACEHOLDER라 --fingerprint 필수)·
                                                  # 온라인이면 cosign verify-blob·이미지·gh attestation. 테스트 bash deploy/test/verify-release.test.sh(버리는 키, 약 2분),
                                                  # 로컬 서명 리허설 bash deploy/test/release-signing-rehearsal.sh 0.1.0 build/release
bash scripts/release-notes.sh [--check] [--assets <dir>] X.Y.Z   # CHANGELOG 절 → 릴리스 노트(T-BLD-019). release-compose.sh X.Y.Z <digests> <out.tar.gz>(compose 자산, 이미지 tag@digest),
                                                  # release-sign.sh(release.yml sign 잡), check-release-key.sh [--allow-placeholder](keys/·상수·SECURITY.md 일관성). 테스트 bash scripts/test/run-all.sh
bash .github/scripts/check-workflows.sh           # 워크플로 정책 W1~W8: pull_request_target 금지, PR 도달 워크플로는 GitHub 호스트 러너만, SHA·다이제스트 고정, W8 self-hosted 잡은
                                                  # push(main·release/**·태그 v*)·schedule·dispatch만 + 잡 if: 신뢰 ref 가드(TRUSTED_REF_GUARDS) + checkout persist-credentials: false +
                                                  # 비밀값은 environment trusted + 쓰기 권한 없음. 새 self-hosted 라벨은 .github/actionlint.yaml에도
bash .github/scripts/check-workflows.sh --simulate <pull_request|push|schedule|workflow_dispatch> [--ref <ref>] [--fork] [<root>]
                                                  # 그 이벤트에서 잡마다 <workflow>\t<job>\t<github-hosted|self-hosted>\t<runs|skipped:<사유>|may-run> TSV(포크 PR·기능 브랜치에
                                                  # self-hosted 실행 0 = runner-isolation.test.sh, T-BLD-027 제안, 약 30초)
bash .github/scripts/check-dependency-verification.sh [<root>]   # 의존성 검증 강제 V1~V5(T-SEC-73a 제안, 약 3초): verification-metadata verify-metadata·sha256, trusted-artifacts 사유 목록,
                                                  # 스크립트·빌드·워크플로에 lenient/off·메타데이터 재생성 금지(docs/·*.md 제외), pnpm install은 --frozen-lockfile.
                                                  # TestKit 시험 ./gradlew :build-logic:test --tests '*DependencyVerificationFunctionalTest*'. Dependabot 설정 시험 bash .github/scripts/test/dependabot-config.test.sh(T-SEC-71b 제안)
bash .github/scripts/test/supply-chain-workflows.test.sh   # CodeQL·secrets-scan·sbom-rescan·pr.yml 이미지 스캔 구조와 변이 사본(T-BLD-028·T-SEC-73b·71c·71d 제안, Docker 불필요, 약 1분)
bash .github/scripts/check-proto.sh [--against-git <base>|--against <dir>]   # proto buf lint, 인자를 주면 buf breaking(고정 다이제스트 컨테이너, --network none, PR lint 잡)
bash .github/scripts/check-file-modes.sh          # git 인덱스에서 gradlew·*.sh가 100755인지(lint 잡)
REQUIRE_DOCKER=1 bash .github/scripts/test/run-all.sh   # CI 스크립트 테스트(DCO·changelog·워크플로 정책·러너 격리·공급망 워크플로·dependabot·의존성 검증·branch-protection·repo-config·project-files·셸 결과 기록기(T-BLD-014i)·traceability 잡 구조(T-BLD-014j) 등 18스위트 736건, 이 노트북 약 7.5분).
                                                  # 셸 실행기 4개(이것, deploy/compose·scripts·test/release의 run-all.sh)는 스크립트마다 build/test-results/shell/<suite>/<script>.tap을 남긴다(traceabilityReport 입력)
bash .github/scripts/test/repo-config.test.sh     # CODEOWNERS·labeler 일관성(T-BLD-025 제안, 약 30초): 문법·경로 존재·19 §6.8 핵심 경로 소유 = labeler needs-matrix 집합, 표본 경로 → 라벨, 위반 픽스처
bash .github/scripts/test/project-files.test.sh [<root>]   # 19 §8 파일 세트·제목 계약·코드 앵커(SECURITY.md#supported-versions 등)·개인정보(이메일·전화)·사업 요소·상대 링크
                                                  # (T-BLD-026 제안, 약 1분). <root>를 주면 그 트리만 검사하고 ERROR [...] 줄 출력. 프로젝트 파일 제목을 바꾸면 시험도 함께 고친다
bash .github/scripts/branch-protection.sh --dry-run [--repo OWNER/NAME] [--approvals N] [--include-e2e] [--include-sbom-scan] [--list-checks]
                                                  # 브랜치 보호 ruleset argos-protected-branches(main·release/*)·Discussions·PVR·포크 PR 워크플로 승인. 메인테이너가 gh auth로 실행(이 노트북에서 GitHub 호출 금지),
                                                  # --dry-run은 gh 호출 없이 실행 가능한 스크립트 출력. pull_request 워크플로의 새 잡은 required_checks 또는 excluded_jobs(사유)에 분류해야
                                                  # branch-protection.test.sh(가짜 gh, T-BLD-024 제안)가 통과한다
bash .github/scripts/create-labels.sh --dry-run [--repo OWNER/NAME] [--backport X.Y]   # 라벨 체계(area/*·kind/*·needs-matrix 등) 생성, 메인테이너가 gh로 실행. 시험 create-labels.test.sh
./gradlew :agent:agent-core:test --tests '*AgentConfigKeySnapshotTest*'   # T-API-009 에이전트 몫: 공개·진단 설정 키(타입·기본값·remote·env) = agent/agent-core/src/test/resources/config/agent-config-keys.txt.
                                                  # 키를 추가·삭제·변경하면 파일을 손으로 고치고 CHANGELOG 항목을 더한다(재생성 없음). 서버 몫은 :server:contractTest ConfigKeySnapshotTest·ConfigEnvAliasSnapshotTest
bash .github/scripts/run-gitleaks.sh dir          # 비밀 스캔(Docker). git [log-opts]는 커밋 범위, 인자 없는 git은 전체 이력(secrets-scan.yml 주간·dispatch와 같다)
```

- 워크플로 lint: `docker run --rm -v "$PWD:/repo:ro" -w /repo rhysd/actionlint:1.7.12@sha256:b1934ee5f1c509618f2508e6eb47ee0d3520686341fec936f3b79331f9315667 -color` (Git Bash에서는 `MSYS_NO_PATHCONV=1`과 Windows 경로 볼륨). PR 잡과 명령의 대응은 `docs/dev-setup.md` 5절.
- 의존성 변경 후: `./gradlew resolveAndLockAll --write-locks --no-configuration-cache -Pargos.ui.skip=true`로 잠금을 갱신하고, `gradle/verification-metadata.xml`은 빈 Gradle 캐시의 Linux 컨테이너에서 `--write-verification-metadata sha256`으로 다시 만든다(명령은 `docs/dev-setup.md` 7절). Windows 웜 캐시에서 만들면 POM·BOM 항목이 빠지고 CRLF로 바뀐다. Dependabot PR도 병합 전에 같은 방법으로 잠금·메타데이터를 다시 만든다(`docs/security/supply-chain.md`).
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
