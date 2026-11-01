---
title: Android login: server address tolerates keyboard-inserted spaces
status: in_progress
owner: dell26/agent-1
deps: [and-login]
touches: [android/app/src/main/java/ch/sonensei/cockpit/core/network,android/app/src/main/java/ch/sonensei/cockpit/auth,android/app/src/test/java/ch/sonensei/cockpit/core/network,android/app/src/test/java/ch/sonensei/cockpit/auth]
prio: 2
lease_until: 2026-11-01T15:29:41Z
---
## Goal
On the phone, the keyboard inserted a space after each dot, so `https://my-cockpit. sonensei. ch` was rejected
with "Enter the server address as an http(s) URL, without user name, query or fragment". The owner could not see
why. Make the server address field forgiving (`core/network/ServerUrl.kt` `ServerUrlValidator`, login screen).

## Acceptance criteria
- [x] `ServerUrlValidator` removes all whitespace (incl. non-breaking and other Unicode spaces) from the typed address before parsing, so `https://my-cockpit. sonensei. ch ` is accepted as `https://my-cockpit.sonensei.ch/`
  - verified (2026-11-01 16:09+0100 by dell26/agent-1): ServerUrlTest (whitespace incl. NBSP/Unicode spaces/zero-width; 'https://my-cockpit. sonensei. ch ' -> Valid https://my-cockpit.sonensei.ch/), AuthRepositoryTest/PasskeyRepositoryTest space cases; android/gradlew -p android ktlintCheck testDebugUnitTest lintDebug createDebugUnitTestCoverageReport --no-daemon on d8207bce -> exit 0, 2407 tests, 0 failures
- [x] An address without scheme (`my-cockpit.sonensei.ch`) is accepted as `https://...`; plain `http://` keeps the existing HTTPS_REQUIRED rule
  - verified (2026-11-01 16:09+0100 by dell26/agent-1): ServerUrlTest 'an address without scheme gets https', http-with-spaces keeps HTTPS_REQUIRED, mistyped/partial scheme -> MALFORMED; AuthRepositoryTest; full android ktlintCheck testDebugUnitTest lintDebug on d8207bce -> 2407 tests, 0 failures
- [x] The server address field turns off autocorrect and auto-capitalization (`KeyboardOptions(autoCorrectEnabled = false, capitalization = None)`, keyboard type Uri), so keyboards don't insert spaces or capitals
  - verified (2026-11-01 16:09+0100 by dell26/agent-1): LoginScreenTest (Robolectric): ServerUrlKeyboardOptions = Uri, capitalization None, autoCorrectEnabled false, and the focused field's EditorInfo inputType has URI class without autocorrect/cap flags; android testDebugUnitTest on d8207bce -> pass. Not tried on a physical keyboard (no device)
- [ ] When the address is still invalid, the error shows the address as the app understood it (e.g. `"https://my-cockpit.sonensei" is not a valid server address`) so the user can see what is wrong
- [ ] All new UI strings exist in English and German (`res/values`, `res/values-de`)
- [ ] Unit tests (ViewModel, plus Compose UI tests where the project has them for similar screens) cover the criteria above; `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` passes

## Notes
Requested by the owner (2026-11-01) after trying the Android app on the phone. Out of scope: the web app.
- ASSUMPTION (2026-11-01 15:33+0100 by dell26/agent-1): Besides Unicode whitespace, zero-width characters (U+200B-200D, U+2060, U+FEFF) are also removed from the server address: they are invisible and never valid in a URL.
- ASSUMPTION (2026-11-01 15:33+0100 by dell26/agent-1): The address-quoting error is used for both server address errors (malformed and https-required); if nothing is left after removing spaces, the previous general message is shown instead of empty quotes.
- ASSUMPTION (2026-11-01 15:33+0100 by dell26/agent-1): Any scheme-like prefix (letters followed by ://) counts as a scheme, so ftp://x stays malformed instead of becoming https://ftp://x; host:port without :// gets https://.
