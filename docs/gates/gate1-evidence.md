# Gate 1 증빙 지도

03 문서 4장 Gate 1 표의 12개 행마다 무엇이 증빙이고(테스트 ID·문서), 결과가 어디서 오고(PR CI 잡, nightly, 전용 장비, 사람), 어떤 명령으로 다시 만들며, 지금 상태가 무엇인지, 그리고 ADR-0010이 사람에게 남긴 확인 항목(C-243~C-257의 '사람 확인')이 어느 행에 걸리는지를 한 쪽에 모은다. 메인테이너는 이 쪽을 위에서 아래로 따라가며 Gate 1을 판정한다(WO-53, 04 문서 "Gate 1 판정" 표).

이 문서의 테스트 ID는 모두 18 문서 카탈로그에 있어야 한다(`checkTraceability` 규칙 R4). 요구 ↔ 테스트 대응은 18 §16, 비시험 증거는 [`test/traceability/evidence.yaml`](../../test/traceability/evidence.yaml), 규칙은 [test/README.md 6장](../../test/README.md)에 있다.

## 다시 만들기

```
./gradlew checkTraceability traceabilityReport gate1Report -Pargos.ui.skip=true
#   build/reports/traceability-check.md(.json)  R1 카탈로그·R2 18 §16.1 diff·R3 필수 요구 누락·R4 이 문서의 ID
#   build/reports/traceability.md(.json)        요구 → 테스트 → 마지막 결과(JUnit·Vitest·Playwright·셸 TAP·매트릭스)
#   build/reports/gate1.md                      NFR 행(N-01~N-07, N-15, N-23~N-30)
./gradlew checkTraceability -Ptraceability.results=none -Pargos.ui.skip=true   # 깨끗한 체크아웃(CI와 같다): 결과 무시, 전부 NOT RUN
./gradlew gate1Report -Pgate1.publish=true -Pargos.ui.skip=true                # docs/bench/gate1.md 갱신(전용 장비 보고서 승인 뒤)
```

결과는 이 작업 트리에 있는 결과 파일만 읽는다. PR CI의 `traceability` 잡은 결과 파일 없이 돌아(전부 NOT RUN) 카탈로그·대응표·필수 요구만 판정한다. 테스트 결과 자체는 각 CI 잡(빌드·통합·보안·E2E·nightly)과 전용 장비 보고서가 증빙이다.

## 현재 상태

2026-11-26 WO-53 통합 단계 최종 실행(이 작업 트리: HEAD eb9693b + 미커밋 쌍 16~20 + WO-52 진행 중 + WO-53). 결과 파일은 이날 이 노트북에서 만든 것이다: `:build-logic:test`, 셸 실행기 4개와 verify-release(TAP 34개), `pnpm -C ui test`(Vitest 2,858건), `pnpm -C ui e2e:preview`(Playwright 18건) + 이전 fresh-install 결과 파일, 기존 `build/matrix` 10개, 이전에 남은 다른 JUnit 결과(부분 실행으로 지워진 태스크는 NOT RUN).

| 항목 | 결과 포함 | `-Ptraceability.results=none` |
|---|---|---|
| R1·R2·R3·R4 | 0·0·0·0 | 0·0·0·0 |
| 필수 요구 대응(D-01~D-26, N 33개, 06 단계 M SEC 42개) | 101 / 101 | 101 / 101 |
| 카탈로그 행 / ID(docs/18) | 521 / 1,202 | 521 / 1,202 |
| 쓰인 테스트 ID | 1,027 | 1,003 |
| 마지막 결과(모든 시험) | PASSED 4,803, FAILED 0, SKIPPED 27, NOT RUN 1,609 | 전부 NOT RUN(5,530) |
| 출처별(결과 파일 / PASSED / NOT RUN) | JUnit 725 / 3,389 / 1,279, Vitest 1 / 880 / 0, Playwright 2 / 9 / 244, 셸 TAP 34 / 515 / 33, 매트릭스 10 / 10 / 53 | 결과 파일 0 |
| 경고(실패 아님) | 번호 규칙 29, 부분 덮임 77, 대응표 ID를 접미 ID만 덮음 8, 자동 SEC를 다른 ID 시험만 덮음 1(SEC-77), 동적 이름만 1, 형식 오류 ID 1(T-SEC-01a 시험 이름 속 예시 문자열), 증거와 소스 중복 22(합 139) | 같은 규칙, 부분 덮임 161, 동적 이름만 2(합 224) |

