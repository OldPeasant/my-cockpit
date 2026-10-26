# Test Report

Date: 2026-10-26

Task: and-new-view - Android combined New view

Scope: `git diff 9bdb47487fa969cc3f6d8d2e5d71a72c8d2ea2ee..8328e25c874a4d07643c0f6d667584aac99a0d5d` on `main` (task commits 09b39ff71 feature, da90f482d and c3de9348c tests, 090f98a6e fixes CR-1..CR-5, plus review commits e614cbe74 and 8328e25c8 that touch only `reviews/`), plus the uncommitted test changes listed below. Task ownership came from the parent worker (base and commit list) and was checked against `git log` and the task's `touches`.

Requirements:
- tasks/and-new-view.md (both acceptance criteria and the three recorded assumptions)
- SPEC.md §5.3 "New view" (via the task), docs/ARCHITECTURE.md "Coverage"
- reviews/and-new-view/review.md and fixes.md (CR-1 logout race, CR-2 hold-back offline, CR-5 task lookup)
- Existing test conventions in `android/app/src/test` (Robolectric + Compose rule, MockK, Hilt in-app tests with MockWebServer)

## Test files changed

- `android/app/src/test/java/ch/sonensei/cockpit/newitems/NewScreenInAppTest.kt` (new)
- `android/app/src/test/java/ch/sonensei/cockpit/newitems/NewViewModelTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/newitems/NewScreenTest.kt`

## Tests added or updated

Count: 5 added (0 existing tests changed). The newitems package now has 98 tests (93 before).

