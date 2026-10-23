# Argos 수집 서버 설계

| 항목 | 내용 |
|---|---|
| 문서 번호 | 12 |
| 문서 버전 | 0.1 (초안) |
| 작성일 | 2026-10-23 |
| 상태 | 설계 검토 중 |
| 만족시키는 요구 | F-S01~F-S08, F-S10, F-S16~F-S33, D-02, D-03, D-06, D-08, D-12~D-19, N-05, N-06, N-08, N-15, N-18, N-22~N-25, SEC-01~05, SEC-07·09, SEC-11~15, SEC-21~24, SEC-30·33·36·39, SEC-40~56, SEC-51~54, SEC-80~84 |
| 의존 문서 | 02 아키텍처(DSN-02-08·10~20·22, §4.2·§4.3·§6·§7), 11 프로토콜, 06 보안 요구 |
| 이 문서에 의존하는 문서 | 13 저장소(테이블·컬럼), 14 API(엔드포인트 계약), 15 UI(WebSocket 프레임), 16 보안(구현 세부), 17 배포(설정 파일·메모리 예산), 18 테스트(하네스) |

## 1. 범위와 구성

`argos-server`는 Spring Boot(JDK 21) 단일 JVM이다. 역할 프로파일 `collector`·`web`·`alert`·`admin`·`ops`를 하나의 프로세스(`all`, 1.0 공식 구성)에서 모두 켠다(DSN-02-19). 역할 분리는 1.x이며, 그때를 위해 역할 간 통신은 인터페이스(`RealtimeBus`, `SessionRegistry`, `CommandRouter`)로만 한다.

```
io.argos.server
├─ bootstrap/      ServerApplication, 역할 프로파일, 기동 순서(마이그레이션 → 키 로드 → 리스너)
├─ config/         ArgosProperties(argos.*), 검증(금지 비밀번호·필수 키), 유효 설정 노출
├─ collector/      gRPC 서버, 인터셉터(인증·세션·상한·쿼터·레이트), AgentServiceImpl, SessionHandler
├─ wal/            WalWriter, WalSegment, WalReader, BlockIndex, WalRecovery
├─ ingest/         Decoder, DictionaryResolver, TimestampGuard, ErrorGroupFingerprinter, ApdexStamper, IngestPipeline
├─ storage/        ClickHouseWriter(테이블별), InsertBlock, DedupToken, RollupDefinitions, ClickHouseAdmin, PostgresRepositories
├─ session/        SessionRegistry, InstanceStateMachine, ClockSync, InstanceNaming, RegistrationPolicy
├─ realtime/       RealtimeBus(Reactor Sinks), Topic, WebSocketHandler, SubscriptionManager
├─ api/            REST 컨트롤러(14 문서 계약), SourceSelector(원본/1m/1h), QueryLimits, ErrorBody
├─ alert/          RuleModel, MetricProvider(Window·ClickHouse·Registry·Self), Evaluator, StateMachine, Grouper, Notifier, Channel(Email·Slack·Webhook), DestinationValidator, TemplateRenderer, DefaultTemplates
├─ admin/          AppService, InstanceService, TokenService, CommandRouter, ConfigChangeService, KillSwitchService, ApprovalQueue
├─ auth/           LoginService, SessionCookieService, RefreshRotation, CsrfFilter, RoleMatrix, SetupTokenBootstrap, PasswordPolicy, Lockout
├─ audit/          AuditEvent(enum), AuditService, AuditSink(PG·syslog·file)
├─ secrets/        MasterKey, EnvelopeCipher(AES-256-GCM), SecretField
├─ privacy/        DeletionJobService(뮤테이션), DataClassification 상수
├─ ops/            ReconcileJob, RollupRebuilder, StorageHealth, EmergencyRetention, MigrationRunner(CH), SelfMetrics, CertReloader, DiagnosticBundle
└─ cli/            argos-admin (rollup rebuild, reconcile, master-key rotate, token issue, setup-token print)
```

## 2. 핵심 인터페이스

```java
// collector → ingest 경계. WAL에 기록된 뒤 호출된다.
public interface IngestPipeline {
  void onTransactions(SessionContext s, WalPosition pos, TransactionBatch b);
  void onProfiles(SessionContext s, WalPosition pos, ProfileBatch b);
  void onMetrics(SessionContext s, WalPosition pos, MetricBatch b);
  void onStats(SessionContext s, WalPosition pos, StatBatch b);
  void onActive(SessionContext s, ActiveTransactionSnapshot snap);   // WAL 미경유(휘발)
}

public record SessionContext(int appId, int instanceId, String env, UUID sessionId, long agentEpoch,
                             IpMode ipMode, UserIdMode userIdMode, int apdexTMs, long receivedAtMs) {}

public record WalPosition(int segmentId, long offset, int length) {
  public String dedupToken() { return "wal:" + segmentId + ":" + offset; }
}

public interface WalWriter extends AutoCloseable {
  /** 레코드를 append하고 그룹 fsync가 끝난 뒤 반환한다(≤ 10ms 대기). 상한 초과면 WalFullException */
  WalPosition append(WalRecordType type, int instanceId, long agentEpoch, long seq, byte[] payload) throws WalFullException;
  long bytesInUse();
}

public interface ClickHouseWriter {
  /** 테이블별 큐에 넣는다. 블록 플러시는 2초 또는 50,000행. 반환 즉시 */
  void enqueue(Table table, WalPosition pos, List<Row> rows);
}

public interface RealtimeBus {
  void publish(Topic topic, RealtimeFrame frame);
  Flux<RealtimeFrame> subscribe(Topic topic, Subscriber who);   // 느린 구독자는 최신 우선 드롭
}

public interface SessionRegistry {
  Optional<AgentSession> find(int instanceId);
  AgentSession register(AgentHello hello, TokenRecord token, Channel control);   // REPLACED 처리 포함
  void closeByToken(long tokenId, CloseReason reason);                           // 폐기 → 즉시 종료
  Collection<AgentSession> byApp(int appId);
  void onHeartbeat(int instanceId, Heartbeat hb, long t2, long t3);
}

public interface CommandRouter {
  CommandHandle issue(Principal who, int instanceId, CommandType type, Map<String,String> args);  // 권한·TTL·동시성 검사
  void onResult(int instanceId, CommandResult chunk);
}

public interface ConfigChangeService {
  ConfigChange propose(Principal who, int appId, Set<Integer> instances, Map<String,String> values, String reason);
  ConfigChange apply(UUID changeId);        // ConfigPush 전송, 인스턴스별 결과 수집
  ConfigChange revert(Principal who, UUID changeId, String reason);
  EffectiveConfig effective(int instanceId);  // 출처(file/env/push)별
}

public interface MetricProvider {           // 알림 평가용
  OptionalDouble value(RuleTarget target, MetricRef metric, Duration window, Instant at, TransactionKind kind);
  long sampleCount(RuleTarget target, Duration window, Instant at, TransactionKind kind);
}

public interface AuditService {
  void record(AuditEvent event, Actor actor, Target target, Result result, Map<String,Object> before, Map<String,Object> after);
}

public interface EnvelopeCipher {
  SecretField encrypt(String plaintext, String aad);     // {key_id, nonce, ciphertext}
  String decrypt(SecretField f, String aad);
}
```

## 3. 수집 파이프라인

### 3.1 gRPC 서버

| 항목 | 값 |
|---|---|
| 구현 | grpc-java Netty 서버, 포트 `argos.collector.port=9100`, 별도 `NettyServerBuilder` |
| 스레드 | boss 1, worker = 코어 수, 호출 executor 고정 8(`argos-grpc-exec-N`). 핸들러는 WAL append까지만 하고 반환하므로 짧다 |
| TLS | `argos.collector.tls.{enabled,cert-file,key-file,ca-file(mTLS 1.x)}`. 암호군 허용 목록 고정(11 문서 2장). 인증서 파일 변경 감지 시 `SslContext` 리로드(`CertReloader`, 30초 폴링) |
| 상한 | `maxInboundMessageSize = argos.collector.max-message-bytes(4 MiB)`, `maxInboundMetadataSize 8 KiB`, `maxConcurrentCallsPerConnection 8`, `permitKeepAliveTime 60s`, `handshakeTimeout 10s`, `maxConnectionIdle 10m` |
| 압축 | gzip 디코더 등록. 해제 후 크기는 `maxInboundMessageSize`로 제한(가정 — 15장 1번). 안전망으로 `BoundedInputStream` 래퍼가 해제 바이트를 세어 초과 시 즉시 `RESOURCE_EXHAUSTED` |
| 리플렉션·헬스 | reflection 미등록. gRPC health 서비스는 미인증 허용(`SERVING`만) |

인터셉터 체인(순서 고정):

| 순서 | 인터셉터 | 동작 | 실패 코드 |
|---|---|---|---|
| 1 | `IpRateLimitInterceptor` | 원격 IP별 인증 실패 카운터(분당 20 → 10분 차단). 차단 IP는 즉시 거부 | `RESOURCE_EXHAUSTED` |
| 2 | `TokenAuthInterceptor` | `authorization` 파싱 → `argos_` 접두 검사 → SHA-256 → `TokenCache.get(hash)`(60초, 폐기 이벤트로 즉시 무효) → 만료·상태 검사. `TokenRecord`를 Context에 | `UNAUTHENTICATED` |
| 3 | `SessionInterceptor` | 데이터 RPC만: `argos-instance-id`·`argos-session-id` → `SessionRegistry.find` → 세션 토큰 id == 요청 토큰 id, session_id 일치. `Session` RPC는 통과 | `FAILED_PRECONDITION(SESSION_REQUIRED)` / `PERMISSION_DENIED` |
| 4 | `QuotaInterceptor` | 인스턴스별 토큰 버킷(트랜잭션 2,000/s, step 20,000/s, 메트릭 1,000/5s, 사전 10,000/h). 메시지 전체가 아니라 항목 단위로 초과분을 계산해 Context에 "허용 개수"를 둔다(3.3) | — |
| 5 | `MetricsInterceptor` | `argos_collector_received_total{type}`, 지연 히스토그램 | — |

