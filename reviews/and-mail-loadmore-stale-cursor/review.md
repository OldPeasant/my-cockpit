# Code Review

Date: 2026-11-14

Task: and-mail-loadmore-stale-cursor - Android: drop a stale load-more answer after a quiet first-page reload replaced the list (New, Mail list)

Scope: committed changes of the task, `git diff a6f7dfc35..0fa420fc0` (uncommitted test additions in the working tree by the parallel test-writer were not reviewed)

Requirements:
- tasks/and-mail-loadmore-stale-cursor.md (acceptance criteria, recorded assumption on silently dropped failed answers)
- reviews/and-mail-live-refresh/review.md CR-4 (origin of the task)
- SPEC.md §4.2 (opaque cursor pagination), server `core/web/CursorPage.kt` (cursor = base64url keyset of the last item of a page)
- AGENTS.md project notes and conventions

Git range:
- Branch: main
- HEAD: 0fa420fc0dd497c734546c42c95d8a07788dd6a3
- Base/start: a6f7dfc35a0e618353dbe4f2711bcc0ca9a6180a
- Commits reviewed: 1 (0fa420fc0 "drop a load-more answer after the list was replaced")

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

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

## Review conclusion

No material issues found.
