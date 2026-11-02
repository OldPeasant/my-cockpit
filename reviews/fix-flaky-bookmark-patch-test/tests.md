# Test Report

Date: 2026-11-02

Task: fix-flaky-bookmark-patch-test - Flaky test: BookmarkWebTests PATCH races the background metadata fetch (409)

Scope: base 19dd927034426e16a7a6d4c5fa0d13c18e16ab4a .. HEAD e4bc1df5dbb87cca26f788380465985f75303705 (branch main).
Task code commits: 94eff1191, 9d2c5f514 (the parent named them; `git log 19dd92703..HEAD -- server` confirms these are the only server commits).
Changed file: `server/src/test/kotlin/ch/sonensei/cockpit/bookmarks/BookmarkWebTests.kt` (test only, no production files).
No uncommitted changes under `server/`. Audited snapshot: sha256 of BookmarkWebTests.kt =
`dae77d0e9057f1ea52f11dc9383d92fedc6bf0c1ea3d670943fcd8265198e8bf`.

Requirements:
- `tasks/fix-flaky-bookmark-patch-test.md` acceptance criterion 2 (the parent asked for this scope)
- Parent instructions
- Production code read only to find out which requests start a background metadata fetch:
  `bookmarks/domain/BookmarkService.kt` (`create`, `update` lines 122-141, `refreshMetadata`),
  `bookmarks/infra/BookmarkTrashContributor.kt` (`restore` fetches again only when the status is `PENDING`),
  `bookmarks/api/BookmarkController.kt` (`If-Match` is checked through `ETags.checkIfMatch` inside the service call)

## Test files changed

- None by the test-writer. The task's own change to `BookmarkWebTests.kt` was audited.

## Tests added or updated

Count: 0 (the audit found no gaps).

## Audit: changes made with an ETag to bookmarks that might still be fetching (criterion 2)

A background fetch starts on `create`, on `update` when the URL changes, on `refresh-metadata`, and on a Trash
restore of a bookmark that is still `PENDING`. Every request in the file that sends `If-Match`:

| Test | Change made with an ETag | Protected? |
|---|---|---|
| PATCH changes only the sent fields... | PATCH 428/412/400 cases, then `change(fetch(...))` | Yes. `settled(newBookmark(...).id())` (line 304). The final change does not touch the URL, so it starts no fetch. |
| a new URL clears the favicon... | `change(first, url)` | Yes. `first` is settled (line 224). The new fetch is started by this change, and the test then waits with `settled`. |
| refresh-metadata fetches again... | refresh with `first.etag()` | Yes. Settled first (line 239). |
| a fetch that finishes after the URL changed again is ignored | `change(created, url)` before the fetch finishes | Deliberately not settled: the test needs the PATCH to win the race against a slow fetch. The fetch is held back by WireMock for `SLOW_FETCH_MS` = 2000 ms (fetch timeout 5s), which leaves a wide margin. The remaining risk is that POST to PATCH takes more than 2 s on a heavily loaded machine. The parent's 20x stress run under load is the evidence for this. |
| read-later queue, read state and filters | `change(doneNow)`, `change(read)`, `change(fetch(done))` | Yes. `doneNow` is settled (line 338). `read` is the response of a change that did not touch the URL (no fetch). `fetch(done)` comes after the settle. |
| a deleted bookmark goes to the Trash... | DELETE with `bookmark.etag()`, DELETE with `restored.etag()` | Yes. Settled first (line 417). The restore starts no fetch because the status is FAILED, not PENDING. |
| every change publishes a change event | `change(settled(...))`, DELETE with `changed.etag()` | Yes. Settled first. The change does not touch the URL. |
| unknown bookmarks give 404 | PATCH/refresh on a random id | Not applicable (there is no bookmark). |

The other tests (validation, URL forms, tags, list paging, resolver, search) create bookmarks but never change them
with an ETag, so the race cannot occur there.

The PATCH test still checks the same behavior:
- 428 without `If-Match` (line 306).
- 412 with `"999"` (line 307).
- 400 for an unknown field, a `url` null and a wrong type (lines 308-310). These now use a current ETag, so a 400 cannot be hidden by a 412.
- The partial update (lines 311-317): the title is kept, the description is cleared, the tags are deduplicated case-insensitively and the URL is unchanged.

