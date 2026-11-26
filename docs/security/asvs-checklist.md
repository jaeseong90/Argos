# OWASP ASVS checklist

> **Status: Draft skeleton** (Gate 1, WO-46; SEC-92). The draft is completed at Gate 1.5 and
> reviewed by a person at Gate 2 (`docs/16-security-design.md` section 12.2). Reference: OWASP
> Application Security Verification Standard 4.0.3, level 2.

Argos is a self-hosted web application (server and UI) with a gRPC collector for its own agents.
The chapters reviewed item by item at Gate 2 are V2 (authentication), V3 (session management), V4
(access control), V5 (validation, sanitization and encoding), V7 (error handling and logging), V9
(communications) and V14 (configuration). The other chapters record a status only (last section).

Columns:

- **ASVS item**: the requirement number of ASVS 4.0.3.
- **Status**: `Met` only when a design clause and, where one can exist, an automated test show it;
  `Not met (planned)` when the control is missing, partial or not yet tested; `N/A` when the item
  does not apply to Argos 1.0.
- **Design clause**: section of `docs/16-security-design.md` unless another document is named.
- **Test ID**: test IDs of `docs/18-test-design.md` appendix A (see `test/security/sec-gate1.tsv`
  for the commands), or the manual procedure.
- **Note**: exceptions, reasons, follow-ups.

## V2 Authentication

| ASVS item | Status | Design clause | Test ID | Note |
|---|---|---|---|---|
| V2.1.1 passwords of at least 12 characters | Met | 7.9 | T-SEC-48a | counted in Unicode code points |
| V2.1.2 at least 64 characters permitted, more than 128 refused | Met | 7.9 | T-SEC-48a | 12 to 128 code points |
| V2.1.3 no truncation | Not met (planned) | 7.9 | - | built: no trimming (T-SEC-48a), SHA-256 pre-hash above bcrypt's 72 bytes (WO-24); a test that passwords differing after byte 72 do not match is missing |
| V2.1.4 any printable Unicode character permitted | Met | 7.9 | T-SEC-48a | |
| V2.1.5 users can change their password | Met | 7.4, 7.9 | T-SEC-48 | |
| V2.1.6 a change needs the current and the new password | Met | 7.9 | T-SEC-48 | wrong current passwords count toward the lockout |
| V2.1.7 breached and common passwords refused | Met | 7.9, 15 #10 | T-SEC-48a | SecLists top 10,000 (MIT) as SHA-256 hashes, operator list adds to it |
| V2.1.9 no composition rules | Met | 7.9 | T-SEC-48a | NIST 800-63B |
| V2.1.10 no periodic rotation or history rules | Met | 7.9 | - | by design (no such setting) |
| V2.2.1 anti-automation against brute force and credential stuffing | Met | 7.9, 7.13 | T-SEC-48, T-SEC-48a, T-SEC-56 | progressive lockout 15/30/60 minutes, unknown-name lockout, 20 logins per minute per address; residual RR-8, RR-9 of the threat model |
| V2.2.3 notification after authentication details change | Not met (planned) | - | - | Argos 1.0 sends no mail to users; decide at Gate 2 (audit events exist, T-SEC-80a) |
| V2.4.1 passwords stored with an approved salted one-way function | Met | 7.9 | T-SEC-48 | bcrypt; Argon2id optional |
| V2.4.4 bcrypt work factor as high as practical (at least 10) | Met | 7.9 | T-SEC-48 | cost 12 (`$2a$12$`) |
| V2.5.1 initial secrets not sent in clear text | Not met (planned) | 7.8 | T-SEC-47 | the one-time setup token is written to the server log (and a 0600 file) by design (DSN-16-12): single use, 30 minutes; review at Gate 2 |
| V2.5.4 no shared or default accounts | Met | 7.8, 10.6 | T-SEC-47, T-SEC-74 | no default credentials; first administrator through the setup token or an unattended password file |
| V2.7, V2.8 out-of-band and one-time passwords | N/A | - | - | no multi-factor authentication in 1.0 (1.x) |
| V2.10.1 service secrets are not unchanging passwords | Met | 2.1, 2.3 | T-SEC-01, T-SEC-04 | agent tokens are random, app scoped, rotated and revoked |
| V2.10.2 no default credentials for service accounts | Met | 10.6 | T-SEC-74 | generated database passwords, forbidden list refused at start |
| V2.10.4 no secrets in source code or repositories | Met | 10.5 | secrets-scan workflow (gitleaks), `.github/scripts/test/gitleaks-config.test.sh` | test sentinels are assembled at run time or marked `gitleaks:allow` per line (ADR-0010 C-192 (4)); the weekly schedule of the secrets-scan workflow scans the full history; GitHub push protection (`branch-protection.sh`, [supply-chain.md](supply-chain.md)) |

