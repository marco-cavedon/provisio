#!/usr/bin/env bash
# Runs the orchestrator on the host against the local Docker Compose stack.
#
# Usage (from any directory):  orchestrator/run-local.sh [extra spring-boot:run arguments]
#
# It exports from .env only the variables the orchestrator needs (REQUIRED_VARS), never the whole file:
# .env also holds credentials the orchestrator must not see. The file is parsed, not sourced, so nothing in it is
# executed. Before starting, it checks that the services the orchestrator depends on are healthy and that the
# orchestrator is not already running in Docker Compose.
set -euo pipefail

# Variables the orchestrator reads from the environment. Add a name here when application.yaml needs a new one.
REQUIRED_VARS=(MONGODB_APP_PASSWORD)

# Compose services that must be healthy before the orchestrator starts.
REQUIRED_SERVICES=(mongodb orchestration)

ORCHESTRATOR_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(dirname "$ORCHESTRATOR_DIR")"
ENV_FILE="$ROOT_DIR/.env"

fail() {
  echo "run-local: $*" >&2
  exit 1
}

# Value of a KEY=VALUE line in .env: last occurrence wins, surrounding quotes removed. Empty if absent.
read_env_value() {
  local key="$1" line value=""
  while IFS= read -r line || [[ -n "$line" ]]; do
    line="${line%$'\r'}"
    if [[ "$line" == "$key="* ]]; then
      value="${line#"$key="}"
    fi
  done < "$ENV_FILE"
  if [[ "$value" =~ ^\"(.*)\"$ || "$value" =~ ^\'(.*)\'$ ]]; then
    value="${BASH_REMATCH[1]}"
  fi
  printf '%s' "$value"
}

# 1. Configuration from .env
[[ -f "$ENV_FILE" ]] || fail ".env not found in $ROOT_DIR. Create it from .env.example (see README.md)."

for var in "${REQUIRED_VARS[@]}"; do
  value="$(read_env_value "$var")"
  [[ -n "$value" ]] || fail "$var is missing or empty in $ENV_FILE (see .env.example)."
  export "$var=$value"
done

# 2. Local stack
command -v docker > /dev/null || fail "docker not found: the orchestrator needs the local stack (docker compose up -d)."

for service in "${REQUIRED_SERVICES[@]}"; do
  health="$(docker compose --project-directory "$ROOT_DIR" ps --format '{{.Health}}' "$service" 2> /dev/null || true)"
  case "$health" in
    healthy) ;;
    starting) fail "service '$service' is still starting: retry in a few seconds." ;;
    *) fail "service '$service' is not healthy (status: ${health:-not running}). Start the stack from $ROOT_DIR: docker compose up -d" ;;
  esac
done

# Two orchestrators would compete for the same jobs, and a job could run in the container instead of in this code.
if [[ -n "$(docker compose --project-directory "$ROOT_DIR" ps --status running --quiet orchestrator 2> /dev/null || true)" ]]; then
  fail "the orchestrator is already running in Docker Compose. Stop it first: docker compose stop orchestrator"
fi

# 3. Run
echo "run-local: stack healthy, starting the orchestrator (variables from .env: ${REQUIRED_VARS[*]})"
cd "$ORCHESTRATOR_DIR"
exec ./mvnw spring-boot:run "$@"
