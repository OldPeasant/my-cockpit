# Test Report

Date: 2026-11-09

Task: web-notes-delete-tags - Web notes: delete and edit tags

Scope: final revision 556c110c3 on top of base 87ce046e96575ce6f3f6cb21f3552ade4001b4ca (`git diff 87ce046e9..556c110c3`), task commits db103995a, bb9f3e16e, 2e6eabf51, 058b62e69 (tests and reports), 556c110c3 (review fixes), on branch main. Second pass: re-checks the fixer's changes (`git diff 058b62e69..556c110c3 -- web`: note-editor-page.ts/.html/.spec.ts and locale files). No uncommitted source or test changes (only reviews/web-notes-delete-tags/review.md was modified in the worktree, a report).

Requirements:
- tasks/web-notes-delete-tags.md acceptance criteria and recorded assumptions (datalist suggestions; tags saved via autosave PATCH only when changed; editor delete saves pending edits first, goes to /notes, Undo restores and reopens; card delete drops the note, Undo reloads the list)
- reviews/web-notes-delete-tags/fixes.md (fixed behavior: delete refused when edits are unsaved, title disabled while deleting, known tags reloaded per opened note and extended after tag saves)
- docs/ARCHITECTURE.md "Coverage"; AGENTS.md project notes

## Test files changed

First pass (committed in 058b62e69):

- `web/src/app/features/notes/editor/note-tags-input.spec.ts` (new)
- `web/src/app/features/notes/list/note-trash.spec.ts` (new)
- `web/src/app/features/notes/editor/note-editor-page.spec.ts`
- `web/src/app/features/notes/list/notes-page.spec.ts`
- `web/src/app/features/notes/list/note-card.spec.ts`
- `web/src/app/features/notes/list/notes.service.spec.ts`

Fixer (556c110c3): `web/src/app/features/notes/editor/note-editor-page.spec.ts` (3 tests added).

Second pass: no test files changed; the fixer's tests cover the new behavior and no gap remained.

## Tests added or updated

Count: 50 in total for the task (47 by the first test-writer pass; 3 by the fixer: "suggests a tag added here on the next note opened", "does not delete when the edits could not be saved", "disables the title while the delete runs"). 0 added in this second pass. No existing test changed or weakened.

## Requirement coverage

Command F = `cd web && npx ng test --watch=false --include='src/app/features/notes/**/*.spec.ts'`; command C = `cd web && npm run coverage` (full web suite). Both run on 556c110c3.

