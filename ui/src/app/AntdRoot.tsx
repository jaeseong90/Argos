// SPDX-License-Identifier: Apache-2.0
//
// Ant Design root of every page except the WO-04 XLog PoC (docs/15 §2.1, §11, DSN-15-09):
// ConfigProvider with the per-request CSP nonce that the server writes into
// <meta name="csp-nonce"> (docs/16 §9.9, WO-24 IndexHtmlTemplate), the token theme and the UI
// language. Ant Design's CSS-in-JS style tags then carry the nonce, so style-src needs no
// 'unsafe-inline'. Kept out of /xlog-poc so that the PoC stays exactly as WO-04 left it.
import { ConfigProvider } from 'antd';
import enUS from 'antd/locale/en_US';
import koKR from 'antd/locale/ko_KR';
import { useMemo } from 'react';
import { useTranslation } from 'react-i18next';
import { Outlet } from 'react-router';

import { createAntdTheme, prefersReducedMotion } from '../styles/antd-theme';

import { readCspNonce } from './nonce';

export function AntdRoot() {
  const { i18n } = useTranslation();
  const nonce = useMemo(() => readCspNonce(document), []);
  const theme = useMemo(() => createAntdTheme({ reducedMotion: prefersReducedMotion() }), []);
  const locale = i18n.resolvedLanguage === 'ko' ? koKR : enUS;
  return (
    <ConfigProvider csp={nonce === undefined ? undefined : { nonce }} theme={theme} locale={locale}>
      <Outlet />
    </ConfigProvider>
  );
}
