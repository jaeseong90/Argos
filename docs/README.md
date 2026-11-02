# Argos 문서 지도

| 단계 | 번호 | 문서 | 상태 | 한 줄 요약 |
|---|---|---|---|---|
| 기획 | 01 | [제품 기획서](01-product-plan.md) | v0.3 확정 | 무엇을, 누구를 위해, 어디까지, 어떤 품질로. F-/D-/N-/S-/O- 요구 |
| 기획 | 05 | [벤치마크 기준](05-benchmark.md) | v0.2 확정 | "상용 수준"의 정의: Datadog APM·Jennifer·Prometheus/Grafana 대비 매트릭스, 화면별 필수 요소 |
| 기획 | 06 | [보안·개인정보 요구](06-security-requirements.md) | v0.1 확정 | SEC-01~93, ASVS L2 목표 |
| 기획 | 03 | [완성 기준](03-definition-of-done.md) | v0.2 확정 | 공통·영역별 DoD, Gate 0/1/1.5/2, 수락 절차, 보고 형식 |
| 설계 | 02 | [시스템 아키텍처](02-architecture.md) | v0.3 기준선 | 구성 요소, 전역 설계 결정 DSN-02-xx, 데이터 분류표, 데이터 흐름, PoC 항목, 미결 A-, 설계 문서 간 정합 결정 C- |
| 설계 | 10 | [에이전트 설계](10-agent-design.md) | v0.1 | 모듈·클래스로더·계측 프레임워크·컨텍스트·버퍼·전송·설정·명령·자기 보호 (DSN-10-01~20) |
| 설계 | 11 | [프로토콜 설계](11-protocol-design.md) | v0.1 | proto 정의, 세션·배치·사전·명령, 멱등성, 버전 협상 (DSN-11-01~11) |
| 설계 | 12 | [서버 설계](12-server-design.md) | v0.1 | WAL·중복 제거 파이프라인, 세션·상태 모델, 실시간 버스, 알림 엔진, 명령 라우팅, 메타데이터 (DSN-12-01~15) |
| 설계 | 13 | [저장소 설계](13-storage-design.md) | v0.1 | ClickHouse DDL·롤업·TTL·코덱, PostgreSQL 스키마, 마이그레이션 러너, 용량, PoC Q1~Q20 (DSN-13-01~20) |
| 설계 | 14 | [API 설계](14-api-design.md) | v0.1 | REST(OpenAPI)·WebSocket 프레임, 소스 선택, 권한 표기, 에러 규약 (DSN-14-01~20) |
| 설계 | 15 | [UI 설계](15-ui-design.md) | v0.1 | 정보 구조, 화면 사양, 컴포넌트, URL 상태, 실시간, XLog 캔버스, i18n (DSN-15-01~15) |
| 설계 | 16 | [보안 설계](16-security-design.md) | v0.1 | 위협 모델, 토큰·세션·권한, 마스킹·분류, 공급망, SEC 대응표 (DSN-16-01~17) |
| 설계 | 17 | [배포·운영 설계](17-deployment-ops-design.md) | v0.1 | 패키지, 설정 관리, 업그레이드, 백업, 자기 모니터링, 런북 |
| 설계 | 18 | [테스트 설계](18-test-design.md) | v0.1 | 테스트 계층·하네스, 매트릭스·벤치·장시간·카오스·정확성·보안·E2E, 테스트 ID 카탈로그 |
| 설계 | 19 | [저장소·빌드·릴리스 설계](19-repo-build-release-design.md) | v0.1 | 모노레포, Gradle·pnpm, CI·러너 격리, 릴리스·서명·SBOM, 기여 흐름 |
| 실행 | 04 | [작업 지시서](04-work-orders.md) | v0.2 | WO 72개(Phase 0: WO-00~05 상세, Phase 1: 44, 2a: 8, 2b: 14), Gate 판정 표, 상태 표 |

설계 문서 간 세부가 어긋나면 02 문서 13장 "설계 문서 간 정합 결정"이 우선한다.

