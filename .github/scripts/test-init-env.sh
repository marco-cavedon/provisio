#!/usr/bin/env bash
# Tests infra/init-env.sh from the repository root. Runs on Linux and macOS (bash 3.2, BSD tools).
# Uses and removes .env in the working tree; Docker-dependent cases run only where Docker is available.
set -euo pipefail

script=infra/init-env.sh
passwords="MONGOT_PASSWORD MONGODB_ADMIN_PASSWORD MONGODB_APP_PASSWORD"

fail() { echo "FAIL: $*" >&2; exit 1; }
value() { grep "^$1=" .env | tail -n 1 | cut -d= -f2-; }
mode() { stat -c %a .env 2>/dev/null || stat -f %Lp .env; }
checksum() { cksum < .env; }

[[ ! -e .env ]] || fail ".env already exists; run on a clean checkout"
trap 'rm -f .env' EXIT

echo "--- fresh clone: creates .env and generates the passwords"
out="$("$script")"
echo "$out"
for var in $passwords; do
  [[ "$(value "$var")" =~ ^[0-9a-f]{32}$ ]] || fail "$var is not a 32-character hex value"
  grep -q "Generated $var" <<< "$out" || fail "$var not reported"
  ! grep -q "$(value "$var")" <<< "$out" || fail "the value of $var was printed"
done
[[ -z "$(value LLM_API_KEY)" ]] || fail "LLM_API_KEY was filled in"
[[ "$(mode)" == "600" ]] || fail "mode is $(mode), expected 600"
[[ "$(value MONGOT_PASSWORD)" != "$(value MONGODB_APP_PASSWORD)" ]] || fail "two passwords are equal"

echo "--- second run: changes nothing"
before="$(checksum)"
out="$("$script")"
echo "$out"
[[ "$(checksum)" == "$before" ]] || fail ".env changed on the second run"
grep -q "Nothing to do" <<< "$out" || fail "second run did not report nothing to do"

echo "--- one empty password: only that one is generated"
admin="$(value MONGODB_ADMIN_PASSWORD)"
mongot="$(value MONGOT_PASSWORD)"
awk '/^MONGODB_APP_PASSWORD=/ { print "MONGODB_APP_PASSWORD=\"\""; next } { print }' .env > .env.tmp && mv .env.tmp .env
out="$("$script")"
echo "$out"
[[ "$(value MONGODB_ADMIN_PASSWORD)" == "$admin" ]] || fail "MONGODB_ADMIN_PASSWORD was overwritten"
[[ "$(value MONGOT_PASSWORD)" == "$mongot" ]] || fail "MONGOT_PASSWORD was overwritten"
[[ "$(value MONGODB_APP_PASSWORD)" =~ ^[0-9a-f]{32}$ ]] || fail "quoted empty MONGODB_APP_PASSWORD not generated"

echo "--- variable missing from an older .env: appended"
grep -v '^MONGOT_PASSWORD=' .env > .env.tmp && mv .env.tmp .env
"$script" > /dev/null
[[ "$(value MONGOT_PASSWORD)" =~ ^[0-9a-f]{32}$ ]] || fail "missing MONGOT_PASSWORD not appended"

if command -v docker > /dev/null 2>&1 && docker info > /dev/null 2>&1; then
  echo "--- empty admin password with an existing MongoDB volume: refused, nothing changed"
  volume="init-env-test-$$"
  docker volume create --label com.docker.compose.project=provisio \
    --label com.docker.compose.volume=mongodb-data "$volume" > /dev/null
  awk '/^MONGODB_ADMIN_PASSWORD=/ { print "MONGODB_ADMIN_PASSWORD="; next } { print }' .env > .env.tmp && mv .env.tmp .env
  chmod 600 .env
  before="$(checksum)"
  status=0
  "$script" 2> refusal.txt || status=$?
  docker volume rm "$volume" > /dev/null
  cat refusal.txt; rm -f refusal.txt
  [[ $status -eq 1 ]] || fail "expected exit 1, got $status"
  [[ "$(checksum)" == "$before" ]] || fail ".env changed despite the refusal"
else
  echo "--- Docker not available: refusal case skipped"
fi

echo "All init-env tests passed"
