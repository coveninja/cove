import { api, type DiscoverInsights, type LibraryStats } from "$lib/api";
import * as m from "$lib/paraglide/messages.js";
import type { CatalogRef } from "$lib/types/addons";
import type { Media } from "$lib/types/tmdb";
import { SvelteMap } from "svelte/reactivity";

export const HOME_ROW_LIMIT = 20;
export const HOME_TASTE_BUCKET_LIMIT = 2;

export interface HomeFeedRow {
  key: string;
  header: string;
  medias: Media[];
  loading: boolean;
}

interface HomeFeedRowSpec {
  key: string;
  header: string;
  load: () => Promise<Media[]>;
}

const capitalize = (value: string) =>
  value ? value[0].toUpperCase() + value.slice(1) : value;

/** Build the personalized rows in the same interleaved order on every shell. */
export function homeTasteSpecs(
  insights: DiscoverInsights,
  primaryType: "movie" | "tv",
): HomeFeedRowSpec[] {
  const movieGenres = insights.top_movie_genres.slice(
    0,
    HOME_TASTE_BUCKET_LIMIT,
  );
  const tvGenres = insights.top_tv_genres.slice(0, HOME_TASTE_BUCKET_LIMIT);
  const keywords = insights.top_keywords.slice(0, HOME_TASTE_BUCKET_LIMIT);
  const specs: HomeFeedRowSpec[] = [];

  for (
    let index = 0;
    index < Math.max(movieGenres.length, tvGenres.length, keywords.length);
    index++
  ) {
    const movieGenre = movieGenres[index];
    if (movieGenre) {
      specs.push({
        key: `mg-${movieGenre.id}`,
        header: m.explore_genre_movies({ genre: movieGenre.name }),
        load: () =>
          api.discoverByGenre("movie", movieGenre.id, {
            limit: HOME_ROW_LIMIT,
          }),
      });
    }

    const tvGenre = tvGenres[index];
    if (tvGenre) {
      specs.push({
        key: `tg-${tvGenre.id}`,
        header: m.explore_genre_shows({ genre: tvGenre.name }),
        load: () =>
          api.discoverByGenre("tv", tvGenre.id, { limit: HOME_ROW_LIMIT }),
      });
    }

    const keyword = keywords[index];
    if (keyword) {
      specs.push({
        key: `kw-${keyword.id}`,
        header: capitalize(keyword.name),
        load: () =>
          api.discoverByKeyword(primaryType, keyword.id, {
            limit: HOME_ROW_LIMIT,
          }),
      });
    }
  }

  return specs;
}

/**
 * Owns Home's API orchestration and row state. Desktop, mobile, and TV retain
 * their own renderers and interaction models while consuming this one feed.
 */
export class HomeFeedController {
  rows = $state<HomeFeedRow[]>([]);
  catalogRows = $state<HomeFeedRow[]>([]);
  catalogRefs = new SvelteMap<string, CatalogRef>();

  #loadSequence = 0;

  #patch(
    target: "rows" | "catalogRows",
    key: string,
    patch: Partial<HomeFeedRow>,
    sequence: number,
  ): void {
    if (sequence !== this.#loadSequence) return;
    const rows = this[target];
    const index = rows.findIndex((row) => row.key === key);
    if (index !== -1) rows[index] = { ...rows[index], ...patch };
  }

  #start(
    target: "rows" | "catalogRows",
    spec: HomeFeedRowSpec,
    sequence: number,
  ): Promise<void> {
    if (sequence !== this.#loadSequence) return Promise.resolve();
    this[target] = [
      ...this[target],
      { key: spec.key, header: spec.header, medias: [], loading: true },
    ];
    return spec
      .load()
      .then((medias) => this.#patch(target, spec.key, { medias }, sequence))
      .catch(() => this.#patch(target, spec.key, { medias: [] }, sequence))
      .finally(() =>
        this.#patch(target, spec.key, { loading: false }, sequence),
      );
  }

  async load(): Promise<void> {
    const sequence = ++this.#loadSequence;
    this.rows = [];
    this.catalogRows = [];
    this.catalogRefs.clear();

    const tastes = this.#start(
      "rows",
      {
        key: "tastes",
        header: m.home_based_on_tastes(),
        load: () => api.discover("all", { limit: HOME_ROW_LIMIT }),
      },
      sequence,
    );

    const personalized = Promise.all([
      api.discoverInsights().catch(() => null),
      api.libraryStats().catch(() => null),
    ]).then(
      async ([insights, stats]: [
        DiscoverInsights | null,
        LibraryStats | null,
      ]) => {
        if (
          sequence !== this.#loadSequence ||
          !insights ||
          insights.signals_used === 0
        ) {
          return;
        }
        const primaryType =
          stats && stats.tv_share > stats.movie_share ? "tv" : "movie";
        await Promise.all(
          homeTasteSpecs(insights, primaryType).map((spec) =>
            this.#start("rows", spec, sequence),
          ),
        );
      },
    );

    const catalogs = api
      .getCatalogs()
      .catch(() => [] as CatalogRef[])
      .then(async (refs) => {
        if (sequence !== this.#loadSequence) return;
        await Promise.all(
          refs.map((ref) => {
            const key = `catalog-${ref.addonId}-${ref.catalogType}/${ref.catalogId}`;
            this.catalogRefs.set(key, ref);
            return this.#start(
              "catalogRows",
              {
                key,
                header: ref.name,
                load: () =>
                  api
                    .catalogPage(
                      ref.addonId,
                      ref.catalogType,
                      ref.catalogId,
                      0,
                      HOME_ROW_LIMIT,
                      ref.addonUrl,
                    )
                    .then((result) => result.medias),
              },
              sequence,
            );
          }),
        );
      });

    await Promise.all([tastes, personalized, catalogs]);
  }
}
