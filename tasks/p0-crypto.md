---
title: Secret encryption at rest
status: todo
owner:
deps: [p0-server-deps]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/core/crypto,server/src/test/kotlin/ch/sonensei/cockpit/core/crypto]
prio: 1
---
## Goal
Encrypt external credentials with the master key (SPEC.md §4.1).

## Acceptance criteria
- [ ] `SecretCipher` encrypts/decrypts with AES-256-GCM (random nonce, versioned format) using `COCKPIT_MASTER_KEY` (base64, 32 bytes); startup fails with a clear message if it is missing/invalid outside tests
- [ ] `EncryptedSecret` JPA converter stores ciphertext; the type's `toString`/Jackson serialization never reveal the value (tests)
- [ ] Tests: round trip, tampered ciphertext rejected, wrong key rejected; `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.crypto.*'` passes (Postgres via Testcontainers where persistence is involved)

## Notes
