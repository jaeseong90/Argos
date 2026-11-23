// SPDX-License-Identifier: Apache-2.0
//
// Commands tab of the instance detail (05 §2.7-3, §2.7-4, docs/15 §9.8, WO-37): the command
// history and buttons, the latest environment dump / self diagnosis panels and the self diagnosis
// hook the environment tab merges its effective configuration with.
export { CommandsTab, type CommandsTabProps } from './CommandsTab';
export { LatestResultPanel, type LatestResultPanelProps } from './LatestResultPanel';
export {
  useLatestSelfDiagnosis,
  type LatestSelfDiagnosis,
  type SelfDiagnosisSummary,
} from './useLatestSelfDiagnosis';
