# Trakt integration

Trakt is an optional external integration for scrobbling and library synchronization. It is independent of a Cove account: connecting one does not sign in to the other or replace Cove cloud sync.

## Connect Trakt

1. Open **Profile → Trakt**.
2. Choose **Connect**.
3. Open the displayed Trakt verification address on any device.
4. Enter the device code before it expires and approve Cove.
5. Return to Cove and wait for the connected username to appear.

The device flow does not ask you to type a Trakt password into Cove. If the code expires, cancel and start again to obtain a new one.

## Choose what Cove sends

Two controls apply after an account is connected:

- **Scrobble to Trakt** reports playback start, pause, progress, and completion as you watch.
- **Sync your Trakt library** reconciles supported library and watch-history state in both directions.

Scrobbling is enabled by default; two-way library sync is opt-in. Disabling a switch stops future work but does not remove data already stored by Trakt.

## Run a manual sync

Enable **Sync your Trakt library**, then choose **Sync now** from the connected-account card when you want an immediate reconciliation. Cove can also enqueue synchronization at configured lifecycle points while the option remains enabled.

Keep Cove open and online until the operation completes. A temporary Trakt or network failure does not mean Cove account sync failed; the integrations maintain separate state and error reporting.

## Understand conflicts

Trakt and Cove use different data models. Cove preserves its profile-scoped library states, ratings, episode progress, and removal markers, while Trakt exposes its own watchlist and history concepts. A two-way sync maps only compatible fields.

If data appears unexpected:

1. Confirm the active Cove profile and connected Trakt username.
2. Disable automatic Trakt library sync while investigating repeated changes.
3. Compare the title or episode on both services.
4. Run one manual sync and record the resulting message.
5. Report the media identifier and direction of the mismatch without sharing authorization tokens.

## Disconnect

Choose **Disconnect** to remove Cove's local Trakt authorization. This does not delete the Trakt account, viewing history, or lists already stored by Trakt. Reconnecting later begins with a new device authorization.

For Cove's own cross-device profile data, read [Accounts and sync](accounts-and-sync.md). For external-service data boundaries, read [Data and privacy](data-and-privacy.md).
