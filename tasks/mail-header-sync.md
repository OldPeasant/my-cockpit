---
title: Header sync and threading
status: in_progress
owner: dell26/agent-1
deps: [mail-imap]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/mail/sync,server/src/test/kotlin/ch/sonensei/cockpit/mail/sync]
prio: 1
lease_until: 2026-10-20T17:27:09Z
---
## Goal
Keep the header cache in sync (SPEC.md §7.2).

## Acceptance criteria
- [ ] Initial sync of the configured window (default 12 months), older on demand; incremental via UIDNEXT/flags, CONDSTORE/QRESYNC when supported; UIDVALIDITY change → resync folder
- [ ] Threading by References/In-Reply-To with subject fallback
- [ ] `external` GreenMail tests: new/deleted/flag-changed messages reconciled; unit tests for threading

## Notes
- ASSUMPTION (2026-10-20 19:02+0200 by dell26/agent-1): Subject fallback: every message without References/In-Reply-To is threaded by normalized subject (Re/Fwd/AW/SV... prefixes and [list] tags stripped), so unrelated mails with the same subject (e.g. a weekly newsletter) share a conversation, like Gmail. Thread key = root Message-ID or 'subject:<normalized>'
- ASSUMPTION (2026-10-20 19:02+0200 by dell26/agent-1): A message gone from the server (expunged, moved, or not found again after a UIDVALIDITY resync) is deleted from the cache unless an item links to it; linked rows are only detached and are taken over by a later message with the same Message-ID
- ASSUMPTION (2026-10-20 19:02+0200 by dell26/agent-1): 'Older on demand' is a service method MailHeaderSyncService.loadOlder(folderId, limit=100): the next messages below the oldest cached UID. No REST endpoint here (mail-read-api owns the API); folder sync publishes one MAIL_FOLDER UPDATED change event when content changed; per-message SSE/notification events are left to mail-idle
- ASSUMPTION (2026-10-20 19:02+0200 by dell26/agent-1): Snippet = first 200 chars of the first text/plain part (else text/html via jsoup), from a 2 KB partial fetch, quoted '>' lines dropped. hasAttachments = a part with disposition attachment, a message/rfc822 part, or a named non-inline part outside multipart/related
