# ADR-0008: 에이전트 셰이딩의 프로퍼티 문자열 정책(C-23)과 ByteBuddy `Unsafe` 경로 처리

| 항목 | 내용 |
|---|---|
| 상태 | Accepted (provisional, maintainer confirmation pending) |
| 날짜 | 2026-10-24 |
| 결정자 | WO-02 에이전트 코어 담당(PM 위임 범위: ADR-0003 C-23이 WO-02에 맡긴 결정). 메인테이너 확인 대기 |
| 관련 | ADR-0003 C-22·C-23·C-24·C-25와 "ByteBuddy `Unsafe` 참조" 지정, 10 §4.1·§4.3·§10.3·§14·§18 U-5·U-6, 19 §3.3 단계 1·7·8·§5.6·§13 #4·#6, 18 T-BLD-001·004·005·006, F-A35, N-30 |

## English summary

Shadow rewrites property-name string constants together with package names, so the shaded
ByteBuddy (and later Netty) reads `io.argos.agent.shaded.<original name>`. We keep that behaviour
(policy RELOCATED, now enforced by `checkRelocatedStrings`): the agent sets the relocated names
itself, and neither side can change the other's ByteBuddy or Netty through system properties. The
four ByteBuddy 1.18.15 classes that reference `sun.misc.Unsafe` are removed from the jar instead of
being allow-listed; because ByteBuddy's public `AgentBuilder.Default` constructors initialise code
that reaches them on JDK 9+, the agent creates its builder through the full constructor with no
initialization and no injection strategy, and an attach test proves on JDK 8, 11, 17, 21 and 25
that none of those code paths is ever loaded.

## 맥락

- ADR-0003 C-23은 프로퍼티 문자열 relocate 정책을 WO-02가 실측해 정하라고 했다. 10 §10.3은 "셰이딩이 프로퍼티 문자열을 relocate하므로 실제 키는 `io.argos.agent.shaded.io.netty.noUnsafe` 형태가 될 수 있다"며 결정을 19와 P-3에 넘겼고, 19 §13 #6은 10의 결정에 따라 `checkRelocatedStrings`의 단언을 정한다고 했다.
- WO-02 실측(ByteBuddy 1.18.15, Shadow 9.6.1, `relocate("net.bytebuddy", "io.argos.agent.shaded.net.bytebuddy")`):
  - 셰이딩 전 jar의 `net.bytebuddy.<프로퍼티>` 문자열 상수: `experimental`, `raw`, `nexus.disabled`, `dump`, `safe`, `securitymanager`, `validation` 등.
  - 셰이딩 후 jar에는 이 상수들이 **전부** `io.argos.agent.shaded.net.bytebuddy.<프로퍼티>`로만 남는다(원래 형태 0건, `build/reports/agent-jar/relocated-strings.txt`). Netty는 아직 jar에 없다(전송 스택은 P-3 레인).
- ADR-0003은 ByteBuddy의 `sun.misc.Unsafe` 참조 클래스를 `minimize`/exclude로 jar에서 빼는 쪽을 우선하고, 허용 목록에 넣으려면 그 클래스가 런타임에 로드되지 않음을 증명하라고 했다. 1.18.15에서 바이트 검색으로 찾은 참조 클래스는 4개다.
  - `AgentBuilder$LambdaInstrumentationStrategy$LambdaMetafactoryFactory$Loader$UsingUnsafe`
  - `ClassInjector$UsingReflection$Dispatcher$UsingUnsafeInjection`
  - `ClassInjector$UsingReflection$Dispatcher$UsingUnsafeOverride`
  - `ClassInjector$UsingUnsafe$Dispatcher$CreationAction`
- 4개를 빼기만 하면 JDK 21에서 에이전트 기동이 `NoClassDefFoundError`로 실패했다(실측). `new AgentBuilder.Default(byteBuddy)`가 기본 `InitializationStrategy.SelfInjection.Split`을 만들고, 그 초기화가 `NexusAccessor` → `ClassInjector.UsingReflection` → `ClassInjector.UsingUnsafe` 정적 초기화로 이어져 뺀 클래스에 닿는다. JDK 8에서는 이 경로가 `Unsafe`를 거치지 않아 드러나지 않았다.

## 결정

### 1. C-23 프로퍼티 문자열 정책 = RELOCATED

