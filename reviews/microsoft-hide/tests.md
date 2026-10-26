# Test Report

Date: 2026-10-26

Task: microsoft-hide - Hide Microsoft integration behind a setting (default off)

Scope: task commits a8afc79e1, 44723ad45, 6abc50ec8 on top of immutable base 2954a5299a3fcd1ce1fea73242f0f645254a3401 (`git diff 2954a5299a3f..6abc50ec854f675755153e743c0d0377bfc5a152`), measured on branch `main` at HEAD 6abc50ec854f675755153e743c0d0377bfc5a152 plus the uncommitted test changes listed below. Task ownership: base and commit list supplied by the parent worker; checked against the task's acceptance criteria and `touches`.

Requirements:
- `tasks/microsoft-hide.md` acceptance criteria and recorded assumptions
- `docs/adr/0003-microsoft-deferred.md`
- `docs/ARCHITECTURE.md` "Coverage", `AGENTS.md`

## Test files changed

- `server/src/test/kotlin/ch/sonensei/cockpit/core/oauth/MicrosoftAvailabilityTests.kt` (new)
- `server/src/test/kotlin/ch/sonensei/cockpit/core/oauth/MicrosoftDisabledWebTests.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/calendar/infra/microsoft/MicrosoftCalendarSyncTests.kt`
- `web/src/app/features/settings/calendars/calendars-settings.spec.ts`
- `web/src/app/features/settings/mail/mail-settings.spec.ts`
- `web/src/app/features/settings/integrations/integrations-settings.spec.ts`

## Tests added or updated

Count: 11. That is 8 new server tests, 1 new web test, and 2 existing web tests with added assertions.

Server (new):
- `MicrosoftAvailabilityTests` (plain unit test, no Spring context):
  - `Google is always allowed, Microsoft only while enabled`
  - `require refuses Microsoft while off with a 400 naming the field` (status, detail "Microsoft is not enabled", field error)
  - `require passes Google while off and every provider while on`
  - `the shipped configuration keeps Microsoft off unless MICROSOFT_ENABLED is true` (resolves the main `application.properties` with no env, `MICROSOFT_ENABLED=true` and `MICROSOFT_ENABLED=false`)
- `MicrosoftDisabledWebTests`:
  - `other sources still sync on schedule while Microsoft sources are skipped` (an ICS source on a loopback URL is attempted by `syncDue`: the private-address guard refuses it before any network access and the run is recorded. The Microsoft source is not attempted, so `syncDue` returns 1.)
  - `Google calendar sources and mail accounts are still accepted` (the gate only blocks Microsoft)
  - `an existing Microsoft mail account can still be changed` (PATCH with the Microsoft connection returns 200. Leaving updates ungated is intentional, see the task assumption.)
- `MicrosoftCalendarSyncTests`:
  - `the scheduler syncs a Microsoft source while Microsoft is enabled` (setting on: `syncDue` picks up the Microsoft source, which syncs against WireMock Graph)

Web:
- New: `IntegrationsSettings` `shows no connected calendars when the only one is Microsoft and Microsoft is off`
- Strengthened: `CalendarsSettings` `still loads when the OAuth providers cannot be read, ...` now also asserts that the kinds are exactly Local/CalDAV/ICS feed/Google (no Microsoft when the providers request fails)
- Strengthened: `MailSettings` `still lists the accounts when providers and connections cannot be read` now also asserts that the types are exactly IMAP/SMTP and Google

