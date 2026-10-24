# test/seed

Seed traffic for the privacy and XSS tests (docs/18 §4.3, DSN-18-09).

Every seed value contains the sentinel `ARGOS_SEED`, so a single `LIKE '%ARGOS_SEED%'` (or `grep`)
finds a leak anywhere. Documented exceptions (`Seeds.java`): the `*_PATTERN` values (realistic
shapes for the masking pattern tests) and the access key shape `AKIAARGOSSEED0000001` (the docs/18
§4.3 value, sent in the `X-Api-Key` header), which carries `ARGOSSEED` without the underscore: leak
scans must also search for `ARGOSSEED`. `seed-traffic.sh` calls every seed endpoint of the shop
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
shapes (email, phone and card also in an exception message, status 500), user id,
`X-Forwarded-For`, token shapes, Statement literals, the four XSS payloads in eight places
(service path, query string, header, user id, SQL, exception message, thread name, ext-stub call
path) and the four CSV formulas. `--catalog batch` (`BatchCatalog`, shop-batch): the
four XSS payloads as a Spring Batch job name and bind parameter (`POST /seed/xss-batch?i=0..3`).
The script wraps the smoke runner (`--catalog <c> --only seed. --repeat N`), so the list is not
copied here. Each answer must have its expected status and contain the sentinel; `GET /seed/env`
of the shop apps reports which secret-looking variables of the app process carry it and never
echoes them (shop-batch has no `/seed/env`: with `--catalog batch` that check is skipped and the
script says so).

The environment seeds (`DB_PASSWORD`, `AWS_SECRET_ACCESS_KEY`, `ARGOS_TOKEN`, `JAVA_TOOL_OPTIONS`;
`Seeds.environment()` in `sample-apps/common`) belong to the app process: `--start` sets them; for
an app started elsewhere, start it with them (smoke runner `--seed-env`, the agent test harness).
With `--catalog shop` the script prints how many of the four the app has.

Options: `--catalog shop|batch` (default `shop`), `--report-dir` (default
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
