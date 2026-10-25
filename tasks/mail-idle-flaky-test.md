---
title: MailIdleGreenMailTests: wait for the change event (flaky in CI)
status: in_progress
owner: dell26/agent-1
deps: []
touches: [server/src/test/kotlin/ch/sonensei/cockpit/mail/sync/MailIdleGreenMailTests.kt]
prio: 1
lease_until: 2026-10-25T22:27:38Z
---
## Goal
`MailIdleGreenMailTests > an account that does not notify gets change events but no notification()` failed in CI
run 39732700039 (integrationTest job, 1 of 107) with `AssertionFailedError at MailIdleGreenMailTests.kt:177`:
it waits until the message is in the cache, then at once expects exactly one change event for it. If the event is
published a little after the message becomes visible, the list is still empty. Make the test wait for the event.

## Acceptance criteria
- [x] The test waits for the change event (`eventually { ... count >= 1 }`) before asserting exactly one after a
  short settle, like the notification check; if the cause turns out to be different (e.g. two events), the
  cause is found and noted here and the test or production code fixed accordingly
  - verified (2026-10-25 23:05+0100 by dell26/agent-1): Cause: MailHeaderSyncService.syncFolder commits messages (MailHeaderStore.upsert, own tx) before NewMailAnnouncer publishes EMAIL CREATED, so the message is visible before its event; NewMailAnnouncer is the only EMAIL publisher and per-folder lock prevents a 2nd announce (code-reviewer confirmed). Test now waits eventually{event present}, settles 300 ms, asserts shouldHaveSize 1; same wait added to the first test (its first{} had the same race) -> reviewed, ktlintCheck pass
- [x] `cd server && ./gradlew integrationTest --tests '*MailIdleGreenMailTests' --rerun` passes 10 times in a row
  - verified (2026-10-25 23:05+0100 by dell26/agent-1): sh .tmp/rerun10.sh = 10x (cd server && ./gradlew integrationTest --tests '*MailIdleGreenMailTests' --rerun) sequentially on 22e4f014c, Testcontainers Postgres + GreenMail -> 10/10 PASS, each 4 tests, 0 skipped, 0 failures

## Notes
- Seen once in the last 40 CI runs; the log does not show the actual list size.
