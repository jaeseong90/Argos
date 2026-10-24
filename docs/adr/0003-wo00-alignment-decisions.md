# ADR-0003: WO-00에서 드러난 설계 문서 간 불일치의 정합 결정

| 항목 | 내용 |
|---|---|
| 상태 | Accepted (provisional, maintainer confirmation pending) |
| 날짜 | 2026-10-23 |
| 결정자 | PM(메인테이너 위임 결정). 메인테이너 확인 대기 |
| 관련 | 02 §13 정합 결정 C-1~C-20의 보충(C-21~C-36), WO-00 완료 보고 "가정과 미해결" C·D절, 10 §4.1·§5.2·§5.3·§10.3, 18 §2.2·§5.10, 19 §3.2·§3.3·§5.3·§5.6·§6.1·§13, 01 O-S04, 16 §10.5, 17 §16 |

## English summary

WO-00 surfaced places where the design documents disagree with each other. This ADR records the PM's
alignment decisions as C-21 to C-36, a supplement to the alignment table in docs/02 §13, so that later
work orders can proceed without stopping. The main points are: the agent entry class follows docs/10,
own agent classes must be bytecode major 52 while shaded third-party classes may be 52 or lower, exit
advice decides by the enter result instead of re-reading the kill switch, the module registry must not
make agent-core depend on instrumentation modules, routine Dependabot updates are monthly, and the demo
load generator must not ship k6 (AGPL); C-34, added after the XLog PoC, makes direct ImageData pixel writes the default XLog point renderer. The design documents themselves are unchanged; where they
conflict with this ADR, this ADR wins until the maintainer confirms or overrides it.

## 맥락

- WO-00 팀은 설계 문서 사이의 세부 불일치를 발견하면 설계를 바꾸지 않고 가장 가까운 준수안으로 구현한 뒤 보고했다(WO-00 보고 C절 1~17, D절).
- 그중 다수가 WO-01·WO-02 이후의 구현 방향을 정한다. 결정 없이 진행하면 WO마다 다른 해석이 생긴다.
- 사용자는 구현 단계를 질문 없이 진행하도록 PM에게 위임했다. CLAUDE.md는 설계 결정을 바꾸면 ADR을 남기라고 정한다. 그래서 설계 문서는 그대로 두고, 02 §13 정합 결정 표를 보충하는 형태로 이 ADR에 기록한다.

## 결정

번호는 02 §13의 C-1~C-20에 이어 붙인다. 이 ADR의 결정이 설계 문서와 다르면 이 ADR이 우선한다(메인테이너가 확정하거나 뒤집을 때까지).

