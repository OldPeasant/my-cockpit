---
title: Contacts API
status: in_progress
owner: dell26/agent-2
deps: [p0-items-links,p0-search-core,p0-trash-core,files-api]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/contacts,server/src/test/kotlin/ch/sonensei/cockpit/contacts,server/src/main/resources/db/migration/contacts]
prio: 2
lease_until: 2026-10-21T02:27:26Z
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
