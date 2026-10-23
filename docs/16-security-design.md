# Argos 보안·개인정보 설계

| 항목 | 내용 |
|---|---|
| 문서 번호 | 16 |
| 문서 버전 | 0.1 (초안) |
| 작성일 | 2026-10-23 |
| 상태 | 설계 검토 중 |
| 만족시키는 요구 | SEC-01~93(06 문서 전체), N-10a~d, N-17, O-S01~06, O-R06~08, O-P01~04, F-S01·F-S05·F-S07·F-S08·F-S32·F-S33, F-A12·F-A13·F-A14·F-A15(보안 부분), D-26, 미결 18·19·20 |
| 의존 문서 | 02 아키텍처(DSN-02-16~18·22, §5.2 데이터 분류표, §7 횡단 규칙), 06 보안 요구 |
| 이 문서에 의존하는 문서 | 10 에이전트, 11 프로토콜, 12 서버, 14 API, 15 UI, 17 배포·운영, 18 테스트, 19 저장소·빌드·릴리스 |

## 0. 문서 역할과 읽는 법

이 문서는 06 문서의 요구 SEC-01~93을 **구현 가능한 설계**로 바꾼다. 각 절은 06 문서의 영역(A~K)과 1:1로 대응하고, 13장의 대응표가 SEC 항목마다 설계 절·구현 문서·테스트 id를 묶는다. 다른 설계 문서(10·12·14·15·17·19)는 자기 영역의 보안 구현을 이 문서의 규칙대로 적는다. 충돌하면 02 → 이 문서 → 해당 설계 문서 순이다.

표기: 설정 키는 서버 `argos.*`(Spring), 에이전트 `argos-agent.yml`의 키 경로. 패키지는 서버 `io.argos.server.security.*`, 에이전트 `io.argos.agent.core.security.*`. 테스트 id는 `ST-<SEC 번호>-<순번>`(예: `ST-23-2`)이며 18 문서의 `securityTest` 스위트가 같은 id를 쓴다.

## 1. 위협 모델

이 장은 그대로 `docs/security/threat-model.md`(SEC-91, O-S03)로 옮겨 공개한다.

### 1.1 신뢰 경계

```
 ┌────────────────────────── (A) 계측 대상 앱 JVM ──────────────────────────┐
 │ 앱 코드 · 앱 라이브러리 · 앱 비밀값(환경변수·시스템 프로퍼티)              │
 │   ┌─ argos-agent ──────────────────────────────────────────────────────┐ │
 │   │ 계측(Advice) · 수집 버퍼 · 마스킹 · remote-config.allow · 명령 처리 │ │
 │   │ 토큰(파일/환경변수) · 로그(0700)                                     │ │
 │   └────────────────────────────────────────────────────────────────────┘ │
 └───────────────────────────────┬──────────────────────────────────────────┘
                                 │ (B) gRPC/TLS :9100  토큰 인증 · 상한 · 쿼터
 ┌───────────────────────────────▼──────────────────────────────────────────┐
 │ (C) argos-server JVM                                                      │
 │  collector(인터셉터 체인 → WAL) · web(쿠키 세션·CSRF·RBAC) · alert(목적지 검증) │
 │  admin(토큰 해시·감사) · 마스터 키 · 설정 토큰 · JWT 키 · WAL(0700)         │
 └────┬──────────────────────┬──────────────────────────┬───────────────────┘
      │ (D1) 전용 계정        │ (D2) 전용 계정             │ (E) HTTPS/WSS :8080
 ┌────▼──────────┐   ┌───────▼────────┐          ┌───────▼────────┐  ┌────────────┐
 │ ClickHouse    │   │ PostgreSQL     │          │ 브라우저(UI)   │  │ 알림 채널  │
 │ 127.0.0.1만   │   │ 127.0.0.1만    │          │ 운영자·조회자  │  │ 웹훅·SMTP  │
 │ writer/reader │   │ app/migrate/   │          │                │  │ (F) 아웃바운드│
 │ /admin        │   │ maint          │          └────────────────┘  └────────────┘
 └───────────────┘   └────────────────┘   (G) 백업 파일 0600 · (H) 릴리스 공급망
```

| 경계 | 넘는 것 | 통제 |
|---|---|---|
| A→B | 트랜잭션·프로파일·메트릭·통계·명령 결과 | 에이전트 측 마스킹·분류 기본값, 토큰, TLS 검증 |
| B→C | 위와 같음 + Hello | 인증 → 상한 → 쿼터 → 레이트 리밋 인터셉터, WAL |
| C→A | 설정 푸시·명령 | 서버 RBAC + 에이전트 `remote-config.allow` 하드 캡 + 명령 폐쇄 목록 |
| C→D | SQL | 전용 계정·최소 권한·로컬 바인드·쿼리 상한 |
| E→C | 조회·관리 요청·WS 구독 | 쿠키 세션·CSRF·Origin·RBAC·레이트 리밋·입력 상한 |
| C→F | 알림 전송 | 목적지 검증·서명·비밀값 암호화·본문 변수 제한 |
| G, H | 백업·릴리스 자산 | 권한·암호화 옵션, 서명·SBOM·재현 빌드 |

### 1.2 공격자 모델

| 공격자 | 위치·권한 | 노리는 것 |
|---|---|---|
| T1 앱 서버 로컬 계정 탈취자 | 앱 호스트의 OS 계정(앱 사용자 또는 다른 사용자) | 에이전트 토큰, 에이전트 로그 속 데이터, 서버로 위장 전송 |
| T2 같은 망의 중간자 | 에이전트↔서버, 브라우저↔서버 경로 | 수집 데이터 도청·변조, 세션 탈취, 다운그레이드 |
| T3 조회자 권한 내부자 | UI 로그인 가능 | 권한 밖 데이터(바인드 값·환경 덤프·타인 명령 결과), 대량 내보내기 |
| T4 운영자 계정 탈취자 | 운영자 역할 세션 | 민감 수집 원격 활성화, 웹훅 SSRF, 명령 남용 |
| T5 서버 호스트 침해자 | argos-server OS 계정 또는 DB 덤프 | 토큰 원문, 채널 비밀값, 모든 앱으로의 명령 |
| T6 공급망 오염자 | 의존성·빌드·릴리스 경로 | 변조된 에이전트 jar(= 모든 앱 RCE), 변조 이미지 |
| T7 앱 사용자(요청 1회) | 앱의 HTTP 클라이언트 | 수집 데이터에 XSS·CSV 수식·거대 입력을 심어 운영자 브라우저·PC 공격 |
| T8 오작동·악성 에이전트 | 유효 토큰을 가진 에이전트 | 과다 전송으로 서버·저장소 고갈 |

### 1.3 STRIDE 표

| 경계 | 자산 | 위협(STRIDE) | 대응 설계(절) | 잔여 위험 |
|---|---|---|---|---|
| A | 에이전트 토큰 | S 위장, I 노출 | 2.6 파일 0600·`token-file`·환경변수, 로그·번들 마스킹, 2.3 회전·폐기, 2.5 토큰별 상한 | T1이 앱 프로세스 메모리를 읽으면 토큰 획득 가능. 앱 단위 토큰이라 피해 범위는 그 앱 |
| A | 앱 비밀값(env·props) | I 노출 | 5.4 에이전트 측 마스킹(끄기 불가), 5.1 관리자 전용, 5.3 암호화 저장 | 패턴에 안 걸리는 이름의 비밀값. 사용자 패턴 추가로 완화 |
| A | 계측 범위 제어권 | E 권한 상승, T 변조 | 5.5 `remote-config.allow` 하드 캡, 5.6 금지 접두사, 1.4 명령 폐쇄 목록 | 앱 소유자가 allow에 민감 키를 넣으면 서버 권한으로 변경 가능(의도된 위임) |
| A | 앱 안정성 | D 서비스 거부 | 10 문서 자기 보호, 5.2 명령 쿨다운·동시 1건, retransform 상한 | 스레드 덤프 자체의 STW 비용 |
| B | 수집 데이터 | I 도청, T 변조 | 3 TLS 1.2+ AEAD·호스트명 검증·평문 이중 플래그 | `insecure-skip-verify`를 켠 운영자 |
| B | 서버 가용성 | D | 4 상한·쿼터·레이트 리밋·gRPC 하드닝 | 유효 토큰 수백 개의 분산 과다 전송은 쿼터 합계로만 제한 |
| B | 세션 | S 위장(토큰 재사용) | 2.4 인증 인터셉터, 2.3 폐기 즉시 종료, 2.5 IP 실패 차단 | 토큰 유출 후 폐기 전까지의 창 |
| C | 토큰 원문 | I(DB 덤프) | 2.2 SHA-256 해시만 저장 | 없음(원문 미보관) |
| C | 채널 비밀값·명령 결과 | I(DB 덤프) | 8.2~8.3 마스터 키 AES-256-GCM, 5.3 | 마스터 키와 DB 덤프를 함께 탈취(T5)하면 복호화 가능. 키 파일 0600·별도 보관 권고 |
| C | 설정 토큰·JWT 키 | S 초기 관리자 선점 | 7.8 1회용 토큰·30분·0600, 7.1 키 관리 | 서버 로그에 접근 가능한 자가 토큰 확보(의도된 신뢰) |
| D | DB 직접 접근 | T·I·E | 10.7 로컬 바인드·포트 비노출, 10.8 최소 권한 계정, 11.3 감사 로그 불변 | DB 호스트 root는 통제 밖 |
| E | UI 세션 | S 세션 탈취(XSS), R 부인, E CSRF | 7.1 httpOnly 쿠키, 7.6 CSRF·Origin, 7.2 회전·재사용 탐지, 7.4 즉시 무효화, 9 렌더 보안, 11 감사 | 운영자 PC 자체 침해 |
| E | 권한 경계 | E 수평·수직 상승 | 7.10 매트릭스(스펙 기반 테스트), 7.11 객체 수준 UUID·소유 검사 | 1.0은 앱 단위 권한 없음(1.x) |
| E | 가용성 | D | 7.13 레이트 리밋, 입력 상한, 7.14 에러 마스킹 | |
| F | 내부망 | I·E SSRF | 8.1 목적지 검증·리다이렉트 미추종·IP 고정 | DNS 응답이 허용 공인 IP → 전송 중 내부 IP로 바뀌는 경우는 IP 고정으로 차단 |
| F | 알림 수신자 | T 위조 | 8.4 HMAC 서명 | 수신 측이 검증하지 않으면 무의미 |
| F | 개인정보 2차 유출 | I | 6.11 템플릿 변수 제한 | |
| A·C | 수집 데이터 속 개인정보 | I | 6 전체(기본 미수집·마스킹·삭제 API·분류표) | 운영자가 `full` 모드를 켠 데이터. 감사로 추적 |
| G | 백업 | I | 10.9 0600·암호화 옵션·보존 | 암호화 옵션을 끈 운영 |
| H | 릴리스 자산 | T 변조, S 위장 | 10.1~10.5 SBOM·서명·재현 빌드·비밀 스캔·러너 격리 | 서명 키 관리(조직 소유·오프라인 마스터) |

### 1.4 에이전트 원격 명령 폐쇄 목록과 "임의 코드 실행 불가" 보증

에이전트가 서버 메시지로 수행할 수 있는 동작은 아래 **열거형**뿐이다(11 문서 `CommandType`). 열거형에 없는 값은 `UNKNOWN_COMMAND`로 거부하고 감사에 남긴다.

| 명령 | 에이전트 동작 | 입력 | 출력 상한 | 역할 |
|---|---|---|---|---|
| `THREAD_DUMP` | `ThreadMXBean.dumpAllThreads` 또는 JDK 21+ `HotSpotDiagnosticMXBean.dumpThreads` | 없음 | 1MB, 스레드당 깊이 64 | 운영자 |
| `THREAD_STACK` | 레지스트리의 특정 트랜잭션 스레드 `getStackTrace()` | tx_id | 64 프레임 | 운영자 |
| `SELF_DIAGNOSE` | 큐·드롭·모듈 상태·오버헤드·설정 해시 보고 | 없음 | 64KB | 운영자 |
| `CONFIG_RELOAD` | 로컬 yml 재읽기(allow 목록 포함) | 없음 | 적용 결과 | 관리자 |
| `CONFIG_PUSH` | 5.5 알고리즘으로 허용 키만 적용 | 키·값 목록 | 적용·거부 키 목록 | 관리자 |
| `ENV_DUMP` | 환경변수·시스템 프로퍼티·JVM 옵션을 5.4 마스킹 후 전송 | 없음 | 256KB | 관리자 |
| `KILL_SWITCH` | `argos.enabled` 플래그 토글 | on/off | 결과 | 관리자 |
| `CUSTOM_METHOD_RETRANSFORM` | `instrumentation.custom-methods`가 allow에 있을 때만 패턴 추가·제거 후 retransform(클래스 50·분당 1회) | 패턴 목록 | 변환 클래스 수·소요 | 관리자 |

보증 논리:

1. 명령 처리기는 `switch(CommandType)`의 고정 분기만 가진다. 문자열을 클래스 이름·메서드 이름·스크립트로 해석하는 경로가 없다(`Class.forName`·`MethodHandles.lookup().findXxx`·`ScriptEngine`·`Runtime.exec`·`ProcessBuilder` 사용 금지, ArchUnit `ST-91-1`).
2. 유일한 바이트코드 변경 경로인 `CUSTOM_METHOD_RETRANSFORM`은 **미리 컴파일된 동일 Advice**(METHOD step 기록)를 사용자 지정 매처에 붙일 뿐이며, Advice 본문은 서버가 바꿀 수 없다. 매처는 5.6 금지 접두사와 상한의 제약을 받고, `instrumentation.custom-methods`는 기본 allow에 없다.
3. 설정 푸시는 5.5의 화이트리스트를 통과한 키만 적용되며, 키 집합은 에이전트 코드에 열거되어 있다(임의 키 반영 없음).
4. 서버가 보내는 모든 메시지는 protobuf 스키마로 파싱되며 미지의 필드는 무시된다. 에이전트는 서버로부터 클래스·jar·스크립트를 받지 않는다(1.x 동적 attach도 로컬 파일만).
5. 따라서 서버가 완전히 탈취되어도 공격자가 에이전트에서 얻는 최대치는 "허용된 관찰 데이터(마스킹된 덤프 포함)"와 "허용 키 범위의 설정 변경·킬 스위치"다.

### 1.5 수용한 잔여 위험

| # | 잔여 위험 | 수용 이유 | 완화 |
|---|---|---|---|
| RR-1 | 앱 프로세스 메모리를 읽을 수 있는 로컬 공격자의 토큰 획득 | JVM 메모리 보호는 에이전트 범위 밖 | 앱 단위 토큰, 회전, 폐기 즉시 종료 |
| RR-2 | 마스터 키 + DB 덤프 동시 탈취 | 단일 노드 all-in-one에서 키·DB가 같은 호스트 | 키 파일 0600, `ARGOS_MASTER_KEY` 환경변수로 외부 비밀 관리와 연계 권고 |
| RR-3 | 운영자가 `insecure-skip-verify`·`allow-plaintext`·`full` 모드를 켬 | 운영 선택 | 명시 플래그 + 매 접속 WARN + 감사 |
| RR-4 | 1.0의 역할 3종으로 앱 단위 격리 불가 | 범위(1.x) | 민감 작업 관리자 전용, 감사 |
| RR-5 | gRPC 경로의 퍼징 미실시 | 1.x | 상한 테스트·protobuf 스키마 검증 |

## 2. 에이전트 토큰 서비스

### 2.1 토큰 형식·생성

