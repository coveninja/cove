# Library, discovery, and watch progress

Cove keeps discovery, your saved library, and playback history separate for each profile. You can browse without saving a title, and saving a title does not require an account.

## Find something to watch

**Home** combines recommendations, trending titles, recently watched media, and rows supplied by enabled addon catalogs. **Explore** is for deliberate browsing by media type, genre, collection, or addon catalog. **Search** looks for films, series, and people.

Opening a title shows its description, release information, cast, related titles, legal watch options where available, and seasons or episodes for a series. Opening a person shows their biography and filmography.

Recommendations use the active profile's watch history, ratings, genres, keywords, people, and studios. Titles already watched, dismissed, or removed are excluded where appropriate. Change the recommendation strategy under **Profile → Content**; see the [settings reference](settings-reference.md).

## Understand Watch and Continue

The primary action reflects the active profile's state:

- **Watch** starts a film or the first relevant episode when no resumable progress exists.
- **Continue** includes the next episode and saved position when Cove can resume precisely.
- **Start over** is available from playback when saved progress exists but you want to restart.

Cove stores playback position periodically. Unexpected end-of-file, a failed source, or a network interruption is not treated as completion. A title becomes watched only after the completion threshold or an explicit library action.

## Organize My List

Add a title to **My List** from its details or card actions. Library states are:

| State | Meaning |
|---|---|
| Watch Later | Saved for later but not started |
| Watching | In progress or intentionally marked as current |
| Finished | Intentionally marked as finished |
| Dropped | No longer being followed |

Ratings and progress are stored separately from the library state. Removing a title clears it from the visible library but preserves the removal marker used by recommendations and sync reconciliation.

**Not Interested** is a separate discovery action rather than a saved My List state. It removes an existing entry and records a dismissal so Cove does not immediately recommend the title again.

On pointer devices, secondary-click a card for its actions. On touch devices, use a long press. Cove also supports dragging library cards to compatible navigation targets where the interface shows a drop action.

## Use the calendar

The calendar view in **My List** groups episodes by air date. **Ready to watch** highlights episodes that have aired and are not marked watched. Calendar entries come from the series in the active profile's library, so an empty calendar usually means no tracked series has dated episodes in the selected range.

Air dates are metadata, not a promise that a stream source is available. Source discovery begins only when you choose to watch.

## Control spoilers and recommendations

Enable **Hide spoilers** under **Profile → Content** to conceal unwatched episode titles and descriptions. The setting does not remove the episode or prevent playback.

Dismiss a recommendation when it is not useful. Dismissal affects discovery without adding the title to My List. Ratings, watched state, and dismissals all influence the smart recommendation mode.

## What syncs

When Cove account sync is enabled, library entries, ratings, progress, dismissals, removal markers, and relevant settings sync per profile. Downloads and cached media remain on the device. See [Accounts and sync](accounts-and-sync.md) for timing and conflict behavior.
