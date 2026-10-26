# Code Review

Date: 2026-10-26

Task: and-new-view - Android combined New view

Scope: `git diff b89b57de81d08813ab3cd4a433c0c220d82d33de..9a7c9b97b94579d101a4d66c83ad490c456e9e93` (final revision, rebased onto latest main). Code commits: 457bf1998 (feature), fa87fab1e, afd83adc9, 9a7c9b97b (tests), 0b5b18d01 (fixes CR-1..CR-5 of the previous review). Commits 6998620ac and f611e4070 touch only `reviews/`. This covers the `newitems` package and its tests, `ui/CockpitShell.kt`, `ui/navigation/CockpitDestination.kt` and its test, `res/values/strings.xml` and `res/values/strings_new.xml`.

Requirements:
- tasks/and-new-view.md (both acceptance criteria and the three recorded assumptions)
- SPEC.md §5.3 "New view", §7.3, §9.2, §9.3
- AGENTS.md project notes, docs/ARCHITECTURE.md conventions
- Previous review, fix report and test report in reviews/and-new-view/
- Existing Android code used for comparison: `calendar/agenda/AgendaCache.kt` (session-guarded cache), `auth/AuthRepository.kt` and `auth/CockpitRoot.kt` (SessionCache, logout), `mail/list/MailScreen.kt` (hoisted list state)

Git range:
- Branch: main
- HEAD: 9a7c9b97b94579d101a4d66c83ad490c456e9e93
- Base/start: b89b57de81d08813ab3cd4a433c0c220d82d33de
- Commits reviewed: 7 (5 with code changes, 2 that touch only reviews/)

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Review conclusion

No material issues found.

How each earlier finding was fixed, checked against the current code:
- CR-1 (logout race): fixed. `NewMailCache` now has a session counter under one lock. `clear()` increments it, `store(session, ...)` drops writes from an older session, and `last` has a private setter, the same pattern as `AgendaCache`. `NewViewModel` reads `cache.session()` before it reads `cache.last` (property initialisation order, `NewViewModel.kt:115-117`), and every write goes through `remember()` with that session. As a result, neither an in-flight refresh or loadMore nor a view model that outlives the logout can restore the old mail. `CockpitRoot` drops the shell on logout, so a view model created after the next login gets the new session. Tests: `aRefreshAnsweringAfterALogoutKeepsNothing`, `aLoadMoreAnsweringAfterALogoutKeepsNothing`, `aViewModelFromBeforeALogoutKeepsNothing`, `NewStoresTest.aWriteFromBeforeALogoutIsDropped`.
- CR-2 (tasks held back offline): fixed. `mergeNew` receives `nextCursor != null && !offline && !failed` (`NewViewModel.kt:125`). The "Load older messages" button still uses the plain cursor. Tests: `offlineTheCachedMailHoldsNoTaskBack` and `aFailedMailLoadHoldsNoTaskBack`. The two loadMore tests were changed to expect the corrected behavior, which is consistent with the accepted finding.
- CR-3 (untested paths): resolved. Refresh merge, loadMore append/dedup/offline/failure/guards, a refresh cancelling a loadMore, an older refresh answering last, the toTask notices and double tap, the session clear, and unknown or malformed stored filters all have tests now.
- CR-4 (scroll position): fixed. `NewRoute` hoists `rememberLazyListState()` and passes it to `Rows`.
- CR-5 (task lookup): fixed. `NewUiState.task(id)` searches the full `inbox` list. `anOpenTaskIsFoundWhileTheListHidesOrHoldsItBack` covers this.

Acceptance criteria checked against the code: the New entry is first in the More list with route `new`, and the order is covered by `CockpitDestinationTest`. Rows are merged newest first with a tie-break on key, and undated rows come last. Each row shows a source marker. The All / GTD Inbox / Mail filters are stored in SharedPreferences `new_view`, and an unknown or malformed value falls back to All. Tapping a row opens the read-only task view or `MailReadRoute`, which reports seen and removed back to the list. The mail row offers "→ Inbox task": disabled offline, guarded against double taps, the mail stays, and the Inbox is refreshed after success. Offline, the view shows the Room Inbox and the in-memory mail page read-only, with a banner. No mail is written to disk and no mail or task content is logged.

Validation run by this reviewer at HEAD:
- `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.newitems.*' --tests 'ch.sonensei.cockpit.ui.navigation.*'`: 98 newitems tests (6 classes) and 3 navigation tests, 0 failures, 0 skipped.
- `cd android && ./gradlew ktlintCheck lintDebug`: passed with no output.

Open for the parent worker, not a finding: `tasks.py verify and-new-view` still shows both criteria unverified. Record the evidence before running `done`.
