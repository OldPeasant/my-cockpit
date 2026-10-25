# Code Review

Date: 2026-10-26

Task: mail-sync-perf - Cheaper link checks and loadOlder candidate narrowing in mail sync

Scope: final task code at 50de2442c, `git diff 98d1e2753 50de2442c -- server/`. This report covers that revision. The earlier pass reviewed 6b431d6a6 (task commits af6b0e07c..7f974dee3 plus the merge of origin/wip/mail-sync-perf). Since then only test files under `server/` changed, all in commit 50de2442c: one new case each in `MailHeaderSyncServiceTests` and `HeaderSyncGreenMailTests`. `git diff --stat 6b431d6a6 50de2442c -- server/src/main` is empty. The other files that differ between the two revisions (`tasks.py`, `tests/test_push_race.py`, `tasks/*.md`, `reviews/`) came in from main or are task bookkeeping, and they are outside this review.

Requirements:
- tasks/mail-sync-perf.md acceptance criteria and notes (including the recorded SINCE window assumption)
- reviews/mail-header-sync/review.md CR-6, CR-7, CR-8 (the origin of the task)
- AGENTS.md project notes (module boundaries, lint, migrations)
- reviews/mail-sync-perf/fixes.md (round 2, bisection)

Git range:
- Branch: main
- HEAD: 50de2442c2238a269a5c8948ad5b3c51a71c0366
- Base/start: 98d1e2753a2b03eb43223d062a0e4ccdcfaef7f4
- Commits reviewed: all task commits through 50de2442c. The production code was reviewed at 6b431d6a6 and has not changed since. The test-only commit 50de2442c was reviewed in this pass.

Summary: 0 P0, 0 P1, 0 P2, 1 P3

## Verification notes

