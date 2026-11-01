# Code Review

Date: 2026-11-01

Task: and-trash - Android: Trash (list, restore, delete forever)

Scope: `git diff 08fab89c4~1..27770ed82 -- android/` (task commits 08fab89c4, d84cbc545, 5f8937d1a, 5dd4ddc43; 13ec7b8ff and 27770ed82 touch only `reviews/`). Final code after the fixes for CR-1/CR-2 of the earlier review.

Requirements:
- tasks/and-trash.md acceptance criteria and recorded assumptions (Trash in More between Review and Settings with the Delete icon; GtdSync.refreshAll after a TASK/PROJECT restore; all pages loaded up front, icon buttons, snackbars without undo)
- SPEC.md (Trash, §4.2 API conventions), docs/ARCHITECTURE.md, AGENTS.md "Project notes"
- reviews/and-trash/fixes.md (claimed fixes for CR-1, CR-2)
- Comparable code: android `auth/AuthRepository.kt`, `push/PushRegistrar.kt`, `gtd/data/GtdChangeStream.kt` (app-lifetime scopes), `gtd/data/GtdSync.kt`, `gtd/data/GtdRefresh.kt`

Git range:
- Branch: main
- HEAD: 27770ed82 (reviewed revision)
- Base/start: 08fab89c4~1 (69e163b21)
- Commits reviewed: 4 code commits (6 including the review/fix report commits)

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Review conclusion

No material issues found.

Verification of the earlier findings:
- CR-1 (restore waited for the GTD refresh): fixed. `TrashRepository.restore()` (`TrashRepository.kt:85-92`) returns right after the server call; on Ok it launches `lists.reload(type)` in a repository-owned `CoroutineScope(SupervisorJob() + Dispatchers.Default)` created in the `@Inject` constructor. The repository is `@Singleton`, so the reload survives leaving the screen. The pattern matches `AuthRepository`, `GtdChangeStream` and `CaptureWidgetUpdater`. The reload is wrapped in `load {}`, and `GtdSync.refreshAll()` maps its own I/O, HTTP, serialization, SQLite and session failures to `RefreshResult` (`GtdRefresh.kt:93-119`), so the background job cannot crash the app through an uncaught exception. Running it on `Dispatchers.Default` is safe because the GTD refresh only uses suspend Retrofit/Room calls, as the WorkManager sync already does off the main thread. `TrashViewModel` now drops the row and shows the snackbar as soon as the restore returns. The regression test `a restore returns without waiting for the reload of its lists` holds the reload behind a gate and shows the restore returns first.
- CR-2 (GTD mapping untested): fixed. `restoring a task or project reloads the GTD cache, other types do not` covers the `(api, gtd, scope)` constructor for TASK, PROJECT and nine other types. `the injected repository reloads the GTD cache in the background after a task restore` uses the real `@Inject` constructor and verifies one `refreshAll()` call within a timeout.

Acceptance criteria checked against the code:
1. Reachable from More: `CockpitDestination.Trash` sits between Review and Settings with the Delete icon, and `CockpitShell` routes it to `TrashScreen`, which falls back to a placeholder outside a Hilt activity. Shell and destination tests are updated, including open-and-back. Rows show the type icon (`searchTypeStyle`), the type label, the title, the localized deletion date and the days until purge (rounded up, "today" at 0, urgent colour at <= 3 days).
2. Restore and delete forever are per row. Delete forever goes through a confirmation dialog that names the item. A per-item busy guard ignores double taps. A 404 drops the row, and other failures keep it and show a snackbar. A load that started before a change is restarted, so it cannot bring a changed item back. After a TASK/PROJECT restore the GTD cache is refreshed. Other types load from the server when their screen opens.
3. Loading uses a progress bar, or the pull indicator for a pulled refresh. The empty state appears only after a successful load. The offline and failed messages have retry, are disabled while a load runs and keep the last items. The screen reloads on resume. This matches the existing list screens.
4. EN and DE `strings_trash.xml` have the same keys, placeholders and plurals, and a test (`TrashModelsTest`) enforces this.
5. Tests: `TrashRepositoryTest` (paging, page cap, dedup by type+id, mapping, failure mapping, background reload), `TrashViewModelTest` (stale loads, busy guard, 404, snackbars, pulled refresh), `TrashScreenTest`/`TrashRouteTest` (Robolectric Compose UI, German locale) and `TrashModelsTest`.

Other checks: no response bodies, secrets or item contents are logged (`TrashHttpFailure` carries only the status). The new `TrashApi` Hilt provider has no duplicate binding. All changes stay inside the task's `touches`, apart from the shell/destination tests that belong to the touched `ui` code. The server and API contract are unchanged.

Validation note: this reviewer's own run of `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.trash.*'` failed in `:app:kspDebugUnitTestKotlin` with `kotlin/reflect/full/KClasses`. That is a KSP worker/classpath error unrelated to the reviewed sources, most likely from a concurrent Gradle build in the same clone (the test-writer was building at the same time). A rerun was not possible in this session. fixes.md records `./gradlew ktlintCheck testDebugUnitTest lintDebug` as BUILD SUCCESSFUL on the fixed code. The parent worker should confirm with an uncontended run before `done`.
