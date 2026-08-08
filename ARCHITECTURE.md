# Architecture

This document explains how Cove is put together: the three cooperating
components, how data flows through them, and the build-tag mechanism that
separates the open-source core from proprietary functionality. It assumes
you've read the [README](README.md) for the user-facing feature list and
build instructions.

For an API endpoint reference, see [docs/API.md](docs/API.md). For dev setup
and contribution conventions, see [CONTRIBUTING.md](CONTRIBUTING.md).

## Two components at a glance

Cove is two cooperating processes, not one monolith:

1. **Go backend** (`main.go` + `internal/`) — an HTTP server on `:6969`
   handling TMDB metadata, streaming, addon integration (Stremio-style
   providers/subtitles), sandboxed community plugin execution, the local
   library/settings/profiles stores, and personalized recommendations.
2. **Compose Multiplatform desktop app** (`app/`) — a Kotlin/JVM Compose
   Multiplatform application that spawns the Go binary as a child process and
   talks to it over HTTP. Structured as three Gradle subprojects: `shared`
   (KMP library with domain models, repositories, and Ktor network layer),
   `ui` (Compose Multiplatform screens — intentional scaffolding, the
   maintainer is writing the real UI), and `desktop` (the JVM entry point,
   backend lifecycle management, and libmpv player integration via JNA).

At startup, `app/desktop`'s `BackendSupervisor` spawns the Go binary as a
child process (capturing stdout/stderr), polls `:6969` via `ReadinessProbe`
(a TCP connect every 250ms — a connectivity check, not an HTTP health check)
until the backend answers, then hands control to the Compose UI. The Go
backend polls `COVE_PARENT_PID` and exits when its parent JVM process
disappears (`monitorParent` in `main.go`) — the JVM cannot use
`PR_SET_PDEATHSIG`/Job Objects for child teardown, so the backend does it
itself.

## Backend package map (`internal/*`)

- **`tmdb`** — the TMDB API client and the largest single set of HTTP routes
  (search, details, images, videos, providers, similar-titles, genre lists,
  a batched quality-probe endpoint). Client/domain code lives in `tmdb.go`;
  HTTP adapters live in `tmdb_handlers.go`. No build-tag variance; always
  compiled the same way. See `docs/API.md` for the full route list.
- **`library`** — the local watch history / ratings / "not interested" store,
  persisted as `library-<profileID>.json` under the OS config dir
  (`internal/utils.ConfigPath`). Exposes `TasteSignals()` and `Generation()`
  — the interface the recommendation engine consumes without either package
  importing the other.
- **`settings`** — a single flat `Settings` struct, same persistence pattern
  as `library` (`settings-<profileID>.json`), whole-object GET/PUT over
  `/api/settings`. Select-style preferences (stream selection mode, discovery
  algorithm) are plain strings with no server-side enum validation — the
  frontend owns the allowed-value metadata.
- **`addons`** — the Stremio-compatible provider/subtitle addon manager. Two
  "official" addons (JustWatch availability, IntroDB timestamps) are hardcoded
  Go integrations; anything else is a user-pasted Stremio manifest URL,
  fetched and classified by resource type at add-time. Fan-out across
  multiple enabled addons of the same kind runs concurrently under a shared
  deadline, with per-addon failures swallowed (non-fatal, matching the
  "addon failures don't break the app" principle).
- **`player`** — owns the `anacrolix/torrent` client and streams the selected
  file in a torrent as seekable HTTP (`http.ServeContent`, so mpv's Range
  requests just work). Torrent/session code lives in `player.go`; HTTP
  adapters live in `player_handlers.go`. See "Playback data flow" below —
  there is no transcoding here.
- **`nuvio`** — runs community-maintained JS scraper plugins in a sandboxed
  `goja` runtime (pure Go, no CGO, no Node) to produce additional direct-HTTP
  stream candidates alongside `addons`. Off by default: a plugin repo must be
  added and each scraper individually enabled by the user. One fresh
  runtime per invocation, bounded by a per-scraper timeout and an overall
  deadline for the whole batch; per-scraper errors/timeouts are logged and
  skipped, matching `addons`'s swallow-per-failure philosophy. Called only
  from `player.go`'s `/api/streams` handler — deliberately excluded from
  `tmdb.go`'s batched `/api/quality/batch` endpoint, which fans out over
  every title in a discovery grid and can't afford a JS runtime + network
  call per tile.
- **`profiles`** — Netflix-style local user profiles (not to be confused with
  content-rating). Switching the active profile reloads `library`,
  `settings`, and `addons` in place via a callback registered in `main.go`.
