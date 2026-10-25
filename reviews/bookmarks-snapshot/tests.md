# Test Report

Date: 2026-10-25

Task: bookmarks-snapshot - Readable snapshots

Scope: third pass, after the CR-4 fix. Base d2c3d585af1d8d3c52e1ac0582bc9bd83046cdb0 (from the parent). The final measured revision is HEAD 37463204ea972219a0c39883e65d5d26a62935b7 on branch main.

The parent rebased the task onto main. The CR-4 fix commit f9ecfc287 is now 4abbaab0c. It changes `ReadableExtractor.kt`:
- only the page's main element (`main`/`[role=main]`, otherwise the outermost `article` with the most text) and its ancestors are protected;
- `article` and `main` were removed from `KEEP_TAGS`;
- `kept` is now built with `listOf(it) + it.parents()`.

The same commit adds a comment to `BookmarkSnapshotService.kt` and 3 CR-4 tests. Commit 37463204e adds this pass's test.

Production paths: `server/src/main/kotlin/ch/sonensei/cockpit/bookmarks/snapshot/`, 3 files, all added by this task.

Excluded:
- generated `api/openapi.yaml` and `web/src/app/api/generated/`;
- other tasks' commits that came in with the rebase (`journal/backup`, other `tasks/` and `reviews/` files): they are outside this task.

Requirements:
- `tasks/bookmarks-snapshot.md`: acceptance criteria 1-2 and the recorded assumptions, including the 22:12 CR-4 assumption (a page without a main element whose largest article is a teaser is an accepted limitation)
- SPEC.md 8.4 (readable copy) and 4.2 (Problem Details, ETag/If-Match)
- `reviews/bookmarks-snapshot/review.md` CR-4 and `reviews/bookmarks-snapshot/fixes.md` (the intended main-element protection)
- KDoc contract of `ReadableExtractor` ("The page's main element ... and the forms or unlikely elements around it are kept")
- docs/ARCHITECTURE.md "Coverage" and docs/QUALITY.md

## Test files changed

- `server/src/test/kotlin/ch/sonensei/cockpit/bookmarks/snapshot/ReadableExtractorEdgeCaseTests.kt`: this pass appended 1 test. The parent committed it in 37463204e.

## Tests added or updated

Count: 1 in this pass, in `ReadableExtractorEdgeCaseTests`.

`the main element itself is kept in the first pass even when its class looks unlikely` runs two variants:
- `<article class="share-header">` (no `main` element, so the article is the main element);
- `<div role="main" class="share-header">`.

Each variant has three prose paragraphs (more than 500 characters) and the WordPress-style comment section. The test requires that the prose is kept and the comments are dropped.

Gap closed: since `article` and `main` left `KEEP_TAGS`, only the `listOf(it)` part of `kept` protects the main element itself. Before this pass, no test failed without it:
- `an article element is never dropped because of its class` uses one paragraph (under 500 characters), so the retry would bring the article back;
- the other CR-1/CR-4 fixtures use main elements without an unlikely class.

If the first pass dropped the main element, the retry would run and keep everything, so the comments would appear in the result. The jsoup `Iterable` bug (`parents() + it`) is covered by `a comment section with an article per comment inside the post is dropped`, which failed before the parent's correction.

The earlier tests (61) were not changed.

## Requirement coverage

"Final run" means `server/gradlew -p server ktlintCheck test --tests 'ch.sonensei.cockpit.bookmarks.snapshot.*' jacocoTestReport`, run by the parent from the clone root on HEAD 37463204e. Result: pass, 62 tests, 0 failures/skipped, ktlint clean, Postgres via Testcontainers.

The test-writer checked the JUnit XML in `server/build/test-results/test/`, timestamps 21:15:52Z to 21:16:09Z:

| Suite | Tests | Failures | Errors | Skipped |
|---|---:|---:|---:|---:|
| ReadableExtractorTests | 12 | 0 | 0 | 0 |
| ReadableExtractorEdgeCaseTests | 30 | 0 | 0 | 0 |
| BookmarkSnapshotWebTests | 8 | 0 | 0 | 0 |
| BookmarkSnapshotEdgeCaseWebTests | 12 | 0 | 0 | 0 |

