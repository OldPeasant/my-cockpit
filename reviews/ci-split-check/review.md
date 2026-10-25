# Code Review

Date: 2026-10-25

Task: ci-split-check - CI: split the fast check into parallel jobs per part

Scope: round 2, `git diff 258d81e7..d20006874`. This covers scripts/check.sh, .github/workflows/ci.yml, docs/ARCHITECTURE.md and the new tests/test_check_sh.sh, plus the review/test/fix reports. Committed changes only. The working tree was clean.

Requirements:
- tasks/ci-split-check.md acceptance criteria 1-3, notes and the recorded assumption
- docs/ARCHITECTURE.md (Testing table, CI paragraph)
- AGENTS.md (Evidence, project notes)
- Round-1 findings CR-1 and CR-2, and reviews/ci-split-check/fixes.md

Git range:
- Branch: main
- HEAD: d200068745c679aa3509f3fa99eaa1c03d50fbed
- Base/start: 258d81e7dd4e7841ab219ce64eb2b4422bad338e
- Commits reviewed: 3 (9e39b3fde, f1452a7fb, d20006874)

Summary: 0 P0, 0 P1, 0 P2, 2 P3

## Round-1 findings

- **CR-1 (timeout headroom not measured): resolved.** check-web and check-android now use `timeout-minutes: 35` (ci.yml:63, ci.yml:77). The reasoning is sound. The serial job took 32 min and the server took 14.5 min of that, so tfp, web, android and the shared setup together took at most about 17.5 min. Neither part alone can take longer, which gives each at least 2x headroom. Each parallel job now repeats its own JDK/Gradle setup, but that adds minutes, not tens of minutes. check-server stays at 35 (2.4x measured) and check-tfp at 10. Criterion 1 can be recorded on this bound. The evidence should say it is a bound, not a per-job measurement. The criterion 3 run will supply the real per-job durations.
- **CR-2 (comment implied measured timeouts): resolved.** The comment at ci.yml:35-37 now says only the server was measured (run 39625365916), and that the web/android limits come from the ~17.5 min bound.

## What I checked (final revision)

- **check.sh.**
  - The code is unchanged since round 1, and the round-1 analysis still holds: the no-arg order and commands are as before, the `COCKPIT_NO_ANDROID=1` skip still works, it is safe under `set -u`, part names must match exactly, and unknown or empty parts exit 2 before anything runs.
  - `.taskcheck` is still `sh scripts/check.sh`.
- **ci.yml.**
  - Four jobs, each calling `sh scripts/check.sh <part>`, each with only the setup that part needs.
  - The header comment describes the layout. The fix commit changed only timeout values and the comment.
- **tests/test_check_sh.sh.**
  - It runs a copy of check.sh in a fixture tree, with stubbed gradlew/npm and a stub test_quality_gate.py.
  - It covers: the no-arg order, COCKPIT_NO_ANDROID with and without a selector, each single part, several parts, duplicates, unknown/empty/case-mismatched parts, failure propagation, the `npm ci` lockfile path, and the ci.yml wiring (each part exactly once, 4 calls in total).
  - The assertions check behavior (which commands ran, the exit code, the final line), not implementation details. The fixture lives in `.tmp/` and is removed on exit.
- **Docs.** The ARCHITECTURE.md Testing row and CI paragraph match the script usage and the job layout.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-3 | The recorded timeout assumption is now stale**
    - File: `tasks/ci-split-check.md:28`
    - Category: Quality
    - Problem: The ASSUMPTION line still says "android 30, web 20" and "~2x measured". The code now uses 35/35 based on the ~17.5 min upper bound.
    - Why it matters: Humans review assumptions with `tasks.py assumptions`. The recorded decision no longer matches what was shipped.
    - Suggested fix: Record a new assumption with `python tasks.py assume ci-split-check "..."` that states the 35 min web/android limits and the bound behind them. Don't edit the old line by hand.

- [ ] **CR-4 | The new selector test is not listed with the other TFP tooling tests**
    - File: `docs/ARCHITECTURE.md:138`
    - Category: Quality
    - Problem: The Testing table names `tests/test_*.py` and `bash tests/test_publish.sh` as the TFP tooling tests, but not `bash tests/test_check_sh.sh`. It runs in neither check.sh nor CI, so nobody will find it unless the docs mention it. The file's own header says to run it with `bash`, while fixes.md ran it with `sh`. That works on this machine only because `sh` is bash here.
    - Why it matters: The selector is what keeps CI and agents running the same check (criterion 2). An unlisted test is unlikely to be re-run when check.sh or ci.yml changes.
    - Suggested fix: Add `bash tests/test_check_sh.sh` to that table row. Optionally, run it in the check-tfp job.

## Review conclusion

No material issues found. CR-1 and CR-2 are resolved in d20006874. The two P3 items are optional cleanups. Criterion 3 (a green GitHub run with per-job durations) still has to be verified on GitHub after the push. That run also confirms the bound behind criterion 1.
