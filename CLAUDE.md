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
- 모호한 점은 가정을 명시하고 진행한 뒤 보고서 "가정과 미해결"에 남긴다. 단, **02 문서의 전역 설계 결정(DSN)이나 설계 문서를 바꿔야 하면 멈추고 묻는다.** 바뀌면 ADR을 남긴다.
- **배포물에 포함되는 의존성은 Apache-2.0 호환 허용 목록만**(Apache-2.0, MIT, BSD, ISC, 0BSD, PostgreSQL). GPL·LGPL·AGPL·SSPL·BSL은 금지. 테스트 전용 드라이버는 배포물에 들어가지 않는 조건으로 허용하고 `docs/licenses/dev-only.md`에 기록한다.
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
                                                  # checkLicenses, checkSpdx, build-logic 테스트, UI test·lint·typecheck
./gradlew spotlessApply                           # Java 포맷·SPDX 헤더 자동 수정
./gradlew :agent:agent-dist:agentJar              # 에이전트 단일 jar → agent/agent-dist/build/libs/argos-agent-<버전>.jar
./gradlew :agent:agent-dist:check verifyAgentJar  # checkShading, checkMajorVersion(52), checkRelocatedStrings
./gradlew :agent:agent-testing:archTest           # Advice·패키지 경계 규칙(T-AG-001~004, ADV-04, B-1~B-4, AGT-01~03)
./gradlew checkLicenses checkDevOnlyLicenses      # 라이선스 허용 목록(테스트 전용 예외: docs/licenses/dev-only.md)
./gradlew integrationTest                         # Testcontainers(Docker). check에 포함되지 않음
./gradlew securityTest                            # 보안 테스트 스위트. check에 포함되지 않음
./gradlew matrixTest -Pmatrix.combo=<id>          # 호환성 매트릭스 1개 조합을 로컬 실행(Docker·해당 JDK 필요). 전체 매트릭스는 신뢰 러너 전용
./gradlew benchTest -Pbench.machine-profile=<id>  # 오버헤드 벤치(벤치 머신 전용)

pnpm -C ui install --frozen-lockfile
pnpm -C ui build && pnpm -C ui test               # build = tsc + vite + 번들 라이선스 검사 + 외부 참조 검사
pnpm -C ui lint && pnpm -C ui typecheck && pnpm -C ui format:check            # ESLint, tsc strict, Prettier
pnpm -C ui check:i18n && pnpm -C ui check:external && pnpm -C ui check:licenses  # i18n 키, 외부 참조, 라이선스
pnpm -C ui check:licenses:dev                     # 허용 목록 밖 UI 개발 도구가 dev-only.md `npm:` 행에 있는지

bash test/lint/check-spdx.sh                      # SPDX 헤더 전수(T-BLD-007)
bash test/agent/check-shading.sh agent/agent-dist/build/libs/argos-agent-0.0.0-SNAPSHOT.jar
bash .github/scripts/check-workflows.sh           # 워크플로 정책(pull_request_target·self-hosted 금지, SHA 고정)
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
