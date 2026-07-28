<script lang="ts">
  import { ScrollArea } from "$lib/components/ui/scroll-area/index.js";
  import * as Select from "$lib/components/ui/select/index.js";
  import { Button } from "$lib/components/ui/button/index.js";
  import { Skeleton } from "$lib/components/ui/skeleton";
  import { Spinner } from "$lib/components/ui/spinner";
  import { Check, ChevronLeft, ListFilter, Play, Settings2 } from "lucide-svelte";
  import * as m from "$lib/paraglide/messages.js";

  import { api, formatPosition } from "$lib/api";
  import { epProgress, progressPct } from "$lib/utils";
  import { settings } from "$lib/stores/settings";
  import { codecLabel, langLabel } from "$lib/streamMeta";
  import {
    formatStreamSummary,
    isTorrentStream,
    STREAM_SORT_MODES,
  } from "$lib/streamSelection";
  import {
    StreamsListController,
    watchTypeLabel,
  } from "$lib/streamsList.svelte";
  import EpisodeCard from "./EpisodeCard.svelte";

  let {
    media,
    onPlayStream,
    maxQuality = $bindable<string | null>(),
    streamActive = false,
    activeSeason = undefined,
    activeEpisode = undefined,
    autoJumpToActive = true,
  } = $props();

  // The whole data layer lives in $lib/streamsList.svelte.ts, shared with
  // TvStreamsList. Props and $settings cross the module boundary as getters,
  // never snapshots, so the controller's deriveds keep tracking them.
  const ctl = new StreamsListController({
    getMedia: () => media,
    getStreamActive: () => streamActive,
    getActiveSeason: () => activeSeason,
    getActiveEpisode: () => activeEpisode,
    getAutoJumpToActive: () => autoJumpToActive,
    getSettings: () => $settings,
    setMaxQuality: (q) => (maxQuality = q),
    // Forwarded rather than passed by reference so a parent swapping the
    // handler is picked up, same reason the props above are getters.
    onPlayStream: (...args) => onPlayStream(...args),
  });

  // One $effect per controller lifecycle method, in the order the inline
  // effects used to run. The controller owns no effects itself; each method
  // reads its dependencies synchronously so these bare wrappers track exactly
  // what the original inline effects tracked.
  $effect(() => ctl.resetOnMediaChange());
  $effect(() => ctl.clearAutoPickingWhenPlaying());
  $effect(() => ctl.loadProgress());
  $effect(() => ctl.loadMovieProgress());
  $effect(() => ctl.loadWatchOptions());
  $effect(() => ctl.loadSeasons());
  $effect(() => ctl.loadEpisodes());
  $effect(() => ctl.loadStreams());
</script>

<div
  class="flex h-full w-full flex-col rounded-2xl border border-border bg-background/60 backdrop-blur-xl"
