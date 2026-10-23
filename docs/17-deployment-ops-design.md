# Argos 배포·운영 설계

| 항목 | 내용 |
|---|---|
| 문서 번호 | 17 |
| 문서 버전 | 0.1 (초안) |
| 작성일 | 2026-10-23 |
| 상태 | 설계 검토 중 |
| 만족시키는 요구 | N-11, N-12, N-16, N-17, N-18, N-19, N-20, N-21, N-22, F-S20, F-S23, S-07, S-08, O-A01, O-A06, O-A08, O-P01, O-R04, O-R12, SEC-07, SEC-09, SEC-26, SEC-47, SEC-52, SEC-55, SEC-74, SEC-75, SEC-76, SEC-84 |
| 의존 문서 | 02 아키텍처(§4.3·§8·DSN-02-19·22), 13 저장소(ClickHouse 설정·마이그레이션 러너), 12 서버(설정 트리·설정 토큰·자기 메트릭), 16 보안(마스터 키·TLS), 19 빌드·릴리스(아티팩트·서명) |
| 이 문서에 의존하는 문서 | 18 테스트(설치·업그레이드·에어갭 테스트), 04 작업 지시서, 사용자 문서(설치 가이드·운영 매뉴얼·런북) |

## 1. 범위와 설계 결정

이 문서는 Argos를 **설치하고, 설정하고, 올리고, 지키고, 고치는** 방법을 정한다. 설치 패키지의 내용물, 설정 체계, 자원 예산, 업그레이드·보존·백업 절차, 자기 모니터링, 런북이 범위다. 서버 내부 동작(12), 스키마·ClickHouse 서버 설정 값(13), 보안 구현(16), 빌드 산출물 생성(19)은 각 문서가 맡고 여기서는 **배치 위치와 운영 절차**만 정한다.

| ID | 결정 | 대안 | 선택 이유 | 영향 요구 |
|---|---|---|---|---|
| DSN-17-01 | 공식 설치 형태는 **Docker Compose**와 **tar + systemd** 둘. 에어갭 번들은 두 형태의 오프라인 변형이다 | Compose만 / Helm 우선 | 폐쇄망 VM이 주 환경. 컨테이너 금지 조직이 있다. Helm은 1.x | N-11, N-12 |
| DSN-17-02 | tar 설치의 ClickHouse·PostgreSQL은 **OS 패키지**(공식 저장소, 에어갭은 번들 동봉 deb/rpm)로 설치하고 `install.sh`가 설정·바인딩·계정을 만든다. 운영자가 기존 DB를 쓰는 **외부 DB 모드**를 지원한다 | 번들 바이너리 직접 실행 | 보안 패치·서비스 관리를 OS 관행에 맡긴다. 단일 바이너리 번들은 업그레이드·권한 관리가 어렵다 | N-21, SEC-76 |
| DSN-17-03 | 모든 자격증명(PostgreSQL·ClickHouse 사용자, 마스터 키, 초기 관리자 비밀번호 파일)은 **설치 시 CSPRNG 생성**해 `.env`(Compose) 또는 `conf/secrets.env`(tar)에 0600으로 쓴다. 저장소·이미지에 기본값 리터럴 없음 | 기본 비밀번호 + 변경 안내 | SEC-74 | SEC-74 |
| DSN-17-04 | 호스트에 노출되는 포트는 **8080(UI/API)과 9100(gRPC)뿐**. ClickHouse(8123·9000)·PostgreSQL(5432)은 Compose 내부 네트워크 또는 `127.0.0.1`에만 바인딩 | DB 포트 노출 + 비밀번호 | SEC-75. 노출 포트 집합을 스모크 테스트가 단언한다 | SEC-75 |
| DSN-17-05 | 서버 설정은 Spring `application.yml`의 `argos.*` 트리 하나로 통일하고, 모든 키에 `ARGOS_*` 환경변수 매핑과 `_FILE` 접미사(비밀값 파일 참조)를 둔다 | 다중 설정 파일 | Compose·systemd·Helm에서 같은 키를 쓴다. 비밀값은 파일로만 | O-R03, SEC-52 |
| DSN-17-06 | 초기 관리자 생성은 **1회용 설정 토큰**(`conf/setup-token`, 0600, 30분)으로 보호하고, 무인 설치는 `ARGOS_ADMIN_PASSWORD_FILE`로 한다. 토큰·비밀번호를 환경변수 평문으로 받지 않는다 | 선착순 마법사 / 환경변수 비밀번호 | SEC-47 | SEC-47 |
| DSN-17-07 | S 토폴로지(16GB) 메모리 예산은 OS 2 / PostgreSQL 1.5 / argos-server 5(-Xmx4g + 오프힙 1) / ClickHouse 7.5GiB. 설치 스크립트가 RAM에 비례해 산출한다(§7) | 각 구성 요소 기본값 | ClickHouse 기본은 RAM 90%라 단일 노드에서 OOM-kill. N-18 | N-18 |
| DSN-17-08 | 업그레이드는 **서버 먼저**, 마이그레이션은 서버 기동 시 자동(PostgreSQL Flyway → ClickHouse 러너 → 앱 기동). 계획 정지 10분 이내. 롤백은 직전 마이너까지, 데이터 롤백은 하지 않는다(전진 전용 스키마) | 수동 마이그레이션 | N-11, N-22, O-R04 | N-22 |
| DSN-17-09 | 백업은 **PostgreSQL 필수(일 1회 덤프, 7일)**, ClickHouse는 **선택**(외부 대상 증분). 기본 설치는 ClickHouse 백업을 켜지 않고 "재구축 가능한 텔레메트리"로 문서화한다 | 전체 백업 필수 | 같은 디스크에 전체 백업을 둘 수 없다(용량). N-20 | N-20, SEC-84 |
| DSN-17-10 | 디스크 임계 동작은 서버가 수행한다: 80% 경고, 90% 비상 보존 단축(오래된 `profile_step` → `xlog` 파티션 DROP), 95% WAL만 기록. 운영 절차는 이 동작을 전제로 쓴다 | 운영자 수동 | 야간 디스크 풀로 수집이 멈추는 것을 막는다. F-S23 | F-S23 |
| DSN-17-11 | 에어갭 반입 검증은 **GPG 분리 서명 + SHA-256**으로 한다. cosign keyless는 온라인 환경 보조 수단. 공개키 지문은 번들·문서·저장소 세 곳에 게시한다 | cosign만 | 폐쇄망에서 Sigstore 인프라에 닿을 수 없다. O-R06·SEC-72 | SEC-72 |
| DSN-17-12 | 에이전트 설치는 "jar 1개 + yml 1개 + `-javaagent` 1줄"을 유지하고, 토큰은 `server.token-file`로 배포하는 것을 기본 패턴으로 문서화한다 | 환경변수 토큰 | 파일 권한으로 보호, 진단 번들 마스킹과 일관. SEC-06 | O-A08 |
| DSN-17-13 | 서버 자기 메트릭은 `/actuator/prometheus`를 **별도 관리 포트(기본 8081, 127.0.0.1 바인딩)**로 노출하고, 원격 스크랩은 베어러 토큰 + IP 허용 목록으로 연다. `health`는 상세 숨김 | 8080 공용 | SEC-55 | SEC-55 |
| DSN-17-14 | 운영 작업(보존 변경, 정리, 재구축, 백업 트리거)은 `argos-admin` CLI(서버 jar의 서브커맨드)로 수행하고 모두 감사 로그에 남긴다 | DB 직접 조작 | 절차를 코드로 고정, 감사 가능 | F-S22, SEC-80 |

## 2. 설치 형태 개요

### 2.1 비교

| 형태 | 대상 | 포함 | 외부 요구 | 설치 시간 목표 |
|---|---|---|---|---|
| Docker Compose | 평가, 소규모 운영, Docker 허용 조직 | argos-server 이미지, ClickHouse·PostgreSQL 공식 이미지(다이제스트 고정), `.env` 생성 스크립트 | Docker 24+, Compose v2 | 10분(N-11 30분 이내), 데모 프로파일 5분(N-16) |
| tar + systemd | 컨테이너 금지 조직, 베어메탈·VM 운영 | `argos-server.jar`, `bin/*.sh`, systemd 유닛, 설정 템플릿, ClickHouse·PostgreSQL 설정 조각 | JDK 21, ClickHouse·PostgreSQL OS 패키지(온라인) 또는 외부 DB | 30분 |
| 에어갭 번들 | 폐쇄망 | 위 두 형태 + 이미지 tar + DB deb/rpm + 체크섬·서명·공개키 지문 + 오프라인 문서 | USB·내부 저장소 반입 | 30분 + 반입 검증 |
| 데모 프로파일 | 첫 5분 | Compose + 샘플 앱(에이전트 부착) + 부하 생성기 | Docker | 60초 내 XLog 점 |

### 2.2 공통 구성도(S 토폴로지)

```
 운영자 브라우저 ──443(리버스 프록시, 권장)──▶ :8080 argos-server ◀──9100(TLS)── 계측 대상 JVM들
                                                   │  :8081 관리(127.0.0.1, Prometheus 스크랩)
                                                   ├── PostgreSQL :5432 (내부 네트워크 / 127.0.0.1)
                                                   └── ClickHouse  :8123/:9000 (내부 네트워크 / 127.0.0.1)
 디스크: data/wal(≤10GiB), ClickHouse 데이터(보존 공식), PostgreSQL, 로그, 백업(외부 대상 권장)
```

### 2.3 배포 산출물과 경로

