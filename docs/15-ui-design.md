# Argos UI 설계

| 항목 | 내용 |
|---|---|
| 문서 번호 | 15 |
| 문서 버전 | 0.1 (초안) |
| 작성일 | 2026-10-23 |
| 상태 | 설계 검토 중 |
| 만족시키는 요구 | F-U01~F-U21, N-14, S-01~S-18(화면 흐름), D-06·D-07·D-18·D-19·D-20(표시 규칙), SEC-40·43·44·46·60~64, O-A05·O-P03, 05 문서 2.0~2.11 |
| 의존 문서 | 02 아키텍처(§4.4, §6.3, §7, DSN-02-18·20·21), 14 API, 12 서버(WS), 16 보안(CSP·쿠키), 05 벤치마크 2장 |
| 이 문서에 의존하는 문서 | 14 API(응답 필드), 18 테스트(E2E 하네스), 19 빌드(pnpm·번들 검사) |

## 1. 범위와 원칙

이 문서는 `ui/`의 기술 스택, 정보 구조, 상태·URL 모델, 공통 컴포넌트, XLog 캔버스, 화면별 사양, 실시간 클라이언트, 인증·보안 구현, i18n, 디자인 토큰, 성능 예산, 테스트 전략을 정한다. Claude Code가 UI WO 하나를 받았을 때 이 문서와 05 문서 2장만으로 구현할 수 있어야 한다.

UI 설계 원칙(01 문서 5.2 원칙 7 "사용성"의 구체화)

| 원칙 | 의미 |
|---|---|
| URL이 진실이다 | 공유 가능한 모든 상태(앱·환경·시간·종류·필터·선택·탭)는 URL에 있다. 메모리 상태는 URL의 파생물이거나 일시적(연결 상태·접힘)이다 |
| 시간 범위는 하나다 | 화면·차트·XLog·딥링크가 같은 전역 시간 상태를 바꾼다 |
| 모든 값은 다음 화면으로 이어진다 | 목록 → 상세, 상세 → 통계, 표의 값 → 필터, 차트의 점 → 목록 |
| 숨기지 않는다 | 드롭·결측·샘플링·수집 중단·마스킹·"프로파일 미수집" 사유를 화면에 표시한다(D-06·D-07·D-18·D-19) |
| 수집 데이터는 신뢰하지 않는다 | 서비스 이름·SQL·예외·헤더·user_id 등 서버에서 온 문자열은 텍스트 노드로만 그린다(SEC-60) |
| 빈 화면은 다음 행동을 말한다 | 로딩·빈·에러 상태 문구에 "무엇을 하면 되는지"를 넣는다 |

## 2. 기술 스택과 폴더 구조

### 2.1 스택 결정

| 영역 | 선택 | 버전 기준 | 이유 |
|---|---|---|---|
| 프레임워크 | React 18+ (착수 시 안정 최신), TypeScript `strict` | | 함수 컴포넌트·훅만 사용. 클래스 컴포넌트 금지 |
| 빌드 | Vite, pnpm(workspace `ui`) | | 라우트 단위 코드 스플리팅, `vite-plugin-checker`(tsc·eslint) |
| 라우팅 | React Router(data router, `createBrowserRouter`) | | 로더로 URL 스키마 검증·초기 데이터 프리페치 |
| 서버 상태 | TanStack Query | | 캐시 키 = URL 상태. 실시간 토픽은 `setQueryData`로 병합 |
| 클라이언트 상태 | Zustand(일시 상태 전용: WS 연결 상태, 사이드바 접힘, 토스트) | | URL에 있는 것은 Zustand에 두지 않는다 |
| 컴포넌트 | Ant Design 5 (`ConfigProvider` + `csp={{ nonce }}`) | | 폼·모달·메뉴·관리 화면. 데이터 표는 자체 `DataTable`(TanStack Table) |
| 표 | TanStack Table + TanStack Virtual | | 정렬·열 필터·열 선택·값 클릭 필터·가상 스크롤을 한 컴포넌트로 |
| 시계열 차트 | uPlot | | 10만 점 시계열, 커서 동기화(`sync` 키), 플러그인으로 마커·음영·기준선 |
| 산점도 | 자체 Canvas 2D(`xlog/`) | | 10만 점 16ms, 드래그 선택·히트 테스트·실시간 스크롤은 범용 차트로 불가 |
| 트리·타임라인 | DOM + CSS(가상화) | | 1,000 step 트랜잭션도 60fps. 텍스트 선택·접근성 유지 |
| 스키마 검증 | zod | | URL 상태 복원·WS 프레임·API 응답(생성 클라이언트 보조) |
| API 클라이언트 | OpenAPI 생성(`openapi-typescript` + `openapi-fetch`) | | `fetch` 직접 호출 금지(03 문서 3.3) |
| i18n | i18next + react-i18next, `Intl` 포맷 | | en 원본, ko 번역 |
| 테스트 | Vitest + Testing Library, Playwright | | 03 문서 3.3 |
| 린트 | ESLint(`react/no-danger` error, `i18next/no-literal-string`, 자체 규칙), Prettier | | |
| 폰트·아이콘 | 번들 포함(가변 폰트 1종 + 아이콘 세트), 외부 로드 0 | | O-P03 |

### 2.2 폴더 구조

```
ui/
  index.html                      서버가 nonce를 주입해 서빙(16 문서). 외부 리소스 참조 0
  src/
    app/                          App, 라우터, Provider(Query·AntD ConfigProvider·i18n·Auth), 전역 단축키
    routes/                       라우트 트리 정의, 로더(URL 스키마 검증), 레이아웃(Shell·Kiosk·Auth)
    features/
      overview/                   전체 현황
      dashboard/                  앱 대시보드(+ 인스턴스 비교 표)
      xlog/                       XLog 화면(캔버스 컴포넌트는 ../xlog 모듈 사용), 선택 요약, 트랜잭션 목록
      transaction/                트랜잭션 상세(타임라인·호출 트리·SQL·외부·에러·STACK·비교)
      trace/                      트레이스 뷰
      active/                     액티브 트랜잭션
      stats/                      서비스·SQL·외부 호출·에러 통계 + 상세
      instance/                   인스턴스 목록·상세(지표·환경·명령·덤프 뷰어)
      alerts/                     규칙·채널·이력·통계
      admin/                      앱·인스턴스·승인 대기·토큰·설정 변경·킬 스위치·사용자·감사·개인정보·라이선스·정보
      auth/                       로그인, 설치 마법사, 비밀번호 변경
      search/                     전역 검색(ID 조회·텍스트)
    components/                   공통 UI: DataTable, StatTile, StatusBadge, TimeRangePicker, FilterChipBar,
                                  SafeText, SafeLink, CodeBlock, EmptyState, ErrorState, KindFilter, EnvSelect, AppSelect
    charts/                       TimeSeriesChart(uPlot 래퍼), 플러그인(markers, gapShading, baseline, clickMenu, cursorSync)
    xlog/                         XLogCanvas, XLogBuffer(TypedArray), renderer, hitIndex, selectionSummary
    realtime/                     WsManager, topics, 병합 리듀서, 연결 상태 스토어
    api/                          생성 클라이언트(generated/), 래퍼(csrf·401·에러 정규화), 쿼리 키 팩토리, zod 응답 보조
    state/                        urlState(스키마·파서·직렬화·훅), timeRange, uiStore(Zustand)
    i18n/                         설정, locales/en/*.json(원본), locales/ko/*.json, 용어표 적용 가이드
    styles/                       tokens.css(디자인 토큰), antd-theme.ts(토큰 매핑), global.css
    utils/                        format(숫자·시간·바이트·기간), ids(tx_id → ts 복원), csv(요청만), shortcuts
  e2e/                            Playwright: fixtures(데모 시드), specs/<screen>.spec.ts, hooks(xss·csp·console), screenshots/
  public/                         없음(모든 자산은 번들). favicon은 inline data
```

규칙: `features/<screen>` 안에서만 그 화면의 컴포넌트·훅을 정의한다. 두 화면 이상이 쓰면 `components/`로 올린다. `api/generated/`는 손으로 수정하지 않는다(CI가 재생성 diff 0 검사).

## 3. 정보 구조와 내비게이션

### 3.1 라우트 트리

| 경로 | 화면 | 역할 | 비고 |
|---|---|---|---|
| `/login` | 로그인 | 공개 | `?next=` 복귀 경로(같은 출처 경로만 허용) |
| `/setup` | 설치 마법사 | 공개(설정 토큰 필요) | 관리자 존재 시 404 |
| `/` → `/overview` | 전체 현황(F-U17) | 조회자+ | 로그인 후 기본 화면 |
| `/apps/:appId/dashboard` | 앱 대시보드(F-U01, F-U20) | 조회자+ | |
| `/apps/:appId/xlog` | XLog + 목록(F-U02) | 조회자+ | `sel=` 선택 영역 |
| `/apps/:appId/transactions/:txId` | 트랜잭션 상세(F-U02) | 조회자+ | `?ts=` 힌트 선택, `tab=` |
| `/traces/:traceId` | 트레이스 뷰(F-U03) | 조회자+ | `?ts=` 힌트(없으면 최근 24h) |
| `/apps/:appId/active` | 액티브 트랜잭션(F-U04) | 조회자+ | 항상 실시간 |
| `/apps/:appId/stats/services`, `/services/:serviceId` | 서비스 통계·상세(F-U05) | 조회자+ | |
| `/apps/:appId/stats/sql`, `/sql/:sqlId` | SQL 통계·상세(F-U06) | 조회자+ | |
| `/apps/:appId/stats/external`, `/external/:hostId` | 외부 호출 통계·상세(F-U05) | 조회자+ | |
| `/apps/:appId/stats/errors`, `/errors/:groupId` | 에러 분석·그룹 상세(F-U07) | 조회자+ | |
| `/apps/:appId/instances`, `/instances/:instanceId` | 인스턴스 목록·상세(F-U08) | 조회자+ (명령 탭은 운영자+) | `tab=metrics|env|commands|dumps` |
| `/apps/:appId/map` | 애플리케이션 맵(F-U11, S) | 조회자+ | |
| `/alerts` | 알림 이력(전 앱)(F-U09) | 조회자+ | 앱 선택 시 `?app=` 필터 |
| `/alerts/rules`, `/alerts/rules/:ruleId`, `/alerts/rules/new` | 규칙 목록·편집(F-U09) | 운영자+ | |
| `/alerts/channels` | 채널(F-U09) | 운영자(선택)·관리자(URL 변경) | |
| `/admin/apps`, `/admin/pending`, `/admin/instances` | 앱·승인 대기·인스턴스 관리(F-U10) | 운영자(조회)·관리자(변경) | |
| `/admin/tokens` | 토큰(F-U10) | 관리자 | |
| `/admin/config` | 설정 변경·킬 스위치(F-U10, F-S29) | 관리자 | |
| `/admin/users`, `/admin/audit`, `/admin/privacy` | 사용자·감사·개인정보(F-U10) | 관리자 | |
| `/admin/licenses`, `/admin/about` | 오픈소스 라이선스·버전 정보 | 조회자+ | |
| `/search` | 전역 검색(F-U14) | 조회자+ | `?q=`, `?type=` |
| `/account` | 내 계정(비밀번호 변경·세션) | 조회자+ | |