### 3.2 AgentServiceImpl

```java
public Ack sendTransactions(TransactionBatch b) {
  var s = SessionContextHolder.get();
  limits.check(b);                                   // 3.3. 위반 → RESOURCE_EXHAUSTED/INVALID_ARGUMENT
  var key = DedupKey.of(s.instanceId(), b.getHeader().getAgentEpoch(), WalRecordType.TX, b.getHeader().getSeq());
  if (dedup.seen(key)) return Ack.duplicate(dedup.lastSeq(key.type()));            // D-13
  int allowed = quota.allow(s.instanceId(), QuotaKind.TX, b.getRecordsCount());      // 3.3
  TransactionBatch kept = allowed == b.getRecordsCount() ? b : trim(b, allowed);
  WalPosition pos;
  try { pos = wal.append(WalRecordType.TX, s.instanceId(), key.epoch(), key.seq(), kept.toByteArray()); }
  catch (WalFullException e) { metrics.dropped("wal_full", b.getRecordsCount()); return Ack.ok(dedup.lastSeq(key.type())).withDropped(b.getRecordsCount()); }  // DSN-11-09
  dedup.mark(key, pos);
  ingestExecutor.execute(() -> pipeline.onTransactions(s, pos, kept));              // ack는 기다리지 않는다
  return Ack.ok(key.seq()).withDropped(b.getRecordsCount() - allowed).withThrottle(throttle.hint(s));
}
```

- ack는 **WAL fsync 완료 직후** 반환한다. 디코드·적재는 비동기다. 따라서 "ack = 유실 없음"이 WAL 내구성에 의해 보장된다(D-13).
- `SendActiveSnapshot`은 WAL을 거치지 않는다(휘발 데이터). 세션 메모리의 최신 스냅샷을 교체하고 실시간 버스에 publish한다.
- 중복 제거 윈도: `DedupWindow`는 (instance_id, epoch, type)별로 최근 1,000개 seq를 비트셋+링으로 보관하고 `last_seq`를 기억한다. 서버 재시작 시 `WalRecovery`가 WAL에서 (instance, epoch, type, seq)를 복원한다(3.4).

### 3.3 상한·쿼터 (SEC-11·12·13·14)

| 검사 | 값(설정 키) | 위치 | 위반 시 |
|---|---|---|---|
| 메시지 크기(해제 후) | `argos.collector.max-message-bytes` 4 MiB | gRPC + BoundedInputStream | `RESOURCE_EXHAUSTED` 디코드 전 |
| 배치 항목 수 | tx 5,000 / step 50,000 / series 500 / sql 5,000 / ext 1,000 / active 500 / dictionary 1,000 | `LimitsChecker` 디코드 후 | `RESOURCE_EXHAUSTED` |
| 문자열 길이 | detail 4 KiB, 일반 2 KiB, url_path 2 KiB, error_message 1 KiB, user_id 128, header 10×1 KiB, SQL_TEXT 64 KiB, 사전 일반 8 KiB | `LimitsChecker` | `RESOURCE_EXHAUSTED` |
| 반복 길이 | frame_ids 30/64, sql_ids 64, headers 10, args 8 | `LimitsChecker` | `RESOURCE_EXHAUSTED` |
| 형식 | 해시 0, trace_id 길이 ≠ 16, enum 범위 밖 필수값, ts < now − 7d | `LimitsChecker` | `INVALID_ARGUMENT` |
| 처리량 쿼터 | 인스턴스당 tx 2,000/s, step 20,000/s, metric 1,000/5s (`argos.collector.quota.*`) | `QuotaInterceptor` 토큰 버킷 | 초과 항목만 제거, `ACK_PARTIAL_DROP`, `argos_collector_dropped_total{reason="quota"}` |
| 사전 폭증 | 인스턴스당 시간당 신규 10,000, 앱당 총 100만 | `DictionaryResolver` | `<overflow>` 매핑 + 경고 메트릭 |
| 인스턴스 상한 | 앱당 1,000, 시간당 신규 100 | `SessionHandler` | `INSTANCE_LIMIT` |
| 세션 레이트 | 토큰당 동시 100, 분당 신규 60 | `SessionHandler` | `RATE_LIMITED`, `retry_after 60` |

쿼터로 잘린 항목은 배치 뒤쪽부터 제거한다(오래된 것이 앞에 있으므로 최신을 버리는 셈이지만, 쿼터는 비정상 상황이고 단순성이 우선). 잘린 수는 Ack와 메트릭에 기록되어 D-06 패널에 "서버 드롭(쿼터)"로 보인다.

### 3.4 WAL

목적: ack 전에 내구성을 확보하고(D-13), ClickHouse 장애 중 스풀 역할을 하며, 재시작 후 중복 제거 상태와 미적재 블록을 복원한다.

| 항목 | 설계 |
|---|---|
| 위치 | `argos.wal.dir`(기본 `data/wal`), 디렉터리 0700 |
| 세그먼트 | `seg-<id 8자리>.wal`, 256 MiB에서 회전(`argos.wal.segment-bytes`). 파일 헤더 16B: magic `ARGW`, format version u16(=1), flags u16, created_ms u64 |
| 레코드 | `[len u32][crc32c u32][type u8][instance_id u32][epoch u64][seq u64][received_ms u64][payload]`. `type`: TX=1, PROFILE=2, METRIC=3, STAT=4, BLOCK_INTENT=16, BLOCK_COMMIT=17 |
| 쓰기 | 단일 `argos-wal-writer` 스레드. 호출자는 `append()`에서 `CompletableFuture`로 fsync 완료를 기다린다. 그룹 fsync: 10ms(`argos.wal.fsync-interval-ms`) 또는 4 MiB 누적 중 먼저. fsync는 `FileChannel.force(false)` |
| 상한 | `argos.wal.max-bytes` 10 GiB. 초과 시 `WalFullException` → 서버 드롭(wal_full). 80%에서 경고 메트릭·이벤트 |
| 회수 | 세그먼트의 모든 레코드가 `BLOCK_COMMIT`로 덮이면(3.8) 세그먼트 삭제. 삭제는 `argos-wal-gc` 1분 주기 |
| 블록 인덱스 | 적재 블록 경계를 WAL 자체에 `BLOCK_INTENT{table, first_pos, last_pos, token}`·`BLOCK_COMMIT{token}` 레코드로 남긴다(3.8). 별도 파일 없음 |
| 복구 | 기동 시 `WalRecovery`: 세그먼트 순회 → crc 불일치 레코드에서 그 세그먼트 뒤를 절단(마지막 부분 쓰기) → (instance, epoch, type, seq)로 `DedupWindow` 복원 → INTENT만 있고 COMMIT 없는 블록은 **같은 token으로 재삽입** → INTENT 뒤의 데이터 레코드는 새 블록으로 적재 → 모두 끝난 뒤 수신 시작. 복구 시간 상한 없음(진행률 로그). 10 GiB 기준 수 분 |
| 디스크 | WAL 디스크는 ClickHouse 디스크와 분리 권장(17 문서). 같은 디스크면 비상 보존 단축(F-S23)이 WAL 여유도 계산에 포함 |

### 3.5 디코드·정규화 (`IngestPipeline`)

`argos-ingest-N`(4 스레드) 풀에서 배치 단위로 실행한다. 순서:

1. **디코드** 후 세션 컨텍스트(app_id, env, instance_id, 모드, Apdex T)를 붙인다.
2. **사전 해석**(`DictionaryResolver`): 레코드의 해시가 캐시에 없으면 `unresolved` 집합에 넣고 `DictionaryRequest`를 예약(100ms 묶음, 3회 상한). 적재는 기다리지 않는다(DSN-11-06). PG upsert는 `argos-dict-writer` 1 스레드가 500건/500ms 배치로 `INSERT … ON CONFLICT (app_id, kind, hash) DO UPDATE SET last_seen = now()`.
3. **타임스탬프 가드**(D-15): `ts > received + 5s` → `received`로 보정 + `argos_ts_clamped_total`. `ts < received − 7d` → 레코드 거부 + `argos_ts_rejected_total`(`INVALID_ARGUMENT`는 배치 전체가 아니라 레코드 단위로 적용하기 위해 디코드 후 처리). `received − ts > 60s` → `late = true`(실시간 미발행) + `argos_late_arrivals_total`.
4. **에러 그룹 지문**(F-S19, `ErrorGroupFingerprinter`):
   - 입력: `error_class_id`의 사전 텍스트(없으면 해시 문자열), `error_message`(에이전트 마스킹분에 서버 기본 마스킹 세트를 한 번 더 적용 — SEC-36 이중 안전), `error_top_frame_id` 텍스트.
   - 메시지 정규화: 숫자 연속 → `#`, 따옴표 문자열 → `'?'`, 16진 8자 이상·UUID → `?`, 공백 압축, 256자 절단.
   - `error_group_id = xxh3_64(class + "\n" + normalized + "\n" + top_frame)`. `error_groups`(PG)에 upsert(first/last_seen, count_total 증가, message_pattern). 대표 스택은 ERROR step이 있는 첫 프로파일에서 1회 채운다.
5. **Apdex T 기록**(D-17): 서비스별 T(기본 앱 T, `apps.apdex_t_ms`·`service_overrides`)를 조회해 `apdex_t_ms` 컬럼에 쓴다.
6. **env·kind·version**: env는 세션에서, kind·version_id는 레코드에서. `app_version_id = 0`이면 인스턴스 메타의 버전 해시.
7. **IP 정규화**: 4B → IPv4-mapped IPv6로 변환해 `IPv6` 컬럼에. 빈 값은 `::`.
8. **실시간 publish**(3.7): `late = false`인 XLog 점·카운터만.
9. **행 변환** → `ClickHouseWriter.enqueue(table, pos, rows)`.

### 3.6 전수 통계 적재 (F-S21)

