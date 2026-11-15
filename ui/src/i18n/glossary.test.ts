// SPDX-License-Identifier: Apache-2.0
//
// T-UI-006 (F-U13, O-A05, DSN-15-13): the Korean UI labels of docs/01 appendix B live in
// common:term.* with exactly the labels of the table; English uses the document terms; every
// English key has a Korean translation (plural forms per language) and the shell's screen labels
// reuse the glossary through $t(common:term.*).
// Requirements (test/README.md §5): { type: 'req', description: 'F-U13' }, { type: 'req', description: 'O-A05' }
import { describe, expect, it } from 'vitest';

import enAuth from '../locales/en/auth.json';
import enCommon from '../locales/en/common.json';
import enShell from '../locales/en/shell.json';
import koAuth from '../locales/ko/auth.json';
import koCommon from '../locales/ko/common.json';
import koShell from '../locales/ko/shell.json';

import { createI18n } from './createI18n';

/**
 * docs/01 appendix B, one row per entry: document term, i18n key under common:term, the exact
 * Korean UI label. Rows with two labels (e.g. "원격 명령 / 환경 덤프") have one key per label.
 */
const APPENDIX_B: readonly (readonly [term: string, key: string, ko: string])[] = [
  ['XLog', 'term.xlog', 'XLog'],
  ['XLog (tooltip)', 'term.xlogDescription', '응답시간 분포도'],
  ['애플리케이션 맵(서비스 맵)', 'term.applicationMap', '애플리케이션 맵'],
  ['느림(점 색)', 'term.pointSlow', '지연'],
  ['느림(점 색) legend: normal', 'term.pointNormal', '정상'],
  ['느림(점 색) legend: error', 'term.pointError', '에러'],
  ['에러 지문', 'term.errorGroup', '에러 그룹'],
  ['음소거', 'term.mute', '알림 일시 중지(점검)'],
  ['OK', 'term.alertState.ok', '정상'],
  ['PENDING', 'term.alertState.pending', '대기'],
  ['FIRING', 'term.alertState.firing', '발생'],
  ['ACKED', 'term.alertState.acked', '확인'],
  ['RESOLVED', 'term.alertState.resolved', '해제'],
  ['NO_DATA', 'term.alertState.noData', '데이터 없음'],
  ['복구 임계치', 'term.recoveryThreshold', '해제 기준'],
  ['드롭', 'term.drop', '유실'],
  ['시계 차이', 'term.clockSkew', '시간 오차'],
  ['스탯 타일', 'term.statTile', '요약 지표'],
  ['워터폴', 'term.waterfall', '타임라인'],
  ['호출 트리', 'term.callTree', '호출 트리'],
  ['Profile step', 'term.profileStep', '프로파일 구간'],
  ['원격 명령', 'term.remoteCommand', '에이전트 명령'],
  ['환경 덤프', 'term.envDump', '환경 정보'],
  ['롤업', 'term.rollup', '집계(1분·1시간)'],
  ['배포 마커 / 자동 이벤트', 'term.eventMarker', '이벤트 표시(배포·재기동·설정 변경)'],
  ['딥링크', 'term.deepLink', '링크 복사'],
  ['수집 품질', 'term.collectionQuality', '수집 상태'],
  ['액티브 트랜잭션', 'term.activeTransactions', '액티브 트랜잭션'],
  ['Apdex', 'term.apdex', 'Apdex'],
  ['종류(kind) HTTP', 'term.kind.http', '요청'],
  ['종류(kind) BATCH', 'term.kind.batch', '배치'],
  ['종류(kind) MESSAGE', 'term.kind.message', '메시지'],
  ['DBC step', 'term.dbcStep', '커넥션 대기'],
  ['전체 현황', 'term.overview', '전체 현황'],
];

