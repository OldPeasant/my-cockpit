# Code Review

Date: 2026-11-25

Task: etag-encoding-suffix - Accept proxy content-coding suffix in If-Match (Caddy gzip breaks saves)

Scope: whole task diff at the final revision, `git diff e4c488dd97eeacfd3dbfc3e34f5aa134b15efd5d..a8c3d44f53bb8ae004c72ab2d0b4d7d436db150f`, with emphasis on the tests the test-writer added after the first review (`git diff 2a688b959..a8c3d44f5 -- server`)

Requirements:
- tasks/etag-encoding-suffix.md (acceptance criteria 1-5, notes)
- SPEC.md §4.2 (optimistic locking via ETag/If-Match, 409/412 on conflict)
- AGENTS.md project notes (conventions, touches)
- deploy/Caddyfile (`encode zstd gzip`, `reverse_proxy server:8080`)
- Parent instructions: re-review at a8c3d44f5 with focus on the new tests; do not run Gradle (the test-writer reports 59 core.web tests passing and a full check with 5296 passing)

Git range:
- Branch: main
- HEAD: a8c3d44f53bb8ae004c72ab2d0b4d7d436db150f
- Base/start: e4c488dd97eeacfd3dbfc3e34f5aa134b15efd5d
- Commits reviewed: 5 (175d09270 production change, rebased from 2a688b959; dd4346f62 first review report; a8c3d44f5 edge-case tests and test report; b43efcfe7 and 338cabeb4 task-file verify records only)

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## What was checked

- Production code unchanged since the first review: `git diff 2a688b959 175d09270 -- server SPEC.md` is empty; the
  rebase changed only the task file. The conclusions from the first review still apply:
  - `ETags.checkIfMatch` / `matches` (server/src/main/kotlin/ch/sonensei/cockpit/core/web/ETags.kt:44-57) accept a tag
    only when it equals `"<version>"` or exactly `"<version>-gzip"`, `"<version>-zstd"`, `"<version>-br"` for the
    current version (whole-tag string equality). Stale versions, longer versions (`"12-gzip"`), weak tags, other
    codings, double suffixes, suffixes outside the quotes and unquoted tags all still give 412; missing/blank gives
    428; `*` still matches.
  - Security: the suffix is accepted only for the exact current version, so optimistic locking is not weakened.
  - Integration: all app write paths use `ETags.checkIfMatch`, so every client named in the task is covered; no client
    change needed (task notes). Outgoing If-Match to CalDAV/Google/Graph is unrelated and untouched.
  - Documentation: SPEC.md §4.2 and the `ETags` KDoc explain the accepted suffixes and why (criterion 5).
- New unit tests (server/src/test/kotlin/ch/sonensei/cockpit/core/web/ETagsTests.kt:67-92): each assertion was traced
  against `matches`:
  - version 0 (`"0-gzip"`), `Long.MAX_VALUE` with `-br`, a list mixing a weak exact tag, a stale suffixed tag and the
    current `-zstd` tag, and a list without spaces (`"2-br","3"`) all match, as the code does;
  - bare suffix (`"-gzip"`), suffix without dash (`"2gzip"`), empty suffix (`"2-"`), reversed (`"gzip-2"`), trailing
    garbage after the closing quote, and a different version ending in the same digit (`"22-zstd"`) all give 412;
  - empty string gives 428 and `*` alongside a stale suffixed tag matches.
  These are behavior assertions on the public function, not on internals.
- New web tests (server/src/test/kotlin/ch/sonensei/cockpit/core/web/ApiConventionsWebTests.kt:337-379), through the
  real MVC stack and the conventions test controller (ConventionsTestApi.kt, which calls `ETags.checkIfMatch`; state
  reset per test in `@BeforeEach`, item `a` starts at version 1):
  - the stale `-zstd` PATCH test now also asserts the item is unchanged afterwards;
  - weak `W/"1-gzip"` and unknown `"1-deflate"` for the current version give 412 Problem Details and leave name and
    ETag `"1"` unchanged;
  - a list `"0-gzip", "1-br"` updates (200, new ETag `"2"`).
  Together with the earlier PATCH `"1-gzip"` (200) and DELETE `"1-zstd"` (204) tests, criterion 3 is covered.
- Criterion 2 coverage: suffixed match and stale version, a tag list with a suffixed tag, and the must-fail cases are
  all in ETagsTests. Existing tests were not weakened or removed.
- Criterion 4 (Caddy) is not code-reviewable; the task file records concrete evidence (bootJar behind caddy:2 v2.11.7
  with deploy/Caddyfile, PATCH with `"0-gzip"` 200 instead of 412, stale 412, DELETE 204), consistent with the code.
- Scope: changes stay within the task's `touches` (ETags.kt, core/web tests, SPEC.md) plus task/review files.
- Tests were not run by this reviewer (parent asked not to run Gradle); results above are the test-writer's.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Review conclusion

No material issues found.
