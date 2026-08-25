# Embedded HTTP API

The desktop and mobile applications call Kotlin repositories directly. The HTTP
boundary exists for media URLs, diagnostics, optional LAN clients, and
compatibility integrations. Desktop starts it with the backend; Android starts
the same route graph while its remote-access foreground service is enabled.
Compatibility clients use `/api/v1` (desktop loopback defaults to
`http://127.0.0.1:6969/api/v1`; Android LAN defaults to port `6970`).

The unversioned `/api` prefix currently exposes the same handlers but adds:

```text
Deprecation: true
Sunset: v0.34.0
Link: </api/v1>; rel=successor-version
```

JSON uses the serializers in `app/shared`. Invalid input returns `400`; missing
state returns `404`; conflicts return `409`; unavailable configured integrations
return `503`; upstream failures return `502`; capped bodies return `413`.

## Endpoint groups

All paths below are relative to `/api/v1`.

| Group | Methods and paths |
|---|---|
| Health/session | `GET /ping`; `GET`, `POST`, `DELETE /client-session` |
| Auth/sync | `POST /auth/register`, `/register/confirm`, `/login`, `/otp`, `/verify-otp`, `/refresh`, `/logout`, `/sync`; `GET /auth/me` |
| Trackers | `POST /{tracker}/device-code`, `/poll`, `/unlink`, `/scrobble`, `/sync`; `GET /{tracker}/status`, `/stats`; `GET /trackers/stats`. `{tracker}` is `trakt` or `simkl` |
| TMDB/content | `GET /discover`, `/browse`, `/search/multi`, `/search`, `/keywords`, `/media`, `/details`, `/images`, `/logos`, `/videos`, `/similar`, `/imdb`, `/person`, `/provider`, `/genres`, `/tv/seasons`, `/tv/episodes` |
| Personalized discovery | `GET /discover/genres`, `/keywords`, `/people`, `/genre`, `/keyword`, `/person`, `/similar-to`, `/favorites`, `/insights`; `POST /discover/algorithm/test` |
| Sources | `GET /streams`, `/subtitles`, `/watch-options`, `/timestamps`, `/quality/batch` |
| Media boundary | `POST /streams/probe`, `/prefetch-download`; `GET /play`, `/torrent/{hash}`, `/progress`, `/progress/stream`, `/speedtest`, `/subtitle-proxy`, `/img/{size}/{file}` |
| Addons/catalogs | `GET`, `POST`, `PATCH`, `DELETE /addons`; `POST /addons/refresh`; `PATCH /addons/catalog`; `GET /catalogs`, `/catalog` |
| Nuvio | `GET`, `POST`, `PATCH`, `DELETE /nuvio/repos`; `POST /nuvio/repos/refresh`; `PATCH /nuvio/scrapers` |
| Settings/profiles | `GET`, `PUT /settings`; `POST /settings/reveal-token`; `GET`, `PUT /settings/mpv-conf`; `GET`, `POST /profiles`; `POST /profiles/{id}/activate`; `PATCH`, `DELETE /profiles/{id}` |
| Library | `GET`, `POST /library`; `GET`, `POST /library/progress`; `POST /library/progress/bulk`; `GET /library/activity`, `/library/calendar`, `/library/stats`, `/library/{id}/{type}`; `POST`, `DELETE /library/dismiss`; `DELETE /library/{id}/{type}`; `PATCH /library/{id}/{type}/status`, `/rating` |
| Update compatibility | `GET /update/check`; `POST /update/apply` |

`/quality/batch` is newline-delimited JSON. `/progress/stream` is server-sent
events. `/play`, `/torrent`, `/subtitle-proxy`, `/img`, and `/speedtest` may
stream response bodies instead of materializing them in memory.

## Connection and authentication

The desktop loopback listener is a trusted local-process boundary. Do not make
it listen publicly. Optional remote access creates a separate LAN listener and
requires the current pairing token on every request:

