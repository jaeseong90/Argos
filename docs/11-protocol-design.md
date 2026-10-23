# Argos 프로토콜 설계 (에이전트 ↔ 서버)

| 항목 | 내용 |
|---|---|
| 문서 번호 | 11 |
| 문서 버전 | 0.1 (초안) |
| 작성일 | 2026-10-23 |
| 상태 | 설계 검토 중 |
| 만족시키는 요구 | F-A06, F-A12, F-A13, F-A22, F-A26, F-A30, F-S01, F-S07, F-S10, F-S21, F-S28, F-S29, F-S33, D-02, D-06, D-11, D-12, D-13, D-15, D-16, D-20, D-23, N-05, N-15, N-25, N-30, SEC-01~08, SEC-11~15, SEC-20~25, SEC-39, O-R01~03 |
| 의존 문서 | 02 아키텍처(DSN-02-08·09·10·11·12·14·15·16·17·22), 06 보안 요구 |
| 이 문서에 의존하는 문서 | 10 에이전트, 12 서버, 13 저장소, 16 보안, 18 테스트, 19 빌드(proto 컴파일) |

## 1. 범위와 원칙

이 문서는 `argos-agent.jar`와 `argos-server` 사이의 유일한 통신 규약을 정한다. 전송(gRPC/TLS), RPC 모델, 메시지(proto3), 세션 수립·버전 협상, 배치 멱등성, 사전 교환, 설정 푸시·명령, 시계 동기화, 상태 코드, 진화 규칙을 포함한다. UI↔서버(REST·WebSocket)는 14 문서다.

| 원칙 | 의미 |
|---|---|
| 에이전트는 기다리지 않는다 | 모든 RPC는 송신 스레드에서만 호출되고 deadline이 있다. 앱 스레드는 큐에 `offer`만 한다(N-05) |
| 배치가 단위다 | 데이터는 배치 단위 unary RPC. 배치마다 `seq`가 있어 ack·재전송·중복 제거가 배치 단위로 된다(DSN-02-11, D-13) |
| 제어는 한 스트림 | 세션·heartbeat·사전·설정·명령은 `Session` 양방향 스트림 하나로 다닌다 |
| 서버가 신뢰 경계다 | 토큰·앱 소속·버전·상한·쿼터를 서버가 검사한다. 에이전트가 보낸 값은 모두 상한 검사를 거친다(SEC-11) |
| 에이전트가 하드 캡이다 | 서버가 보낼 수 있는 명령은 폐쇄 목록이고, 바꿀 수 있는 설정은 에이전트 로컬 allow 목록 안이다. 임의 코드 실행 경로는 없다(SEC-23·25) |
| 추가만 한다 | 필드 번호는 재사용하지 않고 `reserved`로 막는다. 알 수 없는 필드·enum 값은 무시하고 센다 |

## 2. 전송 계층

| 항목 | 값 | 근거 |
|---|---|---|
| 프로토콜 | gRPC over HTTP/2, protobuf 3 | DSN-02-11 |
| 서버 포트 | 9100 (설정 `argos.collector.port`). HTTP(8080)와 별도 리스너 | SEC-75 |
| TLS | 기본 on. TLS 1.2 이상, 암호군 AEAD만(TLS 1.3 전부, 1.2는 `ECDHE_*_AES_*_GCM`, `CHACHA20_POLY1305`). 에이전트는 체인·호스트명 검증 필수. `server.tls.ca-file`로 자체 CA. `insecure-skip-verify`는 명시 + 매 접속 WARN. 평문은 에이전트 `server.tls: false` **그리고** 서버 `argos.collector.allow-plaintext: true`일 때만 | SEC-07·08 |
| 인증 | 모든 RPC의 메타데이터 `authorization: Bearer argos_<base64url>`. 서버는 SHA-256 해시로 조회. 토큰은 앱 단위 | SEC-01·02 |
| 세션 식별 | 데이터 RPC 메타데이터 `argos-instance-id: <u32>`, `argos-session-id: <uuid>`. `Session` 수립 전의 데이터 RPC는 `FAILED_PRECONDITION` | 3장 |
| 메시지 상한 | 서버 `maxInboundMessageSize = 4 MiB`(압축 해제 후). 에이전트 배치 직렬화 목표 ≤ 1 MiB, 초과 시 분할 | SEC-11 |
| 압축 | 메시지 단위 `gzip`. 에이전트는 직렬화 1 KiB 초과 배치에만 적용. 서버는 해제 후 크기로 상한 검사 | N-02 |
| keepalive | 에이전트 `keepAliveTime 60s`, `keepAliveTimeout 20s`, `permitKeepAliveWithoutCalls true`(세션 스트림). 서버 `permitKeepAliveTime 60s`, 위반 시 `GOAWAY too_many_pings` | SEC-15 |
| 동시 스트림 | 서버 `maxConcurrentCallsPerConnection 8`. 에이전트는 세션 1 + in-flight 데이터 RPC ≤ 4 | DSN-02-11 |
| 핸드셰이크 | TLS 핸드셰이크 10초, 연결 후 10초 안에 `AgentHello`가 없으면 서버가 닫음 | SEC-15 |
| 리플렉션 | 서버 gRPC reflection 비활성 | SEC-15 |
| Netty(에이전트) | NIO 전송만, `SslProvider.JDK`, 이벤트 루프 1, direct executor, `io.netty.noUnsafe=true`, `maxDirectMemory 8 MiB` | F-A35, N-26 |

## 3. RPC 모델

```
AgentService
├─ Session(stream AgentMessage) returns (stream ServerMessage)   // 세션·제어. 연결당 1개
├─ SendTransactions(TransactionBatch)        returns (Ack)      // XLog 레코드
├─ SendProfiles(ProfileBatch)                returns (Ack)      // 프로파일(샘플링된 트랜잭션의 step)
├─ SendMetrics(MetricBatch)                  returns (Ack)      // 카운터(5초 격자) + 에이전트 자기 메트릭
├─ SendStats(StatBatch)                      returns (Ack)      // 전수 SQL·외부 호출 10초 창 통계(D-12)
└─ SendActiveSnapshot(ActiveTransactionSnapshot) returns (Ack)  // 진행 중 트랜잭션 5초 스냅샷
```

| 구분 | 방향·형태 | 주기·트리거 | deadline |
|---|---|---|---|
| Session | 양방향 스트림, 에이전트가 연다 | 접속 시 1회, 끊기면 백오프 재접속 | 없음(스트림) |
| SendTransactions | unary | 100ms 또는 500건 중 먼저 | 5초 |
| SendProfiles | unary | 100ms 또는 2,000 step | 5초 |
| SendMetrics | unary | 5초 격자마다 1회 | 5초 |
| SendStats | unary | 10초 창 종료마다 1회 | 5초 |
| SendActiveSnapshot | unary | 5초마다 1회(진행 중 0건이면 `total_active=0`로 전송) | 3초 |

데이터 RPC는 `Session`이 `ServerHello.accepted = true`로 수립된 뒤에만 허용된다. 서버가 세션을 모르면(서버 재시작 등) `FAILED_PRECONDITION`(detail `SESSION_REQUIRED`)을 돌려주고, 에이전트는 세션을 다시 수립한 뒤 **같은 배치를 같은 `seq`로** 재전송한다.

## 4. 메시지 정의 (proto3)

파일은 `proto/argos/v1/{common,session,data}.proto` 셋이며 모두 아래 헤더를 공유한다. 생성 코드는 에이전트(Java 8, `protobuf-javalite` 또는 `protobuf-java` — P-3에서 결정)와 서버(Java 21)가 각각 만든다.

