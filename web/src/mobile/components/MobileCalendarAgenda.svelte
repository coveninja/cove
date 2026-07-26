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
  } from "lucide-svelte";
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
  import { getContext } from "svelte";
  import { slide } from "svelte/transition";

  // ── Context ───────────────────────────────────────────────────────────────────

  const openDetail = getContext<((m: Media) => void) | undefined>("openMediaDetail");

  // ── State ─────────────────────────────────────────────────────────────────────

  let items = $state<CalendarItem[]>([]);
  let loading = $state(true);
  let expanded = $state(false);
  let showAllDays = $state(false);
  let collapsedGroups = $state<Record<string, boolean>>({});

  // ── Data ──────────────────────────────────────────────────────────────────────

  async function load(): Promise<void> {
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
    load();
  });

  // ── Derived ──────────────────────────────────────────────────────────────────

  const days = $derived(groupByDay(items));
  const summary = $derived(calendarSummary(items));
  const label = $derived(summaryLabel(summary));
  const chips = $derived(nextUp(items, 10));
  const isEmpty = $derived(!loading && items.length === 0);

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

  function openItem(item: CalendarItem): void {
    openDetail?.(toMedia(item));
  }

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

  function dayOfMonth(dateKey: string): number {
    return new Date(dateKey + "T00:00:00").getDate();
  }
</script>

