# Code Review

Date: 2026-11-08

Task: web-projects-plus - Web projects: delete project, all tasks in project detail

Scope: task commits aef67f2da and 5cdc8d7e1 (`git diff 7268744e1..HEAD`), web/src/app/features/gtd/projects/ and web/src/locale/

Requirements:
- tasks/web-projects-plus.md (acceptance criteria and the three recorded assumptions)
- SPEC.md §4.2 (ETag/If-Match), §5 (GTD, tickler rule), soft delete / Trash
- docs/PARITY.md (row "No project delete; project detail shows open Next tasks only")
- docs/ARCHITECTURE.md, AGENTS.md project notes (i18n, lint, feature folders)
- Existing conventions: TaskUndo, ListStore.mutate, trash/purge-dialog, settings/security dialogs

Git range:
- Branch: main
- HEAD: 5cdc8d7e1
- Base/start: 7268744e106c4478bda5fe8a63937bba2a0a7706
- Commits reviewed: 2

Summary: 0 P0, 0 P1, 1 P2, 2 P3

Verified as correct:
- Criterion 1: "More project actions" menu on project detail, confirmation dialog (title, Trash text,
  open-task count with singular/plural), `ProjectStore.remove` with If-Match, navigate to /projects,
  Undo snackbar restoring via `restoreTrashItem` type PROJECT. Restoring uses root-provided services, so
  it still works after the page was destroyed by the navigation. The dialog text matches the server rule
  recorded in the assumption (tasks keep their project).
- Criterion 2: `ProjectTaskStore.load({ projectId })` has no list filter and `matchesTask` keeps every list,
  so Waiting/Someday/Scheduled/Inbox tasks show; `listLabel` labels non-Next rows, the next-step marker
  still works on a non-Next task. Tasks with a future tickler date stay hidden, which is the SPEC.md
  tickler rule, not a gap.
- Criterion 3: all 12 new ids are in messages.xlf and messages.de.xlf with German targets; list names
  match the existing nav.* translations (Warten auf, Irgendwann, Geplant, Eingang); placeholders match.
- markdown.ts / markdown.spec.ts changes are formatting only (no behaviour change).
- Grid change (4 columns) works for rows with and without a label, since each row is its own grid.
- Tests cover labels, the next-step marker on a non-Next task, confirm/cancel/dismiss, server refusal,
  If-Match, navigation, snackbar text, Undo, and the dialog texts for 0/1/n tasks.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | Page flips to "Project not found" while the delete runs; focus is lost if the server refuses**
    - File: `web/src/app/features/gtd/projects/project-detail-page.ts:299`
    - Category: Correctness
    - Problem: `remove()` calls `this.projects.remove(project)`, which goes through `ListStore.mutate` with
      `() => undefined` as the optimistic change (`data/project-store.ts:87-93`, `data/list-store.ts:154-169`).
      `project()` turns `undefined` as soon as the request starts, so the template (`@if (project(); as project)`
      ... `@else if (projects.loaded())`) replaces the whole page with "Project not found" while the DELETE
      is in flight. The menu trigger's `[disabled]="busy().has('delete')"` can never show, because the
      trigger is no longer rendered. When the server refuses (412, network error), the store rolls back and
      the page comes back, but the focused element was removed from the DOM, so keyboard and screen reader
      focus drops to the document body; a screen reader may also pick up the "not found" text. The test
      "stays on the project when the server refuses the delete" only checks the final h1 and does not see the
      transient state or the focus.
    - Why it matters: a slow or failing delete shows a misleading "not found" state and leaves keyboard
      users without focus after an error (accessibility regression in an error path).
    - Suggested fix: keep the page content while `busy().has('delete')` (e.g. hold a snapshot of the project
      for the template while deleting, or call the API delete in the page and drop the item from the store
      only after success), and on failure return focus to the "More project actions" trigger. Add a test
      that the heading stays during a pending delete and that focus returns to the trigger after a refusal.

## P3 Low

- [ ] **CR-2 | Delete uses the project version captured before the confirmation dialog**
    - File: `web/src/app/features/gtd/projects/project-detail-page.ts:291`
    - Category: Correctness
    - Problem: `project` is read before the dialog opens and passed to `projects.remove(project)` after the
      user confirms. The ProjectStore refetches on TASK and PROJECT change events, so a project updated
      while the dialog is open (another device, a task of the project completed elsewhere) gets a stale
      If-Match and a 412. `addNext` in the same component already uses `this.projects.find(project.id) ?? project`
      after an await for this reason.
    - Why it matters: avoidable "changed elsewhere" error for the user on a delete they just confirmed.
    - Suggested fix: after the dialog closes, re-read `this.projects.find(project.id)`; if it is gone, stop,
      otherwise delete that current version.

- [ ] **CR-3 | `autoFocus: 'dialog'` makes `cdkFocusInitial` on Cancel ineffective**
    - File: `web/src/app/features/gtd/projects/project-detail-page.ts:296` (and `delete-project-dialog.ts:33`)
    - Category: Quality
    - Problem: with `autoFocus: 'dialog'` Material focuses the dialog container and ignores `cdkFocusInitial`;
      the attribute on Cancel is dead. The other destructive confirmations (trash/purge-dialog, opened with
      the default focus) focus Cancel through `cdkFocusInitial`.
    - Why it matters: the two settings contradict each other and the dialog behaves differently from the
      other delete confirmations (Enter does not cancel by default).
    - Suggested fix: drop `autoFocus: 'dialog'` (default focuses Cancel via `cdkFocusInitial`, like purge-dialog),
      or remove `cdkFocusInitial` if focusing the container is intended.

## Review conclusion

Material issues found; fix before completing the task. (No P0/P1; CR-1 is a P2 resilience and accessibility
issue in the delete error path, CR-2 and CR-3 are small.)
