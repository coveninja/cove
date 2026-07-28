import { api } from "$lib/api";
import { toMediaKey, type GenreNames, type MediaByKey } from "$lib/myList";
import type { LibraryEntry } from "$lib/types/library";
import { SvelteMap } from "svelte/reactivity";

/**
 * Shared library-entry and TMDB-metadata loader for all three My List shells.
 * Filtering and rendering remain platform-specific; requests, caches, and
 * stale-response handling live here.
 */
export class MyListDataController {
  entries = $state<LibraryEntry[]>([]);
  loading = $state(true);
  mediaByKey = $state<MediaByKey>({});
  genreNames = $state<GenreNames>({ movie: {}, tv: {} });

  #entryLoadSequence = 0;
  #mediaLoads = new SvelteMap<string, Promise<void>>();

  async loadEntries(showSpinner = true): Promise<void> {
    const sequence = ++this.#entryLoadSequence;
    if (showSpinner) this.loading = true;
    try {
      const entries = await api.libraryList();
      if (sequence !== this.#entryLoadSequence) return;
      this.entries = entries;
      this.#hydrate(entries);
    } catch {
      // Keep the last successful library visible during a transient failure.
    } finally {
      if (sequence === this.#entryLoadSequence && this.loading) {
        this.loading = false;
      }
    }
  }

  async loadGenreNames(): Promise<void> {
    try {
      const [movie, tv] = await Promise.all([
        api.genreList("movie"),
        api.genreList("tv"),
      ]);
      this.genreNames = {
        movie: Object.fromEntries(movie.map((genre) => [genre.id, genre.name])),
        tv: Object.fromEntries(tv.map((genre) => [genre.id, genre.name])),
      };
    } catch {
      // Genre labels are optional; the list still works without them.
    }
  }

  #hydrate(entries: LibraryEntry[]): void {
    for (const entry of entries) {
      const key = toMediaKey(entry);
      if (this.mediaByKey[key] || this.#mediaLoads.has(key)) continue;

      const request = api
        .getMediaByID(entry.tmdb_id, entry.media_type)
        .then((media) => {
          this.mediaByKey[key] = media;
        })
        .catch(() => {
          // The stored poster remains available as a non-interactive fallback.
        })
        .finally(() => {
          this.#mediaLoads.delete(key);
        });
      this.#mediaLoads.set(key, request);
    }
  }
}
