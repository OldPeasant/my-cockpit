# Test Report

Date: 2026-11-22

Task: ci-robolectric-cache - CI: cache Robolectric runtime jars (Maven Central 429 fails the Android check)

Scope (round 2, final revision): base 6ca342c2ec93616b598957a39b700fa52a467071; task commits
8c45a0e7ef06ef64a89f05c559551d213f321655 (implementation, rebased from 4a15387d3 with identical content) and
1dfe46e78ccc5c0f15d3ab56308fd8526900ebf6 (fix: drop the cross-version `robolectric-` restore key, see
`reviews/ci-robolectric-cache/fixes.md`). Measured at HEAD e61a7869158b9fbe2ac3e902b9b579383d1c63a7, which only adds
`reviews/` files on top of 1dfe46e78 (`git diff 1dfe46e78 HEAD -- .github docs` is empty). Changed files of the task:
`.github/workflows/ci.yml`, `docs/ARCHITECTURE.md`. No production or test code changed. Working tree clean.

Round-2 delta: 1dfe46e78 changes two lines of `.github/workflows/ci.yml`: the comment above the Robolectric steps now
says the cache has no cross-version fallback, and the `restore-keys` list drops the bare `robolectric-` entry, leaving
only `robolectric-VERSION-`. The version, key and save steps are unchanged.

Requirements:
- `tasks/ci-robolectric-cache.md` acceptance criteria
- `AGENTS.md` (Evidence rules), `docs/ARCHITECTURE.md` (CI section, actionlint command)
- Parent instructions for round 2: re-run actionlint and the scratch scripts if still relevant; no repository tests,
  no Gradle, no check.sh

## Test files changed

- None in the repository. Scratch scripts `.tmp/keytest.sh` and `.tmp/tw/gha-step.sh` (Git-ignored, not part of the change).

## Tests added or updated

Count: 0

The change is a GitHub Actions workflow only. The project has no test harness for workflows (actionlint is the
documented check), so there is no sensible place for a repository test.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: Android job restores and saves the Robolectric jars, key from the version in libs.versions.toml plus a restore key | Workflow lint on the final revision; key-step simulation; inspection of the restore keys after the fix | `podman run --rm -v ./:/repo:Z -w /repo docker.io/rhysd/actionlint:latest` (actionlint 1.7.12); `sh .tmp/keytest.sh`; `grep -n "robolectric-" .github/workflows/ci.yml` | Pass (round 2: lint exit 0, no findings; keytest gives no key for missing/empty dir, a stable key for an unchanged jar set, a new key for a new jar; the only restore key is `robolectric-${{ steps.robolectric.outputs.version }}-`). Warm-cache behavior on GitHub: Not run, needs a real CI run |
| AC2: saved also when tests fail; cold cache works as before | Inspection of `if: always()` on key and save steps (unchanged by the fix); simulation of the key step on a missing directory | `sh .tmp/keytest.sh` (round 2); `bash .tmp/tw/gha-step.sh` (round 1 only, see below) | Pass for the step logic. Actual save after a failing run: Not run, needs GitHub |
| AC3: optional hardening (retries or prefetch) | None | - | Not testable: not implemented in this change (optional criterion) |
| AC4: Node 20 actions bumped to Node 24 versions | Round 1: fetched action metadata of `actions/upload-artifact@v7`, `android-actions/setup-android@v4`, `actions/cache/restore@v5`, `actions/cache/save@v5`, all `runs.using: node24`; the fix does not touch any `uses:` line | `curl -sSL https://raw.githubusercontent.com/actions/cache/v5/restore/action.yml` (and the save, upload-artifact v7 and setup-android v4 metadata), round 1 | Pass (still valid: no action reference changed in 1dfe46e78) |
| AC5: evidence from a GitHub Actions run on main | None | - | Not run: only possible after `done` pushes; `gh` is not permitted for agents here |
| AC6: check.sh passes; ARCHITECTURE.md mentions the cache | Diff inspection of ARCHITECTURE.md (one added sentence in the CI paragraph; unchanged by the fix) | `git diff 6ca342c2e 4a15387d3 -- docs/ARCHITECTURE.md` | Docs part: Pass. `sh scripts/check.sh`: Not run by this helper on parent instruction |

## Focused test execution

Command:

`podman run --rm -v ./:/repo:Z -w /repo docker.io/rhysd/actionlint:latest`

Result:

Pass (round 2, on the final revision: exit 0, no output; actionlint 1.7.12 built with go1.26.1, which includes
shellcheck checks of `run:` scripts)

Command:

`sh .tmp/keytest.sh`

Result:

Pass (round 2). Output: no key for a missing dir, no key for an empty dir, `robolectric-4.16-32b99094795090d5` for one
jar, the same key again for the unchanged set, `robolectric-4.16-3b53a881f5b7d8ac` after a second jar. Identical to
round 1, as expected since the key step is unchanged.

Command:

`bash .tmp/tw/gha-step.sh`

Result:

Not re-run in round 2: the sandbox denied the command in this session. Round-1 result (on 8c45a0e7e) remains valid
because 1dfe46e78 does not change the "Robolectric cache key" step body: under GitHub's default
`bash --noprofile --norc -eo pipefail`, missing and empty dir exit 0 with no key; jars produce a key, `.pom` files do
not change it, a new jar changes it; 3000 jars (early pipe close by `head -n 1`) still exit 0.

Restore-key reasoning for the fix (inspection): with only `robolectric-VERSION-` as restore key, `actions/cache/restore`
prefix matching cannot pick up another version's entry. The trailing `-` also prevents prefix collisions between
versions such as `4.1` and `4.16` (`robolectric-4.1-` is not a prefix of `robolectric-4.16-...`). The primary `key`
ends in `-` and never matches exactly, so the restore always falls through to the newest same-version entry, and the
save step compares the computed key with `cache-matched-key`, so an unchanged jar set is not saved again.

## Full test execution

Command:

`sh scripts/check.sh`

Result:

Not run (parent instruction: the parent runs it; Gradle must not run twice in the same clone).

## .taskcheck

Result:

Not run by this helper, same reason as above.

## Bugs found

None.

The round-1 observation (the cross-version `robolectric-` fallback carried old-version jars into new caches) is
resolved by 1dfe46e78: that restore key no longer exists in the workflow.

Remaining observation (no fix required): two concurrent cold runs saving the same key make `actions/cache/save` log
a warning that the key is already reserved; the job does not fail.

## Coverage

Status: not applicable. The task changes only a GitHub Actions workflow (YAML with an inline shell step) and one
documentation line. The project's coverage tooling (JaCoCo for server/android, the Angular/vitest provider for web,
`docs/ARCHITECTURE.md` "Coverage") instruments Kotlin and TypeScript production code; neither changed file is in its
scope, and no coverage tool exists for workflow files.

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured (no production code in scope).

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: ci-robolectric-cache; base
  6ca342c2ec93616b598957a39b700fa52a467071; task commits 8c45a0e7ef06ef64a89f05c559551d213f321655 and
  1dfe46e78ccc5c0f15d3ab56308fd8526900ebf6 supplied by the parent; HEAD at measurement
  e61a7869158b9fbe2ac3e902b9b579383d1c63a7 (reports only on top of 1dfe46e78).
- Source/test snapshot identifier (including uncommitted inputs): blob of `.github/workflows/ci.yml` at 1dfe46e78 and
  HEAD b511b4e38f6ba1eb2d83c5b4a8cdcf6c4276d356; no uncommitted source changes.
- Exact included files; renamed/deleted/excluded files and reasons: no production files in scope;
  `.github/workflows/ci.yml` (CI configuration) and `docs/ARCHITECTURE.md` (documentation) excluded as non-instrumentable.
- Provider/version, command, working directory, and test selection: none run (not applicable).
- Run start/completion time and exit status: not applicable.
- Generated report paths and report hashes/run identifier: none.
- Evidence outputs are fresh and inputs stayed unchanged: actionlint and keytest ran in round 2 on the blob listed above.
- Final run after latest test additions and production fixes: yes for actionlint and keytest (after fix 1dfe46e78; no
  tests added). `gha-step.sh` not re-run (sandbox denial), its subject is unchanged by the fix.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| (no production files in scope) | N/A | N/A | N/A | N/A |

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `.github/workflows/ci.yml` restore/save steps | Warm cache restores the jars; save after a failing test run | Only verifiable on GitHub Actions (AC5); step logic simulated locally, see above |

## Unable to test

- Real cache restore/save on GitHub Actions and a green Android job with a warm cache (AC1, AC2, AC5): requires a CI
  run after `done` pushes; `gh` is not available to agents. Follow-up: the parent records a skip and assumption, a
  human can confirm from the run log.
- `sh scripts/check.sh` (AC6): deliberately left to the parent.
- Optional hardening (AC3): not implemented, nothing to test.
- `.tmp/tw/gha-step.sh` re-run in round 2: denied by the sandbox; not needed for the fix delta (key step unchanged).

## Summary

No additional tests were necessary for the reviewed scope. On the final revision 1dfe46e78, actionlint and the
key-step simulation pass, the cross-version restore key is gone, and no defects were found. GitHub-side behavior
remains to be confirmed by a real CI run.
