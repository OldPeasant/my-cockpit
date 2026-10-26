---
title: Clients: open birthday (virtual) occurrences read-only
status: in_progress
owner: dell26/agent-1
deps: [contacts-birthdays]
touches: [web/src/app/features/calendar,android/app/src/main/java/ch/sonensei/cockpit/calendar]
prio: 2
lease_until: 2026-10-26T04:58:08Z
---
## Goal
Occurrences of the virtual Birthdays calendar (SPEC.md §8.3) have no stored event: `eventId`/`seriesId` are the
contact id and `GET /calendar/events/{eventId}` answers 404 (see `OccurrenceResponse` in `api/openapi.yaml`).
Today the web event dialog treats them as a series occurrence, fetches the event and shows its load-failure state.
Show them read-only from the occurrence data instead, with a link to the contact.

## Acceptance criteria
- [x] Web: clicking a birthday occurrence shows its title/date read-only (no load error, no edit/delete) and links to the contact; unit tests cover it
  - verified (2026-10-26 05:38+0100 by dell26/agent-1): cd web && npm run check -> 174 files passed; test-writer ran npx ng test on event-dialog.spec.ts + event-edit.service.spec.ts -> 69 passed: birthday dialog shows title/date read-only, no events.get/load error, no edit/delete buttons, /contacts/:id link closes dialog, isVirtual only for readOnly LOCAL calendars
- [x] Android: tapping a birthday occurrence in agenda/day/week does not try to load or edit an event; it opens the contact; unit tests cover it
  - verified (2026-10-26 05:38+0100 by dell26/agent-1): cd android && ./gradlew testDebugUnitTest -> CalendarWithEditorTest (agenda/day/week birthday tap opens contact, editRepo.event never called, editor closed, back returns), EventEditorViewModelTest birthday cases, BirthdayContactTest 4/4 (real contact screen loads contact, back/system back, retry), CalendarRepositoryTest+AgendaCacheTest virtual mapping/round trip, BirthdayOccurrenceTest: all pass, 1938 total 0 failures
- [x] `cd web && npm run check` and `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` pass
  - verified (2026-10-26 05:38+0100 by dell26/agent-1): cd web && npm run check -> exit 0 (lint, build, 174 vitest files passed); cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug -> BUILD SUCCESSFUL, 1938 unit tests 0 failures/errors (HEAD c63c35ae4)

## Notes
- Found by the code review of contacts-birthdays (reviews/contacts-birthdays/review.md, CR-1). Recognize the occurrence by its calendar being the read-only calendar whose occurrences 404, e.g. by comparing `eventId` with a known birthdays calendar id or by the calendar's read-only flag plus a missing event.
- ASSUMPTION (2026-10-26 05:05+0100 by dell26/agent-1): Clients recognize a virtual (Birthdays) calendar as readOnly + sourceType LOCAL (CalendarService.ensureVirtualCalendar is the only way a LOCAL calendar becomes read-only); its occurrence eventId is treated as the contact id. A future second virtual calendar would need a provider key in CalendarResponse.
- ASSUMPTION (2026-10-26 05:05+0100 by dell26/agent-1): Android shows the birthday's contact detail inside the Calendar tab (back returns to the calendar) instead of switching to the Contacts tab, so the change stays within the task's touches; agenda cache DB bumped to v2 (destructive fallback) for the new 'virtual' column, which also adds the generated android/app/schemas/.../AgendaCacheDatabase/2.json.
