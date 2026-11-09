# Test Report

Date: 2026-11-09

Task: spa-stale-index - SPA: deploys leave browsers on a stale index.html (constant 1980 Last-Modified)

Scope: base 28ee9f350a33ab55fabf30c99c20c2ef4ddcecff .. task commit 7e98274247e4ba0982eaa950306ee9a0aacdea6d
(`git diff 28ee9f350..7e9827424`, supplied by the parent worker). Measured at HEAD 2a1edb2ce (the same commit
after rebase: `git diff 7e9827424 HEAD -- server` is empty). Changed production file:
`server/src/main/kotlin/ch/sonensei/cockpit/core/spa/SpaWebConfiguration.kt`. Changed test file (by the
implementer): `server/src/test/kotlin/ch/sonensei/cockpit/core/spa/SpaFallbackTests.kt`. Uncommitted at
measurement: only the new test file added by this report.

Requirements:
- `tasks/spa-stale-index.md` acceptance criteria and the recorded assumption (no-cache everywhere, SHA-256
  content ETag, digests cached per URL for jar entries, files on disk hashed per request)
- `docs/ARCHITECTURE.md` "Coverage", `AGENTS.md`, parent worker instructions

## Test files changed

- `server/src/test/kotlin/ch/sonensei/cockpit/core/spa/ContentEtagsTests.kt` (new)

## Tests added or updated

Count: 8 (all in `ContentEtagsTests`, unit tests without a Spring context)

- `etag is the lowercase hex sha-256 of the content` (known SHA-256 vectors for "abc" and the empty file)
- `etag is stable for the same content and differs between contents` (also across a fresh instance)
- `content larger than the read buffer is hashed completely` (3 x 8 KiB + 17 bytes; last-byte change)
- `file on disk changed in place with same size and timestamp gets a new etag` (1980 timestamp, same length)
- `entry inside a jar gets the content digest` (real temp jar, `UrlResource` with `jar:` URL, `isFile` false)
- `non-file resource is read once and its digest cached per url` (counting in-memory resource; separate URL
  separate entry; a new instance sees new content)
- `file resource is read on every request` (counting `FileSystemResource`: 2 reads for 2 calls)
- `read error propagates and is not cached` (IOException propagates; a later good read is digested)

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: conditional GET of `/index.html`, `/`, client route with old validators returns 200 with current content when changed | `SpaFallbackTests`: `changed index html is sent again despite the validators of the old one`, `root forwards a conditional request to the current index html`; `ContentEtagsTests`: `file on disk changed in place with same size and timestamp gets a new etag`, `entry inside a jar gets the content digest` | `./gradlew ktlintCheck test --tests 'ch.sonensei.cockpit.core.spa.*' jacocoTestReport` | Pass. "Fails with the fix reverted" was not re-run by the test writer (reverting production code is out of its authority); the fixture uses the 1980 timestamp and same-length content, so a date-only validator would answer 304 |
| AC2: unchanged files 304 on matching If-None-Match; hashed bundles cacheable; missing `*.js` stays 404 | `SpaFallbackTests`: `unchanged files revalidate by content etag and send no last-modified`, `missing bundle stays 404 with old validators` (plus existing fallback tests); `ContentEtagsTests`: `etag is stable for the same content and differs between contents`, `non-file resource is read once and its digest cached per url` | same | Pass |
| AC3: KDoc describes actual caching | Not testable by an automated test (documentation); read the KDoc: matches the configured `no-cache` + content ETag + no Last-Modified, and the ContentEtags KDoc matches the cache-per-URL / per-request file behaviour verified by the unit tests | n/a | Not testable |
| AC4: `./gradlew ktlintCheck test --tests 'ch.sonensei.cockpit.core.spa.*'` passes | all spa tests: ContentEtagsTests 8, SpaFallbackResourceResolverTests 4, SpaFallbackTests 19 (31 tests, 0 failures, 0 skipped) | `cd server && ./gradlew ktlintCheck test --tests 'ch.sonensei.cockpit.core.spa.*' jacocoTestReport` | Pass (BUILD SUCCESSFUL in 19s) |

## Focused test execution

Command:

`cd server && ./gradlew ktlintCheck test --tests 'ch.sonensei.cockpit.core.spa.*' jacocoTestReport`

Result:

Pass (31 tests, 0 failures; ktlintCheck incl. test source set passed)

## Full test execution

Command:

`cd server && ./gradlew check`

Result:

