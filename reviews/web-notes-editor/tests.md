# Test Report

Date: 2026-10-21

Task: web-notes-editor - Notes: Markdown editor with images

Scope: task base `3f163da11c2057f12d788f154dbeba902e21d751` .. HEAD `c64fdf99c58ab8ece443e140bc882a129ef7e3ea` on `main`; task commits `70b773ba`, `9619aa91`, `c64fdf99` (from `git log 3f163da1..HEAD`, supplied by the parent worker and matching the task's `touches`). Production files changed: `web/src/app/features/notes/editor/{markdown-editor,note-editor-page,note-editor.service,note-markdown,note-markdown-view,note-revisions-dialog}.ts` (+ `.html`/`.scss`), `web/src/app/features/notes/notes.routes.ts`, `web/package.json` (dependency line only). Measured state: HEAD plus the uncommitted test files listed below (no uncommitted production changes).

Requirements:
- `tasks/web-notes-editor.md` acceptance criteria and recorded assumptions (1 s debounce, 409/412 conflict banner, toggles save immediately, flush on leaving, split default remembered in localStorage)
- `SPEC.md` §8.1 (editor, images, checklists, history) and §4.3 (inline links, chips, Linked items panel)
- `docs/ARCHITECTURE.md` "Coverage", `docs/QUALITY.md`, `AGENTS.md`

## Test files changed

- `web/src/app/features/notes/editor/note-editor.service.spec.ts` (new)
- `web/src/app/features/notes/editor/note-markdown-view.spec.ts` (new)
- `web/src/app/features/notes/editor/markdown-editor.spec.ts` (extended; the test host now also binds `searchLinks`, existing tests unchanged)
- `web/src/app/features/notes/editor/note-editor-page.spec.ts` (extended; the router setup adds a second route `/elsewhere` to leave the editor, existing tests unchanged)

## Tests added or updated

Count: 46 added (service 7, markdown view 10, markdown editor 9, editor page 20). The editor specs now hold 79 tests (33 before).

## Requirement coverage

Command F = `cd web && npx ng test --watch=false --include='src/app/features/notes/editor/**/*.spec.ts'` (78 passed, 1 failed: TW-1).

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 CodeMirror editor with preview toggle/split | page: `switches between write, side by side and preview, and remembers the choice`; editor: `shows the value, reports edits and applies values from outside without reporting them` | F | Pass |
| AC1 autosave debounced, If-Match | page: `autosaves once after the debounce, with the version as If-Match`, `saves edits made while a save runs after that save`, `removes the title with null when it is cleared`; service: `saves title and body with the version as a quoted If-Match` | F | Pass |
| AC1 conflict notice (412 and 409), keep mine / discard, failure while resolving | page: `on a conflict stops autosaving and keeps my version on request`, `on a conflict discards my edits on request`, `treats a 409 like a 412: conflict banner, no further autosave`, `keeps the conflict when reloading the newer version fails`; service: `rejects a save of a note changed elsewhere and does not toast it` | F | Pass |
| AC1 other save errors with retry | page: `shows other save errors with a retry` | F | Pass |
| Assumption: pending edits are flushed when leaving (other note, other page, during a save) | page: `saves pending edits before opening another note`, `saves pending edits when leaving the page`, `when leaving during a save, saves the edits made meanwhile after it`, `saves nothing when leaving without edits`, `asks before closing the browser tab only while edits are unsaved`, `ignores a note that arrives after another one was opened` | F | Pass |
| AC2 paste image -> upload -> inserts Markdown | editor: `uploads a pasted image and replaces its placeholder with the Markdown`, `uploads only the images of several pasted files, each into its own line`, `keeps the upload result in place when the text around the placeholder changes meanwhile`, `drops the upload result when its placeholder was deleted`, `leaves pastes without images to the editor`, `leaves image pastes to the editor without an upload function`; page: `uploads pasted images to the note`; service: `uploads an image as multipart form data to the note` | F | Pass |
| AC2 drop image -> upload | editor: `uploads a dropped image at the drop position`, `inserts a dropped image at the cursor when the drop point is outside the text`, `leaves drops without images to the editor` | F | Pass |
| AC2 upload failure shown | editor: `removes the placeholder and reports a failed upload`; page: `shows a failed image upload`, `shows a generic message for an unexpected upload failure` | F | Pass |
| AC2 leaving during an upload must not persist the placeholder | page: `does not leave an upload placeholder in the saved note when leaving during an upload` | F | Fail (TW-1) |
| AC2 rendered via the authenticated file URL | view: `shows cockpit-file images through the authenticated preview URL`; service: `builds the authenticated preview URL of a file`; existing `note-markdown.spec.ts` | F | Pass |
| AC2 preview checklists toggle and save | page: `toggles a checklist item in the preview and saves it right away`, `does not save a checklist toggle while in conflict`; view: `reports the source line of a clicked checklist item`, `does not report clicks on checkboxes inside a quote`; existing `note-markdown.spec.ts` toggle tests | F | Pass |
| AC3 `[[` autocomplete over all item types inserts `[[type:id]]` | editor: `offers link targets after [[ in the editor and inserts the chosen one` (live CodeMirror), `offers no link targets without a search function`, existing `linkCompletions` tests; page: `offers link targets of all types but the note itself, and remembers their titles` | F | Pass |
| AC3 chips | view: `opens a chip of a known item in the app`, `leaves modified and non-primary clicks on a chip to the browser`, `shows an unknown or unavailable item as a chip with its type only, not a link`, `re-renders when the source or the link info changes`; page: `renders inline links as chips with the titles of the linked items`, `shows chips with the type only when the links cannot be loaded`, `reloads the links when a save changes the inline links` | F | Pass |
| AC3 revisions dialog with restore | page: `restores a revision from the dialog`, `saves pending edits before restoring a revision, then restores on the saved version`, `does not restore while the note is in conflict`, `shows a failed restore and keeps the note`, `restores nothing when the dialog is closed without a choice`, `shows when the earlier versions cannot be loaded`, `lists revisions by title or first line and shows the chosen one read-only`; service: `lists the revisions of a note`, `restores a revision with the version as If-Match` | F | Pass |
| AC3 linked items panel | page: `shows the note's linked items in the Linked items panel` | F | Pass |
| Security: preview HTML is trusted, so source must not inject markup | view: `renders no script or event handler from the source`, `read-only: disables the checkboxes and reports no toggles`; existing `note-markdown.spec.ts` escaping tests | F | Pass |
| AC4 Vitest tests cover autosave, checklist toggle, link autocomplete; `npm run check` passes | all of the above | `cd web && npm run check` | Fail: only TW-1 fails (1591 passed, 1 failed); lint, api:check, e2e typecheck pass; `npx ng build` passes on its own |
| AC5 Playwright `e2e/notes/editor.spec.ts` against the e2e stack | `Notes editor > paste an image, toggle a checklist, reload: both persisted` | `cd web && npm run e2e -- --no-deps e2e/notes/editor.spec.ts` (docker compose e2e stack, chromium) | Pass (1 passed) |

## Focused test execution

Command:

`cd web && npx ng test --watch=false --include='src/app/features/notes/editor/**/*.spec.ts'`

Result:

Fail: 5 files, 79 tests, 78 passed, 1 failed (`does not leave an upload placeholder in the saved note when leaving during an upload`, TW-1, production defect).

## Full test execution

Command:

`cd web && npm run check` (api:check, lint, e2e:typecheck, test:ci, build); `cd web && npx ng build` separately; `cd web && npx prettier --check src/app/features/notes/editor/*.spec.ts`

Result:

Fail: test:ci 88 files, 1592 tests, 1591 passed, 1 failed (TW-1 only), so `ng build` was not reached inside `check`; run separately, `ng build` passes. Lint, api:check, e2e:typecheck and Prettier pass.

## .taskcheck

Result:

Fail: `sh scripts/check.sh` - TFP gate tests pass, server `./gradlew check` passes, web step fails on TW-1 only; the Android step was not reached because the script stops at the first failure.

## Bugs found

### Leaving the note while a pasted or dropped image uploads saves the "Uploading" placeholder permanently

Finding ID:

`TW-1`

Test:

`NoteEditorPage > does not leave an upload placeholder in the saved note when leaving during an upload` (`web/src/app/features/notes/editor/note-editor-page.spec.ts`)

Production source:

`web/src/app/features/notes/editor/markdown-editor.ts:209-224` (placeholder inserted, upload result applied only via `replaceMarker` to the live view, line 229-234) together with `web/src/app/features/notes/editor/note-editor-page.ts:96-100` and `:117-124` (flush on destroy / on opening another note saves the body as it is, including the placeholder).

Requirement:

AC2 / SPEC.md §8.1: paste/drop image -> upload -> inserts `![](cockpit-file:{id})`; assumption "pending edits are flushed when leaving the page".

Expected behavior:

When the user leaves the editor (back to All notes, another page, or a chip to another note) while an image upload is still running, the note stored on the server must not end up with the temporary `![Uploading name…](uploading:N)` placeholder. Either the upload result is still written into the note (preferred: the image the user pasted is kept), or the placeholder is removed from what is saved.

Actual behavior:

The flush on leaving sends `PATCH` with `Trip\n![Uploading tent.png…](uploading:0)\n`. When the upload then completes, the editor view is destroyed, so `replaceMarker` changes nothing the page sees and no further save is sent. The note keeps the placeholder for good (the preview shows the text "Uploading tent.png…"), and the uploaded file in /Attachments/Notes/ is not referenced by the note.

Severity:

Medium (persisted wrong note content and a lost image reference in a realistic flow: paste a large image, then click "All notes" or a chip right away).

Fix required:

`Yes — production code must be fixed by the parent worker / fixer.`

## Coverage

Status: verified (whole-file coverage of all 7 task-changed executable production files; measured with the failing TW-1 test deselected, see below).

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured with a separate tool. Six of the seven files were added entirely by this task (`git diff --stat 3f163da1..HEAD`), so for them whole-file coverage equals changed-line coverage; in `notes.routes.ts` the task added the `:id` route entry, whose lazy `loadComponent` callback (line 12) is the uncovered line.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `web-notes-editor`; base `3f163da11c2057f12d788f154dbeba902e21d751`; HEAD `c64fdf99c58ab8ece443e140bc882a129ef7e3ea`; commits `70b773ba`, `9619aa91`, `c64fdf99` supplied by the parent and confirmed with `git log`/`git diff --stat 3f163da1..HEAD`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD `c64fdf99` plus uncommitted test files; sha256 list of all `web/src/app/features/notes/editor/*.ts` and `notes.routes.ts` taken before and after the run was identical (hash of the list file `77bbb2ce60db9ff70fead76704f2ee7e527642e61619d1a6ab356fb5d83674bb`).
- Exact included files; renamed/deleted/excluded files and reasons: included the 7 files in the table. Excluded: `*.html`/`*.scss` (template/styles, not instrumented separately; templates are exercised through the component tests), `web/package.json` and `web/package-lock.json` (dependency config), `docs/ARCHITECTURE.md` (docs), `web/e2e/notes/editor.spec.ts` and `*.spec.ts` (tests). No renames or deletions.
- Provider/version, command, working directory, and test selection: Vitest 5.0.5 with `@vitest/coverage-v8` via `@angular/build:unit-test`; `npm run coverage -- --filter '^(?!.*upload placeholder in the saved note)'` in `web/`; whole suite, with only the TW-1 test deselected by the CLI filter because the provider writes no report when a test fails (no configuration change).
- Run start/completion time and exit status: 2026-10-21T04:45:06Z to 04:45:24Z; exit 0; 88 files, 1591 passed, 1 skipped (TW-1 deselected). The unfiltered run before it (04:44:33Z to 04:44:54Z) exited non-zero on TW-1 and wrote no coverage files; nothing from it is used.
- Generated report paths and report hashes/run identifier: `web/coverage/web/coverage-summary.json` sha256 `eb9e4f38f3b9bb15a30361815a60b2d1a4e83010366e94010914448f1255c2ea`; `web/coverage/web/coverage-final.json` sha256 `6f6612d1e200ed6e0c81f21ad9d9e26cf9ed440a5b3e4f001f7e5c92c5383589`; `web/coverage/web/lcov.info` sha256 `80cdcecdb4e197b44f0b0dc81f61158b280f8384d0d2d5ca522a41cc44e827c8`.
- Evidence outputs are fresh and inputs stayed unchanged: yes. The failed unfiltered run removed the previous report, the filtered run regenerated it (timestamps 06:45 local), and the input snapshot was unchanged.
- Final run after latest test additions and production fixes: yes for the test additions; no production fixes have been made yet (TW-1 is open), so coverage must be measured again after the fix.

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `web/src/app/features/notes/editor/markdown-editor.ts` | 74/77 (96.10%) | 44/48 (91.67%) | 22/23 (95.65%) | 67/68 (98.53%) |
| `web/src/app/features/notes/editor/note-editor-page.ts` | 182/187 (97.33%) | 73/85 (85.88%) | 39/40 (97.50%) | 156/157 (99.36%) |
| `web/src/app/features/notes/editor/note-editor.service.ts` | 13/13 (100%) | 5/5 (100%) | 8/8 (100%) | 10/10 (100%) |
| `web/src/app/features/notes/editor/note-markdown-view.ts` | 30/30 (100%) | 24/25 (96.00%) | 6/6 (100%) | 23/23 (100%) |
| `web/src/app/features/notes/editor/note-markdown.ts` | 109/109 (100%) | 56/57 (98.25%) | 20/20 (100%) | 100/100 (100%) |
| `web/src/app/features/notes/editor/note-revisions-dialog.ts` | 78/78 (100%) | 23/27 (85.19%) | 8/8 (100%) | 53/53 (100%) |
| `web/src/app/features/notes/notes.routes.ts` | 2/3 (66.67%) | N/A (0/0) | 1/2 (50.00%) | 2/3 (66.67%) |
| Aggregate (summed counters, all 7 files; none missing) | 488/497 (98.19%) | 225/247 (91.09%) | 104/107 (97.20%) | 411/414 (99.28%) |

Before the added tests (baseline run 2026-10-21T04:33Z, same command without filter, all passing): aggregate 452/497 statements, 193/247 branches, 96/107 functions, 389/414 lines; `note-markdown-view.ts` 15/25 branches, `note-editor-page.ts` 60/85 branches, `note-revisions-dialog.ts` 18/27 branches.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `markdown-editor.ts:80` | completion request aborted while the search runs | Not added: CodeMirror-internal timing; the result is discarded either way. Low risk. |
| `markdown-editor.ts:155` (`focus()`) | public helper, not called by the page | Not added: unused by the task's features. |
| `markdown-editor.ts:205,230` | `data` null (paste without clipboard data); upload result arriving after the view was replaced | `:230` is the path behind TW-1 (the view is destroyed, not replaced); covered in behavior by the TW-1 test. Null clipboard data is defensive. |
| `note-editor-page.ts:57` (viewChild arrow), `:95`, `:118` | route without id; re-navigation to the same id | Non-material: Angular plumbing and a no-op guard. |
| `note-editor-page.ts:148` | toggle that does not change the body | Defensive: the view only emits lines of checklist items. |
| `note-editor-page.ts:169`, `:259` | flush with nothing to save while in error state; edits made during a save after the debounce timer was cleared | Minor state transitions; the observable behavior (no save / save later) is covered by neighbouring tests. |
| `note-editor-page.ts:277,295-298,331,345` | `??`/ternary fallbacks: note without title, INCOMING link direction, unavailable linked item, stored mode `edit` | Partly covered via the view tests for unavailable items; remaining fallbacks are low risk. |
| `note-markdown-view.ts:63` | chip without `data-route` value (`?? '/'`) | Defensive: chips with `data-route` always carry a value. |
| `note-markdown.ts:127` | ordered list tag | Covered behavior exists in `note-markdown.spec.ts` for lists; one branch side only. |
| `note-revisions-dialog.ts:52,87,106,122` | revision without title in the preview header; non-ApiError load failure; restore with nothing selected (button disabled) | Low risk; the disabled restore button is asserted in `restores nothing when the dialog is closed without a choice`. |
| `notes.routes.ts:12` | lazy route loading of `/notes/:id` | Executed by the Playwright e2e spec (AC5, passed), not by Vitest. |

## Unable to test

- The real paste/drop of OS clipboard data and drop coordinates cannot be produced in jsdom: the drop tests stub `EditorView.posAtCoords` (jsdom has no layout); the actual browser paste path is covered by the Playwright spec (AC5, passed). No follow-up needed.
- Observation, not a test failure: leaving a note while it is in conflict or after a failed save discards the unsaved edits without asking (in-app navigation has no guard; only `beforeunload` covers closing the tab). No requirement defines this case; the parent may want to record it as an assumption or follow-up.

## Summary

Tests added; production defects were discovered and must be passed to the `fixer` before task completion. TW-1 (Medium): leaving the editor during an image upload saves the "Uploading…" placeholder into the note permanently. All other acceptance-criteria behavior is covered and passes, and the Playwright e2e spec passed against the docker compose e2e stack. Test files and this report are left uncommitted for the parent worker.
