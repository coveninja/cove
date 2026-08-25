# Trakt and Simkl tracking

Trakt and Simkl are optional external integrations for scrobbling and library synchronization. Both are independent of a Cove account: connecting one does not sign in to the other or replace Cove cloud sync, and the two trackers are independent of each other. You can connect either, both, or neither.

## Connect a tracker

1. Open **Profile → Tracking**.
2. Choose **Connect** on the account you want.
3. Open the displayed verification address on any device.
4. Enter the code before it expires and approve Cove.
5. Return to Cove and wait for the connected username to appear.

Neither flow asks you to type a password into Cove. If the code expires, cancel and start again to obtain a new one.

A build compiled without credentials for a tracker says so on its card instead of offering **Connect**. That is a build-time decision, not a fault: Trakt needs a client id and secret, Simkl needs a client id.

## Choose what Cove sends

Each connected account gets two controls:

- **Scrobble** reports playback start, progress, and completion as you watch.
- **Sync your library** reconciles supported library and watch-history state in both directions.

Scrobbling is enabled by default; two-way library sync is opt-in. Disabling a switch stops future work but does not remove data already stored by the tracker.

## Run a manual sync

Enable **Sync your library**, then choose **Sync now** from that account's card when you want an immediate reconciliation. Cove can also enqueue synchronization at configured lifecycle points while the option remains enabled.

Keep Cove open and online until the operation completes. A temporary tracker or network failure does not mean Cove account sync failed; the integrations maintain separate state and error reporting.

## Understand conflicts

Cove, Trakt, and Simkl use different data models. Cove preserves its profile-scoped library states, ratings, episode progress, and removal markers, while each tracker exposes its own watchlist and history concepts. A two-way sync maps only compatible fields.

Two limits are specific to Simkl:

- **Titles Simkl knows only as anime are skipped when pulling.** Simkl identifies items by IMDB, TVDB, MAL and AniDB ids while Cove is keyed on TMDB throughout. Cove translates an IMDB id into a TMDB one, but an entry that carries only anime ids has no TMDB counterpart, and inventing one would put the wrong show in your library. Those titles still receive scrobbles and still travel outward when Cove pushes, because both directions of that traffic use TMDB ids.
- **Simkl publishes no ratings total**, so the ratings figure on its insights card reads zero.

If data appears unexpected:

1. Confirm the active Cove profile and the connected username.
2. Disable that tracker's automatic library sync while investigating repeated changes.
3. Compare the title or episode on both services.
4. Run one manual sync and record the resulting message.
5. Report the media identifier and direction of the mismatch without sharing authorization tokens.

With both trackers connected and syncing, expect the two accounts to converge: a title watched on one reaches Cove's library and is pushed to the other on its next cycle.

## Disconnect

Choose **Disconnect** to remove Cove's local authorization. This does not delete the account, viewing history, or lists already stored by the tracker. Reconnecting later begins with a new device authorization.

Trakt revokes Cove's token as part of disconnecting. **Simkl has no revoke endpoint**, so disconnecting there only forgets the account on this device — remove Cove under *Connected Apps* on simkl.com to end its access.

For Cove's own cross-device profile data, read [Accounts and sync](accounts-and-sync.md). For external-service data boundaries, read [Data and privacy](data-and-privacy.md).
