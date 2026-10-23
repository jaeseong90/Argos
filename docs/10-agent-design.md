# Argos 에이전트 상세 설계

| 항목 | 내용 |
|---|---|
| 문서 번호 | 10 |
| 문서 버전 | 0.1 (초안) |
| 작성일 | 2026-10-23 |
| 상태 | 설계 검토 중 |
| 만족시키는 요구 | F-A01~F-A36, D-01·D-04·D-08·D-11·D-12·D-19~D-26, N-01~N-04·N-26~N-30, SEC-06·20·23·24·25·26·31·32·34·35·36·37·38, O-P01 |
| 의존 문서 | 02 아키텍처(DSN-02-01~07·09·11·16·17·20·22), 11 프로토콜, 06 보안 요구 |
| 이 문서에 의존하는 문서 | 11 프로토콜, 12 서버, 13 저장소(사전 kind·frame), 18 테스트(하네스), 19 빌드(셰이딩·classdata) |

## 1. 범위와 원칙

이 문서는 `argos-agent.jar`의 내부 구조를 Claude Code가 작업 지시서 하나로 구현할 수 있는 수준으로 정한다. 프로토콜 메시지의 필드는 11 문서, 서버 측 처리는 12 문서가 정하며 여기서는 이름만 참조한다.

설계가 따르는 순서는 02 문서 2장 원칙과 같다. 안전성(앱 스레드 비블로킹, 예외·반환값·객체 동일성 불변경, 상한 있는 메모리) > 정확성(1요청 1트랜잭션, 1 SQL 1 step, 전수 통계) > 프라이버시 기본값 > 즉시성 > 단순성.

## 2. 모듈·패키지 구조

```
agent/
├─ agent-bootstrap/   JDK 8 타겟, 의존성 0. jar 루트에 .class로 배치 → bootstrap 클래스로더
│   io.argos.agent.AgentPremain              premain/agentmain/main(--check) 진입. 20줄. 리플렉션으로 AgentBootstrap 호출
│   io.argos.agent.bootstrap.AgentBootstrap  AgentClassLoader 생성, core 기동 위임
│   io.argos.agent.bootstrap.Argos           정적 홀더: tracer(), enabled(), skip()
│   io.argos.agent.bootstrap.Tracer          Advice·helper가 쓰는 유일한 API(인터페이스)
│   io.argos.agent.bootstrap.TraceContext    트랜잭션 핸들(final). bootstrap 타입·primitive·String만 보관
│   io.argos.agent.bootstrap.Scope           activate()의 반환. close()로 이전 컨텍스트 복원
│   io.argos.agent.bootstrap.CallDepth       스레드·키별 중첩 카운터
│   io.argos.agent.bootstrap.ArgosThread     에이전트 스레드 마커(extends Thread)
│   io.argos.agent.bootstrap.KillSwitch      enabled 플래그(volatile)
│   io.argos.agent.bootstrap.ContextStore    필드 주입 + 약참조 맵 폴백 추상(VirtualField 상당)
│   io.argos.agent.bootstrap.StepType        int 상수: SQL=1 DBC=2 COMMIT=3 ROLLBACK=4 HTTP=5 METHOD=6 ASYNC=7 ERROR=8 STACK=9 MESSAGE=10
│   io.argos.agent.bootstrap.Kind            int 상수: HTTP=1 BATCH=2 MESSAGE=3
│   io.argos.agent.bootstrap.DictKind        int 상수: SERVICE=1 SQL=2 CLASS=3 METHOD=4 THREAD=5 HOST=6 FRAME=7 METRIC=8 ERROR_GROUP=9
│   io.argos.agent.bootstrap.ModuleHealth    모듈별 활성 플래그·에러 카운터(volatile boolean[], AtomicIntegerArray)
├─ agent-core/        JDK 8 타겟. inst/**.classdata → AgentClassLoader
│   io.argos.agent.core.boot        AgentStarter, AgentClassLoader, ModuleReadEdgeListener, IgnoreMatchers, PoolStrategyCache
│   io.argos.agent.core.trace       TracerImpl, Transaction, StepBuffer, ActiveRegistry, Sampler, ServiceNameResolver, IgnoreRules, TxIdGenerator, TraceIds
│   io.argos.agent.core.stats       StatsAggregator, StatWindow(SQL), StatWindow(EXT)
│   io.argos.agent.core.sql         SqlNormalizer, SqlTokenizer, BindParameterCapturer, JdbcUrlParser, DbIdentity
│   io.argos.agent.core.context     AsyncContextCarrier, Propagation(W3C), TraceStateCodec, VirtualThreads, ThreadNames
│   io.argos.agent.core.metric      MetricRegistry, JvmMetrics, GcNameMapper, PoolRegistry, TomcatRegistry, SystemMetrics, CgroupReader, ActiveSnapshotter
│   io.argos.agent.core.transport   Sender, BatchAssembler, Queues(TransactionQueue, ProfileQueue, StatsQueue, MetricQueue, ActiveQueue), GrpcTransport, SessionClient, Backoff, DictionaryCache, ClockSync
│   io.argos.agent.core.config      AgentConfig, ConfigKeys, ConfigLoader, RemoteConfigGate, EffectiveConfig, ConfigHash
│   io.argos.agent.core.command     CommandDispatcher, ThreadDumpCommand, ThreadStackCommand, SelfDiagCommand, ReloadCommand, EnvDumpCommand, EnvMasker, Cooldown
│   io.argos.agent.core.watch       SlowStackWatcher, OverheadMonitor
│   io.argos.agent.core.guard       TransformErrorListener, SelfProtection
│   io.argos.agent.core.log         AgentLogger, RollingFileSink, StderrFallbackSink
│   io.argos.agent.core.check       CheckCommand, CheckReport
│   io.argos.agent.core.masking     Masker(정규식 세트), MaskPatterns
│   io.argos.agent.core.hash        Xxh3
├─ agent-instrumentation/   JDK 8 타겟. inst/**.classdata
│   io.argos.agent.instrumentation.api      InstrumentationModule, TypeInstrumentation, TypeTransformer, SafeMatchers, Witness, HelperClasses
│   io.argos.agent.instrumentation.servlet          javax·jakarta 두 벌 매처, 공통 helper ServletHelper(주입)
│   io.argos.agent.instrumentation.springwebmvc
│   io.argos.agent.instrumentation.jdbc
│   io.argos.agent.instrumentation.pool              hikari, tomcatjdbc, dbcp2
│   io.argos.agent.instrumentation.tomcat
│   io.argos.agent.instrumentation.httpclient.{jdk,apache4,apache5,okhttp,jdk11,reactornetty}
│   io.argos.agent.instrumentation.executor
│   io.argos.agent.instrumentation.servletasync
│   io.argos.agent.instrumentation.scheduler         spring, quartz, springbatch
│   io.argos.agent.instrumentation.logging           logback, log4j2, log4j1
│   io.argos.agent.instrumentation.custom
├─ agent-dist/        셰이딩·단일 jar 조립(19 문서)
└─ agent-testing/     하네스(18 문서)
```

패키지 경계 규칙(ArchUnit으로 검사)

| 규칙 | 내용 |
|---|---|
| B-1 | `bootstrap`은 JDK 외 어떤 패키지도 import하지 않는다 |
| B-2 | `instrumentation.*`의 Advice 클래스는 `bootstrap.*`과 같은 모듈의 helper만 참조한다. `core.*` 참조 금지(helper는 대상 CL에 주입되어 core를 볼 수 없다) |
| B-3 | `core.*`는 `instrumentation.api`만 참조하고 개별 모듈 패키지는 참조하지 않는다(모듈은 ServiceLoader 아닌 `ModuleRegistry` 정적 목록으로 등록) |
| B-4 | 셰이딩 대상(`io.argos.agent.shaded.*`)은 `core.*`에서만 참조한다. helper·Advice에서 금지 |

## 3. bootstrap API

```java
// io.argos.agent.bootstrap
public final class Argos {
  private static volatile Tracer TRACER = NoopTracer.INSTANCE;
  public static Tracer tracer() { return TRACER; }
  public static boolean enabled() { return KillSwitch.ENABLED; }
  /** 모든 Advice의 첫 문장. 에이전트 스레드·킬 스위치·모듈 비활성이면 true */
  public static boolean skip(int moduleId) {
    return !KillSwitch.ENABLED || !ModuleHealth.enabled(moduleId) || Thread.currentThread() instanceof ArgosThread;
  }
  static void install(Tracer t) { TRACER = t; }   // core만 호출
}

public interface Tracer {
  TraceContext current();                                             // 현재 스레드 컨텍스트(없으면 null)
  TraceContext begin(int kind, String entryName, long startNanos);   // 새 트랜잭션 생성 + 현재 스레드 바인딩
  void end(TraceContext ctx, int status, String errorClass, String errorMessage, Throwable error, long endNanos);
  Scope activate(TraceContext ctx);                                   // 비동기 복원. close()로 이전 상태
  int  stepStart(TraceContext ctx, int type, long nameId, int targetId, long startNanos); // seq 또는 -1(상한)
  void stepEnd(TraceContext ctx, int seq, int status, boolean error, long rows, long endNanos);
  void stepDetail(TraceContext ctx, int seq, String detail);          // 4KB 상한, 마스킹은 호출자가
  void stepStack(TraceContext ctx, int seq, StackTraceElement[] frames, int maxDepth);
  void setServiceName(TraceContext ctx, String name, boolean fix);   // fix=true면 이후 변경 불가
  void setHttp(TraceContext ctx, String method, String path, String remoteIp, String userId);
  void setStatus(TraceContext ctx, int status);
  void recordSqlStat(long sqlId, int dbId, long elapsedNanos, boolean error, long rows);    // 전수 통계(D-12)
  void recordExtStat(long hostId, long elapsedNanos, int failureKind);                       // 0 ok,1 timeout,2 connect_fail,3 http5xx,4 other
  long dict(int kind, String text);                                   // xxh3_64 + 사전 캐시 등록(미등록이면 전송 예약)
  int  dbId(String jdbcUrl);                                          // DbIdentity 캐시
  String traceparent(TraceContext ctx, long childSpanId);            // 전파 헤더 생성
  String tracestate(TraceContext ctx, String incoming);              // Argos 엔트리 추가/보존
  boolean shouldPropagate(String host);                              // exclude-hosts
  long newSpanId();
  void moduleError(int moduleId, Throwable t);                        // 카운터 → 임계 초과 시 비활성
  void countIgnored(String reason);                                   // 제외 요청 카운터(D-19)
  void countOrphan(int stepType);                                     // 컨텍스트 없는 step(D-20)
  CaptureConfig capture();                                            // 현재 수집 모드(volatile 스냅샷)
}

public final class TraceContext {
  public final long txId, traceIdHi, traceIdLo, spanId, parentSpanId;
  public final int kind;                      // Kind.*
  public final long startNanos, startEpochMs;
  public volatile String serviceName; public volatile boolean serviceFixed;
  public volatile int status; public volatile boolean error;
  public volatile int dispatchDepth;          // servlet 중첩 깊이
  public volatile boolean asyncStarted, streaming, ended;
  public final java.lang.ref.WeakReference<Thread> thread;   // 느린 스택 캡처용
  public final boolean virtualThread;
  public final long cpuStartNanos, allocStartBytes;           // -1이면 측정 불가(D-23)
  public int sqlCount, extCount; public long sqlNanos, extNanos;
  public volatile Object coreRef;             // core의 Transaction(스텝 버퍼). bootstrap은 열지 않는다
}

public interface Scope { void close(); }      // AutoCloseable 아님(Advice에서 try-with-resources 금지)

public final class CallDepth {                // 키는 기동 시 등록된 작은 정수
  public static final int JDBC_STATEMENT=0, JDBC_CONNECTION=1, JDBC_DATASOURCE=2, HTTP_CLIENT=3, SERVLET=4, EXECUTOR=5, LOGGING=6;
  public static int enter(int key);           // 증가 후 값 반환(1이면 최외곽)
  public static int exit(int key);            // 감소 후 값
  // 구현: ThreadLocal<int[8]>; 가상 스레드에서도 ThreadLocal 동작
}

public class ArgosThread extends Thread {     // 모든 에이전트 스레드의 공통 상위. ThreadFactory도 제공
  public static ThreadFactory factory(String name);
}

public final class KillSwitch { public static volatile boolean ENABLED = true; }

public abstract class ContextStore<K, V> {    // VirtualField 상당
  public static <K, V> ContextStore<K, V> of(Class<K> k, Class<V> v);   // 기동 시 FieldInjector가 등록한 접근자 또는 WeakMap 폴백
  public abstract V get(K key);
  public abstract void put(K key, V value);
  public abstract V remove(K key);
  public abstract V computeIfAbsent(K key, Factory<V> f);
}

public final class ModuleHealth {
  public static boolean enabled(int moduleId);
  static void disable(int moduleId, String reason);   // core
}
```

`CaptureConfig`(bootstrap, 불변 스냅샷): `queryStringMode`, `headerWhitelist`, `remoteIpMode`, `trustedProxies`, `userIdMode`, `userIdSource`, `sqlParamMode`, `sqlParamWhitelistColumns`, `errorMessageMax`, `stackDepth`. 동적 설정 적용 시 새 객체로 교체(volatile).

## 4. 패키징·기동

### 4.1 단일 jar 구조 (DSN-02-02, F-A31)

```
argos-agent.jar
├─ META-INF/MANIFEST.MF        Premain-Class: io.argos.agent.AgentPremain
│                              Agent-Class: io.argos.agent.AgentPremain      (1.x 동적 attach)
│                              Main-Class: io.argos.agent.AgentPremain       (--check)
│                              Can-Retransform-Classes: true
│                              Can-Redefine-Classes: false
│                              Implementation-Version: X.Y.Z, Argos-Protocol-Version: N, Argos-Commit: <sha>
│                              (Add-Opens·Boot-Class-Path·Launcher-Agent-Class 없음)
├─ io/argos/agent/AgentPremain.class
├─ io/argos/agent/bootstrap/*.class          bootstrap 클래스로더에서 로드됨(jar 자체를 append)
├─ inst/io/argos/agent/core/**.classdata     AgentClassLoader만 읽는다
├─ inst/io/argos/agent/instrumentation/**.classdata
├─ inst/io/argos/agent/shaded/**.classdata    ByteBuddy, gRPC, Netty, protobuf, JCTools 등 relocate 결과
├─ inst/META-INF/services/**                  셰이딩 의존성의 ServiceLoader 파일(이름도 relocate)
├─ META-INF/LICENSE, NOTICE, THIRD-PARTY-NOTICES, argos-deps.txt, sbom/agent.cdx.json
└─ argos-agent-default.yml                    기본값 문서(참조용, 로드 안 함)
```

