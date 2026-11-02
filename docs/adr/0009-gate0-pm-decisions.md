# ADR-0009: Gate 0 판정 때 정한 PM 결정(C-72~C-90)

| 항목 | 내용 |
|---|---|
| 상태 | Accepted (provisional, maintainer confirmation pending) |
| 날짜 | 2026-11-03 |
| 결정자 | PM(메인테이너 위임 결정). 메인테이너 확인 대기 |
| 관련 | ADR-0005(전송), ADR-0007(저장소), ADR-0008(셰이딩), WO-02·WO-03F·WO-05 완료 보고의 "PM 결정 필요" |

## English summary

Phase 0 work orders left decisions that the Gate 0 "decisions" row and the first Phase 1 work orders
depend on. This ADR records them as C-72..C-90, continuing the alignment numbering of 02 §13 and
ADR-0003/0004/0007. Highlights: ADR-0005 is accepted (gRPC over grpc-netty, protobuf-java, Netty 4.1
to keep C-22, a widened but test-proven `Unsafe` allow-list condition, a Windows-only T-BLD-006
exception for the JDK NIO selector socket file); the C-57 daily bound and D-15 clock correction are
reconciled by correcting statistics windows with the same clock offset as xlog `ts` (WO-21); the
WO-02 PoC exporter keys leave the public configuration (WO-10); bench and harness conventions from
WO-05 are fixed. Design-document text is corrected by the maintainer later; until then this ADR
wins where it differs.

## 맥락

- Gate 0(03 §4) "결정" 행은 02 §12 A-1·A-6·A-9·A-10·A-11·A-14, 코덱, bloom 파라미터의 확정을 요구한다. A-6·A-9·A-10·코덱·bloom은 ADR-0006, A-11은 ADR-0007 C-53으로 잠정 확정됐다. A-1·A-14는 ADR-0005가 Proposed로 남았다.
- WO-02(B-1~B-12), WO-03F(C-57 대 D-15, C-62 예시), WO-05(가정과 미해결 A-1~A-16)가 PM 결정을 요청했다. Phase 1의 WO-10·11·21이 이 결정에 기대어 착수한다.
- 원칙: 요구(N-xx·D-xx·SEC-xx)를 낮추지 않는다. 설계 문서 본문 정정은 메인테이너가 한 번에 한다(아래 "결과"). 그 전까지 구현자는 이 표를 따른다.

## 결정

