<script lang="ts">
  import { CheckCircle, Check, Loader2, ArrowLeft, ArrowRight } from "lucide-svelte";
  import { Button } from "$lib/components/ui/button/index.js";
  import * as Select from "$lib/components/ui/select/index.js";
  import { Separator } from "$lib/components/ui/separator/index.js";
  import { Switch } from "$lib/components/ui/switch/index.js";
  import { Input } from "$lib/components/ui/input/index.js";
  import AuthDialog from "./AuthDialog.svelte";
  import StarRating from "./StarRating.svelte";
  import { settings } from "$lib/stores/settings";
  import { auth } from "$lib/stores/auth.svelte";
  import { api } from "$lib/api";
  import type { Media } from "$lib/types/tmdb";
  import type { LibraryEntry } from "$lib/types/library";
  import CoveIcon from "../assets/CoveIcon.svelte";
  import {SvelteSet} from "svelte/reactivity";
  import * as msg from "$lib/paraglide/messages.js";
  import {
    activeLocale,
    activateLocale,
    languageDisplayName,
    LOCALES,
    type AppLocale,
  } from "$lib/i18n";

  let { onclose }: { onclose: () => void } = $props();

  type StepId =
    | "welcome"
    | "language"
    | "account"
    | "genres"
    | "seen"
    | "rate"
    | "preferences"
    | "done";
  type StepMeta = { id: StepId; title: () => string; skippable: boolean };
  const STEPS: StepMeta[] = [
    { id: "welcome",     title: msg.onboarding_welcome,     skippable: false },
    { id: "language",    title: msg.onboarding_language,    skippable: false },
    { id: "account",     title: msg.onboarding_account,     skippable: false },
    { id: "genres",      title: msg.onboarding_taste,       skippable: false },
    { id: "seen",        title: msg.onboarding_seen,        skippable: true },
    { id: "rate",        title: msg.onboarding_rate,        skippable: true },
    { id: "preferences", title: msg.onboarding_preferences, skippable: true },
    { id: "done",        title: msg.onboarding_ready,       skippable: false },
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
    return languageDisplayName(v);
  }

  function audioLangLabel(v: string): string {
    return v === "original" ? msg.common_original() : languageDisplayName(v);
  }

  let selectedMovieGenreIds = new SvelteSet<number>();
  let selectedTvGenreIds    = new SvelteSet<number>();
  const initialUiLanguage = activeLocale();
  let selectedUiLanguage = $state<AppLocale>(initialUiLanguage);
  let languageSaveError = $state(false);

  function selectUiLanguage(locale: AppLocale): void {
    selectedUiLanguage = locale;
    languageSaveError = false;
    activateLocale(locale);
  }

  // ── Navigation ────────────────────────────────────────────────────────────────
  let stepIndex = $state(0);
  const step = $derived(STEPS[stepIndex]);
  const stepTitle = $derived.by(() => {
    selectedUiLanguage;
    return step.title();
  });
  const isFirst = $derived(stepIndex === 0);
  const isLast = $derived(stepIndex === STEPS.length - 1);
  const canProceed = $derived(
    step.id === "genres"
      ? selectedMovieGenreIds.size > 0 || selectedTvGenreIds.size > 0
      : true,
  );

  function stepIndexFor(id: StepId): number {
    return STEPS.findIndex((candidate) => candidate.id === id);
  }

  // ── Account step ──────────────────────────────────────────────────────────────
  let authOpen = $state(false);

  // ── Genre step ────────────────────────────────────────────────────────────────
  let movieGenres = $state<{ id: number; name: string }[]>([]);
  let tvGenres    = $state<{ id: number; name: string }[]>([]);


  let genreQuery = $state("");
  let loadingGenres = $state(false);
  let genresLanguage: AppLocale | null = null;

  const filteredMovieGenres = $derived(
    genreQuery.trim()
      ? movieGenres.filter((g) => g.name.toLowerCase().includes(genreQuery.toLowerCase()))
      : movieGenres,
  );
  const filteredTvGenres = $derived(
    genreQuery.trim()
      ? tvGenres.filter((g) => g.name.toLowerCase().includes(genreQuery.toLowerCase()))
      : tvGenres,
  );

  function toggleMovieGenre(id: number): void {
    if (selectedMovieGenreIds.has(id)) selectedMovieGenreIds.delete(id);
    else selectedMovieGenreIds.add(id);
  }
  function toggleTvGenre(id: number): void {
    if (selectedTvGenreIds.has(id)) selectedTvGenreIds.delete(id);
    else selectedTvGenreIds.add(id);
  }

  async function loadGenres(): Promise<void> {
    if (genresLanguage === selectedUiLanguage) return;
    loadingGenres = true;
    [movieGenres, tvGenres] = await Promise.all([
      api.genreList("movie").catch(() => []),
      api.genreList("tv").catch(() => []),
    ]);
    genresLanguage = selectedUiLanguage;
    loadingGenres = false;
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

  // ── Navigation handlers ───────────────────────────────────────────────────────
  async function next(): Promise<void> {
    if (step.id === "language") {
      languageSaveError = !(await settings.save({ uiLanguage: selectedUiLanguage }));
      if (languageSaveError) return;
    }
    if (step.id === "account") {
      languageSaveError = !(await settings.save({ uiLanguage: selectedUiLanguage }));
      if (languageSaveError) return;
      await loadGenres();
    }
    if (step.id === "genres") {
      await loadBrowseMedia();
    }
    if (step.id === "seen") {
      if (seenMedia.length === 0) {
        stepIndex = stepIndexFor("preferences");
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
    if (step.id === "preferences") {
      await settings.save({
        uiLanguage: selectedUiLanguage,
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
      const saved = await settings.save({
        onboardingDone: true,
        uiLanguage: selectedUiLanguage,
      });
      if (!saved) return;
      if (selectedUiLanguage !== initialUiLanguage) {
        window.location.reload();
      } else {
        onclose();
      }
    } else {
      stepIndex += 1;
    }
  }

  function back(): void {
    if (step.id === "preferences" && seenMedia.length === 0) {
      // Skipped rating step on the way in — go back to seen step
      stepIndex = stepIndexFor("seen");
    } else {
      stepIndex -= 1;
    }
  }

  function skip(): void {
    // Skipping "Seen" with nothing selected also skips "Rate" since it'd be empty
    if (step.id === "seen" && seenMedia.length === 0) {
      stepIndex = stepIndexFor("preferences");
    } else {
      stepIndex += 1;
    }
  }

  let nextLoading = $state(false);
  async function handleNext(): Promise<void> {
    nextLoading = true;
    await next();
    nextLoading = false;
  }
</script>

<div class="flex w-[90%]! h-[80%]! flex-col overflow-hidden rounded-2xl border border-border bg-card shadow-2xl">
  <!-- Progress pills -->
  <div class="flex items-center justify-center gap-2 px-8 pt-8">
    {#each STEPS as _, i}
      <div
        class="h-1.5 rounded-full transition-all duration-300 {i === stepIndex
          ? 'w-6 bg-accent'
          : i < stepIndex
            ? 'w-3 bg-accent/40'
            : 'w-3 bg-muted'}"
      ></div>
    {/each}
  </div>

  <!-- Step content -->
  <div class="flex flex-1 min-h-0 flex-col px-8 py-6">
    {#if stepIndex === 0}
      <!-- Welcome -->
      <div class="flex flex-1 flex-col items-center justify-center gap-4 text-center">
        <div class="flex items-center justify-center rounded-2xl">
          <CoveIcon size={96} />
        </div>
        <h1 class="text-3xl font-bold tracking-tight">{msg.onboarding_welcome()}</h1>
        <p class="max-w-sm text-base text-muted-foreground">
          {msg.onboarding_welcome_description()}
        </p>
      </div>

    {:else if stepIndex === 1}
      <!-- Language -->
      {#key selectedUiLanguage}
        <h2 class="mb-1 text-xl font-semibold">{stepTitle}</h2>
        <p class="mb-6 text-sm text-muted-foreground">{msg.onboarding_language_prompt()}</p>
      {/key}
      <div class="grid grid-cols-2 gap-3">
        {#each LOCALES as locale}
          <button
            type="button"
            onclick={() => selectUiLanguage(locale.appLocale)}
            aria-pressed={selectedUiLanguage === locale.appLocale}
            class="flex items-center justify-between rounded-xl border p-5 text-left transition-colors
              {selectedUiLanguage === locale.appLocale
                ? 'border-accent bg-accent/10'
                : 'border-border hover:border-accent/60 hover:bg-muted/30'}"
          >
            <span class="text-lg font-semibold">{locale.nativeName}</span>
            {#if selectedUiLanguage === locale.appLocale}
              <span class="flex size-7 items-center justify-center rounded-full bg-accent text-accent-foreground">
                <Check class="size-4" />
              </span>
            {/if}
          </button>
        {/each}
      </div>
      {#if languageSaveError}
        <p class="mt-4 text-sm text-destructive">{msg.language_save_error()}</p>
      {/if}

    {:else if stepIndex === 2}
      <!-- Account -->
      <h2 class="mb-4 text-xl font-semibold">{stepTitle}</h2>
      {#if auth.isGuest}
        <p class="text-sm text-muted-foreground">
          {msg.onboarding_guest_description()}
        </p>
        <Button onclick={() => (authOpen = true)} class="mt-4 w-full">
          {msg.onboarding_sign_in()}
        </Button>
        <p class="mt-3 text-center text-xs text-muted-foreground">
          {msg.onboarding_sign_in_later()}
        </p>
      {:else}
        <div class="flex items-center gap-3 rounded-lg border border-border p-4">
          <div
            class="flex size-10 shrink-0 items-center justify-center rounded-full bg-accent font-semibold text-accent-foreground"
          >
            {auth.activeProfile?.name?.charAt(0).toUpperCase() ?? "?"}
          </div>
          <div class="min-w-0 flex-1">
            <p class="truncate text-sm font-medium">{auth.activeProfile?.name ?? msg.onboarding_profile()}</p>
            <p class="truncate text-xs text-muted-foreground">{auth.session?.email}</p>
          </div>
          <CheckCircle class="size-5 shrink-0 text-green-500" />
        </div>
        <p class="mt-3 text-sm text-muted-foreground">
          {msg.onboarding_account_connected()}
        </p>
      {/if}

    {:else if stepIndex === 3}
      <!-- Genres -->
      <h2 class="mb-1 text-xl font-semibold">{stepTitle}</h2>
      <p class="mb-4 text-sm text-muted-foreground">{msg.onboarding_genre_prompt()}</p>
      <Input
        placeholder={msg.onboarding_filter_genres()}
        bind:value={genreQuery}
        class="mb-4"
      />
      <div class="overflow-y-auto" style="max-height: 18rem;">
        {#if loadingGenres}
          <div class="flex items-center justify-center py-8">
            <Loader2 class="size-6 animate-spin text-muted-foreground" />
          </div>
        {:else}
          {#if filteredMovieGenres.length > 0}
            <p class="mb-2 text-xs font-medium uppercase tracking-wider text-muted-foreground">{msg.search_movies()}</p>
            <div class="mb-4 flex flex-wrap gap-2">
              {#each filteredMovieGenres as g (g.id)}
                <button
                  onclick={() => toggleMovieGenre(g.id)}
                  class="rounded-full border px-3 py-1 text-sm transition-colors
                    {selectedMovieGenreIds.has(g.id)
                      ? 'border-accent bg-accent text-accent-foreground'
                      : 'border-border hover:border-accent/60 hover:text-foreground'}"
                >
                  {g.name}
                </button>
              {/each}
            </div>
          {/if}
          {#if filteredTvGenres.length > 0}
            <p class="mb-2 text-xs font-medium uppercase tracking-wider text-muted-foreground">{msg.search_tv_shows()}</p>
            <div class="flex flex-wrap gap-2">
              {#each filteredTvGenres as g (g.id)}
                <button
                  onclick={() => toggleTvGenre(g.id)}
                  class="rounded-full border px-3 py-1 text-sm transition-colors
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

    {:else if stepIndex === 4}
      <!-- Seen Before -->
      <div class="flex items-center justify-between">
        <h2 class="text-xl font-semibold">{stepTitle}</h2>
        {#if seenMedia.length > 0}
          <span class="rounded-full bg-accent/20 px-2 py-0.5 text-xs font-medium text-accent">
            {msg.onboarding_selected_count({ count: seenMedia.length })}
          </span>
        {/if}
      </div>
      <p class="mb-4 mt-1 text-sm text-muted-foreground">{msg.onboarding_seen_prompt()}</p>
      <Input
        placeholder={msg.onboarding_search_media()}
        value={mediaQuery}
        oninput={(e) => onMediaQueryChange(e.currentTarget.value)}
        class="mb-3"
      />
      <div class="overflow-y-auto max-h-full">
        {#if loadingMedia}
          <div class="flex items-center justify-center py-8">
            <Loader2 class="size-6 animate-spin text-muted-foreground" />
          </div>
        {:else if displayMedia.length === 0}
          <p class="py-6 text-center text-sm text-muted-foreground">
            {mediaQuery.trim() ? msg.search_no_results() : msg.onboarding_no_media()}
          </p>
        {:else}
          <div class="grid grid-cols-4 gap-2">
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
                  <div class="flex aspect-2/3 w-full items-center justify-center bg-muted text-xs text-muted-foreground">
                    {title}
                  </div>
                {/if}
                {#if selected}
                  <div class="absolute inset-0 flex items-center justify-center bg-accent/30">
                    <Check class="size-6 text-white drop-shadow" />
                  </div>
                {/if}
              </button>
            {/each}
          </div>
        {/if}
      </div>

    {:else if stepIndex === 5}
      <!-- Rate Them -->
      <h2 class="mb-1 text-xl font-semibold">{stepTitle}</h2>
      <p class="mb-4 text-sm text-muted-foreground">
        {msg.onboarding_rate_prompt()}
      </p>
      {#if preparingEntries}
        <div class="flex flex-1 items-center justify-center">
          <Loader2 class="size-6 animate-spin text-muted-foreground" />
        </div>
      {:else}
        <div class="overflow-y-auto overflow-x-clip max-h-full">
          <div class="flex flex-col gap-3">
            {#each seenMedia as m, i (`${m.media_type}-${m.id}`)}
              {@const title = m.media_type === "movie" ? m.title : m.name}
              <div class="flex items-center gap-3">
                {#if m.poster_path}
                  <img
                    src={m.poster_path}
                    alt={title}
                    loading="lazy"
                    decoding="async"
                    class="h-12 w-8 shrink-0 rounded object-cover"
                  />
                {:else}
                  <div class="h-12 w-8 shrink-0 rounded bg-muted"></div>
                {/if}
                <p class="min-w-0 flex-1 truncate text-sm font-medium">{title}</p>
                <StarRating libraryEntry={seenEntries[i] ?? null} media={m} variant="inline" />
              </div>
            {/each}
          </div>
        </div>
      {/if}

    {:else if stepIndex === 6}
      <!-- Preferences -->
      <h2 class="mb-4 text-xl font-semibold">{stepTitle}</h2>
      <div class="overflow-y-auto overflow-x-clip max-h-full">
        <!-- Language selectors -->
        <div class="flex items-center justify-between py-2">
          <div>
            <p class="text-sm font-medium">{msg.onboarding_subtitles()}</p>
            <p class="text-xs text-muted-foreground">{msg.onboarding_subtitle_description()}</p>
          </div>
          <Select.Root type="single" bind:value={subtitleLang}>
            <Select.Trigger class="w-36">{langLabel(subtitleLang)}</Select.Trigger>
            <Select.Content>
              {#each LANGUAGES as l}
                <Select.Item value={l.value}>{languageDisplayName(l.value)}</Select.Item>
              {/each}
            </Select.Content>
          </Select.Root>
        </div>
        <div class="flex items-center justify-between py-2">
          <div>
            <p class="text-sm font-medium">{msg.onboarding_audio()}</p>
            <p class="text-xs text-muted-foreground">{msg.onboarding_audio_description()}</p>
          </div>
          <Select.Root type="single" bind:value={audioLang}>
            <Select.Trigger class="w-36">{audioLangLabel(audioLang)}</Select.Trigger>
            <Select.Content>
              {#each AUDIO_LANGUAGES as l}
                <Select.Item value={l.value}>{audioLangLabel(l.value)}</Select.Item>
              {/each}
            </Select.Content>
          </Select.Root>
        </div>

        <Separator class="my-3" />

        <!-- Playback toggles -->
        <div class="flex items-center justify-between py-2">
          <div>
            <p class="text-sm font-medium">{msg.settings_autoplay()}</p>
            <p class="text-xs text-muted-foreground">{msg.onboarding_autoplay_description()}</p>
          </div>
          <Switch checked={autoPlay} onCheckedChange={(v) => (autoPlay = v)} />
        </div>
        <div class="flex items-center justify-between py-2">
          <div>
            <p class="text-sm font-medium">{msg.onboarding_remember()}</p>
            <p class="text-xs text-muted-foreground">{msg.onboarding_remember_description()}</p>
          </div>
          <Switch checked={rememberPosition} onCheckedChange={(v) => (rememberPosition = v)} />
        </div>

        <Separator class="my-3" />

        <div class="flex items-center justify-between py-2">
          <div>
            <p class="text-sm font-medium">{msg.onboarding_skip_intros()}</p>
          </div>
          <Switch checked={autoSkipIntro} onCheckedChange={(v) => (autoSkipIntro = v)} />
        </div>
        <div class="flex items-center justify-between py-2">
          <div>
            <p class="text-sm font-medium">{msg.onboarding_skip_recaps()}</p>
          </div>
          <Switch checked={autoSkipRecap} onCheckedChange={(v) => (autoSkipRecap = v)} />
        </div>
        <div class="flex items-center justify-between py-2">
          <div>
            <p class="text-sm font-medium">{msg.onboarding_skip_credits()}</p>
          </div>
          <Switch checked={autoSkipCredits} onCheckedChange={(v) => (autoSkipCredits = v)} />
        </div>
        <div class="flex items-center justify-between py-2">
          <div>
            <p class="text-sm font-medium">{msg.onboarding_skip_previews()}</p>
          </div>
          <Switch checked={autoSkipPreview} onCheckedChange={(v) => (autoSkipPreview = v)} />
        </div>
      </div>

    {:else if stepIndex === 7}
      <!-- Done -->
      <div class="flex flex-1 flex-col items-center justify-center gap-4 text-center">
        <div class="flex size-16 items-center justify-center rounded-full bg-accent/20">
          <CheckCircle class="size-8 text-accent" />
        </div>
        <h2 class="text-2xl font-semibold">{msg.onboarding_ready()}</h2>
        <p class="max-w-sm text-sm text-muted-foreground">
          {msg.onboarding_ready_description()}
        </p>
      </div>
    {/if}
  </div>

  <!-- Navigation bar -->
  <div class="flex items-center justify-between border-t border-border px-8 py-4">
    <div class="w-24">
      {#if !isFirst}
        <Button variant="ghost" onclick={back} disabled={nextLoading}>
          <ArrowLeft class="size-4" />
          {msg.common_back()}
        </Button>
      {/if}
    </div>
    {#key selectedUiLanguage}
      <div class="flex items-center gap-2">
        {#if step.skippable && !nextLoading}
          <Button variant="ghost" class="text-muted-foreground" onclick={skip}>{msg.onboarding_skip()}</Button>
        {/if}
        <Button onclick={handleNext} disabled={nextLoading || !canProceed} class="min-w-28">
          {#if nextLoading}
            <Loader2 class="size-4 animate-spin" />
          {:else}
            {isFirst ? msg.onboarding_get_started() : isLast ? msg.onboarding_finish() : msg.onboarding_next()}
            {#if !isLast && !isFirst}
              <ArrowRight class="size-4" />
            {/if}
          {/if}
        </Button>
      </div>
    {/key}
  </div>
</div>

{#if authOpen}
  <AuthDialog onclose={() => (authOpen = false)} />
{/if}
