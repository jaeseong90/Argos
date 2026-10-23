# ADR-0002: 이름·식별자 세트와 저장소 공개 시점

| 항목 | 내용 |
|---|---|
| 상태 | Accepted (provisional, maintainer confirmation pending) |
| 날짜 | 2026-10-23 |
| 결정자 | PM(메인테이너 위임 결정). 메인테이너 확인 대기 |
| 관련 | 01 문서 미결 9(패키지)·14(이름·식별자)·15(공개 시점), O-N01~O-N04, O-G08, O-R03, R-10, R-11, 19 문서 §7.5·§7.6·§13 #1, 03 문서 Gate 0 "오픈소스 골격" |

## English summary

The product is named "Argos APM". The GitHub organization is `argos-apm` and the repository
`argos-apm/argos`; the Maven coordinates are `io.github.argos-apm:argos-agent`, the Java package root
is `io.argos`, images are `ghcr.io/argos-apm/argos-server`, `argos-demo-app` and `argos-demo-load`,
configuration uses the `argos.*` / `ARGOS_*` prefixes, trace propagation uses the `tracestate` key
`argos`, the MDC key is `argos.trace_id`, shaded dependencies live under `io.argos.agent.shaded`, and
the development version is `0.0.0-SNAPSHOT`. These identifiers are public contracts and cannot change
after 1.0. The `argos-apm` organization does not exist yet and must be created by the maintainer;
nothing has been pushed or published, and the publication date is the maintainer's decision.

## 맥락

- 식별자는 공개 계약(O-R03)이라 1.0 이후 바꿀 수 없고, WO-00 전에 한 번에 확정해야 한다(O-N02).
- "Argos"는 Argo CD·Argo Workflows(argoproj), argos-ci, Argos Translate와 검색에서 겹친다(R-10, O-N01).
  상표가 아니라 발견 가능성의 문제이므로 저장소·패키지·이미지·사이트 제목에 "Argos APM" 수식어를 일관되게
  붙이고, 코드·설정에서는 `argos`만 쓴다(O-N04).
- 도메인을 보유하지 않으므로 Maven Central에서 `io.argos` groupId를 쓸 수 없다(O-N03). Sonatype Central
  Portal은 `io.github.<조직>` 네임스페이스를 GitHub 조직 소유로 검증한다.
- 프로젝트 자산은 개인이 아니라 GitHub 조직 소유여야 한다(O-G08, R-11).
- 현재 작업 원격 저장소는 `github.com/jaeseong90/Argos`(개인 계정)이며 첫 커밋만 있는 상태로 **공개 접근이
  가능하다**. `argos-apm` 조직은 아직 등록되지 않았다.

## 결정

### 식별자 세트(01 미결 14, 미결 9 포함)

| 대상 | 값 | 비고 |
|---|---|---|
| 제품 이름 | `Argos APM` | 문서·README·이미지 라벨·사이트 제목. 코드·설정은 `argos` |
| GitHub 조직 | `argos-apm` | 미등록. 메인테이너가 생성(소유자 2인, 2FA 강제) |
| 저장소 | `argos-apm/argos` | `jaeseong90/Argos`에서 이전 예정 |
| Maven groupId | `io.github.argos-apm` | `gradle.properties`의 `group`. Central Portal의 GitHub 조직 검증 |
| Maven artifactId | `argos-agent` | `agent-dist` 셰이딩 jar |
| Java 패키지 루트 | `io.argos` | 에이전트 bootstrap `io.argos.agent.bootstrap`, 서버 `io.argos.server` |
| 컨테이너 이미지 | `ghcr.io/argos-apm/argos-server`, `ghcr.io/argos-apm/argos-demo-app`, `ghcr.io/argos-apm/argos-demo-load` | linux/amd64·arm64 |
| 설정 접두사 | `argos.*`(파일 키·시스템 프로퍼티), `ARGOS_*`(환경변수) | |
| 전파 `tracestate` 키 | `argos` | |
| MDC 키 | `argos.trace_id` | |
| 셰이딩 접두사 | `io.argos.agent.shaded` | 19 문서 DSN-19-03, `check-shading.sh` |
| 버전 | `0.0.0-SNAPSHOT` | 태그 빌드에서 `-Pversion=X.Y.Z`(DSN-19-10) |

### 공개 시점(01 미결 15)

- 공개 시점은 **메인테이너가 결정한다**. 이번 작업(WO-00)은 아무것도 push·발행하지 않는다.
- 01 문서 미결 13은 "확정 전 저장소 비공개"를 요구하므로, 현재 공개 상태인 `jaeseong90/Argos`를 확정
  전까지 비공개로 돌릴지 메인테이너가 판단한다(아래 확인 사항).
