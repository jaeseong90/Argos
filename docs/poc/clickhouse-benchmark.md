# ClickHouse 저장소 PoC 결과 (P-4, WO-03)

| 항목 | 내용 |
|---|---|
| 상태 | 로컬 참고 측정 완료. Gate 0 판정은 목표 하드웨어(`argos-storage`)에서 사람이 T-ST-001~020을 수행한 뒤 확정한다(13장). ADR-0007(C-50~C-71) 반영은 17장(WO-03F) |
| 날짜 | 2026-10-24(리뷰 r1 수정과 통합 재검증 포함, 16장), 2026-11-02(WO-03F, 17장) |
| 작업 지시 | `docs/04-work-orders.md` WO-03 |
| 입력 설계 | 13 §3·§4·§5·§6.3·§7·§15·§17, 18 §9, 02 §11 P-4·§12 A-6·A-9·A-10·A-11, C-1·C-2·C-6·C-16·C-17, 01 N-07·N-18·N-19 |
| 번들 ClickHouse | **26.8.34.13** LTS(`v26.8.34.13-lts`, 2026-10-22), `clickhouse/clickhouse-server:26.8.34.13@sha256:8b28eb09479c36c4533eec7a6268917d04ba3a6fd90e3f4205f70e761b6d7779` |
| 결정 | [ADR-0006](../adr/0006-storage-poc-decisions.md)(결정 제안, Accepted with amendment) → [ADR-0007](../adr/0007-storage-alignment-decisions.md)(C-50~C-71, Accepted provisional) |

## 0. 요약

> **WO-03F(2026-11-02)**: ADR-0007의 결정(C-53 `quantilesDD`, C-54 ZSTD(1) 코덱, C-55·C-56 bloom 리터럴과 템플릿 지시자, C-59 재구축 절차, C-60 계정 권한, C-61 토큰, C-65·C-57 정합 SQL, C-67 열 이름, C-71)을 V001·`deploy/clickhouse`·저장소 도구에 반영했다. 아래 1~16장의 수치는 **반영 전 스키마**(LZ4·tDigest·옛 열 이름)로 잰 값이다. 반영 내용, 다시 잰 값, 새로 찾은 충돌은 17장에 있다.

- **환경 한계**: 목표 환경(사내 8코어/16GB/NVMe, 13억 행)이 없어 공유 노트북(Ryzen 5 5600H, 15.3GiB, Docker Desktop/WSL2)에서 **1/100(CI 자동 기준)**과 **1/20(로컬 최대 규모)**로 측정했다. 같은 머신에서 WO-02가 JVM 벤치·빌드를 동시에 돌렸고, 측정마다 머신 CPU 부하를 함께 적었다(대부분 20~100%). 이 문서의 모든 수치는 **참고값**이다.
- **자동 기준(CI 1/100)**: 생성·적재(적재 직후 원본 5개 테이블·늦은 행·Σ app_stat_1m이 manifest와 정확히 일치), T-ST-030(Q12 0), T-ST-031(Q13: 범위 합계·raw ↔ 1m ↔ 1h·raw ↔ 1m 분 단위 충족, **일 단위(야간) D-12는 manifest 기대값과 일치(자정 귀속 차이만), PM 결정 대기**), T-ST-032(Q15), T-ST-033(Q16 늦은 도착 2만 행), T-ST-053(`argos_reader` 상한·`system.query_log`) 모두 녹색(7.1절). `pr.yml` integration 잡이 1/100 프로파일(`-Pqueries.profile=ci`)로 돈다.
- **로컬 1/20 결과**(1.7억 행, 30일 중 36시간 구간, TPS·카디널리티는 목표와 같음):
  - 적재 33.5만 행/s(벌크), DelayedInserts 0, 파티션당 parts 최대 17, MemoryTracking 피크 1.2GiB(5GiB 컨테이너).
  - Q1~Q11 웜 중앙값 10~222ms(기준 0.5~2초) **전부 충족**. 콜드(서버 재기동 + 페이지 캐시 drop) 최대 568ms.
  - Q12 0, Q13 범위 합계 0, Q15·Q16 0. **Q13 일 단위(야간) 검사는 자정을 넘는 SQL 때문에 불일치**(D-12 설계 이슈, 7.3절).
  - 압축률: profile 7.16 ≥ 5, metric 11.84 ≥ 8 충족. **xlog 2.56 < 3 미달**(B_x 82.7B, data_compressed 기준. 디스크 기준 86.0B, 일 증가량 약 5.6GB로 13 §14 표 대비 +6%, 5.1절). 원인은 랜덤 id 열과 `CODEC(NONE)`/LZ4 고정 열(5.2절). 제안 코덱(서버 기본 ZSTD(1) + 열 코덱 조정)으로 xlog **3.76(55.7B/행)**, profile 11.2, metric 14.4.
  - Q14: 1m p99 값 오차는 1% 이상치 분포에서 세 함수 모두 74%로 기준 자체가 불량조건이고, 순위 오차는 0.13pp 이하다. 1h·1d 정확도와 재구축 확정값 동일까지 보면 **BFloat16 제안**(8.3절). 단 재구축 결정성 실측에서 BFloat16 1m 상태 **바이트**는 재구축마다 달랐고(값은 동일), 바이트까지 같은 것은 DD뿐이었다(D-14 해석은 PM 결정, 14.2 2항).
  - 리플레이 500 → 2,000 → 5,000 TPS(각 10분): 적재 지연 p99 3.04~3.07초(기준 5초. 1초 tick으로 최대 약 1초 과대 측정이고 WAL 파일 기록·fsync는 빠짐. 100ms tick 재측정은 p99 2.18~2.27초, 4.2절), parts 최대 14, DelayedInserts 0. 같은 동안 Q17(10명 × Q1·Q3·Q7, 5초 간격, 8분씩) 쿼리 p95 82ms(2,000 TPS)·103ms(5,000 TPS), 메모리 피크 422MiB. 이 1/20 Q17은 **쿼리 창이 시작 시점에 고정돼 측정 중 적재된 최신 parts를 읽지 않았다**. 러너를 고쳐(live 창, Q7은 마지막 2분 원본 보정) 1/100 + 리플레이 2,000·5,000 TPS 각 8분으로 다시 재니 쿼리 p95 89·119ms, 적재 지연 p99 2.14·2.18초(100ms tick), parts 최대 14, DelayedInserts 0이었다(9장).
  - Q18·Q19(1/100 통제 재측정): 같은 내용 사본 2개·같은 대상·순서 교대에서 1차(run P)는 `DELETE IN PARTITION`이 같은 사본의 `DELETE WHERE`보다 29~38% 짧았지만, 통합 재검증 재실행에서는 반대로 WHERE가 7~31% 짧아 **두 형식의 시간 차이는 결론 없음**이다(이전 판 "약 2배"는 교란된 비교라 철회, 10.1절). Q19에 보존 기간을 실제로 바꾸는 `MODIFY TTL` materialize 1 단계를 넣었다(1/100 1.1초, 30일 13억 행 추정 2~26분으로 10분 기준 충족이 불확실, 10.2절).
  - **Q17은 축소 부하에서 충족**이다: 5,000 TPS 단계의 xlog·step·통계가 약 13.8k 행/s(xlog 5,000 + step 약 8,500 + 통계 약 260)로 N-06 요구 약 65k 행/s의 **약 21%**다(9장). N-06 부하를 재현하는 생성기 옵션(300 인스턴스·활성 SQL 200·step 50,000/s)을 추가했고 생성기 단독으로 약 66k 행/s(step 50,018·통계 약 5,400·metric 6,000)를 실시간으로 냈다(4.2절). 통계 10,000행/s는 13 §14 전제로는 상한 6,000행/s라 닿지 않는다(14.2 9항). N-06 부하 판정은 목표 하드웨어(13장 6)에서 한다.
- **통합 재검증(리뷰 r1 뒤, 16장)**: 전체 `./gradlew build`와 CI와 같은 `./gradlew integrationTest -Pqueries.profile=ci`가 녹색이다. 이 과정에서 두 통합 결함을 고쳤다: (1) 병렬 통합 테스트에서 Windows Docker Desktop named pipe 검사 경쟁으로 스키마 테스트 JVM이 매번 "Docker 환경 없음"으로 전부 실패 → `DockerStartup`(첫 Docker 접근을 잠금 파일로 순서화하고 pipe 대기), (2) 1/100 데이터에서 `--bloom-days 3`이면 Q05/bloom 조회 대상이 NULL → 대상을 복사 범위의 마지막 날에서 고름. 1/100 처음부터 끝까지(벌크 → Q1~Q20 → 리플레이 500/2,000/5,000 TPS + Q17 live → 정합) 다시 돌려 이전 값과 같음을 확인했다(벌크 49.2만 행/s, xlog 2.56·82.7B, Q1~Q11 충족, Q12 0, Q17 p95 101·110ms, 적재 지연 p99 2.14~2.18초). Q18 삭제 두 형식의 시간 차이는 재현되지 않아 결론 없음으로 고쳤다. **1/20은 다시 돌리지 못했다**(호스트 C: 여유 1.2~2.3GB, 3장).
- **결정 제안**(12장, ADR-0006): A-6 유지 + 실패 시 2일 파티션 bloom 폴백, A-9 1.0은 MergeTree 유지, A-10 `sql_ids` 채택, A-11 BFloat16(기준 재정의 필요), 코덱 ZSTD(1) 기본 + 열 코덱 조정, bloom fpp 0.01·GRANULARITY 4(사용자 id만 1). 모두 목표 하드웨어 확인 대상이다.
- **설계와 다르게 구현했거나 PM 결정이 필요한 항목**은 14장에 모았다(26.8 비호환 3건, 1h dedup window, reader `changeable_in_readonly`, MV `SQL SECURITY DEFINER`, 시스템 로그 TTL, migrator 권한 축소·외부 URL 차단, 미사용 리스너 제거, 롤업 재구축 절차(staging + `REPLACE PARTITION`), D-12 자정 귀속, A-6 시계 오차, C-2 block_id 위치 문구, N-06 통계 10,000행/s 등).

## 1. 범위와 판정 원칙

- 03 문서 Gate 0 저장소 행의 판정은 **목표 하드웨어에서 전체 규모로** 한다. 이 문서는 (a) CI 1/100 자동 기준의 실측, (b) 로컬 1/20 참고 측정, (c) 13 §17 확인 항목의 답, (d) 결정 제안과 근거, (e) 목표 하드웨어에서 사람이 할 확인 목록이다.
- 규모 축소 방식(18 §9 `-Pscale`): **기간만 줄이고 TPS·카디널리티는 그대로** 둔다. 그래서 10분·1시간·1일 범위 쿼리는 목표 규모와 같은 양을 읽고, 7일·30일·90일 범위는 있는 기간(1/20은 36시간)만 읽는다. 장기 범위 쿼리의 목표 규모 값은 읽은 행 수 비례로 추정해 6.3절에 적었다.
- 쿼리 시간 판정은 클라이언트 벽시계(요청~마지막 바이트, localhost HTTP)의 웜 3회 중앙값이다. 서버 시간(`system.query_log.query_duration_ms`), 읽은 행·바이트·marks·메모리를 함께 적는다.

## 2. 환경

| 항목 | 값 |
|---|---|
| 머신 | AMD Ryzen 5 5600H(6C/12T), 15.3GiB RAM, NVMe(C: 476GB, 착수 시 여유 57GB), Windows 11 Home 10.0.26200 |
| Docker | Docker Desktop 4.61.0, WSL 2.7.1(커널 6.18), VM 12 CPU·약 7.4GiB(WO-02와 공유) |
| ClickHouse | 26.8.34.13(번들 고정 LTS), 컨테이너 메모리 상한 **5GiB**, `nofile` 262144 |
| 메모리 변수(13 §7.1 비율) | `max_server_memory_usage` 4,831,838,208B(0.9 × 상한), 읽기 쿼리 1,932,735,282B(0.4), 쓰기 1,288,490,188B(4/15) |
| 설정 | `deploy/clickhouse/config.d/argos.xml`·`users.d/argos.xml` 그대로(default 사용자 없음, 계정 3종). 열 단위 분석(5.2절)에만 벤치 컨테이너 내부 localhost 전용 진단 계정을 scratch 설정으로 추가했다(저장소 미포함) |
| 도구 | 생성기 `test/storage/generator`, 러너 `test/storage/queries`(QueryBench), 서버 기동 `test/storage/bench/bench-server.sh`와 같은 방식 |
| 동시 작업 | WO-02(에이전트 PoC)가 같은 머신에서 빌드·JVM 벤치 실행. 머신 CPU 부하를 5초 간격으로 기록했고(`MachineLoad`, 별도 샘플러 [`cpu-load.tsv`](../bench/wo03-storage-2026-10-24/cpu-load.tsv)), 각 표에 측정 중 부하를 적었다. 1/20 벤치 구간 전체(08:50~10:36 UTC, 샘플 1,034개) 평균 44.5%, 80% 이상 샘플 13.6% |
| 시각 | 2026-10-24 08:50~10:36 UTC(1/20), 08:25~08:45 UTC(1/100), 15:59~17:08 UTC(리뷰 r1 수정 측정), 17:33~19:22 UTC(통합 재검증, 16장. 이때 호스트 C: 여유 1.2~2.3GB) |

## 3. 규모와 선택 이유

| 테이블 | 목표(13 §15, 30일) | CI 1/100(`--profile=ci`) | 로컬 1/20(`--profile=local`) |
|---|---:|---:|---:|
| xlog | 13억(30일) | 12,961,791(7.2시간, 자정 포함) | 64,807,447(36시간, 2026-10-22 20:00~10-24 08:00 UTC) |
| profile_step | 5.4억(7일) | 5,148,066 | 25,691,012(마지막 8.4시간) |
| metric | 12억(14일) | 12,096,300 | 60,480,000(마지막 16.8시간) |
| sql_stat_raw | 1.2억(14일) | 3,172,102 | 15,866,840(xlog 전 구간) |
| ext_stat_raw | 0.1억(14일) | 542,241 | 2,713,412 |
| 합계 | 약 32억 | 3,392만 | 1억 6,956만 |

출처: 1/100 [`ci-1of100/generator/20261024-074002-bulk-804934114677/report.md`](../bench/wo03-storage-2026-10-24/ci-1of100/generator/20261024-074002-bulk-804934114677/report.md), 1/20 [`local-1of20/generator/20261024-085820-bulk-8d30f3a4af28/report.md`](../bench/wo03-storage-2026-10-24/local-1of20/generator/20261024-085820-bulk-8d30f3a4af28/report.md)(같은 디렉터리의 `manifest.json`에 범위·테이블별 행 수). 디스크는 [`local-1of20/monitor/ch-monitor.tsv`](../bench/wo03-storage-2026-10-24/local-1of20/monitor/ch-monitor.tsv)·[`hostdisk.txt`](../bench/wo03-storage-2026-10-24/local-1of20/monitor/hostdisk.txt). 경로는 모두 [`docs/bench/wo03-storage-2026-10-24/`](../bench/wo03-storage-2026-10-24/README.md) 기준이다(15장).

- **1/20을 고른 이유**: 디스크 예산 약 20GB와 5GiB 메모리 상한. 1/20 활성 데이터가 7.0GB이고, Q05/bloom·Q18·Q20이 하루치 xlog 사본(약 3.7GB)을 만들며, 머지·뮤테이션 뒤 outdated part가 약 8분 남는다(벌크 직후 디스크 총량 17.0GB = 활성의 2.4배). 1/10(14GB)은 사본·outdated까지 합치면 예산을 넘는다.
- **환경 발견(디스크)**: 컨테이너 안의 피크는 `system.parts` 기준 약 23GB(outdated part 포함, 하드링크 공유분 중복 계산)였지만 Docker Desktop의 `docker_data.vhdx`가 30.1GB → 70.7GB로 커졌고(WSL2 동적 VHDX는 지운 블록을 호스트에 돌려주지 않는다), 호스트 C: 여유가 57GB → 14GB로 줄었다. 볼륨 삭제 뒤에도 VHDX는 줄지 않으며, 줄이려면 Docker Desktop·WSL을 멈추고 VHDX를 compact해야 한다(14장 미결). 목표 하드웨어(Linux 직접 설치)에는 해당하지 않는다.
- **리뷰 r1 뒤 1/20을 다시 돌리지 않은 이유**: 통합 재검증 시점(2026-10-24 17:33 UTC)에 호스트 C: 여유가 1.2GB였고(`docker_data.vhdx` 75.9GB, 다른 작업의 데이터 포함), 1/20 실행은 VHDX를 30.1 → 70.7GB로 키운 전례가 있어 호스트 디스크를 채워 같은 머신의 WO-02까지 멈출 위험이 컸다. VHDX를 줄이려면 Docker Desktop·WSL을 멈춰야 해 역시 WO-02를 중단시킨다. 그래서 이 판의 1/20 수치(4~12장)는 리뷰 전 도구로 잰 값 그대로다. 그 뒤 바뀐 것은 설정(시스템 로그 TTL·미사용 리스너 제거·writer 제약·migrator 권한)과 도구(생성기 옵션·러너)뿐이고 V001 스키마·롤업은 바뀌지 않아, 데이터 경로 수치(적재·압축·쿼리)는 유효하다고 본다. 같은 도구·설정으로 1/100을 처음부터 끝까지 다시 돌려 값이 이전 1/100과 같음을 확인했다(16장, 볼륨 피크 2.87GB, VHDX 증가 0).

## 4. 적재

### 4.1 벌크(용량·압축 측정 모드)

| 항목 | CI 1/100 (Testcontainers, 4GiB) | 로컬 1/20 (bench 서버 5GiB, url 대상) |
|---|---|---|
| 행 수 / 시간 | 33,920,500행 / 77.5초(생성기 레인 1차), 80.2초(2차) | 169,558,711행 / 505.9초 |
| 처리량 | 437,808행/s(59.1MiB/s), 422,828행/s | **335,148행/s(45.3MiB/s RowBinary)** |
| 테이블별(insert 중) | - | xlog 128,493 · profile 155,847 · metric 187,922 · sql_stat 32,085 · ext 5,981행/s |
| 블록 insert p95(50만 행) | - | xlog 7.3초, profile 3.4초, metric 3.0초, sql_stat 6.8초, ext 4.2초 |
| parts/파티션 최대 | 10 | 16(적재 중), 9(머지 후) |
| MemoryTracking 피크 | 718~951MiB | 848MiB |
| DelayedInserts | 0 | 0 |
| 머신 CPU | 착수 3%/27%, 중 평균 74%/71% | 평균 76%, 최대 100%(생성기 자체 7%) |
| 원본 count = manifest | 일치 | 일치 |

출처: 1/100 [`ci-1of100/generator/20261024-074002-bulk-804934114677/report.md`](../bench/wo03-storage-2026-10-24/ci-1of100/generator/20261024-074002-bulk-804934114677/report.md)(1차)·[`20261024-074204-bulk-804934114677/report.md`](../bench/wo03-storage-2026-10-24/ci-1of100/generator/20261024-074204-bulk-804934114677/report.md)(2차), 1/20 [`local-1of20/generator/20261024-085820-bulk-8d30f3a4af28/report.md`](../bench/wo03-storage-2026-10-24/local-1of20/generator/20261024-085820-bulk-8d30f3a4af28/report.md)·`manifest.json`, 머신 CPU [`cpu-load.tsv`](../bench/wo03-storage-2026-10-24/cpu-load.tsv).

- 목표 규모(약 32억 행) 벌크는 이 처리량으로 약 2.7시간이다(참고).
- 1h 롤업까지 12개 MV가 insert 경로에 있다(xlog 1회 insert → 1m MV 4개 → 1h MV 2개). 13 §17 #16의 "insert당 1m MV 5개"에서 parts 적체는 보이지 않았다(파티션당 최대 17, 기준 300).

### 4.2 리플레이(라이터 경로: WAL → 2초/5만 행 블록 → 토큰)

로컬 1/20 데이터 위에 리플레이(2026-10-24 10:04~10:35 UTC, 500 → 2,000 → 5,000 TPS 각 10분, 생성기 `--mode=replay`, 테이블별 라이터가 2초 또는 5만 행마다 블록, `argos:<table>:<block_id>` 토큰, 중복 재전송 포함. WAL은 파일 없는 메모리 시뮬레이션이고 시뮬레이션 tick은 1초였다. 측정 한계는 표 아래). 13 §15 원안은 각 1시간이며 목표 하드웨어에서 수행한다(13장).

| TPS 단계 | 적재 행 | 수신 → ack 지연 p50 | p99 | 최대 |
|---:|---:|---:|---:|---:|
| 500 | 1,495,012 | 1,615ms | 3,039ms | 3,873ms |
| 2,000 | 3,954,687 | 1,615ms | 3,039ms | 3,139ms |
| 5,000 | 8,912,968 | 1,631ms | 3,071ms | 3,273ms |
| 전체 | 14,362,667(xlog 4,501,626·profile 7,754,443·metric 1,800,000·sql_stat 318,947·ext 40,076) | 1,631ms | **3,071ms** | 3,873ms |

출처: [`local-1of20/generator/20261024-103509-replay-wo03local/report.md`](../bench/wo03-storage-2026-10-24/local-1of20/generator/20261024-103509-replay-wo03local/report.md)·`manifest.json`, 1/100 생성기 레인 [`ci-1of100/generator/20261024-074915-replay-c3cd0823acd5/report.md`](../bench/wo03-storage-2026-10-24/ci-1of100/generator/20261024-074915-replay-c3cd0823acd5/report.md).

- 기준(N-06·Q17 적재 지연 p99 5초) 대비 여유가 있다. 지연의 대부분은 2초 배치 대기(설계상 최대 2초 + insert 왕복)이고 TPS에 따라 거의 늘지 않았다. 블록 insert p95: xlog 147ms, profile 72ms, metric 70ms, sql_stat 54ms, ext 48ms.
- 리플레이 중 파티션당 parts 최대 14, MemoryTracking 피크 729MiB, 머지 최대 4, DelayedInserts 0, 머신 CPU 평균 25%·최대 100%(생성기 CPU 0~1%).
- 원본 count = 리플레이 전 count + 생성 행(테이블 5개 모두 정확히 일치: 예 xlog 64,867,447 + 4,501,626 = 69,369,073). 생성기 보고서의 `rawCountsMatchManifest=false`는 이미 데이터가 있는 테이블에 리플레이해서 전체 count를 이번 실행분과 비교했기 때문이다.
- 5,000 TPS 단계의 부하 구성(13 §15 분포, 생성기 `--target=none` 10분 시뮬레이션 실측): xlog 5,005 + profile_step 8,527 + 통계 261(sql_stat 237 · ext_stat 23) + metric 1,000행/s. xlog·step·통계 합계 약 13.8k 행/s로 N-06(5,000 + 50,000 + 10,000 = 65k 행/s)의 **약 21%**다. 통계가 적은 이유는 인스턴스가 50개이고 서비스가 상용 SQL(서비스당 8개, Zipf)만 써서 10초 창마다 (인스턴스, SQL) 조합이 적기 때문이다.
- **N-06 부하 옵션(이번 수정)**: 생성기에 `--instances-per-app=30`(인스턴스 300, 13 §14), `--active-sqls=200`(인스턴스별 활성 SQL 집합에서 고르게 뽑기, 13 §14 "활성 SQL 200"), `--profile-step-rate=50000`(정상 트랜잭션 캡처율을 tick마다 조정해 profile_step 행/s를 목표에 맞춤)을 추가했다. 통계는 계속 트랜잭션에서 파생하므로 D-12가 유지된다. `--target=none` 5분 리플레이(5,000 TPS, tick 100ms, 2026-10-24 15:59~16:05 UTC, 머신 CPU 평균 34%·최대 100%, 생성기 CPU 평균 1%)에서 실시간으로 **xlog 5,011 · profile_step 50,018 · sql_stat 5,300 · ext_stat 126 · metric 6,000 = 66,455행/s**(xlog·step·통계 합계 60,455, N-06의 93%)를 냈고, 정상 트랜잭션 캡처율 평균 0.73, Σ xlog.sql_count = Σ sql_stat.count(9,379,086), Σ ext_count = Σ ext_stat.count(623,392)였다. 같은 옵션의 10분 벌크 시뮬레이션도 66,408행/s로 같다. 통계 10,000행/s는 13 §14 전제(300 × 200 / 10초 = 6,000행/s 상한)로 닿지 않아 PM 결정 항목으로 올렸다(14.2 9항). 이 부하를 ClickHouse에 적재한 측정은 하지 않았다(목표 하드웨어 13장 6의 3b. 로컬은 C: 여유 1.5GB라 1시간 적재 불가).
- 1/100에서 생성기 레인이 같은 단계를 2분씩 돌린 값(p99 3.04/3.07/3.10초)과 같다.
- **측정 한계(적재 지연)**: (1) WAL은 메모리 시뮬레이션(`WalSimulator`)이라 레코드 파일 기록·그룹 fsync(10ms)·서버 디코드가 지연에 들어 있지 않다. (2) 시뮬레이션이 1초 tick 단위로 돌아 행이 수신 시각이 속한 tick이 끝난 뒤에야 라이터 큐에 들어간다. 큐 진입이 수신보다 최대 1초(평균 약 0.5초) 늦고 2초 플러시 타이머도 그만큼 늦게 시작하므로 위 표의 지연은 **최대 약 1초 과대 측정**이다. 실제 경로의 p99 추정치는 **약 2~2.5초**(2초 플러시 대기 + 블록 insert p95 수십~240ms + WAL 그룹 fsync 10ms + 디코드)이고, 근거는 아래 100ms tick 재측정이다.
- **100ms tick 재측정(이번 수정)**: 생성기에 `--tick-ms`(기본 1000, 1초를 나누는 값)를 추가했다. 1/100 생성기 레인과 같은 방식(Testcontainers ClickHouse 3GiB, 빈 테이블, `--target=docker`)으로 2,000 → 5,000 TPS 각 2분 리플레이를 tick 100ms와 1,000ms로 연달아 돌렸다. 두 실행 모두 원본 count = 생성 행, DelayedInserts 0, D-12 합계 일치, 파티션당 parts 최대 10·9.

