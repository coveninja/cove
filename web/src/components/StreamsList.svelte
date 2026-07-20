<script lang="ts">
  import {epKey, epProgress, getMaxQuality, inferQuality, progressPct,} from "$lib/utils";
  import {ScrollArea} from "$lib/components/ui/scroll-area/index.js";
  import type {Stream, WatchOption} from "$lib/types/addons";
  import * as Select from "$lib/components/ui/select/index.js";
  import {Check, ChevronLeft, ListFilter, Play, Settings2,} from "lucide-svelte";
  import {Button} from "$lib/components/ui/button/index.js";
  import {Spinner} from "$lib/components/ui/spinner";
  import {SvelteMap, SvelteSet} from "svelte/reactivity";
  import {api, formatPosition} from "$lib/api";
  import type {WatchProgress} from "$lib/types/library";
  import {settings} from "$lib/stores/settings";
  import {
    compareStreamsBy,
    formatAutoPickReason,
    formatStreamSummary,
    getSeeders,
    getSizeBytes,
    isCodecHardDisabled,
    isTorrentStream,
    rankStreams,
    rankStreamsWithProbe,
    STREAM_SORT_MODES,
    type StreamSelectionMode,
    type StreamSortMode,
  } from "$lib/streamSelection";
  import {codecLabel, langLabel, parseStreamMeta, type ParsedStreamMeta,} from "$lib/streamMeta";
  import {Skeleton} from "$lib/components/ui/skeleton";
  import type {TVEpisode} from "$lib/types/tmdb";
  import EpisodeCard from "./EpisodeCard.svelte";

  let loadingStreams = $state(false);
  let sortMode = $state<StreamSortMode>("seeders");
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

  // TV types

  type TVSeason = {
    season_number: number;
    episode_count: number;
    name: string;
    poster_path: string;
  };

  // State

  const isTV = $derived(media.media_type === "tv");

  // TV browsing state
  let seasons = $state<TVSeason[]>([]);
  let episodes = $state<TVEpisode[]>([]);
  let selectedSeason = $state<number | null>(null);
  let selectedEpisode = $state<TVEpisode | null>(null);

  // Reset TV browsing state when the media prop changes identity (e.g. the
  // in-player episodes sidebar switches to a different title without unmounting
  // this component). Without this, selectedSeason from the previous title leaks
  // into the new title's season fetch, causing it to skip the default-season
  // logic and sometimes show episodes from the wrong season.
  let _prevMediaId: number | null = null;
  $effect(() => {
    const id = media.id;
    if (_prevMediaId !== null && id !== _prevMediaId) {
      selectedSeason = null;
      selectedEpisode = null;
      seasons = [];
      episodes = [];
    }
    _prevMediaId = id;
  });
  let loadingSeasons = $state(false);
  let loadingEpisodes = $state(false);

  // Stream state
  let streams = $state<Stream[]>([]);
  let watchOptions = $state<WatchOption[]>([]);

  let pollInterval: ReturnType<typeof setInterval> | null = null;
  let pollAttempts = 0;
  // Indexers that never turn anything up shouldn't poll forever — cap it and
  // fall back to the existing empty state. Halved from 20 alongside the 1s→2s
  // poll interval below (B4) — same ~20s total window, half the requests.
  const MAX_POLL_ATTEMPTS = 10;

  // ── Fetch sequencing (B3) ──────────────────────────────────────────────────
  // fetchSeq/abortCtrl guard against rapid episode switching racing a stale
  // response: the effect below bumps fetchSeq and creates a fresh
  // AbortController on every run, fetchStreams bails before touching
  // streams/maxQuality/auto-pick if its seq has been superseded, and
  // autoPickTimer is explicitly cleared on effect cleanup so a pending
  // 500ms auto-pick from the *previous* episode/season can never fire after
  // the user has already moved on (the old wrong-episode-autoplay bug).
  let fetchSeq = 0;
  let abortCtrl: AbortController | null = null;
  let autoPickTimer: ReturnType<typeof setTimeout> | null = null;

  // ── Auto stream selection ─────────────────────────────────────────────────────

  let autoPicking = $state(false);
  let autoPickCancelled = $state(false);
  // Whether to show the picker at all when something's already playing for
  // this exact selection — keeps the panel from defaulting to "here's a
  // full list to pick from" when there's nothing to actually decide yet.
  let showAlternatives = $state(false);

  // True when the season/episode currently browsed here is the exact thing
  // already playing (full or minimized to PiP). Prevents auto-select from
  // firing again and silently swapping out the stream you're watching —
  // this list keeps polling/rendering in the background now that the
  // player no longer unmounts it while a stream is active.
  const alreadyPlayingThisSelection = $derived(
    streamActive &&
      (!isTV ||
        (selectedSeason === activeSeason &&
          selectedEpisode?.episode_number === activeEpisode)),
  );

  // fetchStreams sets autoPicking = true right before kicking off playback,
  // but nothing ever flips it back once that stream actually starts — it
  // used to not matter because this whole component got unmounted the
  // instant playback began. It no longer does, so clear it explicitly once
  // we can see the pick succeeded.
  $effect(() => {
    if (alreadyPlayingThisSelection && autoPicking) {
      autoPicking = false;
    }
  });

  // ── Watch progress ────────────────────────────────────────────────────────────

  // TV: keyed by "season:episode"
  let progressMap = new SvelteMap<string, WatchProgress>();
  // Movie: single record
  let movieProgress = $state<WatchProgress | null>(null);

  // Fetch all episode progress for this show whenever the media changes
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

  // Fetch movie progress
  $effect(() => {
    if (isTV) return;
    api
      .progressGet(media.id, "movie")
      .then((p) => {
        movieProgress = p;
      })
      .catch(console.error);
  });

  // Fetch streaming availability (JustWatch) — runs once per media item
  $effect(() => {
    api
      .getWatchOptions(media.id, media.media_type)
      .then((opts) => (watchOptions = opts))
      .catch(() => (watchOptions = []));
  });

  // Data fetching

  $effect(() => {
    if (!isTV) return;
    loadingSeasons = true;
    api
      .tvSeasons<TVSeason>(media.id)
      .then((data) => {
        seasons = data ?? [];
        if (seasons.length > 0 && selectedSeason === null) {
          // Land on whatever's already playing (full or minimized to PiP)
          // instead of always defaulting to season 1.
          selectedSeason = activeSeason != null &&
          seasons.some((s) => s.season_number === activeSeason)
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
        // Same idea, one level deeper: jump straight to the episode that's
        // already playing rather than leaving the user on the episode
        // browser, having to find and re-click it themselves.
        // When autoJumpToActive is false (the in-player sidebar), skip this
        // so the sidebar opens on the episode list rather than the stream list.
        if (autoJumpToActive && selectedSeason === activeSeason && activeEpisode != null) {
          const match = episodes.find(
            (e) => e.episode_number === activeEpisode,
          );
          if (match) selectedEpisode = match;
        }
      })
      .finally(() => (loadingEpisodes = false));
  });

  $effect(() => {
    if (isTV && (!selectedEpisode || selectedSeason === null))
      return () => {};

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
      if (seq !== fetchSeq || ctrl.signal.aborted) return; // superseded or destroyed before response landed
      loadingStreams = false;
      if (streams.length === 0)
        // 2s, not 1s (B4) — A3's per-addon negative cache makes each poll
        // hit-or-miss the same 20s-TTL cache entry either way, so a tighter
        // interval mostly just burns more requests without surfacing results
        // any sooner.
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

  // Stream helpers

  const availableQualities = $derived.by(() => {
    const qs = [
      ...new Set(streams.map((s) => inferQuality(s)).filter(Boolean)),
    ];
    qs.sort(
      (a, b) =>
        ["4k dv", "4k hdr", "4k", "1080p", "720p", "480p", "ts", "cam"].indexOf(
          a!,
        ) -
        ["4k dv", "4k hdr", "4k", "1080p", "720p", "480p", "ts", "cam"].indexOf(
          b!,
        ),
    );
    return ["all", ...qs];
  });

  // D5: seeders/size/quality are regex-parsed out of the stream title (see
  // streamSelection.ts) — parsing is the expensive part, so it only reruns
  // when `streams` itself changes, not on every filter/sort toggle. `key` is
  // a stable identity for the {#each} below (url/infoHash/title, matching
  // rankStreams' dedup key) so toggling a filter/sort no longer tears down
  // and rebuilds every row's DOM (the previous key was object identity on a
  // freshly-mapped object every derive, which changed on every filter/sort
  // toggle even though the underlying stream hadn't).
  // Some addons return identical streams (same URL/infoHash/title), which
  // crashes Svelte's keyed {#each} block. We dedupe by `key` here.
  interface ParsedStream {
    stream: Stream;
    key: string;
    seeders: number;
    sizeBytes: number;
    quality: string | null;
    /** Codec/language details parsed from the release name — drives the
     * showStreamDetails badges. */
    meta: ParsedStreamMeta;
    /** Device probe says this codec can't be hardware-decoded — row renders
     * greyed/unselectable with a "Play anyway" override. */
    isHardDisabled: boolean;
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
        meta: parseStreamMeta(s),
        isHardDisabled: isCodecHardDisabled(s),
      });
    }
    return result;
  });

  // Preferred audio language with "original" resolved to the title's TMDB
  // original language — shared by the auto-select ranking and language sort.
  const effectiveAudioLang = $derived(
    $settings?.defaultAudioLang === "original"
      ? (media.original_language ?? "")
      : ($settings?.defaultAudioLang ?? ""),
  );

  const filteredStreams = $derived.by(() => {
    const filtered = parsedStreams.filter(
      (s) => qualityFilter === "all" || s.quality === qualityFilter,
    );
    const preferred = $settings?.defaultProvider;
    const compare = compareStreamsBy(sortMode, effectiveAudioLang || undefined);
    return filtered.toSorted((a, b) => {
      if (preferred) {
        const aPref = a.stream.addonName === preferred ? 1 : 0;
        const bPref = b.stream.addonName === preferred ? 1 : 0;
        if (aPref !== bPref) return bPref - aPref;
      }
      return compare(a, b);
    });
  });

  const selectedSeasonLabel = $derived(
    seasons.find((s) => s.season_number === selectedSeason)?.name ??
      (selectedSeason !== null ? `Season ${selectedSeason}` : "Season"),
  );

  function clearPoll(): void {
    if (pollInterval) {
      clearInterval(pollInterval);
      pollInterval = null;
    }
  }

  // setInterval callback for the empty-results poll. Stops itself (falling
  // back to the existing "no streams" empty state) once MAX_POLL_ATTEMPTS is
  // reached instead of retrying forever. seq/signal are bound to the fetch
  // generation that started this poll — if a newer effect run has since
  // superseded it, bail immediately instead of firing a stale request.
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

    // Superseded by a newer effect run (episode/season switch) while this
    // request was in flight — discard rather than clobber the current pick.
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
        defaultAudioLang: effectiveAudioLang || undefined,
      };
      // Synchronous initial ranking — drives the log line and the fallback
      // used when the probe doesn't land before the 500ms window closes.
      const initialRanking = rankStreams(streams, selectionMode, rankOpts);
      const best = initialRanking[0] ?? null;
      if (best) {
        const mode = $settings.streamSelectionMode ?? "balanced";
        console.log(
          `[stream-select] auto (${mode}): "${best.name}" — ${formatAutoPickReason(best)}`,
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
        // Small delay so the "Auto-selecting…" message and its cancel
        // button actually get a moment on screen before playback starts.
        autoPickTimer = setTimeout(() => {
          autoPickTimer = null;
          if (seq === fetchSeq && !autoPickCancelled) {
            const ranking = probedRanking ?? initialRanking;
            // Pass a handful of runner-up candidates so App.svelte's
            // watchdog (B2) can auto-advance to the next one if this pick
            // turns out to be dead, without a full re-fetch.
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
</script>

<div
  class="flex h-full w-full flex-col rounded-2xl border border-border bg-background/60 backdrop-blur-xl"
>
  <!-- TV: episode browser -->
  {#if isTV && !selectedEpisode}
    <!-- Season picker header -->
    <div class="flex-none border-b border-border p-4">
      {#if loadingSeasons}
        <span class="animate-pulse text-sm text-muted-foreground"
          >Loading seasons…</span
        >
      {:else}
        <Select.Root
          type="single"
          value={selectedSeason?.toString()}
          onValueChange={(v) => {
            selectedSeason = v ? Number(v) : null;
          }}
        >
          <Select.Trigger class="w-full">
            {selectedSeasonLabel}
          </Select.Trigger>
          <Select.Content>
            <Select.Group>
              {#each seasons as s (s.season_number)}
                <Select.Item
                  value={s.season_number.toString()}
                  label="{s.name} ({s.episode_count} eps)"
                />
              {/each}
            </Select.Group>
          </Select.Content>
        </Select.Root>
      {/if}
    </div>

    <!-- Episode rows -->
    <ScrollArea class="min-h-0 flex-1 p-2">
      <div class="flex flex-col divide-y divide-border">
        {#if loadingEpisodes}
          <div class="flex items-center justify-center py-12">
            <span class="animate-pulse text-sm text-muted-foreground"
              >Loading episodes…</span
            >
          </div>
        {:else}
          {#each episodes as ep (ep.episode_number)}
            <EpisodeCard
              {media}
              {ep}
              {selectedSeason}
              bind:selectedEpisode
              {progressMap}
              {activeSeason}
              {activeEpisode}
            />
          {/each}
        {/if}
      </div>
    </ScrollArea>

    <!-- Stream list (movies always, TV after episode picked) -->
  {:else}
    <!-- Header: back button for TV, or plain title for movies -->
    <div class="flex-none space-y-3 border-b border-border p-5">
      {#if isTV && selectedEpisode}
        <Button
          variant="outline"
          onclick={() => {
            selectedEpisode = null;
            streams = [];
          }}
        >
          <ChevronLeft class="size-4" />
          Back to episodes
        </Button>

        <!-- Selected episode summary -->
        <div
          class="flex items-start gap-3 rounded-lg border border-border bg-secondary/40 p-2.5"
        >
          {#if selectedEpisode.still_path}
            <img
              src={selectedEpisode.still_path}
              alt={selectedEpisode.name}
              class="aspect-video w-24 shrink-0 rounded-md object-cover"
            />
          {:else}
            <Skeleton
              class="aspect-video w-24 shrink-0 rounded-md object-cover"
            />
          {/if}
          <div class="min-w-0 flex-1">
            <p class="text-[11px] text-muted-foreground">
              S{selectedSeason} · E{selectedEpisode.episode_number}
            </p>
            <p class="text-sm leading-snug font-semibold">
              {selectedEpisode.name}
            </p>
            <!-- Episode progress -->
            {#if selectedSeason != null}
              {@const prog = epProgress(
                selectedSeason,
                selectedEpisode.episode_number,
                progressMap,
              )}
              {#if prog}
                {@const pct = progressPct(prog)}
                {#if prog.completed}
                  <p
                    class="mt-1.5 flex items-center gap-1 text-[11px] text-green-500"
                  >
                    <Check class="size-3" /> Watched
                  </p>
                {:else if pct > 1}
                  <div class="mt-2 space-y-1">
                    <div
                      class="h-1 w-full overflow-hidden rounded-full bg-secondary"
                    >
                      <div
                        class="h-full rounded-full bg-accent transition-all"
                        style="width: {pct}%"
                      ></div>
                    </div>
                    <p class="text-[10px] text-muted-foreground">
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
        <div class="flex items-start justify-between gap-3">
          <h3 class="text-lg font-semibold">Available Streams</h3>
        </div>
        {#if movieProgress}
          {#if movieProgress.completed}
            <p class="flex items-center gap-1.5 text-xs text-green-500">
              <Check class="size-3.5" /> Watched
            </p>
          {:else if progressPct(movieProgress) > 1}
            <div class="space-y-1">
              <div class="h-1 w-full overflow-hidden rounded-full bg-secondary">
                <div
                  class="h-full rounded-full bg-accent transition-all"
                  style="width: {progressPct(movieProgress)}%"
                ></div>
              </div>
              <p class="text-[11px] text-muted-foreground">
                {formatPosition(movieProgress.position_seconds)} / {formatPosition(
                  movieProgress.duration_seconds,
                )}
              </p>
            </div>
          {/if}
        {/if}
      {/if}

      <!-- Quality + sort filters -->
      {#if !alreadyPlayingThisSelection || showAlternatives}
        <div class="grid grid-cols-2 gap-2">
          <Select.Root type="single" bind:value={qualityFilter}>
            <Select.Trigger class="flex w-full">
              <span class="flex flex-row items-center justify-center gap-1">
                <Settings2 class="size-4" />
                {qualityFilter.toUpperCase()}
              </span>
            </Select.Trigger>
            <Select.Content>
              <Select.Group>
                {#each availableQualities as q (q)}
                  <Select.Item value={q} label={q.toUpperCase()} />
                {/each}
              </Select.Group>
            </Select.Content>
          </Select.Root>

          <Select.Root type="single" bind:value={sortMode}>
            <Select.Trigger class="flex w-full">
              <span class="flex flex-row items-center justify-center gap-1">
                <ListFilter class="size-4" />
                {STREAM_SORT_MODES.find((m) => m.value === sortMode)?.label.toUpperCase()}
              </span>
            </Select.Trigger>
            <Select.Content>
              <Select.Group>
                {#each STREAM_SORT_MODES as m (m.value)}
                  <Select.Item value={m.value} label={m.label} />
                {/each}
              </Select.Group>
            </Select.Content>
          </Select.Root>
        </div>
      {/if}
    </div>

    <!-- Stream rows -->
    <ScrollArea class="min-h-0 flex-1">
      <div class="p-4">
        <!-- Where to Watch (JustWatch) -->
        {#if watchOptions.length > 0}
          <div class="mb-4">
            <p
              class="mb-2 text-xs font-medium text-muted-foreground uppercase tracking-wide"
            >
              Where to Watch
            </p>
            <div class="flex flex-wrap gap-2">
              {#each watchOptions as opt (opt.providerId + opt.type)}
                <button
                  onclick={() => window.open(opt.link, "_blank")}
                  class="flex items-center gap-1.5 rounded-md border border-border bg-secondary/40 px-2.5 py-1.5 text-xs transition-colors hover:bg-secondary"
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
                    <span class="text-muted-foreground capitalize">
                      · {opt.type}
                    </span>
                  {/if}
                </button>
              {/each}
            </div>
          </div>
        {/if}

        {#if alreadyPlayingThisSelection}
          <div
            class="flex items-center justify-between gap-2 rounded-lg border border-accent/30 bg-accent/10 px-3 py-2 text-sm text-accent"
            class:mb-3={showAlternatives}
          >
            <span class="flex items-center gap-2">
              <Play class="size-4 fill-current" />
              Playing this stream
            </span>
            <Button
              variant="ghost"
              size="sm"
              onclick={() => (showAlternatives = !showAlternatives)}
            >
              {showAlternatives ? "Hide alternatives" : "See alternatives"}
            </Button>
          </div>
        {/if}
        {#if !alreadyPlayingThisSelection || showAlternatives}
          {#if autoPicking && !autoPickCancelled && !alreadyPlayingThisSelection}
            <div class="flex flex-col items-center justify-center gap-3 py-12">
              <Spinner class="size-8" />
              <span class="text-sm text-muted-foreground">
                Auto-selecting the best stream…
              </span>
              <Button
                variant="outline"
                size="sm"
                onclick={() => {
                  autoPickCancelled = true;
                  autoPicking = false;
                }}
              >
                Choose manually instead
              </Button>
            </div>
          {:else if loadingStreams}
            <div class="flex flex-col items-center justify-center gap-2 py-12">
              <Spinner class="size-8" />
              <span class="animate-pulse text-sm text-muted-foreground">
                Finding streams…
              </span>
            </div>
          {:else if streams.length === 0}
            <div class="flex flex-col items-center justify-center gap-2 py-12">
              <Spinner class="size-8" />
              <span class="animate-pulse text-sm text-muted-foreground">
                No streams found — retrying…
              </span>
            </div>
          {:else if filteredStreams.length === 0}
            <div class="flex items-center justify-center py-12">
              <span class="text-sm text-muted-foreground"
                >No streams match this filter.</span
              >
            </div>
          {:else}
            <div class="flex flex-col gap-3">
              {#each filteredStreams as item (item.key)}
                {@const stream = item.stream}
                {#if item.isHardDisabled}
                  <!-- Codec the device provably can't hardware-decode: the row
                       is inert, with a small "Play anyway" escape hatch (mpv
                       will software-decode, usually too slowly to watch). -->
                  <div
                    aria-disabled="true"
                    class="flex w-full cursor-not-allowed flex-col gap-1 rounded-lg border border-border/30 bg-secondary/20 p-3 text-left opacity-60"
                  >
                    <span class="flex items-center justify-between gap-2">
                      <span class="text-sm font-medium text-foreground"
                        >{stream.name}</span
                      >
                      <span
                        class="shrink-0 rounded bg-destructive/20 px-1.5 py-0.5 text-[10px] font-medium text-destructive"
                      >
                        Unsupported
                      </span>
                    </span>

                    <span
                      class="line-clamp-2 text-xs whitespace-pre-line text-muted-foreground"
                    >
                      {stream.title}
                    </span>

                    <span
                      class="mt-1 flex flex-wrap items-center gap-1.5 text-[11px] text-muted-foreground"
                    >
                      {#if $settings?.showStreamDetails ?? true}
                        {#if isTorrentStream(stream)}
                          <span class="rounded bg-background/70 px-1.5 py-0.5">
                            👤 {item.seeders}
                          </span>
                        {/if}
                        <span class="rounded bg-background/70 px-1.5 py-0.5">
                          {item.quality}
                        </span>
                      {/if}
                      <!-- Always shown — it's why the row is unsupported. -->
                      {#if codecLabel(item.meta)}
                        <span class="rounded bg-background/70 px-1.5 py-0.5">
                          {codecLabel(item.meta)}
                        </span>
                      {/if}
                      {#if stream.addonName}
                        <span class="rounded bg-background/70 px-1.5 py-0.5">
                          {stream.addonName}
                        </span>
                      {/if}
                      <button
                        class="ml-auto cursor-pointer rounded px-1.5 py-0.5 underline hover:text-foreground"
                        onclick={() => {
                          console.log(
                            `[stream-select] play-anyway: "${stream.name}" — ${formatStreamSummary(stream)}`,
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
                        Play anyway
                      </button>
                    </span>
                  </div>
                {:else}
                <button
                  class="group flex w-full flex-col gap-1 rounded-lg border border-border/50 bg-secondary/50 p-3 text-left transition-colors hover:border-border hover:bg-secondary"
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
                  <span class="flex items-center justify-between gap-2">
                    <span class="text-sm font-medium text-foreground"
                      >{stream.name}</span
                    >
                    <Play
                      class="size-3 text-foreground opacity-0 transition-opacity group-hover:opacity-100"
                    />
                  </span>

                  <span
                    class="line-clamp-2 text-xs whitespace-pre-line text-muted-foreground"
                  >
                    {stream.title}
                  </span>

                  <span
                    class="mt-1 flex flex-wrap gap-1.5 text-[11px] text-muted-foreground"
                  >
                    {#if $settings?.showStreamDetails ?? true}
                      {#if isTorrentStream(stream)}
                        <span class="rounded bg-background/70 px-1.5 py-0.5">
                          👤 {item.seeders}
                        </span>
                      {/if}
                      {#if item.sizeBytes > 0}
                        <span class="rounded bg-background/70 px-1.5 py-0.5">
                          💾 {item.sizeBytes / 1024 ** 3 >= 1
                            ? `${(item.sizeBytes / 1024 ** 3).toFixed(2)} GB`
                            : `${(item.sizeBytes / 1024 ** 2).toFixed(0)} MB`}
                        </span>
                      {/if}
                      <span class="rounded bg-background/70 px-1.5 py-0.5">
                        {item.quality}
                      </span>
                      {#if codecLabel(item.meta)}
                        <span class="rounded bg-background/70 px-1.5 py-0.5">
                          {codecLabel(item.meta)}
                        </span>
                      {/if}
                      <!-- The quality tier already says "4k dv"/"4k hdr" — only
                           badge DV/HDR when the tier doesn't carry it. -->
                      {#if item.meta.isDolbyVision && !item.quality?.includes("dv")}
                        <span class="rounded bg-background/70 px-1.5 py-0.5">DV</span>
                      {:else if item.meta.isHDR && !item.quality?.includes("hdr")}
                        <span class="rounded bg-background/70 px-1.5 py-0.5">HDR</span>
                      {/if}
                      {#if langLabel(item.meta)}
                        <span class="rounded bg-background/70 px-1.5 py-0.5">
                          {langLabel(item.meta)}
                        </span>
                      {/if}
                      {#if stream.cached}
                        <span class="rounded bg-background/70 px-1.5 py-0.5">
                          ⚡ {stream.debrid || "Cached"}
                        </span>
                      {:else if stream.debrid}
                        <span class="rounded bg-background/70 px-1.5 py-0.5">
                          {stream.debrid}
                        </span>
                      {/if}
                    {/if}
                    {#if stream.addonName}
                      <span
                        class="rounded px-1.5 py-0.5 {stream.addonName ===
                        $settings?.defaultProvider
                          ? 'bg-accent text-accent-foreground'
                          : 'bg-background/70'}"
                      >
                        {stream.addonName}
                      </span>
                    {/if}
                  </span>
                </button>
                {/if}
              {/each}
            </div>
          {/if}
        {/if}
      </div>
    </ScrollArea>
  {/if}
</div>
