# Argos 작업 지시서 (Work Orders)

| 항목 | 내용 |
|---|---|
| 문서 버전 | 0.2 |
| 작성일 | 2026-10-23 |
| 상태 | 설계(02 v0.3, 10~19 v0.1) 기반으로 전면 재작성. Phase 0은 즉시 지시 가능, Phase 1은 Gate 0 통과 후 착수 전에 Phase 0 수준으로 보강, Phase 2a/2b는 목록 |
| 관련 문서 | [01 제품 기획](01-product-plan.md), [02 아키텍처](02-architecture.md), [03 완성 기준](03-definition-of-done.md), [05 벤치마크](05-benchmark.md), [06 보안 요구](06-security-requirements.md), 설계 [10](10-agent-design.md)·[11](11-protocol-design.md)·[12](12-server-design.md)·[13](13-storage-design.md)·[14](14-api-design.md)·[15](15-ui-design.md)·[16](16-security-design.md)·[17](17-deployment-ops-design.md)·[18](18-test-design.md)·[19](19-repo-build-release-design.md) |

## 0. 사용법

- 한 번에 WO 하나를 Claude Code에 전달한다. 지시 문장 예:

  > `docs/04-work-orders.md`의 WO-12를 수행해. 입력 절의 설계 문서를 먼저 읽고, 02 문서 13장 정합 결정을 우선 적용해. 완성 기준은 `docs/03-definition-of-done.md`를 따르고, 끝나면 그 문서 6장 형식으로 보고해.

- 모든 WO는 **목표 / 선행 / 입력 / 산출물 / 수락 기준(자동·수동) / 범위 밖 / 보안 체크 / 보고 특이사항**을 가진다. 수락 기준은 03 문서 DoD, 18 문서 테스트 ID(`T-…`), 05 문서 2장 필수 요소, 06 문서 SEC 검증 열을 **인용**한다. 새 기준을 발명하지 않는다.
- 설계 문서 사이에 세부가 다르면 **02 문서 13장 정합 결정(C-1~C-20)** 이 우선한다. 구현 중 설계를 바꿔야 하면 멈추고 보고한다.
- 각 WO의 "확인 작업"은 설계 문서가 "착수 시 확인"으로 남긴 가정(U-xx)이다. 결과를 완료 보고 "가정과 미해결"에 적고, 가정이 틀리면 해당 설계 문서 미결 표를 갱신한다.
- 상태는 아래 표에 사람이 기록한다. 상태 정의는 03 문서 7장. WO가 하루~며칠을 넘으면 `WO-12a/b`로 쪼갠다.
- 사업 요소(영업·가격·라이선스 키)는 어떤 WO에도 없다. 커밋은 사람이 요청할 때만.

## 1. 전체 상태 표

| WO | 제목 | Phase | Epic | 선행 | 상태 |
|---|---|---|---|---|---|
| WO-00 | 저장소 골격 + 오픈소스 골격 | 0 | - | 미결 12~15 확정 | TODO |
| WO-01 | 샘플 앱 세트 + ext-stub + 부하 생성기 | 0 | - | WO-00 | TODO |
| WO-02 | 에이전트 PoC (P-1·P-2·P-3·P-6) | 0 | - | WO-00, WO-01 | TODO |
| WO-03 | 저장소 PoC (P-4, Q1~Q20) | 0 | - | WO-00 | TODO |
| WO-04 | XLog 캔버스 PoC (P-5) | 0 | - | WO-00 | TODO |
| WO-05 | 측정·테스트 하네스 기반 | 0 | - | WO-00, WO-01 | TODO |
| WO-10 | 에이전트 코어 정식화 | 1 | A | Gate 0 | TODO |
| WO-11 | 프로토콜(proto) + 전송 | 1 | A | WO-10 | TODO |
| WO-12 | servlet·spring-webmvc·servletasync 계측 | 1 | A | WO-10 | TODO |
| WO-13 | jdbc·pool 계측 | 1 | A | WO-10 | TODO |
| WO-14 | httpclient 6종 + 분산 전파 | 1 | A | WO-12 | TODO |
| WO-15 | executor 비동기 전파 + 가상 스레드 | 1 | A | WO-12 | TODO |
| WO-16 | 메트릭 수집기 + 전수 통계 집계기 | 1 | A | WO-11, WO-13 | TODO |
| WO-17 | 원격 명령·동적 설정·킬 스위치 | 1 | A | WO-11 | TODO |
| WO-18 | scheduler·logging·custom 계측 | 1 | A | WO-12, WO-13 | TODO |
| WO-19 | 느린 스택·`--check`·AgentBye·태그·매트릭스 연동 | 1 | A | WO-11~WO-18 | TODO |
| WO-20 | 서버 골격·설정·마이그레이션 러너 | 1 | B | Gate 0 | TODO |
| WO-21 | gRPC 수집·상한·WAL·중복 제거·배치 라이터 | 1 | B | WO-20, WO-11 | TODO |
| WO-22 | 세션·인스턴스 상태·등록 정책·실시간 버스·WebSocket | 1 | B | WO-21 | TODO |
| WO-23 | 조회 API(OpenAPI·소스 선택·봉투) | 1 | B | WO-21 | TODO |
| WO-24 | 인증·세션·CSRF·설정 토큰·RBAC·레이트 리밋 | 1 | B | WO-20 | TODO |
| WO-25 | 알림 엔진·채널·템플릿 | 1 | B | WO-22, WO-23 | TODO |
| WO-26 | 토큰 서비스·명령 라우팅·설정 변경 통제·킬 스위치 | 1 | B | WO-22, WO-24 | TODO |
| WO-27 | 전수 통계·롤업·야간 정합·재구축·에러 지문·Apdex | 1 | B | WO-21 | TODO |
| WO-28 | 감사·비밀값 암호화·삭제 API·저장소 상태·자기 메트릭·`argos-admin` | 1 | B | WO-24, WO-27 | TODO |
| WO-29 | 서버 부하 테스트·메모리 예산 실측 | 1 | B | WO-21~WO-28 | TODO |
| WO-30 | UI 골격(라우팅·URL 상태·시간 범위·인증·i18n·클라이언트·WS·공통 컴포넌트·E2E 프레임) | 1 | C | WO-23, WO-24 | TODO |
| WO-31 | 전체 현황 | 1 | C | WO-30 | TODO |
| WO-32 | 앱 대시보드 + 인스턴스 비교 표 | 1 | C | WO-30 | TODO |
| WO-33 | XLog + 트랜잭션 목록 | 1 | C | WO-30, WO-04 | TODO |
| WO-34 | 트랜잭션 상세 + 트레이스 뷰 | 1 | C | WO-33 | TODO |
| WO-35 | 액티브 트랜잭션 | 1 | C | WO-30 | TODO |
| WO-36 | 통계 4종(서비스·SQL·외부 호출·에러) | 1 | C | WO-30, WO-27 | TODO |
| WO-37 | 인스턴스 상세·스레드 덤프 뷰어 | 1 | C | WO-30, WO-26 | TODO |
| WO-38 | 알림 화면(규칙·채널·이력) | 1 | C | WO-30, WO-25 | TODO |
| WO-39 | 관리·검색·설치 마법사·내 계정 | 1 | C | WO-30, WO-26, WO-28 | TODO |
| WO-40 | Docker Compose·env-init·smoke·설치 가이드 | 1 | D | WO-20 | TODO |
| WO-41 | 데모 프로파일(이미지·부하·60초) | 1 | D | WO-40, WO-01 | TODO |
| WO-42 | 도그푸딩 배포·운영 체크리스트 | 1 | D | WO-19, WO-29, WO-39, WO-40 | TODO |
| WO-43 | 운영 스크립트(backup·restore·diag·compose 래퍼) | 1 | D | WO-28, WO-40 | TODO |
| WO-44 | 릴리스 파이프라인 0.x(서명·SBOM·GHCR) | 1 | E | WO-00 | TODO |
| WO-45 | 프로젝트 파일 세트·거버넌스·compatibility·dev-setup | 1 | E | WO-00 | TODO |
| WO-46 | 보안 테스트 스위트·PII/XSS 시드·위협 모델 초안 | 1 | F | WO-24, WO-26, WO-30 | TODO |
| WO-47 | 공급망 CI(SBOM 스캔·gitleaks·CodeQL·Dependabot·러너 격리) | 1 | F | WO-00 | TODO |
| WO-50 | 호환성 매트릭스 러너(nightly) | 1 | G | WO-05, WO-19 | TODO |
| WO-51 | 오버헤드 벤치 하네스 정식·주간 보고서 | 1 | G | WO-05, WO-11 | TODO |
| WO-52 | 카오스·72시간 soak | 1 | G | WO-21, WO-50 | TODO |
| WO-53 | 추적성 보고서·테스트 카탈로그 동기화 | 1 | G | WO-50 | TODO |
| WO-60~67 | 공개 베타 준비 | 2a | - | Gate 1 | TODO |
| WO-70~83 | 공개 베타 운영 → 1.0 | 2b | - | Gate 1.5 | TODO |

---

## 2. Phase 0: PoC

목표: 기술 리스크를 숫자로 닫고(02 §11 P-1~P-6) 오픈소스 골격을 첫 커밋에 넣는다. 결과로 02 §12의 A-1·A-9·A-10·A-11·A-14와 코덱·bloom을 확정한다.

### WO-00 저장소 골격 + 오픈소스 골격

