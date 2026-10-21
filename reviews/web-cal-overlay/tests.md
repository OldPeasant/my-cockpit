# Test Report

Date: 2026-10-21

Task: web-cal-overlay - Calendar task overlay and cross-links

Scope: task commits on top of the immutable base `7d07e18cf944c834f8017fe8377eef7a55acce08` up to HEAD `43826282c5ae7d03357b5c2a295fd570abb7a3d4` (branch `main`): ca18fd70e, 61ba28182, d114d91c1, 376aaec60 (earlier test-writer additions), 45f087fc3 (review round 1 fixes), 43826282c (fixer report only). Task ownership comes from the parent worker's commit list and `git log 7d07e18cf..HEAD` (only commits of this task in the range). This round adds uncommitted test changes in three test files (snapshot below); no production file is changed.

Requirements:
- `tasks/web-cal-overlay.md`: the three acceptance criteria and the five ASSUMPTION notes
- SPEC.md §6.3 (optional task overlay in day/week, event detail with linked items, "Create task from event" / "Create note for event" with pre-filled title + date)
- `docs/ARCHITECTURE.md` "Coverage", `docs/QUALITY.md`, AGENTS.md project notes
- `reviews/web-cal-overlay/review.md` (CR-1 to CR-4) and `reviews/web-cal-overlay/fixes.md`

## Test files changed

- `web/src/app/features/calendar/overlay/event-links.spec.ts`
- `web/src/app/features/calendar/views/calendar-view.spec.ts`
- `server/src/test/kotlin/ch/sonensei/cockpit/gtd/TaskDateRangeWebTests.kt`

Existing tests (task-overlay.spec.ts, task-store.spec.ts, event-dialog.spec.ts, the e2e spec and the earlier test-writer additions) were reviewed and not duplicated or changed.

## Tests added or updated

Count: 11 (5 in event-links.spec.ts, 5 in calendar-view.spec.ts, 1 in TaskDateRangeWebTests.kt)

event-links.spec.ts (EventLinks):
- `dates the task and note titles in the zone of its timeZone input, not in UTC` (CR-1 fix: the panel passes its zone; 23:30 UTC titles the 24th in Zurich)
- `dates an all-day event by its start date`
- `links a generated occurrence of a series to the event that holds it` (ASSUMPTION: links point to occurrence.eventId)
- `sends the create requests silently, since the panel shows their errors itself` (SILENT_ERRORS on createTask and createNote)
- `shows the server message of a refused create` (ApiError userMessage path)

calendar-view.spec.ts (CalendarView > task overlay, real OverlayTaskStore with a fake Api):
- `hides the load error of the tasks once the overlay is turned off`
- `shows the tasks of the newest week when an older answer arrives last` (stale answer contains a task that would fall into the shown week)
- `drops an older failed answer arriving after the newest one` (no error alert from the stale failure)
- `loads the tasks of the one shown day in the day view` (from = to = the shown day)
- `shows the tasks again when switching back from the month to the week view` (no refetch for the same week)

