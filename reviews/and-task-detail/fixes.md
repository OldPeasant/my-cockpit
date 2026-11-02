# Fix Report

Date: 2026-11-02

Task: and-task-detail - Android: task detail screen with editing

Scope: current task; findings CR-1, CR-3 / TW-1, CR-4, CR-5 from reviews/and-task-detail/review.md and reviews/and-task-detail/tests.md, as assigned by the parent worker (base 305558bcc69b44c40f9b91a010cc944ae849c286).

## Fixed findings

### CR-3 / TW-1 - The due time in the PATCH body is formatted with the default locale

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/gtd/task/TaskDetailViewModel.kt`

Problem:
`TaskDraft.patch` built `dueTime` with `"%02d:%02d:00".format(...)`, which uses the default locale, so devices with non-Latin digits (e.g. fa-IR) sent a value like "۰۹:۰۵:00" that the server cannot parse.

Fix:
Format with `Locale.ROOT` (`"%02d:%02d:00".format(Locale.ROOT, hour, minute)`), always "HH:mm:00" with ASCII digits.

Validation:
- `TaskDraftTest."the due time is sent with ASCII digits whatever the device language"` (existing failing test, unchanged) - Not run here (Gradle denied to the fixer); the parent must run it.

### CR-1 - An offline reload replaces the fresher server copy with the older list snapshot

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/gtd/task/TaskDetailViewModel.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/task/TaskDetailViewModelEdgeTest.kt`

Problem:
In `load()`, the Offline branch preferred `source.cached(id)` over a detail already loaded from the server, so a reload without answer after a successful change (complete, move, conflict reload, undo) showed the pre-change list copy without checklist and links.

Fix:
The Offline branch now keeps `it.detail` when one is loaded (only `offline = true`), and asks `source.cached(id)` only when nothing is loaded yet; `cachedAt` is taken from the cached copy only in that case.

Validation:
- New test `TaskDetailViewModelEdgeTest."a reload without an answer after a change keeps the loaded task, not the older list copy"` (complete succeeds, reload returns Offline, a different cached copy exists; the shown detail stays the loaded one with its checklist, message Completed, offline true) - Not run here (Gradle denied).

### CR-4 - A Logbook task opened offline shows as an open task

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/gtd/task/TaskNavigation.kt`
- `android/app/src/main/java/ch/sonensei/cockpit/gtd/task/TaskDetailRepository.kt`
- `android/app/src/main/java/ch/sonensei/cockpit/gtd/task/TaskDetailViewModel.kt`
- `android/app/src/main/java/ch/sonensei/cockpit/ui/CockpitShell.kt`
- `android/app/src/main/java/ch/sonensei/cockpit/gtd/lists/LogbookScreen.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/task/TaskDetailContentTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/task/TaskDetailRepositoryTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/task/TaskDetailViewModelEdgeTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/task/TaskDetailScreenTest.kt` (lambda arity only)
- `android/app/src/test/java/ch/sonensei/cockpit/dashboard/DashboardTaskOpenTest.kt` (lambda arity only)

Problem:
The Logbook passed only the `GtdTask` (no completion field) as snapshot, so offline the detail built `TaskDetail(task)` with `completedAt = null` and showed a completed task as open.

Fix:
- `CachedTask` gets an optional `completedAt` (default `null`).
- `TaskOpener`'s single abstract method now takes `completedAt: Instant?`; a non-abstract two-argument `open(id, snapshot)` overload forwards `null`, so the existing callers (TaskListUi, Dashboard) are unchanged.
- `TaskSnapshots` stores `CachedTask` entries: `put(task, completedAt = null)`, `get(id)` still returns the `GtdTask`, new `cached(id)` returns the entry with its completion time.
- The shell's opener stores `completedAt` with the snapshot; the Logbook row passes `done.completedAt`.
- `TaskDetailRepository.cached()` returns the snapshot entry; the view model's offline `TaskDetail` uses `completedAt = copy.completedAt`.
- The three test `TaskOpener { id, snapshot -> ... }` lambdas get the third parameter (`_`); assertions unchanged except the Logbook test, which now also checks the completion time is passed.

Validation:
- `TaskDetailContentTest."in the shell a Logbook row opens the task detail instead of the Logbook's dialog"` (now asserts `Triple(seeds.id, seeds, logged.completedAt)`) - Not run here.
- New `TaskDetailRepositoryTest."a snapshot from the Logbook keeps the completion time for the offline copy"` - Not run here.
- New `TaskDetailViewModelEdgeTest."offline a task opened from the Logbook shows as completed"` - Not run here.

### CR-5 - A failed links request can mark all GTD screens as offline

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/gtd/task/TaskDetailRepository.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/task/TaskDetailRepositoryTest.kt`

Problem:
The optional links request went through `call()`, i.e. `connectivity.refreshing`, so a 5xx or IOException on `/api/v1/links` could set the shared GTD offline flag after the task itself loaded.

Fix:
New private `links(id)` in `TaskDetailRepository`: calls `writeApi.listTaskLinks(id)` inside `runCatching`, maps a successful body to the other ends, and returns an empty list on any failure, without touching `GtdConnectivity`. `load()` uses it instead of `call()`. (When the task GET fails, the links deferred is cancelled and its result discarded, as before.)

Validation:
- New `TaskDetailRepositoryTest."links that fail on the server side leave the shared offline state alone"` (task and checklist 200, links 503: load is Ok with no links and `connectivity.offline` stays false) - Not run here.
- Existing `TaskDetailRepositoryTest."the task still shows when its links cannot be loaded"` and `"an unreachable server is offline for the screen and the lists"` still apply unchanged.

## Not fixed

None of the assigned findings. CR-2 and CR-6 were not assigned to the fixer (the test-writer's TaskDetailRepositoryTest and DashboardTaskOpenTest / TaskDetailContentTest address them); the parent decides their disposition.

## Validation summary

Focused tests:
- Not run: the fixer's single attempt `android/gradlew -p android ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.gtd.task.*' --tests 'ch.sonensei.cockpit.dashboard.DashboardTaskOpenTest' --console=plain` was refused by the permission policy. The parent must run: `cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.gtd.task.*' --tests 'ch.sonensei.cockpit.dashboard.DashboardTaskOpenTest'` - classes affected: TaskDraftTest, TaskDetailViewModelEdgeTest, TaskDetailViewModelTest, TaskDetailRepositoryTest, TaskDetailContentTest, TaskDetailScreenTest, DashboardTaskOpenTest. Also any Logbook test class under `ch.sonensei.cockpit.gtd.lists` (LogbookScreen changed).

Full test suite:
- Not run (Gradle denied). Parent: `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug`.

Lint/type-check/build:
- Not run (Gradle denied). Lines kept under 140 columns; no suppressions added.

`.taskcheck`:
- Not run (runs Gradle; parent runs `sh scripts/check.sh`).

Remaining unresolved findings:
0 (of the 4 assigned)

## Final status

All assigned findings fixed in code and covered by tests; validation not run yet (Gradle is denied to the fixer). The parent must run the commands above before treating this as "validation passed".