```proto
syntax = "proto3";
package argos.v1;
option java_package = "io.argos.proto.v1";
option java_multiple_files = true;
```

### 4.1 common.proto

```proto
// 프로토콜 정수 버전. 메시지 추가는 버전을 올리지 않는다. 의미 변경·필수 필드 추가만 올린다.
// 현재 = 1. 서버는 [min_supported, current]를 지원한다.

enum TransactionKind { TRANSACTION_KIND_UNSPECIFIED = 0; HTTP = 1; BATCH = 2; MESSAGE = 3; }

enum StepType {
  STEP_TYPE_UNSPECIFIED = 0;
  SQL = 1; HTTP_CALL = 2; METHOD = 3; ASYNC = 4; ERROR = 5; MESSAGE_STEP = 6; STACK = 7;
  DBC = 8;      // DataSource.getConnection 대기
  COMMIT = 9; ROLLBACK = 10;
}

// 외부 호출 실패 분류(F-A05)
enum FailureKind { FAILURE_NONE = 0; TIMEOUT = 1; CONNECT_FAIL = 2; HTTP_5XX = 3; OTHER_ERROR = 4; }

// 사전 종류(DSN-02-10). 해시는 전부 xxh3_64(UTF-8 원문).
enum DictionaryKind {
  DICTIONARY_KIND_UNSPECIFIED = 0;
  SERVICE = 1;      // 서비스 이름 "GET /api/orders/{id}", "BATCH Job.Step"
  SQL_TEXT = 2;     // 정규화 SQL (norm_version 포함)
  CLASS = 3;        // 예외 클래스 등 FQCN
  METHOD_NAME = 4;  // Class#method
  THREAD = 5;       // 정규화된 스레드 이름(숫자 접미 제거)
  HOST = 6;         // 외부 호출 "host:port"
  FRAME = 7;        // 스택 프레임 "Class.method(File:line)" → line은 제외한 "Class.method(File)"
  DB_TARGET = 8;    // JDBC 대상 "vendor://host:port/db"
  APP_VERSION = 9;  // app.version 문자열
}

enum MetricKind { METRIC_KIND_UNSPECIFIED = 0; GAUGE = 1; COUNTER = 2; }

enum IpMode { IP_MODE_UNSPECIFIED = 0; IP_FULL = 1; IP_MASKED = 2; IP_OFF = 3; }
enum UserIdMode { USER_ID_MODE_UNSPECIFIED = 0; USER_ID_OFF = 1; USER_ID_PLAIN = 2; USER_ID_HASHED = 3; }
enum QueryStringMode { QUERY_STRING_MODE_UNSPECIFIED = 0; QS_OFF = 1; QS_WHITELIST = 2; QS_FULL = 3; }
enum SqlParamMode { SQL_PARAM_MODE_UNSPECIFIED = 0; SQL_PARAM_MASKED = 1; SQL_PARAM_PARTIAL = 2; SQL_PARAM_FULL = 3; }

// 명령 폐쇄 목록(SEC-21·24). 여기 없는 값은 에이전트가 REJECTED로 응답한다. 코드·클래스·스크립트를 받는 타입은 영원히 없다.
enum CommandType {
  COMMAND_TYPE_UNSPECIFIED = 0;
  THREAD_DUMP = 1;        // 전체 스레드 덤프(가상 스레드 포함). 운영자 이상
  THREAD_STACK = 2;       // 단일 스레드 스택(args: tx_id 또는 thread_id). 운영자 이상
  SELF_DIAGNOSTICS = 3;   // 자가 진단 리포트. 운영자 이상
  CONFIG_RELOAD = 4;      // 로컬 설정 파일 재로드. 관리자
  ENV_DUMP = 5;           // 환경변수·시스템 프로퍼티(에이전트 측 마스킹). 관리자
  reserved 6 to 9;        // 1.x: HEAP_HISTOGRAM, GC_RUN, LOADED_CLASSES
}

message KeyValue { string key = 1; string value = 2; }   // key ≤ 128B, value ≤ 1 KiB
```

### 4.2 session.proto