NFR 행은 [docs/bench/gate1.md](../bench/gate1.md)(결과 없이 생성): 전용 장비 성능 행 13개가 모두 'no evidence (INVALID)'(승인된 보고서 없음), CI 행 N-15·N-23·N-25는 NOT RUN. 이 작업 트리 결과로 만들면 N-15·N-23은 'NOT DECIDED (partly run)'(단위 시험만 PASSED, 지연을 재는 `IngestLatencyIT`·`RealtimeE2eIT`·`AlertDeliveryLatencyIT` 등은 NOT RUN이라 'Why not decided' 칸에 파일이 나온다), N-25는 NOT RUN이다(T-SV-031 `ConfigPushScaleIT` 결과 없음). CI 행은 그 ID를 단 시험이 모두 결과를 가져야 PASS다. 노트북 결과라 CI 증빙은 아니다.

## 1. 기능

- **기준**: 01 문서 6.1의 M 항목 전부 DONE.
- **증빙**: 04 문서 WO 상태 표(WO-10~WO-19, WO-20~WO-28, WO-30~WO-39 DONE)와 `build/reports/traceability.md`의 F-xxx 요구별 테스트·마지막 결과. 화면 WO는 05 §2 필수 요소 대조(각 WO 보고서).
- **결과 출처**: PR CI `build`·`integration`·`e2e`(4샤드) 잡, nightly. 요구별 시험 목록은 traceabilityReport.
- **현재 상태**: 04 상태 표가 아직 전부 TODO로 남아 있다(PM이 DONE으로 갱신할 몫, 사람 확인). Phase 1 WO는 쌍 1~20 보고서로 수락됨.
- **사람 확인**: 04 상태 표 갱신, M 화면 수락은 12행(수락)과 함께.

## 2. 보안(MVP)

- **기준**: 06 단계 M의 SEC 전부 DONE, 위협 모델 초안, 권한 매트릭스 테스트 녹색, 기본 자격증명 없음·DB 포트 비노출 상태의 도그푸딩.
- **증빙**: `checkTraceability` R3의 SEC (M) 행 전부(자동 SEC는 T-SEC 시험 ID, 수동은 태그된 시험·증거 항목). 권한 매트릭스 T-API-010·T-SEC-49(`:server:contractTest`의 permission-matrix.md, `:server:securityTest`의 permission-matrix-session.md), 위협 모델 초안 `docs/security/threat-model.md`와 T-SEC-91a(ST-91-1 ArchUnit), 기본 자격증명·포트 T-SEC-74·T-SEC-75(`deploy/compose/test/run-all.sh`, `:deploy:composeSmoke`), 보안 스위트 카탈로그 T-SEC-93a.
- **결과 출처**: PR CI `security-light`·`sbom-scan`, nightly `securityTest`, Compose 셸 스위트(TAP 결과 `build/test-results/shell/deploy-compose/`).
- **현재 상태**: SEC (M) 전부 대응(R3 0). SEC-77은 06이 '자동'이지만 T-SEC-77 ID를 단 시험이 없고 `req:SEC-77` 태그 시험(T-BLD-003, `CheckShadingScriptTest`)만 있어 경고(`sec-without-id`)로 남는다.
- **사람 확인**: ADR-0010 C-250 (8) GitHub CI의 security-light·sbom-scan·e2e 샤드 1 docker-fresh 실행과 timeout 20분, 첫 녹색 뒤 sbom-scan 필수 검사(C-249 (2)), TestsslSecurityTest Linux 경로, threat-model·asvs 내용 검토(Gate 2), SEC-70/71 실제 PR 게이트, SEC-74/75 도그푸딩. C-253 (10) CodeQL 설정·결과 분류, Dependabot, secrets-scan 주간, sbom-rescan 첫 실행, branch-protection.sh 실제 적용, argos-trusted 러너 그룹·environment 리뷰어.

## 3. 정확성

