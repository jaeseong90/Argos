// SPDX-License-Identifier: Apache-2.0
//
// Texts shared by the trace rows, their tooltip and the node panel: the D-07 reason of a profile
// that was not captured (profile_state, ADR-0010 C-154) and the label of a missing-node
// placeholder by its role and the server's reason (05 §2.8-3).
import { useTranslation } from 'react-i18next';

import type { NodeProfileState } from '../data/traceModel';
import type { MissingRow } from '../model/traceTree';

/** The profile state as text: captured, the D-07 reason (sampling / lost), or unknown. */
export function useProfileText(): (state: NodeProfileState) => string {
  const { t } = useTranslation(['common', 'trace']);
  return (state) => {
    switch (state) {
      case 'captured':
        return t('trace:node.profileCaptured');
      case 'sampled_out':
        return t('common:component.partial.profileSampledOut');
      case 'lost':
        return t('common:component.partial.profileLost');
      case 'unknown':
        return t('trace:node.profileUnknown');
    }
  };
}

/** The text of a missing-node placeholder (05 §2.8-3) by its role and the server's reason. */
export function MissingLabel({ row }: { readonly row: MissingRow }) {
  const { t } = useTranslation(['common', 'trace']);
  const notYet = row.item.missing.reason === 'not_yet_collected';
  if (row.item.role === 'caller') {
    return (
      <>
        {notYet ? t('trace:row.missingCallerNotYet') : t('trace:row.missingCallerNotInstrumented')}
      </>
    );
  }
  return (
    <>{notYet ? t('trace:row.missingCalleeNotYet') : t('trace:row.missingCalleeNotInstrumented')}</>
  );
}
