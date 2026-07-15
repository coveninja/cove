<script lang="ts">
  // TV-friendly fork of web/src/components/StreamsList.svelte.
  //
  // DATA LAYER: verbatim copy of StreamsList's script block (stream fetching,
  // ranking/dedup, auto-pick, quality-filter, sort, season/episode fetching,
  // progress, WatchOptions, fetch-sequencing guards). Only the UI layer
  // differs — Select dropdowns → cycle buttons, ScrollArea → native overflow,
  // shadcn Button/Skeleton → plain elements, EpisodeCard → TvEpisodeCard.
  //
  // PROP CONTRACT: identical to StreamsList so TvDetailOverlay's call site
  // needs only an import swap (no prop changes).

  import { epKey, epProgress, getMaxQuality, inferQuality, progressPct } from "$lib/utils";
  import type { Stream, WatchOption } from "$lib/types/addons";
  import { Check, ChevronLeft, Play } from "lucide-svelte";
  import { Spinner } from "$lib/components/ui/spinner";
  import { SvelteMap, SvelteSet } from "svelte/reactivity";
  import { api, formatPosition } from "$lib/api";
  import type { WatchProgress } from "$lib/types/library";
  import { settings } from "$lib/stores/settings";
  import {
    formatStreamSummary,
    getSeeders,
    getSizeBytes,
    isTorrentStream,
    rankStreams,
    rankStreamsWithProbe,
    type StreamSelectionMode,
  } from "$lib/streamSelection";
  import type { TVEpisode } from "$lib/types/tmdb";
  import { focusGroup, focusable } from "../focus/actions";
  import TvEpisodeCard from "./TvEpisodeCard.svelte";

  // ── Identical prop contract to StreamsList ────────────────────────────────

  let loadingStreams = $state(false);
  let sortMode = $state<"seeders" | "size">("seeders");
  let qualityFilter = $state("all");

  let {
    media,
    onPlayStream,
    maxQuality = $bindable<string | null>(),
    streamActive = false,
    activeSeason = undefined,
    activeEpisode = undefined,
    autoJumpToActive = true,
  } = $props();

  // ── TV types ──────────────────────────────────────────────────────────────

  type TVSeason = {
    season_number: number;
    episode_count: number;
    name: string;
    poster_path: string;
  };

  // ── State ─────────────────────────────────────────────────────────────────

  const isTV = $derived(media.media_type === "tv");

  let seasons = $state<TVSeason[]>([]);
  let episodes = $state<TVEpisode[]>([]);
  let selectedSeason = $state<number | null>(null);
  let selectedEpisode = $state<TVEpisode | null>(null);
  let loadingSeasons = $state(false);
  let loadingEpisodes = $state(false);

  let streams = $state<Stream[]>([]);
  let watchOptions = $state<WatchOption[]>([]);

  let pollInterval: ReturnType<typeof setInterval> | null = null;
  let pollAttempts = 0;
  const MAX_POLL_ATTEMPTS = 10;

  // ── Fetch sequencing (B3) ─────────────────────────────────────────────────

  let fetchSeq = 0;
  let abortCtrl: AbortController | null = null;
  let autoPickTimer: ReturnType<typeof setTimeout> | null = null;

  // ── Auto stream selection ─────────────────────────────────────────────────

  let autoPicking = $state(false);
  let autoPickCancelled = $state(false);
  let showAlternatives = $state(false);

  const alreadyPlayingThisSelection = $derived(
    streamActive &&
      (!isTV ||
        (selectedSeason === activeSeason &&
          selectedEpisode?.episode_number === activeEpisode)),
  );

  $effect(() => {
    if (alreadyPlayingThisSelection && autoPicking) {
      autoPicking = false;
    }
  });

  // ── Watch progress ────────────────────────────────────────────────────────

  // TV: keyed by "season:episode"
  let progressMap = new SvelteMap<string, WatchProgress>();
  // Movie: single record
  let movieProgress = $state<WatchProgress | null>(null);

  $effect(() => {
    if (!isTV) return;
    api
      .libraryGet(media.id, "tv")
      .then((result) => {
        progressMap.clear();
        for (const p of result?.progress ?? []) {
          if (p.season != null && p.episode != null) {
            progressMap.set(epKey(p.season, p.episode), p);
          }
        }
      })
      .catch(console.error);
  });

  $effect(() => {
    if (isTV) return;
    api
      .progressGet(media.id, "movie")
      .then((p) => {
        movieProgress = p;
      })
      .catch(console.error);
  });

  $effect(() => {
    api
      .getWatchOptions(media.id, media.media_type)
      .then((opts) => (watchOptions = opts))
      .catch(() => (watchOptions = []));
  });

  // ── Data fetching ─────────────────────────────────────────────────────────

  $effect(() => {
    if (!isTV) return;
    loadingSeasons = true;
    api
      .tvSeasons<TVSeason>(media.id)
      .then((data) => {
        seasons = data ?? [];
        if (seasons.length > 0 && selectedSeason === null) {
          selectedSeason =
            activeSeason != null && seasons.some((s) => s.season_number === activeSeason)
              ? activeSeason
              : seasons[0].season_number;
        }
      })
      .finally(() => (loadingSeasons = false));
  });

  $effect(() => {
    if (!isTV || selectedSeason === null) return;
    loadingEpisodes = true;
    episodes = [];
    selectedEpisode = null;
    streams = [];
    api
      .tvEpisodes(media.id, selectedSeason!)
      .then((data) => {
        episodes = data ?? [];
        if (autoJumpToActive && selectedSeason === activeSeason && activeEpisode != null) {
          const match = episodes.find((e) => e.episode_number === activeEpisode);
          if (match) selectedEpisode = match;
        }
      })
      .finally(() => (loadingEpisodes = false));
  });

  $effect(() => {
    if (isTV && (!selectedEpisode || selectedSeason === null)) return () => {};

    clearPoll();
    if (autoPickTimer != null) {
      clearTimeout(autoPickTimer);
      autoPickTimer = null;
    }
    abortCtrl?.abort();
    const seq = ++fetchSeq;
    const ctrl = new AbortController();
    abortCtrl = ctrl;

    loadingStreams = true;
    streams = [];
    pollAttempts = 0;
    autoPickCancelled = false;
    autoPicking = false;
    showAlternatives = false;
    fetchStreams(seq, ctrl.signal).then(() => {
      if (seq !== fetchSeq || ctrl.signal.aborted) return;
      loadingStreams = false;
      if (streams.length === 0)
        pollInterval = setInterval(() => pollFetchStreams(seq, ctrl.signal), 2000);
    });

    return () => {
      clearPoll();
      ctrl.abort();
      if (autoPickTimer != null) {
        clearTimeout(autoPickTimer);
        autoPickTimer = null;
      }
    };
  });

  // ── Stream helpers ────────────────────────────────────────────────────────

  const availableQualities = $derived.by(() => {
    const qs = [
      ...new Set(streams.map((s) => inferQuality(s)).filter(Boolean)),
    ];
    qs.sort(
      (a, b) =>
        ["4k dv", "4k hdr", "4k", "1080p", "720p", "480p", "ts", "cam"].indexOf(a!) -
        ["4k dv", "4k hdr", "4k", "1080p", "720p", "480p", "ts", "cam"].indexOf(b!),
    );
    return ["all", ...qs];
  });

  interface ParsedStream {
    stream: Stream;
    key: string;
    seeders: number;
    sizeBytes: number;
    quality: string | null;
  }

  const parsedStreams = $derived.by(() => {
    const seen = new SvelteSet<string>();
    const result: ParsedStream[] = [];
    for (const s of streams) {
      const key = s.url || s.infoHash || s.title;
      if (!key || seen.has(key)) continue;
      seen.add(key);
      result.push({
        stream: s,
        key,
        seeders: getSeeders(s),
        sizeBytes: getSizeBytes(s),
        quality: inferQuality(s),
      });
    }
    return result;
  });

  const filteredStreams = $derived.by(() => {
    const filtered = parsedStreams.filter(
      (s) => qualityFilter === "all" || s.quality === qualityFilter,
    );
    const preferred = $settings?.defaultProvider;
    return filtered.toSorted((a, b) => {
      if (preferred) {
        const aPref = a.stream.addonName === preferred ? 1 : 0;
        const bPref = b.stream.addonName === preferred ? 1 : 0;
        if (aPref !== bPref) return bPref - aPref;
      }
      return sortMode === "seeders"
        ? b.seeders - a.seeders
        : b.sizeBytes - a.sizeBytes;
    });
  });

  function clearPoll(): void {
    if (pollInterval) {
      clearInterval(pollInterval);
      pollInterval = null;
    }
  }

  function pollFetchStreams(seq: number, signal: AbortSignal): void {
    if (seq !== fetchSeq) return;
    pollAttempts++;
    if (pollAttempts > MAX_POLL_ATTEMPTS) {
      clearPoll();
      return;
    }
    fetchStreams(seq, signal);
  }

  async function fetchStreams(seq: number, signal: AbortSignal): Promise<void> {
    let res: Stream[];
    try {
      res = await api.getStreams(
        media.id,
        isTV
          ? {
              type: "tv",
              season: selectedSeason!,
              episode: selectedEpisode!.episode_number,
            }
          : {},
        signal,
      );
    } catch (e) {
      if ((e as { name?: string } | null)?.name === "AbortError") return;
      throw e;
    }

    if (seq !== fetchSeq) return;

    streams = res;
    maxQuality = getMaxQuality(streams);
    if (streams.length > 0) clearPoll();

    if (
      $settings?.autoSelectStream &&
      !autoPickCancelled &&
      !autoPicking &&
      !alreadyPlayingThisSelection &&
      streams.length > 0
    ) {
      const selectionMode = ($settings.streamSelectionMode as StreamSelectionMode) ?? "balanced";
      const rankOpts = {
        measuredBandwidthMbps: $settings.measuredBandwidthMbps,
        preferredProvider: $settings.defaultProvider,
        sourcePreference: $settings.sourcePreference,
      };
      // Synchronous initial ranking — drives the log line and the fallback
      // used when the probe doesn't land before the 500ms window closes.
      const initialRanking = rankStreams(streams, selectionMode, rankOpts);
      const best = initialRanking[0] ?? null;
      if (best) {
        const mode = $settings.streamSelectionMode ?? "balanced";
        console.log(
          `[stream-select] auto (${mode}): "${best.name}" — ${formatStreamSummary(best)}`,
          best,
        );
        autoPicking = true;
        // Background probe: re-rank with dead links demoted and probed
        // Content-Lengths filling unknown sizes. Fills probedRanking before
        // the 500ms timer fires if the backend responds in time.
        let probedRanking: Stream[] | null = null;
        rankStreamsWithProbe(streams, selectionMode, { ...rankOpts, probeEnabled: $settings.probeStreams ?? true }, signal)
          .then((ranked) => {
            if (seq === fetchSeq && !autoPickCancelled) probedRanking = ranked;
          })
          .catch(() => {});
        autoPickTimer = setTimeout(() => {
          autoPickTimer = null;
          if (seq === fetchSeq && !autoPickCancelled) {
            const ranking = probedRanking ?? initialRanking;
            onPlayStream(
              ranking[0],
              selectedSeason ?? undefined,
              selectedEpisode?.episode_number,
              selectedEpisode?.name,
              ranking.slice(0, 5),
            );
          }
        }, 500);
      }
    }
  }

  // ── TV-specific: cycle helpers for quality filter and sort mode ───────────
  // Replaces the two Select.Root dropdowns from the desktop version with simple
  // cycle buttons (Enter advances through the options — same pattern as
  // TvMyListPage's sort button).

  function cycleQuality(): void {
    const idx = availableQualities.indexOf(qualityFilter);
    qualityFilter = availableQualities[(idx + 1) % availableQualities.length];
  }

  function cycleSort(): void {
    sortMode = sortMode === "seeders" ? "size" : "seeders";
  }