`.classdata` 확장자는 bootstrap·앱 클래스로더가 `inst/` 아래 클래스를 리소스로도 보지 못하게 한다. 임시 디렉터리 추출은 없다.

### 4.2 AgentClassLoader (DSN-10-01)

| 항목 | 결정 |
|---|---|
| 부모 | JDK 9+: `ClassLoader.getPlatformClassLoader()`(MethodHandle로 조회), JDK 8: `null`(bootstrap). 플랫폼 로더는 bootstrap에 위임하므로 `io.argos.agent.bootstrap.*`가 보이고, `java.sql`·`java.net.http` 같은 플랫폼 모듈 타입도 보인다 |
| 로딩 | `findClass(name)` → `inst/<name>.classdata` 읽어 `defineClass`. 부모 우선 위임이되 `io.argos.agent.core`·`instrumentation`·`shaded`는 자기 것만 |
| 리소스 | `getResource(s)`는 `inst/` 접두를 붙여 jar에서 찾는다. `META-INF/services/<relocated>`도 같은 규칙 |
| 보안 | `ProtectionDomain`은 jar의 CodeSource. 앱에서 `Class.forName("io.argos.agent.core...")`은 실패해야 한다(앱 CL은 inst/를 볼 수 없다) |
| 중복 방지 | `AgentPremain`은 시스템 CL이 로드하지만 bootstrap 타입은 참조하지 않고 `Class.forName("io.argos.agent.bootstrap.AgentBootstrap", true, null)`로 bootstrap 사본을 명시 로드한다 |

### 4.3 premain 시퀀스 (N-30)

| 단계 | 동작 | 예산 |
|---|---|---|
| P0 | `-Dargos.agent.disabled=true`면 아무것도 하지 않고 return(킬 스위치 2단, F-A15) | 0 |
| P1 | `inst.appendToBootstrapClassLoaderSearch(new JarFile(self))` | 5ms |
| P2 | `AgentBootstrap.start(inst, jarFile)`: AgentClassLoader 생성 → `core.boot.AgentStarter.start(inst, jar)` | 20ms |
| P3 | `AgentLogger` 초기화(파일 디렉터리 생성·권한 0700, 실패 시 stderr 폴백 제한 모드) | 10ms |
| P4 | `ConfigLoader`: yml → `-Dargos.*` → `ARGOS_*` 병합, 토큰 로딩(token > token-file > env), 유효성 검사(필수: `server.address`, `app.name`; `app.env` 없으면 `default` + WARN), `ConfigHash`(토큰 제외) | 30ms |
| P5 | `Argos.install(new TracerImpl(...))`, `CallDepth` 키 등록, `ContextStore` 폴백 맵 준비, `ModuleHealth` 초기화 | 10ms |
| P6 | `AgentBuilder` 조립: ignore 목록, PoolStrategy 캐시, `assureReadEdgeFromAndTo(inst, Argos.class)`, `TransformErrorListener`, 변환 목록 리스너(디버그), 모듈별 TypeInstrumentation 등록(witness는 CL별 지연 평가), `RedefinitionStrategy.RETRANSFORMATION` + `disableClassFormatChanges()`는 **이미 로드된 JDK 허용목록 클래스에만** 적용 → `installOn(inst)` | 200ms |
| P7 | 비동기 시작(모두 `ArgosThread`): `SessionClient`(DNS·TCP·TLS·Hello), `Sender`, 메트릭 스케줄러, `SlowStackWatcher`, `CommandDispatcher`. premain은 기다리지 않는다 | 5ms |
| P8 | 기동 배너 1줄(INFO): 버전·커밋·프로토콜·app/env·설정 해시(토큰 마스킹) | - |

측정: `-Dargos.debug.transform-timing=true`면 P0~P8 각 소요와 클래스별 매칭·변환 시간을 로그에 남긴다. P2~P7 합계 ≤ 500ms가 N-30 기준(클래스 변환은 앱 로딩 시점 비용이라 별도 N-27).

### 4.4 JPMS (DSN-02-03)

- `AgentBuilder.assureReadEdgeFromAndTo(instrumentation, Argos.class, CallDepth.class, ContextStore.class, ModuleHealth.class)`: 변환되는 타입의 모듈(`java.base`, `java.net.http`, `java.sql`, 앱의 자동 모듈)에서 bootstrap unnamed 모듈로 read edge를 추가한다. 누락 시 JDK 클래스에 인라인된 Advice가 `IllegalAccessError`.
- helper 주입: `ClassInjector.UsingReflection`(대상 CL의 `defineClass` 리플렉션). JDK 9+는 사전에 `inst.redefineModule(java.base, …, extraOpens = {"java.lang" → AgentClassLoader의 unnamed 모듈})`로 열어 둔다. `UsingUnsafe`는 쓰지 않는다(F-A35).
- `java.net.http` 모듈 클래스 계측(httpclient-jdk11)은 `jdk.internal.net.http` 패키지에 Advice가 인라인되므로 `redefineModule(java.net.http, extraReads = {unnamed})`도 필요하다. 리스너가 변환 직전 모듈 단위로 1회 수행한다.
- `Instrumentation.isModifiableModule()`가 false면 해당 모듈 계측을 건너뛰고 WARN 1회.

### 4.5 ignore 목록 (N-27)

`AgentBuilder.ignore()` 순서대로:

1. `nameStartsWith("io.argos.")` (shaded 포함), `isSynthetic()`, `AgentClassLoader`가 로드한 클래스 전부(`isLoadedBy`).
2. 생성 클래스: `$$Lambda`, `$Proxy`, `$$SpringCGLIB$$`, `$$EnhancerBy`, `$HibernateProxy$`, `ByteBuddy$`, `$$FastClassBy`, `_$$_jvst`.
3. `java.*`, `javax.*`(단 `javax.servlet.*`·`javax.sql.*`는 허용), `jdk.*`, `sun.*`, `com.sun.*`, `jakarta.*`(단 `jakarta.servlet.*` 허용) → **단, JDK 허용목록 명시 클래스는 ignore에서 제외**: `java.net.HttpURLConnection` 하위(`sun.net.www.protocol.http.HttpURLConnection`, `sun.net.www.protocol.https.*`), `java.util.concurrent.ThreadPoolExecutor`, `ScheduledThreadPoolExecutor`, `ForkJoinPool`, `ForkJoinTask`, `FutureTask`, `CompletableFuture` 및 내부 클래스(`$AsyncSupply`, `$AsyncRun`, `$Uni*`), `java.util.concurrent.ThreadPerTaskExecutor`, `jdk.internal.net.http.HttpClientImpl`.
4. 다른 에이전트·툴: `org.jacoco.*`, `io.opentelemetry.javaagent.*`, `net.bytebuddy.*`(앱 것), `org.groovy.*`, `org.codehaus.groovy.*`, `kotlin.reflect.jvm.internal.*`.
5. 로깅 프레임워크 내부(logging 모듈 대상 클래스 제외): `ch.qos.logback.*`, `org.apache.logging.log4j.*`, `org.slf4j.*`.

TypePool: 클래스로더별 `WeakConcurrentMap<ClassLoader, TypePool>`(`PoolStrategyCache`), `ClassFileLocator`는 해당 CL 리소스 + bootstrap. 기동 후 5분 미사용 캐시는 비운다(메모리 예산 16MB).

## 5. 계측 프레임워크

### 5.1 인터페이스

```java
package io.argos.agent.instrumentation.api;

public interface InstrumentationModule {
  String id();                     // "servlet", "jdbc", "httpclient-okhttp" … 설정 키 instrumentation.disabled 값과 일치
  int moduleId();                  // 0..63, ModuleHealth 인덱스
  ElementMatcher<ClassLoader> witness();        // 예: hasClassesNamed("javax.servlet.http.HttpServlet"). 리소스 존재 확인만, 클래스 로딩 없음
  List<TypeInstrumentation> types();
  List<String> helperClassNames(); // 대상 CL에 주입할 helper. 순서 = 의존 순서
  String[] libraryVersionRange();  // README용 "javax.servlet 3.1~4.0, jakarta.servlet 5~6.1"
}

public interface TypeInstrumentation {
  ElementMatcher<TypeDescription> typeMatcher();          // SafeMatchers만 사용
  void transform(TypeTransformer t);                      // t.advice(methodMatcher, "…ServletServiceAdvice")
  default boolean requiresFieldInjection() { return false; }
  default ContextStoreSpec[] contextStores() { return NONE; }   // (keyType, valueType) → FieldInjector
}
```

`SafeMatchers`: `safeHasSuperType(named(...))`, `safeImplements(named(...))`는 TypePool 해석 실패(`ClassCircularityError`, 미해석 타입)를 잡아 false를 반환하고 `TransformErrorListener`에 센다. `named()`·`nameStartsWith()`만 쓰는 매처를 우선하고 서브타입 매칭은 jdbc·executor·servlet(Filter)에만 허용한다.

### 5.2 등록·활성화 순서

1. `ModuleRegistry.ALL`(정적 목록, 순서 고정) → `instrumentation.disabled`에 있으면 `DISABLED_BY_CONFIG`.
2. 클래스로더마다 첫 변환 요청 시 `witness()` 평가(결과를 CL별 약참조 맵에 캐시). 실패하면 그 CL에서는 매칭하지 않는다.
3. 매칭된 타입에 helper 주입(CL별 1회, `HelperInjector`), `ContextStoreSpec`이 있으면 대상 타입에 필드·접근자 인터페이스 추가(아직 로드되지 않은 타입만. 이미 로드된 JDK 타입은 `disableClassFormatChanges` 경로라 폴백 맵).
4. Advice 적용. `Listener.onTransformation`으로 변환 목록 기록(베이스라인 diff, N-27).
5. 런타임 상태: `ENABLED` / `DISABLED_BY_CONFIG` / `DISABLED_BY_ERRORS`(Advice 예외 ≥ `instrumentation.error-threshold`=100 또는 transform 오류 ≥ 10) / `DISABLED_BY_REMOTE`. 비활성은 `ModuleHealth.enabled()`가 false가 되어 Advice 첫 줄 `Argos.skip(moduleId)`에서 즉시 return(변환은 유지). 전이는 WARN 1회 + 자가 진단 + `InstanceUpdate`.

### 5.3 Advice 작성 규칙 (DSN-02-01, ArchUnit)

| # | 규칙 | 검사 |
|---|---|---|
| A-1 | Advice 클래스는 `*Advice`로 끝나고 `static` 메서드만, `@Advice.OnMethodEnter(suppress = Throwable.class)` / `@Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)` 필수 | 애너테이션 검사 |
| A-2 | 첫 문장은 `if (Argos.skip(MODULE_ID)) return;` | 바이트코드 패턴 |
| A-3 | 람다·메서드 참조·`String.format`·스트림·try-with-resources·`synchronized`·`new Throwable()`·리플렉션 조회(`Class.forName`, `getMethod`) 금지. 로직은 helper 또는 bootstrap API에 위임 | invokedynamic·monitorenter·호출 대상 검사 |
| A-4 | `@Advice.OnMethodEnter(skipOn = …)` 금지(원본 본문 건너뛰기 불가) | 애너테이션 |
| A-5 | `@Advice.Return/@Advice.Thrown/@Advice.Argument/@Advice.FieldValue`의 `readOnly=false`는 **allowlist 파일**(`agent-instrumentation/src/main/resources/argos-advice-allowlist.txt`)에 모듈·클래스·메서드·사유가 있을 때만 허용. 허용 목적은 "관찰 데이터 부가(전파 헤더·MDC 키)"뿐이며 앱 인자·반환값·예외의 의미 변경 금지 | allowlist 대조 |
| A-6 | `MethodDelegation`·`Implementation`·`ClassWriter.COMPUTE_FRAMES` 사용 금지 | 참조 검사 |
| A-7 | 생성자 계측은 `OnMethodExit`만 | 매처 검사 |
| A-8 | Advice·helper는 `io.argos.agent.core.*`·`shaded.*`를 참조하지 않는다(B-2·B-4) | 의존성 검사 |
| A-9 | `TraceContext`·`ContextStore` 값에 앱 클래스 인스턴스를 넣지 않는다. 허용 타입: bootstrap 타입, primitive 박싱, String, `long[]`, `WeakReference` | 타입 검사(ContextStoreSpec 값 타입) |

1.0의 allowlist(A-5): `httpclient-jdk11`(`HttpClientImpl.send/sendAsync` `@Argument(0, readOnly=false)` → 헤더 추가 래퍼), `logging/logback`(`LoggingEvent.getMDCPropertyMap` `@Return(readOnly=false)` → 키 추가 맵), `logging/log4j1`(`LoggingEvent.<init>` `@FieldValue("mdcCopy", readOnly=false)`), `httpclient-reactornetty`(5.5절, 필요 시). 추가는 PM 승인.

### 5.4 helper 주입

helper는 대상 라이브러리 타입(`HttpServletRequest` 등)을 직접 다루는 클래스다. `HelperInjector`가 CL별 1회 `defineClass`하며, helper는 `bootstrap.*`만 참조한다. helper 안에서 `Tracer`를 호출해 core로 넘긴다. helper에 상태를 두지 않는다(정적 필드 금지, CL마다 사본이 생긴다).

## 6. 계측 모듈 사양

공통: 모든 Advice 첫 줄 `Argos.skip(MODULE_ID)`. 시간은 `System.nanoTime()`. 예외는 억제 후 `Tracer.moduleError()`.

### 6.1 servlet (F-A01, F-A28, F-A23, D-04, D-21, D-26)

