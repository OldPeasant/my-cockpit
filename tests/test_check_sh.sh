#!/bin/bash
# Tests for the part selector of scripts/check.sh (sh scripts/check.sh [tfp|server|web|android ...]).
# Runs a copy of check.sh in a fixture tree whose part commands (tfp gate tests, server/android gradlew,
# npm) are stubs that log their calls, so no Gradle, npm or Android SDK is needed. Also checks that
# .github/workflows/ci.yml runs each part through check.sh. Run: bash tests/test_check_sh.sh
set -u
unset COCKPIT_NO_ANDROID
SRC="$(cd "$(dirname "$0")/.." && pwd)"
mkdir -p "$SRC/.tmp"
W="$(mktemp -d "$SRC/.tmp/test-check-sh.XXXXXX")"
trap 'rm -rf "$W"' EXIT
FAIL=0
ok()   { echo "  ok   $1"; }
bad()  { echo "  FAIL $1"; FAIL=1; }
check(){ if eval "$2"; then ok "$1"; else bad "$1"; fi; }

R="$W/repo"
LOG="$W/calls.log"
export CHECK_LOG="$LOG"
mkdir -p "$R/scripts" "$R/tests" "$R/server" "$R/web/node_modules" "$R/android" "$W/bin" "$W/elsewhere"
cp "$SRC/scripts/check.sh" "$R/scripts/check.sh"
cat > "$R/tests/test_quality_gate.py" <<'EOF'
import os, sys
open(os.environ["CHECK_LOG"], "a").write(" ".join(["tfp"] + sys.argv[1:]) + "\n")
EOF
for part in server android; do
  cat > "$R/$part/gradlew" <<EOF
#!/bin/sh
echo "$part \$*" >> "\$CHECK_LOG"
[ "\${STUB_FAIL:-}" = $part ] && exit 1
exit 0
EOF
  chmod +x "$R/$part/gradlew"
done
cat > "$W/bin/npm" <<'EOF'
#!/bin/sh
echo "web $*" >> "$CHECK_LOG"
[ "${STUB_FAIL:-}" = web ] && exit 1
exit 0
EOF
chmod +x "$W/bin/npm"
touch -d '2020-01-01' "$R/web/package-lock.json"
touch "$R/web/node_modules/.package-lock.json"   # dependencies up to date: no `npm ci`
export PATH="$W/bin:$PATH"

# run <args...>: runs the fixture check.sh from an unrelated directory; sets RC, OUT, ERR, CALLS
run() {
  : > "$LOG"
  (cd "$W/elsewhere" && sh "$R/scripts/check.sh" "$@" > "$W/out" 2> "$W/err")
  RC=$?
  OUT=$(cat "$W/out"); ERR=$(cat "$W/err"); CALLS=$(cat "$LOG")
}
nl() { printf '%s\n' "$@"; }
ALL=$(nl "tfp -q" "server --quiet check" "web run -s check" "android --quiet ktlintCheck testDebugUnitTest lintDebug")

echo "1. no arguments: every part, in order, as before the selector"
run
check "exit 0" "[ $RC = 0 ]"
check "tfp, server, web, android ran with the same commands" "[ \"\$CALLS\" = \"\$ALL\" ]"
check "final line has no part suffix" "[ \"\$(printf '%s\n' \"\$OUT\" | tail -n 1)\" = '== all checks passed' ]"

echo "2. no arguments with COCKPIT_NO_ANDROID=1 (.taskcheck on machines without the SDK)"
COCKPIT_NO_ANDROID=1 run
check "exit 0" "[ $RC = 0 ]"
check "tfp, server, web ran; android not" "[ \"\$CALLS\" = \"\$(nl 'tfp -q' 'server --quiet check' 'web run -s check')\" ]"
check "android skip is announced" "printf '%s' \"\$OUT\" | grep -q 'android: SKIPPED (COCKPIT_NO_ANDROID=1)'"

