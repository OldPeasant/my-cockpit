# Code Review

Date: 2026-10-21

Task: and-projects-lists - Android projects, waiting, someday

Scope: `git diff a301e6e1..249a1ce7 -- android` (production code at 519ff16b, unchanged at 249a1ce7; tests at 249a1ce7)

Requirements:
- tasks/and-projects-lists.md (acceptance criteria and the 4 recorded assumptions)
- SPEC.md sections 4.2, 5.1, 5.2 (stalled rule, "Choose next step" prompt), 5.3 (project list/detail, Waiting For sorted by waitingSince with follow up), 9.2, 9.3
- AGENTS.md project notes; existing conventions in gtd/next, gtd/today, gtd/inbox and gtd/data
- Server behavior cross-checked: ProjectService.setNextStep, TaskService.complete (needsNextStep)

Git range:
- Branch: main
- HEAD: 249a1ce7efa1d42b9cbee4e5fd3819e6a106846d
- Base/start: a301e6e110f02b416038ad0dea74608b32a11575
- Commits reviewed: 2 (519ff16b implementation, 249a1ce7 tests)

Summary: 0 P0, 0 P1, 4 P2, 2 P3

Notes:
- Criterion 1 (Projects with stalled highlight, detail with tasks and next step, Waiting For, Someday) is implemented. Projects are grouped by area, stalled ones sorted first and marked with an accent bar and a "No next step" badge. The detail lists open tasks in project order with a next-step marker. The prompt opens when the server's `needsNextStep` is true. Waiting is sorted by `waitingSince`, oldest first, undated last, and has "Follow up". Someday has "Activate".
- ETag usage is consistent with the rest of the app: `If-Match` is the strong `"version"` for complete, move and next-step. `addNextStep` reads the project's current version again before setting the next step.
- Busy guards prevent double submits per task, and per project for the prompt's "Add". Read-only guards exist both in the view models and on the controls.
- No evidence has been recorded yet for either criterion (`tasks.py verify and-projects-lists` shows both unticked). This is not a finding at this stage. The parent must record it before running `done`.
- The reviewer did not run lint or the test suite. The parent and test-writer must record those results.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | Without loaded areas, every project is shown under "No area", with no sign that anything is wrong**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/gtd/projects/ProjectsViewModel.kt:120-121`, `android/app/src/main/java/ch/sonensei/cockpit/gtd/projects/ProjectWorkRepository.kt:58-61`
    - Category: Correctness
    - Problem: Projects come from the Room cache, but areas are loaded live through `liveLoad` and are not cached. The view model passes `a.data.orEmpty()` to `groupByArea`. While areas load, or when that load fails (offline start, any non-2xx), `known` is empty, so every project goes into the `null` group. The list then shows them all under one "No area" heading. The areas' `error` is dropped, so there is no banner and no state that tells the user the grouping is missing.
    - Why it matters: SPEC section 5.3 requires the list to be grouped by area. Offline, the cached project list is shown read-only (SPEC section 9.3), but it presents wrong information: every project appears to have no area. On startup it also briefly shows the "No area" grouping and then regroups.
    - Suggested fix: Keep the area grouping when areas are unavailable. Options: hold off grouping until areas have loaded once; treat an unknown `areaId` as "area not loaded" rather than "No area" while `a.error != null`; or keep the last loaded areas, for example in the view model or with a small cache. Add a test for an areas load failure.

- [ ] **CR-2 | Project detail: when a reload fails (not offline), all actions are disabled silently, with no banner and no retry**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/gtd/projects/ProjectsScreen.kt:342-373`, `android/app/src/main/java/ch/sonensei/cockpit/gtd/projects/ProjectsViewModel.kt:82`
    - Category: Correctness
    - Problem: `readOnly` is true whenever `detail.error != null`. If the tasks were loaded before and a reload then fails with `LoadError.FAILED` (4xx, parse error), the detail still shows the tasks, but checkboxes, "make next step" and "Choose next step" are disabled. Nothing explains why: `OfflineBanner` appears only when the global offline flag is set, which does not happen for FAILED. The retry button is rendered only when `!detail.loaded`. If the failed reload leaves no open tasks, the screen shows "No open tasks in this project." and no retry either. Waiting and Someday handle the same case with `StaleBanner` and a retry (`ListUi.kt:84`). The recorded assumption also says the last loaded list is shown "read-only with a banner".
    - Why it matters: The user sees a project that cannot be edited and gets no explanation. The only way to recover is to leave and reopen the project.
    - Suggested fix: Show the same stale banner with retry in the detail when `detail.loaded && detail.error != null`. Reuse `StaleBanner`, for example by making it internal in `gtd.lists`. Add a screen test for this case.

