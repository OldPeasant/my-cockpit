# Code Review

Date: 2026-10-22

Task: web-mail-compose - Mail compose, reply, forward

Scope: second round. Final code of the task range `3f042d8b6159be030e20ee9566780e2affe3e108..dd03a60d7`, limited to `web/src` and `web/e2e/mail-compose/compose.spec.ts`. The focus is the fix commit dd03a60d7 (CR-1..CR-5): the queued `changeSender`/`followSender`, `draftAccountId`, replacing the URL through `router.navigate(..., replaceUrl)` (`showInUrl`), the Send button's `type`, and the Files picker note. Only committed code was reviewed. A test-writer's uncommitted edits to `mail-compose-page.spec.ts` were in the working tree and were ignored.

Reviewed revision: dd03a60d7

Requirements:
- tasks/web-mail-compose.md (acceptance criteria and the five recorded assumptions)
- SPEC.md §7.3/§7.4, §4.2 (ETag/If-Match)
- docs/ARCHITECTURE.md, AGENTS.md project notes
- First review round: reviews/web-mail-compose/review.md at 3be16cce4; fixer report reviews/web-mail-compose/fixes.md

Git range:
- Branch: main
- HEAD: dd03a60d7
- Base/start: 3f042d8b6159be030e20ee9566780e2affe3e108
- Commits reviewed: 5 (06b177538, 8df2c8a51, 3be16cce4, 27c4e17c6, dd03a60d7). 3be16cce4 holds only the review report. Code review in this round focused on dd03a60d7.

Summary: 0 P0, 0 P1, 0 P2, 1 P3

## Status of earlier findings

- **CR-1 (P1, sender change outside the write queue): Resolved.** `changeSender` (mail-compose-page.ts:188-195) now only sets the picked account. It marks an unstored draft as edited and queues `followSender()` (:388-410), which decides when it runs. I traced these cases:
  - **Change during an in-flight first create.** The create stores the draft in the old account, so `draftAccountId` is the old one. `followSender` then sees `target !== draftAccountId`, recreates the draft in the new account with the content and revision captured before the await, and discards the old copy.
  - **Change and change back before the move runs.** `target === draftAccountId`, so nothing moves.
  - **Several changes.** The first queued move uses the latest selection, and later moves find nothing to do.
  - **Failed create.** `back()` resets From to `draftAccountId` only if the user did not pick yet another account. The error goes to `notifications.error` through the `.catch` in `changeSender`, so the rejection is no longer unhandled. Later updates and Send use the old draft.
  - **Wrong account type for the Sent copy.** `ownSent()` now reads the right account: the select is disabled while busy (html:74), and `accountId` equals `draftAccountId` whenever a move is not pending.
  - **Identity.** The identity is cleared on a move (`identityId: undefined`), so the new account uses its default.
  - Tests cover the race, the failed move (the error is reported once, From shows the old account, update and Send go to the old draft), the change-back case and the identity.
- **CR-2 (P2, Enter in Subject sends): Resolved.**
  - Send is `type="button"` with `(click)="send()"` (html:163-171).
  - The form has `novalidate`, and its `(submit)` only calls `preventDefault()` (html:67).
  - Every other button in the form is `type="button"`: Cc/Bcc, Remove, Attach file, From Files and Discard, plus the rich-text toolbar buttons. Material's `matChipRemove` sets `type="button"` itself (`chips.mjs:57`). So no button can act as the implicit default button.
  - A test checks that the form has no submit button, that a submit event is prevented without sending, and that clicking Send sends.
  - The Playwright spec clicks Send by role and name (compose.spec.ts:84), so the change does not affect it.
- **CR-3 (P2, URL not updated for new or moved drafts): Resolved.** `apply()` calls `showInUrl()` (:337, :351-359). It replaces the query with `?draft=<id>` whenever the stored draft differs from `urlDraftId`: on the first create, after a move, and when a reply/forward starts. It does nothing after `finished`, or after `left` (set on destroy). The compose route has a single route config (mail.routes.ts:21-25), so the default reuse strategy keeps the component, and `open()` reads the query only once. Tests cover a new draft (navigates once), an opened draft (no navigation), a moved draft, and a save on leaving the page (no navigation). See CR-6 for a timing gap in the `left` guard.
- **CR-4 (P2, missing tests): Resolved.** The test-writer's commit 27c4e17c6 tests edits made during an in-flight save. The fix commit adds tests for the failed move, the change during the first save, and the change back. See the CR-1 to CR-3 notes above.
- **CR-5 (P3, Files picker hides entries beyond 200): Resolved (as a note).** `files()` returns `{ entries, more: !!nextCursor }` with `FILES_LIMIT = 200` (mail-compose.service.ts:126-133). The dialog shows "Only the first 200 entries of this folder are shown." (`@@mailCompose.pickMore`, file-picker-dialog.ts:50-54). The note is set only by the latest load (`seq` check). Service and dialog tests cover it. There is still no paging; the finding offered a note as the minimum fix, so this is acceptable.

Validation run in this review:
- `cd web && npx ng test --watch=false --include 'src/app/features/mail/**/*.spec.ts'`:
  - The first run reported 2 failed and 394 passed.
  - A run a few minutes later reported 396 passed.
  - Both runs included the test-writer's concurrent, uncommitted edits to `mail-compose-page.spec.ts`. The failing assertion was at line 1017-1019, beyond the committed spec, and it was gone in the second run.
  - For the committed spec at dd03a60d7, the fixer reported 18 files and 392 passed. I could not isolate the committed spec, because stashing is outside this review's permissions.
- `cd web && npx ng lint` -> all files pass linting.
- Playwright was not run, as instructed. Criterion #3 still needs that run as evidence before `done`.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-6 | A save that finishes while the user is leaving the page can cancel the user's navigation**
    - File: `web/src/app/features/mail/compose/mail-compose-page.ts:139`, `:351-359`
    - Category: Correctness
    - Problem: `left` is set only in the `DestroyRef` callback, which runs once the new route has been activated. Suppose the user starts a navigation away (the "Mail" back link, browser Back, the app menu) while a first create or a sender move is in flight. If that write finishes before the new route activates, `apply()` → `showInUrl()` calls `router.navigate([], { relativeTo, queryParams: { draft }, replaceUrl: true })`. The Angular router cancels the earlier navigation in favour of this newer one, so the user stays on the compose page. With browser Back, the replaced URL can also overwrite the history entry. The window includes loading the lazy `mail-list-page` chunk (`loadComponent`, mail.routes.ts:19), for example after the compose page was reloaded. Before the fix, this could only happen right after `respond()` on open. Now it can happen on any first autosave and on every move.
    - Why it matters: Once in a while a Back click does nothing, or browser Back ends up on the compose URL again. Nothing is lost, because the draft is saved, but the navigation looks broken.
    - Suggested fix: Skip `showInUrl` while another navigation is in progress (`router.currentNavigation()` / `getCurrentNavigation()` is non-null and is not one this page started), or set `left` on `NavigationStart` for a URL outside this route. Add a test in which a navigation is in progress when the create resolves.

## Review conclusion

No material issues found. All five earlier findings (CR-1 to CR-5) are resolved in dd03a60d7, and the only new finding is CR-6 (P3). The parent worker still has to record evidence for acceptance criteria #1 to #3, including the Playwright run against the e2e stack for #3.