```proto
import "argos/v1/common.proto";

message AgentMessage {
  oneof body {
    AgentHello hello = 1;
    Heartbeat heartbeat = 2;
    DictionaryEntries dictionary_entries = 3;      // 선전송 또는 DictionaryRequest 응답
    ConfigApplyResult config_apply_result = 4;
    CommandResult command_result = 5;
    InstanceUpdate instance_update = 6;
    AgentBye bye = 7;
    AgentEvent event = 8;                           // 모듈 자동 비활성, 킬 스위치 등
  }
}

message ServerMessage {
  oneof body {
    ServerHello hello = 1;
    HeartbeatAck heartbeat_ack = 2;
    DictionaryRequest dictionary_request = 3;
    ConfigPush config_push = 4;
    Command command = 5;
    SessionClose close = 6;
    Throttle throttle = 7;
  }
}

message AgentHello {
  string agent_version = 1;            // SemVer "1.2.0"
  uint32 protocol_version = 2;         // 현재 1
  string app_name = 3;                 // ≤ 128B
  string env = 4;                      // ≤ 32B, 비면 서버 기본 "default"
  string instance_name = 5;            // 설정 이름 또는 "host:pid"(포트 미확정 시). 포트 확정 후 InstanceUpdate
  string host = 6; uint32 pid = 7;
  string jvm_version = 8; string jvm_vendor = 9; string os = 10; string arch = 11;
  bool virtual_threads = 12;           // 기동 시 감지. 변경은 InstanceUpdate
  string cgroup = 13;                  // "v1" | "v2" | "none"
  string config_hash = 14;             // 유효 설정 해시(토큰 마스킹 후)
  repeated string remote_config_allow = 15;   // 에이전트 로컬 allow 목록(SEC-23) — 서버는 이 밖의 키를 푸시하지 않는다
  IpMode ip_mode = 16; UserIdMode user_id_mode = 17;
  QueryStringMode query_string_mode = 18; SqlParamMode sql_param_mode = 19;
  string app_version = 20;             // F-A21. 비면 미상
  int64 sent_at_ms = 21;               // 에이전트 벽시계(시계 차이 1차 추정)
  uint64 agent_epoch = 22;             // 프로세스 시작 시 생성한 랜덤 64비트. 배치 중복 제거 키의 일부
  int64 process_start_ms = 23;         // 기동 유예(F-S25) 계산용
  repeated string instrumentation_modules = 24;  // 활성 모듈 이름 목록
}

message ServerHello {
  bool accepted = 1;
  RejectReason reject_reason = 2;      // accepted=false일 때
  string reject_message = 3;           // 사람이 읽는 사유(영어). 토큰 값은 절대 포함하지 않는다
  uint32 retry_after_s = 4;            // 거부 시 재시도 권고. 0이면 재시도 금지(버전 불일치 등)
  uint32 app_id = 5; uint32 instance_id = 6;
  string session_id = 7;               // UUID. 데이터 RPC 메타데이터에 실린다
  string server_version = 8; uint32 protocol_version = 9;
  string min_agent_version = 10; string max_agent_version = 11;
  int64 server_time_ms = 12;           // 1차 시계 차이 추정용
  map<string,string> config = 13;      // 서버가 보유한 이 앱의 푸시 설정(allow 안의 키만)
  uint32 heartbeat_interval_s = 14;    // 기본 30
  uint32 active_snapshot_interval_s = 15;  // 기본 5
  uint32 stat_window_s = 16;           // 기본 10
  Limits limits = 17;                  // 에이전트가 배치를 만들 때 지킬 상한
}

enum RejectReason {
  REJECT_REASON_UNSPECIFIED = 0;
  TOKEN_INVALID = 1; TOKEN_EXPIRED = 2; TOKEN_REVOKED = 3;
  APP_MISMATCH = 4;            // 토큰의 앱 ≠ hello.app_name
  APP_NOT_REGISTERED = 5;      // auto-register off, 승인 대기 등록됨
  AGENT_VERSION_TOO_NEW = 6; AGENT_VERSION_TOO_OLD = 7; PROTOCOL_UNSUPPORTED = 8;
  PLAINTEXT_NOT_ALLOWED = 9;
  INSTANCE_LIMIT = 10; RATE_LIMITED = 11; SERVER_SHUTTING_DOWN = 12;
}

message Limits {
  uint32 max_message_bytes = 1;        // 4194304
  uint32 max_transactions_per_batch = 2;   // 5000
  uint32 max_steps_per_batch = 3;      // 50000
  uint32 max_detail_bytes = 4;         // 4096
  uint32 max_string_bytes = 5;         // 2048
  uint32 max_frames = 6;               // 30(ERROR) / 64(STACK)
  uint32 max_active_items = 7;         // 500
  uint32 max_series_per_instance = 8;  // 500
  uint32 max_dictionary_entries_per_hour = 9;  // 10000
}

// NTP식 시계 동기화(D-02). 서버가 계산한다(4.6절).
message Heartbeat {
  int64 t1_ms = 1;                     // 이 heartbeat 송신 시각(에이전트 시계)
  int64 prev_t1_ms = 2;                // 직전 heartbeat의 t1
  int64 prev_t4_ms = 3;                // 직전 HeartbeatAck 수신 시각(에이전트 시계)
  uint32 active_transactions = 4;      // 가벼운 상태 요약
  uint64 dropped_total = 5;            // 누적 에이전트 드롭(모든 큐 합)
  uint64 last_tx_seq = 6; uint64 last_profile_seq = 7; uint64 last_metric_seq = 8; uint64 last_stat_seq = 9;
}
message HeartbeatAck {
  int64 t1_ms = 1;                     // 에코
  int64 t2_ms = 2;                     // 서버 수신 시각
  int64 t3_ms = 3;                     // 서버 송신 시각
  int32 clock_offset_ms = 4;           // 서버가 계산한 최신 오프셋(에이전트 − 서버). 정보용
  InstanceState state = 5;             // 서버가 본 인스턴스 상태(UP/WARN). 에이전트 자가 진단 표시용
}
enum InstanceState { INSTANCE_STATE_UNSPECIFIED = 0; UP = 1; STALE = 2; DOWN = 3; WARN = 4; }

message DictionaryEntry {
  DictionaryKind kind = 1;
  uint64 hash = 2;                     // xxh3_64(text). 0은 금지
  string text = 3;                     // SQL_TEXT ≤ 64 KiB, 그 외 ≤ 8 KiB. 초과는 절단 + truncated
  bool truncated = 4;
  uint32 norm_version = 5;             // SQL_TEXT만. 정규화기 버전(D-22)
}
message DictionaryEntries { repeated DictionaryEntry entries = 1; }   // ≤ 1,000건/메시지
message DictionaryRequest { repeated DictionaryRef refs = 1; }        // 서버가 모르는 id 요청
message DictionaryRef { DictionaryKind kind = 1; uint64 hash = 2; }

message ConfigPush {
  string change_id = 1;                // UUID. F-S29 변경 레코드
  map<string,string> values = 2;       // 키 = argos-agent.yml 점 표기(예: "sampling.profile.normal-rate")
  bool revert = 3;                     // 되돌리기 푸시(이전 값 세트)
  int64 issued_at_ms = 4;
}
message ConfigApplyResult {
  string change_id = 1;
  repeated string applied_keys = 2;
  repeated RejectedKey rejected = 3;
  string effective_config_hash = 4;
  uint32 retransformed_classes = 5;    // 커스텀 메서드 변경 시
  uint32 duration_ms = 6;
}
message RejectedKey { string key = 1; RejectCode code = 2; string message = 3; }
enum RejectCode { REJECT_CODE_UNSPECIFIED = 0; NOT_ALLOWED = 1; UNKNOWN_KEY = 2; INVALID_VALUE = 3; FORBIDDEN_PATTERN = 4; LIMIT_EXCEEDED = 5; }

message Command {
  string command_id = 1;               // UUIDv4
  CommandType type = 2;
  int64 issued_at_ms = 3;
  uint32 ttl_s = 4;                    // 기본 60. 에이전트는 수신 시각 기준 만료 판단
  map<string,string> args = 5;         // THREAD_STACK: "tx_id" 또는 "thread_id". ≤ 8개, 값 ≤ 256B
}
message CommandResult {
  string command_id = 1;
  CommandStatus status = 2;
  uint32 chunk_index = 3; uint32 chunk_count = 4;   // payload 분할(청크 ≤ 256 KiB, 합 ≤ 1 MiB)
  bytes payload = 5;
  string content_type = 6;             // "text/plain", "application/json"
  string error = 7;                    // 실패 사유(영어, 수집 데이터·비밀값 없음)
  uint32 duration_ms = 8;
  bool truncated = 9;                  // 1 MiB 상한으로 잘림
}
enum CommandStatus { COMMAND_STATUS_UNSPECIFIED = 0; OK = 1; REJECTED = 2; EXPIRED = 3; FAILED = 4; COOLDOWN = 5; }

message InstanceUpdate {
  optional string instance_name = 1;   // 커넥터 포트 확정 후 "host:port"
  optional bool virtual_threads = 2;
  optional string app_version = 3;
  repeated string connectors = 4;      // "http-nio-8080"
  optional string config_hash = 5;
}

message AgentBye {
  ByeReason reason = 1;
  uint64 last_tx_seq = 2; uint64 last_profile_seq = 3; uint64 last_metric_seq = 4; uint64 last_stat_seq = 5;
}
enum ByeReason { BYE_REASON_UNSPECIFIED = 0; SHUTDOWN = 1; AGENT_DISABLED = 2; }

message AgentEvent {
  AgentEventType type = 1;
  string module = 2;                   // 해당 시
  string detail = 3;                   // ≤ 1 KiB, 수집 데이터 없음
  int64 at_ms = 4;
}
enum AgentEventType {
  AGENT_EVENT_TYPE_UNSPECIFIED = 0;
  MODULE_DISABLED = 1;                 // 에러 임계 초과 자동 비활성(F-A15)
  TRANSFORM_ERRORS = 2;
  QUEUE_DROP_BURST = 3;                // 1분 내 드롭 급증
  KILL_SWITCH_ON = 4; KILL_SWITCH_OFF = 5;
  SELF_CPU_HIGH = 6;                   // 에이전트 스레드 CPU 임계 초과 → 샘플링 자동 하향
  CONFIG_FILE_RELOADED = 7;
}

message SessionClose { CloseReason reason = 1; string message = 2; uint32 retry_after_s = 3; }
enum CloseReason { CLOSE_REASON_UNSPECIFIED = 0; TOKEN_REVOKED_CLOSE = 1; SERVER_SHUTDOWN = 2; REPLACED = 3; PROTOCOL_ERROR = 4; IDLE_TIMEOUT = 5; }

message Throttle {
  optional float profile_normal_rate = 1;   // 서버 부하 시 하향 지시(A-3). 에이전트 설정값을 넘지 않는다
  optional uint32 send_backoff_ms = 2;      // 다음 배치까지 최소 간격
}
```

