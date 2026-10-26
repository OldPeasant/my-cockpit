# Code Review

Date: 2026-10-26

Task: web-and-birthday-occurrences - Clients: open birthday (virtual) occurrences read-only

Scope: `git diff e15d16d3330eb4c686125997e74eb5cbb5d0476f..c63c35ae429f8b6a67c391a5350397b0d007d018 -- web android` (commits 5bfafbc66 web, 865d998e4 android, 41d49ccdc tests, c63c35ae4 BirthdayContactTest). Committed changes only.

Requirements:
- tasks/web-and-birthday-occurrences.md (acceptance criteria, notes, both recorded assumptions)
- SPEC.md section 8.3 (virtual Birthdays calendar), api/openapi.yaml `OccurrenceResponse` / `CalendarResponse`
- AGENTS.md project notes, docs/ARCHITECTURE.md conventions
- Parent instructions (scope, validation results, earlier review CR-1/CR-2)

Git range:
- Branch: main
- HEAD: c63c35ae429f8b6a67c391a5350397b0d007d018
- Base/start: e15d16d3330eb4c686125997e74eb5cbb5d0476f
- Commits reviewed: 4

Summary: 0 P0, 0 P1, 0 P2, 0 P3

This review was static: sub-agents cannot run Gradle here, so I ran no build or test commands. It relies on the parent's reported results on c63c35ae4: `cd web && npm run check` exited 0 with 174 test files passing, and `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug createDebugUnitTestCoverageReport` finished with BUILD SUCCESSFUL, 1938 tests and 0 failures.

Earlier findings:
- Earlier CR-1 (Android rule that marks a birthday calendar, and its cache round trip, had no tests) is resolved. `CalendarRepositoryTest` now parses `CalendarResponse` with LOCAL, ICS_FEED and GOOGLE sources, read-only and writable, and checks that only the read-only LOCAL calendar comes back virtual. It also checks that `setVisible` keeps the flag. `AgendaCacheTest` stores a virtual calendar, reads it back, and checks that `contactId` still finds the contact from the cached data. `BirthdayOccurrenceTest` covers `Occurrence.contactId` for virtual, writable, other read-only and unknown calendars.
- Earlier CR-2 (the real `BirthdayContact` composable was never run in a test) is resolved. `BirthdayContactTest` renders the real composable with a `ContactDetailViewModel` backed by a mocked `ContactsRepository`. It checks that `get` and `related` are called once for the contact id, that the contact is shown, that both the back button and the system back call `back`, and that offline then retry works.

What was checked in this review:
- Web criterion 1:
    - `virtualOccurrence()` (event-dialog.ts:396) uses `isVirtual` (readOnly + LOCAL, as in assumption #1). For a birthday, `loading` starts false and both the constructor and `load()` skip `events.get`/`reread`, so no load error can appear. `calendarId` is set, so `readOnly()` is true. That shows the "Event" title and Close, hides Delete and submit, and makes `save()`/`delete()` return early.
    - The virtual branch comes before the form branch, so neither the form nor `app-calendar-event-links` is rendered.
    - The date uses `DatePipe` on the date-only `startDate`.
    - The contact link `['/contacts', eventId]` matches the existing `contacts` routes and closes the dialog when followed.
    - Spec tests cover no loading, read-only content, no edit/delete (including a forced submit), the link href with dialog close, and the ICS-feed negative case. `isVirtual` has its own unit tests.
- Android criterion 2:
    - `CalendarWithEditor.onOpen`, which agenda, day and week all share, sends birthdays to the `contact` slot. `EventEditorViewModel.edit` also ignores them, as a second guard.
    - `rememberSaveable` keeps the open contact across configuration changes.
    - The `ContactDetailCallbacks` match the ones the Contacts tab passes.
    - `ContactDetailViewModel` is a `@HiltViewModel`.
    - Tests cover agenda (the default view), day and week taps: no `editRepo.event` call, the editor stays closed, and back returns to the views. The ViewModel tests cover the edit guard and the case where a birthday is tapped while an event is open.
    - The cache moves to version 2 with destructive fallback (it is only a cache), and the exported 2.json schema is committed.
- Security and quality: no secrets, no logging of contact data, and route segments are encoded by `routerLink`. The changes stay within the task's `touches`, apart from the generated Room schema and tests, which assumption #2 records.

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