| 산출물 | 생성(19 문서) | 설치 위치 |
|---|---|---|
| `argos-server-X.Y.Z.jar`(부트 jar) | `:server:bootJar` | tar: `/opt/argos/lib/`, 이미지: `/app/argos-server.jar` |
| `ghcr.io/<org>/argos-server:X.Y.Z` | jib multi-arch | Compose |
| `ghcr.io/<org>/argos-demo-app:X.Y.Z`, `argos-demo-load:X.Y.Z` | sample-apps jib | Compose demo 프로파일 |
| `argos-agent-X.Y.Z.jar` | `:agent:agent-dist` | 계측 대상 서버 임의 경로(권장 `/opt/argos-agent/`) |
| `argos-server-X.Y.Z.tar.gz` | `:deploy:tarDist` | `/opt/argos/` |
| `argos-airgap-X.Y.Z-<arch>.tar` | `:deploy:airgapBundle` | 반입 후 임시 디렉터리 |

## 3. Docker Compose 설계

### 3.1 파일 목록 (`deploy/compose/`)

| 파일 | 내용 |
|---|---|
| `compose.yaml` | 기본 서비스 3개(server, clickhouse, postgres), 프로파일 `demo`(sample-app-a, sample-app-b, load-generator) |
| `compose.proxy.yaml` | 선택 오버레이: Caddy 또는 nginx 리버스 프록시(443, 자동 인증서 또는 사용자 인증서) |
| `.env.example` | 모든 변수 이름과 설명, **값은 비움** |
| `argos-env-init.sh` | `.env`·`secrets/` 생성(§3.7) |
| `argos-compose.sh` | `up`·`down`·`upgrade`·`backup`·`diag` 래퍼(권한·사전 검사 포함) |
| `config/server/application-compose.yaml` | 컨테이너 전용 서버 설정 오버레이(호스트명·경로) |
| `config/clickhouse/config.d/argos.xml`, `users.d/argos.xml` | 13 문서가 정의한 서버·사용자 설정. 설치 스크립트가 RAM 값만 치환 |
| `config/postgres/init/01-argos.sql` | 스키마 `argos`·사용자 생성(비밀번호는 환경변수에서) |
| `config/demo/argos-agent.yml` | 데모 에이전트 설정(토큰 파일 참조) |
| `smoke.sh` | 설치 후 검증: 포트 집합, `.env` 권한, health, 설정 토큰 접근 제어 |

### 3.2 `compose.yaml` 스케치

```yaml
name: argos
services:
  server:
    image: ghcr.io/<org>/argos-server:${ARGOS_VERSION}@sha256:<digest>   # 릴리스마다 다이제스트 고정
    restart: unless-stopped
    depends_on:
      postgres:    { condition: service_healthy }
      clickhouse:  { condition: service_healthy }
    ports:
      - "${ARGOS_HTTP_BIND:-0.0.0.0}:8080:8080"
      - "${ARGOS_GRPC_BIND:-0.0.0.0}:9100:9100"
      # 8081(관리)은 노출하지 않는다. 스크랩은 ARGOS_MGMT_EXPOSE=true일 때만 127.0.0.1:8081
    environment:
      ARGOS_PG_URL: jdbc:postgresql://postgres:5432/argos
      ARGOS_PG_USER: argos
      ARGOS_PG_PASSWORD_FILE: /run/secrets/pg_password
      ARGOS_CH_URL: http://clickhouse:8123
      ARGOS_CH_WRITER_PASSWORD_FILE: /run/secrets/ch_writer_password
      ARGOS_CH_READER_PASSWORD_FILE: /run/secrets/ch_reader_password
      ARGOS_MASTER_KEY_FILE: /run/secrets/master_key
      ARGOS_ADMIN_PASSWORD_FILE: /run/secrets/admin_password     # 무인 설치. 없으면 설정 토큰 마법사
      ARGOS_TLS_CERT_FILE: /conf/tls/server.crt                   # 없으면 collector.allow-plaintext 검사
      ARGOS_TLS_KEY_FILE: /conf/tls/server.key
      ARGOS_DEMO_MODE: ${ARGOS_DEMO_MODE:-false}
      JAVA_TOOL_OPTIONS: ${ARGOS_SERVER_JAVA_OPTS}
    volumes:
      - argos-data:/data            # WAL, setup-token, 스풀
      - argos-logs:/logs
      - ./config/server:/conf:ro
      - ./tls:/conf/tls:ro
    secrets: [pg_password, ch_writer_password, ch_reader_password, master_key, admin_password]
    healthcheck:
      test: ["CMD", "wget", "-qO-", "http://127.0.0.1:8081/actuator/health"]
      interval: 10s
      timeout: 3s
      retries: 12
      start_period: 60s
    networks: [front, back]
    mem_limit: ${ARGOS_SERVER_MEM:-5g}

  clickhouse:
    image: clickhouse/clickhouse-server:<pinned LTS>@sha256:<digest>
    restart: unless-stopped
    ulimits: { nofile: { soft: 262144, hard: 262144 } }
    environment:
      CLICKHOUSE_SKIP_USER_SETUP: "1"          # default 사용자 비활성, users.d에서 argos_writer/argos_reader만
    volumes:
      - clickhouse-data:/var/lib/clickhouse
      - ./config/clickhouse/config.d:/etc/clickhouse-server/config.d:ro
      - ./config/clickhouse/users.d:/etc/clickhouse-server/users.d:ro
      - ./secrets:/run/argos-secrets:ro         # users.d가 password_sha256_hex 파일 참조
    healthcheck:
      test: ["CMD", "wget", "-qO-", "http://127.0.0.1:8123/ping"]
      interval: 10s
      timeout: 3s
      retries: 12
    networks: [back]                           # 호스트 포트 없음
    mem_limit: ${ARGOS_CH_MEM:-8g}

  postgres:
    image: postgres:<pinned 16.x>@sha256:<digest>
    restart: unless-stopped
    environment:
      POSTGRES_DB: argos
      POSTGRES_USER: argos
      POSTGRES_PASSWORD_FILE: /run/secrets/pg_password
    command: ["postgres", "-c", "shared_buffers=512MB", "-c", "work_mem=16MB", "-c", "max_connections=100"]
    volumes:
      - postgres-data:/var/lib/postgresql/data
      - ./config/postgres/init:/docker-entrypoint-initdb.d:ro
    secrets: [pg_password]
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U argos -d argos"]
      interval: 10s
      timeout: 3s
      retries: 12
    networks: [back]                           # 호스트 포트 없음
    mem_limit: ${ARGOS_PG_MEM:-1536m}

  # ---- demo 프로파일 ----
  sample-app-a:
    profiles: [demo]
    image: ghcr.io/<org>/argos-demo-app:${ARGOS_VERSION}@sha256:<digest>
    environment:
      ARGOS_APP_NAME: order-api
      ARGOS_APP_ENV: demo
      ARGOS_SERVER_ADDRESS: server:9100
      ARGOS_SERVER_TLS: "false"                # 데모 전용. 서버는 ARGOS_DEMO_MODE에서만 allow-plaintext
      ARGOS_TOKEN_FILE: /run/secrets/demo_agent_token
      PEER_URL: http://sample-app-b:8080       # 분산 호출(S-05)
    depends_on: { server: { condition: service_healthy } }
    secrets: [demo_agent_token]
    networks: [back]
  sample-app-b:
    profiles: [demo]
    image: ghcr.io/<org>/argos-demo-app:${ARGOS_VERSION}@sha256:<digest>
    environment: { ARGOS_APP_NAME: payment-api, ARGOS_APP_ENV: demo, ARGOS_SERVER_ADDRESS: server:9100, ARGOS_SERVER_TLS: "false", ARGOS_TOKEN_FILE: /run/secrets/demo_agent_token }
    depends_on: { server: { condition: service_healthy } }
    secrets: [demo_agent_token]
    networks: [back]
  load-generator:
    profiles: [demo]
    image: ghcr.io/<org>/argos-demo-load:${ARGOS_VERSION}@sha256:<digest>
    environment: { TARGET_URL: http://sample-app-a:8080, RATE: "50", MIX: "default" }   # 느림·에러·N+1·분산·배치 포함
    depends_on: [sample-app-a, sample-app-b]
    networks: [back]

secrets:
  pg_password:          { file: ./secrets/pg_password }
  ch_writer_password:   { file: ./secrets/ch_writer_password }
  ch_reader_password:   { file: ./secrets/ch_reader_password }
  master_key:           { file: ./secrets/master_key }
  admin_password:       { file: ./secrets/admin_password }
  demo_agent_token:     { file: ./secrets/demo_agent_token }

volumes: { argos-data: {}, argos-logs: {}, clickhouse-data: {}, postgres-data: {} }
networks:
  front: {}
  back:  { internal: true }                   # 외부 egress 차단(알림 채널은 server가 front로 나간다)
```

규칙

- `server`만 `front`·`back` 양쪽, DB·데모 앱은 `back`(internal)만. 알림 채널(SMTP·Slack·웹훅)은 `server`가 `front`로 나간다.
- 데모 프로파일의 에이전트 토큰은 `argos-env-init.sh --demo`가 생성해 서버 무인 설치 시 `demo` 앱 2개와 함께 등록한다(12 문서의 `ARGOS_BOOTSTRAP_APPS` 파일 형식).
- 데모 모드(`ARGOS_DEMO_MODE=true`)에서만 `collector.allow-plaintext=true`이며 UI 상단에 데모 배너를 표시한다. 운영 설치 스크립트는 이 변수를 쓰지 않는다.

### 3.3 네트워크·포트 정책

