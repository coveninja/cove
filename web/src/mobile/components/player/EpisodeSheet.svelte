<script lang="ts">
  import type { Media, TVSeason, TVEpisode } from "$lib/types/tmdb";
  import type { WatchProgress } from "$lib/types/library";
  import { fly, fade } from "svelte/transition";
  import { onMount } from "svelte";
  import { SvelteMap } from "svelte/reactivity";
  import { CircleCheckBig } from "lucide-svelte";
  import { api } from "$lib/api";
  import { epKey, epProgress, progressPct } from "$lib/utils";
  import { imageFade } from "../../lib/imageFade";

  let {
    media,
    activeSeason = undefined,
    activeEpisode = undefined,
    onSelect,
    onclose,
  }: {
    media: Media;
    activeSeason?: number;
    activeEpisode?: number;
    onSelect: (season: number, episode: number) => void;
    onclose: () => void;
  } = $props();

  // Custom springy slide-up entrance transition (outBack easing with overshoot).
  // c1 controls overshoot: 1.70158 is the canonical outBack (~10% of travel,
  // far too bouncy for a full-height slide); 0.55 settles with a subtle ~3%.
  function slideUp(_node: HTMLElement, { duration = 320 }: { duration?: number } = {}) {
    return {
      duration,
      css: (t: number) => {
        const c1 = 0.55;
        const c3 = c1 + 1;
        const eased = 1 + c3 * Math.pow(t - 1, 3) + c1 * Math.pow(t - 1, 2);
        return `transform: translateY(${(1 - eased) * 100}%)`;
      },
    };
  }

  // ── Drag-to-dismiss ────────────────────────────────────────────────────────
  let panelEl = $state<HTMLElement | null>(null);
  let listEl = $state<HTMLElement | null>(null);

  onMount(() => {
    const el = panelEl;
    if (!el) return;

    let touchStartY = 0;
    let lastY = 0;
    let lastT = 0;
    let velocity = 0;
    let isDragging = false;

    const onTouchStart = (e: TouchEvent) => {
      // Gate on scroll position: if the list is scrolled down, don't hijack.
      if (listEl && listEl.scrollTop > 2) return;
      touchStartY = e.touches[0].clientY;
      lastY = touchStartY;
      lastT = Date.now();
      velocity = 0;
      isDragging = false;
    };

    const onTouchMove = (e: TouchEvent) => {
      if (!touchStartY) return;
      const y = e.touches[0].clientY;
      const dy = y - touchStartY;
      if (dy < 0) {
        // Upward swipe — cancel drag, let native scroll handle it.
        touchStartY = 0;
        isDragging = false;
        return;
      }
      const now = Date.now();
      velocity = (y - lastY) / (now - lastT || 1);
      lastY = y;
      lastT = now;
      if (!isDragging && dy > 8) isDragging = true;
      if (isDragging) {
        el.style.transition = "none";
        el.style.transform = `translateY(${dy}px)`;
        e.preventDefault();
      }
    };

    const onTouchEnd = () => {
      if (!isDragging) {
        touchStartY = 0;
        return;
      }
      // Parse the current drag offset from the inline transform.
      const match = el.style.transform.match(/translateY\(([0-9.]+)px\)/);
      const offset = match ? parseFloat(match[1]) : 0;
      const vel = velocity;
      isDragging = false;
      touchStartY = 0;
      if (offset > 60 || vel > 0.5) {
        // Flick or far-enough drag — dismiss.
        el.style.transform = "";
        el.style.transition = "";
        onclose();
      } else {
        // Snap back with a short ease.
        el.style.transition = "transform 200ms ease";
        el.style.transform = "";
        setTimeout(() => {
          el.style.transition = "";
        }, 200);
      }
    };

    el.addEventListener("touchstart", onTouchStart, { passive: true });
    el.addEventListener("touchmove", onTouchMove, { passive: false });
    el.addEventListener("touchend", onTouchEnd, { passive: true });

    return () => {
      el.removeEventListener("touchstart", onTouchStart);
      el.removeEventListener("touchmove", onTouchMove);
      el.removeEventListener("touchend", onTouchEnd);
    };
  });

  // ── Data fetching ──────────────────────────────────────────────────────────
  let seasons = $state<TVSeason[]>([]);
  let selectedSeason = $state<number | null>(null);
  let episodes = $state<TVEpisode[]>([]);
  let episodesLoading = $state(false);
  const progressMap = new SvelteMap<string, WatchProgress>();

  onMount(async () => {
    // Fetch seasons and progress in parallel
    const [seasonsResult, libResult] = await Promise.allSettled([
      api.tvSeasons<TVSeason>(media.id),
      api.libraryGet(media.id, "tv"),
    ]);

    if (seasonsResult.status === "fulfilled") {
      seasons = seasonsResult.value;
      selectedSeason = activeSeason ?? seasonsResult.value[0]?.season_number ?? 1;
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

  // ── Fetch episodes when selectedSeason changes ─────────────────────────────
  $effect(() => {
    const s = selectedSeason;
    if (s === null) return;
    episodesLoading = true;
    episodes = [];
    api
      .tvEpisodes(media.id, s)
      .then((eps) => {
        episodes = eps;
      })
      .catch(() => {
        episodes = [];
      })
      .finally(() => {
        episodesLoading = false;
      });
  });

  function formatAirDate(dateStr: string): string {
    if (!dateStr) return "";
    try {
      return new Date(dateStr).toLocaleDateString(undefined, {
        year: "numeric",
        month: "short",
        day: "numeric",
      });
    } catch {
      return dateStr;
    }
  }
</script>

<!-- Backdrop -->
<!-- svelte-ignore a11y_no_static_element_interactions -->
<div
  class="fixed inset-0 z-[60] bg-black/60"
  transition:fade={{ duration: 180 }}
  onclick={onclose}
  onkeydown={() => {}}
></div>

<!-- Sheet panel -->
<div
  bind:this={panelEl}
  class="fixed inset-x-0 bottom-0 z-[61] flex flex-col rounded-t-2xl bg-neutral-900 text-white shadow-xl"
  style="padding-bottom: max(1rem, var(--safe-bottom));"
  in:slideUp={{ duration: 320 }}
  out:fly={{ y: 400, duration: 200, opacity: 1 }}
  onclick={(e) => e.stopPropagation()}
  onkeydown={() => {}}
  role="dialog"
  tabindex={-1}
  aria-label="Episodes"
>
  <!-- Drag handle pill -->
  <div class="flex shrink-0 justify-center pb-1 pt-3">
    <div class="h-1 w-10 rounded-full bg-white/25"></div>
  </div>

  <!-- Sheet title -->
  <p class="shrink-0 px-5 pb-1 pt-3 text-base font-semibold">Episodes</p>

  <!-- Season chip strip (only when more than one season) -->
  {#if seasons.length > 1}
    <div class="shrink-0 overflow-x-auto px-5 pb-3 pt-1 [scrollbar-width:none]">
      <div class="flex gap-2">
        {#each seasons as s (s.season_number)}
          <button
            type="button"
            class="shrink-0 rounded-full px-3 py-1.5 text-sm font-medium transition-colors {selectedSeason ===
            s.season_number
              ? 'bg-foreground text-background'
              : 'bg-white/10 text-white/70'}"
            onclick={() => (selectedSeason = s.season_number)}
          >
            {s.name || `Season ${s.season_number}`}
          </button>
        {/each}
      </div>
    </div>
  {/if}

  <!-- Episode list -->
  <div bind:this={listEl} class="overflow-y-auto pb-3" style="max-height: 75dvh;">
    {#if episodesLoading}
      <!-- Loading placeholders -->
      {#each { length: 6 } as _}
        <div class="flex items-center gap-3 px-4 py-2">
          <div class="aspect-video w-28 shrink-0 animate-shimmer rounded-md"></div>
          <div class="min-w-0 flex-1 space-y-2">
            <div class="h-3.5 w-3/4 animate-shimmer rounded"></div>
            <div class="h-3 w-1/2 animate-shimmer rounded"></div>
          </div>
        </div>
      {/each}
    {:else}
      {#each episodes as ep (ep.episode_number)}
        {@const isCurrent =
          selectedSeason === activeSeason && ep.episode_number === activeEpisode}
        {@const prog = epProgress(selectedSeason ?? 1, ep.episode_number, progressMap)}
        {@const pct = prog ? progressPct(prog) : 0}
        {@const completed = prog?.completed ?? false}
        <button
          type="button"
          class="flex w-full items-center gap-3 px-4 py-2 text-left transition-colors active:bg-white/10"
          aria-current={isCurrent ? "true" : undefined}
          onclick={() => {
            if (isCurrent) {
              onclose();
            } else {
              onSelect(selectedSeason ?? 1, ep.episode_number);
            }
          }}
        >
          <!-- Thumbnail -->
          <div class="relative w-28 shrink-0">
            {#if ep.still_path}
              <img
                src={ep.still_path}
                alt="Episode {ep.episode_number}"
                class="aspect-video w-full rounded-md object-cover bg-muted"
                use:imageFade
              />
            {:else}
              <div class="aspect-video w-full rounded-md bg-muted"></div>
            {/if}
            <!-- In-progress bar -->
            {#if !completed && pct > 1}
              <div
                class="absolute bottom-0 left-0 h-0.5 w-full overflow-hidden rounded-b-md bg-white/20"
              >
                <div class="h-full bg-accent" style="width: {pct}%"></div>
              </div>
            {/if}
          </div>

          <!-- Info -->
          <span class="min-w-0 flex-1">
            <span class="flex items-center gap-1.5">
              <span
                class="block truncate text-sm font-medium leading-snug {isCurrent
                  ? 'text-accent'
                  : ''}"
              >
                E{ep.episode_number} · {ep.name}
              </span>
              {#if completed}
                <CircleCheckBig class="size-4 shrink-0 text-accent" />
              {/if}
            </span>
            <span class="block text-xs leading-snug text-white/50">
              {#if isCurrent}
                <span class="font-medium text-accent/80">Now playing</span>{#if ep.air_date || ep.runtime} · {/if}
              {/if}{#if ep.air_date}{formatAirDate(ep.air_date)}{/if}{#if ep.air_date && ep.runtime} · {/if}{#if ep.runtime}{ep.runtime}m{/if}
            </span>
          </span>
        </button>
      {/each}
    {/if}
  </div>
</div>
