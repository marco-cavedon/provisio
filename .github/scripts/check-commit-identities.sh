#!/usr/bin/env bash
# Fails if any commit reachable from HEAD carries an email outside the allow-list,
# as author, committer or Co-authored-by trailer.
#
# Allow-list rather than block-list: it catches any unexpected identity, and it keeps
# the names of other organisations out of this public repository.
set -euo pipefail

ALLOWED_PATTERNS=(
  '^cavedon\.marco@gmail\.com$'          # project owner
  '@users\.noreply\.github\.com$'        # GitHub noreply addresses, Dependabot
  '^noreply@github\.com$'                # committer of merges made on github.com
  '^noreply@anthropic\.com$'             # commits authored by Claude
)

range="${1:-HEAD}"

emails=$(
  git log "$range" --format='%ae%n%ce%n%(trailers:key=Co-authored-by,valueonly)' \
    | grep -oE '[^<> ]+@[^<> ]+' \
    | tr '[:upper:]' '[:lower:]' \
    | sort -u
)

allowed_regex=$(IFS='|'; echo "${ALLOWED_PATTERNS[*]}")
violations=$(grep -vE "$allowed_regex" <<< "$emails" || true)

if [[ -n "$violations" ]]; then
  echo "::error::Commits contain identities outside the allow-list:"
  while read -r email; do
    echo "  $email"
    git log "$range" --format='%h %s | %ae %ce %(trailers:key=Co-authored-by,valueonly,separator=%x20)' \
      | grep -iF "$email" | cut -d'|' -f1 | sed 's/^/    /' | head -5 || true
  done <<< "$violations"
  echo
  echo "Fix your git identity (git config user.email) and rewrite the affected commits before merging."
  exit 1
fi

echo "All commit identities are allowed ($(wc -l <<< "$emails") distinct addresses checked)."
