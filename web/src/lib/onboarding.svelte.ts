// $lib/onboarding.svelte.ts
//
// Shared data layer for the first-run wizard: step definitions and navigation,
// the UI-language choice, genre and "already seen" selection, the rating step's
// library upserts, and the playback preferences written on the way out.
//
// OnboardingPage.svelte and tv/pages/TvOnboardingPage.svelte carried identical
// copies of all of this. What stays in each shell is genuinely
// platform-specific: desktop's genre search filter, TV's D-pad focus rescue,
// its cycle buttons and its re-entry guard on Next.
//
// No $effect here — the components own theirs, same rule as the rest of $lib.

import { api } from "$lib/api";
import {
  activateLocale,
  activeLocale,
  languageDisplayName,
  type AppLocale,
} from "$lib/i18n";
import * as msg from "$lib/paraglide/messages.js";
import { auth } from "$lib/stores/auth.svelte";
import { settings } from "$lib/stores/settings";
import type { LibraryEntry } from "$lib/types/library";
import type { Media } from "$lib/types/tmdb";
import { SvelteSet } from "svelte/reactivity";

export type StepId =
  | "welcome"
  | "language"
  | "account"
  | "genres"
  | "seen"
  | "rate"
  | "preferences"
  | "done";

export type StepMeta = { id: StepId; title: () => string; skippable: boolean };

// `title` is a message *getter*, not a string: it has to be called at render
// time so switching the UI language mid-wizard relabels the steps.
export const STEPS: StepMeta[] = [
  { id: "welcome", title: msg.onboarding_welcome, skippable: false },
  { id: "language", title: msg.onboarding_language, skippable: false },
  { id: "account", title: msg.onboarding_account, skippable: false },
  { id: "genres", title: msg.onboarding_taste, skippable: false },
  { id: "seen", title: msg.onboarding_seen, skippable: true },
  { id: "rate", title: msg.onboarding_rate, skippable: true },
  { id: "preferences", title: msg.onboarding_preferences, skippable: true },
  { id: "done", title: msg.onboarding_ready, skippable: false },
];

export function langLabel(v: string): string {
  return languageDisplayName(v);
}

export function audioLangLabel(v: string): string {
  return v === "original" ? msg.common_original() : languageDisplayName(v);
}

export interface OnboardingOptions {
  /** The page's `onclose` prop — called once the wizard finishes. */
  onClose: () => void;
}

export class OnboardingController {
  // ── Language ─────────────────────────────────────────────────────────────
  readonly initialUiLanguage = activeLocale();
  selectedUiLanguage = $state<AppLocale>(this.initialUiLanguage);
  languageSaveError = $state(false);

  // ── Navigation ───────────────────────────────────────────────────────────
  stepIndex = $state(0);

  // ── Account step ─────────────────────────────────────────────────────────
  authOpen = $state(false);

  // ── Genre step ───────────────────────────────────────────────────────────
  movieGenres = $state<{ id: number; name: string }[]>([]);
  tvGenres = $state<{ id: number; name: string }[]>([]);
  loadingGenres = $state(false);
  selectedMovieGenreIds = new SvelteSet<number>();
  selectedTvGenreIds = new SvelteSet<number>();
  #genresLanguage: AppLocale | null = null;

  // ── Seen step ────────────────────────────────────────────────────────────
  browseMedia = $state<Media[]>([]);
  mediaQuery = $state("");
  searchResults = $state<Media[]>([]);
  loadingMedia = $state(false);
  seenMedia = $state<Media[]>([]);
  #searchTimer: ReturnType<typeof setTimeout> | null = null;

  // ── Rate step ────────────────────────────────────────────────────────────
  seenEntries = $state<(LibraryEntry | null)[]>([]);
  preparingEntries = $state(false);

