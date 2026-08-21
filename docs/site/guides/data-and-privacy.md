# Data and privacy

This page describes Cove's current data flow. It is an operational guide, not a replacement for the terms or privacy policies of services you choose to connect.

## Data stored on your device

Each Cove installation keeps its profiles, settings, library, progress, dismissals, integration state, and cached metadata in local application storage. Local-first profiles work without creating a Cove account.

Desktop logs contain application, backend, and playback diagnostics. Review logs before sharing them because provider responses or URLs may be relevant to a failure.

## Optional Cove account

Creating an account sends the email and authentication material required by Supabase Auth. When sync is enabled, profile-scoped library, progress, settings, dismissal/removal markers, addon configuration, Nuvio state, and activity snapshots are stored in Cove's Supabase project under row-level access policies.

The website uses the same signed-in user policies for profile administration and read-only cloud counts. It does not receive device heartbeat or playback telemetry.

## Optional external services

- TMDB supplies catalog metadata and images.
- Trakt receives the account and viewing operations enabled in Trakt settings.
- Addons and scraper repositories receive the requests needed for capabilities you enable.
- GitHub serves releases, checksums, release notes, and the documentation shown on Cove's website.

## Delete cloud data

The website account page can delete a non-primary synced profile or the complete Cove account. Complete deletion removes the account's child sync rows before its profiles and authentication user.

Deleting cloud data does not remotely erase local databases on offline or previously connected devices. Remove the app or its local data separately on each device when that is also intended.