| 구분 | 문서 | 상태 | 한 줄 요약 |
|---|---|---|---|
| 개발 | [dev-setup.md](dev-setup.md) | 초안(WO-00) | 개발 환경 준비, 빌드·테스트·정적 검사 명령, PR 잡별 로컬 명령, 의존성 변경 절차 (영문) |
| 개발 | [../test/README.md](../test/README.md) | WO-05 | 테스트 위치·계층별 Gradle 태스크와 `check` 구성, 빌드가 강제하는 규칙, 테스트 ID·`req:` 태그 규약, 추적성 보고서, PR 게이트 시간 (영문). 하네스 사용법은 [agent-testing](../agent/agent-testing/README.md)·[test/bench](../test/bench/README.md) README |
| 결정 | [adr/](adr/) | 0000~0009 | ADR 템플릿, 0001 라이선스 Apache-2.0, 0002 식별자·공개 시점, 0003·0004 Phase 0 정합 결정(C-21~C-49), 0005 에이전트 전송 스택(A-1·A-14·C-23), 0006·0007 저장소 PoC 결정(C-50~C-71), 0008 에이전트 셰이딩 프로퍼티 문자열(C-23)·ByteBuddy `Unsafe` 처리, 0009 Gate 0 판정 PM 결정(C-72~C-90) (모두 잠정, 메인테이너 확인 대기) |
| 라이선스 | [licenses/dev-only.md](licenses/dev-only.md) | WO-00 | 배포물에 넣지 않는 테스트·도구 전용 구성요소 목록(`checkDevOnlyLicenses`가 읽음) |
| PoC | [poc/agent-poc.md](poc/agent-poc.md) | WO-02 | 에이전트 PoC(P-1·P-3·P-6): 단일 jar·격리 클래스로더·JPMS read edge·ignore 목록·모듈 발견 구조, 계측 모듈 5종, 샘플 앱 7조합 매트릭스(T-AG-010·040·070), 전송 스택 비교와 A-1·A-14·C-23 결정([ADR-0005](adr/0005-agent-transport.md), 잠정 채택), 통합 검증 실행표 |
| PoC | [poc/agent-overhead.md](poc/agent-overhead.md) | WO-02 | 에이전트 오버헤드(P-2): 01 §7.3 11항 하네스, 공유 머신 참고값(유효 세트 0), Gate 0 판정용 전용 장비 절차(원시 자료 `bench/wo02-overhead-2026-10-24/`) |
| PoC | [poc/xlog-ui.md](poc/xlog-ui.md) | WO-04 | XLog 캔버스 PoC: 접근 방식, 프레임 시간 측정(1만·5만·10만 점), 15 §18 #2 답, 사람 재현 절차 |
| PoC | [poc/clickhouse-benchmark.md](poc/clickhouse-benchmark.md) | WO-03·WO-03F | ClickHouse 저장소 PoC(P-4): 1/100(CI)·1/20(로컬) 적재·압축·Q1~Q20, 13 §17 답, A-6·A-9·A-10·A-11·코덱·bloom 결정([ADR-0006](adr/0006-storage-poc-decisions.md), 잠정 채택·결정 4는 ADR-0007 C-53으로 변경), 목표 하드웨어 확인 목록, [ADR-0007](adr/0007-storage-alignment-decisions.md) 반영(17장, WO-03F) |

## 저장소 상태

