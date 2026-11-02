# 에이전트 오버헤드 측정 (WO-02 P-2)

- 상태: **측정 중단, 수집 자료 정리 완료**(2026-10-25). 공유 노트북에서 측정 큐를 돌리다가 2026-10-24 21:14Z에 사용자 결정(토큰·시간 비용)으로 멈췄다. 이 문서는 그때까지 모은 자료를 정리한 것이다.
- **이 머신에서는 Gate 0 오버헤드를 판정하지 않는다.** 아래 수치는 모두 공유 개발 머신의 참고값이다. 판정은 사람이 전용 장비에서 9장 절차로 다시 재서 내린다(WO-02 수동 수락 기준 "사람이 1회 재실행해 재현"도 그 실행으로 채운다).
- 관련: 01 §7.3(측정 프로토콜 1~11항), N-01~N-04·N-26~N-30, 02 §11 P-2·§12 A-3, 03 §4 Gate 0 "오버헤드", 18 §7(벤치 하네스), 10 §14(예산), ADR-0004 C-46(k6 게이트)·C-49(기동 정의)
- 원시 자료: [`docs/bench/wo02-overhead-2026-10-24/`](../bench/wo02-overhead-2026-10-24/README.md)(세트별 `set.json`·`result.json`·`report.md`, 실행별 `run.json`·k6 요약·1초 CPU 표본 등, 약 2.9MB). 작업 머신의 scratch 디렉터리는 WO-02 뒤 지우므로 저장소 사본이 유일한 원시 자료다(01 §7.3 11항).

## 1. 결론

1. **VALID 세트는 0개다.**
   - 부하 세트 중 끝까지 돈 것은 `main-3`·`main-9` 2개다. 둘 다 off 3회 CPU 변동계수가 2%를 넘어 하네스가 INVALID로 판정했다(26.17%, 4.39%).
   - 기동 세트 `startup-2`·`startup-3`은 shop-boot3만 재고 중단됐다(ABORTED, 부분).
   - 컨테이너(9항)는 70초 파일럿 1쌍뿐이고 INVALID다. 가상 스레드(10항), OTel 참조점(11항), 기동 분해(diag)는 시작하지 못했다.
   - 세트별 분류는 5장에 있다.
2. Gate 0 기준별 참고값이다(판정 아님, 8장).
   - **no-op 상대 CPU(한도 5%)**: `main-9` +3.45%(95% 신뢰구간 −5.44 … +12.14%), `main-3` 조용한 2쌍 +4.76%·+4.87%, 프로토콜 밖 배경 보정 분석 +4.9%(±2.8%). 5% 경계 근처라 전용 장비 판정 전까지 **위험**으로 본다.
   - **p99 증가(한도 max(1ms, off p99의 5%))**: 전체 p99 차이 −0.12ms(`main-9`)·−0.28ms(`main-3`)이고 신뢰구간이 0을 포함한다("차이 없음"). 조용한 쌍의 경로별 p99 차이는 +0.04ms 이하다. 한도 안이지만, 전체 p99가 느린 경로(300ms, 5%)에 묶여 있어 민감하지 않다(6.3).
   - **기동 +2초(heavy 포함)**: shop-boot3 기본 CDS +1,721ms·+1,713ms(두 세트 일치), `-Xshare:off` +2,088ms(8쌍)·+2,009ms(6쌍)이다. heavy 앱은 재지 못했다. 기본 CDS는 여유가 약 14%뿐이고 CDS 끔은 한도를 넘는다. **가장 큰 위험이다**(7장).
   - 메모리·스레드(N-02·N-26·7항): 에이전트 힙 최대 1.4MB, 메타스페이스 +6.9MB, NMT Internal+Other +0.3MB, 직접 메모리 0, 에이전트 스레드 3개다. 한도 대비 여유가 크고 CPU 잡음의 영향도 작다.
3. 이 머신으로 유효한 수치를 낼 수 없는 이유(8.1)
   - k6가 앱과 같은 머신에서 앱과 비슷한 CPU(0.5코어)를 쓴다.
   - 다른 작업의 배경 CPU가 측정 구간 평균 7~61%였다.
   - 배경이 1%p 늘 때 off 앱 CPU가 약 0.9% 늘었다(배경 61%에서는 +49%).
   - 가용 메모리가 0.46GB까지 떨어졌다.
   - 결과적으로 off CV 2% 조건을 맞출 수 없었다.
4. 남은 일
   - 9장 절차로 전용 장비에서 재실행한다. 측정만 약 7.5시간(OTel 참조점을 단축하면 약 6.5시간)이다.
   - 그 결과로 8장 표를 채우고 Gate 0을 판정한다.
   - 기동 위험(7.3)은 재실행 전에 코어 담당이 원인을 줄일지 PM이 정한다(11장).

## 2. 측정 대상과 환경 (01 §7.3 1항)

| 항목 | 값 |
|---|---|
| 에이전트 | 배포 jar `argos-agent-0.0.0-SNAPSHOT.jar`(`:agent:agent-dist:agentJar`, 2026-10-24 18:23 KST 빌드), 4,074,064 bytes, SHA-256 `f108eaee6afe10033c396835bf106bec9dac8a34cae5b054209b0b5673ac3cfd`. 계측 모듈 5개(servlet, spring-webmvc, jdbc, executor, httpclient-jdk), 셰이딩 ByteBuddy 1.18.15, 전송 없음. 모든 세트가 같은 jar다 |
| 에이전트 설정 | `-Dargos.exporter=noop -Dargos.log.dir=<실행 디렉터리>/agent-logs`, 나머지는 기본값. PoC에는 샘플링·프로파일 정책이 없다. 모든 트랜잭션의 step을 `StepBuffer`에 기록한 뒤 no-op 익스포터가 버린다 |
| 앱 | `sample-apps/shop-boot3`(Spring Boot 3.5, Tomcat, Hibernate, H2 in-memory, HikariCP), `shop-boot3.jar` 76,163,634 bytes, SHA-256 `e15278a7…bda75`. 기동 세트는 `shop-heavy.jar`(139,353,963 bytes, `1d2ab258…fcf6`)도 대상이다. 외부 호출 대상은 `sample-apps/ext-stub`(`27fb16bc…c489`) |
| 부하 | `sample-apps/load/bench.js`(SHA-256 `cc26dbd1…40919`), k6 v2.3.0 windows/amd64 네이티브 바이너리(`fetch-k6.sh`, 릴리스 SHA-256 확인). k6는 AGPL-3.0 개발 도구로, 별도 프로세스로만 실행한다(`docs/licenses/dev-only.md` `tool:k6`) |
| 머신 | 노트북 1대. AMD Ryzen 5 5600H(6코어 12스레드), RAM 15.3 GiB(실행 직전 가용 0.5~5.9GB, 유지된 실행의 측정 구간 최저 0.46~3.2GB), Windows 11 Home 10.0.26200, AC 전원. 전원 구성표 "HP 권장"(GUID `48684d4a-8524-4093-8a63-ea7132b79c1c`). THP와 governor는 Windows에 없다 |
| 앱 JDK | Microsoft Build of OpenJDK 21.0.10+7-LTS(`~/.jdks/ms-21.0.10`). 하네스 JDK는 OpenJDK 21+35다 |
| 컨테이너(파일럿) | Docker Desktop 29.2.1, WSL2 커널 6.18, cgroup v2, VM 12 CPU·7.43 GiB(WO-03 ClickHouse와 공유). 이미지 `eclipse-temurin:21-jdk@sha256:23cc5734…96d`, k6 `grafana/k6:2.3.0@sha256:9c2dee7f…eb34`, 표본기 `alpine:3@sha256:be265606…9a` |
| 커밋 | `4a43e77`에 미커밋 작업 트리(WO-00~WO-04)를 더한 상태. 세트마다 `set.json`에 jar·스크립트 SHA-256, 에이전트 설정 문자열의 SHA-256, 작업 트리 상태를 남긴다 |
| 공유 상태 | 같은 시간에 다음 작업이 함께 돌았다. 영향은 실행마다 1초 머신 CPU(`machine.json`), 직전 5초 부하, 배경 CPU로 남겼다(6.1).<br>- WO-03(ClickHouse 적재·리뷰·Gradle 빌드)<br>- WO-02 다른 레인의 빌드·테스트<br>- Windows Defender 실시간 검사<br>- WSL2 VM<br>- 대화형 사용자 프로그램 |
| 시간 | 2026-10-24 10:43Z~21:14Z(KST 19:43~06:14). 이 문서의 시각은 모두 UTC다 |

## 3. 하네스 (`test/agent/overhead`)

01 §7.3 프로토콜을 그대로 돌리는 측정기다.
- 개발 전용 Gradle 프로젝트 `:test:agent:overhead`이고 배포하지 않는다. `build`·`check`는 컴파일과 단위 테스트(통계·파서·판정 33건)만 한다.
- 측정은 Gradle 데몬 없이 실행 jar로 돈다.
- WO-05가 `test/bench`(`benchTest`)로 옮기고, WO-51이 주간 벤치로 만든다(18 §7, DSN-18-07).
- 사용법 전체는 `test/agent/overhead/README.md`에 있다.

2026-10-25 정리 시점의 상태: `./gradlew :test:agent:overhead:check :test:agent:overhead:overheadJar -Pargos.ui.skip=true --rerun-tasks`가 통과한다(의존성 검증 strict, 단위 33/33). 다시 만든 `overhead-bench.jar`의 SHA-256(`90cfbe96…6c8f`)은 측정에 쓴 jar와 같다.

| 명령 | 하는 일 |
|---|---|
| `test/agent/overhead/fetch-k6.sh` | k6 v2.3.0 네이티브 바이너리를 받아 SHA-256을 확인하고 `test/agent/overhead/build/k6/`에 둔다. 버전은 `run-bench.sh`가 이미지로 고정한 것과 같다 |
| `run-overhead.sh load --out DIR` | A-B-A-B-A-B 부하 세트(2~7항). 주요 옵션:<br>- `--mode container`(9항)<br>- `--vt`(10항)<br>- `--variants off,on,otel --otel-agent JAR`(11항 참조점)<br>- `--k6-ssh user@host --app-address IP`(1항: 부하기를 다른 머신에서)<br>- `--variant-defs`(진단용 에이전트 변형) |
| `run-overhead.sh startup --out DIR` | 기동 세트(8항, C-49). 앱 × CDS(기본, `-Xshare:off`) × 변형을 번갈아 10회씩 잰다 |
| `run-overhead.sh --no-build report --out DIR` | 원시 파일에서 `result.json`·`report.md`를 다시 만든다(측정 없음) |