echo "3. a single part"
for part in tfp server web android; do
  run "$part"
  want=$(printf '%s\n' "$ALL" | grep "^$part ")
  check "$part: exit 0" "[ $RC = 0 ]"
  check "$part: only $part ran" "[ \"\$CALLS\" = \"\$want\" ]"
  check "$part: final line names the part" "[ \"\$(printf '%s\n' \"\$OUT\" | tail -n 1)\" = '== all checks passed ($part)' ]"
done

echo "4. several parts, duplicates, argument order"
run android web
check "exit 0" "[ $RC = 0 ]"
check "web then android (script order, not argument order)" \
  "[ \"\$CALLS\" = \"\$(nl 'web run -s check' 'android --quiet ktlintCheck testDebugUnitTest lintDebug')\" ]"
check "final line names the given parts" "[ \"\$(printf '%s\n' \"\$OUT\" | tail -n 1)\" = '== all checks passed (android web)' ]"
run tfp tfp
check "a repeated part runs once" "[ \"\$CALLS\" = 'tfp -q' ]"
run tfp server web android
check "all four named = all parts" "[ $RC = 0 ] && [ \"\$CALLS\" = \"\$ALL\" ]"

echo "5. android selected with COCKPIT_NO_ANDROID=1"
COCKPIT_NO_ANDROID=1 run android
check "exit 0, nothing ran" "[ $RC = 0 ] && [ -z \"\$CALLS\" ]"
check "skip is announced" "printf '%s' \"\$OUT\" | grep -q 'android: SKIPPED'"
COCKPIT_NO_ANDROID=1 run server
check "server alone is unaffected" "[ \"\$CALLS\" = 'server --quiet check' ]"

echo "6. unknown parts are rejected before anything runs"
run bogus
check "exit 2" "[ $RC = 2 ]"
check "message names the part and the valid ones" \
  "[ \"\$ERR\" = \"check.sh: unknown part 'bogus' (expected: tfp, server, web, android)\" ]"
check "nothing ran" "[ -z \"\$CALLS\" ]"
check "no success line" "! printf '%s' \"\$OUT\" | grep -q 'all checks passed'"
run server bogus
check "a valid part before an unknown one: exit 2, nothing ran" "[ $RC = 2 ] && [ -z \"\$CALLS\" ]"
run ""
check "an empty argument: exit 2" "[ $RC = 2 ] && [ -z \"\$CALLS\" ]"
run Server
check "parts are case-sensitive: exit 2" "[ $RC = 2 ]"
run all
check "'all' is not a part: exit 2" "[ $RC = 2 ]"

echo "7. a failing part fails the check and stops it"
STUB_FAIL=server run
check "no arguments: nonzero exit" "[ $RC != 0 ]"
check "no arguments: web and android did not run" "[ \"\$CALLS\" = \"\$(nl 'tfp -q' 'server --quiet check')\" ]"
check "no success line" "! printf '%s' \"\$OUT\" | grep -q 'all checks passed'"
STUB_FAIL=web run web
check "single failing part: nonzero exit, no success line" \
  "[ $RC != 0 ] && ! printf '%s' \"\$OUT\" | grep -q 'all checks passed'"
STUB_FAIL=android run android
check "android failing alone: nonzero exit" "[ $RC != 0 ]"

echo "8. web installs dependencies when the lockfile is newer"
touch -d '2020-01-01' "$R/web/node_modules/.package-lock.json"
touch "$R/web/package-lock.json"
run web
check "npm ci, then the check" "[ \"\$CALLS\" = \"\$(nl 'web ci --no-audit --no-fund' 'web run -s check')\" ]"

echo "9. ci.yml runs each part through check.sh"
CI="$SRC/.github/workflows/ci.yml"
for part in tfp server web android; do
  check "a job runs 'sh scripts/check.sh $part'" "grep -Eq '^ +run: sh scripts/check.sh $part\$' \"\$CI\""
done
check "no job runs check.sh with another argument list" \
  "[ \"\$(grep -Ec 'run: .*scripts/check.sh' \"\$CI\")\" = 4 ]"

if [ $FAIL = 0 ]; then echo "all check.sh selector tests passed"; else echo "SOME TESTS FAILED"; exit 1; fi