| 항목 | 설계 |
|---|---|
| 형식 | `argos_` + base64url(32바이트 CSPRNG, 패딩 없음) = 총 49자. 정규식 `^argos_[A-Za-z0-9_-]{43}$` |
| 생성 | 서버 `SecureRandom.getInstanceStrong()` 폴백 `new SecureRandom()`. 생성 직후 해시만 저장하고 원문은 발급 응답 1회(`POST /api/v1/admin/apps/{appId}/tokens` 응답 `token` 필드)에만 포함. 응답은 `Cache-Control: no-store` |
| 식별 접두 | 원문 7~14번째 문자(8자)를 `prefix`로 저장·표시(`argos_AbCdEfGh…`). 충돌은 허용(조회는 prefix로 후보를 찾고 해시 비교) |
| 해시 | `SHA-256(token 원문 UTF-8)` 32바이트. 비교는 `MessageDigest.isEqual`(상수 시간) |
| 범위 | 토큰 1개 = 앱(`app_id`) 1개. 전역 토큰 없음. 앱당 활성(`active`·`rotating`) 토큰 최대 5개 |

### 2.2 저장 스키마 (PostgreSQL `argos.agent_tokens`)

```sql
CREATE TABLE agent_tokens (
  id               UUID PRIMARY KEY,                 -- UUIDv4
  app_id           BIGINT NOT NULL REFERENCES apps(id),
  name             VARCHAR(100) NOT NULL,            -- 운영자 메모(예: "prod-2026Q4")
  prefix           CHAR(8) NOT NULL,
  hash             BYTEA NOT NULL UNIQUE,            -- SHA-256
  status           VARCHAR(10) NOT NULL,             -- active | rotating | revoked | expired
  created_by       BIGINT NOT NULL REFERENCES users(id),
  created_at       TIMESTAMPTZ NOT NULL,
  expires_at       TIMESTAMPTZ,                      -- NULL = 무기한
  grace_until      TIMESTAMPTZ,                      -- rotating: 유예 종료
  revoked_at       TIMESTAMPTZ,
  revoked_by       BIGINT REFERENCES users(id),
  last_used_at     TIMESTAMPTZ,
  last_used_ip     INET
);
CREATE INDEX agent_tokens_prefix_idx ON agent_tokens(prefix) WHERE status IN ('active','rotating');
```

`last_used_at`은 세션 수립마다 갱신하되 쓰기 폭주를 피해 5분에 1회만 UPDATE한다.

### 2.3 발급·회전·폐기 흐름

```
발급   관리자 → POST /admin/apps/{id}/tokens {name, expires_in_days?}
       서버: 활성 수 < 5 확인 → 생성 → INSERT(hash) → 응답 {id, prefix, token(1회), expires_at}
       감사 TOKEN_ISSUED(prefix). UI는 "다시 볼 수 없음" 경고와 복사 버튼.
회전   관리자 → POST /admin/tokens/{id}/rotate {grace_days=7}
       서버: 새 토큰 발급(위 흐름) + 구 토큰 status=rotating, grace_until=now+grace
       유예 중 두 토큰 모두 인증 통과. 관리 화면: 구 토큰 사용 중 인스턴스 목록(last_used_ip·세션 레지스트리).
       grace_until 경과 → 스케줄러가 status=expired, 해당 세션 종료(2.4).
폐기   관리자 → DELETE /admin/tokens/{id}
       서버: status=revoked, revoked_at/by → SessionRegistry.closeAll(tokenId, UNAUTHENTICATED) (5초 내)
       → 재접속 Hello는 2.4에서 거부. 감사 TOKEN_REVOKED.
만료   expires_at 경과 → 인증 거부(UNAUTHENTICATED, 사유 token_expired). 30일 전부터 메트릭
       argos_agent_token_expiry_seconds{prefix} 노출 + 기본 규칙 "토큰 만료 임박"(01 부록 A).
```

### 2.4 인증 인터셉터와 세션 종료 훅

| 구성 | 설계 |
|---|---|
| 위치 | gRPC `ServerInterceptor` 체인 1번(`AgentAuthInterceptor`, 4.1). 모든 RPC(Session·Send*)에 적용 |
| 입력 | 메타데이터 `authorization: Bearer argos_…`. 없거나 형식 불일치 → `UNAUTHENTICATED` |
| 조회 | `TokenCache`(Caffeine, 키 = SHA-256 해시, TTL 60초, 최대 10,000)에서 `TokenRecord{id, appId, status, expiresAt, graceUntil}` → 미스 시 DB(prefix 인덱스 → 해시 비교) |
| 판정 | status ∈ {active, rotating(grace_until 미경과)} 그리고 expires_at 미경과 → 통과. 그 외 `UNAUTHENTICATED`(사유 코드: token_revoked / token_expired / token_unknown). 사유는 서버 로그·감사에만, 에이전트에는 코드만 |
| 컨텍스트 | 통과 시 `Context`에 `tokenId`, `appId`, `peerIp` 저장. Session RPC의 Hello는 `app.name`이 토큰의 앱과 일치해야 하며 불일치는 `PERMISSION_DENIED`(SEC-01) |
| 폐기 전파 | `TokenRevokedEvent(tokenId)` → `TokenCache.invalidate` + `SessionRegistry.closeAll(tokenId)`: 해당 토큰으로 수립된 모든 Session 스트림을 `Status.UNAUTHENTICATED.withDescription("token_revoked")`로 종료하고 in-flight Send*는 거부. 단일 노드라 이벤트는 프로세스 내 동기 호출 |
| 에이전트 측 | `UNAUTHENTICATED`를 받으면 지수 백오프를 **5분 고정**으로 전환하고 WARN 1회/시간(토큰 회전 안내 메시지). 재접속 성공 시 복귀 |

### 2.5 레이트 리밋 알고리즘

| 한도(SEC-05) | 키 | 알고리즘 | 초과 동작 |
|---|---|---|---|
| 토큰당 동시 세션 100(`argos.collector.limits.sessions-per-token`) | tokenId | `SessionRegistry`의 토큰별 활성 세션 수 비교(Hello 시) | `RESOURCE_EXHAUSTED`(session_limit), 메트릭 `argos_collector_rejected_total{reason="session_limit"}`, 감사 AGENT_SESSION_REJECTED |
| 토큰당 분당 신규 세션 60 | tokenId | 슬라이딩 윈도 카운터(Caffeine, 1분 버킷 2개) | 위와 같음(reason=session_rate) |
| IP당 인증 실패 분당 20 → 10분 차단 | peerIp | 실패 카운터(1분 윈도) → 임계 도달 시 `blockedUntil` 기록. 차단 중에는 토큰 조회 없이 즉시 `UNAUTHENTICATED` | 메트릭 `argos_collector_auth_blocked_total`, 감사 AGENT_AUTH_BLOCKED(IP) |
| 인스턴스 처리량 쿼터 | instanceId | 4.3 | |

차단 상태는 메모리에만 둔다(서버 재시작 시 초기화 허용). 관리 화면 "수집 상태"에 차단 IP 목록 표시.

### 2.6 에이전트 측 보관·마스킹

| 방식 | 설정 | 동작 |
|---|---|---|
| 파일 | `server.token-file: /etc/argos/token` | 기동 시 1회 읽기, 공백·개행 trim. 파일 권한이 그룹·타인 읽기 가능하면 WARN 1회(`ST-06-3`). 권장 0600 |
| 환경변수 | `ARGOS_TOKEN` | yml의 `${ARGOS_TOKEN}` 치환 또는 키 생략 시 자동 조회 |
| 평문 | `server.token: argos_…` | 허용하되 기동 시 `WARN token is stored in plaintext in argos-agent.yml; prefer token-file or ARGOS_TOKEN` 1회 |

마스킹 규칙: 토큰은 에이전트 로그·자가 진단·`--check` 출력·진단 번들·설정 해시 입력·`InstanceUpdate`의 설정 스냅샷에서 항상 `argos_********`(접두 8자 없이)로 표기한다. 설정 해시(`config_hash`)는 토큰 값을 제외한 유효 설정으로 계산한다. 진단 번들 스크립트(17 문서)는 수집한 모든 파일에 정규식 `argos_[A-Za-z0-9_-]{43}` → `argos_********` 치환을 적용한다(`ST-06-2`).

## 3. 전송 보안

### 3.1 서버 gRPC TLS 설정

| 키(`argos.collector.tls.*`) | 기본값 | 의미 |
|---|---|---|
| `enabled` | `true` | false면 평문 리스너. `allow-plaintext`와 함께 true여야 에이전트 평문 접속 허용(3.4) |
| `cert-file` / `key-file` | `conf/tls/server.crt`, `conf/tls/server.key` | PEM. 설치 스크립트가 자체 서명 인증서를 생성해 두고 WARN("self-signed, replace before production") |
| `key-password-file` | 없음 | 암호화된 키 사용 시 |
| `min-version` | `TLSv1.2` | `TLSv1.3`으로 올릴 수 있다. 1.1 이하 불가 |
| `cipher-suites` | 3.2 목록 | 명시 목록. 비어 있으면 기동 거부 |
| `provider` | `JDK` | `OPENSSL`(tcnative)은 선택. 서버 측만 허용, 에이전트는 항상 JDK |
| `reload.watch` | `true` | 인증서·키 파일 변경 감지(`WatchService`, 5초 디바운스) 후 `SslContext` 교체. 기존 연결은 유지, 새 핸드셰이크부터 적용. 관리 API `POST /admin/tls/reload`로 수동 리로드 |
| `allow-plaintext` | `false` | 평문 리스너 허용(3.4) |

HTTP(UI/API) TLS는 `argos.server.http.tls.*`로 같은 구조(`enabled` 기본 false, 리버스 프록시 443 종단을 권장. 17 문서 가이드). 서버가 직접 종단하면 같은 암호군 규칙을 적용한다.

### 3.2 암호군 목록

TLS 1.3: `TLS_AES_256_GCM_SHA384`, `TLS_AES_128_GCM_SHA256`, `TLS_CHACHA20_POLY1305_SHA256`.
TLS 1.2: `TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384`, `TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384`, `TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256`, `TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256`, `TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256`, `TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256`.
CBC·RSA 키교환·NULL·EXPORT·3DES·RC4는 목록에 없으므로 거부된다. CI는 testssl.sh로 1.0·1.1·CBC·RSA-kx 거부를 확인한다(`ST-07-1`). Java 8 에이전트(8u252~8u260)는 TLS 1.2 AEAD로 접속하고, 8u261+는 1.3을 협상한다.

### 3.3 인증서 만료 메트릭과 기본 규칙

- 서버는 로드한 리프 인증서의 `notAfter`로 `argos_server_tls_cert_expiry_seconds{listener="grpc|http"}` 게이지를 노출한다(리로드 시 갱신).
- 설치 시 생성되는 기본 알림 규칙(01 부록 A "인증서·토큰 만료"): 30일 전 경고, 7일 전 심각. 서버 자기 규칙은 앱이 아니라 `target.type=server`.
- 만료된 인증서로 기동하면 ERROR 로그 + 수집 상태 패널 경고. 기동은 막지 않는다(수집 연속성). 에이전트는 검증 실패로 접속하지 못하므로 운영자가 즉시 알게 된다.

### 3.4 에이전트 TLS 검증

| 키(`server.tls.*`) | 기본값 | 동작 |
|---|---|---|
| `enabled`(= `server.tls`) | `true` | false면 평문. 에이전트는 WARN(매 접속) + `AgentHello.plaintext=true`를 보내고, 서버는 `allow-plaintext=false`면 `FAILED_PRECONDITION(plaintext_not_allowed)`로 거부 |
| `ca-file` | 없음(JDK 기본 트러스트) | PEM CA 체인. 지정 시 그 CA만 신뢰 |
| `insecure-skip-verify` | `false` | true면 체인·호스트명 검증 생략. **매 접속마다** `WARN TLS verification disabled (insecure-skip-verify=true)`. `--check` 결과에 FAIL 표시(경고 등급) |
| 호스트명 검증 | 항상 on(`insecure-skip-verify`가 아닌 한) | `SSLParameters.setEndpointIdentificationAlgorithm("HTTPS")`. IP로 접속하면 SAN IP 필요 |
| `sni` | 서버 호스트명 | SNI 전송 |

에이전트는 gRPC Netty **JDK SSL 공급자**만 사용한다(네이티브 미포함, F-A35). TLS 1.3 가능 여부는 런타임에 따른다.

### 3.5 자체 CA 운영 가이드 개요 (17 문서 부록으로 전문 작성)

1. 오프라인 루트 CA(10년) → 발급용 중간 CA(3년) 2단 구성. 루트 키는 서버에 두지 않는다.
2. 서버 인증서: SAN에 DNS 이름(+ 필요 시 IP), 1년, `openssl` 명령 예시 제공.
3. 에이전트 배포: 중간+루트 체인 PEM을 `server.tls.ca-file`로 배포(구성관리 도구 또는 패키지).
4. 갱신: 만료 30일 전 알림 → 새 인증서를 `conf/tls/`에 교체 → 자동 리로드 → 에이전트 재접속 불필요.
5. 폐쇄망: CRL·OCSP를 쓰지 않는다. 유출 시 CA 교체 절차(에이전트 ca-file 재배포) 문서화.

### 3.6 mTLS(1.x) 자리

`argos.collector.tls.client-auth: none|optional|require`(1.0은 `none` 고정, 설정 키만 예약). 1.x에서 `require`면 클라이언트 인증서 CN/SAN을 앱 식별에 매핑하고 토큰은 보조 자격으로 둔다. `AgentAuthInterceptor`는 `Principal` 소스를 전략 인터페이스(`AgentCredentialResolver`)로 추상화해 둔다.

## 4. 수집 경로 하드닝

### 4.1 gRPC 인터셉터 체인

```
NettyServerBuilder.forPort(9100)
  .sslContext(...)                                  // 3.1
  .maxInboundMessageSize(4 MiB)                     // 압축 해제 후 크기(grpc-java 기준)
  .maxInboundMetadataSize(8 KiB)
  .maxConcurrentCallsPerConnection(16)
  .permitKeepAliveTime(1, MINUTES).permitKeepAliveWithoutCalls(false)
  .handshakeTimeout(10, SECONDS)
  .intercept(ConnectionLimitInterceptor)            // 0. 연결당 상태, peer IP 추출
  .intercept(AgentAuthInterceptor)                  // 1. 2.4
  .intercept(MessageLimitInterceptor)               // 2. 4.2 배치 건수·문자열 길이(디코드 직후, 서비스 전)
  .intercept(QuotaInterceptor)                      // 3. 4.3 인스턴스 처리량 쿼터
  .intercept(AuditInterceptor)                      // 4. 거부 사유 카운터·감사
  .addService(agentService)
// 리플렉션 서비스 미등록(ProtoReflectionService 의존성 자체를 제외)
```

| 인터셉터 | 거부 코드 | 메트릭 reason |
|---|---|---|
| ConnectionLimit | `UNAVAILABLE` | `connection_limit` |
| AgentAuth | `UNAUTHENTICATED` / `PERMISSION_DENIED` | `auth_failed` / `app_mismatch` |
| MessageLimit | `RESOURCE_EXHAUSTED` 또는 `INVALID_ARGUMENT` | `message_size` / `batch_count` / `field_length` |
| Quota | `RESOURCE_EXHAUSTED` | `quota_tx` / `quota_step` / `quota_metric` / `dictionary_rate` / `instance_limit` |

압축 해제 후 크기: grpc-java의 `maxInboundMessageSize`는 해제된 메시지에 적용된다(착수 시 확인, 15장). 추가로 `MessageLimitInterceptor`가 디코드된 배치의 항목 수와 문자열 필드 길이를 검사한다. gzip 폭탄 테스트(`ST-11-1`)는 1MB → 1GB 메시지가 메모리 증가 없이 거부됨을 서버 RSS로 확인한다.

### 4.2 상한 표

