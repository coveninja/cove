// $lib/lang.ts
//
// Language-tag normalization. mpv reports track languages from container
// metadata, which is usually ISO 639-2 (three-letter, e.g. "jpn"), while
// Cove's settings/TMDB use ISO 639-1 (two-letter, e.g. "ja"). A naive exact
// match between the two silently fails for almost every non-English title —
// this module normalizes both sides to ISO 639-1 before comparing.
//
// Covers both the bibliographic (B) and terminological (T) 639-2 forms where
// they differ (e.g. "ger"/"deu" for German) — mpv/ffmpeg's actual tagging
// varies by container/muxer, so both need to map to the same 639-1 code.

export const ISO_639_2_TO_1: Record<string, string> = {
  eng: "en",
  spa: "es",
  fre: "fr",
  fra: "fr",
  ger: "de",
  deu: "de",
  por: "pt",
  ita: "it",
  jpn: "ja",
  kor: "ko",
  chi: "zh",
  zho: "zh",
  ara: "ar",
  rus: "ru",
  hin: "hi",
  tur: "tr",
  pol: "pl",
  dut: "nl",
  nld: "nl",
  ces: "cs",
  cze: "cs",
  swe: "sv",
  nor: "no",
  dan: "da",
  fin: "fi",
  ell: "el",
  gre: "el",
  heb: "he",
  tha: "th",
  vie: "vi",
  ind: "id",
  ukr: "uk",
  hun: "hu",
  ron: "ro",
  rum: "ro",
};

/**
 * Normalizes a language tag to a lowercase, region-stripped ISO 639-1 code
 * where possible — "en-US" -> "en", "jpn" -> "ja", "JA" -> "ja". Unknown
 * 3-letter codes and anything else pass through lowercased/region-stripped
 * as a best effort. Returns "" for empty input.
 */
export function normalizeLang(tag: string | null | undefined): string {
  if (!tag) return "";
  // Strip region/script subtags: "pt-BR" -> "pt", "zh-Hans" -> "zh".
  const base = tag.toLowerCase().split(/[-_]/)[0];
  if (base.length === 3) {
    return ISO_639_2_TO_1[base] ?? base;
  }
  return base;
}

/** True if two language tags refer to the same language once normalized to
 * ISO 639-1 (handles 639-1 vs. 639-2, case, and region-subtag mismatches). */
export function langMatches(
  a: string | null | undefined,
  b: string | null | undefined,
): boolean {
  const na = normalizeLang(a);
  const nb = normalizeLang(b);
  return na !== "" && na === nb;
}