The PATCH test asserts the same things as at the base commit. Only the creation line changed: the bookmark is now settled before any PATCH.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| Criterion 2: every test that changes a freshly created bookmark with its ETag waits with `settled(id)` (or avoids the fetch); the PATCH test keeps the 428/412/400/partial-update checks | Static audit of all `BookmarkWebTests` tests (table above) | Code reading at HEAD e4bc1df5d | Pass (static audit only) |
| Criterion 2, run: `cd server && ./gradlew test --tests '*BookmarkWebTests*'` | BookmarkWebTests (whole class) | `cd server && ./gradlew test --tests '*BookmarkWebTests*'` | Not run: the permission system denied running Gradle in this helper session |
| Server lint | n/a | `cd server && ./gradlew ktlintCheck` | Not run: the permission system denied running Gradle in this helper session |
| Criterion 3 (20x under load) | n/a | n/a | Not run: the parent does this run (instruction) |

## Focused test execution

Command:

`cd server && ./gradlew test --tests '*BookmarkWebTests*'`

Result:

Not run. The permission system denied both `cd server && ./gradlew test ...` and `server/gradlew -p server test ...`.
The parent must run this command itself.

## Full test execution

Command:

`cd server && ./gradlew ktlintCheck` (also requested), `sh scripts/check.sh`

Result:

Not run (Gradle execution was denied in this session, see above).

## .taskcheck

Result:

Not run (the same denial; the parent runs it before `done`).

## Bugs found

None. No production defect was found. The audit found no unprotected changes made with an ETag besides the
intentional race in the slow-ordering test, which has its documented 2000 ms margin.

Optional hardening (not required, not a defect): after the rejected PATCHes, the PATCH test could assert
`current.get("version") shouldBe bookmark.get("version")`. This would show that the rejected requests changed
nothing and that no background fetch moved the version after `settled`. It was left out because the parent asked
for changes only where they are needed.

## Coverage

Status: not applicable. This is a test-only change: the task changes no production file, so the task coverage
scope is empty. No whole-file or changed-line coverage figures apply, and none are reported (not 100%).

Metric: whole-file coverage of task-changed production files: N/A (no production files in scope).
Changed-line coverage: not measured (no production lines changed).

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: fix-flaky-bookmark-patch-test; base 19dd927034426e16a7a6d4c5fa0d13c18e16ab4a; HEAD e4bc1df5dbb87cca26f788380465985f75303705; commits 94eff1191, 9d2c5f514 (named by the parent, confirmed with `git log 19dd92703..HEAD -- server`).
- Source/test snapshot identifier (including uncommitted inputs): HEAD e4bc1df5d with no uncommitted changes under `server/`; BookmarkWebTests.kt sha256 dae77d0e9057f1ea52f11dc9383d92fedc6bf0c1ea3d670943fcd8265198e8bf.
- Exact included files; renamed/deleted/excluded files and reasons: included production files: none. Excluded: `server/src/test/kotlin/ch/sonensei/cockpit/bookmarks/BookmarkWebTests.kt` (a test file); `reviews/fix-flaky-bookmark-patch-test/*` (review documents).
- Provider/version, command, working directory, and test selection: no coverage run (empty scope; Gradle execution was also denied in this session).
- Run start/completion time and exit status: N/A.
- Generated report paths and report hashes/run identifier: N/A.
- Evidence outputs are fresh and inputs stayed unchanged: N/A (no outputs). The file hash above identifies the audited state.
- Final run after latest test additions and production fixes: no. No tests were added and there are no production changes. Test execution is pending with the parent.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| (no production files in scope) | N/A | N/A | N/A | N/A |

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| N/A | N/A | Test-only task: no production code to cover |

## Unable to test

- I could not run `./gradlew test --tests '*BookmarkWebTests*'` or `./gradlew ktlintCheck`: the permission system denied Gradle execution in this helper session. Follow-up: the parent must run both and record the results. They are not verified by this report.

## Summary

No additional tests were necessary for the reviewed scope. The static audit confirms criterion 2: every test that
changes a freshly created bookmark with its ETag is protected, except the slow-ordering test, which is deliberately
unprotected and has a 2000 ms margin. However, I could not execute the tests or run ktlint in this session
(permission denied), so the parent must still run that validation.
