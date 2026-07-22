# Contributing to Cove

Thanks for taking a look. This covers dev setup beyond the
[README](README.md)'s quick-start, what to expect around the proprietary
submodules, and the conventions this codebase already follows.

## Dev setup

Follow the README's "Build from source" section for prerequisites and the
first `make run`. A few things specific to iterating on the code:

- `make hot` is the tightest loop: it builds the Go backend and Qt shell, then
  runs Vite with HMR served in-window via the shell's `--dev` mode — frontend
  changes apply live without a full rebuild. `make hot-debug` adds QtWebEngine
  remote devtools on `:9222`.
- `make web-dev` runs a browser-only Vite dev server with no Qt shell at all.
  The player will show "unavailable" (no `QWebChannel`/mpv bridge exists in a
  plain browser), but everything else — search, library, settings, addons —
  works against the real Go backend.
- After changing a Go struct exported to the frontend (see every package in
  `tygo.yaml`), run `make generate` to regenerate the mirrored TypeScript
  types in `web/src/lib/types/*.ts`. **Never hand-edit those generated files**
  — they're overwritten on the next generation pass. `time.Time` fields must
  have a `string` mapping in `tygo.yaml`, matching their JSON encoding.
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
- `web/src/lib/api.ts` is the single point of contact with the backend from
  the frontend — never construct a backend URL anywhere else. If you're
  adding a new endpoint, add its method there alongside the existing ones for
  that package's routes.
- Go doc comments: every package should have a `// Package x ...` comment
  explaining its purpose and any non-obvious constraint (see
  `internal/clientsession/clientsession.go` for the bar to hit — a couple of
  sentences that explain *why* the package exists, not just what it's
  called).

## Testing

```sh
make test      # complete Go + web suite; recommended before a PR
make test-all  # add workflow/security checks plus Qt and Android builds
```

The individual `test-go`, `test-web`, `test-build`, `test-workflows`,
`test-security`, `test-qt`, and `test-android` targets are useful when changing
one component. The web browser suite needs a one-time
`cd web && npx playwright install chromium` setup. `test-workflows` also
requires ShellCheck so actionlint performs the same embedded-shell analysis as
CI.

```sh
make test-private            # maintainers, after make inject-private
make test-android-connected  # with an Android device/emulator running
```

Run Prettier before committing frontend changes:

```sh
cd android
npm run format
```

The pull-request workflow runs public and Supabase-tagged Go tests with
coverage, vet/format checks, the race detector, Linux/Windows compilation,
Vitest/Playwright/typecheck/lint/build, a Qt build, Android lint/JVM tests and
an API 35 emulator smoke test, dependency review, and vulnerability scans.
Private `supabase,discover` integration tests additionally run on trusted
pushes where the private-submodule token is available. Coverage is uploaded
to Codecov and retained as downloadable workflow artifacts; it is currently
informational, with no hard percentage gate.

See [`docs/TESTING.md`](docs/TESTING.md) for the complete job matrix, coverage
artifacts, Android emulator setup, and release gating.

## Before opening a PR

- Run the build/test/lint commands above for whatever you touched.
- If you changed a Go struct consumed by the frontend, confirm you also ran
  `make generate` and committed the regenerated `.ts` files.
- Keep the scope focused — this repo doesn't have issue/PR templates yet, so
  a clear description of *why* the change is needed (not just what changed)
  in the PR body goes a long way.
