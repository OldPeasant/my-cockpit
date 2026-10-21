# Test Report

Date: 2026-10-21

Task: web-cal-sources: Settings: calendars and integrations

Scope: base 3060f4b1b3c57308549774d15244fd89f57228cd .. HEAD 448c168c679630170c75eadf275cf58a93365100 (branch main, HEAD is a merge of origin/wip/web-cal-sources). The parent named the task commits: fdd28477, dfda0e45, b393254c, ec1a0ea4, 85a719ea, bfc71787, 08ab8780, a550bf69, fb41e72c. Other commits in the range only touch `tasks/` (claim/heartbeat/release). Uncommitted input: this test writer's change to `server/src/test/kotlin/ch/sonensei/cockpit/calendar/sync/CalendarSourceCreateTests.kt` (sha256 1a7dbd8828babd66e40e875f9ec25d3c24c7014e76e2729457e12176267ac4e3). No production file was modified by this run.

Requirements:
- tasks/web-cal-sources.md acceptance criteria and the recorded ASSUMPTIONs (POST /api/v1/calendar/sources, OAuth callback on /settings/integrations, reconnect via sessionStorage)
- SPEC.md §6.1, §6.4, §11 (as referenced by the task), §4.2 (Problem Details, If-Match)
- docs/ARCHITECTURE.md "Coverage"; AGENTS.md project notes
- Existing test conventions (CalendarWebTests / CalendarApi helpers, Vitest + HttpTestingController specs)

## Test files changed

- `server/src/test/kotlin/ch/sonensei/cockpit/calendar/sync/CalendarSourceCreateTests.kt`

## Tests added or updated

Count: 2

- `a new source is announced once, an already linked or refused one is not`: a created source publishes exactly one `CALENDAR_SOURCE` CREATED change event, so clients see it (SPEC sync/change events). Re-adding an already linked OAuth connection (200) and a refused request (400) publish nothing. The class now records application events (`@RecordApplicationEvents`).
- `adding a source needs a login`: an anonymous POST gets 401 and creates nothing.