| # | 주제(출처) | 결정 | 적용 |
|---|---|---|---|
| C-72 | A-1·A-14(WO-02 B-1, ADR-0005) | ADR-0005 결정 1~7을 채택한다. 전송은 gRPC over grpc-netty(NIO, `SslProvider.JDK`, 10 §10.3 고정 설정), protobuf는 에이전트도 protobuf-java(full). `protobuf-javalite`는 JDK 24+ `Unsafe` 경고·deny 실패 때문에 쓰지 않는다. | 02 §12, WO-11 |
| C-73 | Netty 계열과 C-22(WO-02 B-2, ADR-0005 P1) | **Netty 4.1.x(4.1.139 이상)를 쓴다.** C-22(셰이딩 class major ≤ 52)를 바꾸지 않는다. grpc-java 업그레이드 때마다 4.1 호환을 테스트로 확인한다. Netty 4.1 지원 종료 공지가 나오면 C-22 예외(실행 시점 버전 검사로만 도달하는 클래스)를 다시 검토한다. | WO-11, 19 §3.3 |
| C-74 | `Unsafe` 허용 조건(WO-02 B-3, ADR-0005 P2) | 허용 조건을 넓힌다. 로드되지 않는 `Unsafe` 참조 클래스는 지금처럼 jar에서 뺀다. 로드될 수 있는 클래스는 다음을 **모두** 테스트로 증명할 때만 `test/agent/unsafe-allowlist.txt`에 둔다: 에이전트가 설정한 프로퍼티(`noUnsafe` 등)로 `Unsafe` 사용이 꺼진다, JDK 25 기본(warn)에서 경고 0줄, `--sun-misc-unsafe-memory-access=deny`에서 동작, 클래스 로드 기록에 근거가 남는다. 항목마다 이유 주석을 단다. 의존성 갱신 때 목록을 다시 검증한다. | WO-11, T-BLD-004, `check-shading.sh` |
| C-75 | Windows NIO 선택기 temp 파일(WO-02 B-4, ADR-0005 P3) | JDK 16+ **Windows**에서 JDK NIO `Selector`가 만들고 바로 지우는 AF_UNIX `socket_<n>` 파일은 T-BLD-006의 예외로 둔다. 에이전트 코드가 만드는 파일이 아니고, 회피책(`jdk.net.unixdomain.tmpdir`)은 앱 JVM 전역 프로퍼티를 바꾸므로 쓰지 않는다. Linux·macOS는 예외가 아니다(WO-11이 Linux에서 0을 확인). 알려진 제약으로 사용자 문서에 적는다. | WO-11, 18 T-BLD-006, 17 알려진 제약 |
| C-76 | 로그 디렉터리 temp 폴백(WO-02 B-5) | 10 §14 설계를 유지한다. `log.dir`과 에이전트 옆 `logs`에 쓸 수 없을 때만 `java.io.tmpdir/argos-agent-<pid>`로 폴백한다. T-BLD-006 "임시 파일 0"은 로그 디렉터리에 쓸 수 있는 기본 조건에 적용한다. CHANGELOG 문구는 WO-02 PM 후속 조치(C-10)로 확정한다. | 10 §14, CHANGELOG |
| C-77 | JDK 17+ CDS 안내 줄(WO-02 B-6) | JVM이 직접 쓰는 CDS 안내 줄은 에이전트 출력이 아니다. T-AG-060의 stdout·stderr 0바이트 판정에서 그 정확한 줄만 하네스 허용 목록으로 뺀다(WO-05 구현). | 18 §5.10, T-AG-060 |
| C-78 | 로거 초기화 전 premain 실패(WO-02 B-7, WO-05 C-1) | 에이전트가 시작하지 못하고 로그 파일도 쓸 수 없을 때에 한해 stderr에 **한 줄**(`argos: agent disabled: <reason>`, 비밀값 없음)을 허용한다. 시작 실패 경로는 그 전에 `AgentLogger`를 flush한다(T-AG-044 발견). | WO-10, AGT-02, F-A35 |
| C-79 | 기동 위험(WO-02 B-8) | 기준(N-04 +2초, N-30 premain ≤ 500ms)은 그대로다. WO-10은 기동 경로(클래스 로드 수·`classdata` 해석)를 측정하고 줄이는 일을 수락 기준에 포함한다. 판정은 전용 장비 측정(WO-19 재측정)으로 한다. | WO-10, WO-19 |
| C-80 | 오버헤드 판정 규칙(WO-02 B-9, WO-05 A-11~A-14) | 재현 기준은 `docs/poc/agent-overhead.md` 9.4 제안을 채택한다. H-2·H-5·H-6은 그 문서의 제안을 잠정 규칙으로 쓰고, 전용 장비 첫 유효 실행 뒤 WO-51에서 다시 정한다. Gate 0 판정 익스포터는 18 §5.4 `noop`이다. WO-11 전에는 인코딩 단계가 없어 WO-02 `drop`과 비용이 같으므로 9장 명령을 그대로 쓴다. 주간 벤치 세트는 main(default)과 startup만, container·vt·worst·otel은 릴리스 후보마다 돌린다(18 §2.4 6시간 예산). | 01 §7.3, 18 §7, WO-51 |
| C-81 | 전송 비교 하네스의 알려진 발견 단언(WO-02 B-10) | 수용한다. 배포 jar 기준(`verifyAgentJar`, T-BLD-006)은 그대로이고 `-Ptransport.poc.strict=true`가 원래 판정을 유지한다. | `test/agent/transport-poc` |
| C-82 | ADR-0008과 C-23(WO-02 B-11) | ADR-0008(RELOCATED + 자체 리터럴 규칙, `checkOwnRelocatedLiterals`)을 정합 결정 목록에 올린다. | CLAUDE.md, 02 §13 |
| C-83 | 데이터 분류(WO-02 B-12) | `docs/poc/agent-poc.md` 14.4의 6개 행을 02 §5.2 분류표에 넣는다. 외부 호출 detail의 경로 매개변수(`;jsessionid=` 등)는 비밀값 성격으로 보고 에이전트가 제거한다(WO-02 PM 후속 조치에서 구현). | 02 §5.2, SEC-30 |
| C-84 | C-57 일 단위 상한과 D-15 시계 보정(WO-03F) | 근본 해결은 선택지 (b)다. WO-21의 `TimestampGuard`가 시계 보정 인스턴스의 **통계 창 시각도 xlog `ts`와 같은 오프셋**(세션 ClockSync 오프셋)으로 보정한다. 그러면 자정 귀속이 xlog와 같아져 C-57 상한이 그대로 성립한다. WO-21 전까지 저장소 PoC의 일 단위 검사는 보정 인스턴스를 빼고(선택지 (c)) D-15 경고로 센다. 분 단위(Q12)와 기간 합계(Q13)는 계속 불일치 0이다. | WO-21, 13 §6.3 (14), D-15 |
| C-85 | C-62 예시 수치(WO-03F) | ADR-0007 C-62의 예시 "인스턴스 500 또는 활성 SQL 350 이상"은 하나만으로 10,000행/s에 닿지 않는다(7,381·7,606행/s 실측). 예시를 "인스턴스 500 × 활성 SQL 600(11,458행/s 실측)"으로 고친다. 기준(≥ 10,000행/s)은 그대로다. | ADR-0007, `test/storage/bench/README.md` 3b |
| C-86 | fault 주입 단위(WO-05 A-1) | `-Dargos.test.faults`는 **모듈 단위**(`advice-throw:<module>` 등)가 계약이다. 지점 단위 표기는 WARN 1회로 거부한다. 18 §5.7 예시를 모듈 단위로 고친다. 지점 단위가 꼭 필요한 테스트가 생기면 그 WO에서 다시 요청한다. | 18 §5.7, `agent-testing` |
| C-87 | 힙 상주 판정 접두(WO-05 A-2) | N-02·벤치 7.4·`LeakProbe`의 힙 상주는 `io.argos.agent.`(셰이딩 `io.argos.agent.shaded.` 포함) 인스턴스 바이트 합으로 판정한다. 샘플 앱 `io.argos.sample.*`는 뺀다. `LeakProbe`는 WO-10에서 이 접두로 맞춘다. | 01 §7.3 항목 7, 18 §5.9·§7.4·§11.2 |
| C-88 | WO-02 PoC 익스포터 키(WO-05 A-4·A-12, build-safety-1) | `argos.exporter`·`exporter.file.*`는 공개 설정에서 뺀다(WO-10, 10 §15 설정 레퍼런스에 넣지 않음). file·noop은 `argos.test.exporter`로만 쓰고, 릴리스 빌드는 무시한다(T-BLD-010). 벤치는 같은 커밋의 SNAPSHOT 빌드 jar로 잰다(릴리스 판정 게이트 말고는 바이트 코드가 같다). WO-10은 `ReleaseGateAttachTest`의 기대값을 이 결정에 맞춘다. | WO-10, 18 §5.4 |
| C-89 | 릴리스 판정과 진단 로그(WO-05 A-6·A-7) | 릴리스 빌드 = `Implementation-Version`이 `-SNAPSHOT`으로 끝나지 않는 jar이고, 판정을 못 하면 릴리스로 본다(fail-closed). `argos.debug.transform-log`는 클래스·로더 이름만 기록하므로 릴리스에서도 허용한다. 19 §3.3·§5.6에 판정 기준과 check-shading `Implementation-Version` 행을 넣는다. | 19 §3.3·§5.6 |
| C-90 | 하네스·벤치 정리(WO-05 A-3·A-5·A-8·A-9·A-10·A-15·A-16) | 변환 베이스라인 위치는 18(`agent/agent-testing/baselines/transformed-classes/`), `Unsafe` 허용 목록 원본은 `test/agent/unsafe-allowlist.txt` 하나다. `serverLoadTest`는 18 §8(`test/load/fake-agents`, WO-29)을 따른다. 컨벤션의 NO-SOURCE `benchTest` 정리와 CI 연결(`test/bench/*.sh` shellcheck, `traceabilityReport` 아티팩트 90일)은 WO-53에서 한다. T-BLD-013(테스트 계층·check 구성), T-BLD-014(추적성 보고서), T-BLD-015(벤치 판정 단위)를 18 부록 A에 예약한다. WAR 조합은 PR이 아니라 nightly에서 돈다. | 18 §5.1·§5.10·§8·부록 A, 19 §11, WO-29, WO-53 |

