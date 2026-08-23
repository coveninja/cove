#!/usr/bin/env bash
set -euo pipefail

if [ "$#" -ne 3 ]; then
  echo "usage: $0 INPUT PRIVATE_KEY_DER OUTPUT_SIGNATURE" >&2
  exit 2
fi

input=$1
private_key=$2
output=$3
test -f "$input" || { echo "input asset does not exist" >&2; exit 1; }
test -f "$private_key" || { echo "private key does not exist" >&2; exit 1; }
mkdir -p "$(dirname "$output")"

openssl pkeyutl -sign -rawin -inkey "$private_key" -keyform DER -in "$input" \
  | openssl base64 -A > "$output"