- **`updater`** — self-update via GitHub releases; skips the check entirely
  on managed distributions (`APPIMAGE`/`FLATPAK_ID` env vars set) or dev
  builds (non-semver version string). Applying an update exits the process
  with code `42`, which `BackendSupervisor` intercepts as a clean restart
  sentinel rather than a crash (no crash-budget slot consumed).
- **`clientsession`** — a tiny opaque JSON blob store
  (`os.UserConfigDir()/cove/session.json`) used for client-side auth token
  persistence. The original motivation was QtWebEngine's unreliable
  `localStorage`; the same dedicated server-side store is equally useful for
  a native Compose desktop app with no DOM storage layer.
- **`utils`** — shared infrastructure: per-OS config paths and atomic writes,
  bounded expiring caches, debounced persistence and background schedules,
  request parameter/method validation, JSON responses, UUID creation, media
  validation, and local TMDB image URL rewriting. HTTP handlers use these
  helpers instead of carrying package-local variants.
- **`discover`** and **`supabase`** — see "The OSS/proprietary split" below;
  these are the two packages with a compile-time swap between an open-source
  stub and proprietary functionality.

**`internal/anet-patch` — vendored Android network fix.** The Go standard
library's `net.Interfaces()` and `net.InterfaceAddrs()` fail with
`route ip+net: netlinkrib: permission denied` on Android 11+ because NETLINK
socket operations are now restricted for ordinary apps (see the `wlynxg/anet`
upstream README and [golang/go#40569](https://github.com/golang/go/issues/40569)).
The `anet` package provides replacement implementations that work within
Android's restrictions. However, `wlynxg/anet` uses `//go:linkname` directives
to access `net.zoneCache` and `golang.org/x/net/internal/socket.zoneCache`,
and Go 1.23+ rejects `//go:linkname` references to symbols that have not opted
in. Rather than pass `-checklinkname=0` globally, the codebase carries a local
fork at `internal/anet-patch` that removes the linkname directives entirely.
IPv6 zone-ID caching is the only functionality removed; it has no impact on
the torrent and HTTP streaming use cases. The fork is wired in via a `replace`
directive in `go.mod` (`replace github.com/wlynxg/anet => ./internal/anet-patch`)
so the rest of the dependency graph sees it as the normal upstream module.

## Playback data flow
1. The frontend requests candidate streams for a title via `GET /api/streams`
   (the `/api/streams` handler in `internal/player/player_handlers.go`), which fans out to
   `addons.Manager.GetAllStreams` — each enabled provider addon contributes
   infohashes and/or direct URLs — and, if any Nuvio scrapers are enabled,
   `nuvio.Manager.GetStreams`, which runs each one in its own sandboxed goja
   runtime and appends whatever direct-HTTP streams they produce.
2. The Compose UI picks a source (auto-ranked or user-chosen) and builds either
   `/api/play?hash=<infohash>` or passes a direct URL through unchanged.
3. `DesktopPlayer` issues an mpv `loadfile` command via the JNA bindings in
   `app/desktop/player/Mpv.kt`. Loading is deferred until the render context
   exists — doing it before that silently drops video for that file.
4. **mpv itself opens the URL as an HTTP client**, hitting the Go backend's
   `GET /api/play` route directly (Qt/QML plays no part in the actual byte
   transfer):
   - `?url=<direct>` → `307 Temporary Redirect` straight to the origin
     server; the Go process isn't in the data path at all (in the `/api/play`
     handler in `internal/player/player_handlers.go`).
   - `?hash=<infohash>` → `Player.StreamTorrent` resolves which file to stream
     via `selectFile()` in `internal/player/player.go` (with a 45s
     metadata-fetch timeout). For a movie, or when nothing matches, that is
     simply the largest video file; for a TV episode it tries increasingly
     loose filename patterns — `S01E02`, then `1x02`, then a bare episode
     marker, then an anime-style bare number — so season-pack torrents stream
     the right episode. The decision logic sits in `selectFileIndex()`, a pure
     function over `(path, length)` pairs, which is what makes it unit-testable
     (`torrent.File` has no exported constructor). It then
     opens a responsive/16MiB-readahead reader, and calls
     `http.ServeContent`. Range-request seeking works because
     `http.ServeContent` handles `Range:` headers and the anacrolix reader's
     `io.ReadSeeker` reprioritizes piece downloads around the seeked offset.
     Every mpv seek opens a *new* HTTP request (and thus a new reader) —
     closing the old reader on handler return matters because anacrolix
     readers hold download priority until closed.
5. mpv decodes and renders every codec/container it natively supports — no
   transcoding step exists to bridge format gaps.

**The torrent reaper.** `CleanupTorrents()` (in `internal/player/player.go`)
runs on a 30-minute ticker (in `main.go`). A torrent is dropped and its on-disk
pieces deleted (from `os.TempDir()/cove-torrents`) only if its reader count is
`<= 0` **and** it hasn't been used in the last 30 minutes. The reader count is
incremented for the duration of `StreamTorrent` and `lastUsed` is also
refreshed by `GetProgress` (so an open progress-bar poll counts as activity)
— together these protect a torrent that's actively being watched, or whose
progress UI is still open, from being collected mid-watch. Without this
reaper, downloaded pieces and open file handles would accumulate for the life
of the process.

An earlier browser-video + HLS.js architecture (predating the current mpv
integration) left behind an unused `Settings.PreferHLS` field and a family
of HLS-related API helpers and WebVTT cue-tracking code in the old frontend.
All of this has since been removed — mentioned here only so a future
spelunk through git history for "HLS" doesn't look like it's chasing a
still-live feature.

## The Compose Multiplatform app (`app/`)

The app is structured as three Gradle subprojects that share a single Gradle
build in `app/`:

**`shared/`** is a KMP library with a `jvm("desktop")` target (structured
so an Android target would be a small addition later). It contains:
- `model/` — `AppSettings`, `Media`, `LibraryEntry`, `WatchProgress`, and
  the other domain types that mirror Go structs.
- `data/` — repository interfaces (`ContentRepository`, `LibraryRepository`,
  `SettingsRepository`) exposing `StateFlow<Loading|Ready|Failed>` sealed
  states, `Live*` implementations over a Ktor HTTP client, and `Fixture*`
  implementations for running with no backend.
- `network/` — `CoveApi` (Ktor-based HTTP client), `CoveJson` (the
  `kotlinx.serialization` `Json` instance — `encodeDefaults = true` is
  mandatory, see below), `ImageUrls` (handles the inconsistent proxied-vs-raw
  TMDB path distinction), and `WireMappers` (response → domain transforms).

**`ui/`** is the Compose Multiplatform layer: `CoveTheme` design tokens,
`AppRoute` sealed class, a hand-rolled back stack (deliberately no navigation
library), and five placeholder screens (`HomeScreen`, `SearchScreen`,
`LibraryScreen`, `SettingsScreen`, `ExploreScreen`). **This layer is
intentional scaffolding** — the maintainer is designing and writing the real
UI themselves. Do not mistake the placeholder screens for finished product.

**`desktop/`** is the JVM entry point:
- `Main.kt` — Compose `application` block; shows `BackendState` before
  handing off to the real UI.
- `LaunchOptions.kt` — CLI flag and environment parsing.
- `backend/` — `BackendSupervisor` (child process management, crash/restart
  loop, exit-42 handling), `ReadinessProbe` (TCP polling), `RestartPolicy`
  (sliding crash budget: 3 crashes per 60 s), `SingleInstanceLock` (prevents
  two desktop processes from starting the same backend binary),
  `BackendProcessFactory`.
- `player/` — `DesktopPlayer` (the Compose-facing player interface), `Mpv`
  (JNA bindings to libmpv), `MpvOpenGlPanel` (JOGL `GLJPanel` bridged via
  `SwingPanel` for the hardware OpenGL path), `MpvOpenGlPlayer`,
  `MpvSoftwarePlayer` (`bgr0` frame capture → `BufferedImage` → Compose
  `Canvas`, selected by `--software-renderer`), `PlayerSnapshot`.

**libmpv in the JVM — four traps, each of which fails silently:**

1. **`setlocale(LC_NUMERIC, "C")` before `mpv_create`** — AWT sets the
   process-wide C locale at JVM startup. libmpv parses floating-point values
   with `strtod` and misreads decimal separators if the locale is not `C`.
   The call and the OS-specific constant for `LC_NUMERIC` live in `Mpv.kt`.

2. **`GLJPanel.setSkipGLOrientationVerticalFlip(true)`** — JOGL's FBO
   compositing applies a vertical flip by default to compensate for OpenGL's
   bottom-left origin. mpv's FBO is already correctly oriented (it renders
   right-side-up for screen display). Skipping the flip is mandatory or video
   renders upside-down. See `MpvOpenGlPanel`.