`StatBatch`는 그대로 `sql_stat_raw`·`ext_stat_raw` 행이 된다(window_start를 `ts`로). `CounterDelta`는 서버가 `argos_agent_*` 메트릭으로 변환해 `metric` 테이블에 5초 격자로 쓴다(인스턴스 라벨). SQL 통계·외부 호출 통계 화면은 이 테이블과 그 롤업만 읽는다(D-12). `kind`별로 분리 저장해 D-20을 만족한다.

### 3.7 실시간 버스

| 항목 | 설계 |
|---|---|
| 구현 | Reactor `Sinks.many().multicast().onBackpressureBuffer(1024)` 토픽별. 토픽 키 `(kind, app_id, env)`: `xlog`, `counter`, `active`, `event`, `instance_state`, `alert` |
| 묶음 | XLog 점은 100ms 윈도로 모아 `XlogPoints{points[]}` 프레임 하나로. 카운터는 5초 격자. 액티브는 스냅샷 교체 |
| 백프레셔 | 구독자별 버퍼 256 프레임. 넘치면 가장 오래된 프레임을 버리고 `dropped` 카운터를 다음 프레임에 실어 UI가 "갱신 중단 N초"를 표시 |
| 늦은 행 | `late = true`(60초 이상) 행은 발행하지 않는다(D-15) |
| 실시간 TPS | 도착 시각이 아니라 `ts` 버킷(1초)으로 집계한 카운터를 발행한다 |

WebSocket(`/ws`, `WebSocketHandler`):

| 항목 | 규칙 |
|---|---|
| 인증 | 핸드셰이크에서 `argos_at` 쿠키 검증 + `Origin` == 서버 자신(설정 `argos.web.public-url`). 실패 시 close 4401. 토큰 없는 쿼리스트링 인증은 없다(SEC-44) |
| 프레임 | JSON 텍스트. 클라이언트 → `{op:"sub", id, topic:"xlog", app, env, kind}` / `{op:"unsub", id}` / `{op:"ping"}`. 서버 → `{op:"data", id, t, payload, dropped}` / `{op:"err", id, code}` / `{op:"pong"}`. 상세 스키마는 14 문서 |
| 상한 | 연결당 구독 20, 수신 메시지 4 KiB, 사용자당 연결 5 |
| 세션 폐기 | `SessionCookieService`의 폐기 이벤트를 구독해 해당 사용자 연결을 1초 내 close 4401 |
| 권한 | 1.0은 역할 검사만(조회자 이상). 1.x 앱 권한은 `SubscriptionManager.authorize()` 한 지점 |

### 3.8 ClickHouse 배치 라이터

| 항목 | 설계 |
|---|---|
| 큐 | 테이블별 `ArrayBlockingQueue<PendingRows>`(상한 200,000행). 테이블: xlog, profile_step, metric, sql_stat_raw, ext_stat_raw |
| 플러시 | 테이블당 1 스레드(`argos-ch-writer-<table>`). 2초(`argos.writer.flush-interval-ms`) 또는 50,000행 중 먼저 |
| 블록 | 플러시 대상 행들의 WAL 위치 범위 `[first_pos, last_pos]`를 계산 → `BLOCK_INTENT{table, first, last, token}` WAL 기록(fsync) → `INSERT INTO <table> (col, …) FORMAT RowBinaryWithNamesAndTypes` + `SETTINGS insert_deduplication_token = token, deduplicate_blocks_in_dependent_materialized_views = 1, async_insert = 0` → 성공 시 `BLOCK_COMMIT{token}` 기록 |
| 토큰 | `token = "wal:" + table + ":" + first.segment + ":" + first.offset + "-" + last.segment + ":" + last.offset`. 재시작 복구 시 같은 범위로 재구성되므로 같은 토큰이 나온다(DSN-02-12) |
| 실패 | insert 예외 → 지수 백오프(1s → 30s) 재시도, 같은 토큰. 큐가 차면 `IngestPipeline`이 블록되지 않도록 `enqueue`는 즉시 반환하고 WAL에만 남긴다(WAL = 스풀). ClickHouse 복구 후 `WalReplayer`가 INTENT 없는 레코드부터 다시 블록을 만든다 |
| 컬럼 | 항상 명시 컬럼 목록. 스키마에 새 컬럼이 추가돼도 구버전 서버가 깨지지 않는다(DS-27) |
| 사용자 | `argos_writer`(INSERT만). 조회 경로는 `argos_reader` |
| 메트릭 | `argos_writer_rows_total{table}`, `argos_writer_inserts_total{table,result}`, `argos_writer_queue_rows{table}`, `argos_ingest_lag_seconds`(수신 → insert ack, 히스토그램 p50/p99) |

### 3.9 실패 모드

| 상황 | 동작 | 사용자 가시성 |
|---|---|---|
| ClickHouse 다운 | 라이터 재시도, WAL 누적(≤ 10 GiB), ack는 계속 OK. 복구 시 자동 재적재 | 적재 지연 메트릭·알림, 수집 상태 패널 "적재 지연", 대시보드 최근 구간 공백 → 복구 후 채워짐 |
| WAL 디스크 풀 / 10 GiB | 서버 드롭(wal_full), `ACK_OK + dropped` | D-06 패널 "서버 드롭", 심각 알림, 수집 중단 음영(D-18) |
| PostgreSQL 다운 | 수집은 계속(사전 upsert·세션 등록 캐시로 버팀), 새 세션 수락은 토큰 캐시 적중 시만, UI 로그인·관리 불가 | 서버 자기 알림, UI 에러 배너 |
| 토큰 캐시 미스 + PG 다운 | Hello 거부 `UNAVAILABLE retry_after 30` | 에이전트 재접속 백오프 |
| 알림 채널 실패 | 재시도 3회 → 다른 채널로 자기 알림(F-S27) | 알림 이력 전송 결과, 관리 배지 |
| 서버 종료 | `SessionClose{SERVER_SHUTDOWN}` 브로드캐스트 → gRPC `shutdown` 10초 drain → WAL 플러시 → 라이터 마지막 플러시 시도(5초) → 종료. 미적재분은 WAL에 남아 재기동 시 복구 | 재기동 후 공백 없음(수집 중단 구간은 에이전트 큐 초과분만) |
| 디코드 예외 | 레코드 단위 skip + `argos_ingest_errors_total{stage}`. 배치 전체를 버리지 않는다 | 수집 상태 패널 |

## 4. 세션 레지스트리·인스턴스 상태

### 4.1 레지스트리

- `ConcurrentHashMap<Integer, AgentSession>`(instance_id → 세션). `AgentSession`: 토큰 id, app_id, env, session_id, 제어 스트림 `StreamObserver<ServerMessage>`(동기화된 send), 마지막 heartbeat, 시계 오프셋 이력(5), 최신 액티브 스냅샷, 유효 설정 해시, `remote_config_allow`, 모드 필드, 에이전트 버전, 상태.
- 같은 (app_id, instance_name) 재접속 시 기존 세션에 `SessionClose{REPLACED}`를 보내고 교체한다(11 문서 5장 7번).
- 토큰 폐기 이벤트(`TokenRevoked`) → `closeByToken` → 해당 세션 전부 `SessionClose{TOKEN_REVOKED_CLOSE}` + 스트림 `onError(UNAUTHENTICATED)` + 레지스트리 제거(SEC-04, 5초 이내).

### 4.2 인스턴스 상태 머신 (F-S28)

| 상태 | 진입 조건 | 이탈 |
|---|---|---|
| UP | Hello 수락, heartbeat 정상(30초 이내), WARN 조건 없음 | — |
| STALE | 마지막 heartbeat로부터 **60초**(= 주기 30초 × 2) 경과, `AgentBye` 없음 | heartbeat 수신 → UP, 2분 더 경과 → DOWN |
| DOWN | STALE 2분 경과(총 3분 무응답) **또는** `AgentBye` 후 5분 내 미재접속 | 재접속 → UP |
| WARN | UP이면서 다음 중 하나: `|clock_offset| ≥ 1,000ms`, 최근 5분 드롭 > 0(에이전트·서버), 에이전트 버전이 호환 범위 하한·`MODULE_DISABLED`·`KILL_SWITCH_ON` 이벤트, 설정 해시가 앱 내 다수와 불일치(drift) | 조건 해소 → UP |

- 상태 전이는 `events` 테이블에 `type = state`로 기록하고 실시간 버스 `instance_state` 토픽과 알림 엔진(`instance_down` 규칙)에 전달한다.
- `AgentBye{SHUTDOWN}`은 "정상 종료" 이벤트를 남기고 5분 유예 타이머를 건다. 유예 안에 재접속하면 다운 알림이 발화하지 않는다(S-17).
- 상태 평가는 `argos-session-monitor` 1 스레드가 10초마다 전체 세션을 훑는다.

### 4.3 인스턴스 이름·보관

- 이름 우선순위: 설정 `app.instance` → `InstanceUpdate.instance_name`(`host:port`) → Hello의 `host:pid`. 포트 확정 전에 들어온 데이터는 같은 instance_id로 이어진다(이름만 갱신).
- `argos.instance.archive-after=7d` 미접속 → `archived = true`(목록 기본 숨김), `argos.instance.purge-after=30d` → 행 삭제(ClickHouse 데이터는 TTL에 맡김).

### 4.4 등록 정책 (F-S30)

`argos.agent.auto-register.<env>`(기본 `default: true`, 운영자가 `prod: false` 권장). off인 env의 미등록 (name, env) Hello는 `pending_registrations`에 (name, env, host, 시도 횟수, 마지막 시각) upsert 후 `APP_NOT_REGISTERED, retry_after 300`. 관리자 승인 → `apps` 생성 + 토큰 발급(원문 1회 표시) → 다음 재시도에 수락. 승인 대기 목록은 1,000건 상한(초과 시 가장 오래된 것 교체).

## 5. 알림 엔진

### 5.1 규칙 모델

