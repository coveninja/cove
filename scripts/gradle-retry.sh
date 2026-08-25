#!/usr/bin/env bash
#
# Retry Gradle only after transient repository failures. Once Gradle disables a failed
# repository for a build, a new process is required to recover; compile, test, and lint
# failures still return immediately.
set -uo pipefail

attempts=${GRADLE_RETRY_ATTEMPTS:-3}
backoff=${GRADLE_RETRY_BACKOFF:-30}

# Resolve Gradle independently of the caller's working directory.
root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)

# Include follow-on "repository is disabled" failures after the original request error.
transient='Received status code (408|429|5[0-9][0-9]) from server|Too Many Requests|Connection reset|Connection timed out|Read timed out|Premature end of Content-Length|Network is unreachable|Remote host (closed|terminated) the connection|repository is disabled|Skipped due to earlier error'

log=$(mktemp)
trap 'rm -f "$log"' EXIT

for attempt in $(seq 1 "$attempts"); do
  if [ "$attempt" -gt 1 ]; then
    echo "::notice::Gradle attempt $attempt of $attempts after a transient repository failure"
  fi

  ("$root/app/gradlew" --project-dir "$root/app" "$@") 2>&1 | tee "$log"
  status=${PIPESTATUS[0]}
  [ "$status" -eq 0 ] && exit 0

  if ! grep -qE "$transient" "$log"; then
    echo "Gradle failed for a non-transient reason; not retrying." >&2
    exit "$status"
  fi

  if [ "$attempt" -lt "$attempts" ]; then
    echo "Transient repository failure; retrying in ${backoff}s." >&2
    sleep "$backoff"
    backoff=$((backoff * 2))
  fi
done

echo "::error::Gradle still failing after $attempts attempts against a transient repository error." >&2
exit "$status"