부하 세트의 실행 1회(앱 1회 기동)는 다음 순서로 돈다.

1. 머신 CPU 5초 평균이 `--max-load` 이하인 확인이 `--quiet` 동안 연속으로 나올 때까지 기다린다(최대 `--max-wait`).
2. 앱을 새로 띄운다. on 변형은 `-javaagent:<배포 jar> -Dargos.exporter=noop -Dargos.log.dir=<run>/agent-logs`를 더한다.
3. 웜업으로 k6 `bench.js`를 3분 돌린다(별도 k6 실행, 보고만 한다).
4. `jcmd Thread.print` → 측정 구간 k6 10분(별도 k6 실행) → `jcmd Thread.print` 순으로 돈다. 그동안 앱 프로세스 CPU 시간(1초 시계열), 머신 CPU·가용 메모리(1초), k6 CPU를 잰다.
5. `jcmd GC.run` 2회 → `GC.class_histogram` → `VM.native_memory summary` → `VM.metaspace`.
6. 앱을 끈다. 앱 로그는 앞 300줄·뒤 200줄과 WARN/ERROR 집계만 남긴다. 의도된 5% 오류가 스택 트레이스를 남겨 실행당 약 250MB가 되기 때문이다.

- 웜업과 측정을 별도 k6 실행으로 나눈 이유: k6는 `dropped_iterations`를 구간별로 태그하지 않는다. 측정 구간을 독립 실행으로 두면 C-46의 "측정 구간 dropped ≤ 1%"를 정확히 판정할 수 있다(`WARMUP=0s`, `MAX_UNEXPECTED_RATE=0`, `MAX_DROPPED_RATE=0.01`).
- 잡음 처리
  - 배경 CPU = 머신 CPU − 앱 CPU − k6 CPU이고, 논리 CPU 12개 대비 %로 낸다.
  - 웜업이나 측정 구간의 배경 CPU가 `--max-background`를 넘거나 측정 구간의 C-46 게이트가 INVALID면, 그 시도를 `runs/<NN>-<variant>-discarded-<k>/`로 옮기고 다시 한다(최대 `--retries`).
  - 마지막 시도는 결과와 관계없이 남긴다.
  - 기동 세트는 기동 1회마다 같은 규칙을 쓴다.

판정(`result.json`, 종료 코드 0 PASS·3 FAIL·4 INVALID)

| 구분 | 조건 |
|---|---|
| INVALID | off 3회 CPU 변동계수 > 2%(4항), off 실행의 k6 게이트 실패, 부하기 머신 평균 CPU ≥ 80%(C-46) |
| FAIL(부하) | 다음 중 하나라도 해당하면 FAIL이다.<br>- on 실행의 k6 게이트 실패<br>- 상대 CPU > 5%(컨테이너 8%, N-29)<br>- 1분 피크 > 8%(N-01)<br>- p99 증가 > max(1ms, off p99의 5%)(N-03)<br>- 에이전트 스레드 CPU > 호스트의 1%<br>- 에이전트 힙 > 64MB(N-02)<br>- 메타스페이스 증가 > 32MB, NMT `Other` > 8MB, 에이전트 스레드 > 6(N-26)<br>- NMT `Internal+Other` 증가 > 16MB, 스레드 증가 > 6(7항) |
| FAIL(기동) | 포트 바인딩 중앙값 증가 > 2초(N-04, Gate 0, heavy 포함), heavy 상대 > 10%(N-04·N-27), premain 중앙값 > 500ms(N-30) |

## 4. 01 §7.3 프로토콜 11항 대응

| 항 | 프로토콜 | 하네스 구현 | 이번 측정 | 결과·편차 |
|---|---|---|---|---|
| 1 환경 | 부하기와 앱 서버는 다른 머신(같은 L2). 코어·메모리·governor·THP·OS·JDK 기록, 설정 해시 | 구현했다.<br>- `set.json`: OS, CPU, 메모리, 전원 구성표(Windows) 또는 governor·THP·커널(Linux), JDK, k6, jar·`bench.js`·설정 문자열 SHA-256, git<br>- 실행마다: 직전 부하, 1초 머신 CPU<br>- `--k6-ssh`: k6를 다른 머신에서 ssh로 돌리고 그 머신의 `/proc/stat`으로 C-46 포화를 판정 | 기록은 했다. 부하기 분리는 못 했다 | **편차**: 머신이 1대라 k6와 앱이 같은 머신에서 돌았다(2장). `--k6-ssh` 경로는 실제 2대 구성으로 한 번도 돌리지 않았다(11장 H-4) |
| 2 JVM 고정 | `-Xms2g -Xmx2g -XX:+UseG1GC -XX:+AlwaysPreTouch -Xlog:gc:file=gc.log`, on/off 동일 | 구현했다. 기본값 `--heap 2g`이고, 양쪽에 `-XX:NativeMemoryTracking=summary`(7항)를 더한다 | 했다(편차 있음) | **편차**: 부하 세트는 `--heap 1g`로 돌렸다. 가용 메모리가 0.5~5.9GB여서 2GB를 미리 터치하면 페이징이 생긴다. 기동 세트는 2g다. 통합 테스트용 C-27 플래그는 쓰지 않는다(CDS를 끄고 JDK 클래스까지 검증하므로 운영 구성이 아니다) |
| 3 워크로드 | k6 `constant-arrival-rate` 1,000 TPS, 믹스(DB 1~3 SQL 60%, 외부 HTTP 20%, N+1 10%, 느림 5%, 에러 5%), step 약 8, 프로파일 정책 기본·최악 | WO-01 `bench.js` 그대로(1,000/s, 18 §7.2 믹스, VU 300~600, 요청 타임아웃 10초) | 했다 | 요청당 평균 계측 step은 약 3.3개(SQL 3.1, HTTP 0.2)로 "약 8"보다 적다. 앱 카탈로그 기준으로 `/api/orders/{id}` 1, 페이지 3, N+1 21, `/api/pay` 외부 호출 1(`HttpURLConnection`)이고, PoC 에이전트에는 메서드 step이 없다. **프로파일 정책이 PoC에 없어** 모든 트랜잭션을 기록하므로 이번 수치는 "최악(정상 100%)"에 해당한다. "기본값"은 정책이 생기는 WO-10·16 뒤에 잰다 |
| 4 절차 | 워밍업 3분 제외, 측정 10분, A-B-A-B-A-B 같은 날, off CV > 2%면 무효 | 구현했다. 실행마다 앱을 재기동하고 실행 사이에 30초 쉰다. 하네스가 CV를 자동 검사한다 | 했다 | 완료 세트 2개(`main-3`, `main-9`)가 모두 off CV 초과로 INVALID다(6.2) |
| 5 CPU | 앱 프로세스 utime+stime(또는 cgroup `usage_usec`) 차분, 에이전트 스레드 CPU는 `ArgosThread` 자가 진단 | 구현했다.<br>- 프로세스 CPU 시간(Windows `GetProcessTimes`, Linux `/proc`)을 측정 k6 실행 앞뒤로 차분한다.<br>- 1초 시계열로 1분 피크(10초 간격 1분 창)를 낸다.<br>- 컨테이너는 cgroup v2 `cpu.stat`을 쓴다. | 했다 | **편차**: 자가 진단 메트릭이 아직 없다(WO-10). 그래서 `jcmd Thread.print`의 `argos-*` 스레드 `cpu=`를 측정 앞뒤로 차분했다 |
| 6 지연 | 부하기 HDR p50·p95·p99·p99.9와 95% 신뢰구간, 구간이 0을 포함하면 "차이 없음" | 구현했다. k6 Trend는 측정 구간 전체 표본으로 정확한 백분위를 낸다(경로별 포함). 신뢰구간은 회차별 on−off 차이(3쌍)의 Student t 구간(자유도 2)이다 | 했다 | **편차**: 요청별 부트스트랩(원시 `--out json`)은 쓰지 않았다. 요청 60만 건의 원시 기록을 같은 머신 디스크에 쓰면 측정이 흔들리기 때문이다. Windows k6의 요청 시간 해상도는 약 0.5ms다(p50이 0 또는 0.5ms로 양자화된다, 6.3) |
| 7 메모리 | `GC.run` 2회 → `argos.` 합 ≤ 64MB, 메타스페이스 차 ≤ 32MB, NMT `Internal+Other` 차 ≤ 16MB, 스레드 차 ≤ 6, 72시간 기울기 | 구현했다.<br>- `io.argos.agent.*` 인스턴스 합을 낸다(샘플 앱 `io.argos.sample.*`은 제외).<br>- NMT 범주별 committed, 메타스페이스, 로드 클래스, 스레드 수를 기록한다.<br>- GC 로그로 요청당 할당량을 계산한다. | 했다(72시간 제외) | 6.4·6.5. 72시간은 범위 밖이다(WO-52) |
| 8 기동 | JVM 시작 → `Started … in` + 포트 바인딩, 10회 중앙값, 기본·heavy 앱 | C-49대로 구현했다.<br>- 판정: 프로세스 생성 → 포트 바인딩(10ms 폴링) 중앙값<br>- 병기: `/actuator/health` 첫 200, Spring `Started … in`, premain, 준비까지 앱 CPU 초<br>- 앱 × 기본 CDS·`-Xshare:off` × off/on 10회, off·on과 on·off를 번갈아 | 부분 | shop-boot3만 쟀다(기본 CDS 10쌍 × 2세트, CDS 끔 8쌍·6쌍). shop-heavy는 파일럿 1회뿐이다(7장) |
| 9 컨테이너 | `docker run --cpus=2 --memory=4g`(cgroup v2) | `--mode container`로 구현했다.<br>- 고정 JDK 이미지에 jar를 읽기 전용으로 마운트한다.<br>- ext-stub·k6는 사설 네트워크의 컨테이너다.<br>- 앱 cgroup 밖의 표본기 컨테이너가 1초마다 읽는다.<br>- `--memory` 기본값은 2g다. | 파일럿만 | 70초 파일럿 1쌍(`pilot2c`)은 부하기 포화로 INVALID다. 본 세트 `container-1`은 출력 없이 중단됐고 이후 실행하지 못했다(6.7) |
| 10 가상 스레드 | Boot 3.2+ `spring.threads.virtual.enabled=true` | `--vt`(`SAMPLE_VT=true`, shop-boot3 Boot 3.5, JDK 21)로 구현했다 | 안 함 | 큐에 있었으나 시작 전에 중단됐다 |
| 11 보고 | `docs/bench/<날짜>-<커밋>.md`에 환경·원시 수치·통계·그래프·재현 명령, OTel 참조점 | 구현했다.<br>- 세트별 `report.md`·`result.json`·원시 파일을 만든다.<br>- 그래프 대신 1초 시계열(`machine.json`, `appCpuTimeline`)을 남긴다.<br>- OTel 참조점: `--variants off,on,otel --otel-agent`(모든 익스포터 `none`) | 이 문서와 원시 자료 | 원시 자료는 `docs/bench/wo02-overhead-2026-10-24/`(디렉터리와 README)에 있다. OTel 참조점은 측정하지 못했다 |

