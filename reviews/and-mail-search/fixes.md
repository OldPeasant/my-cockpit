# Fix Report

Date: 2026-11-01

Task: and-mail-search - Android mail: search box with 'search on server' option

Scope: current task (base 4f351d00b, implementation HEAD 7cc2dddb0), findings CR-1, CR-2, CR-3 of `reviews/and-mail-search/review.md` and two failing tests reported by the parent worker from the run of the test-writer's new tests (`reviews/and-mail-search/tests.md`).

## Fixed findings

### CR-1 (P2) - Returning from the read view resets the search results to the top

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/mail/list/MailScreen.kt`
- `android/app/src/main/java/ch/sonensei/cockpit/mail/list/MailSearchContent.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/mail/list/MailSearchRouteInAppTest.kt` (new test)

Problem:
The results `LazyColumn` had no hoisted state. `MailRoute` removes `MailListContent` while a mail is open, so back from a result rebuilt the results at position 0.

Fix:
`MailRoute` keeps a second `rememberLazyListState()` (`searchState`) next to the mailbox `listState` and passes it through `MailListContent` (new trailing parameter `searchState`, defaulting to `rememberLazyListState()`, so every existing caller compiles unchanged) into `MailSearchContent` and `Results`. A `LaunchedEffect(viewModel)` in `MailRoute` watches the search's (query, server, loading) and scrolls the results to the top whenever it changes (a new query submitted, a server search started, an answer arriving); the current value is skipped (`drop(1)`), so returning from the read view or a configuration change keeps the place. Marking a result read or removing it does not change that key, so it does not scroll.

Validation:
- New route test `back from a result returns to the same place in the results, a new search starts at the top` (30 results, scroll to row 125, open, back: row 125 is shown and row 100 is not composed; submitting again shows row 100). Not run here (Gradle is denied for sub-agents); see "Validation summary".

### Failing test MailSearchRouteInAppTest `search on server asks the servers and names the account that failed, the close button returns`

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/mail/list/MailScreen.kt` (same change as CR-1)

Problem:
This is a genuine production bug, not a test mistake. The cached search showed results [5]; the server answer is [6, 5]. A keyed `LazyColumn` keeps the first visible item by key, so after the answer it stayed on row 5 (now index 1) and the new, newer row 6 sat above the viewport and was not composed. The `waitFor(MailListTags.row(id(6)))` therefore timed out. On a phone the user would see the server's new matches hidden above the top of the list. The ViewModel test passes because the state itself is right; only the list position was wrong.

Fix:
The CR-1 scroll-to-top on a new answer (the loading flag changes from true to false) puts the list back at index 0 when the server answer replaces the results, so row 6 is shown. The test itself was not changed; it stays consistent with CR-2 because the box still holds "invoice", which is the text the server button now sends.

Validation:
- `MailSearchRouteInAppTest` - Not run here; parent to run.

### CR-2 (P3) - "Search on server" can run a different query than the one in the box

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/mail/list/MailListViewModel.kt`
- `android/app/src/main/java/ch/sonensei/cockpit/mail/list/MailScreen.kt` (`SearchCallbacks.server` is now `(String) -> Unit`)
- `android/app/src/main/java/ch/sonensei/cockpit/mail/list/MailSearchContent.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/mail/list/MailSearchScreenTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/mail/list/MailSearchScreenEdgeCasesTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/mail/list/MailSearchViewModelEdgeCasesTest.kt`

Problem:
`searchOnServer()` repeated the last submitted query while the box text lived separately in `SearchBox`, so an edited but unsubmitted text was ignored.

Fix:
The button submits the current text. The box text (`rememberSaveable`) moved from `SearchBox` up to `MailSearchContent`; the server button clears the focus and calls `callbacks.search.server(text)`, and is disabled while the text is blank. `MailListViewModel.searchOnServer(query)` trims and caps the text at `MAX_QUERY`, does nothing for a blank one, keeps the cached results for the same query (unchanged behavior), and starts from an empty `MailSearchState` with `server = true` for an edited query. The parameter defaults to the shown query, so the existing ViewModel tests that call `searchOnServer()` keep their meaning unchanged. The guard `canSearchServer` is unchanged.

Validation:
- `MailSearchScreenTest`: `search on server is offered after a cached search and repeats it` now expects `server x`; new `search on server searches the text in the box, also when it was edited without submitting` (edited text is sent, blank text disables the button).
- `MailSearchViewModelEdgeCasesTest`: new `search on server with text edited since the search searches that text, from empty results` and `search on server with the text searched keeps the cached results until it answers`.
- Not run here; parent to run.

### Failing test MailSearchScreenEdgeCasesTest `search results have no swipe actions`

Files changed:
- `android/app/src/test/java/ch/sonensei/cockpit/mail/list/MailSearchScreenEdgeCasesTest.kt`

Problem:
Under Robolectric the swipe gestures reached the row's `clickable`, so `calls` was `[open id5, open id5]`. Rows legitimately open on a tap (acceptance criterion 1), so the test was wrong, not the code: what it must prove is that no ARCHIVE or DELETE action happens.

Fix:
The assertion ignores `open ` entries (`calls.filterNot { it.startsWith("open ") }` must be empty); the custom accessibility actions check and the row-still-exists check are unchanged, so the test is not weakened for archive and delete. The callback recorder for `server` now records its text (`server {text}`) to match the new callback type.

Validation:
- Not run here; parent to run.

## Not fixed

### CR-3 (P3) - Closing search with system Back is untested

Status:
Invalid (already resolved, no code change)

Reason:
`MailSearchScreenEdgeCasesTest` `the system back closes an open search` takes the `OnBackPressedDispatcher` from `LocalOnBackPressedDispatcherOwner`, calls `onBackPressed()` and asserts that only `close` ran, which exercises the `BackHandler` in `MailSearchContent`. The route test `a result opens the read view, back returns to the results, back again to the mailbox` also closes the search with the activity's back dispatcher. Nothing else needed.

## Validation summary

Focused tests:
- Not run by the fixer: Gradle is denied for sub-agents in this environment. The parent worker must run:
  `cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.mail.list.MailSearch*'`
  which covers exactly these classes: `MailSearchRouteInAppTest`, `MailSearchScreenEdgeCasesTest`, `MailSearchScreenTest`, `MailSearchViewModelEdgeCasesTest`, `MailSearchViewModelTest`, `MailSearchRepositoryTest`.
- Also affected by the `MailListContent` signature (new defaulted parameter, compile only): `MailListScreenTest`, `MailListScreenStatesTest`, `MailListPagingRetryTest`, `MailFolderManageScreenTest`, `MailFolderManageScreenEdgeCasesTest`, `MailFolderManageGermanTest`, `MailRouteInAppTest`, `ch.sonensei.cockpit.mail.compose.MailComposeWiringTest`; the full run below covers them.

Full test suite:
- Not run by the fixer; parent to run `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug`.

Lint/type-check/build:
- Not run by the fixer (part of the command above). Lines were kept below the ktlint_official limit of 140 and the new code follows the existing formatting.

`.taskcheck`:
- Not run (`sh scripts/check.sh` runs Gradle); parent to run.

Remaining unresolved findings:
0

## Final status

All validated findings fixed; validation could not be run by the fixer and is pending the parent worker's Gradle run.
