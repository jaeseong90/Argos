# test/seed

Seed traffic for the privacy and XSS tests (docs/18 §4.3, DSN-18-09).

Every seed value contains the sentinel `ARGOS_SEED`, so a single `LIKE '%ARGOS_SEED%'` (or `grep`)
finds a leak anywhere. Documented exceptions (`Seeds.java`): the `*_PATTERN` values (realistic
shapes for the masking pattern tests) and the access key shape `AKIAARGOSSEED0000001` (the docs/18
§4.3 value, sent in the `X-Api-Key` header), which carries `ARGOSSEED` without the underscore: leak
scans must also search for `ARGOSSEED`.

Exception-message seeds (ADR-0010 C-250 (1)): the agent masks an exception message with the
SEC-36 regular expressions only (email, Korean mobile number, resident number, Luhn card, IPv4),
so a phone or card seed in a message must have the real shape, or it tests no masking rule. The
phone and card exception seeds (`seed.phone.error`, `seed.card.error`) are therefore the fixed
real-shaped sentinels `010-9999-0001` and the Luhn-valid `4111111111111111`
(`Seeds.PHONE_ERROR_MESSAGE`, `Seeds.CARD_ERROR_MESSAGE`); their 500 answers carry that value
instead of `ARGOS_SEED`. Query-string and bind seeds keep `ARGOS_SEED` (the query string of
`seed.phone.error` still holds `010-ARGOS_SEED-0001`). Leak scans search every value of
`Seeds.LEAK_SENTINELS`: `ARGOS_SEED`, `ARGOSSEED`, `010-9999-0001`, `990101-1234567` and
`4111111111111111`. `seed-traffic.sh` calls every seed endpoint of the shop
sample apps and of shop-batch (the batch job name XSS seeds) N times; the security tests then
assert that the sentinel appears nowhere it must not: ClickHouse, PostgreSQL, agent and server
logs, alert inboxes, CSV exports (with the default `masked`/`off` settings; tests that turn on
`full`/`plain` assert the opposite).

```bash
# against an app that is already running (a WAR context path goes into the URL)
test/seed/seed-traffic.sh --url http://localhost:8080 --repeat 10

# or start shop-boot3 here (ext-stub in process, the seed environment set), then stop it
test/seed/seed-traffic.sh --start --repeat 10

# the batch job name XSS seeds of shop-batch (running, or started here with --start)
test/seed/seed-traffic.sh --catalog batch --url http://localhost:8080 --repeat 10
test/seed/seed-traffic.sh --catalog batch --start --repeat 10
```

The seed endpoints are the `seed.*` entries of the shared catalogs in `sample-apps/common`.
`--catalog shop` (default, `ShopCatalog`, the shop apps): email, phone, resident number and card
shapes (email, phone and card also in an exception message, status 500; phone and card there as
the real-shaped sentinels above), user id,
`X-Forwarded-For`, token shapes, Statement literals, the four XSS payloads in eight places
(service path, query string, header, user id, SQL, exception message, thread name, ext-stub call
path) and the four CSV formulas. `--catalog batch` (`BatchCatalog`, shop-batch): the
four XSS payloads as a Spring Batch job name and bind parameter (`POST /seed/xss-batch?i=0..3`).
The script wraps the smoke runner (`--catalog <c> --only seed. --repeat N`), so the list is not
copied here. Each answer must have its expected status and contain the sentinel (the phone and card
exception seeds: their real-shaped sentinel); `GET /seed/env`
of the shop apps reports which secret-looking variables of the app process carry it and never
echoes them (shop-batch has no `/seed/env`: with `--catalog batch` that check is skipped and the
script says so).

The environment seeds (`DB_PASSWORD`, `AWS_SECRET_ACCESS_KEY`, `ARGOS_TOKEN`, `JAVA_TOOL_OPTIONS`;
`Seeds.environment()` in `sample-apps/common`) belong to the app process: `--start` sets them; for
an app started elsewhere, start it with them (smoke runner `--seed-env`, the agent test harness).
With `--catalog shop` the script prints how many of the four the app has.

`--set pii|xss|all` (default `all`, every seed as before) narrows the run: `pii` calls the PII and
secret seeds only (`seed.email` to `seed.literals` and `seed.env`, shop catalog), `xss` the XSS and
CSV seeds (`seed.xss.*`, `seed.csv.*`; with `--catalog batch` every batch seed). The PII seed grep
(`PiiSeedGrepSecurityTest`, T-NFR-10c, `./gradlew :server:securityTest --tests '*PiiSeedGrep*'`)
sends the `pii` set through a real agent and requires every leak sentinel (`ARGOS_SEED`,
`ARGOSSEED` and the three real-shaped values) to be absent from every ClickHouse string column, the
PostgreSQL dump, the server and agent logs, the alert inboxes and every CSV export; as a masking
control, the seed app's error groups must hold the phone and card exception seeds as `<phone>` and
`<card>`. 10,000 requests by default (nightly and release gate, N-10c); the PR job
`security-light` passes `-Psecurity.pii.requests=2000`. It reads the `pii_prefixes` line of this script, so the set exists
once. The XSS and CSV seeds are stored by design (service path, thread name, external call path,
job name) and are checked by T-SEC-62 (UI) and T-SEC-63 (CSV) instead.

```bash
test/seed/seed-traffic.sh --url http://localhost:8080 --set pii --repeat 100
```

Options: `--catalog shop|batch` (default `shop`), `--set all|pii|xss` (default `all`), `--report-dir` (default
`build/reports/seed-traffic`: `smoke.json`, `smoke.md`), `--smoke-jar`, `--java` (17+),
`--app-jar`/`--app-java` (with `--start`; default jar `shop-boot3`, or `shop-batch` with
`--catalog batch`), `--no-build`.
Exit code 0: every seed call as expected; 1: a seed call failed; 2: usage error or missing tool.

The automated check is `./gradlew :sample-apps:load:integrationTest` (`ShopBoot3TrafficTest`):
it starts shop-boot3 with the seed environment, asserts the sentinel in every seed answer, that
the XSS seeds of external calls reach ext-stub, and runs this script with `--repeat 2`. The light
integration tier runs one sample app (docs/18 §2.1: shop-boot3 x JDK 17), so `--catalog batch` is
not part of it; shop-batch's seed endpoints are called (once each, sentinel checked) by
`./gradlew :sample-apps:shop-batch:sampleSmoke`.
