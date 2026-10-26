---
title: Today view: keep 'scheduled for today' visible after the scheduled release
status: in_progress
owner: felix/agent-1
deps: [web-next-today]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/gtd,web/src/app/features/gtd/today]
prio: 2
lease_until: 2026-10-26T13:36:03Z
releases: 2
extended: 
hold: 
---
## Goal
TaskService.releaseDue moves SCHEDULED tasks to NEXT and clears scheduledDate, so the Today view (SPEC.md §5.3 'scheduled for today') can't tell which Next actions were scheduled for today.

## Acceptance criteria
- [ ] Server keeps a marker (e.g. `scheduledDate` kept or a new `releasedOn` field) on released tasks, new Flyway migration if needed, `api/openapi.yaml` regenerated; server tests cover it
- [ ] Today view 'Scheduled for today' shows tasks released today; Vitest covers it; `cd web && npm run check` passes

## Notes
- release by felix/agent-1: Not started. Host felix: JAVA_HOME=/usr/lib/jvm/java-21-openjdk missing, server build/check cannot run; released so a healthy host can take it.
- release by felix/agent-1: Not started. Host felix still has JAVA_HOME=/usr/lib/jvm/java-21-openjdk missing (checked at 545116db); server build/.taskcheck cannot run here. Released for a healthy host.
- hold by felix/agent-1: Host felix: JAVA_HOME=/usr/lib/jvm/java-21-openjdk does not exist (only java-25 installed), so server build and .taskcheck cannot run; a human must fix the JDK on felix (or run this task on another host), then unhold. No work started.
- unhold by Peter Gürtler: JDK issue on felix is fixed
- ASSUMPTION (2026-10-26 08:11+0100 by felix/agent-1): New nullable Task.releasedOn (date the tickler job moved SCHEDULED->NEXT), cleared when the task changes list (move or tickler release), kept on complete/edit/project change; included in backup (optional field, old backups restore with null).
- ASSUMPTION (2026-10-26 08:11+0100 by felix/agent-1): Added GET /tasks?scheduled=today (released to NEXT today OR still SCHEDULED<=today) instead of loading the whole Next list client-side; Today view uses it. Small edit in web/.../gtd/data/task-store.ts (matchesTask + optimistic move) outside the task's touches, needed so live updates match the new filter.
- extended by felix/agent-1: Implementation done and committed (b51b7fab server releasedOn + migration + scheduled=today + openapi regen; 03817d1f web Today view). Code review done (reviews/gtd-scheduled-marker/review.md: only P3 CR-1, handled by follow-up task and-today-released). Test-writer's extra tests committed WIP in a761505c but tests.md not yet written. Left: (1) re-run test-writer to finish tests + write tests.md with coverage; (2) re-run code-reviewer on final code (review predates test commit); (3) verify both criteria; (4) quality.json; (5) sh scripts/check.sh: last run failed/stopped during 'server: ktlint + tests' with no clear error in .tmp/check.log - investigate with cd server && ./gradlew check; (6) done.
- release by felix/agent-1: Implementation done and committed (8c18fe35 server: Task.releasedOn + Flyway migration + GET /tasks?scheduled=today + openapi regen; 7509e6a3 web Today view). Code review in reviews/gtd-scheduled-marker/review.md (only P3 CR-1, moved to follow-up task and-today-released). Test-writer's extra tests committed WIP in 9a159199, tests.md NOT written. Left: (1) investigate server check hang: sh scripts/check.sh hangs in 'server: ktlint + tests' (killed by timeout after ~9.5 min, no error in log, twice on host felix) - run cd server && ./gradlew check --info and check whether a test from 9a159199 hangs (e.g. a clock/scheduler wait) or it is host-specific; (2) re-run test-writer to finish tests + write tests.md with coverage; (3) re-run code-reviewer on final code (review predates 9a159199); (4) verify both criteria; (5) quality.json; (6) done.
- release by felix/agent-1: Implementation done and committed (c141a864 server: Task.releasedOn + Flyway migration + GET /tasks?scheduled=today + openapi regen; 00e5aaf0 web Today view). Code review in reviews/gtd-scheduled-marker/review.md (only P3 CR-1 -> follow-up task and-today-released). Test-writer's extra tests committed WIP in ddb2ec2f; 456ac139 adds fsync=off etc. to PostgresTestBase (shared file outside touches - keep only if it helps the check, else revert) and drops a redundant !! in TicklerWebTests. tests.md NOT written, no criterion verified, no quality.json. Left: (1) sh scripts/check.sh hung in 'server: ktlint + tests' twice on host felix (~9.5 min, no error): run cd server && ./gradlew check --info and find whether a test from ddb2ec2f hangs or it is host/Docker-specific; (2) test-writer -> tests.md with coverage; (3) code-reviewer again on final code; (4) verify both criteria; (5) quality.json; (6) done.
