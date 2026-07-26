import { api } from "$lib/api";
import { hasAired } from "$lib/nextEpisode";
import { libraryChanged } from "$lib/stores/library";
import type { LibraryEntry } from "$lib/types/library";
import type { Media, TVEpisode, TVSeason } from "$lib/types/tmdb";

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
          ? "Mark aired episodes watched"
          : "Mark as watched",
      sublabel:
        media.media_type === "tv"
          ? "Completes every released regular episode"
          : "Completes the movie and moves it to Finished",
    });
  }
  if (state.hasProgress || state.entry?.status === "finished") {
    items.push({
      id: "mark-unwatched",
      label: "Reset watch progress",
      sublabel:
        media.media_type === "tv"
          ? "Marks every saved episode unwatched"
          : "Marks the movie unwatched",
      destructive: true,
    });
  }
  items.push({
    id: "toggle-not-interested",
    label: state.dismissed ? "Undo not interested" : "Not interested",
    sublabel: state.dismissed
      ? "Allow this title in recommendations again"
      : "Reduce similar recommendations",
  });
  if (state.entry) {
    items.push({
      id: "remove-from-library",
      label: "Remove from My List",
      sublabel: "Watch history is preserved",
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
