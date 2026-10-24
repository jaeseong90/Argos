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
| 결정 | [adr/](adr/) | 0000~0002 | ADR 템플릿, 0001 라이선스 Apache-2.0, 0002 식별자·공개 시점 (메인테이너 확인 대기) |
| 라이선스 | [licenses/dev-only.md](licenses/dev-only.md) | WO-00 | 배포물에 넣지 않는 테스트·도구 전용 구성요소 목록(`checkDevOnlyLicenses`가 읽음) |
| PoC | [poc/xlog-ui.md](poc/xlog-ui.md) | WO-04 | XLog 캔버스 PoC: 접근 방식, 프레임 시간 측정(1만·5만·10만 점), 15 §18 #2 답, 사람 재현 절차 |

## 저장소 상태

- **WO-00(저장소 골격 + 오픈소스 골격)**: REVIEW(구현 완료·완료 보고 제출, 사람 수락 검수 대기, 2026-10-23). 04 문서 상태 표는 사람이 갱신한다. Gradle 9.8.2 모노레포(Java 모듈 22개, 빈 소스 + hello 테스트), UI 골격(Vite + React + TypeScript strict + pnpm), 품질 게이트(Spotless·Error Prone·ArchUnit·라이선스 허용 목록·SPDX·`check-shading.sh`), CI 워크플로(`pr.yml`·`codeql.yml`·`secrets-scan.yml`, GitHub에서는 아직 실행하지 않음), 오픈소스 파일 세트가 있다. 기능 코드는 아직 없다.
- **WO-04(XLog 캔버스 PoC)**: REVIEW(구현 완료, 사람 수락 대기, 2026-10-24). 데모 `/xlog-poc`, `ui/src/xlog/` 캔버스, Vitest, Playwright E2E(드래그 → 요약·목록, 통과), `ui/perf/xlog-frame.spec.ts`가 있다. 15 §7.2 원안의 색별 3패스 fill은 5만·10만 점에서 16ms를 넘고, 15 §18 #2의 대안 `ImageData`(`render=pixels`)는 통과한다. 기본 그리기 방식은 ADR-0003 C-34로 `pixels`로 결정되었고 코드 기본값에 반영되었다(판정 단위·상호작용 예산은 C-35·C-36, [poc/xlog-ui.md](poc/xlog-ui.md) 4.7.3·8장).
- **WO-01(샘플 앱 세트 + ext-stub + 부하 생성기)**: REVIEW(구현 완료·r1 리뷰 수정 반영, PM 결정·사람 수락 대기, 2026-10-24). `sample-apps/`에 공유 카탈로그(`common`), 앱 8종(`shop-boot2`·`shop-boot3`·`shop-boot4`·`shop-heavy`·`shop-batch`·`shop-war-javax`·`shop-war-jakarta` + `ext-stub`), 스모크 러너와 `sampleSmoke` 태스크(에이전트 없이 기동해 카탈로그 검증, `check` 미포함), Dockerfile, k6 `load/bench.js`·`run-bench.sh`, 의존성 없는 데모 부하 생성기, `test/seed/seed-traffic.sh`가 있다. 앱별 jar·클래스 수와 에이전트 없는 기동 기준선(N-04·N-27)은 [sample-apps/README.md](../sample-apps/README.md) "Startup baseline" 절에 있다(공유 Windows 머신 측정, 벤치 머신 재측정 필요).
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
