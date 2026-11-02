# ADR-0007: 저장소 PoC(WO-03) 결과에 따른 저장소 결정과 설계 정합

| 항목 | 내용 |
|---|---|
| 상태 | Accepted (provisional, maintainer confirmation pending. 수치 기준은 목표 하드웨어 T-ST-001~020 확인 대기) |
| 날짜 | 2026-10-25 |
| 결정자 | PM(메인테이너 위임 결정). 메인테이너 확인 대기 |
| 관련 | ADR-0006(WO-03 결정 제안), `docs/poc/clickhouse-benchmark.md` 12·14장, WO-03 완료 보고 "가정과 미해결" B절, 01 D-12·D-14·N-06, 02 §12 A-6·A-9·A-10·A-11·§13 C-2·C-11·C-14·C-17, 10 §9.2·§9.3, 11, 12 §3.8·§9.1, 13 §3~§7·§9·§13.2·§14·§15, 14 API, 16 §10.8, 18 §9 |

## English summary

WO-03 measured the storage design on ClickHouse 26.8.34.13 LTS at 1/100 and 1/20 scale and
returned six decision proposals (ADR-0006) and about thirty design conflicts. This ADR records the PM's
decisions as C-50 to C-71. ADR-0006 is accepted with one change: transaction rollups use `quantilesDD`
instead of BFloat16. D-14 requires byte-identical rebuilds, and DD is the only candidate that met
that requirement while staying within the accuracy criterion. The proposed codecs, the bloom
parameters, `sql_ids`, plain MergeTree for 1.0 and a bounded two-day lookup fallback for A-6 are
accepted, and V001 is updated now because no release exists yet. Other decisions:
- The N-06 load bar of 10,000 statistics rows per second stays. The Gate 0 load profile must reach it.
- Storage column names follow C-17.
- Rollup rebuilds go through a staging table and `REPLACE PARTITION`, never per application.
- The nightly per-day D-12 check allows exactly the SQL executions of transactions that cross midnight.

Every number is still a reference value until the target-hardware run.

## 맥락

- ADR-0006은 WO-03 통합 리드의 제안(Proposed)이다. 근거 수치는 공유 노트북(1/20·1/100)의 참고값이다.
- WO-03 PoC 14.1(26.8 호환 때문에 바뀐 점 13건)과 14.2(PM 결정 요청 14건), PMO가 추가로 찾은 B-1~B-10은 설계 문서끼리 또는 설계와 ClickHouse 26.8 동작이 어긋나는 곳이다.
- 사용자가 구현 단계를 질문 없이 진행하도록 위임했으므로 PM이 정하고 여기 남긴다. 설계 문서(02·10~19)는 메인테이너가 확인한 뒤 고친다. 그때까지 이 ADR이 설계 문서보다 우선한다.
- V001과 `deploy/clickhouse`는 아직 릴리스되지 않았으므로 마이그레이션 없이 고친다.

## 결정

### ADR-0006 결정 제안에 대한 판단

