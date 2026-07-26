<script lang="ts">
  import { api } from "$lib/api";
  import type { Media } from "$lib/types/tmdb";
  import type { CalendarItem } from "$lib/types/calendar";
  import { libraryChanged } from "$lib/stores/library";
  import {
    CalendarDays,
    Film,
    Tv,
    CalendarOff,
    ChevronDown,
    ChevronUp,
    ChevronLeft,
    ChevronRight,
  } from "lucide-svelte";
  import { Button } from "$lib/components/ui/button/index.js";
  import { Skeleton } from "$lib/components/ui/skeleton/index.js";
  import * as m from "$lib/paraglide/messages.js";
  import {
    groupByDay,
    calendarSummary,
    summaryLabel,
    nextUp,
    shortDateLabel,
  } from "$lib/calendar";
  import { mediaFromEntry } from "$lib/mediaFromEntry";
  import { slide } from "svelte/transition";

  let { onSelectMedia }: { onSelectMedia: (m: Media) => void } = $props();

  let items = $state<CalendarItem[]>([]);
  let loading = $state(true);
  let expanded = $state(false);
  let showAllDays = $state(false);
  let trackEl = $state<HTMLElement | null>(null);
  let collapsedGroups = $state<Record<string, boolean>>({});

  // ── Data ─────────────────────────────────────────────────────────────────────

  async function loadCalendar(): Promise<void> {
    loading = true;
    try {
      items = await api.libraryCalendar();
    } catch {
      items = [];
    } finally {
      loading = false;
    }
  }

  $effect(() => {
    $libraryChanged;
    loadCalendar();
  });

  // ── Derived ──────────────────────────────────────────────────────────────────

  const days = $derived(groupByDay(items));
  const summary = $derived(calendarSummary(items));
  const label = $derived(summaryLabel(summary));
  const chips = $derived(nextUp(items, 12));
  const isEmpty = $derived(!loading && items.length === 0);

  // Show "Available Now" + first MAX_DAYS_INITIAL date groups; rest hidden behind "show more".
  const MAX_DAYS_INITIAL = 7;

  const visibleDays = $derived.by(() => {
    const availDays = days.filter((d) => d.key === "available");
    const dateDays = days.filter((d) => d.key !== "available");
    if (showAllDays || dateDays.length <= MAX_DAYS_INITIAL) {
      return [...availDays, ...dateDays];
    }
    return [...availDays, ...dateDays.slice(0, MAX_DAYS_INITIAL)];
  });

  const hiddenDayCount = $derived.by(() => {
    const dateDays = days.filter((d) => d.key !== "available");
    return Math.max(0, dateDays.length - MAX_DAYS_INITIAL);
  });

  // ── Helpers ──────────────────────────────────────────────────────────────────

  function toggleGroup(key: string): void {
    collapsedGroups[key] = !collapsedGroups[key];
  }

  function toMedia(item: CalendarItem): Media {
    if (item.media_type === "tv") {
      return mediaFromEntry({
        id: item.tmdb_id,
        media_type: "tv",
        name: item.title,
        poster_path: item.poster_path,
      });
    }
    return mediaFromEntry({
      id: item.tmdb_id,
      media_type: "movie",
      title: item.title,
      poster_path: item.poster_path,
    });
  }

  /** Short label shown below the chip thumbnail. */
  function chipSublabel(item: CalendarItem): string {
    if (item.kind === "available") {
      if (
        item.media_type === "tv" &&
        item.season_number != null &&
        item.episode_number != null
      ) {
        return `S${item.season_number}E${item.episode_number}`;
      }
      return "Watch";
    }
    return shortDateLabel(item.date);
  }

  /** Day-of-month number for the timeline rail bubble. */
  function dayOfMonth(dateKey: string): number {
    return new Date(dateKey + "T00:00:00").getDate();
  }

  // ── Strip scroll ─────────────────────────────────────────────────────────────

  function scrollByCards(direction: 1 | -1): void {
    if (!trackEl) return;
    trackEl.scrollBy({
      left: direction * (trackEl.clientWidth * 0.9),
      behavior: "smooth",
    });
  }

  function handleWheel(e: WheelEvent): void {
    if (!trackEl || e.deltaX !== 0) return;
    const canScroll =
      (e.deltaY > 0 &&
        trackEl.scrollLeft < trackEl.scrollWidth - trackEl.clientWidth) ||
      (e.deltaY < 0 && trackEl.scrollLeft > 0);
    if (!canScroll) return;
    e.preventDefault();
    trackEl.scrollBy({ left: e.deltaY, behavior: "smooth" });
  }
</script>

