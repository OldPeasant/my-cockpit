# Code Review

Date: 2026-11-08

Task: and-projects-manage - Android: create, edit, complete and drop projects

Scope: production changes `git diff 9714b6dc..7745affc -- android` (ProjectEditing.kt, ProjectWriteApi.kt, ProjectWorkRepository.kt, ProjectsViewModel.kt, ProjectForm.kt, ProjectsScreen.kt under `android/app/src/main/java/ch/sonensei/cockpit/gtd/projects/`, EN/DE `strings_projects_lists.xml`). The test-writer's in-progress test files were out of scope, as the parent worker asked.

Requirements:
- tasks/and-projects-manage.md (acceptance criteria and the four recorded assumptions)
- SPEC.md §5.3, §4.2 (PATCH semantics, ETag/If-Match)
- docs/PARITY.md (row "No create/edit/complete/drop/delete project")
- docs/ARCHITECTURE.md, AGENTS.md project notes
- Reference behavior: web/src/app/features/gtd/projects/project-form.ts (MAX_PROJECT_TITLE 500), server ProjectService (update: present fields applied, null clears; delete: soft delete, tasks keep their project; title max 500, description max 100000)

Git range:
- Branch: main
- HEAD: 7745affc5113e551e8800f25e088effbe5e4441a
- Base/start: 9714b6dc5a5558b625f35c0c233ffbce1696f3ce
- Commits reviewed: 1 (7745affc)

Summary: 0 P0, 0 P1, 1 P2, 2 P3

Things I checked and found correct:
- The PATCH body only contains changed fields. Emptied fields are sent as JSON `null`, which matches the server's `ProjectChanges` semantics. The hand-written `ProjectWriteApi` follows the same pattern the repo already uses (InboxRepository, NotesRepository, TaskDetailRepository).
- 404/409/412 become `ActionOutcome.CONFLICT`. Every change reloads the project cache first, and only then does the form get rebased on the current version. Fields the user did not change take the server's value.
- Delete sends `If-Match`, always asks for confirmation, and says that the tasks keep their project. This matches `ProjectService.delete`, as recorded in the assumption.
- The description renders through the notes `MarkdownView` both in the detail view and in the form preview. `NoteImages` is `@Singleton`, so no extra ImageLoader is created per view model.
- Every new string key is present in both `values/` and `values-de/`, including the plurals.
- No secrets, no logging of description contents, and no dependency changes.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | An old form save can close or replace a form the user opened later**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/gtd/projects/ProjectsViewModel.kt:313-321` (also `:373-383` for create; cancel stays enabled at `ProjectForm.kt:108` and through the BackHandler in `ProjectsScreen.kt`)
    - Category: Correctness
    - Problem: The form's Cancel button and system back stay active while `saving` is true. `saveForm` does not use `busy`/`run`, so nothing blocks the detail menu either. The save coroutine ignores whether the form it started from is still open and writes its result straight into `form`:
        - `DONE` sets `form.value = null`.
        - `CONFLICT` sets `form.value = f.rebase(...)`.
      Example: the user cancels while a save is in flight, taps Edit again (a new form, maybe with new edits), and the first save then returns. On `DONE` the new form closes and its edits are lost without a message. On `CONFLICT` the new form is replaced by a rebase of the old draft, and the conflict banner reappears for a form the user had already cancelled. For create: after a cancel, a later `DONE` still closes any form that is open and moves the user to the new project.
    - Why it matters: This is the "older request overwrites newer state" defect: unsaved user input is silently dropped or swapped out. It needs a slow network, but slow networks are normal on mobile.
    - Suggested fix: Have the coroutine apply its result only if the form is still the one it started (for example, give it a request id or token, or `form.update { if (it?.saving == true && it.projectId == f.projectId) ... else it }`). Or disable Cancel and back while `saving`, and block the menu actions for the project while its save runs (put the project id in `busy`). Add a ViewModel test for "cancel during save, then the result arrives".

## P3 Low

- [ ] **CR-2 | Complete or drop skips the confirmation while the project's tasks are still loading**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/gtd/projects/ProjectsViewModel.kt:333` (same for the form path at `:297-305`)
    - Category: Correctness
    - Problem: The open-task count is `detail.tasks.size`. While the project's tasks are still loading (`loaded == false`, no error), that count is 0. `readOnly` is false in this state and the menu is enabled, so Complete or Drop is sent without the confirmation, even if the project has open tasks.
    - Why it matters: Criterion 2 asks for a confirmation when open tasks remain. The change can be undone (Reactivate) and the tasks are not touched, hence P3.
    - Suggested fix: Treat "not loaded yet" as unknown. Either disable Complete/Drop until `detail.loaded` is true, or always ask while the count is not known.

- [ ] **CR-3 | A pending confirmation can show up again for a different project**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/gtd/projects/ProjectsScreen.kt:212`, `android/app/src/main/java/ch/sonensei/cockpit/gtd/projects/ProjectsViewModel.kt:249-252`
    - Category: Correctness
    - Problem: The dialog only shows while `detail != null`, but `confirm` stays set when the detail disappears. That happens when the selected project is trashed or changed elsewhere and the cache reload removes it while the dialog is open. `open()` does not clear `confirm`. If the user then opens another project, the old Delete or Finish dialog appears for that project (with its title but the old open-task count), and `confirm()` applies it to the newly opened project.
    - Why it matters: An unexpected destructive dialog appears for a project the user did not act on. The dialog shows the right title, which limits the risk.
    - Suggested fix: Clear `confirm` in `open()`, and clear it when the selected project is no longer in the cache. Better still, store the project id in `ProjectConfirm` and ignore or drop the confirmation when it does not match the open project.

## Review conclusion

Material issues found; fix before completing the task. CR-1 should be fixed or explicitly decided on by the parent worker. CR-2 and CR-3 are low-priority robustness fixes.

Note: when this review was written, no acceptance-criterion evidence had been recorded yet (`python tasks.py verify and-projects-manage` shows #1-#7 unchecked), and the new tests for criterion 7 were still being written by the test-writer. Both need to be done before `done`. Neither is reported as a finding, because they are part of the parent worker's remaining workflow.
