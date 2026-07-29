<script lang="ts">
  import { CheckCircle, Check, Loader2, ArrowLeft, ArrowRight } from "lucide-svelte";
  import { Button } from "$lib/components/ui/button/index.js";
  import * as Select from "$lib/components/ui/select/index.js";
  import { Separator } from "$lib/components/ui/separator/index.js";
  import { Switch } from "$lib/components/ui/switch/index.js";
  import { Input } from "$lib/components/ui/input/index.js";
  import AuthDialog from "./AuthDialog.svelte";
  import StarRating from "./StarRating.svelte";
  import { auth } from "$lib/stores/auth.svelte";
  import CoveIcon from "../assets/CoveIcon.svelte";
  import * as msg from "$lib/paraglide/messages.js";
  import { AUDIO_LANGUAGES, LANGUAGES } from "$lib/mediaLanguages";
  import {
    audioLangLabel,
    langLabel,
    OnboardingController,
    STEPS,
  } from "$lib/onboarding.svelte";
  import {
    languageDisplayName,
    LOCALES,
  } from "$lib/i18n";

  let { onclose }: { onclose: () => void } = $props();

  // Step definitions, navigation and every step's data live in
  // $lib/onboarding.svelte.ts, shared with TvOnboardingPage. What stays here
  // is desktop-only: the genre search filter below.
  const ctl = new OnboardingController({ onClose: () => onclose() });

  // ── Genre search (desktop only — TV has no text entry here) ───────────────
  let genreQuery = $state("");
  const filteredMovieGenres = $derived(
    genreQuery.trim()
      ? ctl.movieGenres.filter((g) => g.name.toLowerCase().includes(genreQuery.toLowerCase()))
      : ctl.movieGenres,
  );
  const filteredTvGenres = $derived(
    genreQuery.trim()
      ? ctl.tvGenres.filter((g) => g.name.toLowerCase().includes(genreQuery.toLowerCase()))
      : ctl.tvGenres,
  );

  async function handleNext(): Promise<void> {
    ctl.nextLoading = true;
    await ctl.next();
    ctl.nextLoading = false;
  }
</script>

