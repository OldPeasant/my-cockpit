# Test Report

Date: 2026-10-25

Task: bookmarks-snapshot - Readable snapshots

Scope: second pass after fix CR-1. Base d2c3d585af1d8d3c52e1ac0582bc9bd83046cdb0 (from the parent), task commits 4217b37fc..fabb56e30 (HEAD fabb56e307013efcd24b09d41d2b44c9df09a7b0, branch main): 4217b37fc implementation, 326e092b4 first test pass, 79d9790e5 review, 86c1da611 CR-1 fix (`ReadableExtractor.kt` only), fabb56e30 6 CR-1 tests. Production paths: `server/src/main/kotlin/ch/sonensei/cockpit/bookmarks/snapshot/` (3 added files). Generated `api/openapi.yaml` and `web/src/app/api/generated/` are excluded from tests and coverage. This pass changes only one uncommitted test file, `ReadableExtractorEdgeCaseTests.kt` (2 tests added). The 6 tests in fabb56e30 are not duplicated.

Requirements:
- `tasks/bookmarks-snapshot.md` acceptance criteria 1-2 and its recorded assumptions (POST /api/v1/bookmarks/{id}/snapshot with If-Match, 502 + errorCode on fetch failure / no content, HTML file in Attachments, no images, CSP, previous snapshot to Trash, 1MB fetch limit)
- SPEC.md 8.4 (readable copy), 4.2 (Problem Details, ETag/If-Match)
- `reviews/bookmarks-snapshot/review.md` CR-1 and `reviews/bookmarks-snapshot/fixes.md` (the intended retry/wrapper behavior)
- KDoc contract of `ReadableExtractor` and `BookmarkSnapshotService`
- docs/ARCHITECTURE.md "Coverage", docs/QUALITY.md

## Test files changed

- `server/src/test/kotlin/ch/sonensei/cockpit/bookmarks/snapshot/ReadableExtractorEdgeCaseTests.kt` (this pass: 2 tests appended, uncommitted)

The task's test classes in the final run: `ReadableExtractorTests` (12), `ReadableExtractorEdgeCaseTests` (26), `BookmarkSnapshotWebTests` (8), `BookmarkSnapshotEdgeCaseWebTests` (12). Only `ReadableExtractorEdgeCaseTests` changed in this pass.

## Tests added or updated

Count: 2 in this pass, in `ReadableExtractorEdgeCaseTests`:
- `hidden elements, navigation and footers stay out of the retry`: the content is only found by the retry (a form wraps everything and there is no main element). The retry must still drop `[hidden]`, `display: none`, and nav and footer elements. This matters because the sanitizer strips `hidden`/`style` and would make hidden text visible. None of the CR-1 tests checked hidden content or never-content tags in the retry.
- `an element with a never-content role around the article is kept, other such roles inside it are dropped`: covers the `wrapsMain` guard on the role-based removal (fixes.md: "the unlikely-role removal stays in both passes, with the same wrapsMain guard"). Before this test the guard was not tested, and its branch was missed in the earlier coverage run (`ReadableExtractor.kt:84`). Without the guard, both passes drop the article and the result is empty (502 NO_CONTENT).

The earlier tests (26 in the first pass, 4 from the fixer, 6 in fabb56e30) were not changed.

## Requirement coverage

