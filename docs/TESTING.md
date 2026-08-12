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

## Packaged images and smoke runs

```sh
cd app
./gradlew :desktop:createDistributable --no-daemon
./gradlew :desktop:run --args='--smoke-seconds 3' --no-daemon
```

The distributable check verifies the single-JVM packaging graph and bundled
runtime. The smoke run needs a graphical session, libmpv, and a usable
`TMDB_API_KEY`. When validating an artifact, inspect the generated image under
`app/desktop/build/compose/binaries/main/app/Cove` and confirm it does not
contain a backend sidecar.

For the shared phone/tablet UI:

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
and process survival after opening details. The connected tests run downloaded
scraper code in the isolated QuickJS service through its pipe-based fetch broker
and initialize the bundled yt-dlp runtime without downloading a helper. The
mobile manifest intentionally requires a touchscreen and must not gain a
Leanback launcher; the eventual TV host has its own UI.

## Workflow checks

```sh
make test-workflows
```

This runs release-note filtering, the local-action fixture checks, ShellCheck,
and actionlint. Local actionlint and ShellCheck binaries are required. CI uses
the pinned actionlint container.

## CI matrix

| Job | Purpose |
|---|---|
| Workflow lint | Release-note behavior and GitHub Actions syntax/shell analysis |
| Kotlin desktop/mobile build and test | Full Gradle build/test plus Android lint/APK assembly |
| Android phone launch smoke | Install and start the shared mobile UI on an API 35 emulator |
| Dependency review | Pull-request dependency policy and known-vulnerability review |

GitHub's default CodeQL setup and Dependabot cover Kotlin/Java, Gradle, and
workflow dependencies. There is no active Go job or private-source gate.

## Release gates

A version tag must first pass the complete Kotlin desktop/mobile build/test job. Linux release
packaging then builds one Compose distributable, a tarball/PKGBUILD, and a
Flatpak. Windows packaging builds the same application graph, adds the mpv DLL,
and produces the portable zip plus NSIS installer. Android packaging signs and
publishes `cove-android.apk` plus its SHA-256 checksum. Deployment keys are
supplied to the desktop resource or Android `BuildConfig` during packaging.

The release workflow must not fetch private source submodules or stage a second
backend executable. Platform package managers own updates; the zip checksum is
an integrity artifact, not an in-app self-update protocol.

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
