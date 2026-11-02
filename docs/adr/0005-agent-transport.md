# ADR-0005 (전송): 에이전트 전송 스택(A-1)·protobuf 런타임(A-14)·셰이딩 위생 결정 (WO-02 P-3)

| 항목 | 내용 |
|---|---|
| 상태 | Proposed |
| 날짜 | 2026-10-24 |
| 결정자 | 제안: WO-02 전송 레인. 결정: PM·메인테이너(아래 "PM 결정 필요" 3건 포함) |
| 관련 | 02 §11 P-3·§12 A-1·A-14, DSN-02-11, DSN-10-11, 10 §10.3·§14·§18 U-4·U-6, 11 §2·§3·§15 #2~#4, 19 §3.3·§3.4·§13 #4~#6, ADR-0003 C-22·C-23, ADR-0008(셰이딩·ByteBuddy `Unsafe`), F-A35, N-26, N-30, T-BLD-001·004·005·006 |
| 근거 | `docs/poc/agent-poc.md` 9장(측정 표·원시 파일 경로, WO-02 통합 때 `agent-transport.md`를 합침), `docs/bench/wo02-agent-2026-11-02/transport/`(재측정 원시 자료), `test/agent/transport-poc` |

> 번호: 같은 WO-02 코어 레인의 셰이딩 ADR은 번호가 겹쳐 [ADR-0008](0008-agent-shading-and-bytebuddy-unsafe.md)로 옮겼다(0006·0007은 저장소 ADR). 이 ADR은 0005를 유지한다(PM 지시, 2026-10-25).

## English summary

P-3 built seven comparison agent jars (grpc-netty on Netty 4.2 and 4.1, grpc-okhttp, and an own
HTTP/1.1 client, each with protobuf-java or protobuf-javalite) and measured them on JDK 8u242 to 25.
We propose to keep gRPC over grpc-netty (NIO, JDK TLS, the fixed settings of 10 §10.3) with
protobuf-java: it passes every Gate 0 P-3 criterion, it is the only gRPC transport that fits the
six-thread budget, and it keeps the protocol of document 11 unchanged. protobuf-javalite is rejected
because it prints sun.misc.Unsafe warnings on JDK 24+ and stops working with
--sun-misc-unsafe-memory-access=deny. The property-string policy stays RELOCATED (now proven for
Netty and gRPC at run time); the shaded libraries' java.util.logging is redirected to the agent log
by relocation, gRPC jars are minimize roots, and three items need a PM decision: the Netty line
(4.2 ships Java 11 class files, C-22), allow-listing Unsafe-referencing classes that are loaded but
never use Unsafe, and the AF_UNIX socket file that NIO selectors create in the temp directory on
Windows (T-BLD-006).

## 맥락

- A-1(전송 스택)과 A-14(`protobuf-javalite`)는 P-3 비교 뒤 Phase 0에 확정하기로 했다(02 §12). 채택 기준은 jar ≤ 15MB, 기동 ≤ 2초, 스레드 ≤ 6, 직접 메모리 ≤ 8MB, `Unsafe` 경고 0이다(02 §11 P-3).
- ADR-0003 C-23은 프로퍼티 문자열 relocate 정책을 WO-02가 실측해 정하라고 했다. 코어 레인은 ByteBuddy 근거로 RELOCATED를 정했고(ADR-0008 셰이딩), Netty·gRPC 실측은 P-3 몫으로 남겼다.
- 측정(상세: `docs/poc/agent-poc.md` 9장). Windows 11, 12 논리 CPU, 공유 머신(WO-03 동시 실행). 참고값이며 Gate 0 수치는 전용 장비에서 다시 잰다. 아래 표는 2026-10-24 측정이다. WO-02 통합(2026-11-02)에서 다른 작업이 없는 같은 머신으로 다시 쟀고 판정은 같았다(수치와 원시 자료는 9장).

