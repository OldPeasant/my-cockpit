---
title: Performance baseline and tuning
status: done
owner: dell26/agent-1
deps: [p6-smoke]
touches: [server/src/test/kotlin/ch/sonensei/cockpit/perf,server/src/main/resources/db/migration/perf,docs/perf.md]
prio: 3
lease_until: 
---
## Goal
Fast with realistic data volumes.

## Acceptance criteria
- [x] Seed 10k tasks, 2k notes, 50k mail headers, 5k events; p95 of list/search endpoints < 200 ms measured in a repeatable test (`external` tag); indexes added as migrations; results in docs/perf.md
  - verified (2026-10-26 15:25+0100 by dell26/agent-1): cd server && ./gradlew ktlintCheck integrationTest --tests '*PerfBaselineTests' --rerun (Testcontainers Postgres, @ExternalTest) on 66ac4e18f -> BUILD SUCCESSFUL, 2 tests 0 failures; seed 10k tasks/2k notes/50k mail headers/5k events asserted; all 24 list/search endpoints p95 < 200 ms (worst search common word 66.0 ms, unified inbox threaded 50.1 ms); index V202610261500__perf_mail_unified_sort.sql is a Flyway migration; results and tuning in docs/perf.md

## Notes
- ASSUMPTION (2026-10-26 15:00+0100 by dell26/agent-1): p95 is measured in-process through MockMvc (security filters, controller, service, Postgres; no network/TLS/Caddy), 10 warm-up + 50 sequential samples per endpoint; network latency is deployment-specific and not part of the server baseline
- ASSUMPTION (2026-10-26 15:00+0100 by dell26/agent-1): Tuning touched core search (SearchDocumentRepository.fullText: materialized tsquery CTE, float recency boost) outside the task's touches; it was the only endpoint over 200 ms and the change keeps the ranking formula
- ASSUMPTION (2026-10-26 15:00+0100 by dell26/agent-1): The 200 ms assertion runs on whatever machine executes integrationTest; on the shared agent machine timings vary up to ~2x, worst p95 measured is ~120 ms, so the margin is kept rather than loosening the limit
- ASSUMPTION (2026-10-26 15:04+0100 by dell26/agent-1): The mail_message_sort index migration lives in db/migration/perf/ (module 'perf'), as the task's touches name, not in mail/; it passes MigrationConventionTests
- done by dell26/agent-1: `sh scripts/check.sh` passed in 82s on d23b689a7; criteria verified 1/1