| 포트 | 서비스 | 노출 | 바인드 변수 | 비고 |
|---|---|---|---|---|
| 8080 | UI/API | 호스트 | `ARGOS_HTTP_BIND`(기본 0.0.0.0) | 리버스 프록시 뒤면 127.0.0.1 권장 |
| 9100 | gRPC(TLS) | 호스트 | `ARGOS_GRPC_BIND` | 에이전트 접속 |
| 8081 | 관리(health·prometheus·info) | 비노출 | `ARGOS_MGMT_EXPOSE=true`면 127.0.0.1만 | Prometheus 사이드카 또는 호스트 스크랩 |
| 8123, 9000 | ClickHouse | 비노출 | - | `back` 내부만 |
| 5432 | PostgreSQL | 비노출 | - | `back` 내부만 |

`smoke.sh`는 `docker compose ps --format json`과 `ss -ltn`으로 호스트 리슨 포트 집합이 `{8080, 9100}`(관리 노출 시 `+ 127.0.0.1:8081`)과 같음을 단언한다(SEC-75).

### 3.4 볼륨

| 볼륨 | 내용 | 백업 | 권한 |
|---|---|---|---|
| `argos-data` | `wal/`(≤10GiB), `setup-token`, `spool/`(1.x) | WAL은 백업 대상 아님(재접속 시 에이전트가 재전송) | 0700, uid 10001(argos) |
| `argos-logs` | 서버 로그 JSON Lines, 로테이션 100MB×10 | 선택 | 0700 |
| `clickhouse-data` | 데이터·메타 | 선택(§10) | 컨테이너 기본 |
| `postgres-data` | 메타데이터 | **필수**(§10) | 컨테이너 기본 |
| `./secrets/` | 비밀값 파일 | 운영자 보관(복구에 필요, 특히 `master_key`) | 디렉터리 0700, 파일 0600 |
| `./tls/` | 서버 인증서·키·CA | 운영자 보관 | 0600 |

### 3.5 healthcheck·기동 순서

`postgres`·`clickhouse` healthy → `server` 기동(마이그레이션 → 리스너) → `server` healthy(start_period 60초, 마이그레이션 긴 경우 `ARGOS_START_PERIOD`로 연장) → demo 앱 → load-generator. 서버 `/actuator/health`는 상세 숨김(`show-details: never`)이며 liveness/readiness 그룹을 분리한다(readiness는 PG·CH 연결·마이그레이션 완료).

### 3.6 이미지 고정·multi-arch

- 모든 이미지 참조는 `태그@sha256:다이제스트`. 릴리스 워크플로(19 문서)가 `compose.yaml`의 다이제스트를 갱신해 릴리스 자산에 포함한다.
- `argos-server`·데모 이미지는 `linux/amd64`·`linux/arm64` 매니페스트 리스트. ClickHouse·PostgreSQL 공식 이미지도 양쪽 지원(다이제스트는 매니페스트 리스트 다이제스트).
- 베이스 이미지: 서버는 Eclipse Temurin 21 JRE(distroless 계열은 진단 도구 부재로 1.0 제외), 비루트 uid 10001.

### 3.7 `.env`·비밀값 생성 스크립트 (`argos-env-init.sh`)

| 단계 | 동작 | 실패 시 |
|---|---|---|
| 1 | 사전 검사: Docker 24+, Compose v2, `openssl` 또는 `/dev/urandom`, 디스크 여유(권장 100GB, 최소 20GB), RAM | 종료 코드 2, 부족 항목 출력 |
| 2 | RAM 측정 → `ARGOS_SERVER_MEM`·`ARGOS_CH_MEM`·`ARGOS_PG_MEM`·`ARGOS_SERVER_JAVA_OPTS` 산출(§7.3) | - |
| 3 | `secrets/` 0700 생성. `pg_password`·`ch_writer_password`·`ch_reader_password`: 32바이트 CSPRNG base64url. `master_key`: 32바이트 hex(AES-256). `admin_password`: `--unattended`일 때만 24자 생성, 아니면 생성하지 않음(설정 토큰 마법사). `demo_agent_token`: `--demo`일 때 `argos_` + 32바이트 base64url | 기존 파일이 있으면 덮어쓰지 않음(`--force` 필요) |
| 4 | ClickHouse `users.d/argos.xml`용 `password_sha256_hex` 파일 2개 생성(`secrets/ch_writer_sha256`, `ch_reader_sha256`) | - |
| 5 | `.env` 생성(0600): 버전, 바인드, 메모리, 데모 플래그. 비밀값은 `.env`에 쓰지 않는다(파일 참조만) | - |
| 6 | 금지 목록 검사: 생성·입력된 비밀번호가 `changeme`·`password`·`argos`·`admin`·`postgres`·`clickhouse`·8자 미만이면 거부(서버도 기동 시 같은 검사, SEC-74) | 종료 코드 3 |
| 7 | 요약 출력: 다음 명령(`docker compose up -d`), 설정 토큰 확인 방법(`docker compose logs server | grep setup-token` 또는 `argos-data/setup-token`), 보관해야 할 파일(`secrets/`, `tls/`) | - |

### 3.8 환경변수 목록

| 변수 | 기본 | 설명 |
|---|---|---|
| `ARGOS_VERSION` | 릴리스 버전 | 이미지 태그(다이제스트와 함께) |
| `ARGOS_HTTP_BIND`, `ARGOS_GRPC_BIND` | `0.0.0.0` | 호스트 바인드 주소 |
| `ARGOS_MGMT_EXPOSE` | `false` | `true`면 `127.0.0.1:8081` 노출 |
| `ARGOS_SERVER_MEM`, `ARGOS_CH_MEM`, `ARGOS_PG_MEM` | 산출 | 컨테이너 메모리 상한 |
| `ARGOS_SERVER_JAVA_OPTS` | `-Xms4g -Xmx4g -XX:+UseG1GC -XX:MaxDirectMemorySize=512m -XX:+ExitOnOutOfMemoryError` | JVM 옵션 |
| `ARGOS_DEMO_MODE` | `false` | 데모 배너·평문 gRPC 허용·데모 앱 자동 등록 |
| `ARGOS_START_PERIOD` | `60s` | 서버 healthcheck 유예 |
| `ARGOS_LOG_LEVEL` | `INFO` | 서버 로그 레벨 |
| `ARGOS_TZ_DISPLAY` | `UTC` | 알림 메시지 표시 시간대(저장은 항상 UTC) |
| `ARGOS_RETENTION_*` | 13 문서 기본 | `XLOG_DAYS=30`, `PROFILE_DAYS=7`, `RAW_STAT_DAYS=14`, `METRIC_DAYS=14`, `ROLLUP_1M_DAYS=90`, `ROLLUP_1H_DAYS=365` |
| `ARGOS_IP_MODE` | 설정 토큰 마법사에서 선택 | 무인 설치 시 `masked|full|off` 필수(SEC-31) |
| `ARGOS_BOOTSTRAP_APPS_FILE` | 없음 | 무인 설치 시 사전 등록할 앱·토큰 파일(12 문서 형식) |

### 3.9 데모 프로파일

- `docker compose --profile demo up -d` → 이미지 다운로드 제외 60초 내 XLog에 점이 흐른다(N-16). 부하 생성기는 50 TPS 기본, 믹스는 정상 70% / 느림(300ms~3초) 10% / 에러(5xx·예외) 5% / N+1(SQL 20개) 5% / 분산 호출(order → payment) 10%, 그리고 샘플 앱의 `@Scheduled` 배치가 2분마다 실행된다(S-01·S-03·S-05·S-09 재현).
- 샘플 앱 이미지는 `sample-apps/shop-boot3` + 에이전트 jar 동봉(`-javaagent:/opt/argos-agent/argos-agent.jar`). 데모 에이전트 설정은 `capture.sql-parameters: masked`, IP `masked` 등 **운영 기본값과 동일**해 데모가 곧 기본 설정 시연이 된다.
- 서버는 데모 모드에서 `demo` 환경의 앱 2개와 토큰, 권장 알림 규칙을 자동 등록한다. UI 상단에 "DEMO MODE: plaintext gRPC enabled, sample traffic" 배너.
- 데모 종료: `docker compose --profile demo down -v`.

### 3.10 리버스 프록시(443) 가이드 개요

`compose.proxy.yaml`은 Caddy(자동 인증서는 인터넷이 있을 때만) 또는 nginx 예시를 제공한다. 요구: TLS 종단, `X-Forwarded-Proto` 전달(쿠키 `Secure` 판단), WebSocket 업그레이드 통과(`/ws`), 요청 본문 상한 10MB, 타임아웃 65초 이상(WS keepalive), 보안 헤더는 서버가 붙이므로 프록시는 중복 추가하지 않는다. gRPC(9100)는 프록시를 거치지 않고 서버가 직접 TLS 종단한다(HTTP/2 프록시 설정 오류를 피한다).

## 4. tar + systemd 설치

### 4.1 레이아웃

```
/opt/argos/
  bin/      argos-server.sh  argos-admin  install.sh  upgrade.sh  backup.sh  restore.sh  diag-bundle.sh  verify-release.sh  env-init.sh
  conf/     application.yml  secrets.env(0600)  setup-token(0600, 생성 시)  master.key(0600)  tls/{server.crt,server.key,ca.crt}
            clickhouse/{config.d,users.d}  postgres/argos.conf.d  argos-server.service
  lib/      argos-server-X.Y.Z.jar  (심볼릭 링크 argos-server.jar → 현재 버전)
  logs/     server/  (JSON Lines, 로테이션 100MB×10)   admin/
  data/     wal/  spool/  setup-token
  licenses/ LICENSE  NOTICE  THIRD-PARTY-NOTICES  argos-deps.txt  sbom/
  releases/ X.Y.Z/ (이전 버전 jar·conf 백업, 롤백용 2세대 보관)
```

