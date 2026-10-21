---
title: Contact detail aggregation and autocomplete
status: in_progress
owner: dell26/agent-1
deps: [contacts-api,mail-read-api,cal-local-api,gtd-tasks-api]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/contacts/aggregate,server/src/test/kotlin/ch/sonensei/cockpit/contacts/aggregate]
prio: 2
lease_until: 2026-10-21T22:09:33Z
---
## Goal
Everything about a person (SPEC.md §8.3).

## Acceptance criteria
- [x] `GET /api/v1/contacts/{id}/related`: recent mails from/to, upcoming events with them, Waiting For items
  - verified (2026-10-21 23:53+0200 by dell26/agent-1): cd server && ./gradlew test --tests 'ch.sonensei.cockpit.contacts.aggregate.*' (Testcontainers Postgres) on 314a570ad -> ContactAggregateWebTests+EdgeCaseTests: GET /contacts/{id}/related returns mails from/to/cc newest first (Trash/Spam/Drafts/detached/duplicates excluded, cap 20), upcoming attendee occurrences incl. recurring/all-day (cancelled/past/>90d excluded, cap 20), Waiting For tasks by contact across pages; unknown/trashed contact 404 -> pass
- [ ] `GET /api/v1/contacts/autocomplete?q=` merges contacts and recent correspondents
- [ ] `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.contacts.aggregate.*'` passes (Postgres via Testcontainers where persistence is involved)
- [ ] API documented: `api/openapi.yaml` regenerated (`UPDATE_OPENAPI=1 ./gradlew test --tests '*OpenApiContractTest'`) and `OpenApiContractTest` passes without the flag

## Notes
- ASSUMPTION (2026-10-21 23:34+0200 by dell26/agent-1): Mail lookup by address reads mail_message directly via a read-only SQL query in contacts.aggregate (CorrespondenceQuery): the mail module has no address search service and the task's touches exclude mail/. Messages are then loaded through MailReadService.get.
- ASSUMPTION (2026-10-21 23:34+0200 by dell26/agent-1): related: newest 20 mails (From/To/Cc, not Trash/Spam/Drafts, duplicate copies per Message-ID once), next 20 non-cancelled occurrences within 90 days of visible calendars with the contact as attendee, all open Waiting For tasks with waitingForContactId (tickler-hidden ones excluded, like the list).
- ASSUMPTION (2026-10-21 23:34+0200 by dell26/agent-1): autocomplete: contacts first (one entry per email, names starting with q first), then correspondents from the newest 500 matching messages (excluding own account/identity addresses), default 10 / max 50 results; a blank q returns [] instead of 400.
