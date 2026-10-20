# Code Review

Date: 2026-10-20

Task: cal-local-api - Calendar API for local calendars

Scope: `git diff b1708640253e120e461364a0e5ce82230387e099..d46c83e` (committed changes only). This is the final-code review after the fixes for CR-4, CR-5 (previous review) and TW-2 (test-writer). The production changes since the previous review are in `CalendarEventService.kt` (`seriesBase`, used by `updateSeries` and `splitSeries`) and `EventCommands.kt` (`EventContent.propagate`). The whole task was re-checked against its acceptance criteria.

Reviewed revision: d46c83e1e0669ee2321863e1fe7c78cc2874ec2d

Requirements:
- `tasks/cal-local-api.md`: the 5 acceptance criteria and the ASSUMPTION notes, including the 2026-10-20 20:20 note on the all-day kind and on CANCELLED overrides
- SPEC.md §4.2 and §6.4, docs/ARCHITECTURE.md, docs/QUALITY.md, AGENTS.md project notes
- `reviews/cal-local-api/fixes.md` (three cycles), the previous `reviews/cal-local-api/review.md`

Git range:
- Branch: main
- HEAD: d46c83e1e0669ee2321863e1fe7c78cc2874ec2d
- Base/start: b1708640253e120e461364a0e5ce82230387e099
- Commits reviewed: 11 (7437436 to d46c83e)
- Uncommitted changes: `reviews/cal-local-api/tests.md` and `CalendarSeriesEdgeCaseWebTests.kt` have uncommitted changes from a concurrent test-writer. As instructed, they were not reviewed.

Files read in full: `CalendarEventService.kt`, `EventCommands.kt`. I also read the relevant parts of `OccurrenceExpander.kt` (how CANCELLED overrides are handled), the all-day and override tests in `CalendarSeriesEdgeCaseWebTests.kt` (lines 156-405), and the diff of `ReminderServiceEdgeCaseTests.kt`. The controllers, DTOs, `CalendarService`, `EventTrashContributor`, `api/openapi.yaml` and the generated client have not changed since the previous review (`git diff 6fc9bfc..HEAD -- server/src/main` touches only the two files above). I did not run Gradle in this session. Test results come from `fixes.md`.

Summary: 0 P0, 0 P1, 0 P2, 1 P3

## Status of the earlier findings

