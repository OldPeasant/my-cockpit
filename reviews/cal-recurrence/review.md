# Code Review

Date: 2026-10-20

Task: cal-recurrence - Recurrence expansion

Scope: Third review, of the final code at revision e6757fa8. It covers the full task implementation from base 3a4ef451 to HEAD e6757fa8, which is every `cal-recurrence:` commit: c015be14, 8ea3faf2, 2e64fe47, 74b7b9a7, 99ff9fd9, 46158c0b, 824e0e0e and e6757fa8. It looks most closely at what changed since the second review (46158c0b..e6757fa8), which is the DST_END_MARGIN fix and the new tests. Files reviewed in full:
- `server/src/main/kotlin/ch/sonensei/cockpit/calendar/recurrence/OccurrenceExpander.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/calendar/recurrence/OccurrenceExpanderTests.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/calendar/recurrence/OccurrenceExpanderEdgeCaseTests.kt`, including the full diff since the second review

The review reports in `reviews/cal-recurrence/` were read for context only.

Requirements:
- tasks/cal-recurrence.md: acceptance criteria 1-3 and the six recorded assumptions
- SPEC.md §6.1 (RRULE, EXDATE and overrides, time-zone aware)
- docs/ARCHITECTURE.md and the AGENTS.md project notes (logging rules, module layout)
- The second review (CR-1, P3), `reviews/cal-recurrence/fixes.md` and `reviews/cal-recurrence/tests.md`

Git range:
- Branch: main
- HEAD: e6757fa866cb9f0053243ccc55c38e0c950d93aa
- Base/start: 3a4ef4513a7beaedd6216d2976f96e2ab7b61a86
- Commits reviewed: 8
- Uncommitted changes: none

Summary: 0 P0, 0 P1, 0 P2, 0 P3

Verification performed:
- Ran `cd server && ./gradlew test --offline --rerun --tests 'ch.sonensei.cockpit.calendar.recurrence.*'`, which passed. OccurrenceExpanderEdgeCaseTests ran 43 tests and OccurrenceExpanderTests ran 13, with 0 failures, errors or skips (from the JUnit XML results).
- Status of the second review's finding:
  - **CR-1 (an occurrence at an ambiguous DST-end time was lost when the range ended inside the repeated hour): resolved.**
    - For timed series, the period end passed to ical4j is now `range.to + DST_END_MARGIN` (2 h), at `OccurrenceExpander.kt:159` and `:242`. Every generated and overridden occurrence still goes through the exact `overlaps(...)` filter (`:127`, `:132`), so the wider search window cannot leak occurrences past `range.to`.
    - The early-return check `seed.isBefore(periodEnd)` now uses the widened end. This is harmless for the same reason: the overlap filter drops the extra candidates.
    - The period start correctly gets no margin. `withEarlierOffsetAtOverlap()` only ever moves a candidate earlier, and a candidate ical4j drops before `range.from - duration` has an end before `range.from`.
    - COUNT and UNTIL are part of the rule, not of the query window, so the margin cannot extend a series.
    - Two hours covers every current DST jump, including half-hour zones such as Australia/Lord_Howe. All-day series are unchanged.
  - Tests cover the fix:
    - a regression test with three range ends inside the repeated hour;
    - the matching exclusion cases, where the range ends at or before 00:30Z or starts at 01:00Z;
    - a summer-time seed;
    - candidates that fall within the margin but past `range.to`, including zero-length occurrences;
    - COUNT and UNTIL ending inside the margin;
    - an override whose original occurrence lies inside the margin.

    The fix report says the regression test failed without the fix.
  - The earlier fixes from the first review (bad-RRULE fallback with a WARN log that names only the event id and the exception type, cancelled orphan overrides, earlier-offset normalisation with an exact-seed exception) are unchanged and still covered.
- Acceptance criteria:
  - Criterion 1 is met. `OccurrenceExpander` uses ical4j `Recur` to expand the RRULE in the event's zone (UTC if it has none), applies EXDATEs and overrides, and returns the instants that overlap [from, to). It is pure, as the confirmed assumption states.
  - Criterion 2 is met. Named tests in `OccurrenceExpanderTests.kt` cover each listed case:
    - weekly across DST start and end in Europe/Zurich (lines 69 and 85);
    - all-day series and single events (100 and 116);
    - moved and cancelled occurrences (131 and 154);
    - COUNT and UNTIL (164 and 173);
    - monthly by weekday (186).
  - Criterion 3 is met. The command passes, as run above. Testcontainers is not needed because the expander does no persistence.
  - `python tasks.py verify cal-recurrence` still lists all three criteria as unverified. The parent worker records that evidence before `done`, so this is not a finding at this stage.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Review conclusion

No material issues found. The second review's CR-1 is resolved at revision e6757fa8, with regression and boundary tests. The first review's fixes are still in place. The implementation meets all three acceptance criteria.