I reviewed the existing tests for the task: 18 server, 11 service, 30 Calendars component, 23 Integrations component and 2 route tests. They cover the forms, field validation, server field errors, the OAuth redirect and its return, reconnect, sync status rendering, "Sync now", stale and out-of-order answers, double submit, and failed reloads. I found no other important gap, so no web tests were added.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 Add local calendar | `adds a local calendar`, `adds a local calendar in the chosen color, then offers the start color again`, `shows the reason when a local calendar is refused and keeps its name`, `reports an add that worked as added when the list cannot be read again` | `npm run coverage` (web) | Pass |
| AC1 CalDAV (URL, user, app password) | web: `connects a CalDAV account, then fetches its calendars`, `refuses a webcal URL for CalDAV...`, `trims the CalDAV fields...`, `keeps the form and shows the reason when a CalDAV account is refused`, `reports a CalDAV account whose first sync failed`, `sends a source only once while it is being added`; server: `a CalDAV source stores URL and user in its config and the password encrypted...`, `a CalDAV source needs an http URL, a user and a password`, `a CalDAV source refuses a blank user...`, `a CalDAV user name is trimmed but the password is kept as typed`, `a create request does not print its password or URL...` | web: `npm run coverage`; server: `./gradlew ktlintCheck test --tests 'ch.sonensei.cockpit.calendar.*' jacocoTestReport` | Pass |
| AC1 ICS URL | web: `subscribes to an ICS feed and shows field errors of the server`; server: `an ICS feed takes a webcal URL and an own name`, `an ICS feed accepts webcals, refuses credentials in the URL...`, `a given name is trimmed...`, `a host longer than a name is cut...` | same as above | Pass |
| AC1 Google/Microsoft OAuth redirect flow | service: `leaves for the consent screen of Google or Microsoft with purpose CALENDAR`, `puts the API root in front of the consent URL`, `remembers a reconnect...`, `still leaves for the consent screen when the browser blocks sessionStorage`; component: `connects Google through the OAuth redirect, and explains an unconfigured Microsoft`, `connects Microsoft through the OAuth redirect...`; integrations: `turns a connected Google account into a calendar source and clears the URL`, `reports an OAuth error from the redirect`, `does not turn a reconnected account into a calendar source`, `only confirms connections made for mail or contacts`, and 6 more; server: `a Google source links a Google connection once...`, `a second Google account becomes its own source...`, `a Microsoft source without email...`, `an OAuth source refuses URL, user and password...` | same as above | Pass. The real provider consent round trip was not run: it needs real OAuth apps. |
| AC1 colors, visibility, default calendar | `changes the color with If-Match`, `shows or hides a calendar`, `makes a calendar the default and reloads...`, `does not save a color that did not change`, `disables a calendar while its change is saved and reports a failed save`, `explains a concurrent change and shows the current state`, `keeps a change saved while a reload was on its way...`, `keeps a newer state from a reload when a change answers after it`, `drops a reload that answers after a newer one` | `npm run coverage` | Pass |
| AC2 Integrations: per source last sync, error, 'sync now' | `shows per external source its type, last sync, error and next sync`, `shows a source whose syncs never succeeded with Never...`, `syncs a source now and shows its new status`, `shows a sync request that failed`, `syncs two sources at once and applies answers that arrive in reverse order`, `shows a source that cannot be synced yet without a Sync now button`, `shows a load error with a retry`, `still lists the sources when the accounts cannot be read` | `npm run coverage` | Pass |
| AC3 Vitest tests cover source forms and status rendering; `cd web && npm run check` passes | all web specs above, plus `settings.routes.spec.ts` (`opens Calendars at /settings/calendars`, `opens Integrations at /settings/integrations`) | `cd web && npm run check` | Pass |
| Server: new source announced as change event (added) | `a new source is announced once, an already linked or refused one is not` | server focused command above | Pass |
| Server: endpoint requires login (added) | `adding a source needs a login` | server focused command above | Pass |
| Server: LOCAL / unknown type / malformed id refused | `LOCAL and missing types are refused`, `an unknown type and a malformed connection id are refused as bad requests` | server focused command above | Pass |

## Focused test execution

Command:

`cd server && ./gradlew ktlintCheck test --tests 'ch.sonensei.cockpit.calendar.*' jacocoTestReport`

Result:

Pass. BUILD SUCCESSFUL in 1m 30s, 850 tests, 0 failures/errors/skips (`CalendarSourceCreateTests`: 20 tests, 0 failures). ktlint clean.

Command:

`cd web && npm run coverage`

Result:

Pass. 126 test files, 2563 tests passed.

## Full test execution

Command:

`cd web && npm run check` (api:check, lint, e2e:typecheck, test:ci, ng build)

Result:

Pass. The `&&` chain reached a successful `ng build`. The bundle initial budget warning (658 kB vs 500 kB) is not new and does not come from this task: the settings sections are lazy chunks.

## .taskcheck

Result:

Fail: the cause is outside this task. `sh scripts/check.sh` (21:04:38 to 21:11:09, server part BUILD FAILED in 6m 20s): 3612 tests, 1 failure, in `ch.sonensei.cockpit.calendar.infra.ics.IcsFeedClientTests` "a feed of exactly the maximum size is read, a larger Content-Length is refused before reading" (got "The feed server could not be reached (IOException)" instead of "larger than"). This is the flaky cal-ics test the parent already recorded. It passed in the focused `calendar.*` run above (same code, 21:03). `CalendarSourceCreateTests` passed in this run as well (20/20). Because the server step failed, check.sh may not have run the later parts, but the web check ran on its own and passed. The parent must run `.taskcheck` again before `done`. The check also exceeds the 2 min warning (about 6.5 min).

## Bugs found

None in the task's production code.

## Coverage