모든 `/apps/:appId/*` 라우트는 `AppLayout`(앱·환경 컨텍스트) 아래에 있고, `appId`가 없는 화면(전체 현황·전 앱 알림·관리·검색)은 `GlobalLayout` 아래에 있다. 둘 다 `Shell`(상단 바 + 좌측 메뉴)을 공유하며 `?kiosk=1`이면 `KioskLayout`(상단 바·메뉴 숨김, 자동 갱신 배지만)으로 바뀐다.

### 3.2 상단 바 (Shell 공통)

```
┌──────────────────────────────────────────────────────────────────────────────────┐
│ ◉ Argos APM │ [앱 ▾ order-api] [환경 ▾ prod] │ ⏱ [◀ 15m ▸] [지금] [● 실시간] │ [요청 ▾] │ 🔍 검색(⌘K) │ 🔔 3 │ 👤 ▾ │
└──────────────────────────────────────────────────────────────────────────────────┘
```

| 요소 | 동작 |
|---|---|
| 앱 선택 | 앱 라우트에서만 활성. 선택 시 같은 화면·같은 시간 범위로 `appId` 교체. 환경별 그룹 표시 |
| 환경 선택 | 전체 현황·전 앱 알림에서는 필터(`?env=`), 앱 라우트에서는 앱의 env를 표시(변경 = 같은 이름의 다른 env 앱으로 이동) |
| 시간 범위 선택기 | 5장. 프리셋·절대·이전·다음·확대·축소·지금 |
| 실시간 토글 | `live=1`. 과거 범위를 드래그하면 자동 해제되고 "실시간 재개" 버튼이 선택기 옆에 나타난다 |
| 종류 필터 | 요청(기본)/배치/메시지. 전역 `kind=`(D-20). 배치가 하나도 없는 앱은 비활성 표시 |
| 전역 검색 | `⌘K`/`Ctrl+K`. ID 조회(tx·trace·user·request id) 즉시 이동, 텍스트(서비스명·SQL)는 결과 목록 |
| 발생 중 알림 | 전 앱 FIRING 수. 클릭 → `/alerts?state=firing` |
| 계정 | 역할 표시, 내 계정, 언어(en/ko), 로그아웃 |

### 3.3 좌측 메뉴

앱 라우트: 대시보드, XLog, 액티브, 통계(서비스·SQL·외부 호출·에러), 인스턴스, 맵(S). 전역: 전체 현황, 알림(이력·규칙·채널), 관리(역할에 따라 항목 노출), 정보. 접힘 상태는 `localStorage`(일시 상태, 실패해도 무시).

### 3.4 상황판 모드 (F-U21)

`?kiosk=1`: 상단 바·메뉴 숨김, 우측 상단에 "실시간 · 마지막 갱신 N초 전" 배지와 환경·앱 이름만. 전체 현황·대시보드·액티브에서 지원. 자동 갱신은 실시간 토픽 + 5초 폴링 보충. 세션 만료는 서버 정책(SEC-42)을 따르며 UI는 만료 10분 전 배지로 경고하고 만료 시 로그인 화면이 아니라 "세션 만료, 재로그인 필요" 전체 화면 안내(상황판 TV에 로그인 폼이 뜨지 않게)로 바꾼다.

## 4. 전역 상태 모델과 URL 스키마

### 4.1 상태 분류

| 분류 | 예 | 저장 위치 |
|---|---|---|
| 공유 상태 | appId, env, 시간 범위, live, kind, 필터, 정렬, 선택 영역, 탭, 색 기준, y축, 비교 기준선, 검색어 | **URL**(경로 + 쿼리) |
| 서버 상태 | 조회 응답, 실시간 병합 데이터 | TanStack Query 캐시(키 = URL 상태) |
| 일시 상태 | WS 연결 상태, 마지막 메시지 시각, 사이드바 접힘, 토스트, 모달 열림, 포커스 행 | Zustand / 컴포넌트 로컬 |
| 사용자 선호 | 언어, 열 선택·너비, 접힘 | `localStorage`(try/catch, 실패 시 기본값). 공유되지 않는다 |

### 4.2 URL 쿼리 스키마 (zod)

```ts
// state/urlState.ts
export const RelativeRange = z.enum(['5m','15m','1h','6h','24h','7d','30d','today','yesterday']);
export const TimeRangeQ = z.union([
  z.object({ range: RelativeRange }),                                  // 상대(기본 15m)
  z.object({ from: z.coerce.number().int().positive(), to: z.coerce.number().int().positive() }) // epoch ms, from < to
]);
export const Kind = z.enum(['http','batch','message']).default('http');
export const FilterOp = z.enum(['eq','ne','gt','gte','lt','lte','contains','in']);
export const Filter = z.object({
  field: z.string().regex(/^[a-z_][a-z0-9_]{0,31}$/),
  op: FilterOp,
  value: z.string().max(1024)                                          // SEC-64: 자유 텍스트 1KB 상한
});
export const CommonQ = z.object({
  env: z.string().max(64).optional(),
  live: z.literal('1').optional(),
  kind: Kind,
  f: z.array(z.string().max(1100)).max(20).default([]),                // 'field:op:value' 인코딩, 최대 20개
  kiosk: z.literal('1').optional(),
  sort: z.string().regex(/^-?[a-z_][a-z0-9_.]{0,63}$/).optional(),
  cols: z.string().max(512).optional(),
}).merge(TimeRangeQ.optional().default({ range: '15m' }));
// 화면별 확장
export const XLogQ = CommonQ.extend({
  sel: z.string().regex(/^\d+,\d+,\d+,\d+$/).optional(),               // t1,t2(ms),y1,y2(ms)
  color: z.enum(['status','instance','service']).default('status'),
  y: z.enum(['lin','log']).default('lin'),
});
export const TxDetailQ = z.object({ ts: z.coerce.number().int().optional(), tab: z.enum(['timeline','tree','sql','ext','error','stack','headers']).default('timeline'), compare: z.string().regex(/^\d{1,20}$/).optional() });
export const DashboardQ = CommonQ.extend({ baseline: z.enum(['none','1d','7d']).default('none'), tileRef: z.enum(['prev','7d']).default('prev') });
export const AlertsQ = CommonQ.extend({ state: z.enum(['firing','acked','resolved','nodata','all']).default('all'), severity: z.enum(['warn','critical','all']).default('all'), failedOnly: z.literal('1').optional(), app: z.coerce.number().int().optional() });
```

규칙

- 라우트 로더가 쿼리를 파싱한다. 검증 실패 항목은 **기본값으로 복원**하고 상단에 1회 안내("링크의 일부 조건을 적용하지 못해 기본값으로 열었습니다")를 띄운다(SEC-64). 전체 URL 길이 상한 8KB.
- 필터 인코딩 `f=service:eq:GET%20%2Fapi%2Forders%2F%7Bid%7D`. 값은 `encodeURIComponent`. 복원 시 zod `Filter`로 재검증.
- 상태 변경은 `useUrlState()` 훅의 `set(partial, { replace })`로만 한다. 시간 범위·live 변경은 `replace: true`(히스토리 오염 방지), 화면·필터·선택 변경은 push.
- URL → 쿼리 키: `['xlog', appId, from, to, kind, filters, sel]`처럼 정규화된 값으로 만든다. 상대 범위는 로더에서 절대값으로 해석하되 캐시 키에는 "해석 시각을 10초 단위로 내림한 절대값"을 써서 폴링 재사용을 가능하게 한다.

### 4.3 딥링크 수락 기준 구현 (05 문서 2.0)

| 기준 | 구현 |
|---|---|
| 1. 로그인 복귀 | 401 → `/login?next=<현재 경로+쿼리>`. `next`는 같은 출처의 경로만 허용(`^/(?!/)`), 길이 8KB 이하. 로그인 성공 시 `next`로 이동 |
| 2. 고정 범위 | 알림 딥링크는 서버가 `from/to` 절대값(발화 ±15분)으로 생성하고 `live`를 넣지 않는다. UI는 절대 범위면 실시간 토글을 꺼진 상태로 렌더 |
| 3. 대상 필터 | 딥링크의 `f=` 필터(인스턴스·서비스·호스트)와 `env`를 그대로 적용. 필터 칩으로 보이고 제거 가능 |
| 4. 마커 | `alert=<eventId>` 쿼리가 있으면 해당 알림 이벤트의 상태 전이를 차트 마커로 강조하고 상단에 알림 요약 배너 |
| 5. 보존 기간 | 절대 범위이므로 보존 안이면 같은 데이터. 원본 보존(30일) 밖이면 롤업 소스 표시와 "원본 보존 기간 경과" 안내(D-03) |

## 5. 시간 범위 모델

```ts
type TimeRange = { kind: 'relative', range: RelativeRange } | { kind: 'absolute', from: number, to: number };
// 해석: relative → [now - d, now], today → [자정, now], yesterday → [어제 자정, 오늘 자정]
```

