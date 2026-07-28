import { api, type LibraryStatus } from "$lib/api";
import { hasAired } from "$lib/nextEpisode";
import { libraryChanged } from "$lib/stores/library";
import type { LibraryEntry } from "$lib/types/library";
import type { Media, TVEpisode, TVSeason } from "$lib/types/tmdb";
import * as m from "$lib/paraglide/messages.js";

export type MediaUtilityAction =
  | "mark-watched"
  | "mark-unwatched"
  | "toggle-not-interested"
  | "remove-from-library";

export interface MediaActionState {
  entry: LibraryEntry | null;
  dismissed: boolean;
  hasProgress?: boolean;
}

export interface MediaUtilityItem {
  id: MediaUtilityAction;
  label: string;
  sublabel?: string;
  destructive?: boolean;
}

function mediaTitle(media: Media): string {
  return media.media_type === "tv" ? media.name : media.title;
}

function mutationFinished(): void {
  libraryChanged.update((generation) => generation + 1);
  // Best effort for signed-in profiles; guests receive a harmless 401.
  api.authSync().catch(() => {});
}

export interface LibraryStatusMetadata {
  lastAiredSeason?: number | null;
  lastAiredEpisode?: number | null;
}

/**
 * Toggle a title's shelf status, creating, updating, or removing its library
 * entry as needed. All status pickers use this path so their stored metadata
 * and library-change notification cannot drift apart.
 */
export async function setMediaLibraryStatus(
  media: Media,
  entry: LibraryEntry | null,
  status: LibraryStatus,
  metadata: LibraryStatusMetadata = {},
): Promise<LibraryEntry | null> {
  let next: LibraryEntry | null;
  if (entry?.status === status) {
    await api.libraryRemove(media.id, media.media_type);
    next = null;
  } else if (entry) {
    next = await api.librarySetStatus(media.id, media.media_type, status);
  } else {
    next = await api.libraryUpsert({
      tmdb_id: media.id,
      media_type: media.media_type,
      title: mediaTitle(media),
      poster_path: media.poster_path ?? "",
      vote_average: media.vote_average ?? 0,
      last_air_date:
        (media as Media & { last_air_date?: string }).last_air_date ?? "",
      last_aired_season: metadata.lastAiredSeason ?? null,
      last_aired_episode: metadata.lastAiredEpisode ?? null,
      status,
    });
  }
  libraryChanged.update((generation) => generation + 1);
  return next;
}

export function mediaUtilityItems(
  media: Media,
  state: MediaActionState,
): MediaUtilityItem[] {
  const items: MediaUtilityItem[] = [];
  if (state.entry?.status !== "finished") {
    items.push({
      id: "mark-watched",
      label:
        media.media_type === "tv"
          ? m.media_mark_episodes_watched()
          : m.media_mark_movie_watched(),
      sublabel:
        media.media_type === "tv"
          ? m.media_mark_episodes_watched_description()
          : m.media_mark_movie_watched_description(),
    });
  }
  if (state.hasProgress || state.entry?.status === "finished") {
    items.push({
      id: "mark-unwatched",
      label: m.media_reset_progress(),
      sublabel:
        media.media_type === "tv"
          ? m.media_reset_episodes_description()
          : m.media_reset_movie_description(),
      destructive: true,
    });
  }
  items.push({
    id: "toggle-not-interested",
    label: state.dismissed
      ? m.media_undo_not_interested()
      : m.media_not_interested(),
    sublabel: state.dismissed
      ? m.media_undo_not_interested_description()
      : m.media_not_interested_description(),
  });
  if (state.entry) {
    items.push({
      id: "remove-from-library",
      label: m.media_remove_library(),
      sublabel: m.media_history_preserved(),
      destructive: true,
    });
  }
  return items;
}

export async function markMediaWatched(media: Media): Promise<void> {
  const base = {
    tmdb_id: media.id,
    media_type: media.media_type,
    title: mediaTitle(media),
    poster_path: media.poster_path ?? "",
    vote_average: media.vote_average ?? 0,
    completed: true,
  };

  if (media.media_type !== "tv") {
    const details = await api.getDetails(media);
    const durationSeconds =
      details.runtime && details.runtime > 0 ? details.runtime * 60 : 1;
    await api.progressBulkSave({
      ...base,
      status: "finished",
      duration_seconds: durationSeconds,
    });
    mutationFinished();
    return;
  }

  const [details, seasonList] = await Promise.all([
    api.getDetails(media),
    api.tvSeasons<TVSeason>(media.id),
  ]);
  const seasons = seasonList.filter(
    (season) => season.season_number > 0 && season.episode_count > 0,
  );
  const episodeLists = await Promise.all(
    seasons.map((season) =>
      api
        .tvEpisodes(media.id, season.season_number)
        .then((episodes) => ({ season: season.season_number, episodes })),
    ),
  );
  const episodes = episodeLists.flatMap(({ season, episodes }) =>
    (episodes ?? [])
      .filter(
        (episode: TVEpisode) => episode.episode_number > 0 && hasAired(episode),
      )
      .map((episode: TVEpisode) => ({
        season,
        episode: episode.episode_number,
        duration_seconds:
          episode.runtime && episode.runtime > 0 ? episode.runtime * 60 : 1,
      })),
  );
  if (episodes.length === 0) {
    throw new Error("No aired episodes are available to mark watched");
  }
  const lifecycle = details.status?.trim().toLowerCase();
  const hasStoppedAiring =
    lifecycle === "ended" ||
    lifecycle === "canceled" ||
    lifecycle === "cancelled";
  await api.progressBulkSave({
    ...base,
    status: hasStoppedAiring ? "finished" : "watching",
    episodes,
  });
  mutationFinished();
}

export async function performMediaUtilityAction(
  action: MediaUtilityAction,
  media: Media,
  state: MediaActionState,
): Promise<void> {
  switch (action) {
    case "mark-watched":
      await markMediaWatched(media);
      return;
    case "mark-unwatched":
      await api.progressBulkSave({
        tmdb_id: media.id,
        media_type: media.media_type,
        title: mediaTitle(media),
        poster_path: media.poster_path ?? "",
        vote_average: media.vote_average ?? 0,
        completed: false,
        status: "watching",
      });
      mutationFinished();
      return;
    case "toggle-not-interested":
      if (state.dismissed) {
        await api.undoNotInterested(media);
      } else {
        await api.notInterested(media);
      }
      mutationFinished();
      return;
    case "remove-from-library":
      await api.libraryRemove(media.id, media.media_type);
      mutationFinished();
  }
}
