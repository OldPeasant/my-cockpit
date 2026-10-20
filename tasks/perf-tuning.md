---
title: Performance baseline and tuning
status: todo
owner:
deps: [p6-smoke]
touches: [server/src/test/kotlin/ch/sonensei/cockpit/perf,server/src/main/resources/db/migration/perf,docs/perf.md]
prio: 3
---
## Goal
Fast with realistic data volumes.

## Acceptance criteria
- [ ] Seed 10k tasks, 2k notes, 50k mail headers, 5k events; p95 of list/search endpoints < 200 ms measured in a repeatable test (`external` tag); indexes added as migrations; results in docs/perf.md

## Notes
