# Testing and release checks

## Fast path

From the repository root:

```sh
make test
```

This delegates to `app/gradlew test` and covers common repositories/models, the
backend desktop and Android host targets, UI/common tests, mobile artifact
contracts, desktop launcher/options, and player logic.

Focused examples:

```sh
cd app
./gradlew :backend:desktopTest --tests '*LegacyMigrationTest'
./gradlew :backend:desktopTest --tests '*CoreRoutesTest'
./gradlew :backend:desktopTest --tests '*MediaBoundaryTest'
./gradlew :desktop:test
./gradlew :mobile:testDebugUnitTest
```

Use `--no-daemon` for CI-equivalent runs. Tests that exercise config or stores
should use temporary directories and mock HTTP engines; they must not read a
developer's real account, `.env`, or SQLite database.

## Choose tests by subsystem

| Changed behavior | Focused tests | Additional evidence |
|---|---|---|
| Shared models or settings | Owning shared/common test | Serialization/default compatibility |
| SQLDelight schema or legacy import | Migration and repository tests | Upgrade from every supported prior schema |
| HTTP route | `CoreRoutesTest` filter | Status, body, headers, size limits, and legacy-prefix parity |
| Account sync or Trakt | Owning backend service tests | Conflict, offline, auth-expiry, and profile-scope cases |
| Addons or Nuvio | Manager/runtime tests | URL policy, invalidation, timeout, and sandbox behavior |
| Desktop plugin | Package, manager, process, and protocol tests | Permission denial, shutdown, staged update, and result sanitization |
| Shared Compose state | Owning `:ui` test | Phone/tablet behavior and stable state restoration |
| Desktop native player | `:desktop:test` filter | Live mpv smoke on the affected OS/GPU path |
| Android player or services | Mobile unit/connected tests | Physical device or emulator lifecycle evidence |
| TV UI | Shared TV tests | Desktop harness plus Android TV D-pad smoke |
| Packaging or updater | Artifact contract and updater tests | Disposable packaged-image install/replace exercise |

Green JVM tests establish contract safety; they do not prove that a native
decoder, CoreAudio device change, graphics driver, Android service lifecycle, or
nondeterministic shutdown race is resolved. Name the required runtime soak and
platform when the test environment cannot provide it.

## Documentation checks

Versioned website guides use `docs/site/manifest.json` as their public contract:

```sh
make test-site-docs
```

The validator checks manifest structure, source ownership, H1/title agreement,
relative links, and corpus limits. Run it whenever a guide is added, renamed, or
linked. Contributor Markdown is not served through that manifest, so also review
its relative links and finish every documentation change with `git diff --check`.

## Packaged images and smoke runs

```sh
cd app
./gradlew :desktop:createDistributable --no-daemon
./gradlew :desktop:run --args='--smoke-seconds 3' --no-daemon
```

The distributable check verifies the single-JVM packaging graph and bundled
runtime. The command above uses fixtures and needs only a graphical session and
libmpv. To smoke the live graph, add `--backend-mode kotlin` to the arguments and
provide a usable `TMDB_API_KEY`. When validating an artifact, inspect the
host-specific image under `app/desktop/build/compose/binaries/main/app/` and
confirm it does not contain a backend sidecar.

For the Android touch UI:

```sh
cd app
./gradlew :mobile:lintDebug :mobile:assembleDebug --no-daemon
./gradlew :mobile:installDebug --no-daemon
./gradlew :mobile:connectedDebugAndroidTest --no-daemon
adb shell am start -S -W -n com.coveninja.cove/.MainActivity
adb shell pidof com.coveninja.cove
```

Use a phone/tablet AVD on API 28 or newer. Verify edge-to-edge system bars,
compact hero actions, touch scrolling/long-press behavior, real TMDB loading,
and process survival after opening details. The connected tests exercise
targeted Compose navigation/source-picker behavior, run scraper code in the
isolated QuickJS service through its pipe-based fetch broker, and initialize the
bundled yt-dlp runtime without downloading a helper.

One APK also contains the TV root. Exercise it in the desktop harness while
iterating, then install that same APK on a TV image for platform behavior:

```sh
make run-tv             # desktop harness: arrows/Enter/Escape act as the remote
make tv-avd             # one-time Android TV AVD creation
emulator -avd cove-tv -gpu host
make tv-install
```

On the Android TV device, verify launcher/banner visibility, initial focus,
D-pad traversal, Enter/Back handling, focus restoration after overlays, and
playback controls. The manifest must keep both touchscreen and Leanback optional
and retain both launcher categories so the single APK remains installable and
discoverable on every Android form factor. CI currently smoke-launches a phone
image; TV runtime behavior remains an explicit manual/device check.

