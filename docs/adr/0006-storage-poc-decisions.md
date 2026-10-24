# ADR-0006: 저장소 PoC(P-4) 결정 제안: A-6·A-9·A-10·A-11, 코덱, bloom 파라미터

| 항목 | 내용 |
|---|---|
| 상태 | Accepted with amendment (provisional, 2026-10-25 PM): 결정 1~3·5·6 채택, 결정 4는 ADR-0007 C-53(`quantilesDD`)으로 변경. 수치는 목표 하드웨어 T-ST-001~020 확인 대기 |
| 날짜 | 2026-10-24 |
| 결정자 | 제안: WO-03 통합 리드. 결정: PM(메인테이너) |
| 관련 | 02 §11 P-4·§12 A-6·A-9·A-10·A-11, 13 §3·§4·§5·§6.3·§7·§9·§14·§15·§17, 14 API 6.3 조회, 18 §9, C-2·C-14·C-17, D-12·D-14·D-15, N-07·N-18·N-19·N-24, SEC-74·SEC-76, `docs/poc/clickhouse-benchmark.md` |

## English summary

The storage proof of concept (WO-03) ran the 13 §15 workload at 1/100 scale in CI and at 1/20 scale on a shared
laptop (ClickHouse 26.8.34.13 LTS, 5 GiB container); every number is a reference value until the manual Gate 0
run on the target host. Queries Q1-Q11 met their criteria with a wide margin, the consistency checks Q12, Q13
(range totals), Q15 and Q16 found zero mismatches, but xlog compressed only 2.56x (82.7 B/row of compressed
column data, 86.0 B/row on disk including skip indexes and marks) against the target of 3x. Q17 met its
criteria at a reduced load (about 21 % of the N-06 row rate); the N-06 load itself is left to the target host. We propose: keep A-6 with a two-day bloom-filter fallback for clock-skewed transactions, keep plain
MergeTree for 1.0 (A-9), adopt `sql_ids` (A-10), use `quantilesBFloat16` for transaction rollups with a redefined
accuracy criterion (A-11), switch the default compression to ZSTD(1) with adjusted column codecs (xlog 3.76x,
55.7 B/row), and keep bloom filters at fpp 0.01 / GRANULARITY 4 except GRANULARITY 1 for the user id index.

## 맥락

- 02 §12는 A-9·A-10·A-11과 코덱·bloom 파라미터를 P-4 측정 뒤 정하고, A-6(DSN-02-09)은 P-4에서 확정 검증하라고 했다. 03 Gate 0 저장소 행은 목표 규모(13억 행)·목표 하드웨어(8코어/16GB/NVMe) 측정을 요구한다.
- 이 PoC 환경에는 목표 하드웨어가 없다. CI 1/100(자동 기준)과 로컬 1/20(Ryzen 5 5600H, Docker Desktop, 5GiB 컨테이너, 같은 머신에서 WO-02 동시 실행)으로 측정했고, 규모 축소는 기간만 줄인다(TPS·카디널리티 동일). 수치와 근거 표는 `docs/poc/clickhouse-benchmark.md` 5·6·8·12장.
- 판단에 쓴 핵심 사실:
  - Q1~Q11: 1/20 웜 중앙값 10~222ms, 콜드 최대 568ms(기준 0.5~2초). 1/100도 전부 충족.
  - 정합: Q12 0, Q13 범위 합계 0, Q15·Q16 0. Q13 일 단위는 자정을 넘는 SQL의 통계 창 귀속 때문에 불일치(D-12, 별도 결정).
  - 압축: xlog 2.56(82.7B/행은 열 데이터 `data_compressed` 기준, 디스크 `bytes_on_disk` 기준은 86.0B/행), profile 7.16, metric 11.84. 일 증가량(500 TPS)은 data_compressed 기준 약 5.5GB, 디스크 기준 약 5.6GB(13 §14 표 5.3GB 대비 +4%·+6%). xlog의 47%가 압축 불가 랜덤 id이고, `parent_span_id`(90%가 0)·`trace_id`가 `CODEC(NONE)`, 시간 열들이 `LZ4`로 고정돼 서버 기본 코덱을 바꿔도 효과가 막힌다.
  - Q14(분당 3,000값, 1% 이상치): 1m p99 값 오차는 세 함수 모두 74%(불량조건, 순위 오차 ≤ 0.1pp). 1h·1d p99는 BFloat16 2.1%·0.5%, DD 2.5%·0.8%, tDigest 5.1%·2.5%. tDigest 병합값은 버전·순서에 따라 달라 D-14 재구축 동일성을 깨뜨린다.
  - A-6: 시계가 5초 넘게 앞선 인스턴스(D-15 clamp)의 트랜잭션은 복원 ts ±2초 조회가 0행이다(1/20 xlog의 2.86%).

