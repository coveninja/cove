import { api } from "$lib/api";
import { emptyResults, normalizeResults } from "$lib/search";
import type { SearchResults } from "$lib/api";

export interface SearchScheduleHooks {
  beforeLoad?: (query: string) => void | Promise<void>;
  afterLoad?: (results: SearchResults) => void;
  onClear?: () => void;
  onLoading?: (loading: boolean) => void;
}

/**
 * Debounces and sequences search requests. Calling schedule again invalidates
 * both a pending timer and an already-running request immediately.
 */
export class SearchController {
  data = $state<SearchResults>(emptyResults());
  keywords = $state<{ id: number; name: string }[]>([]);
  loading = $state(false);

  #sequence = 0;
  #timer: ReturnType<typeof setTimeout> | null = null;

  schedule(
    query: string,
    hooks: SearchScheduleHooks = {},
    delay = 400,
  ): () => void {
    this.cancel(hooks);
    const sequence = ++this.#sequence;
    const normalizedQuery = query.trim();

    if (!normalizedQuery) {
      this.data = emptyResults();
      this.keywords = [];
      hooks.onClear?.();
      return () => this.#cancelSequence(sequence, hooks);
    }

    this.#timer = setTimeout(() => {
      this.#timer = null;
      void this.#load(normalizedQuery, sequence, hooks);
    }, delay);
    return () => this.#cancelSequence(sequence, hooks);
  }

  cancel(hooks: Pick<SearchScheduleHooks, "onLoading"> = {}): void {
    this.#sequence++;
    if (this.#timer) clearTimeout(this.#timer);
    this.#timer = null;
    this.#setLoading(false, hooks);
  }

  #cancelSequence(sequence: number, hooks: SearchScheduleHooks): void {
    if (sequence !== this.#sequence) return;
    this.cancel(hooks);
  }

  #setLoading(loading: boolean, hooks: SearchScheduleHooks): void {
    this.loading = loading;
    hooks.onLoading?.(loading);
  }

  async #load(
    query: string,
    sequence: number,
    hooks: SearchScheduleHooks,
  ): Promise<void> {
    await hooks.beforeLoad?.(query);
    if (sequence !== this.#sequence) return;
    this.#setLoading(true, hooks);

    const [results, keywords] = await Promise.all([
      api.searchMulti(query).catch(() => emptyResults()),
      api.getKeywords(query).catch(() => []),
    ]);
    if (sequence !== this.#sequence) return;

    this.data = normalizeResults(results);
    this.keywords = keywords ?? [];
    this.#setLoading(false, hooks);
    hooks.afterLoad?.(this.data);
  }
}
