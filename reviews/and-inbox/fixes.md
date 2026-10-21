# Fix Report

Date: 2026-10-21

Task: and-inbox - Android inbox processing

Scope: current task; approved findings CR-1, CR-2 and CR-4 from `reviews/and-inbox/review.md`. CR-3 was declined by the parent worker. Changes are limited to `android/app/src/main/java/ch/sonensei/cockpit/gtd/inbox`, `android/app/src/test/java/ch/sonensei/cockpit/gtd/inbox` and `android/app/src/main/res/values/strings_inbox.xml`.

## Fixed findings

### CR-1 — A failed action can leave the Inbox read-only with no way to retry

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/gtd/inbox/InboxViewModel.kt`
- `android/app/src/main/java/ch/sonensei/cockpit/gtd/inbox/InboxScreen.kt`
- `android/app/src/main/res/values/strings_inbox.xml`
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/inbox/InboxViewModelTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/inbox/InboxScreenTest.kt`

Problem:
After an action failed with an IOException or a 5xx response, the shared offline flag disabled every action, and nothing on the screen could clear it. The banner text said the screen would recover on its own.

Fix:
`InboxViewModel.retry()` calls `TaskRepository.refresh(TaskView.INBOX)`. If that refresh works, `GtdConnectivity` clears the offline flag. A `retrying` flag in `InboxUiState` blocks a second retry while one runs. The offline banner now has a "Retry" button (tag `inbox-retry`) that calls `InboxCallbacks.retry` and is disabled while a retry runs. The `inbox_offline` text now says "Processing is paused until the server can be reached." instead of promising automatic recovery. New string: `inbox_retry`.
Tests: the view model tests `retry reloads the inbox and a reload that works clears the offline state` (one refresh even after two taps, then offline=false, actionsEnabled=true) and `a retry that fails keeps the offline state and can be repeated`; the screen test `the offline banner offers a retry, disabled while one runs`.

Validation:
- `./gradlew ktlintFormat ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.gtd.inbox.*' lintDebug` — Pass

### CR-2 — A double tap on an action button also runs the action on the next item

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/gtd/inbox/InboxViewModel.kt`
- `android/app/src/main/java/ch/sonensei/cockpit/gtd/inbox/InboxScreen.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/inbox/InboxViewModelTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/inbox/InboxScreenTest.kt`

Problem:
`choose(kind)` and `confirmPrompt()` always acted on whatever item was current. A tap meant for an item that had already left the queue therefore ran on the next item.

Fix:
The signatures are now `choose(itemId: UUID, kind)` and `confirmPrompt(itemId: UUID)`. Both return without doing anything when `itemId` is not the current item. In the screen, the action buttons and the prompt's confirm button pass the id of the task they were composed for. `InboxCallbacks.choose` is now `(UUID, InboxActionKind) -> Unit` and `confirmPrompt` is `(UUID) -> Unit`. No code outside `gtd/inbox` uses these (checked with grep).
Tests: the existing view model tests now pass the item id. New tests: `a second tap for an item that already left the queue does not act on the next item` (two `choose(first.id, TRASH)` calls lead to one `process` call and none for `second`) and `a stale prompt confirmation is dropped`. The screen tests now also check that the buttons pass the current item's id.

Limitation: this drops a tap that arrives after the item left the queue but before the buttons recompose. A second tap that lands after the recomposition, about 16 ms later, carries the new item's id and is not dropped. This is the fix the parent worker approved. Blocking taps for a time after the current item changes would need a time-based guard; that was not added.

Validation:
- `./gradlew ktlintFormat ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.gtd.inbox.*' lintDebug` — Pass