| 항목 | 내용 |
|---|---|
| 대상 | `javax.servlet.http.HttpServlet.service(ServletRequest, ServletResponse)` / `jakarta.…`; `safeImplements(named("javax.servlet.Filter")).and(not(isInterface()))`의 `doFilter(ServletRequest, ServletResponse, FilterChain)` / `jakarta.…`. 두 네임스페이스는 helper 두 벌(`ServletHelperJavax`, `ServletHelperJakarta`), Advice 공통 로직은 helper에 |
| witness | `hasClassesNamed("javax.servlet.http.HttpServlet")` 또는 jakarta |
| enter | `ctx = request.getAttribute("argos.tx")`; 없고 `getDispatcherType()==REQUEST`이면: `IgnoreRules.test(method, path, extension, userAgent)` → 제외면 `countIgnored(reason)` + 속성 `argos.ignored`=TRUE 후 return; 아니면 `ctx = tracer.begin(HTTP, method+" "+path, now)`, 속성 저장, `setHttp(method, path(쿼리스트링 제거·모드), remoteIp(모드·trusted-proxies), userId(모드·source))`, 헤더 화이트리스트 수집, `dispatchDepth=1`. 있으면 `dispatchDepth++` + `activate(ctx)`(스레드가 다를 수 있음: ASYNC 디스패치) |
| exit | `dispatchDepth--`; 0이고 `!request.isAsyncStarted()`이면: 상태 = `response.getStatus()`; `@Thrown`이 있고 status<400이면 500(D-21); 서비스 이름 미확정이면 `ServiceNameResolver.resolve(request)`(6.2 → `HttpServletMapping.getPattern()`(Servlet 4+) → 폴백); `tracer.end(...)`, 속성 제거. `isAsyncStarted()`면 servletasync 모듈에 넘긴다(6.10). ERROR·FORWARD·INCLUDE 디스패치 종료는 `dispatchDepth`만 줄인다 |
| 금지 | `getParameter*`, `getReader`, `getInputStream`, `getSession(true)`, `getSession()` 호출 금지(D-26). user-id source가 session이면 `getSession(false)`가 null이 아닐 때만 |
| 수집 항목 | method, path(≤2KB), status, elapsed, remote_ip, user_id, 헤더(화이트리스트, 값 ≤1KB), dispatch 횟수, streaming·timeout 플래그 |
| 설정 | `instrumentation.ignore-paths/-extensions/-user-agents/-methods`, `capture.query-string`, `capture.remote-ip`, `capture.trusted-proxies`, `capture.user-id.*`, `capture.http-headers`, `transaction.max-duration-ms` |
| 버전 | javax.servlet 3.1·4.0, jakarta.servlet 5·6·6.1. Tomcat 9/10.1/11, Jetty ee8/ee10, Undertow 2.3 |
| 테스트 | 디스패치 5종 × 1트랜잭션, `/error` 서비스 이름 없음, 상태 최종값, 제외 규칙·카운터, 헤더 화이트리스트, 쿼리스트링 제거, IP 3모드, user_id 3모드, XFF 스푸핑, `getParameter` 비호출(Mock 요청 단언), 잔존 컨텍스트 정리 |

### 6.2 spring-webmvc (F-A02, F-A29, F-A36)

| 항목 | 내용 |
|---|---|
| 대상 | `safeImplements(named("org.springframework.web.servlet.HandlerAdapter"))`의 `handle(HttpServletRequest, HttpServletResponse, Object)` enter |
| 동작 | `pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE)`("org.springframework.web.servlet.HandlerMapping.bestMatchingPattern"); 있으면 `setServiceName(ctx, method+" "+contextPath접두?+pattern, fix=true)`; 없고 handler가 `HandlerMethod`면 `Controller#method`로 fix=false(종료 시 폴백 재시도). `@RequestMapping`에 메서드 제한이 없어도 실제 요청 메서드를 쓴다. 멀티 웹앱이면 `contextPath`를 접두(`/shop GET /api/orders/{id}`, F-A36) |
| 폴백(F-A29) | 패턴 없음(404·필터 거부·정적): `METHOD + path`에서 세그먼트가 숫자·UUID·16진 32자 이상이면 `{*}`. 앱당 서비스 사전 10,000 상한, 초과는 `_other_` + `service_dictionary_overflow_total` |
| 버전 | Framework 5.3·6.x·7.x. 7.x에서 속성 유지 여부 착수 시 확인 |
| 테스트 | 패턴 서비스명, 메서드 제한 없는 매핑, 404 폴백 정규화, 사전 상한, 컨텍스트 경로 접두, FORWARD 후 이름 불변 |

### 6.3 jdbc (F-A03, F-A04, D-22, D-24, SEC-35, SEC-3A)

| 항목 | 내용 |
|---|---|
| 대상 | `safeImplements(named("javax.sql.DataSource"))`.`getConnection()`/`(String,String)`; `safeImplements(named("java.sql.Connection"))`.`prepareStatement(String,…)`·`prepareCall(String,…)`·`commit()`·`rollback()`; `safeImplements(named("java.sql.Statement"))`.`execute*(String…)`·`executeBatch()`·`addBatch(String)`; `safeImplements(named("java.sql.PreparedStatement"))`.`execute()`·`executeQuery()`·`executeUpdate()`·`executeLargeUpdate()`·`addBatch()`·`clearParameters()`·`set*(int, …)`; `safeImplements(named("java.sql.ResultSet"))`.`next()`·`close()`. 제외: `nameStartsWith("com.sun.proxy")`, `nameStartsWith("jdk.proxy")`(JDK Proxy), `io.argos.` |
| ContextStore | `Connection→DbInfo(dbId)`, `Statement→SqlInfo(rawSqlRef, sqlId, dbId, params, batchCount, execStartNanos, seq)`, `ResultSet→FetchInfo(rows, firstNextNanos)` |
| getConnection | `CallDepth.enter(JDBC_DATASOURCE)==1`일 때만 DBC step(name = 풀 이름 사전 id, `PoolRegistry.nameOf(dataSource)` 없으면 클래스명). exit에서 Connection에 `DbInfo` 지연 설정(아래) |
| DbInfo | 최초 `prepareStatement`/`execute` 시 `connection.getMetaData().getURL()`을 **1회** 호출(CallDepth 가드, 예외 시 dbId=0) → `JdbcUrlParser`(벤더·호스트·포트·DB명; Oracle thin/TNS, MySQL/MariaDB, PostgreSQL, SQL Server, Tibero, Altibase, CUBRID, DB2, H2) → `dbId = dict(HOST, "<vendor>://<host>:<port>/<db>")` |
| prepareStatement | exit: `SqlInfo.raw = sql`(참조만, 정규화 지연) |
| execute* | `CallDepth.enter(JDBC_STATEMENT)==1`일 때만(최외곽, D-24): `sqlId = SqlNormalizer.idOf(raw 또는 인자 sql)`(LRU 캐시 raw→(sqlId, normalized) 10,000), `seq = stepStart(SQL, sqlId, dbId)`; exit: `stepEnd(seq, status, error, rows=0)`, `recordSqlStat(sqlId, dbId, elapsed, error, rows)`(전수, 컨텍스트 없어도 호출? → 컨텍스트 없으면 `countOrphan`만, 통계는 **트랜잭션 내 SQL만**(D-20). 1.x에 백그라운드 SQL 통계), 파라미터 모드≠masked면 `stepDetail(마스킹된 파라미터 문자열)`. executeBatch는 `batchCount` detail |
| ResultSet | `next()` enter: `FetchInfo.rows++`(ThreadLocal 접근 없음, 주입 필드 int 증가), 첫 호출 시각 기록; `close()` 또는 같은 Statement의 다음 execute에서 `fetch_ms`·`rows`를 직전 SQL step에 합산(`stepEnd`의 rows 갱신 API: `tracer.stepFetch(ctx, seq, rows, fetchNanos)` — 11 문서에 `fetch_ms` 필드 요구) |
| commit/rollback | `CallDepth.enter(JDBC_CONNECTION)==1`일 때 COMMIT/ROLLBACK step(name = dbId 호스트) |
| 바인드 파라미터 | `set*(int idx, …)` Advice는 항상 설치, `capture().sqlParamMode==MASKED`면 즉시 return. 허용 타입: `String`, `Number`, `Boolean`, `java.util.Date`·`java.sql.Date/Time/Timestamp`, `java.time.*`, `UUID`, `byte[]`(길이만). 그 외(`InputStream`·`Reader`·`Blob`·`Clob`·`Array`·엔티티)는 `<type>`. 임의 `toString()` 금지. 값 ≤256자, 인덱스 ≤64, 배치는 첫 세트만. `partial`: 컬럼 화이트리스트(정규화 SQL의 `col = ?` 패턴에서 추출 가능할 때) 외는 마스킹 + `Masker` 정규식. `full`: 그대로(서버가 `pii.view` 권한으로만 노출) |
| 설정 | `capture.sql-parameters`, `capture.sql-parameters.whitelist-columns`, `capture.mask-patterns`, `sql.max-length`(65536) |
| 버전 | JDBC 4.0~4.3. 드라이버: MySQL 8, MariaDB 3, PostgreSQL 42, MSSQL 12, Oracle 19/21/23, Tibero 6/7, Altibase 7, CUBRID 10/11, DB2 11, H2(테스트) |
| 테스트 | Hikari+드라이버별 SQL 1회 = step 1, `sql_count` 1; 라우팅 DataSource 중첩 `getConnection` DBC 1개; 정규화 50케이스(D-22 + PII 시드); 실패 안전(`<unparsed>`); 파라미터 3모드·타입 화이트리스트·LOB 비접근; fetch 분리; 트랜잭션 밖 SQL 고아 카운터 |

### 6.4 pool · tomcat (F-A10)

| 모듈 | 대상 | 등록·수집 |
|---|---|---|
| pool/hikari | `com.zaxxer.hikari.pool.HikariPool.<init>` exit | `PoolRegistry.register(poolName=config.getPoolName(), adapter)`; 5초마다 `getActiveConnections/getIdleConnections/getThreadsAwaitingConnection/getTotalConnections`, `config.getMaximumPoolSize()` |
| pool/tomcatjdbc | `org.apache.tomcat.jdbc.pool.ConnectionPool.<init>` exit | `getActive/getIdle/getWaitCount/getSize`, `getPoolProperties().getMaxActive()` |
| pool/dbcp2 | `org.apache.commons.dbcp2.BasicDataSource.createDataSource()` exit(풀 생성 시점) | `getNumActive/getNumIdle/getMaxTotal`, waiting은 결측 |
| tomcat | `org.apache.tomcat.util.net.AbstractEndpoint.start()` exit | `TomcatRegistry.register(endpoint)`; `getCurrentThreadsBusy/getCurrentThreadCount/getMaxThreads/getConnectionCount/getMaxConnections`, 커넥터 이름 `getName()`. 가상 스레드 실행기면 `maxThreads` 결측 |
| 공통 | 약참조 레지스트리(GC되면 자동 제거), 풀 이름 없으면 `<클래스 단순명>-<순번>`, 풀당 시리즈 4개, JMX 비사용. DataSource→풀 이름 역매핑(`PoolRegistry.nameOf`)은 `HikariDataSource.getPool()`·Tomcat `DataSourceProxy.getPool()`로 |
| 테스트 | Spring Boot 기본(MBean off)에서 수집, 풀 2개 이름 구분, 재기동 풀 재등록 |

### 6.5 httpclient-* (F-A05, F-A06, F-A32, F-A33)

공통 helper 흐름: `host = URL 호스트`; `shouldPropagate(host)`면 `traceparent`(기존 헤더 있으면 **보존**, F-A32)·`tracestate`(Argos 엔트리는 `propagation.argos-state.hosts` 매칭 시만) 주입; `seq = stepStart(HTTP, dict(HOST, host), 0)`; exit에서 상태·실패 분류(`SocketTimeoutException`·`HttpTimeoutException`→TIMEOUT, `ConnectException`·`UnknownHostException`·`NoRouteToHost`→CONNECT_FAIL, status≥500→HTTP5XX, 그 외 예외→OTHER) → `stepEnd` + `stepDetail("METHOD host/path")`(쿼리 제거) + `recordExtStat`.

| 모듈 | 대상 | 비고 |
|---|---|---|
| jdk | `safeHasSuperType(named("java.net.HttpURLConnection"))`(실제 `sun.net.www.protocol.http(s).HttpURLConnection`)의 `connect()`, `getOutputStream()`, `getInputStream()`, `getResponseCode()`, `getErrorStream()`. `ContextStore<HttpURLConnection, HttpUrlState>` 상태 머신(미시작→헤더 주입+step 시작(최초 호출 시 `connected`가 false일 때 `setRequestProperty`)→응답 수신(`getResponseCode` 또는 `getInputStream` 성공/예외)→종료 1회) | JDK 클래스: read edge, 이미 로드됐을 수 있어 폴백 맵. `CallDepth(HTTP_CLIENT)` |
| apache4 | `org.apache.http.impl.client.CloseableHttpClient.doExecute(HttpHost, HttpRequest, HttpContext)` | 단일 관문. `HttpRequest.setHeader`. 4.5.x |
| apache5 | `org.apache.hc.client5.http.impl.classic.CloseableHttpClient.doExecute(HttpHost, ClassicHttpRequest, HttpContext)` | 동기만. async는 1.x |
| okhttp | `okhttp3.OkHttpClient$Builder.build()` enter → `builder.interceptors().add(0, new ArgosOkHttpInterceptor())`(helper, `okhttp3.Interceptor` 구현). 인터셉터 안에서 헤더 주입·step·분류. 이미 추가됐으면 중복 방지 | OkHttp 3.x·4.x. `enqueue`는 콜백 스레드가 다르므로 인터셉터 실행 스레드의 컨텍스트는 호출 스레드가 아닐 수 있다 → 인터셉터 생성 시점이 아니라 `Call` 생성 시(`RealCall.<init>` exit)에 `ContextStore<Call, TraceContext>`로 컨텍스트를 붙이고 인터셉터는 `chain.call()`에서 꺼낸다 |
| jdk11 | `jdk.internal.net.http.HttpClientImpl.send(HttpRequest, BodyHandler)`·`sendAsync(HttpRequest, BodyHandler, PushPromiseHandler)` enter: `@Argument(0, readOnly=false)`(allowlist)로 `HttpRequest`를 헤더 추가 래퍼(`ArgosHttpRequestWrapper extends HttpRequest`, 원본 위임 + headers 합성)로 교체. 응답은 `send` exit / `sendAsync`는 반환 `CompletableFuture`에 `whenComplete`를 걸지 않고(반환값 변경 금지) `ContextStore<HttpRequest, seq>`를 두고 `jdk.internal.net.http.MultiExchange.responseAsync…` 완료 지점 계측(착수 시 확인) | `java.net.http` 모듈 read edge. 유일한 인자 교체 예외 |
| reactornetty | 범위는 F-A33: Servlet MVC 안의 WebClient. (1) `reactor.netty.http.client.HttpClientConnect$MonoHttpConnect.subscribe(CoreSubscriber)` enter: `ctx = tracer.current()`(호출 스레드 = `block()/toFuture()/subscribe()` 호출자) → 이 subscribe가 만드는 `HttpClientHandler`에 `ContextStore<HttpClientHandler, TraceContext>`로 붙인다(생성 지점 `HttpClientHandler.<init>` exit에서 ThreadLocal 읽기 — subscribe와 같은 스레드). (2) `HttpClientHandler.requestWithBody(HttpClientOperations)` enter(이벤트 루프): store에서 ctx → `ops.requestHeaders().set(...)` 주입, `seq = stepStart`(ctx에 `activate` 없이 직접 ctx 전달). (3) `HttpClientConnect$HttpObserver.onStateChange(Connection, State)`: `RESPONSE_RECEIVED`→status, `onUncaughtException`→분류 → `stepEnd`. WebFlux 서버 앱은 서블릿 트랜잭션이 없어 ctx=null → 아무것도 하지 않는다(메트릭만) | reactor-netty 1.0~1.2 내부 클래스. witness로 메서드 존재 확인, 없으면 모듈 비활성 + WARN. 착수 시 소스 확인 필수 |
| 공통 테스트 | 클라이언트 6종 각각 step 1개·헤더 주입·기존 traceparent 보존·exclude-hosts 미주입·실패 분류 4종·`recordExtStat` 호출; shop-boot3→shop-boot3 trace 결합 |