```yaml
rule:
  id: uuid
  name: "p95 latency"                 # ≤ 128
  enabled: true
  env: [prod]                          # 비면 전체
  target: { type: app | instance | service | ext_host | server, ids: [...] }   # server = 자기 규칙
  kind: HTTP                           # HTTP | BATCH (D-20). server 규칙은 무시
  metric: response_time_p95            # 5.3 지표 목록
  window: 1m                           # 평가 창
  for: 5m                              # 지속 시간
  evaluation_unit: app | instance      # 인스턴스별이면 상태 머신이 인스턴스마다
  condition: { operator: ">=", warn: 3000, critical: 10000 }   # warn 생략 가능
  recover: { threshold: 2000, for: 2m }
  min_samples: 20                      # 창 안 요청 수 미만이면 평가하지 않음(요청 지표만)
  no_data: ignore | warn | critical    # 요청 지표 기본 ignore
  warmup_grace: 3m                     # 인스턴스 process_start 후 제외
  active_time: [{ days: [MON..SUN], from: "00:00", to: "24:00" }]
  mute_schedules: [{ cron: "0 2 * * *", duration: 2h, reason: "nightly batch" }]
  channels: { warn: [ch-slack-ops], critical: [ch-slack-ops, ch-webhook-sms] }
  renotify_interval: 30m
  runbook_url: "https://..."
  message_template: "..."              # 허용 변수만(SEC-39)
  template_id: "p95_latency"           # 기본 템플릿에서 만든 경우(F-S24 차이 표시용)
```

저장은 PG `alert_rules`(JSONB `spec` + 인덱스 컬럼 app·env·enabled). 변경 이력은 `alert_rule_revisions`. 스키마 검증은 JSON Schema(14 문서가 공개).

### 5.2 지표 공급 (`MetricProvider`)

| 공급자 | 지표 | 소스 | 지연 |
|---|---|---|---|
| `WindowProvider` | 요청 지표(TPS, p50/p95/p99, 에러율, Apdex, 외부 호스트별 p95·에러·타임아웃 비율, 배치 경과) | 수집 파이프라인이 유지하는 메모리 윈도: (app, env, kind, [service|instance|host]) × 1초 버킷 15분 링. 백분위는 창 안 샘플의 `HdrHistogram` 병합 | 실시간 |
| `CounterProvider` | 힙·GC·스레드·Tomcat·풀·시스템·에이전트 드롭 | 최근 15분 메모리 링(5초 격자) | 실시간 |
| `RegistryProvider` | 인스턴스 상태, 액티브 수, 에이전트 버전 | `SessionRegistry` | 실시간 |
| `ClickHouseProvider` | window > 15분인 모든 지표 | `*_1m` 롤업 쿼리(`argos_reader`) | 적재 지연 포함 |
| `SelfProvider` | 서버 자기 지표(적재 지연, 디스크, parts, 채널 실패, 인증서·토큰 만료) | Micrometer 레지스트리 | 실시간 |

### 5.3 지표 목록 (F-S31)

`response_time_p50|p95|p99`, `tps`, `error_rate`, `apdex`, `active_transactions`, `active_ratio_to_max_threads`, `jvm_heap_used_ratio`, `jvm_old_gen_after_gc_ratio`(가능 시), `jvm_gc_pause_ratio`(pause만, D-25), `jvm_threads_live`, `tomcat_threads_usage_ratio`, `jdbc_pool_waiting`, `jdbc_pool_usage_ratio`, `ext_call_p95{host}`, `ext_call_error_ratio{host}`, `ext_call_timeout_ratio{host}`, `batch_elapsed_seconds{job}`, `system_cpu_ratio`(컨테이너 한도 기준), `system_memory_ratio`, `system_disk_usage_ratio`, `argos_agent_dropped_total`, `argos_server_dropped_total`, `argos_server_ingest_lag_seconds`, `argos_server_disk_usage_ratio`, `argos_notification_failures_total`, `argos_server_tls_cert_expiry_days`, `agent_token_expiry_days`, `instance_state`(DOWN 판정), `error_group_new`(S).

### 5.4 평가 루프와 상태 머신

- `argos-alert-eval` 1 스레드, 10초 주기(`argos.alert.evaluation-interval`). 규칙 × 평가 단위(앱 또는 인스턴스)마다 `AlertState`를 메모리에 두고 PG `alert_states`에 전이만 기록한다.
- 평가: 활성 시간대·음소거 일정 밖이면 skip(상태 유지). 대상 인스턴스가 `warmup_grace` 안이면 skip. `min_samples` 미만이면 skip(요청 지표). 값 없음 → `no_data` 정책.
- 전이:

```
OK ──(조건 참)──▶ PENDING ──(for 지속)──▶ FIRING ──(ACK)──▶ ACKED
 ▲                  │(조건 거짓)             │                    │
 └──────────────────┘                        │(recover 조건 recover.for 지속)
 ◀────────────── RESOLVED ◀──────────────────┴────────────────────┘
어느 상태 ──(no_data=warn|critical)──▶ NO_DATA ──(데이터 복귀)──▶ 이전 상태 재평가
```

- 2단계: `critical` 충족이면 심각, 아니고 `warn` 충족이면 경고. FIRING 중 심각도 상승은 즉시 재통지, 하강은 다음 renotify 때 반영.
- 복구 임계치(히스테리시스): `recover.threshold`를 `recover.for` 동안 만족해야 RESOLVED. 없으면 `condition` 거짓이 `for/2` 지속.
- 재알림: FIRING 유지 중 `renotify_interval`마다. ACKED면 중지, RESOLVED는 항상 전송.
- NO_DATA: 요청 지표는 기본 ignore(저트래픽 오탐 방지). `instance_state` 규칙은 상태 머신이 아니라 레지스트리 전이 이벤트로 직접 FIRING/RESOLVED.
- 그룹핑: `Grouper`가 (app, env, severity) 단위로 30초(`argos.alert.group-window`) 모아 한 통지로 합친다(최대 20건, 초과는 "외 N건").

### 5.5 통지

| 구성 | 설계 |
|---|---|
| 템플릿 | 변수: `app`, `env`, `instance`, `service`, `host`, `metric`, `value`, `threshold`, `severity`, `duration`, `started_at`(서버 TZ + UTC 오프셋), `link`(딥링크, 발화 시각 ±15분 고정, 필터 적용), `runbook`, `rule`. 그 외 변수 참조는 저장 시 400(SEC-39). 수집 원문은 변수로 제공하지 않는다 |
| 채널 | `EmailChannel`(SMTP STARTTLS 기본·인증서 검증, 끄기는 플래그+WARN), `SlackChannel`(Incoming Webhook, 목적지 검증 적용), `WebhookChannel`(JSON `{schema_version:1, alert:{…}, link}`, `X-Argos-Signature: t=<unix>,v1=<hmac-sha256(t + "." + body)>`, 채널별 서명 키) |
| 목적지 검증(SEC-51) | `DestinationValidator.validate(url)`: ① 스킴 https(또는 `allow-http`) ② 호스트의 모든 A/AAAA 해석 ③ 하나라도 거부 대역(127/8, ::1, 10/8, 172.16/12, 192.168/16, 169.254/16, fe80::/10, fc00::/7, 224/4, ff00::/8, 169.254.169.254, 서버 자신의 모든 주소)이면 거부, 단 `allow-cidrs`에 있으면 통과 ④ 전송은 해석한 IP에 고정 연결(`Host` 헤더 유지, 커스텀 `Dns` 리졸버) ⑤ 리다이렉트 미추종 ⑥ 타임아웃 연결 2초·읽기 3초 ⑦ 응답 본문 폐기, 상태 코드만 기록. 저장·테스트 전송·실제 전송 모두 같은 경로 |
| 재시도·실패 | 전송 실패 3회(1s·5s·30s) 후 `notification_deliveries{status=failed}`. 채널 연속 실패 3회 → 채널 상태 `failing` + 다른 활성 채널 전부로 자기 알림 `channel_failure`(심각) + 관리 배지(F-S27) |
| 비밀값 | 웹훅 URL 전체·SMTP 비밀번호·서명 키는 `EnvelopeCipher`로 암호화 저장, API는 `https://hooks.slack.com/****`로 마스킹 반환(SEC-52) |
| 이력 | `alert_events`(전이), `notification_deliveries`(채널별 결과·응답 코드·재시도 수). 1년 보존 |

### 5.6 기본 템플릿 (F-S24)

`DefaultTemplates`는 01 문서 부록 A의 14종을 코드 상수(JSON)로 가진다. 앱 등록 시 `apply_recommended_rules = true`(기본)면 앱 범위 규칙을 생성한다(`template_id`, `template_version` 기록). 서버 자기 규칙(디스크·적재 지연·채널 실패·인증서·토큰 만료)은 최초 기동 시 1회 생성. 템플릿 버전이 오르면 관리 화면이 "템플릿과 차이"를 표시하고 운영자가 갱신을 선택한다(자동 덮어쓰기 없음). 배치 템플릿은 앱에 `kind=BATCH` 트랜잭션이 처음 관측될 때 생성한다.

## 6. 명령·설정 변경·킬 스위치

### 6.1 명령 라우팅 (`CommandRouter`)

1. `issue()`: 권한 검사(운영자: THREAD_DUMP·THREAD_STACK·SELF_DIAGNOSTICS, 관리자: CONFIG_RELOAD·ENV_DUMP) → 인스턴스 세션 존재 → 인스턴스당 동시 1건(실행 중이면 409) → 분당 10건 → `commands` 행(UUID, 요청자, type, args, issued_at, ttl 60s, status QUEUED) → 감사 → 세션 스트림으로 `Command` 전송 → status SENT.
2. 에이전트 `CommandResult` 청크 수신 → 조립(≤ 1 MiB) → `EnvelopeCipher.encrypt(payload, aad = command_id)` → `command_results` 저장 → status DONE/FAILED/EXPIRED/COOLDOWN → 감사 → 실시간 `event` 토픽.
3. TTL 만료(서버 타이머 60초 + 10초 여유)면 EXPIRED. 세션이 끊기면 QUEUED/SENT 명령은 EXPIRED.
4. 결과 조회(`GET /commands/{id}`)는 요청자 본인 또는 관리자(SEC-50). 열람은 감사(`COMMAND_RESULT_VIEWED`). 보존 30일(`argos.retention.command-results`) 후 삭제.

