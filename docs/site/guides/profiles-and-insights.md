# Profiles and insights

Profiles give each viewer an independent library, watch progress, ratings, recommendations, settings, and activity history. Profiles work locally; a Cove account is required only to synchronize them across devices.

## Create and switch profiles

Open **Profile → Account → Profiles** to create, rename, activate, or delete a local profile. A new profile starts with an empty library and its own settings.

Switching profiles changes the active data scope immediately. Playback, addon visibility, recommendations, and My List all follow the newly active profile. Finish or stop playback before switching when you want the final position written to the previous profile.

## Primary profile behavior

Every installation has a primary profile. It is the ownership anchor for account synchronization and cannot be deleted from the website while it remains primary.

The primary profile can enable **Primary profile drives addons**. When enabled:

- Provider addons and their catalog switches come from the primary profile.
- Other profiles can use those providers but cannot edit the inherited configuration.
- Nuvio scraper activation, plugins, libraries, progress, ratings, and ordinary preferences remain profile-scoped.

Turn sharing off before configuring a different provider set for a secondary profile. See [Addons and sources](addons-and-sources.md).

## Personalization

Cove builds profile-specific discovery signals from recent watches, ratings, genres, keywords, cast and crew, studios, and saved states. The **Content** settings select one of four strategies:

| Strategy | Behavior |
|---|---|
| Smart | Combines the available profile signals |
| Trending | Favors broadly popular current titles |
| More like what I watch | Leans toward similarity to watched media |
| Custom | Sends a taste profile and candidates to a configured HTTPS scoring endpoint |

The custom endpoint is an advanced integration. It receives recommendation inputs, so use only a service whose privacy and security properties you understand.

## Viewing insights

The Insights tab summarizes the active profile's recorded activity. Depending on available history, it can show total watch time, films and episodes watched, genres, people, streaks, time-of-day patterns, rating behavior, and recent activity.

Insights are derived from Cove's stored activity rather than external provider analytics. Empty or partial charts usually mean the profile has little recorded playback history, older imported data lacks a field, or activity was cleared.

## Local and synced profiles

Signing in links the active local profile to the corresponding cloud profile. Other local profiles remain local until they are reconciled with that account. Matching profile identifiers are used across devices; names alone do not merge two unrelated profiles.

Deleting a cloud profile does not remotely erase an offline device's local copy. When the device reconnects, that local profile no longer has a remote target. Review [Accounts and sync](accounts-and-sync.md) before deleting profiles on multiple devices.

## Profile-scoped and device-scoped data

Most content and playback preferences belong to the profile. Security-sensitive or machine-specific values—including remote-access tokens, automatic update state, low-performance mode, mpv configuration, downloads, and caches—remain on the device.

The [settings reference](settings-reference.md) identifies the scope of each category.