소유자 `argos:argos`(시스템 계정, 로그인 불가). `conf/`·`data/` 0700, 비밀값 파일 0600. 로그는 `logs/` 외에 `journald`로도 나간다(systemd `StandardOutput=journal`).

### 4.2 systemd 유닛

```ini
# /etc/systemd/system/argos-server.service
[Unit]
Description=Argos APM server
After=network-online.target postgresql.service clickhouse-server.service
Wants=network-online.target
Requires=postgresql.service clickhouse-server.service     # 외부 DB 모드에서는 install.sh가 이 줄을 제거

[Service]
Type=simple
User=argos
Group=argos
EnvironmentFile=/opt/argos/conf/secrets.env
Environment=ARGOS_HOME=/opt/argos
ExecStartPre=/opt/argos/bin/argos-server.sh preflight       # 포트·권한·디스크·DB 연결 검사(§4.3 단계 9)
ExecStart=/opt/argos/bin/argos-server.sh run
ExecStop=/bin/kill -TERM $MAINPID
TimeoutStopSec=90                                            # WAL 플러시·세션 종료 통지
Restart=on-failure
RestartSec=5
LimitNOFILE=65536
NoNewPrivileges=true
ProtectSystem=strict
ReadWritePaths=/opt/argos/data /opt/argos/logs
PrivateTmp=true

[Install]
WantedBy=multi-user.target
```

`argos-server.sh`는 `conf/application.yml`과 `secrets.env`를 읽고 JVM 옵션(§7.2)을 조립해 `java -jar lib/argos-server.jar`를 실행한다. `--version`, `preflight`, `run`, `admin <subcommand>` 서브커맨드.

### 4.3 `install.sh` 단계

| # | 단계 | 동작 | 실패 시 |
|---|---|---|---|
| 1 | 사전 요구 | root 또는 sudo, OS(RHEL·Rocky 8+/Ubuntu 20.04+), JDK 21(`java -version`), 디스크(권장 500GB, 최소 50GB), RAM(권장 16GB, 최소 8GB), 포트 8080·9100 미사용, `systemctl` | 종료 2, 부족 항목 표 출력. `--skip-checks`로 경고만 |
| 2 | 모드 선택 | `--mode bundled`(기본: ClickHouse·PostgreSQL을 OS 패키지로 설치) / `--mode external`(기존 DB 주소·계정 입력) / `--mode airgap`(번들 디렉터리에서 deb/rpm 설치) | - |
| 3 | 계정·디렉터리 | `argos` 시스템 계정, §4.1 레이아웃, 권한 | - |
| 4 | DB 설치(bundled·airgap) | ClickHouse: 공식 저장소 또는 번들 deb/rpm → `config.d/argos.xml`(13 문서, `listen_host` 127.0.0.1, RAM 산출값 치환)·`users.d/argos.xml`(default 비활성, `argos_writer`·`argos_reader`) 배치 → 서비스 enable. PostgreSQL 15/16: 저장소 또는 번들 → `listen_addresses='127.0.0.1'`, `shared_buffers`·`work_mem` → 서비스 enable → DB `argos`·사용자 `argos`(마이그레이션용)·`argos_app`(런타임, DDL 불가, SEC-76) 생성 | 종료 4, 수행한 단계 기록 |
| 5 | 비밀값 | `env-init.sh`(§3.7와 동일 로직)가 `conf/secrets.env`·`conf/master.key`·ClickHouse 비밀번호 적용 | 기존 파일 보존 |
| 6 | 설정 | `conf/application.yml` 템플릿 렌더(DB URL·바인드·보존·TLS 경로·IP 모드). TLS 인증서가 없으면 자체 서명 1년짜리를 생성하되 **경고**와 함께 교체 절차 안내(에이전트는 `ca-file`로 신뢰) | - |
| 7 | 서비스 설치 | 유닛 복사, `daemon-reload`, `enable` | - |
| 8 | 마이그레이션 | `argos-admin migrate`(PostgreSQL Flyway → ClickHouse 러너). 소요 시간 출력 | 종료 5, 로그 경로 |
| 9 | preflight·기동 | `argos-server.sh preflight`(DB 연결, 포트, 권한, 디스크, 금지 비밀번호) → `systemctl start argos-server` → health 대기(최대 120초) | 종료 6, `journalctl -u argos-server` 안내 |
| 10 | 부트스트랩 | 무인(`--admin-password-file`, `--ip-mode`)이면 관리자 생성·IP 모드 저장. 아니면 `conf/setup-token` 경로와 "30분 내 http://<host>:8080/setup 에서 입력" 안내 | - |
| 11 | 검증·요약 | `smoke`(포트 집합 `{8080, 9100}` + 127.0.0.1 DB 포트, 파일 권한, health), 보관할 파일(`conf/secrets.env`, `conf/master.key`, `conf/tls/`), 다음 단계(에이전트 설치 안내 URL) | 종료 7 |

### 4.4 운영 스크립트 사양

| 스크립트 | 입력 | 동작 | 출력·종료 코드 |
|---|---|---|---|
| `upgrade.sh <tar.gz>` | 새 릴리스 tar, `--yes` | ① `verify-release.sh` ② 호환 범위 검사(현재 X.Y → 새 X'.Y': 지원 중 마이너에서만, 메이저 건너뛰기 거부) ③ 접속 에이전트 버전 분포 조회 → 새 서버 호환 범위 밖 에이전트 수 경고 ④ PostgreSQL 덤프(백업 §10) ⑤ `releases/<old>/`에 jar·conf 보관 ⑥ `systemctl stop` ⑦ jar 교체·링크 ⑧ 설정 템플릿 diff 표시(새 키 추가만 자동, 변경은 수동) ⑨ `migrate` ⑩ `start` + health ⑪ 실패 시 자동 롤백 제안 | 단계별 로그, 소요 시간(목표 10분), 0/8(롤백 필요) |
| `backup.sh [--clickhouse]` | 대상 디렉터리(기본 `/var/backups/argos`, 외부 마운트 권장), `--encrypt <age 공개키|gpg id>` | PostgreSQL `pg_dump -Fc`(스키마 `argos`) → 0600 → 선택 암호화 → 7일 초과 삭제. `--clickhouse`면 13 문서의 `BACKUP ... TO Disk('backups')` 증분(외부 대상 필수, 같은 디스크면 거부) | 산출물 경로·크기·체크섬, 0/1 |
| `restore.sh <pg dump> [--clickhouse <backup>]` | 덤프 파일, 마스터 키 파일 | 서버 정지 → PostgreSQL 복원 → (선택) ClickHouse 복원 → 마스터 키 검증(채널 비밀값 복호화 시도) → `migrate`(복원본이 구버전이면 전진) → 기동 → 정합 검사 트리거 | 0/1, 시점 불일치 경고 |
| `diag-bundle.sh` | `--since 24h`, `--include-config` | 서버 로그(기간), `journalctl`, `application.yml`(비밀값 참조만), 유효 설정 덤프(`argos-admin config effective`, 비밀값 마스킹), 버전·`/actuator/info`, 자기 메트릭 스냅샷, `system.parts`·디스크·메모리, 접속 에이전트 요약, 마이그레이션 이력. **토큰·비밀번호·마스터 키·채널 URL은 `****`로 마스킹**(SEC-06·26) | `argos-diag-<host>-<ts>.tar.gz`(0600) |
| `verify-release.sh <dir>` | 릴리스 자산 디렉터리 | `SHA256SUMS` 검증 → `SHA256SUMS.asc` GPG 서명 검증(공개키 지문을 스크립트에 내장, 문서와 대조) → (온라인) `cosign verify-blob` 선택 → 결과 표 | 0(전부 통과)/1 |
| `argos-admin` | 서브커맨드 | `migrate`, `config effective|check`, `retention show|apply`, `rollup rebuild --date`, `consistency check --date`, `cleanup instances|dictionary|commands`, `token issue|revoke|rotate`, `setup-token regenerate`, `master-key rotate`, `tls reload`, `version`. 모두 감사 로그 | 표 출력, JSON(`--json`) |

## 5. 에어갭 번들

### 5.1 내용물 (`argos-airgap-X.Y.Z-<amd64|arm64>.tar`)

| 경로 | 내용 |
|---|---|
| `images/*.tar` | `docker save`한 argos-server·demo-app·demo-load·clickhouse·postgres 이미지(다이제스트 포함) + `load-images.sh` |
| `compose/` | §3 파일 전체(이미지 참조는 로컬 로드 후 그대로 유효) |
| `tar/argos-server-X.Y.Z.tar.gz` | §4 설치 패키지 |
| `packages/<distro>/` | ClickHouse·PostgreSQL deb/rpm(지원 배포판별), 의존 패키지 목록 |
| `agent/argos-agent-X.Y.Z.jar`, `agent/argos-agent.yml.example` | 에이전트 |
| `docs/` | 설치·운영·에이전트 설정·호환성·런북·CHANGELOG·업그레이드 가이드(HTML·Markdown 오프라인) |
| `SHA256SUMS`, `SHA256SUMS.asc`, `ARGOS-RELEASE-KEY.asc`, `KEY-FINGERPRINT.txt` | 체크섬·GPG 분리 서명·공개키·지문 |
| `sbom/` | CycloneDX SBOM(서버·에이전트·UI) |
| `verify-release.sh` | §4.4 |

### 5.2 반입 검증 절차