TaskDateRangeWebTests.kt:
- `a rejected range names the field and the rule in Problem Details` (field and message of each rejection, incl. the CR-3 wording "the range must span at most 366 days" for a 367-day range)

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: optional overlay of due tasks and ticklers in day/week views | task-overlay.spec.ts (`taskEvents`, `TaskOverlay`, `TaskOverlay with its OverlayTaskStore`); calendar-view.spec.ts `task overlay` (11 tests, 5 new); TaskDateRangeWebTests (7 tests, 1 new) | `cd web && npx ng test --watch=false --include='src/app/features/calendar/**/*.spec.ts' --include='src/app/features/gtd/data/**/*.spec.ts'`; `cd server && ./gradlew --offline ktlintCheck test --tests 'ch.sonensei.cockpit.gtd.TaskDateRangeWebTests' jacocoTestReport` | Pass (357 web tests; 7 server tests) |
| AC1: event detail has linked items, "Create task from event", "Create note for event" | event-links.spec.ts (`event links helpers`, `EventLinks`, 5 new); event-dialog.spec.ts | same web command | Pass |
| AC2: Vitest tests cover overlay toggle and create-linked actions; `npm run check` passes | as above; full web check | `cd web && npm run check` | Pass (exit 0; 122 files, 2433 tests; lint, api:check, e2e typecheck and build included) |
| AC3: Playwright `e2e/calendar-overlay/overlay.spec.ts` against the e2e stack | `web/e2e/calendar-overlay/overlay.spec.ts` | `npm run e2e -- e2e/calendar-overlay/overlay.spec.ts` | Not run: the test-writer did not start the docker compose e2e stack (not permitted in this session). The parent must run it on the final code; review.md reports a pass on a pre-fix revision only |
| ASSUMPTION: from/to filter of GET /api/v1/tasks (due OR tickler in range, incl. ticklers, both or neither, at most 366 days) | TaskDateRangeWebTests (7); task-store.spec.ts `matchesTask > with a from/to range` | server command above; web command above | Pass |
| ASSUMPTION: toggle per browser, off by default, day/week only, timed due tasks 30 min, read-only, click opens /tasks/{id} | task-overlay.spec.ts; calendar-view.spec.ts `task overlay` | web command above | Pass |
| CR-4 fix: no task requests while the overlay is off | task-overlay.spec.ts `TaskOverlay with its OverlayTaskStore`; calendar-view.spec.ts `is off by default and loads no tasks` | web command above | Pass |
| ASSUMPTION / CR-1 fix: task and note titled "event title (yyyy-MM-dd)" in the settings zone, plain title without a date, linked item to event | event-links.spec.ts (incl. 3 new) | web command above | Pass |
| ASSUMPTION: /calendar/events/:id redirects to ?event=id, which jumps to the date and opens the event once | calendar-view.spec.ts `calendar routes`, `linked event` | web command above | Pass |
| CR-3 fix: range error message | TaskDateRangeWebTests `a rejected range names the field and the rule in Problem Details` | server command above | Pass |

## Focused test execution

Command:

`cd web && npx ng test --watch=false --include='src/app/features/calendar/**/*.spec.ts' --include='src/app/features/gtd/data/**/*.spec.ts'` (also with `--coverage`, see Coverage)

Result:

Pass: 18 files, 357 tests (347 before this round + 10 new).

Command:

`cd server && ./gradlew --offline ktlintCheck test --tests 'ch.sonensei.cockpit.gtd.TaskDateRangeWebTests' jacocoTestReport`

Result:

Pass: BUILD SUCCESSFUL; TaskDateRangeWebTests 7 tests, 0 failures, 0 errors; ktlint over the test source set passed.

`npx prettier --check` and `npx eslint` on the two changed web spec files: Pass (after `prettier --write` on calendar-view.spec.ts, a line wrap in the new tests only).

## Full test execution

Command:

`cd web && npm run check`

Result:

Pass (exit 0): 122 test files, 2433 tests, plus api:check, lint, e2e typecheck and build.

Command:

`cd server && ./gradlew --offline test --tests 'ch.sonensei.cockpit.gtd.*' jacocoTestReport`

Result:

Pass: BUILD SUCCESSFUL; 31 test classes, 407 tests, 0 skipped, 0 failures, 0 errors.

The full server suite and Android were not run separately (they are part of `.taskcheck`).

## .taskcheck

Result:

Not run: the test-writer was not permitted to run `sh scripts/check.sh` in this session. The parent must run it (fixes.md reports one unrelated IcsFeedClientTests flake on the previous run).

## Bugs found

None. No new or existing test failed; no production defect was found.

## Coverage