| ID | 주제 | 결정 | 적용 WO |
|---|---|---|---|
| C-21 | Premain-Class와 커밋 속성 이름 | 10 §4.1을 따른다: `Premain-Class`·`Agent-Class` = `io.argos.agent.AgentPremain`, 커밋 속성 `Argos-Commit`. 19 §5.6 "루트에는 `io/argos/agent/bootstrap/`만"의 예외는 매니페스트가 가리키는 Premain 클래스 1개뿐이다(`check-shading.sh`가 이미 이렇게 검사). | WO-02 |
| C-22 | T-BLD-001 major version | 자체 클래스(`io/argos/agent/**` 중 `io/argos/agent/shaded/**` 제외)는 major == 52. 셰이딩된 서드파티 클래스(`io/argos/agent/shaded/**`)는 major ≤ 52. 목적은 "Java 8에서 로드 가능"이다. `checkMajorVersion`과 `check-shading.sh`를 이 규칙으로 바꾸고 네거티브 테스트(자체 53, 서드파티 53)를 유지한다. | WO-02 |
| C-23 | 프로퍼티 문자열 relocate 정책 | 10 §10.3·19 §13 #6 대로 WO-02가 실측해 결정하고 결과를 이 표에 덧붙인다(ADR-0003 개정 또는 후속 ADR). 그때까지 `checkRelocatedStrings`는 보고만 한다. | WO-02 |
| C-24 | exit Advice의 킬 스위치 검사 | enter Advice는 첫 문장에서 `Argos.skip(MODULE_ID)`를 검사한다(10 §5.3 A-2). exit Advice는 킬 스위치를 다시 읽지 않고 **enter 반환값**(`@Advice.Enter`가 null이면 아무것도 안 함)으로 판단한다. enter와 exit 사이에 킬 스위치가 바뀌어도 이미 연 scope는 반드시 닫는다. ArchUnit ADV-03은 exit Advice에 대해 "첫 동작이 `@Advice.Enter` 값의 null 검사 또는 `Argos.skip`"을 허용하도록 고친다. | WO-02(규칙·PoC), WO-10(정식) |
| C-25 | 모듈 레지스트리와 B-3 | `agent-core`는 계측 모듈 클래스를 컴파일 타임에 참조하지 않는다(B-3 유지). 모듈 목록은 `agent-dist` 조립 단계가 생성하는 색인 리소스(`inst/META-INF/argos/modules.idx` 등) 또는 `ServiceLoader`형 서비스 파일로 발견한다. 10 §5.2의 `ModuleRegistry.ALL`은 이 색인을 읽는 구현으로 해석한다. | WO-02, WO-10 |
| C-26 | T-AG-004와 B-4 해석 | WO-00 구현을 확정한다: TraceContext의 `WeakReference`와 `Object coreRef`는 T-AG-004 예외. B-4 "core만"은 "계측 모듈의 Advice·helper가 core 내부 클래스를 직접 쓰지 않는다"로 해석하고, 10 §5.1 api 모듈의 `ElementMatcher` 노출은 허용한다. | WO-02 이후 |
| C-27 | `-XX:+BytecodeVerificationLocal` | JDK 17 이상에서는 `-XX:+UnlockDiagnosticVMOptions`와 함께 쓴다(실측). 18 §5.10·19 §11의 표기는 이 두 플래그 조합을 뜻한다. | WO-02, WO-05 |
| C-28 | `argos.java-server` 책임 이연 | `bootJar`(layered)와 Flyway 파일명 규칙 검증은 WO-20, `generateOpenApi`는 WO-23의 산출물에 포함한다. Flyway 파일명 규칙은 WO-20이 13 §(마이그레이션 러너)에 맞춰 정하고 보고한다. | WO-20, WO-23 |
| C-29 | 매트릭스 단일 조합 실행 프로퍼티 | 18 §2.2의 `-Pmatrix.combo=<id>`로 통일한다. 19 §10 item 5의 `-Pmatrix=boot3:21`은 같은 뜻의 옛 표기로 본다. | WO-05, WO-50 |
| C-30 | Dependabot 주기 | 요구 문서(01 O-S04)와 16 §10.5를 따른다: 일반 버전 업데이트는 **월 1회**(그룹), 보안 업데이트는 즉시(Dependabot security updates). 19 §6.1·§8의 weekly는 이 결정으로 대체한다. | WO-47 |
| C-31 | SBOM 스캔 PR 필수 검사 | O-Q02·19 §6.2의 SBOM 스캔 잡은 WO-47에서 `pr.yml`에 추가한다(WO-00 잡 목록에 없었음). | WO-47 |
| C-32 | ADR 언어 | 한국어 본문 + 영문 요약(English summary) 1단락. O-G07의 "영문 1페이지"는 영문 요약으로 충족한다. | 모든 ADR |
| C-33 | 데모 부하 생성기와 k6 | k6(AGPL-3.0)는 벤치·개발 전용(`docs/licenses/dev-only.md`)이며 배포물·공개 이미지에 넣지 않는다. 공개 이미지 `argos-demo-load`는 허용 목록 라이선스만 쓰는 자체 구현(예: Java `HttpClient` 기반 고정 도착률 생성기)으로 만든다. WO-01은 벤치용 k6 시나리오와 별도로 이 생성기의 정의를 만들고, WO-41은 데모 앱·부하 이미지를 `checkLicenses` 대상으로 전환한다. | WO-01, WO-41 |
| C-34 | XLog 점 레이어 그리기 방식(P-5 결과, 2026-10-23 추가) | WO-04 실측에서 15 §7.2·DSN-15-03의 색별 3패스 path fill은 5만·10만 점에서 16ms를 넘었다(10만 점 p95 79~145ms, 병목 GPU 래스터). 15 §18 #2가 미리 정한 대안인 **ImageData 직접 쓰기(`pixels`)를 기본값**으로 한다. `path`는 비교·진단용 옵션으로 남긴다. 고DPR(≥ 2)에서는 **점 레이어의 백킹 스토어 배율에 상한**을 두고(축·격자·글자·드래그 오버레이는 전체 DPR), 상한값은 DPR 2에서 10만 점이 기준을 지키는 값으로 WO-04가 실측해 정한다. 판정 통계는 15 §15대로 p95와 평균이 16ms 이하일 때 통과로 하고, max는 보고만 한다. WO-04는 DPR 1과 DPR 2 두 조건을 모두 측정해 보고한다. | WO-04, WO-33 |
| C-35 | XLog 프레임 판정 단위(WO-04 후속, 2026-10-24 추가) | C-34의 p95·평균 판정은 **런마다** 적용한다(3회 런 각각이 통과해야 통과). 3회를 합친 분포는 보고용이다. 측정은 조용한 머신(런 직전 CPU 부하 기록)에서 하며, 공유 머신 수치는 참고값이다. | WO-04, WO-33, WO-51 |
| C-36 | XLog 상호작용 예산(WO-04 후속, 2026-10-24 추가) | 15 §7.3 "10만 점 선택 계산 ≤ 5ms"는 런별 p95로 판정한다. 드래그를 놓은 뒤 요약·목록이 화면에 반영되기까지(React 커밋 포함)는 별도 예산 **p95 ≤ 100ms**를 둔다. WO-04 실측(선택 질의 p95 4.5~6.4ms, 요약+1,000행 커밋 p95 60~73ms, 공유 머신)은 참고값이며, 목록 가상화와 함께 WO-33 수락 기준으로 판정한다. | WO-33 |

