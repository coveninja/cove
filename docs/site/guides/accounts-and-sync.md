# Accounts and sync

Cove accounts are optional. A signed-out profile keeps its library, progress, settings, recommendations, and activity locally on that device.

## Register or sign in

Open **Profile → Account**. Cove supports email registration, sign-in, and one-time confirmation codes. During onboarding the same controls appear in the Sync step.

Registration can require a code sent to the email address. Enter that code in Cove to finish verification. Do not share the code: it authorizes the pending account action. If a code expires, start the flow again instead of repeatedly submitting the old value.

Signing in links the active local profile to a cloud profile. It does not automatically combine unrelated profiles that merely have the same display name.

## What an account adds

Cove reconciles the linked profile, pulls newer compatible data, merges it locally, and pushes the current snapshot. Synced data includes:

- Profile name and primary-profile status
- Library entries, ratings, watch progress, and activity
- Dismissed and removed-title markers
- Profile-scoped settings
- Addon configuration, Nuvio state, and compatible integration snapshots

Downloads, caches, mpv configuration, low-performance mode, update state, remote-access tokens, and other machine-specific security values do not sync.

Authentication uses the account's access token and Cove's public Supabase client configuration. Cove clients never receive the project's administrative service-role credentials.

## Automatic and manual sync

With **Sync automatically** enabled, Cove syncs at launch, periodically while open, and shortly after local changes settle. Turn it off to keep the account connected but sync only when you choose **Sync now**.

The Account page reports idle, active, successful, and failed states. Leave Cove open and online until an active sync completes. A failed sync preserves the local data and can be retried; do not delete the profile as a first recovery step.

## Reconciliation and conflicts

Every synced row is scoped to the authenticated account and profile. Server timestamps and removal markers help devices reconcile newer changes without treating an old offline snapshot as authoritative.

When two devices change the same logical item while separated, Cove merges compatible fields and uses the newest accepted state where they conflict. Reconnect the device with the changes you care about, run one sync, then check the other device after its next sync. Avoid alternating edits on both devices while diagnosing a mismatch.

## Multiple profiles

Profiles keep separate libraries and preferences. Devices reconcile matching profile identifiers rather than names. The primary profile is the account's ownership anchor and cannot be deleted from the website.

Renames and non-primary profile deletions made on the website appear after a device's next sync. A deleted cloud profile can remain locally on an offline device, but it no longer has that remote profile to sync with. See [Profiles and insights](profiles-and-insights.md) for local profile behavior and addon sharing.

## Website cloud snapshot

The website account page can show stored counts and the newest cloud-side timestamp for each profile. This is a read-only snapshot of cloud rows. It is not a device heartbeat, a list of connected devices, a live progress view, or proof that a particular device completed its latest sync.

## Sign out and delete data

Signing out stops future account sync on that installation but leaves its local profile data available. Deleting a non-primary cloud profile or the complete account is a separate website action.

Cloud deletion does not erase offline databases remotely. Remove local app data separately on every device when that is also intended. Read [Data and privacy](data-and-privacy.md) before deleting an account.

## Trakt is separate

[Trakt](trakt.md) uses its own device authorization, scrobbling, and optional two-way library sync. Connecting or disconnecting Trakt does not connect or disconnect the Cove account.
