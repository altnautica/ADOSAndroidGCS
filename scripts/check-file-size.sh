#!/usr/bin/env bash
# Kotlin file-size ratchet: no source file under app/, core/ or feature/ may pass
# LIMIT lines unless it is listed in .file-size-baseline, and a listed file may
# not grow past its recorded count. Shrink a listed file, then lower its count.
#   bash scripts/check-file-size.sh            check (exit 1 on a violation)
#   bash scripts/check-file-size.sh --list     print every file over the limit
set -euo pipefail

LIMIT=500
cd "$(dirname "$0")/.."
BASELINE=.file-size-baseline

declare -A allowed=()
if [[ -f $BASELINE ]]; then
  while read -r path lines; do
    [[ -z ${path:-} || $path == \#* ]] && continue
    allowed[$path]=$lines
  done <"$BASELINE"
fi

ROOTS=(app core feature)
for root in "${ROOTS[@]}"; do
  [[ -d $root ]] || { echo "source root missing: $root"; exit 1; }
done

fail=0
while IFS= read -r f; do
  n=$(wc -l <"$f")
  (( n > LIMIT )) || continue
  if [[ ${1:-} == --list ]]; then
    echo "$f $n"
    continue
  fi
  max=${allowed[$f]:-}
  if [[ -z $max ]]; then
    echo "over $LIMIT lines and not in $BASELINE: $f ($n)"
    fail=1
  elif (( n > max )); then
    echo "grew past its baseline: $f ($n > $max)"
    fail=1
  fi
done < <(find "${ROOTS[@]}" -name '*.kt' -not -path '*/src/test/*' -not -path '*/src/androidTest/*' -not -path '*/build/*' | sort)

if (( fail )); then
  echo "File-size check failed: split the file, or (for an existing file) keep it at or under its baseline."
  exit 1
fi
[[ ${1:-} == --list ]] || echo "File-size check passed (limit $LIMIT)."