### 6.2 설정 변경 통제 (`ConfigChangeService`, F-S29)

- `propose()`: 사유 필수(≤ 1 KiB), 대상(앱 전체 또는 인스턴스 집합), 값 집합. 검증: 키는 서버 측 스키마(허용 키 목록·타입·범위)에 있어야 하고, **대상 인스턴스들의 `remote_config_allow` 교집합**에 있어야 한다(밖이면 400 + 어느 인스턴스가 불허인지). diff = 현재 서버 보관 값 vs 새 값. `config_changes` 행(status PROPOSED).
- `apply()`: 서버 보관 값(`app_config`) 갱신 → 접속 중 인스턴스마다 `ConfigPush{change_id}` → `ConfigApplyResult`를 `config_change_results`(instance_id, applied, rejected, hash, duration)에 기록 → 미접속 인스턴스는 `pending`으로 두고 다음 Hello의 `ServerHello.config`로 전달 → 10초 내 결과 집계가 UI 목표(N-25). 감사 `CONFIG_PUSHED`(키·전후값).
- `revert()`: 해당 change의 이전 값 세트를 새 change(`revert_of`)로 `apply()`.
- `effective()`: 인스턴스의 Hello/Update `config_hash`와 마지막 ApplyResult의 키별 출처(file/env/push)를 합쳐 반환. 앱 내 해시 불일치는 WARN(drift).
- 킬 스위치(`KillSwitchService`): `argos.enabled=false` 푸시의 특수 경로. 관리자 전용, 확인 사유, 인스턴스별 결과, 감사 `KILL_SWITCH`. 되돌리기 = `argos.enabled=true`.

## 7. 인증·세션·권한·토큰

### 7.1 에이전트 토큰 (`TokenService`, SEC-01~06)

| 동작 | 구현 |
|---|---|
| 발급 | `SecureRandom` 32B → base64url(43자) → 원문 `argos_<43자>`. 저장: `token_hash = SHA-256(원문 전체)` hex, `prefix = 원문 앞 14자`("argos_" + 8), app_id, 발급자, `expires_at`(기본 null, UI 기본 365일), status ACTIVE. 원문은 응답에 1회. 앱당 활성 토큰 ≤ 5 |
| 조회 | `TokenCache`(Caffeine, 60초, 최대 10,000) hash → `TokenRecord`. 미스 시 PG. 폐기·만료 변경 이벤트로 즉시 `invalidate(hash)` |
| 회전 | 새 토큰 발급 + 구 토큰 `expires_at = now + grace(7d)`, status ROTATING. 둘 다 유효. 만료 시 자동 REVOKED |
| 폐기 | status REVOKED → 캐시 무효 → `SessionRegistry.closeByToken` → 감사 `TOKEN_REVOKED`. 5초 내 세션 종료(테스트 기준) |
| 레이트 | 토큰당 동시 세션 100, 분당 신규 60(`argos.collector.session.*`), IP당 인증 실패 분당 20 → 10분 차단 |
| 만료 경고 | `argos_agent_token_expiry_seconds{token_prefix}` + 기본 규칙(30일 경고·7일 심각) |
| 로그 | 토큰 원문은 어떤 로그에도 쓰지 않는다. `prefix`만. Error Prone 커스텀 체크 + 로그 appender 정규식 `argos_[A-Za-z0-9_-]{43}` → `argos_****` 치환(최후 안전망) |

### 7.2 사용자 인증·세션 (SEC-40~48)

| 항목 | 구현 |
|---|---|
| 로그인 | `POST /api/v1/auth/login` {username, password} → bcrypt(cost 12) 검증 → `user_sessions` 행(id UUID, user_id, refresh_hash, created, last_seen, expires_abs = +24h, ua, ip) → 쿠키 설정 → 감사 `LOGIN_SUCCESS`. 실패 → `LOGIN_FAILURE`, 카운터, 10회 → 잠금 15분(누진 30·60분, `locked_until`), 관리자 해제 |
| 쿠키 | `argos_at`: JWT(EdDSA, 서버 키 `conf/jwt.key` 자동 생성) 15분, claims `sub, sid, role, iat, exp`. `argos_rt`: 불투명 32B, Path `/api/v1/auth/refresh`, 8시간 비활동·24시간 절대. 둘 다 `HttpOnly; Secure; SameSite=Strict`. `argos_csrf`: 비-httpOnly 랜덤 32B |
| 검증 | 매 요청: `argos_at` 서명·만료 → `sid`의 세션이 유효한지 `SessionStatusCache`(5초) 확인(폐기·역할 변경 즉시 반영) → `role`은 세션 레코드의 현재 값으로 덮어쓴다(JWT 클레임은 힌트) |
| 리프레시 | `POST /auth/refresh`: `argos_rt` 해시 조회 → 유효하면 새 rt 발급(회전), 구 rt는 `rotated_from`에 보관 → 재사용 탐지(이미 회전된 rt 제시) 시 그 사용자의 세션 전부 폐기 + 감사 `SESSION_REUSE_DETECTED` |
| 무효화 | 로그아웃·비밀번호 변경·역할 변경·관리자 강제 종료·계정 비활성화 → 세션 행 `revoked_at` + 이벤트 → WS 종료(3.7) |
| 동시 세션 | 사용자당 5. 초과 시 가장 오래된 세션 폐기 |
| CSRF | 상태 변경 메서드에 `X-Argos-CSRF` 헤더 == `argos_csrf` 쿠키(더블 서브밋) **그리고** `Origin`(없으면 `Sec-Fetch-Site`) 검사. 로그인 성공 시 csrf·sid 재발급(세션 고정 방지) |
| 비밀번호 | 12~128자, 금지 목록(흔한 비밀번호 1만, 사용자명 포함), 복잡도 규칙 없음, 최초 로그인 강제 변경(`must_change_password`) |
| 설정 토큰 부트스트랩(SEC-47) | 기동 시 `users`가 비어 있으면 32B 토큰 생성 → 서버 로그 1줄 + `conf/setup-token`(0600) → `GET/POST /api/v1/setup/*`는 `X-Argos-Setup-Token` 일치 시만. 30분 또는 관리자 생성 즉시 무효(파일 삭제). `ARGOS_ADMIN_PASSWORD_FILE`이 있으면 무인 생성 후 마법사 비활성 |
| 상황판(F-U21) | 조회자 계정에 관리자가 `kiosk_allowed_cidrs`·`kiosk_max_session = 7d`를 설정하면 그 IP에서의 세션만 절대 만료를 7일로. 감사 `KIOSK_SESSION_GRANTED` |

### 7.3 권한 매트릭스 (`RoleMatrix`, SEC-49·50)

- 컨트롤러 메서드에 `@RequiresRole(ADMIN|OPERATOR|VIEWER)` 필수. OpenAPI 생성기가 이를 `x-argos-role`로 내보내고, 스펙 린트가 누락을 실패시킨다. `RoleMatrix`는 기동 시 핸들러 매핑을 훑어 미주석 엔드포인트가 있으면 **기동 실패**.
- 관리자 전용: 사용자·역할, 토큰, 앱 삭제, 채널 URL·비밀값, ENV_DUMP·CONFIG_RELOAD, 설정 변경, 킬 스위치, `pii.view`(바인드 파라미터 full), 감사 로그, 삭제 API, 보존·개인정보 설정. 운영자: 조회 + THREAD_DUMP·THREAD_STACK·SELF_DIAGNOSTICS + 규칙·음소거·ACK + 채널 선택. 조회자: 조회, CSV(감사).
- 객체 수준: `command_results`는 요청자·관리자. 1.x 앱 권한은 `AppScopeFilter` 한 지점.

### 7.4 레이트 리밋·Actuator (SEC-55·56)

- `RateLimitFilter`: 로그인 IP당 분당 20, 사용자당 API 초당 50(버스트 100), 검색·CSV 분당 10. 초과 429 + `Retry-After`. 메트릭 `argos_ratelimit_rejections_total{scope}`.
- Actuator: `health`(상세 숨김, 무인증), `prometheus`(별도 포트 `management.server.port=9101` 기본 또는 같은 포트 + 베이직/베어러 + `argos.metrics.allow-cidrs`), `info`(버전). `env`·`heapdump`·`threaddump`·`loggers`·`shutdown` 비활성.

## 8. 감사·비밀값·개인정보 삭제

### 8.1 감사 (`AuditService`, SEC-80~83)

- `AuditEvent` enum(카탈로그, 스냅샷 테스트 대상): `LOGIN_SUCCESS, LOGIN_FAILURE, ACCOUNT_LOCKED, LOGOUT, PASSWORD_CHANGED, PASSWORD_RESET, USER_CREATED, USER_UPDATED, USER_DELETED, ROLE_CHANGED, SESSION_REVOKED, SESSION_REUSE_DETECTED, KIOSK_SESSION_GRANTED, TOKEN_ISSUED, TOKEN_ROTATED, TOKEN_REVOKED, APP_CREATED, APP_DELETED, APP_APPROVED, APP_REJECTED, RULE_CREATED, RULE_UPDATED, RULE_DELETED, CHANNEL_CREATED, CHANNEL_UPDATED, CHANNEL_DELETED, CHANNEL_TEST_SENT, ALERT_ACKED, MUTE_CREATED, COMMAND_ISSUED, COMMAND_RESULT_VIEWED, CONFIG_PROPOSED, CONFIG_PUSHED, CONFIG_REVERTED, KILL_SWITCH, RETENTION_CHANGED, PRIVACY_SETTING_CHANGED, PRIVACY_DELETION_REQUESTED, PII_VIEWED, ENV_DUMP_VIEWED, THREAD_DUMP_VIEWED, CSV_EXPORTED, AUDIT_VIEWED, AUDIT_EXPORTED, SETUP_COMPLETED, MASTER_KEY_ROTATED`.
- 레코드: `ts(UTC), actor_type(user|system|agent_token), actor_id, actor_name, ip, user_agent, event, target_type, target_id, target_name, result(SUCCESS|FAILURE), reason, before(jsonb, 마스킹), after(jsonb, 마스킹), request_id`. 개인정보 열람은 `target_id`에 user_id·tx_id.
- 불변성: `audit_logs`에 대한 애플리케이션 DB 역할은 INSERT·SELECT만(DDL에서 REVOKE UPDATE/DELETE). 보존 만료 삭제는 `argos_audit_retention` 역할로 별도 잡(`argos.retention.audit=365d`). REST에 수정·삭제 엔드포인트 없음(스펙 린트).
- 싱크: PG 필수 + 선택 `syslog`(RFC 5424 UDP/TCP)·파일(JSON Lines, 롤링). 설정 `argos.audit.sinks`.

