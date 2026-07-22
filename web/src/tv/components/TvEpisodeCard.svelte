<script lang="ts">
  import { epProgress, progressPct, relativeDate } from "$lib/utils";
  import { Check, Play } from "lucide-svelte";
  import type { Media, TVEpisode } from "$lib/types/tmdb";
  import { SvelteDate, SvelteMap } from "svelte/reactivity";
  import type { WatchProgress } from "$lib/types/library";
  import { settings } from "$lib/stores/settings";
  import { focusable } from "../focus/actions";

  // EpisodeCard is not reused as-is because:
  //   1. Its primary activation is inside a ContextMenu.Trigger wrapper (not a
  //      top-level native focusable), which breaks D-pad group member discovery.
  //   2. It relies on `group-hover:*` affordances (play overlay, row highlight)
  //      that are invisible at 10 feet without a pointer.
  //   3. It imports ScrambledText and ContextMenu — the latter fires on right-
  //      click / long-press, neither of which is meaningful in a TV shell.
  // This minimal fork keeps the same data props and `bind:selectedEpisode`
  // contract but renders a single native <button> with explicit focus styling.

  let {
    media: _media,
    ep,
    selectedSeason,
    progressMap,
    selectedEpisode = $bindable<TVEpisode | null>(),
    activeSeason = undefined,
    activeEpisode = undefined,
    groupId,
  }: {
    media: Media;
    ep: TVEpisode;
    selectedSeason: number | null;
    progressMap: SvelteMap<string, WatchProgress>;
    selectedEpisode: TVEpisode | null;
    activeSeason?: number;
    activeEpisode?: number;
    groupId: string;
  } = $props();

  let prog = $derived(
    selectedSeason != null
      ? epProgress(selectedSeason, ep.episode_number, progressMap)
      : undefined,
  );

  let pct = $derived(prog ? progressPct(prog) : 0);
  let completed = $derived(prog?.completed ?? false);
  let inProgress = $derived(!completed && pct > 1);
  let unreleased = $derived(
    !!ep.air_date && new SvelteDate(ep.air_date) > new SvelteDate(),
  );

  let hideSpoilers = $derived($settings?.hideSpoilers && !completed);
  let isCurrentlyPlaying = $derived(
    activeSeason != null &&
      activeEpisode != null &&
      selectedSeason === activeSeason &&
      ep.episode_number === activeEpisode,
  );
</script>

<!--
  Single native <button> — D-pad group member via use:focusable.
  All affordances (progress ring, playing badge, watched check) are always
  visible, not hover-gated, so they read clearly from 10 feet.
-->
<button
  type="button"
  use:focusable={{ groupId }}
  disabled={unreleased}
  onclick={() => {
    if (!unreleased) selectedEpisode = ep;
  }}
  class="relative flex w-full items-center gap-4 rounded-xl p-3 text-left transition-colors
    focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-accent focus-visible:ring-offset-1 focus-visible:ring-offset-background
    {unreleased ? 'cursor-default opacity-40' : 'hover:bg-secondary/60'}
    {completed ? 'opacity-70' : ''}
    {isCurrentlyPlaying ? 'ring-2 ring-inset ring-accent' : ''}"
>
  <!-- Thumbnail -->
  <span class="relative w-36 shrink-0 overflow-hidden rounded-lg bg-muted">
    {#if ep.still_path}
      <img
        src={ep.still_path}
        alt={ep.name}
        loading="lazy"
        decoding="async"
        class="aspect-video w-full object-cover {hideSpoilers ? 'scale-110 blur-md' : ''}"
      />
    {:else}
      <div class="flex aspect-video w-full items-center justify-center bg-secondary">
        <Play class="size-5 text-muted-foreground/50" />
      </div>
    {/if}

    <!-- Completed overlay -->
    {#if completed}
      <span class="absolute inset-0 flex items-center justify-center bg-black/50">
        <span class="flex size-8 items-center justify-center rounded-full bg-green-500/90">
          <Check class="size-5 text-white" />
        </span>
      </span>
    {/if}

    <!-- Now Playing badge — always visible (not hover-gated) -->
    {#if isCurrentlyPlaying}
      <span
        class="absolute bottom-1.5 left-1.5 z-10 flex items-center gap-1 rounded-full bg-accent px-2 py-0.5 text-[11px] font-semibold text-accent-foreground shadow"
      >
        <Play class="size-3 fill-current" />
        Now Playing
      </span>
    {/if}
  </span>

  <!-- Info -->
  <span class="min-w-0 flex-1">
    <span class="flex flex-col gap-0.5">
      <span class="text-base font-semibold leading-snug {completed ? 'text-muted-foreground' : ''}">
        {ep.name}
      </span>
      <span class="flex flex-wrap items-center gap-1.5 text-sm text-muted-foreground">
        <span class="font-medium">E{ep.episode_number}</span>
        {#if ep.air_date}
          <span>·</span>
          <span>{unreleased ? relativeDate(ep.air_date) : ep.air_date}</span>
        {/if}
        {#if ep.runtime > 0}
          <span>·</span>
          <span>{ep.runtime}m</span>
        {/if}
        {#if inProgress && prog}
          <span>·</span>
          <span class="text-accent">{Math.round(pct)}% watched</span>
        {/if}
      </span>
      {#if ep.overview}
        {#if hideSpoilers}
          <span class="mt-1 text-sm italic text-muted-foreground/50">Spoiler hidden</span>
        {:else}
          <span class="mt-1 line-clamp-2 text-sm leading-relaxed text-muted-foreground">
            {ep.overview}
          </span>
        {/if}
      {/if}
    </span>
  </span>

  <!-- In-progress bar pinned to bottom of row -->
  {#if inProgress}
    <span class="absolute right-0 bottom-0 left-0 h-1 overflow-hidden rounded-b-xl bg-secondary">
      <span class="block h-full bg-accent transition-all" style="width: {pct}%"></span>
    </span>
  {/if}
</button>
