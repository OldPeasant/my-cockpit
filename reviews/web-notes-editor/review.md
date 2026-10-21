# Code Review

Date: 2026-10-21

Task: web-notes-editor - Notes: Markdown editor with images

Scope: `git diff 7ce21cc0..HEAD` (8 task commits, e226f13b..48747768), committed changes only, with a close look at the TW-2 fix in 48747768 (`NoteEditorPage.open()`). Production code under `web/src/app/features/notes/editor/`, plus the shared-file edits (`notes.routes.ts`, `web/package.json`/`package-lock.json`, `docs/ARCHITECTURE.md`, `note-card.spec.ts` formatting) and the e2e spec. The working tree was clean at review time. Uncommitted files from the parallel test-writer are out of scope.

Requirements:
- `tasks/web-notes-editor.md`: acceptance criteria 1-5 and the recorded assumptions (own escaped renderer trusted via `bypassSecurityTrustHtml`; 1 s debounce; 409/412 conflict banner; toggles save at once; pending edits flushed when leaving; leaving in conflict discards edits; switching notes during an upload waits for it; `/notes/:id` route; split layout as default, stored in localStorage)
- `SPEC.md` §8.1 (Notes editor), §4.3 (inline links, chips, Linked items), §4.2 (If-Match, 409/412)
- `docs/ARCHITECTURE.md`, `docs/QUALITY.md`, `AGENTS.md`
- `reviews/web-notes-editor/tests.md` (TW-2) and `reviews/web-notes-editor/fixes.md` (TW-2 cycle)

Git range:
- Branch: main
- HEAD: 487477689fa70a68488a7794e53978e0ef946afe
- Base/start: 7ce21cc072525cedb46378ee80989f449382cd1a
- Commits reviewed: 8

Summary: 0 P0, 0 P1, 1 P2, 1 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | Switching to another note after a save keeps failing drops the edits without any notice**
    - File: `web/src/app/features/notes/editor/note-editor-page.ts:130-136`
    - Category: Correctness
    - Problem: `open()` awaits `leave()` and then always goes on to the new note: `id.set(id)`, `note.set(null)`, `load()`. `show()` (`:319-327`) then replaces `body`/`title` and clears `saveState`/`saveError`. If the save on leaving fails (for example a 503 after the autosave already failed, which is the case `retries a failed save before opening another note` covers only when the retry succeeds), the unsaved text of the first note is overwritten and the error banner disappears. Opening the first note again loads the server version. The recorded assumption (2026-10-21 07:01) accepts discarding edits only for the conflict state, not for a failed save.
    - Why it matters: The user loses typed text silently by clicking a chip while the server has problems. The error banner and retry they saw a moment before are gone, so nothing tells them the edits were not saved.
    - Suggested fix: When `leave()` ends with unsaved changes in the `error` state during an in-app note switch, keep the draft and the error visible instead of loading the next note (for example a `CanDeactivate` guard on `/notes/:id` that refuses while the draft cannot be saved; it would also cover the conflict case). If you keep the current behavior, record it as an assumption next to the conflict one and show a notice that the edits of the previous note were not saved. Add a test where the retry on leaving also fails.

## P3 Low

- [ ] **CR-2 | `open()` goes on loading the next note after the page was destroyed while leaving waited**
    - File: `web/src/app/features/notes/editor/note-editor-page.ts:130-136`
    - Category: Quality
    - Problem: The TW-2 check after `await this.leave()` only compares the id with `requested`. When the route goes from note A to note B (leaving A waits for an upload) and then to another page, the component is destroyed, but the pending `open(B)` still resumes after the upload: it sets the signals and sends `GET /api/v1/notes/B` and the links request for a page nobody sees. A related ordering detail: because `requested` holds an id and not a sequence number, A → B → A → B in quick succession lets both pending `open(B)` calls pass the check, and B is loaded twice (`loadSeq` drops the first answer, so the effect is only one extra GET).
    - Why it matters: Requests go out for a destroyed page. The code also suggests that the check covers every "the user went elsewhere" case, but it does not.
    - Suggested fix: Also return when `this.destroyed` is set (`if (this.destroyed || id !== this.requested) return;`). Optionally use a counter that `open()` increments, like `loadSeq`, instead of the id, so only the latest call continues.

## Verification of the TW-2 fix

- A → B (leaving A waits for an upload) → A: `open(A)` sets `requested = A` and returns because `id()` is still A. When `leave()` ends, `open(B)` sees `B !== requested` and stops. The page stays on A, which matches the address. The upload is put in place by the still-living `MarkdownEditor`, `finishUploads()` does not insert it twice because of the `body().includes(placeholder)` guard, and the final `flush()` of `leave()` saves it. The committed test `stays on the first note when the route returns to it while leaving waits for an upload` checks this and passes.
- A → B → C while leaving waits: both `open()` calls run `leave()` at the same time. The flushes run one after the other through `inFlight`, the shared `uploads` map is guarded against double inserts, and only `open(C)` continues. This is correct.
- First load (`note()` is null): the check passes and the note loads as before. A load that is still running is dropped by `loadSeq`.

## Other checks performed

- `cd web && npx ng test --watch=false --include='src/app/features/notes/editor/**/*.spec.ts'` -> 5 files, 93 tests passed (TW-2 included).
- `cd web && npx eslint src/app/features/notes/editor --max-warnings=0` -> no findings.
- Renderer (`note-markdown.ts`): all text is escaped, including both quote types, before markup is added. Generated tags go through private-use slots, and the slot character is removed from the input. Links are limited to `http(s):`/`mailto:`, and images to `cockpit-file:{uuid}` (through `fileUrl`) or `https:`. Chip attributes are escaped. That makes `bypassSecurityTrustHtml` in `note-markdown-view.ts` safe.
- Integration: `NoteEditorService` sends the version as a quoted `If-Match` on save and restore. 409/412 stops autosave. The route entry, the `@codemirror/autocomplete` dependency and the ARCHITECTURE.md stack-table line match the recorded assumptions.
- Earlier findings CR-1..CR-3 (placeholders never saved, stale conflict actions, retry on leave) are still resolved in the current code (`note-editor-page.ts:77-90`, `:143-163`, `:226-253`).

## Notes for the parent worker (not findings)

- `python tasks.py verify web-notes-editor` still shows criteria #1-#5 unticked. `tests.md` reports a Playwright run of `e2e/notes/editor.spec.ts` against the docker compose e2e stack (passed). Record it for criterion 5 once the test-writer has re-run on HEAD.
- `tests.md` still describes the TW-2 failure. It has to be refreshed for HEAD 48747768 (the test-writer is running in parallel).

## Review conclusion

Material issues found; fix before completing the task.