| 동작 | 규칙 | 단축키 |
|---|---|---|
| 프리셋 선택 | relative로 설정, `live` 유지 | `t` 후 숫자 |
| 절대 선택 | 달력·시각 입력, `live` 해제 | |
| 이전·다음 | 범위 길이만큼 이동, absolute로 전환, `live` 해제 | `[` / `]` |
| 확대 | 중심 유지 길이 1/2(최소 1분) | `-`(zoom in = `+`?) → `+` 확대, `-` 축소 |
| 축소 | 길이 2배(최대 30일) | `-` |
| 지금으로 | 같은 길이의 relative로 복귀, 실시간 가능 화면이면 `live=1` 복원 여부는 사용자가 마지막에 켠 상태 기억(localStorage) | `n` |
| 차트 드래그 확대 | 드래그 구간을 absolute 범위로 전역 설정(`replace`) | |
| XLog 드래그 | 전역 시간 범위는 바꾸지 않고 `sel=`만 설정(선택은 범위 안의 부분 영역) | |
| 실시간 중 과거 드래그 | `live` 해제 + "실시간 재개" 버튼 표시. 재개 시 relative 15m + live 복원, 공백은 REST 재조회 | `l` 토글 |
| 앱·화면 전환 | 시간 범위·kind·env·live 유지, 화면 전용 키(sel·tab·color)는 초기화 | |

실시간 모드의 데이터 흐름: 초기 REST 조회(범위 전체) → WS 구독 → 100ms 묶음 수신 → 캐시에 append → 범위 창을 1초마다 `now` 기준으로 이동(캔버스는 프레임마다 translate). 10초 이상 메시지가 없으면 "갱신 중단 N초" 배지. 연결 복구 시 `lastReceivedTs ~ now` 구간을 REST로 보충한 뒤 중복 제거(tx_id 기준).

## 6. 공통 컴포넌트 사양

### 6.1 TimeSeriesChart (uPlot 래퍼)

| 항목 | 사양 |
|---|---|
| 입력 | `series: {key, label, unit, data: [ts[], values[]], kind: 'line'|'area'|'bar', color?}[]`, `baseline?: {offset:'1d'|'7d', data}`, `markers: Event[]`, `gaps: [from,to][]`, `syncKey: string`, `onZoom(from,to)`, `onPointClick(ts)` |
| 커서 동기화 | 같은 화면의 차트는 `uPlot.sync(syncKey)`로 커서·호버 공유 |
| 드래그 확대 | uPlot `select` → `onZoom` → 전역 시간 범위 absolute 설정 |
| 범례 | 클릭 토글(시리즈 숨김), Shift+클릭 단독 표시. 상태는 컴포넌트 로컬 |
| 비교 기준선 | 같은 시리즈를 offset만큼 당긴 데이터를 점선(dash `[4,4]`, 투명도 0.6)으로 오버레이. 범례에 "7일 전" 접미 |
| 이벤트 마커 | 플러그인 `markers`: x 위치에 세로선 + 상단 아이콘. 모양: 배포 ▲, 기동 ●, 종료 ○, 설정 변경 ◆, 알림 전이 ✱(심각도 색). 호버 툴팁에 제목·버전·시각. 겹치면 묶음 "+3" |
| 수집 중단 음영 | 플러그인 `gapShading`: `gaps` 구간을 회색 빗금 사각형으로. 범례 하단 "수집 중단 N건" |
| 결측 | `null` 값은 선을 끊는다(0으로 그리지 않음, D-09·D-16). `source: '1h'`면 x축 아래 "1시간 집계" 표기 |
| 점 클릭 | 가장 가까운 점 클릭 → 메뉴: "이 시각 XLog 보기"(±범위/60, 최소 1분), "관련 목록"(화면별) |
| 툴팁 | 시각(로컬), 시리즈별 값·단위, 기준선 값과 차이(%). 수집 데이터 문자열은 SafeText |
| 접근성 | 캔버스 아래 시각적으로 숨긴 `<table>`에 다운샘플된 값(≤ 100행) 제공 |

### 6.2 StatTile (요약 지표)

값, 단위, 증감(기준: 직전 구간 또는 7일 전 같은 시간, 전역 `tileRef`), 증감 색(좋음/나쁨은 지표별: 에러율·p95는 증가가 나쁨), 스파크라인(선택), 클릭 → 관련 차트로 스크롤. 결측은 "–"와 툴팁 "데이터 없음".

### 6.3 DataTable

| 기능 | 사양 |
|---|---|
| 기반 | TanStack Table(헤드리스) + TanStack Virtual(100행 초과 시 가상화). 열 정의는 화면별 `columns.ts` |
| 정렬 | 서버 정렬(`sort=-p95`)이 기본. 클라이언트 정렬은 응답이 전체일 때만 |
| 열 필터 | 열 헤더 필터 아이콘 → 텍스트/범위/선택. 필터는 URL `f=`로 |
| 값 클릭 필터 | 셀 우클릭 또는 호버 시 나타나는 ⊕/⊖ → "이 값으로 필터" / "이 값 제외". 필터 가능 열은 열 정의에 `filterable: true` |
| 열 선택·너비 | 헤더 메뉴. 선택은 URL `cols=`(공유), 너비는 localStorage |
| 변화율 열 | 열 정의 `delta: { ref: 'prev' }` → 값 옆에 ▲12% 표시(05 문서 2.5) |
| 키보드 | 위·아래 행 이동, Enter 상세, `/` 열 필터 포커스, `Esc` 선택 해제 |
| CSV | "CSV" 버튼 → **서버 내보내기 API** 호출(감사 로그 SEC-80·수식 주입 방지 SEC-63는 서버 책임) → 다운로드. 클라이언트는 CSV를 만들지 않는다 |
| 빈·로딩·에러 | 7장 상태 규칙 |
| 셀 렌더 | 수집 데이터 문자열은 모두 `SafeText`. 링크 열은 `SafeLink` |

### 6.4 StatusBadge

| 상태 | 색 토큰 | 표시 |
|---|---|---|
| UP | `--status-up` 초록 | ● UP |
| STALE | `--status-stale` 노랑 | ● STALE(툴팁: heartbeat N초 미수신) |
| DOWN | `--status-down` 빨강 | ● DOWN |
| WARN | 테두리 `--status-warn` 주황 | 기본 상태 색 + 주황 테두리 + 툴팁(시계 차·드롭·구버전·모듈 비활성 중 사유) |

알림 상태 라벨은 부록 B 용어표(정상/대기/발생/확인/해제/데이터 없음).

### 6.5 TimeRangePicker, FilterChipBar, KindFilter, EnvSelect, AppSelect

- TimeRangePicker: 5장 규칙 구현. 프리셋 드롭다운 + 절대 입력(로컬 시간대, UTC 오프셋 표시) + 이전/다음/확대/축소/지금 버튼 + 실시간 토글.
- FilterChipBar: 적용된 `f=` 필터를 칩으로. 칩 클릭 → 편집, × → 제거, "모두 지우기". 칩 텍스트는 SafeText.
- KindFilter: 요청/배치/메시지 세그먼트. 배치 데이터가 없는 앱은 비활성 + 툴팁.
- EnvSelect·AppSelect: 검색 가능 드롭다운, 최근 사용 상단, 환경별 그룹.

### 6.6 SafeText, SafeLink, CodeBlock

```tsx
// 수집 데이터 문자열은 이 두 컴포넌트로만 그린다. ESLint 자체 규칙 `argos/untrusted-text`가
// 서버 응답 타입(Untrusted<string> 브랜드 타입)을 JSX 자식·속성에 직접 쓰면 에러를 낸다.
export function SafeText({ value, maxLines = 1, mono = false }: { value: string; maxLines?: number; mono?: boolean }) {
  return <span className={cx('safe-text', mono && 'mono')} style={{ WebkitLineClamp: maxLines }} title={value}>{value}</span>;
}
export function SafeLink({ href, children }: { href: string; children: React.ReactNode }) {
  const ok = /^(https?:\/\/|\/(?!\/))/i.test(href);            // http(s) 또는 내부 경로만. javascript:·data: 차단
  return ok ? <a href={href} rel="noopener noreferrer" target={href.startsWith('/') ? undefined : '_blank'}>{children}</a> : <span>{children}</span>;
}
```

CodeBlock: SQL·스택·설정 스니펫. 하이라이트는 토크나이저 결과를 React 요소로 조립(HTML 문자열 금지). "복사" 버튼. 긴 내용은 접기.

### 6.7 EmptyState·ErrorState 문구 규칙

| 상태 | 구성 |
|---|---|
| 로딩 | 스켈레톤(표·차트 모양 유지). 2초 넘으면 "조회 중… (범위를 좁히면 빨라집니다)" |
| 빈 | 아이콘 + 한 줄 사실 + 한 줄 다음 행동 + 버튼. 예: "선택 기간에 트랜잭션이 없습니다. 기간을 넓히거나 필터·종류를 확인하세요. [기간 넓히기] [필터 지우기]". 제외 요청이 있으면 "이 기간에 제외된 요청 N건" 링크(D-19) |
| 에러 | 요청 id·상태 코드와 재시도 버튼. 403은 "권한이 없습니다. 관리자에게 역할을 요청하세요". 429는 `Retry-After` 반영 |
| 부분 | 샘플링("표시 N / 전체 M"), 롤업 소스("1분 집계 데이터"), 원본 보존 경과, 프로파일 미수집 사유(샘플링/유실)를 데이터 위에 띠로 표시 |

## 7. XLog 캔버스 아키텍처

### 7.1 데이터 구조 (`xlog/XLogBuffer.ts`)

```ts
export class XLogBuffer {
  // 열 저장(Struct of Arrays). capacity 기본 150_000, 실시간은 링 버퍼
  ts: Float64Array;        // epoch ms
  elapsed: Uint32Array;    // ms
  status: Uint8Array;      // 0 normal, 1 slow, 2 error
  instance: Uint16Array;   // 인스턴스 인덱스(로컬 사전)
  service: Uint32Array;    // service_id 로컬 인덱스
  txIdLo: Uint32Array; txIdHi: Uint32Array; // tx_id 64비트
  kind: Uint8Array;        // 1 http, 2 batch, 3 message
  size: number; head: number;             // 링 버퍼
  append(batch: XLogPoint[]): void;       // 실시간 100ms 묶음
  evictBefore(ts: number): void;          // 실시간 창 밖 제거
}
```

서버 응답은 바이너리(`application/x-argos-xlog`, 14 문서에 요구) 또는 열 지향 JSON `{ts:number[], elapsed:number[], ...}`로 받아 TypedArray에 바로 복사한다. 행 단위 객체 배열은 쓰지 않는다(GC 비용).

