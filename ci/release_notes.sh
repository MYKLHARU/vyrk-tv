#!/usr/bin/env bash
# Prints the CHANGELOG section for one version: from "## <tag>" up to the next "## " heading.
# Fails when the section is missing or empty, so a release never goes out without notes.
# Usage: bash ci/release_notes.sh v0.1.0 CHANGELOG.md
set -euo pipefail

tag="${1:?usage: release_notes.sh <tag> [changelog]}"
file="${2:-CHANGELOG.md}"

notes=$(awk -v h="## $tag" '
  $0 == h || index($0, h " ") == 1 { on = 1; next }
  on && /^## / { exit }
  on { print }
' "$file" | sed -e '/./,$!d')

if [[ -z "${notes//[[:space:]]/}" ]]; then
  echo "::error::$file has no notes under '## $tag'." >&2
  exit 1
fi
printf '%s\n' "$notes"
