---
title: CI: cache Robolectric runtime jars (Maven Central 429 fails the Android check)
status: done
owner: dell26/agent-1
deps: []
touches: [.github/workflows/ci.yml,docs/ARCHITECTURE.md]
prio: 1
lease_until: 
---
## Goal
The "Fast check (android)" CI job failed in 4 of 7 pushes on 2026-11-22 (runs 45005199770, 45008992077, 45014587068,
45018745005) with `Failed to fetch maven artifact org.robolectric:android-all-instrumented:<sdk>-robolectric-...`,
caused by `HTTP response code: 429` from repo1.maven.org. Robolectric downloads one large Android runtime jar per SDK
level the tests use (currently 11, 14, 16) at test time, outside Gradle, and the job caches none of them, so every run
downloads them again from GitHub's shared runner IPs, which Maven Central rate-limits. Locally the jars sit in
`~/.m2/repository/org/robolectric`, so agents' checks pass.

## Acceptance criteria
- [x] The Android fast-check job restores and saves the Robolectric runtime jars (e.g. `actions/cache` for
  `~/.m2/repository/org/robolectric`, key derived from the Robolectric version in
  `android/gradle/libs.versions.toml`, plus a restore key), so a run with a warm cache downloads none of them
  - verified (2026-11-22 13:03+0100 by dell26/agent-1): ci.yml check-android: actions/cache/restore@v5 + actions/cache/save@v5 for ~/.m2/repository/org/robolectric, key robolectric-<libs.versions.toml robolectric version>-<sha of jar list>, restore-keys robolectric-<ver>- and robolectric-; podman actionlint -> clean; sh .tmp/keytest.sh (simulates key step) -> no key without jars, stable key for same jars, new key when a jar is added. Warm-cache run on GitHub not observable here (#5 skipped)
  - verified (2026-11-22 13:07+0100 by dell26/agent-1): ci.yml check-android (rev 1dfe46e78): actions/cache/restore@v5 + actions/cache/save@v5 for ~/.m2/repository/org/robolectric, key robolectric-<libs.versions.toml robolectric version>-<sha of jar list>, single per-version restore key robolectric-<ver>- (no cross-version fallback); podman actionlint -> clean; sh .tmp/keytest.sh (simulates key step) -> no key without jars, stable key for same jars, new key when a jar is added. Warm-cache run on GitHub not observable here (#5 skipped)
- [x] The cache is also saved when the tests fail (a partial download is not lost) and a cold cache still works as today
  - verified (2026-11-22 13:04+0100 by dell26/agent-1): save step uses if: always() (runs after a failed check step) and is separate from restore (actions/cache main action saves only on success); cold cache: restore misses -> tests download as before -> save; actionlint -> clean. Not observed in a real failing GitHub run (#5 skipped)
- [~] Optional hardening if cheap: Robolectric download retries or a single prefetch step before the tests, so one
  429 does not fail many test classes; no change to the tests themselves
  - skipped (2026-11-22 13:03+0100 by dell26/agent-1): optional hardening not cheap: Robolectric 4.16 has no download-retry setting and a prefetch would hardcode internal android-all-instrumented versions; recorded as assumption
- [x] Actions flagged by GitHub as targeting deprecated Node.js 20 (`actions/upload-artifact@v4`,
  `android-actions/setup-android@v3`) are bumped to versions running on Node 24 where such versions exist
  - verified (2026-11-22 13:03+0100 by dell26/agent-1): upload-artifact@v4->@v7 (4 uses), setup-android@v3->@v4 (2 uses); curl raw action.yml of upload-artifact@v7, setup-android@v4, cache/restore@v5, cache/save@v5 -> all 'using: node24'; actionlint -> clean
- [~] Evidence from GitHub Actions: after `done` pushes, a CI run on main shows the cache restored (log line of the cache
  step) and the Android job green, e.g. `gh run view <id> --log | grep -i cache`; if no run is reachable from the agent,
  skip with the reason and record an assumption
  - skipped (2026-11-22 13:03+0100 by dell26/agent-1): no GitHub Actions run reachable from the agent before done pushes (gh CLI not permitted here); a human checks the first main run's 'Restore Robolectric runtime jars' log line
- [x] `sh scripts/check.sh` passes; ARCHITECTURE.md mentions the cache where CI is described (one line)
  - verified (2026-11-22 13:04+0100 by dell26/agent-1): sh scripts/check.sh (all parts, at 4a15387d3) -> all checks passed, rc=0; docs/ARCHITECTURE.md CI paragraph gained one sentence on the Robolectric jar cache

## Notes
Planned 2026-11-22 by the owner after the Maven Central 429 failures; failed job of run 45018745005 re-run by hand.
- ASSUMPTION (2026-11-22 13:03+0100 by dell26/agent-1): Optional hardening (Robolectric download retries / prefetch step) skipped: Robolectric 4.16 has no retry setting and the exact android-all-instrumented artifact versions are internal, so a prefetch would hardcode them; the cache alone removes repeat downloads.
- ASSUMPTION (2026-11-22 13:03+0100 by dell26/agent-1): CI-run evidence (criterion 5) not gathered: agent cannot reach GitHub Actions runs; assumed actions/cache restore/save v5 behave as documented. Expect first main run to save robolectric-4.16-<hash>, the next to log 'Cache restored from key: robolectric-4.16-...' and skip the save.
- done by dell26/agent-1: `sh scripts/check.sh` passed in 69s on c7f616c4c; criteria verified 4/6, skipped #3 #5