## 5. 세트 목록과 분류

분류 기준
- VALID: 끝까지 돈 세트에서 하네스가 유효 조건으로 PASS·FAIL을 냈다.
- INVALID: 끝까지 돈 세트(또는 파일럿)를 하네스나 프로토콜이 무효로 본다.
- ABORTED: 끝나기 전에 멈췄다.

판정·사유는 각 세트의 `result.json`을 그대로 옮겼다. 중간에 멈춘 세트는 2026-10-25에 `overhead-bench.jar report --out <세트>`로 원시 파일에서 다시 계산했다(새 측정 없음). **VALID 세트는 없다.**

| 세트 | 종류 | 시간(UTC) | 분류 | 하네스 판정과 사유 |
|---|---|---|---|---|
| [`pilot1`](../bench/wo02-overhead-2026-10-24/pilot1/report.md) | 부하 파일럿, 1회차, 웜업 20초·측정 40초 | 10:43~10:46 | INVALID(파일럿) | INVALID: off CV 계산 불가(off 1회) |
| [`pilot2c`](../bench/wo02-overhead-2026-10-24/pilot2c/report.md) | 컨테이너 파일럿 `--cpus 2 --memory 2g`, 1회차, 20초·70초 | 10:47~10:54 | INVALID(파일럿) | INVALID: off k6 게이트 INVALID(부하기 머신 96%), on k6 게이트 FAIL(dropped 1.2%) |
| [`pilot3s`](../bench/wo02-overhead-2026-10-24/pilot3s/report.md) | 기동 파일럿, 칸당 1회 | 10:58~11:04 | INVALID(파일럿) | 하네스는 FAIL(+5.4초 등)을 냈다. 칸당 n=1이고 직전 CPU가 23~94%라 C-49의 10회 중앙값이 아니다 |
| [`main-1`](../bench/wo02-overhead-2026-10-24/main-1/report.md) | 부하 | 11:05~11:22 | ABORTED | 1회차 off 뒤 대기 중에 손으로 멈췄다. 재계산 결과 INVALID: off CV 계산 불가, 1회차 off k6 게이트 FAIL(unexpected 0.0065%) |
| [`main-2`](../bench/wo02-overhead-2026-10-24/main-2/report.md) | 부하 | 11:29~11:54 | ABORTED | 대기 중 큐가 종료 코드 1로 멈췄다. 재계산 결과 INVALID: 1회차 off k6 게이트 INVALID(머신 93%, 연결 거부 15,673건·시간 초과 80건) |
| [`startup-1`](../bench/wo02-overhead-2026-10-24/startup-1/set.json) | 기동 | 11:54~11:55 | ABORTED | 첫 기동 전에 큐가 종료 코드 1로 멈췄다(머신 CPU 100% 대기 중). `set.json`만 있다 |
| `container-1` | 컨테이너 부하 | 11:55~12:04 | ABORTED | 출력이 없다. 큐를 손으로 다시 시작했다 |
| [`main-3`](../bench/wo02-overhead-2026-10-24/main-3/report.md) | 부하 | 12:04~14:55 | INVALID | INVALID: off CV 26.17% > 2%. 1회차 off는 재시도 4회를 다 쓴 뒤 배경 50.8%에서 남았다 |
| [`startup-2`](../bench/wo02-overhead-2026-10-24/startup-2/report.md) | 기동 | 14:55~15:06 | ABORTED(부분) | 37번째 기동에서 하네스 오류로 멈췄다(`AccessDeniedException`: 실행 디렉터리를 discarded로 옮기다 Windows 파일 잠금). 재계산 결과 FAIL: shop-boot3 CDS 끔 +2,088ms > 2,000ms. 기본 CDS 10쌍 완료, CDS 끔 8쌍, shop-heavy 미실행 |
| [`main-4`](../bench/wo02-overhead-2026-10-24/main-4/report.md) | 부하 | 15:12~15:41 | ABORTED | 손으로 멈췄다(배경 하한 16~17%가 한도 15%를 넘음). 웜업 폐기 3회. 재계산 결과 INVALID(남은 실행 없음) |
| [`main-5`](../bench/wo02-overhead-2026-10-24/main-5/report.md) | 부하 | 15:40~16:45 | ABORTED | 손으로 멈췄다(짧은 빈틈에 시작한 시도). 폐기 2회 중 1회는 측정 구간을 마쳤다(배경 30%) |
| [`main-6`](../bench/wo02-overhead-2026-10-24/main-6/report.md) | 부하 | 16:45~16:56 | ABORTED | 손으로 멈췄다(하네스를 앱 출력 스트리밍 방식으로 바꿈). 웜업 폐기 1회 |
| [`main-7`](../bench/wo02-overhead-2026-10-24/main-7/report.md) | 부하 | 16:58~17:48 | ABORTED | 하네스 오류로 멈췄다(`NoSuchFileException`: 대기 중 다른 작업의 빈 디렉터리 정리가 `02-on`을 지움, 디스크 C: 100%). 1회차 off는 완료(4번째 시도). 재계산 결과 INVALID: off CV 계산 불가 |
| [`main-8`](../bench/wo02-overhead-2026-10-24/main-8/report.md) | 부하 | 17:53~18:28 | ABORTED | 손으로 멈췄다(조용한 구간의 배경 하한이 17~23%라 한도를 22%에서 25%로 올림). 폐기 2회 중 1회는 측정 구간을 마쳤다(배경 23%) |
| [`main-9`](../bench/wo02-overhead-2026-10-24/main-9/report.md) | 부하 | 18:28~21:03 | INVALID | INVALID. 사유 3가지:<br>- off CV 4.39% > 2%<br>- 6회차(on) k6 게이트 FAIL: 60만 건 중 2건이 부하기 쪽 소켓 오류(WSAEADDRINUSE)로 실패<br>- 1분 피크 상대 CPU +17.68% > 8% |
| [`startup-3`](../bench/wo02-overhead-2026-10-24/startup-3/report.md) | 기동 | 21:03~21:14 | ABORTED(부분) | 33번째 기동 중 PM이 멈췄다(21:14:11Z, 사용자 결정). 재계산 결과 FAIL: shop-boot3 CDS 끔 +2,009ms > 2,000ms(6쌍). 기본 CDS 10쌍 완료, shop-heavy 미실행 |
| `vt`, `diag`, `otel` | 가상 스레드, 기동 분해, OTel 참조점 | - | 실행 안 함 | 큐 대기 중 중단 |

큐 기록: [`queue-log.txt`](../bench/wo02-overhead-2026-10-24/queue-log.txt), [`queue2-log.txt`](../bench/wo02-overhead-2026-10-24/queue2-log.txt)(손으로 멈춘 사유 포함). 하네스 콘솔 출력은 각 세트의 `console-log.txt`다.

## 6. 부하 세트 결과 (참고값, 판정 아님)

### 6.1 실행별 원시 표 (측정 구간 10분, 파일럿은 40초·70초)

측정 구간을 마친 모든 실행이다. 유지된 실행과 측정 구간 뒤 폐기된 시도를 모두 실었다.
- 배경 CPU = 머신 CPU − 앱 CPU − k6 CPU이고 논리 CPU 12개 대비 %다(파일럿과 `main-1`·`main-2`는 하네스가 아직 계산하지 않던 때라 `-`).
- 지연은 ms이고 k6 측정 구간 전체 요청 기준이다. 경로별 값은 각 `run.json`의 `measure.routes`에 있다.

