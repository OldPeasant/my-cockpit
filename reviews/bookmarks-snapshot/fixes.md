# Fix Report

Date: 2026-10-25

Task: bookmarks-snapshot - Readable snapshots

Scope: current task, findings CR-4 and CR-3 of the second review (`reviews/bookmarks-snapshot/review.md`, HEAD 827d5048e, base d2c3d585a), with the approaches approved by the parent worker. CR-2 is accepted as assumption #3 and needed no change.

Previous round: the first fix round resolved CR-1 (page-wide `<form>` and unlikely-class wrappers removed the content). Forms and unlikely class/id elements are now removed only in a first pass and only when they wrap the main element, and a fresh-parse retry without that removal runs below 500 characters. That fix is in commits 86c1da611, fabb56e30 and 20a0f47b8. The second review confirmed it as resolved. Its over-broad guard (`wrapsMain`) is CR-4 below.

## Fixed findings

### CR-4 — `wrapsMain` protects any element that contains an `<article>`, so comment and teaser sections are no longer removed in the first pass

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/bookmarks/snapshot/ReadableExtractor.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/bookmarks/snapshot/ReadableExtractorEdgeCaseTests.kt`

Problem:
I confirmed this in the code before changing it. `wrapsMain(element)` was `element.selectFirst("article, main, [role=main]") != null`, so every unlikely-class, form or unlikely-role element containing any `<article>` was kept. Both the role removal and the unlikely removal used this check. `article` was also in `KEEP_TAGS`. As a result, a WordPress `div#comments.comments-area` with an `<article class="comment-body">` per comment, or a `div.related` / `[role=complementary]` block of teaser articles, survived the first pass. Inside the winning post article, they were cloned into the snapshot.

Fix:
- New `mainElements(body)` runs once per pass, after the never-content and hidden elements are removed. It returns every `main` / `[role=main]` element if there are any. Otherwise it returns the outermost `article` (one not nested in another article) with the most text, or nothing.
- The elements to keep are those main elements plus all their ancestors (`kept`, an identity hash set, built with `listOf(it) + it.parents()`). Both the unlikely-role removal and the form/unlikely class/id removal now skip exactly the elements in `kept`, instead of skipping any element that contains an article.
- `MAIN_ELEMENTS` is now `main, [role=main]`. `article` and `main` were removed from `KEEP_TAGS`, which is now `html, body`. The main element itself is still protected through `kept`, but other articles (comments, teasers) no longer protect themselves or their wrappers. `wrapsMain` was removed. The object KDoc was updated.
- Unchanged: the retry pass, sanitization, scoring and title.
- The CR-1 wrapper tests should still hold. I traced each one by hand: form around an article, `has-sidebar` wrapper around an article, `main` / `role=main` inside an unlikely wrapper, `role=complementary` around the article, and an article with an unlikely class. In each, the protected element is the single main element or the only top-level article, so the result is the same.
- Tests added in `ReadableExtractorEdgeCaseTests` (section "only the page's main element protects its wrappers (CR-4)"). Each uses three prose paragraphs (more than 500 characters), so no retry runs and the first pass alone decides:
    - `a comment section with an article per comment inside the post is dropped`: WordPress-style `#comments.comments-area > ol.comment-list > li.comment > article.comment-body` inside `article.post`. This failed with the old guard, because the whole post subtree was cloned with its comments.
    - `a comment section with an article per comment next to the post is dropped`: the same comment section as a sibling of the post, once inside `<main id="main">` and once inside a plain `div.site-content` (no main, so the post is the largest top-level article).
    - `a related block or a complementary role with teaser articles is dropped`: `div.related` and `div[role=complementary]`, each holding two `article.teaser` elements, inside the post. Both variants failed with the old guard.

Corrections by the parent worker after the first test run (2 of the 3 new tests failed). Both are in commit f9ecfc287:
- Production bug in the fixer's change. `kept` was first built with `it.parents() + it`. A jsoup `Element` is an `Iterable<Element>` over its descendants, so `+ it` resolved to the Iterable overload of `plus`. That added the main element's whole subtree to `kept`, which protected the comment section inside `article.post`. The parent changed it to `listOf(it) + it.parents()`, with a comment, in `mainContent`.
- Test fixture. `div.related-posts` matches `MAYBE` ("post"), so by design it is not an unlikely block. The parent changed the fixture to `class="related"`, with a comment.

Validation:
- Run by the parent worker after both corrections: `server/gradlew -p server ktlintFormat ktlintCheck test --tests 'ch.sonensei.cockpit.bookmarks.snapshot.*' jacocoTestReport` — Pass. 61 tests, 0 failures: ReadableExtractorTests 12, ReadableExtractorEdgeCaseTests 29, BookmarkSnapshotWebTests 8, BookmarkSnapshotEdgeCaseWebTests 12. That is the 58 previous tests plus the 3 new ones.

Residual limitation (not a finding, for the record): a page with no `main`, `[role=main]` or post `<article>`, whose only `<article>`s are teasers in an unlikely block, still protects the largest teaser and its block in the first pass. This is the behaviour of the approved "keep it simple" approach. In that case the scoring still decides between the block and the real content.

### CR-3 — The snapshot service duplicates BookmarkService's change bookkeeping

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/bookmarks/snapshot/BookmarkSnapshotService.kt`

Problem:
The write transaction repeats the private `BookmarkService.changed` by hand (`Hibernate.initialize(tags)`, `indexer.indexBookmark`, `changes.publish(BOOKMARK, id, UPDATED)`). Nothing pointed out that the two must stay in sync.

Fix:
Added a one-line comment before `repository.saveAndFlush(current).also { ... }`. It says the block mirrors `BookmarkService.changed` (tags init, re-index, UPDATED event) and that both must be kept in sync. There is no code or behaviour change, as approved. Moving the write into `BookmarkService` stays a follow-up because `bookmarks/domain` is outside this task's touches.

Validation:
- Comment only. Covered by the parent worker's ktlint, compile and test run: `server/gradlew -p server ktlintFormat ktlintCheck test --tests 'ch.sonensei.cockpit.bookmarks.snapshot.*' jacocoTestReport` — Pass.

## Not fixed

### CR-2 — Snapshots are silently truncated at the metadata fetch limit

Status:
Out of scope

Reason:
The review already marks it accepted (assumption #3). A larger limit or a truncation flag needs `bookmarks/infra`, which is outside this task's touches. No change was requested.

## Validation summary

The fixer's sandbox cannot run Gradle. The parent worker ran every command below on commit f9ecfc287.

Focused tests:
- First run, on the fixer's version: 2 of the 3 new tests failed. One cause was the `+ it` production bug, the other the `related-posts` fixture. Both are described under CR-4.
- After the parent's corrections: `server/gradlew -p server ktlintFormat ktlintCheck test --tests 'ch.sonensei.cockpit.bookmarks.snapshot.*' jacocoTestReport` — Pass. 61 tests, 0 failures.

Full test suite:
- `cd server && ./gradlew check` — not run yet (left to the parent worker)

Lint/type-check/build:
- `ktlintFormat ktlintCheck` and compile, as part of the focused run above — Pass

`.taskcheck`:
- Not run yet (`sh scripts/check.sh`, left to the parent worker)

Remaining unresolved findings:
0 (CR-2 is accepted and out of scope)

## Final status

All validated findings are fixed and the focused validation passed, after the parent worker's two corrections. The full `./gradlew check` and `.taskcheck` are still for the parent worker to run.