### 6.6 executor (F-A07, DSN-02-05)

| 항목 | 내용 |
|---|---|
| 제출 측 대상(허용목록) | `java.util.concurrent.ThreadPoolExecutor`(`execute`), `ScheduledThreadPoolExecutor`(`schedule*`, `execute`), `ForkJoinPool`(`execute/submit/invoke`), `ForkJoinTask.fork()`, `ThreadPerTaskExecutor`(`execute/submit`, JDK 21+), `java.util.concurrent.AbstractExecutorService.submit(Runnable|Callable)`(위 클래스들에 한해), Spring `org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor`·`SimpleAsyncTaskExecutor`(`execute/submit/submitListenable`), Tomcat `org.apache.tomcat.util.threads.ThreadPoolExecutor.execute` |
| 제출 동작 | `ctx = tracer.current()`; ctx≠null이고 task가 `ArgosThread` 소유가 아니면 `ContextStore<Runnable|Callable|ForkJoinTask, TraceContext>.put(task, ctx)`. **래핑 없음.** Netty `EventExecutor`, Reactor `Scheduler`, 에이전트 스레드는 ignore 목록·`Argos.skip()`으로 제외 |
| 실행 측 대상 | `safeImplements(named("java.lang.Runnable")).and(not(isInterface()))`.`run()`, `safeImplements(named("java.util.concurrent.Callable"))`.`call()`, `java.util.concurrent.ForkJoinTask.exec()`, `java.util.concurrent.FutureTask.run()`. ignore 목록이 JDK 내부·프레임워크 내부 Runnable을 걸러 변환 수를 제한한다(베이스라인으로 감시) |
| 실행 동작 | enter: `ctx = store.remove(this)`; ctx≠null → `scope = activate(ctx)`; `stepStart(ASYNC, dict(CLASS, 클래스명), 0)`는 `async.step=true`(기본 false — 비용)일 때만. exit: `scope.close()`. CPU·alloc은 스레드가 바뀌었으므로 결측(D-23) |
| CompletableFuture | `supplyAsync/runAsync/thenApplyAsync/thenAcceptAsync/thenRunAsync/thenComposeAsync/whenCompleteAsync/handleAsync` enter: 사용자 함수 객체(`$$Lambda`, 필드 주입 불가 → 약참조 맵)에 ctx 저장; `CompletableFuture$AsyncSupply/AsyncRun/Uni*.<init>` exit: 내부 `fn`에서 ctx를 꺼내 `this`로 이전; 각 `run()/tryFire()` enter/exit에서 복원·해제. 1.0 범위: `*Async` 계열. 동기 `thenApply`는 호출 스레드라 전파 불필요 |
| 필드 주입 | 아직 로드되지 않은 앱 Runnable 구현체: `argos$ctx` 필드 + 접근자 인터페이스 주입. JDK 타입(`FutureTask` 등)·이미 로드된 타입: `WeakConcurrentMap`(weak-lock-free, 셰이딩) 폴백 |
| 테스트 | 허용목록 밖 Executor 미변환(베이스라인), `remove()`·`ScheduledFuture.cancel()` 보존, 풀 스레드 재사용 시 오염 0, `commonPool` 병렬 스트림, `ThreadPerTaskExecutor`, 1만 작업 후 폴백 맵 GC 후 0 |

### 6.7 servletasync (F-A07, F-A34)

| 항목 | 내용 |
|---|---|
| 대상 | `ServletRequest.startAsync()`/`(ServletRequest, ServletResponse)` exit → `asyncContext.addListener(new ArgosAsyncListener(ctx))`(helper, javax/jakarta 두 벌), `ctx.asyncStarted=true`; `AsyncContext.start(Runnable)` enter → `ContextStore<Runnable, TraceContext>.put`; `AsyncContext.dispatch*()`는 ASYNC 디스패치로 servlet 모듈이 참여 |
| 종료 | `onComplete/onTimeout/onError` 중 먼저 오는 것에서 상태 결정 후 `tracer.end()`(한 번만, `ended` CAS). `DeferredResult`·`Callable` 반환은 Spring이 startAsync를 쓰므로 자동 커버 |
| 스트리밍(F-A34) | 응답 101(`response.getStatus()==101` 또는 `Upgrade` 헤더)이면 즉시 종료 + `streaming` 플래그. `SseEmitter`·`StreamingResponseBody`·`ResponseBodyEmitter`: startAsync 후 첫 `response.flushBuffer()`/`getOutputStream().flush()`(servlet 모듈이 `ServletResponse.flushBuffer` 계측) 또는 `onTimeout/onComplete` 중 먼저 → 종료, `streaming=true` |
| 상한 | `SlowStackWatcher`가 `transaction.max-duration-ms`(3,600,000) 초과 트랜잭션을 `timeout=true`로 강제 종료하고 레지스트리에서 제거 |
| 테스트 | `DeferredResult`·`Callable`·`SseEmitter`·`StreamingResponseBody`·WebSocket 업그레이드 각각 트랜잭션 1건·수명 규칙, 타임아웃 시 상태 |

### 6.8 scheduler (F-A24, D-20)

| 대상 | 트랜잭션 |
|---|---|
| Spring `org.springframework.scheduling.support.ScheduledMethodRunnable.run()` | `begin(BATCH, "BATCH " + target.getClass().getName() + "#" + method.getName())`, exit에서 `end`. 서비스 이름 fix |
| Quartz `safeImplements(named("org.quartz.Job"))`.`execute(JobExecutionContext)` | `"BATCH " + jobDetail.getKey()`(group.name) |
| Spring Batch `org.springframework.batch.core.step.AbstractStep.execute(StepExecution)` | `"BATCH " + jobName + "." + stepName`. 청크 반복으로 step 상한(1,000) 초과 시 `StepBuffer`가 **요약 모드**로 전환: name_id별 (count, sum) 집계 step 1개씩만 유지 |
| 설정 루트(`instrumentation.custom-methods[].as-transaction: true`, C) | `"BATCH <class#method>"` |
| 공통 | 이미 컨텍스트가 있으면(HTTP 안에서 Job 수동 실행) 새 트랜잭션을 만들지 않고 METHOD step. kind=BATCH는 Apdex·HTTP 통계에서 제외(서버, D-20). 배치 안의 SQL·외부 호출 통계는 `kind` 태그와 함께 전송(11 문서 StatRecord.kind) |
| 테스트 | `@Scheduled` 1회 = 트랜잭션 1건 kind=BATCH, Quartz 이름, Spring Batch 청크 5,000 → 요약 모드, HTTP 안 수동 실행은 step |

### 6.9 logging (F-A20, SEC-26)

| 프레임워크 | 대상 | 방식 |
|---|---|---|
| Logback | `ch.qos.logback.classic.spi.LoggingEvent.getMDCPropertyMap()` exit | `@Return(readOnly=false)`(allowlist): 반환 맵에 `argos.trace_id`(32 hex)·`argos.tx_id`(16 hex)가 없으면 두 키를 추가한 새 `Map`(원본 + 2)으로 교체. 컨텍스트 없으면 그대로. `LoggingEvent.getMdc()`(구버전) 동일 |
| Log4j2 | `org.apache.logging.log4j.core.impl.ThreadContextDataInjector$ForDefaultThreadContextMap.injectContextData(List, StringMap)` 및 `ForGarbageFreeThreadContextMap`·`ForCopyOnWriteThreadContextMap` 동명 메서드 exit | 인자 `StringMap`은 가변: `putValue("argos.trace_id", …)`. 반환값 변경 없음 |
| Log4j 1.x / reload4j | `org.apache.log4j.spi.LoggingEvent.<init>(…)` exit | `getMDCCopy()` 호출 후 `@FieldValue("mdcCopy", readOnly=false)`(allowlist)에 두 키를 추가한 `Hashtable` 설정 |
| 공통 | 앱이 `MDC.clear()`를 해도 영향 없음(이벤트 생성 시점 주입). 가상·비동기 스레드도 활성 컨텍스트 기준. `CallDepth(LOGGING)`로 재진입 방지. 키 이름은 공개 계약 |
| 테스트 | 3 프레임워크 × 패턴 `%X{argos.trace_id}` 출력 단언, `MDC.clear()` 후에도 출력, 비동기 스레드 로그, 컨텍스트 없을 때 키 없음 |

### 6.10 custom (F-A14, SEC-25)

| 항목 | 내용 |
|---|---|
| 설정 | `instrumentation.custom-methods[]`: `pattern: "com.example.service.*Service#*"`, `step-type: METHOD`, `as-transaction: false`. 글롭(`*`, `**`, `?`) |
| 매처 | 패턴을 `nameMatches(regex)`로 변환. **금지 접두사** `java.`·`javax.`·`jakarta.`·`jdk.`·`sun.`·`com.sun.`·`io.argos.`는 패턴이 포함해도 매칭에서 제외(에이전트 고정, 원격·로컬 모두 우회 불가). 생성 클래스 패턴은 ignore 목록이 제외. 패턴당 매칭 클래스 500 초과 시 패턴 비활성 + WARN |
| 동작 | enter: ctx 없으면 `countOrphan(METHOD)` 후 return(`as-transaction`이면 BATCH 트랜잭션 시작); `stepStart(METHOD, dict(METHOD, "Class#method"), 0)`; exit: `stepEnd` |
| 동적 추가 | `remote-config.allow`에 `instrumentation.custom-methods`가 있을 때만 서버 푸시로 변경. retransform은 명령당 클래스 50개·분당 1회 상한(F-A15), 결과(클래스 수·ms)를 `ConfigApplyResult`에 |
| 테스트 | 금지 접두사 무시·WARN, CGLIB 프록시 이중 step 없음, 상한 초과 비활성, retransform 상한 |

## 7. 요청 경계·서비스 이름·제외 규칙

### 7.1 요청 경계 상태 머신 (F-A28)

| 상태 | 진입 | 전이 |
|---|---|---|
| NONE | 요청 도착 | `REQUEST` 디스패치 + 제외 아님 → ACTIVE(dispatchDepth=1). 제외 → IGNORED(속성 `argos.ignored`, 이후 디스패치 모두 무시). REQUEST가 아닌데 속성 없음(필터 체인 밖에서 들어온 ERROR) → 무시 + 카운터 `transactions_unanchored_total` |
| ACTIVE | 필터·서블릿 중첩, FORWARD·INCLUDE·ERROR·ASYNC 디스패치 | 각 enter에서 depth++, exit에서 depth--. depth==0 & !asyncStarted → ENDING. `startAsync` → ASYNC_PENDING |
| ASYNC_PENDING | 요청 스레드 반환 | `AsyncListener.onComplete/onTimeout/onError` → ENDING. `dispatch()` → ACTIVE(ASYNC 디스패치 참여) |
| ENDING | 최종 상태 결정 | status = `response.getStatus()`; thrown & status<400 → 500; 서비스 이름 fix(6.2 폴백); `end()`; 속성 제거; ThreadLocal 정리 |
| ENDED | - | 레지스트리 제거. 두 번째 end는 무시(`ended` CAS) |

잔존 컨텍스트: REQUEST 디스패치 enter 시 ThreadLocal에 다른 트랜잭션이 남아 있으면(이전 요청 누수) 그것을 `status=599, error=true, flag=leaked`로 강제 종료하고 `transactions_leaked_total` 카운트 후 진행.

### 7.2 제외 규칙 평가 순서 (F-A23, D-19)

1. HTTP 메서드 ∈ `ignore-methods`(기본 `OPTIONS`) → reason `method`
2. 경로가 `ignore-paths` 글롭(기본 `/actuator/health`, `/actuator/health/**`, `/health`, `/healthz`, `/ready`, `/favicon.ico`) → `path`
3. 확장자 ∈ `ignore-extensions`(기본 `css js map png jpg jpeg gif svg ico woff woff2 ttf eot`) → `extension`
4. `User-Agent`가 `ignore-user-agents` 글롭(기본 비어 있음; 예 `kube-probe/*`, `ELB-HealthChecker/*`) → `user_agent`
5. 통과 → 트랜잭션 생성. 제외는 `transactions_ignored_total{reason}`로 5초 메트릭에 포함. "XLog 100%"는 제외 규칙 밖의 100%다.

규칙은 `IgnoreRules`(불변, volatile 교체)로 컴파일되고 동적 설정(`instrumentation.ignore-paths` 등은 기본 allow)으로 바뀐다.

## 8. 컨텍스트 전파

