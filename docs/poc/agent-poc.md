# 에이전트 PoC (WO-02: P-1·P-3·P-6)

- 상태: **WO-02 통합 완료(2026-11-02), PMO 수정 1라운드(CHANGELOG 항목, 14.4 데이터 분류 요청) 뒤 재검증 녹색(12.7), 사람 수락·PM 결정 대기.** 코어(1~7장), 계측(8장), P-3 전송 비교(9장), P-6 JDK 클래스 계측(10장)을 한 문서로 합쳤고, 통합 검증 실행표는 12장이다. P-2 오버헤드는 [`agent-overhead.md`](agent-overhead.md)(13장 요약). 결정 제안은 11장과 [ADR-0005 전송](../adr/0005-agent-transport.md)(Proposed), 셰이딩 결정은 [ADR-0008](../adr/0008-agent-shading-and-bytebuddy-unsafe.md).
- 관련: 02 §11 P-1·P-2·P-3·P-6·§12 A-1·A-14, DSN-02-01~03, 10 §2~§5·§6.1~§6.3·§10.3·§14·§18, 11 §15 #2~#4, 19 §3.3·§5.6·§13 #4~#6, ADR-0003 C-21~C-25·C-27, ADR-0004 C-46·C-49, ADR-0005(전송), ADR-0008(셰이딩·C-23), 18 T-BLD-001~006·T-AG-010·040·043·060·070
- 측정 환경: Windows 11 Home 개발 머신(Ryzen 5 5600H 6C/12T, 15.3 GiB) 한 대. 2026-10-24 레인 측정은 WO-03(ClickHouse 부하)과 동시에 돈 공유 상태였고, 2026-11-02 통합 재실행은 다른 작업 없이 했다(실행 직전 CPU 부하를 함께 적는다). **모든 수치는 참고값이고 Gate 0 수치는 사람이 전용 머신에서 다시 잰다.**
- 결론 요약
  - 단일 jar(19 §3.3 3단계 조립) → bootstrap 클래스로더 append → 격리 `AgentClassLoader`(`inst/**/*.classdata`, 임시 파일 0) → `AgentStarter` → ByteBuddy Advice 인라인 구조가 JDK 8·11·17·21·25에서 동작한다. 앱에는 bootstrap API만 보이고 코어·계측 API·셰이딩 ByteBuddy·모듈 색인은 보이지 않는다(1~4장).
  - T-AG-010 PoC: shop-boot2 × JDK 8·11·17·21, shop-boot3 × JDK 17·21(+25)에서 `GET /api/orders/1`이 트랜잭션 1건(`GET /api/orders/{id}`, 200, elapsed)과 앱이 센 수와 같은 SQL step 1개를 JSONL에 남긴다. 모든 실행은 C-27 플래그(`-XX:+UnlockDiagnosticVMOptions -XX:+BytecodeVerificationLocal`)로 돈다(8.5, 12.3).
  - T-AG-070: JDK 17·21·25(25는 `--sun-misc-unsafe-memory-access=deny`)에서 `HttpURLConnection`·`ThreadPoolExecutor` 계측이 동작하고 `IllegalAccessError`는 0이다. premain 전에 로드된 두 클래스의 retransform도 JDK 8~25에서 동작한다(10장).
  - T-AG-040 PoC: servlet, 그리고 jdbc·executor·httpclient-jdk Advice가 매번 던져도 앱 응답은 2xx이고 같은 본문이다(8.5, 10.3).
  - T-BLD-001~006: 배포 jar(클래스 2,648개, 4.07MB)가 `check-shading.sh`와 `verifyAgentJar`를 통과한다. 기동·실행 중 임시 파일 0, stdout 0을 부착 테스트(JDK 5종)와 샘플 앱 기준선 비교로 확인했다(5장, 8.5, 12.2).
  - P-3: grpc-netty·grpc-okhttp·자체 HTTP/1.1 × protobuf full·lite를 비교했다. **A-1은 gRPC over grpc-netty 유지, A-14는 javalite 불채택, C-23은 RELOCATED 확정 + 자체 리터럴 규칙**을 제안한다(9장, 11장). Netty 계열(P1), `Unsafe` 허용 조건(P2), Windows NIO temp 파일(P3)은 PM 결정이 필요하다.
  - 통합에서 고친 것: 배포 jar의 ignore 목록이 셰이딩 때문에 앱의 ByteBuddy를 놓치던 결함(`"net.bytebuddy."` 리터럴 relocate)을 고치고 재발을 막는 `checkOwnRelocatedLiterals`를 넣었다(11.3). 그 밖은 12.1.
  - P-2 오버헤드는 공유 머신에서 판정하지 못했다. 전용 장비 절차는 `agent-overhead.md` 9장이다(13장).

## 1. 단일 jar 구조 (10 §4.1, 19 §3.3, C-21·C-22·C-25)

`./gradlew :agent:agent-dist:agentJar` → `agent/agent-dist/build/libs/argos-agent-<버전>.jar`

| 단계 | 태스크 | 내용 |
|---|---|---|
| 1 | `shadowJar` | `agent-core` + 계측 모듈 + 런타임 의존성(지금은 ByteBuddy 1.18.15)을 `io.argos.agent.shaded`로 relocate, `minimize()`, 서비스 파일 병합, NOTICE·LICENSE 변환기, 서명·`module-info`·`META-INF/versions/**` 제거, ByteBuddy `Unsafe` 참조 4개 제거(ADR-0008) → `build/shadow/agent-inst.jar` |
| 2 | `classdataJar` | `.class` → `inst/**/*.classdata`, 리소스는 `inst/` 아래로 |
| 3 | `agentJar` | 루트에 `agent-bootstrap` 클래스와 `io/argos/agent/AgentPremain.class`, `inst/`, `META-INF/{LICENSE,NOTICE,THIRD-PARTY-NOTICES,argos-deps.txt,sbom/agent.cdx.json}`. 매니페스트 `Premain-Class`·`Agent-Class`·`Main-Class` = `io.argos.agent.AgentPremain`, `Can-Retransform-Classes: true`, `Can-Redefine-Classes: false`, `Argos-Commit`, `Argos-Protocol-Version`(C-21). `Add-Opens`·`Boot-Class-Path`·`Launcher-Agent-Class` 없음 |
| 4~5 | `generateDepsManifest`, `generateThirdPartyNotices`, `agentSbom` | `argos-deps.txt`(`net.bytebuddy:byte-buddy:1.18.15:Apache-2.0`), THIRD-PARTY-NOTICES(원 NOTICE 본문 + ByteBuddy jar 안의 ASM 라이선스 `META-INF/licenses/ASM`), CycloneDX SBOM(byte-buddy 컴포넌트 포함) |
| 6~8 | `verifyAgentJar` = `checkShading`, `checkRelocatedStrings`(RELOCATED), `checkOwnRelocatedLiterals`(C-23 부작용, 통합 때 추가: 11.3), `checkMajorVersion`(C-22), `checkModuleIndex`(C-25) | `check`에 연결 |