| ID | 주제 | 결정 | 적용 |
|---|---|---|---|
| C-50 | A-6 트랜잭션 상세 조회 | ADR-0006 결정 1을 채택한다. 복원 ts ±2초(app 힌트)로 조회하고, 0행이면 `toDate(ts) BETWEEN toDate(t0) - 1 AND toDate(t0) AND tx_id = ?`로 한 번 더 찾는다. **그래도 0행이면 범위 없는 bloom 조회는 하지 않는다.** API는 "찾을 수 없음, 에이전트 시계 오차 가능" 오류를 돌려주고, 목록·URL ts 경로로 여는 방법을 안내한다. 30일 범위 없는 조회(idx_tx 약 1.6GB 읽기)는 비용을 예측할 수 없다. 시계가 하루 넘게 앞선 인스턴스는 D-15 경고 대상이다. | 13 §6.3 (5), 14 API, 서버 조회 WO |
| C-51 | A-9 Replicated 여부 | 채택(1.0은 비복제 MergeTree). Replicated 전환 런북(`convert_to_replicated` 플래그는 테이블 데이터 디렉터리에 둔다, 1h 테이블 포함 dedup window, interserver 포트·자격증명 재활성)은 17 문서에 싣는다. | 17, WO-40 |
| C-52 | A-10 `xlog.sql_ids` | 채택(최대 64개). | 13 §3, 11, WO-21 |
| C-53 | A-11 백분위 함수 | **ADR-0006 결정 4를 바꾼다. 트랜잭션 롤업(app·service·instance)의 `q_elapsed`는 `quantilesDD(0.01, 0.5, 0.95, 0.99)` 상태를 쓴다.**<br>- D-14는 "재구축하면 바이트 단위로 같은 집계"를 요구한다. 실측에서 1m·1h 상태 바이트가 같았던 것은 DD뿐이다. BFloat16은 확정값만 같고 바이트는 달랐다. 요구를 해석으로 낮추지 않는다.<br>- DD는 상태도 가장 작다(1m 2,122B. BFloat16 4,506B, tDigest 2,790B).<br>- 정확도 기준은 ADR-0006 제안대로 다시 정한다: 1m 값 오차 그룹 p95 ≤ 2%(p50·p95), 1h 이상 최대 값 오차 ≤ 5%(p99), 1m p99는 순위 오차 ≤ 0.5pp. 1m p99 값 오차(세 함수 모두 74%)는 1% 이상치 경계에서 생기는 불량조건이라 값 오차로 판정하지 않는다.<br>- DD의 로컬 값은 1m 그룹 p95 1.47%, 1h p99 2.51%, 1d p99 0.76%로 이 기준을 충족한다.<br>- SQL·외부 호출의 15구간 버킷 p95(C-14)는 바꾸지 않는다. | 13 §4·§6.3, 14 API(`quantilesDDMerge`), 02 §12 A-11 |
| C-54 | 코덱 | ADR-0006 결정 5를 채택한다.<br>- 서버 기본 압축은 `ZSTD(1)`, 열 코덱의 `LZ4`는 `ZSTD(1)`로 바꾼다.<br>- xlog `parent_span_id`·`trace_id`의 `CODEC(NONE)`은 없애고, `tx_id`는 `Delta, ZSTD(1)`, `span_id`는 `CODEC(NONE)`을 유지한다. `user_id`·`request_id`·`detail`은 `ZSTD(3)`을 유지한다.<br>- **V001을 지금 고친다.** Q20 비교용으로 옛 코덱(LZ4) 변형은 남긴다. | 13 §3·§7.2, 02 §12, `deploy/clickhouse` |
| C-55 | bloom 파라미터 | ADR-0006 결정 6을 채택한다. xlog bloom은 fpp 0.01·GRANULARITY 4이고, `idx_user`만 GRANULARITY 1이다. V001에는 **리터럴 값**으로 쓴다(C-56). | 13 §3 |

### 설계 정합(PoC 14.1·14.2, 보고서 B절)