다음은 결정이 아니라 담당 WO 지정이다.

- httpclient-jdk11 모듈과 `--release 8`의 충돌(`java.net.http` 컴파일 불가)은 WO-14에서 (a) 모듈 단위 `-source 8 -target 8` + Java 9+ API 참조 바이트코드 검사, (b) compileOnly 스텁 중 하나를 골라 이 표에 덧붙인다. 어느 쪽이든 Java 8 런타임에서 그 모듈이 로드되지 않음(witness 실패로 비활성)을 테스트로 고정한다.
- SRV-01(`@PreAuthorize` vs 12 DSN-12-11 `@RequiresRole`, storage 경유 규칙과 MigrationRunner·AuditSink)은 서버 코드가 생기는 WO-20·WO-24에서 12 문서를 기준으로 정한다.
- ByteBuddy의 `sun.misc.Unsafe` 참조 클래스는 WO-02가 `minimize`/exclude로 jar에서 제거하는 것을 우선한다. `unsafe-allowlist.txt`에 넣으려면 그 클래스가 런타임에 로드되지 않음을 증명하고 이 ADR에 근거를 덧붙인다.
- jk1 license-report의 구성 캐시 비호환은 WO-47에서 구성 캐시 호환 대안을 검토한다.
- 17 §16 #3(서버 베이스 이미지 비루트): Temurin 21 JRE 이미지 기본 사용자는 root이므로 Jib `container.user=10001:10001`과 HOME·데이터 디렉터리 지정이 필요하다(WO-00 PMO 실측). WO-20·WO-40이 반영한다.

## 검토한 대안

| 대안 | 장점 | 단점 | 채택하지 않은 이유 |
|---|---|---|---|
| 설계 문서(02 §13, 10, 19)를 직접 개정 | 문서 하나만 보면 됨 | 메인테이너가 아직 설계를 검토하지 않았는데 원문이 바뀜 | 사람이 검토할 때 원 설계와 변경점을 구분할 수 있게 ADR로 분리했다 |
| WO마다 그때그때 해석 | 결정 비용 없음 | WO마다 다른 해석, 리뷰 반복 | 같은 문제가 WO-02·WO-10·WO-14에 반복된다 |
| 불일치마다 진행을 멈추고 질문 | 사람 결정 | 구현 정지 | 사용자가 질문 없이 진행하도록 위임했다 |

## 결과

- 이후 WO의 공통 맥락은 "02 §13 + ADR-0003"을 정합 결정의 출처로 쓴다.
- 메인테이너가 이 ADR을 확인하면 C-21~C-36을 02 §13 표에 옮기고 이 ADR의 상태를 Accepted로 바꾼다. 뒤집는 결정이 있으면 해당 WO의 산출물을 고친다.
- C-22·C-24는 WO-00이 만든 검사(`checkMajorVersion`, `check-shading.sh`, ArchUnit ADV-03)의 수정을 요구한다. WO-02가 수행하고 네거티브 테스트를 유지한다.

## 확인 사항

- [ ] C-21~C-36 각 항목을 메인테이너가 확인했다.
- [ ] 02 §13 표에 C-21~C-36을 옮겼다.
- [ ] C-23(relocate 정책)과 httpclient-jdk11 결정을 WO-02·WO-14 결과로 덧붙였다.
