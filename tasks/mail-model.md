---
title: Mail data model
status: done
owner: dell26/agent-2
deps: [p0-items-links,p0-search-core,p0-crypto]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/mail/model,server/src/test/kotlin/ch/sonensei/cockpit/mail/model,server/src/main/resources/db/migration/mail]
prio: 1
lease_until: 
---
## Goal
Accounts, identities, folders, cached headers and bodies (SPEC.md §7.1–7.2).

## Acceptance criteria
- [x] Migrations: mail_account (type IMAP_SMTP|GOOGLE|MICROSOFT, hosts/ports/TLS, encrypted password or oauth connection, signature, notify flag), identity, mail_folder (path, role, uidvalidity, uidnext, highestmodseq), mail_message (account, folder, uid, Message-ID, headers, flags, size, hasAttachments, snippet, thread key), body cache table/files with size accounting
  - verified (2026-10-20 16:46+0200 by dell26/agent-2): cd server && ./gradlew test --tests 'ch.sonensei.cockpit.mail.model.*' (Postgres 16 Testcontainers; Flyway applies V202610201402__mail_model.sql) -> 70 passed; MailModelPersistenceTests/MailModelConstraintTests cover mail_account (types, hosts/ports/TLS, encrypted password vs oauth connection, signature, notify), identity, mail_folder sync state, mail_message columns, mail_body size accounting + LRU eviction (MailBodyCacheTests)
- [x] Resolver EMAIL (re-resolve by Message-ID when UID changed)
  - verified (2026-10-20 16:46+0200 by dell26/agent-2): cd server && ./gradlew test --tests 'ch.sonensei.cockpit.mail.model.MailItemResolverTests' (in the package run) -> pass: detached/moved/UIDVALIDITY-changed rows re-resolve by account + Message-ID, same folder preferred, other accounts never, stale UIDs of deleted folders ignored
- [x] `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.mail.model.*'` passes (Postgres via Testcontainers where persistence is involved)
  - verified (2026-10-20 16:46+0200 by dell26/agent-2): cd server && ./gradlew ktlintCheck test --tests 'ch.sonensei.cockpit.mail.model.*' (Testcontainers Postgres) at 03d2a72 -> 70 tests, 0 failures; sh scripts/check.sh -> all checks passed

## Notes
- ASSUMPTION (2026-10-20 16:07+0200 by dell26/agent-2): Model code lives in package ch.sonensei.cockpit.mail.model (flat: entities, repositories, EMAIL resolver, body cache), as the task's touches name mail/model, instead of mail.{domain,infra}.
- ASSUMPTION (2026-10-20 16:07+0200 by dell26/agent-2): mail_account.oauth_connection_id has no foreign key (core oauth_connection table is a parallel task); a DB check forbids password on GOOGLE/MICROSOFT accounts and an OAuth connection on IMAP_SMTP accounts. Hosts/ports/security are stored for OAuth accounts too (imap.gmail.com etc.), filled by the account API.
- ASSUMPTION (2026-10-20 16:07+0200 by dell26/agent-2): Link survival: the sync may keep a mail_message row with uid=null (detach()) instead of deleting it; MailMessageLocator re-resolves such rows by account + Message-ID (same folder preferred, then any folder of the same account, never another account). EMAIL deep link is /mail/messages/{currentId}.
- ASSUMPTION (2026-10-20 16:07+0200 by dell26/agent-2): Body cache is a DB table mail_body (text_plain + raw unsanitized text_html, size = UTF-8 bytes of both) with LRU eviction on every put down to cockpit.mail.body-cache-max-bytes (default 2 GiB); the body just stored is never evicted even if it alone exceeds the budget. Attachments are not cached.
- ASSUMPTION (2026-10-20 16:07+0200 by dell26/agent-2): Address headers (from/to/cc/bcc/reply-to) are stored as RFC 5322 address-list text mapped to List<MailAddress> by a converter (no jsonb); IMAP flags as booleans seen/flagged/answered/draft + keywords text[]; added sync_window_months (default 12) and identity reply_to/signature override.
- ASSUMPTION (2026-10-20 16:47+0200 by dell26/agent-2): CR-1 (body cache eviction sorts mail_body on every put, LRU index unused) accepted as is: results are correct and the cost is small at single-user volumes; revisit in perf-tuning
- done by dell26/agent-2: `sh scripts/check.sh` passed in 59s on 69e9547f3; criteria verified 3/3
