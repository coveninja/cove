<script lang="ts">
  import { onMount, tick } from "svelte";
  import { CheckCircle, Check, Loader2, ArrowLeft, ArrowRight } from "lucide-svelte";
  import { Button } from "$lib/components/ui/button/index.js";
  import { Separator } from "$lib/components/ui/separator/index.js";
  import TvAuthPanel from "../components/TvAuthPanel.svelte";
  import StarRating from "../../components/StarRating.svelte";
  import { settings } from "$lib/stores/settings";
  import { auth } from "$lib/stores/auth.svelte";
  import { api } from "$lib/api";
  import type { Media } from "$lib/types/tmdb";
  import type { LibraryEntry } from "$lib/types/library";
  import CoveIcon from "../../assets/CoveIcon.svelte";
  import { SvelteSet } from "svelte/reactivity";
  import { focusGroup } from "../focus/actions";
  import { focusAfterKeyRelease, focusFirst } from "../focus/focusStore.svelte";

  let { onclose }: { onclose: () => void } = $props();

  type StepMeta = { id: string; title: string; skippable: boolean };
  const STEPS: StepMeta[] = [
    { id: "welcome",     title: "Welcome to Cove",         skippable: false },
    { id: "account",     title: "Your Account",             skippable: false },
    { id: "genres",      title: "Your Taste",               skippable: false },
    { id: "seen",        title: "What Have You Seen?",      skippable: true  },
    { id: "rate",        title: "Rate What You've Seen",    skippable: true  },
    { id: "preferences", title: "Playback Preferences",     skippable: true  },
    { id: "done",        title: "You're All Set!",          skippable: false },
  ];

  const LANGUAGES = [
    { value: "en", label: "English" },
    { value: "es", label: "Spanish" },
    { value: "fr", label: "French" },
    { value: "de", label: "German" },
    { value: "pt", label: "Portuguese" },
    { value: "it", label: "Italian" },
    { value: "ja", label: "Japanese" },
    { value: "ko", label: "Korean" },
    { value: "zh", label: "Chinese" },
    { value: "ar", label: "Arabic" },
    { value: "ru", label: "Russian" },
  ];

  // Audio-only: "original" plays whatever track matches the title's TMDB
  // original_language — see SettingsPage.svelte / Player.svelte for the same
  // concept.
  const AUDIO_LANGUAGES = [
    { value: "original", label: "Original language" },
    ...LANGUAGES,
  ];

  function langLabel(v: string): string {
    return LANGUAGES.find((l) => l.value === v)?.label ?? v;
  }

  function audioLangLabel(v: string): string {
    return AUDIO_LANGUAGES.find((l) => l.value === v)?.label ?? v;
  }

  let selectedMovieGenreIds = new SvelteSet<number>();
  let selectedTvGenreIds    = new SvelteSet<number>();

  // ── Navigation ────────────────────────────────────────────────────────────────
  let stepIndex = $state(0);
  const step = $derived(STEPS[stepIndex]);
  const isFirst = $derived(stepIndex === 0);
  const isLast = $derived(stepIndex === STEPS.length - 1);
  const canProceed = $derived(
    stepIndex === 2
      ? selectedMovieGenreIds.size > 0 || selectedTvGenreIds.size > 0
      : true,
  );

  // ── Account step ──────────────────────────────────────────────────────────────
  let authOpen = $state(false);

  // ── Genre step ────────────────────────────────────────────────────────────────
  let movieGenres = $state<{ id: number; name: string }[]>([]);
  let tvGenres    = $state<{ id: number; name: string }[]>([]);
  let loadingGenres = $state(false);

  function toggleMovieGenre(id: number): void {
    if (selectedMovieGenreIds.has(id)) selectedMovieGenreIds.delete(id);
    else selectedMovieGenreIds.add(id);
  }
  function toggleTvGenre(id: number): void {
    if (selectedTvGenreIds.has(id)) selectedTvGenreIds.delete(id);
    else selectedTvGenreIds.add(id);
  }

  // ── Seen step ─────────────────────────────────────────────────────────────────
  let browseMedia   = $state<Media[]>([]);
  let mediaQuery    = $state("");
  let searchResults = $state<Media[]>([]);
  let loadingMedia  = $state(false);
  let seenMedia     = $state<Media[]>([]);

  const seenIds = $derived(new Set(seenMedia.map((m) => `${m.media_type}-${m.id}`)));
  const displayMedia = $derived(mediaQuery.trim() ? searchResults : browseMedia);

  function toggleSeenMedia(m: Media): void {
    const key = `${m.media_type}-${m.id}`;
    if (seenIds.has(key)) {
      seenMedia = seenMedia.filter((x) => `${x.media_type}-${x.id}` !== key);
    } else {
      seenMedia = [...seenMedia, m];
    }
  }

  let searchTimer: ReturnType<typeof setTimeout> | null = null;
  function onMediaQueryChange(q: string): void {
    mediaQuery = q;
    if (searchTimer) clearTimeout(searchTimer);
    if (!q.trim()) { searchResults = []; return; }
    searchTimer = setTimeout(async () => {
      searchResults = await api.search(q).catch(() => []);
    }, 350);
  }

  async function loadBrowseMedia(): Promise<void> {
    loadingMedia = true;
    try {
      const movieIds = [...selectedMovieGenreIds];
      const tvIds    = [...selectedTvGenreIds];
      if (movieIds.length === 0 && tvIds.length === 0) {
        const [movies, tv] = await Promise.all([
          api.discover("movie", { limit: 15 }),
          api.discover("tv",    { limit: 15 }),
        ]);
        browseMedia = [...movies, ...tv];
      } else {
        const results = await Promise.all([
          ...movieIds.map((id) => api.discoverByGenre("movie", id, { limit: 12 })),
          ...tvIds.map((id)    => api.discoverByGenre("tv",    id, { limit: 12 })),
        ]);
        const seen = new SvelteSet<string>();
        browseMedia = results.flat().filter((m) => {
          const k = `${m.media_type}-${m.id}`;
          if (seen.has(k)) return false;
          seen.add(k);
          return true;
        });
      }
    } catch {
      browseMedia = [];
    }
    loadingMedia = false;
  }

  // ── Rate step ─────────────────────────────────────────────────────────────────
  let seenEntries      = $state<(LibraryEntry | null)[]>([]);
  let preparingEntries = $state(false);

  // ── Preferences step ─────────────────────────────────────────────────────────
  let subtitleLang     = $state($settings?.defaultSubtitleLang ?? "en");
  let audioLang        = $state($settings?.defaultAudioLang    ?? "en");
  let autoPlay         = $state($settings?.autoPlay            ?? false);
  let rememberPosition = $state($settings?.rememberPosition    ?? true);
  let autoSkipIntro    = $state($settings?.autoSkipIntro       ?? false);
  let autoSkipRecap    = $state($settings?.autoSkipRecap       ?? false);
  let autoSkipCredits  = $state($settings?.autoSkipCredits     ?? false);
  let autoSkipPreview  = $state($settings?.autoSkipPreview     ?? false);

  function cycleSubtitleLang(): void {
    const idx = LANGUAGES.findIndex((l) => l.value === subtitleLang);
    subtitleLang = LANGUAGES[(idx + 1) % LANGUAGES.length].value;
  }

  function cycleAudioLang(): void {
    const idx = AUDIO_LANGUAGES.findIndex((l) => l.value === audioLang);
    audioLang = AUDIO_LANGUAGES[(idx + 1) % AUDIO_LANGUAGES.length].value;
  }

  // ── Navigation handlers ───────────────────────────────────────────────────────
  async function next(): Promise<void> {
    if (stepIndex === 2) {
      await loadBrowseMedia();
    }
    if (stepIndex === 3) {
      if (seenMedia.length === 0) {
        stepIndex = 5;
        return;
      }
      preparingEntries = true;
      seenEntries = await Promise.all(
        seenMedia.map((m) =>
          api
            .libraryUpsert({
              tmdb_id: m.id,
              media_type: m.media_type,
              title: m.media_type === "movie" ? m.title : m.name,
              poster_path: m.poster_path,
              vote_average: m.vote_average,
              status: "finished",
            })
            .catch(() => null),
        ),
      );
      preparingEntries = false;
    }
    if (stepIndex === 5) {
      settings.save({
        defaultSubtitleLang: subtitleLang,
        defaultAudioLang: audioLang,
        autoPlay,
        rememberPosition,
        autoSkipIntro,
        autoSkipRecap,
        autoSkipCredits,
        autoSkipPreview,
      });
    }
    if (isLast) {
      await settings.save({ onboardingDone: true });
      onclose();
    } else {
      stepIndex += 1;
    }
  }

  function back(): void {
    if (stepIndex === 5 && seenMedia.length === 0) {
      // Skipped rating step on the way in — go back to seen step
      stepIndex = 3;
    } else {
      stepIndex -= 1;
    }
  }

  function skip(): void {
    // Skipping "Seen" with nothing selected also skips "Rate" since it'd be empty
    if (stepIndex === 3 && seenMedia.length === 0) {
      stepIndex = 5;
    } else {
      stepIndex += 1;
    }
  }

  let nextLoading = $state(false);
  async function handleNext(): Promise<void> {
    // Re-entry guard instead of disabling the button: disabling a focused
    // button drops focus to <body>, and the async step transitions
    // (loadBrowseMedia, library upserts) leave it there for seconds — an
    // arrow press in that window would strand the D-pad behind the overlay.
    if (nextLoading) return;
    nextLoading = true;
    await next();
    nextLoading = false;
  }

  // ── Focus management ──────────────────────────────────────────────────────────
  // Root element of the focus-group — used as the scope for focusFirst() so
  // rescue focus always lands inside the overlay, never behind it.
  let rootEl = $state<HTMLElement | null>(null);
  // Wrapper div around the primary Next/action button — we querySelector into it
  // to get the native <button> element, matching the TvDetailOverlay pattern.
  let nextBtnWrap = $state<HTMLElement | null>(null);
  // Wrapper for the sign-in button on the account step.
  let signInBtnWrap = $state<HTMLElement | null>(null);
  // Wrapper div around the inline TvAuthPanel — used for scoped focus rescue
  // and for focusAfterKeyRelease when the panel opens.
  let authWrap = $state<HTMLElement | null>(null);
  // Bound instance of TvAuthPanel for escapeBack() calls.
  let authPanel = $state<{ escapeBack(): boolean } | null>(null);

  function getNextBtn(): HTMLButtonElement | null {
    const btn = nextBtnWrap?.querySelector("button") as HTMLButtonElement | null;
    return btn;
  }

  // Rescue focus into the overlay: when the auth panel is open, prefer its
  // container so Escape-blur of an auth input doesn't yank focus to the nav
  // bar; otherwise prefer the Next button if enabled, then focusFirst(rootEl).
  function rescueFocus(): void {
    if (authOpen && authWrap) {
      focusFirst(authWrap);
      return;
    }
    const btn = getNextBtn();
    if (btn && !btn.disabled) {
      btn.focus();
    } else if (rootEl) {
      focusFirst(rootEl);
    }
  }

  // After each step change (and when a Next transition starts/ends), rescue
  // focus if it unmounted out from under us (e.g. Next was focused then
  // became disabled on the genres step) or ended up outside the overlay —
  // focus resting outside rootEl is always wrong while onboarding is mounted.
  $effect(() => {
    // Reactive dependencies: stepIndex and nextLoading.
    const _step = stepIndex;
    const _loading = nextLoading;
    void tick().then(() => {
      const active = document.activeElement;
      if (
        !active ||
        !active.isConnected ||
        active === document.body ||
        !rootEl?.contains(active)
      ) {
        rescueFocus();
      }
    });
  });

  // When the auth dialog opens/closes, manage focus appropriately.
  // cancelAuthFocus: cancel fn for the focusAfterKeyRelease targeting the dialog.
  let cancelAuthFocus: (() => void) | undefined;
  let authWasOpen = false;
  $effect(() => {
    if (authOpen) {
      authWasOpen = true;
      // Focus the first interactive element in the dialog after key release,
      // so the Enter press that opened the dialog can't immediately activate it.
      cancelAuthFocus = focusAfterKeyRelease(
        () => authWrap?.querySelector<HTMLElement>("input, button") ?? null,
      );
    } else {
      // Cancel any pending dialog-focus attempt if the dialog closes first.
      cancelAuthFocus?.();
      cancelAuthFocus = undefined;
      if (authWasOpen) {
        authWasOpen = false;
        // Refocus: sign-in button if still mounted, else Next button.
        void tick().then(() => {
          const signInBtn = signInBtnWrap?.querySelector("button") as HTMLElement | null;
          if (signInBtn?.isConnected) {
            signInBtn.focus();
          } else {
            getNextBtn()?.focus();
          }
        });
      }
    }
  });

  // ── Escape / Back intercept ───────────────────────────────────────────────────
  // Capture phase so we beat TvApp's bubble-phase handler on every Escape press.
  // Arrow keys are NOT touched — TvApp's navigate() handles those.
  const EDITABLE_TYPES = new Set(["text", "search", "url", "email", "password", "number", ""]);
  function isEditable(el: Element): boolean {
    if (el instanceof HTMLTextAreaElement) return true;
    if (el instanceof HTMLInputElement && EDITABLE_TYPES.has(el.type.toLowerCase())) return true;
    if ((el as HTMLElement).isContentEditable) return true;
    return false;
  }

  function handleEscape(e: KeyboardEvent): void {
    if (e.key !== "Escape") return;
    e.preventDefault();
    e.stopPropagation();

    const active = document.activeElement;
    // 1. Blur editable element (e.g. dismiss soft keyboard / IME) and
    //    immediately rescue focus back inside the overlay so activeElement
    //    never rests on body (which would let the next arrow press escape the trap).
    if (active && isEditable(active)) {
      (active as HTMLElement).blur();
      rescueFocus();
      return;
    }
    // 2. Step back through auth sub-views; only close the panel from "choose".
    if (authOpen) {
      if (!authPanel?.escapeBack()) {
        authOpen = false;
      }
      return;
    }
    // 3. Go back a step (unless on the first step — can't exit via Escape)
    if (!isFirst) {
      back();
    }
    // On first step: do nothing — app stays in onboarding
  }

  // ── Init ──────────────────────────────────────────────────────────────────────
  onMount(() => {
    // Initial focus: wait for key release so the same Enter that opened
    // onboarding (if any) doesn't immediately activate the Next button.
    // Defer past current tick so the DOM is fully mounted first.
    let cancelFocus: (() => void) | undefined;
    void tick().then(() => {
      cancelFocus = focusAfterKeyRelease(getNextBtn);
    });

    // Load genres; keep onMount synchronous so we can return the cleanup fn.
    loadingGenres = true;
    void Promise.all([
      api.genreList("movie").catch(() => [] as { id: number; name: string }[]),
      api.genreList("tv").catch(() => [] as { id: number; name: string }[]),
    ]).then(([movies, tv]) => {
      movieGenres = movies;
      tvGenres = tv;
      loadingGenres = false;
    });

    return () => {
      cancelFocus?.();
      // Also cancel any pending auth-dialog focus attempt.
      cancelAuthFocus?.();
      cancelAuthFocus = undefined;
    };
  });
