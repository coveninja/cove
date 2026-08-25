# Addons and sources

Cove installs without third-party stream sources. It is a media player and organizer, not a content host, provider, or subscription service.

## Extension types

| Type | Provides | Runtime |
|---|---|---|
| Stremio-compatible addon | Catalogs, streams, subtitles, metadata, or timestamps declared by a manifest | Remote HTTPS service |
| Nuvio-compatible scraper | Opt-in community stream extraction | Isolated JavaScript worker |
| Desktop plugin | Signed integrations and declared media capabilities | Separate restricted desktop worker |

This guide covers addons and Nuvio. See [Desktop plugins](desktop-plugins.md) for the third type.

## Add a provider addon

1. Open **Profile → Addons**.
2. Under **Provider addons**, choose **Add an addon**.
3. Paste the complete HTTPS manifest URL.
4. Review the name and declared capabilities after Cove loads it.
5. Enable the catalogs you want to appear in Cove.

Cove refreshes the manifest and stores the provider configuration for the active profile. Removing an addon removes its catalogs and future source lookups; it does not erase titles already saved in My List.

## Use addon catalogs

Each film or series catalog can be enabled independently. Enabled catalogs appear as attributed rows on Home or Explore. Disabling a catalog hides that browsing row without disabling the addon's stream or subtitle capability.

Catalog results preserve provider order and are resolved to Cove's common title model where possible. A catalog entry can be unavailable when the provider supplies no identifier Cove can match.

## Share provider addons between profiles

The primary profile can enable **Primary profile drives addons** under **Profile → Account → Profiles**. Secondary profiles then inherit the primary profile's provider addons and catalog switches as read-only settings.

This sharing applies to provider addons, not libraries, playback progress, plugins, or Nuvio scraper activation. Turn sharing off when a secondary profile needs an independent provider set.

## Add Nuvio scrapers

Under **Nuvio scrapers**, add the HTTPS GitHub repository URL supported by the scraper catalog. Refresh it to discover compatible scrapers, then enable only the individual scrapers you intend to use.

Repository installation does not enable every scraper automatically. Activation is profile-scoped. Scraper results are merged with compatible addon and plugin sources while preserving provider attribution.

## Safety boundaries

Treat every community URL as untrusted. Add only services you understand and are permitted to use.

- Desktop scraper invocations run in disposable child JVMs with bounded memory and time.
- Android scraper code runs in an isolated process and receives only bounded, brokered network access.
- User-supplied URLs are checked before requests; local, private, metadata, and user-info targets are rejected unless an explicit LAN-source setting permits them.
- Credentials are stripped when a redirect changes authority.

Isolation reduces application risk. It does not make an unknown provider trustworthy, private, reliable, or lawful.

## How source results behave

Cove gathers compatible addon, scraper, and plugin results before presenting or ranking them. Provider names remain visible when source details are enabled. Once playback begins, Cove does not silently switch to a different source, quality, or audio track.

A failed source can reconnect according to the playback policy. Repeated failure at the same playback position stops and asks what to do; successfully advancing playback renews recovery for a later interruption. See [Playback and subtitles](playback-and-subtitles.md).

## Troubleshoot an addon or scraper

1. Confirm the manifest or repository URL uses HTTPS and opens without authentication in a normal client.
2. Refresh it from Cove's Addons settings.
3. Verify the capability, catalog, or scraper is enabled for the active profile.
4. Check whether primary-profile sharing makes the current settings read-only.
5. Try another title and media type to distinguish missing media from a provider-wide failure.
6. Compare with a different provider before changing playback settings.
7. Attach the provider name, media identifier, visible error, and Cove logs to a bug report.

Remove access tokens, private addon URLs, credentials, and full provider responses before sharing logs.
