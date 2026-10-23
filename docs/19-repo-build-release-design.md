# Argos 저장소·빌드·릴리스 설계

| 항목 | 내용 |
|---|---|
| 문서 번호 | 19 |
| 문서 버전 | 0.1 (초안) |
| 작성일 | 2026-10-23 |
| 상태 | 설계 검토 중 |
| 만족시키는 요구 | O-L01~O-L08, O-S04, O-S05, O-G01~O-G09, O-R01~O-R12, O-A02~O-A05, O-A07, O-Q01~O-Q07, O-N02~O-N04, N-27, F-A31, F-A35, SEC-70~SEC-73, SEC-77, SEC-90, R-10, R-11, R-13 |
| 의존 문서 | 02 아키텍처(§4.1 모듈, §9 테스트 계층, §10 OTel 지침), 10 에이전트(bootstrap·classdata 로딩 계약, 셰이딩 대상), 17 배포(산출물·설치 패키지), 18 테스트(태스크 경계), 16 보안(SBOM·서명 키·SECURITY.md) |
| 이 문서에 의존하는 문서 | 04 작업 지시서(WO-00 저장소 골격), 17 배포, 18 테스트, CONTRIBUTING·RELEASING·dev-setup 사용자 문서 |

## 1. 범위와 설계 결정

이 문서는 **저장소 구조, 빌드 시스템, 코드 품질 도구, CI, 릴리스 엔지니어링, 프로젝트 파일 세트, 기여 흐름**을 정한다. 각 모듈의 내부 설계는 10~15 문서가, 설치 패키지의 내용은 17 문서가 맡는다. 여기서 정한 것은 WO-00(저장소 골격)이 그대로 구현한다.

| ID | 결정 | 대안 | 선택 이유 | 영향 요구 |
|---|---|---|---|---|
| DSN-19-01 | **Gradle(Kotlin DSL) 모노레포** 하나. 버전 카탈로그 `gradle/libs.versions.toml`, 컨벤션 플러그인은 `build-logic/`(included build) | 멀티 저장소, Maven | 에이전트·서버·proto·샘플 앱·테스트가 한 커밋으로 움직인다. 공통 규칙을 플러그인으로 강제 | O-Q05, N-27 |
| DSN-19-02 | JDK toolchain 강제: 에이전트 계열 `--release 8`(실행 JDK는 toolchain 8), 서버·테스트 하네스 21. 혼용 금지는 컨벤션 플러그인이 검사 | 단일 JDK | 에이전트 Java 8 타겟 보증 | F-A31, 03 §3.1 |
| DSN-19-03 | 에이전트 단일 jar는 **3단 파이프라인**: ① `agent-core`+계측+의존성을 Shadow로 relocate(`io.argos.agent.shaded`) ② 결과 클래스를 `inst/**/*.classdata`로 이름 변경 ③ `agent-bootstrap` 클래스(루트)·매니페스트·고지·SBOM·버전 매니페스트와 합쳐 `argos-agent-X.Y.Z.jar` | 중첩 jar, 플랫 셰이딩 | DSN-02-02. bootstrap 로더가 `inst/` 클래스를 보지 못하게 하면서 임시 파일 0 | F-A31, F-A35 |
| DSN-19-04 | 배포물 의존성은 **라이선스 허용 목록 플러그인**이 빌드에서 검사(`com.github.jk1.dependency-license-report` + 허용 목록). 테스트·샘플 전용 구성은 검사 범위에서 제외하되 `docs/licenses/dev-only.md`와 대조 | 수동 검토 | O-L03, R-13 | O-L03 |
| DSN-19-05 | 재현 가능 빌드: Gradle 아카이브 타임스탬프 제거·순서 고정, `SOURCE_DATE_EPOCH`, 의존성 잠금(`gradle.lockfile`)·검증 메타데이터(`verification-metadata.xml` 체크섬), `pnpm --frozen-lockfile`. 릴리스 게이트에서 같은 커밋 2회 빌드 → jar 내용물 diff 0 | 미보장 | O-R08, SEC-73 | SEC-73 |
| DSN-19-06 | CI는 **GitHub Actions**. PR 검사는 GitHub-hosted 러너만, nightly·벤치·릴리스는 신뢰 브랜치 push·수동 트리거에서 사내 self-hosted 러너. `pull_request_target` 금지, 포크 PR은 self-hosted 라벨에 닿을 수 없다 | 사내 CI(Jenkins 등) 단일 | 공개 저장소 격리 원칙 O-S05. 사내 CI는 매트릭스 전용 보조로만 | O-S05, O-Q02 |
| DSN-19-07 | 모든 Actions는 **커밋 SHA로 고정**(Dependabot `github-actions` 생태계로 갱신). 캐시는 Gradle·pnpm 공식 액션 | 태그 참조 | 공급망 | SEC-73 |
| DSN-19-08 | 릴리스는 **태그 push가 트리거**(사람이 민다). 워크플로가 게이트 → 빌드 → 재현성 → SBOM → 서명 → Release 초안 → GHCR → Maven Central → 문서 사이트 순으로 진행하고, GA는 사람이 Release 초안을 발행해 끝낸다 | 수동 릴리스 | O-R11 | O-R11 |
| DSN-19-09 | 서명은 **GPG 분리 서명(릴리스 키, 조직 명의) + cosign keyless + GitHub artifact attestation** 세 가지를 모두 붙인다. 폐쇄망 검증은 GPG, 온라인은 셋 중 아무거나 | cosign만 | O-R06, SEC-72, 17 문서 DSN-17-11 | SEC-72 |
| DSN-19-10 | 버전은 SemVer 하나(에이전트·서버·UI 동일). 프로토콜 버전은 정수(`proto/PROTOCOL_VERSION`). 버전 소스는 git 태그 하나(`gradle.properties`에 기본 `0.0.0-SNAPSHOT`, 태그 빌드에서 치환) | 모듈별 버전 | O-R01 | O-R01 |
| DSN-19-11 | CHANGELOG는 **Keep a Changelog 1.1**을 사람이 쓴다(PR마다 `Unreleased` 절 항목 필수 또는 `no-changelog` 라벨). 릴리스 노트는 CHANGELOG 절 + `docs/upgrade/X.Y.md`의 Upgrade notes를 워크플로가 합친다 | release-drafter 자동 생성만 | 라벨 기반 자동 생성은 Upgrade notes 품질이 낮다. 라벨은 보조 | O-R10 |
| DSN-19-12 | 공개 저장소 프로젝트 파일 세트는 **WO-00에서 전부 생성**(LICENSE·NOTICE·CONTRIBUTING·CODE_OF_CONDUCT·SECURITY·GOVERNANCE·ROADMAP·RELEASING·`.github/*`). 내용이 비면 "작성 예정"이 아니라 최소 유효 내용 | Gate 1.5에 몰아서 | 첫 공개 커밋부터 LICENSE·DCO가 있어야 이력이 깨끗하다(O-L01·O-L06) | O-L01 |
| DSN-19-13 | 테스트 태스크를 **5종으로 분리**: `test`(단위·변환 검증), `integrationTest`(Testcontainers·가짜 gRPC), `matrixTest`(샘플 앱 × JDK, self-hosted), `securityTest`(06 문서 스위트), `benchTest`(7.3 프로토콜). PR은 `test`+`integrationTest`만 필수 | 단일 `test` | 실행 시간·러너 격리 | 03 §2, SEC-93 |
| DSN-19-14 | ArchUnit 규칙은 `agent-testing`의 `test`에 두어 PR마다 Advice 작성 규칙(람다·`skipOn`·`readOnly=false`·`synchronized`·블로킹·리플렉션 금지, `ArgosThread` 가드 첫 문장)을 검사한다 | 코드 리뷰만 | AI 생성 코드의 금지 패턴을 기계로 막는다 | F-A15, 03 §3.1 |

## 2. 모노레포 레이아웃

