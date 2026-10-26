# Runbook: first deploy and Android install (2026-10-27)

Step-by-step for the owner: put the web app on the server so that later updates are one command, and install the
Android app. Background and all settings: `deploy/README.md`. Board tasks this completes: `h-deploy-p0`,
`h-android-device` (and later `h-prod-p*`).

Assumed (adapt where yours differs):
- a Linux server with **Docker Engine + Compose v2**, at least 2 vCPU and **4 GB RAM** (the image is built on the
  server; Gradle and the Angular build need the memory). Podman works too: replace `docker compose` with
  `podman-compose` and see "Podman" at the end;
- a **public DNS name** for it (here `cockpit.example.org`), ports **80 and 443** reachable from the internet;
- the Android app is signed with **one release key** that you create once (step 2).

Time: about 1.5 h, most of it waiting for builds.

## How updates will work (the idea)

- The server has a Git checkout of the public repo in `/opt/my-cockpit`, always on a **fixed commit**, never on a
  moving branch. `main` only ever holds finished tasks, but you choose *when* you take them.
- You deploy a commit whose **CI run is green**, and tag it `prod-YYYY-MM-DD` so you can see what runs where.
- One script, `/usr/local/bin/cockpit-update <tag>`, does every update the same way: database dump first, check out
  the tag, build an image tagged with the commit, restart, wait for health, log the change.
- Database migrations (Flyway) only go forward. A rollback is therefore "restore the dump taken before the update,
  then deploy the previous tag", and the script prints exactly that.
- Secrets live only in `deploy/.env` on the server (Git-ignored) and in your password manager.

---

## 1. Before you start (laptop, 10 min)

1. DNS: an `A` (and, if the server has IPv6, `AAAA`) record `cockpit.example.org` → server IP. Check:
   `dig +short cockpit.example.org`.
2. Password manager: create an entry "Cockpit production" for the values you will collect below
   (`COCKPIT_MASTER_KEY`, `POSTGRES_PASSWORD`, keystore passwords, TOTP recovery codes).
3. Pick the commit to deploy: the newest `main` commit with a green CI run.
   ```sh
   gh run list -w ci.yml -b main -L 5          # newest first; take a 'success' row
   git fetch origin && git log --oneline -1 <sha-of-that-run>
   git tag prod-2026-10-27 <sha> && git push origin prod-2026-10-27
   ```

## 2. Android release key (laptop, 15 min, once in the app's life)

Android installs an update only if it is signed with the same key as the installed app. CI without a key signs with a
fresh debug key on every run, so those APKs can't update each other: create the release key **before** the first
install.

```sh
mkdir -p ~/secure && cd ~/secure
keytool -genkeypair -v -keystore cockpit-release.jks -alias cockpit \
  -keyalg RSA -keysize 4096 -validity 10000 -dname "CN=My Cockpit"
# choose a store password; the key password may be the same
```

- Put `cockpit-release.jks` (as an attachment) and both passwords into the password manager. Losing the key means
  uninstalling the app (losing captures not yet synced) to install a newly signed one.
- Give CI the key, so every CI run builds an installable update:
  ```sh
  base64 -w0 cockpit-release.jks | gh secret set ANDROID_KEYSTORE_BASE64 -R OldPeasant/my-cockpit
  gh secret set ANDROID_KEYSTORE_PASSWORD -R OldPeasant/my-cockpit     # prompts for the value
  gh secret set ANDROID_KEY_ALIAS -R OldPeasant/my-cockpit -b cockpit
  gh secret set ANDROID_KEY_PASSWORD -R OldPeasant/my-cockpit
  ```
- Re-run the CI run of the commit you tagged, so its APK is built with the key (reruns use the current secrets):
  `gh run rerun <run-id> -R OldPeasant/my-cockpit`. Wait for "Android release
  APK" to pass, then fetch it:
  ```sh
  mkdir -p ~/cockpit-apk && cd ~/cockpit-apk && rm -f cockpit.apk output-metadata.json
  gh run download <run-id> -R OldPeasant/my-cockpit -n cockpit-android
  apksigner verify --print-certs cockpit.apk | grep 'SHA-256 digest'   # ~/Android/Sdk/build-tools/<v>/apksigner
  ```
  Note the SHA-256 digest: it goes into the server's `.env` for passkeys in the app (step 4).

