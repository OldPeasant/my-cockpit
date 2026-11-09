# Test Report

Date: 2026-11-09

Task: spa-stale-index - SPA: deploys leave browsers on a stale index.html (constant 1980 Last-Modified)

Scope: base 28ee9f350a33ab55fabf30c99c20c2ef4ddcecff .. final code revision
fd99730285abab394a69eb6fc7a24be2cef6d16e (supplied by the parent worker). Measured at HEAD
01a4475137d57d38cbbf191664d59589a9cc795e; `git diff fd9973028 HEAD` differs only in `tasks/spa-stale-index.md`,
so the code is identical. Task-owned code commits: e6677bee3 (implementation), 8db596923 (tests and first
reports), 01a447513 (fixer: `ContentEtags(cacheDigests)`, CR-1/CR-2). Changed production file:
`server/src/main/kotlin/ch/sonensei/cockpit/core/spa/SpaWebConfiguration.kt`. Uncommitted at measurement: the
new test file `SpaWebConfigurationTests.kt` added by this report (and `reviews/spa-stale-index/review.md`, being
written by the code-reviewer in parallel; not a test input).

Requirements:
- `tasks/spa-stale-index.md` acceptance criteria and the recorded assumptions (no-cache everywhere, SHA-256
  content ETag, no Last-Modified; digests cached per URL for a `classpath:` location, hashed per request for a
  `file:` location)
- `reviews/spa-stale-index/fixes.md` (CR-1, CR-2), `docs/ARCHITECTURE.md` "Coverage", `AGENTS.md`, parent
  worker instructions

## Test files changed

- `server/src/test/kotlin/ch/sonensei/cockpit/core/spa/SpaWebConfigurationTests.kt` (new, this run)
- Reviewed unchanged in this run: `ContentEtagsTests.kt` (updated by the fixer), `SpaFallbackTests.kt`,
  `SpaFallbackResourceResolverTests.kt`

## Tests added or updated

Count: 3 (new class `SpaWebConfigurationTests`, no Spring Boot context: a real `ResourceHandlerRegistry` is
filled by `SpaWebConfiguration.addResourceHandlers` and the registered `ResourceHttpRequestHandler` is inspected)

Gap closed: after the fix, `ContentEtagsTests` cover both values of `cacheDigests`, and `SpaFallbackTests`
exercise the `file:` wiring end to end, but nothing checked that `SpaWebConfiguration` passes
`cacheDigests = true` for a `classpath:` location (the production path of CR-1). A wiring that never cached
would have passed every existing test.

- `classpath location caches the content digest per url`: the ETag generator of the handler built for
  `classpath:/static/` reads a resource once for two calls and returns its SHA-256.
- `file location hashes the content on every request`: for a `file:` location the generator reads on every
  call and picks up changed content.
- `handler sends no last-modified and no-cache for every location`: for both kinds of location
  `isUseLastModified` is false and `Cache-Control` is `no-cache`.

Re-check of the fixer's `ContentEtagsTests` (10 tests): both branches of `cacheDigests` are covered (cached jar
entry and cached real file read once; uncached file and uncached non-file resource read every time), plus test
vectors, large content, in-place change with same size and 1980 timestamp, jar entry digest, and read error not
cached. No assertion was weakened compared to the previous revision.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: conditional GET of `/index.html`, `/` and a client route with the validators of an earlier `index.html` returns 200 with the current content when it changed | `SpaFallbackTests`: `changed index html is sent again despite the validators of the old one`, `root forwards a conditional request to the current index html`; `ContentEtagsTests`: `file on disk changed in place with same size and timestamp gets a new etag`, `entry inside a jar gets the content digest`; `SpaWebConfigurationTests`: `file location hashes the content on every request`, `handler sends no last-modified and no-cache for every location` | `cd server && ./gradlew ktlintCheck test --tests 'ch.sonensei.cockpit.core.spa.*' jacocoTestReport` | Pass. "Fails with the fix reverted" not demonstrated by the test writer (reverting production code is outside its authority); the fixture uses the 1980 timestamp and same-length content, so a date-only validator would answer 304 |
| AC2: unchanged files 304 for a matching `If-None-Match`; hashed bundles cacheable; missing `*.js` stays 404 | `SpaFallbackTests`: `unchanged files revalidate by content etag and send no last-modified`, `missing bundle stays 404 with old validators`, `missing file with extension is 404, not index html`; `ContentEtagsTests`: `etag is stable for the same content and differs between contents`, `with caching a jar entry is read once and its digest cached per url`, `with caching a real file (the unpacked jar of the image) is read once and stays content based`; `SpaWebConfigurationTests`: `classpath location caches the content digest per url` | same | Pass |
| AC3: KDoc describes the actual caching | Not testable by an automated test (documentation). Read the KDoc of `SpaWebConfiguration` and `ContentEtags`: no-cache for all files, content SHA-256 ETag, no Last-Modified, cached for `classpath:`, per request otherwise; matches the code and the behaviour pinned by `SpaWebConfigurationTests` | n/a | Not testable |
| AC4: `./gradlew ktlintCheck test --tests 'ch.sonensei.cockpit.core.spa.*'` passes | all spa tests: ContentEtagsTests 10, SpaFallbackResourceResolverTests 4, SpaFallbackTests 19, SpaWebConfigurationTests 3 (36 tests, 0 failures, 0 errors, 0 skipped) | `cd server && ./gradlew ktlintCheck test --tests 'ch.sonensei.cockpit.core.spa.*' jacocoTestReport` | Pass (BUILD SUCCESSFUL in 18s) |
| CR-1 fix: digests cached for a `classpath:` location, including the unpacked jar (real files) | `SpaWebConfigurationTests`: `classpath location caches the content digest per url`; `ContentEtagsTests`: `with caching a real file (the unpacked jar of the image) is read once and stays content based` | same | Pass |