### 7.2 렌더러 (`xlog/renderer.ts`)

| 항목 | 설계 |
|---|---|
| 캔버스 | 두 겹: `points`(점) + `overlay`(선택 사각형·호버·임계선·마커·음영). DPR 적용(`canvas.width = css * dpr`, `ctx.scale`) |
| 좌표 | x = (ts − from) / (to − from) × W, y = 선형 또는 log10(elapsed + 1) 스케일. y 상한은 자동(p99.9 × 1.2) 또는 고정 |
| 그리기 | 색별 3패스: 각 패스에서 `ctx.beginPath()` 후 해당 색 점을 `rect(x, y, 2, 2)`로 누적, `fill()` 1회. 10만 점 ≈ 3 fill → 측정 목표 8ms 이하. 상태 색 기준이 아닐 때(인스턴스·서비스)는 팔레트 12색 + 기타 회색, 색별 패스 ≤ 13 |
| 프레임 | `requestAnimationFrame` 루프는 변경(데이터·범위·크기·호버) 플래그가 있을 때만 그린다. 실시간 모드는 초당 10프레임(100ms 묶음 도착마다) + 1초마다 창 이동 |
| 오프스크린 | 점 레이어는 데이터·범위·크기 변경 시에만 재그리기. 호버·선택은 overlay만 |
| 임계선 | 지연 임계(서비스별 설정, 앱 기본 1,000ms)를 가로 점선. Apdex T·4T도 옵션 |
| 마커·음영 | 이벤트 마커(6.1과 같은 모양) 상단, 수집 중단 음영(D-18) |
| 샘플링 표기 | 응답 `shown/total`이 다르면 좌상단 "표시 100,000 / 전체 342,118" |
| 배치 단위 | `kind=batch`면 y축 라벨 단위 자동(ms → s → min) |
| 리사이즈 | `ResizeObserver` → 캔버스 크기 재설정 → 재그리기 |

### 7.3 히트 테스트 (`xlog/hitIndex.ts`)

렌더 후 화면 좌표 기준 8×8px 격자에 점 인덱스를 버킷팅(`Map<bucketKey, Int32Array>` 대신 `Uint32Array` 오프셋 테이블). 호버는 커서 버킷과 인접 8개에서 최근접 점. 드래그 선택은 사각형이 덮는 버킷 범위를 순회해 조건(`t1≤ts≤t2 && y1≤elapsed≤y2`) 확인. 10만 점 선택 계산 ≤ 5ms.

### 7.4 선택 영역 요약 (`xlog/selectionSummary.ts`)

클라이언트 계산(모든 점이 메모리에 있음): 선택 점 수, 서비스별 상위 10(건수·평균·최대·에러 수), 인스턴스별 분포, HTTP 상태별 분포, 에러 클래스별 분포(에러 클래스 id가 점에 포함될 때만; 없으면 "목록에서 확인"). 서비스·인스턴스 이름은 로컬 사전(응답에 포함)으로 해석. 샘플링된 응답이면 요약 상단에 "표시된 점 기준" 표기. 요약 행 클릭 → 목록 필터(`f=service:eq:…`).

트랜잭션 목록은 `sel`을 서버 검색 API(`transactions/search`, 영역 + 필터, ≤ 1,000행, 응답시간 내림차순)로 조회한다. 선택 점이 1,000개 넘으면 "상위 1,000건" 표기.

### 7.5 실시간

`live=1`: WS 토픽 `xlog` 구독 → 100ms 묶음 → `buffer.append` → 5분 창 밖 `evictBefore`. 창은 1초마다 `[now-5m, now]`로 이동(점 레이어는 translate만, 재그리기 없이 캔버스 `drawImage` 시프트 + 새 점만 그리기). 일시정지 버튼은 창 이동만 멈춘다(데이터는 계속 수신). 60초 이상 늦은 점은 서버가 실시간에 보내지 않으므로(D-15) 재조회 시에만 보인다.

## 8. 트랜잭션 상세 렌더링

### 8.1 데이터 모델 (14 문서 응답 기준)

```ts
type Step = { seq: number; parentSeq: number; type: 'SQL'|'DBC'|'COMMIT'|'ROLLBACK'|'HTTP'|'METHOD'|'ASYNC'|'ERROR'|'STACK'|'MESSAGE';
  startOffsetMs: number; elapsedMs: number; name: string /*사전 해석됨*/; nameId: string; target?: string; rows?: number;
  status?: number; error?: boolean; detail?: string; stack?: string[] /*프레임 사전 해석됨*/; failKind?: 'TIMEOUT'|'CONNECT_FAIL'|'HTTP_5XX'|'OTHER' };
```

### 8.2 트리 구성과 반복 접기

```
buildTree(steps): seq→node, parentSeq로 연결, 자식은 startOffset 순 정렬
collapseRepeats(children):
  key(step) = type + nameId + target
  연속 형제 중 key가 같은 run을 찾아 길이 ≥ 3이면 GroupNode{ count, sumMs, avgMs, maxMs, first, last, children } 로 치환
  GroupNode는 기본 접힘. 펼치면 개별 step(가상화). 배치 트랜잭션의 청크 반복도 같은 규칙
```

### 8.3 Self time

`self(node) = node.elapsed − Σ clip(child.interval, node.interval)`(겹치는 비동기 자식은 합집합 길이로). 루트(트랜잭션)는 `tx.elapsed − 최상위 step 합집합 길이`. 타임라인에서는 자식 사이 공백을 회색 세그먼트로 그린다. `Σ self time ≥ 20% × tx.elapsed`면 상단 요약에 "계측되지 않은 시간 N ms(M%)" 강조.

### 8.4 타임라인(워터폴)

DOM 가상 리스트(행 높이 24px). 행 = [들여쓰기 + 타입 아이콘 + 이름(SafeText, 말줄임)] | [막대 영역: `left = start/total%`, `width = elapsed/total%`, 최소 2px]. 막대 색은 타입별 토큰(SQL 파랑, DBC 하늘, HTTP 보라, METHOD 회청, ASYNC 점선, ERROR 빨강, STACK 아이콘). 상단 눈금(0 ~ total ms). 호버 → 툴팁(시간·비율·상세). 클릭 → 우측 패널(SQL 원문·파라미터·외부 URL·에러 스택). 비교 모드(`compare=<txId>`)는 같은 x 스케일(둘 중 긴 쪽) 또는 각자 100%를 토글.

### 8.5 STACK 뷰

연속 캡처 N개를 열로 나란히(각 열은 프레임 목록, 최상위가 위). 모든 캡처에 공통인 프레임을 강조색으로, 가장 깊은 공통 프레임을 "멈춰 있던 지점"으로 표시. 앱 패키지 프레임(설정 `app-packages`)은 굵게. 프레임은 SafeText(mono).

### 8.6 탭 구성

timeline(기본) · tree · sql(대기/실행 합계 분리, 풀 이름, 정규화·원문·파라미터(모드·권한 안내)) · ext(실패 분류 배지, 호스트 통계·트레이스 링크) · error(클래스·마스킹 메시지·스택, 에러 그룹 링크) · stack · headers(화이트리스트 헤더·요청 ID). 상단 요약 바는 05 문서 2.2 첫 항목 전부 + "같은 서비스 p50/p95와 이 요청의 백분위 위치" + "정상 요청 1건 나란히 보기" + "호출자로 이동" + "로그 열기" + "JSON 내보내기" + "링크 복사".

## 9. 화면별 사양

각 절은 05 문서 2.x와 1:1이며 끝의 체크표가 그 절의 필수 요소를 컴포넌트에 대응시킨다. 데이터 소스의 엔드포인트 이름은 14 문서에서 확정한다(19장에 요구).

### 9.1 전체 현황 `/overview` (05 §2.10)

```
┌ 환경 [prod ▾] 그룹 [전체 ▾]  카드|표  상황판 ▢ ┐  ┌ 발생 중 알림 ──────────┐ ┌ 수집 서버 ─────┐
│ 상태 앱            인스턴스 TPS  p95   에러율  액티브 배치 알림      최근 배포    1h 추이 │  │ 심각 2 · 경고 5         │ │ 적재 지연 1.2s   │
│ ●  order-api       8/8    412  380ms 0.3%   37     0   ⚠1          v2.3.1 2h전  ▁▂▃▅▃▂▁ │  │ order-api p95 … [보기]  │ │ 디스크 61%       │
│ ●  payment-api     3/4    88   1.2s  2.1%   12     1   ✖1 ⚠2       v1.9.0 1d전  ▁▁▂▇▆▃▂ │  │ …                       │ │ 채널 실패 0      │
└──────────────────────────────────────────────────────────────────────────────────────┘  └─────────────────────────┘ └──────────────────┘
```

- 데이터: 전체 현황 API(앱별 요약 + 스파크라인 1h) 10초 폴링 + WS `overview` 토픽(상태·알림 변경). 발생 중 알림 API. 서버 자기 상태 API.
- 상호작용: 행 클릭 → 대시보드(시간 범위 유지). 정렬 상태·에러율·p95·알림. 이상 앱 상단 고정(상태 DOWN/WARN 또는 FIRING). 카드/표 전환은 localStorage. 알림 패널 클릭 → `/alerts?state=firing`.
- 1366×768에서 표 모드 30행이 스크롤 없이 보이도록 행 높이 20px·헤더 28px·패널 높이 계산. E2E가 측정.
- 체크표

| 05 §2.10 항목 | 구현 |
|---|---|
| 기본 화면, 환경·그룹 필터, 카드/표, 상황판 | 라우트 `/`, EnvSelect·GroupSelect, 토글, `?kiosk=1` |
| 앱 행 필수 항목 10종 | `OverviewTable` 열 정의(status, instances, tps, p95, errorRate, active, batches, alerts, lastDeploy, sparkline) |
| 정렬·상단 고정 | DataTable 정렬 + `pinAbnormal` |
| 전 앱 FIRING 요약, 서버 자기 상태 | `FiringPanel`, `ServerHealthPanel` |
| 클릭 → 대시보드(시간 유지) | `useUrlState` 경로 변경 시 공통 쿼리 보존 |
| 30개 앱 스크롤 없음 | 레이아웃 규칙 + E2E `overview.spec` |