- **WO-00(저장소 골격 + 오픈소스 골격)**: REVIEW(구현 완료·완료 보고 제출, 사람 수락 검수 대기, 2026-10-23). 04 문서 상태 표는 사람이 갱신한다. Gradle 9.8.2 모노레포(Java 모듈 22개, 빈 소스 + hello 테스트), UI 골격(Vite + React + TypeScript strict + pnpm), 품질 게이트(Spotless·Error Prone·ArchUnit·라이선스 허용 목록·SPDX·`check-shading.sh`), CI 워크플로(`pr.yml`·`codeql.yml`·`secrets-scan.yml`, GitHub에서는 아직 실행하지 않음), 오픈소스 파일 세트가 있다. 기능 코드는 아직 없다.
- **WO-04(XLog 캔버스 PoC)**: REVIEW(구현 완료, 사람 수락 대기, 2026-10-24). 데모 `/xlog-poc`, `ui/src/xlog/` 캔버스, Vitest, Playwright E2E(드래그 → 요약·목록, 통과), `ui/perf/xlog-frame.spec.ts`가 있다. 15 §7.2 원안의 색별 3패스 fill은 5만·10만 점에서 16ms를 넘고, 15 §18 #2의 대안 `ImageData`(`render=pixels`)는 통과한다. 기본 그리기 방식은 ADR-0003 C-34로 `pixels`로 결정되었고 코드 기본값에 반영되었다(판정 단위·상호작용 예산은 C-35·C-36, [poc/xlog-ui.md](poc/xlog-ui.md) 4.7.3·8장).
- **WO-01(샘플 앱 세트 + ext-stub + 부하 생성기)**: REVIEW(구현 완료·r1 리뷰 수정 반영, PM 결정·사람 수락 대기, 2026-10-24). `sample-apps/`에 공유 카탈로그(`common`), 앱 8종(`shop-boot2`·`shop-boot3`·`shop-boot4`·`shop-heavy`·`shop-batch`·`shop-war-javax`·`shop-war-jakarta` + `ext-stub`), 스모크 러너와 `sampleSmoke` 태스크(에이전트 없이 기동해 카탈로그 검증, `check` 미포함), Dockerfile, k6 `load/bench.js`·`run-bench.sh`, 의존성 없는 데모 부하 생성기, `test/seed/seed-traffic.sh`가 있다. 앱별 jar·클래스 수와 에이전트 없는 기동 기준선(N-04·N-27)은 [sample-apps/README.md](../sample-apps/README.md) "Startup baseline" 절에 있다(공유 Windows 머신 측정, 벤치 머신 재측정 필요).
- **WO-02(에이전트 PoC, P-1·P-2·P-3·P-6)**: REVIEW(구현·통합 완료, PM 결정·사람 수락 대기, 2026-11-02). `agent/`에 bootstrap·core·계측 API와 모듈 5종(servlet·spring-webmvc·jdbc·httpclient-jdk·executor), 단일 jar 조립(`agent-dist`, `verifyAgentJar`에 `checkOwnRelocatedLiterals` 추가), 부착·샘플 앱 하네스(`agent-testing`)가 있고, `test/agent/transport-poc`(P-3 비교 jar 9종·벤치)와 P-2 하네스(WO-05에서 `test/agent/overhead` → `test/bench`로 이동)가 있다. 통합 재검증(2026-11-02)에서 전체 build(UI 포함), 샘플 앱 7조합 × JDK(T-AG-010·040·070, C-27 플래그), 부착 테스트 JDK 5종, `check-shading.sh`, 전송 비교 태스크, 빈 캐시 Linux 컨테이너의 잠금·검증 메타데이터 재생성(변경 없음)과 pr.yml build-java 모의가 녹색이다. PMO 1차 판정(REJECTED: CHANGELOG 항목과 02 §5.2 분류 요청 누락) 뒤 수정 1라운드(CHANGELOG `Added` 항목 2개, PoC 14.4 데이터 분류 요청)를 거쳐 같은 검증을 다시 돌렸고 녹색이다(PoC 12.7). A-1(grpc-netty 유지)·A-14(javalite 불채택)·C-23(RELOCATED) 제안은 [ADR-0005](adr/0005-agent-transport.md)(Proposed), 셰이딩 결정은 [ADR-0008](adr/0008-agent-shading-and-bytebuddy-unsafe.md). P-2 오버헤드 Gate 0은 공유 머신에서 판정하지 않았고 전용 장비 재측정을 기다린다([poc/agent-overhead.md](poc/agent-overhead.md)). 측정·결정은 [poc/agent-poc.md](poc/agent-poc.md), 원시 자료는 `bench/wo02-agent-2026-11-02/`·`bench/wo02-overhead-2026-10-24/`.
- **WO-03(저장소 PoC, P-4)**: REVIEW(구현 완료, 목표 하드웨어 측정·PM 결정·사람 수락 대기, 2026-10-24). 번들 ClickHouse 26.8.34.13 LTS(다이제스트 고정, `deploy/clickhouse/image.properties`), `deploy/clickhouse/` V001 스키마·롤업 12종·설정·계정 3종, `test/storage/`(ClickHouse 지원·스키마 테스트·생성기·Q01~Q20 러너·벤치 서버 스크립트)가 있다. CI 1/100 자동 기준(T-ST-030~033·053)은 녹색이고, 로컬 1/20에서 Q1~Q11·정합은 충족, xlog 압축률(2.56 < 3)은 코덱 변경 제안(3.76)으로 해결을 제안한다. 리뷰 r1 수정(시스템 로그 TTL·미사용 리스너 제거·writer 제약·migrator 권한 축소, 롤업 재구축 IT, 생성기 N-06 부하 옵션·100ms tick, 러너 수정, 증거 `docs/bench/wo03-storage-2026-10-24/`)을 반영했고, 통합 재검증(2026-10-24)에서 전체 build·`integrationTest -Pqueries.profile=ci`·1/100 처음부터 끝까지가 녹색이다(PoC 16장). 결정 제안은 [ADR-0006](adr/0006-storage-poc-decisions.md)(Proposed), 측정은 [poc/clickhouse-benchmark.md](poc/clickhouse-benchmark.md).
- **WO-03F(저장소 PoC 후속, ADR-0007 반영)**: REVIEW(구현·통합 완료, PM 결정·사람 수락 대기, 2026-11-03). [ADR-0007](adr/0007-storage-alignment-decisions.md)(C-50~C-71)을 WO-03 산출물에 반영했다. 릴리스 전 V001을 그 자리에서 고쳤다: 트랜잭션 롤업 `quantilesDD` 상태(C-53), 서버 기본·열 코덱 ZSTD(1)(C-54), bloom 리터럴과 `{rollup:}` 뒤 체크섬(C-55·C-56), C-17 열 이름(C-67), writer INSERT 원본 5개 테이블·migrator `system.mutations`(C-60), `background_schedule_pool_size` 기본값(C-71). 저장소 도구에는 staging + `REPLACE PARTITION` 재구축(C-59), `<wal_instance_id>:<epoch>:<first>-<last>` 토큰(C-61), 생성기 N-06 통계 행/s 출력(C-62), `UNION ALL` 정합 SQL(C-65), Testcontainers Ryuk 다이제스트 고정을 넣었다. WO-03 열린 항목 A-1·A-2·A-3·C-1을 닫았다. PMO 수정(pmo-1)으로 스키마 IT의 고정 날짜를 없앴다: 행 시각은 기준일(오늘 UTC − 2일, `-Pschema.anchorOffsetDays=<n>` 2~12)에서 만들고, 보존 기간 밖 시각은 가드가 거부하며, 날짜 리터럴은 `FixedDatesTest`가 막는다(PoC 17.10). 통합 재검증(2026-11-02 16:54~17:09 UTC)에서 전체 build(UI 포함, 모든 작업 재실행, Java 단위 558·UI 456), 루트 `integrationTest -Pqueries.profile=ci`(저장소 69·agent-testing 21·샘플 앱 37), SPDX·라이선스·gitleaks(0건)·워크플로 정책이 녹색이고, Ryuk이 고정 다이제스트로 받아져 실행됨을 확인했다(PoC 17.11, 첫 통합 검증은 17.9). 남은 것: C-57 일 단위 상한 대 D-15 시계 보정 충돌(PoC 17.3, PM 결정), C-62 예시 문구(500×600 필요, 17.4), 목표 하드웨어 재측정, 설계 문서 갱신(메인테이너).
- **WO-05(측정·테스트 하네스 기반)**: REVIEW(구현·통합 완료, 리뷰·PMO·사람 수락 대기, 2026-11-03). `agent/agent-testing`을 18 §5.1 구조의 정식 하네스로 만들었다: `AppLauncher`·`RunningApp`(샘플 앱 × JDK × 에이전트 설정, 부트 jar와 독립 Tomcat의 WAR, 프로세스·컨테이너(JDK 이미지 다이제스트 고정), JVM 프리셋), 인프로세스 gRPC `FakeCollector`(결함 주입·ConfigPush·Command 왕복, WO-02 PoC proto, WO-11 seam `CollectorProtocol`), 결측과 0을 구분하는 단언 DSL, 변환 베이스라인 녹화·diff(T-AG-005, `baselines/transformed-classes/` 17개), fail-safe·재진입·누수 검사(T-AG-040·041·044·050·053, 자가진단 항목은 WO-10 전까지 NOT_AVAILABLE), 로그 허용목록·Unsafe 검사. 에이전트에는 개발 빌드 전용 훅 `argos.test.exporter`·`argos.test.faults`(`transform-error`·`helper-nocdef`, 주입 대상이 없는 spec·지점 단위 spec은 WARN 1회 후 무시)·`argos.debug.transform-log`와 릴리스 빌드의 `argos.test.*`·`argos.testing.*` 무시(T-BLD-010, `Implementation-Version`이 없거나 매니페스트를 못 읽으면 릴리스로 보는 fail-closed, `checkManifestVersion`)를 넣었다. WO-02 PoC 테스트는 하네스로 다시 썼다(전체 매트릭스 93건: 부착 60·종단 14·FakeCollector 10·플래그 6·jar 검사 1·컨테이너 1·WAR 1, 이 중 의도한 skip 3건, WO-02는 51건). 빌드에는 `check` 구성·머신 프로필 가드(`argos.test-layers`), `checkUnitTestSources`, `matrixTest` skip 경고, `serverLoadTest`·`soakTest`·`chaosTest` 자리표시, `traceabilityReport`, 규약 문서 [test/README.md](../test/README.md)를 더했다. P-2 하네스는 `test/agent/overhead`에서 `test/bench`(`:test:bench`, `benchTest`)로 옮겨 18 §7 정식 벤치 러너(환경 기록·A-B 교대·HDR 재집계·부트스트랩 신뢰구간·보고서 `docs/bench/<날짜>-<커밋7>.md`·회귀 판정)로 만들었다. 리뷰 수정(r1) 뒤 통합 재검증(2026-11-03, 공유 노트북 참고치): 전체 build(UI 포함) 3분 39초, build-java 잡 모의 4분 18초, integration 잡 모의 6분 17초(154 테스트), 에이전트 전체 매트릭스 3분 1초(93건, 의도한 skip 3), WAR 2조합(javax@8·jakarta@21) 1분, securityTest·matrixTest(SKIPPED + 경고)·가드·추적성 보고서(샘플 행 DSN-18-15 → T-BLD-010)·잠금 재생성 무변화·라이선스·`check-shading.sh`·gitleaks(0건)·워크플로 정책·actionlint·shellcheck·CI 스크립트 테스트가 녹색이다. PR 게이트 최장 잡(integration)은 25분 예산 안이다. 수동 수락(전용 장비에서 WO-02 수치 재현)과 벤치 머신 확보(19 U-10·U-11)는 남아 있다.
- **Gate 0(PoC 완료)**: PM 판정 "조건부 통과"(2026-11-03). 이 PC에서 낼 수 있는 증빙(에이전트 구조·JPMS·전송 비교·저장소 CI 1/100·UI 10만 점·오픈소스 골격)은 충족했고, 결정 행은 [ADR-0009](adr/0009-gate0-pm-decisions.md)(C-72~C-90)와 02 §12 갱신으로 잠정 확정했다. 수치 3항목(전용 장비 오버헤드, 목표 하드웨어 저장소 T-ST-001~020, 저사양 노트북 UI)과 메인테이너 확인은 사람 몫이며 Gate 1 전에 끝내야 한다. 03 §4 표의 최종 기입은 메인테이너가 한다.
- 빌드·검증 명령은 루트 `CLAUDE.md`의 "빌드·검증 명령" 절과 [dev-setup.md](dev-setup.md)에 있다.

