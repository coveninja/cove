<script lang="ts">
  import { statusLabel, STATUS_COLORS, type LibraryStatus } from "$lib/api";
  import TvMediaCard from "../components/TvMediaCard.svelte";
  import TvCalendarAgenda from "../components/TvCalendarAgenda.svelte";
  import { BookMarked, Star } from "lucide-svelte";
  import { onMount } from "svelte";
  import * as m from "$lib/paraglide/messages.js";
  import {
    hasNewEpisodes,
    compareEntries,
    sortOptions,
    toMediaKey,
    type SortKey,
  } from "$lib/myList";
  import { MyListDataController } from "$lib/myList.svelte";
  import { libraryChanged } from "$lib/stores/library";
  import { Spinner } from "$lib/components/ui/spinner/index.js";
  import { focusGroup, focusable } from "../focus/actions";

  // Grid columns: keep this constant in sync with the CSS grid-template-columns
  // below so the focusGroup grid policy matches the visual layout.
  const COLS = 6;

  // ── State ────────────────────────────────────────────────────────────────────

  const data = new MyListDataController();
  const entries = $derived(data.entries);
  const loading = $derived(data.loading);
  const mediaByKey = $derived(data.mediaByKey);
  let activeType = $state<"all" | "movie" | "tv">("all");

  // ── Sort ─────────────────────────────────────────────────────────────────────

  const SORT_OPTIONS = sortOptions();
  let sortKey = $state<SortKey>("default");

  // ── Data ─────────────────────────────────────────────────────────────────────

  onMount(() => {
    void data.loadEntries(true);
  });

  let initialized = $state(false);
  $effect(() => {
    $libraryChanged;
    if (!initialized) {
      initialized = true;
      return;
    }
    void data.loadEntries(false);
  });

  // ── Derived ──────────────────────────────────────────────────────────────────

  const SECTION_ORDER: LibraryStatus[] = [
    "watching",
    "watch_later",
    "finished",
    "dropped",
  ];

  const sections = $derived(
    SECTION_ORDER.map((status) => ({
      status,
      label: statusLabel(status),
      entries: entries
        .filter(
          (e) =>
            e.status === status &&
            (activeType === "all" || e.media_type === activeType),
        )
        .toSorted((a, b) => compareEntries(a, b, sortKey, mediaByKey)),
    })).filter((section) => section.entries.length > 0),
  );

  // ── Helpers ──────────────────────────────────────────────────────────────────

  /** Advance sortKey to the next option in the cycle (for the TV sort button). */
  function cycleSortKey(): void {
    const idx = SORT_OPTIONS.findIndex((o) => o.value === sortKey);
    sortKey = SORT_OPTIONS[(idx + 1) % SORT_OPTIONS.length].value;
  }

  const EMPTY_MESSAGES: Record<string, { heading: string; sub: string }> = {
    all: {
      heading: m.my_list_empty(),
      sub: m.my_list_empty_tracking(),
    },
    watching: {
      heading: m.my_list_empty_watching(),
      sub: m.my_list_empty_watching(),
    },
    watch_later: {
      heading: m.my_list_empty_later(),
      sub: m.my_list_empty_later(),
    },
    finished: {
      heading: m.my_list_empty_finished(),
      sub: m.my_list_empty_finished(),
    },
    dropped: {
      heading: m.my_list_empty_dropped(),
      sub: m.my_list_empty_dropped(),
    },
  };
</script>

