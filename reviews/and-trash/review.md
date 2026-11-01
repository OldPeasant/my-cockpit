# Code Review

Date: 2026-11-01

Task: and-trash - Android: Trash (list, restore, delete forever)

Scope: `git diff 57f2ae75c1ea16aa42c195aa0a665e14502f4519..9f8d9851ff527993f366164d0b39a2e8b0cb7efd` (final code). Task commits f442e23c, 2c91040c, b7db7f51, d6e92bf8, 0527b945, 576ba919, 9f8d9851, plus the merge 8e2caf1f and the commits that touch only `reviews/` (b18bd6fa, d15721fd). The review focuses on what changed since the previous post-fix review (0527b945..9f8d9851). Production code is unchanged in that range. Only `TrashRouteTest.kt` (+35 lines), `TrashRepositoryTest.kt` (+17 lines), `reviews/and-trash/tests.md` and `tasks/and-trash.md` changed.

Requirements:
- tasks/and-trash.md: acceptance criteria and recorded assumptions (Trash in More between Review and Settings with the Delete icon; `GtdSync.refreshAll()` after a TASK/PROJECT restore; all pages loaded up front; icon buttons; snackbars without undo)
- SPEC.md (Trash, §4.2 API conventions), docs/ARCHITECTURE.md, docs/QUALITY.md, AGENTS.md "Project notes"
- reviews/and-trash/fixes.md (fixes for CR-1, CR-2), reviews/and-trash/tests.md

Git range:
- Branch: main
- HEAD: 9f8d9851ff527993f366164d0b39a2e8b0cb7efd
- Base/start: 57f2ae75c1ea16aa42c195aa0a665e14502f4519
- Commits reviewed: 10 (7 task commits, 1 merge, 2 commits that touch only reports); no uncommitted changes

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Earlier findings (previous review rounds)

- [x] **CR-1 | A GTD restore was confirmed only after the full GTD cache refresh.** Status: fixed (d6e92bf8) and still fixed. `TrashRepository.restore()` (`android/app/src/main/java/ch/sonensei/cockpit/trash/TrashRepository.kt:85-92`) returns right after the server call. The reload of the restored item's list runs in a repository-owned `CoroutineScope(SupervisorJob() + Dispatchers.Default)` (line 66), and `load {}` wraps it so it cannot crash the app. Regression test: `a restore returns without waiting for the reload of its lists`.
- [x] **CR-2 | The production restore-reload binding was not tested.** Status: fixed (d6e92bf8) and still fixed. The tests `restoring a task or project reloads the GTD cache, other types do not` and `the injected repository reloads the GTD cache in the background after a task restore` (the latter through the real `@Inject` constructor) cover it.

## Changes since the previous review

- `TrashRouteTest.the Trash loads again when the app comes back to it`: provides its own `LifecycleRegistry` through `LocalLifecycleOwner`, moves it RESUMED -> CREATED -> RESUMED on the main thread (`runOnIdle`), and asserts a second `repo.list()` call and the new list contents. This matches the production behaviour: `TrashRoute` calls `viewModel.refresh()` in `LifecycleResumeEffect` (`TrashScreen.kt:81-82`), which uses `LocalLifecycleOwner` by default. The test checks observable behaviour (the call count and the rows shown). It is valid, and it is no longer unverified, because it passes (see below).
- `TrashRepositoryTest.an id that is no UUID fails the load`: the serializer rejects an id that is not a UUID with `SerializationException`/`IllegalArgumentException`. `load` maps both to `Failed(null)` (`TrashRepository.kt:106-109`). The assertion is correct.
- `TrashRepositoryTest.a load answered without a body is a failure, not an empty Trash`: a 204 response gives `body == null`, so `body()` throws `TrashHttpFailure(204)` (`TrashRepository.kt:134-137`), which becomes `Failed(204)`. The test asserts only `is Failed`, which is enough for the intent (no "Trash is empty").
- `reviews/and-trash/tests.md` lists an uncommitted `TrashRepositoryTest.kt` change with a sha256. That change is now committed in 9f8d9851. This only explains the wording of the report and is not a finding.

## Acceptance criteria (re-confirmed on the final code)

1. Reachable from More: `CockpitDestination.Trash` sits between Review and Settings and uses the Delete icon. `CockpitShell` routes it to `TrashScreen`. Each row shows the type icon and label, the title, the deletion date and the days until purge. Tests: `CockpitShellTest`, `CockpitDestinationTest`, `TrashScreenTest`, `TrashModelsTest`.
2. Restore and delete forever work per row. Delete forever goes through a confirmation dialog. A per-item busy guard ignores double taps, a 404 drops the row, and a load that started before a change is replaced. A TASK/PROJECT restore refreshes the GTD cache in the background. Tests: `TrashViewModelTest`, `TrashRepositoryTest`, `TrashScreenTest`.
3. Loading, empty and error states: a progress bar while loading, or the pull indicator for a pull-to-refresh. The empty state appears only after a successful load. The offline and failed messages offer a retry and keep the last items. The list reloads on resume, and this is now tested. Tests: `TrashScreenTest`, `TrashRouteTest`.
4. EN and DE `strings_trash.xml` have the same keys, placeholders and plurals. `TrashModelsTest` enforces this.
5. Unit tests and Compose UI tests (Robolectric) cover criteria 1-4. The full `ktlintCheck testDebugUnitTest lintDebug` run is recorded by the parent and in fixes.md and tests.md. This reviewer ran only the focused suite, as instructed.

Security: nothing is logged. `TrashHttpFailure` carries only the HTTP status, never the response body, and the test data is fictitious.

## Commands run

- `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.trash.*'` -> BUILD SUCCESSFUL. JUnit XML results: TrashRepositoryTest 21, TrashViewModelTest 17, TrashScreenTest 19, TrashRouteTest 7, TrashModelsTest 5 = 69 tests, 0 failures, 0 errors, 0 skipped.
- `git diff 0527b945..HEAD -- android/app/` -> only test files changed. `git diff --stat 57f2ae75..HEAD` -> all changes are inside the task's `touches`, plus the shell and destination tests for the touched `ui` code.

## Review conclusion

No material issues found.
