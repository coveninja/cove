# Addons and sources

Cove installs without third-party stream sources. It is a media player and organizer, not a content host or subscription service.

## Supported extensions

Cove can connect to Stremio-compatible addons for catalogs, streams, subtitles, and metadata. It can also run opt-in Nuvio-compatible community scrapers.

An addon manifest tells Cove what the addon provides. Catalog visibility and enabled providers are stored per profile.

## Safety boundaries

Treat every community URL as untrusted. Add only services you understand and are permitted to use.

- Desktop scraper invocations run in disposable, restricted child processes.
- Android scraper code runs in an isolated process and receives only bounded network access brokered by Cove.
- User-supplied URLs are checked before Cove sends requests, and credentials are not forwarded across authorities.

Isolation reduces application risk; it does not make an unknown provider trustworthy or lawful.

## Troubleshoot an addon

1. Confirm the manifest URL opens over HTTPS.
2. Refresh the addon from Cove's settings.
3. Check that its catalog or stream capabilities are enabled for the active profile.
4. Try another title to separate a provider-wide failure from missing media.
5. Attach the addon name and Cove logs to a bug report, but remove private tokens or credentials first.

Cove never silently switches to a different source, quality, or audio track after playback has begun.