| 경로 | 설계 |
|---|---|
| 스레드 내 | `TracerImpl`의 `ThreadLocal<TraceContext>`(bootstrap `Argos` 경유 접근). 요청 속성이 진실, ThreadLocal은 캐시. `Scope.close()`가 이전 값을 복원한다 |
| 서블릿 비동기 | 6.7 |
| Executor·CompletableFuture | 6.6. 필드 주입 → 폴백 약참조 맵 |
| 가상 스레드(F-A30) | `VirtualThreads.isVirtual(Thread)`: 기동 시 `MethodHandles.lookup().findVirtual(Thread.class, "isVirtual", …)` 1회 조회·캐시(JDK 8 타겟). 가상이면 `cpuStartNanos=allocStartBytes=-1`(결측, D-23). `ActiveRegistry`·`SlowStackWatcher`는 스레드가 아니라 `TraceContext`를 키로 하고 `WeakReference<Thread>`만 보관. 스레드 이름은 `ThreadNames.normalize()`: 끝의 `-\d+`·UUID·16진 8자 이상 제거(`tomcat-handler-1234` → `tomcat-handler-`), 사전 상한 1,000 초과는 `_other_`. `Thread.startVirtualThread`·`Thread.ofVirtual()` 직접 생성은 전파하지 않는다(문서화) |
| 분산(F-A06, F-A32) | 들어오는 `traceparent`가 유효하면 `traceId`를 채택하고 `parentSpanId`=수신 span, 새 `spanId`. 없으면 `TraceIds.newTraceId()`(상위 4B Unix 초 + 12B 랜덤). 나가는 요청: 이미 `traceparent`가 있으면 **보존**하고 그 값을 step detail에 기록; 없으면 `00-<traceId>-<childSpanId>-01`. `tracestate`: 들어온 값 보존 + `argos=<app_id>:<instance_id>:<service_id>` 엔트리는 `propagation.argos-state.hosts` 매칭 호스트에만(기본 비어 있음 = 미주입). `propagation.exclude-hosts` 글롭에 맞으면 둘 다 미주입. 호출자 식별은 서버가 `tracestate`에서 읽는다(12 문서) |
| WebClient | 6.5 reactornetty. WebFlux 서버·코루틴은 비지원(메트릭만) |
| 보관 타입(A-9) | `TraceContext`·store 값은 bootstrap 타입·primitive·String·`long[]`·`WeakReference`만. `HttpServletRequest`·`Connection`·앱 객체 참조 금지(독립 Tomcat 재배포 누수 방지, F-A36) |

## 9. 데이터 모델·샘플링·통계·정규화

### 9.1 core 측 Transaction / StepBuffer

| 구조 | 필드 |
|---|---|
| `Transaction`(core, `TraceContext.coreRef`) | ctx 참조, `kind`, `env`(설정), `versionId`, `serviceId`(종료 시 dict), `httpMethod`, `path`, `remoteIp`(모드 적용 후), `userId`(모드 적용 후), `headers[]`, `errorClassId`, `errorMessage`(마스킹·1KB), `topAppFrameId`(`instrumentation.app-packages` 첫 매칭 프레임, 지문용), `steps: StepBuffer`, `stackCaptures`, `flags(streaming, timeout, leaked, profileCaptured)`, `cpuNanos`, `allocBytes`(-1 결측) |
| `StepBuffer` | 구조체-배열(SoA): `int[] type, parentSeq, status; long[] nameId, startOffsetNanos, elapsedNanos, rows; int[] targetId; byte[] errorFlag; String[] detail(지연 생성); long[][] stack`. 초기 64, 2배 성장, 상한 `sampling.profile.max-steps`(1,000). 상한 도달 시 **요약 모드**: 이후 step은 `(type, nameId)`별 `SummaryStep(count, sumNanos, maxNanos)`로만 집계하고 `summarized=true` 플래그 |
| 부모·자식 | `parentSeq`는 현재 열린 step 스택(`int[] openStack`)의 top. 비동기 복원 시 스택은 비어 있으므로 ASYNC step 아래에 붙는다 |

### 9.2 종료 시 결정

1. `elapsed = endNanos - startNanos`, `cpu`·`alloc` 차분(결측이면 -1).
2. `TransactionRecord`(XLog 1점) 생성 → `TransactionQueue.offer`. 항상.
3. 프로파일 전송 여부(`Sampler`): `error` → 전송; `elapsedMs ≥ slowThreshold(service)` → 전송; 그 외 `ThreadLocalRandom < normalRate`(기본 0.1) → 전송. 전송이면 `profileCaptured=true`, `ProfileRecord(steps, stacks)` → `ProfileQueue.offer`(실패 시 `profileCaptured`는 true로 남기고 `profile_dropped_total`, 서버가 D-07 "유실"로 표시).
4. 통계는 step 종료 시점에 이미 `StatsAggregator`에 반영됨(샘플링과 무관, D-12).
5. `ActiveRegistry.remove(ctx)`, ThreadLocal·요청 속성 정리.

### 9.3 전수 통계 집계기 (F-A22, D-12)

| 항목 | 설계 |
|---|---|
| 창 | 10초(`stats.window-seconds`), 벽시계 기준 정렬(`ts = floor(epoch/10)*10`) |
| SQL 창 | `ConcurrentHashMap<Long key=(sqlId*31+dbId), long[6]{count, errors, sumNanos, maxNanos, rows, kindBits}>` — CAS 없이 `AtomicLongArray` 대신 striped: 키별 `long[]`에 `synchronized`는 Advice 경로가 아니므로 허용(stat 기록은 Advice가 아니라 core `TracerImpl.recordSqlStat`에서 호출되지만 앱 스레드다 → 락 경합 회피를 위해 `LongAdder` 5개 묶음 사용). 상한 `stats.max-keys`(5,000). 초과 키는 `<overflow>` 키(sqlId=0)에 합산 + `stats_overflow_total` |
| EXT 창 | 키 `hostId`, 값 `{count, errors, sumNanos, maxNanos, timeouts, connectFails}` |
| 플러시 | `argos-metrics` 스레드가 창 경계 +1초에 스냅샷·교체(새 맵) → `StatsBatch{window_ts, kind, sql[], ext[]}` → `StatsQueue` |
| kind 분리 | HTTP/BATCH 트랜잭션의 통계는 키에 kind를 포함해 서버가 분리 집계(D-20) |
| 메모리 | 5,000키 × 2창 × ~120B ≈ 1.2MB |

### 9.4 SQL 정규화기 (D-22, SEC-35)

| 항목 | 규칙 |
|---|---|
| 입력 | 원문 ≤ `sql.max-length`(65,536자, 초과는 절단 + `truncated` 플래그), 1ms 시간 예산(문자 수 상한으로 보장, 정규식 미사용) |
| 토크나이저 상태 | DEFAULT, SQUOTE(`'…'`, `''` 이스케이프, `\'`는 `sql.backslash-escape`(기본 true, MySQL)일 때), DQUOTE 식별자(`"…"`, 보존), BACKTICK 식별자(보존), BRACKET 식별자 `[…]`(보존), LINE_COMMENT(`--`, `#`(MySQL)), BLOCK_COMMENT(`/* */`, Oracle 힌트 `/*+ */`도 제거. `sql.keep-hints=false`), DOLLAR_QUOTE(PostgreSQL `$tag$…$tag$`), NUMBER |
| 치환 | 문자열 리터럴 → `?`; 숫자 리터럴(정수·소수·지수·16진 `0x…`, 앞 부호는 직전 토큰이 연산자·`(`·`,`일 때만 포함) → `?`; `N'…'`·`E'…'`·`X'…'` 접두 리터럴 → `?`; 바인드 마커 `?`, `:name`, `:1`, `$1`, `@p1`, `@name`은 보존; `IN (` 뒤 요소가 모두 `?`이면 `IN (?)`로 축약(VALUES 다중 행 `(?,?),(?,?)` → `(?,?)`); 주석 제거; 공백·개행·탭 연속 → 공백 1개; 식별자 안 숫자(`col1`, `t_2024`) 보존; 대소문자 보존; `{call proc(?)}`·PL/SQL `BEGIN … END;` 블록은 내부 리터럴만 치환 |
| 출력 | `normalized`, `sqlId = Xxh3.hash64(normalized)`, `norm_version = 1`. 캐시 raw→(sqlId, normalized) LRU 10,000(식별자 동일성 키 + 해시) |
| 실패 안전 | 예외·상한 초과·미지원 상태 → `sqlId = dict(SQL, "<unparsed>")`, 원문 미전송, `sql_unparsed_total` |
| 사전 전송 | 새 sqlId는 `DictionaryCache`가 `(SQL, sqlId, normalized, norm_version)`를 세션 스트림으로 선전송(11 문서 `DictionaryEntry`) |
| 테스트 | 50케이스(단순 SELECT, IN 리스트, 다중 VALUES, 바인드 마커 5종, `''`·`\'`·`N'…'`, 주석·힌트, 한글 리터럴, 16진·지수, 달러 쿼팅, MERGE·CTE·배열 리터럴, `{call}`, PL/SQL 블록, 식별자 숫자, MyBatis 공백 변형 동일 id, 64KB 절단, 실패 주입) 전부 리터럴 0건 |

### 9.5 tx_id·trace_id (D-11, DSN-02-09)

`TxIdGenerator`: `((nowMs - EPOCH_2026_01_01) << 23) | (nodeId << 11) | seq`. `nodeId` = `ServerHello.instance_id mod 4096`(Hello 전에는 `SecureRandom` 12비트, 접속 후 교체). `seq`는 같은 ms 안 0~2047, 초과 시 다음 ms까지 스핀(`Thread.onSpinWait` 없음, `LockSupport.parkNanos(1000)`). 시계 역행은 마지막 ms를 유지. `TraceIds.newTraceId()`: 상위 32비트 Unix 초 + 96비트 `SplittableRandom`(스레드별). `newSpanId()`: 64비트 랜덤(0 제외).

## 10. 큐·배치·전송

### 10.1 큐 (A-12)

| 큐 | 타입 | 상한 | 드롭 정책 |
|---|---|---|---|
| TransactionQueue | MPSC 배열 큐(JCTools `MpscArrayQueue`, `atomic` 패키지 변형 — Unsafe 미사용) | `buffer.transaction.capacity`(20,000) **그리고** 오래된 항목 `buffer.max-age-seconds`(120) 초과 시 송신 전 폐기 **그리고** 전체 `buffer.max-bytes`(32MB, 추정 바이트 카운터) | 가득 차면 `poll()`로 가장 오래된 것 1개 버리고 `offer` 재시도 1회. 드롭은 `argos_agent_dropped_total{queue="transaction"}` |
| ProfileQueue | MPSC | `buffer.profile.max-bytes`(16MB) | 동일 |
| StatsQueue | MPSC | 60창(10분) | 동일 |
| MetricQueue | MPSC | 120 스냅샷(10분) | 동일 |
| ActiveQueue | 단일 슬롯(최신만) | 1 | 덮어쓰기 |

앱 스레드는 `offer`만 하고 절대 대기하지 않는다(N-05).

### 10.2 송신 (DSN-02-11)

| 항목 | 설계 |
|---|---|
| 스레드 | `argos-sender` 1개. 루프: 100ms(`sender.flush-interval-ms`) 또는 500건(`sender.batch-size`) 도달 시 `BatchAssembler`가 큐별 배치(proto) 생성 |
| RPC | 배치 단위 unary: `SendTransactions`, `SendProfiles`, `SendStats`, `SendMetrics`, `SendActiveSnapshot`(11 문서). deadline 5초(`server.deadline-ms`). in-flight ≤ `sender.max-in-flight`(4, 세마포어). 상한 도달 시 배치는 큐에 남긴다(큐 드롭 정책이 적용) |
| 멱등 | `batch_seq`: 인스턴스 기동 시 `(startEpochMs << 20)`에서 시작하는 단조 증가 `AtomicLong`(세션 재수립과 무관). `Ack{last_seq}` 미수신 배치만 재전송(최대 1회, 그 뒤 드롭 + 카운터). 서버는 `(instance_id, batch_seq)` 윈도로 중복 제거(12 문서) |
| 압축 | gRPC `gzip`(`sender.compression`). 배치 직렬화 후 4MB 초과 시 반으로 분할 |
| 재접속 | `Backoff` 1s→2s→…→30s + 지터. 접속 전·끊김 중에도 큐는 쌓이고 정책대로 드롭. 세션 끊김 시 in-flight 배치는 실패 처리 |
| 종료 훅 | `Runtime.addShutdownHook(ArgosThread)`: `AgentBye`(deadline 1초) → 큐에 남은 배치를 최대 1초 동안 **훅 스레드에서** 직접 전송(블로킹 호출 없이 deadline으로) → `channel.shutdownNow()`. 앱 훅과 동시 실행되므로 앱 객체에 접근하지 않는다 |

### 10.3 gRPC·Netty 고정 설정 (F-A35, N-26)

| 항목 | 값 |
|---|---|
| 채널 | `NettyChannelBuilder.forTarget(server.address)`, `channelType(NioSocketChannel)`, `eventLoopGroup(new NioEventLoopGroup(1, ArgosThread.factory("argos-io")))`, `executor(directExecutor)`, `maxInboundMessageSize(4MB)`, `keepAliveTime(60s)`, `keepAliveWithoutCalls(true)`, `idleTimeout(Long.MAX)` |
| TLS | `GrpcSslContexts.forClient().sslProvider(SslProvider.JDK)`; `server.tls.ca-file`가 있으면 `trustManager(file)`, 없으면 JDK 기본 신뢰 저장소. 호스트명 검증은 gRPC 기본(HTTPS 엔드포인트 식별) 유지. `insecure-skip-verify: true`면 `InsecureTrustManagerFactory` + 접속마다 WARN(SEC-08). `server.tls: false`면 `usePlaintext()` + WARN; 서버가 `allow-plaintext`가 아니면 Hello가 거부된다. Java 8은 8u252+(ALPN) 필수, 미달이면 기동 시 ERROR 1줄 + 전송 비활성(계측은 유지) |
| Netty 시스템 프로퍼티(클래스 로딩 전 설정) | `noUnsafe=true`, `allocator.type=unpooled`, `noPreferDirect=true`, `maxDirectMemory=8388608`, `tryReflectionSetAccessible=false`, `eventLoopThreads=1`, `allocator.numDirectArenas=0`. **셰이딩이 프로퍼티 문자열을 relocate하므로 실제 키는 `io.argos.agent.shaded.io.netty.noUnsafe` 형태가 될 수 있다** → 19 문서 빌드가 relocate 여부를 확정하고 상수 클래스 `NettyProps`에 반영(P-3 확인) |
| 인증 | `CallCredentials`로 모든 RPC 메타데이터에 `authorization: Bearer <token>` |
| 스레드 총수 | argos-sender, argos-metrics, argos-watch, argos-io(Netty 1), argos-logger, argos-command = 6 |
| 직접 메모리 | ≤ 8MB(unpooled + maxDirectMemory). 초과는 `OutOfMemoryError: Direct buffer` → `moduleError`가 아니라 전송 비활성 + ERROR 1줄 |