### 4.3 data.proto

```proto
import "argos/v1/common.proto";

// 모든 배치의 공통 헤더. 중복 제거 키 = (instance_id, agent_epoch, 배치 종류, seq)
message BatchHeader {
  uint32 instance_id = 1;
  uint64 agent_epoch = 2;
  uint64 seq = 3;                      // 배치 종류별로 독립, 프로세스 수명 동안 단조 증가(세션 재수립과 무관)
  int64 sent_at_ms = 4;                // 에이전트 시계. 적재 지연 계산은 서버 수신 시각 기준
}

message Ack {
  uint64 last_seq = 1;                 // 이 종류에서 서버가 WAL에 기록한 마지막 seq
  AckStatus status = 2;
  uint32 dropped_items = 3;            // PARTIAL_DROP(쿼터)로 버린 항목 수
  Throttle throttle = 4;
}
enum AckStatus { ACK_STATUS_UNSPECIFIED = 0; ACK_OK = 1; ACK_DUPLICATE = 2; ACK_PARTIAL_DROP = 3; }

message TransactionBatch { BatchHeader header = 1; repeated TransactionRecord records = 2; }   // ≤ 5,000

message TransactionRecord {
  uint64 tx_id = 1;                    // DSN-02-09. 시작 시각은 tx_id에서 복원 가능
  bytes trace_id = 2;                  // 16B. 비면 서버가 tx_id에서 생성하지 않는다(에이전트가 항상 채운다)
  uint64 span_id = 3; uint64 parent_span_id = 4;
  bool incoming_trace = 5;             // 외부 traceparent에 참여했는가
  uint32 caller_app_id = 6;            // tracestate argos 엔트리에서. 없으면 0
  TransactionKind kind = 7;
  uint64 service_id = 8;               // SERVICE 사전
  int64 start_ts_ms = 9;               // 에이전트 시계(D-02). 서버가 가드(D-15)
  uint64 elapsed_us = 10;              // D-01. 저장 시 ms
  string http_method = 11;             // ≤ 16B
  uint32 http_status = 12;             // 최종값(D-21). BATCH는 0
  string url_path = 13;                // 경로만(쿼리스트링은 모드에 따라). ≤ 2 KiB
  bool error = 14;                     // D-10
  uint64 error_class_id = 15;          // CLASS 사전
  string error_message = 16;           // 마스킹 후 ≤ 1 KiB(SEC-36). 서버가 지문 계산(F-S19)
  uint64 error_top_frame_id = 17;      // FRAME 사전(앱 패키지 최상위 프레임)
  bytes remote_ip = 18;                // 4B 또는 16B. IP_OFF면 비움. IP_MASKED는 에이전트가 마스킹 후
  string user_id = 19;                 // ≤ 128B. OFF면 비움. HASHED면 16자 hex
  uint32 sql_count = 20; uint32 sql_time_ms = 21;
  uint32 ext_count = 22; uint32 ext_time_ms = 23;
  uint32 dbc_wait_ms = 24;             // 커넥션 획득 대기 합계(F-A03)
  optional int64 cpu_time_us = 25;     // 결측이면 미설정(D-23). 가상 스레드·스레드 전환
  optional uint64 alloc_bytes = 26;    // 결측이면 미설정
  uint64 thread_name_id = 27;          // THREAD 사전(정규화)
  bool virtual_thread = 28;
  bool profile_captured = 29;          // 프로파일 배치에 이 tx의 step이 있다
  repeated uint64 sql_ids = 30;        // A-10. 중복 제거, ≤ 64
  uint32 dispatch_count = 31;          // F-A28
  bool streaming = 32; bool timed_out = 33;   // F-A34
  repeated KeyValue headers = 34;      // 화이트리스트 헤더 ≤ 10
  string request_id = 35;              // 설정한 요청 ID 헤더 값 ≤ 128B
  uint64 app_version_id = 36;          // APP_VERSION 사전. 0이면 인스턴스 메타 값 사용
}

message ProfileBatch { BatchHeader header = 1; repeated TransactionProfile profiles = 2; }   // step 합 ≤ 50,000

message TransactionProfile {
  uint64 tx_id = 1;
  int64 start_ts_ms = 2;
  repeated ProfileStep steps = 3;
  bool truncated = 4;                  // step 상한(1,000) 초과로 뒤를 요약
  uint32 total_steps = 5;              // 실제 발생 수
}

message ProfileStep {
  uint32 seq = 1; uint32 parent_seq = 2;   // 루트는 parent_seq = 0, seq ≥ 1
  StepType type = 3;
  uint32 start_offset_us = 4;          // 트랜잭션 시작 기준
  uint32 elapsed_us = 5;               // SQL: exec + fetch
  uint32 fetch_us = 6;                 // SQL만
  uint64 name_id = 7;                  // SQL_TEXT / HOST / METHOD_NAME / CLASS 사전
  uint64 target_id = 8;                // SQL·DBC·COMMIT: DB_TARGET, HTTP_CALL: HOST, 그 외 0
  uint32 rows = 9;                     // SQL fetch 행수, HTTP 응답 바이트(≤ u32)
  uint32 status = 10;                  // HTTP 상태 코드
  FailureKind failure = 11;
  bool error = 12;
  string detail = 13;                  // 바인드 파라미터(모드 적용)·URL 경로 등 ≤ 4 KiB. 스택 원문 금지
  repeated uint64 frame_ids = 14;      // ERROR ≤ 30, STACK ≤ 64 (FRAME 사전)
  uint32 batch_size = 15;              // SQL executeBatch 건수
  uint32 repeat_count = 16;            // 연속 반복 접기(동일 name_id·type) 시 접힌 수. 0/1이면 단건
}

message MetricBatch {
  BatchHeader header = 1;
  repeated MetricSeries series = 2;    // 배치 내 사전. ≤ 500
  repeated MetricSample samples = 3;
}
message MetricSeries { uint32 index = 1; string name = 2; MetricKind kind = 3; }   // name = OpenMetrics "jvm_memory_used_bytes{area=\"heap\"}" ≤ 256B
message MetricSample { uint32 series_index = 1; int64 ts_ms = 2; double value = 3; }   // ts는 5초 격자

message StatBatch {
  BatchHeader header = 1;
  int64 window_start_ms = 2; uint32 window_len_s = 3;   // 10
  repeated SqlStat sql = 4;            // ≤ 5,000
  repeated ExtStat ext = 5;            // ≤ 1,000
  repeated CounterDelta counters = 6;  // 제외·고아 step 등 창 단위 카운터(D-19·D-20)
}
message SqlStat {
  uint64 sql_id = 1; uint64 db_id = 2; TransactionKind kind = 3;
  uint32 count = 4; uint32 error_count = 5;
  uint64 sum_us = 6; uint32 max_us = 7;
  uint64 fetch_rows = 8;
  uint64 dbc_wait_sum_us = 9;          // 이 창에서 이 DB 대상의 커넥션 대기 합
}
message ExtStat {
  uint64 host_id = 1; TransactionKind kind = 2;
  uint32 count = 3; uint32 error_count = 4; uint32 timeout_count = 5; uint32 connect_fail_count = 6;
  uint64 sum_us = 7; uint32 max_us = 8;
}
message CounterDelta { string name = 1; uint64 delta = 2; }   // "transactions_ignored_total{reason=\"health\"}"

message ActiveTransactionSnapshot {
  BatchHeader header = 1;
  int64 snapshot_ts_ms = 2;
  uint32 total_active = 3;
  repeated ActiveTransaction items = 4;   // 경과 시간 내림차순 ≤ 500
  bool truncated = 5;
}
message ActiveTransaction {
  uint64 tx_id = 1; uint64 service_id = 2; TransactionKind kind = 3;
  int64 start_ts_ms = 4; uint32 elapsed_ms = 5;
  ActiveState state = 6;
  uint64 state_name_id = 7;            // SQL: sql_id, HTTP: host_id, DBC_WAIT: DB_TARGET, METHOD: 0
  uint64 thread_name_id = 8; int64 thread_id = 9; bool virtual_thread = 10;
  string user_id = 11; bytes remote_ip = 12;   // 모드 적용
}
enum ActiveState { ACTIVE_STATE_UNSPECIFIED = 0; IN_METHOD = 1; IN_SQL = 2; IN_DBC_WAIT = 3; IN_HTTP = 4; IN_ASYNC = 5; }
```