- **기준**: D-01~D-26이 각각 자동 테스트로 고정되어 통과, 야간 정합 검사가 도그푸딩 기간 불일치 0.
- **증빙**: T-ACC-01~26(18 §10, D-nn과 번호 일치, D-10은 WO-53의 T-ACC-10 `ErrorClassificationTest`). 야간 정합은 `admin consistency check`·`reconcile_results`(WO-27)와 WO-42 운영 기록(읽기 전용 보고 T-BLD-030).
- **결과 출처**: PR CI `build`(단위)·`integration`, nightly(통합·E2E), 도그푸딩 서버의 야간 잡 기록(비공개 저장소).
- **현재 상태**: D-01~D-26 전부 대응(R3 0). 번호 규칙(T-ACC-nn ↔ D-nn)과 다른 요구를 다는 시험은 `id-number` 경고로만 보고한다.
- **사람 확인**: ADR-0010 C-255 (10) 시스템 2개 이상 14일 연속 운영 기록과 야간 정합 불일치 0(Gate 1).

## 4. 매트릭스

- **기준**: 02 유효 조합표 기준 JDK × Spring Boot × Tomcat(내장·독립), 가상 스레드 on/off, JDBC 5종.
- **증빙**: `test/matrix/matrix.yaml`의 T-MX 행 전부(N-13, 18 §6.1과 부록 A), 결과 `build/matrix/T-MX-*.json`, 표 [docs/compatibility-matrix.md](../compatibility-matrix.md)(`matrixReport`).
- **결과 출처**: nightly 매트릭스(self-hosted `argos-matrix`, WO-50). 로컬은 `./gradlew matrixTest -Pmatrix.combo=<id>`.
- **현재 상태**: N-13 대응(매트릭스 행이 소스로 들어감). 결과는 이 작업 트리에 있는 `build/matrix` 파일만 반영되고 나머지는 NOT RUN.
- **사람 확인**: ADR-0010 C-256 (8) argos-matrix 러너(Linux x86_64, cgroup v2, 동시 4잡, 잡당 16 GiB 이상), environment trusted 비밀·변수, 전체 nightly 첫 실행과 △ 행 판정, 사내 Oracle 19c·Tibero·Altibase·CUBRID·DB2, 옛 JRE·Linux 전용·cgroup v1 행. C-240 (12) 매트릭스 빈칸.

## 5. NFR

- **기준**: N-01~N-07·N-15·N-23~N-30 실측 보고서 `docs/bench/gate1.md`.
- **증빙**: [docs/bench/gate1.md](../bench/gate1.md)(`gate1Report`). 행별 출처: T-NFR-01~04·26~30(WO-51 벤치 조각 `docs/bench/gate1-agent-*.md`), N-05 T-CH-001~003(WO-52 카오스), T-NFR-06(WO-29 `docs/bench/server-load.md`), T-NFR-07·24(WO-23 apiBench, C-180 웜 중앙값 ≤ 목표 + 콜드 ≤ 2배), N-15 T-SV-020, N-23 T-SV-030, N-25 T-SV-031(CI 결과).
- **결과 출처**: 성능 행은 전용 장비(argos-bench-app·argos-bench-load·argos-storage, 13 §7.1 목표 장비)의 `"approved": true` 보고서만(ADR-0010 C-252 (5)). 지연 행 N-15·N-23·N-25는 PR CI `integration`·nightly의 결과.
- **현재 상태**: 승인된 전용 장비 보고서가 없어 성능 행 13개가 'no evidence (INVALID)'. 노트북 측정(server-load smoke, apiBench ci)은 참고값이다.
- **사람 확인**: ADR-0010 C-252 (11) 전용 장비 준비(governor performance, cgroup v2, ssh 키, k6 v2.3.0, ARGOS_BENCH_LOADER·ARGOS_BENCH_APP_ADDRESS)와 5시간 전체 프로토콜 1회, 첫 주간 bench.yml 실행·PR·approved, T-NFR-01~04·26~30 판정. C-240 (7) N-30은 전용 장비 1회(PremainTimeAttachTest는 참고). C-244 (7)·(10) 목표 장비 serverLoadTest full(1시간, 공격 전체 규모)과 17 미결 9·§7.3 비율, 32GiB 실행, 전용 디스크 WAL fsync·세션 메모리, VT 핀닝. C-245 (1) 그 full 실행의 class histogram으로 알림 창 메모리 재확인. C-180 argos-storage에서 생성기 full 30일 apiBench enforce. 각 보고서의 요약 주석에 `"approved": true`(docs/bench/README.md "Approval") 뒤 `./gradlew gate1Report -Pgate1.publish=true`.