Status: verified (web and server, whole-file coverage of the task-changed production files; Playwright/e2e coverage not applicable).

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured as counts. Checked qualitatively: web, every executable line of the scoped TS files has hits (line coverage 100%), so no changed executable line is unhit; server, intersecting the JaCoCo `line` entries with the added lines of `git diff -U0 7d07e18cf..HEAD -- server/src/main`, no changed line has zero covered instructions (TaskCommands.kt:69, the new `dates` property, is partly covered: 6 covered, 2 missed instructions of the generated data-class code; no branches). Changed-branch coverage is not inferred.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: web-cal-overlay; base `7d07e18cf944c834f8017fe8377eef7a55acce08`; HEAD `43826282c5ae7d03357b5c2a295fd570abb7a3d4`; commits ca18fd70e, 61ba28182, d114d91c1, 376aaec60, 45f087fc3, 43826282c from the parent and `git log`; files from `git diff --stat 7d07e18cf..HEAD`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD 43826282c plus uncommitted test files, git blob ids (`git hash-object`): `event-links.spec.ts` fb39ef718ca10ce731ef7d7bc0122ea81f1a8115, `calendar-view.spec.ts` 3ed386709d5df7916a2f42c773ba0bf71bd21f3b, `TaskDateRangeWebTests.kt` 3647b4c044ae7bf2cedd0609b2bf06c244ad29b2. No production file uncommitted.
- Exact included files: web `src/app/features/calendar/overlay/task-overlay.ts`, `overlay/event-links.service.ts`, `overlay/event-links.ts`, `views/calendar-view.ts`, `calendar.routes.ts`, `edit/event-dialog.ts`, `src/app/features/gtd/data/task-store.ts`; server `gtd/api/TaskController.kt`, `gtd/domain/TaskService.kt`, `gtd/domain/TaskCommands.kt`. Excluded: `api/openapi.yaml` and `web/src/app/api/generated/fn/tasks/list-tasks.ts` (generated), `views/calendar-view.html` and `edit/event-dialog.html` (Angular templates, not separate v8 entries; their behaviour is exercised by the component tests above), `overlay/task-overlay.scss` (styles), `web/e2e/...` and `*.spec.ts`/server tests (tests), `reviews/` (docs). No renamed or deleted files.
- Provider/version, command, working directory, and test selection: web Vitest 5.0.5 with `@vitest/coverage-v8` (^5.0.5), `npx ng test --watch=false --coverage --coverage-reporters=json-summary --coverage-reporters=json --include='src/app/features/calendar/**/*.spec.ts' --include='src/app/features/gtd/data/**/*.spec.ts'` in `web/` (18 spec files); server JaCoCo via the Gradle `jacoco` plugin, `./gradlew --offline test --tests 'ch.sonensei.cockpit.gtd.*' jacocoTestReport` in `server/` (31 gtd test classes).
- Run start/completion time and exit status: web final run started 2026-10-21 19:41:38 +0200, reports written 19:41:48 +0200, 357 passed; server gtd run finished about 19:41 +0200 (BUILD SUCCESSFUL, 407 tests, 0 failures). Both commands completed successfully.
- Generated report paths and report hashes/run identifier: `web/coverage/web/coverage-final.json` sha256 1a317dc45fdaa868a443f3558cbd4abb88cceefa9e79b0b468033ea277f2896e, `web/coverage/web/coverage-summary.json` sha256 80e5550a7d1155898e4d6a28b24f81c080666f8621d785d32d5d1e27e1c43cb7; `server/build/reports/jacoco/test/jacocoTestReport.xml` sha256 af5096ab127851cd274953ef15c0d0417ea6f42afa9944558daaf8012703e053. Counters read with scratch scripts in `.tmp/` (Git-ignored).
- Evidence outputs are fresh and inputs stayed unchanged: Vitest rewrote `coverage/` in the final run (file times 19:41:48); the web run is after the last test edit (prettier). The server reports come from the gtd run after the last server test edit; the server test file did not change after it (same blob id before and after).
- Final run after latest test additions and production fixes: yes (production last changed in 45f087fc3, before this round).

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A.

Web (Vitest v8):

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `web/src/app/features/calendar/overlay/task-overlay.ts` | 59/59 (100%) | 49/49 (100%) | 13/13 (100%) | 46/46 (100%) |
| `web/src/app/features/calendar/overlay/event-links.service.ts` | 28/28 (100%) | 13/13 (100%) | 9/9 (100%) | 24/24 (100%) |
| `web/src/app/features/calendar/overlay/event-links.ts` | 58/59 (98.3%) | 24/24 (100%) | 13/14 (92.85%) | 45/45 (100%) |
| `web/src/app/features/calendar/views/calendar-view.ts` | 129/134 (96.26%) | 61/66 (92.42%) | 39/40 (97.5%) | 109/109 (100%) |
| `web/src/app/features/calendar/calendar.routes.ts` | 3/3 (100%) | N/A (0/0) | 2/2 (100%) | 3/3 (100%) |
| `web/src/app/features/calendar/edit/event-dialog.ts` | 174/177 (98.3%) | 113/120 (94.16%) | 40/40 (100%) | 145/145 (100%) |
| `web/src/app/features/gtd/data/task-store.ts` | 76/76 (100%) | 86/89 (96.62%) | 25/25 (100%) | 60/60 (100%) |
| Web aggregate (summed counters, 7 files; exclusions above) | 527/536 (98.32%) | 346/361 (95.84%) | 141/143 (98.60%) | 432/432 (100%) |