### 4.4 필드 규칙

| 규칙 | 내용 |
|---|---|
| 결측 | `optional` 필드(`cpu_time_us`, `alloc_bytes`, `InstanceUpdate.*`, `Throttle.*`)만 "없음"을 표현한다. 서버는 미설정이면 NULL로 저장한다(D-23). 그 외 숫자 0은 값 0이다 |
| 시간 | 벽시계는 `*_ms`(에이전트 시계, D-02). 길이는 `*_us` 또는 `*_ms`. 저장 시 µs → ms 반올림 |
| 식별자 | `tx_id`·`span_id` u64, `trace_id` 16B, 사전 키 u64 ≠ 0. `hash = 0`인 레코드는 `INVALID_ARGUMENT` |
| 문자열 상한 | `Limits` 값. 에이전트가 먼저 자르고 서버가 다시 검사한다. 초과 메시지는 배치 전체 거부(SEC-11) |
| 반복 상한 | `records` 5,000, `profiles` 전체 step 50,000, `series` 500, `sql` 5,000, `ext` 1,000, `items` 500, `headers` 10, `sql_ids` 64, `frame_ids` 30/64, `entries` 1,000 |
| 알 수 없는 enum | 수신 측은 `*_UNSPECIFIED`로 취급하고 `argos_collector_unknown_enum_total{field}`로 센다. 거부하지 않는다 |
| 알 수 없는 필드 | 보존·무시(proto3 기본). 양쪽 모두 |
| 개인정보 | `remote_ip`·`user_id`·`headers`·`url_path`·`detail`·`error_message`는 에이전트가 모드·마스킹을 적용한 뒤에만 채운다(SEC-30~3A). 서버는 모드를 `AgentHello`로 알고 인스턴스 메타에 기록한다 |
| 비밀값 | 어떤 메시지에도 토큰·비밀번호를 넣지 않는다. `ENV_DUMP` payload는 에이전트 마스킹 후(SEC-20) |

## 5. 세션 수립

```
에이전트                                  서버
  │── TLS 핸드셰이크(≤10s) ───────────────▶│
  │── Session 스트림 open ────────────────▶│  (10s 안에 Hello 없으면 PROTOCOL_ERROR로 닫음)
  │── AgentHello ─────────────────────────▶│  1 토큰 해시 조회 → 2 앱 소속 → 3 버전 → 4 등록 정책 → 5 상한
  │◀─ ServerHello(accepted, ids, config) ──│  세션 레지스트리 등록, 인스턴스 UP, 시계 1차 추정(server_time − sent_at)
  │── DictionaryEntries(핫 항목 선전송) ──▶│  (선택. 이후 새 해시 발생 시마다)
  │── SendTransactions(...)  [unary] ─────▶│  메타데이터 instance-id·session-id 검사
  │◀─ Ack(last_seq) ───────────────────────│
  │── Heartbeat(30s) ─────────────────────▶│
  │◀─ HeartbeatAck(t2,t3,offset,state) ────│
  │◀─ ConfigPush / Command / DictionaryRequest ─│  서버 → 에이전트 비동기
  │── ConfigApplyResult / CommandResult ──▶│
  │── AgentBye ───────────────────────────▶│  정상 종료. 서버는 5분간 DOWN 판정 유예(F-S28)
```

검사 순서와 결과(서버 `SessionHandler`):

| 순서 | 검사 | 실패 시 |
|---|---|---|
| 1 | 메타데이터 토큰 → SHA-256 → `agent_tokens` 조회(캐시 60초). 없음/만료/폐기 | `UNAUTHENTICATED` + `ServerHello{rejected, TOKEN_*}`, `retry_after_s = 0`(폐기·무효) / `3600`(만료) |
| 2 | 토큰의 앱 이름 == `hello.app_name` | `PERMISSION_DENIED` + `APP_MISMATCH`, `retry_after_s = 0` |
| 3 | 평문 연결인데 서버 `allow-plaintext=false` | `FAILED_PRECONDITION` + `PLAINTEXT_NOT_ALLOWED`, 0 |
| 4 | `protocol_version ∈ [min, current]`, `agent_version ∈ [X.(Y-2), X.Y]` | `FAILED_PRECONDITION` + `PROTOCOL_UNSUPPORTED`/`AGENT_VERSION_TOO_*`, 0. 메시지에 서버 버전·허용 범위 |
| 5 | (name, env) 앱 존재 또는 `auto-register[env]=true`. 아니면 승인 대기 등록 | `ServerHello{rejected, APP_NOT_REGISTERED}`, `retry_after_s = 300`. 승인되면 다음 재시도에 수락 |
| 6 | 앱당 인스턴스 수·시간당 신규 인스턴스·토큰당 동시 세션·분당 신규 세션(SEC-05·14) | `RESOURCE_EXHAUSTED` + `INSTANCE_LIMIT`/`RATE_LIMITED`, `retry_after_s = 60` |
| 7 | 같은 (app, instance_name)의 활성 세션이 있으면 구 세션에 `SessionClose{REPLACED}` 후 새 세션 수락 | — |

`instance_id`는 (app_id, instance_name)이 기존에 있으면 같은 값을 재사용하고(재기동 연속성), 없으면 새로 발급한다. `session_id`는 매 세션 새 UUID다.

버전 규칙(O-R01·R02): 프로토콜 정수 `1`. 서버는 `[1, 1]`로 시작한다. 메시지·필드·enum 값 추가는 프로토콜 버전을 올리지 않는다. 기존 필드 의미 변경이나 새 필수 동작은 `current + 1`로 올리고 서버는 직전 버전을 최소 두 마이너 동안 지원한다. 에이전트 SemVer는 서버 `X.Y` 기준 `X.(Y-2)`~`X.Y`를 수용하며, 더 새 에이전트는 거부하고(`AGENT_VERSION_TOO_NEW`) 관리 화면에 경고를 남긴다.

## 6. 배치 전송 규칙

