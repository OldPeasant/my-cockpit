# Code Review

Date: 2026-10-26

Task: mail-sync-perf - Cheaper link checks and loadOlder candidate narrowing in mail sync

Scope: final task code at 6b431d6a6, `git diff 98d1e2753 HEAD -- server/` (task commits af6b0e07c..7f974dee3 plus the merge of origin/wip/mail-sync-perf). Re-review for this revision; the earlier review was for an older revision and went stale with the bisection fix (fixes.md round 2).

Requirements:
- tasks/mail-sync-perf.md acceptance criteria and notes (including the recorded SINCE window assumption)
- reviews/mail-header-sync/review.md CR-6, CR-7, CR-8 (the origin of the task)
- AGENTS.md project notes (module boundaries, lint, migrations)
- reviews/mail-sync-perf/fixes.md (round 2, bisection)

Git range:
- Branch: main
- HEAD: 6b431d6a6
- Base/start: 98d1e2753a2b03eb43223d062a0e4ccdcfaef7f4
- Commits reviewed: 8 (7 task commits + 1 merge)

Summary: 0 P0, 0 P1, 0 P2, 1 P3

## Verification notes

- **AC1 (hasLinks / exists queries).** `LinkRepository.existsBySourceTypeAndSourceId` and `existsByTargetTypeAndTargetId` are derived Spring Data `exists` queries. The `link_source (source_type, source_id)` and `link_target (target_type, target_id)` indexes in `V202610201400__core_items_link.sql` cover them. `LinkService.hasLinks` ORs the two queries, and the second one only runs when the first returns false. It returns the same result as the old `linksOf(...).isNotEmpty()`: `linksOf` groups every touching link and filters none out, so it is non-empty exactly when a link exists in either direction. `MailHeaderStore.isLinked` (line 171) now calls `hasLinks`. Tests cover the source end, the target end, SYNC links, links that were removed, and the item type being part of the key (`LinkServiceTests`, `MailHeaderStoreTests`). The core-items module boundary is unchanged: mail still goes through `LinkService`.
- **AC2 (newestMissing), termination.** The doubling loop stops in one of three ways: (a) once `now - span` is at or before EPOCH, which takes at most about 12 doublings for a 2026 clock and is checked before every query, so `Duration`/`Instant` cannot overflow; (b) once the window holds all missing messages; (c) once it holds at least `limit`. The bisection loop halves `span - short` every iteration and stops when the gap is 1 day or less, or when the window holds at most `2 * limit`. `limit` is always at least 1 (`require(limit > 0)` in `loadOlder`), and `newestMissing` is only called when `missing.size > limit`, so the limit-0 case cannot happen. Limit 1 also terminates, through the 1-day gap rule.
- **AC2, correctness of the picked set.** After bisection, `candidates` still holds at least `limit` messages, because `span` only moves to a middle that holds at least `limit`. Candidates form a SINCE window, so every missing message outside it arrived on an earlier server day. That makes the `limit` newest of `candidates` the `limit` newest of all missing messages, given the day-mapping assumption already recorded on the task and in the KDoc. EPOCH fallback: returning all of `missing` is safe. It reads every date, which is the old behaviour, and it also covers messages dated before 1970. A message expunged or arriving between `uidsFrom` and `uidsSince` is dropped by the filter against `missing`, which is harmless. Undated messages are never in a window and are still loaded once they fit the limit (paging test).
- **AC3.** The class KDoc now describes the Message-ID lookup of linked rows under Full sync, not under Incremental sync.
- **AC4 / tests.** I could not run Gradle. The parent's results on HEAD are listed in the request: `check` reports 4575 tests with one unrelated `IcsFeedClientTests` flake, which passed on rerun. Focused suites: LinkServiceTests 21/21, MailHeaderStoreTests 24/24, MailHeaderSyncServiceTests 41/41. `integrationTest --tests '*mail.sync*'`: HeaderSyncGreenMailTests 14/14, HeaderSyncDovecotTests 2/2, MailIdle* 5/5. The new unit tests use a fake whose SINCE search compares by day. They cover: doubling, bisection, a cached outlier that must not widen the window, the EPOCH fallback, a folder without cached messages, gaps newer than the oldest cached message, a 10-year archive (100..200 dates read), and paging across calls including an undated message.

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
    - Problem: One KDoc line of `newestMissing` is 149 characters long ("...only their dates need to be read. A server that takes each message's day in its own offset can leave a message up to"). The rest of the file wraps at about 120 characters. This looks like a leftover from the round-2 edit.
    - Why it matters: It hurts readability and is inconsistent with the file. It has no functional impact, and ktlint did not flag it.
    - Suggested fix: Re-wrap the paragraph at the file's usual width.

## Review conclusion

No material issues found. CR-1 is an optional cosmetic cleanup.