```
argos/                                   # 저장소명 미결 14 (예: argos-apm/argos)
├── LICENSE  NOTICE  THIRD-PARTY-NOTICES(생성물, 커밋하지 않음)  README.md  README.ko.md  CHANGELOG.md
├── CONTRIBUTING.md  CODE_OF_CONDUCT.md  SECURITY.md  GOVERNANCE.md  ROADMAP.md  RELEASING.md  CLAUDE.md
├── settings.gradle.kts  build.gradle.kts  gradle.properties  gradlew  gradle/
│   ├── libs.versions.toml               # 버전 카탈로그(단일 출처)
│   ├── verification-metadata.xml        # 의존성 체크섬 검증
│   └── wrapper/
├── build-logic/                         # 컨벤션 플러그인(included build)
│   └── src/main/kotlin/
│       ├── argos.java-common.gradle.kts         # Spotless·Error Prone·SPDX·라이선스 검사·재현 아카이브
│       ├── argos.java-agent.gradle.kts          # toolchain 8, --release 8, 금지 의존성, 셰이딩 규칙
│       ├── argos.java-server.gradle.kts         # toolchain 21, Spring Boot BOM
│       ├── argos.integration-test.gradle.kts    # integrationTest 소스셋·태스크
│       ├── argos.protobuf.gradle.kts            # proto 생성
│       └── argos.publishing.gradle.kts          # Maven Central 메타·서명
├── proto/                               # argos.v1 protobuf, PROTOCOL_VERSION
├── agent/
│   ├── agent-bootstrap/                 # bootstrap 주입 클래스(의존성 0)
│   ├── agent-core/
│   ├── agent-instrumentation/
│   │   ├── servlet/ spring-webmvc/ jdbc/ pool/ executor/ servlet-async/ scheduler/ logging/ custom/
│   │   └── httpclient-jdk/ httpclient-apache4/ httpclient-apache5/ httpclient-okhttp/ httpclient-jdk11/ httpclient-reactornetty/
│   ├── agent-dist/                      # 단일 jar 조립(DSN-19-03)
│   └── agent-testing/                   # 하네스·ArchUnit·변환 베이스라인·매트릭스 러너
├── server/                              # Spring Boot(단일 모듈, 패키지로 역할 분리: collector/web/alert/admin/ops/storage)
├── ui/                                  # React + TypeScript + Vite + pnpm
├── sample-apps/
│   ├── shop-boot2/ shop-boot3/ shop-boot4/ shop-heavy/ shop-batch/ war-javax/ war-jakarta/
│   └── load/                            # k6 시나리오, 데모 부하 생성기 이미지
├── deploy/
│   ├── compose/  tar/  airgap/  monitoring/  clickhouse/(13 문서 schema·rollups·config.d·users.d)  helm/(1.x)
│   └── build.gradle.kts                 # tarDist·airgapBundle 태스크
├── test/
│   ├── agent/check-shading.sh  bench/  chaos/  storage/(생성기·쿼리)  e2e/(Playwright)  security/(ZAP 설정)  matrix/
├── docs/                                # 01~06, 10~19, poc/, bench/, adr/, upgrade/, ops/, security/, licenses/, field-reports/
└── .github/
    ├── workflows/  ISSUE_TEMPLATE/  PULL_REQUEST_TEMPLATE.md  CODEOWNERS  dependabot.yml  labeler.yml  release.yml
```

모듈 의존 그래프

```
proto ─────────────┬──▶ agent-core ─▶ agent-instrumentation/* ─▶ agent-dist(셰이딩·조립)
                   │        ▲                                        ▲
agent-bootstrap ───┴────────┘  (compileOnly, 런타임은 bootstrap CL)  │
                   └──▶ server ◀── ui(정적 자원은 별도 배포, 서버 jar에 포함하지 않음)
sample-apps/* ──(runtime: agent-dist jar)──▶ agent-testing(matrixTest)   test/* ──▶ 모든 모듈
```

규칙: `agent-bootstrap`은 의존성 0, `agent-core`·계측은 `agent-bootstrap`에 `compileOnly`. 계측 모듈은 대상 라이브러리를 `compileOnly`로만 가진다(런타임은 앱 것). 서버는 에이전트 모듈에 의존하지 않고 `proto`만 공유한다. UI 빌드 산출물은 서버 이미지·tar에 `static/`으로 **복사**되며 Gradle이 pnpm을 호출한다(`:ui:build` 태스크, `Exec`).

## 3. Gradle 설계

### 3.1 `settings.gradle.kts`·버전 카탈로그

- `rootProject.name = "argos"`, `includeBuild("build-logic")`, 모듈 `include(":proto", ":agent:agent-bootstrap", … , ":server", ":ui", ":sample-apps:shop-boot3", … , ":deploy")`.
- `dependencyResolutionManagement { repositories { mavenCentral() } }` 외 저장소 금지(사내 미러는 `init.gradle`로 러너에서만).
- `libs.versions.toml`에 모든 라이브러리·플러그인 버전. 주요 항목: `bytebuddy`, `grpc`, `protobuf`(javalite 여부 A-1 후), `netty`, `jctools`, `spring-boot`, `clickhouse-client`, `postgresql`, `flyway`, `testcontainers`, `archunit`, `junit`, `assertj`, `errorprone`, `spotless`, `shadow`(`com.gradleup.shadow`), `cyclonedx`(`org.cyclonedx.bom`), `license-report`, `jib`.
- `gradle.properties`: `org.gradle.caching=true`, `org.gradle.parallel=true`, `org.gradle.configuration-cache=true`, `version=0.0.0-SNAPSHOT`(태그 빌드에서 `-Pversion=`로 치환), `argos.protocolVersion` 읽기는 `proto/PROTOCOL_VERSION` 파일.

### 3.2 컨벤션 플러그인

| 플러그인 | 적용 대상 | 내용 |
|---|---|---|
| `argos.java-common` | 모든 Java 모듈 | Spotless(google-java-format, SPDX 헤더 `/* SPDX-License-Identifier: Apache-2.0 */` + `Copyright <year> <저작권자>`), Error Prone(`-Werror`, 선택 체크 `NullAway`는 1.x), `-Xlint:all -Werror`, 재현 가능 아카이브(`isPreserveFileTimestamps=false`, `isReproducibleFileOrder=true`, `dirPermissions`/`filePermissions` 고정), 라이선스 리포트·허용 목록 검사 태스크 `checkLicenses`(`check`에 연결), JUnit 5, 테스트 로깅 |
| `argos.java-agent` | agent-* , 계측 모듈 | toolchain `languageVersion=8`(실행도 8로 테스트), `options.release=8`, 금지 의존성 규칙(`org.slf4j`, `log4j`, `commons-logging`, `jakarta.*`/`javax.servlet` 구현체는 `compileOnly`만), `agent-bootstrap`은 `dependencies` 0 검사, 모듈 README 존재 검사, ArchUnit 규칙은 `agent-testing`에서 전체 스캔 |
| `argos.java-server` | server | toolchain 21, Spring Boot BOM, `bootJar`(레이어드), Flyway 리소스 검증(파일명 규칙), OpenAPI 스펙 생성 태스크(`generateOpenApi` → `docs/api/openapi.yaml` 커밋, CI가 diff 검사) |
| `argos.integration-test` | server, agent-testing, deploy | `integrationTest` 소스셋·태스크(Testcontainers), `matrixTest`·`securityTest`·`benchTest` 태스크 정의(기본 `check`에 미포함) |
| `argos.protobuf` | proto, agent-core, server | `com.google.protobuf` 플러그인, Java 생성(에이전트 `lite` 또는 `full`은 A-1 후 플래그), gRPC 스텁 |
| `argos.publishing` | agent-dist(Maven Central), 1.x에 proto | `maven-publish` + 서명(GPG, CI 비밀), POM 메타(이름·설명·URL·라이선스·개발자·SCM), `sources`·`javadoc` jar(에이전트 javadoc은 bootstrap API만), Sonatype Central Portal 업로드(플러그인은 WO-00에서 `com.vanniktech.maven.publish` 또는 JReleaser 중 확정) |

### 3.3 에이전트 단일 jar 조립 (`:agent:agent-dist`)