## 읽는 순서

- **처음 보는 사람**: 01 → 02 → 05(2장) → 03
- **구현하는 사람(AI 포함)**: 02 → 담당 영역의 설계 문서 → 03 → 06(해당 영역) → 04의 WO
- **검수하는 사람**: 03 → 04의 WO 수락 시나리오 → 05 2장 필수 요소

## 식별자 체계

| 접두 | 의미 | 정의 위치 |
|---|---|---|
| F-Axx / F-Sxx / F-Uxx | 기능(에이전트/서버/UI) | 01 §6 |
| D-xx | 데이터 정확성 규칙 | 01 §7.1 |
| N-xx | 비기능 요구 | 01 §7.2 |
| S-xx | 사용 시나리오 | 01 §5.6 |
| O-xx | 오픈소스 프로젝트 요구 | 01 §10 |
| SEC-xx | 보안·개인정보 요구 | 06 |
| R-xx | 리스크 | 01 §13 |
| A-x | 아키텍처 미결 | 02 §12 |
| DSN-NN-xx | 설계 결정(문서 NN) | 02, 10~19 |
| P-x | PoC 검증 항목 | 02 §11 |
| WO-xx | 작업 지시서 | 04 |

## 언어·형식

기획·설계 문서는 한국어 원본(코드 식별자·설정 키·SQL·proto는 영어). 사용자 문서·README(영문 원본)·CHANGELOG·SECURITY·CONTRIBUTING은 영어가 원본이며 한국어 번역을 둔다. 모든 문서는 UTF-8, LF.