| 항목 | grpc-netty 4.2 full | grpc-netty 4.1 full | grpc-okhttp full | HTTP/1.1 full |
|---|---|---|---|---|
| jar(바이트, base 4,074,064) | 12,184,050 | 12,024,167 | 11,385,857 | 6,050,980 |
| 기동(`main` 진입 지연, 무에이전트 대비, JDK 21 / 8 중앙값, base +494 / +383 ms) | +468 / +382 ms | +479 / +353 ms | +508 / +404 ms | +473 / +387 ms |
| 첫 배치 수신(JVM 시작 후, JDK 21 / 8) | 1,560 / 2,071 ms | 1,613 / 1,980 ms | 1,298 / 1,707 ms | 1,041 / 1,243 ms |
| 메타스페이스(base 대비, JDK 21) | +17.1 MiB | +17.2 MiB | +12.9 MiB | +7.4 MiB |
| 전송이 늘린 스레드 | 1 (`argos-io`) | 1 | 3 (`argos-io`, 이름이 바뀐 `OkHttpClientTransport`, `argos-io-timer`) | 0 |
| 직접 메모리 최대(JDK 21) | 10,615 B | 10,615 B | 9,497 B | 8,192 B |
| JDK 25 `Unsafe` 경고 / deny | 0 / 동작 | 0 / 동작 | 0 / 동작 | 0 / 동작 |
| 8u242(ALPN 없음) | 기동 시 ERROR 1줄 + 전송 비활성 | 같음 | 접속마다 실패 | 동작(TLS 1.2) |
| C-22(셰이딩 major ≤ 52) | 위반 51개(Netty JCTools VarHandle 큐, major 55) | 통과 | 통과 | 통과 |
| `Unsafe` 참조 클래스(전체 / 실제 로드) | 37 / 4 | 39 / 17 | 11 / 1 | 7 / 0 |
| Windows temp 파일(JDK 16+) | `socket_<n>` 생성(NIO 선택기 AF_UNIX 파이프) | 같음 | 0 | 0 |
| 11 문서 RPC 모델 | 그대로 | 그대로 | 그대로 | 세션 양방향 스트림 불가(재설계) |

- `protobuf-javalite`: `map`·`optional` 왕복과 존재 판별은 full과 같다(테스트 통과). jar는 full보다 약 1.2MB 작다. 그러나 JDK 25 기본(warn)에서 `sun.misc.Unsafe::arrayBaseOffset` 경고 3줄을 stderr에 쓰고, `deny`에서는 메시지를 만들 때 `RuntimeException: Lite gencode is primarily intended for Android use and uses sun.misc.Unsafe`로 실패한다(전송 3종 모두 같음). protobuf-java 4.36.2는 두 모드 모두 경고 0이고 동작한다.
- 셰이딩된 gRPC·protobuf·Netty는 `java.util.logging`을 쓴다(gRPC·Netty 클래스 103개). 그대로 두면 에이전트가 JDK `LogManager`를 초기화하고 경고가 루트 콘솔 핸들러로 stderr에 나갈 수 있다(F-A35, CLAUDE.md "JUL 금지").
- Shadow `minimize()`는 ServiceLoader·`Class.forName`으로 읽는 gRPC 제공자(`UdsNameResolverProvider` 등)를 지워 채널 생성이 `ServiceConfigurationError`로 실패했다.

## 결정 (제안)

1. **A-1: gRPC over grpc-netty를 유지한다.** NIO 전송, `SslProvider.JDK`, 10 §10.3 고정 설정(이벤트 루프 1 `argos-io`, `directExecutor`, `offloadExecutor` = 이벤트 루프, 인바운드 4MiB, keepalive 60초/20초, idle 끔)을 그대로 쓴다. 11 문서 RPC 모델과 DSN-02-11은 바뀌지 않는다. 측정에서 전송이 늘린 스레드는 `argos-io` 하나였다(gRPC 공유 executor·timer 스레드 0).
2. **A-14: `protobuf-javalite`를 채택하지 않는다.** 에이전트도 protobuf-java(full)를 쓴다. `gradle.properties`의 `argos.proto.lite=false`를 유지하고, 19 §3.4의 lite 플래그는 JDK 24+ 제약이 풀릴 때까지 쓰지 않는다.
3. **C-23: RELOCATED를 확정한다(Netty·gRPC 실측).** 비교 jar 9개(제외 실험 2개 포함) 모두 Netty 17·ByteBuddy 7·gRPC 3개 프로퍼티 이름의 원래 형태가 0건이다(`checkRelocatedStrings`, 정책 RELOCATED 통과). 에이전트가 relocate된 이름으로 설정한 값(`noUnsafe`, `allocator.type=unpooled`, `maxDirectMemory=8388608`)이 셰이딩된 Netty에 적용되고(`hasUnsafe=false`, `UnpooledByteBufAllocator`), 앱이 `-Dio.netty.noUnsafe=false -Dio.netty.allocator.type=pooled -Dio.netty.maxDirectMemory=0`을 줘도 에이전트 Netty는 바뀌지 않았다. 에이전트는 원래 이름을 설정하지 않는다(앱에서 `io.netty.noUnsafe`가 `null`).
   - 덧붙이는 규칙: Shadow는 에이전트 **자체 클래스**의 문자열 상수도 relocate한다. 앱 클래스를 가리키려고 쓴 리터럴이 바뀐다. 실측: `IgnoreMatchers`의 `"net.bytebuddy."`가 `"io.argos.agent.shaded.net.bytebuddy."`가 되어 앱의 ByteBuddy 클래스가 ignore 목록에서 빠진다. `kotlin`을 relocate하면 `"kotlin.reflect.jvm.internal."`도 바뀐다. 자체 코드는 relocate 대상 패키지로 시작하는 리터럴을 쓰지 않는다(필요하면 `String.join(".", "net", "bytebuddy", "")`처럼 실행 시점에 만든다). relocate된 이름을 일부러 쓰는 상수(`NettyProps`, `AgentClassLoader`의 접두사)는 허용 목록으로 관리한다. agent-dist에 자체 코드 리터럴 검사(P-3의 `PocOwnRelocatedLiterals`와 같은 것)를 추가한다.
