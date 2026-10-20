# Test Report

Date: 2026-10-20

Task: gtd-model (GTD data model)

Scope: `git diff 8b76424aa5589325952279902ed930351d90ee0a..e7e1ba888d818aed395ba1dba3b85008fe834b5f -- server` (one task commit, e7e1ba8), plus the uncommitted test changes listed below.

Requirements:
- `tasks/gtd-model.md` acceptance criteria and notes (assumptions on soft delete, context-name uniqueness, id references)
- SPEC.md §5.1, §5.2 (fields, enums, rules), §4.2 (UUIDv7, optimistic locking, soft delete), §4.3 (item resolvers, deep links)
- docs/ARCHITECTURE.md "Coverage" and "Persistence conventions"
- AGENTS.md project notes

## Test files changed

- `server/src/test/kotlin/ch/sonensei/cockpit/gtd/GtdItemResolverTests.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/gtd/GtdRepositoryTests.kt`

## Tests added or updated

Count: 16 (14 new tests, 2 existing tests extended)

New in `GtdItemResolverTests` (5 -> 8 tests):
- `every open task list has a readable subtitle` (all 5 `TaskList` labels, deep link `/tasks/{id}`)
- `every project status has a readable subtitle` (all 5 `ProjectStatus` labels, deep link `/projects/{id}`)
- `a restored task and project resolve again`
- extended `missing and soft-deleted items do not resolve` with `exists()` on a soft-deleted project

New in `GtdRepositoryTests` (11 -> 22 tests):
- `values outside the project status, energy, creativity and recurrence mode enums are rejected` (also null and lower-case list)
- `every enum value round-trips through the database` (all values of all five enums; `findByStatus...` per status)
- `a zero time estimate and a due date without a time are accepted` (boundary of `task_time_estimate`, `task_due_time_needs_date`)
- `a project's area and next step must exist` (FKs `project.area_id`, `project_next_task`)
- `changing a task's contexts replaces the stored set` (element collection update)
- `an update bumps the version and updated time, and a stale update is rejected` (optimistic locking, task)
- `processing a task and editing areas, contexts and checklist items are persisted` (INBOX -> NEXT, updates of every mutable column)
- `a stale project update is rejected` (optimistic locking, project)
- `a restored task is back in normal reads and list queries`
- `tasks with equal positions keep creation order` (id tie-break)
- `an unknown context name is not found`
- extended `list, project and due queries can use the indexes` with `task_scheduled_date`, `task_tickler_date`, `task_completed_at`, `project_area`, `project_due_date`

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 Flyway migration for task, project, area, context, task_context, checklist_item with §5.2 fields, FKs, indexes | all `GtdRepositoryTests` (full-field round trips; `references to missing rows ...`; `a project's area and next step must exist`; `deleting a task ...`; `deleting a project, area or context ...`; `values outside ... enums are rejected`; `a zero time estimate ...`; `list, project and due queries can use the indexes`) | `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.gtd.*'` (Postgres 16 via Testcontainers) | Pass |
| AC2 Enums list / energy / creativity / project status / recurrence mode | `every enum value round-trips through the database`, `values outside the ... enums are rejected`, `references to missing rows and values outside the enums are rejected` | same | Pass |
| AC3 TASK and PROJECT `ItemResolver`s registered, deep links `/tasks/{id}`, `/projects/{id}` | `TASK and PROJECT resolvers are registered`, `a task resolves ...`, `a done task resolves as done`, `a project resolves ...`, `every open task list has a readable subtitle`, `every project status has a readable subtitle`, `a restored task and project resolve again`, `missing and soft-deleted items do not resolve` | same | Pass |
| AC4 Repository tests on Postgres: save/load all fields | `a task with every field set ...`, `a project with every field set ...`, `areas, contexts and checklist items ...`, `processing a task and editing ...`, `changing a task's contexts ...` | same | Pass |
| AC4 soft delete excluded | `soft-deleted tasks and projects are excluded from normal reads`, `a restored task is back in normal reads ...`, `missing and soft-deleted items do not resolve` | same | Pass |
| AC4 `./gradlew test --tests 'ch.sonensei.cockpit.gtd.*'` passes | 30 tests (8 + 22) | same | Pass (30 tests, 0 failures, 0 skipped) |
| SPEC §4.2 optimistic locking (version) for GTD entities | `an update bumps the version ...`, `a stale project update is rejected` | same | Pass |
| SPEC §5.2 "tasks may stay in INBOX with partial data" | `a new task defaults to the inbox with only a title` (existing) | same | Pass |

## Focused test execution

Command:

`cd server && ./gradlew test --tests 'ch.sonensei.cockpit.gtd.*' ktlintCheck`

Result:

Pass (GtdItemResolverTests 8/8, GtdRepositoryTests 21/21 at that point; the 22nd test was added afterwards and passed in the full runs below; ktlint clean)

## Full test execution

Command:

`cd server && ./gradlew ktlintCheck cleanTest test jacocoTestReport`

Result:

Pass: 75 suites, 900 tests, 0 failures, 0 errors, 0 skipped (gtd: 8 + 22). Run 2026-10-20 15:21:30 to 15:23:35 +02:00, BUILD SUCCESSFUL.

## .taskcheck

Result:

Pass (`sh scripts/check.sh` -> "== all checks passed", run after the final test changes)

## Bugs found

None. No production defect was found; all new tests passed against e7e1ba8 without production changes.

## Coverage