| 단계 | 태스크 | 입력 | 출력 | 규칙 |
|---|---|---|---|---|
| 1 | `shadowJar`(Shadow) | `agent-core`, 계측 모듈, 런타임 의존성(ByteBuddy, gRPC, Netty, protobuf, JCTools 등) | `build/shadow/agent-inst.jar` | relocate `net.bytebuddy`→`io.argos.agent.shaded.net.bytebuddy`, `io.grpc`, `io.netty`, `com.google.protobuf`, `org.jctools`, `com.google.common`(있다면) 등 **모든 외부 패키지**. `agent-bootstrap`·`io.argos.*`는 relocate 제외. `minimize()`는 켜되 리플렉션 로딩 클래스(gRPC `NameResolverProvider`, Netty 채널)는 `exclude` 목록으로 보존. `mergeServiceFiles()`, `ApacheNoticeResourceTransformer`·`ApacheLicenseResourceTransformer`로 META-INF 고지 병합, `META-INF/*.SF/*.DSA/*.RSA` 제거, `module-info.class` 제거 |
| 2 | `classdataJar`(커스텀) | 1의 jar | `build/classdata/inst/**/*.classdata` | `.class` → `.classdata` 이름 변경, 디렉터리 `inst/` 아래로. 리소스(`META-INF/services`, proto descriptor)는 `inst/`에 그대로. 10 문서의 `AgentClassLoader` 로딩 계약(경로 → 클래스명 변환)과 일치 |
| 3 | `agentJar`(커스텀 `Jar`) | `agent-bootstrap` 클래스(루트), 2의 `inst/`, 생성 파일 | `argos-agent-X.Y.Z.jar` | 매니페스트: `Premain-Class: io.argos.agent.bootstrap.ArgosAgent`, `Agent-Class`(동일, 1.x 동적 attach), `Can-Retransform-Classes: true`, `Can-Redefine-Classes: false`, `Implementation-Version`, `Argos-Protocol-Version`, `Argos-Git-Commit`, `Multi-Release: false`. **`Add-Opens`·`Launcher-Agent-Class`·`Boot-Class-Path` 넣지 않음**(DSN-02-03). 포함: `META-INF/LICENSE`, `META-INF/NOTICE`, `META-INF/THIRD-PARTY-NOTICES`, `META-INF/argos-deps.txt`, `META-INF/sbom/agent.cdx.json` |
| 4 | `generateDepsManifest` | 셰이딩 전 런타임 의존성 그래프 | `argos-deps.txt`(group:artifact:version:license 한 줄씩) | SEC-77. 고지 문서와 같은 리포트에서 생성 |
| 5 | `cyclonedxBom` | 셰이딩 전 `agent-core` 등 원본 모듈 | `agent.cdx.json` | SEC-70. "포함 가능성 전부"의 보수적 SBOM |
| 6 | `checkShading`(Exec `test/agent/check-shading.sh`) | 3의 jar | 통과/실패 | §5.6 |
| 7 | `checkRelocatedStrings`(커스텀) | 3의 jar | 통과/실패 | `javap -c`로 `io.netty.noUnsafe`·`net.bytebuddy.experimental` 등 **프로퍼티 문자열 상수가 relocate됐는지** 확인하고, 10 문서가 정한 방식(relocate된 이름으로 설정 또는 relocate 제외 패턴)과 일치하는지 검사 |
| 8 | `checkMajorVersion` | 3의 jar | 통과/실패 | 모든 `.class`·`.classdata`의 major version == 52 |

산출 jar 크기 목표 ≤ 15MB(Gate 0 P-3). `agent-dist`는 `check`에 6·7·8을 연결한다.

### 3.4 protobuf 생성

`proto/src/main/proto/argos/v1/*.proto`(11 문서). `argos.protobuf` 플러그인이 Java 코드를 생성한다. 에이전트는 `protobuf-javalite`(크기 1/3) 채택 여부를 A-1(P-3)에서 결정하므로 플러그인에 `argos.proto.lite=true|false` 플래그를 두고 서버는 항상 full. 생성 코드는 커밋하지 않는다(빌드 산출물). `PROTOCOL_VERSION` 파일 값은 `AgentHello`와 서버 상수 양쪽에 주입된다.

### 3.5 서버 부트 jar·이미지

- `:server:bootJar` 레이어드 jar(의존성/스냅샷/리소스/앱 레이어). UI 정적 자원은 `:ui:build` 산출물을 `src/main/resources/static`이 아니라 **빌드 시 복사**(`processResources` 의존)해 저장소에 번들을 커밋하지 않는다.
- 이미지: Jib(`com.google.cloud.tools.jib`) `jib.from.image = eclipse-temurin:21-jre@sha256:<digest>`, `platforms = [amd64, arm64]`, 비루트 uid 10001, `container.creationTime = SOURCE_DATE_EPOCH`, 라벨(OCI `org.opencontainers.image.*`: version·revision·licenses=Apache-2.0·source). 데모 앱 2종도 Jib(샘플 앱 + `agent-dist` jar를 `/opt/argos-agent/`에 포함).
- `:deploy:tarDist`: 17 문서 §4.1 레이아웃으로 `argos-server-X.Y.Z.tar.gz`(스크립트 0755, conf 템플릿, 유닛, 라이선스). `:deploy:airgapBundle`: 17 문서 §5.1(이미지는 `docker save`를 CI에서 수행).

### 3.6 테스트 태스크 분리 (DSN-19-13)

| 태스크 | 소스셋 | 내용 | 실행 |
|---|---|---|---|
| `test` | `src/test` | 단위, ArchUnit, 변환 검증(CheckClassAdapter·베이스라인 diff·Unsafe 검사·major 52), SQL 정규화 50케이스, 마스킹 패턴 | PR 필수, GitHub-hosted |
| `integrationTest` | `src/integrationTest` | Testcontainers ClickHouse·PostgreSQL, 가짜 gRPC 서버/에이전트, 서버 API 계약, 정합 D-03·D-12·D-13 축소판, 가벼운 샘플 앱 1종(shop-boot3, JDK 21) `-XX:+BytecodeVerificationLocal` | PR 필수, GitHub-hosted(약 15분 상한) |
| `matrixTest` | `agent-testing/src/matrixTest` | 02 문서 유효 조합표 전부(JDK toolchain 8/11/17/21/25 × 샘플 앱), 가상 스레드 on/off, JDK 25 `deny`, JDBC 9종, WAR 재배포 50회, 공존, 로그 허용목록 | nightly, self-hosted |
| `securityTest` | `src/securityTest` | 06 문서 SEC-93 스위트(권한 매트릭스 생성 테스트, 상한, 레이트 리밋, SSRF, 토큰 수명주기, 하드 캡, 환경 덤프 마스킹, PII 시드), XSS 시드 E2E는 `ui` Playwright 태그로 | PR 필수(경량 부분) + nightly(전체) |
| `benchTest` | `test/bench` | 01 문서 7.3 프로토콜 자동화(k6 + 측정 스크립트), 서버 5,000 TPS 1시간, 저장소 리플레이 | 주 1회·릴리스 전, self-hosted(측정 전용 머신) |

### 3.7 재현 가능 아카이브·의존성 잠금·검증

- 모든 `AbstractArchiveTask`: 타임스탬프 제거·순서 고정·권한 고정. `SOURCE_DATE_EPOCH`는 커밋 시각(CI가 주입).
- `dependencyLocking { lockAllConfigurations() }` + `gradle.lockfile` 커밋. 갱신은 Dependabot PR 또는 `--write-locks`.
- `gradle/verification-metadata.xml`: 체크섬 검증(`sha256`), 서명 검증은 1.x(키 서버 폐쇄망 문제). 불일치 시 빌드 실패. 갱신 절차를 CONTRIBUTING에.
- 재현성 검사 태스크 `verifyReproducible`: 릴리스 게이트에서 clean 빌드 2회 → `argos-agent.jar`·`argos-server.jar`의 엔트리별 해시 비교(jar 전체 해시가 아니라 내용물 diff, O-R08 1.0 기준).

## 4. pnpm·UI 빌드