| tick(시각 UTC) | TPS 단계 | 적재 행 | p50 | p99 | 최대 | 블록 insert p95 xlog(실행 전체) | 머신 CPU 평균 / 최대 |
|---|---:|---:|---:|---:|---:|---:|---:|
| 100ms(2026-10-24 16:08~16:12) | 2,000 | 786,526 | 1,151ms | **2,175ms** | 2,439ms | 237ms | 42% / 62% |
| 100ms | 5,000 | 1,764,166 | 1,215ms | **2,271ms** | 2,597ms | | 60% / 94% |
| 1,000ms(16:13~16:18) | 2,000 | 776,312 | 1,695ms | 3,103ms | 3,313ms | 783ms | 56% / 100% |
| 1,000ms | 5,000 | 1,768,136 | 1,887ms | 3,967ms | 5,679ms | | 72% / 100% |

  2,000 TPS 단계에서 tick을 1초에서 100ms로 줄이면 p50이 0.54초, p99가 0.93초 줄어 1초 tick의 과대 측정(평균 0.5초, 최대 1초)과 맞는다. 5,000 TPS 단계의 1,000ms 값은 머신 부하가 더 높아(평균 72%, xlog insert p95 783ms) 차이가 더 크다. 100ms tick의 p99 2.2~2.3초가 위 추정치의 근거다. WAL 파일 기록·fsync·디코드는 여전히 빠져 있어 서버 라이터(WO-21) 구현 뒤 실제 경로로 재측정해야 한다. 1/20 리플레이(위 표)와 Q17은 다시 돌리지 않았다(C: 여유 1.5GB).

  출처: [`r1-generator/replay-tick100/`](../bench/wo03-storage-2026-10-24/r1-generator/replay-tick100/20261024-161249-replay-25dee337a10f/report.md)·[`replay-tick1000/`](../bench/wo03-storage-2026-10-24/r1-generator/replay-tick1000/20261024-161815-replay-ca0dffd0e069/report.md)(각 `manifest.json`, `machine-cpu.tsv`), N-06 옵션 [`r1-generator/n06-none/`](../bench/wo03-storage-2026-10-24/r1-generator/n06-none/20261024-160524-replay-c7d4121c8afa/report.md), 단계별 부하 구성 [`r1-generator/rates/`](../bench/wo03-storage-2026-10-24/r1-generator/README.md). 명령은 [`r1-generator/README.md`](../bench/wo03-storage-2026-10-24/r1-generator/README.md).

### 4.3 parts 추이와 메모리

[`local-1of20/monitor/ch-monitor.tsv`](../bench/wo03-storage-2026-10-24/local-1of20/monitor/ch-monitor.tsv)(15초 간격, `argos_reader`, 열 설명은 증거 디렉터리 README)의 요약이다.

| 구간(UTC) | 파티션당 parts 최대 | 활성 parts 최대 | 머지 최대 | MemoryTracking 피크 | DelayedInserts | 활성 디스크 |
|---|---:|---:|---:|---:|---:|---:|
| 벌크 08:50~08:59 | 16 | - | 3 | 848MiB | 0 | 7.0GB(직후 outdated 포함 17.0GB) |
| 쿼리·프로시저 09:00~10:03(사본·뮤테이션·코덱 실험) | 17 | 220 | 3 | 1,217MiB | 0 | 최대 13.0GB |
| 리플레이 + Q17 10:04~10:35 | 14 | 240 | 4 | 729MiB | 0 | 7.5GB |

- 파티션당 parts는 기준 300(13 §7.2 `parts_to_delay_insert`)의 6% 이하였다. Q17 측정 중 parts가 가장 많은 파티션은 `profile_step` 당일 파티션이었다.
- 서버 RSS는 Q17 중 최대 1.0GiB, 컨테이너 상한 5GiB(서버 4.5GiB) 대비 여유가 컸다. 목표 하드웨어 기준 7.5GiB(N-18)는 9장 참고.
- outdated part(머지·뮤테이션·DROP 뒤 남는 이전 part)가 `old_parts_lifetime`(기본 480초) 동안 디스크를 잡는다. 벌크 직후 활성 7.0GB에 대해 디스크 총량 17.0GB였다. 13 §14 디스크 공식의 "머지 여유 20%"는 대량 적재 직후에는 부족하다(14장).

## 5. 압축률과 행당 바이트(B_*)

### 5.1 테이블별(1/20, 머지 후 활성 parts, `system.parts`)

| 테이블 | 행 | 압축 MiB | 압축률 | B/행(data) | disk B/행 | 13 §14 기대 | Gate 0 기준 | 판정(V001 그대로) | 제안 코덱(5.3) 압축률 / B/행 |
|---|---:|---:|---:|---:|---:|---|---|---|---|
| xlog | 64,807,447 | 5,111.8 | **2.56** | **82.7** | **86.0** | 70B | ≥ 3 | **미달** | **3.76 / 55.7** |
| profile_step | 25,691,012 | 355.0 | 7.16 | 14.5 | 14.5 | 25B | ≥ 5 | 충족 | 11.20 / 9.26 |
| metric | 60,480,000 | 151.1 | 11.84 | 2.62 | 2.62 | 4B | ≥ 8 | 충족 | 14.44 / 2.15 |
| sql_stat_raw | 15,866,840 | 409.7 | 5.10 | 27.1 | 27.1 | 30B(N-19: 20B) | - | - | 11.38 / 12.14 |
| ext_stat_raw | 2,713,412 | 55.4 | 5.15 | 21.4 | 21.5 | 30B | - | - | 10.98 / 9.90 |
| app_stat_1m | 44,622 | 33.1 | 2.21 | 778.5 | 782.5 | 300B(tDigest 포함) | - | - | - |
| service_stat_1m | 363,254 | 95.6 | 2.86 | 276.0 | 277.1 | 300B | - | - | - |
| instance_stat_1m | 132,142 | 79.8 | 2.75 | 633.5 | 635.6 | 60B | - | - | - |
| error_stat_1m | 310,573 | 4.3 | 4.01 | 14.5 | 14.9 | 60B | - | - | - |
| sql_stat_1m | 1,694,669 | 61.1 | 5.25 | 37.8 | 38.1 | 60B | - | - | - |
| ext_stat_1m | 145,718 | 5.9 | 4.45 | 42.7 | 43.2 | 60B | - | - | - |
| metric_1m | 5,089,500 | 72.0 | 4.25 | 14.8 | 14.9 | 60B | - | - | - |
| app_stat_1h / service_stat_1h | 745 / 7,564 | 1.6 / 9.9 | 1.69 / 1.90 | 2,199 / 1,378 | 2,252 / 1,400 | - | - | - | - |
| sql_stat_1h / ext_stat_1h / metric_1h | 81,652 / 2,674 / 112,900 | 3.8 / 0.2 / 1.9 | 4.24 / 2.89 / 3.60 | 48.6 / 65.8 / 17.6 | 50.1 / 78.4 / 18.6 | - | - | - | - |

출처: [`local-1of20/sizes/sizes-after-bulk.txt`](../bench/wo03-storage-2026-10-24/local-1of20/sizes/sizes-after-bulk.txt)(리플레이 뒤는 `sizes-final.txt`), 제안 코덱 열 [`local-1of20/codec/codec-sizes.txt`](../bench/wo03-storage-2026-10-24/local-1of20/codec/codec-sizes.txt)(`q20t_*`), 1/100 [`ci-1of100/querybench/report.md`](../bench/wo03-storage-2026-10-24/ci-1of100/querybench/report.md)(Data 표), 리플레이 적재분 [`ci-1of100/generator/20261024-074915-replay-c3cd0823acd5/report.md`](../bench/wo03-storage-2026-10-24/ci-1of100/generator/20261024-074915-replay-c3cd0823acd5/report.md).

- 1/100에서도 같은 값이 나왔다(xlog 2.56·82.7B, profile 7.15, metric 11.87, sql_stat 5.06, ext 5.14). 리플레이(2초 블록) 적재분도 xlog 2.61·81.1B(생성기 레인)로 같은 수준이다.
- 제안 코덱 열은 같은 하루(2026-10-23) 사본(INSERT SELECT, 3~7 parts) 값이다. 같은 사본의 V001 값은 xlog 2.53·82.6B로 머지된 본 테이블과 차이가 1% 미만이다.
- **B/행 기준 두 가지**: "B/행(data)"는 `sum(data_compressed_bytes)/rows`(열 데이터만, 13 §15 압축률과 같은 기준)이고, "disk B/행"은 `sum(bytes_on_disk)/rows`(skip index·marks·primary index·part 메타 포함, 13 §14·N-19 디스크 용량 공식의 기준)다. 차이는 xlog +4.0%(skip index 5종 약 3.3B/행, 12.6절), 나머지 원본 테이블 +0.1~0.2%, 소형 1h 롤업 +2~19%다. 생성기 보고서(`report.md`·`manifest.json`의 `clickhouse.storage.*.diskBytesPerRow`)도 이번 수정부터 두 값을 함께 낸다.
- **일 증가량(500 TPS, 목표 규모 환산, 디스크 기준)**: xlog 43.2M행 × 86.0B = **3.72GB**, profile_step 73.4M행 × 14.5B = 1.07GB, metric 86.4M행 × 2.6B = 0.23GB, sql·ext 통계 0.33GB, 1m 롤업 0.31GB(1h 롤업 0.01GB) → **약 5.6GB/일**(13 §14 표 5.3GB 대비 **+6%**, T-ST-050 ±30% 안). data_compressed 기준으로는 xlog 3.57GB, 합계 약 5.5GB/일(+4%)이다. 제안 코덱이면 data_compressed 기준 약 3.7GB/일, 디스크 기준 약 3.9GB/일(제안 코덱의 skip index 크기는 측정하지 않아 V001과 같은 xlog +3.3B/행으로 가정한 추정).
- 롤업 행당 바이트는 tDigest 상태가 큰 app·instance 1m이 기대(300B·60B)를 넘는다. 일 용량으로는 작다(app 23MB, instance 56MB, service 67MB).

### 5.2 xlog 열별 크기(1/20, 활성 parts, `system.parts_columns`)

| 열 | 타입·V001 코덱 | B/행(V001, LZ4) | 비중 | ZSTD(1) 기본 + V001 열 코덱 | 제안 코덱 |
|---|---|---:|---:|---:|---:|
| trace_id | FixedString(16) `CODEC(NONE)` | 16.00 | 19.3% | 16.00 | 14.65 (기본 코덱: Argos 생성 trace의 상위 4B가 Unix 초) |
| request_id | String `ZSTD(3)` | 10.21 | 12.4% | 10.21 | 10.21 |
| sql_ids | Array(UInt64) | 10.10 | 12.2% | **3.57** | 3.57 |
| parent_span_id | UInt64 `CODEC(NONE)` | 8.00 | 9.7% | 8.00 | **1.01** (90%가 0인데 NONE으로 고정돼 있었다) |
| span_id | UInt64 `CODEC(NONE)` | 8.00 | 9.7% | 8.00 | 8.00 (랜덤, NONE 유지) |
| tx_id | UInt64 | 6.96 | 8.4% | 4.53 | **3.36** (`Delta, ZSTD(1)`) |
| remote_ip | IPv6 | 3.76 | 4.6% | 2.52 | 2.52 |
| alloc_bytes | Int64 `T64, LZ4` | 2.69 | 3.3% | 2.69 | 2.43 |
| instance_id | UInt32 | 2.34 | 2.8% | 0.61 | 0.61 |
| user_id | String `ZSTD(3)` | 1.79 | 2.2% | 1.79 | 1.79 |
| service_id | UInt64 | 1.79 | 2.2% | 0.66 | 0.66 |
| sql_count / elapsed_ms / sql_time_ms / ts / cpu_time_ms | | 1.40 / 1.34 / 1.22 / 1.13 / 1.08 | 각 1~2% | 0.68 / 1.34 / 1.22 / 1.13 / 1.08 | 0.68 / 1.21 / 0.96 / 1.09 / 0.79 |
| 나머지 25열 | | 합 약 4.6 | | | |
| **합계** | | **82.7** | | **66.5** | **55.7** |

출처: [`local-1of20/sizes/xlog-columns.txt`](../bench/wo03-storage-2026-10-24/local-1of20/sizes/xlog-columns.txt)(V001), [`xlog-columns-codecs.txt`](../bench/wo03-storage-2026-10-24/local-1of20/sizes/xlog-columns-codecs.txt)(`base_lz4`/`zstd`/`tuned`), V001 열 코덱 [`xlog-codecs.tsv`](../bench/wo03-storage-2026-10-24/local-1of20/sizes/xlog-codecs.tsv).

- 13 §14의 "랜덤 id 40B" 가정(trace 16 + span 8 + parent 8 + tx 8)은 실측 39B와 맞다. 그러나 압축 불가 열이 전체의 47%라서 **LZ4 기본 + `CODEC(NONE)`·`LZ4` 고정 열 구성으로는 압축률 3에 닿지 않는다**.
- 데이터 분포(2026-10-23 하루): `parent_span_id = 0` 90.1%, `request_id` 빈 값 51.5%, `user_id` 빈 값 73.1%, `sql_ids` 빈 배열 9.7%·평균 3.07개, 자식 트랜잭션(caller_app_id ≠ 0) 7.2%.
- `request_id`를 `FixedString(16)`/`UUID`로 저장하면 50% 채움 기준 약 8B로 줄지만(−2.2B/행) 타입 변경이고 헤더 값이 UUID가 아닐 수 있어 제안하지 않았다(14장 참고 항목).

### 5.3 Q20 코덱 비교(1/20, 하루 = 2026-10-23, INSERT SELECT 사본)

QueryBench Q20(4변형)과 수동 실험(제안 코덱 1변형)이다. **표본 크기**: 1/20의 "하루 사본"은 테이블마다 그날 가진 만큼이다. xlog·sql·ext는 하루 전체지만 profile_step은 마지막 8.4시간만 있어 그날 **24분치(1,222,131행, 목표 하루 7,340만 행의 약 1.7%)**, metric은 16.8시간 중 그날 분량이다. 사본의 part 수는 통제하지 않았다(제안 xlog 7 parts, ZSTD xlog 3, metric 5 대 2 등). insert CPU는 `system.query_log` ProfileEvents(사용자+시스템 시간)이고, 공유 머신이라 같은 변형도 실행마다 ±40% 흔들렸다(xlog base 147초 vs 다른 변형 87~105초는 그 시점 부하 차이로 본다).

| 테이블 | base(V001, LZ4) | nocodec(LZ4) | zstd(ZSTD(1) 기본 + V001 열 코덱) | zstd_nocodec | **제안(ZSTD(1) 기본 + 열 코덱 LZ4→ZSTD(1), xlog 조정)** |
|---|---|---|---|---|---|
| xlog(43,204,363행) | 2.53 / 82.6B | 2.18 / 95.7B | 3.15 / 66.4B | 3.64 / 57.4B | **3.76 / 55.7B** |
| profile_step(1,222,131행) | 7.13 / 14.6B | 5.21 / 19.9B | 10.26 / 10.1B | 10.73 / 9.7B | **11.20 / 9.3B** |
| metric(31,677,400행) | 13.01 / 2.38B | 6.03 / 5.14B | 12.88 / 2.41B | 10.15 / 3.05B | **14.44 / 2.15B** |
| sql_stat_raw(10,577,464행) | 5.11 / 27.0B | 4.91 / 28.1B | 11.25 / 12.3B | 10.94 / 12.6B | **11.38 / 12.1B** |
| ext_stat_raw(1,808,615행) | 5.11 / 21.3B | 4.92 / 22.4B | 10.96 / 9.9B | 10.73 / 10.3B | **10.98 / 9.9B** |
| xlog insert(벽시계 / CPU) | 100.4초 / 147.1초 | 76.2 / 89.4 | 73.6 / 87.0 | 87.8 / 105.0 | 74.2 / 89.0 |

출처: 4변형 [`local-1of20/querybench/qA/report.md`](../bench/wo03-storage-2026-10-24/local-1of20/querybench/qA/report.md)(Q20 절, `runs.tsv`), 제안 변형 [`local-1of20/codec/codec-sizes.txt`](../bench/wo03-storage-2026-10-24/local-1of20/codec/codec-sizes.txt)(`q20t_*`)·[`codec-insert.txt`](../bench/wo03-storage-2026-10-24/local-1of20/codec/codec-insert.txt)·DDL [`codec/ddl/`](../bench/wo03-storage-2026-10-24/local-1of20/codec/ddl/ddl-q20t-xlog.sql)(`ddl-q20t-*`·`ddl-q20z-*`·V001 `ddl-*`), 읽기 비용 [`codec-read-qlog.txt`](../bench/wo03-storage-2026-10-24/local-1of20/codec/codec-read-qlog.txt), 1/100 [`ci-1of100/querybench/report.md`](../bench/wo03-storage-2026-10-24/ci-1of100/querybench/report.md)(Q20 절).

- 1/100 같은 비교(하루 = 648만 행): xlog base 2.55 / zstd 3.18 / zstd_nocodec 3.68, profile 7.15/10.32/10.79, metric 12.02/12.22/11.44, sql_stat 5.08/10.95/10.72. 1/20과 같은 경향이다.
- metric은 열 코덱(DoubleDelta·Gorilla)이 핵심이다(코덱 끄면 13.0 → 6.0). xlog·profile은 ZSTD 기본에서 LZ4 고정 열 코덱이 오히려 손해다.
- **통제한 재측정(1/100, run P, 2026-10-24 16:31~16:39 UTC, 머신 CPU 30~60%)**: Q20을 고쳐 (a) 테이블마다 자기 데이터가 꽉 찬 최신 24시간 창(없으면 그 테이블 전체 범위, 1/100은 모든 테이블이 24시간 미만이라 전체: xlog 1,296만·profile 515만·metric 1,210만·sql 317만·ext 54만 행), (b) 변형마다 머지가 멈출 때까지 대기(실행 중 머지 0, 같은 part 수 2회, 상한 180초)하고 최종 part 수 기록, (c) 제안 변형(`proposed`, `ProposedCodecs`)을 포함한 5변형을 **2회 반복**(2회차는 역순)했다.

  | 테이블 | base | nocodec | zstd | zstd_nocodec | **proposed** | 반복 간 최대 차이 |
  |---|---|---|---|---|---|---|
  | xlog | 2.54 / 82.7B | 2.20 / 95.8B | 3.17 / 66.5B | 3.67 / 57.4B | **3.79 / 55.7B** | 0.6%(base 2.54 대 2.55, part 12 대 5) |
  | profile_step | 7.15 / 14.5B | 5.22 / 19.9B | 10.31 / 10.1B | 10.78 / 9.6B | **11.27 / 9.2B** | 0.0% |
  | metric | 11.99 / 2.59B | 7.34 / 4.22B | 12.18 / 2.55B | 11.43 / 2.71B | **14.00 / 2.22B** | 0.1% |
  | sql_stat_raw | 5.08 / 27.2B | 4.92 / 28.1B | 10.91 / 12.7B | 10.70 / 12.9B | **10.97 / 12.6B** | 0.1% |
  | ext_stat_raw | 5.14 / 21.4B | 5.01 / 22.0B | 11.00 / 10.0B | 10.76 / 10.3B | **11.03 / 10.0B** | 0.0% |

  - 같은 표본·같은 정의의 반복 간 차이는 0.6% 이하였고, 최종 part 수(xlog 5~12, sql 7~8)에 따른 차이도 그 안이다. 반면 1/20에서 같은 "zstd" 정의의 metric이 QueryBench 12.88, 수동 실험 13.29로 3% 달랐다(표본 일자·part 수 미통제). 이 정도 차이 안에서 **base와 순위가 뒤집히는 값은 "잡음 안"으로 본다**: metric의 base 대 zstd(1/20 13.01 > 12.88, 1/100 11.99 < 12.18), sql·ext의 zstd 대 proposed(차이 1% 미만).
  - 제안 정의는 1/20 수동 실험과 같다(xlog 3.76/55.7B 대 1/100 3.79/55.7B). xlog·profile·metric에서 proposed가 다른 모든 변형보다 크고, 그 차이(xlog +3% 이상, metric +15%)는 잡음보다 크다.
  - 원자료: [`r1-queries/run-P/report.md`](../bench/wo03-storage-2026-10-24/r1-queries/run-P/report.md)(Q20 절, 변형·반복·part 수·insert CPU), `runs.tsv`.
- **읽기 비용**(같은 하루, `argos_reader`, 웜 중앙값 서버 시간 / CPU): 1시간 대시보드형 집계 19 / 24 / 17ms, 1시간 범위 검색(SELECT * LIMIT 1000) 30 / 29 / 31ms, `has(sql_ids)` 1일 44 / 49 / 38ms(V001 / zstd / 제안). 하루 전체 스캔(4,320만 행, 2.0GiB)은 1,709ms·CPU 6.7초 → 2,026ms·8.0초(**+19% CPU**). 대시보드·검색 경로에는 차이가 측정 잡음 안이다.

## 6. 쿼리 Q1~Q11(T-ST-001~011)

### 6.1 로컬 1/20(QueryBench run A2, 2026-10-24 09:37~09:57 UTC)

콜드 = 각 변형 직전 서버 재기동(graceful) + Docker 호스트 페이지 캐시 drop, 웜 = 3회 중앙값. 측정 중 머신 CPU 평균 32%·최대 100%(WO-02 동시 실행). "CPU" 열은 그 변형 측정 중 1초 샘플(`MachineLoad`)의 평균이다. **"-"는 샘플 없음(집계 버그, 수정 전 측정)**: 러너가 쿼리가 끝난 즉시 집계해 1초 미만 쿼리에는 그 시점에 샘플이 없었다. 수정 뒤 러너는 보고서를 쓸 때 "시작 이후 첫 샘플부터 끝 이후 첫 샘플까지"로 계산한다(1/100 run P는 모든 실행에 값이 있다). 별도 샘플러 [`cpu-load.tsv`](../bench/wo03-storage-2026-10-24/cpu-load.tsv)(5초)는 시각이 있지만 run A2 `runs.tsv`에 쿼리별 시각이 없어(콜드 재기동 시간도 변형마다 다름) 시각 조인으로 채울 수 없었다.

| Q | 변형 | 기준 | 콜드 ms | 웜 ms | 서버 ms | 읽은 행 | MiB | marks | 메모리 MiB | 결과 행 | CPU | 판정 |
|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|
| Q1 | 1h 원본 10초 버킷 | 1초 | 43.6 | 28.9 | 24 | 475,136 | 7.7 | 58 | 10.4 | 355 | 65% | 충족 |
| Q1 | 6h(기록) | - | 91.2 | 66.0 | 62 | 2,825,424 | 45.9 | 348 | 14.5 | 360 | - | 기록 |
| Q2 | 7일 `app_stat_1m` | 1초 | 67.7 | 46.6 | 42 | 35,788 | 3.8 | 11 | 39.4 | 146 | 77% | 충족 |
| Q2 | 90일 `app_stat_1h` | 1초 | 21.9 | 19.3 | 15 | 805 | 0.1 | 4 | 9.6 | 7 | 78% | 충족 |
| Q3 | 10분 XLog | 2초 | 568.2 | 92.4 | 55 | 147,456 | 3.5 | 18 | 11.5 | 69,090 | 96% | 충족 |
| Q3 | 10만 점(샘플링, shown/total) | 2초 | 349.2 | 141.5 | 101 | 212,992 | 5.1 | 26 | 17.1 | 99,855 | - | 충족 |
| Q4 | 1h × 응답시간 범위 | 1초 | 235.4 | 45.1 | 40 | 950,272 | 34.8 | 58 | 9.6 | 1,000 | - | 충족 |
| Q4 | + 서비스 필터 | 1초 | 119.9 | 62.1 | 55 | 933,888 | 34.2 | 57 | 13.6 | 1,000 | - | 충족 |
| Q4 | + 에러 필터 | 1초 | 255.1 | 49.5 | 45 | 950,272 | 34.8 | 58 | 10.6 | 1,000 | - | 충족 |
| Q4 | user_id 1h(N-24) | 3초 | 163.9 | 17.1 | 12 | 57,344 | 1.3 | 4 | 5.3 | 3 | - | 충족 |
| Q4 | user_id 24h(기록) | - | 139.6 | 32.4 | 29 | 653,728 | 13.5 | 51 | 6.7 | 29 | - | 기록 |
| Q4 | request_id 1h(N-24) | 3초 | 32.5 | 11.3 | 7 | 16,384 | 0.4 | 1 | 5.3 | 1 | - | 충족 |
| Q5 | tx_id 복원 ±2초 + app | 0.5초 | 65.5 | 15.8 | 12 | 8,192 | 1.6 | 1 | 9.5 | 1 | - | 충족 |
| Q5 | 복원 ±2초, app 없음 | 0.5초 | 148.6 | 24.7 | 19 | 8,192 | 1.6 | 1 | 9.6 | 1 | 94% | 충족 |
| Q5 | URL ts | 0.5초 | 74.5 | 17.9 | 14 | 8,192 | 1.6 | 1 | 9.5 | 1 | - | 충족 |
| Q5 | profile_step 상세 | 0.5초 | 41.6 | 10.8 | 6 | 8,192 | 0.1 | 1 | 5.3 | 18 | - | 충족 |
| Q5 | 범위 없이 bloom만(기록) | - | 118.2 | 16.5 | 12 | 8,192 | 0.1 | 1 | 7.3 | 1 | - | 기록 |
| Q6 | trace_id 힌트 ±1h | 0.5초 | 129.4 | 11.3 | 7 | 16,384 | 0.9 | 2 | 6.3 | 2 | - | 충족 |
| Q6 | 24h | 1초 | 86.7 | 9.8 | 6 | 16,384 | 0.9 | 2 | 6.3 | 2 | - | 충족 |
| Q6 | 30일(기록) | - | 122.0 | 10.4 | 6 | 16,384 | 0.8 | 2 | 6.3 | 2 | 93% | 기록 |
| Q7 | 서비스 Top N 1일 | 1초 | 95.9 | 66.4 | 62 | 63,944 | 10.9 | 10 | 41.3 | 20 | 66% | 충족 |
| Q7 | 7일 | 1초 | 129.9 | 96.8 | 93 | 86,997 | 12.7 | 15 | 44.6 | 20 | 71% | 충족 |
| Q7 | 1일 버전별 | 1초 | 130.3 | 100.7 | 94 | 63,944 | 11.4 | 10 | 45.9 | 60 | - | 충족 |
| Q7 | 7일 버전별 | 1초 | 388.2 | 222.4 | 176 | 86,997 | 13.2 | 15 | 45.6 | 60 | 100% | 충족 |
| Q8 | SQL Top N 1일(버킷 p95) | 1초 | 69.9 | 33.0 | 29 | 212,992 | 38.5 | 26 | 21.6 | 500 | 69% | 충족 |
| Q8 | 7일 | 1초 | 112.4 | 54.9 | 50 | 286,720 | 52.4 | 35 | 21.6 | 500 | - | 충족 |
| Q8 | SQL 상세 추이 1일 | 1초 | 58.4 | 22.4 | 17 | 98,304 | 14.6 | 12 | 16.2 | 289 | - | 충족 |
| Q8 | SQL 상세 추이 7일 | 1초 | 65.4 | 23.4 | 20 | 147,456 | 20.9 | 18 | 16.1 | 146 | - | 충족 |
| Q9 | 에러 그룹 1일 | 1초 | 76.2 | 14.9 | 10 | 49,740 | 2.7 | 8 | 6.4 | 39 | - | 충족 |
| Q9 | 그룹 → 관련 트랜잭션 30일 | 1초 | 136.3 | 65.6 | 48 | 219,678 | 4.3 | 2,069 | 14.4 | 10 | - | 충족 |
| Q10 | 인스턴스 힙 7일(1m) | 1초 | 42.7 | 16.4 | 13 | 57,344 | 3.1 | 7 | 6.9 | 1,008 | - | 충족 |
| Q10 | 앱 합계 90일(1h) | 1초 | 67.2 | 29.9 | 22 | 24,284 | 0.7 | 3 | 6.4 | 18 | 67% | 충족 |
| Q11 | `has(sql_ids)` 1일 | 2초 | 291.3 | 94.2 | 92 | 2,292,075 | 110.0 | 1,379 | 35.6 | 1,000 | 94% | 충족 |
| Q11 | 30일(기록) | - | 229.6 | 100.2 | 98 | 2,808,350 | 135.7 | 2,069 | 43.8 | 1,000 | 87% | 기록 |