"Final run" = `server/gradlew -p server ktlintCheck test --tests 'ch.sonensei.cockpit.bookmarks.snapshot.*' jacocoTestReport`, run from the clone root by the parent on HEAD fabb56e30 plus this pass's 2 tests. Result: pass, 58 tests, 0 failures/skipped, ktlint clean, Postgres via Testcontainers. The test-writer checked the JUnit XML in `server/build/test-results/test/` (20:54:54Z-20:55:29Z): 12 + 8 + 26 + 12 tests, 0 failures/errors/skipped. The test-writer's sandbox could not run Gradle test tasks itself.

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: main content extracted with Readability-style heuristics | `keeps the main content and drops navigation, sidebar, comments and footer`, `div with only text counts as a paragraph`, `hidden elements are dropped`, `elements with navigation or complementary roles are dropped`, `a block made of links loses against prose`, `a prose paragraph next to the best container is kept, an unrelated link list is not`, `paragraphs in lists and headings lose against prose, paragraphs in a quote count`, `an article element is never dropped because of its class`, `class names that look unlikely but also say content are kept` | Final run | Pass |
| AC1 / CR-1: wrappers of the main element are kept (form, unlikely class/id, `main`, `[role=main]`) | `a form wrapping the whole page keeps its article`, `a wrapper with an unlikely class around the article is kept`, `a main element or role main inside an unlikely wrapper keeps the wrapper in the first pass` | Final run | Pass |
| AC1 / CR-1: a never-content role around the article is kept (role guard) | new `an element with a never-content role around the article is kept, other such roles inside it are dropped` | Final run | Pass |
| AC1 / CR-1: retry without form/class/id removal below 500 chars, longer result wins | `a form wrapping the whole page without an article falls back to an extraction that keeps forms`, `a wrapper with an unlikely class and no main element falls back to an extraction that keeps it`, `a short first result is replaced by the longer result of the retry`, `the first result is kept when the retry finds less text`, `the retry runs below 500 characters of text and not at 500`, `roles that are never content are dropped in the retry as well` | Final run | Pass |
| AC1 / CR-1: the retry still drops hidden elements, nav and footer | new `hidden elements, navigation and footers stay out of the retry` | Final run | Pass |
| AC1: output is sanitized HTML (XSS / remote loads), also from the retry | `the result is sanitized ...`, `dangerous links and markup in the content are removed`, `markup in the title is escaped`, `XSS and remote-load vectors in the main content leave only safelisted markup`, `XSS vectors are also sanitized when no paragraph qualifies and the whole body is kept`, `the result of the retry is sanitized like the first`, `a base tag in the page is dropped and links stay http(s)`, `relative, protocol-relative and mailto links become safe absolute links, other schemes lose their href`, `the output is a complete document with doctype, no-referrer policy and no empty leftovers` | Final run | Pass |
| AC1: charset handling | `the charset of the response is used`, `an unknown or illegal charset name falls back to detection instead of failing`, `without a charset header the meta charset of the page is used` | Final run | Pass |
| AC1: title selection | `og title wins, the fallback title is used without any`, `titles collapse whitespace, blank ones fall back to the h1, then to the fallback title` | Final run | Pass |
| AC1: stored as a file (Attachments, text/html, linked via snapshotFileId, previous to Trash) | `save snapshot stores the main content ...`, `a new snapshot replaces the previous one, which goes to the Trash`, `other bookmark changes keep the snapshot`, `a new snapshot still succeeds when the user already deleted the previous one` | Final run | Pass |
| File name from title | `a file name is made from the title without separators`, `a long title is cut to 100 characters in the file name`, `a long title is not cut in the middle of a surrogate pair, and trailing blanks are trimmed`, `control characters in the title are replaced in the file name`, `a page without any title is named after the bookmark` | Final run | Pass |
| URL changed / deleted during fetch, concurrent snapshots | `a URL changed while the page was fetched is a 409 and stores no file`, `a bookmark deleted while the page was fetched is a 404 and stores no file`, `two overlapping snapshots leave exactly one active snapshot file, the one the bookmark points to` | Final run | Pass |
| If-Match / 404 before any fetch | `if-match is required and checked`, `an unknown or deleted bookmark is 404`, `a bookmark in the Trash is 404 and its page is not fetched`, `a stale If-Match is 412 and the page is not fetched` | Final run | Pass |
| Fetch failures -> 502 Problem Details with errorCode, no URL leak | `a page that cannot be fetched is a 502 ...`, `a page without readable content is a 502 NO_CONTENT`, `a failed fetch answers problem details that do not reveal the URL`, `after a redirect, relative links resolve against the final page` | Final run | Pass |
| AC2: `./gradlew test --tests 'ch.sonensei.cockpit.bookmarks.snapshot.*'` passes (Testcontainers Postgres) | all 58 tests in the package | Final run (equivalent to `cd server && ./gradlew test --tests ...`) | Pass (58/58) |

## Focused test execution

Command:

