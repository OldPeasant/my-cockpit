# Code Review

Date: 2026-10-20

Task: cal-local-api - Calendar API for local calendars

Scope: `git diff b1708640253e120e461364a0e5ce82230387e099..6abd35bcc18d97ff3c0e73a153de12607171ffba` (committed changes only). This is the final-code review after the fixes for CR-4, CR-5 (previous review) and TW-2 (test-writer). The production changes since the previous review are in `CalendarEventService.kt` (`seriesBase`, used by `updateSeries` and `splitSeries`) and `EventCommands.kt` (`EventContent.propagate`). The whole task was re-checked against its acceptance criteria.

The first version of this report covered `b1708640..d46c83e`. The task commits were then rebased onto the latest main, and 6abd35b was added. Between d46c83e and 6abd35b, `git diff d46c83e..6abd35b -- server/src/main api web` is empty. The only change to the task's code is one added test in `CalendarSeriesEdgeCaseWebTests.kt`, reviewed below. The other files in that diff are from main or are tasks/reviews bookkeeping: tooling (`tasks.py`, `.claude/agents/`, `docs/QUALITY.md`, `tests/test_quality_gate.py`), `dashboard/`, and `tasks/gtd-recurrence.md`. They are outside this task and were not reviewed.

Reviewed revision: 6abd35bcc18d97ff3c0e73a153de12607171ffba

Requirements:
- `tasks/cal-local-api.md`: the 5 acceptance criteria and the ASSUMPTION notes, including the 2026-10-20 20:20 note (all-day kind, CANCELLED overrides) and the 20:40 note (CR-6 accepted)
- SPEC.md §4.2 and §6.4, docs/ARCHITECTURE.md, docs/QUALITY.md, AGENTS.md project notes
- `reviews/cal-local-api/fixes.md` (three cycles), the previous `reviews/cal-local-api/review.md`

Git range:
- Branch: main
- HEAD: 6abd35bcc18d97ff3c0e73a153de12607171ffba
- Base/start: b1708640253e120e461364a0e5ce82230387e099 (an ancestor of HEAD)
- Commits reviewed: 25 in the range. 12 are task commits (d69187a to 6abd35b), the rest are main's tooling, heartbeat, verify and assume commits.
- Uncommitted changes: none in the reviewed files

Files read in full: `CalendarEventService.kt`, `EventCommands.kt`. I also read:
- the relevant parts of `OccurrenceExpander.kt` (how CANCELLED overrides are handled);
- the all-day and override tests in `CalendarSeriesEdgeCaseWebTests.kt` (lines 156-405), including the test added in 6abd35b;
- the diff of `ReminderServiceEdgeCaseTests.kt`.

The controllers, DTOs, `CalendarService`, `EventTrashContributor`, `api/openapi.yaml` and the generated client have not changed since the previous review. I did not run Gradle in this session.

Test results reported by the parent worker on 6abd35b:
- focused calendar API suite plus `OpenApiContractTest`: 145/145 pass;
- full server test task: 1789/1789 pass.

Summary: 0 P0, 0 P1, 0 P2, 1 P3 (CR-6, accepted)

## Status of the earlier findings

