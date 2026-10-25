# Test Report

Date: 2026-10-25

Task: bookmarks-snapshot - Readable snapshots

Scope: task commits a116ea33e37c47a2460a3b6ee97ec95c6e65d7bb..2424773cf7c67a5ff2a72905f4e23c15d74b1064 (one implementation commit; the parent named it 24d9bada1, which differs from HEAD only in `tasks/bookmarks-snapshot.md`, i.e. same code after a rebase). Production paths: `server/src/main/kotlin/ch/sonensei/cockpit/bookmarks/snapshot/` (3 added files), plus generated `api/openapi.yaml` and `web/src/app/api/generated/` (generated, excluded from tests/coverage). Tests added only in `server/src/test/kotlin/ch/sonensei/cockpit/bookmarks/snapshot/` as instructed.

Requirements:
- `tasks/bookmarks-snapshot.md` acceptance criteria 1-2 and its recorded assumptions (POST /api/v1/bookmarks/{id}/snapshot with If-Match, 502 + errorCode on fetch failure / no content, HTML file in Attachments, no images, CSP, previous snapshot to Trash)
- SPEC.md 8.4 (readable copy), 4.2 (Problem Details, ETag/If-Match)
- KDoc contract of `BookmarkSnapshotService` (409 when the URL changed during the fetch, 404 when deleted, concurrent snapshots keep no extra file)
- docs/ARCHITECTURE.md "Coverage", docs/QUALITY.md

## Test files changed

- `server/src/test/kotlin/ch/sonensei/cockpit/bookmarks/snapshot/ReadableExtractorEdgeCaseTests.kt` (new)
- `server/src/test/kotlin/ch/sonensei/cockpit/bookmarks/snapshot/BookmarkSnapshotEdgeCaseWebTests.kt` (new)

The existing `ReadableExtractorTests` (12) and `BookmarkSnapshotWebTests` (8) were not modified.

## Tests added or updated

Count: 26 (14 extractor unit tests, 12 web/Postgres tests)

## Requirement coverage

All commands run from the clone root. "Task selection" = `server/gradlew -p server test --rerun --tests 'ch.sonensei.cockpit.bookmarks.snapshot.*' jacocoTestReport ktlintCheck` (46 tests, 0 failures, Postgres via Testcontainers).

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: main content extracted with Readability-style heuristics | existing `keeps the main content and drops navigation, sidebar, comments and footer`, `div with only text counts as a paragraph`, `hidden elements are dropped`; new `elements with navigation or complementary roles are dropped`, `a block made of links loses against prose`, `a prose paragraph next to the best container is kept, an unrelated link list is not`, `paragraphs in lists and headings lose against prose, paragraphs in a quote count`, `an article element is never dropped because of its class`, `class names that look unlikely but also say content are kept` | Task selection | Pass |
| AC1: output is sanitized HTML (XSS / remote loads) | existing `the result is sanitized ...`, `dangerous links and markup in the content are removed`, `markup in the title is escaped`; new `XSS and remote-load vectors in the main content leave only safelisted markup`, `XSS vectors are also sanitized when no paragraph qualifies and the whole body is kept` (allowlist check of every element/attribute in the output: entity/tab/case/space-obfuscated javascript and vbscript and data URLs, style/class/id/data attributes, img/video/audio/picture/source, math+style mXSS, noscript attribute mXSS, meta refresh, link, base, details ontoggle, unsafe cite on blockquote/q, time onclick, table background, ftp links, object/embed/applet, marquee), `a base tag in the page is dropped and links stay http(s)`, `relative, protocol-relative and mailto links become safe absolute links, other schemes lose their href`, `the output is a complete document with doctype, no-referrer policy and no empty leftovers` | Task selection | Pass |
| AC1: charset handling | existing `the charset of the response is used`; new `an unknown or illegal charset name falls back to detection instead of failing`, `without a charset header the meta charset of the page is used` | Task selection | Pass |
| AC1: title selection | existing `og title wins, the fallback title is used without any`; new `titles collapse whitespace, blank ones fall back to the h1, then to the fallback title` | Task selection | Pass |
| AC1: stored as a file (Attachments, text/html, linked via snapshotFileId, previous to Trash) | existing `save snapshot stores the main content ...`, `a new snapshot replaces the previous one, which goes to the Trash`, `other bookmark changes keep the snapshot`; new `a new snapshot still succeeds when the user already deleted the previous one` | Task selection | Pass |
| File name from title (separators, control chars, truncation) | existing `a file name is made from the title without separators`; new `a long title is cut to 100 characters in the file name`, `a long title is not cut in the middle of a surrogate pair, and trailing blanks are trimmed`, `control characters in the title are replaced in the file name`, `a page without any title is named after the bookmark` | Task selection | Pass |
| URL changed during fetch -> 409, no file kept | new `a URL changed while the page was fetched is a 409 and stores no file` (WireMock delays the page 800 ms; the URL is changed in the DB once WireMock has received the request) | Task selection | Pass |
| Deleted during fetch -> 404, no file kept | new `a bookmark deleted while the page was fetched is a 404 and stores no file` | Task selection | Pass |
| Concurrent snapshots keep exactly one active file | new `two overlapping snapshots leave exactly one active snapshot file, the one the bookmark points to` (asserts the invariant for both possible interleavings: both 200 sequentially, or one 409) | Task selection | Pass |
| If-Match / 404 before any fetch | existing `if-match is required and checked`, `an unknown or deleted bookmark is 404`; new `a bookmark in the Trash is 404 and its page is not fetched`, `a stale If-Match is 412 and the page is not fetched` | Task selection | Pass |
| Fetch failures -> 502 Problem Details with errorCode, no URL/page leak | existing `a page that cannot be fetched is a 502 ...`, `a page without readable content is a 502 NO_CONTENT`; new `a failed fetch answers problem details that do not reveal the URL`, `after a redirect, relative links resolve against the final page` | Task selection | Pass |
| AC2: `./gradlew test --tests 'ch.sonensei.cockpit.bookmarks.snapshot.*'` passes (Testcontainers Postgres) | all 46 tests in the package | Task selection (equivalent to `cd server && ./gradlew test --tests ...`) | Pass (46/46) |