### 8.2 비밀값 암호화 (`EnvelopeCipher`, SEC-22·52)

- 마스터 키: `ARGOS_MASTER_KEY`(base64 32B) 또는 `argos.master-key-file`(기본 `conf/master.key`, 0600, 설치 시 생성). 없으면 기동 거부(설치 스크립트가 만든다).
- AES-256-GCM, 96비트 랜덤 nonce, AAD = `<table>.<column>:<row id>`. 저장 형식 `v1:<key_id>:<nonce b64>:<ciphertext b64>`.
- 대상: `notification_channels.secret`, `command_results.payload`, `webhook_signing_keys`. 복호화는 전송·열람 시점에만, 메모리 보관 최소.
- 교체: `argos-admin master-key rotate --new-key-file …` → 모든 암호문 재암호화(트랜잭션, 진행률) → `key_id` 증가 → 감사.

### 8.3 개인정보 삭제 (`DeletionJobService`, SEC-33)

- `DELETE /api/v1/admin/privacy/subjects?user_id=…|ip=…&from&to` → `privacy_jobs`(UUID, subject, 범위, status QUEUED) → 감사 → `argos-privacy-job` 스레드:
  1. `SELECT tx_id FROM xlog WHERE app_id IN (전체) AND ts BETWEEN … AND user_id = ?`(해시 모드면 입력을 같은 HMAC으로) → tx_id 목록(청크 10,000).
  2. `ALTER TABLE profile_step DELETE WHERE tx_id IN (…)` 청크별, `ALTER TABLE xlog DELETE WHERE … AND user_id = ?`.
  3. `system.mutations`를 폴링해 완료 확인 → status DONE, 삭제 행수 기록.
- 롤업·통계는 집계값이라 대상에서 제외하고 문서·응답에 명시한다. `dictionary`의 서비스 이름에 user_id가 들어간 경우는 F-A29 정규화로 예방하고, 남은 것은 관리자 사전 삭제 API.

## 9. ops

### 9.1 야간 정합 검사·재구축 (F-S22, D-14)

- `ReconcileJob`(`argos.ops.reconcile.cron`, 기본 `0 0 3 * * *` 서버 TZ): 전날 UTC 일자, 앱별로
  - D-03: `SELECT toStartOfMinute(ts) m, count() FROM xlog WHERE app_id=? AND kind=1 AND ts∈[D, D+1d) GROUP BY m` vs `SELECT minute, sum(count) FROM service_stat_1m …` vs `app_stat_1m`. 불일치 분 수 → `reconcile_results`(app, date, table, mismatch_minutes) + `argos_reconcile_mismatch_minutes{app}`.
  - D-12: `sum(xlog.sql_count)` vs `sum(sql_stat_raw.count)`(분 단위). 불일치는 "수집 유실 의심"으로 기록만(재구축 대상 아님 — 서로 다른 스트림).
  - 불일치 롤업은 `RollupRebuilder.rebuild(app, date, table)` 자동 실행.
- `RollupRebuilder`: `rollups/<table>.sql`(MV와 공유하는 SELECT)을 `INSERT INTO <table>_rebuild SELECT … WHERE toDate(minute) = D` → `ALTER TABLE <table> REPLACE PARTITION D FROM <table>_rebuild` → 바이트 동일성은 `SELECT cityHash64(groupArray(...))`로 비교 로그. CLI `argos-admin rollup rebuild --app <id> --date <d> [--table …]`.

### 9.2 저장소 상태·비상 보존 (F-S23)

- `StorageHealth` 1분 주기: `system.parts`(테이블·파티션별 활성 수), `system.merges`, `system.events`(InsertedRows, DelayedInserts, RejectedInserts), `system.disks`, `system.query_log` p99(최근 5분), `MemoryTracking`. 메트릭 `argos_ch_*`. 임계: parts/파티션 300 경고·1,000 심각, DelayedInserts > 0 경고.
- `EmergencyRetention`: ClickHouse 디스크 사용률 80% 경고 → 90%에서 `profile_step` 가장 오래된 파티션부터 `DROP PARTITION`, 그래도 90% 이상이면 `xlog`·`sql_stat_raw`·`ext_stat_raw`·`metric` 순. 롤업은 건드리지 않는다. 각 DROP은 `events{type=retention_emergency}` + 심각 알림 + 감사. 95%에서는 라이터를 멈추고 WAL만 쓴다(WAL 상한 내), 초과분 서버 드롭.

### 9.3 마이그레이션 러너 (N-22)

기동 순서: ① 설정 검증 → ② 마스터 키·JWT 키 로드 → ③ PG Flyway(`db/migration/V*.sql`) → ④ CH 러너 → ⑤ WAL 복구 → ⑥ gRPC·HTTP 리스너. CH 러너(`MigrationRunner`): `deploy/clickhouse/schema/V<n>__<name>.sql`을 번호순으로 읽어 `ch_schema_history(version, checksum, applied_at, duration_ms, success)`(PG)와 대조. 적용된 버전의 체크섬이 다르면 기동 거부(`--repair`로만 우회). 스크립트는 `;` 분리 문장 단위 실행, 문장 타임아웃 10분, 멱등(`IF NOT EXISTS`). MV 교체는 스크립트 안에서 4단계(새 MV 생성 → 백필 `INSERT … SELECT` 범위 → 읽기 전환은 테이블 이름 설정으로 → 구 MV DROP)를 따르며 각 단계가 별도 버전이다.

### 9.4 자기 메트릭 (F-S10·F-S20)

Micrometer → Prometheus 레지스트리. 전체 목록: `argos_collector_received_total{type}`, `argos_collector_rejected_total{reason}`, `argos_collector_dropped_total{reason=quota|wal_full}`, `argos_collector_unknown_enum_total{field}`, `argos_sessions_active`, `argos_instances{state}`, `argos_wal_bytes`, `argos_wal_segments`, `argos_wal_fsync_seconds`(히스토그램), `argos_ingest_lag_seconds`(히스토그램), `argos_ingest_errors_total{stage}`, `argos_ts_clamped_total`, `argos_ts_rejected_total`, `argos_late_arrivals_total`, `argos_dictionary_unresolved`, `argos_dictionary_overflow_total`, `argos_writer_rows_total{table}`, `argos_writer_inserts_total{table,result}`, `argos_writer_queue_rows{table}`, `argos_realtime_subscribers{topic}`, `argos_realtime_dropped_total`, `argos_alert_evaluations_total`, `argos_alert_states{severity,state}`, `argos_notifications_total{channel,result}`, `argos_notification_failures_total{channel}`, `argos_commands_total{type,result}`, `argos_config_changes_total{result}`, `argos_auth_failures_total`, `argos_ratelimit_rejections_total{scope}`, `argos_server_tls_cert_expiry_seconds`, `argos_agent_token_expiry_seconds{token_prefix}`, `argos_ch_parts_active{table}`, `argos_ch_delayed_inserts_total`, `argos_ch_disk_free_bytes`, `argos_ch_query_p99_seconds`, `argos_server_disk_usage_ratio{mount}`, `argos_reconcile_mismatch_minutes{app}`, `argos_privacy_jobs{state}`, `argos_clock_offset_ms{instance}`(게이지, 상위 50만). 서버 자기 메트릭은 `metric` 테이블에도 5초 격자로 기록해(`app_id = 0` 서버 가상 앱) UI 전체 현황이 쓴다.

### 9.5 인증서·진단 번들

- `CertReloader`: 30초마다 cert/key 파일 mtime 확인 → 변경 시 `SslContext` 교체(새 연결부터). 만료일 메트릭.
- `DiagnosticBundle`(`argos-admin bundle`): 서버 로그 최근 7일, 유효 설정(비밀값·토큰 마스킹), 버전, `ch_schema_history`, 저장소 상태 스냅샷, 세션 목록(토큰 prefix만), 시스템 정보 → tar.gz.

## 10. 설정 레퍼런스 (`argos.*`, 환경변수는 Spring 규칙 `ARGOS_…`)

