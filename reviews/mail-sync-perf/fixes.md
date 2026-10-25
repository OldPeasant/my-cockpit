# Fix Report

Date: 2026-10-25

Task: mail-sync-perf - Cheaper link checks and loadOlder candidate narrowing in mail sync

Scope: current task; findings CR-1, CR-2, CR-3 of `reviews/mail-sync-perf/review.md` (reviewed HEAD 98e9f945)

## Fixed findings

### CR-1 — One old cached message makes the narrowing useless again (P2)

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/mail/sync/MailHeaderSyncService.kt`
- `server/src/main/kotlin/ch/sonensei/cockpit/mail/sync/MailHeaderStore.kt`
- `server/src/main/kotlin/ch/sonensei/cockpit/mail/sync/MailSyncRepository.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/mail/sync/MailHeaderSyncServiceTests.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/mail/sync/MailHeaderStoreTests.kt`

Problem:
`newestMissing` anchored its window at `min(receivedAt)` of the located cached rows. Any single far-older cached
row (moved into the folder, found by `searchServer`, retaken after a resync) pushed the window back that far, so
every `loadOlder` page read the INTERNALDATE of years of uncached mail again.

Fix:
As the parent decided, the window now grows backward from `clock.instant()`: it starts at 7 days and doubles until it
holds at least `limit` missing UIDs (or all of them, or it reaches the epoch). It does not depend on the cache.
Correctness is unchanged: the window always contains every missing message newer than its cut. `oldestReceivedAt`
is no longer used, so it was removed from `MailHeaderStore` and `MailSyncRepository` (and the now unused
`java.time.Instant` import of the repository), together with its Postgres test in `MailHeaderStoreTests`. I updated
the KDoc of `newestMissing` and the loadOlder bullet of the class comment.
Tests in `MailHeaderSyncServiceTests`:
- removed the `oldestReceivedAt` stub in `setUp`;
- replaced `loadOlder reads the dates only of missing messages that arrived from a week before the oldest cached one`
  with the regression test `loadOlder keeps the candidates narrow when one far older message is cached`. One cached
  message is three years old, and there are uncached messages from 980 to 999 days ago. Only one `uidsSince` (now - 7
  days) runs, and only `receivedDates [24..30]` is read;
- `loadOlder doubles the window ...` is now anchored at now (spans 7/14/28/56 days);
- `loadOlder still picks missing messages that arrived after the oldest cached one first` now asserts the
  spans grown from now (7 to 448 days). It still shows that the gap 25 and the newest older message 10 are picked;
- renamed `loadOlder without a cached arrival time ...` to `loadOlder of a folder without cached messages starts the
  window a week before now`, because the cached arrival time no longer plays a role.

Validation:
- `cd server && ./gradlew ktlintFormat test --tests '*MailHeaderSyncServiceTests*' ktlintCheck compileTestKotlin` — Pass (BUILD SUCCESSFUL; MailHeaderSyncServiceTests: 39 tests, 0 failures, 0 skipped)

### CR-2 — `existsTouching` is a `count(...) > 0`, not an exists query (P3)

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/core/items/infra/LinkRepository.kt`
- `server/src/main/kotlin/ch/sonensei/cockpit/core/items/domain/LinkService.kt`

Problem:
`select count(l) > 0 ... where (...) or (...)` counted every matching link instead of stopping at the first one.

Fix:
I replaced the JPQL `existsTouching` with two Spring Data derived queries, `existsBySourceTypeAndSourceId` and
`existsByTargetTypeAndTargetId`. Spring Data runs these as limit-1 selects, each backed by its own index
(`link_source`, `link_target`). `LinkService.hasLinks(ItemRef)` keeps its signature and returns
`source exists || target exists`, so the target query only runs when there is no outgoing link. `existsTouching` had
no other callers.

Validation:
- `cd server && ./gradlew compileTestKotlin ktlintCheck` — Pass
- `LinkServiceTests` (Postgres/Testcontainers) — Not run: no Docker daemon here (see Validation summary)

### CR-3 — The KDoc's ordering guarantee assumes one timezone for every INTERNALDATE (P3)

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/mail/sync/MailHeaderSyncService.kt`

Problem:
The KDoc said without condition that every missing message outside the window arrived earlier than every one inside.
That only holds when the server maps INTERNALDATE to days in a single timezone (RFC 3501 `SINCE` ignores time and
timezone).

Fix:
As the parent decided, this is a comment change only, with no code guard. The `newestMissing` KDoc now limits the
guarantee to servers that map every INTERNALDATE to its day in one timezone. It also says that a server that takes
each message's day in its own offset can leave a message up to about a day newer than a loaded one outside the
window, and that a later page then picks that message up.

Validation:
- `cd server && ./gradlew ktlintCheck` — Pass

## Not fixed

None.

## Validation summary

Focused tests:
- `cd server && ./gradlew ktlintFormat test --tests '*MailHeaderSyncServiceTests*' ktlintCheck compileTestKotlin` — BUILD SUCCESSFUL; MailHeaderSyncServiceTests 39/39 passed

Full test suite:
- `cd server && ./gradlew check` — FAIL, environmental only: 2650 tests, 369 failed. Every failing result file
  is a Docker/Testcontainers failure ("Could not find a valid Docker environment", `PostgresTestBase` /
  ApplicationContext initialisation). No failure is outside that cause. Not covered here because of that:
  `LinkServiceTests` (the derived exists queries of CR-2), the rest of `MailHeaderStoreTests`, and the `external`
  GreenMail tests (`HeaderSyncGreenMailTests`, the end-to-end `SEARCH SINCE` path). They must be run where Docker
  exists.

Lint/type-check/build:
- `ktlintCheck`, `compileKotlin`, `compileTestKotlin` — Pass

`.taskcheck`:
- Not run by the fixer (it includes the server unit suite, which needs Docker here); the parent worker runs it.

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed. Focused validation passed. The Postgres and GreenMail suites
could not run here because there is no Docker daemon.