<div class="mb-4">
  <!-- ── Header ──────────────────────────────────────────────────────────────── -->
  {#if isEmpty}
    <!-- No expand toggle when empty -->
    <div class="flex items-center gap-2 p-6">
      <CalendarDays class="size-4 text-muted-foreground" />
      <h2 class="text-base font-semibold">{m.calendar_calendar()}</h2>
    </div>
  {:else}
    <button
      onclick={() => (expanded = !expanded)}
      aria-expanded={expanded}
      class="flex w-full items-center gap-2 rounded-lg px-1 py-0.5 text-left transition-colors active:bg-secondary/70"
    >
      <CalendarDays class="size-4 shrink-0 text-muted-foreground" />
      <h2 class="flex-1 text-base font-semibold">{m.calendar_calendar()}</h2>
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
      <Skeleton class="h-3.5 w-40 rounded" />
      <div class="flex gap-2.5 overflow-hidden">
        {#each { length: 3 } as _, i (i)}
          <div class="w-36 shrink-0 space-y-1">
            <Skeleton class="aspect-video w-full rounded-md" />
            <Skeleton class="h-3 w-24 rounded" />
            <Skeleton class="h-2.5 w-12 rounded" />
          </div>
        {/each}
      </div>
    </div>

  {:else if isEmpty}
    <!-- Empty state compact -->
    <div class="mt-1 flex items-center gap-2 py-2 text-muted-foreground">
      <CalendarOff class="size-4 shrink-0 opacity-40" />
      <p class="text-xs">{m.calendar_empty()}</p>
    </div>

  {:else}
    <!-- Summary line (always visible) -->
    <p class="mt-0.5 px-1 text-xs text-muted-foreground">{label}</p>

    {#if !expanded}
      <!-- ── Collapsed: next-up chip strip ───────────────────────────────────── -->
      <!--
        Touch-native horizontal scroll: native inertia, hidden scrollbar per
        mobile conventions — mirrors the ContinueWatching and filter-chip rows.
      -->
      <div
        class="mt-2 flex gap-2.5 overflow-x-auto pb-1 [scrollbar-width:none] [-webkit-overflow-scrolling:touch] [&::-webkit-scrollbar]:hidden"
      >
        {#each chips as item (`${item.tmdb_id}-${item.kind}-${item.season_number ?? ""}-${item.episode_number ?? ""}`)}
          <button
            onclick={() => openItem(item)}
            class="flex w-36 shrink-0 flex-col overflow-hidden rounded-lg bg-secondary/40 text-left active:bg-secondary"
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
                <div
                  class="absolute bottom-1.5 left-1.5 h-2 w-2 rounded-full bg-accent"
                  aria-hidden="true"
                ></div>
              {/if}
            </span>
            <!-- Title + sublabel -->
            <span class="px-2 py-1.5">
              <span class="truncate text-xs font-medium leading-tight">
                {item.title}
              </span>
              <span
                class="mt-0.5 truncate text-[11px] leading-tight text-muted-foreground"
              >
                {chipSublabel(item)}
              </span>
            </span>
          </button>
        {/each}
      </div>

    {:else}
      <!-- ── Expanded: timeline ───────────────────────────────────────────────── -->
      <div transition:slide={{ duration: 200 }} class="mt-3">
        <div class="relative">
          <!-- Vertical connecting line -->
          <div
            class="absolute bottom-4 left-4 top-4 w-px bg-border"
            aria-hidden="true"
          ></div>

          {#each visibleDays as day, i (day.key)}
            <div class="flex gap-2 {i < visibleDays.length - 1 ? 'mb-4' : ''}">
              <!-- Date rail: ~w-8, narrower than desktop -->
              <div
                class="relative z-10 flex w-8 shrink-0 justify-center pt-0.5"
              >
                {#if day.key === "available"}
                  <div
                    class="flex h-7 w-7 items-center justify-center rounded-full bg-accent"
                  >
                    <CalendarDays class="size-3.5 text-accent-foreground" />
                  </div>
                {:else}
                  <div
                    class="flex h-7 w-7 items-center justify-center rounded-full border border-border bg-background"
                  >
                    <span class="text-[11px] font-semibold tabular-nums">
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
                  class="mb-1 flex w-full items-center gap-1.5 rounded px-1 py-0.5 text-left transition-colors active:bg-secondary/60"
                >
                  <span class="flex-1 text-[11px] font-semibold uppercase tracking-wider text-muted-foreground">
                    {day.label}
                  </span>
                  <span class="text-[11px] tabular-nums text-muted-foreground/60">{day.items.length}</span>
                  {#if collapsedGroups[day.key]}
                    <ChevronDown class="size-3 shrink-0 text-muted-foreground/60" />
                  {:else}
                    <ChevronUp class="size-3 shrink-0 text-muted-foreground/60" />
                  {/if}
                </button>

                {#if !collapsedGroups[day.key]}
                <div class="space-y-0.5 pb-3" transition:slide={{ duration: 150 }}>
                  {#each day.items as item (`${item.tmdb_id}-${item.kind}-${item.season_number ?? ""}-${item.episode_number ?? ""}`)}
                    <button
                      onclick={() => openItem(item)}
                      class="flex w-full items-center gap-2 rounded-lg px-1.5 py-1 text-left transition-colors active:bg-secondary/80"
                    >
                      <!-- Still / poster: ~h-14 w-24 (smaller than desktop h-16 w-28) -->
                      <span
                        class="relative h-14 w-24 shrink-0 overflow-hidden rounded-md"
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
                              <Tv class="size-3.5 text-muted-foreground/40" />
                            {:else}
                              <Film class="size-3.5 text-muted-foreground/40" />
                            {/if}
                          </div>
                        {/if}
                        {#if item.media_type === "tv" && item.season_number != null && item.episode_number != null}
                          <span
                            class="absolute bottom-1 left-1 rounded bg-black/70 px-1 py-0.5 text-[10px] font-medium leading-none text-white"
                          >
                            {m.common_season_episode({
                              season: item.season_number,
                              episode: item.episode_number,
                            })}
                          </span>
                        {/if}
                      </span>

                      <!-- Text (text-xs/sm) -->
                      <span class="min-w-0 flex-1">
                        <p class="truncate text-sm font-medium">{item.title}</p>
                        <p class="truncate text-xs text-muted-foreground">
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
                        </p>
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
            class="mt-1 flex w-full items-center justify-center gap-1 rounded-lg py-1.5 text-xs text-muted-foreground transition-colors active:bg-secondary"
          >
            <ChevronDown class="size-3.5" />
            {m.common_show_more_days({ count: hiddenDayCount })}
          </button>
        {/if}
      </div>
    {/if}
  {/if}
</div>
