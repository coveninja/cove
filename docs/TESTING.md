# Testing and continuous integration

This document describes Cove's local checks, GitHub Actions matrix, coverage
reporting, and release gates. Contributor conventions live in
[`CONTRIBUTING.md`](../CONTRIBUTING.md).

## Local checks

For the normal pre-PR suite, run:

```sh
make test
```

This runs the public and Supabase-tagged Go checks plus the Kotlin
shared/desktop test suite. For the broadest local approximation of CI,
including workflow/security checks and cross-platform compilation, run:

```sh
make test-all
```

`make test-all` additionally requires network access, libmpv, JDK 17, and
ShellCheck. On Arch Linux, install the last dependency with
`sudo pacman -S shellcheck`.

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

### Kotlin

```sh
cd app
./gradlew test
```

This runs both the `shared` (KMP) and `desktop` (JVM) test suites. The
`shared` suite covers `CoveApi` request invariants, `ImageUrls` proxied-vs-raw
path handling, `LiveSettingsRepository` round-trip correctness, and fixture
wiring. The `desktop` suite covers `BackendSupervisor` lifecycle,
`RestartPolicy` crash-budget accounting, `SingleInstanceLock`, `LaunchOptions`
parsing, and mpv binding smoke tests.

Maintainers can add the private release-tag checks after injection with
`make test-private`. This target is intentionally not a prerequisite of
`make test-all`.

## Pull-request and branch CI

`.github/workflows/ci.yml` runs on every branch push, version tag, pull
request, and manual dispatch.

| Job                  | What it protects                                                          |
| -------------------- | ------------------------------------------------------------------------- |
| Workflow lint        | Invalid GitHub Actions workflow definitions                               |
| Go test matrix       | Public and `supabase` variants, gofmt, vet, and coverage                  |
| Go race              | Concurrency bugs in the Supabase-tagged build                             |
| Go build matrix      | Static Linux and Windows compilation                                      |
| Private integrations | Trusted pushes only: source-copy check plus `supabase,discover` test/vet  |
| Kotlin               | `shared` and `desktop` test suites (JVM target)                           |
| Dependency review    | Vulnerable dependency changes introduced by pull requests                 |
| govulncheck          | Reachable Go vulnerability scan                                           |

GitHub CodeQL default setup analyzes Actions, C/C++, Go, and Kotlin/Java
without a competing advanced workflow. Dependabot checks Go modules, Gradle,
and GitHub Actions weekly.

## Coverage

Go jobs upload `coverage-public.out` and `coverage-supabase.out`. Raw reports
remain downloadable from each workflow run even if the external coverage upload
is unavailable.

The CI jobs publish Go coverage to Codecov using GitHub OIDC, so no
`CODECOV_TOKEN` repository secret is required. The README badge reflects the
combined default-branch report. Coverage is intentionally informational while
the baseline is being expanded—CI does not yet reject a change based on a
percentage. If a new repository shows an unknown badge after its first
successful `master` run, enable that repository in the Codecov dashboard.

## Release gates

`.github/workflows/release.yml` publishes only for `v*` tags; manual dispatch
runs its shared private Go validation without creating a release. A tagged
GitHub release is created only after public/private Go tests, the tagged build,
and the Kotlin test suite succeed. Linux/Flatpak and Windows packaging then
upload their assets with job-scoped `contents: write`; the workflow default
remains read-only. `make patch`, `make minor`, and `make major` generate the
release's linked change list from conventional `fix` and `feat` commit subjects
only; run `make test-release-notes` to verify that filter locally.

Coverage artifacts are diagnostic rather than release assets.
