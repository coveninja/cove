<script lang="ts">
  // TV-friendly fork of web/src/components/StreamsList.svelte.
  //
  // DATA LAYER: shared verbatim via $lib/streamsList.svelte.ts — this file used
  // to carry a copy of StreamsList's whole script block. Only the UI layer
  // differs now: Select dropdowns → cycle buttons, ScrollArea → native
  // overflow, shadcn Button/Skeleton → plain elements, EpisodeCard →
  // TvEpisodeCard.
  //
  // PROP CONTRACT: identical to StreamsList so TvDetailOverlay's call site
  // needs only an import swap (no prop changes).

  import { Check, ChevronLeft, Play } from "lucide-svelte";
  import { Spinner } from "$lib/components/ui/spinner";
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
  import { focusGroup, focusable } from "../focus/actions";
  import TvEpisodeCard from "./TvEpisodeCard.svelte";

  // ── Identical prop contract to StreamsList ────────────────────────────────

  let {
    media,
    onPlayStream,
    maxQuality = $bindable<string | null>(),
    streamActive = false,
    activeSeason = undefined,
    activeEpisode = undefined,
    autoJumpToActive = true,
  } = $props();

  // Props and $settings cross the module boundary as getters, never snapshots,
  // so the controller's deriveds keep tracking them.
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
  // effects used to run. ctl.resetOnMediaChange() is deliberately NOT wired:
  // TvDetailOverlay tears this list down between titles, so TV never had the
  // desktop sidebar's mid-mount media swap (see the method's doc comment).
  $effect(() => ctl.clearAutoPickingWhenPlaying());
  $effect(() => ctl.loadProgress());
  $effect(() => ctl.loadMovieProgress());
  $effect(() => ctl.loadWatchOptions());
  $effect(() => ctl.loadSeasons());
  $effect(() => ctl.loadEpisodes());
  $effect(() => ctl.loadStreams());
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
  {#if ctl.isTV && !ctl.selectedEpisode}
    <!-- Season chip strip -->
    <div class="flex-none border-b border-border">
      {#if ctl.loadingSeasons}
        <div class="p-4">
          <span class="animate-pulse text-base text-muted-foreground">{m.streams_loading_seasons()}</span>
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
          {#each ctl.seasons as s (s.season_number)}
            <button
              type="button"
              use:focusable={{ groupId: "streams-seasons" }}
              onclick={() => (ctl.selectedSeason = s.season_number)}
              class="shrink-0 rounded-xl px-4 py-2.5 text-sm font-medium transition-colors
                focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-accent focus-visible:ring-offset-1 focus-visible:ring-offset-background
                {ctl.selectedSeason === s.season_number
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
      {#if ctl.loadingEpisodes}
        <div class="flex items-center justify-center py-16">
          <span class="animate-pulse text-base text-muted-foreground">{m.streams_loading_episodes()}</span>
        </div>
      {:else}
        <div class="flex flex-col divide-y divide-border px-2 py-2">
          {#each ctl.episodes as ep (ep.episode_number)}
            <TvEpisodeCard
              {media}
              {ep}
              selectedSeason={ctl.selectedSeason}
              bind:selectedEpisode={ctl.selectedEpisode}
              progressMap={ctl.progressMap}
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
      {#if ctl.isTV && ctl.selectedEpisode}
        <!-- Native button — reached geometrically from streams-filters via Up -->
        <button
          type="button"
          onclick={() => ctl.clearSelectedEpisode()}
          class="flex items-center gap-2 rounded-xl border border-border bg-secondary/60 px-4 py-2.5 text-base font-medium transition-colors hover:bg-secondary focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-accent"
        >
          <ChevronLeft class="size-5" />
          {m.streams_back_episodes()}
        </button>

        <!-- Selected episode summary (display only — not focusable) -->
        <div class="flex items-start gap-4 rounded-xl border border-border bg-secondary/40 p-3">
          {#if ctl.selectedEpisode.still_path}
            <img
              src={ctl.selectedEpisode.still_path}
              alt={ctl.selectedEpisode.name}
              class="aspect-video w-28 shrink-0 rounded-lg object-cover"
            />
          {:else}
            <div class="aspect-video w-28 shrink-0 animate-pulse rounded-lg bg-secondary"></div>
          {/if}
          <div class="min-w-0 flex-1">
            <p class="text-sm text-muted-foreground">
              {m.common_season_short({ season: ctl.selectedSeason })} · {m.common_episode_short(
                { episode: ctl.selectedEpisode.episode_number },
              )}
            </p>
            <p class="text-base font-semibold leading-snug">{ctl.selectedEpisode.name}</p>
            {#if ctl.selectedSeason != null}
              {@const prog = epProgress(ctl.selectedSeason, ctl.selectedEpisode.episode_number, ctl.progressMap)}
              {#if prog}
                {@const pct = progressPct(prog)}
                {#if prog.completed}
                  <p class="mt-1.5 flex items-center gap-1 text-sm text-green-500">
                    <Check class="size-4" /> {m.media_watched()}
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
        <h3 class="text-xl font-semibold">{m.streams_available()}</h3>
        {#if ctl.movieProgress}
          {#if ctl.movieProgress.completed}
            <p class="flex items-center gap-1.5 text-sm text-green-500">
              <Check class="size-4" /> {m.media_watched()}
            </p>
          {:else if progressPct(ctl.movieProgress) > 1}
            <div class="space-y-1">
              <div class="h-1.5 w-full overflow-hidden rounded-full bg-secondary">
                <div
                  class="h-full rounded-full bg-accent transition-all"
                  style="width: {progressPct(ctl.movieProgress)}%"
                ></div>
              </div>
              <p class="text-sm text-muted-foreground">
                {formatPosition(ctl.movieProgress.position_seconds)} / {formatPosition(
                  ctl.movieProgress.duration_seconds,
                )}
              </p>
            </div>
          {/if}
        {/if}
      {/if}

      <!-- Quality + sort cycle buttons (hidden when already playing + not showing alternatives) -->
      {#if !ctl.alreadyPlayingThisSelection || ctl.showAlternatives}
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
            onclick={() => ctl.cycleQuality()}
            class="flex-1 rounded-xl bg-secondary px-4 py-3 text-base font-medium text-muted-foreground transition-colors hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-accent focus-visible:ring-offset-1 focus-visible:ring-offset-background"
          >
            {m.streams_sort_quality()}: {ctl.qualityFilter.toUpperCase()}
          </button>
          <button
            type="button"
            use:focusable={{ groupId: "streams-filters" }}
            onclick={() => ctl.cycleSort()}
            class="flex-1 rounded-xl bg-secondary px-4 py-3 text-base font-medium text-muted-foreground transition-colors hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-accent focus-visible:ring-offset-1 focus-visible:ring-offset-background"
          >
            {m.my_list_sort()}: {STREAM_SORT_MODES.find(
              (m) => m.value === ctl.sortMode,
            )?.label}
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
        {#if ctl.watchOptions.length > 0}
          <div class="mb-5">
            <p class="mb-2 text-sm font-medium uppercase tracking-wide text-muted-foreground">
              {m.streams_where_watch()}
            </p>
            <div class="flex flex-wrap gap-2">
              {#each ctl.watchOptions as opt (opt.providerId + opt.type)}
                <span
                  class="flex items-center gap-1.5 rounded-lg border border-border bg-secondary/40 px-3 py-1.5 text-sm"
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
                    <span class="capitalize text-muted-foreground"
                      >· {watchTypeLabel(opt.type)}</span
                    >
                  {/if}
                </span>
              {/each}
            </div>
          </div>
        {/if}

        <!-- Already playing banner -->
        {#if ctl.alreadyPlayingThisSelection}
          <div
            class="flex items-center justify-between gap-2 rounded-xl border border-accent/30 bg-accent/10 px-4 py-3 text-base text-accent"
            class:mb-4={ctl.showAlternatives}
          >
            <span class="flex items-center gap-2">
              <Play class="size-5 fill-current" />
              {m.streams_playing_current()}
            </span>
            <!-- Native button — reached geometrically within tv-detail free group -->
            <button
              type="button"
              onclick={() => (ctl.showAlternatives = !ctl.showAlternatives)}
              class="rounded-lg bg-accent/20 px-3 py-1.5 text-sm font-medium transition-colors hover:bg-accent/30 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-accent"
            >
              {ctl.showAlternatives
                ? m.streams_hide_alternatives()
                : m.streams_see_alternatives()}
            </button>
          </div>
        {/if}

        {#if !ctl.alreadyPlayingThisSelection || ctl.showAlternatives}
          {#if ctl.autoPicking && !ctl.autoPickCancelled && !ctl.alreadyPlayingThisSelection}
            <!-- Auto-picking state -->
            <div class="flex flex-col items-center justify-center gap-4 py-16">
              <Spinner class="size-10" />
              <span class="text-base text-muted-foreground">{m.streams_auto_selecting()}</span>
              <!-- Focusable cancel button — reached geometrically -->
              <button
                type="button"
                onclick={() => ctl.cancelAutoPick()}
                class="rounded-xl border border-border bg-secondary px-5 py-2.5 text-base font-medium transition-colors hover:bg-secondary/80 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-accent"
              >
                {m.streams_choose_manually()}
              </button>
            </div>

          {:else if ctl.loadingStreams}
            <div class="flex flex-col items-center justify-center gap-3 py-16">
              <Spinner class="size-10" />
              <span class="animate-pulse text-base text-muted-foreground">{m.streams_loading()}</span>
            </div>

          {:else if ctl.streams.length === 0}
            <div class="flex flex-col items-center justify-center gap-3 py-16">
              <Spinner class="size-10" />
              <span class="animate-pulse text-base text-muted-foreground">
                {m.streams_none_retrying()}
              </span>
            </div>

          {:else if ctl.filteredStreams.length === 0}
            <div class="flex items-center justify-center py-16">
              <span class="text-base text-muted-foreground">{m.streams_no_filter()}</span>
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
              {#each ctl.filteredStreams as item (item.key)}
                {@const stream = item.stream}
                {#if item.isHardDisabled}
                  <!-- Codec the device provably can't hardware-decode: the
                       outer row is a plain div (skipped by D-pad focus); the
                       inner "Play anyway" button stays focusable so the
                       override is still reachable by remote. -->
                  <div
                    aria-disabled="true"
                    class="flex w-full flex-col gap-2 rounded-xl border border-border/30 bg-secondary/20 p-4 text-left opacity-60"
                  >
                    <span class="flex items-center justify-between gap-2">
                      <span class="text-base font-semibold text-foreground">{stream.name}</span>
                      <span
                        class="shrink-0 rounded-lg bg-destructive/20 px-2 py-1 text-sm font-medium text-destructive"
                      >
                        {m.streams_unsupported()}
                      </span>
                    </span>

                    <span class="line-clamp-2 text-sm whitespace-pre-line text-muted-foreground">
                      {stream.title}
                    </span>

                    <span class="flex flex-wrap items-center gap-2 text-sm text-muted-foreground">
                      {#if $settings?.showStreamDetails ?? true}
                        {#if isTorrentStream(stream)}
                          <span class="rounded-lg bg-background/70 px-2 py-1">
                            👤 {item.seeders}
                          </span>
                        {/if}
                        <span class="rounded-lg bg-background/70 px-2 py-1">
                          {item.quality}
                        </span>
                      {/if}
                      <!-- Always shown — it's why the row is unsupported. -->
                      {#if codecLabel(item.meta)}
                        <span class="rounded-lg bg-background/70 px-2 py-1">
                          {codecLabel(item.meta)}
                        </span>
                      {/if}
                      {#if stream.addonName}
                        <span class="rounded-lg bg-background/70 px-2 py-1">
                          {stream.addonName}
                        </span>
                      {/if}
                      <button
                        type="button"
                        class="ml-auto rounded-lg border border-border px-3 py-1 underline transition-colors hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-accent focus-visible:ring-offset-1 focus-visible:ring-offset-background"
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
                  type="button"
                  class="flex w-full flex-col gap-2 rounded-xl border border-border/50 bg-secondary/50 p-4 text-left transition-colors hover:border-border hover:bg-secondary focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-accent focus-visible:ring-offset-1 focus-visible:ring-offset-background"
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
                  <!-- Name + play icon -->
                  <span class="flex items-center justify-between gap-2">
                    <span class="text-base font-semibold text-foreground">{stream.name}</span>
                    <Play class="size-4 shrink-0 text-foreground/60" />
                  </span>

                  <!-- Title / description -->
                  <span class="line-clamp-2 text-sm whitespace-pre-line text-muted-foreground">
                    {stream.title}
                  </span>

                  <!-- Badges: seeders, size, quality, codec, language, provider -->
                  <span class="flex flex-wrap gap-2 text-sm text-muted-foreground">
                    {#if $settings?.showStreamDetails ?? true}
                      {#if isTorrentStream(stream)}
                        <span class="rounded-lg bg-background/70 px-2 py-1">
                          👤 {item.seeders}
                        </span>
                      {/if}
                      {#if item.sizeBytes > 0}
                        <span class="rounded-lg bg-background/70 px-2 py-1">
                          💾 {item.sizeBytes / 1024 ** 3 >= 1
                            ? `${(item.sizeBytes / 1024 ** 3).toFixed(2)} GB`
                            : `${(item.sizeBytes / 1024 ** 2).toFixed(0)} MB`}
                        </span>
                      {/if}
                      <span class="rounded-lg bg-background/70 px-2 py-1">
                        {item.quality}
                      </span>
                      {#if codecLabel(item.meta)}
                        <span class="rounded-lg bg-background/70 px-2 py-1">
                          {codecLabel(item.meta)}
                        </span>
                      {/if}
                      <!-- The quality tier already says "4k dv"/"4k hdr" — only
                           badge DV/HDR when the tier doesn't carry it. -->
                      {#if item.meta.isDolbyVision && !item.quality?.includes("dv")}
                        <span class="rounded-lg bg-background/70 px-2 py-1">DV</span>
                      {:else if item.meta.isHDR && !item.quality?.includes("hdr")}
                        <span class="rounded-lg bg-background/70 px-2 py-1">HDR</span>
                      {/if}
                      {#if langLabel(item.meta)}
                        <span class="rounded-lg bg-background/70 px-2 py-1">
                          {langLabel(item.meta)}
                        </span>
                      {/if}
                      {#if stream.cached}
                        <span class="rounded-lg bg-background/70 px-2 py-1">
                          ⚡ {stream.debrid || m.streams_sort_cached()}
                        </span>
                      {:else if stream.debrid}
                        <span class="rounded-lg bg-background/70 px-2 py-1">
                          {stream.debrid}
                        </span>
                      {/if}
                    {/if}
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
                {/if}
              {/each}
            </div>
          {/if}
        {/if}
      </div>
    </div>
  {/if}
</div>