Fail, unrelated to this task: 5259 tests, 2 failed, both in `ch.sonensei.cockpit.files.FilesEdgeCaseTests`
(`a file deleted on its own is restored from the Trash, searchable again, with its blob`, `an empty folder can be
purged`): "List should be empty but has 3 elements, first being: TrashEntry(type=TASK ... Already in the Trash)",
i.e. trash entries left by another test class in the shared database. Rerun alone:
`./gradlew test --tests 'ch.sonensei.cockpit.files.FilesEdgeCaseTests'` -> BUILD SUCCESSFUL. Order-dependent
test-isolation flake outside the task scope (no files under `files/` or trash touched by this task). All spa tests
passed in the full run.

## .taskcheck

Result:

Not run (covers web and android too; the parent worker runs it before `done`).

## Bugs found

None.

## Coverage

Status: verified

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured with tooling; by inspection of the JaCoCo line data every added executable line
(SpaWebConfiguration.kt lines 40-41, 90-109) is covered, see below.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: spa-stale-index; base
  28ee9f350a33ab55fabf30c99c20c2ef4ddcecff; task commit 7e98274247e4ba0982eaa950306ee9a0aacdea6d (from the parent);
  measured HEAD 2a1edb2ce (rebased task commit, identical server tree to 7e9827424); scope from
  `git diff --name-only 28ee9f350..7e9827424 -- server/src/main`.
- Source/test snapshot identifier (including uncommitted inputs): sha256 SpaWebConfiguration.kt
  7a2da4421cd4d2746fc73c4e1e59152dea782a1104aa7cd724478205758cceb3; ContentEtagsTests.kt (uncommitted)
  e64978dc466c829cb883719107cf56717183e9b2011923f8e6b1c3a932fa94f1; SpaFallbackTests.kt
  3a4042812325353e2fb94cd2d9072fae08d9145c799cf270a509e9e942bc37e9; SpaFallbackResourceResolverTests.kt
  3c2788d8eede5e5fcf62f823e968d9d81766120d8398e24139f1e0be1e122828.
- Exact included files; renamed/deleted/excluded files and reasons: included
  `server/src/main/kotlin/ch/sonensei/cockpit/core/spa/SpaWebConfiguration.kt`; no renames, deletions or exclusions.
- Provider/version, command, working directory, and test selection: JaCoCo (Gradle `jacoco` plugin, Gradle 9.8.1,
  JDK 21.0.12.1); `./gradlew ktlintCheck test --tests 'ch.sonensei.cockpit.core.spa.*' jacocoTestReport`;
  working directory `server/`; test selection `ch.sonensei.cockpit.core.spa.*` only.
- Run start/completion time and exit status: 2026-11-09 about 10:52-10:53 +0100 (test suites timestamped
  09:53:04Z); exit 0, BUILD SUCCESSFUL in 19s.
- Generated report paths and report hashes/run identifier: `server/build/reports/jacoco/test/jacocoTestReport.xml`,
  sha256 042c29ad66b886721c3adcb7ce79c1ae0a09a91d89c03b287fd83995bf1c955d.
- Evidence outputs are fresh and inputs stayed unchanged: test task re-executed (new test class compiled and run),
  report regenerated in the same invocation; file hashes above taken after the run, no edits afterwards.
- Final run after latest test additions and production fixes: yes (no production fixes were made).

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `server/src/main/kotlin/ch/sonensei/cockpit/core/spa/SpaWebConfiguration.kt` | 208/213 instructions (97.7%) | 12/12 (100%) | 12/12 methods (100%) | 34/34 (100%) |

Aggregate equals the single file above; no files missing or excluded.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `SpaWebConfiguration.kt:97` (5 of 21 instructions missed, line itself covered) | compiler-generated exception path of Kotlin `use { }` (close with a pending throwable) | Justified gap: synthetic bytecode; the read-error behaviour is covered by `read error propagates and is not cached` with a stream that fails on open |

## Unable to test

- "Server test reproduces the bug (fails with the fix reverted)" was not demonstrated by reverting production code,
  since the test writer must not modify production code. The parent may confirm it by temporarily reverting the two
  `setUseLastModified(false)` / `setEtagGenerator` lines locally.
- Serving from the real boot jar end to end (classpath `static/` inside the bootJar) is not run as an HTTP test; the
  jar path is covered at unit level by `entry inside a jar gets the content digest` and the per-URL cache test.

## Summary

Tests added and the task-scoped validation passed; task-scoped coverage is sufficient and no production defects
were found. The full server `check` had 2 unrelated, order-dependent failures in `FilesEdgeCaseTests` that pass
when run alone.
