#!/usr/bin/env bash
# Prepares .env for the local stack: creates it from .env.example if missing, and fills in the local-only MongoDB
# passwords that are still empty with random values.
#
# Usage (from any directory):  infra/init-env.sh
#
# Safe to run again: it never overwrites a value, and it prints the names of the variables it fills in, never the
# values. External API keys (LLM_API_KEY, VOYAGE_API_KEY) are left alone: they come from accounts only you can create.
#
# The one password that cannot change on existing data is MONGODB_ADMIN_PASSWORD: mongodb-init authenticates as
# admin with it, while it updates the passwords of the other users at every start (infra/mongodb/init). So the script
# refuses to generate it when a MongoDB data volume already exists.
#
# Portable to the bash 3.2 that macOS ships, and to BSD and GNU tools alike.
set -euo pipefail
umask 077   # files created here are readable by the owner only

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
env_file="$root/.env"
example_file="$root/.env.example"
generated_vars="MONGOT_PASSWORD MONGODB_ADMIN_PASSWORD MONGODB_APP_PASSWORD"
compose_project="${COMPOSE_PROJECT_NAME:-provisio}"

random_hex() {
  if command -v openssl >/dev/null 2>&1; then
    openssl rand -hex 16
  else
    od -An -N16 -tx1 /dev/urandom | tr -d ' \n'
  fi
}

# Value of a variable in a dotenv file, the last assignment winning as in Compose; empty if the variable is absent.
# Surrounding quotes are ignored, so VAR="" counts as empty.
value_in() {
  awk -v key="$1" -v q="'" '
    index($0, key "=") == 1 { value = substr($0, length(key) + 2) }
    END {
      first = substr(value, 1, 1)
      if (length(value) >= 2 && (first == "\"" || first == q) && substr(value, length(value)) == first)
        value = substr(value, 2, length(value) - 2)
      print value
    }' "$2"
}

# Sets every assignment of a variable in a file to a value, or appends one if the variable is absent.
set_value_in() {
  local key="$1" value="$2" file="$3" tmp
  tmp="$(mktemp "$file.XXXXXX")"
  awk -v key="$key" -v value="$value" '
    index($0, key "=") == 1 { print key "=" value; found = 1; next }
    { print }
    END { if (!found) print key "=" value }' "$file" > "$tmp"
  mv "$tmp" "$file"
}

# 0 if the Compose project has a MongoDB data volume, 1 if not, 2 if Docker cannot tell.
mongodb_volume_status() {
  local volumes
  command -v docker >/dev/null 2>&1 || return 2
  volumes="$(docker volume ls --quiet \
    --filter "label=com.docker.compose.project=$compose_project" \
    --filter "label=com.docker.compose.volume=mongodb-data" 2>/dev/null)" || return 2
  [[ -n "$volumes" ]] && return 0
  return 1
}

# 1. Which variables are empty, reading .env if it exists, otherwise the template it will be created from
source_file="$env_file"
[[ -f "$env_file" ]] || source_file="$example_file"
to_generate=""
for var in $generated_vars; do
  if [[ -z "$(value_in "$var" "$source_file")" ]]; then
    to_generate="$to_generate $var"
  fi
done

# 2. Refuse a new admin password on existing data, before writing anything
case " $to_generate " in
  *" MONGODB_ADMIN_PASSWORD "*)
    status=0
    mongodb_volume_status || status=$?
    if [[ $status -eq 0 ]]; then
      cat >&2 <<EOF
infra/init-env.sh: MONGODB_ADMIN_PASSWORD is empty, but the MongoDB data volume already exists.
Its admin user keeps the password it was created with, so a new one would lock mongodb-init out. Either:
  - restore the .env (or just MONGODB_ADMIN_PASSWORD) used when the volume was created, or
  - delete the local data and start over (this removes every Provisio volume), then run this script again.
    Compose refuses any command while a required password is empty, so give it placeholders for this one:
      MONGOT_PASSWORD=- MONGODB_ADMIN_PASSWORD=- MONGODB_APP_PASSWORD=- docker compose down -v
Nothing was changed.
EOF
      exit 1
    elif [[ $status -eq 2 ]]; then
      echo "infra/init-env.sh: warning: Docker is not available, so existing MongoDB data could not be checked." >&2
    fi
    ;;
esac

# 3. Create .env and fill in the empty passwords
if [[ ! -f "$env_file" ]]; then
  cp "$example_file" "$env_file"
  chmod 600 "$env_file"
  echo "Created .env from .env.example"
fi
for var in $to_generate; do
  set_value_in "$var" "$(random_hex)" "$env_file"
  chmod 600 "$env_file"
  echo "Generated $var"
done
[[ -n "$to_generate" ]] || echo "Nothing to do: the MongoDB passwords in .env are already set"

if [[ -z "$(value_in LLM_API_KEY "$env_file")" ]]; then
  echo "LLM_API_KEY is empty: fine until the agent process exists (see .env.example)"
fi