### 9.2 앱 대시보드 `/apps/:appId/dashboard` (05 §2.3)

```
┌ TPS 412 ▲3% │ 평균 120ms ▼2% │ p95 380ms ▲8% │ p99 1.1s │ 에러율 0.3% │ 액티브 37 ┐
│ [TPS 차트]            [응답시간 avg·p95·p99 + 기준선]        [에러율]            │
│ [액티브 수]           [힙 사용 / GC 정지 시간]                 [CPU 프로세스·시스템] │
│ [Tomcat 스레드 사용률] [풀 사용률(풀별)]                      [외부 호출 시간·에러] │
├ 인스턴스 타일 ●●●●●●●○  ─────────────────────────────────────────────────────────┤
│ 인스턴스 비교 표: 이름 상태 TPS p95 에러율 액티브 힙 CPU 풀 업타임 (이상치 강조)      │
├ 발생 중 알림(이 앱·환경) │ 수집 상태: 에이전트 유실 0 · 서버 유실 0 · 적재 지연 1.2s · 시간 오차 ⚠1 · 제외 1,204건 · 늦은 도착 0 · 배치 진행 0 │
```

- 데이터: 대시보드 시계열 API(`source` 포함, p99 포함), 인스턴스 요약 API, 발생 중 알림, 수집 상태, 이벤트(마커), 수집 중단 구간. 실시간: WS `counter` 토픽(5초) + `xlog` 미사용. 기준선은 같은 API에 `baseline=7d`.
- 상호작용: 모든 차트 커서 동기화(`syncKey='dashboard'`), 드래그 확대 전역, 점 클릭 → XLog. 인스턴스 타일 클릭 → 인스턴스 상세. 비교 표 정렬·이상치(앱 평균 대비 p95 ×2 또는 에러율 ×3) 강조. 타일 증감 기준 `tileRef`.
- 체크표: 05 §2.3 8개 항목 → StatTile×6(`tileRef`), TimeSeriesChart×9(`baseline`,`markers`,`gaps`), InstanceTiles(StatusBadge), InstanceCompareTable(F-U20), FiringPanel, IngestStatusPanel(D-06·D-18·D-19 필드), live 토글.

### 9.3 XLog `/apps/:appId/xlog` (05 §2.1)

```
┌ 색 기준 [상태▾] y축 [선형|로그] 지연 임계 1,000ms  표시 100,000 / 전체 342,118  [실시간 ●] [⏸] ┐
│  ▲ elapsed                                                                                   │
│  │        ·    ·  ·          · ·  ✱                                                          │
│  │  · · ·  ·  ┌──────────┐ ·    ·                                                             │
│  │ ·········· │ 선택 영역 │···············                                                   │
│  └────────────┴──────────┴──────────────────────────────▶ time                               │
├ 선택 요약: 1,284점 │ 서비스 상위10 │ 인스턴스 분포 │ 상태 분포 │ 에러 클래스 ┤ 트랜잭션 목록(≤1,000) ┤
```

- 데이터: XLog 점 API(바이너리·열 지향, `shown/total`, 로컬 사전), 선택 시 검색 API, 이벤트·중단 구간. 실시간 WS `xlog`.
- 상호작용: 드래그 → `sel=` → 요약 + 목록. 요약 행 클릭 → 목록 필터. 점 호버 툴팁, 클릭 → 상세. 색 기준·y축은 URL. 목록 키보드(↑↓ Enter), 상세에서 이전·다음(목록 순서 유지는 `sel`+정렬로 재조회). 목록 열 선택·정렬·CSV(서버).
- 체크표: 05 §2.1 11개 항목 → XLogCanvas(축·색·임계선·호버·샘플링·배치 단위·음영·마커), SelectionSummary, TxListTable(필수 열 13개), 키보드, 실시간 모드(일시정지·보충).

### 9.4 트랜잭션 상세 `/apps/:appId/transactions/:txId` (05 §2.2)

8장 설계. 데이터: 트랜잭션 상세 API(헤더·step·사전 해석·stack 프레임 해석·같은 서비스 p50/p95·백분위 위치·호출자·`profileStatus: 'captured'|'sampled_out'|'lost'`), 정상 요청 1건 API(같은 서비스·기간·정상 중 중앙값 근처). 체크표: 05 §2.2 11개 항목 → SummaryBar(필드 17개), PercentilePosition, CompareButton, Timeline(selfTime·stackIcon), TreeTab(collapse), SqlTab(대기/실행 분리·풀 이름·링크), ExtTab(failKind 배지·링크), ErrorTab(그룹 링크), StackView, ProfileStatusBanner(D-07), CallerLink, LogLink(F-U18), ExportJson, CopyLink.

### 9.5 트레이스 뷰 `/traces/:traceId` (05 §2.8)

트리(좌) + 타임라인(우), 노드 = 트랜잭션 또는 외부 호출. 노드 라벨: 앱(환경)·서비스·인스턴스·시간·상태·버전. 호출자 HTTP step과 피호출자 트랜잭션을 둘 다 그리고 차이를 "네트워크·대기" 회색 구간으로(D-05). 누락 노드 3종 구분(미계측 외부 노드 / "아직 수집되지 않음 [다시 조회]" / 프로파일 미수집 사유). 임계 경로 강조(가장 긴 경로를 굵은 테두리). 노드 클릭 → 상세, 앱 노드 → 대시보드(같은 시간). trace id 복사, 로그 열기, JSON 내보내기. 데이터: 트레이스 API(`?ts` 힌트, 없으면 24h, 트랜잭션 ≤ 500, `missing` 표기).

### 9.6 액티브 트랜잭션 `/apps/:appId/active` (05 §2.6)

```
┌ 진행 중 142 · 8초 이상 7 · 인스턴스별 최대 31 │ [⏸] N초 전 기준 │ [알림 규칙] ┐
│ 인스턴스별 막대: order-1 ████████▌ 31   order-2 █████ 18 (색: <1s 초록 1~3s 노랑 3~8s 주황 ≥8s 빨강) │
├ 목록: 인스턴스 서비스 경과 상태(SQL 실행 중: SELECT … / 외부 호출 중: pg.example.com / 커넥션 대기: HikariPool-1 / 메서드) 스레드 사용자 IP 종류 시작 │
│ 행 선택 → [이 스레드 스택] [인스턴스 스레드 덤프] [같은 서비스 최근 완료] │
```

항상 실시간(WS `active` 토픽, 5초 스냅샷). 막대 클릭 → 인스턴스 필터. 기본 정렬 경과 내림차순. 필터 인스턴스·서비스·종류·상태. 끊김 배지. 체크표: 05 §2.6 7개 항목 → InstanceBars(4단계 색, 설정 가능), ActiveTable(필수 열 9개), 정렬·갱신·일시정지·필터, RowActions(단일 스레드 스택 명령 → 결과 모달), SummaryBar, WsStaleBadge, "N초 전 기준".

### 9.7 통계: 서비스·SQL·외부 호출·에러 (05 §2.5)

공통: DataTable Top N(정렬 전환 세그먼트: 총 시간·횟수·평균·p95·에러), 값 클릭 필터, 변화율 열, 종류·환경 필터, CSV. 상세는 우측 드로어 또는 라우트.

| 화면 | 상세 구성 |
|---|---|
| 서비스 | 추이 차트(count·avg·p95·p99·에러율, 기준선), 버전별 분리 표(`version` 열, 배포 마커), 인스턴스별 분포, 교차 뷰 탭(SQL Top N·외부 호출 Top N·에러 그룹 Top N), 관련 트랜잭션 → XLog(필터 적용) |
| SQL | 추이, 실행 서비스 목록(건수·비중), 실행 인스턴스 분포, DB(데이터소스)별 분리, 커넥션 대기 합계, 정규화 SQL·원문(CodeBlock), 관련 트랜잭션(배너: "프로파일 수집 트랜잭션만 표시") |
| 외부 호출 | 호스트(+경로 패턴)별 호출수·p50/p95·에러·타임아웃·연결 실패, 추이, 호출 서비스, 관련 트랜잭션, 맵 노드 링크 |
| 에러 | 그룹별 횟수·최초·최근, "신규" 필터(최초 발생이 선택 기간 내), 대표 스택(앱 프레임 굵게), 마스킹된 메시지 패턴, 영향 서비스, 버전별·인스턴스별 분포, 최근 트랜잭션 10건 |

데이터: 각 통계 API(`source` 표기, 백분위, 변화율은 `compare=prev` 옵션으로 서버가 계산), 상세 API.

### 9.8 인스턴스 상세 `/instances/:instanceId` (05 §2.7)

탭 `metrics`(기본)·`env`·`commands`·`dumps`. 상단 헤더는 05 §2.7 첫 항목 전부(StatusBadge, 메타 16개, IP 수집 모드·가상 스레드 모드 배지). metrics: TimeSeriesChart 11종(힙 영역별·GC 수집기별·스레드·클래스·CPU·FD·Tomcat 커넥터별·풀별·시스템 4종), 기준선·마커. env: JVM 옵션, 마스킹된 환경변수·시스템 프로퍼티(관리자만, 아니면 "관리자 권한 필요" 안내), 유효 설정 표(값·출처 파일/환경변수/서버 푸시), 원격 변경 허용 키, 모듈 상태 표, 자가 진단 결과. commands: 이력 표 + 실행 버튼(역할별 노출: 스레드 덤프·단일 스택·자가 진단은 운영자, 설정 리로드·환경 덤프는 관리자), 실행 확인 모달(영향 설명), 결과 폴링(명령 TTL 60초 → 만료 표시). dumps: 스레드 덤프 뷰어.

스레드 덤프 뷰어: 상태별 그룹(접기), 동일 스택 묶기(해시 기준, "×N"), 락 관계(보유 → 대기 화살표 목록, 데드락 배지), 가상 스레드 배지, 검색(스레드 이름·클래스·메서드), 진행 중 트랜잭션 매칭(스레드 → 트랜잭션 링크, 스냅샷 기준), 원문 다운로드(서버 API), 최근 20개 선택, 잘림 표시. 두 덤프 비교는 1.x(탭 자리만).

### 9.9 알림 `/alerts/*` (05 §2.4, §2.9)

