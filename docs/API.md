# Embedded HTTP API

The desktop and mobile applications call Kotlin repositories directly. This
desktop-only HTTP boundary exists for media URLs, diagnostics, optional LAN
clients, and compatibility integrations; the Android UI does not start a
localhost server. New desktop compatibility clients should use
`http://127.0.0.1:6969/api/v1`.

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
| Trakt | `POST /trakt/device-code`, `/poll`, `/unlink`, `/scrobble`, `/sync`; `GET /trakt/status` |
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

`/person` takes a positive `id` alone — a person has no type — and answers with
the typed `PersonDetails` shape (biography, birthday, `place_of_birth`,
`known_for_department`, `profile_path` and `combined_credits`), not the whole
TMDB document it is built from. Like `/details`, it is a deliberate subset: the
keys it does return keep TMDB's own names, and everything the app does not model
is dropped. The route no longer requires the TMDB catalog specifically — it is
served by whatever `MediaCatalog` the host was built with.

## Remote access

The trusted listener binds to loopback. Enabling remote control creates a
separate LAN listener using the persisted settings and `COVE_REMOTE_ADDR`
override. A remote request must provide the current token as `X-Cove-Token` or
the `token` query parameter. Disabled access, an empty token, or a mismatch is
rejected. Browser origins are restricted to localhost/loopback.

Do not expose the LAN listener through a public reverse proxy. It is intended
for a trusted local network and does not replace TLS or an internet-facing auth
gateway.

## Updates

The JVM build does not rewrite its own installation. `/update/check` reports
the current version and no downloadable self-update; `/update/apply` returns a
conflict directing the caller to Flatpak, AUR, or the Windows installer.