| 키 | 기본값 | 설명 |
|---|---|---|
| `argos.collector.port` | 9100 | gRPC 포트 |
| `argos.collector.tls.enabled` / `cert-file` / `key-file` | true / `conf/tls/server.crt` / `conf/tls/server.key` | |
| `argos.collector.allow-plaintext` | false | SEC-08 |
| `argos.collector.max-message-bytes` | 4194304 | 해제 후 |
| `argos.collector.limits.*` | 11 문서 `Limits` 값 | ServerHello로 전달 |
| `argos.collector.quota.tx-per-second` / `steps-per-second` / `metrics-per-5s` | 2000 / 20000 / 1000 | 인스턴스당 |
| `argos.collector.session.max-per-token` / `new-per-minute` / `instances-per-app` / `new-instances-per-hour` | 100 / 60 / 1000 / 100 | |
| `argos.collector.auth-fail.per-ip-per-minute` / `block-minutes` | 20 / 10 | |
| `argos.collector.heartbeat-interval` / `stale-after` / `down-after` / `bye-grace` | 30s / 60s / 180s / 5m | F-S28 |
| `argos.wal.dir` / `max-bytes` / `segment-bytes` / `fsync-interval-ms` / `warn-ratio` | `data/wal` / 10GiB / 256MiB / 10 / 0.8 | |
| `argos.writer.flush-interval-ms` / `flush-rows` / `queue-rows` | 2000 / 50000 / 200000 | |
| `argos.clickhouse.url` / `database` / `writer.user` / `writer.password-file` / `reader.user` / `reader.password-file` / `timezone` | `http://clickhouse:8123` / `argos` / `argos_writer` / … / `argos_reader` / … / UTC | |
| `argos.postgres.url` / `user` / `password-file` | | |
| `argos.master-key-file` | `conf/master.key` | 또는 `ARGOS_MASTER_KEY` |
| `argos.web.public-url` | (필수) | Origin 검사·딥링크 생성 |
| `argos.auth.access-ttl` / `refresh-idle-ttl` / `refresh-max-ttl` / `max-sessions-per-user` | 15m / 8h / 24h / 5 | |
| `argos.auth.password.min-length` / `max-length` / `banned-list-file` | 12 / 128 / 내장 | |
| `argos.auth.lockout.failures` / `base-minutes` | 10 / 15 | 누진 ×2 |
| `argos.auth.setup-token-ttl` | 30m | |
| `argos.ratelimit.login-per-ip-per-minute` / `api-per-user-per-second` / `export-per-user-per-minute` | 20 / 50 / 10 | |
| `argos.alert.evaluation-interval` / `group-window` / `window-retention` | 10s / 30s / 15m | |
| `argos.alert.webhook.allow-http` / `allow-cidrs` / `timeout` | false / [] / 5s | SEC-51 |
| `argos.alert.smtp.*` | STARTTLS 필수 | SEC-54 |
| `argos.alert.recommended-rules.enabled` | true | F-S24 |
| `argos.agent.auto-register.<env>` | `default: true` | F-S30 |
| `argos.instance.archive-after` / `purge-after` | 7d / 30d | |
| `argos.retention.xlog` / `profile` / `stat` / `metric` / `rollup-1m` / `rollup-1h` / `command-results` / `audit` / `alert-events` | 30d / 7d / 14d / 14d / 90d / 365d / 30d / 365d / 365d | 기동 시 TTL 적용(13 문서 절차) |
| `argos.privacy.default-ip-mode` | masked | 설치 마법사 기본 선택(미결 18) |
| `argos.ops.reconcile.cron` | `0 0 3 * * *` | |
| `argos.ops.disk.warn-ratio` / `emergency-ratio` / `stop-ratio` | 0.8 / 0.9 / 0.95 | F-S23 |
| `argos.ops.storage-health-interval` | 1m | |
| `argos.audit.sinks` | `[postgres]` | + `syslog://host:514`, `file:logs/audit.jsonl` |
| `argos.ui.log-link-template` | (없음) | F-U18 |
| `argos.server.timezone` | Asia/Seoul | 알림 메시지 표기용 |
| `management.server.port` / `argos.metrics.allow-cidrs` / `argos.metrics.token-file` | 9101 / [] / (없음) | SEC-55 |

금지 비밀번호(`changeme`, `argos`, `password`, 빈 값)로 DB 접속 설정이 오면 기동 거부(SEC-74). 알 수 없는 `argos.*` 키는 WARN(O-R03).

## 11. 스레드·실행기·메모리

| 풀 | 스레드 | 역할 |
|---|---|---|
| gRPC boss/worker | 1 / 코어 수 | 네트워크 I/O |
| `argos-grpc-exec` | 8 고정 | 인터셉터·상한 검사·WAL append 대기 |
| `argos-wal-writer` | 1 | append·그룹 fsync |
| `argos-ingest` | 4 | 디코드·정규화·행 변환 |
| `argos-ch-writer-<table>` | 5(테이블당 1) | 블록 insert |
| `argos-dict-writer` | 1 | PG 사전 upsert |
| `argos-realtime` | 1 (Reactor `Schedulers.single`) | 100ms 묶음 |
| `argos-session-monitor` | 1 | 상태 전이 10초 |
| `argos-alert-eval` / `argos-notify` | 1 / 4 | 평가 / 채널 전송 |
| `argos-command` | 1 | TTL·결과 조립 |
| `argos-ops` | 1 | 정합·상태·보존·GC |
| `argos-privacy-job` | 1 | 삭제 뮤테이션 |
| web(Tomcat) | 가상 스레드(`spring.threads.virtual.enabled=true`) | REST·WS |

메모리: JVM `-Xmx4g`(17 문서 예산 5 GiB 중 1 GiB는 Netty 직접 메모리·메타스페이스). 알림 윈도 15분 × (앱 30 × 서비스 300) × HdrHistogram ≈ 수백 MB 상한 → 서비스 윈도는 앱당 상위 500 서비스로 제한. 라이터 큐 200,000행 × 5 테이블 ≈ 300 MB 상한.

## 12. 테스트 포인트 (03 문서 3.2·3.5 대응)

| 테스트 | 검증 | 03 항목 |
|---|---|---|
| WAL 내구성 | 1,000 배치 ack 후 `kill -9` → 재기동 → count(xlog) = 보낸 수, 중복 0 | 3.2 D-13 |
| 중복 재전송 | 같은 (epoch, seq) 100회 → `ACK_DUPLICATE`, 행 수 불변, 롤업 불변 | 3.2 |
| ClickHouse 30분 중단 | WAL 누적 → 복구 → 재적재 → 정합 0, INTENT/COMMIT 복구 경로 | 3.2 |
| 늦은 도착 | 1시간 전 ts 2만 행 → 롤업 갱신, 실시간 미발행, late 카운터 | 3.2 D-15 |
| 타임스탬프 가드 | +10s clamp, −8d 거부, 카운터 | D-15 |
| 쿼터 | 과다 전송 → `ACK_PARTIAL_DROP`, 다른 인스턴스 지연 영향 없음 | 3.5 SEC-12 |
| 상한 5종·gzip 폭탄 | 1MB→1GB 거부, 메모리 상한 유지 | 3.5 SEC-11 |
| 토큰 수명주기 | 발급 원문 1회·해시 보관·회전 동시 유효·폐기 5초 내 세션 종료·만료 거부 | 3.5 SEC-01~06 |
| 세션 검사 순서 | 11 문서 5장 7개 경로 각각의 코드·사유 | 3.5 |
| 상태 머신 | heartbeat 중단 60s → STALE, 180s → DOWN, Bye 후 5분 내 재접속 → 알림 0 | 3.2 F-S28 |
| 알림 | 지속·복구 임계치·NO_DATA·기동 유예·최소 표본·그룹핑·재알림·ACK 전이 테스트 | 3.2 |
| SSRF | 금지 대역 10종 저장 400, 리다이렉트 미추종, allow-cidrs 통과, 응답 본문 비노출 | 3.5 SEC-51 |
| 채널 비밀값 | DB 평문 부재, GET 마스킹, 키 교체 후 복호화 | 3.5 SEC-52 |
| 권한 매트릭스 | OpenAPI에서 생성한 (엔드포인트 × 역할) 전수, 미주석 엔드포인트 기동 실패 | 3.5 SEC-49 |
| 세션 쿠키 | 플래그, 15분 만료, 회전·재사용 탐지 → 전체 폐기, 역할 변경 즉시 403, 로그아웃 후 401, WS 1초 내 종료 | 3.5 SEC-40~44 |
| CSRF·Origin | 헤더 없는 POST 403, 교차 Origin 403 | 3.5 SEC-43 |
| 설정 토큰 | 없이 403, 재사용 403, 관리자 존재 시 404 | 3.5 SEC-47 |
| 명령 | 권한 매트릭스 3×5, 타인 결과 403, TTL 만료, 동시 1, 암호화 저장 | 3.5 SEC-21~24 |
| 하드 캡 | 불허 키 propose 400(인스턴스 명시), 에이전트 거부 결과 기록 | 3.5 SEC-23 |
| 감사 카탈로그 | enum 전부 발생 → 행 단언, UPDATE/DELETE 권한 없음 | 3.5 SEC-80~82 |
| 삭제 API | user_id·IP 기준 삭제 후 0건, 뮤테이션 상태 | 3.5 SEC-33 |
| 소스 선택 | 범위별 raw/1m/1h + 마지막 2분 raw, 분 경계 미정렬 D-03 일치 | 3.2 |
| 읽기 전용 | 모든 조회가 `argos_reader`·쿼리 설정 경유(`system.query_log`) | 3.2 |
| 마이그레이션 | 빈 DB·이전 버전+1/100 데이터 두 경로, 체크섬 불일치 기동 거부 | 3.2 N-22 |
| 부하 | 1,000 TPS 5분 적재 지연 p99 ≤ 5초(커밋), 5,000 TPS 1시간(릴리스 전, N-06) | 3.2 |
| 정합 잡 | 합성 불일치 주입 → 탐지 → 재구축 → 바이트 동일 | D-14 |
| 자기 메트릭 | 9.4 목록 전부 노출, Actuator env 404, prometheus 무인증 401 | SEC-55 |

## 13. 요구 ID 대응표

| 요구 | 대응 절 |
|---|---|
| F-S01, SEC-01~08, SEC-15 | 3.1, 7.1 |
| F-S02, D-13, D-14 | 3.4, 3.8, 9.1 |
| F-S03, SEC-44 | 3.7 |
| F-S04 | 14 문서(계약), 3.7 소스 선택은 `api.SourceSelector` |
| F-S05, SEC-40~48 | 7.2 |
| F-S06, F-S17, F-S24, F-S25, F-S26, F-S27, F-S31, SEC-39, SEC-51~54 | 5장 |
| F-S07, F-S29, SEC-21~24 | 6장 |
| F-S08, SEC-80~84 | 8.1 |
| F-S10, D-06, D-15, D-18, D-19 | 3.3, 3.5, 9.4 |
| F-S16, D-17 | 3.5 (5) |
| F-S19, SEC-36 | 3.5 (4) |
| F-S20, SEC-55 | 9.4, 7.4 |
| F-S21, F-A22, D-12, D-20 | 3.6 |
| F-S22 | 9.1 |
| F-S23, N-18 | 9.2 |
| F-S28, D-02 | 4장, 11 문서 9장 |
| F-S30 | 4.4 |
| F-S32, SEC-33 | 8.3 |
| F-S33, SEC-11~14 | 3.3 |
| N-05 | 3.2(ack 비동기), 3.9 |
| N-06, N-15 | 3.8, 12장 부하 |
| N-08 | 10장 retention, 13 문서 |
| N-22 | 9.3 |
| N-23 | 5.4 (10초 평가 + 통지 ≤ 20초) |
| N-25 | 6.2 |
| SEC-22, SEC-52 | 8.2 |
| SEC-49·50 | 7.3 |
| SEC-56 | 7.4 |

