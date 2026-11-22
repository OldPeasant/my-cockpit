# Fix Report

Date: 2026-11-22

Task: ci-robolectric-cache - CI: cache Robolectric runtime jars (Maven Central 429 fails the Android check)

Scope: current task, finding CR-1 from `reviews/ci-robolectric-cache/review.md`

## Fixed findings

### CR-1 - Cross-version restore key carries stale Robolectric jars into every later cache entry

Files changed:
- `.github/workflows/ci.yml`

Problem:
The fallback restore key `robolectric-` in step "Restore Robolectric runtime jars" restored the jar tree of an older Robolectric version after a version bump. The save key hashes every jar in that tree, so the old version's jars were saved again under the new key and the cache grew with every bump.

Fix:
Removed the `robolectric-` line from `restore-keys`. Only the per-version prefix `robolectric-${{ steps.robolectric.outputs.version }}-` remains, so a version bump starts with a cold cache (one download per version) and old jars are never restored or saved again. The comment above the steps now says the cache has no cross-version fallback, and why. `docs/ARCHITECTURE.md` already describes the cache as "per Robolectric version" and needed no change. This is the second option the reviewer suggested.

Commit: `1dfe46e78ccc5c0f15d3ab56308fd8526900ebf6` ("ci-robolectric-cache: drop cross-version Robolectric restore key")

Validation:
- `podman run --rm -v ./:/repo:Z -w /repo docker.io/rhysd/actionlint:latest` - Pass (no output, exit 0)

## Not fixed

None.

## Validation summary

Focused tests:
- actionlint (command above) - Pass

Full test suite:
- Not run (the parent said not to run Gradle or check.sh; the change only affects the GitHub Actions workflow, which `.taskcheck` does not run)

Lint/type-check/build:
- actionlint - Pass

`.taskcheck`:
- Not run (the parent said not to run it)

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed.
