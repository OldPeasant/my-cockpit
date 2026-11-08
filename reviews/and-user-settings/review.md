# Code Review

Date: 2026-11-08

Task: and-user-settings - Android reads and writes the synced user settings

Scope: committed changes of the task, `git diff 80c9f39e1..e97612486` (Android only)

Requirements:
- tasks/and-user-settings.md (acceptance criteria and recorded assumptions)
- SPEC.md §4.2 (ETag/If-Match), §4.7 (settings)
- docs/ARCHITECTURE.md, AGENTS.md project notes
- Server contract: server/src/main/kotlin/ch/sonensei/cockpit/core/settings (CoreSettings keys, values, defaults; SettingsController ETag/If-Match, 412/428)

Git range:
- Branch: main
- HEAD: e9761248653017b79b2cebf8738183ec1affdd51
- Base/start: 80c9f39e15daef3e3c6c57536749ae4e264ebac7
- Commits reviewed: 3 (215fcbda5, 57a4e8017, e97612486); no uncommitted changes

Summary: 0 P0, 0 P1, 2 P2, 1 P3

Validation seen: the parent ran `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug -q` (passed, 2915 tests, 0 failures). The reviewer could not run Gradle; the findings below come from reading the code.

Checked and found fine: the wire keys, values and defaults match `CoreSettings` on the server (theme light/dark/system, weekStart as DayOfWeek names, label keys, mailImagePolicy block/allow). The PATCH sends only the changed keys with the stored ETag in If-Match, and 409/412/428 reload the settings without retrying. The Hilt graph has no cycle: the `SessionCache` set contribution compiles and the build passed. Logout clears the cache and the state, and a `generation` guard drops a GET that was started before a clear. Unknown or invalid values fall back to the defaults. A corrupt DataStore file is replaced, and read/write/clear errors are swallowed without logging any content. The calendar takes the synced week start and zone (falling back to the locale and device zone while the settings are unknown) and reloads the shown range when they change. Inbox, Next and task detail take the user's labels through `LocalUserSettings`. The new strings exist in both `values/` and `values-de/`. No secrets or user data are logged.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | An older GET answer can overwrite a newer PATCH answer (out-of-order results)**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/core/settings/UserSettingsRepository.kt:134`
    - Category: Correctness
    - Problem: `refresh()` sends `api.getSettings()` outside the mutex. Afterwards it only checks `generation`, and `generation` changes only in `clear()`. `update()` holds the mutex during the PATCH and applies its answer, but it does not bump anything that `refresh()` checks. Here is how it goes wrong: a refresh starts (MainActivity `onStart`, the sign-in collector in `start()`, or the post-conflict reload) and its GET is still running. Meanwhile the user picks a theme, and the PATCH returns 200 with ETag N+1. The GET was answered from the state before the PATCH (ETag N). It then takes the lock and `accept()` applies and caches the old theme with the old ETag. The UI goes back to the old theme until the next refresh, and the next `update()` sends the stale ETag, gets a 412 and shows a misleading "changed elsewhere" conflict. Two overlapping refreshes have the same problem: the slower, older answer wins. The KDoc of `lock` (line 101) says "an older answer never overwrites a newer one", which is only true for clears.
    - Why it matters: Step 5 of the review lists out-of-order async results as a defect class. The user's own change is visibly undone and stored in the offline cache, and the stale ETag causes a spurious conflict on the next write.
    - Suggested fix: Keep one counter for every applied state (incremented in `apply()` or `accept()`, and in `clear()`). `refresh()` captures it before the GET and drops its answer if the counter moved, or uses a per-request sequence number the same way. Then fix the KDoc of `lock` to match.

- [ ] **CR-2 | The repository's concurrency guards have no tests**
    - File: `android/app/src/test/java/ch/sonensei/cockpit/core/settings/UserSettingsRepositoryTest.kt:1`
    - Category: Tests
    - Problem: `UserSettingsRepositoryTest` covers load, cache, offline, update, 412, clear and start, but nothing runs concurrently. No test checks that a GET still running when `clear()` (logout) happens is dropped (the `generation` check at line 148). That check is the privacy safeguard behind the recorded assumption "a different account never sees the previous user's labels/theme". No test checks the ordering between `refresh()` and `update()` either (CR-1), or that a server answer arriving before the cache read is not replaced by the cache (line 119).
    - Why it matters: these guards are the part of the repository most likely to regress, and CR-1 shows that such ordering bugs get through without these tests. If someone removes the generation check, the suite would still pass.
    - Suggested fix: use a suspending fake `SettingsApi` (for example a `CompletableDeferred` answer). Add tests where (a) `clear()` runs while a GET is pending, then the GET completes, and settings and store stay empty; (b) a pending GET completes after a successful `update()`, and the updated theme and ETag remain; (c) a cache read completing after a server answer does not replace it.

## P3 Low

- [ ] **CR-3 | Cold start may draw one frame in the system theme before the cached theme applies**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/core/settings/UserSettingsRepository.kt:107`
    - Category: Quality
    - Problem: the cached settings are read asynchronously (`cacheRead` on `Dispatchers.Default` via DataStore), and `settings` starts as `null`. `CockpitSettingsTheme` (`ui/theme/Theme.kt`) treats `null` as "system". So if the user chose light or dark against the system setting, the first frames at a cold start can show the system brightness and then switch, the system bars included. Whether that is visible depends on how fast the DataStore read is compared with the first frame. The reviewer did not reproduce it on a device.
    - Why it matters: a visible light/dark flash at start for users with an explicit theme. The user still ends up on the right theme.
    - Suggested fix: optional. For example, keep the content hidden (or the splash screen up) until the first cache read finished (expose a "cache read" signal), or store the theme alone somewhere that can be read synchronously.

## Review conclusion

Material issues found; fix before completing the task.

The acceptance criteria are implemented. CR-1 is a real ordering bug in the shared repository and CR-2 is the missing test for it. Both are medium priority and should be fixed before the task is completed. CR-3 is optional.