```http
X-Cove-Token: <pairing-token>
```

The `token` query parameter is retained for clients that cannot set headers,
but headers avoid leaking the secret through copied URLs and access logs. This
token authenticates the LAN connection; it is unrelated to a Cove account
access token.

`POST /auth/sync` additionally requires the Cove account session as
`Authorization: Bearer <access-token>`. In-process application sync uses the
stored session through `AuthService.syncNow()` instead of this HTTP route. Auth
and Trakt routes return `503` when their integration was not configured. Route
groups whose repository is entirely absent, such as a fixture without a media
boundary, are not installed and therefore return `404`.

Requests and responses use `application/json` unless the endpoint is documented
as a stream or returns `204 No Content`. Data contracts are the serializable
types under `app/shared`; route tests are the executable wire contract.

## Common request contracts

### Media keys

Most catalog and source endpoints require a positive `id` and
`type=movie|tv`. TV stream lookup requires positive `season` and `episode`
values. Season-list and episode-list routes accept season zero where specials
are meaningful, but normal stream addressing does not treat zero as an episode.

```sh
curl 'http://127.0.0.1:6969/api/v1/details?id=550&type=movie'
curl 'http://127.0.0.1:6969/api/v1/streams?id=1399&type=tv&season=1&episode=1'
```

Search endpoints require a non-blank `q`. Pagination parameters are numeric;
invalid required identifiers return `400` rather than silently selecting an
unrelated title.

### JSON mutations

Auth requests use the matching shared request type: registration includes
email, password, and profile name; confirmation adds the emailed token; login
uses email and password; OTP verification uses email and token. Never expose
these routes or bodies through an internet-facing proxy.

`PUT /settings` is a complete replacement, not a patch. Send the full
`AppSettings` returned by `GET /settings`; omitted fields otherwise take their
decoded defaults. Profile, addon, Nuvio, and library mutations use their
specific POST/PATCH bodies and reject invalid state with `400` or `409`.

### Cache behavior and empty results

`GET /client-session` sends `Cache-Control: no-store`. Empty discovery,
catalog, stream, subtitle, activity, and calendar collections are successful
results, not `404`. `GET /{tracker}/stats` and `GET /trackers/stats` return `204`
when the connected accounts have no reportable totals.

## Endpoint behavior notes

### Auth and sync

Registration returns either a session or `confirmation_required: true`.
`/auth/otp` sends a sign-in code and `/auth/verify-otp` exchanges it. `/auth/me`
reports the current local account session, while `/auth/logout` clears it.
`/auth/sync` responds with the resulting library generation and any non-fatal
push error after reconciliation.

### Trackers

The same seven routes exist once per linked tracker, under its own path segment:
`/trakt/…` and `/simkl/…`. A host that owns neither serves neither group at all,
rather than answering `503` to every path in it.

`/{tracker}/device-code` starts device authorization; `/{tracker}/poll` accepts
the code from that call. **The field is spelled `device_code` for both, but the
value differs**: Trakt polls with the `device_code` it returned, while Simkl
polls with the `user_code` — the one the viewer can see. Scrobbles and library
sync are queued and return `202 Accepted` when work was accepted;
`/{tracker}/unlink` removes the local authorization and returns `204`. Note that
unlinking Simkl cannot revoke the token remotely — Simkl publishes no revoke
endpoint, so access ends only when the user removes Cove from Connected Apps.

`/{tracker}/stats` reports all-time totals for one account and answers `204` when
there is nothing to say. `GET /trackers/stats` returns the same values for every
linked tracker as an array, each entry tagged with its `provider`, and likewise
`204` when none has anything — which is the call the insights page makes, so it
does not have to know which trackers a host offers.

### Addons, catalogs, and Nuvio

