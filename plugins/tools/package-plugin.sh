#!/usr/bin/env bash
set -euo pipefail

if [ "$#" -lt 2 ] || [ "$#" -gt 3 ]; then
  echo "usage: $0 SOURCE_DIRECTORY OUTPUT_ZIP [DISCORD_APPLICATION_ID]" >&2
  exit 2
fi

source_dir=$1
output_zip=$2
discord_application_id=${3:-}

test -d "$source_dir" || { echo "plugin source directory does not exist" >&2; exit 1; }
mkdir -p "$(dirname "$output_zip")"
output_zip="$(cd "$(dirname "$output_zip")" && pwd)/$(basename "$output_zip")"

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
cp -R "$source_dir"/. "$work"/

if [ -f "$work/plugin.template.json" ]; then
  [[ "$discord_application_id" =~ ^[0-9]{16,22}$ ]] || {
    echo "a 16-22 digit Discord application id is required for this plugin" >&2
    exit 1
  }
  jq --arg application_id "$discord_application_id" \
    '.discord_application_id = $application_id' \
    "$work/plugin.template.json" > "$work/plugin.json"
  rm "$work/plugin.template.json"
fi

test -f "$work/plugin.json" || { echo "plugin source is missing plugin.json" >&2; exit 1; }
entrypoint=$(jq -r '.entrypoint // "main.js"' "$work/plugin.json")
[[ "$entrypoint" =~ ^[a-zA-Z0-9._/-]{1,160}$ ]] || { echo "plugin entrypoint is invalid" >&2; exit 1; }
test -f "$work/$entrypoint" || { echo "plugin source is missing $entrypoint" >&2; exit 1; }
while IFS= read -r -d '' file; do
  relative=${file#"$work/"}
  if [[ "$relative" == *$'\n'* ]]; then
    echo "plugin filenames must not contain newlines" >&2
    exit 1
  fi
done < <(find "$work" -type f -print0)
find "$work" -type f -exec touch -t 198001010000 {} +
rm -f "$output_zip"
(
  cd "$work"
  find . -type f -printf '%P\n' | LC_ALL=C sort | zip -X -q -9 "$output_zip" -@
)