## Focused test execution

Command:

`server/gradlew -p server test --rerun --tests 'ch.sonensei.cockpit.bookmarks.snapshot.*' jacocoTestReport ktlintCheck`

Result:

Pass - ReadableExtractorTests 12/12, ReadableExtractorEdgeCaseTests 14/14, BookmarkSnapshotWebTests 8/8, BookmarkSnapshotEdgeCaseWebTests 12/12; ktlint (main + test) clean. `BookmarkSnapshotEdgeCaseWebTests` was also run on its own 3 more times (incl. `--rerun`) without failures.

## Full test execution

Command:

`server/gradlew -p server check`

Result:

Pass - 333 suites, 4486 tests, 0 failures, 0 errors, 0 skipped, ktlint clean (5 min 58 s). This run was made before the last added test (`two overlapping snapshots ...`); that test only adds a test in the snapshot package and passed in the final task-selection run above. web/ and android/ were not changed by this task's tests.

## .taskcheck

Result:

Not run by the test-writer: `.taskcheck` (`sh scripts/check.sh`) also runs web and android checks; the server part was covered by the full `server/gradlew -p server check` above. The parent runs `.taskcheck` before `done`.

## Bugs found

None. No production defect was found. Observations that are not requirement violations (no fix required):

- `ReadableExtractor.mainContent` adds `initialScore` of a container once per scored paragraph rather than once per container (Mozilla Readability adds it once). This only changes the weighting of the heuristic; no acceptance criterion is violated.
- A layout table whose cells are both long enough is collected as a whole (the row wins over the prose cell), because `td` is itself a scored "paragraph". Same as Readability's scoring of `td`; not a requirement.

## Coverage