| 항목 | 규칙 |
|---|---|
| 패키지 관리 | pnpm, `pnpm-lock.yaml` 커밋, CI `pnpm install --frozen-lockfile`. Node LTS 버전 `.nvmrc`·`package.json#engines` 고정 |
| 빌드 | Vite, 산출 `ui/dist/`. `:ui:build`(Gradle `Exec`)가 pnpm을 호출하고 서버 리소스로 복사 |
| 외부 리소스 0 | 빌드 후 검사 스크립트 `ui/scripts/check-no-external.mjs`: `dist/` 내 `http(s)://` 참조(폰트·스크립트·이미지) 0, `index.html`에 외부 `<link>`·`<script>` 0(O-P03). CSP 메타는 서버가 헤더로 |
| 라이선스 고지 | `license-checker-rseidelsohn`(또는 `pnpm licenses`)로 번들 포함 패키지 고지 생성 → `dist/licenses.txt`, 허용 목록(O-L03) 검사 |
| SBOM | `@cyclonedx/cyclonedx-npm`으로 `ui.cdx.json`(SEC-70) |
| 품질 | `tsc --noEmit --strict`, ESLint(`react/no-danger` error, `no-restricted-syntax`로 `innerHTML`·`eval`·`new Function` 금지, `eslint-plugin-i18next/no-literal-string`로 하드코딩 문자열 금지, import 순서), Prettier. 예외는 파일 단위 allowlist + 사유 주석(SEC-60) |
| 테스트 | Vitest(단위·컴포넌트, XSS 페이로드 렌더 단언), Playwright(E2E, 화면별 필수 요소, XSS 전역 훅, CSP 위반 리포트 0). Playwright는 `integrationTest` 단계에서 서버 컨테이너 + 합성 데이터로 실행 |
| i18n | `ui/src/locales/en/*.json`(원본), `ko/*.json`. 키 누락 검사 스크립트(`check-i18n-keys.mjs`), en에 없는 키 금지 |
| 생성 클라이언트 | `docs/api/openapi.yaml`에서 `openapi-typescript` + fetch 래퍼 생성(`ui/src/api/generated/`, 커밋하지 않음). `fetch` 직접 호출은 ESLint 금지 |

## 5. 코드 품질 도구

### 5.1 Spotless + SPDX

- Java: `googleJavaFormat()`, `licenseHeaderFile("build-logic/spdx-header.txt")` — 내용 `/* SPDX-License-Identifier: Apache-2.0 */` 1줄(저작권 줄은 `NOTICE`에만 두어 파일별 연도 갱신 부담을 없앤다. 미결 13 확정 후 변경 가능). Kotlin DSL, YAML(`prettier`), Markdown은 포맷 검사 제외(문서 자유도).
- TypeScript: Prettier + `eslint-plugin-header`로 같은 SPDX 헤더(`// SPDX-License-Identifier: Apache-2.0`).
- proto·SQL·sh 파일에도 헤더 검사(단순 grep 스크립트 `test/lint/check-spdx.sh`).

### 5.2 Error Prone

`net.ltgt.errorprone` 플러그인, `-Werror`. 기본 체크 + `MissingOverride`·`UnusedVariable` 등 경고를 오류로. 에이전트 모듈에는 커스텀 체크 대신 ArchUnit(§5.3)으로 Advice 규칙을 검사한다(Error Prone 커스텀 체크는 1.x).

### 5.3 ArchUnit 규칙 (`agent-testing`, PR 필수)

| 규칙 | 대상 | 검사 |
|---|---|---|
| ADV-01 | `@Advice.OnMethodEnter/Exit` 메서드 | `static`, 람다·메서드 참조·`invokedynamic` 없음(바이트코드 검사), `String.format`·스트림·try-with-resources 호출 없음 |
| ADV-02 | Advice 애노테이션 | `skipOn` 미사용, `@Advice.Return/Thrown(readOnly=false)` 미사용, `suppress = Throwable.class` 필수 |
| ADV-03 | Advice 메서드 본문 | 첫 호출이 `ArgosThread.isAgentThread()`(또는 동등 가드) 또는 helper 위임만. `synchronized`, `BlockingQueue.put/take`, `Thread.sleep`, `Class.forName`, `Method.invoke`, `new Throwable/Exception`(스택 캡처) 호출 금지 |
| ADV-04 | 계측 모듈 | `agent-bootstrap` 외 다른 모듈의 클래스를 Advice가 직접 참조하지 않음(helper 주입 대상만 허용 목록) |
| AGT-01 | `agent-bootstrap` | 외부 패키지 import 0, `java.*`만 |
| AGT-02 | `agent-core` | `org.slf4j`·`java.util.logging` 직접 사용 금지(자체 로거만), `System.out/err` 호출 금지 |
| AGT-03 | 전체 에이전트 | `sun.misc.Unsafe` 참조 금지(셰이딩 의존성은 `checkShading`의 javap 검사) |
| SRV-01 | 서버 | 컨트롤러는 `@PreAuthorize` 또는 OpenAPI `x-argos-role` 매핑 필수(권한 매트릭스 생성 테스트가 스펙에서 검사), 저장소 접근은 `storage` 패키지 경유 |

### 5.4 ESLint·tsc

§4 참조. 추가: `@typescript-eslint/strict`, `react-hooks`, `jsx-a11y`(키보드·대비 기본), `no-restricted-imports`로 `antd` 직접 import 대신 공통 컴포넌트 경유(표·차트 공통 상호작용 강제, F-U16).

### 5.5 라이선스 허용 목록 검사

- Gradle: `com.github.jk1.dependency-license-report`(Apache-2.0)로 `runtimeClasspath` 리포트 생성 → 커스텀 태스크 `checkLicenses`가 허용 목록(`build-logic/allowed-licenses.json`: Apache-2.0, MIT, BSD-2-Clause, BSD-3-Clause, ISC, 0BSD, PostgreSQL, Unicode-DFS, CC0-1.0)과 대조. 알 수 없는 표기는 `build-logic/license-overrides.json`에 수동 매핑(출처 URL 필수). 위반 시 실패. 구성 범위: 배포물에 들어가는 구성(`runtimeClasspath` of agent-dist·server·deploy)만. `testRuntimeClasspath`·`sample-apps`는 제외하되 `docs/licenses/dev-only.md`와의 대조 태스크 `checkDevOnlyLicenses`(목록에 없는 비호환 라이선스 테스트 의존성 발견 시 실패).
- UI: §4.
- 같은 리포트에서 `THIRD-PARTY-NOTICES`(이름·버전·라이선스·저작권·원 NOTICE 본문)와 `argos-deps.txt`를 생성한다. `NOTICE`(루트)는 손으로 유지(프로젝트 저작권 + 차용 코드 출처 목록, O-L05).

### 5.6 `test/agent/check-shading.sh` 사양

| 검사 | 기준 | 실패 메시지 |
|---|---|---|
| 클래스 패키지 | `jar tf`의 `.class`(루트)와 `inst/**/*.classdata`(변환 후 이름)에서 패키지가 `io/argos/agent/bootstrap/`(루트) 또는 `io/argos/agent/`·`io/argos/agent/shaded/`(inst) 외인 것 0 | 패키지 목록 출력 |
| 루트 노출 | 루트(`inst/` 밖)의 `.class`는 `io/argos/agent/bootstrap/`만 | |
| META-INF 필수 파일 | `LICENSE`, `NOTICE`, `THIRD-PARTY-NOTICES`, `argos-deps.txt`, `sbom/agent.cdx.json` 존재, `MANIFEST.MF`의 `Premain-Class`·`Can-Retransform-Classes` 존재, `Add-Opens`·`Launcher-Agent-Class`·`Boot-Class-Path` 부재 | |
| 서명 파일 | `META-INF/*.SF|*.DSA|*.RSA` 0 | |
| major version | 모든 클래스 바이트 7~8번째 == 0x0034(52) | 위반 클래스 |
| Unsafe | `javap -c`(또는 바이트 grep)로 `sun/misc/Unsafe` 참조가 허용 목록(`test/agent/unsafe-allowlist.txt`, 비어 있는 것이 목표) 밖 0 | 참조 클래스 |
| 네이티브 | `.so`·`.dll`·`.dylib`·`META-INF/native/` 0 | |
| 크기 | ≤ 15MB(경고 12MB) | |
| 중복 | 같은 클래스명 2회 이상 0 | |

종료 코드 0/1. CI PR 필수 검사와 `agent-dist:check`에서 실행.

## 6. CI 설계 (GitHub Actions)

### 6.1 워크플로 파일