## 결정(제안)

1. **A-6**: `tx_id` 복원 ts 조회(±2초, app 힌트)를 유지한다. 0행이면 `toDate(ts) BETWEEN toDate(t0) - 1 AND toDate(t0) AND tx_id = ?`(idx_tx bloom)로 한 번 더 찾는다(1/20 측정 4~5ms, 1 mark). 14 API 상세 조회와 13 §6.3 (5)에 폴백을 추가한다. **적용 한계**: 이 폴백은 에이전트 시계가 약 24시간 이내로 앞선 경우에만 찾는다(t0와 수신 ts가 다른 날이어도 하루 차이까지. 24~48시간 앞서면 시각에 따라 찾거나 못 찾고, 48시간 이상이면 못 찾는다). 생성기는 +60초 인스턴스만 모델링했다. 한계를 넘는 경우의 처리(범위 없는 bloom 조회로 한 번 더, 또는 "시계 오차로 찾을 수 없음" 오류)는 PM 결정 항목이다(`docs/poc/clickhouse-benchmark.md` 12.1, 14.2 4항).
2. **A-9**: 1.0은 비복제 MergeTree. Replicated 전환은 26.8의 `convert_to_replicated` 플래그(테이블 데이터 디렉터리에 직접 둔다) + 내장 Keeper·macros + 재기동 절차로 17 문서 런북에 싣고, 전환 뒤 1h 테이블을 포함한 dedup window 값을 명시한다.
3. **A-10**: `xlog.sql_ids` 채택(최대 64개). 비용 V001 코덱 10.1B/행(0.44GB/일, 500 TPS), 아래 코덱이면 3.6B/행(0.15GB/일). `has()` 1일 검색 94ms.
4. **A-11**: 트랜잭션 롤업(app·service·instance)의 `q_elapsed`를 `AggregateFunction(quantilesBFloat16(0.5, 0.95, 0.99), UInt32)`로 바꾸고 조회를 `quantilesBFloat16Merge`로 한다. 기준을 "1m 값 오차 그룹 p95 ≤ 2%(p50·p95), 1h 이상 최대 값 오차 ≤ 5%(p99), 1m p99는 순위 오차 ≤ 0.5pp"로 재정의한다. SQL·외부 호출의 15구간 버킷 p95(C-14)는 그대로(오차 1m 최대 19.6%, `estimated` 표기).
5. **코덱**: 서버 기본 압축 `ZSTD(1)`. 열 코덱의 `LZ4`를 `ZSTD(1)`로 바꾼다(`DoubleDelta, ZSTD(1)`, `T64, ZSTD(1)`, `Gorilla, ZSTD(1)`). xlog는 `parent_span_id`·`trace_id`의 `CODEC(NONE)`을 없애고 `tx_id`에 `CODEC(Delta, ZSTD(1))`, `span_id`는 `CODEC(NONE)` 유지. `user_id`·`request_id`·`detail`의 `ZSTD(3)`는 유지. 1/20 하루 사본에서 xlog 3.76(55.7B), profile 11.2, metric 14.4, sql_stat 11.4, ext_stat 11.0(1/100 통제 재측정 2회: xlog 3.79, 반복 간 차이 0.6% 이하), 일 증가량(500 TPS) data_compressed 기준 5.5 → 3.7GB, 디스크 기준 약 5.6 → 약 3.9GB(제안 코덱의 skip index 크기는 V001과 같다고 가정한 추정).
6. **bloom**: 모든 xlog bloom 인덱스 fpp 0.01, GRANULARITY 4. `idx_user`만 GRANULARITY 1(자주 나오는 사용자 조회의 읽는 행 44% 감소, 크기 동일).

## 검토한 대안