| 규칙 | 내용 |
|---|---|
| seq | 배치 종류(tx·profile·metric·stat·active)별 독립 카운터. 프로세스 수명 동안 1부터 단조 증가. 세션 재수립·서버 재시작과 무관 |
| epoch | `agent_epoch`(프로세스마다 랜덤 u64)로 재기동 후 seq 리셋을 구분한다. 중복 제거 키 = `(instance_id, agent_epoch, type, seq)` |
| in-flight | 종류 합계 ≤ 4. 상한에 닿으면 큐에 머문다. 큐가 차면 가장 오래된 배치부터 드롭하고 `argos_agent_dropped_total{queue}` 증가(D-06) |
| deadline | 데이터 5초, 액티브 3초. 초과·`UNAVAILABLE`은 재시도 |
| 재시도 | 같은 배치·같은 seq로 최대 3회, 백오프 200ms → 1s → 3s. 그 뒤 드롭(카운트). 재시도 중에도 큐 상한은 유지된다 |
| 재시도 금지 | `INVALID_ARGUMENT`(형식 오류 — 드롭 + 카운트 + 에이전트 WARN 1회/분), `PERMISSION_DENIED`, `UNAUTHENTICATED`(세션 재수립으로 해결 시도, 토큰 폐기면 재접속 백오프 상한 5분) |
| 세션 없음 | `FAILED_PRECONDITION(SESSION_REQUIRED)` → 세션 재수립 → 같은 배치 재전송 |
| 쿼터 | 서버가 일부 항목을 버리면 `ACK_PARTIAL_DROP + dropped_items`. 에이전트는 재전송하지 않고 서버 드롭으로 센다. `Throttle` 힌트가 있으면 적용 |
| 중복 | 서버가 이미 WAL에 기록한 키면 `ACK_DUPLICATE + last_seq`. 재기록하지 않는다(D-13) |
| 서버 드롭 | WAL 상한(10 GiB) 초과 시 서버는 WAL에 쓰지 않고도 `ACK_OK`를 돌려주되 `dropped_items = 전체`로 표시하고 `argos_collector_dropped_total{reason="wal_full"}`로 센다. 에이전트가 재전송해 봐야 소용없기 때문이다 |
| 순서 | 종류 안에서 에이전트는 seq 순으로 보낸다. 서버는 순서를 가정하지 않는다(중복 제거 윈도 1,000) |
| 백프레셔 | `Ack.throttle.send_backoff_ms`가 있으면 다음 배치까지 그만큼 쉰다(큐는 계속 찬다). 스트리밍 RPC를 쓰지 않으므로 gRPC 내부 버퍼가 무제한으로 자라지 않는다 |
| 종료 | 종료 훅은 `AgentBye`를 보낸 뒤 큐에 있는 배치를 최대 1초 동안 보낸다. 블로킹 대기는 없다 |

적재 지연(N-15)은 서버 수신 시각 → ClickHouse insert ack 시각으로 정의한다(`sent_at_ms`는 에이전트 시계라 쓰지 않는다).

## 7. 사전 교환

1. 에이전트는 사전 종류별 LRU 캐시(기본 SERVICE 10,000 / SQL_TEXT 20,000 / 그 외 5,000)를 가진다. 새 해시가 처음 생기면 `DictionaryEntries`에 모아 **같은 배치의 데이터 RPC보다 먼저 또는 100ms 안에** 세션 스트림으로 보낸다(선전송). 캐시 적중이면 보내지 않는다.
2. 서버는 `(app_id, kind, hash)`를 PostgreSQL `dictionary`에 비동기 upsert하고 메모리 캐시에 둔다. 데이터 적재는 사전 해석을 기다리지 않는다(id만 저장).
3. 서버가 조회 시 모르는 id를 만나면 `DictionaryRequest{refs}`를 그 앱의 활성 세션 중 하나(가장 최근 heartbeat)에 보낸다. 에이전트는 캐시에 있으면 `DictionaryEntries`로 응답하고, 없으면(캐시 축출) 응답하지 않는다. 서버는 3회 요청 후 `<unknown:hash>`로 표시하고 `argos_dictionary_unresolved`로 센다.
4. 시간당 신규 항목 상한(10,000/인스턴스, SEC-13)을 넘으면 서버는 `<overflow>` 항목으로 매핑하고 `AgentEvent`가 아니라 서버 알림으로 경고한다. 에이전트 측에서는 서비스 사전 상한(10,000/앱, F-A29) 초과 시 `_other_` 서비스로 보낸다.
5. `SQL_TEXT`는 `norm_version`을 함께 보낸다. 서버는 `(hash, norm_version)`을 저장하고, 정규화기 버전이 바뀌면 13 문서의 마이그레이션 규칙을 따른다.

## 8. 설정 푸시·명령

### 8.1 ConfigPush

- 서버는 `AgentHello.remote_config_allow`에 **있는 키만** 푸시한다. 그래도 에이전트는 수신 시 다시 allow 목록·금지 패턴(SEC-25)·값 범위를 검사하고 거부 키를 `ConfigApplyResult.rejected`로 돌려준다(이중 검사).
- 기본 allow: `sampling.*`, `instrumentation.disabled`, `instrumentation.ignore-paths`, `log.level`, `argos.enabled`. 기본 불허: `capture.sql-parameters`, `capture.http-headers`, `capture.user-id`, `capture.query-string`, `capture.remote-ip`, `instrumentation.custom-methods`(SEC-23).
- 적용은 원자적이다: 전부 검증 후 적용하거나(부분 적용 없음) 거부 키가 있어도 허용 키는 적용한다 — **허용 키는 적용, 거부 키만 보고**를 채택한다(운영자가 한 번에 여러 키를 바꿀 때 전체 실패보다 유용). `applied_keys`·`rejected`로 결과가 명확하므로 혼동이 없다.
- `revert = true`는 서버가 보관한 이전 값 세트를 담은 일반 푸시다. 에이전트에 특별한 동작은 없다.
- 커스텀 메서드 변경은 retransform을 수반한다: 명령당 클래스 50개·분당 1회 상한, 결과 `retransformed_classes`·`duration_ms`.

### 8.2 Command

- 폐쇄 목록(4.1 `CommandType`). 서버 코드에도 이 enum 외의 타입을 만들 수 없고, 에이전트는 모르는 값에 `REJECTED`로 응답한다. 에이전트에는 코드·클래스 이름·스크립트를 받아 실행하는 경로가 없다. 이것이 "서버가 탈취돼도 임의 코드 실행 불가" 보증의 근거다(SEC-91).
- TTL: 에이전트는 `issued_at_ms`가 아니라 **수신 시각** 기준으로 `ttl_s` 안에 시작하지 않으면 `EXPIRED`. 서버도 전달 전 만료 명령을 폐기한다.
- 동시성: 인스턴스당 동시 실행 1건(서버 큐), 분당 10건. 에이전트 측 `THREAD_DUMP` 쿨다운 5초(`COOLDOWN` 응답).
- 권한은 서버가 집행한다(운영자: THREAD_DUMP·THREAD_STACK·SELF_DIAGNOSTICS, 관리자: CONFIG_RELOAD·ENV_DUMP). 에이전트는 권한을 모른다.
- `ENV_DUMP` payload는 에이전트가 키·값 패턴 마스킹을 적용한 뒤 보낸다. 마스킹을 끄는 설정은 없다(SEC-20).
- 결과는 청크(≤ 256 KiB)로 나눠 보내고 합 ≤ 1 MiB. 초과는 `truncated = true`. 스레드 덤프는 스레드당 깊이 64로 먼저 줄인다.

## 9. 시계 동기화 (D-02)

서버가 계산한다(에이전트의 산술을 신뢰하지 않는다).

```
라운드 n:  에이전트 Heartbeat{t1}  ──▶  서버 기록 (t1 → t2 = 수신 시각)
           서버 HeartbeatAck{t1, t2, t3 = 송신 시각}  ──▶  에이전트 기록 t4
라운드 n+1: 에이전트 Heartbeat{t1', prev_t1 = t1, prev_t4 = t4}
           서버: (t2, t3)를 prev_t1로 찾아 offset = ((t2 − t1) + (t3 − t4)) / 2,  rtt = (t4 − t1) − (t3 − t2)
```

