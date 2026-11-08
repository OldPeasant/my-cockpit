# Code Review

Date: 2026-11-08

Task: and-user-settings - Android reads and writes the synced user settings

Scope: review of the whole task on the final revision. Android task commits only: `git diff a32b1930c..HEAD -- android` (commits fe6fd3c36, decac4add, 03a170513, 84c4cf231, c317d934e, 8c228f06e). Other paths in that range belong to other tasks merged from main and were not reviewed. Third pass: the only change since the second-round review of c317d934e is 8c228f06e, which adds two tests to `UserSettingsRepositoryEdgeCasesTest.kt` plus the review reports. It changes no production code.

Requirements:
- tasks/and-user-settings.md (acceptance criteria and recorded assumptions)
- SPEC.md §4.2 (ETag/If-Match), §4.7 (settings)
- docs/ARCHITECTURE.md, AGENTS.md project notes
- First-round review findings CR-1, CR-2, CR-3 and reviews/and-user-settings/fixes.md

Git range:
- Branch: main
- HEAD: 8c228f06e3435a0e081150a65b372d8100ada70c
- Base/start: a32b1930c26fc97f61c159db611ff19b2a731a3e
- Commits reviewed: 6 (fe6fd3c36, decac4add, 03a170513, 84c4cf231, c317d934e, 8c228f06e); no uncommitted changes

Summary: 0 P0, 0 P1, 0 P2, 1 P3

Validation seen: the parent ran `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug -q` on c317d934e (exit 0, 2940 test cases, 0 failures). On 8c228f06e it ran `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.core.settings.UserSettingsRepositoryEdgeCasesTest' ktlintCheck -q` (passed, 11 tests, 0 failures). The reviewer could not run Gradle. The results below come from reading the code and the tests.

Note on hashes: fixes.md names the pre-rebase commits 80c9f39e1 and f34cc57fc. After the rebase, the fix is commit c317d934e.

## Verification of the first-round findings

- CR-1 (an older GET answer could overwrite a newer PATCH answer): fixed in c317d934e. `UserSettingsRepository.kt:104-166` replaces the old `generation` counter with `sequence` and `appliedSequence`, both guarded by the mutex. `refresh()` takes its ticket under the lock before it sends the GET and drops the answer when `ticket < appliedSequence`. A successful `update()` applies with a fresh ticket while it still holds the lock, and `clear()` moves `appliedSequence` forward. The reviewer traced these orderings: refresh then update; an update with a refresh started during the PATCH (that refresh waits for the lock, gets a higher ticket and is correctly accepted); two overlapping refreshes answered in either order; refresh then logout; a failed newer refresh followed by a successful older one (accepted, which is correct); and a conflict followed by the internal reload. In every case the newest applied answer stays in the state and the cache, and the ETag always belongs to the state shown. Failed answers (non-2xx or no body) return from `accept()` before `apply()` and do not move `appliedSequence`.
- CR-2 (the concurrency guards had no tests): fixed. `UserSettingsRepositoryEdgeCasesTest.kt:47-181` uses suspending fake answers (`CompletableDeferred`) and now covers six orderings. The four from c317d934e: a GET answered after logout is dropped; a GET answered after an update keeps the update's theme and ETag "2"; overlapping refreshes answered out of order keep the later answer; overlapping refreshes answered in order are both accepted. 8c228f06e adds the two below.
- CR-3 (possible theme flash at cold start): not fixed, by the parent's decision. It is carried over below as P3.

## Review of the two tests added in 8c228f06e