| 대안 | 장점 | 단점 | 채택하지 않은 이유 |
|---|---|---|---|
| A-6 폴백 없이 유지 | 조회 단순 | 시계 앞선 인스턴스 트랜잭션 2.86%를 상세 화면에서 못 찾음 | 사용자 노출 결함 |
| A-6 서버가 clamp 시 tx_id 재발급 | 조회 단순 | trace·profile·로그 연계가 에이전트 id를 씀 | id 일관성 파괴 |
| A-9 1.0부터 Replicated + Keeper | 확장 쉬움 | 단일 노드에 Keeper 운영 부담, 측정 이득 없음 | 전환 경로가 검증됨 |
| A-10 미채택(SQL 포함 검색은 profile_step으로) | xlog 12%(LZ4)·6%(ZSTD) 절약 | profile은 캡처된 트랜잭션만 → 검색 누락 | 기능 결손이 비용보다 큼 |
| A-11 tDigest 유지 | 현행, 1m p50·p95 가장 정확 | 병합값이 순서·버전 의존(D-14 위반), 1h p99 5.1% | 결정성 요구 |
| A-11 DD | 상태 최소(2.1KB), 결정적 | 1m p50 2.05%·p95 4.27% 최대 오차 | BFloat16이 1h·1d에서 더 정확. 상태 크기 우선이면 차선 |
| 코덱: ZSTD(1) 기본만 | 변경 작음 | xlog 3.15(66.4B)로 기준은 넘지만 LZ4 고정 열이 손해 | 열 코덱 조정이 추가 −16% |
| 코덱: 열 코덱 전부 제거 + ZSTD | 단순 | metric 10.2(코덱 있음 14.4) | metric에 열 코덱 필요 |
| 코덱: LZ4 유지 | insert·스캔 CPU 최소 | xlog 2.53 < 3(Gate 0 미달), 일 5.5GB(data_compressed 기준, 디스크 기준 5.6GB) | 기준 미달 |
| bloom fpp 0.025 | 인덱스 −20%(xlog의 0.8%) | 거짓 양성 증가 | 절약 미미, N-24 우선 |

## 결과

- 13 §4 DDL(롤업 `q_elapsed` 타입, 열 코덱), §7.2(서버 압축), §6.3 (5)·(8), 02 §12, 14 API 조회 함수, 17 런북(Replicated 전환, 비상 보존 단축의 디스크 회수 지연)을 고쳐야 한다. V001은 이 ADR이 수락될 때까지 바꾸지 않았다(13 원안 + 26.8 호환 수정만).
- 일 증가량이 약 1/3 줄어 13 §14 용량 표와 N-19 매뉴얼 표를 다시 계산한다.
- 하루 전체 스캔 CPU가 약 19% 늘어난다(야간 정합·재구축). 대시보드·검색 쿼리는 측정 잡음 안.
- BFloat16 상태는 tDigest보다 1.6배 커서 트랜잭션 1m 롤업이 하루 약 0.15GB(500 TPS) 늘어난다.
- 되돌리는 비용: 코덱은 `ALTER … MODIFY COLUMN … CODEC`과 머지로 점진 적용 가능. 백분위 함수 변경은 롤업 재구축(D-14 rollups 파일 공유)으로 바꾼다.

## 확인 사항

- [ ] 목표 하드웨어(`argos-storage`)에서 T-ST-001~020 수행(`docs/poc/clickhouse-benchmark.md` 13장 절차)
- [ ] V001 코덱과 제안 코덱의 압축률·insert CPU를 전용 머신에서 3회 반복(Q20)
- [ ] Q14를 실데이터 분포(`--q14-source dataset`)로 반복, BFloat16·DD 재구축 바이트 동일성(D-14) 확인. 로컬 합성 표본 1회(Q14 결정성 단계): BFloat16은 1m 상태 바이트가 재구축마다 달랐고 확정값은 같았다, DD는 1m·1h 바이트 동일, tDigest는 바이트·값 모두 불일치(`docs/poc/clickhouse-benchmark.md` 8.2). "바이트 동일"을 지켜야 하면 결정 4는 DD로 바뀐다(PM 결정)
- [ ] Q17 5,000 TPS 1시간 + 쿼리 10명 30분(쿼리 p95 2초, 적재 지연 p99 5초, parts < 300, DelayedInserts 0, 메모리 < 7.5GiB). 로컬 측정은 축소 부하(약 13.8k 행/s, N-06의 약 21%)였으므로 N-06 부하(생성기 N-06 옵션, `test/storage/bench/README.md` 3b)로 판정한다
- [ ] Q19 보존 기간 값 변경(`ttl_change_1`, materialize 1)이 전체 데이터에서 10분 이내인지(1/100 처리량으로는 2~26분 추정)
- [ ] A-6 폴백 조회 소요(전체 30일 데이터)
- [ ] PM 결정: D-12 일 단위 정합의 자정 귀속(`docs/poc/clickhouse-benchmark.md` 7.3절), A-11 기준 재정의. 이 ADR 밖의 설계 반영 요청(롤업 재구축 절차, migrator 권한·외부 URL 차단, writer 제약·INSERT 범위, 시스템 로그 TTL, C-2 block_id 문구, N-06 통계 행/s 등)은 같은 문서 14.2절
