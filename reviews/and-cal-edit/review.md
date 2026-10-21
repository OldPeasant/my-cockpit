# Code Review

Date: 2026-10-21

Task: and-cal-edit - Android event create/edit

Scope: task commits 2688789e (implementation) and c127c7c9 (tests) on top of base 6db1d0fd, limited to `android/` (calendar/edit package, the one-line hook in calendar/agenda/CalendarScreen.kt, res/values/strings_calendar_edit.xml and the calendar.edit tests). I read the files directly because Bash/git was denied in this sub-agent session.

Requirements:
- tasks/and-cal-edit.md acceptance criteria and recorded assumptions (Notes)
- SPEC.md §6.2/§6.3 (scope THIS/FOLLOWING/ALL, optimistic locking, conflict handling), as summarised by the parent and the server contract
- AGENTS.md "Project notes", docs/ARCHITECTURE.md conventions
- api/openapi.yaml contract as implemented by server `CalendarEventService.update/resolve` (PATCH /api/v1/calendar/events/{id}?scope=&occurrence= with If-Match)
- Reference design: web/src/app/features/calendar/edit/ (task web-cal-edit)

Git range:
- Branch: main
- HEAD: c127c7c98857ff6fe5140bef773d195941c6a29d
- Base/start: 6db1d0fd401c70518b56cc0253f96852656bd9ad
- Commits reviewed: 2

Summary: 0 P0, 0 P1, 2 P2, 2 P3

Criterion check:
- AC1 (event form, recurrence presets, THIS/FOLLOWING/ALL prompt): implemented. The form has title, calendar, all-day, start/end date and time, repeat (none/daily/weekly/monthly/yearly plus a kept custom rule), location and description. The scope prompt offers THIS/FOLLOWING/ALL, or only FOLLOWING/ALL when the rrule or calendar changes. Scope/target mapping matches the server `resolve()`: a single event gets no scope; an override gets its own id plus a scope; a generated occurrence gets the master id plus the scope and the original start. If-Match carries the version of the event that holds the occurrence. Only changed fields go into the PATCH. An all-day end is converted to the exclusive end and back. A DST gap is handled by `ZonedDateTime.of`. 409 and 412 both show a conflict with a Reload action and block saving. Form state is kept in a ViewModel, so it survives configuration changes.
- AC2 (calendar.edit unit tests incl. Robolectric/Compose): the tests cover form validation (EventFormTest, EventEditorViewModelTest, EventEditorScreenTest) and the scope prompt (VM and Compose). The parent reports 36/36 passing at c127c7c9. I could not run Gradle here, so I did not run the tests myself.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | A generated occurrence is not read again on open, so stale times are edited under a fresh If-Match**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/calendar/edit/EventEditorViewModel.kt:212` (load), `:288`/`:307`/`:311` (editForm uses the view's occurrence times), `:236` (version)
    - Category: Correctness
    - Problem: For a generated occurrence, `load()` fetches the series master again and stores its *current* version as the If-Match value. The form's times still come from the `Occurrence` the calendar view held when it was tapped. Since then, the series may have been moved elsewhere, or this occurrence may have become an override (edited on the web or on another device). In that case the form shows outdated times, and for an override also outdated content (the master's title instead of the override's). The If-Match check still passes because the master's version was just reloaded. If the occurrence no longer exists, the server answers 404 ("The series has no such occurrence"). That maps to the generic `FAILED` message with no Reload action, and Reload would load the same stale occurrence anyway. The web reference closes exactly this gap: `event-dialog.ts` `load()` calls `events.reread(occurrence)` for generated occurrences and closes with `'changed'` when the occurrence is gone.
    - Why it matters: SPEC §6.2's optimistic locking is meant to stop a user from overwriting changes made elsewhere without seeing them. Here the user edits values they never saw as current, and the change is not detected.
    - Suggested fix: Mirror the web. When the occurrence is generated, read it again from the occurrences endpoint (a small range around `occurrence.occurrence`, filtered by `seriesId`/`occurrence`) before loading. If it is now an override, edit that event. If it is gone, close the editor (the view refreshes on resume) or show a "changed elsewhere" message. Add a VM test for both cases.

- [ ] **CR-2 | Closing or going back while a save runs cancels it silently, though the server may already have committed it**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/calendar/edit/EventEditorViewModel.kt:180` (close cancels `job`, resets `newId`), `EventEditorScreen.kt:102` (BackHandler) and `:106` (Close button, always enabled)
    - Category: Correctness
    - Problem: During `saving`, back or Close calls `close()`. That cancels the coroutine and resets the state, including `newId`. The request may already have reached the server, so the event may have been created or changed even though the editor discarded it without a word. If the user then re-creates the event, it gets a new id, so the idempotent-create protection is lost and a duplicate is possible. The web reference blocks this deliberately: `event-dialog.ts` `run()` sets `ref.disableClose = true` ("The change may be committed even if its answer never arrives here"). Related: `CalendarResult.Offline` also covers 5xx and IO failures after the request was sent, yet `calendar_edit_offline` says "Nothing was saved".
    - Why it matters: The checklist case "a cancelled write may already have been committed by the server": the user can create duplicate events or be misled about whether their change was saved.
    - Suggested fix: While `saving` is true, ignore close/back, or disable them (keep the BackHandler but no-op). Word the offline save message neutrally ("may not have been saved"). Add a VM test that `close()` during `saving` neither cancels the request nor resets the state.

## P3 Low

- [ ] **CR-3 | The custom repeat choice disappears once another preset is picked**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/calendar/edit/EventEditorViewModel.kt:72`
    - Category: Correctness
    - Problem: `repeatChoices` adds `Repeat.CUSTOM` only while `form.repeat == CUSTOM`. If the user picks a preset on a series with a custom rule (for example `FREQ=WEEKLY;BYDAY=MO,WE`), the "Custom (...)" option is gone, so the only way back to the original rule is to close and discard every edit. The web shows the custom option whenever a custom rule exists (`event-dialog.html`, `@if (customRule())`). `form.rule` cannot serve as the condition, because `editForm` also fills it for preset rules.
    - Why it matters: A selection that cannot be undone in a form that edits a whole series.
    - Suggested fix: Base the condition on the loaded form (initial `repeat == CUSTOM`). Expose it in the UI state, for example as `customRule: String?`.

- [ ] **CR-4 | `editForm` paths for all-day occurrences and overrides have no tests**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/calendar/edit/EventEditorViewModel.kt:281-319`; tests in `android/app/src/test/java/ch/sonensei/cockpit/calendar/edit/EventEditorViewModelTest.kt`
    - Category: Tests
    - Problem: The VM tests cover timed single events and timed generated occurrences only. These paths are not exercised: converting an all-day exclusive end date to the inclusive last day (`after.minusDays(1)`, `maxOf(..., first)`), loading an override (fetching the master for its rrule, using the override's own times and version), a failing master load, and a fallback zone when `timeZone` is invalid. `createBody` is tested for the +1 day conversion; the reverse is not.
    - Why it matters: The all-day off-by-one and the choice between override and master are exactly where regressions would silently shift dates or send the wrong If-Match.
    - Suggested fix: Add `editForm` unit tests for an all-day single event, an all-day generated occurrence and an override with its master. Add a VM test where the master load fails and LOAD_FAILED/LOAD_OFFLINE appears.

## Review conclusion

Material issues found; fix before completing the task.

The acceptance criteria are met. CR-1 and CR-2 are P2 correctness and resilience gaps where the Android client falls short of the web reference's documented protections. The parent worker decides whether to fix them in this task or record them as follow-ups.