| 대상 | 상한 | 설정 키 | 초과 동작 |
|---|---|---|---|
| 메시지(압축 해제 후) | 4 MiB | `argos.collector.limits.max-message-bytes` | 디코드 전 거부 |
| 트랜잭션 배치 | 5,000건 | `…max-transactions-per-batch` | 배치 전체 거부(부분 수용 없음) |
| 프로파일 배치 | 50,000 step | `…max-steps-per-batch` | 거부 |
| 메트릭 배치 | 20,000 샘플 | `…max-metrics-per-batch` | 거부 |
| 통계 배치 | 20,000행 | `…max-stats-per-batch` | 거부 |
| step `detail` | 4 KiB | 에이전트 측 절단 + 서버 검증 | 서버는 초과 시 절단 + `truncated` 플래그(거부 아님) |
| 문자열 필드(서비스명·URL·메시지·사전 항목) | 2 KiB(사전 8 KiB) | | 절단 + 플래그 |
| 스택 프레임 | 30(ERROR) / 64(STACK·덤프) | | 절단 |
| 스레드 덤프 결과 | 1 MiB | | 절단 + `truncated` |
| 트랜잭션당 step | 1,000 | 에이전트 | 요약 전환(10 문서) |
| Hello 재시도 | 핸드셰이크 10초 | | 연결 종료 |

### 4.3 처리량 쿼터

| 쿼터(인스턴스별, SEC-12) | 기본 | 알고리즘 |
|---|---|---|
| 트랜잭션 | 2,000/s | 토큰 버킷(용량 = 2× 초당 한도, 보충 = 한도/s). 배치 수신 시 `tryConsume(n)`; 부족하면 **배치 전체를 드롭하고 ack는 반환**(에이전트 재전송 방지) + `argos_server_dropped_total{reason="quota_tx"}` + 수집 상태 패널 "서버 드롭(쿼터)" |
| step | 20,000/s | 동일 |
| 메트릭 | 1,000/5s | 동일 |
| 통계 행 | 5,000/10s | 동일 |

쿼터 드롭은 데이터 손실이므로 D-06에 따라 가시화하고 기본 알림 규칙 "수집 상태"가 잡는다. 쿼터는 앱별 오버라이드(`apps.quota_multiplier`)를 둔다.

### 4.4 사전·인스턴스 상한

| 항목(SEC-13·14) | 기본 | 동작 |
|---|---|---|
| 인스턴스당 시간당 신규 사전 항목 | 10,000 | 초과분은 `<overflow>` 해시(고정값)로 매핑, `argos_dictionary_overflow_total{instance}` |
| 사전 항목 길이 | 8 KiB | 절단 + `truncated` |
| 앱당 사전 총 항목 | 1,000,000 | 초과 시 신규 항목 `<overflow>`, 관리 화면 경고 |
| 미참조 정리 | `last_seen` 90일 | 야간 작업 삭제(ClickHouse 원본 보존 30일보다 길다) |
| 앱당 인스턴스 | 1,000 | Hello 거부 `RESOURCE_EXHAUSTED(instance_limit)` |
| 앱당 시간당 신규 인스턴스 | 100 | 거부 |
| 미접속 인스턴스 정리 | 7일 보관(숨김) → 30일 삭제 | 스케줄러 |

### 4.5 거부 코드·카운터·알림

- 모든 거부는 `argos_collector_rejected_total{reason}`(거부) 또는 `argos_server_dropped_total{reason}`(수용 후 드롭)로 센다.
- 인증 실패·세션 상한·쿼터 드롭은 감사 이벤트(11장)와 수집 상태 패널에 남는다.
- 기본 규칙 "수집 상태"(01 부록 A)가 드롭 ≥ 1을 경고한다.

### 4.6 gRPC 서버 하드닝 설정

리플렉션 서비스 미등록(의존성 제외), `permitKeepAliveTime` 1분·`permitKeepAliveWithoutCalls` false, 연결당 동시 호출 16, 핸드셰이크 타임아웃 10초, `maxConnectionIdle` 10분, `maxConnectionAge` 24시간(+ grace 5분, 재접속 유도로 설정 재협상), 메타데이터 8 KiB. 미인증 호출은 1번 인터셉터에서 끝나므로 서비스 코드에 도달하지 않는다(`ST-15-1`, `ST-15-2`).

## 5. 원격 명령·동적 설정 보안

### 5.1 명령 권한 매트릭스

| 명령(1.4) | 관리자 | 운영자 | 조회자 | 결과 열람 |
|---|---|---|---|---|
| THREAD_DUMP | ○ | ○ | × | 요청자·관리자 |
| THREAD_STACK | ○ | ○ | × | 요청자·관리자 |
| SELF_DIAGNOSE | ○ | ○ | × | 요청자·관리자(결과는 자가 진단 화면에 공개 요약도 표시) |
| CONFIG_RELOAD | ○ | × | × | 관리자 |
| CONFIG_PUSH | ○ | × | × | 관리자 |
| ENV_DUMP | ○ | × | × | 관리자(열람 감사 SENSITIVE_VIEW) |
| KILL_SWITCH | ○ | × | × | 관리자 |
| CUSTOM_METHOD_RETRANSFORM | ○ | × | × | 관리자 |

서버 `CommandService.request(type, instanceId, principal)`가 매트릭스를 검사하고(`@PreAuthorize` + 스펙 `x-argos-role`), 통과한 명령만 큐에 넣는다. 에이전트는 권한을 모르며 마스킹·쿨다운·allow만 담당한다.

### 5.2 명령 수명주기

| 항목 | 값(설정 키 `argos.commands.*`) | 구현 |
|---|---|---|
| id | UUIDv4 | 추측 불가 |
| TTL | 60초(`ttl`) | `commands.expires_at` 경과 시 상태 `EXPIRED`, 에이전트에 미전달. 전달 후 응답 없으면 `TIMEOUT`(120초) |
| 인스턴스당 동시 | 1(`max-inflight-per-instance`) | 큐에 `PENDING`/`SENT`가 있으면 `409 CONFLICT` |
| 인스턴스당 분당 | 10(`max-per-minute-per-instance`) | 초과 `429` |
| 에이전트 쿨다운 | 스레드 덤프 5초, 환경 덤프 60초, retransform 60초 | 에이전트가 `RESOURCE_EXHAUSTED(cooldown)` 결과 반환 |
| 감사 | 요청(COMMAND_REQUESTED)·결과 수신(COMMAND_COMPLETED)·거부(COMMAND_REJECTED)·열람(COMMAND_RESULT_VIEWED, ENV_DUMP는 SENSITIVE_VIEW) | 11장 |

### 5.3 결과 암호화·보존

`command_results(id UUID, command_id, instance_id, type, size_bytes, truncated, ciphertext BYTEA, created_at)`. `ciphertext`는 8.3 봉투 포맷(AAD = `command_results:` + id). 보존 30일(`argos.commands.result-retention-days`), 인스턴스당 최근 100건 초과분 삭제. 조회 API는 복호화 후 스트리밍 반환하고 `Cache-Control: no-store`.

### 5.4 환경 덤프 마스킹

에이전트 `EnvDumpMasker`가 전송 전에 적용한다. 끄는 설정은 없다.

키 패턴(대소문자 무시, 부분 일치):
```
(?i)(pass|pwd|secret|token|key|credential|auth|cert|private|session|cookie|salt|argos|apikey|api_key|access|signature|license|bearer|jwt|otp|pin\b|dsn|connection[-_]?string)
```
값 패턴(키가 안 걸려도 값이 걸리면 마스킹):
```
JWT          ^eyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}$
AWS key id   AKIA[0-9A-Z]{16}
긴 비밀 후보  (?<![A-Za-z0-9+/=_-])[A-Za-z0-9+/=_-]{32,}(?![A-Za-z0-9+/=_-])   (base64/hex 연속 32자 이상)
URL 자격증명  ://[^/\s:]+:[^/\s@]+@   → 비밀번호 부분만 마스킹
JDBC         (?i)(password|pwd|user)=([^;&\s]+) → 값 마스킹
PEM          -----BEGIN [A-Z ]*PRIVATE KEY-----
```
마스킹 표기: `****(len=N)`. `JAVA_TOOL_OPTIONS`·`JDK_JAVA_OPTIONS`·`CATALINA_OPTS` 같은 복합 값은 `-Dkey=value` 단위로 분해해 키·값 규칙을 각각 적용한다. 사용자 추가 패턴 `commands.env-dump.mask-patterns`(정규식 목록)은 **추가만** 가능하다.