## V3 Session management

| ASVS item | Status | Design clause | Test ID | Note |
|---|---|---|---|---|
| V3.1.1 session tokens never in URLs | Met | 7.1 | T-SEC-40 | cookies only, no Authorization header in the UI |
| V3.2.1 a new session token on authentication | Met | 7.2, 7.6 | T-SEC-41, T-SEC-43 | login issues a new session and CSRF cookie |
| V3.2.2 at least 64 bits of entropy | Met | 7.1 | T-SEC-41a | 32-byte random refresh token, signed JWT access token |
| V3.2.3 tokens stored safely in the browser | Met | 7.1 | T-SEC-40, T-SEC-40a | httpOnly cookies, nothing in Web Storage (lint rule T-SEC-60) |
| V3.3.1 logout invalidates the session | Met | 7.4 | T-SEC-42 | |
| V3.3.2 idle and absolute timeouts | Met | 7.3 | T-SEC-41, T-SEC-41a | access 15 minutes, idle 8 hours, absolute 24 hours; kiosk sessions only by policy (T-SEC-42a) |
| V3.3.3 terminate other sessions after a password change | Met | 7.4 | T-SEC-44 | all sessions revoked, WebSocket closed with 4401 within 1 second |
| V3.3.4 users can see and end their sessions | Met | 7.10 | T-E2E-058 | |
| V3.4.1 cookie `Secure` | Met | 7.1 | T-SEC-40a | `cookie.secure=false` only for non-production, warns at start |
| V3.4.2 cookie `HttpOnly` | Met | 7.1 | T-SEC-40a | the CSRF cookie is readable on purpose |
| V3.4.3 cookie `SameSite` | Met | 7.1 | T-SEC-40a | Strict |
| V3.4.4 `__Host-` prefix | Not met (planned) | 7.1 | - | cookie names have no prefix; decide at Gate 2 |
| V3.4.5 cookie path | Met | 7.1 | T-SEC-40a | |
| V3.5.3 stateless tokens are signed and checked | Met | 7.1 | T-SEC-41a | EdDSA, `alg none` and algorithm confusion refused |
| V3.7.1 re-authentication before sensitive operations | Not met (planned) | - | - | only the password change asks for the current password; decide at Gate 2 |

## V4 Access control

| ASVS item | Status | Design clause | Test ID | Note |
|---|---|---|---|---|
| V4.1.1 enforced on a trusted service layer | Met | 7.10 | T-SEC-49 | every handler carries `@RequiresRole`, checked at start |
| V4.1.2 users cannot change the attributes access control uses | Met | 7.3 | T-SEC-42 | the role comes from the session record, not the JWT claim |
| V4.1.3 least privilege | Met | 7.10, 5.1 | T-SEC-49, T-SEC-21 | matrix generated from the OpenAPI spec x 3 roles |
| V4.1.5 access control fails securely | Met | 7.10 | T-SEC-49 | the interceptor fails closed; unknown handlers refused |
| V4.2.1 protection against insecure direct object references | Met | 7.11 | T-SEC-50, T-SEC-50a | UUIDv4 identifiers, owner or administrator |
| V4.2.2 CSRF protection | Met | 7.6 | T-SEC-43, T-SEC-43a | double submit plus Origin and `Sec-Fetch-Site` |
| V4.3.1 multi-factor authentication for administration | Not met (planned) | - | - | 1.x |
| V4.3.3 additional authorization for sensitive data | Met | 6.7 | T-SEC-3A | `pii.view` for full bind parameters, audited |

## V5 Validation, sanitization and encoding