| 세트/실행 | 변형 | 시작(UTC) | 시도 | 직전 CPU % | 측정 구간 배경 CPU % | 머신 CPU % | 앱 CPU 초 | CPU ms/요청 | 요청 | dropped | unexpected | p50 | p95 | p99 | p99.9 | k6 게이트 | 에이전트 스레드 CPU 초 | premain ms | 상태 | 원시 파일 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| pilot1/01-off | off | 10:43:40 | 1 | 12 | - | 57.1 | 29.84 | 0.7461 | 40,001 | 0 | 0 | 0.532 | 14.969 | 312.442 | 315.096 | PASS | - | - | 유지 | [run.json](../bench/wo02-overhead-2026-10-24/pilot1/runs/01-off/run.json) |
| pilot1/02-on | on | 10:45:02 | 1 | 56 | - | 47.6 | 29.42 | 0.7355 | 40,001 | 0 | 0 | 0.528 | 33.163 | 312.399 | 315.128 | PASS | 0.09 | 559 | 유지 | [run.json](../bench/wo02-overhead-2026-10-24/pilot1/runs/02-on/run.json) |
| pilot2c/01-off | off | 10:47:47 | 1 | 38 | - | 96.3 | 144.43 | 2.2232 | 64,964 | 5,037 | 0 | 128.105 | 988.148 | 1567.45 | 2304.54 | INVALID | - | - | 유지 | [run.json](../bench/wo02-overhead-2026-10-24/pilot2c/runs/01-off/run.json) |
| pilot2c/02-on | on | 10:51:13 | 1 | 97 | - | 74.7 | 142.71 | 2.0635 | 69,159 | 842 | 0 | 6.721 | 507.806 | 886.291 | 2231.86 | FAIL | 0.16 | 2479 | 유지 | [run.json](../bench/wo02-overhead-2026-10-24/pilot2c/runs/02-on/run.json) |
| main-1/01-off | off | 11:06:44 | 1 | 25 | - | 60.1 | 328.41 | 0.5486 | 598,594 | 1,405 | 6.5e-05 | 0.533 | 300.68 | 314.925 | 421.476 | FAIL | - | - | 유지 | [run.json](../bench/wo02-overhead-2026-10-24/main-1/runs/01-off/run.json) |
| main-2/01-off | off | 11:39:51 | 1 | 26 | - | 93.1 | 394.56 | 0.6815 | 578,921 | 21,081 | 0.03004 | 3.039 | 312.614 | 659.877 | 2424.44 | INVALID | - | - | 유지 | [run.json](../bench/wo02-overhead-2026-10-24/main-2/runs/01-off/run.json) |
| main-3/01-off | off | 12:59:10 | 5 | 21 | 50.8 | 61.7 | 378.33 | 0.6305 | 600,000 | 0 | 0 | 0.502 | 91.788 | 313.107 | 316.245 | PASS | - | - | 유지 | [run.json](../bench/wo02-overhead-2026-10-24/main-3/runs/01-off/run.json) |
| main-3/02-on | on | 13:46:28 | 4 | 24 | 23.4 | 31.6 | 279.08 | 0.4651 | 600,001 | 0 | 0 | 0.519 | 109.983 | 312.225 | 314.893 | PASS | 0.52 | 402 | 유지 | [run.json](../bench/wo02-overhead-2026-10-24/main-3/runs/02-on/run.json) |
| main-3/03-off | off | 14:00:25 | 1 | 15 | 14.8 | 22.5 | 248.55 | 0.4142 | 600,000 | 0 | 0 | 0.515 | 165.015 | 312.219 | 314.908 | PASS | - | - | 유지 | [run.json](../bench/wo02-overhead-2026-10-24/main-3/runs/03-off/run.json) |
| main-3/04-on | on | 14:14:14 | 1 | 13 | 14.5 | 22.4 | 260.39 | 0.4340 | 600,001 | 0 | 0 | 0.515 | 36.017 | 312.237 | 314.899 | PASS | 0.45 | 418 | 유지 | [run.json](../bench/wo02-overhead-2026-10-24/main-3/runs/04-on/run.json) |
| main-3/05-off | off | 14:28:10 | 1 | 13 | 6.7 | 14.5 | 244.77 | 0.4079 | 600,001 | 0 | 0 | 0.513 | 15.678 | 312.202 | 314.879 | PASS | - | - | 유지 | [run.json](../bench/wo02-overhead-2026-10-24/main-3/runs/05-off/run.json) |
| main-3/06-on | on | 14:41:59 | 1 | 5 | 8.4 | 16.0 | 256.69 | 0.4278 | 600,000 | 0 | 0 | 0.515 | 36.182 | 312.215 | 314.906 | PASS | 0.36 | 467 | 유지 | [run.json](../bench/wo02-overhead-2026-10-24/main-3/runs/06-on/run.json) |
| main-5/01-off-discarded-1 | off | 16:26:38 | 1 | 14 | 30.5 | 39.3 | 294.59 | 0.4910 | 600,000 | 0 | 0 | 0.522 | 300.206 | 312.318 | 314.986 | PASS | - | - | 폐기: background CPU 30% (limit 22%) | [attempt.json](../bench/wo02-overhead-2026-10-24/main-5/runs/01-off-discarded-1/attempt.json) |
| main-7/01-off | off | 17:18:30 | 4 | 11 | 17.5 | 25.2 | 251.00 | 0.4183 | 600,000 | 0 | 0 | 0.516 | 29.032 | 312.211 | 314.896 | PASS | - | - | 유지 | [run.json](../bench/wo02-overhead-2026-10-24/main-7/runs/01-off/run.json) |
| main-8/01-off-discarded-2 | off | 18:14:20 | 2 | 18 | 22.6 | 30.9 | 274.73 | 0.4579 | 600,001 | 0 | 0 | 0.518 | 181.292 | 312.236 | 314.911 | PASS | - | - | 폐기: background CPU 23% (limit 22%) | [attempt.json](../bench/wo02-overhead-2026-10-24/main-8/runs/01-off-discarded-2/attempt.json) |
| main-9/01-off | off | 18:32:19 | 1 | 16 | 21.1 | 29.3 | 268.69 | 0.4478 | 600,002 | 0 | 0 | 0.519 | 16.346 | 312.244 | 314.924 | PASS | - | - | 유지 | [run.json](../bench/wo02-overhead-2026-10-24/main-9/runs/01-off/run.json) |
| main-9/02-on | on | 18:47:03 | 1 | 9 | 20.6 | 29.1 | 281.28 | 0.4688 | 600,001 | 0 | 0 | 0.52 | 20.935 | 312.233 | 314.929 | PASS | 0.42 | 517 | 유지 | [run.json](../bench/wo02-overhead-2026-10-24/main-9/runs/02-on/run.json) |
| main-9/03-off-discarded-1 | off | 19:01:56 | 1 | 10 | 61.3 | 72.1 | 371.41 | 0.6190 | 600,001 | 0 | 0 | 0.537 | 40.12 | 312.694 | 315.858 | PASS | - | - | 폐기: background CPU 61% (limit 25%) | [attempt.json](../bench/wo02-overhead-2026-10-24/main-9/runs/03-off-discarded-1/attempt.json) |
| main-9/03-off-discarded-2 | off | 19:23:24 | 2 | 6 | 29.1 | 37.5 | 282.02 | 0.4700 | 600,001 | 0 | 0 | 0.52 | 83.232 | 312.42 | 315.15 | PASS | - | - | 폐기: background CPU 29% (limit 25%) | [attempt.json](../bench/wo02-overhead-2026-10-24/main-9/runs/03-off-discarded-2/attempt.json) |
| main-9/03-off | off | 19:40:23 | 3 | 17 | 16.0 | 23.8 | 249.28 | 0.4157 | 599,698 | 302 | 0 | 0.515 | 300.203 | 312.64 | 320.598 | PASS | - | - | 유지 | [run.json](../bench/wo02-overhead-2026-10-24/main-9/runs/03-off/run.json) |
| main-9/04-on | on | 19:59:09 | 1 | 13 | 10.3 | 17.8 | 247.62 | 0.4127 | 599,994 | 0 | 0 | 0.514 | 118.705 | 312.234 | 314.916 | PASS | 0.41 | 430 | 유지 | [run.json](../bench/wo02-overhead-2026-10-24/main-9/runs/04-on/run.json) |
| main-9/05-off | off | 20:14:17 | 1 | 14 | 24.8 | 32.9 | 269.77 | 0.4496 | 600,000 | 0 | 0 | 0.519 | 41.811 | 312.24 | 314.908 | PASS | - | - | 유지 | [run.json](../bench/wo02-overhead-2026-10-24/main-9/runs/05-off/run.json) |
| main-9/06-on | on | 20:50:39 | 1 | 11 | 19.9 | 28.5 | 286.02 | 0.4767 | 600,000 | 0 | 3e-06 | 0.52 | 186.941 | 312.312 | 314.977 | FAIL | 0.42 | 558 | 유지 | [run.json](../bench/wo02-overhead-2026-10-24/main-9/runs/06-on/run.json) |

k6 CPU는 측정 구간마다 294~420 CPU초였다(약 0.5~0.7코어, `run.json` `measure.k6CpuSeconds`). 앱 CPU와 같은 규모다.

### 6.2 CPU (N-01)

하네스 집계(`result.json`)

| 세트 | off 평균 앱 CPU 초 | on 평균 | 상대 차이 | 회차 쌍 95% 신뢰구간 | off CV | 1분 피크 상대 | 에이전트 스레드 CPU(최대) | 판정 |
|---|---|---|---|---|---|---|---|---|
| main-3 | 290.55 | 265.39 | −8.66% | −50.07 … +39.01% | 26.17% | −3.73% | 호스트의 0.0072% | INVALID |
| main-9 | 262.58 | 271.64 | +3.45%(요청당 0.4377 → 0.4527ms) | −5.44 … +12.14% | 4.39% | +17.68% | 호스트의 0.0059% | INVALID |

회차별 on/off 비율

| 세트 | 1회차 | 2회차 | 3회차 |
|---|---|---|---|
| main-3 | −26.23%(off 배경 50.8%) | +4.76%(배경 14.8/14.5%) | +4.87%(배경 6.7/8.4%) |
| main-9 | +4.69%(배경 21.1/20.6%) | −0.67%(배경 16.0/10.3%) | +6.02%(배경 24.8/19.9%) |

- 배경 CPU의 영향이 에이전트 효과보다 크다.
  - 같은 off 조건에서 배경 6.7%일 때 앱 CPU는 244.77초였다.
  - 배경 30.5%일 때는 294.59초(+20%), 61.3%일 때는 371.41초(+52%)였다(`main-9/03-off-discarded-1`과 같은 회차의 유지 실행 249.28초를 비교하면 +49%).
  - SMT 형제 스레드 공유, 캐시, 클럭 변화가 앱 자신의 CPU 시간을 늘린다. 차감 방식으로는 이 효과를 지울 수 없다.
