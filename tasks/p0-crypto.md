---
title: Secret encryption at rest
status: in_progress
owner: dell26/agent-1
deps: [p0-server-deps]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/core/crypto,server/src/test/kotlin/ch/sonensei/cockpit/core/crypto]
prio: 1
lease_until: 2026-10-20T09:06:37Z
---
## Goal
Encrypt external credentials with the master key (SPEC.md §4.1).

## Acceptance criteria
- [ ] `SecretCipher` encrypts/decrypts with AES-256-GCM (random nonce, versioned format) using `COCKPIT_MASTER_KEY` (base64, 32 bytes); startup fails with a clear message if it is missing/invalid outside tests
- [ ] `EncryptedSecret` JPA converter stores ciphertext; the type's `toString`/Jackson serialization never reveal the value (tests)
- [ ] Tests: round trip, tampered ciphertext rejected, wrong key rejected; `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.crypto.*'` passes (Postgres via Testcontainers where persistence is involved)

## Notes
- ASSUMPTION (2026-10-20 10:49+0200 by dell26/agent-1): Master key is read from property cockpit.master-key (= ${COCKPIT_MASTER_KEY:}); all test contexts get a fictitious key from server/src/test/resources/config/application.properties (outside the task's touches, needed so every SpringBootTest starts). The dev and e2e profiles have NO default key: bootRun/e2e must export COCKPIT_MASTER_KEY (documented in ARCHITECTURE.md).
- ASSUMPTION (2026-10-20 10:49+0200 by dell26/agent-1): Stored format: base64(version byte 0x01 | 12-byte random nonce | AES-256-GCM ciphertext+128-bit tag), version byte authenticated as AAD, kept in a text column. No key id / rotation yet; a new version byte allows adding it later.
- ASSUMPTION (2026-10-20 10:49+0200 by dell26/agent-1): EncryptedSecret holds the plaintext in memory (reveal()) and serializes to JSON as the constant "******" (redacted rather than throwing), toString is EncryptedSecret(******). A startup FailureAnalyzer (META-INF/spring.factories) prints the key error without a stack trace.