- 이력(`/alerts`): 상단 고정 패널(발생·확인 목록, 심각도 건수, 채널 상태), DataTable(필수 열 12개: 시각·앱(환경)·대상·규칙·심각도·상태 전이·현재값/임계치·지속·전송 결과(채널별)·ACK 사용자…), 필터(기간·앱·환경·심각도·상태·규칙·전송 실패만), 행 동작(딥링크·규칙 편집·ACK 메모 모달·"이 대상 알림 일시 중지" 모달), 알림 통계 탭(규칙별 발화 수·평균 지속·ACK까지·해제까지, 7일·30일).
- 규칙 편집(`/alerts/rules/:id`): 05 §2.4 필드 전부를 가진 폼(AntD Form). 섹션: 대상·종류·지표 / 조건(평가 창, 지속, 단위, 경고·심각 임계치, 최소 표본, no-data, 복구 임계치·지속, 기동 유예) / 일정(활성 시간대, 반복 음소거) / 알림(심각도별 채널, 재알림, 런북, 템플릿 — 허용 변수만 자동완성, 금지 변수 입력 시 즉시 오류) / 미리보기(최근 7일 발화 차트·횟수·최대 지속, 변경 시 재계산 디바운스 500ms). "템플릿에서 만들기" 드로어(부록 A 목록 → 폼 채움, 달라진 필드 점 표시). 복제·비활성·변경 이력.
- 채널(`/alerts/channels`): 목록(종류·이름·마스킹된 대상·최근 전송 성공/실패), 편집(URL 재입력 방식, 목적지 검증 결과 즉시 표시: 금지 대역 오류 문구), 테스트 전송 버튼(결과 상태 코드만 표시).

### 9.10 관리 `/admin/*` (05 §2.11)

| 라우트 | 구성 |
|---|---|
| `/admin/apps` | 앱 표(이름·환경·그룹·인스턴스 수·토큰 수·등록 정책·권장 규칙·마지막 수신), 생성·편집 모달(Apdex T, 그룹, 등록 정책, 권장 규칙 적용) |
| `/admin/pending` | 승인 대기 표((name, env)·호스트·시도 시각·횟수), 승인(→ 앱 생성·토큰 발급 모달에 원문 1회 표시) / 거부 |
| `/admin/instances` | 인스턴스 표(상태·버전 경고·설정 해시 불일치 강조·heartbeat·드롭·시간 오차·모듈 비활성·IP 모드), 보관 목록 토글, 삭제 |
| `/admin/tokens` | 앱별 토큰(앞 8자·발급자·만료·마지막 사용·사용 인스턴스), 발급(만료일 기본 365일) → 원문 1회 모달("다시 볼 수 없음" + 복사), 회전(유예 7일 안내), 폐기(확인: 활성 세션 N개 즉시 종료 경고), 만료 임박 배지 |
| `/admin/config` | 앱 선택 → 현재 유효 설정(인스턴스별 drift 표시) → 변경 폼(원격 변경 허용 키만 편집 가능, 불허 키는 잠금 아이콘 + "로컬 yml에서만 변경") → 사유 입력(필수) → diff 미리보기 모달 → 적용 → 인스턴스별 결과 표(성공·실패·거부 키·미접속, 10초 내 갱신) → 변경 이력 → "이전 설정으로 되돌리기"(한 번의 확인). 킬 스위치 섹션(앱·인스턴스 단위, 영향 설명 모달, 결과·시각) |
| `/admin/users` | 사용자·역할 CRUD, 강제 세션 종료, 잠금 해제, 초기 비밀번호(강제 변경 플래그) |
| `/admin/audit` | 감사 로그 표(필터 기간·행위자·행위·대상), CSV(서버) |
| `/admin/privacy` | IP·user_id·쿼리스트링·파라미터 모드 현황(앱·인스턴스별 실제 적용값), 삭제 요청 폼(user_id 또는 IP + 기간) → 작업 상태 표 |
| `/admin/licenses` | 서버·UI·에이전트 서드파티 고지(빌드 산출물 로드, CodeBlock) |
| `/admin/about` | 서버·UI 버전·커밋, 접속 에이전트 버전 분포, 프로토콜 버전, 지원 정책 링크 |
| 설치 안내(앱 상세 안) | 복사 가능한 `-javaagent` 라인·`argos-agent.yml`(앱 이름·환경·서버 주소 채워짐, 토큰은 자리표시)·`--check` 명령, 호환성 표 링크, 카나리 체크리스트 |

### 9.11 로그인·설치 마법사·내 계정

- `/login`: 사용자명·비밀번호, 실패 메시지는 일반화("사용자명 또는 비밀번호가 올바르지 않습니다"), 잠금 시 남은 시간, 성공 → `next`.
- `/setup`: 1) 설정 토큰 입력 2) 관리자 계정 3) **IP 수집 모드 선택(필수, 기본 선택 `masked`, 각 모드 설명)** 4) 첫 앱 등록(선택) 5) 완료 → 설치 안내. 관리자 존재 시 404.
- `/account`: 비밀번호 변경(정책 안내), 내 세션 목록·종료, 언어.

### 9.12 전역 검색 `/search`

입력값 패턴 판정: 숫자 20자리 이하 → tx id 후보, 32 hex → trace id, `user:`·`req:` 접두 → 기간 필수 검색, 그 외 텍스트 → 서비스명·SQL(S). ID 조회는 결과 1건이면 즉시 이동. 결과 목록은 DataTable. 검색어는 URL `q=`(1KB 상한).

## 10. 실시간 클라이언트

```ts
// realtime/WsManager.ts
class WsManager {
  connect(): void;                                  // wss://<host>/ws, 쿠키 세션. Origin은 브라우저가 붙임
  subscribe(topic: Topic, params: Record<string,string>, onData: (frame) => void): Unsubscribe;
  // 내부: 토픽+params 키로 참조 카운트. 첫 구독 시 sub 프레임, 마지막 해제 시 unsub 프레임
  // 재접속: 1s → 2 → 4 … 30s 상한, 지터 ±20%. 복구 시 활성 구독 전부 재전송 + 각 토픽 onResync(lastTs) 호출(REST 보충)
  // visibilitychange: hidden 30초 후 전체 unsub(연결 유지), visible 시 재구독 + 보충
  // close 4401(세션 만료·폐기): 재접속하지 않고 authStore.expire() → 로그인 복귀(상황판은 안내 화면)
  // ping/pong 30초, 10초 내 pong 없으면 끊고 재접속
}
type Topic = 'xlog' | 'counter' | 'active' | 'overview' | 'alerts' | 'config_apply';
```

연결 상태 스토어: `{ status: 'connecting'|'open'|'closed', lastMessageAt }`. 실시간 화면은 `lastMessageAt`가 10초 넘으면 "갱신 중단 N초" 배지, 30초 넘으면 폴링 보충 시작. 프레임 포맷(JSON, `{t:'data', topic, params, ts, items:[...]}` 등)은 12 문서가 확정하며 UI는 zod로 검증한다(검증 실패 프레임은 버리고 카운트).

토픽 병합 규칙: `xlog` → XLogBuffer append(중복 tx_id 제거). `counter` → 대시보드 쿼리 캐시에 최신 버킷 append(분 경계 재조회는 D-03의 "최근 5분 재조회" 규칙으로 60초마다). `active` → 스냅샷 교체. `overview` → 앱 요약 병합. `alerts` → 발생 중 목록·배지. `config_apply` → 설정 적용 결과 표 갱신.

## 11. 인증·보안 구현

| 항목 | 구현 |
|---|---|
| 세션 | 쿠키(`argos_at`, `argos_rt`, httpOnly). UI는 토큰을 읽지도 저장하지도 않는다. `localStorage`·`sessionStorage`에 토큰·사용자 식별 정보 금지(린트: `argos/no-token-storage` — `localStorage.setItem` 호출 시 키 허용 목록 검사) |
| CSRF | 서버가 비-httpOnly 쿠키 `argos_csrf`를 내려주면 API 래퍼가 상태 변경 요청에 `X-Argos-CSRF: <값>` 헤더를 붙인다(더블 서브밋). 생성 클라이언트의 `fetch` 미들웨어 한 곳에서 처리 |
| 401 | API 래퍼가 401을 받으면 1회 `/auth/refresh` 시도(서버가 쿠키로 처리) → 실패 시 `authStore.expire()` → `/login?next=`. 동시 요청은 refresh 프라미스 공유 |
| 403 | ErrorState "권한이 없습니다" + 요청 id. 메뉴·버튼은 `useCan(permission)`으로 미리 숨기지만 서버가 최종 집행 |
| 권한 모델 | `/auth/me` 응답 `{ user, role, permissions: string[] }`. 권한 키 예: `commands.thread-dump`, `commands.env-dump`, `config.push`, `tokens.manage`, `pii.view`, `audit.read`, `privacy.delete`, `channels.edit-url`, `kill-switch` |
| XSS | SafeText/SafeLink만으로 수집 데이터 렌더. ESLint `react/no-danger: error`, `no-restricted-syntax`로 `innerHTML`·`eval`·`new Function`·`document.write` 금지. 예외는 파일 allowlist(`eslint-allow.json`) + 사유 주석 |
| XSS 시드 E2E | 데모 시드(18 문서)에 심은 페이로드가 모든 화면에서 텍스트로 보이는지: Playwright 전역 훅이 각 페이지 로드 후 `window.__xss === undefined`와 `dialog` 이벤트 0을 단언 |
| CSP | 서버가 `index.html`에 요청별 nonce를 주입(`<script nonce>`·`<meta property="csp-nonce">`). AntD `ConfigProvider csp={{ nonce }}`로 CSS-in-JS 스타일 태그에 nonce 부여 → `style-src 'self' 'nonce-…'` 가능 목표. uPlot CSS는 번들 CSS 파일. `securitypolicyviolation` 이벤트를 전역에서 잡아 서버 report 엔드포인트와 콘솔에 기록(E2E는 0건 단언) |
| 외부 리소스 | 폰트·아이콘·스크립트 전부 번들. 빌드 후 `index.html`·CSS·JS에 `http(s)://` 참조가 없음을 19 문서 CI가 검사 |
| 딥링크 검증 | 4.2 zod 스키마, 길이 상한, 실패 시 기본값 + 안내 |
| CSV | 서버 내보내기 API만 사용(감사·수식 방지는 서버). UI는 다운로드 링크 처리 |
| 민감 표시 | 바인드 파라미터 `full`은 `pii.view` 권한 + 열람 시 서버가 감사 기록. UI는 "열람 기록됨" 안내 배지 |

