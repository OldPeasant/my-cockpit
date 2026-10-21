# Code Review

Date: 2026-10-21

Task: and-projects-lists - Android projects, waiting, someday

Scope: `git diff 404b41ed508b16bbef7bf02b77ed151cf6caef0b..4db5f564790d781a7e24efef6fe9624f3af0a544 -- android` (base = `git merge-base HEAD origin/main`). This is the final re-review. It checks the full task diff, the fixes for the P3 findings CR-1 and CR-2 of the previous re-review (commit 4db46544, fixes.md round 2), and the tests added in 4db5f564. The task commits were rebased onto the new main before this review (the hashes in tests.md, db31b7fa..0b64a78a, are the pre-rebase ones). Only `android/` and `reviews/and-projects-lists/` changed.

Requirements:
- tasks/and-projects-lists.md (2 acceptance criteria, 4 recorded assumptions)
- SPEC.md sections 4.2 (ETag/If-Match, 404/409/412), 5.2 (stalled rule, "Choose next step" prompt), 5.3 (projects grouped by area, stalled highlighted, detail with next step, Waiting For, Someday), 9.3 (read cache, offline banner)
- AGENTS.md project notes (lint, conventions, feature folders, fictitious test data)
- reviews/and-projects-lists/review.md (previous), fixes.md (rounds 1 and 2), tests.md
- Existing conventions in gtd/data (`GtdConnectivity.refreshing`, `GtdRefreshTriggers.on`) and gtd/next (route and in-app tests)

Git range:
- Branch: main
- HEAD: 4db5f564790d781a7e24efef6fe9624f3af0a544
- Base/start: 404b41ed508b16bbef7bf02b77ed151cf6caef0b
- Commits reviewed: 8 (1f76e12f, 52310894, 72463324, af8dcbcd, 13d00c1b, d6e6cb91, 4db46544, 4db5f564)
- Uncommitted changes: none at the start of the review

Commands run by the reviewer:
- `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.gtd.projects.*' --tests 'ch.sonensei.cockpit.gtd.lists.*'` - Pass. Fresh JUnit XML results (written 2026-10-21 12:42 +0200): ProjectsScreenTest 28, ProjectsViewModelTest 27, ProjectWorkRepositoryTest 16, ProjectGroupingTest 9, ProjectsRouteTest 4, ProjectsScreensInAppTest 3, ListScreensTest 14, ListViewModelsTest 13, LiveLoadTest 6, TaskListsRepositoryTest 6, ListRoutesTest 3. Total 129, 0 failures, 0 errors, 0 skipped.
- `cd android && ./gradlew ktlintCheck lintDebug` - Pass.

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## Status of the earlier P3 findings

- CR-1 (cached projects hidden behind "Loading" while the areas load): resolved. `ProjectsViewModel.kt:170` always uses `groupByArea(visible, v.areas)`. With no known areas this is the "In an area (areas not loaded)" grouping, so cached projects appear at once. `areasError` stays null while the areas load (`ProjectsViewModel.kt:155`, `Live.loading` means no data and no error), so no stale banner appears. `loading` (`ProjectsViewModel.kt:101`) is now true only when there is nothing to show. When the areas arrive, the projects are regrouped by area. Covered by the VM tests `cached projects show at once without their areas while the areas still load, without an error` and `without cached projects the list shows loading while the areas still load`, and by the screen tests `while areas load the cached projects show ...` and `while areas load without projects the list shows loading`. The earlier failure and recovery assertions are kept in `areas that cannot load keep projects out of No area, with an error`.
- CR-2 (two stacked offline banners in the detail): resolved. `ProjectsScreen.kt:347` shows the detail's `StaleBanner` only when `detail.error == LoadError.FAILED || !state.offline`. Offline, the list's `OfflineBanner` is the only banner, and the detail stays read-only (`readOnly` includes `offline`). A FAILED reload still shows its banner and retry. Covered by the two new `offline, a detail whose reload failed ...` screen tests.

## New tests (4db5f564)

- `ProjectsRouteTest` drives the real `ProjectsViewModel` through `ProjectsRoute` with mocked repositories. It covers open, show finished, back, make next step (with the project passed for its version), completing the next step leading to the prompt and a choice, and choose leading to skip, then add with a trimmed title. Swapped positional callbacks in `ProjectsCallbacks.of` would now fail a test.
- `ListRoutesTest` covers the Waiting order (given newest first, shown oldest first, checked by row bounds), follow up with the expected title, complete with undo, and Someday activate through the real view models.
- `ProjectsScreensInAppTest` checks, like the existing `NextScreenInAppTest`, that the Hilt activity shows the real Projects, Waiting and Someday screens instead of the placeholder. It accepts the loading, empty, list or error state, which is right for a test without a server.
- The two added `ProjectsScreenTest` cases (due date next to the stalled badge, offline detail error with retry) assert visible behaviour.
- Test data is fictitious (placeholder tokens, made-up titles and names).

## Acceptance criteria

- Criterion 1 (Projects with stalled ones highlighted, detail with tasks and next step, Waiting For, Someday): implemented, as the earlier reviews found. Nothing in the final changes regresses it. Projects are grouped by area in position order. Stalled projects come first, with an accent bar and a "No next step" badge, and finished projects sit behind a chip. The detail shows open tasks in project order, a next-step marker, "make next step", "Choose next step", and the prompt after completing the next step (`needsNextStep`). Waiting is sorted oldest `waitingSince` first, with "Follow up". Someday has "Activate". Mutations send strong If-Match values and map 404/409/412 to a conflict.
- Criterion 2 (`gtd.projects.*` and `gtd.lists` tests pass, covering the stalled badge and the next-step prompt with Robolectric/Compose UI tests): the reviewer ran the named command (see above): 129 tests, 0 failures. The stalled badge is covered by `ProjectsScreenTest` and `ProjectsRouteTest`, and the prompt by `ProjectsScreenTest`, `ProjectsViewModelTest` and `ProjectsRouteTest`. The task file still shows both criteria without recorded evidence. The parent must record it with `tasks.py verify` before `done`. That is a process step, not a finding.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

Observation, not a finding: on the first open of a new Projects view model with the server reachable, projects that have an area appear briefly under "In an area (areas not loaded)" and are regrouped once the areas response arrives. This is the trade-off the previous review suggested for CR-1, so that cached projects show without waiting for the network. It is transient and does not need a change.

## Review conclusion

No material issues found. The two P3 findings of the previous re-review are resolved and tested. The new route and in-app tests close the gap in the wiring between the screens and their view models. The focused test command named by criterion 2, ktlintCheck and lintDebug pass on HEAD 4db5f564.
