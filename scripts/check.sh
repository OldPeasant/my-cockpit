#!/bin/sh
# Fast project check (aim < 2 min): lint + unit tests of every part, plus the TFP completion-gate tests.
# `.taskcheck` runs it on every `tasks.py done`. Slow suites (Testcontainers integration, e2e, image
# builds) are separate commands, see docs/ARCHITECTURE.md "Testing".
# Usage: sh scripts/check.sh [tfp|server|web|android ...]. Without arguments every part runs; CI runs
# one part per job (.github/workflows/ci.yml).
set -eu
cd "$(dirname "$0")/.."
: "${ANDROID_HOME:=$HOME/Android/Sdk}"
export ANDROID_HOME
PY=$(command -v python3 || command -v python)
step() { printf '\n== %s\n' "$*"; }

for part in "$@"; do
  case "$part" in
    tfp | server | web | android) ;;
    *)
      echo "check.sh: unknown part '$part' (expected: tfp, server, web, android)" >&2
      exit 2
      ;;
  esac
done
PARTS=${*:-tfp server web android}
selected() { case " $PARTS " in *" $1 "*) return 0 ;; esac; return 1; }

if selected tfp; then
  step "tfp: completion gate tests"
  "$PY" tests/test_quality_gate.py -q
fi

if selected server; then
  step "server: ktlint + tests"
  (cd server && ./gradlew --quiet check)
fi

if selected web; then
  step "web: eslint + tests + build"
  (cd web && { [ node_modules/.package-lock.json -nt package-lock.json ] || npm ci --no-audit --no-fund; } \
    && npm run -s check)
fi

if selected android; then
  if [ "${COCKPIT_NO_ANDROID:-}" = 1 ]; then
    # Machines without an Android SDK; they don't claim Android tasks either (TASK_SKIP_TOUCHES=android).
    step "android: SKIPPED (COCKPIT_NO_ANDROID=1), checked on machines with the Android SDK"
  else
    step "android: ktlint + unit tests + lint"
    (cd android && ./gradlew --quiet ktlintCheck testDebugUnitTest lintDebug)
  fi
fi

step "all checks passed${*:+ ($*)}"
