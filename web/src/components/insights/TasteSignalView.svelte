<script lang="ts">
  import type { DiscoverInsights, ContributingTitle } from "$lib/api";
  import { mediaFromEntry } from "$lib/mediaFromEntry";
  import * as Card from "$lib/components/ui/card/index.js";
  import * as Chart from "$lib/components/ui/chart/index.js";
  import { getContext } from "svelte";
  import type { Media } from "$lib/types/tmdb";
  import { Tag } from "lucide-svelte";
  import { LineChart } from "layerchart";
  import { curveLinearClosed } from "d3-shape";
  import { scaleBand } from "d3-scale";
  import {SvelteMap} from "svelte/reactivity";

  let { insights }: { insights: DiscoverInsights } = $props();

  const openDetail = getContext<((m: Media) => void) | undefined>(
          "openMediaDetail",
  );

  // ── Diverging signal chart ─────────────────────────────────────────────────
  //
  // Combine positive genres (top movie + TV, deduped) and negative genres
  // (disliked) into one list, plus positive keywords. Each item is signed.
  // Sort by |score| desc, cap at 16 entries.

  type SignalItem = { name: string; score: number };

  const divergingItems = $derived.by((): SignalItem[] => {
    // Deduplicate genres by id, keep highest |score|, keep sign.
    // Positive genres land first; disliked genres may overwrite if their
    // |score| is higher (same dedup rule as before, sign preserved).
    const genreMap = new SvelteMap<number, SignalItem>();

    for (const g of [...insights.top_movie_genres, ...insights.top_tv_genres]) {
      const existing = genreMap.get(g.id);
      if (!existing || Math.abs(g.score) > Math.abs(existing.score)) {
        genreMap.set(g.id, { name: g.name, score: Math.abs(g.score) });
      }
    }
    for (const g of insights.disliked_genres) {
      const existing = genreMap.get(g.id);
      const neg = -Math.abs(g.score);
      if (!existing || Math.abs(neg) > Math.abs(existing.score)) {
        genreMap.set(g.id, { name: g.name, score: neg });
      }
    }

    // Split into positives and disliked after dedup
    const positiveGenres: SignalItem[] = [];
    const dislikedGenres: SignalItem[] = [];
    for (const item of genreMap.values()) {
      if (item.score >= 0) positiveGenres.push(item);
      else dislikedGenres.push(item);
    }

    // Keywords as positive signals
    const kwItems: SignalItem[] = insights.top_keywords
            .slice(0, 8)
            .map((k) => ({ name: k.name, score: k.score }));

    // Reserve up to 5 disliked slots so they're never crowded out by high
    // positive scores, then fill the rest of the 16-item budget with positives.
    const disliked = dislikedGenres
            .sort((a, b) => Math.abs(b.score) - Math.abs(a.score))
            .slice(0, 5);
    const positives = [...positiveGenres, ...kwItems].sort(
            (a, b) => Math.abs(b.score) - Math.abs(a.score),
    );
    const positiveSlots = 16 - disliked.length;
    const selected = [...positives.slice(0, positiveSlots), ...disliked];

    // Final display order: highest |score| first
    selected.sort((a, b) => Math.abs(b.score) - Math.abs(a.score));
    return selected;
  });

  // ── Radar chart data ────────────────────────────────────────────────────
  //
  // Radar/spider charts get unreadable with too many spokes, so each side is
  // capped tighter than the diverging bar chart was (8 vs 16).

  type RadarPoint = { name: string; value: number };

  const RADAR_MAX_ITEMS = 8;

  const likedRadarData = $derived.by((): RadarPoint[] =>
          divergingItems
                  .filter((i) => i.score >= 0)
                  .sort((a, b) => b.score - a.score)
                  .slice(0, RADAR_MAX_ITEMS)
                  .map((i) => ({ name: i.name, value: i.score })),
  );

  const dislikedRadarData = $derived.by((): RadarPoint[] =>
          divergingItems
                  .filter((i) => i.score < 0)
                  .sort((a, b) => Math.abs(b.score) - Math.abs(a.score))
                  .slice(0, RADAR_MAX_ITEMS)
                  .map((i) => ({ name: i.name, value: Math.abs(i.score) })),
  );

  const likedChartConfig = {
    value: { label: "Liked", color: "var(--accent)" },
  } satisfies Chart.ChartConfig;

  const dislikedChartConfig = {
    value: { label: "Disliked", color: "#ef4444" },
  } satisfies Chart.ChartConfig;

  // ── Contributor poster row ─────────────────────────────────────────────────

  function openContributor(c: ContributingTitle): void {
    if (!openDetail) return;
    openDetail(
            mediaFromEntry({
              id: c.tmdb_id,
              media_type: c.media_type,
              poster_path: c.poster_path,
              ...(c.media_type === "tv" ? { name: c.title } : { title: c.title }),
            }),
    );
  }

  function weightLabel(w: number): string {
    const abs = Math.abs(w);
    if (abs < 0.1) return "±0";
    const sign = w >= 0 ? "+" : "−";
    return `${sign}${abs.toFixed(1)}`;
  }
