// SPDX-License-Identifier: Apache-2.0
//
// Hover/focus tooltip for modules outside src/components that may not import Ant Design directly
// (charts). Pass React content; collected strings inside it go through SafeText.
import { Tooltip } from 'antd';
import type { ReactElement, ReactNode } from 'react';

export interface HintProps {
  readonly title: ReactNode;
  readonly children: ReactElement;
  readonly placement?: 'top' | 'bottom' | 'left' | 'right';
}

/** Ant Design tooltip with the shared defaults. */
export function Hint({ title, children, placement = 'top' }: HintProps) {
  return (
    <Tooltip title={title} placement={placement} mouseEnterDelay={0.15}>
      {children}
    </Tooltip>
  );
}
