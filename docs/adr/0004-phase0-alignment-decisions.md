# ADR-0004: Phase 0 진행 중 드러난 설계 불일치의 정합 결정(WO-01 이후)

| 항목 | 내용 |
|---|---|
| 상태 | Accepted (provisional, maintainer confirmation pending) |
| 날짜 | 2026-10-24 |
| 결정자 | PM(메인테이너 위임 결정). 메인테이너 확인 대기 |
| 관련 | 02 §13 정합 결정 C-1~C-20, ADR-0003(C-21~C-36)의 후속(C-37~C-49), WO-01 완료 보고 "가정과 미해결" B·C·D절, 01 O-L03·F-A03·F-A29·D-04·D-24, 16 §9.4, 17 §2.3·§3.9, 18 §4.1·§4.3·§4.4·§7.2·§7.4, 19 §3.5·§5.5 |

## English summary

WO-01 (sample applications) surfaced thirteen places where the design documents disagree or leave a value
open. This ADR records the PM's decisions as C-37 to C-49, continuing ADR-0003. The most important ones:
MIT-0 joins the licence allowlist (it is more permissive than MIT and Reactor needs it); the public
`argos-demo-app` image becomes a dedicated allowlist-only build of the shop catalogue (Spring JDBC,
PostgreSQL driver, Log4j 2, no Hibernate, H2, MySQL or AspectJ) instead of shipping `shop-boot3` as is;
seed scans look for both `ARGOS_SEED` and `ARGOSSEED`; the XSS seed set is the union of docs 16 and 18;
and the k6 gate is "dropped iterations at most 1 % and unexpected errors 0 % in the measured window". The
design documents are unchanged; where they conflict with this ADR, this ADR wins until the maintainer
confirms or overrides it.

## 맥락

- WO-01 팀은 설계 문서 사이의 불일치를 가장 가까운 준수안으로 구현하고 PM 결정 항목 13건(보고서 C절)과 후속 사실(D절)을 남겼다.
- 그중 C-7(데모 앱 라이선스), C-6(reactive-streams)은 WO-20·WO-41 전에, C-8·C-9(시드)는 WO-46 전에, C-10(k6 게이트)은 WO-02 전에 정해야 한다.
- 사용자가 구현 단계를 질문 없이 진행하도록 위임했으므로 PM이 정하고 이 ADR에 남긴다.

## 결정

