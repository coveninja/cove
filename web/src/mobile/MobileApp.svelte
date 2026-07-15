<script lang="ts">
  import { playback } from "$lib/playback.svelte";
  import { ModeWatcher, setMode } from "mode-watcher";
  import MediaDetailSheet from "./components/MediaDetailSheet.svelte";
  import PersonExpandedModal from "../components/modals/PersonExpandedModal.svelte";
  import ProviderExpandedModal from "../components/modals/ProviderExpandedModal.svelte";
  import type { Media } from "$lib/types/tmdb";
  import type { Person, Provider } from "$lib/api";
  import MobilePlayer from "./components/MobilePlayer.svelte";
  import * as Tooltip from "$lib/components/ui/tooltip";
  import { Cog, X } from "lucide-svelte";

  import type { Page } from "$lib/types/types";
  import MobileHomePage from "./pages/MobileHomePage.svelte";
  import SettingsPage from "../components/SettingsPage.svelte";
  import MobileMyListPage from "./pages/MobileMyListPage.svelte";
  import MyAccountPage from "../components/MyAccountPage.svelte";
  import MobileExplorePage from "./pages/MobileExplorePage.svelte";
  import MobileCatalogGridPage from "./pages/MobileCatalogGridPage.svelte";
  import OnboardingPage from "../components/OnboardingPage.svelte";
  import SplashScreen from "../components/SplashScreen.svelte";
  import MobileSearchPage from "./pages/MobileSearchPage.svelte";
  import BottomNav from "./components/BottomNav.svelte";

  import { settings } from "$lib/stores/settings";
  import { onMount, setContext } from "svelte";
  import { scale, fade } from "svelte/transition";
  import { cubicOut } from "svelte/easing";
  import { api, setTokenSource } from "$lib/api";
  import { auth } from "$lib/stores/auth.svelte";
  import { libraryChanged } from "$lib/stores/library";
  import { Spinner } from "$lib/components/ui/spinner";
  import { Player } from "$lib/player/player.svelte";
  import { minimizeApp } from "$lib/platform";

  // Wire api.ts to read the JWT directly from the auth store on every request.
  setTokenSource(() => auth.authToken);

  let splashVisible = $state(true);
  let showOnboarding = $state(false);

  // The single app-level media detail overlay — floats over whatever page is
  // beneath, Netflix-style. Cards request it via the "openMediaDetail" context.
  let selectedMedia: Media | null = $state(null);

  // Person / provider overlays opened from search results.
  let selectedPerson: Person | null = $state(null);
  let selectedProvider: Provider | null = $state(null);

  let currentPage = $state<Page>({ type: "home" });
  let pageHistory = $state<Page[]>([]);

  // ── Contexts ─────────────────────────────────────────────────────────────────
  // Mark this tree as mobile so MediaCard (and other components) can opt out
  // of hover / context-menu behavior that doesn't belong on touch screens.
  setContext("isMobile", true);

  function selectMedia(media: Media): void {
    selectedMedia = media;
  }

  setContext("openMediaDetail", selectMedia);
  setContext("watchMedia", (m: Media, s?: number, e?: number) =>
    playback.quickPlay(m, s, e),
  );

  // ── Playback init ─────────────────────────────────────────────────────────────
  playback.init({ playStartSound, openMediaDetail: selectMedia });

  // ── AudioContext chime (same as App.svelte) ──────────────────────────────────
  let audioCtx: AudioContext | null = null;

  function getAudioCtx(): AudioContext {
    if (!audioCtx) audioCtx = new AudioContext();
    return audioCtx;
  }

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
      if (ctx.state === "suspended") await ctx.resume();

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
      osc.addEventListener("ended", () => {
        osc.disconnect();
        gain.disconnect();
      });
    } catch (e) {
      console.error("playStartSound failed", e);
    }
  }

  // ── cove-playing class (reveal mpv surface) ───────────────────────────────
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

  // ── Page navigation ───────────────────────────────────────────────────────────
  function changePage(page: Page): void {
    selectedMedia = null;
    if (playback.playerMode === "full") {
      playback.closePlayer();
    }
    pageHistory.push(currentPage);
    currentPage = page;
    if (pageHistory.length > 25) pageHistory.shift();
  }

  function goBack(): void {
    selectedMedia = null;
    if (playback.playerMode === "full") {
      playback.closePlayer();
      return;
    }
    const previousPage = pageHistory.pop();
    if (previousPage) {
      currentPage = previousPage;
    }
  }

  // ── MobilePlayer sheet-close hook for Escape priority ───────────────────────
  // MobilePlayer registers this via onRegisterCloseSheets; returns true if it
  // closed a sheet (caller should stop processing Escape further).
  let closePlayerSheets: () => boolean = () => false;

  // ── Centralized Escape / Android-back handler ────────────────────────────────
  // Android back button arrives as an Escape keydown on the document.
  // Priority order is strict (see ARCHITECTURE for rationale):
  //   1. Media detail open          → close overlay
  //   2. Player track sheet open    → close sheet
  //   3. Player fullscreen          → exit fullscreen
  //   4. Player session active      → close player
  //   5. History or not home        → go back (fall through to home if empty)
  //   6. Fully at home              → minimizeApp()
  function handleKeydown(e: KeyboardEvent): void {
    if (e.key !== "Escape") return;
    e.preventDefault();

    if (selectedMedia) {
      selectedMedia = null;
      return;
    }

    if (playback.quickPlayPending) {
      playback.cancelQuickPlay();
      return;
    }

    if (playback.playerSession && closePlayerSheets()) {
      return;
    }

    if (Player.isFullscreen) {
      Player.setFullscreen(false);
      return;
    }

    if (playback.playerSession) {
      playback.closePlayer();
      return;
    }

    if (pageHistory.length > 0) {
      goBack();
      return;
    }

    if (currentPage.type !== "home") {
      currentPage = { type: "home" };
      selectedMedia = null;
      return;
    }

    minimizeApp();
  }

  // ── Bootstrap: settings + auth, then reveal app ───────────────────────────────
  // Focus sync bookkeeping (not $state — plain instance vars, same as App.svelte).
  let lastAuthSyncMs = 0;
  let lastLibraryGeneration: number | null = null;
  // Track the last push error surfaced this session to avoid spamming the user.
  let lastShownPushErr = "";
  // Displayed when a push sync error is detected; auto-clears after 5 s.
  let syncErrorToast = $state<string | null>(null);
  let syncErrorTimer: ReturnType<typeof setTimeout> | undefined;
  function showSyncError(msg: string) {
    syncErrorToast = msg;
    clearTimeout(syncErrorTimer);
    syncErrorTimer = setTimeout(() => (syncErrorToast = null), 5000);
  }

  onMount(() => {
    setMode("dark");

    Promise.all([settings.load(), auth.init().catch(console.error)]).then(() => {
      splashVisible = false;
      if (!$settings.onboardingDone) {
        showOnboarding = true;
      }
    });

    // Suppress AbortErrors from the media player (vidstack / maverick).
    const isAbort = (v: unknown): boolean => {
      const r = v as { name?: string; message?: string } | null | undefined;
      return (
        r?.name === "AbortError" ||
        (typeof r?.message === "string" && /abort/i.test(r.message))
      );
    };
    const onRejection = (e: PromiseRejectionEvent) => {
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

    // Android's onResume dispatches a window focus event, so the same
    // focus-sync guard that works on desktop works here too.
    const onFocus = () => {
      if (auth.isGuest) return;
      const now = Date.now();
      if (now - lastAuthSyncMs < 60_000) return;
      lastAuthSyncMs = now;
      api
        .authSync()
        .then((res) => {
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

  // ── Derived for the player overlay ───────────────────────────────────────────
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
</script>

<svelte:window onkeydown={handleKeydown} />

<Tooltip.Provider>
  <!-- Media detail sheet (M4) -->
  {#if selectedMedia}
    {#key selectedMedia.id}
      <MediaDetailSheet
        media={selectedMedia}
        onwatch={(season, episode) => {
          const m = selectedMedia;
          if (m) playback.quickPlay(m, season, episode);
        }}
        onplaystream={(stream, season, episode, episodeName, candidates) => {
          const m = selectedMedia;
          if (m)
            playback.startPlayback(m, stream, season, episode, episodeName, candidates, 0);
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

  <!-- Main layout: pages fill screen, BottomNav pinned to bottom -->
  <!-- mobile-shell: scoped global overrides reduce desktop TopBar padding on mobile pages. -->
  <div class="mobile-shell flex h-screen flex-col overflow-hidden">
    <!-- isolate traps page-internal z-indexes so hero content (z-30) can't
         paint over the fixed MediaDetailSheet (z-20) or BottomNav (z-40)
         siblings that live outside this element. -->
    <main class="relative isolate min-h-0 flex-1 overflow-hidden">
      <!-- Pages are always mounted; hidden/shown with class:hidden so state
           and scroll position survive tab switching. Invisible (not just
           hidden) while the player is full-size so the opaque background
           doesn't block the transparent player from revealing mpv. -->
      <div
        class="h-full w-full bg-background"
        class:invisible={playback.playerMode === "full"}
      >
        <!-- Home -->
        <div
          class="h-full pb-[calc(3.5rem+var(--safe-bottom))]"
          class:hidden={currentPage.type !== "home"}
        >
          <MobileHomePage
            onSelectMedia={selectMedia}
            onWatch={(m, s, e) => playback.quickPlay(m, s, e)}
            visible={currentPage.type === "home"}
            onChangePage={changePage}
          />
        </div>

        <!-- My List -->
        <div
          class="h-full pb-[calc(3.5rem+var(--safe-bottom))]"
          class:hidden={currentPage.type !== "myList"}
        >
          <MobileMyListPage
            onSelectMedia={selectMedia}
            onWatch={(m, s, e) => playback.quickPlay(m, s, e)}
          />
        </div>

        <!-- Explore -->
        <div
          class="h-full pb-[calc(3.5rem+var(--safe-bottom))]"
          class:hidden={currentPage.type !== "explore"}
        >
          <MobileExplorePage
            onSelectMedia={selectMedia}
            onWatch={(m, s, e) => playback.quickPlay(m, s, e)}
          />
        </div>

        <!-- Search (mobile-specific wrapper around QueryPage) -->
        <div
          class="h-full pb-[calc(3.5rem+var(--safe-bottom))]"
          class:hidden={currentPage.type !== "query"}
        >
          <MobileSearchPage
            onSelectPerson={(p) => (selectedPerson = p)}
            onSelectProvider={(p) => (selectedProvider = p)}
          />
        </div>

        <!-- Settings -->
        <div
          class="h-full pb-[calc(3.5rem+var(--safe-bottom))]"
          class:hidden={currentPage.type !== "settings"}
        >
          <SettingsPage />
        </div>

        <!-- Account -->
        <div
          class="h-full pb-[calc(3.5rem+var(--safe-bottom))]"
          class:hidden={currentPage.type !== "account"}
        >
          <MyAccountPage
            visible={currentPage.type === "account"}
            onSelectPerson={(p) => (selectedPerson = p)}
          />
        </div>

        <!-- Catalog (opened from HomePage addon rows via onChangePage) -->
        <div
          class="h-full pb-[calc(3.5rem+var(--safe-bottom))]"
          class:hidden={currentPage.type !== "catalog"}
        >
          <MobileCatalogGridPage
            addonId={currentPage.type === "catalog" ? currentPage.addonId : ""}
            catalogType={currentPage.type === "catalog" ? currentPage.catalogType : ""}
            catalogId={currentPage.type === "catalog" ? currentPage.catalogId : ""}
            name={currentPage.type === "catalog" ? currentPage.name : ""}
            onSelectMedia={selectMedia}
            onWatch={(m, s, e) => playback.quickPlay(m, s, e)}
          />
        </div>
      </div>

      <!-- quickPlayPending overlay: covers the gap between a Watch tap and
           playerSession being set, before Player can show its own loading UI. -->
      {#if playback.quickPlayPending && !playback.playerSession}
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
            <span class="relative z-10 px-8 text-center text-3xl font-bold text-white">
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
            class="relative z-10 mt-6 flex min-h-[44px] items-center gap-2 rounded-full border border-white/20 px-5 py-3 text-base text-white/60 transition active:bg-white/10 active:text-white"
            onclick={() => playback.cancelQuickPlay()}
            aria-label="Cancel"
          >
            <X class="size-5" />
            Cancel
          </button>
        </div>
      {/if}

      <!-- Mobile player overlay (M5) -->
      {#if playback.playerSession}
        <div
          class="absolute inset-0 z-30 overflow-hidden"
          transition:scale={{
            duration: 280,
            start: 0.92,
            opacity: 0,
            easing: cubicOut,
          }}
        >
          <MobilePlayer
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
            onRegisterCloseSheets={(fn) => (closePlayerSheets = fn)}
          />
        </div>
      {/if}
    </main>
  </div>

  <!-- Bottom navigation — hidden while playback (or its loading overlay) is up:
       it sits at z-40, above the z-30 player, and would cover the controls. -->
  {#if !playback.playerSession && !playback.quickPlayPending}
    <BottomNav {currentPage} onSelectPage={changePage} />
  {/if}

  <!-- Small fixed gear button: visible only on the account page to surface
       settings without adding a second settings tab. -->
  {#if currentPage.type === "account" && !playback.playerSession && !playback.quickPlayPending}
    <button
      type="button"
      class="fixed right-4 z-50 flex size-10 items-center justify-center rounded-full bg-background/80 backdrop-blur-sm"
      style="top: calc(0.75rem + var(--safe-top));"
      onclick={() => changePage({ type: "settings" })}
      aria-label="Settings"
    >
      <Cog class="size-5" />
    </button>
  {/if}
</Tooltip.Provider>

<!-- Playback toast (shown while player is closed or loading) -->
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

<!-- Sync push error toast (auto-clears after 5 s, de-duped per session) -->
{#if syncErrorToast}
  <div
    class="pointer-events-none fixed inset-x-0 bottom-20 z-50 flex justify-center"
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

<style>
  /*
   * --safe-top / --safe-bottom: the single source of truth for safe-area
   * offsets in the mobile web shell.
   *
   * Default (browser preview / iOS WebView): resolved from the standard
   * env() values so the UI looks correct even without native injection.
   *
   * Override (Android WebView): Kotlin's ViewCompat insets listener injects
   * --cove-safe-top / --cove-safe-bottom as inline custom properties on <html>.
   * The attribute selector below wins whenever those properties are present,
   * replacing the env() fallback with the accurate native-reported values.
   */
  :global(:root) {
    --safe-top: env(safe-area-inset-top, 0px);
    --safe-bottom: env(safe-area-inset-bottom, 0px);
  }
  :global(:root[style*="--cove-safe-top"]) {
    --safe-top: var(--cove-safe-top);
    --safe-bottom: var(--cove-safe-bottom);
  }

  /*
   * On mobile there is no TopBar, so the desktop-standard pt-18 / mt-24 that
   * certain pages use to clear it creates dead space. Override them to
   * safe-area + a small breathing gap for descendants of .mobile-shell.
   *
   * These overrides are ONLY for the deliberately-reused desktop pages:
   *   – SettingsPage  uses .pt-18 for its outer container
   *   – MyAccountPage uses .mt-24 for its content top margin
   *
   * All native mobile pages (Home, MyList, Explore, Search, Catalog) own their
   * own safe-area padding and are unaffected by these selectors.
   */
  :global(.mobile-shell .pt-18) {
    padding-top: calc(var(--safe-top) + 0.75rem) !important;
  }
  :global(.mobile-shell .mt-24) {
    margin-top: calc(var(--safe-top) + 0.75rem) !important;
  }
</style>