## 6. 도그푸딩

- **기준**: 사내 시스템 2개 이상, 2주 연속 운영, 에이전트 원인 장애 0, 서버 재시작 없이 2주. 기록은 비공개 저장소, 공개는 익명화 요약.
- **증빙**: WO-42 현장 절차와 읽기 전용 운영 보고 T-BLD-030(`deploy/compose/ops-report.sh`, `:deploy:composeSmoke`의 `ops_report_check`).
- **결과 출처**: 사람(비공개 저장소의 운영 기록), 보고 스크립트 출력.
- **현재 상태**: 운영 기록 없음(사람 확인).
- **사람 확인**: ADR-0010 C-255 (10) T-ACPT-07·08 수행, 시스템 2개 이상 14일 연속 운영 기록과 야간 정합 불일치 0, 절차 9단계 U-18·U-19·U-20·N-19·SEC-74/75·C-240 (12), 비공개 기록 저장소 채우기, 첫 현장 보고서 2인 익명화 검토.

## 7. 장시간

- **기준**: 72시간 부하 테스트 통과(힙 기울기 0, NMT 증가 ≤ 16MB, FD·스레드 평탄).
- **증빙**: T-SOAK-001(18 §11.2, WO-52 `soakTest`), 보고서 `docs/bench/soak-<date>.md`.
- **결과 출처**: 전용 장비(WO-52 러너, self-hosted 주간·수동).
- **현재 상태**: WO-52 진행 중. `evidence.yaml`에 deferred로 등록되어 있고, WO-52 러너 단위 시험(T-SOAK-001a, 이 작업 트리 결과 PASSED 23)이 먼저 보인다. 기본 ID T-SOAK-001은 접미 ID로만 덮인다(경고 partial-mapping). 72시간 실행 보고서는 없다.
- **사람 확인**: 전용 장비 72시간 1회 실행과 보고서 승인(WO-52 보고서가 정한다).

## 8. 카오스

- **기준**: 서버 중단 1시간·지연 500ms·손실 10%·5xx·디스크 풀·DNS 실패·인증서 불일치·instance_id 재부여에서 앱 p99 변화 ≤ 잡음, 복구 후 1분 내 재접속.
- **증빙**: T-CH-001~008(18 §11.1, 8개 시나리오), T-CH-009(ClickHouse 30분 중단), 러너 단위 시험 T-CH-020a~i, 보고서 `docs/bench/chaos-<date>.md`.
- **결과 출처**: 전용 장비 `chaosTest`(WO-52).
- **현재 상태**: WO-52 진행 중. 시나리오 시험 T-CH-001·002·003·009가 소스에 있고 이 작업 트리의 짧은 실행(노트북, 참고) 결과는 PASSED 4·SKIPPED 1(T-CH-003 한 변형)이다. N-05(T-CH-001~003)는 `gate1Report`가 승인된 카오스 보고서에서만 판정하므로 'no evidence (INVALID)'.
- **사람 확인**: 전용 장비 실행과 보고서 승인(WO-52 보고서가 정한다).

## 9. 설치

- **기준**: Docker Compose 단일 노드 설치 가이드로 프로젝트 비참여자 설치 성공.
- **증빙**: N-11 비시험 증거(`evidence.yaml`, T-ACPT 설치), 자동 부분은 Compose 셸 스위트(`deploy/compose/test/run-all.sh`, 기본 자격증명 T-SEC-74·포트 T-SEC-75, 업그레이드 T-NFR-11a)와 `:deploy:composeSmoke`.
- **결과 출처**: PR CI `compose` 셸 스위트(TAP 결과), 사람(비참여자 설치 기록).
- **현재 상태**: 자동 부분은 이 작업 트리 셸 TAP 결과(deploy-compose 7개, 2,856 ok·0 not ok·SKIP 4)에서 T-SEC-75 PASSED 51, T-NFR-11a PASSED 56, T-SEC-74 PASSED 37(NOT RUN 4는 `composeSmoke` 등 TAP를 남기지 않는 경로). 비참여자 설치 기록 없음.
- **사람 확인**: 비참여자 1인이 docs/install/compose.md만 보고 30분 안에 설치(N-11). ADR-0010 C-247 (13) Linux Docker Engine 실스택의 백업 세트 0700/0600·진단 묶음·upgrade-state.json 모드, C-245 (9) macOS·WSL permissive 기동.

