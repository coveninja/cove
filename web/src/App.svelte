<script lang="ts">
  import TopBar from "./components/TopBar.svelte";
  import { playback } from "$lib/playback.svelte";
  import { ModeWatcher } from "mode-watcher";
  import MediaExpandedModal from "./components/MediaExpandedModal.svelte";
  import PersonExpandedModal from "./components/modals/PersonExpandedModal.svelte";
  import ProviderExpandedModal from "./components/modals/ProviderExpandedModal.svelte";
  import type { Media } from "$lib/types/tmdb";
  import type { Person, Provider } from "$lib/api";
  import PlayerComponent from "./components/player/Player.svelte";
  import * as Tooltip from "$lib/components/ui/tooltip";
  import { setMode } from "mode-watcher";

  import type { Page } from "$lib/types/types";
  import QueryPage from "./components/QueryPage.svelte";
  import HomePage from "./components/HomePage.svelte";
  import SettingsPage from "./components/SettingsPage.svelte";
  import MyListPage from "./components/MyListPage.svelte";
  import { settings } from "$lib/stores/settings";
  import { onMount, setContext } from "svelte";
  import { scale, fade } from "svelte/transition";
  import { cubicOut } from "svelte/easing";
  import { api, type UpdateCheckResult, setTokenSource } from "$lib/api";
  import MyAccountPage from "./components/MyAccountPage.svelte";
  import ExplorePage from "./components/ExplorePage.svelte";
  import CatalogGridPage from "./components/CatalogGridPage.svelte";
  import UpdateModal from "./components/UpdateModal.svelte";
  import OnboardingPage from "./components/OnboardingPage.svelte";
  import SplashScreen from "./components/SplashScreen.svelte";
  import { auth } from "$lib/stores/auth.svelte";
  import { startAutoSync, syncAtStartup } from "$lib/sync";
  import { createPlaybackChime } from "$lib/playbackChime";

  // Wire api.ts to read the JWT directly from the auth store on every request,
  // avoiding any $effect timing gap between auth state changing and the token
  // being available for the next fetch.
  setTokenSource(() => auth.authToken);

  let query = $state("");
  let updateInfo = $state<UpdateCheckResult | null>(null);
  let splashVisible = $state(true);
  let showOnboarding = $state(false);

  // The media whose detail overlay (the single app-level MediaExpandedModal)
  // is currently open. Opening one no longer navigates to a page — the overlay
  // floats over whatever page is underneath, Netflix-style. Cards request it
  // via the "openMediaDetail" context provided below.
  let selectedMedia: Media | null = $state(null);
  let openSelectedMediaStreams = $state(false);

  // Person / provider detail overlays, opened from search result cards. Same
  // floating-overlay model as selectedMedia.
  let selectedPerson: Person | null = $state(null);
  let selectedProvider: Provider | null = $state(null);

  let loading = $state(false);
  // Displayed when a push sync error is detected; auto-clears after 5 s.
  let syncErrorToast = $state<string | null>(null);
  let syncErrorTimer: ReturnType<typeof setTimeout> | undefined;
  function showSyncError(msg: string) {
    syncErrorToast = msg;
    clearTimeout(syncErrorTimer);
    syncErrorTimer = setTimeout(() => (syncErrorToast = null), 5000);
  }

  let currentPage = $state<Page>({ type: "home" });
  let pageHistory = $state<Page[]>([]);

  const canGoBack = $derived(
    playback.playerMode === "full" ||
      pageHistory.length > 0 ||
      !!playback.quickPlayPending,
  );

  // Whether the active/floating stream belongs to the media page currently
  // on screen — used to stop the trailer from playing underneath it, and to
  // stop StreamsList from re-triggering auto-select for the same episode.
  const streamActiveForSelectedMedia = $derived(
    !!playback.playerSession &&
      !!selectedMedia &&
      playback.playerSession.media.id === selectedMedia.id &&
      playback.playerSession.media.media_type === selectedMedia.media_type,
  );

  const activePlaybackSeason = $derived(
    streamActiveForSelectedMedia ? playback.playerSession?.season : undefined,
  );
  const activePlaybackEpisode = $derived(
    streamActiveForSelectedMedia ? playback.playerSession?.episode : undefined,
  );

  // Drives the topbar's "now playing" title while the player is full-size —
  // replaces the logo so there's nothing left in the corner to collide with
  // the player's own controls.
  const fullscreenInfo = $derived(
    playback.playerMode === "full" && playback.playerSession
      ? {
          title:
            playback.playerSession.media.media_type === "tv"
              ? playback.playerSession.media.name
              : playback.playerSession.media.title,
          subtitle:
            playback.playerSession.media.media_type === "tv" &&
            playback.playerSession.season != null &&
            playback.playerSession.episode != null
              ? `S${playback.playerSession.season}E${playback.playerSession.episode}${
                  playback.playerSession.episodeName
                    ? ` - ${playback.playerSession.episodeName}`
                    : ""
                }`
              : undefined,
        }
      : null,
  );

  // Restore and refresh auth, pull the account, then decide whether onboarding
  // is needed from the merged settings.
  onMount(() => {
    setMode("dark");
    let stopAutoSync: (() => void) | null = null;
    void (async () => {
      await auth.init().catch(console.error);
      await syncAtStartup(showSyncError);
      splashVisible = false;
      if (!$settings.onboardingDone) {
        showOnboarding = true;
      }
      stopAutoSync = startAutoSync(showSyncError, { initialSync: false });
    })();

    // Non-blocking background update check. Failures are silently swallowed
    // since the user may be offline or on a dev build (which skips the check
    // server-side anyway).
    api
      .checkUpdate()
      .then((result) => {
        if (result.available) updateInfo = result;
      })
      .catch(() => {});

    // The media player (vidstack/maverick) aborts internal signals when its
    // element unmounts — closing the detail modal, re-keying it, or swapping it
    // for the person/provider overlay while a trailer is still loading. Those
    // surface as uncaught AbortErrors that are safe to ignore: an abort is an
    // intentional cancellation, not a failure. We match on name *and* message
    // (the dispose path can surface the abort without a clean name) and cover
    // both rejection and error events.
    const isAbort = (v: unknown): boolean => {
      const r = v as { name?: string; message?: string } | null | undefined;
      return (
        r?.name === "AbortError" ||
        (typeof r?.message === "string" && /abort/i.test(r.message))
      );
    };
    const onRejection = (e: PromiseRejectionEvent) => {
      // Vidstack rejects pending media requests with "provider destroyed"
      // when a player is torn down mid-flight. These are harmless; swallow
      // only this exact message so real rejections still surface.
      const msg =
        typeof e.reason === "string"
          ? e.reason
          : (e.reason as { message?: string } | null)?.message;
      if (msg === "provider destroyed") {
        e.preventDefault();
        return;
      }
      if (isAbort(e.reason)) e.preventDefault();
    };
    const onError = (e: ErrorEvent) => {
      if (isAbort(e.error) || /aborted without reason/i.test(e.message ?? "")) {
        e.preventDefault();
      }
    };
    window.addEventListener("unhandledrejection", onRejection);
    window.addEventListener("error", onError);

    const onContextMenu = (e: MouseEvent) => e.preventDefault();
    document.addEventListener("contextmenu", onContextMenu);

    return () => {
      window.removeEventListener("unhandledrejection", onRejection);
      window.removeEventListener("error", onError);
      document.removeEventListener("contextmenu", onContextMenu);
      stopAutoSync?.();
      clearTimeout(syncErrorTimer);
    };
  });

  function changePage(page: Page): void {
    // Navigating away dismisses the detail overlay.
    selectedMedia = null;

    // Navigating away from a full-screen player closes the stream — there's no
    // PiP to drop it into anymore.
    if (playback.playerMode === "full") {
      playback.closePlayer();
    }

    pageHistory.push(currentPage);
    currentPage = page;

    if (pageHistory.length > 25) {
      pageHistory.shift();
    }
  }

  function goBack(): void {
    // Navigating away dismisses the detail overlay.
    selectedMedia = null;

    // Loading overlay is showing — cancel the in-flight quickPlay.
    if (playback.quickPlayPending) {
      playback.cancelQuickPlay();
      return;
    }

    // While the player is shown full-size, "back" closes the stream and reveals
    // the page underneath (which was only hidden, never left).
    if (playback.playerMode === "full") {
      playback.closePlayer();
      return;
    }

    const previousPage = pageHistory.pop();
    if (previousPage) {
      currentPage = previousPage;
      if (previousPage.type === "query") {
        query = previousPage.query;
      }
    }
  }

  function selectMedia(media: Media, showStreams = false): void {
    openSelectedMediaStreams = showStreams;
    selectedMedia = media;
  }

  // Any MediaCard, anywhere in the tree (including inside HomePage's
  // recommendation rows), opens the single detail overlay through this —
  // no prop drilling, no per-page wiring.
  setContext("openMediaDetail", selectMedia);

  // Same idea for "play now" (auto-pick best stream), so the hero card's
  // Watch button and similar entry points can start playback directly.
  setContext("watchMedia", (m: Media, s?: number, e?: number) =>
    playback.quickPlay(m, s, e),
  );

  const playbackChime = createPlaybackChime();
  playback.init({
    playStartSound: playbackChime.play,
    openMediaDetail: selectMedia,
  });
  onMount(playbackChime.unlockOnInteraction);

  // mpv's surface always fills the whole window behind the web UI. Make the app
  // background transparent (revealing it) ONLY while the player is full-size;
  // otherwise keep the app opaque so the full-window video stays hidden behind
  // the UI after the stream closes.
  $effect(() => {
    document.documentElement.classList.toggle(
      "cove-playing",
      playback.playerMode === "full",
    );
  });

  // Dismiss onboarding reactively when the flag arrives via sync or login.
  // Only dismisses — onMount remains the sole entry point.
  $effect(() => {
    if ($settings.onboardingDone && showOnboarding) {
      showOnboarding = false;
    }
  });
