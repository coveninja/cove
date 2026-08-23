#!/bin/bash

set -euo pipefail

if [[ "$(uname -s)" != "Darwin" ]]; then
  echo "This script builds Cove's patched macOS libmpv." >&2
  exit 1
fi

install_prefix=${1:?"usage: $0 INSTALL_PREFIX"}
script_dir=$(cd "$(dirname "$0")" && pwd)
repo_root=$(cd "$script_dir/.." && pwd)
patch_file="$repo_root/packaging/macos/mpv-coreaudio-hotplug-lifetime.patch"
mpv_version=0.41.0
mpv_sha256=ee21092a5ee427353392360929dc64645c54479aefdb5babc5cfbb5fad626209
build_root=$(mktemp -d "${TMPDIR:-/tmp}/cove-mpv-build.XXXXXX")

cleanup() {
  rm -rf "$build_root"
}
trap cleanup EXIT

archive="$build_root/mpv-$mpv_version.tar.gz"
source_dir="$build_root/mpv-$mpv_version"
build_dir="$build_root/build"

# Homebrew keeps libarchive keg-only, so pkg-config cannot find it through the
# normal prefix. mpv links it for archive-backed media and expects its .pc file.
libarchive_pkgconfig="$(brew --prefix libarchive)/lib/pkgconfig"
export PKG_CONFIG_PATH="$libarchive_pkgconfig${PKG_CONFIG_PATH:+:$PKG_CONFIG_PATH}"

curl --fail --location --retry 3 \
  --output "$archive" \
  "https://github.com/mpv-player/mpv/archive/refs/tags/v$mpv_version.tar.gz"

actual_sha256=$(shasum -a 256 "$archive" | awk '{print $1}')
if [[ "$actual_sha256" != "$mpv_sha256" ]]; then
  echo "mpv source checksum mismatch: expected $mpv_sha256, got $actual_sha256" >&2
  exit 1
fi

tar -xzf "$archive" -C "$build_root"
(
  cd "$source_dir"
  git apply --check "$patch_file"
  git apply "$patch_file"
)

meson setup "$build_dir" "$source_dir" \
  --prefix="$install_prefix" \
  --libdir=lib \
  --buildtype=release \
  -Dbuild-date=false \
  -Dcplayer=false \
  -Dhtml-build=disabled \
  -Djavascript=enabled \
  -Dlibarchive=enabled \
  -Dlibmpv=true \
  -Dlua=luajit \
  -Dmanpage-build=disabled \
  -Duchardet=enabled \
  -Dvapoursynth=disabled \
  -Dvulkan=enabled
meson compile -C "$build_dir" --verbose
meson install -C "$build_dir"

test -f "$install_prefix/lib/libmpv.2.dylib"
