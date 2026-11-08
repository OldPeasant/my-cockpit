# Fix Report

Date: 2026-11-08

Task: and-task-convert - Android: convert tasks to notes and events

Scope: current task; findings CR-1 to CR-4 of reviews/and-task-convert/review.md (base 654adae4abac0966827d2226695cf876396a77d5)

## Fixed findings

### CR-1 — To calendar puts timed events in the device zone, not the user's synced time zone

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/gtd/task/TaskDetailViewModel.kt`
- `android/app/src/main/java/ch/sonensei/cockpit/gtd/inbox/InboxViewModel.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/task/TaskDetailConvertViewModelTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/inbox/InboxConvertViewModelTest.kt`

Problem:
Both view models took the default start (`ZonedDateTime.now(clock)`) and the request zone (`clock.zone`) from the device clock. The Android event editor and the web use the synced `settings.timeZone`, with the device zone only as a fallback.

Fix:
Both `@Inject` constructors now also take `UserSettingsRepository`, the same way `EventEditorViewModel` does. The internal constructors take a `StateFlow<UserSettings?>` that defaults to `MutableStateFlow(null)`, so the existing test construction sites still compile. A `zone` property returns `settings.value?.timeZone ?: clock.zone`. It is used for `EventPlan.startingAfter(ZonedDateTime.now(clock.withZone(zone)))` and for `plan.request(zone)` (task detail) / `prompt.action(zone)` (Inbox). Hilt resolves the new parameter through constructor injection, so no DI module changed. New tests set the synced zone to America/New_York while the clock is in Europe/Zurich. They assert the default start (05:00 New York instead of 11:00 Zurich) and the request's offset (-04:00) and `timeZone`.

Validation:
- `./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.gtd.task.*' --tests 'ch.sonensei.cockpit.gtd.inbox.*'` — Pass

### CR-2 — An older calendar-list answer can overwrite a newer one in the To calendar dialog

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/gtd/task/TaskDetailViewModel.kt`
- `android/app/src/main/java/ch/sonensei/cockpit/gtd/inbox/InboxViewModel.kt`

Problem:
Each `loadCalendars()` started a new coroutine without cancelling the previous one. A late answer from an earlier dialog or prompt could replace the reopened one's calendars. The regression tests "calendars of an earlier dialog/prompt answering last do not replace those of the reopened one" were failing.

Fix:
Both view models keep the running load in a `calendarsJob: Job?` and cancel it before they start a new one. Both repositories' `calendars()` rethrow `CancellationException`, so a cancelled load never writes state.

Validation:
- `TaskDetailConvertViewModelTest` / `InboxConvertViewModelTest` "calendars of an earlier ... answering last ..." — Pass (they failed before the fix)

### CR-3 — Task detail "To reference" converts at once, without the confirmation the web asks for

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/gtd/task/TaskDetailScreen.kt`
- `android/app/src/main/res/values/strings_task_detail.xml`
- `android/app/src/main/res/values-de/strings_task_detail.xml`
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/task/TaskDetailConvertScreenTest.kt`

Problem:
One tap in the overflow menu created the note and trashed the task, with no confirmation and no Undo. The web asks first (`REFERENCE_CONFIRM`).

Fix:
The menu item now opens an `AlertDialog`. Its state is a `rememberSaveable` flag, the same pattern as the existing Waiting dialog. The dialog has the confirm tag `TaskDetailTags.REFERENCE_CONFIRM`, a "To reference" button and a "Cancel" button. Only the confirm button calls `callbacks.toReference()`. The English text is the web wording: "Move this task to reference? It becomes a note and the task goes to the Trash." The German text follows the web translation, but uses "zur Referenz" instead of the web's "in die Ablage", because the Android German strings say "Referenz" everywhere ("Zu Referenz", "zur Referenz verschoben"). The web's dirty-form variant is not needed: the menu is not shown while the edit form is open. Tests: the menu test now confirms the dialog, and two new Compose tests check that confirming converts once and cancelling converts nothing. `TaskDetailStringsTest` covers the new string in both locales.

Validation:
- `./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.gtd.task.*'` — Pass

### CR-4 — Convert calls duplicated between TaskConverter and TaskDetailRepository

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/gtd/task/TaskConversion.kt`
- `android/app/src/main/java/ch/sonensei/cockpit/gtd/task/TaskDetailRepository.kt`

Problem:
`TaskDetailRepository.convertToNote/convertToEvent` called the convert endpoints, with their If-Match header, separately from `TaskConverter.toNote/toEvent`.

Fix:
`TaskConverter` now has `internal noteResponse(task)` / `eventResponse(task, request)`, which hold the only endpoint and If-Match call, and `toNote/toEvent` are built on them. `TaskDetailRepository` runs these through its existing `call()` error mapping: `call({ converter.noteResponse(task) }) { it!!.toConverted() }`. The repository does not call `converter.toNote/toEvent` directly, because those throw `HttpFailure` from `body()` and drop the error body. That would lose the Problem Details field messages (`TaskResult.Invalid`) that `call()` parses and that `TaskConversionRepositoryTest` checks. `TaskDetailRepository`'s private `etag()` helper stays because complete, move, delete, patch and the checklist calls still use it. No helper became unused.

Validation:
- `TaskConversionRepositoryTest`, `TaskDetailRepositoryTest`, `InboxConversionRepositoryTest` (MockWebServer, real `TaskConverter`) — Pass

## Not fixed

None.

## Validation summary

Focused tests:
- `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.gtd.task.*' --tests 'ch.sonensei.cockpit.gtd.inbox.*'` — Pass (0 failures, including the two CR-2 regression tests that failed before)

Full test suite:
- `cd android && ./gradlew ktlintFormat ktlintCheck testDebugUnitTest lintDebug`: the first run had 1 failure in 3965 tests, `settings/SettingsServerChangeTest` "changing to another server in settings logs out with SERVER_CHANGED and shows the new address". That code is not touched by this fix. The test passed twice on its own (`--tests '*SettingsServerChangeTest' --rerun`), so it looks like a timing flake under full-suite load.
- `cd android && ./gradlew ktlintFormat ktlintCheck testDebugUnitTest lintDebug --rerun-tasks` — BUILD SUCCESSFUL (all tests, ktlint and lint pass)

Lint/type-check/build:
- ktlintCheck and lintDebug — Pass (part of the command above)

`.taskcheck`:
- Not run by the fixer (`sh scripts/check.sh` takes about 5 min and covers all parts). The parent worker runs it before `done`.

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed.
