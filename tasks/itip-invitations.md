---
title: Send invitations for local events (iTIP)
status: in_progress
owner: r1963macos/agent-1
deps: [cal-local-api,mail-compose]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/calendar/itip,server/src/test/kotlin/ch/sonensei/cockpit/calendar/itip]
prio: 3
lease_until: 2026-10-26T14:35:31Z
extended: 
releases: 1
---
## Goal
Invite attendees by mail (SPEC.md §6.2).

## Acceptance criteria
- [ ] REQUEST/CANCEL as text/calendar mails; incoming REPLY updates attendee status
- [ ] `external` GreenMail tests

## Notes
- ASSUMPTION (2026-10-26 13:20+0100 by r1963macos/agent-1): No REST endpoint/UI for sending invitations in this task: touches only covers calendar/itip; ItipService.send is the server API, exposing it (controller + openapi + clients) is follow-up task itip-api
- ASSUMPTION (2026-10-26 13:20+0100 by r1963macos/agent-1): Incoming REPLY is only applied when it arrives in an Inbox, the event is in a LOCAL calendar, lists the attendee, and the mail's From matches the attendee address (anti-spoofing); delegated/alias replies from another address are ignored
- ASSUMPTION (2026-10-26 13:20+0100 by r1963macos/agent-1): A REPLY for one occurrence without an existing override is ignored (no override created); a series REPLY updates the master and overrides listing the attendee. Storing a status bumps the event version, which is also the iTIP SEQUENCE
- extended by r1963macos/agent-1: Implementation + tests committed (aeb5b2ab): ItipMessagesTests 24/24 via ./gradlew test, ItipGreenMailTests 23/23 via ./gradlew integrationTest, ktlint clean. Left: test-writer + code-reviewer helpers, fixer if needed, reviews/itip-invitations/quality.json, tasks.py verify for both criteria, scripts/check.sh, done.
- release by r1963macos/agent-1: Done: ItipMessages/ItipService (REQUEST/CANCEL text/calendar mails) + ItipReplyProcessor (incoming REPLY -> attendee status), unit tests + @ExternalTest ItipGreenMailTests incl. test-writer edge cases (c8cfb38f, ktlint clean). reviews/itip-invitations/review.md committed: 2 P2 (CR-1 uninvite-CANCEL must omit STATUS:CANCELLED and not go to still-invited attendees; CR-2 ItipReplyProcessor uses mail repositories directly, use a mail service instead), 2 P3 (REPLY ordering by DTSTAMP/SEQUENCE; reply lost on optimistic-lock conflict). LEFT: test-writer never wrote reviews/itip-invitations/tests.md (re-run it, incl. coverage); fixer for CR-1/CR-2 (P3 fix or record disposition); re-run test-writer + code-reviewer on final code; quality.json; tasks.py verify 1+2 (./gradlew test --tests '*itip*', ./gradlew integrationTest --tests '*ItipGreenMail*'); scripts/check.sh (server part took 13 min here under load, last run failed only on the now-fixed ktlint issue); done.
- ASSUMPTION (2026-10-26 15:15+0100 by r1963macos/agent-1): CR-3 partial: only newest REPLY per attendee within one mail is applied (by SEQUENCE, then DTSTAMP); cross-message staleness check not implemented because it needs per-attendee answered-at storage (shared Attendee model or new table). Follow-up if needed.
