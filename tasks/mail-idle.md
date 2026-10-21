---
title: IMAP IDLE push and polling
status: in_progress
owner: dell26/agent-1
deps: [mail-header-sync,p0-events-sse]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/mail/sync,server/src/test/kotlin/ch/sonensei/cockpit/mail/sync]
prio: 1
lease_until: 2026-10-21T20:33:38Z
---
## Goal
Near-real-time new mail (SPEC.md §7.2).

## Acceptance criteria
- [ ] IDLE on each account's INBOX with reconnect/backoff and 29-min re-IDLE; other folders polled every 5 min
- [ ] New mail → ChangeEvent + optional notification event (per-account setting)
- [ ] `external` GreenMail test: delivered message appears within seconds without polling

## Notes
- ASSUMPTION (2026-10-21 22:07+0200 by dell26/agent-1): The IDLE connection is opened outside the ImapConnectionPool (one extra connection per account), with a read timeout of re-idle-interval + read-timeout, because an IDLE holds its connection for good
- ASSUMPTION (2026-10-21 22:07+0200 by dell26/agent-1): New-mail notification is SSE event 'mail' (ClientNotification) with accountId, folderId, count and up to 10 {id, from, subject, receivedAt} of unread arrivals in the Inbox; every arrival also gets an EMAIL CREATED ChangeEvent. Arrivals of a first/full sync are never announced
- ASSUMPTION (2026-10-21 22:07+0200 by dell26/agent-1): Added cockpit.mail.push.enabled=false to server/src/test/resources/config/application.properties (outside touches) so other Spring tests never sync in the background; the 5-min poll also refreshes the folder list
