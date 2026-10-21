# Code Review

Date: 2026-10-21

Task: and-projects-lists - Android projects, waiting, someday

Scope: `git diff 3148bba7..bd8f525a -- android`. This is a re-review of the final production code at revision bd8f525a, after the fixes for CR-1 to CR-6 of the previous review. HEAD 83140003 only adds `reviews/and-projects-lists/fixes.md`, so the working tree matches bd8f525a for `android/`.

Requirements:
- tasks/and-projects-lists.md (2 acceptance criteria, 4 recorded assumptions)
- SPEC.md sections 4.2 (ETag/If-Match, 409/412), 5.1, 5.2 (stalled rule, "Choose next step" prompt), 5.3 (project list grouped by area with stalled ones highlighted, project detail with next-step marker, Waiting For sorted by waitingSince with follow up), 9.2, 9.3 (read cache and offline banner)
- AGENTS.md project notes; the previous review and reviews/and-projects-lists/fixes.md
- Existing conventions in gtd/data (GtdRefresh `refreshing`, `GtdRefreshTriggers.on`) and gtd/next

Git range:
- Branch: main
- HEAD: 83140003 (production code identical to bd8f525a)
- Base/start: 3148bba731393bc3d96d27804616e8c16db4d3b0
- Commits reviewed: 4 (93c75594, c5384f51, 8b0ba2e7, bd8f525a); 83140003 touches only reviews/
- Uncommitted changes: none at the start of the review

Summary: 0 P0, 0 P1, 0 P2, 2 P3

## Status of the earlier findings

- CR-1 (projects shown under "No area" without loaded areas): resolved. `ProjectsViewModel.kt:138-160` keeps the areas it loaded last (`knownAreas`). While no areas are known, the list shows loading instead of grouping (`areasLoading`). If areas never loaded, `groupByArea(projects, null)` (`ProjectsViewModel.kt:342-348`) puts projects that have an area into their own group, headed "In an area (areas not loaded)", and `ProjectsScreen.kt:202` shows a StaleBanner with retry. Covered by VM, grouping and screen tests. One side effect is noted as CR-1 below.
- CR-2 (detail read-only after a failed reload, no banner and no retry): resolved. `ProjectsScreen.kt:346` shows `StaleBanner` with retry whenever `detail.loaded && detail.error != null`, also when no open tasks are left. Covered by two screen tests.
- CR-3 (the prompt's "Add" was not atomic): resolved. `ProjectWorkRepository.addNextStep` (`ProjectWorkRepository.kt:93-112`) returns the created task id even when setting the next step fails, and skips the create when `created` is passed. The VM stores it in `NextStepPrompt.created` and reuses it only for the same trimmed title of the same project (`ProjectsViewModel.kt:250-264`). Covered by repository and VM tests.
- CR-4 (`ProjectWorkRepository` untested): resolved. `ProjectWorkRepositoryTest` covers the If-Match values, the `needsNextStep` mapping, the create/re-read/set call order, offline skipping the cache reload, the 404/409/412 to CONFLICT mapping, 400 to FAILED, area ordering and an area load failure.
- CR-5 (prompt not tied to its project): resolved. `NextStepPrompt.projectId` is set. `complete()` captures the open project and prompts only if it is still selected (`ProjectsViewModel.kt:212-224`). The detail shows only its own prompt (`:183`). `makeNextStep` and `addNextStep` close only their own project's prompt. `choose()` does nothing without an open project.
- CR-6 (`completed` never pruned): resolved. `forgetCompleted` (`ProjectsViewModel.kt:144`, `:282-285`) prunes on every successful load that no longer holds the id, and a failed reload prunes nothing. A load that is still in flight and still holds the task keeps it hidden, so pruning does not cause a completed task to flash back. Covered by a VM test.

## Acceptance criteria

- Criterion 1 (Projects with stalled ones highlighted, detail with tasks and next step, Waiting For, Someday): implemented. Projects are grouped by area in position order, stalled projects come first with an accent bar and a "No next step" badge, and finished projects sit behind a chip. The detail shows open tasks in project order, a next-step marker, "make next step" and "Choose next step", and the prompt opens when the server's `needsNextStep` is true. Waiting is sorted by `waitingSince`, oldest first, undated last, and has "Follow up". Someday has "Activate". Mutations send strong `"version"` If-Match values and map 404/409/412 to a conflict message, as SPEC 4.2 requires.
- Criterion 2 (the `gtd.projects.*` and `gtd.lists` unit tests pass, covering the stalled badge and the next-step prompt with Robolectric/Compose UI tests): the tests exist (`ProjectsScreenTest` covers the stalled badge and the prompt, `ProjectsViewModelTest` covers the prompt logic). The reviewer did not run them, because the test-writer runs Gradle in parallel in the same clone. fixes.md reports 98 tests passing. The task file still shows both criteria unticked. The parent must record evidence with `tasks.py verify` before `done`. That is not a finding at this stage.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | Cached projects are hidden behind "Loading" until the live areas request ends, which can take the full HTTP timeout when the server is unreachable**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/gtd/projects/ProjectsViewModel.kt:101`, `android/app/src/main/java/ch/sonensei/cockpit/gtd/projects/ProjectsViewModel.kt:154`, `android/app/src/main/java/ch/sonensei/cockpit/gtd/projects/ProjectsViewModel.kt:169`
    - Category: Correctness
    - Problem: When no areas are known yet (first subscription of a new view model), `areasLoading` is true and `groups` is empty, even though Room already holds the projects. `liveLoad` does not check connectivity first. It runs the request, and only an `IOException` (`GtdRefresh.kt:73-81`) ends it. OkHttp's default 10 s connect and read timeouts apply (no override was found outside the change stream). So when the device has a network but the server cannot be reached (server down, captive portal, bad Wi-Fi), the Projects tab shows "Loading" for up to the timeout, then the "areas not loaded" grouping. Before the CR-1 fix, the cached projects appeared at once.
    - Why it matters: Projects are cached so that they can be shown when the server is unreachable (SPEC 9.3 behaviour, which this screen follows with its offline banner). In exactly that case the cache is now held back for many seconds on every cold open.
    - Suggested fix: Show the "areas not loaded" grouping (`groupByArea(visible, null)`) while areas are still loading and none are known, without the error banner, instead of an empty loading list. Or apply the areas wait only while the projects themselves are not offline, or only for a short grace period. Add a VM test with cached projects and a pending areas load.

- [ ] **CR-2 | An offline project detail shows two stacked banners that say the same thing**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/gtd/projects/ProjectsScreen.kt:162`, `android/app/src/main/java/ch/sonensei/cockpit/gtd/projects/ProjectsScreen.kt:346`
    - Category: Quality
    - Problem: When the server is unreachable, the project list's `offline` flag is set, so `OfflineBanner` is shown. The detail's task reload also fails with `LoadError.OFFLINE`, so `StaleBanner` is shown with the "offline" stale text. The user sees two offline banners one above the other.
    - Why it matters: The UI is redundant, and the screen is cluttered with what is effectively one message. The retry button that matters sits in the second banner.
    - Suggested fix: Show the detail's `StaleBanner` only when `detail.error == LoadError.FAILED || !state.offline`. Or have a single banner with retry when both apply.

## Review conclusion

No material issues found. All six earlier findings (CR-1 to CR-6 of the previous review) are resolved in bd8f525a. The two remaining low-priority findings are optional to fix before completing the task.
