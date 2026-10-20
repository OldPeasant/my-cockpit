# Code Review

Date: 2026-10-20

Task: gtd-recurrence - Recurring tasks

Scope: `git diff a2a9d346fb51ae2a0406191b8e3bb589f58cf497..HEAD -- server` (task commits 707e71b and f169e67; the other commits in the range touch no `server/` files of this task)

Requirements:
- `tasks/gtd-recurrence.md` acceptance criteria 1-3 and recorded assumptions #1-#6 (`python tasks.py assumptions`)
- `SPEC.md` §5.2 (task data model, recurrence rule)
- `docs/ARCHITECTURE.md`, `AGENTS.md` project conventions (module boundaries, Flyway migration naming, Problem Details errors, logging)
- Parent instructions (production and test files named in the request)
- Test report `reviews/gtd-recurrence/tests.md`

Git range:
- Branch: main
- HEAD: f169e677b61b1cf612c0537ff2d42aee27acb56f
- Base/start: a2a9d346fb51ae2a0406191b8e3bb589f58cf497
- Commits reviewed: 2 (707e71b gtd-recurrence: RRULE validation and next occurrence on completion; f169e67 gtd-recurrence: edge-case tests and test report)
- Uncommitted changes: none at review start

Summary: 0 P0, 0 P1, 0 P2, 1 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | Comment on the probe day contradicts the actual UNTIL behavior**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/gtd/domain/TaskRecurrence.kt:14`
    - Category: Quality
    - Problem: The comment gives "an UNTIL with a time cannot be compared with days" as the example of a rule the probe rejects. But ical4j accepts an UNTIL with a UTC time, and the test `an UNTIL with a UTC time, as calendar clients write it, is accepted and ends the series by day` (`TaskRecurrenceTests.kt:75`) asserts that `problem("FREQ=DAILY;UNTIL=20261020T120000Z")` is `null`. The rule the probe actually rejects is a time-of-day part such as `BYHOUR` (`TaskRecurrenceTests.kt:83`).
    - Why it matters: A maintainer who trusts the comment could add a separate check that rejects UNTIL date-times, which calendar clients write all the time.
    - Suggested fix: Change the example in the comment to a time-of-day part such as `BYHOUR=9`.

## Notes (no finding)

- AC1: `validate` runs `TaskRecurrence.problem` (ical4j `Recur<LocalDate>` parse, frequency check, expansion probe, COUNT>=1) and requires `recurrenceRule` and `recurrenceMode` together. Errors go out as 400 Problem Details through `InvalidRequestException`, and the web tests cover them.
- AC2: `complete` creates the occurrence only when `completedAt` and `nextOccurrenceId` are both null, inside the same versioned transaction. A stale If-Match or a concurrent complete fails the optimistic lock, which rolls back the new occurrence too. FIXED seeds on due date, then scheduled date, then the completion day. AFTER_COMPLETION seeds on the completion day in `settings.timeZone()`. No later occurrences are generated in advance. Uncompleting keeps the link, so completing again creates nothing. This matches assumptions #2, #3 and #6.
- The copied occurrence cannot fail `validate` with current data. Contexts are hard-deleted with `on delete cascade`, a project in the Trash is dropped, and `dueTime` only exists together with `dueDate`.
- The migration follows the module naming convention. It only adds a nullable self-referencing FK with `on delete set null`, and no applied migration was edited.
- AC3: I ran `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.gtd.*'` (Postgres via Testcontainers) at HEAD f169e67: BUILD SUCCESSFUL. The suite includes the weekly Monday case (FIXED and AFTER_COMPLETION), the late AFTER_COMPLETION case, and the spring and autumn DST boundaries of the completion day.
- `tasks.py verify gtd-recurrence` does not show any recorded criterion evidence yet. The parent worker still has to record it.
- The scope line of `reviews/gtd-recurrence/tests.md` names commit `cf49e8e`. The rebased production commit is now `707e71b`. The parent should check that `reviewed_revision` in `quality.json` names the final hash.

## Review conclusion

No material issues found.