The new test's name appears in the `ReadableExtractorEdgeCaseTests` XML.

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: main content extracted with Readability-style heuristics | `keeps the main content and drops navigation, sidebar, comments and footer`, `div with only text counts as a paragraph`, `hidden elements are dropped`, `elements with navigation or complementary roles are dropped`, `a block made of links loses against prose`, `a prose paragraph next to the best container is kept, an unrelated link list is not`, `paragraphs in lists and headings lose against prose, paragraphs in a quote count`, `an article element is never dropped because of its class`, `class names that look unlikely but also say content are kept` | Final run | Pass |
| AC1 / CR-4: comment and teaser articles are not protected; they are removed with their unlikely block or never-content role | `a comment section with an article per comment inside the post is dropped`, `a comment section with an article per comment next to the post is dropped` (inside `main` and inside a plain `div`), `a related block or a complementary role with teaser articles is dropped` | Final run | Pass |
| AC1 / CR-4: the main element itself is protected in the first pass (`listOf(it)`) | new `the main element itself is kept in the first pass even when its class looks unlikely` | Final run | Pass |
| AC1 / CR-1: wrappers (ancestors) of the main element are kept: form, unlikely class/id, `main`, `[role=main]`, never-content role | `a form wrapping the whole page keeps its article`, `a wrapper with an unlikely class around the article is kept`, `a main element or role main inside an unlikely wrapper keeps the wrapper in the first pass`, `an element with a never-content role around the article is kept, other such roles inside it are dropped` | Final run | Pass |
| AC1 / CR-1: the retry (forms and unlikely class/id elements kept) runs below 500 characters, the longer result wins, and it still drops hidden content, never-content tags and roles | `a form wrapping the whole page without an article falls back to an extraction that keeps forms`, `a wrapper with an unlikely class and no main element falls back to an extraction that keeps it`, `a short first result is replaced by the longer result of the retry`, `the first result is kept when the retry finds less text`, `the retry runs below 500 characters of text and not at 500`, `roles that are never content are dropped in the retry as well`, `hidden elements, navigation and footers stay out of the retry` | Final run | Pass |
| AC1: output is sanitized HTML (XSS / remote loads), also from the retry | `the result is sanitized - no scripts, styles, event handlers or images, absolute links`, `dangerous links and markup in the content are removed`, `markup in the title is escaped`, `XSS and remote-load vectors in the main content leave only safelisted markup`, `XSS vectors are also sanitized when no paragraph qualifies and the whole body is kept`, `the result of the retry is sanitized like the first`, `a base tag in the page is dropped and links stay http(s)`, `relative, protocol-relative and mailto links become safe absolute links, other schemes lose their href`, `the output is a complete document with doctype, no-referrer policy and no empty leftovers` | Final run | Pass |
| AC1: charset handling | `the charset of the response is used`, `an unknown or illegal charset name falls back to detection instead of failing`, `without a charset header the meta charset of the page is used` | Final run | Pass |
| AC1: title selection | `og title wins, the fallback title is used without any`, `titles collapse whitespace, blank ones fall back to the h1, then to the fallback title` | Final run | Pass |
| AC1: stored as a file (Attachments, text/html, linked via snapshotFileId, previous snapshot to Trash) | `save snapshot stores the main content ...`, `a new snapshot replaces the previous one, which goes to the Trash`, `other bookmark changes keep the snapshot`, `a new snapshot still succeeds when the user already deleted the previous one` | Final run | Pass |
| File name from title | `a file name is made from the title without separators`, `a long title is cut to 100 characters in the file name`, `a long title is not cut in the middle of a surrogate pair, and trailing blanks are trimmed`, `control characters in the title are replaced in the file name`, `a page without any title is named after the bookmark` | Final run | Pass |
| URL changed or bookmark deleted during the fetch; concurrent snapshots | `a URL changed while the page was fetched is a 409 and stores no file`, `a bookmark deleted while the page was fetched is a 404 and stores no file`, `two overlapping snapshots leave exactly one active snapshot file, the one the bookmark points to` | Final run | Pass |
| If-Match required; 404 before any fetch | `if-match is required and checked`, `an unknown or deleted bookmark is 404`, `a bookmark in the Trash is 404 and its page is not fetched`, `a stale If-Match is 412 and the page is not fetched` | Final run | Pass |
| Fetch failures -> 502 Problem Details with errorCode, URL not revealed | `a page that cannot be fetched is a 502 ...`, `a page without readable content is a 502 NO_CONTENT`, `a failed fetch answers problem details that do not reveal the URL`, `after a redirect, relative links resolve against the final page` | Final run | Pass |
| AC2: `./gradlew test --tests 'ch.sonensei.cockpit.bookmarks.snapshot.*'` passes (Testcontainers Postgres) | all 62 tests in the package | Final run (equivalent to `cd server && ./gradlew test --tests ...`) | Pass (62/62) |

## Focused test execution

Command:

`server/gradlew -p server ktlintCheck test --tests 'ch.sonensei.cockpit.bookmarks.snapshot.*' jacocoTestReport`

The parent ran it on HEAD 37463204e. The test-writer's sandbox cannot run Gradle.

Result:

Pass:
- ReadableExtractorTests 12/12
- ReadableExtractorEdgeCaseTests 30/30
- BookmarkSnapshotWebTests 8/8
- BookmarkSnapshotEdgeCaseWebTests 12/12
- ktlint clean

## Full test execution

Command:

`server/gradlew -p server check`

Result:

Not run by the test-writer (sandbox). CR-4 changed only `ReadableExtractor.kt`, which is internal to `bookmarks/snapshot`, plus a comment in the service. The parent runs `.taskcheck` on the final code.

## .taskcheck

Result:

Not run by the test-writer (sandbox; it also covers web and android). The parent runs it before `done`.

## Bugs found

None open. The parent already fixed the production bug that the first CR-4 test run exposed (commit 4abbaab0c, formerly f9ecfc287). `parents() + it` resolved to the `Iterable` overload, because a jsoup `Element` iterates its descendants, so the whole subtree was protected. `a comment section with an article per comment inside the post is dropped` covers it.

Observations (not requirement violations, no fix required):
- Accepted limitation, recorded as an assumption: on a page without `main`, if the article with the most text is a teaser or comment in an unlikely block, that block survives the first pass and the scoring decides. No test was added, by agreement with the parent.
- With several `main`/`[role=main]` elements, all of them and their ancestors are protected. This is reasonable, but not tested separately.

## Coverage

Status: verified. A fresh JaCoCo run of the task's test selection on the final production code and the final test snapshot, including this pass's test.

Metric: whole-file coverage of task-changed production files.

Changed-line coverage: equal to the whole-file numbers. All three in-scope files were added by this task, so every executable line is a changed line.

Evidence:
- **Task, base, HEAD and file scope:** task bookmarks-snapshot. Base d2c3d585af1d8d3c52e1ac0582bc9bd83046cdb0 (from the parent). Measured HEAD 37463204ea972219a0c39883e65d5d26a62935b7, after the parent's rebase onto main. The task-owned commits for this pass are 4abbaab0c (CR-4 fix) and 37463204e (this pass's test). The file scope comes from the task's `touches` and from the earlier diff `git diff --stat d2c3d585a..f9ecfc287`. The production file hashes are identical before and after the rebase, so the rebase only brought in other tasks' files.
- **Source/test snapshot** (sha256, all committed at HEAD and matching the working tree):

  | File | sha256 |
  |---|---|
  | `ReadableExtractor.kt` | 07bb4f07dc5be221335b3eaceccc444773b8427aa574afe2813c8fb71647b298 |
  | `BookmarkSnapshotService.kt` | e6e0e4e9740cc0cc131dafa14e7ffbed0f251d15eb750d8e4006b223e66df275 |
  | `BookmarkSnapshotController.kt` | c231eb689d98b0df86598f0e87fb3ca79fbaa7d202be61a5339cfaa917ed3b0a |
  | `ReadableExtractorTests.kt` | 72c0b17b985d31123a1930b8f5b97652270df9c8503d21eca7e38d345167c3c7 |
  | `ReadableExtractorEdgeCaseTests.kt` | 997b0d3234e094d34db26d120faf9c1d38f9b13f28d48c1ecb2c17449255d354 |
  | `BookmarkSnapshotWebTests.kt` | 272d49d366a776ba207c2b0fbbe71dcbfc3d97a77cdc8614a71638d72022bdfb |
  | `BookmarkSnapshotEdgeCaseWebTests.kt` | d1aae5d2df276e225f3f9e54ca71103c97905045da5ef363a6cb11f8418dfaf9 |

  The only uncommitted files are the reports in `reviews/bookmarks-snapshot/`, which are not test inputs.