| Requirement | Tests | Command | Result |
|---|---|---|---|
| Delete on the note card (to Trash) | NoteCard: "labels the delete button with the note title", "emits remove, and only remove, when the delete button is pressed", "disables the delete button and emits nothing while busy"; NotesPage delete: "moves a note to the Trash with If-Match, drops it, reloads the tags and offers undo", "keeps the note and shows the error when the delete fails, without offering undo", "reloads the notes when the note to delete changed elsewhere (412)", "drops a note already gone (404) without an error and without undo", "sends a delete only once when pressed twice and disables the card meanwhile" | F, C | Pass |
| Card delete with undo | NotesPage delete: "reloads the notes and tags when the delete is undone"; NoteTrash: all 7 tests (snackbar text/action/duration, restore on Undo, no restore on dismiss or late action, toast with problem detail or generic message on restore failure) | F, C | Pass |
| DELETE / restore API calls | NotesService: "moves a note to the Trash with the version as If-Match, without toasting errors", "rejects a delete of a note changed elsewhere with a 412 ApiError", "restores a note from the Trash as type NOTE, without toasting errors", "rejects a failed restore with its ApiError" | F, C | Pass |
| Delete in the editor (to Trash, with undo) | NoteEditorPage delete: "moves the note to the Trash with If-Match, goes to the notes and offers undo", "reopens the note when the delete is undone", "saves pending edits first and deletes with the new version", "saves nothing more after the delete, also when the debounce would fire", "shows a failed delete in the banner, stays on the note and can delete again", "shows a generic message when the delete fails unexpectedly", "disables Delete while it runs and sends it only once" | F, C | Pass |
| Fix: delete refused when the pending edits could not be saved | NoteEditorPage delete: "does not delete when the edits could not be saved" (PATCH 503 -> no DELETE, banner "Save or discard your edits before deleting the note.", no navigation, no undo offer, Delete enabled again, edit kept) | F, C | Pass |
| Fix: title disabled while deleting | NoteEditorPage delete: "disables the title while the delete runs" (disabled during the DELETE, enabled again after a 500) | F, C | Pass |
| Tags editable in the editor (chips) | NoteTagsInput: chips + labels, remove button, Enter (prevents default), comma split, blur, leading # stripped, empty/whitespace/#-only ignored, case-insensitive dedupe, dedupe within one input, Backspace removes last only when empty, disabled ignores everything; NoteEditorPage tags: "saves an added tag right away with If-Match, sending the tags", "saves a removed tag right away", "sends the tags with a body edit only when they changed", "shows the tags as the server kept them and does not save them again", "disables the tags while the note is in conflict" | F, C | Pass |
| Tag suggestions | NoteTagsInput: "suggests the known tags the note does not have yet, regardless of case", "gives each instance its own suggestion list id"; NoteEditorPage tags: "shows the tags as chips and suggests the tags in use that the note lacks", "still edits tags without suggestions when the tags cannot be loaded" | F, C | Pass |
| Fix: known tags extended after a tag save and reloaded per opened note | NoteEditorPage tags: "suggests a tag added here on the next note opened" (saved tag suggested on the next note before the reload answers; GET /tags issued again on opening the other note and its answer replaces the list) | F, C | Pass |
| New UI strings in web/src/locale (English + German) | None (not unit-testable here; tests assert the English source strings only) | - | Not testable by unit tests: parent verifies with the i18n extract / German build in `npm run check` |
| `npm run check` and `sh scripts/check.sh` pass | - | - | Not run by test-writer (parent runs check/.taskcheck) |

## Focused test execution

Command:

`cd web && npx ng test --watch=false --include='src/app/features/notes/**/*.spec.ts'`

Result:

Pass: 11 files, 258 tests (2026-11-09T00:56Z, revision 556c110c3).

## Full test execution

Command:

`cd web && npm run coverage` (whole web Vitest suite with coverage)

Result:

Pass: 196 files, 4731 tests, exit 0 (2026-11-09T00:56:44Z, 62 s). Server and Android suites not run (task is web-only; Gradle not run as instructed).

## .taskcheck

Result:

Not run (left to the parent worker).

## Bugs found

None. The first-pass observation (delete proceeding after a failed pre-delete save) was fixed in 556c110c3 and is now covered by "does not delete when the edits could not be saved"; the orphaned doc comment above `onTags` was replaced.

## Coverage