- 서버는 인스턴스별 최근 5개 offset의 이동 중앙값을 보관하고(`instances.clock_offset_ms`), rtt가 중앙값의 3배를 넘는 라운드는 버린다.
- 1차 추정은 `ServerHello.server_time_ms − AgentHello.sent_at_ms`(rtt 미보정). 첫 heartbeat 라운드가 끝나면 교체한다.
- |offset| ≥ 1,000ms면 인스턴스 WARN(F-S28). |offset| ≥ 7일이면 세션을 수락하되 모든 레코드가 D-15 가드에 걸리므로 관리 화면에 "시계 설정 확인" 경고를 띄운다.
- 저장되는 타임스탬프는 **보정하지 않는다**(에이전트 시계 그대로, D-02). 오프셋은 표시·경고용이다.

## 10. 상태 코드 사용 규칙

| gRPC 코드 | 서버가 쓰는 경우 | 에이전트 동작 |
|---|---|---|
| `OK` | 정상. `Ack.status`로 세부 | — |
| `UNAUTHENTICATED` | 토큰 없음·형식 오류·해시 불일치·만료·폐기. 세션 중 폐기 시 `SessionClose{TOKEN_REVOKED_CLOSE}` 후 스트림 종료 | 재접속 백오프(1s → 5분 상한), 매 실패 WARN 1회/분. 데이터는 큐에서 드롭 |
| `PERMISSION_DENIED` | 토큰 앱 ≠ 요청 앱, 인스턴스가 토큰 앱 소속 아님 | 재시도 안 함. ERROR 1회, 세션 재수립 시도 |
| `FAILED_PRECONDITION` | `SESSION_REQUIRED`, 버전 불일치, 평문 불허, 모르는 명령 타입(에이전트 측) | SESSION_REQUIRED는 재수립 후 재전송. 나머지는 재시도 안 함 |
| `RESOURCE_EXHAUSTED` | 메시지·배치 상한 초과(디코드 전 거부), 쿼터, 레이트 리밋, 인스턴스 상한, 사전 상한. trailer `argos-retry-after-ms` | 상한 초과 배치는 분할 1회 시도 후 드롭. 쿼터·레이트는 `retry-after` 뒤 재시도 |
| `INVALID_ARGUMENT` | 해시 0, 음수 길이, enum 범위 밖 필수값, ts가 가드 밖(−7일 이전) | 드롭 + 카운트 + WARN 1회/분 |
| `UNAVAILABLE` | 서버 종료 중, 과부하(WAL fsync 지연 > 1초) | 재시도(백오프). `SessionClose{SERVER_SHUTDOWN, retry_after_s}` 수신 시 그 뒤 재접속 |
| `DEADLINE_EXCEEDED` | (클라이언트 측) | 재시도 3회 후 드롭 |

trailer 키: `argos-retry-after-ms`(u32), `argos-reject-reason`(RejectReason 이름). 본문·메시지에 토큰 값은 절대 포함하지 않는다.

## 11. 보안 고려

| 항목 | 규칙 |
|---|---|
| 토큰 | 메타데이터에만. 로그·에러 메시지·`reject_message`·`AgentEvent.detail`에 넣지 않는다. 에이전트 로그는 `argos_****` |
| 상한 | 모든 반복·문자열에 상한(4.4). 서버는 `maxInboundMessageSize`(해제 후)로 1차 거부, 디코드 후 2차 검사 |
| 쿼터 | 인스턴스당 트랜잭션 2,000/s, step 20,000/s, 메트릭 1,000/5s(SEC-12). 초과분은 `ACK_PARTIAL_DROP` |
| 미인증 | Hello 전 어떤 데이터 RPC도 `FAILED_PRECONDITION`. Hello 실패 IP는 분당 20회 → 10분 차단(SEC-05) |
| 명령 | 폐쇄 enum, TTL, 동시 1, 쿨다운, 마스킹은 에이전트 측. 결과는 서버가 암호화 저장(SEC-22) |
| 설정 | 에이전트 allow가 하드 캡. 서버는 allow를 Hello로 알고 그 안에서만 UI를 열어 준다 |
| 전파 정보 | `caller_app_id`는 `tracestate` `argos=<app>:<inst>:<svc>`에서. 외부 호스트에는 주입하지 않는다(SEC-38). 프로토콜은 이 값을 신뢰하지 않고 서비스 맵 집계에만 쓴다 |
| 사전 | `SQL_TEXT`는 정규화 성공분만. 실패분은 `<unparsed>` 해시(SEC-35) |
| 재생 | TLS + 세션 id + epoch·seq 중복 제거. 재생된 배치는 `ACK_DUPLICATE`로 무해 |

## 12. 진화 규칙

- 필드 번호는 영구적이다. 삭제는 `reserved N;` + `reserved "name";`으로 막는다.
- 새 필드는 `optional` 또는 기본값이 "없음"을 뜻하도록 설계한다.
- enum은 `*_UNSPECIFIED = 0`을 두고 새 값은 끝에 추가한다. 수신 측은 모르는 값을 UNSPECIFIED로 처리하고 센다.
- 새 RPC 추가는 호환 변경이다. 기존 RPC 제거는 메이저에서만.
- `Limits`는 서버가 Hello로 내려주므로 상한 변경은 프로토콜 변경이 아니다.
- proto 파일은 `proto/` 모듈에서 단일 소스로 관리하고, 생성 코드는 커밋하지 않는다(19 문서). CI는 `buf breaking`(또는 동등한 검사)으로 호환성 위반을 막는다.

## 13. 요구 ID 대응표

| 요구 | 이 문서의 대응 |
|---|---|
| F-A06 / SEC-38 | `trace_id`·`span_id`·`parent_span_id`·`incoming_trace`·`caller_app_id` |
| F-A12 / SEC-20~24 | `Command`·`CommandResult`·`CommandType` 폐쇄 목록·TTL·청크 |
| F-A13 / F-S29 / SEC-23 | `ConfigPush`·`ConfigApplyResult`·`remote_config_allow` |
| F-A22 / F-S21 / D-12 | `SendStats`·`SqlStat`·`ExtStat` |
| F-A26 / F-S28 | `AgentBye`·`Heartbeat`·`InstanceState` |
| F-A30 / D-23 | `virtual_threads`·`optional cpu_time_us`·`alloc_bytes` |
| F-S01 / SEC-01~08 | 2장 전송, 5장 검사 순서, 10장 코드 |
| F-S10 / D-06 / D-19 / D-20 | `Ack.dropped_items`, `Heartbeat.dropped_total`, `CounterDelta`, `AgentEvent` |
| F-S33 / SEC-11~15 | `Limits`, 4.4 상한, 6장 쿼터·`RESOURCE_EXHAUSTED` |
| D-02 | 9장 |
| D-11 | `tx_id` u64(시간 접두), `start_ts_ms` 병행 |
| D-13 | `BatchHeader.seq`·`agent_epoch`, `ACK_DUPLICATE`, 6장 |
| D-15 | `INVALID_ARGUMENT`(−7일), 서버 가드(12 문서) |
| D-16 | `MetricSeries.kind` |
| N-05 / N-30 | unary·deadline·재시도·드롭, Hello 비동기 |
| N-25 | ConfigPush → ApplyResult 왕복(10초 목표는 12 문서 측정) |
| O-R01~03 | 5장 버전 규칙, 12장 진화 규칙 |