테스트 케이스 30개(`ST-20-1`~`ST-20-30`): `DB_PASSWORD`, `db.password`, `SPRING_DATASOURCE_PASSWORD`, `ARGOS_TOKEN`, `AWS_SECRET_ACCESS_KEY`, `AWS_ACCESS_KEY_ID`(값 AKIA…), `GITHUB_TOKEN`, `JWT_SECRET`, `API_KEY`, `apiKey`, `X-Api-Key`, `PRIVATE_KEY`(PEM), `SSL_KEYSTORE_PASSWORD`, `KEYSTORE_PASS`, `SESSION_SECRET`, `COOKIE_SECRET`, `SALT`, `OAUTH_CLIENT_SECRET`, `AUTH_HEADER`(값 Bearer…), `LICENSE_KEY`, `OTP_SEED`, `DATABASE_URL`(postgres://u:p@h), `JAVA_TOOL_OPTIONS=-Dspring.datasource.password=x -Xmx2g`(password만 마스킹, Xmx 유지), `REDIS_URL`(redis://:p@h), `MAIL_PASSWORD`, `SMTP_PWD`, `SIGNING_KEY`, `ENCRYPTION_KEY`, 값만 걸리는 `SOME_VAR=eyJhbGciOi…`(JWT), 음성 케이스 `PATH`·`HOME`·`JAVA_HOME`·`LANG`(마스킹 안 됨) 4건 포함.

### 5.5 `remote-config.allow` 평가

에이전트 yml:
```yaml
remote-config:
  allow:                      # 기본값(명시하지 않아도 적용)
    - sampling.*
    - instrumentation.disabled
    - instrumentation.ignore-paths
    - log.level
    - argos.enabled           # 킬 스위치
  # 기본 불허(allow에 명시해야만 원격 변경 가능):
  #   capture.sql-parameters, capture.http-headers, capture.user-id.*, capture.query-string,
  #   capture.remote-ip, capture.trusted-proxies, capture.mask-patterns(축소 금지),
  #   instrumentation.custom-methods, server.*, remote-config.*
```

알고리즘(`RemoteConfigGate.apply(ConfigPush push)`):
1. `server.*`, `remote-config.*`, `log.dir`는 어떤 allow에도 **절대 불가**(하드코딩 거부, 이유 `immutable`).
2. 각 키에 대해 allow 패턴(글롭, `*`는 한 세그먼트, `**`는 이하 전부)과 매칭. 불일치 → `rejected_keys[key]=not_allowed`.
3. `capture.mask-patterns`는 allow에 있어도 **추가만** 허용(기존 패턴 제거 시도 → `rejected(reduce_masking)`).
4. 값 검증(타입·범위, 예: `sampling.profile.normal-rate` ∈ [0,1]) 실패 → `rejected(invalid_value)`.
5. 통과 키를 원자적으로 적용(설정 스냅샷 교체), `config_hash` 재계산, `ConfigApplyResult{change_id, applied_keys, rejected_keys{key: reason}, config_hash}` 반환.
6. 거부가 하나라도 있으면 에이전트 WARN 1줄(`remote config rejected keys: capture.sql-parameters(not_allowed)`), 서버는 감사 CONFIG_PUSH에 거부 목록을 남기고 UI 적용 결과에 표시한다.
7. `CONFIG_RELOAD`는 로컬 yml을 다시 읽으며 allow 목록도 갱신된다. 로컬 yml이 서버 푸시 값보다 우선한다(출처 표시: file > env > remote).

### 5.6 커스텀 메서드 금지 접두사·상한

`CustomMethodMatcherPolicy`: 패턴의 클래스 부분이 `java.`, `javax.`, `jakarta.`, `jdk.`, `sun.`, `com.sun.`, `io.argos.`, `net.bytebuddy.`로 시작하면(셰이딩 후 접두 포함) 무시 + WARN. 와일드카드로 우회(`*`로 시작)하는 패턴은 매칭 결과에서 위 접두 클래스를 제외한다. 패턴당 매칭 클래스 500 초과 시 패턴 비활성 + WARN + 자가 진단 보고. 원격 retransform은 클래스 50·분당 1회(10 문서).

### 5.7 감사 이벤트(명령·설정)

COMMAND_REQUESTED, COMMAND_SENT, COMMAND_COMPLETED, COMMAND_REJECTED, COMMAND_EXPIRED, COMMAND_RESULT_VIEWED, SENSITIVE_VIEW(type=env_dump|thread_dump|sql_params_full), CONFIG_PUSH(키·전후값·거부 목록), CONFIG_ROLLBACK, KILL_SWITCH(on|off), CUSTOM_METHOD_CHANGED.

## 6. 개인정보 구현

### 6.1 분류표 → 설정 키 매핑

| 필드(02 §5.2) | 에이전트 설정 키 | 기본 | 모드·동작 | 서버 측 |
|---|---|---|---|---|
| remote_ip | `capture.remote-ip` | 설치 마법사 선택(권장 `masked`) | `full` / `masked` / `off` | 인스턴스 메타 `ip_mode` 기록, UI "IP 마스킹됨" |
| X-Forwarded-For | `capture.trusted-proxies` | 비어 있음 | CIDR 목록이 있을 때만 XFF 사용 | |
| user_id | `capture.user-id.mode`, `.source`(header:<name> \| session:<attr> \| mdc:<key>), `.max-length`(128) | `off` | `off` / `plain` / `hashed` | 해시 모드면 검색 시 서버가 같은 HMAC 적용(앱 솔트 보관) |
| URL 쿼리스트링 | `capture.query-string`, `.whitelist`(파라미터 이름) | `off` | `off` / `whitelist` / `full`, 경로 2 KiB | 서비스 이름에는 절대 미포함 |
| HTTP 헤더 | `capture.http-headers`(이름 목록) | 비어 있음 | 목록의 헤더만, 값 1 KiB | |
| SQL 바인드 파라미터 | `capture.sql-parameters`, `.columns-whitelist` | `masked` | `masked` / `partial` / `full` | `full` 열람은 `pii.view` + 감사 |
| 예외 메시지 | `capture.exception-message.max-length`(1024), `capture.mask-patterns` | 기본 세트 on | 6.6 정규식 마스킹 후 전송 | 지문은 마스킹 후 메시지로 |
| SQL 원문 | (정규화기) | 정규화 성공분만 | 실패 시 미전송(`<unparsed>`) | 사전 64 KiB·90일 미참조 정리·관리자 삭제 |
| 스레드·환경 덤프 | 5장 | 명령 시만 | 마스킹(환경), 암호화 저장, 30일 | |

`remote-config.allow` 기본값에 위 키는 하나도 없다(5.5). 즉 수집 범위를 넓히려면 앱 서버의 yml을 바꿔야 한다.

### 6.2 IP 모드·마스킹 규칙

| 모드 | IPv4 | IPv6 | 저장 타입 |
|---|---|---|---|
| `full` | 그대로 | 그대로 | `IPv6`(IPv4는 매핑 주소) |
| `masked` | 마지막 옥텟 0(`203.0.113.0`) | 하위 80비트 0(`/48` 유지) | 동일 |
| `off` | `0.0.0.0` | `::` | 동일, UI "미수집" |

XFF 처리(SEC-37): `trusted-proxies`가 비어 있으면 소켓 주소. 있으면 XFF를 오른쪽에서 왼쪽으로 읽어 신뢰 CIDR에 속한 홉을 제거하고 처음 만나는 비신뢰 주소를 택한다. 파싱 실패·빈 값이면 소켓 주소. 그 뒤 모드 적용. 검색 API는 `masked` 인스턴스에 대해 `/24`·`/48` 단위 검색을 수행하고 결과에 `ip_mode`를 표시한다.

### 6.3 user_id HMAC

- 앱 생성 시 서버가 32바이트 솔트를 생성해 `apps.user_id_salt`(8.3 봉투 암호화)에 보관하고, `ServerHello.user_id_salt`로 에이전트에 전달(TLS 안에서만). 에이전트는 메모리에만 둔다.
- `hashed` = `base64url(HMAC-SHA256(salt, user_id))[0:16]`. 같은 앱의 모든 인스턴스가 같은 솔트를 쓰므로 D-08처럼 안정적이다.
- 검색(S-12): UI가 평문 user_id를 입력하면 서버가 해당 앱 솔트로 HMAC을 계산해 조회한다(`hashed` 앱만). 솔트 교체는 과거 데이터 검색 불가를 뜻하므로 관리자 확인 대화상자 + 감사.
- `plain` 모드는 길이 128 절단, 제어 문자 제거.

### 6.4 쿼리스트링

트랜잭션 URL 필드는 `?` 이전까지만 저장한다(`off`). `whitelist`는 지정 파라미터만 `?a=1&b=2` 형태로 재조립(순서 정렬, 값 256자). `full`은 원문(2 KiB). 어떤 모드에서도 서비스 이름(F-A02·A29)과 사전에는 쿼리스트링을 넣지 않는다(`ST-34-1`~`ST-34-3`).

### 6.5 SQL 정규화 실패 안전·PII 시드

정규화기(10 문서 `SqlNormalizer`)는 1ms 예산의 단일 패스 토크나이저다. 예외·예산 초과·미지원 토큰이면 결과를 버리고 `sql_id = xxh3_64("<unparsed>")`, `norm_failed=true`를 반환한다. 원문은 어떤 경로로도 전송하지 않는다(`ST-35-2`).

PII 시드 케이스(50케이스 중 PII 관련 20, `ST-35-1`): 한글 문자열 리터럴(`'홍길동'`), 이스케이프 따옴표(`'O''Brien'`, `'a\'b'`), 주석 안 리터럴(`/* kim@x.com */`), `IN (1,2,3)`·`IN ('a','b')`, 16진 `0xDEAD`, 과학 표기 `1.5e3`, 음수 `-42`, 달러 쿼팅 `$$secret$$`, `N'유니코드'`, 백틱 식별자 안 숫자 `` `col1` ``(보존), 식별자 `table_2024`(보존), 날짜 리터럴 `DATE '2026-01-01'`, `INTERVAL '1 day'`, MERGE, CTE, 배열 리터럴 `ARRAY[1,2]`, JSON 리터럴 `'{"phone":"010-1234-5678"}'`, `LIKE '%010%'`, PL/SQL 블록 안 리터럴, `{call proc('x')}`, Statement 인라인 `WHERE email='a@b.c'`. 각 케이스는 정규화 결과에서 시드 문자열이 grep 0건이어야 한다.

### 6.6 예외 메시지 마스킹 기본 정규식 세트

| 이름 | 패턴 | 치환 |
|---|---|---|
| email | `[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}` | `<email>` |
| kr_mobile | `01[016789][-\s]?\d{3,4}[-\s]?\d{4}` | `<phone>` |
| kr_rrn | `\d{6}[-\s]?[1-4]\d{6}` | `<rrn>` |
| card | `\b(?:\d[ -]?){13,19}\b` (Luhn 검사 통과 시만) | `<card>` |
| ipv4 | `\b(?:\d{1,3}\.){3}\d{1,3}\b` | `<ip>` |
| jwt | 5.4와 동일 | `<jwt>` |

적용 순서는 표 순서. 길이 상한 1,024자 절단은 마스킹 후. 사용자 패턴(`capture.mask-patterns`, 이름·정규식·치환)은 기본 세트 뒤에 추가. 같은 세트를 바인드 파라미터 `partial` 모드와 `detail` 필드에 적용한다. 에러 그룹 지문은 마스킹된 메시지에서 숫자·따옴표 문자열을 추가로 `?`로 치환한 "메시지 패턴"으로 계산한다(`ST-36-2`: 마스킹 전 값이 달라도 같은 그룹).

### 6.7 바인드 파라미터 3모드와 `pii.view`

| 모드 | 에이전트 전송 | 서버 저장 | UI 표시 |
|---|---|---|---|
| `masked` | `[String(11)]`처럼 타입·길이만 | detail | 그대로 |
| `partial` | 컬럼 화이트리스트에 해당하는 값은 6.6 마스킹 후 평문, 나머지는 타입·길이 | detail | 그대로 |
| `full` | 값 평문(6.6 마스킹은 적용) | detail(8.3 봉투 암호화, AAD `profile_step:`+tx_id. ClickHouse `detail_enc` 컬럼) | `pii.view` 권한 사용자에게만 복호화 표시, 열람 시 감사 SENSITIVE_VIEW(type=sql_params_full, tx_id) |

`pii.view`는 1.0에서 관리자 역할에 고정 포함되는 권한 이름이다(1.x 세분 권한 대비). 컬럼 화이트리스트 매칭은 정규화 SQL의 `col = ?` 패턴에서 위치별 컬럼명을 추출할 수 있을 때만 적용하고, 추출 불가 위치는 마스킹한다.

### 6.8 삭제 요청 API (SEC-33, F-S32)

```
DELETE /api/v1/admin/privacy/subjects?subject=user_id|ip&value=…&from=…&to=…   (관리자)
  → 202 {job_id}   PostgreSQL privacy_jobs(id, subject, value_hash, from, to, status, mutation_ids[], created_by, created_at, finished_at)
GET    /api/v1/admin/privacy/jobs/{job_id}  → 상태(QUEUED|RUNNING|DONE|FAILED), 삭제 추정 행 수
```
동작: `user_id`는 앱별 모드에 따라 평문 또는 HMAC 값으로 매칭. 범위를 일 파티션 단위로 쪼개 `ALTER TABLE xlog DELETE WHERE app_id=? AND ts BETWEEN ? AND ? AND user_id=?`와 `profile_step`(해당 tx_id 목록 선조회 후 `tx_id IN`) 뮤테이션을 `argos_admin` 계정으로 실행하고 `system.mutations`로 완료를 추적한다. 롤업은 집계값이라 대상에서 제외하며 응답과 문서에 명시한다. IP 삭제는 `remote_ip` 매칭(`masked` 모드면 `/24` 전체가 지워짐을 경고). 실행은 감사 PRIVACY_DELETE_REQUESTED/COMPLETED. 뮤테이션 비용은 P-4에서 측정(15장).

### 6.9 `docs/data-collected.md` 생성 규약

02 §5.2 분류표의 소스를 `docs/data-collected.yaml`(필드·분류·기본·설정 키·마스킹·보존·삭제 경로·관련 SEC)로 두고, 빌드 태스크 `:docs:generateDataCollected`가 영문 Markdown 표를 생성한다. 03 공통 DoD의 "분류표 갱신" 항목은 이 yaml 변경을 뜻하며, CI는 에이전트 수집 필드 열거형(`CollectedField`)과 yaml 항목 집합이 같은지 검사한다(`ST-30-1`).

### 6.10 설치 마법사 IP 모드 강제

설치 마법사 2단계 "데이터 수집 기본값"에서 IP 모드를 **선택해야만** 다음으로 진행한다(기본 선택 `masked`, 각 모드의 의미·법적 고려 안내 문구). 선택값은 `settings.default_ip_mode`에 저장되어 관리 화면의 에이전트 설치 안내 yml 스니펫에 반영된다. 에이전트 yml에 `capture.remote-ip`가 없으면 에이전트는 Hello 응답의 `default_ip_mode`를 따른다(서버 기본값 전달은 "축소 방향"만 허용: 서버가 `off`를 보내도 에이전트 로컬이 `full`이면 로컬 우선. 상충 시 로컬 우선 + WARN). 무인 설치는 `ARGOS_IP_CAPTURE_MODE` 환경변수 필수.

### 6.11 알림 템플릿 허용 변수

`{app}`, `{env}`, `{instance}`, `{service}`, `{host}`(외부 호스트명), `{metric}`, `{value}`, `{threshold}`, `{severity}`, `{duration}`, `{state}`, `{since}`, `{link}`, `{runbook}`, `{rule}`. 템플릿 저장 시 `\{[a-z_]+\}` 토큰이 이 집합 밖이면 `400 invalid_template_variable`. 렌더러는 변수 치환 외의 표현식·HTML을 지원하지 않는다(`ST-39-1`).

## 7. UI·API 인증·세션

### 7.1 쿠키와 액세스 토큰

| 항목 | 설계 |
|---|---|
| 액세스 쿠키 | `argos_at`; `HttpOnly; Secure; SameSite=Strict; Path=/api`; Max-Age 900 |
| 리프레시 쿠키 | `argos_rt`; `HttpOnly; Secure; SameSite=Strict; Path=/api/v1/auth/refresh`; Max-Age 86400 |
| CSRF 쿠키 | `argos_csrf`; `Secure; SameSite=Strict; Path=/`(JS 읽기 가능, httpOnly 아님) |
| `Secure` | 항상. `argos.security.cookie.secure=false`는 비운영용으로만 허용되며 기동 시 WARN. localhost는 브라우저가 보안 컨텍스트로 취급하므로 데모는 영향 없음 |
| 액세스 토큰 | JWT(JWS, `EdDSA/Ed25519`). 클레임: `sub`(user id), `sid`(세션 id), `role`, `perm`(`pii.view` 등), `iat`, `exp`(+15m), `jti`. 서명 키는 `conf/jwt.key`(0600, 설치 시 생성) 또는 `ARGOS_JWT_KEY`; 키 교체는 `kid`로 두 키 동시 수용 |
| 리프레시 토큰 | 불투명 32바이트 랜덤, DB에는 SHA-256 해시만 |
| 생성 클라이언트 | OpenAPI 생성 클라이언트는 `credentials: 'include'`, Authorization 헤더 미사용. 토큰을 JS 저장소에 두지 않는다(`ST-40-1`) |

### 7.2 리프레시 회전·재사용 탐지

```
POST /api/v1/auth/refresh (쿠키 argos_rt)
  1. hash(rt) → user_sessions 조회. 없음 → 401.
  2. 행이 revoked_at != null 또는 rotated_at != null (이미 사용된 토큰) → 재사용 탐지:
       같은 family_id의 모든 세션 revoked(reason=refresh_reuse) → 감사 SESSION_REUSE_DETECTED → 401.
  3. idle_expires_at/absolute_expires_at 경과 → revoked(reason=expired) → 401.
  4. 새 rt 생성 → 새 행(family_id 유지, rotated_from=이전 id), 이전 행 rotated_at=now
     → 새 access JWT(sid=새 행 id) → Set-Cookie 둘 다.
```

### 7.3 `user_sessions` 스키마와 검사 지점

```sql
CREATE TABLE user_sessions (
  id                  UUID PRIMARY KEY,
  user_id             BIGINT NOT NULL REFERENCES users(id),
  family_id           UUID NOT NULL,
  refresh_hash        BYTEA NOT NULL UNIQUE,
  created_at          TIMESTAMPTZ NOT NULL,
  last_seen_at        TIMESTAMPTZ NOT NULL,
  idle_expires_at     TIMESTAMPTZ NOT NULL,          -- last_seen + 8h (kiosk: 설정)
  absolute_expires_at TIMESTAMPTZ NOT NULL,          -- created + 24h (kiosk: 설정)
  rotated_at          TIMESTAMPTZ, rotated_from UUID,
  revoked_at          TIMESTAMPTZ, revoked_reason VARCHAR(40),
  user_agent          VARCHAR(512), ip INET,
  kiosk               BOOLEAN NOT NULL DEFAULT false
);
CREATE INDEX user_sessions_user_idx ON user_sessions(user_id) WHERE revoked_at IS NULL;
```

검사 지점: (1) 모든 API 요청: JWT 서명·exp 검증 후 `sid`를 `SessionStateCache`(Caffeine, 키 sid, 값 active|revoked, TTL 60초, 무효화 이벤트로 즉시 갱신)에서 확인. revoked면 401. (2) 리프레시: 7.2. (3) WebSocket: 7.7. 단일 노드이므로 캐시 무효화는 프로세스 내 이벤트로 즉시 반영된다(`ST-42-1`: 역할 강등 후 다음 요청 403).

### 7.4 무효화 훅

| 사건 | 동작 |
|---|---|
| 로그아웃 | 현재 세션 revoked(logout), 쿠키 삭제 |
| 비밀번호 변경·초기화 | 사용자의 모든 세션 revoked(password_changed), 현재 세션은 재발급 |
| 역할·권한 변경 | 모든 세션 revoked(role_changed) → 재로그인 |
| 관리자 강제 종료 | 지정 세션 또는 사용자 전체 revoked(admin_terminated), 감사 |
| 계정 비활성화·삭제 | 전체 revoked(account_disabled) |
| 재사용 탐지 | family 전체(7.2) |
| 동시 세션 상한 5 | 6번째 로그인 시 가장 오래된 세션 revoked(session_limit) |

모든 무효화는 `SessionRevokedEvent(sid…)` → `SessionStateCache` 갱신 → `WsConnectionRegistry.close(sid, 4401)`(7.7).

### 7.5 상황판 긴 세션

`argos.security.kiosk.enabled`(기본 false). 관리자가 특정 **조회자** 계정에 `kiosk_allowed=true`와 `kiosk_allowed_cidrs`를 설정하면, 그 계정이 허용 CIDR에서 로그인할 때 `kiosk=true` 세션(idle 24h·absolute 30일, 설정 가능)을 발급한다. 조건 불충족 시 일반 세션. kiosk 세션 발급·사용은 감사 KIOSK_SESSION_ISSUED. `?kiosk=1` UI 모드 자체는 세션 종류와 무관하다.

### 7.6 CSRF와 세션 고정

- 상태 변경 메서드(POST·PUT·PATCH·DELETE)는 `X-Argos-CSRF` 헤더 == `argos_csrf` 쿠키(더블 서브밋) **그리고** `Origin`(없으면 `Referer`)이 설정된 공개 오리진(`argos.server.public-origin`)과 일치해야 한다. `Sec-Fetch-Site`가 `cross-site`면 거부. 실패 403 `csrf_failed`.
- 로그인 성공 시 CSRF 쿠키와 세션(sid)을 새로 발급한다(세션 고정 방지, `ST-43-3`).
- 로그인 엔드포인트 자체는 CSRF 토큰 대신 Origin 검사만 적용(쿠키 없는 첫 요청).

### 7.7 WebSocket

- 핸드셰이크 인터셉터: `argos_at` 쿠키 JWT 검증 + `sid` 활성 확인 + `Origin == public-origin`. 실패 시 HTTP 401/403으로 업그레이드 거부. 업그레이드 후 인증 실패 상황(만료)은 `close(4401)`.
- 구독 메시지 `{type:"subscribe", topic:"xlog|counter|active|alerts", app_id, env}`: 역할 검사(조회자 이상 전부 허용, 1.x 앱 권한 지점 예약). 연결당 구독 20개, 수신 메시지 4 KiB, 초과 시 `close(4413)`.
- `WsConnectionRegistry(sid → connections)`: 세션 무효화 이벤트 1초 내 `close(4401)`(`ST-44-3`).
- 액세스 토큰 만료(15분) 시 WS를 끊지 않는다. 대신 클라이언트가 리프레시 성공 시 `{type:"reauth"}`로 새 JWT를 보내고 서버가 sid를 재확인한다. 리프레시 실패 → 클라이언트가 연결 종료.

### 7.8 설정 토큰 부트스트랩

```
서버 기동 → users 테이블에 ADMIN 없음?
  예 → setupToken = base64url(32B) → 로그 "Setup token: argos_setup_…(30분 유효)" + conf/setup-token(0600) 기록
       → GET /setup 화면 활성, POST /api/v1/setup {setup_token, admin_username, admin_password, ip_mode}
         헤더 X-Argos-Setup-Token 또는 본문 token 검증(상수 시간), 30분 경과 시 401 → 재기동으로 재발급
       → 성공 시 관리자 생성(비밀번호 정책 적용, must_change=false), setup-token 파일 삭제, 메모리 토큰 폐기,
         감사 SETUP_COMPLETED → 이후 /setup은 404
  무인: ARGOS_ADMIN_USERNAME + ARGOS_ADMIN_PASSWORD_FILE(0600) + ARGOS_IP_CAPTURE_MODE가 있으면
        기동 시 관리자 생성(must_change=true), 마법사 비활성
```

### 7.9 비밀번호 정책

| 항목 | 값 |
|---|---|
| 길이 | 12~128자(유니코드 코드포인트 기준), 앞뒤 공백 제거 없음 |
| 금지 목록 | 번들 `passwords-denylist.txt`(흔한 비밀번호 1만, MIT 라이선스 소스에서 생성, 빌드 시 SHA-256 해시 집합으로 변환) + 사용자명·"argos" 포함 금지(대소문자 무시) |
| 복잡도 규칙 | 없음(NIST 800-63B) |
| 해시 | `BCryptPasswordEncoder(12)`. 기본값. `argos.security.password.encoder=argon2id`(m=64MiB, t=3, p=1) 선택 가능. 업그레이드 시 로그인 성공 때 재해시 |
| 잠금 | 실패 10회 → 15분, 이후 연속 잠금마다 30분·60분(최대). 성공 시 카운터 초기화. 관리자 해제 API. 잠금·해제는 감사 |
| 강제 변경 | `must_change_password`(초기화·무인 설치 생성) 시 비밀번호 변경 API 외 모든 API 403 `password_change_required` |
| 로그 | 비밀번호·토큰 필드는 `@Sensitive` 어노테이션 → 로깅·toString 제외(ArchUnit `ST-48-4`) |

### 7.10 권한 매트릭스

역할: `ADMIN` ⊃ `OPERATOR` ⊃ `VIEWER`. 권한 `pii.view`는 1.0에서 ADMIN에 고정. OpenAPI 각 operation에 `x-argos-role: admin|operator|viewer`(+ `x-argos-permission: pii.view` 선택)를 표기하고, 스펙 린트는 누락 시 실패, 테스트 생성기는 (operation × 역할 3) 요청을 만들어 허용·거부를 단언한다(`ST-49-1`).

| 엔드포인트 그룹 | 조회자 | 운영자 | 관리자 |
|---|---|---|---|
| 조회 API(전체 현황·대시보드·XLog·트랜잭션·트레이스·통계·인스턴스·메트릭·알림 이력 조회) | ○ | ○ | ○ |
| CSV 내보내기 | ○(감사) | ○(감사) | ○(감사) |
| 바인드 파라미터 `full` 복호화 표시 | × | × | ○(`pii.view`, 감사) |
| 명령: THREAD_DUMP·THREAD_STACK·SELF_DIAGNOSE | × | ○ | ○ |
| 명령: ENV_DUMP·CONFIG_RELOAD·CONFIG_PUSH·KILL_SWITCH·RETRANSFORM | × | × | ○ |
| 명령 결과 조회 | × | 본인 요청분 | 전부 |
| 알림 규칙 생성·수정·삭제·비활성, 음소거, ACK | × | ○ | ○ |
| 알림 채널 생성·URL/비밀값 변경·삭제·테스트 전송 | × | × | ○ |
| 알림 채널 선택(규칙에 연결) | × | ○ | ○ |
| 앱 등록 승인·앱 그룹·Apdex T·등록 정책 | × | × | ○ |
| 앱 삭제 | × | × | ○ |
| 토큰 발급·회전·폐기 | × | × | ○ |
| 설정 변경(F-S29)·되돌리기 | × | × | ○ |
| 사용자·역할·세션 강제 종료·잠금 해제 | × | × | ○ |
| 감사 로그 조회·내보내기 | × | × | ○(감사) |
| 개인정보 삭제 API·개인정보 설정 | × | × | ○ |
| 보존 설정·TLS 리로드·마스터 키 상태 | × | × | ○ |
| 자기 계정(비밀번호 변경·세션 목록·로그아웃) | ○ | ○ | ○ |
| `/actuator/health` | 무인증 | | |
| `/actuator/prometheus` | 메트릭 토큰 또는 베이직 + IP 허용 목록 | | |

### 7.11 객체 수준 권한

식별자형 리소스(명령·명령 결과·세션·토큰·채널·개인정보 작업·설정 변경)는 UUIDv4. `GET /commands/{id}`·`/commands/{id}/result`는 `requested_by == principal` 또는 ADMIN(`ST-50-1`). 앱 범위 조회는 `app_id` 필수이며 1.x 앱 권한은 `AppAccessPolicy.check(principal, appId)` 한 지점에서 필터한다(1.0은 항상 허용).

### 7.12 Actuator

`management.endpoints.web.exposure.include=health,info,prometheus`, 나머지 비활성(`env`·`heapdump`·`threaddump`·`loggers`·`mappings` 404, `ST-55-1`). `health`는 `show-details: never`, 무인증. `prometheus`는 `argos.metrics.auth: bearer|basic`(기본 bearer, 토큰은 설치 시 생성 `ARGOS_METRICS_TOKEN`) + `argos.metrics.allowed-cidrs`(기본 비어 있음 = 전부 거부 → 설치 스크립트가 로컬·사설 대역 안내). `info`는 버전만.

### 7.13 API 레이트 리밋

| 대상 | 한도 | 키 | 구현 |
|---|---|---|---|
| 로그인·설정·리프레시 | 20/분 | IP | Bucket4j(Apache-2.0) 인메모리 |
| 일반 API | 50/초(버스트 100) | 사용자(sid) | 동일 |
| 검색(ID 조회·텍스트 검색)·CSV 내보내기·트랜잭션 검색 | 10/분 | 사용자 | 동일 |
| 개인정보 삭제 API | 5/시간 | 사용자 | 동일 |
| WebSocket 구독 변경 | 60/분 | 연결 | |

초과: `429` + `Retry-After` + 본문 `{code:"rate_limited"}`. 메트릭 `argos_api_rate_limited_total{group}`.

### 7.14 에러 응답 마스킹

표준 본문 `{code, message, details?, request_id}`. 5xx는 `code:"internal_error"`와 `request_id`만. 예외 메시지·스택·SQL·파일 경로·클래스 이름은 응답에 넣지 않는다(`@ControllerAdvice`, `server.error.include-*=never`). 테스트는 의도적 예외 주입 후 본문 스냅샷(`ST-56-2`).

## 8. 알림 채널 보안

### 8.1 웹훅 목적지 검증 알고리즘

`WebhookDestinationValidator.validate(url)`은 채널 저장·수정·테스트 전송·실제 전송 **네 시점 모두**에서 실행한다(저장 시 통과한 호스트가 나중에 내부 IP로 바뀌는 경우 대비).

```
1. URL 파싱 실패 → reject(invalid_url)
2. scheme == https 아니면: argos.alert.webhook.allow-http=true 일 때만 http 허용, 아니면 reject(scheme)
3. 사용자 정보(user:pass@) 포함 → reject(userinfo)
4. host가 IP 리터럴이면 그 IP, 아니면 DNS 해석(A·AAAA 전부, 타임아웃 2초). 해석 실패 → reject(dns)
5. 해석된 모든 IP에 대해:
     allow-cidrs(argos.alert.webhook.allow-cidrs)에 속하면 통과
     아니면 금지 대역에 속하면 reject(private_destination):
       0.0.0.0/8, 10.0.0.0/8, 100.64.0.0/10, 127.0.0.0/8, 169.254.0.0/16, 172.16.0.0/12,
       192.0.0.0/24, 192.0.2.0/24, 192.168.0.0/16, 198.18.0.0/15, 198.51.100.0/24, 203.0.113.0/24,
       224.0.0.0/4, 240.0.0.0/4, 255.255.255.255/32,
       ::/128, ::1/128, ::ffff:0:0/96(매핑된 IPv4는 IPv4 규칙 재적용), fc00::/7, fe80::/10, ff00::/8,
       서버 자신의 모든 인터페이스 주소와 public-origin 호스트의 해석 결과
6. 통과한 IP 중 첫 번째로 연결을 고정한다: HttpClient에 커스텀 DNS 리졸버(해석 결과 재사용)로 연결하고
   SNI·Host 헤더는 원 호스트명. 전송 직전에 1~5를 다시 수행(DNS 리바인딩 방지).
7. HttpClient: 리다이렉트 NEVER(3xx는 실패로 기록), 연결 3초·응답 5초, 응답 본문은 읽어서 버리고(최대 64KB) 저장·표시 금지,
   상태 코드·소요만 notification_deliveries에 기록.
```

테스트(`ST-51-1`~`ST-51-6`): 금지 대역 각 1건 저장 400, `http://` 기본 거부, 리다이렉트 응답 시 미추종, 허용 CIDR 설정 시 통과, 테스트 전송에도 동일, 응답 본문 비노출.

### 8.2 마스터 키 관리

| 항목 | 설계 |
|---|---|
| 소스 | `ARGOS_MASTER_KEY`(base64 32바이트) 우선, 없으면 `conf/master.key`(0600). 둘 다 없으면 **기동 거부**(설치 스크립트가 생성) |
| 권한 검사 | 파일이 그룹·타인 읽기 가능하면 기동 거부(`argos.security.master-key.permissive=true`로만 우회 + WARN) |
| 키 id | `kid = SHA-256(key)[0:8] hex`. 봉투에 기록 |
| 교체 | `argos-admin secrets rekey --new-key <path>`: 새 키 로드 → 모든 암호화 컬럼(notification_channels.secret_enc, command_results.ciphertext, apps.user_id_salt_enc, profile_step detail_enc는 ClickHouse라 제외·보존 7일로 자연 만료) 재암호화 → 완료 후 구 키 제거. 실행 중 서버는 두 kid를 모두 복호화 가능(`argos.security.master-key.previous`) |
| 메모리 | `byte[]`로 보관하고 종료 시 0으로 덮어씀. 로그·toString 금지 |

### 8.3 봉투 암호화 포맷

```
v1:<kid 8hex>:<base64url(nonce 12B)>:<base64url(ciphertext||tag 16B)>
```
AES-256-GCM, nonce는 CSPRNG 12바이트(행마다 새로), AAD = `<table>:<column>:<row id>`(열 바꿔치기 방지). 복호화 실패는 `DecryptionException` → 500이 아니라 `409 secret_unavailable`(키 교체 중 안내). 구현 클래스 `io.argos.server.security.crypto.EnvelopeCipher`. 암호화 대상 컬럼은 `*_enc` 접미로 통일하고 JPA `AttributeConverter`로 투명 처리한다.

### 8.4 웹훅 HMAC 서명

헤더 `X-Argos-Signature: t=<unix초>,v1=<hex(HMAC-SHA256(secret, t + "." + body))>`. 채널 생성 시 32바이트 서명 키 자동 생성(봉투 암호화 저장, UI는 1회 표시·재발급). 회전: `POST /admin/channels/{id}/signing-key/rotate` → 새 키 추가, 구 키 유예 7일 동안 헤더에 `v1=` 두 개(새·구) → 유예 후 구 키 삭제. 수신 측 검증 예시(5분 허용 오차)를 문서에 제공. 페이로드에 `schema_version: 1`.

### 8.5 SMTP

`argos.alert.smtp.*`: `host`, `port`(587), `username`, `password_enc`, `starttls: required`(기본), `ssl.check-server-identity: true`, `insecure: false`. `insecure=true`면 평문·검증 생략 허용 + 기동 WARN + 채널 화면 경고 배지. 수신자 도메인 허용 목록 `allowed-recipient-domains`(비어 있으면 전부). 테스트 전송은 관리자만.

## 9. UI 렌더링 보안

### 9.1 신뢰할 수 없는 입력 목록

서비스 이름, URL·경로, SQL 정규화문·원문·바인드 파라미터, 예외 클래스·메시지·스택 프레임, 스레드 덤프·환경 덤프 본문, HTTP 헤더 이름·값, user_id, 인스턴스·호스트·스레드·풀·커넥터 이름, 앱·환경·그룹 이름, 버전 문자열, 알림 규칙·채널·템플릿 이름, 사용자 이름·메모, 외부 호출 호스트·경로, 사전 문자열 전부, 명령 결과, 배포 이벤트 제목·상세, JVM 옵션. 이 목록은 `ui/src/security/untrusted.md`에 두고 컴포넌트 리뷰 체크리스트로 쓴다.

### 9.2 린트 설정

```js
// ui/eslint.config.js (발췌)
'react/no-danger': 'error',
'react/no-danger-with-children': 'error',
'no-eval': 'error', 'no-implied-eval': 'error', 'no-new-func': 'error',
'no-restricted-properties': ['error',
  { object: '*', property: 'innerHTML' }, { property: 'outerHTML' },
  { property: 'insertAdjacentHTML' }, { object: 'document', property: 'write' }],
'no-restricted-syntax': ['error', { selector: "JSXAttribute[name.name='dangerouslySetInnerHTML']", message: 'Use SafeText/SafeCode' }],
'@argos/no-raw-href': 'error',        // <a href={expr}>는 safeHref() 경유만
```
예외는 파일 단위 `/* eslint-disable … -- 사유: … (SEC-60 allowlist) */`와 `ui/security-allowlist.txt` 등록, CODEOWNERS 리뷰 필수.

### 9.3 안전 렌더 컴포넌트 규약

| 컴포넌트 | 용도 | 규칙 |
|---|---|---|
| `<SafeText value />` | 모든 수집 문자열 | React 텍스트 노드만. 길이 상한·말줄임 |
| `<SafeCode lang value />` | SQL·스택 | 토크나이저가 토큰 배열을 만들고 `<span className>`으로 렌더. HTML 문자열 생성 금지. 서드파티 하이라이터 사용 시 출력이 이스케이프됨을 컴포넌트 테스트로 고정 |
| `<SafeLink href external />` | 외부 호출 URL·로그 링크·런북 | `safeHref()` 통과분만 `href`, `rel="noopener noreferrer"`, 외부는 `target=_blank` |
| 툴팁·표 셀 | | 문자열 prop만 받고 ReactNode는 내부 컴포넌트만 허용 |

`safeHref(url)`: `http:`, `https:`, `/`로 시작하는 내부 경로만 허용. `javascript:`, `data:`, `vbscript:`, 프로토콜 상대(`//`)는 `undefined`(링크 비활성). 로그 링크 템플릿(F-U18) 치환값은 `encodeURIComponent`.

### 9.4~9.5 XSS 시드 데이터와 E2E 훅

샘플 앱(`sample-apps/shop-boot3`) `/xss/*` 엔드포인트가 아래 페이로드를 서비스 경로·SQL 리터럴·예외 메시지·요청 헤더(`X-Seed`)·user_id 헤더·스레드 이름(`Thread.currentThread().setName`)·배포 이벤트 제목(API)로 심는다.

```
<img src=x onerror=window.__xss=1>
"><svg/onload=window.__xss=1>
javascript:window.__xss=1
'"><script>window.__xss=1</script>
<a href="javascript:window.__xss=1">x</a>
{{7*7}} ${7*7} <%= 7*7 %>
=HYPERLINK("http://evil","x")     (CSV용)
‮\u0000<b>bidi</b>
<iframe srcdoc="<script>parent.__xss=1</script>">
%3Cscript%3Ewindow.__xss%3D1%3C/script%3E (이중 인코딩)
```
Playwright 전역 훅(`ui/e2e/global.setup.ts`): 모든 테스트의 `page` 생성 시 `page.on('dialog', fail)`, `page.on('pageerror', fail)`, 각 내비게이션·상호작용 후 `expect(await page.evaluate(() => window.__xss)).toBeUndefined()`; CSP 위반은 서버 `/api/v1/csp-report` 카운터(`argos_csp_violations_total`)를 테스트 시작·종료에 비교해 증가 0 단언(`ST-62-1`, `ST-46-1`). 시드 데이터 세트는 매 E2E 실행 전에 적재한다.

### 9.6 CSV 수식 주입 방지

`CsvWriter`: 셀 값이 `=`, `+`, `-`, `@`, `\t`, `\r`로 시작하면 앞에 `'`를 붙이고, 모든 셀을 `"`로 감싸며 내부 `"`는 `""`. 파일은 UTF-8 BOM, 줄 끝 CRLF. 내보내기는 감사 EXPORT_CSV(화면·필터·행 수). 테스트 `ST-63-1`.

### 9.7 딥링크 파라미터 검증

URL 상태는 zod 스키마(`ui/src/state/urlSchema.ts`)로 파싱한다: 앱 id 숫자, env 64자 영숫자·`-_`, 시간 범위 ISO 또는 프리셋 열거, 필터 값 1 KiB·개수 20, user_id·IP·검색어 1 KiB. 실패 항목은 기본값으로 복원하고 토스트 "일부 링크 값이 무시되었습니다". 검색어·user_id는 URL에 남음을 문서에 명시(SP-35 수용, 1.x에 해시 참조 검토).

### 9.8 보안 헤더

| 헤더 | 값 |
|---|---|
| `Strict-Transport-Security` | `max-age=31536000; includeSubDomains`(HTTPS 종단 시) |
| `X-Content-Type-Options` | `nosniff` |
| `X-Frame-Options` | `DENY` |
| `Referrer-Policy` | `strict-origin-when-cross-origin` |
| `Permissions-Policy` | `camera=(), microphone=(), geolocation=(), payment=(), usb=()` |
| `Cache-Control`(API) | `no-store` |
| `Cross-Origin-Opener-Policy` | `same-origin` |
| `Cross-Origin-Resource-Policy` | `same-origin` |

### 9.9 CSP

```
default-src 'self';
script-src 'self' 'nonce-{nonce}' 'strict-dynamic';
style-src 'self' 'nonce-{nonce}';            # 폴백: 'unsafe-inline'(15장 미결)
img-src 'self' data: blob:;
font-src 'self';
connect-src 'self' wss://{host};
worker-src 'self' blob:;
object-src 'none'; base-uri 'self'; form-action 'self'; frame-ancestors 'none';
report-uri /api/v1/csp-report; report-to csp
```
nonce: 서버가 `index.html`을 템플릿으로 서빙하며 요청마다 16바이트 nonce를 생성해 `<script nonce>`·`<meta name="csp-nonce">`에 주입한다(정적 파일 서버가 아니라 `IndexHtmlController`). Ant Design 5는 `ConfigProvider csp={{ nonce }}`로 CSS-in-JS 스타일 태그에 nonce를 붙이므로 `style-src`에 nonce 방식을 1차 시도한다. uPlot·캔버스는 인라인 스타일을 쓰지 않도록 CSS 클래스로 구현한다. 그래도 위반이 남으면 `style-src 'unsafe-inline'`으로 타협하고 사유를 `docs/security/csp.md`에 기록한다. 리포트는 `/api/v1/csp-report`(무인증, 레이트 리밋 60/분/IP, 본문 8 KiB)가 받아 로그와 메트릭으로만 남긴다.

## 10. 공급망·빌드·배포

### 10.1 SBOM 파이프라인

| 산출물 | 생성 | 동봉·배포 |
|---|---|---|
| `argos-agent.cdx.json` | `org.cyclonedx.bom` Gradle 플러그인을 `agent-bootstrap`·`agent-core`·`agent-instrumentation/*`에 적용, `agent-dist`가 **셰이딩 전** 런타임 의존성 그래프로 집계(aggregate). 셰이딩으로 relocate된 패키지도 원 좌표(groupId:artifactId:version)로 기록 | jar `META-INF/sbom/argos-agent.cdx.json`, 릴리스 자산 |
| `argos-server.cdx.json` | 서버 모듈 런타임 클래스패스 | tar `sbom/`, 이미지 attestation(`cosign attest --type cyclonedx`), 릴리스 자산 |
| `argos-ui.cdx.json` | pnpm 락파일 기반(cdxgen 또는 `@cyclonedx/cyclonedx-npm` — pnpm 지원 여부 착수 시 확인) | 서버 tar에 포함(UI는 서버가 서빙) |
| `META-INF/argos-deps.txt` | 같은 소스에서 `groupId:artifactId:version:license` 텍스트 생성 | jar, `check-shading.sh` 검사 |

### 10.2 스캔·게이트·예외

- 커밋·PR: `trivy sbom <file>`(또는 Grype)로 SBOM 3종 스캔, 서버 컨테이너 이미지 `trivy image`. Critical·High(수정 가능) 0이 머지 조건. 주간 전체 재스캔 워크플로(새 CVE 반영) 실패 시 이슈 자동 생성(`kind/security`).
- 예외 문서 `docs/security/cve-exceptions.md`:
  ```
  | CVE | 컴포넌트:버전 | 영향 평가(도달 가능성) | 완화 | 승인자 | 승인일 | 재검토일 |
  ```
  스캐너 설정(`.trivyignore`)은 이 문서의 CVE만 포함해야 하며 CI가 두 집합 일치를 검사한다(`ST-71-2`).
- Dependabot: Gradle·npm·GitHub Actions·Docker, 월 1회 그룹 업데이트, 보안 업데이트는 즉시. 폐쇄망 CI만 쓰는 경우 주간 수동 절차를 19 문서에.

### 10.3 릴리스 서명·검증

| 대상 | 서명 | 검증 |
|---|---|---|
| `SHA256SUMS`(모든 릴리스 파일의 해시) | GPG 분리 서명 `SHA256SUMS.asc` | `sha256sum -c SHA256SUMS && gpg --verify SHA256SUMS.asc` |
| `argos-agent.jar`, 서버 tar, 에어갭 번들 | 각각 `.asc`(Maven Central 요구 형식과 동일) + cosign keyless `.sig`/`.pem`(온라인) | `gpg --verify` / `cosign verify-blob` |
| 컨테이너 이미지 | cosign keyless 서명 + `cosign attest`(SLSA provenance, CycloneDX) | `cosign verify ghcr.io/<org>/argos-server@sha256:…` |
| Maven Central | GPG(필수) | Central 자동 검증 |

GPG 키: 조직 명의 마스터 키(오프라인, 4년) + 서명 서브키(2년, CI 비밀로 보관, 교체 절차). 공개키는 저장소 `KEYS`, 문서 사이트, keys.openpgp.org에 게시하고 지문을 README·설치 문서·에어갭 번들 `KEYS.fingerprint`에 적는다. 검증 스크립트 `deploy/verify-release.sh <version> <dir> [--offline]`: 체크섬 → GPG → (온라인이면) cosign·attestation 검증, 결과를 표로 출력하고 실패 시 종료 코드 1. 설치 가이드는 이 스크립트 1줄을 필수 단계로 둔다.

### 10.4 재현 빌드 설정

Gradle: `tasks.withType<AbstractArchiveTask> { isPreserveFileTimestamps = false; isReproducibleFileOrder = true }`, `gradle/libs.versions.toml` 고정, `gradle/verification-metadata.xml`(의존성 체크섬·서명 검증, 불일치 시 실패), Shadow 플러그인 매니페스트에서 `Build-Jdk`·시각 제거, `SOURCE_DATE_EPOCH` 사용. pnpm `--frozen-lockfile`, Vite 빌드 해시 결정성(`build.rollupOptions.output.hashCharacters` 고정, 환경변수 주입 금지). CI 베이스 이미지·GitHub Actions는 다이제스트·SHA 고정. 릴리스 워크플로가 같은 커밋을 2회 빌드해 jar·tar 내용물(`unzip -l` + 해시) diff 0을 단언(`ST-73-1`).

### 10.5 저장소 보안 자동화

- gitleaks(커밋·PR, `.gitleaks.toml`에 `argos_[A-Za-z0-9_-]{43}` 커스텀 규칙 추가), GitHub push protection.
- CodeQL(Java·JavaScript/TypeScript) 주간 + PR.
- self-hosted 러너는 `push`(main·release/*)와 `workflow_dispatch`에서만, `pull_request_target` 미사용(O-S05). 19 문서가 워크플로 파일로 고정.

### 10.6 자격증명 생성·금지 목록·기동 거부

`deploy/compose/init-env.sh`(및 tar 설치 스크립트):
```
CLICKHOUSE_PASSWORD, POSTGRES_PASSWORD, ARGOS_MASTER_KEY(base64 32B), ARGOS_JWT_KEY(base64 32B),
ARGOS_METRICS_TOKEN(32B) 를 /dev/urandom 기반으로 생성 → .env (umask 077, 0600). 기존 .env가 있으면 덮어쓰지 않고 종료 1.
```
서버 기동 검사(`CredentialPolicyCheck`): DB 비밀번호가 비어 있거나 12자 미만이거나 금지 목록(`changeme`, `argos`, `password`, `postgres`, `clickhouse`, `admin`, `123456`, `qwerty`, `default`)에 있으면 기동 거부 + 명확한 ERROR. `.env.example`은 빈 값. ClickHouse `default` 사용자는 설정으로 비활성(`<default><access_management>0…` + 비밀번호 없는 접속 차단), 전용 사용자 3개(10.8). 테스트 `ST-74-1`~`ST-74-3`.

### 10.7 포트·바인드 정책

Compose: `argos-server`만 `8080:8080`, `9100:9100` 바인딩. ClickHouse·PostgreSQL은 `ports:` 없음(내부 네트워크 `argos-net`). tar: ClickHouse `<listen_host>127.0.0.1</listen_host>`, PostgreSQL `listen_addresses='localhost'`. 서버 바인드 주소 `argos.server.http.bind`·`argos.collector.bind`(기본 `0.0.0.0`). 스모크 테스트가 기동 후 `ss -ltn`/`docker compose ps`로 노출 포트 집합 = {8080, 9100}을 단언(`ST-75-1`).

### 10.8 DB 계정 최소 권한

| 계정 | 권한 | 사용처 |
|---|---|---|
| PG `argos_migrate` | 스키마 `argos` DDL 전부 | 기동 시 Flyway·ClickHouse 러너 이력 기록. 마이그레이션 완료 후 데이터소스 종료 |
| PG `argos_app` | 테이블별 DML. `audit_logs`는 INSERT·SELECT만(UPDATE·DELETE 없음). `ch_schema_history` SELECT | 서버 런타임 |
| PG `argos_maint` | `audit_logs` 파티션 DROP, 사전·명령 결과·세션 만료 삭제 | 야간 유지보수 작업(별도 데이터소스) |
| CH `argos_writer` | `INSERT` on `argos.*`, `readonly=0`, `allow_ddl=0` | 배치 라이터 |
| CH `argos_reader` | `SELECT` on `argos.*`, `readonly=1`, 쿼리 상한 프로파일 | 조회 API |
| CH `argos_admin` | DDL·`ALTER … DELETE`·`DROP PARTITION`·`BACKUP` | 마이그레이션 러너, 개인정보 삭제, 비상 보존 단축, 백업 |

비밀번호는 환경변수·파일 참조(`${file:/run/secrets/…}`)를 지원한다. 원격 DB 연결 시 TLS 옵션을 17 문서에.

### 10.9 백업 보호

백업 스크립트 산출물은 `data/backup/`(0700) 아래 0600. `--encrypt age -r <recipient>` 또는 `--encrypt gpg -r <keyid>` 옵션(기본 off, 가이드에서 켜기 권장). 보존 7일(설정). 백업에 개인정보·명령 결과(암호문)·채널 비밀값(암호문)·토큰 해시가 포함됨을 운영 매뉴얼에 명시. 복구 리허설 절차에 마스터 키·JWT 키 복구 단계 포함. 마스터 키 없이 복구하면 암호화 컬럼은 `secret_unavailable`로 표시되고 재입력이 필요함을 문서화.

## 11. 감사 로그

### 11.1 이벤트 카탈로그

열거형 `AuditEvent`(서버 `io.argos.server.audit.AuditEvent`). 단계 M = Gate 1 전, G2 = Gate 2 전. 카탈로그 스냅샷 테스트(`ST-80-1`)가 열거형과 이 표의 일치를 검사한다.

| 그룹 | 이벤트 | 단계 | 대상·추가 필드 |
|---|---|---|---|
| 인증 | LOGIN_SUCCESS, LOGIN_FAILED, ACCOUNT_LOCKED, ACCOUNT_UNLOCKED, LOGOUT, SESSION_REVOKED(reason), SESSION_REUSE_DETECTED, KIOSK_SESSION_ISSUED, PASSWORD_CHANGED, PASSWORD_RESET, MUST_CHANGE_ENFORCED | M | user, sid, ip, ua |
| 사용자·역할 | USER_CREATED, USER_UPDATED, USER_DISABLED, USER_DELETED, ROLE_CHANGED(before/after) | M | target user |
| 토큰 | TOKEN_ISSUED(prefix, expires), TOKEN_ROTATED, TOKEN_REVOKED, TOKEN_EXPIRED, AGENT_AUTH_FAILED(ip, prefix?), AGENT_AUTH_BLOCKED(ip), AGENT_SESSION_REJECTED(reason) | M | app, token prefix |
| 앱·인스턴스 | APP_CREATED, APP_UPDATED, APP_DELETED, APP_APPROVED, APP_REJECTED, INSTANCE_ARCHIVED, INSTANCE_DELETED | M | app, env |
| 알림 | RULE_CREATED/UPDATED/DELETED/DISABLED, RULE_ACKED, MUTE_CREATED/DELETED, CHANNEL_CREATED/UPDATED/DELETED, CHANNEL_TEST_SENT(status), CHANNEL_SIGNING_KEY_ROTATED | M | rule, channel |
| 명령·설정 | 5.7 전부 | M | instance, command id |
| 설정 변경 통제 | CONFIG_CHANGE_CREATED(reason, diff), CONFIG_CHANGE_APPLIED(instance results), CONFIG_CHANGE_ROLLED_BACK | M | app, change id |
| 개인정보·보존 | PRIVACY_SETTING_CHANGED, RETENTION_CHANGED, PRIVACY_DELETE_REQUESTED(subject type, value hash, range), PRIVACY_DELETE_COMPLETED(rows) | M | |
| 민감 열람 | SENSITIVE_VIEW(type=env_dump|thread_dump|sql_params_full, tx_id?, command id?) | G2 | |
| 내보내기 | EXPORT_CSV(view, filters, rows), AUDIT_LOG_VIEWED, AUDIT_LOG_EXPORTED | G2 | |
| 시스템 | SETUP_COMPLETED, TLS_RELOADED, MASTER_KEY_ROTATED, SERVER_STARTED(version), MIGRATION_APPLIED(version) | M | |

### 11.2 레코드 스키마

```sql
CREATE TABLE audit_logs (
  id            BIGINT GENERATED ALWAYS AS IDENTITY,
  ts            TIMESTAMPTZ NOT NULL DEFAULT now(),
  event         VARCHAR(64) NOT NULL,
  actor_type    VARCHAR(16) NOT NULL,      -- user | agent | system
  actor_id      VARCHAR(64),               -- user id / token prefix / 'system'
  actor_name    VARCHAR(128),
  source_ip     INET, user_agent VARCHAR(512),
  target_type   VARCHAR(32), target_id VARCHAR(64), target_name VARCHAR(256),
  outcome       VARCHAR(16) NOT NULL,      -- success | failure | rejected
  reason        VARCHAR(128),
  before_json   JSONB, after_json JSONB,   -- 비밀값은 저장 전 마스킹
  request_id    VARCHAR(64),
  PRIMARY KEY (ts, id)
) PARTITION BY RANGE (ts);                 -- 월 파티션
```

### 11.3 불변성·보존

- `argos_app` 계정은 `audit_logs`에 INSERT·SELECT만. UPDATE·DELETE 권한 없음(`ST-82-2`). API에 PUT/PATCH/DELETE 경로가 없음을 OpenAPI 린트로 고정(`ST-82-1`).
- 보존 `argos.audit.retention-days`(기본 365, 미결 20). 만료는 `argos_maint`가 월 파티션 DROP(야간). 보존 단축은 RETENTION_CHANGED로 기록.
- 기록 실패(DB 다운)는 요청을 실패시키지 않되 `argos_audit_write_failures_total`을 올리고 로컬 JSONL 폴백에 쓴다.

### 11.4 출력 포맷

`argos.audit.sink`: `db`(기본), `+jsonl`(`logs/audit.jsonl`, 롤링), `+syslog`(RFC 5424, TLS 선택, 구조화 데이터 `[argos@XXXXX event="…" actor="…" …]`). JSONL 한 줄 = 11.2 레코드의 JSON. SIEM 연동 가이드는 17 문서.

### 11.5 조회·내보내기

`GET /api/v1/admin/audit?from&to&actor&event&target`(관리자, 페이지 500), `GET …/export.csv`(9.6 규칙, 이 행위도 AUDIT_LOG_EXPORTED). UI는 관리 > 감사 로그.

## 12. 보안 프로세스 산출물

### 12.1 `SECURITY.md` 초안 (영문, 저장소 루트)

```markdown
# Security Policy

## Supported Versions
The latest minor release receives all fixes. The previous minor receives security and critical
fixes for 3 months after the next minor is released. Pre-releases (0.x, beta, rc) are not supported.

## Reporting a Vulnerability
Please do NOT open a public issue. Use GitHub Private Vulnerability Reporting on this repository,
or email security@<domain> (PGP key: KEYS, fingerprint in README).
Include: affected component (agent/server/ui/deploy), version, reproduction steps, impact.

## What to expect
- Acknowledgement within 3 business days.
- Initial assessment and severity within 10 business days.
- Fix target: High/Critical within 30 days, others within 90 days (coordinated disclosure).
- Fixes are released as patch versions for all supported minors, with a GitHub Security Advisory
  (GHSA) and CVE where applicable, and recorded in CHANGELOG under "Security".

## Scope
In scope: argos-agent, argos-server, UI, official Compose/tar/airgap packaging, release pipeline.
Out of scope: misconfiguration of a deployment (e.g. insecure-skip-verify, exposed DB ports),
vulnerabilities in third-party dependencies without an Argos-specific exploit path (report upstream;
we track them via SBOM scans).

## Hardening guidance
See docs/security/ for the threat model, data collected, and deployment hardening.
```

### 12.2 `docs/security/asvs-checklist.md` 구조

| 열 | 내용 |
|---|---|
| ASVS 항목 | 예: V2.1.1 |
| 상태 | 충족 / 미충족(계획) / 해당 없음 |
| 설계 절 | 이 문서의 절 번호 |
| 테스트 id | `ST-xx-n` 또는 수동 절차 id |
| 비고 | 예외 승인·사유 |

Gate 2 대상 장: V2(인증)·V3(세션)·V4(접근 제어)·V5(입력 검증)·V7(오류·로깅)·V9(통신)·V14(설정). 그 외 장은 상태만 기록. 초안은 Gate 1.5, 확정은 Gate 2.

### 12.3 `docs/security/cve-exceptions.md` 형식

10.2의 표. 재검토일이 지난 행이 있으면 주간 스캔 워크플로가 실패한다.

### 12.4 보안 릴리스 절차와 모의 훈련

1. 접수(비공개) → 트리아지(심각도 CVSS 3.1) → 임시 완화 안내(필요 시 비공개 권고).
2. 수정은 비공개 포크(GitHub Security Advisory의 private fork)에서 개발·테스트.
3. 지원 중인 마이너 전부에 패치 태그 → 10.3 서명 → 릴리스 노트 `Security` 절(세부는 공개일까지 보류).
4. GHSA 공개·CVE 발급·Discussions Announcements.
5. 모의 훈련(Gate 2 전 1회): 가상 취약점 1건으로 1~4를 수행하고 접수→릴리스 소요, 누락 단계를 `docs/security/drill-<date>.md`에 기록.

### 12.5 침투 테스트 체크리스트(WSTG 기반) 개요

WSTG-ATHN(인증)·SESS(세션)·ATHZ(권한)·INPV(입력 검증: XSS·SQLi·CSV·헤더 주입)·CONF(설정: 헤더·Actuator·기본 자격증명)·CRYP(TLS·저장 암호화)·BUSL(명령·설정 변경 흐름)·CLNT(클라이언트: CSP·DOM XSS)·API(OpenAPI 기반 권한 매트릭스·레이트 리밋) 항목을 체크리스트로 만들어 사람이 1회 수행하고 결과·수정 이슈를 기록한다. gRPC는 자체 스위트와 protobuf 변이 입력 테스트(기본 10종)로 보완.

### 12.6 `securityTest` 스위트 구성

Gradle 소스셋 `securityTest`(서버)·`ui/e2e/security/`(UI). 매 PR 실행, 결과는 CI 필수 검사.

| 그룹 | 테스트 id 범위 | 핵심 |
|---|---|---|
| 토큰 수명주기 | ST-01~ST-06 | 발급 1회·해시·회전·폐기 즉시 종료·상한·마스킹 |
| 전송 | ST-07~ST-09 | testssl 결과 파싱, 호스트명 불일치, 평문 이중 플래그, 만료 메트릭 |
| 수집 상한 | ST-11~ST-15 | gzip 폭탄 RSS, 배치 초과, 쿼터 드롭·격리, 사전·인스턴스 상한, 리플렉션·미인증 거부 |
| 명령·설정 | ST-20~ST-26 | 마스킹 30케이스, 권한 매트릭스, 결과 암호화·TTL, allow 거부, 금지 접두사, 로그 PII 0 |
| 개인정보 | ST-30~ST-39, ST-3A | 분류표 동기, IP·user_id·쿼리스트링 모드, 정규화 PII 50, 메시지 마스킹·지문, XFF, 전파 헤더, 템플릿 변수, 파라미터 3모드, 삭제 API |
| 세션·인증 | ST-40~ST-50, ST-55, ST-56 | 쿠키 플래그·JS 접근 불가, 회전·재사용, 무효화, CSRF·Origin, WS, 설정 토큰, 비밀번호 정책, 매트릭스 생성 테스트, UUID·소유, Actuator, 레이트 리밋, 에러 본문 |
| 채널 | ST-51~ST-54 | SSRF 6케이스, 비밀값 평문 부재·마스킹 반환·rekey, 서명 검증, SMTP 기본 거부 |
| UI | ST-60~ST-64 | 린트, 컴포넌트 XSS, 전역 훅, CSV, 딥링크, 헤더·CSP |
| 공급망 | ST-70~ST-77 | SBOM 존재·스캔, 예외 일치, 서명·검증 스크립트, 재현 빌드, gitleaks, 자격증명 정책, 포트 집합, DB 권한, 버전 매니페스트 |
| 감사 | ST-80~ST-84 | 카탈로그 스냅샷, 스키마, 불변성, 포맷, 백업 권한 |
| PII 시드(N-10c) | ST-10C-1 | 시드 앱 1만 요청 후 CH·PG 전체 grep 0, 삭제 API 후 0 |

### 12.7 ZAP 인증 상태 활성 스캔 계획

릴리스 전(Gate 1.5·2) 워크플로: 데모 프로파일 기동 → ZAP 자동화 프레임워크(`zap.yaml`)에 로그인 컨텍스트(쿠키 세션, CSRF 헤더 스크립트) + OpenAPI 스펙 import → 활성 스캔(관리자·조회자 두 컨텍스트) → 결과 Critical·High 0, Medium은 `docs/security/zap-<version>.md`에 사유. gRPC·WebSocket 메시지는 범위 밖(자체 스위트).

## 13. SEC ↔ 설계 절·구현 문서·테스트 대응표

| SEC | 설계 절 | 구현 문서 | 테스트 id | 단계 |
|---|---|---|---|---|
| SEC-01 | 2.1, 2.4 | 11, 12 | ST-01-1 앱 불일치 거부, ST-01-2 형식·상한 5 | M |
| SEC-02 | 2.2, 2.3 | 12 | ST-02-1 DB 원문 부재, ST-02-2 재조회 원문 없음, ST-02-3 로그 린트 | M |
| SEC-03 | 2.3, 3.3 | 12 | ST-03-1 만료 거부, ST-03-2 만료 메트릭·규칙 | G2 |
| SEC-04 | 2.3, 2.4 | 11, 12, 10 | ST-04-1 폐기→5초 내 종료→재접속 거부, ST-04-2 회전 동시 유효 | M |
| SEC-05 | 2.5 | 12 | ST-05-1~3 세션 상한·분당·IP 차단 | G2 |
| SEC-06 | 2.6 | 10, 17 | ST-06-1 평문 WARN, ST-06-2 번들 grep 0, ST-06-3 파일 권한 WARN | M |
| SEC-07 | 3.1, 3.2 | 12, 17 | ST-07-1 testssl 거부 확인 | M |
| SEC-08 | 3.4 | 10, 12 | ST-08-1 호스트명 불일치 실패, ST-08-2 skip-verify WARN, ST-08-3 평문 이중 플래그 | M |
| SEC-09 | 3.1, 3.3 | 12, 17 | ST-09-1 만료 메트릭·알림, ST-09-2 리로드 핸드셰이크 | G2 |
| SEC-10 | 3.5, 3.6 | 17, 11 | 수동: 자체 CA 가이드 설치 | 1.x·G2 |
| SEC-11 | 4.1, 4.2 | 12, 11 | ST-11-1 gzip 폭탄, ST-11-2 배치 초과, ST-11-3 필드 절단 | M |
| SEC-12 | 4.3 | 12 | ST-12-1 쿼터 드롭·격리 | G2 |
| SEC-13 | 4.4 | 12, 13 | ST-13-1 사전 10만 → overflow, ST-13-2 PG 상한 | G2 |
| SEC-14 | 4.4 | 12 | ST-14-1 인스턴스 상한·정리 | G2 |
| SEC-15 | 4.1, 4.6 | 12 | ST-15-1 리플렉션 실패, ST-15-2 미인증 Send 거부 | M |
| SEC-20 | 5.4 | 10 | ST-20-1~30 | M |
| SEC-21 | 5.1, 7.11 | 12, 14 | ST-21-1 매트릭스 3×8, ST-21-2 타인 결과 403 | M |
| SEC-22 | 5.3, 8.3 | 12, 13 | ST-22-1 TTL, ST-22-2 평문 부재 | G2 |
| SEC-23 | 5.5 | 10, 11, 12 | ST-23-1 불허 거부·불변, ST-23-2 허용 적용, ST-23-3 mask-patterns 축소 거부 | M |
| SEC-24 | 5.2 | 12, 10 | ST-24-1 만료 미실행, ST-24-2 쿨다운·동시 1건 | G2 |
| SEC-25 | 5.6 | 10 | ST-25-1 금지 패턴 무시, ST-25-2 상한 비활성 | M |
| SEC-26 | 6.1, 2.6 | 10 | ST-26-1 INFO PII grep 0, ST-26-2 디렉터리 0700 | M |
| SEC-30 | 6.1, 6.9 | 02, 10, 18 | ST-30-1 열거형↔yaml 동기 | M |
| SEC-31 | 6.2, 6.10 | 10, 12, 15 | ST-31-1 3모드 저장값, ST-31-2 /24 검색, ST-31-3 마법사 강제 | M |
| SEC-32 | 6.3 | 10, 12 | ST-32-1 모드별 저장값, ST-32-2 해시 안정성·검색 | M |
| SEC-33 | 6.8 | 12, 13, 14 | ST-33-1 삭제 후 0건·상태 API | G2 |
| SEC-34 | 6.4 | 10 | ST-34-1~3 | M |
| SEC-35 | 6.5 | 10 | ST-35-1 PII 50, ST-35-2 실패 미전송 | M |
| SEC-36 | 6.6 | 10, 12 | ST-36-1 패턴 단위, ST-36-2 지문 안정성 | G2 |
| SEC-37 | 6.2 | 10 | ST-37-1 스푸핑 XFF | G2 |
| SEC-38 | 6.1(F-A32) | 10 | ST-38-1 허용 외 호스트 헤더 부재 | G2 |
| SEC-39 | 6.11 | 12 | ST-39-1 변수 집합·거부 | M |
| SEC-3A | 6.7 | 10, 12, 13, 15 | ST-3A-1 3모드·화면·감사 | G2 |
| SEC-40 | 7.1 | 12, 14, 15 | ST-40-1 JS 저장소 부재·플래그 | M |
| SEC-41 | 7.2 | 12 | ST-41-1 만료 401, ST-41-2 재사용 → 전체 무효화 | M |
| SEC-42 | 7.3, 7.4, 7.5 | 12, 13 | ST-42-1 강등 후 403, ST-42-2 로그아웃 후 401, ST-42-3 kiosk 조건 | M |
| SEC-43 | 7.6 | 12, 15 | ST-43-1 토큰 없는 POST 403, ST-43-2 교차 Origin 403, ST-43-3 세션 id 재발급 | M |
| SEC-44 | 7.7 | 12, 15 | ST-44-1 무인증 4401, ST-44-2 Origin 거부, ST-44-3 폐기 시 종료 | M |
| SEC-45 | 9.8 | 12 | ST-45-1 헤더 단언 | G2 |
| SEC-46 | 9.9 | 12, 15 | ST-46-1 E2E CSP 위반 0 | G2 |
| SEC-47 | 7.8 | 12, 15, 17 | ST-47-1 토큰 없이 403, ST-47-2 재사용 403, ST-47-3 관리자 존재 시 404 | M |
| SEC-48 | 7.9 | 12 | ST-48-1 경계, ST-48-2 잠금·누진, ST-48-3 cost, ST-48-4 로그 린트 | M |
| SEC-49 | 7.10 | 14, 12 | ST-49-1 스펙 기반 매트릭스 생성 테스트 | M |
| SEC-50 | 7.11 | 12, 14 | ST-50-1 타인 403, ST-50-2 UUID 형식 | M |
| SEC-51 | 8.1 | 12 | ST-51-1~6 | M |
| SEC-52 | 8.2, 8.3 | 12, 13 | ST-52-1 평문 부재, ST-52-2 마스킹 반환, ST-52-3 rekey | M |
| SEC-53 | 8.4 | 12 | ST-53-1 서명 검증 | G2 |
| SEC-54 | 8.5 | 12 | ST-54-1 평문 SMTP 거부 | G2 |
| SEC-55 | 7.12 | 12, 17 | ST-55-1 env 404·prometheus 401 | M |
| SEC-56 | 7.13, 7.14 | 12 | ST-56-1 429, ST-56-2 에러 본문 | G2 |
| SEC-60 | 9.1, 9.2 | 15 | ST-60-1 린트 통과·allowlist | M |
| SEC-61 | 9.3 | 15 | ST-61-1 컴포넌트 XSS 페이로드 | M |
| SEC-62 | 9.4~9.5 | 15, 18 | ST-62-1 전역 훅 | M |
| SEC-63 | 9.6 | 12, 15 | ST-63-1 | G2 |
| SEC-64 | 9.7 | 15 | ST-64-1 변조 URL 복원 | G2 |
| SEC-70 | 10.1 | 19 | ST-70-1 SBOM 생성·동봉, ST-70-2 스캔 0 | M |
| SEC-71 | 10.2 | 19 | ST-71-1 게이트, ST-71-2 예외 일치 | M |
| SEC-72 | 10.3 | 19, 17 | ST-72-1 서명 생성, ST-72-2 verify-release.sh | G2 |
| SEC-73 | 10.4, 10.5 | 19 | ST-73-1 2회 빌드 diff 0, ST-73-2 gitleaks 0, ST-73-3 검증 메타데이터 | G2 |
| SEC-74 | 10.6 | 17, 12 | ST-74-1 .env 생성·권한, ST-74-2 금지 비밀번호 기동 거부, ST-74-3 비밀 스캔 | M |
| SEC-75 | 10.7 | 17 | ST-75-1 포트 집합 | M |
| SEC-76 | 10.8 | 13, 17 | ST-76-1 reader DDL 실패, 수동 권한 표 | G2 |
| SEC-77 | 10.1 | 19 | ST-77-1 argos-deps.txt 존재·형식 | M |
| SEC-80 | 11.1 | 12 | ST-80-1 카탈로그 스냅샷, ST-80-2 이벤트별 행 단언 | M·G2 |
| SEC-81 | 11.2 | 12, 13 | ST-81-1 스키마 | M |
| SEC-82 | 11.3, 11.4 | 12, 13, 17 | ST-82-1 API 부재 린트, ST-82-2 DELETE 권한 실패, ST-82-3 syslog 포맷 | G2 |
| SEC-83 | 11.5 | 12, 14, 15 | ST-83-1 매트릭스 포함 | G2 |
| SEC-84 | 10.9 | 17 | ST-84-1 산출물 권한, 수동 복구 리허설 | G2 |
| SEC-90 | 12.1 | 19 | 수동 Gate 1.5·2 | G2 |
| SEC-91 | 1장 | 이 문서, 18 | ST-91-1 ArchUnit(동적 로딩 API 금지), 수동 리뷰 | M·G2 |
| SEC-92 | 12.2 | 18 | 수동 체크리스트 | G2 |
| SEC-93 | 12.6, 12.7 | 18, 19 | 스위트 전체·ZAP 리포트 | G2 |

03 문서 3.5 매핑: 3.5의 각 불릿은 위 그룹(12.6)과 1:1이다.

## 14. 설계 결정

| ID | 결정 | 대안 | 선택 이유 | 영향 요구 |
|---|---|---|---|---|
| DSN-16-01 | 앱 단위 토큰 + SHA-256 해시 보관 + prefix 인덱스 + 상수 시간 비교 | bcrypt 해시 | 토큰은 고엔트로피 랜덤이라 느린 해시 불필요, 초당 수백 Hello 처리 | SEC-01·02 |
| DSN-16-02 | 토큰 폐기 전파는 프로세스 내 동기 이벤트(단일 노드) | 폴링 | 5초 내 종료 보장 단순 | SEC-04 |
| DSN-16-03 | 에이전트 TLS는 JDK 공급자만, 서버는 JDK 기본·OpenSSL 선택 | 양쪽 tcnative | 에이전트 네이티브 미포함(F-A35), Java 8 호환 | SEC-07·08 |
| DSN-16-04 | 상한 검사는 인터셉터(디코드 직후), 쿼터 초과 배치는 드롭 후 ack | 거부(NACK) | 재전송 폭주 방지. 드롭은 카운터로 가시화 | SEC-11·12 |
| DSN-16-05 | 명령은 열거형 폐쇄 목록, 동적 코드 로딩 API 금지를 ArchUnit으로 고정 | 플러그인 명령 | "임의 코드 실행 불가" 보증을 기계 검사 | SEC-91 |
| DSN-16-06 | `remote-config.allow`는 에이전트 로컬 전권, 서버 값은 축소 방향만 기본 적용 | 서버 전권 | 서버 침해의 폭발 반경 제한 | SEC-23 |
| DSN-16-07 | 환경 덤프 마스킹은 에이전트 측, 끄기 불가, 패턴 추가만 | 서버 측 마스킹 | 평문이 네트워크·DB에 닿지 않음 | SEC-20 |
| DSN-16-08 | user_id 해시는 앱별 HMAC 솔트, 서버가 솔트를 보관하고 검색 시 재계산 | 전역 솔트 / 평문 | 앱 간 결합 방지 + 검색 가능 | SEC-32 |
| DSN-16-09 | 바인드 `full` 값은 ClickHouse에도 봉투 암호화 저장, 복호화는 `pii.view`에서만 | 평문 저장 + 권한 | DB 덤프 유출 시 보호 | SEC-3A |
| DSN-16-10 | 액세스 JWT(EdDSA, 15분) + 서버 측 세션 레코드 + 프로세스 내 폐기 캐시로 즉시 무효화 | 불투명 세션 쿠키만 / JWT만 | JWT 검증 비용 없이 즉시 무효화 둘 다 확보 | SEC-40~42 |
| DSN-16-11 | CSRF는 더블 서브밋 + Origin 이중 검사 | SameSite만 | 구형 브라우저·프록시 변형 대비 | SEC-43 |
| DSN-16-12 | 설정 토큰은 로그·0600 파일 둘 다 기록, 30분·1회 | 첫 접속자 관리자 | 선착순 선점 차단, 무인 설치 경로 분리 | SEC-47 |
| DSN-16-13 | 웹훅은 저장·전송 모두 검증 + 해석 IP 고정 + 리다이렉트 금지 | 저장 시만 검증 | DNS 리바인딩·시간차 공격 차단 | SEC-51 |
| DSN-16-14 | 비밀값은 마스터 키 AES-256-GCM 봉투(`v1:kid:nonce:ct`), AAD에 테이블·컬럼·id | DB 투명 암호화 | 열 바꿔치기 방지, 키 교체 가능 | SEC-52·22 |
| DSN-16-15 | CSP는 nonce + `strict-dynamic`, style은 antd `csp.nonce` 1차, 실패 시 `'unsafe-inline'` 타협 | `'unsafe-inline'` 기본 | 가능하면 엄격, 라이브러리 제약은 문서화 | SEC-46 |
| DSN-16-16 | SBOM은 셰이딩 전 그래프, 서명은 GPG(오프라인 검증) + cosign keyless(온라인) 병행 | cosign만 | 폐쇄망 검증 가능 | SEC-70·72 |
| DSN-16-17 | 감사 로그 불변성은 DB 권한(INSERT·SELECT만) + API 부재 + 별도 유지보수 계정 | 애플리케이션 플래그 | 침해된 앱 계정으로도 삭제 불가 | SEC-82 |

## 15. 미결·가정

| # | 항목 | 가정·추천 | 확정 시점 |
|---|---|---|---|
| 1 | 기본 IP 수집 모드(미결 18) | 설치 마법사 기본 선택 `masked`. 사용자 결정 | 설치 마법사 구현 전 |
| 2 | 운영자의 환경 덤프 권한(미결 19) | 관리자 전용 유지 | 확정(추천 채택) |
| 3 | 감사 로그 보존 기간(미결 20) | 기본 365일, 개인정보처리시스템 해당 시 법률 자문으로 1~2년 결정 | Gate 2 전 |
| 4 | Java 8 TLS 1.3 가용성 | 8u261+만 1.3, 하한 8u252는 1.2 AEAD. 매트릭스 JDK 8 테스트는 8u252와 최신 8 둘 다 | 착수 시 확인 |
| 5 | grpc-java `maxInboundMessageSize`가 압축 해제 후 크기에 적용되는지 | 그렇다고 가정. `ST-11-1`로 실측, 아니면 디코더 래핑으로 보강 | WO(수집) |
| 6 | antd `ConfigProvider csp.nonce`로 style-src nonce가 충분한지, uPlot 인라인 스타일 여부 | 1차 nonce, 실패 시 `'unsafe-inline'` + 문서 | UI 골격 WO |
| 7 | ClickHouse `ALTER … DELETE` 뮤테이션 비용(삭제 API) | 일 파티션 범위 지정으로 감당. P-4에서 측정 | Phase 0 |
| 8 | cosign keyless 검증의 폐쇄망 불가 | GPG 병행으로 해소. 키 관리 절차는 19 문서 | 확정 |
| 9 | pnpm용 CycloneDX 도구(cdxgen vs cyclonedx-npm) | cdxgen(Apache-2.0) 가정 | WO-00 |
| 10 | 비밀번호 금지 목록 소스 | MIT 라이선스 공개 목록(예: SecLists 10k) 사용, 빌드 시 해시 집합 | WO(인증) |
| 11 | JWT 서명 알고리즘 | EdDSA(Ed25519). JDK 15+ 표준 지원(서버는 21) | 확정 |
| 12 | Bucket4j(Apache-2.0) 레이트 리밋 라이브러리 채택 | 채택. 인메모리 | 확정 |
| 13 | ZAP의 WebSocket 커버리지·gRPC 미지원 | 자체 스위트 + protobuf 변이 10종으로 보완, 퍼징은 1.x | 확정 |
| 14 | ClickHouse에 암호화 컬럼(`detail_enc`) 저장 시 압축률 저하 | `full` 모드는 드물다고 가정. P-4에 측정 항목 추가 요청(16장) | Phase 0 |
| 15 | 상황판 kiosk 세션 절대 만료 30일 | 운영 조직 정책에 따라 설정 가능 | 확정 |

## 16. 다른 설계 문서에 요구하는 것

| 대상 | 요구 | 이유 |
|---|---|---|
| 10 에이전트 | `remote-config.allow` 평가기(5.5), `EnvDumpMasker`(5.4), 금지 접두사 정책(5.6), 명령 열거형 처리기·쿨다운(1.4·5.2), 토큰 보관 3방식·마스킹(2.6), TLS 설정 키·호스트명 검증·평문 플래그(3.4), 6.1~6.7의 capture 설정 키와 모드별 동작, `SqlNormalizer` 실패 안전·PII 케이스(6.5), 메시지 마스킹 세트(6.6), 로그 디렉터리 0700·INFO PII 금지(SEC-26), `UNAUTHENTICATED` 시 백오프 5분 고정(2.4), 동적 코드 로딩 API 미사용(ArchUnit 대상), Hello에 `plaintext`·`ip_mode`·`remote_config_allow` 필드 전송 | A 경계 보안의 구현처 |
| 11 프로토콜 | `CommandType` 열거형(1.4 8종), `ConfigPush{change_id, entries}`·`ConfigApplyResult{applied_keys, rejected_keys{key:reason}, config_hash}`, `AgentHello.plaintext/ip_mode/remote_config_allow/token 메타데이터 규약`, `ServerHello.user_id_salt`·`default_ip_mode`·거부 사유 코드, 상한 수치(4.2)를 proto 주석에, 배치별 ack 의미(쿼터 드롭도 ack) | 메시지 계약 |
| 12 서버 | 인터셉터 체인·설정 키(4.1~4.6), `TokenCache`·`SessionRegistry.closeAll`·레이트 리밋(2.4·2.5), 명령 서비스 권한·TTL·결과 암호화(5.1~5.3), 세션 레코드·폐기 캐시·리프레시 회전(7.1~7.4), CSRF·Origin 필터(7.6), WS 핸드셰이크·레지스트리(7.7), 설정 토큰 부트스트랩(7.8), 비밀번호 정책(7.9), Actuator·레이트 리밋·에러 어드바이스(7.12~7.14), 웹훅 검증기·HttpClient 설정·서명(8.1·8.4), `EnvelopeCipher`·`AttributeConverter`·rekey CLI(8.2·8.3), SMTP(8.5), CSV writer(9.6), CSP nonce 컨트롤러·리포트 엔드포인트(9.9), 감사 서비스·싱크(11), 삭제 API 작업(6.8), 자격증명 기동 검사(10.6), 메트릭 토큰 인증(7.12) | C 경계 구현처 |
| 13 저장소 | `agent_tokens`(2.2), `user_sessions`(7.3), `audit_logs` 월 파티션·권한(11.2·11.3), `command_results.ciphertext`, `notification_channels.*_enc`, `apps.user_id_salt_enc`, `privacy_jobs`, `profile_step.detail_enc`(또는 detail 암호문 저장 방식) 컬럼, DB 계정 3+3 권한 부여 스크립트(10.8), 사전 90일 정리·`<overflow>` 해시 상수, 삭제 뮤테이션 패턴·측정 항목(P-4에 "뮤테이션 소요·암호화 컬럼 압축률" 추가) | 스키마·권한 |
| 14 API | 모든 operation에 `x-argos-role`(+`x-argos-permission`), 7.10 매트릭스 반영, 7.11 UUID 경로, 7.13 레이트 리밋 그룹 표기, 7.14 표준 에러 스키마, 인증 엔드포인트(`/auth/login|refresh|logout|sessions`), `/setup`, `/admin/privacy/*`, `/admin/audit`, `/csp-report`, `/admin/tls/reload`, 채널 응답의 비밀값 마스킹 스키마, WS 구독·`reauth`·close 코드(4401·4413) | 계약 |
| 15 UI | 쿠키 기반 클라이언트(credentials include, CSRF 헤더), 9.1 목록·9.2 린트·9.3 컴포넌트·`safeHref`·zod URL 스키마, XSS 시드 E2E 전역 훅, antd `csp.nonce`, 설치 마법사 IP 모드 강제 단계, `pii.view` 조건부 표시·열람 감사 트리거, 상황판 모드와 kiosk 세션 구분, 토큰 1회 표시 UX, 채널 비밀값 재입력 UX, 감사 로그·개인정보 설정 화면 | E 경계 구현처 |
| 17 배포·운영 | `init-env.sh`(10.6), 포트·바인드 템플릿(10.7), 자체 서명 인증서 생성·자체 CA 가이드(3.5), 인증서 교체 절차, 마스터 키·JWT 키·메트릭 토큰 생성·보관·교체 런북, 백업 암호화·권한·복구 리허설(10.9), 진단 번들 마스킹(2.6), syslog 연동 가이드(11.4), 에어갭 번들의 KEYS·검증 스크립트 동봉(10.3), 리버스 프록시 443 가이드, DB 계정 생성 스크립트 | 운영 절차 |
| 18 테스트 | `securityTest` 소스셋·`ST-xx` id 체계(12.6), gzip 폭탄 RSS 측정, 매트릭스 생성 테스트(스펙 → 테스트), PII 시드 앱·grep 하네스(N-10c), XSS 시드 적재·전역 훅, testssl.sh 러너, ZAP 자동화 설정(12.7), ArchUnit 규칙 세트(동적 로딩 금지·`@Sensitive` 로그 금지·Advice 규칙), 2회 빌드 재현성 검사 | 검증 |
| 19 빌드·릴리스 | SBOM 플러그인·집계(10.1), 스캔·예외 일치 검사(10.2), GPG·cosign·attestation 워크플로와 `verify-release.sh`(10.3), 재현 빌드 설정(10.4), gitleaks·CodeQL·Dependabot·러너 격리(10.5), `KEYS` 파일, `SECURITY.md` 배치(12.1), `check-shading.sh`에 SBOM·deps.txt 검사 추가 | 공급망 |
| 02 아키텍처(PM) | 데이터 분류표에 "스레드·환경 덤프 암호화 저장", "바인드 full 암호화 저장", "user_id 솔트" 행 보강 제안. §7 횡단 규칙에 "식별자형 리소스 UUIDv4" 이미 있음(변경 없음) | 정합 |
| 01 기획(PM) | F-S05에 "설정 토큰 부트스트랩·kiosk 세션 조건" 명시 제안, F-U21 비고를 "SEC-42 kiosk 세션 조건" 참조로 | 정합 |