Server (JaCoCo; JaCoCo has no statement counter, instructions shown instead):

| Changed file | Instructions | Branches | Methods | Lines |
|---|---:|---:|---:|---:|
| `server/src/main/kotlin/ch/sonensei/cockpit/gtd/api/TaskController.kt` | 589/596 (98.83%) | 38/40 (95.00%) | 19/21 (90.48%) | 89/90 (98.89%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/gtd/domain/TaskService.kt` | 2248/2258 (99.56%) | 268/271 (98.89%) | 38/38 (100%) | 264/264 (100%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/gtd/domain/TaskCommands.kt` | 347/461 (75.27%) | N/A (0/0) | 42/45 (93.33%) | 44/47 (93.62%) |
| Server aggregate (summed counters, 3 files) | 3184/3315 (96.05%) | 306/311 (98.39%) | 99/104 (95.19%) | 397/401 (99.00%) |

No scoped file is missing from either report.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `web/.../overlay/event-links.ts:82` (function and statement) | `viewChild('links')` signal initialiser counted as an uncalled arrow by v8 | Non-material (Angular query plumbing; the reload it serves is asserted by the "reloads the panel" tests) |
| `web/.../views/calendar-view.ts:66, 81` | `providers` decorator branch and the `viewChild(FullCalendarComponent)` initialiser, v8 artefacts | Non-material |
| `web/.../views/calendar-view.ts:257, 270` (editorContext/openLinked without settings) | Defensive: the linked-event effect only runs once `options()` exist, which requires settings | Non-material defensive code, not reachable through the view |
| `web/.../views/calendar-view.ts:212, 236` | `newEvent` without settings, event click without occurrence/context: pre-existing code outside this task's change | Not in the task's changed lines; left |
| `web/.../gtd/data/task-store.ts:54, 92, 110` (right side of `??`) | Pre-existing optimistic-update fallbacks, not changed by the task | Out of task scope; the changed `matchesTask` range branch is fully covered |
| `web/.../edit/event-dialog.ts:87, 158, 159, 174, 222, 224, 317` | Pre-existing dialog branches; the task only added the EventLinks import | Out of task scope |
| `server/.../TaskController.kt:163, 361, 380` | Pre-existing (another endpoint's partial branch, companion-object constants) | Not changed by the task; `dateRange` (lines 388-400) is fully covered |
| `server/.../TaskService.kt:102, 325` (partial branches) | Pre-existing code, not in the changed predicate (lines 457-468, fully covered) | Out of task scope |
| `server/.../TaskCommands.kt` lines 14-77 (missed instructions) | Generated data-class code (copy/componentN/default constructors) of the command and filter classes | Non-material; the new `dates` field (line 69) is used by the range tests |

## Unable to test

- AC3, the Playwright overlay spec against the docker compose e2e stack: not run in this session (the test-writer did not start the e2e stack). Follow-up: the parent must run `cd web && npm run e2e -- e2e/calendar-overlay/overlay.spec.ts` against the e2e stack on the final code before verifying AC3.
- `.taskcheck` (`sh scripts/check.sh`): not permitted in this session. Follow-up: the parent runs it.
- Angular template-only details (exact layout and CSS of the overlay marks in `task-overlay.scss`) are not unit-testable in jsdom; the visible behaviour (toggle, hint, alert, entries passed to FullCalendar) is covered by the component tests.

## Summary

Tests added and all validation passed that could run here (focused web and server tests, the full web check, the gtd server package); no production defects found. The Playwright criterion (AC3) and `.taskcheck` were not run by the test-writer and remain for the parent worker.
