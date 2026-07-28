<script lang="ts">
  import { onMount, tick } from "svelte";
  import {
    CheckCircle,
    Check,
    Loader2,
    ArrowLeft,
    ArrowRight,
  } from "lucide-svelte";
  import { Button } from "$lib/components/ui/button/index.js";
  import { Separator } from "$lib/components/ui/separator/index.js";
  import TvAuthPanel from "../components/TvAuthPanel.svelte";
  import StarRating from "../../components/StarRating.svelte";
  import { auth } from "$lib/stores/auth.svelte";
  import CoveIcon from "../../assets/CoveIcon.svelte";
  import { focusGroup } from "../focus/actions";
  import {
    focusAfterKeyRelease,
    focusFirst,
    isEditable,
  } from "../focus/focusStore.svelte";
  import * as msg from "$lib/paraglide/messages.js";
  import { AUDIO_LANGUAGES, LANGUAGES } from "$lib/mediaLanguages";
  import {
    audioLangLabel,
    langLabel,
    OnboardingController,
    STEPS,
  } from "$lib/onboarding.svelte";
  import { LOCALES } from "$lib/i18n";

  let { onclose }: { onclose: () => void } = $props();

  // Step definitions, navigation and every step's data live in
  // $lib/onboarding.svelte.ts, shared with OnboardingPage. What stays here is
  // TV-only: the aria label, the cycle buttons, the Next re-entry guard and
  // the D-pad focus management below.
  const ctl = new OnboardingController({ onClose: () => onclose() });

  // Re-derives on locale change so the label follows the chosen language.
  const onboardingAriaLabel = $derived.by(() => {
    ctl.selectedUiLanguage;
    return msg.aria_onboarding();
  });

  // Cycle buttons replace the desktop selects — Enter advances one option.
  function cycleSubtitleLang(): void {
    const idx = LANGUAGES.findIndex((l) => l.value === ctl.subtitleLang);
    ctl.subtitleLang = LANGUAGES[(idx + 1) % LANGUAGES.length].value;
  }

  function cycleAudioLang(): void {
    const idx = AUDIO_LANGUAGES.findIndex((l) => l.value === ctl.audioLang);
    ctl.audioLang = AUDIO_LANGUAGES[(idx + 1) % AUDIO_LANGUAGES.length].value;
  }

  async function handleNext(): Promise<void> {
    // Re-entry guard instead of disabling the button: disabling a focused
    // button drops focus to <body>, and the async step transitions
    // (loadBrowseMedia, library upserts) leave it there for seconds — an
    // arrow press in that window would strand the D-pad behind the overlay.
    if (ctl.nextLoading) return;
    ctl.nextLoading = true;
    await ctl.next();
    ctl.nextLoading = false;
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
    const btn = nextBtnWrap?.querySelector(
      "button",
    ) as HTMLButtonElement | null;
    return btn;
  }

  // Rescue focus into the overlay: when the auth panel is open, prefer its
  // container so Escape-blur of an auth input doesn't yank focus to the nav
  // bar; otherwise prefer the Next button if enabled, then focusFirst(rootEl).
  function rescueFocus(): void {
    if (ctl.authOpen && authWrap) {
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
    const _step = ctl.stepIndex;
    const _loading = ctl.nextLoading;
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
    if (ctl.authOpen) {
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
          const signInBtn = signInBtnWrap?.querySelector(
            "button",
          ) as HTMLElement | null;
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
    if (ctl.authOpen) {
      if (!authPanel?.escapeBack()) {
        ctl.authOpen = false;
      }
      return;
    }
    // 3. Go back a step (unless on the first step — can't exit via Escape)
    if (!ctl.isFirst) {
      ctl.back();
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
  use:focusGroup={{
    id: "tv-onboarding",
    policy: { type: "free" },
    trapFocus: true,
  }}
  role="dialog"
  aria-modal="true"
  aria-label={onboardingAriaLabel}
>
  <!-- Progress pills -->
  <div class="flex shrink-0 items-center justify-center gap-2.5 px-16 pt-10">
    {#each STEPS as _, i}
      <div
        class="h-2 rounded-full transition-all duration-300 {i === ctl.stepIndex
          ? 'w-8 bg-accent'
          : i < ctl.stepIndex
            ? 'w-4 bg-accent/40'
            : 'w-4 bg-muted'}"
      ></div>
    {/each}
  </div>

  <!-- Step content -->
  <div class="flex min-h-0 flex-1 flex-col px-16 py-8">
    {#if ctl.stepIndex === 0}
      <!-- Welcome -->
      <div
        class="flex flex-1 flex-col items-center justify-center gap-6 text-center"
      >
        <div class="flex items-center justify-center rounded-2xl">
          <CoveIcon size={120} />
        </div>
        <h1 class="text-2xl font-bold tracking-tight">
          {msg.onboarding_welcome()}
        </h1>
        <p class="max-w-lg text-base text-muted-foreground">
          {msg.onboarding_welcome_description()}
        </p>
      </div>
    {:else if ctl.stepIndex === 1}
      <!-- Language -->
      {#key ctl.selectedUiLanguage}
        <h2 class="mb-2 text-2xl font-semibold">{ctl.stepTitle}</h2>
        <p class="mb-8 text-base text-muted-foreground">
          {msg.onboarding_language_prompt()}
        </p>
      {/key}
      <div class="grid grid-cols-2 gap-5 p-4">
        {#each LOCALES as locale}
          <button
            type="button"
            onclick={() => ctl.selectUiLanguage(locale.appLocale)}
            aria-pressed={ctl.selectedUiLanguage === locale.appLocale}
            class="flex min-h-28 items-center justify-between rounded-xl border px-8 py-6 text-left transition-colors
              {ctl.selectedUiLanguage === locale.appLocale
              ? 'border-accent bg-accent/10'
              : 'border-border hover:border-accent/60 hover:bg-muted/30'}"
          >
            <span class="text-2xl font-semibold">{locale.nativeName}</span>
            {#if ctl.selectedUiLanguage === locale.appLocale}
              <span
                class="flex size-10 items-center justify-center rounded-full bg-accent text-accent-foreground"
              >
                <Check class="size-6" />
              </span>
            {/if}
          </button>
        {/each}
      </div>
      {#if ctl.languageSaveError}
        <p class="mt-5 text-base text-destructive">
          {msg.language_save_error()}
        </p>
      {/if}
    {:else if ctl.stepIndex === 2}
      <!-- Account -->
      <h2 class="mb-6 text-2xl font-semibold">{ctl.stepTitle}</h2>
      {#if auth.isGuest}
        {#if ctl.authOpen}
          <!-- Inline auth panel — no fixed overlay, D-pad stays inside the trap -->
          <div bind:this={authWrap} class="flex justify-center">
            <TvAuthPanel
              bind:this={authPanel}
              ondone={() => (ctl.authOpen = false)}
            />
          </div>
        {:else}
          <p class="text-base text-muted-foreground">
            {msg.onboarding_guest_description()}
          </p>
          <div bind:this={signInBtnWrap} class="mt-6">
            <Button
              onclick={() => (ctl.authOpen = true)}
              class="h-12 w-full px-8 text-lg"
            >
              {msg.onboarding_sign_in()}
            </Button>
          </div>
          <p class="mt-4 text-center text-base text-muted-foreground">
            {msg.onboarding_sign_in_later()}
          </p>
        {/if}
      {:else}
        <div
          class="flex items-center gap-4 rounded-xl border border-border p-6"
        >
          <div
            class="flex size-14 shrink-0 items-center justify-center rounded-full bg-accent text-xl font-semibold text-accent-foreground"
          >
            {auth.activeProfile?.name?.charAt(0).toUpperCase() ?? "?"}
          </div>
          <div class="min-w-0 flex-1">
            <p class="truncate text-lg font-medium">
              {auth.activeProfile?.name ?? msg.onboarding_profile()}
            </p>
            <p class="truncate text-base text-muted-foreground">
              {auth.session?.email}
            </p>
          </div>
          <CheckCircle class="size-8 shrink-0 text-green-500" />
        </div>
        <p class="mt-4 text-base text-muted-foreground">
          {msg.onboarding_account_connected()}
        </p>
      {/if}
    {:else if ctl.stepIndex === 3}
      <!-- Genres -->
      <h2 class="mb-2 text-2xl font-semibold">{ctl.stepTitle}</h2>
      <p class="mb-6 text-base text-muted-foreground">
        {msg.onboarding_genre_prompt()}
      </p>
      <div class="min-h-0 flex-1 overflow-y-auto">
        {#if ctl.loadingGenres}
          <div class="flex items-center justify-center py-12">
            <Loader2 class="size-8 animate-spin text-muted-foreground" />
          </div>
        {:else}
          {#if ctl.movieGenres.length > 0}
            <p
              class="mb-3 text-sm font-medium uppercase tracking-wider text-muted-foreground"
            >
              {msg.search_movies()}
            </p>
            <div class="mb-6 p-4 flex flex-wrap gap-3">
              {#each ctl.movieGenres as g (g.id)}
                <button
                  onclick={() => ctl.toggleMovieGenre(g.id)}
                  class="rounded-full border px-5 py-2.5 text-lg transition-colors
                    {ctl.selectedMovieGenreIds.has(g.id)
                    ? 'border-accent bg-accent text-accent-foreground'
                    : 'border-border hover:border-accent/60 hover:text-foreground'}"
                >
                  {g.name}
                </button>
              {/each}
            </div>
          {/if}
          {#if ctl.tvGenres.length > 0}
            <p
              class="mb-3 text-sm font-medium uppercase tracking-wider text-muted-foreground"
            >
              {msg.search_tv_shows()}
            </p>
            <div class="flex flex-wrap gap-3">
              {#each ctl.tvGenres as g (g.id)}
                <button
                  onclick={() => ctl.toggleTvGenre(g.id)}
                  class="rounded-full border px-5 py-2.5 text-lg transition-colors
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
      <div class="mb-2 flex shrink-0 items-center justify-between">
        <h2 class="text-2xl font-semibold">{ctl.stepTitle}</h2>
        {#if ctl.seenMedia.length > 0}
          <span
            class="rounded-full bg-accent/20 px-3 py-1 text-base font-medium text-accent"
          >
            {msg.onboarding_selected_count({ count: ctl.seenMedia.length })}
          </span>
        {/if}
      </div>
      <p class="mb-5 shrink-0 text-base text-muted-foreground">
        {msg.onboarding_seen_prompt()}
      </p>
      <input
        type="search"
        placeholder={msg.onboarding_search_media()}
        value={ctl.mediaQuery}
        oninput={(e) => ctl.onMediaQueryChange(e.currentTarget.value)}
        class="mb-4 h-12 shrink-0 rounded-lg border border-border bg-input px-4 text-lg text-foreground placeholder:text-muted-foreground focus:border-accent focus:outline-none"
      />
      <div class="min-h-0 flex-1 overflow-y-auto">
        {#if ctl.loadingMedia}
          <div class="flex items-center justify-center py-12">
            <Loader2 class="size-8 animate-spin text-muted-foreground" />
          </div>
        {:else if ctl.displayMedia.length === 0}
          <p class="py-8 text-center text-lg text-muted-foreground">
            {ctl.mediaQuery.trim()
              ? msg.search_no_results()
              : msg.onboarding_no_media()}
          </p>
        {:else}
          <div class="grid grid-cols-6 gap-3">
            {#each ctl.displayMedia as m (`${m.media_type}-${m.id}`)}
              {@const title = m.media_type === "movie" ? m.title : m.name}
              {@const selected = ctl.seenIds.has(`${m.media_type}-${m.id}`)}
              <button
                onclick={() => ctl.toggleSeenMedia(m)}
                {title}
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
                  <div
                    class="flex aspect-2/3 w-full items-center justify-center bg-muted text-sm text-muted-foreground"
                  >
                    {title}
                  </div>
                {/if}
                {#if selected}
                  <div
                    class="absolute inset-0 flex items-center justify-center bg-accent/30"
                  >
                    <Check class="size-8 text-white drop-shadow" />
                  </div>
                {/if}
              </button>
            {/each}
          </div>
        {/if}
      </div>
    {:else if ctl.stepIndex === 5}
      <!-- Rate Them -->
      <h2 class="mb-2 shrink-0 text-2xl font-semibold">{ctl.stepTitle}</h2>
      <p class="mb-6 shrink-0 text-base text-muted-foreground">
        {msg.onboarding_rate_prompt()}
      </p>
      {#if ctl.preparingEntries}
        <div class="flex flex-1 items-center justify-center">
          <Loader2 class="size-8 animate-spin text-muted-foreground" />
        </div>
      {:else}
        <div class="min-h-0 flex-1 overflow-y-auto overflow-x-clip">
          <div class="flex flex-col gap-4">
            {#each ctl.seenMedia as m, i (`${m.media_type}-${m.id}`)}
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
                <p class="min-w-0 flex-1 truncate text-lg font-medium">
                  {title}
                </p>
                <StarRating
                  libraryEntry={ctl.seenEntries[i] ?? null}
                  media={m}
                  variant="inline"
                />
              </div>
            {/each}
          </div>
        </div>
      {/if}
    {:else if ctl.stepIndex === 6}
      <!-- Preferences -->
      <h2 class="mb-6 shrink-0 text-2xl font-semibold">{ctl.stepTitle}</h2>
      <div class="min-h-0 flex-1 p-4 overflow-y-auto overflow-x-clip">
        <!-- Subtitle language cycle button -->
        <button
          onclick={cycleSubtitleLang}
          class="flex w-full items-center justify-between rounded-xl border border-border px-6 py-4 text-left transition-colors hover:bg-muted/30"
        >
          <div>
            <p class="text-lg font-medium">{msg.onboarding_subtitles()}</p>
            <p class="text-sm text-muted-foreground">
              {msg.onboarding_subtitle_description()}
            </p>
          </div>
          <span
            class="ml-6 shrink-0 rounded-lg bg-muted px-4 py-2 text-lg font-semibold"
          >
            {langLabel(ctl.subtitleLang)}
          </span>
        </button>

        <!-- Audio language cycle button -->
        <button
          onclick={cycleAudioLang}
          class="mt-3 flex w-full items-center justify-between rounded-xl border border-border px-6 py-4 text-left transition-colors hover:bg-muted/30"
        >
          <div>
            <p class="text-lg font-medium">{msg.onboarding_audio()}</p>
            <p class="text-sm text-muted-foreground">
              {msg.onboarding_audio_description()}
            </p>
          </div>
          <span
            class="ml-6 shrink-0 rounded-lg bg-muted px-4 py-2 text-lg font-semibold"
          >
            {audioLangLabel(ctl.audioLang)}
          </span>
        </button>

        <Separator class="my-5" />

        <!-- Toggle rows -->
        <button
          onclick={() => (ctl.autoPlay = !ctl.autoPlay)}
          class="flex w-full items-center justify-between rounded-xl border border-border px-6 py-4 text-left transition-colors hover:bg-muted/30"
        >
          <div>
            <p class="text-lg font-medium">{msg.settings_autoplay()}</p>
            <p class="text-sm text-muted-foreground">
              {msg.onboarding_autoplay_description()}
            </p>
          </div>
          <span
            class="ml-6 shrink-0 rounded-full px-5 py-1.5 text-base font-semibold
            {ctl.autoPlay
              ? 'bg-accent text-accent-foreground'
              : 'bg-muted text-muted-foreground'}"
          >
            {ctl.autoPlay ? msg.common_on() : msg.common_off()}
          </span>
        </button>

        <button
          onclick={() => (ctl.rememberPosition = !ctl.rememberPosition)}
          class="mt-3 flex w-full items-center justify-between rounded-xl border border-border px-6 py-4 text-left transition-colors hover:bg-muted/30"
        >
          <div>
            <p class="text-lg font-medium">{msg.onboarding_remember()}</p>
            <p class="text-sm text-muted-foreground">
              {msg.onboarding_remember_description()}
            </p>
          </div>
          <span
            class="ml-6 shrink-0 rounded-full px-5 py-1.5 text-base font-semibold
            {ctl.rememberPosition
              ? 'bg-accent text-accent-foreground'
              : 'bg-muted text-muted-foreground'}"
          >
            {ctl.rememberPosition ? msg.common_on() : msg.common_off()}
          </span>
        </button>

        <Separator class="my-5" />

        <button
          onclick={() => (ctl.autoSkipIntro = !ctl.autoSkipIntro)}
          class="flex w-full items-center justify-between rounded-xl border border-border px-6 py-4 text-left transition-colors hover:bg-muted/30"
        >
          <p class="text-lg font-medium">{msg.onboarding_skip_intros()}</p>
          <span
            class="ml-6 shrink-0 rounded-full px-5 py-1.5 text-base font-semibold
            {ctl.autoSkipIntro
              ? 'bg-accent text-accent-foreground'
              : 'bg-muted text-muted-foreground'}"
          >
            {ctl.autoSkipIntro ? msg.common_on() : msg.common_off()}
          </span>
        </button>

        <button
          onclick={() => (ctl.autoSkipRecap = !ctl.autoSkipRecap)}
          class="mt-3 flex w-full items-center justify-between rounded-xl border border-border px-6 py-4 text-left transition-colors hover:bg-muted/30"
        >
          <p class="text-lg font-medium">{msg.onboarding_skip_recaps()}</p>
          <span
            class="ml-6 shrink-0 rounded-full px-5 py-1.5 text-base font-semibold
            {ctl.autoSkipRecap
              ? 'bg-accent text-accent-foreground'
              : 'bg-muted text-muted-foreground'}"
          >
            {ctl.autoSkipRecap ? msg.common_on() : msg.common_off()}
          </span>
        </button>

        <button
          onclick={() => (ctl.autoSkipCredits = !ctl.autoSkipCredits)}
          class="mt-3 flex w-full items-center justify-between rounded-xl border border-border px-6 py-4 text-left transition-colors hover:bg-muted/30"
        >
          <p class="text-lg font-medium">{msg.onboarding_skip_credits()}</p>
          <span
            class="ml-6 shrink-0 rounded-full px-5 py-1.5 text-base font-semibold
            {ctl.autoSkipCredits
              ? 'bg-accent text-accent-foreground'
              : 'bg-muted text-muted-foreground'}"
          >
            {ctl.autoSkipCredits ? msg.common_on() : msg.common_off()}
          </span>
        </button>

        <button
          onclick={() => (ctl.autoSkipPreview = !ctl.autoSkipPreview)}
          class="mt-3 flex w-full items-center justify-between rounded-xl border border-border px-6 py-4 text-left transition-colors hover:bg-muted/30"
        >
          <p class="text-lg font-medium">{msg.onboarding_skip_previews()}</p>
          <span
            class="ml-6 shrink-0 rounded-full px-5 py-1.5 text-base font-semibold
            {ctl.autoSkipPreview
              ? 'bg-accent text-accent-foreground'
              : 'bg-muted text-muted-foreground'}"
          >
            {ctl.autoSkipPreview ? msg.common_on() : msg.common_off()}
          </span>
        </button>
      </div>
    {:else if ctl.stepIndex === 7}
      <!-- Done -->
      <div
        class="flex flex-1 flex-col items-center justify-center gap-6 text-center"
      >
        <div
          class="flex size-24 items-center justify-center rounded-full bg-accent/20"
        >
          <CheckCircle class="size-12 text-accent" />
        </div>
        <h2 class="text-3xl font-semibold">{msg.onboarding_ready()}</h2>
        <p class="max-w-lg text-lg text-muted-foreground">
          {msg.onboarding_ready_description()}
        </p>
      </div>
    {/if}
  </div>

  <!-- Navigation bar -->
  <div
    class="flex shrink-0 items-center justify-between border-t border-border px-16 py-6"
  >
    <div class="w-36">
      {#if !ctl.isFirst}
        <Button
          variant="ghost"
          onclick={ctl.back}
          disabled={ctl.nextLoading}
          class="h-12 px-8 text-lg"
        >
          <ArrowLeft class="size-5" />
          {msg.common_back()}
        </Button>
      {/if}
    </div>
    {#key ctl.selectedUiLanguage}
      <div class="flex items-center gap-3">
        {#if ctl.step.skippable && !ctl.nextLoading}
          <Button
            variant="ghost"
            class="h-12 px-8 text-lg text-muted-foreground"
            onclick={ctl.skip}
          >
            {msg.onboarding_skip()}
          </Button>
        {/if}
        <div bind:this={nextBtnWrap}>
          <Button
            onclick={handleNext}
            disabled={!ctl.canProceed}
            class="h-12 min-w-36 px-8 text-lg"
          >
            {#if ctl.nextLoading}
              <Loader2 class="size-5 animate-spin" />
            {:else}
              {ctl.isFirst
                ? msg.onboarding_get_started()
                : ctl.isLast
                  ? msg.onboarding_finish()
                  : msg.onboarding_next()}
              {#if !ctl.isLast && !ctl.isFirst}
                <ArrowRight class="size-5" />
              {/if}
            {/if}
          </Button>
        </div>
      </div>
    {/key}
  </div>
</div>