<div class="flex h-full flex-col overflow-hidden p-4 gap-2">
  <!-- Sticky header with D-pad-navigable filter controls -->
  <div class="shrink-0 space-y-3 p-4 bg-card rounded-2xl">
    <div class="flex items-baseline gap-3">
      <h1 class="text-2xl font-bold">{m.my_list_title()}</h1>
      {#if !loading && entries.length > 0}
        <span class="text-base text-muted-foreground">
          {entries.length === 1
            ? m.my_list_title_count_one()
            : m.my_list_titles_count({ count: entries.length })}
        </span>
      {/if}
    </div>

    {#if !loading && entries.length > 0}
      <!--
        Single filter row (one D-pad vertical stop):
        type chips [All | Movies | TV]  ·  sort cycle-button (Enter = next sort).
        Status chips removed — content is already grouped into status sections.
      -->
      <div
        use:focusGroup={{ id: "mylist-filters", policy: { type: "row" } }}
        class="flex items-center gap-2"
      >
        {#each [["all", m.my_list_all()], ["movie", m.my_list_movies()], ["tv", m.my_list_shows()]] as [val, label] (val)}
          <button
            type="button"
            use:focusable={{ groupId: "mylist-filters" }}
            onclick={() => (activeType = val as typeof activeType)}
            class="rounded-xl px-5 py-2 text-sm font-medium transition-colors {activeType ===
            val
              ? 'bg-foreground text-background'
              : 'bg-secondary text-muted-foreground'}">{label}</button
          >
        {/each}

        <div class="h-6 w-px shrink-0 bg-white/20"></div>

        <!-- Sort cycle: Enter advances to the next sort option. -->
        <button
          type="button"
          use:focusable={{ groupId: "mylist-filters" }}
          onclick={cycleSortKey}
          class="flex items-center gap-1.5 rounded-xl bg-secondary px-5 py-2 text-sm font-medium text-muted-foreground transition-colors hover:text-foreground"
        >
          {m.my_list_sort()}: {SORT_OPTIONS.find((o) => o.value === sortKey)
            ?.label ?? ""}
        </button>
      </div>
    {/if}
  </div>

  <!-- Body -->
  {#if loading}
    <div class="flex flex-1 items-center justify-center">
      <Spinner class="size-12" />
    </div>
  {:else if entries.length === 0}
    <div
      class="flex flex-1 flex-col items-center justify-center gap-4 px-8 text-center"
    >
      <BookMarked class="size-16 text-muted-foreground/30" />
      <p class="text-xl font-medium">{EMPTY_MESSAGES.all.heading}</p>
      <p class="text-base text-muted-foreground">{EMPTY_MESSAGES.all.sub}</p>
    </div>
  {:else}
    <div
      class="min-h-0 px-4 flex-1 overflow-y-auto scrollbar-none [&::-webkit-scrollbar]:hidden rounded-2xl"
    >
      <div class="pb-12 pt-4">
        <TvCalendarAgenda />

        {#if sections.length === 0}
          <div
            class="flex h-[40vh] flex-col items-center justify-center gap-3 text-center"
          >
            <p class="text-xl font-medium">{m.my_list_no_filter()}</p>
            <p class="text-base text-muted-foreground">
              {m.my_list_change_filter()}
            </p>
          </div>
        {:else}
          {#each sections as section (section.status)}
            <section class="mt-8 first:mt-4">
              <div class="mb-4 flex items-baseline gap-3">
                <span
                  class="size-3 shrink-0 self-center rounded-full {STATUS_COLORS[
                    section.status
                  ].dot}"
                ></span>
                <h2 class="text-xl font-semibold">{section.label}</h2>
                <span class="text-base text-muted-foreground tabular-nums">
                  {section.entries.length}
                </span>
              </div>

              <!--
                D-pad-navigable grid: policy type "grid" with COLS columns.
                COLS must match the CSS grid-template-columns count below.
              -->
              <div
                use:focusGroup={{
                  id: `mylist-section-${section.status}`,
                  policy: { type: "grid", cols: COLS },
                  rememberFocus: true,
                }}
                class="grid grid-cols-6 gap-3"
              >
                {#each section.entries as entry (entry.id)}
                  {@const media = mediaByKey[toMediaKey(entry)]}
                  <div class="relative">
                    {#if media}
                      <TvMediaCard
                        {media}
                        groupId={`mylist-section-${section.status}`}
                      />
                    {:else}
                      <!-- Poster stub while Media object loads -->
                      <img
                        src={entry.poster_path}
                        alt={entry.title}
                        loading="lazy"
                        decoding="async"
                        class="aspect-2/3 w-full rounded-lg object-cover opacity-60"
                      />
                    {/if}

                    {#if entry.rating !== null && entry.rating !== undefined}
                      <div
                        class="pointer-events-none absolute top-1.5 left-1.5 z-10 flex items-center gap-1 rounded border border-yellow-400/40 bg-black/65 px-1.5 py-0.5 text-xs font-semibold text-yellow-400"
                      >
                        <Star class="size-3 fill-current" />
                        {entry.rating}
                      </div>
                    {/if}

                    {#if hasNewEpisodes(entry)}
                      <div
                        class="pointer-events-none absolute top-1.5 right-1.5 z-10 size-2.5 rounded-full bg-accent"
                      ></div>
                    {/if}
                  </div>
                {/each}
              </div>
            </section>
          {/each}
        {/if}
      </div>
    </div>
  {/if}
</div>
