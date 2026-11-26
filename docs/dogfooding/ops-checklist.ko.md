# 운영 체크리스트: 매일과 매주

[English](ops-checklist.md)

도그푸딩 2주([README.md](README.md)) 동안 운영자는 Argos 서버와 에이전트를 매일, 그리고 주 1회
점검하고 결과를 기록합니다. 이 페이지는 점검 항목마다 명령이나 화면, 정상 값, 정상이 아닐 때
할 일을 정리합니다. [docs/install/compose.md](../install/compose.ko.md)의 Docker Compose 설치를
기준으로 합니다. 명령은 설치의 `deploy/compose` 디렉터리에서, Linux Docker Engine에서는 `sudo`로
실행합니다.

기록 위치:

| 무엇 | 어디(비공개 저장소 `<PRIVATE-RECORDS-REPO>`) |
|---|---|
| 그날의 `ops-report.sh` 출력 | `ops/daily/<yyyy-mm-dd>.md` |
| 하루 한 행, 주마다 요약 한 개, 14일 뒤 판정 | `ops/operating-log.md`([operating-log-template.md](operating-log-template.md)로 작성) |
| 모든 문제와 의심 | `issues/issue-log.md`([issue-log-template.md](issue-log-template.md)로 작성) |

토큰, 비밀번호, 마스터 키, `secrets/`의 내용은 어떤 기록에도 붙여 넣지 않습니다.
`ops-report.sh`는 이것들을 출력하지 않고, 애플리케이션·인스턴스·호스트 이름도 출력하지 않습니다.

"벗어났을 때" 열은 서버 [런북](../ops/runbook.md)(영어)의 증상·장애 시나리오 절과 기존 가이드를
가리킵니다.

## 매일

### 값 모으기: `ops-report.sh`

하루 한 번, **야간 정합 검사가 끝난 뒤에** 실행합니다. 검사는 기본으로 서버 시각 03:00에
돌고(`argos.ops.reconcile.cron`) 전날(UTC)을 검사하므로, 아침에 실행하면 끝난 검사를 읽습니다.

```sh
cd <설치 디렉터리>/deploy/compose
sudo ./ops-report.sh --since <14일의 시작, UTC, 예: 2026-12-01T00:00:00Z> > daily.md
```

`ops-report.sh`는 읽기만 합니다. 아무것도 시작·중지·재실행하지 않고 어떤 행도 쓰지 않습니다.
PostgreSQL은 postgres 컨테이너 안에서 `argos_backup`으로 읽기 전용 트랜잭션 안에서 읽고,
`/actuator/prometheus`는 서버 컨테이너 안에서 관리 포트로 읽으며(메트릭 토큰은 컨테이너 밖으로
나오지 않음), clickhouse 컨테이너의 `df`와 백업 세트의 `manifest.json`을 읽습니다. 어디로도
보내지 않고 결과는 표준 출력으로만 나옵니다.

| 옵션 | 뜻 |
|---|---|
| `--since <UTC 시각>` | 14일 창의 시작. 이 시각 뒤에 서버가 시작했으면 재시작으로 표시합니다. 14일은 다시 시작합니다. |
| `--date <yyyy-mm-dd>` | 야간 정합 검사를 보여 줄 UTC 날짜(기본: UTC 기준 어제). 빠뜨린 날을 채울 때 씁니다. |
| `--backup-dir <dir>` | `backup.sh`에 `--dir`을 줄 때의 백업 디렉터리(기본: `.env`의 `ARGOS_BACKUP_DIR`, 없으면 `./backups`). |

종료 코드: `0` 모든 구획을 모음, `1` 어떤 구획을 모으지 못함("Sections" 표에 **FAILED**로 표시되고
나머지 구획은 그대로 출력), `2` 사용법 오류 또는 Docker 없음.

출력은 운영 일지의 표 행(날짜, 정합 불일치, 서버 재시작, 적재 지연, 디스크, FIRING 알림, 백업)과
**Checks to act on** 목록으로 시작합니다. 목록의 항목은 아래 표의 행 하나에 대응합니다. 행을 운영
일지에 복사하고 마지막 두 열(에이전트 원인 장애, 메모)을 채운 뒤, 출력 전체를 그날의 파일로
보관합니다. FAILED 구획은 그 자체로 발견 사항입니다. 기록하고 그 항목은 손으로 확인합니다.

### 매일 항목