- [ ] **CR-3 | The prompt's "Add" is not atomic: if setting the next step fails, retrying creates the task again**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/gtd/projects/ProjectWorkRepository.kt:88-94`, `android/app/src/main/java/ch/sonensei/cockpit/gtd/projects/ProjectsViewModel.kt:195-204`
    - Category: Correctness
    - Problem: `addNextStep` makes three calls in a row: `createTask`, `getProject`, `setProjectNextStep`. If the create succeeds but a later call fails (network drop gives OFFLINE, 412 gives CONFLICT, another error gives FAILED), the outcome is not DONE. The prompt stays open and keeps the typed title, and the snackbar says the change did not go through. Tapping "Add as next step" again calls `createTask` a second time, so the project ends up with two identical tasks. The first task was already created on the server even though the user was told it failed.
    - Why it matters: Overlapping writes produce duplicate tasks in a normal retry flow, after a failure the user was shown.
    - Suggested fix: Remember the id of a task already created for this prompt and only retry the next-step call, or report a partial success. At the least, after a failed set, clear the text field and rely on the reloaded task list, where the new task appears as a choice. Add a test where the create succeeds and the set fails.

- [ ] **CR-4 | `ProjectWorkRepository` has no tests; its HTTP and ETag logic is mocked out**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/gtd/projects/ProjectWorkRepository.kt:58-112`, `android/app/src/test/java/ch/sonensei/cockpit/gtd/projects/ProjectsViewModelTest.kt:56`
    - Category: Tests
    - Problem: `ProjectsViewModelTest` replaces `ProjectWorkRepository` with a MockK mock, and no other test covers it. None of the following is exercised: the `If-Match` values sent by `completeTask` and `setProjectNextStep`; mapping the server's `needsNextStep` to `ProjectCompletion`; the order of the `addNextStep` calls (create, re-read version, set); skipping the cache reload when offline; mapping 404/409/412 to CONFLICT; ordering areas by `position`. `TaskListsRepositoryTest` does cover the equivalent paths for `TaskListsRepository`.
    - Why it matters: These are the ETag (SPEC section 4.2) and next-step (SPEC section 5.2) contracts the feature depends on. A wrong header or call order would not be caught.
    - Suggested fix: Add a `ProjectWorkRepositoryTest` with a fake or mock server, like `TaskListsRepositoryTest`, covering the cases above.

## P3 Low

- [ ] **CR-5 | The "Choose next step" prompt is not tied to the project whose task was completed**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/gtd/projects/ProjectsViewModel.kt:178`, `android/app/src/main/java/ch/sonensei/cockpit/gtd/projects/ProjectsViewModel.kt:44-46`
    - Category: Correctness
    - Problem: `complete()` sets `prompt.value` after the request returns, whatever project is selected at that point. If the user goes back and opens another project while the completion is still in flight, the prompt appears over project B. It says "Completed <task of A>" and offers B's tasks. `makeNextStep` and `addNextStep` would then change B.
    - Why it matters: In a narrow timing window, the next step can be set on the wrong project.
    - Suggested fix: Store the project id in `NextStepPrompt`, and show or apply the prompt only while `selected` equals that id. Alternatively, drop the prompt if `selected` changed during the request.

- [ ] **CR-6 | The `completed` set is never pruned, despite its documentation**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/gtd/projects/ProjectsViewModel.kt:111-112`, `android/app/src/main/java/ch/sonensei/cockpit/gtd/projects/ProjectsViewModel.kt:141`, `android/app/src/main/java/ch/sonensei/cockpit/gtd/projects/ProjectsViewModel.kt:176`
    - Category: Quality
    - Problem: The KDoc says completed tasks are "hidden until the reloaded project tasks no longer hold them", but no code ever removes ids from `completed`. If a task completed here is uncompleted elsewhere (web Logbook), it stays hidden in this view model for its whole lifetime. That lifetime is the Projects tab's back-stack entry.
    - Why it matters: The documentation does not match the behavior, and the screen can show stale results after changes made on another device.
    - Suggested fix: Remove ids from `completed` once a successful reload no longer contains them, or once the completion's reload has arrived. Alternatively, rely on the reload alone and drop the set.

## Review conclusion

Material issues found; fix before completing the task.
