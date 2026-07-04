<script lang="ts">
  import type { DiscoverInsights, ContributingTitle } from "$lib/api";
  import { mediaFromEntry } from "$lib/mediaFromEntry";
  import * as Card from "$lib/components/ui/card/index.js";
  import { getContext } from "svelte";
  import type { Media } from "$lib/types/tmdb";
  import { Tag } from "lucide-svelte";

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
    // Deduplicate genres by id, keep highest |score|, keep sign
    const genreMap = new Map<number, SignalItem>();

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

    // Keywords as positive signals
    const kwItems: SignalItem[] = insights.top_keywords
      .slice(0, 8)
      .map((k) => ({ name: k.name, score: k.score }));

    const all = [...Array.from(genreMap.values()), ...kwItems];
    all.sort((a, b) => Math.abs(b.score) - Math.abs(a.score));
    return all.slice(0, 16);
  });

  const maxScore = $derived(
    divergingItems.reduce((m, i) => Math.max(m, Math.abs(i.score)), 0.001),
  );

  // Bar pct: 0-100, relative to each half column
  function barPct(score: number): number {
    return Math.round((Math.abs(score) / maxScore) * 100);
  }

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
      <Card.Title class="flex items-center gap-2 text-sm">
        <Tag class="size-4" />
        Your taste signals
      </Card.Title>
      <Card.Description>
        Genres &amp; themes that shape your recommendations
      </Card.Description>
    </Card.Header>
    <Card.Content>
      <!-- Column headers -->
      <div
        class="mb-2 grid text-[10px] font-medium text-muted-foreground/70"
        style="grid-template-columns: 1fr {Math.min(140, 30)}px 1fr"
      >
        <div class="text-right pr-2 text-indigo-400/70">liked</div>
        <div></div>
        <div class="pl-2 text-rose-400/70">disliked</div>
      </div>

      <!-- Diverging rows -->
      <div class="flex flex-col gap-[5px]">
        {#each divergingItems as item, i (i)}
          {@const pct = barPct(item.score)}
          {@const isPos = item.score >= 0}
          <div
            class="grid items-center"
            style="grid-template-columns: 1fr auto 1fr"
          >
            <!-- Left column: liked bar (right-aligned, grows toward center) -->
            <div class="flex items-center justify-end pr-[3px]">
              {#if isPos}
                <div
                  class="h-2.5 rounded-l-[3px] bg-indigo-500/65"
                  style="width: {pct}%"
                ></div>
              {/if}
            </div>

            <!-- Center: item name -->
            <div
              class="max-w-[120px] truncate px-2 text-center text-[11px] text-muted-foreground"
              title={item.name}
            >
              {item.name}
            </div>

            <!-- Right column: disliked bar (left-aligned, grows away from center) -->
            <div class="flex items-center pl-[3px]">
              {#if !isPos}
                <div
                  class="h-2.5 rounded-r-[3px] bg-rose-500/55"
                  style="width: {pct}%"
                ></div>
              {/if}
            </div>
          </div>
        {/each}
      </div>
    </Card.Content>
  </Card.Root>
{/if}

<!-- ── Titles that shaped your profile ───────────────────────────────────── -->
{#if (insights.top_contributors?.length ?? 0) > 0 || (insights.negative_contributors?.length ?? 0) > 0}
  <Card.Root>
    <Card.Header>
      <Card.Title class="text-sm">Titles that shaped your profile</Card.Title>
      <Card.Description>
        Positive and negative contributors to your taste
      </Card.Description>
    </Card.Header>
    <Card.Content class="flex flex-col gap-4">
      {#if insights.top_contributors?.length > 0}
        <div>
          <p class="mb-2 text-xs font-medium text-indigo-400/80">
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
                  class="relative overflow-hidden rounded-lg border-2 border-indigo-500/30 transition-colors group-hover:border-indigo-500/60"
                  style="width: 72px; height: 108px"
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
                    class="absolute bottom-1 right-1 rounded bg-indigo-500/80 px-1 py-0.5 text-[9px] font-semibold text-white"
                  >
                    {weightLabel(c.weight)}
                  </div>
                </div>
                <p
                  class="mt-1 max-w-[72px] truncate text-center text-[9px] text-muted-foreground"
                >
                  {c.title}
                </p>
              </div>
            {/each}
          </div>
        </div>
      {/if}

      {#if insights.negative_contributors?.length > 0}
        <div>
          <p class="mb-2 text-xs font-medium text-rose-400/80">
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
                  class="relative overflow-hidden rounded-lg border-2 border-rose-500/30 transition-colors group-hover:border-rose-500/60"
                  style="width: 72px; height: 108px"
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
                    class="absolute bottom-1 right-1 rounded bg-rose-500/80 px-1 py-0.5 text-[9px] font-semibold text-white"
                  >
                    {weightLabel(c.weight)}
                  </div>
                </div>
                <p
                  class="mt-1 max-w-[72px] truncate text-center text-[9px] text-muted-foreground"
                >
                  {c.title}
                </p>
              </div>
            {/each}
          </div>
        </div>
      {/if}
    </Card.Content>
  </Card.Root>
{/if}
