---
title: IMAP IDLE push and polling
status: in_progress
owner: dell26/agent-1
deps: [mail-header-sync,p0-events-sse]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/mail/sync,server/src/test/kotlin/ch/sonensei/cockpit/mail/sync]
prio: 1
lease_until: 2026-10-21T20:14:38Z
---
## Goal
Near-real-time new mail (SPEC.md §7.2).

## Acceptance criteria
- [ ] IDLE on each account's INBOX with reconnect/backoff and 29-min re-IDLE; other folders polled every 5 min
- [ ] New mail → ChangeEvent + optional notification event (per-account setting)
- [ ] `external` GreenMail test: delivered message appears within seconds without polling

## Notes
