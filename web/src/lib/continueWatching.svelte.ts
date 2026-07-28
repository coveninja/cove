import { api, formatPosition } from "$lib/api";
import { mediaFromEntry } from "$lib/mediaFromEntry";
import { nextUnwatchedAiredEpisode } from "$lib/nextEpisode";
import type { LibraryEntry, WatchProgress } from "$lib/types/library";
import type { Media, TVEpisode } from "$lib/types/tmdb";
import { SvelteMap } from "svelte/reactivity";

const MOVIE_MIN_SECONDS = 15;
const MAX_FRACTION = 0.95;

export interface ContinueItem {
  key: string;
  media: Media;
  title: string;
  image: string;
  mediaType: "movie" | "tv";
  season: number | null;
  episode: number | null;
  upNext: boolean;
  position: number;
  duration: number;
  watchedAt: string;
  progress: number;
}

export function continueSubtitle(item: ContinueItem): string {
  if (item.mediaType !== "tv") {
    return `${formatPosition(Math.max(0, item.duration - item.position))} left`;
  }
  const tag = `S${item.season}E${item.episode}`;
  return item.upNext ? `${tag} · Up Next` : tag;
}

export function continuePercent(item: ContinueItem): number {
  return Math.round(Math.min(1, Math.max(0, item.progress)) * 100);
}

function latestProgress(progress: WatchProgress[]): WatchProgress | null {
  if (progress.length === 0) return null;
  return progress.toSorted(
    (a, b) => Date.parse(b.watched_at) - Date.parse(a.watched_at),
  )[0];
}

function toMedia(entry: LibraryEntry): Media {
  return mediaFromEntry({
    id: entry.tmdb_id,
    media_type: entry.media_type,
    title: entry.title,
    name: entry.title,
    poster_path: entry.poster_path,
    vote_average: entry.vote_average,
  });
}

export class ContinueWatchingController {
  items = $state<ContinueItem[]>([]);
  loading = $state(true);

  #seasonCache = new SvelteMap<string, Promise<TVEpisode[]>>();
  #loadSequence = 0;

  #fetchSeason(id: number, season: number): Promise<TVEpisode[]> {
    const key = `${id}:${season}`;
    let request = this.#seasonCache.get(key);
    if (!request) {
      request = api
        .tvEpisodes(id, season)
        .then((episodes) => episodes ?? [])
        .catch(() => [] as TVEpisode[]);
      this.#seasonCache.set(key, request);
    }
    return request;
  }

  async #episodeStill(
    id: number,
    season: number,
    episode: number,
  ): Promise<string> {
    const episodes = await this.#fetchSeason(id, season);
    return (
      episodes.find((candidate) => candidate.episode_number === episode)
        ?.still_path ?? ""
    );
  }

  async #buildItem(entry: LibraryEntry): Promise<ContinueItem | null> {
    let progress: WatchProgress[];
    try {
      progress =
        (await api.libraryGet(entry.tmdb_id, entry.media_type))?.progress ?? [];
    } catch {
      return null;
    }

    const latest = latestProgress(progress);
    if (!latest) return null;

    const common = {
      key: `${entry.tmdb_id}-${entry.media_type}`,
      media: toMedia(entry),
      title: entry.title,
      watchedAt: latest.watched_at,
    };

    if (entry.media_type !== "tv") {
      if (latest.completed || latest.duration_seconds <= 0) return null;
      if (latest.position_seconds < MOVIE_MIN_SECONDS) return null;
      const fraction = latest.position_seconds / latest.duration_seconds;
      if (fraction > MAX_FRACTION) return null;
      return {
        ...common,
        image: entry.poster_path,
        mediaType: "movie",
        season: null,
        episode: null,
        upNext: false,
        position: latest.position_seconds,
        duration: latest.duration_seconds,
        progress: fraction,
      };
    }

    const season = latest.season ?? 1;
    const episode = latest.episode ?? 1;
    const fraction =
      latest.duration_seconds > 0
        ? latest.position_seconds / latest.duration_seconds
        : 0;

    if (!latest.completed && fraction <= MAX_FRACTION) {
      const still = await this.#episodeStill(entry.tmdb_id, season, episode);
      return {
        ...common,
        image: still || entry.poster_path,
        mediaType: "tv",
        season,
        episode,
        upNext: false,
        position: latest.position_seconds,
        duration: latest.duration_seconds,
        progress: fraction,
      };
    }

    const next = await nextUnwatchedAiredEpisode(
      entry.tmdb_id,
      season,
      episode,
      progress,
      (id, seasonNumber) => this.#fetchSeason(id, seasonNumber),
    );
    if (!next) return null;
    return {
      ...common,
      image: next.episode.still_path || entry.poster_path,
      mediaType: "tv",
      season: next.season,
      episode: next.episode.episode_number,
      upNext: true,
      position: 0,
      duration: 0,
      progress: 0,
    };
  }

  async load(): Promise<void> {
    const sequence = ++this.#loadSequence;
    this.loading = true;
    this.#seasonCache = new SvelteMap();
    try {
      const entries = await api.libraryList("watching");
      const results = await Promise.all(
        entries.map((entry) => this.#buildItem(entry)),
      );
      if (sequence !== this.#loadSequence) return;
      this.items = results
        .filter((item): item is ContinueItem => item !== null)
        .toSorted((a, b) => Date.parse(b.watchedAt) - Date.parse(a.watchedAt));
    } catch {
      if (sequence === this.#loadSequence) this.items = [];
    } finally {
      if (sequence === this.#loadSequence) this.loading = false;
    }
  }
}
