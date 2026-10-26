# Test Report

Date: 2026-10-26

Task: and-cal-cache - Android agenda offline read cache (SPEC 9.3)

Scope: second pass on the final code. Final revision: `6fc162929328234442f51bc21340ecd7382c497d`. `git diff 6e48a9c9a865f1aad9a097b2f02c265230591302..6fc162929328234442f51bc21340ecd7382c497d -- android` (base supplied by the parent). Task commits: `2ad1c6d34` (implementation + tests), `8aec21219` (test-writer tests, first pass), `6f394e2fb` (fixer: CR-2 and TW-1 in `CalendarViewModel.load()`, 3 tests), `6fc162929` (this pass's 2 tests, test file only). Focus of this pass: the fixer's change, `git diff 8aec21219..6f394e2fb`. Production code is identical in `6f394e2fb` and `6fc162929` (`git diff --name-only 6f394e2fb 6fc162929 -- android/app/src/main` is empty; the only other changes are `tasks/*.md`). Branch `main`, no uncommitted source or test changes.

Requirements:
- `tasks/and-cal-cache.md` acceptance criteria and recorded assumptions
- SPEC.md §9.3 "Read cache" (via the task), `docs/ARCHITECTURE.md` "Coverage", `AGENTS.md`
- `reviews/and-cal-cache/fixes.md` (fixed behavior: CR-2, TW-1) and parent instructions

## Test files changed

This pass (committed by the parent as `6fc162929`; `ktlintFormat` made no changes, sha256 of the committed file f0dbe1ced8f926e06653d3e6325f8db04042450be849b892a7996e3355252b92 equals the hash recorded for the focused run):
- `android/app/src/test/java/ch/sonensei/cockpit/calendar/agenda/CalendarViewModelCacheTest.kt` (2 tests added, `CompletableDeferred` import)

Earlier in this task (committed in `8aec21219`, unchanged now): `AgendaCacheTest.kt`, `CalendarViewModelCacheTest.kt`, `AgendaCacheGraphTest.kt` (new), `CalendarViewModelRoomCacheTest.kt` (new). The fixer added 3 tests to `CalendarViewModelCacheTest.kt` in `6f394e2fb`.

Existing tests were not changed, weakened or disabled.

## Tests added or updated

Count: 2 added in this pass (13 added in the first pass, 3 by the fixer).

Gap analysis of the fix: the fixer's 3 tests cover storing the calendar list only from an `Ok` answer (cached calendars kept, empty without a cache) and the offline fallback applying a freshly loaded list. Two parts of the new fallback had no test asserting them:

- `offline events without a calendar list keep the calendars loaded in this session over the cached ones`: agenda loaded online (calendar renamed, version 4), then a day view whose events and calendar list are both offline shows the cached events but keeps the session's calendar list, not the older cached one (`current.calendars.ifEmpty { cached.calendars }`, non-empty arm; the comment "Calendars loaded now or earlier in this session are newer").
- `offline events with a loaded calendar list keep a visibility choice still being written`: a visibility change is still being written (held with a `CompletableDeferred`), the day view's events are offline but the calendar list answers with the pre-change version; the fallback shows the cached events and the calendar keeps the user's pending choice (`withCalendars` before `withCached`, as fixes.md states).

Both pass on 6f394e2fb; no defect found.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: every successful agenda load (first range and each scrolled chunk) is stored in Room with the calendar list; a new load from today replaces it | `AgendaCacheTest` (12), `CalendarViewModelCacheTest`: `a loaded agenda and the days loaded on scrolling are cached`, `day and week loads and failed loads are not cached`, `an agenda refreshed on a later day replaces ...`, `days loaded on scrolling that fail are not appended ...`, fixer: `an agenda loaded without the calendar list keeps the cached calendars`, `... and without a cache stores no calendars` | `android/gradlew -p android testDebugUnitTest --tests 'ch.sonensei.cockpit.calendar.agenda.*' -q` (test-writer, on 6f394e2fb + this pass's tests, identical to 6fc162929); full suite on 6fc162929 by the parent | Pass |
| AC2: offline with nothing loaded, the agenda / a day or week inside the cached range shows cached events with the banner naming the load time; read-only; next successful load replaces it | `CalendarViewModelCacheTest` offline/range tests, fixer: `offline events with a loaded calendar list show the cached events with the loaded calendars`, this pass: the 2 tests above; `CalendarScreensCachedTest` (4) | same | Pass |
| AC3: logout clears the cache, and an answer whose load started before the logout is not stored | `AgendaCacheTest` logout tests, `AgendaCacheGraphTest` (2), `CalendarViewModelRoomCacheTest` (2) | same | Pass |
| AC4: `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` passes | whole Android suite | `android/gradlew -p android ktlintCheck testDebugUnitTest lintDebug` (run by the parent on the final revision 6fc162929) | Pass (parent: 1880 tests, 0 failures/errors) |

## Focused test execution

Command:

`android/gradlew -p android testDebugUnitTest --tests 'ch.sonensei.cockpit.calendar.agenda.*' -q` (test-writer, working directory: clone root, on 6f394e2fb plus this pass's test change, 2026-10-26 ~01:25 local)

Result:

Pass: exit 0; JUnit XML in `android/app/build/test-results/testDebugUnitTest/`: 18 agenda suites, 135 tests, 0 failures, 0 errors, 0 skipped; `CalendarViewModelCacheTest` 17 tests (incl. both new ones).

## Full test execution

Command:

`android/gradlew -p android ktlintCheck testDebugUnitTest lintDebug` (clone root)

Result:

Pass, run by the parent on the final revision 6fc162929328234442f51bc21340ecd7382c497d: 1880 tests, 0 failures, 0 errors (1878 earlier on 6f394e2fb plus this pass's 2). ktlintCheck and lintDebug passed. Reported by the parent; the test-writer did not rerun it.

## .taskcheck

Result:

Not run by the test-writer (`sh scripts/check.sh`); the parent runs it before `done`.

## Bugs found

None. The earlier observation (TW-1, offline fallback dropping the loaded calendar list) was fixed in `6f394e2fb` and is covered by the fixer's test and by `offline events with a loaded calendar list keep a visibility choice still being written`.

## Coverage

Status: verified for the production code of the final revision. Measured on 6f394e2fb (parent's run); the final revision 6fc162929 has identical production code (only this pass's 2 tests and `tasks/*.md` changed), so the counters apply to it. Not remeasured on 6fc162929: the 2 extra tests can only add hits, so the figures below are a lower bound for the final revision.

Metric: whole-file coverage of task-changed production files (JaCoCo counters, includes hits from pre-existing tests).
Changed-line coverage: not measured as a metric. Read from the report: every line of the fixer's change in `CalendarViewModel.kt` (lines 313-374) has 0 missed instructions and 0 missed branches.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `and-cal-cache`; base `6e48a9c9a865f1aad9a097b2f02c265230591302`; coverage measured on `6f394e2fbac924ce4c49a340201bb4302cbbb88e`; final revision `6fc162929328234442f51bc21340ecd7382c497d` (same production code); task commits `2ad1c6d34`, `8aec21219`, `6f394e2fb`, `6fc162929` (from the parent); file scope `git diff --name-only 6e48a9c..6fc162929 -- android/app/src/main`.
- Source/test snapshot identifier (including uncommitted inputs): coverage: 6f394e2fb, clean tree (parent). Focused run: 6f394e2fb + `CalendarViewModelCacheTest.kt` sha256 f0dbe1ced8f926e06653d3e6325f8db04042450be849b892a7996e3355252b92, which is exactly the file committed in 6fc162929 (hash rechecked with `git show` of the file at 6fc162929 piped to `sha256sum`). Full suite (parent): 6fc162929, clean tree.
- Exact included files: `android/app/src/main/java/ch/sonensei/cockpit/calendar/agenda/AgendaCache.kt`, `CalendarViewModel.kt`, `CalendarScreen.kt` (same folder). Excluded: `res/values/strings_calendar_agenda.xml` (resource, no executable code), `android/app/schemas/.../1.json` (generated Room schema).
- Provider/version, command, working directory, and test selection: JaCoCo via AGP; `android/gradlew -p android ktlintCheck testDebugUnitTest lintDebug createDebugUnitTestCoverageReport`, clone root, all Android unit tests (parent).
- Run start/completion time and exit status: completed 2026-10-26 01:22:57 local (report mtime), after the commit of 6f394e2fb (01:20:52); exit 0 (parent).
- Generated report paths and report hashes/run identifier: `android/app/build/reports/coverage/test/debug/report.xml`, sha256 c4fd2b33924ba39bda1336e1225c3aff90bb06a844764b7e761ddbae2a52707f (read by the test-writer; counters below match the parent's figures).
- Evidence outputs are fresh and inputs stayed unchanged: report written after the commit of 6f394e2fb; production code unchanged in 6fc162929.
- Final run after latest test additions and production fixes: after the production fixes, yes (coverage on 6f394e2fb). After this pass's 2 test additions, the full suite was rerun by the parent on 6fc162929 (ktlintCheck testDebugUnitTest lintDebug, 1880 tests, pass), but coverage was not regenerated; production code is unchanged, so the counters below still describe the final production code.

Values below use covered/total (percentage).

| Changed file | Instructions | Branches | Methods | Lines |
|---|---:|---:|---:|---:|
| `android/app/src/main/java/ch/sonensei/cockpit/calendar/agenda/AgendaCache.kt` | 1259/1264 (99.6%) | 28/28 (100%) | 65/66 (98.5%) | 148/149 (99.3%) |
| `android/app/src/main/java/ch/sonensei/cockpit/calendar/agenda/CalendarViewModel.kt` | 2097/2112 (99.3%) | 159/164 (97.0%) | 49/49 (100%) | 211/211 (100%) |
| `android/app/src/main/java/ch/sonensei/cockpit/calendar/agenda/CalendarScreen.kt` | 5858/5927 (98.8%) | 227/254 (89.4%) | 99/107 (92.5%) | 492/496 (99.2%) |
| Total (summed counters, all 3 scoped files measured, none missing) | 9214/9303 (99.0%) | 414/446 (92.8%) | 213/222 (95.9%) | 851/856 (99.4%) |

JaCoCo has no separate statement metric; instructions are shown instead.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `CalendarViewModel.kt:313-374` (fixed `load()`, `cachedRange`, `withCached`) | CR-2 / TW-1 fixes, AC1/AC2 | Fully covered except one branch at `:361` (`end <= from \|\| cached.to < end`, pre-existing range check, its boundaries tested by the range tests). Added the 2 tests above to assert the fallback's calendar choice, not to raise the percentage |
| `CalendarViewModel.kt:31-35, 200, 241, 264` | Default parameters and visibility/range logic of earlier tasks | Outside this task's change; not tested here |
| `AgendaCache.kt:308` (1 line, 1 method), `:332` | `AgendaCache.None` default for the internal constructor; part of a line | Non-material (default for older tests) |
| `CalendarScreen.kt:110-114, 141-150, 202-208, 280-298, 390-415, 517-522, 647-827` | Mostly pre-existing screen branches (Compose-generated defaults, rare layout states) | The cached-state UI of this task (banner, disabled chips, no "more", not openable) is covered by `CalendarScreensCachedTest`; remaining gaps are not task behavior |

## Unable to test

- `.taskcheck` (`sh scripts/check.sh`): not run by the test-writer; the parent runs it before `done`. ktlint, lint and the full Android suite were run by the parent on 6fc162929 (pass).
- A real device's full-disk `SQLiteFullException`: simulated with an SQLite trigger abort (first pass).
- Room migration behavior: only schema version 1 exists; nothing to migrate yet.

## Summary

Tests added and all validation passed: 2 tests for the fixed offline fallback (session calendars kept, pending visibility choice kept), committed in the final revision 6fc162929328234442f51bc21340ecd7382c497d. Focused agenda suites pass (135 tests, 0 failures); the parent's `ktlintCheck testDebugUnitTest lintDebug` on 6fc162929 passed (1880 tests, 0 failures/errors). Coverage of the final production code (measured on 6f394e2fb, unchanged since): AgendaCache.kt 148/149 lines, 28/28 branches; CalendarViewModel.kt 211/211 lines, 159/164 branches; CalendarScreen.kt 492/496 lines, 227/254 branches. No production defect found; `.taskcheck` is left to the parent.