</script>

<!--
  Intercept Escape at the capture phase so TvApp's bubble-phase handler never
  sees it while onboarding is mounted.
-->
<svelte:window onkeydowncapture={handleEscape} />

<!--
  Full-screen TV onboarding overlay.
  use:focusGroup with free policy + trapFocus keeps D-pad inside the overlay.
  Mounted inside .tv-shell so it inherits the TV focus-ring CSS.
-->
<div
  bind:this={rootEl}
  class="flex h-full w-full flex-col bg-background"
  use:focusGroup={{ id: "tv-onboarding", policy: { type: "free" }, trapFocus: true }}
  role="dialog"
  aria-modal="true"
  aria-label="Onboarding"
>
  <!-- Progress pills -->
  <div class="flex shrink-0 items-center justify-center gap-2.5 px-16 pt-10">
    {#each STEPS as _, i}
      <div
        class="h-2 rounded-full transition-all duration-300 {i === stepIndex
          ? 'w-8 bg-accent'
          : i < stepIndex
            ? 'w-4 bg-accent/40'
            : 'w-4 bg-muted'}"
      ></div>
    {/each}
  </div>

  <!-- Step content -->
  <div class="flex min-h-0 flex-1 flex-col px-16 py-8">

    {#if stepIndex === 0}
      <!-- Welcome -->
      <div class="flex flex-1 flex-col items-center justify-center gap-6 text-center">
        <div class="flex items-center justify-center rounded-2xl">
          <CoveIcon size={120} />
        </div>
        <h1 class="text-2xl font-bold tracking-tight">Welcome to Cove</h1>
        <p class="max-w-lg text-base text-muted-foreground">
          Your personal media streaming hub. Let's get you set up in a few quick steps.
        </p>
      </div>

    {:else if stepIndex === 1}
      <!-- Account -->
      <h2 class="mb-6 text-2xl font-semibold">{step.title}</h2>
      {#if auth.isGuest}
        {#if authOpen}
          <!-- Inline auth panel — no fixed overlay, D-pad stays inside the trap -->
          <div bind:this={authWrap} class="flex justify-center">
            <TvAuthPanel bind:this={authPanel} ondone={() => (authOpen = false)} />
          </div>
        {:else}
          <p class="text-base text-muted-foreground">
            You're browsing as a guest. You can optionally sign in to sync your library and preferences across devices.
          </p>
          <div bind:this={signInBtnWrap} class="mt-6">
            <Button onclick={() => (authOpen = true)} class="h-12 w-full px-8 text-lg">
              Sign in / Create account
            </Button>
          </div>
          <p class="mt-4 text-center text-base text-muted-foreground">
            You can always sign in later from the account menu.
          </p>
        {/if}
      {:else}
        <div class="flex items-center gap-4 rounded-xl border border-border p-6">
          <div
            class="flex size-14 shrink-0 items-center justify-center rounded-full bg-accent text-xl font-semibold text-accent-foreground"
          >
            {auth.activeProfile?.name?.charAt(0).toUpperCase() ?? "?"}
          </div>
          <div class="min-w-0 flex-1">
            <p class="truncate text-lg font-medium">{auth.activeProfile?.name ?? "Profile"}</p>
            <p class="truncate text-base text-muted-foreground">{auth.session?.email}</p>
          </div>
          <CheckCircle class="size-8 shrink-0 text-green-500" />
        </div>
        <p class="mt-4 text-base text-muted-foreground">
          Your account is connected. Your library syncs automatically.
        </p>
      {/if}

    {:else if stepIndex === 2}
      <!-- Genres -->
      <h2 class="mb-2 text-2xl font-semibold">{step.title}</h2>
      <p class="mb-6 text-base text-muted-foreground">Pick genres you enjoy watching.</p>
      <div class="min-h-0 flex-1 overflow-y-auto">
        {#if loadingGenres}
          <div class="flex items-center justify-center py-12">
            <Loader2 class="size-8 animate-spin text-muted-foreground" />
          </div>
        {:else}
          {#if movieGenres.length > 0}
            <p class="mb-3 text-sm font-medium uppercase tracking-wider text-muted-foreground">Movies</p>
            <div class="mb-6 p-4 flex flex-wrap gap-3">
              {#each movieGenres as g (g.id)}
                <button
                  onclick={() => toggleMovieGenre(g.id)}
                  class="rounded-full border px-5 py-2.5 text-lg transition-colors
                    {selectedMovieGenreIds.has(g.id)
                      ? 'border-accent bg-accent text-accent-foreground'
                      : 'border-border hover:border-accent/60 hover:text-foreground'}"
                >
                  {g.name}
                </button>
              {/each}
            </div>
          {/if}
          {#if tvGenres.length > 0}
            <p class="mb-3 text-sm font-medium uppercase tracking-wider text-muted-foreground">TV Shows</p>
            <div class="flex flex-wrap gap-3">
              {#each tvGenres as g (g.id)}
                <button
                  onclick={() => toggleTvGenre(g.id)}
                  class="rounded-full border px-5 py-2.5 text-lg transition-colors
                    {selectedTvGenreIds.has(g.id)
                      ? 'border-accent bg-accent text-accent-foreground'
                      : 'border-border hover:border-accent/60 hover:text-foreground'}"
                >
                  {g.name}
                </button>
              {/each}
            </div>
          {/if}
        {/if}
      </div>

    {:else if stepIndex === 3}
      <!-- Seen Before -->
      <div class="mb-2 flex shrink-0 items-center justify-between">
        <h2 class="text-2xl font-semibold">{step.title}</h2>
        {#if seenMedia.length > 0}
          <span class="rounded-full bg-accent/20 px-3 py-1 text-base font-medium text-accent">
            {seenMedia.length} selected
          </span>
        {/if}
      </div>
      <p class="mb-5 shrink-0 text-base text-muted-foreground">Select movies and shows you've already watched.</p>
      <input
        type="search"
        placeholder="Search for a movie or show..."
        value={mediaQuery}
        oninput={(e) => onMediaQueryChange(e.currentTarget.value)}
        class="mb-4 h-12 shrink-0 rounded-lg border border-border bg-input px-4 text-lg text-foreground placeholder:text-muted-foreground focus:border-accent focus:outline-none"
      />
      <div class="min-h-0 flex-1 overflow-y-auto">
        {#if loadingMedia}
          <div class="flex items-center justify-center py-12">
            <Loader2 class="size-8 animate-spin text-muted-foreground" />
          </div>
        {:else if displayMedia.length === 0}
          <p class="py-8 text-center text-lg text-muted-foreground">
            {mediaQuery.trim() ? "No results found." : "No media to show — try selecting some genres first."}
          </p>
        {:else}
          <div class="grid grid-cols-6 gap-3">
            {#each displayMedia as m (`${m.media_type}-${m.id}`)}
              {@const title = m.media_type === "movie" ? m.title : m.name}
              {@const selected = seenIds.has(`${m.media_type}-${m.id}`)}
              <button
                onclick={() => toggleSeenMedia(m)}
                title={title}
                class="group relative overflow-hidden rounded-lg border-2 transition-colors
                  {selected ? 'border-accent' : 'border-transparent'}"
              >
                {#if m.poster_path}
                  <img
                    src={m.poster_path}
                    alt={title}
                    loading="lazy"
                    decoding="async"
                    class="aspect-2/3 w-full object-cover"
                  />
                {:else}
                  <div class="flex aspect-2/3 w-full items-center justify-center bg-muted text-sm text-muted-foreground">
                    {title}
                  </div>
                {/if}
                {#if selected}
                  <div class="absolute inset-0 flex items-center justify-center bg-accent/30">
                    <Check class="size-8 text-white drop-shadow" />
                  </div>
                {/if}
              </button>
            {/each}
          </div>
        {/if}
      </div>

    {:else if stepIndex === 4}
      <!-- Rate Them -->
      <h2 class="mb-2 shrink-0 text-2xl font-semibold">{step.title}</h2>
      <p class="mb-6 shrink-0 text-base text-muted-foreground">
        Rate the ones you remember — this helps personalize your recommendations.
      </p>
      {#if preparingEntries}
        <div class="flex flex-1 items-center justify-center">
          <Loader2 class="size-8 animate-spin text-muted-foreground" />
        </div>
      {:else}
        <div class="min-h-0 flex-1 overflow-y-auto overflow-x-clip">
          <div class="flex flex-col gap-4">
            {#each seenMedia as m, i (`${m.media_type}-${m.id}`)}
              {@const title = m.media_type === "movie" ? m.title : m.name}
              <div class="flex items-center gap-4">
                {#if m.poster_path}
                  <img
                    src={m.poster_path}
                    alt={title}
                    loading="lazy"
                    decoding="async"
                    class="h-16 w-11 shrink-0 rounded object-cover"
                  />
                {:else}
                  <div class="h-16 w-11 shrink-0 rounded bg-muted"></div>
                {/if}
                <p class="min-w-0 flex-1 truncate text-lg font-medium">{title}</p>
                <StarRating libraryEntry={seenEntries[i] ?? null} media={m} />
              </div>
            {/each}
          </div>
        </div>
      {/if}

    {:else if stepIndex === 5}
      <!-- Preferences -->
      <h2 class="mb-6 shrink-0 text-2xl font-semibold">{step.title}</h2>
      <div class="min-h-0 flex-1 p-4 overflow-y-auto overflow-x-clip">

        <!-- Subtitle language cycle button -->
        <button
          onclick={cycleSubtitleLang}
          class="flex w-full items-center justify-between rounded-xl border border-border px-6 py-4 text-left transition-colors hover:bg-muted/30"
        >
          <div>
            <p class="text-lg font-medium">Subtitle language</p>
            <p class="text-sm text-muted-foreground">Auto-selected when subtitles are available</p>
          </div>
          <span class="ml-6 shrink-0 rounded-lg bg-muted px-4 py-2 text-lg font-semibold">
            {langLabel(subtitleLang)}
          </span>
        </button>

        <!-- Audio language cycle button -->
        <button
          onclick={cycleAudioLang}
          class="mt-3 flex w-full items-center justify-between rounded-xl border border-border px-6 py-4 text-left transition-colors hover:bg-muted/30"
        >
          <div>
            <p class="text-lg font-medium">Audio language</p>
            <p class="text-sm text-muted-foreground">Auto-selected when multiple tracks are available</p>
          </div>
          <span class="ml-6 shrink-0 rounded-lg bg-muted px-4 py-2 text-lg font-semibold">
            {audioLangLabel(audioLang)}
          </span>
        </button>

        <Separator class="my-5" />

        <!-- Toggle rows -->
        <button
          onclick={() => (autoPlay = !autoPlay)}
          class="flex w-full items-center justify-between rounded-xl border border-border px-6 py-4 text-left transition-colors hover:bg-muted/30"
        >
          <div>
            <p class="text-lg font-medium">Auto-play next episode</p>
            <p class="text-sm text-muted-foreground">Automatically start the next episode</p>
          </div>
          <span class="ml-6 shrink-0 rounded-full px-5 py-1.5 text-base font-semibold
            {autoPlay ? 'bg-accent text-accent-foreground' : 'bg-muted text-muted-foreground'}">
            {autoPlay ? "On" : "Off"}
          </span>
        </button>

        <button
          onclick={() => (rememberPosition = !rememberPosition)}
          class="mt-3 flex w-full items-center justify-between rounded-xl border border-border px-6 py-4 text-left transition-colors hover:bg-muted/30"
        >
          <div>
            <p class="text-lg font-medium">Remember playback position</p>
            <p class="text-sm text-muted-foreground">Resume from where you left off</p>
          </div>
          <span class="ml-6 shrink-0 rounded-full px-5 py-1.5 text-base font-semibold
            {rememberPosition ? 'bg-accent text-accent-foreground' : 'bg-muted text-muted-foreground'}">
            {rememberPosition ? "On" : "Off"}
          </span>
        </button>

        <Separator class="my-5" />

        <button
          onclick={() => (autoSkipIntro = !autoSkipIntro)}
          class="flex w-full items-center justify-between rounded-xl border border-border px-6 py-4 text-left transition-colors hover:bg-muted/30"
        >
          <p class="text-lg font-medium">Auto-skip intros</p>
          <span class="ml-6 shrink-0 rounded-full px-5 py-1.5 text-base font-semibold
            {autoSkipIntro ? 'bg-accent text-accent-foreground' : 'bg-muted text-muted-foreground'}">
            {autoSkipIntro ? "On" : "Off"}
          </span>
        </button>

        <button
          onclick={() => (autoSkipRecap = !autoSkipRecap)}
          class="mt-3 flex w-full items-center justify-between rounded-xl border border-border px-6 py-4 text-left transition-colors hover:bg-muted/30"
        >
          <p class="text-lg font-medium">Auto-skip recaps</p>
          <span class="ml-6 shrink-0 rounded-full px-5 py-1.5 text-base font-semibold
            {autoSkipRecap ? 'bg-accent text-accent-foreground' : 'bg-muted text-muted-foreground'}">
            {autoSkipRecap ? "On" : "Off"}
          </span>
        </button>

        <button
          onclick={() => (autoSkipCredits = !autoSkipCredits)}
          class="mt-3 flex w-full items-center justify-between rounded-xl border border-border px-6 py-4 text-left transition-colors hover:bg-muted/30"
        >
          <p class="text-lg font-medium">Auto-skip credits</p>
          <span class="ml-6 shrink-0 rounded-full px-5 py-1.5 text-base font-semibold
            {autoSkipCredits ? 'bg-accent text-accent-foreground' : 'bg-muted text-muted-foreground'}">
            {autoSkipCredits ? "On" : "Off"}
          </span>
        </button>

        <button
          onclick={() => (autoSkipPreview = !autoSkipPreview)}
          class="mt-3 flex w-full items-center justify-between rounded-xl border border-border px-6 py-4 text-left transition-colors hover:bg-muted/30"
        >
          <p class="text-lg font-medium">Auto-skip previews</p>
          <span class="ml-6 shrink-0 rounded-full px-5 py-1.5 text-base font-semibold
            {autoSkipPreview ? 'bg-accent text-accent-foreground' : 'bg-muted text-muted-foreground'}">
            {autoSkipPreview ? "On" : "Off"}
          </span>
        </button>
      </div>

    {:else if stepIndex === 6}
      <!-- Done -->
      <div class="flex flex-1 flex-col items-center justify-center gap-6 text-center">
        <div class="flex size-24 items-center justify-center rounded-full bg-accent/20">
          <CheckCircle class="size-12 text-accent" />
        </div>
        <h2 class="text-3xl font-semibold">You're all set!</h2>
        <p class="max-w-lg text-lg text-muted-foreground">
          Cove is ready to use. Add streaming addons from Settings to start watching.
        </p>
      </div>
    {/if}

  </div>

  <!-- Navigation bar -->
  <div class="flex shrink-0 items-center justify-between border-t border-border px-16 py-6">
    <div class="w-36">
      {#if !isFirst}
        <Button variant="ghost" onclick={back} disabled={nextLoading} class="h-12 px-8 text-lg">
          <ArrowLeft class="size-5" />
          Back
        </Button>
      {/if}
    </div>
    <div class="flex items-center gap-3">
      {#if step.skippable && !nextLoading}
        <Button variant="ghost" class="h-12 px-8 text-lg text-muted-foreground" onclick={skip}>
          Skip
        </Button>
      {/if}
      <div bind:this={nextBtnWrap}>
        <Button
          onclick={handleNext}
          disabled={!canProceed}
          class="h-12 min-w-36 px-8 text-lg"
        >
          {#if nextLoading}
            <Loader2 class="size-5 animate-spin" />
          {:else}
            {isFirst ? "Get Started" : isLast ? "Start Exploring" : "Next"}
            {#if !isLast && !isFirst}
              <ArrowRight class="size-5" />
            {/if}
          {/if}
        </Button>
      </div>
    </div>
  </div>

</div>
