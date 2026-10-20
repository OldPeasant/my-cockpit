# Code Review

Date: 2026-10-20

Task: p0-crypto - Secret encryption at rest

Scope: full task re-review of the final code, `git diff b5ceb4b..8ca99fc` (5 commits), including the CR-1 fix and the test-writer additions. The working tree was clean.

Requirements:
- tasks/p0-crypto.md acceptance criteria and recorded assumptions (versioned format `0x01 | nonce | ciphertext+tag` with the version byte as AAD, redacted JSON/toString, FailureAnalyzer, fictitious test key)
- SPEC.md §4.1 (secrets at rest: AES-256-GCM, `COCKPIT_MASTER_KEY`, never plaintext, never returned by the API), §11 (env configuration)
- docs/ARCHITECTURE.md (stack, run commands, env variables, conventions), docs/QUALITY.md
- AGENTS.md project notes (no secrets in logs, fictitious test data, touches)
- Previous review (CR-1), reviews/p0-crypto/fixes.md, reviews/p0-crypto/tests.md

Git range:
- Branch: main
- HEAD: 8ca99fc4ce32857ad6da9611a32319127f5cae7d
- Base/start: b5ceb4b77dc7cecc8031b8533dc628b4e56d9f42
- Commits reviewed: 5 (9621215, c596e4b, f19e6e4, a1649dd, 8ca99fc)

Summary: 0 P0, 0 P1, 0 P2, 1 P3

## What was checked

- CR-1 (previous review) is resolved. The property is now `cockpit.crypto.master-key`, which the OS variable `COCKPIT_MASTER_KEY` does not relax-bind to. The variable is read only through the placeholder `cockpit.crypto.master-key=${COCKPIT_MASTER_KEY:}` in `server/src/main/resources/application.properties:19`, so `src/test/resources/config/application.properties` wins in tests. `CryptoConfiguration` (`@Value` and KDoc), the test config and all tests use the new name. No reference to the old `cockpit.master-key` remains in code, docs or SPEC.md. The regression test `MasterKeyStartupTests` "an exported COCKPIT_MASTER_KEY does not override the fictitious test key" injects an invalid `COCKPIT_MASTER_KEY` into the system environment and asserts that the test key is used. The new test "outside tests the master key is read from the COCKPIT_MASTER_KEY variable" uses only the main `application.properties` (the test classpath has no root `application.properties`, so this is the main file) and proves that production still reads the variable. The ARCHITECTURE.md statement that tests use a fictitious key is now accurate.
- AC1: `SecretCipher` uses AES/GCM/NoPadding, enforces a 32-byte key, creates a fresh 96-bit `SecureRandom` nonce per encryption, uses a 128-bit tag, and writes the versioned layout. The version byte is checked before decryption and authenticated as AAD. The length is checked before slicing, and every call gets a new `Cipher` instance, so the class is thread-safe. All failure paths map to `SecretDecryptionException` without the value. `MasterKey.decode` gives clear messages for a missing, non-base64 or wrong-length key and never echoes the value. The eager bean plus the `spring.factories` FailureAnalyzer make startup fail with a short message. The real `SpringApplication` tests check this, including that an invalid key is not logged.
- AC2: `EncryptedSecretConverter` is `autoApply`, handles null in both directions and stores ciphertext. Testcontainers Postgres confirms the column holds ciphertext, which reads back to the plaintext. `toString` and `@JsonValue` are redacted. This is tested both with a plain `JsonMapper` and with the application's mapper.
- AC3: tests cover the round trip, tampering (nonce, body and tag, also through the database), the wrong key (cipher, converter and database), unknown version, truncated input, non-base64 input and the key length. I ran `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.crypto.*'` at 8ca99fc: BUILD SUCCESSFUL, and the `:test` task actually executed. It ran 31 tests with 0 failures, 0 errors and 0 skipped (EncryptedSecretConverterTests 4, SecretCipherTests 7, MasterKeyTests 7, EncryptedSecretTests 4, EncryptedSecretPersistenceTests 4, MasterKeyStartupTests 5).
- Security: no real keys or credentials are committed. The test key is documented as fictitious, and the test data uses `-fictitious` values and `example.test`. No logging of key or secret values was added.
- Integration: no Dockerfile, compose file or CI workflow exists yet that would now fail without the key. ARCHITECTURE.md documents the required variable for `bootRun`, and the e2e profile gap is covered by the recorded assumption.
- Scope: edits outside `touches` are main/test `application.properties`, `META-INF/spring.factories` and ARCHITECTURE.md. They are small and necessary.
- Coverage (tests.md): 100% instructions, branches, methods and lines for `ch.sonensei.cockpit.core.crypto`.
- Evidence: `python tasks.py verify p0-crypto` lists all 3 criteria as still unverified. Nothing is claimed without evidence. The parent worker must still record evidence for each criterion.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-2 | Recorded assumption still names the old property `cockpit.master-key`**
    - File: `tasks/p0-crypto.md:19`
    - Category: Quality
    - Problem: the first ASSUMPTION note says the master key is read from property `cockpit.master-key (= ${COCKPIT_MASTER_KEY:})`. After the CR-1 fix, the property is `cockpit.crypto.master-key`, and the note does not say why the name must not relax-bind to the variable.
    - Why it matters: humans review assumptions through `tasks.py assumptions`, so they would confirm an outdated configuration contract.
    - Suggested fix: the parent worker records a corrected assumption with `python tasks.py assume p0-crypto "..."`. It should name `cockpit.crypto.master-key` and give the reason. The task file must not be edited by hand.

## Review conclusion

No material issues found. CR-1 is resolved. The single P3 finding concerns task metadata, not code, and does not block the task. The parent worker must still record criterion evidence with `tasks.py verify`.
