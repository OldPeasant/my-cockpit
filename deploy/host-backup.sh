#!/bin/sh
# Example host-level backup (SPEC.md §11): PostgreSQL dump + data dir, stored with restic.
# Complements the in-app backup zip, which is on demand only. Run it from cron/systemd on the host, e.g.
#   15 3 * * * RESTIC_REPOSITORY=sftp:backup@nas:/cockpit RESTIC_PASSWORD_FILE=/root/.restic-pw \
#              sh /opt/my-cockpit/deploy/host-backup.sh
# Needs: restic, docker compose (or podman compose), a running stack. Restore: see deploy/README.md.
# Also keep deploy/.env (COCKPIT_MASTER_KEY) somewhere safe: without it stored credentials are lost.
set -eu
cd "$(dirname "$0")"

: "${RESTIC_REPOSITORY:?set RESTIC_REPOSITORY (e.g. sftp:user@host:/path or /mnt/backup/cockpit)}"
if [ -z "${RESTIC_PASSWORD:-}" ] && [ -z "${RESTIC_PASSWORD_FILE:-}" ]; then
	echo "set RESTIC_PASSWORD or RESTIC_PASSWORD_FILE" >&2
	exit 2
fi
COMPOSE=${COMPOSE:-docker compose}

# Fixed staging path, so every restic snapshot has the same layout (cockpit.pgdump, data.tar).
staging=${COCKPIT_BACKUP_STAGING:-/var/tmp/cockpit-backup}
rm -rf "$staging"
mkdir -m 700 -p "$staging"
trap 'rm -rf "$staging"' EXIT INT TERM

# Consistent database snapshot (custom format, restore with pg_restore).
$COMPOSE -f docker-compose.yml exec -T postgres pg_dump -U cockpit -d cockpit -Fc > "$staging/cockpit.pgdump"
# Data dir (files, blobs) as a tar stream from the server container.
$COMPOSE -f docker-compose.yml exec -T server tar -C /data -cf - . > "$staging/data.tar"

restic snapshots > /dev/null 2>&1 || restic init
restic backup --tag cockpit --host cockpit "$staging"
restic forget --tag cockpit --keep-daily 7 --keep-weekly 4 --keep-monthly 12 --prune