  // ── Preferences step ─────────────────────────────────────────────────────
  // Seeded from the store's current value, exactly as the components' $state
  // initialisers did — one-shot, not reactive.
  subtitleLang = $state(settings.getCurrent()?.defaultSubtitleLang ?? "en");
  audioLang = $state(settings.getCurrent()?.defaultAudioLang ?? "en");
  autoPlay = $state(settings.getCurrent()?.autoPlay ?? false);
  rememberPosition = $state(settings.getCurrent()?.rememberPosition ?? true);
  autoSkipIntro = $state(settings.getCurrent()?.autoSkipIntro ?? false);
  autoSkipRecap = $state(settings.getCurrent()?.autoSkipRecap ?? false);
  autoSkipCredits = $state(settings.getCurrent()?.autoSkipCredits ?? false);
  autoSkipPreview = $state(settings.getCurrent()?.autoSkipPreview ?? false);

  nextLoading = $state(false);

  #opts: OnboardingOptions;

  constructor(opts: OnboardingOptions) {
    this.#opts = opts;
  }

  // ── Derived ──────────────────────────────────────────────────────────────

  step = $derived(STEPS[this.stepIndex]);

  // Reading selectedUiLanguage first makes this re-derive when the locale
  // changes, so the paraglide getter is re-invoked under the new language.
  stepTitle = $derived.by(() => {
    this.selectedUiLanguage;
    return this.step.title();
  });

  isFirst = $derived(this.stepIndex === 0);
  isLast = $derived(this.stepIndex === STEPS.length - 1);

  canProceed = $derived(
    this.step.id === "genres"
      ? this.selectedMovieGenreIds.size > 0 || this.selectedTvGenreIds.size > 0
      : true,
  );

  seenIds = $derived.by(
    () =>
      // Transient lookup set rebuilt on every derive.
      // eslint-disable-next-line svelte/prefer-svelte-reactivity
      new Set(this.seenMedia.map((m) => `${m.media_type}-${m.id}`)),
  );

  displayMedia = $derived(
    this.mediaQuery.trim() ? this.searchResults : this.browseMedia,
  );

  // ── Language step ────────────────────────────────────────────────────────

  selectUiLanguage(locale: AppLocale): void {
    this.selectedUiLanguage = locale;
    this.languageSaveError = false;
    activateLocale(locale);
  }

  finishAuthentication(onboardingDone: boolean): void {
    this.authOpen = false;
    if (onboardingDone) this.#opts.onClose();
  }

  // ── Genre step ───────────────────────────────────────────────────────────

  toggleMovieGenre(id: number): void {
    if (this.selectedMovieGenreIds.has(id)) this.selectedMovieGenreIds.delete(id);
    else this.selectedMovieGenreIds.add(id);
  }

  toggleTvGenre(id: number): void {
    if (this.selectedTvGenreIds.has(id)) this.selectedTvGenreIds.delete(id);
    else this.selectedTvGenreIds.add(id);
  }

  async loadGenres(): Promise<void> {
    if (this.#genresLanguage === this.selectedUiLanguage) return;
    this.loadingGenres = true;
    [this.movieGenres, this.tvGenres] = await Promise.all([
      api.genreList("movie").catch(() => []),
      api.genreList("tv").catch(() => []),
    ]);
    this.#genresLanguage = this.selectedUiLanguage;
    this.loadingGenres = false;
  }

  // ── Seen step ────────────────────────────────────────────────────────────

  toggleSeenMedia(m: Media): void {
    const key = `${m.media_type}-${m.id}`;
    if (this.seenIds.has(key)) {
      this.seenMedia = this.seenMedia.filter(
        (x) => `${x.media_type}-${x.id}` !== key,
      );
    } else {
      this.seenMedia = [...this.seenMedia, m];
    }
  }

  onMediaQueryChange(q: string): void {
    this.mediaQuery = q;
    if (this.#searchTimer) clearTimeout(this.#searchTimer);
    if (!q.trim()) {
      this.searchResults = [];
      return;
    }
    this.#searchTimer = setTimeout(async () => {
      this.searchResults = await api.search(q).catch(() => []);
    }, 350);
  }

