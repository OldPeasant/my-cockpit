# Test Report

Date: 2026-11-09

Task: web-notes-delete-tags - Web notes: delete and edit tags

Scope: task commits db103995a, bb9f3e16e, 2e6eabf51 on top of base 87ce046e96575ce6f3f6cb21f3552ade4001b4ca (`git diff 87ce046e9..HEAD`), measured at HEAD 2e6eabf51efe99c295f6d03dffc69d4948a29174 on branch main, plus the uncommitted test files listed below.

Requirements:
- tasks/web-notes-delete-tags.md acceptance criteria and recorded assumptions (datalist suggestions; tags saved via autosave PATCH only when changed; editor delete saves pending edits first, goes to /notes, Undo restores and reopens; card delete drops the note, Undo reloads the list)
- docs/ARCHITECTURE.md "Coverage"; AGENTS.md project notes
- Parent worker's description of the changed behavior

## Test files changed

- `web/src/app/features/notes/editor/note-tags-input.spec.ts` (new)
- `web/src/app/features/notes/list/note-trash.spec.ts` (new)
- `web/src/app/features/notes/editor/note-editor-page.spec.ts`
- `web/src/app/features/notes/list/notes-page.spec.ts`
- `web/src/app/features/notes/list/note-card.spec.ts`
- `web/src/app/features/notes/list/notes.service.spec.ts`

## Tests added or updated

Count: 47 added (note-tags-input 13, note-trash 7, note-editor-page 14, notes-page 6, note-card 3, notes.service 4). No existing test changed or weakened.

## Requirement coverage

Command F = `cd web && npx ng test --watch=false --include='src/app/features/notes/**/*.spec.ts'`; command C = `cd web && npm run coverage` (full web suite).

| Requirement | Tests | Command | Result |
|---|---|---|---|
| Delete on the note card (to Trash) | NoteCard: "labels the delete button with the note title", "emits remove, and only remove, when the delete button is pressed", "disables the delete button and emits nothing while busy"; NotesPage delete: "moves a note to the Trash with If-Match, drops it, reloads the tags and offers undo", "keeps the note and shows the error when the delete fails, without offering undo", "reloads the notes when the note to delete changed elsewhere (412)", "drops a note already gone (404) without an error and without undo", "sends a delete only once when pressed twice and disables the card meanwhile" | F, C | Pass |
| Card delete with undo | NotesPage delete: "reloads the notes and tags when the delete is undone"; NoteTrash: all 7 tests (snackbar text/action/duration, restore on Undo, no restore on dismiss or late action, toast with problem detail or generic message on restore failure) | F, C | Pass |
| DELETE / restore API calls | NotesService: "moves a note to the Trash with the version as If-Match, without toasting errors", "rejects a delete of a note changed elsewhere with a 412 ApiError", "restores a note from the Trash as type NOTE, without toasting errors", "rejects a failed restore with its ApiError" | F, C | Pass |
| Delete in the editor (to Trash, with undo) | NoteEditorPage delete: "moves the note to the Trash with If-Match, goes to the notes and offers undo", "reopens the note when the delete is undone", "saves pending edits first and deletes with the new version", "saves nothing more after the delete, also when the debounce would fire", "shows a failed delete in the banner, stays on the note and can delete again", "shows a generic message when the delete fails unexpectedly", "disables Delete while it runs and sends it only once" | F, C | Pass |
| Tags editable in the editor (chips) | NoteTagsInput: chips + labels, remove button, Enter (prevents default), comma split, blur, leading # stripped, empty/whitespace/#-only ignored, case-insensitive dedupe, dedupe within one input, Backspace removes last only when empty, disabled ignores everything; NoteEditorPage tags: "saves an added tag right away with If-Match, sending the tags", "saves a removed tag right away", "sends the tags with a body edit only when they changed", "shows the tags as the server kept them and does not save them again", "disables the tags while the note is in conflict" | F, C | Pass |
| Tag suggestions | NoteTagsInput: "suggests the known tags the note does not have yet, regardless of case", "gives each instance its own suggestion list id"; NoteEditorPage tags: "shows the tags as chips and suggests the tags in use that the note lacks", "still edits tags without suggestions when the tags cannot be loaded" | F, C | Pass |
| New UI strings in web/src/locale (English + German) | None (not unit-testable here; tests assert the English source strings only) | - | Not testable by unit tests: parent should verify with the i18n extract / German build in `npm run check` |
| `npm run check` and `sh scripts/check.sh` pass | - | - | Not run by test-writer (parent runs check/.taskcheck); lint of the changed spec files: `npx eslint` and `npx prettier --check` pass |

## Focused test execution

Command:

`cd web && npx ng test --watch=false --include='src/app/features/notes/**/*.spec.ts'`

Result:

Pass: 11 files, 255 tests (rerun after the Prettier format of note-editor-page.spec.ts, 2026-11-09T00:52Z).

## Full test execution

Command:

`cd web && npm run coverage` (whole web Vitest suite with coverage)

Result:

Pass: 196 files, 4728 tests, exit 0. Server and Android suites not run (task is web-only; Gradle not run as instructed).

## .taskcheck

Result:

Not run (left to the parent worker).

## Bugs found

None that violate an acceptance criterion. Observations for the parent / code-reviewer (no failing test, no fix demanded):

