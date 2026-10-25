---
title: Cheaper link checks and loadOlder candidate narrowing in mail sync
status: todo
owner: 
deps: [mail-header-sync, mail-idle, mail-idle-flaky-test]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/core/items,server/src/main/kotlin/ch/sonensei/cockpit/mail/sync,server/src/test/kotlin/ch/sonensei/cockpit/mail/sync]
prio: 2
lease_until: 
releases: 1
extended: 
---
## Goal
Follow-ups from the mail-header-sync review (reviews/mail-header-sync/review.md CR-6, CR-7, CR-8): make large
resyncs and paging through old mail cheap.

## Acceptance criteria
- [ ] `LinkService.hasLinks(ItemRef)` (or a batched variant) backed by an `exists` query; `MailHeaderStore.isLinked` uses it instead of resolving all links (CR-6)
- [x] `MailHeaderSyncService.loadOlder` narrows candidates (e.g. `UID SEARCH BEFORE` the oldest cached date) instead of reading INTERNALDATE of every uncached message on every page; still picks by arrival date (CR-7)
  - verified (2026-10-25 23:48+0100 by r1963macos/agent-1): cd server && ./gradlew test --tests '*MailHeaderSyncServiceTests*' at c56e3174 -> 41/41 passed; incl. tests that only the window candidates' dates are read, window doubling + bisection (10y archive cached 5y back, limit 100 -> 100..200 dates read instead of ~1759), one far-older cached message keeps the window narrow, pick order by arrival date. Real IMAP SINCE path (GreenMail) not run here: no Docker
- [x] Class comment of `MailHeaderSyncService`: the Message-ID lookup of linked rows is described under the full resync, not the incremental sync (CR-8)
  - verified (2026-10-25 23:48+0100 by r1963macos/agent-1): read MailHeaderSyncService class KDoc at c56e3174: the Message-ID lookup of linked rows (retakeLinked) is now in the Full sync bullet, not Incremental; confirmed by code-reviewer (reviews/mail-sync-perf/review.md)
- [ ] Unit tests and the `external` GreenMail sync tests pass

## Notes
- ASSUMPTION (2026-10-25 23:48+0100 by r1963macos/agent-1): loadOlder narrows candidates with UID SEARCH SINCE windows grown back from now (7 days, doubling, then bisected to <= ~2x limit missing) instead of SEARCH BEFORE the oldest cached date: a window from now also covers missing messages newer than the oldest cached one, and one old cached message (moved in, found by search) can't widen it. Assumes the server maps INTERNALDATE to days in one timezone; otherwise a later page picks up a skipped message.
- release by r1963macos/agent-1: Implementation complete (c56e3174): LinkService.hasLinks via derived exists queries, isLinked uses it; loadOlder narrows candidates by SINCE windows from now (doubling + bisection); class comment fixed. Criteria 2,3 verified. BLOCKED here: Docker daemon not running on r1963macos, so Postgres tests (LinkServiceTests, MailHeaderStoreTests - new tests written, never run), integrationTest HeaderSyncGreenMailTests and .taskcheck cannot run. Left: on a machine with Docker run ./gradlew test + integrationTest --tests '*HeaderSyncGreenMailTests*', verify criteria 1 and 4, re-run test-writer + code-reviewer on the final code (last review/tests.md are for e8aa3f4d, stale after the bisection fix in reviews/mail-sync-perf/fixes.md round 2), write quality.json, done.
