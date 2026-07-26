<script lang="ts">
  import * as m from "$lib/paraglide/messages.js";
  import type { ActivityStats } from "$lib/api";
  import {
    activityWeekdayLabels,
    buildActivityCalendar,
    fmtHours,
    type ActivityCalendarCell,
  } from "./utils";
  import * as Card from "$lib/components/ui/card/index.js";
  import { CalendarDays } from "lucide-svelte";

  let { activity }: { activity: ActivityStats } = $props();

  // ── Calendar geometry constants ──────────────────────────────────────────
  const CELL_PX = 11; // cell size in px
  const GAP_PX = 3; // gap between cells
  const STEP = CELL_PX + GAP_PX; // column/row step
  const DAY_LABEL_W = 20; // left label column width in px

  // Day-of-week labels: show Mon (1), Wed (3), Fri (5); others hidden.
  const DOW_LABELS = activityWeekdayLabels();
  const calData = $derived(buildActivityCalendar(activity.calendar));

  // ── Cell color (sequential: one hue, muted→indigo) ───────────────────────
  // Using rgba so we don't depend on Tailwind purge for these dynamic values.
  function cellStyle(level: number, inRange: boolean): string {
    if (!inRange) return "background:transparent";
    if (level === 0) return "background:rgba(255,255,255,0.06)";
    const opacities = [0, 0.18, 0.42, 0.67, 0.92];
    return `background:rgba(38,223,106,${opacities[level]})`;
  }

  // ── Simple hover tooltip (lighter than 365 Tooltip.Root instances) ────────
  type HoverTooltip = { x: number; y: number; text: string } | null;
  let tooltip = $state<HoverTooltip>(null);

  function onCellEnter(e: MouseEvent, cell: ActivityCalendarCell): void {
    if (!cell.inRange) return;
    const el = e.currentTarget as HTMLElement;
    const rect = el.getBoundingClientRect();
    const timeStr =
      cell.seconds > 0 ? fmtHours(cell.seconds) : m.account_no_activity();
    tooltip = {
      x: rect.left + rect.width / 2,
      y: rect.top,
      text: `${cell.label} · ${timeStr}`,
    };
  }

  function onCellLeave(): void {
    tooltip = null;
  }

  const gridWidth = $derived(calData.totalWeeks * STEP - GAP_PX);
</script>

<Card.Root>
  <Card.Header>
    <Card.Title class="flex items-center gap-2 text-sm">
      <CalendarDays class="size-4" />
      {m.account_activity()}
    </Card.Title>
    <Card.Description>{m.account_trailing_months()}</Card.Description>
  </Card.Header>

  <Card.Content>
    <!-- Scroll wrapper: horizontal overflow only -->
    <div class="overflow-x-auto pb-1">
      <div class="relative" style="width: {DAY_LABEL_W + GAP_PX + gridWidth}px">
        <!-- Month labels row -->
        <div
          class="relative mb-1 h-4"
          style="margin-left: {DAY_LABEL_W + GAP_PX}px"
        >
          {#each calData.monthLabels as m (m.weekIdx)}
            <span
              class="absolute select-none text-[10px] leading-4 text-muted-foreground"
              style="left: {m.weekIdx * STEP}px"
            >
              {m.name}
            </span>
          {/each}
        </div>

        <!-- Day labels + cell grid -->
        <div class="flex gap-0.75">
          <!-- Day-of-week labels: fixed 7-row column -->
          <div
            class="flex shrink-0 flex-col gap-0.75"
            style="width: {DAY_LABEL_W}px"
          >
            {#each DOW_LABELS as label, i (i)}
              <div
                class="flex items-center text-[10px] leading-none text-muted-foreground"
                style="height: {CELL_PX}px"
              >
                {label}
              </div>
            {/each}
          </div>

          <!-- Calendar cell grid (grid-auto-flow: column, 7 rows) -->
          <div
            class="grid gap-0.75"
            style="grid-template-rows: repeat(7, {CELL_PX}px); grid-auto-flow: column; grid-auto-columns: {CELL_PX}px"
          >
            {#each calData.cells as cell (cell.dateStr)}
              <!-- svelte-ignore a11y_no_static_element_interactions -->
              <div
                class="rounded-xs transition-opacity hover:opacity-80"
                style="{cellStyle(
                  cell.level,
                  cell.inRange,
                )}; width: {CELL_PX}px; height: {CELL_PX}px"
                onmouseenter={(e) => onCellEnter(e, cell)}
                onmouseleave={onCellLeave}
              ></div>
            {/each}
          </div>
        </div>
      </div>
    </div>
  </Card.Content>
</Card.Root>

<!-- Single lightweight hover tooltip, rendered outside scroll container -->
{#if tooltip}
  <div
    class="pointer-events-none fixed z-50 select-none rounded-lg bg-foreground px-2.5 py-1 text-xs text-background shadow"
    style="left: {tooltip.x}px; top: {tooltip.y}px; transform: translate(-50%, calc(-100% - 6px))"
  >
    {tooltip.text}
  </div>
{/if}
