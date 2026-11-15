// SPDX-License-Identifier: Apache-2.0
//
// zod for the application, configured without its JIT (docs/16 §9.9, SEC-46). zod 4 compiles
// object parsers with `new Function` and probes for it when a z.object() is created. The server's
// CSP (script-src 'self' 'nonce-…', no 'unsafe-eval') blocks that, and the browser reports the
// probe as a securitypolicyviolation even though zod catches the error. The interpreted
// (jitless) path gives the same results.
//
// Application code imports `z` from this module, never from 'zod' directly: a module that creates
// schemas then always runs after the configuration, also when the bundler places zod and the
// schema modules in a shared chunk that runs before main.tsx.
import { z } from 'zod';

z.config({ jitless: true });

export { z };
