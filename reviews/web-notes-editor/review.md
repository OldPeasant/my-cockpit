# Code Review

Date: 2026-10-21

Task: web-notes-editor - Notes: Markdown editor with images

Scope: `git diff 4f49a66e..HEAD` (task commits 20c33dcc, 32dcfdf4, e51eb73b, 5f140ee9, 20c64a4d, 0001772c), committed changes only, with a close look at fix commit 0001772c (CR-1/TW-1, CR-2, CR-3). Production code under `web/src/app/features/notes/` plus the shared-file edits (`notes.routes.ts`, `web/package.json`, `docs/ARCHITECTURE.md`, `note-card.spec.ts` formatting). The working tree was clean at review time.

Requirements:
- `tasks/web-notes-editor.md`: acceptance criteria 1-5 and the recorded assumptions (own escaped renderer trusted via `bypassSecurityTrustHtml`; 1 s debounce; 409/412 conflict banner; toggles save at once; pending edits flushed when leaving; leaving in conflict discards edits; switching notes during an upload waits for it; `/notes/:id` route; split layout as default, stored in localStorage)
- `SPEC.md` §8.1 (Notes editor), §4.3 (inline links, chips, Linked items), §4.2 (If-Match, 409/412)
- `docs/ARCHITECTURE.md`, `docs/QUALITY.md`, `AGENTS.md`
- Earlier `reviews/web-notes-editor/review.md` (CR-1..CR-3), `reviews/web-notes-editor/fixes.md`, `reviews/web-notes-editor/tests.md` (TW-1)

Git range:
- Branch: main
- HEAD: 0001772c47600a9989a5f4733737e888a0c4cd87
- Base/start: 4f49a66e73d13ed0f53676cab4e82a98a521ffd7
- Commits reviewed: 6

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Status of earlier findings

- **CR-1 / TW-1 (P1, upload placeholder saved when leaving during an upload): resolved.** `draft` now uses `withoutUploadPlaceholders(this.body())` (`note-editor-page.ts:87-90`), so neither an intermediate autosave nor a flush when leaving contains a `uploading:N` placeholder. The page tracks pending uploads by placeholder (`:76-83`, `:95`). `leave()` (`:137-142`) flushes, waits for the uploads in `finishUploads()` (`:145-157`, which loops to pick up uploads started while it waits), puts each result where its placeholder still stands (removes it when the upload failed) and saves again. `MarkdownEditor` clears `view` on destroy and drops late results and late failures (`markdown-editor.ts:148-152`, `:234`, `:244`), so it no longer writes into a destroyed `EditorView`. When the editor is still alive (switching notes), its marker replacement runs before the page's `done` promise settles, and the `body().includes(placeholder)` check prevents a second insert. In preview mode the editor is only `[hidden]`, not destroyed, so uploads still finish through it. `beforeunload` now also warns while an upload is pending. The TW-1 test is unchanged and passes; new tests cover the intermediate autosave, an upload that finishes before another note opens, and an upload that fails after leaving.
- **CR-2 (P2, conflict actions ignore a note switch): resolved.** `useTheirs()` and `keepMine()` capture `loadSeq` before the GET and drop the result and its error when a load happened meanwhile (`note-editor-page.ts:220-247`), following the pattern in `load()`. If the GET returns before the new note's `load()` starts, `id`/`body` still belong to the old note, so applying it is correct. Tests cover both actions and the error path.
- **CR-3 (P3, edits dropped when leaving after a failed save): resolved.** The destroy hook always calls `leave()`. `flush()` also saves in the `error` state, and the test `retries a failed save when leaving the page` covers this. Leaving while in conflict still discards the edits, which is now a recorded assumption (2026-10-21 07:01).

## Verification performed

- `cd web && npx ng test --watch=false --include='src/app/features/notes/editor/**/*.spec.ts'` -> 5 files, 88 tests passed (includes TW-1 and the new CR-1..CR-3 tests).
- I re-read the renderer (`note-markdown.ts`). All text is escaped before markup is added, including quotes. Link targets are limited to `http(s):`/`mailto:`, and images to `cockpit-file:{uuid}` (served through `fileUrl`) or `https:`. `data-line` attributes cannot be forged from note text, because quotes in text are escaped, and the private-use slot character is stripped from the input. That makes `bypassSecurityTrustHtml` in `note-markdown-view.ts` safe. Chip routes are escaped and come from server data only.
- Integration: `NoteEditorService` sends the version as a quoted `If-Match` on save and restore. Conflicts (409/412) stop autosave. Saves are serialized through `inFlight`. The route entry, the `@codemirror/autocomplete` dependency and the ARCHITECTURE.md stack-table line match the recorded assumptions.

## Notes for the parent worker (not findings)

- No acceptance criterion has been recorded with `tasks.py verify` yet (`python tasks.py verify web-notes-editor` shows #1-#5 unticked). Criterion 5 needs the Playwright spec run against the e2e stack (`npm run e2e -- e2e/notes/editor.spec.ts`), or a `--skip` with a reason. A unit-test run is not evidence for it.
- `reviews/web-notes-editor/tests.md` still has to be refreshed for the final revision (the test-writer is running in parallel).

## Review conclusion

No material issues found.