- **목표**: 모노레포 빌드 뼈대·CI·코드 규약·오픈소스 필수 파일을 만들어 이후 모든 WO가 같은 명령으로 빌드·검사되게 한다.
- **착수 조건**: 01 문서 미결 12(라이선스)·13(저작권자)·14(식별자 세트)·15(공개 시점)를 사람이 확정하고 ADR-0001·0002에 기록한다. 확정 전에는 저장소를 공개하지 않는다.
- **선행**: 없음.
- **입력**: 19 문서 §2(레이아웃), §3.1~3.2(settings·카탈로그·컨벤션 플러그인), §3.3(에이전트 단일 jar 조립 태스크 골격), §3.6(테스트 태스크 5종), §3.7(재현 아카이브·검증 메타데이터), §4(pnpm), §5(Spotless+SPDX·Error Prone·ArchUnit 규칙·ESLint·라이선스 허용 목록·`check-shading.sh`), §6.1~6.2(`pr.yml` 필수 검사), §8(프로젝트 파일 세트). 01 문서 10장 O-L01~O-L08·O-G04·O-G09·O-Q01~Q02. 02 문서 §4.1 모듈 목록. 10 문서 §2 패키지 경계 규칙 B-1~B-4.
- **산출물**
  - `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `gradle/libs.versions.toml`, `gradle/verification-metadata.xml`(체크섬), `build-logic/`(컨벤션 플러그인 6종: java-common, java-agent, java-server, integration-test, protobuf, publishing)
  - 모듈 디렉터리와 빈 소스: `proto/`, `agent/{agent-bootstrap,agent-core,agent-instrumentation/*,agent-dist,agent-testing}`, `server/`, `ui/`, `sample-apps/`, `deploy/`, `test/`, `docs/`(현행 유지), `.github/`
  - JDK toolchain: agent 모듈 `--release 8`(컴파일은 JDK 21 컴파일러, 테스트 실행은 toolchain 8), server 21. 각 모듈에 "hello" 테스트 1개. `./gradlew check`에 `test`·`archTest`·`contractTest`(빈 스펙)·`verifyAgentJar`(빈 jar) 포함
  - `ui/`: Vite + React + TypeScript strict + pnpm, Vitest 1개, ESLint(`react/no-danger` error, `no-restricted-imports` 골격), `tsc --strict`, `check-i18n-keys.mjs`, `check-no-external.mjs`
  - 품질 도구: Spotless(google-java-format + SPDX 헤더 `SPDX-License-Identifier: Apache-2.0`), Error Prone `-Werror`, ArchUnit 규칙 파일(10 문서 §5.3 Advice 규칙 T-AG-001~004, 패키지 경계 B-1~B-4; 람다 `invokedynamic` 검사는 ASM 커스텀 테스트 골격), 라이선스 허용 목록 검사(`checkLicenses`, `checkDevOnlyLicenses`), `test/agent/check-shading.sh`(19 §5.6 사양: 클래스 패키지·META-INF 파일 5종·Unsafe 참조·major 52·크기), `.editorconfig`, `.gitattributes`(LF, 바이너리)
  - 오픈소스 파일: `LICENSE`(Apache-2.0 전문), `NOTICE`(`Argos APM`, 저작권자 미결 13 확정값, 차용 코드 목록 절 비움), `README.md`(en, pre-release 배지, "Telemetry: none") + `README.ko.md`, `.github/ISSUE_TEMPLATE/{bug,feature,compat-report,config}.yml`, `PULL_REQUEST_TEMPLATE.md`, `CODEOWNERS`(핵심 경로 → 메인테이너), `dependabot.yml`, `labeler.yml`(`area/*`, `needs-matrix` 경로 규칙), 라벨 체계 생성 스크립트, `docs/licenses/dev-only.md`(초기 목록: MySQL Connector/J, MariaDB, ojdbc, Tibero·Altibase·CUBRID 드라이버, H2, k6, testssl.sh), `docs/adr/0000-template.md`·`0001-license-apache-2.0.md`·`0002-identifiers.md`
  - `.github/workflows/pr.yml`(19 §6.2 필수 검사 전부: build-java, build-ui, lint, license, agent-dist-checks, integration(빈), security-light(빈), openapi-diff(빈 스펙 허용), changelog, dco), `codeql.yml`, `secrets-scan.yml`(gitleaks). self-hosted 라벨 잡은 아직 없음
  - `CHANGELOG.md`(Keep a Changelog 1.1, `Unreleased`), `docs/dev-setup.md` 초안(JDK 8·21, pnpm, Docker, 30분 빌드 목표), `CLAUDE.md` 빌드·검증 명령 절 실제 명령으로 갱신, `docs/README.md` 상태 갱신
- **수락 기준**
  - 자동: `./gradlew build` 성공, `pnpm -C ui build && pnpm -C ui test` 성공, `pr.yml` 전 잡 녹색. T-BLD-001(major 52), T-BLD-007(SPDX 전수·허용 목록), T-AG-001~004 규칙이 "위반 예제"에서 실패함을 보이는 네거티브 테스트 각 1개. DCO 검사가 `Signed-off-by` 없는 커밋을 거부
  - 자동: 라이선스 허용 목록에 GPL 의존성을 넣은 브랜치에서 `checkLicenses` 실패(네거티브)
  - 수동: `docs/dev-setup.md`만 보고 사람이 새 PC에서 30분 안에 빌드 성공(시간 기록). LICENSE·NOTICE·README 내용 확인
- **확인 작업**: 19 문서 U-7(Flyway community 모듈 라이선스), U-8(ArchUnit의 invokedynamic 검사 한계 → ASM 보완), U-9(DCO 수단), U-14(Error Prone + `--release 8`), 17 문서 U-3(서버 베이스 이미지 Temurin 21 JRE 비루트)
- **범위 밖**: 기능 코드, proto 메시지, Compose, 릴리스 워크플로(WO-44), CONTRIBUTING 등 거버넌스 문서 본문(WO-45. 단 파일 자리와 제목은 만든다)
- **보안 체크**: SEC-73(검증 메타데이터·잠금·Actions SHA 고정), SEC-74(저장소에 자격증명 리터럴 0, `.env.example` 값 비움), O-S05(self-hosted 러너 잡 없음 확인)
- **보고 특이사항**: 미결 12~15 확정값과 ADR 경로를 보고에 적는다. 저장소 공개 여부는 사람이 결정하므로 공개 동작을 하지 않는다.

### WO-01 샘플 앱 세트 + ext-stub + 부하 생성기

- **목표**: 계측·벤치·E2E·보안 테스트가 공유하는 현실적인 대상 앱 세트와 외부 호출 스텁, 재현 가능한 부하 시나리오를 만든다.
- **선행**: WO-00.
- **입력**: 18 문서 §4.1(앱 8종 표), §4.2(공통 엔드포인트 카탈로그), §4.3(PII·XSS 시드 엔드포인트와 센티널 `ARGOS_SEED`), §4.4(구성·패키징), §7.2(k6 `bench.js` 믹스). 01 문서 7.3 워크로드(3항). 02 문서 §9 유효 조합표. 정합 결정 C-18.
- **산출물**
  - `sample-apps/common`(공유 엔드포인트 카탈로그 모듈), `shop-boot2`(Boot 2.7·Java 8·javax·Tomcat 9·Hibernate 5·HikariCP·RestTemplate·HC4·OkHttp3), `shop-boot3`(Boot 3.x·17·jakarta·Tomcat 10.1·Hibernate 6·WebClient·HC5·OkHttp4·Java 11 HttpClient), `shop-boot4`(Boot 4.x·21·Tomcat 11), `shop-heavy`(boot3 + 의존성 200 jar·클래스 3만), `shop-batch`(@Scheduled 3종·Quartz 2종·Spring Batch Tasklet+Chunk 5,000·공유 DataSource), `war-javax`(독립 Tomcat 9), `war-jakarta`(독립 Tomcat 10.1/11, 멀티 웹앱 2개), `ext-stub`(정상·지연·타임아웃·연결 거부·5xx·리다이렉트)
  - 환경변수 스위치: `SAMPLE_DB`(h2|postgres|mysql|…), `SAMPLE_VT`, `SAMPLE_ROUTING_DS`(AbstractRoutingDataSource + TransactionAwareDataSourceProxy + 드라이버 래퍼 중첩), `SAMPLE_POOL`(hikari|tomcat|dbcp2)
  - 엔드포인트 카탈로그 전부(느림·에러·N+1·외부 호출·비동기·async servlet·SSE·WebSocket 업그레이드·풀 고갈·타임아웃·forward·sendError·PII/XSS 시드)
  - `sample-apps/load/bench.js`(k6 `constant-arrival-rate`, 믹스: DB 1~3 SQL 60%, 외부 HTTP 20%, N+1 10%, 느림 5%, 에러 5%), 데모 부하 생성기 이미지 정의, 각 앱 Dockerfile(jib), `sample-apps/README.md`
- **수락 기준**
  - 자동: 8종 모두 기동 + 스모크(카탈로그 엔드포인트 기대 상태 코드) 통과(JDK는 18 §4.1 표의 빌드 JDK). `SAMPLE_VT=true`로 boot3 기동. 라우팅 DS 4단 래퍼 구성이 켜진 상태에서 요청 성공
  - 자동: k6가 로컬에서 1,000 TPS 1분 실행되고 결과 JSON 생성. PII·XSS 시드 엔드포인트 응답에 센티널 포함
  - 수동: README대로 사람이 앱 1종 기동 + 부하 1분 실행
- **확인 작업**: 18 문서 미결(Oracle 컨테이너 `gvenzl/oracle-free` 가용성), Boot 4.x·Framework 7 GA 상태(10 문서 U-8 선행 확인)
- **범위 밖**: 에이전트 부착, 외부 DB 컨테이너 연결 테스트(WO-13·WO-50), 데모 이미지 레지스트리 푸시(WO-44)
- **보안 체크**: 샘플 앱 의존성은 `dev-only.md` 규칙(배포물 미포함). 시드 값은 테스트 전용 센티널만
- **보고 특이사항**: 각 앱의 jar 수·클래스 수·기동 시간(에이전트 없이)을 표로 적는다(N-04·N-27 기준선).

### WO-02 에이전트 PoC (P-1·P-2·P-3·P-6)

- **목표**: 10 문서 §4(단일 jar·AgentClassLoader·premain·JPMS read edge·ignore 목록)와 §5(계측 프레임워크) 구조가 실제로 동작하고, 오버헤드 목표를 만족하며, 전송 스택을 비교해 A-1·A-14를 결정한다. PoC지만 구조는 본 구현의 기반이다.
- **선행**: WO-00, WO-01.
- **입력**: 10 문서 §2~§5, §6.1(servlet)·§6.2(spring-webmvc)·§6.3(jdbc 핵심만)·§10.3(gRPC·Netty 고정 설정), §14(예산), §18 U-3·U-5·U-6. 11 문서 §15 미결 2·3·4. 02 문서 §11 P-1·P-2·P-3·P-6, DSN-02-01~03. 01 문서 7.3 측정 프로토콜 전체, N-01~N-04·N-26·N-27·N-30. 19 문서 §3.3(단일 jar 조립). 정합 결정 C-1(StepType 번호), C-9.
- **산출물**
  - `agent-bootstrap`: `AgentPremain`, `AgentBootstrap`, `Argos`, `Tracer`, `TraceContext`, `Scope`, `CallDepth`, `ArgosThread`, `KillSwitch`, `ContextStore`, `StepType`(C-1 번호), `Kind`, `DictKind`, `ModuleHealth`
  - `agent-core`: `AgentStarter`, `AgentClassLoader`(classdata 로딩, 부모 = 플랫폼 CL/JDK 8은 null), `ModuleReadEdgeListener`, `IgnoreMatchers`, 최소 `TracerImpl`·`Transaction`·`StepBuffer`, `AgentLogger`(파일 롤링·stderr 폴백 제한), 익스포터 SPI(`file` JSONL·`noop`·`grpc` 자리)
  - `agent-instrumentation`: `api`(InstrumentationModule·TypeInstrumentation·SafeMatchers·Witness·HelperClasses), `servlet`(javax·jakarta, 요청 경계는 REQUEST만 시작하는 최소 규칙), `springwebmvc`(`bestMatchingPattern`), `jdbc`(prepareStatement·execute만), `httpclient.jdk`(P-6용 `HttpURLConnection` 1지점), `executor`(P-6용 `ThreadPoolExecutor` 1지점)
  - `agent-dist`: 단일 jar 조립(`inst/*.classdata`, 셰이딩 `io.argos.agent.shaded`, NOTICE 트랜스포머, 매니페스트 `Premain-Class`·`Agent-Class`·`Can-Retransform-Classes`), 전송 스택 3종을 각각 포함한 비교 빌드(grpc-netty NIO·JDK TLS / grpc-okhttp / 자체 HTTP/1.1+protobuf 스텁), javalite vs protobuf-java 크기 비교
  - `agent-testing` 최소: 샘플 앱 기동 유틸, JSONL 단언, 변환 클래스 목록 기록
  - `docs/poc/agent-poc.md`(P-1·P-3·P-6 결과·구조 설명·결정), `docs/poc/agent-overhead.md`(P-2, 7.3 프로토콜 11항 전부 기록, no-op 익스포터 기준·컨테이너 `--cpus=2` 병기)
- **수락 기준**
  - 자동: shop-boot2 × JDK 8/11/17/21, shop-boot3 × JDK 17/21 기동 + 요청 → JSONL에 트랜잭션(서비스 이름 `GET /api/orders/{id}`, 상태, elapsed)·SQL step 기록(T-AG-010 PoC 버전). 임시 파일 0(T-BLD-006, 기동 중 `/tmp` 감시). `check-shading.sh` 통과(T-BLD-001~004). JDK 17·21·25에서 `HttpURLConnection`·`ThreadPoolExecutor` 계측 `IllegalAccessError` 0(T-AG-070). Advice 예외 주입 시 앱 2xx(T-AG-040 PoC 버전). 변환 클래스 목록 파일 생성
  - 수동: `agent-overhead.md`가 Gate 0 기준(03 §4: no-op 상대 CPU ≤ 5%, p99 증가 ≤ max(1ms, 5%), 기동 +2초 이하 heavy 포함)을 충족하고 사람이 1회 재실행해 재현. 전송 스택 비교표(jar 크기·클래스 수·메타스페이스·기동·직접 메모리·스레드·8u252 TLS·JDK 25 deny)로 A-1·A-14 결정, 02 §12 갱신
- **확인 작업**: 10 문서 U-3(`ClassInjector` + `redefineModule`), U-4(8u252 ALPN), U-5(ByteBuddy 최소 버전·`experimental` 프로퍼티 셰이딩), U-6(Shadow 프로퍼티 문자열 relocate → 19 문서 U-6 결정), 11 문서 미결 2(javalite `map`·`optional`), 19 문서 U-4(`minimize` exclude), U-5
- **범위 밖**: 전송 본 구현(WO-11), 메트릭, 나머지 계측 모듈, 설정 레퍼런스 전체, 명령
- **보안 체크**: F-A35(Unsafe 참조 0, stdout 0)를 PoC jar에서도 확인(T-BLD-004, T-AG-060 PoC 버전). 토큰은 PoC에서 사용하지 않음
- **보고 특이사항**: 측정 환경·명령·원시 수치 파일 경로를 전부 적는다. 수치 없는 "충족"은 무효(01 §7.3 11항).

### WO-03 저장소 PoC (P-4, Q1~Q20)

- **목표**: 13 문서 스키마가 목표 규모에서 조회 성능·압축률·적재 안정성·정확성을 만족하는지 확인하고 A-6(확정 검증)·A-9·A-10·A-11, 코덱, bloom 파라미터를 결정한다.
- **선행**: WO-00.
- **입력**: 13 문서 §3(원본 DDL)·§4(롤업·MV)·§5(중복 제거 토큰)·§6.3(대표 SQL)·§7(설정)·§15(PoC 사양·Q1~Q20)·§17(확인 항목). 18 문서 §9(`storageBench` 하네스). 02 문서 §11 P-4, 정합 결정 C-1·C-2·C-6·C-16·C-17. 01 문서 N-07·N-18·N-19.
- **산출물**
  - `deploy/clickhouse/schema/V001__init.sql`(13 §3·§4 테이블 전부, C-17 추가 컬럼, C-1 Enum8, `instance_stat_1m`), `deploy/clickhouse/rollups/*.sql`(MV SELECT 본문, 재구축 공용), `deploy/clickhouse/config.d/argos.xml`·`users.d/argos.xml`(13 §7.2·7.3, RAM 치환 변수)
  - `test/storage/generator`(Java, 13 §15 분포·규모, 벌크·리플레이 모드, 늦은 도착·중복·시계 오차·자정 경계), `test/storage/queries/Q01~Q20`, 실행기·보고서 생성기, 1/100 축소 CI 프로파일
  - `docs/poc/clickhouse-benchmark.md`(환경, 적재 속도, 테이블별 압축률·행당 바이트 B_*, parts 추이, 메모리 피크, 쿼리별 콜드·웜 시간, 정합 결과, 결정 사항)
- **수락 기준**
  - 자동(CI, 1/100): 생성·적재, T-ST-030~033(Q12·Q13·Q15·Q16 정합 0), T-ST-053(`argos_reader` 쿼리 상한·`system.query_log`)
  - 수동(사내 `argos-storage` 8코어/16GB/1TB NVMe): T-ST-001~020. Gate 0 기준: Q1~Q11 충족, 압축률 xlog ≥ 3·profile ≥ 5·metric ≥ 8(T-ST-041), Q12·13·15·16 불일치 0, Q17 동시 부하(쿼리 p95 2초·적재 지연 p99 5초·parts < 300·DelayedInserts 0·메모리 < 7.5GiB), Q18~Q20 기록. 미달 항목은 원인·대안 기록
  - 수동: Q5·Q6·Q11·Q14·Q20 결과로 A-6 검증·A-10·A-11·코덱·bloom 결정, 13 문서 DDL과 02 §12 갱신. Q18의 삭제 뮤테이션·DROP 소요 기록
- **확인 작업**: 13 문서 §17 전체(`insert_deduplication_token` + MV 비복제 동작, `quantilesBFloat16`·`DD` -State 지원, parts 기본값, `max_partition_size_to_drop` 테이블 단위, DateTime64 TTL, 집계 상태 버전 호환, Replicated 전환 플래그, `Array(UInt64)` bloom, Gorilla Float64), 12 문서 미결 2·6, 17 문서 미결 1(고정 LTS 버전)
- **범위 밖**: 서버 코드, WAL 구현(WO-21), 실제 에이전트 데이터, PostgreSQL 스키마(WO-20)
- **보안 체크**: SEC-74(ClickHouse `default` 사용자 비활성, 전용 계정 3종 최소 권한), SEC-76(`argos_reader` DDL 시도 실패)
- **보고 특이사항**: 결정 항목(A-9·A-10·A-11·코덱·bloom)별로 근거 수치를 표로. 번들 고정 ClickHouse 정확 버전을 보고.

### WO-04 XLog 캔버스 PoC (P-5)

- **목표**: 15 문서 §7의 Canvas 2D 아키텍처가 10만 점에서 16ms/프레임을 지키고 드래그 선택·요약이 자연스러운지 확인한다.
- **선행**: WO-00.
- **입력**: 15 문서 §2.1(스택), §7.1~7.5(XLogBuffer TypedArray·renderer 3패스 fill·hitIndex 8px 격자·selectionSummary·실시간), §14(성능 예산), §18 미결(fill 방식 16ms 달성). 05 문서 2.1 필수 요소. 02 §11 P-5. 01 N-14.
- **산출물**
  - `ui/src/xlog/{XLogCanvas.tsx, XLogBuffer.ts, renderer.ts, hitIndex.ts, selectionSummary.ts}`: 점 색 3종·크기 1종, y축 선형·로그, 지연 임계선, 드래그 사각형 → 선택 콜백, 리사이즈·DPR, 색 기준 전환(상태/인스턴스/서비스 상위 N), 샘플링 표기 자리
  - 더미 데이터 생성기(10만 점 로그정규 롱테일, 인스턴스·서비스 분포), 실시간 시뮬레이션(초당 500점, 5분 창), 데모 라우트 `/xlog-poc`, 선택 영역 요약 패널(점 수, 서비스별 상위 10, 인스턴스별·에러별 분포)
  - `ui/perf/xlog-frame.spec.ts`(Chrome tracing, 프레임 시간 분포), Vitest(좌표 변환·선택 영역·요약 계산), Playwright 1개(드래그 → 요약·목록 N건)
  - `docs/poc/xlog-ui.md`(측정 결과·접근 방식·한계)
- **수락 기준**
  - 자동: T-UI-001(좌표·선택 영역 계산·샘플 표시), T-E2E 드래그 → 목록, `xlog-frame.spec.ts` 16ms/프레임 이하(T-NFR-14 PoC 버전)
  - 수동: 사람이 데모에서 10만 점 실시간 모드로 1분 조작, 체감 끊김 없음, Performance 탭 스크린샷 첨부
- **확인 작업**: 15 문서 미결(3패스 fill 방식의 16ms 달성, 오프스크린 점 레이어 효과)
- **범위 밖**: 실제 API 연동(WO-33), 디자인 토큰·Ant Design 적용(WO-30), 트랜잭션 목록 표 공통 컴포넌트
- **보안 체크**: 더미 서비스 이름에 XSS 페이로드를 섞어 툴팁·요약이 텍스트로만 렌더됨을 Vitest로 단언(SEC-61 선행)
- **보고 특이사항**: 프레임 시간 분포(p50·p95·max)와 점 수별(1만·5만·10만) 표.

### WO-05 측정·테스트 하네스 기반

- **목표**: Phase 1 모든 WO가 쓸 `agent-testing` 하네스(AppLauncher·FakeCollector·단언 DSL·베이스라인·fail-safe 주입·LeakProbe)와 벤치 자동화의 뼈대를 만든다. WO-02의 최소 하네스를 정식화한다.
- **선행**: WO-00, WO-01(WO-02와 병행 가능).
- **입력**: 18 문서 §2.2(태스크·소스 세트), §3(테스트 ID 규약·`@Tag("req:…")`), §5.1~5.10(하네스 모듈), §7.1~7.7(벤치 하네스·보고서 템플릿·회귀 판정), §15(데이터·플레이키·아티팩트). 01 문서 7.3. 10 문서 §19(18에 요구한 익스포터 SPI·결함 주입·`argos.test.*`).
- **산출물**
  - `agent-testing`: `AppLauncher`(샘플 앱 × JDK × 옵션 × 에이전트 부착, `argos.test.*` 네임스페이스), `FakeCollector`(gRPC 인프로세스, 지연·거부·명령 발행·`hang()`), 익스포터 SPI(`file`/`noop`/`grpc`), 단언 DSL(트랜잭션 수·서비스명·step 트리·결측 표현·카운터), 변환 베이스라인 녹화·diff(T-AG-005), fail-safe 주입 모듈(T-AG-040·041·044), 재진입 검증(T-AG-050), `LeakProbe`(T-AG-053 4종), JVM 옵션 프리셋(`-XX:+BytecodeVerificationLocal`, JDK 25 `--sun-misc-unsafe-memory-access=deny`), 로그 허용목록 fixture(T-AG-060), stdout 0 검사
  - Gradle 태스크 분리(19 §3.6·18 §2.2): `test`·`integrationTest`·`contractTest`·`securityTest`·`matrixTest`(skip 규칙)·`benchTest`·`serverLoadTest`·`storageBench`·`soakTest`·`chaosTest`·`verifyAgentJar`·`archTest`
  - `test/bench/`: 환경 기록 스크립트, k6 러너, A-B-A-B-A-B 교대 실행기, CPU(`/proc`·cgroup)·HDR·메모리(`jcmd`·NMT)·기동 측정, 보고서 생성기(`docs/bench/<YYYY-MM-DD>-<commit7>.md` 템플릿), 회귀 판정(off CV 2% 초과 INVALID), 컨테이너·가상 스레드 변형 실행 옵션
  - 테스트 ID·요구 태그 규약 문서(`test/README.md`)와 추적성 보고서 생성기 골격(`build/reports/traceability.md`)
- **수락 기준**
  - 자동: WO-02의 PoC 테스트가 하네스로 재작성되어 통과. `./gradlew check`가 `test`·`archTest`·`contractTest`·`verifyAgentJar`를 포함. `matrixTest`가 self-hosted 라벨 없이는 skip + 경고. 추적성 보고서가 생성되고 샘플 태그 1개가 대응표에 나타남
  - 자동: 벤치 러너가 off CV 2% 초과 시 INVALID 판정하는 단위 테스트, 보고서 템플릿 생성
  - 수동: 사람이 벤치 러너로 WO-02 수치를 1회 재현(같은 결과 ±신뢰구간)
- **확인 작업**: 19 문서 U-10·U-11(사내 러너·벤치 머신 2대 확보 상태), 18 문서 미결(부하기 머신·측정 잡음 임계)
- **범위 밖**: 매트릭스 전체 실행(WO-50), 카오스·soak 스크립트(WO-52), E2E 프레임(WO-30)
- **보안 체크**: `argos.test.*`·`argos.testing.*`가 릴리스 빌드에서 무시됨(T-BLD-010)
- **보고 특이사항**: 태스크별 소요 시간(PR 게이트 25분 예산 대비).

### Gate 0 판정

03 문서 §4 Gate 0 표를 사람이 채운다.

| Gate 0 항목 | 증빙 WO |
|---|---|
| 에이전트 구조 | WO-02(`docs/poc/agent-poc.md`, T-AG-010·040·070, T-BLD-006) |
| 오버헤드 | WO-02(`docs/poc/agent-overhead.md`), WO-05 재현 |
| 전송 스택 | WO-02 비교표 → A-1·A-14 |
| JPMS | WO-02 T-AG-070 |
| 저장소 | WO-03(`docs/poc/clickhouse-benchmark.md`, T-ST-001~020·030~033·041·053) |
| UI | WO-04(`docs/poc/xlog-ui.md`, T-NFR-14 PoC) |
| 오픈소스 골격 | WO-00(LICENSE·NOTICE·SPDX·허용 목록·DCO, ADR-0001·0002) |
| 결정 | 02 §12 A-1·A-6·A-9·A-10·A-11·A-14, 코덱·bloom → 02·13 갱신 + ADR |

통과 후 Phase 1 각 WO를 Phase 0 수준(8개 항목)으로 보강해 지시한다.

---

## 3. Phase 1: MVP (내부 베타) → `0.x` 사전 릴리스

목표: 01 문서 6.1의 M 항목 전부, 06 문서 단계 M의 SEC 항목 전부, D-01~D-26 테스트 고정. 아래 각 WO의 "수락 기준"은 18 문서 부록 A의 테스트 ID를 인용한다. Epic 간 병렬 가능하며 의존은 상태 표의 선행 열을 따른다.

### Epic A: 에이전트 (10 문서)

**WO-10 에이전트 코어 정식화**
- 목표: WO-02 구조를 본 구현으로. 10 문서 §2 패키지 전부의 골격, §3 bootstrap API 확정, §9.1~9.2(Transaction·StepBuffer·종료 시 샘플링·요약 모드), §9.5(TxIdGenerator Snowflake·TraceIds), §10.1(큐 삼중 상한 건수·120초·바이트), §14(자기 보호·로그·예산), §15(설정 레퍼런스 전부: ConfigLoader 우선순위 yml → `-Dargos.*` → `ARGOS_*`, `RemoteConfigGate`, `EffectiveConfig`·출처, `ConfigHash`), `Masker`·`MaskPatterns`, `Xxh3`.
- 입력: 10 §2·§3·§9.1·9.2·9.5·§10.1·§14·§15·§17 DSN-10-01~04·08·09·16~18·20. 02 DSN-02-01·09, C-1·C-4·C-9. 06 SEC-06·26.
- 산출물: 위 클래스 전부, ArchUnit 규칙 전체 활성(B-1~B-4, Advice 규칙), 설정 레퍼런스 문서(`docs/agent-config.md` 초안, 원격 허용 여부 열 포함), 자가 진단 필드(ThreadLocal 잔존·레지스트리·약참조·에이전트 CPU·직접 메모리·premain_ms).
- 수락 기준: T-AG-001~006, T-AG-040~046(fail-safe·exclude-classes·킬 스위치 2단·초기화 예외·stderr 폴백·retransform 상한), T-AG-050, T-AG-053(누수 4종), T-AG-060, T-ACC-01(단조 시계), T-ACC-11(tx_id 1,000만 충돌 0·`>> 23` 복원), 설정 우선순위·`EffectiveConfig` 출처 단위 테스트, 큐 삼중 상한 초과 드롭·카운터 테스트, 힙 64MB 상한 테스트(N-02 축소판). 수동: 설정 레퍼런스 표와 실제 키 일치 확인.
- 확인 작업: 10 U-17(xxh3 자체 구현 vs ZAH Unsafe), U-15.

**WO-11 프로토콜(proto) + 전송**
- 목표: 11 문서 proto 전체를 `proto/` 모듈로 작성하고(C-1·C-3·C-4·C-16 적용), 10 §10.2~10.4 전송(배치 unary·in-flight 4·deadline 5초·`agent_epoch`·종류별 seq·백오프·`isReady`)·세션 스트림(Hello·heartbeat·ClockSync `prev_t1/prev_t4`·사전 선전송 LRU·InstanceUpdate·AgentBye)·gRPC/Netty 고정 설정(§10.3)·TLS 검증(SEC-08)·토큰 보관 3방식·마스킹(SEC-06)을 구현한다.
- 입력: 11 §1~§12 전체, 10 §10, 02 DSN-02-11·22, C-3·C-4, 06 SEC-06·07·08·15(클라이언트 측), 19 §3.4(proto 생성, `buf` 린트).
- 산출물: `proto/argos/v1/*.proto` + `PROTOCOL_VERSION`, `buf.yaml`(lint·breaking), 에이전트·서버 각각 생성 설정, `GrpcTransport`·`SessionClient`·`Sender`·`BatchAssembler`·`Backoff`·`DictionaryCache`·`ClockSync`, `grpc` 익스포터.
- 수락 기준: T-AG-071(in-flight·백프레셔·드롭·seq 단조·8u252 TLS), T-AG-067(AgentBye 1초 플러시), T-ACC-02(시계 오프셋), T-ACC-06(드롭 가시화), T-SEC-06(평문 WARN·`token-file` 0600·마스킹), T-SEC-07·08 클라이언트 측(호스트명 불일치 실패·skip-verify WARN·평문은 서버 플래그 없으면 거부), `buf breaking` CI, N-01·N-03을 gRPC 전송 포함으로 재측정해 PoC 수치와 비교(WO-05 하네스), 스레드 ≤ 6·직접 메모리 ≤ 8MB(T-NFR-26). FakeCollector 계약 테스트(11 §16 요구).
- 확인 작업: 11 미결 4(8u252·8u261), A-1·A-14 확정값 적용.

**WO-12 servlet·spring-webmvc·servletasync 계측**
- 목표: 10 §6.1·6.2·6.7·§7(요청 경계 상태 머신, 제외 규칙 평가 순서, 서비스 이름 폴백·사전 상한, 스트리밍·101·max-duration), 헤더 화이트리스트·IP 모드·user_id 모드·쿼리스트링 제거·XFF trusted-proxies, 멀티 웹앱 컨텍스트 경로 접두.
- 입력: 10 §6.1·6.2·6.7·§7·§8(요청 속성 앵커), 02 DSN-02-04, C-8 무관. 06 SEC-31·32·34·37. 01 F-A01·A02·A23·A28·A29·A34·A36, D-04·D-19·D-21·D-26.
- 수락 기준: T-AG-010~017, T-AG-020, T-ACC-04(디스패치 5종), T-ACC-19(제외 카운터), T-ACC-21(최종 상태 코드), T-ACC-26(요청 비소비 Mock 단언), T-SEC-31·32·34·37, shop-boot2/3/4 + war 2종 통합.
- 확인 작업: 10 U-8(`BEST_MATCHING_PATTERN_ATTRIBUTE` Framework 7), U-18(멀티 웹앱 사전 상한).

**WO-13 jdbc·pool 계측**
- 목표: 10 §6.3(`getConnection` DBC·prepare SQL 캡처 필드 주입·execute exec_ms·`ResultSet` 카운터·fetch_ms·commit/rollback·CallDepth 최외곽·`DbInfo`), §9.4(SqlNormalizer·Tokenizer·D-22 규칙·1ms 예산·`<unparsed>`·norm_version), BindParameterCapturer(3모드·타입 화이트리스트·256자·64개·배치 첫 세트), JdbcUrlParser(9벤더), §6.4(pool·tomcat 레지스트리 + getter, JMX 비사용).
- 입력: 10 §6.3·6.4·§9.4, 02 DSN-02-06·07, C-1(DBC=2·COMMIT=3·ROLLBACK=4), 06 SEC-35·3A, 01 D-22·D-24·F-A03·A04·A10.
- 수락 기준: T-AG-030~036, T-AG-062(풀·Tomcat JMX off 수집 부분), T-ACC-22(정규화 50케이스 fixture `normalization-cases.yaml` PII 시드 포함), T-ACC-24(라우팅 DS 4단 래퍼 + 매트릭스 DB에서 step 1), T-SEC-35, T-SEC-3A(저장값·마스킹). Testcontainers MySQL·MariaDB·PostgreSQL·MS SQL + 사내 Oracle(미확보 시 skip 표기).
- 확인 작업: 10 U-7(풀 getter 안정성), 18 미결(Oracle 컨테이너).

**WO-14 httpclient 6종 + 분산 전파**
- 목표: 10 §6.5(jdk·apache4·apache5·okhttp·jdk11·reactornetty, 실패 분류 TIMEOUT/CONNECT_FAIL/5xx/기타), §8(W3C `traceparent` 보존·`tracestate` `argos=` 엔트리·`exclude-hosts`·`argos-state.hosts`), WebClient는 Servlet MVC `block()/toFuture()/subscribe()` 캡처 범위(DSN-10-14), `readOnly=false` 허용 목록 파일(jdk11 헤더 부가).
- 입력: 10 §6.5·§8·DSN-10-04·14, 02 DSN-02-01(완화)·06, C-9, 06 SEC-38, 01 F-A05·A06·A32·A33, D-05.
- 수락 기준: T-AG-055(WebClient block 전파·WebFlux 앱 메트릭만), T-AG-056(6종 step·헤더·실패 분류), T-AG-057(기존 traceparent 보존·tracestate·exclude-hosts), T-ACC-05(2-hop 호출자 ≥ 피호출자), T-SEC-38, ext-stub으로 타임아웃·연결 거부·5xx·리다이렉트 분류 통합.
- 확인 작업: 10 U-9(reactor-netty 시그니처 1.0~1.2, witness), U-10(jdk11 sendAsync 완료 지점), U-11(OkHttp enqueue 매핑).

**WO-15 executor 비동기 전파 + 가상 스레드**
- 목표: 10 §6.6(허용목록 Executor·필드 주입 `ContextStore`·약참조 폴백·실행 측 `run/call/exec` Advice·래핑 금지·Netty·Reactor·`ArgosThread` 제외), `CompletableFuture.*Async`, Spring `@Async`, §8 가상 스레드(인식 `MethodHandle` 캐시·CPU/alloc 결측·`ThreadNames` 정규화·사전 상한 1,000·`ActiveRegistry` 키 = TraceContext·약참조 Thread), `ThreadPerTaskExecutor`.
- 입력: 10 §6.6·§8·DSN-10-05·13, 02 DSN-02-05, C-4, 01 F-A07·A30, D-23.
- 수락 기준: T-AG-051(허용목록 밖 미변환·`remove()`·`cancel()` 보존·병렬 스트림·ThreadPerTaskExecutor), T-AG-052(CF·@Async 결합·오염 0), T-AG-054(VT 모드 4항), T-ACC-23(측정 불가 → 결측), 변환 베이스라인에 Executor 구현체 외 클래스 없음(T-AG-005).
- 확인 작업: 10 U-1(VT CPU·alloc -1), U-12(`CompletableFuture` 내부 필드 8~25), U-16(`tomcat-handler-` 접두).

**WO-16 메트릭 수집기 + 전수 통계 집계기**
- 목표: 10 §11(JvmMetrics·GcNameMapper 매핑표·pause/cycle·MetricRegistry gauge/counter·`delta`·PoolRegistry·TomcatRegistry·SystemMetrics·CgroupReader v1/v2·ActiveSnapshotter 5초·시리즈 상한 500), §9.3(StatsAggregator 10초 창 SQL·EXT, 15구간 버킷, 메모리 상한, flush → `SendStats`).
- 입력: 10 §9.3·§11, 13 §3.3~3.5(StatWindow·MetricSample 필드·buckets 경계 상수), 11 StatsBatch·MetricSeries, 02 DSN-02-08·20, C-15·C-16, 01 F-A09~A11·A22, D-09·D-12·D-16·D-25.
- 수락 기준: T-AG-061(10초 창 집계 = 실제 실행 수), T-AG-062(JVM·풀·Tomcat JMX off·GC 매핑표 전부), T-AG-063(cgroup v1·v2 한도 기준·`container_*`), T-ACC-12(전수 통계 = 앱 측 카운터), T-ACC-16(counter 리셋 보정·sample_count·501번째 드롭), T-ACC-25(ZGC 세대형·Shenandoah·G1·Parallel·Serial 라벨), 메트릭 이름 OpenMetrics 규약 스냅샷(T-API-009 에이전트 부분).
- 확인 작업: 10 U-14(세대형 ZGC MXBean 이름).

**WO-17 원격 명령·동적 설정·킬 스위치**
- 목표: 10 §12(CommandDispatcher·ThreadDumpCommand JDK별(`dumpThreads` 21+·`dumpAllThreads`)·ThreadStackCommand·SelfDiagCommand·ReloadCommand·EnvDumpCommand + `EnvMasker`(끄기 불가)·`Cooldown` 5초·명령 폐쇄 열거 8종), §12.2(`RemoteConfigGate` 하드 캡 평가 7단계·기본 allow·기본 불허·`ConfigApplyResult{rejected}`·retransform 상한 50/분당 1회), 킬 스위치 2단 원격 적용.
- 입력: 10 §12·DSN-10-16·19, 11 Command/CommandResult/ConfigPush/ConfigApplyResult, 16 §5(마스킹 패턴 정확한 목록·테스트 30케이스·allow 알고리즘), 02 DSN-02-17, 06 SEC-20·23·24(쿨다운)·25, 01 F-A12·A13·A14(금지 접두사)·A15.
- 수락 기준: T-AG-043(킬 스위치 원격), T-AG-046(retransform 상한), T-AG-069(하드 캡 거부·적용), T-AG-072(환경 덤프 마스킹 30케이스 + 시드 0건), T-SEC-20·23·25, 가상 스레드 포함 덤프(T-AG-054 덤프 항목), FakeCollector 명령 왕복 테스트, N-25 에이전트 측 적용 ≤ 10초 기여분 측정.
- 확인 작업: 10 U-2(`dumpThreads` 가용 버전).

**WO-18 scheduler·logging·custom 계측**
- 목표: 10 §6.8(Spring `@Scheduled`·Quartz `Job.execute`·Spring Batch `AbstractStep.execute` → `kind=BATCH`, 서비스 이름 `BATCH <…>`, 청크 반복 요약 모드, 컨텍스트 밖 SQL 카운터 `sql_outside_tx_total`·orphan step 카운터), §6.9(Logback·Log4j2·Log4j1 이벤트 생성 시점 MDC 주입 `argos.trace_id`·`argos.tx_id`), §6.10(custom 패턴·생성 클래스 제외·금지 접두사·패턴당 500 상한·`app-packages` 자동 추정).
- 입력: 10 §6.8~6.10·DSN-10-07·15, 02 DSN-02-15, C-1(SUMMARY=11), 06 SEC-25·26, 01 F-A14·A20·A24, D-20.
- 수락 기준: T-AG-058(배치 3종 kind·청크 요약), T-AG-065(MDC 4프레임워크·`MDC.clear()` 무영향), T-AG-036(트랜잭션 밖 SQL 카운터), T-ACC-20(kind 분리·HTTP 통계 비영향·`sql_outside_tx_total`), T-SEC-25(금지 패턴·501 비활성), T-SEC-26(INFO PII 0), shop-batch 통합.
- 확인 작업: 10 U-13(Log4j2 `injectContextData` 시그니처), U-19(Spring Batch 청크 요약 충분성), U-20(`app-packages` 자동 추정).

**WO-19 느린 스택·`--check`·AgentBye·태그·매트릭스 연동**
- 목표: 10 §13.1(SlowStackWatcher 1초 감시·사이클당 10·일괄 `getThreadInfo`·깊이 64·VT 폴백·비용 자가 진단 → STACK step), §13.3(`--check` CLI 표·종료 코드: 연결·TLS·토큰·시계·JDK·호환성 표 대조), AgentBye·기동/종료 이벤트, `app.version`(설정·매니페스트)·`app.env` 필수, 기동 배너 1줄, OverheadMonitor 자동 샘플링 하향, 에이전트 모듈 README(대상 버전 범위·witness) 전수, 매트릭스 러너(WO-50)에 넘길 조합 파라미터 정리.
- 입력: 10 §13·§14 OverheadMonitor, 17 §17 요구(`--check` 형식·`token-file`·`ca-file`·로그 0700·배너), 01 F-A19·A21·A25·A26·A27, N-30.
- 수락 기준: T-AG-064(느린 스택 상한·깊이·일괄·비용), T-AG-066(버전·env 태그), T-AG-067(AgentBye), T-AG-068(`--check` 표·종료 코드 5항), premain ≤ 500ms 로그 단언(T-NFR-30), 모듈 README 존재 린트, 벤치 재측정(T-NFR-01~04·26~30 Gate 1 수치 생성).
- 확인 작업: 10 U-13(VT `getStackTrace` 비용).

### Epic B: 수집 서버 (12 문서)

**WO-20 서버 골격·설정·마이그레이션 러너**
- 목표: 12 §1 패키지 골격, `bootstrap`(기동 순서 설정 → 키 → PG Flyway → CH 러너 → WAL 복구 → 리스너, DSN-12-15), `config`(`ArgosProperties` 12 §10 설정 레퍼런스 전부·금지 비밀번호 거부·`*_FILE` 참조·유효 설정 노출), PostgreSQL Flyway `V001__init.sql`(13 §12 스키마 전부 + C-11 계정·권한, `audit_logs` INSERT·SELECT만), ClickHouse 자체 러너(13 §13.2: `ch_schema_history`·체크섬·멱등 정규식·플레이스홀더·`M` 절차 파일·4단계), 관리 포트 8081(127.0.0.1·베어러, C-7), 헬스·readiness(마이그레이션 완료), 구조화 로깅(요청 id·비밀값 마스킹 appender), Micrometer 골격.
- 입력: 12 §1·§10·DSN-12-15, 13 §12·§13, 17 §6.1(설정 트리)·§17 요구, 06 SEC-55·74·76, 02 C-7·C-11, 01 N-22.
- 수락 기준: T-SV-001(빈 DB 멱등 2회), T-SV-002(이전 스키마 + 1/100 증분·각 ≤ 10분, nightly), T-ST-052(두 경로·MV 4단계 리허설), T-SV-049(금지 DB 비밀번호 기동 거부), T-SEC-55(`/actuator/env` 404·prometheus 무인증 401·8081 바인딩), 조회 계정 DDL 실패(T-SEC-76), 설정 키 스냅샷 테스트 골격(T-API-009 서버 부분).
- 확인 작업: 17 미결 11(`management.server.port` 분리), 13 §17 러너 관련 항목.

**WO-21 gRPC 수집·상한·WAL·중복 제거·배치 라이터**
- 목표: 12 §3.1~3.9(gRPC 서버·인터셉터 체인 인증 → 상한(해제 후 크기·배치 건수·문자열 길이) → 쿼터 → 레이트, `AgentServiceImpl`, `WalWriter`/`WalSegment`/`WalReader`/`BlockIndex`/`WalRecovery`(INTENT/COMMIT 레코드·그룹 fsync 10ms·상한 10GiB·`ACK_OK + dropped_items`), 중복 제거 윈도 `(instance_id, agent_epoch, kind, seq)` 1,000 + WAL 복원, `IngestPipeline`(Decoder·DictionaryResolver 비동기 upsert·TimestampGuard·ErrorGroupFingerprinter·ApdexStamper·env/kind), `ClickHouseWriter`(테이블별 2초/50,000행·명시 컬럼·`insert_deduplication_token = argos:{table}:{block_id}`·`async_insert=0`·재시도·WAL 재적재), 쿼터 드롭 뒤쪽 항목 제거(DSN-12-04), 실패 모드 표.
- 입력: 12 §3·DSN-12-01~06, 11 §5~§10(검사 순서·코드·Ack·Limits), 13 §5·§7.4, 02 DSN-02-12·C-2·C-3·C-4, 06 SEC-11~15, 01 D-13·D-15·N-05·N-06·N-15.
- 수락 기준: T-SV-010(WAL 선기록 후 ack·`kill -9` 복구), T-SV-011(토큰 재삽입 100회 불변), T-SV-012~014(상한 5종·쿼터 구분·사전/인스턴스 상한), T-SV-015(타임스탬프 가드), T-SV-016(에러 지문), T-SV-017(Apdex T), T-SV-020(수집 지연 WS 1초·조회 5초), T-ACC-13(중복 0·누락 0 4시나리오), T-ACC-15, T-SEC-11~15(gzip 폭탄 RSS ≤ 200MB·리플렉션 off·미인증 RPC 거부), 1,000 TPS 5분 적재 지연 p99 ≤ 5초(`argos_ingest_lag_seconds`), `system.query_log`로 insert 설정 확인(T-ST-053).
- 확인 작업: 12 미결 1·11 미결 1(grpc 해제 후 크기 적용 지점·`BoundedInputStream`), 12 미결 9(gRPC health 미인증 범위 → 16 검토).

**WO-22 세션·인스턴스 상태·등록 정책·실시간 버스·WebSocket**
- 목표: 12 §4(SessionRegistry·InstanceStateMachine UP/STALE 60초/DOWN 180초/Bye 유예 5분/WARN 사유(시계·드롭·구버전·모듈 비활성·누수)·전이 이벤트·ClockSync 서버 계산·InstanceNaming·보관 7일/정리 30일·RegistrationPolicy auto-register·승인 대기), §3.7(RealtimeBus Reactor Sinks·토픽 6종·100ms 묶음·느린 구독자 최신 우선 드롭·60초 늦은 행 제외), WebSocketHandler(핸드셰이크 쿠키·Origin·구독 20·메시지 4KB·close 4401/4413·세션 폐기 1초 내 종료)·SubscriptionManager, 세션 스트림 idle 90초(C-19).
- 입력: 12 §3.7·§4·DSN-12-08, 14 §4(WS 프레임 스키마), 11 §9(시계 오프셋), 02 C-8·C-19, 06 SEC-44, 01 F-S03·S28·S30, D-02·D-06·D-18.
- 수락 기준: T-SV-021(느린 구독자 드롭), T-SV-022(상태 전이·이벤트), T-SV-023(Bye 유예·기동 유예), T-SV-043(등록 정책), T-ACC-02(오프셋 WARN), T-ACC-18(수집 중단 `outages[]`), T-API-007(WS 프레임·상한·인증), T-SEC-44.
- 확인 작업: 12 미결 4(Tomcat VT 핀닝, REST·WS 처리).

**WO-23 조회 API(OpenAPI·소스 선택·봉투)**
- 목표: 14 문서 §1~§3·§5~§7의 조회 리소스 전부(전체 현황·대시보드 p99·비교 offset·마커·XLog 컬럼형 JSON+gzip·결정적 샘플링·트랜잭션 검색 필터 전부·상세(`tx_id` 복원·`?ts` 힌트·`profile_status`)·트레이스(24h 기본)·서비스/SQL/외부/에러 통계·상세·교차 뷰·버전 분리·변화율·액티브 스냅샷·인스턴스·메트릭·수집 상태 `outages`·저장소 상태·ID 조회·검색), `SourceSelector`(≤6h raw / ≤7d 1m / 그 이상 1h, 마지막 2분 raw, 분 경계 부분 분 원본)·응답 봉투(`source`·`truncated`·`shown/total`·`sampling`·`retention_exceeded`·`outages`·`measured`)·`QueryLimits`·`ErrorBody`·OpenAPI 생성·커밋(`docs/api/openapi.yaml`)·`x-argos-role`/`x-argos-audit`/`x-argos-pii`/`x-argos-untrusted` 확장·`oasdiff` CI·`argos_reader` 쿼리 설정.
- 입력: 14 전체, 13 §6(엔드포인트↔테이블·대표 SQL), 12 §api, 02 §6.3·§7 봉투·C-5·C-6·C-13·C-14, 01 F-S04·S10, D-03·D-07·D-11·D-18·D-20·D-23, N-07·N-24.
- 수락 기준: T-API-001~006(린트·호환 diff·소스 선택·제한·ID 조회·샘플링), T-API-010 골격, T-ACC-03(분 경계 일치 API 계층), T-ACC-07(`profile_status`), T-ACC-09(결측 null), T-ACC-18, T-ST-053, N-07·N-24 측정(30일 합성 데이터, `docs/bench/gate1.md` 입력), `masked_by_role` 강등(C-13) 계약 테스트.
- 확인 작업: 14 §11 미결 10건(해당 항목을 "확인 작업"으로 수행하고 결과 기록).

**WO-24 인증·세션·CSRF·설정 토큰·RBAC·레이트 리밋**
- 목표: 12 §7.2~7.4·16 §7(LoginService·SessionCookieService httpOnly·Secure·SameSite=Strict `argos_at/argos_rt`·EdDSA JWT 15분·RefreshRotation 재사용 탐지 → 전체 무효화·`user_sessions` 5초 캐시·즉시 무효화 훅 5종·동시 세션 5·kiosk 긴 세션 조건·CsrfFilter 더블 서브밋 + Origin·세션 고정 방지·SetupTokenBootstrap(32B·로그·`conf/setup-token` 0600·30분·`ARGOS_ADMIN_PASSWORD_FILE`)·PasswordPolicy(12자·금지 목록·bcrypt 12·잠금 10회 15분 누진·강제 변경)·`@RequiresRole` → `x-argos-role` 기동 시 전수 검사·`RoleMatrix`(16 권한 매트릭스 전표·`pii.view`)·객체 수준 권한(명령 결과 요청자·관리자, UUIDv4)·레이트 리밋 5버킷(로그인 IP 20/분·API 50/초·검색/CSV 10/분·429 `Retry-After`)·Actuator 노출·보안 헤더·CSP nonce 주입(index.html).
- 입력: 12 §7.2~7.4·DSN-12-10·11, 16 §7·§9.5(헤더·CSP 값), 14 인증 엔드포인트, 06 SEC-40~50·55·56·45·46, 01 F-S05.
- 수락 기준: T-SV-048(부트스트랩·비밀번호·잠금), T-SEC-40~44(Playwright 포함 → WO-30과 공동), T-SEC-45·46(헤더·CSP 위반 0), T-SEC-47~50(토큰 없이 403·재사용 403·관리자 존재 404·경계·누진·cost·권한 매트릭스 생성 실행·객체 권한), T-SEC-55·56, T-API-001·010(권한 표기 전수·매트릭스 생성).
- 확인 작업: 12 미결 5(EdDSA 라이브러리 허용 목록), 16 §15 미결 중 세션·CSP 항목.

**WO-25 알림 엔진·채널·템플릿**
- 목표: 12 §5(RuleModel 전체 필드·`alert_rules(spec jsonb)`·revisions·`MetricProvider` 4종(15분 HdrHistogram 윈도·ClickHouse 1m·레지스트리·자기)·지표 목록 F-S31 전부·10초 평가·상태 머신 OK/PENDING/FIRING/ACKED/RESOLVED/NO_DATA·복구 임계·최소 표본·평가 단위·기동 유예 3분·2단계·재알림·그룹핑·음소거(`mutes`)·ACK·`Notifier`·채널 Email(STARTTLS)·Slack·Webhook(HMAC 서명·`webhook_signing_keys`)·`DestinationValidator`(SSRF 7단계·해석 IP 고정·리다이렉트 금지·allow-cidrs)·재시도 3회·실패 자기 알림·`notification_deliveries`·`TemplateRenderer` 허용 변수만·`DefaultTemplates` 14종·앱 등록 시 적용·차이 표시).
- 입력: 12 §5·DSN-12-07·12, 16 §8(마스터 키 봉투·서명), 14 알림 엔드포인트·웹훅 페이로드 `schema_version`, 02 A-15, 06 SEC-39·51~54·52, 01 F-S06·S17·S24~S27·S31, 부록 A, N-23.
- 수락 기준: T-SV-030(≤ 30초), T-SV-032(상태 머신 전부), T-SV-033(템플릿 14종·권장 적용·차이), T-SV-034(재시도·자기 알림), T-SV-035(목적지 검증·서명·STARTTLS), T-SV-036(지표 전부), T-SEC-39·51~54, T-API-008(페이로드·변수 집합), 저트래픽 비발화(최소 표본) 단위 테스트, S-17 자동 부분(T-SV-023 연동).
- 확인 작업: 12 미결 3(HdrHistogram 메모리 상한), 7(알림 백분위 표기).

**WO-26 토큰 서비스·명령 라우팅·설정 변경 통제·킬 스위치**
- 목표: 12 §7.1(TokenService: `argos_` + base64url 32B·SHA-256 해시·앞 8자·앱당 5개·만료·회전 유예 7일·폐기 → 세션 `UNAUTHENTICATED` 즉시 종료·토큰별 레이트 리밋·IP 인증 실패 차단), §6.1(CommandRouter: UUID·TTL 60초·인스턴스당 동시 1·분당 10·권한 분리(운영자: 덤프·스택·자가 진단 / 관리자: 환경 덤프·리로드·설정·킬 스위치)·결과 봉투 암호화·보존 30일·`commands`·`command_results`), §6.2(ConfigChangeService: 사유 필수·diff·대상 인스턴스 `remote_config_allow` 교집합 선검증·`config_changes`·`config_change_results`·되돌리기·유효 설정·drift), KillSwitchService, ApprovalQueue(승인 대기 → 앱 생성·토큰 발급).
- 입력: 12 §6·§7.1·DSN-12-09, 16 §2·§5, 11 Command/ConfigPush, 14 관리 엔드포인트(202 + UUID 비동기), 06 SEC-01~05·21~24·49, 01 F-S07·S29·S30, N-25.
- 수락 기준: T-SV-040(토큰 수명주기), T-SV-041(명령 TTL·동시·권한·암호화·UUID), T-SV-042(설정 변경 통제 전부), T-SV-031(50 인스턴스 ≤ 10초), T-SEC-01~05·21~24, 에이전트(WO-17) 연동 왕복 테스트(스레드 덤프·거부 키).
- 확인 작업: 16 §15 미결 중 토큰·명령 항목.

**WO-27 전수 통계·롤업·야간 정합·재구축·에러 지문·Apdex**
- 목표: 12 §3.6(`sql_stat_raw`·`ext_stat_raw` 적재), 13 §4 롤업·MV 전부(`rollups/*.sql` 공용·1h 캐스케이드·`instance_stat_1m`·백분위 상태 함수 A-11 확정값·버킷 p95·메트릭 롤업 sample_count/increase·Apdex 집계), 12 §9.1(ReconcileJob 야간 D-03·D-12 전수 비교·`reconcile_results`·자동 재구축, RollupRebuilder `argos-admin rollup rebuild --date`·`REPLACE PARTITION`·바이트 동일성), ErrorGroupFingerprinter 확정(마스킹 후 클래스+메시지 패턴+최상위 앱 프레임·`error_groups` 대표 스택), ApdexStamper·`service_apdex_overrides`·T 변경 이후 적용·소급 옵션.
- 입력: 12 §3.5·3.6·§9.1, 13 §4·§6.3(Q7·Q8·Q9·Q12·Q13), 02 DSN-02-08·13·C-14, 01 F-S16·S19·S21·S22, D-03·D-12·D-14·D-17.
- 수락 기준: T-SV-016~019, T-ACC-03(전체 규모는 storageBench), T-ACC-12, T-ACC-14(재구축 바이트 동일·불일치 주입 탐지), T-ACC-17(Apdex T), T-ST-030~033(CI 1/100 정합 0), T-ST-042(백분위 값 오차 1m·1h·1d).
- 확인 작업: 13 §17 중 집계 상태·`REPLACE PARTITION` 항목.

**WO-28 감사·비밀값 암호화·삭제 API·저장소 상태·자기 메트릭·`argos-admin`**
- 목표: 12 §8(AuditService 이벤트 카탈로그 enum 전부(16 §11 ~60종)·레코드 필드·`AuditSink` PG/syslog/JSONL·불변성 DB 권한(C-11)·보존 1년, `MasterKey`·`EnvelopeCipher` AES-256-GCM·키 교체 재암호화·`SecretField`, `DeletionJobService` 뮤테이션·`privacy_jobs`·`system.mutations` 폴링·감사), §9.2~9.5(StorageHealth 지표·EmergencyRetention 80/90/95%·DROP 순서·SelfMetrics `argos_*` 전부 + `metric` `app_id=0` 기록·`/actuator/prometheus` 인증·CertReloader·만료 메트릭·DiagnosticBundle 마스킹), `cli/` `argos-admin` 서브커맨드 전부(17 §4.4 표).
- 입력: 12 §8·§9·§cli·DSN-12-13·14, 16 §8.2·§10·§11, 13 §8·§9·§12(audit_logs·privacy_jobs), 17 §4.4·§9.2·§9.3·§11, 06 SEC-22·33·52·80~84·09, 01 F-S08·S10·S20·S23·S32.
- 수락 기준: T-SV-044(카탈로그 스냅샷·불변·syslog), T-SV-045(삭제 API·상태), T-SV-046(저장소 지표·90% 비상 단축), T-SV-047(Prometheus 노출·인증), T-SEC-22(명령 결과 평문 부재), T-SEC-52(채널 비밀값 평문 부재·마스킹·키 교체), T-SEC-80~82, T-SEC-09(인증서 만료 메트릭·리로드), T-ST-054(뮤테이션 소요), `argos-admin` 서브커맨드별 통합 테스트(`--json`), 진단 번들 토큰·비밀값 grep 0(T-SEC-06 서버 부분).
- 확인 작업: 12 미결 6(`system.mutations`), 17 미결 10(DROP 단위·속도).

**WO-29 서버 부하 테스트·메모리 예산 실측**
- 목표: 18 §8 `serverLoadTest`(가짜 에이전트 N개, 5,000 TPS + 50,000 step/s + 10,000 stat/s 1시간, 공격 시나리오 5종), 12 §11 스레드·메모리 예산(JVM 4g + 오프힙 1g) 실측·RSS, 대시보드 10 사용자 5초 폴링 동시, 적재 지연·parts·DelayedInserts·메모리 피크 측정, 결과 `docs/bench/server-load.md`.
- 입력: 18 §8, 12 §11, 13 §7.1·7.5, 17 §7.1·7.3(RAM 비율), 01 N-06·N-18.
- 수락 기준: T-NFR-06(1시간 지속·p99 5초·쿼리 p95 2초·parts < 300·DelayedInserts 0), T-NFR-18(ClickHouse 설정 단언·OOM 0), T-SEC-11~15 전체 규모 재실행, RSS 실측으로 17 §7.3 비율 조정 제안.
- 확인 작업: 12 미결 3·4, 17 미결 9(메모리 비율).

### Epic C: UI (15 문서)

**WO-30 UI 골격**
- 목표: 15 §2(스택·폴더), §3(라우트 트리 전부·Shell·AppLayout·GlobalLayout·KioskLayout·상단 바·좌측 메뉴), §4(URL 스키마 zod·1KB·복원 실패 기본값·딥링크 5항 구현), §5(시간 범위 모델·단축키), §6 공통 컴포넌트 전부(TimeSeriesChart uPlot 래퍼 + 플러그인 markers/gapShading/baseline/clickMenu/cursorSync, StatTile, DataTable 가상 스크롤·값 클릭 필터·변화율·CSV 요청, StatusBadge 4색, TimeRangePicker, FilterChipBar, KindFilter, EnvSelect, AppSelect, SafeText/SafeLink/CodeBlock, EmptyState/ErrorState 문구 규칙), §10 WsManager(단일 연결·멀티플렉스·백오프·visibilitychange·"갱신 중단 N초"·세션 만료), §11(쿠키 세션·CSRF 헤더·401 → `?next=` 복귀·역할별 노출·`Untrusted<string>` 브랜드·린트), §12 i18n(en 원본·ko·부록 B 용어표·포맷), §13 토큰·레이아웃·접근성, 생성 API 클라이언트(`api/generated/` diff 0), 로그인·설치 마법사·내 계정(§9.11), E2E 프레임(18 §13: fixtures auth/seedData/ws, `required/<screen>.yaml` 데이터 주도, XSS·CSP·콘솔 훅, 샤딩, 스크린샷 정책).
- 입력: 15 §2~§6·§10~§13·§15, 05 §2.0, 14 §6(딥링크 매핑)·§4(WS), 16 §9(렌더링·CSP), 18 §13, 06 SEC-40·43·44·46·60·61·64, 01 F-U13·U14·U16.
- 수락 기준: T-UI-002(시간 범위 리듀서), T-UI-003(URL 직렬화·검증·1KB), T-UI-004(표 공통), T-UI-005(XSS → 텍스트), T-UI-006(하드코딩 0·en 누락 0·ko 용어표), T-UI-007(결측 표시), T-UI-008(상태 색·알림 라벨 ko), T-E2E 로그인·딥링크 5항·시간 범위 전역·표 필터 칩·끊김 배지·로그아웃 WS 종료·en/ko 전환·1366 가로 스크롤 없음, T-SEC-40~44 브라우저 부분, T-SEC-46 CSP 위반 0, T-SEC-60·61·64, `check-no-external` 0, Lighthouse 초기 로드 ≤ 2초 골격(T-NFR-14).
- 확인 작업: 15 §18 미결 10건(AntD `csp.nonce` `style-src` 커버리지 등).

**WO-31 전체 현황** — 15 §9.1·05 §2.10 필수 요소 전부(환경·그룹 필터, 카드/표, 앱 행 항목 11종, 정렬·이상 앱 고정, 전 앱 FIRING 패널, 서버 자기 상태, 클릭 → 대시보드 시간 유지, 1366에서 30개 스크롤 없음). 입력: 14 `/overview`, 12 DSN-12-14. 수락: T-E2E overview(1366·30개), 필수 요소 체크표 전수, T-ACPT-16 자동 부분.

**WO-32 앱 대시보드 + 인스턴스 비교 표** — 15 §9.2·05 §2.3 전부(요약 타일 증감 기준 선택, 시계열 12종, 커서 동기화·드래그 확대·비교 기준선 토글·이벤트 마커·`outages` 음영, 인스턴스 타일 상태 색, 인스턴스 비교 표 이상치 강조, 발화 중 알림 패널, 수집 상태 패널 7항, 실시간 5초). 입력: 14 대시보드·인스턴스 통계(`instance_stat_1m`), 01 F-U01·U20·U19 토글 자리. 수락: T-E2E dashboard, 필수 요소 전수, T-ACC-18(음영 표시), T-NFR-14 초기 로드.

**WO-33 XLog + 트랜잭션 목록** — WO-04 캔버스를 API 연동(15 §9.3·05 §2.1 전부: 색 기준 전환, 선형·로그, 임계선, 드래그 → 선택 요약 패널 → 목록, 실시간 5분 창·일시정지·공백 보충, 샘플링 표기 "표시 N / 전체 M", 필터 즉시 반영, 종류=배치 y축 단위, 목록 필수 열 13종·열 선택·정렬·CSV 요청, 키보드 이동·상세, 음영·마커). 입력: 14 XLog·검색 API, 13 Q3·Q4. 수락: T-UI-001, T-E2E xlog→summary→list, 필수 요소 전수, T-API-006 표시, T-NFR-14 10만 점 재확인.

**WO-34 트랜잭션 상세 + 트레이스 뷰** — 15 §8·§9.4·§9.5, 05 §2.2·§2.8 전부(상단 요약 18항, 백분위 위치·정상 요청 나란히 보기, 타임라인 self time 회색·20% 강조·스택 아이콘, 호출 트리 반복 접기 run ≥ 3·청크 요약, SQL 탭 대기/실행 분리·통계 이동, 외부 탭 실패 분류·트레이스 이동, 에러 탭 앱 프레임 강조·그룹 이동, STACK 뷰 공통 프레임, 미수집 사유 D-07, 호출자 이동, 로그 열기(F-U18 자리), JSON 내보내기, URL 복사; 트레이스 트리+타임라인·네트워크/대기·누락 노드 3종 구분·임계 경로·노드 클릭·앱 대시보드 점프·trace id 복사). 입력: 14 상세·트레이스 API, 02 C-13(`masked_by_role`). 수락: T-E2E detail/trace, 필수 요소 전수, T-ACC-05·07 표시, T-UI-007.

**WO-35 액티브 트랜잭션** — 15 §9.6·05 §2.6 전부(인스턴스별 막대, 경과 색 4단계 설정, 목록 열 9종, 5초 "N초 전 기준", 일시정지·필터, 행 동작 3종(단일 스레드 스택·덤프·최근 완료), 상단 요약·규칙 링크, 끊김 배지). 입력: 14 액티브·명령 API. 수락: T-E2E active(끊김·재접속 포함), 필수 요소 전수, T-ACPT-02 자동 부분.

**WO-36 통계 4종** — 15 §9.7·05 §2.5 전부(공통 표 값 클릭·변화율·종류·환경, 서비스 상세 교차 뷰·버전 분리·인스턴스 분포, SQL 상세 실행 서비스·인스턴스·DB별·커넥션 대기·원문, 외부 호출 통계 호스트별 타임아웃·연결 실패·추이·호출 서비스·관련 트랜잭션, 에러 분석 신규 필터·대표 스택 앱 프레임·버전·인스턴스 분포·최근 10건, 버킷 p95 `estimated` 표기 C-14). 입력: 14 통계 API, 27 롤업. 수락: T-E2E stats 4종·service-version·errors, 필수 요소 전수, T-ACPT-03·04·11·13 자동 부분.

**WO-37 인스턴스 상세·스레드 덤프 뷰어** — 15 §9.8·05 §2.7 전부(상단 메타 15항 + IP 모드·VT 모드, 차트 10종 커서 동기화·마커·기준선, 환경 탭 마스킹 환경변수(관리자)·유효 설정 출처·원격 허용 키·모듈 상태·자가 진단, 명령 탭 역할별 버튼·이력·감사, 덤프 뷰어 상태별 그룹·동일 스택 묶기·데드락·VT 구분·검색·트랜잭션 매칭·다운로드·최근 20·잘림, 링크 4종). 입력: 14 인스턴스·메트릭·명령 API, 12 §6.1. 수락: T-E2E instance/dumps(명령 왕복), 필수 요소 전수, T-ACPT-06·07·14 자동 부분.

**WO-38 알림 화면** — 15 §9.9·05 §2.4·§2.9 전부(규칙 편집 필드 전부·7일 미리보기·템플릿에서 만들기·차이 표시·복제·비활성·이력, 채널 설정 목적지 검증 결과·마스킹·테스트 전송, 이력 상단 고정 FIRING/ACKED·채널 상태·열 10종·필터·행 동작 4종(딥링크·편집·ACK·일시 중지)·알림 통계, 전 앱 보기). 입력: 14 알림 API, 12 §5. 수락: T-E2E alerts(규칙 → 발화 → 이력 → ACK), 필수 요소 전수, T-ACPT-15·17 자동 부분.

**WO-39 관리·검색·설치 마법사·내 계정** — 15 §9.10~9.12·05 §2.11 전부(앱 목록 8열, 인스턴스 목록 9열·보관, 승인 대기, 토큰 발급 1회 표시·폐기·회전·만료 경고, 설정 변경 사유·diff·대상·인스턴스별 결과·이력·되돌리기, 킬 스위치 확인 대화상자, 설치 안내 복사 스니펫·카나리 체크리스트, 수집 상태 순위, 개인정보 설정 현황·삭제 요청 실행·상태, 사용자·역할, 감사 로그 조회·내보내기, 오픈소스 라이선스·버전 정보; `/search` ID 조회·텍스트; `/setup` 설정 토큰·IP 모드 강제·관리자 생성; `/account`). 입력: 14 관리·인증 API, 16 권한 매트릭스. 수락: T-E2E admin(토큰 1회·diff·되돌리기·킬 스위치·승인), T-E2E search, T-SEC-47 UI 부분, 역할별 메뉴 노출 매트릭스 E2E, 필수 요소 전수, T-ACPT-07·12·18 자동 부분.

### Epic D: 배포·운영 (17 문서)

**WO-40 Docker Compose·env-init·smoke·설치 가이드** — 17 §3 전부(`compose.yaml` 3서비스·네트워크 internal/front·DB 포트 비노출·볼륨·healthcheck·기동 순서·이미지 다이제스트·multi-arch, `.env.example` 값 비움, `argos-env-init.sh` CSPRNG 자격증명·마스터 키·0600·금지 목록, `argos-compose.sh` 래퍼, `config/*` 템플릿(13 config.d/users.d RAM 치환·PG init·demo 에이전트 yml), `smoke.sh`(포트 집합 {8080, 9100}·`.env` 권한·health·설정 토큰 접근 제어), 리버스 프록시 오버레이 개요), 설치 가이드(en 초안 + ko), 설정 토큰 운영 절차. 수락: T-SEC-74·75(`.env`·금지 비밀번호·포트 집합), T-SEC-47 설치 시나리오, `smoke.sh` 통과, 수동: 비참여자 30분 설치(N-11, T-NFR-12 전 단계), 17 미결 2·3·6·12 확인.

**WO-41 데모 프로파일** — 17 §3.9·O-A01(sample-app-a/b 에이전트 부착·load-generator·60초 내 XLog·데모 트래픽 느림·에러·N+1·분산·배치·데모 배너), 데모 이미지 2종(jib), 60초 측정 스크립트. 수락: T-NFR-16 스크립트(60초), 수동: 비참여자 2인 README만 보고 5분(기록은 Gate 1.5).

**WO-42 도그푸딩 배포·운영 체크리스트** — 사내 시스템 2개에 에이전트 적용(01 §9.5 절차: `--check` → 등록·토큰 → 카나리 1대 → 비교 표 → 확대), 운영 체크리스트·문제 기록 템플릿, 기록은 **비공개 저장소**, 공개는 `docs/field-reports/` 익명화 요약. 수락: T-ACPT-07·08 사람 수행, 2주 연속 운영 기록(Gate 1), 야간 정합 검사 불일치 0 기록.

**WO-43 운영 스크립트** — 17 §4.4 `backup.sh`(pg_dump 필수·0600·age/GPG·7일·CH 증분 외부 대상 거부 규칙), `restore.sh`(마스터 키 검증·migrate 전진·정합 트리거), `diag-bundle.sh`(마스킹), `argos-compose.sh upgrade/backup/diag`(Compose용), 백업 라운드트립·시점 불일치 테스트, 복구 리허설 문서 초안. 수락: 17 §14 백업 라운드트립·WAL 복구 자동 테스트, T-SEC-84(산출물 권한), T-SEC-06 번들 grep 0, 17 미결 8 확인.

### Epic E: 릴리스·프로젝트 (19 문서)

**WO-44 릴리스 파이프라인 0.x** — 19 §7(`release.yml` 태그 트리거: 게이트 → 빌드 → 재현성 2회 빌드 해시 비교 → SBOM(CycloneDX 3종, 에이전트 셰이딩 전) → GPG 분리 서명(조직 키·`keys/ARGOS-RELEASE-KEY.asc`·지문) + cosign keyless + attestation → `SHA256SUMS` → GitHub Release 초안(pre-release) → GHCR multi-arch(server·demo 2종)), `deploy/verify-release.sh`, `argos-deps.txt`·NOTICE·THIRD-PARTY 생성 트랜스포머, 버전 노출(`--version`·`/actuator/info`·배너), CHANGELOG → 릴리스 노트 생성. 수락: T-BLD-003·005·008·009·012, `verify-release.sh` 통과, 0.1.0 pre-release 발행 리허설(태그는 사람이 밈), 19 미결 4·6 확정 반영.

**WO-45 프로젝트 파일 세트·거버넌스·compatibility·dev-setup** — 19 §8 전부 본문(CONTRIBUTING: 빌드·DCO·규약·DoD 축약·모듈 추가 가이드·차용 규칙·AI 코드 정책·`needs-matrix`·응답 7일; CODE_OF_CONDUCT; GOVERNANCE; ROADMAP(01 §11 요약·마일스톤); RELEASING(19 §7.11); SECURITY 초안(16 §12 전문); Discussions 카테고리; 브랜치 보호 설정 스크립트; 라벨 생성), `docs/compatibility.md`(19 §9: 공개 계약 O-R03·폐기·에이전트↔서버 범위·스키마 전진), `docs/dev-setup.md` 완성, `docs/README.md` 영문 1페이지 요약. 수락: 파일 존재·항목 충족(수동), DCO·CODEOWNERS·labeler 동작(자동), 설정 키 스냅샷 테스트(T-API-009) 기준 파일 생성.

### Epic F: 보안 (16 문서)

**WO-46 보안 테스트 스위트·PII/XSS 시드·위협 모델 초안** — 18 §12 `securityTest` 전 항목 구현(SEC 자동 M 단계 전부: 권한 매트릭스 생성 실행기(OpenAPI → 3역할 × 엔드포인트), 토큰 수명주기, 상한·레이트 리밋, SSRF 벡터 10종(ext-stub), XSS 전역 훅·페이로드 10종, 하드 캡, 환경 덤프 마스킹, PII 시드 grep(ClickHouse `system.columns` String 전수·PG 덤프·로그·알림 Mock·CSV), 세션·CSRF·WS, 헤더·CSP), `docs/security/threat-model.md` 초안(16 §1 그대로), `docs/security/asvs-checklist.md` 골격(V2·V3·V4·V5·V7·V9·V14 항목 → 설계 절·테스트 id), `cve-exceptions.md` 형식, CI 잡 `security`. 수락: T-SEC-93(스위트 분리 실행), 06 단계 M의 SEC 전부 녹색(Gate 1 행), T-NFR-10c(PII 시드 0건). 확인: 16 §15 미결 중 M 단계 항목.

**WO-47 공급망 CI** — 19 §6.1·6.6, 16 §10: SBOM 생성·Trivy sbom 스캔(Critical·High 0, 예외 파일 대조)·gitleaks(전체 이력 주간)·CodeQL Java/TS·Dependabot 그룹(보안 즉시)·`needs-matrix` labeler·러너 격리(포크 PR GitHub-hosted만·`pull_request_target` 금지·self-hosted 라벨은 main/release push·dispatch만)·Actions SHA 고정·검증 메타데이터 강제. 수락: T-BLD-007·008·009, T-SEC-70·71·73, 포크 PR 시뮬레이션에서 self-hosted 잡 미실행 확인(워크플로 단위 테스트), 19 미결 10 확인.

### Epic G: 테스트 (18 문서)

**WO-50 호환성 매트릭스 러너(nightly)** — 18 §6(조합표 전부 실행 설계·`-Pmatrix.combo`·컨테이너 이미지·JDK 5종·Boot 3종·WAR 2종·VT on/off·JDK 25 deny·JDBC 9종(사내 상용 DB 환경변수·미설정 skip "미검증")·공존 JaCoCo/OTel/devtools·재배포 50회), `nightly.yml`(self-hosted `argos-matrix`, main·release push·dispatch만), 호환성 표 자동 생성(검증/미검증/커뮤니티 검증). 수락: T-MX 전 조합 녹색 또는 명시적 skip, T-AG-015·031·053·054·070 매트릭스 계층, 보고서 `docs/compatibility-matrix.md` 생성, 19 미결 10·11 확인.

**WO-51 오버헤드 벤치 하네스 정식·주간 보고서** — WO-05 벤치 러너 완성(7.3 11항 전부 자동, no-op/grpc 익스포터 분리, 컨테이너 `--cpus=2`, VT 변형, heavy 앱 기동, CDS on/off, OTel 참조 측정), `bench.yml`(주 1회·`argos-bench` 2대·`docs/bench/<date>-<sha>.md` PR 생성), 회귀 판정. 수락: T-NFR-01~04·26~30 Gate 1 보고서 생성, 회귀 판정 단위 테스트, 18 미결(잡음 임계) 확정.

**WO-52 카오스·72시간 soak** — 18 §11(`chaosTest` toxiproxy/tc netem 시나리오 9종: 서버 중단 1시간·지연 500ms·손실 10%·5xx·디스크 풀·DNS 실패·TLS 불일치·instance_id 재부여·CH 30분 중단; 판정 p99 ≤ 잡음·복구 1분; `soakTest` 72시간: 힙 기울기 ≤ 0·NMT ≤ 16MB·FD·스레드 평탄·parts·OOM 0). 수락: T-CH-001~009, T-SOAK-001, T-NFR-05·18, 보고서 `docs/bench/soak-<date>.md`.

**WO-53 추적성 보고서·테스트 카탈로그 동기화** — `@Tag("req:…")`·Playwright annotations → `build/reports/traceability.md`, 18 §16 대응표와 diff 시 실패, 부록 A 카탈로그 갱신(세분 ID 접미사), 03 Gate 표 행별 증빙 테스트 ID 매핑 문서(`docs/gates/gate1-evidence.md`). 수락: 추적성 CI 잡 녹색, D-01~D-26·N-xx·SEC M 전부 대응 테스트 존재(누락 0).

### Gate 1 판정

| Gate 1 항목(03 §4) | 증빙 WO |
|---|---|
| 기능 M 전부 | WO-10~WO-19, WO-20~WO-28, WO-30~WO-39 상태 DONE |
| 보안(MVP) | WO-24·26·28·46·47(SEC M 전부, 위협 모델 초안, 권한 매트릭스 녹색), WO-40(기본 자격증명 없음·포트) |
| 정확성 D-01~D-26 | WO-10·12~18·21·22·27·23(T-ACC-01~26), WO-27 야간 정합 0(WO-42 기록) |
| 매트릭스 | WO-50(유효 조합표·VT·JDBC 5종) |
| NFR | WO-51(N-01~04·26~30), WO-29(N-06·18), WO-23(N-07·24), WO-21(N-15), WO-25(N-23), WO-26(N-25) → `docs/bench/gate1.md`(WO-53 취합) |
| 도그푸딩 | WO-42(2개 시스템 2주, 비공개 기록·익명화 요약) |
| 장시간·카오스 | WO-52 |
| 설치 | WO-40(비참여자 Compose 설치) |
| 고지 | WO-44(NOTICE·THIRD-PARTY·deps.txt·SBOM 동봉, T-BLD-003) |
| 릴리스 | WO-44(0.x pre-release 파이프라인) |
| 수락 | T-ACPT-01~18 중 M 범위를 사람이 수행(WO-42 환경) |

---

## 4. Phase 2a: 공개 베타 준비 → `1.0.0-beta.1`

Gate 1 통과 후 Phase 0 수준으로 보강해 지시한다.

| WO | 목표 | 입력 | Gate 1.5 연결 |
|---|---|---|---|
| WO-60 | 영어 UI 완성·ko 완역·부록 B 용어표 적용, 로그·에러 메시지 영어(F-U13 M, O-A05) | 15 §12, 01 부록 B | 기능(F-U13) |
| WO-61 | 영문 사용자 문서 세트: README 스크린샷 3장·빠른 시작·Compose 설치·에이전트/서버 설정 레퍼런스·호환성 표(검증·미검증·커뮤니티)·트러블슈팅 초안·`docs/data-collected.md`(16 §6 생성 규약)·CHANGELOG·`docs/compatibility.md`(0.x → 1.0 예고)·`docs/privacy.md`, ko 번역 | 01 §9.4, O-A02·A04·P04 | 문서(영문) |
| WO-62 | `SECURITY.md` 확정·신고 채널 동작(자가 신고 1회)·위협 모델 문서 갱신·CodeQL·비밀값 스캔·Dependabot 운영 확인·self-hosted 러너 격리 검토 보고 | 16 §12, O-S01~S05 | 보안 |
| WO-63 | 릴리스 검증 완성: GPG·cosign·attestation·SBOM·`verify-release.sh` 문서 절차 검증, 에어갭 번들 초안(17 §5 내용물·반입 검증) | 19 §7, 17 §5 | 릴리스 |
| WO-64 | no phone-home 자동 검증: 에어갭 격리망 패킷 캡처 egress 0(17 §5.3), UI 번들 외부 리소스 0 재확인, README·docs 선언 | O-P01~P03, N-17 | 프라이버시 |
| WO-65 | 동적 설정 UI 편집 폼(F-A13 S), 감사 로그 조회·CSV 내보내기·민감 열람 감사(F-S08 S, SEC-80 G2 범위·83) | 15 §9.10, 16 §11 | 기능(F-A13·F-S08) |
| WO-66 | 사내 실환경 3개 이상 확대 운영, 익명화 요약 갱신, 알려진 결함 목록 | 01 §12 | 품질 |
| WO-67 | 공개 베타 운영 도구: 라벨 자동화, 트리아지 체크리스트, 호환성 보고 → 호환성 표 반영 절차, Claude Code 1차 리뷰 프롬프트(O-Q04), Discussions 개설 | O-G04~G06·G09, O-Q04·Q07 | 프로젝트 파일 |

Gate 1.5 판정: 03 §4 표의 기능·첫 5분(WO-41 + 비참여자 2인 5분 측정)·문서·프로젝트 파일(WO-45·67)·보안(WO-62·47)·릴리스(WO-63)·프라이버시(WO-64)·품질(WO-66)·호환성(WO-61 compatibility) 행을 사람이 채운다. 통과 시 `1.0.0-beta.1` 공개 릴리스.

## 5. Phase 2b: 공개 베타 운영 → `1.0.0-rc.1` → `1.0.0`

| WO | 목표 | 입력 | Gate 2 연결 |
|---|---|---|---|
| WO-70 | 애플리케이션 맵(F-U11): 앱·DB·외부 호스트 노드, 호출수·에러율, 시간 범위 | 15, 14, 13(caller_app_id·target·host 집계) | 기능 S |
| WO-71 | 바인드 파라미터 모드 `partial`/`full`·컬럼 화이트리스트·`pii.view` 열람·`*_enc` 봉투 암호화 컬럼·UI 마스킹 표시(F-A04, SEC-3A, DSN-16-09) | 10 §6.3, 13, 16 §6 | 기능 S·보안 G2 |
| WO-72 | 시스템 메트릭 보강(F-A11 S): cgroup 매트릭스, Windows 1.x 자리 문서화 | 10 §11 | 기능 S |
| WO-73 | 커스텀 메서드 계측 설정 파일 패턴(F-A14 S), `--check` 호환성 표 대조 범위 확장 | 10 §6.10·§13.3 | 기능 S |
| WO-74 | 알림 b: 활성 시간대·반복 음소거 일정·규칙 복제·복합 조건·그룹핑(F-S17 S·F-S25b), ACK 화면(F-S26), Apdex T 설정 UI·`service_apdex_overrides`(F-S16) | 12 §5, 15 §9.9 | 기능 S |
| WO-75 | 비교 기준선(F-U19)·외부 로그 링크 템플릿(F-U18)·상황판 모드(F-U21, SEC-42 조건)·배포 이벤트 API·자동 마커 완성(F-S18) | 15 §6.1·§3.4, 14 `/events` | 기능 S |
| WO-76 | tar + systemd 설치(17 §4: 레이아웃·유닛·`install.sh`·`upgrade.sh`·`restore.sh`)·에어갭 번들 완성(DB 패키지 동봉 범위)·리버스 프록시 가이드·`:deploy:tarDist`·`airgapBundle` 태스크 | 17 §4·§5, 19 §3 | 설치 |
| WO-77 | 백업·복구 리허설 1회(N-20)·업그레이드 경로 테스트 `beta.1 → beta.2 → rc → GA`(자동 + 사람 1회)·지원 중 모든 이전 마이너 → 현재 마이그레이션 테스트 | 17 §8·§10, 13 §13 | 설치 |
| WO-78 | Maven Central 발행(`argos-agent`, GPG, Central Portal)·문서 사이트(MkDocs Material, i18n en/ko, 버전 선택)·`docs.yml` | 19 §7.5, O-A03 | 릴리스·문서 |
| WO-79 | 호환성 약속·지원 버전·폐기 정책 확정(`docs/compatibility.md` 확정, O-R01~R04·R09), 공개 계약 CI 고정(OpenAPI diff 게이트·설정 키 스냅샷·메트릭 이름·웹훅 페이로드·WS 프레임 스냅샷) | 19 §9, 14 §1 | 호환성 약속 |
| WO-80 | 보안 G2 묶음: SEC-03·05·09·12~14·22·24·33·36·37·38·3A·45·46·53·54·56·63·64·72·73·76·82~84·90~93 구현·검증, ASVS L2 체크리스트 완성, ZAP 인증 상태 활성 스캔 + API 스캔(Critical·High 0, Medium 사유 `docs/security/scan-<version>.md`), WSTG 침투 체크리스트 사람 1회, 보안 릴리스 모의 훈련 1회(가상 취약점 → 패치 → 지원 마이너 배포 → GHSA 초안, 소요 기록), `cve-exceptions.md` 승인 흐름 | 16 전체, 06 G2 열, 18 §12 | 보안 |
| WO-81 | 개인정보 삭제 API UI·작업 상태(F-S32), `docs/data-collected.md` 자동 생성(T-SEC-30 diff 테스트), 데이터 분류표 ↔ proto·DDL 필드 diff CI | 16 §6, 02 §5.2 | 프라이버시·정확성 |
| WO-82 | OpenSSF Best Practices Badge "passing" 체크리스트 전 항목 충족 또는 사유 문서화, GitHub 조직 소유자 2인·2FA·ADR 전부 공개 확인 | 01 §10, O-G08 | 외부 벤치마크·거버넌스 |
| WO-83 | 데모 영상(2분)·스크린샷 갱신(O-A07·A02), 릴리스 노트·Upgrade notes 1.0, `RELEASING.md` 대로 rc → GA 리허설 | 19 §7.7·7.11 | 문서·릴리스 |

Gate 2 판정: 03 §4 Gate 2 표(기능 M+S·05 동등 항목, 공개 베타 6주·베타 2회·외부 보고 3건, 품질 4주, 정확성 4주 정합 기록, 매트릭스 "지원" 열 전부, 성능 N-01~N-30 `docs/bench/gate2.md`, 보안 전 항목, 설치 3형태·비참여자 2인(외부 1인)·업그레이드·백업 리허설, 릴리스 Central·재현성, 호환성 약속 CI 고정, 문서 전 범위·사이트·영상, 거버넌스, 프라이버시 O-P01~04, UI 전 화면 E2E·필수 요소, OpenSSF passing)를 사람이 채운다. 통과 시 `1.0.0`.

## 6. Phase 3: 1.x (지속)

마이너 분기 1회, 패치 수시(O-R09). 01 문서 6.2·6.3 항목을 외부 피드백·호환성 보고 순서로 WO화한다. 설계 문서의 "1.x 자리"(10 Reactor Context·메시지 컨슈머·디스크 스풀, 12 역할 분리·내부 버스, 13 Replicated 전환·Distributed, 15 사용자 정의 대시보드·다크 모드, 16 mTLS·세분 권한·SSO, 17 Helm, 19 PGP 의존성 검증·비트 단위 재현)을 출발점으로 쓴다.