- `a refresh started while an update is in flight is taken after the update` (line 138): correct, and it tests what its name says. With `UnconfinedTestDispatcher`, `update` runs eagerly into `api.updateSettings` and suspends on `patchGate` while it holds the mutex. `refresh` then waits on `lock.withLock { ++sequence }`. Once the gate opens, the update applies "2" with ticket 2 and releases the lock. The refresh takes ticket 3, gets the re-stubbed GET ("3", theme system) and applies it. The test catches regressions. If the refresh took its ticket without the lock, or sent its GET before the update finished, it would get the lower ticket and be dropped, leaving LIGHT/"2" and failing the assertions. The theme values (dark, then light, then system) differ at every step, so the final assertions show which answer won.
- `a later refresh that fails does not make an earlier answer be dropped` (line 163): correct, and it tests what its name says. The older refresh gets ticket 1 and the newer gets ticket 2. Both GETs are sent outside the lock, so the `ArrayDeque` hands out `first` and `second` in call order. The 503 goes through `accept()` and returns OFFLINE (`code >= SERVER_ERROR`) without calling `apply()`, so `appliedSequence` stays 0. The older answer is then accepted (LOADED, LIGHT, ETag "1"). If a regression made failed answers advance `appliedSequence`, the older answer would come back FAILED and the test would fail.
- Flakiness: none found. The repository runs in `backgroundScope` on the test's unconfined dispatcher, so `cacheRead` and every suspension point are deterministic. There are no real delays, threads or wall-clock dependencies. Each test builds its own mocks and `MemoryStore` because JUnit creates a fresh instance per test. `ArrayDeque` here is Kotlin's `kotlin.collections.ArrayDeque`, so the JDK 21 `List.removeFirst` ambiguity does not apply.

## Acceptance criteria

1. Repository: `UserSettingsRepository` is a Hilt `@Singleton`. It loads `GET /api/v1/settings` and caches the document plus the ETag in its own DataStore file, using a replace-on-corruption handler. It exposes `settings: StateFlow<UserSettings?>`. `update(UserSettingsChange)` sends only the changed keys, with If-Match set to the stored ETag. On 409, 412 or 428 it reloads without retrying the write and returns Conflict. Implemented. The recorded assumption covers the missing SSE event: the settings refresh at sign-in and on MainActivity `onStart`.
2. Theme: `CockpitSettingsTheme` (`ui/theme/Theme.kt`) is used by MainActivity, CaptureActivity and ShareActivity. It maps light, dark and system to dark-or-not, keeps dynamic color, and restyles the system bars. "System" behaves as before. Implemented.
3. Calendar: `CalendarViewModel` takes the synced week start and zone and falls back to the locale and the device zone while the settings are unknown. When either value changes, it reloads the shown range. `EventEditorViewModel` defaults new and edited events to the synced zone. Implemented.
4. Labels: `userEnergyLabel` and `userCreativityLabel` (`LocalUserSettings.kt`) are used in inbox processing, task detail (view and edit) and the Next filter chips. Labels equal to the server's English defaults fall back to the app's translation, as recorded in an assumption. Implemented.
5. Strings: all nine new `settings_appearance_*` strings exist in both `values/strings_settings.xml` and `values-de/strings_settings.xml`. Implemented.
6. Tests and checks: covered are the repository (load, cache, offline, update, conflict, clear, ordering, storage failure), the DataStore store, settings parsing, theme mapping and the theme composable, calendar and editor settings, inbox and task detail labels, the Appearance view model and the Appearance section. The parent reports the Android check passing on c317d934e and the changed test class passing on 8c228f06e. Recording `sh scripts/check.sh` is the parent's job.

Also checked and found fine: no secrets, settings contents or user data are logged. Logout clears the cache and the state through the `SessionCache` contribution. Unknown or invalid server values fall back to the defaults. The Appearance view model resets `saving` on every result, so a failed or conflicting save always ends in a usable screen with a message.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-3 | Cold start may draw a frame in the system theme before the cached theme applies (carried over, not fixed by parent decision)**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/core/settings/UserSettingsRepository.kt:119`
    - Category: Quality
    - Problem: the cached settings are read asynchronously (`cacheRead` through DataStore), and `settings` starts as `null`. `CockpitSettingsTheme` treats `null` as "system". A user whose explicit light or dark choice differs from the system setting may see the first frames of a cold start in the system brightness, system bars included. Not reproduced on a device.
    - Why it matters: a brief light/dark flash at start. The user still ends up on the right theme.
    - Suggested fix: optional. Keep the splash screen up until the first cache read finishes, or store the theme alone where it can be read synchronously.

## Review conclusion

No material issues found.

CR-1 and CR-2 are fixed and covered by tests. The two tests added in 8c228f06e are correct, deterministic, and would catch the regressions their names describe. All acceptance criteria are implemented. The only open item is CR-3, a P3 the parent chose not to fix. It does not block completion.
