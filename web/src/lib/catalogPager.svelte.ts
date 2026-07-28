import { api } from "$lib/api";
import type { Media } from "$lib/types/tmdb";
import { SvelteSet } from "svelte/reactivity";

export interface CatalogIdentity {
  addonId: string;
  catalogType: string;
  catalogId: string;
  addonUrl?: string;
}

interface CatalogPagerOptions {
  pageSize?: number;
  maxItems?: number;
}

const sameIdentity = (
  left: CatalogIdentity | null,
  right: CatalogIdentity,
): boolean =>
  left?.addonId === right.addonId &&
  left.catalogType === right.catalogType &&
  left.catalogId === right.catalogId &&
  left.addonUrl === right.addonUrl;

/** Shared, bounded and stale-request-safe pagination for addon catalogs. */
export class CatalogPager {
  medias = $state<Media[]>([]);
  nextSkip = $state(0);
  hasMore = $state(false);
  loading = $state(false);

  #seen = new SvelteSet<string>();
  #generation = 0;
  #identity: CatalogIdentity | null = null;
  #pageSize: number;
  #maxItems: number;

  constructor(options: CatalogPagerOptions = {}) {
    this.#pageSize = options.pageSize ?? 40;
    this.#maxItems = options.maxItems ?? 600;
  }

  reset(identity: CatalogIdentity): void {
    if (sameIdentity(this.#identity, identity)) return;
    this.#identity = { ...identity };
    this.#generation++;
    this.medias = [];
    this.nextSkip = 0;
    this.hasMore = false;
    this.loading = false;
    this.#seen.clear();
    if (identity.addonId) void this.loadPage(0);
  }

  async loadMore(): Promise<void> {
    await this.loadPage(this.nextSkip);
  }

  async loadPage(skip: number): Promise<void> {
    const identity = this.#identity;
    if (!identity || !identity.addonId || this.loading) return;

    this.loading = true;
    const generation = this.#generation;
    try {
      const result = await api.catalogPage(
        identity.addonId,
        identity.catalogType,
        identity.catalogId,
        skip,
        this.#pageSize,
        identity.addonUrl,
      );
      if (generation !== this.#generation) return;

      const room = Math.max(0, this.#maxItems - this.medias.length);
      if (room === 0) {
        this.hasMore = false;
        return;
      }
      const fresh: Media[] = [];
      for (const media of result.medias) {
        const key = `${media.media_type}:${media.id}`;
        if (!this.#seen.has(key)) {
          this.#seen.add(key);
          fresh.push(media);
          if (fresh.length === room) break;
        }
      }
      this.medias = [...this.medias, ...fresh];
      this.nextSkip = result.nextSkip;
      this.hasMore =
        result.medias.length > 0 && this.medias.length < this.#maxItems;
    } catch (error) {
      console.error("CatalogPager: failed to load page", error);
    } finally {
      if (generation === this.#generation) this.loading = false;
    }
  }
}