4. **JUL: 셰이딩 라이브러리의 `java.util.logging.Logger` 참조를 에이전트 로거로 relocate한다.** Shadow `relocate("java.util.logging.Logger", <에이전트 클래스>)`로 바꾸고, 그 클래스(P-3 `JulLogger`)가 SEVERE→ERROR, WARNING→WARN, INFO→INFO, FINE/CONFIG→DEBUG로 `AgentLogger`에 보낸다. 실측: 비교 jar의 `java/util/logging/Logger`·`LogManager` 참조 0, 에이전트가 만든 JUL 로거 0, gRPC 내부 오류도 에이전트 로그로만 갔다. 새 호출 메서드가 생기면 빌드 검사로 잡는다.
5. **minimize: gRPC 아티팩트(`io.grpc:*`)를 minimize 루트로 둔다**(19 §13 #4). gRPC 클래스가 모두 남는다(grpc-netty jar에서 `io.grpc` 압축 1,037KB + `io.grpc.netty` 277KB). Netty·Guava·protobuf는 minimize한다.
6. **Java 8 하한: 전송은 8u252 이상**(11 §15 #4, 10 U-4 확인). 8u242에서 grpc-netty는 TLS 컨텍스트 생성 시 `IllegalArgumentException: Could not find Jetty NPN/ALPN or Conscrypt`로 실패하고, 에이전트는 ERROR 1줄을 남기고 전송만 끈다(계측 유지, stdout·stderr 0). 8u252는 TLS 1.2, 최신 8u(8u504)·11 이상은 TLS 1.3으로 협상했다.
7. **에이전트가 설정하는 Netty 프로퍼티**(relocate된 이름, 10 §10.3 + P-3 추가): `noUnsafe=true`, `allocator.type=unpooled`, `noPreferDirect=true`, `maxDirectMemory=8388608`, `tryReflectionSetAccessible=false`, `eventLoopThreads=1`, `allocator.numDirectArenas=0`, 추가 `leakDetection.level=disabled`, `recycler.maxCapacityPerThread=0`, `noKeySetOptimization=true`, `varHandle.enabled=false`(4.2만 해당). 운영자가 같은 이름을 먼저 설정하면 그 값을 쓴다.

### PM 결정 필요

- **(P1) Netty 계열과 C-22.** Netty 4.2.19의 `netty-common`은 JCTools VarHandle 큐 51개를 Java 11 클래스 파일(major 55)로 본 jar에 넣는다. Netty 4.2는 Java 11 이상에서 `Unsafe`가 꺼져 있으면 이 큐를 무조건 쓴다(`hasJctoolsVarHandle() = javaVersion() >= 11`, `io.netty.varHandle.enabled`로도 끌 수 없음). 제거하면 JDK 21에서 `NoClassDefFoundError`가 난다(실측). Java 8에서는 로드되지 않는다(JDK 8 클래스 로드 기록). 선택지:
  - (a) Netty 4.1.139를 쓴다. C-22를 그대로 지킨다. grpc-java 1.85.1과 8u252·8·11·17·21·25에서 동작을 확인했다. 단점: grpc-java가 의존하는 계열(4.2)과 다르고, 4.1 유지보수 기간에 묶인다. 실제 로드되는 `Unsafe` 참조 클래스가 17개로 많다.
  - (b) Netty 4.2를 쓰고 C-22에 "실행 시점 버전 검사로만 도달하는 클래스는 경로 패턴 허용 목록으로 major 55까지 허용"을 덧붙인다. JDK 8 클래스 로드 0건을 CI 검사로 둔다.
  - 이 ADR은 C-22를 바꾸지 않는 선택인 (a)를 기본으로 제안한다.
- **(P2) `Unsafe` 허용 목록 조건.** ADR-0003은 "런타임에 로드되지 않음"을 증명한 클래스만 허용한다. 측정에서 로드되지 않는 `Unsafe` 참조 클래스는 jar에서 빼도 동작했다(`*-x` jar, JDK 8·21·25). 그러나 로드되는 클래스가 남는다: Netty `PlatformDependent0`·`PlatformDependent`·`CleanerJava9`(4.1은 JCTools 필드 오프셋 클래스 13개 추가), Guava `AbstractFutureState$UnsafeAtomicHelper`(로드됨). 이 클래스들은 `noUnsafe` 설정과 라이브러리의 자체 판단으로 `Unsafe`를 쓰지 않는다(JDK 25 warn 경고 0, deny 동작). 허용 조건을 "로드될 수 있으나 설정으로 `Unsafe` 사용이 꺼져 있고, JDK 25 warn에서 경고 0·deny에서 동작함을 테스트로 증명"으로 넓힐지 정해야 한다. 넓히지 않으면 조건을 만족하는 후보는 HTTP/1.1뿐이다(`http1-full-x`: 허용 목록 0으로 check-shading 통과).
- **(P3) Windows의 NIO 선택기 temp 파일과 T-BLD-006.** JDK 16 이상 Windows에서 NIO `Selector`는 웨이크업 파이프를 AF_UNIX 소켓으로 만들며 temp 디렉터리에 `socket_<n>` 파일을 잠깐 만들고 지운다(경로가 108자 이하일 때). grpc-netty 에이전트는 JDK 21에서 매번 1개를 만들었다(grpc-okhttp·HTTP/1.1·base는 0). Linux는 이 파일을 만들지 않는 것으로 알려져 있으나 이 환경에서는 재지 않았다. Windows는 호환성 매트릭스의 2열 플랫폼이다. T-BLD-006에서 "JVM 내부 AF_UNIX 파이프(Windows)"를 예외로 둘지 정해야 한다.
- 결정 전까지 비교 하네스(`test/agent/transport-poc`)는 (P1)·(P3)을 실패로 세지 않고 그 발견 자체를 단언한다(위반이 사라지거나 다른 위반이 생기면 실패, `-Ptransport.poc.strict=true`면 원래대로 실패). WO-02 통합(2026-11-02)에서 바꿨고 근거는 `docs/poc/agent-poc.md` 9.6이다. 배포 jar의 기준(`verifyAgentJar`, 부착·샘플 앱 테스트의 T-BLD-006)은 그대로다.

## 검토한 대안

| 대안 | 장점 | 단점 | 채택하지 않은 이유 |
|---|---|---|---|
| grpc-okhttp | jar·메타스페이스가 netty보다 작다(−0.8MB, −4.2MiB). Netty·NIO가 없어 C-22 위반·Windows temp 파일·Netty `Unsafe` 클래스가 없다 | 전송 스레드 3개(transport executor 2개, 그중 하나는 gRPC가 `OkHttpClientTransport`로 이름을 바꿈, timer 1개). transport executor를 2개로 묶으면 재접속 중 `RejectedExecutionException` → 채널 "Panic"(실측). okio 3이 kotlin-stdlib(압축 약 1.3MB)를 끌고 온다. 기본 ConnectionSpec이 TLS 1.2로 협상 | 스레드 예산 6(10 §14: sender·metrics·watch·io·logger·command)을 넘고, 스레드 상한을 두면 채널이 망가진다 |
| 자체 HTTP/1.1 + protobuf | 가장 작다(6.05MB, base +1.98MB). 스레드 0, 직접 메모리 거의 0, ALPN 불필요(8u242 동작), Windows temp 0. 로드되지 않는 `Unsafe` 클래스만 있어 허용 목록 0으로 check-shading 통과 | 11 §3의 `Session` 양방향 스트림을 만들 수 없다(롱 폴링 연결·스레드가 따로 필요). 배치 in-flight 4는 연결 4개 또는 파이프라이닝이 필요. 서버에 HTTP 엔드포인트와 상태 코드 규칙(11 §10)을 새로 정의해야 한다 | DSN-02-11과 11 문서 재설계가 필요하다(이 WO 범위 밖). (P2)가 "넓히지 않음"으로 정해지면 다시 검토할 대체안 |
| Netty 4.2 그대로 + C-22 유지 | grpc-java 기본 조합 | 51개 클래스 C-22 위반 | (P1) 결정 전에는 C-22 위반 |
| `protobuf-javalite` | jar 약 1.2MB 감소, `map`·`optional` 지원 | JDK 24+ 경고, deny에서 동작 불가 | F-A35 위반 |
| ORIGINAL(프로퍼티 이름 relocate 제외) | 운영자가 `-Dio.netty.*`로 조정 | 앱과 에이전트가 설정을 공유. Shadow 기본과 반대 | ADR-0008(셰이딩) 결정 유지 |

## 결과

- 쉬워지는 것: 11 문서 프로토콜과 12 문서 서버(grpc-java)를 그대로 쓴다. 전송 스레드 1개, 직접 메모리 수 KB로 N-26 예산 안이다. JDK 8u252~25에서 같은 코드가 동작한다.
- 어려워지는 것·새 의무
  - jar가 12MB대로 커진다(check-shading 12MB 경고). 남은 여유는 약 3MB다. 의존성 추가 때마다 크기를 확인한다.
  - 메타스페이스가 base 대비 약 17MiB 늘어 에이전트 전체 약 24MiB다(N-26 32MiB). 다른 기능의 여유가 8MiB 남짓이다.
  - WO-11은 JUL relocate 셰임, gRPC minimize 루트, Netty relocate 프로퍼티, `Unsafe` 클래스 제외 목록·허용 목록(P2 결정 후), 자체 리터럴 검사, 8u252 미만 ERROR 1줄 경로를 구현하고 각 항목을 테스트로 고정한다. 제외 목록은 P-3 경로(접속·전송·실패·종료)에서 얻었으므로, WO-11의 세션·재접속·gzip 경로를 넣은 뒤 클래스 로드 기록으로 다시 확인한다.
  - (P1)에서 (a)를 고르면 Netty BOM을 4.1.139로 고정하고 grpc-java 업그레이드마다 4.1 호환을 확인한다.
- 되돌리는 비용: 전송은 익스포터 SPI(`grpc` 슬롯) 뒤에 있어 HTTP/1.1로 바꿀 때 에이전트 쪽 변경은 전송 클래스에 갇힌다. 비용의 대부분은 프로토콜(11)·서버(12) 재설계다.

## 확인 사항

- [ ] PM이 A-1(grpc-netty)과 A-14(javalite 불채택)를 확정하고 02 §12 표를 갱신했다.
- [ ] PM이 (P1) Netty 계열, (P2) `Unsafe` 허용 조건, (P3) Windows temp 파일을 결정했다.
- [x] 코어 담당이 `IgnoreMatchers`의 relocate되는 리터럴(`net.bytebuddy.`)을 고치고 agent-dist에 자체 리터럴 검사를 넣었다. WO-02 통합(2026-11-02): `IgnoreMatchers.APP_BYTEBUDDY_PREFIX`를 실행 시점에 만들고, `:agent:agent-dist:checkOwnRelocatedLiterals`(`verifyAgentJar`에 포함, 허용 목록 비어 있음)를 추가했다. 옛 리터럴로 되돌리면 이 검사가 `IgnoreMatchers.classdata: io.argos.agent.shaded.net.bytebuddy.`로 실패함을 확인했다(`docs/poc/agent-poc.md` 12장).
- [ ] 11 문서 data.proto에 `import "argos/v1/session.proto"`(Ack의 `Throttle`)를 추가했다(P-3 스냅샷에서 컴파일 오류로 발견).
- [ ] 전용 장비에서 `./gradlew :test:agent:transport-poc:transportPocBench`를 다시 돌려 수치를 확인했다.
