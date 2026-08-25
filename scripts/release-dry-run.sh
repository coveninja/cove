#!/usr/bin/env bash
#
# Rehearse release-only packaging, signing, and manifest steps with a throwaway key.
# The checks cover PKGBUILD, Flatpak, NSIS, and SignedManifestVerifier contracts. When a
# desktop distributable exists, use it instead of a stub to catch jpackage layout changes.
#
#   bash scripts/release-dry-run.sh                # rehearse the tag in VERSION
#   bash scripts/release-dry-run.sh --tag v1.2.3   # rehearse a specific tag
#   bash scripts/release-dry-run.sh --remote       # also check GitHub secrets/vars via gh
#
set -euo pipefail

repo_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
cd "$repo_root"

workflow=.github/workflows/release.yml
tag=""
check_remote=0

while [[ $# -gt 0 ]]; do
    case "$1" in
        --tag) tag="${2:?--tag needs a value}"; shift 2 ;;
        --remote) check_remote=1; shift ;;
        -h|--help) sed -n '2,20p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *) echo "unknown argument: $1" >&2; exit 2 ;;
    esac
done

tag="${tag:-v$(tr -d ' \t\r\n' < VERSION)}"

work=$(mktemp -d "${TMPDIR:-/tmp}/cove-release-dry-run.XXXXXX")
trap 'rm -rf -- "$work"' EXIT

failures=0
section() { printf '\n\033[1m%s\033[0m\n' "$1"; }
pass()    { printf '  \033[32mok\033[0m    %s\n' "$1"; }
fail()    { printf '  \033[31mFAIL\033[0m  %s\n' "$1"; failures=$((failures + 1)); }
skip()    { printf '  \033[33mskip\033[0m  %s\n' "$1"; }
warn()    { printf '  \033[33mwarn\033[0m  %s\n' "$1"; }
check()   { if [[ -n "$2" ]]; then pass "$1"; else fail "$1"; fi; }

# ── Tag and version agreement ────────────────────────────────────────────────
# The release workflow's first real step; a mismatch aborts the run after checkout, but the
# tag is already pushed by then and tags are not meant to move.
section "Tag and version"

version="${tag#v}"
if [[ "$version" = "$(tr -d '\r\n' < VERSION)" ]]; then
    pass "$tag agrees with VERSION ($version)"
else
    fail "$tag disagrees with VERSION ($(cat VERSION)) — the kotlin job aborts on this"
fi

# `make patch` rewrites the metainfo release line alongside VERSION. A hand-edited VERSION
# skips that, and the Flatpak ships claiming the previous version.
metainfo_version=$(sed -n 's/.*<release version="\([^"]*\)".*/\1/p' \
    flatpak/io.github.coveninja.Cove.metainfo.xml | head -1)
if [[ "$metainfo_version" = "$version" ]]; then
    pass "Flatpak metainfo declares $metainfo_version"
else
    fail "Flatpak metainfo declares $metainfo_version, not $version"
fi

# ── Signing key derivation (update-key job) ──────────────────────────────────
# Reproduced verbatim from the workflow so a change to either side shows up here. The key is
# generated fresh in a temp dir; the real UPDATE_SIGNING_KEY_BASE64 is never needed or read.
section "Update signing key derivation"

openssl genpkey -algorithm Ed25519 -outform DER -out "$work/throwaway.der" 2>/dev/null
PRIVATE_KEY=$(base64 -w0 "$work/throwaway.der")
KEY_ID=$(sed -n "s/.*UPDATE_SIGNING_KEY_ID || '\([^']*\)'.*/\1/p" "$workflow" | head -1)
KEY_ID="${KEY_ID:-cove-2026-1}"

printf '%s' "$PRIVATE_KEY" | tr -d ' \t\r\n' | base64 -d > "$work/private.der"
openssl pkey -inform DER -in "$work/private.der" -pubout -outform DER -out "$work/public.der"
PUBLIC=$(base64 -w0 "$work/public.der")
PUBLIC_KEYS="$KEY_ID=$PUBLIC"

check "default key id '$KEY_ID' matches the verifier's [a-zA-Z0-9._-]{1,64}" \
    "$([[ "$KEY_ID" =~ ^[a-zA-Z0-9._-]{1,64}$ ]] && echo y)"
