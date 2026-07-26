<script lang="ts">
  import { fly } from "svelte/transition";
  import { onMount, tick } from "svelte";
  import { SvelteMap } from "svelte/reactivity";
  import { api } from "$lib/api";
  import { epKey } from "$lib/utils";
  import type { Media, TVSeason, TVEpisode } from "$lib/types/tmdb";
  import type { WatchProgress } from "$lib/types/library";
  import { focusable, focusGroup } from "../../focus/actions";
  import TvEpisodeCard from "../TvEpisodeCard.svelte";
  import * as m from "$lib/paraglide/messages.js";

  // ── Props ─────────────────────────────────────────────────────────────────────

  let {
    media,
    activeSeason = undefined,
    activeEpisode = undefined,
    onSelect,
    onClose,
  }: {
    media: Media;
    activeSeason?: number;
    activeEpisode?: number;
    onSelect: (season: number, episode: number) => void;
    onClose: () => void;
  } = $props();

  // ── Panel + focus restore ─────────────────────────────────────────────────────

  let panelEl = $state<HTMLDivElement | null>(null);
  let listEl = $state<HTMLDivElement | null>(null);
  // Track the element that opened this panel so we can restore focus on close.
  let openerEl: HTMLElement | null = null;

  // ── Data state ────────────────────────────────────────────────────────────────

  let seasons = $state<TVSeason[]>([]);
  let episodes = $state<TVEpisode[]>([]);
  let selectedSeason = $state<number | null>(null);
  let selectedEpisode = $state<TVEpisode | null>(null);
  let loadingEpisodes = $state(false);
  // Plain (non-reactive) guard — reset when selectedSeason changes so each
  // new season gets its own initial-focus pass.
  let initialFocusDone = false;

  const progressMap = new SvelteMap<string, WatchProgress>();

  onMount(() => {
    openerEl =
      document.activeElement instanceof HTMLElement ? document.activeElement : null;

    // Fetch seasons + library progress in parallel (mirrors EpisodeSheet).
    Promise.allSettled([
      api.tvSeasons<TVSeason>(media.id),
      api.libraryGet(media.id, "tv"),
    ]).then(([seasonsResult, libResult]) => {
      if (seasonsResult.status === "fulfilled") {
        seasons = seasonsResult.value ?? [];
        // Default: activeSeason if present in the list, else first season, else 1.
        if (
          activeSeason != null &&
          seasons.some((s) => s.season_number === activeSeason)
        ) {
          selectedSeason = activeSeason;
        } else {
          selectedSeason = seasons[0]?.season_number ?? 1;
        }
      } else {
        selectedSeason = activeSeason ?? 1;
      }

      if (libResult.status === "fulfilled") {
        progressMap.clear();
        for (const p of libResult.value?.progress ?? []) {
          if (p.season != null && p.episode != null) {
            progressMap.set(epKey(p.season, p.episode), p);
          }
        }
      }
    });

    return () => {
      // Restore focus to the button that opened the panel.
      if (openerEl?.isConnected) {
        openerEl.focus({ preventScroll: true });
      }
    };
  });

  // ── Fetch episodes when selectedSeason changes ────────────────────────────────
  //
  // ONE focus group (free + trapFocus) covers season chips and episode cards.
  // Inner row/column groups would break trapFocus: members registered to an
  // inner group resolve to that group, and its unhandled edges fall through to
  // geometric nav, escaping past the player controls behind the panel.

  $effect(() => {
    const s = selectedSeason;
    if (s === null) return;
    loadingEpisodes = true;
    episodes = [];
    initialFocusDone = false;
    api
      .tvEpisodes(media.id, s)
      .then((eps) => {
        episodes = eps ?? [];
      })
      .catch(() => {
        episodes = [];
      })
      .finally(() => {
        loadingEpisodes = false;
      });
  });

  // ── Initial focus: after the first episode list arrives per season ────────────

  $effect(() => {
    if (loadingEpisodes || episodes.length === 0 || initialFocusDone) return;
    initialFocusDone = true;
    tick().then(() => {
      if (!listEl) return;
      const btns = listEl.querySelectorAll<HTMLElement>("[data-tv-focusable], button");
      // Prefer the currently-playing episode card.
      if (
        activeSeason != null &&
        activeEpisode != null &&
        selectedSeason === activeSeason
      ) {
        const idx = episodes.findIndex((ep) => ep.episode_number === activeEpisode);
        const target = idx >= 0 ? btns[idx] ?? null : null;
        if (target) {
          target.focus({ preventScroll: false });
          return;
        }
      }
      // Fallback: first episode button, then first chip in the panel.
      const first =
        btns[0] ??
        panelEl?.querySelector<HTMLElement>("[data-tv-focusable], button") ??
        null;
      first?.focus({ preventScroll: false });
    });
  });

  // ── Episode selection ─────────────────────────────────────────────────────────

  $effect(() => {
    const ep = selectedEpisode;
    if (!ep) return;
    // Reset so the same episode can be re-clicked after closing and reopening.
    selectedEpisode = null;
    if (selectedSeason === activeSeason && ep.episode_number === activeEpisode) {
      // Already playing — just close the panel.
      onClose();
    } else {
      onSelect(selectedSeason ?? activeSeason ?? 1, ep.episode_number);
    }
  });

  // ── Keyboard ──────────────────────────────────────────────────────────────────

  function handleKeydown(e: KeyboardEvent): void {
    if (e.key === "Escape") {
      e.stopPropagation();
      onClose();
    }
  }
