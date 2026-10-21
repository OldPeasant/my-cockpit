# Code Review

Date: 2026-10-21

Task: and-cal-edit - Android event create/edit

Scope: re-review of the final code. Task commits d82af939 (implementation), 54268ba5 (tests) and 746e3979 (fixes for CR-1..CR-3 plus the test-writer's tests) on top of base a75919af. The review covers `android/app/src/main/java/ch/sonensei/cockpit/calendar/edit/`, its tests in `android/app/src/test/java/ch/sonensei/cockpit/calendar/edit/`, `android/app/src/main/res/values/strings_calendar_edit.xml` and the hook in `calendar/agenda/CalendarScreen.kt`. Committed changes only; the working tree was clean.

Requirements:
- tasks/and-cal-edit.md: acceptance criteria and the recorded assumptions (Notes)
- SPEC.md §6.2/§6.3 (THIS/FOLLOWING/ALL scopes, optimistic locking, conflicts), as reflected in api/openapi.yaml (`POST /api/v1/calendar/events`, `PATCH /api/v1/calendar/events/{id}`, `GET /api/v1/calendar/events` occurrences over [from, to))
- AGENTS.md "Project notes", docs/ARCHITECTURE.md conventions
- Reference implementation: web/src/app/features/calendar/edit/ (`event-edit.service.ts` `reread`/`rangeAround`, `event-dialog.ts` `load`/`run`)
- Previous review (CR-1..CR-4) and reviews/and-cal-edit/fixes.md

Git range:
- Branch: main
- HEAD: 746e397974c375086c2e9d9945e72aa56ace0684
- Base/start: a75919afc79e9d4d5e8316d864477b49d5f3c5c6
- Commits reviewed: 3

Summary: 0 P0, 0 P1, 0 P2, 1 P3

## Status of the earlier findings

- **CR-1 (generated occurrence not re-read): fixed.** `EventEditRepository.reread` queries the occurrence list of the occurrence's calendar over the same window as the web: ±1 day around an instant, or from the day before to two days after an all-day date, in UTC. It matches on `seriesId` and the original-start string `occurrence`. Both values come from the same endpoint and the same `toOccurrence()` mapping as the agenda, so the string comparison is consistent. A cancelled match counts as gone. The list endpoint has no pagination, so a busy window cannot hide the match. `load()` re-reads only generated occurrences. It stores the fresh occurrence as the edit target, so a later Reload of an occurrence that is now an override loads that override directly, and its If-Match is the override's own version. If the occurrence is gone, the editor shows `GONE`: no form, no Reload, saving blocked. After Close, `CalendarRoute`'s `LifecycleResumeEffect` refreshes the views, which matches the web's `'changed'` result. A re-read failure maps to LOAD_OFFLINE/LOAD_FAILED with Retry. A 404 on save maps to GONE. For create, the contract has no 404 (an unknown calendar is 400), so the new mapping cannot misfire there. Tests: repository (request path, calendarIds, range, match among other series and occurrences, gone, cancelled, 5xx, all-day range); VM (moved, became an override, gone, re-read failure then retry, save 404); screen (GONE).
- **CR-2 (close while saving): fixed.** `close()` returns early while `saving`. A successful save goes through the private `reset()`. `BackHandler` stays registered but does nothing while saving, so back does not leave the Calendar screen. The close icon is disabled while saving. `newId` is kept across a failed attempt, so a retry reuses the id and stays idempotent. `reset()` cancels `job` from inside the job itself, as its last action, which is harmless. The OFFLINE wording no longer says "Nothing was saved". Tests cover a close during a save (the answer arrives and a retry keeps the id), a close after a failure, and the disabled close in the screen.
- **CR-3 (custom repeat choice lost): fixed.** `repeatChoices` now keys off `repeatOf(form.rule)`. `rule` holds the rule as loaded, and the UI never changes it. Picking CUSTOM again gives the same `rrule`, so `patchBody` sends nothing for it. A preset rule offers no custom choice. The VM and screen tests cover this.
- **CR-4 (editForm paths untested): resolved by tests.** `EventEditorViewModelEdgeCasesTest` covers:
  - an all-day generated occurrence;
  - a single all-day event, plus its inclusive/exclusive round trip;
  - a one-day all-day event with a missing end or an end that is not after the start;
  - an override with its master, using the override's own version;
  - an override that takes its zone from the series;
  - an unknown zone;
  - a failing master load with retry.

Criterion check:
- AC1 (event form, recurrence presets, THIS/FOLLOWING/ALL prompt): implemented as described in the first review. The fixes did not change the scope and target mapping (`EventEditRepository.update`), and the prompt still offers only FOLLOWING/ALL for a new rule or calendar.
- AC2 (`./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.calendar.edit.*'`, covering validation and the scope prompt, with Robolectric/Compose screen tests): the tests exist and assert behavior (EventFormTest, the two VM tests, EventEditRepositoryTest, EventEditorScreenTest, CalendarWithEditorTest). The parent reports `./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.calendar.*'` on HEAD 746e3979: BUILD SUCCESSFUL, 178 tests, 0 failures. I could not run Gradle in this session, so I did not reproduce that result.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-5 | A refused create (409) shows "changed elsewhere" with a Reload button that does nothing**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/calendar/edit/EventEditorViewModel.kt:226` (409 maps to CONFLICT for creates too), `:242` (`load()` returns at once when there is no occurrence), `EventEditorScreen.kt:147` (Reload offered for CONFLICT), `:85` (`canSave` false under CONFLICT)
    - Category: Correctness
    - Problem: For `POST /api/v1/calendar/events`, the contract answers 409 when "the id belongs to an event in the Trash, or the calendar is read-only or cannot be changed". `send()` maps it to `CONFLICT`, so a new event shows "This event was changed elsewhere. Reload it…". Reload calls `load()`, which does nothing for a new event (`occurrence` is null). `canSave` stays false under CONFLICT, and `update()` ignores edits, so the user cannot pick another calendar and retry. The only way out is Close, which loses the form.
    - Why it matters: A rare path (for example, a synced calendar turned read-only while the form was open), but it is a dead end with a misleading message and a button that does nothing.
    - Suggested fix: When `creating`, map a 409 to `FAILED`. The form stays editable, so the user can choose another calendar and save again. Alternatively, add a create-specific message. Add a VM test for a create answered with 409.

## Review conclusion

No material issues found.

The CR-1..CR-3 fixes are correct and complete, and I found no regressions in the occurrence re-read (range and matching), GONE handling, close-while-saving or the custom repeat choice. Tests now cover CR-4. CR-5 is a low-priority edge case the parent may fix or record as a follow-up.