check "derived public key is a single base64 line" \
    "$([[ "$PUBLIC" != *$'\n'* && -n "$PUBLIC" ]] && echo y)"
# UPDATE_PUBLIC_KEYS is baked into the build as one properties line and one BuildConfig
# string; a newline in either would truncate the file or break compilation.
check "UPDATE_PUBLIC_KEYS value carries no newline" \
    "$([[ "$PUBLIC_KEYS" != *$'\n'* ]] && echo y)"
check "workflow passes UPDATE_PUBLIC_KEYS to every packaging job" \
    "$([[ $(grep -c 'UPDATE_PUBLIC_KEYS: ' "$workflow") -ge 3 ]] && echo y)"

# ── Linux packaging: tarball, launcher, PKGBUILD, Flatpak ────────────────────
# The tarball layout is defined in the workflow, consumed by packaging/PKGBUILD, and nothing
# connects the two. Every path the PKGBUILD installs from is checked against what the
# workflow's own commands actually produce.
section "Linux packaging"

app_image=app/desktop/build/compose/binaries/main/app/Cove
if [[ -d "$app_image" ]]; then
    pass "using the real Compose distributable at $app_image"
    real_image=1
else
    skip "no distributable at $app_image — using a stub (run 'make test-build' for the real one)"
    real_image=0
    app_image="$work/stub/Cove"
    mkdir -p "$app_image/bin" "$app_image/lib/runtime/lib" "$app_image/lib/app"
    : > "$app_image/bin/Cove"
    : > "$app_image/lib/runtime/lib/libjsig.so"
fi

pkg="$work/_pkg"
mkdir -p "$pkg/bin" "$pkg/lib/cove" "$pkg/share/applications" \
         "$pkg/share/icons/hicolor/scalable/apps" "$work/compose-app"
# shellcheck disable=SC2016
printf '#!/bin/sh\nJSIG=/usr/lib/cove/Cove/lib/runtime/lib/libjsig.so\n[ -f "$JSIG" ] && export LD_PRELOAD="${JSIG}${LD_PRELOAD:+:$LD_PRELOAD}"\nexec /usr/lib/cove/Cove/bin/Cove "$@"\n' > "$pkg/bin/cove"
chmod +x "$pkg/bin/cove"
# Hardlinks where the filesystem allows it: the checks below read the tree's shape, not its
# bytes, and a 260 MB image copied twice is the difference between seconds and a minute.
# cp -al leaves the directories it managed to create behind when it hits a cross-device
# link (a tmpfs $TMPDIR against an on-disk repo), and a bare cp -r would then nest the image
# inside that remnant. Clear it first so the fallback reproduces the workflow's own layout.
clone() { cp -al "$1" "$2" 2>/dev/null || { rm -rf "$2"; cp -r "$1" "$2"; }; }
clone "$app_image" "$pkg/lib/cove/Cove"
clone "$app_image" "$work/compose-app/Cove"
cp flatpak/io.github.coveninja.Cove.desktop "$pkg/share/applications/"
cp packaging/icons/cove.svg \
   "$pkg/share/icons/hicolor/scalable/apps/io.github.coveninja.Cove.svg"
tar -cf - -C "$pkg" . | gzip -1 > "$work/cove-linux-amd64.tar.gz"

sha256=$(sha256sum "$work/cove-linux-amd64.tar.gz" | cut -d' ' -f1)
sed -e "s/__VERSION__/${version}/" -e "s/__SHA256__/${sha256}/" packaging/PKGBUILD \
    > "$work/PKGBUILD"

check "no __PLACEHOLDER__ survives PKGBUILD substitution" \
    "$(grep -q '__[A-Z0-9_]*__' "$work/PKGBUILD" || echo y)"
check "PKGBUILD pkgver is $version" \
    "$(grep -qx "pkgver=$version" "$work/PKGBUILD" && echo y)"
check "PKGBUILD source URL points at the $tag release asset" \
    "$(grep -q "releases/download/v\${pkgver}/cove-linux-amd64.tar.gz" "$work/PKGBUILD" && echo y)"

# Every ${srcdir}/... the PKGBUILD reads must exist in the tarball the workflow built.
# Scraped from the PKGBUILD rather than hardcoded, so a new install line is covered for free.
tar -tzf "$work/cove-linux-amd64.tar.gz" | sed -e 's|^\./||' -e 's|/$||' \
    | sort -u > "$work/tarball-entries"