3. **Rebind the JOGL FBO after `mpv_render_context_render`** — mpv leaves
   framebuffer 0 bound when the call returns. The JOGL compositing listener
   exits expecting the panel's own FBO to be bound; failing to rebind it
   immediately after the mpv render call breaks compositing silently, typically
   showing a black or corrupted frame. See `MpvOpenGlPanel`.

4. **The mpv render update callback must only `EventQueue.invokeLater`** —
   the callback fires on mpv's internal render thread. Calling any AWT/Swing
   state directly from that thread causes silent data races or deadlocks.
   The callback must only schedule a repaint via `EventQueue.invokeLater`.

**Settings round-trip correctness.** `AppSettings` in `shared/model/` mirrors
Go's `Settings` struct field-for-field (currently 35 fields). `PUT
/api/settings` is a whole-object replace — any field absent from the body is
written as its Go zero value. `CoveJson` sets `encodeDefaults = true`; if that
is removed, any field holding its Kotlin default will be silently omitted and
the round-trip will clobber user data with no error. If a field is added to
the Go struct it must be added to `AppSettings` in the same change.

**Image paths.** Most routes return absolute proxied URLs
(`http://127.0.0.1:6969/api/img/w500/...`); `/api/images` returns raw TMDB
paths (`/abc.jpg`). `ImageUrls` in `shared/network/` handles this — use it
rather than building URLs directly. Passing an already-proxied URL through
the proxy builder yields a 400 from the backend.

