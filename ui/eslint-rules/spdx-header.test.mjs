// SPDX-License-Identifier: Apache-2.0
import { RuleTester } from 'eslint';
import { describe, it } from 'vitest';

import spdxHeader from './spdx-header.mjs';

RuleTester.describe = describe;
RuleTester.it = it;
RuleTester.itOnly = it.only;

const ruleTester = new RuleTester({
  languageOptions: { ecmaVersion: 'latest', sourceType: 'module' },
});

const HEADER = '// SPDX-License-Identifier: Apache-2.0';
const message = { messageId: 'missing', data: { expected: HEADER } };

ruleTester.run('argos/spdx-header (T-BLD-007)', spdxHeader, {
  valid: [
    `${HEADER}\nexport const a = 1;\n`,
    `${HEADER}\r\nexport const a = 1;\r\n`,
    `${HEADER}\n`,
    `#!/usr/bin/env node\n${HEADER}\nexport {};\n`,
    { code: '// SPDX-License-Identifier: MIT\nexport {};\n', options: [{ identifier: 'MIT' }] },
  ],
  invalid: [
    {
      code: 'export const a = 1;\n',
      errors: [message],
      output: `${HEADER}\nexport const a = 1;\n`,
    },
    {
      code: '',
      errors: [message],
      output: `${HEADER}\n`,
    },
    {
      code: '/* SPDX-License-Identifier: Apache-2.0 */\nexport {};\n',
      errors: [message],
      output: `${HEADER}\n/* SPDX-License-Identifier: Apache-2.0 */\nexport {};\n`,
    },
    {
      code: `// comment first\n${HEADER}\nexport {};\n`,
      errors: [message],
      output: `${HEADER}\n// comment first\n${HEADER}\nexport {};\n`,
    },
    {
      code: '// SPDX-License-Identifier: MIT\nexport {};\n',
      errors: [message],
      output: `${HEADER}\n// SPDX-License-Identifier: MIT\nexport {};\n`,
    },
    {
      code: '#!/usr/bin/env node\nexport {};\n',
      errors: [{ ...message, line: 2 }],
      output: `#!/usr/bin/env node\n${HEADER}\nexport {};\n`,
    },
  ],
});
