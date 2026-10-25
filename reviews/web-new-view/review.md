# Code Review

Date: 2026-10-25

Task: web-new-view - Combined New view (GTD inbox + mail inbox)

Scope: round 2, a re-review of the final code after the fixes. `git diff 614a5151..HEAD -- web` covers task commits d941e026, 634c1bb7 and c149a323; 2d30464f only adds review reports. Focus: web/src/app/features/new, app.routes.ts, shell/nav-sections.ts, app.spec.ts, search/search-palette.provider.spec.ts, e2e/new. Committed changes only.

Requirements:
- tasks/web-new-view.md acceptance criteria 1-4 and the recorded assumptions: nav placement, hold-back of older tasks, keys 1/2/3 with `?source=`, and j/k/o/t
- SPEC.md §5.3 "New view", §9.1
- docs/ARCHITECTURE.md, AGENTS.md "Project notes"
- Round-1 report (CR-1..CR-3) and reviews/web-new-view/fixes.md
- Existing conventions: MailListStore (load/reload/loadMore semantics), MailListPage.focusCursor, gtd ListStore (`loaded`/`error` semantics)

Git range:
- Branch: main
- HEAD: c149a32326d594e3fd5d924fe0c711a5906f5a9a
- Base/start: 614a515158d13f77a258d0eb380156a4f2a2bc39
- Commits reviewed: 4 (d941e026, 634c1bb7, 2d30464f, c149a323)

Summary: 0 P0, 0 P1, 0 P2, 0 P3

The round-1 code was rebased. Commits 98d81e0d/5784aa66 became d941e026/634c1bb7. `git diff 5784aa66 634c1bb7` over the files in scope is empty, so the round-1 review of those files still applies. In this range, only this task changes files under web/. Fix commit c149a323 changes only web/src/app/features/new (plus fixes.md).

## Status of round-1 findings

- **CR-1 (P2): Resolved.** At `new-page.ts:73-77`, a shown source counts as loading only while it has neither data nor an error. I checked this against the store semantics. `ListStore.refresh` sets `error` and leaves `loaded` false on failure. `MailListStore.load` sets `loadError` and leaves `items` null and `nextCursor` null. After a mail failure, `moreMail` is therefore false and no task is held back, so all tasks show under "All". After a task failure, the mails show. `new-page.html:98` now shows the empty state only when no load error is displayed. During "Try again" the outcome depends on the source. For mail, `reload()` clears `loadError` and shows "Loading…" again. For tasks, the error banner stays until the refresh succeeds. Both paths end in a recoverable state. The tests cover both failures under "All", including the absence of "Loading…" (`stops showing "Loading…" when the %s load failed`), the rows of the other source, no empty state next to the error, and retry recovery for both sources.
- **CR-2 (P3): Resolved.** At `new-page.ts:170`, only `j`/`k` repeat. The button guard at `new-page.ts:187` now applies only to `Enter`, so `o` opens the cursor row from a focused filter button. `focusCursor()` at `new-page.ts:217-223` moves focus to the cursor row's `.open` button with `preventScroll`, the same as `MailListPage.focusCursor`. When the focused `.open` button receives Enter, the native click calls `cursorKey.set(row.key); open(row)`, so the row is still opened only once. New tests cover repeat behaviour, focus following the cursor, and `o` on a focused filter button.
- **CR-3 (P3): Resolved.** At `new-feed.ts:67-69`, `mailAt()` uses `receivedAt`, else `sentAt`, else `''`. That value drives sorting, the row's `at` and the hold-back cutoff. Mails without any date are left out of `oldest()` (`new-feed.ts:75-78`), so they can no longer switch off the hold-back. A dateless `at` of `''` renders safely: DatePipe returns null for `''`. Three new unit cases are in new-feed.spec.ts. The server falls back to `created_at`, but the client falls back to `sentAt`. That is a reasonable approximation because `created_at` is not exposed in the summary DTO. The fix report documents this.

No regressions or new issues found in the final code. Acceptance criteria 1-3 are implemented as described in round 1. The fixes do not change the route, the nav entry, the filter/URL handling, the "→ Inbox task" flow or the SSE behaviour, which comes from the stores. No new server endpoint is used. I did not re-run the test suites. The fix report records `npm run check` passing (3524 tests). Criterion 4 (Playwright `e2e/new/new.spec.ts` against the e2e stack) could not run on this machine (no Docker). It must be recorded with `tasks.py verify --skip` and a reason, not claimed from this static review or the Vitest suite.

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
