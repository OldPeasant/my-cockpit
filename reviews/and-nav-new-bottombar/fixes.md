# Fix Report

Date: 2026-11-14

Task: and-nav-new-bottombar (New takes Inbox's bottom-bar slot, Inbox heads the More list)

Scope: current task, finding CR-4 only (CR-2 and CR-3 are excluded by the parent worker)

## Fixed findings

### CR-4 — Nested stacked screens lose the highlighted tab after rotation or process recreation

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/ui/CockpitShell.kt`

Problem:
The underlying destination of a stacked entry (task detail, search, search item) was cached in a plain `remember` map keyed by entry id. Activity recreation empties the map while the NavController restores the back stack, so the top entry of a nested stack such as dashboard, new, task, mail item resolved to null (its previous entry is stacked and no longer in the map), no tab was highlighted, and the null was cached.

Fix:
Removed the remembered map. A new private helper `NavHostController.underlyingDestination(entry)` stores the resolved destination route on the back stack entry itself, in `entry.savedStateHandle` under the shell-private key `UNDERLYING_ROUTE_KEY`, which is saved and restored with the back stack. Resolution order: the value stored on the entry; otherwise the route of `previousBackStackEntry` via `CockpitDestination.fromRoute`, or, if that entry is stacked too, the value stored on its saved state handle. The result is written to the entry only when non-null, so null is never cached. `current` still takes precedence. No `currentBackStack` use and no lint suppressions.

Validation:
- `android/gradlew -p android ktlintFormat` — Pass (no further formatting changes)
- `android/gradlew -p android ktlintCheck lintDebug testDebugUnitTest --tests 'ch.sonensei.cockpit.ui.*' -q` — Pass (CockpitShellSelectionRestoreTest 3/3, CockpitShellSelectionTest 21/21, all 14 ui test classes with 0 failures and 0 errors)

## Not fixed

None in scope. CR-2 and CR-3 were excluded by the parent worker and were not examined for changes.

## Validation summary

Focused tests:
- `android/gradlew -p android ktlintCheck lintDebug testDebugUnitTest --tests 'ch.sonensei.cockpit.ui.*' -q` — Pass

Full test suite:
- Not run by the fixer (the parent worker runs `.taskcheck`; Gradle must not run in parallel)

Lint/type-check/build:
- ktlintCheck and lintDebug — Pass

`.taskcheck`:
- Not run (left to the parent worker)

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed.
