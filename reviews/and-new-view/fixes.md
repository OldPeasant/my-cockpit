# Fix Report

Date: 2026-10-26

Task: and-new-view - Android combined New view

Scope: current task; findings CR-1..CR-5 of `reviews/and-new-view/review.md`. Fix commit 090f98a6e (on top of c3de9348c).

## Fixed findings

### CR-1 — A mail load still running at logout puts the previous user's mails back into the in-memory cache

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/newitems/NewStores.kt`
- `android/app/src/main/java/ch/sonensei/cockpit/newitems/NewViewModel.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/newitems/NewStoresTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/newitems/NewViewModelTest.kt`

Problem:
`remember()` wrote `cache.last` unconditionally, so a refresh/loadMore finishing after `SessionCache.clear()` restored the previous session's mails.

Fix:
Following `AgendaCache`, `NewMailCache` now has a session counter incremented by `clear()`, `session()` to read it and `store(session, mail)` that drops writes from an older session (all under one lock); `last` is no longer publicly settable. `NewViewModel` captures the session when it is created (before reading `cache.last`) and passes it to every write, so neither a load in flight at logout nor a view model that outlived the logout (including `markSeen`/`removed`) can write the old mails back.
Tests: `NewViewModelTest.aRefreshAnsweringAfterALogoutKeepsNothing` (clear while the fake `MailRepository` call is suspended, complete it, `cache.last` stays null), `aLoadMoreAnsweringAfterALogoutKeepsNothing`, `aViewModelFromBeforeALogoutKeepsNothing`, `NewStoresTest.aWriteFromBeforeALogoutIsDropped`. Mutation check: with the session comparison removed, these 4 tests fail.

Validation:
- `./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.newitems.*'` — Pass

### CR-2 — Offline (or after a failed mail load), the All filter hides cached GTD Inbox tasks

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/newitems/NewViewModel.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/newitems/NewViewModelTest.kt`

Problem:
`mergeNew` held back older tasks whenever a cursor existed, even when the next page could not load.

Fix:
The view model passes `moreMail = nextCursor != null && !offline && !failed` to `mergeNew`; the UI `moreMail` flag (the "Load older messages" button) is unchanged. New tests `offlineTheCachedMailHoldsNoTaskBack` and `aFailedMailLoadHoldsNoTaskBack`. The existing tests `anOfflineLoadMoreKeepsTheLoadedMailsAndItsCursor` and `aFailedLoadMoreKeepsTheLoadedMails` asserted the old hold-back (the older task hidden after an offline/failed loadMore). That contradicts the accepted finding, so their expected keys now include that task. Their other assertions are unchanged.

Validation:
- `./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.newitems.*'` — Pass

### CR-3 — Main paging and concurrency paths of NewViewModel are untested

Files changed:
- `android/app/src/test/java/ch/sonensei/cockpit/newitems/NewStoresTest.kt`

Problem:
Paging, concurrency, conversion and store paths lacked tests at the reviewed revision.

Fix:
Checked the current tests (after test-writer commits da90f482d, c3de9348c). Already covered: refresh merge (`refreshKeepsThePagesLoadedBehindTheFirst`, `refreshDropsMailsThatLeftTheFirstPage`, `refreshWithoutOverlapStartsOverWithTheNewPage`, `refreshThatReturnsTheWholeInboxReplacesThePages`), loadMore append/dedup/offline/failure/guards, `refreshCancelsARunningLoadMore`, toTask offline/failure/double tap, the SessionCache clear (`aLogoutDropsTheCachedMail`) and an unknown stored filter. The only gap was malformed stored filter values: added `malformedStoredFiltersFallBackToAll` ("", "mail", " MAIL", "INBOX,MAIL"). The session race is covered by the CR-1 tests.

Validation:
- `./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.newitems.*'` — Pass

### CR-4 — Returning from the mail read view or the task view scrolls the New list back to the top

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/newitems/NewScreen.kt`

Problem:
The `LazyColumn` state lived inside `Rows` and was lost while a row was open.

Fix:
`NewRoute` hoists `rememberLazyListState()` and passes it to `NewContent` (new parameter `listState`, defaulting to `rememberLazyListState()` so the screen tests still work) and on to `Rows`, the same way `MailRoute` does it. No dedicated test: scroll restoration is plain Compose state hoisting. The existing route and screen tests still pass.

Validation:
- `./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.newitems.*' ktlintCheck lintDebug` — Pass

### CR-5 — An opened task is looked up in the filtered and held-back rows

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/newitems/NewViewModel.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/newitems/NewViewModelTest.kt`

Problem:
`NewUiState.task(id)` searched `rows`, so a held-back or filtered-out task closed the task view.

Fix:
`NewUiState` has a new `inbox` field (the full Inbox list). `task(id)` searches that list, so only a task that left the Inbox closes the view. `theUiStateFindsOnlyListedTasks` was replaced by `theUiStateFindsOnlyInboxTasks`, because the old test asserted the behavior the finding rejects. New test: `anOpenTaskIsFoundWhileTheListHidesOrHoldsItBack` (task held back under All, hidden under Mail, still found, and `null` once it leaves the Inbox). `NewRouteTest.anOpenTaskProcessedElsewhereReturnsToTheList` still passes.

Validation:
- `./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.newitems.*'` — Pass

## Not fixed

None.

## Validation summary

Focused tests:
- `cd android && ./gradlew ktlintFormat` — Pass
- `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.newitems.*' ktlintCheck lintDebug` — Pass (93 tests in newitems, 0 failures)

Full test suite:
- `sh scripts/check.sh` (`.taskcheck`: tfp, server, web, android) — Pass ("all checks passed")

Lint/type-check/build:
- `ktlintCheck lintDebug` — Pass

`.taskcheck`:
- Pass

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed.