- `NewScreenInAppTest."New in the More list shows the real New view with the unified inbox"`: real `MainActivity`, the production Hilt graph and HTTP client against a fake MockWebServer; More -> New shows the real New view (not the placeholder), loads `/api/v1/mail/messages` and shows the mail with its "Mail" source marker. Covers the Hilt branch of `NewScreen`, the navigation entry, and the DI wiring of `NewMailCache`/`PreferencesNewFilterStore`/`NewItemsModule`, which no test reached before.
- `NewScreenInAppTest."tapping a mail opens the mail read view and back returns to the list"`: tapping the mail row opens the real `MailReadRoute` (it requests `/api/v1/mail/messages/{id}`), back returns to the list. Before, the "tap opens the mail read view" path through `NewRoute` was untested (only the callback in `NewScreenTest`).
- `NewViewModelTest.aRefreshFindingTheMailInboxEmptyDropsTheShownMails`: a refresh returning an empty first page (all mail archived elsewhere) drops the shown mails, ends paging and stores the empty state in the cache (the `page.items.lastOrNull() == null` branch).
- `NewViewModelTest.theFirstLoadEndsOnceTheInboxAnswersInAnyWay`: `NewUiState.firstLoad` decision table (loaded, offline, failed Inbox; mail still loading with and without rows), which decides between the loading text and the empty text.
- `NewScreenTest.rowsShowNotesSenderAndTodayAsTimeOlderAsDate`: a task row shows its notes, blank notes are hidden, a mail row shows "sender – snippet", a mail without sender/snippet shows no such line, today's rows show the time, older rows the date, undated rows no date.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 "New" screen in the app navigation | `NewScreenInAppTest` (both), `CockpitDestinationTest` (order, existing) | `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.newitems.*' ktlintCheck lintDebug` | Pass |
| AC1 merged list newest first with source marker | `NewModelsTest.mergesTasksAndMailsNewestFirst`, `rowsWithoutTimeComeLast`, `rowsSortByTheirOwnTime`, `aTaskAsOldAsTheOldestLoadedMailIsShown`, `NewViewModelTest.showsTasksAndMailsMergedAndKeepsTheMailForOffline`, `NewScreenTest.rowsShowTheirSource`, `rowsShowNotesSenderAndTodayAsTimeOlderAsDate`, `NewScreenInAppTest` (source marker) | same | Pass |
| AC1 filter All / GTD Inbox / Mail | `NewModelsTest.inboxFilterShowsOnlyTasks`, `mailFilterShowsOnlyMails`, `theInboxFilterShowsEvenOldTasksWhileMoreMailIsToLoad`, `NewViewModelTest.theInboxFilterHidesTheMails`, `NewScreenTest.tappingRowsOpensTheItemAndTheFilterIsChosen`, `theChosenFilterIsSelected`, `loadMoreIsHiddenForTheInboxFilterAndAfterTheLastPage` | same | Pass |
| AC1 filter remembered | `NewViewModelTest.theFilterIsRememberedAcrossViewModels`, `choosingTheShownFilterAgainWritesNothing`, `NewStoresTest.aChosenFilterIsReadBackByANewStore`, `anUnknownStoredFilterFallsBackToAll`, `malformedStoredFiltersFallBackToAll`, `theFilterIsAllUntilOneIsChosen` | same | Pass |
| AC1 tap opens the task | `NewRouteTest.tappingATaskOpensItAndBackReturnsToTheList`, `anOpenTaskProcessedElsewhereReturnsToTheList`, `NewScreenTest.theTaskViewShowsTheTaskReadOnlyAndBackCloses`, `aTaskWithoutTimestampShowsNoCaptureTime`, `NewViewModelTest.anOpenTaskIsFoundWhileTheListHidesOrHoldsItBack` (CR-5) | same | Pass |
| AC1 tap opens the mail read view | `NewScreenInAppTest."tapping a mail opens the mail read view and back returns to the list"`, `NewViewModelTest.anOpenedMailIsShownAsReadAndKeptSoForOffline`, `aRemovedMailLeavesTheList` | same | Pass |
| AC1 mail row offers "→ Inbox task" | `NewScreenTest.onlineMailCanBecomeATask`, `aMailBeingConvertedCannotBeConvertedAgain`, `aCreatedTaskIsAnnouncedAndCanBeDismissed`, `aConversionThatDidNotWorkSaysWhy`, `NewViewModelTest.toTaskCreatesAnInboxTaskAndReloadsTheInbox`, `aSecondTapWhileTheTaskIsCreatedDoesNothing`, `aFailedConversionSaysSoAndDoesNotReloadTheInbox`, `anUnreachableServerDuringConversionSaysNothingWasCreated`, `aMailThatIsNotListedCannotBecomeATask` | same | Pass |
| AC1 cached data offline, mail part read-only offline | `NewViewModelTest.offlineShowsTheCachedMailReadOnly`, `theLastLoadedMailIsShownAtOnceFromTheCache`, `offlineWithoutAnyCachedMailShowsOnlyTheInbox`, `offlineTheCachedMailHoldsNoTaskBack`, `aFailedMailLoadHoldsNoTaskBack` (CR-2), `NewScreenTest.offlineShowsTheBannerAndMailIsReadOnly`, `offlineWithoutLoadedMailSaysSo`, `offlineNamesWhenEachPartWasLoaded`, `anOfflineInboxAloneKeepsMailConvertible` | same | Pass |
| Assumption: logout drops the in-memory mail (CR-1 race fix) | `NewStoresTest.aLogoutDropsTheCachedMail`, `aWriteFromBeforeALogoutIsDropped`, `NewViewModelTest.aRefreshAnsweringAfterALogoutKeepsNothing`, `aLoadMoreAnsweringAfterALogoutKeepsNothing`, `aViewModelFromBeforeALogoutKeepsNothing` (existing; reviewed, no gap found) | same | Pass |
| Paging and request ordering | `NewViewModelTest` loadMore/refresh tests (append, dedup, guards, offline/failed loadMore, refresh keeps pages, refresh cancels loadMore, older refresh answering last), `aRefreshFindingTheMailInboxEmptyDropsTheShownMails` | same | Pass |
| AC2 newitems tests pass, `ktlintCheck lintDebug` pass | whole newitems package (98 tests) | same | Pass (BUILD SUCCESSFUL, 98 tests, 0 failures) |

## Focused test execution

Command:

`cd android && ./gradlew ktlintFormat testDebugUnitTest --tests 'ch.sonensei.cockpit.newitems.*' ktlintCheck lintDebug`

Result:

Pass (98 tests in 6 classes, 0 failures, 0 skipped; ktlint and Android lint clean; `ktlintFormat` changed no production file).

## Full test execution

Command:

`cd android && ./gradlew testDebugUnitTest createDebugUnitTestCoverageReport` (whole Android unit suite)

Result:

Pass (BUILD SUCCESSFUL in 1m 50s).

## .taskcheck

Result:

Pass: `sh scripts/check.sh` (tfp, server, web, android) -> exit 0, "all checks passed". The web build prints a pre-existing bundle-budget warning (not related to this task).

## Bugs found

None. No production defect was found on the final revision (8328e25c8).

## Coverage

Status: verified (fresh run on the final reviewed code plus the new tests).

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured (the project's JaCoCo setup has no changed-line report). Since 4 of the 6 files are new in this task, whole-file coverage equals changed-line coverage for them; `CockpitShell.kt` and `CockpitDestination.kt` changed 1 line each, both covered.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `and-new-view`; base 9bdb47487fa969cc3f6d8d2e5d71a72c8d2ea2ee; HEAD 8328e25c874a4d07643c0f6d667584aac99a0d5d; commits 09b39ff71, da90f482d, c3de9348c, 090f98a6e (production/test), e614cbe74, 8328e25c8 (reviews only), from the parent worker and `git log 9bdb47487..HEAD`; file list from `git diff --name-only 9bdb47487..HEAD -- android/app/src/main`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD 8328e25c8 + uncommitted test changes; `git diff HEAD -- android | sha256sum` = `7f7214037b4317c6d556726fefd26371a5956ce94b1be49c17f77db3c01d43ec`, `NewScreenInAppTest.kt` sha256 `859ad0246d12da6e17658bf74e7abc16b4915e913809d97a6fe1da8e3cba63f2`. No production file is modified in the working tree.
- Exact included files: `android/app/src/main/java/ch/sonensei/cockpit/newitems/{NewModels,NewStores,NewViewModel,NewScreen}.kt`, `android/app/src/main/java/ch/sonensei/cockpit/ui/CockpitShell.kt`, `android/app/src/main/java/ch/sonensei/cockpit/ui/navigation/CockpitDestination.kt`. Excluded: `res/values/strings.xml`, `res/values/strings_new.xml` (resources, not instrumented); Hilt/KSP-generated Java (`*_Factory`, `*_HiltModules*`) and inline-function pseudo source files (`Comparisons.kt`, `LazyDsl.kt`, `LifecycleEffect.kt`) that JaCoCo lists under the package (generated, not task source). No renamed or deleted files.
- Provider/version, command, working directory, and test selection: JaCoCo via AGP `enableUnitTestCoverage` (version is AGP's default, not pinned in the build files); `./gradlew testDebugUnitTest createDebugUnitTestCoverageReport` in `android/`; whole Android unit test suite.
- Run start/completion time and exit status: started 2026-10-26T10:31:18Z, finished 10:33:13Z, BUILD SUCCESSFUL (JaCoCo session `dell26-bd6b573e`, start 10:31:20Z, dump 10:33:07Z).
- Generated report paths and report hashes/run identifier: `android/app/build/reports/coverage/test/debug/report.xml` (sha256 `9e374d6c76fda525e42a8d2b7a4cf6811dbe5364cc95ff424f727159b5c1db56`), `index.html` next to it.
- Evidence outputs are fresh and inputs stayed unchanged: yes; the report's session times fall inside the run, and the snapshot hashes were identical before and after the run.
- Final run after latest test additions and production fixes: yes (includes fix commit 090f98a6e and all tests added here).

Values below use covered/total (percentage); counters from the JaCoCo XML `<sourcefile>` elements (Statements = INSTRUCTION).

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `newitems/NewModels.kt` | 158/158 (100.0%) | 16/16 (100.0%) | 12/12 (100.0%) | 18/18 (100.0%) |
| `newitems/NewStores.kt` | 142/143 (99.3%) | 4/4 (100.0%) | 15/16 (93.8%) | 23/24 (95.8%) |
| `newitems/NewViewModel.kt` | 1230/1233 (99.8%) | 68/68 (100.0%) | 52/53 (98.1%) | 145/145 (100.0%) |
| `newitems/NewScreen.kt` | 4094/4286 (95.5%) | 157/183 (85.8%) | 64/74 (86.5%) | 318/323 (98.5%) |
| `ui/CockpitShell.kt` | 1031/1045 (98.7%) | 47/49 (95.9%) | 27/27 (100.0%) | 86/87 (98.9%) |
| `ui/navigation/CockpitDestination.kt` | 317/317 (100.0%) | 9/9 (100.0%) | 12/12 (100.0%) | 33/33 (100.0%) |
| **Aggregate (summed counters, 6 files above; resources and generated files excluded as listed)** | 6972/7182 (97.1%) | 301/329 (91.5%) | 182/194 (93.8%) | 623/630 (98.9%) |

Before this report's tests (same command, report regenerated at 10:27Z on HEAD without the new tests): `NewViewModel.kt` branches 64/68, `NewScreen.kt` instructions 3908/4286, branches 144/183, lines 307/323.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `NewScreen.kt:125,134-135,159-161` | Reply/forward from a mail opened in the New view opens `MailComposeRoute` (the `composing` state) | Left uncovered: not in the acceptance criteria (they require opening the read view, now covered in-app); the compose screen itself is tested in `mail/compose`. Follow-up optional. |
| `NewScreen.kt:176-183` | Default no-op lambdas of `NewCallbacks` | Non-material (test convenience defaults). |
| `NewScreen.kt:120, 207, 213, 491, 496` | Default parameter values (`hiltViewModel()`, `Clock.systemDefaultZone()`, `rememberLazyListState()`) | Non-material; the Hilt default is exercised in-app, JaCoCo counts the synthetic default-argument branches separately. |
| `NewScreen.kt:81` (1 branch) | `NewScreen` placeholder outside a Hilt activity | Both real branches run (placeholder line 85 and Hilt line 83 are covered); the remaining branch count is Compose-generated. |
| `NewScreen.kt:422` (1 branch) | Offline banner when the Inbox is offline but was never loaded (`inboxRefreshedAt == null`) | Non-material display edge (the banner then names only the mail part); no test added. |
| `NewScreen.kt:304, 441, 474-480, 529` (partial branches) | Compose-generated `when`/lambda branches in rows, banners, notice | Behavior is covered (row tap, retry disabled while loading, all three notices, blank task notes); remaining counts are compiler-generated. |
| `NewStores.kt:67` | Default no-op `NewFilterStore.write` | Non-material (interface default; production store overrides it). |
| `NewViewModel.kt:45` | Synthetic default of the `inbox` constructor parameter | Non-material. |
| `CockpitShell.kt:67-68, 149, 168` | Pre-existing shell code outside this task's 1-line change | Out of task scope. |

## Unable to test

- Scroll position kept when returning from an opened row (CR-4 fix): not tested; it is Compose state hoisting, and asserting a restored `LazyListState` across replaced content in Robolectric would test Compose rather than task behavior. No follow-up needed.
- Instrumented device / emulator behavior: not run (the criteria name only the JVM unit tests with Robolectric/Compose, which ran).

## Summary

Tests and task-scoped coverage are sufficient; no production defects found.