Addon configuration and individual catalog switches are distinct. `/catalogs`
lists the effective catalog descriptors; `/catalog` reads one page using addon,
type, catalog, skip, and bounded limit parameters. Under primary-profile addon
sharing, mutations from a secondary profile can be rejected even though reads
show the inherited providers.

Nuvio repository routes manage repository metadata, while
`PATCH /nuvio/scrapers` changes individual scraper activation. Adding a
repository does not automatically run every scraper.

### Media registration and streaming

`GET /streams` merges addon, Nuvio, and available desktop-plugin results, then
registers playable URLs and torrent hashes with the media boundary. A later
`GET /play` can serve only a registered direct URL or hash. Registrations are
bounded and expire; clients should not persist them as durable media URLs.

`POST /streams/probe` validates a candidate batch. Torrent playback accepts
optional `season`, `episode`, and `fileIdx` selectors. `/progress` is a snapshot;
`/progress/stream` emits an SSE update approximately every two seconds.
`/prefetch-download` returns `202` and a `started` flag rather than waiting for
the download.

The subtitle and image proxies validate upstream targets and cap responses.
Clients must not treat them as general-purpose open proxies.

`/browse` and `/discover` both return `Media` lists and are easy to confuse.
`/browse` is the whole catalog — `type`, optional `genre`, `sort`
(`popularity`|`rating`|`newest`|`oldest`|`title`, unknown values falling back to
popularity) and `page` — and deliberately includes titles already in the library,
because browsing has to be able to reach them. `/discover` is personalized: when a
discovery service is present it ranks by the profile's taste and excludes anything
already saved or dismissed. Use `/browse` to let someone look for a title, and
`/discover` to suggest one. Genre ids are only meaningful alongside `/genres`, whose
vocabularies for films and series differ.

## Addressing media

TMDB-backed endpoints generally use `id=<positive TMDB id>&type=movie|tv`.
Episode-specific routes additionally use positive `season` and `episode`
parameters. Library item routes encode the same pair as
`/library/{id}/{type}`.

Addon catalogs accept `addonId`/`addonUrl`, `type`, `catalogId`, `skip`, and a
bounded `limit`. Returned addon metadata is resolved to the shared TMDB `Media`
shape while preserving source order; `nextSkip` advances by consumed source
items.

`/search/multi` answers with `movies`, `tv` and `people`. The people are TMDB
`/search/person` records — the slim kind, carrying `known_for` rather than a
filmography — capped at the dozen most prominent, and a person-search failure is
swallowed rather than failing the whole search: the titles are what the query was
almost certainly for. `people` is defaulted on the client, so a compatibility host
that omits it reports titles alone.

`/person` takes a positive `id` alone — a person has no type — and answers with
the typed `PersonDetails` shape (biography, birthday, `place_of_birth`,
`known_for_department`, `profile_path` and `combined_credits`), not the whole
TMDB document it is built from. Like `/details`, it is a deliberate subset: the
keys it does return keep TMDB's own names, and everything the app does not model
is dropped. The route no longer requires the TMDB catalog specifically — it is
served by whatever `MediaCatalog` the host was built with.

## Remote access

The trusted listener binds to loopback. Enabling remote control creates a
separate LAN listener using the persisted settings. Desktop accepts the
`COVE_REMOTE_ADDR` override; Android binds port `6970` while its connected-device
foreground service is visible. A remote request must provide the current token as `X-Cove-Token` or
the `token` query parameter. Disabled access, an empty token, or a mismatch is
rejected. Browser origins are restricted to localhost/loopback.

Do not expose the LAN listener through a public reverse proxy. It is intended
for a trusted local network and does not replace TLS or an internet-facing auth
gateway.

## Updates

The compatibility routes do not expose the application updater. `/update/check`
reports the current version and no downloadable update; `/update/apply` returns
a conflict. Signed update checking, staging, and installation live in the host's
device-local `UpdateRepository`, are not reachable from the LAN API, and never
accept a caller-supplied URL or key. See [Application updates](UPDATES.md).