- 셰이딩된 라이브러리가 읽는 시스템 프로퍼티 이름은 relocate된 형태(`io.argos.agent.shaded.<원래 이름>`)가 정답이다. 에이전트가 값을 정할 때는 그 이름으로 설정한다(10 §10.3의 `NettyProps` 상수는 `io.argos.agent.shaded.io.netty.noUnsafe` 형태로 만든다).
- 에이전트는 원래 이름(`io.netty.noUnsafe`, `net.bytebuddy.*`)을 절대 설정하지 않는다. 설정하면 앱의 Netty·ByteBuddy 동작을 바꾼다.
- `build-logic` `AgentShading.PROPERTY_STRING_POLICY = RELOCATED`. `checkRelocatedStrings`(19 §3.3 단계 7, T-BLD-005)는 `AgentShading.PROPERTY_STRINGS`의 원래 형태가 jar에 하나라도 있으면 실패한다. 목록에 ByteBuddy 7개(`experimental`, `raw`, `nexus.disabled`, `dump`, `safe`, `securitymanager`, `validation`)와 10 §10.3의 Netty 8개를 둔다. 전송 레인이 Netty를 넣으면 같은 검사가 Netty 이름에도 적용된다.

### 2. ByteBuddy `Unsafe` 참조 클래스는 jar에서 제거한다(허용 목록 0)

- `AgentShading.UNSAFE_EXCLUDED_CLASSES`(위 4개)를 Shadow `exclude`로 뺀다. `test/agent/unsafe-allowlist.txt`는 비어 있다. `check-shading.sh`의 unsafe 검사가 0건으로 통과한다.
- 에이전트 코어는 ByteBuddy가 자기 클래스 주입기를 초기화하지 않게 만든다.
  - `ArgosAgentBuilder`가 `AgentBuilder.Default`의 protected 전체 생성자를 리플렉션으로 호출한다. 인자는 매개변수 타입별로 ByteBuddy 기본값을 쓰되 `InitializationStrategy.NoOp`, `InjectionStrategy.Disabled`, `TypeStrategy.REDEFINE_FROZEN`, Argos ignore 목록을 넣는다. 모르는 매개변수 타입(ByteBuddy 버전 변경)이 나오면 예외로 기동을 중단한다(에이전트 비활성 + ERROR 1줄). 제거한 클래스에 닿는 대신 명시적으로 실패한다.
  - Advice는 인라인만 하므로 보조 타입 주입이 필요 없다. helper 주입은 코어 `HelperInjector`가 `ClassLoader#defineClass` 리플렉션으로 한다(JDK 9+는 `java.base/java.lang`을 에이전트 클래스로더의 unnamed 모듈에만 연다, 10 §4.4).
- 증명(ADR-0003 요구): `agent-testing` `AgentAttachTest`의 "ByteBuddy Unsafe code paths are never loaded"가 JDK 8·11·17·21·25에서 `-verbose:class`로 `ClassInjector$UsingUnsafe*`, `ClassInjector$UsingReflection*`, `NexusAccessor`, `LambdaMetafactoryFactory$Loader*` 로드 0건을 단언한다. JDK 23 이상은 `--sun-misc-unsafe-memory-access=deny`를 함께 켠다.

### 3. C-22 실측 기록

ByteBuddy 1.18.15의 클래스(`META-INF/versions/**` 제외)는 전부 major 52다. 셰이딩 후 jar는 자체 104개 == 52, 셰이딩 2,507개 == 52다(2026-10-24 빌드). 검사는 C-22 규칙(자체 == 52, 셰이딩 ≤ 52)으로 바꿨다(`checkMajorVersion`, `check-shading.sh`, 네거티브 테스트 유지·추가).

## 검토한 대안

| 대안 | 장점 | 단점 | 채택하지 않은 이유 |
|---|---|---|---|
| ORIGINAL: 프로퍼티 이름을 relocate 대상에서 제외 | 운영자가 익숙한 `-Dio.netty.*`로 에이전트를 조정 | 앱과 에이전트가 같은 프로퍼티를 공유. 에이전트가 `io.netty.noUnsafe=true`를 켜면 앱 Netty 성능이 바뀌고, 앱의 `-Dnet.bytebuddy.dump`가 에이전트 변환 클래스를 디스크에 쓴다(T-BLD-006 위반 경로) | 격리(10 §4.2)와 앱 무영향(원칙 1)에 어긋난다. Shadow 기본 동작을 거슬러 패턴별 제외 규칙을 유지해야 한다 |
| `Unsafe` 참조 4개를 허용 목록에 넣고 `net.bytebuddy.safe=true`(relocate 이름) 설정 | ByteBuddy 기본 생성자 사용 가능 | JDK 9+에서 그 클래스들이 실제로 로드된다(정적 초기화 체인). ADR-0003의 허용 조건(런타임 미로드 증명)을 만족할 수 없다 | 조건 불충족 |
| ByteBuddy 바이트코드를 빌드 시 패치(`SelfInjection` 기본값 제거) | 리플렉션 없음 | 라이브러리 수정본을 배포. 업그레이드마다 패치 유지 | 유지 비용과 감사 부담이 크다 |
| ByteBuddy 대신 다른 바이트코드 라이브러리 | - | 10 문서 전체가 ByteBuddy Advice 전제(DSN-02-01) | 설계 변경 |