- 1분 피크(+17.68%)는 배경이 출렁인 1분 창에서 나온 값이다. 이 머신에서는 의미가 없다.
- **프로토콜 밖 참고 분석**(판정 근거가 아니다).
  - 방법: 측정 구간을 마친 off 9개(배경 ≤ 35%, 유지·폐기 포함)로 "앱 CPU = a + b × 배경"을 맞췄다(요청 60만 건 기준 a = 221.00초, b = 2.156초/%p, 잔차 표준편차 6.33초 ≈ 2.5%). on 6개를 같은 배경의 예측 off와 비교했다.
  - 결과: +2.78, +3.20, +7.37, +6.01, +1.85, +8.39%. 평균 **+4.93%**, t 구간(자유도 5) ±2.82%, 즉 +2.1 … +7.8%다.
  - 한계: off·on이 시간과 세트가 다르고, 선형 가정이며, 배경에 ext-stub CPU가 들어 있다.
  - 이 분석과 `main-3` 조용한 2쌍(+4.76%, +4.87%)은 같은 방향을 가리킨다. no-op 상대 CPU는 4~5% 근처이고 5% 한도를 넘을 가능성을 배제할 수 없다.
- 에이전트 스레드(`argos-logger`, `argos-sender`, `argos-watch`)의 측정 구간 CPU는 0.36~0.52초였다(호스트의 0.006~0.007%, 보조 한도 1%). 오버헤드는 거의 전부 앱 스레드 안의 Advice·기록 경로에서 생긴다.

### 6.3 지연 (N-03)

| 세트 | 전체 p99 off → on(ms, 평균) | 차이 | 95% 신뢰구간 | 한도 | p99.9 차이 |
|---|---|---|---|---|---|
| main-3 | 312.509 → 312.226 | −0.284 | −1.571 … +1.004(차이 없음) | 15.625 | −0.445 |
| main-9 | 312.375 → 312.260 | −0.115 | −0.750 … +0.520(차이 없음) | 15.619 | −1.869 |

- 전체 p99는 off·on 모두 약 312ms다. 요청의 5%가 300ms 느린 경로(`/api/slow?ms=300`)라서 p99는 그 경로의 분포 안에 있다. 그래서 전체 p99 비교는 사실상 느린 경로만 보고, 빠른 경로(95%)의 증가는 거의 반영되지 않는다(11장 H-5).
- 경로별 p99(ms, `run.json` `measure.routes`), 배경이 낮았던 `main-3` 2·3회차(off → on)
  - orders.get: 0.657/0.649 → 0.655/0.666
  - orders.page: 0.696/0.670 → 0.710/0.688
  - pay: 0.699/0.659 → 0.708/0.678
  - orders.items: 1.054/1.047 → 1.059/1.063
  - error-500: 1.185/1.188 → 1.200/1.224
  - slow: 315.052/315.027 → 315.043/315.043
  - 증가는 최대 +0.04ms다.
- 배경이 높았던 실행은 off 쪽 경로별 p99가 오히려 컸다(예: `main-9/03-off` orders.get 10.0ms, `main-3/01-off` 3.5ms). 그래서 하네스의 경로별 평균 차이는 음수다(`report.md` "Per-route p99").
- Windows의 k6 요청 시간은 약 0.5ms 단위로 양자화된다(빠른 경로 p50이 0 또는 0.5ms). 1ms 미만 차이는 이 머신에서 분해되지 않는다.

### 6.4 메모리·스레드 (N-02, N-26, 7항)

측정 구간 직후 `GC.run` 2회 뒤의 값이다.

| 세트/실행 | 변형 | 에이전트 힙 바이트(`io.argos.agent.*`) | 인스턴스 | 메타스페이스 used KB | 로드 클래스 | NMT Internal KB | NMT Other KB | NMT 스레드 | Java 스레드(측정 끝) | NMT committed 합 KB |
|---|---|---|---|---|---|---|---|---|---|---|
| pilot1/01-off | off | 1,368 | 51 | 95,816 | 19,788 | 2,118 | 1,670 | 253 | 235 | 1,360,188 |
| pilot1/02-on | on | 333,040 | 8,306 | 103,071 | 21,658 | 2,447 | 1,670 | 255 | 237 | 1,395,069 |
| pilot2c/01-off | off | 1,368 | 51 | 96,078 | 19,533 | 2,149 | 1,828 | 233 | 226 | 1,354,006 |
| pilot2c/02-on | on | 709,680 | 16,778 | 103,160 | 21,284 | 2,438 | 1,828 | 236 | 229 | 1,366,612 |
| main-1/01-off | off | 0 | 0 | 96,017 | 19,772 | 2,130 | 1,670 | 252 | 234 | 1,365,844 |
| main-2/01-off | off | 0 | 0 | 95,876 | 19,637 | 2,129 | 1,670 | 252 | 235 | 1,366,034 |
| main-3/01-off | off | 0 | 0 | 96,275 | 19,928 | 2,133 | 1,670 | 252 | 235 | 1,365,681 |
| main-3/02-on | on | 651,240 | 15,462 | 103,134 | 21,593 | 2,452 | 1,670 | 255 | 237 | 1,381,414 |
| main-3/03-off | off | 0 | 0 | 95,921 | 19,735 | 2,122 | 1,670 | 252 | 234 | 1,363,585 |
| main-3/04-on | on | 1,323,288 | 30,607 | 103,198 | 21,648 | 2,452 | 1,670 | 255 | 237 | 1,380,499 |
| main-3/05-off | off | 0 | 0 | 95,852 | 19,719 | 2,123 | 1,670 | 252 | 234 | 1,361,528 |
| main-3/06-on | on | 1,260,200 | 29,188 | 103,001 | 21,513 | 2,441 | 1,670 | 255 | 237 | 1,380,088 |
| main-7/01-off | off | 0 | 0 | 96,004 | 19,798 | 2,124 | 1,670 | 252 | 234 | 1,363,784 |
| main-9/01-off | off | 0 | 0 | 95,875 | 19,711 | 2,125 | 1,670 | 252 | 234 | 1,363,774 |
| main-9/02-on | on | 1,442,920 | 33,300 | 103,063 | 21,544 | 2,448 | 1,670 | 255 | 237 | 1,378,856 |
| main-9/03-off | off | 0 | 0 | 95,923 | 19,741 | 2,122 | 1,670 | 252 | 234 | 1,362,620 |
| main-9/04-on | on | 563,064 | 13,463 | 102,983 | 21,491 | 2,453 | 1,670 | 255 | 237 | 1,380,390 |
| main-9/05-off | off | 0 | 0 | 96,171 | 19,899 | 2,124 | 1,670 | 252 | 235 | 1,363,661 |
| main-9/06-on | on | 330,216 | 8,218 | 103,177 | 21,608 | 2,451 | 1,670 | 256 | 238 | 1,381,622 |

원시 파일: 각 실행의 `run.json`(`memory`), `class-histogram-argos.txt`, `nmt-summary.txt`, `metaspace.txt`, `threads-argos-*.txt`.

| 지표 | 한도 | main-3 | main-9 |
|---|---|---|---|
| 에이전트 힙(N-02, 실행 중 최대) | 64MB | 1.26MiB | 1.38MiB |
| 메타스페이스 증가(N-26) | 32MB | +6.93MB | +6.92MB |
| NMT Internal+Other 증가(7항) | 16MB | +0.31MB | +0.32MB |
| 직접 메모리 NMT Other(N-26) | 8MB | 0 | 0 |
| 스레드 증가(7항) | 6 | +3.00 | +3.33 |
| 에이전트 스레드(N-26) | 6 | 3 | 3 |
| NMT committed 합 증가(참고) | - | +16.67MB | +16.54MB |
| 로드 클래스 증가(참고) | - | +1,791 | +1,764 |

- 이 지표들은 CPU 경쟁의 영향을 거의 받지 않고, 실행 간 편차도 작다(메타스페이스 102,983~103,198KB). 한도 대비 여유도 크다.
- 다만 세트가 INVALID이고 72시간 기울기는 범위 밖이므로 판정값이 아니다.
- 관찰: 부하가 끝나고 전체 GC를 2번 한 뒤에도 끝난 트랜잭션 객체가 남았다(11장 A-3).
  - `Transaction`·`TraceContext`·`StepBuffer` 245~1,385개씩
  - `WeakContextStore$WeakKey` 4,647~26,309개

### 6.5 할당·GC (02 §12 A-3 참고)

| 세트 | 요청당 할당 off → on | GC 일시정지(측정 구간 평균 횟수/합계 ms) off → on |
|---|---|---|
| main-3 | 71.22 → 79.25KB(+8.03KB, +11.3%) | 68.7회/162.8ms → 81.0회/451.8ms |
| main-9 | 71.92 → 79.47KB(+7.55KB, +10.5%) | 69.3회/136.3ms → 81.0회/456.6ms |

에이전트를 붙이면 요청당 약 7.5~8KB를 더 할당한다. 10분 동안의 GC 일시정지 합계는 약 3배가 된다(절대값은 0.46초/10분). 원인 후보는 약참조 키(`WeakContextStore`)의 참조 처리 비용이다. 원시 자료는 각 실행의 `run.json`이다(`gc.log`는 저장소에 복사하지 않았다).

### 6.6 웜업에서 폐기된 시도

측정 구간 전에 폐기된 시도다. 측정 구간을 마치고 폐기된 시도는 6.1 표에 있다.

