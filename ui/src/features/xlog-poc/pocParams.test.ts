// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from 'vitest';

import { DEFAULT_PARAMS, MAX_POINTS, parsePocParams, withParam } from './pocParams';

function parse(query: string) {
  return parsePocParams(new URLSearchParams(query));
}

describe('/xlog-poc URL parameters (SEC-64: whitelist and bounds)', () => {
  it('defaults to 100,000 static points coloured by status on a linear axis, drawn as pixels (ADR-0003 C-34)', () => {
    expect(parse('')).toEqual(DEFAULT_PARAMS);
    expect(DEFAULT_PARAMS).toMatchObject({
      n: 100_000,
      live: false,
      rate: 500,
      colorBy: 'status',
      yScale: 'linear',
      render: 'pixels',
      incremental: true,
      canvas: 'gpu',
      pointCap: null,
      threshold: 1000,
    });
  });

  it('reads valid values', () => {
    expect(
      parse(
        'n=50000&total=342118&live=1&rate=800&color=service&y=log&render=path&incremental=0&canvas=cpu&pointcap=1.25&threshold=3000&seed=9&perf=1',
      ),
    ).toEqual({
      n: 50_000,
      total: 342_118,
      live: true,
      rate: 800,
      colorBy: 'service',
      yScale: 'log',
      render: 'path',
      incremental: false,
      canvas: 'cpu',
      pointCap: 1.25,
      threshold: 3000,
      seed: 9,
      perf: true,
    });
  });

  it.each([
    'n=-1',
    'n=1e5',
    'n=150001',
    'n=abc',
    'color=<script>',
    'y=sqrt',
    'render=webgl',
    'canvas=webgl',
    'pointcap=0.5',
    'pointcap=3.5',
    'pointcap=1.125',
    'pointcap=2e0',
    'threshold=0',
    'rate=999999',
    'live=yes',
    'seed=99999999999',
  ])('falls back to the default for %s', (query) => {
    const parsed = parse(query);
    const [key] = query.split('=');
    const field = (
      {
        n: 'n',
        color: 'colorBy',
        y: 'yScale',
        render: 'render',
        canvas: 'canvas',
        pointcap: 'pointCap',
        threshold: 'threshold',
        rate: 'rate',
        live: 'live',
        seed: 'seed',
      } as const
    )[key as 'n'];
    expect(parsed[field]).toEqual(DEFAULT_PARAMS[field]);
  });

  it('reads the point layer scale cap within 1..3 (ADR-0003 C-34 measurement option)', () => {
    expect(parse('pointcap=1').pointCap).toBe(1);
    expect(parse('pointcap=1.5').pointCap).toBe(1.5);
    expect(parse('pointcap=3').pointCap).toBe(3);
    expect(parse('pointcap=3.01').pointCap).toBeNull();
  });

  it('bounds the number of points by the buffer capacity', () => {
    expect(parse(`n=${String(MAX_POINTS)}`).n).toBe(MAX_POINTS);
    expect(parse(`n=${String(MAX_POINTS + 1)}`).n).toBe(DEFAULT_PARAMS.n);
  });

  it('ignores a total that is not larger than the shown points', () => {
    expect(parse('n=1000&total=1000').total).toBeNull();
    expect(parse('n=1000&total=999').total).toBeNull();
    expect(parse('n=1000&total=600000').total).toBeNull();
    expect(parse('n=1000&total=1001').total).toBe(1001);
  });

  it('writes one chart parameter and keeps the others', () => {
    const next = withParam(new URLSearchParams('n=10&color=status'), 'color', 'instance');
    expect(next.toString()).toBe('n=10&color=instance');
  });
});