Without CI (fallback): build locally with the same key, using a version code above every CI run number so later CI
APKs still update it:
```sh
cd ~/work/my-cockpit/android
COCKPIT_KEYSTORE_FILE=~/secure/cockpit-release.jks COCKPIT_KEY_ALIAS=cockpit \
COCKPIT_KEYSTORE_PASSWORD=... COCKPIT_KEY_PASSWORD=... \
./gradlew assembleRelease -PcockpitVersionCode=$(gh run list -w ci.yml -L1 --json number -q '.[0].number')
# app/build/outputs/apk/release/app-release.apk (rename to cockpit.apk) + output-metadata.json
```

## 3. Prepare the server (20 min)

As root (or with sudo):

```sh
# Docker (Debian/Ubuntu; other distros: docs.docker.com/engine/install)
curl -fsSL https://get.docker.com | sh
docker compose version                      # v2.x

# Firewall: only SSH, 80, 443 (ufw example)
ufw allow OpenSSH && ufw allow 80/tcp && ufw allow 443/tcp && ufw allow 443/udp && ufw enable

# Code
git clone https://github.com/OldPeasant/my-cockpit.git /opt/my-cockpit
cd /opt/my-cockpit && git checkout --detach prod-2026-10-27

# Configuration (refuses to overwrite an existing .env)
sh deploy/gen-env.sh cockpit.example.org
cat deploy/.env
```

In `deploy/.env`:
- copy `COCKPIT_MASTER_KEY` and `POSTGRES_PASSWORD` into the password manager **now**. Without the master key no
  backup can decrypt stored mail passwords and OAuth tokens;