1. 반입 PC(인터넷 가능)에서 번들 다운로드 → `verify-release.sh`로 체크섬·GPG 검증. 공개키 지문을 저장소 `SECURITY.md`·문서 사이트·릴리스 노트의 지문과 **육안 대조**(세 곳이 일치해야 한다).
2. 매체(USB·내부 저장소)로 반입 → 폐쇄망 서버에서 다시 `verify-release.sh`(공개키는 번들에 동봉, 지문은 1단계에서 확인한 값과 대조).
3. `load-images.sh`(Compose) 또는 `install.sh --mode airgap --bundle <dir>`(tar).
4. 설치 후 `smoke.sh`.

### 5.3 egress 0 자동 검증 (N-17, O-P01)

테스트 VM(18 문서)에서 수행한다.

- 네트워크: 기본 라우트 없음 + nftables `counter`로 아웃바운드 패킷 집계. 허용 목록은 테스트용 SMTP·웹훅 수신기(같은 네트워크)뿐.
- 절차: 설치 → 데모 트래픽 1시간 → 알림 1회 발화(허용된 웹훅으로) → UI 전 화면 순회(Playwright) → `tcpdump -i any -nn not (dst net <내부 대역>)` 캡처 파일과 nftables 카운터 확인.
- 판정: 허용 목록 외 목적지 패킷 0. UI 번들의 외부 리소스 요청 0(브라우저 네트워크 로그). DNS 질의 0(허용 목록 호스트는 `/etc/hosts`).

## 6. 설정 관리

### 6.1 서버 설정 트리 (`application.yml` `argos.*`)

키의 전체 목록·기본값·설명은 12 문서(서버)와 13 문서(저장소 설정)가 정의하고 설정 레퍼런스 문서로 생성된다. 여기서는 최상위 구조와 운영 관점 규칙만 정한다.

| 최상위 키 | 내용 | 환경변수 접두 |
|---|---|---|
| `argos.server` | 바인드 주소·포트(http 8080, grpc 9100, management 8081), 외부 URL(딥링크·알림용), 표시 시간대 | `ARGOS_SERVER_*` |
| `argos.tls` | gRPC·HTTP 인증서·키·CA 파일, 최소 버전, 암호군, 리로드 감시 | `ARGOS_TLS_*` |
| `argos.collector` | 상한·쿼터(SEC-11~15), `allow-plaintext`, WAL 경로·상한, 배치 주기 | `ARGOS_COLLECTOR_*` |
| `argos.storage.clickhouse` | URL, writer·reader 자격증명(파일 참조), DB 이름, 테이블 접두(A-7) | `ARGOS_CH_*` |
| `argos.storage.postgres` | URL, 자격증명, 풀 | `ARGOS_PG_*` |
| `argos.retention` | xlog·profile·raw-stat·metric·1m·1h 일수 | `ARGOS_RETENTION_*` |
| `argos.security` | 마스터 키 파일, 세션 수명, 비밀번호 정책, 레이트 리밋, 관리 포트 인증(베어러 토큰 파일·IP 허용 목록), 설정 토큰 TTL | `ARGOS_SECURITY_*` |
| `argos.alert` | 평가 주기, 채널 기본값(SMTP, 웹훅 allow-cidrs·allow-http), 템플릿 | `ARGOS_ALERT_*` |
| `argos.agent` | 자동 등록 정책(환경별), 호환 범위, 명령 TTL·상한 | `ARGOS_AGENT_*` |
| `argos.privacy` | IP 모드 기본(마법사가 저장), 마스킹 패턴 추가분, 삭제 API 활성 | `ARGOS_PRIVACY_*` |
| `argos.ops` | 디스크 임계(80/90/95), 정리 스케줄, 정합 검사 시각, 인스턴스 보관 일수 | `ARGOS_OPS_*` |
| `argos.ui` | 로그 링크 템플릿, 기본 언어, 상황판 계정 허용 목록 | `ARGOS_UI_*` |
| `argos.demo` | 데모 모드 | `ARGOS_DEMO_*` |

규칙

- 환경변수 매핑은 Spring 관대한 바인딩(`argos.storage.clickhouse.url` ↔ `ARGOS_STORAGE_CLICKHOUSE_URL`)을 쓰되, Compose·문서에서는 §3.8의 **짧은 별칭**(`ARGOS_CH_URL` 등)을 쓴다. 별칭은 `application.yml`의 플레이스홀더로 매핑한다(둘 다 공개 계약).
- 비밀값 키는 반드시 `*_FILE`(파일 경로) 또는 `${file:/path}` 참조를 지원하며, 평문 값이 설정에 있으면 기동 시 WARN(운영 설치에서는 `install.sh`가 절대 평문을 쓰지 않는다).
- 알 수 없는 `argos.*` 키는 WARN이지 실패가 아니다(O-R03). `argos-admin config check`가 키 유효성·폐기 키·값 범위를 검사해 표로 출력한다.
- 유효 설정과 출처는 `argos-admin config effective`와 UI 관리 화면에서 조회한다(비밀값 마스킹).

### 6.2 에이전트 설정 배포 패턴

```yaml
# /opt/argos-agent/argos-agent.yml  (권한 0640, 소유자 앱 실행 계정)
server:
  address: argos.internal:9100
  tls:
    ca-file: /opt/argos-agent/argos-ca.crt      # 자체 CA 또는 사내 CA
  token-file: /opt/argos-agent/token            # 0600. 토큰 원문은 관리 화면에서 1회 표시
app:
  name: order-api
  env: prod
  version: ${APP_VERSION:}                      # 비우면 매니페스트 Implementation-Version
remote-config:
  allow: [sampling.*, instrumentation.disabled, instrumentation.ignore-paths, log.level, argos.enabled]
log:
  dir: /opt/argos-agent/logs                    # 0700, 10MB×5
```

- JVM 옵션 1줄: `-javaagent:/opt/argos-agent/argos-agent.jar=config=/opt/argos-agent/argos-agent.yml`. 문서는 Spring Boot `bootRun`, Dockerfile(`COPY argos-agent.jar`, `JAVA_TOOL_OPTIONS`), Tomcat `setenv.sh`, systemd `Environment=` 네 가지 스니펫을 제공한다(O-A08).
- 배포 전 `java -jar argos-agent.jar --check --config argos-agent.yml`(F-A27). 변경 통제 절차는 01 문서 9.5.
- 다수 서버 배포는 조직의 형상 관리 도구(Ansible 등) 몫이며, Argos는 설정 파일·토큰 파일 두 개와 권한만 요구한다. Kubernetes init-container 패턴은 1.x.

### 6.3 설정 변경 적용 경로

| 변경 대상 | 적용 방법 | 감사 |
|---|---|---|
| 서버 일반 설정(`application.yml`) | 파일 수정 → `argos-admin config check` → `systemctl restart`(또는 `docker compose up -d`). 계획 정지 1분 | 기동 로그에 설정 해시 |
| 보존(`argos.retention.*`) | 파일 수정 → 재기동 시 러너가 `ALTER TABLE ... MODIFY TTL`(연장, 재물질화 없음) 또는 단축 안내(다음 TTL 주기). 또는 `argos-admin retention apply`로 무중단 적용 | 감사 로그 `retention.changed`(전후값) |
| TLS 인증서 교체 | 파일 교체 → 자동 감지(파일 변경 감시) 또는 `argos-admin tls reload`. 재기동 없음(SEC-09) | `tls.reloaded` |
| 마스터 키 교체 | `argos-admin master-key rotate --new <file>`: 채널 비밀값·명령 결과 재암호화 → 설정 파일 경로 교체 → 재기동 | `master-key.rotated` |
| 에이전트 동적 설정 | UI 관리 화면(사유·diff·결과·되돌리기, F-S29). 에이전트 `remote-config.allow` 밖 키는 거부 | `agent-config.pushed` |
| 에이전트 정적 설정 | 앱 소유자가 yml 수정 + 재기동(또는 `설정 리로드` 명령으로 허용 키만) | 인스턴스 설정 해시 변경 이벤트 |

### 6.4 설정 토큰 부트스트랩 운영 절차 (SEC-47)

1. 서버 최초 기동 시 관리자가 없으면 32바이트 토큰을 생성해 **서버 로그 1줄**과 `data/setup-token`(0600)에 기록한다. TTL 30분(만료 시 재기동 또는 `argos-admin setup-token regenerate`).
2. 운영자가 `http://<host>:8080/setup`에 토큰 입력 → 관리자 계정(비밀번호 정책 SEC-48) → **IP 수집 모드 선택(필수, 기본 선택 `masked`)** → 외부 URL(딥링크용) → 완료. 토큰 즉시 무효, 파일 삭제.
3. 무인 설치: `ARGOS_ADMIN_PASSWORD_FILE` + `ARGOS_IP_MODE`(+ 선택 `ARGOS_BOOTSTRAP_APPS_FILE`). 설정 토큰은 생성되지 않고 마법사는 404.
4. 관리자가 존재하면 `/setup`은 404. 관리자 비밀번호 분실 시 `argos-admin user reset-password <name>`(로컬 셸 권한 = 관리 권한, 감사 로그).

## 7. 자원 예산·용량

### 7.1 S 토폴로지(16GB) 메모리 예산

| 구성 요소 | 예산 | 설정 |
|---|---|---|
| OS·페이지 캐시 | 2 GiB | - |
| PostgreSQL | 1.5 GiB | `shared_buffers=512MB`, `work_mem=16MB`, `max_connections=100`, `effective_cache_size=1GB` |
| argos-server | 5 GiB | `-Xms4g -Xmx4g`, `MaxDirectMemorySize=512m`, 메타스페이스·스레드·Netty 약 0.5GiB 여유 |
| ClickHouse | 7.5 GiB | `max_server_memory_usage=8053063680`, `mark_cache_size=512MiB`, `uncompressed_cache_size=0`, 쿼리 프로파일 상한(13 문서) |

