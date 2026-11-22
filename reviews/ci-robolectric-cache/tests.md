# Test Report

Date: 2026-11-22

Task: ci-robolectric-cache - CI: cache Robolectric runtime jars (Maven Central 429 fails the Android check)

Scope: base 6ca342c2ec93616b598957a39b700fa52a467071, task commit 4a15387d3d9fecac829fbaff81491a10412ea53b
(rebased on the board as 8c45a0e7ef06ef64a89f05c559551d213f321655; `git diff 4a15387d3 8c45a0e7e -- .github docs`
is empty, so the content is identical). Changed files: `.github/workflows/ci.yml`, `docs/ARCHITECTURE.md`. No
production or test code changed. Working tree clean apart from the untracked `reviews/ci-robolectric-cache/`.

Requirements:
- `tasks/ci-robolectric-cache.md` acceptance criteria
- `AGENTS.md` (Evidence rules), `docs/ARCHITECTURE.md` (CI section, actionlint command)
- Parent instructions: run actionlint, the key-step simulation, check Node 24 for bumped actions; no Gradle, no check.sh

## Test files changed

- None in the repository. Scratch simulation added at `.tmp/tw/gha-step.sh` (Git-ignored, not part of the change).

## Tests added or updated

Count: 0

The change is a GitHub Actions workflow only. The project has no test harness for workflows (actionlint is the
documented check), so there is no sensible place for a repository test. Validation was done with actionlint and
shell simulations of the new step logic.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: Android job restores and saves the Robolectric jars, key from the version in libs.versions.toml plus a restore key | Workflow lint; version extraction against the real toml; key-step simulation (no dir, empty dir, one jar, same set, new jar) | `podman run --rm -v ./:/repo:Z -w /repo docker.io/rhysd/actionlint:latest` (actionlint 1.7.12); `sed -n 's/^robolectric = "\(.*\)"$/\1/p' android/gradle/libs.versions.toml`; `sh .tmp/keytest.sh`; `sh .tmp/tw/gha-step.sh` | Pass (lint exit 0, no findings; sed prints exactly `4.16`; keys stable for an unchanged jar set, new key for a new jar, no key without jars). Warm-cache behavior on GitHub: Not run, needs a real CI run |
| AC2: saved also when tests fail; cold cache works as before | Inspection of `if: always()` on key and save steps; simulation of the key step on a missing directory | `sh .tmp/tw/gha-step.sh` | Pass for the step logic (missing/empty dir: exit 0, no key, so the save step is skipped and the job is unaffected). Actual save after a failing run: Not run, needs GitHub |
| AC3: optional hardening (retries or prefetch) | None | - | Not testable: not implemented in this change (optional criterion) |
| AC4: Node 20 actions bumped to Node 24 versions | Fetched action metadata: `actions/upload-artifact@v7`, `android-actions/setup-android@v4`, `actions/cache/restore@v5`, `actions/cache/save@v5` all declare `runs.using: node24`; inputs used by the workflow (`name`, `path`, `packages`, `key`, `restore-keys`) exist; output `cache-matched-key` exists. `grep -rnE "upload-artifact@v[0-6]\b\|setup-android@v[0-3]\b" .github/` finds nothing | `curl -sSL https://raw.githubusercontent.com/actions/cache/v5/restore/action.yml` (and `.../cache/v5/save/action.yml`, `.../upload-artifact/v7/action.yml`, `.../android-actions/setup-android/v4/action.yml`) | Pass |
| AC5: evidence from a GitHub Actions run on main | None | - | Not run: only possible after `done` pushes; `gh` is not permitted for agents here |
| AC6: check.sh passes; ARCHITECTURE.md mentions the cache | Diff inspection of ARCHITECTURE.md (one added sentence in the CI paragraph) | `git diff 6ca342c2e 4a15387d3 -- docs/ARCHITECTURE.md` | Docs part: Pass. `sh scripts/check.sh`: Not run by this helper on parent instruction (the parent runs it; Gradle build dir clash) |

