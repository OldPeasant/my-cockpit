# Code Review

Date: 2026-11-22

Task: ci-robolectric-cache - CI: cache Robolectric runtime jars (Maven Central 429 fails the Android check)

Scope: `git diff 6ca342c2e 4a15387d3` (one task commit: `.github/workflows/ci.yml`, `docs/ARCHITECTURE.md`)

Requirements:
- tasks/ci-robolectric-cache.md acceptance criteria and notes (assumptions on skipped hardening and skipped CI-run evidence)
- docs/ARCHITECTURE.md (CI section), AGENTS.md (Evidence rules, conventions)
- Parent instructions: review cache restore/save semantics, Node 24 bumps, ARCHITECTURE.md line; optional hardening intentionally skipped; Gradle and check.sh not run by the reviewer

Git range:
- Branch: main
- HEAD: 4a15387d3d9fecac829fbaff81491a10412ea53b
- Base/start: 6ca342c2ec93616b598957a39b700fa52a467071
- Commits reviewed: 1

Summary: 0 P0, 0 P1, 0 P2, 1 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | Cross-version restore key carries stale Robolectric jars into every later cache entry**
    - File: `.github/workflows/ci.yml:114`
    - Category: Quality
    - Problem: The fallback restore key `robolectric-` restores the whole `~/.m2/repository/org/robolectric` tree of an older Robolectric version after a version bump. The save key hashes every jar in that tree, and nothing prunes jars the new version no longer uses, so the old version's android-all-instrumented jars (each roughly 100-200 MB) get saved again under the new key and stay in every later entry. Each Robolectric bump whose instrumentation version changes makes the cache bigger.
    - Why it matters: Restore time and cache storage grow with every bump. GitHub evicts at 10 GB per repository, so large entries push out other caches, such as the Gradle cache from setup-gradle. This does not break correctness.
    - Suggested fix: Keep the fallback (it helps when jar versions do not change between Robolectric releases), but before computing the key, delete jars that the run did not access, for example with `find -atime` or by comparing against a marker file touched before the tests. Or drop the `robolectric-` fallback and accept one cold download per version bump.

## Verified without findings

- Restore semantics: the primary key `robolectric-4.16-` never matches exactly, because saved keys always have a hash suffix. The first restore key, which is the same prefix, picks the most recently saved entry for this version. `cache-matched-key` is an output of `actions/cache/restore`, so the save condition can use it. The version is read correctly by the `sed` expression from `android/gradle/libs.versions.toml:28` (`robolectric = "4.16"`). Line 93 (`robolectric = {`) does not match.
- Save on failure: restore and save are separate steps. The key step and the save step use `if: always()`, so they also run after a failed or timed-out `check.sh android`. This fixes the problem that the combined `actions/cache` action only saves after success.
- Cold cache: the restore misses, the tests download as before, and the key step finds jars and saves. If no jar exists (for example, the run failed before any download), no key is written and the save is skipped. No empty cache is created.
- Warm cache, unchanged set: the hash of the sorted jar list matches the restored key, so the save is skipped. A new SDK level adds a jar, which gives a new hash, so it is saved under a new key. The default `bash -e` shell without pipefail does not abort on `find` errors inside the command substitution.
- Path and home: `~` is supported in `actions/cache` paths, and it expands to the same runner home that Robolectric's default local repository uses (`user.home/.m2/repository`). The Android build changes only `java.io.tmpdir`, not `user.home`.
- Node 24 bumps: all four `upload-artifact@v4` uses are now `@v7`, and both `setup-android@v3` uses are now `@v4`. No v4 or v3 references remain in the workflow. The recorded evidence (raw action.yml shows `using: node24`, actionlint clean) supports the criterion. The reviewer could not re-check this because network access was denied here.
- ARCHITECTURE.md: one sentence was added in the CI paragraph. It names the path, the per-version key and save-on-failure, as criterion 6 requires.
- Security: workflow `permissions: contents: read` is unchanged. The cache scope follows GitHub's ref rules, so PR caches cannot overwrite main's. No secrets were introduced.
- Evidence: criteria 1 and 2 were verified by static reasoning, actionlint and a local simulation of the key step. They say that the warm-cache behaviour was not observed. Criterion 5 (real CI run) is skipped with a reason and an assumption, as the task allows. Criterion 6 is still open (check.sh pending in the parent).

## Review conclusion

No material issues found.
