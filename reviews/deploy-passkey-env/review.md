# Code Review

Date: 2026-10-25

Task: deploy-passkey-env - Forward COCKPIT_AUTH_PASSKEY_* to the server in deploy

Scope: full task change `git diff c309e711..2dc672c0 -- deploy docs` (second review, final revision). Commits: `727052f9` (the originally reviewed change `939dae22`, rebased) and fix commit `2dc672c0` (CR-1, `deploy/README.md` only).

Requirements:
- `tasks/deploy-passkey-env.md` acceptance criteria and recorded assumption (RP_NAME defaults to `My Cockpit` in compose)
- `docs/ARCHITECTURE.md` environment-variable table
- `AGENTS.md` project notes (placeholders only in `.env.example`, fictitious test data)
- `server/src/main/kotlin/ch/sonensei/cockpit/core/auth/passkey/api/PasskeyRelyingParties.kt` (consumer of the variables)
- `reviews/deploy-passkey-env/fixes.md` (fix for CR-1)

Git range:
- Branch: main
- HEAD: 2dc672c02116ba8a591220f71600fb3ecdd3e9ed
- Base/start: c309e711af1498faadf41fa30512692361824e46
- Commits reviewed: 2 (727052f9, 2dc672c0)

Summary: 0 P0, 0 P1, 0 P2, 0 P3

Verification performed by the reviewer:
- First review (same compose and `.env.example` content, unchanged by the fix commit): `docker compose --env-file <sample> -f deploy/docker-compose.yml config` with the three variables set but empty showed `COCKPIT_AUTH_PASSKEY_ORIGINS: ""`, `COCKPIT_AUTH_PASSKEY_RP_ID: ""`, `COCKPIT_AUTH_PASSKEY_RP_NAME: My Cockpit` on the server service. With set values the server got them verbatim, and caddy and postgres did not receive them.
- `git show --stat 2dc672c0`: the fix changes only `deploy/README.md`, so the compose wiring, `.env.example` and the `docs/ARCHITECTURE.md` row are the same as in the first review.
- Ran the README's Python filter (`deploy/README.md:76`) with `python3 -I` on fictitious `apksigner verify --print-certs` style output. The sample had a certificate SHA-256 digest of bytes 0x01..0x20 followed by a verbose-mode `public key SHA-256 digest` line. The filter printed `AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyA`, which matches an independent base64url (no padding) encoding of the certificate digest. The first match is the certificate digest, not the public-key digest that `apksigner` prints after it with `-v`.
- The regex `SHA-?256(?: digest)?: ` needs `: ` right after the token, so keytool's `Signature algorithm name: SHA256withRSA` line cannot match. The `keytool -list -v` `SHA256: AA:BB:...` line matches, and the colons are stripped.
- No secrets or real hashes are committed. Only `REPLACE_WITH_APK_KEY_HASH` placeholders and `cockpit.example.org` are used.

## Resolution of earlier findings

- **CR-1 (P2) | Documented APK hash command fails for this app's APKs (v2-only signing)**: resolved in `2dc672c0`. `deploy/README.md:72-79` now uses `apksigner verify --print-certs` and explains that the APK is v2/v3-signed only (`minSdk = 29`, `android/app/build.gradle.kts:22`). It also documents the `keytool -list -v -keystore` alternative, and the filter handles both output formats (checked above). The command has still not been run against a real APK or keystore because no Android SDK or APK is available in the clone (`fixes.md`). The fictitious-output check covers the documented formats, so this is not a finding.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Review conclusion

No material issues found.

Both acceptance criteria are met. The compose file forwards the three variables to the server (RP_NAME defaults to `My Cockpit` as recorded in the assumption), and `.env.example` and `deploy/README.md` document them with placeholder hashes only. CR-1 is resolved.