- **Included and excluded files:** included `server/src/main/kotlin/ch/sonensei/cockpit/bookmarks/snapshot/{BookmarkSnapshotController,BookmarkSnapshotService,ReadableExtractor}.kt`. Excluded: `api/openapi.yaml` (generated spec, no executable code), `web/src/app/api/generated/**` (generated client, not instrumented by the server run), and other tasks' files from the rebase. No renames or deletions.
- **Provider, command and selection:** JaCoCo via the Gradle `jacoco` plugin (default tool version, no override). The parent ran `server/gradlew -p server ktlintCheck test --tests 'ch.sonensei.cockpit.bookmarks.snapshot.*' jacocoTestReport` from the clone root. The selection is the 4 classes of the snapshot package, 62 tests. The test-writer read the counters of package `ch/sonensei/cockpit/bookmarks/snapshot` from the XML's `sourcefile` elements with `.tmp/jacoco_scope.py`.
- **Run times and exit status:** JaCoCo session `dell26-f44a61d5`, start 1792962951662 ms, dump 1792962969634 ms (2026-10-25, about 22:15:51 to 22:16:09 +01:00). `test.exec` was written at 22:16:09 and the XML at 22:16:10. Exit status: pass, per the parent, and the JUnit XML shows 62 tests with 0 failures, errors or skips.
- **Report paths and run identifier:** `server/build/reports/jacoco/test/jacocoTestReport.xml` (sha256 c7248a0bec98ec16b4f0d5d9d03c0781a03d79808b3efb27484239c5947d094e) and `server/build/jacoco/test.exec`. Session `dell26-f44a61d5`.
- **Freshness:** yes. This session is newer than the one for the pre-test run (`dell26-a6e5658f`, 22:11:45 to 22:12:03). The `ReadableExtractorEdgeCaseTests` XML shows 30 tests, including the new one, and the input hashes match the committed HEAD.
- **Final run after the latest test additions and production fixes:** yes.

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A. JaCoCo "Statements" is the INSTRUCTION counter.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `server/src/main/kotlin/ch/sonensei/cockpit/bookmarks/snapshot/ReadableExtractor.kt` | 1666/1685 (98.9%) | 126/150 (84.0%) | 20/20 (100%) | 216/216 (100%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/bookmarks/snapshot/BookmarkSnapshotService.kt` | 374/398 (94.0%) | 27/32 (84.4%) | 9/9 (100%) | 63/65 (96.9%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/bookmarks/snapshot/BookmarkSnapshotController.kt` | 52/52 (100%) | N/A (0 branches) | 4/4 (100%) | 8/8 (100%) |
| Aggregate (summed counters, 3 files; generated and other-task files excluded as above; no unmeasured files) | 2092/2135 (98.0%) | 153/182 (84.1%) | 33/33 (100%) | 287/289 (99.3%) |

The counters are identical to the pre-test run (`dell26-a6e5658f`). The CR-4 code was already fully covered there. The new test adds a behavioural check (a regression would fail), not new coverage.

The CR-4 code has no missed instructions or branches. This includes line 84 (`kept`) and lines 137-145 (`mainElements`, with the no-article `listOfNotNull` path and the nested-article filter).

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `ReadableExtractor.kt:84` (`listOf(it)`: the main element protects itself) | CR-4: the main element must survive the first pass even when its class looks unlikely | The line was covered, but no test failed without it. The new test `the main element itself is kept in the first pass even when its class looks unlikely` passes. |
| `ReadableExtractor.kt:88,93` (`it.parent() != null` false) | Removing an element inside one that was already removed | Not material: a guard against removing a detached node twice, with the same outcome either way. |
| `ReadableExtractor.kt:102,103,115,119,120,151,175,182,183,191` (pre-existing scoring, sibling, prose and removeEmpty short-circuits) | Null-safety guards and alternate operands of the same boolean expressions | Not material: every line runs, and the heuristics are covered at the behavior level (first pass). CR-4 did not change these lines; they only shifted. |
| `BookmarkSnapshotService.kt:111-112` | Logging a non-NotFound failure while trashing the previous snapshot (best effort) | Left uncovered: it needs a failing `FilesService` bean in the shared context, and it only logs after the snapshot was saved. The NotFound path is covered. |
| `BookmarkSnapshotService.kt:86` (`checkNotNull` null branch) | `TransactionTemplate.execute` returning null | Unreachable: the callback returns the entity or throws. |
| `BookmarkSnapshotService.kt:101` (2 of 8 branches) | Previous id equal to the new id | Unreachable in practice: each file gets a new UUIDv7. |
| `BookmarkSnapshotService.kt:137` (`ifEmpty { "Page" }`) | Empty title after cleaning | Unreachable: the title is trimmed and never empty. |

## Unable to test

- Orphaned blob content after a rolled-back snapshot transaction: a `files` module concern that the snapshot API cannot observe. Unchanged from the first pass.
- Fetch timeout -> 502 TIMEOUT: generic error mapping, covered by the bookmarks fetcher tests outside this task.
- Silent truncation at the 1MB fetch limit (CR-2): accepted as an assumption and not tested here, because it is outside the task's touches.
- Rendering of the HTML snapshot in the web reader: out of scope, as recorded in an assumption.

## Summary

Tests and task-scoped coverage are sufficient; no production defects found. This pass added 1 test for the only real CR-4 gap: the main element protecting itself in the first pass. Before it, the retry would have masked a regression there.

The snapshot package passes 62/62 on HEAD 37463204e, with ktlint clean. Fresh task-scoped coverage on the final code across the 3 task files:

| Metric | Covered/total | Percent |
|---|---:|---:|
| Statements | 2092/2135 | 98.0% |
| Branches | 153/182 | 84.1% |
| Lines | 287/289 | 99.3% |

All CR-4 lines are fully covered, and the remaining gaps are justified above.