### 7.2 서버 JVM 옵션(기본)

```
-Xms{heap} -Xmx{heap} -XX:+UseG1GC -XX:MaxGCPauseMillis=200
-XX:MaxDirectMemorySize=512m -XX:+ExitOnOutOfMemoryError -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/opt/argos/logs
-Xlog:gc*:file=/opt/argos/logs/server/gc.log:time,uptime:filecount=5,filesize=20m
-Djava.security.egd=file:/dev/urandom -Dfile.encoding=UTF-8 -Duser.timezone=UTC
```

### 7.3 RAM 비율 산출(설치 스크립트)

| 항목 | 식 | 하한 | 상한 |
|---|---|---|---|
| ClickHouse `max_server_memory_usage` | RAM × 0.47 | 3 GiB | 64 GiB(그 이상은 수동) |
| 서버 힙 | RAM × 0.25 | 2 GiB | 16 GiB |
| 서버 컨테이너 상한 | 힙 + 1 GiB | | |
| PostgreSQL `shared_buffers` | RAM × 0.03 | 256 MB | 4 GB |
| PostgreSQL 컨테이너 상한 | `shared_buffers` × 3 | 1 GiB | |
| 검사 | 합계 ≤ RAM × 0.9, 미달이면 ClickHouse부터 줄인다 | | |

RAM 8GB(최소)에서는 CH 3.5 / 서버 2+1 / PG 1 / OS 0.5GiB가 되며 설치 스크립트가 "평가용 구성, 운영은 16GB 권장"을 경고한다.

### 7.4 디스크 권장과 용량 공식 (N-19)

일 증가량(압축 후) = TPS × 86,400 × B_x + TPS × 캡처율 × step 수 × 86,400 × B_p + 인스턴스 × 시리즈 × 17,280 × B_m + 인스턴스 × 활성 SQL/창 × 8,640 × B_s. B_*는 Phase 0 PoC 실측으로 확정하고(기대 B_x 70B, B_p 25B, B_m 4B, B_s 20B), 운영 매뉴얼의 표는 실측값으로 갱신한다. 아래는 기대값·캡처율 12%·step 15·시리즈 150/인스턴스·활성 SQL 50/창 가정의 예시다.

| 규모 | 인스턴스 | xlog/일 | profile/일 | metric/일 | stat/일 | 보존 누적(30/7/14/14일 + 롤업) | 권장 디스크 |
|---|---|---|---|---|---|---|---|
| 500 TPS | 50 | 3.0 GB | 1.9 GB | 0.5 GB | 0.4 GB | 약 140 GB | 500 GB SSD |
| 2,000 TPS | 150 | 12 GB | 7.8 GB | 1.5 GB | 1.3 GB | 약 500 GB | 1 TB SSD |
| 5,000 TPS | 300 | 30 GB | 19 GB | 3.1 GB | 2.6 GB | 약 1.2 TB | 2 TB SSD, M 토폴로지 권장 |

추가 여유: WAL 10GiB, `keep_free_space_bytes` 10GiB, PostgreSQL 10GB, 로그 2GB, 백업은 **외부 대상**. `argos-admin capacity estimate --tps --instances --days`가 같은 공식으로 계산해 준다.

### 7.5 CPU·커널·파일

| 항목 | 권장 |
|---|---|
| CPU | S: 8코어(ClickHouse 4·서버 3·PG 1 배분은 OS 스케줄링에 맡기되 ClickHouse `max_threads=4`) |
| 파일 디스크립터 | argos 65,536, clickhouse 262,144 |
| 커널 | `vm.swappiness=1`, THP `madvise`(ClickHouse 권고), `net.core.somaxconn=4096` |
| 시계 | chrony/NTP 필수. 서버 시계가 틀리면 모든 에이전트가 WARN(D-02) |
| 파일시스템 | ext4/xfs, ClickHouse 데이터는 별도 볼륨 권장 |

## 8. 업그레이드

### 8.1 원칙

- 서버 `X.Y`는 에이전트 `X.(Y-2)`~`X.Y`를 수용한다(O-R02). **서버를 먼저** 올리고 에이전트는 앱 재기동 일정에 맞춰 순차 교체한다. 에이전트가 서버보다 새 버전이면 서버가 거부하고 관리 화면에 이유를 표시한다.
- 스키마는 전진 전용. 지원 중인 모든 이전 마이너에서 현재로 올릴 수 있다(한 번에). 메이저 건너뛰기는 거부한다.
- 업그레이드 전 PostgreSQL 덤프는 필수(스크립트가 자동 수행).

### 8.2 절차(계획 정지 10분 목표)

| 시각 | 단계 | 비고 |
|---|---|---|
| T-1일 | 릴리스 노트·업그레이드 가이드(`docs/upgrade/X.Y.md`) 확인, `verify-release.sh`, 호환 범위 밖 에이전트 수 확인(관리 화면 "에이전트 버전 분포") | |
| T-10분 | 알림 일시 중지(점검, 서버 자기 규칙 포함) | 수집 중단 구간은 D-18로 표시된다 |
| T+0 | `upgrade.sh`(Compose: `argos-compose.sh upgrade`) 시작: 백업 → 정지 → 교체 → 마이그레이션 → 기동 | 에이전트는 큐(120초분)에 쌓고 재접속 백오프 |
| T+5 | health 통과, `argos-admin consistency check --date today` 트리거, 관리 화면에서 세션 수 복귀 확인 | |
| T+10 | 알림 재개, 완료 기록 | 10분 초과 시 마이그레이션 소요가 원인이면 N-22 위반으로 기록 |

### 8.3 마이그레이션 실행 순서

1. PostgreSQL: Flyway `V<n>__*.sql`(`argos` 마이그레이션 계정). 트랜잭션 단위.
2. ClickHouse: 13 문서의 자체 러너. 이력은 PostgreSQL `ch_schema_history`(버전·체크섬·소요). 스크립트는 멱등(`IF NOT EXISTS`, `ADD COLUMN IF NOT EXISTS`). MV 교체는 4단계(새 이름 생성 → 백필 → 읽기 전환 → 구 MV 삭제)를 러너가 수행하며 백필은 서버 기동 후 백그라운드로 이어진다(기동을 막지 않는다).
3. 서버 리스너 기동. readiness는 1·2 완료 후.

### 8.4 롤백

- 대상: 직전 마이너까지. `upgrade.sh --rollback`은 `releases/<old>/`의 jar·conf를 복원하고 PostgreSQL을 **업그레이드 직전 덤프로 복원**한다(그 사이 수집된 메타데이터는 유실, 감사 로그에 기록). ClickHouse 스키마는 되돌리지 않는다(추가 컬럼·테이블은 구버전이 무시).
- 롤백 전 조건: 새 버전이 추가한 MV로의 읽기 전환이 끝났다면 구버전은 구 MV를 그대로 읽으므로 안전. 러너는 "읽기 전환 후 구 MV 삭제"를 **다음 마이너**에서 하도록 지연해 롤백 창을 보장한다.

### 8.5 베타 → GA 경로

`1.0.0-beta.1` → `beta.N` → `rc.1` → `1.0.0`은 모두 마이너 내 패치로 취급해 `upgrade.sh`가 그대로 동작한다. 0.x → 1.0.0은 메이저 전환이지만 0.x 마지막 마이너에서의 마이그레이션 경로를 제공한다(O-R01).

### 8.6 ClickHouse·PostgreSQL 버전 (N-21)

- 번들·Compose에 ClickHouse **정확 버전**을 고정한다(착수 시점 LTS). 지원 범위는 그 LTS와 다음 LTS. 업그레이드는 한 LTS 단위로, `docs/ops/clickhouse-upgrade.md`의 체크(설정 기본값 변경, 집계 상태 호환, `system.parts` 정상, 롤업 읽기 테스트) 후 수행. 외부 DB 모드에서는 지원 범위 밖 버전이면 서버가 WARN.
- PostgreSQL 15·16 지원. 메이저 업그레이드는 `pg_dump`/복원 또는 `pg_upgrade`, 운영 매뉴얼에 절차.

## 9. 보존·디스크·정리 운영

### 9.1 보존 변경

| 변경 | 절차 | 반영 |
|---|---|---|
| 연장 | `argos.retention.*` 수정 → `argos-admin retention apply`(무중단) 또는 재기동 → `MODIFY TTL ... SETTINGS materialize_ttl_after_modify=0` | 즉시(기존 데이터는 새 TTL로 자연 만료) |
| 단축 | 같은 절차 → 러너가 `MODIFY TTL` + 초과 파티션 `DROP PARTITION` 반복(I/O 급증 방지로 분당 N개) | 다음 TTL 주기(기본 4시간) 안 |
| 검증 | `argos-admin retention show`: 테이블별 TTL·가장 오래된 파티션·예상 디스크 | 감사 로그 |

### 9.2 디스크 임계 동작(서버 자동, F-S23)

| 사용률 | 동작 | 표시 |
|---|---|---|
| 80% | 경고 알림(기본 자기 규칙), 수집 상태 패널 경고 | 노랑 |
| 90% | **비상 보존 단축**: 가장 오래된 `profile_step` 파티션부터, 그다음 `xlog` 파티션 순으로 DROP(한 번에 1일, 85% 아래까지). 심각 알림 | 빨강, "비상 보존 단축 N일" 배지 |
| 95% | ClickHouse insert 중단, WAL만 기록(상한 10GiB). WAL 초과분은 서버 드롭으로 카운트. 심각 알림 | 수집 중단 음영(D-18) |
| 복구 | 디스크 확보 후 WAL 재적재 → 정합 검사 트리거 | |