출처: [`local-1of20/querybench/qA2/report.md`](../bench/wo03-storage-2026-10-24/local-1of20/querybench/qA2/report.md)·[`runs.tsv`](../bench/wo03-storage-2026-10-24/local-1of20/querybench/qA2/runs.tsv)(run `wo03-f348495d`), run A는 [`qA/report.md`](../bench/wo03-storage-2026-10-24/local-1of20/querybench/qA/report.md)(run `wo03-90d5b258`).

- 같은 데이터에서 부하가 더 높았던 run A(09:00~09:05 UTC, 평균 47%·대부분 변형 중 93~100%)도 전부 충족했다: 웜 최대 Q7 7일 버전별 246ms, Q01 1h 228ms, 콜드 최대 Q04 user 24h 1,113ms. 이 실행은 콜드 재기동이 query_log를 잃어(기본 10초 정지 대기) 서버 시간 열이 비어 있어 재기동을 graceful(120초 대기)로 고친 뒤 A2를 다시 돌렸다.

### 6.2 CI 1/100(BenchRunIT, `-Pqueries.profile=ci`, Testcontainers 3GiB, 콜드 = 첫 실행)

Q1~Q11 전 변형 충족. 웜 중앙값: Q1 1h 52ms, Q2 7일 63ms, Q3 10분 71ms·10만 점 133ms, Q4 범위 85~100ms(user_id·request_id 1h 51·12ms), Q5 20~41ms, Q6 15ms, Q7 62~68ms, Q8 30~63ms, Q9 14~20ms, Q10 12~18ms, Q11 1일 130ms(run `wo03-bb03c7cc`, 08:36~08:42 UTC, 머신 CPU 평균 21%·최대 56%). `ReaderLimitsIT`(T-ST-053)에서 같은 쿼리를 `argos_reader` 상한으로 실행해 모두 `system.query_log`에 남았다. r1 수정 뒤 `BenchRunIT`는 1/100에서 하네스 스모크(변형마다 1회, 7.1절)라 위 웜 중앙값은 이전 판 실행 값이다. 수정 뒤 러너의 1/100 웜 값(3회)은 벤치 서버 run P에 있다(Q1 1h 31ms, Q3 10분 91ms·10만 점 122ms, Q4 12~73ms, Q5 14~27ms(`fallback_coverage` 233ms는 기록용), Q6 16~20ms, Q7 32~50ms, Q8 20~29ms, Q9 13~15ms, Q10 13~21ms, Q11 1일 51ms, 머신 CPU 49~71%, [`r1-queries/run-P/report.md`](../bench/wo03-storage-2026-10-24/r1-queries/run-P/report.md)).

출처: [`ci-1of100/querybench/report.md`](../bench/wo03-storage-2026-10-24/ci-1of100/querybench/report.md)·[`runs.tsv`](../bench/wo03-storage-2026-10-24/ci-1of100/querybench/runs.tsv)(통합 테스트 2회차), `ReaderLimitsIT`는 [`ci-1of100/it/reader-limits.md`](../bench/wo03-storage-2026-10-24/ci-1of100/it/reader-limits.md). 1회차(`Q05/bloom_fill` 10초 상한 초과로 `BenchRunIT` 실패) 보고서는 2회차가 덮어써 남아 있지 않아, 이 절의 값은 2회차 기준이다.

### 6.3 목표 규모 추정(장기 범위)

1/20은 36시간뿐이라 7일·30일·90일 변형은 그만큼만 읽었다. 읽은 행 수는 기간에 비례하므로(정렬 키 `(app_id, ts, …)`, 일 파티션) 목표 규모 추정은 아래와 같다. 모두 기준 안이지만 목표 하드웨어 확인 대상이다.

| 변형 | 1/20 읽은 행(웜 ms) | 30일 데이터 추정 읽은 행 | 추정 |
|---|---|---|---|
| Q2 7일 `app_stat_1m` | 35,788(46.6) | 약 17만 | 1초 안(롤업 행 수가 작다) |
| Q7 7일 버전별 | 86,997(222.4) | 약 41만 | 수백 ms. 1h 롤업 소스로 바꾸면 더 줄어든다(14 API 소스 선택 규칙 7일 → 1m) |
| Q8 7일 | 286,720(54.9) | 약 130만 | 0.3초 안팎 |
| Q9 관련 트랜잭션 30일 | 219,678(65.6), marks 2,069 | `error_group_id` 필터 + 30일 파티션 전부의 marks 검사(약 4만 marks) | 0.5초 안팎. `ORDER BY ts DESC LIMIT`로 최근 파티션에서 끝나는 경우가 많다 |
| Q11 30일 | 2,808,350(100.2) | `LIMIT 1000` 조기 종료 | 1/20과 같은 수준(최근 파티션에서 1,000행이 차면 끝) |
| Q12·Q13 30일 정합(야간, migrator) | 6,535만(553) | 13억 | 약 11초(CPU 단일 노드). 10초 상한 없는 `argos_migrator` 야간 작업 |
| Q5 범위 없이 bloom만(`bloom_only`, idx_tx) | 8,192(16.5), 1 mark | 행은 같지만 **모든 파티션의 idx_tx를 읽는다**: 1.26B/행 × 13억 = **약 1.6GB**(압축, 조회마다) | 측정하지 않은 비용. NVMe 순차 읽기·복원 CPU로 수 초 단위가 될 수 있다(목표 하드웨어 확인) |
| Q6 trace_id 30일(`30d`, idx_trace) | 16,384(10.4), 2 marks | 같은 방식으로 idx_trace 1.22B/행 × 13억 = **약 1.6GB** | 위와 같다 |

- **범위 없는 bloom 조회는 행 수로 외삽하면 안 된다.** 읽는 행·marks는 규모와 무관하게 1~2 mark지만, 스킵 인덱스 자체를 모든 파티션에서 읽는다. 1/100 run P(2026-10-24 16:17~16:39 UTC, 이번 수정 뒤 러너)에서 `ProfileEvents['ReadCompressedBytes']`를 기록했다: Q6 30d 첫 실행 19.7MB(idx_trace 1/100 전체 약 15.8MB + 데이터), 웜 1.2MB. 웜 값이 작은 것은 26.8의 query condition cache(같은 조건의 통과 granule을 기억)가 같은 trace_id 재조회에서 인덱스 읽기를 건너뛰기 때문이다. 실제 상세 조회는 매번 다른 id라서 **첫 실행 값이 실제 비용**이다(Q5 `bloom_only`는 앞선 스모크 실행이 같은 tx를 조회해 run P의 첫 실행도 캐시를 탔다: 1.3MB).
- 이 경로는 N-24(ID 단건 0.5초) 대상 경로가 아니다. N-24 경로는 복원 ts ±2초(Q5 `restored`) 또는 URL ts이고, A-6 폴백(12.1절)은 **2개 파티션**의 idx_tx만 읽는다(500 TPS 하루 4,320만 행 × 2 × 1.26B ≈ 0.11GB, 범위 없는 조회의 약 1/15). 범위 없는 bloom 조회는 폴백도 실패할 때의 마지막 수단(12.1절 PM 결정 항목)으로만 쓴다.

출처: Q2~Q11은 [`local-1of20/querybench/qA2/report.md`](../bench/wo03-storage-2026-10-24/local-1of20/querybench/qA2/report.md), Q12·Q13은 [`qA/report.md`](../bench/wo03-storage-2026-10-24/local-1of20/querybench/qA/report.md).

## 7. 정합 Q12·Q13·Q15·Q16(T-ST-030~033)

### 7.1 CI 1/100(자동, `./gradlew :test:storage:queries:integrationTest -Pqueries.profile=ci`)

| 테스트 | 내용 | 결과 |
|---|---|---|
| T-ST-030 적재 | 적재 직후(어떤 프로시저도 행을 더하기 전) 스냅숏: 원본 5개 테이블 count = 생성기 manifest, xlog `late = 1` 행 = manifest 늦은 행, Σ app_stat_1m.count = xlog 행. 재전송 블록 > 0 | **정확히 일치**(이전 판은 xlog `≥` 비교만 했다) |
| T-ST-030 Q12 | 분 단위 `count(xlog)` = Σ app·service·instance_stat_1m, 1h 캐스케이드, error_stat_1m, 야간 원형 | 불일치 0(4변형) |
| T-ST-031 Q13 | 범위 합계 Σ xlog.sql_count·ext_count = Σ sql·ext_stat_1m, raw ↔ 1m ↔ 1h(일 단위), **raw ↔ 1m 분 단위(`minute_raw_1m`, 새 변형)**. 일 단위(야간): 적재 직후 일별 Σ xlog.sql_count·ext_count·Σ sql·ext_stat_1m.count가 manifest의 일별 기대값과 같고, `daily_30d`·`nightly`가 보고한 날짜 = manifest에서 xlog ≠ 통계인 날짜 | 범위 합계·raw ↔ 1m ↔ 1h·분 단위 충족. **일 단위(야간) D-12는 manifest 기대값과 일치(자정 귀속 차이만: 10-23·10-24, 20행·야간 1행), PM 결정 대기**(7.3절) |
| T-ST-032 Q15 | 같은 토큰 100회 재삽입, MV 1개 고장 → 복구 → 재삽입 | 원본·모든 롤업 증가 0, 고장 MV만 채워짐 |
| T-ST-033 Q16 | **늦은 도착 2만 행**(13 §15, 5,000행 블록 4개) → 해당 분·시 롤업 갱신, Q12 재실행 | 분별 증가 = 삽입 행, Q12 0 |
| 음성 대조 | 행 1개를 롤업 없이 추가(app_stat_1m, sql_stat_raw), ext_stat_1m에 원본 없는 행 1개, **세 번째 소스(service_stat_1m)에만 있는 키(app 999, BATCH)** | Q12·Q13 `total_30d`·`minute_raw_1m`이 1행씩 검출. 세 번째 소스에만 있는 키도 kind `BATCH`로 정확히 표시되고, `join_use_nulls=1`을 강제해도 같은 2행(UNION ALL 형태) |
| 변이 검출(로컬 1회) | `rollups/ext_stat_1m.sql`의 `toStartOfMinute`를 `toStartOfFiveMinutes`로 바꾼 사본으로 Q12·Q13(저장소 파일은 바꾸지 않음) | `minute_raw_1m`만 실패(1,000행 = LIMIT), `rollups_14d`·`daily_30d`·`total_30d`는 통과. 분 단위 변형이 없으면 잡지 못하는 결함이다 |
| T-ST-053 | `argos_reader` 상한(행·메모리·시간 초과 거부), 상한 올리기·다른 설정 거부, query_log 기록, 카탈로그 DDL·쓰기 44건 거부(이전 판의 "35건"은 리뷰 r1에서 Q05·Q07·Q19 변형이 늘기 전 값이다. 2026-10-24 PMO 재실행과 WO-03F 실행 모두 44건, 17.6절) | 통과 |

데이터: 생성기 1/100(xlog 12,961,791행, 늦은 행 63,080, 재전송 블록 5, 시계 +60초 인스턴스 2), 적재 78~80초, `ConsistencyIT` 6/6·`ReaderLimitsIT` 3/3·`BenchRunIT` 3/3·`ColdRestartIT` 1/1(8분 48초, 머신 CPU 21% 평균).

**r1 수정 뒤 재실행(2026-10-24 17:02~17:08 UTC)**: `./gradlew :test:storage:schema-tests:integrationTest :test:storage:generator:integrationTest :test:storage:queries:integrationTest -Pqueries.profile=ci --rerun-tasks` → BUILD SUCCESSFUL, 벽시계 **5분 48초**(컴파일 포함, 세 모듈 병렬, 머신 CPU 평균 47%·최대 96%). 적재 112초(생성기 1/100 같은 데이터: xlog 12,961,791 = manifest, 늦은 행 63,080 = manifest, Σ app_stat_1m = xlog). 클래스별: `BenchRunIT` 186초(적재 포함), `ConsistencyIT` 32초(6/6), `ReaderLimitsIT` 30초(3/3), `ColdRestartIT` 28초, 스키마 IT 51건 133초, 생성기 IT 98초. 1/100에서 `BenchRunIT`는 하네스 스모크로 줄였다(Q01~Q11 각 1회, 사본 프로시저 Q05/bloom·Q18·Q20 제외, Q16 1,000행; 전체는 `-Pqueries.benchrun=full`. **이 옵션은 WO-03 당시 테스트 JVM에 전달되지 않아 효과가 없었다**(리뷰 지적 A-2). WO-03F에서 `test/storage/queries/build.gradle.kts`가 `argos.queries.benchrun`으로 넘기게 고쳤고, 실제 실행 결과는 17.6절). 이전 리뷰 측정은 저장소 IT 849초, 그중 `BenchRunIT` 635초였다. 4 vCPU 러너(이 머신 12스레드)에서는 저장소 IT만 약 12~15분으로 보고, `./gradlew integrationTest`가 같은 잡에서 에이전트·서버·샘플 앱 IT도 돌리므로 `pr.yml` integration 잡의 `timeout-minutes`를 30 → **45**로 올렸다(통합 리드 확인 필요, 첫 GitHub 실행 시간은 14.3 미결). 원자료: [`r1-queries/it/consistency.md`](../bench/wo03-storage-2026-10-24/r1-queries/it/consistency.md)·`dataset.md`·`reader-limits.md`·`bench-ci.md`·`benchrun-smoke-report.md`(테스트 실행마다 덧붙는 파일이고 마지막 절이 이 실행).

**통합 재검증(2026-10-24 18:02~18:08 UTC, 16장)**: CI 잡과 같은 루트 명령 `./gradlew integrationTest -Pqueries.profile=ci --rerun`(에이전트·샘플 앱 IT 포함, 모듈 병렬) → BUILD SUCCESSFUL, **5분 47초**(머신 CPU 평균 64%·최대 100%). 저장소: 스키마 51/51, 생성기 3/3, 쿼리 13/13(`ConsistencyIT` 6, `ReaderLimitsIT` 3, `BenchRunIT` 3, `ColdRestartIT` 1). 이 명령은 처음 두 번 스키마 테스트 19건이 전부 실패했다(Docker 환경 없음, 16.2절). 원인을 고친 뒤 통과했다. Q05 수정 뒤 기본 데이터의 `:test:storage:queries:integrationTest --rerun`(전체 카탈로그, Q05/bloom 포함)도 13/13이다(3분 53초).

출처: [`ci-1of100/it/consistency.md`](../bench/wo03-storage-2026-10-24/ci-1of100/it/consistency.md)·[`reader-limits.md`](../bench/wo03-storage-2026-10-24/ci-1of100/it/reader-limits.md)·[`dataset.md`](../bench/wo03-storage-2026-10-24/ci-1of100/it/dataset.md)·[`generator-log.txt`](../bench/wo03-storage-2026-10-24/ci-1of100/it/generator-log.txt)·[`cold-restart.md`](../bench/wo03-storage-2026-10-24/ci-1of100/it/cold-restart.md)(테스트 실행마다 덧붙는 파일이고 마지막 절이 2회차), [`ci-1of100/querybench/report.md`](../bench/wo03-storage-2026-10-24/ci-1of100/querybench/report.md)(Q15·Q16 절).

### 7.2 로컬 1/20(run A 09:04 UTC, 리플레이 뒤 run C 10:36 UTC)

리플레이 뒤(xlog 69,369,073행) Q12 4변형·Q13 범위 합계·rollups 모두 다시 0이었다(분 단위 851ms, 6,992만 행). 일 단위 34행·야간 1행은 같은 자정(10-22/10-23 경계) 때문이다. 아래 표는 run A 값이다.

| 검사 | 읽은 행 | 서버 ms | 불일치 |
|---|---:|---:|---:|
| Q12 분 단위 30일 | 65,347,465 | 551 | **0** |
| Q12 1h | 415,262 | 68 | **0** |
| Q12 error_stat | 65,117,833 | 331 | **0** |
| Q12 야간 원형 | 43,463,564 | 320 | **0** |
| Q13 범위 합계 | 66,647,834 | 528 | **0** |
| Q13 raw ↔ 1m ↔ 1h 14일 | 20,504,965 | 237 | **0** |
| Q13 일 단위 | 66,647,834 | 544 | 34행(기록) |
| Q13 야간 | 44,333,046 | 194 | 1행(270,266,745 대 270,266,687) |
| Q15(2만 tx 블록 × 5테이블, 100회) | - | 재삽입 왕복 85ms | 증가 0, 고장 MV만 채워짐(`VIOLATED_CONSTRAINT`로 실패시킨 뒤) |
| Q16(2만 행, 1시간 전 ts) | - | - | 분별 증가 불일치 0, Q12 0 |

출처: run A [`local-1of20/querybench/qA/report.md`](../bench/wo03-storage-2026-10-24/local-1of20/querybench/qA/report.md)(Q12·Q13·Q15·Q16), 리플레이 뒤 run C [`qC/report.md`](../bench/wo03-storage-2026-10-24/local-1of20/querybench/qC/report.md).

### 7.3 Q13 일 단위 불일치(D-12, PM 결정 필요)

- 원인: 에이전트 통계는 SQL·호출이 **끝난 시점**의 10초 창에 귀속된다(10 §9.2·§9.3, 생성기 기본 `stat-window=end`). 자정을 넘는 트랜잭션의 SQL은 xlog는 시작일, 통계는 다음 날에 잡힌다. 1/100에서 자정 1회당 SQL 실행 710건(4,050만 중), 외부 호출 69건이 넘어갔다.
- 범위 합계(Q13 total)와 분 단위 xlog = 롤업(Q12)은 항상 0이다. 생성기를 `--stat-window=start`(트랜잭션 시작 창 귀속)로 돌리거나 러너의 결정적 픽스처를 쓰면 일 단위도 0이다(쿼리 레인 실측).
- 선택지: (a) 야간 정합 검사를 자정 ± 최대 트랜잭션 시간 허용으로 바꾸기, (b) 에이전트가 트랜잭션 시작 창으로 귀속(10 문서 변경), (c) 일 단위 검사를 범위 합계로 대체. 이 PoC는 설계를 바꾸지 않고 일 단위 변형을 기록 전용으로 두었다.
- **결정(ADR-0007 C-57)**: (a)의 정확한 형태로 정했다. 일 단위는 \|Δ일\|이 그날 양쪽 자정을 가로지른 트랜잭션의 `sql_count`·`ext_count` 합 이하이면 통과다. WO-03F에서 Q13 `daily_30d`·`nightly`에 적용했고, 시계 보정(D-15) 인스턴스 때문에 이 상한을 넘는 날이 남는다는 새 충돌을 찾았다(17.3절).

## 8. Q14 백분위 정확도(T-ST-014, T-ST-042 → A-11)

### 8.1 방법

서버에서 결정적으로 만든 합성 표본(앱 2개 × 분당 3,000값 × 3일 = 2,592만 값, 로그정규 중앙값 80ms·σ 1.0 + 1% 이상치 2~30초 = 목표 규모 밀도)으로 1m 상태를 만들고 1m·1h·1d로 병합해 `quantileExact`와 비교했다. n ≥ 1,000 그룹만 판정한다.

- **판정 그룹 수**: 1m은 하루 2,880(앱 2 × 1,440분), 3일이면 8,640, 1h는 144(앱 2 × 24 × 3일), 1d는 **6**(앱 2 × 3일)이다. "최대 오차"는 그룹 수가 많은 단계일수록 커진다(1m 8,640개의 최대와 1d 6개의 최대는 같은 성격의 값이 아니다). 그래서 그룹 p95와 날짜별 최대를 함께 적는다.
- **이전 판(1/20 run A)의 1m 판정은 3일이 아니라 마지막 하루(2,880그룹)만 대상**이었다(러너 기본값 `minuteDays=1`). 러너를 고쳐 1m도 3일 전체로 판정하고 날짜별 1m 최대 오차를 함께 보고한다. 아래 8.2는 고친 러너로 1/100 서버에서 다시 돌린 값이다(run P, 2026-10-24 16:17~16:31 UTC, 합성 표본이라 데이터 규모와 무관. 1/20 run A의 마지막 하루 값과 날짜 2026-01-07 값이 같다).

### 8.2 결과(3일, 최대 값 오차, 괄호는 그룹 p95 / 순위 오차 최대)

| 함수 | 1m p50 | 1m p95 | 1m p99 | 1h p50 / p95 / p99 | 1d p50 / p95 / p99 | 1m 상태 크기 |
|---|---|---|---|---|---|---:|
| tDigest(V001 현재) | 1.18%(0.70%) / 0.53pp | **2.21%**(0.75%) / 0.13pp | 74.4%(33.4%) / 0.05pp | 0.72 / 0.25 / **5.23%** | 0.08 / 0.09 / 3.89% | 2,790B |
| BFloat16 | 1.32%(1.21%) / 0.45pp | **3.86%**(1.30%) / 0.18pp | 74.5%(33.6%) / 0.13pp | 0.00 / 0.44 / 2.11% | 0.00 / 0.22 / 0.52% | 4,506B |
| DD(0.01) | **2.05%**(0.87%) / 0.93pp | **4.27%**(1.47%) / 0.30pp | 74.3%(33.5%) / 0.13pp | 0.81 / 1.01 / 2.51% | 0.43 / 0.33 / 0.76% | 2,122B |
| 버킷 15구간(SQL·외부 호출용) | - | 19.6%(11.4%) | - | - / 6.8% / - | - / 6.0% / - | - |

날짜별 1m 최대 오차(각 2,880그룹):

| 함수 | p50 최대(2026-01-05 / 06 / 07) | p95 최대 | p99 최대 |
|---|---|---|---|
| tDigest | 1.09 / 1.16 / 1.18% | **2.21 / 2.15 / 2.05%** | 73.3 / 68.2 / 74.4% |
| BFloat16 | 1.30 / 1.32 / 1.30% | **3.48 / 3.86 / 3.67%** | 73.4 / 68.2 / 74.5% |
| DD | 2.05 / 2.05 / 2.05% | **4.27 / 4.17 / 4.27%** | 73.5 / 68.1 / 74.3% |

출처: 고친 러너 [`r1-queries/run-P/report.md`](../bench/wo03-storage-2026-10-24/r1-queries/run-P/report.md)(Q14 절: 단계별·날짜별 표, 상태 크기, D-14 결정성), 이전 판 [`local-1of20/querybench/qA/report.md`](../bench/wo03-storage-2026-10-24/local-1of20/querybench/qA/report.md)(Q14 절, 1m은 마지막 하루).

기준(13 §15): p50·p95 ≤ 2%, p99 ≤ 5%(상태 함수). 굵게 쓴 칸이 최대값 기준 미달이다. 1m p95 최대는 날짜마다 tDigest 2.05~2.21%, BFloat16 3.48~3.86%, DD 4.17~4.27%로 흔들린다(같은 분포·같은 그룹 수에서 하루 단위 최대의 분산). 같은 표본인데 tDigest의 1h·1d p99는 이전 판(5.06%·2.54%)과 이번(5.23%·3.89%)이 다르다. BFloat16·DD는 두 실행의 값이 같다(병합 순서 의존성, 아래 결정성).

- **1m p99는 기준 자체가 불량조건이다**: 1% 이상치(2~30초)가 정확히 p99 경계에 걸려 정확값이 본체 꼬리와 이상치 사이의 간극에 놓인다. 순위로는 0.05~0.1pp(1,000개 중 1~3개 차이) 차이인데 값은 수십 % 달라진다. 세 함수가 똑같이 74%인 것이 그 증거다.
- 그룹 p95 기준으로 보면 세 함수 모두 1m p50·p95 ≤ 1.5%, 1h·1d p99 ≤ 3.4%다.
- **결정성(D-14, 실측)**: tDigest 병합값은 병합 순서·버전에 따라 달라진다(26.3 vs 26.8에서 같은 상태를 병합한 값 80.4 vs 80.22222, `UpgradeIT`). Q14에 재구축 결정성 단계를 넣어 같은 날(2026-01-07)의 1m 상태를 두 번 재구축하고(a: `max_threads=1`, b: `max_threads=8, max_block_size=4096`로 부분 상태 병합 순서를 바꿈) 각 1m에서 1h 상태를 만든 뒤, 상태 바이트(키 순으로 정렬한 `hex(state)`의 `cityHash64`)와 확정값을 비교했다.

  | 함수 | 1m 상태 바이트 | 1m 확정값 | 1h 상태 바이트 | 1h 확정값 |
  |---|---|---|---|---|
  | tDigest | 불일치 | 불일치 | 불일치 | 불일치 |
  | BFloat16 | **불일치** | 동일 | 동일 | 동일 |
  | DD(0.01) | 동일 | 동일 | 동일 | 동일 |

  BFloat16 1m 상태는 같은 값을 담지만 직렬화 바이트가 재구축마다 다를 수 있다(값별 카운트 해시 테이블의 순회 순서로 보인다). D-14("재구축하면 바이트 단위로 같은 집계")를 바이트 그대로 지키는 것은 이 시험에서 DD뿐이다. 이번 결과는 합성 표본·로컬 1회이며 목표 하드웨어에서 실데이터(`--q14-source dataset`)로 다시 확인한다(13장 12).
- 소표본·이상치 분포에서 tDigest 1m→1h 병합의 p99가 크게 틀린 사례: 3,600건 중 이상치 1%에서 1,862 대 정확값 3,118(`RollupViewsIT`).

### 8.3 제안

**BFloat16**(`quantilesBFloat16(0.5, 0.95, 0.99)`)을 트랜잭션 롤업의 백분위 함수로 제안한다. 근거는 단일 날짜의 최대값이 아니라 3일 전체의 그룹 분포와 단계별 값이다: 1h·1d p99 최대 2.1%·0.5%(144·6그룹, 세 함수 중 가장 작음), 1m p50·p95 그룹 p95 오차 1.2%·1.3%(8,640그룹, 기준 2% 안), 재구축 확정값 동일(1m·1h). 1m p95 최대(3.5~3.9%, 날짜별)는 기준 2%를 넘으므로 기준 재정의(아래)가 전제다. 대가: 1m 상태 1.6배(app·service·instance 1m 롤업 일 용량 약 +0.15GB, 전체의 3%), **1m 상태 바이트는 재구축마다 같지 않을 수 있다**(8.2 결정성 표). D-14를 "확정값 동일"로 해석할 수 있으면 BFloat16, "바이트 동일"을 지켜야 하면 DD다(1m·1h 바이트 동일, 상태 최소 2.1KB, 대신 1m p50 최대 2.05%·p95 그룹 p95 1.47%로 세 함수 중 오차가 가장 크다). tDigest 유지는 값까지 재구축·실행마다 달라지므로 권하지 않는다.

