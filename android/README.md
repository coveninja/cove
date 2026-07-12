# Cove Android

Native Android client for Cove, written in Kotlin/Jetpack Compose. The full Go
backend is embedded in the app as a gomobile AAR and runs inside a foreground
service, so the app is completely standalone — same TMDB metadata, addon
streams, torrent engine, library, and Supabase auth/sync as the desktop app.
Playback uses libmpv (same player core as desktop) with subtitle/audio track
selection, external subtitles, intro/recap/credits skip, and up-next
auto-advance.

Alternatively, **Remote mode** (Settings → Server) points the app at a desktop
Cove on your LAN instead of the embedded server — pair with the token from the
desktop's remote-access settings.

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
4. **gomobile** — install once, then init with the NDK. gomobile looks up the
   `gobind` binary on PATH, so both installs are required (the `gobind` go.mod
   tool directive alone is not enough):
   ```sh
   go install golang.org/x/mobile/cmd/gomobile@latest
   go install golang.org/x/mobile/cmd/gobind@latest
   export ANDROID_NDK_HOME=$ANDROID_HOME/ndk/27.2.12479018
   gomobile init
   ```
5. **Secrets** — create `android/local.properties` (gitignored) with:
   ```
   sdk.dir=/home/<you>/Android/Sdk
   TMDB_API_KEY=your_key_here
   # Optional — enables in-app Supabase sign-in/sync (publishable anon key only):
   SUPABASE_URL=https://<project>.supabase.co
   SUPABASE_ANON_KEY=your_publishable_key
   # Optional, DEV ONLY — make the app talk to a host backend instead of the
   # embedded one (e.g. adb reverse + desktop Cove). WARNING: this points the
   # app at that backend's REAL library/settings data.
   # BACKEND_URL=http://127.0.0.1:6970/api
   ```
   These land in `BuildConfig` (see `app/build.gradle.kts`); all have safe
   empty/loopback defaults, so a bare `sdk.dir` file also builds fine.

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

## App structure

Single-Activity Compose app (`MainActivity` hosts a bottom-nav shell mirroring
the desktop's top bar: Home / My List / Explore / Search / Settings), plus a
separate landscape `PlayerActivity`. Packages under
`app/src/main/kotlin/com/coveninja/cove/`:

| Package | Contents |
|---|---|
| `ui/` | Screens + colocated ViewModels (Home hero pager & rows, Explore genre rows, Search, My List with status/type/sort filters), `MediaDetailSheet` (trailer, cast, similar, seasons/episodes with progress), `StreamsSheet` (ranked stream picker, `StreamRanking.kt` ports the desktop's selection logic) |
| `player/` | `PlayerActivity` + `MpvPlayerView` (libmpv via `dev.jdtech.mpv:libmpv`): track pickers, external subtitles, IntroDB segment skip, up-next auto-advance, resume, MediaSession/audio focus |
| `api/` | `CoveApiClient` (OkHttp singleton, base-URL + auth-token handling for Local/Remote modes), kotlinx-serialization DTOs mirroring the Go types |
| `auth/`, `sync/` | Supabase login/register/OTP, encrypted token store, `SyncCoordinator` (foreground-resume + post-mutation sync mirroring the desktop) |
| `service/` | `CoveService` — foreground service that owns the embedded Go server |

**Server modes:** Local (default) runs the embedded backend on
`127.0.0.1:6969`; Remote connects to a desktop Cove's LAN listener
(`:6970`) using its pairing token — switchable at runtime in Settings.

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