| # | 항목 | 명령 또는 화면 | 정상 | 벗어났을 때 |
|---|---|---|---|---|
| D1 | 컨테이너와 health | `sudo ./argos-compose.sh status`, `ops-report.sh`의 "Services" | `server`, `clickhouse`, `postgres`가 running·healthy, 재시작 횟수 0 | `sudo ./argos-compose.sh logs server`(그리고 `clickhouse`, `postgres`), [compose.ko.md 11장](../install/compose.ko.md). 재시작 횟수가 0보다 크면 비정상 종료 뒤 재시작입니다. [런북 4.12절](../ops/runbook.md#412-server-out-of-memory-or-restarting). 문제 기록에 남깁니다. |
| D2 | 창 안에서 서버 재시작 없음 | "Server restarts" 열(`--since`와 함께) | `0 since <창 시작>`, 시작 시각이 날마다 같음 | Gate 1은 업그레이드를 포함해 서버 재시작 없는 14일을 요구합니다. 재시작과 원인을 기록하고, 14일은 다음 날부터 다시 셉니다. |
| D3 | 설치 상태 | `sudo ./smoke.sh`(옵션 없음: 아무것도 바꾸지 않고 로그인하지 않음) | 종료 0, 설치 직후와 같은 `NOT VERIFIABLE` 줄 | 실패하면 위반(파일 권한, 게시 포트, DB 포트, 기본 비밀번호)을 알려 줍니다. 진행하기 전에 고칩니다. 기본 자격 증명 없음과 DB 포트 비노출은 Gate 1 항목입니다(SEC-74, SEC-75). |
| D4 | 적재 지연 | "Ingest lag" 열: `argos_ingest_lag_seconds`의 p99(서버가 히스토그램 버킷만 내보내므로 보고서는 서버 시작 뒤 p99를 그 버킷의 상한으로 추정하고 구획에 출처를 적음)와 서버 시작 뒤 약 4.3초를 넘은 적재의 비율 | p99 5초 이하 | [런북 4.1절](../ops/runbook.md#41-ingest-lag-rises-argos_ingest_lag_seconds-p99-above-5-s): 저장소 패널(parts, merges), 무거운 조회, 디스크 속도. 기본 자기 알림은 30초에 울립니다. |
| D5 | 야간 정합 검사 | "Consistency mismatch" 열과 "Nightly consistency check" 구획: 어제(UTC)의 `reconcile_results` `summary` 행, 게이지 `argos_reconcile_mismatch_minutes` | 실행 1회, 불일치 `0`분, 결과 `clean`, 게이지 0 | Gate 1은 모든 날 0을 요구합니다. 보고서의 불일치 표와 함께 그날을 문제 기록에 남깁니다. 결과가 `repaired`가 아니면(`mismatch`, `failed`, `not_rebuilt`) 아래처럼 재구축을 포함해 손으로 검사하고 출력을 기록합니다. "No run recorded"는 작업 시각에 서버가 내려가 있었거나 작업이 꺼진 것입니다(`argos.ops.reconcile.enabled`). 손으로 검사합니다. 반복되면 [런북 4.13절](../ops/runbook.md#413-nightly-consistency-mismatch), 버그로 보고합니다. |
| D6 | 디스크 | "Disk" 열: `argos_server_disk_usage_ratio`(서버의 가장 큰 마운트)와 ClickHouse 데이터 볼륨(`df -P /var/lib/clickhouse`) | 서버 비율 0.80 미만, ClickHouse 볼륨 80% 미만 | 서버가 스스로 동작합니다(docs/17 9.2절): 0.80에 경고 알림(0.90에 심각), 0.90에 비상 보존 단축(가장 오래된 `profile_step` 파티션, 그다음 `xlog`를 하루씩 0.85 아래까지 DROP), 0.95에 ClickHouse 삽입을 멈추고 WAL만 기록(0.93에 재개). 디스크를 늘리거나 보존을 줄입니다(`argos-admin retention show` 뒤 보존 설정, [compose.ko.md 7.4](../install/compose.ko.md)). 비상 단축은 데이터가 사라진 것이므로 기록합니다. |
| D7 | 서버 드롭과 거부된 데이터 | "Ingest and storage" 구획: `argos_collector_dropped_total`, `argos_writer_dead_letter_blocks`, `argos_retention_emergency_drops_total` | 0, 또는 어제와 같은 값(서버 시작 뒤 누계) | 드롭이 늘면 인스턴스별 쿼터나 WAL 가득 참입니다. [런북 5.4절](../ops/runbook.md#54-server-errors-resource_exhausted-and-quota-t-ch-004)과 [5.9절](../ops/runbook.md#59-clickhouse-down-t-ch-009). dead-letter 블록은 ClickHouse가 거부한 데이터입니다. 문제 기록에 남기고 진단 번들을 보관합니다. |
| D8 | 알림과 채널 | "FIRING alerts" 열과 "Alerts and channels" 구획, 알림 화면 | FIRING 0(또는 메모에 하나씩 설명), 24시간 전송 실패 0, `failing` 채널 없음 | FIRING 알림을 하나씩 엽니다. 에이전트가 관련됐을 수 있으면(도입과 함께 시작된 애플리케이션 알림, 에이전트 자기 시리즈) 문제 기록을 엽니다. 채널 실패: 채널 화면에서 테스트 전송, [런북 4.7절](../ops/runbook.md#47-alert-channel-failures). |
| D9 | 인스턴스 | "Instances" 구획, 인스턴스 화면 | UP + WARN = 떠 있어야 할 인스턴스 수(WARN은 경고 조건이 있는 가동 중 인스턴스, 경고 수와 D10 참고), STALE 0, DOWN은 일부러 멈춘 것만, 승인 대기 0 | STALE: 애플리케이션 멈춤, 네트워크, 에이전트 전송 중단. 인스턴스와 에이전트 로그를 봅니다. 계획에 없는 DOWN은 애플리케이션 담당자에게 확인합니다. 승인 대기는 관리 > Pending registrations에서 승인하거나 거부합니다. |
| D10 | 에이전트 드롭과 비활성 모듈 | "Instances" 구획: 경고 `drops`, `module disabled`, `clock skew`가 있는 인스턴스, 인스턴스의 "Show latest self-diagnosis" | 각각 0 | 드롭과 자동 비활성 모듈은 에이전트 동작입니다. 자가 진단(큐, 쿼터, 모듈과 사유)과 함께 문제 기록을 엽니다. 시계 차이는 애플리케이션 호스트의 NTP입니다. [런북 4.5절](../ops/runbook.md#45-clock-skew-warning-warn). |
| D11 | 에이전트 토큰과 인증서 | "Certificate and tokens" 구획 | 인증서 30일 이상, 30일 안에 만료되는 에이전트 토큰 없음 | W3 참고. |
| D12 | 애플리케이션(애플리케이션 담당자) | 애플리케이션 자체 모니터링과 로그, 에이전트 로그 `argos-agent/logs/` | 에이전트 도입 뒤 새 오류·지연·CPU·메모리 변화 없음, 에이전트 `ERROR` 줄 없음, 표준 출력에 에이전트 출력 없음 | 의심은 모두 문제 기록에 남깁니다. 애플리케이션이 영향을 받으면 먼저 되돌리고(킬 스위치, [procedure.ko.md](procedure.ko.md)) 분석은 나중에 합니다. "Agent-caused incident" 열은 그날의 문제가 에이전트 원인으로 분류되면 `y`입니다. |

### 정합 검사를 손으로 실행하기

D5가 `clean`이나 `repaired`가 아닌 결과를 보이거나 실행이 없을 때 실행합니다. 롤업 락을 쓰므로
야간 작업이 돌고 있으면 기다립니다. ClickHouse를 많이 읽으니 피크 시간은 피합니다.

```sh
sudo ./argos-compose.sh compose exec -T -e JAVA_TOOL_OPTIONS= server java -Xmx256m -XX:+UseSerialGC \
  -cp @/opt/argos/app/jib-classpath-file io.argos.server.ServerApplication admin \
  consistency check --date <yyyy-mm-dd> --rebuild
```

`-e JAVA_TOOL_OPTIONS=`는 `argos-compose.sh token create`처럼 서버의 힙 옵션이 이 두 번째 JVM에
들어가지 않게 합니다. 종료 코드 0: 남은 롤업 불일치 없음, 1: 불일치가 남았거나 검사 실패,
2: 사용법 오류([docs/ops/admin-cli.md](../ops/admin-cli.md)). 실행은 트리거 `command`로
`reconcile_results`에, 감사 이벤트 `CONSISTENCY_CHECKED`로 기록됩니다. 그 뒤
`sudo ./ops-report.sh --date <yyyy-mm-dd>`를 실행하면 그날의 마지막 실행을 보여 줍니다. 처음의
실패한 검사는 일지에서 여전히 불일치가 있는 날로 셉니다.

## 매주

같은 요일에 하고, 운영 일지의 주간 요약을 씁니다.

| # | 항목 | 명령 또는 화면 | 정상 | 벗어났을 때 |
|---|---|---|---|---|
| W1 | 백업 | `ops-report.sh`의 "Backup" 열과 "Backups" 구획, 일일 백업 작업의 로그([backup-restore.md, Daily backup](../ops/backup-restore.md#daily-backup)) | 날마다 세트 하나, 가장 새 세트가 26시간 이내, 작업 로그의 종료 코드 0, `--clickhouse`를 쓰면 가장 새 세트에 ClickHouse 백업 있음 | `sudo ./argos-compose.sh backup`을 손으로 실행하고 메시지를 읽습니다([backup-restore.md](../ops/backup-restore.md), 종료 코드). 세트가 없는 날은 발견 사항이니 기록합니다. |
| W2 | 마스터 키와 복구 준비 | `sudo ./argos-compose.sh compose exec -T -e JAVA_TOOL_OPTIONS= server java -Xmx256m -XX:+UseSerialGC -cp @/opt/argos/app/jib-classpath-file io.argos.server.ServerApplication admin secrets verify --purpose backup`, 별도 안전한 곳의 마스터 키 사본 | 종료 0, 키 사본이 백업 기록에 적힌 곳에 있음 | 마스터 키가 없으면 복구 때 채널 비밀값과 명령 결과를 잃습니다. 지금 사본을 되살립니다. 전체 복구 리허설은 [docs/ops/recovery-rehearsal.md](../ops/recovery-rehearsal.md)(Gate 2 전, 이후 분기마다)이며 14일 안에 할 필요는 없습니다. |
| W3 | 인증서와 토큰 만료 | "Certificate and tokens" 구획, 관리 > Agent tokens | gRPC 인증서 30일 이상, 30일 안에 만료되는 에이전트 토큰 없음 | `tls/server.crt`와 `tls/server.key`를 바꾸고 재시작 없이 다시 읽게 합니다(`argos-admin tls reload`, [admin-cli.md](../ops/admin-cli.md)). 서버를 재시작하면 14일이 다시 시작합니다. 만료가 다가온 토큰은 회전하고(`argos-admin token rotate --prefix <argos_xxxxxxxx>`, 유예 7일) 애플리케이션 호스트의 토큰 파일을 바꿉니다. |
| W4 | 보존과 용량(N-19) | `sudo ./argos-compose.sh compose exec -T -e JAVA_TOOL_OPTIONS= server java -Xmx256m -XX:+UseSerialGC -cp @/opt/argos/app/jib-classpath-file io.argos.server.ServerApplication admin retention show` | 테이블 크기가 날마다 일정하게 늘고, 전체 보존 기간까지의 예상이 디스크에 들어감 | 매주 테이블별 크기를 기록합니다. 일 증가량 = (이번 주 크기 - 지난주 크기) / 7. TPS와 인스턴스 수에 맞춘 docs/17 7.4절 추정과 비교하고, 30%를 넘게 다르면 N-19 사람 확인 항목이므로 현장 보고에 기록합니다. 예상이 디스크에 들어가지 않으면 14일째 전에 디스크를 늘리거나 보존을 줄입니다. |
| W5 | 감사 로그 | 관리 > Audit log, 최근 7일, CSV로 내보내 비공개 저장소에 보관 | 예상한 이벤트만: 내 로그인, 내가 한 `TOKEN_*`·`CONFIG_*`·`KILL_SWITCH`, 매일 밤 `CONSISTENCY_CHECKED`, 창 시작의 `SERVER_STARTED` 1건 | 모르는 행위자, 연속된 로그인 실패(`LOGIN_FAILED`, `ACCOUNT_LOCKED`), 모르는 출처의 `AGENT_AUTH_FAILED`, 창 안의 `SERVER_STARTED`: 문제 기록을 엽니다. |
| W6 | 버전 | 서버 버전(관리 > About), 에이전트 버전(관리 > Instance management) | 14일 동안 그대로(계획된 업그레이드로 창을 다시 시작한 경우 제외) | 서버를 업그레이드하면 14일이 다시 시작합니다. 업그레이드 전에 [docs/upgrade/0.1.md](../upgrade/0.1.md)를 읽습니다(에러 그룹이 한 번 갈림). |
| W7 | 진단 번들(문제가 있을 때만) | `sudo ./argos-compose.sh diag --since 24h`([compose.ko.md 15.3](../install/compose.ko.md)) | 평소 주에는 실행하지 않음 | 비밀값은 가려지지만 번들에는 호스트 이름, 주소, 로그가 남습니다. 읽어 본 뒤 문제 기록 옆에 비공개 저장소로 보관하고, 공개 이슈에는 절대 첨부하지 않습니다. |

## 점검을 할 수 없을 때

`ops-report.sh`가 어떤 구획에서 실패하면(종료 1) 그 항목은 손으로 확인하고 실패 이유를 적습니다.
서버가 내려가 있으면 중단 시간(`sudo ./argos-compose.sh status`와 로그 기준)을 기록합니다.
에이전트는 계속 돌며 버퍼에 쌓고, 14일은 서버가 재시작했을 때만 다시 시작합니다.

## 이 체크리스트가 다루지 않는 것

- 도입 자체, 에이전트 없는 인스턴스와의 비교, 되돌리기, 수집기 중단 훈련:
  [procedure.ko.md](procedure.ko.md).
- 서버 자기 메트릭용 Prometheus·Grafana 예시는 이 버전에 없습니다. 관리 포트는
  [compose.ko.md 7.2](../install/compose.ko.md)에 있습니다.
