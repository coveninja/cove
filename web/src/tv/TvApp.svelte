<script lang="ts">
  import { playback } from "$lib/playback.svelte";
  import { ModeWatcher, setMode } from "mode-watcher";
  import type { Media } from "$lib/types/tmdb";
  import * as Tooltip from "$lib/components/ui/tooltip";

  import type { Page } from "$lib/types/types";
  import TvHomePage from "./pages/TvHomePage.svelte";
  import TvMyListPage from "./pages/TvMyListPage.svelte";
  import TvExplorePage from "./pages/TvExplorePage.svelte";
  import TvSearchPage from "./pages/TvSearchPage.svelte";
  import TvCatalogGridPage from "./pages/TvCatalogGridPage.svelte";
  import TvSideNav from "./components/TvSideNav.svelte";
  import TvDetailOverlay from "./components/TvDetailOverlay.svelte";
  import TvPlayer from "./components/player/TvPlayer.svelte";
  import TvSettingsPage from "./pages/TvSettingsPage.svelte";
  import TvAccountPage from "./pages/TvAccountPage.svelte";
  import TvOnboardingPage from "./pages/TvOnboardingPage.svelte";
  import SplashScreen from "../components/SplashScreen.svelte";

  import { settings } from "$lib/stores/settings";
  import { onMount, setContext, tick } from "svelte";
  import { scale, fade } from "svelte/transition";
  import { cubicOut } from "svelte/easing";
  import { api, setTokenSource } from "$lib/api";
  import { auth } from "$lib/stores/auth.svelte";
  import { startAutoSync } from "$lib/sync";
  import { Spinner } from "$lib/components/ui/spinner";
  import { Player } from "$lib/player/player.svelte";
  import { minimizeApp } from "$lib/platform";
  import { navigate, focusFirst, editableKeepsArrow } from "./focus/focusStore.svelte";
  import { X } from "lucide-svelte";

  // Wire api.ts to read the JWT directly from the auth store on every request.
  setTokenSource(() => auth.authToken);

  let splashVisible = $state(true);
  let showOnboarding = $state(false);

  // The single app-level selected media — detail overlay mounts in M5.
  let selectedMedia: Media | null = $state(null);

  let currentPage = $state<Page>({ type: "home" });
  let pageHistory = $state<Page[]>([]);

  // ── Contexts ─────────────────────────────────────────────────────────────────
  // Mark this tree as TV so components can opt into D-pad-specific behavior.
  // Do NOT set "isMobile" — TV and mobile shells are mutually exclusive.
  setContext("isTv", true);

  // Captured before selectedMedia is set so it's the element that triggered
  // the overlay open, not something inside the overlay that hasn't mounted yet.
  // Plain var (not $state) — purely imperative bookkeeping.
  let detailOpenerEl: HTMLElement | null = null;

  function selectMedia(media: Media): void {
    const el = document.activeElement;
    detailOpenerEl = el instanceof HTMLElement ? el : null;
    selectedMedia = media;
  }

  // Close the detail overlay and restore focus to the card that opened it
  // (or fall back to the first focusable element if the opener is gone).
  async function closeDetailOverlay(): Promise<void> {
    selectedMedia = null;
    await tick();
    const opener = detailOpenerEl;
    detailOpenerEl = null;
    if (opener?.isConnected) {
      opener.focus();
    } else {
      focusFirst();
    }
  }

  setContext("openMediaDetail", selectMedia);
  setContext("watchMedia", (m: Media, s?: number, e?: number) =>
    playback.quickPlay(m, s, e),
  );

  // ── Playback init ─────────────────────────────────────────────────────────────
  playback.init({ playStartSound, openMediaDetail: selectMedia });

  // ── AudioContext chime (same as MobileApp.svelte) ────────────────────────────
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

  // 10-foot scale note: an earlier pass bumped the root font-size to 18px so
  // every rem-based Tailwind size grew 1.125× — user feedback was that the UI
  // felt too big, so TV now keeps desktop's 16px density and relies on the
  // TV components' own larger explicit sizes (cards, hero, controls).

  // ── cove-playing class (reveals mpv surface below the transparent shell) ─────
  $effect(() => {
    document.documentElement.classList.toggle(
      "cove-playing",
      playback.playerMode === "full",
    );
  });

  // Dismiss onboarding and restore shell focus.
  // Used by both the onclose callback and the reactive effect below.
  async function dismissOnboarding(): Promise<void> {
    showOnboarding = false;
    await tick();
    focusFirst();
  }

  // Dismiss onboarding reactively when the flag arrives via sync or login.
  // Only dismisses — onMount remains the sole entry point.
  $effect(() => {
    if ($settings.onboardingDone && showOnboarding) {
      void dismissOnboarding();
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

  // ── quickPlayPending cancel button ref (autofocus when overlay appears) ──────
  let cancelBtn = $state<HTMLButtonElement | null>(null);

  $effect(() => {
    if (playback.quickPlayPending && cancelBtn) {
      cancelBtn.focus();
    }
  });

  // ── TvPlayer sheet-close hook for Escape priority ────────────────────────────
  // TvPlayer (M6) registers this via onRegisterCloseSheets; returns true if it
  // closed a sheet (caller stops processing Escape further).
  let closePlayerSheets: () => boolean = () => false;

  const ARROW_DIRS = {
    ArrowLeft: "left",
    ArrowRight: "right",
    ArrowUp: "up",
    ArrowDown: "down",
  } as const;

  // ── Editable guard (mirrors focusStore.svelte.ts — not exported from there) ──
  const EDITABLE_TYPES = new Set([
    "text",
    "search",
    "url",
    "email",
    "password",
    "number",
    "",
  ]);

  function isEditable(el: Element): boolean {
    if (el instanceof HTMLTextAreaElement) return true;
    if (el instanceof HTMLInputElement && EDITABLE_TYPES.has(el.type.toLowerCase()))
      return true;
    if ((el as HTMLElement).isContentEditable) return true;
    return false;
  }

  // ── Centralized keydown handler ───────────────────────────────────────────────
  // Arrow keys drive the spatial focus engine (from M2); Enter is left to native
  // activation.  Escape chain (TV — identical to MobileApp priority order):
  //   1. Editable element focused  → blur it
  //   2. Detail overlay open       → close overlay        (M5 mounts it)
  //   3. Player sheet open         → close sheet          (M6 registers fn)
  //   4. Player fullscreen         → exit fullscreen
  //   5. Player session active     → close player
  //   6. Page history non-empty    → go back
  //   7. Not on home page          → go home
  //   8. Fully at home             → minimizeApp()
  async function handleKeydown(e: KeyboardEvent): Promise<void> {
    const active = document.activeElement;

    if (e.key === "Escape") {
      // Step 1: blur an active editable (lets user dismiss keyboard on TV IME)
      if (active && isEditable(active)) {
        (active as HTMLElement).blur();
        e.preventDefault();
        return;
      }

      e.preventDefault();

      // Step 2: media detail open — close overlay and restore focus to opener
      if (selectedMedia) {
        await closeDetailOverlay();
        return;
      }

      // Step 2.5: quickPlay loading overlay — cancel the in-flight fetch
      if (playback.quickPlayPending) {
        playback.cancelQuickPlay();
        return;
      }

      // Step 3: player sheet open (registered by TvPlayer in M6)
      if (playback.playerSession && closePlayerSheets()) {
        return;
      }

      // Step 4: player fullscreen → exit fullscreen only
      if (Player.isFullscreen) {
        Player.setFullscreen(false);
        return;
      }

      // Step 5: player session active → close player
      if (playback.playerSession) {
        playback.closePlayer();
        return;
      }

      // Step 6: non-empty history → pop one level
      if (pageHistory.length > 0) {
        goBack();
        return;
      }

      // Step 7: not home → reset to home
      if (currentPage.type !== "home") {
        currentPage = { type: "home" };
        selectedMedia = null;
        return;
      }

      // Step 8: fully at home → minimize to launcher
      minimizeApp();
      return;
    }

    // Arrow keys → spatial nav. Editable elements keep the arrows that mean
    // something to them (editableKeepsArrow: horizontal caret movement, all
    // arrows in textareas, value stepping in number inputs); vertical arrows
    // on single-line text inputs escape the field — the only D-pad way out
    // of a search box without Back/Escape.
    // Enter: left to native activation — buttons activate, links follow, etc.
    const dir = ARROW_DIRS[e.key as keyof typeof ARROW_DIRS];
    if (!dir) return;
    if (active && editableKeepsArrow(active, dir)) return;
    e.preventDefault();
    navigate(dir);
  }

  // ── Bootstrap: settings + auth, then reveal app ───────────────────────────────
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
    Promise.all([settings.load(), auth.init().catch(console.error)]).then(
      async () => {
        splashVisible = false;
        if (!$settings.onboardingDone) {
          showOnboarding = true;
        }
        stopAutoSync = startAutoSync(showSyncError);
        // Focus the first navigable element after the shell is ready so the
        // remote's D-pad is immediately active.
        // Skip when onboarding is shown — TvOnboardingPage manages its own
        // initial focus and focusFirst() here would steal it to the side-nav.
        if (!showOnboarding) {
          await tick();
          focusFirst();
        }
      },
    );

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
      clearTimeout(syncErrorTimer);
    };
  });

  // ── Derived for the detail overlay (M5 uses these) ───────────────────────────
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
  <!--
    TV shell: flex row = collapsible left rail + overscan-padded main content.
    --tv-safe-inset is the overscan constant; main uses it as uniform padding
    (the rail handles its own block padding internally).
    .tv-shell also carries the 10-foot base font-size and the D-pad focus ring
    via the scoped :global rules in <style>.
  -->
  <div class="tv-shell flex h-screen overflow-hidden bg-[#0a0a0a] text-white" style="--tv-safe-inset: 32px;">
    <!-- Left rail: hidden while player or its loading overlay is up (mirrors
         BottomNav pattern in MobileApp). -->
    {#if !playback.playerSession && !playback.quickPlayPending}
      <!-- inert while onboarding or the detail overlay is up: those overlays
           paint over the shell, but occlusion alone doesn't remove covered
           elements from the focus engine's candidate set — without inert a
           focus leak strands the D-pad behind the overlay (invisible ring,
           Enter hits hidden nav). -->
      <div class="contents" inert={showOnboarding || !!selectedMedia}>
        <TvSideNav {currentPage} onNavigate={changePage} />
      </div>
    {/if}

    <!-- Main content: overscan-padded, isolated z-stack so page z-indexes
         can't escape above the overlays that live outside this element. -->
    <!-- inert only while the detail overlay is the TOPMOST surface: the
         player and quickPlay loading overlays mount INSIDE main, so it must
         un-inert the moment either of them exists (Cancel button / player
         controls need focus even while selectedMedia is still set). -->
    <main
      class="relative isolate min-h-0 flex-1 overflow-hidden"
      style="padding: var(--tv-safe-inset);"
      inert={showOnboarding ||
        (!!selectedMedia && !playback.playerSession && !playback.quickPlayPending)}
    >
      <!-- Pages: always mounted, shown/hidden via class:hidden so state and
           scroll positions survive tab switching.  invisible (not just hidden)
           while the player is full-size so the opaque background doesn't block
           the transparent shell from revealing mpv beneath. -->
      <div
        class="h-full w-full bg-background"
        class:invisible={playback.playerMode === "full"}
      >
        <!-- Home -->
        <div class="h-full" class:hidden={currentPage.type !== "home"}>
          <TvHomePage onChangePage={changePage} />
        </div>

        <!-- My List -->
        <div class="h-full" class:hidden={currentPage.type !== "myList"}>
          <TvMyListPage />
        </div>

        <!-- Explore -->
        <div class="h-full" class:hidden={currentPage.type !== "explore"}>
          <TvExplorePage />
        </div>

        <!-- Search -->
        <div class="h-full" class:hidden={currentPage.type !== "query"}>
          <TvSearchPage />
        </div>

        <!-- Settings -->
        <div class="h-full" class:hidden={currentPage.type !== "settings"}>
          <TvSettingsPage />
        </div>

        <!-- Account -->
        <div class="h-full" class:hidden={currentPage.type !== "account"}>
          <TvAccountPage
            visible={currentPage.type === "account"}
            onSelectPerson={() => {}}
          />
        </div>

        <!-- Catalog grid (opened from home rows via onChangePage; M4 fills data) -->
        <div class="h-full" class:hidden={currentPage.type !== "catalog"}>
          <TvCatalogGridPage
            addonId={currentPage.type === "catalog" ? currentPage.addonId : ""}
            catalogType={currentPage.type === "catalog"
              ? currentPage.catalogType
              : ""}
            catalogId={currentPage.type === "catalog" ? currentPage.catalogId : ""}
            name={currentPage.type === "catalog" ? currentPage.name : ""}
            addonUrl={currentPage.type === "catalog" ? currentPage.addonUrl : undefined}
            onSelectMedia={selectMedia}
            onWatch={(m, s, e) => playback.quickPlay(m, s, e)}
          />
        </div>
      </div>

      <!-- quickPlayPending overlay: covers the gap between a Watch press and
           playerSession being set, before the player can show its own loading UI.
           Poster image (or title text) + spinner — restyled slightly larger
           than mobile for 10-foot viewing. -->
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
              class="relative z-10 h-72 w-48 rounded-xl object-cover shadow-2xl"
            />
          {:else}
            <div class="absolute inset-0 bg-black/65"></div>
            <span class="relative z-10 px-8 text-center text-4xl font-bold text-white">
              {playback.quickPlayPending.media.media_type === "tv"
                ? playback.quickPlayPending.media.name
                : playback.quickPlayPending.media.title}
            </span>
          {/if}
          <Spinner class="relative z-10 mt-8 size-12" />
          <p class="relative z-10 mt-4 text-base text-white/50">
            {playback.quickPlayPending.message}
          </p>
          <button
            bind:this={cancelBtn}
            type="button"
            class="relative z-10 mt-8 flex items-center gap-2 rounded-full border border-white/25 px-6 py-3 text-base text-white/70 transition hover:bg-white/10 hover:text-white"
            onclick={() => playback.cancelQuickPlay()}
            aria-label="Cancel"
          >
            <X class="size-5" />
            Cancel
          </button>
        </div>
      {/if}

      <!-- TV Player (M6) -->
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
          <TvPlayer
            src={playback.playerSession.stream.infoHash ||
              (playback.playerSession.stream.headers
                ? api.playProxyUrl(playback.playerSession.stream.url)
                : playback.playerSession.stream.url)}
            media={playback.playerSession.media}
            externalSubtitles={playback.playerSession.subtitles}
            season={playback.playerSession.season}
            episode={playback.playerSession.episode}
            fileIdx={playback.playerSession.stream.fileIdx}
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

    <!-- Detail overlay (M5): full-screen fixed + trapFocus'd. Mounted INSIDE
         the .tv-shell div so it inherits the 10-foot base font-size and the
         scoped focus-ring CSS (fixed positioning is unaffected by placement). -->
    {#if selectedMedia}
      {#key selectedMedia.id}
        <TvDetailOverlay
          media={selectedMedia}
          streamActive={streamActiveForSelectedMedia}
          activeSeason={activePlaybackSeason}
          activeEpisode={activePlaybackEpisode}
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
          onclose={closeDetailOverlay}
        />
      {/key}
    {/if}

    <!-- Onboarding overlay: mounted INSIDE .tv-shell so focus-ring CSS applies.
         Fixed positioning is unaffected by DOM placement (same note as above). -->
    {#if showOnboarding}
      <div
        transition:fade={{ duration: 200 }}
        class="fixed inset-0 z-50 bg-background"
      >
        <TvOnboardingPage onclose={dismissOnboarding} />
      </div>
    {/if}
  </div>
</Tooltip.Provider>

<!-- Playback toast (visible while player is closed or loading) -->
{#if playback.playbackToast}
  <div
    class="pointer-events-none fixed inset-x-0 top-6 z-50 flex justify-center"
    transition:fade={{ duration: 150 }}
  >
    <div
      class="rounded-full bg-black/80 px-5 py-2.5 text-base font-medium text-white shadow-lg backdrop-blur-sm"
    >
      {playback.playbackToast}
    </div>
  </div>
{/if}

<!-- Sync push error toast (auto-clears after 5 s, de-duped per session) -->
{#if syncErrorToast}
  <div
    class="pointer-events-none fixed inset-x-0 bottom-24 z-50 flex justify-center"
    transition:fade={{ duration: 150 }}
  >
    <div
      class="rounded-full bg-destructive/90 px-5 py-2.5 text-base font-medium text-destructive-foreground shadow-lg backdrop-blur-sm"
    >
      {syncErrorToast}
    </div>
  </div>
{/if}

{#if splashVisible}
  <SplashScreen />
{/if}

<ModeWatcher defaultMode="dark" />

<style>
  /*
   * mpv reveal: on Android, video renders to a SurfaceView BEHIND the
   * transparent WebView — every layer between it and the player controls must
   * become see-through during playback. html.cove-playing is toggled by the
   * $effect above; without this rule the shell's opaque background paints
   * black over the video (audio plays, no picture).
   */
  :global(html.cove-playing) .tv-shell {
    background: transparent;
  }

  /* ── TV focus ring ───────────────────────────────────────────────────────── */
  /*
   * Visible accent-coloured ring on every D-pad-reachable element inside the
   * TV shell — both `[data-tv-focusable]` elements (via use:focusable) and
   * native focusables reached by the geometric fallback (buttons, inputs, …).
   *
   * Scoped under `.tv-shell` so desktop and mobile shells are unaffected.
   * The blanket :focus rule suppresses the browser default outline first, then
   * the more-specific selectors add the accent ring with a soft glow halo so
   * the focused element is legible from across the room.
   */
  :global(.tv-shell :focus) {
    outline: none;
  }
  :global(.tv-shell [data-tv-focusable]:focus),
  :global(.tv-shell button:focus),
  :global(.tv-shell a:focus),
  :global(.tv-shell input:focus),
  :global(.tv-shell select:focus),
  :global(.tv-shell textarea:focus),
  :global(.tv-shell [tabindex]:focus) {
    outline: 3px solid var(--accent, oklch(0.793 0.212 149.472));
  }

  /*
   * Scroll margin: ensure that when scrollIntoView() runs after a D-pad move
   * the focused element is never flush against the screen edge — 96 px of
   * breathing room on all sides.
   */
  :global(.tv-shell [data-tv-focusable]),
  :global(.tv-shell button),
  :global(.tv-shell a),
  :global(.tv-shell input),
  :global(.tv-shell select),
  :global(.tv-shell textarea),
  :global(.tv-shell [tabindex]) {
    scroll-margin: 96px;
  }
</style>
