# Code Review

Date: 2026-11-01

Task: and-logbook - Android: Logbook (completed tasks, reopen)

Scope: `git diff 8d50027a3..HEAD -- android` (implementation eb6d01264, tests 5cced38c5, fix df97a5273 for the earlier CR-1, test-only commit 5e312ada0); committed changes only

Requirements:
- `tasks/and-logbook.md` acceptance criteria and its three recorded assumptions (read-only detail dialog with Reopen; pages of 50, reload of as many pages as shown, not cached offline; Logbook in More right after Someday)
- `AGENTS.md` project notes and conventions; `docs/ARCHITECTURE.md` conventions
- Earlier review (`reviews/and-logbook/review.md` at 70b9a904d) and fix report `reviews/and-logbook/fixes.md`
- Existing GTD list screens for comparison: `LiveTaskListViewModel` (`ListViewModels.kt`), `ListUi.kt` helpers, `TaskCompletionRepository`, `GtdRefreshTriggers`

Git range:
- Branch: main
- HEAD: 5e312ada0a7e2252aeafa509c767df829146fd78
- Base/start: 8d50027a3173f2e01426abf6611a01bc943dd06c
- Commits reviewed: 5 (eb6d01264, 5cced38c5, 70b9a904d report only, df97a5273, 5e312ada0 test-only)

Summary: 0 P0, 0 P1, 0 P2, 1 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | "late answer is ignored" assertion reads the cached StateFlow value and cannot fail**
    - File: `android/app/src/test/java/ch/sonensei/cockpit/gtd/lists/LogbookViewModelTest.kt:597`
    - Category: Tests
    - Problem: In `a first load cut off by leaving the screen loads again when shown again, and its late answer is ignored`, the check `assertEquals(emptyList<LoggedTask>(), vm.uiState.value.tasks)` runs after the last subscriber left and the stop timeout passed. At that point `uiState` (built with `stateIn(..., WhileSubscribed(...))`) no longer collects its upstream, so `.value` is the frozen last value and would stay empty even if the cancelled reload had written `old` into the internal state. The assertion after resubscribing cannot catch it either, because the new reload replaces `tasks` with `late, taxes`.
    - Why it matters: The test name claims coverage of "its late answer is ignored", but no assertion would fail if that regressed. This is a small gap: in the current code the cancelled `page()` call never resumes.
    - Suggested fix: Make the second `page(null)` call suspend on a second `CompletableDeferred`, resubscribe, and assert that `tasks` is still empty and `loading` is true before completing it. That reads fresh state through an active subscription. Otherwise rename the test so it does not claim this.

## Review conclusion

No material issues found. The single P3 is a minor test-quality improvement and does not block completion.

Notes on items checked and not reported:
- Earlier CR-1 (reloads while the Logbook is off screen) is resolved correctly. The `init` launch is gone; the reload pipeline is the private `reloading` flow (`LogbookViewModel.kt:108-112`), a `combine` input of `uiState` shared with `SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS)`, the same lifecycle as `LiveTaskListViewModel`. `reloading` emits `Unit` once so `combine` can produce values, then only runs `collectLatest { reload() }`; UI updates still come from `state`. On (re)subscription `GtdRefreshTriggers.on` emits once, so the screen loads exactly once with as many pages as were shown. A stop-timeout cancellation in the middle of `reload()` leaves a consistent state. A reload requested by `reopen` while nobody is subscribed is dropped by the unreplayed `reloads` flow, which is harmless because resubscription reloads.
- Tests added in 5e312ada0 were reviewed. Two of them meaningfully pin the CR-1 behaviour: (1) no reload when coming back within the stop timeout, and a reload on a change afterwards; (3) a reopen finishing while off screen removes the task, clears `busy`, keeps the snackbar, and coming back reloads once. Test (2) is covered by CR-1 above, apart from its valid reload-on-return checks.
- All five acceptance criteria are implemented in code: Logbook in More after Someday (`CockpitDestination.kt`, `CockpitShell.kt`); newest-first list grouped by local completion day with project/context text and Today/Yesterday headers; paging on scroll (`LoadMoreAtEnd`, including a short first page) with a retryable footer; detail dialog on tap and Reopen as row and dialog action via `TaskCompletionRepository.undo` (If-Match with the listed version, 404/409/412 mapped to CONFLICT plus reload); loading/empty/error/stale states reuse `Centered`, `LoadFailed`, `StaleBanner` and `LiveListTags`; 15 new strings in both `values` and `values-de` (`LogbookStringsTest` checks parity).
- The paging races noted in the earlier review (a "load more" answering before or after a running reload) remain consistent thanks to id de-duplication and `canLoadMore`; they cost at most a repeated page request and are covered by tests.
- Validation at 5e312ada0: `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` -> BUILD SUCCESSFUL. Gradle reported the tasks up to date, so the results come from the test-writer's build of the same inputs. `TEST-ch.sonensei.cockpit.gtd.lists.LogbookViewModelTest.xml` shows 32 tests, 0 failures, 0 errors, 0 skipped, including the three new tests.
- `python tasks.py verify and-logbook` showed all five criteria still unrecorded at review time; recording evidence is the parent worker's step before `done` and is not a finding against the code.
