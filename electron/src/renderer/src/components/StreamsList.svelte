<script lang="ts">
  import { getMaxQuality, inferQuality } from "$lib/utils";
  import { ScrollArea } from "$lib/components/ui/scroll-area/index.js";
  import type { Stream } from "$lib/types/addons";
  import * as Select from "$lib/components/ui/select/index.js";
  import * as ContextMenu from "$lib/components/ui/context-menu/index.js";
  import {
    ListFilter,
    Play,
    Settings2,
    ChevronLeft,
    Check,
    RotateCcw,
  } from "lucide-svelte";
  import { Button } from "$lib/components/ui/button/index.js";
  import { Spinner } from "$lib/components/ui/spinner";
  import { SvelteMap } from "svelte/reactivity";
  import { api, formatPosition } from "$lib/api";
  import type { WatchProgress } from "$lib/types/library";

  let loadingStreams = $state(false);
  let sortMode = $state<"seeders" | "size">("seeders");
  let qualityFilter = $state("all");

  let {
    media,
    onPlayStream,
    maxQuality = $bindable<string | null>(),
  } = $props();

  // TV types

  type TVSeason = {
    season_number: number;
    episode_count: number;
    name: string;
    poster_path: string;
  };

  type TVEpisode = {
    episode_number: number;
    name: string;
    overview: string;
    still_path: string;
    air_date: string;
  };

  // State

  const isTV = $derived(media.media_type === "tv");

  // TV browsing state
  let seasons = $state<TVSeason[]>([]);
  let episodes = $state<TVEpisode[]>([]);
  let selectedSeason = $state<number | null>(null);
  let selectedEpisode = $state<TVEpisode | null>(null);
  let loadingSeasons = $state(false);
  let loadingEpisodes = $state(false);

  // Stream state
  let streams = $state<Stream[]>([]);

  let pollInterval: ReturnType<typeof setInterval> | null = null;

  // ── Watch progress ────────────────────────────────────────────────────────────

  // TV: keyed by "season:episode"
  let progressMap = new SvelteMap<string, WatchProgress>();
  // Movie: single record
  let movieProgress = $state<WatchProgress | null>(null);

  function epKey(season: number, episode: number) {
    return `${season}:${episode}`;
  }

  function epProgress(
    season: number,
    episode: number,
  ): WatchProgress | undefined {
    return progressMap.get(epKey(season, episode));
  }

  function progressPct(p: WatchProgress): number {
    if (!p.duration_seconds) return 0;
    return Math.min(100, (p.position_seconds / p.duration_seconds) * 100);
  }

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

  async function markWatched(ep: TVEpisode): Promise<void> {
    const p = await api.progressSave({
      tmdb_id: media.id,
      media_type: "tv",
      title: media.name,
      poster_path: media.poster_path ?? "",
      vote_average: media.vote_average ?? 0,
      season: selectedSeason!,
      episode: ep.episode_number,
      position_seconds: 1,
      duration_seconds: 1,
      completed: true,
    });
    progressMap.set(epKey(selectedSeason!, ep.episode_number), p);
  }

  async function markUnwatched(ep: TVEpisode): Promise<void> {
    const p = await api.progressSave({
      tmdb_id: media.id,
      media_type: "tv",
      title: media.name,
      poster_path: media.poster_path ?? "",
      vote_average: media.vote_average ?? 0,
      season: selectedSeason!,
      episode: ep.episode_number,
      position_seconds: 0,
      duration_seconds: 0,
      completed: false,
    });
    progressMap.set(epKey(selectedSeason!, ep.episode_number), p);
  }

  // Data fetching

  $effect(() => {
    if (!isTV) return;
    loadingSeasons = true;
    fetch(`http://localhost:6969/api/tv/seasons?id=${media.id}`)
      .then((r) => r.json())
      .then((data: TVSeason[]) => {
        seasons = data ?? [];
        if (seasons.length > 0 && selectedSeason === null) {
          selectedSeason = seasons[0].season_number;
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
    fetch(
      `http://localhost:6969/api/tv/episodes?id=${media.id}&season=${selectedSeason}`,
    )
      .then((r) => r.json())
      .then((data: TVEpisode[]) => (episodes = data ?? []))
      .finally(() => (loadingEpisodes = false));
  });

  $effect(() => {
    if (isTV) {
      if (!selectedEpisode || selectedSeason === null) return () => {};
      clearPoll();
      loadingStreams = true;
      streams = [];
      fetchStreams().then(() => {
        loadingStreams = false;
        if (streams.length === 0)
          pollInterval = setInterval(fetchStreams, 1000);
      });
    } else {
      clearPoll();
      loadingStreams = true;
      streams = [];
      fetchStreams().then(() => {
        loadingStreams = false;
        if (streams.length === 0)
          pollInterval = setInterval(fetchStreams, 1000);
      });
    }

    return () => clearPoll();
  });

  // Stream helpers

  function getSeeders(stream: Stream): number {
    const match = stream.title.match(/👤\s*(\d+)/);
    return match ? Number(match[1]) : 0;
  }

  function getSizeBytes(stream: Stream): number {
    const match = stream.title.match(/💾\s*([\d.]+)\s*(TB|GB|MB)/i);
    if (!match) return 0;
    const value = Number(match[1]);
    switch (match[2].toUpperCase()) {
      case "TB":
        return value * 1024 ** 4;
      case "GB":
        return value * 1024 ** 3;
      case "MB":
        return value * 1024 ** 2;
      default:
        return 0;
    }
  }

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

  const filteredStreams = $derived.by(() => {
    const list = streams.map((s) => ({
      ...s,
      seeders: getSeeders(s),
      sizeBytes: getSizeBytes(s),
      quality: inferQuality(s),
    }));
    const filtered = list.filter(
      (s) => qualityFilter === "all" || s.quality === qualityFilter,
    );
    filtered.sort((a, b) =>
      sortMode === "seeders"
        ? b.seeders - a.seeders
        : b.sizeBytes - a.sizeBytes,
    );
    return filtered;
  });

  const selectedSeasonLabel = $derived(
    seasons.find((s) => s.season_number === selectedSeason)?.name ??
      (selectedSeason !== null ? `Season ${selectedSeason}` : "Season"),
  );

  function relativeDate(dateStr: string): string {
    const days = Math.ceil(
      (new Date(dateStr).getTime() - Date.now()) / (1000 * 60 * 60 * 24),
    );
    if (days <= 1) return "Coming Tomorrow";
    if (days <= 7) return `Coming in ${days} Days`;
    if (days <= 14) return "Coming Next Week";
    return `Coming ${new Date(dateStr).toLocaleDateString(undefined, { month: "short", day: "numeric" })}`;
  }

  function clearPoll(): void {
    if (pollInterval) {
      clearInterval(pollInterval);
      pollInterval = null;
    }
  }

  async function fetchStreams(): Promise<void> {
    const API_BASE = "http://localhost:6969";
    const url = isTV
      ? `${API_BASE}/api/streams?id=${media.id}&type=tv&season=${selectedSeason}&episode=${selectedEpisode!.episode_number}`
      : `${API_BASE}/api/streams?id=${media.id}`;

    return await fetch(url)
      .then((r) => r.json())
      .then((res: Stream[]) => {
        streams = res;
        maxQuality = getMaxQuality(streams);
        if (streams.length > 0) clearPoll();
      });
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
    <ScrollArea class="min-h-0 flex-1">
      <div class="flex flex-col divide-y divide-border">
        {#if loadingEpisodes}
          <div class="flex items-center justify-center py-12">
            <span class="animate-pulse text-sm text-muted-foreground"
              >Loading episodes…</span
            >
          </div>
        {:else}
          {#each episodes as ep (ep.episode_number)}
            {@const unreleased =
              ep.air_date && new Date(ep.air_date) > new Date()}
            {@const prog =
              selectedSeason != null
                ? epProgress(selectedSeason, ep.episode_number)
                : undefined}
            {@const pct = prog ? progressPct(prog) : 0}
            {@const completed = prog?.completed ?? false}
            {@const inProgress = !completed && pct > 1}

            <ContextMenu.Root>
              <ContextMenu.Trigger class="w-full text-left">
                <button
                  class="group relative flex w-full items-center gap-3 p-3 text-left transition-colors
                    {unreleased
                    ? 'cursor-default opacity-40'
                    : 'hover:bg-secondary/60'}
                    {completed ? 'opacity-70' : ''}"
                  onclick={() => {
                    if (!unreleased) selectedEpisode = ep;
                  }}
                  disabled={unreleased}
                >
                  <!-- Thumbnail -->
                  <span
                    class="relative w-28 shrink-0 overflow-hidden rounded-md bg-muted"
                  >
                    {#if ep.still_path}
                      <img
                        src={ep.still_path}
                        alt={ep.name}
                        class="aspect-video w-full object-cover"
                      />
                    {:else}
                      <div
                        class="flex aspect-video w-full items-center justify-center bg-secondary"
                      >
                        <Play class="size-5 text-muted-foreground/50" />
                      </div>
                    {/if}

                    <!-- Completed checkmark -->
                    {#if completed}
                      <span
                        class="absolute inset-0 flex items-center justify-center bg-black/50"
                      >
                        <span
                          class="flex size-7 items-center justify-center rounded-full bg-green-500/90"
                        >
                          <Check class="size-4 text-white" />
                        </span>
                      </span>
                    {:else}
                      <!-- Hover play overlay (only when not completed) -->
                      <span
                        class="absolute inset-0 flex items-center justify-center bg-black/0 transition-colors group-hover:bg-black/40"
                      >
                        <Play
                          class="size-5 text-white opacity-0 transition-opacity group-hover:opacity-100"
                        />
                      </span>
                    {/if}
                  </span>

                  <!-- Info -->
                  <span class="min-w-0 flex-1 flex-col py-0.5">
                    <span class="flex flex-col">
                      <span
                        class="text-sm leading-snug font-medium {completed
                          ? 'text-muted-foreground'
                          : ''}">{ep.name}</span
                      >
                      <span
                        class="flex items-center gap-1.5 text-xs font-semibold text-muted-foreground"
                      >
                        E{ep.episode_number}
                        {#if ep.air_date}
                          · <span class="font-normal"
                            >{unreleased
                              ? relativeDate(ep.air_date)
                              : ep.air_date}</span
                          >
                        {/if}
                        {#if inProgress}
                          · <span class="font-normal text-accent"
                            >{formatPosition(prog!.position_seconds)} watched</span
                          >
                        {/if}
                      </span>
                    </span>
                    {#if ep.overview}
                      <p
                        class="mt-1 line-clamp-2 text-xs leading-relaxed text-muted-foreground"
                      >
                        {ep.overview}
                      </p>
                    {/if}
                  </span>

                  <!-- In-progress bar (absolute bottom of row) -->
                  {#if inProgress}
                    <span
                      class="absolute right-0 bottom-0 left-0 h-0.5 overflow-hidden bg-secondary"
                    >
                      <span
                        class="block h-full bg-accent transition-all"
                        style="width: {pct}%"
                      ></span>
                    </span>
                  {/if}
                </button>
              </ContextMenu.Trigger>

              {#if !unreleased}
                <ContextMenu.Content>
                  {#if !completed}
                    <ContextMenu.Item
                      onclick={() => markWatched(ep)}
                      class="flex items-center gap-2"
                    >
                      <Check class="size-4" /> Mark as Watched
                    </ContextMenu.Item>
                  {:else}
                    <ContextMenu.Item
                      onclick={() => markUnwatched(ep)}
                      class="flex items-center gap-2"
                    >
                      <RotateCcw class="size-4" /> Mark as Unwatched
                    </ContextMenu.Item>
                  {/if}
                  <ContextMenu.Separator />
                  <ContextMenu.Item
                    onclick={() => {
                      selectedEpisode = ep;
                    }}
                    class="flex items-center gap-2"
                  >
                    <Play class="size-4" /> View Streams
                  </ContextMenu.Item>
                </ContextMenu.Content>
              {/if}
            </ContextMenu.Root>
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
              {sortMode.toUpperCase()}
            </span>
          </Select.Trigger>
          <Select.Content>
            <Select.Group>
              <Select.Item value="seeders" label="Seeders" />
              <Select.Item value="size" label="Size" />
            </Select.Group>
          </Select.Content>
        </Select.Root>
      </div>
    </div>

    <!-- Stream rows -->
    <ScrollArea class="min-h-0 flex-1">
      <div class="p-4">
        {#if loadingStreams}
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
            {#each filteredStreams as stream (stream)}
              <button
                class="group flex w-full flex-col gap-1 rounded-lg border border-border/50 bg-secondary/50 p-3 text-left transition-colors hover:border-border hover:bg-secondary"
                onclick={() =>
                  onPlayStream(
                    stream,
                    selectedSeason ?? undefined,
                    selectedEpisode?.episode_number,
                  )}
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
                  <span class="rounded bg-background/70 px-1.5 py-0.5">
                    👤 {getSeeders(stream)}
                  </span>
                  <span class="rounded bg-background/70 px-1.5 py-0.5">
                    💾 {getSizeBytes(stream) / 1024 ** 3 >= 1
                      ? `${(getSizeBytes(stream) / 1024 ** 3).toFixed(2)} GB`
                      : `${(getSizeBytes(stream) / 1024 ** 2).toFixed(0)} MB`}
                  </span>
                  <span class="rounded bg-background/70 px-1.5 py-0.5">
                    {inferQuality(stream)}
                  </span>
                </span>
              </button>
            {/each}
          </div>
        {/if}
      </div>
    </ScrollArea>
  {/if}
</div>
