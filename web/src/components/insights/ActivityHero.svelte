<script lang="ts">
  import type { ActivityStats } from "$lib/api";
  import { fmtHours } from "./utils";
  import * as Card from "$lib/components/ui/card/index.js";
  import { TrendingUp, TrendingDown } from "lucide-svelte";
  import * as m from "$lib/paraglide/messages.js";
  import { intlLocale } from "$lib/i18n";

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
  <Card.Content class="flex flex-col items-center gap-3 p-4 text-center">
    <p
      class="font-medium uppercase tracking-widest text-muted-foreground"
    >
      {m.account_time_spent()}
    </p>

    <div class="flex items-baseline gap-2">
      <span
        class="font-mono text-7xl font-black leading-none text-accent [font-variant-numeric:tabular-nums]"
      >
        {hours.toLocaleString(intlLocale())}
      </span>
      <span class="text-2xl font-light text-muted-foreground">{m.common_hours_unit()}</span>
    </div>

    <p class="text-muted-foreground">
      {m.account_across_titles({
        count: activity.total_titles.toLocaleString(intlLocale()),
      })}
    </p>

    <div class="flex flex-wrap items-center justify-center gap-2 pt-1">
      {#if yoyPct !== null}
        {@const positive = yoyPct >= 0}
        <span
          class="inline-flex items-center gap-1.5 rounded-full px-3 py-1 font-medium {positive
            ? 'bg-emerald-500/15 text-emerald-400'
            : 'bg-rose-500/15 text-rose-400'}"
        >
          {#if positive}
            <TrendingUp class="size-3" />
            {m.account_year_comparison({ percent: `+${yoyPct}`, year: lastYear })}
          {:else}
            <TrendingDown class="size-3" />
            {m.account_year_comparison({ percent: yoyPct, year: lastYear })}
          {/if}
        </span>
      {/if}

      {#if activity.titles_this_year > 0}
        <span
          class="rounded-full bg-muted/60 px-3 py-1 text-muted-foreground"
        >
          {m.account_titles_in_year({
            count: activity.titles_this_year,
            hours: fmtHours(activity.this_year_seconds),
            year: currentYear,
          })}
        </span>
      {/if}
    </div>
  </Card.Content>
</Card.Root>