## Focused test execution

Command:

`podman run --rm -v ./:/repo:Z -w /repo docker.io/rhysd/actionlint:latest`

Result:

Pass (exit 0, no output; actionlint 1.7.12, which includes shellcheck checks of `run:` scripts)

Command:

`sh .tmp/keytest.sh`

Result:

Pass. Output: no key for a missing dir, no key for an empty dir, `robolectric-4.16-32b99094795090d5` for one jar,
the same key again for the unchanged set, `robolectric-4.16-3b53a881f5b7d8ac` after a second jar.

Command:

`sh .tmp/tw/gha-step.sh` (runs the exact step body under GitHub's default `bash --noprofile --norc -eo pipefail`
with a fake HOME, so the `~` expansion in `dir=~/.m2/...` is exercised too)

Result:

Pass. Missing dir and empty dir: exit 0, no key. One jar: key; jar plus `.pom`: same key (only jars count); second
jar: new key; 3000 jars (where `head -n 1` closes the pipe early): exit 0 and a key, so SIGPIPE under pipefail does
not fail the step. One artificial case (an unreadable subdirectory, `chmod 000`) makes `find` fail and the step exit
1; this cannot happen on a GitHub runner where the runner user owns `~/.m2`, and the save step would then be skipped
because the key output is empty. Not treated as a defect.

## Full test execution

Command:

`sh scripts/check.sh`

Result:

Not run (parent instruction: the parent runs it concurrently and Gradle must not run twice in the same clone).

## .taskcheck

Result:

Not run by this helper, same reason as above.

## Bugs found

None.

Observations (no fix required, for the parent and reviewer to weigh):
- The fallback restore key `robolectric-` restores jars of an older Robolectric version after a version bump. They
  are harmless but are then included in the next saved cache, so the cache keeps old jars until GitHub evicts the
  entry. Cost only, not correctness.
- Two concurrent cold runs saving the same key: `actions/cache/save` logs a warning when the key is already
  reserved and does not fail the job, so this is safe.

## Coverage

Status: not applicable. The task changes only a GitHub Actions workflow (YAML with an inline shell step) and one
documentation line. The project's coverage tooling (JaCoCo for server/android, the Angular/vitest provider for web,
`docs/ARCHITECTURE.md` "Coverage") instruments Kotlin and TypeScript production code; neither changed file is in its
scope, and no coverage tool exists for workflow files.

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured (no production code in scope).

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: ci-robolectric-cache; base
  6ca342c2ec93616b598957a39b700fa52a467071; task commit 4a15387d3d9fecac829fbaff81491a10412ea53b supplied by the
  parent, rebased as 8c45a0e7ef06ef64a89f05c559551d213f321655 (identical content); HEAD at measurement
  8c45a0e7ef06ef64a89f05c559551d213f321655.
- Source/test snapshot identifier (including uncommitted inputs): blob of `.github/workflows/ci.yml` at HEAD
  2e0e0e5bef50c69c43436d539962c852e08009be; no uncommitted source changes.
- Exact included files; renamed/deleted/excluded files and reasons: no production files in scope;
  `.github/workflows/ci.yml` (CI configuration) and `docs/ARCHITECTURE.md` (documentation) excluded as non-instrumentable.
- Provider/version, command, working directory, and test selection: none run (not applicable).
- Run start/completion time and exit status: not applicable.
- Generated report paths and report hashes/run identifier: none.
- Evidence outputs are fresh and inputs stayed unchanged: the validations above ran on the HEAD content listed.
- Final run after latest test additions and production fixes: yes (no tests added, no fixes made).

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

## Summary

No additional tests were necessary for the reviewed scope. actionlint, the key-step simulations and the Node 24
metadata checks pass; no defects found. GitHub-side behavior remains to be confirmed by a real CI run.
