# Code Review

Date: 2026-10-20

Task: p0-ci - GitHub Actions CI

Scope: re-review of the final revision. `git diff 0995d28bff12d70c79389824cbd8771569b539bd..609e21a -- .github docs`: `.github/workflows/ci.yml` (new) and `docs/ARCHITECTURE.md` (CI paragraph). Checked that CR-1 from the previous review (HEAD 90cbf45) is fixed, as described in `reviews/p0-ci/fixes.md`. Board-only changes in `tasks/` and the review reports were not reviewed as implementation. Only committed changes were reviewed. The working tree was clean.

Requirements:
- tasks/p0-ci.md acceptance criteria and recorded assumptions (3 parallel jobs, major-tag action pins, Android checked in CI, triggers limited to main pushes + PRs with board/review paths ignored)
- docs/ARCHITECTURE.md "Stack", "Install", "Test"
- AGENTS.md (project notes, evidence rules, done flow)
- Parent instructions: verify the CR-1 fix; assess paths-ignore on pull_request vs. required checks; assess skipping reviews/** given that `done` pushes code and reviews together; run actionlint

Git range:
- Branch: main
- HEAD: 609e21a9ed364d04058b6e4da4a4ef6e67aecdff
- Base/start: 0995d28bff12d70c79389824cbd8771569b539bd
- Commits reviewed: 13 in range. 2 change `.github`/`docs`: 0a01809 (workflow) and 609e21a (CR-1 fix + reports). The other 11 are board-only commits (assume/heartbeat).

Summary: 0 P0, 0 P1, 0 P2, 1 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | "Runs on main are never cancelled" is not fully true: GitHub concurrency still replaces a pending main run**
    - File: `.github/workflows/ci.yml:5-6,25-27`; `docs/ARCHITECTURE.md:134`
    - Category: Quality
    - Problem: `cancel-in-progress: false` on main only protects the run that is *in progress*. Within a concurrency group, GitHub keeps at most one running and one pending run. When a third run is queued, it cancels the pending one, whatever `cancel-in-progress` says. So if three `done` pushes land on main while one run is still in progress, the middle push's run gets cancelled. The workflow comment ("Runs on main are never cancelled, so every finished task gets a complete result") and the ARCHITECTURE.md sentence ("runs on `main` are never cancelled") promise more than the configuration does.
    - Why it matters: Impact is low. The surviving later run tests the cumulative code, so a breakage still shows red. But it can't be attributed to the specific task merge, and the docs mislead readers about that guarantee.
    - Suggested fix: Either make the main group unique per commit, e.g. `group: ci-${{ github.ref == 'refs/heads/main' && github.sha || github.ref }}` (then every main push gets its own complete run), or change the comment and the doc to "a running main run is never cancelled; queued runs may be superseded by a newer push".

## Verified without findings

- **CR-1 (previous review) fixed:**
  - `push` is limited to `branches: [main]`, so `wip/*` backups and `status/*` branches no longer trigger CI.
  - `paths-ignore` covers `tasks/**`, `reviews/**` and `BOARD.md` on both events.
  - `cancel-in-progress: ${{ github.ref != 'refs/heads/main' }}` is a valid boolean expression. It stops in-progress main runs from being cancelled, with the pending-run caveat in CR-1 above.
  - `board.html` doesn't exist in the repository, so leaving it out is correct.
- **Skipping `reviews/**` is safe.** GitHub evaluates `paths-ignore` over all files changed in the push (before..after), and skips the run only if *every* changed file matches. `cmd_done` (`tasks.py:827`, `update(..., with_code=True)`) pushes the task's code commits, its review reports and the status change in one push, so any push that carries code still runs CI. Pushes that only touch the board (heartbeat/claim/assume/verify) or only reviews are correctly skipped. Other non-code paths (`board.py`, `dashboard/`, `tasks.py`) still trigger CI, which is the safe side.
- **paths-ignore on `pull_request` vs. required checks: no harm found.**
  - The documented flow has no PRs: agents' work reaches main only through `tasks.py done`, which pushes directly, and `repos.txt` is absent.
  - Nothing in the repository configures branch protection or required status checks.
  - Known GitHub caveat for later: if a human makes these jobs required checks, a PR that only touches `tasks/`, `reviews/` or `BOARD.md` would stay "pending" and block merging. Not a defect in this task's scope.
- **Criterion 1 (unchanged since the previous review):** JDK 21 via setup-java (Temurin), Node via `node-version-file: .nvmrc` (`24`), and Android `platforms;android-37.0` + `build-tools;36.0.0`. The jobs run `sh scripts/check.sh`, `./gradlew integrationTest` (in `server/`) and `docker build .`.
- **Criterion 2, version match:** unchanged from the previous review (server toolchain 21, android `VERSION_21`, Dockerfile temurin 21 / node 24, `engines` ^24.15.0).
- **Security:**
  - Workflow-level `permissions: contents: read`.
  - No `pull_request_target`.
  - No untrusted `${{ github.event.* }}` interpolation in `run:`.
  - No secrets. The image is built without a push.
- **Docs:** the ARCHITECTURE.md paragraph describes the triggers, skipped paths and actionlint command correctly, apart from CR-1.
- **actionlint:** could not be run by this reviewer in this session: the sandbox denied Docker and any local binary lookup. `reviews/p0-ci/fixes.md` records `docker run ... rhysd/actionlint:latest -no-color` -> exit 0 on the fixed workflow. A manual check of the syntax (event filters, the concurrency expression, step keys, `working-directory`) found no problems. The parent should re-run actionlint on HEAD 609e21a when recording criterion 2.
- **Evidence:** `tasks.py verify p0-ci` still shows both criteria unchecked. Before `done`, the parent must record actionlint and the local runs, and skip the "after push" part of criterion 2 with a reason, as the criterion allows.

## Review conclusion

No material issues found. CR-1 (P3) is a minor doc/comment accuracy issue that is optional to fix.
