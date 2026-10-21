# Code Review

Date: 2026-10-21

Task: web-notes-editor - Notes: Markdown editor with images

Scope: `git diff 993bc56e..966e827e` (10 task commits: 05c1d4e9, c60013f8, c32b8c7a, e6c00d41, 4ffce46f, 48e278b6, 4f8d270c, 161c5b5b, 9a3d8b0b, 966e827e; 4ffce46f, 4f8d270c and 9a3d8b0b only touch `reviews/`). Committed changes only. This review looks closely at 966e827e (`NoteEditorPage.open()`: the previous CR-1 fix, which stays on the note when the save on leaving fails, and the previous CR-2 fix, which adds a sequence counter and a destroyed check). Production code under `web/src/app/features/notes/editor/` was read in full. The shared-file edits were also checked: `notes.routes.ts`, the `web/package.json`/`package-lock.json` dependency line, the `docs/ARCHITECTURE.md` stack table and the formatting change in `note-card.spec.ts`. The uncommitted change to `note-editor-page.spec.ts` from the parallel test-writer is out of scope. `git diff 48747768 161c5b5b -- web` is empty, so the only production change since the previous review is 966e827e.

Requirements:
- `tasks/web-notes-editor.md`: acceptance criteria 1-5 and the recorded assumptions. These cover the own escaped renderer trusted via `bypassSecurityTrustHtml`, the 1 s debounce, the 409/412 banner, toggles saving at once, flush on leave, leaving in conflict discarding the edits, switching during an upload waiting for it, the `/notes/:id` route, the split default kept in localStorage, and (07:26) staying on the note with its draft and putting the address back (replaceUrl) when the save on leaving fails during an in-app switch.
- `SPEC.md` §8.1 (Notes editor), §4.2 (If-Match, 409/412), §4.3 (inline links, chips, Linked items)
- `docs/ARCHITECTURE.md`, `docs/QUALITY.md`, `AGENTS.md`
- `reviews/web-notes-editor/fixes.md` (the CR-1/CR-2 section of 966e827e)

Git range:
- Branch: main
- HEAD: 966e827ebe6104016e5858ca55656aa67079588c
- Base/start: 993bc56e05989364b984f3fc983c70db1594a500
- Commits reviewed: 10

Summary: 0 P0, 0 P1, 0 P2, 2 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | Text typed during the last save before an in-app note switch is dropped**
    - File: `web/src/app/features/notes/editor/note-editor-page.ts:134-145` (with `:311-315`)
    - Category: Correctness
    - Problem: `open()` treats leaving as finished after `await this.leave()`. If the user types while the final `flush()` of `leave()` waits for the server, `persist()` sees the new changes. The page is not destroyed and no timer is set, so it calls `schedule()` (`:314`), and the state becomes `pending`. The new check in `open()` handles only `saveState() === 'error' && dirty()`, so the code goes on with `id.set(id)`, `note.set(null)` and `load()`. `show()` then clears the timer and replaces `body`/`title`. The leftover edits are never sent. The comment at `:311` says such edits are "saved now when leaving", but that only happens on the `destroyed` path (`:313`), not on an in-app switch. This race existed before 966e827e. It matters now because the fix aims to never drop unsaved edits on a switch, and its check does not cover this state.
    - Why it matters: Typed text is lost silently. The window is small (typing in the still-visible editor during the save round-trip after a chip or route change), but the outcome is the one the 07:26 assumption is meant to prevent.
    - Suggested fix: In `open()`, after `leave()`, keep saving while unsaved changes remain and the state is not `conflict`. For example, loop `flush()` while `dirty()` and `saveState()` is neither `error` nor `conflict`. Or apply the "stay" branch whenever `dirty()` and the state is not `conflict`. Add a test that types while the leaving PATCH is pending and expects a second PATCH before the other note's GET.

- [ ] **CR-2 | Putting the address back after a failed leave leaves a duplicate history entry**
    - File: `web/src/app/features/notes/editor/note-editor-page.ts:138-141`
    - Category: Quality
    - Problem: A chip click (or `navigateByUrl`) pushes `/notes/B`. When the save on leaving fails, `router.navigate(['/notes', A], { replaceUrl: true })` replaces that entry with `/notes/A`, so history ends with `…, /notes/A, /notes/A`. The first browser Back then lands on the same note and seems to do nothing. When the switch came from the browser Back button, the replaced entry is the earlier note's, so that note drops out of the back history. There is no loop: the re-emitted `paramMap` for A runs `open(A)`, which returns at `id === this.id()` (`:133`) before any save or navigation. A newer `open()` that is still waiting has its own sequence number and returns at `:136` or reaches the same check.
    - Why it matters: Navigation is a little confusing after a failed save. The draft is safe, and this is a small cost of the chosen design.
    - Suggested fix: Accept it and add it to the 07:26 assumption, or switch to a `CanDeactivate` guard on the `:id` route. The guard would block the navigation before the URL changes, so no history entry needs repair. If you keep the current approach, add a short comment.