- 모듈 발견(C-25): 각 계측 모듈은 `src/main/resources/META-INF/services/io.argos.agent.instrumentation.api.InstrumentationModule`에 구현 클래스를 적는다. Shadow가 병합해 `inst/META-INF/services/`에 두고, 코어 `ModuleRegistry`가 `AgentClassLoader`로 읽어 인스턴스를 만든다. 코어는 모듈 클래스를 컴파일 타임에 참조하지 않는다(B-3). `checkModuleIndex`는 색인의 클래스가 jar에 모두 있는지 검사한다.
- 코어 선행 단계 jar(계측 모듈 0개): 클래스 2,611개(자체 104, 셰이딩 2,507), 4,009,980 bytes(2026-10-24 16:11 빌드). 통합 최종 jar(계측 모듈 5개)는 12.2. ByteBuddy 3,050개 중 `minimize`로 약 540개가 빠졌다. **리플렉션으로만 쓰이는 ByteBuddy 클래스가 빠질 위험**이 있으므로 계측 모듈을 넣을 때마다 `AgentAttachTest`·샘플 앱 테스트로 확인한다(19 §13 #4).
- `-Xlint:classfile`: ByteBuddy 클래스의 SpotBugs 애너테이션(LGPL jar, 추가 금지) 때문에 javac가 경고를 낸다. `argos.java-agent` 컨벤션에서 에이전트 모듈 전체에 `-Xlint:-classfile`을 둔다(의존성 클래스 파일 읽기에 관한 범주만 끈다).

## 2. 클래스로더와 격리 (10 §4.2, DSN-10-01)

| 항목 | 구현 |
|---|---|
| 위치 | `io.argos.agent.bootstrap.AgentClassLoader`(package-private). 10 §2는 `core.boot`에 두었지만 클래스로더는 자기 클래스를 로드할 수 없어 bootstrap 패키지에 둔다. 앱은 클래스는 볼 수 있어도 생성할 수 없다(아래 테스트) |
| 부모 | JDK 9+ `ClassLoader.getPlatformClassLoader()`(리플렉션), JDK 8 `null`. `--check` 모드는 시스템 클래스로더 |
| 위임 | `io.argos.agent.core.`·`instrumentation.`·`shaded.`는 자기만 정의(부모에 묻지 않음), 나머지는 부모 우선 |
| 클래스 | `inst/<이름>.classdata`를 열린 `JarFile`에서 읽어 `defineClass`. `Package` 정의, `ProtectionDomain` = jar `CodeSource` |
| 리소스 | `name` → `inst/name`, `x.class` → `inst/x.classdata`(ByteBuddy가 Advice·helper 클래스 파일을 읽는다). JDK 8에서는 부모(bootstrap)가 append된 jar의 리소스를 못 보므로 `io/argos/agent/bootstrap/*.class`는 jar 루트에서 대신 준다 |
| 임시 파일 | 없음. jar 추출·`Boot-Class-Path`·임시 jar 주입을 쓰지 않는다 |

`AgentPremain`(시스템 클래스로더가 로드)은 bootstrap 타입을 직접 참조하지 않고 `Class.forName("io.argos.agent.bootstrap.AgentBootstrap", true, null)`로 bootstrap 사본을 부른다. 코어가 bootstrap 상태(트레이서 설치, 킬 스위치, 모듈 상태)를 바꿀 때는 `AgentBootstrap`이 만들어 넘긴 `AgentControl` 객체를 쓴다(공개 생성자·정적 접근자 없음).

## 3. premain 시퀀스와 기동 시간 (10 §4.3, N-30)

| 단계 | 구현 |
|---|---|
| P0 | `-Dargos.agent.disabled=true`면 즉시 return(아무것도 설치하지 않음) |
| P1 | `appendToBootstrapClassLoaderSearch(new JarFile(agentJar))` |
| P2 | `AgentBootstrap.start` → `AgentClassLoader` → `core.boot.AgentStarter.start` |
| P3·P4 | 설정(`-Dargos.*` → `ARGOS_*`, YAML은 WO-10) → `AgentLogger` 초기화(파일 롤링 또는 stderr 제한 폴백) → 설정 경고 로그 |
| P5 | `TracerImpl` 설치, `ModuleRegistry`(C-25), 모듈 상태(`instrumentation.disabled`, `argos.test.faults`) |
| P6 | `ArgosAgentBuilder`(ADR-0008) + RETRANSFORMATION(이미 로드된 클래스는 JDK 허용목록만 후보, `PremainDiscovery`) + read edge·변환 기록·변환 오류 리스너 + 모듈별 type 규칙 → `installOn` |
| P7 | `argos-sender`(익스포터 생성·배치 전송), `argos-watch`(변환 목록 기록, ContextStore 정리), 종료 훅 `argos-shutdown` |
| P8 | INFO 배너 1줄: 버전·커밋·프로토콜·app/env·익스포터·모듈·JVM·로그 위치. `-Dargos.debug.transform-timing=true`면 단계별 ms와 클래스별 변환 시간 |

기동 시간 최적화(측정으로 찾은 것)

| 원인 | 측정 | 조치 |
|---|---|---|
| `TxIdGenerator`의 `new SecureRandom()`(노드 id 12비트) | Java 8/Windows에서 P5 700~940ms(보안 프로바이더 로딩) | `ThreadLocalRandom` + 시계 혼합으로 대체(서버 Hello 뒤 교체되는 임시 값). 10 §9.5와 다른 점은 `TxIdGenerator` javadoc과 WO-02 보고서에 기록 |
| 로거 타임스탬프 `java.time`(`DateTimeFormatter`) | Java 8 P3 150~350ms | 자체 ISO-8601 포맷터(`Timestamps`, `TimeZone` 오프셋) |
| retransform 후보 = 로드된 전체 클래스 | P6 증가 | JDK 허용목록 이름만 후보(`PremainDiscovery`) |

참고값: 2026-10-24 16:01~16:03, 코어 레인의 `measure-start.sh`(원시 파일 `summary.txt`는 세션 스크래치에 있었고 2026-11-02 이전에 삭제되었다. 아래 표는 그 파일에서 옮긴 값이다. 통합 재실행의 premain 값은 12.3). 프로브 jar(테스트 모듈 2개: 앱 타입 2개 + `ThreadPoolExecutor`), 익스포터 noop, 기본 CDS, JDK별 5회. "직전 부하"는 매 실행 직전 `Win32_Processor.LoadPercentage`.

| JDK | 직전 부하 | 배너 started in(P0~P8) | P6 builder | 그중 installOn |
|---|---|---|---|---|
| 8u504 (Temurin) | 14~23% | 231~257 ms | 191~216 ms | 67~75 ms |
| 11.0.32.1 (Temurin) | 8~30% | 323~348 ms | 273~293 ms | 170~187 ms |
| 17.0.20.1 (Temurin) | 10~32% | 315~367 ms | 270~312 ms | 166~196 ms |
| 21 GA (21+35-2513) | 5~26% | 392~418 ms | 321~345 ms | 174~188 ms |
| 25 (Oracle OpenJDK) | 15~28% | 378~411 ms | 314~336 ms | 158~174 ms |

모듈 0개(배포 jar)일 때는 71~162ms다(`AgentAttachTest` 출력, `-XX:+BytecodeVerificationLocal` 켬). 계측 모듈이 늘면 P6의 type 규칙·Advice 해석이 늘어난다. N-04(앱 기동 +2초 이하)와 heavy 앱 기준 판정은 P-2(`agent-overhead.md`)와 C-49 정의로 한다.

## 4. JPMS와 ignore 목록 (10 §4.4·§4.5, DSN-02-03, U-3, N-27)

### 4.1 JPMS

- `ModuleReadEdgeListener`: 변환되는 타입이 named 모듈이면 `onTransformation`(클래스 정의 전)에서 그 모듈 → bootstrap unnamed 모듈(`Argos`가 있는 곳) read edge를 `Instrumentation.redefineModule`로 추가한다. 모듈당 1회(상한 1,024). 수정 불가 모듈은 WARN 1회.
- helper 주입: `HelperInjector`가 대상 클래스로더에 `ClassLoader#defineClass`(리플렉션)로 helper를 정의한다. JDK 9+는 처음 쓸 때 `java.base/java.lang`을 **에이전트 클래스로더의 unnamed 모듈에만** 연다. JDK 9~15는 `--illegal-access=permit` 때문에 `isOpen`이 참이어도 그 경로를 쓰면 "illegal reflective access" 경고가 stderr에 나오므로(JDK 11 실측) 항상 명시적으로 연다. named 모듈 대상이면 그 모듈 → 대상 클래스로더 unnamed 모듈 read edge도 추가한다.
- U-3 결과: ByteBuddy `ClassInjector.UsingReflection`은 JDK 9+에서 `Unsafe` 경로를 거치므로 쓰지 않았다. 위 자체 주입이 JDK 11·17·21·25에서 동작한다(프로브 helper가 앱 클래스로더에 정의됨, `AgentAttachTest`).
- bootstrap 클래스로더 대상(JDK 클래스) 모듈은 helper를 가질 수 없다. Advice가 bootstrap API만 쓴다(`ProbeJdkModule` 예). helper를 선언하면 그 타입 변환이 실패로 집계된다.

### 4.2 ignore 목록 (10 §4.5, N-27)

`core.boot.IgnoreMatchers`가 모듈 매처보다 먼저 `AgentBuilder.ignore(..)`로 걸린다. 타입 이름과 클래스로더만 보고(타입 계층 해석 없음) 판정하므로 변환 후보가 아닌 클래스는 모듈 매처까지 가지 않는다.

| 항목(10 §4.5) | 규칙 |
|---|---|
| 1 Argos 자신 | `io.argos.*`(셰이딩 라이브러리 포함), synthetic 타입, 에이전트 클래스로더의 모든 클래스, 리플렉션 접근자 로더(`sun.reflect.DelegatingClassLoader`, `jdk.internal.reflect.DelegatingClassLoader`)의 클래스 |
| 2 생성 클래스 | 이름에 `$$Lambda`, `$Proxy`, `$$SpringCGLIB$$`, `$$EnhancerBy`, `$HibernateProxy$`, `ByteBuddy$`, `$$FastClassBy`, `_$$_jvst`가 있으면 제외 |
| 3 JDK·Jakarta EE | `java.`·`javax.`·`jdk.`·`sun.`·`com.sun.`·`jakarta.` 제외. 앱·컨테이너가 구현하는 `javax.servlet.`·`javax.sql.`·`jakarta.servlet.`은 대상이다. JDK 클래스는 `JDK_ALLOWLIST`·`JDK_ALLOWLIST_PREFIXES`(`HttpURLConnection`, `sun.net.www.protocol.http(s)`, `ThreadPoolExecutor` 등 실행기, `CompletableFuture` 내부 작업, `jdk.internal.net.http.HttpClientImpl`)에 있을 때만 변환된다. premain에서 이미 로드된 클래스도 이 목록만 retransform 후보다(`PremainDiscovery`) |
| 4 다른 에이전트·런타임 | `org.jacoco.`, `io.opentelemetry.javaagent.`, 앱의 `net.bytebuddy.`, `org.groovy.`, `org.codehaus.groovy.`, `kotlin.reflect.jvm.internal.` |
| 5 로깅 내부 | `ch.qos.logback.`, `org.apache.logging.log4j.`, `org.slf4j.`. logging 모듈 대상 2개(`LoggingEvent`, `ThreadContextDataInjector`)는 제외하지 않는다 |
| 6 현장 우회 | `instrumentation.exclude-classes` 글롭(`*`는 패키지 세그먼트 안, `**`는 세그먼트를 넘음, `?`는 한 문자, T-AG-042) |

- 셰이딩 부작용(통합에서 수정, 11.3): 4항의 `"net.bytebuddy."` 리터럴을 Shadow가 `"io.argos.agent.shaded.net.bytebuddy."`로 바꿔 배포 jar에서는 앱의 ByteBuddy 클래스가 ignore 목록에서 빠져 있었다(P-3 레인 발견). 이제 `APP_BYTEBUDDY_PREFIX`를 실행 시점에 만들고, `checkOwnRelocatedLiterals`가 자체 클래스의 relocate 네임스페이스 상수를 0으로 강제한다.
- 효과(8.5 변환 목록): shop-boot2·shop-boot3에서 변환 클래스는 18개뿐이고 `io.argos.*`·JDK 비허용 클래스·생성 프록시는 0이다.

## 5. 검증 (WO-02 코어 선행 단계)

| 검증 | 결과 | 명령·위치 |
|---|---|---|
| 단일 jar 검사(T-BLD-001~006 정적 부분) | 통과: 패키지·루트 노출·META-INF·서명·major(자체 52/셰이딩 ≤ 52)·Unsafe 0·네이티브 0·크기 4.0MB·중복 0 | `./gradlew :agent:agent-dist:verifyAgentJar`, `bash test/agent/check-shading.sh …` |
| C-22·C-23·C-25 검사 | 통과(`relocated-strings.txt`: ByteBuddy 7개 전부 relocate 형태만), 모듈 색인 검사 | `build/reports/agent-jar/` |
| 부착 테스트 JDK 8·11·17·21·25 × 7시나리오 | 35/35 통과 | `./gradlew :agent:agent-testing:integrationTest -Pagent.attach.jdks=8,11,17,21,25` |
| ArchUnit(C-24 ADV-03 포함) | 92/92 통과 | `./gradlew :agent:agent-testing:archTest` |
| 단위 테스트(JDK 8에서 실행) | bootstrap 26, core 43, api 5 통과 | `./gradlew :agent:agent-bootstrap:test :agent:agent-core:test :agent:agent-instrumentation:api:test` |

부착 테스트 시나리오(`AgentAttachTest`, 앱 `example.trivial.TrivialApp`)

1. 배포 jar: 임시 파일 0(자식 JVM의 `java.io.tmpdir`와 `TMP`·`TEMP`·`TMPDIR`을 감시 디렉터리로 지정, `WatchService` 이벤트 0, 실행 후 비어 있음, 시스템 temp에 `argos*`·`bytebuddy*` 신규 0, jar 옆 추출 0), stdout = 앱 1줄, stderr = 비었거나 JVM CDS 안내만, 격리(bootstrap 보임, core·api·shaded·ByteBuddy·`.class` 리소스·모듈 색인 안 보임), 로그 ERROR·WARN 0.
2. `-Xshare:off`: stderr 0바이트(CDS 안내가 JVM 출력임을 확인).
3. 프로브 jar: JSONL 트랜잭션 3건(서비스 이름, 200, SQL step, `ThreadPoolExecutor#execute` ASYNC step), helper가 앱 클래스로더에 정의됨, 변환 목록에 앱 타입 2개와 `java.util.concurrent.ThreadPoolExecutor test-probe-jdk bootstrap`.
4. 킬 스위치 2단(`-Dargos.agent.disabled=true`): 로그 디렉터리·익스포터 파일·변환 목록 없음, 에이전트 스레드 0.
5. 킬 스위치 1단(`-Dargos.enabled=false`): 변환은 설치, 트랜잭션 0.
6. Advice 예외 주입(`-Dargos.test.faults=advice-throw:test-probe`): 앱 결과 정상, 트랜잭션 0, 모듈 에러 WARN.
7. `-verbose:class`: ByteBuddy `Unsafe` 경로 클래스 로드 0, JDK 23+는 `--sun-misc-unsafe-memory-access=deny`로도 정상.

모든 자식 JVM은 C-27 플래그(`-XX:+UnlockDiagnosticVMOptions -XX:+BytecodeVerificationLocal`, 2번만 제외)와 `-XX:-UsePerfData`(JVM 자신의 hsperfdata가 감시 디렉터리에 생기지 않게)로 돈다.

## 6. 확인 작업 결과 (04 WO-02)

| 항목 | 결과 |
|---|---|
| 10 U-3 (`ClassInjector` + `redefineModule`) | ByteBuddy `ClassInjector.UsingReflection`은 JDK 9+에서 `Unsafe`로 접근권을 얻으므로 쓰지 않는다. `redefineModule(java.base, extraOpens java.lang → 에이전트 unnamed 모듈)` 뒤 자체 `defineClass` 리플렉션이 JDK 11·17·21·25에서 동작(4장). 실험 근거(JDK 17·21·25, JDK 25 deny에서 `UsingReflection` 불가, 자체 방식 동작)와 jdbc helper 실측은 10.4 |
| 10 U-4 (8u252 ALPN) | 확인. AdoptOpenJDK 8u242에서 grpc-netty는 TLS 컨텍스트를 만들지 못해 전송만 끄고(ERROR 1줄), 8u252부터 ALPN 내장으로 TLS 1.2 배치를 전달한다(9.4.4). `--check`는 1.8.0_252 미만을 FAIL로 판정한다(`CheckCommand`) |
| 10 U-5 (ByteBuddy 최소 버전·`experimental`) | 1.18.15 사용, 클래스 major 52(Java 8 로드 가능), JDK 25 실행 확인. `net.bytebuddy.experimental`은 셰이딩 후 `io.argos.agent.shaded.net.bytebuddy.experimental`로만 남는다. 에이전트는 이 값을 설정하지 않는다. 버전별 실측: 클래스 파일 69를 `experimental` 없이 처리하는 최소 버전은 시험한 것 중 1.16.1(1.15.11 이하는 `experimental` 필요), 10.4 |
| 10 U-6 / 19 §13 #6 (프로퍼티 문자열 relocate) | Shadow가 relocate한다(실측). 정책 RELOCATED로 결정(ADR-0008), `checkRelocatedStrings`가 강제. 셰이딩 jar 전수 조사(ByteBuddy 프로퍼티 이름 전부 relocate, 비relocate 1건은 오류 메시지 문자열)는 10.4 |
| 19 §13 #4 (`minimize` 제외 목록) | ByteBuddy는 제외 없이 동작(JDK 5종). `Unsafe` 참조 4개는 명시적으로 제외. gRPC는 제공자를 `ServiceLoader`로 읽으므로 `io.grpc:*`를 minimize 루트로 둔다. Netty·Guava·protobuf는 minimize해도 동작(9.4.9) |
| 19 U-5·§13 #5, 11 §15 #2 (javalite) | javalite는 `map`·`optional`을 지원하지만 JDK 24+에서 `Unsafe` 경고, deny에서 실패한다. 채택하지 않는다(A-14, 9.4.5·9.4.7) |
| 11 §15 #3 (전송 구현) | grpc-netty NIO·JDK TLS 가정 유지(A-1 제안, 9장·11장) |
| 11 §15 #4 (Java 8 하한) | 전송은 8u252 이상. 미만은 ERROR 1줄 + 전송 비활성(9.4.4). 8u261 TLS 1.3 가정은 이 환경에 해당 빌드가 없어 확인하지 않았다 |

## 7. 알려진 제약·후속

- CDS 안내: JDK 17 이상 기본 CDS에서 `appendToBootstrapClassLoaderSearch` 때문에 JVM이 stderr에 1줄을 쓴다. 에이전트 출력이 아니며 10 §14가 알려진 제약으로 둔다. T-AG-060 하네스(WO-05·WO-19)는 이 줄을 허용 목록에 넣거나 `-Xshare:off`로 판정해야 한다.
- 로그 디렉터리 기본값의 temp 폴백: `log.dir` 미설정 + jar 옆 `../logs` 쓰기 불가면 10 §14대로 `java.io.tmpdir/argos-agent-<pid>`를 쓴다. 이 경우 T-BLD-006("기동 중 /tmp 감시")이 디렉터리 생성을 잡는다. 부착 테스트는 `log.dir`을 지정해 이 경로를 쓰지 않는다. PM 결정 필요(보고서).
- `DictKind` 번호: C-16 목록 순서대로 1~11(10 §2의 1~9 + DB_TARGET 10, APP_VERSION 11)로 했다. 11 §3 proto는 DB_TARGET 8, APP_VERSION 9라 WO-11 전에 맞춰야 한다(보고서).
- 사전 해시: WO-02는 FNV-1a 64를 쓴다. DSN-10-18의 xxh3_64는 WO-10(`core.hash.Xxh3`)이 바꾼다. 서버에 저장되는 id가 생기기 전에 바꿔야 한다.
- 설정 파일: `argos-agent.yml`(`argos.config`)은 아직 읽지 않는다(경고 1줄). WO-10.

## 8. P-1 계측 구조 결과

### 8.1 계측 API와 모듈 작성 규칙 (10 §5)

| 항목 | 구현 |
|---|---|
| API | `InstrumentationModule`, `TypeInstrumentation`, `TypeTransformer`, `SafeMatchers`, `Witness`, `HelperClasses`(신규: helper를 단순 이름으로 선언, 모듈 패키지·`*Helper*`·중복 검사), `ModuleIds` |
| Advice 인자 타입 | 라이브러리 타입(`HttpServletRequest` 등)은 Advice 매개변수에서 `Object`로 받고 helper가 캐스팅한다. Advice는 bootstrap 타입과 자기 모듈 helper만 참조한다(B-2, ArchUnit). `AdviceFactory`는 에이전트 클래스로더의 `TypePool`로 Advice를 해석하므로 그 로더에 없는 라이브러리 타입이 Advice 시그니처에 있으면 인라인 단계에서 해석에 실패한다(`AdviceFactoryTest`가 실패와 `Object` 매개변수의 성공을 보인다). JDK 타입(`java.sql.*` 등)은 에이전트 로더의 부모가 보므로 쓸 수 있다 |
| javax/jakarta | 네임스페이스마다 Advice 1개·helper 1개, 공통 로직은 서블릿 타입이 없는 `*HelperCommon`에 둔다. 모듈의 helper 전부를 witness가 맞는 로더에 주입한다. 없는 네임스페이스의 helper는 정의만 되고 링크되지 않는다(그 네임스페이스 Advice만 호출하므로) |
| 매처 비용 | 서브타입 매칭(Filter)은 `declaresMethod(doFilter…)`와 `not(isInterface())`를 먼저 두어 계층 탐색 대상을 줄였다. `doFilter`를 상속만 하는 필터(Spring `OncePerRequestFilter` 하위 클래스)는 변환하지 않는다. shop-boot3 변환 클래스: 19개 → 7개 |
| 모듈 일관성 검사 | `archTest`의 `ModuleChecks`(ASM): 모듈 색인 등재, `id()` = Gradle 모듈 이름, 운영 moduleId 범위, helper 클래스 파일·정적 상태 없음·상속 순서, Advice 클래스 파일·`*Advice`·애너테이션. 위반 픽스처로 각 검사가 실패함을 보인다 |
| 작성 절차 | `agent/agent-instrumentation/api/README.md` "Writing a module", 레인용 요약은 WO-02 계측 선행 단계 보고서 |

### 8.2 servlet·spring-webmvc (10 §6.1·§6.2·§7.1·§7.2, C-48)

| 규칙 | 구현 |
|---|---|
| 계측 지점 | `HttpServlet.service(ServletRequest, ServletResponse)`(이름), `doFilter(ServletRequest, ServletResponse, FilterChain)`를 선언하고 `Filter`를 구현하는 클래스(`SafeMatchers`). javax 3.1·jakarta 5.0 API로 컴파일(`compileOnly`) |
| 트랜잭션 시작 | 요청 속성 `argos.tx`가 없고 `DispatcherType.REQUEST`일 때만. 제외 규칙(`Tracer.ignoreReason`, 10 §7.2 기본값)에 걸리면 `argos.ignored`를 두고 카운트만 한다 |
| 참여·종료 | 중첩 호출과 같은 요청의 FORWARD·INCLUDE·ERROR·ASYNC 디스패치는 깊이 +1. 최외곽 종료에서 `response.getStatus()`, 예외 탈출 + status < 400이면 500(D-21, `ServletException`은 root cause 기록). `startAsync` 뒤에는 요청 스레드가 컨텍스트를 놓고 요청을 마무리하는 ASYNC 디스패치가 끝낸다 |
| `/error` | Tomcat이 트랜잭션 종료 뒤 시작하는 ERROR 디스패치는 앵커가 없어 기록하지 않는다. spring-webmvc는 REQUEST 디스패치에서만 서비스 이름을 정한다(C-48). 핸들러 없는 404는 F-A29 폴백 `GET /api/not-found-x` |
| 서비스 이름 | spring-webmvc: `HandlerAdapter.handle` enter에서 `bestMatchingPattern` → `[컨텍스트 경로 ]METHOD 패턴` 고정(F-A36 접두). 패턴이 없으면 코어가 종료 시 `{*}` 정규화(숫자·UUID·32자 이상 16진), 앱당 10,000개 상한 후 `_other_` |
| 수집 | method, path(쿼리 없음, `;jsessionid` 등 경로 파라미터 제거), status, elapsed, remote IP(`capture.remote-ip` 기본 masked: IPv4 마지막 옥텟·IPv6 하위 80비트 0). 예외 메시지는 SEC-36 기본 세트로 마스킹 후 1KB 절단. `getParameter*`·`getReader`·`getInputStream`·`getSession` 호출 없음(D-26, 프록시 요청 단위 테스트로 확인) |
| PoC 밖 | 헤더 화이트리스트, user_id, 쿼리스트링 모드, `transactions_unanchored_total`, 잔존 컨텍스트 강제 종료, `HttpServletMapping` 폴백, ASYNC 디스패치 없는 비동기 완료(servlet-async 모듈) |

### 8.3 테스트 하네스 최소판 (`agent-testing` `src/main`)

- `AppLauncher`/`RunningApp`/`SampleApp`: WO-01 `sampleAppElements` 산출물과 `<app>.properties`로 jar 앱을 JDK × 에이전트 옵션으로 기동한다. 실행마다 작업 디렉터리(`tmp/`, 에이전트 jar 사본, `logs/`, `out/tx.jsonl`, `out/transformed.txt`, `stdout.log`·`stderr.log` 분리), C-27 플래그, 127.0.0.1 빈 포트, 배너 끔. 기동 시간은 포트 바인딩(C-49)과 `/actuator/health` 첫 200 두 가지를 잰다. WAR 앱은 WO-05가 지원한다.
- `JsonlTransactions`·`TransactionRecord`·`TransactionAssert`(JSONL 단언), `TransformList`(변환 목록), `AppOutput`(stdout 비로그 줄·에이전트 언급·stderr CDS 외 줄).
- WO-01 `AppProcess`·`SmokeRun`은 패키지 전용이고 stdout·stderr를 합쳐 한 파일로 쓰며 에이전트 부착 옵션이 없다. 그래서 기동·준비 대기·정지 로직을 하네스에 따로 두었다(WO-05에서 통합 검토).
- Windows의 `Process.destroy()`는 JVM 종료 훅을 돌리지 않으므로 테스트는 주기 기록(JSONL 약 100ms, 변환 목록 약 2초)을 기다린다.

### 8.4 첫 종단 증명 (T-AG-010 PoC, `SampleAppAgentTest`)

`./gradlew :agent:agent-testing:integrationTest`(기본 `shop-boot3@21`), 다른 조합은 `-Pagent.e2e=<app>@<jdk>,...`.

| 확인 | shop-boot3 × JDK 21 | 추가 실행 |
|---|---|---|
| `/actuator/health` 준비 폴링 | 트랜잭션 0(제외) | 같음 |
| `GET /api/orders/1` | 트랜잭션 정확히 1건: `GET /api/orders/{id}`, 200, elapsed, `remote_ip` `127.0.0.0` | shop-boot2 × JDK 8, shop-boot3 × JDK 17 통과 |
| 404·forward·500·503·정적 파일·preflight | `GET /api/not-found-x` 404(`/error` 서비스 0), `GET /api/forward` 1건, 500 + `java.lang.IllegalStateException` + 마스킹된 메시지, 503, 정적 파일·OPTIONS 0건 → 총 5건 | 같음 |
| 변환 목록 | 7개(아래), `io.argos.*` 0 | shop-boot2(javax)도 7개 |
| stdout·stderr | 앱 로그 줄과 그 연속 줄만, 에이전트 언급 0, stderr 0줄(C-27 플래그로 CDS 꺼짐) | 같음 |
| 임시 파일·로그 | 앱 temp에 `argos*`·`bytebuddy*` 0, 에이전트 로그 ERROR·WARN 0 | 같음 |
| Advice 예외 주입(`advice-throw:servlet`, T-AG-040 PoC) | 앱 200 ×3, 트랜잭션 0, 로그 `advice error in module 1` | - |

shop-boot3 × JDK 21 변환 목록(2026-10-24 17:11 수동 실행, `out/transformed.txt`)

```
jakarta.servlet.http.HttpServlet servlet org.springframework.boot.loader.launch.LaunchedClassLoader
org.apache.tomcat.websocket.server.WsFilter servlet org.springframework.boot.loader.launch.LaunchedClassLoader
org.springframework.web.filter.OncePerRequestFilter servlet org.springframework.boot.loader.launch.LaunchedClassLoader
org.springframework.web.servlet.function.support.HandlerFunctionAdapter spring-webmvc org.springframework.boot.loader.launch.LaunchedClassLoader
org.springframework.web.servlet.mvc.HttpRequestHandlerAdapter spring-webmvc org.springframework.boot.loader.launch.LaunchedClassLoader
org.springframework.web.servlet.mvc.SimpleControllerHandlerAdapter spring-webmvc org.springframework.boot.loader.launch.LaunchedClassLoader
org.springframework.web.servlet.mvc.method.AbstractHandlerMethodAdapter spring-webmvc org.springframework.boot.loader.launch.LaunchedClassLoader
```

참고값(공유 머신, WO-03 동시 실행, 직전 CPU 부하 기록, Gate 0 수치 아님)

| 실행 | 직전 CPU 부하 | 포트 바인딩 | 준비(200) | `GET /api/orders/1` 첫 요청 |
|---|---|---|---|---|
| shop-boot3 × 21 (integrationTest) | 34% | 9,815ms | 10,147ms | 152ms |
| shop-boot3 × 21 (integrationTest) | 37% | 11,286ms | 11,645ms | 174ms |
| shop-boot2 × 8 (e2e만) | 27% | 7,115ms | 7,419ms | 145ms |
| shop-boot3 × 17 (e2e만) | 27% | 11,755ms | 12,084ms | 161ms |
| shop-boot3 × 21 (수동) | 7% | - | 약 4초 | 134ms, 두 번째 3.0ms |

첫 요청 시간은 `DispatcherServlet` 지연 초기화를 포함한다. 에이전트 premain(배너의 `started in`)은 406~449ms였다. 에이전트 유무 비교와 기동 증가분 판정은 P-2(`agent-overhead.md`)가 한다.

### 8.5 jdbc·샘플 앱 매트릭스·변환 목록 베이스라인

jdbc 모듈(10 §6.3 중 PoC 범위 "prepareStatement·execute", `agent/agent-instrumentation/jdbc`)

| 항목 | 구현 |
|---|---|
| 계측 지점 | `Connection.prepareStatement(String, ..)`·`prepareCall(String, ..)` exit(`PrepareStatementAdvice`), `PreparedStatement.execute()`·`executeQuery()`·`executeUpdate()`·`executeLargeUpdate()`(`PreparedStatementExecuteAdvice`), `Statement.execute*(String, ..)`(`StatementExecuteAdvice`). 타입 매처는 `declaresMethod(..)` → `not(isInterface())` → JDK 프록시(`com.sun.proxy.`·`jdk.proxy`) 제외 → `SafeMatchers.safeImplements(java.sql.Connection/Statement)` 순 |
| 중첩(D-24) | `CallDepth.JDBC_CONNECTION`(prepare)·`JDBC_STATEMENT`(execute)로 트랜잭션 안의 최외곽 호출만 기록한다. HikariCP 프록시 → H2 드라이버 2단(`HikariProxyConnection` → `ProxyConnection` → `JdbcConnection` 3단)에서 step 1개. 깊이 확인은 helper 호출 전, 증가는 후에 해서 helper가 실패해도 짝이 깨지지 않는다(exit는 `@Advice.Enter` 값으로 판단, C-24) |
| step | `SQL`, name = SQL 사전 id, target = DB 사전 id(`getMetaData().getURL()`을 커넥션 객체당 1회, 그동안 드라이버 내부 JDBC 호출은 중첩 처리, `;`·`?` 이후와 `user:password@` 제거), status 0, error = 예외 여부, exit에서 `recordSqlStat`. 트랜잭션 밖이면 아무것도 하지 않는다 |
| SQL 정규화(SEC-35) | helper는 원문을 `Tracer.dict(DictKind.SQL, raw)`에 넘기고 코어가 `core.sql.SqlNormalizer`로 정규화한 뒤 해시·보관한다(10 §9.4 PoC 부분집합: 문자열·접두 리터럴·달러 쿼팅·숫자(부호 규칙) → `?`, 주석·힌트 제거, 공백 1개, 바인드 마커 보존, `IN (?, ?)` → `IN (?)`, 다중 `VALUES` 첫 행, 실패 시 `<unparsed>`). 원문 → id 캐시(`core.trace.SqlIds`)는 1만 개·문자 200만 상한, 4KB 초과 SQL은 캐시하지 않는다. 사전·JSONL에는 리터럴 없는 문장만 남는다(`SqlNormalizerTest` 31건, PII 시드 포함) |
| 보관 | `ContextStore<Statement, long[]{sqlId, dbId}>`, `ContextStore<Connection, Long>`(약참조 키, 저장소당 10만 상한). 필드 주입은 WO-13 |
| PoC 밖(WO-13) | `DataSource.getConnection`(DBC step), commit·rollback, 배치, 바인드 파라미터 3모드, ResultSet 행·fetch 시간, 벤더별 URL 파서, orphan 카운터. 트랜잭션 밖에서 준비하고 안에서 실행한 문장은 이름 `<unknown prepared statement>` |
| 단위 테스트(Java 8) | `JdbcAdviceTest`(ByteBuddy로 같은 Advice·매처를 생성 JDBC 클래스(풀 → 드라이버)에 인라인, 실제 `TracerImpl`): step 1개·정규화·DB id·반복 실행·예외 동일성·트랜잭션 밖·URL 1회·모듈 비활성·Advice 오류 0. `JdbcModuleTest`(매처, 프록시 제외) |

샘플 앱 매트릭스(T-AG-010·T-AG-070 PoC, `SampleAppAgentTest`)

```
./gradlew :agent:agent-testing:integrationTest --tests '*SampleAppAgentTest*' \
  -Pagent.e2e=shop-boot2@8,shop-boot2@11,shop-boot2@17,shop-boot2@21,shop-boot3@17,shop-boot3@21,shop-boot3@25
```

2026-10-24 18:41~18:43 실행(시작 직전 CPU 부하 1%, 공유 머신). 모든 실행의 공통 JVM 플래그: `-XX:-UsePerfData -XX:+UnlockDiagnosticVMOptions -XX:+BytecodeVerificationLocal`(C-27) `-Djava.io.tmpdir=<run>/tmp -javaagent:argos-agent.jar -Dargos.exporter=file -Dargos.exporter.file.path=.. -Dargos.log.dir=.. -Dargos.debug.transform-list=.. -Dargos.async.step=true`, 환경 `SAMPLE_EXT_URL`(테스트 JVM 안의 ext-stub), `SERVER_ADDRESS=127.0.0.1`, `TMP`·`TEMP`·`TMPDIR=<run>/tmp`. 각 실행의 단언은 위 8.4 항목 전부 + 아래 SQL·HTTP·ASYNC·변환 목록·`IllegalAccessError` 0이다.

| 앱 | JDK(빌드) | 추가 플래그 | 결과 | 포트/준비(ms) | premain(ms) | 트랜잭션 | `/api/orders/1` SQL step | `/api/pay` HTTP step | ASYNC step | 변환 클래스 |
|---|---|---|---|---|---|---|---|---|---|---|
| shop-boot2 | 8 (Temurin 1.8.0_504) | - | 통과 | 7,882 / 8,131 | 268 | 9 | 1 | 1 (49.8ms 첫 호출) | 2 | 18 |
| shop-boot2 | 11 (Temurin 11.0.32.1) | - | 통과 | 9,589 / 9,887 | 344 | 9 | 1 | 1 (13.3ms) | 2 | 18 |
| shop-boot2 | 17 (Temurin 17.0.20.1) | - | 통과 | 8,014 / 8,289 | 363 | 9 | 1 | 1 (10.7ms) | 2 | 18 |
| shop-boot2 | 21 (Oracle 21+35 GA) | - | 통과 | 7,513 / 7,763 | 424 | 9 | 1 | 1 (9.4ms) | 2 | 18 |
| shop-boot3 | 17 (Temurin 17.0.20.1) | - | 통과 | 11,081 / 11,534 | 353 | 9 | 1 | 1 (8.7ms) | 1 | 18 |
| shop-boot3 | 21 (Oracle 21+35 GA) | - | 통과 | 9,667 / 10,022 | 457 | 9 | 1 | 1 (8.2ms) | 1 | 18 |
| shop-boot3 | 25 (OpenJDK 25+36) | `--sun-misc-unsafe-memory-access=deny` | 통과 | 8,882 / 9,154 | 429 | 9 | 1 | 1 (7.5ms) | 1 | 18 |
| shop-boot2 | 8 | `-Dargos.test.faults=advice-throw:servlet` | 통과(T-AG-040: 200 ×3, 트랜잭션 0, `advice error in module 1`) | - | - | 0 | - | - | - | - |
| shop-boot2 | 8 | `-Dargos.test.faults=advice-throw:jdbc,advice-throw:executor,advice-throw:httpclient-jdk` | 통과(T-AG-040: `/api/orders/1`·`/api/pay`·`/api/async/executor` 200, 트랜잭션 3건 step 0, 모듈 3·6·11 오류 집계) | - | - | 3 | 0 | 0 | 0 | - |
| shop-boot2 | 8 | 에이전트 없음(T-BLD-006 기준선) | 통과(에이전트 실행의 temp 항목이 모두 기준선에도 있음) | - | - | - | - | - | - | - |

- 반복 실행(같은 명령, 2026-10-24 19:07~19:09, 시작 직전 CPU 부하 41%, WO-03 동시 실행): 10/10 통과. 포트/준비(ms) shop-boot2 × 8 8,742/9,005, × 11 9,030/9,322, × 17 9,667/9,942, × 21 8,091/8,343, shop-boot3 × 17 10,532/10,826, × 21 9,637/9,975, × 25 9,857/10,125. 기본 `integrationTest`(부착 JDK 8·21 + shop-boot3 × 21)도 21/21 통과(19:06, CPU 40%, 약 1분 40초).
- 통합 재실행(2026-11-02, 다른 작업 없음, 직전 CPU 부하 2%): 같은 명령에 부착 JDK 5종을 더해 51/51 통과했다. 포트 바인딩 5.6~7.7초, premain 211~337ms(12.3 표).
- SQL step: shop-boot2(Hibernate 5) `select orderentit0_.id as id1_1_0_, … from orders orderentit0_ where orderentit0_.id=?`, shop-boot3(Hibernate 6) `select oe1_0.id,oe1_0.amount,… from orders oe1_0 where oe1_0.id=?`, target `jdbc:h2:mem:shop-boot2`/`jdbc:h2:mem:shop-boot3`. 앱이 센 문장 수(`sqlStatements`=1)와 step 수가 같다(T-AG-010 정식판 조건).
- ASYNC step 수 차이: shop-boot2(Spring 5.3)의 `@Async`는 `ThreadPoolTaskExecutor`가 만든 `ThreadPoolExecutor`의 `beforeExecute`를 재정의하지 않아 `CompletableFuture$AsyncSupply` step이 하나 더 생긴다. shop-boot3(Spring 6.2)는 `beforeExecute`/`afterExecute`를 `super` 없이 재정의해 전파되지 않는다(10장 한계, WO-15).
- 같은 날 앞선 단일 실행(shop-boot3 × 21, CPU 부하 47~48%)도 같은 단언으로 통과했다. 기동 수치는 공유 머신 참고값이고 에이전트 유무 비교는 P-2(`agent-overhead.md`)가 한다.
- 원시 자료: `agent/agent-testing/build/reports/agent-e2e/<app>-jdk<N>-{transformed.txt,tx.jsonl,agent.log,stdout.log,temp-events.txt}`. 2026-10-24 실행의 사본(레인 스크래치 `wo02jdk/matrix1/`)은 세션 스크래치와 함께 삭제되었다. 통합 재실행(2026-11-02, 12.3)의 사본은 [`docs/bench/wo02-agent-2026-11-02/agent-e2e/`](../bench/wo02-agent-2026-11-02/README.md)에 있다.

변환 목록 베이스라인(T-AG-005 입력, `-Dargos.debug.transform-list`)

같은 앱이면 JDK 8·11·17·21(shop-boot2), 17·21·25(shop-boot3) 사이에 목록이 완전히 같다(18클래스, 정렬 후 동일). 차이는 servlet 네임스페이스와 Spring Boot 로더 이름뿐이다. shop-boot3(로더 `org.springframework.boot.loader.launch.LaunchedClassLoader`, shop-boot2는 `javax.servlet.http.HttpServlet`과 `org.springframework.boot.loader.LaunchedURLClassLoader`):

```
com.zaxxer.hikari.pool.HikariProxyConnection jdbc <loader>
com.zaxxer.hikari.pool.HikariProxyPreparedStatement jdbc <loader>
com.zaxxer.hikari.pool.HikariProxyStatement jdbc <loader>
com.zaxxer.hikari.pool.ProxyConnection jdbc <loader>
com.zaxxer.hikari.pool.ProxyPreparedStatement jdbc <loader>
com.zaxxer.hikari.pool.ProxyStatement jdbc <loader>
jakarta.servlet.http.HttpServlet servlet <loader>
java.util.concurrent.ThreadPoolExecutor executor bootstrap
org.apache.tomcat.websocket.server.WsFilter servlet <loader>
org.h2.jdbc.JdbcConnection jdbc <loader>
org.h2.jdbc.JdbcPreparedStatement jdbc <loader>
org.h2.jdbc.JdbcStatement jdbc <loader>
org.springframework.web.filter.OncePerRequestFilter servlet <loader>
org.springframework.web.servlet.function.support.HandlerFunctionAdapter spring-webmvc <loader>
org.springframework.web.servlet.mvc.HttpRequestHandlerAdapter spring-webmvc <loader>
org.springframework.web.servlet.mvc.SimpleControllerHandlerAdapter spring-webmvc <loader>
org.springframework.web.servlet.mvc.method.AbstractHandlerMethodAdapter spring-webmvc <loader>
sun.net.www.protocol.http.HttpURLConnection httpclient-jdk bootstrap
```

녹화·diff 도구(T-AG-005 정식)는 WO-05다. 지금은 `TransformList`로 읽어 단언하고 목록 파일을 보고서 디렉터리에 남긴다.

T-BLD-006(임시 파일 0)과 샘플 앱

- `AppLauncher`가 기동 전부터 `TempWatch`(앱 temp 디렉터리 `WatchService` + 종료 전 전체 트리)로 기록한다. 에이전트 실행에서 에이전트 고유 이름(`argos`·`bytebuddy`·`jna`·`netty`) 0을 단언하고, 첫 조합은 같은 요청을 에이전트 없이 한 번 더 돌려 에이전트 실행의 항목(숫자는 `#`로 정규화)이 모두 기준선에도 있는지 본다. Tomcat 작업 디렉터리 외에 추가 항목 0.
- 발견: Windows의 JDK(16+)는 NIO `Selector`의 웨이크업 파이프를 AF_UNIX 소켓으로 만들고 `%TEMP%`에 `socket_<n>` 파일을 잠깐 만들었다 지운다. 경로가 AF_UNIX 상한(108자) 안일 때만 그렇다. shop-boot3은 기동 중(JPA 초기화 뒤, 포트 바인딩 전) 13개를 한꺼번에, 이후 2개를 더 만든다(짧은 temp 경로에서 에이전트 있음·없음 모두 15개 실측). WebClient가 없는 shop-boot2는 0~1개라서 12코어 머신의 Reactor Netty 이벤트 루프 그룹(루프당 선택기 1개)과 Tomcat 선택기로 본다(추정, 스택은 확인하지 않았다). 그래서 기준선과 에이전트 실행의 작업 디렉터리 이름 길이를 같게 맞췄다. **에이전트가 Netty NIO 전송(P-3)을 넣으면 에이전트도 Windows에서 이 파일을 만든다**(T-BLD-006 충돌 가능, 보고서 미해결).

## 9. P-3 전송 스택 비교 (02 §11 P-3, A-1·A-14)

- 이 장은 전송 레인의 `docs/poc/agent-transport.md`를 WO-02 통합 때 합친 것이다(그 파일은 지웠다).
- 측정은 두 번 했다. 2026-10-24 전송 레인 측정은 공유 머신(WO-03 동시 실행, 실행 직전 CPU 부하 중앙값 18%·최대 87%)이었고, 그 원시 파일(`build/poc/bench/20261024-190700/`)은 빌드 출력 정리로 지워졌다. 2026-11-02 통합 측정은 같은 머신에서 다른 작업 없이 다시 했다. **아래 표는 2026-11-02 값이다**(원시 자료 [`docs/bench/wo02-agent-2026-11-02/transport/`](../bench/wo02-agent-2026-11-02/README.md)). 판정과 정성 결과는 두 측정이 같다. 2026-10-24 값은 ADR-0005 전송의 맥락 표에 남아 있다.
- 결정 제안은 [ADR-0005 전송](../adr/0005-agent-transport.md)(Proposed)과 11장. Gate 0 수치는 사람이 전용 장비에서 다시 잰다(9.8 명령).

### 9.1 결론

| 결정 항목 | 제안 | 근거 |
|---|---|---|
| A-1 전송 스택 | **gRPC over grpc-netty**(NIO, JDK TLS, 10 §10.3 고정 설정) 유지 | Gate 0 P-3 기준 5개 모두 충족(9.5). gRPC 전송 중 스레드 예산 6에 들어가는 것은 grpc-netty뿐(9.4.3). 11 문서 RPC 모델 그대로 |
| A-14 `protobuf-javalite` | **채택하지 않음**(protobuf-java) | `map`·`optional`은 지원하지만(9.4.7) JDK 24+에서 `Unsafe` 경고, `deny`에서 동작 불가(9.4.5) |
| C-23 프로퍼티 문자열 | **RELOCATED 확정** + 자체 코드 리터럴 규칙 | Netty·gRPC 원래 형태 0건, 실행 시점 격리 확인, 자체 리터럴 오염 발견과 수정(9.4.8, 11.3) |
| 19 §13 #4 `minimize` 제외 | `io.grpc:*`를 minimize 루트로 | 제공자 클래스 삭제로 채널 생성 실패(9.4.9) |
| 11 §15 #4 Java 8 하한 | 전송 8u252 이상, 미만은 ERROR 1줄 + 전송 비활성 | 8u242·8u252 실측(9.4.4) |
| JUL | 셰이딩 라이브러리의 `java.util.logging.Logger`를 에이전트 로거로 relocate | JUL 로거 0, stderr 0(9.4.10) |
| PM 결정 필요 | (P1) Netty 4.2의 Java 11 클래스와 C-22, (P2) 로드되지만 `Unsafe`를 쓰지 않는 클래스의 허용 조건, (P3) Windows NIO 선택기의 temp 파일과 T-BLD-006 | 9.4.6, 9.4.11, ADR-0005 |

### 9.2 비교 대상

같은 에이전트(`agent-core`, 계측 모듈 5개 `servlet`·`spring-webmvc`·`jdbc`·`executor`·`httpclient-jdk`, ByteBuddy 1.18.15)에 전송만 바꾼 비교용 단일 jar를 `test/agent/transport-poc`(개발 전용 `:test:agent:transport-poc`)가 만든다. 조립은 `:agent:agent-dist`와 같다(19 §3.3 1~3단계: 셰이딩·relocate·minimize, `inst/**.classdata`, 루트 bootstrap, META-INF 고지·`argos-deps.txt`·SBOM, 매니페스트). 각 jar의 `grpc` 익스포터 슬롯(`io.argos.agent.core.transport.GrpcExporterFactory`)에 최소 송신기가 들어 있다. 송신기는 `argos-sender` 스레드에서 1초마다 `TransactionBatch`(레코드 5건, 직렬화 약 330바이트)를 로컬 가짜 수집기로 보내고 `Ack`를 기다린다(deadline 5초, 실패 시 1~30초 백오프).

| id | 전송 | protobuf 런타임 |
|---|---|---|
| `grpc-netty-full` / `-lite` | grpc-java 1.85.1 `NettyChannelBuilder`, Netty 4.2.19, NIO, `SslProvider.JDK` | protobuf-java / javalite 4.36.2 |
| `grpc-netty41-full` | 위와 같음, Netty 4.1.139(C-22 비교) | protobuf-java |
| `grpc-okhttp-full` / `-lite` | grpc-java 1.85.1 `OkHttpChannelBuilder`(okio 3.4.0, kotlin-stdlib 1.8.0), JDK `SSLSocket` | protobuf-java / javalite |
| `http1-full` / `-lite` | 자체 HTTP/1.1 클라이언트: JDK `SSLSocket` 연결 1개 유지, `argos-sender`에서 블로킹, `POST /argos.v1.AgentService/SendTransactions`(`application/x-protobuf`) | protobuf-java / javalite |
| `grpc-netty41-full-x`, `http1-full-x` | 위 jar에서 실행 중 로드되지 않는 `Unsafe` 참조 클래스를 뺀 것(9.4.6) | protobuf-java |
| base | 전송 없음(`:agent:agent-dist` 배포 jar, `exporter=noop`) | - |

공통 설정

- 메시지: 11 §4 proto 3개(common·session·data)와 `AgentService`(11 §3)의 스냅샷(`test/agent/transport-poc/src/proto`, Java 패키지만 `io.argos.agent.transportpoc.proto`). 11 §4.3 data.proto는 `Ack.throttle`이 session.proto의 `Throttle`을 쓰는데 import가 없어 protoc가 실패했다. 스냅샷에 import를 추가했다(11 문서 수정 필요, 14장).
- TLS: 가짜 수집기가 `keytool`로 만든 RSA 2048 자체 서명 인증서(`localhost`, `127.0.0.1`). 에이전트는 `server.tls.ca-file`(PEM)을 신뢰하고 호스트명 검증을 켠다(gRPC 기본, HTTP/1.1은 `HTTPS` 식별 알고리즘).
- grpc-netty(10 §10.3): `NioEventLoopGroup(1, argos-io)`, `NioSocketChannel`, `directExecutor`, `offloadExecutor`=이벤트 루프, `maxInboundMessageSize` 4MiB, keepalive 60초/20초, `keepAliveWithoutCalls`, idle 끔. Netty 프로퍼티는 relocate된 이름(`io.argos.agent.shaded.io.netty.*`)으로 설정: `noUnsafe=true`, `allocator.type=unpooled`, `noPreferDirect=true`, `maxDirectMemory=8388608`, `tryReflectionSetAccessible=false`, `eventLoopThreads=1`, `allocator.numDirectArenas=0`, P-3 추가 `leakDetection.level=disabled`, `recycler.maxCapacityPerThread=0`, `noKeySetOptimization=true`, `varHandle.enabled=false`. Netty 로거는 `JdkLoggerFactory`.
- grpc-okhttp: `transportExecutor`(`argos-io`, 최대 2), `scheduledExecutorService`·`offloadExecutor`(`argos-io-timer` 1), `directExecutor`, 나머지는 netty와 같다.
- 모든 비교 jar: `java.util.logging.Logger` → 에이전트 `JulLogger` relocate(9.4.10), gRPC 아티팩트 minimize 루트(9.4.9), 주석 전용 아티팩트(jsr305, error_prone_annotations, j2objc, animal-sniffer, checker-qual, `com.google.android:annotations`, jetbrains annotations, jspecify)는 셰이딩하지 않음.

### 9.3 측정 방법

- 하네스: `TransportBench`가 가짜 수집기(JDK 21 프로세스, gRPC + HTTPS, 127.0.0.1)를 띄우고, 호스트 앱(`example.transportpoc.HostApp`, 10초 대기 후 JVM 자기 보고)을 에이전트 없음·base·비교 jar로 JDK별 프로세스로 실행한다. 모든 실행은 `-XX:-UsePerfData`와 실행별 temp 디렉터리(`java.io.tmpdir`, `TMP`·`TEMP`)로 돈다.

| 지표 | 정의 |
|---|---|
| jar 크기·클래스 수 | jar 바이트, 루트 `.class` + `inst/**.classdata` 수 |
| 기동 | 호스트 `main()` 진입 시각의 JVM uptime(premain 포함), 에이전트 없는 실행과의 중앙값 차이. 전송은 `argos-sender`에서 비동기로 시작하므로 "첫 배치 수신"(수집기 수신 시각 − JVM 시작 시각)을 따로 적는다 |
| 메타스페이스·클래스 | 10초 뒤 `MemoryPoolMXBean("Metaspace").used`, `ClassLoadingMXBean.loadedClassCount` |
| 스레드 | 10초 뒤 전체 스레드 이름. 에이전트 스레드 = `argos-*` + 에이전트 없는 실행에 없던 스레드 |
| 직접 메모리 | `BufferPoolMXBean("direct").memoryUsed`, 100ms 간격 표본의 최대 |
| `Unsafe` 경고 | JDK 25 기본(warn)과 `--sun-misc-unsafe-memory-access=deny`에서 stderr의 `sun.misc.Unsafe` 줄, deny에서 배치 전달 여부 |
| 로드된 클래스 | JDK 9+ `-Xlog:class+load`, JDK 8 `-verbose:class` |
| temp 파일 | 실행별 temp 디렉터리(`<out>/t/<n>`, AF_UNIX 경로 108자 안)를 `WatchService`로 감시(생성 후 바로 지워진 것 포함) |
| CPU 부하 | 머신 전체 CPU 부하(`OperatingSystemMXBean.getCpuLoad`, Win32_Processor LoadPercentage와 같은 양) 1초 표본. 실행 직전 값과 실행 중 평균 |

- 반복: 자원 측정은 JDK 21·8 각 5회, 에이전트를 반복마다 번갈아 실행. 기능(11·17·8u242·8u252)·JDK 25·클래스 로드·C-23·접속 불가 시나리오는 1회.
- 환경(2026-11-02): Windows 11 Home 10.0.26200, Ryzen 5 5600H 6C/12T(논리 CPU 12), RAM 15.3GiB, AC 전원. 다른 작업 없음(WO-03·WO-04 종료, 측정 중 Gradle 데몬과 하네스만 실행). 실행 직전 머신 CPU 부하 최소 0 / 중앙값 3 / 최대 11%, 실행 중 평균 중앙값 5 / 최대 9%(212회).
- JDK: 8 = Temurin 1.8.0_504, 8u242 = AdoptOpenJDK 1.8.0_242-b08 JRE, 8u252 = AdoptOpenJDK 1.8.0_252-b09 JRE(GitHub 릴리스 `OpenJDK8U-jre_x64_windows_hotspot_8u242b08.zip`·`…8u252b09.zip`, SHA-256 `b00c361e…46898`·`582f5829…a804`가 릴리스의 `.sha256.txt`와 일치), 11 = Temurin 11.0.32.1, 17 = Temurin 17.0.20.1, 21 = Oracle OpenJDK 21+35(GA), 25 = OpenJDK 25+36.
- 커밋: 작업 트리 기준(`4a43e77` + 미커밋 WO-00~WO-04 + WO-02 통합 변경).
- 원시 파일: `test/agent/transport-poc/build/poc/bench/20261102-203220/`(summary.md, runs.tsv, `raw/<run>/`). 저장소 사본: `docs/bench/wo02-agent-2026-11-02/transport/`(summary.md, runs.tsv, smoke.md 등. 실행별 raw 디렉터리는 크기 때문에 넣지 않았다).
- 측정 중 발견(환경): 압축을 푼 8u242·8u252 JRE를 `%TEMP%` 아래에 두면 파일 시각이 2020년이라 Windows 임시 파일 정리가 `bin/` 파일 대부분(55개 중 48개, `instrument.dll` 포함)을 지운다. 첫 통합 측정의 8u242·8u252 행이 `Could not find agent library instrument`로 실패한 원인이고, 전송 레인 보고의 "원인 미상으로 사라진 압축 해제 디렉터리"도 같은 현상으로 본다. `unzip -DD`(현재 시각)로 다시 풀어 처음부터 다시 쟀다.

### 9.4 결과

#### 9.4.1 jar 크기와 클래스 수

| jar | 바이트 | base 대비 | 클래스(자체/셰이딩) | `Unsafe` 참조 클래스 | JUL 참조 클래스 | check-shading |
|---|---|---|---|---|---|---|
| base | 4,074,167 | - | 2,648 (141/2,507) | 0 | 0 | **통과** |
| `grpc-netty-full` | 12,184,174 | +8,110,007 | 7,315 (369/6,946) | 35 | 0 | 실패: major-version·unsafe |
| `grpc-netty-lite` | 10,974,272 | +6,900,105 | 6,953 (385/6,568) | 35 | 0 | 실패: major-version·unsafe |
| `grpc-netty41-full` | 12,024,291 | +7,950,124 | 7,264 (369/6,895) | 38 | 0 | 실패: unsafe |
| `grpc-netty41-full-x` | 11,953,440 | +7,879,273 | 7,242 (369/6,873) | 16 | 0 | **통과**(P-3 허용 목록 `unsafe/grpc-netty41-full-x.allowlist.txt`) |
| `grpc-okhttp-full` | 11,385,986 | +7,311,819 | 6,438 (368/6,070) | 11 | 0 | 실패: unsafe |
| `grpc-okhttp-lite` | 10,175,805 | +6,101,638 | 6,076 (384/5,692) | 11 | 0 | 실패: unsafe |
| `http1-full` | 6,051,105 | +1,976,938 | 3,412 (365/3,047) | 7 | 0 | 실패: unsafe |
| `http1-lite` | 4,895,464 | +821,297 | 3,081 (381/2,700) | 7 | 0 | 실패: unsafe |
| `http1-full-x` | 5,999,469 | +1,925,302 | 3,405 (365/3,040) | 0 | 0 | **통과**(허용 목록 0) |

- 모든 jar가 15MB 이하다. grpc-netty 계열은 check-shading의 12MB 경고를 넘는다.
- check-shading 실패 항목은 `unsafe`(허용 목록 밖 `sun.misc.Unsafe` 참조)와 `major-version`(C-22, Netty 4.2만)이다. 비교 jar의 판정은 보고서로만 남고(`pocCheckShading*`), 배포 jar의 check-shading은 `verifyAgentJar`가 강제한다.
- 라이브러리별 압축 크기(KB, grpc-netty-full 기준): ByteBuddy 3,045, Netty 2,171(4.1은 2,035), Guava 1,412, protobuf-java 1,234(javalite 372), gRPC 1,037 + grpc-netty 277, 생성 코드 529(lite 319), gson 62, perfmark 5. grpc-okhttp는 Netty 대신 grpc-okhttp 233 + okio 297 + kotlin-stdlib 약 1,300. 자체 송신기는 약 16(summary.md "Compressed size by library").

#### 9.4.2 기동·메타스페이스·클래스·직접 메모리 (중앙값, 5회)

JDK 21

| 에이전트 | `main` 진입(무에이전트 대비) | 첫 배치 수신 | 메타스페이스 MiB (무에이전트 / base 대비) | 로드 클래스 (base 대비) | 직접 메모리 최대 | 전송 스레드 | temp 생성(5회 합) |
|---|---|---|---|---|---|---|---|
| none | 56 ms(절대값) | - | 0.8 | 867 | 0 | - | 0 |
| base | +358 ms | - | 7.2 (+6.4 / +0.0) | 2,470 | 0 | - | 0 |
| grpc-netty-full | +362 ms | 1,143 ms | 24.3 (+23.5 / +17.1) | 5,701 (+3,231) | 10,615 B | 1 | 5 (socket_#) |
| grpc-netty-lite | +363 ms | 1,155 ms | 24.4 (+23.6 / +17.2) | 5,704 (+3,234) | 10,615 B | 1 | 5 (socket_#) |
| grpc-netty41-full | +361 ms | 1,139 ms | 24.3 (+23.5 / +17.2) | 5,714 (+3,244) | 10,615 B | 1 | 5 (socket_#) |
| grpc-netty41-full-x | +362 ms | 1,144 ms | 24.3 (+23.5 / +17.2) | 5,717 (+3,247) | 10,615 B | 1 | 5 (socket_#) |
| grpc-okhttp-full | +360 ms | 907 ms | 20.1 (+19.2 / +12.9) | 4,943 (+2,473) | 9,497 B | 3 | 0 |
| grpc-okhttp-lite | +360 ms | 920 ms | 20.1 (+19.3 / +13.0) | 4,946 (+2,476) | 9,497 B | 3 | 0 |
| http1-full | +355 ms | 761 ms | 14.6 (+13.8 / +7.4) | 3,858 (+1,388) | 8,192 B | 0 | 0 |
| http1-lite | +353 ms | 766 ms | 14.7 (+13.8 / +7.5) | 3,866 (+1,396) | 8,192 B | 0 | 0 |
| http1-full-x | +354 ms | 777 ms | 14.6 (+13.8 / +7.4) | 3,858 (+1,388) | 8,192 B | 0 | 0 |

JDK 8(Temurin 8u504)

| 에이전트 | `main` 진입(무에이전트 대비) | 첫 배치 수신 | 메타스페이스 MiB (무에이전트 / base 대비) | 로드 클래스 (base 대비) | 직접 메모리 최대 | 전송 스레드 | temp 생성(5회 합) |
|---|---|---|---|---|---|---|---|
| none | 54 ms(절대값) | - | 3.1 | 511 | 0 | - | 0 |
| base | +250 ms | - | 7.6 (+4.5 / +0.0) | 1,461 | 0 | - | 0 |
| grpc-netty-full | +252 ms | 1,402 ms | 22.8 (+19.7 / +15.2) | 4,353 (+2,892) | 946 B | 1 | 0 |
| grpc-netty-lite | +251 ms | 1,406 ms | 22.9 (+19.8 / +15.3) | 4,354 (+2,893) | 2,745 B | 1 | 0 |
| grpc-netty41-full | +248 ms | 1,383 ms | 22.8 (+19.6 / +15.2) | 4,329 (+2,868) | 2,439 B | 1 | 0 |
| grpc-netty41-full-x | +248 ms | 1,389 ms | 22.7 (+19.6 / +15.1) | 4,329 (+2,868) | 2,439 B | 1 | 0 |
| grpc-okhttp-full | +251 ms | 1,052 ms | 20.6 (+17.5 / +13.0) | 3,984 (+2,523) | 0 | 3 | 0 |
| grpc-okhttp-lite | +252 ms | 1,057 ms | 20.7 (+17.6 / +13.1) | 3,985 (+2,524) | 0 | 3 | 0 |
| http1-full | +249 ms | 854 ms | 14.7 (+11.6 / +7.1) | 2,736 (+1,275) | 0 | 0 | 0 |
| http1-lite | +249 ms | 852 ms | 14.7 (+11.6 / +7.1) | 2,742 (+1,281) | 0 | 0 | 0 |
| http1-full-x | +249 ms | 854 ms | 14.7 (+11.6 / +7.1) | 2,736 (+1,275) | 0 | 0 | 0 |

- 기동: 전송은 `argos-sender`에서 시작하므로 premain을 늘리지 않는다(`main` 진입 지연이 base와 같은 수준). 전송 시작 자체(클래스 로드·TLS 컨텍스트·채널 생성, 에이전트 로그 `transport-poc started`, JDK 21 / 8 각 5회 범위)는 grpc-netty 406~460 / 641~665 ms, grpc-okhttp 359~383 / 641~657 ms, HTTP/1.1 160~174 / 410~423 ms였다. 앱 스레드는 기다리지 않는다.
- 2026-10-24 공유 머신 측정과 비교하면 base `main` 진입 지연이 JDK 21 +494 → +358 ms, JDK 8 +383 → +250 ms로 줄었고 첫 배치 수신도 빨라졌다(부하 차이). 메타스페이스·클래스·스레드·직접 메모리는 같다.
- 직접 메모리: 전송 종류와 무관하게 수 KB다. JDK 13+의 소켓 구현과 NIO가 힙 버퍼를 쓸 때 쓰는 임시 직접 버퍼다. Netty는 `noPreferDirect`·`unpooled`로 직접 버퍼를 만들지 않았다. 큰 배치(11 §2 목표 1MiB)에서는 JDK 스레드별 임시 직접 버퍼가 배치 크기만큼 커질 수 있으므로 WO-11에서 1MiB 배치로 다시 잰다.

#### 9.4.3 스레드

| 에이전트 | 에이전트 스레드(측정) | 전송이 늘린 스레드 | 10 §14 전체 예산(6)에 넣으면 |
|---|---|---|---|
| base | `argos-logger`, `argos-sender`, `argos-watch` | - | metrics·command 추가 시 5 |
| grpc-netty(4.2·4.1) | + `argos-io` | 1 | **6** |
| grpc-okhttp | + `argos-io`, `OkHttpClientTransport`(`argos-io-2`를 gRPC가 개명), `argos-io-timer` | 3 | 8 |
| http1 | 변화 없음 | 0 | 5(세션용 롱 폴링 스레드를 두면 6) |

- grpc-netty는 gRPC 공유 executor(`grpc-default-executor`)·timer(`grpc-timer`) 스레드를 만들지 않았다(`directExecutor` + `offloadExecutor` = 이벤트 루프).
- grpc-okhttp는 연결의 프레임 리더가 블로킹 스레드를 계속 쓰고, 접속 작업에 스레드가 하나 더 필요하다. transport executor를 2개로 묶자 8u242(ALPN 실패)와 수집기 접속 불가 시나리오에서 `RejectedExecutionException` → `INTERNAL: Panic! This is a bug!`로 채널이 영구 고장 났다(9.4.12). gRPC 기본값은 상한 없는 캐시 풀이다. 에이전트 규칙(상한 없는 자원 금지)과 스레드 예산을 함께 지킬 수 없다.

#### 9.4.4 JDK별 동작과 TLS (11 §15 #4, 10 U-4)

| 에이전트 | 8u242 | 8u252 | 8 | 11 | 17 | 21 | 25 | 25 deny |
|---|---|---|---|---|---|---|---|---|
| `grpc-netty-full` | 전송 비활성(ERROR 1줄) | TLS 1.2 (8) | TLS 1.3 (9) | TLS 1.3 (8) | TLS 1.3 (8) | TLS 1.3 (10) | TLS 1.3 (8) | TLS 1.3 (8) |
| `grpc-netty-lite` | 전송 비활성(ERROR 1줄) | TLS 1.2 (7) | TLS 1.3 (9) | TLS 1.3 (8) | TLS 1.3 (8) | TLS 1.3 (10) | TLS 1.3 (8) | 실패(javalite `Unsafe`) |
| `grpc-netty41-full` | 전송 비활성(ERROR 1줄) | TLS 1.2 (8) | TLS 1.3 (9) | TLS 1.3 (8) | TLS 1.3 (8) | TLS 1.3 (10) | TLS 1.3 (8) | TLS 1.3 (8) |
| `grpc-netty41-full-x` | 전송 비활성(ERROR 1줄) | TLS 1.2 (8) | TLS 1.3 (9) | TLS 1.3 (8) | TLS 1.3 (8) | TLS 1.3 (10) | TLS 1.3 (8) | TLS 1.3 (8) |
| `grpc-okhttp-full` | 접속마다 실패(채널 Panic) | TLS 1.2 (8) | TLS 1.2 (9) | TLS 1.2 (8) | TLS 1.2 (8) | TLS 1.2 (10) | TLS 1.2 (8) | TLS 1.2 (8) |
| `grpc-okhttp-lite` | 접속마다 실패(채널 Panic) | TLS 1.2 (8) | TLS 1.2 (9) | TLS 1.2 (8) | TLS 1.2 (8) | TLS 1.2 (10) | TLS 1.2 (8) | 실패(javalite `Unsafe`) |
| `http1-full` | TLS 1.2 (8) | TLS 1.2 (8) | TLS 1.3 (9) | TLS 1.3 (8) | TLS 1.3 (8) | TLS 1.3 (10) | TLS 1.3 (8) | TLS 1.3 (8) |
| `http1-lite` | TLS 1.2 (8) | TLS 1.2 (8) | TLS 1.3 (9) | TLS 1.3 (8) | TLS 1.3 (8) | TLS 1.3 (10) | TLS 1.3 (8) | 실패(javalite `Unsafe`) |
| `http1-full-x` | TLS 1.2 (8) | TLS 1.2 (8) | TLS 1.3 (9) | TLS 1.3 (8) | TLS 1.3 (8) | TLS 1.3 (10) | TLS 1.3 (8) | TLS 1.3 (8) |

- 칸: 배치를 실제로 전달했을 때 수집기가 기록한 협상 TLS와 (배치 수). 기능 실행은 8초, JDK 21 자원 실행은 10초다.
- 8u242(ALPN 없음): grpc-netty는 `GrpcSslContexts.configure(.., SslProvider.JDK)`에서 `IllegalArgumentException: Could not find Jetty NPN/ALPN or Conscrypt as installed JDK providers`로 시작하지 못했다. 에이전트는 ERROR 1줄(`transport-poc disabled: grpc-netty could not start …; transactions are recorded and dropped`)을 남기고 전송만 껐다. 앱 종료 코드 0, stdout·stderr 0. 10 §10.3의 "미달이면 ERROR 1줄 + 전송 비활성"과 같다. grpc-okhttp는 시작 시 검사가 없어 접속 때마다 실패했고, transport executor 상한 때문에 채널이 `INTERNAL: Panic! This is a bug!`로 고장 났다(WO-11에서 버전 검사를 앞에 둬야 함). HTTP/1.1은 ALPN이 필요 없어 동작한다.
- 8u252부터 ALPN이 내장되어 gRPC 두 전송 모두 TLS 1.2로 동작했다(U-4 가정 확인). 8u504·11 이상은 TLS 1.3으로 협상했다. 8u261 TLS 1.3 가정(11 §15 #4)은 이 환경에 해당 빌드가 없어 확인하지 않았다.
- grpc-okhttp는 모든 JDK에서 TLS 1.2로 협상했다(grpc-okhttp 기본 ConnectionSpec). `tlsConnectionSpec(..)`로 바꿀 수 있으나 재지 않았다.

#### 9.4.5 JDK 25 `sun.misc.Unsafe` 경고와 `deny` (F-A35)

| 에이전트 | 기본(warn) `Unsafe` 경고 줄 | 기본 배치 | deny 배치 | deny 에이전트 WARN | 그 밖 stderr |
|---|---|---|---|---|---|
| base | 0 | 0 | 0 | 0 | 0 |
| grpc-netty-full | 0 | 8 | 8 | 0 | 0 |
| grpc-netty-lite | 3 | 8 | 0 | 5 | 1 |
| grpc-netty41-full | 0 | 8 | 8 | 0 | 0 |
| grpc-netty41-full-x | 0 | 8 | 8 | 0 | 0 |
| grpc-okhttp-full | 0 | 8 | 8 | 0 | 0 |
| grpc-okhttp-lite | 3 | 8 | 0 | 5 | 1 |
| http1-full | 0 | 8 | 8 | 0 | 0 |
| http1-lite | 3 | 8 | 0 | 5 | 1 |
| http1-full-x | 0 | 8 | 8 | 0 | 0 |

#### 9.4.6 `Unsafe` 참조 클래스: jar에 있는 것과 실제 로드되는 것 (T-BLD-004, ADR-0003)

| jar | jar 안 `Unsafe` 참조 클래스 | 실제 로드 JDK 21 / 25 / 8 | 로드되는 것 |
|---|---|---|---|
| `grpc-netty-full` | 35 | 2 / 2 / 2 | Netty `PlatformDependent0`, Guava `AbstractFutureState$UnsafeAtomicHelper` |
| `grpc-netty-lite` | 35 | 9 / 9 / 9 | 위 2개 + protobuf `UnsafeUtil`·`MessageSchema` 등 7개 |
| `grpc-netty41-full` | 38 | 16 / 16 / 15 | Netty `PlatformDependent0`·`CleanerJava9`(JDK 9+), JCTools 필드 오프셋 클래스 13개(`MpscArrayQueueProducerIndexField` 등), Guava 1개 |
| `grpc-netty41-full-x` | 16 | 16 / 16 / 15 | `grpc-netty41-full`과 같다(제외한 22개는 로드되지 않음) |
| `grpc-okhttp-full` | 11 | 1 / 1 / 1 | Guava `AbstractFutureState$UnsafeAtomicHelper` |
| `grpc-okhttp-lite` | 11 | 8 / 8 / 8 | Guava 1개 + protobuf 7개 |
| `http1-full` | 7 | 0 / 0 / 0 | - |
| `http1-lite` | 7 | 7 / 7 / 7 | protobuf `UnsafeUtil`·`MessageSchema` 등 7개(전부) |
| `http1-full-x` | 0 | 0 / 0 / 0 | - |

- 기준: check-shading의 `sun/misc/Unsafe` 참조 클래스(jar)와, `-Xlog:class+load`(JDK 21·25)·`-verbose:class`(JDK 8)로 기록한 실제 로드 클래스의 교집합. 2026-10-24 측정은 grpc-netty-full 37/4, grpc-netty41-full 39/17이었다(Netty 클래스 몇 개가 minimize·소스 정리 뒤 참조 목록에서 빠짐). 판단은 같다.
- protobuf-java(full)의 `UnsafeUtil`·`MessageSchema`는 이 경로(직렬화·`Ack` 파싱)에서 로드되지 않았다. javalite는 모두 로드했다.
- 로드되지 않는 클래스를 뺀 `grpc-netty41-full-x`(22개 제외)와 `http1-full-x`(7개 제외)는 JDK 8u252~25·25 deny에서 동작했다(9.4.4, 에이전트 로그 `NoClassDefFoundError`·`ClassNotFoundException` 0). check-shading은 `http1-full-x`가 허용 목록 0으로, `grpc-netty41-full-x`가 P-3 허용 목록으로 통과했다(제외·허용 목록: `test/agent/transport-poc/unsafe/`).
- 로드되는 클래스는 뺄 수 없다(Netty 핵심 클래스, Guava는 다른 헬퍼 선택 과정에서 로드). 이 클래스들은 `Unsafe`를 쓰지 않는다(JDK 25 warn 경고 0, deny 동작). ADR-0003의 허용 조건("로드되지 않음")을 만족하지 않으므로 PM 결정이 필요하다(ADR-0005 (P2)).

#### 9.4.7 javalite vs protobuf-java (A-14, 11 §15 #2)

| 항목 | javalite 4.36.2 | protobuf-java 4.36.2 |
|---|---|---|
| `map<string,string>`(`ServerHello.config`, `Command.args`) 왕복 | 통과 | 통과 |
| proto3 `optional` 존재 판별(`TransactionRecord.cpu_time_us`·`alloc_bytes`, `InstanceUpdate.*`, `Throttle.*`; 미설정과 0 구분) | 통과 | 통과 |
| jar 크기(같은 전송, full 대비) | grpc-netty -1,209,902 B, grpc-okhttp -1,210,181 B, http1 -1,155,641 B | - |
| 런타임 + 생성 코드(압축) | 372 + 319 KB | 1,234 + 529 KB |
| 메타스페이스 | full과 차이 없음(±0.1MiB) | - |
| JDK 24+ | 경고(warn), 실패(deny) | 경고 0, 동작 |

- 테스트: `ProtoFlavourTest`(같은 소스를 `protoLiteTest`·`protoFullTest` 두 스위트로 Java 8에서 실행, 각 3/3 통과, 12.1).

#### 9.4.8 C-23 프로퍼티 문자열 (19 §13 #6, 10 U-6)

- 빌드: `checkRelocatedStrings`(정책 RELOCATED, ByteBuddy 7 + Netty 17 + gRPC 3 이름)가 비교 jar 9개 모두 통과했다(원래 형태 0건, `transportPocJars`). 예: `io.netty.noUnsafe`는 `PlatformDependent0`과 자체 `NettyProps`에 `io.argos.agent.shaded.io.netty.noUnsafe`로만 있다. Shadow는 프로퍼티 문자열을 relocate한다(U-6 가정 확인).
- 실행: grpc-netty 4종 모두 에이전트 로그가 `hasUnsafe=false maxDirectMemory=8388608 directBufferPreferred=false allocator=UnpooledByteBufAllocator`였다. 앱이 `-Dio.netty.noUnsafe=false -Dio.netty.allocator.type=pooled -Dio.netty.maxDirectMemory=0`을 줘도 같았다. 앱에서 본 `io.netty.noUnsafe`는 평소 `null`(에이전트가 원래 이름을 설정하지 않음), relocate된 이름은 `true`였다.
- 부작용(발견): Shadow는 에이전트 **자체 클래스**의 문자열 상수도 relocate한다.
  - `agent-core` `IgnoreMatchers`의 `"net.bytebuddy."`가 2026-10-24의 모든 jar(배포 jar 포함)에서 `"io.argos.agent.shaded.net.bytebuddy."`로 바뀌어 있었다. 앱의 ByteBuddy 클래스(Hibernate·Mockito 등이 쓰는 원래 패키지)가 ignore 목록(4.2)에서 빠진다. 통합에서 고쳤고(11.3), 2026-11-02 비교 jar의 자체 리터럴 보고서에는 이 항목이 없다.
  - okhttp 비교 jar처럼 `kotlin`을 relocate하면 `"kotlin.reflect.jvm.internal."`도 바뀐다(2026-11-02 `grpc-okhttp-*`에서도 남음. 배포 jar는 kotlin을 넣지 않는다).
  - 의도한 상수(`NettyProps`의 relocate된 이름, 루트 `AgentClassLoader`의 `io.argos.agent.shaded.` 접두사)와 구분하는 검사가 `checkOwnRelocatedLiterals`다(11.3). P-3의 `PocOwnRelocatedLiterals`는 같은 상수를 보고만 한다(`build/poc/<id>/reports/own-relocated-literals.txt`).

#### 9.4.9 `minimize`와 제공자 클래스 (19 §13 #4)

- gRPC를 minimize하자 `ServiceConfigurationError: io.argos.agent.shaded.io.grpc.NameResolverProvider: Provider io.argos.agent.shaded.io.grpc.netty.UdsNameResolverProvider not found`로 채널을 만들지 못했다(서비스 파일은 남고 클래스는 지워짐). `io.grpc:*` 아티팩트를 minimize 루트(`apiJars`)로 두자 해결됐다. Netty·Guava·protobuf·okio·kotlin은 minimize해도 모든 시나리오에서 동작했다.

#### 9.4.10 `java.util.logging`

- 셰이딩 전 gRPC·Netty 클래스 103개가 `java.util.logging`을 썼다(`Logger.getLogger`, `log(Level,String,Throwable)` 등 11개 메서드). protobuf도 `UnsafeUtil`에서 쓴다(javalite deny 탐침에서 JUL 경고가 stderr로 나감).
- 비교 jar는 `java.util.logging.Logger`를 에이전트 `JulLogger`로 relocate했다. 결과: jar의 JUL 참조 클래스 0(9.4.1 표), 모든 실행에서 에이전트가 만든 JUL 로거 0(`LogManager.getLoggerNames()`의 `io.argos.*`), okhttp Panic 같은 gRPC 내부 오류도 에이전트 로그로만 갔다(stderr 0).

#### 9.4.11 temp 파일 (T-BLD-006)

실행 중 생성된 temp 항목 수 / 실행 수(모든 시나리오 합). 실행 뒤 남은 항목은 모든 실행에서 0이다.

| 에이전트 | 8u242 | 8u252 | 8 | 11 | 17 | 21 | 25 | 25 deny |
|---|---|---|---|---|---|---|---|---|
| base | 0/1 | 0/1 | 0/5 | 0/1 | 0/1 | 0/6 | 0/1 | 0/1 |
| grpc-netty-full | 0/1 | 0/1 | 0/6 | 0/1 | 1/1 | 8/8 | 2/2 | 1/1 |
| grpc-netty-lite | 0/1 | 0/1 | 0/6 | 0/1 | 1/1 | 8/8 | 2/2 | 1/1 |
| grpc-netty41-full | 0/1 | 0/1 | 0/6 | 0/1 | 1/1 | 8/8 | 2/2 | 1/1 |
| grpc-netty41-full-x | 0/1 | 0/1 | 0/6 | 0/1 | 1/1 | 8/8 | 2/2 | 1/1 |
| grpc-okhttp-full | 0/1 | 0/1 | 0/6 | 0/1 | 0/1 | 0/7 | 0/2 | 0/1 |
| grpc-okhttp-lite | 0/1 | 0/1 | 0/6 | 0/1 | 0/1 | 0/7 | 0/2 | 0/1 |
| http1-full | 0/1 | 0/1 | 0/6 | 0/1 | 0/1 | 0/7 | 0/2 | 0/1 |
| http1-lite | 0/1 | 0/1 | 0/6 | 0/1 | 0/1 | 0/7 | 0/2 | 0/1 |
| http1-full-x | 0/1 | 0/1 | 0/6 | 0/1 | 0/1 | 0/7 | 0/2 | 0/1 |

- JDK 16 이상 Windows의 NIO `Selector`는 웨이크업 파이프를 AF_UNIX 소켓으로 만들며 temp 디렉터리(`jdk.net.unixdomain.tmpdir` 또는 `java.io.tmpdir`)에 소켓 파일을 만들었다가 지운다(P-1 레인이 앱 쪽에서 먼저 관찰, 8.5). grpc-netty의 이벤트 루프가 선택기 1개를 만들므로 에이전트 실행마다 1개가 생긴다. 남는 파일은 0이다. Linux는 재지 않았다.
- 스모크는 이 파일을 알려진 발견(ADR-0005 P3)으로 단언한다(9.6).

#### 9.4.12 접속 불가·`-x` jar

수집기 접속 불가(`server.address=localhost:1`, JDK 21):

| 에이전트 | 종료 코드 | stdout | 그 밖 stderr | 에이전트 WARN | 에이전트 ERROR | 첫 WARN |
|---|---|---|---|---|---|---|
| `grpc-netty-lite` | 0 | 0 | 0 | 3 | 0 | `transport-poc failed: grpc-netty batch seq=1 (1 failures): io.argos.agent.shaded.io.grpc.StatusRuntimeException: UNAVAILABLE: io exception` |
| `grpc-netty-full` | 0 | 0 | 0 | 3 | 0 | `transport-poc failed: grpc-netty batch seq=1 (1 failures): io.argos.agent.shaded.io.grpc.StatusRuntimeException: UNAVAILABLE: io exception` |
| `grpc-netty41-full` | 0 | 0 | 0 | 3 | 0 | `transport-poc failed: grpc-netty batch seq=1 (1 failures): io.argos.agent.shaded.io.grpc.StatusRuntimeException: UNAVAILABLE: io exception` |
| `grpc-okhttp-lite` | 0 | 0 | 0 | 3 | 2 | `transport-poc failed: grpc-okhttp batch seq=1 (1 failures): io.argos.agent.shaded.io.grpc.StatusRuntimeException: INTERNAL: Panic! This is a bug!` |
| `grpc-okhttp-full` | 0 | 0 | 0 | 3 | 2 | `transport-poc failed: grpc-okhttp batch seq=1 (1 failures): io.argos.agent.shaded.io.grpc.StatusRuntimeException: INTERNAL: Panic! This is a bug!` |
| `http1-lite` | 0 | 0 | 0 | 3 | 0 | `transport-poc failed: http1 batch seq=1 (1 failures): java.net.ConnectException: Connection refused: no further information` |
| `http1-full` | 0 | 0 | 0 | 3 | 0 | `transport-poc failed: http1 batch seq=1 (1 failures): java.net.ConnectException: Connection refused: no further information` |
| `grpc-netty41-full-x` | 0 | 0 | 0 | 3 | 0 | `transport-poc failed: grpc-netty batch seq=1 (1 failures): io.argos.agent.shaded.io.grpc.StatusRuntimeException: UNAVAILABLE: io exception` |
| `http1-full-x` | 0 | 0 | 0 | 3 | 0 | `transport-poc failed: http1 batch seq=1 (1 failures): java.net.ConnectException: Connection refused: no further information` |

### 9.5 Gate 0 판정 (02 §11 P-3 채택 기준)

| 에이전트 | jar ≤ 15MB | 기동 +≤ 2초 (JDK 21 / 8) | 스레드 ≤ 6 | 직접 메모리 ≤ 8MB | `Unsafe` 경고 0 (JDK 25) | JDK 25 deny 동작 | 판정 |
|---|---|---|---|---|---|---|---|
| `grpc-netty-lite` | 10.97 MB | +363 ms / +251 ms | 4 | 10,615 B | 3 | 실패 | 미충족(`Unsafe` 경고, deny) |
| `grpc-netty-full` | 12.18 MB | +362 ms / +252 ms | 4 | 10,615 B | 0 | 동작 | **충족** |
| `grpc-netty41-full` | 12.02 MB | +361 ms / +248 ms | 4 | 10,615 B | 0 | 동작 | **충족** |
| `grpc-okhttp-lite` | 10.18 MB | +360 ms / +252 ms | 6 | 9,497 B | 3 | 실패 | 미충족(`Unsafe` 경고, deny) |
| `grpc-okhttp-full` | 11.39 MB | +360 ms / +251 ms | 6 | 9,497 B | 0 | 동작 | **충족** |
| `http1-lite` | 4.90 MB | +353 ms / +249 ms | 3 | 8,192 B | 3 | 실패 | 미충족(`Unsafe` 경고, deny) |
| `http1-full` | 6.05 MB | +355 ms / +249 ms | 3 | 8,192 B | 0 | 동작 | **충족** |
| `grpc-netty41-full-x` | 11.95 MB | +362 ms / +248 ms | 4 | 10,615 B | 0 | 동작 | **충족** |
| `http1-full-x` | 6.00 MB | +354 ms / +249 ms | 3 | 8,192 B | 0 | 동작 | **충족** |

- 스레드는 측정 시점 에이전트 전체(base 3개 포함)다. 기동은 `main` 진입 지연(premain 포함)이며 N-30(premain ≤ 500ms)과 별도로 P-2가 샘플 앱 기준(C-49)으로 잰다.
- 기준 밖 조건: C-22(Netty 4.2 위반), `Unsafe` 참조 클래스(전 jar), Windows temp 파일(grpc-netty)은 9.4.6·9.4.11과 ADR-0005 (P1)~(P3).

### 9.6 스모크와 알려진 발견 (`transportPocJars`, `transportPocTest`)

- 스모크(`transportPocSmoke`, `transportPocTest`에 포함): JDK 8·21, C-27 플래그(`-XX:+UnlockDiagnosticVMOptions -XX:+BytecodeVerificationLocal`). 비교 jar는 60초 안에 배치 3개 수신, stdout 0, stderr 0(CDS 안내 줄만 허용), temp 생성 0, 에이전트 ERROR 0, JUL 로거 0, 종료 코드 0을 단언한다(base는 7초 실행, 배치 단언 제외).
- 알려진 발견(통합에서 추가): PM 결정을 기다리는 두 발견은 실패로 세지 않고 **그 발견 자체를 단언**한다. 그 밖의 결과는 전과 같이 실패한다. `-Ptransport.poc.strict=true`면 둘 다 다시 실패로 센다.

| 발견 | 태스크 | 단언 |
|---|---|---|
| P1: Netty 4.2가 Java 11 클래스 파일을 넣는다(ADR-0003 C-22) | `pocCheckMajorVersion`(`grpc-netty-full`, `grpc-netty-lite`) | jar가 C-22를 어기고, 어기는 클래스가 모두 `inst/io/argos/agent/shaded/io/netty/` 아래다(51개, `build/poc/<id>/reports/major-version.txt`). 위반이 없어도 실패한다(발견이 사라짐: ADR 갱신 필요) |
| P3: Windows JDK 16+ NIO 선택기의 AF_UNIX `socket_<n>` 파일 | `transportPocSmoke`, grpc-netty 4종 | 이름이 `socket_<n>`인 파일만, 실행 뒤 남은 파일 0, Windows, JDK 16+. `smoke.md`에 `KNOWN FINDING` 행으로 남는다 |

- 이유: 비교 하네스의 목적은 후보별 판정을 내는 것이다. 후보가 기준을 어기는 것은 결과이지 하네스 고장이 아니다. 그래서 문서화된 위반은 그대로 기대값으로 단언하고(사라져도 실패), 새 위반·다른 후보의 위반·남는 파일·다른 이름은 실패로 둔다. 배포 jar의 기준(`verifyAgentJar`, `AgentAttachTest`·`SampleAppAgentTest`의 T-BLD-006)은 바꾸지 않았다.

2026-11-02 결과(12.1에 명령과 시간)

| 실행 | JDK 8 | JDK 21 |
|---|---|---|
| 기본(알려진 발견 단언) | base와 jar 9개 모두 PASS | base, grpc-okhttp 2종, http1 3종 PASS. grpc-netty 4종(`-lite`, `-full`, `41-full`, `41-full-x`) `KNOWN FINDING`(실행 중 `socket_#` 1개, 남은 파일 0). 배치·stdout·stderr·ERROR·JUL 단언은 모두 통과 |
| `-Ptransport.poc.strict=true` | 같음 | grpc-netty 4종 FAIL(T-BLD-006), 나머지 PASS. `pocCheckMajorVersionGrpcNettyFull`·`…Lite` FAIL(C-22) |

전송 레인의 2026-10-24 최종 스모크(공유 머신)도 같은 4건만 실패했다. 결과 파일: `build/poc/smoke/smoke.md`, 사본 `docs/bench/wo02-agent-2026-11-02/transport/smoke.md`·`smoke-strict.md`.

### 9.7 확인 작업 결과

| 확인 작업(04 WO-02) | 결과 |
|---|---|
| A-1 | grpc-netty 제안(ADR-0005, 11.1). 대안 표는 ADR |
| A-14·11 §15 #2·19 §13 #5 | javalite 불채택. `map`·`optional`은 지원 |
| 11 §15 #3 | grpc-netty NIO·JDK TLS 가정 유지 |
| 11 §15 #4·10 U-4 | 8u252 ALPN 확인(8u242 전송 비활성, 8u252 동작). 8u261 TLS 1.3은 미확인(해당 빌드 없음) |
| 10 U-6·19 §13 #6·C-23 | Shadow가 프로퍼티 문자열을 relocate함. RELOCATED 확정 + 자체 리터럴 규칙(11.3) |
| 19 §13 #4 | `io.grpc:*` minimize 루트 |
| U-5 관련(ByteBuddy `experimental`) | relocate 형태로만 존재(10.4와 같음) |

### 9.8 재현

```
./gradlew :test:agent:transport-poc:transportPocJars -Pargos.ui.skip=true     # jar 9개 + 보고서(build/poc/<id>/reports)
./gradlew :test:agent:transport-poc:transportPocTest -Pargos.ui.skip=true     # proto 테스트 + 스모크(JDK 8, 21)
./gradlew :test:agent:transport-poc:transportPocBench -Pargos.ui.skip=true \
    -Ptransport.poc.jdk8u242=<8u242 JRE> -Ptransport.poc.jdk8u252=<8u252 JRE>   # 약 35분(jar 빌드 제외)
```

- 일부만: `-Ptransport.poc.variants=grpc-netty-full,http1-full`, 반복: `-Ptransport.poc.repetitions=10`, 알려진 발견도 실패로: `-Ptransport.poc.strict=true`.
- 8u242/8u252 JRE: AdoptOpenJDK GitHub 릴리스 `jdk8u242-b08`, `jdk8u252-b09.1`의 `OpenJDK8U-jre_x64_windows_hotspot_*.zip`(SHA-256은 릴리스의 `.sha256.txt`와 비교). Windows에서는 `%TEMP%` 밖에 풀거나 `unzip -DD`로 풀어 임시 파일 정리에 지워지지 않게 한다(9.3).
- 전용 장비에서는 다른 부하가 없을 때 돌리고, summary.md의 CPU 부하 줄을 함께 기록한다.

### 9.9 한계

- Windows 한 대의 참고값이다. Linux(CI·운영 대상 1열)는 재지 않았다.
- 송신기는 최소 구현이다: 세션 스트림·사전·gzip·in-flight 4·재전송·토큰이 없다. 메타스페이스·클래스 수는 WO-11 본 구현에서 늘어난다.
- `Unsafe` 제외 목록은 P-3 경로(접속·전송·실패·종료)의 클래스 로드에서 얻었다. WO-11 경로를 넣은 뒤 다시 확인한다.
- 배치는 약 330바이트다. 1MiB 배치의 직접 메모리·GC는 WO-11에서 잰다.

## 10. P-6 JDK 클래스 계측

범위: 04 WO-02의 "`httpclient.jdk`(`HttpURLConnection` 1지점)·`executor`(`ThreadPoolExecutor` 1지점)". 18 T-AG-070 목록의 `CompletableFuture`(기본 실행기 `ForkJoinPool`)는 WO-02 범위 밖이라 WO-15로 남긴다. 두 모듈 모두 대상이 bootstrap 클래스로더의 `java.base` 클래스라 helper가 없고 Advice가 bootstrap API와 JDK 타입만 쓴다(10 §5.4).

### 10.1 httpclient-jdk (10 §6.5, F-A05·F-A32)

| 항목 | 구현 |
|---|---|
| 계측 지점 | `sun.net.www.protocol.http.HttpURLConnection.getInputStream()`(HTTPS는 이 클래스를 상속한 delegate가 같은 메서드를 쓴다). JDK 허용목록 클래스 |
| 1회 기록 | JDK는 첫 `getInputStream()`에서 교환을 하고, 그 안에서 `getResponseCode()`를 거쳐 `getInputStream()`을 다시 부른다. 앱도 `getResponseCode()` 뒤에 다시 부른다. 그래서 트랜잭션 안의 첫 호출만 기록한다(`ContextStore<HttpURLConnection, TraceContext>` 표식, 약참조) |
| step | `HTTP`, name = 호스트(사전 HOST), detail = `METHOD host[:port]/path`(쿼리스트링·user info 없음, `;jsessionid` 등 경로 매개변수는 인바운드 `HttpPaths.clean`과 같은 규칙으로 제거. advice 안 인라인 루프), status = `responseCode` 필드(JDK가 상태 줄을 읽을 때 채우고 4xx·5xx로 예외를 던질 때도 남는다), error = 실패 분류가 있을 때, `recordExtStat(hostId, elapsed, failure)` |
| 실패 분류 | status ≥ 500 → `HTTP_5XX`. 응답이 없을 때만 예외 종류로 `SocketTimeoutException` → `TIMEOUT`, `ConnectException`·`UnknownHostException`·`NoRouteToHostException` → `CONNECT_FAIL`, 그 밖 → `OTHER`. 4xx는 응답이므로 실패 분류가 아니다(앱에는 `FileNotFoundException`이 그대로 간다) |
| 전파(F-A32) | 요청이 아직 연결되지 않았고(`connected` 필드) `traceparent`가 없을 때만 `traceparent`(트랜잭션 trace id, 새 span id)를 넣는다. 기존 값은 보존한다 |
| PoC 밖(WO-14) | `connect()`·`getOutputStream()` 시작점(이들로 먼저 연결한 POST는 본문 쓰기 시간이 step 밖이고 헤더도 없다), `tracestate`, `propagation.exclude-hosts`, detail의 URL 경로 마스킹 |

### 10.2 executor (10 §6.6, F-A07, DSN-02-05)

| 메서드 | Advice | 스레드 | 동작 |
|---|---|---|---|
| `ThreadPoolExecutor.execute(Runnable)` enter | `ExecuteAdvice` | 제출 | 현재 트랜잭션을 작업 객체에 붙인다(`ContextStore<Runnable, TraceContext>`, 약참조 identity 키, 10만 상한). `submit()`(`FutureTask`)·`CompletableFuture.*Async(.., executor)`(`AsyncSupply`)도 `execute`를 거친다 |
| `beforeExecute(Thread, Runnable)` enter | `BeforeExecuteAdvice` | 워커 | 작업에서 트랜잭션을 떼어 활성화(스코프는 워커 스레드 키로 보관). 끝난 트랜잭션은 활성화하지 않는다. `async.step=true`면 작업 클래스 이름의 ASYNC step(람다 클래스는 `$$Lambda`까지만, 실행마다 바뀌는 접미사로 사전이 늘지 않게) |
| `afterExecute(Runnable, Throwable)` enter | `AfterExecuteAdvice` | 워커 | ASYNC step 종료(작업이 던졌으면 error)와 스코프 복원 |

- 설계 선택: 10 §6.6은 실행 측을 `Runnable.run()`·`FutureTask.run()` 등으로 잡는다. PoC는 "`ThreadPoolExecutor` 1지점"이라 같은 클래스의 `beforeExecute`/`afterExecute` 훅을 썼다. `runWorker`가 새 워커의 첫 작업까지 모든 작업 앞뒤에서 부르므로, 숨은 클래스라 변환할 수 없는 람다 작업도 래핑 없이 전파된다. 작업 객체를 바꾸지 않으므로 `remove(task)`가 그대로 동작한다(샘플 앱 `"removed":true`, 단위 테스트).
- 한계(WO-15): `beforeExecute`/`afterExecute`를 `super` 없이 재정의한 하위 클래스(Spring 6.1+ `ThreadPoolTaskExecutor`, shop-boot3의 `@Async`)는 전파되지 않는다. `ScheduledThreadPoolExecutor`는 `execute`를 거치지 않는다. `ForkJoinPool`·`CompletableFuture` 기본 실행기는 대상이 아니다. 두 훅 사이에 모듈이 비활성되면 그 워커는 다음 작업 전까지 바인딩을 유지한다(C-24처럼 enter 값으로 짝을 맞출 수 없는 두 메서드라서).
- `async.step`(10 §15, 기본 false)은 Advice가 코어 설정을 볼 수 없어 `CaptureConfig.asyncStep()`(bootstrap, `withAsyncStep` 복사본)으로 전달한다. 10 §3의 `CaptureConfig` 필드 목록에 없는 추가다(보고서 가정).

### 10.3 T-AG-070 결과 (JPMS read edge·retransform·`IllegalAccessError` 0)

| 확인 | 방법 | JDK | 결과 |
|---|---|---|---|
| 앱 로드 시점 변환 + read edge | `SampleAppAgentTest`: shop-boot3 `/api/pay/1?client=hurl`(HTTP step·`traceparent`가 ext-stub에 도착, 500 → `HTTP_5XX` 표시, 연결 거부 → error), `/api/async/executor`(람다 작업 ASYNC step, `removed:true`), 변환 목록에 두 클래스가 `bootstrap` 로더로 기록 | 17·21·25(25는 `--sun-misc-unsafe-memory-access=deny`), shop-boot2로 8·11·17·21도 | 7조합 모두 통과(8.5 표) |
| premain 전에 로드된 JDK 클래스의 retransform | `AgentAttachTest` 새 시나리오: 테스트 전용 `preload-agent.jar`가 Argos보다 먼저 `ThreadPoolExecutor`·`HttpURLConnection`을 로드 → 변환 목록 `java.util.concurrent.ThreadPoolExecutor executor,test-probe-jdk bootstrap retransformed`, `sun.net.www.protocol.http.HttpURLConnection httpclient-jdk bootstrap retransformed`, retransform된 `ThreadPoolExecutor`에서 executor Advice가 트랜잭션을 풀 스레드로 넘겨 ASYNC step(`example.trivial.TrivialApp$Handler$1`) 기록 | 8·11·17·21·25 | 5/5 통과(2026-10-24 18:56, 직전 CPU 59%, 이 시나리오 premain 452~677ms) |
| `IllegalAccessError` 0 | 위 모든 실행에서 stdout·stderr·에이전트 로그에 `IllegalAccessError`·`advice error` 없음, 로그 WARN·ERROR 0 | 8~25 | 0건 |
| Advice 예외(T-AG-040) | `advice-throw:jdbc,executor,httpclient-jdk`: JDK 클래스 Advice가 매번 던져도 앱 응답 2xx·같은 본문 | 8 (shop-boot2) | 통과 |

- JDK 25 `--sun-misc-unsafe-memory-access=deny`에서도 에이전트·앱 모두 정상이다(ByteBuddy `Unsafe` 경로 제거, ADR-0008, 아래 10.4 U-3).
- 단위 테스트(Java 8): `HttpUrlConnectionAdviceTest`(JDK 구현처럼 동작하는 하위 클래스에 같은 Advice 인라인: 1회 기록·헤더 규칙·500·404·실패 3종·트랜잭션 밖), `ExecutorAdviceTest`(같은 Advice를 인라인한 `ThreadPoolExecutor` 하위 클래스: 첫 작업·대기 작업 전파, 워커 복원, ASYNC step 이름, `remove`, 실패 작업, 끝난 트랜잭션·모듈 비활성). Advice 예외는 `Argos.adviceError`로 집계하고 테스트마다 0을 단언한다.

### 10.4 확인 작업(04 WO-02: 10 U-3·U-5·U-6)

U-3 `ClassInjector.UsingReflection` + `redefineModule`(실험 `U3Probe.java`, ByteBuddy 1.18.15 비셰이딩, 결과 `u3-results.txt`. 실험 소스와 결과 파일은 레인 스크래치 `wo02jdk/u3u5/`에 있었고 2026-11-02 이전에 삭제되었다. 표는 그 결과 파일에서 옮긴 값이다)

| JDK | 옵션 | ByteBuddy `UsingReflection` | `ClassLoader#defineClass` 리플렉션(Argos `HelperInjector` 방식) |
|---|---|---|---|
| 17.0.20.1·21 GA·25 | 없음 | `isAvailable=true`, 주입 성공(`sun.misc.Unsafe` 경유: `-verbose:class`에 Unsafe 관련 클래스 로드 JDK 21 8건·JDK 25 11건) | 실패 `InaccessibleObjectException`(java.lang 비공개) |
| 17·21·25 | `--add-opens java.base/java.lang=ALL-UNNAMED`(= `redefineModule` extraOpens와 같은 효과) | 성공 | 성공 |
| 25 | `--sun-misc-unsafe-memory-access=deny` | `isAvailable=false`, 주입 실패 `UnsupportedOperationException: … objectFieldOffset` | 실패(열지 않음) |
| 25 | deny + add-opens | `isAvailable=false`, 실패 | 성공 |

결론: `UsingReflection`은 `redefineModule`과 무관하게 `Unsafe`로 동작하고, JDK 25 deny에서는 열어도 동작하지 않는다. 10 U-3 가정("동작")은 deny 없는 JDK 17·21·25에서만 참이며 그때도 `Unsafe` 경유라 F-A35(Unsafe 0)와 맞지 않는다. Argos는 `redefineModule(java.base, extraOpens java.lang → 에이전트 unnamed 모듈)` 뒤 `defineClass` 리플렉션을 쓰며(4장), jdbc helper(`JdbcHelper`)가 shop-boot3 × JDK 17·21·25(deny 포함)에서 앱 클래스로더(`LaunchedClassLoader`)에 정의되어 SQL step을 남겼다(8.5).

U-5 ByteBuddy 최소 버전·`experimental`(실험 `U5Probe.java`, JDK 25(OpenJDK 25+36)로 컴파일한 클래스 파일 69를 `redefine` + Advice, 결과 `u5-results.txt`)

| ByteBuddy | `experimental=false` | `experimental=true` |
|---|---|---|
| 1.14.19 | 실패(`ofThisVm`: 버전 문자열 "25" 해석 불가, `Java 25 (69) is not supported`) | 성공 |
| 1.15.11 | 실패(`officially supports Java 24 (68)`) | 성공 |
| 1.16.1, 1.17.0, 1.17.5, 1.17.6, 1.17.8, 1.18.3, 1.18.15 | 성공 | 성공 |

- 결론: JDK 25(클래스 파일 69)를 `experimental` 없이 처리하는 최소 버전은 시험한 것 중 1.16.1이다(10 U-5 가정 "1.17.x 이상"보다 낮다). Argos는 1.18.15를 쓰며 `experimental`이 필요 없다.
- 셰이딩 후: `io.argos.agent.shaded.net.bytebuddy.utility.OpenedClassReader.EXPERIMENTAL_PROPERTY = "io.argos.agent.shaded.net.bytebuddy.experimental"`(`javap -constants`). 셰이딩된 ByteBuddy는 relocate된 이름만 읽으므로 앱이 자기 ByteBuddy를 위해 `-Dnet.bytebuddy.experimental=true`를 줘도 에이전트에 영향이 없고, 에이전트가 켜야 할 때는 relocate된 이름을 써야 한다(ADR-0008 RELOCATED와 같다).

U-6 Shadow 프로퍼티 문자열 relocate(→ 19 U-6)

- 셰이딩 jar(`agent-inst.jar`)의 ByteBuddy 클래스 상수에서 `net.bytebuddy.<프로퍼티>` 형태 문자열을 모두 뽑았다. 프로퍼티 이름은 전부 relocate되어 있다: `io.argos.agent.shaded.net.bytebuddy.{experimental, dump, raw, nexus.disabled, safe, naming, processor, reproducible, …}`. relocate되지 않은 것은 `ByteBuddy` 클래스의 오류 메시지 문자열 `'net.bytebuddy.naming' is set to an unknown, non-numeric value` 1건뿐이고 프로퍼티로 읽히지 않는다(`DEFAULT_NAMING_PROPERTY`는 relocate된 이름).
- 따라서 Shadow는 패키지 이름 형태의 문자열 상수를 relocate한다(10 U-6 가정 "relocate됨" 확인). 정책 RELOCATED와 `checkRelocatedStrings`(ADR-0008)가 이를 고정한다. Netty·gRPC 프로퍼티(`io.netty.noUnsafe` 등)도 같다(9.4.8).

## 11. 결정 (A-1·A-14·C-23)

결정 제안은 [ADR-0005 전송](../adr/0005-agent-transport.md)(Proposed, PM·메인테이너 확정 대기)에 있고, C-23의 ByteBuddy 근거와 `Unsafe` 경로 처리는 [ADR-0008](../adr/0008-agent-shading-and-bytebuddy-unsafe.md)에 있다. 02 §12(A-1·A-14) 표 갱신은 PM이 한다(이 WO는 설계 문서를 바꾸지 않는다).

### 11.1 A-1 전송 스택: gRPC over grpc-netty(제안)

- NIO 전송, `SslProvider.JDK`, 10 §10.3 고정 설정(이벤트 루프 1 `argos-io`, `directExecutor`, `offloadExecutor` = 이벤트 루프, 인바운드 4MiB, keepalive 60초/20초, idle 끔)을 그대로 쓴다. 11 문서 RPC 모델과 DSN-02-11은 바뀌지 않는다.
- 근거: Gate 0 P-3 기준 5개를 모두 충족한다(9.5). gRPC 전송 중 10 §14 스레드 예산 6에 들어가는 것은 grpc-netty뿐이다(9.4.3). Java 8 하한 8u252는 11 §15 #4 가정과 같다(9.4.4).
- 조건: Netty 계열(PM 결정 P1), `Unsafe` 허용 조건(P2), Windows temp 파일(P3)이 정해져야 본 구현(WO-11)에 들어갈 수 있다. P1의 기본 제안은 C-22를 바꾸지 않는 Netty 4.1.139다.
- 대체안: 자체 HTTP/1.1은 가장 작고(6.05MB) 스레드·`Unsafe`·temp 문제가 없지만 11 §3 세션 양방향 스트림과 서버 프로토콜 재설계가 필요하다. P2가 "넓히지 않음"으로 정해지면 다시 검토한다. grpc-okhttp는 스레드 예산을 넘고 executor 상한을 두면 채널이 고장 나서 제외한다.

### 11.2 A-14 `protobuf-javalite`: 채택하지 않음(제안)

- javalite는 `map`·`optional`을 지원하고(9.4.7) jar가 약 1.2MB 작지만, JDK 24+ 기본(warn)에서 `sun.misc.Unsafe` 경고 3줄을 stderr에 쓰고 `--sun-misc-unsafe-memory-access=deny`에서는 메시지를 만들지 못한다(9.4.5, F-A35 위반). 에이전트도 protobuf-java(full)를 쓰고 `gradle.properties`의 `argos.proto.lite=false`를 유지한다.

### 11.3 C-23 프로퍼티 문자열: RELOCATED 확정 + 자체 리터럴 규칙

- 정책: 셰이딩된 라이브러리는 `io.argos.agent.shaded.<원래 이름>` 프로퍼티만 읽는다. 에이전트는 relocate된 이름을 직접 설정하고 원래 이름은 설정하지 않는다. ByteBuddy(ADR-0008)와 Netty·gRPC(9.4.8) 모두 실측으로 확인했고 `checkRelocatedStrings`(정책 RELOCATED)가 강제한다.
- 덧붙인 규칙(통합에서 구현): Shadow는 에이전트 자체 클래스의 문자열 상수도 relocate한다. 자체 코드는 relocate 대상 패키지로 시작하는 리터럴을 쓰지 않고 실행 시점에 만든다. relocate된 이름을 일부러 쓰는 상수(WO-11의 Netty 프로퍼티 등)는 `checkOwnRelocatedLiterals.allowed`에 `<jar 항목>: <상수>`로 올린다.
  - 수정: `IgnoreMatchers.APP_BYTEBUDDY_PREFIX = String.join(".", "net", "bytebuddy", "")`.
  - 검사: `:agent:agent-dist:checkOwnRelocatedLiterals`(`verifyAgentJar`·`check`에 포함). `inst/io/argos/agent/` 아래 자체 클래스(셰이딩 제외)의 relocate 네임스페이스 상수(점·슬래시 형태)가 허용 목록 밖에 있으면 실패한다. 루트 bootstrap 클래스는 Shadow를 거치지 않아 검사하지 않는다(`AgentClassLoader`의 `io.argos.agent.shaded.` 위임 접두사는 의도된 상수). 배포 jar 결과 0건(`build/reports/agent-jar/own-relocated-literals.txt`).
  - 음성 확인: 옛 리터럴로 되돌리고 `checkOwnRelocatedLiterals`를 돌리면 `inst/io/argos/agent/core/boot/IgnoreMatchers.classdata: io.argos.agent.shaded.net.bytebuddy.`로 실패했다(12.1). build-logic 테스트 `checkOwnRelocatedLiterals`가 실패·허용·제외 범위를 고정한다.
  - 같은 위험의 다른 리터럴: `IgnoreMatchers`의 `"kotlin.reflect.jvm.internal."`는 kotlin을 relocate한 okhttp 비교 jar에서만 바뀐다(9.4.8). 배포 jar는 kotlin을 넣지 않으므로 지금은 0건이고, 넣으면 이 검사가 잡는다.

## 12. WO-02 통합 검증 (2026-11-02)

PM 재개 지시(2026-11-02)대로 세션 스크래치·빌드 출력·Docker 이미지가 지워진 상태에서 시작했다(Gradle 의존성 캐시와 JDK는 남아 있음). 머신에는 이 작업 말고 다른 작업이 없었다. 명령별 시작 시각·시간·종료 코드·직전 CPU 부하 원본은 [`docs/bench/wo02-agent-2026-11-02/runs.tsv`](../bench/wo02-agent-2026-11-02/runs.tsv)에 있다.

### 12.1 실행표

| # | 명령 | 결과 | 시간 |
|---|---|---|---|
| 1 | `./gradlew clean -Pargos.ui.skip=true` | 통과(전 프로젝트 빌드 출력 제거) | 44초 |
| 2 | `./gradlew build -Pargos.ui.skip=true --continue`(통합 수정 전 기준선, 직전 CPU 0%) | 통과, 408 태스크, 의존성 검증 strict | 3분 22초 |
| 3 | `./gradlew :agent:agent-core:spotlessApply :agent:agent-core:test :agent:agent-dist:agentJar :build-logic:test` | 통과: core 96/96, build-logic 60/60(새 `checkOwnRelocatedLiterals` 테스트 포함), `verifyAgentJar`에서 자체 relocate 리터럴 0 | 1분 53초 |
| 4 | (`IgnoreMatchers`의 리터럴을 `"net.bytebuddy."`로 잠시 되돌림) `./gradlew :agent:agent-dist:checkOwnRelocatedLiterals` | **의도한 실패**: `inst/io/argos/agent/core/boot/IgnoreMatchers.classdata: io.argos.agent.shaded.net.bytebuddy.`. 소스는 바로 되돌렸다 | 약 1분 |
| 5 | `bash .github/scripts/run-gitleaks.sh dir`(MaskerTest JWT 픽스처 수정 뒤) | 통과: no leaks found(36.3MB) | 1분 10초 |
| 6 | `./gradlew :test:agent:transport-poc:transportPocJars --continue`(알려진 발견 변경 전 구성) | 실패(레인 보고와 같음): `pocCheckMajorVersionGrpcNettyFull`·`…Lite`만 실패(Netty Java 11 클래스 51개). check-shading 보고서는 base·`grpc-netty41-full-x`·`http1-full-x` 통과, 나머지 7개 실패 | 8분 39초 |
| 7 | `./gradlew :test:agent:transport-poc:spotlessApply :test:agent:transport-poc:transportPocJars --continue` | 통과: grpc-netty 4.2 두 jar의 C-22 위반을 알려진 발견 P1로 단언(51개, 모두 `shaded/io/netty/`) | 1분 49초 |
| 8 | `./gradlew :test:agent:transport-poc:transportPocTest`(직전 CPU 6%) | 통과: `protoLiteTest` 3/3, `protoFullTest` 3/3, 스모크 20회 중 PASS 16·알려진 발견 4(grpc-netty × JDK 21)·예상 밖 0 | 1분 47초 |
| 9 | `… pocCheckMajorVersionGrpcNettyFull … pocCheckMajorVersionGrpcNettyLite … transportPocSmoke -Ptransport.poc.strict=true --continue` | **의도한 실패**: 두 major 검사와 grpc-netty × JDK 21 스모크 4회만 실패 | 1분 34초 |
| 10 | `./gradlew :test:agent:transport-poc:transportPocBench -Ptransport.poc.jdk8u242=… -Ptransport.poc.jdk8u252=…`(1차) | 태스크는 통과했지만 8u242·8u252 행이 무효(9.3: 임시 파일 정리가 JRE `bin/`을 지움). 버리고 다시 쟀다 | 32분 38초 |
| 11 | 같은 명령(2차, JRE를 `unzip -DD`로 다시 풀어서, 직전 CPU 3%) | 통과: 212회, 9장 표의 원본(`build/poc/bench/20261102-203220/`) | 35분 25초 |
| 12 | `./gradlew :agent:agent-dist:check verifyAgentJar :agent:agent-testing:archTest`(직전 CPU 0%) | 통과(모두 최신 상태) | 18초 |
| 13 | `./gradlew :agent:agent-testing:archTest --rerun :agent:agent-dist:checkShading --rerun …checkOwnRelocatedLiterals --rerun …checkRelocatedStrings --rerun …checkMajorVersion --rerun …checkModuleIndex --rerun …checkLicenses --rerun` | 통과: archTest 99/99, 배포 jar 검사 6개 다시 실행 | 14초 |
| 14 | `bash test/agent/check-shading.sh agent/agent-dist/build/libs/argos-agent-0.0.0-SNAPSHOT.jar` | 통과(12.2) | 6초 |
| 15 | `jar tf agent/agent-dist/build/libs/argos-agent-0.0.0-SNAPSHOT.jar` | 2,730개 항목(12.2) | - |
| 16 | `./gradlew :agent:agent-testing:integrationTest -Pagent.attach.jdks=8,11,17,21,25 -Pagent.e2e=shop-boot2@8,shop-boot2@11,shop-boot2@17,shop-boot2@21,shop-boot3@17,shop-boot3@21,shop-boot3@25`(직전 CPU 2%) | 통과 51/51: C-27 플래그 확인 1, 부착 8시나리오 × JDK 5 = 40, 샘플 앱 10(12.3) | 2분 4초 |
| 17 | 잠금·검증 메타데이터 재생성(빈 Gradle 캐시 Linux 컨테이너, 12.4) | 통과, 결과가 저장소 파일과 바이트 단위로 같음 | 6분 22초 |
| 18 | 같은 컨테이너에서 메타데이터를 처음부터 생성(비교용, 12.4. 19번과 겹침) | 통과, 컴포넌트 집합 동일(1,191개) | 6분 3초 |
| 19 | `./gradlew build checkDevOnlyLicenses -Pargos.ui.skip=true --continue`(의존성 검증 strict, 직전 CPU 36%: 18번 컨테이너와 겹침) | 통과, 448 태스크(313 실행). `checkLicenses`·`checkDevOnlyLicenses` 전 프로젝트 포함 | 1분 14초 |
| 20 | `./gradlew build --rerun-tasks --continue`(UI 포함 전체, 모든 태스크 재실행, 직전 CPU 6%) | 통과, 414 태스크 모두 실행. UI Vitest 31 파일 456/456, lint·typecheck·build | 3분 32초 |
| 21 | `bash test/lint/check-spdx.sh` | 통과: OK(981 files, 2 excluded) | 4초 |
| 22 | pr.yml `build-java` 모의: `docker run --cpus=4`(nproc 12), 빈 Gradle·pnpm·Corepack 캐시, `./gradlew build -x integrationTest --continue`(12.5) | 통과, 414 태스크 모두 실행, Vitest 456/456(66초) | 9분 7초(Gradle 8분 58초) |
| 23 | 같은 모의 `docker run --cpuset-cpus=0-3`(nproc 4, GitHub 러너와 같은 CPU 수) | 통과, 414 태스크, Vitest 456/456(40초) | 7분 39초(Gradle 7분 31초) |
| 24 | `bash .github/scripts/run-gitleaks.sh dir`(문서·증거 정리 뒤 1차) | 실패: leaks 3. 전송 PoC 가짜 수집기가 빌드 출력(`test/agent/transport-poc/build/poc/*/collector/server.p12`)에 남긴 일회용 자체 서명 PKCS12 키(규칙 `pkcs12-file`). git 무시 대상이지만 `dir` 스캔은 작업 트리 전체를 읽는다 | 2분 33초 |
| 25 | `CollectorProcess.close()`가 수집기 종료 뒤 키를 지우도록 고치고 남은 3개를 지운 뒤 `./gradlew :test:agent:transport-poc:spotlessApply :test:agent:transport-poc:check :test:agent:transport-poc:transportPocTest` | 통과: 스모크 결과 같음(PASS 16, 알려진 발견 4), 실행 뒤 `server.p12` 0 | 1분 43초 |
| 26 | `bash .github/scripts/run-gitleaks.sh dir`(최종) | 통과: no leaks found(91.2MB). `docs/bench/wo02-agent-2026-11-02/`만 따로 스캔해도 no leaks | 2분 21초 |
| 27 | `./gradlew build -Pargos.ui.skip=true --continue`(최종, 직전 CPU 7%), `bash test/lint/check-spdx.sh` | 통과: 409 태스크(45 실행), SPDX OK(981 files, 2 excluded) | 8초 |

### 12.2 배포 jar (T-BLD-001~006)

`argos-agent-0.0.0-SNAPSHOT.jar`: 4,074,167 bytes, SHA-256 `bfe8341fb33a80ee3ffaadf067d8f4077b566d380ee514f1b6f3ebf0f50218e3`, 클래스 2,648개(자체 141 = 루트 bootstrap 30 + `AgentPremain` + `inst/` 110, 셰이딩 ByteBuddy 2,507), `jar tf` 2,730개 항목. 번들 의존성은 `net.bytebuddy:byte-buddy:1.18.15:Apache-2.0` 하나다(`META-INF/argos-deps.txt`).

```
ok   [packages] 2648 classes, all below io/argos/agent/
ok   [root-exposure] root classes only in io/argos/agent/bootstrap/ (+ Premain-Class io.argos.agent.AgentPremain)
ok   [root-entries] outside inst/ only bootstrap classes, the Premain-Class, META-INF notices/SBOM, manifest
ok   [meta-inf] LICENSE, NOTICE, THIRD-PARTY-NOTICES, argos-deps.txt, sbom/agent.cdx.json, manifest
ok   [signatures] no signature files
ok   [major-version] 2648 classes: own major 52, shaded <= 52 (0 shaded below 52; read from jar entries)
ok   [unsafe] no sun.misc.Unsafe reference outside the allow-list (0 entries)
ok   [native] no native libraries
ok   [size] 4074167 bytes <= 15 MB (15000000 bytes)
ok   [duplicates] no duplicate classes or entries
check-shading: OK (2648 classes, 4074167 bytes, agent/agent-dist/build/libs/argos-agent-0.0.0-SNAPSHOT.jar)
```

| 검사 | 결과 | 보고서(`agent/agent-dist/build/reports/agent-jar/`, 사본 `docs/bench/wo02-agent-2026-11-02/agent-jar/`) |
|---|---|---|
| T-BLD-001 major(C-22) | 자체 141개 52, 셰이딩 2,507개 52 | `major-version.txt` |
| T-BLD-002 패키지·루트 노출 | 모두 `io/argos/agent/` 아래, 루트는 bootstrap·`AgentPremain`·META-INF뿐 | `check-shading.txt` |
| T-BLD-003 META-INF | LICENSE·NOTICE·THIRD-PARTY-NOTICES·`argos-deps.txt`·`sbom/agent.cdx.json`·매니페스트(`Premain-Class`·`Agent-Class`·`Main-Class` = `io.argos.agent.AgentPremain`, `Can-Retransform-Classes: true`, `Argos-Commit`, `Argos-Protocol-Version: 1`) | `check-shading.txt`, `jar-tf.txt` |
| T-BLD-004 `Unsafe` | 허용 목록(0개) 밖 참조 0 | `check-shading.txt` |
| T-BLD-005 프로퍼티 문자열·자체 리터럴 | ByteBuddy 7개 이름 relocate 형태만, 자체 클래스 relocate 상수 0 | `relocated-strings.txt`, `own-relocated-literals.txt` |
| T-BLD-006 크기·임시 파일 | 4.07MB ≤ 15MB. 임시 파일 0은 부착 테스트 JDK 5종(감시 디렉터리 이벤트 0)과 샘플 앱 기준선 비교(12.3) | `check-shading.txt`, `AgentAttachTest`, `SampleAppAgentTest` |
| C-25 모듈 색인 | executor, httpclient-jdk, jdbc, servlet, spring-webmvc 5개 모두 jar에 있음 | `modules.txt` |

### 12.3 샘플 앱 매트릭스·부착 테스트 재실행 (T-AG-010·040·070, T-BLD-006)

12.1의 16번 실행. 모든 자식 JVM은 C-27 플래그와 `-XX:-UsePerfData`, 실행별 temp 디렉터리로 돈다. 단언은 8.4·8.5·10.3과 같다(트랜잭션 서비스 이름·상태·elapsed, `/api/orders/1`의 SQL step = 앱이 센 문장 수, HTTP·ASYNC step, 변환 목록, `IllegalAccessError` 0, stdout 비로그 줄 0, stderr CDS 안내 외 0, 에이전트 로그 WARN·ERROR 0, temp에 에이전트 고유 이름 0).

| 앱 | JDK | 추가 플래그 | 결과 | 포트/준비(ms) | premain(ms) | 트랜잭션 | `/api/orders/1` SQL step | HTTP step(전체) | ASYNC step | 변환 클래스 |
|---|---|---|---|---|---|---|---|---|---|---|
| shop-boot2 | 8 (Temurin 1.8.0_504) | - | 통과 | 5,608 / 5,803 | 211 | 9 | 1 | 3 | 2 | 18 |
| shop-boot2 | 11 (Temurin 11.0.32.1) | - | 통과 | 6,386 / 6,617 | 265 | 9 | 1 | 3 | 2 | 18 |
| shop-boot2 | 17 (Temurin 17.0.20.1) | - | 통과 | 5,965 / 6,164 | 250 | 9 | 1 | 3 | 2 | 18 |
| shop-boot2 | 21 (Oracle 21+35 GA) | - | 통과 | 5,797 / 5,995 | 326 | 9 | 1 | 3 | 2 | 18 |
| shop-boot3 | 17 (Temurin 17.0.20.1) | - | 통과 | 7,746 / 7,988 | 262 | 9 | 1 | 3 | 1 | 18 |
| shop-boot3 | 21 (Oracle 21+35 GA) | - | 통과 | 7,331 / 7,564 | 337 | 9 | 1 | 3 | 1 | 18 |
| shop-boot3 | 25 (OpenJDK 25+36) | `--sun-misc-unsafe-memory-access=deny` | 통과 | 6,324 / 6,527 | 300 | 9 | 1 | 3 | 1 | 18 |
| shop-boot2 | 8 | `-Dargos.test.faults=advice-throw:servlet` | 통과(T-AG-040: 앱 200 ×3, 트랜잭션 0) | - | - | 0 | - | - | - | - |
| shop-boot2 | 8 | `-Dargos.test.faults=advice-throw:jdbc,advice-throw:executor,advice-throw:httpclient-jdk` | 통과(T-AG-040: 요청 3종 2xx, step 0) | - | - | 3 | 0 | 0 | 0 | - |
| shop-boot2 | 8 | 에이전트 없음(T-BLD-006 기준선) | 통과(에이전트 실행의 temp 항목이 모두 기준선에도 있음: Tomcat 작업 디렉터리뿐) | - | - | - | - | - | - | - |

- 변환 클래스 목록은 같은 앱이면 JDK 사이에 같고 8.5의 베이스라인(18개)과 같다. shop-boot3 × JDK 25 실행의 temp에는 앱 자신의 NIO 선택기가 만든 `socket_<n>` 2개가 기록됐다(에이전트 고유 이름 아님, 8.5 발견과 같음).
- 부착 테스트(`AgentAttachTest`) 40/40: JDK 8·11·17·21·25 각각 배포 jar 임시 파일 0·출력 0·격리, `-Xshare:off` stderr 0, 프로브 jar 트랜잭션·helper·JDK 클래스, premain 전 로드 JDK 클래스 retransform(T-AG-070), 킬 스위치 2단·1단, Advice 예외 주입, `-verbose:class`로 ByteBuddy `Unsafe` 경로 로드 0.
- 기동·premain 수치는 다른 작업이 없을 때의 참고값이다(2026-10-24 공유 머신 값은 8.5). 에이전트 유무 비교는 P-2(13장)가 한다.
- 원시 자료: [`docs/bench/wo02-agent-2026-11-02/agent-e2e/`](../bench/wo02-agent-2026-11-02/README.md)(조합별 변환 목록·JSONL·에이전트 로그·stdout·temp 이벤트), 테스트별 결과 수는 `agent-tests.tsv`.

### 12.4 잠금 파일·의존성 검증 메타데이터 재생성 (dev-setup 7장)

- 방법: 작업 트리의 추적·비무시 파일(`git ls-files -co --exclude-standard`, 1,870개)을 tar로 묶어 컨테이너 안 파일 시스템에 풀고(Windows 바인드 마운트를 쓰지 않음), 빈 `GRADLE_USER_HOME`으로 dev-setup 7장 명령을 그대로 돌렸다. 이미지 `eclipse-temurin:21-jdk@sha256:ebfbb18d11ff163ee83b21258acf12248af48d22a0fdfdc68fa1a1516c05976a`, 같은 래퍼(Gradle 9.8.2), 컨테이너 메모리 6GiB, nproc 12.

```
./gradlew --no-daemon --write-verification-metadata sha256 --write-locks --no-configuration-cache \
  help resolveAndLockAll build checkDevOnlyLicenses --continue -Pargos.ui.skip=true
```

| 실행 | 결과 |
|---|---|
| 기존 `verification-metadata.xml`에 더하기(문서 절차) | BUILD SUCCESSFUL, 488 태스크, 5분 50초. 결과 잠금 파일 42개와 `verification-metadata.xml`이 저장소 파일과 **바이트 단위로 같다**(바꿀 것 없음) |
| 같은 명령, 메타데이터를 지우고 처음부터 | BUILD SUCCESSFUL, 5분 56초. 컴포넌트 1,191개로 저장소 파일과 집합이 같다(빠진 것·남는 것 0). 차이는 손으로 관리하는 항목뿐이다: `trusted-artifacts`(javadoc·sources), `protoc`·`protoc-gen-grpc-java`의 다른 플랫폼 실행 파일(WO-00, dev-setup 7장) |

- 따라서 WO-02(servlet API 5개, 전송 PoC 의존성)와 WO-03(Testcontainers 등)의 의존성이 모두 이미 들어 있고, 지울 낡은 항목도 없다. 2026-10-24 19:56에 다시 만든 파일이 최종 의존성 집합과 같다.
- Windows 검증(strict, 기본값): 12.1의 19·20번 실행이 `--dependency-verification` 옵션 없이 통과했다.

### 12.5 pr.yml `build-java` 모의 (25분 예산)

- GitHub Actions는 이 환경에서 돌릴 수 없어 Linux 컨테이너로 모의했다. 이미지는 스크래치 Dockerfile로 만든 GitHub `ubuntu-latest` 대응: Ubuntu 24.04(`eclipse-temurin:21-jdk-noble@sha256:114cabb7…ab2d`) + Temurin 8(`eclipse-temurin:8-jdk-noble@sha256:cadba864…8973`의 JDK) + Node 24.22.2와 Corepack(`node:24-bookworm-slim@sha256:c86ecead…920c`), `JAVA_HOME_8_X64`·`JAVA_HOME_21_X64`·`GRADLE_TOOLCHAINS_FROM_ENV`.
- 단계는 pr.yml과 같다: 작업 트리 사본(tar, `gradlew`·`*.sh` 실행 권한), `org.gradle.java.installations.fromEnv` 등록, `corepack enable`, `corepack install -g pnpm@12.14.0`, `./gradlew build -x integrationTest`(`--continue` 추가). Gradle·pnpm·Corepack 캐시는 비어 있다(래퍼 배포판과 모든 의존성을 다시 받음). `GITHUB_SHA`는 `4a43e77…`.

| 조건 | 결과 | 전체 | Gradle | Vitest |
|---|---|---|---|---|
| `--cpus=4 --memory 6g`(CPU 할당량 4, nproc 12) | BUILD SUCCESSFUL, 414/414 실행 | 9분 7초 | 8분 58초 | 31 파일 456/456, 66초 |
| `--cpuset-cpus=0-3 --memory 6g`(nproc 4) | BUILD SUCCESSFUL, 414/414 실행 | 7분 39초 | 7분 31초 | 31 파일 456/456, 40초 |

- 25분 예산(18 §2.4, pr.yml `timeout-minutes: 45`) 대비 여유가 크다. WO-01 통합 때의 모의(7~8.5분)와 같은 수준이고, WO-02가 더한 태스크(계측 모듈 5종 테스트, archTest 99, 전송 PoC의 spotless·라이선스, 오버헤드 하네스 단위 테스트)로 늘어난 시간은 작다. `integrationTest`(부착·샘플 앱 매트릭스)는 이 잡에 없고 integration 잡에서 돈다(기본 부착 JDK 8·21 + shop-boot3@21, 이 머신에서 약 1~2분).
- 로그: `docs/bench/wo02-agent-2026-11-02/ci/build-java-cpus4-log.txt`, `build-java-cpuset-log.txt`.

### 12.6 통합에서 바꾼 것

| 파일 | 변경 | 이유 |
|---|---|---|
| `agent/agent-core/src/main/java/io/argos/agent/core/boot/IgnoreMatchers.java` | `APP_BYTEBUDDY_PREFIX`를 실행 시점에 만든다 | 배포 jar에서 relocate되어 앱 ByteBuddy가 ignore 목록에서 빠지던 결함(9.4.8, 11.3) |
| `build-logic/src/main/kotlin/io/argos/buildlogic/AgentJarChecks.kt`, `…/test/kotlin/…/AgentJarChecksTest.kt`, `agent/agent-dist/build.gradle.kts`, `agent/agent-dist/README.md` | `CheckOwnRelocatedLiterals` 태스크와 테스트, `verifyAgentJar`에 연결 | 같은 결함의 재발 방지(ADR-0005 결정 3의 "agent-dist에 자체 코드 리터럴 검사 추가") |
| `agent/agent-core/src/test/java/io/argos/agent/core/masking/MaskerTest.java` | JWT 픽스처를 실행 시점에 `String.join`으로 만든다(값은 같음) | 저장소 전체 비밀 스캔(gitleaks `jwt` 규칙) 녹색. `.gitleaks.toml` 허용 목록을 넓히지 않았고 마스킹 단언은 그대로다 |
| `test/agent/transport-poc/build.gradle.kts`, `src/bench/…/TransportBench.java`, `BenchOptions.java`, `README.md` | 알려진 발견 P1·P3 단언, `-Ptransport.poc.strict=true` | 9.6 |
| `test/agent/transport-poc/src/bench/…/CollectorProcess.java` | 수집기 종료 뒤 일회용 키 `server.p12` 삭제 | 저장소 전체 비밀 스캔(12.1 24~26번) |
| `docs/adr/0005-agent-shading-and-bytebuddy-unsafe.md` → `docs/adr/0008-agent-shading-and-bytebuddy-unsafe.md` | 파일 이름·제목 번호 변경, 저장소의 참조 전부(코드 주석·README·ADR·PoC 문서) 갱신 | PM 지시(0006·0007은 저장소 ADR). 전송 ADR은 0005 유지 |
| `docs/adr/0005-agent-transport.md` | 번호 안내, 근거 경로, 확인 사항(리터럴 수정 완료) 갱신 | 문서 합침·수정 반영 |
| `docs/poc/agent-poc.md`, `docs/poc/agent-transport.md`(삭제) | 9장으로 합치고 11~14장 추가, 지워진 스크래치 경로 정리 | 4번 지시 |
| `docs/bench/wo02-agent-2026-11-02/` | 통합 증거(신규) | 원시 자료 보존 |
| `gradle/verification-metadata.xml`, `*.lockfile` | 변경 없음(재생성 결과가 저장소 파일과 같음, 12.4) | 3번 지시 |
| `CLAUDE.md`(빌드·검증 명령 절), `docs/README.md`(문서 표·상태) | 에이전트 PoC 매트릭스, 전송 비교·벤치, 오버헤드 하네스 명령. `verifyAgentJar` 설명 갱신 | 6번 지시 |

### 12.7 수정 1라운드 뒤 재검증 (2026-11-02 22:23~)

- 배경: PMO 판정(REJECTED: B-1 `CHANGELOG.md`에 WO-02 항목 없음, B-2 02 §5.2 분류 요청 없음) 뒤 수정 1라운드가 `CHANGELOG.md`(`[Unreleased]` → `Added` 항목 2개)와 이 문서 14.4만 바꿨다. 코드·빌드 스크립트·의존성은 바뀌지 않았다.
- 방법: 통합 리드가 `./gradlew clean`으로 빌드 출력을 지운 뒤 지시된 검증을 모두 다시 돌렸다. 빌드 캐시가 이전 결과를 내주지 않도록 Gradle 명령에 `--rerun-tasks`(태스크 하나는 `--rerun`)를 붙였다. 머신에는 다른 작업이 없었고, Gradle과 자식 JVM의 TMP·TEMP·TMPDIR는 통합 리드 스크래치였다. 의존성 검증은 기본값(strict)이다.
- 결과: 지시된 명령이 모두 통과했다(9번은 의도한 실패). 배포 jar는 12.2와 바이트 단위로 같다(SHA-256 `bfe8341f…18e3`, 4,074,167 bytes). `jar tf` 목록과 샘플 앱 7조합의 변환 클래스 목록도 `docs/bench/wo02-agent-2026-11-02/`의 사본과 같다. 명령별 원본은 같은 디렉터리 `runs.tsv`의 29번 이후 행이다.

| # | 명령 | 결과 | 시간 | 직전 CPU |
|---|---|---|---|---|
| 1 | `./gradlew clean -Pargos.ui.skip=true` | 통과 | 31초 | 7% |
| 2 | `./gradlew build -Pargos.ui.skip=true --rerun-tasks --continue` | 통과, 409 태스크 모두 실행. 에이전트 단위 테스트(bootstrap 26, core 96, api 8, servlet 25, spring-webmvc 14, jdbc 13, executor 8, httpclient-jdk 9, agent-testing 11, agent-dist 1, 나머지 계측 모듈 10 × 1), 오버헤드 하네스 33, build-logic 60, 실패 0 | 3분 36초 | 0% |
| 3 | `./gradlew build --rerun-tasks --continue`(UI 포함 전체) | 통과, 414 태스크 모두 실행. Vitest 31 파일 456/456, UI lint·typecheck·build | 3분 14초 | 5% |
| 4 | `./gradlew :agent:agent-dist:check verifyAgentJar :agent:agent-testing:archTest -Pargos.ui.skip=true --rerun-tasks` | 통과, 66 태스크. archTest 99/99. `checkShading`, `checkMajorVersion`, `checkRelocatedStrings`, `checkOwnRelocatedLiterals`, `checkModuleIndex`, `checkLicenses`, `checkForbiddenDependencies` 실행 | 52초 | 2% |
| 5 | `bash test/agent/check-shading.sh agent/agent-dist/build/libs/argos-agent-0.0.0-SNAPSHOT.jar` | 통과: 10개 검사 ok, `check-shading: OK (2648 classes, 4074167 bytes)`(T-BLD-001~004, 12.2와 같음) | 5초 | 0% |
| 6 | `jar tf`, `sha256sum`, 매니페스트·`META-INF/argos-deps.txt` 확인 | 2,730개 항목. `Premain-Class`·`Agent-Class`·`Main-Class` = `io.argos.agent.AgentPremain`, `Argos-Commit: 4a43e77…`, 번들 의존성 `net.bytebuddy:byte-buddy:1.18.15:Apache-2.0` 하나 | - | - |
| 7 | `./gradlew :agent:agent-testing:integrationTest --rerun -Pargos.ui.skip=true -Pagent.attach.jdks=8,11,17,21,25 -Pagent.e2e=shop-boot2@8,shop-boot2@11,shop-boot2@17,shop-boot2@21,shop-boot3@17,shop-boot3@21,shop-boot3@25` | 통과 51/51: C-27 플래그 확인 1, `AgentAttachTest` 40(8시나리오 × JDK 5, T-BLD-006·T-AG-060·070·040), `SampleAppAgentTest` 10(T-AG-010·070 7조합, T-AG-040 2, T-BLD-006 기준선). 모든 자식 JVM이 `-XX:+UnlockDiagnosticVMOptions -XX:+BytecodeVerificationLocal`, shop-boot3 × 25는 `--sun-misc-unsafe-memory-access=deny`. 조합마다 트랜잭션 9건, 변환 클래스 18개, `IllegalAccessError`·`advice error` 0. 포트 바인딩 5.6~7.4초, premain 205~327ms | 1분 51초 | 2% |
| 8 | `./gradlew :test:agent:transport-poc:transportPocJars :test:agent:transport-poc:transportPocTest -Pargos.ui.skip=true --rerun-tasks --continue` | 통과, 193 태스크 모두 실행. 비교 jar 9종 재조립과 보고서, grpc-netty 4.2 두 jar의 알려진 발견 P1(Netty 클래스 51개) 단언, `protoLiteTest` 3/3·`protoFullTest` 3/3, 스모크 20회 PASS 16·알려진 발견 4(grpc-netty × JDK 21, P3)·예상 밖 0. 남은 `server.p12` 0 | 10분 30초 | 1% |
| 9 | `./gradlew …:pocCheckMajorVersionGrpcNettyFull --rerun …:pocCheckMajorVersionGrpcNettyLite --rerun …:transportPocSmoke --rerun -Ptransport.poc.strict=true --continue` | **의도한 실패**: 두 major 검사와 grpc-netty × JDK 21 스모크 4회만 실패, 나머지 16회 PASS(9.6과 같음) | 1분 37초 | 14% |
| 10 | `./gradlew checkLicenses checkDevOnlyLicenses -Pargos.ui.skip=true --rerun-tasks --continue` | 통과, 126 태스크. `checkLicenses` 39개 프로젝트 모두 허용 목록 안, `checkDevOnlyLicenses` 39개 프로젝트 통과 | 24초 | 12% |
| 11 | `bash test/lint/check-spdx.sh` | 통과: OK(981 files, 2 excluded) | 10초 | 17% |
| 12 | `bash .github/scripts/check-file-modes.sh` | 실패(기존 상태, 지시 목록 밖): `gradlew: not tracked`. 커밋이 0건이라 생기며 커밋 때 `git add --chmod=+x`로 해소한다 | 0초 | 7% |
| 13 | `bash .github/scripts/check-workflows.sh` | 통과: 4 file(s) clean | 0초 | 5% |
| 14 | `bash .github/scripts/run-gitleaks.sh dir` | 통과: no leaks found(49.84MB). 남은 컨테이너 0 | 3분 34초 | 5% |
| 15 | pr.yml `changelog` 잡 모의: 스크래치 git 저장소(base = WO-02 항목 2개를 뺀 `CHANGELOG.md`, head = 현재 파일)에서 `bash .github/scripts/check-changelog.sh HEAD~1 HEAD`, 음성 `HEAD HEAD` | 양성 exit 0(`found a new entry under '## [Unreleased]'`), 음성 exit 1. Argos 저장소에는 git 쓰기를 하지 않았다 | - | - |
| 16 | `java -jar <jar> --version`, `--check`(JDK 8u504, 21, 25 + `deny`) | exit 0, `[PASS] JDK 1.8.0_504`·`JDK 21`·`JDK 25`, 나머지 점검은 WO-19 안내 1줄(CHANGELOG 문구 "`--check` (JDK version only)" 확인) | - | - |
| 17 | 잠금·검증 메타데이터 재생성: 빈 `GRADLE_USER_HOME`, `eclipse-temurin:21-jdk@sha256:ebfbb18d…976a`(Docker 초기화로 다시 받음), 작업 트리 1,961개 파일 tar, 12.4와 같은 명령 | 통과, 488 태스크 모두 실행(Gradle 7분 20초). 잠금 파일 42개와 `verification-metadata.xml`(컴포넌트 1,191개, LF)이 저장소 파일과 **바이트 단위로 같다** → 바꿀 것 없음. WO-03 의존성도 그대로 포함 | 7분 29초 | 31% |
| 18 | pr.yml `build-java` 모의 `docker run --cpus=4 --memory 6g`(nproc 12), 빈 Gradle·pnpm·Corepack 캐시, `./gradlew build -x integrationTest --continue`(이미지는 12.5와 같은 base digest로 다시 만듦) | 통과, 414 태스크 모두 실행, Vitest 31 파일 456/456(84초). 25분 예산 안. 12.5(9분 7초)보다 2분 느린 것은 시작 직전 Docker VM 부하(17번 컨테이너 종료 직후)와 다운로드 차이로 본다 | 11분 6초(Gradle 10분 53초) | 40% |
| 19 | 문서 수정·증거 복사 뒤 `./gradlew build -Pargos.ui.skip=true --continue`, `bash test/lint/check-spdx.sh`, `bash .github/scripts/run-gitleaks.sh dir` | 통과: 409 태스크(45 실행, 의존성 검증 strict), SPDX OK(981 files, 2 excluded), no leaks found(51.78MB, 새 증거 로그 포함) | 16초 / 3초 / 2분 53초 | 22% / 37% / 14% |

- 7번의 수치는 12.3과 같은 범위다(포트 바인딩 shop-boot2 5.6~6.4초, shop-boot3 6.4~7.4초). 모두 참고값이다.
- 이번 라운드에서 통합 리드가 바꾼 것은 문서뿐이다: 14.4에 트랜잭션 스레드 이름 행 추가(PMO 발견 C-2가 든 필드 중 수정 라운드 표에 빠진 것), 이 절, 머리말 상태 줄, `docs/bench/wo02-agent-2026-11-02/runs.tsv`·`README.md`, `docs/README.md` 상태 줄. `CLAUDE.md`는 새 명령이 없어 바꾸지 않았다.

## 13. P-2 오버헤드

P-2는 [`docs/poc/agent-overhead.md`](agent-overhead.md)가 다룬다(01 §7.3 11항 대응표, 하네스 `test/agent/overhead`, 원시 자료 [`docs/bench/wo02-overhead-2026-10-24/`](../bench/wo02-overhead-2026-10-24/README.md)).

- 공유 노트북에서는 유효(VALID) 세트가 0개라 **Gate 0 오버헤드를 판정하지 않았다**. 판정은 사람이 전용 장비(앱 서버 + 부하기)에서 그 문서 9장 절차로 낸다(측정 약 7.5시간). WO-02 수동 수락 기준 "사람이 1회 재실행해 재현"도 그 실행으로 채운다.
- 참고값(판정 아님): no-op 상대 CPU +3.45~4.9%(한도 5% 근처, 위험), p99 차이 없음, shop-boot3 기동 기본 CDS +1.71~1.72초·`-Xshare:off` +2.01~2.09초(N-04 +2초 한도 근처이거나 넘음, 가장 큰 위험), 메모리·스레드는 여유가 크다.
- 이번 통합에서는 PM 지시로 오버헤드 측정 세트를 돌리지 않았다. 하네스 단위 테스트는 `build`에 포함되어 통과한다(12.1).

## 14. PM 결정·문서 정합·후속

WO-02 레인 보고서와 통합에서 모은 항목이다. 설계 문서(01~06, 10~19)와 ADR-0000~0004는 이 WO에서 바꾸지 않았다.

### 14.1 PM 결정 필요

| 항목 | 내용 | 근거 |
|---|---|---|
| A-1·A-14 확정, 02 §12 갱신 | grpc-netty 유지, javalite 불채택 제안 | 9장, 11장, ADR-0005 |
| (P1) Netty 계열과 C-22 | Netty 4.2.19는 Java 11 클래스 51개를 넣고 Java 11+에서 반드시 쓴다. 기본 제안은 Netty 4.1.139(C-22 유지) | 9.4.1·9.6, ADR-0005 |
| (P2) `Unsafe` 허용 조건 | 로드되지만 `Unsafe`를 쓰지 않는 클래스(Netty `PlatformDependent0` 등, Guava 1개)를 허용할지. 넓히지 않으면 조건을 만족하는 후보는 HTTP/1.1뿐 | 9.4.6, ADR-0005 |
| (P3) Windows NIO temp 파일과 T-BLD-006 | JDK 16+ Windows의 NIO 선택기가 temp에 `socket_<n>`을 잠깐 만든다. grpc-netty 에이전트는 실행마다 1개. 예외로 둘지, `jdk.net.unixdomain.tmpdir` 같은 회피책을 쓸지 | 9.4.11, 8.5, ADR-0005 |
| 로그 디렉터리 temp 폴백 | `log.dir` 미설정 + jar 옆 `../logs` 쓰기 불가면 10 §14대로 `java.io.tmpdir/argos-agent-<pid>`를 만든다. T-BLD-006 "temp 파일 0"과 충돌. PM 결정 뒤 CHANGELOG 문구 확정 | 7장 |
| CDS 안내 줄 | JDK 17+ 기본 CDS에서 `appendToBootstrapClassLoaderSearch` 때문에 JVM이 stderr에 1줄을 쓴다. T-AG-060 하네스(WO-05·19)의 허용 목록 또는 `-Xshare:off` 판정 | 7장 |
| premain 실패 진단 | 로거 초기화 전 실패는 출력 없이 넘어간다(AGT-02). stderr 1줄 허용 여부 | 코어 보고서 |
| 기동 위험(N-04) | shop-boot3 기동 증가 +1.7~2.1초(13장). 전용 장비 재측정 전에 클래스 로드 경로를 줄일지 | `agent-overhead.md` 7·11장 |
| 하네스 판정 규칙 | 부하기 소켓 오류의 판정(H-2), 경로별 p99(H-5), 컨테이너 부하율(H-6), 재현 기준 | `agent-overhead.md` 11장 |
| 비교 하네스의 알려진 발견 | P1·P3를 PoC 태스크에서 단언(실패로 세지 않음)하도록 바꾼 통합 판단의 수용 여부. `-Ptransport.poc.strict=true`는 원래대로 실패한다 | 9.6 |

### 14.2 설계 문서 정합(문서 개정 필요)

- 10 §2: `AgentClassLoader` 위치(bootstrap 패키지, 2장). 10 §3: `Tracer`의 long `targetId`·`dbId`, `TraceContext.markEnded()`, `ArgosThread` final, `Tracer.ignoreReason`(helper가 코어를 볼 수 없음), `CaptureConfig.asyncStep`, `Tracer.dict(SQL)`의 정규화 계약.
- 10 §9.5: `TxIdGenerator`의 다음 ms 빌리기와 `ThreadLocalRandom` 노드 id(Java 8 premain 비용). 사전 해시는 WO-02 동안 FNV-1a 64(xxh3_64는 WO-10).
- `DictKind` 번호: 구현은 C-16 순서 1~11, 10 §2(METRIC=8)와 11 §3 proto(DB_TARGET=8)가 서로 다르다. WO-11 전에 맞춘다.
- 10 §6.2 `safeImplements(HandlerAdapter)`와 10 §5.1(서브타입 매칭 제한)의 충돌: 구현은 §5.1을 따라 Spring 어댑터 5종을 이름으로 매칭.
- 10 §6.5: `HttpURLConnection` 4xx를 실패 분류 NONE으로 둔 해석. 10 §6.6: executor 실행 측을 `beforeExecute`/`afterExecute` 훅으로 구현(WO-15에서 최종 지점 결정).
- D-21·F-A28: Tomcat이 REQUEST 체인 밖에서 하는 ERROR 디스패치는 트랜잭션에 넣지 않는다(오류 페이지 시간과 그 안의 SQL은 트랜잭션 밖).
- 16 §6.6: `Masker` 패턴 보강 2건(rrn·mobile 숫자 경계, JWT 토큰 시작 기준).
- 10 U-3·U-5 가정 갱신(10.4): `UsingReflection`은 `Unsafe` 경유이며 JDK 25 deny에서 불가, JDK 25를 `experimental` 없이 처리하는 최소 ByteBuddy는 1.16.1.
- 11 §4.3 data.proto: `import "argos/v1/session.proto"` 누락(9.2). 10 §10.3의 `NioEventLoopGroup`은 Netty 4.2에서 deprecated.
- PoC 설정 키 5개(`exporter`, `exporter.file.path`, `exporter.file.max-mb`, `debug.transform-list`, `test.faults`)를 10 §15 설정 레퍼런스에 넣을지. T-BLD-010(릴리스 빌드에서 `argos.test.*` 무시)은 아직 없다.

### 14.3 후속 WO로 넘긴 것

- WO-05: 하네스 정식화(`AppLauncher` WAR 지원, WO-01 `AppProcess`와 통합, T-AG-005 녹화·diff, T-BLD-006 기준선 비교 일반화와 디렉터리 이름 길이 맞춤), 오버헤드 하네스 결함 H-1(기동 0회 세트가 PASS).
- WO-10: `StepBuffer` 열린 step 스택의 스레드 구분, CPU·alloc 차분, YAML 설정, ActiveRegistry·샘플링, 요청당 할당과 잔존 객체(`agent-overhead.md` A-2·A-3).
- WO-11: 전송 본 구현(JUL relocate 셰임, gRPC minimize 루트, Netty relocate 프로퍼티, `Unsafe` 제외 목록 재확인, 8u252 미만 ERROR 1줄, 1MiB 배치 직접 메모리), `TransactionQueue` JCTools 교체, `DictKind` 정합.
- WO-12·13·14·15·16: servlet PoC 밖 항목, jdbc PoC 밖 항목(DBC·commit·배치·바인드·fetch·URL 파서·SqlNormalizer 50케이스), httpclient-jdk `connect()`·`getOutputStream()` 시작점과 경로 마스킹, executor 재정의 하위 클래스·`ForkJoinPool`·`CompletableFuture`, `recordSqlStat`·`recordExtStat`.
- 위험 관리: Shadow `minimize()`가 리플렉션으로만 쓰이는 클래스를 지울 수 있다. 모듈·전송을 넣을 때마다 부착·샘플 앱 테스트로 확인한다. `ArgosAgentBuilder`는 ByteBuddy protected 생성자 시그니처에 의존하므로 ByteBuddy를 올릴 때 매개변수 대응·`Unsafe` 재스캔·`AgentAttachTest`(JDK 5종)를 다시 한다.

### 14.4 02 §5.2 데이터 분류 요청

WO-02가 새로 수집·기록하는 필드 가운데 [02 §5.2 데이터 분류표](../02-architecture.md)(SEC-30, [06](../06-security-requirements.md))에 행이 없는 것의 분류안이다. 03 공통 DoD는 새 필드마다 이 표를 갱신하라고 하지만 이 WO는 02 문서를 바꾸지 않으므로(14장 머리말) 여기에 적는다. 열은 02 §5.2와 같고 근거 조항 열을 더했다.

| 필드 | 분류 | 기본 수집 | 마스킹·모드 | 보존 | 삭제 경로 | 근거 조항 |
|---|---|---|---|---|---|---|
| 외부 호출 step 이름 = 대상 호스트(사전 `HOST`) | 내부 | 수집 | 호스트 이름만(포트·경로·쿼리스트링·user info 없음), 사전 상한 | 사전 90일 미참조 정리 | 사전 관리자 삭제 | 10 §6.5, F-A05, 10.1 |
| 외부 호출 step detail = `METHOD host[:port]/path` | 개인정보 가능(경로에 식별자가 들어갈 수 있다) | 경로만(쿼리스트링·user info·fragment 제외) | PoC는 경로를 그대로 둔다(detail 4KB 상한, `StepBuffer.DETAIL_MAX_CHARS`). 경로 매개변수(`;jsessionid=` 등, 비밀값 성격)는 에이전트가 제거한다(`HttpUrlConnectionAdviceTest.pathParameters`). 경로 세그먼트 마스킹은 WO-14(16 §6.6 `Masker` 적용) | profile 7일 | 삭제 API(tx 범위, SEC-33) | 10 §6.5(설계값 `METHOD host/path`, 쿼리 제거), 02 §5.1 Profile step `detail`, SEC-34, 10.1 |
| DB 대상 = 자격 증명(`user:password@`·`user/password@`)과 첫 `;`·`?` 이후 매개변수를 뺀 JDBC URL(사전 `DB_TARGET`) | 내부 | 수집 | 자격 증명·매개변수 제거(`JdbcUrls.withoutCredentials`). 공급사별 파서(`JdbcUrlParser`)는 WO-13 | 사전 90일 미참조 정리 | 사전 관리자 삭제 | 10 §6.3, F-A03, 02 §5.1 Stat `db_id`, C-16(`DictKind`), 8.5 |
| ASYNC step 이름 = 작업 클래스 이름(람다 클래스는 `$$Lambda`에서 자름, 사전 `CLASS`) | 내부 | `async.step=true`일 때만(기본 false) | 클래스 이름만(작업의 필드·인자 값 없음). 실행마다 바뀌는 람다 접미사를 잘라 사전이 늘지 않게 한다 | 사전 90일 미참조 정리, step은 profile 7일 | 사전 관리자 삭제 | 10 §6.6, 10 §15 `async.step`, F-A07, 10.2 |
| 트랜잭션 스레드 이름(JSONL `thread`, 02 §5.1 Transaction `thread_name_id`) | 내부(앱이 정한 이름이라 바인드 주소·포트 등이 들어갈 수 있다. 예: `http-nio-127.0.0.1-51403-exec-5`) | 수집 | PoC는 시작 스레드의 `Thread.getName()` 원문을 JSONL에만 쓴다(PoC 자체 상한 없음. 외부 요청이 아니라 앱 코드가 정하는 값). 번호 정규화와 사전 `THREAD`(`DictKind` 5, 사전 텍스트 상한 64KB) 적용은 WO-10·11(11 §4.3 `thread_name_id`) | xlog 30일, 사전 90일 미참조 정리 | 사전 관리자 삭제 | 02 §5.1, 11 §4.3, `TracerImpl`·`JsonlFileExporter` |
| file 익스포터의 로컬 JSONL 파일(`exporter=file`일 때만) | 개인정보 가능 데이터의 로컬 사본 | 수집 안 함(기본 `exporter`는 `grpc`. 진단·PoC·테스트용) | 수집 모드와 마스킹을 거친 필드만 기록하고 원문을 더하지 않는다(그래서 WO-14 전까지 detail 경로는 마스킹되지 않은 채 기록된다). 앱 호스트 디스크: `exporter.file.path` 또는 로그 디렉터리의 `argos-transactions.jsonl` | `exporter.file.max-mb`(기본 256MB) 상한까지 이어 쓰고, 넘으면 이후 트랜잭션을 버린다(WARN 1회). 자동 회전·만료 없음 | 운영자가 파일 삭제(서버 삭제 API 대상 아님) | 14.2 마지막 항목(PoC 설정 키), SEC-30, `JsonlFileExporter` |

- 02 §5.2에 이미 있는 필드는 구현이 그 기본값을 따른다(`CaptureConfig.DEFAULT`): 서비스 이름(쿼리스트링 제외·정규화), URL 원 경로(`getRequestURI`, `capture.query-string: off`), `remote_ip` `masked`, SQL 정규화문(실패 시 `<unparsed>`), 예외 메시지(`Masker` 마스킹 + 1KB). user_id·HTTP 헤더·SQL 바인드 파라미터는 WO-02에서 수집하지 않는다(`User-Agent`는 제외 규칙 판정에만 읽고 저장하지 않는다).
- detail 경로 마스킹은 10.1 표의 "PoC 밖(WO-14)" 항목과 14.3의 WO-14 항목(httpclient-jdk 경로 마스킹)이 같은 후속 작업이다.
- **PM이 02 §5.2에 반영한다.**
