// SPDX-License-Identifier: Apache-2.0
//
// Stack frames as an ordered list of text nodes (SafeText, monospace; SEC-60), top of the stack
// first. Optional marks: frames common to every STACK capture, the deepest common frame ("stuck
// here", docs/15 §8.5) and application frames in bold (only when the application's packages are
// known; until WO-26 adds `app_packages` none are, ADR-0010 C-207 (3)).
import { useTranslation } from 'react-i18next';

import type { Untrusted } from '../../api/untrusted';
import { SafeText } from '../../components/SafeText';

import { isAppFrame } from './model/stacks';

/** Anything that carries frames (a STACK or ERROR step, a capture). */
export interface FrameSource {
  readonly frames?: readonly Untrusted[] | undefined;
}

/** Class names of a frame row. */
function frameClass(common: boolean, stuck: boolean, app: boolean): string {
  const classes = ['tx-frame'];
  if (common) classes.push('tx-frame--common');
  if (stuck) classes.push('tx-frame--stuck');
  if (app) classes.push('tx-frame--app');
  return classes.join(' ');
}

export interface FrameListProps {
  /** The step or capture whose frames are listed (top of the stack first). */
  readonly source: FrameSource;
  /** Frames present in every capture (highlighted). */
  readonly common?: ReadonlySet<string>;
  /** The deepest common frame (marked "stuck here"). */
  readonly stuckAt?: string | null;
  /** Application packages (bold frames); none until the API provides them. */
  readonly appPackages?: readonly string[];
  readonly 'data-testid'?: string;
}

export function FrameList({
  source,
  common,
  stuckAt = null,
  appPackages = [],
  'data-testid': testId,
}: FrameListProps) {
  const { t } = useTranslation(['common', 'tx']);
  const frames = source.frames ?? [];
  if (frames.length === 0) {
    return (
      <p className="tx-muted" data-testid={testId}>
        {t('tx:panel.noFrames')}
      </p>
    );
  }
  return (
    <ol className="tx-frames" data-testid={testId} data-count={frames.length}>
      {frames.map((frame, index) => {
        const isCommon = common?.has(frame) === true;
        const isStuck = stuckAt !== null && frame === stuckAt;
        const app = isAppFrame(frame, appPackages);
        return (
          <li
            key={`${String(index)}:${frame}`}
            className={frameClass(isCommon, isStuck, app)}
            data-testid="transaction-frame"
            data-common={isCommon ? 'true' : undefined}
            data-stuck={isStuck ? 'true' : undefined}
            data-app={app ? 'true' : undefined}
          >
            <SafeText value={frame} mono maxLines={0} />
            {isStuck ? <span className="tx-frame__stuck">{t('tx:stack.stuckHere')}</span> : null}
          </li>
        );
      })}
    </ol>
  );
}
