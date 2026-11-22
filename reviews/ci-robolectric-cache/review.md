# Code Review

Date: 2026-11-22

Task: ci-robolectric-cache - CI: cache Robolectric runtime jars (Maven Central 429 fails the Android check)

Scope: Round 2, final revision. `git diff 6ca342c2e 1dfe46e78 -- .github docs`. Task code commits are 8c45a0e7e (implementation) and 1dfe46e78 (fix for CR-1). The other commits in the range only touch `tasks/` and `reviews/`.

Requirements:
- tasks/ci-robolectric-cache.md: acceptance criteria, notes and assumptions (optional hardening skipped, CI-run evidence skipped)
- docs/ARCHITECTURE.md (CI section), AGENTS.md (Evidence rules, conventions)
- reviews/ci-robolectric-cache/fixes.md (fix for CR-1)
- Parent instructions: confirm CR-1 is resolved and that nothing new broke. Gradle and check.sh were not run by the reviewer.

Git range:
- Branch: main
- HEAD: 1dfe46e78ccc5c0f15d3ab56308fd8526900ebf6 (the reviewed revision; the later HEAD e61a78691 only adds fixes.md)
- Base/start: 6ca342c2ec93616b598957a39b700fa52a467071
- Commits reviewed: 2 code commits (8c45a0e7e, 1dfe46e78), plus board and report commits with no code

Summary: 0 P0, 0 P1, 0 P2, 1 P3

## Round 1 finding

- CR-1 (P3, round 1): the cross-version restore key `robolectric-` brought an older Robolectric version's jars into every later cache entry. **Resolved** in 1dfe46e78. The `robolectric-` line was removed from `restore-keys` in `.github/workflows/ci.yml:113-114`. Only the per-version prefix `robolectric-${{ steps.robolectric.outputs.version }}-` remains. The step comment (lines 100-101) now explains that there is no cross-version fallback. After a version bump the job starts with a cold cache, which is the accepted cost. The cache can no longer restore old-version jars and save them again.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-2 | Criterion 1 evidence still lists the removed `robolectric-` restore key**
    - File: `tasks/ci-robolectric-cache.md:22`
    - Category: Tests
    - Problem: The evidence recorded for criterion 1 says "restore-keys robolectric-ver- and robolectric-". Since 1dfe46e78 the workflow has only the per-version restore key, so the evidence no longer matches the reviewed code.
    - Why it matters: A human who checks the done task against the workflow would find the evidence out of date. The behaviour itself is still correct and still meets the criterion, which only asks for "a restore key".
    - Suggested fix: Re-record criterion 1 with `tasks.py verify` and describe the final configuration (per-version restore key only, actionlint clean at 1dfe46e78).

## Verified without findings (final revision)

- Restore: the primary key `robolectric-4.16-` never matches exactly, because saved keys always end in a hash. The single restore key (same prefix) restores the most recent entry for this Robolectric version. The `sed` expression reads `4.16` from `android/gradle/libs.versions.toml:28`. Line 93 (the library alias) does not match the anchored pattern.
- Save on failure and cold cache: the separate key and save steps run under `always()`, so they also run after a failed `check.sh android`. With no jars, no key is written and the save is skipped. The save is also skipped when the computed key equals `cache-matched-key`, which is an output of `actions/cache/restore`. The 1dfe46e78 fix did not change any of this.
- Effect of the fix on the key logic: the hash suffix still separates jar sets within one version. Old entries of the same version are left for GitHub's LRU eviction. Nothing else depends on the removed fallback.
- Node 24 bumps: all four `upload-artifact` uses are `@v7`, both `setup-android` uses are `@v4`, and the cache actions are `@v5`. No `@v4` artifact or `@v3` setup-android reference remains. This is unchanged since round 1.
- ARCHITECTURE.md: one sentence in the CI paragraph names the path, the per-version cache and save-on-failure. "Per Robolectric version" is still accurate after the fix, so the doc needed no change.
- Security: `permissions: contents: read` is unchanged and no secrets were added. Cache scoping follows GitHub's branch rules.
- Validation: fixes.md records a clean actionlint run on the fixed workflow. `.taskcheck` does not run the workflow, so the one-line YAML change cannot affect it. Criterion 5 (real CI run) remains skipped with a reason and an assumption, as the task allows.

## Review conclusion

No material issues found.
