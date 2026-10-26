#!/usr/bin/env bash
# Stops everything running locally: the orchestrator on the host, then the Docker Compose stack.
#
# Usage (from any directory):  orchestrator/stop-local.sh
#
# Order matters: the orchestrator is stopped first, so it does not keep retrying against a database that is gone.
# It is found by its main class, whether started by run-local.sh or by the IDE, and stopped with SIGTERM so Spring
# shuts down gracefully. The stack is then removed with `docker compose down`: containers and network go, volumes
# stay, so Camunda and MongoDB data survive. To delete the data too, run `docker compose down -v` by hand.
set -euo pipefail

# Command line of the orchestrator JVM (extended regular expression, matched by pgrep -f against the whole command
# line). It must start with a `java` executable and name the main class as a whole argument: matching the class name
# alone would also hit any process that merely mentions it, such as `grep ProvisioApplication` or a shell running
# this very pattern.
MAIN_CLASS_PATTERN='^[^ ]*java .* io\.github\.marcocavedon\.provisio\.ProvisioApplication( |$)'

# How long to wait for a graceful shutdown before giving up.
SHUTDOWN_TIMEOUT_SECONDS=30

ORCHESTRATOR_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(dirname "$ORCHESTRATOR_DIR")"

fail() {
  echo "stop-local: $*" >&2
  exit 1
}

# PIDs of the orchestrator JVMs still running, space-separated; empty if none.
orchestrator_pids() {
  pgrep -f "$MAIN_CLASS_PATTERN" | tr '\n' ' ' || true
}

command -v docker > /dev/null || fail "docker not found."

# 1. Orchestrator on the host
pids="$(orchestrator_pids)"

if [[ -z "${pids// /}" ]]; then
  echo "stop-local: orchestrator not running"
else
  echo "stop-local: stopping the orchestrator (PID ${pids% })"
  # shellcheck disable=SC2086 # word splitting of the PID list is intended
  kill -TERM $pids 2> /dev/null || true

  waited=0
  while [[ -n "$(orchestrator_pids | tr -d ' ')" ]] && (( waited < SHUTDOWN_TIMEOUT_SECONDS )); do
    sleep 1
    waited=$((waited + 1))
  done

  remaining="$(orchestrator_pids)"
  if [[ -n "${remaining// /}" ]]; then
    fail "the orchestrator (PID ${remaining% }) did not stop within ${SHUTDOWN_TIMEOUT_SECONDS}s; the stack was left running. Stop it by hand (kill -9 ${remaining% }) and run this script again."
  fi
  echo "stop-local: orchestrator stopped"
fi

# 2. Local stack (volumes are kept)
echo "stop-local: stopping the Docker Compose stack (volumes are kept)"
docker compose --project-directory "$ROOT_DIR" down
echo "stop-local: everything is stopped"