</script>

<!-- ── Diverging score chart ────────────────────────────────────────────── -->
{#if divergingItems.length > 0}
  <Card.Root>
    <Card.Header>
      <Card.Title class="flex items-center gap-2">
        <Tag class="size-4" />
        Your taste signals
      </Card.Title>
      <Card.Description>
        Genres &amp; themes that shape your recommendations
      </Card.Description>
    </Card.Header>
    <Card.Content>
      <div class="grid grid-cols-1 gap-6 sm:grid-cols-2">
        <!-- Liked radar -->
        <div class="flex flex-col items-center">
          <p class="mb-2 font-medium text-accent/80">Liked</p>
          {#if likedRadarData.length > 0}
            <Chart.Container
                    config={likedChartConfig}
                    class="mx-auto aspect-square max-h-62.5 w-full"
            >
              <LineChart
                      data={likedRadarData}
                      series={[
                  {
                    key: "value",
                    label: "Liked",
                    color: likedChartConfig.value.color,
                  },
                ]}
                      radial
                      x="name"
                      xScale={scaleBand()}
                      padding={12}
                      props={{
                  spline: {
                    curve: curveLinearClosed,
                    fill: "var(--color-value)",
                    fillOpacity: 0.6,
                    stroke: "0",
                    motion: "tween",
                  },
                  xAxis: {
                    tickLength: 0,
                  },
                  yAxis: {
                    format: () => "",
                  },
                  grid: {
                    radialY: "linear",
                  },
                  tooltip: {
                    context: {
                      mode: "voronoi",
                    },
                  },
                  highlight: {
                    lines: false,
                  },
                }}
              >
                {#snippet tooltip()}
                  <Chart.Tooltip />
                {/snippet}
              </LineChart>
            </Chart.Container>
          {:else}
            <p class="text-sm text-muted-foreground">No liked signals yet</p>
          {/if}
        </div>

        <!-- Disliked radar -->
        <div class="flex flex-col items-center">
          <p class="mb-2 font-medium text-red-400/80">Disliked</p>
          {#if dislikedRadarData.length > 0}
            <Chart.Container
                    config={dislikedChartConfig}
                    class="mx-auto aspect-square max-h-62.5 w-full"
            >
              <LineChart
                      data={dislikedRadarData}
                      series={[
                  {
                    key: "value",
                    label: "Disliked",
                    color: dislikedChartConfig.value.color,
                  },
                ]}
                      radial
                      x="name"
                      xScale={scaleBand()}
                      padding={12}
                      props={{
                  spline: {
                    curve: curveLinearClosed,
                    fill: "var(--color-value)",
                    fillOpacity: 0.6,
                    stroke: "0",
                    motion: "tween",
                  },
                  xAxis: {
                    tickLength: 0,
                  },
                  yAxis: {
                    format: () => "",
                  },
                  grid: {
                    radialY: "linear",
                  },
                  tooltip: {
                    context: {
                      mode: "voronoi",
                    },
                  },
                  highlight: {
                    lines: false,
                  },
                }}
              >
                {#snippet tooltip()}
                  <Chart.Tooltip />
                {/snippet}
              </LineChart>
            </Chart.Container>
          {:else}
            <p class="text-sm text-muted-foreground">No disliked signals yet</p>
          {/if}
        </div>
      </div>
    </Card.Content>
  </Card.Root>
{/if}

<!-- ── Titles that shaped your profile ───────────────────────────────────── -->
{#if (insights.top_contributors?.length ?? 0) > 0 || (insights.negative_contributors?.length ?? 0) > 0}
  <Card.Root>
    <Card.Header>
      <Card.Title>Titles that shaped your profile</Card.Title>
      <Card.Description>
        Positive and negative contributors to your taste
      </Card.Description>
    </Card.Header>
    <Card.Content class="flex flex-col gap-4">
      {#if insights.top_contributors?.length > 0}
        <div>
          <p class="mb-2 font-medium text-accent/80">
            Strongest positive influence
          </p>
          <div class="flex gap-3 overflow-x-auto pb-2">
            {#each insights.top_contributors as c (c.tmdb_id + c.media_type)}
              <div
                      class="group shrink-0 cursor-pointer"
                      onclick={() => openContributor(c)}
                      onkeydown={(e) => e.key === "Enter" && openContributor(c)}
                      role="button"
                      tabindex="0"
              >
                <div
                        class="w-32 h-48 relative overflow-hidden rounded-lg border-2 border-accent/30 transition-colors group-hover:border-accent/60"
                >
                  {#if c.poster_path}
                    <img
                            src={c.poster_path}
                            alt={c.title}
                            class="h-full w-full object-cover"
                            loading="lazy"
                    />
                  {:else}
                    <div
                            class="flex h-full items-center justify-center bg-muted/50 p-1 text-center text-[9px] text-muted-foreground"
                    >
                      {c.title}
                    </div>
                  {/if}
                  <!-- Weight chip -->
                  <div
                          class="absolute bottom-1 right-1 rounded bg-accent/80 px-1 py-0.5 text-[9px] font-semibold text-white"
                  >
                    {weightLabel(c.weight)}
                  </div>
                </div>
              </div>
            {/each}
          </div>
        </div>
      {/if}

      {#if insights.negative_contributors?.length > 0}
        <div>
          <p class="mb-2 font-medium text-red-500/80">
            Strongest negative influence
          </p>
          <div class="flex gap-3 overflow-x-auto pb-2">
            {#each insights.negative_contributors as c (c.tmdb_id + c.media_type)}
              <div
                      class="group shrink-0 cursor-pointer"
                      onclick={() => openContributor(c)}
                      onkeydown={(e) => e.key === "Enter" && openContributor(c)}
                      role="button"
                      tabindex="0"
              >
                <div
                        class="w-32 h-48 relative overflow-hidden rounded-lg border-2 border-red-500/30 transition-colors group-hover:border-red-500/60"
                >
                  {#if c.poster_path}
                    <img
                            src={c.poster_path}
                            alt={c.title}
                            class="h-full w-full object-cover"
                            loading="lazy"
                    />
                  {:else}
                    <div
                            class="flex h-full items-center justify-center bg-muted/50 p-1 text-center text-[9px] text-muted-foreground"
                    >
                      {c.title}
                    </div>
                  {/if}
                  <!-- Weight chip -->
                  <div
                          class="absolute bottom-1 right-1 rounded bg-red-500/80 px-1 py-0.5 text-[9px] font-semibold text-white"
                  >
                    {weightLabel(c.weight)}
                  </div>
                </div>
              </div>
            {/each}
          </div>
        </div>
      {/if}
    </Card.Content>
  </Card.Root>
{/if}