</script>

<Tooltip.Provider>
  <!-- Any interaction with the top bar dismisses the open detail overlay.
       `contents` keeps this layout-neutral; the handler still fires via DOM
       event bubbling from the fixed TopBar inside it. -->
  <!-- svelte-ignore a11y_no_static_element_interactions -->
  <div class="contents" onpointerdown={() => (selectedMedia = null)}>
    <TopBar
      bind:query
      bind:loading
      onSelectPage={changePage}
      {canGoBack}
      onGoBack={goBack}
      {fullscreenInfo}
      onCloseStream={() => playback.closePlayer()}
      {currentPage}
    />
  </div>
  {#if selectedMedia}
    {#key selectedMedia.id}
      <MediaExpandedModal
        media={selectedMedia}
        onwatch={(season, episode) => {
          const m = selectedMedia;
          if (m) playback.quickPlay(m, season, episode);
        }}
        onplaystream={(stream, season, episode, episodeName, candidates) => {
          const m = selectedMedia;
          if (m)
            playback.startPlayback(
              m,
              stream,
              season,
              episode,
              episodeName,
              candidates,
              0,
            );
        }}
        onsimilar={(m) => selectMedia(m)}
        onclose={() => (selectedMedia = null)}
        streamActive={streamActiveForSelectedMedia}
        activeSeason={activePlaybackSeason}
        activeEpisode={activePlaybackEpisode}
        openStreamsInitially={openSelectedMediaStreams}
      />
    {/key}
  {/if}

  {#if selectedPerson}
    {#key selectedPerson.id}
      <PersonExpandedModal
        person={selectedPerson}
        onclose={() => (selectedPerson = null)}
        onselect={(m) => {
          selectedPerson = null;
          selectMedia(m);
        }}
      />
    {/key}
  {/if}

  {#if selectedProvider}
    {#key selectedProvider.provider_id}
      <ProviderExpandedModal
        provider={selectedProvider}
        onclose={() => (selectedProvider = null)}
        onselect={(m) => {
          selectedProvider = null;
          selectMedia(m);
        }}
      />
    {/key}
  {/if}

  <div class="flex h-screen flex-col overflow-hidden">
    <main class="relative min-h-0 flex-1 overflow-hidden">
      <!-- Hidden (not unmounted) while the player is full, so its opaque page
           background doesn't block the transparent player from revealing mpv,
           and page state/scroll survive opening and closing the player. -->
      <div
        class="h-full w-full bg-background"
        class:invisible={playback.playerMode === "full"}
      >
        <div
          class="h-full w-full bg-background"
          class:invisible={playback.playerMode === "full"}
        >
          <div class="h-full" class:hidden={currentPage.type !== "settings"}>
            <SettingsPage />
          </div>
          <div class="h-full" class:hidden={currentPage.type !== "query"}>
            <QueryPage
              bind:query
              bind:loading
              onSelectMedia={selectMedia}
              onSuggested={(name) => {
                query = name;
                changePage({ type: "query", query: name });
              }}
              onWatch={(m, s, e) => playback.quickPlay(m, s, e)}
              onSelectPerson={(p) => (selectedPerson = p)}
              onSelectProvider={(p) => (selectedProvider = p)}
            />
          </div>
          <div class="h-full" class:hidden={currentPage.type !== "home"}>
            <HomePage
              onSelectMedia={selectMedia}
              onWatch={(m, s, e) => playback.quickPlay(m, s, e)}
              visible={currentPage.type === "home"}
              onChangePage={changePage}
            />
          </div>
          <div class="h-full" class:hidden={currentPage.type !== "account"}>
            <MyAccountPage
              visible={currentPage.type === "account"}
              onSelectPerson={(p) => (selectedPerson = p)}
            />
          </div>
          <div class="h-full" class:hidden={currentPage.type !== "myList"}>
            <MyListPage
              onSelectMedia={selectMedia}
              onWatch={(m, s, e) => playback.quickPlay(m, s, e)}
            />
          </div>
          <div class="h-full" class:hidden={currentPage.type !== "explore"}>
            <ExplorePage
              onSelectMedia={selectMedia}
              onWatch={(m, s, e) => playback.quickPlay(m, s, e)}
            />
          </div>
          <div class="h-full" class:hidden={currentPage.type !== "catalog"}>
            <CatalogGridPage
              addonId={currentPage.type === "catalog"
                ? currentPage.addonId
                : ""}
              catalogType={currentPage.type === "catalog"
                ? currentPage.catalogType
                : ""}
              catalogId={currentPage.type === "catalog"
                ? currentPage.catalogId
                : ""}
              name={currentPage.type === "catalog" ? currentPage.name : ""}
              addonUrl={currentPage.type === "catalog"
                ? currentPage.addonUrl
                : undefined}
              onSelectMedia={selectMedia}
              onWatch={(m, s, e) => playback.quickPlay(m, s, e)}
            />
          </div>
        </div>
      </div>

      {#if playback.playerSession || (playback.quickPlayPending && !playback.playerSession)}
        <!--
          One single <Player> instance overlaying the page full-screen. The page
          underneath is only hidden (not unmounted), so its state/scroll survive;
          closing the player reveals it again. It mounts during stream discovery
          so the same loading screen owns the entire watch-to-playback handoff.
        -->
        <div
          class="absolute inset-0 z-30 overflow-hidden rounded-xl shadow-2xl"
          transition:scale={{
            duration: 280,
            start: 0.92,
            opacity: 0,
            easing: cubicOut,
          }}
        >
          <!-- Streams whose origin needs extra headers (Nuvio CDNs) must go
               through the backend proxy — mpv fetching the raw URL directly
               would drop the Referer/Origin the host requires. -->
          <PlayerComponent
            src={playback.playerSession
              ? playback.playerSession.stream.infoHash ||
                (playback.playerSession.stream.headers
                  ? api.playProxyUrl(playback.playerSession.stream.url)
                  : playback.playerSession.stream.url)
              : ""}
            media={playback.playerSession?.media ??
              playback.quickPlayPending?.media}
            pendingMessage={!playback.playerSession
              ? playback.quickPlayPending?.message
              : undefined}
            onCancelPending={() => playback.cancelQuickPlay()}
            externalSubtitles={playback.playerSession?.subtitles ?? []}
            season={playback.playerSession?.season}
            episode={playback.playerSession?.episode}
            fileIdx={playback.playerSession?.stream.fileIdx}
            automaticStartupRecovery={playback.playerSession
              ?.automaticStartupRecovery ?? false}
            onPlaybackFailed={() => playback.handlePlaybackFailed()}
            onPlayNext={(s, e) => {
              const m = playback.playerSession?.media;
              if (m) playback.quickPlay(m, s, e);
            }}
            onPlayStream={(stream, s, e, name, candidates) => {
              const m = playback.playerSession?.media;
              if (m)
                playback.startPlayback(m, stream, s, e, name, candidates, 0);
            }}
            onclose={() => playback.closePlayer()}
          />
        </div>
      {/if}
    </main>
  </div>
</Tooltip.Provider>

{#if updateInfo}
  <UpdateModal info={updateInfo} ondismiss={() => (updateInfo = null)} />
{/if}

{#if playback.playbackToast}
  <div
    class="pointer-events-none fixed inset-x-0 top-4 z-50 flex justify-center"
    transition:fade={{ duration: 150 }}
  >
    <div
      class="rounded-full bg-black/80 px-4 py-2 text-sm font-medium text-white shadow-lg backdrop-blur-sm"
    >
      {playback.playbackToast}
    </div>
  </div>
{/if}

{#if syncErrorToast}
  <div
    class="pointer-events-none fixed inset-x-0 bottom-6 z-50 flex justify-center"
    transition:fade={{ duration: 150 }}
  >
    <div
      class="rounded-full bg-destructive/90 px-4 py-2 text-sm font-medium text-destructive-foreground shadow-lg backdrop-blur-sm"
    >
      {syncErrorToast}
    </div>
  </div>
{/if}

{#if showOnboarding}
  <div
    transition:fade={{ duration: 200 }}
    class="fixed inset-0 z-50 flex items-center justify-center bg-background"
  >
    <OnboardingPage onclose={() => (showOnboarding = false)} />
  </div>
{/if}

{#if splashVisible}
  <SplashScreen />
{/if}

<ModeWatcher defaultMode="dark" />
