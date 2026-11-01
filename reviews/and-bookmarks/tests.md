# Test Report

Date: 2026-11-01

Task: and-bookmarks - Android: Bookmarks screen (list, read later, open, edit)

Scope: base e775c74b2b952c94a7329b753dfe59155008dc6e .. HEAD 1c3351ed55349bf1f6036a7e04bd324685fd0fcd on `main` (task commits b7eefdaef implementation, 1c3351ed5 tests), plus the uncommitted test files listed below. Task ownership from the parent's commit list and `touches` in `tasks/and-bookmarks.md`; `git diff --stat e775c74b2..HEAD` shows only the task's files.

Requirements:
- `tasks/and-bookmarks.md` acceptance criteria and recorded assumptions (client-side search, notes = description, favicons only, ACTION_VIEW instead of Custom Tabs, reload on resume and when the capture queue's pending count drops)
- SPEC.md §8.4 / §9.2 item 8 as referenced by the task
- docs/ARCHITECTURE.md "Coverage" and code conventions (EN/DE strings, ktlint)

## Test files changed

- `android/app/src/test/java/ch/sonensei/cockpit/bookmarks/BookmarksModelsTest.kt` (new)
- `android/app/src/test/java/ch/sonensei/cockpit/bookmarks/BookmarksRouteTest.kt` (new)
- `android/app/src/test/java/ch/sonensei/cockpit/bookmarks/BookmarksViewModelTest.kt` (tests added)
- `android/app/src/test/java/ch/sonensei/cockpit/bookmarks/BookmarksScreenTest.kt` (tests added)
- `BookmarksRepositoryTest.kt`: reviewed, unchanged

No existing test was changed, weakened, skipped or removed.

## Tests added or updated

Count: 26 added (existing 29, now 55 in `ch.sonensei.cockpit.bookmarks`)

- BookmarksModelsTest (7): site from host without `www.` and fallback for host-less/unparseable addresses; tag parsing (commas, spaces, tabs, `#`, case-insensitive dedupe, blank input); search matching (every word, any field, case, blank query, untitled bookmark found by site, `#tag`); queue membership; newest-first order with id tie-break; If-Match value.
- BookmarksViewModelTest (+10): failed first load (nothing loaded, message, tags kept on tag failure); reselecting the shown view or no tag does not reload; a second change of a busy bookmark is ignored; after a conflict the next change uses the reloaded version even from an outdated row; conflict whose reload is 404 drops the bookmark, offline reload keeps it; refused save says FAILED and does not reload tags, delete of a gone bookmark drops it silently; save with nothing edited does nothing; edit in "All" stays in place; dismissing an older snackbar keeps a newer one; a share sent before the first load does not load.
- BookmarksScreenTest (+6): failure / no-browser snackbars (all three failure kinds); FAILED load banner with retry disabled while loading; pull-to-refresh gesture calls refresh and a pulled refresh shows no extra progress bar; saving an untitled bookmark sends a blank draft; tag chip selection (case-insensitive); a Trashed snackbar that times out reports dismiss.
- BookmarksRouteTest (3, real view model + `BookmarksRoute`): loads once when shown and again when the lifecycle comes back to RESUMED, showing a bookmark shared meanwhile; tapping a row starts ACTION_VIEW with the bookmark URL; without a browser (Robolectric `checkActivities(true)`) the tap shows the no-browser snackbar instead of crashing.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 list with title, site, favicon/initial, tags; views Read later (default) and All; tag filter; search | ScreenTest `rows show title, site, notes, tags...`, `the tag filter marks the chosen tag`, `empty states follow the view and tag`, `the search field reports...`; VMTest `starts with the read-later queue...`, `the search filters...`; ModelsTest search/host/tag tests; RepositoryTest `the queue asks for unread read-later bookmarks...`, `all bookmarks are asked without the queue filters` | `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.bookmarks.*'` | Pass (favicon image loading itself: see gaps) |
| AC2 tap opens URL in browser; mark read/unread, edit title/tags/notes, delete to Trash with undo | RouteTest `tapping a bookmark opens its address in the browser`, `without a browser on the device...`; ScreenTest menu/editor/undo tests; VMTest read/edit/delete/undo/conflict/busy tests; RepositoryTest PATCH/DELETE/restore tests | same | Pass |
| AC3 pull to refresh; loading, empty, error states | ScreenTest `pulling the list down asks to refresh...`, `empty states follow the view and tag`, `a load problem is shown with a retry`, `a failed load says so, and retry waits...`; VMTest `a failed load keeps the list...`, `a first load that fails...` | same | Pass |
| AC4 shared bookmark appears without restart | RouteTest `the list loads once when shown and again when the app comes back to it`; VMTest `a share sent by the capture queue reloads the list...`, `a share sent before the first load does not load the list` | same | Pass |
| AC5 strings in EN and DE | ScreenTest `the screen is translated to German`; key parity checked by reading both files (31 keys each, same names); `MissingTranslation` lint is enforced by `lintDebug` | same (lint not run by me) | Pass for the test; lint: Not run by test-writer, parent runs `lintDebug` |
| AC6 unit + Compose tests; `ktlintCheck testDebugUnitTest lintDebug` pass | all of the above | `./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.bookmarks.*' ktlintCheck`; `./gradlew createDebugUnitTestCoverageReport` (full unit suite) | ktlint and tests Pass; lintDebug Not run by test-writer (parent runs it) |

## Focused test execution

Command:

`cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.bookmarks.*' ktlintCheck -q`

Result:

Pass: 55 tests (Models 7, Repository 6, ViewModel 22, Screen 17, Route 3), 0 failures, 0 skipped; ktlintCheck clean.

Two of my first drafts failed and were corrected as test errors, not production bugs: the load-banner text sits in unmerged child nodes (now asserted with `onNodeWithText`), and a repeated identical failure snackbar keeps the same instance because `MutableStateFlow` conflates an equal state, which is acceptable behavior (the visible snackbar simply stays).

## Full test execution

Command:

`cd android && ./gradlew createDebugUnitTestCoverageReport -q` (runs the whole debug unit suite with coverage)

Result:

Pass: exit 0, 2301 tests, 0 failures, 0 errors, 0 skipped (from `app/build/test-results/testDebugUnitTest/*.xml`).

## .taskcheck

Result:

Not run by the test-writer: `sh scripts/check.sh` covers server, web and android and is the parent's final validation step; Android lint (`lintDebug`) was also left to the parent.

## Bugs found

None. No production defect was found by the tests.

## Coverage

Status: verified (whole-file, Android JaCoCo, fresh run on the final test set).

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured separately. Three of the four files are new in the task and `BookmarksScreen.kt` was a placeholder replaced almost entirely (586 lines changed, 1 removed), so whole-file numbers are close to changed-line numbers, but no intersection with the diff was computed.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: and-bookmarks; base e775c74b2b952c94a7329b753dfe59155008dc6e; HEAD 1c3351ed55349bf1f6036a7e04bd324685fd0fcd; commits b7eefdaef, 1c3351ed5 given by the parent, matching `git diff --name-only e775c74b2..HEAD`.
- Source/test snapshot identifier (including uncommitted inputs): sha256 of inputs, unchanged before and after the run: BookmarksModels.kt 63d071cd..., BookmarksRepository.kt be1fea77..., BookmarksScreen.kt 309848ea..., BookmarksViewModel.kt 15b92d98..., values/strings_bookmarks.xml 050abd2c..., values-de/strings_bookmarks.xml 9c609412...; tests: BookmarksModelsTest e145e920..., BookmarksRepositoryTest 9cf4a0ab..., BookmarksRouteTest 9b570b12..., BookmarksScreenTest c1b6ce31..., BookmarksViewModelTest de949931...
- Exact included files; renamed/deleted/excluded files and reasons: included `android/app/src/main/java/ch/sonensei/cockpit/bookmarks/{BookmarksModels,BookmarksRepository,BookmarksViewModel,BookmarksScreen}.kt`. Excluded: `res/values{,-de}/strings_bookmarks.xml` (resources, not instrumented; covered by the German UI test and lint). No renames or deletions.
- Provider/version, command, working directory, and test selection: JaCoCo via AGP `enableUnitTestCoverage` (Gradle 9.8.1, JDK 21.0.12.1); `./gradlew createDebugUnitTestCoverageReport -q` in `android/`; full debug unit suite.
- Run start/completion time and exit status: 2026-11-01 14:19:18 to 14:21:38 +01:00, exit 0.
- Generated report paths and report hashes/run identifier: `android/app/build/reports/coverage/test/debug/report.xml`, written 14:21:36, sha256 cc13be9a0f672e5098ec864672eb8cb6e46858befd9993bdd8842fa28f2600da. Counters read from `package ch/sonensei/cockpit/bookmarks` / `sourcefile` elements.
- Evidence outputs are fresh and inputs stayed unchanged: yes (report timestamp after run start; snapshot hashes verified after the run). An earlier report from 14:12:44 (before the added tests) was not used.
- Final run after latest test additions and production fixes: yes (no production changes were made).

Values below use covered/total (percentage).

| Changed file | Instructions | Branches | Methods | Lines |
|---|---:|---:|---:|---:|
| `BookmarksModels.kt` | 413/424 (97.4%) | 30/32 (93.8%) | 27/30 (90.0%) | 58/58 (100%) |
| `BookmarksRepository.kt` | 476/537 (88.6%) | 20/24 (83.3%) | 27/28 (96.4%) | 64/70 (91.4%) |
| `BookmarksViewModel.kt` | 1536/1552 (99.0%) | 95/104 (91.3%) | 56/57 (98.2%) | 141/143 (98.6%) |
| `BookmarksScreen.kt` | 4437/4722 (94.0%) | 110/134 (82.1%) | 96/117 (82.1%) | 356/367 (97.0%) |
| Total (summed counters, 4 of 4 Kotlin files measured, 2 string resource files excluded) | 6862/7235 (94.8%) | 255/294 (86.7%) | 206/232 (88.8%) | 619/638 (97.0%) |

For comparison, before the added tests (report of 14:16:02, same production code): Screen lines 354/367, branches 109/134; other files unchanged. The added route test covers the no-browser fallback (Screen 121-122) and the reload on resume; the other additions mainly pin behavior that was executed but not asserted.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `BookmarksScreen.kt:433-439`, branches 431-432 | Favicon loading through Coil when the API provides an https favicon (AC1) | Justified gap: exercising it would fetch from a real external site; tests use no ImageLoader and assert the initial fallback. Follow-up only if a fake Coil fetcher is wanted. |
| `BookmarksScreen.kt:86, 95`, branches 84-96 | Hilt entry (`BookmarksScreen` in a Hilt activity, `hiltViewModel()` default) | Not reachable without a Hilt test activity; the route itself is tested with an explicit view model. |
| `BookmarksScreen.kt:160, 322`, branch 568, 593, 319, 346, 384-401, 451-454 | data-class/`when` synthetic branches, Compose-generated default and recomposition branches | Non-material; behavior behind them (all snackbar kinds, dismiss on timeout, undo, editor fields, read/unread rows) is tested. |
| `BookmarksRepository.kt:53` | Hilt `@Provides` for `BookmarksWriteApi` | Dependency-injection glue; non-material. |
| `BookmarksRepository.kt:161-162, 169-171`, branches 97, 143, 150, 193 | Rethrowing cancellation; IllegalArgument/DateTime parse failures; 50-page cap; null body on 2xx | Defensive paths; the unreadable-answer path (`SerializationException` to `Failed(null)`) and server error/offline mapping are tested. Low risk. |
| `BookmarksViewModel.kt:77, 92` | `@Inject` constructor mapping `CaptureQueue.status`; end of the never-ending collect | Hilt glue / unreachable; the internal constructor with a pending-count flow is tested. |
| `BookmarksViewModel.kt` branches 204, 237, 240, 248, 257, 265 | Elvis/`when` sub-branches (change of a bookmark not in the list, conflict reload failing with another status, case-insensitive tag match on put) | Main paths tested; remaining sub-branches low risk. |
| `BookmarksModels.kt` branch 80 | `hostOf` with an empty host string | Low risk; null-host and unparseable cases tested. |

## Unable to test

- Real favicon image loading from remote sites (would call external services from unit tests); fallback initial is tested.
- Custom Tab vs default browser: the assumption records ACTION_VIEW; the intent is tested, the real browser app is not.
- Pull-to-refresh is tested by a Robolectric swipe gesture on the list, not on a device.
- `lintDebug` (incl. `MissingTranslation`) and `.taskcheck` were left to the parent's final validation.

## Summary

Tests added and all validation passed. Tests and task-scoped coverage are sufficient; no production defects found. Remaining gaps (remote favicon loading, Hilt glue, defensive repository catches) are justified above.
