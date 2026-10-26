# Test Report

Date: 2026-10-26

Task: web-and-birthday-occurrences - Clients: open birthday (virtual) occurrences read-only

Scope: `git diff e15d16d3330eb4c686125997e74eb5cbb5d0476f..c63c35ae429f8b6a67c391a5350397b0d007d018 -- web android`. The task commits are:
- 5bfafbc66 (web)
- 865d998e4 (android)
- 41d49ccdc (tests from an earlier test-writer run that stopped before writing its report)
- c63c35ae4 (`BirthdayContactTest`, written in this run and committed by the parent)

Branch main. The production code is the same in 865d998e4, 41d49ccdc and c63c35ae4: the last two commits change only test files.

Requirements:
- tasks/web-and-birthday-occurrences.md (acceptance criteria 1 to 3, notes, assumptions #1 virtual = readOnly + LOCAL, #2 Android shows the contact inside the Calendar tab)
- SPEC.md section 8.3 (virtual Birthdays calendar), api/openapi.yaml `OccurrenceResponse`/`CalendarResponse`
- docs/ARCHITECTURE.md "Coverage", docs/QUALITY.md, AGENTS.md
- reviews/web-and-birthday-occurrences/review.md (CR-1, CR-2)
- Parent instructions (scope, Gradle results reported by the parent)

## Test files changed

New in this run (the parent committed it as c63c35ae4):
- `android/app/src/test/java/ch/sonensei/cockpit/calendar/edit/BirthdayContactTest.kt` (sha256 277cf2be3c4bcf6c0bf9447a286486f8da1aaf5e94f8a4967f02eee7a5914374, the same before and after the commit)

Already in the task (commits 5bfafbc66, 865d998e4 and 41d49ccdc). I reviewed them and did not change them:
- `web/src/app/features/calendar/edit/event-dialog.spec.ts` ("birthdays (virtual calendar)": 4 tests)
- `web/src/app/features/calendar/edit/event-edit.service.spec.ts` (`isVirtual`: 4 tests)
- `android/app/src/test/java/ch/sonensei/cockpit/calendar/agenda/BirthdayOccurrenceTest.kt` (4 tests)
- `android/app/src/test/java/ch/sonensei/cockpit/calendar/agenda/CalendarRepositoryTest.kt` (+2 tests, virtual mapping)
- `android/app/src/test/java/ch/sonensei/cockpit/calendar/agenda/AgendaCacheTest.kt` (+1 test, virtual round trip)
- `android/app/src/test/java/ch/sonensei/cockpit/calendar/edit/CalendarWithEditorTest.kt` (+3 tests: agenda, day and week taps)
- `android/app/src/test/java/ch/sonensei/cockpit/calendar/edit/EventEditorViewModelTest.kt` (+3 tests)

## Tests added or updated

Count: 4 added in this run (`BirthdayContactTest`). The earlier task commits contain 21 birthday-related tests (8 web, 13 Android).

This run adds tests for review finding CR-2: no test ever rendered the real `BirthdayContact` composable. `BirthdayContactTest` renders it with a real `ContactDetailViewModel` and a mocked `ContactsRepository`:
- `it loads and shows the contact the birthday comes from`: `LaunchedEffect` calls `open(contactId)`, which calls `get`/`related` for the contact id once and shows the name.
- `its back button returns to the calendar`: `ContactsTags.BACK` calls `back`.
- `the system back returns to the calendar`: `BackHandler`, through `onBackPressedDispatcher.onBackPressed()`.
- `a contact that could not be loaded says so, and retry loads it again`: an offline load shows the banner. Retry is wired to `viewModel::refresh`, loads the contact again and shows it.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 Web: clicking a birthday shows title/date read-only, with no load error | `EventDialog > birthdays (virtual calendar) > shows title and date read-only without loading an event` | `cd web && npx ng test --watch=false --include src/app/features/calendar/edit/event-dialog.spec.ts --include src/app/features/calendar/edit/event-edit.service.spec.ts` (run by me), plus `npm run check` (run by the parent) | Pass (2 files, 69 tests) |
| AC1 Web: no edit/delete | `... > offers no edit or delete, only close` | same | Pass |
| AC1 Web: links to the contact | `... > links to the contact and closes the dialog when followed` (href `/contacts/contact-ada`, the dialog closes with `undefined`) | same | Pass |
| AC1 Web: only virtual calendars are affected (read-only feeds still load) | `... > still loads events of read-only calendars of other sources`; `isVirtual` (4 cases: LOCAL read-only, LOCAL writable, ICS_FEED/GOOGLE/MICROSOFT/CALDAV read-only, undefined) | same | Pass |
| AC2 Android: a tap in agenda/day/week does not load or edit an event | `CalendarWithEditorTest`: `a tapped birthday opens its contact without loading or editing an event, back returns to the views`, `in the day view ...`, `in the week view ...`; `EventEditorViewModelTest`: `a birthday is not opened and its event is never loaded`, `a birthday tapped while an event is open leaves that event's form as it was`, `an occurrence of a read-only calendar that is not virtual is still loaded` | `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug createDebugUnitTestCoverageReport` (run by the parent on c63c35ae4) | Pass (reported by the parent: BUILD SUCCESSFUL, 1938 tests, 0 failures/errors) |
| AC2 Android: recognizing a birthday (mapping, cache, contactId) | `BirthdayOccurrenceTest` (4); `CalendarRepositoryTest`: `only a read-only calendar of the local source (Birthdays) is virtual`, `the Birthdays calendar stays virtual after its visibility changes`; `AgendaCacheTest`: `the virtual Birthdays calendar reads back virtual, so a cached birthday still opens its contact` | same | Pass (reported by the parent) |
| AC2 Android: it opens the contact (the real screen loads that contact; back/system back return; retry) | `BirthdayContactTest`: `it loads and shows the contact the birthday comes from`, `its back button returns to the calendar`, `the system back returns to the calendar`, `a contact that could not be loaded says so, and retry loads it again` | same (run by the parent on c63c35ae4) | Pass (reported by the parent: 4 tests, 0 failures) |
| AC3 `npm run check` and the Android ktlint/test/lint command pass | the whole suites | `cd web && npm run check` (run by the parent on 41d49ccdc; web unchanged since); `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug createDebugUnitTestCoverageReport` (run by the parent on c63c35ae4) | Pass (web: exit 0, 174 test files; Android: BUILD SUCCESSFUL, 1938 tests) |

## Focused test execution

Command:

`cd web && npx ng test --watch=false --include src/app/features/calendar/edit/event-dialog.spec.ts --include src/app/features/calendar/edit/event-edit.service.spec.ts`

Result:

Pass: 2 test files, 69 tests (Vitest 5.0.5, 2026-10-26 05:33).

Android: I cannot run Gradle. The parent ran `BirthdayContactTest` as part of the full Android run on c63c35ae4 (below): 4 tests, 0 failures.

## Full test execution

Command:

`cd web && npm run check`; `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug createDebugUnitTestCoverageReport`

Result:

Pass, as reported by the parent:
- Web on 41d49ccdc: exit 0, 174 test files. c63c35ae4 changes no web file, so this result still applies.
- Android on c63c35ae4: BUILD SUCCESSFUL, 1938 tests, 0 failures/errors, including `BirthdayContactTest` (4 tests).

## .taskcheck

Result:

Not run by me (`sh scripts/check.sh` runs Gradle, which is not available to sub-agents). The parent runs it before `done`.

## Bugs found

None. Static reading of the changed code and the web test run found no behavior that breaks the acceptance criteria.

## Coverage

Status: verified. Android was measured fresh on c63c35ae4, which includes all task tests (`BirthdayContactTest` too). Web was measured on 41d49ccdc. c63c35ae4 changes no web file, so the web inputs are the same.

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: measured. I intersected the added lines of the task's production diff (`git diff e15d16d33..c63c35ae4 -- web android`; the production lines match those in `..41d49ccdc`) with the provider's per-line data (the JaCoCo line `ci` attribute, lcov `DA`), and counted only lines that the provider reports as executable. Results:
- web: `event-dialog.ts` 8/8, `event-edit.service.ts` 1/1
- Android: `AgendaCache.kt` 3/3, `CalendarModels.kt` 3/3, `CalendarWithEditor.kt` 11/11, `EventEditorViewModel.kt` 1/1, `BirthdayContact.kt` 18/21 (missed 30, 38, 39; see the gaps table)
- Total: 45/48 (93.8%)

Changed-branch coverage was not derived from this.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: web-and-birthday-occurrences; base e15d16d3330eb4c686125997e74eb5cbb5d0476f. Measured HEAD: Android c63c35ae429f8b6a67c391a5350397b0d007d018, web 41d49ccdc4670a55ab9844da63978094f5443ffb (the web code is the same at c63c35ae4). Ownership comes from the parent's commit list (5bfafbc66, 865d998e4, 41d49ccdc, c63c35ae4), matched against the task's `touches` and `git diff --stat`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD c63c35ae4 with a clean tracked tree. `git diff --stat 41d49ccdc..c63c35ae4` shows only `BirthdayContactTest.kt` (+111). Its sha256 277cf2be... is the same as the file I wrote. Untracked: only `reviews/web-and-birthday-occurrences/`.
- Exact included files: `web/src/app/features/calendar/edit/event-dialog.ts`, `web/src/app/features/calendar/edit/event-edit.service.ts`, `android/app/src/main/java/ch/sonensei/cockpit/calendar/agenda/AgendaCache.kt`, `.../calendar/agenda/CalendarModels.kt`, `.../calendar/edit/BirthdayContact.kt` (new), `.../calendar/edit/CalendarWithEditor.kt`, `.../calendar/edit/EventEditorViewModel.kt`. Excluded: `event-dialog.html` and `event-dialog.scss` (template and style; the providers do not instrument them, and the dialog spec tests the template), `android/app/schemas/.../AgendaCacheDatabase/2.json` (generated Room schema), and all test files. No renamed or deleted files.
- Provider/version, command, working directory, and test selection: the parent ran both.
  - Web: Vitest 5.0.5 + `@vitest/coverage-v8`, `npm run coverage` in `web/`, all specs.
  - Android: JaCoCo via AGP, `./gradlew ktlintCheck testDebugUnitTest lintDebug createDebugUnitTestCoverageReport` in `android/`, all debug unit tests (1938).
- Run start/completion time and exit status: on 2026-10-26, the reports were written at 05:31:51 (web) and 05:38:05 (Android) +01:00. The parent reported exit 0 (web) and BUILD SUCCESSFUL, 0 failures/errors (Android).
- Generated report paths and report hashes/run identifier: `web/coverage/web/coverage-final.json` sha256 9f83d7dcf44ed49ff3653c63e8710d8515d089b96b7afd87092abba646b464f1; `web/coverage/web/lcov.info` sha256 90fb6bae7058f6d36e78ce512f981387c6d6cf0baa97f8079e6f881a77e89a38; `android/app/build/reports/coverage/test/debug/report.xml` sha256 e537832dd9a24409d7932af4cc7812e7073393ea8f04d4bb928577d41012455a (this replaces the earlier 4b939659... report from 41d49ccdc).
- Evidence outputs are fresh and inputs stayed unchanged:
  - The Android report was regenerated (new hash, `BirthdayContact.kt` changed from 0 to 18/21 lines).
  - It was written 14 s before the c63c35ae4 commit timestamp (05:38:19). That commit contains only the test file, with the same hash I wrote, so the measured inputs are the c63c35ae4 contents.
  - HEAD was still c63c35ae4 when I read the report.
- Final run after latest test additions and production fixes: yes. No production code changed in this task after review.

Values below use covered/total (percentage). Web uses istanbul statements/branches/functions from `coverage-final.json` and lines from `lcov.info` (LF/LH). Android uses JaCoCo INSTRUCTION (shown under Statements), BRANCH, METHOD and LINE.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `web/src/app/features/calendar/edit/event-dialog.ts` | 182/185 (98.4%) | 123/130 (94.6%) | 42/42 (100%) | 151/151 (100%) |
| `web/src/app/features/calendar/edit/event-edit.service.ts` | 32/32 (100%) | 27/27 (100%) | 14/14 (100%) | 28/28 (100%) |
| `android/.../calendar/agenda/AgendaCache.kt` | 1269/1274 (99.6%) | 28/28 (100%) | 66/67 (98.5%) | 149/150 (99.3%) |
| `android/.../calendar/agenda/CalendarModels.kt` | 1112/1136 (97.9%) | 106/114 (93.0%) | 60/61 (98.4%) | 131/131 (100%) |
| `android/.../calendar/edit/BirthdayContact.kt` | 284/326 (87.1%) | 15/24 (62.5%) | 3/6 (50.0%) | 18/21 (85.7%) |
| `android/.../calendar/edit/CalendarWithEditor.kt` | 616/641 (96.1%) | 18/20 (90.0%) | 10/11 (90.9%) | 43/43 (100%) |
| `android/.../calendar/edit/EventEditorViewModel.kt` | 1581/1656 (95.5%) | 164/190 (86.3%) | 42/43 (97.7%) | 179/179 (100%) |

Aggregates use summed counters. Units differ by provider (web statements vs. Android instructions), so the parts are reported separately:
- Web (2 files): statements 214/217 (98.6%), branches 150/157 (95.5%), functions 56/56 (100%), lines 179/179 (100%).
- Android (5 files, measured on c63c35ae4): instructions 4862/5033 (96.6%), branches 331/376 (88.0%), methods 181/188 (96.3%), lines 520/524 (99.2%).
- Excluded: `event-dialog.html`, `event-dialog.scss`, Room `2.json` (reasons above). No scoped file is missing from the reports.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `android/.../calendar/edit/BirthdayContact.kt` (before this run: all lines 26-51) | AC2 "it opens the contact": `open(contactId)`, back/system back, retry (CR-2) | Added `BirthdayContactTest` (4 tests, pass). Lines 26-51 are now covered except those in the next row. |
| `android/.../calendar/edit/BirthdayContact.kt:30` (the `hiltViewModel()` default) | Production DI default; tests inject the view model | Left uncovered. Testing it would need the Hilt activity. |
| `android/.../calendar/edit/BirthdayContact.kt:38-39` (the `launch` lambda: start the dial/mail intent, or show the "no app" toast); `call`/`mail` callbacks at 44-45 never invoked | Calling or mailing a birthday's contact from the calendar tab. The acceptance criteria don't ask for it, only that the contact opens. `ContactsRouteInAppTest` tests the same logic on the Contacts tab | Left uncovered (low risk, outside the criteria). A follow-up could add a Robolectric test with a phone number that checks the started intent. |
| `web/.../event-dialog.ts:188` (`load()` early return for a virtual occurrence) | Defensive: `load()` is called only from the constructor (which skips it for virtual occurrences) and from the reload/retry buttons, which are not rendered for them | Left uncovered. Not reachable from the UI. |
| `web/.../event-dialog.ts:95, 169-170, 236, 238, 331` | Existing create-dialog/reminder/delete guards, not changed by this task | Out of task scope. |
| `android/.../calendar/edit/CalendarWithEditor.kt:34` (default-argument branches; the default `contact` slot `{ id, back -> BirthdayContact(id, back, modifier) }`) | The production wiring of the slot to `BirthdayContact`. Tests pass a fake slot, and `BirthdayContactTest` covers the composable on its own | Left as a small gap. It is a one-line default, the code review checked it, and testing it would need the Hilt activity with a calendar fixture. |
| `android/.../calendar/agenda/CalendarModels.kt:57, 150`; `AgendaCache.kt:309`; `EventEditorViewModel.kt` partial branches (65-372) | Existing code (date fallbacks, Hilt `@Binds` module, editor branches) not changed by this task. Changed lines 56/221 and `EventEditorViewModel.kt:142` are fully covered | Out of task scope. |

## Unable to test

- I cannot run Gradle as a sub-agent, so the Android results come from the parent's run on c63c35ae4 (see above).
- Web navigation from the dialog link to the real contact page in a browser (Playwright) was not run. The task criteria ask only for unit tests. The unit test checks the `/contacts/contact-ada` href and that the dialog closes, and the `contacts` route exists in `app.routes.ts`.

## Summary

Tests added and all validation passed. `BirthdayContactTest` (for CR-2) passed on c63c35ae4 as part of the parent's full Android run (1938 tests, 0 failures). The web suite and the focused web specs pass. Task-scoped coverage is verified, and every changed line is covered except the three justified `BirthdayContact.kt` lines. No production defects were found.
