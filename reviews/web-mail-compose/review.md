# Code Review

Date: 2026-10-22

Task: web-mail-compose - Mail compose, reply, forward

Scope: the task's commits `3f042d8b6..8df2c8a51` (06b177538 compose page, reply/forward entry points; 8df2c8a51 Vitest specs and the Playwright spec). Only committed code is reviewed; the test-writer's uncommitted spec changes are out of scope.

Requirements:
- tasks/web-mail-compose.md (acceptance criteria and the five recorded assumptions)
- SPEC.md §7.3/§7.4, §4.2 (ETag/If-Match)
- docs/ARCHITECTURE.md, AGENTS.md project notes
- Server contract: server/src/main/kotlin/ch/sonensei/cockpit/mail/compose/DraftController.kt

Git range:
- Branch: main
- HEAD: 8df2c8a51
- Base/start: 3f042d8b6159be030e20ee9566780e2affe3e108
- Commits reviewed: 2

Summary: 0 P0, 1 P1, 3 P2, 1 P3

What I checked and found correct:
- The serial write queue (`write()`) chains every write, and each write reads `draftId`/`etag` when it runs, so each one sends the ETag the previous one returned.
- The server's ETag is a quoted UID (`ETags.of`), and the client sends it back unchanged in `If-Match`.
- Dirty tracking captures `revision` and the content together, so edits made while a save is in flight stay dirty.
- A 412 sets `conflict` and stops the interval.
- Send saves pending changes first and then sends with the new ETag.
- Discard waits for an in-flight create.
- `keptAttachments()` is read inside the queued work and keeps inline parts by `partId`.
- `fileId` is used only for new files.
- Upload creates the draft first.
- TipTap parses HTML through its schema. Images render as `[alt]` placeholders (`QuietImage` node view), and `getHTML()` serializes into an inert document. Link validation (`isAllowedUri`) is active and `openOnClick` is false.
- Reading the editor HTML on destroy still works, because TipTap 3 keeps `editorState` after `destroy()`.
- `$localize`/`i18n` ids are present (`messages.xlf` is left to a dedicated extract, the convention since 80411290d).
- `npx ng test --watch=false --include 'src/app/features/mail/**/*.spec.ts'` -> 17 files, 328 tests passed. `npx ng lint` -> all files pass.
- No acceptance criterion has recorded evidence yet (`tasks.py verify web-mail-compose` shows #1-#3 open). Criterion #3 (Playwright against the e2e stack) was not run in this review.

## P0 Critical

None.

## P1 High

- [ ] **CR-1 | Changing the sender can leave the draft in the old account while From shows the new one; Send then sends from the old account**
    - File: `web/src/app/features/mail/compose/mail-compose-page.ts:178-193`
    - Category: Correctness
    - Problem: `changeSender` sets `accountId` and checks `stored()` synchronously, outside the write queue. This fails in two ways:
      (a) Race: the draft is not stored yet but its first create is in flight (an autosave tick). `stored()` is still false, so the method only calls `edited()`. The create then finishes in the old account (`storeContent` read `accountId()` before the change). Every later autosave and Send uses `update`/`send` on the draft in the old account, while the select shows the new account.
      (b) Failure: the draft is stored and `service.create` for the new account fails (mail server down, no Drafts folder, 409). `accountId` already shows the new account, but `draftId`/`etag` still point to the old draft. Autosave and Send keep using that old draft. The rejection from `void this.write(...)` is unhandled: no notification, no `saveState` change, and the select is not reverted.
      In both cases `ownSent()` also checks the wrong account type for the "no copy in Sent" message.
    - Why it matters: The message is sent from a different account than the one the From field shows. That sends the wrong identity to external recipients and can't be undone. The assumption for this task says that moving the draft recreates it in the chosen account.
    - Suggested fix: Make the decision inside the queued work. In the write, check whether the draft is stored and whether its `draft.accountId` differs from the selected account. On failure, set `accountId` back to the draft's account (or keep the selection and retry the move before the next save or send), and report the error with `notifications.error`/`saveState`. Add tests for both the in-flight-create race and the failed create.

## P2 Medium

- [ ] **CR-2 | Pressing Enter in the Subject field sends the message**
    - File: `web/src/app/features/mail/compose/mail-compose-page.html:66` (form `(submit)`), `:115` (Subject input)
    - Category: Correctness
    - Problem: The whole compose view is a `<form>` with a `type="submit"` Send button, and `(submit)` calls `send()`. Pressing Enter in the Subject `<input>` triggers the browser's implicit form submission, which calls `send()`. When To is valid, the mail goes out immediately. The chip inputs are not affected, because `MatChipInput._emitChipEnd` calls `preventDefault()` on Enter. The form also does not import `FormsModule`, so it has no `novalidate`, and native constraint validation of the `type="email"` chip inputs can block a Send click without explanation.
    - Why it matters: Users often press Enter after typing a subject to move to the body. Here that sends an unfinished mail, which can't be recalled.
    - Suggested fix: Remove the implicit submit. Either make Send a `type="button"` that calls `send()` (and drop `(submit)`, or add `novalidate`), or ignore Enter in the Subject field (or move focus to the editor). Add a test that Enter in Subject does not send.

- [ ] **CR-3 | The URL is not updated when a new draft is created or moved, so a reload loses or breaks the draft**
    - File: `web/src/app/features/mail/compose/mail-compose-page.ts:348-358` (create path in `storeContent`), `:186-192` (`changeSender`), `:295-299` (only reply/forward set `?draft=`)
    - Category: Correctness
    - Problem: `?draft=<id>` is set only after reply, reply all and forward. A new mail (`/mail/compose`) stays on that URL after its first save. Reloading the page opens an empty compose view and starts another draft, while the saved one stays in Drafts. A forward without attachments can be moved to another account. The move discards draft X and creates Y, but the URL keeps `?draft=X`, so a reload shows "The draft could not be opened" (404). The conflict banner tells the user to "Reload to see that version" (html:24), which does nothing for a new draft.
    - Why it matters: The recovery path that the 412 handling offers doesn't work for new drafts. Reloads leave orphan drafts behind, and after a sender move a reload fails.
    - Suggested fix: Whenever `apply()` takes a draft id that differs from the URL's (first create, sender move), call `router.navigate([], { queryParams: { draft: id }, replaceUrl: true })`, the same way the reply path does.

- [ ] **CR-4 | No tests for edits made during an in-flight save, or for the sender-move failure and race**
    - File: `web/src/app/features/mail/compose/mail-compose-page.spec.ts:184-272, 394-414` (as committed in 8df2c8a51)
    - Category: Tests
    - Problem: The autosave tests cover the timing, ETag chaining, conflicts and leaving the page. No committed test makes an edit while a save is pending and then checks that the state stays "unsaved" and that the next tick saves the edit. That revision logic is the core of the autosave. The sender tests cover only the success path (CR-1).
    - Why it matters: These are the paths most likely to regress silently (lost edits, wrong account), and the current suite would not catch them.
    - Suggested fix: Add a fake-timer test with a deferred `update` promise: make an edit while it is pending, resolve it, then assert `Unsaved changes` and a second `update` with the new content on the next tick. Add sender tests for the failed create and for the change during an in-flight create.

## P3 Low

- [ ] **CR-5 | The Files picker silently shows only the first 200 entries of a folder**
    - File: `web/src/app/features/mail/compose/mail-compose.service.ts:114-121`
    - Category: Quality
    - Problem: `files()` asks for `limit: 200` and returns `page.items` without following or showing the next page. A folder with more entries hides the rest, with no hint.
    - Why it matters: In a large folder, files can't be picked and the user gets no explanation.
    - Suggested fix: Follow the cursor (or add "Load more"), or at least show a note when more entries exist.

## Review conclusion

Material issues found; fix before completing the task.
