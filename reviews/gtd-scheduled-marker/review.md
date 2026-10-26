# Code Review

Date: 2026-10-26

Task: gtd-scheduled-marker - Today view: keep 'scheduled for today' visible after the scheduled release

Scope: committed changes `git diff db3148b7..HEAD`, excluding `tasks/` (commits 2f3c6703 server, 39543869 web). Uncommitted test-writer changes in the working tree were not reviewed.

Requirements:
- tasks/gtd-scheduled-marker.md (acceptance criteria, recorded assumptions)
- SPEC.md §5.1, §5.2, §5.3, §14
- AGENTS.md project notes (Flyway naming, generated API files, backup coverage, conventions)
- docs/QUALITY.md (report contract)

Git range:
- Branch: main
- HEAD: 3954386931cfa77f316c0645e16bd12e4cb8e918
- Base/start: db3148b74a94943ba417ec5b41b128a287ac0b11
- Commits reviewed: 2

Summary: 0 P0, 0 P1, 0 P2, 1 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | Android Today view still has the gap this task fixes on the web, and no task tracks it**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/gtd/today/TodayViewModel.kt:124`
    - Category: Integration
    - Problem: Android `sectionOf` still puts a task under SCHEDULED only when `scheduledDate <= today`. The server clears `scheduledDate` when it releases the task (it now sets `releasedOn`), so Android's "Scheduled for today" stays empty, which is the bug this task fixes for the web. Android is outside this task's `touches` (and `and-next-today` is done), and no open task covers the Android change.
    - Why it matters: Web and Android now behave differently for the same SPEC.md §5.3 section, and nothing on the board says Android still needs the fix.
    - Suggested fix: Don't change Android in this task. Add a follow-up task (`tasks.py new`) for Android to read `releasedOn` (or use `?scheduled=today`), or record an assumption saying it is deferred.

## Verified without findings

- Criterion 1: there is a new nullable `task.released_on` column in a new migration (`V202610260700__gtd_task_released_on.sql`, named by the module convention, no applied migration edited). `releaseDue` sets it to today for SCHEDULED->NEXT releases and clears it for tickler->INBOX releases. `move` clears it whenever the list changes. Complete, uncomplete, PATCH and project changes keep it. `createNextOccurrence` does not copy it, so it is not carried into recurrences. `api/openapi.yaml` and the generated web client (`list-tasks.ts`, `task-response.ts`, `completed-task-response.ts`) match the new `scheduled` parameter and `releasedOn` field. Server tests in `TicklerWebTests` cover setting the marker, keeping it, clearing it on list change and on tickler release, the `scheduled=today` filter (also combined with `list` and `completed`, case-insensitive), and the 400 Problem Details response for an unknown value.
- Backup (SPEC.md §14): `releasedOn` is exported, imported with the extra column and placeholder, and read back from rows. `TaskBackup.releasedOn` defaults to `null`, so older backups still restore. The backup fixture sets the field and the export field-set test lists it.
- Criterion 2: the Today view loads `scheduled: 'today'` and no longer filters on `scheduledDate` in the client. `matchesTask` follows the server predicate (NEXT with `releasedOn === today`, or SCHEDULED dated up to today, still subject to the tickler rule), so live updates and optimistic changes keep the section consistent. The optimistic `move` keeps `releasedOn` only when the list is unchanged, as the server does. Vitest covers the view request, a released task shown under "Scheduled for today", completing it, `matchesTask` boundaries and the optimistic move.
- The edit to `web/src/app/features/gtd/data/task-store.ts`, outside the task's `touches`, is small, necessary, and recorded as an assumption.
- Loading, error and retry paths of the Today view are unchanged. The tests were updated to the new query and still exercise them.
- Security: the new query parameter is validated against a single allowed value, and no new data exposure or logging was added.
- Evidence: `tasks.py verify gtd-scheduled-marker` shows no criterion recorded yet. This is expected before the parent worker records evidence and is not a finding.

## Review conclusion

No material issues found.
