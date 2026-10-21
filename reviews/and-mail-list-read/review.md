# Code Review

Date: 2026-10-21

Task: and-mail-list-read - Android mail list and reading

Scope: all committed changes of the task under `android/` (`git diff c09b5f6e..7b14fdfd -- android`), commits e4fab28b, bd14278e, f01eb4b9, 7b14fdfd. This is a re-review. It checks the fixes for the earlier review's CR-1 to CR-6 (`reviews/and-mail-list-read/fixes.md`) and looks for new issues. Uncommitted changes were ignored.

Requirements:
- tasks/and-mail-list-read.md: acceptance criteria and the recorded assumptions (hand-written DTOs; WebView blocks all network loads; swipe right = archive, swipe left = delete; opening a mail marks it read)
- SPEC.md §3 "HTML email is untrusted", §9.2 item 5
- docs/ARCHITECTURE.md, AGENTS.md project notes
- reviews/and-mail-list-read/fixes.md (fix claims)

Git range:
- Branch: main
- HEAD: 93418b24 (adds only `reviews/and-mail-list-read/fixes.md` on top of 7b14fdfd; `git diff --stat 7b14fdfd..HEAD` shows no code change)
- Base/start: c09b5f6e04edd8778a4aa1f4ab40d109fd6dc82f
- Reviewed revision: 7b14fdfd53298f3e176710b3cac19f689dfac576
- Commits reviewed: 4

Summary: 0 P0, 0 P1, 1 P2, 1 P3

Fixes from the earlier review, checked against the code at 7b14fdfd:
- CR-1 fixed. `MailListViewModel.act()` (`:109`, `:113-129`) records the mailbox it acted in. A refused mail goes back only into that same mailbox, and only if the mail is not already in the list. A successful call removes the id again, so a reload that overtook the call cannot bring the mail back. Both cases have tests in `MailListViewModelEdgeCasesTest`.
- CR-2 fixed. `MailReadRoute` uses a single unkeyed `hiltViewModel()`. `open()` and `close()` call `reset()`, which clears the state and increments `session`. `updateFor()` drops answers from an earlier session. Every close path goes through `close`: toolbar back, the `BackHandler` (moved into `MailReadRoute`), and the close after a removal. A configuration change re-runs `open(id)` with the same id, which returns early, so the mail is not reloaded. Tests are in `MailReadViewModelTest` and `MailRouteInAppTest`.
- CR-3 fixed. The tests in f01eb4b9 cover the cases the finding listed.
- CR-4 fixed, but the fix introduces a new problem (see CR-1 below).
- CR-5 fixed. `mergeFirstPage()` keeps the pages loaded after the first page only when all of these hold: same mailbox, already loaded, the first page has a next cursor, and the page's last mail is already in the list. In every other case it replaces the list, so the list never has a gap.
- CR-6 fixed. `MailWebViewClient` calls `onLink` only when `hasGesture() && isForMainFrame`. It still returns true for every navigation. Covered by `MailWebViewTest`.

Unchanged and still correct: the WebView lock-down (no JavaScript, no file or content access, `blockNetworkLoads`, no DOM storage, no multiple windows), the CSP `default-src 'none'`, and the DTO and endpoint alignment checked in the earlier review. No mail content is logged.

Validation: I was not allowed to run Gradle in this session (permission denied). The existing JUnit results in `android/app/build/test-results/testDebugUnitTest` (written 2026-10-21T16:38Z, the time of 7b14fdfd) show 0 failures. Examples: MailListViewModelEdgeCasesTest 15/15, MailListScreenStatesTest 10/10, MailRouteInAppTest 4/4, MailReadViewModelTest 14/14, MailWebViewTest 4/4. The parent worker must re-run `cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.mail.*' lintDebug` to record the evidence.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | Infinite scroll now retries a failed page in a tight loop with no backoff**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/mail/list/MailScreen.kt:203`
    - Category: Correctness
    - Problem: After the CR-4 fix, the trigger emits `nearEnd && nextCursor != null && !loading && !loadingMore`, filtered by `distinctUntilChanged`. Each `loadMore()` sets `loadingMore = true`, so the trigger emits false. When the call fails (`MailListViewModel.kt:93-94`, Offline or Failed), `loadingMore` goes back to false and `nextCursor` is kept. The trigger emits true again, and `loadMore()` fires again right away. This repeats for as long as the list stays near its end. Nothing stops it: no failure flag, no delay, no check on `message`. The same loop starts after a failed `refresh()` while the list is near its end and a cursor exists. Offline, OkHttp fails within milliseconds, so the app sends requests about once per frame. On a server 5xx, every attempt reaches the server. The test `near the end the next page is asked for again once a load ends` (`MailListScreenStatesTest`) asserts this retry after a failure ("That page failed and kept its cursor: the next attempt follows when it ends").
    - Why it matters: A phone that is offline or behind a failing server, with the mail list scrolled to its end, keeps sending requests until the user scrolls away. This drains the battery, uses data, and loads the server. It is a retry loop on the failure path, which unit tests on the success path do not show.
    - Suggested fix: Do not retry automatically after a failed page. Options: add a `loadMoreFailed` flag (or check `message == null` for OFFLINE/FAILED) to `canLoadMore`, and clear it on a successful load, a refresh or `select`. The existing Retry button already calls `refresh()`, and the CR-5 merge keeps the loaded pages, so the user can retry by hand. Alternatively, add a backoff. Change the screen test so that a failed page is not asked for again until the failure is cleared, and keep the "load ends, then retry" case for a load that did not fail.

## P3 Low

- [ ] **CR-2 | A mail closed before it loaded is still marked read on the server, but stays unread in the list**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/mail/read/MailReadViewModel.kt:133-136`
    - Category: Correctness
    - Problem: In `load()`, the `Ok` branch drops the state update through `updateFor(opened)` when the session changed, but it still calls `mail.markRead(...)` without checking the session. If the user opens a mail and goes back before it loads, the server marks it read, but `state.message` is never set. `MailReadRoute`'s `onSeen` therefore never runs, and the list keeps the unread dot until the next refresh. The mail was also never shown to the user.
    - Why it matters: The list and the server disagree, and a mail the user never saw counts as read. This is a small inconsistency, not data loss.
    - Suggested fix: Call `markRead` only when `opened == session`, the same check `updateFor` makes. That keeps "opening marks it read" meaning "shown". Add a view-model test: close the mail before it loads, and expect no `markRead`.

## Review conclusion

Material issues found; fix before completing the task.
