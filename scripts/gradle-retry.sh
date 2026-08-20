#!/usr/bin/env bash
#
# Run a Gradle invocation, retrying the whole thing when it dies from a transient
# repository failure.
#
# Gradle already retries an individual download with backoff, but when those attempts run
# out it disables the repository for the remainder of the build ("Repository disabling" in
# the Gradle dependency-management docs). Every later resolution against that repository
# then fails immediately, so nothing inside the build can recover — only a fresh Gradle
# process re-enables it. That is exactly how the v1.0.2 Android asset was lost: Maven
# Central answered 429 to one ui-backhandler-android aar, mavenCentral() was disabled, and
# two unrelated transforms failed against a repository that was healthy seconds earlier.
# A tag build hits Maven Central from a shared runner IP with several full Gradle builds
# already in flight, so this is a matter of timing rather than of anything in the source.
#
# Only transient failures are retried. A compile error, a failing test or a lint violation
# is reported on the first attempt rather than burning three full builds to say the same
# thing, so the retry cannot mask a real regression.
set -uo pipefail

attempts=${GRADLE_RETRY_ATTEMPTS:-3}
backoff=${GRADLE_RETRY_BACKOFF:-30}

# Resolve gradlew from this script's own location so the caller's working directory,
# which differs between the workflow steps, cannot change which build is run.
root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)

# Signatures of a repository that was reachable but refused this request, or a connection
# that broke mid-transfer. "disabled" catches the follow-on failures once Gradle has given
# up on the repository, which is what the rest of the build actually reports.
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