Status: verified

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured separately. Every scoped file is new in this task (all lines added), so whole-file coverage equals changed-line coverage for these files.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `gtd-model`; base 8b76424aa5589325952279902ed930351d90ee0a (from the parent worker); measured HEAD e7e1ba888d818aed395ba1dba3b85008fe834b5f; task-owned commit e7e1ba8 (the only commit in `8b76424..HEAD`), matches the task's `touches`.
- Source/test snapshot identifier (including uncommitted inputs): production = HEAD e7e1ba8 (no uncommitted production changes); uncommitted tests: `GtdItemResolverTests.kt` sha256 ade2f921b1d4e39108bfb0830e96660c9dc95e042c886d8d57eaf3dd883b0c40, `GtdRepositoryTests.kt` sha256 d1c01fdddf5ca806192011945fb10890f004bd596ffe3f154c5159ef4f71882d (hashed right after the final run).
- Exact included files: `server/src/main/kotlin/ch/sonensei/cockpit/gtd/domain/{Area,ChecklistItem,Context,GtdEnums,Project,Task}.kt`, `server/src/main/kotlin/ch/sonensei/cockpit/gtd/infra/{GtdItemResolvers,GtdRepositories}.kt`. Excluded: `server/src/main/resources/db/migration/gtd/V202610201311__gtd_model.sql` (SQL, not instrumentable by JaCoCo; exercised by every Postgres test through Flyway and directly by the constraint/index tests). No renamed or deleted files.
- Provider/version, command, working directory, and test selection: JaCoCo (Gradle `jacoco` plugin, project default version); `./gradlew ktlintCheck cleanTest test jacocoTestReport`; working directory `server/`; full server test suite.
- Run start/completion time and exit status: 2026-10-20T15:21:30+02:00 to 15:23:35+02:00; BUILD SUCCESSFUL (exit 0).
- Generated report paths and report hashes/run identifier: `server/build/reports/jacoco/test/jacocoTestReport.xml` (written 15:23:35.37, sha256 0529e612b26bba18d706660206ed4fe4b8675eefc5394254f2146758611f6e7c); `server/build/jacoco/test.exec` (written 15:23:33.76).
- Evidence outputs are fresh and inputs stayed unchanged: `cleanTest` forced the test task (and its exec data) to rerun; report timestamps fall inside the run; test file hashes taken after the run match the final content; no production file changed.
- Final run after latest test additions and production fixes: yes (no production fixes were made).

Values below use covered/total (percentage); zero-denominator metrics are N/A. JaCoCo has no separate statement counter; "Statements" shows the INSTRUCTION counter.

| Changed file | Statements (instructions) | Branches | Functions (methods) | Lines |
|---|---:|---:|---:|---:|
| `server/src/main/kotlin/ch/sonensei/cockpit/gtd/domain/Area.kt` | 46/46 (100%) | N/A (0/0) | 7/7 (100%) | 8/8 (100%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/gtd/domain/ChecklistItem.kt` | 69/69 (100%) | N/A (0/0) | 10/10 (100%) | 12/12 (100%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/gtd/domain/Context.kt` | 59/62 (95.2%) | N/A (0/0) | 8/9 (88.9%) | 10/10 (100%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/gtd/domain/GtdEnums.kt` | 143/143 (100%) | N/A (0/0) | 5/5 (100%) | 18/18 (100%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/gtd/domain/Project.kt` | 88/88 (100%) | N/A (0/0) | 19/19 (100%) | 15/15 (100%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/gtd/domain/Task.kt` | 178/178 (100%) | 2/2 (100%) | 42/42 (100%) | 27/27 (100%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/gtd/infra/GtdItemResolvers.kt` | 124/124 (100%) | 18/18 (100%) | 8/8 (100%) | 38/38 (100%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/gtd/infra/GtdRepositories.kt` | N/A (0/0) | N/A (0/0) | N/A (0/0) | N/A (0/0) |
| **Aggregate (summed counters, 8 Kotlin files)** | 707/710 (99.6%) | 20/20 (100%) | 99/100 (99.0%) | 128/128 (100%) |

Aggregate excludes the SQL migration (not instrumentable). `GtdRepositories.kt` contains only Spring Data interfaces (no bytecode with instructions); its derived queries are exercised by the repository tests.

Before the added tests (fresh run at 15:21:01, same command without ktlintCheck): aggregate instructions 665/710 (93.7%), methods 88/100 (88.0%), lines 127/128 (99.2%); the resolver label branches for WAITING/SOMEDAY/SCHEDULED and most project statuses, and the setters of `Task.list`, `Area`, `ChecklistItem`, `Context`, were unexercised.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `server/src/main/kotlin/ch/sonensei/cockpit/gtd/domain/Context.kt:14` (1 method, 3 instructions) | Kotlin-generated getter/accessor of `name` not called directly; the value itself is verified through `findByNameIgnoreCase` and uniqueness tests | Justified gap, trivial accessor |
| `server/src/main/resources/db/migration/gtd/V202610201311__gtd_model.sql` | Schema, constraints, FKs, indexes | Not instrumentable; covered behaviorally by constraint, FK, cascade/set-null and index tests |

## Unable to test

- Whether the indexes are actually chosen by the planner for list/project/due queries: only their existence is asserted (`pg_indexes`). Plan choice depends on table statistics; not worth a brittle EXPLAIN test at this stage. No follow-up needed for this task.
- Backup/restore round trip for GTD entities (AGENTS.md: every user-facing entity must be covered by backup/restore) and link/search integration beyond the resolvers are not in this task's acceptance criteria; they belong to later tasks.

## Summary

Tests and task-scoped coverage are sufficient; no production defects found.
