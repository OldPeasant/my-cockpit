# Code Review

Date: 2026-11-08

Task: and-task-convert - Android: convert tasks to notes and events

Scope: commits of task and-task-convert on top of base 57d11505580a0a3c74610d267a4be32375de69a5 (implementation b8ca4b30a, tests cb4ecedea); uncommitted test changes of the parallel test-writer excluded

Requirements:
- tasks/and-task-convert.md (acceptance criteria and recorded assumptions)
- SPEC.md §5.3 (Inbox processing: To reference / To calendar), API list (`POST /api/v1/tasks/{id}/convert-to-note | /convert-to-event`)
- docs/PARITY.md (owner decisions, GTD gap row for and-task-convert)
- docs/ARCHITECTURE.md, AGENTS.md project notes
- api/openapi.yaml (`convertTaskToNote`, `convertTaskToEvent`, `ConvertToEventRequest`)
- Web reference: web/src/app/features/gtd/inbox/inbox-page.ts, task-detail/task-detail.ts, task-detail/to-calendar-dialog.ts
- Existing Android conventions: calendar/edit/EventEditorViewModel.kt (event zone), ui/CockpitShell.kt navigation

Git range:
- Branch: main
- HEAD: cb4ecedea
- Base/start: 57d11505580a0a3c74610d267a4be32375de69a5
- Commits reviewed: 2

Summary: 0 P0, 0 P1, 1 P2, 3 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | To calendar puts timed events in the device zone, not the user's synced time zone**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/gtd/task/TaskDetailViewModel.kt:614` and `:629`; `android/app/src/main/java/ch/sonensei/cockpit/gtd/inbox/InboxViewModel.kt:234` and `:271`
    - Category: Integration
    - Problem: Both view models build the default start (`ZonedDateTime.now(clock)`) and the request (`plan.request(clock.zone)`, which also sends `timeZone = zone.id`) from `Clock.systemDefaultZone()`. The Android event editor already uses the synced setting (`EventEditorViewModel.kt:157`: `settings.value?.timeZone ?: clock.zone`, via `UserSettingsRepository`), and the web To calendar dialog uses `settings.timeZone` with the browser zone only as a fallback. docs/PARITY.md decision 2 says Android uses the synced settings (time zone) even though it does not edit them. The task's assumption ("timed events in the device zone") records the choice but does not reconcile it with that decision or the existing Android convention.
    - Why it matters: When the device zone differs from the account zone (travel, misconfigured device), an event created from a task gets a different zone and wall-clock time than an event created in the Android calendar editor or on the web from the same input. The conversion cannot be undone in place, because the task is already in the Trash.
    - Suggested fix: Inject `UserSettingsRepository` (as `EventEditorViewModel` does) and use `settings.timeZone ?: clock.zone` both for `EventPlan.startingAfter` and for `EventPlan.request`. Add a test where the synced zone differs from the clock zone.

## P3 Low

- [ ] **CR-2 | An older calendar-list answer can overwrite a newer one in the To calendar dialog**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/gtd/task/TaskDetailViewModel.kt:633`; `android/app/src/main/java/ch/sonensei/cockpit/gtd/inbox/InboxViewModel.kt:243`
    - Category: Correctness
    - Problem: `loadCalendars()` starts a new coroutine each time and applies its result to whatever dialog/prompt is open, with no request identity and no cancellation of the previous load. If the user opens the dialog, cancels it and opens it again (or opens it for the next Inbox item of the same id path) while the first request is still running, a late failure of the first request replaces the second request's success with "The calendars could not be loaded".
    - Why it matters: The user sees a spurious error, although Retry recovers it. This is the out-of-order async pattern the project's review checklist calls out.
    - Suggested fix: Keep the `Job` of the running load and cancel it before starting a new one (or tag the request with a sequence number and drop stale results).

- [ ] **CR-3 | Task detail "To reference" converts at once, without the confirmation the web asks for**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/gtd/task/TaskDetailScreen.kt:514` (menu item calling `callbacks.toReference()` directly) and `TaskDetailViewModel.kt:608`
    - Category: Correctness
    - Problem: The web task detail confirms before "To reference" (`task-detail.ts:407`, `REFERENCE_CONFIRM`). On Android one tap in the overflow menu creates the note and trashes the task. Unlike Delete on the same screen, the snackbar offers no Undo.
    - Why it matters: An accidental tap is hard to reverse: the user has to restore the task from the Trash and delete the created note separately. The behaviour also differs from the web for the same action.
    - Suggested fix: Show a short confirmation dialog (reuse the web wording in both locales), or record an assumption explaining why Android skips it.

- [ ] **CR-4 | Convert calls duplicated between TaskConverter and TaskDetailRepository**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/gtd/task/TaskDetailRepository.kt:368-373`; `android/app/src/main/java/ch/sonensei/cockpit/gtd/task/TaskConversion.kt:101-106`
    - Category: Quality
    - Problem: `TaskConverter.toNote/toEvent` (used by the Inbox) and `TaskDetailRepository.convertToNote/convertToEvent` call the same endpoints with their own `etag()` helpers. `TaskDetailRepository` injects `TaskConverter` but uses it only for `calendars()`.
    - Why it matters: Changes to the conversion request, for example the If-Match handling or how the response maps to `ConvertedItem`, have to be made in two places.
    - Suggested fix: Have `TaskDetailRepository` wrap `converter.toNote/toEvent` in its existing error mapping (as it does for `calendars()`), or document why the two must stay separate.

## Review conclusion

Material issues found; fix before completing the task.

All five acceptance criteria are implemented in the code:
- Inbox processing has "To reference" and "To calendar". The dialog offers the calendar, start, duration and all-day.
- The task detail menu has both actions for open tasks.
- After converting, a snackbar with "Open" appears. A note opens in its editor; an event opens the Calendar tab.
- The strings are in English and German.
- The tests cover the repository, view model, screen and shell opener.

The requests match `ConvertToEventRequest`: the all-day end date is exclusive, and timed requests carry start, end and `timeZone`. If-Match uses the strong ETag format. CR-1 is the only finding that should be fixed or explicitly confirmed by the owner before `done`. CR-2 to CR-4 are optional cleanups.

The criteria have no verification evidence recorded yet (`tasks.py verify and-task-convert` shows all five unticked). Criterion 5 (gradle `ktlintCheck testDebugUnitTest lintDebug` and `scripts/check.sh`) was not run in this read-only review, so the parent must run it and record the result.
