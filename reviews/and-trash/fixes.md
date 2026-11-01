# Fix Report

Date: 2026-11-01

Task: and-trash - Android: Trash (list, restore, delete forever)

Scope: findings CR-1 and CR-2 of `reviews/and-trash/review.md` (base a74408c9f, task commits fed7ae1eb, e7a8e2237, afff18baf).

## Fixed findings

### CR-1 — A GTD restore is confirmed only after the full GTD cache refresh

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/trash/TrashRepository.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/trash/TrashRepositoryTest.kt`

Problem:
`restore()` awaited `lists.reload(type)` (for TASK/PROJECT `GtdSync.refreshAll()`, five sequential requests) before returning, so the row stayed busy, stayed in the list and the "Restored" snackbar was delayed; leaving the screen cancelled the reload with the view model scope.

Fix:
`TrashRepository` (a `@Singleton`) now owns an app-lifetime `CoroutineScope(SupervisorJob() + Dispatchers.Default)`, following the existing pattern of `AuthRepository`/`PushRegistrar` (scope created in the `@Inject` constructor, passed explicitly via an internal constructor for tests). `restore()` returns its result right after the server call succeeds and launches the reload in that scope, so it neither delays the row/snackbar nor stops when the screen is left. A failing reload is still swallowed by `load` (the item then shows up at the next sync). `TrashViewModel` is unchanged. Tests pass an Unconfined scope; a new regression test (`a restore returns without waiting for the reload of its lists`) gates the reload and checks that `restore()` returns Ok before the reload finishes, and that the reload completes afterwards.

Validation:
- `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.trash.*'` — Pass (65 tests, 0 failures)

### CR-2 — The production restore-reload binding is not tested

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/trash/TrashRepository.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/trash/TrashRepositoryTest.kt`

Problem:
The `@Inject` constructor's mapping (`if (type in GTD_TYPES) gtd.refreshAll()`) was not covered by tests.

Fix:
The test-writer had already added `restoring a task or project reloads the GTD cache, other types do not` (commit afff18baf), which builds the repository from a mocked `GtdSync`. With CR-1 the GTD mapping moved into an internal `(api, gtd, scope)` constructor that the `@Inject` constructor delegates to; that test now uses it with a synchronous test scope and checks NOTE and eight other non-GTD types do not call `refreshAll()`, TASK and PROJECT each call it exactly once. A new test `the injected repository reloads the GTD cache in the background after a task restore` uses the real `@Inject` constructor (its own background scope) and verifies `refreshAll()` is called exactly once (`coVerify(exactly = 1, timeout = 5_000)`). The existing `purging a task does not reload the GTD cache` test also uses the `@Inject` constructor.

Validation:
- `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.trash.*'` — Pass

## Not fixed

None.

## Validation summary

Focused tests:
- `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.trash.*'` — Pass (TrashRepositoryTest 18, TrashViewModelTest 17, TrashScreenTest 19, TrashRouteTest 6, TrashModelsTest 5; 0 failures)

Full test suite:
- `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` — BUILD SUCCESSFUL (2545 unit tests, 0 failures, 0 errors). A first attempt failed while another Gradle build ran concurrently in the same clone; the rerun without contention passed.

Lint/type-check/build:
- `./gradlew ktlintFormat` then `ktlintCheck` and `lintDebug` — Pass

`.taskcheck`:
- Not run (the parent worker runs `sh scripts/check.sh`; only Android files changed and its Android part passed above)

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed.
