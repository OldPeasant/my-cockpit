---
title: Backup contributors: core entities
status: done
owner: dell26/agent-5
deps: [backup-core,reminders-core]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/core/backupcontrib,server/src/test/kotlin/ch/sonensei/cockpit/core/backupcontrib]
prio: 1
lease_until: 
---
## Goal
Back up the user, settings, links, reminders (SPEC.md §10.2).

## Acceptance criteria
- [x] Contributors for user (password hash, TOTP via secrets mechanism), settings, links, reminders, with readable README sections where useful
  - verified (2026-10-20 23:18+0200 by dell26/agent-5): cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.backupcontrib.*' (Testcontainers Postgres) -> CoreBackupContributorsTests 11/11 pass: user (Argon2 password hash exported, TOTP secret excluded and replaced with fresh encrypted secret + re-enrollment WARN, recovery code login after import), settings, links, reminders contributors and readable/*.md README sections (links.md, reminders.md, settings.md) verified; JaCoCo backupcontrib 100% lines
- [x] Round-trip test (`./gradlew test --tests '*BackupRoundTripTest'`) passes with this module's `BackupFixture` seeding a user, settings, links and reminders
  - verified (2026-10-20 23:18+0200 by dell26/agent-5): cd server && ./gradlew test --rerun --tests '*BackupRoundTripTest' (Testcontainers Postgres, CoreBackupFixtures seeding user+recovery codes, settings, links, reminders) -> 1/1 pass; BackupWebTests 12/12 pass

## Notes
- ASSUMPTION (2026-10-20 22:39+0200 by dell26/agent-5): The TOTP secret is not in user.json: the secrets mechanism (data/secrets.enc.json) doesn't exist yet (task backup-secrets, which this task doesn't depend on). On import the user gets a fresh random TOTP secret, so they sign in with a recovery code; backup-secrets should add the secret to secrets.enc.json and the forced re-enrollment flag.
- ASSUMPTION (2026-10-20 22:39+0200 by dell26/agent-5): Settings go to data/settings.json (own 'settings' contributor), not into user.json as the SPEC 10.2 tree suggests, because the criterion asks for separate user and settings contributors and they are separate modules.
- ASSUMPTION (2026-10-20 22:39+0200 by dell26/agent-5): Login sessions, Android devices and retired refresh tokens are not backed up (short-lived token hashes): after a restore the owner signs in again and re-pairs devices.
- ASSUMPTION (2026-10-20 22:39+0200 by dell26/agent-5): Core contributors read and write rows with JdbcTemplate instead of JPA so a restore keeps ids, created_at/updated_at and version exactly (JPA auditing/versioning would overwrite them).
- done by dell26/agent-5: `sh scripts/check.sh` passed in 54s on ce2cf9af9; criteria verified 2/2
