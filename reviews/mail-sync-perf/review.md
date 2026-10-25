# Code Review

Date: 2026-10-25

Task: mail-sync-perf - Cheaper link checks and loadOlder candidate narrowing in mail sync

Scope: second review on the final code. `git diff 0f66c50c..e8aa3f4d` (all task commits on top of the merge base with
origin/main), committed changes only. It checks the fixes of CR-1..CR-3 from the first review (on 98e9f945, before
the rebase) and reviews the final diff afresh.

Reviewed revision: e8aa3f4d72214c9f738aa02f4610ad04f7c1eb96

Requirements:
- `tasks/mail-sync-perf.md` acceptance criteria (#1-#4)
- `reviews/mail-header-sync/review.md` CR-6, CR-7, CR-8 (origin of the task)
- `AGENTS.md` project notes, `docs/ARCHITECTURE.md` conventions
- `reviews/mail-sync-perf/fixes.md`, `reviews/mail-sync-perf/tests.md`
- Parent instructions: verify the fixes are correct and complete, review the final diff afresh

Git range:
- Branch: main
- HEAD: e8aa3f4d72214c9f738aa02f4610ad04f7c1eb96
- Base/start: 0f66c50cd5799eb033006c685e971562070fcf01 (`git merge-base HEAD origin/main`)
- Commits reviewed: 5 (b6beff59, ca4f9b1c, cd0774df, 12d84719, e8aa3f4d); working tree clean

Summary: 0 P0, 0 P1, 1 P2, 0 P3

Checks run by the reviewer:
- `cd server && ./gradlew test --tests '*MailHeaderSyncServiceTests*' ktlintCheck` -> BUILD SUCCESSFUL
  (MailHeaderSyncServiceTests: 40 tests, 0 failures, 0 skipped).
- I could not run `LinkServiceTests`, `MailHeaderStoreTests` (Postgres/Testcontainers) or `HeaderSyncGreenMailTests`
  (`external`) because no Docker daemon is available. So I checked the derived exists queries and the real IMAP
  `SEARCH SINCE` path only by reading the code.
- `python3 tasks.py verify mail-sync-perf`: none of criteria #1-#4 has recorded evidence yet.

Status of the earlier findings:
- CR-1 (window anchored on `min(receivedAt)`): fixed as stated. `newestMissing`
  (`MailHeaderSyncService.kt:226-241`) now grows the window from `clock.instant()` and no longer depends on the
  cache. `oldestReceivedAt` was removed from the store and the repository, and nothing references it any more. The
  regression test `loadOlder keeps the candidates narrow when one far older message is cached` covers the outlier.
  Correctness holds: every window back from now contains all missing messages newer than its cut. But the new anchor
  brings in a different cost problem. See CR-1 below (new finding).
- CR-2 (`count > 0` instead of exists): fixed. `existsBySourceTypeAndSourceId` and `existsByTargetTypeAndTargetId`
  (`LinkRepository.kt:24-34`) are derived exists queries, which Spring Data runs as limit-1 selects. They are backed
  by `link_source` and `link_target` (`V202610201400__core_items_link.sql:28-29`). `hasLinks` short-circuits the
  second query. `isLinked` uses it (`MailHeaderStore.kt:171`). The Postgres tests cover both directions, a type
  mismatch with the same id, and SYNC links (`LinkServiceTests`, `MailHeaderStoreTests`), but they have not been run.
- CR-3 (single-timezone assumption): fixed as a comment-only change. The KDoc (`MailHeaderSyncService.kt:217-225`)
  now states the condition and what happens on a per-offset server.

Checked and found sound (no finding):
- AC #3: the Message-ID lookup sentence is under **Full sync** (`MailHeaderSyncService.kt:56-57`), not under the
  incremental sync.
- Loop termination in `newestMissing`: the span doubles from 7 days until `since <= EPOCH` (12 steps from 2026). The
  exit on `candidates.size == missing.size` is correct. At the call site, `missing.size > limit` always holds.
  Undated or pre-epoch messages are reached through the epoch fallback (test `... reaches the epoch`).
- The arrival-order sort and the tie-break on UID are unchanged. `hasMore` is still computed from `missing.size`.
- Architecture: mail reaches links only through `LinkService` (core). No schema change, no new dependency, no
  secrets, and no logging of message data.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | Doubling from now overshoots up to the cached frontier's age, so deep pages read the dates of almost every uncached message again**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/mail/sync/MailHeaderSyncService.kt:231-240` (loop), KDoc `:217-219` and class comment `:61-63`
    - Category: Correctness (performance, AC #2)
    - Problem: the window is `[now - 7d * 2^k, now]` for the smallest k that holds `limit` missing UIDs. Once the
      cache is contiguous from now back to age A (the normal state after a few `loadOlder` pages), every window
      shorter than A holds only the gaps. The first window that holds enough has a span between A and 2A, so the
      candidates are *all* missing messages with an age between A and 2A, and their INTERNALDATE is fetched on every
      page. Example: an archive with 10 years of evenly spread mail, cached down to A = 1825 days (5 years). The span
      1792 d holds no candidates, so the next span is 3584 d (about 9.8 years). Each page of 100 then fetches the
      dates of about 4.8 years of mail, which is about 96% of the uncached messages. That is the CR-7 cost again. In
      general, for a mailbox whose oldest mail is at most 2A old, every uncached message is a candidate. The class
      comment's wording "the shortest window back from now that holds enough of them" is therefore inaccurate: the
      window is only the shortest power-of-two window. No test has a cache that is contiguous down to a deep frontier,
      so the tests don't show this. They use spans just past the cut, for example
      `paging with loadOlder ...` and `... after the oldest cached one first`.
    - Why it matters: AC #2 requires that `loadOlder` stop reading INTERNALDATE of (nearly) every uncached message on
      every page. Deep paging through a large archive is exactly the case CR-7 targeted. The fix for the old CR-1
      solved the outlier case but made this common case cost about as much as before the task, while
      the folder lock is held. The results stay correct.
    - Suggested fix: keep the doubling to find the bracket. `lo` is the last span with too few candidates and `hi`
      the first with enough. Then bisect between them with `uidsSince`, which is any window back from now and so
      just as correct. Stop when the window holds between `limit` and about `2 * limit` missing UIDs, or when the
      bracket is one day wide (`SINCE` works by day). That adds at most about log2(A in days) ≈ 12 cheap UID
      searches and bounds the date fetch to about `limit` plus one day of mail. Add a unit test in which the cache
      is contiguous from now down to a deep frontier and assert the size of `receivedDates`. Fix the KDoc and class
      comment to match.

## P3 Low

None.

## Review conclusion

Material issues found; fix before completing the task. The fixes for CR-2 and CR-3 are correct and complete. The CR-1
fix removes the outlier problem, but it brings in the overshoot described in the new CR-1 (P2), which undoes most of
the AC #2 saving when paging deep into large folders. Separately, the Postgres tests for AC #1 (`hasLinks`,
`isLinked`) and the `external` GreenMail sync tests for AC #4 have no run evidence, because Docker is unavailable
here. They must be run where Docker exists, or be recorded as skipped with that reason. They must not be claimed as
verified.