- optional: `COCKPIT_PUSH_SUBJECT=mailto:<your address>` (contact for the browsers' push services);
- leave `GOOGLE_*` empty for now (Google later: `docs/oauth-setup.md`, task `h-oauth-apps`), `MICROSOFT_ENABLED=false`.

Install the update script (also used for the first deploy):

```sh
cat > /usr/local/bin/cockpit-update <<'EOF'
#!/bin/sh
# Deploys a tag or commit of My Cockpit: dump the DB, check out, build, restart, wait until healthy.
# Usage: cockpit-update <tag-or-commit>        e.g. cockpit-update prod-2026-11-02
set -eu
ref=${1:?usage: cockpit-update <tag-or-commit>}
dir=/opt/my-cockpit
backups=/var/backups/cockpit
cd "$dir"
compose="docker compose -f deploy/docker-compose.yml"
domain=$(sed -n 's/^COCKPIT_DOMAIN=//p' deploy/.env)
port=$(sed -n 's/^COCKPIT_HTTPS_PORT=//p' deploy/.env); port=${port:-443}
mkdir -p "$backups"; chmod 700 "$backups"

prev=$(git rev-parse --short HEAD)
stamp=$(date +%Y%m%d-%H%M%S)
if [ -n "$($compose ps -q postgres 2>/dev/null)" ]; then
	dump="$backups/pre-update-$stamp-$prev.pgdump"
	$compose exec -T postgres pg_dump -U cockpit -Fc cockpit > "$dump"
	echo "database dumped to $dump"
fi

git fetch --quiet --tags origin
git checkout --quiet --detach "$ref"
sha=$(git rev-parse --short HEAD)
# The image name goes into .env, so every later `docker compose ...` (restart, logs, backup) uses this version.
image="localhost/cockpit-server:$sha"
sed -i '/^COCKPIT_IMAGE=/d' deploy/.env && echo "COCKPIT_IMAGE=$image" >> deploy/.env
$compose build server
$compose up -d

printf 'waiting for %s to become healthy ' "$domain"
for _ in $(seq 1 90); do
	if curl -fsS --resolve "$domain:$port:127.0.0.1" "https://$domain:$port/actuator/health" 2>/dev/null | grep -q '"UP"'; then
		echo " UP"
		echo "$stamp $prev -> $sha ($ref)" >> "$backups/deploy.log"
		docker image prune -f > /dev/null
		exit 0
	fi
	printf .; sleep 2
done
echo " NOT healthy after 3 min. Logs: $compose logs --tail 200 server"
echo "Rollback: cockpit-rollback $prev ${dump:-<no dump: first deploy>}"
exit 1
EOF
chmod 755 /usr/local/bin/cockpit-update
```

And the matching rollback (restores the dump taken before the update, then deploys the previous commit):

```sh
cat > /usr/local/bin/cockpit-rollback <<'EOF'
#!/bin/sh
# Usage: cockpit-rollback <previous-commit> <pre-update dump>   (both printed by cockpit-update)
set -eu
prev=${1:?usage: cockpit-rollback <previous-commit> <dump>}; dump=${2:?dump file missing}
cd /opt/my-cockpit
compose="docker compose -f deploy/docker-compose.yml"
$compose stop server
$compose exec -T postgres pg_restore -U cockpit -d cockpit --clean --if-exists < "$dump"
git checkout --quiet --detach "$prev"
sed -i '/^COCKPIT_IMAGE=/d' deploy/.env && echo "COCKPIT_IMAGE=localhost/cockpit-server:$prev" >> deploy/.env
$compose up -d --build
EOF
chmod 755 /usr/local/bin/cockpit-rollback
```

## 4. First deploy (20-30 min, mostly the build)

```sh
cockpit-update prod-2026-10-27
```

- The first build downloads npm and Gradle dependencies (10-20 min). Later builds reuse the cache.
- Caddy gets the Let's Encrypt certificate on the first HTTPS request; `docker compose -f deploy/docker-compose.yml
  logs caddy` shows `certificate obtained successfully`.
- From the laptop: `curl https://cockpit.example.org/actuator/health` → `{"status":"UP",...}`.

**Do the setup immediately** (the first visitor of a fresh install creates the owner account; once done, setup is
closed for good): open `https://cockpit.example.org` → setup → user name and password → scan the TOTP QR code →
store the recovery codes in the password manager → log out → log in with password + TOTP.

Optional, in the web app: Settings → Security → add a passkey for this browser.

Then put the APK on the server (from the laptop, `~/cockpit-apk`):

```sh
scp cockpit.apk output-metadata.json root@cockpit.example.org:/tmp/
ssh root@cockpit.example.org '
  cd /opt/my-cockpit && c="docker compose -f deploy/docker-compose.yml" &&
  $c exec server mkdir -p /data/android &&
  $c cp /tmp/cockpit.apk server:/data/android/cockpit.apk &&
  $c cp /tmp/output-metadata.json server:/data/android/output-metadata.json && rm /tmp/cockpit.apk /tmp/output-metadata.json'
```

Passkeys in the Android app: add the SHA-256 digest from step 2 to `deploy/.env`
(`COCKPIT_AUTH_PASSKEY_ANDROID_FINGERPRINTS=AB:CD:...`), then
`cd /opt/my-cockpit && docker compose -f deploy/docker-compose.yml up -d server` and check
`curl https://cockpit.example.org/.well-known/assetlinks.json` lists `ch.sonensei.cockpit`. (`cockpit-update` wrote
the running image into `deploy/.env` as `COCKPIT_IMAGE`, so this restart keeps the deployed version.)

## 5. Host backup (15 min)

The in-app backup (Settings → Backup) is on demand. Add a nightly host backup with restic (`deploy/host-backup.sh`):

```sh
apt install restic                          # or your distro's package
echo '<a long random password>' > /root/.restic-pw && chmod 600 /root/.restic-pw   # also into the password manager
export RESTIC_REPOSITORY=sftp:backup@nas:/cockpit RESTIC_PASSWORD_FILE=/root/.restic-pw
sh /opt/my-cockpit/deploy/host-backup.sh    # first run by hand; creates the restic repository if needed
# nightly at 03:15
echo '15 3 * * * root RESTIC_REPOSITORY=sftp:backup@nas:/cockpit RESTIC_PASSWORD_FILE=/root/.restic-pw sh /opt/my-cockpit/deploy/host-backup.sh' > /etc/cron.d/cockpit-backup
```

Also keep a copy of `/opt/my-cockpit/deploy/.env` in the password manager (done in step 3). Restore: `deploy/README.md`
"Host backup".

## 6. Install the Android app (20 min)

1. On the phone, in the browser: `https://cockpit.example.org`, log in, then open
   `https://cockpit.example.org/download/android` and install `cockpit.apk` (allow "install unknown apps" for the
   browser once).
2. Open My Cockpit → server URL `https://cockpit.example.org` → log in (password + TOTP, or a passkey once step 4's
   fingerprint is set). Allow notifications and, when asked, exact alarms (reminders).
3. Push (optional but recommended): install **ntfy** (F-Droid or Play Store) as UnifiedPush distributor, then close
   and reopen My Cockpit: it registers itself at app start. Without a distributor the app falls back to periodic sync.
4. Run the phase 3 checks (`h-android-device`):
   - share a link/text from another app to My Cockpit → appears in the Inbox;
   - add the capture widget to the home screen and the quick-settings tile; capture from both;
   - airplane mode → capture two items → airplane mode off → both arrive in the web Inbox;
   - a task with a reminder in 2 minutes → the notification arrives (also with the app closed).

## 7. Record it on the board (laptop, 5 min)

```sh
cd ~/work/my-cockpit && git pull
TASK_OWNER="Peter Gürtler" python tasks.py claim h-deploy-p0 --force
python tasks.py verify h-deploy-p0 1 "cockpit-update prod-2026-10-27 on <server> -> health UP, valid Let's Encrypt cert"
python tasks.py verify h-deploy-p0 2 "setup + TOTP login on https://cockpit.example.org -> OK"
python tasks.py done h-deploy-p0
```
Same for `h-android-device` once step 6 passed. Anything that failed: write it down as a task
(`python tasks.py new ...`) so the agents fix it.

---

## Later: every update (5 min + build)

On the laptop: choose a green commit, tag it, push the tag.

```sh
gh run list -w ci.yml -b main -L 5
git tag prod-2026-11-02 <sha> && git push origin prod-2026-11-02
```

On the server:

```sh
cockpit-update prod-2026-11-02
cat /var/backups/cockpit/deploy.log         # what ran when
```

If it prints "NOT healthy": read the logs, then `cockpit-rollback <prev> <dump>` exactly as printed. Delete old
dumps now and then (`ls -t /var/backups/cockpit/*.pgdump | tail -n +11 | xargs -r rm`).

New app version: download the `cockpit-android` artifact of the same CI run (step 2) and copy it to the server
(step 4). On the phone open `/download/android` again and install over the old app; data stays.

## Troubleshooting

| Symptom | Cause / fix |
|---|---|
| No certificate, Caddy logs `challenge failed` | DNS not pointing here yet, or port 80 blocked. Fix, then `docker compose -f deploy/docker-compose.yml restart caddy` |
| Blank page, browser console shows a CSP error with a `sha256-...` hash | Angular changed its inline script: put the hash from the console into `script-src` in `deploy/Caddyfile` and file a task, so the repo gets it too |
| Build killed / `exit code 137` | Not enough RAM: add swap (`fallocate -l 4G /swapfile && chmod 600 /swapfile && mkswap /swapfile && swapon /swapfile`) or build on the laptop (below) |
| APK "App not installed" over an older version | Signed with another key (debug vs release). Uninstall the old app once; from now on only release-signed APKs |
| App can't log in with a passkey | Fingerprint missing or wrong in `.env`, or `assetlinks.json` not reachable; see `deploy/README.md` "Passkeys" |

**Building on the laptop instead** (small server): `podman build -t localhost/cockpit-server:<sha> .` in a checkout
of the tag, then `podman save localhost/cockpit-server:<sha> | ssh root@server docker load`, and on the server run
the steps of `cockpit-update` without `build` (`git checkout`, `COCKPIT_IMAGE=... docker compose ... up -d`).

**Podman** instead of Docker: `podman-compose -f deploy/docker-compose.yml ...` everywhere (also inside the two
scripts). Rootless Podman can't bind ports below 1024: run it as root, or set `net.ipv4.ip_unprivileged_port_start=80`
via sysctl.