Status: verified for the server and web task files (fresh runs, command exit 0). This is whole-file coverage of the task-changed production files.

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured. Every line of the task's web .ts and .html files is covered, and the new server files are 100% line-covered, so changed-line coverage of those files is 100% for lines by implication. `CalendarSourceController.kt` existed before; the whole file is 78/78 lines covered.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: web-cal-sources; base 3060f4b1b3c57308549774d15244fd89f57228cd; HEAD 448c168c679630170c75eadf275cf58a93365100. The parent supplied the commit list; scope is `git diff --name-only 3060f4b1..HEAD -- server/src/main web/src android/app/src/main` without `*.spec.ts`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD 448c168c plus the uncommitted `CalendarSourceCreateTests.kt` (sha256 1a7dbd88...ac4e3). The web run happened before this server test edit; no web file changed afterwards. The server run happened after the edit.
- Exact included files: `server/.../calendar/sync/CalendarSourceController.kt`, `server/.../calendar/sync/CalendarSourceService.kt`, `web/src/app/features/settings/calendars/calendar-sources.service.ts`, `web/src/app/features/settings/calendars/calendars-settings.ts` (+ `.html` template), `web/src/app/features/settings/integrations/integrations-settings.ts` (+ `.html` template), `web/src/app/features/settings/settings.routes.ts`. Excluded: `web/src/app/api/generated/**` (generated by ng-openapi-gen), `*.scss` (styles, no behavior), `web/src/app/features/settings/calendars/testing.ts` (test fixtures), `api/openapi.yaml` (generated). No renames or deletions.
- Provider/version, command, working directory, and test selection: web: Vitest + @vitest/coverage-v8 5.0.5, `npm run coverage`, run in `web/`, full web suite. Server: JaCoCo (Gradle 9.8.1 `jacoco` plugin with its default tool version, not pinned), `./gradlew ktlintCheck test --tests 'ch.sonensei.cockpit.calendar.*' jacocoTestReport`, run in `server/`, selection `ch.sonensei.cockpit.calendar.*` (850 tests).
- Run start/completion time and exit status: web 20:59:09 to 21:00:04, exit 0. Server about 21:01:47 to 21:03:17, BUILD SUCCESSFUL.
- Generated report paths and report hashes/run identifier: `web/coverage/web/coverage-final.json` sha256 f9bc5cc78e9dc9f145acd1992522efb97a94553088b25bf90d23d4126498a8e3; `web/coverage/web/coverage-summary.json` sha256 7fed99b715fc35f585e2f9369226b98dd56dfc7cb0a42790e6453d5255064891; `server/build/reports/jacoco/test/jacocoTestReport.xml` sha256 7a23ae02ca86716b3c2e34ae7f9606303a46cb519e9a117b63b035a020bcc79f (written 21:03:17).
- Evidence outputs are fresh and inputs stayed unchanged: yes. Vitest rewrote `coverage/` (files dated 21:00). The JaCoCo XML was written by this run, and `CalendarSourceCreateTests` ran in it (20 tests, report timestamp 19:03:09Z). Inputs did not change during either run.
- Final run after latest test additions and production fixes: yes. The server run includes the new tests. The web run reflects HEAD; no web tests or production code changed after it.

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A. JaCoCo has no statement counter, so the server "Statements" column shows the INSTRUCTION counter.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `server/.../calendar/sync/CalendarSourceService.kt` | 814/833 (97.72%) instr. | 106/110 (96.36%) | 16/16 (100%) | 83/83 (100%) |
| `server/.../calendar/sync/CalendarSourceController.kt` | 376/394 (95.43%) instr. | 18/20 (90.00%) | 33/33 (100%) | 78/78 (100%) |
| `web/.../calendars/calendar-sources.service.ts` | 35/35 (100%) | 6/7 (85.71%) | 16/16 (100%) | 31/31 (100%) |
| `web/.../calendars/calendars-settings.ts` | 151/151 (100%) | 68/74 (91.89%) | 47/47 (100%) | 130/130 (100%) |
| `web/.../calendars/calendars-settings.html` | 267/267 (100%) | 39/40 (97.50%) | 11/11 (100%) | 165/165 (100%) |
| `web/.../integrations/integrations-settings.ts` | 76/77 (98.70%) | 40/44 (90.91%) | 17/17 (100%) | 65/65 (100%) |
| `web/.../integrations/integrations-settings.html` | 142/142 (100%) | 27/27 (100%) | 3/3 (100%) | 88/88 (100%) |
| `web/.../settings/settings.routes.ts` | 9/9 (100%) | N/A (0/0) | 8/8 (100%) | 9/9 (100%) |

