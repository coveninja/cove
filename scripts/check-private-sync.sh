#!/usr/bin/env bash
set -euo pipefail

check_copy() {
  local source_dir=$1
  local destination_dir=$2
  local integration=$3

  for source in "$source_dir"/*.go; do
    local file=${source##*/}
    local destination="$destination_dir/$file"
    if [[ ! -f "$destination" ]] || ! cmp -s "$source" "$destination"; then
      echo "$integration source mismatch: $source != $destination" >&2
      echo "Update both copies before merging; release injection uses the private copy." >&2
      exit 1
    fi
  done
}

check_copy _private/cove-auth internal/supabase Supabase