| ID | 주제 | 결정 | 적용 WO |
|---|---|---|---|
| C-37 | WAR 샘플 앱 이름 | 18 §4.1(C-18)의 `shop-war-javax`·`shop-war-jakarta`가 정식 이름이다. 04의 `war-javax`·`war-jakarta`는 옛 표기다. | 전체 |
| C-38 | 샘플 앱 이미지 빌드 방식 | 개발 전용 샘플 앱 이미지는 Dockerfile(18 §4.4)로 만든다. 공개 이미지(`argos-server`, `argos-demo-app`, `argos-demo-load`)는 19 §3.5대로 Jib로 만든다. | WO-41, WO-44 |
| C-39 | shop-batch 실행 JDK | 02 §9 유효 조합표(17·21·25)를 따른다. 18 §4.1의 "17·21"은 옛 표기다. | WO-50 |
| C-40 | JDBC 드라이버 래퍼 픽스처 | `SAMPLE_ROUTING_DS=true`는 지금처럼 4단(라우팅 → TransactionAwareDataSourceProxy → 풀 → 드라이버)이다. D-24(JDBC 중첩 제거) 검증을 위해 `java.sql.Driver`·`Connection`·`Statement`를 위임 래핑하는 5번째 층을 별도 스위치 `SAMPLE_DRIVER_WRAPPER=true`로 추가한다. 두 스위치는 함께 켤 수 있다. | WO-13 |
| C-41 | "Hibernate 전 모듈"의 범위 | core·envers·jcache·validator로 한정한다. LGPL·EPL 의존을 끌어오는 spatial·community-dialects는 넣지 않는다. | WO-01(완료) |
| C-42 | MIT-0 라이선스 | **허용 목록에 MIT-0을 추가한다.** MIT에서 고지 의무까지 뺀 OSI 승인 라이선스로 MIT보다 관대하다. 대상: `build-logic/allowed-licenses.json`, `ui/scripts/license-policy.json`, CLAUDE.md 허용 목록 요약. Reactor가 쓰는 reactive-streams 1.0.4(MIT-0)가 서버 배포물에 들어갈 수 있게 된다. | 즉시(후속 정리), WO-20 |
| C-43 | `argos-demo-app` 이미지 구성 | 17 §2.3·§3.9의 "shop-boot3 + 에이전트"를 그대로 배포하지 않는다. 공개 데모 앱은 **허용 목록만 쓰는 전용 빌드**로 만든다: 같은 엔드포인트 카탈로그, Spring Boot web(내장 Tomcat), Spring JDBC(JPA·Hibernate 없음), PostgreSQL 드라이버, HikariCP, Log4j 2(Logback 대신), `jakarta.annotation-api`는 제외하고 Tomcat 내장 구현 사용, AspectJ·H2·MySQL 없음. `checkLicenses` 대상이며 위반 0이어야 한다. shop-boot3은 개발 전용 기준 앱으로 남는다. | WO-41 |
| C-44 | 시드 센티널 탐색 | 보안 스위트와 PII 시드 검사는 `ARGOS_SEED`와 `ARGOSSEED` 두 문자열을 모두 탐색한다. AWS 키 형태(`AKIAARGOSSEED0000001`)는 형식상 밑줄을 넣을 수 없기 때문이다. 토큰 형태 값은 18 §4.3 "환경변수 값" 위치 전부(JWT 형태와 AWS 키 형태)를 채운다. | WO-46, WO-62 |
| C-45 | XSS 시드 세트 | 16 §9.4와 18 §4.3의 **합집합**을 쓴다. 엔드포인트는 18의 `/seed/xss/<payload>`, 헤더는 18의 위치에 16의 `X-Seed`를 더하고, 페이로드는 16의 9종과 18의 4종을 중복 없이 합친다. | WO-46 |
| C-46 | k6 게이트와 dropped 집계 | 측정 구간(웜업 제외)에서 `dropped_iterations` ≤ 계획 반복 수의 1%이고 예상 밖 오류(unexpected) 0%이면 통과다. 웜업 구간은 보고만 한다. 부하 생성기 머신의 CPU 부하를 함께 기록하고, 부하 생성기가 포화(평균 CPU 80% 이상)면 결과를 INVALID로 표시한다. | WO-02, WO-05, WO-51 |
| C-47 | 샘플 앱 스모크 정기 실행 | PR은 shop-boot3 3변형만 돈다. 50변형 전체 `sampleSmoke`는 nightly(self-hosted, 18 §2.1)에서 돈다. | WO-47, WO-50 |
| C-48 | 핸들러 없는 404의 서비스 이름(10 U-8 실측 반영) | Framework 7.0.10 실측에서 핸들러 없는 404는 REQUEST 디스패치에 매칭 패턴이 없고 ERROR 디스패치에서만 `/error`가 잡힌다. 에이전트는 REQUEST 디스패치 기준으로 서비스 이름을 정하고, 패턴이 없으면 F-A29 폴백 규칙(경로 정규화)을 쓴다. ERROR 디스패치의 `/error`는 서비스 이름으로 쓰지 않는다(D-04). | WO-12 |
| C-49 | 기동 시간 측정 정의 | jar 앱은 18 §7.4·01 §7.3 8항대로 "포트 바인딩"(10회 중앙값, `-Xshare:off`와 기본 CDS 둘 다)을 쓴다. 독립 Tomcat WAR는 초기화 단계에서 포트를 먼저 열므로 "포트 바인딩" 대신 **ready**(배포된 웹앱의 알려진 엔드포인트가 처음 200을 돌려준 시각)를 쓴다. WO-01 표는 잠정 기준선이고, N-04·N-27 판정은 벤치 머신에서 WO-02·WO-05가 이 정의로 한다. | WO-02, WO-05, WO-51 |

## 검토한 대안

| 대안 | 장점 | 단점 | 채택하지 않은 이유 |
|---|---|---|---|
| C-43: 17 §3.9를 바꿔 데모 앱을 공개 이미지에서 빼기 | 라이선스 문제 없음 | "첫 5분" 데모(N-16, Gate 1.5)가 사라짐 | 첫 5분 경험은 오픈소스 확산의 핵심 요구다 |
| C-43: 약한 카피레프트(EPL·LGPL) 바이너리 재배포 예외 목록 신설 | shop-boot3 그대로 사용 | 허용 목록 원칙(O-L03)에 예외가 생김 | 전용 빌드로 원칙을 지킬 수 있다 |
| C-42: reactive-streams를 서버에서 빼기 | 허용 목록 유지 | Reactor(실시간 버스)를 못 씀 | MIT-0은 MIT보다 관대해 위험이 없다 |
| C-44: 18의 AWS 키 형태 값을 바꾸기 | 센티널 하나로 통일 | 실제 키 형식 탐지 테스트가 약해짐 | 실제 형식을 유지하는 편이 마스킹 검증에 유리하다 |

## 결과

- 이후 WO의 공통 맥락은 "02 §13 + ADR-0003 + ADR-0004"를 정합 결정의 출처로 쓴다.
- C-42는 허용 목록 파일 2개와 CLAUDE.md 요약을 바꾼다(WO-01 후속 정리에서 수행).
- C-43은 WO-41의 산출물을 늘린다(전용 데모 빌드와 그 `checkLicenses`).
- 메인테이너가 확인하면 C-37~C-49를 02 §13 표에 옮기고 관련 설계 문서(04, 16 §9.4, 17 §3.9, 18 §4.1·§4.3·§7.2)의 표기를 고친다.

## 확인 사항

- [ ] C-37~C-49 각 항목을 메인테이너가 확인했다.
- [ ] 02 §13 표와 관련 설계 문서 표기를 갱신했다.
