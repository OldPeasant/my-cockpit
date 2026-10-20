---
title: Header sync and threading
status: todo
owner:
deps: [mail-imap]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/mail/sync,server/src/test/kotlin/ch/sonensei/cockpit/mail/sync]
prio: 1
---
## Goal
Keep the header cache in sync (SPEC.md §7.2).

## Acceptance criteria
- [ ] Initial sync of the configured window (default 12 months), older on demand; incremental via UIDNEXT/flags, CONDSTORE/QRESYNC when supported; UIDVALIDITY change → resync folder
- [ ] Threading by References/In-Reply-To with subject fallback
- [ ] `external` GreenMail tests: new/deleted/flag-changed messages reconciled; unit tests for threading

## Notes