## 결과

- 쉬워지는 것: 앱의 ByteBuddy·Netty 설정과 에이전트 설정이 서로 간섭하지 않는다. `sun.misc.Unsafe` 참조 0인 jar를 JDK 25 `deny`에서 그대로 쓴다.
- 새 의무
  - 셰이딩 대상에 시스템 프로퍼티를 읽는 라이브러리를 추가하면 `AgentShading.PROPERTY_STRINGS`에 이름을 더하고, 에이전트가 설정할 값은 relocate된 이름으로 둔다.
  - ByteBuddy를 올릴 때: `Unsafe` 참조 클래스 목록을 다시 스캔하고(`check-shading.sh`가 새 참조를 잡는다), `ArgosAgentBuilder`의 생성자 매개변수 대응과 `AgentAttachTest`(JDK 5종)를 다시 돌린다.
  - 전송 레인(P-3)은 Netty·gRPC·JCTools의 `Unsafe` 참조를 같은 방식(설정으로 끄고, 참조 클래스는 제거 우선)으로 처리하고 이 ADR에 덧붙인다.
- 되돌리는 비용: 정책을 ORIGINAL로 바꾸려면 relocate 제외 패턴과 `checkRelocatedStrings` 정책 한 줄을 바꾸면 된다. 에이전트 코드의 프로퍼티 상수도 바꿔야 한다.

## 확인 사항

- [ ] 메인테이너가 C-23 정책(RELOCATED)을 확인했다.
- [ ] 메인테이너가 `Unsafe` 참조 클래스 제거 + `ArgosAgentBuilder` 방식을 확인했다.
- [x] P-3(전송 스택) 결과로 Netty·gRPC의 `Unsafe` 처리와 relocate된 Netty 프로퍼티 실측을 이 ADR에 덧붙였다(아래 "덧붙임").
- [ ] 02 §13(또는 ADR-0003) 정합 결정 표에 C-23 결과를 옮겼다.

## 덧붙임: P-3 실측 (WO-02 전송 레인, 2026-10-24)

상세는 `docs/poc/agent-poc.md` 9장, 결정 제안은 `docs/adr/0005-agent-transport.md`(Proposed)다.

- C-23 RELOCATED는 Netty·gRPC에서도 성립한다. 비교 jar 7개에서 Netty 17·gRPC 3·ByteBuddy 7개 프로퍼티 이름의 원래 형태가 0건이었다. 에이전트가 `io.argos.agent.shaded.io.netty.*`로 설정한 값이 셰이딩된 Netty에 적용됐고(`hasUnsafe=false`, `maxDirectMemory=8388608`, `UnpooledByteBufAllocator`), 앱의 `-Dio.netty.*`는 에이전트 Netty를 바꾸지 않았다.
- 부작용: Shadow는 에이전트 자체 클래스의 문자열 상수도 relocate한다. `IgnoreMatchers`의 `"net.bytebuddy."`가 배포 jar에서 `"io.argos.agent.shaded.net.bytebuddy."`로 바뀌어 앱의 ByteBuddy 클래스가 ignore 목록에서 빠진다. 자체 코드 리터럴 검사와 수정이 필요하다(전송 ADR 결정 3). WO-02 통합(2026-11-02)에서 고쳤다: `IgnoreMatchers.APP_BYTEBUDDY_PREFIX`를 실행 시점에 만들고, `verifyAgentJar`에 `checkOwnRelocatedLiterals`(자체 클래스의 relocate 네임스페이스 상수 0, 허용 목록 방식)를 넣었다.
- `Unsafe`: Netty는 `noUnsafe`로, Guava 33.6·protobuf-java 4.36은 자체 판단으로 `Unsafe`를 쓰지 않는다(JDK 25 warn 경고 0, deny 동작). protobuf-javalite는 경고를 내고 deny에서 실패한다. 실행 중 로드되지 않는 참조 클래스는 이 ADR의 방식대로 jar에서 뺄 수 있었다. Netty `PlatformDependent0` 등 로드되는 클래스는 뺄 수 없어 허용 조건을 PM이 정해야 한다(전송 ADR (P2)).
- Netty 4.2.19는 Java 11 클래스 파일 51개(JCTools VarHandle 큐)를 본 jar에 넣고 Java 11+에서 반드시 쓴다. C-22 위반이다(전송 ADR (P1)).
