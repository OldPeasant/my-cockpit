# Code Review

Date: 2026-10-25

Task: bookmarks-snapshot - Readable snapshots

Scope: the whole task diff `d2c3d585a..20a0f47b8` (committed changes only). This is the second review, on the final code after the CR-1 fix (production change in 86c1da611 and 20a0f47b8, `ReadableExtractor.kt`; tests in fabb56e30). The uncommitted change to `reviews/bookmarks-snapshot/tests.md` is a helper report and was not reviewed.

Requirements:
- `tasks/bookmarks-snapshot.md` acceptance criteria 1-2 and recorded assumptions #1-#3 (`tasks.py assumptions`)
- SPEC.md §8.4 (readable snapshot), §4.2 (Problem Details, ETag/If-Match)
- docs/ARCHITECTURE.md and the AGENTS.md project notes (module boundaries, logging, generated files)
- Previous review (`reviews/bookmarks-snapshot/review.md`, first revision) and `reviews/bookmarks-snapshot/fixes.md`
- Existing code: `BookmarkService`, `PageFetcher`, `FilesService`, the Mozilla Readability approach that criterion 1 names as the model

Git range:
- Branch: main
- HEAD: 20a0f47b8
- Base/start: d2c3d585af1d8d3c52e1ac0582bc9bd83046cdb0
- Commits reviewed: 6 (4217b37fc, 326e092b4, 79d9790e5, 86c1da611, fabb56e30, 20a0f47b8)

Summary: 0 P0, 0 P1, 1 P2, 1 P3 (open). CR-1 is resolved and CR-2 is accepted as assumption #3. CR-3 is still open. CR-4 is new and was introduced by the CR-1 fix.

Checked without findings:
- CR-1 fix:
    - `form` is no longer in `NEVER_CONTENT`. The form controls (`input`, `button`, `select`, `textarea`) are still removed in both passes.
    - The retry (`extract`, lines 46-50) parses the page again, so the first pass's changes to the tree do not leak into it.
    - The retry result goes through the same `cleaned()` step (`Cleaner(SAFELIST)` plus `removeEmpty`), so the Cleaner drops a kept `<form>` tag and its attributes. The test `the result of the retry is sanitized like the first` covers this with the full XSS vector set.
    - Both passes still remove `NEVER_CONTENT`, hidden elements and unlikely roles (lines 80-85). Tests: `hidden elements, navigation and footers stay out of the retry`, `roles that are never content are dropped in the retry as well`.
    - The title comes from the first parse, before any mutation.
    - Retry selection works like Readability: the retry runs only below 500 characters, and the longer result wins (a tie keeps the first). The boundary is tested at 499 and 500.
    - `wrapsMain` uses `selectFirst`, which also matches the element itself. So a `[role=main]` element with an unlikely class or role protects itself.
- Unchanged since the first review and re-read:
    - Controller and 502 Problem Details with `errorCode`.
    - If-Match is checked before the fetch.
    - The fetch runs outside a transaction, and the URL is re-checked in the write transaction.
    - SSRF protection comes from reusing `PageFetcher`.
    - Logging contains only the id and fixed messages.
    - File name sanitizing.
    - `api/openapi.yaml` and the generated web client match.
