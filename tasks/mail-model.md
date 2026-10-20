---
title: Mail data model
status: in_progress
owner: dell26/agent-2
deps: [p0-items-links,p0-search-core,p0-crypto]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/mail/model,server/src/test/kotlin/ch/sonensei/cockpit/mail/model,server/src/main/resources/db/migration/mail]
prio: 1
lease_until: 2026-10-20T14:25:30Z
---
## Goal
Accounts, identities, folders, cached headers and bodies (SPEC.md §7.1–7.2).

## Acceptance criteria
- [ ] Migrations: mail_account (type IMAP_SMTP|GOOGLE|MICROSOFT, hosts/ports/TLS, encrypted password or oauth connection, signature, notify flag), identity, mail_folder (path, role, uidvalidity, uidnext, highestmodseq), mail_message (account, folder, uid, Message-ID, headers, flags, size, hasAttachments, snippet, thread key), body cache table/files with size accounting
- [ ] Resolver EMAIL (re-resolve by Message-ID when UID changed)
- [ ] `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.mail.model.*'` passes (Postgres via Testcontainers where persistence is involved)

## Notes
