# Code Review

Date: 2026-11-14

Task: and-mail-loadmore-stale-cursor - Android: drop a stale load-more answer after a quiet first-page reload replaced the list (New, Mail list)

Scope: committed changes of the task, `git diff a6f7dfc35..0fa420fc0` (first review), plus the delta `git diff 0fa420fc0..15acc52dc -- android` (test-writer tests, see "Delta review")

Requirements:
- tasks/and-mail-loadmore-stale-cursor.md (acceptance criteria, recorded assumption on silently dropped failed answers)
- reviews/and-mail-live-refresh/review.md CR-4 (origin of the task)
- SPEC.md §4.2 (opaque cursor pagination), server `core/web/CursorPage.kt` (cursor = base64url keyset of the last item of a page)
- AGENTS.md project notes and conventions

Git range:
- Branch: main
- HEAD: 15acc52dc078f1a40f22419ef518d32437831b9b (delta review); first review at 0fa420fc0dd497c734546c42c95d8a07788dd6a3
- Base/start: a6f7dfc35a0e618353dbe4f2711bcc0ca9a6180a
- Commits reviewed: 2 (0fa420fc0 "drop a load-more answer after the list was replaced"; 15acc52dc "edge-case tests, test and review reports", test files only on the code side)

Summary: 0 P0, 0 P1, 0 P2, 1 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | Folder-switch test passes through job cancellation, not through the mailbox guard its comment implies** (found in the delta review)
    - File: `android/app/src/test/java/ch/sonensei/cockpit/mail/list/MailListLiveRefreshTest.kt:369`
    - Category: Tests
    - Problem: `a load more of the unified inbox never lands in a folder opened meanwhile with an equal cursor` sets the folder's first page to the same cursor text "c1" ("The folder's first page happens to carry the same opaque cursor text"), which suggests it exercises the `it.mailbox != current.mailbox` check in `MailListViewModel.loadMore` (`MailListViewModel.kt:264`). But `select` runs a non-quiet `load`, which calls `loadJob?.cancel()` (`MailListViewModel.kt:744`), so the pending load-more coroutine is cancelled while suspended in `nextPage.await()` and never reaches the state update. The test would stay green with both the mailbox check and the cursor check removed.
    - Why it matters: the end-state assertions are correct and the test is a valid regression test for the observable behavior, but a reader (or a later fixer) may believe the mailbox guard is covered by a test when it is not; the guard is effectively defense in depth that no test reaches.
    - Suggested fix: adjust the test comment (or name) to say that switching the folder cancels the pending load more, so the equal cursor cannot matter; no production change needed. Optional, not required for the task.

## Review notes (no findings)

Checked and found correct:

- Staleness test by cursor equality. The server cursor is the keyset of the last item of a page (`CursorRequest.page`, `encodeCursor(positionOf(items.last()))`), and the Android repository passes it through unchanged. A load-more answer for cursor c is exactly "the mails after keyset c", so it follows the shown list whenever the list's current `nextCursor` is still c, regardless of what happened to the list above it. Hence:
  - a quiet reload whose new first page ends inside the loaded pages keeps the old cursor (`loadFirstPageOnce` / `mergeFirstPage`), and the pending answer is still appended, which is correct;
  - a quiet reload that replaces the list (a full page of new mail) gets a different keyset cursor, and the answer is dropped;
  - a replacement that happens to yield the same cursor string (for example the last item was removed locally but is still the last item of the new server page) means the new list ends at the same keyset, so appending the answer is still correct; an A-B-A change of the cursor during one pending request is benign for the same reason.
  - a reload that leaves no next page (`nextCursor` null) drops the answer, which is correct.
- Failed or offline stale answers only end `loadingMore` and show no banner or message, matching the recorded assumption; the replaced list's cursor stays for the next attempt, so paging remains recoverable.
- Mail list mailbox switch: `select` runs a non-quiet `load` that cancels `loadJob` and resets `loadingMore`; the added `it.mailbox != current.mailbox` check additionally guards a folder with an equal cursor string. Manual refresh (both view models) cancels `loadJob` as before.
- `loadOlder` interplay: `loadOlder` requires `nextCursor == null` and no `loadingMore`, and `loadMore` requires a non-null cursor, so they cannot start together from the same state; if a quiet reload sets a cursor during `loadOlder`, the new check only makes a later load-more answer safer.
- NewViewModel `remember()` after a dropped answer stores the unchanged current state; harmless. The cache-restored initial cursor works with the check.
- Loading state: every path (Ok, Offline, Failed, stale) ends `loadingMore`.
- Tests: for both view models, a race test with a full page of new mail during a pending load more (asserts list, cursor, loading state, and that paging continues from the new cursor), and a failed stale answer test (no message or banner, cursor kept). The pre-existing tests `an event during load more still reloads the first page` and `anEventDuringALoadMoreStillReloadsTheNewestMails` cover the kept-cursor path where the answer is still appended. The parent reports the new race tests fail without the fix; Gradle was not run by this review as instructed.
- Scope stays inside the task's `touches`; no API, schema or build changes; no logging of mail content.

## Delta review (reviewed revision 15acc52dc078f1a40f22419ef518d32437831b9b)

Scope: `git diff 0fa420fc0..15acc52dc -- android`; three tests added by the test-writer, no production code changed. Gradle was not run by this review (a full build runs in parallel; the test-writer reports them green). Each test was traced against the current production code.

- `MailListLiveRefreshTest` "a load more answering after an offline event reload kept the cursor is still appended": the quiet reload returns `Offline`, and `loadFirstPage` with `quiet = true` returns the state unchanged apart from accounts, so `nextCursor` stays "c1"; the pending answer then passes the cursor check and is appended (list 1, 2, 3, cursor c2, no message). `coVerify(exactly = 2)` confirms the reload really ran. Correct and meaningful: it guards against the fix over-dropping answers after a failed quiet reload, a path the first-round tests did not cover.
- `MailListLiveRefreshTest` "a load more of the unified inbox never lands in a folder opened meanwhile with an equal cursor": assertions (mailbox, list 5, 6, cursor c1, not loading more) match the behavior and are correct; see CR-1 for what the test actually exercises.
- `NewMailLiveRefreshTest.aLoadMoreAnsweringAfterAFailedEventReloadKeptTheCursorIsStillAppended`: the quiet `Failed(503)` reload keeps `mailPart` unchanged (`if (quiet) m`), so the answer is appended; the expected row order (party, invoice, plumber task, receipt) follows from the fixture timestamps (receipt 2026-10-19 is older than the plumber task 2026-10-20 08:00), and `mailFailed` stays false. Cache assertions confirm `remember()` stored the appended list and the new cursor. Correct and meaningful.
- Determinism: all three use `CompletableDeferred` to hold the load-more answer, the same dispatcher setup as the surrounding tests (`StandardTestDispatcher` with `advanceUntilIdle` in the Mail list, an unconfined Main sharing the `runTest` scheduler in New), and virtual time for the quiet period. No real time, threads or ordering assumptions beyond those already used by the existing tests.
- Fixture data is fictitious; no production files or build files changed in the delta.

## Review conclusion

No material issues found. (CR-1 is an optional P3 test-comment clarification.)