### 10.4 세션·시계·사전 (11 문서 참조)

`SessionClient`(argos-io 루프 위 비동기 스트림): `AgentHello{agent_version, protocol_version, jvm, os, host, pid, app.name, app.env, app.version, instance_name, config_hash, remote_config_allow[], capture_modes, virtual_threads, cgroup, sent_at}` → `ServerHello{accepted, reason, app_id, instance_id, clock_offset_ms, config_push[], min_agent_version}`. 거부 사유(버전·토큰·미등록·상한)는 ERROR 1줄 + 백오프 재시도(미등록은 60초 간격). heartbeat 30초마다 `sent_at/received_at`으로 NTP식 오프셋 측정 → 이동 중앙값(5개) → `ClockSync.offsetMs`(에이전트는 자기 시계로 ts를 찍고 서버가 보정·표시, D-02). `DictionaryCache`: kind별 LRU(서비스 10,000 / SQL 10,000 / 클래스·메서드 20,000 / 호스트 1,000 / 스레드 1,000 / 프레임 50,000), 신규 항목은 `DictionaryEntry` 배치로 선전송, 서버 `DictionaryRequest`에 재전송. `InstanceUpdate`: 포트 확정(Tomcat `AbstractEndpoint.start`), 가상 스레드 감지, 버전 변경, 모듈 상태 변화.

## 11. 메트릭 수집기 (F-A09~F-A11, D-16, D-25)

`argos-metrics` 스레드, 5초 격자. 각 메트릭은 `MetricRegistry`에 (이름, 라벨, 종류 gauge/counter, 단위)로 등록되고 11 문서 `MetricBatch`로 전송. 인스턴스당 시리즈 상한 500(`metrics.max-series`) 초과는 드롭 + `argos_agent_metric_series_dropped_total`.

| 그룹 | 메트릭(OpenMetrics) | 소스 |
|---|---|---|
| JVM 메모리 | `jvm_memory_used_bytes{area="heap|nonheap"}`, `jvm_memory_max_bytes{area}`, `jvm_memory_pool_used_bytes{pool}`, `jvm_memory_pool_max_bytes{pool}`, `jvm_buffer_memory_used_bytes{buffer="direct|mapped"}` | `MemoryMXBean`, `MemoryPoolMXBean`, `BufferPoolMXBean` |
| GC | `jvm_gc_collection_count_total{gc,gc_type}`, `jvm_gc_collection_seconds_total{gc,gc_type}`, `jvm_gc_pause_seconds_total{gc}`(pause 계열만) | `GarbageCollectorMXBean` + `GcNameMapper`(아래 표) |
| 스레드·클래스 | `jvm_threads_live`, `jvm_threads_daemon`, `jvm_threads_peak`, `jvm_threads_virtual_started_total`(JDK 21+, `jdk.management.VirtualThreadSchedulerMXBean` 가용 시), `jvm_classes_loaded`, `jvm_classes_unloaded_total` | `ThreadMXBean`, `ClassLoadingMXBean` |
| CPU·FD | `process_cpu_usage_ratio`, `system_cpu_usage_ratio`, `process_open_fds`, `process_max_fds`, `jvm_uptime_seconds` | `OperatingSystemMXBean`(com.sun), `UnixOperatingSystemMXBean` |
| Tomcat | `tomcat_threads_busy{connector}`, `tomcat_threads_current{connector}`, `tomcat_threads_max{connector}`, `tomcat_connections_current{connector}`, `tomcat_connections_max{connector}` | `TomcatRegistry` |
| 풀 | `jdbc_pool_active{pool}`, `jdbc_pool_idle{pool}`, `jdbc_pool_waiting{pool}`, `jdbc_pool_max{pool}` | `PoolRegistry` |
| 시스템(Linux) | `system_cpu_usage_ratio`(cgroup 한도 분모), `system_memory_used_bytes`, `system_memory_total_bytes`(한도), `system_disk_used_bytes{mount}`, `system_disk_total_bytes{mount}`, `system_network_receive_bytes_total{iface}`, `system_network_transmit_bytes_total{iface}`, `container_cpu_limit_cores`, `container_memory_limit_bytes`, `container_cpu_throttled_seconds_total` | `/proc/stat`, `/proc/meminfo`, `/proc/diskstats`, `/proc/net/dev`; cgroup v2 `/sys/fs/cgroup/cpu.max`·`cpu.stat`·`memory.max`·`memory.current`, v1 `cpu/cpu.cfs_quota_us`·`cpu.cfs_period_us`·`cpuacct.usage`·`memory/memory.limit_in_bytes`·`memory.usage_in_bytes`. 한도 없으면 호스트 값. 인스턴스 메타 `cgroup=v1|v2|none`. Windows는 1.x |
| 에이전트 자기 | `argos_agent_dropped_total{queue}`, `argos_agent_ignored_total{reason}`, `argos_agent_orphan_steps_total{type}`, `argos_agent_module_errors_total{module}`, `argos_agent_transform_errors_total`, `argos_agent_cpu_seconds_total`(ArgosThread 합), `argos_agent_heap_bytes_estimated`, `argos_agent_queue_size{queue}`, `argos_agent_stack_capture_seconds_total`, `argos_agent_sql_unparsed_total`, `argos_agent_dictionary_overflow_total{kind}`, `argos_agent_clock_offset_ms` | 내부 카운터 |

GC 이름 매핑(`GcNameMapper`)

| MXBean 이름 | `gc` | `gc_type` | pause 집계 |
|---|---|---|---|
| `G1 Young Generation` / `G1 Old Generation` / `G1 Concurrent GC` | g1 | young / old / concurrent | young·old |
| `PS Scavenge` / `PS MarkSweep` | parallel | young / old | 둘 다 |
| `Copy` / `MarkSweepCompact` | serial | young / old | 둘 다 |
| `ParNew` / `ConcurrentMarkSweep` | cms | young / old | young만 |
| `ZGC Pauses` / `ZGC Cycles` / `ZGC Major|Minor Pauses` / `ZGC Major|Minor Cycles` | zgc | pause / cycle | Pauses만 |
| `Shenandoah Pauses` / `Shenandoah Cycles` | shenandoah | pause / cycle | Pauses만 |
| 그 외 | 원문 | unknown | 제외, WARN 1회 |

`ActiveSnapshotter`(5초): `ActiveRegistry`의 진행 중 트랜잭션 → `ActiveTransactionSnapshot{tx_id, service_id, kind, elapsed_ms, state(SQL|HTTP|DBC|METHOD|IDLE), state_name_id, thread_name_id, user_id, remote_ip, start_ts}` (11 문서).

## 12. 원격 명령·동적 설정 (F-A12, F-A13, SEC-20~25)

### 12.1 명령

`CommandDispatcher`(`argos-command` 스레드, 세션 스트림 `Command{id(UUID), type, args, ttl_ms, issued_at}` 수신). 권한은 서버가 강제하고 에이전트는 종류·쿨다운·크기만 통제한다. `issued_at + ttl`(기본 60초)이 지난 명령은 실행하지 않고 `CommandResult{status=EXPIRED}`.

| 종류 | 동작 | 상한·쿨다운 |
|---|---|---|
| THREAD_DUMP | JDK ≤20: `ThreadMXBean.getThreadInfo(getAllThreadIds(), 64)` → 텍스트 포맷(이름·상태·락·스택 ≤64). JDK 21+: `com.sun.management.HotSpotDiagnosticMXBean.dumpThreads(path, JSON)`(리플렉션)로 `log.dir/td-<id>.json`에 쓴 뒤 읽고 즉시 삭제(가상 스레드 포함). 결과에 진행 중 트랜잭션의 `tx_id↔thread` 매핑 첨부 | 1MB 초과 시 절단 + `truncated`. 인스턴스당 동시 1건, 쿨다운 5초(`commands.thread-dump.cooldown-seconds`) |
| THREAD_STACK | `ActiveRegistry.find(tx_id)` → `WeakReference<Thread>` → `getStackTrace()`(가상 스레드도 동일) | 깊이 64, 쿨다운 1초 |
| SELF_DIAG | JSON: 버전·커밋·프로토콜, 업타임, 설정 해시·유효 설정(출처 포함, 토큰 마스킹), `remote-config.allow`, 캡처 모드, 모듈 상태·에러 카운터, 변환 클래스 수, 큐 길이·드롭, 사전 크기·오버플로, 에이전트 CPU(ArgosThread 합)·추정 힙, 스레드 목록(6), 느린 스택 캡처 비용, 시계 오프셋, 가상 스레드·cgroup 여부 | 256KB |
| RELOAD_CONFIG | yml 재로딩 → 로컬 키 전부 적용(로컬은 하드 캡 대상 아님) → `ConfigApplyResult` | 분당 1회 |
| ENV_DUMP | `System.getenv()`, `System.getProperties()`, `RuntimeMXBean.getInputArguments()` → `EnvMasker`: 키 정규식 `(?i)(pass|pwd|secret|token|key|credential|auth|cert|private|session|cookie|salt|argos)` 또는 값 정규식(JWT `^[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.`, `AKIA[0-9A-Z]{16}`, 32자 이상 base64/hex 연속) → `****(len=N)`. 사용자 추가 `commands.env-dump.mask-patterns`. **끄는 설정 없음** | 256KB |
| 공통 | 모든 결과는 `CommandResult{id, status, started_at, duration_ms, payload(bytes), truncated}`. 실행 중 예외는 `status=FAILED` + 메시지(스택 없음) |

### 12.2 동적 설정 (DSN-02-17)

`ConfigPush{change_id, entries[{key, value}], issued_by}` → `RemoteConfigGate`:

1. 키가 `remote-config.allow` 글롭에 매칭되지 않으면 거부(`reason=not_allowed`). 기본 allow: `sampling.*`, `instrumentation.disabled`, `instrumentation.ignore-paths`, `instrumentation.ignore-extensions`, `instrumentation.ignore-user-agents`, `log.level`, `enabled`, `transaction.slow-stack.*`.
2. 기본 불허(allow에 명시해야만 가능): `capture.sql-parameters`, `capture.http-headers`, `capture.user-id.*`, `capture.query-string`, `capture.remote-ip`, `instrumentation.custom-methods`, `server.*`, `app.*`, `remote-config.*`, `agent.disabled`.
3. 값 검증 실패 → `reason=invalid`. 적용 → 새 `AgentConfig`·`CaptureConfig`·`IgnoreRules`를 volatile 교체, `ConfigHash` 재계산.
4. `instrumentation.custom-methods` 변경은 retransform: 매칭 클래스 ≤50/명령, 분당 1회, 초과는 `reason=retransform_limit`.
5. `ConfigApplyResult{change_id, applied_keys[], rejected[{key, reason}], effective_hash, retransformed_classes, duration_ms}` 응답 + 로그 INFO(거부는 WARN).

킬 스위치 2단(F-A15): ① `enabled=false`(동적·로컬) → `KillSwitch.ENABLED=false`, 모든 Advice 즉시 return, 메트릭·세션은 유지(복구 가능). ② `-Dargos.agent.disabled=true` → premain이 아무것도 설치하지 않음(현장에서 `-javaagent`를 못 지울 때).

## 13. 느린 스택·MDC·사전 점검

### 13.1 SlowStackWatcher (F-A19)

`argos-watch` 스레드 1초 틱. `ActiveRegistry` 순회: `elapsed ≥ slowThreshold(service)`(서비스별 `sampling.profile.slow-threshold-ms`, 기본 1,000)이고 `captures < max-captures`(5)이고 마지막 캡처 후 `interval-ms`(3,000) 경과한 트랜잭션을 모은다 → 플랫폼 스레드는 `ThreadMXBean.getThreadInfo(ids[], depth=64)` **1회 일괄**, 가상 스레드는 `Thread.getStackTrace()` 개별 → 각 트랜잭션에 `STACK` step(`stepStack`, 프레임은 `dict(FRAME, "class.method(file:line)")` id 배열 ≤64). 사이클당 최대 `max-threads-per-cycle`(10). 소요 ms를 `argos_agent_stack_capture_seconds_total`에 더하고, 10사이클 연속 50ms 초과면 자동 비활성 + WARN(`transaction.slow-stack.enabled=false`로 복귀 가능). 같은 틱에서 `max-duration-ms` 초과 트랜잭션 강제 종료(6.7)와 `OverheadMonitor`(ArgosThread CPU 합산, 1분 창 1% 초과 시 `normal-rate` 자동 1/2, 복귀는 5분 후)도 수행한다.

### 13.2 MDC 주입

6.9. 키 `argos.trace_id`(32 hex), `argos.tx_id`(16 hex). 공개 계약(O-R03).

### 13.3 `--check` (F-A27)

`java -jar argos-agent.jar --check [--config argos-agent.yml] [--json] [--classpath <dir|jar>...]`. `AgentPremain.main`이 AgentClassLoader로 `CheckCommand`를 실행한다(앱에 붙지 않음).

| # | 항목 | 판정 |
|---|---|---|
| 1 | 설정 파일 파싱·필수 키·알 수 없는 키 | PASS / FAIL(필수 누락) / WARN(알 수 없는 키) |
| 2 | 토큰 존재·형식(`argos_` 접두, 길이) · 보관 방식(평문이면 WARN) | |
| 3 | DNS 해석, TCP 연결(5초), TLS 핸드셰이크(체인·만료일, 30일 이내 WARN), 호스트명 검증 | |
| 4 | `AgentHello` 인증·등록 결과(서버 버전·프로토콜 호환, 앱 등록 여부·승인 대기) | |
| 5 | 시계 차이(1초 이상 WARN) | |
| 6 | JDK 버전·벤더 → 호환성 표 대조(8u252 미만 FAIL, 미검증 벤더 WARN) | |
| 7 | `--classpath`가 있으면 jar 매니페스트에서 Spring Boot·Framework·Tomcat·Servlet API·드라이버 버전 감지 → 표 대조(지원/미검증/비지원) | |
| 8 | `log.dir` 쓰기 가능, `token-file` 권한(0600 아니면 WARN) | |