Status: verified (task test selection, fresh run on the final test snapshot)

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: equal to the whole-file numbers - all three in-scope files were added by this task (`git diff --name-status` shows `A`), so every executable line is a changed line. No separate tool was used.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: bookmarks-snapshot; base a116ea33e37c47a2460a3b6ee97ec95c6e65d7bb (from parent); HEAD 2424773cf7c67a5ff2a72905f4e23c15d74b1064 (the task commit; parent-named 24d9bada1 has identical code); scope from `git diff --name-status a116ea33e..HEAD -- server/src/main web/src android/app/src/main api` and the task's `touches`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD 2424773cf plus two untracked test files; sha256 BookmarkSnapshotController.kt c231eb68..., BookmarkSnapshotService.kt e87ed031..., ReadableExtractor.kt ac1f250b..., BookmarkSnapshotEdgeCaseWebTests.kt d1aae5d2df276e225f3f9e54ca71103c97905045da5ef363a6cb11f8418dfaf9, ReadableExtractorEdgeCaseTests.kt 3495502a0bbf8b39b3f9711c95f8ab708efd5757b980c6c844d5b08704382eaa, BookmarkSnapshotWebTests.kt 272d49d3..., ReadableExtractorTests.kt 72c0b17b... (hashes identical before and after the run).
- Exact included files; renamed/deleted/excluded files and reasons: included `server/src/main/kotlin/ch/sonensei/cockpit/bookmarks/snapshot/{BookmarkSnapshotController,BookmarkSnapshotService,ReadableExtractor}.kt`. Excluded: `api/openapi.yaml` (generated spec, no executable code), `web/src/app/api/generated/**` (generated client, not instrumented by the server run; no web tests in scope). No renames or deletions.
- Provider/version, command, working directory, and test selection: JaCoCo via the Gradle `jacoco` plugin (default tool version of Gradle 9.8.1; no override in `server/build.gradle.kts`); `server/gradlew -p server test --rerun --tests 'ch.sonensei.cockpit.bookmarks.snapshot.*' jacocoTestReport ktlintCheck`; working directory = clone root; selection = the 4 classes of the snapshot package (46 tests). Counters read from `package name="ch/sonensei/cockpit/bookmarks/snapshot"` / `sourcefile` elements of the XML.
- Run start/completion time and exit status: started 2026-10-25T21:40:22+01:00, completed 21:40:53+01:00 (report written), exit 0 (BUILD SUCCESSFUL).
- Generated report paths and report hashes/run identifier: `server/build/reports/jacoco/test/jacocoTestReport.xml` sha256 2e90e0e06a56840f57b6bbc15c4aba210b0f0e44093690f07ce8260b569e2878, JaCoCo sessioninfo id `dell26-e85ea1bf` (single session); `server/build/jacoco/test.exec` written 21:40:50.
- Evidence outputs are fresh and inputs stayed unchanged: yes - `--rerun` forced execution, the report and exec timestamps fall inside the run, `server/build/test-results/test/` held only the 4 snapshot suites, and the input hashes matched before and after.
- Final run after latest test additions and production fixes: yes (no production changes were made; the run includes all added tests).

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A. JaCoCo "Statements" = INSTRUCTION counter.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `server/src/main/kotlin/ch/sonensei/cockpit/bookmarks/snapshot/ReadableExtractor.kt` | 1499/1518 (98.7%) | 104/128 (81.2%) | 17/17 (100%) | 200/200 (100%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/bookmarks/snapshot/BookmarkSnapshotService.kt` | 374/398 (94.0%) | 27/32 (84.4%) | 9/9 (100%) | 63/65 (96.9%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/bookmarks/snapshot/BookmarkSnapshotController.kt` | 52/52 (100%) | N/A (0 branches) | 4/4 (100%) | 8/8 (100%) |
| Aggregate (summed counters, 3 files; generated files excluded as above) | 1925/1968 (97.8%) | 131/160 (81.9%) | 30/30 (100%) | 271/273 (99.3%) |

Before the added tests (same command, first run 21:30): ReadableExtractor lines 197/200, branches 101/128; service unchanged.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `BookmarkSnapshotService.kt:110-111` | Logging a non-NotFound failure while trashing the previous snapshot (best effort) | Left uncovered: needs a failing `FilesService` (mocking a Spring bean in the shared context); only logs, the snapshot is already saved. The NotFound path is covered by `a new snapshot still succeeds when the user already deleted the previous one`. |
| `BookmarkSnapshotService.kt:86` (checkNotNull null branch) | `TransactionTemplate.execute` returning null | Unreachable: the callback always returns the saved entity or throws. |
| `BookmarkSnapshotService.kt:100` (2 of 8 branches) | previous id equal to the new id | Unreachable in practice (a new file always has a new UUIDv7). |
| `BookmarkSnapshotService.kt:136` (`ifEmpty { "Page" }`) | Empty title after cleaning | Unreachable: the title is trimmed and non-empty (fallback `displayTitle` is never empty) and replacements never produce blanks. |
| `ReadableExtractor.kt:64,72,73,85,89,90,100,106,130,137,138,146` (24 of 128 branches) | Short-circuit combinations inside the scoring heuristics (null parents, zero-length link density, sibling/prose combinations, role-without-class) | Not material: every line runs; the remaining branch combinations are null-safety guards or alternate operands of the same boolean expressions. Behavior-level heuristics are covered by the tests listed above; no tests added only to raise the percentage. |

## Unable to test

- Orphaned blob content when the snapshot transaction rolls back after `files.storeFile` (e.g. the losing side of two concurrent snapshots): the test checks that no extra active file row remains, but whether the blob in storage is removed is a `files` module concern and is not observable through the snapshot API. Follow-up only if the files module does not already clean up rolled-back blobs.
- Fetch timeout -> 502 TIMEOUT was not added (the error mapping is generic and already covered by HTTP_ERROR/NOT_HTML; PageFetcher timeouts are covered by the bookmarks fetcher tests outside this task).
- Rendering of the HTML snapshot in the web reader is out of this task's scope (recorded assumption).

## Summary

Tests and task-scoped coverage are sufficient; no production defects found. 26 tests added (XSS allowlist corpus, charset/title edge cases, heuristics, URL changed during fetch -> 409, deleted during fetch -> 404, concurrent snapshots, file-name truncation incl. surrogate pairs and control characters, redirects, no URL leak in problem details); the snapshot package (46 tests) and the full server `check` (4486 tests) pass.
