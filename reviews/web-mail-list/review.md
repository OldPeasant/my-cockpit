# Code Review

Date: 2026-10-21

Task: web-mail-list - Mail list and triage

Scope: `git diff c87a2bd5..11d5d088`, excluding `tasks/` and `reviews/` (all `web-mail-list:` commits; final code revision 11d5d088). This is the third review. It checks the Round 2 fixes for CR-1..CR-3 of the second review (`reviews/web-mail-list/fixes.md`) and reviews the whole implementation again. Uncommitted test-writer changes in the working tree (`mail-list-page.spec.ts`, `mail-list-store.spec.ts`) were not reviewed.

Requirements:
- tasks/web-mail-list.md (acceptance criteria 1-4 and the recorded assumptions)
- SPEC.md §7.3, §7.4, §9.1
- docs/ARCHITECTURE.md (web conventions, test commands, e2e, environment variables)
- AGENTS.md project notes (i18n, lint, e2e-only test hook, security, shared files)
- Parent instructions: verify the Round 2 fixes, judge whether a dropped load-more page can leave the list stuck, review the whole implementation

Git range:
- Branch: main
- HEAD: 11d5d088c7272347913c54484643e49cece4a353
- Base/start: c87a2bd5ac5cc0bc93edc7055add70779c96f8c2 (merge-base with origin/main)
- Commits reviewed: 7 (917dd57a, 4a62e908, 12351f98, ada715b6, 8fe04749, 3e69239b, 11d5d088)

Summary: 0 P0, 0 P1, 0 P2, 1 P3

Earlier findings (second review), checked against 11d5d088:
- CR-1 (refetch before an overlapping load more leaves a gap): fixed. `listGeneration` is bumped by `load()` and by `refresh()` when it replaces the list. `loadMore()` drops its page when the generation changed, so the list keeps the refetch's `nextCursor` and no item is skipped. The opposite order is still handled by `mutationSeq` (a refetch overtaken by a load more is redone with the longer list). Only one of the two overlapping requests is ever dropped, so they cannot keep cancelling each other. The new store spec covers the refetch-first order.
- Can the list get stuck after a dropped page? No. `loadingMore` is reset in `finally`, `nextCursor` stays non-null (the refetch set it), so the `.more` block with the enabled "Load older messages" button stays rendered. `j` on the last item calls `loadMore()` again, and scrolling the sentinel out of and back into view fires the IntersectionObserver again. The only cost: if the sentinel stays in view, nothing loads until the user scrolls, presses `j` or clicks the button. That is acceptable for a rare race and is documented in fixes.md.
- CR-2 (unbounded thread lookups): fixed. `mapWithLimit` runs at most `THREAD_LOOKUP_CONCURRENCY` (6) lookups, keeps results in item order, and starts no new lookup after a failure. `Promise.all` handles late rejections of in-flight workers, so there is no unhandled rejection. On failure the action is not sent and every target is rolled back, as before. Specs cover the peak concurrency and the stop after a failure.
- CR-3 (AltGr/Option `#`): fixed. `#` and `!` are accepted with AltGraph or Alt (Windows Ctrl+Alt, Linux AltGraph, macOS Option), never with Meta. Letters with any of Ctrl/Alt/Meta/AltGraph are rejected, and a held symbol key does not repeat. Shift alone is not a modifier, so `Shift+I` and US-layout `#` still work. Specs cover each case. I ran `npx ng test --include=mail-shortcuts/mail-folders-store/mail-move-dialog specs --watch=false`: 3 files, 58 tests passed. These spec files are unchanged in the working tree.

Checked again with no findings:
- Criterion 1: the unified Inbox (`/mail`) and per-account folder tree (`/mail/folders/:folderId`, one route via `mailListMatcher`), the flat/threaded toggle (persisted in `localStorage` with try/catch), infinite scroll (IntersectionObserver plus button), multi-select with select-all/indeterminate, and toolbar actions for read/unread/star/move/archive/delete/spam match SPEC.md §7.3/§7.4. The keys j/k, e, #, u, v are present; r/a/f/c are assigned to later tasks by a recorded assumption. Move stays within one account (`moveAccountId`, `moveTargets`).
- Criterion 2: `MAIL_FOLDER` (shown folder, or any folder for the unified Inbox), `MAIL_ACCOUNT` and SSE resync trigger a debounced refetch. While an action runs, the refetch is deferred. The folder store reloads on unknown folders and account changes.
- Async ordering: `loadSeq` guards load, load more, refetch and action rollback against scope changes. `mutationSeq` and `listGeneration` guard the refetch/load-more overlaps. Every load, load-more and action failure ends in a visible state that can be retried.
- Server hook `POST /api/test/mail/sync`: `@Profile("e2e")` and `@Hidden`. Only the e2e permit-all chain of `core.testsupport` (also e2e-only) makes it public. A Spring test checks the bean is absent outside that profile, and it logs only counts. GreenMail is bound to 127.0.0.1 with fictitious credentials. `E2E_IMAP_PORT` is documented in ARCHITECTURE.md, web/e2e/README.md and run-e2e.mjs.
- Security/i18n: no `innerHTML`, and selectors use `CSS.escape`. Every user-facing string uses `$localize`/`i18n` with `mail.*` ids.
- Files outside `touches` (`mail.routes.ts`, `app.spec.ts`, server hook, compose file, docs) are needed to replace the placeholder route and to run criterion 4, and the task's assumptions cover them.
- Evidence: `tasks.py verify web-mail-list` still shows criteria 1-4 unverified. The parent worker must record them, including an actual `npm run e2e -- e2e/mail-list/list.spec.ts` run against the e2e stack for criterion 4 and a `npm run check` run for criterion 3. A unit-test run is not evidence for criterion 4.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | `MAIL_SHORTCUTS` doc comment claims it feeds a shortcut help that does not use it**
    - File: `web/src/app/features/mail/list/mail-shortcuts.ts:17`
    - Category: Quality
    - Problem: the comment says the table is "shown in the list's shortcut help". It is only used to build `BY_KEY`. The help line in `mail-list-page.html:233-236` and the toolbar `title` attributes repeat the keys as hard-coded text, and the shell's `?` overview (`shell/shortcuts-dialog.ts`) lists only `SHELL_SHORTCUTS`.
    - Why it matters: the comment is misleading. A later change to a key in `MAIL_SHORTCUTS` will look as if it updates the help, but the hard-coded hint and titles will silently drift.
    - Suggested fix: correct the comment (for example "Gmail-like keys (SPEC.md §7.3) of the mail list"), or render the hint from the table. Adding mail keys to the shell's `?` overview is outside this task's `touches`.

## Review conclusion

No material issues found. All three findings of the second review are fixed correctly and have tests. The dropped load-more page cannot leave the list stuck, because the "Load older messages" button, `j` on the last item and re-scrolling all load it again. The remaining P3 is optional cleanup. Criteria 1-4 still need recorded evidence (`tasks.py verify`), including an actual e2e run, before the task can be completed.
