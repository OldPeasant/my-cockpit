---
title: Full-text search in PDFs and text files
status: in_progress
owner: dell26/agent-2
deps: [files-preview,p0-search-core]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/files/fulltext,server/src/test/kotlin/ch/sonensei/cockpit/files/fulltext]
prio: 3
lease_until: 2026-10-26T12:51:48Z
---
## Goal
Search file contents (SPEC.md §4.4).

## Acceptance criteria
- [ ] Text extracted (PDFBox) in a background job, size-limited, indexed; search finds it
- [ ] `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.files.fulltext.*'` passes (Postgres via Testcontainers where persistence is involved)

## Notes