</script>

<!--
  Focus-group layout (from outermost to innermost):
    tv-detail (free + trapFocus) — owned by TvDetailOverlay, wraps everything.

  Inside TvStreamsList:
    streams-seasons  — row, rememberFocus — season chip horizontal strip
    streams-episodes — column             — episode list (TvEpisodeCard buttons)
    streams-filters  — row               — quality + sort cycle buttons
    streams-results  — column            — stream row native buttons

  The back button and episode-summary card in the stream-list view are native
  <button>/<div> elements outside any named group; the outer tv-detail free
  policy reaches them geometrically.
-->
<div
  class="flex h-full w-full flex-col overflow-hidden rounded-2xl border border-border bg-background/60 backdrop-blur-xl"
>
  <!-- ── TV: episode browser (season chips + episode list) ─────────────────── -->
  {#if isTV && !selectedEpisode}
    <!-- Season chip strip -->
    <div class="flex-none border-b border-border">
      {#if loadingSeasons}
        <div class="p-4">
          <span class="animate-pulse text-base text-muted-foreground">Loading seasons…</span>
        </div>
      {:else}
        <!--
          overflow-x-hidden: blocks pointer/touch scroll while letting the
          focus engine's scrollIntoView() drive the strip on D-pad moves,
          matching the TvMediaRow pattern.
        -->
        <div
          use:focusGroup={{ id: "streams-seasons", policy: { type: "row" }, rememberFocus: true }}
          class="flex gap-2 overflow-x-hidden p-3"
        >
          {#each seasons as s (s.season_number)}
            <button
              type="button"
              use:focusable={{ groupId: "streams-seasons" }}
              onclick={() => (selectedSeason = s.season_number)}
              class="shrink-0 rounded-xl px-4 py-2.5 text-sm font-medium transition-colors
                focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-accent focus-visible:ring-offset-1 focus-visible:ring-offset-background
                {selectedSeason === s.season_number
                  ? 'bg-accent text-accent-foreground'
                  : 'bg-secondary text-muted-foreground hover:text-foreground'}"
            >
              {s.name}
            </button>
          {/each}
        </div>
      {/if}
    </div>

    <!-- Episode list — column group; each TvEpisodeCard button registers itself -->
    <div
      use:focusGroup={{ id: "streams-episodes", policy: { type: "column" } }}
      class="min-h-0 flex-1 overflow-y-auto overflow-x-hidden scrollbar-none [&::-webkit-scrollbar]:hidden"
    >
      {#if loadingEpisodes}
        <div class="flex items-center justify-center py-16">
          <span class="animate-pulse text-base text-muted-foreground">Loading episodes…</span>
        </div>
      {:else}
        <div class="flex flex-col divide-y divide-border px-2 py-2">
          {#each episodes as ep (ep.episode_number)}
            <TvEpisodeCard
              {media}
              {ep}
              {selectedSeason}
              bind:selectedEpisode
              {progressMap}
              {activeSeason}
              {activeEpisode}
              groupId="streams-episodes"
            />
          {/each}
        </div>
      {/if}
    </div>

  <!-- ── Stream list (movies always; TV after episode selected) ────────────── -->
  {:else}
    <!-- Header: back button for TV shows, title for movies -->
    <div class="flex-none space-y-3 border-b border-border p-5">
      {#if isTV && selectedEpisode}
        <!-- Native button — reached geometrically from streams-filters via Up -->
        <button
          type="button"
          onclick={() => {
            selectedEpisode = null;
            streams = [];
          }}
          class="flex items-center gap-2 rounded-xl border border-border bg-secondary/60 px-4 py-2.5 text-base font-medium transition-colors hover:bg-secondary focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-accent"
        >
          <ChevronLeft class="size-5" />
          Back to episodes
        </button>

        <!-- Selected episode summary (display only — not focusable) -->
        <div class="flex items-start gap-4 rounded-xl border border-border bg-secondary/40 p-3">
          {#if selectedEpisode.still_path}
            <img
              src={selectedEpisode.still_path}
              alt={selectedEpisode.name}
              class="aspect-video w-28 shrink-0 rounded-lg object-cover"
            />
          {:else}
            <div class="aspect-video w-28 shrink-0 animate-pulse rounded-lg bg-secondary"></div>
          {/if}
          <div class="min-w-0 flex-1">
            <p class="text-sm text-muted-foreground">
              S{selectedSeason} · E{selectedEpisode.episode_number}
            </p>
            <p class="text-base font-semibold leading-snug">{selectedEpisode.name}</p>
            {#if selectedSeason != null}
              {@const prog = epProgress(selectedSeason, selectedEpisode.episode_number, progressMap)}
              {#if prog}
                {@const pct = progressPct(prog)}
                {#if prog.completed}
                  <p class="mt-1.5 flex items-center gap-1 text-sm text-green-500">
                    <Check class="size-4" /> Watched
                  </p>
                {:else if pct > 1}
                  <div class="mt-2 space-y-1">
                    <div class="h-1.5 w-full overflow-hidden rounded-full bg-secondary">
                      <div
                        class="h-full rounded-full bg-accent transition-all"
                        style="width: {pct}%"
                      ></div>
                    </div>
                    <p class="text-xs text-muted-foreground">
                      {formatPosition(prog.position_seconds)} / {formatPosition(
                        prog.duration_seconds,
                      )}
                    </p>
                  </div>
                {/if}
              {/if}
            {/if}
          </div>
        </div>

      {:else}
        <!-- Movie: "Available Streams" header with progress -->
        <h3 class="text-xl font-semibold">Available Streams</h3>
        {#if movieProgress}
          {#if movieProgress.completed}
            <p class="flex items-center gap-1.5 text-sm text-green-500">
              <Check class="size-4" /> Watched
            </p>
          {:else if progressPct(movieProgress) > 1}
            <div class="space-y-1">
              <div class="h-1.5 w-full overflow-hidden rounded-full bg-secondary">
                <div
                  class="h-full rounded-full bg-accent transition-all"
                  style="width: {progressPct(movieProgress)}%"
                ></div>
              </div>
              <p class="text-sm text-muted-foreground">
                {formatPosition(movieProgress.position_seconds)} / {formatPosition(
                  movieProgress.duration_seconds,
                )}
              </p>
            </div>
          {/if}
        {/if}
      {/if}

      <!-- Quality + sort cycle buttons (hidden when already playing + not showing alternatives) -->
      {#if !alreadyPlayingThisSelection || showAlternatives}
        <!--
          Two cycle buttons side by side — same Enter-to-advance pattern as
          TvMyListPage's sort button. Row policy handles Left/Right between them;
          Up falls through to geometric nav (finds the back button above) and
          Down falls through to streams-results.
        -->
        <div
          use:focusGroup={{ id: "streams-filters", policy: { type: "row" } }}
          class="flex gap-3"
        >
          <button
            type="button"
            use:focusable={{ groupId: "streams-filters" }}
            onclick={cycleQuality}
            class="flex-1 rounded-xl bg-secondary px-4 py-3 text-base font-medium text-muted-foreground transition-colors hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-accent focus-visible:ring-offset-1 focus-visible:ring-offset-background"
          >
            Quality: {qualityFilter.toUpperCase()}
          </button>
          <button
            type="button"
            use:focusable={{ groupId: "streams-filters" }}
            onclick={cycleSort}
            class="flex-1 rounded-xl bg-secondary px-4 py-3 text-base font-medium text-muted-foreground transition-colors hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-accent focus-visible:ring-offset-1 focus-visible:ring-offset-background"
          >
            Sort: {sortMode === "seeders" ? "Seeders" : "Size"}
          </button>
        </div>
      {/if}
    </div>

    <!-- Stream rows -->
    <div
      class="min-h-0 flex-1 overflow-y-auto overflow-x-hidden scrollbar-none [&::-webkit-scrollbar]:hidden"
    >
      <div class="p-4">
        <!-- Where to Watch (JustWatch) — display-only on TV; can't launch external apps -->
        {#if watchOptions.length > 0}
          <div class="mb-5">
            <p class="mb-2 text-sm font-medium uppercase tracking-wide text-muted-foreground">
              Where to Watch
            </p>
            <div class="flex flex-wrap gap-2">
              {#each watchOptions as opt (opt.providerId + opt.type)}
                <span
                  class="flex items-center gap-1.5 rounded-lg border border-border bg-secondary/40 px-3 py-1.5 text-sm"
                  title="{opt.providerName} ({opt.type})"
                >
                  {#if opt.logoPath}
                    <img
                      src={api.imgUrl("w45", opt.logoPath)}
                      alt={opt.providerName}
                      class="size-5 rounded-sm object-contain"
                    />
                  {/if}
                  <span class="font-medium">{opt.providerName}</span>
                  {#if opt.type !== "flatrate"}
                    <span class="capitalize text-muted-foreground">· {opt.type}</span>
                  {/if}
                </span>
              {/each}
            </div>
          </div>
        {/if}

        <!-- Already playing banner -->
        {#if alreadyPlayingThisSelection}
          <div
            class="flex items-center justify-between gap-2 rounded-xl border border-accent/30 bg-accent/10 px-4 py-3 text-base text-accent"
            class:mb-4={showAlternatives}
          >
            <span class="flex items-center gap-2">
              <Play class="size-5 fill-current" />
              Playing this stream
            </span>
            <!-- Native button — reached geometrically within tv-detail free group -->
            <button
              type="button"
              onclick={() => (showAlternatives = !showAlternatives)}
              class="rounded-lg bg-accent/20 px-3 py-1.5 text-sm font-medium transition-colors hover:bg-accent/30 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-accent"
            >
              {showAlternatives ? "Hide alternatives" : "See alternatives"}
            </button>
          </div>
        {/if}

        {#if !alreadyPlayingThisSelection || showAlternatives}
          {#if autoPicking && !autoPickCancelled && !alreadyPlayingThisSelection}
            <!-- Auto-picking state -->
            <div class="flex flex-col items-center justify-center gap-4 py-16">
              <Spinner class="size-10" />
              <span class="text-base text-muted-foreground">Auto-selecting the best stream…</span>
              <!-- Focusable cancel button — reached geometrically -->
              <button
                type="button"
                onclick={() => {
                  autoPickCancelled = true;
                  autoPicking = false;
                }}
                class="rounded-xl border border-border bg-secondary px-5 py-2.5 text-base font-medium transition-colors hover:bg-secondary/80 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-accent"
              >
                Choose manually instead
              </button>
            </div>

          {:else if loadingStreams}
            <div class="flex flex-col items-center justify-center gap-3 py-16">
              <Spinner class="size-10" />
              <span class="animate-pulse text-base text-muted-foreground">Finding streams…</span>
            </div>

          {:else if streams.length === 0}
            <div class="flex flex-col items-center justify-center gap-3 py-16">
              <Spinner class="size-10" />
              <span class="animate-pulse text-base text-muted-foreground">
                No streams found — retrying…
              </span>
            </div>

          {:else if filteredStreams.length === 0}
            <div class="flex items-center justify-center py-16">
              <span class="text-base text-muted-foreground">No streams match this filter.</span>
            </div>

          {:else}
            <!--
              Column group — native <button> elements inside are discovered by
              containment; no use:focusable needed on each row.
              Up from the first row falls through column policy and geometric nav
              finds the streams-filters row above.
            -->
            <div
              use:focusGroup={{ id: "streams-results", policy: { type: "column" } }}
              class="flex flex-col gap-3"
            >
              {#each filteredStreams as item (item.key)}
                {@const stream = item.stream}
                <button
                  type="button"
                  class="flex w-full flex-col gap-2 rounded-xl border border-border/50 bg-secondary/50 p-4 text-left transition-colors hover:border-border hover:bg-secondary focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-accent focus-visible:ring-offset-1 focus-visible:ring-offset-background"
                  onclick={() => {
                    console.log(
                      `[stream-select] manual: "${stream.name}" — ${formatStreamSummary(stream)}`,
                      stream,
                    );
                    onPlayStream(
                      stream,
                      selectedSeason ?? undefined,
                      selectedEpisode?.episode_number,
                      selectedEpisode?.name,
                    );
                  }}
                >
                  <!-- Name + play icon -->
                  <span class="flex items-center justify-between gap-2">
                    <span class="text-base font-semibold text-foreground">{stream.name}</span>
                    <Play class="size-4 shrink-0 text-foreground/60" />
                  </span>

                  <!-- Title / description -->
                  <span class="line-clamp-2 text-sm whitespace-pre-line text-muted-foreground">
                    {stream.title}
                  </span>

                  <!-- Badges: seeders, size, quality, provider -->
                  <span class="flex flex-wrap gap-2 text-sm text-muted-foreground">
                    {#if isTorrentStream(stream)}
                      <span class="rounded-lg bg-background/70 px-2 py-1">
                        👤 {item.seeders}
                      </span>
                      <span class="rounded-lg bg-background/70 px-2 py-1">
                        💾 {item.sizeBytes / 1024 ** 3 >= 1
                          ? `${(item.sizeBytes / 1024 ** 3).toFixed(2)} GB`
                          : `${(item.sizeBytes / 1024 ** 2).toFixed(0)} MB`}
                      </span>
                    {/if}
                    <span class="rounded-lg bg-background/70 px-2 py-1">
                      {item.quality}
                    </span>
                    {#if stream.addonName}
                      <span
                        class="rounded-lg px-2 py-1 {stream.addonName ===
                        $settings?.defaultProvider
                          ? 'bg-accent text-accent-foreground'
                          : 'bg-background/70'}"
                      >
                        {stream.addonName}
                      </span>
                    {/if}
                  </span>
                </button>
              {/each}
            </div>
          {/if}
        {/if}
      </div>
    </div>
  {/if}
</div>