| ASVS item | Status | Design clause | Test ID | Note |
|---|---|---|---|---|
| V5.1.1 HTTP parameter pollution | Not met (planned) | - | - | ZAP scan at Gate 1.5 |
| V5.1.3 input validation with allow lists | Met | 9.7, 14 | T-SEC-64 | deep-link parameters validated by schema; API inputs bounded |
| V5.2.1 untrusted HTML sanitized | Met | 9.1 .. 9.3 | T-SEC-60, T-SEC-61 | Argos never renders HTML from collected data: lint rules forbid the sinks, safe components render text |
| V5.2.6 SSRF protection | Met | 8.1 | T-SEC-51, T-SEC-51b | destination validation, pinned address, redirects not followed |
| V5.3.1 output encoding for the context | Met | 9.3 | T-SEC-61, T-SEC-62 | seeded XSS payloads over every screen with the global page hook |
| V5.3.3 context-aware escaping against XSS | Met | 9.3 .. 9.5, 9.9 | T-SEC-61, T-SEC-62, T-SEC-46 | CSP with nonce as a second line |
| V5.3.4 parameterized database queries | Not met (planned) | 13, 14 | - | queries are built with parameters; no automated injection test yet (ZAP at Gate 1.5); the reader account is read only (T-SEC-76) |
| V5.5.3 no deserialization of untrusted data into objects | Met | 1.4 (point 4), 4.1 | T-SEC-11, T-SEC-15, T-SEC-91a | protobuf messages only, bounded parsing; the agent loads no code named by server data |

## V7 Error handling and logging

| ASVS item | Status | Design clause | Test ID | Note |
|---|---|---|---|---|
| V7.1.1 no credentials or session tokens in logs | Met | 7.9, 2.6 | T-SEC-48b, T-SEC-06 | `@Sensitive` ArchUnit rule, log masking of cookies, JWTs, tokens and key material |
| V7.1.2 no other sensitive data in logs | Met | 6, SEC-26 | T-SEC-26, T-SEC-26a, T-NFR-10c | the agent's INFO logs carry no collected data; the PII seed grep covers server and agent logs |
| V7.1.3 security events are logged | Met | 11.1 | T-SEC-80a, T-SEC-80b, T-SEC-80c, T-SEC-80d | logins, tokens, commands, configuration |
| V7.1.4 events carry what a timeline needs | Met | 11.2 | T-SEC-81 | |
| V7.2.1 authentication decisions are logged | Met | 11.1 | T-SEC-80a | |
| V7.2.2 access control failures are logged | Not met (planned) | 11.1 | - | 403 decisions are counted, not audited one by one; decide at Gate 2 |
| V7.3.1 log injection prevented | Not met (planned) | 7.14 | T-SEC-46a | CSP report fields are cleaned of control characters; a general log-injection test is missing |
| V7.3.3 logs protected from change | Met | 11.3 | T-SEC-82 | the application account has INSERT and SELECT only on `audit_logs` |
| V7.4.1 generic error message with a request ID | Met | 7.14 | T-SEC-56 | 5xx bodies hold `internal_error` and `request_id` only |

## V9 Communications

| ASVS item | Status | Design clause | Test ID | Note |
|---|---|---|---|---|
| V9.1.1 TLS for all client connections, no insecure fallback | Met | 3.1, 3.4 | T-SEC-07, T-SEC-08 | agent TLS by default, plaintext needs two flags and warns; UI HTTPS through the built-in TLS or a reverse proxy (17), HSTS on secure requests (T-SEC-45) |
| V9.1.2 strong cipher suites | Met | 3.2 | T-SEC-07, T-SEC-07b | TLS 1.2 AEAD and 1.3 only; testssl scan of the collector port (WO-46) |
| V9.1.3 only TLS 1.2 and 1.3 | Met | 3.1 | T-SEC-07b | |
| V9.2.1 trusted certificates for outbound connections | Met | 3.4, 8.5 | T-SEC-08, T-SEC-54 | host name verification in the agent; SMTP STARTTLS required |
| V9.2.2 encryption for all connections, back end included | Not met (planned) | 10.7, 17 | - | PostgreSQL and ClickHouse sit on the internal Compose network without TLS; a TLS option for remote databases is documented in 17 |
| V9.2.3 outbound connections with sensitive data are encrypted and authenticated | Met | 8.1, 8.4, 8.5 | T-SEC-51b, T-SEC-53, T-SEC-54 | webhooks HTTPS by default and signed |
| V9.2.4 certificate revocation checking | Not met (planned) | - | - | decide at Gate 2 |