| 세트/시도 | 변형 | 시작(UTC) | 직전 CPU % | 웜업 배경 CPU % | 웜업 머신 CPU % | 폐기 사유(하네스) |
|---|---|---|---|---|---|---|
| main-3/01-off-discarded-1 | off | 12:20:37 | 19 | 48.7 | 62.2 | background CPU 49% > 25% during the warm-up |
| main-3/01-off-discarded-2 | off | 12:36:58 | 25 | 46.8 | 60.7 | background CPU 47% > 25% during the warm-up |
| main-3/01-off-discarded-3 | off | 12:41:04 | 28 | 47.4 | 61.6 | background CPU 47% > 25% during the warm-up |
| main-3/01-off-discarded-4 | off | 12:53:47 | 30 | 48.6 | 62.8 | background CPU 49% > 25% during the warm-up |
| main-3/02-on-discarded-1 | on | 13:23:45 | 24 | 52.7 | 68.6 | background CPU 53% > 25% during the warm-up |
| main-3/02-on-discarded-2 | on | 13:34:31 | 30 | 58.0 | 73.8 | background CPU 58% > 25% during the warm-up |
| main-3/02-on-discarded-3 | on | 13:42:30 | 29 | 40.8 | 54.5 | background CPU 41% > 25% during the warm-up |
| main-4/01-off-discarded-1 | off | 15:28:44 | 11 | 16.6 | 27.1 | background CPU 17% > 15% during the warm-up |
| main-4/01-off-discarded-2 | off | 15:32:43 | 11 | 15.9 | 26.5 | background CPU 16% > 15% during the warm-up |
| main-4/01-off-discarded-3 | off | 15:36:39 | 15 | 16.2 | 26.5 | background CPU 16% > 15% during the warm-up |
| main-5/01-off-discarded-2 | off | 16:40:45 | 19 | 26.2 | 37.4 | background CPU 26% > 22% during the warm-up |
| main-6/01-off-discarded-1 | off | 16:50:43 | 9 | 22.6 | 33.9 | background CPU 23% > 22% during the warm-up |
| main-7/01-off-discarded-1 | off | 17:00:55 | 9 | 36.5 | 48.1 | background CPU 36% > 22% during the warm-up |
| main-7/01-off-discarded-2 | off | 17:09:01 | 5 | 24.3 | 35.3 | background CPU 24% > 22% during the warm-up |
| main-7/01-off-discarded-3 | off | 17:13:48 | 12 | 26.2 | 37.5 | background CPU 26% > 22% during the warm-up |
| main-8/01-off-discarded-1 | off | 17:56:17 | 15 | 22.1 | 33.0 | background CPU 22% > 22% during the warm-up |

원시 파일: `docs/bench/wo02-overhead-2026-10-24/<세트>/runs/<시도>/attempt.json`, `k6-warmup.json`.

### 6.7 컨테이너 파일럿 (9항)

`pilot2c`는 `--cpus 2 --memory 2g`, 웜업 20초, 측정 70초, 1쌍으로 돌렸다.
- off: 부하기 머신 96%로 C-46 INVALID, dropped 5,037/70,000, p50 128ms
- on: dropped 842로 FAIL
- 앱 CPU: off 144.4초 / on 142.7초. 측정 70초 동안 2코어를 다 쓴 셈이다(요청당 2.2ms, 프로세스 모드의 약 5배).
- 웜업 20초로는 JIT 컴파일이 끝나지 않는다. Docker Desktop VM 안에서 k6·ext-stub이 같은 CPU를 나눠 썼다.
- premain은 2,479ms였다(프로세스 모드 약 390ms).

이 파일럿으로는 N-29를 말할 수 없다. 전용 장비에서 3분 웜업 뒤에도 `--cpus 2`의 off가 1,000/s를 견디지 못하면, 9항의 부하율을 PM이 정해야 한다(11장 H-6).

## 7. 기동 결과 (8항, N-04·N-27·N-30, C-49)

### 7.1 하네스 집계

| 세트 | 앱 | CDS | n(쌍) | 포트 off 중앙값 ms | on | 증가 ms | 증가 % | ready off/on ms | Spring `Started` off/on 초 | premain 중앙값(최대) ms | 직전 CPU off/on % | +2초 | premain |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| startup-2 | shop-boot3 | 기본 | 10 | 7,344 | 9,065 | **+1,721** | +23.4% | 7,588 / 9,342 | 6.655 / 7.785 | 386.0 (433.5) | 5 / 6 | PASS | PASS |
| startup-2 | shop-boot3 | 끔 | 8 | 7,876 | 9,963 | **+2,088** | +26.5% | 8,134 / 10,301 | 7.031 / 8.496 | 407.5 (537.9) | 16 / 14 | FAIL | PASS |
| startup-3 | shop-boot3 | 기본 | 10 | 7,527 | 9,240 | **+1,713** | +22.8% | 7,788 / 9,515 | 6.848 / 7.913 | 388.7 | 13 / 14 | PASS | PASS |
| startup-3 | shop-boot3 | 끔 | 6 | 7,452 | 9,461 | **+2,009** | +27.0% | 7,704 / 9,800 | 6.680 / 8.080 | 390.6 | 12 / 13 | FAIL | PASS |
| pilot3s | shop-heavy | 기본 | 1 | 59,006 | 48,980 | −10,026 | −17.0% | - | 57.6 / 46.6 | 571.9 | 74 / 27 | (무효) | - |
| pilot3s | shop-heavy | 끔 | 1 | 37,657 | 39,383 | +1,726 | +4.6% | - | 36.0 / 37.2 | 548.8 | 29 / 23 | (무효) | - |

원시 파일: [`startup-2/result.json`](../bench/wo02-overhead-2026-10-24/startup-2/result.json), [`startup-3/result.json`](../bench/wo02-overhead-2026-10-24/startup-3/result.json), [`pilot3s/result.json`](../bench/wo02-overhead-2026-10-24/pilot3s/result.json), 기동마다 `runs/<NNN>-<앱>-<cds>-<변형>/run.json`. 기동 세트의 JVM은 `-Xms2g -Xmx2g -XX:+UseG1GC`다.

### 7.2 기동별 원시 값 (실행 순서)

`startup-2`

| 앱 | CDS | 변형 | n | 포트 바인딩 ms | ready ms | premain ms | 준비까지 앱 CPU 초 | 직전 CPU % | 제외 |
|---|---|---|---|---|---|---|---|---|---|
| shop-boot3 | default | off | 10 | 7596, 7295, 7376, 7351, 7392, 7198, 7189, 7379, 7337, 7306 | 7873, 7536, 7616, 7592, 7647, 7446, 7439, 7620, 7583, 7563 | - | 24.5, 24.5, 25.4, 25.5, 24.2, 23.6, 23.4, 25.2, 25.5, 25.2 | 6, 7, 6, 4, 5, 4, 4, 6, 4, 5 | - |
| shop-boot3 | default | on | 10 | 9092, 9110, 8883, 8974, 9090, 9070, 9038, 8934, 9062, 9068 | 9374, 9387, 9149, 9241, 9356, 9355, 9321, 9210, 9332, 9352 | 395, 390, 388, 382, 380, 387, 377, 385, 434, 380 | 32.2, 29.7, 30.2, 30.1, 32, 31.7, 31.2, 30.7, 29.2, 31.9 | 8, 4, 8, 7, 6, 4, 4, 5, 22, 10 | - |
| shop-boot3 | off | off | 8 | 7720, 8027, 7880, 7621, 7960, 7619, 7981, 7871 | 7969, 8287, 8143, 7928, 8219, 7872, 8244, 8125 | - | 25.0, 26.2, 26.6, 25.0, 24.9, 23.7, 27.2, 26.7 | 9, 15, 19, 17, 14, 14, 18, 28 | 037(포트 8062): 폐기로 옮기다 하네스 오류, 집계 제외 |
| shop-boot3 | off | on | 8 | 10067, 9628, 9285, 9924, 10002, 9772, 10015, 10051 | 10422, 9965, 9626, 10258, 10345, 10125, 10344, 10402 | 412, 410, 412, 538, 397, 393, 395, 405 | 35.2, 32.8, 32.0, 33.8, 34.1, 32.7, 35.0, 34.7 | 17, 18, 7, 10, 9, 10, 17, 25 | 035 첫 시도(포트 10506): 배경 30% > 25%로 폐기 |

`startup-3`

| 앱 | CDS | 변형 | n | 포트 바인딩 ms | ready ms | premain ms | 준비까지 앱 CPU 초 | 직전 CPU % | 제외 |
|---|---|---|---|---|---|---|---|---|---|
| shop-boot3 | default | off | 10 | 7953, 7208, 7520, 8861, 8166, 7268, 7248, 7568, 7347, 7534 | 8235, 7453, 7783, 9185, 8400, 7504, 7526, 7806, 7604, 7792 | - | 26.0, 24.6, 25.7, 29.1, 25.9, 24.8, 23.0, 25.6, 24.4, 25.7 | 12, 13, 13, 20, 15, 13, 17, 16, 10, 11 | - |
| shop-boot3 | default | on | 10 | 9240, 9251, 9074, 9278, 9467, 9239, 9239, 9212, 9086, 9494 | 9503, 9522, 9364, 9574, 9733, 9508, 9527, 9474, 9375, 9778 | 406, 384, 389, 389, 402, 384, 408, 383, 383, 389 | 31.2, 31.5, 31.9, 31.7, 33.5, 31.2, 33.0, 32.0, 30.7, 32.7 | 13, 17, 15, 10, 14, 14, 15, 15, 13, 14 | - |
| shop-boot3 | off | off | 6 | 7723, 8064, 7441, 7437, 7415, 7463 | 7992, 8315, 7691, 7691, 7668, 7717 | - | 25.9, 26.4, 24.0, 24.6, 23.7, 25.6 | 13, 13, 16, 11, 12, 9 | 033: 기동 중 중단(`command.txt`만 있음) |
| shop-boot3 | off | on | 6 | 9247, 9863, 9514, 9643, 9380, 9407 | 9585, 10264, 9864, 9979, 9706, 9736 | 406, 390, 402, 390, 387, 391 | 32.0, 34.5, 33.4, 34.5, 31.5, 32.3 | 12, 10, 14, 13, 14, 13 | - |

`pilot3s`(칸당 1회, 직전 CPU가 높아 무효)

| 앱 | CDS | 변형 | 포트 바인딩 ms | ready ms | premain ms | 직전 CPU % |
|---|---|---|---|---|---|---|
| shop-boot3 | default | off / on | 14681 / 20050 | 15199 / 20640 | - / 848 | 94 / 93 |
| shop-boot3 | off | off / on | 15913 / 24207 | 16479 / 26227 | - / 794 | 73 / 69 |
| shop-heavy | default | off / on | 59006 / 48980 | 59395 / 49343 | - / 572 | 74 / 27 |
| shop-heavy | off | off / on | 37657 / 39383 | 37958 / 39876 | - / 549 | 29 / 23 |

### 7.3 해석