in_tarball() { grep -qxF "$1" "$work/tarball-entries"; }
missing=""
# shellcheck disable=SC2016  # ${srcdir} is matched literally in the PKGBUILD text
while read -r path; do
    [[ -n "$path" ]] || continue
    in_tarball "$path" || missing="$missing $path"
done < <(grep -o '\${srcdir}/[^"]*' "$work/PKGBUILD" | sed 's|\${srcdir}/||' | sort -u)
check "every path PKGBUILD installs exists in the tarball" \
    "$([[ -z "$missing" ]] && echo y)"
[[ -z "$missing" ]] || printf '        missing:%s\n' "$missing"

# The generated launcher hardcodes install paths. /usr/lib/cove maps onto the tarball's
# lib/cove, so the same tree answers both.
launcher_missing=""
while read -r path; do
    [[ -n "$path" ]] || continue
    in_tarball "${path#/usr/}" || launcher_missing="$launcher_missing $path"
done < <(grep -o '/usr/lib/cove/[A-Za-z0-9/._-]*' "$pkg/bin/cove" | sort -u)
if [[ "$real_image" = 1 ]]; then
    check "launcher's hardcoded paths resolve inside the tarball" \
        "$([[ -z "$launcher_missing" ]] && echo y)"
    [[ -z "$launcher_missing" ]] || printf '        missing:%s\n' "$launcher_missing"
else
    skip "launcher path resolution (stub image cannot prove jpackage's real layout)"
fi

# The Flatpak manifest copies from ../compose-app, which the workflow stages separately.
flatpak_manifest=flatpak/io.github.coveninja.Cove.yml
check "Flatpak manifest sources the compose-app directory the workflow stages" \
    "$(grep -qE 'path: \.\./compose-app$' "$flatpak_manifest" && echo y)"
flatpak_missing=""
while read -r path; do
    [[ -n "$path" ]] || continue
    [[ -e "$work/$path" || -e "$repo_root/$path" ]] || flatpak_missing="$flatpak_missing $path"
done < <(grep -oE '(compose-app/Cove|flatpak/[a-zA-Z0-9._-]+|packaging/icons/[a-zA-Z0-9._-]+)' \
    "$flatpak_manifest" | sort -u)
check "every file the Flatpak build-commands install exists" \
    "$([[ -z "$flatpak_missing" ]] && echo y)"
[[ -z "$flatpak_missing" ]] || printf '        missing:%s\n' "$flatpak_missing"

if command -v makepkg >/dev/null 2>&1; then
    if (cd "$work" && makepkg --printsrcinfo > SRCINFO 2>"$work/srcinfo.err"); then
        check "makepkg --printsrcinfo accepts the generated PKGBUILD (the AUR job's step)" \
            "$(grep -q "pkgver = $version" "$work/SRCINFO" && echo y)"
    else
        fail "makepkg --printsrcinfo rejected the generated PKGBUILD"
        sed 's/^/        /' "$work/srcinfo.err"
    fi
else
    skip "makepkg not installed — .SRCINFO generation unverified"
fi

# ── Windows packaging inputs ─────────────────────────────────────────────────
# makensis does not run here, but everything the .nsi reads at compile time is on disk and
# its paths are relative to packaging/windows/, which is easy to break by moving a file.
section "Windows packaging inputs"

nsi=packaging/windows/cove.nsi
nsi_missing=""
while read -r path; do
    [[ -n "$path" ]] || continue
    [[ "$path" != '..\..\staging' ]] || continue  # assembled by CI, not in the repo
    resolved="packaging/windows/${path//\\//}"
    resolved=$(cd "$(dirname "$resolved")" 2>/dev/null && pwd)/$(basename "$resolved") || true
    [[ -e "$resolved" ]] || nsi_missing="$nsi_missing $path"
done < <(grep -oE '"\.\.\\\.\.\\[^"]+"' "$nsi" | tr -d '"' | sort -u)
check "every repo file the NSIS script references exists" \
    "$([[ -z "$nsi_missing" ]] && echo y)"
[[ -z "$nsi_missing" ]] || printf '        missing:%s\n' "$nsi_missing"

# `!cd "..\..\staging"` resolves to the workspace root, which is where the workflow builds it.
check "NSIS packs from the staging directory the workflow assembles at the repo root" \
    "$(grep -q '!cd "\.\.\\\.\.\\staging"' "$nsi" && echo y)"