<div class="flex w-[90%]! h-[80%]! flex-col overflow-hidden rounded-2xl border border-border bg-card shadow-2xl">
  <!-- Progress pills -->
  <div class="flex items-center justify-center gap-2 px-8 pt-8">
    {#each STEPS as _, i}
      <div
        class="h-1.5 rounded-full transition-all duration-300 {i === ctl.stepIndex
          ? 'w-6 bg-accent'
          : i < ctl.stepIndex
            ? 'w-3 bg-accent/40'
            : 'w-3 bg-muted'}"
      ></div>
    {/each}
  </div>

  <!-- Step content -->
  <div class="flex flex-1 min-h-0 flex-col px-8 py-6">
    {#if ctl.stepIndex === 0}
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

    {:else if ctl.stepIndex === 1}
      <!-- Language -->
      {#key ctl.selectedUiLanguage}
        <h2 class="mb-1 text-xl font-semibold">{ctl.stepTitle}</h2>
        <p class="mb-6 text-sm text-muted-foreground">{msg.onboarding_language_prompt()}</p>
      {/key}
      <div class="grid grid-cols-2 gap-3">
        {#each LOCALES as locale}
          <button
            type="button"
            onclick={() => ctl.selectUiLanguage(locale.appLocale)}
            aria-pressed={ctl.selectedUiLanguage === locale.appLocale}
            class="flex items-center justify-between rounded-xl border p-5 text-left transition-colors
              {ctl.selectedUiLanguage === locale.appLocale
                ? 'border-accent bg-accent/10'
                : 'border-border hover:border-accent/60 hover:bg-muted/30'}"
          >
            <span class="text-lg font-semibold">{locale.nativeName}</span>
            {#if ctl.selectedUiLanguage === locale.appLocale}
              <span class="flex size-7 items-center justify-center rounded-full bg-accent text-accent-foreground">
                <Check class="size-4" />
              </span>
            {/if}
          </button>
        {/each}
      </div>
      {#if ctl.languageSaveError}
        <p class="mt-4 text-sm text-destructive">{msg.language_save_error()}</p>
      {/if}

    {:else if ctl.stepIndex === 2}
      <!-- Account -->
      <h2 class="mb-4 text-xl font-semibold">{ctl.stepTitle}</h2>
      {#if auth.isGuest}
        <p class="text-sm text-muted-foreground">
          {msg.onboarding_guest_description()}
        </p>
        <Button onclick={() => (ctl.authOpen = true)} class="mt-4 w-full">
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

    {:else if ctl.stepIndex === 3}
      <!-- Genres -->
      <h2 class="mb-1 text-xl font-semibold">{ctl.stepTitle}</h2>
      <p class="mb-4 text-sm text-muted-foreground">{msg.onboarding_genre_prompt()}</p>
      <Input
        placeholder={msg.onboarding_filter_genres()}
        bind:value={genreQuery}
        class="mb-4"
      />
      <div class="overflow-y-auto" style="max-height: 18rem;">
        {#if ctl.loadingGenres}
          <div class="flex items-center justify-center py-8">
            <Loader2 class="size-6 animate-spin text-muted-foreground" />
          </div>
        {:else}
          {#if filteredMovieGenres.length > 0}
            <p class="mb-2 text-xs font-medium uppercase tracking-wider text-muted-foreground">{msg.search_movies()}</p>
            <div class="mb-4 flex flex-wrap gap-2">
              {#each filteredMovieGenres as g (g.id)}
                <button
                  onclick={() => ctl.toggleMovieGenre(g.id)}
                  class="rounded-full border px-3 py-1 text-sm transition-colors
                    {ctl.selectedMovieGenreIds.has(g.id)
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
                  onclick={() => ctl.toggleTvGenre(g.id)}
                  class="rounded-full border px-3 py-1 text-sm transition-colors
                    {ctl.selectedTvGenreIds.has(g.id)
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

    {:else if ctl.stepIndex === 4}
      <!-- Seen Before -->
      <div class="flex items-center justify-between">
        <h2 class="text-xl font-semibold">{ctl.stepTitle}</h2>
        {#if ctl.seenMedia.length > 0}
          <span class="rounded-full bg-accent/20 px-2 py-0.5 text-xs font-medium text-accent">
            {msg.onboarding_selected_count({ count: ctl.seenMedia.length })}
          </span>
        {/if}
      </div>
      <p class="mb-4 mt-1 text-sm text-muted-foreground">{msg.onboarding_seen_prompt()}</p>
      <Input
        placeholder={msg.onboarding_search_media()}
        value={ctl.mediaQuery}
        oninput={(e) => ctl.onMediaQueryChange(e.currentTarget.value)}
        class="mb-3"
      />
      <div class="overflow-y-auto max-h-full">
        {#if ctl.loadingMedia}
          <div class="flex items-center justify-center py-8">
            <Loader2 class="size-6 animate-spin text-muted-foreground" />
          </div>
        {:else if ctl.displayMedia.length === 0}
          <p class="py-6 text-center text-sm text-muted-foreground">
            {ctl.mediaQuery.trim() ? msg.search_no_results() : msg.onboarding_no_media()}
          </p>
        {:else}
          <div class="grid grid-cols-4 gap-2">
            {#each ctl.displayMedia as m (`${m.media_type}-${m.id}`)}
              {@const title = m.media_type === "movie" ? m.title : m.name}
              {@const selected = ctl.seenIds.has(`${m.media_type}-${m.id}`)}
              <button
                onclick={() => ctl.toggleSeenMedia(m)}
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

    {:else if ctl.stepIndex === 5}
      <!-- Rate Them -->
      <h2 class="mb-1 text-xl font-semibold">{ctl.stepTitle}</h2>
      <p class="mb-4 text-sm text-muted-foreground">
        {msg.onboarding_rate_prompt()}
      </p>
      {#if ctl.preparingEntries}
        <div class="flex flex-1 items-center justify-center">
          <Loader2 class="size-6 animate-spin text-muted-foreground" />
        </div>
      {:else}
        <div class="overflow-y-auto overflow-x-clip max-h-full">
          <div class="flex flex-col gap-3">
            {#each ctl.seenMedia as m, i (`${m.media_type}-${m.id}`)}
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
                <StarRating libraryEntry={ctl.seenEntries[i] ?? null} media={m} variant="inline" />
              </div>
            {/each}
          </div>
        </div>
      {/if}

    {:else if ctl.stepIndex === 6}
      <!-- Preferences -->
      <h2 class="mb-4 text-xl font-semibold">{ctl.stepTitle}</h2>
      <div class="overflow-y-auto overflow-x-clip max-h-full">
        <!-- Language selectors -->
        <div class="flex items-center justify-between py-2">
          <div>
            <p class="text-sm font-medium">{msg.onboarding_subtitles()}</p>
            <p class="text-xs text-muted-foreground">{msg.onboarding_subtitle_description()}</p>
          </div>
          <Select.Root type="single" bind:value={ctl.subtitleLang}>
            <Select.Trigger class="w-36">{langLabel(ctl.subtitleLang)}</Select.Trigger>
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
          <Select.Root type="single" bind:value={ctl.audioLang}>
            <Select.Trigger class="w-36">{audioLangLabel(ctl.audioLang)}</Select.Trigger>
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
          <Switch checked={ctl.autoPlay} onCheckedChange={(v) => (ctl.autoPlay = v)} />
        </div>
        <div class="flex items-center justify-between py-2">
          <div>
            <p class="text-sm font-medium">{msg.onboarding_remember()}</p>
            <p class="text-xs text-muted-foreground">{msg.onboarding_remember_description()}</p>
          </div>
          <Switch checked={ctl.rememberPosition} onCheckedChange={(v) => (ctl.rememberPosition = v)} />
        </div>

        <Separator class="my-3" />

        <div class="flex items-center justify-between py-2">
          <div>
            <p class="text-sm font-medium">{msg.onboarding_skip_intros()}</p>
          </div>
          <Switch checked={ctl.autoSkipIntro} onCheckedChange={(v) => (ctl.autoSkipIntro = v)} />
        </div>
        <div class="flex items-center justify-between py-2">
          <div>
            <p class="text-sm font-medium">{msg.onboarding_skip_recaps()}</p>
          </div>
          <Switch checked={ctl.autoSkipRecap} onCheckedChange={(v) => (ctl.autoSkipRecap = v)} />
        </div>
        <div class="flex items-center justify-between py-2">
          <div>
            <p class="text-sm font-medium">{msg.onboarding_skip_credits()}</p>
          </div>
          <Switch checked={ctl.autoSkipCredits} onCheckedChange={(v) => (ctl.autoSkipCredits = v)} />
        </div>
        <div class="flex items-center justify-between py-2">
          <div>
            <p class="text-sm font-medium">{msg.onboarding_skip_previews()}</p>
          </div>
          <Switch checked={ctl.autoSkipPreview} onCheckedChange={(v) => (ctl.autoSkipPreview = v)} />
        </div>
      </div>

    {:else if ctl.stepIndex === 7}
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
      {#if !ctl.isFirst}
        <Button variant="ghost" onclick={ctl.back} disabled={ctl.nextLoading}>
          <ArrowLeft class="size-4" />
          {msg.common_back()}
        </Button>
      {/if}
    </div>
    {#key ctl.selectedUiLanguage}
      <div class="flex items-center gap-2">
        {#if ctl.step.skippable && !ctl.nextLoading}
          <Button variant="ghost" class="text-muted-foreground" onclick={ctl.skip}>{msg.onboarding_skip()}</Button>
        {/if}
        <Button onclick={handleNext} disabled={ctl.nextLoading || !ctl.canProceed} class="min-w-28">
          {#if ctl.nextLoading}
            <Loader2 class="size-4 animate-spin" />
          {:else}
            {ctl.isFirst ? msg.onboarding_get_started() : ctl.isLast ? msg.onboarding_finish() : msg.onboarding_next()}
            {#if !ctl.isLast && !ctl.isFirst}
              <ArrowRight class="size-4" />
            {/if}
          {/if}
        </Button>
      </div>
    {/key}
  </div>
</div>

{#if ctl.authOpen}
  <AuthDialog
    onclose={() => (ctl.authOpen = false)}
    onauthdone={(onboardingDone) =>
      ctl.finishAuthentication(onboardingDone)}
  />
{/if}