- shop-boot3의 기동 증가는 두 세트에서 거의 같았다(기본 CDS +1,721/+1,713ms, CDS 끔 +2,088/+2,009ms). 분포도 좁다(기본 CDS on 8,883~9,494ms). 직전 CPU는 4~28%로 부하 세트보다 조용했다.
- 따라서 이 머신에서는 공유 상태의 잡음보다 실제 비용으로 보인다. 다만 절대값은 CPU 클럭과 코어 성능에 따라 다르다.
- 비용 분해
  - premain은 약 0.39초다(N-30 ≤ 500ms 안).
  - 나머지 약 1.3초(CDS 끔이면 약 1.6초)는 앱이 클래스를 로드하는 동안 생긴다.
  - 준비까지 앱 CPU가 기본 CDS에서 +6.1초(24.85 → 30.95초, `startup-2`)·+6.2초(25.65 → 31.8초, `startup-3`), CDS 끔에서 +7.8~8.4초 늘었다.
  - 로드 클래스는 약 1,800개 늘었다(6.4). 클래스 로드 경로의 매칭·변환(ignore 매처, 서브타입 매칭, `TypePool` 해석)이 주 비용으로 추정된다.
- 이 분해를 확인하려던 `diag` 세트는 돌리지 못했다. `diag`는 premain만(모든 모듈 끔), 이름 매칭 모듈만, 전체 모듈을 비교한다.
- heavy 앱(의존성 200 jar 이상, 클래스 3만 개 이상)은 로드 클래스가 더 많다. 클래스당 비용이 같다면 증가분은 shop-boot3보다 클 수 있다. Gate 0 "+2초 이하(heavy 포함)"의 가장 큰 위험이다.

## 8. Gate 0 기준 대비 (참고값만)

**Gate 0 오버헤드는 이 머신에서 판정하지 않는다.** 아래 "참고 판단"은 전용 장비 재실행의 우선순위를 정하기 위한 것이다. 기준 충족을 주장하지 않는다(01 §7.3 11항: 수치 없는 "충족"은 무효이고, 이 수치는 유효 세트의 수치가 아니다).

| 03 §4 Gate 0 기준 | 한도 | 이 머신 참고값 | 근거 파일 | 참고 판단 |
|---|---|---|---|---|
| no-op 상대 CPU(N-01 평균) | ≤ 5% | `main-9` +3.45%(95% CI −5.44 … +12.14%), `main-3` 조용한 쌍 +4.76%·+4.87%, 배경 보정 +4.9%(+2.1 … +7.8%) | `main-9/result.json`, `main-3/runs/0[3-6]-*/run.json` | 판정 불가(INVALID). 5% 근처, **위험** |
| p99 증가(N-03) | ≤ max(1ms, off p99의 5% ≈ 15.6ms) | 전체 p99 −0.12ms(`main-9`)·−0.28ms(`main-3`), 신뢰구간 0 포함. 경로별 p99 증가 ≤ +0.04ms(조용한 쌍) | `main-*/result.json`, `run.json` `measure.routes` | 판정 불가(INVALID). 한도 안으로 보이나 전체 p99가 둔감하다(H-5) |
| 기동 +2초(N-04, heavy 포함) | 기본 앱 ≤ +2,000ms, heavy 상대 ≤ +10% | shop-boot3 기본 CDS +1,721·+1,713ms, `-Xshare:off` +2,088·+2,009ms. heavy 미측정 | `startup-2/result.json`, `startup-3/result.json` | 판정 불가(ABORTED). CDS 끔은 한도 초과, heavy 미측정. **가장 큰 위험** |
| 컨테이너 `--cpus=2` 수치 병기(N-29 ≤ 8%) | 병기 | 없음(70초 파일럿, INVALID) | `pilot2c/result.json` | 측정 안 됨 |
| (참고) N-02·N-26·7항 메모리 | 64MB, 32MB, 8MB, 16MB, 6 | 1.4MB, +6.9MB, 0, +0.3MB, 스레드 3 | 6.4 | 여유 큼. 72시간은 WO-52 |
| (참고) N-30 premain | ≤ 500ms | 기동 세트 중앙값 386~408ms, 부하 세트 402~558ms(NMT 켬), 컨테이너 파일럿 2,479ms | 7.1, 6.1 | 프로세스 모드는 한도 안. 바쁜 머신·2 CPU 컨테이너에서 위험 |

### 8.1 이 머신에서 판정할 수 없는 이유 (근거)

1. **부하기 분리 불가(1항)**: k6가 같은 머신에서 측정 구간마다 294~420 CPU초를 썼다. 앱과 같은 규모이고, 앱과 같은 코어·캐시를 나눠 쓴다.
2. **배경 CPU**: 측정 구간 평균 배경 CPU가 유지된 실행에서도 6.7~50.8%였다. 조용한 시간을 기다린 뒤에도 하한이 16~23%였다(Defender, WSL2 VM, 다른 작업의 빌드·적재, 사용자 프로그램).
   - off 앱 CPU가 배경에 따라 244.8~378.3초로 움직였다(6.2). 에이전트 효과(약 5%)의 여러 배다.
   - off CV 2%(4항)를 맞추려면 off 3회의 배경이 거의 같아야 하는데, 공유 머신에서는 통제할 수 없다.
3. **메모리 압박**: 측정 구간 중 가용 메모리가 0.46GB(`main-9/02-on`), 최악 15MB(`main-1`)까지 떨어졌다. 그래서 힙을 1GB로 줄였다(2항 편차).
4. **Windows 고유 요인**
   - k6 시간 해상도가 약 0.5ms다.
   - 루프백 임시 포트 고갈로 k6 쪽 연결이 실패했다(`main-9/06-on` 2건 WSAEADDRINUSE).
   - 실행 디렉터리 이동이 파일 잠금으로 실패했다(`startup-2`).
   - 디스크가 가득 찼을 때 다른 작업의 정리 스크립트가 빈 디렉터리를 지웠다(`main-7`).

## 9. 전용 장비 재실행 절차 (사람용)

Gate 0 판정과 WO-02 수동 수락("사람이 1회 재실행해 재현")을 위한 절차다. 하네스 명령은 이번에 쓴 것과 같고, 바뀌는 것은 장비와 옵션 값뿐이다.

### 9.1 준비물

- **앱 서버 1대**
  - 전용 Linux x86_64. 다른 서비스·크론·자동 업데이트를 끈다.
  - 컨테이너 세트에서 앱 컨테이너 2 CPU와 k6·ext-stub 컨테이너가 겹치지 않도록 물리 코어 6개 이상, RAM 16GB 이상을 권장한다.
  - 필요 소프트웨어: JDK 21(측정 JDK 빌드를 고정·기록한다. 이번에는 Microsoft Build of OpenJDK 21.0.10+7), git, bash, curl, Docker Engine(cgroup v2), ssh 클라이언트.
- **부하기 1대**
  - Linux. 앱 서버와 같은 L2 스위치에 둔다.
  - k6 v2.3.0이 `PATH`에 있어야 한다. 저장소를 받아 `test/agent/overhead/fetch-k6.sh`를 돌리고 `sudo install test/agent/overhead/build/k6/k6 /usr/local/bin/k6`로 설치한다. SHA-256은 스크립트가 확인한다.
- 앱 서버에서 부하기로 키 인증 ssh가 되어야 한다. `ssh -o BatchMode=yes <user>@<부하기> 'k6 version && head -n1 /proc/stat'`가 암호 없이 끝나야 한다.
- 방화벽은 부하기에서 앱 서버의 임시 포트(TCP)로 들어오는 연결을 허용한다. 하네스가 실행마다 빈 포트를 고른다.
- OTel 참조점용 `opentelemetry-javaagent-2.32.0.jar`
  - Maven Central `io.opentelemetry.javaagent:opentelemetry-javaagent:2.32.0`에서 받는다.
  - 2026-10-24에 받은 파일의 SHA-256은 `f787eb6c7f3d18e69a431e108a15278d25ee37f83d68b678f621e063f3988f82`다.
  - Apache-2.0이고 측정에만 쓰며, 배포물에 넣지 않는다.

### 9.2 앱 서버 설정과 빌드

```bash
sudo cpupower frequency-set -g performance          # 01 §7.3 1항
cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_governor
cat /sys/kernel/mm/transparent_hugepage/enabled      # 값은 기록만 한다(하네스도 set.json에 남긴다)

git clone <저장소> argos && cd argos && git checkout <판정할 커밋>
export JAVA_HOME=/opt/jdk-21.0.10                    # 측정 JDK
./gradlew -Pargos.ui.skip=true :test:agent:overhead:check
./gradlew -Pargos.ui.skip=true :test:agent:overhead:overheadJar :agent:agent-dist:agentJar \
  :sample-apps:ext-stub:sampleAppJar :sample-apps:shop-boot3:sampleAppJar :sample-apps:shop-heavy:sampleAppJar
./gradlew --stop                                     # 측정 중 Gradle 데몬이 없어야 한다
sha256sum agent/agent-dist/build/libs/argos-agent-*.jar sample-apps/*/build/sample-app/*.jar
```

공통 변수(저장소 루트에서)

```bash
R=test/agent/overhead/run-overhead.sh
J="$JAVA_HOME/bin/java"
LOAD=<user>@<부하기>                # ssh 대상
APP_IP=<앱 서버 IP>                 # 부하기에서 닿는 주소
OUT=build/overhead/$(date -u +%Y%m%d)-$(git rev-parse --short HEAD)
SSH=(--k6-ssh "$LOAD" --app-address "$APP_IP")
QUIET=(--max-load 10 --max-background 10)   # ext-stub CPU도 배경에 들어가므로 5%는 너무 빡빡하다
```

### 9.3 실행 순서

각 명령의 종료 코드는 0 PASS, 3 FAIL, 4 INVALID다. 결과는 `<out>/report.md`에 있다. 시간은 이 노트북의 실측(앱 기동, 실행 사이 대기 포함)을 바탕으로 한 추정이고, 조용한 장비에서는 대기가 없다.

0. **ssh 경로 확인용 짧은 파일럿**(필수, 약 5분)
   ```bash
   $R --no-build load --out $OUT/pilot --java "$J" "${SSH[@]}" --rounds 1 --warmup 20s --duration 40s
   ```
   `--k6-ssh` 경로는 이번에 실제 2대 구성으로 돌린 적이 없다(H-4). `pilot/report.md`에서 다음을 확인한 뒤 본 세트로 넘어간다.
   - 부하기 CPU(`loader CPU %`)가 채워졌는가
   - k6 요약이 돌아왔는가
   - 게이트가 PASS인가
   이 파일럿은 판정에 쓰지 않는다(CV 계산 불가로 INVALID가 정상이다).
