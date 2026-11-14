# Code Review

Date: 2026-11-14

Task: web-task-new-project - Web task detail: create a new project from the project picker

Scope: `git diff d75effeac87421101b605c2e4816e3f036b50ceb..06aa16a64 -- web` (final code, round 2: the whole task diff again, plus a check of the CR-1 fix from round 1)

Requirements:
- tasks/web-task-new-project.md (acceptance criteria and the recorded assumption: title asked inline, Create saves the whole form)
- AGENTS.md project notes (i18n, conventions)
- Reference behavior: web/src/app/features/gtd/inbox/inbox-page.ts ("New project" in processing, retry without a second project)
- Existing conventions in web/src/app/features/gtd/task-detail/task-detail.ts and web/src/app/features/gtd/data/project-store.ts
- reviews/web-task-new-project/fixes.md (CR-1 fix description)

Git range:
- Branch: main
- HEAD: 06aa16a644eb34f17cbfd04eae1115b0706c287b
- Base/start: d75effeac87421101b605c2e4816e3f036b50ceb
- Commits reviewed: 6 (3 touch `web/`: e31ee4903 feature, 433f5a45c tests, 06aa16a64 CR-1 fix; the rest are board and report commits)
- Uncommitted changes: none at the start of the review

Summary: 0 P0, 0 P1, 0 P2, 1 P3

## Verification of round-1 finding

- CR-1 (retry reused the earlier project when a different title was entered) is fixed. `createdProject` now stores the trimmed title (`task-detail.ts:164`). `createProject()` reuses it only when both the task id and the title match (`task-detail.ts:296-300`); otherwise it creates the new project and replaces the entry (`task-detail.ts:306`). `discard()` clears it (`task-detail.ts:475`). Keeping the entry across a 412 conflict reload is fine now: the same title reuses the project (so no duplicate, criterion 2) and a different title creates the one asked for. The new spec cases cover a different title after a 412 (including the reloaded `If-Match`), the same title after a 412, and Discard after a 500.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | `creatingProject` is not scoped to the task, so a task switch during creation briefly locks the next task**
    - File: `web/src/app/features/gtd/task-detail/task-detail.ts:158`
    - Category: Correctness
    - Problem: `converting` and `acting` are tied to the task id, so "another task the editor switched to is free" (`task-detail.ts:140-154`). `creatingProject` is a plain boolean, and `busy` includes it (`task-detail.ts:166-172`). If the user switches to another task while `projects.create` is still running, the new task's project picker is disabled (`task-detail.html` `[disabled]="creatingProject()"`). Its Save, Complete, Delete and conversions are blocked too, and background reloads for it are skipped (`isStale` checks `busy()`). All of this lasts until the old request settles. The spec case "switching to another task while creating does not assign the project to it" does not check whether the second task is usable before `finish(created)`.
    - Why it matters: This is a small inconsistency with the editor's own pattern for operations that run per task. The lock lasts only as long as one POST, and nothing is lost.
    - Suggested fix: Store the task id, a nullable string signal, and compute `creatingProject` as `id === this.taskId()`, as `conversion` and `converting` do. Clear the signal in `finally` only if it still holds that task's id. Then add an assertion to the existing task-switch spec.

## Review conclusion

No material issues found.

Notes (not findings): Criterion 1: "New project…" is the last option in the picker, it asks only for a title, `ProjectStore.create({ title })` sends no status or area (so the server defaults apply), and the task is assigned and saved in one step. Criterion 2: Cancel, Escape and an empty title leave the project unchanged and send no request. A failed create shows an error, keeps the title and retries on Create. A failed save keeps the created project chosen, and a retry with the same title, or with the main Save, does not create a second project. Criterion 3: the created project goes into the shared `ProjectStore` (`create` calls `accept`), so it appears in the picker and in store-based views. The project list and project detail parts still need run evidence from the parent worker, because the unit tests cover only the picker and the store. Criterion 4: all four new units are in `messages.xlf`, and `messages.de.xlf` has German targets for them. Criterion 5: the parent worker must record `npm run check` and `sh scripts/check.sh` results. This review did not run them (no Gradle, as instructed).
