# Fix Report

Date: 2026-10-20

Task: p0-crypto (SecretCipher, AES-256-GCM, master key startup check)

Scope: current task, finding CR-1 from `reviews/p0-crypto/review.md` (base 474e1eb, reviewed HEAD 7378ed1)

## Fixed findings

### CR-1 — A developer's exported `COCKPIT_MASTER_KEY` overrides the fictitious test key

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/core/crypto/CryptoConfiguration.kt`
- `server/src/main/resources/application.properties`
- `server/src/test/resources/config/application.properties`
- `server/src/test/kotlin/ch/sonensei/cockpit/core/crypto/MasterKeyTests.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/core/crypto/MasterKeyStartupTests.kt`

Problem:
The property was named `cockpit.master-key`. Spring's relaxed binding maps the OS variable `COCKPIT_MASTER_KEY`
directly onto that name, and OS variables rank above every `application.properties`. An exported variable
therefore replaced the fictitious key from `src/test/resources/config/application.properties` in every test
context. An empty or malformed export made every Spring context test fail.

Fix:
Renamed the property to `cockpit.crypto.master-key` (as approved by the parent worker). The main
`application.properties` keeps `cockpit.crypto.master-key=${COCKPIT_MASTER_KEY:}`, so production still reads the
variable, but only through this placeholder. The test config file now overrides it. `CryptoConfiguration`
(`@Value` and KDoc), the test config file, and the tests that set the property (`MasterKeyTests`,
`MasterKeyStartupTests`) use the new name. The KDoc says why the name must not relax-bind to the variable.
`docs/ARCHITECTURE.md` never names the property, and its statement that tests use a fictitious key is now
correct, so it was not changed. No `cockpit.master-key` references remain in `server/src`, `docs/` or `SPEC.md`.

Regression test added: `MasterKeyStartupTests` `an exported COCKPIT_MASTER_KEY does not override the fictitious
test key`. It starts the real `SpringApplication` with a `StandardEnvironment` whose `getSystemEnvironment()`
returns the real environment plus `COCKPIT_MASTER_KEY=AAAA` (invalid). It asserts that the context starts and
that `cockpit.crypto.master-key` resolves to the fictitious test key. Negative control: with the variable name
temporarily changed to `COCKPIT_CRYPTO_MASTER_KEY` (which does relax-bind to the new property), the test failed
as expected (1 of 4 failed). The change was reverted afterwards.

Validation:
- `cd server && ./gradlew ktlintCheck test --tests 'ch.sonensei.cockpit.core.crypto.*'` — Pass (MasterKeyStartupTests: 4 tests, 0 failures)
- Negative control `./gradlew test --tests 'ch.sonensei.cockpit.core.crypto.MasterKeyStartupTests'` with `COCKPIT_CRYPTO_MASTER_KEY` injected — Fail as expected (reverted)

## Not fixed

None.

## Validation summary

Focused tests:
- `cd server && ./gradlew ktlintCheck test --tests 'ch.sonensei.cockpit.core.crypto.*'` — BUILD SUCCESSFUL

Full test suite:
- `cd server && ./gradlew check` — BUILD SUCCESSFUL

Lint/type-check/build:
- ktlintCheck is included in the commands above — Pass

`.taskcheck`:
- Not run. The fixer's sandbox needed approval to run `sh scripts/check.sh`. The change only affects `server/`, and `./gradlew check` passed there. The parent worker runs `.taskcheck` before `done`.

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed.