출력: 표(또는 `--json`). 종료 코드 0(전부 PASS·WARN), 1(FAIL 있음), 2(사용법 오류).

## 14. 자기 보호·로그·예산 (F-A15, F-A35, N-02, N-26)

| 영역 | 설계 |
|---|---|
| 재진입 | `Argos.skip()`의 `ArgosThread` 검사; `CallDepth` 키별 최외곽; `io.argos.*`·AgentClassLoader 클래스 변환 제외; 매처·Advice 로딩 중 `ClassCircularityError`는 `SafeMatchers`가 포착 → skip + `transform_errors_total{reason=circularity}` |
| 변환 실패 | `TransformErrorListener.onError(typeName, cl, module, throwable)`: 모듈별 카운터 ≥10 → `ModuleHealth.disable(moduleId, "transform_errors")`. `instrumentation.exclude-classes`(글롭)로 특정 클래스를 변환에서 제외(현장 우회). `VerifyError`는 정의 시점이라 런타임 포착 불가 → 통합 테스트 `-XX:+BytecodeVerificationLocal`(18 문서) |
| Advice 예외 | `suppress`로 억제 → `Tracer.moduleError()` → 카운터 ≥100 → 모듈 비활성 + WARN 1회 + `InstanceUpdate` |
| 상한 | 모든 큐·캐시·사전·문자열(detail 4KB, 메시지 1KB, 경로 2KB, 헤더 값 1KB)·스택(30/64)·step(1,000)·시리즈(500)·통계 키(5,000) |
| 로그 | `AgentLogger`: 비동기(`argos-logger`), 파일 `log.dir/argos-agent.log` 10MB×5(`log.max-file-mb`, `log.max-files`), 디렉터리 0700 생성. 디렉터리 쓰기 불가 → stderr 폴백 **분당 10줄**, 같은 메시지 분당 1회. INFO 이하에서 수집 데이터(SQL·파라미터·헤더·URL 쿼리·user_id) 출력 금지(정적 검사: 로그 호출 인자 타입 allowlist). DEBUG는 문서에 PII 경고. 토큰은 항상 `argos_****`. 정상 운영에서 stdout/stderr 출력 0(F-A35) |
| 로그 디렉터리 기본 | `log.dir` 미설정 시 jar 위치의 `../logs`가 쓰기 가능하면 그곳, 아니면 `java.io.tmpdir/argos-agent-<pid>`(0700) |
| 스레드 예산(6) | `argos-sender`(배치·전송), `argos-metrics`(메트릭·통계 플러시·액티브 스냅샷·사전 선전송), `argos-watch`(느린 스택·max-duration·오버헤드 모니터·모듈 상태), `argos-io`(Netty NIO 1), `argos-logger`, `argos-command`. 모두 데몬·`ArgosThread` |
| 힙 예산(≤64MB) | 큐 32MB + 프로파일 16MB(겹침 포함 최대 40MB로 조정: `buffer.max-bytes`는 두 큐 합산 상한), 사전 캐시 8MB, TypePool 캐시 8MB(5분 후 축소), 기타 8MB |
| 비힙(N-26) | 직접 메모리 ≤8MB(unpooled), 메타스페이스 증가 ≤32MB(셰이딩 클래스 수 상한을 19 문서가 minimize로 관리), 스레드 6 |
| Unsafe | 셰이딩 의존성 설정으로 `sun.misc.Unsafe` 메모리 접근 미사용(Netty `noUnsafe`, JCTools `atomic` 큐, protobuf-javalite 또는 `UnsafeUtil` 비활성). `javap` 검사 테스트(03 문서 3.1). JDK 25 `--sun-misc-unsafe-memory-access=deny` 통과 |
| CDS | bootstrap 검색 경로 추가로 앱 CDS가 비활성되고 JVM 경고가 날 수 있다(JVM 출력, 에이전트 출력 아님). "알려진 제약"에 문서화, N-04는 CDS on/off 병기 |

## 15. 설정 레퍼런스

파일 `argos-agent.yml`(키는 아래 경로) → 시스템 프로퍼티 `-Dargos.<key>`(`.` 그대로) → 환경변수 `ARGOS_<KEY>`(`.`·`-` → `_`, 대문자) 순으로 뒤의 것이 이긴다. 알 수 없는 키는 WARN. "원격" 열: Y = 기본 allow, N = 기본 불허(로컬 allow에 추가해야 가능), - = 원격 불가(서버·앱·remote-config 자체).

| 키 | 타입 | 기본값 | 원격 | 설명 |
|---|---|---|---|---|
| `server.address` | string | (필수) | - | `host:port`, 기본 포트 9100 |
| `server.tls` | bool | true | - | false면 평문(WARN, 서버 allow-plaintext 필요) |
| `server.tls.ca-file` | path | - | - | 자체 CA PEM |
| `server.tls.insecure-skip-verify` | bool | false | - | true면 매 접속 WARN |
| `server.token` / `server.token-file` | string / path | - | - | 셋 중 하나(또는 `ARGOS_TOKEN`). 평문이면 WARN |
| `server.connect-timeout-ms` / `server.deadline-ms` | int | 5000 / 5000 | - | |
| `app.name` | string | (필수) | - | |
| `app.env` | string | `default` | - | 없으면 WARN |
| `app.instance` | string | 자동(`host:port` → `host:pid`) | - | |
| `app.version` | string | jar 매니페스트 `Implementation-Version` | - | |
| `app.group` | string | - | - | 전체 현황 그룹 |
| `enabled` | bool | true | Y | 킬 스위치 1단 |
| `agent.disabled` | bool(sysprop만) | false | - | 킬 스위치 2단 |
| `sampling.profile.slow-threshold-ms` | int 또는 `{default, services{}}` | 1000 | Y | 서비스별 가능 |
| `sampling.profile.normal-rate` | 0..1 | 0.1 | Y | |
| `sampling.profile.max-steps` | int | 1000 | Y | 초과는 요약 모드 |
| `capture.query-string` | off/whitelist/full | off | N | |
| `capture.query-string.whitelist` | list | [] | N | |
| `capture.http-headers` | list | [] | N | 값 ≤1KB |
| `capture.remote-ip` | full/masked/off | masked | N | 설치 마법사가 선택 강제(서버 측 안내) |
| `capture.trusted-proxies` | CIDR list | [] | N | 비면 XFF 무시 |
| `capture.user-id.mode` | off/plain/hashed | off | N | |
| `capture.user-id.source` | `header:<name>` / `session:<attr>` / `mdc:<key>` | - | N | |
| `capture.sql-parameters` | masked/partial/full | masked | N | |
| `capture.sql-parameters.whitelist-columns` | list | [] | N | |
| `capture.mask-patterns` | regex list | 기본 세트(이메일·휴대전화·주민번호·카드·IPv4) + 추가 | N | 예외 메시지·partial 파라미터 공용 |
| `capture.error-message-max-length` | int | 1024 | Y | |
| `capture.stack-depth` | int | 30 | Y | ERROR step 스택 |
| `instrumentation.disabled` | list | [] | Y | 모듈 id |
| `instrumentation.exclude-classes` | glob list | [] | Y | 변환 제외(현장 우회) |
| `instrumentation.ignore-paths` | glob list | `/actuator/health`, `/actuator/health/**`, `/health`, `/healthz`, `/ready`, `/favicon.ico` | Y | |
| `instrumentation.ignore-extensions` | list | css js map png jpg jpeg gif svg ico woff woff2 ttf eot | Y | |
| `instrumentation.ignore-user-agents` | glob list | [] | Y | |
| `instrumentation.ignore-methods` | list | [OPTIONS] | Y | |
| `instrumentation.custom-methods` | list of {pattern, step-type, as-transaction} | [] | N | 금지 접두사 고정 |
| `instrumentation.custom-methods.max-classes-per-pattern` | int | 500 | N | |
| `instrumentation.app-packages` | list | 자동(main 클래스 패키지 상위 2단계) | Y | 지문용 최상위 앱 프레임 |
| `instrumentation.service-dictionary-max` | int | 10000 | N | |
| `instrumentation.error-threshold` / `.transform-error-threshold` | int | 100 / 10 | N | |
| `transaction.max-duration-ms` | int | 3600000 | Y | |
| `transaction.slow-stack.enabled` / `.interval-ms` / `.max-captures` / `.max-threads-per-cycle` / `.depth` | bool/int | true / 3000 / 5 / 10 / 64 | Y | |
| `propagation.exclude-hosts` | glob list | [] | N | traceparent·tracestate 미주입 |
| `propagation.argos-state.hosts` | glob list | [] | N | Argos tracestate 엔트리 허용 호스트 |
| `propagation.preserve-incoming` | bool | true | N | 기존 traceparent 보존 |
| `async.step` | bool | false | Y | ASYNC step 기록 여부 |
| `buffer.transaction.capacity` | int | 20000 | N | |
| `buffer.max-age-seconds` | int | 120 | N | |
| `buffer.max-bytes` | bytes | 32MB | N | 트랜잭션+프로파일 합산 |
| `sender.flush-interval-ms` / `.batch-size` / `.max-in-flight` / `.compression` | int/int/int/gzip|none | 100 / 500 / 4 / gzip | N | |
| `metrics.interval-seconds` / `.max-series` | int | 5 / 500 | Y / N | |
| `stats.window-seconds` / `.max-keys` | int | 10 / 5000 | N | |
| `active.snapshot-interval-seconds` | int | 5 | Y | |
| `sql.max-length` / `sql.backslash-escape` / `sql.keep-hints` | int/bool/bool | 65536 / true / false | N | |
| `commands.env-dump.mask-patterns` | regex list | 기본 세트 + 추가 | N | 제거 불가 |
| `commands.thread-dump.cooldown-seconds` / `.max-bytes` | int | 5 / 1048576 | N | |
| `remote-config.allow` | glob list | `sampling.*`, `instrumentation.disabled`, `instrumentation.ignore-*`, `log.level`, `enabled`, `transaction.slow-stack.*`, `capture.error-message-max-length`, `capture.stack-depth`, `instrumentation.app-packages`, `async.step`, `active.snapshot-interval-seconds`, `metrics.interval-seconds`, `transaction.max-duration-ms` | - | 하드 캡 |
| `log.level` / `log.dir` / `log.max-file-mb` / `log.max-files` | string/path/int/int | INFO / 자동 / 10 / 5 | Y / - / - / - | |
| `debug.transform-timing` | bool | false | - | 클래스별 매칭·변환 시간 로그 |

## 16. 요구 대응과 테스트 포인트

### 16.1 요구 → 절

| 요구 | 절 |
|---|---|
| F-A01·A28·A23 | 6.1, 7 |
| F-A02·A29·A36 | 6.2 |
| F-A03·A04 | 6.3, 9.4 |
| F-A05·A06·A32·A33 | 6.5, 8 |
| F-A07 | 6.6, 6.7, 8 |
| F-A08 | 9.1(errorMessage 마스킹, topAppFrame), 6.1 exit |
| F-A09·A10·A11 | 11, 6.4 |
| F-A12·A13 | 12 |
| F-A14 | 6.10 |
| F-A15·A35 | 4.5, 5.2, 5.3, 12.2(킬 스위치), 14 |
| F-A19 | 13.1 |
| F-A20 | 6.9 |
| F-A21·A25 | 10.4(Hello), 9.1(env·versionId) |
| F-A22 | 9.3 |
| F-A24 | 6.8 |
| F-A26 | 10.2(종료 훅 AgentBye) |
| F-A27 | 13.3 |
| F-A30 | 8, 11, 12.1(dumpThreads) |
| F-A31 | 4 |
| F-A34 | 6.7 |
| D-01 | 9.2(nanoTime) |
| D-04·D-21 | 7.1 |
| D-08 | 9.4, 10.4(사전) |
| D-11 | 9.5 |
| D-12 | 9.3 |
| D-19 | 7.2 |
| D-20 | 6.8, 6.3(고아), 9.3(kind) |
| D-22 | 9.4 |
| D-23 | 8(가상 스레드), 6.6(스레드 전환) |
| D-24 | 6.3(CallDepth) |
| D-25 | 11(GC 매핑) |
| D-26 | 6.1(금지 호출) |
| N-01~04·N-26~30 | 4.3, 10.3, 14; 측정은 01 문서 7.3 |
| SEC-06 | 10.3(토큰), 14(로그) |
| SEC-20·24 | 12.1 |
| SEC-23·25 | 12.2, 6.10 |
| SEC-26 | 14 |
| SEC-31·32·34·37 | 6.1, 15 |
| SEC-35·3A | 9.4, 6.3 |
| SEC-36 | 9.1(Masker) |
| SEC-38 | 8 |

### 16.2 03 문서 3.1 항목 → 검증 위치

| 03 §3.1 항목 | 테스트(18 문서 하네스) |
|---|---|
| major version 52 | `agent-dist` 빌드 검사 |
| 셰이딩·META-INF 파일 | `check-shading.sh` |
| Unsafe 참조 0, JDK 25 deny | `javap` 스캔 테스트, 매트릭스 JDK 25 |
| 모듈 README 버전 범위·경계 테스트·witness | 모듈별 통합 테스트(2 버전 이상) |
| 변환 목록 베이스라인, onError 0 | `agent-testing` TransformListener 녹화 → `baseline/<app>-<jdk>.txt` diff |
| `-XX:+BytecodeVerificationLocal` | 통합 테스트 JVM 옵션 |
| JDK 클래스 계측 IllegalAccessError 0 | P-6, JDK 17·21·25 |
| 1요청 1트랜잭션 5종 | servlet 통합 테스트 |
| JDBC 중첩 제거, DBC·COMMIT | jdbc 통합 테스트(Hikari + 드라이버) |
| JMX off 메트릭 | pool·tomcat 통합 테스트(Spring Boot 기본) |
| 가상 스레드 모드 | JDK 21·25 `spring.threads.virtual.enabled=true` 반복 |
| 재진입 | 에이전트 스레드 작업 미기록 단언 |
| Executor 허용목록·동작 보존 | executor 통합 테스트 |
| fail-safe·exclude-classes | 예외 주입(테스트 전용 모듈)·transform 오류 주입 |
| 누수 4종 | 1만 요청 후 레지스트리·ThreadLocal·폴백 맵, Tomcat 재배포 50회 |
| 오버헤드·72h·스레드·직접 메모리·메타스페이스 | 01 문서 7.3 프로토콜 벤치 |
| 로그 ERROR 0·WARN 허용목록·stdout 0 | 로그 fixture 대조 |
| PII·토큰 grep 0 | INFO 1만 요청 후 grep |
| ArchUnit | `agent-instrumentation` 테스트 |
| 환경 덤프 마스킹·하드 캡·금지 접두사 | command 단위·가짜 서버 통합 테스트 |

