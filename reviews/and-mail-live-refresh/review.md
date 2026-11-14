# Code Review

Date: 2026-11-14

Task: and-mail-live-refresh - Android: mail views reload when new mail syncs (New, Mail list, Today), not only on resume

Scope: `git diff fef29f97665731ff3675310fe7d8338ca46b762f e3b261d19` (one implementation commit, Android only). The same code change was later rebased as 31835c3a8 on top of board-only commits (tasks/ changes); the code diff is identical.

Requirements:
- tasks/and-mail-live-refresh.md acceptance criteria
- AGENTS.md project notes and conventions
- Existing code conventions (GtdRefreshTriggers, GtdChangeStream, the view models touched)

Git range:
- Branch: main
- HEAD: e3b261d1920136448bb6d8b5621a72e10e9ffa5c (reviewed commit; rebased copy 31835c3a8f84c73ec9bd319366a337c7377c352b)
- Base/start: fef29f97665731ff3675310fe7d8338ca46b762f
- Commits reviewed: 1

Review method: code reading only (the parent asked not to run Gradle while another helper builds in this clone). No tests were executed by this review.

Summary: 0 P0, 1 P1, 1 P2, 1 P3

## P0 Critical

None.

## P1 High

- [ ] **CR-1 | Required unit tests for the live refresh are absent at the reviewed revision**
    - File: `android/app/src/test/java/ch/sonensei/cockpit/newitems`, `android/app/src/test/java/ch/sonensei/cockpit/mail/list` (no test references `followMailChanges` or `MailRefreshTriggers`; `grep -rln` over `android/app/src/test` returns nothing)
    - Category: Tests
    - Problem: Criterion 5 requires unit tests: an `EMAIL`/`MAIL_FOLDER` signal reloads the New view's mail part (new mail on top, filters All and Mail), a burst gives one reload, a `TASK` signal does not reload, and the same for the Mail list. The reviewed commit adds no tests; the debounce, the type filter, the quiet failure path (no banner, list kept) and the "not started twice" guard are all unverified.
    - Why it matters: An acceptance criterion is unmet, and the coalescing and quiet-failure behavior is exactly what regresses silently.
    - Suggested fix: Add the tests named in criterion 5 (the `internal` `MailRefreshTriggers(signals, quietMs)` constructor with a `MutableSharedFlow` and virtual time makes this straightforward), plus a quiet offline/failed reload keeping rows and flags, and the Dashboard Today reload. If the parallel test-writer run adds them, this finding is resolved by that work; the code then needs a fresh review per docs/QUALITY.md only if production code changes.

## P2 Medium

- [ ] **CR-2 | A quiet reload can be orphaned by loadMore/loadOlder and later overwrite a newer mailbox or refresh**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/mail/list/MailListViewModel.kt:223-226`, `:720-758` (also `android/app/src/main/java/ch/sonensei/cockpit/newitems/NewViewModel.kt:164-203`, `:212-217`)
    - Category: Correctness
    - Problem: A quiet load no longer sets `loading = true`. `loadMore()` (guarded only by `loading || loadingMore`) and `loadOlder()` (guarded by `canLoadOlder`, which checks `!loading`) can therefore start while the quiet load runs, and both overwrite the shared `loadJob` field. The quiet job is then no longer referenced; a following `select(otherMailbox)` or `refresh()` cancels only the paging job via `loadJob?.cancel()`, while the quiet job keeps running. Its `Ok` branch does not check that `current.mailbox` still equals the `mailbox` it loaded: `mergeFirstPage` returns `page.items to page.nextCursor` when the mailboxes differ, so the previous mailbox's mails, with its cursor, are written into the newly selected mailbox with `loading = false, loaded = true`. If it finishes after the new mailbox's load, the wrong mails stay shown, and the next `loadMore` uses the old mailbox's cursor for the new mailbox. Before this change it could not happen, because a running first-page load always set `loading = true` and so blocked loadMore/loadOlder from replacing `loadJob`. In NewViewModel (single mailbox) the same orphaning lets an older quiet answer land after a pull-to-refresh and end its spinner early (`loading = false` in the quiet `Ok` branch).
    - Why it matters: Wrong mailbox content after an ordinary sequence (new-mail event, scroll to the end, switch folder). This is the out-of-order async result case: an older request overwrites a newer one. The window is narrow (one network round trip) but real on slow connections.
    - Suggested fix: Keep the quiet reload in its own job field that `load()`/`select()`/`refresh()` cancel as well, or give each first-page load a generation number and drop an answer that is no longer the newest. In MailListViewModel, also discard the answer when `current.mailbox != mailbox`.

## P3 Low

- [ ] **CR-3 | A mail event that arrives while any load runs is dropped, not deferred**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/newitems/NewViewModel.kt:166`, `android/app/src/main/java/ch/sonensei/cockpit/mail/list/MailListViewModel.kt:225`, `android/app/src/main/java/ch/sonensei/cockpit/dashboard/DashboardViewModel.kt:135`
    - Category: Correctness
    - Problem: The guard `loadJob?.isActive != true` skips the debounced trigger when `loadJob` is a `loadMore`/`loadOlder` job, or a first-page load that may have read the server before the new mail was stored. The trigger is not remembered, so the new mail stays invisible until the next `EMAIL`/`MAIL_FOLDER` event or resume. That can take many minutes, because the server sends `MAIL_FOLDER` only when a sync changed something. The criterion says "a load in progress is not started twice", which concerns the first-page load, not paging.
    - Why it matters: In rare timings, the owner's reported symptom (new mail not shown while the view stays open) comes back.
    - Suggested fix: Skip only when a first-page load is running, or set a "reload pending" flag that the running load checks when it finishes (a conflate-style approach, which the criterion also allows).

## Review conclusion

Material issues found; fix before completing the task.

Notes on criteria checked without findings: a single shared SSE connection is reused (`MailRefreshTriggers` wraps `GtdChangeStream.signals`, no second connection, no sync scheduling). `Reconnected` and the `EMAIL`/`MAIL_FOLDER` types trigger reloads, and the first `Connected` does not. Bursts are debounced for 1 s. Collection runs only while the screen is STARTED (`repeatOnLifecycle` in `FollowMailChanges`), so nothing polls in the background. A quiet failure leaves rows, flags and messages unchanged in all three view models. A quiet success clears only the stale OFFLINE/FAILED message in the Mail list. Search results are not reloaded, and the pages loaded behind the first one are kept through the existing merge logic. The Dashboard reloads only the Today widget and skips this when the widget is hidden. Criterion 6 (manual device check) and criterion 7 (Gradle and check.sh) were not assessed by this code-reading review.
