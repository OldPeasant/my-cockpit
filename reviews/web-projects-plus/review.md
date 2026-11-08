# Code Review

Date: 2026-11-08

Task: web-projects-plus - Web projects: delete project, all tasks in project detail

Scope: final revision b7195f3d83674564015d5edb7407e99ce197b096, task commits aef67f2da, 5cdc8d7e1, e54172f83, e0c1bddbe, b7195f3d8 (`git diff 7268744e1..b7195f3d8`), web/src/app/features/gtd/projects/ and web/src/locale/. The step since the second pass (`git diff e0c1bddbe..b7195f3d8 -- web`) only adds two tests to `project-detail-delete.spec.ts`; production code is unchanged since e0c1bddbe.

Requirements:
- tasks/web-projects-plus.md (acceptance criteria and the three recorded assumptions)
- SPEC.md §4.2 (ETag/If-Match), §5 (GTD, tickler rule), soft delete / Trash
- docs/PARITY.md, docs/ARCHITECTURE.md, AGENTS.md project notes (i18n, lint, feature folders)
- First-pass review (HEAD 5cdc8d7e1) and reviews/web-projects-plus/fixes.md
- Existing conventions: TaskUndo, ListStore.mutate, trash/purge-dialog

Git range:
- Branch: main
- HEAD: b7195f3d83674564015d5edb7407e99ce197b096
- Base/start: 7268744e106c4478bda5fe8a63937bba2a0a7706
- Commits reviewed: 5

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## Status of first-pass findings

- CR-1 (P2, page flips to "Project not found" during delete, focus lost on refusal): fixed. A private
  `deleting` signal holds the project being deleted; `project()` falls back to it only for the same id
  while the store no longer has it (`project-detail-page.ts:94-99`). It is set right before the request,
  cleared on failure and after the navigation settles on success. On failure the focus returns to
  `.head button.more` via `afterNextRender` with the component injector (`project-detail-page.ts:313-320`).
  The menu trigger's `[disabled]="busy().has('delete')"` is now visible during the request. Tests
  "keeps the page content while the delete runs..." and "moves the focus back to the menu button after a
  refused delete" cover it.
- CR-2 (P3, stale project version after the dialog): fixed. After the dialog closes `remove()` re-reads
  `this.projects.find(shown.id)`, stops if it is gone, and deletes, announces and restores that current
  version (`project-detail-page.ts:308-310`). Tests "deletes the version current when the confirmation
  closes (fresh If-Match)" and "does not delete a project that disappeared while the confirmation was open".
- CR-3 (P3, `autoFocus: 'dialog'` defeats `cdkFocusInitial`): fixed. The dialog is opened with only
  `{ data }`, so Cancel gets initial focus as in trash/purge-dialog; a test checks that no `autoFocus`
  override is passed.

## Tests added in b7195f3d8

- "sends one DELETE when two confirmations were open at the same time": opens two confirmations through
  `remove()`, resolves both together and expects one DELETE and one snackbar. This exercises the `busy`
  re-check after the dialog plus the synchronous busy flag in `run()`; the assertions are on observable
  behaviour (HTTP calls, snackbar text), not internals. Valid.
- "drops the kept copy after a successful delete (no stale project on its route)": after a successful
  delete with the route stub still on p1, the heading no longer shows the project and the menu button is
  gone, so the `deleting` fallback is cleared once the navigation settles. Valid regression test for the
  CR-1 fix.
- Both tests use the existing harness (`whileDialogOpen`, `dialogOpens`, `chooseDelete`, `refresh`);
  no existing test was weakened, skipped or removed.

## Re-verified on the final code

- Criterion 1: "More project actions" icon button (localized aria-label, aria-hidden SVG) opens a mat-menu
  with "Delete project"; confirmation names the project, says it goes to the Trash and that open tasks stay
  open and keep the project (0 / 1 / n wording), matching the recorded server rule. `ProjectStore.remove`
  sends If-Match; double submit is guarded both before the dialog and after it (`busy` re-check) and by
  `run()`. Success navigates to /projects and shows an Undo snackbar (`UNDO_DURATION_MS`) that restores via
  `restoreTrashItem` type PROJECT and refreshes the store; root-provided services make this safe after the
  page is destroyed; a failing restore is swallowed (HTTP layer reports it), consistent with `run()`.
- Criterion 2: `listLabel` labels WAITING / SOMEDAY / SCHEDULED / INBOX rows and leaves NEXT unlabelled;
  the next-step marker still works on a non-Next task; the 4-column grid fits rows with and without a label.
- Criterion 3: all 12 new ids are present once in messages.xlf and messages.de.xlf with German targets;
  placeholders (`INTERPOLATION`, `count`, `title`) match between source and target; list names match the
  existing nav translations.
- Criterion 4: delete and label behaviour, dialog texts, refusal, retry, concurrent confirmations, Undo
  success and failure, the pending state, clearing the kept copy, focus and fresh If-Match are covered in
  `project-detail-delete.spec.ts` (21 tests across page and dialog). Running `npm run check` /
  `scripts/check.sh` is the parent worker's evidence; not re-run by the reviewer.
- markdown.ts / markdown.spec.ts changes are formatting only.
- No secrets, no unsafe HTML (labels and texts are interpolated, not bound as innerHTML), no new
  dependencies, changes stay inside the task's `touches`.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Review conclusion

No material issues found. CR-1, CR-2 and CR-3 from the first pass are resolved, and the two tests added
in b7195f3d8 are valid; the review covers revision b7195f3d83674564015d5edb7407e99ce197b096.
