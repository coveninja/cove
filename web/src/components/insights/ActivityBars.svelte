<script lang="ts">
  import { fmtHours } from "./utils";

  /**
   * Reusable vertical bar chart.
   *
   * Props:
   *   items     – primary series: { label, value } (values are seconds)
   *   secondary – optional parallel array of comparison values (last year, etc.)
   *   title     – optional section label shown above bars
   *   compact   – true for dense 24-bar hour-of-day layout (hides value labels)
   */
  let {
    items,
    secondary,
    title,
    compact = false,
  }: {
    items: { label: string; value: number }[];
    secondary?: number[];
    title?: string;
    compact?: boolean;
  } = $props();

  const maxVal = $derived.by(() => {
    let m = 1;
    for (const it of items) if (it.value > m) m = it.value;
    if (secondary) for (const v of secondary) if (v > m) m = v;
    return m;
  });

  // Bar height as percentage of container (0-100)
  function pct(val: number): number {
    return maxVal === 0 ? 0 : Math.round((val / maxVal) * 100);
  }

  // How many bar labels to show (skip every-other for dense layouts)
  const labelEvery = $derived(compact ? 4 : 1);
</script>

<div class="flex flex-col gap-2">
  {#if title}
    <p class="text-xs font-medium text-muted-foreground">{title}</p>
  {/if}

  <!-- Bar area with fixed height -->
  <div
    class="flex items-end gap-0.5 overflow-hidden"
    style="height: {compact ? 44 : 72}px"
  >
    {#each items as item, i (i)}
      {@const primaryH = pct(item.value)}
      {@const ghostH = secondary ? pct(secondary[i] ?? 0) : 0}
      <!-- Column: bars stacked in a relative container anchored to bottom -->
      <div class="relative flex h-full flex-1 items-end">
        <!-- Ghost bar (last year / secondary) — rendered behind primary -->
        {#if secondary && ghostH > 0}
          <div
            class="absolute bottom-0 w-full rounded-t-xs bg-muted/60"
            style="height: max({ghostH}%, 2px)"
          ></div>
        {/if}
        <!-- Primary bar (this year) -->
        <div
          class="relative w-full rounded-t-xs bg-accent/75 transition-[height]"
          style="height: max({primaryH}%, {item.value > 0 ? 2 : 0}px)"
        ></div>
      </div>
    {/each}
  </div>

  <!-- Labels row -->
  <div class="flex gap-0.5">
    {#each items as item, i (i)}
      <div
        class="flex flex-1 justify-center truncate text-[9px] text-muted-foreground"
        title={item.label}
      >
        {i % labelEvery === 0 ? item.label : ""}
      </div>
    {/each}
  </div>

  <!-- Optional value labels (hidden in compact mode) -->
  {#if !compact}
    <div class="flex gap-0.5">
      {#each items as item, i (i)}
        <div
          class="flex flex-1 justify-center truncate text-[9px] text-muted-foreground/60"
        >
          {item.value > 0 ? fmtHours(item.value) : ""}
        </div>
      {/each}
    </div>
  {/if}
</div>

<!-- Legend for secondary (last-year comparison) -->
{#if secondary}
  <div class="mt-1 flex items-center gap-3 text-[10px] text-muted-foreground">
    <span class="flex items-center gap-1">
      <span class="inline-block size-2.5 rounded-[2px] bg-indigo-500/75"></span>
      This year
    </span>
    <span class="flex items-center gap-1">
      <span class="inline-block size-2.5 rounded-[2px] bg-muted/60"></span>
      Last year
    </span>
  </div>
{/if}
