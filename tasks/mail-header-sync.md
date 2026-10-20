---
title: Header sync and threading
status: in_progress
owner: dell26/agent-1
deps: [mail-imap]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/mail/sync,server/src/test/kotlin/ch/sonensei/cockpit/mail/sync]
prio: 1
lease_until: 2026-10-20T17:05:52Z
---
## Goal
Keep the header cache in sync (SPEC.md §7.2).

## Acceptance criteria
- [ ] Initial sync of the configured window (default 12 months), older on demand; incremental via UIDNEXT/flags, CONDSTORE/QRESYNC when supported; UIDVALIDITY change → resync folder
- [ ] Threading by References/In-Reply-To with subject fallback
- [ ] `external` GreenMail tests: new/deleted/flag-changed messages reconciled; unit tests for threading

## Notes
