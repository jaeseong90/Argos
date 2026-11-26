# Fixture: security requirements (WO-53 checkTraceability tests)

## 2. 요구사항

### A. 토큰

| ID | 요구 | 기본값·수치 | 검증 | 단계 |
|---|---|---|---|---|
| SEC-01 | 앱 단위 토큰 | 32바이트 | 자동: 다른 앱 이름 Hello 거부 | M |
| SEC-03 | 만료일 | 무기한 | 자동: 만료 토큰 거부 | G2 |
| SEC-11 | 수신 상한 | 4MB | 자동: gzip 폭탄 | M |
| SEC-30 | 데이터 분류표 | | 수동: 표 존재. 자동: 새 필드 | M |
| SEC-34 | 쿼리스트링 `capture.query-string: off | whitelist | full` | off | 자동: 저장 URL에 phone 부재 | G2 |

### K. 문서

| ID | 요구 | 검증 | 단계 |
|---|---|---|---|
| SEC-91 | 위협 모델 문서 | 수동: Gate 1 초안 | M(초안)·G2 |
