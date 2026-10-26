---
title: Full-text search in PDFs and text files
status: in_progress
owner: dell26/agent-2
deps: [files-preview,p0-search-core]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/files/fulltext,server/src/test/kotlin/ch/sonensei/cockpit/files/fulltext]
prio: 3
lease_until: 2026-10-26T14:06:30Z
extended: dell26/agent-2
---
## Goal
Search file contents (SPEC.md §4.4).

## Acceptance criteria
- [ ] Text extracted (PDFBox) in a background job, size-limited, indexed; search finds it
- [ ] `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.files.fulltext.*'` passes (Postgres via Testcontainers where persistence is involved)

## Notes
- ASSUMPTION (2026-10-26 13:36+0100 by dell26/agent-2): Extracted text is cached in a new table file_text (not backed up: rebuilt from blobs after a restore); FilesService.indexed(file) now re-reads it so rename/move/restore keep the body. Small edit outside touches (FilesService, build.gradle.kts pdfbox line, test application.properties scheduler switch).
- ASSUMPTION (2026-10-26 13:36+0100 by dell26/agent-2): Limits (cockpit.files.fulltext.*): max-file-size 20MB, max-chars 100000 (SearchIndexer body limit), max-pages 500; job every 30s (initial 1 min). Supported: application/pdf and the text types the preview treats as text.
- ASSUMPTION (2026-10-26 14:07+0100 by dell26/agent-2): Review fixes: extraction runs on a dedicated single 'file-text' thread (skips overlapping ticks, stops starting batches after 5 min); StackOverflowError -> FAILED, other VirtualMachineErrors propagate; blob read errors are not stored but retried after 15 min (in-memory timer); FilesService delete/restore flush before touching search documents.
- extended by dell26/agent-2: Implementation and 2nd-round review fixes (CR-1 interrupt stores nothing, CR-2 file row lock in FilesService.indexed, CR-3 Error logged + skip after repeats) committed in 4b3107c9e; fulltext tests pass. Left: fixer report for round-2 fixes (fixes.md only covers round 1; have fixer verify/document CR-1..3 of current review.md), re-run test-writer + code-reviewer on final code, tasks.py verify #1/#2, sh scripts/check.sh, quality.json, done.