## 12. i18n

| 항목 | 규칙 |
|---|---|
| 라이브러리 | i18next + react-i18next. 네임스페이스 = feature(`overview`, `dashboard`, `xlog`, `tx`, `trace`, `active`, `stats`, `instance`, `alerts`, `admin`, `auth`, `common`) |
| 키 규약 | `<ns>:<area>.<element>.<variant>` 소문자·점. 예 `xlog:summary.title`, `common:status.up`, `alerts:rule.field.minSamples.help` |
| 원본·번역 | `locales/en/*.json`이 원본(코드 리뷰 대상). `locales/ko/*.json`은 번역. 누락 키는 en 폴백 + 개발 모드 콘솔 경고. CI가 en·ko 키 집합 diff를 검사(누락 허용 0, 릴리스 전) |
| 용어표 | 01 문서 부록 B를 `locales/ko/common.json`의 `term.*`에 반영하고, 다른 네임스페이스는 `$t(common:term.xlog)` 참조로 재사용 |
| 하드코딩 금지 | `eslint-plugin-i18next/no-literal-string`(JSX 텍스트·`title`·`placeholder`·`aria-label`). 허용: 숫자·단위 기호·ID·코드 |
| 포맷 | `Intl.NumberFormat`(천 단위, 바이트는 자체 `formatBytes`), `Intl.DateTimeFormat`(브라우저 시간대, 초 단위), 기간 `formatDuration`(ms → "1.2s", "3m 12s"), 상대 시각("2h전"). 복수형은 i18next plural |
| 언어 선택 | 브라우저 언어가 `ko*`면 ko, 아니면 en(O-A05). 계정 메뉴에서 변경 → localStorage |
| 시간대 | 표시는 브라우저. 절대 시각 툴팁에 UTC 오프셋. 알림 메시지의 시간대는 서버 설정(UI 범위 밖) |

## 13. 디자인 토큰·레이아웃·접근성

```css
/* styles/tokens.css (AntD theme token과 antd-theme.ts에서 동기화) */
:root {
  --color-bg: #ffffff; --color-surface: #f7f8fa; --color-border: #e2e5ea; --color-text: #1f2328; --color-text-muted: #6b7280;
  --color-primary: #2f6feb;
  --status-up: #1a7f37; --status-stale: #b58900; --status-down: #c62828; --status-warn: #e65100;
  --point-normal: #3b82f6; --point-slow: #f59e0b; --point-error: #dc2626;
  --sev-warn: #d97706; --sev-critical: #b91c1c;
  --step-sql: #2563eb; --step-dbc: #0ea5e9; --step-http: #7c3aed; --step-method: #64748b; --step-async: #0d9488; --step-error: #dc2626;
  --space-1: 4px; --space-2: 8px; --space-3: 12px; --space-4: 16px; --space-6: 24px;
  --font-sans: 'ArgosSans', system-ui, sans-serif; --font-mono: 'ArgosMono', ui-monospace, monospace;
  --fs-xs: 11px; --fs-sm: 12px; --fs-md: 13px; --fs-lg: 15px; --fs-xl: 20px;
  --radius: 6px; --row-h: 28px; --row-h-dense: 20px;
}
/* 다크 모드는 1.x. 토큰만 준비(:root[data-theme="dark"] 자리) */
```

레이아웃 규칙: 최소 1366×768. 상단 바 48px, 좌측 메뉴 200px(접힘 56px), 콘텐츠 패딩 16px. 세로 스크롤은 화면 단위 1개(중첩 스크롤 최소화). 표는 가상 스크롤로 자체 높이. 1366에서 가로 스크롤 금지(E2E 측정).

접근성 기본: 포커스 링 가시(2px primary), 대비 4.5:1 이상(상태 색은 텍스트 라벨과 병기, 색만으로 의미 전달 금지), 모든 아이콘 버튼 `aria-label`, 표 행 `role=row`·키보드 이동, 차트 대체 표, 모달 포커스 트랩, `prefers-reduced-motion` 시 애니메이션 제거.

전역 단축키(`?`로 목록): `⌘/Ctrl+K` 검색, `g o` 전체 현황, `g d` 대시보드, `g x` XLog, `g a` 액티브, `[`·`]` 시간 이동, `+`·`-` 확대·축소, `n` 지금, `l` 실시간 토글, `Esc` 선택·모달 닫기, `↑↓ Enter` 목록.

## 14. 성능 예산 (N-14)

| 항목 | 예산 | 수단 |
|---|---|---|
| 초기 로드(로그인 → 전체 현황 표시) | 2초(사내망, 캐시 없음) | 라우트 lazy, 셸+전체 현황 청크 ≤ 350KB gzip, 벤더(React·AntD 핵심) 분리, AntD 트리셰이킹, 폰트 subset |
| XLog 렌더 | 16ms/프레임, 10만 점 | 7장. Performance 측정 E2E(`performance.measure`) |
| 대시보드 | 초기 1.5초, 차트 9개 동시 | 서버 버킷 ≤ 720점, 단일 API로 묶음 조회, uPlot |
| 표 | 1,000행 스크롤 60fps | 가상화, 셀 메모 |
| 실시간 | 메시지 처리 ≤ 2ms/묶음 | TypedArray append, 배치 setState(묶음당 1회) |
| 메모리 | 탭 1시간 사용 후 힙 ≤ 300MB | 링 버퍼 상한, 쿼리 캐시 gcTime 5분, 화면 이탈 시 구독 해제 |
| 번들 전체 | ≤ 2.5MB gzip | CI 번들 크기 검사(19 문서), `rollup-plugin-visualizer` 리포트 |

## 15. 테스트 전략

| 계층 | 대상 | 도구 |
|---|---|---|
| 단위 | 좌표 변환(선형·로그·DPR), 히트 인덱스, 선택 영역 계산, 선택 요약 집계, 반복 접기, self time, URL 스키마 파서·직렬화·복원 폴백, 시간 범위 연산, 포맷 함수, 권한 훅 | Vitest |
| 컴포넌트 | SafeText/SafeLink에 XSS 페이로드(`<img src=x onerror>`, `javascript:`, `"><svg/onload>`) → 텍스트 렌더 단언, DataTable 값 클릭 필터·정렬·열 선택, StatusBadge 4상태, EmptyState 문구, 규칙 폼 템플릿 변수 검증 | Vitest + Testing Library |
| 시각 회귀 | 화면별 로딩·빈·에러·정상 상태 스크린샷(데모 시드 고정) | Playwright `toHaveScreenshot` |
| E2E(화면별) | 05 문서 2.x 필수 요소 체크표 1:1(각 항목에 `data-testid`), S-xx 시나리오의 UI 구간, 딥링크 5항, 실시간 끊김·재접속·로그아웃 종료, 1366×768 가로 스크롤 0, 전체 현황 30행 무스크롤, 키보드 흐름 | Playwright |
| 전역 훅 | 모든 spec 페이지 로드 후: `window.__xss` 미정의, `dialog` 0, 콘솔 error 0, `securitypolicyviolation` 0, 네트워크 요청 출처가 자기 자신뿐 | Playwright fixture |
| 성능 | XLog 10만 점 프레임 시간(`performance.now` 루프 60프레임 평균 ≤ 16ms), 초기 로드 Navigation Timing ≤ 2초 | Playwright + 측정 스크립트 |
| 린트 | `react/no-danger`, `i18next/no-literal-string`, `argos/untrusted-text`, `argos/no-token-storage`, 생성 클라이언트 외 `fetch` 금지(`no-restricted-globals`) | ESLint(CI) |
| 접근성 | axe-core 스캔 Critical 0(화면별) | Playwright + axe |

03 문서 3.3 항목 ↔ 위 계층: E2E 핵심 시나리오(E2E), 필수 요소 표(E2E 체크표), URL 복원(단위+E2E), 전역 시간 범위(E2E), 상태 문구(시각 회귀), i18n 린트(린트), 1366 무스크롤(E2E), 생성 클라이언트만(린트), XSS·CSP(전역 훅), WS 시나리오(E2E), 키보드(E2E), 표 공통(컴포넌트).

## 16. 요구 ID 대응표

| 요구 | 구현 절 |
|---|---|
| F-U01 대시보드 | 9.2, 6.1, 6.2 |
| F-U02 XLog·목록·상세 | 7, 8, 9.3, 9.4 |
| F-U03 트레이스 | 9.5 |
| F-U04 액티브 | 9.6, 10 |
| F-U05 서비스·외부 호출 통계 | 9.7 |
| F-U06 SQL 통계 | 9.7 |
| F-U07 에러 분석 | 9.7 |
| F-U08 인스턴스 상세·덤프 뷰어 | 9.8 |
| F-U09 알림 | 9.9 |
| F-U10 관리 | 9.10 |
| F-U11 애플리케이션 맵(S) | 라우트 자리(9.1 메뉴). 상세 설계는 1.0 후반 WO에서 이 문서 개정 |
| F-U13 다국어 en·ko | 12 |
| F-U14 ID 조회·딥링크·검색 | 4, 9.12 |
| F-U16 차트·표 공통 | 6.1, 6.3 |
| F-U17 전체 현황 | 9.1 |
| F-U18 로그 링크 | 9.4·9.5 LogLink(서버 설정 템플릿 치환은 API 응답 `logUrl`) |
| F-U19 비교 기준선 | 6.1 baseline, 9.2 |
| F-U20 인스턴스 비교 표 | 9.2 |
| F-U21 상황판 | 3.4 |
| N-14 | 14 |
| D-06·D-18·D-19 수집 상태·음영·제외 표시 | 6.1 gaps, 9.2 IngestStatusPanel, 6.7 |
| D-07 프로파일 사유 | 9.4 ProfileStatusBanner |
| D-20 종류 필터 기본 요청 | 3.2 KindFilter, 4.2 `kind` |
| SEC-40·43·44 | 11, 10 |
| SEC-46 | 11 CSP |
| SEC-60~62 | 6.6, 11, 15 |
| SEC-63 | 6.3 CSV(서버 위임) |
| SEC-64 | 4.2, 4.3 |
| O-A05 | 12 |
| O-P03 | 2.1, 11 |
| S-01~S-18 | 각 화면 흐름(9장)과 E2E(15) |

