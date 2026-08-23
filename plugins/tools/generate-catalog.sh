#!/usr/bin/env bash
set -euo pipefail

if [ "$#" -lt 4 ]; then
  echo "usage: $0 KEY_ID RELEASE_BASE_URL OUTPUT_JSON PACKAGE_ZIP..." >&2
  exit 2
fi

key_id=$1
release_base_url=${2%/}
output=$3
shift 3
[[ "$key_id" =~ ^[a-zA-Z0-9._-]{1,64}$ ]] || { echo "invalid key id" >&2; exit 1; }
[[ "$release_base_url" == https://* ]] || { echo "release base URL must use HTTPS" >&2; exit 1; }

entries='[]'
for package in "$@"; do
  test -f "$package" || { echo "package does not exist: $package" >&2; exit 1; }
  manifest=$(unzip -p "$package" plugin.json)
  jq -e '.schema_version == 1 and .api_version == 1 and (.id | type == "string")' \
    <<<"$manifest" >/dev/null
  filename=$(basename "$package")
  size=$(wc -c < "$package" | tr -d ' ')
  checksum=$(sha256sum "$package" | cut -d' ' -f1)
  entry=$(jq -n \
    --argjson manifest "$manifest" \
    --arg package_url "$release_base_url/$filename" \
    --arg signature_url "$release_base_url/$filename.sig" \
    --argjson size_bytes "$size" \
    --arg sha256 "$checksum" \
    '{manifest:$manifest,package_url:$package_url,signature_url:$signature_url,size_bytes:$size_bytes,sha256:$sha256}')
  entries=$(jq --argjson entry "$entry" '. + [$entry]' <<<"$entries")
done

mkdir -p "$(dirname "$output")"
jq -n \
  --arg key_id "$key_id" \
  --arg published_at "$(date -u +'%Y-%m-%dT%H:%M:%SZ')" \
  --argjson plugins "$entries" \
  '{schema_version:1,key_id:$key_id,published_at:$published_at,plugins:$plugins}' > "$output"