## Verification of 966e827e (previous CR-1 and CR-2)

- Previous CR-1 (P2) is resolved for the case the assumption names. With A dirty and in the `error` state, `open(B)` retries through `leave()`. If that also fails, the code skips `id.set`/`load`, keeps `body`, `title`, `saveError` and the retry button, and restores `/notes/A` with `replaceUrl`. The committed test `stays on the note with its edits when the retry before opening another note fails too` checks four things: no GET for B, the router URL is back to `/notes/A`, the editor value and banner are kept, and a later successful retry PATCHes A with If-Match `"1"` and does not reload. If the retry on leaving succeeds, the switch continues (`retries a failed save before opening another note`). The conflict state still discards the edits, as the 07:01 assumption accepts. A failed save when leaving to a non-note page still drops the draft after the retry. This is not covered by the 07:26 assumption, which names in-app note switches only. The 07:01 assumption states that there is no in-app navigation guard, so this is not a new finding. The parent may want to widen that assumption.
- Previous CR-2 (P3) is resolved. `openSeq` is increased on every route emission, including the early return for the same id. After `leave()`, the code returns when `this.destroyed || seq !== this.openSeq`, so only the latest `open()` continues, and nothing loads after the page is destroyed. The committed test `does not open the other note when the page is left while leaving the first one waits` checks that the image is still saved into A exactly once and that no GET or links request goes out for B.
- Router loop check: the back-navigation emits A. `open(A)` returns immediately because `id()` is still A, so it does not save or navigate again. A new failed switch happens only on a new user action. A navigation that is still running (for example a click on C) is cancelled by Angular's router in favor of the replaceUrl navigation, and the page stays on A with its draft. This is consistent with the assumption.
- The TW-2 behavior (A→B→A, A→B→C, A→B→A→B while leaving waits) is unchanged by the switch from id to counter. Every case still ends on the last requested note, because a same-id emission also increments `openSeq`.

## Other checks performed

- Renderer (`note-markdown.ts`): all text is escaped, both quote types included, before markup is added. Generated tags go through private-use slots, and the slot character is removed from the input. Links are limited to `http(s):`/`mailto:`. Images are limited to `cockpit-file:{uuid}` (through `fileUrl`) or `https:`. Chip titles and routes are escaped. That makes `bypassSecurityTrustHtml` in `note-markdown-view.ts` safe. Malformed nesting from emphasis around a link only gives unbalanced tags, which the browser repairs; this is not an injection.
- Integration: `NoteEditorService` sends the version as a quoted `If-Match` on save and restore and uses `SILENT_ERRORS`. A 409 or 412 stops autosave. `fileUrl` uses the generated `previewFile.PATH`. The route entry, the `@codemirror/autocomplete` dependency and the ARCHITECTURE.md stack-table line match the recorded assumptions. Feature code stays in `features/notes/editor`, apart from the one route line recorded as an assumption.
- localStorage: reading and writing the layout mode are both wrapped in `try`, and unknown values fall back to `split`.
- Earlier fixes (upload placeholders never saved, stale conflict actions guarded by `loadSeq`, retry on leave) are still present in the current code.
- I did not run tests or lint: running commands that execute the test suite was not permitted in this review session. The test evidence comes from the committed tests and the helper reports. The parent worker should confirm the focused suite (`cd web && npx ng test --watch=false --include='src/app/features/notes/editor/**/*.spec.ts'`) and `npm run check` on HEAD.

## Notes for the parent worker (not findings)

- `reviews/web-notes-editor/tests.md` still describes HEAD `48747768` (pre-rebase hashes). The test-writer running in parallel must refresh it for 966e827e.
- `python tasks.py verify web-notes-editor` still shows criteria #1-#5 unticked. Record them from the refreshed test evidence. For #5, use the Playwright run against the docker compose e2e stack.

## Review conclusion

No material issues found.