Status: verified

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: web-notes-delete-tags; base 87ce046e96575ce6f3f6cb21f3552ade4001b4ca; HEAD 556c110c3 (main); task commits as listed in Scope, given by the parent and checked with `git diff --stat 058b62e69..556c110c3`.
- Source/test snapshot identifier (including uncommitted inputs): committed revision 556c110c3; no uncommitted source or test changes (`git status --porcelain` shows only the review report).
- Exact included files: `web/src/app/features/notes/editor/note-editor-page.ts`, `.../editor/note-editor.service.ts`, `.../editor/note-tags-input.ts`, `.../list/note-card.ts`, `.../list/note-trash.ts`, `.../list/notes-page.ts`, `.../list/notes.service.ts`. Excluded: `.html`/`.scss` templates and styles (compiled into the components, not separately instrumented), `web/src/locale/*.xlf` (translations, no executable code), spec files. No renamed or deleted files.
- Provider/version, command, working directory, and test selection: Vitest v5.0.5 with @vitest/coverage-v8; `npm run coverage` in `web/`; whole web suite.
- Run start/completion time and exit status: 2026-11-09T00:56:44Z, 62 s, exit 0.
- Generated report paths and report hashes/run identifier: `web/coverage/web/coverage-final.json` (sha256 994aa9adcb9fe660c2c342a217a6346e46edf02eff6805e642d06e5d4c502d8a), `web/coverage/web/coverage-summary.json`.
- Evidence outputs are fresh and inputs stayed unchanged: report rewritten by this run (hash differs from the first pass, counts grew with the fixer's code); no source or test file changed during or after the run.
- Final run after latest test additions and production fixes: yes (on 556c110c3, after the fixer's changes).

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `web/src/app/features/notes/editor/note-editor-page.ts` | 288/293 (98.29%) | 147/161 (91.30%) | 60/61 (98.36%) | 242/243 (99.59%) |
| `web/src/app/features/notes/editor/note-editor.service.ts` | 13/13 (100%) | 5/5 (100%) | 8/8 (100%) | 10/10 (100%) |
| `web/src/app/features/notes/editor/note-tags-input.ts` | 84/84 (100%) | 25/27 (92.59%) | 17/17 (100%) | 59/59 (100%) |
| `web/src/app/features/notes/list/note-card.ts` | 73/73 (100%) | 20/20 (100%) | 8/8 (100%) | 50/50 (100%) |
| `web/src/app/features/notes/list/note-trash.ts` | 25/25 (100%) | 9/9 (100%) | 5/5 (100%) | 20/20 (100%) |
| `web/src/app/features/notes/list/notes-page.ts` | 144/145 (99.31%) | 70/78 (89.74%) | 44/44 (100%) | 116/116 (100%) |
| `web/src/app/features/notes/list/notes.service.ts` | 20/20 (100%) | 9/9 (100%) | 9/9 (100%) | 17/17 (100%) |
| Aggregate (summed counters, 7 files, none missing) | 647/653 (99.08%) | 285/309 (92.23%) | 151/152 (99.34%) | 514/515 (99.81%) |

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `note-editor-page.ts:262` (guard `deleting or deleted` in remove) | Double delete | Button is disabled while deleting (tested "disables Delete while it runs and sends it only once"); guard is defensive, unreachable through the UI |
| `note-editor-page.ts:274` (`!note or deleted` after the pre-delete save) | Delete racing another delete / note gone | Defensive; not reachable via UI once the button is disabled |
| `note-editor-page.ts:267` (new unsaved-edits guard) | Delete refused when edits are unsaved | Fully covered (all branches hit) by "does not delete when the edits could not be saved" and the successful delete tests |
| `note-editor-page.ts:77, 142, 158, 293, 314, 341, 444, 463, 478-481, 523, 541-543` | Pre-existing editor paths (onToggle no-op, missing route id fallback, stale load error, retry state, useTheirs failure, link info, stored mode) | Outside this task's changes |
| `note-tags-input.ts:120/148` | Compiler-generated branch / `parts.pop() ?? ''` fallback (split always yields an element) | Not meaningfully reachable |
| `notes-page.ts:238` and branches 67, 127, 163, 186, 202, 233, 245 | `run()` busy guard and null-list fallbacks of `notes.update` (pre-existing pattern) | Busy guard covered indirectly (disabled buttons); null-list fallbacks only hit while a reload races; not material |

## Unable to test

- German translations of the new strings: not checked by unit tests (only English source text is asserted); verify through the i18n build in `npm run check`.
- Real MatSnackBar rendering/timing of the Undo snackbar: NoteTrash is tested with a fake snackbar ref; pages stub `offerUndo`. A browser/e2e check was not in scope and not run.
- Navigation to `/notes` after editor delete: asserted through a `Router.navigate` spy, not a real route transition.

## Summary

Tests and task-scoped coverage are sufficient; no production defects found.
