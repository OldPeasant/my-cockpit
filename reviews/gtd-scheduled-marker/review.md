# Code Review

Date: 2026-10-26

Task: gtd-scheduled-marker - Today view: keep 'scheduled for today' visible after the scheduled release

Scope: committed changes `git diff ad5a240b..ad667a0d`. `tasks/` and `reviews/` were read only as context. This review covers the final code: production commits c141a864 (server) and 00e5aaf0 (web), test commits ddb2ec2f, 456ac139 and ad667a0d, the revert 28e44631, and the merge e86e723f. It replaces the earlier review of an older revision.

Requirements:
- tasks/gtd-scheduled-marker.md (acceptance criteria and the recorded assumptions in Notes)
- SPEC.md §5.1, §5.3, §14 (as referenced by the task and the code)
- AGENTS.md project notes (Flyway naming, generated API files, backup coverage, scope/touches)
- docs/QUALITY.md (report contract)
- reviews/gtd-scheduled-marker/tests.md (test evidence, used as context)

Git range:
- Branch: main
- HEAD: ad667a0ddfee9da11be102abf8b5dd7306504369
- Base/start: ad5a240be3b61f185d8fe8d49d2a762a5b01cb7c
- Commits reviewed: 8 (c141a864, 00e5aaf0, 3b7fb520, ddb2ec2f, 456ac139, e86e723f, 28e44631, ad667a0d)
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

## Verified without findings

- **Criterion 1 (server marker):**
    - The new nullable `task.released_on` column is added by a new migration, `gtd/V202610260700__gtd_task_released_on.sql`. It follows the module naming convention, and no applied migration was edited.
    - `TaskService.releaseDue` sets `releasedOn = today` (in the user's zone) on SCHEDULED->NEXT releases. It clears the marker on tickler->INBOX releases.
    - `move` clears the marker whenever the list changes. Complete, uncomplete, PATCH (`apply` cannot change the list) and project changes keep it.
    - `createNextOccurrence` does not copy the marker.
    - `GET /tasks?scheduled=today` accepts only `today` (case-insensitive), and any other value returns a 400 Problem Details error on the `scheduled` field. The predicate is NEXT with `releasedOn = today`, or SCHEDULED with `scheduledDate <= today`. It combines with the existing tickler, completed and trash predicates.
    - `api/openapi.yaml` and the generated web client (`list-tasks.ts`, `task-response.ts`, `completed-task-response.ts`) match the controller. `OpenApiContractTest` guards this.
    - Server tests in `TicklerWebTests` (12 new) cover:
        - setting the marker, including the user-zone day boundary;
        - keeping it on complete, uncomplete, edit and moves within Next;
        - clearing it when the task leaves Next and on a tickler release;
        - no marker on a manual move to Next, and none on a recurrence's next occurrence;
        - trashed tasks being excluded;
        - the filter combined with other filters;
        - the 400 response.
- **Backup (SPEC.md §14):** `releasedOn` is exported, imported (column count and placeholders match: 24/24) and read back. `TaskBackup.releasedOn` defaults to `null`, so older backups still restore. A test shows this, and the fixture round trip includes the field.
- **Criterion 2 (web Today view):**
    - The view loads `{ scheduled: 'today' }` and no longer filters on `scheduledDate` in the client.
    - `matchesTask` mirrors the server predicate, so live updates and optimistic changes keep the section consistent.
    - The optimistic `move` keeps `releasedOn` only when the list is unchanged, as the server does, and rolls back on error.
    - The view still dedupes a released task that is also due today, so it shows only under "Due today".
    - Vitest covers these behaviors. A focused run of `today-view.spec.ts` and `task-store.spec.ts` during this review gave 74/74 passed.
- **Scope:** the small edit to `web/src/app/features/gtd/data/task-store.ts`, outside the task's `touches`, is necessary and recorded as an assumption. The shared `PostgresTestBase` change from 456ac139 was reverted in 28e44631, and the net diff leaves `server/src/test/.../support/` unchanged. The only remaining change in 456ac139 removes a redundant `!!` in `TicklerWebTests`. It compiles, and tests.md reports a passing run.
- **Earlier CR-1** (Android Today view lacks the same fix): tracked by the follow-up task `tasks/and-today-released.md` (deps on this task, touches Android only). No action needed in this task.
- **Loading, error and retry paths** of the Today view are unchanged, and the updated tests still exercise them with the new query.
- **Security:** the new query parameter is validated against one allowed value. The change adds no new data exposure, logging, authorization change or unsafe query construction (the JPA Criteria API is used).
- **Evidence:** `tasks.py verify gtd-scheduled-marker` shows both criteria still unrecorded. The parent records them before `done`. tests.md reports a full server `./gradlew check` pass at 28e44631, focused gtd server tests plus ktlint passing after the ad667a0d test addition, and `cd web && npm run check` passing. ad667a0d changes only a test file and tests.md.

## Review conclusion

No material issues found.
