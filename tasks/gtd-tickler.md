---
title: Tickler and scheduled tasks job
status: in_progress
owner: dell26/agent-4
deps: [gtd-recurrence]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/gtd,server/src/test/kotlin/ch/sonensei/cockpit/gtd,server/src/main/resources/db/migration/gtd]
prio: 1
lease_until: 2026-10-20T19:18:30Z
---
## Goal
Ticklers return to the inbox, scheduled tasks become next actions (SPEC.md §5.1).

## Acceptance criteria
- [ ] Tasks with `ticklerDate` are excluded from all list queries until that date
- [ ] Job at 00:05 user-local time (and an on-read check) moves due ticklers to INBOX and due SCHEDULED tasks to NEXT, publishing ChangeEvents
- [ ] `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.gtd.*'` passes (Postgres via Testcontainers where persistence is involved) using the test clock (advance 2 days → task back in inbox)

## Notes
- ASSUMPTION (2026-10-20 20:52+0200 by dell26/agent-4) [confirmed 2026-10-20 20:58+0200 by Peter Gürtler]: Releasing a SCHEDULED task to NEXT clears its scheduledDate (the existing invariant allows scheduledDate only on SCHEDULED); a released tickler also clears scheduledDate and waiting-for fields since it lands in INBOX
- ASSUMPTION (2026-10-20 20:52+0200 by dell26/agent-4) [confirmed 2026-10-20 20:58+0200 by Peter Gürtler]: The on-read check runs on GET /tasks and GET /tasks/{id}; its failures (e.g. a concurrent run) are logged and never fail the read. The job runs at 00:05 in the user's zone via a trigger that rereads the zone setting for each run; disabled in tests via cockpit.gtd.tickler.scheduler.enabled=false
- ASSUMPTION (2026-10-20 20:52+0200 by dell26/agent-4): Only open (not done) tasks are moved; the list filter hides any task (also done ones in the Logbook) whose tickler date is after today. A scheduled task that is also in a tickler waits for the tickler and then goes to INBOX
