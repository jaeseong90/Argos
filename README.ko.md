# Argos APM

[![Status: pre-release](https://img.shields.io/badge/status-pre--release%2C%20unsupported-orange)](#프로젝트-상태)
[![License: Apache-2.0](https://img.shields.io/badge/license-Apache--2.0-blue)](LICENSE)

[English](README.md)

Argos APM은 Java 백엔드(Spring Boot·Tomcat)를 위한 오픈소스 설치형 APM(Application Performance
Monitoring)이다. 바이트코드 에이전트(Java 8+)가 트랜잭션, SQL(커넥션 풀 대기 포함), 외부 호출, 배치 작업,
JVM·풀·호스트 지표를 수집해 Spring Boot 수집 서버로 보내고, 서버는 ClickHouse와 PostgreSQL에 저장한다.
React UI는 전체 현황, XLog(응답시간 산점도), 분산 추적, 실시간 액티브 트랜잭션, 전수 SQL 통계, 야간 오탐을
억제하는 알림을 제공한다. 폐쇄망을 포함한 어떤 운영 환경에 설치해도 문제없이 돌아가는 상용 SW 수준의
완성도를 목표로 한다.

## 프로젝트 상태

**사전 릴리스(pre-release). 지원하지 않는다. 운영 환경에 쓰지 말 것.**

Argos APM은 설계와 저장소 골격 단계다. 릴리스, 컨테이너 이미지, 배포된 에이전트는 아직 없다. 저장소에는
설계 문서, 빌드 골격, 이후 모든 변경이 통과해야 하는 CI 검사가 있다. 아래에 적은 인터페이스는 계획이며
1.0 전까지 예고 없이 바뀔 수 있다.

## 빠른 시작: 데모

Docker가 있는 한 대에서 Argos를 체험한다. 서버, ClickHouse, PostgreSQL, 에이전트를 붙인 샘플
애플리케이션 2개(`order-api`, `payment-api`), 부하 생성기(초당 50요청: 느린 요청, 에러, N+1 쿼리,
`order-api`에서 `payment-api`로의 호출, 2분마다 도는 배치)가 뜬다. Docker Engine 24 이상과 Compose
v2.24 이상(또는 Docker Desktop), Docker에 보이는 메모리 7.4 GiB 이상이 필요하고, 이미지가 배포되기
전까지는 이미지를 빌드할 JDK 21이 필요하다.

```sh
# 1. 저장소 루트에서 이미지를 한 번 빌드한다(첫 빌드는 몇 분 걸린다).
./gradlew :server:jibDockerBuild :sample-apps:demo-app:jibDockerBuild :sample-apps:load:jibDockerBuild

# 2. 데모를 준비하고 시작한다(Linux Docker Engine에서는 각 명령 앞에 sudo).
cd deploy/compose
./argos-env-init.sh --demo
docker compose --profile demo up -d

# 3. http://localhost:8080 을 열고 admin과 아래 비밀번호로 로그인한다. UI가 새 비밀번호를 묻는다.
cat secrets/admin_password
#    약 1분 안에 XLog 점이 들어온다. 대시보드, 트레이스, 에러, SQL, 배치 트랜잭션을 보고,
#    약 8분 뒤에는 알림도 확인한다.

# 4. 데모와 모든 데이터를 지운다.
docker compose --profile demo down -v
```

데모는 평가 전용이다. 데모 에이전트는 Docker 네트워크 안에서 평문 gRPC로 보내고, UI에 "DEMO MODE"
배너가 보이며, 포트 `8080`과 `9100`은 `127.0.0.1`에 게시되고, 서버는 마스터 키 파일의 권한 검사를
건너뛴다(Docker Desktop에서는 비밀값 파일이 `0777`로 보인다. 기동마다 경고를 남긴다). 자세한 내용과 문제 해결(Windows의 Docker
Desktop 포함)은 [docs/install/compose.ko.md 14장](docs/install/compose.ko.md#14-데모-프로파일)에 있다. 실제
설치는 그 가이드의 나머지를 따른다.

애플리케이션에 에이전트 붙이기(계획):

```sh
export ARGOS_SERVER_TOKEN_FILE=/etc/argos/agent.token
java -javaagent:/opt/argos-agent/argos-agent.jar \
     -Dargos.server.address=argos.example.internal:9100 -Dargos.app.name=shop -Dargos.app.env=prod \
     -jar app.jar
```

모든 설정은 `argos-agent.yml`, 시스템 프로퍼티 `-Dargos.<key>`, 환경변수 `ARGOS_<KEY>` 중 어느 것으로도
줄 수 있다.

## 소스에서 빌드

요구 사항: JDK 21(에이전트 테스트용 JDK 8 toolchain은 Gradle이 자동으로 내려받는다), Corepack이 포함된
Node.js 24(24.14.0 이상), Docker 24+, git. 자세한 내용, Windows 참고 사항, 전체 검사 목록은
[docs/dev-setup.md](docs/dev-setup.md)(영문)에 있다.

```sh
corepack enable                      # PC마다 한 번
corepack install -g "$(node -p "require('./ui/package.json').packageManager")"   # ui/가 고정한 pnpm 버전
./gradlew build                      # 모든 모듈: 컴파일, 단위 테스트, 정적 분석, 검사
pnpm -C ui install --frozen-lockfile
pnpm -C ui build && pnpm -C ui test
```

Windows에서 `corepack enable`이 EPERM으로 실패하면 pnpm을 `corepack pnpm …`으로 실행한다
([docs/dev-setup.md](docs/dev-setup.md) 1절 참고).

## 호환성 목표(1.0)

| 영역 | 지원(검증) |
|---|---|
| 애플리케이션 JDK | 8(8u252+), 11, 17, 21, 25 |
| 실행 모델 | 플랫폼 스레드, 가상 스레드(JDK 21+, Spring Boot 3.2+) |
| 서블릿 컨테이너 | Tomcat 9.0, 10.1, 11(독립형·내장) |
| Spring | Framework 5.3 / 6.x / 7.x, Spring Boot 2.7 / 3.x / 4.x |
| JDBC | MySQL, MariaDB, PostgreSQL, Microsoft SQL Server, Oracle |
| HTTP 클라이언트 | `HttpURLConnection`, Apache HttpClient 4.5 / 5.x, OkHttp 3 / 4, Java 11 `HttpClient`, `RestTemplate` |
| 서버 | JDK 21, ClickHouse(번들 고정 LTS), PostgreSQL 15+, Docker 24+, linux/amd64·linux/arm64 |

WebFlux(Netty) 서버, Kotlin 코루틴, GraalVM native image는 1.0 범위 밖이다. "미검증" 조합을 포함한 전체
매트릭스는 [docs/01-product-plan.md](docs/01-product-plan.md) 8장에 있다.

## 텔레메트리: 없음

Argos APM은 외부로 정보를 보내지 않는다(no phone-home). 에이전트·서버·UI는 운영자가 명시적으로 설정한
대상(수집 서버 주소, 알림 채널, SMTP) 외에 어떤 네트워크 호출도 하지 않는다. 사용 통계·크래시 리포트·업데이트
확인이 없고, UI는 외부 폰트·스크립트·분석 도구를 불러오지 않는다.

## 문서

| 문서 | 내용 |
|---|---|
| [docs/README.md](docs/README.md) | 설계 문서 지도와 읽는 순서 |
| [docs/dev-setup.md](docs/dev-setup.md) | 개발 환경, 빌드·테스트 명령(영문) |
| [CHANGELOG.md](CHANGELOG.md) | 주요 변경 내역([Keep a Changelog](https://keepachangelog.com/ko/1.1.0/)) |
| [ROADMAP.md](ROADMAP.md) | 단계와 품질 게이트 |
| [docs/adr/](docs/adr/) | 아키텍처 결정 기록(ADR) |

기획·설계 문서(`docs/01`~`docs/06`, `docs/10`~`docs/19`)는 한국어가 원본이다. 사용자 문서는 영어가 원본이며
한국어 번역을 둔다.

## 커뮤니티와 기여

- 질문과 아이디어: GitHub Discussions(영어 또는 한국어). 이슈는 버그·기능 제안·호환성 보고 전용이다.
- 기여는 [DCO](https://developercertificate.org/)(Developer Certificate of Origin)로 받는다. 모든 커밋에
  `git commit -s`로 서명한다. [CONTRIBUTING.md](CONTRIBUTING.md)를 참고한다.
- 이 프로젝트는 [행동 강령](CODE_OF_CONDUCT.md)을 따른다.
- 의사 결정 방식: [GOVERNANCE.md](GOVERNANCE.md). 사람이 기획과 검수를 맡고 AI 코딩 에이전트의 도움을 받아
  구현한다. 모든 변경은 같은 테스트와 사람의 리뷰를 거친다.

## 보안

취약점은 공개 이슈로 올리지 말고 [SECURITY.md](SECURITY.md)의 비공개 신고 절차를 따른다.

## 라이선스

Argos APM은 [Apache License 2.0](LICENSE)으로 배포한다. 저작권 고지는 [NOTICE](NOTICE)에 있다. 라이선스는
바꾸지 않는다([GOVERNANCE.md](GOVERNANCE.md)).
