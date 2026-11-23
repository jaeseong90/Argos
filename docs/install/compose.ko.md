# Docker Compose로 Argos APM 설치

이 가이드는 Docker Compose로 Linux 호스트 한 대에 Argos APM 서버를 설치합니다. Argos 서버,
ClickHouse, PostgreSQL을 기본 비밀번호 없이, 네트워크에 포트 두 개만 열어 설치합니다. Argos를 처음
보는 운영자를 위해 썼습니다. 처음부터 끝까지 순서대로 따라 하세요. 다운로드와 이미지 빌드를 빼면 첫
설치는 약 30분이 걸립니다([시간 예산](#13-시간-예산과-테스터-체크리스트)).

[English](compose.md)

텔레메트리: 없음. Argos는 사용 통계를 보내지 않고 업데이트를 확인하지 않습니다. 서버는 운영자가 설정한
대상, 곧 데이터베이스 두 개와 나중에 설정하는 알림 채널에만 연결합니다.

## 1. 설치되는 것

### 서비스, 포트, 볼륨

| 서비스 | 이미지 | 네트워크 | 호스트 포트 |
|---|---|---|---|
| `server` | `argos-server`, 이 버전에서는 소스로 빌드([3장](#3-파일과-서버-이미지-준비)) | `front`, `back` | `8080`(UI와 REST API), `9100`(에이전트 gRPC, TLS) |
| `clickhouse` | `clickhouse/clickhouse-server` 26.8 LTS, 다이제스트 고정 | `back` | 없음 |
| `postgres` | `postgres` 16, 다이제스트 고정 | `back` | 없음 |

- 호스트에 게시되는 포트는 `8080`과 `9100`뿐입니다([8장](#8-리버스-프록시443-https)의 선택 리버스
  프록시를 쓰면 `443`이 더해집니다). ClickHouse(`8123`, `9000`)와 PostgreSQL(`5432`)은 `back` 네트워크
  안에서만 닿습니다.
- `back` 네트워크는 internal입니다. 데이터베이스에는 호스트 밖으로 나가는 경로가 없습니다. 서버만
  `front`에도 연결되며, 게시 포트와 운영자가 설정한 알림 채널로 가는 연결이 이 네트워크를 지납니다.
- 관리 포트 `8081`(health, Prometheus 메트릭)은 게시하지 않습니다. 호스트의 `127.0.0.1`에만 게시할 수
  있습니다([7.2절](#72-관리-포트)).
- 서버는 uid/gid `10001`로 실행되며 root로 실행되지 않고, 루트 파일 시스템은 읽기 전용입니다.

| 볼륨 | 마운트 위치 | 내용 |
|---|---|---|
| `argos-data` | 서버 `/data` | 선행 기록 로그(`wal/`, 최대 10 GiB), 설정 토큰, 세션 서명 키 |
| `argos-logs` | 서버 `/logs` | 서버 로그 파일(JSON Lines, 100 MB x 10) |
| `clickhouse-data` | ClickHouse 데이터 디렉터리 | 모니터링 데이터(트랜잭션, 프로파일, 메트릭, 통계) |
| `postgres-data` | PostgreSQL 데이터 디렉터리 | 설정 데이터: 사용자, 앱, 에이전트 토큰, 감사 로그 |

Compose 프로젝트 이름이 `argos`(`.env`의 `COMPOSE_PROJECT_NAME`으로 바꿈)이므로 Docker는 볼륨을
`argos_argos-data` 같은 이름으로 보여 줍니다.

호스트의 `deploy/compose/`에 있는 파일:

| 경로 | 내용 |
|---|---|
| `.env` | 비밀값이 없는 설정(이미지, 공개 URL, 바인드 주소, 메모리, 보존 기간), 모드 `0600` |
| `secrets/` | 생성된 비밀번호와 키, 디렉터리 `0700`, 파일 `0600` |
| `tls/` | gRPC 인증서와 키([4장](#4-grpc-인증서-준비)) |
| `compose.override.yaml` | 선택: 운영자 자신의 Compose 변경. 있으면 `argos-compose.sh`가 더합니다 |

### 단일 노드, 복제 없음

이 설치는 단일 노드입니다. 함께 설치되는 ClickHouse는 서버 한 대에서 테이블을 일반(비복제) MergeTree
테이블로 두며, 장애 조치가 없습니다. 호스트나 디스크를 잃으면 모니터링 데이터도 잃습니다. 먼저
백업할 부분은 PostgreSQL의 설정 데이터입니다. 복제 구성의 ClickHouse로 전환하는 방법은 이 가이드에서
다루지 않습니다.

## 2. 시작하기 전에

### 호스트

- x86-64(amd64) Linux. 이 버전은 amd64용 서버 이미지만 제공합니다. 기준 시스템은 Ubuntu 22.04와
  RHEL 9입니다.
- Docker Engine 24 이상(rootful, rootless Docker는 지원하지 않음), Compose 플러그인 v2.24 이상,
  `openssl`, `curl`(`smoke.sh`가 씁니다):

  ```sh
  docker version --format '{{.Server.Version}}'
  docker compose version
  openssl version
  curl --version
  ```

  사용자가 `docker` 그룹에 없다면 두 `docker` 명령 앞에 `sudo`를 붙이세요.

- `sudo`를 통한 root 권한. 비밀값 파일은 서버 컨테이너의 사용자인 uid `10001`의 소유이므로 설치
  스크립트는 root로 실행합니다.
- 시간 동기화(chrony 또는 NTP). 에이전트는 자기 시계를 서버 시계와 비교합니다.
- Windows나 macOS의 Docker Desktop은 지원하는 운영 구성이 아닙니다. NTFS 같은 비POSIX 파일
  시스템에서는 `0600` 같은 파일 모드를 강제할 수 없습니다.

### CPU, 메모리, 디스크

| 자원 | 평가용 | 운영(노드 1대) |
|---|---|---|
| CPU | 코어가 적어도 부하가 낮으면 동작 | 8코어 |
| 메모리 | 8 GB: 평가 전용, `argos-env-init.sh`가 경고. 8 GB 호스트는 Docker에 약 7.5~7.9 GiB로 보입니다. 7168 MiB부터는 상한을 낮춘 최소 사양 구간을 쓰고([7.1절](#71-메모리)), 7168 MiB 미만은 거부합니다 | 16 GB 이상 |
| 디스크 여유 공간 | 최소 20 GB(`argos-env-init.sh`가 그보다 적으면 거부) | 100 GB 이상, 아래 표로 산정, SSD |

`argos-env-init.sh`가 호스트 메모리를 세 컨테이너에 나눕니다([7.1절](#71-메모리)).

기본 보존 기간(트랜잭션 30일, 프로파일 7일, 통계와 메트릭 14일, 1분 집계 90일, 1시간 집계 365일)에서의
디스크 산정 예상치:

| 부하 | 인스턴스 | 보존 기간이 찬 뒤 저장량 | 권장 디스크 |
|---|---|---|---|
| 초당 트랜잭션 500 | 50 | 약 140 GB | 500 GB SSD |
| 초당 트랜잭션 2,000 | 150 | 약 500 GB | 1 TB SSD |
| 초당 트랜잭션 5,000 | 300 | 약 1.2 TB | 2 TB SSD, 노드 여러 대 검토(이 버전에는 없음) |

선행 기록 로그(10 GiB), ClickHouse가 남겨 두는 여유 공간(10 GiB), PostgreSQL(10 GB), 로그(2 GB)로 약
32 GB를 더하세요. 백업은 다른 장비에 두세요. 파일 시스템은 ext4나 xfs를 쓰세요.

### 네트워크

| 방향 | 포트 | 출발지 또는 목적지 |
|---|---|---|
| 인바운드 | `8080/tcp` | Argos 사용자의 브라우저, 또는 리버스 프록시만([8장](#8-리버스-프록시443-https)) |
| 인바운드 | `9100/tcp` | 모니터링 대상 Java 애플리케이션(에이전트) |
| 인바운드, 선택 | `443/tcp` | 브라우저, [8장](#8-리버스-프록시443-https)의 리버스 프록시를 쓸 때 |
| 아웃바운드, 설치 때만 | `443/tcp` | ClickHouse와 PostgreSQL 이미지를 내려받을 Docker Hub |
| 아웃바운드, 선택 | 설정한 대로 | 운영자의 알림 채널(메일 서버, 웹훅) |

실행 중에는 그 밖에 아무것도 필요하지 않습니다. 텔레메트리: 없음.

## 3. 파일과 서버 이미지 준비

미리 빌드되고 다이제스트가 고정된 서버 이미지를 담은 릴리스 패키지는 아직 발행되지 않았습니다. 그때까지는
서버 이미지를 소스로 빌드합니다.

1. 소스를 받습니다. 공개 저장소는 아직 없습니다. 메인테이너가 알려 준 위치나 git 번들을 쓰고, 그
   디렉터리로 이동하세요.

   ```sh
   git clone https://github.com/argos-apm/argos.git
   cd argos
   ```

   umask가 엄격한 호스트(보안 가이드가 권하는 `027`이나 `077`)에서는 컨테이너가 읽는 설정 파일을
   컨테이너가 읽지 못하게 되고, `argos-compose.sh up`이 기동을 거부합니다. 체크아웃 전에 `umask 022`를
   실행하거나, 나중에 파일 권한을 고치세요([11장](#11-문제-해결)).

2. JDK 21, Node.js 24.x(24.14.0 이상, Corepack 포함), Docker가 있는 장비에서 서버 이미지를 빌드합니다.
   [docs/dev-setup.md](../dev-setup.md) 1절에 이 도구들이 있습니다. Gradle이나 pnpm을 전역으로 설치할
   필요는 없습니다. 빌드가 이미지를 로컬 Docker 데몬에 넣으므로, 빌드하는 사용자는 Docker를 쓸 수
   있어야 합니다(`docker` 그룹 구성원). `./gradlew`를 `sudo`로 실행하지 마세요. 아래의 `docker image ls`,
   `docker save`, `docker load`도 마찬가지입니다. `docker` 그룹이 아니면 그 앞에 `sudo`를 붙이세요.
   저장소 루트에서:

   ```sh
   ./gradlew :server:jibDockerBuild
   ```

   빌드는 웹 UI를 포함하며, 이미지 `argos-server:<version>`(예: `argos-server:0.0.0-SNAPSHOT`)을 로컬
   Docker 데몬에 넣습니다. 확인하세요.

   ```sh
   docker image ls argos-server
   ```

3. 다른 장비에서 이미지를 빌드했다면 Argos 호스트로 복사합니다.

   ```sh
   docker save argos-server:0.0.0-SNAPSHOT | gzip > argos-server.tar.gz   # on the build machine
   docker load -i argos-server.tar.gz                                     # on the Argos host
   ```

4. 이후 명령은 모두 Argos 호스트에 있는 소스 트리의 `deploy/compose/`에서 실행합니다.

   ```sh
   cd deploy/compose
   ```

ClickHouse와 PostgreSQL 이미지는 `deploy/clickhouse/image.properties`, `deploy/postgres/image.properties`와
같은 태그와 다이제스트로 참조합니다. Docker가 첫 기동 때 내려받습니다.

## 4. gRPC 인증서 준비

에이전트는 TLS로 포트 `9100`에 접속하고, 호스트 이름을 포함해 서버 인증서를 검증합니다. 서버는
인증서와 키가 없으면 기동하지 않고, `argos-compose.sh up`도 두 파일을 검사합니다. 첫 기동 전에 놓으세요.

| 파일 | 내용 | 모드와 소유자 |
|---|---|---|
| `tls/server.crt` | 서버 인증서와 그 뒤에 이어지는 중간 CA 인증서(PEM) | `0644` |
| `tls/server.key` | 개인 키, 암호화하지 않은 PKCS#8 PEM(첫 줄이 대시 사이의 `BEGIN PRIVATE KEY`) | `0600`, uid `10001` 소유 |

인증서의 주체 대체 이름(SAN)에는 에이전트가 쓰는 주소를 모두 넣어야 합니다. DNS 이름(예:
`argos.example.com`)과, 에이전트가 IP 주소로 접속한다면 그 IP 주소입니다.

### 방법 A: 조직의 인증서

인증 기관에 위 이름을 넣은 서버 인증서를 요청하고 파일을 복사하세요.

```sh
mkdir -p tls && chmod 0700 tls
cp /path/to/argos.example.com.crt tls/server.crt
cp /path/to/argos.example.com.key tls/server.key
```

키의 첫 줄이 대시 사이의 `BEGIN PRIVATE KEY`가 아니면 다음 명령으로 PKCS#8로 변환하세요. 예를 들어
`BEGIN RSA PRIVATE KEY`(PKCS#1) 키나 `BEGIN EC PRIVATE KEY`(SEC1) 키입니다. EC 키 앞에
`BEGIN EC PARAMETERS` 블록이 있어도 같은 명령으로 됩니다.

```sh
openssl pkcs8 -topk8 -nocrypt -in /path/to/argos.example.com.key -out tls/server.key
```

첫 줄이 `BEGIN ENCRYPTED PRIVATE KEY`이면 대신 키의 암호를 풉니다. `openssl`이 암호를 묻습니다.

```sh
openssl pkcs8 -in /path/to/argos.example.com.key -out tls/server.key
```

그다음 결과의 첫 줄을 확인하세요.

```sh
head -n 1 tls/server.key
```

`-----BEGIN PRIVATE KEY-----`가 출력되어야 합니다.

에이전트의 JVM이 그 CA를 이미 신뢰하지 않는다면, 에이전트에는 이 인증서를 발급한 CA 인증서(체인)가
필요합니다.

### 방법 B: 자체 CA로 만든 평가용 인증서

평가할 때는 작은 자체 CA와 그 CA가 서명한 서버 인증서를 만듭니다. 두 변수를 에이전트가 쓸 이름과
주소로 바꾼 뒤 명령을 그대로 실행하세요.

```sh
CERT_DNS=argos.example.com
CERT_IP=192.0.2.10
mkdir -p tls && chmod 0700 tls
openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:prime256v1 -sha256 -days 1095 -nodes \
  -subj "/CN=Argos evaluation CA" \
  -addext "basicConstraints=critical,CA:TRUE" \
  -addext "keyUsage=critical,keyCertSign,cRLSign" \
  -keyout tls/ca.key -out tls/ca.crt
openssl req -newkey ec -pkeyopt ec_paramgen_curve:prime256v1 -sha256 -nodes -subj "/CN=$CERT_DNS" \
  -keyout tls/server.key -out tls/server.csr
printf 'subjectAltName=DNS:%s,IP:%s\nextendedKeyUsage=serverAuth\nkeyUsage=critical,digitalSignature\n' \
  "$CERT_DNS" "$CERT_IP" > tls/server.ext
openssl x509 -req -sha256 -days 365 -in tls/server.csr \
  -CA tls/ca.crt -CAkey tls/ca.key -CAcreateserial \
  -extfile tls/server.ext -out tls/server.crt
openssl verify -CAfile tls/ca.crt tls/server.crt
rm -f tls/server.csr tls/server.ext tls/ca.srl
```

마지막 `openssl verify`가 `tls/server.crt: OK`를 출력합니다. 만들어지는 파일:

- `tls/ca.crt`: CA 인증서. 모든 에이전트 호스트에 복사합니다(`server.tls.ca-file`,
  [9장](#9-첫-에이전트-연결)).
- `tls/ca.key`: CA 키. 서버에서 안전한 곳으로 옮기세요. 다음 서버 인증서를 발급할 때만 필요하며,
  서버는 이 파일을 읽지 않습니다.
- `tls/server.crt`, `tls/server.key`: 서버 인증서(1년 유효)와 키.

### 키를 서버에 넘기기

어느 방법이든 모드를 정하고 키를 서버의 사용자인 uid `10001`에게 넘기세요.

```sh
chmod 0644 tls/server.crt
chmod 0600 tls/server.key
sudo chown 10001:10001 tls/server.key
```

### 운영 환경의 인증서

CA를 2단으로 운영하세요. 키가 서버에 닿지 않는 오프라인 루트 CA(약 10년)와, 1년짜리 서버 인증서에
서명하는 발급용 중간 CA(약 3년)입니다. 에이전트는 중간 CA와 루트 CA 인증서를 PEM 파일 하나로
`server.tls.ca-file`에 받습니다. 폐기 확인(CRL, OCSP)은 하지 않습니다. 키가 유출되면 CA를 바꾸고 새
`server.tls.ca-file`을 에이전트에 배포하세요.

서버 인증서는 만료 전에 갱신합니다. 새 키도 처음 키처럼 암호화하지 않은 PKCS#8이어야 합니다(확인과
변환은 [방법 A](#방법-a-조직의-인증서)에 있습니다). `compose restart`는 `argos-compose.sh up`처럼 파일을
검사하지 않습니다. 그러니 다시 시작하기 전에 파일을 바꾸고 키를 다시 서버에 넘기세요. 이전 키는 uid
`10001` 소유이고 바꾼 파일은 root 소유일 수 있으므로 명령에 `sudo`를 씁니다.

```sh
sudo cp /path/to/new-server.crt tls/server.crt
sudo cp /path/to/new-server.key tls/server.key
sudo chmod 0644 tls/server.crt
sudo chmod 0600 tls/server.key
sudo chown 10001:10001 tls/server.key
```

다시 시작하기 전에 파일을 확인하세요.

```sh
ls -ln tls/
openssl x509 -noout -ext subjectAltName -enddate -in tls/server.crt
```

`ls -ln`에서 `server.key`는 `-rw-------`이고 소유자와 그룹이 `10001 10001`이어야 하며, `server.crt`는
`-rw-r--r--`이어야 합니다. `openssl x509`는 새 인증서의 이름과 새 만료일(`notAfter`)을 출력합니다. 그다음
서버를 다시 시작하고, 다시 healthy가 되는지(1분 정도 걸릴 수 있음)와 로그의 마지막 `gRPC collector` 줄이
`(TLS 1.2+, JDK provider)`를 포함하는지 확인하세요.

```sh
sudo ./argos-compose.sh compose restart server
sudo ./argos-compose.sh status
sudo ./argos-compose.sh logs --no-color server | grep "gRPC collector" | tail -n 1
```

서버가 기동하지 않으면 로그에 인증서나 키 파일과 문제가 나옵니다([문제 해결](#11-문제-해결)).

에이전트에서 인증서 검사를 끄지 마세요(`server.tls.insecure-skip-verify`). 수집기를 평문으로 운영하지도
마세요. 에이전트 토큰과 수집한 데이터 전체가 읽을 수 있는 상태로 네트워크를 지나갑니다.

## 5. 단계별 설치

### UI 주소 정하기

UI의 세션 쿠키는 `Secure`입니다. 브라우저는 평문 HTTP로는 `localhost`에만 이 쿠키를 보냅니다. 그래서
다른 컴퓨터에서 `http://<host>:8080`으로는 로그인할 수 없습니다. 사용자가 UI를 어떻게 열지 지금
정하세요. 그 주소가 1단계에서 주는 **공개 URL**입니다.

| 브라우저 위치 | 브라우저 주소이자 공개 URL |
|---|---|
| Argos 호스트 자체 | `http://localhost:8080` |
| 작업용 컴퓨터, SSH 터널 경유: `ssh -N -L 8080:127.0.0.1:8080 <user>@<host>` | `http://localhost:8080` |
| 어디서나, HTTPS 리버스 프록시 경유([8장](#8-리버스-프록시443-https)) | `https://argos.example.com` |

공개 URL은 브라우저 주소 표시줄의 주소(스킴, 호스트, 포트)와 정확히 같아야 합니다. 서버는 다른
출처에서 온 변경 요청과 WebSocket을 거부하고, 링크에 이 URL을 씁니다.

`.env`에 `ARGOS_SECURITY_COOKIE_SECURE=false`를 두면 서버가 세션 쿠키를 평문 HTTP로 어느 호스트에나
보냅니다. 신뢰할 수 있는 네트워크에서 잠깐 평가할 때만 쓰세요. 이때 세션 쿠키는 읽을 수 있는 상태로
네트워크를 지나가고, 서버는 기동할 때마다 경고를 기록합니다.

### 1단계: 설정과 비밀값 생성

```sh
sudo ./argos-env-init.sh --public-url http://localhost:8080
```

위 표에서 정한 공개 URL을 쓰세요. `--public-url`이 없으면 스크립트는 `http://<host name>:8080`을
쓰는데, 이 주소는 `ARGOS_SECURITY_COOKIE_SECURE=false`와 함께일 때만 브라우저에서 동작합니다.

공개 URL이 평문 `http://`이고 호스트가 `localhost`, `127.0.0.1`, `[::1]`이 아니면, 스크립트는 다른
컴퓨터에서 로그인이 실패한다는 경고를 위 표의 선택지와 `ARGOS_SECURITY_COOKIE_SECURE=false`와 함께
출력합니다. 오류가 아니라 경고이므로 종료 코드는 0입니다. `.env`의 `ARGOS_WEB_PUBLIC_URL`(또는 경고가
말하는 다른 설정)을 바꾸고 `sudo ./argos-compose.sh up`을 다시 실행하세요. 그래도 그런 주소로 로그인하면
로그인 화면이 브라우저가 세션 쿠키를 저장하지 않았다고 알려 줍니다.

이 스크립트는 다음을 합니다.

1. 사전 조건(Docker 24 이상, Compose v2.24 이상, `openssl`, 디스크 여유 공간 20 GB 이상, Docker에 보이는
   메모리 7168 MiB 이상, 즉 최소 사양 8 GB([7.1절](#71-메모리)), Linux Docker Engine에서는 root)을
   검사하고, 빠진 것이 있으면 그 목록과 함께 종료 코드 2로 멈춥니다.
2. Docker가 보고하는 메모리로 컨테이너 메모리 상한과 서버 힙을 계산합니다([7.1절](#71-메모리)).
3. `secrets/`(모드 `0700`)를 만들고 비밀번호나 키마다 파일 하나(모드 `0600`, uid `10001` 소유)를
   씁니다. 각각 암호학적으로 안전한 난수 생성기로 만듭니다. PostgreSQL 계정, ClickHouse 계정 세 개,
   마스터 키, 메트릭 토큰입니다.
4. ClickHouse 비밀번호의 SHA-256 해시를 `secrets/clickhouse.env`(`0600`)에 씁니다. ClickHouse는 비밀번호
   대신 이 해시를 읽습니다.
5. 비밀값 없이 설정만 `.env`(`0600`)에 씁니다.
6. 무엇이든 해시하기 전에, 비어 있거나 12자보다 짧거나 `changeme`, `password`, `argos`, `admin`,
   `postgres`, `clickhouse`, `123456`, `qwerty`, `default` 중 하나인 비밀값을 거부합니다(종료 코드 3.
   `secrets/`에 직접 놓은 파일에 해당합니다).
7. 메모리 계획과 다음 명령을 출력하고, 해당하면 위의 공개 URL 경고도 출력합니다.

기존 파일은 그대로 둡니다. `--force`를 주면 `.env`와 모든 비밀값을 바꿉니다([10장](#10-중지-재시작-제거)의
경고를 보세요). 비밀값은 출력하지 않습니다. 모든 옵션은 `--help`에 있습니다.

지금 `secrets/`(무엇보다 `secrets/master_key`)와 `tls/`를 이 호스트 밖에 백업하세요. 이 파일들이 없으면
데이터 볼륨을 다시 쓸 수 없습니다.

### 2단계: 스택 시작

```sh
sudo ./argos-compose.sh up
```

`up`은 `.env`, `secrets/`, `tls/`(존재, 모드, 소유자)와 서버 이미지가 있는지 검사한 뒤 PostgreSQL과
ClickHouse를 시작하고, 둘 다 healthy가 될 때까지 기다린 다음 서버를 시작해 healthy가 될 때까지
기다립니다(최대 600초, `--timeout`으로 바꿈). 첫 기동 때 서버가 데이터베이스 스키마를 만들며 1분 정도
걸립니다. 실패하면 [문제 해결](#11-문제-해결)을 보세요.

### 3단계: 상태 확인

```sh
sudo ./argos-compose.sh status
```

세 서비스가 모두 `healthy`로 나옵니다. 서버의 health 검사는 컨테이너 안에서
`http://127.0.0.1:8081/actuator/health`를 호출합니다. 서버 로그:

```sh
sudo ./argos-compose.sh logs server
```

### 4단계: 스모크 테스트 실행

```sh
sudo ./smoke.sh
```

`smoke.sh`는 설치된 상태를 검사하며 아무것도 바꾸지 않습니다. 설치 마법사는 열린 채로 남습니다.
검사하는 것:

- `.env`에 비밀값이 없고 모드가 `0600`이며, `secrets/`가 `0700`이고 그 안의 파일이 `0600`이며 비었거나
  짧거나 기본 비밀번호인 파일이 없는지
- 게시 포트가 정확히 `8080`과 `9100`(관리 포트를 게시했으면 `127.0.0.1:8081`, 리버스 프록시를 쓰면
  `443` 추가)이고 데이터베이스 포트가 게시되지 않았는지
- ClickHouse와 PostgreSQL이 `image.properties`에 고정된 이미지로 실행되는지
- `back` 네트워크가 internal이고 데이터베이스가 그 네트워크에만 연결되었는지
- 서버가 uid `10001`로 실행되고 `/data`에 쓸 수 있으며, 세 서비스가 모두 healthy인지
- 포트 `8080`에서 `/actuator/*`가 `404`인지
- 설치 마법사가 설정 토큰이 없거나 틀린 요청을 거부하는지(`403`), 설치를 마친 뒤라면 `404`로 답하는지

모두 통과하면 `smoke: passed`와 종료 코드 0으로 끝나고, 처음 위반한 항목에서 `smoke: FAIL`과 종료 코드
1로 멈춥니다. `NOT VERIFIABLE` 줄은 이 호스트에서 할 수 없는 검사를 알려 줍니다(예: POSIX 모드가 없는
파일 시스템의 파일 모드).

### 5단계: 첫 관리자 만들기

첫 기동 직후 서버에는 사용자가 없습니다. 서버는 설치 마법사를 보호하는 1회용 **설정 토큰**을 발급합니다.

- 서버 데이터 디렉터리의 `setup-token` 파일, 즉 `argos-data` 볼륨의 `/data/setup-token`(모드 `0600`,
  uid `10001`)과 서버 로그 한 줄에 기록됩니다. 파일에는 항상 현재 토큰이 있으니 파일에서 읽으세요.

  ```sh
  sudo ./argos-compose.sh compose exec server cat /data/setup-token
  ```

  또는 서버 로그에서 그런 줄 중 마지막 줄을 읽습니다(컨테이너 로그에는 이전 기동의 줄도 남아 있습니다).

  ```sh
  sudo ./argos-compose.sh logs --no-color server | grep "Setup token" | tail -n 1
  ```

  이 줄은 JSON입니다. `message` 끝의 콜론 뒤 값(닫는 따옴표 앞까지)이 토큰입니다(일부 생략, 토큰은
  가림).

  ```text
  server-1  | {"ts":"2026-11-15T09:00:12.345Z","level":"WARN","logger":"io.argos.server.auth.setup.SetupTokenBootstrap",...,"message":"Setup token (one use, valid for 30 minutes until 2026-11-15T09:30:12.341Z; same value in /data/setup-token): XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX"}
  ```

- 서버 기동 뒤 30분 동안, 한 번만 유효합니다. 만료되면 서버를 다시 시작해 새 토큰을 받으세요. 재시작하면
  이전 토큰은 무효가 되니 파일에서 새 토큰을 다시 읽으세요.

  ```sh
  sudo ./argos-compose.sh compose restart server
  ```

`<공개 URL>/setup`에서 설치 마법사를 열고 단계를 진행합니다.

1. **설정 토큰**: 토큰을 붙여 넣습니다.
2. **관리자**: 사용자명(1~64자: 영문자, 숫자, `. _ @ + -`)과 비밀번호(12~128자, 흔한 비밀번호와
   사용자명, "argos"가 들어간 비밀번호는 거부됨)를 정합니다. 비밀번호는 두 번 입력합니다.
3. **IP 주소**: 모니터링하는 요청의 클라이언트 IP 주소를 어떻게 저장할지 고릅니다. 반드시 골라야 하며
   **마스킹(권장)**이 미리 선택되어 있습니다. 마스킹은 네트워크 부분만 남깁니다(`203.0.113.0`). **전체
   주소**는 개인정보 처리의 법적 근거가 있어야 하고, **수집 안 함**은 주소를 저장하지 않습니다.
4. **완료**: 관리자가 만들어지고 로그인됩니다. 이 화면은 첫 애플리케이션을 연결하는 방법을 알려 줍니다.
   `argos-compose.sh`의 `token create` 명령으로 에이전트 토큰을 만들고 애플리케이션을 에이전트와 함께
   시작합니다([9장](#9-첫-에이전트-연결)). 애플리케이션은 토큰을 만들면 바로 UI 위쪽의 애플리케이션
   선택기에 나옵니다. 에이전트가 연결됐는지 확인하는 방법은 [9장](#연결-확인)에 있습니다.

그러면 토큰은 무효가 되고 파일은 지워집니다. 이후 마법사는 닫힙니다. 설치 API는 `404`로 답하고
`/setup`은 "설치가 이미 끝났습니다"를 보여 줍니다.

이 버전에서는 유일한 관리자의 비밀번호를 잃으면 `down --volumes`로 제거한 뒤 다시 설치하는 방법뿐이며,
이때 모든 데이터가 지워집니다([10장](#10-중지-재시작-제거)). 비밀번호를 안전하게 보관하세요.

## 6. 무인 설치

스크립트로 설치할 때는 마법사 없이 관리자를 만듭니다. IP 수집 모드는 필수입니다.

```sh
sudo ./argos-env-init.sh --public-url https://argos.example.com --unattended --ip-mode masked
```

| `--ip-mode` | 의미 |
|---|---|
| `masked` | 네트워크 부분만 저장(권장) |
| `full` | 전체 주소 저장(법적 근거 필요) |
| `off` | 클라이언트 주소를 저장하지 않음 |

`--unattended`를 주면 스크립트가 `secrets/admin_password`(무작위 24자)도 만들고, `.env`에
`ARGOS_UNATTENDED=true`, `ARGOS_IP_MODE`, `ARGOS_ADMIN_USERNAME`(기본 `admin`, 또는 `--admin-user`)을
씁니다. 그다음 평소처럼 시작합니다(`sudo ./argos-compose.sh up`, `sudo ./smoke.sh`).
설치 직후 아직 아무도 비밀번호를 바꾸기 전이라면 `sudo ./smoke.sh --check-admin-login`이
`secrets/admin_password`로 로그인해 비밀번호 변경이 필요한지 확인하고 다시 로그아웃합니다. 비밀번호를
바꾼 뒤에는 실패합니다.

서버는 첫 기동 때 관리자를 만듭니다. 설정 토큰은 발급되지 않고 마법사는 `404`로 답합니다. 그
사용자명과 `secrets/admin_password`의 비밀번호로 로그인하세요.

```sh
sudo cat secrets/admin_password
```

UI가 먼저 새 비밀번호를 정하라고 합니다. 그 뒤로 이 파일로는 접근할 수 없으며, 관리자가 있으면 서버는
이 파일을 다시 읽지 않습니다.

비밀번호 파일이 없거나 읽을 수 없거나, 비밀번호가 비밀번호 정책에 맞지 않거나, `ARGOS_IP_MODE`가
없으면 서버는 문제를 밝히는 메시지와 함께 기동을 거부합니다. 그룹이나 다른 사용자가 비밀번호 파일을
읽을 수 있으면 경고를 기록합니다.

이 버전에서는 유일한 관리자의 비밀번호를 잃으면 `down --volumes`로 제거한 뒤 다시 설치하는 방법뿐이며,
이때 모든 데이터가 지워집니다([10장](#10-중지-재시작-제거)). 새 비밀번호를 안전하게 보관하세요.

## 7. 설정

`.env`는 `argos-env-init.sh`가 씁니다. `.env.example`에 모든 변수가 한 줄 설명과 함께 있습니다. 설정을
바꾸려면 `.env`를 (root로) 고치고 `sudo ./argos-compose.sh up`을 다시 실행하세요. 이 용도로
`argos-env-init.sh --force`를 쓰지 마세요. 비밀값까지 바뀝니다.

### 7.1 메모리

| 변수 | 의미 |
|---|---|
| `ARGOS_SERVER_MEM` | 서버 컨테이너의 메모리 상한 |
| `ARGOS_SERVER_JAVA_OPTS` | 서버 JVM의 힙(`-Xms`, `-Xmx`). 나머지 JVM 옵션은 이미지가 더합니다 |
| `ARGOS_CH_MEM` | ClickHouse 컨테이너의 메모리 상한 |
| `ARGOS_CH_MAX_SERVER_MEMORY_USAGE` | ClickHouse 자체 메모리 상한, 바이트(`max_server_memory_usage`) |
| `ARGOS_CH_WRITER_MAX_MEMORY_USAGE`, `ARGOS_CH_READER_MAX_MEMORY_USAGE` | insert 하나와 쿼리 하나의 메모리 상한, 바이트 |
| `ARGOS_PG_MEM` | PostgreSQL 컨테이너의 메모리 상한 |
| `ARGOS_PG_SHARED_BUFFERS`, `ARGOS_PG_EFFECTIVE_CACHE_SIZE` | PostgreSQL `shared_buffers`와 `effective_cache_size` |

스크립트는 Docker가 보고하는 메모리로 이 값을 계산합니다(`--ram-mb`로 바꿀 수 있음). 예산은 컨테이너
상한으로 셉니다.

| 항목 | 규칙 | 하한 | 상한 |
|---|---|---|---|
| 서버 힙 | 0.25 x RAM | 2 GiB | 16 GiB |
| 서버 컨테이너 | 힙 + 1 GiB | | |
| PostgreSQL `shared_buffers` | 0.03 x RAM | 256 MB | 4 GB |
| PostgreSQL 컨테이너 | 3 x `shared_buffers` | 1 GiB | |
| ClickHouse 컨테이너 | 0.47 x RAM / 0.9(약 0.52 x RAM) | 3 GiB | 72818 MiB |
| ClickHouse 상한(`max_server_memory_usage`) | 0.9 x ClickHouse 컨테이너 | | |
| 서버 컨테이너 + PostgreSQL 컨테이너 + ClickHouse 컨테이너 | 최대 0.9 x RAM, ClickHouse 컨테이너부터 3 GiB까지 줄임 | | |
| 최소 사양 구간(Docker에 보이는 메모리 7168~7964 MiB) | ClickHouse 컨테이너를 2.5 GiB까지, 그다음 서버 힙을 1.5 GiB까지 줄임 | ClickHouse 2.5 GiB, 힙 1.5 GiB | |
| insert 하나 / 쿼리 하나 | ClickHouse 상한의 4/15 / 2/5 | | |

Docker에 보이는 메모리가 7965 MiB 이상이면 이 규칙이 늘 들어맞습니다(서버 3072 + PostgreSQL 1024 +
ClickHouse 3072 MiB = 7168 MiB가 0.9 x RAM 이하). 8 GB 호스트는 Docker에 그보다 적은 약 7.5~7.9 GiB로
보입니다. 7168~7964 MiB에서는 스크립트가 **최소 사양 구간**을 쓰고 경고를 출력합니다. ClickHouse
컨테이너를 2.5 GiB까지 줄이고(상한은 그대로 컨테이너의 0.9배), 그것으로 모자랄 때만 서버 힙을 1.5 GiB까지
줄입니다(서버 컨테이너는 그대로 힙 + 1 GiB). 이런 호스트는 평가나 작은 부하에만 쓰고, 부하가 커지기 전에
메모리를 늘리세요. 7168 MiB 미만이면 스크립트는 종료 코드 2로 멈춥니다.

메모리 크기 다섯 가지에서의 결과(MiB):

| RAM | 서버 컨테이너 / 힙 | ClickHouse 컨테이너 / 상한 | PostgreSQL 컨테이너 / `shared_buffers` | 컨테이너 합계 / 예산 |
|---|---|---|---|---|
| 7168, 최소(구간) | 2867 / 1843 | 2560 / 2304 | 1024 / 256 | 6451 / 6451 |
| 7680, 흔한 8 GB 호스트(구간) | 3072 / 2048 | 2816 / 2534 | 1024 / 256 | 6912 / 6912 |
| 8 GiB (8192) | 3072 / 2048 | 3276 / 2948 | 1024 / 256 | 7372 / 7372 |
| 16 GiB (16384) | 5120 / 4096 | 8152 / 7336 | 1473 / 491 | 14745 / 14745 |
| 32 GiB (32768) | 9216 / 8192 | 17112 / 15400 | 2949 / 983 | 29277 / 29491 |

"16 GB" 호스트는 Docker에 그보다 조금 적은 메모리를 보여 주므로 값이 약간 낮게 나옵니다. 스크립트가
계획을 출력합니다. ClickHouse 자체 상한은 컨테이너 상한의 0.9배이므로, 커널이 컨테이너를 죽이기 전에
ClickHouse가 너무 큰 쿼리를 거부합니다. 16 GB보다 적으면 스크립트가 평가용 구성이라고 경고합니다.
값을 직접 바꿀 때는 이 관계를 지키세요.

서버 힙은 16 GB 호스트에서 4 GiB입니다. 컨테이너는 힙 위로 1 GiB를 남겨 직접 메모리(512 MiB),
메타스페이스, 스레드에 씁니다. 힙이 4 GiB이면 수집기의 메시지 크기 상한
(`argos.collector.max-message-bytes`, 기본 4 MiB)을 16 MiB 이하로 두세요.

### 7.2 관리 포트

서버는 관리 포트 `8081`에서 health 검사와 Prometheus 수집에 답합니다. 기본으로는 이 포트를 게시하지
않습니다. `.env`에

```sh
ARGOS_MGMT_EXPOSE=true
```

를 두면 `sudo ./argos-compose.sh up`이 같은 호스트의 Prometheus나 health 검사를 위해 호스트의
`127.0.0.1:8081`에만 게시합니다. `/actuator/health`는 열려 있고 상세를 보여 주지 않습니다.
`/actuator/prometheus`와 `/actuator/info`에는 `secrets/metrics_token`의 베어러 토큰
(`Authorization: Bearer ...`)과 `ARGOS_METRICS_ALLOW_CIDRS` 안의 클라이언트 주소(기본: 루프백과 Docker
사설 주소 대역)가 필요합니다. `/actuator/env`는 없습니다.

### 7.3 그 밖의 변수

| 변수 | 기본값 | 의미 |
|---|---|---|
| `ARGOS_SERVER_IMAGE` | 로컬 빌드 `argos-server:0.0.0-SNAPSHOT` | 서버 이미지(`--server-image`) |
| `ARGOS_WEB_PUBLIC_URL` | `http://<host name>:8080` | 브라우저에서 쓰는 UI 주소(`--public-url`, [5장](#ui-주소-정하기)) |
| `ARGOS_HTTP_BIND` | `0.0.0.0` | `8080`을 게시할 호스트 주소(`--http-bind`). 같은 호스트의 리버스 프록시 뒤라면 `127.0.0.1` |
| `ARGOS_GRPC_BIND` | `0.0.0.0` | `9100`을 게시할 호스트 주소(`--grpc-bind`) |
| `ARGOS_MGMT_EXPOSE` | `false` | 관리 포트를 `127.0.0.1`에 게시([7.2절](#72-관리-포트)) |
| `ARGOS_PROXY`, `ARGOS_PROXY_BIND` | `false`, `0.0.0.0` | `compose.proxy.yaml`의 리버스 프록시 시작, 그 포트 `443`의 호스트 주소([8장](#8-리버스-프록시443-https)) |
| `ARGOS_PROXY_SUBNET`, `ARGOS_PROXY_IP` | `172.16.240.0/24`, `172.16.240.254` | `front` 네트워크의 서브넷과 그 안의 프록시 고정 주소. 서버는 이 주소에서 온 `X-Forwarded-*` 헤더만 받아들입니다([8장](#8-리버스-프록시443-https)) |
| `ARGOS_UNATTENDED`, `ARGOS_IP_MODE`, `ARGOS_ADMIN_USERNAME` | `false` | 무인 설치([6장](#6-무인-설치)) |
| `ARGOS_START_PERIOD` | `60s` | 기동 후 서버 health 검사 실패를 세기 전에 기다리는 시간 |
| `ARGOS_LOG_LEVEL` | `INFO` | 서버 로그 레벨 |
| `ARGOS_TZ_DISPLAY` | `UTC` | 알림 메시지에 쓰는 시간대(데이터는 항상 UTC로 저장) |
| `ARGOS_RETENTION_XLOG_DAYS`, `ARGOS_RETENTION_PROFILE_DAYS`, `ARGOS_RETENTION_RAW_STAT_DAYS`, `ARGOS_RETENTION_METRIC_DAYS`, `ARGOS_RETENTION_ROLLUP_1M_DAYS`, `ARGOS_RETENTION_ROLLUP_1H_DAYS` | 30, 7, 14, 14, 90, 365 | 보존 일수. 이 버전에서는 `ARGOS_RETENTION_RAW_STAT_DAYS`와 `ARGOS_RETENTION_METRIC_DAYS`가 같아야 하며, 다르면 서버가 기동을 거부합니다 |
| `ARGOS_METRICS_ALLOW_CIDRS` | 루프백과 Docker 사설 대역 | 관리 포트에 허용하는 클라이언트 |
| `ARGOS_SECURITY_COOKIE_SECURE` | `true` | `false`는 평문 HTTP로 잠깐 평가할 때만([5장](#ui-주소-정하기)) |

비밀값은 `.env`에 넣지 않습니다. `secrets/`에 두고 파일로 컨테이너에 전달합니다.

## 8. 리버스 프록시(443 HTTPS)

다른 컴퓨터에서 접속하려면 포트 443에서 HTTPS를 종단하는 리버스 프록시 뒤에 UI를 두세요.
`compose.proxy.yaml`은 스택에 nginx 프록시(이미지 다이제스트 고정, 설정 `config/proxy/nginx.conf`)를
더합니다. 예시입니다. 이 버전은 Compose 설정만 검사하며, 실제 브라우저 앞에서의 동작은 검사하지
않습니다.

1. 사용자가 브라우저에 입력할 이름으로 발급한 프록시 인증서(체인 포함)와 암호화하지 않은 키를 놓습니다.

   | 파일 | 내용 |
   |---|---|
   | `tls/proxy.crt` | 인증서와 중간 CA 인증서(PEM) |
   | `tls/proxy.key` | 개인 키(PEM), 모드 `0600` |

2. `.env`에 다음을 둡니다.

   ```sh
   ARGOS_PROXY=true
   ARGOS_WEB_PUBLIC_URL=https://argos.example.com
   ARGOS_HTTP_BIND=127.0.0.1
   ```

   `ARGOS_PROXY=true`이면 `argos-compose.sh`가 `compose.proxy.yaml`을 더합니다. 이때
   `ARGOS_HTTP_BIND=127.0.0.1`은 필수입니다. 포트 `8080`이 이 호스트에서만 닿아야 어떤 클라이언트도
   프록시를 건너뛰지 못합니다(다른 값이면 `argos-compose.sh up`이 기동을 거부하고 `smoke.sh`가
   실패합니다). `ARGOS_PROXY_BIND`(기본 `0.0.0.0`)는 `443`의 호스트 주소입니다.

3. `sudo ./argos-compose.sh up`을 실행합니다. 이미 실행 중인 설치라면 먼저
   `sudo ./argos-compose.sh down`을 실행하세요(`--volumes` 없이, 데이터는 남습니다). 그래야 `front`
   네트워크가 아래의 고정 서브넷으로 다시 만들어집니다.

이 오버레이는 서버가 프록시의 `X-Forwarded-*` 헤더를 신뢰하게도 합니다. 그래서 클라이언트 주소(레이트
리밋, 감사 로그)와 `https` 스킴을 프록시에서 받습니다.

- 프록시는 `front` 네트워크에서 고정 주소 `ARGOS_PROXY_IP`(기본 `172.16.240.254`)를 쓰며, 이 주소는
  서브넷 `ARGOS_PROXY_SUBNET`(기본 `172.16.240.0/24`) 안에 있습니다. 서버는 이 주소에서 온 헤더만
  받아들입니다. LAN이 이 대역을 쓰면 `.env`에서 두 변수를 비어 있는 대역으로 바꾸세요.
  `argos-compose.sh`가 `ARGOS_PROXY_IP`에서 서버 설정을 만듭니다.
- 프록시는 `X-Forwarded-For`를 자신에게 접속한 클라이언트의 주소로 덮어씁니다. 그래서 클라이언트가
  직접 보낸 값은 남지 않습니다.

다른 프록시를 쓴다면 다음을 지켜야 합니다.

- 포트 `8080`에 닿는 유일한 길이어야 합니다. 프록시가 같은 호스트에 있으면 `ARGOS_HTTP_BIND=127.0.0.1`을
  둡니다. 프록시가 다른 호스트에 있을 때 ufw나 firewalld 같은 호스트 방화벽은 이 포트를 막지 못합니다.
  Docker가 게시한 포트는 방화벽의 INPUT 규칙이 패킷을 보기 전에 컨테이너로 전달되기 때문입니다(Docker
  문서: [Packet filtering and firewalls](https://docs.docker.com/engine/network/packet-filtering-firewalls/)).
  대신 다음 중 하나를 쓰세요.
  - `ARGOS_HTTP_BIND`를 프록시만 닿는 Argos 호스트의 주소로 둡니다. 예를 들어 프록시와 Argos 호스트
    사이 네트워크의 주소입니다(`smoke.sh`가 `8080`이 그 주소에만 게시됐는지 검사합니다).
  - 또는 `DOCKER-USER` 체인([Docker with iptables](https://docs.docker.com/engine/network/firewall-iptables/))이나
    Argos 호스트 앞단의 네트워크 방화벽에서 포트 `8080`을 프록시 주소에만 허용합니다.
- 운영자의 인증서로 TLS를 종단하고 서버의 포트 `8080`으로 전달합니다.
- 서버가 HTTPS임을 알 수 있도록 `X-Forwarded-Proto`를 전달합니다.
- `X-Forwarded-For`를 프록시가 본 클라이언트 주소로 설정합니다. 클라이언트가 보낸 값에 덧붙이지 말고
  덮어씁니다(nginx라면 `proxy_set_header X-Forwarded-For $remote_addr;`).
- `/ws`의 WebSocket 업그레이드를 통과시킵니다(UI 실시간 갱신).
- 요청 본문을 10 MB까지 받고, 유휴 연결을 65초 이상 유지합니다.
- 보안 헤더를 더하지 않습니다. 서버가 붙입니다.

그리고 `compose.override.yaml`에서 서버에 두 설정을 함께 주세요.

```yaml
services:
  server:
    environment:
      SERVER_FORWARD_HEADERS_STRATEGY: native
      # The proxy's address as the server sees it, as a regular expression (dots escaped).
      SERVER_TOMCAT_REMOTEIP_INTERNALPROXIES: '192\.0\.2\.10'
```

`SERVER_TOMCAT_REMOTEIP_INTERNALPROXIES`가 없으면 서버는 모든 사설 주소와 루프백 주소에서 온 이 헤더를
신뢰합니다. 다른 호스트의 프록시는 자기 주소로 보입니다. 같은 호스트에서 `127.0.0.1:8080`으로 접속하는
프록시는 `front` 네트워크의 게이트웨이 주소로 보입니다. Docker가 게시된 포트를 그 주소에서 전달하기
때문입니다(`127.0.0.1:8080`으로 들어오는 다른 로컬 접속도 모두 그 주소에서 옵니다). 이 주소는
`docker network inspect argos_front --format '{{range .IPAM.Config}}{{.Gateway}}{{end}}'`로 확인합니다
(`argos`는 사용하는 `COMPOSE_PROJECT_NAME`으로 바꾸세요). `front`의 서브넷은 Docker가 네트워크를 만들 때
고르므로 `down` 뒤에 게이트웨이가 바뀔 수 있습니다. 주소와 정규식이 계속 맞도록 `compose.override.yaml`에서
서브넷을 고정하세요(`networks:` / `front:` / `ipam:` / `config:` / `- subnet: <대역>`).

gRPC(`9100`)는 프록시를 거치지 않습니다. 에이전트는 서버에 직접 접속하고 서버가 TLS를 직접 종단합니다.

## 9. 첫 에이전트 연결

### 에이전트 토큰 만들기

애플리케이션마다 에이전트 토큰이 필요합니다. 스택이 실행 중일 때 Argos 호스트에서 만드세요(서버가
healthy여야 합니다. 이 명령은 서버 컨테이너 안에서 서버의 토큰 서비스 `argos-admin token create`를
실행합니다).

```sh
sudo ./argos-compose.sh token create --app order-api --env prod
```

| 옵션 | 의미 |
|---|---|
| `--app <name>` | 에이전트가 `app.name`으로 보고하는 애플리케이션 이름(`A-Z a-z 0-9 . _ -` 1~64자). 없으면 만듦 |
| `--env <env>` | `app.env`와 같은 환경(`prod`, `staging` 등, 최대 32자) |
| `--expires-days <N>` | 선택: N일 뒤 토큰 만료(1~3650, 기본 365). 서버 기본 알림 규칙(인증서·토큰 만료)이 만료 전에 경고합니다 |

명령은 토큰을 에이전트 설정 예시와 함께 **한 번만** 출력합니다. Argos는 SHA-256 해시만 보관하므로,
토큰을 저장할 때까지([토큰 저장](#토큰-저장)) 출력을 화면에 남겨 두세요. 애플리케이션 하나에 활성
토큰은 최대 5개이며, 여섯 번째는 종료 코드 4로 거부됩니다. 토큰은 자기 애플리케이션과, 그 에이전트가
등록한 같은 이름의 다른 환경(최대 8개)에만 씁니다. 발급한 토큰은 모두 감사 로그에 기록됩니다.

### 에이전트 jar 준비

[3장](#3-파일과-서버-이미지-준비)의 빌드 장비에서, 저장소 루트에서 에이전트 jar를 빌드합니다. 서버
이미지처럼 이 빌드도 [시간 예산](#13-시간-예산과-테스터-체크리스트)에서 따로 기록합니다.

```sh
./gradlew :agent:agent-dist:agentJar
```

`agent/agent-dist/build/libs/argos-agent-<version>.jar`(예: `argos-agent-0.0.0-SNAPSHOT.jar`)에
만들어집니다. 이 파일과 [4장](#4-grpc-인증서-준비)의 CA 인증서(평가용 CA라면 `tls/ca.crt`)를
애플리케이션 호스트로 복사하세요. 예를 들어 `scp`로 홈 디렉터리에 둡니다. 이후 명령은 그 디렉터리에서
실행합니다.

### 에이전트 디렉터리 준비

애플리케이션 호스트에서 에이전트 파일은 `/opt/argos-agent`에 두며, 애플리케이션을 실행하는 사용자가
소유합니다. 그 사용자는 `/opt`에 디렉터리를 만들 수 없으므로, 디렉터리와 로그 디렉터리를 `sudo`로
만드세요. 두 변수를 애플리케이션을 실행하는 사용자와 그룹으로 바꾸세요.

```sh
APP_USER=app
APP_GROUP=app
sudo install -d -m 0750 -o "$APP_USER" -g "$APP_GROUP" /opt/argos-agent
sudo install -d -m 0700 -o "$APP_USER" -g "$APP_GROUP" /opt/argos-agent/logs
```

에이전트 jar와 CA 인증서를 같은 사용자 소유로 설치합니다.

```sh
sudo install -m 0644 -o "$APP_USER" -g "$APP_GROUP" argos-agent-0.0.0-SNAPSHOT.jar /opt/argos-agent/argos-agent.jar
sudo install -m 0644 -o "$APP_USER" -g "$APP_GROUP" ca.crt /opt/argos-agent/argos-ca.crt
```

### 에이전트 설정

현재 디렉터리의 `argos-agent.yml` 파일에 에이전트 설정을 씁니다. Argos 서버 주소와, 토큰의
애플리케이션 이름과 환경을 넣으세요.

```yaml
server:
  address: argos.example.com:9100
  tls:
    ca-file: /opt/argos-agent/argos-ca.crt
  token-file: /opt/argos-agent/token
app:
  name: order-api
  env: prod
log:
  dir: /opt/argos-agent/logs
```

모드 `0640`, 애플리케이션 실행 사용자 소유로 설치합니다.

```sh
sudo install -m 0640 -o "$APP_USER" -g "$APP_GROUP" argos-agent.yml /opt/argos-agent/argos-agent.yml
```

`server.address`는 서버 인증서에 있는 이름이나 주소여야 합니다. `log.dir`은 에이전트가 로그를 쓰는
디렉터리입니다(없으면 에이전트 jar 옆의 `../logs`, 거기에 쓸 수 없으면 JVM 임시 디렉터리 아래).
에이전트 설정 전체는 [docs/agent-config.md](../agent-config.md)에 있습니다.

### 토큰 저장

애플리케이션 사용자로, 명령줄이나 셸 기록에 토큰을 남기지 않고, 그 사용자만 읽을 수 있는 파일에 토큰을
쓰세요. 명령을 실행하고 `token create` 출력의 토큰을 붙여 넣은 뒤 Enter, Ctrl-D를 누르면 됩니다.

```sh
sudo -u "$APP_USER" sh -c 'umask 077 && cat > /opt/argos-agent/token'
```

그러면 `/opt/argos-agent/token`은 모드 `0600`이고 애플리케이션 사용자의 소유입니다.

### 에이전트를 붙여 애플리케이션 시작

JVM 옵션 하나를 더해 애플리케이션을 시작하세요. Java 8 애플리케이션이 TLS를 쓰려면 8u252 이상이
필요합니다.

```sh
-javaagent:/opt/argos-agent/argos-agent.jar=config=/opt/argos-agent/argos-agent.yml
```

### 연결 확인

1. 에이전트 로그에 서버와의 세션이 기록됩니다.

   ```sh
   sudo grep "collector session established" /opt/argos-agent/logs/argos-agent.log
   ```

2. 애플리케이션에 요청을 몇 번 보냅니다. 그다음 Argos에 로그인한 브라우저에서
   `<공개 URL>/api/v1/apps?q=order-api`(애플리케이션 이름으로 바꿈)를 엽니다. 응답의 애플리케이션에서
   `instances_up`이 `1` 이상이면 서버가 그 에이전트와 살아 있는 세션을 갖고 있다는 뜻입니다.
   애플리케이션 이름이 보이는 것만으로는 아무것도 증명하지 않습니다. `token create`가 에이전트가
   접속하기 전에 애플리케이션을 등록하기 때문입니다(그때 `instances_up`은 `0`). 같은 이유로 UI 위쪽의
   애플리케이션 선택기도 토큰을 만든 때부터 그 애플리케이션을 보여 줍니다. 페이지가 `401`로
   답하면 UI를 열고(로그인을 요구하면 다시 로그인) 페이지를 새로 고치세요. 애플리케이션과 인스턴스를
   보여 주는 UI 화면은 이 버전에 없습니다([12장](#12-이-버전에-없는-기능)).

## 10. 중지, 재시작, 제거

| 작업 | 명령 | 데이터 |
|---|---|---|
| 중지 | `sudo ./argos-compose.sh down` | 볼륨에 남음 |
| 다시 시작 | `sudo ./argos-compose.sh up` | |
| 서버만 다시 시작 | `sudo ./argos-compose.sh compose restart server` | |
| 제거, 데이터 전부 삭제 | `sudo ./argos-compose.sh down --volumes` | **삭제됨**: 모니터링 데이터, 사용자, 앱, 에이전트 토큰, 감사 로그 |

`down --volumes` 뒤에 그 파일들로 다시 설치하지 않을 거라면 `secrets/`, `tls/`, `.env`도 지우세요.

볼륨을 유지하려는 설치에서는 `argos-env-init.sh --force`를 실행하지 마세요. 새 데이터베이스 비밀번호를
만들어, 볼륨에 저장된 계정과 맞지 않게 됩니다.

## 11. 문제 해결

| 증상 | 원인과 해결 |
|---|---|
| `argos-env-init.sh`나 `argos-compose.sh`가 종료 코드 2로 끝남 | 사전 조건이 빠졌거나 옵션이 틀렸습니다. 메시지에 목록이 있습니다(예: Linux Docker Engine에서 root가 아님, Compose가 v2.24보다 오래됨, 디스크 여유 공간이 20 GB 미만, Docker에 보이는 메모리가 7168 MiB 미만([7.1절](#71-메모리)), `tls/server.key` 없음, 모드나 소유자가 틀림) |
| `argos-env-init.sh`가 종료 코드 3으로 끝남 | 비밀값이 비었거나 12자보다 짧거나 금지 목록에 있습니다. 생성된 비밀값은 그렇지 않습니다. `secrets/`에 직접 놓은 파일을 바꾸거나 지우세요 |
| `up`이 `port is already allocated`나 `address already in use`로 실패 | 다른 프로그램이 `8080`이나 `9100`을 씁니다. `sudo ss -ltnp`로 찾아 멈추거나, Argos를 다른 호스트 주소에 게시하세요(`ARGOS_HTTP_BIND`, `ARGOS_GRPC_BIND`) |
| 스택이 healthy가 되지 않음 | `sudo ./argos-compose.sh logs server`(와 `clickhouse`, `postgres`)를 읽으세요. 첫 기동은 스키마를 만듭니다. 디스크가 느리면 `.env`의 `ARGOS_START_PERIOD`를 올리거나(예: `180s`) `up --timeout`에 시간을 더 주세요 |
| umask `027`이나 `077`인 호스트에서 ClickHouse, PostgreSQL 또는 서버가 설정 파일을 읽지 못해 healthy가 되지 않음(`argos-compose.sh up`이 기동을 거부하고 파일을 알려 줌) | 체크아웃 때 컨테이너가 자기 사용자로 읽는 설정 파일에 다른 사용자의 읽기 권한이 빠졌습니다(파일 `0640`, 디렉터리 `0750`). `deploy/compose`에서 `sudo chmod -R a+rX ../clickhouse/config.d ../clickhouse/users.d ../postgres config`를 실행하세요. 이 파일들에는 비밀값이 없습니다 |
| 서버가 기동 중 `tls.cert-file`, `tls.key-file`, 또는 gRPC TLS 인증서에 관한 메시지와 함께 종료 | gRPC 인증서나 키가 없거나 uid `10001`이 읽을 수 없습니다(갱신 뒤라면 [4장](#운영-환경의-인증서)의 `chmod`, `chown` 명령을 다시 실행하세요). 또는 키가 암호화하지 않은 PKCS#8이 아닙니다. `tls/server.key`의 첫 줄이 대시 사이의 `BEGIN PRIVATE KEY`가 아니면 [방법 A](#방법-a-조직의-인증서)처럼 변환하세요 |
| 서버가 비밀번호 때문에 기동을 거부 | 비밀번호 파일이 비었거나 짧거나 금지된 기본값이거나(데이터베이스 계정), 관리자 비밀번호가 비밀번호 정책에 어긋납니다(무인 설치). 파일을 바꾸고 `up`을 다시 실행하세요 |
| 설치 마법사가 토큰이 틀렸거나 만료되었다고 함 | 토큰은 서버 기동 뒤 30분 동안, 한 번만 유효하고, 재시작하면 이전 토큰은 무효가 됩니다. 서버를 다시 시작하고(`sudo ./argos-compose.sh compose restart server`) 파일에서 새 토큰을 읽으세요: `sudo ./argos-compose.sh compose exec server cat /data/setup-token`(로그의 이전 토큰은 더 이상 쓸 수 없습니다) |
| 로그인 화면이나 설치 마법사의 마지막 단계가 브라우저가 세션 쿠키를 저장하지 않았다고 알려 주거나, 로그인하면 로그인 화면으로 돌아감 | 브라우저가 `localhost`가 아닌 호스트에는 평문 HTTP로 `Secure` 세션 쿠키를 저장하지 않거나(`argos-env-init.sh`가 이 공개 URL에 경고했습니다), `ARGOS_WEB_PUBLIC_URL`이 브라우저 주소와 다릅니다. UI를 `http://localhost:8080`(호스트에서 또는 SSH 터널 경유)이나 HTTPS 리버스 프록시로 열고, 그 주소를 `ARGOS_WEB_PUBLIC_URL`에 두세요([5장](#ui-주소-정하기)) |
| ClickHouse가 다시 시작되거나 쿼리가 메모리 오류로 실패 | 부하에 비해 ClickHouse 메모리가 부족합니다. 호스트 메모리를 늘리거나, `ARGOS_CH_MEM`을 `ARGOS_CH_MAX_SERVER_MEMORY_USAGE`, 쿼리별 상한 두 개와 함께 올리세요([7.1절](#71-메모리)) |
| 파일 모드를 검사할 수 없다는 경고 | 파일이 POSIX 모드가 없는 파일 시스템(Windows, NTFS)에 있습니다. 지원하는 운영 구성이 아닙니다. Linux 파일 시스템에 설치하세요 |
| 에이전트가 TLS나 인증서 오류를 기록 | `server.address`가 인증서의 이름에 없거나, `server.tls.ca-file`이 그 인증서에 서명한 CA가 아닙니다 |
| `token create`가 종료 코드 4로 끝남 | 애플리케이션에 이미 활성 에이전트 토큰이 5개 있습니다 |
| `token create`가 server 서비스가 실행 중이 아니거나 healthy가 아니라고 함 | 스택을 시작하고(`sudo ./argos-compose.sh up`) `sudo ./argos-compose.sh status`에서 server가 healthy가 될 때까지 기다리세요. 토큰은 실행 중인 서버가 발급합니다 |

## 12. 이 버전에 없는 기능

다음은 계획되어 있지만 이 버전에는 없습니다.

- 샘플 애플리케이션과 생성 트래픽이 있는 데모 프로파일(`argos-env-init.sh --demo`는 제공하지 않는다고
  알립니다)
- `argos-compose.sh upgrade`, `backup`, `diag`(제공하지 않는다고 알리고 종료 코드 2로 끝남): Compose 설치의
  업그레이드, 백업, 진단 번들
- systemd를 쓰는 `tar.gz` 아카이브 설치와 에어갭 번들
- 서명된 amd64·arm64 서버 이미지 릴리스
- UI에서 관리하는 에이전트 토큰과 애플리케이션(`argos-compose.sh token create`를 쓰세요. 토큰 목록·회전·폐기는
  서버의 관리 API `/api/v1/admin/apps/{appId}/tokens`로도 할 수 있습니다)
- 그 밖의 `argos-admin` 명령(관리자 비밀번호 재설정 등)과 UI의 사용자 관리
- 애플리케이션, 인스턴스, 트랜잭션을 보여 주는 UI 화면(에이전트 연결은 [9장](#연결-확인)처럼
  확인하세요)

## 13. 시간 예산과 테스터 체크리스트

Argos 작업에 참여하지 않은 사람이 깨끗한 VM에서 이 가이드만 보고 처음 설치할 때의 예산입니다.
다운로드(소스, 빌드 의존성, 이미지)는 세지 않습니다. 소스로 서버 이미지와 에이전트 jar를 빌드하는
시간은 따로 기록합니다. 릴리스된 서버 이미지와 에이전트 jar가 그 단계를 대신하게 됩니다.

| # | 단계 | 장 | 예산 |
|---|---|---|---|
| 1 | 호스트 확인(Docker, Compose, `openssl`, 메모리, 디스크) | [2](#2-시작하기-전에) | 3분 |
| 2 | 파일 받기 | [3](#3-파일과-서버-이미지-준비) | 2분 |
| - | 서버 이미지 빌드(따로 기록) | [3](#3-파일과-서버-이미지-준비) | 세지 않음 |
| 3 | gRPC 인증서 준비 | [4](#4-grpc-인증서-준비) | 4분 |
| 4 | 설정과 비밀값 생성 | [5, 1단계](#1단계-설정과-비밀값-생성) | 2분 |
| 5 | healthy까지 스택 시작 | [5, 2~3단계](#2단계-스택-시작) | 4분 |
| 6 | 스모크 테스트 | [5, 4단계](#4단계-스모크-테스트-실행) | 2분 |
| 7 | 첫 관리자 만들기 | [5, 5단계](#5단계-첫-관리자-만들기) | 3분 |
| - | 에이전트 jar 빌드(따로 기록) | [9](#에이전트-jar-준비) | 세지 않음 |
| 8 | 첫 에이전트 연결: 토큰, 애플리케이션 호스트의 파일, 시작, 확인 | [9](#9-첫-에이전트-연결) | 8분 |
| | **합계(1~8단계)** | | **28분**(한도 30분) |

테스터 체크리스트: 이 표들을 복사해 단계마다 시작 시각과 끝 시각을 적고, 가이드가 불분명하거나 틀린
곳을 모두 적으세요.

| 항목 | 값 |
|---|---|
| 테스터(Argos 작업 참여 여부: 예 / 아니요) | |
| 날짜 | |
| 호스트: OS와 버전, CPU, RAM, 디스크 | |
| `docker version` / `docker compose version` | |
| 이 가이드의 소스 리비전 | |
| 다운로드 시간(소스, 이미지), 세지 않음 | |
| 서버 이미지와 에이전트 jar 빌드 시간, 따로 기록 | |

| # | 단계 | 시작 | 끝 | 분 | 문제, 불분명한 문장 |
|---|---|---|---|---|---|
| 1 | 호스트 확인 | | | | |
| 2 | 파일 받기 | | | | |
| - | 서버 이미지 빌드 | | | | |
| 3 | gRPC 인증서 준비 | | | | |
| 4 | 설정과 비밀값 생성 | | | | |
| 5 | healthy까지 스택 시작 | | | | |
| 6 | 스모크 테스트 | | | | |
| 7 | 첫 관리자 만들기 | | | | |
| - | 에이전트 jar 빌드 | | | | |
| 8 | 첫 에이전트 연결 | | | | |
| | **합계(1~8단계)** | | | | |

결과:

- 1~8단계 합계 30분 이내(예 / 아니요)
- `smoke.sh` 통과(예 / 아니요)
- 에이전트 로그에 `collector session established`가 있음(예 / 아니요)
- 애플리케이션에 요청을 보낸 뒤, 로그인한 브라우저의 `<공개 URL>/api/v1/apps?q=<애플리케이션>`에서
  `instances_up`이 `1` 이상([9장](#연결-확인))(예 / 아니요)