1. **main**(2~7항, Gate 0 CPU·p99, 약 85분)
   ```bash
   $R --no-build load --out $OUT/main --java "$J" "${SSH[@]}" "${QUIET[@]}" --label "main: dedicated"
   ```
   기본값: `--heap 2g`, 3회차 × off/on, 웜업 3분, 측정 10분, 1,000/s.
2. **startup**(8항, N-04·N-27·N-30, Gate 0 기동, 약 50분)
   ```bash
   $R --no-build startup --out $OUT/startup --java "$J" --max-load 10 --label "startup: dedicated"
   ```
   기본값: shop-boot3·shop-heavy × 기본 CDS·`-Xshare:off` × off/on 10회, `-Xms2g -Xmx2g -XX:+UseG1GC`.
3. **diag**(기동 비용 분해, 판정 아님, 약 25분)
   ```bash
   $R --no-build startup --out $OUT/diag --java "$J" --apps shop-boot3,shop-heavy --cds default --runs 5 \
     --variants off,on,nomod,nameonly \
     --variant-defs "nomod:instrumentation.disabled=servlet,spring-webmvc,jdbc,executor,httpclient-jdk|nameonly:instrumentation.disabled=servlet,jdbc" \
     --max-load 10 --label "diag: premain only / name-only matchers / all modules"
   ```
4. **container**(9항, N-29, 약 90분)
   ```bash
   $R --no-build load --out $OUT/container --mode container --cpus 2 --memory 4g --heap 2g "${QUIET[@]}" \
     --label "container: --cpus 2 --memory 4g"
   ```
   하네스 제약상 `--k6-ssh`는 process 모드 전용이다. 이 세트의 k6·ext-stub는 같은 호스트의 컨테이너로 돌고, 앱 컨테이너 밖의 코어를 쓴다(1항 편차로 기록한다). off 실행이 C-46 게이트를 통과하지 못하면 결과를 쓰지 말고 PM에게 9항 부하율 결정을 요청한다(H-6).
5. **vt**(10항, N-28, 약 85분)
   ```bash
   $R --no-build load --out $OUT/vt --vt --java "$J" "${SSH[@]}" "${QUIET[@]}" --label "vt: SAMPLE_VT=true"
   ```
6. **otel**(11항 참조점, 판정 아님)
   ```bash
   $R --no-build load --out $OUT/otel --java "$J" "${SSH[@]}" "${QUIET[@]}" \
     --variants off,on,otel --otel-agent /path/opentelemetry-javaagent-2.32.0.jar --label "otel reference"
   ```
   프로토콜 길이 그대로 3회차 × 3변형이면 약 2시간이다. 시간이 부족하면 `--rounds 2 --warmup 2m --duration 5m`(약 50분)로 줄이고, 단축했다고 기록한다.

합계는 약 7.5시간(OTel 단축 시 약 6.5시간)이다. 세트 사이에 장비를 쉬게 할 필요는 없다(하네스가 실행마다 조용한 구간을 확인한다).

### 9.4 결과 처리

1. 세트가 INVALID면(예: off CV > 2%) 원인을 없앤 뒤 **세트 전체를** 다시 돌린다. 실행을 골라 쓰지 않는다. 판정을 다시 계산할 때는 `$R --no-build report --out $OUT/<세트>`를 쓴다.
2. 원시 자료를 `docs/bench/<YYYY-MM-DD>-<커밋>/`에 넣는다(01 §7.3 11항). 이번 디렉터리의 README 규칙을 따른다.
   - 넣는 것: `set.json`·`result.json`·`report.md`·하네스 콘솔 출력, 실행별 `run.json`·`attempt.json`·k6 요약·`machine.json`·NMT·메타스페이스·`argos-*` 스레드와 `io.argos.agent.*` 히스토그램 발췌
   - 빼는 것: gc 로그, 앱 로그, jar, 1MB 초과 파일
   - 로컬 경로의 사용자 이름은 지운다.
3. 이 문서 8장 표의 "참고값" 칸을 전용 장비 값으로 바꾸고, Gate 0 판정(PASS/FAIL)과 근거 파일을 적는다. 결과가 FAIL이면 해당 N 항목과 함께 PM에게 보고한다.
4. WO-02 수동 수락의 "사람이 1회 재실행해 재현"은 같은 장비에서 다른 날(또는 다른 사람이) main과 startup을 한 번 더 돌린다. 재현 판단 기준은 설계 문서에 없다. 제안: 두 실행이 같은 Gate 0 판정을 내고, 상대 CPU 차이가 첫 세트의 95% 신뢰구간 안이며, 기동 증가 중앙값 차이가 ±5% 안이면 재현으로 본다. 기준은 PM이 확정한다.

## 10. 측정하지 못한 것

| 항목 | 상태 | 이유 |
|---|---|---|
| 유효한 A-B×3 부하 세트(4항) | 없음 | 공유 머신에서 off CV 2%를 맞추지 못함(8.1) |
| shop-heavy 기동(8항, N-04 heavy·N-27) | 파일럿 1회만 | 기동 세트가 shop-boot3 단계에서 두 번 중단됨 |
| 컨테이너 `--cpus=2`(9항, N-29) | 70초 파일럿만 | `container-1` 중단, 큐 정지 |
| 가상 스레드(10항, N-28) | 없음 | 큐 정지 |
| OTel Java Agent 참조점(11항) | 없음 | 큐 정지(jar는 받아 두었음) |
| 기동 분해(diag) | 없음 | 큐 정지 |
| 부하기 분리(1항)·`--k6-ssh` 실제 실행 | 없음 | 머신 1대 |
| 프로파일 정책 "기본값"(3항) | 없음 | PoC에 정책 없음(WO-10·16) |
| 72시간 힙 기울기(7항, N-02) | 범위 밖 | WO-52 |
| 에이전트 자가 진단 CPU(5항) | 대체 | 자가 진단 메트릭이 WO-10에서 생김. `jcmd` 스레드 CPU로 대체 |
| JFR 프로파일(진단) | 없음 | 스크립트만 만들고 실행하지 않음 |

## 11. 관찰과 후속 제안

하네스(이번 정리 범위 밖이라 고치지 않았다. WO-05에서 다룬다)

- **H-1** 기동이 0회인 기동 세트를 `report`로 다시 계산하면 PASS(사유 없음)가 나온다(`startup-1`로 확인, 결과 파일은 지움). 행이 없으면 INVALID로 해야 한다.
- **H-2** 부하기 쪽 소켓 오류(Windows WSAEADDRINUSE, Linux EADDRNOTAVAIL 등)도 "unexpected"로 세어 on 실행 FAIL이 된다(`main-9/06-on`, 60만 건 중 2건). C-46 취지로는 부하기 문제이므로 INVALID가 맞다. 해석은 PM이 정한다.
- **H-3**(측정 중에 고침) Windows에서 실행 디렉터리를 discarded로 옮기다 파일 잠금으로 세트 전체가 멈췄다(`startup-2`). 지금은 `FileTools.moveDirectory`가 최대 30초 재시도한다. 대기 중 다른 작업이 실행 디렉터리를 지운 문제(`main-7`)는 대기 뒤 디렉터리를 다시 만드는 것으로 고쳤다. `startup-3`·`main-9`는 고친 jar로 돌았다.
- **H-4** `--k6-ssh` 경로(원격 k6, 요약 회수, 원격 `/proc/stat` 포화 판정)는 단위 테스트도 실제 실행도 없다. 9.3의 0단계 파일럿으로 먼저 확인한다.
- **H-5** 전체 p99가 느린 경로(5%, 300ms)에 묶여 N-03 판정이 빠른 경로의 지연 증가를 거의 보지 못한다. 경로별 p99 판정을 추가할지(또는 느린 경로를 제외한 p99) PM이 정한다. 지금은 보고만 한다.
- **H-6** 컨테이너 파일럿에서 앱이 2 CPU를 다 썼다(웜업 20초). 3분 웜업 뒤에도 `--cpus 2`의 off가 1,000/s를 견디지 못하면 9항의 부하율을 PM이 정해야 한다.

에이전트(해당 레인·WO에 넘긴다)

- **A-1(기동, 코어·계측, Gate 0 위험)**: shop-boot3 기동이 기본 CDS +1.7초, CDS 끔 +2.0~2.1초 늘었다. premain(0.39초)을 빼면 클래스 로드 중 매칭·변환 비용이 대부분으로 보인다(앱 CPU +6~8초). heavy 앱은 더 클 수 있다. 전용 장비 재실행 전에 코어 담당이 클래스 로드 경로를 프로파일링해 줄일지, 아니면 재실행 결과를 먼저 볼지 PM이 정한다. 후보는 ignore 조기 종료, 이름 기반 사전 필터, 서브타입 매칭 범위, `TypePool` 캐시다.
- **A-2(할당·GC)**: 요청당 할당 +7.5~8.0KB(+10.5~11.3%), 측정 구간 GC 일시정지 합계 약 3배(136~163 → 452~457ms/10분).
- **A-3(힙 상주, N-02 72시간 기울기 위험)**: 부하가 끝나고 GC를 2번 한 뒤에도 끝난 `Transaction`·`TraceContext`·`StepBuffer`가 245~1,385개, `WeakContextStore$WeakKey`가 4,647~26,309개 남았다(`class-histogram-argos.txt`). 크기는 1.4MB 이하이고 저장소 상한(10만)이 있다. 다만 끝난 트랜잭션이 컨텍스트 저장소 값으로 계속 도달 가능하다는 뜻일 수 있다. WO-52의 72시간 측정 전에 코어(WO-10)가 확인한다.
- **A-4(premain, N-30)**: 프로세스 모드 중앙값은 약 390ms다. 바쁜 머신에서는 549~848ms, 2 CPU 컨테이너 파일럿에서는 2,479ms였다. 9항 재실행에서 다시 확인한다.