## The OSS/proprietary split

Two packages ship two implementations each, selected at compile time via Go
build tags:

| Package | OSS default (`noop.go`, no tag) | Proprietary (`-tags discover`/`supabase`) |
|---|---|---|
| `internal/discover` | `//go:build !discover`. Personalization rows return `[]`/`{}` unless a custom algorithm URL is configured in Settings (see below); `/api/genres` still works (plain TMDB proxy). | `//go:build discover`, source lives in the `_private/cove-discover` git submodule. Real taste-profile-driven recommendations: genre/keyword/cast-crew scoring with recency decay, re-ranking, and the pluggable custom-algorithm system. |
| `internal/supabase` | `//go:build !supabase`. Every `/api/auth/*` route returns `503`. | `//go:build supabase`, mirrored in `internal/supabase` and verified against `_private/cove-auth`. Real account creation, login, profile reconciliation, and cross-device sync. |

The private sources are refreshed via `make inject-private`: `git submodule
update --init`, then a plain `cp` of
`_private/cove-auth/*.go` into `internal/supabase/` and
`_private/cove-discover/*.go` into `internal/discover/`. The `Makefile`'s
`go` target auto-detects which private files are present
(the `_PRIVATE_TAGS` variable in the `Makefile`, checking for
`internal/supabase/client.go` and `internal/discover/discover.go`) and adds
the matching `-tags` automatically
— so `make inject-private && make go` alone is enough; you don't need to
remember the tag names. The Supabase implementation is intentionally checked
in as a mirror so PR CI can compile and test `-tags supabase`; trusted-push
and release CI run `scripts/check-private-sync.sh` before injection and fail
if it differs from `_private/cove-auth`. Discovery implementation files remain
gitignored and are available only after private-submodule injection.

**Licensing note**: the main repo is AGPL-3.0. The proprietary submodule
files—and the checked-in Supabase mirror—carry their own "All Rights
Reserved" copyright header and are explicitly excluded from the AGPL grant
(see each file header). Untagged Go builds select the AGPL stubs. The standard
Make targets auto-enable any implementation files present, but auth still
requires Supabase runtime configuration and private discovery still requires
submodule access.

## Cross-device sync flow

The frontend is the sync scheduler; the backend owns reconciliation and
persistence:

1. After session restoration, desktop/web calls `POST /api/auth/sync`
   immediately, on focus/visibility resume, and every 60 seconds while visible.
   Calls are throttled to a 45-second minimum and coalesced while one is in
   flight. Android triggers on process resume and debounces post-mutation syncs.
2. The backend validates the bearer JWT, reconciles the active local profile
   with the account's remote profile rows, pulls remote data, and merges it
   synchronously into the local stores. Entries, progress, profile names, and
   settings use timestamp-based last-write-wins rules; dismissals are unioned;
   removal tombstones prevent a deleted title from being resurrected by an
   older device.
3. A local-to-remote push starts asynchronously after the pull. RLS/unique-key
   conflicts repair or adopt row IDs and retry once. Independent table errors
   are joined so one failed dataset does not suppress the others.
4. The response returns `library_generation`, allowing clients to refresh only
   when merged library state changed. `push_error` reports the previous
   completed asynchronous push; clients show each distinct error once instead
   of spamming every poll.

`MergeFrom` persists synchronously because a successful sync response must not
race a delayed disk write. Unit tests cover generation stability, LWW merges,
tombstone behavior, and sync cleanup/error de-duplication.

**Worked example — the pluggable discovery algorithm.** This split doesn't
have to mean "OSS users get nothing": `internal/discover`'s "custom
algorithm" feature (an HTTP endpoint the user points Settings at, which
receives a taste profile + a pre-filtered candidate pool and returns
relevance scores) is implemented independently in *both* builds. The
proprietary build sends a real taste profile built from library signals; the
OSS `noop.go` build has no taste-profile machinery at all, so it sends the
same JSON shape with empty profile arrays and a plain TMDB-popularity
candidate pool instead. A single third-party algorithm implementation works
unmodified against either edition — the contract, not the personalization
data, is what's shared.