## 17. 설계 결정

| ID | 결정 | 대안 | 선택 이유 | 영향 요구 |
|---|---|---|---|---|
| DSN-15-01 | 공유 상태는 URL, 서버 상태는 TanStack Query, 일시 상태만 Zustand | 전역 스토어 중심 | 딥링크·공유·새로고침 복원이 설계 목표(F-U14). 상태 이중화 방지 | F-U14, SEC-64 |
| DSN-15-02 | 시간 범위는 상대(`range`)/절대(`from,to`) 두 표현. 드래그·이동은 절대로 전환, 알림 딥링크는 항상 절대 | 항상 절대 | 프리셋 공유 시 "최근 15분" 의미 보존, 알림은 고정 범위 | 05 §2.0 딥링크 2·5 |
| DSN-15-03 | XLog는 자체 Canvas 2D(SoA TypedArray, 색별 3패스 fill, 격자 히트 인덱스, 오프스크린 점 레이어) | uPlot 산점도, WebGL | 10만 점 16ms·드래그 선택·실시간 스크롤·요약을 통제 가능하게. WebGL은 복잡도·호환 비용 | N-14, F-U02 |
| DSN-15-04 | 시계열은 uPlot + 플러그인(마커·음영·기준선·클릭 메뉴·sync) | ECharts, Recharts | 성능과 커서 동기화. 플러그인으로 D-18·F-U19·F-S18 표현 | F-U01·U16·U19 |
| DSN-15-05 | 표는 TanStack Table + Virtual 기반 자체 `DataTable` 하나. AntD Table은 단순 관리 폼에만 | AntD Table 전면 | 값 클릭 필터·열 선택·가상화·변화율 열을 모든 통계 표에 동일 제공 | F-U16 |
| DSN-15-06 | CSV 내보내기는 서버 API 호출(클라이언트 생성 금지) | 클라이언트 CSV | 감사 로그(SEC-80)와 수식 주입 방지(SEC-63)를 서버 한 곳에서 보증 | SEC-63·80 |
| DSN-15-07 | 수집 데이터 문자열은 `Untrusted<string>` 브랜드 타입 + SafeText/SafeLink 전용 렌더, 린트로 강제 | 리뷰 규율만 | AI 구현 전제에서 기계 검사 필요 | SEC-60·61 |
| DSN-15-08 | CSRF는 더블 서브밋(`argos_csrf` 쿠키 → `X-Argos-CSRF` 헤더), API 래퍼 한 곳에서 처리 | 커스텀 헤더만 | SEC-43 두 방식 모두 충족, 서버 Origin 검사와 겹겹이 | SEC-43 |
| DSN-15-09 | CSP는 nonce 기반 목표: 서버가 index.html에 nonce 주입, AntD `csp={{nonce}}`. 검증 실패 시에만 `style-src 'unsafe-inline'` 타협(사유 문서화) | 처음부터 unsafe-inline | SEC-46 상한 달성 시도 | SEC-46 |
| DSN-15-10 | WS는 단일 연결 + 토픽 멀티플렉스 + 참조 카운트, 4401은 재접속 금지 | 토픽별 연결 | 연결 수 상한(SEC-44), 세션 폐기 즉시 반영 | SEC-44, F-U04 |
| DSN-15-11 | 트랜잭션 타임라인·트리는 DOM 가상 리스트(SVG 아님) | SVG/Canvas | 1,000 step 60fps + 텍스트 선택·접근성·툴팁 단순 | F-U02 |
| DSN-15-12 | 반복 step 접기는 연속 형제 run ≥ 3, 키 = type+nameId+target | 전역 그룹화 | 순서·구조 보존, N+1 식별(S-03) | F-U02 |
| DSN-15-13 | i18n은 en 원본 JSON + ko 번역, 용어표는 `common:term.*`로 단일화, 리터럴 금지 린트 | ko 원본 | O-A05, 부록 B 일관 적용 | F-U13 |
| DSN-15-14 | 전체 현황은 10초 폴링 + WS 변경 알림 혼합 | WS만 | 30개 앱 요약은 폴링이 단순·견고, 상태·알림 변화만 즉시 | F-U17 |
| DSN-15-15 | 다크 모드·사용자 정의 대시보드는 토큰·라우트 자리만 두고 1.x | 1.0 포함 | 01 문서 범위 | 6.4 비범위 |

## 18. 미결·가정

| # | 항목 | 가정 | 확인 방법 |
|---|---|---|---|
| 1 | Ant Design 5 `ConfigProvider csp.nonce`가 사용하는 모든 컴포넌트의 스타일 주입에 nonce를 붙이는지(일부 Portal·Motion 스타일 포함) | 붙인다고 가정. 안 되면 `style-src 'unsafe-inline'` 타협 + 사유 문서화 | WO-30 골격에서 CSP 위반 리포트 0 확인 |
| 2 | 색별 `rect` 누적 fill 방식이 10만 점에서 16ms 이내인지(저사양 노트북 기준) | 8ms 예상 | WO-04 PoC(P-5) 측정. 미달 시 `ImageData` 픽셀 플롯으로 전환 |
| 3 | XLog 점 응답 포맷(바이너리 vs 열 지향 JSON) | 14 문서가 바이너리(`application/x-argos-xlog`)를 제공한다고 가정, JSON 폴백 | 14 문서 확정 |
| 4 | 트랜잭션 상세 응답에 사전(서비스·SQL·프레임)이 해석되어 오는지 | 해석되어 온다고 가정(UI 사전 조회 왕복 없음) | 14 문서 확정 |
| 5 | 선택 영역 요약을 클라이언트에서 계산해도 되는지(에러 클래스 id가 점에 포함되는지) | 점에 `errorClassId`가 포함되지 않으면 요약에서 제외하고 목록에서 확인 | 14 문서 |
| 6 | TanStack Virtual과 AntD 레이아웃의 중첩 스크롤 간섭 | 화면 단위 스크롤 1개 규칙으로 회피 | WO-30 골격 |
| 7 | 상황판 긴 세션의 만료 안내 UX와 서버 정책(SEC-42) 결합 | 서버가 세션 만료 시각을 `/auth/me`에 내려준다고 가정 | 16 문서 |
| 8 | 브라우저 지원: Chrome·Edge 최신 2개(01 문서 8장), Firefox·Safari는 미검증 | `OffscreenCanvas` 미사용(호환), `ResizeObserver` 사용 | |
| 9 | 애플리케이션 맵(F-U11, S) 렌더링 라이브러리 | 1.0 후반 WO에서 결정(ELK 레이아웃 + SVG 후보) | 이 문서 개정 |
| 10 | 폰트: 한글 포함 subset 가변 폰트 번들 크기 | 한글 subset ≤ 1.2MB woff2 | 19 문서 번들 검사 |

## 19. 다른 설계 문서에 요구하는 것

| 대상 문서 | 요구 | 이유 |
|---|---|---|
| 14 API | 엔드포인트와 응답 필드: 전체 현황(앱별 요약·스파크라인·발생 알림·서버 상태), 대시보드 시계열(`series`에 p99 포함, `source`, `baseline` 옵션, `events`, `gaps`), 인스턴스 요약·비교 표, XLog 점(바이너리·열 지향, `shown/total`, 로컬 사전), 트랜잭션 검색(영역 `sel` + 필터 `f` + 정렬, ≤1,000), 트랜잭션 상세(사전·프레임 해석, `profileStatus`, 같은 서비스 p50/p95·백분위 위치, 호출자, `logUrl`), 정상 요청 1건, 트레이스(`missing` 표기), 액티브 스냅샷, 서비스·SQL·외부 호출·에러 통계(백분위, `compare=prev` 변화율, 버전 분리, 교차 뷰), 인스턴스 상세(메타·유효 설정·출처·모듈 상태·자가 진단), 명령 요청·결과·덤프 원문 다운로드, 알림 규칙·채널·이력·통계·ACK·음소거·미리보기, 템플릿 목록, 관리 CRUD·토큰 발급(원문 1회)·회전·폐기, 설정 변경(유효 설정·diff·적용·결과·되돌리기), 킬 스위치, 사용자·감사·개인정보 삭제·라이선스·버전, 검색(ID 판정), **CSV 내보내기 엔드포인트**(감사·수식 방지), `/auth/{login,logout,refresh,me}`·CSRF 쿠키, 설치 마법사(설정 토큰). 모든 목록 응답에 `limit·truncated`, 에러 본문 표준 |
| 12 서버 | WebSocket 프레임 JSON 스키마(`sub/unsub/data/ping/pong/error`), 토픽 6종과 params, 100ms 묶음 규칙, 느린 구독자 드롭 표기, close 코드 4401, 연결당 구독 상한 20 |
| 16 보안 | `index.html` nonce 주입 방식(서버 템플릿)과 CSP 헤더 값(`style-src` nonce 또는 unsafe-inline 결정), 쿠키 이름(`argos_at`·`argos_rt`·`argos_csrf`)·속성, `/auth/me`에 세션 만료 시각·권한 키 목록, 상황판 긴 세션 정책 |
| 13 저장소 / 12 | 스택 프레임 사전·서비스·SQL 원문 해석을 API 응답 시점에 서버가 수행(UI 왕복 없음) |
| 18 테스트 | 데모 시드에 XSS 페이로드 심기(서비스 경로·SQL 리터럴·예외 메시지·헤더·user_id·스레드 이름), E2E 공통 fixture(로그인·시드·전역 훅), 시각 회귀 기준 이미지 관리, 성능 측정 스크립트 |
| 19 빌드 | pnpm workspace `ui`, Vite 빌드, OpenAPI 클라이언트 생성 태스크와 diff 검사, ESLint 자체 규칙 패키지, 번들 크기 CI 검사(≤ 2.5MB gzip, 셸 청크 ≤ 350KB), 외부 URL 참조 0 검사, en·ko 키 diff 검사, 폰트 subset 빌드 |
| 01 기획(PM) | F-U16에 "CSV는 서버 내보내기"를 명시(현재 "표 CSV"로만 적힘). F-U14 전역 검색 결과 화면 라우트 `/search` 추가 명시. 5.6 S-12에 "user_id 해시 모드면 입력값을 해시해 검색"은 서버 책임임을 명확히 |
