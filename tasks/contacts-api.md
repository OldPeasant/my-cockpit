---
title: Contacts API
status: in_progress
owner: dell26/agent-1
deps: [p0-items-links,p0-search-core,p0-trash-core,files-api]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/contacts,server/src/test/kotlin/ch/sonensei/cockpit/contacts,server/src/main/resources/db/migration/contacts]
prio: 2
lease_until: 2026-10-21T07:59:02Z
releases: 2
extended: 
---
## Goal
Contacts (SPEC.md §8.3).

## Acceptance criteria
- [ ] Contact entity (SPEC.md §8.3 fields), CRUD with ETag, photo via Files, resolver CONTACT, search, trash
- [ ] vCard import (multi-contact file) and export (ez-vcard)
- [ ] `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.contacts.*'` passes (Postgres via Testcontainers where persistence is involved)
- [ ] API documented: `api/openapi.yaml` regenerated (`UPDATE_OPENAPI=1 ./gradlew test --tests '*OpenApiContractTest'`) and `OpenApiContractTest` passes without the flag

## Notes
- ASSUMPTION (2026-10-21 03:53+0200 by dell26/agent-2): Contact emails/phones/addresses are jsonb arrays of {address|number,label} / {label,street,city,region,postalCode,country}; labels are free text (vCard TYPEs joined with ', ', pref/internet dropped).
- ASSUMPTION (2026-10-21 03:53+0200 by dell26/agent-2): displayName is derived when missing/cleared (given+family name, else organization, else first email); a contact without any of these is a 400. The API creates only LOCAL contacts; source/externalId are read-only.
- ASSUMPTION (2026-10-21 03:53+0200 by dell26/agent-2): vCard import: each card in its own transaction, invalid cards are skipped and listed (no 400 for the whole file); embedded photos stored in /Attachments (no Contacts subfolder: SpecialFolder belongs to the files module); photo URLs and year-less birthdays ignored; max 20 MB / 10000 cards.
- ASSUMPTION (2026-10-21 03:54+0200 by dell26/agent-2): vCard export: GET /contacts/export (optional ids) as vCard 3.0 with UID urn:uuid:<id>; photos are not embedded (FilesService exposes no blob read to other modules).
- ASSUMPTION (2026-10-21 03:54+0200 by dell26/agent-2): GET /contacts has an optional q filter (substring of display name or email) for recipient autocomplete; photoFileId is validated via the FILE item resolver.
- ASSUMPTION (2026-10-21 04:19+0200 by dell26/agent-2): vCard export rewrites '"' in labels as "'" and CR/LF as spaces, because ez-vcard rejects them in TYPE values (instead of a 500)
- ASSUMPTION (2026-10-21 04:19+0200 by dell26/agent-2): q email matching uses a SQL function contacts_email_matches(jsonb,text) (migration V202610211010) so only address values match, not jsonb keys/labels
- release by dell26/agent-2: Implementation complete (entity/CRUD+ETag/photo/resolver/search/trash, vCard import+export, openapi.yaml + web client regenerated). review.md (re-review: 0 findings) and fixes.md (CR-1..3, TW-1) committed; contacts tests pass (45+, Testcontainers). Last commit a0a62a41 adds one more edge-case test (empty EMAIL/TEL/ADR/ORG dropped), passing. LEFT: run test-writer to write reviews/contacts-api/tests.md (with coverage), re-run code-reviewer on final HEAD (tests changed after review), write quality.json, tasks.py verify criteria 1-4 (run OpenApiContractTest without flag), sh scripts/check.sh, done.
- release by dell26/agent-6: Implementation, tests, tests.md (test-writer), review.md (final re-review, 0 findings, reviewed 029b887d) and fixes.md are all committed; working tree clean at af3bbcd2. LEFT: (1) confirm review/tests evidence is fresh for HEAD via git patch-id (only tests/reviews changed after 029b887d per review.md; re-run code-reviewer only if test code changed after it), (2) write+commit reviews/contacts-api/quality.json (docs/QUALITY.md), (3) tasks.py verify 1-4: run ./gradlew test --tests 'ch.sonensei.cockpit.contacts.*' and OpenApiContractTest without flag, (4) sh scripts/check.sh, (5) done. Note: check.sh on 2026-10-21 failed 1/2855 in core ClockConfigurationTests (flaky: clock truncates to micros, test compares nanos) - unrelated to contacts; re-run, or file a task if it persists.
- ASSUMPTION (2026-10-21 09:38+0200 by dell26/agent-1): Fixed TaskToNoteWebTests/TaskToNoteRollbackTests (outside touches): they used CONTACT as an item type without resolver; switched to HABIT, which still has none
