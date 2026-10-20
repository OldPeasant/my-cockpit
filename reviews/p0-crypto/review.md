# Code Review

Date: 2026-10-20

Task: p0-crypto - Secret encryption at rest

Scope: committed task changes `git diff 474e1eb..0e13001` (2 commits). Uncommitted test-writer work in progress was not reviewed.

Requirements:
- tasks/p0-crypto.md acceptance criteria and recorded assumptions (property `cockpit.master-key`, versioned format `0x01 | nonce | ciphertext+tag` with the version byte as AAD, redacted JSON/toString, FailureAnalyzer)
- SPEC.md §4.1 (secrets at rest: AES-256-GCM, `COCKPIT_MASTER_KEY`, never plaintext, never returned by the API), §10 (backup secrets), §11 (env configuration)
- docs/ARCHITECTURE.md (stack, env variables, Kotlin conventions), docs/QUALITY.md
- AGENTS.md project notes (no secrets in logs, fictitious test data, touches)

Git range:
- Branch: main
- HEAD: 0e1300105ade72d5c1e68d0a05b29fb376e5dd71
- Base/start: 474e1eb49289d802c419d84beb306b607d0c8505
- Commits reviewed: 2 (50628ae, 0e13001)

Summary: 0 P0, 0 P1, 0 P2, 1 P3

## What was checked

- `SecretCipher`: AES/GCM/NoPadding, 32-byte key enforced, a fresh 96-bit nonce from `SecureRandom` per encryption, 128-bit tag, a version byte that is checked before decryption and authenticated as AAD, a length check before slicing, and a new `Cipher` per call, so it is thread-safe. Every failure path (bad base64, too short, unknown version, `AEADBadTagException`) maps to `SecretDecryptionException`, and none of its messages contain the plaintext or the ciphertext. I found no crypto defects.
- `MasterKey.decode`: missing/blank, non-base64 and wrong-length values give clear messages that never echo the configured value. Wiring the eager `@Bean` with `@Value("${cockpit.master-key:}")` makes startup fail. `InvalidMasterKeyFailureAnalyzer` is registered in `META-INF/spring.factories`. `MasterKeyStartupTests` checks the real `SpringApplication` output, including that an invalid key is not logged.
- `EncryptedSecret`: `toString` and `@JsonValue` are redacted (`com.fasterxml.jackson.annotation` is still the annotation package under Jackson 3). Equality by value avoids spurious Hibernate dirty updates, since encryption is non-deterministic. The converter is `autoApply`, Hibernate creates it through Spring's bean container, and it handles null in both directions.
- Tests cover the round trip (empty, unicode, long), the versioned layout, nonce freshness, tampering in the nonce, body and tag, the wrong key, unknown version, truncated and non-base64 input, the key length, the redacted JSON through the application `JsonMapper`, and persistence against Testcontainers Postgres, where the column holds ciphertext and a null stays null. Using a fixture entity outside the application package is a reasonable way to keep schema validation in other contexts intact.
- Integration: no Docker, CI or e2e start path exists yet that would now fail without the key. ARCHITECTURE.md documents the new required variable for `bootRun`. The `p0-e2e-harness` task (todo) will need to supply it, which the recorded assumption already notes.
- Scope: the edits outside `touches` (main/test `application.properties`, `spring.factories`, ARCHITECTURE.md) are small, needed, and covered by a recorded assumption.
- Evidence: `python tasks.py verify p0-crypto` shows all 3 criteria still unverified, so nothing is claimed without evidence yet. My own run of `./gradlew test --tests 'ch.sonensei.cockpit.core.crypto.*'` aborted with `NoSuchFileException .../build/test-results/test/binary/in-progress-results-generic.bin`. A concurrent Gradle test run (the test-writer) was using the same build directory, so this run proves neither a pass nor a failure. The parent worker must record its own passing run for criterion 3.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | A developer's exported `COCKPIT_MASTER_KEY` overrides the fictitious test key**
    - File: `server/src/test/resources/config/application.properties:3`, `server/src/main/resources/application.properties:19`
    - Category: Tests
    - Problem: the property is named `cockpit.master-key`. Spring's environment property source resolves it from the OS variable `COCKPIT_MASTER_KEY` through relaxed binding (`.` and `-` become `_`, uppercase), and OS variables rank above every `application.properties`, including `classpath:/config/`. A developer who follows ARCHITECTURE.md and exports `COCKPIT_MASTER_KEY` for `bootRun` therefore runs every SpringBootTest with that real key, not the fictitious one. If the exported value is empty or malformed, every Spring context test fails with the master-key error. ARCHITECTURE.md line 102 says tests use the fictitious key, which is not always true.
    - Why it matters: tests behave differently depending on the shell environment, and a real key gets used in test contexts. No test asserts on the key value, so nothing breaks today and nothing leaks.
    - Suggested fix: give the property a name that the variable does not bind to directly (for example `cockpit.crypto.master-key=${COCKPIT_MASTER_KEY:}`), so only the explicit placeholder reads the env variable and the test file wins. Alternatively, state in ARCHITECTURE.md that an exported `COCKPIT_MASTER_KEY` overrides the test key.

## Review conclusion

No material issues found. The single P3 finding does not block the task. The parent worker must still record a passing run of the criterion-3 test command, since my run could not complete because of the concurrent build.
