# ADR-0001: 프로젝트 라이선스 Apache-2.0과 저작권자 표기

| 항목 | 내용 |
|---|---|
| 상태 | Accepted (provisional, maintainer confirmation pending) |
| 날짜 | 2026-10-23 |
| 결정자 | PM(메인테이너 위임 결정). 메인테이너 확인 대기 |
| 관련 | 01 문서 미결 12(라이선스)·13(저작권자 표기), O-L01~O-L08, O-G01, R-13, 19 문서 §5.1·§5.5·§8, 03 문서 Gate 0 "오픈소스 골격" |

## English summary

Argos APM is licensed under the Apache License 2.0, and the license will not change. The copyright
line in `NOTICE` reads "Copyright 2026 Argos APM contributors"; this holder name is provisional until
the maintainer confirms it (and, if the work was created in employment, obtains the employer's written
approval). Contributions are accepted under the Developer Certificate of Origin (no CLA), every
source file carries the header `SPDX-License-Identifier: Apache-2.0` (no per-file copyright line),
and shipped artifacts may only contain dependencies from the license allow list. The decision was
made by the PM under delegated authority and is recorded here for the maintainer to confirm.

## 맥락

- 01 문서 §14 미결 12·13은 WO-00 착수 조건이다. 첫 공개 커밋부터 LICENSE·NOTICE·DCO가 있어야 이력이
  깨끗하다(O-L01·O-L02·O-L06, DSN-19-12).
- 에이전트는 사용자 앱 JVM 안에서 실행되고 의존성(ByteBuddy, gRPC-java, Netty, protobuf, JCTools)을
  전부 셰이딩해 단일 jar로 배포한다. 라이선스는 이 의존성 전부와 호환이어야 하고, 셰이딩 jar에 고지 외의
  추가 의무를 만들지 않아야 한다.
- 겨냥하는 현장(폐쇄망·금융·공공)은 AGPL 컴포넌트를 앱 프로세스에 붙이는 것을 거부한다. Pinpoint,
  Scouter, SkyWalking, OpenTelemetry Java agent, Elastic APM agent가 모두 Apache-2.0이다(01 문서 §10.1).
- 저작권자 표기(미결 13)는 회사명 또는 "Argos APM contributors" 중 선택이며, 업무상 저작물이면 회사의
  서면 승인이 필요하다. 승인 서면은 아직 확보되지 않았다.

## 결정

1. **라이선스는 Apache License 2.0**이다. 저장소 루트 `LICENSE`는 apache.org 원문
   (`https://www.apache.org/licenses/LICENSE-2.0.txt`, SHA-256
   `cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30`)을 그대로 둔다. 모든 배포물
   (에이전트 jar, 서버 tar·이미지, 에어갭 번들)에 `LICENSE`·`NOTICE`를 포함한다(O-L01·O-L04).
2. **라이선스는 바꾸지 않는다.** GOVERNANCE.md에 명시한다. CLA를 두지 않으므로 재라이선스 수단도 없다.
3. **저작권자 표기는 "Argos APM contributors"**(잠정). `NOTICE` 첫머리:
   ```
   Argos APM
   Copyright 2026 Argos APM contributors
   ```
4. **파일 헤더는 SPDX 한 줄**이며 저작권 줄을 두지 않는다(19 문서 §5.1). Java
   `/* SPDX-License-Identifier: Apache-2.0 */`, TS/JS `// SPDX-License-Identifier: Apache-2.0`,
   셸·YAML·TOML·properties `# SPDX-License-Identifier: Apache-2.0`. 저작권 줄은 `NOTICE`에만 둔다.
5. **기여는 DCO 1.1**(`Signed-off-by`, `git commit -s`). CI `dco` 잡이 PR의 모든 비머지 커밋에서 작성자와
   일치하는 서명을 검사한다(`.github/scripts/check-dco.sh`). AI 생성 코드는 제출자가 DCO로 책임진다(O-L08).
6. **배포물 의존성 허용 목록**: Apache-2.0, MIT, BSD-2-Clause, BSD-3-Clause, ISC, 0BSD, PostgreSQL,
   Unicode-DFS, CC0-1.0. 테스트·샘플 전용 예외는 `docs/licenses/dev-only.md`에 기록한다(O-L03, DSN-19-04).
7. 외부 코드를 차용하면 원 저작권 헤더를 유지하고 `NOTICE`의 차용 코드 절에 기록한다(O-L05). 현재 없음.

## 검토한 대안

| 대안 | 장점 | 단점 | 채택하지 않은 이유 |
|---|---|---|---|
| MIT | 가장 단순 | 명시적 특허 허여 없음 | 기업 채택에 Apache-2.0의 특허 조항이 유리(01 §10.1) |
| AGPL-3.0 | 네트워크로 제공되는 수정본도 소스 공개 의무 | javaagent로 붙은 앱이 파생물인지 해석 불확실, 목표 현장이 거부 | 채택 장벽 |
| 다른 라이선스와 병행 + CLA | 나중에 재라이선스 가능 | CLA가 기여 장벽, 라이선스 변경 가능성 자체가 신뢰를 낮춤 | 01 §10.1 "라이선스는 바꾸지 않는다" |
| 저작권자 = 회사명 | 법적 주체 명확 | 회사 서면 승인 필요(미확보) | 승인 확보 시 이 ADR을 갱신해 전환 가능 |

## 결과

- CI가 라이선스를 기계로 지킨다: SPDX 헤더(`spotlessCheck`, `test/lint/check-spdx.sh`), 허용 목록
  (`checkLicenses`, `checkDevOnlyLicenses`, `pnpm -C ui check:licenses`), DCO(`dco` 잡).
- 셰이딩 jar는 `META-INF/LICENSE`·`NOTICE`·`THIRD-PARTY-NOTICES`를 반드시 포함한다(`checkShading`).
- 저작권자 표기를 바꾸려면 `NOTICE` 한 곳과 이 ADR만 고치면 된다(파일 헤더에 저작권 줄이 없으므로).

## 확인 사항

메인테이너가 확인하고 결과를 이 표에 기록한 뒤 상태를 "Accepted"로 바꾼다.

- [ ] 라이선스 Apache-2.0 확정(01 미결 12)
- [ ] 저작권자 표기 확정(01 미결 13): "Argos APM contributors" 유지 또는 회사명으로 변경
- [ ] 업무상 저작물이면 회사의 공개 승인 서면 확보(확보 전 저장소 비공개 유지, 01 미결 13)
- [ ] `LICENSE`·`NOTICE`·README 라이선스 절 내용 확인(04 문서 WO-00 수동 수락 기준)
