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

---

# Fix Report (round 2)

Date: 2026-10-25

Task: mail-sync-perf - Cheaper link checks and loadOlder candidate narrowing in mail sync

Scope: current task; finding CR-1 of the second review in `reviews/mail-sync-perf/review.md` (reviewed revision
e8aa3f4d, HEAD 631648ca)

## Fixed findings

### CR-1 — Doubling from now overshoots up to the cached frontier's age, so deep pages read the dates of almost every uncached message again (P2)

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/mail/sync/MailHeaderSyncService.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/mail/sync/MailHeaderSyncServiceTests.kt`

Problem:
`newestMissing` returned the first power-of-two window back from now that held `limit` missing UIDs. If the cache is
complete back to age A, that window spans between A and 2A. So every page read the INTERNALDATE of all uncached
messages between A and 2A old, which in a typical archive is nearly all of them (the CR-7 cost again).

Fix:
The doubling still finds the bracket: `short` is the last span with fewer than `limit` missing messages (zero for the
first window) and `span` is the first with enough. Then the window start is bisected between them with `uidsSince`.
A middle span with at least `limit` missing becomes the new `span` and candidate set. Otherwise it becomes `short`.
This stops once the candidates are at most `2 * limit`, or once the bracket is at most one day wide (`SINCE`
compares by day). The early exits are unchanged (all missing in the window; the window reaches the epoch). Every
window still starts back from now, so it holds every missing message newer than its cut, and correctness is the same
as before. Each page costs at most about log2(A in days) extra UID searches. I updated the KDoc of `newestMissing`
and the loadOlder bullet of the class comment, which no longer says "shortest window".
Tests in `MailHeaderSyncServiceTests`:
- new regression test `loadOlder reads only about the limit of dates when the cache is complete far back`. It
  covers 10 years of one message a day, with the last 5 years cached, and `limit = 100`. It asserts that the loaded
  messages are the 100 next older ones (1826..1925), that the dates read are in 100..200 (about 191; without the
  bisection, 1759), and that there are at most 25 `uidsSince` calls;
- `loadOlder keeps the candidates narrow when one far older message is cached` and `loadOlder of a folder without
  cached messages starts the window a week before now`: the first week held more than `2 * limit`. Their exact call
  sequences now include the bisection step at 3.5 days (`uidsSince 2026-10-16T22:00:00Z`) and the smaller date read
  (`[27..30]`, `[1..4]`). The loaded messages are unchanged.
- The other `uidsSince` sequence tests are unchanged and still pass (their windows hold at most `2 * limit`).

Validation:
- `cd server && ./gradlew ktlintFormat test --tests '*MailHeaderSyncServiceTests*' ktlintCheck compileTestKotlin` — Pass (BUILD SUCCESSFUL; MailHeaderSyncServiceTests: 41 tests, 0 failures, 0 skipped)

## Not fixed

None.

## Validation summary

Focused tests:
- `cd server && ./gradlew ktlintFormat test --tests '*MailHeaderSyncServiceTests*' ktlintCheck compileTestKotlin` — BUILD SUCCESSFUL; 41/41 passed

Full test suite:
- Not run: no Docker daemon here, so the Postgres/Testcontainers tests and the `external` GreenMail tests
  (`HeaderSyncGreenMailTests`, the real `SEARCH SINCE` path) cannot run. This round changes only
  `MailHeaderSyncService.newestMissing` and its unit tests.

Lint/type-check/build:
- `ktlintCheck`, `compileKotlin`, `compileTestKotlin` — Pass

`.taskcheck`:
- Not run by the fixer (it includes the server unit suite, which needs Docker here); the parent worker runs it.

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed. Focused validation passed. The Docker-backed suites could not run
here.
