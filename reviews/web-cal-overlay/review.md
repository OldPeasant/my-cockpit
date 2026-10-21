# Code Review

Date: 2026-10-21

Task: web-cal-overlay - Calendar task overlay and cross-links (round 3, final revision)

Scope: the whole task on the final revision, `git diff 7d07e18c..915e9caa -- . ':!tasks' ':!reviews'`. Round 2 reviewed the code at 43826282c without findings. Since then the only code commit is 915e9caaf (test-writer), and it changes only tests. `git diff 43826282c..915e9caa -- server web` touches exactly `web/src/app/features/calendar/overlay/event-links.spec.ts` (+76/-1), `web/src/app/features/calendar/views/calendar-view.spec.ts` (+95) and `server/src/test/kotlin/ch/sonensei/cockpit/gtd/TaskDateRangeWebTests.kt` (+22). Main was rebased after round 2, so the task commits now have new hashes (28c2680b2, 2b7dc32f5, 8c3f3ff78, 4b200d88c, plus test and review commits). Production code is the same as in round 2. Reviewed revision: 915e9caafe8880fb6a16520c8b42c18560a630f8. Uncommitted changes at review start: none.

Requirements:
- tasks/web-cal-overlay.md (three acceptance criteria and the ASSUMPTION notes)
- SPEC.md §6.3 (optional task overlay in day/week, event detail with linked items, "Create task from event" / "Create note for event" with a pre-filled title and date)
- docs/ARCHITECTURE.md, docs/QUALITY.md, AGENTS.md project notes
- reviews/web-cal-overlay/review.md (rounds 1 and 2) and reviews/web-cal-overlay/fixes.md

Git range:
- Branch: main
- HEAD: 915e9caafe8880fb6a16520c8b42c18560a630f8
- Base/start: 7d07e18cf944c834f8017fe8377eef7a55acce08
- Commits reviewed: 1 new test commit (915e9caaf) on top of the round-2 review of the task's code commits

Commands run (read-only):
- `git diff --stat 43826282c..915e9caa` and `git diff 43826282c..915e9caa -- server web` -> only the three test files above changed outside `tasks/` and `reviews/`
- read the production code the new tests target: `overlay/event-links.ts` (timeZone input, error message through `ApiError.userMessage`), `overlay/event-links.service.ts` (`datedTitle`, `occurrenceDate`, `silent()` context), `gtd/api/TaskController.kt` `dateRange` (messages and the `DAYS.between >= 366` rule), `core/http/api-error.ts`
- read the spec helpers the new tests rely on: `views/testing.ts` `fakeOccurrence`, `calendar-view.spec.ts` `week`/`nextWeek`/`showWeek`/`answerTasks`/`taskEntries`/`check`, `event-links.spec.ts` `render` (sets `timeZone` to Europe/Zurich)
- The reviewer ran no tests. The parent reported for 915e9caa: focused web tests 357 passed. The test-writer reported `npm run check` exit 0 and gtd server tests 407 passed. Playwright overlay spec: 23 passed (including the 2 overlay tests) on 43826282c, and production code has not changed since.

Summary: 0 P0, 0 P1, 0 P2, 0 P3

New tests (915e9caaf), checked:
- `event-links.spec.ts`:
  - The time zone date case (23:30 UTC becomes 2026-03-24 in Zurich) checks the task and the note titles against the real `datedTitle` path. It would fail if the date were taken in UTC.
  - The all-day case checks that the title takes its date from `startDate`.
  - The series case (eventId = seriesId = master, the shape of a generated occurrence) checks `links.list` and `links.create` against the event that holds the series. The override case (eventId differs from seriesId) is already covered by the `eventRef` helper test.
  - The SILENT_ERRORS case reads the `HttpContext` that is actually passed to both create calls.
  - The refused-create case checks that the panel alert shows `ApiError.userMessage` and that no link is created.
  - All assertions check behavior, not internals.
- `calendar-view.spec.ts`:
  - Turning the overlay off hides the load error.
  - An older answer that arrives last is dropped, both when it succeeds and when it fails. The stale success holds a task due inside the shown week, so the test would catch a stale overwrite rather than pass because the date filter removes it. The "no error shown" assertion covers the failure path.
  - Day view: the range is a single day (`from = to = 2026-03-25`, from FullCalendar's exclusive end in CET).
  - Month back to the same week shows the loaded tasks again without a second request.
  - These tests cover the out-of-order and loading/error-state checks from round 2 with real `OverlayTaskStore`/`TaskOverlay` wiring and fake `Api` calls.
- `TaskDateRangeWebTests.kt`: the new Problem Details test checks status 400, `application/problem+json`, and `errors[0].field`/`message` for all four rejection rules. The messages match `TaskController.dateRange` exactly. The comment "366 days after from is a 367-day range" is correct for 2026-01-01..2027-01-02 (`DAYS.between = 366`, rejected).
- Test data is fictitious (Pay rent, Call Ada, Book dentist, Ski trip, Late call, Standup). No secrets, no personal data.
- No existing tests were weakened, deleted or skipped.

Round-1 findings (CR-1 dated task title, CR-2 range branch of `matchesTask` untested, CR-3 range message, CR-4 refetch while overlay off): still resolved. The round-2 reasoning holds unchanged because production code did not change. The round-2 checks without findings also still hold: toggling off during a request, turning on after a range change while off, retry visibility, the `/calendar/events/:id` redirect, the event-dialog links panel, and server range validation with the generated client.

Observation, not a finding (carried over from round 2): `TaskOverlay` keeps the on/off state in `enabledState` and mirrors it into `OverlayTaskStore.active` in both places that write it. This is consistent today, but a future writer would have to keep the two in sync.

Note for the parent: the criterion-3 Playwright evidence comes from 43826282c. That is valid for 915e9caa because the only change since then is unit/web test files, which the e2e run does not use. The recorded evidence should name the revision it ran on.

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