- 공개하더라도 Gate 1 전에는 릴리스·홍보를 하지 않고 README에 "pre-release, unsupported"를 표시한다
  (01 문서 §11 추천안, README 반영 완료).

## 검토한 대안

| 대안 | 장점 | 단점 | 채택하지 않은 이유 |
|---|---|---|---|
| groupId `io.argos` | 짧고 패키지와 일치 | 도메인 미보유로 Central 네임스페이스 검증 불가 | O-N03 |
| 개인 계정 저장소 유지(`jaeseong90/Argos`) | 추가 작업 없음 | 자산이 개인 소유, 승계 불가 | O-G08, R-11 |
| 셰이딩 접두사 `argos.shaded` | 짧음 | 패키지 루트 `io.argos` 밖에 클래스가 생김, `check-shading.sh` 규칙과 불일치 | 19 문서 §5.6·DSN-19-03 |
| 이름 변경 | 검색 충돌 해소 | 기획·설계 문서 전면 수정 | "Argos APM" 수식어로 대응(O-N04), 필요 시 공개 전 재검토 |

## 결과

- 이후 모든 WO는 위 값을 그대로 쓴다. 바꾸려면 이 ADR을 대체하는 ADR이 필요하고, 1.0 이후에는 바꿀 수 없다.
- README·이슈 템플릿·SECURITY.md·CODEOWNERS의 링크는 `github.com/argos-apm/argos`를 가리킨다. 조직과
  저장소가 만들어지기 전까지 이 링크는 동작하지 않는다.
- CODEOWNERS는 조직 팀이 생기기 전까지 메인테이너 개인 계정(`@jaeseong90`)을 쓴다.

## 확인 사항

메인테이너가 확인하고 결과를 기록한 뒤 상태를 "Accepted"로 바꾼다.

- [ ] 이름 "Argos APM"과 위 식별자 세트 확정(01 미결 14). 1.0 후 변경 불가임을 확인
- [ ] GitHub 조직 `argos-apm` 가용 여부 확인·생성, 소유자 2인(백업 메인테이너, 01 미결 17), 2FA 강제
- [ ] 저장소 `argos-apm/argos` 생성 또는 `jaeseong90/Argos` 이전, 기본 브랜치 `main`
- [ ] 조직과 저장소 생성 후 `docs/dev-setup.md` §2의 "조직·저장소 미존재" 노트 삭제
- [ ] 확정 전까지 `jaeseong90/Argos`를 비공개로 돌릴지 결정(현재 공개, 첫 커밋만 있음)
- [ ] 공개 시점 결정(01 미결 15)
- [ ] Sonatype Central Portal에서 `io.github.argos-apm` 네임스페이스 검증(첫 Maven Central 발행 전)
- [ ] GHCR 패키지 이름 3종을 조직 명의로 사용 가능한지 확인
- [ ] 저장소 설정: Private Vulnerability Reporting, Dependabot security updates, secret scanning push
      protection, Discussions(Announcements·Q&A·Ideas·Show and tell·한국어) 활성화,
      "Require approval for all outside collaborators"
- [ ] 브랜치 보호(main·`release/*`, 19 문서 §8·O-Q01): PR 필수, 승인 1, **선형 이력(Require linear history)
      필수**, 관리자 포함, 필수 검사 = `build-java`, `build-ui`, `lint`, `license`, `agent-dist-checks`,
      `integration`, `security-light`, `openapi-diff`, `changelog`, `dco`(이상 `pr.yml`), `analyze (java-kotlin)`,
      `analyze (javascript-typescript)`(`codeql.yml`), `gitleaks`(`secrets-scan.yml`).
      선형 이력은 선택이 아니다. 머지 커밋이 보호 브랜치에 들어가지 않게 해야 머지 커밋에만 생긴 내용이
      커밋 범위 기반 검사(DCO·gitleaks 범위 스캔)를 비켜 가지 못한다(WO-00 리뷰 security-3)
- [ ] WO-47 완료 후 SBOM 스캔 잡을 필수 검사에 추가(O-Q02·19 §6.2의 PR 필수 검사. 04 문서 WO-00 `pr.yml` 잡
      목록에 없어 WO-47로 이월)
- [ ] 조직 러너 그룹 `argos-trusted`는 self-hosted 러너를 쓰는 워크플로(nightly·bench·release, 이후 WO)가
      생길 때 만든다. 현재 저장소에 self-hosted 잡은 없다(`check-workflows.sh` W2)
- [ ] 조직 메일함(보안 신고·행동 강령 연락처) 생성 후 SECURITY.md·CODE_OF_CONDUCT.md 갱신