Tests the task already had, reviewed and kept: `MicrosoftDisabledWebTests` (setting read as off, providers leave Microsoft out, Microsoft start 400 and Google start passes the gate, Microsoft calendar source 400, Microsoft mail account 400, existing Microsoft source skipped by `syncDue` and refused by manual sync), `MailPushServiceTests` (2 tests: Microsoft accounts not watched or polled while off, and watched and polled while on), and the web specs in settings/{calendars,mail,integrations}. With the setting on, existing behavior stays covered by the existing Microsoft suites: `OAuthConnectionsWebTests` providers lists MICROSOFT, the `OAuthFlowWebTests`/`OAuthWebTestBase` Microsoft connect flows, `CalendarSourceCreateTests` Microsoft source, `MailAccountWebTests` Microsoft account, `MicrosoftCalendarSyncTests`, and the `MicrosoftInvitation*` tests.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: setting off by default (`MICROSOFT_ENABLED`, default false) | `MicrosoftAvailabilityTests.the shipped configuration keeps Microsoft off unless MICROSOFT_ENABLED is true`; `MicrosoftDisabledWebTests.the setting is read as off` | `cd server && ./gradlew test --tests '*MicrosoftAvailabilityTests' --tests '*MicrosoftDisabledWebTests'` | Not run by test-writer: sub-agents cannot run Gradle here; the parent must run it |
| AC1: Microsoft OAuth start -> 400 "Microsoft is not enabled" (Problem Details) | `MicrosoftDisabledWebTests.starting a Microsoft connection is refused, Google still starts`; `MicrosoftAvailabilityTests.require refuses Microsoft while off ...` | same | Not run by test-writer (Gradle); the parent reported that the pre-existing `MicrosoftDisabledWebTests` pass |
| AC1: MICROSOFT calendar source / mail account -> 400 | `MicrosoftDisabledWebTests.a Microsoft calendar source is refused`, `.a Microsoft mail account is refused` | same | Not run by test-writer (Gradle); the parent reported that the pre-existing tests pass |
| AC1: gate is Microsoft-only (Google still accepted) | `MicrosoftDisabledWebTests.Google calendar sources and mail accounts are still accepted`, `.starting a Microsoft connection is refused, Google still starts`; `MicrosoftAvailabilityTests.require passes Google ...` | same | Not run (Gradle) |
| AC1: available providers leave Microsoft out | `MicrosoftDisabledWebTests.providers leaves Microsoft out` | same | Not run by test-writer (Gradle); the parent reported a pass |
| AC1: Microsoft sync jobs don't run (calendar scheduler, mail push/poll) and other sources still sync | `MicrosoftDisabledWebTests.an existing Microsoft source is neither synced by the scheduler nor on demand`, `.other sources still sync on schedule while Microsoft sources are skipped`; `MailPushServiceTests.Microsoft accounts are neither watched nor polled while Microsoft is not enabled` | same plus `--tests '*MailPushServiceTests'` | Not run by test-writer (Gradle); the parent reported that `MailPushServiceTests` passes |
| AC1: setting on -> behavior unchanged | `OAuthConnectionsWebTests.providers tells which OAuth apps are configured`; `MicrosoftCalendarSyncTests.the scheduler syncs a Microsoft source while Microsoft is enabled`; `MailPushServiceTests.Microsoft accounts are watched and polled while Microsoft is enabled`; existing Microsoft suites | `cd server && ./gradlew test --tests '*MicrosoftCalendarSyncTests' --tests '*OAuthConnectionsWebTests' --tests '*MailPushServiceTests'` | Not run by test-writer (Gradle) |
| Assumption: existing Microsoft mail account stays editable | `MicrosoftDisabledWebTests.an existing Microsoft mail account can still be changed` | `--tests '*MicrosoftDisabledWebTests'` | Not run (Gradle) |
| AC1: `api/openapi.yaml` regenerated | not a behavior test; covered by `npm run api:check` inside `npm run check` | parent's `sh scripts/check.sh` | Not run by test-writer |
| AC2: Web calendars settings show no Microsoft when the server leaves it out (and when the providers can't be read) | `CalendarsSettings` `offers no Microsoft calendar while the server does not offer Microsoft`, `offers the Microsoft calendar while the server offers Microsoft`, `still loads when the OAuth providers cannot be read, ...` | `cd web && npx ng test --watch=false --include='src/app/features/settings/**/*.spec.ts'` | Pass (19 files, 468 tests) |
| AC2: Web mail settings show no Microsoft option | `MailSettings` `offers no Microsoft account while the server does not offer Microsoft`, `offers a Microsoft account while the server offers Microsoft`, `still lists the accounts when providers and connections cannot be read` | same | Pass |
| AC2: Web integrations page hides Microsoft sources and connections | `IntegrationsSettings` `hides Microsoft sources and accounts while the server does not offer Microsoft`, `hides Microsoft when the providers cannot be read`, `shows no connected calendars when the only one is Microsoft and Microsoft is off`, existing Microsoft-shown tests (`shows a source that cannot be synced yet ...`, `shows a failed token refresh ...`) | same | Pass |
| AC2: Android shows no Microsoft option | none | n/a | Not testable: Android has no OAuth or Microsoft UI (task assumption), so there is nothing to hide or test |
| AC2: `cd web && npm run check` passes | n/a | the parent's `.taskcheck` | Not run by test-writer (only the ng test, coverage and eslint parts) |
| AC3: no Microsoft code, enum or migration deleted; existing Microsoft tests pass with the setting on | diff inspection: no deletions of Microsoft code, enum values or migrations (`git diff --stat` shows only additions/edits); test config sets `cockpit.microsoft.enabled=true` | `git diff 2954a5299a3f..HEAD --stat` | Inspected: no deletions. Suite run is the parent's |
| AC3: docs and `.env.example` say Microsoft is deferred | doc content, not testable by unit tests | n/a | Not testable (documentation) |

## Focused test execution

Command:

`cd web && npx ng test --watch=false --include='src/app/features/settings/**/*.spec.ts'`

Result:

Pass: 19 test files, 468 tests (the 467 the parent reported plus the 1 new test).

`cd web && npx eslint` on the 3 changed spec files: Pass, no output. `npx prettier --write` left them unchanged.

Server focused tests (to be run by the parent, because sub-agents cannot run Gradle):

`cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.oauth.MicrosoftAvailabilityTests' --tests 'ch.sonensei.cockpit.core.oauth.MicrosoftDisabledWebTests' --tests 'ch.sonensei.cockpit.calendar.infra.microsoft.MicrosoftCalendarSyncTests' --tests 'ch.sonensei.cockpit.mail.sync.MailPushServiceTests' --tests 'ch.sonensei.cockpit.core.oauth.OAuthConnectionsWebTests'` plus `./gradlew ktlintCheck`

Result:

Not run by the test-writer. Gradle is blocked for sub-agents in this environment. The new server tests have not been executed yet.

## Full test execution

Command:

`cd web && npm run coverage` (the full web Vitest suite with coverage)

Result:

Pass: 175 test files, 3672 tests. The server and Android suites were not run by the test-writer (Gradle blocked). The parent is running `sh scripts/check.sh`.

## .taskcheck

Result:

Not run by the test-writer. The parent is running `sh scripts/check.sh` in the background. It must be rerun after these test changes, because the new server tests were written after that run started.

## Bugs found

No production defects found. Observations below are for the parent to judge. They are not defects against the acceptance criteria:

- Existing MICROSOFT mail accounts can still use the manual endpoints `POST /api/v1/mail/accounts/{id}/test` and `/folders/sync` while Microsoft is off. Manual calendar "Sync now" of a Microsoft source is refused with 400. The AC only requires that sync jobs don't run, so this is consistent with the AC, but the two behave differently.
- `web/src/app/features/contacts/contacts-page.html:55` still says "Connect a Google or Microsoft account to sync them." The AC lists only the settings pages, so this is outside the task scope.

## Coverage

Status: partial. Web is verified. Server coverage is unavailable: JaCoCo runs through Gradle, which is blocked for sub-agents here. Android is not applicable (no Android production files changed).

Metric: whole-file coverage of task-changed production files.
Changed-line coverage, web only: V8 statements and branches whose source-mapped start line falls on an added or modified line of the task diff (`git diff -U0 2954a5299a3f..HEAD`). Server changed-line coverage was not measured.

Evidence:
- Task id `microsoft-hide`. Immutable base 2954a5299a3fcd1ce1fea73242f0f645254a3401. Measured HEAD 6abc50ec854f675755153e743c0d0377bfc5a152. Task-owned commits a8afc79e1, 44723ad45, 6abc50ec8, as supplied by the parent.
- Source/test snapshot: HEAD 6abc50ec8 plus uncommitted test changes. `git diff | sha256sum` = a3e354a687ae79ca01e413b93c1ec544a832efe3737245771363950532c640d8 (identical before and after the coverage run). Untracked `MicrosoftAvailabilityTests.kt` sha256 = a53ec775ea2d7e9eba4c03735487a9d3c22cadb24121e0b3942cbd3addd0d385. No production files are modified in the working tree.
- Included web files: `web/src/app/features/settings/{calendars/calendars-settings,integrations/integrations-settings,mail/mail-settings}.ts` and `.html`, plus `mail/identities.html` (a formatting-only change).
- Included server files (not measured): `core/oauth/domain/MicrosoftAvailability.kt`, `core/oauth/api/OAuthController.kt`, `calendar/sync/{CalendarSourceService,CalendarSyncService,CalendarSourceController}.kt`, `mail/imap/{MailAccountService,MailAccountController}.kt`, `mail/sync/MailPushService.kt`.
- Excluded: `server/src/main/resources/application.properties` (configuration, exercised by `MicrosoftAvailabilityTests`), `api/openapi.yaml` and `web/src/app/api/generated` (generated), and `docs/`, `deploy/` (docs and configuration).
- Web: provider Vitest 5.0.5 with `@vitest/coverage-v8`. Command `npm run coverage`, working directory `web/`, all specs. Run started 2026-10-26 09:48:46 +01:00, reports written 09:49:47 +01:00. Exit status: the run reported all 175 files and 3672 tests passed (the exact exit code was hidden by the output pipe).
- Reports: `web/coverage/web/coverage-final.json` (sha256 2137f6dfb99e22eab3e28fa84f123522d8249ba254883545f792751e077a6f83) and `web/coverage/web/coverage-summary.json` (sha256 d797fe7da4c20de56dcc0c2201379899694d065a11dfb1eef752abe1be7c55f2), rewritten by this run (file mtimes match the run).
- The outputs are fresh and the inputs stayed unchanged: yes (the diff hash matched before and after the run).
- Final run after the latest test additions: yes for web. Server: no, not run.

Web, values as covered/total (percentage):

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `web/src/app/features/settings/calendars/calendars-settings.ts` | 153/153 (100%) | 68/74 (91.89%) | 49/49 (100%) | 131/131 (100%) |
| `web/src/app/features/settings/integrations/integrations-settings.ts` | 85/86 (98.83%) | 45/49 (91.83%) | 22/22 (100%) | 73/73 (100%) |
| `web/src/app/features/settings/mail/mail-settings.ts` | 151/153 (98.69%) | 65/74 (87.83%) | 48/48 (100%) | 128/128 (100%) |
| `web/src/app/features/settings/calendars/calendars-settings.html` | 269/269 (100%) | 41/42 (97.61%) | 11/11 (100%) | 167/167 (100%) |
| `web/src/app/features/settings/integrations/integrations-settings.html` | 142/142 (100%) | 27/27 (100%) | 3/3 (100%) | 87/87 (100%) |
| `web/src/app/features/settings/mail/mail-settings.html` | 361/361 (100%) | 34/34 (100%) | 16/16 (100%) | 237/237 (100%) |
| `web/src/app/features/settings/mail/identities.html` | 183/183 (100%) | 28/28 (100%) | 8/8 (100%) | 116/116 (100%) |
| Web aggregate (summed counters, 7 files) | 1344/1347 (99.78%) | 308/328 (93.90%) | 157/157 (100%) | 939/939 (100%) |

Web changed-line coverage: on changed lines, statements 38/38 and branches 17/17 are covered. By file, statements and branches: calendars ts 2/2 and 0/0, integrations ts 12/12 and 7/7, mail ts 2/2 and 0/0, calendars html 4/4 and 2/2, integrations html 6/6 and 2/2, mail-settings html 12/12 and 4/4.

The aggregate excludes the 8 unmeasured server files listed above. It is not task-wide coverage.

Server: unavailable. To measure it, the parent should run `cd server && ./gradlew test jacocoTestReport` and read `server/build/reports/jacoco/test/jacocoTestReport.xml` for the 8 server files listed above. No server numbers are reported, and no earlier or repository-wide numbers are used in their place.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `integrations-settings.ts:112` (statement and branch), branches 54[0], 117[1], 211[1] | Lines the task did not change (`syncNow` double-click guard, defaults) | Pre-existing and outside the task's changed lines; no test added |
| `mail-settings.ts:163, 345` and branches 79, 196, 239, 299, 331, 364, 368 | Lines the task did not change | Pre-existing; no test added |
| `calendars-settings.ts` branches 56, 95, 133, 293, 309, 352; `calendars-settings.html:116[1]` | Lines the task did not change | Pre-existing; no test added |
| All 8 server files | AC1 server gating | Tests written (see above); coverage unmeasured because Gradle is blocked; the parent should run JaCoCo |

## Unable to test

- Server tests: written but not executed by the test-writer, because Gradle is blocked for sub-agents. The parent must run the classes listed under "Focused test execution" plus `./gradlew ktlintCheck` before relying on them.
- Server JaCoCo coverage: not measured, for the same reason. Follow-up: the parent runs `./gradlew test jacocoTestReport`.
- Android: no OAuth or Microsoft UI exists, so there is nothing to test (task assumption).
- `docs/oauth-setup.md`, `deploy/.env.example`: documentation, not unit-testable.

## Summary

Tests are added and no production defects were found. The web tests pass (the settings specs and the full web suite with coverage). The new and changed server tests (`MicrosoftAvailabilityTests`, `MicrosoftDisabledWebTests`, `MicrosoftCalendarSyncTests`) have not been run yet and need a Gradle run by the parent, as does server coverage.
