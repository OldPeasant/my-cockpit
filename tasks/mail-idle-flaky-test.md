---
title: MailIdleGreenMailTests: wait for the change event (flaky in CI)
status: todo
owner:
deps: []
touches: [server/src/test/kotlin/ch/sonensei/cockpit/mail/sync/MailIdleGreenMailTests.kt]
prio: 1
---
## Goal
`MailIdleGreenMailTests > an account that does not notify gets change events but no notification()` failed in CI
run 39732700039 (integrationTest job, 1 of 107) with `AssertionFailedError at MailIdleGreenMailTests.kt:177`:
it waits until the message is in the cache, then at once expects exactly one change event for it. If the event is
published a little after the message becomes visible, the list is still empty. Make the test wait for the event.

## Acceptance criteria
- [ ] The test waits for the change event (`eventually { ... count >= 1 }`) before asserting exactly one after a
  short settle, like the notification check; if the cause turns out to be different (e.g. two events), the
  cause is found and noted here and the test or production code fixed accordingly
- [ ] `cd server && ./gradlew integrationTest --tests '*MailIdleGreenMailTests' --rerun` passes 10 times in a row

## Notes
- Seen once in the last 40 CI runs; the log does not show the actual list size.
