# Code Review

Date: 2026-10-25

Task: bookmarks-snapshot - Readable snapshots

Scope: the whole task diff `d2c3d585a..37463204e`, committed changes only. This is the fourth review, on the final code. The focus is commit 37463204e, which adds one test. The rest of the task diff was checked against the previous review of f9ecfc287. Uncommitted changes to the helper reports in `reviews/bookmarks-snapshot/` were not reviewed.

Requirements:
- `tasks/bookmarks-snapshot.md` acceptance criteria 1-2 and recorded assumptions #1-#3
- SPEC.md §8.4 (readable snapshot), §4.2 (Problem Details, ETag/If-Match)
- docs/ARCHITECTURE.md and the AGENTS.md project notes (module boundaries, logging, generated files)
- Previous reviews of this task (findings CR-1 to CR-5) and `reviews/bookmarks-snapshot/fixes.md`
- The parent's decisions: CR-3 accepted with a pointer comment (`bookmarks/domain` is outside the touches); CR-5 accepted without a rename (cosmetic)

Git range:
- Branch: main
- HEAD: 37463204ea972219a0c39883e65d5d26a62935b7 (reviewed revision 37463204e)
- Base/start: d2c3d585af1d8d3c52e1ac0582bc9bd83046cdb0
- Commits reviewed: 9 task commits (12d3f303c, 59eecbeec, b4a2b6c39, 4e64aed61, d827c75a8, 28073f780, 09ca12ae3, 4abbaab0c, 37463204e). The other commits in the range are board commits or other tasks merged from main.

Summary: 0 P0, 0 P1, 0 P2, 0 P3 open. CR-1 and CR-4 are resolved. CR-2, CR-3 and CR-5 are accepted by the parent's decisions or assumption #3.

Checked without findings:
- Earlier conclusions still hold at HEAD:
    - The history was rebased since the last review. f9ecfc287 is now 4abbaab0c.
    - `git diff f9ecfc287 4abbaab0c -- server web api` shows only journal backup files from other tasks merged from main. Nothing under `bookmarks` changed.
    - `git diff 4abbaab0c 37463204e` touches only `ReadableExtractorEdgeCaseTests.kt` (+13 lines).
    - Production code, the API contract and the generated client are the same as in the previous review, so its conclusions on CR-1, CR-3 and CR-4, the retry, the sanitization, the controller and the persistence still hold.
- New test `the main element itself is kept in the first pass even when its class looks unlikely` (`ReadableExtractorEdgeCaseTests.kt:564-575`):
    - `share-header` matches `UNLIKELY` (`share`, `header`) and none of the `MAYBE` words, so the element is only kept because it is in `kept`.
    - `article.share-header` case: the page has no `main`, and the nested `article.comment-body` elements have an article ancestor. That makes `article.share-header` the only outermost article, so it is the main element.
    - `div[role=main].share-header` case: the element matches `MAIN_ELEMENTS`.
    - Three `prose` paragraphs give about 570 characters, more than `MIN_TEXT_LENGTH` (500), so no retry runs when the first pass works.
    - The test can fail if the code regresses. If the first pass dropped the main element, the body would be empty and the retry (`stripUnlikely = false`) would win. That retry keeps `#comments`, so `shouldNotContain "fictitious comment"` would fail. The comment block is not in `kept` (it is a descendant, not an ancestor) and is removed in the working first pass.
    - The test asserts the result text (behavior), not internals, and uses fictitious data.
- Tests and evidence:
    - The parent reports a pass on HEAD for `server/gradlew -p server ktlintCheck test --tests 'ch.sonensei.cockpit.bookmarks.snapshot.*' jacocoTestReport`: 62 tests (12 + 30 + 8 + 12), 0 failures. That is one more than the 61 reported last time, which matches the single added test.
    - I could not run Gradle here.
    - `tasks.py verify bookmarks-snapshot` still shows both criteria as unverified. The parent must record the evidence before running `done`.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None open.

- [x] **CR-5 | The test name "an article element is never dropped because of its class" no longer describes the behavior** (accepted by the parent, not renamed)
    - File: `server/src/test/kotlin/ch/sonensei/cockpit/bookmarks/snapshot/ReadableExtractorEdgeCaseTests.kt:327`
    - Category: Tests
    - Problem: Only the page's main article is protected now. The test passes because its page has a single article, but the name states a broader rule.
    - Why it matters: The name can mislead a reader. It is cosmetic.
    - Resolution: The parent accepted it without a rename to avoid another review round. It should be recorded as accepted in quality.json.

- [x] **CR-2 | Snapshots are silently truncated at the metadata fetch limit** (accepted)
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/bookmarks/snapshot/BookmarkSnapshotService.kt:73`
    - Category: Correctness
    - Problem: The snapshot reuses the 1 MB metadata fetch limit, and a longer page is cut off without any hint.
    - Why it matters: A user may not notice that the copy is incomplete.
    - Resolution: Recorded as assumption #3 for a human to decide. A separate limit or a truncation flag needs `bookmarks/infra`, which is outside this task's touches.

- [x] **CR-3 | The snapshot service duplicates BookmarkService's change bookkeeping** (accepted by the parent)
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/bookmarks/snapshot/BookmarkSnapshotService.kt:93-98`
    - Category: Architecture
    - Problem: The write repeats the private `BookmarkService.changed` steps (tags init, re-index, UPDATED event).
    - Why it matters: A step added to `BookmarkService.changed` later could be missed here.
    - Resolution: A comment at line 93 names `BookmarkService.changed` and its steps. Restructuring needs `bookmarks/domain`, which is outside the touches.

## Resolved

- [x] **CR-4 | `wrapsMain` protected any element that contained an `<article>`** (resolved in 4abbaab0c, formerly f9ecfc287)
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/bookmarks/snapshot/ReadableExtractor.kt:84-93, 137-146`
    - Category: Correctness
    - Resolution: Only the page's main element and its ancestors are protected. Comment and teaser blocks made of `<article>` items are removed again. Regression tests cover comments inside and next to the post, `div.related` and `[role=complementary]` teasers, and since 37463204e the main element itself having an unlikely class.

- [x] **CR-1 | Page-wide wrappers were removed before scoring and nothing fell back** (resolved)
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/bookmarks/snapshot/ReadableExtractor.kt:46-51, 84-94`
    - Category: Correctness
    - Resolution: Wrappers of the main element are kept in the first pass. A fresh-parse retry without class/id and form removal runs below 500 characters, and the longer result wins. Both results are sanitized the same way.

## Review conclusion

No material issues found. Reviewed revision: 37463204e. The added test is correct and catches the regression it targets. Production code is unchanged since the previous review, so its conclusions still hold. CR-2, CR-3 and CR-5 are accepted. The parent must still record the evidence for both acceptance criteria with `tasks.py verify` before running `done`.
