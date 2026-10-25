# Fix Report

Date: 2026-10-25

Task: deploy-passkey-env - Forward COCKPIT_AUTH_PASSKEY_* to the server in deploy

Scope: current task; finding CR-1 from `reviews/deploy-passkey-env/review.md` (review of commit `939dae22`, rebased as `32e2ee2d`).

## Fixed findings

### CR-1 — Documented APK hash command fails for this app's APKs (v2-only signing)

Files changed:
- `deploy/README.md`

Problem:
The README got the Android origin hash with `keytool -printcert -jarfile cockpit.apk | python3 ...`. `keytool -printcert -jarfile` reads only v1 (JAR) signatures. The app has `minSdk = 29` (`android/app/build.gradle.kts`), so AGP signs it with v2/v3 only. keytool then prints "Not a signed jar file", and the Python filter crashes with an `AttributeError` because `re.search` returns `None`.

Fix:
- The README now uses `apksigner verify --print-certs cockpit.apk` (Android SDK `build-tools/<version>/`) and says why `keytool -printcert -jarfile` does not work.
- The Python filter's regex is now `SHA-?256(?: digest)?: ([0-9A-Fa-f:]+)`. It matches both apksigner's `Signer #1 certificate SHA-256 digest: <hex>` line (no colons) and keytool's `SHA256: AA:BB:...` line. The first match is Signer #1, so a later `Source Stamp Signer ... SHA-256 digest` line is ignored.
- One added line says the same filter also reads `keytool -list -v -keystore <release.keystore> -alias <alias>`.
- Base64url encoding and padding removal are unchanged.

Validation:
- `python3 -I .tmp/fix-dpe/test_readme_cmd.py deploy/README.md .tmp/fix-dpe` — Pass. The script extracts the exact `python3 -c` filter from the README and runs it on two saved samples:
  - `.tmp/fix-dpe/apksigner.txt`: realistic `apksigner verify --print-certs` output with a META-INF WARNING line, Signer #1 DN/SHA-256/SHA-1/MD5 lines and a Source Stamp signer.
  - `.tmp/fix-dpe/keytool-list.txt`: realistic `keytool -list -v` output with SHA1/SHA256 fingerprints and a `SHA256withRSA` algorithm line.
  - Both produce `_XI0BlgGtTeqVuY9vSPSGqzzKUfSGd2e2wp8tr2uvw0`. This equals the base64url (no padding, 43 chars) of the fictitious digest, computed independently with `hashlib`/`base64`.
- The README pipeline, run verbatim with `cat <sample>` in place of the tool, gives the same value for both samples — Pass.
- No real `apksigner`/`keytool` run on a real APK or keystore:
  - The Android SDK (`~/Library/Android/sdk/build-tools`) is outside the clone, and listing it was denied.
  - No APK has been built in `android/`.
  - Creating a throwaway keystore with `keytool -genkeypair` in `.tmp/` needed approval this session did not have.
  - The sample formats follow the documented apksigner and keytool output.

## Not fixed

None.

## Validation summary

Focused tests:
- `python3 -I .tmp/fix-dpe/test_readme_cmd.py deploy/README.md .tmp/fix-dpe` — Pass (apksigner sample OK, keytool -list -v sample OK)

Full test suite:
- Not run. The change is documentation only (`deploy/README.md`), and no code, build or test file changed.

Lint/type-check/build:
- Not applicable (Markdown only)

`.taskcheck`:
- Not run (documentation-only change; the parent worker runs it before `done`)

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed.
