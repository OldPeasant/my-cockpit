# Fix Report

Date: 2026-11-08

Task: and-user-settings - Android reads and writes the synced user settings

Scope: current task; findings CR-1 and CR-2 of `reviews/and-user-settings/review.md` (CR-3 not fixed by decision of the parent worker). Base commit 80c9f39e15daef3e3c6c57536749ae4e264ebac7, task commits 0aada7887, ece2e9920, e15fe954b, f34cc57fc; the fix is the uncommitted working-tree change on top of f34cc57fc.

## Fixed findings

### CR-1 - An older GET answer can overwrite a newer PATCH answer (out-of-order results)

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/core/settings/UserSettingsRepository.kt`

Problem:
`refresh()` sent its GET outside the mutex and only checked a `generation` counter that changed in `clear()` alone. A GET answered with the state before a successful PATCH, but arriving after it, was applied and cached, bringing back the old theme and the stale ETag (and a spurious 412 on the next write). Of two overlapping refreshes, the slower, older answer won.

Fix:
The `generation` counter is replaced by two counters guarded by the mutex: `sequence` (the next ticket) and `appliedSequence` (the ticket of what set the current state).
- `refresh()` takes a ticket under the lock before sending the GET; after the answer it takes the lock again and drops the answer (result `FAILED`, nothing applied or cached) when `ticket < appliedSequence`, i.e. when a newer update answer, a later refresh answer or a logout was applied in the meantime.
- `update()` (which holds the lock for the whole PATCH) applies a successful answer with a fresh ticket, so any refresh started before it is dropped; a refresh started during the PATCH waits for the lock and therefore gets a higher ticket and is accepted.
- `clear()` sets `appliedSequence` to a fresh ticket, so a GET started before a logout is still dropped (the previous `generation` behavior is kept).
- The cache read applies with a fresh ticket; its comment now states that it is defensive, because `refresh`, `update` and `clear` all `join()` the cache read first.
- `apply()` records the ticket in `appliedSequence`; `accept()` passes it through. Non-2xx answers are not applied and do not move `appliedSequence`.
- The KDoc of `lock` and of `refresh()` was corrected to describe the real guarantee.

Review of the fix: traced all orderings (refresh/update, refresh/refresh in both answer orders, refresh/clear, update conflict followed by the internal refresh). In each, the state and the cache end with the newest applied answer, and the ETag always belongs to the shown state. The `FAILED` result of a dropped refresh is ignored by every caller (`MainActivity.onStart`, the sign-in collector in `start()`, the post-conflict reload in `update()`), so no misleading error is shown. No public contract changed.

Validation:
- `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.core.settings.*' ktlintCheck -q` (run by the parent worker on this working tree) - Pass

### CR-2 - The repository's concurrency guards have no tests

Files changed:
- `android/app/src/test/java/ch/sonensei/cockpit/core/settings/UserSettingsRepositoryEdgeCasesTest.kt`

Problem:
No test covered a GET still pending during `clear()`, the ordering between `refresh()` and `update()`, or overlapping refreshes, so removing a guard would not fail the suite.

Fix:
`UserSettingsRepositoryEdgeCasesTest` uses suspending fake answers (`CompletableDeferred`) for the concurrency cases:
- (a) `a load answered after a logout does not bring the old user's settings back` (already added by the test-writer): GET pending, `clear()`, GET completes; settings and store stay empty, the next refresh loads normally.
- (b) `a refresh answered after an update does not overwrite the update's newer answer` (already added by the test-writer): it fails against the old `generation` guard and passes with the fix; the updated theme and ETag "2" stay in the state and the cache.
- New: `of two overlapping refreshes the later one wins even when the earlier answer arrives last` - the older refresh returns `FAILED`, the later answer stays in the state and the cache.
- New: `of two overlapping refreshes answered in order both are taken and the later one stays` - guards against over-dropping: both return `LOADED`.
- (c) from the review (a cache read completing after a server answer) cannot happen: `refresh`, `update` and `clear` all wait for the cache read before touching the state, so there is no ordering to test. The guard stays as a defensive check and its comment says so. The cache-at-start path is covered by `the cached settings are shown at start and stay when the server cannot be reached`.

Validation:
- `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.core.settings.*' ktlintCheck -q` (run by the parent worker on this working tree) - Pass

## Not fixed

### CR-3 - Cold start may draw one frame in the system theme before the cached theme applies

Status:
Out of scope (decision of the parent worker)

Reason:
P3 and optional in the review, not reproduced on a device; the fix (holding the splash screen or a synchronously readable theme) would go beyond the minimal change for this task. No code was changed for it.

## Validation summary

Focused tests:
- `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.core.settings.*' ktlintCheck -q` - Pass (no failures, no ktlint violations); run by the parent worker on the current working tree, because this fixer cannot run Gradle. The fixer made no code changes after that run (it only reviewed the uncommitted fix and wrote this report), so the result applies to the final code.

Full test suite:
- Not run by the fixer (Gradle not available to sub-agents); left to the parent worker.

Lint/type-check/build:
- ktlintCheck: Pass (in the command above). `lintDebug` not run by the fixer.

`.taskcheck`:
- Not run (left to the parent worker).

Remaining unresolved findings:
1 (CR-3, out of scope by decision)

## Final status

All validated findings fixed and validation passed.
