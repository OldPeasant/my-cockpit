# Code Review

Date: 2026-10-21

Task: and-cal-edit - Android event create/edit

Scope: final review of the whole task, base 384c5efc..HEAD bb5b6544 (task commits 5ef72d65, d4fad91e, f83a0341, 7b78b549, bb5b6544). The earlier rounds reviewed the code up to 7b78b549. This round checks the CR-5 fix (bb5b6544), the test-only commit 7b78b549, and looks for regressions across the whole task. It covers `android/app/src/main/java/ch/sonensei/cockpit/calendar/edit/`, its tests in `android/app/src/test/java/ch/sonensei/cockpit/calendar/edit/`, `android/app/src/main/res/values/strings_calendar_edit.xml` and the hook in `calendar/agenda/CalendarScreen.kt`. Committed changes only. The working tree was clean.

Requirements:
- tasks/and-cal-edit.md: acceptance criteria and the recorded assumptions (Notes)
- SPEC.md §6.2/§6.3 (THIS/FOLLOWING/ALL scopes, optimistic locking, conflicts), and api/openapi.yaml (`POST /api/v1/calendar/events`, `PATCH /api/v1/calendar/events/{id}`, `GET /api/v1/calendar/events`)
- Server create semantics: `server/.../calendar/api/CalendarEventService.kt` (`create`, `existingCreate`), `CalendarEventController.kt` (`create`), `CalendarService.requireWritable`, `sync/CalendarWriteThrough.kt`, and the provider adapters' `createEvent`
- AGENTS.md "Project notes", docs/ARCHITECTURE.md conventions
- The previous review rounds (CR-1..CR-5) and reviews/and-cal-edit/fixes.md

Git range:
- Branch: main
- HEAD: bb5b65443410faa9108ae741e7c5c29833aca434
- Base/start: 384c5efc10df8d68b9bad30f76a154388de93b2d
- Commits reviewed: 5

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## Status of the earlier findings

- **CR-1 (generated occurrence not re-read): fixed.** Verified in the previous round. Nothing in bb5b6544 touches `load()`, `reread` or GONE handling for edits.
- **CR-2 (close while saving): fixed.** `close()` still returns early while `saving`, and `reset()` still clears `newId` only after a successful save or a close.
- **CR-3 (custom repeat choice lost): fixed.** Unchanged since the previous round.
- **CR-4 (editForm paths untested): resolved by tests.** 7b78b549 adds the single all-day event round trip (inclusive in the form, exclusive on the wire), the one-day all-day event with a missing end or an end that is not after the start, and an override that takes its zone from the series. The tests assert behaviour, use fictitious data and do not touch production code.
- **CR-5 (refused create shows CONFLICT with a Reload that does nothing): fixed.** Verified in `EventEditorViewModel.send` (`EventEditorViewModel.kt:216-244`):
  - `creating` is read from `occurrence == null` before the request starts. `create()` sets `occurrence = null` and `edit()`/`load()` set it to the edited occurrence, so the flag reliably tells a create from an edit. While a save runs, `close()` is ignored and the calendar views (FAB, occurrence tap) are hidden behind the editor, so `occurrence` cannot change before the answer arrives.
  - When a create fails, the result is always `FAILED`. `canSave` stays true (it blocks only CONFLICT/GONE), so `update()` accepts edits and the user can pick another writable calendar (`targetCalendars` drops read-only ones) and save again.
  - Edits are unchanged: the `when` falls through to the old mapping (409/412 give CONFLICT, 404 gives GONE, everything else FAILED). The existing tests "a 409 is a conflict too", the 412 conflict test and "a save answered with 404 says the event is gone" are unchanged, and the parent reports they pass.
  - **No duplicates from the regenerated id.** I checked the server path. `CalendarEventService.create` calls `existingCreate(id)` first. An existing live event with that id is returned with 200 before any writability check, so a 409 can never hide a live event with the id. The other 409 sources on create are all thrown inside the same transaction, before or in place of the insert, and roll it back:
    - `existingCreate`: the id is in the Trash;
    - `requireWritable`: the calendar is read-only or not supported;
    - `CalendarWriteThrough.write`: `registry.find`, or the calendar is "not synced yet".

    The write-through runs in `beforeCommit`, so any 409 it throws rolls the local insert back. Its 409s are thrown before the provider call. `RemoteEventChangedException` cannot happen for a plain create: CalDAV, Google and Microsoft each handle an already-existing resource on create themselves. So after a 409 no live event has the old id, and a new UUIDv7 cannot create a second live copy. One case is worth knowing about. An earlier OFFLINE attempt may have committed, the user then trashes that event elsewhere, and the retry gets a 409. A new event is then created next to the trashed one. That follows from the user's own deliberate Save and is not a live duplicate.
  - Every other failure keeps the id: OFFLINE (including 5xx), other 4xx, and `Failed(null)`, which the repository uses for parse and IO-shaped failures. Idempotent retries after a lost answer therefore still work.
  - Tests: "a refused create (409) is a failure, the form stays editable and the retry uses a new id" checks FAILED, `canSave`, a calendar change that is actually sent, and a different `id` on the retry. "other refused creates (412, 404, 422) ... keep the id" checks one id across 4 requests. The existing screen test "a failed save says so and offers no reload, saving stays possible" covers how FAILED renders.

Criterion check:
- AC1 (event form, recurrence presets, THIS/FOLLOWING/ALL prompt): implemented. The scope prompt (`scopesFor`, only FOLLOWING/ALL for a new rule) and the scope and target mapping in `EventEditRepository.update` are unchanged by the last two commits.
- AC2 (`./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.calendar.edit.*'`, covering validation and the scope prompt, with Robolectric/Compose screen tests): the tests exist and assert behaviour (EventFormTest, EventEditorViewModelTest, EventEditorViewModelEdgeCasesTest, EventEditRepositoryTest, EventEditorScreenTest, CalendarWithEditorTest). The parent reports this result on HEAD bb5b6544: `cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.calendar.*' lintDebug` -> BUILD SUCCESSFUL, 183 tests, 0 failures. I did not run Gradle in this review session, so I did not reproduce it.

Note (not a finding): the task assumption "409 and 412 both show 'changed elsewhere' with Reload" now applies to edits only. A refused create shows "could not be saved", and only a 409 gets a fresh id. The parent may want to record that with `tasks.py assume`, so a human reviewing assumptions sees the create behaviour.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [x] **CR-5 | A refused create (409) shows "changed elsewhere" with a Reload button that does nothing** (fixed in bb5b6544, verified above)
    - File: `android/app/src/main/java/ch/sonensei/cockpit/calendar/edit/EventEditorViewModel.kt:216`
    - Category: Correctness
    - Problem: a 409 on create mapped to CONFLICT. That blocked saving, and Reload did nothing for a new event.
    - Why it matters: the user hit a dead end and lost the form.
    - Suggested fix: applied. A failed create is FAILED, the form stays editable, and only a 409 gets a fresh client id.

## Review conclusion

No material issues found.

The CR-5 fix is correct. A regenerated id after a 409 cannot produce a live duplicate, because the server answers a known live id with 200 before any check that could return 409. Edit conflicts (409/412 to CONFLICT, 404 to GONE) behave as before. The test-only commit adds meaningful coverage. I found no regressions in the rest of the task.