운영자 조치 순서: 디스크 추가 또는 보존 단축(§9.1) → `argos-admin retention show`로 확인 → 비상 단축 배지 해제.

### 9.3 정리 작업 스케줄(서버 내장, `argos.ops.*`)

| 작업 | 기본 시각 | 내용 |
|---|---|---|
| 정합 검사·재구축 | 매일 02:00(서버 로컬) | 전날 D-03·D-12 검사, 불일치 날 재구축, 결과를 수집 상태 패널 |
| 인스턴스 보관·정리 | 매일 03:00 | 7일 미접속 → 보관(숨김), 30일 → 삭제(SEC-14) |
| 사전 정리 | 매일 03:30 | `last_seen` 90일 초과 사전 항목 삭제(SEC-13) |
| 명령 결과 만료 | 매일 04:00 | 30일 초과 `command_results` 삭제(SEC-22) |
| 감사 로그 만료 | 매일 04:30 | 보존(기본 1년) 초과 삭제, 별도 계정(SEC-82) |
| 알림 이력 정리 | 매일 05:00 | 1년 초과 `alert_events` 삭제 |
| 토큰·인증서 만료 점검 | 매시 | 30일·7일 전 알림(SEC-03·09) |
| PostgreSQL 백업 | 매일 01:00(systemd timer 또는 Compose cron 컨테이너) | §10 |

## 10. 백업·복구 (N-20, SEC-84)

| 항목 | PostgreSQL(필수) | ClickHouse(선택) |
|---|---|---|
| 내용 | 사용자·세션, 앱·인스턴스, 토큰 해시, 사전 원문, 에러 그룹, 규칙·채널(암호문), 명령·결과(암호문), 이벤트, 감사, 마이그레이션 이력 | 트랜잭션·프로파일·메트릭·통계·롤업 |
| 방법 | `pg_dump -Fc --schema=argos`, 0600, 선택 `age`/GPG 암호화 | `BACKUP DATABASE argos TO Disk('backups', '<ts>') SETTINGS base_backup = ...`(증분), 외부 마운트·S3 호환 대상만. 같은 디스크면 스크립트가 거부 |
| 주기·보관 | 일 1회, 7일 | 일 1회, 7일 |
| RPO / RTO | 24시간 / 30분 | 24시간 / 데이터 크기 의존 |
| 함께 보관해야 하는 것 | `conf/master.key`(없으면 채널 비밀값·명령 결과 복호화 불가), `conf/secrets.env`, `conf/tls/` | - |
| 복구 리허설 | Gate 2 전 1회, 이후 분기 1회. `restore.sh`로 비참여자가 1시간 안에 | 포함 |
| 시점 불일치 | PG가 CH보다 오래되면 "알 수 없는 인스턴스(id)"로 표시, 사전 키는 내용 해시라 에이전트 재접속 시 자가 복구. CH가 더 오래되면 조회 공백만 생긴다 | |

운영 매뉴얼에 "백업에는 개인정보 가능 필드(IP·user_id·SQL 원문·명령 결과)가 포함된다"를 명시한다.

## 11. 자기 모니터링·로그

### 11.1 메트릭 노출

- `/actuator/prometheus`는 관리 포트 8081에서만. 로컬 스크랩(127.0.0.1)은 무인증, 원격은 `argos.security.management.token-file`의 베어러 토큰 + `allow-cidrs`. `/actuator/health`는 상세 숨김, `env`·`heapdump`·`threaddump`·`loggers` 비활성(SEC-55).
- Prometheus 연동 예시(`prometheus.yml` job `argos-server`, `bearer_token_file`)와 Grafana 대시보드 JSON(서버 자기 상태)을 `deploy/monitoring/`에 제공한다.
- 핵심 자기 메트릭(12·13 문서 정의): `argos_ingest_lag_seconds`, `argos_collector_rejected_total{reason}`, `argos_server_dropped_total{reason}`, `argos_wal_bytes`, `argos_sessions`, `argos_ch_parts_active{table}`, `argos_ch_delayed_inserts_total`, `argos_disk_usage_ratio{mount}`, `argos_notification_failures_total{channel}`, `argos_server_tls_cert_expiry_seconds`, `argos_agent_tokens_expiring`, `argos_consistency_mismatch_minutes`.

### 11.2 기본 자기 알림 규칙(설치 시 생성, 01 문서 부록 A)

서버 디스크(80/90), 적재 지연(30초), 채널 실패, 인증서·토큰 만료(30/7일), parts 폭증(파티션당 300/1,000), WAL 사용률 80%, 정합 불일치 발생. 채널은 설치 마법사에서 지정한 관리자 이메일(없으면 UI 배지만).

### 11.3 로그

| 로그 | 위치 | 형식·보존 |
|---|---|---|
| 서버 | `logs/server/argos-server.log`(+ journald) | JSON Lines, 요청 id, 비밀값 마스킹, 100MB×10 |
| 서버 GC | `logs/server/gc.log` | 20MB×5 |
| 감사 | PostgreSQL `audit_logs` + 선택 `logs/audit/*.jsonl`·syslog | 1년(SEC-82) |
| 관리 CLI | `logs/admin/` | 명령·결과 |
| 에이전트 | 앱 서버 `argos-agent/logs/`(0700) | 10MB×5, INFO 이하 PII 없음(SEC-26) |

## 12. 운영 런북 (`docs/ops/runbook.md` 목차와 핵심 항목)

| 증상 | 가능 원인 | 확인 | 조치 |
|---|---|---|---|
| 적재 지연 상승(`argos_ingest_lag_seconds` > 5) | ClickHouse merge 지연, DelayedInserts, 디스크 느림, 쿼리 과부하 | 저장소 상태 패널(parts·merges), `system.query_log` p99, 디스크 I/O | 무거운 조회 중단, `max_concurrent_queries` 확인, parts 폭증이면 §9.1 보존 단축·디스크 확장. 에이전트 쿼터(SEC-12) 조정 |
| parts 폭증(파티션당 300 초과) | 늦은 도착 데이터로 과거 파티션 다수 생성, insert 주기 과소, 머지 스레드 부족 | `system.parts` 파티션별, 수집 상태 "늦은 도착" 수 | 시계 차이 큰 인스턴스 확인(D-15 가드), 13 문서 `background_pool_size`, 일시적으로 `OPTIMIZE TABLE ... PARTITION` |
| 디스크 풀 | 보존 과다, 비상 단축 미동작, WAL 누적 | `argos_disk_usage_ratio`, `argos-admin retention show` | §9.2. 디스크 추가 또는 보존 단축. WAL은 ClickHouse 복구 후 자동 재적재 |
| 에이전트 접속 거부 | 토큰 폐기·만료, 앱 미등록(승인 대기), 버전 호환 범위 밖, TLS 호스트명 불일치, 평문 접속 | 서버 로그 `UNAUTHENTICATED/PERMISSION_DENIED/FAILED_PRECONDITION` 사유, 관리 화면 승인 대기·버전 분포, 에이전트 로그 | `--check`로 재현. 토큰 재발급, 앱 승인, 에이전트 버전 교체, `ca-file`·SAN 확인 |
| 시계 차이 경고(WARN) | 앱 서버 NTP 미동작 | 인스턴스 상세 시계 차이 값 | 앱 서버 chrony 수정. 서버 시계가 틀리면 전 인스턴스 WARN이므로 서버 먼저 |
| WAL 복구 | 서버 비정상 종료, ClickHouse 장기 중단 | 기동 로그 "WAL replay N segments", `argos_wal_bytes` | 자동. 재적재 후 `consistency check` 결과 확인. WAL 손상 세그먼트는 격리(`data/wal/corrupt/`)·드롭 카운트 |
| 알림 채널 실패 | SMTP 다운, 웹훅 URL 폐기, 목적지 검증 거부, TLS | 알림 이력 "전송 실패만", `argos_notification_failures_total` | 채널 테스트 전송(같은 검증), URL·인증서 교체. 채널 실패 자기 알림은 다른 채널로 간다 |
| 세션 폭주·거부 | 자동 스케일 재기동 반복, 토큰 공유, 공격 | `argos_sessions`, `argos_collector_rejected_total{reason=rate_limit}` | 인스턴스 등록 상한·레이트 리밋(SEC-05·14) 확인, 문제 토큰 폐기 |
| 인증서 만료 임박·만료 | 갱신 누락 | `argos_server_tls_cert_expiry_seconds`, 에이전트 로그 핸드셰이크 실패 | 파일 교체 → 자동 리로드 또는 `argos-admin tls reload`. 만료 후에는 모든 에이전트 수집 중단이므로 심각 알림 7일 전 |
| 마스터 키 분실 | 백업 누락 | 채널 전송 실패(복호화 불가), 명령 결과 열람 불가 | 복구 불가. `argos-admin master-key reset`으로 새 키 생성 후 채널 비밀값 **재입력**, 기존 명령 결과 폐기. 감사 로그 |
| 설정 토큰 분실·만료 | 30분 경과 | `/setup` 403 | `argos-admin setup-token regenerate`(관리자 없을 때만) |
| 서버 OOM·재시작 | 힙 부족, ClickHouse 메모리 초과 | `dmesg`, 힙 덤프, `MemoryTracking` | §7 예산 재산출, 쿼리 상한 확인 |
| 야간 정합 불일치 | 중복·누락, MV 실패 | 수집 상태 패널 "정합", `argos_consistency_mismatch_minutes` | 자동 재구축 결과 확인. 반복되면 WAL·dedup 토큰 로그 조사(버그 보고) |

