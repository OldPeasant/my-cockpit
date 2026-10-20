#!/bin/sh
# Fast project check (aim < 2 min): lint + unit tests of every part, plus the TFP completion-gate tests.
# `.taskcheck` runs it on every `tasks.py done`. Slow suites (Testcontainers integration, e2e, image
# builds) are separate commands, see docs/ARCHITECTURE.md "Testing".
set -eu
cd "$(dirname "$0")/.."
: "${ANDROID_HOME:=$HOME/Android/Sdk}"
export ANDROID_HOME
PY=$(command -v python3 || command -v python)
step() { printf '\n== %s\n' "$*"; }

step "tfp: completion gate tests"
"$PY" tests/test_quality_gate.py -q

step "server: ktlint + tests"
(cd server && ./gradlew --quiet check)

step "web: eslint + tests + build"
(cd web && { [ node_modules/.package-lock.json -nt package-lock.json ] || npm ci --no-audit --no-fund; } \
  && npm run -s check)

if [ "${COCKPIT_NO_ANDROID:-}" = 1 ]; then
  # Machines without an Android SDK; they don't claim Android tasks either (TASK_SKIP_TOUCHES=android).
  step "android: SKIPPED (COCKPIT_NO_ANDROID=1), checked on machines with the Android SDK"
else
  step "android: ktlint + unit tests + lint"
  (cd android && ./gradlew --quiet ktlintCheck testDebugUnitTest lintDebug)
fi

step "all checks passed"