- **CR-4: resolved.** `seriesBase` (`CalendarEventService.kt:292-299`) uses the addressed override as the base only when the override has the kind the series ends up with (`input.allDay`, otherwise the series' own kind). A request without `allDay` therefore never changes the series' kind. Time fields of the other kind now get a 400 from `Timing.resolve`, before any write. Tests: `CalendarSeriesEdgeCaseWebTests.kt:204` (400, nothing changed), `:223` and `:242` (a start moves or splits the series, which stays timed and keeps its EXDATE).
- **TW-2: resolved.** When `allDay` equals the series' kind, the result is now the same as without `allDay` (`:306`, `:325`, `:368`). I traced the ALL and FOLLOWING paths by hand and they give the expected times.
- **CR-5: resolved as specified.** `propagate` no longer changes the status of a CANCELLED override (`EventCommands.kt:240`). ALL and FOLLOWING both use it. Tests: `:748`, `:765`. The resulting trade-off is recorded with `tasks.py assume`: an override that was cancelled together with its series stays cancelled, and hidden, after the series is un-cancelled. See the conclusion.
- CR-1 to CR-3 and TW-1 from earlier cycles are still in place (`addressedTiming`/`carryExceptions` `:375-427`, the 409 mapping `:223-230`, `load` `:606-610`).

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-6 | Turning an all-day series timed through a timed override (`allDay:false` + `start`, scope ALL or FOLLOWING) gives the series a start near midnight instead of the requested time**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/calendar/api/CalendarEventService.kt:297-298` (`seriesBase`), applied at `:266-267` (ALL) and `:323-324` (FOLLOWING); `server/src/main/kotlin/ch/sonensei/cockpit/calendar/api/EventCommands.kt:99-101` (`Timing.shifted`, Timed branch)
    - Category: Correctness
    - Problem: When the client requests a kind change and the addressed override already has the target kind, `seriesBase` uses that override as the base. For the timed target the shift is `Duration.between(override.localStart, requested.localStart)`, and `shifted` adds it to the master's `localStart`. For an all-day master, `localStart` is midnight. Worked example, based on the setup of the test at `:343`:
        1. Create a daily all-day series "Gym" starting 2026-03-02.
        2. `PATCH THIS {"allDay":false,"start":"2026-03-04T17:00:00Z","timeZone":"Europe/Zurich"}` makes the occurrence of 03-04 a timed override (18:00 local time).
        3. `PATCH <override>?scope=ALL {"allDay":false,"start":"2026-03-05T17:00:00Z"}`.

      The computed shift is +24h. The master becomes timed at 2026-03-03T00:00 Europe/Zurich (2026-03-02T23:00Z), so every occurrence runs from 00:00 to 01:00. The user asked for 18:00. Because the kind changes, `carryExceptions` drops every override (`moveOccurrenceId` returns `null`), including the addressed one. The requested 18:00 therefore appears nowhere. FOLLOWING has the same base error for the new series. Without an override the same request works: `shown` is the all-day occurrence with a midnight base, and the analogous master path is tested at `:177`. The reverse direction (timed series, all-day override, `allDay:true`) is not affected, because the AllDay branch of `shifted` only uses dates. No test covers this combination.
    - Why it matters: A requested change of kind puts the whole series at the wrong time of day and silently discards the time the client sent. The precondition is rare, so this is P3. It is the mirror case of CR-4/TW-2 and comes from the same base-selection rule.
    - Suggested fix: When the series changes kind, use the original occurrence (the series' own kind) as the base. Use the override only when it, the series and the target all have the same kind: `override?.takeIf { (it is Timing.AllDay) == allDay && allDay == (original is Timing.AllDay) } ?: original`. By my trace, the existing tests at `:267` and `:288` give the same results with this base, because the override there is on its original date. Add web tests for ALL and FOLLOWING with `{"allDay":false,"start":...}` through a timed override of an all-day series. Alternatively, record the current behaviour with `tasks.py assume` if it is intended.

## Checked without findings

- AC1/AC2: endpoints, the range check, scope defaults, occurrence parsing, FOLLOWING COUNT/UNTIL handling, and DELETE THIS/FOLLOWING/ALL are unchanged since the previous review and still match the recorded assumptions. In the new code, validation (`Timing.resolve`, `validate`) runs before the first `saveAndFlush`, so a rejected kind-mismatch request writes nothing (asserted at `:204` with `rowCount`).
- AC3: the new code paths publish change events and re-index as before. With the CR-5 change, a CANCELLED override is still de-indexed (`CalendarEventIndexer.kt:38`) and hidden by the expander (`OccurrenceExpander.kt:130`), as `:748` asserts.
- AC5 and integration: no annotations or DTOs changed after the last regeneration, so `api/openapi.yaml` and `web/src/app/api/generated/` are still current. `fixes.md` reports that `OpenApiContractTest` passes without the flag.
- `ReminderServiceEdgeCaseTests.kt` (outside the task's touches, recorded as an assumption): the test now uses its own two-thread executor, shut down in `finally`. The latch release is still in an inner `finally`. Assertions are not weakened.
- Security: no new logging, inputs or endpoints. No secrets or personal data in the diff.
- Tests: every fixed finding has a web regression test. Per `fixes.md`, the focused suite and `./gradlew check` pass on Testcontainers Postgres.

## Review conclusion

No material issues found. CR-4, CR-5 and TW-2 are fixed correctly. I found no regression in the paths they touch, apart from the pre-existing mirror case CR-6 (P3). The parent worker may fix CR-6 or accept it with a recorded assumption. It does not block completion of the task.

The CR-5 fix has a trade-off, already recorded with `tasks.py assume` for human review. `PATCH ALL {"status":"CANCELLED"}` followed by `{"status":"CONFIRMED"}` now hides every override of the series for good: the occurrences the user had moved or edited disappear from the list and from search, while the generated occurrences come back. A THIS delete of an override could instead be stored as an EXDATE and the override row deleted. That would remove the ambiguity, if the humans reject the assumption.

I did not run the test suite in this review. For criteria 4 and 5 the parent must use its own test runs as evidence.
