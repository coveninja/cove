#!/usr/bin/env bash
#
# Build, install, and open Cove's first-run flow on a selected Android device.
# The script distinguishes TV via the same leanback feature as the app and can build only
# the target ABI to keep emulator installs small.
#
# Usage: onboarding-install.sh --kind tv|phone|any [--fixtures true|false] [--abi ABI|all]
#                              [--device SERIAL]
set -euo pipefail

kind=any
fixtures=true
abi=
device=

while [ $# -gt 0 ]; do
    case "$1" in
        --kind)     kind=$2;     shift 2 ;;
        --fixtures) fixtures=$2; shift 2 ;;
        --abi)      abi=$2;      shift 2 ;;
        --device)   device=$2;   shift 2 ;;
        *) echo "unknown argument: $1" >&2; exit 2 ;;
    esac
done

command -v adb >/dev/null 2>&1 || {
    echo "adb is not on PATH (Android SDK platform-tools)." >&2
    exit 1
}

# Match MainActivity's leanback check. Listing features works on API levels that lack
# `pm has-system-feature`; strip adb's carriage returns before matching a full line.
is_television() {
    adb -s "$1" shell pm list features 2>/dev/null \
        | tr -d '\r' \
        | grep -qx "feature:android.software.leanback"
}

serials=()
if [ -n "$device" ]; then
    serials=("$device")
else
    while read -r serial state; do
        [ "$state" = "device" ] || continue
        serials+=("$serial")
    done < <(adb devices | tail -n +2 | tr -d '\r' | awk 'NF >= 2 { print $1, $2 }')
fi

if [ ${#serials[@]} -eq 0 ]; then
    echo "No device or emulator is connected — start one, then re-run." >&2
    echo "  A television emulator is created once with: make tv-avd" >&2
    echo "  and started with:                           emulator -avd cove-tv -gpu host" >&2
    exit 1
fi

matching=()
televisions=()
handhelds=()
for serial in "${serials[@]}"; do
    if is_television "$serial"; then
        televisions+=("$serial")
    else
        handhelds+=("$serial")
    fi
done

case "$kind" in
    tv)    matching=("${televisions[@]:-}") ;;
    phone) matching=("${handhelds[@]:-}") ;;
    any)   matching=("${serials[@]}") ;;
    *) echo "unknown --kind: $kind" >&2; exit 2 ;;
esac
# An unset array expanded with :- leaves one empty element behind; drop it.
[ "${#matching[@]}" -eq 1 ] && [ -z "${matching[0]}" ] && matching=()

if [ ${#matching[@]} -eq 0 ]; then
    case "$kind" in
        tv)
            echo "No Android TV device is attached." >&2
            if [ ${#handhelds[@]} -gt 0 ]; then
                echo "A phone or tablet is attached — for that, use: make onboarding-mobile" >&2
            else
                echo "Create the emulator once with: make tv-avd" >&2
                echo "then start it with:            emulator -avd cove-tv -gpu host" >&2
            fi
            ;;
        phone)
            echo "No phone or tablet is attached." >&2
            if [ ${#televisions[@]} -gt 0 ]; then
                echo "A television is attached — for that, use: make onboarding-tv-install" >&2
            fi
            ;;
    esac
    exit 1
fi

if [ ${#matching[@]} -gt 1 ]; then
    echo "More than one matching device is attached:" >&2
    printf '  %s\n' "${matching[@]}" >&2
    echo "Pick one with: make <target> DEVICE=<serial>" >&2
    exit 1
fi

serial=${matching[0]}
shell_name=$(is_television "$serial" && echo television || echo handheld)

if [ -z "$abi" ]; then
    abi=$(adb -s "$serial" shell getprop ro.product.cpu.abi | tr -d '\r')
fi

echo "Target: $serial ($shell_name shell)"
if [ "$abi" = "all" ] || [ -z "$abi" ]; then
    echo "Building for every ABI."
    (cd app && ./gradlew :mobile:assembleDebug)
else
    echo "Building for $abi."
    (cd app && ./gradlew :mobile:assembleDebug -PcoveAbi="$abi")
fi

apk=app/mobile/build/outputs/apk/debug/mobile-debug.apk
if ! adb -s "$serial" install -r "$apk"; then
    echo ""
    echo "Install failed. If it was for storage, the device's /data is full:"
    adb -s "$serial" shell df -h /data/user/0 2>/dev/null || true
    echo ""
    echo "Repeated reinstalls stack up stale copies. Free them with:"
    echo "  adb -s $serial uninstall com.coveninja.cove"
    echo "or give the AVD a bigger data partition in Device Manager."
    exit 1
fi

adb -s "$serial" shell am start -n com.coveninja.cove/.MainActivity \
    --ez com.coveninja.cove.SHOW_ONBOARDING true \
    --ez com.coveninja.cove.ONBOARDING_FIXTURES "$fixtures"