- `note-editor-page.ts` `remove()`: when the pre-delete save fails (state "error") the delete still proceeds with the last saved version, so the unsaved edits are discarded and Undo restores the older text. This matches "delete" semantics but differs from the literal assumption "first saves pending edits"; the parent may want to record it or stop the delete on a failed save.
- `note-editor-page.ts` around line 249: an orphaned doc comment ("A checkbox in the preview: the change is saved right away.") now sits above `onTags` instead of `onToggle`.

## Coverage

Status: verified

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: web-notes-delete-tags; base 87ce046e96575ce6f3f6cb21f3552ade4001b4ca; HEAD 2e6eabf51efe99c295f6d03dffc69d4948a29174; task commits db103995a, bb9f3e16e, 2e6eabf51 as given by the parent and checked with `git diff --stat 87ce046e9..HEAD`.
- Source/test snapshot identifier (including uncommitted inputs): production = HEAD 2e6eabf51 (no uncommitted production changes). Test files sha256: note-editor-page.spec.ts 74c725d0 (after Prettier; coverage run used the pre-format content, formatting only), note-card.spec.ts bd9745b5, notes-page.spec.ts 529f760d, notes.service.spec.ts f081ee12, note-tags-input.spec.ts b3d0a583, note-trash.spec.ts fa25d99d.
- Exact included files: `web/src/app/features/notes/editor/note-editor-page.ts`, `.../editor/note-editor.service.ts`, `.../editor/note-tags-input.ts`, `.../list/note-card.ts`, `.../list/note-trash.ts`, `.../list/notes-page.ts`, `.../list/notes.service.ts`. Excluded: `.html`/`.scss` templates and styles (compiled into the components, not separately instrumented), `web/src/locale/*.xlf` (translations, no executable code), spec files. No renamed or deleted files.
- Provider/version, command, working directory, and test selection: Vitest v5.0.5 with @vitest/coverage-v8; `npm run coverage` in `web/`; whole web suite.
- Run start/completion time and exit status: 2026-11-09T00:50:53Z, about 46 s, exit 0.
- Generated report paths and report hashes/run identifier: `web/coverage/web/coverage-final.json` (sha256 a3e6d4bb7b67f74a4bfab18dd9cb7d9dbba49125d1381a5f2d36bb9abc4ef9c3), `web/coverage/web/coverage-summary.json`.
- Evidence outputs are fresh and inputs stayed unchanged: report written by this run (Vitest rewrites `coverage/`); production unchanged; only a formatting-only change to one spec file afterwards, focused suite re-passed.
- Final run after latest test additions and production fixes: yes (no production fixes made).

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `web/src/app/features/notes/editor/note-editor-page.ts` | 276/281 (98.22%) | 139/152 (91.44%) | 56/57 (98.25%) | 235/236 (99.58%) |
| `web/src/app/features/notes/editor/note-editor.service.ts` | 13/13 (100%) | 5/5 (100%) | 8/8 (100%) | 10/10 (100%) |
| `web/src/app/features/notes/editor/note-tags-input.ts` | 84/84 (100%) | 25/27 (92.59%) | 17/17 (100%) | 59/59 (100%) |
| `web/src/app/features/notes/list/note-card.ts` | 73/73 (100%) | 20/20 (100%) | 8/8 (100%) | 50/50 (100%) |
| `web/src/app/features/notes/list/note-trash.ts` | 25/25 (100%) | 9/9 (100%) | 5/5 (100%) | 20/20 (100%) |
| `web/src/app/features/notes/list/notes-page.ts` | 144/145 (99.31%) | 70/78 (89.74%) | 44/44 (100%) | 116/116 (100%) |
| `web/src/app/features/notes/list/notes.service.ts` | 20/20 (100%) | 9/9 (100%) | 9/9 (100%) | 17/17 (100%) |
| Aggregate (summed counters, 7 files, none missing) | 635/641 (99.06%) | 277/300 (92.33%) | 147/148 (99.32%) | 507/508 (99.80%) |

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `note-editor-page.ts:262` (guard `deleting or deleted` in remove) | Double delete | Button is disabled while deleting (tested "disables Delete while it runs and sends it only once"); guard is defensive, unreachable through the UI |
| `note-editor-page.ts:267` (`!note or deleted` after the pre-delete save) | Delete racing another delete / note gone | Defensive; not reachable via UI once the button is disabled |
| `note-editor-page.ts:77, 285, 306, 333, 434, 461-464, 506, 524` | Pre-existing editor paths (onToggle no-op, retry state, useTheirs failure, link info, stored mode) | Outside this task's changes |
| `note-tags-input.ts:120/148` | Compiler-generated branch / `parts.pop() ?? ''` fallback (split always yields an element) | Not meaningfully reachable |
| `notes-page.ts:238` and branches 67, 127, 163, 186, 202, 233, 245 | `run()` busy guard and null-list fallbacks of `notes.update` (pre-existing pattern) | Busy guard covered indirectly (disabled buttons); null-list fallbacks only hit while a reload races; not material |

## Unable to test

- German translations of the new strings: not checked by unit tests (only English source text is asserted); verify through the i18n build in `npm run check`.
- Real MatSnackBar rendering/timing of the Undo snackbar: NoteTrash is tested with a fake snackbar ref; pages stub `offerUndo`. A browser/e2e check was not in scope and not run.
- Navigation to `/notes` after editor delete: asserted through a `Router.navigate` spy, not a real route transition.

## Summary

Tests and task-scoped coverage are sufficient; no production defects found.
