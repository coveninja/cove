#!/usr/bin/env bash

set -euo pipefail

if (( $# < 1 || $# > 2 )); then
    echo "usage: $0 <previous-tag> [end-ref]" >&2
    exit 2
fi

previous_tag=$1
end_ref=${2:-HEAD}
commit_url_prefix=${COVE_COMMIT_URL_PREFIX:-https://github.com/coveninja/cove/commit}
user_facing_subject='^(feat|fix)(\([^)]*\))?!?:[[:space:]]'

# Keep release notes focused on behavior users can see. Reading the subject
# separately also prevents a feat/fix line in a non-user-facing commit body
# from causing that commit to be included.
git log --reverse --format='%H%x09%s' "$previous_tag..$end_ref" |
    while IFS=$'\t' read -r commit subject; do
        [[ $subject =~ $user_facing_subject ]] || continue
        printf -- '- [%s](%s/%s)\n' "$subject" "$commit_url_prefix" "$commit"
    done
