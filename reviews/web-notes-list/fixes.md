# Fix Report

Date: 2026-10-21

Task: web-notes-list - Notes: list and organization

Scope: current task; findings CR-2, CR-3, CR-4 from `reviews/web-notes-list/review.md` (CR-2 also raised as an observation in `reviews/web-notes-list/tests.md`), approved by the parent worker. Files: `web/src/app/features/notes/list/`. Base reviewed: 07ddc501 plus test-writer commit fa1539c6; fixes are uncommitted for the parent to commit.

## Fixed findings

### CR-3 — Cancel/Escape during a running save loses the draft and leaves a stale error

Files changed:
- `web/src/app/features/notes/list/notes-page.ts`
- `web/src/app/features/notes/list/notes-page.html`
- `web/src/app/features/notes/list/notes-page.spec.ts`

Problem:
Cancel and Escape called `closeComposer()` while the POST was running; the draft was cleared, a later failure set `createError` on the closed composer and reappeared the next time it was opened.

Fix:
`closeComposer()` returns without doing anything while `saving()` (covers Escape and Cancel); the Cancel button has `[disabled]="saving()"` (and the class `cancel`). The reset of the draft moved to a private `resetComposer()`, which `create()` uses after a successful save. `openComposer()` now clears `createError`.

Validation:
- New test `cannot cancel or escape while a note is saved, so a failed save keeps the draft` (Cancel disabled, click + Escape keep the draft, a 500 then shows the error with the draft kept, Cancel enabled again) — Pass
- Existing `cancels the composer and clears its error`, `discards the draft with Escape and starts empty next time` — Pass

### CR-2 — A note created while the list is reloading does not appear

Files changed:
- `web/src/app/features/notes/list/notes-page.ts`
- `web/src/app/features/notes/list/notes-page.spec.ts`

Problem:
With `notes()` null (load running after a filter change or a 409/412 reload), `create()` could not insert the note and the running GET might have been answered before the POST committed, so the saved note was missing from the view.

Fix:
After a successful create, if `notes()` is null, `create()` starts a new `load()` (its sequence number supersedes the running load); otherwise it prepends the note when it matches the filter, as before.

Validation:
- New test `shows a note created while the list reloads by loading it again` (tag filter clicked, note saved before the list arrives, the older GET's result is ignored, the new GET with `tag=Holiday` shows the note) — Pass

### CR-4 — Excerpt can repeat the derived title when the body starts with a non-text line

Files changed:
- `web/src/app/features/notes/list/notes-filter.ts`
- `web/src/app/features/notes/list/notes-filter.spec.ts`

Problem:
For untitled notes the excerpt dropped the first non-blank raw line, but the server (`NoteMarkdown.titleOf`) derives the title from the first line that has text after Markdown/image/inline-link stripping; a whitespace-only title was treated as a title.

Fix:
The per-line cleanup moved into a private `plainLine()`; lines are cleaned first, then for a note whose `title?.trim()` is empty the first cleaned line containing something other than whitespace and the list/checkbox markers (`•`, `☐`, `☑`) is dropped. Output for titled notes is unchanged.

Validation:
- New tests `leaves out the line the title is derived from when the text starts with an image or link`, `treats a blank title as no title`, `keeps every line, an image-only first line included, when the note has a title` — Pass
- Existing excerpt tests unchanged — Pass

## Not fixed

### CR-1 — Stale-load protection and 404 handling are untested

Status:
Invalid (no longer valid)

Reason:
Already covered by the test-writer's tests committed in fa1539c6 (`shows the newest tag filter when an older response arrives last`, `ignores the failure of an outdated load`, `drops a note that was deleted elsewhere when pinning it (404)`); the parent confirmed no change is needed.

## Validation summary

Focused tests:
- `cd web && npx ng test --watch=false --include='src/app/features/notes/**/*.spec.ts'` — Pass, 3 files, 71 tests (66 before + 5 new)

Full test suite:
- `cd web && npm run check` — Pass (exit 0): lint "All files pass linting", 82 test files / 1505 tests passed, build OK (the existing bundle and `notes-page.scss` budget warnings are unchanged; the scss was not touched)

Lint/type-check/build:
- Included in `npm run check` (ng lint --max-warnings=0, e2e typecheck, ng build) — Pass

`.taskcheck`:
- `sh scripts/check.sh` — Pass ("all checks passed"; Android skipped by the machine's `COCKPIT_NO_ANDROID=1`)

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed.