</script>

<!-- Backdrop -->
<div
  class="fixed inset-0 z-[60] bg-black/40"
  role="presentation"
  onclick={onClose}
  onkeydown={() => {}}
  transition:fly={{ x: 560, duration: 200, opacity: 1 }}
></div>

<!-- Side panel -->
<div
  bind:this={panelEl}
  class="fixed right-0 top-0 z-[61] flex h-full w-[560px] flex-col bg-neutral-900 text-white shadow-2xl"
  role="dialog"
  tabindex={-1}
  aria-label={m.player_episodes()}
  onkeydown={handleKeydown}
  use:focusGroup={{ id: "tv-episode-panel", policy: { type: "free" }, trapFocus: true }}
  transition:fly={{ x: 560, duration: 220, opacity: 1 }}
>
  <!-- Header ──────────────────────────────────────────────────────────────────── -->
  <div class="flex shrink-0 items-center border-b border-white/10 px-6 py-5">
    <p class="text-lg font-semibold">{m.player_episodes()}</p>
  </div>

  <!-- Season chip strip (only when more than one season) ──────────────────────── -->
  {#if seasons.length > 1}
    <div class="shrink-0 overflow-x-auto px-5 pb-3 pt-4 [scrollbar-width:none]">
      <div class="flex gap-2">
        {#each seasons as s (s.season_number)}
          <button
            type="button"
            use:focusable={{ groupId: "tv-episode-panel" }}
            onclick={() => (selectedSeason = s.season_number)}
            class="shrink-0 rounded-full px-4 py-2 text-sm font-medium transition-colors
              focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-accent
              focus-visible:ring-offset-1 focus-visible:ring-offset-background
              {selectedSeason === s.season_number
                ? 'bg-white text-black'
                : 'bg-white/10 text-white/70 hover:text-white'}"
          >
            {s.name ||
              m.common_season_number({ season: s.season_number })}
          </button>
        {/each}
      </div>
    </div>
  {/if}

  <!-- Episode list ─────────────────────────────────────────────────────────────── -->
  <div bind:this={listEl} class="flex-1 overflow-y-auto py-2">
    {#if loadingEpisodes}
      <!-- Skeleton placeholders (animate-shimmer is mobile-shell-only CSS) -->
      {#each { length: 5 } as _}
        <div class="flex items-center gap-4 px-4 py-3">
          <div class="aspect-video w-36 shrink-0 animate-pulse rounded-lg bg-secondary"></div>
          <div class="min-w-0 flex-1 space-y-2">
            <div class="h-4 w-3/4 animate-pulse rounded bg-secondary"></div>
            <div class="h-3 w-1/2 animate-pulse rounded bg-secondary"></div>
          </div>
        </div>
      {/each}
    {:else}
      <div class="flex flex-col divide-y divide-white/5 px-3 py-1">
        {#each episodes as ep (ep.episode_number)}
          <TvEpisodeCard
            {media}
            {ep}
            {selectedSeason}
            {progressMap}
            bind:selectedEpisode
            {activeSeason}
            {activeEpisode}
            groupId="tv-episode-panel"
          />
        {/each}
      </div>
    {/if}
  </div>
</div>