## Focused test execution

Command:

`cd server && ./gradlew ktlintCheck test --tests 'ch.sonensei.cockpit.core.spa.*' jacocoTestReport`

Result:

Pass (36 tests, 0 failures; ktlintCheck including the test source set passed; compileTestKotlin re-ran with the
new file)

## Full test execution

Command:

`cd server && ./gradlew check`

Result:

Not run in this pass. The parent worker specified the focused command; the only change since the previous full
run (recorded in the earlier report: all spa tests passed, 2 unrelated order-dependent `FilesEdgeCaseTests`
failures that pass alone) is confined to `core/spa` production code and spa test classes. The parent runs
`.taskcheck` (which includes `./gradlew check`) before `done`.

## .taskcheck

Result:

Not run (covers web and android too; the parent worker runs it before `done`).

## Bugs found

None.

## Coverage

Status: verified

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: measured by intersecting JaCoCo line data with the added lines of
`git diff -U0 28ee9f350..HEAD -- SpaWebConfiguration.kt` (executable added lines 45, 46, 93, 94, 96, 100, 101,
105-111, 113, 114): 16/16 lines covered (100%), branches on those lines 4/4 (100%); line 106 has 5 of 21
instructions missed (see below).

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: spa-stale-index; base
  28ee9f350a33ab55fabf30c99c20c2ef4ddcecff; final code revision fd99730285abab394a69eb6fc7a24be2cef6d16e (parent);
  measured HEAD 01a4475137d57d38cbbf191664d59589a9cc795e (same code, differs only in `tasks/`); scope from
  `git diff 28ee9f350..HEAD -- server/src/main`.
- Source/test snapshot identifier (including uncommitted inputs), sha256 taken after the run:
  SpaWebConfiguration.kt e8cf107a11a00f0f05b1d90e06a1366fc8bd66abea9a864229a69253bc1df1ee;
  SpaWebConfigurationTests.kt (uncommitted) 786fbb564a3922b63fd091bf3f75bb84a2e3a6c927017e75ec803aca508790b2;
  ContentEtagsTests.kt 6775f1bb954a2539fe7761739c0a8202da6e801b798aa5f88379de6b86ceb3a4;
  SpaFallbackTests.kt 3a4042812325353e2fb94cd2d9072fae08d9145c799cf270a509e9e942bc37e9;
  SpaFallbackResourceResolverTests.kt 3c2788d8eede5e5fcf62f823e968d9d81766120d8398e24139f1e0be1e122828.
- Exact included files; renamed/deleted/excluded files and reasons: included
  `server/src/main/kotlin/ch/sonensei/cockpit/core/spa/SpaWebConfiguration.kt`; no renames, deletions or
  exclusions.
- Provider/version, command, working directory, and test selection: JaCoCo (Gradle `jacoco` plugin, Gradle
  9.8.1); `./gradlew ktlintCheck test --tests 'ch.sonensei.cockpit.core.spa.*' jacocoTestReport`; working
  directory `server/`; test selection `ch.sonensei.cockpit.core.spa.*` only.
- Run start/completion time and exit status: 2026-11-09 about 11:01 +0100 (test suites timestamped
  2026-11-09T10:01:14Z); exit 0, BUILD SUCCESSFUL in 18s.
- Generated report paths and report hashes/run identifier: `server/build/reports/jacoco/test/jacocoTestReport.xml`,
  sha256 f66ddb3d3448c2b5820d425d1dd6789a3fefac71b326211b3fd4ade8952da6ac.
- Evidence outputs are fresh and inputs stayed unchanged: test task re-executed (new class compiled and run, its
  result XML present), report regenerated in the same invocation; counters differ from the previous report
  (223 instructions vs 213 before the fix); no edits after the run.
- Final run after latest test additions and production fixes: yes (after the fixer's commit 01a447513 and this
  run's test addition).

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `server/src/main/kotlin/ch/sonensei/cockpit/core/spa/SpaWebConfiguration.kt` | 218/223 instructions (97.8%) | 12/12 (100%) | 12/12 methods (100%) | 35/35 (100%) |

Aggregate equals the single file above; no files missing or excluded.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `SpaWebConfiguration.kt:106` (5 of 21 instructions missed, line covered) | compiler-generated exception path of Kotlin `use { }` (close with a pending throwable) | Justified gap: synthetic bytecode; read errors are covered by `read error propagates and is not cached` (stream fails on open) |
| `SpaWebConfiguration.kt:46` (`location.startsWith(...)` wiring, no JaCoCo branch) | CR-1: `classpath:` must cache, `file:` must hash per request | Covered by behaviour: `SpaWebConfigurationTests` checks both outcomes |

## Unable to test

- "Server test reproduces the bug (fails with the fix reverted)" was not demonstrated by reverting production code
  (outside the test writer's authority). The parent may confirm it by temporarily reverting the
  `setUseLastModified(false)` / `setEtagGenerator` lines locally.
- Serving from the real boot jar (packed or unpacked) over HTTP is not run; the `classpath:` handler wiring is
  covered without a server by `SpaWebConfigurationTests`, the jar entry digest by `ContentEtagsTests`.

## Summary

Tests added and all validation passed: one gap left by the fix (the `classpath:` caching wiring) is now covered;
task-scoped coverage is sufficient and no production defects were found.