## 검토한 대안

| 대안 | 채택하지 않은 이유 |
|---|---|
| C-73: Netty 4.2 + C-22 예외 | grpc-java 기본 조합이지만 C-22를 바꾼다. 4.1로 C-22를 지키며 동작을 확인했다(8u252~25) |
| C-74: 허용 조건 유지 | 조건을 만족하는 전송이 HTTP/1.1뿐이라 11 문서·DSN-02-11 재설계가 필요하다 |
| C-75: `jdk.net.unixdomain.tmpdir` 설정 | 앱 JVM 전역 프로퍼티를 바꾼다. 에이전트는 앱 설정을 바꾸지 않는다 |
| C-84: 선택지 (a) 상한에 flush 창을 더함 | 상한이 느슨해지고 시계 보정 인스턴스의 귀속 차이를 숨긴다 |
| C-88: PoC 키를 공개 설정으로 유지 | 10 문서에 없는 키가 릴리스에서 수집 데이터를 로컬 파일로 쓴다(데이터 분류·SEC 검토 밖) |

## 결과

- WO-10: C-78·C-79·C-87·C-88 반영(설정 레퍼런스, 시작 실패 경로, LeakProbe 접두, PoC 키 제거).
- WO-11: C-72~C-75 반영(Netty 4.1 BOM 고정, 허용 목록 증명 테스트, Windows 예외 문서화, Linux temp 0 확인).
- WO-21: C-84(통계 창 오프셋 보정).
- WO-51·WO-53: C-80·C-90.
- 메인테이너 설계 문서 정정(한 번에): 02 §5.2(C-83)·§12(이 ADR로 갱신함)·§13 목록, 10 §14·§15, 13 §6.3, 17 알려진 제약(C-75), 18 §5.7·§5.9·§7.4·§11.2·부록 A, 19 §3.3·§5.6·§11, ADR-0007 C-62 예시(C-85, PM이 고침).

## 확인 사항

- [ ] 메인테이너가 C-72~C-90을 확인했다.
- [x] PM이 02 §12 A-1·A-9·A-10·A-11·A-14 행을 갱신했다(2026-11-03).
- [x] PM이 ADR-0005 상태와 ADR-0007 C-62 예시를 고쳤다(2026-11-03).
- [ ] 위 "결과"의 설계 문서 정정을 반영했다.
