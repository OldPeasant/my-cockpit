# Code Review

Date: 2026-10-21

Task: and-mail-list-read - Android mail list and reading

Scope: all committed changes of the task under `android/` (`git diff 5943b18008986fe3fe6cbf86258a682c0fc0def2..71c09b02 -- android`), commits ff176d0c, db548006, 64c9b655, 2c3deccc, 9a7bf6c5, ba8aa418, 556605ca, 71c09b02. This is the third review. It checks the fixes in 71c09b02 for the second review's CR-1 (P2) and CR-2 (P3), described in `reviews/and-mail-list-read/fixes.md` ("second round"), and looks again for new issues across the whole task diff. Uncommitted changes were ignored.

Requirements:
- tasks/and-mail-list-read.md: acceptance criteria and the recorded assumptions (hand-written DTOs; WebView blocks all network loads; swipe right = archive, swipe left = delete; opening a mail marks it read)
- SPEC.md §3 "HTML email is untrusted", §9.2 item 5
- docs/ARCHITECTURE.md, AGENTS.md project notes
- the previous review (`git show 0a392ff4:reviews/and-mail-list-read/review.md`) and reviews/and-mail-list-read/fixes.md

Git range:
- Branch: main
- HEAD: 71c09b02c5e1ed7334d90ff98bd7fa0fc855145e
- Base/start: 5943b18008986fe3fe6cbf86258a682c0fc0def2 (merge-base with main)
- Reviewed revision: 71c09b02c5e1ed7334d90ff98bd7fa0fc855145e
- Commits reviewed: 8

Summary: 0 P0, 0 P1, 0 P2, 0 P3

The task was rebased since the previous review (reviewed revision 7b14fdfd, re-reviewed as 0a392ff4). `git diff 0a392ff4 71c09b02 -- android` shows that, inside the task's mail packages, only the changes of 71c09b02 are new. The other files in that diff (launcher icons, `AndroidManifest.xml`, `LauncherIconTest`) come from main and are not part of this task.

Fixes from the previous review, checked against the code at 71c09b02:
- CR-1 (P2, failed page retried in a tight loop) is fixed. In `MailScreen.kt:204-205`, `canLoadMore` is now false while `state.message` is `OFFLINE` or `FAILED`. Both failure branches of `loadMore()` and `load()` set one of these values (`MailListViewModel.kt:93-94`, `:173-174`), so after a failure the snapshot stays false and `distinctUntilChanged` sends nothing more. Every successful load sets `message = null` (`:91`, `:170`), so paging resumes after a successful Retry (`refresh()`). `ARCHIVE_FAILED`/`DELETE_FAILED` do not block paging. A swipe failure that replaces an `OFFLINE` message can allow one more page request, but only once per user action, so this is not a loop. New tests in `MailListScreenStatesTest` cover each case: a failed page is not requested again (OFFLINE, then FAILED, then 1 s of frames), a failed refresh near the end does not request a page, a failed archive does not stop paging, and paging resumes after a successful retry. The earlier test that expected a retry after a failure was changed to model a page that loaded successfully. That change is correct, because the old expectation described the bug.
- CR-2 (P3, mail closed before it loaded still marked read) is fixed. In `MailReadViewModel.kt:137`, `markRead` now runs only when `opened == session`, the same check `updateFor` uses. The check runs in the same coroutine, right after the state update, so the server marks a mail read only when the view also sets `state.message`. That means the list's `onSeen` (`MailReadRoute`, `MailReadScreen.kt:72`) and the server state stay consistent. The new test "a mail closed or replaced before it loaded is not marked read" covers closing the mail and opening another mail before the load ends.

I also re-checked the rest of the task diff and found no new issues:
- WebView lock-down (`MailBodyView.kt`): no JavaScript, no file or content access, `blockNetworkLoads`/`blockNetworkImage`, no DOM storage, no multiple windows, CSP `default-src 'none'`. Links go to other apps only after a user gesture in the main frame, and nothing ever loads inside the view.
- DTOs and endpoints (`MailApi.kt`) are unchanged since the earlier reviews.
- Error mapping (`mailCall`) does not put response bodies into exception messages, and no mail content is logged.
- The list's swipe and refresh races (CR-1/CR-5 of the first review) and the read view's session handling are unchanged.

Validation: I was not allowed to run Gradle in this session (permission denied). The existing JUnit results in `android/app/build/test-results/testDebugUnitTest` were written at 2026-10-21T16:57Z, just before 71c09b02 was committed (16:58Z), and match the counts in fixes.md: 12 mail test classes, 116 tests, 0 failures, 0 skipped (MailListScreenStatesTest 15, MailListViewModelEdgeCasesTest 20, MailReadViewModelTest 17, MailWebViewTest 5, ...). The parent worker must re-run `cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.mail.*' lintDebug` on the final revision to record the evidence.

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
