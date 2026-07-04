<script lang="ts">
  import type { ActivityStats } from "$lib/api";
  import { fmtHours } from "./utils";
  import * as Card from "$lib/components/ui/card/index.js";
  import { TrendingUp, TrendingDown } from "lucide-svelte";

  let { activity }: { activity: ActivityStats } = $props();

  const hours = $derived(Math.floor(activity.total_seconds / 3600));
  const currentYear = new Date().getFullYear();
  const lastYear = currentYear - 1;

  // Year-over-year percentage change, only when last year has data.
  const yoyPct = $derived(
    activity.last_year_seconds > 0
      ? Math.round(
          ((activity.this_year_seconds - activity.last_year_seconds) /
            activity.last_year_seconds) *
            100,
        )
      : null,
  );
</script>

<Card.Root class="overflow-hidden">
  <Card.Content class="flex flex-col items-center gap-3 px-8 py-10 text-center">
    <p
      class="text-xs font-medium uppercase tracking-widest text-muted-foreground"
    >
      Your watch time
    </p>

    <div class="flex items-baseline gap-2">
      <span
        class="font-mono text-7xl font-black leading-none text-indigo-400 [font-variant-numeric:tabular-nums]"
      >
        {hours.toLocaleString()}
      </span>
      <span class="text-2xl font-light text-muted-foreground">h</span>
    </div>

    <p class="text-sm text-muted-foreground">
      across <span class="font-semibold text-foreground"
        >{activity.total_titles.toLocaleString()}</span
      > titles
    </p>

    <div class="flex flex-wrap items-center justify-center gap-2 pt-1">
      {#if yoyPct !== null}
        {@const positive = yoyPct >= 0}
        <span
          class="inline-flex items-center gap-1.5 rounded-full px-3 py-1 text-xs font-medium {positive
            ? 'bg-emerald-500/15 text-emerald-400'
            : 'bg-rose-500/15 text-rose-400'}"
        >
          {#if positive}
            <TrendingUp class="size-3" />
            +{yoyPct}% vs {lastYear}
          {:else}
            <TrendingDown class="size-3" />
            {yoyPct}% vs {lastYear}
          {/if}
        </span>
      {/if}

      {#if activity.titles_this_year > 0}
        <span
          class="rounded-full bg-muted/60 px-3 py-1 text-xs text-muted-foreground"
        >
          {activity.titles_this_year} titles · {fmtHours(
            activity.this_year_seconds,
          )} in {currentYear}
        </span>
      {/if}
    </div>
  </Card.Content>
</Card.Root>