## 14. 설계 결정

| ID | 결정 | 대안 | 선택 이유 | 영향 요구 |
|---|---|---|---|---|
| DSN-11-01 | 데이터는 배치 unary, 제어는 양방향 스트림 1개 | 전부 스트리밍 | 배치 단위 ack·중복 제거, gRPC 버퍼 무제한 증가 방지 | D-13, N-02 |
| DSN-11-02 | 중복 제거 키 = (instance_id, agent_epoch, 종류, seq) | (instance_id, seq) | 재기동 후 seq 리셋 구분, 종류별 독립 카운터로 단순화 | D-13 |
| DSN-11-03 | `profile`은 트랜잭션 단위(`TransactionProfile`)로 묶어 전송 | step 평면 배열 | tx_id·start_ts 반복 제거, `truncated`·`total_steps`를 자연스럽게 표현 | D-07 |
| DSN-11-04 | 메트릭은 배치 내 시리즈 사전(`MetricSeries`) + 샘플 | 이름 문자열 반복 / 전역 사전 | 500 시리즈 × 5초에서 전역 사전은 과잉, 반복은 낭비 | N-02, D-16 |
| DSN-11-05 | 시계 오프셋은 서버가 계산(이전 라운드 t1·t4를 다음 heartbeat에 실음) | 에이전트 계산·보고 | 서버가 신뢰 경계. 에이전트 산술 오류·조작 배제 | D-02 |
| DSN-11-06 | 서비스 이름·SQL·호스트 등은 u64 해시 + 사전 선전송, 적재는 해석을 기다리지 않음 | 서버 발급 id | 적재 경로 결합도 제거 | D-08, N-15 |
| DSN-11-07 | `Limits`를 ServerHello로 내려 에이전트가 배치 생성 시 적용 | 하드코딩 | 상한 변경이 프로토콜 변경이 되지 않게 | SEC-11 |
| DSN-11-08 | ConfigPush는 허용 키 적용 + 거부 키 보고(부분 적용) | 전부 아니면 전무 | 운영자 UX. 결과가 키 단위로 명확 | F-S29 |
| DSN-11-09 | WAL 상한 초과 시 서버는 `ACK_OK + dropped_items`로 응답(에이전트 재전송 무의미) | UNAVAILABLE | 에이전트 큐가 불필요하게 차지 않게, 드롭을 서버 드롭으로 정확히 집계 | D-06 |
| DSN-11-10 | `remote_ip`·`user_id` 마스킹은 에이전트에서 적용하고 모드를 Hello로 보고 | 서버 마스킹 | 원문이 네트워크·WAL에 실리지 않게(최소 수집) | SEC-31·32 |
| DSN-11-11 | 명령 타입은 폐쇄 enum, 코드·스크립트 인자 타입 없음 | 범용 "exec" | 임의 코드 실행 불가 보증 | SEC-91 |

## 15. 미결·가정

| # | 항목 | 가정 | 확인 |
|---|---|---|---|
| 1 | grpc-java `maxInboundMessageSize`가 압축 해제 후 크기에 적용되는지, gzip 스트리밍 해제 중 메모리 특성 | 해제 후 적용 | 12 문서 gzip 폭탄 테스트(SEC-11)로 실측. 아니면 서버 측 `Decompressor` 래핑으로 해제 바이트 카운트 |
| 2 | 에이전트 측 `protobuf-javalite` 채택 여부(크기 1/3) | P-3에서 결정 | javalite는 `map`·`optional` 지원 확인 |
| 3 | 전송 스택(grpc-netty vs grpc-okhttp vs 자체 HTTP/1.1+protobuf) | grpc-netty NIO·JDK TLS | A-1, P-3. 자체 스택으로 가면 이 문서의 RPC 모델은 유지하고 프레이밍만 바뀐다 |
| 4 | Java 8u252 ALPN 내장 여부와 8u261 TLS 1.3 | 8u252 ALPN, 8u261 TLS 1.3 | 릴리스 노트 확인. 설치 문서에 하한 명시 |
| 5 | `tracestate` 엔트리 길이(W3C: 키 ≤ 256, 값 ≤ 256, 총 32개) 안에서 `argos=<app>:<inst>:<svc>` | 16진 u32·u32·u64 ≈ 40자 | 문제없음. 10 문서에서 포맷 확정 |
| 6 | 세션 스트림의 서버 측 idle 타임아웃 | heartbeat 3회(90초) 미수신 시 `IDLE_TIMEOUT`으로 닫고 STALE/DOWN은 12 문서 상태 모델 | — |
| 7 | 액티브 스냅샷 0건일 때도 매 5초 전송하는 비용 | 메시지 수십 바이트, 무시 | "N초 전 기준" 표시를 위해 유지 |

## 16. 다른 설계 문서에 요구하는 것

| 대상 문서 | 요구 | 이유 |
|---|---|---|
| 10 에이전트 | `BatchHeader.seq`를 종류별 `AtomicLong`으로, `agent_epoch`를 기동 시 `SecureRandom` u64로. in-flight ≤ 4·재시도 3회·백오프 200ms/1s/3s·큐 시간 기반(120초분) 구현. Hello의 `remote_config_allow`·모드 필드를 유효 설정에서 채움. `prev_t1/prev_t4` 기록. `DictionaryEntries` 선전송 LRU. `CommandType` 폐쇄 처리·`COOLDOWN`·마스킹 | 6~9장 |
| 10 에이전트 | `ProfileStep.repeat_count`(연속 반복 접기)와 `TransactionProfile.truncated/total_steps`를 step 빌더에서 계산 | DSN-11-03 |
| 12 서버 | 5장 검사 순서·코드, 6장 중복 제거 윈도(종류별 1,000)·WAL 상한 응답, 9장 오프셋 계산·보관, `Limits` 값 설정화, trailer 키 | 5·6·9·10장 |
| 13 저장소 | `StepType`에 DBC=8·COMMIT=9·ROLLBACK=10 추가(ClickHouse `Enum8`), `profile_step.fetch_us → fetch_ms`, `repeat_count`, `batch_size` 컬럼. `xlog.dbc_wait_ms`, `request_id`, `dispatch_count`, `streaming`, `timed_out`, `incoming_trace` 컬럼. `app_version_id` UInt64(사전 해시). `sql_stat_raw.kind`·`dbc_wait_sum_us`, `ext_stat_raw.timeout_count`·`connect_fail_count`·`kind`. `dictionary.kind`에 DB_TARGET·APP_VERSION 추가 | 4.3 |
| 14 API | 인스턴스 메타에 `ip_mode`·`user_id_mode`·`query_string_mode`·`sql_param_mode`·`virtual_threads`·`cgroup`·`clock_offset_ms`·`remote_config_allow`·`instrumentation_modules` 노출 | Hello 필드 |
| 16 보안 | 토큰 해시 조회·캐시 무효화 이벤트, IP 기준 Hello 실패 차단, `reject_message` 내용 규칙 검토 | 5·10·11장 |
| 18 테스트 | 가짜 서버·가짜 에이전트가 이 문서의 코드·상한·중복 제거·시계 계산을 그대로 재현하는 계약 테스트. `buf breaking` CI | 12장 |
| 19 빌드 | `proto/` 모듈, 에이전트(Java 8)·서버(Java 21) 각각 생성, 생성 코드 미커밋, `buf` 린트·breaking | 12장 |
