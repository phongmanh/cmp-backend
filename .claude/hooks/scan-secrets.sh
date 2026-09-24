#!/usr/bin/env bash
# Claude Code Stop hook.
# Looks at everything changed in the working tree and warns loudly if something
# that looks like a secret was written into the code.
#
# Install at: .claude/hooks/scan-secrets.sh   (chmod +x)

set -uo pipefail

files="$(git diff --name-only --diff-filter=ACM 2>/dev/null; git diff --cached --name-only --diff-filter=ACM 2>/dev/null)"
files="$(printf '%s\n' "$files" | sort -u | grep -E '\.(kt|kts|java|yaml|yml|json|properties)$' || true)"

[ -z "$files" ] && exit 0

patterns=(
  'password[[:space:]]*=[[:space:]]*"[^"$]{4,}"'
  'secret[[:space:]]*=[[:space:]]*"[^"$]{4,}"'
  'apiKey[[:space:]]*=[[:space:]]*"[^"$]{8,}"'
  'token[[:space:]]*=[[:space:]]*"[^"$]{8,}"'
  'jdbc:postgresql://[^"]*:[^"@]*@'
  'AKIA[0-9A-Z]{16}'
  '-----BEGIN [A-Z ]*PRIVATE KEY-----'
  'eyJhbGciOi[A-Za-z0-9._-]{20,}'
)

found=0
while IFS= read -r file; do
  [ -f "$file" ] || continue
  for p in "${patterns[@]}"; do
    if grep -nEI "$p" "$file" >/dev/null 2>&1; then
      if [ "$found" -eq 0 ]; then
        echo "Possible hardcoded secret in changed files:" >&2
        found=1
      fi
      grep -nEI "$p" "$file" | sed "s|^|  $file:|" >&2
    fi
  done
done <<< "$files"

if [ "$found" -eq 1 ]; then
  echo "Move these into environment variables before committing." >&2
  exit 2
fi

exit 0