# The workflow validates staging by name; the installer's own rollback checks the same names.
for entry in Cove.exe mpv-2.dll runtime app; do
    check "workflow's staging validation and the installer agree on $entry" \
        "$(grep -q "\"$entry\"" "$workflow" && grep -q "INSTDIR\\\\$entry" "$nsi" && echo y)"
done
check "portable ZIP keeps the name that makes the pre-1.0.0 updater fail closed" \
    "$(grep -q 'cove-windows-amd64-portable\.zip' "$workflow" &&
       ! grep -q 'DestinationPath cove-windows-amd64\.zip' "$workflow" && echo y)"

# ── Update manifest: generate, sign, verify, and contract-check ──────────────
# The manifest is assembled by jq in the workflow and parsed by SignedManifestVerifier in
# Kotlin. Nothing but this check connects the two, and a field rename on either side breaks
# updates for every installed client with no build failure anywhere.
section "Update manifest"

assets="$work/release-assets"
mkdir -p "$assets"

# Asset names and the jq program are read out of the workflow rather than copied here. A copy
# would only ever test itself: renaming a target or a field in release.yml has to change what
# this script produces, or the whole section proves nothing.
WIN_SETUP=$(sed -n 's/^[[:space:]]*WIN_SETUP=\(.*\)$/\1/p' "$workflow" | head -1)
WIN_PORTABLE=$(sed -n 's/^[[:space:]]*WIN_PORTABLE=\(.*\)$/\1/p' "$workflow" | head -1)
ANDROID=$(sed -n 's/^[[:space:]]*ANDROID=\(.*\)$/\1/p' "$workflow" | head -1)
check "workflow names all three update assets" \
    "$([[ -n "$WIN_SETUP" && -n "$WIN_PORTABLE" && -n "$ANDROID" ]] && echo y)"

manifest_filter=$(sed -n "/'{schema_version/,/]}' >/p" "$workflow" \
    | sed -e "1s/^[[:space:]]*'//" -e "\$s/'[[:space:]]*>.*\$//")
if [[ -z "$manifest_filter" ]]; then
    fail "could not find the manifest jq program in $workflow — this section proves nothing"
    manifest_filter='{}'
else
    pass "read the manifest jq program from $workflow ($(wc -l <<< "$manifest_filter") lines)"
fi

for file in "$WIN_SETUP" "$WIN_PORTABLE" "$ANDROID"; do
    head -c 4096 /dev/urandom > "$assets/$file"
done

# Every asset the manifest names must also be uploaded by a packaging job, or publish-release
# fails its `test -s` after all three platform builds have already run.
uploaded=$(grep -oE '^ {12}[A-Za-z0-9._-]+$' "$workflow" | tr -d ' ' | sort -u)
for file in "$WIN_SETUP" "$WIN_PORTABLE" "$ANDROID"; do
    check "$file is uploaded by a packaging job" \
        "$(grep -qx "$file" <<< "$uploaded" && echo y)"
done

jq -n \
    --arg key_id "$KEY_ID" \
    --arg version "$version" \
    --arg release_name "$tag" \
    --arg published_at "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
    --arg win_setup "$WIN_SETUP" \
    --arg win_setup_sha "$(sha256sum "$assets/$WIN_SETUP" | cut -d' ' -f1)" \
    --argjson win_setup_size "$(stat -c %s "$assets/$WIN_SETUP")" \
    --arg win_portable "$WIN_PORTABLE" \
    --arg win_portable_sha "$(sha256sum "$assets/$WIN_PORTABLE" | cut -d' ' -f1)" \
    --argjson win_portable_size "$(stat -c %s "$assets/$WIN_PORTABLE")" \
    --arg android "$ANDROID" \
    --arg android_sha "$(sha256sum "$assets/$ANDROID" | cut -d' ' -f1)" \
    --argjson android_size "$(stat -c %s "$assets/$ANDROID")" \
    "$manifest_filter" > "$work/cove-update-manifest-v1.json"

openssl pkeyutl -sign -rawin -inkey "$work/private.der" -keyform DER \
    -in "$work/cove-update-manifest-v1.json" -out "$work/manifest-signature.bin"
base64 -w0 "$work/manifest-signature.bin" > "$work/cove-update-manifest-v1.json.sig"