- **CR-4: resolved.** `seriesBase` (`CalendarEventService.kt:292-299`) uses the addressed override as the base only when the override has the kind the series ends up with (`input.allDay`, otherwise the series' own kind). A request without `allDay` therefore never changes the series' kind. Time fields of the other kind now get a 400 from `Timing.resolve`, before any write. Tests: `CalendarSeriesEdgeCaseWebTests.kt:204` (400, nothing changed), `:223` and `:242` (a start moves or splits the series, which stays timed and keeps its EXDATE).
- **TW-2: resolved.** When `allDay` equals the series' kind, the result is now the same as without `allDay` (`:306`, `:325`, `:368`). I traced the ALL and FOLLOWING paths by hand and they give the expected times.
- **CR-5: resolved as specified.** `propagate` no longer changes the status of a CANCELLED override (`EventCommands.kt:240`). ALL and FOLLOWING both use it. Tests: `:748`, `:765`. The resulting trade-off is recorded with `tasks.py assume`. See the conclusion.
- CR-1 to CR-3 and TW-1 from earlier cycles are still in place (`addressedTiming`/`carryExceptions` `:375-427`, the 409 mapping `:223-230`, `load` `:606-610`).

## Test added in 6abd35b

`PATCH ALL or FOLLOWING with allDay true through a timed override of an all-day series behaves like without allDay` (`CalendarSeriesEdgeCaseWebTests.kt:368`). The test name occurs once in the file.

The test is sound:
- **ALL path:** daily all-day series from 03-02, with a timed override of 03-04. `seriesBase` takes the original all-day occurrence (the override is timed, and the target is all-day). The series moves by +1 day to 03-03. The override's recurrence id moves to 03-05 and it takes the requested all-day times. My trace gives the asserted start/end dates and five all-day occurrences from 03-03 to 03-07.
- **FOLLOWING path:** daily series from 04-01 with COUNT=5. The old series ends with UNTIL 04-02. The new series starts 04-04 with COUNT=3 (5 minus 2 earlier occurrences). That gives the asserted dates 04-01, 04-02, 04-04, 04-05, 04-06.
- **Assertions:** it checks behaviour through the web API without mocks.
- **Coverage:** it covers the `allDay` sent equal to an all-day series' own kind direction of TW-2. That direction had no test before.

No findings.

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
    - Problem: When the client requests a kind change and the addressed override already has the target kind, `seriesBase` uses that override as the base. For the timed target the shift is `Duration.between(override.localStart, requested.localStart)`, and `shifted` adds it to the master's `localStart`. For an all-day master, `localStart` is midnight. Worked example:
        1. Create a daily all-day series starting 2026-03-02.
        2. Make the occurrence of 03-04 a timed override at 18:00 Europe/Zurich.
        3. `PATCH <override>?scope=ALL {"allDay":false,"start":"2026-03-05T17:00:00Z"}`.

      The master becomes timed at 2026-03-03T00:00 Europe/Zurich, not at 18:00. Because the kind changes, `carryExceptions` drops every override, including the addressed one, so the requested time appears nowhere. FOLLOWING has the same base error. The reverse direction is not affected, and no test covers this combination.
    - Why it matters: A requested change of kind puts the series at the wrong time of day. The precondition is rare. The defect existed before the CR-4/TW-2 fixes and is the mirror case of them.
    - Suggested fix: When the series changes kind, use the original occurrence (the series' own kind) as the base. Add ALL and FOLLOWING web tests for this case.
    - Disposition: **Accepted, not fixed.** It is recorded with `tasks.py assume` in `tasks/cal-local-api.md` (ASSUMPTION 2026-10-20 20:40, R1963MACOS/agent-2), with the follow-up fix described. It does not block completion.

## Checked without findings

- AC1/AC2: endpoints, the range check, scope defaults, occurrence parsing, FOLLOWING COUNT/UNTIL handling, and DELETE THIS/FOLLOWING/ALL are unchanged since the previous review and still match the recorded assumptions. In the new code, validation (`Timing.resolve`, `validate`) runs before the first `saveAndFlush`, so a rejected kind-mismatch request writes nothing (asserted at `:204` with `rowCount`).
- AC3: the new code paths publish change events and re-index as before. A CANCELLED override is still de-indexed (`CalendarEventIndexer.kt:38`) and hidden by the expander (`OccurrenceExpander.kt:130`), as `:748` asserts.
- AC4: the parent worker reports 145/145 focused tests (`ch.sonensei.cockpit.calendar.api.*` plus `OpenApiContractTest`) and 1789/1789 in the full server test task on 6abd35b. I did not run them myself.
- AC5 and integration: no annotations or DTOs changed after the last regeneration, so `api/openapi.yaml` and `web/src/app/api/generated/` are still current, and `OpenApiContractTest` is among the passing tests.
- `ReminderServiceEdgeCaseTests.kt` (outside the task's touches, recorded as an assumption): the test now uses its own two-thread executor, shut down in `finally`. The latch release is still in an inner `finally`. Assertions are not weakened.
- Security: no new logging, inputs or endpoints. No secrets or personal data in the diff.
- Tests: every fixed finding has a web regression test.

## Review conclusion

No material issues found. CR-4, CR-5 and TW-2 are fixed correctly, and the test added in 6abd35b is correct. I found no regression in the paths the fixes touch. The only open item is CR-6 (P3). It existed before these fixes and is accepted with a recorded assumption.

The CR-5 fix has a trade-off, also recorded with `tasks.py assume` for human review. `PATCH ALL {"status":"CANCELLED"}` followed by `{"status":"CONFIRMED"}` now hides every override of the series for good. The occurrences the user had moved or edited disappear from the list and from search, while the generated occurrences come back. If the humans reject that assumption, there is an alternative. A THIS delete of an override could be stored as an EXDATE, with the override row deleted. That would remove the ambiguity.
