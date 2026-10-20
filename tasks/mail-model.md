---
title: Mail data model
status: in_progress
owner: dell26/agent-2
deps: [p0-items-links,p0-search-core,p0-crypto]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/mail/model,server/src/test/kotlin/ch/sonensei/cockpit/mail/model,server/src/main/resources/db/migration/mail]
prio: 1
lease_until: 2026-10-20T14:40:41Z
---
## Goal
Accounts, identities, folders, cached headers and bodies (SPEC.md §7.1–7.2).

## Acceptance criteria
- [ ] Migrations: mail_account (type IMAP_SMTP|GOOGLE|MICROSOFT, hosts/ports/TLS, encrypted password or oauth connection, signature, notify flag), identity, mail_folder (path, role, uidvalidity, uidnext, highestmodseq), mail_message (account, folder, uid, Message-ID, headers, flags, size, hasAttachments, snippet, thread key), body cache table/files with size accounting
- [ ] Resolver EMAIL (re-resolve by Message-ID when UID changed)
- [ ] `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.mail.model.*'` passes (Postgres via Testcontainers where persistence is involved)

## Notes
- ASSUMPTION (2026-10-20 16:07+0200 by dell26/agent-2): Model code lives in package ch.sonensei.cockpit.mail.model (flat: entities, repositories, EMAIL resolver, body cache), as the task's touches name mail/model, instead of mail.{domain,infra}.
- ASSUMPTION (2026-10-20 16:07+0200 by dell26/agent-2): mail_account.oauth_connection_id has no foreign key (core oauth_connection table is a parallel task); a DB check forbids password on GOOGLE/MICROSOFT accounts and an OAuth connection on IMAP_SMTP accounts. Hosts/ports/security are stored for OAuth accounts too (imap.gmail.com etc.), filled by the account API.
- ASSUMPTION (2026-10-20 16:07+0200 by dell26/agent-2): Link survival: the sync may keep a mail_message row with uid=null (detach()) instead of deleting it; MailMessageLocator re-resolves such rows by account + Message-ID (same folder preferred, then any folder of the same account, never another account). EMAIL deep link is /mail/messages/{currentId}.
- ASSUMPTION (2026-10-20 16:07+0200 by dell26/agent-2): Body cache is a DB table mail_body (text_plain + raw unsanitized text_html, size = UTF-8 bytes of both) with LRU eviction on every put down to cockpit.mail.body-cache-max-bytes (default 2 GiB); the body just stored is never evicted even if it alone exceeds the budget. Attachments are not cached.
- ASSUMPTION (2026-10-20 16:07+0200 by dell26/agent-2): Address headers (from/to/cc/bcc/reply-to) are stored as RFC 5322 address-list text mapped to List<MailAddress> by a converter (no jsonb); IMAP flags as booleans seen/flagged/answered/draft + keywords text[]; added sync_window_months (default 12) and identity reply_to/signature override.
