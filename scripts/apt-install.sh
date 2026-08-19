#!/usr/bin/env bash
#
# Install Debian packages on a GitHub-hosted runner without the step hanging.
#
# The v1.1.0 release sat twelve minutes on "Install libmpv" and had to be cancelled by
# hand. Its log holds nothing at all between the step header and the cancellation: the
# step ran `apt-get update -qq`, and -qq means "output only errors", so a step that was
# retrying stalled mirror connections is indistinguishable in the log from one that has
# deadlocked. That is the first thing fixed here — -q rather than -qq, and a timestamp
# around each invocation, so the next slow run says which phase it was in.
#
# The stalls themselves are a property of the runner, not of the package list. The same
# step, on the same image, took 1m41s in the CI run eighteen minutes earlier, and across
# the v1.0.x tags it took 1m4s, 6m42s, 11m30s, 20m4s and 23m14s for a fixed set of
# packages. apt's own defaults turn one unreachable mirror address into minutes of
# silence: three retries at a 120-second connection timeout, per index file, and on Azure
# the IPv6 route to the Ubuntu mirrors is the address that blackholes. ForceIPv4 skips it,
# and the shorter timeout and retry budget bound what a genuinely slow mirror can cost,
# because failing in ninety seconds and retrying the whole command from scratch recovers
# faster than one apt process grinding through its own backoff.
#
# DPkg::Lock::Timeout is set for the other candidate. apt defaults it to 0 — fail at once
# if unattended-upgrades holds the lock — which turns a runner that is merely busy into a
# failed release, so wait a bounded and visible while for it instead.
#
# Package selection is deliberately untouched: every caller passes the packages it always
# passed, and nothing here adds --no-install-recommends. The hang is the bug, not the
# package set.
set -uo pipefail

[ "$#" -gt 0 ] || { echo "usage: apt-install.sh <package>..." >&2; exit 2; }

attempts=${APT_ATTEMPTS:-3}
backoff=${APT_BACKOFF:-15}
lock_timeout=${APT_LOCK_TIMEOUT:-180}

apt_get() {
  sudo apt-get \
    -o Acquire::ForceIPv4=true \
    -o Acquire::Retries=2 \
    -o Acquire::http::Timeout=30 \
    -o Acquire::https::Timeout=30 \
    -o DPkg::Lock::Timeout="$lock_timeout" \
    "$@"
}

# Both phases are timestamped because the failure this guards against produces no other
# output. A bare "still going" is worth more than a silent step.
run_phase() {
  echo "[$(date -u +%H:%M:%S)] apt-get $1"
  shift
  apt_get "$@"
  local status=$?
  echo "[$(date -u +%H:%M:%S)] exit $status"
  return "$status"
}

for attempt in $(seq 1 "$attempts"); do
  if [ "$attempt" -gt 1 ]; then
    echo "::notice::apt attempt $attempt of $attempts after a transient mirror failure"
  fi

  if run_phase update update -q && run_phase "install $*" install -y "$@"; then
    exit 0
  fi

  if [ "$attempt" -lt "$attempts" ]; then
    echo "apt failed; retrying in ${backoff}s." >&2
    sleep "$backoff"
    backoff=$((backoff * 2))
  fi
done

echo "::error::apt still failing after $attempts attempts installing: $*" >&2
exit 1
