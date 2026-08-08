# Contributing to Cove

Thanks for taking a look. This covers dev setup beyond the
[README](README.md)'s quick-start, what to expect around the proprietary
submodules, and the conventions this codebase already follows.

## Dev setup

Follow the README's "Build from source" section for prerequisites and the
first `make run`. A few things specific to iterating on the code:

- `make go` and `make app` build a single component at a time. `make run`
  builds both and launches the app. For Kotlin-only iterations you can also
  run `cd app && ./gradlew :desktop:run` directly.
- `make hot` is the preferred UI loop. It builds the Go sidecar, starts the
  Compose desktop app against it, and automatically recompiles and hot-reloads
  Kotlin UI changes while preserving the running window and most UI state.
- If you add a field to a Go struct mirrored in Kotlin (notably `Settings` →
  `AppSettings`), update both in the same change. `PUT /api/settings` is a
  whole-object replace — a missing field in `AppSettings` will write the Go
  zero value back on the next save. See `AppSettings.kt` in `app/shared`
  (`model/`) and the note in `CoveJson.kt` (`network/`) about
  `encodeDefaults = true`.
- `internal/nuvio` embeds pure-Go dependencies (`goja`, `goja_nodejs`,
  `andybalholm/brotli`) for its sandboxed JS runtime, so the OSS build stays
  `CGO_ENABLED=0` with no Node/V8 dependency. Keep any new dependency for that
  package pure Go for the same reason.

## Working without submodule access

`internal/discover` and `internal/supabase` each have two implementations,
switched at compile time by build tag: an open-source stub (`noop.go`,
compiled by a plain untagged Go command) and a proprietary implementation.
The Supabase auth/sync implementation is mirrored in `internal/supabase` so
tagged CI and contributors can compile it; releases still verify that it
matches `_private/cove-auth` byte-for-byte. The discovery implementation is
injected from `_private/cove-discover` by `make inject-private`. See
[ARCHITECTURE.md](ARCHITECTURE.md#the-ossproprietary-split) for the full
mechanism.

If you don't have private-submodule access, that's fine. Untagged
`go test ./...` exercises the public stubs; `go test -tags supabase ./...`
exercises the mirrored auth/sync implementation. Personalized discovery
remains on its stub unless the private discovery files are present. This
covers shared work in `internal/library`, `internal/settings`, `internal/tmdb`,
`internal/player`, `internal/addons`, and the frontend. If an interface changes
across a build-tag boundary, update the corresponding stub and tagged
implementation in the same change so both variants keep compiling.

When changing the mirrored Supabase files, update `_private/cove-auth` and
`internal/supabase` together, then run `bash scripts/check-private-sync.sh`.
Push CI and release builds intentionally fail if those copies diverge.

## Code style

- Comments should explain **why**, not what — the codebase leans on
  descriptive naming for the "what" and reserves comments for non-obvious
  constraints, workarounds, or the reasoning behind a magic number. Look at
  `internal/player/player.go` or `internal/tmdb/tmdb.go` for the tone to
  match.
- Every backend package that registers HTTP routes does so via a
  `SetupHandlers(mux *http.ServeMux)` method (or, for a couple of simpler
  packages, a package-level `SetupHandlers(mux, ...)` function) called once
  from `main.go`. Keep new routes consistent with that pattern rather than
  wiring `http.HandleFunc` calls elsewhere.
- `CoveApi.kt` in `app/shared` (`network/`) is the single point of contact
  with the backend from the Kotlin side — never construct a backend URL
  anywhere else. If you're adding a new endpoint, add its method there
  alongside the existing ones for that package's routes.
- Go doc comments: every package should have a `// Package x ...` comment
  explaining its purpose and any non-obvious constraint (see
  `internal/clientsession/clientsession.go` for the bar to hit — a couple of
  sentences that explain *why* the package exists, not just what it's
  called).

## Testing

```sh
make test      # Go + Kotlin test suites; recommended before a PR
make test-all  # add workflow/security checks and cross-platform builds
```

The individual `test-go`, `test-kotlin`, `test-build`, `test-workflows`, and
`test-security` targets are useful when changing one component.
`test-workflows` requires ShellCheck so actionlint performs the same
embedded-shell analysis as CI.

```sh
make test-private  # maintainers, after make inject-private
```

The pull-request workflow runs public and Supabase-tagged Go tests with
coverage, vet/format checks, the race detector, Linux/Windows compilation,
the Kotlin shared/desktop test suite, dependency review, and vulnerability
scans. Private `supabase,discover` integration tests additionally run on
trusted pushes where the private-submodule token is available. Coverage is
uploaded to Codecov and retained as downloadable workflow artifacts; it is
currently informational, with no hard percentage gate.

See [`docs/TESTING.md`](docs/TESTING.md) for the complete job matrix, coverage
artifacts, and release gating.

## Before opening a PR

- Run the build/test/lint commands above for whatever you touched.
- If you changed a Go struct mirrored in Kotlin (e.g. `Settings` →
  `AppSettings`), confirm both the Go and Kotlin sides are updated together.
- Keep the scope focused — this repo doesn't have issue/PR templates yet, so
  a clear description of *why* the change is needed (not just what changed)
  in the PR body goes a long way.