### Scraper and plugin isolation

Nuvio tests must exercise JavaScript compatibility, bounded fetches, blocked
private targets, timeouts, malformed results, and worker cleanup. Android
connected tests should cross the isolated-process pipe; a JVM fake does not
prove the Android service boundary.

Desktop plugin validation covers package, manager, and worker layers:

```sh
cd app
./gradlew :backend:desktopTest --tests '*PluginPackagesTest' --no-daemon
./gradlew :backend:desktopTest --tests '*DesktopPluginManagerTest' --no-daemon
./gradlew :backend:desktopTest --tests '*PluginProcessTest' --no-daemon
```

Also test the visible permission flow with an ephemeral local package. Never use
the production catalog signing key in tests. A shutdown or update race needs
repeated worker start/stop and staged-activation coverage on the affected
desktop OS; one green Linux execution is not proof of macOS timing.

### Playback and media boundaries

Separate source discovery, registration, and native playback when diagnosing a
failure. Route tests can prove redirects, headers, range requests, subtitle
conversion, torrent selection, and progress contracts. Only a live player can
prove surface rendering, hardware decoding, audio-device behavior, track
selection, or local subtitle loading.

For segment skipping, cover embedded chapters alone, external timestamps alone,
both sources with overlapping kinds, manual skip, automatic skip, seeking back,
and up-next interaction. Embedded media must win per matching segment type.

## Workflow checks

```sh
make test-workflows
```

This runs release-note filtering, the local-action fixture checks, ShellCheck,
and actionlint. Local actionlint and ShellCheck binaries are required. CI
downloads and checksum-verifies its pinned actionlint binary.

## CI matrix

| Job | Purpose |
|---|---|
| Workflow lint | Release-note behavior and GitHub Actions syntax/shell analysis |
| Kotlin desktop/mobile build and test | Full Gradle build/test plus Android lint/APK assembly |
| macOS native build and test | Desktop/backend tests plus an Apple-silicon application image check |
| Android phone launch smoke | Install and start the shared mobile UI on an API 35 emulator |
| Dependency review | Pull-request dependency policy and known-vulnerability review |

The tracked Dependabot configuration covers Gradle and GitHub Actions
dependencies, while pull requests also run dependency review. There is no
active Go job or private-source gate.

## Release gates

A version tag must first pass the complete Kotlin desktop/mobile build/test job.
Linux release packaging then builds one Compose distributable, a
tarball/PKGBUILD, and a Flatpak. Windows packaging builds the same application
graph, adds the mpv DLL, and produces the portable zip plus NSIS installer.
macOS packaging builds an Apple-silicon app, bundles and ad-hoc signs its native
libmpv closure, and publishes a DMG. Android packaging signs the one
phone/tablet/TV APK and publishes `cove-android.apk` plus its SHA-256 checksum.
Deployment keys are supplied to desktop resources or Android `BuildConfig`
during packaging.

The release workflow must not fetch private source submodules or stage a second
backend executable. It publishes no GitHub release until every platform package
exists, creates one canonical update manifest with exact asset sizes and SHA-256
digests, signs those exact bytes with Ed25519, verifies that signature, and only
then publishes all assets atomically. Windows installed/portable helpers and the
Android APK are the in-app update payloads; AUR remains package-manager managed
and standalone Flatpak bundles remain manual.

Focused updater coverage uses an ephemeral Ed25519 key and mock HTTP engine:

```sh
cd app
./gradlew :backend:desktopTest --tests '*SignedUpdateServiceTest' --no-daemon
./gradlew :mobile:testDebugUnitTest --tests '*MobileArtifactContractTest' --no-daemon
```

Before tagging, also compile both NSIS modes on Windows and exercise replacement
failure/rollback in a disposable installed and portable directory. A release
candidate APK should be installed over the previous production APK on a device
signed with the production certificate; the system must accept the upgrade and
the post-update notification must open the new version.

## Final local checklist

For a backend or release refactor:

```sh
cd app
./gradlew test --no-daemon
./gradlew :mobile:lintDebug :mobile:assembleDebug --no-daemon
cd ..
git diff --check
```

Also run `:desktop:createDistributable` when dependencies, entry points,
resources, native libraries, or packaging changed. Run the desktop GUI and
Android emulator smoke tests when the relevant host changed. Document an
environmental failure instead of treating it as a product regression.
