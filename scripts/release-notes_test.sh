#!/usr/bin/env bash

set -euo pipefail

repo_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
test_repo=$(mktemp -d "${TMPDIR:-/tmp}/cove-release-notes-test.XXXXXX")
trap 'rm -rf -- "$test_repo"' EXIT

git -C "$test_repo" init -q
git -C "$test_repo" config user.name "Cove Tests"
git -C "$test_repo" config user.email "tests@coveninja.invalid"

commit_subject() {
    git -C "$test_repo" commit --allow-empty -q -m "$1"
    git -C "$test_repo" rev-parse HEAD
}

commit_subject "baseline" >/dev/null
git -C "$test_repo" tag v1.0.0

commit_subject "chore(deps): update internal dependency" >/dev/null
fix_sha=$(commit_subject "fix: repair playback")
feat_sha=$(commit_subject "feat(player): add subtitle selection")
scoped_fix_sha=$(commit_subject "fix( #42 ): handle profile refresh")
breaking_feat_sha=$(commit_subject "feat(tv)!: replace navigation behavior")
commit_subject "docs: update contributor guide" >/dev/null
commit_subject "Merge pull request #99 from example/topic" >/dev/null
commit_subject "fixed: this is not a conventional fix commit" >/dev/null
git -C "$test_repo" commit --allow-empty -q \
    -m "refactor: reorganize playback" \
    -m "feat: mentioning a feature in the body must not include this commit"

actual=$(
    cd "$test_repo"
    COVE_COMMIT_URL_PREFIX="https://example.invalid/commit" \
        bash "$repo_root/scripts/release-notes.sh" v1.0.0 HEAD
)

expected=$(printf '%s\n%s\n%s\n%s' \
    "- [fix: repair playback](https://example.invalid/commit/$fix_sha)" \
    "- [feat(player): add subtitle selection](https://example.invalid/commit/$feat_sha)" \
    "- [fix( #42 ): handle profile refresh](https://example.invalid/commit/$scoped_fix_sha)" \
    "- [feat(tv)!: replace navigation behavior](https://example.invalid/commit/$breaking_feat_sha)")

if [[ $actual != "$expected" ]]; then
    echo "release note filtering produced unexpected output" >&2
    diff -u <(printf '%s\n' "$expected") <(printf '%s\n' "$actual") || true
    exit 1
fi

echo "release note filtering passed"