## 10. 고지

- **기준**: 에이전트 jar·서버 tar에 NOTICE·THIRD-PARTY-NOTICES·버전 매니페스트·SBOM이 빌드로 생성돼 포함.
- **증빙**: T-BLD-003(에이전트 jar), T-BLD-003a~c(서버 jar·이미지·About 목록), T-BLD-008·T-BLD-008a(SBOM·스캔), T-BLD-012(NOTICE 린트).
- **결과 출처**: PR CI `build`(`verifyAgentJar`·`verifyServerJar`·`checkThirdPartyNotices`), `sbom-scan`.
- **현재 상태**: 이 작업 트리 결과에서 T-BLD-003 PASSED 17, T-BLD-003a 6, T-BLD-003b 1, T-BLD-003c 1, T-BLD-008 4, T-BLD-008a 1, T-BLD-012 6, FAILED 0(2026-11-26 traceabilityReport).
- **사람 확인**: ADR-0010 C-246 (13) 중 발행물 확인(GHCR 패키지 공개 전환 뒤 이미지 고지 확인).

## 11. 릴리스

- **기준**: 0.x 태그가 릴리스 파이프라인(빌드 → 서명 → GitHub Release pre-release → GHCR)으로 발행됨.
- **증빙**: release.yml 구조 T-BLD-020, 검증 스크립트 T-BLD-018(`deploy/verify-release.sh`), 릴리스 노트 T-BLD-019, 재현성 T-BLD-009·T-BLD-009a, 이미지 T-BLD-022, 자산 T-BLD-023, 버전 플래그 T-BLD-021.
- **결과 출처**: PR CI `lint`(verify-release 셸 시험)·`build`, 태그 푸시의 release.yml 실행(GitHub).
- **현재 상태**: 파이프라인 구조 시험만 있다. 실제 0.x 발행 없음.
- **사람 확인**: ADR-0010 C-246 (13) 조직 GPG 키 설치(keys/·verify-release.sh 상수·SECURITY.md 지문·Environment 'release' 비밀) 뒤 check-release-key.sh 엄격 모드, 0.1.0 pre-release 리허설(CHANGELOG [0.1.0] → 태그 → 초안 검토·발행), cosign keyless·attestation·GHCR 멀티아키·두 러너 재현성·CI Trivy, GHCR 패키지 3개 공개 전환, 발행 뒤 C-245 (2)·C-172 (2)·C-243 (8). C-248 추가 확인. C-249 (11) gh auth로 branch-protection.sh 적용(새 필수 검사 `traceability` 포함)과 필수 검사 이름 대조.

## 12. 수락

- **기준**: 모든 M 화면의 수락 시나리오(S-01~S-18 중 M 범위)를 사람이 통과.
- **증빙**: T-ACPT-01~18(18 §14, `evidence.yaml` human). 자동화된 부분은 18 §14 표의 셋째 열(예: T-ACC-12, T-ACC-25, T-E2E-stats-25~27, T-E2E-admin-20~29).
- **결과 출처**: 사람(WO-42 환경, 각 체크리스트 15분 이내).
- **현재 상태**: 수행 기록 없음.
- **사람 확인**: ADR-0010 C-255 (10) T-ACPT-07·08 수행, 나머지 M 범위 체크리스트. C-243 (8) 데모 60초 판정·비참여자 2인 5분은 Gate 1.5 몫.

## 공통: 추적성 CI 잡

- PR CI `traceability` 잡(`.github/workflows/pr.yml`)이 `checkTraceability traceabilityReport gate1Report -Pargos.ui.skip=true`를 결과 파일 없이 돌려 R1~R4만 판정하고 보고서 3종을 아티팩트로 남긴다(보존 90일). 시험 결과가 없으므로 FAILED가 아니라 NOT RUN이다.
- **사람 확인**: GitHub Actions에서의 첫 녹색 실행, 브랜치 보호의 필수 검사로 `traceability` 적용(WO-53 PM 질문 1 가정, C-249 (11)).
