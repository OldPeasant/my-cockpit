# Code Review

Date: 2026-10-20

Task: gtd-recurrence - Recurring tasks

Round: 2 (final revision `288b6ddbe03c313a1ef573bab7574f3facc38058`)

Scope: `git diff c4fcbf7955734df6ca768627aff7791b38755d70..288b6ddbe03c313a1ef573bab7574f3facc38058 -- server` (base = `git merge-base HEAD origin/main` after the rebase on main). Round 2 focuses on the CR-1 fix (`288b6dd`) and a short re-check of the final code against the acceptance criteria.

Requirements:
- `tasks/gtd-recurrence.md` acceptance criteria 1-3 and recorded assumptions #1-#6
- `SPEC.md` §5.2 (task data model, recurrence rule)
- `docs/ARCHITECTURE.md`, `AGENTS.md` project conventions (module boundaries, Flyway migration naming, Problem Details errors, logging)
- Parent instructions (round 2 scope)
- Round-1 review (this file, previous version), `reviews/gtd-recurrence/fixes.md`, round-2 test report `reviews/gtd-recurrence/tests.md`

Git range:
- Branch: main
- HEAD: 8299db1 (report-only commit on top of the reviewed code revision 288b6dd)
- Base/start: c4fcbf7955734df6ca768627aff7791b38755d70
- Commits reviewed: 4 task commits touching `server/` or reports (e2da2ae implementation, 39d9765 edge-case tests and test report, d12cd98 review report, 288b6dd fix CR-1); 8299db1 touches only `reviews/`
- Uncommitted changes: none at review start

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Round-1 findings

- [x] **CR-1 | Comment on the probe day contradicts the actual UNTIL behavior** (P3, resolved)
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/gtd/domain/TaskRecurrence.kt:15`
    - Verification: `288b6dd` changes only this comment line. It now gives a time-of-day part such as `BYHOUR=9` as the example. That matches the test asserting `problem("FREQ=DAILY;BYHOUR=9")` is `"is not a valid RRULE"`, and it no longer contradicts the test that accepts `UNTIL=20261020T120000Z`. No executable code changed.

## Notes (no finding)

- Rebase check: the `server/` diff `c4fcbf7..39d9765` has the same `git patch-id` (`79cdd156...`) as the `server/` diff `a2a9d346..f169e67` reviewed in round 1. The only code change since round 1 is therefore the one comment line in `288b6dd`.
- AC1, AC2: the conclusions from round 1 still hold for the final code. That covers ical4j validation with a frequency check, the expansion probe and COUNT>=1, and the rule that `recurrenceRule` and `recurrenceMode` are set together (400 Problem Details otherwise). Completion creates exactly one occurrence, guarded by `next_occurrence_id` inside the versioned transaction. FIXED seeds on due date, then scheduled date, then the completion day. AFTER_COMPLETION uses the completion day in the user's time zone. Nothing is generated in advance, and uncompleting does not create duplicates.
- AC3: the round-2 test report records `./gradlew ... test --tests 'ch.sonensei.cockpit.gtd.*'` (Postgres 16 via Testcontainers) on `288b6dd` with BUILD SUCCESSFUL, 192 tests and 0 failures, plus a passing `sh scripts/check.sh`. I did not re-run the suite in round 2, because only a comment changed since my round-1 run passed on identical code.
- `python tasks.py verify gtd-recurrence` still shows all three criteria unverified. The parent worker must record evidence before `done`.
- Minor: the Requirements line of `tests.md` cites assumptions "#1-#5", but the task has six recorded assumptions. This does not affect the test results.
- `reviewed_revision` in `quality.json` should name `288b6ddbe03c313a1ef573bab7574f3facc38058`. Report-only commits on top of it keep the evidence fresh.

## Review conclusion

No material issues found.
