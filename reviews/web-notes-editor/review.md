# Code Review

Date: 2026-10-21

Task: web-notes-editor - Notes: Markdown editor with images

Scope: `git diff 868c962c..HEAD` (task commits b6ed8b8e, a6f6b454, 9c2020bd, c9980b1b), committed changes only; no uncommitted changes at review time.

Requirements:
- `tasks/web-notes-editor.md` acceptance criteria 1-5 and recorded assumptions (own escaped renderer trusted via `bypassSecurityTrustHtml`; 1 s debounce; 409/412 conflict banner; toggles save at once; pending edits flushed when leaving; `/notes/:id` route; split default in localStorage)
- `SPEC.md` §8.1 (Notes editor), §4.3 (inline links, chips, Linked items), §4.2 (If-Match, 409/412)
- `docs/ARCHITECTURE.md`, `docs/QUALITY.md`, `AGENTS.md`
- `reviews/web-notes-editor/tests.md` (test-writer finding TW-1)

Git range:
- Branch: main
- HEAD: c9980b1b8a4e8f734b4abe14fcb0b85ebfe1134c
- Base/start: 868c962c9f0210a8a92ca00123d4b123afd0711e
- Commits reviewed: 4

Summary: 0 P0, 1 P1, 1 P2, 1 P3

## P0 Critical

None.

## P1 High

- [ ] **CR-1 | Leaving the editor during an image upload saves the "Uploading" placeholder permanently (TW-1 confirmed); `npm run check` fails**
    - File: `web/src/app/features/notes/editor/markdown-editor.ts:209-234`, `web/src/app/features/notes/editor/note-editor-page.ts:96-100` and `:117-124`
    - Category: Correctness
    - Problem: `insertImages` writes `![Uploading name…](uploading:N)` into the CodeMirror document, so it becomes part of `body` and is autosaved like any other text. When the page is destroyed (All notes, another route) or the note is switched through a chip (`open()` sets `note` to null, which destroys the `@if` block and the `MarkdownEditor`), the destroy/open flush sends the body with the placeholder. When the upload resolves later, `replaceMarker` still sees `this.view === view` (the field is not cleared on destroy) and dispatches to a destroyed `EditorView`. CodeMirror then only updates its internal state (`if (this.destroyed) { this.viewState.state = state; return; }` in `@codemirror/view`), the update listener does not fire, `valueChange` is never emitted, and no further save happens. The stored note keeps the placeholder (rendered as the text "Uploading tent.png…") and the uploaded file in `/Attachments/Notes/` is unreferenced. Reproduced: `npx ng test --watch=false --include='src/app/features/notes/editor/**/*.spec.ts'` -> 78 passed, 1 failed (`does not leave an upload placeholder in the saved note when leaving during an upload`, received `Trip\n![Uploading tent.png…](uploading:0)\n`). A milder form of the same issue: an upload that takes longer than the 1 s debounce also autosaves the placeholder as an intermediate version (one of the 20 kept revisions).
    - Why it matters: AC2 (paste/drop -> upload -> inserts `![](cockpit-file:{id})`) is not met in a realistic flow (paste a large image, then leave), the note content is corrupted on the server, and the committed failing test makes AC4 (`cd web && npm run check` passes) and `.taskcheck` fail, so the task cannot pass `done`.
    - Suggested fix: keep pending uploads out of the persisted body or finish them after the editor is gone. For example, have the page own the uploads: on completion substitute the placeholder in the page's `body` (or in the saved note for that note id when the page has moved on) and save; or strip `uploading:` placeholders from the draft in `changes()`/`flush()` and apply the upload result to the note through the service when the view no longer exists. Clear `this.view` in `onDestroy` so `replaceMarker` cannot silently write into a destroyed view. Keep the TW-1 test and make it pass.

## P2 Medium

- [ ] **CR-2 | Conflict actions ignore a note switch made while they run; "Save my version" can overwrite another note**
    - File: `web/src/app/features/notes/editor/note-editor-page.ts:187-207` (with `open()` at `:117-124` and `load()` at `:103-114`)
    - Category: Correctness
    - Problem: `load()` guards against stale results with `loadSeq`, but `useTheirs()` and `keepMine()` do not. Chips in the preview stay clickable while the conflict banner is shown. Sequence for `keepMine`: in conflict on note A, click "Save my version" (GET A starts), then click a chip to note B. `open(B)` returns from `flush()` immediately (conflict), sets `id` to B and loads B. If GET B resolves first, `title`/`body` hold B's content; then GET A resolves, `keepMine` sets `note` to A, state to pending and `flush()` saves B's title and body to note A (`service.save` uses `note.id`), with a fresh If-Match, so the server accepts it. `useTheirs()` in the same race calls `show(A)` while `id()` is B, so the page shows A's text under B's route, uploads go to B (`uploadImage` uses `this.id()`) and the Linked items panel shows B.
    - Why it matters: an older request overwrites the state of a newer one; in the `keepMine` case the content of one note silently replaces another note's content on the server (recoverable only through revisions).
    - Suggested fix: capture the note id (or `loadSeq`) before the GET in `useTheirs`/`keepMine` and drop the result when it changed meanwhile (the same pattern as `load()`), and add a test for each.

## P3 Low

- [ ] **CR-3 | Leaving the editor after a failed save or in conflict discards the unsaved edits without notice**
    - File: `web/src/app/features/notes/editor/note-editor-page.ts:96-100`, `:166`
    - Category: Correctness
    - Problem: the destroy hook only flushes when a debounce timer is pending, and `flush()` returns early in conflict. After a save error (timer cleared, state `error`) or in conflict, navigating within the app (All notes link, a chip, the nav bar) drops the edits silently; only closing the tab is guarded by `beforeunload`. The recorded assumption says pending edits are flushed when leaving the page; the test-writer noted the same gap as an observation.
    - Why it matters: user edits are lost without a warning in states where the page already knows they are not saved.
    - Suggested fix: on destroy also attempt `flush()` in the `error` state, or add a `canDeactivate` guard that asks when `dirty()` is true and the state is `error` or `conflict`; alternatively record the current behavior as an assumption.

## Review conclusion

Material issues found; fix before completing the task.

Notes on the rest of the scope: the Markdown renderer escapes all text before adding markup, restricts link targets to `http(s):`/`mailto:` and images to `cockpit-file:{uuid}`/`https:`, and `data-line` attributes cannot be forged from note text, so trusting its output with `bypassSecurityTrustHtml` is sound; chip `deepLink`s come from server-side resolvers only. Autosave sends the version as a quoted `If-Match`, serializes saves through `inFlight`, handles 409 and 412 as conflicts and keeps edits made during a save. The route addition, the `@codemirror/autocomplete` dependency and the ARCHITECTURE.md stack-table line match the recorded assumptions. Acceptance criteria are not yet recorded with `tasks.py verify`; the test report's scope line still names the pre-rebase base and commit hashes (`3f163da1`, `70b773ba`, `9619aa91`, `c64fdf99`), which the parent should take into account when recording evidence after the fixes.
