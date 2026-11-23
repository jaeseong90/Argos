// SPDX-License-Identifier: Apache-2.0
//
// Representative stack of the error group detail (05 §2.5-5 "애플리케이션 패키지 프레임 굵게"): the
// transaction detail's FrameList, which the error detail reuses, puts application frames in bold
// once the application's packages are known. The API has no `app_packages` until WO-26 (ADR-0010
// C-207 (3)), so this fixture stands in for them: frames of `com.shop` are bold, library frames and
// look-alike packages (`com.shopping`) are not, and with no packages nothing is bold.
// Requirements (test/README.md §5): { type: 'req', description: 'F-U07' }
import { render, screen, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';

import { untrusted } from '../../../api/untrusted';
import { withI18n } from '../../../components/testing/withI18n';
import { FrameList } from '../../transaction/FrameList';
import { SAMPLE_STACK } from '../testing/fixtures';

/** The fixture standing in for the API's app_packages (WO-26). */
const APP_PACKAGES = ['com.shop'] as const;

const FRAMES = [...SAMPLE_STACK, 'com.shopping.cart.Cart.add(Cart.java:12)'].map((frame) =>
  untrusted(frame),
);

/** A step-like frame source, as the transaction detail passes it. */
const SOURCE = { frames: FRAMES };

function appFlags(): boolean[] {
  return within(screen.getByTestId('stack'))
    .getAllByTestId('transaction-frame')
    .map((frame) => frame.getAttribute('data-app') === 'true');
}

describe('representative stack application frames (05 §2.5-5, C-207 (3))', () => {
  it('puts the frames of the application packages in bold once packages exist', () => {
    render(<FrameList source={SOURCE} appPackages={APP_PACKAGES} data-testid="stack" />, {
      wrapper: withI18n('en'),
    });
    // com.shop.order..., spring, com.shop.web..., java.base, com.shopping (not com.shop).
    expect(appFlags()).toEqual([true, false, true, false, false]);
    const bold = screen.getByTestId('stack').querySelectorAll('.tx-frame--app');
    expect(Array.from(bold).map((frame) => frame.textContent)).toEqual([
      'com.shop.order.OrderService.pay(OrderService.java:88)',
      'com.shop.web.OrderController.pay(OrderController.java:41)',
    ]);
  });

  it('puts nothing in bold without application packages (until WO-26)', () => {
    render(<FrameList source={SOURCE} data-testid="stack" />, {
      wrapper: withI18n('en'),
    });
    expect(appFlags()).toEqual([false, false, false, false, false]);
    expect(screen.getByTestId('stack').querySelectorAll('.tx-frame--app')).toHaveLength(0);
  });
});
