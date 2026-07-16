<script lang="ts">
  import TopBar from "./components/TopBar.svelte";
  import { playback } from "$lib/playback.svelte";
  import { ModeWatcher } from "mode-watcher";
  import MediaExpandedModal from "./components/MediaExpandedModal.svelte";
  import PersonExpandedModal from "./components/modals/PersonExpandedModal.svelte";
  import ProviderExpandedModal from "./components/modals/ProviderExpandedModal.svelte";
  import type { Media } from "$lib/types/tmdb";
  import type { Person, Provider } from "$lib/api";
  import PlayerComponent from "./components/Player.svelte";
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
  import { libraryChanged } from "$lib/stores/library";
  import { Spinner } from "$lib/components/ui/spinner";
  import { X } from "lucide-svelte";

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

  const canGoBack = $derived(playback.playerMode === "full" || pageHistory.length > 0 || !!playback.quickPlayPending);

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

  // Focus-triggered auth sync bookkeeping (not $state — plain instance vars
  // read/written only from the onFocus handler below).
  let lastAuthSyncMs = 0;
  let lastLibraryGeneration: number | null = null;
  // Track the last push error surfaced this session to avoid spamming the user.
  let lastShownPushErr = "";

  // Load settings once on startup so all components have values immediately.
  onMount(() => {
    setMode("dark");
    // Wait for both settings and auth to resolve before revealing the app.
    Promise.all([settings.load(), auth.init().catch(console.error)]).then(
      () => {
        splashVisible = false;
        if (!$settings.onboardingDone) {
          showOnboarding = true;
        }
      },
    );

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
      const msg = typeof e.reason === "string" ? e.reason : (e.reason as { message?: string } | null)?.message;
      if (msg === "provider destroyed") { e.preventDefault(); return; }
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

    // Pull remote changes on window focus when signed in. Guarded so rapid
    // focus/blur cycling (alt-tabbing) doesn't trigger a sync — and downstream
    // refetch storm across every MediaCard + ContinueWatching — on every
    // single focus.
    const onFocus = () => {
      if (auth.isGuest) return;
      const now = Date.now();
      if (now - lastAuthSyncMs < 60_000) return;
      lastAuthSyncMs = now;
      api
        .authSync()
        .then((res) => {
          // Only bump when the library actually changed remotely. Older
          // backends / a noop build (503) omit library_generation entirely —
          // fall back to the old always-bump behavior for those.
          if (typeof res.library_generation === "number") {
            if (res.library_generation !== lastLibraryGeneration) {
              lastLibraryGeneration = res.library_generation;
              libraryChanged.update((n) => n + 1);
            }
          } else {
            libraryChanged.update((n) => n + 1);
          }
          // Pull merged settings (including onboardingDone) into the frontend store.
          settings.load().catch(() => {});
          // Surface push errors — only when the message is non-empty and
          // differs from what we already told the user this session (no spam).
          if (res.push_error && res.push_error !== lastShownPushErr) {
            lastShownPushErr = res.push_error;
            console.warn("Sync push error:", res.push_error);
            showSyncError("Sync issue: some data failed to upload");
          }
        })
        .catch(() => {});
    };
    window.addEventListener("focus", onFocus);

    return () => {
      window.removeEventListener("unhandledrejection", onRejection);
      window.removeEventListener("error", onError);
      document.removeEventListener("contextmenu", onContextMenu);
      window.removeEventListener("focus", onFocus);
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

  function selectMedia(media: Media): void {
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

  // Wire up shell callbacks the playback module needs: the start chime
  // (AudioContext lives here, not in the module) and the detail overlay
  // (selectedMedia lives here, not in the module).
  playback.init({ playStartSound, openMediaDetail: selectMedia });

  // Short synthesized "thud" played whenever a stream actually starts —
  // the same kind of confirmation chime Netflix plays on play. No audio
  // asset needed; it's just a quick pitch-dropping tone through Web Audio.
  let audioCtx: AudioContext | null = null;

  function getAudioCtx(): AudioContext {
    if (!audioCtx) {
      audioCtx = new AudioContext();
    }
    return audioCtx;
  }

  // Browsers create a fresh AudioContext in "suspended" state until a real
  // user gesture unlocks it. Auto-select (StreamsList's setTimeout-fired
  // pick) isn't itself a gesture, so without this, the very first sound —
  // whichever path triggers it first — could end up scheduled against a
  // context that hadn't actually started ticking yet and just stay silent.
  // Unlocking eagerly on the first real interaction anywhere in the app
  // sidesteps that entirely; by the time anything calls playStartSound,
  // the context is already running.
  onMount(() => {
    const unlock = () => {
      getAudioCtx()
        .resume()
        .catch(() => {});
      window.removeEventListener("pointerdown", unlock);
      window.removeEventListener("keydown", unlock);
    };
    window.addEventListener("pointerdown", unlock);
    window.addEventListener("keydown", unlock);
    return () => {
      window.removeEventListener("pointerdown", unlock);
      window.removeEventListener("keydown", unlock);
    };
  });

  async function playStartSound(): Promise<void> {
    try {
      const ctx = getAudioCtx();
      if (ctx.state === "suspended") {
        await ctx.resume();
      }

      const now = ctx.currentTime;
      const osc = ctx.createOscillator();
      const gain = ctx.createGain();

      osc.type = "sine";
      osc.frequency.setValueAtTime(180, now);
      osc.frequency.exponentialRampToValueAtTime(70, now + 0.15);

      gain.gain.setValueAtTime(0.0001, now);
      gain.gain.exponentialRampToValueAtTime(0.35, now + 0.01);
      gain.gain.exponentialRampToValueAtTime(0.0001, now + 0.22);

      osc.connect(gain);
      gain.connect(ctx.destination);

      osc.start(now);
      osc.stop(now + 0.25);
      // The destination retains connected nodes even after stop() — without
      // this, every episode start leaks an oscillator+gain pair in the graph.
      osc.addEventListener("ended", () => {
        osc.disconnect();
        gain.disconnect();
      });
    } catch (e) {
      console.error("playStartSound failed", e);
    }
  }

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
            <HomePage onSelectMedia={selectMedia} onWatch={(m, s, e) => playback.quickPlay(m, s, e)} visible={currentPage.type === "home"} onChangePage={changePage} />
          </div>
          <div class="h-full" class:hidden={currentPage.type !== "account"}>
            <MyAccountPage
              visible={currentPage.type === "account"}
              onSelectPerson={(p) => (selectedPerson = p)}
            />
          </div>
          <div class="h-full" class:hidden={currentPage.type !== "myList"}>
            <MyListPage onSelectMedia={selectMedia} onWatch={(m, s, e) => playback.quickPlay(m, s, e)} />
          </div>
          <div class="h-full" class:hidden={currentPage.type !== "explore"}>
            <ExplorePage onSelectMedia={selectMedia} onWatch={(m, s, e) => playback.quickPlay(m, s, e)} />
          </div>
          <div class="h-full" class:hidden={currentPage.type !== "catalog"}>
            <CatalogGridPage
              addonId={currentPage.type === "catalog" ? currentPage.addonId : ""}
              catalogType={currentPage.type === "catalog" ? currentPage.catalogType : ""}
              catalogId={currentPage.type === "catalog" ? currentPage.catalogId : ""}
              name={currentPage.type === "catalog" ? currentPage.name : ""}
              onSelectMedia={selectMedia}
              onWatch={(m, s, e) => playback.quickPlay(m, s, e)}
            />
          </div>
        </div>
      </div>

      {#if playback.quickPlayPending && !playback.playerSession}
        <!--
          Covers the gap between a "Watch" click and playerSession being set,
          i.e. before <PlayerComponent> even mounts (and before its own
          "Connecting to peers…"/"Buffering…" loading screen can show
          anything). Mirrors that loading screen's visual style — blurred
          poster backdrop, Spinner, status text — for a seamless handoff.
        -->
        <div
          class="absolute inset-0 z-30 flex flex-col items-center justify-center bg-black"
          transition:fade={{ duration: 150 }}
        >
          {#if playback.quickPlayPending.media.poster_path}
            <div
              class="absolute inset-0 scale-110 bg-cover bg-center"
              style="background-image: url('{playback.quickPlayPending.media
                .poster_path}'); filter: blur(5px); opacity: 0.35;"
            ></div>
            <div class="absolute inset-0 bg-black/65"></div>
            <img
              src={playback.quickPlayPending.media.poster_path}
              alt={playback.quickPlayPending.media.media_type === "tv"
                ? playback.quickPlayPending.media.name
                : playback.quickPlayPending.media.title}
              class="relative z-10 h-48 w-32 rounded-lg object-cover shadow-2xl"
            />
          {:else}
            <div class="absolute inset-0 bg-black/65"></div>
            <span
              class="relative z-10 px-8 text-center text-3xl font-bold text-white"
            >
              {playback.quickPlayPending.media.media_type === "tv"
                ? playback.quickPlayPending.media.name
                : playback.quickPlayPending.media.title}
            </span>
          {/if}
          <Spinner class="relative z-10 mt-6 size-10" />
          <p class="relative z-10 mt-4 text-sm text-white/50">
            {playback.quickPlayPending.message}
          </p>
          <button
            type="button"
            class="relative z-10 mt-6 flex items-center gap-2 rounded-full border border-white/20 px-4 py-2 text-sm text-white/60 transition hover:bg-white/10 hover:text-white"
            onclick={() => playback.cancelQuickPlay()}
            aria-label="Cancel"
          >
            <X class="size-4" />
            Cancel
          </button>
        </div>
      {/if}

      {#if playback.playerSession}
        <!--
          One single <Player> instance overlaying the page full-screen. The page
          underneath is only hidden (not unmounted), so its state/scroll survive;
          closing the player reveals it again.
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
            src={playback.playerSession.stream.infoHash ||
              (playback.playerSession.stream.headers
                ? api.playProxyUrl(playback.playerSession.stream.url)
                : playback.playerSession.stream.url)}
            media={playback.playerSession.media}
            externalSubtitles={playback.playerSession.subtitles}
            season={playback.playerSession.season}
            episode={playback.playerSession.episode}
            onPlaybackFailed={() => playback.handlePlaybackFailed()}
            onPlayNext={(s, e) => {
              const m = playback.playerSession?.media;
              if (m) playback.quickPlay(m, s, e);
            }}
            onPlayStream={(stream, s, e, name, candidates) => {
              const m = playback.playerSession?.media;
              if (m) playback.startPlayback(m, stream, s, e, name, candidates ?? [], 0);
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
