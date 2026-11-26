# 도그푸딩 절차: 사내 시스템에 에이전트 적용

운영 중인 애플리케이션에 에이전트를 붙이는 순서입니다. 사전 점검부터 전체 적용까지, 그리고 되돌리는
두 가지 길을 다룹니다. [docs/01-product-plan.md](../01-product-plan.md) 9.5절의 에이전트 도입 흐름과
시나리오 S-07·S-08을 따릅니다. 단계마다 무엇을 실행하고, 무엇이 보여야 하고, 무엇을 기록하고, 언제
멈추는지 적었습니다.

[English](procedure.md)

개요: [README.md](README.md)(영문). 기록은 비공개 저장소 `<PRIVATE-RECORDS-REPO>`에 둡니다. 아래 파일
이름은 README의 저장소 구성을 따릅니다. 화면 이름은 UI 언어가 한국어일 때의 이름이며, 괄호 안은 영어
UI의 이름입니다.

| 단계 | 내용 | 담당 | 기록 위치 |
|---|---|---|---|
| [0](#0단계-준비) | Argos 서버 준비 | 운영자 | 수락 기록, 운영 일지 |
| [1](#1단계-사전-점검) | 애플리케이션 호스트에서 사전 점검 | 애플리케이션 담당 | `check-*.txt`, 수락 기록 |
| [2](#2단계-등록과-토큰) | 애플리케이션 등록, 토큰 발급, 다시 점검 | 운영자, 애플리케이션 담당 | `check-*.json`, 수락 기록 |
| [3](#3단계-카나리-인스턴스-1대) | 인스턴스 1대에 에이전트 적용 | 애플리케이션 담당 | 수락 기록 |
| [4](#4단계-24시간-비교) | 카나리와 에이전트 없는 인스턴스를 24시간 비교 | 애플리케이션 담당 | 비교 표 |
| [5](#5단계-한-대씩-확대) | 한 대씩 확대 | 애플리케이션 담당, 운영자 | 운영 일지 |
| [6](#6단계-롤백) | 롤백: 킬 스위치(즉시) 또는 제거(완전) | 운영자, 애플리케이션 담당 | 문제 기록 |
| [7](#7단계-수집-서버-장애-훈련t-acpt-08) | 수집 서버 장애 훈련(T-ACPT-08) | 운영자 | `server/outage-drill-*.md` |
| [8](#8단계-업그레이드와-토큰-회전) | 운영 기간 중과 뒤의 업그레이드, 토큰 회전 | 운영자 | 운영 일지 |
| [9](#9단계-운영-중-사람-확인) | 실제 시스템에서 사람만 확인할 수 있는 항목 | 전원 | 수락 기록 |

아래에서 쓰는 자리표시: `<collector-host>`는 에이전트가 접속하는 Argos 서버 주소(gRPC 인증서에 들어 있는
이름), `app-a`와 `prod`는 애플리케이션 이름과 환경, `APP_USER`와 `APP_GROUP`은 애플리케이션을 실행하는
사용자와 그룹, `<app libs>`는 애플리케이션의 jar·WAR·라이브러리 디렉터리, `<공개 URL>`은 Argos UI
주소입니다. 서버 명령은 Argos 호스트의 `deploy/compose`에서 실행하며, Linux Docker Engine에서는
`sudo`를 붙입니다.

## 0단계: 준비

첫 시스템 전에 한 번 합니다.

1. **서버 설치**: [docs/install/compose.ko.md](../install/compose.ko.md) 2~5장대로, 예상 부하에 맞는
   호스트에 설치합니다([CPU, 메모리, 디스크](../install/compose.ko.md#cpu-메모리-디스크)). gRPC 인증서는
   자체 서명이 아니라 조직의 인증서나 자체 CA로 만든 것을 씁니다.
2. **스모크 테스트** 통과:

   ```sh
   sudo ./smoke.sh
   ```

   기대: 종료 코드 `0`, 마지막 줄 `smoke: passed (<n> checks ok, <m> not verifiable on this host)`.
   `smoke: FAIL` 줄이 하나라도 있으면 멈추고 설치부터 고칩니다. `NOT VERIFIABLE` 줄은 실패가 아니며,
   그 줄이 가리키는 항목을 기록합니다.
3. **자기 알림이 사람에게 닿는지**: UI의 **알림 규칙**(Alert rules)에 Argos 서버 자신의 규칙(대상
   "Argos 서버": 디스크, 적재 지연, 채널 실패, 인증서·토큰 만료, parts, 정합)이 첫 기동 때 만들어져
   있습니다. **알림 채널**(Notification channels)에서 채널을 만들고 시험 메시지를 보냅니다. 채널이
   없으면 자기 알림은 UI에만 보입니다.
4. **백업 1회**, 마스터 키는 백업과 따로 보관([15.1절](../install/compose.ko.md#151-백업과-복구)):

   ```sh
   sudo ./argos-compose.sh backup
   ```

   기대: 종료 코드 `0`, `ARGOS_BACKUP_DIR`에 새 백업 세트. 15.1절의 일일 `crontab` 줄도 넣습니다.
   세트 이름(내용이 아니라)을 `server/backups.md`에 기록합니다.
5. **시계 동기화**: Argos 호스트와 모든 애플리케이션 호스트(`timedatectl`이 `System clock
   synchronized: yes`, 또는 `chronyc tracking`의 오프셋이 작음). 사전 점검은 1초 이상 차이부터 경고합니다.
6. **운영 기간 동안 서버 버전 고정**: **버전 정보**(About)에 보이는 버전을 기록합니다. 14일 동안 Argos
   서버를 업그레이드하거나 재시작하지 않습니다. 서버를 재시작하면(업그레이드 포함) 14일을 처음부터 다시
   셉니다(Gate 1: "서버 재시작 없이 2주"). [7단계](#7단계-수집-서버-장애-훈련t-acpt-08) 수집 서버 장애
   훈련은 14일이 시작되기 **전에** 합니다.
7. **일일 점검이 되는지**: [ops-checklist.ko.md](ops-checklist.ko.md)의 일일 점검을 한 번 해 보고 결과를
   운영 일지에 붙여 넣습니다. 운영 첫날이 첫 시도가 되지 않게 합니다.

수락 기록에 적을 것: 서버 버전, 설치일, 스모크 결과, 백업 세트, 채널 시험 결과, 시계 상태.

## 1단계: 사전 점검

애플리케이션 호스트에서, 애플리케이션 사용자로, 애플리케이션을 실행하는 `java`로, `-javaagent`를 넣기
전에 합니다. 에이전트 디렉터리, jar, CA 인증서, `argos-agent.yml`을
[docs/install/compose.ko.md 9장](../install/compose.ko.md#9-첫-에이전트-연결)대로 준비합니다(에이전트
jar 받기부터 에이전트 설정까지). 값은 `server.address: <collector-host>:9100`, `app.name: app-a`,
`app.env: prod`, `server.token-file: /opt/argos-agent/token`입니다. 그다음:

```sh
sudo -u "$APP_USER" /path/to/the/application/java -jar /opt/argos-agent/argos-agent.jar --version
sudo -u "$APP_USER" /path/to/the/application/java -jar /opt/argos-agent/argos-agent.jar --check \
  --config /opt/argos-agent/argos-agent.yml --classpath <app libs> | tee check-<instance>-<yyyymmdd>.txt
```

표 마지막 줄 `Result: ...; exit code <n>`이 점검의 종료 코드입니다(파이프 뒤의 `$?`는 `tee`의 것).
종료 코드: `0` 실패 없음, `1` 실패한 점검 있음, `2` 사용법 오류. 점검은 애플리케이션에 붙지 않고, 파일을
쓰지 않고, 디렉터리를 만들지 않습니다([docs/agent-config.md, Pre-flight check](../agent-config.md#pre-flight-check---check)).

이 1차 점검은 **토큰이 생기기 전에** 합니다(01 9.5절: 점검 결과를 변경 요청서에 첨부하고, 토큰은
2단계에서 발급). 이때의 기대:

| 점검 | 1차 기대 |
|---|---|
| `config` | PASS(`app.env`가 있어야 함. 이 항목 WARN은 빠졌다는 뜻) |
| `token` | FAIL: 아직 토큰 없음. 예상되는 실패는 이것 하나뿐 |
| `dns`, `tcp`, `tls` | PASS. `<collector-host>`까지 연결과 인증서 체인이 동작 |
| `hello` | PASS가 아님(서버가 토큰 없는 세션을 거부하거나 SKIP) |
| `clock` | SKIP 또는 PASS |
| `jdk` | PASS. Java 8은 update 252 이상 |
| `classpath` | 찾은 라이브러리마다 PASS. WARN은 검증하지 않은 버전 |
| `log-dir`, `token-file` | PASS, 또는 토큰이 없는 동안 `token-file` 행 없음 |

**멈춤**: 그 밖의 실패. DNS·연결·TLS(주소 오류, 방화벽, 인증서 이름이나 CA), 지원하지 않는 JDK,
`classpath` FAIL(지원하지 않는 라이브러리 버전), 쓸 수 없는 `log-dir`. 고친 뒤 1차 점검을 다시 합니다.
`classpath` WARN은 적용을 멈추지 않지만, 하나하나 애플리케이션 담당의 결정과 함께 수락 기록에 적습니다.

기록: 파일 `check-<instance>-<yyyymmdd>.txt`와 이 단계의 시작 시각.

## 2단계: 등록과 토큰

1. Argos 호스트에서 **토큰 발급**. 명령줄로:

   ```sh
   sudo ./argos-compose.sh token create --app app-a --env prod --expires-days 90
   ```

   기대: 종료 코드 `0`, 토큰이 에이전트 설정 예시와 함께 **한 번** 출력됨. 종료 코드 `4`는 그
   애플리케이션에 활성 토큰이 이미 5개라는 뜻입니다. `--expires-days`는 선택(기본 365, 1~3650)이며,
   운영 기간과 다음 회전 계획보다 뒤에 끝나게 정합니다. 또는 UI에서: **애플리케이션**(Applications) →
   **새 애플리케이션**(New application)(이름, 환경, 등록 정책, 권장 알림 규칙) → **에이전트
   토큰**(Agent tokens) → **토큰 발급**(Issue token). 이때도 토큰은 한 번만 보입니다.

   `token create`가 애플리케이션 `(app-a, prod)`를 직접 등록하므로, 정확히 이 이름과 환경을 보고하는
   에이전트는 승인 없이 등록됩니다.

2. 각 애플리케이션 호스트에 **토큰 저장**: 애플리케이션 사용자로, 모드 `0600`, 명령줄에 토큰을 넣지 않고
   ([토큰 저장](../install/compose.ko.md#토큰-저장)):

   ```sh
   sudo -u "$APP_USER" sh -c 'umask 077 && cat > /opt/argos-agent/token'
   ```

   토큰을 붙여 넣고 Enter, 그다음 Ctrl-D. yml은 `server.token-file`로 이 파일을 가리킵니다. 토큰을
   `argos-agent.yml`, 티켓, 채팅, 비공개 저장소에 쓰지 않습니다.

3. **승인 대기: 적용할 때는 피합니다.** 2.1의 토큰을 에이전트가 보고하는 이름과 환경 그대로 발급하면
   승인을 기다릴 것이 없습니다. 승인 대기열은 아무도 등록하지 않은 환경으로, 등록 정책이
   **승인**(Approval)인 환경(**애플리케이션** 화면의 **등록 정책** 열, 또는 서버 기본값
   `argos.agent.auto-register.<env>`)으로 접속한 에이전트를 위한 것입니다. 그런 세션은 거부되고 요청이
   **승인 대기**(Pending registrations)에 나타나며, **승인**(Approve)하면 그 환경의 애플리케이션을 만들고
   토큰 하나를 발급해 한 번 보여 줍니다. 에이전트는 다음 재시도에 접속합니다. REST로는 관리자 세션으로
   `GET /api/v1/admin/pending-registrations` 다음 `POST /api/v1/admin/pending-registrations/{id}/approve`
   (또는 `.../reject`)입니다. 사전 점검은 요청을 대기열에 넣지 않으며(hello가 check-only), 환경이
   등록되지 않은 동안 `hello` 행은 승인 사유로 실패합니다. 요청이 나타나면(계획에 없던 `app.env`로
   시작한 에이전트) 그 화면에서 승인하거나 거부하고 문제 기록에 적습니다. 승인 전까지 그 에이전트는
   `collector rejected the agent session: APP_NOT_REGISTERED ...` `ERROR` 줄을 한 번 남깁니다.

4. **사전 점검 2차**: 1단계와 같은 명령에 `--json`도:

   ```sh
   sudo -u "$APP_USER" /path/to/the/application/java -jar /opt/argos-agent/argos-agent.jar --check \
     --config /opt/argos-agent/argos-agent.yml --classpath <app libs> | tee -a check-<instance>-<yyyymmdd>.txt
   sudo -u "$APP_USER" /path/to/the/application/java -jar /opt/argos-agent/argos-agent.jar --check \
     --config /opt/argos-agent/argos-agent.yml --classpath <app libs> --json > check-<instance>-<yyyymmdd>.json
   ```

   기대: `Result: PASS` 또는 `Result: WARN`에 `0 failed`, 종료 코드 `0`. `token` 행은 `argos_****`,
   `token-file` 행은 `rw-------`. `hello` 행에 `check-only (no instance registered)`, `Note:` 줄에 서버가
   인스턴스를 등록하지 않았다는 문장. JSON은 `"exit_code":0`. UI에 `<instance>-check` 인스턴스가 생기지
   않습니다(에이전트보다 오래된 서버는 등록한 뒤 중지로 표시하며, `Note:` 줄이 어느 쪽인지 알려 줌).

   **멈춤**: 종료 코드 `1`. `hello` 행이 원인을 말합니다(토큰 무효·만료·폐기, 다른 애플리케이션의 토큰,
   승인 대기 중인 애플리케이션, 서버가 받는 범위 밖의 에이전트 버전). 고친 뒤 2차 점검을 다시 합니다.
   `token-file` WARN(`chmod 600`)과 `clock` WARN(NTP)은 3단계 전에 고칩니다.

기록: JSON 파일, 토큰 **접두사**(`argos_xxxxxxxx`, **에이전트 토큰** 화면), 만료일, 발급자(역할), 시각.

## 3단계: 카나리 인스턴스 1대

짝이 있는 인스턴스를 고릅니다. 짝은 같은 애플리케이션에서 역할·하드웨어·트래픽 비중이 같고 에이전트
없이 남는 인스턴스입니다(두 대면 더 좋음, [comparison-template.md](comparison-template.md) 참고). 비교의
24시간 "전" 구간은 이 단계 **전에** 시작합니다. 그동안 모두 에이전트 없이 돕니다.

1. 카나리의 기동 설정(예: `JAVA_OPTS`나 서비스 유닛)에 JVM 옵션 하나를 그대로 넣습니다:

   ```sh
   -javaagent:/opt/argos-agent/argos-agent.jar=config=/opt/argos-agent/argos-agent.yml
   ```

2. 평소 변경 시간대에 그 인스턴스의 애플리케이션을 재시작합니다. 재시작 시작 시각과 애플리케이션이
   준비됐다고 알린 시각을 기록합니다.
3. **배너**: 에이전트 로그에 기동 배너가 정확히 한 줄:

   ```sh
   sudo grep "started in" /opt/argos-agent/logs/argos-agent.log
   sudo grep "collector session established" /opt/argos-agent/logs/argos-agent.log
   ```

   기대: 기동마다 `Argos agent <version> (commit <c>, protocol <p>) started in <ms> ms: app=app-a
   env=prod ... token=argos_**** ... enabled=true ...` 한 줄, 그리고 `collector session established` 한 줄.
4. **애플리케이션 자신의 출력에 에이전트 출력 없음**: 이번 기동의 애플리케이션 표준 출력과 로그에
   에이전트가 쓴 줄이 없습니다(에이전트는 자기 로그 파일에만 쓰고, 그 파일을 쓸 수 없을 때만 표준
   오류로 대신 씀):

   ```sh
   grep -ci "argos" <이번 기동의 애플리케이션 stdout 또는 콘솔 로그>
   sudo grep -c " ERROR " /opt/argos-agent/logs/argos-agent.log*
   ```

   기대: `0`과 `0`(에이전트 로그 파일마다 개수 하나). JVM 옵션을 출력하는 기동 스크립트처럼 에이전트를
   언급하는 애플리케이션 쪽 줄은 세지 않고 적어 둡니다. 에이전트 로그의 `WARN`은 모두 읽고 기록합니다.
5. **인스턴스 UP**: UI에서 애플리케이션의 **인스턴스**(Instances) 화면에 카나리가 `UP`. 또는 로그인한
   브라우저에서 `<공개 URL>/api/v1/apps?q=app-a`를 열면 `instances_up`이 `1`.
6. **자가 진단 정상**: 인스턴스 페이지(**인스턴스** → 카나리)에서:
   - 머리글에 **유실 (24시간)**(Drops (24 h)) `0`, **비활성 모듈**(Modules disabled) `없음`(None);
   - **환경**(Environment) 탭의 **계측 모듈**(Instrumentation modules)에 `비활성`(Disabled)이거나 오류가
     있는 모듈이 없고, **자가 진단**(Self diagnosis)의 유실이 `0`;
   - 전체 보고서(운영자 역할): **명령**(Commands) 탭 → **자가 진단 실행**(Run a self diagnosis), 그다음
     **최근 자가 진단 보기**(Show latest self-diagnosis). 확인할 것: 유실 항목 없음, 자동으로 비활성된
     모듈 없음, 출처가 `default`인 `instrumentation.app-packages`가 애플리케이션 자신의 패키지를
     가리킴(U-20, [9단계](#9단계-운영-중-사람-확인)).
7. 평소 트래픽을 보내고 카나리의 트랜잭션이 보이는지 확인합니다(XLog, 대시보드).

**멈추고 롤백**([6단계](#6단계-롤백)): 애플리케이션이 뜨지 않거나 다르게 동작, 에이전트가 애플리케이션
출력에 씀, 에이전트 로그에 `ERROR`, 애플리케이션 준비 뒤 1분 안에 `UP`이 되지 않음, 유실이 `0`이 아님,
모듈이 자동으로 비활성됨. 문제 기록에 적습니다.

수락 기록(T-ACPT-07)에 적을 것: 1~3단계 시각, 배너의 `started in` 값, 오류·출력 개수, 인스턴스 상태,
자가 진단 결과.

## 4단계: 24시간 비교

카나리를 평소 트래픽으로 24시간 돌리고 [comparison-template.md](comparison-template.md)(영문)를 채웁니다.
지연 p50·p95·p99, 에러율, CPU, 힙, GC, 기동 시간, 처리량을 카나리와 짝에서 **Argos 밖의 같은 도구로**
잽니다. 에이전트 없는 인스턴스는 Argos에 데이터가 없고, Argos 인스턴스 비교는 p99가 아니라 p95를
보이므로, Argos 수치는 카나리 교차 확인에만 씁니다.

기준(S-07): **CPU 차이 3%p 이하**, **p99 차이 1ms 이하**. 두 인스턴스가 에이전트 전에 이미 가졌던 차이를
뺀 값으로 판정합니다(계산은 템플릿에 있음).

- 둘 다 기준 안이고 사고 없음: 5단계로.
- 기준을 넘고, 에이전트 없는 두 인스턴스 사이의 잡음보다도 큼: **멈추고 롤백**(6단계), 문제를 엽니다.
- 기준을 넘지만 그 잡음 안: 판정 불가. 확대하지 않습니다. 24시간 더 비교하거나 역할을 바꾸고(짝에
  에이전트, 카나리에서 제거) 애플리케이션 담당과 결정합니다. 결정을 기록합니다.

## 5단계: 한 대씩 확대

1. 다음 인스턴스에 3단계처럼(파일, 토큰 파일, JVM 옵션, 재시작) 에이전트를 붙이고 3단계 3~6항을
   확인합니다. 비교가 가능하도록 마지막까지 에이전트 없는 인스턴스를 하나 남깁니다.
2. 한 대마다 애플리케이션의 **인스턴스 비교**(Instance comparison, 대시보드 구역과 **인스턴스** 화면)를
   봅니다. 값이 있는 인스턴스가 3대 이상이면 서버가 p95·에러율·CPU의 이상치를 표시합니다. 에이전트가
   붙은 인스턴스 가운데 이상치가 있으면 다음 인스턴스 전에 멈추고 살펴봅니다.
3. 대시보드의 **수집 상태**(Collection quality)를 확인합니다: 에이전트 유실 `0`, 예상 밖 제외 요청 없음,
   시계 차이 경고 없음.
4. 모든 인스턴스에 에이전트가 붙으면 14일 운영 기간을 시작할 수 있습니다(그 뒤로 서버를 재시작하지
   않았다면, [0단계](#0단계-준비) 참고). 시작 시각을 UTC로 기록합니다. 이때부터 매일 운영 일지를
   채웁니다([ops-checklist.ko.md](ops-checklist.ko.md)). 매일 실행하는
   `sudo ./ops-report.sh --since <시작, YYYY-MM-DDTHH:MM:SSZ>`는 그 시각 뒤의 서버 기동을 재시작으로
   보고합니다.

## 6단계: 롤백

되돌리는 길은 두 가지이며, 둘 다 Argos 서버를 바꾸지 않습니다([docs/agent-config.md, Kill switch](../agent-config.md#kill-switch)).

### 즉시: 킬 스위치, 재시작 없음

에이전트의 모든 계측 지점이 즉시 아무 일도 하지 않게 됩니다. 애플리케이션은 그대로 돌고, 메트릭과
세션은 남으므로 다시 켤 수 있습니다.

- UI: **설정**(Configuration) → 애플리케이션 → **킬 스위치**(Kill switch) 구역 → **인스턴스**(Instances,
  전체 또는 한 대) → **추적 중지(킬 스위치 켜기)**(Stop tracing (kill switch on)), 사유 입력, 확인.
  **인스턴스별 결과**(Results by instance)에 인스턴스마다 적용됨이 보이고, 킬 스위치를 끌 때까지
  인스턴스는 `WARN`입니다.
- REST, 관리자 세션으로: `POST /api/v1/admin/apps/{appId}/kill-switch`, 본문
  `{"enabled": false, "reason": "<사유>", "instance_ids": [<id>]}`(`instance_ids`가 없거나 빈 목록이면
  전체 인스턴스). 응답 `202`에 설정 변경이 담기고, 인스턴스별 결과는
  `GET /api/v1/admin/config/changes/{changeId}`에 있습니다.

기대: 접속 중인 모든 인스턴스가 **10초** 안에 적용(N-25), 에이전트 로그에 킬 스위치 한 줄, 서버 감사
로그에 `KILL_SWITCH`. 응답 `400`은 대상 에이전트 가운데 `enabled` 키의 원격 변경을 허용하는 것이 없다는
뜻입니다(`remote-config.allow`를 좁힘): 완전 롤백을 씁니다. 되돌리려면 **추적 재개(킬 스위치
끄기)**(Resume tracing (kill switch off), `"enabled": true`).

### 완전: 에이전트 제거, 재시작

1. 기동 설정에서 `-javaagent:...` 옵션을 빼고 애플리케이션을 재시작합니다. 옵션을 뺄 수 없으면(공용 기동
   스크립트, 플랫폼 설정) 대신 `-Dargos.agent.disabled=true`를 넣습니다. 다음 기동부터 에이전트는 아무것도
   설치하지 않습니다.
2. 기대: 애플리케이션이 에이전트 전처럼 기동하고, Argos에서 인스턴스는 중지로 보입니다(에이전트가 종료 때
   작별 메시지를 보내므로 인스턴스 다운 알림이 나지 않음). 에이전트 로그에 새 `started in` 배너가
   없습니다.
3. 인스턴스는 자동 보관(7일)에 맡기거나 **인스턴스 관리**(Instance management)에서 보관합니다.

롤백은 모두 문제 기록에 적습니다: 어느 길로, 왜, 시각, 적용까지 걸린 시간.

## 7단계: 수집 서버 장애 훈련(T-ACPT-08)

시나리오 S-08: Argos 서버가 내려가도 애플리케이션은 영향을 받지 않습니다. 훈련은 서버를 재시작하므로
**14일이 시작되기 전에** 한 번 합니다. 평소 트래픽이 있는 시간을 고르고, 알림 수신자에게 알리고,
애플리케이션 담당이 훈련 동안과 그 직전 같은 길이 동안 Argos 밖에서 지연을 잽니다(비교와 같은 방법).

1. 서버를 10분 멈춥니다(에이전트가 보관하는 약 2분 분량보다 길게 해서 유실이 세어지게):

   ```sh
   sudo ./argos-compose.sh compose stop server
   ```

   시각을 기록합니다. 기대: 애플리케이션은 계속 응답하고, Argos 밖에서 잰 지연이 변하지 않고, 에이전트
   로그는 연결 끊김을 경고하며 백오프로 재시도하되 `ERROR`는 없고, 애플리케이션 출력에 에이전트 출력이
   없습니다.
2. 다시 시작합니다:

   ```sh
   sudo ./argos-compose.sh compose start server
   sudo ./argos-compose.sh status
   ```

   `status`에 서버가 `healthy`로 보인 시각을 기록합니다.
3. 기대: 그 시각부터 **1분** 안에 모든 에이전트가 `collector session established`를 다시 남기고(각
   에이전트 로그의 그 줄 시각을 읽음), 인스턴스가 `UP`이 됩니다.
4. UI에서 훈련 시간 범위를 보면: 대시보드의 **수집 상태**에 훈련 동안의 에이전트 유실이 보이고, 차트는
   중단 구간을 TPS `0`이 아니라 음영 처리된 수집 공백으로 보입니다.

애플리케이션이 영향을 받으면 훈련을 **멈추고** 서버를 바로 시작합니다. 이것은 에이전트 원인 장애이므로
기록합니다.

`server/outage-drill-<yyyymmdd>.md`와 수락 기록의 T-ACPT-08 행에 적을 것: 정지·시작 시각, 마지막
에이전트가 재접속할 때까지 걸린 시간, 중단 전과 중단 중 애플리케이션 p99(Argos 밖), 보인 유실 수, 음영
구간 화면 캡처(비공개 저장소에만).

## 8단계: 업그레이드와 토큰 회전

- **서버 업그레이드**: 14일이 끝난 뒤에 하거나, 14일을 다시 세는 것을 받아들입니다. 서버 먼저, 에이전트는
  나중: [docs/install/compose.ko.md 15.2절](../install/compose.ko.md#152-업그레이드와-롤백)
  (`sudo ./argos-compose.sh upgrade --image argos-server:<new version>`, 롤백은 `upgrade --rollback`).
  업그레이드는 전날의 정합 검사를 실행하므로 결과를 기록합니다.
- **에이전트 업그레이드**: 5단계처럼 jar를 바꾸고 한 대씩 재시작합니다. 개발·도그푸딩 빌드에서 올리면
  에러 그룹이 한 번 새로 갈리고, `instrumentation.app-packages`를 바꾸면 다시 갈립니다.
  [docs/upgrade/0.1.md](../upgrade/0.1.md)(영문)를 읽고 변경 시각을 운영 일지에 적습니다.
- **토큰 회전**: **에이전트 토큰** → **회전**(Rotate), 또는 서버 컨테이너에서 `argos-admin token rotate`
  ([docs/ops/admin-cli.md](../ops/admin-cli.md#token-commands-and-a-running-server)):

  ```sh
  sudo ./argos-compose.sh compose exec -T -e JAVA_TOOL_OPTIONS= server java -Xmx256m -XX:+UseSerialGC \
    -cp @/opt/argos/app/jib-classpath-file io.argos.server.ServerApplication \
    admin token rotate --prefix argos_xxxxxxxx --grace-days 7
  ```

  새 토큰은 한 번 출력됩니다. 이전 토큰은 유예 기간(1~30일, 기본 7일) 동안 계속 동작합니다. 에이전트는
  토큰 파일을 기동 때 읽으므로, 유예 기간 안에 모든 호스트의 파일을 바꾸고 인스턴스마다 재시작합니다.
  **폐기**(Revoke)하면 그 토큰의 세션이 5초 안에 끝납니다. 아래의 다른 `argos-admin` 명령(`retention
  show`, `version`)도 같은 명령줄로 실행합니다.

## 9단계: 운영 중 사람 확인

실제 시스템에서만 답할 수 있는 미결 항목입니다. 답과 근거를 수락 기록에 적습니다.

| 항목 | 질문 | 확인 방법 |
|---|---|---|
| U-18 | 웹 애플리케이션이 여러 개인 Tomcat: `contextPath` 접두가 서비스 사전 상한(웹 애플리케이션 수 × 서비스 수 ≤ 10,000) 안에 머무는가 | 애플리케이션의 서비스 통계, 인스턴스 **자가 진단**의 **사전 오버플로**(Dictionary overflows)가 `0`으로 유지 |
| U-19 | 청크가 수천 개인 Spring Batch step: 요약 모드로 충분한가 | 종류 `BATCH` 트랜잭션: 상세에 요약 행이 보이고, 배치가 쓸 수 없을 만큼 많은 step으로 쪼개지지 않고, HTTP 통계가 영향받지 않음 |
| U-19 | 애플리케이션의 실제 Logback·Log4j 설정에서 로그 상관 | 트랜잭션 실행 중 애플리케이션 로그 줄에 상관 키가 있음([Log correlation](../agent-config.md#log-correlation)) |
| U-20 | 추정된 `instrumentation.app-packages`가 맞는가 | 자가 진단: 출처 `default`인 값을 애플리케이션 자신의 패키지와 비교, 에러 스택의 최상위 애플리케이션 프레임이 애플리케이션의 프레임 |
| N-19 | 용량 산정이 ±30% 안에서 맞는가 | 실제 부하에서 하루 저장량 증가를 [용량 표](../install/compose.ko.md#cpu-메모리-디스크)와 비교: 서버 컨테이너에서 `argos-admin retention show`([docs/ops/admin-cli.md](../ops/admin-cli.md)) |
| SEC-74, SEC-75 | 운영 중에도 기본 자격증명이 없고 게시 포트가 두 개뿐인가 | 주간 점검의 `sudo ./smoke.sh`([ops-checklist.ko.md](ops-checklist.ko.md)) |
| C-240 (12) | 실제 서버 상대 `--check`가 인스턴스를 등록하지 않음, 정상 종료한 애플리케이션이 인스턴스 다운 알림을 만들지 않음 | 2단계 4항과 6단계(완전 롤백) |