## 17. 설계 결정

| ID | 결정 | 대안 | 선택 이유 | 영향 요구 |
|---|---|---|---|---|
| DSN-10-01 | AgentClassLoader 부모 = 플랫폼 CL(JDK 9+) / null(8) | 항상 null | `java.sql`·`java.net.http`는 플랫폼 모듈. 플랫폼 CL은 bootstrap에 위임하므로 bootstrap API도 보인다 | F-A31 |
| DSN-10-02 | 요청 앵커 = `ServletRequest` 속성 `argos.tx`, ThreadLocal은 캐시 | ThreadLocal 단독 | ERROR·ASYNC 디스패치가 다른 스레드·재진입이어도 1요청 1트랜잭션 | D-04 |
| DSN-10-03 | 서블릿 계측 지점 = `Filter.doFilter` 최초 진입 + `HttpServlet.service`(두 네임스페이스), Tomcat Valve는 1.x 옵션 | Valve만 | Tomcat·Jetty·Undertow 공통, Security 거부 요청도 기록 | F-A01 |
| DSN-10-04 | Advice 인라인만, `readOnly=false`는 allowlist(관찰 데이터 부가 목적)로 제한 | 전면 금지 | jdk11 HttpClient·Logback MDC·Log4j1은 인자·반환 교체 없이는 불가능. 범위를 좁혀 허용 | F-A15, 원칙 1 |
| DSN-10-05 | Executor 허용목록 + 필드 주입/약참조 폴백, 실행 측은 `Runnable.run`·`Callable.call`·`ForkJoinTask.exec`·`FutureTask.run` 계측 | 래핑 | 객체 동일성 보존. 변환 수는 ignore 목록과 베이스라인으로 통제 | F-A07, N-27 |
| DSN-10-06 | JDBC는 인터페이스 서브타입 매칭 + `CallDepth` 최외곽, `DbInfo`는 Connection당 `getMetaData().getURL()` 1회 | 드라이버별 계측 | 드라이버 무관, 래퍼 중첩 제거 | D-24 |
| DSN-10-07 | 전수 통계는 트랜잭션 컨텍스트 안의 SQL·외부 호출만(배치 포함), 컨텍스트 밖은 카운터 | 백그라운드 SQL 포함 | 풀 검증 쿼리 등 노이즈 배제. 1.x에 별도 통계 | D-12, D-20 |
| DSN-10-08 | 프로파일 샘플링은 종료 시 결정, step은 항상 메모리 기록, 상한 초과는 요약 모드 | 시작 시 결정 | 느림·에러는 종료 시에만 안다. 요약 모드로 메모리 상한 유지 | A-3 |
| DSN-10-09 | 큐는 건수·시간(120초)·바이트 삼중 상한, 앱 스레드 비대기 | 건수만 | 계획 정지 10분의 일부라도 보존하되 힙 64MB 안에 | A-12, N-02, N-05 |
| DSN-10-10 | 전송은 배치 unary, in-flight 4, deadline 5초, batch_seq 단조 | 클라이언트 스트리밍 | 배치별 ack·재전송, gRPC 내부 버퍼 무제한 증가 방지 | DSN-02-11 |
| DSN-10-11 | gRPC/Netty NIO·JDK TLS·이벤트 루프 1·unpooled·direct 8MB·Unsafe off, 스레드 6 | 기본 설정 | N-26, 앱 로그 경고 0 | F-A35 |
| DSN-10-12 | 풀·Tomcat 메트릭은 생성 Advice 레지스트리 + getter, JMX 비사용 | JMX | Spring Boot 기본이 MBean off | F-A10 |
| DSN-10-13 | 가상 스레드: 레지스트리 키 = TraceContext, Thread는 약참조, CPU·alloc 결측, 이름 정규화 | 미지원 | Boot 3.2+ 확산 | F-A30, D-23 |
| DSN-10-14 | WebClient는 reactor-netty 내부 `HttpClientHandler` 지점에 ContextStore로 전파, WebFlux 서버는 비지원 | Reactor Context 전면 연동 | 1.0 범위 한정, 1.x 확장 | F-A33 |
| DSN-10-15 | MDC 주입은 로그 이벤트 생성 시점(프레임워크별) | `MDC.put` | 앱 `MDC.clear()`·비동기 영향 없음 | F-A20 |
| DSN-10-16 | 환경 덤프 마스킹은 에이전트 측 고정(끄기 불가), 원격 변경은 로컬 allow 하드 캡 | 서버 측 | 서버 침해 시 피해 범위 축소 | SEC-20, SEC-23 |
| DSN-10-17 | SQL 정규화 실패는 원문 미전송 `<unparsed>`, 64KB 상한, 1ms 예산 | 원문 전송 | PII 보호, 핫패스 보호 | SEC-35, D-22 |
| DSN-10-18 | 사전 키 xxh3_64(자체 구현 또는 zero-allocation-hashing 셰이딩), 선전송 + 서버 요청 재전송 | 서버 발급 순번 | 적재 경로 비결합 | D-08 |
| DSN-10-19 | 스레드 덤프 JDK 21+는 `HotSpotDiagnosticMXBean.dumpThreads`를 log.dir에 쓰고 즉시 삭제 | `dumpAllThreads`만 | 가상 스레드 포함 | F-A30 |
| DSN-10-20 | 로그 디렉터리 쓰기 불가 시 stderr 폴백은 분당 10줄 | 무제한 폴백 | 고객 로그 오염 방지 | F-A35 |

## 18. 미결·가정

| # | 항목 | 가정 | 확인 방법 |
|---|---|---|---|
| U-1 | 가상 스레드에서 `ThreadMXBean.getCurrentThreadCpuTime()`·`getCurrentThreadAllocatedBytes()` 반환값 | -1 반환(예외 아님) | JDK 21·25 실측 |
| U-2 | `HotSpotDiagnosticMXBean.dumpThreads(String, ThreadDumpFormat)` 가용 버전·가상 스레드 포함 | JDK 21+ | 실측 |
| U-3 | `ClassInjector.UsingReflection`이 `redefineModule(extraOpens java.lang)` 후 JDK 17·21·25에서 동작 | 동작 | P-6 |
| U-4 | Java 8 ALPN 내장 최소 빌드 | 8u252 | 릴리스 노트 |
| U-5 | ByteBuddy 최소 버전(JDK 25 class file 69)과 `net.bytebuddy.experimental` 프로퍼티의 셰이딩 후 유효성 | 1.17.x 이상 | 빌드·`strings` 확인 |
| U-6 | Shadow가 `io.netty.noUnsafe` 등 프로퍼티 문자열을 relocate하는지 | relocate됨 | P-3 `javap -c` |
| U-7 | HikariCP·Tomcat JDBC·DBCP2 getter 이름이 지원 버전 범위에서 안정적인지 | 안정 | 모듈 통합 테스트 |
| U-8 | Spring MVC `BEST_MATCHING_PATTERN_ATTRIBUTE`가 Framework 7에서도 설정되는지 | 유지 | 실측 |
| U-9 | reactor-netty `HttpClientHandler.requestWithBody`·`HttpObserver.onStateChange` 시그니처(1.0~1.2) | 존재 | 소스 확인, witness로 방어 |
| U-10 | jdk11 `HttpClientImpl.sendAsync` 응답 완료 지점 계측 방법 | `MultiExchange` 또는 반환 CF 완료 콜백 없이 가능 | 착수 시 OTel 소스 참고 |
| U-11 | OkHttp `enqueue` 콜백 스레드에서 인터셉터 실행 시 `Call`→컨텍스트 매핑의 안정성 | `RealCall.<init>`에서 붙이면 안정 | 통합 테스트 |
| U-12 | `CompletableFuture` 내부 클래스 이름·필드(`fn`)가 JDK 8~25에서 동일한지 | 동일 | 매트릭스 |
| U-13 | `Log4j2 ThreadContextDataInjector` 구현 3종의 `injectContextData` 시그니처(2.17~2.24) | 동일 | 테스트 |
| U-14 | 세대형 ZGC MXBean 이름 | `ZGC Major/Minor Pauses|Cycles` | JDK 21·25 실측 |
| U-15 | `-XX:+BytecodeVerificationLocal` product 플래그 유지(JDK 25) | 유지 | `PrintFlagsFinal` |
| U-16 | Spring Boot 가상 스레드 Tomcat 스레드 이름 접두 | `tomcat-handler-` | 소스 |
| U-17 | xxh3_64 자체 구현 vs `zero-allocation-hashing` 셰이딩(Apache-2.0, Unsafe 사용 여부) | ZAH는 Unsafe 사용 가능성 → 자체 구현 우선 | 라이브러리 소스 확인 |
| U-18 | Tomcat 멀티 웹앱에서 `contextPath` 접두 시 서비스 사전 상한과의 상호작용 | 웹앱 수 × 서비스 수 ≤ 10,000 | 도그푸딩 |
| U-19 | Spring Batch `AbstractStep.execute` 계측 단위가 청크 수천 개 배치에서 요약 모드로 충분한지 | 충분 | 사내 배치 검증 |
| U-20 | `instrumentation.app-packages` 자동 추정(main 클래스 패키지) 정확도 | 대부분 맞음 | 도그푸딩 |

## 19. 다른 설계 문서에 요구하는 것

| 대상 | 요구 | 이유 |
|---|---|---|
| 11 프로토콜 | `AgentHello`에 `remote_config_allow[]`, `capture_modes{remote_ip, user_id, query_string, sql_parameters}`, `virtual_threads`, `cgroup`, `config_hash`, `app.version`, `app.group`; `ServerHello`에 `clock_offset_ms`, `config_push[]`, `reject_reason` enum(VERSION, TOKEN, UNREGISTERED, LIMIT, PLAINTEXT_DENIED); `TransactionRecord`에 `kind`, `env`, `version_id`, `error_group 입력(error_class_id, error_message, top_frame_id)`, `flags(streaming, timeout, leaked, profile_captured, summarized)`, `cpu_time_ms`·`alloc_bytes`는 -1 결측 표현; `Step`에 `fetch_ms`, `rows`, `target_id`, `stack[]`(frame id), `SummaryStep`; `StatsBatch{window_ts, kind, sql[], ext[{host_id, count, errors, sum_ms, max_ms, timeouts, connect_fails}]}`; `DictionaryEntry{kind, hash64, text, norm_version, truncated}`; `ConfigPush`/`ConfigApplyResult{rejected[{key, reason}], retransformed_classes}`; `Command{ttl_ms, issued_at}`/`CommandResult{status enum(OK, FAILED, EXPIRED, THROTTLED), truncated}`; `InstanceUpdate{port, virtual_threads, version, module_states[]}`; `AgentBye{reason}`; `Ack{last_seq}` | 10장·12장 |
| 12 서버 | `(instance_id, batch_seq)` 중복 제거 윈도 1,000; Hello 거부 사유·재시도 간격 안내; `tracestate` `argos=` 엔트리에서 `caller_app_id` 추출; `kind=BATCH` 통계 분리; 명령 권한·TTL·동시 1건은 서버가 강제(에이전트는 쿨다운만); 모듈 자동 비활성·누수 카운터를 인스턴스 WARN 사유에 포함; `transactions_ignored_total`·`orphan`·`leaked`·`unanchored`를 수집 상태 패널에 | 12장·7장 |
| 13 저장소 | 사전 `kind`에 FRAME(7)·METRIC(8)·ERROR_GROUP(9) 추가, `norm_version`·`truncated` 컬럼; `profile_step.type`에 DBC·COMMIT·ROLLBACK 추가(Enum8 재정의: SQL=1 DBC=2 COMMIT=3 ROLLBACK=4 HTTP=5 METHOD=6 ASYNC=7 ERROR=8 STACK=9 MESSAGE=10 SUMMARY=11), `fetch_ms` 컬럼, `xlog.flags` 비트 컬럼, `cpu_time_ms`·`alloc_bytes` Nullable 또는 -1 규약 | 9장 |
| 16 보안 | `EnvMasker` 기본 패턴 세트와 `Masker` 정규식 세트의 최종 목록·테스트 케이스 30건; `token-file` 권한 검사 정책 | 12.1, 9.1 |
| 18 테스트 | `agent-testing` 하네스: 샘플 앱 기동(JDK×Boot 매트릭스), 수집 결과 JSONL/가짜 서버 단언 DSL, 변환 목록 베이스라인 녹화·diff, fail-safe 주입용 테스트 모듈, `BytecodeVerificationLocal`, 재배포 50회, 가짜 gRPC 서버(지연·거부·명령 발행), 벤치 러너(01 문서 7.3) | 16.2 |
| 19 빌드 | `inst/*.classdata` 재패키징 태스크, 셰이딩 relocate 규칙(`io.argos.agent.shaded.`), ServiceLoader 파일 relocate, Netty 프로퍼티 relocate 확정, `minimize`, `--release 8`, `check-shading.sh`(클래스·META-INF 파일·Unsafe 참조), 매니페스트 속성, SBOM·NOTICE·deps.txt 동봉, ArchUnit·allowlist 파일 위치 | 4장, 14장 |
| 02 아키텍처(PM) | DSN-02-01의 "readOnly=false 금지"를 "allowlist로 제한(관찰 데이터 부가 목적)"으로 완화 반영(DSN-10-04). `StepType`에 DBC·COMMIT·ROLLBACK·SUMMARY 추가 | 5.3, 9.1 |