### CR-4 — A project created for an item can be reused under a different title

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/gtd/inbox/InboxRepository.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/inbox/InboxRepositoryTest.kt`

Problem:
`createdProjects[itemId]` was reused for any later "New project" choice on the item, whatever its title. The entry was only removed after a successful move to a project.

Fix:
`createdProjects` now stores the trimmed title together with the project id (`CreatedProject`), and an entry is reused only when the new title matches. Choosing an existing project removes the item's entry. A successful `process` (any action) also removes it. A new title creates a new project and replaces the entry. A project created by an earlier attempt and then abandoned stays on the server, the same as on the web.
Tests: `a retry with another new project title creates that project instead of reusing the first`, `choosing an existing project forgets the project created for the item`, and `processing the item another way forgets the project created for it`. The existing test `to a new project creates it once, also when the move is retried` still passes: the same title, with surrounding spaces, is reused.

Validation:
- `./gradlew ktlintFormat ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.gtd.inbox.*' lintDebug` — Pass

## Not fixed

### CR-3 — Hilt fallback relies on an internal Hilt API and hides wiring errors in production

Status:
Out of scope

Reason:
The parent worker declined it, because the fix would change shell tests outside the task's touches. Not touched.

## Validation summary

Focused tests:
- `cd android && ./gradlew ktlintFormat ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.gtd.inbox.*' lintDebug` — BUILD SUCCESSFUL. InboxActionsTest 10/0 failures, InboxScreenTest 18/0, InboxViewModelTest 24/0, InboxRepositoryTest 26/0. On the first run, `ktlintTestSourceSetCheck` failed with one chain-method-continuation issue in a new test. `ktlintFormat` reformatted it, and the later runs passed.

Full test suite:
- `cd android && ./gradlew testDebugUnitTest` (all Android unit tests) — BUILD SUCCESSFUL

Lint/type-check/build:
- `ktlintCheck` and `lintDebug` — Pass (included in the focused command)

`.taskcheck`:
- Not run (`sh scripts/check.sh` covers server and web too; the parent worker runs it before `done`)

Remaining unresolved findings:
0 (CR-3 was declined by the parent worker)

## Final status

All validated findings fixed and validation passed.

---

# Fix Report (second round)

Date: 2026-10-21

Task: and-inbox - Android inbox processing

Scope: current task; approved finding TW-1 from `reviews/and-inbox/tests.md`. The change is limited to `android/app/src/main/java/ch/sonensei/cockpit/gtd/inbox/InboxRepository.kt`.

## Fixed findings

### TW-1 — An unreadable date in the project answer after the move makes processing fail

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/gtd/inbox/InboxRepository.kt`

Problem:
`makeNextStepIfStalled` runs after the item has already moved to the project. It ignored connection, HTTP, serialization and argument errors, but not `java.time.DateTimeException`, which the generated date parser throws for an unreadable date. Such an answer escaped, so `process` returned `Rejected(null)` although the move had succeeded.

Fix:
Added `catch (_: DateTimeException) {}` to the existing catch chain (plus the `java.time.DateTimeException` import). This matches the set that `GtdConnectivity.refreshing` in `gtd/data/GtdRefresh.kt` catches. The project then stays stalled, as the KDoc already describes for an unreadable answer. No test was changed.

Validation:
- `cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.gtd.inbox.*' lintDebug` — Pass (BUILD SUCCESSFUL). `a project answer with an unreadable date after the move still counts as processed` now passes.

## Not fixed

None.

## Validation summary

Focused tests:
- `cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.gtd.inbox.*' lintDebug` — BUILD SUCCESSFUL. 100 tests, 0 failures, 0 skipped: InboxRepositoryTest 34/34, InboxViewModelTest 28/28, InboxScreenTest 21/21, InboxActionsTest 11/11, InboxRouteTest 4/4, InboxGraphTest 2/2. ktlintCheck and lintDebug passed.

Full test suite:
- Not run in this round (the change only adds one catch clause in the inbox package; the parent worker runs `.taskcheck`)

Lint/type-check/build:
- `ktlintCheck`, `lintDebug` and the Kotlin compile — Pass (included in the focused command)

`.taskcheck`:
- Not run (`sh scripts/check.sh` covers server and web too; the parent worker runs it before `done`)

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed.
