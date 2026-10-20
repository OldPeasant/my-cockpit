# Code Review

Date: 2026-10-20

Task: gtd-model (GTD data model)

Scope: `git diff 8b76424..706ebcd` (committed changes only; working tree clean at review time)

Requirements:
- `tasks/gtd-model.md` acceptance criteria and recorded assumptions (no FK for `waiting_for_contact_id`; projects soft-deletable, areas/contexts/checklist items hard-deleted; id references instead of JPA associations; case-insensitive unique context names)
- SPEC.md §4.2 (UUIDv7, created/updated/version, soft delete), §4.3 (ItemResolver, deep links), §5.1, §5.2 (fields, enums, rules)
- docs/ARCHITECTURE.md "Persistence conventions", "Conventions", "Coverage"
- AGENTS.md project notes (migration naming, module boundaries, lint)
- Existing code: `core/persistence` (`BaseEntity`, `SoftDeletableEntity`, `SoftDeletableRepository`), `core/items` (`ItemResolver`, `ItemResolverRegistry`, `ItemType`), `core/V202610201400__core_items_link.sql`
- Downstream task files read for scope boundaries only: `gtd-tasks-api`, `gtd-projects-api`, `gtd-tickler`, `backup-contrib-gtd`

Git range:
- Branch: main
- HEAD: 706ebcda44c4c05a4a41ab42e003171ed950e097
- Base/start: 8b76424aa5589325952279902ed930351d90ee0a
- Commits reviewed: 2 (e7e1ba8 implementation; 706ebcd tests and `reviews/gtd-model/tests.md`)

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Verification notes

What was checked (no findings resulted):

- AC1 migration `server/src/main/resources/db/migration/gtd/V202610201311__gtd_model.sql`: name follows `V<yyyyMMddHHmm>__<module>_<desc>.sql`; tables `task`, `project`, `area`, `context`, `task_context`, `checklist_item` exist; every SPEC.md §5.2 field is present (task: all 22 listed fields plus `deleted_at`, base columns; project: title, description_md, area_id, status, next_task_id, due_date, review_date, completed_at, deleted_at; area/context/checklist_item as specified). Every entity table carries `id/created_at/updated_at/version` as `BaseEntity` requires. FKs: task.project_id and project.area_id `set null`, project.next_task_id `set null` (added after `task` exists, so the circular reference is valid), task_context and checklist_item `cascade`; `waiting_for_contact_id` has no FK, as recorded in the assumption. Indexes cover open-list ordering (`task_open_list` matches the derived query's filter and order), project order, due/scheduled/tickler dates, logbook, project status/area/due. CHECK constraints mirror every enum.
- Entity mapping matches the schema (column names, `EnumType.STRING`, `LocalDate`/`LocalTime`/`Instant` types, `@ElementCollection` on `task_context`); `spring.jpa.hibernate.ddl-auto=validate` passes in the Testcontainers runs reported in `tests.md`.
- AC2 enums in `gtd/domain/GtdEnums.kt`: `TaskList`, `Energy`, `Creativity`, `ProjectStatus`, `RecurrenceMode` with exactly the SPEC.md §5.1/§5.2 values.
- AC3 `gtd/infra/GtdItemResolvers.kt`: `TaskItemResolver` and `ProjectItemResolver` are Spring `@Component`s with types TASK/PROJECT, deep links `/tasks/{id}` and `/projects/{id}`, return `null` for missing and soft-deleted rows (via `findActiveById`), in line with the `ItemResolver` contract; registration is asserted through `ItemResolverRegistry`.
- AC4 tests (`GtdRepositoryTests`, `GtdItemResolverTests`) extend `PostgresTestBase` (Postgres 16 Testcontainers), save and reload every field of task and project (including contexts and completed state), areas, contexts and checklist items, and assert soft-deleted tasks/projects are excluded from `findActiveById`, `findAllActive`, and the derived list/project/status queries. Also covered: FK cascade/set-null behavior, enum CHECKs, optimistic locking, restore, case-insensitive context uniqueness. Tests assert behavior, not internals. Test classes run sequentially in one JVM, so the `@BeforeEach` table cleanup does not race.
- Architecture: packages `gtd.domain`/`gtd.infra` follow the convention; the gtd module depends only on `core` building blocks; no other module's repositories are used; ids, time and soft delete use the `core.persistence` building blocks; no `Instant.now()`; no logging of content.
- Out of scope, and correctly left for later tasks: tickler exclusion from list queries (`gtd-tickler`), API/search/ChangeEvents/trash (`gtd-tasks-api`, `gtd-projects-api`), backup round trip (`backup-contrib-gtd`).
- Evidence state: `python tasks.py verify gtd-model` shows all four criteria still unrecorded. This is expected before step 7 of the quality workflow; the parent worker must record them (AC4 with the `./gradlew test --tests 'ch.sonensei.cockpit.gtd.*'` Testcontainers run) before `done`.

## Review conclusion

No material issues found.
