# Code Review

Date: 2026-10-20

Task: cal-recurrence - Recurrence expansion

Scope: The task's code changes from base e5c45b44 up to the task commit (4b0e570, rebased as ede9e8b on main with identical server files; the diff between them touches only `tasks/*.md`). Files reviewed: `server/src/main/kotlin/ch/sonensei/cockpit/calendar/recurrence/OccurrenceExpander.kt` and `server/src/test/kotlin/ch/sonensei/cockpit/calendar/recurrence/OccurrenceExpanderTests.kt`. Board-only `tasks/*.md` changes and the test-writer's uncommitted `OccurrenceExpanderEdgeCaseTests.kt` are out of scope.

Requirements:
- tasks/cal-recurrence.md (acceptance criteria 1-3 and the three recorded assumptions)
- SPEC.md §6.1 (RRULE + EXDATE + overrides, time-zone aware), §6.4 (`GET /calendar/events` returns expanded occurrences), §12 (RRULE expansion unit tests)
- docs/ARCHITECTURE.md (ical4j 4.1.1 in the stack), AGENTS.md project notes
- Calendar model: `CalendarEvent`, `OccurrenceId`, `CalendarEventRepository.findCandidatesInRange`, migration `V202610201351__calendar_model.sql` (constraints guarantee non-null start/end per form)

Git range:
- Branch: main
- HEAD: ede9e8b0dc544c339386e3d116031c84bd548219
- Base/start: e5c45b44d3144647824cd28217b67d3620da2aed
- Commits reviewed: 1 code commit (4b0e570 / ede9e8b). The other commits in the range are board-only (assume, heartbeat, confirm, verify)

Summary: 0 P0, 0 P1, 2 P2, 1 P3

Verification performed:
- `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.calendar.recurrence.*'` -> BUILD SUCCESSFUL.
- A scratch harness in `.tmp/cr-cal-recurrence` (a plain Gradle project with ical4j 4.1.1 and the compiled server classes, run with `./gradlew -p ../.tmp/cr-cal-recurrence run --offline`) called `OccurrenceExpander` directly. Results:
  - COUNT applies when the range starts after the seed: `FREQ=WEEKLY;COUNT=3` with the range after the 3rd occurrence gives 0 occurrences. ical4j counts candidates before periodStart toward COUNT.
  - A zero-length series occurrence exactly at `from` is included, so periodStart is not exclusive.
  - UTC UNTIL, floating UNTIL and DATE-TIME UNTIL on an all-day series are all inclusive and correct.
  - A nonexistent local time at DST start is shifted forward (02:30 to 03:30+02:00), as the assumption says.
  - `BYMONTHDAY=31` skips short months. `FREQ=YEARLY` on 29 Feb lands only in leap years.
  - Performance measured on a cold JVM, one series per call: DAILY since 1990 over 1 week took 39 ms. HOURLY since 2000 over 3 h took 176 ms. MINUTELY;INTERVAL=15 since 2025 took 39 ms. All-day DAILY since 1990 took 8 ms. ical4j iterates from the seed, so cost grows with series age times frequency. This is acceptable for the realistic DAILY/WEEKLY cases and is not reported as a finding.