| 파일 | 트리거 | 러너 | 내용 |
|---|---|---|---|
| `pr.yml` | `pull_request`(모든 브랜치), `push`(main·release/*) | GitHub-hosted(ubuntu-latest, 필요 시 macOS는 없음) | §6.2 필수 검사 전부. 잡 분리: `build-java`, `build-ui`, `lint`, `license`, `agent-dist-checks`, `integration`, `security-light`, `openapi-diff`, `changelog`, `dco` |
| `codeql.yml` | `pull_request`, `push`, 주간 | GitHub-hosted | CodeQL Java·TypeScript |
| `secrets-scan.yml` | `pull_request`, `push` | GitHub-hosted | gitleaks(전체 이력은 주간) |
| `nightly.yml` | `schedule`(매일), `workflow_dispatch` | **self-hosted**(`[self-hosted, argos-matrix]`) | `matrixTest` 전체, JDBC 9종(상용 DB는 사내 컨테이너), WAR 재배포, 공존, 가상 스레드, JDK 25 deny, `securityTest` 전체, 데모 60초 측정, egress 0(월 1회) |
| `bench.yml` | `schedule`(주 1회), `workflow_dispatch` | self-hosted(`argos-bench`, 측정 전용 2대) | `benchTest`(7.3 프로토콜), 결과를 `docs/bench/<date>-<sha>.md`로 PR 생성(사람이 머지) |
| `release.yml` | `push: tags: ['v*']` | GitHub-hosted(빌드·서명·배포) + self-hosted(전체 게이트) | §7.2 |
| `docs.yml` | `push`(main, 태그) | GitHub-hosted | 문서 사이트 빌드·배포(1.0부터), 영문 요약 검사 |
| `dependabot.yml`(설정) | 주간 | - | Gradle·pnpm·Actions·Docker 베이스 이미지 그룹 업데이트, 보안은 즉시 |
| `labeler.yml`(설정) | PR | - | 경로 기반 `area/*`, 핵심 경로 `needs-matrix` 자동 부여(§6.8) |

### 6.2 PR 필수 검사 (브랜치 보호 `required_status_checks`)

| 검사 | 도구·태스크 | 실패 조건 |
|---|---|---|
| 빌드·단위 | `./gradlew build -x integrationTest`(모든 모듈), `pnpm -C ui build` | 실패 |
| 정적 분석 | Spotless `check`, Error Prone(`-Werror`), ESLint, `tsc`, ArchUnit(`agent-testing:test`) | 경고 1건 |
| SPDX 헤더 | Spotless + `check-spdx.sh` | 누락 1건 |
| 라이선스 허용 목록 | `checkLicenses`, `checkDevOnlyLicenses`, UI 고지 검사 | 비허용 라이선스 |
| 셰이딩·패키징 | `agent-dist:check`(check-shading, relocated strings, major 52, 크기) | 실패 |
| 경량 통합 | `integrationTest`(Testcontainers), Playwright E2E(합성 데이터) | 실패 |
| 보안 경량 | `securityTest`의 PR 태그(권한 매트릭스·상한·토큰·하드 캡 단위), gitleaks, CodeQL | 발견 1건 |
| SBOM 스캔 | CycloneDX 생성 → Trivy `sbom` 모드(또는 Grype) | Critical·High(예외 문서 `docs/security/cve-exceptions.md`에 없는 것) |
| OpenAPI 호환 | `oasdiff breaking docs/api/openapi.yaml(base) (head)` | breaking 변경인데 `breaking-change` 라벨 없음 |
| CHANGELOG | `CHANGELOG.md` `Unreleased` 절 변경 또는 `no-changelog` 라벨 | 둘 다 없음 |
| DCO | DCO GitHub App(또는 액션) | `Signed-off-by` 누락 |
| i18n | `check-i18n-keys.mjs` | en 누락·ko 고아 키 |
| 외부 리소스 | `check-no-external.mjs` | 1건 |

PR 전체 목표 시간 25분 이내(Gradle 빌드 캐시·pnpm 캐시, 모듈별 병렬 잡).

### 6.3 nightly (신뢰 브랜치·self-hosted)

- 트리거는 `schedule`과 `workflow_dispatch`뿐. `on: pull_request`를 두지 않는다. 체크아웃은 `main`·`release/*`의 커밋만.
- 잡: `matrix`(02 문서 유효 조합표를 `strategy.matrix`로, toolchain 자동 다운로드는 사내 미러), `jdbc-matrix`(Testcontainers + 사내 Oracle·Tibero·Altibase·CUBRID 컨테이너는 러너 라벨 `argos-db`), `webapp-redeploy`, `coexistence`, `virtual-threads`, `jdk25-deny`, `security-full`, `demo-first-5-minutes`(60초 측정), `egress-zero`(월 1회, 격리 VM).
- 결과: 실패 시 Discussions가 아니라 메인테이너 알림(이메일·Slack), `docs/compat/matrix-status.md` 자동 갱신 PR(검증·미검증 표).

### 6.4 주간 벤치

`benchTest`를 측정 전용 self-hosted 2대(부하기·앱 서버)에서 실행. 01 문서 7.3 절차(워밍업·A-B-A-B-A-B·CV 검사)를 스크립트가 수행하고 `docs/bench/<date>-<sha>.md`를 생성해 PR로 올린다. 기준 미달이면 PR 본문에 빨간 표시, 사람이 판단. OTel javaagent 참조 측정을 같은 세트로 함께 실행(비교 기준).

### 6.5 릴리스 전 전체 게이트

`release.yml` 첫 단계. nightly 전체 + `benchTest` + 72시간 테스트(별도 트리거, 결과 아티팩트 존재 확인) + 카오스 + 재현성 검사 + 업그레이드 경로 테스트(17 문서) + 에어갭 egress 0. 하나라도 실패하면 릴리스 중단.

### 6.6 러너 격리 규칙 (O-S05, DSN-19-06)

| 규칙 | 구현 |
|---|---|
| 포크 PR은 GitHub-hosted만 | `pr.yml`의 모든 잡 `runs-on: ubuntu-latest`. self-hosted 라벨은 `nightly`·`bench`·`release`에만 |
| `pull_request_target` 금지 | 워크플로 린트(`actionlint` + 커스텀 grep)가 PR 검사에서 실패 처리 |
| self-hosted 러너는 조직 수준 러너 그룹 `argos-trusted`에 등록, 저장소 설정 "Require approval for all outside collaborators"(포크 PR 워크플로 승인 필수) | GitHub 설정, GOVERNANCE에 기록 |
| self-hosted 러너는 사내망 격리 VLAN, 비밀값은 러너 환경변수가 아니라 GitHub Environment(`trusted`) 비밀로만, 작업 디렉터리는 잡마다 초기화(ephemeral runner) | 17 문서 사내 환경 |
| 워크플로 권한 | `permissions: contents: read` 기본. 릴리스 잡만 `contents: write, packages: write, id-token: write, attestations: write` |
| Actions 고정 | 모든 `uses:`는 SHA. Dependabot `github-actions`가 갱신 PR |

### 6.7 캐시

Gradle: `gradle/actions/setup-gradle`(읽기 전용 캐시는 PR, 쓰기는 main). pnpm: `actions/setup-node` + pnpm store 캐시. Docker 레이어: Jib는 로컬 캐시 불필요, Testcontainers 이미지는 러너 캐시(나이틀리 self-hosted는 사내 레지스트리 미러).

### 6.8 `needs-matrix` 자동 라벨 (O-Q03)

`labeler.yml` 경로 패턴: `agent/agent-bootstrap/**`, `agent/agent-core/**`(전송·클래스로더·컨텍스트·버퍼), `agent/agent-dist/**`, `proto/**`, `server/src/main/java/io/argos/server/collector/**`, `.../storage/**`(WAL·라이터·마이그레이션), `.../security/**`, `.../admin/token/**`, `.../command/**`, `.../privacy/**`(마스킹·정규화), `.../alert/channel/**`(웹훅 전송), `deploy/clickhouse/schema/**`. 라벨이 붙은 PR은 메인테이너가 신뢰 브랜치로 가져와 nightly·벤치 통과 후 머지(절차는 CONTRIBUTING·GOVERNANCE).

## 7. 릴리스 엔지니어링

### 7.1 버전 체계

- SemVer `X.Y.Z`, 사전 릴리스 `X.Y.Z-beta.N`·`-rc.N`, 태그 `vX.Y.Z[-pre]`. 에이전트·서버·UI·이미지·tar 모두 같은 버전.
- 프로토콜 버전 `proto/PROTOCOL_VERSION` 정수. 호환 범위(서버 `X.Y` ↔ 에이전트 `X.(Y-2)`~`X.Y`)는 서버 상수 표로 두고 테스트가 검증.
- 0.x: 호환성 약속 없음, 단 0.(n-1) → 0.n 마이그레이션 제공. 1.0부터 `docs/compatibility.md` 적용.
- 브랜치: `main`(다음 마이너), `release/X.Y`(패치·백포트). 태그는 release 브랜치에서.

### 7.2 태그 트리거 워크플로 (`release.yml`)

| # | 단계 | 러너 | 산출 |
|---|---|---|---|
| 1 | 전체 게이트(§6.5) | self-hosted + hosted | 통과 보고 |
| 2 | 빌드(`-Pversion=X.Y.Z`, `SOURCE_DATE_EPOCH`=태그 커밋 시각) | hosted | agent jar, server jar, tar, UI dist, 데모 이미지 |
| 3 | 재현성 검사(`verifyReproducible`: 2회 빌드 내용물 diff 0) | hosted | 보고 |
| 4 | SBOM 3종(agent·server·ui) + SBOM 스캔 Critical·High 0(예외 문서 대조) | hosted | `*.cdx.json` |
| 5 | 체크섬 `SHA256SUMS` | hosted | |
| 6 | 서명: GPG 분리 서명(`SHA256SUMS.asc`, 각 자산 `.asc`; 키는 GitHub Environment 비밀, 조직 명의 릴리스 키), cosign keyless(`cosign sign-blob --yes` → `.sig`·`.pem`), `actions/attest-build-provenance`(SLSA provenance) | hosted(`id-token: write`) | 서명 파일, attestation |
| 7 | 컨테이너: Jib 멀티아키 푸시 `ghcr.io/<org>/argos-server:X.Y.Z`·`X.Y`·(GA만)`latest`, 데모 이미지, `cosign sign` + attestation, 다이제스트를 `compose.yaml`에 기록해 자산에 포함 | hosted | 이미지, 다이제스트 |
| 8 | 에어갭 번들 조립(`docker save`, 패키지, 문서, 체크섬·서명·공개키·지문) | hosted(큰 디스크) | `argos-airgap-X.Y.Z-<arch>.tar` |
| 9 | GitHub Release **초안** 생성: 자산 전부 + 릴리스 노트(CHANGELOG 절 + Upgrade notes) + 검증 명령 | hosted | 초안 |
| 10 | Maven Central: `agent-dist` 발행(Sonatype Central Portal, GPG 서명, sources·javadoc) — rc·GA만, beta는 GitHub Release만 | hosted | `<groupId>:argos-agent:X.Y.Z` |
| 11 | 문서 사이트 버전 추가(1.0부터), `docs/compat/matrix-status.md` 스냅샷 | hosted | |
| 12 | **사람**이 Release 초안 검토 → 발행(GA는 `latest` 표시, pre-release는 체크) → Discussions Announcements | 수동 | |

실패 시 어느 단계든 중단, 이미 푸시된 이미지는 태그 `X.Y.Z-failed`로 바꾸고 다음 시도에서 덮어쓴다(다이제스트 고정이라 사용자에게 영향 없음).

### 7.3 서명·검증 자료

- 공개키: `ARGOS-RELEASE-KEY.asc`를 저장소 루트 `keys/`, `SECURITY.md`, 문서 사이트, 각 Release 자산에. 지문은 네 곳 모두에 기록(에어갭 육안 대조).
- 사용자 검증 1줄: `sha256sum -c SHA256SUMS && gpg --verify SHA256SUMS.asc SHA256SUMS`(오프라인) / `cosign verify-blob --certificate-identity-regexp '…/release.yml@refs/tags/v' --certificate-oidc-issuer https://token.actions.githubusercontent.com` / `gh attestation verify`(온라인). `deploy/verify-release.sh`가 셋을 순서대로 시도한다.

### 7.4 GPG 키 관리 (O-G08)

- 릴리스 키는 **조직 명의**(이름 "Argos APM Release Signing", 조직 이메일), 4096-bit RSA 또는 Ed25519, 만료 2년(갱신 절차 RELEASING). 개인 키는 개인에게 묶이지 않는다.
- 오프라인 마스터 키 + 온라인 서명 서브키. 서브키 비밀은 GitHub Environment `release`(필수 리뷰어 2인 중 1인 승인)에만. 마스터 키는 조직 소유자 2인이 분리 보관(암호화 USB, 봉인).
- 폐기·교체 절차와 교차 서명(새 키를 구 키로 서명)을 RELEASING·SECURITY에. Maven Central 키 등록(keys.openpgp.org 등).

### 7.5 Maven Central

- `groupId`는 미결 14: 도메인 보유 시 `io.argos`, 아니면 `io.github.<org>`. `artifactId` `argos-agent`. 네임스페이스 검증은 Sonatype Central Portal 절차(DNS TXT 또는 GitHub 조직 자동 검증).
- POM: name·description·url·licenses(Apache-2.0)·developers(조직 계정)·scm. `sources` jar(bootstrap + core 소스), `javadoc` jar(bootstrap 공개 API만; 나머지는 빈 javadoc 허용).
- 발행 플러그인은 WO-00에서 `com.vanniktech.maven.publish`(Central Portal 지원)와 JReleaser 중 하나로 확정(미결).
- 1.x에 `argos-proto`(서버 연동 개발용) 발행 검토.

### 7.6 GHCR

이미지 `ghcr.io/<org>/argos-server`, `argos-demo-app`, `argos-demo-load`. 태그 `X.Y.Z`, `X.Y`, `latest`(GA만). 공개 패키지, 다운로드 인증 없음(O-R05). 이미지 라벨 OCI 표준, 서명·attestation(§7.2 7단계). 오래된 pre-release 이미지는 90일 후 정리(GA는 영구).

### 7.7 CHANGELOG·릴리스 노트·Upgrade notes

- `CHANGELOG.md`: Keep a Changelog 1.1. 절 `Unreleased`, 버전별 `Added/Changed/Deprecated/Removed/Fixed/Security`. PR은 `Unreleased`에 1줄(이슈 번호 포함) 또는 `no-changelog` 라벨.
- `docs/upgrade/X.Y.md`: 마이너마다 수기 Upgrade notes(breaking·마이그레이션 소요·설정 키 변경·에이전트 호환 범위). 패치는 CHANGELOG만.
- 릴리스 노트 = `scripts/release-notes.sh X.Y.Z`가 CHANGELOG 해당 절 + Upgrade notes + 검증 명령 + SBOM·서명 링크 + 알려진 문제를 합친다.
- 라벨(`kind/*`, `area/*`)은 트리아지·필터용. release-drafter는 쓰지 않는다(DSN-19-11).

### 7.8 지원 버전·백포트 (O-R09)

- 지원 = 최신 마이너(전체) + 직전 마이너(보안·치명, 다음 마이너 출시 후 3개월). `SECURITY.md` 표로 공개, 서버 `/actuator/info`와 UI 정보 화면이 "지원 종료 예정" 표시(릴리스 메타데이터는 빌드에 포함, 외부 조회 없음).
- 백포트: `release/X.Y`로 cherry-pick PR(라벨 `backport/X.Y`), 같은 PR 검사, 패치 태그.
- 폐기: CHANGELOG `Deprecated` + 실행 시 WARN + 문서 → 2마이너 뒤 메이저에서 제거. 공개 계약 목록(O-R03)은 `docs/compatibility.md`.

### 7.9 보안 릴리스 절차 (SEC-90, O-S02)

1. 비공개 접수(GitHub Private Vulnerability Reporting·보안 이메일) → 3영업일 확인 → 10영업일 심각도.
2. 비공개 포크(GitHub Security Advisory 임시 포크)에서 수정 → 지원 중인 모든 마이너에 패치 → `release.yml`로 패치 릴리스(일반 절차와 같음, CHANGELOG `Security`).
3. GHSA 발행·CVE 요청, Discussions Announcements, 릴리스 노트 "Security" 절. 조정 공개 90일.
4. 공개 베타 중 **모의 훈련 1회**(가상 취약점으로 1~3 수행, 소요 시간 기록, Gate 2 항목).

### 7.10 버전 노출 (O-R12)

`argos-server.sh --version`·`java -jar argos-server.jar --version`(버전·커밋·빌드 시각·프로토콜), `/actuator/info`(같은 값 + 지원 종료 예정), UI 정보 화면(서버·UI 버전, 접속 에이전트 버전 분포), 에이전트 기동 배너 1줄 `Argos agent X.Y.Z (commit, protocol N) attached to <app>/<env>`. 값의 출처는 빌드 시 생성되는 `build-info.properties`(Gradle `springBoot.buildInfo` + 에이전트 매니페스트).

### 7.11 `RELEASING.md` 체크리스트

사전: 마일스톤 이슈 0, CHANGELOG `Unreleased` → 버전 절 이동, `docs/upgrade/X.Y.md` 작성, 호환 범위 표 갱신, nightly 7일 녹색, 벤치 최신, 72시간·카오스 아티팩트 존재, 스크린샷 갱신(마이너). 실행: `release/X.Y` 브랜치 → 태그 push → 워크플로 감시 → Release 초안 검토(자산·서명·노트) → 발행 → Announcements → `main` 버전 범프. 사후: 이슈 템플릿 버전 목록 갱신, 지원 종료 마이너 공지. 체크리스트 수행은 Claude Code가 WO로 할 수 있고 태그 push와 발행은 사람이 한다(O-R11).

## 8. 프로젝트 파일 세트 (WO-00 산출)

| 파일 | 내용 개요 |
|---|---|
| `LICENSE` | Apache-2.0 전문 |
| `NOTICE` | `Argos APM` / `Copyright <year> <저작권자(미결 13)>` / 차용 코드 출처 목록(O-L05, 파일별 원 프로젝트·라이선스) / 셰이딩 포함 라이브러리의 NOTICE는 생성물 `THIRD-PARTY-NOTICES` 참조 |
| `README.md`(en) · `README.ko.md` | 한 문단 정의, 스크린샷 3장(Gate 1.5부터), 빠른 시작(`docker compose --profile demo up`), 에이전트 붙이기 3줄, 호환성 요약, 상태 배지(pre-release), 문서·커뮤니티·보안·라이선스 링크, "Telemetry: none" |
| `CONTRIBUTING.md` | 로컬 빌드(`docs/dev-setup.md`), 이슈 선행 원칙, DCO(`git commit -s`), 코드 규약·린트, 테스트 요구(03 문서 공통 DoD 기계 검증 부분 축약 체크리스트), PR 크기, CHANGELOG 작성, 계측 모듈 추가 가이드(패키지 1 = PR 1, README·경계 버전 테스트·fail-safe·witness), 차용 규칙, AI 생성 코드 정책(O-L08), `needs-matrix` 절차, 응답 기대치 7일 |
| `CODE_OF_CONDUCT.md` | Contributor Covenant 2.1 + 집행 연락처(조직 이메일) |
| `SECURITY.md` | 16 문서 초안: 지원 버전 표, 비공개 신고 채널, 기한, 권고 발행 위치, 범위·비범위, 릴리스 서명 공개키·지문 |
| `GOVERNANCE.md` | 단일 메인테이너 모델, 추가·제거 기준, ADR, "라이선스 불변", 개발 방식(사람 기획·검수 + AI 구현) 공개, 러너·비밀값 접근 규칙, 승계(소유자 2인) |
| `ROADMAP.md` | 01 문서 11장 요약 + 마일스톤 링크 |
| `RELEASING.md` | §7.11 |
| `CLAUDE.md` | 기여자용 요약(현행 유지) |
| `.github/ISSUE_TEMPLATE/` | `bug.yml`(버전·JDK·Boot·WAS·OS·진단 번들), `feature.yml`, `compat-report.yml`(라이브러리·DB·WAS·버전·결과·에이전트 로그), `config.yml`(질문 → Discussions) |
| `.github/PULL_REQUEST_TEMPLATE.md` | 연결 이슈, 변경 요약, 테스트 근거, CHANGELOG, DoD 축약 체크, DCO 확인, 차용 코드 |
| `.github/CODEOWNERS` | 핵심 경로(§6.8) → 메인테이너 |
| `.github/dependabot.yml` | gradle·npm·github-actions·docker, 주간 그룹, 보안 즉시 |
| `.github/labeler.yml` | `area/*`, `needs-matrix` |
| 라벨 체계 | `area/agent|server|ui|deploy|docs`, `kind/bug|feature|compat-report|security`, `good first issue`, `help wanted`, `needs-matrix`, `needs-info`, `breaking-change`, `no-changelog`, `backport/X.Y` |
| 브랜치 보호 | main·release/*: PR 필수, 승인 1, 필수 검사(§6.2), 선형 이력, 관리자 포함, 서명 커밋 권장(DCO 필수) |
| Discussions | Announcements, Q&A, Ideas, Show and tell, 한국어 |
| `docs/licenses/dev-only.md` | 테스트·샘플 전용 비호환 라이선스 의존성 목록(MySQL Connector/J, MariaDB, ojdbc, Tibero·Altibase·CUBRID 드라이버, H2, k6)과 "배포물 미포함" 근거 |
| `docs/adr/` | `0000-template.md`, `0001-license-apache-2.0.md`, `0002-identifiers.md`(미결 14 확정 시) 영문 1페이지 |
| `docs/compatibility.md` | §9 |
| `docs/dev-setup.md` | §10 |
| `keys/ARGOS-RELEASE-KEY.asc` | 릴리스 공개키 + 지문 |

## 9. 호환성 약속 문서(`docs/compatibility.md`) 개요

| 절 | 내용 |
|---|---|
| 버전 정책 | SemVer, 0.x 예외, 사전 릴리스 |
| 에이전트↔서버 | 서버 `X.Y` ↔ 에이전트 `X.(Y-2)`~`X.Y`, 메이저 전환 1마이너 유예, 거부 동작 |
| 공개 계약 목록(O-R03) | `argos-agent.yml` 키, 서버 설정 키·환경변수(별칭 포함), REST `/api/v1` 스키마(필드 추가만), WebSocket 프레임, 메트릭 이름(OpenMetrics), 웹훅 페이로드(`schema_version`), CLI 인자, 디렉터리 규약, MDC 키, `tracestate` 키 |
| 폐기 절차 | CHANGELOG Deprecated + WARN + 문서 → 2마이너 → 메이저 제거. 알 수 없는 키는 WARN |
| 스키마 | 전진 전용, 지원 마이너 전체에서 현재로, 롤백 직전 마이너 |
| 지원 범위 | 01 문서 8장 매트릭스 요약 + `docs/compat/matrix-status.md`(nightly 자동) |
| 검증 | 설정 키 스냅샷 테스트(`config-keys.snapshot`), OpenAPI diff, 메트릭 이름 스냅샷, 웹훅 페이로드 스키마 테스트 — 변경은 PR에서 실패 |

## 10. 개발 환경(`docs/dev-setup.md`) 개요

1. 요구: JDK 8(Temurin, 에이전트 테스트 실행용)과 JDK 21, Gradle은 wrapper, Node LTS + pnpm(corepack), Docker 24+(Testcontainers·Compose), git. toolchain 자동 다운로드(Foojay 플러그인) 허용.
2. `git clone` → `./gradlew build`(첫 빌드 목표 30분 이내, 캐시 후 5분) → `pnpm -C ui install && pnpm -C ui dev`.
3. 로컬 실행: `docker compose -f deploy/compose/compose.yaml --profile demo up`(또는 `./gradlew :server:bootRun` + 로컬 ClickHouse·PostgreSQL 컨테이너 `deploy/compose/compose.dev.yaml`).
4. 샘플 앱 + 에이전트: `./gradlew :sample-apps:shop-boot3:bootRun -Pagent=true`(agent-dist jar를 `-javaagent`로 붙임).
5. 테스트: `./gradlew test`, `integrationTest`, 특정 모듈 `:agent:agent-instrumentation:jdbc:test`. `matrixTest`는 로컬에서 조합 지정 `-Pmatrix=boot3:21`.
6. IDE: IntelliJ 권장(Gradle 임포트, Error Prone 플러그인), VS Code(UI).
7. 규칙 요약·링크(CONTRIBUTING, 03 문서, CLAUDE.md).

## 11. 테스트 포인트 (03 문서 2장·3.1 대응)

| 03 항목 | 이 문서의 구현 | 검사 위치 |
|---|---|---|
| 정적 분석 경고 0 | §5 Spotless·Error Prone·ArchUnit·ESLint·tsc | PR 필수 |
| SPDX 헤더 | §5.1 | PR 필수 |
| 라이선스 허용 목록·dev-only | §5.5 | PR 필수 |
| 셰이딩 검사(클래스 패키지·META-INF·Unsafe·52·크기) | §5.6 `check-shading.sh`, §3.3 6~8 | PR 필수, `agent-dist:check` |
| 변환 목록 베이스라인·onError 0 | `agent-testing:test`(10·18 문서 하네스), 베이스라인 파일 `agent-testing/src/test/resources/transform-baseline/*.txt` | PR 필수 |
| `-XX:+BytecodeVerificationLocal` | `integrationTest`·`matrixTest` JVM 인자 | PR·nightly |
| OpenAPI `x-argos-role`·호환 diff | `generateOpenApi` 커밋 검사 + `oasdiff` + 권한 매트릭스 생성 테스트 | PR 필수 |
| CHANGELOG·breaking 라벨 | §6.2 | PR 필수 |
| 비밀 스캔 0 | gitleaks | PR 필수 |
| SBOM 생성·동봉·스캔 | §3.3 5, §4, §6.2 | PR 필수 |
| 재현 빌드 | `verifyReproducible` | 릴리스 게이트 |
| 서명·검증 스크립트 | §7.2·7.3, `deploy/verify-release.sh` | 릴리스 게이트 |
| 매트릭스 nightly | §6.3 | nightly |
| 벤치 보고서 | §6.4 | 주간 |

## 12. 요구 ID 대응표

| 요구 | 대응 |
|---|---|
| O-L01·L02 | §8 LICENSE·NOTICE, DSN-19-12 |
| O-L03 | §5.5, DSN-19-04 |
| O-L04 | §3.3 3~5, §5.5 고지 생성 |
| O-L05 | NOTICE 차용 목록, CONTRIBUTING 차용 규칙 |
| O-L06 | DCO 필수 검사 |
| O-L07 | §5.1 SPDX |
| O-L08 | CONTRIBUTING AI 코드 정책 |
| O-S04 | CodeQL·gitleaks·Dependabot·SBOM 스캔 |
| O-S05 | §6.6 러너 격리 |
| O-G01~G09 | §8 GOVERNANCE·CONTRIBUTING·CoC·템플릿·ROADMAP·Discussions·ADR·조직 소유·라벨 |
| O-R01~R04 | §7.1, §9 |
| O-R05~R07 | §7.2 5~10, §7.6, SBOM |
| O-R08 | §3.7, DSN-19-05 |
| O-R09 | §7.8 |
| O-R10·R11 | §7.7, §7.11 |
| O-R12 | §7.10 |
| O-A02·A04·A05 | README 세트, 문서 언어 규칙, i18n en 기본 |
| O-A07 | README 데모 영상(GA) |
| O-Q01~Q07 | §6.2 필수 검사, 브랜치 보호, §6.8, CONTRIBUTING 리뷰 절차, CLAUDE.md 구조, 트리아지 라벨 |
| O-N02~N04 | §7.5 groupId, 저장소명·이미지명 "Argos APM" 수식어, 미결 14 |
| N-27 | 변환 베이스라인 PR 검사, heavy 샘플 앱 기동 측정(bench) |
| F-A31·F-A35 | §3.3 단일 jar·매니페스트·Unsafe 검사 |
| SEC-70~73, 77 | SBOM, 스캔 CI, 서명, 재현성·잠금·SHA 고정, `argos-deps.txt` |
| SEC-90 | SECURITY.md |
| R-10·R-11·R-13 | 식별자 확정 절차, 조직 소유·키 분리 보관, 라이선스 CI |

## 13. 미결·가정

| # | 항목 | 가정 | 확인 시점 |
|---|---|---|---|
| 1 | GitHub 조직명·저장소명·groupId·도메인(미결 14) | `argos-apm/argos`, `io.github.argos-apm`, 패키지 `io.argos` | WO-00 전(사람) |
| 2 | 저작권자 표기(미결 13) | NOTICE·SPDX 저작권 줄에 `Argos APM contributors` 또는 회사명 | WO-00 전(사람) |
| 3 | Maven Central 발행 플러그인 | `com.vanniktech.maven.publish` 우선, JReleaser 대안. Central Portal 네임스페이스 검증 절차 최신 문서 확인 | WO-00 |
| 4 | Shadow 플러그인 좌표·버전(`com.gradleup.shadow`)과 `minimize()`가 gRPC 리플렉션 로딩 클래스를 제거하지 않도록 하는 exclude 목록 | P-3에서 확정 | Phase 0 |
| 5 | protobuf-javalite 채택(A-1) | P-3 비교 후 | Phase 0 |
| 6 | 프로퍼티 문자열 relocate 처리 방식(`io.netty.noUnsafe` 등) | 10 문서가 "relocate된 이름으로 설정"을 택하면 `checkRelocatedStrings`는 relocate됨을 단언, 반대면 제외 패턴 단언 | 10 문서 |
| 7 | Flyway community 모듈 라이선스 | `flyway-core`·`flyway-database-postgresql` Apache-2.0. 버전 고정 시 재확인 | WO-00 |
| 8 | ArchUnit의 Advice 바이트코드 수준 검사(람다 `invokedynamic`) 가능 여부 | ArchUnit은 메서드 호출 그래프를 제공. `invokedynamic` 감지는 ASM 기반 커스텀 테스트로 보완 | WO-00 |
| 9 | DCO 검사 수단 | DCO GitHub App(조직 설치) 우선, 불가 시 액션 | WO-00 |
| 10 | 사내 self-hosted 러너의 GitHub 연결(아웃바운드 HTTPS 허용 여부) | 허용 가정. 불가 시 매트릭스는 사내 CI에서 돌리고 결과만 커밋 | Phase 0 |
| 11 | 벤치 전용 머신 2대 확보 | 사내 환경 | Phase 0 |
| 12 | 의존성 검증 메타데이터의 PGP 서명 검증 | 1.0은 체크섬만 | 1.x |
| 13 | 문서 사이트 도구 | MkDocs Material(i18n·버전 플러그인) | Gate 1.5 |
| 14 | Error Prone 버전과 JDK 8 toolchain 컴파일 호환 | 에이전트 모듈은 JDK 21 컴파일러로 `--release 8` 컴파일하므로 Error Prone 동작. 테스트 실행만 JDK 8 toolchain | WO-00 |

## 14. 다른 설계 문서에 요구하는 것

| 대상 문서 | 요구 | 이유 |
|---|---|---|
| 10 에이전트 | `AgentClassLoader`의 `inst/**/*.classdata` 로딩 계약(경로 ↔ 클래스명 변환, 리소스 처리), relocate 접두 `io.argos.agent.shaded`, 프로퍼티 문자열 relocate 처리 방식 결정(미결 6), `minimize` 제외 목록(리플렉션 로딩 클래스), 매니페스트 속성 소비 방식, 변환 베이스라인 파일 형식, 기동 배너 문자열 | §3.3 조립·검사와 일치 |
| 18 테스트 | 태스크 5종(§3.6)의 소스셋·태그 규약, `matrixTest`의 조합 선택 파라미터, ArchUnit 규칙 구현 위치, 벤치 스크립트가 생성하는 `docs/bench` 보고서 형식, 72시간·카오스 아티팩트 형식 | CI 잡이 호출 |
| 16 보안 | SECURITY.md 본문, 릴리스 GPG 키 생성·보관·교체 절차, CVE 예외 문서 형식, SBOM 스캔 예외 승인 흐름, `x-argos-role` 스펙 확장 정의 | §7.4·7.9·6.2 |
| 17 배포 | `tarDist`·`airgapBundle` 입력 레이아웃·스크립트 목록 확정, `compose.yaml` 다이제스트 치환 자리표시자 형식, 데모 이미지 2종 사양 | §3.5 태스크 |
| 12 서버 | OpenAPI 스펙 생성 태스크의 소스(코드 애노테이션 vs 스펙 우선), `/actuator/info` 빌드 정보 필드, 지원 종료 예정 표시 로직, 설정 키 스냅샷 테스트 | §7.10, §9 |
| 14 API | `docs/api/openapi.yaml` 커밋 규칙과 `oasdiff` breaking 판정 기준(필드 추가 허용), 생성 TypeScript 클라이언트 출력 경로 | §4, §6.2 |
| 15 UI | 공통 컴포넌트 경유 강제를 위한 `no-restricted-imports` 대상 목록, i18n 파일 구조, 외부 리소스 0 검사 예외(없음 기대) | §4, §5.4 |
| 02 아키텍처 | §10 테스트 전략 표에 "PR/nightly/주간/릴리스" 실행 구분을 §3.6 태스크 이름으로 통일 | 용어 일치 |
| 01 기획 | 미결 13·14를 WO-00 착수 조건으로 명시(이미 "WO-00 전"), ADR-0001·0002 산출을 Gate 0 항목으로(03 문서에 반영됨) | 확인 |
