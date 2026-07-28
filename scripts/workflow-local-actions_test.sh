#!/usr/bin/env bash
set -euo pipefail

status=0
for workflow in .github/workflows/*.yml; do
  if ! awk '
    /^  [[:alnum:]_-]+:$/ {
      job = $1
      sub(/:$/, "", job)
      checked_out = 0
    }
    /^[[:space:]]+- uses: actions\/checkout@/ {
      checked_out = 1
    }
    /^[[:space:]]+- uses: \.\/\.github\/actions\// && !checked_out {
      printf "%s:%d: job %s uses a local action before checkout\n", FILENAME, FNR, job
      invalid = 1
    }
    END {
      exit invalid
    }
  ' "$workflow"; then
    status=1
  fi
done

exit "$status"