if openssl pkeyutl -verify -rawin -pubin -inkey "$work/public.der" -keyform DER \
     -in "$work/cove-update-manifest-v1.json" \
     -sigfile "$work/manifest-signature.bin" >/dev/null 2>&1; then
    pass "raw Ed25519 signature verifies over the exact manifest bytes"
else
    fail "raw Ed25519 signature does not verify"
fi

# The verifier base64-decodes the signature after trimming; -w0 must keep it on one line.
check "signature is one base64 line the verifier can decode" \
    "$([[ $(wc -l < "$work/cove-update-manifest-v1.json.sig") -le 1 ]] && echo y)"

model=app/shared/src/commonMain/kotlin/com/coveninja/cove/shared/model/UpdateManifest.kt
# Wire names as kotlinx-serialization sees them: @SerialName where present, the property name
# otherwise. Every one is required (no default), so a missing key is a decode failure.
wire_names=$(awk '
    /@SerialName\("/ { match($0, /@SerialName\("[^"]+"/); print substr($0, RSTART+13, RLENGTH-14); next }
    /val [a-zA-Z][a-zA-Z0-9]*:/ {
        match($0, /val [a-zA-Z][a-zA-Z0-9]*:/); print substr($0, RSTART+4, RLENGTH-5)
    }
' "$model" | sort -u)
produced=$( { jq -r 'keys[]' "$work/cove-update-manifest-v1.json"
              jq -r '.assets[0] | keys[]' "$work/cove-update-manifest-v1.json"; } | sort -u)

if [[ "$wire_names" = "$produced" ]]; then
    pass "manifest fields match UpdateManifest.kt exactly"
else
    fail "manifest fields drifted from UpdateManifest.kt"
    diff -u <(printf '%s\n' "$wire_names") <(printf '%s\n' "$produced") \
        | sed 's/^/        /' || true
fi

check "schema_version matches UPDATE_MANIFEST_SCHEMA_VERSION" \
    "$([[ "$(jq -r .schema_version "$work/cove-update-manifest-v1.json")" = \
          "$(sed -n 's/.*UPDATE_MANIFEST_SCHEMA_VERSION = \([0-9]*\).*/\1/p' "$model")" ]] && echo y)"

for constant in UPDATE_MANIFEST_ASSET_NAME UPDATE_MANIFEST_SIGNATURE_NAME; do
    name=$(sed -n "s/.*$constant = \"\([^\"]*\)\".*/\1/p" "$model")
    check "workflow publishes '$name' ($constant)" \
        "$(grep -q "$name" "$workflow" && echo y)"
done

# Every target in the manifest must be one an UpdatePlatform actually reports, or that
# platform silently finds no asset and never updates.
runtime_targets=$(grep -rhoE 'override val target[^=]*= [^{]*' \
        app/backend/src/desktopMain app/backend/src/androidMain \
    | grep -oE '"[a-z0-9-]+"' | tr -d '"' | sort -u)
for target in $(jq -r '.assets[].target' "$work/cove-update-manifest-v1.json"); do
    check "target '$target' is claimed by an UpdatePlatform implementation" \
        "$(grep -qx "$target" <<< "$runtime_targets" && echo y)"
done
for target in $runtime_targets; do
    check "UpdatePlatform target '$target' is published in the manifest" \
        "$(jq -e --arg t "$target" '.assets[] | select(.target == $t)' \
            "$work/cove-update-manifest-v1.json" >/dev/null && echo y)"
done

# The verifier's own rules, applied to the real generated document.
verifier=app/backend/src/jvmSharedMain/kotlin/com/coveninja/cove/backend/updater/SignedManifestVerifier.kt
max_asset_bytes=$(( 1 << 30 ))
while read -r target name size sha; do
    check "asset '$name' has a verifier-safe name" \
        "$([[ "$name" =~ ^[a-zA-Z0-9._-]{1,128}$ ]] && echo y)"
    check "asset '$name' size $size is within 1..$max_asset_bytes" \
        "$([[ "$size" -ge 1 && "$size" -le "$max_asset_bytes" ]] && echo y)"
    check "asset '$name' checksum is lowercase hex ($target)" \
        "$([[ "$sha" =~ ^[0-9a-f]{64}$ ]] && echo y)"
done < <(jq -r '.assets[] | "\(.target) \(.name) \(.size_bytes) \(.sha256)"' \
    "$work/cove-update-manifest-v1.json")

manifest_bytes=$(stat -c %s "$work/cove-update-manifest-v1.json")
max_manifest=$(sed -n 's/.*MAX_MANIFEST_BYTES = \([0-9]*\) \* \([0-9]*\).*/\1*\2/p' "$verifier")
check "manifest is $manifest_bytes bytes, under MAX_MANIFEST_BYTES ($max_manifest)" \
    "$([[ "$manifest_bytes" -le $(( ${max_manifest/\*/ * } )) ]] && echo y)"

# ── Checksum sidecars ────────────────────────────────────────────────────────
# publish-release runs `sha256sum -c` over every *.sha256 in the merged asset directory.
# Linux writes them with sha256sum; Windows writes them from PowerShell with -NoNewline, so
# both spellings have to round-trip through the same verification step.
section "Checksum sidecars"

(cd "$assets" && sha256sum "$ANDROID" > "$ANDROID.sha256")
printf '%s  %s' "$(sha256sum "$assets/$WIN_SETUP" | cut -d' ' -f1)" "$WIN_SETUP" \
    > "$assets/$WIN_SETUP.sha256"
if (cd "$assets" && for c in *.sha256; do sha256sum -c "$c" >/dev/null; done); then
    pass "sha256sum -c accepts both the Linux and the newline-free Windows sidecars"
else
    fail "sha256sum -c rejected a sidecar — publish-release verifies these"
fi

# ── Release notes ────────────────────────────────────────────────────────────
# publish-release uses the tag commit's body verbatim as the GitHub release notes.
section "Release notes"

if git rev-parse -q --verify "refs/tags/$tag" >/dev/null 2>&1; then
    body=$(git log -1 --format=%b "$tag")
    notes_source="tag $tag"
else
    body=$(git log -1 --format=%b HEAD)
    notes_source="HEAD (tag $tag does not exist yet)"
fi
if [[ -n "${body//[$' \t\n\r']/}" ]]; then
    pass "release notes from $notes_source are non-empty ($(wc -l <<< "$body") lines)"
elif [[ "$notes_source" = "tag $tag" ]]; then
    fail "release notes from $notes_source are empty — the release publishes with no body"
else
    warn "$notes_source has an empty commit body; 'make patch' writes the changelog into the
        commit it tags, so this only matters if you tag the current HEAD by hand"
fi

# ── Remote preflight ─────────────────────────────────────────────────────────
# A missing secret fails the release 20+ minutes in, after the Kotlin build has run.
section "Remote preflight"

if [[ "$check_remote" = 0 ]]; then
    skip "GitHub secrets, variables and tag state (pass --remote to check)"
elif ! command -v gh >/dev/null 2>&1; then
    skip "gh is not installed"
else
    if configured=$(gh secret list --json name --jq '.[].name' 2>/dev/null); then
        # Scraped from the workflow so a newly referenced secret is covered automatically.
        while read -r secret; do
            [[ -n "$secret" && "$secret" != "GITHUB_TOKEN" ]] || continue
            check "secret $secret is configured" \
                "$(grep -qx "$secret" <<< "$configured" && echo y)"
        done < <(grep -oE 'secrets\.[A-Z0-9_]+' "$workflow" | cut -d. -f2 | sort -u)
    else
        skip "cannot list secrets (needs admin on coveninja/cove)"
    fi

    if configured_vars=$(gh variable list --json name --jq '.[].name' 2>/dev/null); then
        for var in UPDATE_SIGNING_KEY_ID UPDATE_TRUSTED_PUBLIC_KEYS; do
            if grep -qx "$var" <<< "$configured_vars"; then
                pass "variable $var is set"
            else
                warn "variable $var is unset — the workflow falls back to its default"
            fi
        done
    fi

    if gh release view "$tag" >/dev/null 2>&1; then
        fail "release $tag already exists — publish-release refuses to replace assets"
    else
        pass "release $tag does not exist yet"
    fi
fi

# ── Summary ──────────────────────────────────────────────────────────────────
printf '\n'
if [[ "$failures" -eq 0 ]]; then
    printf '\033[32mrelease dry run passed\033[0m for %s\n' "$tag"
else
    printf '\033[31mrelease dry run found %d problem(s)\033[0m for %s\n' "$failures" "$tag"
fi
exit $(( failures > 0 ))