## 13. 1.x 자리

- **Helm 차트**(`deploy/helm/argos/`): 서버 Deployment(역할 프로파일 값), ClickHouse·PostgreSQL은 서브차트 또는 외부(권장 외부 운영자 관리), Secret 생성 Job(§3.7 로직), NetworkPolicy(DB 내부만), Ingress(443, WS), ServiceMonitor(관리 포트). 에이전트 init-container 패턴(jar 복사) 문서.
- **역할 분리**: `argos.server.roles=collector|web|alert|ops` 프로파일, collector 다중 노드는 L4 뒤, 명령 라우팅은 세션 보유 노드로(12 문서 A-2 버스). 1.0은 `all`만 공식.
- **ClickHouse 복제 전환**: MergeTree → ReplicatedMergeTree(13 문서 A-9 절차: 신규 테이블 + `ATTACH PARTITION FROM` 또는 버전별 변환 플래그), Keeper 3노드. 테이블·DB 이름이 설정값이라 조회 계층 변경 없음.
- **디스크 스풀**(에이전트, A-12), **mTLS**(SEC-10), 보존 UI.

## 14. 테스트 포인트 (03 문서 3.4·4장 대응)

| 03 항목 | 검증 방법 | 자동/수동 |
|---|---|---|
| 비참여자 설치 30분 | 깨끗한 VM(RHEL 9·Ubuntu 22.04) × Compose·tar, 가이드만으로, 시간 기록 | 수동(Gate 1·2) |
| 데모 60초(N-16) | `docker compose --profile demo up` → UI XLog 첫 점까지 시간을 Playwright가 측정 | 자동(nightly) |
| 설치 직후 상태 | `smoke.sh`: 노출 포트 `{8080, 9100}`, `.env`/`secrets` 권한 0600, 금지 비밀번호 기동 거부, `/setup` 토큰 없이 403, `/actuator/env` 404 | 자동 |
| 업그레이드 | "지원 중인 이전 마이너 데이터(1/100) → 현재" 자동 테스트, 소요 시간 기록, 롤백 스크립트 복원 검증 | 자동(릴리스 전) |
| WAL 복구 | `kill -9` → 재기동 → 보낸 행 수 = count(xlog) | 자동(18 문서 카오스) |
| 에어갭 egress 0(N-17) | §5.3 절차, 패킷 캡처 0 | 자동(릴리스 전) |
| 릴리스 검증 | `verify-release.sh` 통과, 변조 파일 실패 | 자동 |
| 진단 번들 마스킹 | 번들 산출물 grep: 토큰·비밀번호·마스터 키 0건 | 자동 |
| 백업·복구 | `backup.sh` → `restore.sh` 라운드트립, 산출물 권한 0600, 같은 디스크 CH 백업 거부 | 자동 + 리허설 수동(Gate 2) |
| 디스크 임계 동작 | 테스트 컨테이너에서 디스크 채움 → 90% DROP·95% WAL 전환·복구 재적재 | 자동(18 문서) |
| 자원 산출 | RAM 8/16/32GB 입력에 대한 `env-init.sh` 출력 스냅샷 | 자동 |

## 15. 요구 ID 대응표

| 요구 | 이 문서의 대응 |
|---|---|
| N-11 설치 30분·업그레이드 10분 | §3·§4 설치 단계, §8.2 절차 |
| N-12 폐쇄망 | §5 에어갭 번들, DSN-17-11 |
| N-16 빠른 시작 | §3.9 데모 프로파일 |
| N-17 egress 0 | §5.3, Compose `back` internal 네트워크 |
| N-18 자원 상한 | §7.1·7.3 |
| N-19 용량 산정 | §7.4 공식·표·`capacity estimate` |
| N-20 백업·복구 | §10 |
| N-21 저장소 버전 | §8.6 |
| N-22 마이그레이션 | §8.3·8.4 |
| F-S20 자기 메트릭 | §11.1 |
| F-S23 저장소 상태·디스크 비상 | §9.2 |
| S-07 에이전트 도입 | §6.2 패턴, `--check` |
| S-08 수집 서버 장애 | WAL·큐·재접속 전제, §12 |
| O-A01·A06·A08 | §3.9, §3.6, §6.2 스니펫 |
| O-P01 | §5.3, 네트워크 정책 |
| O-R04 | §8.3 전진 전용 |
| O-R12 | `argos-server.sh --version`, `/actuator/info` |
| SEC-07·09 | §6.1 `argos.tls`, §6.3 리로드 |
| SEC-26 | §11.3 에이전트 로그 권한 |
| SEC-47 | §6.4, DSN-17-06 |
| SEC-52 | 마스터 키 파일·교체 절차 |
| SEC-55 | DSN-17-13 |
| SEC-74·75·76 | DSN-17-03·04, §4.3 계정 분리 |
| SEC-84 | §10 |

## 16. 미결·가정

| # | 항목 | 가정 | 확인 시점 |
|---|---|---|---|
| 1 | ClickHouse 고정 버전 | 착수 시점 LTS | WO-03(13 문서) |
| 2 | ClickHouse 공식 이미지의 `CLICKHOUSE_SKIP_USER_SETUP`·users.d 파일 참조 방식(`password_sha256_hex` 파일 포함 가능 여부) | 가능. 불가 시 entrypoint 스크립트로 치환 | WO-40 |
| 3 | 서버 베이스 이미지 | Eclipse Temurin 21 JRE(Ubuntu 기반), 비루트 | WO-00/40 |
| 4 | tar 설치의 PostgreSQL 버전 | 16(PGDG), 15도 지원 | WO-40 |
| 5 | 에어갭 번들의 DB 패키지 동봉 범위 | RHEL/Rocky 8·9, Ubuntu 20.04·22.04·24.04의 deb/rpm. 번들 크기 약 1GB/arch | Gate 1.5 |
| 6 | Compose `internal` 네트워크에서 서버의 알림 아웃바운드 | `front` 네트워크로 나간다. 데모 앱은 egress 없음 | WO-40 |
| 7 | 리버스 프록시 예시 | Caddy(자동 인증서는 온라인 전제)와 nginx 둘 다 | Gate 1.5 |
| 8 | 백업 암호화 도구 | `age` 우선(단일 바이너리), GPG 대안 | WO-40 |
| 9 | 메모리 비율(0.47/0.25/0.03) | 16GB 경험값. WO-27 부하 테스트 RSS 실측 후 조정 | Gate 1 |
| 10 | 비상 보존 단축의 DROP 단위·속도 | 1일 파티션, 85%까지, 분당 1개 | WO-23/28 |
| 11 | 관리 포트 8081 분리와 Spring Actuator `management.server.port` | 가능 | WO-20 |
| 12 | 설치 스크립트 언어 | POSIX sh + 최소 의존(`openssl`, `curl`, `tar`, `systemctl`). Windows 서버 설치는 비범위 | WO-40 |

## 17. 다른 설계 문서에 요구하는 것

| 대상 문서 | 요구 | 이유 |
|---|---|---|
| 12 서버 | `argos.*` 설정 트리 전체 목록·기본값·`*_FILE` 지원·짧은 별칭 매핑 표, `argos-admin` 서브커맨드 구현, 설정 토큰·무인 부트스트랩(`ARGOS_ADMIN_PASSWORD_FILE`, `ARGOS_IP_MODE`, `ARGOS_BOOTSTRAP_APPS_FILE` 형식), 관리 포트 8081 분리와 인증, readiness(마이그레이션 완료) 그룹, 디스크 임계 동작(§9.2) 구현, 정리 스케줄(§9.3), TLS 파일 변경 감시 리로드, 자기 메트릭 이름(§11.1) | 운영 절차가 전제하는 서버 기능 |
| 13 저장소 | `config.d/argos.xml`·`users.d/argos.xml` 완성본(RAM 치환 변수 `{{CH_MAX_MEMORY}}` 등), ClickHouse 러너 사양(`ch_schema_history`, MV 4단계, 구 MV 삭제 지연), `MODIFY TTL`·`DROP PARTITION` 절차, 증분 백업 명령, 복제 전환 절차(A-9), 용량 B_* 실측 보고 형식 | 설치 스크립트가 배치·치환하고 운영 절차가 호출 |
| 16 보안 | 마스터 키 포맷·교체(재암호화) 절차, 관리 포트 베어러 토큰 발급, TLS 암호군 설정 키, GPG 공개키 게시 위치·지문 대조 절차, 진단 번들 마스킹 패턴 | SEC 구현과 운영 절차 일치 |
| 19 빌드·릴리스 | `:deploy:tarDist`·`:deploy:airgapBundle` 태스크, `compose.yaml` 다이제스트 자동 갱신, 데모 이미지 2종 빌드, `SHA256SUMS`·`.asc`·공개키·지문 파일 생성, `verify-release.sh` 동봉 | 산출물 생성 |
| 18 테스트 | §14의 자동 테스트(smoke, 데모 60초 측정, 업그레이드 경로, WAL 복구, egress 0, 디스크 임계, 백업 라운드트립, env-init 스냅샷) 하네스 | 검증 자동화 |
| 10 에이전트 | `--check` 출력 형식·종료 코드, `server.token-file`·`tls.ca-file` 키, 로그 디렉터리 권한 0700 생성, 기동 배너 1줄 | 에이전트 설치 문서와 일치 |
| 02 아키텍처 | §8 S 토폴로지에 "관리 포트 8081(127.0.0.1)" 추가, 노출 포트 집합 문구에 반영 | 포트 정책 일치 |
