#!/usr/bin/env bash
# Claude Code PreToolUse hook.
# Blocks any bash command that looks like it touches a real database or a
# production host. Exit code 2 stops the tool call and shows stderr to Claude.
#
# Install at: .claude/hooks/block-prod-db.sh   (chmod +x)

set -euo pipefail

input="$(cat)"
command="$(printf '%s' "$input" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("tool_input",{}).get("command",""))' 2>/dev/null || true)"

[ -z "$command" ] && exit 0

# Lower-case copy for matching.
lower="$(printf '%s' "$command" | tr '[:upper:]' '[:lower:]')"

deny() {
  echo "Blocked by block-prod-db hook: $1" >&2
  echo "If this is really needed, run it yourself outside Claude." >&2
  exit 2
}

case "$lower" in
  *prod*|*production*|*.rds.amazonaws.com*|*staging*)
    case "$lower" in
      *psql*|*pg_dump*|*pg_restore*|*flyway*|*gradlew*flyway*)
        deny "command mentions a non-local environment"
        ;;
    esac
    ;;
esac

case "$lower" in
  *"drop table"*|*"drop database"*|*"drop schema"*|*truncate*|*"delete from"*)
    deny "destructive SQL statement"
    ;;
  *flywayclean*|*"flyway clean"*)
    deny "flyway clean wipes the whole schema"
    ;;
esac

exit 0