## 14. 설계 결정

| ID | 결정 | 대안 | 선택 이유 | 영향 요구 |
|---|---|---|---|---|
| DSN-12-01 | ack는 WAL fsync 직후, 적재는 비동기 | insert 후 ack | 에이전트 지연·버퍼와 서버 적재를 분리, 내구성은 WAL이 보장 | D-13, N-05 |
| DSN-12-02 | 블록 경계를 WAL 자체의 INTENT/COMMIT 레코드로 기록 | 별도 인덱스 파일 | 단일 fsync 경로, 복구 로직 단순 | D-13 |
| DSN-12-03 | 중복 제거 윈도는 메모리 + WAL 복원(PG 미사용) | PG 테이블 | 핫패스에 DB 왕복 없음 | D-13, N-06 |
| DSN-12-04 | 쿼터 초과는 배치 뒤쪽 항목부터 제거, 배치 전체 거부 아님 | 전체 거부 | 정상 트래픽 보존, 드롭 수 정확 집계 | SEC-12, D-06 |
| DSN-12-05 | 사전 해석을 기다리지 않고 id만 적재, PG upsert 비동기 | 동기 해석 | 적재 지연·결합도 | D-08, N-15 |
| DSN-12-06 | 에러 그룹 지문은 서버가 insert 전에 계산해 `xlog.error_group_id`에 저장 | 조회 시 계산 | 30일 관련 트랜잭션 조회를 원본 컬럼으로 | F-S19 |
| DSN-12-07 | 알림 지표는 15분 메모리 윈도(HdrHistogram) + 그 이상은 1m 롤업 쿼리 | 전부 ClickHouse | 10초 평가·30초 통지(N-23)를 적재 지연과 무관하게 | N-23 |
| DSN-12-08 | 상태 모델 STALE 60초 / DOWN 180초 / Bye 유예 5분 | 더 짧게 | heartbeat 30초 × 2 여유, 롤링 재기동 오탐 0 | F-S28, S-17 |
| DSN-12-09 | 설정 변경은 대상 인스턴스 `remote_config_allow` 교집합으로 서버에서 선검증, 에이전트가 재검증 | 서버 검증만 | 하드 캡 이중 안전 | SEC-23 |
| DSN-12-10 | 사용자 세션은 JWT 15분 + 서버 세션 레코드(5초 캐시) 조합 | 순수 JWT / 순수 세션 | 즉시 무효화와 무상태 검증 비용의 균형 | SEC-40~42 |
| DSN-12-11 | 권한은 `@RequiresRole` 주석 → OpenAPI `x-argos-role` → 기동 시 전수 검사 | 설정 파일 매트릭스 | 누락이 컴파일·기동 단계에서 잡힘 | SEC-49 |
| DSN-12-12 | 웹훅 전송은 DNS 해석 결과 IP에 고정 연결, 리다이렉트 금지 | 호스트명 연결 | DNS 리바인딩 차단 | SEC-51 |
| DSN-12-13 | 감사 로그 불변성은 DB 역할 권한으로 강제(앱 역할 INSERT·SELECT만) | 앱 코드 규약만 | 코드 결함이 있어도 삭제 불가 | SEC-82 |
| DSN-12-14 | 서버 자기 메트릭을 `metric` 테이블에 `app_id = 0`으로도 기록 | Prometheus만 | 전체 현황이 외부 Prometheus 없이 서버 상태를 보여줌 | F-S10, F-U17 |
| DSN-12-15 | 기동 순서: 설정 → 키 → PG Flyway → CH 러너 → WAL 복구 → 리스너 | 리스너 먼저 | 복구 전 수신 금지로 중복·순서 문제 제거 | N-22, D-13 |

## 15. 미결·가정

| # | 항목 | 가정 | 확인 |
|---|---|---|---|
| 1 | grpc-java 해제 후 크기 제한 적용 지점 | `maxInboundMessageSize`가 해제 후 적용 + `BoundedInputStream` 안전망 | SEC-11 gzip 폭탄 테스트로 실측 |
| 2 | ClickHouse `insert_deduplication_token` + `deduplicate_blocks_in_dependent_materialized_views`가 비복제 MergeTree에서 번들 버전대로 동작 | 동작 | WO-03 Q15 |
| 3 | HdrHistogram 메모리(서비스 500 × 15분) | 수백 MB 이내 | 부하 테스트 RSS 실측, 상한 조정 |
| 4 | Tomcat 가상 스레드로 REST·WS 처리 시 핀닝 이슈 | 없음(JDK 21+) | 부하 테스트 |
| 5 | EdDSA JWT 서명(Ed25519) 라이브러리 | Nimbus JOSE 또는 JDK 15+ 내장 | 19 문서 의존성 허용 목록 |
| 6 | `system.mutations` 폴링으로 삭제 완료 판정 | 가능 | 13 문서 |
| 7 | 알림 윈도 백분위를 HdrHistogram으로 할 때 롤업(tDigest/BFloat16/DD)과의 값 차이 | 알림 평가용이므로 허용 | 문서에 "알림 p95는 메모리 윈도 기준" 명시 |
| 8 | 서버 TZ 기본 `Asia/Seoul` | 알림 메시지 표기용만, 저장은 UTC | — |
| 9 | gRPC health 서비스 미인증 노출 | `SERVING`만, 정보 없음 | 16 문서 검토 |

## 16. 다른 설계 문서에 요구하는 것

| 대상 문서 | 요구 | 이유 |
|---|---|---|
| 13 저장소 | 테이블: `xlog`에 `error_group_id`·`apdex_t_ms`·`env`·`kind`·`dbc_wait_ms`·`request_id`·`dispatch_count`·`streaming`·`timed_out`·`incoming_trace`·`app_version_id(UInt64)`·`late UInt8`, `profile_step`에 DBC/COMMIT/ROLLBACK enum·`fetch_ms`·`repeat_count`·`batch_size`, `metric`에 `app_id=0` 서버 가상 앱 허용, `sql_stat_raw.kind·dbc_wait_sum_ms`, `ext_stat_raw.kind·timeout_count·connect_fail_count`. PG: `user_sessions`, `pending_registrations`, `config_changes`·`config_change_results`, `alert_rules(spec jsonb)`·`alert_rule_revisions`·`alert_states`·`notification_deliveries`·`webhook_signing_keys`, `privacy_jobs`, `reconcile_results`, `service_apdex_overrides`, `audit_logs` 역할 권한(INSERT·SELECT만), `ch_schema_history`. 롤업 SELECT를 `rollups/*.sql`로 분리(MV·재구축 공용). `REPLACE PARTITION` 재구축 절차. TTL 변경 절차 | 3.5·3.6·3.8·5·6·7·8·9장 |
| 14 API | 3.7 WebSocket 프레임 스키마, `source` 필드, `SourceSelector` 규칙(≤6h raw / ≤7d 1m / 그 이상 1h, 마지막 2분 raw), `/api/v1/auth/*`·`/setup/*`·`/admin/privacy/*`·`/commands/*`·`/config-changes/*`·`/alerts/{rules,events,channels,acks,mutes}`·`/apps/{id}/storage/health`·`/events` 계약, 에러 본문 `{code, message, details, request_id}`, `x-argos-role` 표기, 429 `Retry-After`, 알림 규칙 JSON Schema 공개 | 5·6·7·8장 |
| 15 UI | 토큰 발급 1회 표시 UX, 설정 변경 diff·인스턴스별 결과·되돌리기, 승인 대기, ACK, 채널 상태 배지, "갱신 중단 N초"(dropped 프레임), 설정 토큰 입력 마법사, IP 모드 선택 강제 | 3.7·4.4·5·6·7장 |
| 16 보안 | 7.1 토큰 캐시 무효화·IP 차단 구현 검토, 7.2 쿠키·JWT 키 관리·세션 캐시 5초의 수용 가능성, 8.2 봉투 암호화 포맷·키 교체 절차, DestinationValidator 테스트 벡터, 로그 토큰 치환 appender | 7·8장 |
| 17 배포 | 10장 설정 키를 Compose·tar 템플릿에 반영, WAL 디렉터리 분리 권장, `management.server.port 9101` 내부 전용, 마스터 키·JWT 키·설정 토큰 파일 생성·권한, 메모리 예산(JVM 4g + 1g) | 10·11장 |
| 18 테스트 | 12장 테스트 포인트의 하네스: 가짜 에이전트(5,000 TPS·쿼터·gzip 폭탄·재전송·epoch 리셋), `kill -9` 재시작, ClickHouse 중단 시나리오, SSRF 벡터, 권한 매트릭스 생성기, 감사 카탈로그 스냅샷 | 12장 |
| 10 에이전트 | 쿼터 `ACK_PARTIAL_DROP`·`Throttle` 처리, `SESSION_REQUIRED` 재수립, `REPLACED` 수신 시 재접속 금지(중복 기동 경고 로그), `remote_config_allow`·모드를 Hello에 정확히 보고 | 3.2·4.1 |
| 02 아키텍처(PM) | DSN-02-12 보강 제안: "블록 경계는 WAL INTENT/COMMIT 레코드로 기록"(DSN-12-02)을 전역 결정으로 승격할지 | 3.8 |
