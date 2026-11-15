// SPDX-License-Identifier: Apache-2.0
//
// docs/05 §2.0 "모든 화면 공통" as data (ui/e2e/required/common.yaml, docs/18 §13, DSN-18-10): the
// required-elements engine declares one test T-E2E-common-<n> per checklist entry. Screen work
// orders add their own file (required/<screen>.yaml) and one line here.
import { defineRequiredTests } from '../required/engine';

defineRequiredTests('common');