- The acceptance criteria are all met by the code and tests. CR-1 to CR-3 are about resilience and edge-case correctness.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | One unparsable RRULE makes the whole range expansion throw**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/calendar/recurrence/OccurrenceExpander.kt:140`, `:150` (called for every master from `expand` at `:87`)
    - Category: Correctness
    - Problem: `Recur<LocalDate>(rule)` / `Recur<ZonedDateTime>(rule)` and `getDates` are called without any error handling, and `rrule` is free text (no validation in `CalendarEvent` or in the schema). The scratch harness showed these failures:
      - `FREQ=FORTNIGHTLY` throws `IllegalArgumentException: No enum constant ...Frequency.FORTNIGHTLY`.
      - A blank rule throws `IllegalArgumentException: Missing expected token`.
      - `BYDAY=XX` throws `IllegalArgumentException`.
      - A timed series with a DATE-only UNTIL (`FREQ=DAILY;UNTIL=20260108`) throws `UnsupportedTemporalTypeException: Unsupported unit: Seconds`. This value form is invalid under RFC 5545 but common in third-party ICS/CalDAV data.

      `expand(listOf(badSeries, goodSingleEvent), range)` throws, so the valid events are lost too.
    - Why it matters: `expand` is meant to serve `GET /api/v1/calendar/events` (SPEC.md §6.4) over all candidates of the requested calendars. Events come from Google, Microsoft, CalDAV and ICS feeds (§6.1/§6.2). A single malformed or loosely formed rule from a provider would take down the whole calendar view (every range that includes that series), not just that one event.
    - Suggested fix: In `originalStarts`, catch the parse/expansion failure for that series and fall back to the master's own occurrence (seed only) or skip the series. Log the event id (not its content) at WARN. Consider normalizing a DATE-only UNTIL on a timed series (end of that day in the event's zone) since providers emit it. Add a test that `expand` still returns the other events when one series has an invalid rule.

- [ ] **CR-2 | Orphan overrides ignore CANCELLED, so a cancelled occurrence is shown as an event**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/calendar/recurrence/OccurrenceExpander.kt:88-93`
    - Category: Correctness
    - Problem: For overrides whose master is in `events`, `expandSeries` drops `EventStatus.CANCELLED` overrides (`:121-122`). The orphan path in `expand` (master not among the candidates) has no such filter. Scratch check: a master `FREQ=WEEKLY` starting 2026-06-08 and a CANCELLED override at 2026-06-01 09:00, expanded over 2026-06-01..06-02, returned 1 occurrence with status CANCELLED. With the master present the same override yields 0. `findCandidatesInRange` leaves the master out when it starts at or after `to` while still returning all its live overrides, so the repository path produces this input. Example: an override that lies before the series' current DTSTART after the series start was moved or truncated.
    - Why it matters: The class contract (KDoc `:68-69`) and the model (`CalendarEvent` KDoc) say a CANCELLED override removes the occurrence. Here a removed occurrence shows up as a visible event in day/week views. The result also depends on whether the master happened to be in the candidate set.
    - Suggested fix: Apply the same `status != EventStatus.CANCELLED` filter to orphans, and add a test for a cancelled orphan override.

## P3 Low

- [ ] **CR-3 | Ambiguous DST-end times resolve by the seed's offset, not always to the earlier offset as recorded**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/calendar/recurrence/OccurrenceExpander.kt:146-151`
    - Category: Correctness
    - Problem: The recorded assumption says ambiguous local times at DST end use the earlier (summer) offset. That holds only when the seed is in summer time, which is the case the test at `OccurrenceExpanderTests.kt:85-97` covers. ical4j derives each candidate from the seed `ZonedDateTime`, which keeps the seed's offset when the local time is ambiguous. Scratch check: `FREQ=WEEKLY` seeded 2025-11-02T02:30 Europe/Zurich (+01:00) gives the 2026-10-25 occurrence at 02:30+01:00 (01:30Z), the later instant. A summer seed gives 02:30+02:00 (00:30Z).
    - Why it matters: Occurrence ids (`OccurrenceId.at`) for that one occurrence differ from the RFC 5545 §3.3.5 reading ("refers to the first occurrence of the referenced time"). A synced RECURRENCE-ID or EXDATE resolved with java.time's default (earlier offset) would then not match the generated occurrence: the override would appear next to the unmodified occurrence instead of replacing it. This only affects events between 02:00 and 03:00 on the DST-end day, hence low.
    - Suggested fix: Normalize generated candidates with `ZonedDateTime.withEarlierOffsetAtOverlap()` before `toInstant()` (or build them via `LocalDateTime.atZone(zone)`), and add a test with a winter-time seed. Alternatively, correct the recorded assumption to describe the actual behavior.

## Review conclusion

Material issues found; fix before completing the task. CR-1 and CR-2 are P2 correctness and resilience defects in the expander's own contract. Each has a small, local fix and a matching test. CR-3 is optional.