  async loadBrowseMedia(): Promise<void> {
    this.loadingMedia = true;
    try {
      const movieIds = [...this.selectedMovieGenreIds];
      const tvIds = [...this.selectedTvGenreIds];
      if (movieIds.length === 0 && tvIds.length === 0) {
        const [movies, tv] = await Promise.all([
          api.discover("movie", { limit: 15 }),
          api.discover("tv", { limit: 15 }),
        ]);
        this.browseMedia = [...movies, ...tv];
      } else {
        const results = await Promise.all([
          ...movieIds.map((id) =>
            api.discoverByGenre("movie", id, { limit: 12 }),
          ),
          ...tvIds.map((id) => api.discoverByGenre("tv", id, { limit: 12 })),
        ]);
        const seen = new SvelteSet<string>();
        this.browseMedia = results.flat().filter((m) => {
          const k = `${m.media_type}-${m.id}`;
          if (seen.has(k)) return false;
          seen.add(k);
          return true;
        });
      }
    } catch {
      this.browseMedia = [];
    }
    this.loadingMedia = false;
  }

  // ── Navigation ───────────────────────────────────────────────────────────

  stepIndexFor(id: StepId): number {
    return STEPS.findIndex((candidate) => candidate.id === id);
  }

  async next(): Promise<void> {
    if (this.step.id === "language") {
      this.languageSaveError = !(await settings.save({
        uiLanguage: this.selectedUiLanguage,
      }));
      if (this.languageSaveError) return;
    }
    if (this.step.id === "account") {
      this.languageSaveError = !(await settings.save({
        uiLanguage: this.selectedUiLanguage,
      }));
      if (this.languageSaveError) return;
      await this.loadGenres();
    }
    if (this.step.id === "genres") {
      await this.loadBrowseMedia();
    }
    if (this.step.id === "seen") {
      if (this.seenMedia.length === 0) {
        this.stepIndex = this.stepIndexFor("preferences");
        return;
      }
      this.preparingEntries = true;
      this.seenEntries = await Promise.all(
        this.seenMedia.map((m) =>
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
      this.preparingEntries = false;
    }
    if (this.step.id === "preferences") {
      await settings.save({
        uiLanguage: this.selectedUiLanguage,
        defaultSubtitleLang: this.subtitleLang,
        defaultAudioLang: this.audioLang,
        autoPlay: this.autoPlay,
        rememberPosition: this.rememberPosition,
        autoSkipIntro: this.autoSkipIntro,
        autoSkipRecap: this.autoSkipRecap,
        autoSkipCredits: this.autoSkipCredits,
        autoSkipPreview: this.autoSkipPreview,
      });
    }
    if (this.isLast) {
      const saved = await settings.save({
        onboardingDone: true,
        uiLanguage: this.selectedUiLanguage,
      });
      if (!saved) return;
      if (!auth.isGuest) {
        await api.authSync().catch((error) => {
          console.error("Failed to sync onboarding completion:", error);
        });
      }
      if (this.selectedUiLanguage !== this.initialUiLanguage) {
        window.location.reload();
      } else {
        this.#opts.onClose();
      }
    } else {
      this.stepIndex += 1;
    }
  }

  back(): void {
    if (this.step.id === "preferences" && this.seenMedia.length === 0) {
      // Skipped rating step on the way in — go back to seen step
      this.stepIndex = this.stepIndexFor("seen");
    } else {
      this.stepIndex -= 1;
    }
  }

  skip(): void {
    // Skipping "Seen" with nothing selected also skips "Rate" since it'd be
    // empty
    if (this.step.id === "seen" && this.seenMedia.length === 0) {
      this.stepIndex = this.stepIndexFor("preferences");
    } else {
      this.stepIndex += 1;
    }
  }
}