`server/gradlew -p server ktlintCheck test --tests 'ch.sonensei.cockpit.bookmarks.snapshot.*' jacocoTestReport` (run by the parent; the test-writer's sandbox requires approval for Gradle test tasks)

Result:

Pass: ReadableExtractorTests 12/12, ReadableExtractorEdgeCaseTests 26/26, BookmarkSnapshotWebTests 8/8, BookmarkSnapshotEdgeCaseWebTests 12/12; ktlint clean.

## Full test execution

Command:

`server/gradlew -p server check`

Result:

Not run in this pass. The first pass ran it before CR-1, and all 4486 tests passed. CR-1 changed only `ReadableExtractor.kt`, which is internal to `bookmarks/snapshot` and used by no other module, and this pass only adds tests in that package. The parent runs `.taskcheck` on the final code.

## .taskcheck

Result:

Not run by the test-writer (sandbox; it also includes web and android). The parent runs it before `done`.

## Bugs found

None. No production defect was found in the CR-1 change. Observations that are not requirement violations (no fix required):

- The retry re-parses the page and runs the whole heuristic again, so a page with less than 500 characters of content is extracted twice. This is a performance observation only; the page size is capped at 1MB.
- The first-pass observations still apply (initialScore is added once per paragraph, and layout tables are scored per `td`). They change heuristic weights only.

## Coverage

Status: verified. Fresh JaCoCo run of the task test selection on the final production code and the final test snapshot, including this pass's 2 tests.

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: equal to the whole-file numbers, because all three in-scope files were added by this task (`git diff --name-status d2c3d585a..HEAD` shows `A`), so every executable line is a changed line.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: bookmarks-snapshot; base d2c3d585af1d8d3c52e1ac0582bc9bd83046cdb0 and commits 4217b37fc..fabb56e30 (from the parent); HEAD fabb56e307013efcd24b09d41d2b44c9df09a7b0; scope from `git diff --name-status d2c3d585a..HEAD -- server/src/main web/src android api` and the task's `touches`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD fabb56e30 plus the uncommitted `ReadableExtractorEdgeCaseTests.kt`. sha256: `ReadableExtractor.kt` 0e0508b461e6aa830e44776546b22550fc7420ece28b51f2394aac1e5c000229, `BookmarkSnapshotService.kt` e87ed031f0d596f36037208782e78ab7a2e89e9778cf1ab953911caf52a4a5f3, `BookmarkSnapshotController.kt` c231eb689d98b0df86598f0e87fb3ca79fbaa7d202be61a5339cfaa917ed3b0a, `ReadableExtractorEdgeCaseTests.kt` da07a564736fbddf1f6067472554ac694be71f5c3db38d61b51628f22267707a, `ReadableExtractorTests.kt` 72c0b17b985d31123a1930b8f5b97652270df9c8503d21eca7e38d345167c3c7, `BookmarkSnapshotWebTests.kt` 272d49d366a776ba207c2b0fbbe71dcbfc3d97a77cdc8614a71638d72022bdfb, `BookmarkSnapshotEdgeCaseWebTests.kt` d1aae5d2df276e225f3f9e54ca71103c97905045da5ef363a6cb11f8418dfaf9. The hashes were taken at 21:52:33 before the run and checked again after the run: `ReadableExtractor.kt` and `ReadableExtractorEdgeCaseTests.kt` were re-hashed and are unchanged, and the other files are committed and unchanged at HEAD.
- Exact included files; renamed/deleted/excluded files and reasons: included `server/src/main/kotlin/ch/sonensei/cockpit/bookmarks/snapshot/{BookmarkSnapshotController,BookmarkSnapshotService,ReadableExtractor}.kt`. Excluded: `api/openapi.yaml` (generated spec, no executable code) and `web/src/app/api/generated/**` (generated client, not instrumented by the server run). No renames or deletions.
- Provider/version, command, working directory, and test selection: JaCoCo via the Gradle `jacoco` plugin (Gradle 9.8.1 default tool version, no override). Command: `server/gradlew -p server ktlintCheck test --tests 'ch.sonensei.cockpit.bookmarks.snapshot.*' jacocoTestReport` from the clone root (run by the parent). Selection: the 4 classes of the snapshot package, 58 tests. The test-writer read the counters from the `sourcefile` elements of package `ch/sonensei/cockpit/bookmarks/snapshot` in the XML, and they match the parent's numbers.
- Run start/completion time and exit status: JaCoCo session `dell26-b8f98e09`, start 1792961693766 ms and dump 1792961729607 ms (2026-10-25 about 21:54:53 to 21:55:29 +01:00). The XML was written at 21:55:36. Exit status: pass (per the parent), and the JUnit XML shows 0 failures/errors/skipped.
- Generated report paths and report hashes/run identifier: `server/build/reports/jacoco/test/jacocoTestReport.xml` sha256 e53c199c1182b323d01db5f654f7ded87f56089e24a56b86f6c0f63dce7989d4; `server/build/jacoco/test.exec` (21:55:29); session id `dell26-b8f98e09`.
- Evidence outputs are fresh and inputs stayed unchanged: yes. The session started after the last test edit (21:52:10) and after the last production change (86c1da611, 21:48:01). `test-results` holds exactly the 4 snapshot suites, with 26 edge-case tests, which confirms that the new tests ran. The input hashes match the pre-run snapshot.
- Final run after latest test additions and production fixes: yes.

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A. JaCoCo "Statements" = INSTRUCTION counter.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `server/src/main/kotlin/ch/sonensei/cockpit/bookmarks/snapshot/ReadableExtractor.kt` | 1607/1626 (98.8%) | 124/148 (83.8%) | 20/20 (100%) | 209/209 (100%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/bookmarks/snapshot/BookmarkSnapshotService.kt` | 374/398 (94.0%) | 27/32 (84.4%) | 9/9 (100%) | 63/65 (96.9%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/bookmarks/snapshot/BookmarkSnapshotController.kt` | 52/52 (100%) | N/A (0 branches) | 4/4 (100%) | 8/8 (100%) |
| Aggregate (summed counters, 3 files; generated files excluded as above; no unmeasured files) | 2033/2076 (97.9%) | 151/180 (83.9%) | 33/33 (100%) | 280/282 (99.3%) |

Earlier runs, for comparison: before CR-1 (first pass), ReadableExtractor had 1499/1518 statements, 104/128 branches and 200/200 lines. After CR-1 without this pass's tests (session `dell26-fc39b858`), ReadableExtractor had 123/148 branches, with line 84 missed. The service and controller numbers have not changed.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `ReadableExtractor.kt:84` (`wrapsMain` true for a never-content role) | CR-1 guard: a role wrapper around the article must not take the content with it | Covered now by `an element with a never-content role around the article is kept, other such roles inside it are dropped` (the line no longer appears as partial) |
| `ReadableExtractor.kt:85,90` (`it.parent() != null` false) | Removing an element that sits inside one already removed | Not material: only a guard against removing a detached node twice. The outcome is the same either way. |
| `ReadableExtractor.kt:99,100,112,116,117,135,159,166,167,175` (pre-existing scoring, sibling, prose and removeEmpty short-circuits) | Null-safety guards and alternate operands of the same boolean expressions | Not material: every line runs and the heuristics are covered at the behavior level (first pass). No tests were added only to raise the percentage. |
| `BookmarkSnapshotService.kt:110-111` | Logging a non-NotFound failure while trashing the previous snapshot (best effort) | Left uncovered: it needs a failing `FilesService` bean in the shared context, and the code only logs after the snapshot is saved. The NotFound path is covered. |
| `BookmarkSnapshotService.kt:86` (checkNotNull null branch) | `TransactionTemplate.execute` returning null | Unreachable: the callback returns the entity or throws. |
| `BookmarkSnapshotService.kt:100` (2 of 8 branches) | Previous id equal to the new id | Unreachable in practice (new UUIDv7 per file). |
| `BookmarkSnapshotService.kt:136` (`ifEmpty { "Page" }`) | Empty title after cleaning | Unreachable: the title is trimmed and never empty. |

## Unable to test

- Orphaned blob content after a rolled-back snapshot transaction: a `files` module concern that the snapshot API cannot observe (unchanged from the first pass).
- Fetch timeout -> 502 TIMEOUT: generic error mapping, covered by the bookmarks fetcher tests outside this task.
- Silent truncation at the 1MB fetch limit (CR-2): recorded as an assumption by the parent and not tested here (outside touches).
- Rendering of the HTML snapshot in the web reader: out of scope (recorded assumption).

## Summary

Tests and task-scoped coverage are sufficient; no production defects found. This pass added 2 tests for real CR-1 gaps: the role `wrapsMain` guard and hidden/nav/footer removal in the retry. The snapshot package passes 58/58 with ktlint clean. Fresh task-scoped coverage on the final code is 97.9% statements, 83.9% branches and 99.3% lines across the 3 task files, and all remaining gaps are justified above.
