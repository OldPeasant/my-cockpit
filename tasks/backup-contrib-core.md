---
title: Backup contributors: core entities
status: in_progress
owner: dell26/agent-5
deps: [backup-core,reminders-core]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/core/backupcontrib,server/src/test/kotlin/ch/sonensei/cockpit/core/backupcontrib]
prio: 1
lease_until: 2026-10-20T20:56:31Z
---
## Goal
Back up the user, settings, links, reminders (SPEC.md §10.2).

## Acceptance criteria
- [ ] Contributors for user (password hash, TOTP via secrets mechanism), settings, links, reminders, with readable README sections where useful
- [ ] Round-trip test (`./gradlew test --tests '*BackupRoundTripTest'`) passes with this module's `BackupFixture` seeding a user, settings, links and reminders

## Notes
- ASSUMPTION (2026-10-20 22:39+0200 by dell26/agent-5): The TOTP secret is not in user.json: the secrets mechanism (data/secrets.enc.json) doesn't exist yet (task backup-secrets, which this task doesn't depend on). On import the user gets a fresh random TOTP secret, so they sign in with a recovery code; backup-secrets should add the secret to secrets.enc.json and the forced re-enrollment flag.
