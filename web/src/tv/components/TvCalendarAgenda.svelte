<script lang="ts">
  import { api } from "$lib/api";
  import type { Media } from "$lib/types/tmdb";
  import type { CalendarItem } from "$lib/types/calendar";
  import { libraryChanged } from "$lib/stores/library";
  import {
    CalendarDays,
    Film,
    Tv,
    ChevronDown,
    ChevronUp,
  } from "lucide-svelte";
  import { Spinner } from "$lib/components/ui/spinner/index.js";
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
  import { focusGroup, focusable } from "../focus/actions";

  // ── Context ───────────────────────────────────────────────────────────────────

  const openDetail = getContext<((m: Media) => void) | undefined>("openMediaDetail");

  // ── State ─────────────────────────────────────────────────────────────────────

  let items = $state<CalendarItem[]>([]);
  let loading = $state(true);
  let expanded = $state(false);
  let showAllDays = $state(false);

  /**
   * Bound to the header toggle button so focus can be programmatically
   * restored to it when collapsing — guards against the case where focus
   * was inside the (now-unmounting) timeline subtree.
   */
  let headerButtonEl = $state<HTMLButtonElement | null>(null);

  let collapsedGroups = $state<Record<string, boolean>>({});

  /**
   * Registry of per-group header button elements.
   * Not reactive — only used for programmatic focus on collapse.
   */
  const groupHeaderEls: Record<string, HTMLElement | null> = {};

  function bindHeaderEl(el: HTMLElement, key: string): { destroy(): void } {
    groupHeaderEls[key] = el;
    return {
      destroy() {
        if (groupHeaderEls[key] === el) groupHeaderEls[key] = null;
      },
    };
  }

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

  /**
   * Toggles expand/collapse. On collapse, explicitly moves focus to the
   * header button so that if focus was somewhere inside the timeline subtree
   * (elements about to be unmounted), it does not silently escape to body.
   */
  function toggleExpanded(): void {
    expanded = !expanded;
    if (!expanded) {
      headerButtonEl?.focus({ preventScroll: true });
    }
  }

  /**
   * Toggles a per-day group collapsed/expanded.
   * On collapse, moves focus to that group's header button so that if focus
   * was inside the (now-unmounting) items subtree it doesn't escape to body.
   */
  function toggleGroup(key: string): void {
    const wasExpanded = !collapsedGroups[key];
    collapsedGroups[key] = !collapsedGroups[key];
    if (wasExpanded) {
      groupHeaderEls[key]?.focus({ preventScroll: true });
    }
  }
</script>

<!--
  Two-state calendar for the TV shell.

  Collapsed (default): header toggle (focusGroup "calendar-header") + summary
  line + horizontal chip strip (focusGroup "calendar-chips", D-pad row policy).

  Expanded: same header + summary, then a vertical timeline where each date
  section is a column-policy focusGroup (id "calendar-{key}") so D-pad Up/Down
  moves between rows within a day and Up/Down at the edge crosses to the next
  day group via cross-group navigation.

  Focus on collapse: toggleExpanded() calls focus() on the bound header button
  element. Since the header is never unmounted, this is always reachable.

  Renders nothing when loading=false and items.length=0 — an empty block would
  steal D-pad vertical space with no navigable targets.
