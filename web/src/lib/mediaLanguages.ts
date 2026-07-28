// $lib/mediaLanguages.ts
//
// The audio/subtitle track languages offered in Settings and in Onboarding.
// Both screens (and both of their shells) carried identical copies of these
// two arrays — this is the single definition.
//
// Distinct from $lib/i18n's LOCALES, which are the UI translations the app
// ships. These are the languages a *stream's* tracks may be in; the two lists
// happen to coincide today but are not the same concept.

export const LANGUAGES = [
  { value: "en" },
  { value: "es" },
  { value: "fr" },
  { value: "de" },
  { value: "pt" },
  { value: "it" },
  { value: "ja" },
  { value: "ko" },
  { value: "zh" },
  { value: "ar" },
  { value: "ru" },
];

// Audio-only: "original" plays whatever track matches the title's TMDB
// original_language, instead of a fixed language — see Player.svelte's audio
// auto-select effect. Subtitles have no equivalent concept (TMDB doesn't
// publish an "original subtitle language").
export const AUDIO_LANGUAGES = [{ value: "original" }, ...LANGUAGES];
