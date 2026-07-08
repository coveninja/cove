# Cove Android

Phase 2 scaffold: Go backend embedded as a gomobile AAR, thin Compose verification UI.

## Prerequisites

1. **JDK 17** — install via pacman: `sudo pacman -S jdk17-openjdk`
2. **adb** — install via pacman: `sudo pacman -S android-tools`
3. **Android SDK** — set up in user-space under `~/Android/Sdk` (no Android Studio required):
   ```sh
   # Create the directory layout
   mkdir -p ~/Android/Sdk/cmdline-tools
   # Download and unzip command-line tools from https://developer.android.com/studio#command-tools
   # Move the extracted 'cmdline-tools' dir to ~/Android/Sdk/cmdline-tools/latest

   export ANDROID_HOME=~/Android/Sdk

   # Accept licenses, then install required SDK components
   yes | $ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager --licenses
   $ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager \
     "platform-tools" "platforms;android-35" "build-tools;35.0.0" \
     "emulator" "system-images;android-35;google_apis;x86_64" \
     "ndk;27.2.12479018"
   ```
4. **gomobile** — install once, then init with the NDK:
   ```sh
   go install golang.org/x/mobile/cmd/gomobile@latest
   go install golang.org/x/mobile/cmd/gobind@latest
   go get golang.org/x/mobile/bind      # adds the dep to go.mod
   export ANDROID_NDK_HOME=$ANDROID_HOME/ndk/27.2.12479018
   gomobile init
   ```
5. **TMDB API key** — create `android/local.properties` (gitignored) with:
   ```
   sdk.dir=/home/<you>/Android/Sdk
   TMDB_API_KEY=your_key_here
   ```

## AVD (emulator)

Create a Pixel 7 AVD named `cove` (x86_64, API 35):
```sh
$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager create avd \
  -n cove \
  -k "system-images;android-35;google_apis;x86_64" \
  -d pixel_7
# Optionally bump RAM in ~/.android/avd/cove.avd/config.ini:
#   hw.ramSize=3072
```

**Headless (CI / no-window):**
```sh
$ANDROID_HOME/emulator/emulator -avd cove -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect &
adb wait-for-device shell 'while [[ -z $(getprop sys.boot_completed) ]]; do sleep 2; done'
```

**Windowed (interactive UI work):**
```sh
$ANDROID_HOME/emulator/emulator -avd cove
```

## Build

From the repo root:
```sh
# Build the Go AAR then assemble the APK
make android

# Or step-by-step
make android-aar               # outputs android/app/libs/cove.aar (arm64 + amd64)
cd android && ./gradlew assembleDebug
```

The debug APK is at `android/app/build/outputs/apk/debug/app-debug.apk`.

**Fast UI-only loop** (skips AAR rebuild — use when only Kotlin/Compose changed):
```sh
cd android && ./gradlew installDebug
```

**Full install + launch** (rebuilds AAR and APK, then installs):
```sh
make android-install
```

> Note: Compose Live Edit requires Android Studio with the Compose plugin. For
> hot-reload in the CLI workflow, `./gradlew installDebug` is the fastest path.

## Gradle wrapper jar

`gradle-wrapper.jar` is not committed. Generate it once with a locally installed
Gradle 8.11+:
```sh
cd android
~/Android/gradle-8.11.1/bin/gradle wrapper --gradle-version 8.11.1
```
After that `./gradlew` works without a system Gradle installation.

## What Phase 2 gives you

A single-screen app that starts the Go backend in a background thread and polls
`http://127.0.0.1:6969/api/ping` every 2 seconds, displaying "Backend: running
(pong)" or "Backend: unreachable". This is the integration smoke test before the
full Compose UI (Phase 4) and foreground service (Phase 3) land.

## Release signing (CI)

Tagged releases (`v*`) build a signed APK in the `package-android` job of
`.github/workflows/release.yml` and attach `cove-android.apk` (+ `.sha256`) to
the GitHub release. Signing needs a one-time keystore setup:

**1. Generate the keystore** (keep it out of the repo; back it up — losing it
means users must uninstall/reinstall to update, since Android rejects APKs
signed with a different key):

```sh
keytool -genkeypair -v \
  -keystore cove-release.jks \
  -alias cove \
  -keyalg RSA -keysize 4096 \
  -validity 10000
```

**2. Add GitHub repository secrets** (Settings → Secrets and variables →
Actions):

| Secret | Value |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | `base64 -w0 cove-release.jks` output |
| `ANDROID_KEYSTORE_PASSWORD` | keystore password chosen above |
| `ANDROID_KEY_ALIAS` | `cove` (or whatever `-alias` you used) |
| `ANDROID_KEY_PASSWORD` | key password (same as keystore password unless you set one) |

The job fails fast with a clear error if `ANDROID_KEYSTORE_BASE64` is missing.
Local `assembleRelease` builds without these env vars fall back to debug
signing (see `app/build.gradle.kts`), so the keystore is never required for
development.

To sign a release locally instead:
```sh
export ANDROID_KEYSTORE_FILE=/path/to/cove-release.jks
export ANDROID_KEYSTORE_PASSWORD=... ANDROID_KEY_ALIAS=cove ANDROID_KEY_PASSWORD=...
COVE_VERSION=v1.2.3 ./gradlew assembleRelease
```
