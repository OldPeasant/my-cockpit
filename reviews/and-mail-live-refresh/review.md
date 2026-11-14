# Code Review

Date: 2026-11-14

Task: and-mail-live-refresh - Android: mail views reload when new mail syncs (New, Mail list, Today), not only on resume

Scope: re-review of the final code, `git diff 2c363a763cb34a2fce40ad68afafa0778036c597 094d34bda -- android` (task commits ee50ac07f..094d34bda), plus the test-only delta in d8d028764 (see "Delta review" below). Committed changes only.

Requirements:
- tasks/and-mail-live-refresh.md (acceptance criteria and recorded assumptions, including the superseding one of 20:47)
- SPEC.md section 2 (SSE while the app is in the foreground)
- AGENTS.md project notes (conventions, lint, feature folders)
- Earlier review (CR-1, CR-2, CR-3), tests.md (TW-1), fixes.md

Git range:
- Branch: main
- HEAD: d8d0287643dfae377825ffd381d2387067200223
- Base/start: 2c363a763cb34a2fce40ad68afafa0778036c597
- Commits reviewed: 5 (ee50ac07f, c137dce61, 7fffc4fd0, 094d34bda, d8d028764; c137dce61 touches reviews only, d8d028764 touches tests and reviews only)

Summary: 0 P0, 0 P1, 0 P2, 1 P3

## Status of earlier findings

- CR-1 (missing tests): resolved. NewMailLiveRefreshTest (EMAIL with filter All, MAIL_FOLDER with filter Mail, burst, TASK ignored, reconnect, offline/failed quiet reload, no spinner, deferred event, paging races), MailListLiveRefreshTest (same set for the Mail list plus folder shown, search not reloaded, selection kept, message handling), DashboardMailRefreshTest, MailRefreshTriggersTest and FollowMailChangesTest cover the criteria.
- CR-2 (orphaned quiet reload): resolved. First-page loads now own `firstPageJob` (NewViewModel.kt:131, MailListViewModel.kt:200); every first-page load cancels it, manual loads also cancel paging. The Mail list additionally drops an answer for a mailbox no longer shown (MailListViewModel.kt:765). Regression tests `aRefreshAfterLoadMoreDropsTheAnswerOfAnEarlierEventReload` and "an event reload outrun by load more and a folder switch does not overwrite the new folder" assert this.
- CR-3 (events dropped during paging): resolved. Events during paging start a quiet first-page load at once; events during a first-page load set `reloadPending` and the running job loops once more (NewViewModel.kt:190-197, MailListViewModel.kt:743-753). Dashboard defers via `mailPending` (DashboardViewModel.kt:129-160). All state is touched only on the main dispatcher (viewModelScope and LaunchedEffect), so the flags need no synchronisation.
- TW-1 (dashboard reloaded TODAY): resolved. `reloadInbox()` reloads only `WidgetId.INBOX`, which holds `unreadMail` (DashboardViewModel.kt:150-160); a refresh cancels the event reload and clears the pending flag, so an older event answer cannot overwrite a newer refresh.

Other checks done on the final code: debounce of 1 s on EMAIL/MAIL_FOLDER/Reconnected over the shared `GtdChangeStream.signals` (no second SSE connection, no GTD sync scheduling, first connection does not emit); collection via `repeatOnLifecycle(STARTED)`; quiet reloads keep shown mails and existing flags/messages on failure and clear only the outdated OFFLINE/FAILED state on success; `folderGeneration` interplay with `reloadFolders()` still drops older account lists correctly; Hilt constructors (default `NONE` on Dashboard/MailList, required parameter on New) are consistent with the existing `projectRequests` pattern.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-4 | A quiet reload that replaces the whole list while "load more" runs lets the page answer append after a gap**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/newitems/NewViewModel.kt:237`, `android/app/src/main/java/ch/sonensei/cockpit/mail/list/MailListViewModel.kt:262`
    - Category: Correctness
    - Problem: Quiet first-page loads now intentionally leave a running `loadMore()` alive. When the new first page does not overlap the mails shown (at least a full page, 50, of new mail arrived), the first-page merge replaces the list with the new page and its own cursor (NewViewModel.kt:207-211, `mergeFirstPage` at MailListViewModel.kt:796-810). If the older `loadMore()` answer (requested with the previous cursor) arrives afterwards, it is appended unconditionally and sets `nextCursor` to its own next cursor, so the list shows the new first page followed directly by the old page two: the mails of the old first page are missing and further paging continues from there. Before this task this could not happen, because every first-page load cancelled paging.
    - Why it matters: Mails silently missing from the middle of the list until the next manual refresh. Needs a large sync burst during a pending page load, so it is rare; no data is lost on the server.
    - Suggested fix: In both `loadMore()` result handlers, apply the page only when the state's `nextCursor` still equals the cursor the request used (otherwise just clear `loadingMore`); add a regression test with a non-overlapping event reload finishing before the page answer.

## Delta review (d8d028764)

Reviewed revision is now d8d0287643dfae377825ffd381d2387067200223. The delta since 094d34bda changes no production code; under `android/` it only adds two tests (`git show d8d028764 -- android`). Gradle was not run by the reviewer (a check was running in the clone); the test-writer reported both tests green.

- `MailListLiveRefreshTest` "an event while a newly opened folder loads reloads that folder once it ends, not the unified inbox": holds the folder's first page open, emits MAIL_FOLDER, and asserts no extra load starts during the running first-page load, then exactly one follow-up load of the selected folder (not Unified), the follow-up answer shown, and `loading` cleared. This matches the `reloadPending` loop in `MailListViewModel.load` (lines 743-753), which reloads `state.value.mailbox`. Meaningful regression coverage for CR-3 on folder switch.
- `DashboardMailRefreshTest` "a refresh during an event reload drops the event reload's older answer": holds the event-triggered INBOX reload open, runs a manual refresh that answers first, then completes the stale answer and asserts the refresh result stays and only three loads happened. This asserts the TW-1 behavior (refresh cancels the event reload). Because the cancelled coroutine never resumes, completing the deferred afterwards is a no-op; the assertion would also catch a regression where the event reload is not cancelled.

Both tests assert observable state rather than internals, use the existing helpers (`shown`, `signal`, `quiet`, `firstPages`), and introduce no new findings. CR-4 remains open as before.

## Review conclusion

No material issues found. The earlier findings CR-1, CR-2, CR-3 and TW-1 are resolved; the test-only delta in d8d028764 adds valid regression tests and no new findings. CR-4 is a low-priority edge case the parent may fix or record.
