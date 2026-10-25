# Code Review

Date: 2026-10-25

Task: mail-idle-flaky-test - MailIdleGreenMailTests: wait for the change event (flaky in CI)

Scope: `git diff 677f9840bfc96e617bed01677314dc4e48ffb17d 22e4f014c7367d45fb518d08fb48427ce0669cd6` (one test file:
`server/src/test/kotlin/ch/sonensei/cockpit/mail/sync/MailIdleGreenMailTests.kt`)

Requirements:
- `tasks/mail-idle-flaky-test.md` (acceptance criteria and notes)
- `AGENTS.md` project notes and conventions
- Production code read to check the root cause: `server/src/main/kotlin/ch/sonensei/cockpit/mail/sync/`
  (`MailHeaderSyncService.kt`, `MailHeaderStore.kt`, `NewMailAnnouncer.kt`, `MailPushService.kt`) and
  `core/events/domain/ChangeEventPublisher.kt`

Git range:
- Branch: main
- HEAD: 22e4f014c7367d45fb518d08fb48427ce0669cd6
- Base/start: 677f9840bfc96e617bed01677314dc4e48ffb17d
- Commits reviewed: 1 (22e4f014c); no uncommitted changes in the reviewed file

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## Root cause check

I checked the root cause separately, without relying on the commit message:

- `MailHeaderSyncService.syncFolder` has no transaction of its own. Inside `mailbox.withFolder { ... }`,
  `incremental` calls `MailHeaderStore.upsert`, which is `@Transactional` on a separate bean (so it goes through the
  proxy). Each batch therefore commits as soon as `upsert` returns, and `findByFolderIdAndUidIsNotNull` in the test
  can see the row from then on. After that come `store.finish(...)`, the return from `withFolder` (the IMAP folder
  closes), `announce(result)` (MAIL_FOLDER) and finally `announcer.announce(...)`, which publishes the `EMAIL`
  CREATED events (`NewMailAnnouncer.kt:59`). `ChangeEventPublisher.publish` is a plain synchronous
  `publishEvent`, and the test's `@EventListener` recorder gets it on the watcher thread. So there is a real window
  between the message showing up in the cache and its event being recorded. That window matches the failure the
  task describes: an empty list at the old `shouldHaveSize 1`.
- The other explanation (two events for the same message) has no code path that I can find. `NewMailAnnouncer` is
  the only place that publishes `ItemType.EMAIL` change events (grep over `server/src/main`), and it only gets
  `result.arrived`. `arrived` is set only by `incremental` (`arrivals.createdIds`). `upsert` adds an id to
  `created` only for a newly created row: rows already cached are skipped, and reattached rows count as `updated`.
  `incremental` also drops UIDs that are already cached (`filter { it !in known }`). Full syncs and `loadOlder` /
  `searchServer` announce no arrivals. Syncs of one folder are serialized by the per-folder lock, so a later sync
  sees the row as cached and does not announce it again. The new test still asserts `shouldHaveSize 1` after the
  300 ms settle, so a duplicate event would still fail the test and would not be hidden.

## Acceptance criteria

- AC1: Met in code. The quiet test (lines 183-185) now waits with `eventually("the change event") { ... isNotEmpty() }`,
  sleeps 300 ms, and then asserts exactly one event. The sleep now comes before the exact-count assertion, not after
  it, which matches the notification check. The first test (line 149) gets the same wait. It had the same race:
  `recorder.changes.first { it.id == message.id }` would have thrown `NoSuchElementException`. The cause matches the
  one the task assumed, so nothing more needs to be noted in the task.
- AC2 (10 consecutive `integrationTest --tests '*MailIdleGreenMailTests' --rerun` runs): needs Gradle, which I could
  not run as a sub-agent. The parent worker has to run it and record it with `tasks.py verify`.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Review conclusion

No material issues found.
