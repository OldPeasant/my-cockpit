---
title: Performance baseline and tuning
status: in_progress
owner: dell26/agent-1
deps: [p6-smoke]
touches: [server/src/test/kotlin/ch/sonensei/cockpit/perf,server/src/main/resources/db/migration/perf,docs/perf.md]
prio: 3
lease_until: 2026-10-26T14:43:32Z
---
## Goal
Fast with realistic data volumes.

## Acceptance criteria
- [ ] Seed 10k tasks, 2k notes, 50k mail headers, 5k events; p95 of list/search endpoints < 200 ms measured in a repeatable test (`external` tag); indexes added as migrations; results in docs/perf.md

## Notes
- ASSUMPTION (2026-10-26 15:00+0100 by dell26/agent-1): p95 is measured in-process through MockMvc (security filters, controller, service, Postgres; no network/TLS/Caddy), 10 warm-up + 50 sequential samples per endpoint; network latency is deployment-specific and not part of the server baseline
- ASSUMPTION (2026-10-26 15:00+0100 by dell26/agent-1): Tuning touched core search (SearchDocumentRepository.fullText: materialized tsquery CTE, float recency boost) outside the task's touches; it was the only endpoint over 200 ms and the change keeps the ranking formula
- ASSUMPTION (2026-10-26 15:00+0100 by dell26/agent-1): The 200 ms assertion runs on whatever machine executes integrationTest; on the shared agent machine timings vary up to ~2x, worst p95 measured is ~120 ms, so the margin is kept rather than loosening the limit
- ASSUMPTION (2026-10-26 15:04+0100 by dell26/agent-1): The mail_message_sort index migration lives in db/migration/perf/ (module 'perf'), as the task's touches name, not in mail/; it passes MigrationConventionTests
