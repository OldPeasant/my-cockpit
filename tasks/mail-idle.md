---
title: IMAP IDLE push and polling
status: in_progress
owner: dell26/agent-1
deps: [mail-header-sync,p0-events-sse]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/mail/sync,server/src/test/kotlin/ch/sonensei/cockpit/mail/sync]
prio: 1
lease_until: 2026-10-21T21:03:57Z
---
## Goal
Near-real-time new mail (SPEC.md §7.2).

## Acceptance criteria
- [x] IDLE on each account's INBOX with reconnect/backoff and 29-min re-IDLE; other folders polled every 5 min
  - verified (2026-10-21 22:38+0200 by dell26/agent-1): cd server && ./gradlew test --tests 'ch.sonensei.cockpit.mail.sync.*' at 71457b4c8 -> InboxWatcherTests 13 (reconnect + doubling backoff capped, failure reset, re-IDLE refresh, no-IDLE path), MailPushServiceTests 20 (watcher per account incl. restart on account change, 5-min poll of all selectable folders incl. Inbox), MailPushPropertiesTests 11 (defaults 29 min re-IDLE / 5 min poll via Spring binding) all pass; integrationTest MailIdleGreenMailTests 4 (re-IDLE every 1 s keeps delivering) pass
- [x] New mail → ChangeEvent + optional notification event (per-account setting)
  - verified (2026-10-21 22:38+0200 by dell26/agent-1): cd server && ./gradlew test --tests 'ch.sonensei.cockpit.mail.sync.*' -> NewMailAnnouncerTests 10 (EMAIL CREATED ChangeEvent per arrival, 'mail' notification only when account notifyNewMail is on, unseen Inbox only, max 10) pass; integrationTest MailIdleGreenMailTests (notifyNewMail on/off over GreenMail) pass
- [x] `external` GreenMail test: delivered message appears within seconds without polling
  - verified (2026-10-21 22:38+0200 by dell26/agent-1): cd server && ./gradlew integrationTest --tests 'ch.sonensei.cockpit.mail.sync.*' (GreenMail, tag external) at 71457b4c8 -> MailIdlePushGreenMailTests 1 pass: with re-idle-interval and poll-interval PT1H, a delivered message is in the cache < 10 s (suite 2.4 s); plus MailIdleGreenMailTests 4, HeaderSyncGreenMailTests 14 pass

## Notes
- ASSUMPTION (2026-10-21 22:07+0200 by dell26/agent-1): The IDLE connection is opened outside the ImapConnectionPool (one extra connection per account), with a read timeout of re-idle-interval + read-timeout, because an IDLE holds its connection for good
- ASSUMPTION (2026-10-21 22:07+0200 by dell26/agent-1): New-mail notification is SSE event 'mail' (ClientNotification) with accountId, folderId, count and up to 10 {id, from, subject, receivedAt} of unread arrivals in the Inbox; every arrival also gets an EMAIL CREATED ChangeEvent. Arrivals of a first/full sync are never announced
- ASSUMPTION (2026-10-21 22:07+0200 by dell26/agent-1): Added cockpit.mail.push.enabled=false to server/src/test/resources/config/application.properties (outside touches) so other Spring tests never sync in the background; the 5-min poll also refreshes the folder list
