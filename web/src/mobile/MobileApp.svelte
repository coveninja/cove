<script lang="ts">
  import * as m from "$lib/paraglide/messages.js";
  import { playback } from "$lib/playback.svelte";
  import { ModeWatcher, setMode } from "mode-watcher";
  import MediaDetailSheet from "./components/MediaDetailSheet.svelte";
  import PersonExpandedModal from "../components/modals/PersonExpandedModal.svelte";
  import ProviderExpandedModal from "../components/modals/ProviderExpandedModal.svelte";
  import type { Media } from "$lib/types/tmdb";
  import type { Person, Provider } from "$lib/api";
  import MobilePlayer from "./components/player/MobilePlayer.svelte";
  import * as Tooltip from "$lib/components/ui/tooltip";
  import { Cog } from "lucide-svelte";

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
  import { startAutoSync, syncAtStartup } from "$lib/sync";
  import { Player } from "$lib/player/player.svelte";
  import { isAndroid, minimizeApp } from "$lib/platform";
  import { createPlaybackChime } from "$lib/playbackChime";

  // Wire api.ts to read the JWT directly from the auth store on every request.
  setTokenSource(() => auth.authToken);

  let splashVisible = $state(true);
  let showOnboarding = $state(false);

  // The single app-level media detail overlay — floats over whatever page is
  // beneath, Netflix-style. Cards request it via the "openMediaDetail" context.
  let selectedMedia: Media | null = $state(null);
  let openSelectedMediaStreams = $state(false);

  // Person / provider overlays opened from search results.
  let selectedPerson: Person | null = $state(null);
  let selectedProvider: Provider | null = $state(null);

  let currentPage = $state<Page>({ type: "home" });
  let pageHistory = $state<Page[]>([]);

  // ── Contexts ─────────────────────────────────────────────────────────────────
  // Mark this tree as mobile so MediaCard (and other components) can opt out
  // of hover / context-menu behavior that doesn't belong on touch screens.
  setContext("isMobile", true);

  function selectMedia(media: Media, showStreams = false): void {
    openSelectedMediaStreams = showStreams;
    selectedMedia = media;
  }

  setContext("openMediaDetail", selectMedia);
  setContext("watchMedia", (m: Media, s?: number, e?: number) =>
    playback.quickPlay(m, s, e),
  );

  const playbackChime = createPlaybackChime();
  playback.init({
    playStartSound: playbackChime.play,
    openMediaDetail: selectMedia,
  });
  onMount(playbackChime.unlockOnInteraction);

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

  // ── Auto-fullscreen while the player is open (Android) ──────────────────────
  // Entering the player puts the app into immersive fullscreen (system bars
  // hidden via MpvBridge.setFullscreen); leaving it restores them. Derived off
  // the session's presence only, so the effect fires on open/close transitions
  // — a manual fullscreen exit inside the player (toggle button or back key)
  // isn't re-forced by unrelated session updates.
  const playerActive = $derived(playback.playerSession !== null);
  $effect(() => {
    if (isAndroid()) Player.setFullscreen(playerActive);
  });

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

  // ── Bootstrap: auth + account pull, then reveal app ───────────────────────────
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

    return () => {
      window.removeEventListener("unhandledrejection", onRejection);
      window.removeEventListener("error", onError);
      document.removeEventListener("contextmenu", onContextMenu);
      stopAutoSync?.();
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
          class="h-full pb-[calc(3.5rem+var(--safe-bottom))] page-enter"
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
          class="h-full pb-[calc(3.5rem+var(--safe-bottom))] page-enter"
          class:hidden={currentPage.type !== "myList"}
        >
          <MobileMyListPage
            onSelectMedia={selectMedia}
            onWatch={(m, s, e) => playback.quickPlay(m, s, e)}
          />
        </div>

        <!-- Explore -->
        <div
          class="h-full pb-[calc(3.5rem+var(--safe-bottom))] page-enter"
          class:hidden={currentPage.type !== "explore"}
        >
          <MobileExplorePage
            onSelectMedia={selectMedia}
            onWatch={(m, s, e) => playback.quickPlay(m, s, e)}
          />
        </div>

        <!-- Search (mobile-specific wrapper around QueryPage) -->
        <div
          class="h-full pb-[calc(3.5rem+var(--safe-bottom))] page-enter"
          class:hidden={currentPage.type !== "query"}
        >
          <MobileSearchPage
            onSelectPerson={(p) => (selectedPerson = p)}
            onSelectProvider={(p) => (selectedProvider = p)}
          />
        </div>

        <!-- Settings -->
        <div
          class="h-full pb-[calc(3.5rem+var(--safe-bottom))] page-enter"
          class:hidden={currentPage.type !== "settings"}
        >
          <SettingsPage />
        </div>

        <!-- Account -->
        <div
          class="h-full pb-[calc(3.5rem+var(--safe-bottom))] page-enter"
          class:hidden={currentPage.type !== "account"}
        >
          <MyAccountPage
            visible={currentPage.type === "account"}
            onSelectPerson={(p) => (selectedPerson = p)}
          />
        </div>

        <!-- Catalog (opened from HomePage addon rows via onChangePage) -->
        <div
          class="h-full pb-[calc(3.5rem+var(--safe-bottom))] page-enter"
          class:hidden={currentPage.type !== "catalog"}
        >
          <MobileCatalogGridPage
            addonId={currentPage.type === "catalog" ? currentPage.addonId : ""}
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

      <!-- Mobile player overlay (M5) -->
      {#if playback.playerSession || (playback.quickPlayPending && !playback.playerSession)}
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
      aria-label={m.nav_settings()}
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

  /*
   * Settings tab bar: 6 whitespace-nowrap triggers can't fit portrait width
   * (~360 dp). Make the list itself scroll horizontally and let each trigger
   * size to its natural label width instead of flex-stretching.
   *
   * ScrollArea won't clip this because overflow-x: auto is on the list's own
   * box (w-full), so the inner scroll container stays within the layout width.
   */
  :global(.mobile-shell [data-slot="tabs-list"]) {
    overflow-x: auto;
    scrollbar-width: none; /* Firefox */
  }
  :global(.mobile-shell [data-slot="tabs-list"]::-webkit-scrollbar) {
    display: none; /* WebKit / Blink */
  }
  :global(.mobile-shell [data-slot="tabs-trigger"]) {
    flex: 0 0 auto; /* size to natural label width; don't compress or stretch */
  }

  /*
   * Page-enter animation
   * CSS animations restart automatically whenever display:none is removed, so
   * this re-triggers on every tab switch with no JS needed.
   *
   * @keyframes -global-mobile-page-enter: the "-global-" prefix tells the
   * Svelte compiler to emit the keyframes without a scope hash, so the name
   * resolves to "mobile-page-enter" in the compiled output and matches the
   * animation reference in the :global rule below.
   */
  :global(.mobile-shell .page-enter) {
    animation: mobile-page-enter 180ms ease-out;
  }
  @keyframes -global-mobile-page-enter {
    from {
      opacity: 0;
      transform: translateY(8px);
    }
  }

  /*
   * Shimmer skeleton utility
   * Named "animate-shimmer" (starts with "animate-") so the existing
   * app.css pause rule (.hidden [class*="animate-"]) pauses it on hidden pages.
   * However, app.css targets the element itself — not ::after — and
   * animation-play-state is not inherited, so we explicitly pause ::after too.
   */
  :global(.animate-shimmer) {
    position: relative;
    overflow: hidden;
    background-color: var(--muted);
  }
  :global(.animate-shimmer)::after {
    content: "";
    position: absolute;
    inset: 0;
    background: linear-gradient(
      90deg,
      transparent,
      oklch(1 0 0 / 6%),
      transparent
    );
    animation: mobile-shimmer 1.4s ease-in-out infinite;
    transform: translateX(-100%);
  }
  @keyframes -global-mobile-shimmer {
    100% {
      transform: translateX(100%);
    }
  }
  /* Pause ::after shimmer animation on hidden/invisible pages (mirrors app.css). */
  :global(.hidden .animate-shimmer)::after,
  :global(.invisible .animate-shimmer)::after {
    animation-play-state: paused;
  }
</style>
