---
title: Persistence conventions: base entity, UUIDv7, clock, migrations layout
status: in_progress
owner: R1963MACOS/agent-2
deps: [p0-server-deps]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/core/persistence,server/src/test/kotlin/ch/sonensei/cockpit/core/persistence,server/src/main/resources/db/migration/core]
prio: 1
lease_until: 2026-10-20T09:06:14Z
---
## Goal
Shared persistence building blocks every module uses (SPEC.md §4.2).

## Acceptance criteria
- [ ] `BaseEntity` with UUIDv7 `id` (generated server-side, or accepted from the client for idempotent creates), `createdAt`, `updatedAt` (UTC `timestamptz`), `version` (`@Version`); `SoftDeletable` with `deletedAt`
- [ ] An injectable `Clock` bean is used for all timestamps; tests can substitute a fixed/mutable clock
- [ ] Migration convention documented in `docs/ARCHITECTURE.md`: `db/migration/<module>/V<yyyyMMddHHmm>__<module>_<desc>.sql`, never edit applied ones; first migration enables `pg_trgm` and creates nothing else module-specific
- [ ] Tests: UUIDv7 ids are time-ordered; optimistic locking raises a conflict on a stale version; soft-deleted rows are excluded by the repository helper; `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.persistence.*'` passes (Postgres via Testcontainers where persistence is involved)

## Notes