>
  <!-- TV: episode browser -->
  {#if ctl.isTV && !ctl.selectedEpisode}
    <!-- Season picker header -->
    <div class="flex-none border-b border-border p-4">
      {#if ctl.loadingSeasons}
        <span class="animate-pulse text-sm text-muted-foreground"
          >{m.streams_loading_seasons()}</span
        >
      {:else}
        <Select.Root
          type="single"
          value={ctl.selectedSeason?.toString()}
          onValueChange={(v) => {
            ctl.selectedSeason = v ? Number(v) : null;
          }}
        >
          <Select.Trigger class="w-full">
            {ctl.selectedSeasonLabel}
          </Select.Trigger>
          <Select.Content>
            <Select.Group>
              {#each ctl.seasons as s (s.season_number)}
                <Select.Item
                  value={s.season_number.toString()}
                  label={m.common_season_with_episodes({
                    season: s.name,
                    count: s.episode_count,
                  })}
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
        {#if ctl.loadingEpisodes}
          <div class="flex items-center justify-center py-12">
            <span class="animate-pulse text-sm text-muted-foreground"
              >{m.streams_loading_episodes()}</span
            >
          </div>
        {:else}
          {#each ctl.episodes as ep (ep.episode_number)}
            <EpisodeCard
              {media}
              {ep}
              selectedSeason={ctl.selectedSeason}
              bind:selectedEpisode={ctl.selectedEpisode}
              progressMap={ctl.progressMap}
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
      {#if ctl.isTV && ctl.selectedEpisode}
        <Button
          variant="outline"
          onclick={() => ctl.clearSelectedEpisode()}
        >
          <ChevronLeft class="size-4" />
          {m.streams_back_episodes()}
        </Button>

        <!-- Selected episode summary -->
        <div
          class="flex items-start gap-3 rounded-lg border border-border bg-secondary/40 p-2.5"
        >
          {#if ctl.selectedEpisode.still_path}
            <img
              src={ctl.selectedEpisode.still_path}
              alt={ctl.selectedEpisode.name}
              class="aspect-video w-24 shrink-0 rounded-md object-cover"
            />
          {:else}
            <Skeleton
              class="aspect-video w-24 shrink-0 rounded-md object-cover"
            />
          {/if}
          <div class="min-w-0 flex-1">
            <p class="text-[11px] text-muted-foreground">
              {m.common_season_short({ season: ctl.selectedSeason })} · {m.common_episode_short(
                { episode: ctl.selectedEpisode.episode_number },
              )}
            </p>
            <p class="text-sm leading-snug font-semibold">
              {ctl.selectedEpisode.name}
            </p>
            <!-- Episode progress -->
            {#if ctl.selectedSeason != null}
              {@const prog = epProgress(
                ctl.selectedSeason,
                ctl.selectedEpisode.episode_number,
                ctl.progressMap,
              )}
              {#if prog}
                {@const pct = progressPct(prog)}
                {#if prog.completed}
                  <p
                    class="mt-1.5 flex items-center gap-1 text-[11px] text-green-500"
                  >
                    <Check class="size-3" /> {m.media_watched()}
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
          <h3 class="text-lg font-semibold">{m.streams_available()}</h3>
        </div>
        {#if ctl.movieProgress}
          {#if ctl.movieProgress.completed}
            <p class="flex items-center gap-1.5 text-xs text-green-500">
              <Check class="size-3.5" /> {m.media_watched()}
            </p>
          {:else if progressPct(ctl.movieProgress) > 1}
            <div class="space-y-1">
              <div class="h-1 w-full overflow-hidden rounded-full bg-secondary">
                <div
                  class="h-full rounded-full bg-accent transition-all"
                  style="width: {progressPct(ctl.movieProgress)}%"
                ></div>
              </div>
              <p class="text-[11px] text-muted-foreground">
                {formatPosition(ctl.movieProgress.position_seconds)} / {formatPosition(
                  ctl.movieProgress.duration_seconds,
                )}
              </p>
            </div>
          {/if}
        {/if}
      {/if}

      <!-- Quality + sort filters -->
      {#if !ctl.alreadyPlayingThisSelection || ctl.showAlternatives}
        <div class="grid grid-cols-2 gap-2">
          <Select.Root type="single" bind:value={ctl.qualityFilter}>
            <Select.Trigger class="flex w-full">
              <span class="flex flex-row items-center justify-center gap-1">
                <Settings2 class="size-4" />
                {ctl.qualityFilter.toUpperCase()}
              </span>
            </Select.Trigger>
            <Select.Content>
              <Select.Group>
                {#each ctl.availableQualities as q (q)}
                  <Select.Item value={q} label={q.toUpperCase()} />
                {/each}
              </Select.Group>
            </Select.Content>
          </Select.Root>

          <Select.Root type="single" bind:value={ctl.sortMode}>
            <Select.Trigger class="flex w-full">
              <span class="flex flex-row items-center justify-center gap-1">
                <ListFilter class="size-4" />
                {STREAM_SORT_MODES.find((m) => m.value === ctl.sortMode)?.label.toUpperCase()}
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
        {#if ctl.watchOptions.length > 0}
          <div class="mb-4">
            <p
              class="mb-2 text-xs font-medium text-muted-foreground uppercase tracking-wide"
            >
              {m.streams_where_watch()}
            </p>
            <div class="flex flex-wrap gap-2">
              {#each ctl.watchOptions as opt (opt.providerId + opt.type)}
                <button
                  onclick={() => window.open(opt.link, "_blank")}
                  class="flex items-center gap-1.5 rounded-md border border-border bg-secondary/40 px-2.5 py-1.5 text-xs transition-colors hover:bg-secondary"
                  title="{opt.providerName} ({watchTypeLabel(opt.type)})"
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
                      · {watchTypeLabel(opt.type)}
                    </span>
                  {/if}
                </button>
              {/each}
            </div>
          </div>
        {/if}

        {#if ctl.alreadyPlayingThisSelection}
          <div
            class="flex items-center justify-between gap-2 rounded-lg border border-accent/30 bg-accent/10 px-3 py-2 text-sm text-accent"
            class:mb-3={ctl.showAlternatives}
          >
            <span class="flex items-center gap-2">
              <Play class="size-4 fill-current" />
              {m.streams_playing_current()}
            </span>
            <Button
              variant="ghost"
              size="sm"
              onclick={() => (ctl.showAlternatives = !ctl.showAlternatives)}
            >
              {ctl.showAlternatives
                ? m.streams_hide_alternatives()
                : m.streams_see_alternatives()}
            </Button>
          </div>
        {/if}
        {#if !ctl.alreadyPlayingThisSelection || ctl.showAlternatives}
          {#if ctl.autoPicking && !ctl.autoPickCancelled && !ctl.alreadyPlayingThisSelection}
            <div class="flex flex-col items-center justify-center gap-3 py-12">
              <Spinner class="size-8" />
              <span class="text-sm text-muted-foreground">
                {m.streams_auto_selecting()}
              </span>
              <Button
                variant="outline"
                size="sm"
                onclick={() => ctl.cancelAutoPick()}
              >
                {m.streams_choose_manually()}
              </Button>
            </div>
          {:else if ctl.loadingStreams}
            <div class="flex flex-col items-center justify-center gap-2 py-12">
              <Spinner class="size-8" />
              <span class="animate-pulse text-sm text-muted-foreground">
                {m.streams_loading()}
              </span>
            </div>
          {:else if ctl.streams.length === 0}
            <div class="flex flex-col items-center justify-center gap-2 py-12">
              <Spinner class="size-8" />
              <span class="animate-pulse text-sm text-muted-foreground">
                {m.streams_none_retrying()}
              </span>
            </div>
          {:else if ctl.filteredStreams.length === 0}
            <div class="flex items-center justify-center py-12">
              <span class="text-sm text-muted-foreground"
                >{m.streams_no_filter()}</span
              >
            </div>
          {:else}
            <div class="flex flex-col gap-3">
              {#each ctl.filteredStreams as item (item.key)}
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
                        {m.streams_unsupported()}
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
                            ctl.selectedSeason ?? undefined,
                            ctl.selectedEpisode?.episode_number,
                            ctl.selectedEpisode?.name,
                          );
                        }}
                      >
                        {m.streams_play_anyway()}
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
                      ctl.selectedSeason ?? undefined,
                      ctl.selectedEpisode?.episode_number,
                      ctl.selectedEpisode?.name,
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
                          ⚡ {stream.debrid || m.streams_sort_cached()}
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