이 결정은 13 §4 DDL(`q_elapsed` 타입)·14 API 조회 함수·A-11 기준 문구를 바꾸므로 PM 결정이 필요하다. 기준은 "1m 값 오차 그룹 p95 ≤ 2%(p50·p95), 1h 이상 최대 ≤ 5%(p99), 1m p99는 순위 오차 ≤ 0.5pp"로 재정의할 것을 함께 제안한다. V001은 바꾸지 않았다(tDigest 유지).

> **결정(ADR-0007 C-53)**: D-14 "바이트 동일"을 그대로 지키기 위해 BFloat16이 아니라 **DD**(`quantilesDD(0.01, 0.5, 0.95, 0.99)`)로 정했다. 기준 재정의는 위 제안대로 채택됐다. WO-03F에서 V001에 반영했다(17.1절).

## 9. Q17 동시 부하(T-ST-017, 축소)

축소 조건: 13 §15 원안은 리플레이 5,000 TPS 1시간 중 가상 사용자 10명이 Q1·Q3·Q7을 5초마다 30분이다. 로컬에서는 같은 10명·5초 간격으로 **2,000 TPS 단계와 5,000 TPS 단계에서 각각 8분** 돌렸다. 두 번 쟀다.

- **1/20 run(10:15~10:23, 10:25~10:33 UTC): 고정 창.** 러너가 Q17 시작 때 `end_minute`(그 시점 데이터의 마지막 분 + 1)를 한 번 정해 8분 내내 같은 창을 읽었다. 창은 리플레이 앞 단계의 데이터까지만 담고, **측정 중 적재되는 최신 parts는 조회하지 않았다**. 아래 첫 표의 값은 이 한계를 가진 참고값이다.
- **1/100 재측정(16:41~16:50, 16:51~17:00 UTC): live 창.** 러너를 고쳐 사용자가 반복마다 `end_minute`을 서버의 현재 분(`toStartOfMinute(now())`, 1초마다 공유 값 갱신)으로 다시 잡는다. 각 8분 동안 end_minute이 9번 바뀌었다(16:41~16:49, 16:51~16:59). Q7은 13 §6.1의 "마지막 2분 원본 보정"을 넣은 `1d_live`(service_stat_1m은 end − 2분까지, 마지막 2분은 xlog, tDigest 상태 병합)로 바꿨다. 1/100 벌크 데이터는 10-23 20:24~10-24 03:36 UTC라 **1h·10분 창은 리플레이 데이터만 담는다**: 2,000 TPS 단계의 1h 창은 단계 시작 뒤 1~9분치(목표 규모 1h보다 적다), 5,000 TPS 단계는 2,000 TPS 10분 + 5,000 TPS 1~9분치(목표 규모보다 많다), 10분 창은 리플레이 TPS 그대로라 목표(500 TPS)의 4~10배 밀도다. 1일 창(Q7)은 벌크 7.2시간 + 리플레이다. 실제로 읽은 행은 표에 적었다.

**부하도 축소다.** 리플레이는 13 §15 분포(인스턴스 50, 서비스별 상용 SQL, 캡처율 약 12%)를 썼고, 단계별 실제 적재 행/s는 다음과 같다(생성기 `--target=none`, 같은 분포 10분 시뮬레이션 실측, 15장).

| 단계 | xlog | profile_step | 통계(sql_stat + ext_stat) | metric | xlog+step+통계 | N-06 요구 대비 |
|---|---:|---:|---:|---:|---:|---:|
| 2,000 TPS | 2,003 | 3,405 | 202(179 + 23) | 1,000 | 약 5.6k | - |
| 5,000 TPS | 5,005 | 8,527 | 261(237 + 23) | 1,000 | **약 13.8k** | **약 21%**(요구 5,000 + 50,000 + 10,000 = 65k) |

N-06·Q17의 50,000 step/s·10,000 stat 행/s 부하는 이 로컬 측정에 들어 있지 않다. 그 부하를 재현하는 생성기 옵션은 4.2절, 목표 하드웨어 절차는 13장 6이다. 출처: [`r1-generator/rates/default-2000/`](../bench/wo03-storage-2026-10-24/r1-generator/rates/default-2000/20261024-160559-bulk-831b244ffc9f/report.md)·[`default-5000/`](../bench/wo03-storage-2026-10-24/r1-generator/rates/default-5000/20261024-160614-bulk-823a0cfb9fc3/report.md)(`Rows/simulated s` 열, 2026-10-24 16:05~16:06 UTC, 머신 CPU 31~51%). 1/20 리플레이의 xlog 4,501,626행도 (500 + 2,000 + 5,000) × 600초 = 450만과 맞는다.

| 항목 | 기준 | 2,000 TPS | 5,000 TPS |
|---|---|---:|---:|
| Q1 1h(원본 10초 버킷) p50 / p95 / 최대 | - | 18.6 / 23.3 / 97.3ms | 29.7 / 36.7 / 47.9ms |
| Q3 10분 XLog p50 / p95 / 최대 | - | 71.2 / 88.0 / 160.5ms | 88.5 / 113.8 / 280.1ms |
| Q7 1일 서비스 Top N p50 / p95 / 최대 | - | 62.6 / 76.0 / 143.2ms | 64.3 / 86.6 / 142.0ms |
| **쿼리 p95(전체)** | 2초 | **82ms** | **103ms** |
| 쿼리 수 / 오류 | - | 2,880 / 0 | 2,880 / 0 |
| 적재 지연 p99(생성기, 단계 전체, 1초 tick. 최대 약 1초 과대 측정이고 WAL 파일 기록·fsync는 빠짐, 4.2절) | 5초 | 3,039ms | 3,071ms |
| 파티션당 parts 최대 | < 300 | 12 | 11 |
| DelayedInserts / 거부된 insert | 0 | 0 / 0 | 0 / 0 |
| MemoryTracking 피크 / RSS 피크 | < 7.5GiB | 413MiB / 872MiB | 422MiB / 1,008MiB |
| 측정 중 머신 CPU 평균 / 최대 | - | 21% / 61% | 28% / 86% |

위 표는 1/20 **고정 창**이다(Q7은 `1d`). 아래는 1/100 **live 창** 재측정이다(리플레이 2,000 → 5,000 TPS 각 10분, 100ms tick, 생성기 run `qfixlive`; Q17은 각 단계 시작 60초 뒤부터 8분).

| 항목(1/100, live 창) | 기준 | 2,000 TPS | 5,000 TPS |
|---|---|---:|---:|
| Q1 1h p50 / p95 / 최대(읽은 행 중앙값, 최대) | - | 17.9 / 24.8 / 49.3ms(14.7만, 28.6만) | 28.8 / 43.7 / 450.0ms(69.0만, 101.2만) |
| Q3 10분 XLog p50 / p95 / 최대(읽은 행) | - | 74.2 / 97.5 / 229.5ms(29.7만, 57.1만) | 95.7 / 155.3 / 1,989.0ms(112.5만, 152.0만) |
| Q7 1일 `1d_live` p50 / p95 / 최대(읽은 행) | - | 47.2 / 57.8 / 75.0ms(11.0만, 13.2만) | 49.6 / 71.6 / 795.1ms(21.1만, 25.7만) |
| **쿼리 p95(전체)** | 2초 | **89ms** | **119ms** |
| 쿼리 수 / 오류 | - | 2,880 / 0 | 2,880 / 0 |
| 창 이동(서로 다른 end_minute) | - | 9(16:41~16:49) | 9(16:51~16:59) |
| 적재 지연 p50 / p99 / 최대(생성기, 단계 전체, 100ms tick) | p99 5초 | 1,087 / 2,143 / 2,912ms | 1,119 / 2,175 / 3,066ms |
| 파티션당 parts 최대 | < 300 | 13 | 14 |
| DelayedInserts / 거부된 insert | 0 | 0 / 0 | 0 / 0 |
| MemoryTracking 피크 / RSS 피크 | < 7.5GiB | 462MiB / 1,241MiB | 425MiB / 1,289MiB |
| 측정 중 머신 CPU 평균 / 최대 | - | 24% / 61% | 34% / 100% |

- live 창에서도 축소 조건 기준을 모두 충족했다. 최신 parts를 읽으면서 5,000 TPS 단계의 Q3 최대가 1,989ms(머신 CPU 100% 순간, p95는 155ms)로 고정 창(280ms)보다 커졌다. 적재 부하·쿼리 수는 1/20과 같지만 데이터 규모(1/100)와 창 밀도가 달라 1/20 표와 직접 비교하지 않는다.
- 리플레이 뒤 Q12 4변형·Q13 `total_30d`·`rollups_14d`·`minute_raw_1m`은 0행이었다(Q13 일 단위는 7.3절과 같은 자정 귀속 20행·야간 1행).
- 원자료: [`r1-queries/q17-2000/report.md`](../bench/wo03-storage-2026-10-24/r1-queries/q17-2000/report.md)·[`q17-5000/report.md`](../bench/wo03-storage-2026-10-24/r1-queries/q17-5000/report.md)(쿼리별 읽은 행, monitor 표), 적재 지연 [`r1-queries/replay/report.md`](../bench/wo03-storage-2026-10-24/r1-queries/replay/report.md), 리플레이 뒤 정합 [`r1-queries/after-replay/report.md`](../bench/wo03-storage-2026-10-24/r1-queries/after-replay/report.md), 머신 CPU [`r1-queries/host.tsv`](../bench/wo03-storage-2026-10-24/r1-queries/host.tsv).

출처(1/20): [`local-1of20/querybench/q17-2000/report.md`](../bench/wo03-storage-2026-10-24/local-1of20/querybench/q17-2000/report.md)·[`q17-5000/report.md`](../bench/wo03-storage-2026-10-24/local-1of20/querybench/q17-5000/report.md)(각 `runs.tsv`), 적재 지연 [`local-1of20/generator/20261024-103509-replay-wo03local/report.md`](../bench/wo03-storage-2026-10-24/local-1of20/generator/20261024-103509-replay-wo03local/report.md), CI [`ci-1of100/querybench/report.md`](../bench/wo03-storage-2026-10-24/ci-1of100/querybench/report.md)(Q17 절).

- **축소 부하(약 13.8k 행/s, 요구 약 65k 행/s의 약 21%)와 축소 시간(8분)에서 Q17 기준을 모두 충족**했다. N-06 부하에서의 판정이 아니다. 목표 판정은 N-06 부하 5,000 TPS 1시간 + 쿼리 30분(목표 하드웨어, 13장 6의 3b)이다. 통계 10,000행/s는 13 §14 파라미터로는 도달할 수 없어 PM 결정 항목으로 올렸다(14.2 9항).
- CI 1/100의 `BenchRunIT`는 적재 없이 3명 × 12초로 하네스만 확인한다(p95 75ms).

## 10. Q18~Q20 기록(T-ST-018~020)

### 10.1 Q18 TTL·DROP·삭제 뮤테이션(1/20, 하루 = 2026-10-23 사본, 4,320만 행 3.7GB)

| 작업 | 소요 | 비고 |
|---|---:|---|
| 하루 사본 만들기(INSERT SELECT) | 73.6초 | 17 parts |
| 삭제 뮤테이션 `DELETE WHERE toDate(ts)=d AND app AND user_id` | 100.9초 | 대상 253,978행 → 0. **통제 없는 측정**(아래) |
| 삭제 뮤테이션 `DELETE IN PARTITION d WHERE app AND user_id` | 53.1초 | 대상 139,012행 → 0. **통제 없는 측정**(아래) |
| `DROP PARTITION d` | 69ms | 3.68GB, 3 parts |
| DROP 뒤 디스크 회수 | **> 600초** | 10분 대기 뒤에도 outdated part 7개가 남았다(`old_parts_lifetime` 기본 480초 + 정리 주기) |
| `MODIFY TTL 1 DAY`(materialize 0) | 51ms | 메타데이터만 |
| `MATERIALIZE TTL`(가장 오래된 날 사본 720만 행) | 8.1초 | 720만 → 0행, parts 7 → 2 |

1/100: 사본 648만 행 13.2초, `DELETE IN PARTITION` 14.4초, `DELETE WHERE`는 20초 대기 안에 끝나지 않음, DROP 42ms, MATERIALIZE TTL 11.6초.

출처: [`local-1of20/querybench/qA/report.md`](../bench/wo03-storage-2026-10-24/local-1of20/querybench/qA/report.md)(Q18 절), 1/100 [`ci-1of100/querybench/report.md`](../bench/wo03-storage-2026-10-24/ci-1of100/querybench/report.md)(Q18 절).

- **위 두 삭제 형식 비교는 교란돼 있었다**: 사본이 하루치(한 파티션)뿐이라 두 형식이 같은 part 집합에 작용했고, 대상이 서로 다른 사용자(25.4만 대 13.9만 행)였으며, 같은 사본에 순차로 실행했다. 그래서 위 101초 대 53초로 형식의 차이를 말할 수 없다.
- **통제한 재측정(1/100, run P, 2026-10-24 16:19~16:21 UTC)**: Q18을 고쳐 같은 내용의 사본 2개(`q18_xlog_a`·`q18_xlog_b`, 2026-10-23·10-24 두 파티션, 각 1,296만 행 1.1GB)를 만들고, 반복마다 같은 대상(app_id, user_id)을 한 사본에는 `DELETE WHERE toDate(ts)=d …`, 다른 사본에는 `DELETE IN PARTITION d …`로 지웠다. 실행 순서를 반복마다 바꿨고(1회차 a→b = WHERE→IN, 2회차 a→b = IN→WHERE) 매 측정 전에 머지가 멈추길 기다렸다.

  | 회차 | 순서 | 형식 | 사본(활성 parts) | 대상 행(d) | parts_to_do 첫 관측 | 다시 쓴 part | 소요 | 머신 CPU |
  |---|---|---|---|---:|---:|---:|---:|---:|
  | 1 | 1 | WHERE | a(5) | 38,112 → 0 | 4 | 2 | 9.24초 | 50% |
  | 1 | 2 | IN PARTITION | b(13) | 38,112 → 0 | 13 | 4 | 7.63초 | 39% |
  | 2 | 1 | IN PARTITION | a(5) | 21,073 → 0 | 2 | 2 | 5.73초 | 49% |
  | 2 | 2 | WHERE | b(13) | 21,073 → 0 | 13 | 4 | 10.71초 | 38% |

  - **같은 사본 안에서 IN PARTITION이 29~38% 짧았다**: 사본 a 9.24 → 5.73초, 사본 b 10.71 → 7.63초(형식별 범위 WHERE 9.2~10.7초, IN PARTITION 5.7~7.6초). 다시 쓴 part 수는 같은 사본에서 두 형식이 같았다(a 2·2, b 4·4). 차이는 다시 쓰는 양이 아니라 대상 아닌 part의 처리에서 오는 것으로 보이지만, 이 측정으로는 원인을 가르지 못했다(parts_to_do 첫 관측: 사본 a WHERE 4 대 IN 2, 사본 b 13 대 13).
  - 한계: 머지를 기다렸지만 두 사본의 part 구성이 달랐다(a 5, b 13). 그래서 형식 비교는 사본 안에서만 한다. 회차마다 대상 사용자가 다르다(같은 사용자를 두 번 지울 수 없다). 2회 반복·1/100·공유 머신(CPU 38~50%)이며, 5,000 TPS 하루 파티션(약 30GB)에서의 절대 시간은 목표 하드웨어에서 잰다(13장 7).
  - 권고 근거: 이 재측정과, 스키마 IT `ConfirmationItemsIT`의 관찰(`DELETE IN PARTITION` 뒤 다른 날 part의 파일 링크 수가 1보다 크다 = 다시 쓰지 않고 하드링크, 기록 전용)이다. 원자료: [`r1-queries/run-P/report.md`](../bench/wo03-storage-2026-10-24/r1-queries/run-P/report.md)(Q18 절).
  - **통합 재검증에서 재현되지 않았다**(같은 1/100 데이터·같은 명령, 2026-10-24 18:2x UTC, 머신 CPU 23~33%, 16장): 사본 a(활성 14 parts) WHERE 5.67초 → IN PARTITION 8.26초, 사본 b(6 parts) IN PARTITION 5.93초 → WHERE 5.54초로 이번에는 **같은 사본 안에서 WHERE가 7~31% 짧았다**. 두 실행 8건을 합치면 WHERE 5.5~10.7초(평균 7.8초), IN PARTITION 5.7~8.3초(평균 6.9초)로 범위가 겹친다. 사본의 part 수(5~14)와 반복 순서가 형식보다 큰 변수였다. 따라서 1/100에서 **두 형식의 소요 시간 차이는 결론 없음**이다. `DELETE IN PARTITION` 권고(14.2 5항)는 시간이 아니라 "대상 날 밖의 part를 다시 쓰지 않는다"(하드링크 관찰)와 뮤테이션 범위를 파티션으로 한정한다는 성질에 근거하며, 시간 비교는 목표 하드웨어(13장 7, 반복 3회 이상)에서 다시 한다. 원자료: [`r1-integration/querybench-p/report.md`](../bench/wo03-storage-2026-10-24/r1-integration/querybench-p/report.md)(Q18 절).
  - 같은 실행의 DROP PARTITION(사본 a, 1.1GB 5 parts) 40ms, 10분 뒤에도 outdated part 2개가 남았다. `MATERIALIZE TTL`(10-23 사본 648만 행, 1일 TTL)은 7.8초였지만 데이터가 하루 미만이라 지운 행은 없다(재계산만, parts 7 → 2).
- 13 §8 비상 보존 단축(디스크 90%)은 DROP 직후 디스크가 돌아오지 않는다. 런북은 회수 지연(이 측정에서 10분 이상)을 감안하거나 `old_parts_lifetime`을 낮추는 설정을 검토해야 한다(17 미결 10, 14장).
- 완료 판정은 `argos_reader`의 `system.mutations` 폴링으로 한다(`argos_migrator`는 system 테이블 권한이 없다).

### 10.2 Q19 마이그레이션 리허설(1/20 본 데이터, 기준 각 10분)

| 단계 | 소요 |
|---|---:|
| `ADD COLUMN` | 46ms |
| `DROP COLUMN`(xlog 6,487만 행) | 4.9초 |
| `MODIFY TTL` materialize 0 | 32ms |
| `MODIFY TTL` materialize 1(같은 값) | 24ms. **no-op 대조**: 같은 식이면 재계산이 일어나지 않아 TTL 변경 비용을 재지 않는다 |
| `MODIFY TTL` materialize 1(값 변경) | **미측정(no-op)**. 아래 1/100 재측정 |
| MV 교체 1: v2 테이블 + MV 생성 | 83ms |
| MV 교체 2: 하루 백필(재구축 문장, D-14) | 11.7초 |
| MV 교체 3: v1 = v2 비교 | 469ms, 불일치 0 |
| MV 교체 4: 정리 | 23ms |

1/100 재측정(run P, 2026-10-24 16:31 UTC, xlog 12,961,791행·9 parts·1.1GB). Q19에 실제로 값이 바뀌는 단계를 넣었다: `ttl_change_1`(보존 xlog-days + 1일, `materialize_ttl_after_modify = 1`, `mutations_sync = 2`, 지워지는 행 없이 모든 part의 TTL 재계산), 측정 뒤 `ttl_restore_0`(원래 값, materialize 0, 메타데이터만). `ttl_noop_1`은 `system.mutations`에 뮤테이션을 만들지 않았고 `ttl_change_1`은 `MATERIALIZE TTL` 뮤테이션 1건(완료)을 만들었다.

| 단계(1/100) | 소요 | 머신 CPU |
|---|---:|---:|
| `ADD COLUMN` / `DROP COLUMN` | 37ms / 1.92초 | 33~34% |
| `MODIFY TTL` materialize 0 / no-op 대조(materialize 1, 같은 값) | 33ms / 22ms | 47% |
| **`MODIFY TTL` materialize 1, 값 변경(+1일)** | **1.10초** | 50% |
| 원복(materialize 0, 기록만) | 29ms | 52% |
| MV 교체 1 / 2(하루 백필, 2026-10-24 3.6시간분) / 3(불일치 0) / 4 | 53ms / 0.98초 / 85ms / 12ms | 44~52% |

- **30일 13억 행 추정(TTL 값 변경, materialize 1)**: 1/100 처리량 1,175만 행/s(1,296만 행 1.10초)로는 약 **111초**다. 같은 TTL 재계산이 Q18의 `MATERIALIZE TTL`에서는 훨씬 느렸다: 1/20 720만 행 8.1초(89만 행/s, 행 삭제 포함), 1/100 648만 행 7.8초(83만 행/s, 삭제 없음, 갓 만든 사본이라 머지와 겹침). 이 처리량이면 13억 행에 **약 24~26분**이다. 머지가 끝난 본 테이블(Q19)과 막 채운 사본(Q18)의 차이로 보이지만 확정하지 못했으므로 목표 규모 추정은 **2~26분**이고, 10분 기준을 넘을 수 있다. 목표 하드웨어 전체 데이터에서 `ttl_change_1`을 재는 것이 필수다(13장 8).
- 원자료: [`r1-queries/run-P/report.md`](../bench/wo03-storage-2026-10-24/r1-queries/run-P/report.md)(Q19 절).

출처: [`local-1of20/querybench/qA/report.md`](../bench/wo03-storage-2026-10-24/local-1of20/querybench/qA/report.md)(Q19 절).

측정한 단계는 1/20·1/100 모두 10분 기준 안이다. 단 1/20의 "materialize 1"은 no-op이라 **값 변경 TTL은 1/20에서 미측정**이고, 1/100에서 잰 값 변경(1.10초)의 목표 규모 추정은 2~26분으로 기준 충족을 단정할 수 없다. 하루 백필은 규모와 무관하게 하루치 양이라 목표 규모에서도 비슷할 것으로 본다(목표 하드웨어 확인).

### 10.3 Q20 압축 비교

5.3절. 결론은 12.5절 코덱 제안.

## 11. 13 §17 확인 항목, 12 미결 2·6, 17 미결 1

번들 ClickHouse 26.8.34.13에서 확인했다. 근거는 `:test:storage:schema-tests:integrationTest`(51건, 실제 ClickHouse)와 이 문서의 벤치다.