## V14 Configuration

| ASVS item | Status | Design clause | Test ID | Note |
|---|---|---|---|---|
| V14.1.1 secure, repeatable build and deployment | Met | 10.4 | T-BLD-009 | two builds compared entry by entry in the release workflow |
| V14.2.1 components up to date, dependency checking | Met | 10.2 | T-BLD-008, ST-71-2, T-SEC-71a, T-SEC-71b (proposed) | Trivy gate on every pull request (`sbom-scan`) and release, weekly rescan (`sbom-rescan.yml`); exceptions in `docs/security/cve-exceptions.md`; Dependabot monthly grouped updates and immediate security updates ([supply-chain.md](supply-chain.md)) |
| V14.2.2 unneeded features removed | Met | 7.12 | T-SEC-55 | Actuator limited to health, info, prometheus |
| V14.2.3 no external resources in the UI | Met | 9.9, 15 | `pnpm -C ui check:external` | the bundle loads nothing from outside |
| V14.2.4 third-party components from trusted sources | Met | 10.4 | T-SEC-73a (proposed) | dependency checksums in `gradle/verification-metadata.xml` (a changed or unlisted artifact fails the build), pnpm lock file with integrity hashes and `--frozen-lockfile`; `check-dependency-verification.sh` rejects lenient or off verification |
| V14.2.5 software bill of materials | Met | 10.1 | T-BLD-008, T-BLD-003 | agent, server and UI SBOMs; `argos-deps.txt` in the agent jar |
| V14.2.6 third-party libraries isolated | Met | 10 (agent) | T-BLD-002 | the agent shades every dependency under `io.argos.agent.shaded` |
| V14.3.2 debug modes off in production | Met | 7.14 | T-BLD-010, T-SEC-56 | release agent jars refuse test keys; server error details never included |
| V14.3.3 no version details in HTTP headers | Not met (planned) | 9.8 | - | no automated check of `Server` and similar headers yet |
| V14.4.1 content type with a safe character set | Met | 9.8 | T-SEC-45 | |
| V14.4.3 Content-Security-Policy | Met | 9.9 | T-SEC-46, T-SEC-46a | nonce and `strict-dynamic`; style fallback off |
| V14.4.4 `X-Content-Type-Options: nosniff` | Met | 9.8 | T-SEC-45 | |
| V14.4.5 Strict-Transport-Security | Met | 9.8 | T-SEC-45 | on secure requests only |
| V14.4.6 Referrer-Policy | Met | 9.8 | T-SEC-45 | |
| V14.4.7 framing refused | Met | 9.8, 9.9 | T-SEC-45, T-SEC-46a | `frame-ancestors 'none'` |
| V14.5.3 strict CORS | Met | 9.8 | T-SEC-45 | no cross-origin access; preflight refused (WO-24) |

## Other chapters (status only)

| Chapter | Status | Note |
|---|---|---|
| V1 Architecture and threat modeling | Draft | `docs/security/threat-model.md` (SEC-91) |
| V6 Stored cryptography | Partly met | master-key envelopes AES-256-GCM (T-SEC-52), token hashes (T-SEC-02); review at Gate 2 |
| V8 Data protection | Partly met | data classification (02 section 5.2), deletion API (T-SEC-33), PII seed grep (T-NFR-10c) |
| V10 Malicious code | Partly met | ArchUnit ST-91-1 (T-SEC-91a), secret scanning, SBOM gate |
| V11 Business logic | Not reviewed | command and configuration flows (WSTG BUSL at Gate 2) |
| V12 Files and resources | Not reviewed | diagnostics bundle (T-SEC-06g), CSV exports (T-SEC-63) |
| V13 API and web service | Partly met | OpenAPI role matrix (T-SEC-49), rate limits (T-SEC-56); gRPC covered by the collector tests |
