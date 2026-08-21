# Accounts and sync

Cove accounts are optional. A signed-out profile keeps its library, progress, settings, and recommendations locally on that device.

## What an account adds

Signing in connects the active profile to Cove's cloud sync. Cove reconciles the cloud profile, pulls newer compatible data, merges it locally, and pushes the current profile snapshot. Sync runs on launch, periodically, and after local changes settle.

Synced profile data includes:

- Profile name and primary-profile status
- Library entries, ratings, and watch progress
- Dismissed and removed-title markers
- Profile settings
- Addon, Nuvio, and activity snapshots used by compatible Cove builds

Authentication and sync use the account's access token and the public Supabase client key. Client applications do not receive administrative database credentials.

## Multiple profiles

Every profile has separate synced data. Devices reconcile matching profile identifiers; the primary profile cannot be deleted from the website while it is the account's ownership anchor.

Renames and profile deletions made on the website appear after a device's next sync. A deleted cloud profile can remain locally on an offline device, but it no longer has that remote profile to sync with.

## Website cloud snapshot

The account page can show counts and the newest cloud-side update visible for each profile. This is a snapshot of stored cloud data, not a device heartbeat and not proof that a particular device completed its latest sync.

## Trakt is separate

Linking Trakt is a separate device authorization. Trakt scrobbling and two-way library sync follow their own settings and do not replace Cove account sync.

