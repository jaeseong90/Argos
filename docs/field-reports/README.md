<!-- SPDX-License-Identifier: Apache-2.0 -->

# Field reports

A field report is a short, anonymized summary of running Argos APM on real applications: how the
rollout went, what the agent cost, what broke and what was not verified. The maintainers use the
reports of their own dogfooding as evidence for release gates (the dogfooding row of Gate 1 for
the first 0.x pre-release), and anyone can read them to judge whether Argos fits their systems.

## What goes here and what does not

- **Here:** public, anonymized summaries only, one file per report, written from
  [TEMPLATE.md](TEMPLATE.md).
- **Not here:** the operating records themselves. Daily checklists, issue logs, comparison
  measurements, acceptance records, logs, screenshots and diagnostic bundles of in-house systems
  live in the private repository `<PRIVATE-RECORDS-REPO>`. A field report never links to that
  repository, to its files or to any internal system; it states results in its own words.
- **Not here either:** anything about sales, prices, customers or business results.

How the dogfooding itself runs (pre-flight check, registration, canary, comparison, expansion,
rollback, daily checks) is in [docs/dogfooding/README.md](../dogfooding/README.md).

## File name

`YYYY-MM-{n}.md`: the year and month the reported period ended and a sequence number within that
month, starting at 1 (for example `2026-12-1.md`). Do not put a system, team or organization name
in the file name.

## Before publishing: anonymization checklist

Go through every item for the report text, its tables and anything attached. When in doubt, leave
it out.

- [ ] No company, organization, team or department names.
- [ ] No product, service, application or system names of the operator. Call the systems
  "system A" and "system B".
- [ ] No host names, instance names, container names, cluster names or Kubernetes namespaces.
- [ ] No IP addresses, subnets or port numbers of internal services.
- [ ] No domains, internal URLs, URL paths of the applications or repository addresses.
- [ ] No people: names, user ids, e-mail addresses, chat handles or initials. Roles only
  ("an operator", "an application owner").
- [ ] No customers or partners of the operator, and no data about them.
- [ ] No business data: no request contents, SQL text with values, table names that reveal the
  business, error messages with data, revenue or volume figures of the business.
- [ ] No screenshots that contain text from the applications or from Argos screens (service
  names, URLs, SQL, user ids). Redraw a chart as a table of ranges instead.
- [ ] Traffic and size figures given as ranges, not exact numbers (for example "50 to 200
  transactions a second", "4 to 8 instances", "heap 1 to 2 GiB").
- [ ] Overhead given as ranges or relative values (for example "CPU +1 to +2 percentage
  points", "p95 within 1 ms"), with the method named, not raw measurements.
- [ ] Timestamps coarsened to the day or week; no times of day that match incidents or changes.
- [ ] Problems cited by public issue numbers only; no internal ticket ids or links.
- [ ] No agent tokens, passwords, certificates, configuration files or log excerpts. A setting is
  named by its key only (for example `sampling.profile.normal-rate`), without the operator's value
  when that value reveals something.
- [ ] Software versions (JDK, Spring Boot, servlet container, database) are fine to give exactly;
  they are what other users need.

## Review and sign-off

A report is published only after two people have checked it: the author and a second maintainer
who did not write it. Both go through the checklist above independently and sign off at the end of
the report:

```text
Anonymization review 1: {maintainer role or public handle}, {YYYY-MM-DD}, checklist complete
Anonymization review 2: {maintainer role or public handle}, {YYYY-MM-DD}, checklist complete
```

Use a public project handle or a role, never a real name or a company e-mail address. The pull
request that adds the report carries the same two sign-offs in its description.

## Reports from outside the project

If you run Argos and want to share results, open a
[compatibility report](../../.github/ISSUE_TEMPLATE/compat-report.yml) for a library, database,
application server or JDK combination, or a regular issue for anything else. Follow the same
checklist before posting. The maintainers may summarize such reports here with your permission.