- **AC1 (hasLinks / exists queries).** `LinkRepository.existsBySourceTypeAndSourceId` and `existsByTargetTypeAndTargetId` are derived Spring Data `exists` queries. The `link_source (source_type, source_id)` and `link_target (target_type, target_id)` indexes in `V202610201400__core_items_link.sql` cover them. `LinkService.hasLinks` ORs the two queries, and the second one only runs when the first returns false. It returns the same result as the old `linksOf(...).isNotEmpty()`: `linksOf` groups every touching link and filters none out, so it is non-empty exactly when a link exists in either direction. `MailHeaderStore.isLinked` now calls `hasLinks`. Tests cover the source end, the target end, SYNC links, links that were removed, and the item type being part of the key (`LinkServiceTests`, `MailHeaderStoreTests`). The core-items module boundary is unchanged: mail still goes through `LinkService`.
- **AC2 (newestMissing), termination.** The doubling loop stops in one of three ways: (a) once `now - span` is at or before EPOCH, which takes at most about 12 doublings for a 2026 clock and is checked before every query, so `Duration`/`Instant` cannot overflow; (b) once the window holds all missing messages; (c) once it holds at least `limit`. The bisection loop halves `span - short` on every iteration. It stops when the gap is 1 day or less, or when the window holds at most `2 * limit`. `limit` is always at least 1 (`require(limit > 0)` in `loadOlder`), and `newestMissing` is only called when `missing.size > limit`, so the limit-0 case cannot happen. Limit 1 also terminates, through the 1-day gap rule.
- **AC2, correctness of the picked set.** After bisection, `candidates` still holds at least `limit` messages, because `span` only moves to a middle that holds at least `limit`. Candidates form a SINCE window, so every missing message outside it arrived on an earlier server day. So the `limit` newest of `candidates` are the `limit` newest of all missing messages, given the day-mapping assumption already recorded on the task and in the KDoc. EPOCH fallback: returning all of `missing` is safe. It reads every date (the old behaviour) and also covers messages dated before 1970. A message that is expunged or arrives between `uidsFrom` and `uidsSince` is dropped by the filter against `missing`, which is harmless. Undated messages are never in a window, but they are still loaded once they fit the limit (paging test).
- **AC3.** The class KDoc now describes the Message-ID lookup of linked rows under Full sync, not under Incremental sync.
- **New unit test (50de2442c), `loadOlder stops bisecting at a day when more than twice the limit arrived on the same day`.** I traced it against `newestMissing` and it is correct. The fixed clock is 2026-10-20T10:00Z (UTC). Messages 1..10 are dated 10-18T10:00Z and message 11 is dated 09-20, so the 7-day window holds {1..10} (10 > 2 * 2). The middles are 3.5 days (10-16T22:00, all ten), 1.75 days (10-18T16:00: the fake compares by UTC day, so day 10-18 is included and all ten stay in) and 0.875 days (10-19T13:00: none, so `short` moves). The gap is then 0.875 days, which is not more than 1 day, so the loop stops. `receivedDates` is read only for 1..10, and the tie-break on equal dates (`thenByDescending { it }`) picks 9 and 10. The expected call list matches exactly. Because the clock is fixed and the fake works in UTC, the test cannot be flaky on time or timezone. The `@Timeout(10s, SEPARATE_THREAD)` makes a termination regression fail instead of hang. It is the only `@Timeout` in the file, but it fits a test whose purpose is loop termination. It is not a convention problem.
- **New GreenMail test (50de2442c), `paging older mail with narrowed SINCE windows on the server loads the newest arrival first`.** I traced it and it is correct. After the initial sync only "recent" (10 days) is cached. Five messages are missing. With limit 1, the windows double from 7 to 448 days, and 448 is the first one that holds any of them (400/403/406/409). It holds four, which is more than 2, so bisection runs and keeps a window that holds at least d400. Each page loads the newest remaining message. On page 5 only d900 is left, which takes the `missing.size <= limit` path, and the expected results follow: `hasMore = page < 5`, then `loaded = 0, hasMore = false`. Flakiness: the test uses `Instant.now()` like the neighbouring GreenMail tests, and the messages are 3 days apart. A server-side day-mapping or timezone difference of up to a day, or a run that crosses midnight, can move a message in or out of one window. It cannot change which message is newest, and every non-empty window contains the newest missing message. So the asserted order does not depend on the time of day or the JVM timezone. One limitation, which I did not raise as a finding: the test checks the paging result and not the SINCE calls, so it would also pass if bisection were skipped. The unit tests above assert the exact call sequence, so this gap is covered.
- **AC4 / tests.** I could not run Gradle. The parent reported these results. At 6b431d6a6: `check` ran 4575 tests with one unrelated `IcsFeedClientTests` flake that passed on rerun; LinkServiceTests 21/21, MailHeaderStoreTests 24/24; `integrationTest --tests '*mail.sync*'`: HeaderSyncDovecotTests 2/2, MailIdle* 5/5. At 50de2442c: `server/gradlew -p server ktlintCheck test --tests '*MailHeaderSyncServiceTests*'`: ktlint ok, 42/42 passed. `server/gradlew -p server integrationTest --tests '*HeaderSyncGreenMailTests*'`: 15/15 passed. That is one more test in each suite than at 6b431d6a6 (41 and 14), which matches the two new cases. The unit tests use a fake whose SINCE search compares by day. They cover: doubling, bisection, the 1-day stop of the bisection, a cached outlier that must not widen the window, the EPOCH fallback, a folder without cached messages, gaps newer than the oldest cached message, a 10-year archive (100..200 dates read), and paging across calls including an undated message.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | KDoc line in newestMissing is not wrapped like the rest**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/mail/sync/MailHeaderSyncService.kt:227`
    - Category: Quality
    - Problem: One KDoc line of `newestMissing` is 149 characters long ("...only their dates need to be read. A server that takes each message's day in its own offset can leave a message up to"). The rest of the file wraps at about 120 characters. It looks like a leftover from the round-2 edit. It is still present at 50de2442c.
    - Why it matters: It hurts readability and is inconsistent with the file. It has no functional impact, and ktlint did not flag it.
    - Suggested fix: Re-wrap the paragraph at the file's usual width.

## Review conclusion

No material issues found. The two test cases added in 50de2442c are correct and deterministic. CR-1 is an optional cosmetic cleanup.