- Tests: the parent reports 58 passing tests on HEAD in `ch.sonensei.cockpit.bookmarks.snapshot.*` (web tests against Postgres via Testcontainers) and ReadableExtractor coverage of 209/209 lines and 124/148 branches. I could not run Gradle here. Both criteria are still unverified in `tasks.py verify` (the parent records them).

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-4 | `wrapsMain` protects any element that contains an `<article>`, so comment and teaser sections are no longer removed in the first pass**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/bookmarks/snapshot/ReadableExtractor.kt:130` (`wrapsMain`, with `MAIN_ELEMENTS` at line 214), used at lines 84 and 89
    - Category: Correctness
    - Problem: The guard is meant to keep wrappers of the page's main element. It actually keeps every unlikely, form or unlikely-role element that contains any `article` element. Pages commonly use `<article>` for individual comments (the HTML spec recommends it, and WordPress core renders `<article class="comment-body">` inside `<div id="comments" class="comments-area">`) and for teaser cards in "related" blocks. Here is a static trace for `<article class="post"><p>…</p>…<div id="comments" class="comments-area"><ol class="comment-list"><li class="comment"><article class="comment-body"><p>…</p></article></li></ol></div></article>`:
        1. Before the fix, `#comments`, `.comment-list` and `li.comment` all matched `UNLIKELY` and were removed.
        2. Now every one of them contains an `<article>`, so they all stay. The `article.comment-body` elements themselves are also kept, because `article` is in `KEEP_TAGS`.
        3. `article.post` wins the scoring (+10 tag, +25 for the "post" class), and its whole subtree is cloned (line 118), comments included.
        4. The longer text also makes the 500-character retry less likely to run.

        The same happens with `<div class="related"><article class="teaser">…` and with `role="complementary"` blocks of teaser articles (line 84). No test covers an unlikely block that contains a non-main `<article>`. The existing comment test (`keeps the main content and drops navigation, sidebar, comments and footer`) uses a comment block without `<article>`.
    - Why it matters: Criterion 1 requires extracting the main content. This is a regression the fix introduced: for a common markup pattern, the saved copy now includes reader comments or teasers that the first version removed.
    - Suggested fix: Protect only the ancestors of the page's main element, not of any article. Two options:
        - Compute the main element once per pass: `main`/`[role=main]` if present, otherwise the outermost `article` with the most text (or one that holds most of the body text). Then skip removing exactly its ancestors.
        - Restrict `MAIN_ELEMENTS` to `main, [role=main]` and require an `article` to hold most of the wrapper's text.

        Add a test with a comment section of `<article>` items inside, or next to, the post article, checking that the comments are dropped. Also add a test for a `div.related` with teaser articles.

## P3 Low

- [x] **CR-2 | Snapshots are silently truncated at the metadata fetch limit** (accepted)
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/bookmarks/snapshot/BookmarkSnapshotService.kt:73`
    - Category: Correctness
    - Problem: The snapshot reuses the 1 MB metadata fetch limit, and a longer page is cut off without any hint.
    - Why it matters: A user may not notice that the copy is incomplete.
    - Suggested fix: Already done for this task: assumption #3 records it for a human to decide. A larger snapshot limit or a truncation flag needs `bookmarks/infra`, which is outside this task's touches, so it is a follow-up. No further change is needed in this task.

- [ ] **CR-3 | The snapshot service duplicates BookmarkService's change bookkeeping**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/bookmarks/snapshot/BookmarkSnapshotService.kt:93-97`
    - Category: Architecture
    - Problem: Re-assessed and still present. The write transaction saves through `BookmarkRepository` and repeats the private `BookmarkService.changed` by hand: `Hibernate.initialize(tags)`, `indexer.indexBookmark`, and `changes.publish(BOOKMARK, id, UPDATED)`. The `BookmarkService` KDoc (line 47) says every change re-indexes and publishes an event. The steps match today, and `indexBookmark` is already shared with the Trash restore, so this is a known second path rather than a defect. It still has to be kept in sync by hand.
    - Why it matters: A step added later to `BookmarkService.changed` (for example link-index upkeep) would silently be skipped for snapshots.
    - Suggested fix: Moving the write into `BookmarkService` needs `bookmarks/domain`, which is outside this task's touches. The cheap fix within scope is a short comment at lines 93-97 that points to `BookmarkService.changed`. Either add that, or record "accepted, follow-up" in quality.json and an assumption. It does not block the task.

## Resolved

- [x] **CR-1 | Page-wide wrappers are removed before scoring and nothing falls back** (resolved)
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/bookmarks/snapshot/ReadableExtractor.kt:46-50, 80-91`
    - Category: Correctness
    - Problem (original): A `<form>` wrapper or an unlikely-class wrapper around the article took the content with it, and the result was `NO_CONTENT` or a truncated copy.
    - Resolution: Forms and unlikely class/id elements are now removed only in the first pass, and only when they do not contain a main element. A fresh-parse retry without that removal runs below 500 characters, and the longer result wins. Both results are sanitized by the same path. Regression tests cover:
        - a form wrapper with and without an article;
        - an unlikely-class wrapper with and without an article;
        - `main` and `[role=main]` inside a wrapper;
        - the retry boundary;
        - retry sanitization and the removal of hidden content and never-content elements in the retry.

        The ASP.NET and `has-sidebar` layouts from the original finding are handled. The over-broad guard is tracked separately as CR-4.

## Review conclusion

Material issues found; fix before completing the task. The CR-1 fix works for the layouts it targeted, and the sanitization of the retry result is correct. However, the `wrapsMain` guard (CR-4, P2) brings back comment and teaser sections for pages that mark them up with `<article>`. CR-3 (P3) remains for the parent to decide (a pointer comment or an accepted follow-up).
