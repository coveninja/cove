<script lang="ts">
  import MobileHero from "../components/MobileHero.svelte";
  import MobileMediaRow from "../components/MobileMediaRow.svelte";
  import ContinueWatching from "../../components/ContinueWatching.svelte";
  import type { Media } from "$lib/types/tmdb";
  import { api, type DiscoverInsights, type LibraryStats } from "$lib/api";
  import type { CatalogRef } from "$lib/types/addons";
  import type { Page } from "$lib/types/types";
  import { onMount, tick } from "svelte";
  import { SvelteMap } from "svelte/reactivity";

  // Same contract as desktop HomePage so MobileApp.svelte can slot this in
  // with identical prop bindings.
  let {
    onSelectMedia,
    onWatch,
    visible = true,
    onChangePage,
  }: {
    onSelectMedia: (m: Media) => void;
    onWatch?: (m: Media, season?: number, episode?: number) => void;
    visible?: boolean;
    onChangePage?: (p: Page) => void;
  } = $props();

  // ── Row management (same approach as HomePage) ────────────────────────────
  type Row = { key: string; header: string; medias: Media[]; loading: boolean };
  type RowSpec = { key: string; header: string; load: () => Promise<Media[]> };

  let rows = $state<Row[]>([]);
  let catalogRows = $state<Row[]>([]);
  const catalogRefMap = new SvelteMap<string, CatalogRef>();

  const PER_BUCKET = 2;
  const ROW_LIMIT = 20;
  const cap = (s: string) => (s ? s[0].toUpperCase() + s.slice(1) : s);

  function patchRow(key: string, patch: Partial<Row>): void {
    const i = rows.findIndex((r) => r.key === key);
    if (i !== -1) rows[i] = { ...rows[i], ...patch };
  }

  function startRow(spec: RowSpec): void {
    rows = [...rows, { key: spec.key, header: spec.header, medias: [], loading: true }];
    spec
      .load()
      .then((d) => patchRow(spec.key, { medias: d }))
      .catch(() => patchRow(spec.key, { medias: [] }))
      .finally(() => patchRow(spec.key, { loading: false }));
  }

  function patchCatalogRow(key: string, patch: Partial<Row>): void {
    const i = catalogRows.findIndex((r) => r.key === key);
    if (i !== -1) catalogRows[i] = { ...catalogRows[i], ...patch };
  }

  function startCatalogRow(spec: RowSpec): void {
    catalogRows = [
      ...catalogRows,
      { key: spec.key, header: spec.header, medias: [], loading: true },
    ];
    spec
      .load()
      .then((d) => patchCatalogRow(spec.key, { medias: d }))
      .catch(() => patchCatalogRow(spec.key, { medias: [] }))
      .finally(() => patchCatalogRow(spec.key, { loading: false }));
  }

  function tasteSpecs(
    insights: DiscoverInsights,
    primaryType: "movie" | "tv",
  ): RowSpec[] {
    const mg = insights.top_movie_genres.slice(0, PER_BUCKET);
    const tg = insights.top_tv_genres.slice(0, PER_BUCKET);
    const kw = insights.top_keywords.slice(0, PER_BUCKET);
    const specs: RowSpec[] = [];
    for (let i = 0; i < Math.max(mg.length, tg.length, kw.length); i++) {
      const movieGenre = mg[i];
      if (movieGenre)
        specs.push({
          key: `mg-${movieGenre.id}`,
          header: `${movieGenre.name} movies`,
          load: () => api.discoverByGenre("movie", movieGenre.id, { limit: ROW_LIMIT }),
        });
      const tvGenre = tg[i];
      if (tvGenre)
        specs.push({
          key: `tg-${tvGenre.id}`,
          header: `${tvGenre.name} shows`,
          load: () => api.discoverByGenre("tv", tvGenre.id, { limit: ROW_LIMIT }),
        });
      const keyword = kw[i];
      if (keyword)
        specs.push({
          key: `kw-${keyword.id}`,
          header: cap(keyword.name),
          load: () =>
            api.discoverByKeyword(primaryType, keyword.id, { limit: ROW_LIMIT }),
        });
    }
    return specs;
  }

  // ── Watch / select wrappers (pause hero timer while overlay is open) ───────
  let heroVisible = $state(true);

  async function handleOnWatch(
    m: Media,
    season?: number,
    episode?: number,
  ): Promise<void> {
    heroVisible = false;
    await tick();
    onWatch?.(m, season, episode);
  }

  async function handleSelectMedia(m: Media): Promise<void> {
    heroVisible = false;
    await tick();
    onSelectMedia(m);
  }

  onMount(() => {
    startRow({
      key: "tastes",
      header: "Based on your tastes",
      load: () => api.discover("all", { limit: ROW_LIMIT }),
    });

    Promise.all([
      api.discoverInsights().catch(() => null),
      api.libraryStats().catch(() => null),
    ]).then(([insights, stats]: [DiscoverInsights | null, LibraryStats | null]) => {
      if (!insights || insights.signals_used === 0) return;
      const primaryType =
        stats && stats.tv_share > stats.movie_share ? "tv" : "movie";
      for (const spec of tasteSpecs(insights, primaryType)) startRow(spec);
    });

    api
      .getCatalogs()
      .catch(() => [] as CatalogRef[])
      .then((refs) => {
        for (const ref of refs) {
          const key = `catalog-${ref.addonId}-${ref.catalogType}/${ref.catalogId}`;
          catalogRefMap.set(key, ref);
          startCatalogRow({
            key,
            header: ref.name,
            load: () =>
              api
                .catalogPage(ref.addonId, ref.catalogType, ref.catalogId, 0, 20)
                .then((r) => r.medias),
          });
        }
      });
  });

  // Resume timer when page becomes visible again.
  $effect(() => {
    if (visible) heroVisible = true;
  });
</script>

<div class="h-full w-full overflow-y-auto overscroll-contain [scrollbar-width:none] [&::-webkit-scrollbar]:hidden">
  <div class="flex flex-col gap-4 pb-8">
    <!-- Full-bleed hero (no top padding — goes under status bar) -->
    <MobileHero visible={visible && heroVisible} />

    <!-- Catalog rows (addon catalogs, e.g. Debrid) -->
    {#each catalogRows as row (row.key)}
      {@const ref = catalogRefMap.get(row.key)}
      <MobileMediaRow
        header={row.header}
        medias={row.medias}
        loading={row.loading}
        onSelect={handleSelectMedia}
        onWatch={handleOnWatch}
        onSeeAll={ref
          ? () =>
              onChangePage?.({
                type: "catalog",
                addonId: ref.addonId,
                catalogType: ref.catalogType,
                catalogId: ref.catalogId,
                name: ref.name,
              })
          : undefined}
      />
    {/each}

    <!-- Continue watching -->
    <ContinueWatching onWatch={handleOnWatch} onSelectMedia={handleSelectMedia} navEnabled={false} />

    <!-- Taste-driven rows -->
    {#each rows as row (row.key)}
      <MobileMediaRow
        header={row.header}
        medias={row.medias}
        loading={row.loading}
        onSelect={handleSelectMedia}
        onWatch={handleOnWatch}
      />
    {/each}
  </div>
</div>
