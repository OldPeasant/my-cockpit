# Fix Report

Date: 2026-11-08

Task: and-mail-search-nav (Android Mail: previous/next in the read view and back to origin)

Scope: current task, findings CR-1 and CR-2 of `reviews/and-mail-search-nav/review.md`

## Fixed findings

### CR-1 — The next-page request from the read view runs only once

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/mail/list/MailScreen.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/mail/list/MailReadNavigationInAppTest.kt`

Problem:
`LaunchedEffect(atLoadedEnd) { if (atLoadedEnd) viewModel.loadMore() }` ran only when the open mail became the last loaded one. `MailListViewModel.loadMore()` does nothing while a refresh (`loading`) or another page load (`loadingMore`) runs, so when the last loaded mail was opened during the resume refresh, the request was lost and Next stayed disabled although `nextCursor` said more mail exists.

Fix:
In `MailRoute`, the effect (keyed on `atLoadedEnd` and the open mail) still tries once when the last loaded mail is opened, also after an earlier failure, so the behavior of the existing test "the last loaded mail fetches the next page so next can go on" is kept. It then watches `viewModel.uiState` and calls `loadMore()` again each time the state changes to "the open mail is the last loaded one, a next cursor exists, no refresh or page load runs, and the last load did not fail". A failed page load (`message` OFFLINE or FAILED) is not retried by itself, so nothing loops while offline. The list's Retry is still the way out, as the code comment says. The failure check moved into a small private extension `MailListUiState.pageFailed`, which the list's own near-end loading in `Messages` uses as well (same condition as before). The read view's condition is `MailListUiState.canLoadAfter(id)`. `MailListViewModel` is unchanged.

Regression tests added to `MailReadNavigationInAppTest` (Hilt, in-app):
- `the last loaded mail opened during a refresh fetches the next page once the refresh ends`: the first page loads, then the list's own page-2 load answers Offline. The activity goes STARTED and back to RESUMED, so `LifecycleResumeEffect` starts a refresh whose `mail.messages(Mailbox.Unified, null)` call waits on a `CompletableDeferred`. Mail 2, the last loaded one, is opened while that refresh is pending. The test checks "2 of 2", Next disabled and only one page-2 call. Then it completes the refresh and checks "2 of 3", Next enabled, mail 3 opens, and there are exactly two page-2 calls. Without the fix the page-2 request is never repeated and the wait for "2 of 3" times out.
- `a failed next page from the read view is not retried by itself`: page 2 always answers Offline. Opening the last loaded mail makes exactly one more request (two in total with the list's own), with no loop, and Next stays disabled.

Validation:
- Gradle cannot be run by this sub-agent (blocked in this environment). The parent worker must run, from `android/`:
  - `./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.mail.list.MailReadNavigationInAppTest'` — Not run here
  - `./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.mail.list.*' --tests 'ch.sonensei.cockpit.mail.read.*'` — Not run here
  - `./gradlew ktlintCheck lintDebug` — Not run here

### CR-2 — No test for removing the last mail, or for previous/next being disabled while an action runs

Files changed:
- none (the test-writer had already added the tests)

Problem:
The review found no test for the `else close()` branch after archive/delete/spam at the end of the list, and none for the `enabled = !state.busy` gate on Previous/Next.

Fix:
No change needed. I checked that the current code has these tests:
- `MailReadNavigationInAppTest`: `deleting the last mail of the mailbox returns to the list` and `deleting the last search result returns to the same search` cover the close branch for the mailbox and for search results.
- `android/app/src/test/java/ch/sonensei/cockpit/mail/read/MailReadNavigationScreenTest.kt`: `previous and next wait while an action runs, the position stays shown` renders `MailReadUiState(busy = true)`, checks that PREVIOUS and NEXT are not enabled, and checks that clicking them calls nothing.

Validation:
- Covered by the same parent-run commands as CR-1 (`MailReadNavigationInAppTest`, `MailReadNavigationScreenTest`) — Not run here

## Not fixed

None.

## Validation summary

Focused tests:
- Not run by the fixer (Gradle is blocked for sub-agents). The parent must run `./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.mail.list.MailReadNavigationInAppTest' --tests 'ch.sonensei.cockpit.mail.read.MailReadNavigationScreenTest'` in `android/`.

Full test suite:
- Not run (parent: `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug`).

Lint/type-check/build:
- Not run (parent: `./gradlew ktlintCheck lintDebug`). I followed ktlint_official style: each operand of the multiline condition is on its own line, and imports are in lexicographic order (`androidx.lifecycle.Lifecycle`, `kotlinx.coroutines.CompletableDeferred`).

`.taskcheck`:
- Not run (parent: `sh scripts/check.sh`).

Remaining unresolved findings:
0

## Final status

All validated findings fixed. The parent worker still has to run the validation (Gradle could not run in the fixer).