Aggregates (summed counters; exclusions as listed above, no scoped file missing from the reports):
- Server: instructions 1190/1227 (96.98%), branches 124/130 (95.38%), methods 49/49 (100%), lines 161/161 (100%).
- Web (.ts + .html): statements 680/681 (99.85%), branches 180/192 (93.75%), functions 102/102 (100%), lines 488/488 (100%).

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `CalendarSourceService.kt:118` (`runCatching` / `?.asString()` in `linked`) | Reading a stored source config that is not valid JSON | Not reachable: `calendar_source.config` is `jsonb` with a check for `jsonb_typeof = 'object'`. I tried a test and dropped it because the database refuses such a row. |
| `CalendarSourceService.kt:137` (`uri.scheme?.`) | A URL with a host but no scheme | Defensive: `java.net.URI` gives no host without a scheme. Scheme refusals (ftp, webcal for CalDAV) are tested. |
| `CalendarSourceService.kt:95` (2 branches) | Default name fallback chain | Branches inside `replaceFirstChar` (an already uppercase first letter). The input is always "google"/"microsoft". All three fallbacks (email, displayName, provider name) are tested. |
| `CalendarSourceService.kt:24-26` | Data class default arguments | Generated bytecode; no behavior. |
| `CalendarSourceController.kt:138-139` (`checkNotNull` on createdAt/updatedAt) | Persisted entities always have audit timestamps | Defensive; not reachable. |
| `calendar-sources.service.ts:128` (`?? undefined`) | No `defaultView` (no window) | Not reachable in a browser. A blocked sessionStorage is tested. |
| `calendars-settings.ts` 6 branches (`?? []`/`?? null` while calendars are null, `current()` fallback, `message()` fallback for a non-ApiError) and `.html:114` (`@switch` with no matching case) | Defensive fallbacks | Every HTTP failure arrives as `ApiError` through the interceptor, so the non-ApiError text needs a thrown non-HTTP error. I left it untested on purpose; adding a test would only chase the number. |
| `integrations-settings.ts:92` (`if (syncing.has(id)) return`) | A second "Sync now" for a source that is already syncing | The button is disabled while syncing, and the test `syncs two sources at once...` asserts that a second click sends nothing. The guard is only a second layer. |
| `integrations-settings.ts:97, :193, :50` | `?? null`, the non-ApiError message fallback, a compiled class-field branch | Defensive, as above. |

## Unable to test

- The real Google/Microsoft OAuth consent round trip: it needs registered OAuth apps and real accounts. The redirect URL and the callback handling are covered with fakes. Follow-up is only needed if the parent's criteria call for an E2E run.
- A real CalDAV/ICS first sync from the Settings page against live servers: the component tests cover it with HTTP fakes. Adapter behavior belongs to the cal-caldav/cal-ics tasks.
- Playwright E2E: the criteria don't name it, so it was not run.

## Summary

Tests and task-scoped coverage are sufficient; no production defects found. I added 2 server tests (change event, login required). All focused suites and `cd web && npm run check` pass. `.taskcheck` failed only on the known flaky `IcsFeedClientTests` max-size test (cal-ics, outside this task), which passes when run in the focused calendar suite. The parent must run `.taskcheck` again before `done`.
