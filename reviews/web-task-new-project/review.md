# Code Review

Date: 2026-11-14

Task: web-task-new-project - Web task detail: create a new project from the project picker

Scope: `git diff d75effeac87421101b605c2e4816e3f036b50ceb..HEAD -- web` (final code). Round 3 is a delta review: it checks the one spec case added after round 2 (`git diff 06aa16a64..1e00f0bbd -- web`). The round-2 review of the whole task diff and of the CR-1 fix still applies, because production code has not changed since 06aa16a64.

Requirements:
- tasks/web-task-new-project.md (acceptance criteria and the recorded assumption: title asked inline, Create saves the whole form)
- AGENTS.md project notes (i18n, conventions)
- Reference behavior: web/src/app/features/gtd/inbox/inbox-page.ts ("New project" in processing, retry without a second project)
- Existing conventions in web/src/app/features/gtd/task-detail/task-detail.ts and web/src/app/features/gtd/data/project-store.ts
- reviews/web-task-new-project/fixes.md (CR-1 fix description)

Git range:
- Branch: main
- HEAD: ee224049a9d568951de096f24992f8ea25be00c9 (during the review the parent rebased the task onto new board commits. The old HEAD 1e00f0bbd and the new HEAD differ only in `tasks/web-task-new-project.md`, so the reviewed code is the same)
- Base/start: d75effeac87421101b605c2e4816e3f036b50ceb
- Commits reviewed: 4 commits touch `web/`: the feature, more tests, the CR-1 fix, and the retry-chain test. The others are board, review and test-report commits.
- Uncommitted changes: none in `web/`

Summary: 0 P0, 0 P1, 0 P2, 1 P3

## Round 3: delta since 06aa16a64

- The only `web/` change is a new spec case in `web/src/app/features/gtd/task-detail/task-detail.spec.ts:3549-3577`, 'a project created by a retry with another title is the one a later retry reuses'. No production file changed.
- What the case does: it creates "Garage", and the save fails. A retry with "Garden" creates p11, and that save fails too. A third retry with "Garden" then succeeds. It asserts that exactly two create requests were sent (Garage, Garden), that the final save assigns p11, and that the error alert is gone. The assertions follow from the CR-1 fix: `createProject()` replaces the remembered entry with the newest project (`task-detail.ts:306`) and reuses it only for the same task and title (`task-detail.ts:296-300`). So the case checks the behavior that matters: an earlier project is not reused, and no third project is created after a chain of failures. It checks results (request bodies, saved `projectId`, alert) rather than internals. The mock setup matches the neighbouring `answerConflictOnce` helper.
- The case does not assert that the alert appears after the first failure, only after the second. This is not a gap: the earlier spec cases for a failed save already cover that.
- Ran `npx ng test --watch=false --include src/app/features/gtd/task-detail/task-detail.spec.ts`: 1 file, 167 tests passed.

## Verification of round-1 finding

- CR-1 (retry reused the earlier project when a different title was entered) is fixed. `createdProject` now stores the trimmed title (`task-detail.ts:164`). `createProject()` reuses it only when both the task id and the title match (`task-detail.ts:296-300`); otherwise it creates the new project and replaces the entry (`task-detail.ts:306`). `discard()` clears it (`task-detail.ts:475`). Keeping the entry across a 412 conflict reload is fine now: the same title reuses the project (so no duplicate, criterion 2) and a different title creates the one asked for. Spec cases cover: a different title after a 412 (including the reloaded `If-Match`), the same title after a 412, Discard after a 500, and (new in round 3) a chain of retries with changing titles.

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

Notes (not findings): Criterion 1: "New project…" is the last option in the picker, it asks only for a title, `ProjectStore.create({ title })` sends no status or area (so the server defaults apply), and the task is assigned and saved in one step. Criterion 2: Cancel, Escape and an empty title leave the project unchanged and send no request. A failed create shows an error, keeps the title and retries on Create. A failed save keeps the created project chosen. A retry with the same title, or with the main Save, does not create a second project, and this also holds across a chain of retries with changing titles. Criterion 3: the created project goes into the shared `ProjectStore` (`create` calls `accept`), so it appears in the picker and in store-based views. The project list and project detail parts still need run evidence from the parent worker, because the unit tests cover only the picker and the store. Criterion 4: all four new units are in `messages.xlf`, and `messages.de.xlf` has German targets for them. Criterion 5: the parent worker must record the `npm run check` and `sh scripts/check.sh` results. This review ran only the task-detail spec file (no Gradle, as instructed).
