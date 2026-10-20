# Code Review

Date: 2026-10-20

Task: cal-recurrence - Recurrence expansion

Scope: Second review, of the final code after the fixes in `reviews/cal-recurrence/fixes.md`. It covers all task commits from base 5537ffa9 to revision ba2e34f2 (67d8680e, 53b64bc2, a59471a4, 7a15f449, ba2e34f2). That chain has since been rebased onto main: 57f063c8, 5add4814, b902721d, 62bd3ae0, b6e92400, on base 172d59d6. `git diff ba2e34f2 b6e92400 -- server reviews/cal-recurrence` is empty, so the reviewed server code is the same in both. Files reviewed in full:
- `server/src/main/kotlin/ch/sonensei/cockpit/calendar/recurrence/OccurrenceExpander.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/calendar/recurrence/OccurrenceExpanderTests.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/calendar/recurrence/OccurrenceExpanderEdgeCaseTests.kt`

Board-only `tasks/*.md` commits are out of scope. So are the review reports, apart from checking that the fixes they describe are present.

Requirements:
- tasks/cal-recurrence.md: acceptance criteria 1-3 and the six recorded assumptions, three of them confirmed by a human
- SPEC.md §6.1 (RRULE, EXDATE and overrides, time-zone aware) and §6.4
- docs/ARCHITECTURE.md (ical4j in the stack) and the AGENTS.md project notes (logging rules, module layout)
- The calendar model in `calendar/model/CalendarEvent.kt` (`OccurrenceId`, exdates, rrule)
- The previous review (CR-1 to CR-3), the test report (TW-1) and `reviews/cal-recurrence/fixes.md`

Git range:
- Branch: main
- HEAD: ba2e34f2adf038e167a7a46e559b52c16be1d3fd as supplied. The clone's HEAD is now b6e92400761853cb9e3a1333dc5e1e9e5bc8b4fb, which is the same task code after a rebase.
- Base/start: 5537ffa91f44c8f44d5311076731fb7f738c87f8 (rebased base: 172d59d6)
- Commits reviewed: 5
- Uncommitted changes: none

Summary: 0 P0, 0 P1, 0 P2, 1 P3

Verification performed:
- Ran `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.calendar.recurrence.*' --offline`: BUILD SUCCESSFUL. OccurrenceExpanderTests ran 13 tests and OccurrenceExpanderEdgeCaseTests ran 37, all passing with 0 failures, errors or skips.
- Status of the earlier findings, checked against the current code:
  - **CR-1 (an unparsable RRULE broke the whole range): resolved.** `recurOrEmpty` (`OccurrenceExpander.kt:170-180`) wraps the ical4j parse and expansion for both all-day and timed series. On failure only DTSTART remains, and overrides and EXDATEs still apply. The WARN line logs only the event id and the exception class name, which follows the logging rule in AGENTS.md. Tests cover:
    - invalid, blank and `BYDAY=XX` rules alongside a valid event;
    - an all-day series with a bad rule;
    - overrides on a series with a bad rule;
    - the log content, via `CapturedOutput`.

    A date-only UNTIL on a timed series is now rewritten to a floating end-of-day value (`:158`, `:236`). This matches the recorded assumption and is tested, including the zone-versus-UTC case.
  - **CR-2 / TW-1 (cancelled orphan overrides were shown): resolved.** The orphan path now filters `EventStatus.CANCELLED` (`:100`), the same way `expandSeries` does (`:130`). The test `cancelled override is never shown, also when its master is not among the candidates` covers it.
  - **CR-3 (an ambiguous DST-end time followed the seed's offset): resolved.** Generated dates are normalised with `withEarlierOffsetAtOverlap()`. The seed's own local date-time is the one exception and stays exactly as stored (`:164`). Tests cover a winter-time seed, a seed at the later ambiguous instant, and EXDATE/override matching at the ambiguous time. One new boundary issue introduced by this normalisation is reported as CR-1 below.
- Acceptance criteria:
  - Criterion 1 is met by the code. It is pure and needs no persistence, as the confirmed assumption states.
  - Criterion 2 is covered by named tests for weekly series across DST start and end in Europe/Zurich, all-day series, moved and cancelled occurrences, COUNT and UNTIL, and monthly by weekday.
  - Criterion 3: the command passes as run above. Testcontainers is not needed because nothing here touches persistence.
  - `python tasks.py verify cal-recurrence` still lists all three criteria as unverified. The parent worker records that evidence before `done`, so this is not a finding at this stage.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | An occurrence at an ambiguous DST-end time is lost when the range ends inside the repeated hour**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/calendar/recurrence/OccurrenceExpander.kt:156-164`
    - Category: Correctness
    - Problem: Two things combine here:
      - ical4j picks candidates up to `periodEnd = range.to.atZone(zone)`, using the seed's offset for an ambiguous local time.
      - The code then moves each candidate to the earlier offset (`withEarlierOffsetAtOverlap()`), which can make it start up to one hour before the instant ical4j filtered on.

      Example: a weekly series seeded in winter time (2026-01-04 02:30 Europe/Zurich, 30 min) and a range ending at 2026-10-25T01:00Z (02:00+01:00, inside the repeated hour). ical4j's candidate is 02:30+01:00 = 01:30Z. That is after `periodEnd`, so ical4j never returns it. After normalisation the occurrence would be [00:30Z, 01:00Z), which overlaps the range and should be returned, but it is missing. The same happens for any `range.to` in (00:30Z, 01:30Z] on that day.

      This follows from the code. I did not run it, because this review may not add scratch code. No test covers a range end inside the repeated hour; the existing DST-end tests use midnight boundaries.
    - Why it matters: Views and API clients get no occurrence for that slot, while a range a few minutes longer returns it. The trigger is narrow: a range end in the repeated hour, plus a series at that hour whose seed is in the other offset. Hence low priority.
    - Suggested fix: Widen the timed `periodEnd` given to ical4j by the zone's largest offset jump. A fixed `plusHours(1)`, or better `Duration.ofHours(2)` for zones with larger shifts, is enough, because `overlaps(...)` already filters the result exactly. Add a test with a winter-time seed and `range.to` = 2026-10-25T01:00Z.

## Review conclusion

No material issues found. All earlier findings (CR-1, CR-2, CR-3 and TW-1) are resolved in revision ba2e34f2, and each has a regression test. The only new finding is the P3 boundary edge case above. It is optional and does not block completing the task.
