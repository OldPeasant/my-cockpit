---
title: CI: cache Robolectric runtime jars (Maven Central 429 fails the Android check)
status: in_progress
owner: dell26/agent-1
deps: []
touches: [.github/workflows/ci.yml,docs/ARCHITECTURE.md]
prio: 1
lease_until: 2026-11-22T12:22:17Z
---
## Goal
The "Fast check (android)" CI job failed in 4 of 7 pushes on 2026-11-22 (runs 45005199770, 45008992077, 45014587068,
45018745005) with `Failed to fetch maven artifact org.robolectric:android-all-instrumented:<sdk>-robolectric-...`,
caused by `HTTP response code: 429` from repo1.maven.org. Robolectric downloads one large Android runtime jar per SDK
level the tests use (currently 11, 14, 16) at test time, outside Gradle, and the job caches none of them, so every run
downloads them again from GitHub's shared runner IPs, which Maven Central rate-limits. Locally the jars sit in
`~/.m2/repository/org/robolectric`, so agents' checks pass.

## Acceptance criteria
- [ ] The Android fast-check job restores and saves the Robolectric runtime jars (e.g. `actions/cache` for
  `~/.m2/repository/org/robolectric`, key derived from the Robolectric version in
  `android/gradle/libs.versions.toml`, plus a restore key), so a run with a warm cache downloads none of them
- [ ] The cache is also saved when the tests fail (a partial download is not lost) and a cold cache still works as today
- [~] Optional hardening if cheap: Robolectric download retries or a single prefetch step before the tests, so one
  429 does not fail many test classes; no change to the tests themselves
  - skipped (2026-11-22 13:03+0100 by dell26/agent-1): optional hardening not cheap: Robolectric 4.16 has no download-retry setting and a prefetch would hardcode internal android-all-instrumented versions; recorded as assumption
- [ ] Actions flagged by GitHub as targeting deprecated Node.js 20 (`actions/upload-artifact@v4`,
  `android-actions/setup-android@v3`) are bumped to versions running on Node 24 where such versions exist
- [~] Evidence from GitHub Actions: after `done` pushes, a CI run on main shows the cache restored (log line of the cache
  step) and the Android job green, e.g. `gh run view <id> --log | grep -i cache`; if no run is reachable from the agent,
  skip with the reason and record an assumption
  - skipped (2026-11-22 13:03+0100 by dell26/agent-1): no GitHub Actions run reachable from the agent before done pushes (gh CLI not permitted here); a human checks the first main run's 'Restore Robolectric runtime jars' log line
- [ ] `sh scripts/check.sh` passes; ARCHITECTURE.md mentions the cache where CI is described (one line)

## Notes
Planned 2026-11-22 by the owner after the Maven Central 429 failures; failed job of run 45018745005 re-run by hand.
- ASSUMPTION (2026-11-22 13:03+0100 by dell26/agent-1): Optional hardening (Robolectric download retries / prefetch step) skipped: Robolectric 4.16 has no retry setting and the exact android-all-instrumented artifact versions are internal, so a prefetch would hardcode them; the cache alone removes repeat downloads.
