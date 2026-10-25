# Code Review

Date: 2026-10-25

Task: web-new-view - Combined New view (GTD inbox + mail inbox)

Scope: `git diff 614a5151..HEAD -- web` (task commits 98d81e0d, 5784aa66), committed changes only

Requirements:
- tasks/web-new-view.md acceptance criteria 1-4 and recorded assumptions (nav placement, hold-back of older tasks, keys 1/2/3 + `?source=`, j/k/o/t)
- SPEC.md §5.3 "New view", §9.1
- docs/ARCHITECTURE.md, AGENTS.md "Project notes"
- Existing conventions: features/mail/list (MailListStore, mail-shortcuts, mail-list-page), features/gtd/data (ListStore, TaskStore), e2e/mail-list, e2e/capture, e2e/mail-read
- Server behaviour checked: MailGtdService.toTask (creates INBOX task + link, does not move or change the mail), MailReadService.list / MailMessageListQuery (sort by `coalesce(received_at, created_at)`)

Git range:
- Branch: main
- HEAD: 5784aa66e63e1d99422e5be40a508421b33fb3ec
- Base/start: 614a515158d13f77a258d0eb380156a4f2a2bc39
- Commits reviewed: 2

Summary: 0 P0, 0 P1, 1 P2, 2 P3

Verified without findings: route `/new` (lazy, app.routes.ts) and nav entry directly after Dashboard, before Inbox (nav-sections.ts); merge order by task `createdAt` / mail `receivedAt` with the hold-back rule keeping paging append-only (new-feed.ts, unit-tested); source icon + label per row; infinite scroll via IntersectionObserver plus "Load older messages" fallback; filter via buttons, keys 1/2/3 and `?source=` (replaceUrl, survives reload); open row -> `/tasks/:id` or `/mail/messages/:id`; "→ Inbox task" uses the existing `mailToTask` endpoint through MailReadService, guarded against double submit, error reported and button re-enabled; live updates come from the existing stores (TASK events / resync refetch or drop; MAIL_FOLDER / MAIL_ACCOUNT / resync refetch); only existing APIs are used, no server or `api/openapi.yaml` changes; the search-palette spec adjustment is needed because "new" now also matches the "New" nav section. The e2e spec follows the patterns of e2e/mail-read and e2e/capture (GreenMail via `Imap`, `/api/test/mail/sync`, `q` capture dialog, `list=INBOX&limit=200`) and runs in the `chromium` project like capture.spec. It was only reviewed statically, not run (no Docker).

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | A load failure of one source keeps "Loading…" on screen and hides the other, successfully loaded source under "All"**
    - File: `web/src/app/features/new/new-page.ts:72`
    - Category: Correctness
    - Problem: `loading` is `(filter !== 'mail' && !tasks.loaded()) || (filter !== 'inbox' && mail.items() === null)`. When the mail list request fails, `MailListStore.load` leaves `items` at `null` (only `loadError` is set). When the task request fails, `ListStore.loaded` stays `false`. Under the default "All" filter, `loading()` therefore stays `true` permanently after either failure. The template (`new-page.html:32`) then shows "Loading…" (role=status) next to the error alert, and it never renders the rows of the source that did load. For example, a failing mail backend hides the whole GTD Inbox in the New view until the user switches to "GTD Inbox". No spec covers the failure or retry paths (`new-page.spec.ts` has `taskFailure` / `mailFailure` variables but never sets them).
    - Why it matters: Under "All", one source failing hides the other source that loaded fine, and the screen says it is still loading at the same moment it shows the error. Review checklist: every path, including failure, must end loading.
    - Suggested fix: Count a source as loading only while it has neither data nor an error (for example, mail: `items() === null && !loadError()`, tasks: `!loaded() && !error()`). Render the rows of whichever source is available. Add component tests for a task load failure and a mail load failure under "All" (error shown, other source's rows shown, no "Loading…"), and for "Try again" recovering.

## P3 Low

- [ ] **CR-2 | Keyboard handling differs from the mail list it claims to reuse: `o` ignored on any focused button, `j`/`k` do not auto-repeat, no roving focus**
    - File: `web/src/app/features/new/new-page.ts:166`, `web/src/app/features/new/new-page.ts:184`
    - Category: Quality
    - Problem: `onKeydown` returns early on `event.repeat` for every key (line 166), so holding `j`/`k` does not move the cursor repeatedly. `mailShortcutFor` explicitly allows repeat for next/previous. Line 184 drops `o` (not just `Enter`) whenever the target is any `HTMLButtonElement`. So after clicking a filter button or "→ Inbox task", focus stays on that button and `o` does nothing, while `j`/`k` still move the cursor. Unlike `MailListPage.focusCursor`, the cursor never moves focus to the row's `.open` button, so assistive technology does not announce the cursor row. The recorded assumption says the row keys "reuse the mail list's".
    - Why it matters: These are small inconsistencies in a keyboard-first view (SPEC.md §9.1), and opening with `o` after using a filter button silently fails.
    - Suggested fix: Allow `event.repeat` for `j`/`k` only. Restrict the button guard to `Enter`. Move focus to the cursor row's `.open` button after `j`/`k`, as the mail list does.

- [ ] **CR-3 | Mails without `receivedAt` sort to the bottom and turn off the hold-back, unlike the server's order**
    - File: `web/src/app/features/new/new-feed.ts:56`, `web/src/app/features/new/new-feed.ts:67`
    - Category: Correctness
    - Problem: The server marks `receivedAt` as nullable (`MailMessageController.kt:358`, `nullable = true`; IMAP sync sets it from an optional INTERNALDATE) and sorts the list by `coalesce(received_at, created_at)`. The generated client type declares it as `string`. `time()` maps an unparseable or null value to `0`. Such a mail goes to the very end of the merged list, and `oldest()` becomes `0`, so no task is held back while more mail pages are pending. Each following page is then inserted above existing rows, which breaks the AC1 "without breaking the order" guarantee for that mailbox.
    - Why it matters: This is a rare edge case (INTERNALDATE is normally present), but in that case the merge order and the append-only paging are wrong.
    - Suggested fix: Fall back to `sentAt` (or keep the mail's position from the server page) when `receivedAt` is missing, and leave such mails out of the `oldest()` threshold. Add a unit case to new-feed.spec.ts.

## Review conclusion

Material issues found; fix before completing the task.

CR-1 should be fixed (with tests) before `done`. CR-2 and CR-3 are minor. No acceptance-criterion evidence has been recorded yet (`tasks.py verify web-new-view` shows all four unticked). Criterion 4 (Playwright against the e2e stack) has not been executed on this machine. It must be recorded with `--skip` and a reason, not claimed from the static review or from the Vitest suite.
