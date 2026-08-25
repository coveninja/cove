# Data and privacy

This page describes Cove's current data flow. It is an operational guide, not a replacement for the terms or privacy policies of services you choose to connect.

## Data stored on your device

Each Cove installation keeps its profiles, settings, library, progress, ratings, dismissals, removal markers, activity, integration state, and cached metadata in local application storage. Local-first profiles work without creating a Cove account.

Desktop logs contain application, backend, and playback diagnostics. Review logs before sharing them because provider responses or URLs may be relevant to a failure.

## Device-local media and settings

Downloads, torrent pieces, image and metadata caches, managed helper tools, mpv configuration, update staging, low-performance mode, and remote-access tokens remain on the device. They are not uploaded by Cove account sync.

Storage retention can remove eligible downloaded data without deleting its library or watch-history record. Clearing a cache can make the next request slower but does not sign out the profile.

Desktop data and logs use the platform application-data directory. Android stores them in app-private storage. Other applications do not receive direct database access through Cove.

## Optional Cove account

Creating an account sends the email and authentication material required by Supabase Auth. When sync is enabled, profile-scoped library, progress, settings, dismissal/removal markers, addon configuration, Nuvio state, and activity snapshots are stored in Cove's Supabase project under row-level access policies.

The website uses the same signed-in row policies for profile administration and read-only cloud counts. It does not receive device heartbeat, a device inventory, live playback state, or playback-error telemetry.

Account access tokens are held by the client session. The public Supabase client key identifies the project but does not grant administrative authority. Service-role keys and database secrets are not packaged in Cove clients.

## Optional external services

- TMDB supplies catalog metadata and images.
- Trakt and Simkl each receive the account and viewing operations enabled for them in Tracking settings.
- Addons and scraper repositories receive the requests needed for capabilities you enable.
- GitHub serves releases, checksums, release notes, and the documentation shown on Cove's website.

Desktop plugins receive only data and operations covered by capabilities approved for the active profile. Playback integrations receive a URL-free activity snapshot. Plugin network and storage access is brokered and bounded.

The custom recommendation mode sends taste and candidate information to the HTTPS scoring endpoint you configure. Enabling LAN sources or remote access relaxes ordinary local-network boundaries on that device; use them only on networks and with services you trust.

## Logs and bug reports

Logs can contain title identifiers, provider names, diagnostic URLs, error bodies, filesystem paths, device information, and playback state. Review every attachment and remove:

- Account, provider, and stream tokens
- Private addon or scraper URLs
- Remote-access pairing tokens
- Email verification codes
- Personal filesystem names where they are not relevant

Do not publish the local database as a substitute for a focused log excerpt.

## Delete cloud data

The website account page can delete a non-primary synced profile or the complete Cove account. Complete deletion removes the account's child sync rows before its profiles and authentication user.

Deleting cloud data does not remotely erase local databases on offline or previously connected devices. Remove the app or its local data separately on each device when that is also intended.

Signing out is not deletion. It stops future sync on that installation while retaining the local profile. Disconnecting a tracker or uninstalling a plugin likewise stops future access but does not delete information already stored by the external service. Disconnecting Simkl does not revoke Cove’s token either — Simkl has no revoke endpoint, so remove Cove under *Connected Apps* on simkl.com.

## Backups and removal

Cove does not currently present a universal cross-platform backup-and-restore workflow. Account sync protects compatible profile data, not downloads, caches, device-only configuration, or every legacy field.

Before uninstalling or clearing application data, confirm which profiles have completed sync and preserve any device-only configuration you need. On Android, uninstalling removes app-private data. Desktop package removal can leave the application-data directory until it is deleted separately.
