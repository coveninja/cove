# Testing and continuous integration

This document describes Cove's local checks, GitHub Actions matrix, coverage
reporting, and release gates. Contributor conventions live in
[`CONTRIBUTING.md`](../CONTRIBUTING.md).

## Local checks

For the normal pre-PR suite, run:

```sh
make test
```

This runs the public and Supabase-tagged Go checks plus the complete web unit,
type, lint, build, and browser suite. For the broadest local approximation of
CI, including workflow/security checks and Qt/Android compilation, run:

```sh
make test-all
```

The first browser-test run needs `npx playwright install chromium` from
`web/`. `make test-all` additionally requires network access, Qt/libmpv, the
Android SDK/NDK, gomobile, JDK 17, and ShellCheck. On Arch Linux, install the
last dependency with `sudo pacman -S shellcheck`.

### Go

```sh
# Public/no-op implementation
go test -count=1 ./...
go vet ./...

# Checked-in Supabase auth/sync implementation
go test -count=1 -tags supabase ./...
go vet -tags supabase ./...
go test -count=1 -race -tags supabase ./...

# Exact private release combination (maintainers, after make inject-private)
go test -count=1 -tags supabase,discover ./...
go vet -tags supabase,discover ./...
```

Run `gofmt` on changed Go files. CI checks every tracked `.go` file and rejects
an unformatted tree. Changes to mirrored Supabase source must also pass:

```sh
bash scripts/check-private-sync.sh
```

### Web

```sh
cd web
npm ci
npm test
npm run check
npm run lint
npm run build
npx playwright install chromium  # first local run only
npm run test:e2e
npm audit --audit-level=low
```

Vitest covers API request invariants and automatic sync behavior. Playwright
uses a mocked Go API and covers degraded startup, login/session persistence,
profile activation, and sync push-error presentation.

### Qt

```sh
cmake -S qt -B qt/build -G Ninja -DCMAKE_BUILD_TYPE=Release
cmake --build qt/build
```

### Android

Build `android/app/libs/cove.aar` first if it is absent or its Go/web inputs
changed:

```sh
make android-aar
cd android
./gradlew lintDebug testDebugUnitTest assembleDebugAndroidTest
```

With an API 35 emulator running, execute the activity launch test with:

```sh
make test-android-connected
```

Maintainers can add the private release-tag checks after injection with
`make test-private`. These two environment-dependent targets are intentionally
not prerequisites of `make test-all`.

## Pull-request and branch CI

`.github/workflows/ci.yml` runs on every branch push, version tag, pull
request, and manual dispatch.

| Job                  | What it protects                                                                       |
| -------------------- | -------------------------------------------------------------------------------------- |
| Workflow lint        | Invalid GitHub Actions workflow definitions                                            |
| Go test matrix       | Public and `supabase` variants, gofmt, vet, and coverage                               |
| Go race              | Concurrency bugs in the Supabase-tagged build                                          |
| Go build matrix      | Static Linux and Windows compilation                                                   |
| Private integrations | Trusted pushes only: source-copy check plus `supabase,discover` test/vet               |
| Web                  | Vitest coverage, Svelte typecheck, ESLint, production build, npm audit, and Playwright |
| Qt                   | Blocking Ubuntu QtWebEngine/libmpv configure and build                                 |
| Android              | Fresh gomobile AAR, lint, JVM tests, and API 35 emulator launch                        |
| Dependency review    | Vulnerable dependency changes introduced by pull requests                              |
| govulncheck          | Reachable Go vulnerability scan                                                        |

GitHub CodeQL default setup analyzes Actions, C/C++, Go, and
JavaScript/TypeScript without a competing advanced workflow. Dependabot checks
Go modules, npm, Gradle, and GitHub Actions weekly.

## Coverage

Go jobs upload `coverage-public.out` and `coverage-supabase.out`; Vitest writes
`web/coverage/lcov.info`. Raw reports remain downloadable from each workflow
run even if the external coverage upload is unavailable.

The CI jobs publish all three reports to Codecov using GitHub OIDC, so no
`CODECOV_TOKEN` repository secret is required. The README badge reflects the
combined default-branch report. Coverage is intentionally informational while
the baseline is being expanded—CI does not yet reject a change based on a
percentage. If a new repository shows an unknown badge after its first
successful `master` run, enable that repository in the Codecov dashboard.

## Release gates

`.github/workflows/release.yml` publishes only for `v*` tags; manual dispatch
runs its shared private Go validation without creating a release. A tagged
GitHub release is created only after public/private Go tests, the tagged build,
web unit/browser/type/lint/build/audit checks, and the Linux Qt build succeed.
Linux/Flatpak, Windows, and Android packaging then upload their assets with
job-scoped `contents: write`; the workflow default remains read-only. Android
packaging reruns lint and JVM tests before signing.

Coverage artifacts are diagnostic rather than release assets.
