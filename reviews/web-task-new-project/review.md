# Code Review

Date: 2026-11-14

Task: web-task-new-project - Web task detail: create a new project from the project picker

Scope: `git diff d75effeac..8abd4876a` (task commit "web-task-new-project: New project… in the task detail project picker"; HEAD 612cec44d differs from 8abd4876a only in `tasks/web-task-new-project.md`)

Requirements:
- tasks/web-task-new-project.md (acceptance criteria and recorded assumption: inline title, Create saves the whole form)
- AGENTS.md project notes (i18n, conventions)
- Reference behavior: web/src/app/features/gtd/inbox/inbox-page.ts ("New project" in processing, `createdProjects` retry map)
- Existing conventions in web/src/app/features/gtd/task-detail/task-detail.ts and web/src/app/features/gtd/data/project-store.ts

Git range:
- Branch: main
- HEAD: 8abd4876a8dc7caf4dc0ecdaae72f060070d0566 (reviewed code revision)
- Base/start: d75effeac87421101b605c2e4816e3f036b50ceb
- Commits reviewed: 1 code commit (plus the board-only assume commit 2f7d95725)

Summary: 0 P0, 0 P1, 1 P2, 0 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | A retry silently assigns the previously created project even when a different title is entered**
    - File: `web/src/app/features/gtd/task-detail/task-detail.ts:295`
    - Category: Correctness
    - Problem: After "New project…" created a project and the following save failed, `createdProject` stays set for the task. It is cleared only by picking another option in the picker (line 265) or by a successful save (line 315). It is not cleared by Discard (`discard()`), by a 412 conflict reload (`failed()` -> `load(..., 'conflict')`, which resets the draft to the server version without the new project), or when the user types a different title. Any later "New project…" + Create for the same task then takes `projectId` from `createdProject` and ignores the entered title (lines 295-296). Example: create "Garage", the save fails with a conflict and the form reloads; the user picks "New project…" again, types "Garden", presses Create: the task is assigned to "Garage" and no "Garden" project exists. Inbox processing does not substitute silently: it pre-selects the created project in the visible project select (`inbox-page.ts:192-197`), and choosing "New project" there with a title always creates that project.
    - Why it matters: The user gets assigned to a project with a name they did not ask for and no feedback. The "no second project on retry" guarantee (criterion 2) is met, but at the cost of wrong behavior on a plausible path (conflict reload or Discard, then trying again with a corrected title).
    - Suggested fix: Reuse the remembered project only when the trimmed title equals that project's title (or the title used to create it); otherwise create a new one and forget the old entry. Alternatively, clear `createdProject` in `discard()`, but the conflict path also needs handling. Add a spec case: create fails at save (412 or 500), retry with a different title creates or assigns the project with that title.

## P3 Low

None.

## Review conclusion

Material issues found; fix before completing the task.

Notes (not findings): the four new UI strings are present in `messages.xlf` and `messages.de.xlf` with German targets. The created project is added to the shared root `ProjectStore` (`ProjectStore.create` -> `accept`), so it shows in the picker and in views using that store. Unlike Inbox processing, the task detail does not make the task the new project's next step, so the new project stays stalled. The criteria do not require that, and assigning an existing project in task detail never did it either. No acceptance-criterion evidence was recorded yet (`tasks.py verify web-task-new-project` shows all five open). Criterion 3 (project list, project detail) and criterion 5 (`npm run check`, `sh scripts/check.sh`) still need run evidence from the parent worker; the unit tests cover only the picker side.
