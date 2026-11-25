# Code Review

Date: 2026-11-25

Task: etag-encoding-suffix - Accept proxy content-coding suffix in If-Match (Caddy gzip breaks saves)

Scope: task commit 2a688b95936fcc14e69cedf12b5dcec6b996b485 on top of base e4c488dd97eeacfd3dbfc3e34f5aa134b15efd5d (`git diff e4c488dd..2a688b95`)

Requirements:
- tasks/etag-encoding-suffix.md (acceptance criteria 1-5, notes)
- SPEC.md §4.2 (optimistic locking via ETag/If-Match, 409/412 on conflict)
- AGENTS.md project notes (conventions, touches)
- deploy/Caddyfile (`encode zstd gzip`, `reverse_proxy server:8080`)
- Parent instructions: web-test criterion met via the conventions test controller in ApiConventionsWebTests (task `touches` limits test changes to core/web); Caddy verification is done by the parent

Git range:
- Branch: main
- HEAD: 2a688b95936fcc14e69cedf12b5dcec6b996b485
- Base/start: e4c488dd97eeacfd3dbfc3e34f5aa134b15efd5d
- Commits reviewed: 1

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## What was checked

- `ETags.checkIfMatch` / `matches` (server/src/main/kotlin/ch/sonensei/cockpit/core/web/ETags.kt:44-57): a tag matches only
  when it equals `"<version>"` or exactly `"<version>-gzip"`, `"<version>-zstd"`, `"<version>-br"` for the current
  version. The comparison is exact string equality on the whole tag, so:
  - stale versions with a suffix (`"1-gzip"` for version 2) still give 412;
  - prefix tricks (`"12-gzip"` vs version 2, `"2-gzip-gzip"`, `"2"-gzip`, unquoted `2-gzip`) do not match;
  - weak tags (`W/"2-gzip"`) still do not match (strong comparison kept);
  - other codings (`-deflate`) do not match;
  - missing/blank header still gives 428; `*` still matches.
- Security (stale write): the suffix is accepted only for the exact current version, so no stale or foreign version can
  pass the precondition. A client gains nothing it could not already do by sending `"<version>"`; the change does not
  weaken optimistic locking.
- Integration: every server write path that checks `If-Match` goes through `ETags.checkIfMatch` (53 call sites,
  including the optional-If-Match paths in TaskController, ProjectController, TaskToNoteController,
  TaskToEventController); no other code parses `If-Match` for the app's own entities, so the fix covers all clients
  named in the task (drafts, files, journal, notes, tasks, ...). Outgoing `If-Match` to CalDAV/Google/Graph is
  unrelated and untouched. Suffix names match Caddy's `-<encoder name>` scheme for the configured `zstd gzip`
  encoders, plus `-br`.
- Tests: ETagsTests adds the match cases for all three suffixes, a tag list with a suffixed tag, and the must-fail cases
  (stale, longer version, weak, unknown suffix, double suffix, suffix outside quotes, unquoted, list of only stale
  suffixed tags). ApiConventionsWebTests adds PATCH with `"1-gzip"` (200, new ETag `"2"`), PATCH with stale `"0-zstd"`
  (412 Problem Details) and DELETE with `"1-zstd"` (204), through the real MVC stack and the test controller in
  ConventionsTestApi.kt, which calls `ETags.checkIfMatch`. Existing tests were not weakened. Tests were not run by this
  reviewer (parent asked not to run Gradle).
- Documentation: SPEC.md §4.2 and the `ETags` object KDoc and `checkIfMatch` KDoc explain the accepted suffixes and why
  (proxy rewrites ETag, passes If-Match unchanged), satisfying criterion 5.
- Scope: changes stay within the task's `touches` (ETags.kt, core/web tests, SPEC.md). No client changes, consistent
  with the task notes.
- Criterion 4 (verification against Caddy) is not code-reviewable and is owned by the parent; at review time no
  criterion had recorded evidence yet (`tasks.py verify etag-encoding-suffix` shows all unticked), which is expected
  before the parent records it.

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