| ID | 주제 | 결정 | 적용 |
|---|---|---|---|
| C-56 | 스키마 템플릿 지시자(B-1) | 13 §13.2 플레이스홀더 허용 목록(`{db}`·`{p}`·`{where}`·`{retention.*}`)에 포함 지시자 `{rollup:<table>}` 하나만 더한다. 롤업 SELECT를 MV와 재구축이 공유하기 위해서다(D-14). bloom 값은 C-55로 정해졌으니 `{bloom.*}`는 없애고 리터럴로 쓴다. 스키마 체크섬은 `{rollup:}`를 펼친 뒤, 환경 플레이스홀더를 치환하기 전의 텍스트로 계산한다. 그래서 DB 이름·접두사를 바꿔도 체크섬은 그대로다. | 13 §13.2, WO-20·WO-21 마이그레이션 러너 |
| C-57 | D-12 일 단위 정합의 자정 귀속(14.2 #1) | 에이전트 통계는 지금처럼 SQL·호출이 **끝난** 10초 창에 귀속한다. 10 §9.2·§9.3은 바꾸지 않는다. 시계열 차트에는 종료 시각 귀속이 맞기 때문이다.<br>판정 규칙은 셋이다.<br>- 분 단위(Q12)와 기간 합계(Q13 total)는 계속 **불일치 0**이어야 한다.<br>- 야간 일 단위 검사(13 §6.3 (14))는 \|Δ일\|이 그날 양쪽 자정을 가로지른 트랜잭션(`ts < 자정 ≤ ts + elapsed`)의 `sql_count`·`ext_count` 합 이하이면 통과다. 이 상한은 xlog에서 정확히 계산된다.<br>- 상한을 넘으면 F-S22대로 불일치를 기록하고 재구축한다. | 13 §6.3 (14), F-S22 야간 잡 WO |
| C-58 | 26.8 호환·보강(14.1 #1~#13, 14.2 #11·#13) | 전부 승인한다.<br>- 롤업 `allow_dimensions_outside_sorting_key`, `AggregateFunction(sumForEach)`·`sumForEachMerge`, metric `sample_count` UInt64.<br>- **1h 테이블 dedup window 1000**, `insert_deduplication_token_support` 제거, reader 상한 6종 `changeable_in_readonly`(낮추기만), MV `SQL SECURITY DEFINER`(definer `argos_migrator`), merge_tree 임계값 비례 축소.<br>- default 사용자 제거(SEC-74 우선), 시스템 로그 TTL 7일·미사용 로그 제거, MySQL·PostgreSQL·interserver 리스너 제거(8123·9000만), writer 프로필 제약.<br>- migrator 하위 권한(사전·함수·named collection DDL 없음), 빈 `remote_url_allow_hosts`(설치 시 운영자가 지정한 백업 대상만 추가). | 13 §4·§5·§6.3 (8)·§7.2·§7.3, 14 API, 17 |
| C-59 | 롤업 재구축 절차(14.2 #10) | (a) 재구축은 staging 테이블(`CREATE TABLE <t>_rebuild AS <t>`)에 넣고 `ALTER TABLE <t> REPLACE PARTITION … FROM <t>_rebuild`로 바꾼다. 롤업에 직접 INSERT하지 않는다(1h 캐스케이드 이중 집계).<br>(b) 1m 하루를 재구축하면 같은 달의 1h를 1m에서 월 단위로 다시 만든다.<br>(c) 재구축 단위는 파티션 전체(모든 앱)다. 12 §9.1의 앱 단위 `rebuild(app, …)`·`--app`은 없앤다. | 13 §4.1·§13, 12 §9.1, F-S22 |
| C-60 | ClickHouse 계정 권한(14.2 #11·#12, B-7, C-11) | 계정 이름은 C-11대로 `argos_writer`·`argos_reader`·`argos_migrator`이다. 16 §10.8의 `argos_admin`은 `argos_migrator`로 읽는다.<br>- **writer의 INSERT는 원본 5개 테이블(`{p}xlog`·`{p}profile_step`·`{p}metric`·`{p}sql_stat_raw`·`{p}ext_stat_raw`)로 좁힌다**(SEC-76). 롤업은 `SQL SECURITY DEFINER` MV만 쓴다.<br>- migrator에 `SELECT ON system.mutations`를 준다. 삭제 잡이 같은 계정으로 완료를 폴링하기 위해서다(12 미결 6, 13 §9). | 13 §7.3·§9, 16 §10.8, `deploy/clickhouse/users.d` |
| C-61 | 중복 제거 토큰과 block_id(14.2 #8, C-2) | C-2·DSN-02-12의 "COMMIT 레코드 위치"를 다음으로 바로잡는다.<br>- 토큰은 insert 전에 **INTENT 레코드에 기록되는 결정적 값** `<wal_instance_id>:<epoch>:<first>-<last>`(데이터 레코드 위치 범위)다.<br>- WAL을 초기화하면 epoch를 올린다. 서버가 여러 대여도 instance id로 구분된다.<br>- 13 §5.1의 UUIDv7은 쓰지 않는다.<br>- 라이터는 `X-ClickHouse-Summary.written_rows`로 중복 여부를 판정하지 않는다. | 02 §13 C-2 문구, 12 §3.8, 13 §5.1, WO-21 |
| C-62 | N-06 통계 행/s(14.2 #9, B-9) | **N-06의 10,000 통계 row/s는 그대로 둔다.** 요구는 서버가 감당할 상한이다. 생성기가 정합을 지키며 만들 수 있는 양에 맞춰 낮추지 않는다.<br>- Gate 0 Q17 3b 부하는 인스턴스 수·활성 SQL 수를 올려 **통계 ≥ 10,000행/s**에 닿게 한다(정합 유지, 예: 인스턴스 500 × 활성 SQL 600, 1/100 생성기 단독 실측 11,458행/s. 인스턴스 500만 또는 활성 SQL 350만으로는 닿지 않는다: ADR-0009 C-85).<br>- 실제로 닿은 행/s를 기록하고, 닿지 못하면 Gate 0 미결로 남긴다.<br>- 13 §14의 `sql_stat_raw` 용량 공식은 목표 하드웨어 실측 행/s로 다시 계산한다. 13 §15(1.2억)와 §14 공식(약 12억/14일)의 10배 차이를 그때 정정한다. | 13 §14·§15 Q17, `test/storage/bench/README.md`, Gate 0 |
| C-63 | 생성기 분포(14.2 #6) | 13 §15가 원본이다. 18 §9의 다른 값(외부 호스트 30, BATCH 2%, ext_stat 0.2억)은 13 §15에 맞춘다. | 18 §9 |
| C-64 | 조회 상한과 감사(14.2 #7) | 정확한 결과 상한은 `LIMIT`로 건다. `result_overflow_mode=break`는 안전망일 뿐이다(블록 단위로 넘친다). 감사가 필요한 쿼리 설정은 URL 파라미터가 아니라 SQL `SETTINGS` 절로 보낸다. URL 설정 거부는 `query_log`에 남지 않기 때문이다. | 14 API, 서버 조회 WO |
| C-65 | 야간 정합 SQL 형식(14.2 #14) | 13 §6.3 (13)(14)의 3개 이상 `FULL JOIN … USING`을 소스별 `UNION ALL` + 키 `GROUP BY` + `sumIf` + `HAVING` 형태로 바꾼다. `join_use_nulls` 설정에 의존하지 않기 위해서다. 테스트 SQL도 같은 형태를 쓴다. | 13 §6.3, F-S22 야간 잡 WO |
| C-66 | 삭제 잡과 비상 보존 단축(14.2 #5) | 13 §9 삭제 잡은 `ALTER TABLE … DELETE IN PARTITION`을 쓴다. 대상 날 밖의 part를 다시 쓰지 않는다는 근거만 있고, 소요 시간 차이는 목표 하드웨어에서 3회 이상 반복해 확인한다. 비상 보존 단축 런북에는 "디스크 회수는 10분 넘게 걸릴 수 있다"를 적는다. | 13 §9, 17 런북 |
| C-67 | xlog 열 이름(B-2) | C-17의 이름을 쓴다: `dbc_wait_ms`·`timed_out`·`app_version_id`(13의 `dbc_time_ms`·`timeout_flag`·`version_id` 대신). 롤업 열은 `sum_dbc_wait_ms`다. 에이전트·프로토콜·서버·저장소가 같은 이름을 써서 매핑 계층을 없앤다. **V001을 지금 고친다.** | 13 §3·§4, 11, 12, `deploy/clickhouse` |
| C-68 | `sql_stat_raw.dbc_wait_sum`(B-3) | 넣지 않는다(13 §3.5가 맞다). 커넥션 획득 대기는 SQL 단위가 아니라 풀 단위 현상이다. 메트릭 `jdbc_pool_wait_*`와 트랜잭션 단위 `xlog.dbc_wait_ms`로 본다. 11의 `SqlStat.dbc_wait_sum_us`와 12의 `sql_stat_raw.dbc_wait_sum_ms`는 지운다. | 11, 12, proto(WO-02 산출물이면 후속 정리) |
| C-69 | `xlog.flags` 비트(B-4) | 1 = virtual thread, 2 = leaked, 4 = summarized로 확정한다. 나머지 비트는 예약이다. | 10, 11, 13 §3 |
| C-70 | `profile_step.batch_size`·`detail_enc`(B-5) | 13 §3 DDL에 넣는다(V001에는 이미 있다). | 13 §3 |
| C-71 | 그 밖의 항목(B-6·B-8·B-10, 14.1 #12) | B-6: `background_schedule_pool_size`는 LTS 기본값을 쓰고 13 §7.2의 8로 낮추지 않는다. N-06 부하 실측(parts·DelayedInserts)에서 문제가 보일 때만 다시 정한다.<br>B-8: `detail_enc` 암호화 열 압축률(16 미결 14)은 `full` 모드 암호화를 구현하는 WO에서 Q20 변형으로 잰다.<br>B-10: 02 §5.2 데이터 분류표에 `request_id`(앱이 붙인 요청 식별자, 운영 데이터, 수집)와 `incoming_trace`(들어온 추적 헤더 존재·형식, 운영 데이터, 수집) 행을 더한다. | 13 §7.2, 16, 02 §5.2 |

## 검토한 대안

| 대안 | 장점 | 단점 | 채택하지 않은 이유 |
|---|---|---|---|
| C-53: BFloat16 + D-14를 "확정값 동일"로 해석 | 1h·1d p99가 가장 정확(2.1%·0.5%) | D-14 문구(바이트 동일)를 해석으로 낮춤. 상태 2.1배 | DD도 새 기준을 충족하고 요구를 그대로 지킨다 |
| C-50: 2일 폴백 뒤 범위 없는 bloom 조회 | 시계가 크게 틀린 경우도 찾음 | 30일이면 1.6GB 읽기, 비용 예측 불가 | 드문 경우를 위해 조회 비용 상한을 깨지 않는다 |
| C-57: 에이전트가 트랜잭션 시작 창으로 귀속 | 일 단위도 0 | 10 문서 변경, 차트 시각 왜곡 | 판정 규칙만 바꿔도 손실을 잡는다 |
| C-62: N-06 통계를 6,000행/s로 정정 | 생성기 기본값으로 판정 가능 | 처리량 요구를 낮춤 | 요구를 약화하지 않는다. 부하 조건을 올린다 |
| C-67: 13 이름 유지 + 라이터 매핑 | V001 그대로 | 이름 두 벌이 영구히 남음 | 릴리스 전이라 지금 바꾸는 비용이 가장 작다 |

## 결과

- **WO-03 후속 정리**(이 ADR 직후, WO-02 종료 뒤 수행):
  - V001·롤업·config·users를 C-53·C-54·C-55·C-56·C-60·C-67·C-71(B-6)에 맞춘다.
  - 생성기·쿼리·테스트를 갱신하고, Q14·Q20은 비교 변형을 유지한다.
  - 열린 리뷰 지적 A-1·A-2·A-3을 처리하고, `testcontainers/ryuk` 이미지를 다이제스트로 고정한다.
  - CI 1/100 자동 기준을 다시 녹색으로 만든다.
- 설계 문서(02 §12·§13, 10, 11, 12 §3.8·§9.1, 13, 14, 16 §10.8, 17, 18 §9)는 메인테이너 확인 뒤 고친다. 그때 C-50~C-71을 02 §13 표에 옮긴다.
- ADR-0006은 "Accepted with amendment(결정 4 → C-53)"이다. 목표 하드웨어 확인 항목은 그대로 남는다.

## 확인 사항

- [ ] 메인테이너가 C-50~C-71을 확인했다.
- [ ] 목표 하드웨어에서 T-ST-001~020을 수행했다(Q17 3b는 통계 ≥ 10,000행/s 조건, C-62).
- [ ] 02 §13 표와 관련 설계 문서를 갱신했다.