<div class="rounded-2xl border p-4">
  <!-- ── Header ──────────────────────────────────────────────────────────────── -->
  {#if isEmpty}
    <!-- No expand toggle when empty -->
    <div class="flex items-center gap-2 px-1 py-0.5">
      <CalendarDays class="size-4 shrink-0 text-muted-foreground" />
      <h2 class="text-lg font-semibold">{m.calendar_calendar()}</h2>
    </div>
  {:else}
    <button
      onclick={() => (expanded = !expanded)}
      aria-expanded={expanded}
      class="flex w-full items-center gap-2 rounded-lg px-1 py-0.5 text-left transition-colors hover:bg-secondary/50"
    >
      <CalendarDays class="size-4 shrink-0 text-muted-foreground" />
      <span class="flex-1 text-lg font-semibold">{m.calendar_calendar()}</span>
      {#if expanded}
        <ChevronUp class="size-4 shrink-0 text-muted-foreground" />
      {:else}
        <ChevronDown class="size-4 shrink-0 text-muted-foreground" />
      {/if}
    </button>
  {/if}

  <!-- ── Body ────────────────────────────────────────────────────────────────── -->

  {#if loading}
    <!-- Skeleton matching collapsed height -->
    <div class="mt-2 space-y-2">
      <Skeleton class="h-3.5 w-44 rounded" />
      <div class="flex gap-2 overflow-hidden">
        {#each { length: 4 } as _, i (i)}
          <div class="w-40 shrink-0 space-y-1">
            <Skeleton class="aspect-video w-full rounded-lg" />
            <Skeleton class="h-3 w-28 rounded" />
            <Skeleton class="h-2.5 w-16 rounded" />
          </div>
        {/each}
      </div>
    </div>

  {:else if isEmpty}
    <!-- Empty state compact -->
    <div class="mt-2 flex items-center gap-3 py-3 text-muted-foreground">
      <CalendarOff class="size-5 shrink-0 opacity-40" />
      <p class="text-sm">{m.calendar_empty()}</p>
    </div>

  {:else}
    <!-- Summary line (always visible) -->
    <p class="mt-1 px-1 text-xs text-muted-foreground">{label}</p>

    {#if !expanded}
      <!-- ── Collapsed: next-up chip strip ────────────────────────────────────── -->
      <div class="mt-2 flex items-center gap-2">
        <Button
          onclick={() => scrollByCards(-1)}
          variant="outline"
          size="icon"
          aria-label={m.common_scroll_left()}
        >
          <ChevronLeft class="size-4" />
        </Button>
        <div
          bind:this={trackEl}
          class="flex min-w-0 flex-1 gap-2 overflow-x-auto pb-1 [&::-webkit-scrollbar]:hidden"
          onwheel={handleWheel}
        >
          {#each chips as item (`${item.tmdb_id}-${item.kind}-${item.season_number ?? ""}-${item.episode_number ?? ""}`)}
            <button
              onclick={() => onSelectMedia(toMedia(item))}
              class="flex w-64 h-42 border shrink-0 flex-col overflow-hidden rounded-lg bg-card text-left transition-colors hover:bg-secondary"
              title={item.title}
            >
              <!-- 16:9 thumbnail -->
              <span class="relative w-full overflow-hidden">
                {#if item.still_path}
                  <img
                    src={item.still_path}
                    alt={item.title}
                    class="aspect-video w-full object-cover"
                  />
                {:else if item.poster_path}
                  <img
                    src={item.poster_path}
                    alt={item.title}
                    class="aspect-video w-full object-cover"
                  />
                {:else}
                  <div
                    class="flex aspect-video w-full items-center justify-center bg-secondary"
                  >
                    {#if item.media_type === "tv"}
                      <Tv class="size-5 text-muted-foreground/40" />
                    {:else}
                      <Film class="size-5 text-muted-foreground/40" />
                    {/if}
                  </div>
                {/if}
                {#if item.kind === "available"}
                  <!-- Small accent dot indicating "ready to watch" -->
                  <div
                    class="absolute bottom-1 left-1 h-1.5 w-1.5 rounded-full bg-accent"
                    aria-hidden="true"
                  ></div>
                {/if}
              </span>
              <!-- Title + sublabel -->
              <span class="py-1.5 flex flex-col p-4">
                <span class="truncate text-base font-medium leading-tight">
                  {item.title}
                </span>
                <span
                  class="mt-0.5 truncate text-sm leading-tight text-muted-foreground"
                >
                  {chipSublabel(item)}
                </span>
              </span>
            </button>
          {/each}
        </div>
        <Button
          onclick={() => scrollByCards(1)}
          variant="outline"
          size="icon"
          aria-label={m.common_scroll_right()}
        >
          <ChevronRight class="size-4" />
        </Button>
      </div>

    {:else}
      <!-- ── Expanded: timeline ────────────────────────────────────────────────── -->
      <div transition:slide={{ duration: 200 }} class="mt-3">
        <div class="relative">
          <!-- Vertical connecting line (behind the bubbles) -->
          <div
            class="absolute bottom-4 left-5 top-4 w-px bg-border"
            aria-hidden="true"
          ></div>

          {#each visibleDays as day, i (day.key)}
            <div class="flex gap-3 {i < visibleDays.length - 1 ? 'mb-5' : ''}">
              <!-- Date rail: bubble centered at left-5 (20px) -->
              <div
                class="relative z-10 flex w-10 shrink-0 justify-center pt-0.5"
              >
                {#if day.key === "available"}
                  <div
                    class="flex h-8 w-8 items-center justify-center rounded-full bg-accent"
                  >
                    <CalendarDays class="size-3.5 text-accent-foreground" />
                  </div>
                {:else}
                  <div
                    class="flex h-8 w-8 items-center justify-center rounded-full border border-border bg-background"
                  >
                    <span class="text-base font-semibold tabular-nums">
                      {dayOfMonth(day.key)}
                    </span>
                  </div>
                {/if}
              </div>

              <!-- Day content -->
              <div class="min-w-0 flex-1">
                <button
                  onclick={() => toggleGroup(day.key)}
                  aria-expanded={!collapsedGroups[day.key]}
                  class="mb-1.5 flex w-full items-center gap-2 rounded-md px-1 py-0.5 text-left transition-colors hover:bg-secondary/50"
                >
                  <span class="flex-1 px-4 py-2 text-sm font-semibold uppercase tracking-wider text-muted-foreground">
                    {day.label}
                  </span>
                  <span class="text-xs tabular-nums text-muted-foreground/60">{day.items.length}</span>
                  {#if collapsedGroups[day.key]}
                    <ChevronDown class="size-3.5 shrink-0 text-muted-foreground/60" />
                  {:else}
                    <ChevronUp class="size-3.5 shrink-0 text-muted-foreground/60" />
                  {/if}
                </button>

                {#if !collapsedGroups[day.key]}
                <div class="space-y-1 pb-4" transition:slide={{ duration: 150 }}>
                  {#each day.items as item (`${item.tmdb_id}-${item.kind}-${item.season_number ?? ""}-${item.episode_number ?? ""}`)}
                    <button
                      onclick={() => onSelectMedia(toMedia(item))}
                      class="flex w-full items-center gap-3 rounded-lg px-2 py-1.5 text-left transition-colors hover:bg-secondary"
                    >
                      <!-- Still / poster with episode badge -->
                      <span
                        class="relative h-16 w-28 shrink-0 overflow-hidden rounded-lg"
                      >
                        {#if item.still_path}
                          <img
                            src={item.still_path}
                            alt={item.title}
                            class="h-full w-full object-cover"
                          />
                        {:else if item.poster_path}
                          <img
                            src={item.poster_path}
                            alt={item.title}
                            class="h-full w-full object-cover"
                          />
                        {:else}
                          <div
                            class="flex h-full w-full items-center justify-center bg-secondary"
                          >
                            {#if item.media_type === "tv"}
                              <Tv class="size-5 text-muted-foreground/40" />
                            {:else}
                              <Film class="size-5 text-muted-foreground/40" />
                            {/if}
                          </div>
                        {/if}
                        {#if item.media_type === "tv" && item.season_number != null && item.episode_number != null}
                          <span
                            class="absolute bottom-1 left-1 rounded bg-black/70 px-1 py-0.5 text-[9px] font-medium leading-none text-white"
                          >
                            {m.common_season_episode({
                              season: item.season_number,
                              episode: item.episode_number,
                            })}
                          </span>
                        {/if}
                      </span>

                      <!-- Text -->
                      <span class="min-w-0 flex flex-1 flex-col">
                        <span class="truncate text-base font-medium">{item.title}</span>
                        <span class="truncate text-sm text-muted-foreground">
                          {#if item.media_type === "tv" && item.episode_name}
                            {item.episode_name}{#if item.kind === "available" && item.waiting_count > 1}&nbsp;<span
                                class="font-medium text-accent"
                                >{m.common_more_count({
                                  count: item.waiting_count - 1,
                                })}</span
                              >{/if}
                          {:else if item.media_type === "tv" && item.season_number != null}
                            {m.common_season_number({
                              season: item.season_number,
                            })}
                          {:else}
                            &nbsp;
                          {/if}
                        </span>
                      </span>
                    </button>
                  {/each}
                </div>
                {/if}
              </div>
            </div>
          {/each}
        </div>

        <!-- Show N more days -->
        {#if !showAllDays && hiddenDayCount > 0}
          <button
            onclick={() => (showAllDays = true)}
            class="mt-1 flex w-full items-center justify-center gap-1.5 rounded-lg py-2 text-sm text-muted-foreground transition-colors hover:bg-secondary hover:text-foreground"
          >
            <ChevronDown class="size-4" />
            {m.common_show_more_days({ count: hiddenDayCount })}
          </button>
        {/if}
      </div>
    {/if}
  {/if}
</div>