| # | 항목 | 결과 | 근거 |
|---|---|---|---|
| 13 §17 #1, 12 미결 2 | `insert_deduplication_token` + `non_replicated_deduplication_window` + `deduplicate_blocks_in_dependent_materialized_views`가 비복제 MergeTree + MV에서 동작 | **동작한다.** 같은 토큰 100회 재삽입 시 원본·1m·1h 증가 0. MV 하나가 실패한 뒤 같은 토큰으로 재시도하면 실패한 MV(와 1h 캐스케이드)만 채워진다. 자정 경계로 쪼개진 블록, 재기동, 26.3→26.8 업그레이드 뒤에도 유지. **단 1h 테이블에도 window가 있어야 한다**(없으면 재시도 때 1h가 이중 집계 400→800). V001은 1h 테이블 전부에 window 1000을 넣었다 | `DeduplicationTokenIT` 6/6, T-ST-032(Q15) 1/100·1/20 |
| #2 | `quantilesBFloat16`·`quantilesDD`의 `-State/-MergeState`와 상태 크기 | 둘 다 지원. 1m 상태(분당 3,000값, RowBinary): DD 2,122B, tDigest 2,790B, BFloat16 4,506B | `ConfirmationItemsIT`, Q14 |
| #3 | `parts_to_delay_insert/throw_insert` 기본값 | 26.8 기본 1000/3000. config.d로 실효 300/1000 | `ConfirmationItemsIT` |
| #4 | `max_partition_size_to_drop=0` 서버 설정 | 적용된다. 쿼리 설정으로도 덮어쓸 수 있다. 테이블 단위 MergeTree 설정은 없다(`UNKNOWN_SETTING`) | `ConfirmationItemsIT` |
| #5 | `DateTime64` TTL의 `toDateTime()` 캐스트 | 캐스트 유무와 관계없이 동작. V001은 캐스트 유지 | `ConfirmationItemsIT` |
| #6 | 집계 상태 LTS 간 호환 | 26.3.60.2가 쓴 tDigest·sumForEach 상태가 26.8에서 바이트 단위로 같고 finalize 값도 같다. **단 tDigest 병합값은 버전·병합 순서에 따라 다를 수 있다**(한 키 80.4 vs 80.22222). D-14 "바이트 동일 재구축"은 tDigest 병합값에는 성립하지 않는다 | `UpgradeIT`(T-ST-051) |
| #7 | `convert_to_replicated` 플래그 | 26.8에서 동작. 플래그 파일을 **테이블 데이터 디렉터리에 직접** 두고(`flags/` 하위는 무효) Keeper·macros를 구성하면 재기동 때 `ReplicatedMergeTree('/clickhouse/tables/{uuid}/{shard}','{replica}')`로 바뀌고 데이터·MV 공급이 유지된다 | `ReplicatedConversionIT` |
| #8 | `Array(UInt64)` bloom이 `has()`에 적용 | 적용된다(30만 행 중 읽은 행 32,768 vs 미사용 319,949). 단 자주 쓰이는 SQL id는 거의 모든 그래뉼에 있어 스킵 효과가 작다(12.6절) | `ConfirmationItemsIT`, Q05/bloom |
| #9 | `Gorilla`를 `Float64`에 | 허용, 값 정확 왕복 | `ConfirmationItemsIT` |
| #10 | `SimpleAggregateFunction(sumForEach)` | **미지원**. 대체안 `AggregateFunction(sumForEach, Array(UInt64))`를 쓰고 조회는 `sumForEachMerge(buckets)`(13 §6.3 (8)·14 API 수정 필요) | V001, `RollupViewsIT` |
| #11 | `tx_id` 노드 비트 충돌 | 범위 밖(에이전트·서버). 1/100·1/20 데이터에서 Q5 단건 조회는 항상 1행 | Q5 |
| #12 | 메모리 예산 | 5GiB 컨테이너(서버 4.5GiB)에서 벌크 피크 848MiB, 프로시저(사본·뮤테이션) 피크 1.2GiB, 리플레이·Q17은 9장. 7.5GiB 비율은 목표 하드웨어·WO-27에서 확정 | 4·9장 |
| #13 | 행당 바이트 기대값 | 디스크 기준(`bytes_on_disk`, 13 §14 공식 기준): B_x 86.0B(기대 70B, +23%), B_p 14.5B, B_m 2.6B, B_s 27.1B, B_e 21.5B. 일 증가량 약 5.6GB(표 5.3GB, +6%, ±30% 안). data_compressed 기준: B_x 82.7B(+18%), B_e 21.4B, 일 증가량 약 5.5GB(+4%) | 5.1절 |
| #14 | 버킷 p95 UI 표기 | 범위 밖(15 문서). 버킷 p95 오차 1m 최대 19.6%·1h 6.8%로 "추정값" 표기가 필요함을 확인(C-14) | Q14 |
| #15 | `pg_trgm` | 범위 밖(WO-20) | - |
| #16 | `instance_stat_1m` 추가의 parts 영향 | 1/20 벌크·리플레이·프로시저 중 파티션당 parts 최대 17(기준 300) | 4.3절 |
| 12 미결 6 | `system.mutations` 폴링으로 삭제 완료 판정 | 가능. 단 `argos_migrator`는 `system.*`를 읽을 수 없어(`select_from_system_db_requires_grant`) 폴링은 `argos_reader`로 한다 | `ConfirmationItemsIT`, Q18 |
| D-14(13 §4.1) | 롤업 재구축이 MV와 같은 결과를 내는가 | **검증한 범위**: rollups 파일 12개 전부, 1m은 하루(`toDate(ts) = D`)·1h는 한 달(`toYYYYMM(minute) = M`)을 staging 테이블에 넣었을 때 MV가 채운 테이블과 전체 키(app_id, env, kind, 차원, minute/hour) 단위로 일치한다(소규모 워크로드, 키 12~1,364개/테이블). `SimpleAggregateFunction` 열과 `sumForEach` 버킷은 정확히 같고, tDigest `q_elapsed`(병합 순서 의존, 8장·#6)와 `anyLast` `last_v`(읽기 순서 의존, 14.3)는 비교에서 뺐다. 재구축 **절차**는 staging + `REPLACE PARTITION`(1m 하루 → 같은 달 1h)일 때만 원본↔1m↔1h가 맞는다. 13 §4.1 문구대로 롤업 테이블에 직접 `INSERT`하면 1h 캐스케이드로 그 날이 두 번 더해진다(`DROP PARTITION` 뒤에도 1h 합계 2배: app 4,800 vs 원본 2,400). `REPLACE PARTITION`은 MV를 거치지 않으므로 1m을 고친 뒤 1h는 따로 다시 만들어야 한다. 목표 규모·실데이터 분포에서의 동일성은 확인하지 않았다 | `RollupViewsIT.rebuildEqualsView`, `RollupRebuildIT` 2건, 14.2 |
| 16 §10.8 | CH 관리 계정의 `BACKUP` 권한 | 권한명만 V001 계정(`argos_migrator`)에 반영했고 백업 동작은 검증 범위 밖이다. 26.8에서는 `BACKUP` 권한만으로 `BACKUP … TO Disk/File/S3`가 `ACCESS_DENIED`다(각각 `WRITE ON DISK`·`WRITE ON FILE`·`WRITE ON S3` 필요, 수동 실측 2026-10-25). `backups.allowed_disk`도 설정하지 않았다 | 14.3 |
| 17 미결 1 | 고정 LTS 버전 | **26.8.34.13**(`v26.8.34.13-lts`, 2026-10-22), `clickhouse/clickhouse-server:26.8.34.13@sha256:8b28eb09479c36c4533eec7a6268917d04ba3a6fd90e3f4205f70e761b6d7779`(amd64·arm64 매니페스트 리스트). 업그레이드 시험용 이전 LTS 26.3.60.2(`sha256:3cbe484d8571988bae07dfe98bb0f62778a5ad8225745be9366acf5f1debd9e8`). `deploy/clickhouse/image.properties` | `UpgradeIT` |

## 12. 결정 제안(근거 표)

모든 제안은 **로컬 참고 근거**이고 13장의 목표 하드웨어 확인 뒤 확정한다. 결정 초안은 [ADR-0006](../adr/0006-storage-poc-decisions.md)(Proposed).

### 12.1 A-6 트랜잭션 상세 조회의 ts(확정 검증)

| 근거 | 값 |
|---|---|
| Q5 `tx_id` 복원 ±2초(app 있음/없음), URL ts | 1/20 웜 16 / 25 / 18ms, 콜드 66~149ms, 1 mark. 1/100 34 / 29 / 41ms |
| 복원 실패율 | 시계가 5초 넘게 앞선 인스턴스는 서버가 ts를 수신 시각으로 clamp(D-15)하지만 `tx_id`는 에이전트 시각을 유지한다. 1/20 하루 xlog의 **2.86%**(1,233,574 / 43,204,363행, +60초 인스턴스 2개)가 ±2초 밖이라 복원 조회가 0행 |
| 폴백 비용 | `toDate(ts) BETWEEN toDate(t0) - 1 AND toDate(t0) AND tx_id = ?`(idx_tx bloom): 4~5ms, 1 mark(app 유무 무관). 범위 없는 bloom 전체(Q5 bloom_only) 16.5ms(행·mark만 같고 인덱스 읽기 바이트는 30일이면 약 15배, 6.3절) |
| 저장소 도구(재현) | Q05에 probe `skewed`(마지막 하루에서 ts가 tx_id 복원 시각과 2초 넘게 다른 행, 날짜 차이가 큰 것 우선), 변형 `restored_skewed`(복원 ±2초, 기록: 0행이 정상), `fallback_2d`(위 폴백, 1행 기대·0.5초 기준), `fallback_coverage`(마지막 하루의 복원 실패·폴백 실패 수)를 넣었다. 시계 오차 트랜잭션이 없으면 두 변형은 NOT_APPLICABLE |
| 1/100(+60초 인스턴스 2개, run P) | 전체 7.2시간 12,961,791행 중 복원 실패 **370,227행(2.86%)**, 폴백 실패 **0행**(하루 전으로 넘어간 816행 포함). `restored_skewed` 0행·`fallback_2d` 1행 웜 27ms(1 mark, 압축 읽기 1.4MB) |
| **1/100 소규모(+25시간 인스턴스 2개)** | `--skewed-offset-ms=90000000` 2일 1 TPS 데이터(마지막 하루 86,565행): 복원 실패 2,463행, 그중 **폴백 실패 94행(3.8%)**: 23시 이후 시작해 복원 날짜가 이틀 뒤로 넘어간 트랜잭션. probe가 고른 이 경우의 `fallback_2d`는 **0행**(INVALID), 나머지 2,369행은 하루 전 파티션에서 찾았다 |

출처: Q5는 [`local-1of20/querybench/qA2/report.md`](../bench/wo03-storage-2026-10-24/local-1of20/querybench/qA2/report.md)·1/100 [`ci-1of100/querybench/report.md`](../bench/wo03-storage-2026-10-24/ci-1of100/querybench/report.md), 폴백 비용은 [`local-1of20/sizes/a6-fallback.txt`](../bench/wo03-storage-2026-10-24/local-1of20/sizes/a6-fallback.txt)(열 설명은 증거 디렉터리 README). 복원 실패율(2.86%)은 측정 중 수동 집계 값이고 원자료 파일이 남아 있지 않다(13장 11항에서 재측정).

**제안**: A-6(복원 ts) 유지. ±2초 조회가 0행이면 2일 파티션 bloom 조회로 폴백한다(14 API, 13 §6.3 (5) 보강). 서버 가드가 clamp한 행은 ts < 복원 시각이므로 하루 전 파티션까지 본다. **적용 한계**: 이 폴백은 에이전트 시계가 약 24시간 이내로 앞선 경우에만 찾는다(t0와 수신 ts가 다른 날이어도 하루 차이까지). 24~48시간 앞서면 시작 시각에 따라 찾거나 못 찾고(+25시간 실측 3.8% 실패), 48시간 이상이면 못 찾는다. 생성기의 기본 모델은 +60초 인스턴스뿐이다. 한계를 넘는 경우의 처리는 PM 결정 항목이다(14.2 4항): (a) 2일 폴백도 0행이면 범위 없는 bloom 조회(Q5 `bloom_only` 경로, 30일이면 idx_tx 약 1.6GB 읽기, 6.3절)로 한 번 더 찾기, (b) "시계 오차로 찾을 수 없음" 오류를 내고 URL ts 경로를 쓰게 하기.

출처(재현 도구): [`r1-queries/run-P/report.md`](../bench/wo03-storage-2026-10-24/r1-queries/run-P/report.md)(Q05 변형), [`r1-queries/skew25h/report.md`](../bench/wo03-storage-2026-10-24/r1-queries/skew25h/report.md), 집계 SQL과 결과 [`r1-queries/a6-coverage.txt`](../bench/wo03-storage-2026-10-24/r1-queries/a6-coverage.txt).

### 12.2 A-9 1.0부터 ReplicatedMergeTree 여부

| 근거 | 값 |
|---|---|
| 전환 경로 | `convert_to_replicated` 26.8 동작 확인(플래그 위치 주의), 데이터·MV 공급 유지(`ReplicatedConversionIT`) |
| 단일 노드 운영 | dedup·MV는 비복제 MergeTree에서 문서대로 동작(1h window 필수). Keeper 없이 Q1~Q16 충족 |
| 비용 | Keeper(내장) 추가 시 메모리·프로세스·백업 대상 증가(측정 안 함) |

**제안**: 02 §12 추천 유지. 1.0은 MergeTree, 전환 절차(플래그 파일을 테이블 데이터 디렉터리에 둠, Keeper·macros, 재기동)를 17 문서 런북에 싣는다. 전환 뒤 dedup window는 `replicated_deduplication_window`로 바뀌므로 1h 테이블 포함 값을 런북에 명시한다.

### 12.3 A-10 `xlog.sql_ids` 채택

| 근거 | 값 |
|---|---|
| 디스크 비용(Q11/cost, 하루 4,320만 행) | V001(LZ4): **10.10B/행 + bloom 0.006B = 0.437GB/일**(500 TPS), xlog의 12%. ZSTD(1) 기본이면 **3.57B/행 = 0.15GB/일** |
| 검색 성능(Q11 `has(sql_ids, ?)` 1일, LIMIT 1000) | 1/20 웜 94ms·콜드 291ms(기준 2초), 1/100 130ms |
| bloom 효과 | 자주 쓰이는 SQL(앱 내 21번째)은 1,372~2,069 marks를 읽는다(스킵 거의 없음). 드문 SQL에는 효과(30만 행 중 32,768행만 읽음, §17 #8). 인덱스 크기 0.007B/행 |
| 관련 트랜잭션 30일(Q9) | 66ms |

출처: [`local-1of20/querybench/qA/report.md`](../bench/wo03-storage-2026-10-24/local-1of20/querybench/qA/report.md)(Q11/cost·Q05/bloom 절), Q9·Q11 시간은 [`qA2/report.md`](../bench/wo03-storage-2026-10-24/local-1of20/querybench/qA2/report.md), ZSTD 열 크기 [`local-1of20/sizes/xlog-columns-codecs.txt`](../bench/wo03-storage-2026-10-24/local-1of20/sizes/xlog-columns-codecs.txt), 1/100 [`ci-1of100/querybench/report.md`](../bench/wo03-storage-2026-10-24/ci-1of100/querybench/report.md).

**제안**: 채택. 최대 개수는 V001 주석대로 64개로 상한(N+1 루프 방지), 코덱 제안(ZSTD)과 함께 쓰면 비용이 xlog의 6%다.

### 12.4 A-11 백분위 함수

8장. **BFloat16 제안**, 기준 재정의 필요(PM). DD는 상태 크기 우선 시, 또는 D-14를 "바이트 동일"로 지켜야 할 때의 대안(재구축 결정성 실측에서 바이트까지 같은 것은 DD뿐, 8.2절).

### 12.5 코덱

| 선택지(하루 사본, 1/20) | xlog | profile | metric | sql_stat | ext_stat | 하루 스캔 CPU |
|---|---|---|---|---|---|---|
| V001(LZ4 기본, 현재) | 2.53 / 82.6B | 7.13 | 13.01 | 5.11 | 5.11 | 6.7초 |
| ZSTD(1) 기본, 열 코덱 그대로 | 3.15 / 66.4B | 10.26 | 12.88 | 11.25 | 10.96 | 8.0초 |
| **제안** | **3.76 / 55.7B** | **11.20** | **14.44** | **11.38** | **10.98** | 8.0초(+19%) |

출처: 5.3절과 같다([`local-1of20/codec/`](../bench/wo03-storage-2026-10-24/local-1of20/codec/codec-sizes.txt), [`qA/report.md`](../bench/wo03-storage-2026-10-24/local-1of20/querybench/qA/report.md) Q20 절).

**제안 내용**:
- 서버 기본 압축을 `ZSTD(1)`로(`config.d`의 `<compression><case><method>zstd</method><level>1</level></case></compression>`, 또는 테이블 `default_compression_codec`).
- 열 코덱의 `LZ4` 고정을 `ZSTD(1)`로: `DoubleDelta, ZSTD(1)`(ts), `T64, ZSTD(1)`(시간·카운터), `Gorilla, ZSTD(1)`(metric 값). metric은 열 코덱 유지가 핵심(끄면 13.0 → 6.0).
- xlog: `parent_span_id`의 `CODEC(NONE)` 제거(90%가 0, 8.0 → 1.0B), `trace_id`의 `CODEC(NONE)` 제거(Argos 생성 trace의 시간 접두, 16.0 → 14.7B), `tx_id`에 `CODEC(Delta, ZSTD(1))`(7.0 → 3.4B). `span_id`는 `CODEC(NONE)` 유지(랜덤).
- 대가: 하루 전체 스캔 CPU +19%. 대시보드·검색 쿼리는 측정 잡음 안. insert CPU는 공유 머신 잡음(±40%) 안에서 차이를 가리지 못했다(목표 하드웨어에서 Q20 재측정).
- 효과: 일 증가량 약 5.5GB → 3.7GB(500 TPS, data_compressed 기준. 디스크 기준 약 5.6GB → 약 3.9GB 추정, 5.1절), xlog 압축률 기준 ≥ 3 충족.

### 12.6 bloom 파라미터

| 근거(1/20 하루 사본, 범위 없는 조회) | fpp 0.01 / G1 | 0.01 / G4 | 0.025 / G1 | 0.025 / G4 |
|---|---|---|---|---|
| 스킵 인덱스 크기(5개 합) | 142.5MB(3.30B/행) | 140.4MB(3.25B/행) | 114.0MB(2.64B/행) | 112.4MB(2.60B/행) |
| tx_id | 1 mark | 1 mark | 1 mark | 1 mark |
| trace_id | 2 marks | 2 marks | 2 marks | 2 marks |
| user_id(자주 나오는 사용자) | **299 marks, 488만 행** | 773 marks, 871만 행 | 303 marks, 490만 행 | 786 marks, 883만 행 |
| has(sql_ids)(중간 빈도 SQL) | 1,372 marks | 1,372 marks | 1,373 marks | 1,371 marks |

인덱스별 크기(V001 0.01/G4, 1/20 전체): idx_tx 1.26B/행, idx_trace 1.22B/행, idx_request 0.61B/행, idx_user 0.16B/행, idx_sql 0.007B/행. 조회 시간은 같은 시점 머신 부하(100%)로 흔들려(10~1,197ms) 판단에 쓰지 않고 marks·읽은 행으로 판단했다. **인덱스 읽기 바이트(fpp·GRANULARITY별)는 이 비교에서 미측정**이고 목표 하드웨어에서 확인한다(러너는 이제 `ReadCompressedBytes`를 기록한다, 6.3절·13장 10).

출처: [`local-1of20/querybench/qA/report.md`](../bench/wo03-storage-2026-10-24/local-1of20/querybench/qA/report.md)(Q05/bloom 절), 인덱스별 크기 [`local-1of20/sizes/xlog-skip-indexes.txt`](../bench/wo03-storage-2026-10-24/local-1of20/sizes/xlog-skip-indexes.txt).

**제안**: fpp 0.01 유지(0.025로 줄여도 xlog의 0.8%만 절약되고 ID 단건 조회 0.5초(N-24)가 우선), GRANULARITY 4 유지, **`idx_user`만 GRANULARITY 1**(읽는 행 44% 감소, 크기 차이 없음). `idx_sql`은 효과가 드문 SQL에 한정되지만 크기가 무시할 만해 유지.

## 13. 목표 하드웨어 확인 목록(사람, T-ST-001~020)

환경: 사내 `argos-storage`(8코어/16GB/NVMe 500GB 이상), Linux, Docker. 절차와 명령은 [`test/storage/bench/README.md`](../../test/storage/bench/README.md).

1. **준비**: `ARGOS_BENCH_MEMORY_GIB=8 test/storage/bench/bench-server.sh start`(서버 7.2GiB). 머신이 전용인지 확인(다른 부하 없음), `uname -a`·`nproc`·`free -g`·디스크 모델 기록.
2. **벌크 전체 규모**(T-ST-041·050): `storageBench -Pbench.machine-profile=argos-storage -Pstorage.profile=full -Pstorage.target=url:… -Pstorage.apply-schema=true`. 기록: 행/s, 테이블별 압축률·B/행, parts 최대, MemoryTracking 피크, DelayedInserts. 판정: xlog ≥ 3·profile ≥ 5·metric ≥ 8(V001 그대로와 12.5 제안 코덱 둘 다), B_* 공식 ±30%.
3. **Q1~Q11**(T-ST-001~011): `storageQueries --procedures none -Pqueries.cold="command:… restart-dropcache"`. 판정: 6장 기준(웜 중앙값). 30일 데이터에서 Q9·Q11 30일, Q12·Q13 소요 기록.
4. **Q12·Q13·Q15·Q16**(T-ST-012·013·015·016): 불일치 0(Q13 일 단위는 7.3절 결정에 따라).
5. **Q14**(T-ST-014): `--q14-source dataset`(실데이터 분포)와 `synthetic` 둘 다. A-11 결정 확인(BFloat16 제안).
6. **Q17**(T-ST-017): 판정: 쿼리 p95 2초, 적재 지연 p99 5초, parts < 300, DelayedInserts 0, 메모리 < 7.5GiB. 두 번 돌린다(명령은 bench README 3a·3b).
   - Q17은 **live 창**으로 돈다(`--q17-ingestion`을 주면 기본, 명시하려면 `--q17-window live`): 가상 사용자가 반복마다 쿼리 창의 끝을 서버의 현재 분(`toStartOfMinute(now())`, 1초마다 갱신)으로 잡아 리플레이가 쓰는 최신 parts를 읽고, Q7은 마지막 2분을 원본에서 더하는 `1d_live` 형태다(13 §6.1). 보고서의 "window" 메모(서로 다른 end_minute 수)와 쿼리별 읽은 행으로 창이 움직였는지 확인한다.
   - 3a(13 §15 분포): `storageGenerate -Pstorage.mode=replay -Pstorage.target=url:$URL -Pstorage.tps-steps=500,2000,5000 -Pstorage.step-duration=PT1H -Pstorage.tick-ms=100`, 5,000 TPS 구간(시작 7,500초 뒤)에 10명 × (Q1·Q3·Q7) 5초 간격 30분. 기대 적재 행/s(5,000 TPS): xlog 5,000 · profile_step 약 8,500 · 통계 약 260 · metric 1,000(N-06의 약 21%, 축소 부하).
   - 3b(**N-06 부하**, ADR-0007 C-62로 바뀜): `storageGenerate -Pstorage.mode=replay -Pstorage.target=url:$URL -Pstorage.tps-steps=5000 -Pstorage.step-duration=PT1H -Pstorage.instances-per-app=50 -Pstorage.sqls=6000 -Pstorage.active-sqls=600 -Pstorage.profile-step-rate=50000 -Pstorage.tick-ms=100 -PstorageXmx=4g`, 시작 15분 뒤부터 Q17 30분. 기대 적재 행/s: xlog 5,000 · profile_step 50,000 · sql_stat 약 11,250 + ext_stat 약 200(통계 약 11,450, N-06 기준 10,000 이상) · metric 10,000(합계 약 76k, 17.4절 생성기 단독 실측). 생성기가 끝에 출력하는 `statistics rows/simulated s … N-06 bar 10,000: reached`와 보고서 `Rows/simulated s`를 먼저 보고 기록한다. NOT reached면 N-06 판정을 하지 않고 Gate 0 미결로 남긴다(C-62). (WO-03 판의 300 인스턴스 × 활성 SQL 200은 통계 약 5,400행/s로 닿지 않았다, 14.2 9항.)
7. **Q18**(T-ST-018, T-ST-054): 5,000 TPS 하루 파티션의 DROP·디스크 회수 시간, 삭제 두 형식 비교(같은 내용 사본 2개, 같은 대상, 순서 교대, `--q18-repeats 4`(1/100 두 실행에서 형식 간 순서가 반대였으므로 반복을 늘린다, 10.1절); 사본 2개가 디스크 예산을 넘으면 `--q18-copy-hours`로 자정 주변만), TTL 반영 시간.
8. **Q19**(T-ST-019, T-ST-052): 전체 데이터에서 각 단계 10분 이내. 보존 기간 값을 실제로 바꾼 `ttl_change_1`(materialize 1)이 판정 대상이고 `ttl_noop_1`은 no-op 대조다. 1/100 처리량으로는 2~26분 추정이라 기준 충족이 불확실하다(10.2절).
9. **Q20**(T-ST-020): V001·ZSTD·제안 코덱의 압축률과 insert CPU(전용 머신에서 3회 반복): `./gradlew :test:storage:queries:storageQueries -Pqueries.args="--url $URL --queries none --procedures Q20 --q20-repeats 3"`. 변형 `proposed`가 12.5 제안 정의다(`ProposedCodecs`). 보고서의 "Ratio per repetition" 표로 반복 간 차이를 보고, 그보다 작은 순위 차이는 잡음으로 본다.
10. **bloom**(Q05/bloom `--bloom-days 3`): 12.6 제안 확인. 30일 전체 데이터에서 **Q5 `bloom_only`·Q6 `30d`의 콜드(재기동 + 페이지 캐시 drop)·웜 시간과 `ReadCompressedBytes`**(보고서 "MiB compressed read" 열, `runs.tsv`의 `read_compressed_bytes`)를 기록한다. 웜 재실행은 query condition cache를 타므로 판단은 콜드 값으로 한다(6.3절).
11. **A-6 폴백**: `./gradlew :test:storage:queries:storageQueries -Pqueries.args="--url $URL --queries Q05 --procedures none"`. +60초 인스턴스 트랜잭션으로 `restored_skewed` 0행, `fallback_2d` 1행·시간(0.5초 기준), `fallback_coverage`(복원 실패·폴백 실패 수). 한계 확인용으로 `--skewed-offset-ms=90000000` 소규모 데이터(prefix를 따로)를 한 번 더 돌린다(12.1절).
12. **결정성**(D-14): `./gradlew :test:storage:queries:storageQueries -Pqueries.args="--url $URL --queries none --procedures Q14 --q14-source dataset --q14-days 3"`(실데이터)와 `--q14-source synthetic`. 보고서 Q14 절의 "D-14 rebuild determinism" 표(1m·1h × 함수별 상태 바이트·확정값 동일/불일치)를 본다.

## 14. PM 결정·설계 반영 요청과 미결

### 14.1 26.8에서 13 원안이 동작하지 않거나 보강이 필요해 V001·설정이 다른 점(스키마 레인, 근거 테스트 있음)

| # | 13 원안 | V001·`deploy/clickhouse` 설정 | 반영 대상 |
|---|---|---|---|
| 1 | 롤업 AggregatingMergeTree의 정렬 키 밖 일반 열(env·kind) | 26.8이 거부 → 정렬 키는 그대로 두고 `allow_dimensions_outside_sorting_key=1` | 13 §4 |
| 2 | `SimpleAggregateFunction(sumForEach)` | 미지원 → `AggregateFunction(sumForEach)`, 조회 `sumForEachMerge` | 13 §4·§6.3 (8), 14 API |
| 3 | `SimpleAggregateFunction(sum, UInt32)`(metric sample_count) | 거부 → UInt64 | 13 §4.6 |
| 4 | 1h 테이블 dedup window 없음 | 재시도 때 1h 이중 집계(400→800) → 1h 전부 window 1000 | 13 §4.2·§5 |
| 5 | writer 프로필 `insert_deduplication_token_support` | 26.8에 없는 설정(UNKNOWN_SETTING) → 제거 | 13 §7.3 |
| 6 | reader `readonly=1` + 쿼리별 SETTINGS(13 §6.3) | 그대로면 READONLY 오류 → `readonly=1` 유지 + 상한 6종에 `changeable_in_readonly`(낮추기만 허용) | 13 §7.3 |
| 7 | MV 정의(SQL SECURITY 절 없음) | INSERT만 가진 writer가 ACCESS_DENIED로 실패하고 원본만 커밋 → `SQL SECURITY DEFINER`(definer `argos_migrator`) | 13 §4 |
| 8 | `background_pool_size=4`만 | 26.8이 기동 거부 → merge_tree 임계값 3개(2/5/6) 비례 축소 | 13 §7.2, 17 |
| 9 | default 사용자 비밀번호 + 127.0.0.1(13 §7.3) | SEC-74에 따라 제거 | 13 §7.3 |
| 10 | 13 §7.2: 26.8 기본 시스템 로그 중 `query_log`만 TTL, `part_log`·`text_log`만 제거 | 번들 이미지 `config.xml`의 `*_log` 섹션 23개 중 나머지 20개의 15개에 TTL이 없어(5개는 이미지 기본 TTL 3~30일) 디스크가 상한 없이 는다(리뷰 r1 실측: `metric_log` 약 19MB/일, `query_views_log` 하루 약 52만 행 = 뷰 12개 × 2초 블록 43,200개/일). → 진단용 7개(`query_log`·`trace_log`·`metric_log`·`asynchronous_metric_log`·`error_log`·`crash_log`·`backup_log`)는 `event_date + INTERVAL 7 DAY`, 저장소 패널·운영 진단에 쓰지 않는 16개는 `remove`(선택 근거는 config.d 주석). `SystemLogsIT`가 병합된 설정의 `*_log` 섹션과 생성된 `system.*_log` 테이블 전부의 TTL을 단언하므로 다음 LTS에서 새 로그가 기본으로 켜지면 실패한다(이번에 `s3queue_log`를 이 테스트로 찾았다) | 13 §7.2·§14 |
| 11 | 13 §7.3 migrator `GRANT CREATE, DROP, ALTER, INSERT, SELECT`, 13 §7.2 `remote_url_allow_hosts` 없음 | 포괄 `CREATE`에 `CREATE DICTIONARY`가 들어 있어 HTTP·CLICKHOUSE 사전 소스로 ClickHouse 서버가 임의 호스트에 연결할 수 있었다. → 하위 권한 `CREATE DATABASE, CREATE TABLE, CREATE VIEW, DROP DATABASE, DROP TABLE, DROP VIEW, ALTER, INSERT, SELECT, BACKUP`으로 축소(사전·함수·named collection DDL 없음, ClickHouse 사전은 설계에서 쓰지 않음 DSN-13-09), 빈 `<remote_url_allow_hosts>`로 모든 원격 URL 거부(`url()`·HTTP 사전 → `UNACCEPTABLE_URL`, 운영자가 설정한 S3 백업 대상 등만 설치 시 추가). V001 적용·MV 생성·`REPLACE PARTITION`·`DELETE` 뮤테이션은 그대로 된다(SEC-74·76, 외부 호출 금지) | 13 §7.2·§7.3(14.2) |
| 12 | 13 §7.2 리스너 지정 없음 | 공식 이미지 기본값으로 MySQL 9004·PostgreSQL 9005·인증 없는 interserver 9009가 와일드카드 주소에 열려 있었다. → 세 포트 `remove`, 8123(HTTP)·9000(native, 운영 clickhouse-client)만 남김(`AccountsIT`가 컨테이너 LISTEN 포트 = {8123, 9000} 단언). A-9에서 Replicated를 채택하면 `interserver_http_port`를 `interserver_http_credentials`와 함께 다시 켠다(`ReplicatedConversionIT`는 테스트 설정에서만 켬) | 13 §7.2(16 §10.7 취지) |
| 13 | 13 §7.3 writer 프로필에 제약 없음 | writer가 쿼리 설정으로 메모리 상한 해제·`query_log` 끄기·dedup·`async_insert` 변경을 할 수 있었다. → `constraints`: `async_insert`·`insert_deduplicate`·`deduplicate_blocks_in_dependent_materialized_views`·`log_queries`·`readonly`·`allow_ddl` 고정(같은 값 재전달은 허용), `max_insert_threads` 1, `max_memory_usage`는 낮추기만(SEC-76) | 13 §7.3 |

### 14.2 PM 결정 요청

1. D-12 일 단위 정합의 자정 귀속(7.3절).
2. A-11 함수와 기준 재정의(8장). D-14 해석도 함께 정한다: 재구축 결정성 실측에서 BFloat16은 1m 상태 **바이트**가 재구축마다 달랐고(확정값은 동일), 바이트까지 같은 것은 DD뿐이었다(8.2절). "확정값 동일"이면 BFloat16, "바이트 동일"이면 DD.
3. 코덱 변경(12.5)과 bloom `idx_user` GRANULARITY 1(12.6) → 13 DDL·02 §12 갱신.
4. A-6 폴백 조회(12.1) → 14 API·13 §6.3 (5). 폴백은 에이전트 시계가 약 24시간 이내로 앞선 경우만 찾는다(+25시간 실측 3.8% 실패). 한계를 넘는 경우: (a) 2일 폴백도 0행이면 범위 없는 bloom 조회(30일 idx_tx 약 1.6GB 읽기, 6.3절), (b) "시계 오차로 찾을 수 없음" 오류(URL ts 경로 안내) 중 선택.
5. 13 §9 삭제 잡을 `DELETE IN PARTITION`으로, 비상 보존 단축 런북에 디스크 회수 지연(> 10분) 반영(10.1). 근거: `ConfirmationItemsIT`의 하드링크 관찰(대상 날 밖의 part를 다시 쓰지 않음)과 뮤테이션 범위를 파티션으로 한정하는 성질. **소요 시간 근거는 없다**: 통제 재측정 1차(run P)에서는 같은 사본 안 IN PARTITION이 29~38% 짧았지만(WHERE 9.2~10.7초, IN PARTITION 5.7~7.6초), 같은 조건의 통합 재검증 재실행에서는 WHERE가 7~31% 짧아(WHERE 5.5~5.7초, IN PARTITION 5.9~8.3초) 1/100에서는 결론이 없다(10.1절). 이전 판의 "약 2배"(1/20 101초 대 53초)는 교란된 비교라 철회했다. 시간 비교는 목표 하드웨어에서 반복 3회 이상으로 한다(13장 7).
6. 13 §15와 18 §9 생성기 분포 차이(외부 호스트 20 vs 30, BATCH 3% vs 2%, ext_stat 0.1억 vs 0.2억) 정정. 13 §14 `sql_stat_raw` 공식(약 12억/14일)과 §15 규모(1.2억)의 10배 차이 정정(생성기 실측 환산 1.48억).
7. 14 API: `result_overflow_mode=break`는 블록 단위로 넘친다(`max_result_rows=1000`에 65,400행) → 정확한 상한은 `LIMIT`. URL 파라미터로 보낸 설정이 거부되면 `system.query_log`에 남지 않는다(SQL `SETTINGS` 절은 남는다) → 감사가 필요하면 SQL 절로 보낸다.
8. C-2 토큰: WAL 위치만으로 block_id를 만들면 WAL 초기화·서버 2대가 같은 ClickHouse에 쓸 때 최근 1,000블록 안에서 새 데이터가 조용히 버려질 수 있다 → 토큰에 WAL 인스턴스 id·epoch를 넣을 것(WO-21). ClickHouse 26.8은 dedup된 재전송에도 `X-ClickHouse-Summary.written_rows`를 0이 아닌 값으로 보고하므로 라이터가 이것으로 중복을 판정하면 안 된다.
   - **block_id 위치 문구(설계는 바꾸지 않음)**: C-2·DSN-02-12의 "block_id = COMMIT 레코드 위치"는 insert 전에 토큰이 있어야 한다는 조건과 모순이다(COMMIT은 ack 뒤에 기록된다). 12 §3.8은 INTENT에 token(데이터 레코드 범위 `first..last` 위치에서 유도)을 기록하고, 13 §5.1은 block_id를 UUIDv7로 둔다. 세 문서가 서로 다르다. 생성기 리플레이(`WalSimulator`)는 **INTENT 레코드 위치(WAL id 접두)**를 block_id로 썼다. → C-2 문구를 INTENT 위치(또는 UUIDv7)와 WAL id·epoch 포함으로 정정할지 PM 결정(WO-21).
9. **N-06·Q17 통계 10,000행/s와 13 §14·§15 분포의 불일치**: 통계 행은 (인스턴스, SQL 또는 호스트, kind)의 10초 창마다 1행이므로 상한이 "인스턴스 × 창당 고유 SQL / 10초"다. 5,000 TPS·300 인스턴스·활성 SQL 200(13 §14)이면 sql_stat 상한이 300 × 200 / 10 = **6,000행/s**이고, 생성기 N-06 옵션 실측은 sql_stat 5,300 + ext_stat 126 = **약 5,400행/s**다(4.2절, 트랜잭션에서 파생하므로 D-12 정합 유지). 10,000행/s에 닿으려면 인스턴스마다 10초 창당 고유 SQL이 333개 이상 실행돼야 한다(활성 SQL 200의 1.7배 이상, 또는 인스턴스 500 이상). 생성기에는 정합을 깨는 별도 합성 통계 스트림을 넣지 않았다. → N-06·13 §15 Q17의 통계 수치를 약 6,000행/s(13 §14 공식과 일치)로 정정할지, 13 §14의 활성 SQL·인스턴스 전제를 올릴지 PM 결정.
10. **롤업 재구축 절차(D-14, 11장 D-14 행)**: (a) 13 §4.1의 재구축 문구 `INSERT INTO <rollup> <SELECT> WHERE toDate(ts) = :date`를 "staging 테이블(`CREATE TABLE <t>_rebuild AS <t>`)에 넣고 `ALTER TABLE <t> REPLACE PARTITION … FROM <t>_rebuild`"로 바꿀 것. 롤업 테이블에 직접 넣으면 1h MV가 1m에 걸린 캐스케이드라서 그 날이 1h에 두 번 더해진다(`DROP PARTITION` 뒤에도, `RollupRebuildIT`). (b) 1m 하루를 재구축하면 같은 달 1h를 1m에서 월 단위(`toYYYYMM(minute) = M`, 1h 파티션이 월 단위)로 다시 만들 것(`REPLACE PARTITION`은 MV를 거치지 않는다). (c) 재구축 단위는 파티션 전체(모든 앱)로 하고, 12 §9.1의 앱 단위 `RollupRebuilder.rebuild(app, date, table)`·`--app`은 금지할 것(앱 하나만 담은 staging으로 `REPLACE PARTITION`하면 같은 파티션의 다른 앱 행이 사라진다). 반영 대상 13 §4.1·§13, 12 §9.1. `deploy/clickhouse/README.md` "Rollup rebuild"에 절차를 적었다.
11. **migrator 권한 축소와 외부 URL 차단(14.1 #11)**: 13 §7.3의 `GRANT CREATE, DROP, …`를 하위 권한 목록으로, 13 §7.2에 빈 `remote_url_allow_hosts`(설치 시 운영자 대상만 추가)를 넣는 문구 변경 승인. 16 §10.8 표의 `argos_admin`(DDL·`ALTER … DELETE`·`DROP PARTITION`·`BACKUP`)과 이름·범위도 맞출 것.
12. **writer INSERT 권한 범위(구현하지 않음)**: 13 §7.3의 `GRANT INSERT ON {db}.*`를 원본 5개 테이블(`{p}xlog`·`{p}profile_step`·`{p}metric`·`{p}sql_stat_raw`·`{p}ext_stat_raw`)로 좁히면 writer가 롤업 테이블에 직접 쓸 수 없게 된다(MV는 `SQL SECURITY DEFINER`라 영향 없음). 13 §7.3 문구 변경이라 이번에는 `argos.*` 그대로 두었다. 좁힐지 PM 결정(접두사 `{p}`·DB 이름 변경 DSN-13-11 시 GRANT 생성 방식 포함).
13. **설계 문서 반영(14.1 #10·#12·#13)**: 시스템 로그 TTL·제거 목록, 미사용 리스너 제거, writer 프로필 제약을 13 §7.2·§7.3과 17(설치·Compose)에 반영할지 승인.
14. **13 §6.3 (13)(14) 야간 정합 SQL의 형식**: 원안은 3개 이상을 `FULL JOIN … USING`으로 잇는다. 이 형식은 `join_use_nulls`(기본 0)에 의존하고, 세 번째 이후 테이블에만 있는 키의 Enum 키(kind)를 기본값으로 잘못 표시한다. PoC의 Q12·Q13·Q16 검사는 소스마다 `src` 열을 붙인 `UNION ALL` + `GROUP BY` 키 + `sumIf(c, src = …)` + `HAVING` 형태로 바꿨고(설정 비의존, `join_use_nulls=1`을 강제해도 같은 결과, `ConsistencyIT` 음성 대조), 원안 그대로인 nightly 변형은 파일 설정에 `join_use_nulls=0`을 고정했다. → 13 §6.3 (13)(14)를 UNION ALL + GROUP BY 형태로 바꿀지 결정(서버 야간 잡 WO).

### 14.3 미결

- 목표 하드웨어 측정 전부(13장).
- **PR integration 잡 시간**: GitHub 러너(ubuntu-latest, 4 vCPU)에서의 실측이 없다. 이 머신의 저장소 IT 3종은 5분 48초, 잡과 같은 루트 `./gradlew integrationTest -Pqueries.profile=ci`(전 모듈)는 5분 47초였다(7.1절, 12스레드·공유 부하). 통합 리드는 잡 `timeout-minutes` 45를 유지한다(4 vCPU에서 2~3배 느려도 여유). 첫 GitHub 실행의 잡 시간과 `:test:storage:queries:integrationTest` 시간을 기록해 45분이 적절한지(또는 1/100 저장소 IT를 별도 잡으로 나눌지) 정한다.
- `DockerStartup`(16.2절)은 첫 Docker 접근을 저장소 `build/tmp/` 잠금 파일로 순서화한다. 저장소 IT 모듈이 셋이라 JVM당 초기화 약 2초씩 최대 몇 초 늦게 시작한다. Linux CI(Unix 소켓)에는 pipe 경쟁이 없어 순서만 정한다. Testcontainers가 named pipe 검사를 고치면 없앨 수 있다.
- Docker Desktop(WSL2) 개발 머신에서 대량 벤치를 돌리면 `docker_data.vhdx`가 지운 데이터만큼 줄지 않는다(이번 실행 30.1 → 70.7GB, 리뷰 r1 통합 재검증 시점 75.9GB·호스트 C: 여유 1.2GB로 1/20 재실행 불가, 3장). 되돌리려면 Docker Desktop을 끄고 `wsl --shutdown` 뒤 VHDX를 compact해야 한다. 같은 머신의 다른 작업(WO-02)을 멈추게 하므로 이 작업에서는 하지 않았다.
- ~~`testcontainers/ryuk:0.14.0` 이미지는 다이제스트로 고정하지 않았다(테스트 인프라).~~ WO-03F에서 고정했다(17.5절).
- Testcontainers의 Ryuk 컨테이너는 자기 포트를 호스트 전체 인터페이스(0.0.0.0)에 게시한다(Testcontainers 공통 동작). Testcontainers 2.0.5에 Ryuk 게시 주소를 바꾸는 설정이 없어 알려진 한계로 남긴다(17.5절). ClickHouse 테스트 컨테이너의 HTTP 포트는 로컬 Docker일 때 127.0.0.1에만 게시한다(`ArgosClickHouse`, `AccountsIT`).
- **백업 권한(16 §10.8)**: 26.8에서는 `BACKUP` 권한만으로 백업이 되지 않는다. `BACKUP … TO Disk`·`File`·`S3`가 각각 `WRITE ON DISK`·`WRITE ON FILE`·`WRITE ON S3`가 없어 `ACCESS_DENIED`이고(수동 실측), `backups.allowed_disk`도 없다. 백업 WO(17)에서 필요 권한(대상 디스크로 한정한 `WRITE ON DISK`)과 `allowed_disk`를 IT와 함께 확정한다. V001 계정에는 16 §10.8의 권한명 `BACKUP`만 반영했고 동작은 검증하지 않았다.
- ClickHouse는 `sha256('')`(빈 비밀번호의 해시)를 받아들인다(`password_sha256_hex`는 형식만 검사. 수동 실측: reader 해시를 `e3b0c442…b855`로 두면 빈 비밀번호로 로그인된다). → 설치 스크립트가 해시하기 전에 길이·금지 목록을 검사해야 한다(WO-40, 17 설치 6단계, 16 §10.6 `CredentialPolicyCheck`와 같은 규칙). `ConfigFailFastIT`는 변수 미설정·형식 오류만 막는다.
- 기존 설치를 이 설정으로 올리면 TTL이 없던 시스템 로그 테이블은 ClickHouse가 `<이름>_0`으로 이름을 바꾸고 새로 만든다. 옛 테이블은 TTL 없이 남으므로 업그레이드 런북(17)에서 지우는 단계가 필요하다(1.0 신규 설치는 해당 없음, 실측하지 않음).
- `metric_1m.last_v`(anyLast)는 늦은 도착이 있으면 머지 순서에 따라 "마지막 값"이 달라질 수 있다(13 설계 유지, 테스트 단정 제외).
- Compose `mem_limit`과 `max_server_memory_usage`의 관계(ClickHouse가 cgroup 상한의 0.9로 자동 축소), tar 설치의 RAM 변수 공급(systemd EnvironmentFile)은 WO-40·17 문서에서 정리.

## 15. 재현 명령과 증거

| 목적 | 명령 |
|---|---|
| 스키마·계정·확인 항목(WO-03 51건, WO-03F 53건) | `./gradlew :test:storage:schema-tests:integrationTest` |
| 생성기(단위 WO-03 40·WO-03F 42, IT 3) | `./gradlew :test:storage:generator:test :test:storage:generator:integrationTest` |
| 생성기 부하 구성(N-06 옵션, 행/s) | `./gradlew :test:storage:generator:storageGenerate -Pstorage.mode=replay -Pstorage.target=none -Pstorage.tps-steps=5000 -Pstorage.step-duration=PT5M -Pstorage.instances-per-app=30 -Pstorage.active-sqls=200 -Pstorage.profile-step-rate=50000 -Pstorage.tick-ms=100` |
| CI 1/100 정합(T-ST-030~033·053) + 카탈로그 스모크 | `./gradlew :test:storage:queries:integrationTest -Pqueries.profile=ci`(전체 카탈로그·모든 프로시저는 `-Pqueries.benchrun=full`, WO-03F부터 테스트 JVM에 전달됨) |
| CI 잡과 같은 전체 통합 테스트 | `./gradlew integrationTest -Pqueries.profile=ci` |
| 1/100 처음부터 끝까지(16장) | `bench-server.sh start` → `storageBench -Pstorage.profile=ci -Pstorage.target=url:… -Pstorage.apply-schema=true` → `storageQueries --queries all --procedures Q05/bloom,Q11/cost,Q14,Q15,Q16,Q18,Q19,Q20` → `storageGenerate -Pstorage.mode=replay -Pstorage.tps-steps=500,2000,5000 -Pstorage.step-duration=PT8M -Pstorage.tick-ms=100`과 동시에 `--procedures Q17 --q17-window live` → `--queries Q12,Q13`. 정확한 인자는 [`r1-integration/README.md`](../bench/wo03-storage-2026-10-24/r1-integration/README.md) |
| 1/20 벌크 | `./gradlew :test:storage:generator:storageBench -Pbench.machine-profile=<id> -Pstorage.profile=local -Pstorage.target=url:http://127.0.0.1:18123/ -Pstorage.apply-schema=true` |
| Q1~Q20 | `./gradlew :test:storage:queries:storageQueries -Pqueries.args="--url http://127.0.0.1:18123 …" -Pqueries.cold="command:…"` |
| 결정 확인 도구(저장소) | 코덱 제안: Q20 변형 `proposed`(`test/storage/queries/.../procedures/ProposedCodecs.java`) `--procedures Q20 --q20-repeats 3`. A-6 폴백: Q05 `restored_skewed`·`fallback_2d`·`fallback_coverage` `--queries Q05`. D-14 결정성: Q14 단계 `determinism_*` `--procedures Q14`. Q17 live 창: `--q17-window live`. 명령은 `test/storage/bench/README.md` 2b·13장 |
| 서버 | `test/storage/bench/bench-server.sh start|restart-dropcache|rm-volume` |

증거: [`docs/bench/wo03-storage-2026-10-24/`](../bench/wo03-storage-2026-10-24/README.md)(README에 측정 구간·조건·파일별 설명·원본 경로). QueryBench 보고서 `report.md`·`runs.tsv`(1/100 CI `ci-1of100/querybench/`, 1/20 run A·A2·C·Q17 `local-1of20/querybench/`), 1/100 통합 테스트 요약(`ci-1of100/it/`), 생성기 `manifest.json`·`report.md`(1/100 생성기 레인 `ci-1of100/generator/`, 1/20 벌크·리플레이 `local-1of20/generator/`), `ch-monitor.tsv`(15초 간격 parts·머지·메모리·디스크)·`hostdisk.txt`(`local-1of20/monitor/`), 머신 CPU 샘플(`cpu-load.tsv`), 열·인덱스 크기 덤프(`local-1of20/sizes/`), 코덱 실험 DDL과 결과(`local-1of20/codec/`). 생성기 사전·샘플 파일(`dictionary.tsv`·`samples.json`)은 시드로 다시 만들 수 있어 넣지 않았다. 측정 뒤 1/20 데이터(활성 7.0GB)와 볼륨은 삭제했다. 리뷰 r1 측정은 `r1-generator/`·`r1-queries/`, 통합 재검증은 `r1-integration/`(볼륨 2.868GB, 측정 뒤 삭제)에 있다.

## 16. 통합 재검증(리뷰 r1 뒤, 2026-10-24 17:33~19:22 UTC)

리뷰 r1의 수정(스키마·설정, 생성기, 러너, 증거 이관)을 한 작업 트리에 모은 뒤 통합 리드가 다시 돌린 결과다. 같은 머신에서 WO-02의 오버헤드 벤치가 계속 돌았다(머신 CPU는 [`r1-integration/machine-cpu.tsv`](../bench/wo03-storage-2026-10-24/r1-integration/machine-cpu.tsv)).

### 16.1 명령과 결과

| 명령 | 시각(UTC) | 결과 | 머신 CPU 평균 / 최대 |
|---|---|---|---|
| `./gradlew build "-Pargos.ui.skip=true" --continue` | 17:33 | BUILD SUCCESSFUL(408 태스크, 46 실행) | 46% / 68% |
| `./gradlew build --continue`(UI 포함) | 17:34 | BUILD SUCCESSFUL(413 태스크) | - |
| `./gradlew integrationTest -Pqueries.profile=ci --rerun --continue` 1·2회 | 17:34~17:40, 17:42~17:47 | **실패**: `:test:storage:schema-tests:integrationTest` 19건 전부 "Could not find a valid Docker environment"(나머지 모듈은 통과) | 61% / 100% |
| `./gradlew :test:storage:schema-tests:integrationTest --rerun`(단독) | 17:41 | 51/51 통과(1분 19초) | - |
| 진단: 같은 명령 + 스키마 테스트에 로그 바인딩(scratch init 스크립트, lenient) | 17:49~17:55 | 통과(타이밍이 바뀌면 재현되지 않음) | - |
| 진단: Testcontainers 초기화만 하는 JVM 10개 동시 시작 | 17:56~17:57 | 10개 중 2개, 1개 실패. 실패 JVM 로그: `DOCKER_HOST socket file '//./pipe/docker_engine' does not exist` | - |
| 수정(`DockerStartup`, 16.2) 뒤 같은 진단 3회 × 10 JVM | 17:58~18:01 | 30/30 성공 | - |
| `./gradlew integrationTest -Pqueries.profile=ci --rerun --continue` 4회 | 18:02~18:08 | **BUILD SUCCESSFUL, 5분 47초**: 스키마 51/51, 생성기 3/3, 쿼리 13/13, agent-testing 21/21, 샘플 앱 IT 전부 | 64% / 100% |
| 1/100 처음부터 끝까지(16.3) | 18:08~18:59 | 전 단계 완료. Q05/bloom만 첫 실행 `ERROR`(16.2) → 수정 뒤 재실행 RECORDED | 35~37% / 60~76% |
| `./gradlew :test:storage:queries:integrationTest --rerun`(기본 데이터, 전체 카탈로그) | 19:00~19:04 | 13/13 통과(3분 53초) | - |
| `./gradlew checkLicenses checkDevOnlyLicenses --rerun --continue` | 19:04 | BUILD SUCCESSFUL | - |
| `bash test/lint/check-spdx.sh` | 19:04 | `check-spdx: OK (981 files)` | - |
| 의존성 재생성: 빈 Gradle 캐시의 Linux 컨테이너(`eclipse-temurin:21-jdk`, 작업 트리 tar 사본)에서 `./gradlew --no-daemon --write-verification-metadata sha256 --write-locks --no-configuration-cache help resolveAndLockAll build checkDevOnlyLicenses --continue -Pargos.ui.skip=true`(dev-setup 7절) | 19:04~19:12 | BUILD SUCCESSFUL(487 태스크 전부 실행, 7분 33초). 생성된 `gradle/verification-metadata.xml`이 저장소 파일과 **바이트 단위로 같고**, 잠금 파일 42개도 전부 같다 → 공유 파일 변경 없음(리뷰 r1과 WO-02 모두 새 의존성 없음) | 75% / 100% |
| 같은 컨테이너에서 검증 활성(strict) 재확인: `build checkLicenses checkDevOnlyLicenses --continue -Pargos.ui.skip=true` | 19:12~19:15 | BUILD SUCCESSFUL(447 태스크) | 같음 |
| 최종: `./gradlew build "-Pargos.ui.skip=true" --continue`, `./gradlew build --continue` | 19:15~19:16 | 둘 다 BUILD SUCCESSFUL | - |
| 최종: `./gradlew integrationTest -Pqueries.profile=ci --rerun --continue` 5회 | 19:16~19:22 | **BUILD SUCCESSFUL, 5분 11초**(수정 뒤 연속 2회 녹색): 스키마 51, 생성기 3, 쿼리 13, agent-testing 21, 샘플 앱 37 | 50% / 100% |
| 최종: `bash test/lint/check-spdx.sh`, `./gradlew checkLicenses checkDevOnlyLicenses` | 19:22 | OK(981 files), BUILD SUCCESSFUL | - |

빠른 Java 빌드가 짧은 것은 각 수정 담당이 같은 입력으로 이미 돌린 태스크가 up-to-date였기 때문이다(Gradle 입력 해시 기준). 빈 캐시 컨테이너의 빌드는 487 태스크를 모두 새로 실행했다. 통합 테스트는 `--rerun`으로 모두 다시 실행했다.

### 16.2 고친 통합 결함

1. **병렬 통합 테스트의 Docker 초기화 경쟁(Windows Docker Desktop)**: Testcontainers는 Docker 호스트가 named pipe(`npipe:////./pipe/docker_engine`)이면 먼저 `Files.exists`로 pipe를 확인한다. 이 확인은 pipe를 여는 동작이라, 다른 프로세스가 pipe 인스턴스를 모두 잡고 있는 순간에는 pipe가 없다고 판단하고, 그 JVM에서는 이후 모든 시도를 거부한다("Previous attempts to find a Docker environment failed. Will not retry"). 루트 `integrationTest`는 저장소 IT 모듈 셋을 병렬 JVM으로 같은 밀리초에 시작해, 이 머신에서 스키마 테스트 JVM이 두 번 모두 이 경쟁에 걸렸다(r0 통합 때는 우연히 통과). → `test/storage/clickhouse-support`에 `DockerStartup`을 넣었다: JVM의 첫 Docker 접근(`ArgosClickHouse.publishesOnLoopback`·`removeVolume`)이 저장소 `build/tmp/argos-testcontainers-startup.lock` 파일 잠금을 잡고, named pipe 호스트면 pipe가 열릴 때까지(최대 30초) 기다린 뒤 Testcontainers 클라이언트를 초기화한다. 테스트 단언은 바꾸지 않았다. Linux CI(Unix 소켓)에는 경쟁이 없고 잠금은 시작 순서만 정한다.
2. **Q05/bloom 조회 대상 NULL**: 1/100 데이터(2026-10-23 20:24부터)에서 `--bloom-days 3`(기본값)이면 복사 범위의 첫날이 그날 저녁부터만 데이터가 있는 날이고, `bloom_targets`가 그 첫날 01~04시에서 조회 대상(tx·trace·user)을 골라 NULL → `BAD_QUERY_PARAMETER`로 프로시저가 `ERROR`였다. 리뷰 r1의 run P는 Q05/bloom을 돌리지 않았고 CI 스모크는 이 프로시저를 빼서 드러나지 않았다(1/20 run A는 하루 복사라 통과). → `Q05.sql` `bloom_targets`가 복사 범위의 **마지막 날**(`full_day`) 01~04시에서 고르게 했고, `BloomVariants`는 대상이 NULL이면 명확한 메시지로 실패한다. 전체 규모(30일)에서는 두 날 모두 온전한 날이라 결과가 같다.

### 16.3 1/100 처음부터 끝까지(벤치 서버 5GiB, `--cold none`)

| 단계 | 결과 | 이전 1/100과 비교 |
|---|---|---|
| 벌크(`storageBench -Pstorage.profile=ci`) | 33,920,500행 68.95초 = **49.2만 행/s**(66.4MiB/s), xlog insert p95 3.55초, 파티션당 parts 최대 9, MemoryTracking 피크 681MiB, DelayedInserts 0, 원본 count = 생성기 카운터, D-12 합계 일치. 머신 CPU 70% / 98% | 생성기 레인 43.8만·42.3만 행/s(4.1절) |
| 압축·B/행(data / disk) | xlog **2.56 · 82.74B / 86.06B**, profile_step 7.15 · 14.50B, metric 11.87 · 2.61B, sql_stat 5.06 · 27.29B, ext_stat 5.14 · 21.44B | 같은 값(5.1절, 결정적 생성) |
| Q1~Q11(웜 3회 중앙값) | 전 변형 충족: Q1 1h 26.6ms, Q2 7일 29.5ms, Q3 10분 66.0ms·10만 점 91.7ms, Q4 9.6~38.3ms, Q5 13.8~22.0ms(`fallback_2d` 22.0ms, `restored_skewed` 0행), Q6 11.8~12.6ms, Q7 28.6~44.4ms(`1d_live` 44.4ms), Q8 15.5~24.8ms, Q9 11.9~12.1ms, Q10 10.7~13.3ms, Q11 1일 46.2ms. 첫 실행 최대 102ms(기록용 `fallback_coverage` 199ms) | run P와 같은 수준(6.2절) |
| Q12·Q13 | Q12 4변형 0, Q13 `total_30d`·`rollups_14d`·`minute_raw_1m` 0, 일 단위 20행·야간 1행(7.3절 자정 귀속) | 같음 |
| Q14 | 8.2절 표와 같은 값(합성 표본 결정적), 재구축 결정성 표도 같음(BFloat16 1m 상태 바이트만 불일치, DD 동일) | 같음 |
| Q15·Q16 | 증가 0·고장 MV만 채워짐(재삽입 왕복 79ms), 늦은 도착 2만 행 분별 증가 일치·Q12 0 | 같음 |
| Q18 | 사본 2개(각 1,302만 행 1.12GB, 25.6·28.8초), DROP PARTITION 868ms·회수 > 600초(outdated 6개 남음), MATERIALIZE TTL 7.5초, 삭제 두 형식은 10.1절(결론 없음) | 형식 순서가 run P와 반대 |
| Q19 | `ttl_change_1` **1.04초**(1,302만 행·12 parts), DROP COLUMN 0.85초, MV 백필 0.97초, 불일치 0, 전 단계 10분 안 | run P 1.10초 |
| Q20 | proposed xlog **3.79 / 55.7B**(2회 같음), profile 11.28, metric 13.98, sql 10.96, ext 11.29. 반복 간 차이 ≤ 0.4% | run P와 같음(5.3절) |
| Q05/bloom(수정 뒤, 리플레이 뒤 데이터 1,662만 행 복사) | tx_id 1 mark, trace_id 2 marks, user_id G1 365 marks·593만 행 대 G4 408 marks·631만 행(−11% marks, −6% 행), has(sql_ids) 414 marks. 스킵 인덱스 5종 0.01/G1 54.9MB(3.30B/행) ~ 0.025/G4 43.3MB(2.60B/행) | 12.6절과 같은 경향 |
| 리플레이 500 → 2,000 → 5,000 TPS(각 8분, 100ms tick) | 11,443,645행, 적재 지연 p50 / p99 / 최대: 500 TPS 1,119 / **2,143** / 2,680ms, 2,000 TPS 1,119 / **2,143** / 2,303ms, 5,000 TPS 1,135 / **2,175** / 2,471ms. 파티션당 parts 최대 15, MemoryTracking 피크 473MiB, DelayedInserts 0, xlog insert p95 117ms. 원본 5개 테이블 count = 리플레이 전 count + 생성 행(정확히 일치) | r1 100ms tick 2.14~2.27초(4.2절) |
| Q17 live 창(10명 × Q1·Q3·Q7, 5초, 6분씩) | 2,000 TPS: 쿼리 p95 **101ms**(Q1 27.5, Q3 112.7, Q7 `1d_live` 58.1ms), 5,000 TPS: **110ms**(37.3 / 120.2 / 59.7ms). 각 7개 end_minute, 오류 0, parts 최대 15·12, DelayedInserts 0, MemoryTracking 피크 411·365MiB, RSS 1.25·1.32GiB. 머신 CPU 31~32% / 56~57% | r1 89·119ms(9장) |
| 리플레이 뒤 Q12·Q13 | Q12 0, Q13 범위·분 단위 0(일 단위는 7.3절과 같음) | 같음 |
| 디스크 | 끝에 활성 1.83GB, outdated 포함 3.33GB, 볼륨 2.868GB(삭제). `docker_data.vhdx` 75,879,153,664B 그대로, 호스트 C: 여유 1.2~2.3GB | - |

원자료: [`r1-integration/`](../bench/wo03-storage-2026-10-24/r1-integration/README.md)(`bulk/`, `querybench-p/`, `replay/`, `q17-2000/`, `q17-5000/`, `after-replay/`, `q05-bloom/`, `sizes-final.tsv`, `host-disk.tsv`).

이 실행도 축소 부하(13 §15 분포, 5,000 TPS에서 약 13.8k 행/s)이며 Q17·Q18·Q19의 목표 판정은 13장 목표 하드웨어 절차로 한다.

## 17. ADR-0007 반영(WO-03F, 2026-11-02~03)

[ADR-0007](../adr/0007-storage-alignment-decisions.md)(C-50~C-71, Accepted provisional)을 WO-03 산출물에 반영하고 WO-03 완료 보고의 열린 항목(A-1·A-2·A-3·C-1)을 닫았다. ADR-0007이 설계 문서(13 등)와 다르면 ADR이 우선한다. 설계 문서 자체는 메인테이너가 나중에 고친다(이 작업에서 고치지 않음). V001은 아직 릴리스 전이라 마이그레이션 없이 그 자리에서 고쳤다(C-54·C-67).

**수치의 기준 스키마**: 1~16장의 모든 측정(적재·압축·Q1~Q20·Q17·bloom·결정성 등)은 **반영 전 V001**(서버 기본 LZ4, LZ4 열 코덱, `trace_id`·`parent_span_id` `CODEC(NONE)`, `q_elapsed` tDigest, bloom `{bloom.*}` 0.01/4 전부, 열 이름 `dbc_time_ms`·`timeout_flag`·`version_id`)으로 잰 값이다. 이 장의 17.6·17.7만 반영 뒤 스키마로 다시 잰 값이다. 목표 하드웨어 측정(13장)은 반영 뒤 스키마로 한다.

### 17.1 C-번호별 반영

| C | 바뀐 것 | 파일 |
|---|---|---|
| C-53 | 트랜잭션 롤업(app·service·instance, 1m·1h)의 `q_elapsed`를 `AggregateFunction(quantilesDD(0.01, 0.5, 0.95, 0.99), UInt32)`로. 1m은 `quantilesDDState(…)(elapsed_ms)`, 1h는 `quantilesDDMergeState(…)(q_elapsed)`, 조회는 `quantilesDDMerge(…)`. Q01(원본 10초 버킷)·Q02·Q07(롤업·live 보정)도 DD로. SQL·외부 호출 버킷 p95(C-14)는 그대로. Q14 판정 기준을 C-53으로 바꿨다: 1m 그룹 p95 값 오차 ≤ 2%(p50·p95, `p50_err_1m_p95`·`p95_err_1m_p95`), 1h 이상 최대 값 오차 ≤ 5%(p99, `p99_err_1h_max`), 1m p99 순위 오차 ≤ 0.5pp(`p99_rank_err_minute`). 저장 상태 비교는 `Q14/dd_stored`(1h·1d뿐이라 `p99_err_1h_max`로 판정). tDigest·BFloat16·DD 비교는 유지 | `schema/V001__init.sql`, `rollups/{app,service}_stat_1m/1h.sql`·`instance_stat_1m.sql`, `queries/Q01·Q02·Q07·Q14.sql`, `PercentileAccuracy` |
| C-54 | `config.d`에 서버 기본 압축 `<compression><case><method>zstd</method><level>1</level></case></compression>`. V001 열 코덱의 `LZ4`를 모두 `ZSTD(1)`로, xlog `trace_id`·`parent_span_id`의 `CODEC(NONE)` 제거, `tx_id` `CODEC(Delta, ZSTD(1))`, `span_id` `CODEC(NONE)` 유지, `user_id`·`request_id`·`detail` `ZSTD(3)` 유지. Q11 비용 측정용 사본 DDL도 같은 코덱. Q20은 비교 변형을 유지한다: `base`(새 V001), `lz4`(반영 전 V001을 `LegacyCodecs`로 재구성, WO-03의 `base`), `lz4_nocodec`·`lz4cols_zstd`·`nocodec`(WO-03의 `nocodec`·`zstd`·`zstd_nocodec`), `proposed`(WO-03 제안 정의 = 새 V001 + `profile_step.tx_id` Delta) | `config.d/argos.xml`, V001, `Q11.sql`, `Q20.sql`, `CompressionComparison`, `LegacyCodecs`(신규), `ProposedCodecs` |
| C-55·C-56 | xlog bloom을 리터럴로: 전부 `bloom_filter(0.01) GRANULARITY 4`, `idx_user`만 `GRANULARITY 1`. `{bloom.*}`를 V001·`SchemaParameters`·`SchemaRenderer`·`SqlTemplate`·README에서 없앴다. 허용 지시자는 `{db}`·`{p}`·`{where}`·`{retention.*}`·`{rollup:<table>}`뿐이고 그 밖의 플레이스홀더(예: `{bloom.tx.fpp}`, 없는 `{retention.other}`)는 렌더 오류다. 스키마 체크섬을 구현했다: `SchemaRenderer.expand`(= `{rollup:}`만 펼치고 `{db}`·`{p}`·`{retention.*}`는 그대로, 줄바꿈 LF 정규화)의 UTF-8 SHA-256. 단위 테스트: DB 이름·접두사·보존 기간을 바꿔도 체크섬 동일, CRLF여도 동일, 롤업 파일을 바꾸면 변경, V001을 바꾸면 변경, `render` = `expand` 뒤 환경 치환. Q05/bloom 비교는 쿼리 도구 안에서 V001 리터럴을 바꾼 변형 DDL을 만든다(`BloomVariants.variantStatement`, 인덱스 5개가 정확히 바뀌지 않으면 실패) | V001, `SchemaParameters`, `SchemaRenderer`, `SchemaRendererTest`, `BloomVariants`, `SqlTemplate` |
| C-57 | Q13 `daily_30d`·`nightly`(13 §6.3 (14))에 일 단위 규칙을 그대로 넣었다: \|xlog − 통계\| ≤ 그날 양쪽 자정을 가로지른(`ts < 자정 ≤ ts + elapsed`) 트랜잭션의 `sql_count`·`ext_count` 합(xlog에서 계산, 여러 날에 걸친 트랜잭션은 걸친 모든 날에 센다). 분 단위(Q12, Q13 `minute_raw_1m`)와 기간 합계(Q13 `total_30d`)는 계속 정확히 0. 이 규칙으로 1/100 데이터를 판정하면 **시계가 60초 앞선 인스턴스(D-15 보정 대상) 때문에 상한을 넘는 날이 남는다**(17.3절) | `Q13.sql`, `ConsistencyIT` |
| C-58 | WO-03 구현을 유지했다(롤업 `allow_dimensions_outside_sorting_key`, `sumForEach`, metric `sample_count` UInt64, 1h dedup window 1000, reader 상한 6종 `changeable_in_readonly`, MV `SQL SECURITY DEFINER`, merge_tree 임계값, default 사용자 제거, 시스템 로그 TTL·제거, 리스너 8123·9000, writer 제약, migrator 하위 권한, 빈 `remote_url_allow_hosts`). 회귀 없음은 `DeployConfigTest`·`AccountsIT`·`SystemLogsIT`·`ConfirmationItemsIT`·`DeduplicationTokenIT`가 그대로 녹색인 것으로 확인 | - |
| C-59 | 재구축 절차를 `RollupRebuild`(clickhouse-support, 신규)에 구현했다: `CREATE TABLE <t>_rebuild AS <t>` → `INSERT INTO <t>_rebuild <롤업 SELECT>` → `ALTER TABLE <t> REPLACE PARTITION … FROM <t>_rebuild` → staging 삭제. 파티션 전체(앱 단위 없음), 1m 하루 뒤 같은 달 1h를 1m에서. `RollupRebuildIT`·`RollupViewsIT`(`Rollups`가 위임), Q14(`Q14/d14_rebuild`), Q19 MV 교체 2단계 백필이 모두 이 절차를 쓴다. 롤업에 직접 INSERT하는 것은 `RollupRebuildIT`의 손상 재현·음성 대조(1h 이중 집계)뿐이다 | `RollupRebuild`, `Rollups`, `RollupRebuildIT`, `PercentileAccuracy`, `MigrationRehearsal`, `Q19.sql` |
| C-60 | writer 권한을 원본 5개 테이블 INSERT로 좁혔다(`GRANT INSERT ON argos.xlog` 등 5줄). migrator에 `GRANT SELECT ON system.mutations`. `AccountsIT`가 단언한다: writer `SHOW GRANTS` = 원본 5개 × 접두사, 롤업 8종(기본·테스트 접두사)과 원본 아닌 테이블에 INSERT `ACCESS_DENIED`, 원본 INSERT는 DEFINER 뷰를 거쳐 1h까지 채워짐, migrator가 `system.mutations`를 읽고 자기 DELETE 뮤테이션 완료(`is_done = 1`)를 본다(`system.parts`·`system.query_log`는 여전히 거부). 삭제 완료 폴링 테스트(`ConfirmationItemsIT`)도 migrator로 폴링한다. 테스트는 클래스마다 접두사를 쓰므로 런처에 `writerTablePrefixes(...)`를 넣었다: 설치가 접두사를 바꿀 때 렌더할 것과 같은 5개 GRANT를, 속성이 붙은 `<query>`로 시험 전용 users.d 파일에 덧붙인다(ClickHouse는 속성이 다른 같은 이름 요소를 덧붙인다, 실측). 저장소의 `users.d/argos.xml` 자체는 기본 접두사 5개만 준다(`DeployConfigTest`) | `users.d/argos.xml`, `ArgosClickHouse`, `AccountsIT`, `DeployConfigTest`, `ConfirmationItemsIT`, 각 IT `Fixture` |
| C-61 | 생성기 토큰을 `<wal_instance_id>:<epoch>:<first>-<last>`(데이터 레코드 위치, 16진)로 바꿨다. 리플레이는 `WalSimulator`가 INTENT에 기록하는 결정적 값(WAL id, epoch, 블록의 첫·끝 레코드 위치)이고 재시작 복구는 INTENT에 기록된 토큰으로 재삽입한다. 벌크는 `bulk-<run id>:1:<첫 행>-<끝 행>`(실행 전체 행 번호). epoch를 올린 WAL은 같은 위치라도 다른 토큰이다(단위 테스트). `CiDataset`·스키마 IT·쿼리 IT의 토큰도 같은 모양으로 바꿨다. `written_rows`로 중복을 판정하는 코드는 없다(확인, 기록용 카운터만 있음) | `DedupToken`, `Block`, `BulkLoader`, `WalSimulator`, `ReplayLoader`, `DedupTokenContractTest`, `CiDataset`, `Workload`, `DeduplicationTokenIT` |
| C-62 | 생성기에 필요한 옵션(`--instances-per-app`, `--active-sqls`, `--sqls`)은 있었다. `--active-sqls`가 앱당 SQL 수(`sqls / apps`)를 넘으면 조용히 잘리던 것을 오류로 바꿨고(단위 테스트), 실행 끝에 달성한 통계 행/s와 N-06 기준(10,000) 도달 여부를 콘솔과 보고서에 출력한다(`statistics rows/simulated s: … N-06 bar 10,000: reached/NOT reached`). Q17 3b 절차를 통계 ≥ 10,000행/s 조건으로 바꿨다(17.4절). 부하 자체는 돌리지 않았다 | `GeneratorConfig`, `Generator`, `ReportWriter`, `LoadOptionsTest`, `test/storage/bench/README.md` |
| C-65 | 정합 SQL을 소스별 `UNION ALL` + 키 `GROUP BY` + `sumIf` + `HAVING`으로: Q12 `errors_30d`·`nightly`, Q13 `nightly`, Q19 `mv_compare`, `RollupViewsIT.q12Mismatches()`, `GeneratorClickHouseIT` 분 단위 검사(A-1). 두 테스트는 `join_use_nulls = 1`로도 같은 결과임을 단언하고, 생성기 IT는 한쪽에만 있는 분 1개를 잡는 음성 대조를 더했다. 이제 저장소 도구에 `FULL JOIN` 정합 SQL은 없다 | `Q12.sql`, `Q13.sql`, `Q19.sql`, `RollupViewsIT`, `GeneratorClickHouseIT` |
| C-67 | xlog `dbc_time_ms` → `dbc_wait_ms`, `timeout_flag` → `timed_out`, `version_id` → `app_version_id`, 롤업 `sum_dbc_time_ms` → `sum_dbc_wait_ms`, 롤업 차원 `version_id` → `app_version_id`(service_stat_1m/1h, error_stat_1m, 정렬 키 포함). V001 머리의 C-17 매핑 주석을 지웠다. 생성기·`CiDataset`·Q04·Q07·Q09·Q19·테스트도 새 이름(Java 필드명 포함) | V001, rollups, generator, queries, schema-tests |
| C-69·C-70 | `xlog.flags` 주석을 확정 문구로(1 virtual thread, 2 leaked, 4 summarized, 나머지 예약). `profile_step.batch_size`·`detail_enc`가 V001에 있음을 단위 테스트로 단언 | V001, `SchemaRendererTest` |
| C-71(B-6) | `config.d`에서 `background_schedule_pool_size` 8을 지우고 LTS 기본값을 쓴다(실측 512). 주석에 C-71을 적었다 | `config.d/argos.xml`, `DeployConfigTest` |

**변경하지 않은 결정(확인만)**: C-50(A-6 2일 폴백 뒤 범위 없는 조회 금지)은 서버 조회 WO 범위이고 Q05의 `bloom_only` 변형은 비용 기록용 측정으로 남겼다(API 경로 아님). C-51(1.0 MergeTree)은 V001과 같다. C-52(`sql_ids` 최대 64)는 V001 주석과 같다. C-64(`LIMIT` + SQL `SETTINGS`)는 서버 조회 WO 범위이고 저장소 도구의 상한 시험은 C-64와 모순되지 않는다(`AccountsIT`가 `break` 초과를 기록). C-66(`DELETE IN PARTITION`)은 `ConfirmationItemsIT`·Q18이 이미 그 형식을 쓴다. C-68(`dbc_wait_sum` 없음)은 V001에 없고, PM이 proto·agent·server에 없음을 확인했다. C-63(생성기 분포는 13 §15 원본)은 기존 기본값과 같다.

### 17.2 결정성(D-14)과 DD 상태

- **`RollupViewsIT`**: 1m·1h DD 상태가 원본 값으로 직접 만든 DD 상태와 키(앱·kind·분/시)마다 **바이트 동일**하다(DD 병합은 버킷 카운트 덧셈이라 순서·분할과 무관). 12개 롤업 파일의 재구축 SELECT를 staging에 넣은 결과도 MV가 채운 테이블과 `q_elapsed` 상태 바이트까지 같다(이전에는 tDigest라 비교에서 뺐다).
- **`RollupRebuildIT`**: 손상(직접 INSERT로 이중 집계) → C-59 절차로 1m 하루·1h 한 달 복구 → 상태 바이트 포함 원본 일치, 다른 날은 그대로.
- **`UpgradeIT`(26.3 → 26.8)**: 저장 DD 상태 바이트·확정값 동일, 그리고 26.8에서 5회 병합한 값이 모두 26.3에서 병합한 값과 같다(tDigest 때는 80.4 대 80.22222로 달랐다).
- **CI 1/100 데이터 `Q14/d14_rebuild`**(`BenchRunIT`, 마지막 온전한 날): C-59 절차로 두 번 재구축(1회 `max_threads=1`, 2회 `max_threads=8, max_block_size=4096`)한 뒤 트랜잭션 롤업 5개의 DD 상태(키별 `-MergeState`의 hex)와 확정값이 **재구축 1 = 재구축 2 = MV가 쓴 상태**로 모두 일치했다(`rebuild_state_mismatches` 0, `view_state_mismatches` 0).
  1/100 실행 두 번(스모크 루트 실행과 `-Pqueries.benchrun=full` 실행) 모두 같은 결과였다(마지막 실행: 2026-11-02 하루, 1h는 202611, 키 수 app_stat_1m 4,330 · service_stat_1m 46,570 · instance_stat_1m 13,899 · app_stat_1h 160 · service_stat_1h 2,051, 다섯 테이블 모두 상태 바이트·확정값 identical). 판정 `Q14/d14_rebuild` PASS(기준 `rebuild_state_mismatches == 0, view_state_mismatches == 0`).
- **Q14 합성 표본(목표 밀도 분당 3,000값 × 앱 2 × 3일, 8.1절과 같은 결정적 표본)**: `storageQueries --volume … --queries none --procedures Q14 --q14-source synthetic --q14-days 3`(Testcontainers 4GiB, 15:53:51~15:54:36 UTC, 44초)을 C-53 기준으로 판정했다. **DD PASS**: 1m 그룹 p95 값 오차 p50 0.87%·p95 1.47%(≤ 2%), 1h 이상 p99 최대 2.51%(≤ 5%), 1m p99 순위 오차 최대 0.13pp(≤ 0.5pp). 같은 기준으로 tDigest(0.70%·0.75%·4.42%·0.05pp)와 BFloat16(1.20%·1.30%·2.11%·0.13pp)도 PASS다. 8.2절 표의 값과 같다(표본·함수가 같다). 재구축 결정성 표(1m·1h × a/b)도 8.2절과 같다: DD만 1m·1h 바이트·확정값 identical, BFloat16은 1m 바이트 MISMATCH, tDigest는 바이트·값 MISMATCH.
- CI 스모크의 Q14(합성 분당 600값 × 1일)는 그룹이 작아 C-53 판정이 목표 밀도를 대표하지 않는다(DD `p50_err_1m_p95` 2.02%). 자동 테스트는 이 값을 단언하지 않고 기록만 한다(WO-03과 같음).

### 17.3 C-57 규칙과 D-15 시계 보정(새 충돌)

CI 1/100 데이터(시계 +60초 인스턴스 2개, id 19·37)에 C-57 일 단위 규칙을 그대로 적용하면 `daily_30d`가 4개 그룹(앱 4·8 × 자정 앞뒤 두 날, 예: 앱 4 HTTP 11-01 \|Δsql\| 422 > 상한 61)을, `nightly`가 1행(\|Δ\| 710 > 상한 543)을 보고한다. `ConsistencyIT`가 독립 계산(원본 통계 테이블, 인스턴스 필터)으로 정확히 확인한 내용은 다음과 같다.

- 시계가 앞서지 않은 인스턴스만 보면 상한을 넘는 날은 **0**이고, xlog ≠ 통계인 그룹은 20개다(상한이 실제로 필요함).
- 전체 데이터에서 `daily_30d`가 보고한 그룹 = 독립 계산이 상한 초과로 예측한 그룹(4개), 그 앱은 모두 시계 +60초 인스턴스의 앱이다. `nightly`는 합계 \|Δ\|가 합계 상한을 넘을 때만 1행이다.
- 원인: D-15는 `ts > now + 5s`를 `now`로 보정한다. 시계가 앞선 인스턴스의 트랜잭션 ts는 수신 시각(≈ 트랜잭션 종료)으로, 통계 행의 10초 창은 **flush 수신 시각의 창**(SQL 종료 뒤 최대 약 11초)으로 보정된다. 그래서 자정 직전 약 11초 안에 끝난 SQL은 트랜잭션이 자정을 넘지 않아도 통계만 다음 날로 간다. C-57 상한(자정을 가로지른 트랜잭션)은 이 경우를 세지 않는다. 재구축으로도 고쳐지지 않는다(원본 xlog와 원본 통계가 다르다).
- 판정: 이 PoC는 C-57을 바꾸지 않았다. Q13 `daily_30d`·`nightly`는 생성기 데이터에서 C-57 규칙대로 FAIL을 보고한다(CI 자동 기준인 Q13 범위 합계·분 단위는 0). **PM 결정 필요**: (a) 상한에 "보정된(시계 앞선) 인스턴스의 자정 직전 flush 창 통계"를 더하거나 보정 행을 표시하는 열을 둘지, (b) 통계 창 보정을 xlog와 같은 규칙(트랜잭션 수신 시각 기준)으로 맞출지, (c) 보정 인스턴스를 일 단위 검사에서 빼고 D-15 경고로만 다룰지. 관련: 01 D-15, 13 §6.3 (14), ADR-0007 C-57, 12(서버 수신 가드).

### 17.4 C-62 Q17 3b 부하

ADR-0007 C-62는 N-06의 통계 10,000행/s를 그대로 두고 부하를 올리라고 정했다. 생성기만으로(`--target=none`, 벌크, 5,000 TPS, 시뮬레이션 10분, `-Pstorage.profile-step-rate=50000`, 2026-11-02 15:50~15:52 UTC) 인스턴스 수 × 활성 SQL을 바꿔 통계 행/s(sql_stat_raw + ext_stat_raw)를 쟀다. ClickHouse에 적재하지 않았다(부하 실측은 목표 하드웨어 3b).

| 인스턴스 × 활성 SQL(`--instances-per-app` × `--active-sqls`, `--sqls`) | 통계 행/s | N-06 10,000 |
|---|---:|---|
| 300 × 200(WO-03 3b, 13 §14 전제) | 5,398 | 미달 |
| 300 × 350 | 7,606 | 미달 |
| 500 × 200 | 7,381 | 미달 |
| 500 × 400(`--sqls=4000`) | 10,086 | 도달(여유 1%) |
| **500 × 600(`--sqls=6000`, 3b 권장)** | **11,458**(sql 11,256 + ext 203) | 도달 |
| 500 × 1,000(`--sqls=10000`) | 12,846 | 도달 |

- ADR 예시("인스턴스 500 또는 활성 SQL 350 이상")는 하나씩만으로는 닿지 않는다(7,381·7,606). 통계 행은 인스턴스마다 10초 창에 실행된 서로 다른 SQL 수라, 인스턴스와 활성 SQL을 함께 올려야 한다. 500 × 600에서 전체 부하는 xlog 5,005 · profile_step 약 50,000 · 통계 약 11,450 · metric 10,000(인스턴스 500 × 시리즈 100 / 5초) = 약 76k 행/s이고, D-12 합계(Σ xlog.sql_count = Σ 통계)는 그대로 같다.
- 생성기는 실행 끝에 `statistics rows/simulated s: <n> (sql_stat_raw <a> + ext_stat_raw <b>); N-06 bar 10,000: reached|NOT reached`를 출력하고 보고서 Simulation 줄과 `manifest.json`(`simulation.n06StatisticsReached`)에 같은 값을 남긴다. `--active-sqls`가 `sqls / apps`를 넘으면 이제 오류다(이전에는 조용히 잘렸다). 명령줄 인자는 한꺼번에 검증해 순서와 무관하다.
- `test/storage/bench/README.md` 3b와 13장 6의 3b 명령을 500 × 600으로 바꿨다. 목표 하드웨어에서 생성기가 NOT reached를 출력하면 N-06 판정을 하지 않고 Gate 0 미결로 남긴다(C-62).
- 원자료: `test/storage/generator/build/reports/storage-generator/20261102-155035-bulk-c468def0140a/`(500 × 1,000) 등 6개 실행의 `report.md`(빌드 산출물, 저장소에 넣지 않음).

### 17.5 Testcontainers Ryuk 고정(C-1)

- `test/storage/clickhouse-support/src/main/resources/testcontainers.properties`에 `ryuk.container.image=testcontainers/ryuk:0.14.0@sha256:7c1a8a9a47c780ed0f983770a662f80deb115d95cce3e2daa3d12115b8cd28f0`(다중 아키텍처 매니페스트 리스트 다이제스트, `docker buildx imagetools inspect`로 확인)를 넣었다. 저장소에서 Testcontainers를 쓰는 것은 `test/storage`뿐이고(Java `org.testcontainers` import는 clickhouse-support·schema-tests·queries에만 있음, 샘플 앱·에이전트 IT는 쓰지 않음) 모두 이 라이브러리를 클래스패스에 둔다(IT와 `storageGenerate`·`storageBench`·`storageQueries` 작업). Testcontainers는 내장 이름 `testcontainers/ryuk:0.14.0`을 이 값으로 치환한다(`ClickHouseSupportTest`가 `ImageNameSubstitutor`로 단언).
- IT 실행 중 증거(2026-11-02 15:2x UTC, `docker inspect`): Ryuk 컨테이너 2개 모두 `Config.Image=testcontainers/ryuk:0.14.0@sha256:7c1a8a9a…28f0`, `Image=sha256:7c1a8a9a…28f0`. 같은 시각 ClickHouse 컨테이너는 `clickhouse/clickhouse-server:26.8.34.13@sha256:8b28eb09…7779`, HTTP는 `127.0.0.1:<임의>`.
- **알려진 한계**: Ryuk은 제어 포트 8080을 모든 인터페이스에 게시한다(`HostConfig.PortBindings` `HostIp ""`, `docker ps` `0.0.0.0:<포트>`). Testcontainers 2.0.5에는 Ryuk 게시 주소를 정하는 설정이 없고(`ryuk.container.privileged`·`timeout` 등만 있음), Ryuk을 끄면(`TESTCONTAINERS_RYUK_DISABLED`) JVM이 비정상 종료할 때 컨테이너가 남는다. 같은 네트워크의 다른 호스트가 이 포트로 라벨 필터를 등록하면 개발자 머신의 테스트 컨테이너를 지울 수 있지만 다른 정보는 얻지 못한다. 개발 머신 방화벽에 맡기고 남긴다.
- 추가 이미지 확인: 이 실행에서 Testcontainers가 받은 이미지는 ClickHouse 2종(고정)과 Ryuk뿐이었다(`alpine` 등 보조 이미지를 쓰지 않음, 실행 뒤 `docker images`).

### 17.6 실행과 결과(WO-03F)

모두 이 머신(Ryzen 5 5600H, 15.3GiB, Docker Desktop, 다른 작업 없음)에서 2026-11-02(UTC) 실행했다.

| 명령 | 결과 |
|---|---|
| `./gradlew spotlessApply :test:storage:{clickhouse-support,generator,queries,schema-tests}:test -Pargos.ui.skip=true` | 녹색. 단위 110건(clickhouse-support 32, generator 42, queries 27, schema-tests 9). WO-03은 97건 |
| `./gradlew :test:storage:schema-tests:integrationTest` | 첫 실행 6건 실패(테스트 수정 필요: 보존 기간 밖 기본 ts로 넣은 행이 TTL로 바로 지워짐, `Rollups`의 별칭 그림자, 빈 코덱 문자열 비교, MV 열 포함) → 고친 뒤 **53/53**(약 1분) |
| `./gradlew :test:storage:generator:integrationTest :test:storage:queries:integrationTest -Pqueries.profile=ci` | 생성기 3/3, 쿼리 12/13: `ConsistencyIT` Q13 일 단위가 C-57 상한을 넘는 4개 그룹을 보고(17.3) → 독립 계산으로 정확히 단언하도록 고친 뒤 `ConsistencyIT` 6/6 |
| **`./gradlew integrationTest -Pqueries.profile=ci --rerun --continue`**(CI 잡과 같은 루트 명령, 15:30:10~15:35:36) | **BUILD SUCCESSFUL in 5m 24s**. 저장소 69(스키마 53, 생성기 3, 쿼리 13: `ConsistencyIT` 6, `ReaderLimitsIT` 3, `BenchRunIT` 3, `ColdRestartIT` 1), agent-testing 21, 샘플 앱 37(common 6, ext-stub 11, load 12, shop-batch 1, shop-boot2 2, shop-heavy 5). 실패·skip 0 |
| **`./gradlew integrationTest -Pqueries.profile=ci -Pqueries.benchrun=full --continue`**(A-2, 15:36:00~15:48:49) | **BUILD SUCCESSFUL in 12m 48s**. 다른 모듈은 입력이 같아 up-to-date이고 `:test:storage:queries:integrationTest`만 다시 돌았다(시스템 속성 `argos.queries.benchrun=full`이 테스트 입력). `BenchRunIT` 3/3·672.7초: 전체 카탈로그 623.4초(Q01~Q13 웜 3회, 프로시저 Q05/bloom·Q11/cost·Q14~Q20 전부, Q05/bloom 4변형·Q18·Q20 포함), 적재 데이터 Q14 45.6초. `ConsistencyIT` 6/6(33.4초), `ReaderLimitsIT` 3/3(29.6초), `ColdRestartIT` 1/1(26.5초). WO-03 판의 "`-Pqueries.benchrun=full`이면 전체 카탈로그"는 이제 실제 동작이다 |
| A-3 | `reader-limits.md`: "DDL and write statements refused as argos_reader: 44". 7.1절 표를 35 → 44로 고쳤다 |
| `bash test/lint/check-spdx.sh` | `check-spdx: OK (984 files, git ls-files, 2 excluded)`(새 파일 3개: `RollupRebuild.java`, `LegacyCodecs.java`, `testcontainers.properties`) |
| `./gradlew checkLicenses checkDevOnlyLicenses` | BUILD SUCCESSFUL(4초, 새 의존성 없음) |
| Ryuk 확인(IT 실행 중 `docker inspect`) | 17.5절 |

- 적재 데이터의 압축(1/100, 새 V001, `BenchRunIT` 전체 실행의 Q20 "Loaded data"): xlog **3.80 · 55.70B/행**(스킵 인덱스 41.2MB = 3.18B/행), profile_step 11.39 · 9.10B, metric 13.80 · 2.25B, sql_stat_raw 10.81 · 12.77B, ext_stat_raw 10.96 · 10.05B, app_stat_1m 7.66 · 175.5B(DD 상태), instance_stat_1m 7.54 · 178.4B, service_stat_1m 7.16 · 95.3B. WO-03의 xlog 2.56·82.7B(5.1절) 대비 T-ST-041 기준 ≥ 3을 넘는다(참고값, 목표 하드웨어 판정 전).
- 기간 정합(Q12 4변형, Q13 `total_30d`·`rollups_14d`·`minute_raw_1m`), Q15, Q16은 모든 실행에서 0이었다. Q13 `daily_30d`·`nightly`는 17.3.

### 17.7 Q20 코덱 비교(1/100, 반영 뒤 스키마, 반복 1회, 참고값)

`BenchRunIT` 전체 실행(위 표, CI 1/100 데이터를 Testcontainers ClickHouse 3GiB에 적재, `CompressionOptions(반복 1, 머지 대기 상한 20초)`, 모든 변형 "settled after 5 s")의 Q20이다. 각 테이블은 자기 전체 범위(1/100은 24시간 미만)를 복사했다. 압축률 / 데이터 B/행:

| 테이블 | **base(새 V001)** | **lz4(옛 V001)** | lz4_nocodec | lz4cols_zstd | nocodec | proposed |
|---|---|---|---|---|---|---|
| xlog(12,963,791행) | **3.79 / 55.68** | **2.55 / 82.68** | 2.20 / 95.82 | 3.17 / 66.50 | 3.67 / 57.44 | 3.79 / 55.68 |
| profile_step(5,149,786) | **11.39 / 9.11** | 7.15 / 14.49 | 5.22 / 19.85 | 10.32 / 10.05 | 10.79 / 9.61 | 11.29 / 9.19 |
| metric(12,096,420) | **13.98 / 2.22** | 11.95 / 2.59 | 7.39 / 4.20 | 12.14 / 2.55 | 11.52 / 2.69 | 13.98 / 2.22 |
| sql_stat_raw(3,178,133) | **10.88 / 12.69** | 5.07 / 27.25 | 4.91 / 28.12 | 10.83 / 12.75 | 10.65 / 12.96 | 10.87 / 12.70 |
| ext_stat_raw(544,108) | **11.03 / 9.99** | 5.15 / 21.42 | 5.02 / 21.96 | 11.01 / 10.01 | 10.76 / 10.25 | 11.03 / 9.99 |
| xlog insert CPU(ms) | 32,910 | 29,250 | 25,755 | 31,616 | 30,722 | 32,912 |

- 옛 정의 재구성이 맞다: `lz4`(xlog 2.55 / 82.68B)는 WO-03 run P의 `base`(2.54 / 82.7B), `lz4cols_zstd`·`nocodec`·`proposed`는 WO-03의 `zstd`(3.17 / 66.5)·`zstd_nocodec`(3.67 / 57.4)·`proposed`(3.79 / 55.7)와 같다(5.3절).
- 새 V001(`base`)은 WO-03 제안값을 그대로 낸다: xlog 3.79(옛 V001 대비 B/행 −33%), profile 11.39, metric 13.98, 통계 10.9~11.0.
- `proposed`와 `base`의 차이는 `profile_step.tx_id`의 `Delta`뿐이고, profile_step은 오히려 `base`가 낫다(11.39 대 11.29). 그래서 C-54 문구대로 `tx_id` Delta를 xlog에만 둔 V001을 유지한다(17.8).
- insert CPU는 xlog `base`가 `lz4`보다 12.5% 많다(공유 부하 없는 1회, 참고값). 목표 하드웨어에서 3회 반복으로 다시 잰다(13장 9).
- 원자료: `test/storage/queries/build/reports/storage-queries/ci/report.md`(Q20 절)·`runs.tsv`(빌드 산출물).

### 17.8 남은 것

- 17.3 C-57 대 D-15(PM 결정).
- C-54의 `tx_id` Delta는 xlog만 적었다. WO-03 제안 정의(`proposed`)는 `profile_step.tx_id`에도 Delta를 줬다. V001은 ADR 문구대로 xlog만 바꿨고 차이는 Q20 `proposed` 대 `base`로 잰다(17.7). 필요하면 PM이 정한다.
- 목표 하드웨어 측정(13장)은 반영 뒤 스키마로 해야 한다. 13장의 판정 기준 중 Q14는 C-53 기준(17.1), Q17 3b는 17.4 부하, Q20은 `base`(새 V001)와 `lz4`(옛 V001)를 비교한다.
- 설계 문서(02 §12·§13, 10·11·12·13·14·16·17·18) 갱신은 메인테이너 확인 뒤(ADR-0007 결과 절).

### 17.9 통합 검증(WO-03F 통합 리드, 2026-11-02 16:01~16:15 UTC)

저장소 프로젝트 4개를 `clean`하고 남은 컨테이너·볼륨이 없음을 확인한 뒤, 같은 머신에서 다른 작업 없이 실행했다.

| 명령 | 결과 |
|---|---|
| `./gradlew build`(UI 포함) | BUILD SUCCESSFUL 8초(대부분 up-to-date·캐시). 증거로 쓰려고 `--rerun-tasks`로 다시 돌렸다 |
| `./gradlew build --rerun-tasks`(UI 포함, 16:02:00~16:05:35) | **BUILD SUCCESSFUL in 3m 34s, 415개 작업 전부 실행**. Java 단위 557건(저장소 110: clickhouse-support 32, generator 42, queries 27, schema-tests 9), UI Vitest 456건(31파일), 실패 0 |
| `./gradlew integrationTest -Pqueries.profile=ci --rerun`(CI integration 잡과 같은 명령, 16:06:55~16:12:13) | **BUILD SUCCESSFUL in 5m 18s**. 저장소 69(스키마 53, 생성기 3, 쿼리 13), agent-testing 21, 샘플 앱 37(common 6, ext-stub 11, load 12, shop-batch 1, shop-boot2 2, shop-heavy 5). 실패·skip 0. 클래스별: `BenchRunIT` 215초(적재 포함), `GeneratorClickHouseIT` 101초, `ReaderLimitsIT` 35초, `DeduplicationTokenIT` 35초, `ConsistencyIT` 34초. Q13 일 단위는 17.3과 같다(상한 초과 그룹 = 앱 4·8 × 11-01·11-02의 4개, 시계 정상 인스턴스만 보면 0, 차이 있는 그룹 20, nightly 1행 \|Δ\| 710 > 543). reader 거부 44건 |
| Ryuk 고정 확인 | 실행 전에 로컬 `testcontainers/ryuk:0.14.0` 이미지를 지웠다. 실행 중 `docker inspect`로 컨테이너 15개를 기록했다: Ryuk 3개 모두 `Config.Image=testcontainers/ryuk:0.14.0@sha256:7c1a8a9a…28f0`, ClickHouse 26.8 11개·26.3 1개(`UpgradeIT`)는 `image.properties`의 다이제스트, 메모리 상한 2GiB(11개)·3GiB(1개), HTTP 게시는 `127.0.0.1`. 실행 뒤 `docker images --digests`의 Ryuk 이미지는 태그 없이 다이제스트 `sha256:7c1a8a9a…28f0`로만 있다(다이제스트로 받음). Ryuk 8080 게시가 모든 인터페이스(`HostIp ""`)인 것은 17.5의 알려진 한계 그대로다 |
| `bash test/lint/check-spdx.sh` | `check-spdx: OK (984 files, git ls-files, 2 excluded)` |
| `./gradlew checkLicenses checkDevOnlyLicenses --rerun-tasks -Pargos.ui.skip=true` | BUILD SUCCESSFUL in 19s(126개 작업 실행). 저장소 프로젝트는 "0 shipped third-party module(s)". 잠금 파일·`gradle/`(검증 메타데이터 포함)은 WO-03F 전 스냅숏과 같다(의존성 변경 없음, 재생성 안 함) |
| `bash .github/scripts/run-gitleaks.sh dir` | `no leaks found`(71.37MB, 2분 27초) |
| `bash .github/scripts/check-workflows.sh` | `check-workflows: 4 file(s) clean` |

- 통합 중 고친 것: `SqlTemplate` Javadoc에 남은 `{bloom.<index>.<param>}` 언급을 지웠다(C-56, 주석만). `:test:storage:queries:check`(Spotless·Error Prone·단위 테스트) 녹색, 바이트코드가 같아 단위 테스트는 up-to-date였다.
- 실행 뒤 컨테이너 0개, 볼륨 0개다. 이미지는 ClickHouse 2종·Ryuk·gitleaks(실행 전과 같은 구성)만 남겼다.

### 17.10 스키마 IT의 고정 날짜 제거(PMO pmo-1, 2026-11-02 UTC)

**문제**: `test/storage/schema-tests` 통합 테스트는 V001을 운영 보존 기간(`SchemaParameters.defaults()`: xlog 30일, 원본 통계·metric 14일, profile 7일)으로 렌더하면서 행은 2026-10-22~25의 고정 시각으로 넣었다. ClickHouse 26.8은 TTL이 지난 행을 insert 시점에 버리고(`ttl_only_drop_parts = 1`) MV에는 그대로 넘기므로(PMO 실측: 20일 전 100행 → part 0행, MV 대상 100행), 날짜가 보존 기간 밖으로 나가면 원본 대 롤업 비교와 원본 행 수 단언이 스스로 깨진다. 첫 실패 예정은 2026-11-07 00:00 UTC(`ConfirmationItemsIT.gorillaFloat64`, metric 14일)였고 11-23 무렵에는 xlog 기반 단언 대부분이 깨질 상태였다.

**수정**(운영 보존 기간·접두사·단언 값은 그대로, 바뀐 것은 날짜 리터럴뿐):

- 기준일 하나: `Workload.anchorDay()` = 오늘(UTC) − 2일, JVM마다 한 번 계산한다(실행이 자정을 넘어도 모든 클래스가 같은 날). 행 시각·파티션·날짜 문자열은 모두 `Workload.at(LocalTime)`·`at(일, LocalTime)`·`day(일)`·`seconds`·`millis`로 기준일에서 만든다. 기존 모양은 그대로다: 23:40부터 30분(자정 교차, 두 일 파티션·두 시간), 09:58부터 4분, 23:58부터 4분(자정 블록), 늦은 분 23:41, 다음 날 00:05부터 5분(`UpgradeIT`), 기준일 −2일 10:00부터 10초 간격 20,000행(삭제 폴링, 3일). `Rollups.DAY`는 기준일, `Rollups.MONTH`는 그 달(`RollupRebuild.monthPartition`)이다.
- 바꾼 곳: `AccountsIT`(@BeforeAll 적재, writer 원본 INSERT, 거부되는 `DROP PARTITION` 2곳, TTL 없는 migrator 시험 테이블), `ConfirmationItemsIT`(bloom 입력, gorilla metric 3행, 삭제 폴링의 시작 시각·파티션 `IN` 목록·`DELETE IN PARTITION`·첫날 비교, 그리고 TTL 없는 시험 테이블인 quantileStates 분 기준·`drop_limit` 파티션), `DeduplicationTokenIT` 4곳 + `tokenIsTheIdentity` 행, `ReplicatedConversionIT` 2곳, `RollupRebuildIT`(START·OTHER_DAY·23시/00시 리터럴), `RollupViewsIT`(START·늦은 분), `Rollups`, `SchemaApplyIT`, `UpgradeIT` 2곳. TTL 없는 시험 테이블과 거부 문장은 고정 날짜여도 깨지지 않지만 아래 소스 검사를 예외 없이 두려고 같이 바꿨다.
- 재발 방지 가드: (1) `Workload.generate`와 `Workload.requireRetained`(원본 테이블 직접 INSERT 4곳 앞: bloom xlog, gorilla metric, 삭제 폴링 xlog, `tokenIsTheIdentity` xlog)가 행 시각이 미래이거나 그 테이블의 보존 기간(defaults) − 1일보다 오래되면 `IllegalArgumentException`을 던진다(테이블·범위·보존 키가 메시지에 나온다). (2) 단위 테스트 `FixedDatesTest`(Docker 없음)가 `src/integrationTest`의 `yyyy-MM-dd` 리터럴을 찾아 실패시킨다. 줄에 `fixed-date-ok: <이유>`가 있으면 허용하고, 지금은 `Workload`의 tx_id epoch 주석 1줄뿐이다(그 수도 단언). `:test:storage:schema-tests:test`가 통합 테스트 소스를 입력으로 갖도록 했다. (3) `-Pschema.anchorOffsetDays=<n>`(시스템 속성 `argos.test.anchorOffsetDays`)로 기준일을 옮긴다. 유효 범위 2~12: 2 미만이면 다음 날 00:10이 미래가 될 수 있고, 12를 넘으면 기준일 00:00의 원본 행이 14일 − 여유 1일 밖이 된다.
- `test/storage`의 다른 IT: 남은 날짜 리터럴은 `GeneratorClickHouseIT` 150행(음성 대조의 SELECT 상수)과 `ReaderLimitsIT` 334~338행(실행 전에 거부되는 문장의 더미 파라미터)뿐이고 테이블에 넣지 않는다. 생성기·쿼리 IT 데이터는 now 기준이다. Q14 합성 표본(`PercentileAccuracy`, 2026-01-05)은 TTL 없는 `q14_sample` 테이블이다.

| 명령 | 결과 |
|---|---|
| `./gradlew spotlessApply :test:storage:schema-tests:test :test:storage:schema-tests:compileIntegrationTestJava -Pargos.ui.skip=true` | 녹색. schema-tests 단위 10건(`DeployConfigTest` 9, `FixedDatesTest` 1) |
| `FixedDatesTest` 음성 확인 | `Rollups.java`에 `Instant.parse("2026-10-24T23:40:00Z")` 주석 줄을 덧붙이면 실패(`Rollups.java:172` 보고), 되돌린 뒤 녹색 |
| `./gradlew :test:storage:schema-tests:integrationTest -Pargos.ui.skip=true --rerun`(16:41:54~16:42:55) | **53/53**, 1분. 기준일 2026-10-31(오늘 11-02 UTC, `build/reports/clickhouse-schema/workload.md`). 기준일이 달의 마지막 날이라 자정 교차 창이 월 1h 파티션 경계(202610 → 202611)도 넘는 경우가 이번에 실행됐다. 1h 재구축은 기준일의 달이고 단언은 그대로 녹색 |
| 같은 명령 `-Pschema.anchorOffsetDays=6`(16:43:12~) | **53/53**, 기준일 10-27 |
| 같은 명령 `-Pschema.anchorOffsetDays=12`(16:44:13~) | **53/53**, 기준일 10-21(원본 행은 10-21 00:00부터 = 14일 보존의 경계 하루 안, 삭제 폴링 xlog는 10-19~21) |
| 같은 명령 `-Pschema.anchorOffsetDays=13`(가드 확인, 16:45:22~) | 기대대로 실패: 40건 실행 중 7건, 모두 가드의 `IllegalArgumentException`(예: `metric rows 2026-10-20T00:00:00Z .. 2026-10-20T00:00:10Z are outside [2026-10-20T16:45:34Z, 2026-11-02T16:45:34Z] (raw-days = 14, one day of margin)`). 단언 실패는 0. 23:40 창(`RollupViewsIT`·`RollupRebuildIT`·`UpgradeIT`)은 16:45 UTC에는 아직 경계 안이라 통과했다. 그래서 하루 중 시각과 무관한 최대값이 12다 |
| **`./gradlew integrationTest -Pqueries.profile=ci --rerun`**(CI integration 잡과 같은 명령, 16:46:05~16:51:16) | **BUILD SUCCESSFUL in 5m 10s**. 저장소 69(스키마 53, 생성기 3, 쿼리 13), agent-testing 21, 샘플 앱 37(common 6, ext-stub 11, load 12, shop-batch 1, shop-boot2 2, shop-heavy 5). 실패·skip 0. 스키마 기준일 2026-10-31 |
| `bash test/lint/check-spdx.sh` | `check-spdx: OK (985 files, git ls-files, 2 excluded)`(새 파일 `FixedDatesTest.java`) |

- `deploy/clickhouse`는 바꾸지 않았다. 의존성 변경 없음.

### 17.11 통합 재검증(PMO 수정 뒤, WO-03F 통합 리드, 2026-11-02 16:54~17:09 UTC)

17.10 수정 뒤 저장소 프로젝트 4개를 `clean`하고(16:54) 남은 컨테이너·볼륨이 없음을 확인한 다음, 같은 머신에서 다른 작업 없이 실행했다. 스키마 IT 기준일은 2026-10-31(오늘 11-02 UTC − 2일, `build/reports/clickhouse-schema/workload.md`)이다.

| 명령 | 결과 |
|---|---|
| `./gradlew build --rerun-tasks`(UI 포함, 16:54:32~16:58:27) | **BUILD SUCCESSFUL in 3m 53s, 415개 작업 전부 실행**. Java 단위 558건(저장소 111: clickhouse-support 32, generator 42, queries 27, schema-tests 10. 17.9 대비 `FixedDatesTest` 1건 추가), UI Vitest 456건(31파일), 실패 0 |
| `./gradlew integrationTest -Pqueries.profile=ci --rerun`(CI integration 잡과 같은 명령, 16:59:05~17:04:32) | **BUILD SUCCESSFUL in 5m 26s**. 저장소 69(스키마 53, 생성기 3, 쿼리 13), agent-testing 21, 샘플 앱 37(common 6, ext-stub 11, load 12, shop-batch 1, shop-boot2 2, shop-heavy 5), shop-boot3 스모크 3변형. 실패·skip 0. 클래스별: `BenchRunIT` 223초(적재 포함), `GeneratorClickHouseIT` 97초, `DeduplicationTokenIT` 36초, `ReaderLimitsIT` 35초, `ConsistencyIT` 34초 |
| Ryuk 고정 확인 | 실행 전에 로컬 Ryuk 이미지(`sha256:7c1a8a9a…28f0`)를 지웠다. 실행 중 `docker inspect`로 컨테이너 15개를 기록했다: Ryuk 3개 모두 `Config.Image=testcontainers/ryuk:0.14.0@sha256:7c1a8a9a…28f0`, ClickHouse 26.8 11개·26.3 1개(`UpgradeIT`)는 `image.properties`의 다이제스트, 메모리 상한 2GiB(11개)·3GiB(1개), HTTP 게시는 `127.0.0.1`. 실행 뒤 `docker images --digests`의 Ryuk 이미지는 태그 없이 다이제스트로만 있다(다이제스트로 다시 받음). Ryuk 8080 게시가 모든 인터페이스(`HostIp ""`)인 것은 17.5의 알려진 한계 그대로다 |
| `bash test/lint/check-spdx.sh` | `check-spdx: OK (985 files, git ls-files, 2 excluded)` |
| `./gradlew checkLicenses checkDevOnlyLicenses --rerun-tasks -Pargos.ui.skip=true`(17:05:12~17:05:35) | BUILD SUCCESSFUL in 22s(126개 작업 실행). 잠금 파일 42개와 `gradle/`(검증 메타데이터·버전 카탈로그·래퍼 포함)은 WO-03F 전 스냅숏과 같다(의존성 변경 없음, 재생성 안 함). 바뀐 빌드 스크립트는 `test/storage/queries`·`schema-tests`의 시스템 속성·입력 선언뿐이다 |
| `bash .github/scripts/run-gitleaks.sh dir`(17:06:02~17:08:32) | `no leaks found`(80.28MB, 2분 30초) |
| `bash .github/scripts/check-workflows.sh` | `check-workflows: 4 file(s) clean` |

- 통합 중 고친 것: 없음.
- 빌드 명령은 바뀌지 않았다. `-Pschema.anchorOffsetDays`는 기준일을 옮기는 진단용 선택 속성이라 `test/storage/schema-tests/README.md`와 17.10에만 둔다.
- 실행 뒤 컨테이너 0개, 볼륨 0개다. 이미지는 ClickHouse 2종·Ryuk·gitleaks(실행 전과 같은 구성)만 남겼다.