-->
{#if loading}
  <div class="flex items-center justify-center py-6">
    <Spinner class="size-8" />
  </div>
{:else if !isEmpty}
  <div class="space-y-3 pb-4">

    <!-- ── Header toggle (single focusable in its own group) ─────────────────── -->
    <div
      use:focusGroup={{ id: "calendar-header", policy: { type: "row" } }}
    >
      <button
        bind:this={headerButtonEl}
        type="button"
        use:focusable={{ groupId: "calendar-header" }}
        onclick={toggleExpanded}
        onkeydown={(e) => {
          if (e.key === "Enter") {
            e.preventDefault();
            toggleExpanded();
          }
        }}
        aria-expanded={expanded}
        class="flex w-full items-center gap-3 rounded-xl px-3 py-2 text-left transition-[transform,filter] duration-150 focus:scale-[1.02] focus:brightness-[1.15] focus:outline-none"
      >
        <CalendarDays class="size-5 shrink-0 text-muted-foreground" />
        <span class="flex-1 text-base font-semibold">{m.calendar_calendar()}</span>
        {#if expanded}
          <ChevronUp class="size-5 shrink-0 text-muted-foreground" />
        {:else}
          <ChevronDown class="size-5 shrink-0 text-muted-foreground" />
        {/if}
      </button>
    </div>

    <!-- Summary line (always visible, not focusable) -->
    <p class="px-3 text-sm text-muted-foreground">{label}</p>

    {#if !expanded}
      <!-- ── Collapsed: chip strip ────────────────────────────────────────────── -->
      <!--
        overflow-x-hidden lets the focus engine's scrollIntoView() drive the
        strip on D-pad left/right, consistent with other TV media rows.
      -->
      <div
        use:focusGroup={{
          id: "calendar-chips",
          policy: { type: "row" },
          rememberFocus: true,
        }}
        class="flex gap-3 overflow-x-hidden p-4"
      >
        {#each chips as item (`${item.tmdb_id}-${item.kind}-${item.season_number ?? ""}-${item.episode_number ?? ""}`)}
          <button
            type="button"
            use:focusable={{ groupId: "calendar-chips" }}
            onclick={() => openItem(item)}
            onkeydown={(e) => {
              if (e.key === "Enter") {
                e.preventDefault();
                openItem(item);
              }
            }}
            class="flex w-64 h-42 shrink-0 flex-col overflow-hidden rounded-xl bg-secondary/50 text-left transition-[transform,filter] duration-150 focus:scale-[1.08] focus:brightness-[1.15] focus:outline-none"
            title={item.title}
          >
            <!-- 16:9 thumbnail — larger than desktop (w-24 → w-40) -->
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
            <div class="px-2 py-1.5">
              <p class="truncate text-sm font-medium leading-tight">
                {item.title}
              </p>
              <p
                class="mt-0.5 truncate text-xs leading-tight text-muted-foreground"
              >
                {chipSublabel(item)}
              </p>
            </div>
          </button>
        {/each}
      </div>

    {:else}
      <!-- ── Expanded: timeline ────────────────────────────────────────────────── -->
      <div transition:slide={{ duration: 200 }} class="mt-2">
        <div class="relative">
          <!-- Vertical connecting line (behind bubbles) -->
          <div
            class="absolute bottom-4 left-6 top-4 w-px bg-border"
            aria-hidden="true"
          ></div>

          {#each visibleDays as day, i (day.key)}
            <div class="flex gap-4 {i < visibleDays.length - 1 ? 'mb-6' : ''}">
              <!-- Date rail: bubble centred at left-6 (24 px) -->
              <div
                class="relative z-10 flex w-12 shrink-0 justify-center pt-1"
              >
                {#if day.key === "available"}
                  <div
                    class="flex h-10 w-10 items-center justify-center rounded-full bg-accent"
                  >
                    <CalendarDays class="size-4 text-accent-foreground" />
                  </div>
                {:else}
                  <div
                    class="flex h-10 w-10 items-center justify-center rounded-full border border-border bg-background"
                  >
                    <span class="text-sm font-semibold tabular-nums">
                      {dayOfMonth(day.key)}
                    </span>
                  </div>
                {/if}
              </div>

              <!-- Day content -->
              <div class="min-w-0 flex-1">
                <!-- Group header: own focusGroup so D-pad Up/Down hits header → items → next header -->
                <div use:focusGroup={{ id: `calendar-head-${day.key}`, policy: { type: "row" } }}>
                  <button
                    type="button"
                    use:focusable={{ groupId: `calendar-head-${day.key}` }}
                    use:bindHeaderEl={day.key}
                    onclick={() => toggleGroup(day.key)}
                    onkeydown={(e) => {
                      if (e.key === "Enter") {
                        e.preventDefault();
                        toggleGroup(day.key);
                      }
                    }}
                    aria-expanded={!collapsedGroups[day.key]}
                    class="mb-2 flex w-full items-center gap-3 rounded-xl px-2 py-1.5 text-left transition-[transform,filter] duration-150 focus:scale-[1.02] focus:brightness-[1.15] focus:outline-none"
                  >
                    <span class="flex-1 text-xs font-semibold uppercase tracking-wider text-muted-foreground">
                      {day.label}
                    </span>
                    <span class="text-sm tabular-nums text-muted-foreground/60">{day.items.length}</span>
                    {#if collapsedGroups[day.key]}
                      <ChevronDown class="size-4 shrink-0 text-muted-foreground/60" />
                    {:else}
                      <ChevronUp class="size-4 shrink-0 text-muted-foreground/60" />
                    {/if}
                  </button>
                </div>

                {#if !collapsedGroups[day.key]}
                <div
                  use:focusGroup={{
                    id: `calendar-${day.key}`,
                    policy: { type: "column" },
                    rememberFocus: true,
                  }}
                  class="space-y-2 pb-5"
                  transition:slide={{ duration: 200 }}
                >
                  {#each day.items as item (`${item.tmdb_id}-${item.kind}-${item.season_number ?? ""}-${item.episode_number ?? ""}`)}
                    <button
                      type="button"
                      use:focusable={{ groupId: `calendar-${day.key}` }}
                      onclick={() => openItem(item)}
                      onkeydown={(e) => {
                        if (e.key === "Enter") {
                          e.preventDefault();
                          openItem(item);
                        }
                      }}
                      class="flex w-full items-center gap-4 rounded-xl border border-border/50 bg-secondary/30 px-4 py-3 text-left transition-colors focus:bg-secondary focus:outline-none focus:ring-2 focus:ring-accent focus:ring-offset-1 focus:ring-offset-background"
                    >
                      <!-- Still / poster: ~h-20 w-36, larger than desktop h-16 w-28 -->
                      <div
                        class="relative h-20 w-36 shrink-0 overflow-hidden rounded-lg"
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
                              <Tv class="size-6 text-muted-foreground/40" />
                            {:else}
                              <Film class="size-6 text-muted-foreground/40" />
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
                      </div>

                      <!-- Text -->
                      <div class="min-w-0 flex-1">
                        <p class="truncate text-base font-semibold">
                          {item.title}
                        </p>
                        <p class="truncate text-sm text-muted-foreground">
                          {#if item.media_type === "tv" && item.episode_name}
                            {item.episode_name}{#if item.kind === "available" && item.waiting_count > 1}&nbsp;<span
                                class="font-medium text-accent-foreground"
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
                      </div>

                      <!-- Right badge -->
                      {#if item.kind === "available"}
                        <span
                          class="shrink-0 rounded-full bg-accent px-4 py-1.5 text-sm font-semibold text-accent-foreground"
                        >
                          {m.common_watch()}
                        </span>
                      {:else if item.kind === "movie"}
                        <span class="shrink-0 text-sm text-muted-foreground"
                          >{m.common_release()}</span
                        >
                      {/if}
                    </button>
                  {/each}
                </div>
                {/if}
              </div>
            </div>
          {/each}
        </div>

        <!-- Show N more days: own focusGroup so cross-group Down from the last
             day section lands here cleanly. -->
        {#if !showAllDays && hiddenDayCount > 0}
          <div
            use:focusGroup={{
              id: "calendar-show-more",
              policy: { type: "row" },
            }}
          >
            <button
              type="button"
              use:focusable={{ groupId: "calendar-show-more" }}
              onclick={() => (showAllDays = true)}
              onkeydown={(e) => {
                if (e.key === "Enter") {
                  e.preventDefault();
                  showAllDays = true;
                }
              }}
              class="mt-2 flex w-full items-center justify-center gap-2 rounded-xl border border-border/50 py-3 text-sm text-muted-foreground transition-colors focus:bg-secondary focus:text-foreground focus:outline-none focus:ring-2 focus:ring-accent focus:ring-offset-1 focus:ring-offset-background"
            >
              <ChevronDown class="size-5" />
              {m.common_show_more_days({ count: hiddenDayCount })}
            </button>
          </div>
        {/if}
      </div>
    {/if}
  </div>
{/if}
