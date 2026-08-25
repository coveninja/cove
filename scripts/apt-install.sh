#!/usr/bin/env bash
#
# Install Debian packages on GitHub runners with bounded, visible retries.
# Force IPv4 to avoid runner routes that blackhole Ubuntu mirrors, use shorter network
# timeouts, and wait briefly for unattended-upgrades to release the dpkg lock.
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

# Timestamp both phases so a stalled mirror does not produce a silent job.
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
