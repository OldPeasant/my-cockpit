#!/bin/sh
# Creates deploy/.env from deploy/.env.example with a random master key and database password.
# Usage: sh deploy/gen-env.sh <domain> [--force]      e.g. sh deploy/gen-env.sh cockpit.example.org
# Refuses to overwrite an existing .env (a new master key would make stored credentials unreadable).
set -eu
cd "$(dirname "$0")"

domain=${1:-}
if [ -z "$domain" ]; then
	echo "usage: sh deploy/gen-env.sh <domain> [--force]" >&2
	exit 2
fi
# A host name such as cockpit.example.org or localhost: it goes into a sed replacement below.
case "$domain" in
	*[!A-Za-z0-9.-]* | [.-]*)
		echo "invalid domain '$domain': use a host name (letters, digits, dots, hyphens)" >&2
		echo "usage: sh deploy/gen-env.sh <domain> [--force]" >&2
		exit 2
		;;
esac
if [ -e .env ] && [ "${2:-}" != "--force" ]; then
	echo "deploy/.env already exists; not overwriting it (pass --force to replace it, keys included)" >&2
	exit 1
fi

# 32 random bytes as base64 for AES-256; hex for the password (no characters that need quoting).
rand_b64() { head -c 32 /dev/urandom | base64 | tr -d '\n'; }
rand_hex() { head -c 24 /dev/urandom | od -An -tx1 | tr -d ' \n'; }

master_key=$(rand_b64)
db_password=$(rand_hex)

umask 077
sed -e "s|^COCKPIT_DOMAIN=.*|COCKPIT_DOMAIN=$domain|" \
	-e "s|^COCKPIT_MASTER_KEY=.*|COCKPIT_MASTER_KEY=$master_key|" \
	-e "s|^POSTGRES_PASSWORD=.*|POSTGRES_PASSWORD=$db_password|" \
	.env.example > .env.tmp
mv .env.tmp .env
echo "wrote deploy/.env for $domain (mode 600). Back up COCKPIT_MASTER_KEY somewhere safe."
