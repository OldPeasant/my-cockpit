---
title: Settings: backup and restore
status: in_progress
owner: dell26/agent-3
deps: [p0-web-settings,backup-secrets]
touches: [web/src/app/features/settings/backup]
prio: 1
lease_until: 2026-10-21T07:53:59Z
---
## Goal
Backup UI (SPEC.md §10.1).

## Acceptance criteria
- [~] Create backup with optional passphrase, progress (SSE), download; restore: upload, show validation report, confirm with typed confirmation, progress, then forced re-login
  - skipped (2026-10-21 09:24+0200 by dell26/agent-3): Real-browser run against the server not possible: starting the e2e docker stack (npm run e2e) needs an approval this unattended session does not have. Covered instead by Vitest component tests with HttpTestingController + fake SSE: start with/without passphrase, progress refetch on BACKUP events and resync, download, upload->report, typed confirmation, indeterminate restore progress, checkSession(true)+navigate /login
- [x] Vitest component/service tests cover report rendering and confirmation gate; `cd web && npm run check` passes
  - verified (2026-10-21 09:23+0200 by dell26/agent-3): cd web && npm run check at 36f90acd -> api:check, lint (all files pass), e2e:typecheck, test:ci 93 files/1809 tests passed incl. backup-settings.spec.ts (report rendering: facts/sorted counts/warnings/problems/secrets; confirmation gate: disabled until exact RESTORE, submit blocked) and backup.service.spec.ts, ng build ok; sh scripts/check.sh -> all checks passed

## Notes
- ASSUMPTION (2026-10-21 09:00+0200 by dell26/agent-3): Typed confirmation word is the localized string RESTORE (trimmed, case-sensitive); restore progress is an indeterminate bar because POST /restore/{id}/confirm is synchronous (no SSE progress for restore)
- ASSUMPTION (2026-10-21 09:00+0200 by dell26/agent-3): 422 on confirm shows a generic 'nothing changed, check passphrase' message: ApiError drops the ProblemDetail 'problems' extension and core/http is outside this task's touches
- ASSUMPTION (2026-10-21 09:00+0200 by dell26/agent-3): The last started backup id is kept in the root BackupService (tab memory only) so progress/download reappear when returning to the section; the server has no list endpoint
- ASSUMPTION (2026-10-21 09:01+0200 by dell26/agent-3): Edited settings.routes.ts (+spec) outside touches to swap the backup placeholder for the real section, as the other settings section tasks did
- ASSUMPTION (2026-10-21 09:24+0200 by dell26/agent-3): Criterion 1 not verified in a real browser against the stack (e2e stack start denied); assumed the server endpoints behave as documented in api/openapi.yaml (BACKUP change events carry the job id, confirm ends all sessions). No committed Playwright spec: a restore replaces the sessions of the shared e2e account and would break parallel specs without a playwright.config project change