/** English document terms (docs/01 appendix B: "en은 문서 용어의 영어 표기"). */
const EN_TERMS: Readonly<Record<string, string>> = {
  'term.xlog': 'XLog',
  'term.errorGroup': 'Error fingerprint',
  'term.alertState.ok': 'OK',
  'term.alertState.firing': 'Firing',
  'term.alertState.resolved': 'Resolved',
  'term.drop': 'Drop',
  'term.waterfall': 'Waterfall',
  'term.rollup': 'Rollup',
  'term.kind.http': 'HTTP',
  'term.kind.batch': 'Batch',
  'term.kind.message': 'Message',
  'term.overview': 'Overview',
};

type Catalog = Readonly<Record<string, unknown>>;

function lookup(catalog: Catalog, path: string): unknown {
  return path.split('.').reduce<unknown>((node, part) => {
    if (typeof node === 'object' && node !== null && part in node) {
      return (node as Record<string, unknown>)[part];
    }
    return undefined;
  }, catalog);
}

function leaves(catalog: Catalog, prefix = ''): string[] {
  return Object.entries(catalog).flatMap(([key, value]) =>
    typeof value === 'object' && value !== null
      ? leaves(value as Catalog, `${prefix}${key}.`)
      : [`${prefix}${key}`],
  );
}

/** Base key of a plural form (key_one, key_other -> key). */
function base(key: string): string {
  return key.replace(/_(zero|one|two|few|many|other)$/, '');
}

describe('T-UI-006 docs/01 appendix B glossary (common:term.*)', () => {
  it.each(APPENDIX_B)('T-UI-006 %s -> %s = %s', (_term, key, ko) => {
    expect(lookup(koCommon, key)).toBe(ko);
    expect(typeof lookup(enCommon, key)).toBe('string');
  });

  it('T-UI-006 English uses the document terms', () => {
    for (const [key, en] of Object.entries(EN_TERMS)) {
      expect(lookup(enCommon, key)).toBe(en);
    }
  });

  it('T-UI-006 resolves the glossary through i18next in both languages', () => {
    const ko = createI18n('ko');
    const en = createI18n('en');
    expect(ko.t('common:term.alertState.firing')).toBe('발생');
    expect(ko.t('common:term.kind.http')).toBe('요청');
    expect(en.t('common:term.kind.http')).toBe('HTTP');
    // Screen titles reuse the glossary ($t(common:term.*)), so the menu follows appendix B.
    expect(ko.t('shell:screen.overview')).toBe('전체 현황');
    expect(ko.t('shell:screen.xlog')).toBe('XLog');
    expect(ko.t('shell:screen.active')).toBe('액티브 트랜잭션');
    expect(ko.t('shell:screen.map')).toBe('애플리케이션 맵');
    expect(ko.t('shell:screen.errorDetail')).toBe('에러 그룹');
    expect(en.t('shell:screen.overview')).toBe('Overview');
  });

  it.each([
    ['common', enCommon, koCommon],
    ['shell', enShell, koShell],
    ['auth', enAuth, koAuth],
  ] as const)('T-UI-006 every English key of %s has a Korean translation', (_ns, en, ko) => {
    const koKeys = new Set(leaves(ko).map(base));
    const missing = leaves(en)
      .map(base)
      .filter((key) => !koKeys.has(key));
    expect(missing).toEqual([]);
    const enKeys = new Set(leaves(en).map(base));
    expect(
      leaves(ko)
        .map(base)
        .filter((key) => !enKeys.has(key)),
    ).toEqual([]);
  });

  it('T-UI-006 uses i18next plurals for counts (Korean has only the other form)', () => {
    const ko = createI18n('ko');
    const en = createI18n('en');
    expect(en.t('shell:realtime.staleTitle', { count: 1 })).toMatch(
      /^No realtime updates for 1 second\. /,
    );
    expect(en.t('shell:realtime.staleTitle', { count: 12 })).toMatch(
      /^No realtime updates for 12 seconds\. /,
    );
    expect(ko.t('shell:realtime.stale', { count: 12 })).toBe('갱신 중단 12초');
  });
});
